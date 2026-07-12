/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcOwnershipSSATest {
    private val entry = ArcBlockId("entry")
    private val thenBlock = ArcBlockId("then")
    private val elseBlock = ArcBlockId("else")
    private val merge = ArcBlockId("merge")
    private val exit = ArcBlockId("exit")

    @Test
    fun completeOwnedWebConvertsEveryForwardAndPlacesOneFrontierDestroy() {
        val thenSource = ArcSSAValue("thenSource")
        val thenForward = ArcSSAValue("thenForward")
        val elseSource = ArcSSAValue("elseSource")
        val elseForward = ArcSSAValue("elseForward")
        val joined = ArcSSAValue("joined")
        val result = ArcSSAValue("result")
        val input = diamond(
            thenOperations = listOf(
                ArcSSAOperation.Introduce(thenSource, ArcOwnership.Owned),
                ArcSSAOperation.Forward(thenSource, thenForward),
            ),
            elseOperations = listOf(
                ArcSSAOperation.Introduce(elseSource, ArcOwnership.Owned),
                ArcSSAOperation.Forward(elseSource, elseForward),
            ),
            mergeOperations = listOf(
                ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to thenForward, elseBlock to elseForward)),
                ArcSSAOperation.Forward(joined, result),
                ArcSSAOperation.Use(result, ArcSSAUseKind.Borrow),
            ),
        )

        val analysis = ArcOwnershipSSAAnalysis.analyze(input)

        assertTrue(analysis.rejected.isEmpty())
        val web = analysis.accepted.single()
        assertEquals(ArcOwnership.Owned, web.ownership)
        assertEquals(setOf(thenSource, thenForward, elseSource, elseForward, joined, result), web.members)
        assertEquals(setOf(ArcSSADestroyPlacement.BeforeExit(exit)), web.destroyPlacements)
    }

    @Test
    fun mixedIncomingOwnershipFailsClosed() {
        val left = ArcSSAValue("left")
        val right = ArcSSAValue("right")
        val joined = ArcSSAValue("joined")
        val result = ArcOwnershipSSAAnalysis.analyze(
            diamond(
                listOf(ArcSSAOperation.Introduce(left, ArcOwnership.Owned)),
                listOf(ArcSSAOperation.Introduce(right, ArcOwnership.Immortal)),
                listOf(ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to left, elseBlock to right))),
            )
        )

        assertEquals(ArcOwnershipSSARejectionReason.MIXED_INCOMING_OWNERSHIP, result.rejected.single().reason)
    }

    @Test
    fun unknownConsumeRejectsWholeWeb() {
        val left = ArcSSAValue("left")
        val right = ArcSSAValue("right")
        val joined = ArcSSAValue("joined")
        val result = ArcOwnershipSSAAnalysis.analyze(
            diamond(
                listOf(ArcSSAOperation.Introduce(left, ArcOwnership.Owned)),
                listOf(ArcSSAOperation.Introduce(right, ArcOwnership.Owned)),
                listOf(
                    ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to left, elseBlock to right)),
                    ArcSSAOperation.Use(joined, ArcSSAUseKind.UnknownConsume),
                ),
            )
        )

        assertEquals(ArcOwnershipSSARejectionReason.UNKNOWN_CONSUME, result.rejected.single().reason)
    }

    @Test
    fun canonicalReborrowAnchorsMustDominateBothArms() {
        val anchor = ArcSSAValue("anchor")
        val leftSource = ArcSSAValue("leftSource")
        val rightSource = ArcSSAValue("rightSource")
        val left = ArcSSAValue("left")
        val right = ArcSSAValue("right")
        val joined = ArcSSAValue("joined")
        val accepted = ArcOwnershipSSAAnalysis.analyze(
            diamond(
                entryOperations = listOf(ArcSSAOperation.Introduce(anchor, ArcOwnership.Owned)),
                thenOperations = listOf(
                    ArcSSAOperation.Introduce(leftSource, ArcOwnership.Owned),
                    ArcSSAOperation.Reborrow(leftSource, left, setOf(anchor)),
                ),
                elseOperations = listOf(
                    ArcSSAOperation.Introduce(rightSource, ArcOwnership.Owned),
                    ArcSSAOperation.Reborrow(rightSource, right, setOf(anchor)),
                ),
                mergeOperations = listOf(
                    ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to left, elseBlock to right)),
                    ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow),
                ),
            )
        )
        assertTrue(accepted.rejected.isEmpty())
        assertEquals(setOf(anchor), accepted.accepted.single().anchorDependencies)

        val lateAnchor = ArcSSAValue("lateAnchor")
        val rejected = ArcOwnershipSSAAnalysis.analyze(
            diamond(
                thenOperations = listOf(
                    ArcSSAOperation.Introduce(lateAnchor, ArcOwnership.Owned),
                    ArcSSAOperation.Introduce(leftSource, ArcOwnership.Owned),
                    ArcSSAOperation.Reborrow(leftSource, left, setOf(lateAnchor)),
                ),
                elseOperations = listOf(
                    ArcSSAOperation.Introduce(rightSource, ArcOwnership.Owned),
                    ArcSSAOperation.Reborrow(rightSource, right, setOf(lateAnchor)),
                ),
                mergeOperations = listOf(ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to left, elseBlock to right))),
            )
        )
        assertEquals(ArcOwnershipSSARejectionReason.ANCHOR_DOES_NOT_DOMINATE_REBORROW, rejected.rejected.single().reason)
    }

    @Test
    fun exceptionalEdgeGetsPreConsumeCleanup() {
        val left = ArcSSAValue("left")
        val right = ArcSSAValue("right")
        val joined = ArcSSAValue("joined")
        val handler = ArcBlockId("handler")
        val exceptional = ArcSSAEdge(merge, handler, ArcSSAEdgeKind.Exceptional)
        val result = ArcOwnershipSSAAnalysis.analyze(
            diamond(
                thenOperations = listOf(ArcSSAOperation.Introduce(left, ArcOwnership.Owned)),
                elseOperations = listOf(ArcSSAOperation.Introduce(right, ArcOwnership.Owned)),
                mergeOperations = listOf(
                    ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to left, elseBlock to right)),
                    ArcSSAOperation.Use(joined, ArcSSAUseKind.Consume, mayThrow = true),
                ),
                extraBlocks = listOf(ArcSSABlock(handler, emptyList())),
                extraEdges = setOf(exceptional),
            )
        )

        assertTrue(result.rejected.isEmpty())
        assertEquals(setOf(ArcSSADestroyPlacement.OnEdge(exceptional)), result.accepted.single().destroyPlacements)
    }

    @Test
    fun destroyStopsBeforeDeinitBarrier() {
        val left = ArcSSAValue("left")
        val right = ArcSSAValue("right")
        val joined = ArcSSAValue("joined")
        val result = ArcOwnershipSSAAnalysis.analyze(
            diamond(
                listOf(ArcSSAOperation.Introduce(left, ArcOwnership.Owned)),
                listOf(ArcSSAOperation.Introduce(right, ArcOwnership.Owned)),
                listOf(
                    ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to left, elseBlock to right)),
                    ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow),
                    ArcSSAOperation.DeinitBarrier(),
                ),
            )
        )

        assertTrue(result.rejected.isEmpty())
        assertEquals(setOf(ArcSSADestroyPlacement.BeforeOperation(merge, 2)), result.accepted.single().destroyPlacements)
    }

    @Test
    fun requiredCleanupOnCriticalExceptionalEdgeBailsOut() {
        val left = ArcSSAValue("left")
        val right = ArcSSAValue("right")
        val joined = ArcSSAValue("joined")
        val handler = ArcBlockId("handler")
        val other = ArcBlockId("other")
        val result = ArcOwnershipSSAAnalysis.analyze(
            diamond(
                thenOperations = listOf(ArcSSAOperation.Introduce(left, ArcOwnership.Owned)),
                elseOperations = listOf(ArcSSAOperation.Introduce(right, ArcOwnership.Owned)),
                mergeOperations = listOf(
                    ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to left, elseBlock to right)),
                    ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow, mayThrow = true),
                ),
                extraBlocks = listOf(ArcSSABlock(handler, emptyList()), ArcSSABlock(other, emptyList())),
                extraEdges = setOf(
                    ArcSSAEdge(merge, handler, ArcSSAEdgeKind.Exceptional),
                    ArcSSAEdge(other, handler),
                ),
            )
        )

        assertEquals(ArcOwnershipSSARejectionReason.CRITICAL_EDGE_DESTROY, result.rejected.single().reason)
    }

    @Test
    fun duplicateIncomingDefinitionRejectsWholeWeb() {
        val left = ArcSSAValue("left")
        val right = ArcSSAValue("right")
        val joined = ArcSSAValue("joined")
        val result = ArcOwnershipSSAAnalysis.analyze(
            diamond(
                thenOperations = listOf(
                    ArcSSAOperation.Introduce(left, ArcOwnership.Immortal),
                    ArcSSAOperation.Introduce(left, ArcOwnership.Owned),
                ),
                elseOperations = listOf(ArcSSAOperation.Introduce(right, ArcOwnership.Owned)),
                mergeOperations = listOf(ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to left, elseBlock to right))),
            )
        )

        assertEquals(ArcOwnershipSSARejectionReason.MALFORMED_CFG, result.rejected.single().reason)
    }

    @Test
    fun incomingDefinitionMustDominateItsPredecessorEdge() {
        val left = ArcSSAValue("left")
        val right = ArcSSAValue("right")
        val joined = ArcSSAValue("joined")
        val result = ArcOwnershipSSAAnalysis.analyze(
            diamond(
                thenOperations = emptyList(),
                elseOperations = listOf(ArcSSAOperation.Introduce(right, ArcOwnership.Owned)),
                mergeOperations = listOf(
                    ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to left, elseBlock to right)),
                    ArcSSAOperation.Introduce(left, ArcOwnership.Owned),
                ),
            )
        )

        assertEquals(ArcOwnershipSSARejectionReason.INVALID_SSA_DOMINANCE, result.rejected.single().reason)
    }

    @Test
    fun exceptionalHandlerUseRejectsPrematureEdgeCleanup() {
        val left = ArcSSAValue("left")
        val right = ArcSSAValue("right")
        val joined = ArcSSAValue("joined")
        val handler = ArcBlockId("handler")
        val result = ArcOwnershipSSAAnalysis.analyze(
            diamond(
                thenOperations = listOf(ArcSSAOperation.Introduce(left, ArcOwnership.Owned)),
                elseOperations = listOf(ArcSSAOperation.Introduce(right, ArcOwnership.Owned)),
                mergeOperations = listOf(
                    ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to left, elseBlock to right)),
                    ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow, mayThrow = true),
                ),
                extraBlocks = listOf(ArcSSABlock(handler, listOf(ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow)))),
                extraEdges = setOf(ArcSSAEdge(merge, handler, ArcSSAEdgeKind.Exceptional)),
            )
        )

        assertEquals(ArcOwnershipSSARejectionReason.AMBIGUOUS_EXCEPTIONAL_CLEANUP, result.rejected.single().reason)
    }

    @Test
    fun multipleThrowingOperationsInOneBlockRejectAmbiguousEdgeCleanup() {
        val left = ArcSSAValue("left")
        val right = ArcSSAValue("right")
        val joined = ArcSSAValue("joined")
        val other = ArcSSAValue("other")
        val handler = ArcBlockId("handler")
        val result = ArcOwnershipSSAAnalysis.analyze(
            diamond(
                entryOperations = listOf(ArcSSAOperation.Introduce(other, ArcOwnership.Owned)),
                thenOperations = listOf(ArcSSAOperation.Introduce(left, ArcOwnership.Owned)),
                elseOperations = listOf(ArcSSAOperation.Introduce(right, ArcOwnership.Owned)),
                mergeOperations = listOf(
                    ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to left, elseBlock to right)),
                    ArcSSAOperation.Use(other, ArcSSAUseKind.Borrow, mayThrow = true),
                    ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow, mayThrow = true),
                ),
                extraBlocks = listOf(ArcSSABlock(handler, emptyList())),
                extraEdges = setOf(ArcSSAEdge(merge, handler, ArcSSAEdgeKind.Exceptional)),
            )
        )

        assertEquals(ArcOwnershipSSARejectionReason.AMBIGUOUS_EXCEPTIONAL_CLEANUP, result.rejected.single().reason)
    }

    @Test
    fun asymmetricPathsEachReceiveExactlyOneDestroy() {
        val left = ArcSSAValue("left")
        val right = ArcSSAValue("right")
        val joined = ArcSSAValue("joined")
        val barrierPath = ArcBlockId("barrierPath")
        val plainPath = ArcBlockId("plainPath")
        val barrierExit = ArcBlockId("barrierExit")
        val plainExit = ArcBlockId("plainExit")
        val cfg = ArcOwnershipSSAInput(
            entry,
            listOf(
                ArcSSABlock(entry, emptyList()),
                ArcSSABlock(thenBlock, listOf(ArcSSAOperation.Introduce(left, ArcOwnership.Owned))),
                ArcSSABlock(elseBlock, listOf(ArcSSAOperation.Introduce(right, ArcOwnership.Owned))),
                ArcSSABlock(merge, listOf(
                    ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to left, elseBlock to right)),
                    ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow),
                )),
                ArcSSABlock(barrierPath, listOf(ArcSSAOperation.DeinitBarrier())),
                ArcSSABlock(plainPath, emptyList()),
                ArcSSABlock(barrierExit, emptyList()),
                ArcSSABlock(plainExit, emptyList()),
            ).associateBy { it.id },
            setOf(
                ArcSSAEdge(entry, thenBlock), ArcSSAEdge(entry, elseBlock),
                ArcSSAEdge(thenBlock, merge), ArcSSAEdge(elseBlock, merge),
                ArcSSAEdge(merge, barrierPath), ArcSSAEdge(merge, plainPath),
                ArcSSAEdge(barrierPath, barrierExit), ArcSSAEdge(plainPath, plainExit),
            ),
        )

        val web = ArcOwnershipSSAAnalysis.analyze(cfg).accepted.single()
        assertEquals(
            setOf(
                ArcSSADestroyPlacement.BeforeOperation(barrierPath, 0),
                ArcSSADestroyPlacement.BeforeExit(plainExit),
            ),
            web.destroyPlacements,
        )
    }

    @Test
    fun reconvergingDestroyedAndLivePathsFailClosed() {
        val left = ArcSSAValue("left")
        val right = ArcSSAValue("right")
        val joined = ArcSSAValue("joined")
        val barrierPath = ArcBlockId("barrierPath")
        val plainPath = ArcBlockId("plainPath")
        val commonExit = ArcBlockId("commonExit")
        val cfg = ArcOwnershipSSAInput(
            entry,
            listOf(
                ArcSSABlock(entry, emptyList()),
                ArcSSABlock(thenBlock, listOf(ArcSSAOperation.Introduce(left, ArcOwnership.Owned))),
                ArcSSABlock(elseBlock, listOf(ArcSSAOperation.Introduce(right, ArcOwnership.Owned))),
                ArcSSABlock(merge, listOf(ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to left, elseBlock to right)))),
                ArcSSABlock(barrierPath, listOf(ArcSSAOperation.DeinitBarrier())),
                ArcSSABlock(plainPath, emptyList()),
                ArcSSABlock(commonExit, emptyList()),
            ).associateBy { it.id },
            setOf(
                ArcSSAEdge(entry, thenBlock), ArcSSAEdge(entry, elseBlock),
                ArcSSAEdge(thenBlock, merge), ArcSSAEdge(elseBlock, merge),
                ArcSSAEdge(merge, barrierPath), ArcSSAEdge(merge, plainPath),
                ArcSSAEdge(barrierPath, commonExit), ArcSSAEdge(plainPath, commonExit),
            ),
        )

        assertEquals(
            ArcOwnershipSSARejectionReason.AMBIGUOUS_DESTROY_PLACEMENT,
            ArcOwnershipSSAAnalysis.analyze(cfg).rejected.single().reason,
        )
    }

    @Test
    fun overlappingSequentialJoinWebsAreBothRejected() {
        val firstLeft = ArcSSAValue("firstLeft")
        val firstRight = ArcSSAValue("firstRight")
        val firstJoin = ArcSSAValue("firstJoin")
        val secondLeft = ArcSSAValue("secondLeft")
        val secondRight = ArcSSAValue("secondRight")
        val secondJoin = ArcSSAValue("secondJoin")
        val secondLeftBlock = ArcBlockId("secondLeft")
        val secondRightBlock = ArcBlockId("secondRight")
        val secondMerge = ArcBlockId("secondMerge")
        val cfg = ArcOwnershipSSAInput(
            entry,
            listOf(
                ArcSSABlock(entry, emptyList()),
                ArcSSABlock(thenBlock, listOf(ArcSSAOperation.Introduce(firstLeft, ArcOwnership.Owned))),
                ArcSSABlock(elseBlock, listOf(ArcSSAOperation.Introduce(firstRight, ArcOwnership.Owned))),
                ArcSSABlock(merge, listOf(
                    ArcSSAOperation.Join(firstJoin, linkedMapOf(thenBlock to firstLeft, elseBlock to firstRight)),
                )),
                ArcSSABlock(secondLeftBlock, listOf(ArcSSAOperation.Forward(firstJoin, secondLeft))),
                ArcSSABlock(secondRightBlock, listOf(ArcSSAOperation.Forward(firstJoin, secondRight))),
                ArcSSABlock(secondMerge, listOf(
                    ArcSSAOperation.Join(secondJoin, linkedMapOf(secondLeftBlock to secondLeft, secondRightBlock to secondRight)),
                )),
                ArcSSABlock(exit, emptyList()),
            ).associateBy { it.id },
            setOf(
                ArcSSAEdge(entry, thenBlock), ArcSSAEdge(entry, elseBlock),
                ArcSSAEdge(thenBlock, merge), ArcSSAEdge(elseBlock, merge),
                ArcSSAEdge(merge, secondLeftBlock), ArcSSAEdge(merge, secondRightBlock),
                ArcSSAEdge(secondLeftBlock, secondMerge), ArcSSAEdge(secondRightBlock, secondMerge),
                ArcSSAEdge(secondMerge, exit),
            ),
        )

        val result = ArcOwnershipSSAAnalysis.analyze(cfg)
        assertTrue(result.accepted.isEmpty())
        assertEquals(
            setOf(ArcOwnershipSSARejectionReason.OVERLAPPING_WEB),
            result.rejected.mapTo(mutableSetOf()) { it.reason },
        )
        assertEquals(2, result.rejected.size)
    }

    @Test
    fun ownedSlotBorrowMoveAndDestroyConsumeOneVersionAtATime() {
        val value = ArcSSAValue("value")
        val borrowed = ArcSSAValue("borrowed")
        val source = ArcSSASlot("source")
        val destination = ArcSSASlot("destination")
        val sourceVersion = ArcSSASlotVersion("source.0")
        val destinationVersion = ArcSSASlotVersion("destination.0")
        val borrow = ArcSSABorrowId("borrow.0")
        val cfg = ArcOwnershipSSAInput(
            entry,
            mapOf(entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(source, sourceVersion, value),
                ArcSSAOperation.Borrow(source, sourceVersion, value, borrowed, borrow),
                ArcSSAOperation.Use(borrowed, ArcSSAUseKind.Borrow),
                ArcSSAOperation.EndBorrow(borrow),
                ArcSSAOperation.MoveOwned(source, sourceVersion, destination, destinationVersion, value),
                ArcSSAOperation.DestroyOwned(destination, destinationVersion, value),
            ))),
            emptySet(),
        )

        val result = ArcOwnershipSSAAnalysis.analyze(cfg)

        assertTrue(result.slotFlow.rejections.toString(), result.slotFlow.verified)
        assertTrue(result.slotFlow.blockExitStates.getValue(entry).facts.isEmpty())
        assertEquals(
            setOf(value),
            result.slotFlow.blockExitStates.getValue(entry).destroyedOwnedIdentities,
        )
    }

    @Test
    fun liveBorrowPreventsMove() {
        val value = ArcSSAValue("value")
        val borrowed = ArcSSAValue("borrowed")
        val source = ArcSSASlot("source")
        val destination = ArcSSASlot("destination")
        val sourceVersion = ArcSSASlotVersion("source.0")
        val borrow = ArcSSABorrowId("borrow.0")
        val result = ArcOwnershipSSAAnalysis.analyze(
            ArcOwnershipSSAInput(
                entry,
                mapOf(entry to ArcSSABlock(entry, listOf(
                    ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                    ArcSSAOperation.InitializeOwned(source, sourceVersion, value),
                    ArcSSAOperation.Borrow(source, sourceVersion, value, borrowed, borrow),
                    ArcSSAOperation.MoveOwned(
                        source, sourceVersion, destination, ArcSSASlotVersion("destination.0"), value,
                    ),
                ))),
                emptySet(),
            )
        )

        assertEquals(ArcSSASlotRejectionReason.LIVE_BORROW, result.slotFlow.rejections.single().reason)
    }

    @Test
    fun liveBorrowPreventsDestroy() {
        val value = ArcSSAValue("value")
        val borrowed = ArcSSAValue("borrowed")
        val slot = ArcSSASlot("source")
        val version = ArcSSASlotVersion("source.0")
        val result = ArcOwnershipSSAAnalysis.analyze(
            ArcOwnershipSSAInput(
                entry,
                mapOf(entry to ArcSSABlock(entry, listOf(
                    ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                    ArcSSAOperation.InitializeOwned(slot, version, value),
                    ArcSSAOperation.Borrow(slot, version, value, borrowed, ArcSSABorrowId("borrow.0")),
                    ArcSSAOperation.DestroyOwned(slot, version, value),
                ))),
                emptySet(),
            )
        )

        assertEquals(ArcSSASlotRejectionReason.LIVE_BORROW, result.slotFlow.rejections.single().reason)
    }

    @Test
    fun initializationAfterThrowExistsOnlyOnNormalEdge() {
        val value = ArcSSAValue("value")
        val slot = ArcSSASlot("result")
        val version = ArcSSASlotVersion("result.0")
        val normalExit = ArcBlockId("normalExit")
        val handler = ArcBlockId("handler")
        val normal = ArcSSAEdge(entry, normalExit)
        val exceptional = ArcSSAEdge(entry, handler, ArcSSAEdgeKind.Exceptional)
        val cfg = ArcOwnershipSSAInput(
            entry,
            linkedMapOf(
                entry to ArcSSABlock(entry, listOf(
                    ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                    ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow, mayThrow = true),
                    ArcSSAOperation.InitializeOwned(slot, version, value),
                )),
                normalExit to ArcSSABlock(normalExit, listOf(ArcSSAOperation.DestroyOwned(slot, version, value))),
                handler to ArcSSABlock(handler, emptyList()),
            ),
            setOf(normal, exceptional),
        )

        val result = ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow

        assertTrue(result.rejections.toString(), result.verified)
        assertEquals(version, result.normalEdgeStates.getValue(normal)[slot]?.version)
        assertNull(result.exceptionalEdgeStates.getValue(exceptional)[slot])
    }

    @Test
    fun explicitJoinSlotVerifiesPathDisjointOwnedAlternatives() {
        val left = ArcSSAValue("left")
        val right = ArcSSAValue("right")
        val joined = ArcSSAValue("joined")
        val borrowed = ArcSSAValue("borrowed")
        val slot = ArcSSASlot("result")
        val leftVersion = ArcSSASlotVersion("result.left")
        val rightVersion = ArcSSASlotVersion("result.right")
        val joinedVersion = ArcSSASlotVersion("result.joined")
        val leftEdge = ArcSSAEdge(thenBlock, merge)
        val rightEdge = ArcSSAEdge(elseBlock, merge)
        val borrow = ArcSSABorrowId("joined.borrow")
        val cfg = diamond(
            thenOperations = listOf(
                ArcSSAOperation.Introduce(left, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(slot, leftVersion, left),
            ),
            elseOperations = listOf(
                ArcSSAOperation.Introduce(right, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(slot, rightVersion, right),
            ),
            mergeOperations = listOf(
                ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to left, elseBlock to right)),
                ArcSSAOperation.JoinSlot(
                    slot,
                    joinedVersion,
                    joined,
                    linkedMapOf(leftEdge to leftVersion, rightEdge to rightVersion),
                ),
                ArcSSAOperation.Borrow(slot, joinedVersion, joined, borrowed, borrow),
                ArcSSAOperation.Use(borrowed, ArcSSAUseKind.Borrow),
                ArcSSAOperation.EndBorrow(borrow),
                ArcSSAOperation.DestroyOwned(slot, joinedVersion, joined),
            ),
        )

        val result = ArcOwnershipSSAAnalysis.analyze(cfg)

        assertTrue(result.slotFlow.rejections.toString(), result.slotFlow.verified)
        val fact = result.slotFlow.blockEntryStates.getValue(merge)[slot]!!
        assertEquals(joinedVersion, fact.version)
        assertTrue(fact.identity is ArcSSASlotRCIdentity.PathDisjoint)
    }

    @Test
    fun unequalSlotVersionsWithoutExplicitJoinFailClosed() {
        val left = ArcSSAValue("left")
        val right = ArcSSAValue("right")
        val joined = ArcSSAValue("joined")
        val slot = ArcSSASlot("result")
        val cfg = diamond(
            thenOperations = listOf(
                ArcSSAOperation.Introduce(left, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(slot, ArcSSASlotVersion("left"), left),
            ),
            elseOperations = listOf(
                ArcSSAOperation.Introduce(right, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(slot, ArcSSASlotVersion("right"), right),
            ),
            mergeOperations = listOf(
                ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to left, elseBlock to right)),
            ),
        )

        val rejection = ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single()

        assertEquals(ArcSSASlotRejectionReason.INVALID_PATH_DISJOINT_JOIN, rejection.reason)
    }

    @Test
    fun mixedInitializedAndEmptyPathsFailClosed() {
        val value = ArcSSAValue("value")
        val slot = ArcSSASlot("result")
        val cfg = diamond(
            thenOperations = listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(slot, ArcSSASlotVersion("then"), value),
            ),
            elseOperations = emptyList(),
            mergeOperations = emptyList(),
        )

        assertEquals(
            ArcSSASlotRejectionReason.MIXED_INITIALIZATION,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun mixedOwnedAndImmortalSlotAlternativesFailClosed() {
        val owned = ArcSSAValue("owned")
        val immortal = ArcSSAValue("immortal")
        val joined = ArcSSAValue("joined")
        val slot = ArcSSASlot("result")
        val cfg = diamond(
            thenOperations = listOf(
                ArcSSAOperation.Introduce(owned, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(slot, ArcSSASlotVersion("owned"), owned),
            ),
            elseOperations = listOf(
                ArcSSAOperation.Introduce(immortal, ArcOwnership.Immortal),
                ArcSSAOperation.InitializeImmortal(slot, ArcSSASlotVersion("immortal"), immortal),
            ),
            mergeOperations = listOf(
                ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to owned, elseBlock to immortal)),
            ),
        )

        assertEquals(
            ArcSSASlotRejectionReason.MIXED_OWNERSHIP,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun slotVersionsAndBorrowIdsAreGloballyUniqueProofTokens() {
        val first = ArcSSAValue("first")
        val second = ArcSSAValue("second")
        val duplicated = ArcSSASlotVersion("duplicate")
        val cfg = diamond(
            thenOperations = listOf(
                ArcSSAOperation.Introduce(first, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(ArcSSASlot("left"), duplicated, first),
            ),
            elseOperations = listOf(
                ArcSSAOperation.Introduce(second, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(ArcSSASlot("right"), duplicated, second),
            ),
            mergeOperations = emptyList(),
        )

        assertEquals(
            ArcSSASlotRejectionReason.DUPLICATE_VERSION,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun borrowIdsAreGloballyUniqueProofTokens() {
        val value = ArcSSAValue("value")
        val firstBorrow = ArcSSAValue("firstBorrow")
        val secondBorrow = ArcSSAValue("secondBorrow")
        val slot = ArcSSASlot("slot")
        val version = ArcSSASlotVersion("slot.0")
        val duplicated = ArcSSABorrowId("duplicate")
        val cfg = ArcOwnershipSSAInput(
            entry,
            mapOf(entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(slot, version, value),
                ArcSSAOperation.Borrow(slot, version, value, firstBorrow, duplicated),
                ArcSSAOperation.EndBorrow(duplicated),
                ArcSSAOperation.Borrow(slot, version, value, secondBorrow, duplicated),
                ArcSSAOperation.EndBorrow(duplicated),
                ArcSSAOperation.DestroyOwned(slot, version, value),
            ))),
            emptySet(),
        )

        assertEquals(
            ArcSSASlotRejectionReason.DUPLICATE_BORROW_ID,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun useAfterDestroyRejectsTheCompleteIdentity() {
        val value = ArcSSAValue("value")
        val alias = ArcSSAValue("alias")
        val slot = ArcSSASlot("result")
        val version = ArcSSASlotVersion("result.0")
        val cfg = ArcOwnershipSSAInput(
            entry,
            mapOf(entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.Forward(value, alias),
                ArcSSAOperation.InitializeOwned(slot, version, value),
                ArcSSAOperation.DestroyOwned(slot, version, alias),
                ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow),
            ))),
            emptySet(),
        )

        assertEquals(
            ArcSSASlotRejectionReason.INVALID_SLOT_OPERATION,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun ownedSlotMustBeConsumedBeforeEveryExit() {
        val value = ArcSSAValue("value")
        val slot = ArcSSASlot("result")
        val cfg = ArcOwnershipSSAInput(
            entry,
            mapOf(entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(slot, ArcSSASlotVersion("result.0"), value),
            ))),
            emptySet(),
        )

        assertEquals(
            ArcSSASlotRejectionReason.UNCONSUMED_OWNED_AT_EXIT,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun slotInitializationCannotPrecedeItsSsaDefinition() {
        val value = ArcSSAValue("value")
        val slot = ArcSSASlot("slot")
        val result = ArcOwnershipSSAAnalysis.analyze(ArcOwnershipSSAInput(
            entry,
            mapOf(entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.InitializeOwned(slot, ArcSSASlotVersion("slot.0"), value),
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
            ))),
            emptySet(),
        ))

        assertEquals(ArcSSASlotRejectionReason.MALFORMED_CFG, result.slotFlow.rejections.single().reason)
    }

    @Test
    fun siblingBranchBorrowResultCannotBeUsedWithoutDominance() {
        val value = ArcSSAValue("value")
        val borrowed = ArcSSAValue("borrowed")
        val slot = ArcSSASlot("slot")
        val version = ArcSSASlotVersion("slot.0")
        val left = ArcBlockId("left")
        val right = ArcBlockId("right")
        val cfg = ArcOwnershipSSAInput(
            entry,
            linkedMapOf(
                entry to ArcSSABlock(entry, listOf(
                    ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                    ArcSSAOperation.InitializeOwned(slot, version, value),
                )),
                left to ArcSSABlock(left, listOf(
                    ArcSSAOperation.Borrow(slot, version, value, borrowed, ArcSSABorrowId("borrow")),
                )),
                right to ArcSSABlock(right, listOf(ArcSSAOperation.Use(borrowed, ArcSSAUseKind.Borrow))),
            ),
            setOf(ArcSSAEdge(entry, left), ArcSSAEdge(entry, right)),
        )

        assertEquals(
            ArcSSASlotRejectionReason.MALFORMED_CFG,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun joinInputMustDominateItsNamedPredecessorEnd() {
        val root = ArcSSAValue("root")
        val leftValue = ArcSSAValue("left")
        val joined = ArcSSAValue("joined")
        val slot = ArcSSASlot("slot")
        val left = ArcBlockId("left")
        val right = ArcBlockId("right")
        val mergeBlock = ArcBlockId("join")
        val cfg = ArcOwnershipSSAInput(entry, linkedMapOf(
            entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.Introduce(root, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(slot, ArcSSASlotVersion("slot.0"), root),
            )),
            left to ArcSSABlock(left, listOf(ArcSSAOperation.Introduce(leftValue, ArcOwnership.Owned))),
            right to ArcSSABlock(right, emptyList()),
            mergeBlock to ArcSSABlock(mergeBlock, listOf(
                ArcSSAOperation.Join(joined, linkedMapOf(left to leftValue, right to leftValue)),
            )),
        ), setOf(
            ArcSSAEdge(entry, left), ArcSSAEdge(entry, right),
            ArcSSAEdge(left, mergeBlock), ArcSSAEdge(right, mergeBlock),
        ))

        assertEquals(
            ArcSSASlotRejectionReason.MALFORMED_CFG,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun blockMapKeyMustMatchEmbeddedBlockId() {
        val value = ArcSSAValue("value")
        val wrong = ArcBlockId("wrong")
        val result = ArcOwnershipSSAAnalysis.analyze(ArcOwnershipSSAInput(
            entry,
            mapOf(entry to ArcSSABlock(wrong, listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(ArcSSASlot("slot"), ArcSSASlotVersion("slot.0"), value),
            ))),
            emptySet(),
        ))

        assertEquals(ArcSSASlotRejectionReason.MALFORMED_CFG, result.slotFlow.rejections.single().reason)
    }

    @Test
    fun endedBorrowResultCannotBeUsedAfterMovingItsOwner() {
        val value = ArcSSAValue("value")
        val borrowed = ArcSSAValue("borrowed")
        val source = ArcSSASlot("source")
        val destination = ArcSSASlot("destination")
        val sourceVersion = ArcSSASlotVersion("source.0")
        val destinationVersion = ArcSSASlotVersion("destination.0")
        val borrowId = ArcSSABorrowId("borrow")
        val cfg = ArcOwnershipSSAInput(entry, mapOf(entry to ArcSSABlock(entry, listOf(
            ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
            ArcSSAOperation.InitializeOwned(source, sourceVersion, value),
            ArcSSAOperation.Borrow(source, sourceVersion, value, borrowed, borrowId),
            ArcSSAOperation.EndBorrow(borrowId),
            ArcSSAOperation.MoveOwned(source, sourceVersion, destination, destinationVersion, value),
            ArcSSAOperation.Use(borrowed, ArcSSAUseKind.Borrow),
        ))), emptySet())

        assertEquals(
            ArcSSASlotRejectionReason.INVALID_BORROW_USE,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun endedBorrowCannotCreateForwardAlias() {
        val value = ArcSSAValue("value")
        val borrowed = ArcSSAValue("borrowed")
        val alias = ArcSSAValue("alias")
        val slot = ArcSSASlot("slot")
        val version = ArcSSASlotVersion("slot.0")
        val borrowId = ArcSSABorrowId("borrow")
        val cfg = ArcOwnershipSSAInput(entry, mapOf(entry to ArcSSABlock(entry, listOf(
            ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
            ArcSSAOperation.InitializeOwned(slot, version, value),
            ArcSSAOperation.Borrow(slot, version, value, borrowed, borrowId),
            ArcSSAOperation.EndBorrow(borrowId),
            ArcSSAOperation.Forward(borrowed, alias),
            ArcSSAOperation.Use(alias, ArcSSAUseKind.Borrow),
        ))), emptySet())

        assertEquals(
            ArcSSASlotRejectionReason.INVALID_BORROW_USE,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun endedBorrowCannotCreateReborrowAlias() {
        val value = ArcSSAValue("value")
        val borrowed = ArcSSAValue("borrowed")
        val alias = ArcSSAValue("alias")
        val slot = ArcSSASlot("slot")
        val version = ArcSSASlotVersion("slot.0")
        val borrowId = ArcSSABorrowId("borrow")
        val cfg = ArcOwnershipSSAInput(entry, mapOf(entry to ArcSSABlock(entry, listOf(
            ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
            ArcSSAOperation.InitializeOwned(slot, version, value),
            ArcSSAOperation.Borrow(slot, version, value, borrowed, borrowId),
            ArcSSAOperation.EndBorrow(borrowId),
            ArcSSAOperation.Reborrow(borrowed, alias, setOf(value)),
        ))), emptySet())

        assertEquals(
            ArcSSASlotRejectionReason.INVALID_BORROW_USE,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun endedBorrowCannotCreateJoinAlias() {
        val value = ArcSSAValue("value")
        val borrowed = ArcSSAValue("borrowed")
        val joined = ArcSSAValue("joined")
        val slot = ArcSSASlot("slot")
        val version = ArcSSASlotVersion("slot.0")
        val borrowId = ArcSSABorrowId("borrow")
        val left = ArcBlockId("left")
        val right = ArcBlockId("right")
        val join = ArcBlockId("join")
        val cfg = ArcOwnershipSSAInput(entry, linkedMapOf(
            entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(slot, version, value),
                ArcSSAOperation.Borrow(slot, version, value, borrowed, borrowId),
                ArcSSAOperation.EndBorrow(borrowId),
            )),
            left to ArcSSABlock(left, emptyList()),
            right to ArcSSABlock(right, emptyList()),
            join to ArcSSABlock(join, listOf(
                ArcSSAOperation.Join(joined, linkedMapOf(left to borrowed, right to borrowed)),
            )),
        ), setOf(
            ArcSSAEdge(entry, left), ArcSSAEdge(entry, right),
            ArcSSAEdge(left, join), ArcSSAEdge(right, join),
        ))

        assertEquals(
            ArcSSASlotRejectionReason.INVALID_BORROW_USE,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun activeBorrowAliasRejectsEscapingEffect() {
        val value = ArcSSAValue("value")
        val borrowed = ArcSSAValue("borrowed")
        val alias = ArcSSAValue("alias")
        val slot = ArcSSASlot("slot")
        val version = ArcSSASlotVersion("slot.0")
        val borrowId = ArcSSABorrowId("borrow")
        val cfg = ArcOwnershipSSAInput(entry, mapOf(entry to ArcSSABlock(entry, listOf(
            ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
            ArcSSAOperation.InitializeOwned(slot, version, value),
            ArcSSAOperation.Borrow(slot, version, value, borrowed, borrowId),
            ArcSSAOperation.Forward(borrowed, alias),
            ArcSSAOperation.Use(alias, ArcSSAUseKind.Escape),
        ))), emptySet())

        assertEquals(
            ArcSSASlotRejectionReason.INVALID_BORROW_USE,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun endedBorrowCannotCreateAliasInSuccessorBlock() {
        val value = ArcSSAValue("value")
        val borrowed = ArcSSAValue("borrowed")
        val alias = ArcSSAValue("alias")
        val slot = ArcSSASlot("slot")
        val version = ArcSSASlotVersion("slot.0")
        val borrowId = ArcSSABorrowId("borrow")
        val next = ArcBlockId("next")
        val cfg = ArcOwnershipSSAInput(entry, linkedMapOf(
            entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(slot, version, value),
                ArcSSAOperation.Borrow(slot, version, value, borrowed, borrowId),
                ArcSSAOperation.EndBorrow(borrowId),
            )),
            next to ArcSSABlock(next, listOf(
                ArcSSAOperation.Forward(borrowed, alias),
                ArcSSAOperation.Use(alias, ArcSSAUseKind.Borrow),
            )),
        ), setOf(ArcSSAEdge(entry, next)))

        assertEquals(
            ArcSSASlotRejectionReason.INVALID_BORROW_USE,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun everySlotOperationRequiresItsBorrowDependentOperandsToRemainActive() {
        listOf("InitializeOwned", "InitializeImmortal", "JoinSlot", "MoveOwned", "DestroyOwned").forEach { shape ->
            val owner = ArcSSAValue("owner.$shape")
            val borrowed = ArcSSAValue("borrowed.$shape")
            val value = ArcSSAValue("value.$shape")
            val ownerSlot = ArcSSASlot("owner.$shape")
            val valueSlot = ArcSSASlot("value.$shape")
            val ownerVersion = ArcSSASlotVersion("owner.$shape.0")
            val valueVersion = ArcSSASlotVersion("value.$shape.0")
            val borrowId = ArcSSABorrowId("borrow.$shape")
            val operations = mutableListOf<ArcSSAOperation>(
                ArcSSAOperation.Introduce(owner, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(ownerSlot, ownerVersion, owner),
                ArcSSAOperation.Borrow(ownerSlot, ownerVersion, owner, borrowed, borrowId),
            )
            when (shape) {
                "InitializeOwned" -> operations += ArcSSAOperation.Introduce(
                    value, ArcOwnership.Owned, setOf(borrowed),
                )
                "InitializeImmortal" -> operations += ArcSSAOperation.Introduce(
                    value, ArcOwnership.Immortal, setOf(borrowed),
                )
                "MoveOwned", "DestroyOwned" -> {
                    operations += ArcSSAOperation.Introduce(value, ArcOwnership.Owned, setOf(borrowed))
                    operations += ArcSSAOperation.InitializeOwned(valueSlot, valueVersion, value)
                }
            }
            operations += ArcSSAOperation.EndBorrow(borrowId)
            operations += when (shape) {
                "InitializeOwned" -> ArcSSAOperation.InitializeOwned(valueSlot, valueVersion, value)
                "InitializeImmortal" -> ArcSSAOperation.InitializeImmortal(valueSlot, valueVersion, value)
                "JoinSlot" -> ArcSSAOperation.JoinSlot(
                    valueSlot, valueVersion, borrowed, emptyMap(),
                )
                "MoveOwned" -> ArcSSAOperation.MoveOwned(
                    valueSlot, valueVersion, ArcSSASlot("moved.$shape"), ArcSSASlotVersion("moved.$shape.0"), value,
                )
                "DestroyOwned" -> ArcSSAOperation.DestroyOwned(valueSlot, valueVersion, value)
                else -> error("unknown test shape $shape")
            }
            val cfg = ArcOwnershipSSAInput(
                entry,
                mapOf(entry to ArcSSABlock(entry, operations)),
                emptySet(),
            )

            assertEquals(
                shape,
                ArcSSASlotRejectionReason.INVALID_BORROW_USE,
                ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
            )
        }
    }

    @Test
    fun activeBorrowRejectsNonBorrowEffects() {
        listOf(ArcSSAUseKind.Escape, ArcSSAUseKind.Consume, ArcSSAUseKind.UnknownConsume).forEach { kind ->
            val value = ArcSSAValue("value.$kind")
            val borrowed = ArcSSAValue("borrowed.$kind")
            val slot = ArcSSASlot("slot.$kind")
            val version = ArcSSASlotVersion("slot.$kind.0")
            val borrowId = ArcSSABorrowId("borrow.$kind")
            val cfg = ArcOwnershipSSAInput(entry, mapOf(entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(slot, version, value),
                ArcSSAOperation.Borrow(slot, version, value, borrowed, borrowId),
                ArcSSAOperation.Use(borrowed, kind),
            ))), emptySet())

            assertEquals(
                kind.toString(),
                ArcSSASlotRejectionReason.INVALID_BORROW_USE,
                ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
            )
        }
    }

    @Test
    fun endedBorrowCannotBeUsedInSuccessorBlock() {
        val value = ArcSSAValue("value")
        val borrowed = ArcSSAValue("borrowed")
        val slot = ArcSSASlot("slot")
        val version = ArcSSASlotVersion("slot.0")
        val borrowId = ArcSSABorrowId("borrow")
        val next = ArcBlockId("next")
        val cfg = ArcOwnershipSSAInput(entry, linkedMapOf(
            entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(slot, version, value),
                ArcSSAOperation.Borrow(slot, version, value, borrowed, borrowId),
                ArcSSAOperation.EndBorrow(borrowId),
            )),
            next to ArcSSABlock(next, listOf(ArcSSAOperation.Use(borrowed, ArcSSAUseKind.Borrow))),
        ), setOf(ArcSSAEdge(entry, next)))

        assertEquals(
            ArcSSASlotRejectionReason.INVALID_BORROW_USE,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun joinSlotCannotAppearAfterBorrowOrDestroyEffects() {
        val left = ArcSSAValue("left")
        val right = ArcSSAValue("right")
        val joined = ArcSSAValue("joined")
        val borrowed = ArcSSAValue("borrowed")
        val slot = ArcSSASlot("slot")
        val leftVersion = ArcSSASlotVersion("left")
        val rightVersion = ArcSSASlotVersion("right")
        val joinedVersion = ArcSSASlotVersion("joined")
        val borrowId = ArcSSABorrowId("borrow")
        val cfg = diamond(
            thenOperations = listOf(
                ArcSSAOperation.Introduce(left, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(slot, leftVersion, left),
            ),
            elseOperations = listOf(
                ArcSSAOperation.Introduce(right, ArcOwnership.Owned),
                ArcSSAOperation.InitializeOwned(slot, rightVersion, right),
            ),
            mergeOperations = listOf(
                ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to left, elseBlock to right)),
                ArcSSAOperation.Borrow(slot, joinedVersion, joined, borrowed, borrowId),
                ArcSSAOperation.EndBorrow(borrowId),
                ArcSSAOperation.DestroyOwned(slot, joinedVersion, joined),
                ArcSSAOperation.JoinSlot(
                    slot, joinedVersion, joined,
                    linkedMapOf(ArcSSAEdge(thenBlock, merge) to leftVersion, ArcSSAEdge(elseBlock, merge) to rightVersion),
                ),
            ),
        )

        assertEquals(
            ArcSSASlotRejectionReason.INVALID_PATH_DISJOINT_JOIN,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun throwingOperationWithLiveOwnedSlotRequiresExceptionalEdge() {
        val value = ArcSSAValue("value")
        val slot = ArcSSASlot("slot")
        val version = ArcSSASlotVersion("slot.0")
        val cfg = ArcOwnershipSSAInput(entry, mapOf(entry to ArcSSABlock(entry, listOf(
            ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
            ArcSSAOperation.InitializeOwned(slot, version, value),
            ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow, mayThrow = true),
            ArcSSAOperation.DestroyOwned(slot, version, value),
        ))), emptySet())

        assertEquals(
            ArcSSASlotRejectionReason.UNMODELED_EXCEPTIONAL_EDGE,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    @Test
    fun slotFactsRejectBackedgesUntilOwnershipPhiCleanupIsModeled() {
        val value = ArcSSAValue("value")
        val loop = ArcBlockId("loop")
        val slot = ArcSSASlot("result")
        val cfg = ArcOwnershipSSAInput(
            entry,
            linkedMapOf(
                entry to ArcSSABlock(entry, listOf(
                    ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                    ArcSSAOperation.InitializeOwned(slot, ArcSSASlotVersion("entry"), value),
                )),
                loop to ArcSSABlock(loop, emptyList()),
            ),
            setOf(ArcSSAEdge(entry, loop), ArcSSAEdge(loop, loop)),
        )

        assertEquals(
            ArcSSASlotRejectionReason.BACKEDGE_STATE,
            ArcOwnershipSSAAnalysis.analyze(cfg).slotFlow.rejections.single().reason,
        )
    }

    private fun diamond(
        thenOperations: List<ArcSSAOperation>,
        elseOperations: List<ArcSSAOperation>,
        mergeOperations: List<ArcSSAOperation>,
        entryOperations: List<ArcSSAOperation> = emptyList(),
        extraBlocks: List<ArcSSABlock> = emptyList(),
        extraEdges: Set<ArcSSAEdge> = emptySet(),
    ): ArcOwnershipSSAInput {
        val blocks = listOf(
            ArcSSABlock(entry, entryOperations),
            ArcSSABlock(thenBlock, thenOperations),
            ArcSSABlock(elseBlock, elseOperations),
            ArcSSABlock(merge, mergeOperations),
            ArcSSABlock(exit, emptyList()),
        ) + extraBlocks
        return ArcOwnershipSSAInput(
            entry,
            blocks.associateBy { it.id },
            setOf(
                ArcSSAEdge(entry, thenBlock),
                ArcSSAEdge(entry, elseBlock),
                ArcSSAEdge(thenBlock, merge),
                ArcSSAEdge(elseBlock, merge),
                ArcSSAEdge(merge, exit),
            ) + extraEdges,
        )
    }
}
