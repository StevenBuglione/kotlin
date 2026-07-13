/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

/**
 * A normalized definition in one ownership live range. The operation at [operationIndex] must
 * define [value]. Multiple definitions are allowed; a forwarding operation can therefore be both
 * a use of the old value and a definition of the new value.
 */
internal data class ArcPrunedOwnershipDefinition(
    val value: ArcSSAValue,
    val block: ArcBlockId,
    val operationIndex: Int,
)

/** Swift PrunedLiveness' three-way interesting-user classification. */
internal enum class ArcPrunedOwnershipUseLifetime {
    NonLifetimeEnding,
    LifetimeEnding,
    NonUse,
}

/**
 * An explicitly selected use which contributes to this pruned live range. Phi operands are edge
 * uses, and exit payloads are exit uses. Keeping those distinct avoids inventing a terminator
 * instruction in the emission-independent ownership SSA.
 */
internal sealed class ArcPrunedOwnershipUse {
    abstract val value: ArcSSAValue
    abstract val lifetime: ArcPrunedOwnershipUseLifetime

    data class Operation(
        override val value: ArcSSAValue,
        val block: ArcBlockId,
        val operationIndex: Int,
        override val lifetime: ArcPrunedOwnershipUseLifetime,
        /** True only when the lowered operation transfers control to the block successors. */
        val isTerminator: Boolean = false,
    ) : ArcPrunedOwnershipUse()

    data class Edge(
        override val value: ArcSSAValue,
        val edge: ArcSSAEdge,
        override val lifetime: ArcPrunedOwnershipUseLifetime,
    ) : ArcPrunedOwnershipUse()

    data class Exit(
        override val value: ArcSSAValue,
        val block: ArcBlockId,
        override val lifetime: ArcPrunedOwnershipUseLifetime,
    ) : ArcPrunedOwnershipUse()
}

internal data class ArcPrunedOwnershipLivenessBudget(
    val maximumBlocks: Int = 100_000,
    val maximumEdges: Int = 400_000,
    val maximumDefinitions: Int = 400_000,
    val maximumUses: Int = 800_000,
    val maximumDataflowSteps: Int = 2_000_000,
)

internal data class ArcPrunedOwnershipLivenessInput(
    val cfg: ArcOwnershipSSAInput,
    val definitions: Set<ArcPrunedOwnershipDefinition>,
    val uses: Set<ArcPrunedOwnershipUse>,
    val deadEndBlocks: Set<ArcBlockId> = emptySet(),
    val barriers: Set<ArcSemanticARCBarrier> = emptySet(),
    val budget: ArcPrunedOwnershipLivenessBudget = ArcPrunedOwnershipLivenessBudget(),
    /** Set only after the IR adapter has enumerated the complete selected def/use inventory. */
    val completeInventory: Boolean,
)

/** A stable instruction/edge coordinate which the authenticated IR adapter can bind by identity. */
internal sealed class ArcPrunedOwnershipBoundaryPoint {
    data class AfterOperation(val block: ArcBlockId, val operationIndex: Int) : ArcPrunedOwnershipBoundaryPoint()
    data class AfterDeadDefinition(val definition: ArcPrunedOwnershipDefinition) : ArcPrunedOwnershipBoundaryPoint()

    data class ExistingLifetimeEnd(
        val use: ArcPrunedOwnershipUse,
    ) : ArcPrunedOwnershipBoundaryPoint()

    data class OnEdge(
        val edge: ArcSSAEdge,
        val requiresEdgeSplit: Boolean,
        /** Exceptional cleanup must be emitted in the landing-pad cleanup, not as a normal edge block. */
        val requiresExceptionalCleanup: Boolean,
    ) : ArcPrunedOwnershipBoundaryPoint()

    data class BeforeExit(val block: ArcBlockId) : ArcPrunedOwnershipBoundaryPoint()
}

internal enum class ArcPrunedOwnershipLivenessRejectionReason {
    IncompleteInventory,
    MalformedCFG,
    InvalidDefinition,
    InvalidUse,
    InvalidBarrier,
    BarrierCrossing,
    BudgetExhausted,
    InconsistentBoundary,
}

internal data class ArcPrunedOwnershipLivenessRejection(
    val reason: ArcPrunedOwnershipLivenessRejectionReason,
    val detail: String,
)

/**
 * Immutable pruned-liveness proof. [liveBeforeOperations] and [liveAfterOperations] use the same
 * operation indices as the input CFG. The proof contains no IR objects and cannot mutate code.
 */
internal data class ArcPrunedOwnershipLivenessProof(
    val blockLiveness: Map<ArcBlockId, ArcPrunedBlockLiveness>,
    val liveBeforeOperations: Map<ArcBlockId, List<Boolean>>,
    val liveAfterOperations: Map<ArcBlockId, List<Boolean>>,
    val liveOnEdges: Map<ArcSSAEdge, Boolean>,
    val boundary: Set<ArcPrunedOwnershipBoundaryPoint>,
    val dataflowSteps: Int,
)

internal data class ArcPrunedOwnershipLivenessResult(
    val proof: ArcPrunedOwnershipLivenessProof?,
    val rejection: ArcPrunedOwnershipLivenessRejection?,
) {
    init {
        require((proof == null) != (rejection == null))
    }
}

/**
 * Swift-style multi-definition pruned liveness and lifetime-frontier discovery.
 *
 * The implementation follows `MultiDefPrunedLiveness`: definitions delimit holes in the live
 * range, only selected interesting uses generate liveness, phi uses occur on predecessor edges,
 * and boundary discovery scans definitions before uses at the same operation. It additionally
 * models exceptional cleanup explicitly and fails closed at ownership barriers.
 */
internal object ArcPrunedOwnershipLivenessAnalysis {
    private sealed class Point {
        data class Operation(val block: ArcBlockId, val index: Int) : Point()
        data class Edge(val edge: ArcSSAEdge) : Point()
        data class Exit(val block: ArcBlockId) : Point()
    }

    private data class Dataflow(
        val liveIn: Map<ArcBlockId, Boolean>,
        val liveOut: Map<ArcBlockId, Boolean>,
        val before: Map<ArcBlockId, List<Boolean>>,
        val after: Map<ArcBlockId, List<Boolean>>,
        val edgeLive: Map<ArcSSAEdge, Boolean>,
        val steps: Int,
    )

    fun analyze(input: ArcPrunedOwnershipLivenessInput): ArcPrunedOwnershipLivenessResult {
        fun reject(reason: ArcPrunedOwnershipLivenessRejectionReason, detail: String) =
            ArcPrunedOwnershipLivenessResult(null, ArcPrunedOwnershipLivenessRejection(reason, detail))

        if (!input.completeInventory) {
            return reject(ArcPrunedOwnershipLivenessRejectionReason.IncompleteInventory,
                "the selected definition/use inventory is not sealed")
        }
        if (input.definitions.isEmpty()) {
            return reject(ArcPrunedOwnershipLivenessRejectionReason.InvalidDefinition,
                "a pruned ownership live range requires at least one definition")
        }
        val budget = input.budget
        if (listOf(
                budget.maximumBlocks, budget.maximumEdges, budget.maximumDefinitions,
                budget.maximumUses, budget.maximumDataflowSteps,
            ).any { it < 1 }
        ) {
            return reject(ArcPrunedOwnershipLivenessRejectionReason.BudgetExhausted,
                "all pruned-liveness budgets must be positive")
        }
        if (input.cfg.blocks.size > budget.maximumBlocks || input.cfg.edges.size > budget.maximumEdges ||
            input.definitions.size > budget.maximumDefinitions || input.uses.size > budget.maximumUses
        ) {
            return reject(ArcPrunedOwnershipLivenessRejectionReason.BudgetExhausted,
                "inventory exceeds the configured pruned-liveness budget")
        }
        val malformedCFG = input.cfg.entry !in input.cfg.blocks ||
                input.cfg.blocks.any { (id, block) -> id != block.id } ||
                input.cfg.edges.any { it.from !in input.cfg.blocks || it.to !in input.cfg.blocks } ||
                input.deadEndBlocks.any { it !in input.cfg.blocks }
        if (malformedCFG) {
            return reject(ArcPrunedOwnershipLivenessRejectionReason.MalformedCFG,
                "entry, block identity, edge endpoint, or dead-end inventory is malformed")
        }

        val reachable = reachableBlocks(input.cfg)
        val dominators = computeDominators(input.cfg, reachable)
        val definitionsByValue = input.definitions.groupBy { it.value }
        val definitionsByPoint = input.definitions.groupBy { Point.Operation(it.block, it.operationIndex) }
        val invalidDefinition = input.definitions.firstOrNull { definition ->
            definition.block !in reachable || definitionsByValue[definition.value]?.size != 1 ||
                    input.cfg.blocks[definition.block]?.operations?.getOrNull(definition.operationIndex)
                        ?.prunedResult() != definition.value
        }
        if (invalidDefinition != null) {
            return reject(ArcPrunedOwnershipLivenessRejectionReason.InvalidDefinition,
                "definition $invalidDefinition is unreachable, duplicated, out of bounds, or does not bind its SSA result")
        }

        val operationUses = input.uses.filterIsInstance<ArcPrunedOwnershipUse.Operation>()
        val edgeUses = input.uses.filterIsInstance<ArcPrunedOwnershipUse.Edge>()
        val exitUses = input.uses.filterIsInstance<ArcPrunedOwnershipUse.Exit>()
        val usesByPoint = input.uses.groupBy { it.point() }
        val invalidUse = input.uses.firstOrNull { use ->
            val definition = definitionsByValue[use.value]?.singleOrNull() ?: return@firstOrNull true
            when (use) {
                is ArcPrunedOwnershipUse.Operation -> {
                    val operation = input.cfg.blocks[use.block]?.operations?.getOrNull(use.operationIndex)
                    use.block !in reachable || operation == null ||
                            (use.lifetime != ArcPrunedOwnershipUseLifetime.NonUse && use.value !in operation.prunedOperands()) ||
                            !definitionDominatesOperation(definition, use.block, use.operationIndex, dominators)
                }
                is ArcPrunedOwnershipUse.Edge -> {
                    use.edge !in input.cfg.edges || use.edge.from !in reachable ||
                            (use.lifetime != ArcPrunedOwnershipUseLifetime.NonUse &&
                                    !isModeledEdgeUse(use, input.cfg)) ||
                            !definitionDominatesBlockEnd(definition, use.edge.from, dominators)
                }
                is ArcPrunedOwnershipUse.Exit ->
                    use.block !in reachable || input.cfg.edges.any { it.from == use.block } ||
                            !definitionDominatesBlockEnd(definition, use.block, dominators)
            }
        }
        if (invalidUse != null) {
            return reject(ArcPrunedOwnershipLivenessRejectionReason.InvalidUse,
                "use $invalidUse is unreachable, unbound, out of bounds, or violates SSA dominance")
        }
        val trackedValues = definitionsByValue.keys
        val missingOperationUse = input.cfg.blocks.values.sortedBy { it.id.name }.firstNotNullOfOrNull blockLoop@{ block ->
            block.operations.withIndex().firstNotNullOfOrNull operationLoop@{ (index, operation) ->
                if (operation is ArcSSAOperation.Join) return@operationLoop null
                operation.prunedOperands().filter { it in trackedValues }.firstOrNull { operand ->
                    operationUses.none { selected ->
                        selected.block == block.id && selected.operationIndex == index &&
                                selected.value == operand && selected.lifetime != ArcPrunedOwnershipUseLifetime.NonUse
                    }
                }?.let { operand -> "${block.id}:$index does not inventory the use of $operand" }
            }
        }
        val missingEdgeUse = input.cfg.blocks.values.sortedBy { it.id.name }.firstNotNullOfOrNull blockLoop@{ block ->
            block.operations.filterIsInstance<ArcSSAOperation.Join>().firstNotNullOfOrNull joinLoop@{ join ->
                join.incoming.entries.sortedBy { it.key.name }.firstNotNullOfOrNull incomingLoop@{ (predecessor, operand) ->
                    if (operand !in trackedValues) return@incomingLoop null
                    val edge = input.cfg.edges.singleOrNull {
                        it.from == predecessor && it.to == block.id && it.kind == ArcSSAEdgeKind.Normal
                    } ?: return@incomingLoop "${block.id} has no unique normal phi edge from $predecessor"
                    if (edgeUses.any { selected ->
                            selected.edge == edge && selected.value == operand &&
                                    selected.lifetime != ArcPrunedOwnershipUseLifetime.NonUse
                        }
                    ) null else "$edge does not inventory the phi use of $operand"
                }
            }
        }
        if (missingOperationUse != null || missingEdgeUse != null) {
            return reject(ArcPrunedOwnershipLivenessRejectionReason.IncompleteInventory,
                missingOperationUse ?: missingEdgeUse!!)
        }
        val unsupportedUse = operationUses.firstOrNull { selected ->
            when (val operation = input.cfg.blocks.getValue(selected.block).operations[selected.operationIndex]) {
                is ArcSSAOperation.Use -> operation.kind in setOf(ArcSSAUseKind.Escape, ArcSSAUseKind.UnknownConsume)
                else -> false
            }
        }
        if (unsupportedUse != null) {
            return reject(ArcPrunedOwnershipLivenessRejectionReason.InvalidUse,
                "escape or unknown-consume use $unsupportedUse cannot participate in destroy placement")
        }
        val misclassifiedConsume = operationUses.firstOrNull { selected ->
            val operation = input.cfg.blocks.getValue(selected.block).operations[selected.operationIndex]
            val consumes = operation is ArcSSAOperation.DestroyOwned ||
                    operation is ArcSSAOperation.Use && operation.kind == ArcSSAUseKind.Consume
            consumes && operationUses.none { candidate ->
                candidate.value == selected.value && candidate.block == selected.block &&
                        candidate.operationIndex == selected.operationIndex &&
                        candidate.lifetime == ArcPrunedOwnershipUseLifetime.LifetimeEnding
            }
        }
        if (misclassifiedConsume != null) {
            return reject(ArcPrunedOwnershipLivenessRejectionReason.InvalidUse,
                "consuming use $misclassifiedConsume is not classified as lifetime-ending")
        }
        val ambiguousExceptionalBlock = input.cfg.blocks.values.firstOrNull { block ->
            input.cfg.edges.any { it.from == block.id && it.kind == ArcSSAEdgeKind.Exceptional } &&
                    block.operations.count { it is ArcSSAOperation.Use && it.mayThrow } != 1
        }
        if (ambiguousExceptionalBlock != null) {
            return reject(ArcPrunedOwnershipLivenessRejectionReason.MalformedCFG,
                "exceptional source ${ambiguousExceptionalBlock.id} must contain exactly one modeled throwing use")
        }

        val invalidBarrier = input.barriers.firstOrNull { barrier ->
            val size = input.cfg.blocks[barrier.block]?.operations?.size
            size == null || barrier.operationIndex !in 0..size || barrier.block in input.deadEndBlocks
        }
        if (invalidBarrier != null) {
            return reject(ArcPrunedOwnershipLivenessRejectionReason.InvalidBarrier,
                "barrier $invalidBarrier is out of bounds or belongs to a dead-end block")
        }

        val dataflow = computeDataflow(
            input, definitionsByPoint, usesByPoint, reachable, budget.maximumDataflowSteps,
        ) ?: return reject(ArcPrunedOwnershipLivenessRejectionReason.BudgetExhausted,
            "pruned-liveness fixed point exceeded ${budget.maximumDataflowSteps} dataflow steps")

        val crossingBarrier = input.barriers.sortedWith(compareBy({ it.block.name }, { it.operationIndex }, { it.kind.name }))
            .firstOrNull { barrier ->
                val block = input.cfg.blocks.getValue(barrier.block)
                val liveImmediatelyBefore = when (barrier.operationIndex) {
                    0 -> dataflow.liveIn[barrier.block] == true
                    else -> dataflow.after.getValue(barrier.block)[barrier.operationIndex - 1]
                }
                val liveImmediatelyAfter = when (barrier.operationIndex) {
                    block.operations.size -> dataflow.liveOut[barrier.block] == true ||
                            exitUses.any { it.block == barrier.block }
                    else -> dataflow.before.getValue(barrier.block)[barrier.operationIndex]
                }
                liveImmediatelyBefore && liveImmediatelyAfter
            }
        if (crossingBarrier != null) {
            return reject(ArcPrunedOwnershipLivenessRejectionReason.BarrierCrossing,
                "selected ownership lifetime crosses ${crossingBarrier.kind} at " +
                        "${crossingBarrier.block}:${crossingBarrier.operationIndex}")
        }

        val blockLiveness = input.cfg.blocks.keys.sortedBy { it.name }.associateWithTo(linkedMapOf()) { block ->
            val hasLocalPoint = definitionsByPoint.keys.any { it.block == block } || usesByPoint.keys.any {
                when (it) {
                    is Point.Operation -> it.block == block
                    is Point.Edge -> it.edge.from == block
                    is Point.Exit -> it.block == block
                }
            }
            when {
                dataflow.liveOut[block] == true -> ArcPrunedBlockLiveness.LiveOut
                dataflow.liveIn[block] == true || hasLocalPoint ||
                        dataflow.before[block].orEmpty().any { it } || dataflow.after[block].orEmpty().any { it } ->
                    ArcPrunedBlockLiveness.LiveWithin
                else -> ArcPrunedBlockLiveness.Dead
            }
        }

        val rawBoundary = discoverBoundary(
            input, blockLiveness, definitionsByPoint, usesByPoint, operationUses, edgeUses, exitUses,
        ) ?: return reject(ArcPrunedOwnershipLivenessRejectionReason.InconsistentBoundary,
            "a live-within block has no selected last use or dead definition")
        val boundary = materializeBoundary(input, rawBoundary)
        val nonDeadReachableExits = reachable.filter { block ->
            block !in input.deadEndBlocks && input.cfg.edges.none { it.from == block }
        }
        if (boundary.isEmpty() && nonDeadReachableExits.isNotEmpty()) {
            return reject(ArcPrunedOwnershipLivenessRejectionReason.InconsistentBoundary,
                "the live range has no lifetime frontier outside dead-end blocks")
        }
        return ArcPrunedOwnershipLivenessResult(
            ArcPrunedOwnershipLivenessProof(
                blockLiveness,
                dataflow.before,
                dataflow.after,
                dataflow.edgeLive,
                boundary,
                dataflow.steps,
            ),
            null,
        )
    }

    private fun computeDataflow(
        input: ArcPrunedOwnershipLivenessInput,
        definitionsByPoint: Map<Point.Operation, List<ArcPrunedOwnershipDefinition>>,
        usesByPoint: Map<Point, List<ArcPrunedOwnershipUse>>,
        reachable: Set<ArcBlockId>,
        maximumSteps: Int,
    ): Dataflow? {
        var liveIn = input.cfg.blocks.keys.associateWith { false }
        var liveOut = liveIn
        var edgeLive = input.cfg.edges.associateWith { false }
        var before = input.cfg.blocks.mapValues { (_, block) -> List(block.operations.size) { false } }
        var after = before
        var steps = 0
        while (true) {
            val nextEdgeLive = input.cfg.edges.sortedWith(edgeComparator).associateWithTo(linkedMapOf()) { edge ->
                if (++steps > maximumSteps) return null
                edge.from in reachable && edge.to in reachable &&
                        (liveIn[edge.to] == true || usesByPoint[Point.Edge(edge)].orEmpty().isNotEmpty())
            }
            val nextLiveOut = input.cfg.blocks.keys.sortedBy { it.name }.associateWithTo(linkedMapOf()) { block ->
                if (++steps > maximumSteps) return null
                input.cfg.edges.any { it.from == block && nextEdgeLive[it] == true }
            }
            val nextBefore = linkedMapOf<ArcBlockId, List<Boolean>>()
            val nextAfter = linkedMapOf<ArcBlockId, List<Boolean>>()
            val nextLiveIn = linkedMapOf<ArcBlockId, Boolean>()
            input.cfg.blocks.values.sortedByDescending { it.id.name }.forEach { block ->
                var live = nextLiveOut[block.id] == true || usesByPoint[Point.Exit(block.id)].orEmpty().isNotEmpty()
                val blockBefore = MutableList(block.operations.size) { false }
                val blockAfter = MutableList(block.operations.size) { false }
                block.operations.indices.reversed().forEach { index ->
                    if (++steps > maximumSteps) return null
                    blockAfter[index] = live
                    val point = Point.Operation(block.id, index)
                    if (definitionsByPoint[point].orEmpty().isNotEmpty()) live = false
                    if (usesByPoint[point].orEmpty().isNotEmpty()) live = true
                    blockBefore[index] = live
                }
                nextBefore[block.id] = blockBefore
                nextAfter[block.id] = blockAfter
                nextLiveIn[block.id] = live
            }
            if (nextLiveIn == liveIn && nextLiveOut == liveOut && nextEdgeLive == edgeLive &&
                nextBefore == before && nextAfter == after
            ) {
                return Dataflow(nextLiveIn, nextLiveOut, nextBefore, nextAfter, nextEdgeLive, steps)
            }
            liveIn = nextLiveIn
            liveOut = nextLiveOut
            edgeLive = nextEdgeLive
            before = nextBefore
            after = nextAfter
        }
    }

    /** Boundary items before they are converted into concrete insertion requirements. */
    private sealed class RawBoundary {
        data class LastUse(val uses: List<ArcPrunedOwnershipUse>) : RawBoundary()
        data class DeadDefinition(val definition: ArcPrunedOwnershipDefinition) : RawBoundary()
        data class Edge(val edge: ArcSSAEdge) : RawBoundary()
    }

    private fun discoverBoundary(
        input: ArcPrunedOwnershipLivenessInput,
        blockLiveness: Map<ArcBlockId, ArcPrunedBlockLiveness>,
        definitionsByPoint: Map<Point.Operation, List<ArcPrunedOwnershipDefinition>>,
        usesByPoint: Map<Point, List<ArcPrunedOwnershipUse>>,
        operationUses: List<ArcPrunedOwnershipUse.Operation>,
        edgeUses: List<ArcPrunedOwnershipUse.Edge>,
        exitUses: List<ArcPrunedOwnershipUse.Exit>,
    ): Set<RawBoundary>? {
        val boundary = linkedSetOf<RawBoundary>()
        input.cfg.blocks.values.sortedBy { it.id.name }.forEach { block ->
            val state = blockLiveness.getValue(block.id)
            if (state == ArcPrunedBlockLiveness.Dead) return@forEach
            val blockDefinitions = input.definitions.filter { it.block == block.id }
            if (blockDefinitions.isEmpty()) {
                if (state == ArcPrunedBlockLiveness.LiveOut) {
                    input.cfg.edges.filter { it.from == block.id }.sortedWith(edgeComparator).forEach { edge ->
                        if (blockLiveness[edge.to] == ArcPrunedBlockLiveness.Dead) boundary += RawBoundary.Edge(edge)
                    }
                } else {
                    val last = lastUsesInBlock(block.id, operationUses, edgeUses, exitUses)
                    if (last.isEmpty()) return null
                    boundary += RawBoundary.LastUse(last)
                }
                return@forEach
            }

            var live = state == ArcPrunedBlockLiveness.LiveOut
            val terminatorUses = buildList<ArcPrunedOwnershipUse> {
                addAll(edgeUses.filter { it.edge.from == block.id })
                addAll(exitUses.filter { it.block == block.id })
                addAll(operationUses.filter { it.block == block.id && it.isTerminator })
            }
            if (!live && terminatorUses.isNotEmpty()) {
                boundary += RawBoundary.LastUse(terminatorUses.sortedWith(useComparator))
                live = true
            }
            block.operations.indices.reversed().forEach { index ->
                val point = Point.Operation(block.id, index)
                val operationDefinitions = definitionsByPoint[point].orEmpty().filterNot { definition ->
                    input.cfg.blocks.getValue(definition.block).operations[definition.operationIndex] is ArcSSAOperation.Join
                }
                operationDefinitions.sortedBy { it.value.name }.forEach { definition ->
                    if (!live) boundary += RawBoundary.DeadDefinition(definition)
                    live = false
                }
                val localUses = usesByPoint[point].orEmpty().filterNot {
                    it is ArcPrunedOwnershipUse.Operation && it.isTerminator
                }
                if (!live && localUses.isNotEmpty()) {
                    boundary += RawBoundary.LastUse(localUses.sortedWith(useComparator))
                    live = true
                }
            }
            val entryJoinDefinitions = blockDefinitions.filter { definition ->
                input.cfg.blocks.getValue(definition.block).operations[definition.operationIndex] is ArcSSAOperation.Join
            }
            if (!live) entryJoinDefinitions.sortedBy { it.value.name }.forEach { definition ->
                boundary += RawBoundary.DeadDefinition(definition)
            }
            if (!live) {
                input.cfg.edges.filter { it.to == block.id }.sortedWith(edgeComparator).forEach { edge ->
                    if (blockLiveness[edge.from] == ArcPrunedBlockLiveness.LiveOut) boundary += RawBoundary.Edge(edge)
                }
            }
            if (state == ArcPrunedBlockLiveness.LiveWithin && boundary.none { it.belongsTo(block.id) }) return null
        }
        return boundary
    }

    private fun materializeBoundary(
        input: ArcPrunedOwnershipLivenessInput,
        rawBoundary: Set<RawBoundary>,
    ): Set<ArcPrunedOwnershipBoundaryPoint> = buildSet {
        fun addEdge(edge: ArcSSAEdge) {
            if (edge.from in input.deadEndBlocks || edge.to in input.deadEndBlocks) return
            add(ArcPrunedOwnershipBoundaryPoint.OnEdge(
                edge,
                requiresEdgeSplit = edge.kind == ArcSSAEdgeKind.Normal && isCritical(edge, input.cfg),
                requiresExceptionalCleanup = edge.kind == ArcSSAEdgeKind.Exceptional,
            ))
        }
        rawBoundary.sortedWith(rawBoundaryComparator).forEach { raw ->
            when (raw) {
                is RawBoundary.Edge -> addEdge(raw.edge)
                is RawBoundary.DeadDefinition -> if (raw.definition.block !in input.deadEndBlocks) {
                    add(ArcPrunedOwnershipBoundaryPoint.AfterDeadDefinition(raw.definition))
                }
                is RawBoundary.LastUse -> {
                    val uses = raw.uses
                    val representative = uses.minWithOrNull(useComparator) ?: return@forEach
                    val lifetime = meetLifetime(uses)
                    if (lifetime == ArcPrunedOwnershipUseLifetime.LifetimeEnding) {
                        add(ArcPrunedOwnershipBoundaryPoint.ExistingLifetimeEnd(representative))
                        if (representative is ArcPrunedOwnershipUse.Operation) {
                            val operation = input.cfg.blocks.getValue(representative.block)
                                .operations[representative.operationIndex]
                            if (operation is ArcSSAOperation.Use && operation.mayThrow) {
                                input.cfg.edges.filter {
                                    it.from == representative.block && it.kind == ArcSSAEdgeKind.Exceptional
                                }.sortedWith(edgeComparator).forEach(::addEdge)
                            }
                        }
                    } else when (representative) {
                        is ArcPrunedOwnershipUse.Operation -> {
                            if (representative.block in input.deadEndBlocks) return@forEach
                            if (representative.isTerminator) {
                                input.cfg.edges.filter { it.from == representative.block }
                                    .sortedWith(edgeComparator).forEach(::addEdge)
                            } else {
                                add(ArcPrunedOwnershipBoundaryPoint.AfterOperation(
                                    representative.block, representative.operationIndex,
                                ))
                                val operation = input.cfg.blocks.getValue(representative.block)
                                    .operations[representative.operationIndex]
                                if (operation is ArcSSAOperation.Use && operation.mayThrow) {
                                    input.cfg.edges.filter {
                                        it.from == representative.block && it.kind == ArcSSAEdgeKind.Exceptional
                                    }.sortedWith(edgeComparator).forEach(::addEdge)
                                }
                            }
                        }
                        is ArcPrunedOwnershipUse.Edge -> addEdge(representative.edge)
                        is ArcPrunedOwnershipUse.Exit -> if (representative.block !in input.deadEndBlocks) {
                            add(ArcPrunedOwnershipBoundaryPoint.BeforeExit(representative.block))
                        }
                    }
                }
            }
        }
    }

    private fun lastUsesInBlock(
        block: ArcBlockId,
        operationUses: List<ArcPrunedOwnershipUse.Operation>,
        edgeUses: List<ArcPrunedOwnershipUse.Edge>,
        exitUses: List<ArcPrunedOwnershipUse.Exit>,
    ): List<ArcPrunedOwnershipUse> {
        val terminator = buildList<ArcPrunedOwnershipUse> {
            addAll(edgeUses.filter { it.edge.from == block })
            addAll(exitUses.filter { it.block == block })
            addAll(operationUses.filter { it.block == block && it.isTerminator })
        }
        if (terminator.isNotEmpty()) return terminator.sortedWith(useComparator)
        val lastIndex = operationUses.filter { it.block == block }.maxOfOrNull { it.operationIndex } ?: return emptyList()
        return operationUses.filter { it.block == block && it.operationIndex == lastIndex }.sortedWith(useComparator)
    }

    private fun meetLifetime(uses: List<ArcPrunedOwnershipUse>): ArcPrunedOwnershipUseLifetime {
        val isTerminatorPoint = uses.any { it is ArcPrunedOwnershipUse.Edge || it is ArcPrunedOwnershipUse.Exit ||
                it is ArcPrunedOwnershipUse.Operation && it.isTerminator }
        val order = if (isTerminatorPoint) {
            listOf(
                ArcPrunedOwnershipUseLifetime.LifetimeEnding,
                ArcPrunedOwnershipUseLifetime.NonLifetimeEnding,
                ArcPrunedOwnershipUseLifetime.NonUse,
            )
        } else {
            listOf(
                ArcPrunedOwnershipUseLifetime.NonLifetimeEnding,
                ArcPrunedOwnershipUseLifetime.LifetimeEnding,
                ArcPrunedOwnershipUseLifetime.NonUse,
            )
        }
        return uses.minByOrNull { order.indexOf(it.lifetime) }?.lifetime
            ?: ArcPrunedOwnershipUseLifetime.NonUse
    }

    private fun ArcPrunedOwnershipUse.point(): Point = when (this) {
        is ArcPrunedOwnershipUse.Operation -> Point.Operation(block, operationIndex)
        is ArcPrunedOwnershipUse.Edge -> Point.Edge(edge)
        is ArcPrunedOwnershipUse.Exit -> Point.Exit(block)
    }

    private fun isModeledEdgeUse(use: ArcPrunedOwnershipUse.Edge, cfg: ArcOwnershipSSAInput): Boolean {
        if (use.edge.kind != ArcSSAEdgeKind.Normal) return false
        return cfg.blocks.getValue(use.edge.to).operations.filterIsInstance<ArcSSAOperation.Join>()
            .any { join -> join.incoming[use.edge.from] == use.value }
    }

    private fun definitionDominatesOperation(
        definition: ArcPrunedOwnershipDefinition,
        block: ArcBlockId,
        operationIndex: Int,
        dominators: Map<ArcBlockId, Set<ArcBlockId>>,
    ): Boolean = definition.block in dominators[block].orEmpty() &&
            (definition.block != block || definition.operationIndex < operationIndex)

    private fun definitionDominatesBlockEnd(
        definition: ArcPrunedOwnershipDefinition,
        block: ArcBlockId,
        dominators: Map<ArcBlockId, Set<ArcBlockId>>,
    ): Boolean = definition.block in dominators[block].orEmpty()

    private fun reachableBlocks(cfg: ArcOwnershipSSAInput): Set<ArcBlockId> {
        val reachable = linkedSetOf<ArcBlockId>()
        if (cfg.entry !in cfg.blocks) return reachable
        val worklist = ArrayDeque<ArcBlockId>().apply { add(cfg.entry) }
        while (worklist.isNotEmpty()) {
            val block = worklist.removeFirst()
            if (!reachable.add(block)) continue
            cfg.edges.filter { it.from == block }.sortedWith(edgeComparator).forEach { worklist += it.to }
        }
        return reachable
    }

    private fun computeDominators(
        cfg: ArcOwnershipSSAInput,
        reachable: Set<ArcBlockId>,
    ): Map<ArcBlockId, Set<ArcBlockId>> {
        val result = reachable.associateWithTo(linkedMapOf()) { if (it == cfg.entry) setOf(it) else reachable.toSet() }
        var changed = true
        while (changed) {
            changed = false
            reachable.filter { it != cfg.entry }.sortedBy { it.name }.forEach { block ->
                val predecessors = cfg.edges.filter { it.to == block && it.from in reachable }.map { it.from }
                val next = if (predecessors.isEmpty()) setOf(block) else
                    predecessors.map { result.getValue(it) }.reduce { left, right -> left intersect right } + block
                if (result[block] != next) {
                    result[block] = next
                    changed = true
                }
            }
        }
        return result
    }

    private fun isCritical(edge: ArcSSAEdge, cfg: ArcOwnershipSSAInput): Boolean =
        cfg.edges.count { it.from == edge.from } > 1 && cfg.edges.count { it.to == edge.to } > 1

    private fun ArcSSAOperation.prunedResult(): ArcSSAValue? = when (this) {
        is ArcSSAOperation.Introduce -> result
        is ArcSSAOperation.Forward -> result
        is ArcSSAOperation.Reborrow -> result
        is ArcSSAOperation.Join -> result
        is ArcSSAOperation.Borrow -> result
        else -> null
    }

    private fun ArcSSAOperation.prunedOperands(): List<ArcSSAValue> = when (this) {
        is ArcSSAOperation.Introduce -> anchorDependencies.toList()
        is ArcSSAOperation.Forward -> listOf(source)
        is ArcSSAOperation.Reborrow -> listOf(source) + anchorDependencies
        is ArcSSAOperation.Join -> incoming.values.toList()
        is ArcSSAOperation.InitializeOwned -> listOf(value)
        is ArcSSAOperation.InitializeImmortal -> listOf(value)
        is ArcSSAOperation.JoinSlot -> listOf(value)
        is ArcSSAOperation.Borrow -> listOf(source)
        is ArcSSAOperation.MoveOwned -> listOf(value)
        is ArcSSAOperation.DestroyOwned -> listOf(value)
        is ArcSSAOperation.Use -> listOf(value)
        is ArcSSAOperation.EndBorrow, is ArcSSAOperation.DeinitBarrier -> emptyList()
    }

    private fun RawBoundary.belongsTo(block: ArcBlockId): Boolean = when (this) {
        is RawBoundary.LastUse -> uses.any {
            when (it) {
                is ArcPrunedOwnershipUse.Operation -> it.block == block
                is ArcPrunedOwnershipUse.Edge -> it.edge.from == block
                is ArcPrunedOwnershipUse.Exit -> it.block == block
            }
        }
        is RawBoundary.DeadDefinition -> definition.block == block
        is RawBoundary.Edge -> edge.from == block || edge.to == block
    }

    private val edgeComparator = compareBy<ArcSSAEdge>({ it.from.name }, { it.to.name }, { it.kind.name })
    private val useComparator = compareBy<ArcPrunedOwnershipUse>(
        { use ->
            when (use) {
                is ArcPrunedOwnershipUse.Operation -> use.block.name
                is ArcPrunedOwnershipUse.Edge -> use.edge.from.name
                is ArcPrunedOwnershipUse.Exit -> use.block.name
            }
        },
        { use -> if (use is ArcPrunedOwnershipUse.Operation) use.operationIndex else Int.MAX_VALUE },
        { it.value.name },
        { it::class.simpleName.orEmpty() },
    )
    private val rawBoundaryComparator = compareBy<RawBoundary>({ it::class.simpleName.orEmpty() }, { it.toString() })
}
