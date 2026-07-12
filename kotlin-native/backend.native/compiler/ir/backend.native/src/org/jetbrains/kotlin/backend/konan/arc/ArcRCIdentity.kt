/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

/** A stable operation position in the emission-independent ownership CFG. */
internal data class ArcRCPosition(val block: ArcBlockId, val operationIndex: Int)

internal enum class ArcRCBarrierKind {
    Deinitialization,
    ExceptionalCall,
    Consume,
    Escape,
    UnknownEffect,
    ControlJoin,
}

internal data class ArcRCBarrier(
    val position: ArcRCPosition,
    val kind: ArcRCBarrierKind,
    val location: ArcPlanLocation? = null,
)

/**
 * Abstract lifetime ends. These are queries for a later planner, not emission instructions.
 */
internal sealed class ArcRCLifetimeFrontier {
    data class AfterOperation(val position: ArcRCPosition) : ArcRCLifetimeFrontier()
    data class ConsumedAt(val position: ArcRCPosition) : ArcRCLifetimeFrontier()
    data class BeforeExit(val block: ArcBlockId) : ArcRCLifetimeFrontier()
    data class OnEdge(val edge: ArcSSAEdge) : ArcRCLifetimeFrontier()
    data class BeforeBarrier(val barrier: ArcRCBarrier) : ArcRCLifetimeFrontier()
}

internal enum class ArcRCIdentityIssueKind {
    DuplicateDefinition,
    UnresolvedCycle,
    MissingDefinition,
    OverlappingOwnershipWeb,
}

internal data class ArcRCIdentityIssue(
    val kind: ArcRCIdentityIssueKind,
    val value: ArcSSAValue,
    val detail: String,
)

/**
 * Canonical RC identity of one SSA value.
 *
 * [provenanceRoots] is deliberately a set: a phi selecting distinct owned introductions has no
 * single allocation identity. [ownershipWeb] is the complete, verified path-disjoint web which a
 * later optimizer may use to reason about that set as one joined lifetime. Reborrows keep the RC
 * identity of their source while recording independent lifetime [anchorRoots].
 */
internal data class ArcRCIdentity(
    val value: ArcSSAValue,
    val provenanceRoots: Set<ArcSSAValue>,
    val anchorRoots: Set<ArcSSAValue>,
    val ownershipWeb: ArcOwnershipSSAWeb?,
) {
    val singleRoot: ArcSSAValue? get() = provenanceRoots.singleOrNull()
}

internal data class ArcRCIdentityInput(
    val cfg: ArcOwnershipSSAInput,
    val ownershipWebs: List<ArcOwnershipSSAWeb>,
)

internal data class ArcRCIdentityResult(
    val identities: Map<ArcSSAValue, ArcRCIdentity>,
    val barriers: List<ArcRCBarrier>,
    val liveness: ArcRCPrunedLiveness,
    val issues: List<ArcRCIdentityIssue>,
) {
    fun identity(value: ArcSSAValue): ArcRCIdentity? = identities[value]

    /**
     * RC traffic may move inside one block only when it crosses no observable ownership barrier.
     * Cross-block motion remains disabled until dominance and edge-splitting proofs are integrated.
     */
    fun canMoveWithinBlock(value: ArcSSAValue, block: ArcBlockId, fromIndex: Int, toIndex: Int): Boolean {
        val identity = identities[value] ?: return false
        val operationCount = liveness.operationCount(block) ?: return false
        if (fromIndex !in 0 until operationCount || toIndex !in 0 until operationCount || fromIndex > toIndex) return false
        if (fromIndex == toIndex) return true
        if (!liveness.isLiveAfter(value, block, fromIndex) || !liveness.isLiveBefore(value, block, toIndex)) return false
        return barriers.none { barrier ->
            barrier.position.block == block &&
                    barrier.position.operationIndex in (fromIndex + 1)..toIndex &&
                    liveness.barrierAffects(identity, barrier)
        }
    }
}

/**
 * Backward, pruned liveness over RC identity families. This deliberately answers questions only;
 * it does not insert destroys or split edges.
 */
internal class ArcRCPrunedLiveness internal constructor(
    private val cfg: ArcOwnershipSSAInput,
    private val identities: Map<ArcSSAValue, ArcRCIdentity>,
    private val barriers: List<ArcRCBarrier>,
) {
    private data class FamilyLiveness(
        val liveBefore: Map<ArcBlockId, BooleanArray>,
        val liveAfter: Map<ArcBlockId, BooleanArray>,
        val frontier: Set<ArcRCLifetimeFrontier>,
    )

    private val cache = mutableMapOf<Set<ArcSSAValue>, FamilyLiveness>()

    internal fun operationCount(block: ArcBlockId): Int? = cfg.blocks[block]?.operations?.size

    @Suppress("UNUSED_PARAMETER")
    internal fun barrierAffects(identity: ArcRCIdentity, barrier: ArcRCBarrier): Boolean {
        // Until exceptional cleanup and observable deinit ordering are represented by the motion
        // proof itself, every classified effect is a global barrier for all RC identities.
        return true
    }

    fun isLiveBefore(value: ArcSSAValue, block: ArcBlockId, operationIndex: Int): Boolean {
        val state = family(value) ?: return false
        return state.liveBefore[block]?.getOrNull(operationIndex) == true
    }

    fun isLiveAfter(value: ArcSSAValue, block: ArcBlockId, operationIndex: Int): Boolean {
        val state = family(value) ?: return false
        return state.liveAfter[block]?.getOrNull(operationIndex) == true
    }

    fun lifetimeFrontier(value: ArcSSAValue): Set<ArcRCLifetimeFrontier> =
        family(value)?.frontier.orEmpty()

    private fun ArcSSAValue.hasOverlappingIdentity(identity: ArcRCIdentity): Boolean {
        val candidate = identities[this] ?: return false
        return candidate.provenanceRoots.any { it in identity.provenanceRoots } ||
                (candidate.ownershipWeb != null && candidate.ownershipWeb === identity.ownershipWeb)
    }

    private fun family(value: ArcSSAValue): FamilyLiveness? {
        val identity = identities[value] ?: return null
        val family = identities.values.asSequence()
            .filter { candidate ->
                candidate.provenanceRoots.any { it in identity.provenanceRoots } ||
                        (identity.ownershipWeb != null && candidate.ownershipWeb === identity.ownershipWeb)
            }
            .mapTo(linkedSetOf()) { it.value }
        return cache.getOrPut(family) { compute(family, identity) }
    }

    private fun compute(family: Set<ArcSSAValue>, identity: ArcRCIdentity): FamilyLiveness {
        val successors = cfg.blocks.keys.associateWith { block ->
            cfg.edges.filter { it.from == block }.map { it.to }
        }
        val liveIn = cfg.blocks.keys.associateWithTo(linkedMapOf()) { false }
        val liveOut = cfg.blocks.keys.associateWithTo(linkedMapOf()) { false }
        val before = linkedMapOf<ArcBlockId, BooleanArray>()
        val after = linkedMapOf<ArcBlockId, BooleanArray>()

        var changed = true
        while (changed) {
            changed = false
            cfg.blocks.values.toList().asReversed().forEach { block ->
                val blockLiveOut = successors.getValue(block.id).any { liveIn[it] == true }
                var live = blockLiveOut
                val newAfter = BooleanArray(block.operations.size)
                val newBefore = BooleanArray(block.operations.size)
                for (index in block.operations.indices.reversed()) {
                    val operation = block.operations[index]
                    newAfter[index] = live
                    live = transferBackward(operation, family, live)
                    newBefore[index] = live
                }
                if (liveOut[block.id] != blockLiveOut || liveIn[block.id] != live ||
                    !newAfter.contentEquals(after[block.id] ?: BooleanArray(block.operations.size)) ||
                    !newBefore.contentEquals(before[block.id] ?: BooleanArray(block.operations.size))
                ) {
                    liveOut[block.id] = blockLiveOut
                    liveIn[block.id] = live
                    after[block.id] = newAfter
                    before[block.id] = newBefore
                    changed = true
                }
            }
        }

        val frontier = linkedSetOf<ArcRCLifetimeFrontier>()
        cfg.blocks.values.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                val position = ArcRCPosition(block.id, index)
                when {
                    operation is ArcSSAOperation.Use && operation.value in family &&
                            operation.kind == ArcSSAUseKind.Consume ->
                        frontier += ArcRCLifetimeFrontier.ConsumedAt(position)
                    before.getValue(block.id)[index] && !after.getValue(block.id)[index] -> {
                        val barrier = barriers.firstOrNull { it.position == position }
                        if (barrier != null && barrierAffects(identity, barrier)) {
                            frontier += ArcRCLifetimeFrontier.BeforeBarrier(barrier)
                        } else {
                            frontier += ArcRCLifetimeFrontier.AfterOperation(position)
                        }
                    }
                }
            }
            val endsLive = block.operations.indices.lastOrNull()?.let { after.getValue(block.id)[it] }
                ?: liveOut.getValue(block.id)
            if (endsLive && successors.getValue(block.id).isEmpty()) {
                frontier += ArcRCLifetimeFrontier.BeforeExit(block.id)
            }
        }
        cfg.blocks.values.forEach { block ->
            val exceptionalEdges = cfg.edges.filter { it.from == block.id && it.kind == ArcSSAEdgeKind.Exceptional }
            val throwingUses = block.operations.filterIsInstance<ArcSSAOperation.Use>().filter { it.mayThrow }
            val throwingUse = throwingUses.singleOrNull()
            val edge = exceptionalEdges.singleOrNull()
            if (throwingUse != null && edge != null && throwingUse.value in family &&
                !reachableFrom(edge.to).any { reachable ->
                    cfg.blocks.getValue(reachable).operations.any { it is ArcSSAOperation.Use && it.value in family }
                }
            ) {
                frontier += ArcRCLifetimeFrontier.OnEdge(edge)
            }
        }
        identity.ownershipWeb?.destroyPlacements?.forEach { placement ->
            frontier += when (placement) {
                is ArcSSADestroyPlacement.BeforeOperation -> ArcRCLifetimeFrontier.BeforeBarrier(
                    barriers.firstOrNull {
                        it.position == ArcRCPosition(placement.block, placement.operationIndex)
                    } ?: ArcRCBarrier(
                        ArcRCPosition(placement.block, placement.operationIndex),
                        ArcRCBarrierKind.Deinitialization,
                    )
                )
                is ArcSSADestroyPlacement.BeforeExit -> ArcRCLifetimeFrontier.BeforeExit(placement.block)
                is ArcSSADestroyPlacement.OnEdge -> ArcRCLifetimeFrontier.OnEdge(placement.edge)
            }
        }
        return FamilyLiveness(before, after, frontier)
    }

    private fun reachableFrom(start: ArcBlockId): Set<ArcBlockId> {
        val result = linkedSetOf<ArcBlockId>()
        val worklist = ArrayDeque<ArcBlockId>().apply { add(start) }
        while (worklist.isNotEmpty()) {
            val block = worklist.removeFirst()
            if (!result.add(block)) continue
            cfg.edges.filter { it.from == block }.forEach { worklist += it.to }
        }
        return result
    }

    private fun transferBackward(operation: ArcSSAOperation, family: Set<ArcSSAValue>, liveAfter: Boolean): Boolean = when (operation) {
        is ArcSSAOperation.Use -> if (operation.value in family) true else liveAfter
        is ArcSSAOperation.Introduce -> if (operation.result in family) false else liveAfter
        // Forwarding and reborrow definitions do not end an identity family; their source and
        // result are the same RC identity at different SSA points.
        is ArcSSAOperation.Forward -> if (operation.source in family || operation.result in family) liveAfter else liveAfter
        is ArcSSAOperation.Reborrow -> if (operation.source in family || operation.result in family) liveAfter else liveAfter
        is ArcSSAOperation.Join -> if (operation.result in family || operation.incoming.values.any { it in family }) liveAfter else liveAfter
        is ArcSSAOperation.DeinitBarrier -> liveAfter
    }

}

internal object ArcRCIdentityAnalysis {
    private data class Definition(val position: ArcRCPosition, val operation: ArcSSAOperation)

    fun analyze(input: ArcRCIdentityInput): ArcRCIdentityResult {
        val definitions = linkedMapOf<ArcSSAValue, Definition>()
        val issues = mutableListOf<ArcRCIdentityIssue>()
        val invalidValues = linkedSetOf<ArcSSAValue>()
        input.cfg.blocks.values.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                operation.resultOrNull()?.let { value ->
                    val previous = definitions.put(value, Definition(ArcRCPosition(block.id, index), operation))
                    if (previous != null) {
                        invalidValues += value
                        issues += ArcRCIdentityIssue(
                            ArcRCIdentityIssueKind.DuplicateDefinition,
                            value,
                            "defined at both ${previous.position} and ${ArcRCPosition(block.id, index)}",
                        )
                    }
                }
            }
        }

        val webByMember = linkedMapOf<ArcSSAValue, ArcOwnershipSSAWeb>()
        input.ownershipWebs.forEach { web ->
            web.members.forEach { member ->
                val previous = webByMember.put(member, web)
                if (previous != null && previous !== web) {
                    invalidValues += previous.members
                    invalidValues += web.members
                    previous.members.forEach { webByMember.remove(it) }
                    web.members.forEach { webByMember.remove(it) }
                    issues += ArcRCIdentityIssue(
                        ArcRCIdentityIssueKind.OverlappingOwnershipWeb,
                        member,
                        "member belongs to both ${previous.join} and ${web.join}",
                    )
                }
            }
        }

        definitions.forEach { (value, definition) ->
            val missing = definition.operation.dependencies().filter { it !in definitions }
            if (missing.isNotEmpty()) {
                invalidValues += value
                issues += ArcRCIdentityIssue(
                    ArcRCIdentityIssueKind.MissingDefinition,
                    value,
                    "identity dependencies have no definition: ${missing.joinToString()}",
                )
            }
        }
        input.ownershipWebs.flatMap { it.members }.filter { it !in definitions }.forEach { member ->
            invalidValues += member
            issues += ArcRCIdentityIssue(
                ArcRCIdentityIssueKind.MissingDefinition,
                member,
                "ownership web member has no definition",
            )
        }
        var invalidChanged = true
        while (invalidChanged) {
            invalidChanged = false
            definitions.forEach { (value, definition) ->
                if (value !in invalidValues && definition.operation.dependencies().any { it in invalidValues }) {
                    invalidValues += value
                    invalidChanged = true
                }
            }
            input.ownershipWebs.forEach { web ->
                if (web.members.any { it in invalidValues } && invalidValues.addAll(web.members)) {
                    web.members.forEach { webByMember.remove(it) }
                    invalidChanged = true
                }
            }
        }

        val roots = linkedMapOf<ArcSSAValue, Set<ArcSSAValue>>()
        val anchors = linkedMapOf<ArcSSAValue, Set<ArcSSAValue>>()
        definitions.forEach { (value, definition) ->
            if (value in invalidValues) return@forEach
            if (definition.operation is ArcSSAOperation.Introduce) {
                roots[value] = setOf(value)
                anchors[value] = canonicalKnownRoots(definition.operation.anchorDependencies, roots)
            }
        }
        var changed = true
        while (changed) {
            changed = false
            definitions.forEach { (value, definition) ->
                if (value in invalidValues) return@forEach
                val resolvedRoots = when (val operation = definition.operation) {
                    is ArcSSAOperation.Introduce -> setOf(value)
                    is ArcSSAOperation.Forward -> roots[operation.source]
                    is ArcSSAOperation.Reborrow -> roots[operation.source]
                    is ArcSSAOperation.Join -> operation.incoming.values.map { roots[it] }
                        .takeIf { it.none { rootsForInput -> rootsForInput == null } }
                        ?.filterNotNull()?.flatten()?.toCollection(linkedSetOf())
                    else -> null
                }
                val resolvedAnchors = when (val operation = definition.operation) {
                    is ArcSSAOperation.Introduce -> canonicalKnownRoots(operation.anchorDependencies, roots)
                    is ArcSSAOperation.Forward -> anchors[operation.source]
                    is ArcSSAOperation.Reborrow -> canonicalKnownRoots(operation.anchorDependencies, roots)
                    is ArcSSAOperation.Join -> operation.incoming.values.map { anchors[it] }
                        .takeIf { it.none { anchorsForInput -> anchorsForInput == null } }
                        ?.filterNotNull()?.flatten()?.toCollection(linkedSetOf())
                    else -> null
                }
                if (resolvedRoots != null && roots[value] != resolvedRoots) {
                    roots[value] = resolvedRoots
                    changed = true
                }
                if (resolvedAnchors != null && anchors[value] != resolvedAnchors) {
                    anchors[value] = resolvedAnchors
                    changed = true
                }
            }
        }

        definitions.keys.filter { it !in roots && it !in invalidValues }.forEach { value ->
            val hasMissingDependency = definitions.getValue(value).operation.dependencies().any { it !in definitions }
            issues += ArcRCIdentityIssue(
                if (hasMissingDependency) ArcRCIdentityIssueKind.MissingDefinition else ArcRCIdentityIssueKind.UnresolvedCycle,
                value,
                if (hasMissingDependency) "identity dependency has no definition" else "identity forwarding cycle has no canonical introducer",
            )
        }
        val identities = roots.filterKeys { it !in invalidValues }.mapValues { (value, provenance) ->
            ArcRCIdentity(value, provenance, anchors[value].orEmpty(), webByMember[value])
        }
        val barriers = collectBarriers(input.cfg)
        val liveness = ArcRCPrunedLiveness(input.cfg, identities, barriers)
        return ArcRCIdentityResult(identities, barriers, liveness, issues.distinct())
    }

    private fun canonicalKnownRoots(values: Set<ArcSSAValue>, roots: Map<ArcSSAValue, Set<ArcSSAValue>>): Set<ArcSSAValue> =
        values.flatMapTo(linkedSetOf()) { roots[it] ?: setOf(it) }

    private fun collectBarriers(cfg: ArcOwnershipSSAInput): List<ArcRCBarrier> = buildList {
        cfg.blocks.values.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                val kind = when (operation) {
                    is ArcSSAOperation.DeinitBarrier -> ArcRCBarrierKind.Deinitialization
                    is ArcSSAOperation.Join -> ArcRCBarrierKind.ControlJoin
                    is ArcSSAOperation.Use -> when {
                        operation.kind == ArcSSAUseKind.Consume -> ArcRCBarrierKind.Consume
                        operation.kind == ArcSSAUseKind.Escape -> ArcRCBarrierKind.Escape
                        operation.kind == ArcSSAUseKind.UnknownConsume -> ArcRCBarrierKind.UnknownEffect
                        operation.mayThrow -> ArcRCBarrierKind.ExceptionalCall
                        else -> null
                    }
                    else -> null
                }
                if (kind != null) add(ArcRCBarrier(ArcRCPosition(block.id, index), kind, operation.location))
            }
        }
    }

    private fun ArcSSAOperation.resultOrNull(): ArcSSAValue? = when (this) {
        is ArcSSAOperation.Introduce -> result
        is ArcSSAOperation.Forward -> result
        is ArcSSAOperation.Reborrow -> result
        is ArcSSAOperation.Join -> result
        is ArcSSAOperation.Use, is ArcSSAOperation.DeinitBarrier -> null
    }

    private fun ArcSSAOperation.dependencies(): List<ArcSSAValue> = when (this) {
        is ArcSSAOperation.Introduce -> anchorDependencies.toList()
        is ArcSSAOperation.Forward -> listOf(source)
        is ArcSSAOperation.Reborrow -> listOf(source) + anchorDependencies
        is ArcSSAOperation.Join -> incoming.values.toList()
        is ArcSSAOperation.Use -> listOf(value)
        is ArcSSAOperation.DeinitBarrier -> emptyList()
    }
}
