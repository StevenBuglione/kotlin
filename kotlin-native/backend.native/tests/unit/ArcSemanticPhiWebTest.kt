/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcSemanticPhiWebTest {
    private val entry = ArcBlockId("entry")
    private val left = ArcBlockId("left")
    private val right = ArcBlockId("right")
    private val merge = ArcBlockId("merge")
    private val exit = ArcBlockId("exit")

    @Test
    fun completeMultiIntroducerWebConvertsAtFixedPointAndReverifiesEveryRewrite() {
        val fixture = diamond()
        val result = ArcSemanticPhiWebAnalysis.analyze(fixture.input)

        assertTrue(result.rejected.toString(), result.rejected.isEmpty())
        val plan = result.accepted.single()
        assertEquals(setOf(fixture.leftCopy, fixture.rightCopy, fixture.joined), plan.members)
        assertEquals(2, plan.seeds.size)
        assertEquals(2, plan.reborrows.size)
        assertEquals(plan.rewrites.size + 1, plan.verificationEpochs)
        assertTrue(plan.rewrites.first() is ArcSemanticARCRewrite.PrepareJoinedWeb)
        assertEquals(2, plan.rewrites.filterIsInstance<ArcSemanticARCRewrite.EliminateGuaranteedCopy>().size)
        assertTrue(plan.liveness.getValue(entry) === ArcPrunedBlockLiveness.Dead)
        assertTrue(plan.liveness.getValue(left) === ArcPrunedBlockLiveness.LiveOut)
        assertTrue(plan.liveness.getValue(merge) === ArcPrunedBlockLiveness.LiveWithin)
    }

    @Test
    fun partialPhiWebFailsClosedWhenOneIntroducerWasNotSeeded() {
        val fixture = diamond(seedRight = false)
        val result = ArcSemanticPhiWebAnalysis.analyze(fixture.input)

        assertEquals(ArcSemanticARCRejectionReason.IncompletePhiWeb, result.rejected.single().reason)
        assertTrue(result.accepted.isEmpty())
    }

    @Test
    fun multipleConsumesOnOnePathAreRejectedButPathDisjointConsumesAreAccepted() {
        val sequential = diamond(mergeTail = { joined -> listOf(
            ArcSSAOperation.Use(joined, ArcSSAUseKind.Consume),
            ArcSSAOperation.Use(joined, ArcSSAUseKind.Consume),
        ) })
        val sequentialRejections = ArcSemanticPhiWebAnalysis.analyze(sequential.input).rejected
        assertTrue(sequentialRejections.isNotEmpty())
        assertTrue(sequentialRejections.all { it.reason === ArcSemanticARCRejectionReason.MultipleConsumesOnPath })

        val fixture = diamond(mergeTail = { emptyList() })
        val postLeft = ArcBlockId("postLeft")
        val postRight = ArcBlockId("postRight")
        val blocks = fixture.input.cfg.blocks + mapOf(
            postLeft to block(postLeft, ArcSSAOperation.Use(fixture.joined, ArcSSAUseKind.Consume)),
            postRight to block(postRight, ArcSSAOperation.Use(fixture.joined, ArcSSAUseKind.Consume)),
        )
        val edges = fixture.input.cfg.edges.filterNot { it.from.name.compareTo(merge.name) == 0 }.toSet() +
                setOf(ArcSSAEdge(merge, postLeft), ArcSSAEdge(merge, postRight),
                    ArcSSAEdge(postLeft, exit), ArcSSAEdge(postRight, exit))
        assertTrue(ArcSemanticPhiWebAnalysis.analyze(fixture.input.copy(
            cfg = fixture.input.cfg.copy(blocks = blocks, edges = edges)
        )).rejected.isEmpty())
    }

    @Test
    fun escapingBorrowRejectsCompleteWeb() {
        val fixture = diamond(mergeTail = { joined -> listOf(ArcSSAOperation.Use(joined, ArcSSAUseKind.Escape)) })
        val rejections = ArcSemanticPhiWebAnalysis.analyze(fixture.input).rejected
        assertTrue(rejections.isNotEmpty())
        assertTrue(rejections.all { it.reason === ArcSemanticARCRejectionReason.BorrowEscape })
    }

    @Test
    fun cyclicLoopPhiIsOneJoinedSccAndCarriesAReborrowOnBackedge() {
        val anchor = ArcSSAValue("anchor")
        val source = ArcSSAValue("source")
        val copy = ArcSSAValue("copy")
        val phi = ArcSSAValue("phi")
        val back = ArcSSAValue("back")
        val header = ArcBlockId("header")
        val body = ArcBlockId("body")
        val cfg = ArcOwnershipSSAInput(
            entry,
            linkedMapOf(
                entry to block(entry,
                    ArcSSAOperation.Introduce(anchor, ArcOwnership.Immortal),
                    ArcSSAOperation.Introduce(source, ArcOwnership.Guaranteed, setOf(anchor)),
                    ArcSSAOperation.Introduce(copy, ArcOwnership.Owned)),
                header to block(header,
                    ArcSSAOperation.Join(phi, linkedMapOf(entry to copy, body to back)),
                    ArcSSAOperation.Use(phi, ArcSSAUseKind.Borrow)),
                body to block(body, ArcSSAOperation.Forward(phi, back)),
                exit to block(exit),
            ),
            setOf(ArcSSAEdge(entry, header), ArcSSAEdge(header, body), ArcSSAEdge(body, header), ArcSSAEdge(header, exit)),
        )
        val result = ArcSemanticPhiWebAnalysis.analyze(ArcSemanticARCInput(
            cfg, setOf(ArcGuaranteedCopySeed(copy, source, setOf(anchor)))
        ))

        assertTrue(result.rejected.toString(), result.rejected.isEmpty())
        val plan = result.accepted.single()
        assertEquals(setOf(copy, phi, back), plan.members)
        assertTrue(plan.reborrows.any { sameEdge(it.edge, ArcSSAEdge(body, header)) })
    }

    @Test
    fun loopProjectionCannotUseTransformedPhiAsItsOwnLifetimeAnchor() {
        val result = ArcSemanticPhiWebAnalysis.analyze(selfAnchoredLoopFixture(indirectAnchor = false))

        assertTrue(result.accepted.isEmpty())
        assertEquals(
            ArcSemanticARCRejectionReason.InvalidGuaranteedSeed,
            result.rejected.single().reason,
        )
    }

    @Test
    fun forwardedLoopProjectionCannotHideTransformedSelfAnchor() {
        val result = ArcSemanticPhiWebAnalysis.analyze(selfAnchoredLoopFixture(indirectAnchor = true))

        assertTrue(result.accepted.isEmpty())
        assertEquals(
            ArcSemanticARCRejectionReason.InvalidGuaranteedSeed,
            result.rejected.single().reason,
        )
    }

    @Test
    fun loopPhiWithExternalLifetimeAnchorRemainsValid() {
        val result = ArcSemanticPhiWebAnalysis.analyze(loopFixture(ArcSSAUseKind.Borrow))

        assertTrue(result.rejected.toString(), result.rejected.isEmpty())
        assertEquals(setOf(ArcSSAValue("loopAnchor")), result.accepted.single().canonicalRCRoots)
    }

    @Test
    fun loopCarriedConsumeIsRejectedBecauseOneStaticConsumeCanExecuteRepeatedly() {
        val fixture = loopFixture(ArcSSAUseKind.Consume)
        val result = ArcSemanticPhiWebAnalysis.analyze(fixture)

        assertEquals(ArcSemanticARCRejectionReason.MultipleConsumesOnPath, result.rejected.single().reason)
    }

    @Test
    fun malformedJoinAndNonDominatingIncomingAreRejected() {
        val malformed = diamond()
        val oldJoin = malformed.input.cfg.blocks.getValue(merge).operations.first() as ArcSSAOperation.Join
        val malformedBlocks = malformed.input.cfg.blocks + (merge to block(merge,
            oldJoin.copy(incoming = mapOf(left to malformed.leftCopy))))
        assertEquals(ArcSemanticARCRejectionReason.MalformedCFG,
            ArcSemanticPhiWebAnalysis.analyze(malformed.input.copy(
                cfg = malformed.input.cfg.copy(blocks = malformedBlocks)
            )).rejected.first().reason)

        val anchor = ArcSSAValue("anchor")
        val source = ArcSSAValue("source")
        val copy = ArcSSAValue("lateCopy")
        val joined = ArcSSAValue("joined")
        val cfg = ArcOwnershipSSAInput(entry, linkedMapOf(
            entry to block(entry,
                ArcSSAOperation.Introduce(anchor, ArcOwnership.Immortal),
                ArcSSAOperation.Introduce(source, ArcOwnership.Guaranteed, setOf(anchor))),
            left to block(left),
            right to block(right, ArcSSAOperation.Introduce(copy, ArcOwnership.Owned)),
            merge to block(merge, ArcSSAOperation.Join(joined, linkedMapOf(left to copy, right to copy))),
        ), setOf(ArcSSAEdge(entry, left), ArcSSAEdge(entry, right), ArcSSAEdge(left, merge), ArcSSAEdge(right, merge)))
        val dominance = ArcSemanticPhiWebAnalysis.analyze(ArcSemanticARCInput(
            cfg, setOf(ArcGuaranteedCopySeed(copy, source, setOf(anchor)))
        ))
        assertEquals(ArcSemanticARCRejectionReason.MalformedCFG, dominance.rejected.single().reason)
    }

    @Test
    fun explicitExitTerminatorLivenessPlacesBeforeExitFrontier() {
        val fixture = diamond()
        val result = ArcSemanticPhiWebAnalysis.analyze(fixture.input.copy(exitLifetimeUses = setOf(exit)))

        assertTrue(result.rejected.toString(), result.rejected.isEmpty())
        assertTrue(result.accepted.single().frontier.contains(ArcSemanticLifetimeFrontier.BeforeExit(exit)))
    }

    @Test
    fun criticalEdgeFrontierRequestsSplittingInsteadOfRejectingWeb() {
        val fixture = diamond(mergeTail = { _ -> emptyList() })
        val live = ArcBlockId("live")
        val dead = ArcBlockId("dead")
        val other = ArcBlockId("other")
        val joined = fixture.joined
        val blocks = fixture.input.cfg.blocks.toMutableMap().apply {
            this[merge] = block(merge,
                (fixture.input.cfg.blocks.getValue(merge).operations.first()),
                ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow))
            this[live] = block(live, ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow))
            this[dead] = block(dead)
            this[other] = block(other)
        }
        val edges = fixture.input.cfg.edges.filterNot { it.from.name.compareTo(merge.name) == 0 }.toMutableSet().apply {
            add(ArcSSAEdge(merge, live)); add(ArcSSAEdge(merge, dead)); add(ArcSSAEdge(other, dead))
        }
        val result = ArcSemanticPhiWebAnalysis.analyze(fixture.input.copy(
            cfg = fixture.input.cfg.copy(blocks = blocks, edges = edges)
        ))

        assertTrue(result.rejected.toString(), result.rejected.isEmpty())
        assertTrue(result.accepted.single().frontier.any {
            it is ArcSemanticLifetimeFrontier.OnEdge && sameEdge(it.edge, ArcSSAEdge(merge, dead)) && it.requiresEdgeSplit
        })
    }

    @Test
    fun exceptionalEdgeGetsAFrontierAndDeadEndGetsNoExitCleanup() {
        val handler = ArcBlockId("handler")
        val fixture = diamond(mergeTail = { joined -> listOf(
            ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow, mayThrow = true)
        ) })
        val blocks = fixture.input.cfg.blocks + (handler to block(handler))
        val exceptional = ArcSSAEdge(merge, handler, ArcSSAEdgeKind.Exceptional)
        val input = fixture.input.copy(cfg = fixture.input.cfg.copy(
            blocks = blocks, edges = fixture.input.cfg.edges + exceptional
        ))
        val result = ArcSemanticPhiWebAnalysis.analyze(input.copy(deadEndBlocks = setOf(handler)))

        assertTrue(result.rejected.toString(), result.rejected.isEmpty())
        val frontier = result.accepted.single().frontier
        assertTrue(frontier.any { it is ArcSemanticLifetimeFrontier.OnEdge && sameEdge(it.edge, exceptional) })
        assertFalse(frontier.any {
            it is ArcSemanticLifetimeFrontier.BeforeExit && it.block.name.compareTo(handler.name) == 0
        })
    }

    @Test
    fun suspensionDeinitAndForeignBarriersRejectCrossingButAllowEndingBeforeBarrier() {
        ArcSemanticARCBarrierKind.values().forEach { kind ->
            val crossing = diamond(mergeTail = { joined -> listOf(
                ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow),
                ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow),
            ) })
            val rejected = ArcSemanticPhiWebAnalysis.analyze(crossing.input.copy(
                barriers = setOf(ArcSemanticARCBarrier(merge, 2, kind))
            ))
            assertTrue(kind.name, rejected.rejected.isNotEmpty())
            assertTrue(kind.name, rejected.rejected.all {
                it.reason === ArcSemanticARCRejectionReason.BarrierCrossing
            })

            val ending = diamond(mergeTail = { joined -> listOf(ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow)) })
            val accepted = ArcSemanticPhiWebAnalysis.analyze(ending.input.copy(
                barriers = setOf(ArcSemanticARCBarrier(merge, 2, kind))
            )).accepted.single()
            assertTrue(accepted.frontier.any {
                it is ArcSemanticLifetimeFrontier.BeforeBarrier && it.barrier.kind === kind
            })
        }
    }

    @Test
    fun conflictingIdentityAndDeterministicBudgetsFailClosed() {
        val fixture = diamond()
        val leftSeed = fixture.input.guaranteedCopies.first { it.copy.name.compareTo(fixture.leftCopy.name) == 0 }
        val conflict = leftSeed.copy(guaranteedSource = fixture.input.guaranteedCopies
            .first { it.copy.name.compareTo(fixture.rightCopy.name) == 0 }.guaranteedSource)
        val conflictResult = ArcSemanticPhiWebAnalysis.analyze(fixture.input.copy(
            guaranteedCopies = fixture.input.guaranteedCopies + conflict
        ))
        assertEquals(ArcSemanticARCRejectionReason.ConflictingRCIdentity, conflictResult.rejected.first().reason)

        val budgetResult = ArcSemanticPhiWebAnalysis.analyze(fixture.input.copy(
            budget = ArcSemanticARCBudget(maximumInstructions = 1, maximumUses = 1, maximumRewriteSteps = 1)
        ))
        assertTrue(budgetResult.accepted.isEmpty())
        assertTrue(budgetResult.rejected.all {
            it.reason === ArcSemanticARCRejectionReason.BudgetExhausted
        })
    }

    @Test
    fun dominatingButUnrelatedAnchorCannotAuthorizeGuaranteedCopy() {
        val actualAnchor = ArcSSAValue("actualAnchor")
        val unrelatedAnchor = ArcSSAValue("unrelatedAnchor")
        val leftSource = ArcSSAValue("anchoredLeftSource")
        val rightSource = ArcSSAValue("anchoredRightSource")
        val leftCopy = ArcSSAValue("anchoredLeftCopy")
        val rightCopy = ArcSSAValue("anchoredRightCopy")
        val joined = ArcSSAValue("anchoredJoin")
        val cfg = ArcOwnershipSSAInput(entry, linkedMapOf(
            entry to block(entry,
                ArcSSAOperation.Introduce(actualAnchor, ArcOwnership.Immortal),
                ArcSSAOperation.Introduce(unrelatedAnchor, ArcOwnership.Immortal),
                ArcSSAOperation.Introduce(leftSource, ArcOwnership.Guaranteed, setOf(actualAnchor)),
                ArcSSAOperation.Introduce(rightSource, ArcOwnership.Guaranteed, setOf(actualAnchor))),
            left to block(left, ArcSSAOperation.Introduce(leftCopy, ArcOwnership.Owned)),
            right to block(right, ArcSSAOperation.Introduce(rightCopy, ArcOwnership.Owned)),
            merge to block(merge,
                ArcSSAOperation.Join(joined, linkedMapOf(left to leftCopy, right to rightCopy)),
                ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow)),
            exit to block(exit),
        ), setOf(ArcSSAEdge(entry, left), ArcSSAEdge(entry, right), ArcSSAEdge(left, merge),
            ArcSSAEdge(right, merge), ArcSSAEdge(merge, exit)))
        val result = ArcSemanticPhiWebAnalysis.analyze(ArcSemanticARCInput(cfg, setOf(
            ArcGuaranteedCopySeed(leftCopy, leftSource, setOf(unrelatedAnchor)),
            ArcGuaranteedCopySeed(rightCopy, rightSource, setOf(actualAnchor)),
        )))

        assertTrue(result.accepted.isEmpty())
        assertEquals(ArcSemanticARCRejectionReason.InvalidGuaranteedSeed, result.rejected.single().reason)
    }

    @Test
    fun memberStrongSlotInitializationAndMovesFailClosed() {
        val sourceSlot = ArcSSASlot("transferSource")
        val destinationSlot = ArcSSASlot("transferDestination")
        val sourceVersion = ArcSSASlotVersion("transferSourceVersion")
        val destinationVersion = ArcSSASlotVersion("transferDestinationVersion")
        val operations: List<(ArcSSAValue) -> ArcSSAOperation> = listOf(
            { value -> ArcSSAOperation.InitializeOwned(sourceSlot, sourceVersion, value) },
            { value -> ArcSSAOperation.InitializeImmortal(sourceSlot, sourceVersion, value) },
            { value -> ArcSSAOperation.JoinSlot(sourceSlot, sourceVersion, value, emptyMap()) },
            { value -> ArcSSAOperation.MoveOwned(sourceSlot, sourceVersion, destinationSlot, destinationVersion, value) },
        )

        operations.forEach { operation ->
            val fixture = diamond(mergeTail = { joined -> listOf(operation(joined)) })
            val result = ArcSemanticPhiWebAnalysis.analyze(fixture.input)
            assertTrue(result.accepted.isEmpty())
            assertTrue(result.rejected.isNotEmpty())
            assertTrue(result.rejected.all { it.reason === ArcSemanticARCRejectionReason.BorrowEscape })
        }
    }

    @Test
    fun destroyOwnedIsAConsumingUseWithLifetimeFrontier() {
        val slot = ArcSSASlot("destroySlot")
        val version = ArcSSASlotVersion("destroyVersion")
        val fixture = diamond(mergeTail = { joined -> listOf(
            ArcSSAOperation.DestroyOwned(slot, version, joined)
        ) })
        val result = ArcSemanticPhiWebAnalysis.analyze(fixture.input)

        assertTrue(result.rejected.toString(), result.rejected.isEmpty())
        assertTrue(result.accepted.single().frontier.contains(
            ArcSemanticLifetimeFrontier.AfterOperation(merge, 1)
        ))
    }

    @Test
    fun slotBorrowContributesNonconsumingPrunedLiveness() {
        val slot = ArcSSASlot("borrowSlot")
        val version = ArcSSASlotVersion("borrowVersion")
        val borrowed = ArcSSAValue("borrowedValue")
        val borrowId = ArcSSABorrowId("borrowIdentity")
        val fixture = diamond(mergeTail = { joined -> listOf(
            ArcSSAOperation.Borrow(slot, version, joined, borrowed, borrowId),
            ArcSSAOperation.EndBorrow(borrowId),
        ) })
        val result = ArcSemanticPhiWebAnalysis.analyze(fixture.input)

        assertTrue(result.rejected.toString(), result.rejected.isEmpty())
        val plan = result.accepted.single()
        assertTrue(plan.liveness.getValue(merge) === ArcPrunedBlockLiveness.LiveWithin)
        assertTrue(plan.frontier.contains(ArcSemanticLifetimeFrontier.AfterOperation(merge, 1)))
    }

    @Test
    fun deinitOperationImplicitlyRejectsCrossingWithoutAdapterBarrier() {
        val fixture = diamond(mergeTail = { joined -> listOf(
            ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow),
            ArcSSAOperation.DeinitBarrier(),
            ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow),
        ) })
        val result = ArcSemanticPhiWebAnalysis.analyze(fixture.input)

        assertTrue(result.accepted.isEmpty())
        assertTrue(result.rejected.isNotEmpty())
        assertTrue(result.rejected.all { it.reason === ArcSemanticARCRejectionReason.BarrierCrossing })
    }

    private data class DiamondFixture(
        val input: ArcSemanticARCInput,
        val leftCopy: ArcSSAValue,
        val rightCopy: ArcSSAValue,
        val joined: ArcSSAValue,
    )

    private fun diamond(
        seedRight: Boolean = true,
        leftTail: (ArcSSAValue) -> List<ArcSSAOperation> = { emptyList() },
        rightTail: (ArcSSAValue) -> List<ArcSSAOperation> = { emptyList() },
        mergeTail: (ArcSSAValue) -> List<ArcSSAOperation> = { listOf(ArcSSAOperation.Use(it, ArcSSAUseKind.Borrow)) },
    ): DiamondFixture {
        val leftAnchor = ArcSSAValue("leftAnchor")
        val leftSource = ArcSSAValue("leftSource")
        val leftCopy = ArcSSAValue("leftCopy")
        val rightAnchor = ArcSSAValue("rightAnchor")
        val rightSource = ArcSSAValue("rightSource")
        val rightCopy = ArcSSAValue("rightCopy")
        val joined = ArcSSAValue("joined")
        val cfg = ArcOwnershipSSAInput(
            entry,
            linkedMapOf(
                entry to block(entry),
                left to block(left,
                    ArcSSAOperation.Introduce(leftAnchor, ArcOwnership.Immortal),
                    ArcSSAOperation.Introduce(leftSource, ArcOwnership.Guaranteed, setOf(leftAnchor)),
                    ArcSSAOperation.Introduce(leftCopy, ArcOwnership.Owned),
                    *leftTail(leftCopy).toTypedArray()),
                right to block(right,
                    ArcSSAOperation.Introduce(rightAnchor, ArcOwnership.Immortal),
                    ArcSSAOperation.Introduce(rightSource, ArcOwnership.Guaranteed, setOf(rightAnchor)),
                    ArcSSAOperation.Introduce(rightCopy, ArcOwnership.Owned),
                    *rightTail(rightCopy).toTypedArray()),
                merge to block(merge,
                    ArcSSAOperation.Join(joined, linkedMapOf(left to leftCopy, right to rightCopy)),
                    *mergeTail(joined).toTypedArray()),
                exit to block(exit),
            ),
            setOf(ArcSSAEdge(entry, left), ArcSSAEdge(entry, right), ArcSSAEdge(left, merge),
                ArcSSAEdge(right, merge), ArcSSAEdge(merge, exit)),
        )
        val seeds = linkedSetOf(ArcGuaranteedCopySeed(leftCopy, leftSource, setOf(leftAnchor)))
        if (seedRight) seeds += ArcGuaranteedCopySeed(rightCopy, rightSource, setOf(rightAnchor))
        return DiamondFixture(ArcSemanticARCInput(cfg, seeds), leftCopy, rightCopy, joined)
    }

    private fun loopFixture(useKind: ArcSSAUseKind): ArcSemanticARCInput {
        val anchor = ArcSSAValue("loopAnchor")
        val source = ArcSSAValue("loopSource")
        val copy = ArcSSAValue("loopCopy")
        val phi = ArcSSAValue("loopPhi")
        val back = ArcSSAValue("loopBack")
        val header = ArcBlockId("loopHeader")
        val body = ArcBlockId("loopBody")
        val cfg = ArcOwnershipSSAInput(entry, linkedMapOf(
            entry to block(entry,
                ArcSSAOperation.Introduce(anchor, ArcOwnership.Immortal),
                ArcSSAOperation.Introduce(source, ArcOwnership.Guaranteed, setOf(anchor)),
                ArcSSAOperation.Introduce(copy, ArcOwnership.Owned)),
            header to block(header,
                ArcSSAOperation.Join(phi, linkedMapOf(entry to copy, body to back)),
                ArcSSAOperation.Use(phi, useKind)),
            body to block(body, ArcSSAOperation.Forward(phi, back)),
            exit to block(exit),
        ), setOf(ArcSSAEdge(entry, header), ArcSSAEdge(header, body), ArcSSAEdge(body, header), ArcSSAEdge(header, exit)))
        return ArcSemanticARCInput(cfg, setOf(ArcGuaranteedCopySeed(copy, source, setOf(anchor))))
    }

    /**
     * Models `current = current.completion`: the projected value's guaranteed lifetime is rooted
     * in the old loop phi. Converting the complete phi web to guaranteed would replace that root
     * on the backedge and leave the projection dangling. A forwarded alias must not hide it.
     */
    private fun selfAnchoredLoopFixture(indirectAnchor: Boolean): ArcSemanticARCInput {
        val externalAnchor = ArcSSAValue("externalAnchor")
        val initialSource = ArcSSAValue("initialSource")
        val initialCopy = ArcSSAValue("initialCopy")
        val phi = ArcSSAValue("selfAnchoredPhi")
        val forwardedAnchor = ArcSSAValue("forwardedSelfAnchor")
        val projectedSource = ArcSSAValue("projectedSource")
        val projectedCopy = ArcSSAValue("projectedCopy")
        val header = ArcBlockId("selfAnchoredHeader")
        val body = ArcBlockId("selfAnchoredBody")
        val projectionAnchor = if (indirectAnchor) forwardedAnchor else phi
        val bodyOperations = buildList {
            if (indirectAnchor) add(ArcSSAOperation.Forward(phi, forwardedAnchor))
            add(ArcSSAOperation.Introduce(
                projectedSource,
                ArcOwnership.Guaranteed,
                setOf(projectionAnchor),
            ))
            add(ArcSSAOperation.Introduce(projectedCopy, ArcOwnership.Owned))
        }
        val cfg = ArcOwnershipSSAInput(
            entry,
            linkedMapOf(
                entry to block(
                    entry,
                    ArcSSAOperation.Introduce(externalAnchor, ArcOwnership.Immortal),
                    ArcSSAOperation.Introduce(initialSource, ArcOwnership.Guaranteed, setOf(externalAnchor)),
                    ArcSSAOperation.Introduce(initialCopy, ArcOwnership.Owned),
                ),
                header to block(
                    header,
                    ArcSSAOperation.Join(phi, linkedMapOf(entry to initialCopy, body to projectedCopy)),
                    ArcSSAOperation.Use(phi, ArcSSAUseKind.Borrow),
                ),
                body to ArcSSABlock(body, bodyOperations),
                exit to block(exit),
            ),
            setOf(
                ArcSSAEdge(entry, header),
                ArcSSAEdge(header, body),
                ArcSSAEdge(body, header),
                ArcSSAEdge(header, exit),
            ),
        )
        return ArcSemanticARCInput(
            cfg,
            setOf(
                ArcGuaranteedCopySeed(initialCopy, initialSource, setOf(externalAnchor)),
                ArcGuaranteedCopySeed(projectedCopy, projectedSource, setOf(projectionAnchor)),
            ),
        )
    }

    private fun block(id: ArcBlockId, vararg operations: ArcSSAOperation) = ArcSSABlock(id, operations.toList())

    private fun sameEdge(left: ArcSSAEdge, right: ArcSSAEdge): Boolean =
        left.from.name.compareTo(right.from.name) == 0 && left.to.name.compareTo(right.to.name) == 0 &&
                left.kind === right.kind
}
