/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

/**
 * Emission-independent ownership SSA for joined reference values.
 *
 * This is intentionally a closed model rather than an LLVM or IR rewrite. The IR adapter is
 * expected to describe an acyclic lowered `IrWhen` diamond using identity-stable [ArcSSAValue]s,
 * run this analysis, and only later teach codegen how to consume [ArcOwnershipSSAWeb]. Keeping the
 * proof object independent lets the existing path verifier remain fail-closed until that seam is
 * wired.
 */
@JvmInline
internal value class ArcSSAValue(val name: String) {
    override fun toString(): String = "%$name"
}

internal enum class ArcSSAEdgeKind { Normal, Exceptional }

internal data class ArcSSAEdge(
    val from: ArcBlockId,
    val to: ArcBlockId,
    val kind: ArcSSAEdgeKind = ArcSSAEdgeKind.Normal,
)

/** The ownership effects which must be classified before a joined web can be converted. */
internal enum class ArcSSAUseKind {
    Borrow,
    Consume,
    Escape,
    UnknownConsume,
}

internal sealed class ArcSSAOperation {
    abstract val location: ArcPlanLocation?

    data class Introduce(
        val result: ArcSSAValue,
        val ownership: ArcOwnership,
        /** Required for guaranteed introducers; ignored for owned and immortal introducers. */
        val anchorDependencies: Set<ArcSSAValue> = emptySet(),
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    data class Forward(
        val source: ArcSSAValue,
        val result: ArcSSAValue,
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    /** Produces a guaranteed projection whose complete dependency set is explicit. */
    data class Reborrow(
        val source: ArcSSAValue,
        val result: ArcSSAValue,
        val anchorDependencies: Set<ArcSSAValue>,
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    /** A phi-like definition emitted by an acyclic lowered `IrWhen` merge. */
    data class Join(
        val result: ArcSSAValue,
        val incoming: Map<ArcBlockId, ArcSSAValue>,
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    data class Use(
        val value: ArcSSAValue,
        val kind: ArcSSAUseKind,
        /** The exceptional successor observes the pre-use ownership state. */
        val mayThrow: Boolean = false,
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    /** A destroy may not be sunk across user deinitialization or runtime finalization. */
    data class DeinitBarrier(override val location: ArcPlanLocation? = null) : ArcSSAOperation()
}

internal data class ArcSSABlock(
    val id: ArcBlockId,
    val operations: List<ArcSSAOperation>,
)

/**
 * Frozen adapter boundary for the first ownership-SSA integration.
 *
 * [blocks] and [edges] must describe a finite CFG. Candidate discovery currently accepts only
 * canonical two-arm, acyclic `IrWhen` diamonds and deliberately rejects loop-carried or critical
 * join shapes.
 */
internal data class ArcOwnershipSSAInput(
    val entry: ArcBlockId,
    val blocks: Map<ArcBlockId, ArcSSABlock>,
    val edges: Set<ArcSSAEdge>,
)

internal sealed class ArcSSADestroyPlacement {
    data class BeforeOperation(val block: ArcBlockId, val operationIndex: Int) : ArcSSADestroyPlacement()
    data class BeforeExit(val block: ArcBlockId) : ArcSSADestroyPlacement()
    data class OnEdge(val edge: ArcSSAEdge) : ArcSSADestroyPlacement()
}

internal enum class ArcOwnershipSSARejectionReason {
    MALFORMED_CFG,
    NOT_ACYCLIC_WHEN_DIAMOND,
    INCOMPLETE_WEB,
    MIXED_INCOMING_OWNERSHIP,
    INCOMPATIBLE_ANCHOR_DEPENDENCIES,
    ANCHOR_DOES_NOT_DOMINATE_REBORROW,
    ESCAPE,
    UNKNOWN_CONSUME,
    MULTIPLE_CONSUME,
    CRITICAL_EDGE_DESTROY,
    INVALID_SSA_DOMINANCE,
    AMBIGUOUS_EXCEPTIONAL_CLEANUP,
    AMBIGUOUS_DESTROY_PLACEMENT,
    OVERLAPPING_WEB,
}

internal data class ArcOwnershipSSARejection(
    val join: ArcSSAValue,
    val reason: ArcOwnershipSSARejectionReason,
    val detail: String,
    val location: ArcPlanLocation? = null,
)

/** The immutable proof record which a later codegen integration is allowed to consume. */
internal data class ArcOwnershipSSAWeb(
    val join: ArcSSAValue,
    val mergeBlock: ArcBlockId,
    val ownership: ArcOwnership,
    val members: Set<ArcSSAValue>,
    /** Canonical introducer identities; equality is the guaranteed-lifetime join rule. */
    val anchorDependencies: Set<ArcSSAValue>,
    val destroyPlacements: Set<ArcSSADestroyPlacement>,
)

internal data class ArcOwnershipSSAResult(
    val accepted: List<ArcOwnershipSSAWeb>,
    val rejected: List<ArcOwnershipSSARejection>,
)

internal object ArcOwnershipSSAAnalysis {
    private data class Definition(val block: ArcBlockId, val index: Int, val operation: ArcSSAOperation)
    private data class Diamond(
        val header: ArcBlockId,
        val trueBlock: ArcBlockId,
        val falseBlock: ArcBlockId,
        val mergeBlock: ArcBlockId,
    )

    fun analyze(input: ArcOwnershipSSAInput): ArcOwnershipSSAResult {
        val definitions = linkedMapOf<ArcSSAValue, Definition>()
        val duplicateDefinitions = mutableSetOf<ArcSSAValue>()
        input.blocks.values.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                operation.resultOrNull()?.let { result ->
                    if (definitions.put(result, Definition(block.id, index, operation)) != null) {
                        duplicateDefinitions += result
                    }
                }
            }
        }

        val joins = definitions.values.mapNotNull { definition ->
            (definition.operation as? ArcSSAOperation.Join)?.let { it to definition }
        }
        val rejected = mutableListOf<ArcOwnershipSSARejection>()
        val accepted = mutableListOf<ArcOwnershipSSAWeb>()
        val malformed = input.entry !in input.blocks || input.edges.any { it.from !in input.blocks || it.to !in input.blocks }

        joins.forEach { (join, definition) ->
            if (malformed || join.result in duplicateDefinitions) {
                rejected += rejection(join, ArcOwnershipSSARejectionReason.MALFORMED_CFG, "missing block or duplicate SSA definition")
                return@forEach
            }
            val diamond = discoverDiamond(input, definition.block, join)
            if (diamond == null) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.NOT_ACYCLIC_WHEN_DIAMOND,
                    "join is not the merge of one canonical two-arm acyclic IrWhen diamond",
                )
                return@forEach
            }

            val members = discoverCompleteWeb(join.result, definitions, input.blocks)
            if (members == null) {
                rejected += rejection(join, ArcOwnershipSSARejectionReason.INCOMPLETE_WEB, "web contains an undefined or cyclic forwarding value")
                return@forEach
            }

            if (members.any { it in duplicateDefinitions }) {
                rejected += rejection(join, ArcOwnershipSSARejectionReason.MALFORMED_CFG, "web contains a duplicate SSA definition")
                return@forEach
            }

            val dominators = computeDominators(input)
            if (!hasValidDominance(input, members, definitions, dominators)) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.INVALID_SSA_DOMINANCE,
                    "a web definition does not dominate one of its uses or incoming edges",
                )
                return@forEach
            }

            val incomingOwnerships = join.incoming.values.map { inferOwnership(it, definitions, mutableSetOf()) }
            val ownerships = incomingOwnerships.filterNotNull().toSet()
            if (incomingOwnerships.any { it == null } || ownerships.size != 1) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.MIXED_INCOMING_OWNERSHIP,
                    "incoming ownerships are ${if (ownerships.isEmpty()) "unknown" else ownerships.joinToString()}",
                )
                return@forEach
            }
            val ownership = ownerships.single()
            val dependencySets = join.incoming.values.map { canonicalDependencies(it, definitions, mutableSetOf()) }
            if (dependencySets.any { it == null } || dependencySets.filterNotNull().distinct().size != 1) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.INCOMPATIBLE_ANCHOR_DEPENDENCIES,
                    "incoming guaranteed values do not have one canonical anchor dependency set",
                )
                return@forEach
            }
            val dependencies = dependencySets.firstOrNull().orEmpty()
            if (ownership == ArcOwnership.Guaranteed && dependencies.isEmpty()) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.INCOMPATIBLE_ANCHOR_DEPENDENCIES,
                    "guaranteed web has no lifetime anchor",
                )
                return@forEach
            }

            val invalidReborrow = members.asSequence()
                .mapNotNull { definitions[it] }
                .mapNotNull { definitionOfMember ->
                    (definitionOfMember.operation as? ArcSSAOperation.Reborrow)?.let { definitionOfMember to it }
                }
                .firstOrNull { (reborrowDefinition, reborrow) ->
                    reborrow.anchorDependencies.any { anchor ->
                        val anchorDefinition = definitions[canonicalAnchor(anchor, definitions, mutableSetOf())]
                        anchorDefinition == null || anchorDefinition.block !in dominators.getValue(reborrowDefinition.block) ||
                                (anchorDefinition.block == reborrowDefinition.block &&
                                        anchorDefinition.index >= reborrowDefinition.index)
                    }
                }
            if (invalidReborrow != null) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.ANCHOR_DOES_NOT_DOMINATE_REBORROW,
                    "a reborrow anchor does not dominate ${invalidReborrow.first.block}",
                    invalidReborrow.second.location,
                )
                return@forEach
            }

            val uses = classifiedUses(input, members)
            val forbiddenUse = uses.firstOrNull { it.third.kind == ArcSSAUseKind.Escape || it.third.kind == ArcSSAUseKind.UnknownConsume }
            if (forbiddenUse != null) {
                val reason = if (forbiddenUse.third.kind == ArcSSAUseKind.Escape) {
                    ArcOwnershipSSARejectionReason.ESCAPE
                } else {
                    ArcOwnershipSSARejectionReason.UNKNOWN_CONSUME
                }
                rejected += rejection(join, reason, "${forbiddenUse.third.kind} of ${forbiddenUse.third.value}", forbiddenUse.third.location)
                return@forEach
            }
            if (uses.count { it.third.kind == ArcSSAUseKind.Consume } > 1) {
                rejected += rejection(join, ArcOwnershipSSARejectionReason.MULTIPLE_CONSUME, "web has more than one consuming use")
                return@forEach
            }

            if (!hasUnambiguousExceptionalCleanup(input, members, uses)) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.AMBIGUOUS_EXCEPTIONAL_CLEANUP,
                    "throwing use is not uniquely associated with one dead-on-entry exceptional edge",
                )
                return@forEach
            }

            val placements = if (ownership == ArcOwnership.Owned) {
                computeDestroyPlacements(input, diamond, members, uses)
            } else emptySet()
            if (placements == null) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.AMBIGUOUS_DESTROY_PLACEMENT,
                    "no single destroy placement covers every normal path exactly once",
                )
                return@forEach
            }
            val critical = placements.filterIsInstance<ArcSSADestroyPlacement.OnEdge>()
                .firstOrNull { isCritical(it.edge, input) }
            if (critical != null) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.CRITICAL_EDGE_DESTROY,
                    "destroy requires unsplit critical edge ${critical.edge.from} -> ${critical.edge.to}",
                )
                return@forEach
            }

            accepted += ArcOwnershipSSAWeb(
                join = join.result,
                mergeBlock = diamond.mergeBlock,
                ownership = ownership,
                members = members,
                anchorDependencies = dependencies,
                destroyPlacements = placements,
            )
        }
        val overlapping = accepted.filter { candidate ->
            accepted.any { other -> other !== candidate && candidate.members.any { it in other.members } }
        }
        if (overlapping.isNotEmpty()) {
            accepted.removeAll(overlapping.toSet())
            overlapping.forEach { web ->
                rejected += ArcOwnershipSSARejection(
                    web.join,
                    ArcOwnershipSSARejectionReason.OVERLAPPING_WEB,
                    "ownership web overlaps another accepted join proof",
                )
            }
        }
        return ArcOwnershipSSAResult(accepted, rejected)
    }

    private fun discoverDiamond(input: ArcOwnershipSSAInput, merge: ArcBlockId, join: ArcSSAOperation.Join): Diamond? {
        if (join.incoming.size != 2 || join.incoming.keys.toSet() != input.predecessors(merge, ArcSSAEdgeKind.Normal).toSet()) return null
        val arms = join.incoming.keys.toList()
        if (arms.any { input.successors(it, ArcSSAEdgeKind.Normal) != listOf(merge) }) return null
        val commonHeaders = input.predecessors(arms[0], ArcSSAEdgeKind.Normal).toSet()
            .intersect(input.predecessors(arms[1], ArcSSAEdgeKind.Normal).toSet())
        val header = commonHeaders.singleOrNull() ?: return null
        if (input.successors(header, ArcSSAEdgeKind.Normal).toSet() != arms.toSet()) return null
        if (!isAcyclic(input)) return null
        return Diamond(header, arms[0], arms[1], merge)
    }

    /** Backward and forward closure is what prevents partial phi-web conversion. */
    private fun discoverCompleteWeb(
        root: ArcSSAValue,
        definitions: Map<ArcSSAValue, Definition>,
        blocks: Map<ArcBlockId, ArcSSABlock>,
    ): Set<ArcSSAValue>? {
        val members = linkedSetOf(root)
        var changed = true
        while (changed) {
            changed = false
            for (value in members.toList()) {
                val operation = definitions[value]?.operation ?: return null
                val dependencies = when (operation) {
                    is ArcSSAOperation.Introduce -> emptyList()
                    is ArcSSAOperation.Forward -> listOf(operation.source)
                    // A reborrow introduces a new guaranteed value. Its source and anchors are
                    // lifetime dependencies, not ownership-equivalent members of this phi web.
                    is ArcSSAOperation.Reborrow -> emptyList()
                    is ArcSSAOperation.Join -> operation.incoming.values.toList()
                    else -> return null
                }
                dependencies.forEach { if (members.add(it)) changed = true }
            }
            blocks.values.forEach { block ->
                block.operations.forEach { operation ->
                    when (operation) {
                        is ArcSSAOperation.Forward -> if (operation.source in members && members.add(operation.result)) changed = true
                        is ArcSSAOperation.Reborrow -> Unit
                        is ArcSSAOperation.Join -> if (operation.incoming.values.any { it in members }) {
                            if (members.add(operation.result)) changed = true
                            operation.incoming.values.forEach { if (members.add(it)) changed = true }
                        }
                        else -> Unit
                    }
                }
            }
        }
        return members
    }

    private fun inferOwnership(
        value: ArcSSAValue,
        definitions: Map<ArcSSAValue, Definition>,
        visiting: MutableSet<ArcSSAValue>,
    ): ArcOwnership? {
        if (!visiting.add(value)) return null
        val result = when (val operation = definitions[value]?.operation) {
            is ArcSSAOperation.Introduce -> operation.ownership
            is ArcSSAOperation.Forward -> inferOwnership(operation.source, definitions, visiting)
            is ArcSSAOperation.Reborrow -> ArcOwnership.Guaranteed
            is ArcSSAOperation.Join -> operation.incoming.values.mapNotNull { inferOwnership(it, definitions, visiting.toMutableSet()) }
                .toSet().singleOrNull()
            else -> null
        }
        visiting.remove(value)
        return result
    }

    private fun canonicalDependencies(
        value: ArcSSAValue,
        definitions: Map<ArcSSAValue, Definition>,
        visiting: MutableSet<ArcSSAValue>,
    ): Set<ArcSSAValue>? {
        if (!visiting.add(value)) return null
        val result = when (val operation = definitions[value]?.operation) {
            is ArcSSAOperation.Introduce -> if (operation.ownership == ArcOwnership.Guaranteed) {
                operation.anchorDependencies.mapTo(linkedSetOf()) { canonicalAnchor(it, definitions, mutableSetOf()) }
            } else emptySet()
            is ArcSSAOperation.Forward -> canonicalDependencies(operation.source, definitions, visiting)
            is ArcSSAOperation.Reborrow -> operation.anchorDependencies.mapTo(linkedSetOf()) {
                canonicalAnchor(it, definitions, mutableSetOf())
            }
            is ArcSSAOperation.Join -> operation.incoming.values.map { canonicalDependencies(it, definitions, visiting.toMutableSet()) }
                .takeIf { sets -> sets.none { it == null } && sets.filterNotNull().distinct().size == 1 }
                ?.firstOrNull()
            else -> null
        }
        visiting.remove(value)
        return result
    }

    private fun canonicalAnchor(
        value: ArcSSAValue,
        definitions: Map<ArcSSAValue, Definition>,
        visiting: MutableSet<ArcSSAValue>,
    ): ArcSSAValue {
        if (!visiting.add(value)) return value
        return when (val operation = definitions[value]?.operation) {
            is ArcSSAOperation.Forward -> canonicalAnchor(operation.source, definitions, visiting)
            else -> value
        }
    }

    private fun classifiedUses(
        input: ArcOwnershipSSAInput,
        members: Set<ArcSSAValue>,
    ): List<Triple<ArcBlockId, Int, ArcSSAOperation.Use>> = buildList {
        input.blocks.values.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                if (operation is ArcSSAOperation.Use && operation.value in members) add(Triple(block.id, index, operation))
            }
        }
    }

    private fun computeDestroyPlacements(
        input: ArcOwnershipSSAInput,
        diamond: Diamond,
        members: Set<ArcSSAValue>,
        uses: List<Triple<ArcBlockId, Int, ArcSSAOperation.Use>>,
    ): Set<ArcSSADestroyPlacement>? {
        val result = linkedSetOf<ArcSSADestroyPlacement>()
        val postMergeReachable = reachableFrom(diamond.mergeBlock, input, normalOnly = true)
        val consume = uses.singleOrNull { it.third.kind == ArcSSAUseKind.Consume }

        // A throwing use owns the value only on its normal continuation. Unwind gets an explicit
        // edge cleanup, including for a consuming call whose callee never took ownership.
        uses.filter { it.third.mayThrow }.forEach { (block, _, _) ->
            input.edges.filter { it.from == block && it.kind == ArcSSAEdgeKind.Exceptional }
                .forEach { result += ArcSSADestroyPlacement.OnEdge(it) }
        }

        if (consume == null) {
            // Pruned liveness: if a deinit barrier is beyond the last real use, end lifetime before
            // it rather than extending an ARC value across observable destruction.
            val useBlocks = uses.mapTo(mutableSetOf()) { it.first }
            val futureUseBlocks = reverseReachable(useBlocks, input)
            val barrierPlacements = linkedMapOf<ArcBlockId, ArcSSADestroyPlacement.BeforeOperation>()
            postMergeReachable.forEach { blockId ->
                val block = input.blocks.getValue(blockId)
                val lastLocalUse = block.operations.indexOfLast { it is ArcSSAOperation.Use && it.value in members }
                val barrier = block.operations.withIndex().firstOrNull { (index, operation) ->
                    operation is ArcSSAOperation.DeinitBarrier && index > lastLocalUse &&
                            input.successors(blockId, ArcSSAEdgeKind.Normal).none { it in futureUseBlocks }
                }
                if (barrier != null) {
                    barrierPlacements[blockId] = ArcSSADestroyPlacement.BeforeOperation(blockId, barrier.index)
                }
            }

            result += barrierPlacements.values
            val pathCounts = linkedMapOf<ArcBlockId, MutableSet<Int>>()
            pathCounts.getOrPut(diamond.mergeBlock) { linkedSetOf() } += 0
            val worklist = ArrayDeque<ArcBlockId>().apply { add(diamond.mergeBlock) }
            while (worklist.isNotEmpty()) {
                val block = worklist.removeFirst()
                val outgoingCounts = pathCounts.getValue(block).mapTo(linkedSetOf()) {
                    (it + if (block in barrierPlacements) 1 else 0).coerceAtMost(2)
                }
                input.successors(block, ArcSSAEdgeKind.Normal).forEach { successor ->
                    val successorCounts = pathCounts.getOrPut(successor) { linkedSetOf() }
                    if (successorCounts.addAll(outgoingCounts)) worklist += successor
                }
            }
            postMergeReachable.filter { input.successors(it, ArcSSAEdgeKind.Normal).isEmpty() }.forEach { exit ->
                val counts = pathCounts[exit].orEmpty().mapTo(linkedSetOf()) {
                    it + if (exit in barrierPlacements) 1 else 0
                }
                when (counts) {
                    setOf(0) -> result += ArcSSADestroyPlacement.BeforeExit(exit)
                    setOf(1) -> Unit
                    else -> return null
                }
            }
        }
        return result
    }

    private fun hasValidDominance(
        input: ArcOwnershipSSAInput,
        members: Set<ArcSSAValue>,
        definitions: Map<ArcSSAValue, Definition>,
        dominators: Map<ArcBlockId, Set<ArcBlockId>>,
    ): Boolean {
        fun dominates(value: ArcSSAValue, block: ArcBlockId, index: Int): Boolean {
            val definition = definitions[value] ?: return false
            return definition.block in dominators.getValue(block) &&
                    (definition.block != block || definition.index < index)
        }
        if (members.any { value ->
                val block = definitions[value]?.block ?: return false
                input.entry !in dominators.getValue(block)
            }) return false
        return input.blocks.values.all { block ->
            block.operations.withIndex().all { (index, operation) ->
                when (operation) {
                    is ArcSSAOperation.Forward -> operation.source !in members || dominates(operation.source, block.id, index)
                    is ArcSSAOperation.Reborrow -> operation.result !in members ||
                            dominates(operation.source, block.id, index)
                    is ArcSSAOperation.Join -> operation.incoming.all { (predecessor, value) ->
                        value !in members || dominates(value, predecessor, input.blocks.getValue(predecessor).operations.size)
                    }
                    is ArcSSAOperation.Use -> operation.value !in members || dominates(operation.value, block.id, index)
                    is ArcSSAOperation.Introduce -> operation.result !in members ||
                            operation.anchorDependencies.all { dominates(it, block.id, index) }
                    is ArcSSAOperation.DeinitBarrier -> true
                }
            }
        }
    }

    private fun hasUnambiguousExceptionalCleanup(
        input: ArcOwnershipSSAInput,
        members: Set<ArcSSAValue>,
        uses: List<Triple<ArcBlockId, Int, ArcSSAOperation.Use>>,
    ): Boolean = uses.filter { it.third.mayThrow }.all { (blockId, operationIndex, _) ->
        val block = input.blocks.getValue(blockId)
        val throwingOperations = block.operations.withIndex().mapNotNull { (index, operation) ->
            (operation as? ArcSSAOperation.Use)?.takeIf { it.mayThrow }?.let { index to it }
        }
        val exceptionalEdges = input.edges.filter { it.from == blockId && it.kind == ArcSSAEdgeKind.Exceptional }
        throwingOperations.singleOrNull()?.first == operationIndex && exceptionalEdges.size == 1 &&
                reachableFrom(exceptionalEdges.single().to, input, normalOnly = false).none { reachable ->
                    input.blocks.getValue(reachable).operations.any { it is ArcSSAOperation.Use && it.value in members }
                }
    }

    private fun computeDominators(input: ArcOwnershipSSAInput): Map<ArcBlockId, Set<ArcBlockId>> {
        val all = input.blocks.keys
        val result = all.associateWithTo(linkedMapOf()) { if (it == input.entry) setOf(it) else all.toSet() }
        var changed = true
        while (changed) {
            changed = false
            all.filter { it != input.entry }.forEach { block ->
                val predecessors = input.predecessors(block, null)
                val newValue = if (predecessors.isEmpty()) setOf(block) else {
                    predecessors.map { result.getValue(it) }.reduce { left, right -> left intersect right } + block
                }
                if (newValue != result[block]) {
                    result[block] = newValue
                    changed = true
                }
            }
        }
        return result
    }

    private fun isAcyclic(input: ArcOwnershipSSAInput): Boolean {
        val color = mutableMapOf<ArcBlockId, Int>()
        fun visit(block: ArcBlockId): Boolean {
            when (color[block]) {
                1 -> return false
                2 -> return true
            }
            color[block] = 1
            if (input.successors(block, null).any { !visit(it) }) return false
            color[block] = 2
            return true
        }
        return input.blocks.keys.all { visit(it) }
    }

    private fun isCritical(edge: ArcSSAEdge, input: ArcOwnershipSSAInput): Boolean =
        input.successors(edge.from, null).size > 1 && input.predecessors(edge.to, null).size > 1

    private fun reachableFrom(start: ArcBlockId, input: ArcOwnershipSSAInput, normalOnly: Boolean): Set<ArcBlockId> {
        val result = linkedSetOf<ArcBlockId>()
        val worklist = ArrayDeque<ArcBlockId>().apply { add(start) }
        while (worklist.isNotEmpty()) {
            val block = worklist.removeFirst()
            if (!result.add(block)) continue
            input.successors(block, if (normalOnly) ArcSSAEdgeKind.Normal else null).forEach { worklist += it }
        }
        return result
    }

    private fun reverseReachable(starts: Set<ArcBlockId>, input: ArcOwnershipSSAInput): Set<ArcBlockId> {
        val result = linkedSetOf<ArcBlockId>()
        val worklist = ArrayDeque<ArcBlockId>().apply { addAll(starts) }
        while (worklist.isNotEmpty()) {
            val block = worklist.removeFirst()
            if (!result.add(block)) continue
            input.predecessors(block, ArcSSAEdgeKind.Normal).forEach { worklist += it }
        }
        return result
    }

    private fun ArcOwnershipSSAInput.successors(block: ArcBlockId, kind: ArcSSAEdgeKind?): List<ArcBlockId> =
        edges.asSequence().filter { it.from == block && (kind == null || it.kind == kind) }.map { it.to }.distinct().toList()

    private fun ArcOwnershipSSAInput.predecessors(block: ArcBlockId, kind: ArcSSAEdgeKind?): List<ArcBlockId> =
        edges.asSequence().filter { it.to == block && (kind == null || it.kind == kind) }.map { it.from }.distinct().toList()

    private fun ArcSSAOperation.resultOrNull(): ArcSSAValue? = when (this) {
        is ArcSSAOperation.Introduce -> result
        is ArcSSAOperation.Forward -> result
        is ArcSSAOperation.Reborrow -> result
        is ArcSSAOperation.Join -> result
        is ArcSSAOperation.Use, is ArcSSAOperation.DeinitBarrier -> null
    }

    private fun rejection(
        join: ArcSSAOperation.Join,
        reason: ArcOwnershipSSARejectionReason,
        detail: String,
        location: ArcPlanLocation? = join.location,
    ) = ArcOwnershipSSARejection(join.result, reason, detail, location)
}
