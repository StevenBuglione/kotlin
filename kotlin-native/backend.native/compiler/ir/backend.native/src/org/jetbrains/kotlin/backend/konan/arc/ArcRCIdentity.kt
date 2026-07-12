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
    UseAfterConsume,
    UnmodeledExceptionalConsume,
    UnsupportedEscape,
    UnsupportedUnknownConsume,
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

    private val cache = mutableMapOf<ArcSSAValue, FamilyLiveness>()

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
        return cache.getOrPut(value) {
            val relevantRoots = identity.provenanceRoots.toMutableSet()
            val family = linkedSetOf<ArcSSAValue>()
            var changed = true
            while (changed) {
                changed = false
                identities.values.forEach { candidate ->
                    val related = candidate.provenanceRoots.any { it in relevantRoots } ||
                            candidate.anchorRoots.any { it in relevantRoots } ||
                            (identity.ownershipWeb != null && candidate.ownershipWeb === identity.ownershipWeb)
                    if (related && family.add(candidate.value)) {
                        relevantRoots += candidate.provenanceRoots
                        relevantRoots += candidate.anchorRoots
                        changed = true
                    }
                }
            }
            compute(family, identity)
        }
    }

    private fun compute(family: Set<ArcSSAValue>, identity: ArcRCIdentity): FamilyLiveness {
        val normalSuccessors = cfg.blocks.keys.associateWith { block ->
            cfg.edges.filter { it.from == block && it.kind == ArcSSAEdgeKind.Normal }.map { it.to }
        }
        val liveIn = cfg.blocks.keys.associateWithTo(linkedMapOf()) { false }
        val liveOut = cfg.blocks.keys.associateWithTo(linkedMapOf()) { false }
        val before = linkedMapOf<ArcBlockId, BooleanArray>()
        val after = linkedMapOf<ArcBlockId, BooleanArray>()

        var changed = true
        while (changed) {
            changed = false
            cfg.blocks.values.toList().asReversed().forEach { block ->
                val blockLiveOut = normalSuccessors.getValue(block.id).any { liveIn[it] == true }
                var live = blockLiveOut
                val newAfter = BooleanArray(block.operations.size)
                val newBefore = BooleanArray(block.operations.size)
                for (index in block.operations.indices.reversed()) {
                    val operation = block.operations[index]
                    newAfter[index] = live
                    val exceptionalLive = if (operation is ArcSSAOperation.Use && operation.mayThrow) {
                        cfg.edges.any { edge ->
                            edge.from == block.id && edge.kind == ArcSSAEdgeKind.Exceptional && liveIn[edge.to] == true
                        }
                    } else false
                    live = transferBackward(operation, family, identity, live) || exceptionalLive
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

        val hasUnsupportedTerminalEffect = cfg.blocks.values.any { block ->
            block.operations.any { operation ->
                operation is ArcSSAOperation.Use && operation.value in family &&
                        (operation.kind == ArcSSAUseKind.Escape || operation.kind == ArcSSAUseKind.UnknownConsume)
            }
        }
        if (hasUnsupportedTerminalEffect) return FamilyLiveness(before, after, emptySet())

        val frontier = linkedSetOf<ArcRCLifetimeFrontier>()
        cfg.blocks.values.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                val position = ArcRCPosition(block.id, index)
                when {
                    operation is ArcSSAOperation.Use && operation.value in family &&
                            operation.kind == ArcSSAUseKind.Consume &&
                            operation.value.hasQueriedProvenance(identity) ->
                        frontier += ArcRCLifetimeFrontier.ConsumedAt(position)
                    before.getValue(block.id)[index] && !after.getValue(block.id)[index] -> {
                        val barrier = barriers.firstOrNull { it.position == position }
                        val endsAfterOperation = operation is ArcSSAOperation.Use &&
                                operation.kind == ArcSSAUseKind.Borrow || operation.isDistinctConsumeOf(identity)
                        if (barrier != null && barrierAffects(identity, barrier) && !endsAfterOperation
                        ) {
                            frontier += ArcRCLifetimeFrontier.BeforeBarrier(barrier)
                        } else {
                            frontier += ArcRCLifetimeFrontier.AfterOperation(position)
                        }
                    }
                }
            }
            val endsLive = block.operations.indices.lastOrNull()?.let { after.getValue(block.id)[it] }
                ?: liveOut.getValue(block.id)
            if (endsLive && cfg.edges.none { it.from == block.id }) {
                frontier += ArcRCLifetimeFrontier.BeforeExit(block.id)
            }
        }
        cfg.edges.forEach { edge ->
            val block = cfg.blocks.getValue(edge.from)
            val sourceLive = if (edge.kind == ArcSSAEdgeKind.Exceptional) {
                val throwingIndex = block.operations.indices.singleOrNull { index ->
                    (block.operations[index] as? ArcSSAOperation.Use)?.mayThrow == true
                }
                throwingIndex?.let { before.getValue(block.id)[it] } == true
            } else {
                liveOut.getValue(block.id)
            }
            if (sourceLive && liveIn[edge.to] != true) frontier += ArcRCLifetimeFrontier.OnEdge(edge)
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

    private fun transferBackward(
        operation: ArcSSAOperation,
        family: Set<ArcSSAValue>,
        queriedIdentity: ArcRCIdentity,
        liveAfter: Boolean,
    ): Boolean = when (operation) {
        is ArcSSAOperation.Use -> if (operation.value in family) true else liveAfter
        is ArcSSAOperation.Introduce -> if (operation.result in family &&
            identities[operation.result]?.provenanceRoots?.any { it in queriedIdentity.provenanceRoots } == true
        ) false else liveAfter
        // Forwarding and reborrow definitions do not end an identity family; their source and
        // result are the same RC identity at different SSA points.
        is ArcSSAOperation.Forward -> if (operation.source in family || operation.result in family) liveAfter else liveAfter
        is ArcSSAOperation.Reborrow -> if (operation.source in family || operation.result in family) liveAfter else liveAfter
        is ArcSSAOperation.Borrow -> if (operation.source in family || operation.result in family) liveAfter else liveAfter
        is ArcSSAOperation.Join -> if (operation.result in family || operation.incoming.values.any { it in family }) liveAfter else liveAfter
        is ArcSSAOperation.InitializeOwned -> if (operation.value in family) true else liveAfter
        is ArcSSAOperation.InitializeImmortal -> if (operation.value in family) true else liveAfter
        is ArcSSAOperation.JoinSlot -> if (operation.value in family) liveAfter else liveAfter
        is ArcSSAOperation.MoveOwned -> if (operation.value in family) true else liveAfter
        is ArcSSAOperation.DestroyOwned -> if (operation.value in family) true else liveAfter
        is ArcSSAOperation.EndBorrow, is ArcSSAOperation.DeinitBarrier -> liveAfter
    }

    private fun ArcSSAValue.hasQueriedProvenance(queriedIdentity: ArcRCIdentity): Boolean {
        val candidate = identities[this] ?: return false
        return candidate.provenanceRoots.any { it in queriedIdentity.provenanceRoots } ||
                (queriedIdentity.ownershipWeb != null && candidate.ownershipWeb === queriedIdentity.ownershipWeb)
    }

    private fun ArcSSAOperation.isDistinctConsumeOf(queriedIdentity: ArcRCIdentity): Boolean = when (this) {
        is ArcSSAOperation.Use -> kind == ArcSSAUseKind.Consume && !value.hasQueriedProvenance(queriedIdentity)
        is ArcSSAOperation.DestroyOwned -> !value.hasQueriedProvenance(queriedIdentity)
        else -> false
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
                    is ArcSSAOperation.Borrow -> roots[operation.source]
                    is ArcSSAOperation.Join -> operation.incoming.values.map { roots[it] }
                        .takeIf { it.none { rootsForInput -> rootsForInput == null } }
                        ?.filterNotNull()?.flatten()?.toCollection(linkedSetOf())
                    else -> null
                }
                val resolvedAnchors = when (val operation = definition.operation) {
                    is ArcSSAOperation.Introduce -> canonicalKnownRoots(operation.anchorDependencies, roots)
                    is ArcSSAOperation.Forward -> anchors[operation.source]
                    is ArcSSAOperation.Reborrow -> canonicalKnownRoots(operation.anchorDependencies, roots)
                    is ArcSSAOperation.Borrow -> canonicalKnownRoots(setOf(operation.source), roots)
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
        issues += validateTerminalConsumes(input.cfg, identities)
        issues += validateUnsupportedEffects(input.cfg, identities)
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
                    is ArcSSAOperation.Join, is ArcSSAOperation.JoinSlot -> ArcRCBarrierKind.ControlJoin
                    is ArcSSAOperation.DestroyOwned -> ArcRCBarrierKind.Consume
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

    private fun validateTerminalConsumes(
        cfg: ArcOwnershipSSAInput,
        identities: Map<ArcSSAValue, ArcRCIdentity>,
    ): List<ArcRCIdentityIssue> = buildList {
        fun overlaps(left: ArcSSAValue, right: ArcSSAValue): Boolean {
            val leftIdentity = identities[left] ?: return false
            val rightIdentity = identities[right] ?: return false
            return leftIdentity.provenanceRoots.any { it in rightIdentity.provenanceRoots } ||
                    (leftIdentity.ownershipWeb != null && leftIdentity.ownershipWeb === rightIdentity.ownershipWeb)
        }
        fun operationTouches(operation: ArcSSAOperation, consumed: ArcSSAValue): Boolean =
            operation.dependencies().any { overlaps(it, consumed) }

        cfg.blocks.values.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                val consume = operation as? ArcSSAOperation.Use ?: return@forEachIndexed
                if (consume.kind != ArcSSAUseKind.Consume || consume.value !in identities) return@forEachIndexed
                if (consume.mayThrow && cfg.edges.count {
                        it.from == block.id && it.kind == ArcSSAEdgeKind.Exceptional
                    } != 1
                ) {
                    add(ArcRCIdentityIssue(
                        ArcRCIdentityIssueKind.UnmodeledExceptionalConsume,
                        consume.value,
                        "throwing consume at ${ArcRCPosition(block.id, index)} requires exactly one exceptional edge",
                    ))
                }
                val sameBlockUse = block.operations.drop(index + 1).firstOrNull { operationTouches(it, consume.value) }
                val reachable = linkedSetOf<ArcBlockId>()
                val worklist = ArrayDeque<ArcBlockId>().apply {
                    cfg.edges.filter {
                        it.from == block.id && it.kind == ArcSSAEdgeKind.Normal
                    }.forEach { add(it.to) }
                }
                while (worklist.isNotEmpty()) {
                    val next = worklist.removeFirst()
                    if (!reachable.add(next)) continue
                    cfg.edges.filter { it.from == next }.forEach { worklist += it.to }
                }
                val downstreamUse = reachable.asSequence()
                    .flatMap { cfg.blocks.getValue(it).operations.asSequence() }
                    .firstOrNull { operationTouches(it, consume.value) }
                if (sameBlockUse != null || downstreamUse != null) {
                    add(ArcRCIdentityIssue(
                        ArcRCIdentityIssueKind.UseAfterConsume,
                        consume.value,
                        "consume at ${ArcRCPosition(block.id, index)} is not the terminal normal-path identity use",
                    ))
                }
            }
        }
    }

    private fun validateUnsupportedEffects(
        cfg: ArcOwnershipSSAInput,
        identities: Map<ArcSSAValue, ArcRCIdentity>,
    ): List<ArcRCIdentityIssue> = buildList {
        cfg.blocks.values.forEach { block ->
            block.operations.filterIsInstance<ArcSSAOperation.Use>().forEach useLoop@ { use ->
                if (use.value !in identities) return@useLoop
                val kind = when (use.kind) {
                    ArcSSAUseKind.Escape -> ArcRCIdentityIssueKind.UnsupportedEscape
                    ArcSSAUseKind.UnknownConsume -> ArcRCIdentityIssueKind.UnsupportedUnknownConsume
                    ArcSSAUseKind.Borrow, ArcSSAUseKind.Consume -> null
                }
                if (kind != null) add(ArcRCIdentityIssue(
                    kind,
                    use.value,
                    "${use.kind} has no proven local lifetime end",
                ))
            }
        }
    }

    private fun ArcSSAOperation.resultOrNull(): ArcSSAValue? = when (this) {
        is ArcSSAOperation.Introduce -> result
        is ArcSSAOperation.Forward -> result
        is ArcSSAOperation.Reborrow -> result
        is ArcSSAOperation.Join -> result
        is ArcSSAOperation.Borrow -> result
        is ArcSSAOperation.Use,
        is ArcSSAOperation.InitializeOwned,
        is ArcSSAOperation.InitializeImmortal,
        is ArcSSAOperation.JoinSlot,
        is ArcSSAOperation.EndBorrow,
        is ArcSSAOperation.MoveOwned,
        is ArcSSAOperation.DestroyOwned,
        is ArcSSAOperation.DeinitBarrier -> null
    }

    private fun ArcSSAOperation.dependencies(): List<ArcSSAValue> = when (this) {
        is ArcSSAOperation.Introduce -> anchorDependencies.toList()
        is ArcSSAOperation.Forward -> listOf(source)
        is ArcSSAOperation.Reborrow -> listOf(source) + anchorDependencies
        is ArcSSAOperation.Join -> incoming.values.toList()
        is ArcSSAOperation.Borrow -> listOf(source)
        is ArcSSAOperation.Use -> listOf(value)
        is ArcSSAOperation.InitializeOwned -> listOf(value)
        is ArcSSAOperation.InitializeImmortal -> listOf(value)
        is ArcSSAOperation.JoinSlot -> listOf(value)
        is ArcSSAOperation.MoveOwned -> listOf(value)
        is ArcSSAOperation.DestroyOwned -> listOf(value)
        is ArcSSAOperation.EndBorrow, is ArcSSAOperation.DeinitBarrier -> emptyList()
    }
}
