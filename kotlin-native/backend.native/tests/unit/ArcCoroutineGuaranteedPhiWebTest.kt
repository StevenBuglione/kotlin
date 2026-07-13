/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcCoroutineGuaranteedPhiWebTest {
    private val entry = ArcBlockId("entry")
    private val header = ArcBlockId("header")
    private val afterInvoke = ArcBlockId("afterInvoke")
    private val backedge = ArcBlockId("backedge")
    private val exit = ArcBlockId("exit")
    private val unwind = ArcBlockId("unwind")

    @Test
    fun physicalBackedgeRejectsAlternatePredecessor() {
        val exact = linkedMapOf(
            "entry" to listOf("header"),
            "header" to listOf("body"),
            "stores" to listOf("exit1"),
            "exit1" to listOf("backedge"),
            "backedge" to listOf("header"),
            "body" to listOf("stores"),
        )
        assertTrue(hasExactCoroutinePhysicalBackedge(exact, "stores", "backedge", "header", "entry"))

        val withAlternateJoin = exact + ("alternate" to listOf("exit1"))
        assertTrue(!hasExactCoroutinePhysicalBackedge(
            withAlternateJoin, "stores", "backedge", "header", "entry",
        ))

        val storeIsBackedgeWithExtraSuccessor = linkedMapOf(
            "entry" to listOf("header"),
            "stores" to listOf("header", "escape"),
        )
        assertTrue(!hasExactCoroutinePhysicalBackedge(
            storeIsBackedgeWithExtraSuccessor, "stores", "stores", "header", "entry",
        ))
    }

    @Test
    fun baseContinuationEntryGuaranteesFormTwoMateriallyUsefulLoopPhiWebs() {
        val fixture = baseContinuationFixture()
        val result = ArcCoroutineGuaranteedPhiWebAnalysis.analyze(fixture.candidates)

        assertTrue(result.rejected.toString(), result.rejected.isEmpty())
        assertEquals(2, result.accepted.size)
        assertEquals(
            ArcCoroutineGuaranteedPhiReduction(updateStackRefs = 4, retains = 4, releases = 4),
            result.totalReduction,
        )
        val current = result.accepted.single { it.join.name == fixture.currentJoin.name }
        val param = result.accepted.single { it.join.name == fixture.paramJoin.name }
        assertEquals(ArcCoroutineGuaranteedPhiCounts(2, 1, 1, 4, 1), current.counts)
        assertEquals(ArcCoroutineGuaranteedPhiCounts(2, 1, 1, 2, 1), param.counts)
        assertEquals(3, current.crossedBarriers.size)
        assertEquals(2, param.crossedBarriers.size)
        assertTrue(current.frontier.isNotEmpty())
        assertTrue(param.frontier.isNotEmpty())
    }

    @Test
    fun everyLiveBarrierKindIsExplicitlyProvenOrRejected() {
        val fixture = baseContinuationFixture()
        val current = fixture.candidates.single { it.join.name == fixture.currentJoin.name }

        // Exceptional calls and a coupled peer control join are the only supported kinds, and
        // the positive fixture supplies both independent-anchor and frozen-coverage proofs.
        assertTrue(ArcCoroutineGuaranteedPhiWebAnalysis.analyze(listOf(current)).rejected.isEmpty())

        val withoutPeer = ArcCoroutineGuaranteedPhiWebAnalysis.analyze(listOf(current.copy(peerControlJoins = emptySet())))
        assertEquals(
            ArcCoroutineGuaranteedPhiRejectionReason.InvalidPeerControlJoinProof,
            withoutPeer.rejected.single().reason,
        )

        val blocker = ArcSSAValue("barrier.blocker")
        val unsupported = linkedMapOf(
            ArcRCBarrierKind.Deinitialization to ArcSSAOperation.DeinitBarrier(),
            ArcRCBarrierKind.Consume to ArcSSAOperation.Use(blocker, ArcSSAUseKind.Consume),
            ArcRCBarrierKind.Escape to ArcSSAOperation.Use(blocker, ArcSSAUseKind.Escape),
            ArcRCBarrierKind.UnknownEffect to ArcSSAOperation.Use(blocker, ArcSSAUseKind.UnknownConsume),
        )
        unsupported.forEach { (kind, operation) ->
            val entryBlock = current.cfg.blocks.getValue(entry)
            val afterBlock = current.cfg.blocks.getValue(afterInvoke)
            val cfg = current.cfg.copy(blocks = current.cfg.blocks + mapOf(
                entry to entryBlock.copy(operations = entryBlock.operations +
                        ArcSSAOperation.Introduce(blocker, ArcOwnership.Owned)),
                afterInvoke to afterBlock.copy(operations =
                        afterBlock.operations.take(1) + operation + afterBlock.operations.drop(1)),
            ))
            val result = ArcCoroutineGuaranteedPhiWebAnalysis.analyze(listOf(current.copy(cfg = cfg)))
            assertEquals(kind.toString(), ArcCoroutineGuaranteedPhiRejectionReason.UnsupportedBarrier, result.rejected.single().reason)
        }
        assertEquals(
            ArcRCBarrierKind.values().toSet(),
            setOf(
                ArcRCBarrierKind.ExceptionalCall,
                ArcRCBarrierKind.ControlJoin,
                ArcRCBarrierKind.Deinitialization,
                ArcRCBarrierKind.Consume,
                ArcRCBarrierKind.Escape,
                ArcRCBarrierKind.UnknownEffect,
            ),
        )
    }

    @Test
    fun peerControlJoinMustHaveIdenticalFrozenPredecessors() {
        val fixture = baseContinuationFixture()
        val current = fixture.candidates.single { it.join.name == fixture.currentJoin.name }
        val headerBlock = current.cfg.blocks.getValue(header)
        val mismatched = headerBlock.copy(operations = headerBlock.operations.map { operation ->
            if (operation is ArcSSAOperation.Join && operation.result.name == fixture.paramJoin.name) {
                operation.copy(incoming = linkedMapOf(entry to operation.incoming.getValue(entry)))
            } else operation
        })
        val result = ArcCoroutineGuaranteedPhiWebAnalysis.analyze(listOf(current.copy(
            cfg = current.cfg.copy(blocks = current.cfg.blocks + (header to mismatched)),
        )))

        assertTrue(result.accepted.isEmpty())
        assertEquals(ArcCoroutineGuaranteedPhiRejectionReason.InvalidPeerControlJoinProof, result.rejected.single().reason)
    }

    @Test
    fun peerControlJoinMayNotTouchCandidateAnchors() {
        val fixture = baseContinuationFixture()
        val current = fixture.candidates.single { it.join.name == fixture.currentJoin.name }
        val entryBlock = current.cfg.blocks.getValue(entry)
        val paramEntry = ArcSSAValue("param.entry")
        val touching = entryBlock.copy(operations = entryBlock.operations.map { operation ->
            if (operation is ArcSSAOperation.Introduce && operation.result.name == paramEntry.name) {
                operation.copy(anchorDependencies = setOf(current.entryAnchor))
            } else operation
        })
        val result = ArcCoroutineGuaranteedPhiWebAnalysis.analyze(listOf(current.copy(
            cfg = current.cfg.copy(blocks = current.cfg.blocks + (entry to touching)),
        )))

        assertTrue(result.accepted.isEmpty())
        assertEquals(ArcCoroutineGuaranteedPhiRejectionReason.InvalidPeerControlJoinProof, result.rejected.single().reason)
    }

    @Test
    fun virtualCallWithoutIndependentNormalAndUnwindAnchorProofFailsClosed() {
        val fixture = baseContinuationFixture()
        val current = fixture.candidates.single { it.join.name == fixture.currentJoin.name }
        val result = ArcCoroutineGuaranteedPhiWebAnalysis.analyze(listOf(current.copy(
            barrierProofs = current.barrierProofs.drop(1).toSet(),
        )))

        assertTrue(result.accepted.isEmpty())
        assertEquals(ArcCoroutineGuaranteedPhiRejectionReason.MissingBarrierProof, result.rejected.single().reason)
    }

    @Test
    fun proofWithDeadUnwindAnchorIsRejected() {
        val fixture = baseContinuationFixture()
        val current = fixture.candidates.single { it.join.name == fixture.currentJoin.name }
        val first = current.barrierProofs.first()
        val result = ArcCoroutineGuaranteedPhiWebAnalysis.analyze(listOf(current.copy(
            barrierProofs = current.barrierProofs - first + first.copy(anchorLiveOnUnwindEdge = false),
        )))

        assertTrue(result.accepted.isEmpty())
        assertEquals(ArcCoroutineGuaranteedPhiRejectionReason.InvalidBarrierProof, result.rejected.single().reason)
    }

    @Test
    fun backedgeMustMaterializeOwnershipInsteadOfSelfAnchoringTheBorrow() {
        val fixture = baseContinuationFixture()
        val current = fixture.candidates.single { it.join.name == fixture.currentJoin.name }
        val result = ArcCoroutineGuaranteedPhiWebAnalysis.analyze(listOf(current.copy(
            backedgeOwnershipMaterializedBeforeAnchorEnd = false,
        )))

        assertTrue(result.accepted.isEmpty())
        assertEquals(ArcCoroutineGuaranteedPhiRejectionReason.InvalidBackedgeAnchor, result.rejected.single().reason)
    }

    @Test
    fun incompleteUseClassificationAndEscapesAreRejected() {
        val fixture = baseContinuationFixture()
        val current = fixture.candidates.single { it.join.name == fixture.currentJoin.name }
        val incomplete = ArcCoroutineGuaranteedPhiWebAnalysis.analyze(listOf(current.copy(completeUseChain = false)))
        assertEquals(ArcCoroutineGuaranteedPhiRejectionReason.IncompleteUseChain, incomplete.rejected.single().reason)

        val escapedCfg = current.cfg.copy(blocks = current.cfg.blocks + mapOf(
            exit to ArcSSABlock(exit, listOf(ArcSSAOperation.Use(current.join, ArcSSAUseKind.Escape))),
        ))
        val escaped = ArcCoroutineGuaranteedPhiWebAnalysis.analyze(listOf(current.copy(cfg = escapedCfg)))
        assertEquals(ArcCoroutineGuaranteedPhiRejectionReason.UnsupportedBarrier, escaped.rejected.single().reason)
    }

    @Test
    fun lateBackedgeChangingFrozenPhiCoverageFailsIdentityAuthentication() {
        val fixture = baseContinuationFixture()
        val current = fixture.candidates.single { it.join.name == fixture.currentJoin.name }
        val extra = ArcBlockId("extraBackedge")
        val malformed = current.cfg.copy(
            blocks = current.cfg.blocks + (extra to ArcSSABlock(extra, emptyList())),
            edges = current.cfg.edges + setOf(ArcSSAEdge(afterInvoke, extra), ArcSSAEdge(extra, header)),
        )
        val result = ArcCoroutineGuaranteedPhiWebAnalysis.analyze(listOf(current.copy(cfg = malformed)))

        assertTrue(result.accepted.isEmpty())
        assertEquals(ArcCoroutineGuaranteedPhiRejectionReason.UnresolvedRCIdentity, result.rejected.single().reason)
    }

    private data class Fixture(
        val candidates: List<ArcCoroutineGuaranteedPhiCandidate>,
        val currentJoin: ArcSSAValue,
        val paramJoin: ArcSSAValue,
    )

    private fun baseContinuationFixture(): Fixture {
        val currentScope = ArcSSAValue("receiver.scope")
        val paramScope = ArcSSAValue("result.scope")
        val currentEntry = ArcSSAValue("current.entry")
        val paramEntry = ArcSSAValue("param.entry")
        val currentBackedgeOwner = ArcSSAValue("current.backedge.owner")
        val paramBackedgeOwner = ArcSSAValue("param.backedge.owner")
        val currentBackedge = ArcSSAValue("current.backedge.borrow")
        val paramBackedge = ArcSSAValue("param.backedge.borrow")
        val currentJoin = ArcSSAValue("current.phi")
        val paramJoin = ArcSSAValue("param.phi")
        val currentForward = ArcSSAValue("tmp0_with")
        val paramForward = ArcSSAValue("invoke.param")

        val entryToHeader = ArcSSAEdge(entry, header)
        val headerToAfter = ArcSSAEdge(header, afterInvoke)
        val headerToUnwind = ArcSSAEdge(header, unwind, ArcSSAEdgeKind.Exceptional)
        val afterToBackedge = ArcSSAEdge(afterInvoke, backedge)
        val afterToExit = ArcSSAEdge(afterInvoke, exit)
        val afterToUnwind = ArcSSAEdge(afterInvoke, unwind, ArcSSAEdgeKind.Exceptional)
        val backToHeader = ArcSSAEdge(backedge, header)
        val cfg = ArcOwnershipSSAInput(
            entry,
            linkedMapOf(
                entry to ArcSSABlock(entry, listOf(
                    ArcSSAOperation.Introduce(currentScope, ArcOwnership.Immortal),
                    ArcSSAOperation.Introduce(paramScope, ArcOwnership.Immortal),
                    ArcSSAOperation.Introduce(currentEntry, ArcOwnership.Guaranteed, setOf(currentScope)),
                    ArcSSAOperation.Introduce(paramEntry, ArcOwnership.Guaranteed, setOf(paramScope)),
                )),
                header to ArcSSABlock(header, listOf(
                    ArcSSAOperation.Join(currentJoin, linkedMapOf(entry to currentEntry, backedge to currentBackedge)),
                    ArcSSAOperation.Join(paramJoin, linkedMapOf(entry to paramEntry, backedge to paramBackedge)),
                    ArcSSAOperation.Forward(currentJoin, currentForward),
                    ArcSSAOperation.Forward(paramJoin, paramForward),
                    // One virtual invoke consumes two +0 arguments. The first operation carries
                    // the exceptional-call barrier; pruned liveness still observes param across it.
                    ArcSSAOperation.Use(currentForward, ArcSSAUseKind.Borrow, mayThrow = true),
                    ArcSSAOperation.Use(paramForward, ArcSSAUseKind.Borrow),
                )),
                afterInvoke to ArcSSABlock(afterInvoke, listOf(
                    // releaseIntercepted is virtual/re-entrant. Current remains anchored across it.
                    ArcSSAOperation.Use(currentJoin, ArcSSAUseKind.Borrow, mayThrow = true),
                    ArcSSAOperation.Use(currentJoin, ArcSSAUseKind.Borrow),
                )),
                backedge to ArcSSABlock(backedge, listOf(
                    ArcSSAOperation.Introduce(currentBackedgeOwner, ArcOwnership.Owned),
                    ArcSSAOperation.Reborrow(
                        currentBackedgeOwner,
                        currentBackedge,
                        setOf(currentBackedgeOwner),
                    ),
                    ArcSSAOperation.Introduce(paramBackedgeOwner, ArcOwnership.Owned),
                    ArcSSAOperation.Reborrow(
                        paramBackedgeOwner,
                        paramBackedge,
                        setOf(paramBackedgeOwner),
                    ),
                )),
                exit to ArcSSABlock(exit, emptyList()),
                unwind to ArcSSABlock(unwind, emptyList()),
            ),
            setOf(
                entryToHeader,
                headerToAfter,
                headerToUnwind,
                afterToBackedge,
                afterToExit,
                afterToUnwind,
                backToHeader,
            ),
        )
        fun proof(position: ArcRCPosition, normal: ArcSSAEdge, unwind: ArcSSAEdge) =
            ArcCoroutinePhiBarrierProof(position, normal, unwind, true, true, true)
        val invokeProof = proof(ArcRCPosition(header, 4), headerToAfter, headerToUnwind)
        val releaseProof = proof(ArcRCPosition(afterInvoke, 0), afterToExit, afterToUnwind)
        fun candidate(
            join: ArcSSAValue,
            entryValue: ArcSSAValue,
            backedgeValue: ArcSSAValue,
            entryAnchor: ArcSSAValue,
            backedgeAnchor: ArcSSAValue,
            barrierProofs: Set<ArcCoroutinePhiBarrierProof>,
            peerControlJoins: Set<ArcCoroutinePhiPeerControlJoinProof> = emptySet(),
        ) = ArcCoroutineGuaranteedPhiCandidate(
            cfg,
            header,
            entryToHeader,
            backToHeader,
            join,
            entryValue,
            backedgeValue,
            entryAnchor,
            backedgeAnchor,
            setOf(entryValue, backedgeValue),
            barrierProofs,
            removableCopiesPerInvocation = 2,
            backedgeConsumes = 1,
            completeUseChain = true,
            strongNonVolatileStorage = true,
            entryGuaranteedByAbi = true,
            backedgeOwnershipMaterializedBeforeAnchorEnd = true,
            peerControlJoins = peerControlJoins,
        )
        return Fixture(
            listOf(
                candidate(currentJoin, currentEntry, currentBackedge, currentScope, currentBackedgeOwner,
                    setOf(invokeProof, releaseProof),
                    setOf(ArcCoroutinePhiPeerControlJoinProof(ArcRCPosition(header, 1), paramJoin))),
                candidate(paramJoin, paramEntry, paramBackedge, paramScope, paramBackedgeOwner,
                    setOf(invokeProof),
                    setOf(ArcCoroutinePhiPeerControlJoinProof(ArcRCPosition(header, 0), currentJoin))),
            ),
            currentJoin,
            paramJoin,
        )
    }
}
