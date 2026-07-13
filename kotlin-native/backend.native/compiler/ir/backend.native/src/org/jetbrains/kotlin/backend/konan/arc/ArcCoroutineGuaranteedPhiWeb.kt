/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

/**
 * Verifies the physical CFG boundary used by the emitted coroutine phi prototype.
 *
 * The store block must reach the natural backedge through a unique chain of unconditional
 * branches, and the loop header must have exactly the ABI entry and that one backedge. This is
 * intentionally independent of LLVM so the fail-closed rule has direct unit coverage.
 */
internal fun <B> hasExactCoroutinePhysicalBackedge(
    successors: Map<B, List<B>>,
    storeBlock: B,
    naturalBackedge: B,
    loopHeader: B,
    entryPredecessor: B,
): Boolean {
    val predecessors = linkedMapOf<B, MutableList<B>>()
    successors.forEach { (block, targets) ->
        targets.forEach { target -> predecessors.getOrPut(target) { mutableListOf() } += block }
    }
    if (predecessors[loopHeader].orEmpty().toSet() != setOf(entryPredecessor, naturalBackedge) ||
        predecessors[loopHeader].orEmpty().size != 2 ||
        successors[naturalBackedge] != listOf(loopHeader)
    ) return false
    var cursor = naturalBackedge
    val visited = linkedSetOf<B>()
    while (cursor != storeBlock) {
        if (!visited.add(cursor)) return false
        val predecessor = predecessors[cursor].orEmpty().singleOrNull() ?: return false
        if (successors[predecessor] != listOf(cursor)) return false
        cursor = predecessor
    }
    return true
}

/**
 * Proof that an ownership barrier cannot invalidate a coroutine phi-web borrow.
 *
 * A virtual or otherwise re-entrant call is never accepted merely because its arguments are +0.
 * The selected anchor must remain independently owned on both successors and its storage must not
 * be replaced by the call. This is the same distinction Swift makes between a reborrow and the
 * scope which guarantees that reborrow.
 */
internal data class ArcCoroutinePhiBarrierProof(
    val position: ArcRCPosition,
    val normalEdge: ArcSSAEdge,
    val unwindEdge: ArcSSAEdge,
    val anchorLiveOnNormalEdge: Boolean,
    val anchorLiveOnUnwindEdge: Boolean,
    val anchorStorageUnchanged: Boolean,
)

/**
 * Proof request for another phi in the same coroutine loop header.
 *
 * The analysis derives identities and physical predecessor coverage from [position]; callers do
 * not get to assert those facts. This record merely says that the adapter selected [peerJoin] as
 * an independent member of the same coupled header conversion.
 */
internal data class ArcCoroutinePhiPeerControlJoinProof(
    val position: ArcRCPosition,
    val peerJoin: ArcSSAValue,
)

internal data class ArcCoroutineGuaranteedPhiReduction(
    val updateStackRefs: Int,
    val retains: Int,
    val releases: Int,
) {
    operator fun plus(other: ArcCoroutineGuaranteedPhiReduction) =
        ArcCoroutineGuaranteedPhiReduction(
            updateStackRefs + other.updateStackRefs,
            retains + other.retains,
            releases + other.releases,
        )
}

internal data class ArcCoroutineGuaranteedPhiCounts(
    val seeds: Int,
    val joins: Int,
    val forwards: Int,
    val borrows: Int,
    val consumes: Int,
)

/**
 * One loop-carried value after normalizing all incoming alternatives to +0.
 *
 * [entryValue] is guaranteed by the function ABI. [backedgeValue] is a reborrow of a separately
 * owned physical slot initialized on the backedge. Consequently the joined value itself is always
 * +0 even though the identity of its lifetime anchor is path dependent. Ownership is materialized
 * only when the backedge is actually taken.
 */
internal data class ArcCoroutineGuaranteedPhiCandidate(
    val cfg: ArcOwnershipSSAInput,
    val header: ArcBlockId,
    val entryEdge: ArcSSAEdge,
    val backedge: ArcSSAEdge,
    val join: ArcSSAValue,
    val entryValue: ArcSSAValue,
    val backedgeValue: ArcSSAValue,
    val entryAnchor: ArcSSAValue,
    val backedgeAnchor: ArcSSAValue,
    val seedValues: Set<ArcSSAValue>,
    val barrierProofs: Set<ArcCoroutinePhiBarrierProof>,
    /** Original eager copies removed before entering or while evaluating one iteration. */
    val removableCopiesPerInvocation: Int,
    /** The backedge keeps this many genuine +1 materializations. */
    val backedgeConsumes: Int,
    val completeUseChain: Boolean,
    val strongNonVolatileStorage: Boolean,
    val entryGuaranteedByAbi: Boolean,
    val backedgeOwnershipMaterializedBeforeAnchorEnd: Boolean,
    val peerControlJoins: Set<ArcCoroutinePhiPeerControlJoinProof> = emptySet(),
)

internal enum class ArcCoroutineGuaranteedPhiRejectionReason {
    MalformedCFG,
    IncompletePhi,
    InvalidEntryGuarantee,
    InvalidBackedgeAnchor,
    IncompleteUseChain,
    UnsupportedStorage,
    UnresolvedRCIdentity,
    EscapeOrUnknownConsume,
    UnsupportedBarrier,
    InvalidPeerControlJoinProof,
    MissingBarrierProof,
    InvalidBarrierProof,
    InvalidLifetime,
    PhysicalPhiMismatch,
}

internal data class ArcCoroutineGuaranteedPhiRejection(
    val join: ArcSSAValue,
    val reason: ArcCoroutineGuaranteedPhiRejectionReason,
    val detail: String,
)

internal data class ArcCoroutineGuaranteedPhiPlan(
    val join: ArcSSAValue,
    val provenanceRoots: Set<ArcSSAValue>,
    val anchorRoots: Set<ArcSSAValue>,
    val frontier: Set<ArcRCLifetimeFrontier>,
    val crossedBarriers: Set<ArcRCBarrier>,
    val counts: ArcCoroutineGuaranteedPhiCounts,
    val reduction: ArcCoroutineGuaranteedPhiReduction,
)

internal data class ArcCoroutineGuaranteedPhiResult(
    val accepted: List<ArcCoroutineGuaranteedPhiPlan>,
    val rejected: List<ArcCoroutineGuaranteedPhiRejection>,
) {
    val totalReduction: ArcCoroutineGuaranteedPhiReduction
        get() = accepted.fold(ArcCoroutineGuaranteedPhiReduction(0, 0, 0)) { total, plan ->
            total + plan.reduction
        }
}

/**
 * Swift-style owned-to-guaranteed conversion for a coroutine loop header.
 *
 * This remains emission independent. It deliberately combines the existing RC-identity/pruned-
 * liveness analysis with the physical CFG verifier. Codegen may consume a plan only after an IR
 * adapter has supplied a complete candidate and the same frozen CFG has been revalidated.
 */
internal object ArcCoroutineGuaranteedPhiWebAnalysis {
    fun analyze(candidates: Collection<ArcCoroutineGuaranteedPhiCandidate>): ArcCoroutineGuaranteedPhiResult {
        val accepted = mutableListOf<ArcCoroutineGuaranteedPhiPlan>()
        val rejected = mutableListOf<ArcCoroutineGuaranteedPhiRejection>()
        candidates.forEach { candidate ->
            when (val result = analyzeOne(candidate)) {
                is OneResult.Accepted -> accepted += result.plan
                is OneResult.Rejected -> rejected += result.rejection
            }
        }
        return ArcCoroutineGuaranteedPhiResult(accepted, rejected)
    }

    private sealed interface OneResult {
        data class Accepted(val plan: ArcCoroutineGuaranteedPhiPlan) : OneResult
        data class Rejected(val rejection: ArcCoroutineGuaranteedPhiRejection) : OneResult
    }

    private fun analyzeOne(candidate: ArcCoroutineGuaranteedPhiCandidate): OneResult {
        fun reject(reason: ArcCoroutineGuaranteedPhiRejectionReason, detail: String) =
            OneResult.Rejected(ArcCoroutineGuaranteedPhiRejection(candidate.join, reason, detail))

        val cfg = candidate.cfg
        if (cfg.entry !in cfg.blocks || candidate.header !in cfg.blocks ||
            cfg.edges.any { it.from !in cfg.blocks || it.to !in cfg.blocks }
        ) return reject(ArcCoroutineGuaranteedPhiRejectionReason.MalformedCFG, "CFG references a missing block")
        if (candidate.entryEdge.kind != ArcSSAEdgeKind.Normal || candidate.backedge.kind != ArcSSAEdgeKind.Normal ||
            candidate.entryEdge.from != cfg.entry || candidate.entryEdge.to != candidate.header ||
            candidate.backedge.to != candidate.header || candidate.entryEdge !in cfg.edges || candidate.backedge !in cfg.edges
        ) return reject(ArcCoroutineGuaranteedPhiRejectionReason.MalformedCFG, "entry/backedge do not form one loop header")

        val definitions = definitions(cfg)
            ?: return reject(ArcCoroutineGuaranteedPhiRejectionReason.MalformedCFG, "an SSA value is defined more than once")
        val join = definitions[candidate.join] as? ArcSSAOperation.Join
            ?: return reject(ArcCoroutineGuaranteedPhiRejectionReason.IncompletePhi, "joined value has no Join definition")
        if (join.incoming != linkedMapOf(
                candidate.entryEdge.from to candidate.entryValue,
                candidate.backedge.from to candidate.backedgeValue,
            ) || candidate.seedValues != setOf(candidate.entryValue, candidate.backedgeValue)
        ) return reject(ArcCoroutineGuaranteedPhiRejectionReason.IncompletePhi, "phi does not cover exactly entry and backedge seeds")

        val entry = definitions[candidate.entryValue] as? ArcSSAOperation.Introduce
        if (!candidate.entryGuaranteedByAbi || entry?.ownership != ArcOwnership.Guaranteed ||
            candidate.entryAnchor !in entry.anchorDependencies
        ) return reject(ArcCoroutineGuaranteedPhiRejectionReason.InvalidEntryGuarantee, "entry seed is not ABI-guaranteed by its scope anchor")

        val backedge = definitions[candidate.backedgeValue] as? ArcSSAOperation.Reborrow
        val backedgeAnchor = definitions[candidate.backedgeAnchor] as? ArcSSAOperation.Introduce
        if (!candidate.backedgeOwnershipMaterializedBeforeAnchorEnd ||
            backedge?.source != candidate.backedgeAnchor || candidate.backedgeAnchor !in backedge.anchorDependencies ||
            backedgeAnchor?.ownership != ArcOwnership.Owned
        ) return reject(ArcCoroutineGuaranteedPhiRejectionReason.InvalidBackedgeAnchor, "backedge is not a reborrow of an independently owned materialization")
        if (!candidate.completeUseChain) {
            return reject(ArcCoroutineGuaranteedPhiRejectionReason.IncompleteUseChain, "one or more phi-web uses are unclassified")
        }
        if (!candidate.strongNonVolatileStorage) {
            return reject(ArcCoroutineGuaranteedPhiRejectionReason.UnsupportedStorage, "weak, unowned, volatile, or aliased storage cannot anchor the web")
        }
        if (candidate.removableCopiesPerInvocation <= 0 || candidate.backedgeConsumes <= 0) {
            return reject(ArcCoroutineGuaranteedPhiRejectionReason.InvalidLifetime, "the rewrite has no entry copy or no backedge ownership handoff")
        }

        val identity = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))
        val joinedIdentity = identity.identity(candidate.join)
            ?: return reject(ArcCoroutineGuaranteedPhiRejectionReason.UnresolvedRCIdentity, "joined RC identity is unavailable")
        if (joinedIdentity.provenanceRoots.isEmpty() ||
            candidate.entryValue !in identity.identities || candidate.backedgeValue !in identity.identities
        ) return reject(ArcCoroutineGuaranteedPhiRejectionReason.UnresolvedRCIdentity, "one incoming identity is unresolved")

        val family = identity.identities.values.filter { member ->
            member.provenanceRoots.any { it in joinedIdentity.provenanceRoots }
        }.mapTo(linkedSetOf()) { it.value }
        val protectedAnchors = joinedIdentity.anchorRoots + candidate.entryAnchor + candidate.backedgeAnchor
        val physicalCfg = ArcPhysicalCfg(
            cfg.entry,
            cfg.blocks.keys,
            cfg.edges.mapTo(linkedSetOf()) { edge ->
                ArcPhysicalCfgEdge(
                    edge.from,
                    edge.to,
                    if (edge.kind == ArcSSAEdgeKind.Normal) ArcPhysicalCfgEdgeKind.NORMAL else ArcPhysicalCfgEdgeKind.UNWIND,
                )
            },
        )

        val ownJoinIndex = cfg.blocks.getValue(candidate.header).operations.indexOfFirst { it === join }
        if (ownJoinIndex < 0) {
            return reject(ArcCoroutineGuaranteedPhiRejectionReason.IncompletePhi, "joined definition is not in the loop header")
        }
        val ownJoinPosition = ArcRCPosition(candidate.header, ownJoinIndex)

        val liveCrossedBarriers = identity.barriers.filterTo(linkedSetOf()) { barrier ->
            barrier.position != ownJoinPosition &&
                    identity.liveness.isLiveBefore(candidate.join, barrier.position.block, barrier.position.operationIndex)
        }
        val unsupportedBarriers = liveCrossedBarriers.filter {
            it.kind != ArcRCBarrierKind.ExceptionalCall && it.kind != ArcRCBarrierKind.ControlJoin
        }
        if (unsupportedBarriers.isNotEmpty()) {
            return reject(
                ArcCoroutineGuaranteedPhiRejectionReason.UnsupportedBarrier,
                "the joined borrow crosses unsupported ownership barriers: " +
                        unsupportedBarriers.joinToString { "${it.kind}@${it.position}" },
            )
        }
        val liveControlJoins = liveCrossedBarriers.filter { it.kind == ArcRCBarrierKind.ControlJoin }
        val peerProofs = candidate.peerControlJoins.associateBy { it.position }
        if (peerProofs.size != candidate.peerControlJoins.size ||
            peerProofs.keys != liveControlJoins.mapTo(linkedSetOf()) { it.position }
        ) return reject(
            ArcCoroutineGuaranteedPhiRejectionReason.InvalidPeerControlJoinProof,
            "peer-control-join proofs do not exactly cover the live control joins",
        )
        for (barrier in liveControlJoins) {
            val proof = peerProofs.getValue(barrier.position)
            val block = cfg.blocks[barrier.position.block]
            val operation = block?.operations?.getOrNull(barrier.position.operationIndex) as? ArcSSAOperation.Join
            val peerIdentity = identity.identity(proof.peerJoin)
            if (barrier.position.block != candidate.header || operation?.result != proof.peerJoin ||
                operation.incoming.keys != join.incoming.keys || peerIdentity == null ||
                peerIdentity.provenanceRoots.any { it in joinedIdentity.provenanceRoots } ||
                peerIdentity.anchorRoots.any { it in protectedAnchors } ||
                operation.incoming.values.any { it in family || it in protectedAnchors }
            ) return reject(
                ArcCoroutineGuaranteedPhiRejectionReason.InvalidPeerControlJoinProof,
                "control join at ${barrier.position} is not an independent peer with identical predecessor coverage",
            )
            val peerStates = operation.incoming.mapValuesTo(linkedMapOf()) { (_, value) ->
                ArcPhysicalSlotExactState<ArcBlockId, ArcSSAValue>(value)
            }
            val peerPhysical = ArcPhysicalSlotCfgVerifier.proveJoin(
                physicalCfg,
                candidate.header,
                ArcPhysicalValuePhi(proof.peerJoin, candidate.header, "ObjHeader*", operation.incoming),
                "ObjHeader*",
                peerStates,
            )
            val peerPhysicalState = peerPhysical.state
            if (!peerPhysical.accepted || peerPhysicalState == null ||
                !ArcPhysicalSlotCfgVerifier.coverageIsComplete(physicalCfg, peerPhysicalState)
            ) return reject(
                ArcCoroutineGuaranteedPhiRejectionReason.InvalidPeerControlJoinProof,
                "peer control join at ${barrier.position} has incomplete frozen physical coverage: ${peerPhysical.rejection}",
            )
        }

        val crossedBarriers = liveCrossedBarriers
        val exceptionalBarriers = crossedBarriers.filter { it.kind == ArcRCBarrierKind.ExceptionalCall }
        val proofs = candidate.barrierProofs.associateBy { it.position }
        for (barrier in exceptionalBarriers) {
            val proof = proofs[barrier.position]
                ?: return reject(ArcCoroutineGuaranteedPhiRejectionReason.MissingBarrierProof, "no normal/unwind anchor proof for ${barrier.position}")
            val outgoing = cfg.edges.filter { it.from == barrier.position.block }.toSet()
            if (proof.normalEdge !in outgoing || proof.normalEdge.kind != ArcSSAEdgeKind.Normal ||
                proof.unwindEdge !in outgoing || proof.unwindEdge.kind != ArcSSAEdgeKind.Exceptional ||
                !proof.anchorLiveOnNormalEdge || !proof.anchorLiveOnUnwindEdge || !proof.anchorStorageUnchanged
            ) return reject(ArcCoroutineGuaranteedPhiRejectionReason.InvalidBarrierProof, "anchor is not independently live across ${barrier.position}")
        }
        if (proofs.size != candidate.barrierProofs.size ||
            proofs.keys != exceptionalBarriers.mapTo(linkedSetOf()) { barrier -> barrier.position }
        ) {
            return reject(ArcCoroutineGuaranteedPhiRejectionReason.InvalidBarrierProof, "barrier proof does not correspond to a live exceptional-call crossing")
        }

        if (identity.issues.any {
                it.kind != ArcRCIdentityIssueKind.UnsupportedEscape &&
                        it.kind != ArcRCIdentityIssueKind.UnsupportedUnknownConsume
            }
        ) return reject(
            ArcCoroutineGuaranteedPhiRejectionReason.UnresolvedRCIdentity,
            identity.issues.joinToString { it.detail },
        )
        if (identity.issues.any {
                it.kind == ArcRCIdentityIssueKind.UnsupportedEscape ||
                        it.kind == ArcRCIdentityIssueKind.UnsupportedUnknownConsume
            }
        ) return reject(ArcCoroutineGuaranteedPhiRejectionReason.EscapeOrUnknownConsume, "the joined RC family escapes local ARC control")

        val frontier = identity.liveness.lifetimeFrontier(candidate.join)
        if (frontier.isEmpty()) {
            return reject(ArcCoroutineGuaranteedPhiRejectionReason.InvalidLifetime, "pruned liveness found no bounded frontier")
        }

        val incomingStates = linkedMapOf(
            candidate.entryEdge.from to ArcPhysicalSlotExactState<ArcBlockId, ArcSSAValue>(candidate.entryValue),
            candidate.backedge.from to ArcPhysicalSlotExactState<ArcBlockId, ArcSSAValue>(candidate.backedgeValue),
        )
        val physical = ArcPhysicalSlotCfgVerifier.proveJoin(
            physicalCfg,
            candidate.header,
            ArcPhysicalValuePhi(candidate.join, candidate.header, "ObjHeader*", join.incoming),
            "ObjHeader*",
            incomingStates,
        )
        val physicalState = physical.state
        if (!physical.accepted || physicalState == null ||
            !ArcPhysicalSlotCfgVerifier.coverageIsComplete(physicalCfg, physicalState)
        ) return reject(ArcCoroutineGuaranteedPhiRejectionReason.PhysicalPhiMismatch, "physical phi edges or frozen coverage are incomplete: ${physical.rejection}")

        val operations = cfg.blocks.values.flatMap { it.operations }
        val counts = ArcCoroutineGuaranteedPhiCounts(
            seeds = candidate.seedValues.size,
            joins = operations.filterIsInstance<ArcSSAOperation.Join>().count { it.result in family },
            forwards = operations.filterIsInstance<ArcSSAOperation.Forward>().count { it.result in family },
            borrows = operations.count { operation -> when (operation) {
                is ArcSSAOperation.Reborrow -> operation.result in family
                is ArcSSAOperation.Borrow -> operation.result in family
                is ArcSSAOperation.Use -> operation.value in family && operation.kind == ArcSSAUseKind.Borrow
                else -> false
            } },
            consumes = candidate.backedgeConsumes + operations.filterIsInstance<ArcSSAOperation.Use>()
                .count { it.value in family && it.kind == ArcSSAUseKind.Consume },
        )
        return OneResult.Accepted(ArcCoroutineGuaranteedPhiPlan(
            candidate.join,
            joinedIdentity.provenanceRoots,
            joinedIdentity.anchorRoots,
            frontier,
            crossedBarriers,
            counts,
            ArcCoroutineGuaranteedPhiReduction(
                updateStackRefs = candidate.removableCopiesPerInvocation,
                retains = candidate.removableCopiesPerInvocation,
                releases = candidate.removableCopiesPerInvocation,
            ),
        ))
    }

    private fun definitions(cfg: ArcOwnershipSSAInput): Map<ArcSSAValue, ArcSSAOperation>? {
        val result = linkedMapOf<ArcSSAValue, ArcSSAOperation>()
        cfg.blocks.values.forEach { block ->
            block.operations.forEach { operation ->
                operation.resultOrNull()?.let { value ->
                    if (result.put(value, operation) != null) return null
                }
            }
        }
        return result
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
}
