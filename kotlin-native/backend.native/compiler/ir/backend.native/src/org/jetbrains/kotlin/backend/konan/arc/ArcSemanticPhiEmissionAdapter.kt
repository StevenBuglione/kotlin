/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import java.util.Collections
import java.util.IdentityHashMap

/** Stable within one normalized lowered body. */
internal data class ArcSemanticEmissionOperationId(val block: ArcBlockId, val operationIndex: Int)

/**
 * Binds an abstract exceptional CFG edge to the exact throwing operation which owns the physical
 * unwind successor. The three identities are checked again when the plan is consumed by codegen.
 */
internal data class ArcSemanticExceptionalSuccessorBinding<T : Any>(
    val call: ArcSemanticEmissionOperationId,
    val callBinding: T,
    val edge: ArcSSAEdge,
    val successorBinding: T,
)

/** A reserved identity for the block which a CFG adapter will insert on one critical edge. */
internal data class ArcSemanticCriticalEdgeSplitBinding<T : Any>(
    val edge: ArcSSAEdge,
    val splitBlock: ArcBlockId,
    val splitBlockBinding: T,
)

internal data class ArcSemanticBarrierEmissionBinding<T : Any>(
    val barrier: ArcSemanticARCBarrier,
    val binding: T,
)

/**
 * Complete lowered-body inventory required to materialize one semantic-ARC proof. The inventory is
 * deliberately separate from the analysis CFG: production IR walkers can use object identities,
 * while tests can use opaque identity tokens.
 */
internal data class ArcSemanticPhiLoweredBody<T : Any>(
    val functionBinding: T,
    val cfg: ArcOwnershipSSAInput,
    val blockBindings: Map<ArcBlockId, T>,
    val operationBindings: Map<ArcSemanticEmissionOperationId, T>,
    val exceptionalSuccessors: List<ArcSemanticExceptionalSuccessorBinding<T>> = emptyList(),
    val criticalEdgeSplits: List<ArcSemanticCriticalEdgeSplitBinding<T>> = emptyList(),
    val barrierBindings: List<ArcSemanticBarrierEmissionBinding<T>> = emptyList(),
    val completeLoweredIRWalk: Boolean,
)

/** Every ownership rewrite has a stable, independently consumable emission identity. */
internal sealed class ArcSemanticEmissionActionId {
    data class ConvertJoin(val joined: ArcSSAValue, val definition: ArcSemanticEmissionOperationId) :
        ArcSemanticEmissionActionId()

    data class Reborrow(val joined: ArcSSAValue, val edge: ArcSSAEdge) : ArcSemanticEmissionActionId()
    data class EliminateCopy(val copy: ArcSSAValue, val definition: ArcSemanticEmissionOperationId) :
        ArcSemanticEmissionActionId()

    data class EndAfterOperation(val operation: ArcSemanticEmissionOperationId) : ArcSemanticEmissionActionId()
    data class EndBeforeBarrier(val barrier: ArcSemanticARCBarrier) : ArcSemanticEmissionActionId()
    data class EndBeforeExit(val block: ArcBlockId) : ArcSemanticEmissionActionId()

    /** [throwingOperation] is non-null exactly for a physical exceptional successor. */
    data class EndOnEdge(
        val edge: ArcSSAEdge,
        val throwingOperation: ArcSemanticEmissionOperationId?,
        val splitBlock: ArcBlockId?,
    ) : ArcSemanticEmissionActionId()
}

/** Exact object identities which codegen must present when consuming [id]. */
internal data class ArcSemanticEmissionActionBinding<T : Any>(
    val id: ArcSemanticEmissionActionId,
    val bindingIdentities: List<T>,
)

internal data class ArcSemanticPhiEmissionSelection<T : Any>(
    val functionBinding: T,
    val plan: ArcSemanticPhiWebPlan,
    val actions: Map<ArcSemanticEmissionActionId, ArcSemanticEmissionActionBinding<T>>,
)

/** Fail-closed shape-drift guard. Every planned action must be emitted exactly once. */
internal class ArcSemanticPhiEmissionLedger<T : Any>(
    selection: ArcSemanticPhiEmissionSelection<T>,
    functionBinding: T,
) {
    private val expected = selection.actions
    private val consumed = linkedSetOf<ArcSemanticEmissionActionId>()

    init {
        check(selection.functionBinding === functionBinding) {
            "semantic ARC emission ledger was opened for a different function identity"
        }
    }

    fun consume(id: ArcSemanticEmissionActionId, bindingIdentities: List<T>) {
        val action = expected[id] ?: error("unknown semantic ARC emission action: $id")
        check(action.bindingIdentities.size == bindingIdentities.size &&
                action.bindingIdentities.indices.all { action.bindingIdentities[it] === bindingIdentities[it] }) {
            "semantic ARC emission binding identity drifted: $id"
        }
        check(consumed.add(id)) { "duplicate semantic ARC emission action: $id" }
    }

    fun verifyComplete() {
        check(consumed == expected.keys) {
            "semantic ARC emission incomplete: missing=${expected.keys - consumed}, unexpected=${consumed - expected.keys}"
        }
    }
}

internal enum class ArcSemanticPhiEmissionRejectionReason {
    IncompleteLoweredIRWalk,
    ShapeDrift,
    DuplicateBinding,
    AnalysisPlanMismatch,
    MissingExceptionalSuccessor,
    AmbiguousExceptionalSuccessor,
    InvalidExceptionalSuccessor,
    MissingCriticalEdgeSplit,
    DuplicateCriticalEdgeMapping,
    InvalidCriticalEdgeSplit,
    MissingBarrierBinding,
    DuplicateBarrierBinding,
}

internal data class ArcSemanticPhiEmissionRejection(
    val reason: ArcSemanticPhiEmissionRejectionReason,
    val detail: String,
)

internal data class ArcSemanticPhiEmissionAdapterResult<T : Any>(
    val selection: ArcSemanticPhiEmissionSelection<T>?,
    val rejections: List<ArcSemanticPhiEmissionRejection>,
)

/**
 * Authenticates a synthetic SemanticARC plan against exact lowered identities before codegen is
 * allowed to mutate LLVM. This closes the gap between block-level pruned liveness and per-invoke
 * exceptional successors without silently guessing which throwing operation an edge belongs to.
 */
internal object ArcSemanticPhiEmissionAdapter {
    fun <T : Any> adapt(
        input: ArcSemanticARCInput,
        plan: ArcSemanticPhiWebPlan,
        body: ArcSemanticPhiLoweredBody<T>,
    ): ArcSemanticPhiEmissionAdapterResult<T> {
        val rejected = mutableListOf<ArcSemanticPhiEmissionRejection>()
        fun reject(reason: ArcSemanticPhiEmissionRejectionReason, detail: String) {
            rejected += ArcSemanticPhiEmissionRejection(reason, detail)
        }

        if (!body.completeLoweredIRWalk) reject(
            ArcSemanticPhiEmissionRejectionReason.IncompleteLoweredIRWalk,
            "the lowered body inventory is not complete",
        )
        if (body.cfg != input.cfg) reject(
            ArcSemanticPhiEmissionRejectionReason.ShapeDrift,
            "the lowered CFG differs from the analyzed CFG",
        )

        val expectedOperations = input.cfg.blocks.values.flatMapTo(linkedSetOf()) { block ->
            block.operations.indices.map { ArcSemanticEmissionOperationId(block.id, it) }
        }
        if (body.blockBindings.keys != input.cfg.blocks.keys || body.operationBindings.keys != expectedOperations) {
            reject(
                ArcSemanticPhiEmissionRejectionReason.ShapeDrift,
                "every analyzed block and operation must have one exact lowered binding",
            )
        }

        val allBaseBindings = body.blockBindings.values + body.operationBindings.values
        val uniqueBaseBindings = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        if (allBaseBindings.any { !uniqueBaseBindings.add(it) }) reject(
            ArcSemanticPhiEmissionRejectionReason.DuplicateBinding,
            "one lowered identity is bound to more than one block or operation",
        )

        val reproduced = ArcSemanticPhiWebAnalysis.analyze(input).accepted.singleOrNull { it == plan }
        if (reproduced == null) reject(
            ArcSemanticPhiEmissionRejectionReason.AnalysisPlanMismatch,
            "the plan is not an accepted result of the exact analyzed input",
        )

        val edgeFrontiers = plan.frontier.filterIsInstance<ArcSemanticLifetimeFrontier.OnEdge>()
        val exceptionalFrontiers = edgeFrontiers.filter { it.edge.kind == ArcSSAEdgeKind.Exceptional }
        val expectedExceptionalEdges = exceptionalFrontiers.mapTo(linkedSetOf()) { it.edge }
        val suppliedExceptionalEdges = body.exceptionalSuccessors.mapTo(linkedSetOf()) { it.edge }
        if (suppliedExceptionalEdges != expectedExceptionalEdges) reject(
            ArcSemanticPhiEmissionRejectionReason.ShapeDrift,
            "exceptional successor inventory differs from the planned exceptional frontier",
        )
        expectedExceptionalEdges.sortedWith(edgeComparator).forEach { edge ->
            val mappings = body.exceptionalSuccessors.filter { it.edge == edge }
            val throwingOperations = input.cfg.blocks.getValue(edge.from).operations.withIndex().filter { (_, operation) ->
                operation is ArcSSAOperation.Use && operation.mayThrow
            }
            when {
                throwingOperations.size > 1 -> reject(
                    ArcSemanticPhiEmissionRejectionReason.AmbiguousExceptionalSuccessor,
                    "${edge.from} has ${throwingOperations.size} throwing operations but the analysis frontier has no operation identity",
                )
                mappings.isEmpty() -> reject(
                    ArcSemanticPhiEmissionRejectionReason.MissingExceptionalSuccessor,
                    "no exact throwing operation owns exceptional edge $edge",
                )
                mappings.size > 1 -> reject(
                    ArcSemanticPhiEmissionRejectionReason.AmbiguousExceptionalSuccessor,
                    "exceptional edge $edge has ${mappings.size} exact-operation mappings",
                )
                throwingOperations.size != 1 -> reject(
                    ArcSemanticPhiEmissionRejectionReason.InvalidExceptionalSuccessor,
                    "exceptional edge $edge has no unique throwing operation",
                )
                else -> {
                    val mapping = mappings.single()
                    val expectedCall = ArcSemanticEmissionOperationId(edge.from, throwingOperations.single().index)
                    if (mapping.call != expectedCall || mapping.callBinding !== body.operationBindings[expectedCall] ||
                        mapping.successorBinding !== body.blockBindings[edge.to]
                    ) reject(
                        ArcSemanticPhiEmissionRejectionReason.InvalidExceptionalSuccessor,
                        "exceptional edge $edge is not bound to its exact call and successor identities",
                    )
                }
            }
        }

        val criticalFrontiers = edgeFrontiers.filter { it.requiresEdgeSplit }
        val expectedCriticalEdges = criticalFrontiers.mapTo(linkedSetOf()) { it.edge }
        val suppliedCriticalEdges = body.criticalEdgeSplits.mapTo(linkedSetOf()) { it.edge }
        if (suppliedCriticalEdges != expectedCriticalEdges) reject(
            ArcSemanticPhiEmissionRejectionReason.ShapeDrift,
            "critical-edge split inventory differs from the planned frontier",
        )
        val splitIds = linkedSetOf<ArcBlockId>()
        val splitBindings = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        body.criticalEdgeSplits.groupBy { it.edge }.forEach { (edge, mappings) ->
            if (mappings.size != 1) reject(
                ArcSemanticPhiEmissionRejectionReason.DuplicateCriticalEdgeMapping,
                "critical edge $edge has ${mappings.size} split mappings",
            )
            mappings.forEach { mapping ->
                if (mapping.edge !in expectedCriticalEdges || mapping.splitBlock in input.cfg.blocks ||
                    !splitIds.add(mapping.splitBlock) || !splitBindings.add(mapping.splitBlockBinding) ||
                    uniqueBaseBindings.contains(mapping.splitBlockBinding)
                ) reject(
                    ArcSemanticPhiEmissionRejectionReason.InvalidCriticalEdgeSplit,
                    "critical edge $edge does not have a unique new split-block identity",
                )
            }
        }
        expectedCriticalEdges.filter { edge -> body.criticalEdgeSplits.none { it.edge == edge } }.forEach { edge ->
            reject(
                ArcSemanticPhiEmissionRejectionReason.MissingCriticalEdgeSplit,
                "critical edge $edge has no reserved split block",
            )
        }

        val barrierFrontiers = plan.frontier.filterIsInstance<ArcSemanticLifetimeFrontier.BeforeBarrier>()
            .mapTo(linkedSetOf()) { it.barrier }
        val suppliedBarriers = body.barrierBindings.mapTo(linkedSetOf()) { it.barrier }
        if (suppliedBarriers != barrierFrontiers) reject(
            ArcSemanticPhiEmissionRejectionReason.ShapeDrift,
            "barrier binding inventory differs from the planned frontier",
        )
        val uniqueBarrierBindings = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        if (body.barrierBindings.any { !uniqueBarrierBindings.add(it.binding) }) reject(
            ArcSemanticPhiEmissionRejectionReason.DuplicateBarrierBinding,
            "one lowered operation identity was reused for multiple semantic ARC barriers",
        )
        body.barrierBindings.groupBy { it.barrier }.forEach { (barrier, mappings) ->
            if (mappings.size > 1) reject(
                ArcSemanticPhiEmissionRejectionReason.DuplicateBarrierBinding,
                "barrier $barrier has ${mappings.size} bindings",
            )
            mappings.forEach { mapping ->
                val position = ArcSemanticEmissionOperationId(barrier.block, barrier.operationIndex)
                if (mapping.binding !== body.operationBindings[position] ||
                    body.criticalEdgeSplits.any { it.splitBlockBinding === mapping.binding }
                ) reject(
                    ArcSemanticPhiEmissionRejectionReason.MissingBarrierBinding,
                    "barrier $barrier is not the exact lowered operation identity at its position",
                )
            }
        }
        barrierFrontiers.filter { barrier -> body.barrierBindings.none { it.barrier == barrier } }.forEach { barrier ->
            reject(ArcSemanticPhiEmissionRejectionReason.MissingBarrierBinding, "barrier $barrier has no exact identity")
        }

        if (rejected.isNotEmpty()) return ArcSemanticPhiEmissionAdapterResult(null, rejected)

        val definitions = linkedMapOf<ArcSSAValue, ArcSemanticEmissionOperationId>()
        input.cfg.blocks.values.forEach { block -> block.operations.forEachIndexed { index, operation ->
            operation.emissionResultOrNull()?.let { definitions[it] = ArcSemanticEmissionOperationId(block.id, index) }
        } }
        val actions = linkedMapOf<ArcSemanticEmissionActionId, ArcSemanticEmissionActionBinding<T>>()
        fun add(id: ArcSemanticEmissionActionId, bindings: List<T>) {
            check(actions.put(id, ArcSemanticEmissionActionBinding(id, bindings)) == null) {
                "duplicate semantic ARC emission action id: $id"
            }
        }
        plan.joins.sortedBy { it.name }.forEach { joined ->
            val definition = definitions.getValue(joined)
            add(ArcSemanticEmissionActionId.ConvertJoin(joined, definition), listOf(body.operationBindings.getValue(definition)))
        }
        plan.reborrows.sortedWith(compareBy({ it.edge.from.name }, { it.edge.to.name }, { it.joined.name })).forEach { reborrow ->
            val joinDefinition = definitions.getValue(reborrow.joined)
            add(
                ArcSemanticEmissionActionId.Reborrow(reborrow.joined, reborrow.edge),
                listOf(
                    body.blockBindings.getValue(reborrow.edge.from),
                    body.operationBindings.getValue(joinDefinition),
                    body.blockBindings.getValue(reborrow.edge.to),
                ),
            )
        }
        plan.seeds.sortedBy { it.copy.name }.forEach { seed ->
            val definition = definitions.getValue(seed.copy)
            add(ArcSemanticEmissionActionId.EliminateCopy(seed.copy, definition), listOf(body.operationBindings.getValue(definition)))
        }
        plan.frontier.sortedBy { it.toString() }.forEach { frontier -> when (frontier) {
            is ArcSemanticLifetimeFrontier.AfterOperation -> {
                val operation = ArcSemanticEmissionOperationId(frontier.block, frontier.operationIndex)
                add(ArcSemanticEmissionActionId.EndAfterOperation(operation), listOf(body.operationBindings.getValue(operation)))
            }
            is ArcSemanticLifetimeFrontier.BeforeBarrier -> {
                val binding = body.barrierBindings.single { it.barrier == frontier.barrier }.binding
                add(ArcSemanticEmissionActionId.EndBeforeBarrier(frontier.barrier), listOf(binding))
            }
            is ArcSemanticLifetimeFrontier.BeforeExit -> add(
                ArcSemanticEmissionActionId.EndBeforeExit(frontier.block),
                listOf(body.blockBindings.getValue(frontier.block)),
            )
            is ArcSemanticLifetimeFrontier.OnEdge -> {
                val exceptional = body.exceptionalSuccessors.singleOrNull { it.edge == frontier.edge }
                val split = body.criticalEdgeSplits.singleOrNull { it.edge == frontier.edge }
                val bindings = if (exceptional != null) {
                    listOfNotNull(exceptional.callBinding, exceptional.successorBinding, split?.splitBlockBinding)
                } else {
                    listOfNotNull(
                        body.blockBindings.getValue(frontier.edge.from),
                        body.blockBindings.getValue(frontier.edge.to),
                        split?.splitBlockBinding,
                    )
                }
                add(
                    ArcSemanticEmissionActionId.EndOnEdge(frontier.edge, exceptional?.call, split?.splitBlock),
                    bindings,
                )
            }
        } }
        return ArcSemanticPhiEmissionAdapterResult(
            ArcSemanticPhiEmissionSelection(body.functionBinding, plan, actions),
            emptyList(),
        )
    }

    private val edgeComparator = compareBy<ArcSSAEdge>({ it.from.name }, { it.to.name }, { it.kind.name })
}

private fun ArcSSAOperation.emissionResultOrNull(): ArcSSAValue? = when (this) {
    is ArcSSAOperation.Introduce -> result
    is ArcSSAOperation.Forward -> result
    is ArcSSAOperation.Reborrow -> result
    is ArcSSAOperation.Join -> result
    is ArcSSAOperation.Borrow -> result
    is ArcSSAOperation.InitializeOwned,
    is ArcSSAOperation.InitializeImmortal,
    is ArcSSAOperation.JoinSlot,
    is ArcSSAOperation.EndBorrow,
    is ArcSSAOperation.MoveOwned,
    is ArcSSAOperation.DestroyOwned,
    is ArcSSAOperation.Use,
    is ArcSSAOperation.DeinitBarrier -> null
}
