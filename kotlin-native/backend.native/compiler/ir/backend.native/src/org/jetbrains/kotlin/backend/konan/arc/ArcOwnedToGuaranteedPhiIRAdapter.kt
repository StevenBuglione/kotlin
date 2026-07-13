/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import java.util.Collections
import java.util.IdentityHashMap

internal data class ArcOwnedToGuaranteedPhiIRCompilationMode(
    val arcEnabled: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val diagnosticsDisabled: Boolean,
)

internal data class ArcOwnedToGuaranteedPhiIROperation<T : Any>(
    val operation: ArcSSAOperation,
    val binding: T,
)

internal data class ArcOwnedToGuaranteedPhiIRBlock<T : Any>(
    val id: ArcBlockId,
    val binding: T,
    val operations: List<ArcOwnedToGuaranteedPhiIROperation<T>>,
)

/** One exact lowered successor identity, including the invoke which owns an exceptional edge. */
internal data class ArcOwnedToGuaranteedPhiIREdge<T : Any>(
    val edge: ArcSSAEdge,
    val binding: T,
    val throwingOperation: ArcSemanticEmissionOperationId? = null,
    val throwingOperationBinding: T? = null,
)

/** Exact definition identities which authenticate one abstract guaranteed-copy seed. */
internal data class ArcOwnedToGuaranteedPhiIRSeed<T : Any>(
    val seed: ArcGuaranteedCopySeed,
    val copyDefinitionBinding: T,
    val guaranteedSourceDefinitionBinding: T,
    val anchorDefinitionBindings: Map<ArcSSAValue, T>,
)

internal data class ArcOwnedToGuaranteedPhiIRBarrier<T : Any>(
    val barrier: ArcSemanticARCBarrier,
    val binding: T,
)

internal data class ArcOwnedToGuaranteedPhiIRBlockSeal<T : Any>(
    val block: ArcBlockId,
    val binding: T,
)

/** Identity reserved by the CFG adapter for a block which does not exist in the input CFG yet. */
internal data class ArcOwnedToGuaranteedPhiIRCriticalEdgeSplit<T : Any>(
    val edge: ArcSSAEdge,
    val splitBlock: ArcBlockId,
    val splitBlockBinding: T,
)

/**
 * Complete inventory from one lowered function. All bindings are compared by object identity.
 * [completeLoweredIRWalk] means that every ownership definition, use, edge, and barrier was visited.
 */
internal data class ArcOwnedToGuaranteedPhiIRInventory<T : Any>(
    val functionBinding: T,
    val mode: ArcOwnedToGuaranteedPhiIRCompilationMode,
    val entry: ArcBlockId,
    val blocks: List<ArcOwnedToGuaranteedPhiIRBlock<T>>,
    val edges: List<ArcOwnedToGuaranteedPhiIREdge<T>>,
    val seeds: List<ArcOwnedToGuaranteedPhiIRSeed<T>>,
    val barriers: List<ArcOwnedToGuaranteedPhiIRBarrier<T>> = emptyList(),
    val deadEndBlocks: List<ArcOwnedToGuaranteedPhiIRBlockSeal<T>> = emptyList(),
    val exitLifetimeUses: List<ArcOwnedToGuaranteedPhiIRBlockSeal<T>> = emptyList(),
    val criticalEdgeSplits: List<ArcOwnedToGuaranteedPhiIRCriticalEdgeSplit<T>> = emptyList(),
    val budget: ArcSemanticARCBudget = ArcSemanticARCBudget(),
    val completeLoweredIRWalk: Boolean,
)

internal data class ArcOwnedToGuaranteedPhiIRPlanId(val seedCopies: List<ArcSSAValue>)

/** Stable future-emission operation. Every action is authenticated by exact IR identities. */
internal sealed class ArcOwnedToGuaranteedPhiIRActionId {
    abstract val plan: ArcOwnedToGuaranteedPhiIRPlanId?

    data class ConvertForwardingDefinition(
        override val plan: ArcOwnedToGuaranteedPhiIRPlanId,
        val value: ArcSSAValue,
        val definition: ArcSemanticEmissionOperationId,
    ) : ArcOwnedToGuaranteedPhiIRActionId()

    data class ConvertJoin(
        override val plan: ArcOwnedToGuaranteedPhiIRPlanId,
        val value: ArcSSAValue,
        val definition: ArcSemanticEmissionOperationId,
    ) : ArcOwnedToGuaranteedPhiIRActionId()

    data class InstallReborrow(
        override val plan: ArcOwnedToGuaranteedPhiIRPlanId,
        val joined: ArcSSAValue,
        val edge: ArcSSAEdge,
    ) : ArcOwnedToGuaranteedPhiIRActionId()

    data class EliminateCopy(
        override val plan: ArcOwnedToGuaranteedPhiIRPlanId,
        val copy: ArcSSAValue,
        val definition: ArcSemanticEmissionOperationId,
    ) : ArcOwnedToGuaranteedPhiIRActionId()

    data class RemoveDestroy(
        override val plan: ArcOwnedToGuaranteedPhiIRPlanId,
        val value: ArcSSAValue,
        val operation: ArcSemanticEmissionOperationId,
    ) : ArcOwnedToGuaranteedPhiIRActionId()

    data class EndLifetime(
        override val plan: ArcOwnedToGuaranteedPhiIRPlanId,
        val frontier: ArcSemanticLifetimeFrontier,
    ) : ArcOwnedToGuaranteedPhiIRActionId()

    /** Authenticated Swift-style pruned-liveness boundary consumed by the atomic rewrite. */
    data class ApplyPrunedLifetimeBoundary(
        override val plan: ArcOwnedToGuaranteedPhiIRPlanId,
        val boundary: ArcPrunedOwnershipBoundaryPoint,
    ) : ArcOwnedToGuaranteedPhiIRActionId()

    data class SplitCriticalEdge(
        val edge: ArcSSAEdge,
        val splitBlock: ArcBlockId,
    ) : ArcOwnedToGuaranteedPhiIRActionId() {
        override val plan: ArcOwnedToGuaranteedPhiIRPlanId? = null
    }
}

internal data class ArcOwnedToGuaranteedPhiIRAction<T : Any>(
    val id: ArcOwnedToGuaranteedPhiIRActionId,
    val bindingIdentities: List<T>,
)

internal data class ArcOwnedToGuaranteedPhiIRExactBindings<T : Any>(
    val function: T,
    val blocks: Map<ArcBlockId, T>,
    val operations: Map<ArcSemanticEmissionOperationId, T>,
    val edges: Map<ArcSSAEdge, T>,
    val definitions: Map<ArcSSAValue, T>,
    val barriers: Map<ArcSemanticARCBarrier, T>,
    val deadEndBlocks: Map<ArcBlockId, T>,
    val exitLifetimeUses: Map<ArcBlockId, T>,
    val criticalEdgeSplits: Map<ArcSSAEdge, T>,
)

internal data class ArcOwnedToGuaranteedPhiIRSelection<T : Any>(
    val inventory: ArcOwnedToGuaranteedPhiIRInventory<T>,
    val input: ArcSemanticARCInput,
    val plans: List<ArcSemanticPhiWebPlan>,
    val prunedLiveness: Map<ArcOwnedToGuaranteedPhiIRPlanId, ArcPrunedOwnershipLivenessProof>,
    val exactBindings: ArcOwnedToGuaranteedPhiIRExactBindings<T>,
    val actions: Map<ArcOwnedToGuaranteedPhiIRActionId, ArcOwnedToGuaranteedPhiIRAction<T>>,
)

/**
 * Stages every authenticated action before a future emitter may commit the transformation.
 * Committing an incomplete or identity-drifted transaction fails without authorizing emission.
 */
internal class ArcOwnedToGuaranteedPhiIRConsumptionLedger<T : Any>(
    private val selection: ArcOwnedToGuaranteedPhiIRSelection<T>,
    functionBinding: T,
) {
    private enum class State { Open, Committed, Aborted }

    private var state = State.Open
    private val staged = linkedSetOf<ArcOwnedToGuaranteedPhiIRActionId>()

    init {
        check(selection.exactBindings.function === functionBinding) {
            "owned-to-guaranteed phi transaction opened for a different function identity"
        }
    }

    fun stage(id: ArcOwnedToGuaranteedPhiIRActionId, bindingIdentities: List<T>) {
        check(state == State.Open) { "owned-to-guaranteed phi transaction is already $state" }
        val action = selection.actions[id] ?: error("unknown owned-to-guaranteed phi action: $id")
        check(action.bindingIdentities.size == bindingIdentities.size &&
                action.bindingIdentities.indices.all { action.bindingIdentities[it] === bindingIdentities[it] }) {
            "owned-to-guaranteed phi binding identity drifted: $id"
        }
        check(staged.add(id)) { "duplicate owned-to-guaranteed phi action: $id" }
    }

    fun commit() {
        check(state == State.Open) { "owned-to-guaranteed phi transaction is already $state" }
        check(staged == selection.actions.keys) {
            "owned-to-guaranteed phi transaction incomplete: missing=${selection.actions.keys - staged}"
        }
        state = State.Committed
    }

    fun abort() {
        check(state == State.Open) { "owned-to-guaranteed phi transaction is already $state" }
        staged.clear()
        state = State.Aborted
    }
}

internal enum class ArcOwnedToGuaranteedPhiIRRejectionReason {
    UnsupportedCompilationMode,
    IncompleteLoweredIRWalk,
    BudgetExhausted,
    DuplicateBlock,
    DuplicateOperation,
    DuplicateEdge,
    DuplicateIRIdentity,
    MissingDefinition,
    InvalidCFG,
    InvalidExceptionalEdge,
    InvalidBarrier,
    InvalidBlockSeal,
    InvalidSeedIdentity,
    SemanticAnalysisRejected,
    PrunedLivenessRejected,
    IncompleteSemanticCoverage,
    MissingCriticalEdgeSplit,
    InvalidCriticalEdgeSplit,
    ActionBindingFailure,
}

internal data class ArcOwnedToGuaranteedPhiIRRejection(
    val reason: ArcOwnedToGuaranteedPhiIRRejectionReason,
    val detail: String,
)

internal data class ArcOwnedToGuaranteedPhiIRAdapterResult<T : Any>(
    val selection: ArcOwnedToGuaranteedPhiIRSelection<T>?,
    val rejections: List<ArcOwnedToGuaranteedPhiIRRejection>,
)

/** Exact-identity adapter from a complete lowered inventory into the generalized phi-web proof. */
internal object ArcOwnedToGuaranteedPhiIRAdapter {
    fun <T : Any> adapt(
        inventory: ArcOwnedToGuaranteedPhiIRInventory<T>,
    ): ArcOwnedToGuaranteedPhiIRAdapterResult<T> {
        val rejected = mutableListOf<ArcOwnedToGuaranteedPhiIRRejection>()
        fun reject(reason: ArcOwnedToGuaranteedPhiIRRejectionReason, detail: String) {
            rejected += ArcOwnedToGuaranteedPhiIRRejection(reason, detail)
        }

        if (!inventory.mode.arcEnabled || !inventory.mode.optimizationsEnabled ||
            !inventory.mode.debugInfoDisabled || !inventory.mode.diagnosticsDisabled
        ) reject(
            ArcOwnedToGuaranteedPhiIRRejectionReason.UnsupportedCompilationMode,
            "general phi conversion requires optimized, non-debug, diagnostics-free ARC",
        )
        if (!inventory.completeLoweredIRWalk) reject(
            ArcOwnedToGuaranteedPhiIRRejectionReason.IncompleteLoweredIRWalk,
            "the ownership inventory did not classify every lowered node and edge",
        )
        val instructionCount = inventory.blocks.sumOf { it.operations.size }
        val useCount = inventory.blocks.sumOf { block -> block.operations.sumOf { it.operation.adapterOperands().size } } +
                inventory.edges.size
        if (inventory.budget.maximumInstructions < 1 || inventory.budget.maximumUses < 1 ||
            inventory.budget.maximumRewriteSteps < 1 ||
            instructionCount > inventory.budget.maximumInstructions || useCount > inventory.budget.maximumUses
        ) reject(
            ArcOwnedToGuaranteedPhiIRRejectionReason.BudgetExhausted,
            "inventory requires $instructionCount instructions and $useCount operands/edges",
        )

        val blocksById = inventory.blocks.groupBy { it.id }
        if (blocksById.any { it.value.size != 1 }) reject(
            ArcOwnedToGuaranteedPhiIRRejectionReason.DuplicateBlock,
            "one abstract block id has multiple exact lowered owners",
        )
        val blockMap = blocksById.mapValues { it.value.first() }
        if (inventory.entry !in blockMap) reject(
            ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidCFG,
            "entry block is absent from the complete inventory",
        )

        val operationBindings = linkedMapOf<ArcSemanticEmissionOperationId, T>()
        val definitions = linkedMapOf<ArcSSAValue, Pair<ArcSemanticEmissionOperationId, T>>()
        val duplicateDefinitions = linkedSetOf<ArcSSAValue>()
        inventory.blocks.forEach { block -> block.operations.forEachIndexed { index, ownedOperation ->
            val position = ArcSemanticEmissionOperationId(block.id, index)
            if (operationBindings.put(position, ownedOperation.binding) != null) reject(
                ArcOwnedToGuaranteedPhiIRRejectionReason.DuplicateOperation,
                "operation position $position has multiple exact lowered owners",
            )
            ownedOperation.operation.adapterResult()?.let { value ->
                if (definitions.put(value, position to ownedOperation.binding) != null) duplicateDefinitions += value
            }
        } }
        if (duplicateDefinitions.isNotEmpty()) reject(
            ArcOwnedToGuaranteedPhiIRRejectionReason.DuplicateOperation,
            "SSA definitions are duplicated: ${duplicateDefinitions.sortedBy { it.name }}",
        )
        val missingOperands = inventory.blocks.flatMap { block -> block.operations.flatMap { it.operation.adapterOperands() } }
            .filterTo(linkedSetOf()) { it !in definitions }
        if (missingOperands.isNotEmpty()) reject(
            ArcOwnedToGuaranteedPhiIRRejectionReason.MissingDefinition,
            "ownership operands have no exact definition: ${missingOperands.sortedBy { it.name }}",
        )

        val edgesByShape = inventory.edges.groupBy { it.edge }
        if (edgesByShape.any { it.value.size != 1 }) reject(
            ArcOwnedToGuaranteedPhiIRRejectionReason.DuplicateEdge,
            "one CFG edge has multiple exact lowered successor owners",
        )
        if (inventory.edges.any { it.edge.from !in blockMap || it.edge.to !in blockMap }) reject(
            ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidCFG,
            "an edge references a block absent from the complete inventory",
        )
        inventory.edges.forEach { ownedEdge ->
            val throwing = ownedEdge.throwingOperation
            val throwingBinding = ownedEdge.throwingOperationBinding
            if (ownedEdge.edge.kind == ArcSSAEdgeKind.Normal) {
                if (throwing != null || throwingBinding != null) reject(
                    ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidExceptionalEdge,
                    "normal edge ${ownedEdge.edge} claims an exceptional owner",
                )
            } else {
                val operation = throwing?.let { position ->
                    blockMap[position.block]?.operations?.getOrNull(position.operationIndex)
                }
                val throwingUse = operation?.operation as? ArcSSAOperation.Use
                if (throwing == null || throwing.block != ownedEdge.edge.from ||
                    throwingUse?.mayThrow != true ||
                    operation.binding !== throwingBinding || operationBindings[throwing] !== throwingBinding
                ) reject(
                    ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidExceptionalEdge,
                    "exceptional edge ${ownedEdge.edge} is not owned by one exact throwing operation",
                )
            }
        }
        val throwingOperations = inventory.blocks.flatMap { block ->
            block.operations.mapIndexedNotNull { index, operation ->
                (operation.operation as? ArcSSAOperation.Use)?.takeIf { it.mayThrow }
                    ?.let { ArcSemanticEmissionOperationId(block.id, index) }
            }
        }
        throwingOperations.forEach { position ->
            val owners = inventory.edges.filter {
                it.edge.kind == ArcSSAEdgeKind.Exceptional && it.throwingOperation == position
            }
            if (owners.size != 1) reject(
                ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidExceptionalEdge,
                "throwing operation $position must own exactly one exceptional successor",
            )
        }

        val barrierGroups = inventory.barriers.groupBy { it.barrier }
        if (barrierGroups.any { it.value.size != 1 }) reject(
            ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidBarrier,
            "one abstract barrier has multiple exact bindings",
        )
        inventory.barriers.forEach { ownedBarrier ->
            val position = ArcSemanticEmissionOperationId(
                ownedBarrier.barrier.block, ownedBarrier.barrier.operationIndex,
            )
            if (operationBindings[position] !== ownedBarrier.binding) reject(
                ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidBarrier,
                "barrier ${ownedBarrier.barrier} is not bound to the exact operation at its position",
            )
        }
        val implicitDeinitBarriers = inventory.blocks.flatMapTo(linkedSetOf()) { block ->
            block.operations.mapIndexedNotNull { index, operation ->
                if (operation.operation is ArcSSAOperation.DeinitBarrier) {
                    ArcSemanticARCBarrier(block.id, index, ArcSemanticARCBarrierKind.Deinitialization)
                } else null
            }
        }
        if (implicitDeinitBarriers.any { it !in barrierGroups }) reject(
            ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidBarrier,
            "every lowered deinitialization barrier requires one exact inventory binding",
        )

        fun validateBlockSeals(
            seals: List<ArcOwnedToGuaranteedPhiIRBlockSeal<T>>,
            name: String,
        ): Map<ArcBlockId, T> {
            val groups = seals.groupBy { it.block }
            if (groups.any { it.value.size != 1 }) reject(
                ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidBlockSeal,
                "$name contains duplicate block identities",
            )
            seals.forEach { seal -> if (blockMap[seal.block]?.binding !== seal.binding) reject(
                ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidBlockSeal,
                "$name block ${seal.block} is not bound to its exact lowered owner",
            ) }
            return groups.mapValues { it.value.first().binding }
        }
        val deadEndBindings = validateBlockSeals(inventory.deadEndBlocks, "dead-end inventory")
        val exitBindings = validateBlockSeals(inventory.exitLifetimeUses, "exit-lifetime inventory")
        if (deadEndBindings.keys.intersect(exitBindings.keys).isNotEmpty()) reject(
            ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidBlockSeal,
            "a block cannot be both dead-end and a modeled lifetime exit",
        )

        val seedGroups = inventory.seeds.groupBy { it.seed.copy }
        if (inventory.seeds.isEmpty() || seedGroups.any { it.value.size != 1 }) reject(
            ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidSeedIdentity,
            "every candidate owned copy requires one unique seed identity",
        )
        inventory.seeds.forEach { ownedSeed ->
            val seed = ownedSeed.seed
            val copy = definitions[seed.copy]?.second
            val source = definitions[seed.guaranteedSource]?.second
            if (copy !== ownedSeed.copyDefinitionBinding || source !== ownedSeed.guaranteedSourceDefinitionBinding ||
                ownedSeed.anchorDefinitionBindings.keys != seed.anchorDependencies ||
                seed.anchorDependencies.any { anchor ->
                    definitions[anchor]?.second !== ownedSeed.anchorDefinitionBindings[anchor]
                }
            ) reject(
                ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidSeedIdentity,
                "seed ${seed.copy} does not carry the exact copy, source, and anchor definition identities",
            )
        }

        val allBaseBindings = buildList {
            add(inventory.functionBinding)
            inventory.blocks.forEach { block ->
                add(block.binding)
                block.operations.forEach { add(it.binding) }
            }
            inventory.edges.forEach { add(it.binding) }
        }
        val uniqueBaseBindings = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        if (allBaseBindings.any { !uniqueBaseBindings.add(it) }) reject(
            ArcOwnedToGuaranteedPhiIRRejectionReason.DuplicateIRIdentity,
            "one lowered IR object was assigned to more than one function, block, operation, or edge role",
        )

        if (rejected.isNotEmpty()) return ArcOwnedToGuaranteedPhiIRAdapterResult(null, rejected)

        val cfg = ArcOwnershipSSAInput(
            inventory.entry,
            inventory.blocks.associateTo(linkedMapOf()) { block ->
                block.id to ArcSSABlock(block.id, block.operations.map { it.operation })
            },
            inventory.edges.mapTo(linkedSetOf()) { it.edge },
        )
        val input = ArcSemanticARCInput(
            cfg = cfg,
            guaranteedCopies = inventory.seeds.mapTo(linkedSetOf()) { it.seed },
            deadEndBlocks = deadEndBindings.keys,
            exitLifetimeUses = exitBindings.keys,
            barriers = barrierGroups.keys,
            budget = inventory.budget,
        )
        val semantic = ArcOwnedToGuaranteedPhiWebAnalysis.analyze(input)
        if (semantic.rejected.isNotEmpty() || semantic.accepted.isEmpty()) return ArcOwnedToGuaranteedPhiIRAdapterResult(
            null,
            listOf(ArcOwnedToGuaranteedPhiIRRejection(
                ArcOwnedToGuaranteedPhiIRRejectionReason.SemanticAnalysisRejected,
                semantic.rejected.toString(),
            )),
        )
        val plannedSeeds = semantic.accepted.flatMapTo(linkedSetOf()) { plan -> plan.seeds.map { it.copy } }
        if (plannedSeeds != seedGroups.keys || semantic.accepted.flatMap { it.members }.distinct().size !=
            semantic.accepted.sumOf { it.members.size }
        ) return ArcOwnedToGuaranteedPhiIRAdapterResult(
            null,
            listOf(ArcOwnedToGuaranteedPhiIRRejection(
                ArcOwnedToGuaranteedPhiIRRejectionReason.IncompleteSemanticCoverage,
                "the accepted plans do not cover every exact seed once",
            )),
        )

        val plans = semantic.accepted.sortedBy { plan -> plan.seeds.minOf { it.copy.name } }
        val dominators = adapterDominators(cfg)
        val prunedLiveness = linkedMapOf<ArcOwnedToGuaranteedPhiIRPlanId, ArcPrunedOwnershipLivenessProof>()
        plans.forEach planLoop@{ plan ->
            val planId = ArcOwnedToGuaranteedPhiIRPlanId(plan.seeds.map { it.copy }.sortedBy { it.name })
            val prunedDefinitions = plan.members.mapTo(linkedSetOf()) { value ->
                val position = definitions.getValue(value).first
                ArcPrunedOwnershipDefinition(value, position.block, position.operationIndex)
            }
            val prunedUses = linkedSetOf<ArcPrunedOwnershipUse>()
            var malformedPhiEdge: String? = null
            cfg.blocks.values.sortedBy { it.id.name }.forEach { block ->
                block.operations.forEachIndexed { index, operation ->
                    if (operation is ArcSSAOperation.Join) {
                        operation.incoming.entries.sortedBy { it.key.name }.forEach incomingLoop@{ (predecessor, incoming) ->
                            if (incoming !in plan.members) return@incomingLoop
                            val edge = cfg.edges.singleOrNull {
                                it.from == predecessor && it.to == block.id && it.kind == ArcSSAEdgeKind.Normal
                            }
                            if (edge == null) {
                                malformedPhiEdge = "join ${operation.result} has no unique normal edge from $predecessor"
                            } else {
                                prunedUses += ArcPrunedOwnershipUse.Edge(
                                    incoming, edge, ArcPrunedOwnershipUseLifetime.NonLifetimeEnding,
                                )
                            }
                        }
                    } else {
                        operation.adapterOperands().filter { it in plan.members }.distinct().forEach { operand ->
                            val lifetime = when (operation) {
                                is ArcSSAOperation.DestroyOwned -> ArcPrunedOwnershipUseLifetime.LifetimeEnding
                                is ArcSSAOperation.Use -> if (operation.kind == ArcSSAUseKind.Consume) {
                                    ArcPrunedOwnershipUseLifetime.LifetimeEnding
                                } else ArcPrunedOwnershipUseLifetime.NonLifetimeEnding
                                else -> ArcPrunedOwnershipUseLifetime.NonLifetimeEnding
                            }
                            prunedUses += ArcPrunedOwnershipUse.Operation(
                                operand, block.id, index, lifetime,
                            )
                        }
                    }
                }
            }
            exitBindings.keys.sortedBy { it.name }.forEach { exitBlock ->
                plan.members.sortedBy { it.name }.forEach { value ->
                    val definition = definitions.getValue(value).first
                    if (adapterDefinitionDominatesBlockEnd(definition, exitBlock, dominators)) {
                        prunedUses += ArcPrunedOwnershipUse.Exit(
                            value, exitBlock, ArcPrunedOwnershipUseLifetime.LifetimeEnding,
                        )
                    }
                }
            }
            if (malformedPhiEdge != null) {
                reject(
                    ArcOwnedToGuaranteedPhiIRRejectionReason.PrunedLivenessRejected,
                    malformedPhiEdge!!,
                )
                return@planLoop
            }
            val graphUnitCount = inventory.blocks.size.toLong() + inventory.edges.size + instructionCount +
                    prunedUses.size + prunedDefinitions.size
            val iterationLimit = minOf(
                inventory.blocks.size.toLong() + 1,
                inventory.budget.maximumRewriteSteps.toLong(),
            ).coerceAtLeast(1)
            val maximumDataflowSteps = (graphUnitCount * iterationLimit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val result = ArcPrunedOwnershipLivenessAnalysis.analyze(
                ArcPrunedOwnershipLivenessInput(
                    cfg = cfg,
                    definitions = prunedDefinitions,
                    uses = prunedUses,
                    deadEndBlocks = deadEndBindings.keys,
                    barriers = barrierGroups.keys,
                    budget = ArcPrunedOwnershipLivenessBudget(
                        maximumBlocks = maxOf(1, inventory.blocks.size),
                        maximumEdges = maxOf(1, inventory.edges.size),
                        maximumDefinitions = maxOf(1, prunedDefinitions.size),
                        maximumUses = maxOf(1, inventory.budget.maximumUses),
                        maximumDataflowSteps = maxOf(1, maximumDataflowSteps),
                    ),
                    completeInventory = true,
                ),
            )
            val proof = result.proof
            if (proof == null) {
                reject(
                    ArcOwnedToGuaranteedPhiIRRejectionReason.PrunedLivenessRejected,
                    "$planId: ${result.rejection}",
                )
            } else {
                // Swift's boundary visitor always records LiveOut -> Dead edges before it asks the
                // single-/multi-def scanner for local boundaries. Reconstruct that outer visitor
                // step from the authenticated proof so definition-bearing source blocks cannot
                // lose a normal critical frontier or exceptional cleanup frontier.
                val edgeBoundary = cfg.edges.filter { edge ->
                    proof.blockLiveness[edge.from] == ArcPrunedBlockLiveness.LiveOut &&
                            proof.blockLiveness[edge.to] == ArcPrunedBlockLiveness.Dead &&
                            proof.liveOnEdges[edge] != true && edge.from !in deadEndBindings &&
                            edge.to !in deadEndBindings
                }.mapTo(linkedSetOf()) { edge ->
                    ArcPrunedOwnershipBoundaryPoint.OnEdge(
                        edge,
                        requiresEdgeSplit = edge.kind == ArcSSAEdgeKind.Normal && adapterIsCritical(edge, cfg),
                        requiresExceptionalCleanup = edge.kind == ArcSSAEdgeKind.Exceptional,
                    )
                }
                val completeProof = proof.copy(boundary = proof.boundary + edgeBoundary)
                if (prunedLiveness.put(planId, completeProof) != null) reject(
                    ArcOwnedToGuaranteedPhiIRRejectionReason.PrunedLivenessRejected,
                    "duplicate pruned-liveness plan identity $planId",
                )
            }
        }
        if (rejected.isNotEmpty()) return ArcOwnedToGuaranteedPhiIRAdapterResult(null, rejected)

        // The semantic frontier remains an independent oracle. Pruned liveness is the sole
        // authoritative emitted lifetime boundary, preventing two ends for one ownership range.
        val requiredCriticalEdges = prunedLiveness.values.flatMap { proof -> proof.boundary }
            .filterIsInstance<ArcPrunedOwnershipBoundaryPoint.OnEdge>()
            .filter { it.requiresEdgeSplit }.mapTo(linkedSetOf()) { it.edge }
        val splitGroups = inventory.criticalEdgeSplits.groupBy { it.edge }
        if (splitGroups.keys != requiredCriticalEdges || splitGroups.any { it.value.size != 1 }) reject(
            ArcOwnedToGuaranteedPhiIRRejectionReason.MissingCriticalEdgeSplit,
            "critical frontier edges and reserved split identities differ",
        )
        val splitIds = linkedSetOf<ArcBlockId>()
        val splitBindings = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        inventory.criticalEdgeSplits.forEach { split ->
            if (split.splitBlock in blockMap || !splitIds.add(split.splitBlock) ||
                !splitBindings.add(split.splitBlockBinding) || uniqueBaseBindings.contains(split.splitBlockBinding)
            ) reject(
                ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidCriticalEdgeSplit,
                "critical edge ${split.edge} lacks one unique future split-block identity",
            )
        }
        if (rejected.isNotEmpty()) return ArcOwnedToGuaranteedPhiIRAdapterResult(null, rejected)

        val blockBindings = inventory.blocks.associate { it.id to it.binding }
        val edgeBindings = inventory.edges.associate { it.edge to it.binding }
        val barrierBindings = inventory.barriers.associate { it.barrier to it.binding }
        val splitBindingMap = inventory.criticalEdgeSplits.associate { it.edge to it.splitBlockBinding }
        val exactBindings = ArcOwnedToGuaranteedPhiIRExactBindings(
            function = inventory.functionBinding,
            blocks = blockBindings,
            operations = operationBindings,
            edges = edgeBindings,
            definitions = definitions.mapValues { it.value.second },
            barriers = barrierBindings,
            deadEndBlocks = deadEndBindings,
            exitLifetimeUses = exitBindings,
            criticalEdgeSplits = splitBindingMap,
        )
        val actions = linkedMapOf<ArcOwnedToGuaranteedPhiIRActionId, ArcOwnedToGuaranteedPhiIRAction<T>>()
        fun add(id: ArcOwnedToGuaranteedPhiIRActionId, bindings: List<T>): Boolean {
            if (bindings.isEmpty() || actions.put(id, ArcOwnedToGuaranteedPhiIRAction(id, bindings)) != null) {
                reject(
                    ArcOwnedToGuaranteedPhiIRRejectionReason.ActionBindingFailure,
                    "action $id has no exact identity or collides with another action",
                )
                return false
            }
            return true
        }
        inventory.criticalEdgeSplits.sortedBy { it.edge.toString() }.forEach { split ->
            add(
                ArcOwnedToGuaranteedPhiIRActionId.SplitCriticalEdge(split.edge, split.splitBlock),
                listOf(edgeBindings.getValue(split.edge), split.splitBlockBinding),
            )
        }
        plans.forEach { plan ->
            val planId = ArcOwnedToGuaranteedPhiIRPlanId(plan.seeds.map { it.copy }.sortedBy { it.name })
            plan.members.sortedBy { it.name }.forEach { value ->
                val definition = definitions.getValue(value).first
                when (cfg.blocks.getValue(definition.block).operations[definition.operationIndex]) {
                    is ArcSSAOperation.Forward, is ArcSSAOperation.Reborrow -> add(
                        ArcOwnedToGuaranteedPhiIRActionId.ConvertForwardingDefinition(planId, value, definition),
                        listOf(operationBindings.getValue(definition)),
                    )
                    is ArcSSAOperation.Join -> add(
                        ArcOwnedToGuaranteedPhiIRActionId.ConvertJoin(planId, value, definition),
                        listOf(operationBindings.getValue(definition)),
                    )
                    else -> Unit
                }
            }
            plan.reborrows.sortedWith(compareBy({ it.edge.from.name }, { it.edge.to.name }, { it.joined.name })).forEach { reborrow ->
                val joinDefinition = definitions.getValue(reborrow.joined).first
                add(
                    ArcOwnedToGuaranteedPhiIRActionId.InstallReborrow(planId, reborrow.joined, reborrow.edge),
                    listOf(
                        edgeBindings.getValue(reborrow.edge),
                        definitions.getValue(reborrow.incoming).second,
                        operationBindings.getValue(joinDefinition),
                    ),
                )
            }
            plan.seeds.sortedBy { it.copy.name }.forEach { seed ->
                val definition = definitions.getValue(seed.copy).first
                add(
                    ArcOwnedToGuaranteedPhiIRActionId.EliminateCopy(planId, seed.copy, definition),
                    listOf(
                        definitions.getValue(seed.copy).second,
                        definitions.getValue(seed.guaranteedSource).second,
                    ) + seed.anchorDependencies.sortedBy { it.name }.map { definitions.getValue(it).second },
                )
            }
            inventory.blocks.forEach { block -> block.operations.forEachIndexed { index, ownedOperation ->
                val destroy = ownedOperation.operation as? ArcSSAOperation.DestroyOwned ?: return@forEachIndexed
                if (destroy.value in plan.members) add(
                    ArcOwnedToGuaranteedPhiIRActionId.RemoveDestroy(
                        planId, destroy.value, ArcSemanticEmissionOperationId(block.id, index),
                    ),
                    listOf(ownedOperation.binding),
                )
            } }
            prunedLiveness.getValue(planId).boundary.sortedBy { it.toString() }.forEach { boundary ->
                val bindings: List<T>? = when (boundary) {
                    is ArcPrunedOwnershipBoundaryPoint.AfterOperation ->
                        operationBindings[ArcSemanticEmissionOperationId(
                            boundary.block, boundary.operationIndex,
                        )]?.let(::listOf)
                    is ArcPrunedOwnershipBoundaryPoint.AfterDeadDefinition -> {
                        val definition = definitions[boundary.definition.value]
                        if (definition?.first?.block == boundary.definition.block &&
                            definition.first.operationIndex == boundary.definition.operationIndex
                        ) listOf(definition.second) else null
                    }
                    is ArcPrunedOwnershipBoundaryPoint.ExistingLifetimeEnd -> when (val use = boundary.use) {
                        is ArcPrunedOwnershipUse.Operation ->
                            operationBindings[ArcSemanticEmissionOperationId(
                                use.block, use.operationIndex,
                            )]?.let(::listOf)
                        is ArcPrunedOwnershipUse.Edge -> edgeBindings[use.edge]?.let(::listOf)
                        is ArcPrunedOwnershipUse.Exit -> exitBindings[use.block]?.let(::listOf)
                    }
                    is ArcPrunedOwnershipBoundaryPoint.OnEdge -> {
                        val edgeBinding = edgeBindings[boundary.edge]
                        val throwingBinding = if (boundary.requiresExceptionalCleanup) {
                            inventory.edges.singleOrNull { it.edge == boundary.edge }?.throwingOperationBinding
                        } else null
                        val splitBinding = if (boundary.requiresEdgeSplit) splitBindingMap[boundary.edge] else null
                        if (edgeBinding == null || boundary.requiresExceptionalCleanup && throwingBinding == null ||
                            boundary.requiresEdgeSplit && splitBinding == null
                        ) null else buildList {
                            add(edgeBinding)
                            throwingBinding?.let { add(it) }
                            splitBinding?.let { add(it) }
                        }
                    }
                    is ArcPrunedOwnershipBoundaryPoint.BeforeExit ->
                        exitBindings[boundary.block]?.let(::listOf)
                }
                if (bindings == null) {
                    reject(
                        ArcOwnedToGuaranteedPhiIRRejectionReason.ActionBindingFailure,
                        "pruned lifetime boundary $boundary has no authenticated exact identity",
                    )
                } else {
                    add(
                        ArcOwnedToGuaranteedPhiIRActionId.ApplyPrunedLifetimeBoundary(planId, boundary),
                        bindings,
                    )
                }
            }
        }
        if (rejected.isNotEmpty()) return ArcOwnedToGuaranteedPhiIRAdapterResult(null, rejected)
        return ArcOwnedToGuaranteedPhiIRAdapterResult(
            ArcOwnedToGuaranteedPhiIRSelection(inventory, input, plans, prunedLiveness, exactBindings, actions),
            emptyList(),
        )
    }
}

private fun ArcSSAOperation.adapterResult(): ArcSSAValue? = when (this) {
    is ArcSSAOperation.Introduce -> result
    is ArcSSAOperation.Forward -> result
    is ArcSSAOperation.Reborrow -> result
    is ArcSSAOperation.Join -> result
    is ArcSSAOperation.Borrow -> result
    else -> null
}

private fun ArcSSAOperation.adapterOperands(): List<ArcSSAValue> = when (this) {
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

private fun adapterDominators(cfg: ArcOwnershipSSAInput): Map<ArcBlockId, Set<ArcBlockId>> {
    if (cfg.entry !in cfg.blocks) return emptyMap()
    val reachable = linkedSetOf<ArcBlockId>()
    val worklist = ArrayDeque<ArcBlockId>().apply { add(cfg.entry) }
    while (worklist.isNotEmpty()) {
        val block = worklist.removeFirst()
        if (!reachable.add(block)) continue
        cfg.edges.filter { it.from == block }.sortedWith(
            compareBy({ it.to.name }, { it.kind.name }),
        ).forEach { worklist += it.to }
    }
    val result = reachable.associateWithTo(linkedMapOf()) {
        if (it == cfg.entry) setOf(it) else reachable.toSet()
    }
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

private fun adapterDefinitionDominatesBlockEnd(
    definition: ArcSemanticEmissionOperationId,
    block: ArcBlockId,
    dominators: Map<ArcBlockId, Set<ArcBlockId>>,
): Boolean = definition.block in dominators[block].orEmpty()

private fun adapterIsCritical(edge: ArcSSAEdge, cfg: ArcOwnershipSSAInput): Boolean =
    cfg.edges.count { it.from == edge.from } > 1 && cfg.edges.count { it.to == edge.to } > 1
