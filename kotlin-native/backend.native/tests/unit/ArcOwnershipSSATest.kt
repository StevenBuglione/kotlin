/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
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
