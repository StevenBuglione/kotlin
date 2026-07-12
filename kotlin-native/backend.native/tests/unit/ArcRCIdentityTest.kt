/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcRCIdentityTest {
    private val entry = ArcBlockId("entry")
    private val leftBlock = ArcBlockId("left")
    private val rightBlock = ArcBlockId("right")
    private val merge = ArcBlockId("merge")
    private val exit = ArcBlockId("exit")

    @Test
    fun forwardAndReborrowReachOneCanonicalRootAtFixedPoint() {
        val root = ArcSSAValue("root")
        val alias = ArcSSAValue("alias")
        val reborrow = ArcSSAValue("reborrow")
        val cfg = linear(
            listOf(
                ArcSSAOperation.Introduce(root, ArcOwnership.Owned),
                ArcSSAOperation.Forward(root, alias),
                ArcSSAOperation.Reborrow(alias, reborrow, setOf(alias)),
                ArcSSAOperation.Use(reborrow, ArcSSAUseKind.Borrow),
            )
        )

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))

        assertTrue(result.issues.isEmpty())
        assertEquals(root, result.identity(root)?.singleRoot)
        assertEquals(root, result.identity(alias)?.singleRoot)
        assertEquals(root, result.identity(reborrow)?.singleRoot)
        assertEquals(setOf(root), result.identity(reborrow)?.anchorRoots)
    }

    @Test
    fun joinKeepsCompleteCanonicalProvenanceSetAndForwardsIt() {
        val left = ArcSSAValue("leftValue")
        val right = ArcSSAValue("rightValue")
        val joined = ArcSSAValue("joined")
        val forwarded = ArcSSAValue("forwarded")
        val cfg = diamond(
            leftOperations = listOf(ArcSSAOperation.Introduce(left, ArcOwnership.Owned)),
            rightOperations = listOf(ArcSSAOperation.Introduce(right, ArcOwnership.Owned)),
            mergeOperations = listOf(
                ArcSSAOperation.Join(joined, linkedMapOf(leftBlock to left, rightBlock to right)),
                ArcSSAOperation.Forward(joined, forwarded),
            ),
        )

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))

        assertEquals(setOf(left, right), result.identity(joined)?.provenanceRoots)
        assertEquals(setOf(left, right), result.identity(forwarded)?.provenanceRoots)
        assertNull(result.identity(joined)?.singleRoot)
    }

    @Test
    fun acceptedOwnershipWebIsPreservedAsPathDisjointIdentity() {
        val left = ArcSSAValue("leftValue")
        val right = ArcSSAValue("rightValue")
        val joined = ArcSSAValue("joined")
        val members = setOf(left, right, joined)
        val web = ArcOwnershipSSAWeb(
            join = joined,
            mergeBlock = merge,
            ownership = ArcOwnership.Owned,
            members = members,
            anchorDependencies = emptySet(),
            destroyPlacements = setOf(ArcSSADestroyPlacement.BeforeExit(exit)),
        )
        val cfg = diamond(
            leftOperations = listOf(ArcSSAOperation.Introduce(left, ArcOwnership.Owned)),
            rightOperations = listOf(ArcSSAOperation.Introduce(right, ArcOwnership.Owned)),
            mergeOperations = listOf(
                ArcSSAOperation.Join(joined, linkedMapOf(leftBlock to left, rightBlock to right)),
                ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow),
            ),
        )

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, listOf(web)))

        members.forEach { assertSame(web, result.identity(it)?.ownershipWeb) }
        assertTrue(ArcRCLifetimeFrontier.BeforeExit(exit) in result.liveness.lifetimeFrontier(joined))
    }

    @Test
    fun conservativeBarriersBlockMotionButPlainBorrowsDoNot() {
        val root = ArcSSAValue("root")
        val alias = ArcSSAValue("alias")
        val block = ArcBlockId("block")
        val cfg = ArcOwnershipSSAInput(
            entry = block,
            blocks = mapOf(
                block to ArcSSABlock(
                    block,
                    listOf(
                        ArcSSAOperation.Introduce(root, ArcOwnership.Owned),
                        ArcSSAOperation.Forward(root, alias),
                        ArcSSAOperation.Use(alias, ArcSSAUseKind.Borrow),
                        ArcSSAOperation.Use(alias, ArcSSAUseKind.Borrow, mayThrow = true),
                        ArcSSAOperation.DeinitBarrier(),
                        ArcSSAOperation.Use(alias, ArcSSAUseKind.UnknownConsume),
                    ),
                )
            ),
            edges = emptySet(),
        )
        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))

        assertTrue(result.canMoveWithinBlock(alias, block, 1, 2))
        assertFalse(result.canMoveWithinBlock(alias, block, 1, 3))
        assertFalse(result.canMoveWithinBlock(alias, block, 3, 4))
        assertFalse(result.canMoveWithinBlock(alias, block, 4, 5))
        assertEquals(
            listOf(ArcRCBarrierKind.ExceptionalCall, ArcRCBarrierKind.Deinitialization, ArcRCBarrierKind.UnknownEffect),
            result.barriers.map { it.kind },
        )
    }

    @Test
    fun prunedLivenessStopsAtBranchIntroducersAndIgnoresDeadExit() {
        val left = ArcSSAValue("leftValue")
        val right = ArcSSAValue("rightValue")
        val joined = ArcSSAValue("joined")
        val cfg = diamond(
            leftOperations = listOf(ArcSSAOperation.Introduce(left, ArcOwnership.Owned)),
            rightOperations = listOf(ArcSSAOperation.Introduce(right, ArcOwnership.Owned)),
            mergeOperations = listOf(
                ArcSSAOperation.Join(joined, linkedMapOf(leftBlock to left, rightBlock to right)),
                ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow),
            ),
        )
        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))

        assertFalse(result.liveness.isLiveBefore(joined, leftBlock, 0))
        assertTrue(result.liveness.isLiveAfter(joined, leftBlock, 0))
        assertFalse(result.liveness.isLiveBefore(joined, rightBlock, 0))
        assertTrue(result.liveness.isLiveAfter(joined, rightBlock, 0))
        assertTrue(result.liveness.isLiveBefore(joined, merge, 1))
        assertFalse(result.liveness.isLiveAfter(joined, merge, 1))
        assertTrue(
            ArcRCLifetimeFrontier.AfterOperation(ArcRCPosition(merge, 1)) in
                    result.liveness.lifetimeFrontier(joined)
        )
    }

    @Test
    fun throwingConsumeProducesConsumeAndExceptionalFrontiers() {
        val value = ArcSSAValue("value")
        val handler = ArcBlockId("handler")
        val exceptional = ArcSSAEdge(entry, handler, ArcSSAEdgeKind.Exceptional)
        val cfg = ArcOwnershipSSAInput(
            entry,
            mapOf(
                entry to ArcSSABlock(
                    entry,
                    listOf(
                        ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                        ArcSSAOperation.Use(value, ArcSSAUseKind.Consume, mayThrow = true),
                    ),
                ),
                handler to ArcSSABlock(handler, emptyList()),
            ),
            setOf(exceptional),
        )
        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))
        val frontier = result.liveness.lifetimeFrontier(value)

        assertTrue(ArcRCLifetimeFrontier.ConsumedAt(ArcRCPosition(entry, 1)) in frontier)
        assertTrue(ArcRCLifetimeFrontier.OnEdge(exceptional) in frontier)
    }

    @Test
    fun unresolvedForwardCycleAndMissingSourceFailClosed() {
        val first = ArcSSAValue("first")
        val second = ArcSSAValue("second")
        val missingResult = ArcSSAValue("missingResult")
        val missingSource = ArcSSAValue("missingSource")
        val cfg = linear(
            listOf(
                ArcSSAOperation.Forward(second, first),
                ArcSSAOperation.Forward(first, second),
                ArcSSAOperation.Forward(missingSource, missingResult),
            )
        )

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))

        assertEquals(
            setOf(ArcRCIdentityIssueKind.UnresolvedCycle, ArcRCIdentityIssueKind.MissingDefinition),
            result.issues.mapTo(mutableSetOf()) { it.kind },
        )
        assertNull(result.identity(first))
        assertNull(result.identity(missingResult))
    }

    @Test
    fun duplicateDefinitionsInvalidateTheirCompleteDependentFamily() {
        val root = ArcSSAValue("root")
        val alias = ArcSSAValue("alias")
        val cfg = linear(
            listOf(
                ArcSSAOperation.Introduce(root, ArcOwnership.Immortal),
                ArcSSAOperation.Introduce(root, ArcOwnership.Owned),
                ArcSSAOperation.Forward(root, alias),
                ArcSSAOperation.Use(alias, ArcSSAUseKind.Borrow),
            )
        )

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))

        assertEquals(setOf(ArcRCIdentityIssueKind.DuplicateDefinition), result.issues.mapTo(mutableSetOf()) { it.kind })
        assertNull(result.identity(root))
        assertNull(result.identity(alias))
        assertFalse(result.canMoveWithinBlock(alias, entry, 2, 3))
    }

    @Test
    fun overlappingOwnershipWebsInvalidateMemberAndDependents() {
        val root = ArcSSAValue("root")
        val leftOnly = ArcSSAValue("leftOnly")
        val rightOnly = ArcSSAValue("rightOnly")
        val firstJoin = ArcSSAValue("firstJoin")
        val secondJoin = ArcSSAValue("secondJoin")
        val cfg = linear(
            listOf(
                ArcSSAOperation.Introduce(root, ArcOwnership.Owned),
                ArcSSAOperation.Introduce(leftOnly, ArcOwnership.Owned),
                ArcSSAOperation.Introduce(rightOnly, ArcOwnership.Owned),
            )
        )
        fun web(join: ArcSSAValue, members: Set<ArcSSAValue>) = ArcOwnershipSSAWeb(
            join,
            entry,
            ArcOwnership.Owned,
            members,
            emptySet(),
            emptySet(),
        )

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, listOf(
            web(firstJoin, setOf(root, leftOnly)),
            web(secondJoin, setOf(root, rightOnly)),
        )))

        assertEquals(setOf(ArcRCIdentityIssueKind.OverlappingOwnershipWeb), result.issues.mapTo(mutableSetOf()) { it.kind })
        assertNull(result.identity(root))
        assertNull(result.identity(leftOnly))
        assertNull(result.identity(rightOnly))
        assertFalse(result.canMoveWithinBlock(leftOnly, entry, 1, 2))
    }

    @Test
    fun missingGuaranteedAnchorInvalidatesResolvedIdentity() {
        val missingAnchor = ArcSSAValue("missingAnchor")
        val value = ArcSSAValue("value")
        val cfg = linear(listOf(ArcSSAOperation.Introduce(value, ArcOwnership.Guaranteed, setOf(missingAnchor))))

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))

        assertEquals(setOf(ArcRCIdentityIssueKind.MissingDefinition), result.issues.mapTo(mutableSetOf()) { it.kind })
        assertEquals(value, result.issues.single().value)
        assertNull(result.identity(value))
    }

    @Test
    fun unrelatedThrowAndEffectsAreGlobalMotionBarriers() {
        val value = ArcSSAValue("value")
        val other = ArcSSAValue("other")
        val block = ArcBlockId("block")
        val cfg = ArcOwnershipSSAInput(
            block,
            mapOf(block to ArcSSABlock(block, listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow),
                ArcSSAOperation.Introduce(other, ArcOwnership.Owned),
                ArcSSAOperation.Use(other, ArcSSAUseKind.Borrow, mayThrow = true),
                ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow),
                ArcSSAOperation.Use(other, ArcSSAUseKind.UnknownConsume),
                ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow),
            ))),
            emptySet(),
        )
        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))

        assertFalse(result.canMoveWithinBlock(value, block, 1, 4))
        assertFalse(result.canMoveWithinBlock(value, block, 4, 6))
    }

    @Test
    fun exceptionalHandlerUseSuppressesPrematureEdgeFrontier() {
        val value = ArcSSAValue("value")
        val handler = ArcBlockId("handler")
        val exceptional = ArcSSAEdge(entry, handler, ArcSSAEdgeKind.Exceptional)
        val cfg = ArcOwnershipSSAInput(
            entry,
            mapOf(
                entry to ArcSSABlock(entry, listOf(
                    ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                    ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow, mayThrow = true),
                )),
                handler to ArcSSABlock(handler, listOf(ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow))),
            ),
            setOf(exceptional),
        )

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))
        val frontier = result.liveness.lifetimeFrontier(value)

        assertFalse(ArcRCLifetimeFrontier.OnEdge(exceptional) in frontier)
        assertTrue(result.liveness.isLiveBefore(value, handler, 0))
    }

    @Test
    fun multipleThrowingOperationsDoNotInventBlockLevelEdgeCleanup() {
        val value = ArcSSAValue("value")
        val other = ArcSSAValue("other")
        val handler = ArcBlockId("handler")
        val exceptional = ArcSSAEdge(entry, handler, ArcSSAEdgeKind.Exceptional)
        val cfg = ArcOwnershipSSAInput(
            entry,
            mapOf(
                entry to ArcSSABlock(entry, listOf(
                    ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                    ArcSSAOperation.Introduce(other, ArcOwnership.Owned),
                    ArcSSAOperation.Use(other, ArcSSAUseKind.Borrow, mayThrow = true),
                    ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow, mayThrow = true),
                )),
                handler to ArcSSABlock(handler, emptyList()),
            ),
            setOf(exceptional),
        )

        val frontier = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))
            .liveness.lifetimeFrontier(value)
        assertFalse(ArcRCLifetimeFrontier.OnEdge(exceptional) in frontier)
    }

    @Test
    fun consumeMustBeTheTerminalNormalPathUse() {
        listOf(ArcSSAUseKind.Borrow, ArcSSAUseKind.Consume).forEach { laterKind ->
            val value = ArcSSAValue("value.$laterKind")
            val cfg = linear(listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.Use(value, ArcSSAUseKind.Consume),
                ArcSSAOperation.Use(value, laterKind),
            ))

            val issues = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList())).issues
            assertEquals(
                laterKind.toString(),
                setOf(ArcRCIdentityIssueKind.UseAfterConsume),
                issues.mapTo(mutableSetOf()) { it.kind },
            )
        }
    }

    @Test
    fun consumeCannotBeFollowedByBorrowProjection() {
        val value = ArcSSAValue("value")
        val borrowed = ArcSSAValue("borrowed")
        val cfg = linear(listOf(
            ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
            ArcSSAOperation.Use(value, ArcSSAUseKind.Consume),
            ArcSSAOperation.Borrow(
                ArcSSASlot("slot"), ArcSSASlotVersion("slot.0"), value, borrowed, ArcSSABorrowId("borrow"),
            ),
        ))

        val issues = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList())).issues
        assertEquals(
            setOf(ArcRCIdentityIssueKind.UseAfterConsume),
            issues.mapTo(mutableSetOf()) { it.kind },
        )
    }

    @Test
    fun throwingConsumeKillsNormalPathButPreservesUnwindPath() {
        val value = ArcSSAValue("value")
        val normal = ArcBlockId("normal")
        val handler = ArcBlockId("handler")
        val exceptional = ArcSSAEdge(entry, handler, ArcSSAEdgeKind.Exceptional)
        val cfg = ArcOwnershipSSAInput(entry, linkedMapOf(
            entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.Use(value, ArcSSAUseKind.Consume, mayThrow = true),
            )),
            normal to ArcSSABlock(normal, emptyList()),
            handler to ArcSSABlock(handler, listOf(ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow))),
        ), setOf(ArcSSAEdge(entry, normal), exceptional))

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))

        assertTrue(result.issues.toString(), result.issues.isEmpty())
        assertFalse(result.liveness.isLiveAfter(value, entry, 1))
        assertTrue(result.liveness.isLiveBefore(value, handler, 0))
        assertFalse(ArcRCLifetimeFrontier.OnEdge(exceptional) in result.liveness.lifetimeFrontier(value))
    }

    @Test
    fun throwingConsumeReleasesOnDeadUnwindEdge() {
        val value = ArcSSAValue("value")
        val normal = ArcBlockId("normal")
        val handler = ArcBlockId("handler")
        val exceptional = ArcSSAEdge(entry, handler, ArcSSAEdgeKind.Exceptional)
        val cfg = ArcOwnershipSSAInput(entry, linkedMapOf(
            entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.Use(value, ArcSSAUseKind.Consume, mayThrow = true),
            )),
            normal to ArcSSABlock(normal, emptyList()),
            handler to ArcSSABlock(handler, emptyList()),
        ), setOf(ArcSSAEdge(entry, normal), exceptional))

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))

        assertTrue(result.issues.toString(), result.issues.isEmpty())
        assertTrue(ArcRCLifetimeFrontier.OnEdge(exceptional) in result.liveness.lifetimeFrontier(value))
    }

    @Test
    fun throwingConsumeWithoutModeledUnwindEdgeFailsClosed() {
        val value = ArcSSAValue("value")
        val cfg = linear(listOf(
            ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
            ArcSSAOperation.Use(value, ArcSSAUseKind.Consume, mayThrow = true),
        ))

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))

        assertEquals(
            setOf(ArcRCIdentityIssueKind.UnmodeledExceptionalConsume),
            result.issues.mapTo(mutableSetOf()) { it.kind },
        )
    }

    @Test
    fun unrelatedThrowGetsExceptionalEdgeFrontierWhenValueLivesNormally() {
        val value = ArcSSAValue("value")
        val other = ArcSSAValue("other")
        val normal = ArcBlockId("normal")
        val handler = ArcBlockId("handler")
        val exceptional = ArcSSAEdge(entry, handler, ArcSSAEdgeKind.Exceptional)
        val cfg = ArcOwnershipSSAInput(entry, linkedMapOf(
            entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.Introduce(other, ArcOwnership.Owned),
                ArcSSAOperation.Use(other, ArcSSAUseKind.Borrow, mayThrow = true),
            )),
            normal to ArcSSABlock(normal, listOf(ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow))),
            handler to ArcSSABlock(handler, emptyList()),
        ), setOf(ArcSSAEdge(entry, normal), exceptional))

        val frontier = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))
            .liveness.lifetimeFrontier(value)
        assertTrue(ArcRCLifetimeFrontier.OnEdge(exceptional) in frontier)
    }

    @Test
    fun asymmetricNormalSuccessorsGetPerEdgeFrontier() {
        val value = ArcSSAValue("value")
        val live = ArcBlockId("live")
        val dead = ArcBlockId("dead")
        val deadEdge = ArcSSAEdge(entry, dead)
        val cfg = ArcOwnershipSSAInput(entry, linkedMapOf(
            entry to ArcSSABlock(entry, listOf(ArcSSAOperation.Introduce(value, ArcOwnership.Owned))),
            live to ArcSSABlock(live, listOf(ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow))),
            dead to ArcSSABlock(dead, emptyList()),
        ), setOf(ArcSSAEdge(entry, live), deadEdge))

        val frontier = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))
            .liveness.lifetimeFrontier(value)
        assertTrue(ArcRCLifetimeFrontier.OnEdge(deadEdge) in frontier)
    }

    @Test
    fun ownerLifetimeExtendsThroughAnchoredProjectionLastUse() {
        val owner = ArcSSAValue("owner")
        val source = ArcSSAValue("source")
        val other = ArcSSAValue("other")
        val projection = ArcSSAValue("projection")
        val cfg = linear(listOf(
            ArcSSAOperation.Introduce(owner, ArcOwnership.Owned),
            ArcSSAOperation.Introduce(source, ArcOwnership.Owned),
            ArcSSAOperation.Introduce(other, ArcOwnership.Owned),
            ArcSSAOperation.Reborrow(source, projection, setOf(owner)),
            ArcSSAOperation.Use(owner, ArcSSAUseKind.Borrow),
            ArcSSAOperation.Use(projection, ArcSSAUseKind.Borrow),
            ArcSSAOperation.Use(other, ArcSSAUseKind.Borrow),
        ))

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))
        val frontier = result.liveness.lifetimeFrontier(owner)

        assertTrue(ArcRCLifetimeFrontier.AfterOperation(ArcRCPosition(entry, 5)) in frontier)
        assertFalse(ArcRCLifetimeFrontier.AfterOperation(ArcRCPosition(entry, 4)) in frontier)
        assertTrue(result.canMoveWithinBlock(owner, entry, 4, 5))
        assertFalse(result.canMoveWithinBlock(owner, entry, 4, 6))
    }

    @Test
    fun ownerLifetimeFollowsAnchorDependenciesTransitively() {
        val owner = ArcSSAValue("owner")
        val firstSource = ArcSSAValue("firstSource")
        val secondSource = ArcSSAValue("secondSource")
        val firstProjection = ArcSSAValue("firstProjection")
        val secondProjection = ArcSSAValue("secondProjection")
        val cfg = linear(listOf(
            ArcSSAOperation.Introduce(owner, ArcOwnership.Owned),
            ArcSSAOperation.Introduce(firstSource, ArcOwnership.Owned),
            ArcSSAOperation.Introduce(secondSource, ArcOwnership.Owned),
            ArcSSAOperation.Reborrow(firstSource, firstProjection, setOf(owner)),
            ArcSSAOperation.Reborrow(secondSource, secondProjection, setOf(firstProjection)),
            ArcSSAOperation.Use(secondProjection, ArcSSAUseKind.Borrow),
        ))

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))

        assertTrue(
            ArcRCLifetimeFrontier.AfterOperation(ArcRCPosition(entry, 5)) in
                    result.liveness.lifetimeFrontier(owner)
        )
    }

    @Test
    fun distinctIntroducerDoesNotEndQueriedOwnerBeforeAnchoredProjection() {
        val owner = ArcSSAValue("owner")
        val source = ArcSSAValue("source")
        val projection = ArcSSAValue("projection")
        val cfg = linear(listOf(
            ArcSSAOperation.Introduce(owner, ArcOwnership.Owned),
            ArcSSAOperation.Use(owner, ArcSSAUseKind.Borrow),
            ArcSSAOperation.Introduce(source, ArcOwnership.Owned),
            ArcSSAOperation.Reborrow(source, projection, setOf(owner)),
            ArcSSAOperation.Use(owner, ArcSSAUseKind.Borrow),
            ArcSSAOperation.Use(projection, ArcSSAUseKind.Borrow),
        ))

        val frontier = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))
            .liveness.lifetimeFrontier(owner)

        assertTrue(ArcRCLifetimeFrontier.AfterOperation(ArcRCPosition(entry, 5)) in frontier)
        assertFalse(ArcRCLifetimeFrontier.AfterOperation(ArcRCPosition(entry, 1)) in frontier)
        assertFalse(ArcRCLifetimeFrontier.AfterOperation(ArcRCPosition(entry, 4)) in frontier)
    }

    @Test
    fun distinctAnchorFamilyConsumeIsNotQueriedOwnerConsume() {
        val owner = ArcSSAValue("owner")
        val source = ArcSSAValue("source")
        val projection = ArcSSAValue("projection")
        val cfg = linear(listOf(
            ArcSSAOperation.Introduce(owner, ArcOwnership.Owned),
            ArcSSAOperation.Introduce(source, ArcOwnership.Owned),
            ArcSSAOperation.Reborrow(source, projection, setOf(owner)),
            ArcSSAOperation.Use(projection, ArcSSAUseKind.Borrow),
            ArcSSAOperation.Use(source, ArcSSAUseKind.Consume),
        ))

        val frontier = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))
            .liveness.lifetimeFrontier(owner)

        assertFalse(ArcRCLifetimeFrontier.ConsumedAt(ArcRCPosition(entry, 4)) in frontier)
        assertTrue(ArcRCLifetimeFrontier.AfterOperation(ArcRCPosition(entry, 4)) in frontier)
    }

    @Test
    fun destroyingAnchoredChildEndsOwnerAfterChildDestroy() {
        val owner = ArcSSAValue("owner")
        val child = ArcSSAValue("child")
        val slot = ArcSSASlot("child")
        val version = ArcSSASlotVersion("child.0")
        val cfg = linear(listOf(
            ArcSSAOperation.Introduce(owner, ArcOwnership.Owned),
            ArcSSAOperation.Introduce(child, ArcOwnership.Owned, setOf(owner)),
            ArcSSAOperation.InitializeOwned(slot, version, child),
            ArcSSAOperation.DestroyOwned(slot, version, child),
        ))

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))
        val frontier = result.liveness.lifetimeFrontier(owner)
        val destroyBarrier = result.barriers.single()

        assertTrue(ArcRCLifetimeFrontier.AfterOperation(ArcRCPosition(entry, 3)) in frontier)
        assertFalse(ArcRCLifetimeFrontier.BeforeBarrier(destroyBarrier) in frontier)
    }

    @Test
    fun terminalThrowingBorrowEndsNormallyAfterOperationAndCleansExceptionalEdge() {
        val value = ArcSSAValue("value")
        val handler = ArcBlockId("handler")
        val exceptional = ArcSSAEdge(entry, handler, ArcSSAEdgeKind.Exceptional)
        val cfg = ArcOwnershipSSAInput(entry, linkedMapOf(
            entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow, mayThrow = true),
            )),
            handler to ArcSSABlock(handler, emptyList()),
        ), setOf(exceptional))

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))
        val frontier = result.liveness.lifetimeFrontier(value)

        assertTrue(ArcRCLifetimeFrontier.AfterOperation(ArcRCPosition(entry, 1)) in frontier)
        assertTrue(ArcRCLifetimeFrontier.OnEdge(exceptional) in frontier)
        assertFalse(ArcRCLifetimeFrontier.BeforeBarrier(result.barriers.single()) in frontier)
    }

    @Test
    fun escapeAndUnknownConsumeDisableLocalLifetimeFrontier() {
        listOf(
            ArcSSAUseKind.Escape to ArcRCIdentityIssueKind.UnsupportedEscape,
            ArcSSAUseKind.UnknownConsume to ArcRCIdentityIssueKind.UnsupportedUnknownConsume,
        ).forEach { (useKind, issueKind) ->
            val value = ArcSSAValue("value.$useKind")
            val cfg = linear(listOf(
                ArcSSAOperation.Introduce(value, ArcOwnership.Owned),
                ArcSSAOperation.Use(value, useKind),
            ))

            val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))

            assertEquals(useKind.toString(), setOf(issueKind), result.issues.mapTo(mutableSetOf()) { it.kind })
            assertTrue(useKind.toString(), result.liveness.lifetimeFrontier(value).isEmpty())
        }
    }

    @Test
    fun slotBorrowKeepsCanonicalIdentityAndRecordsItsAnchor() {
        val root = ArcSSAValue("root")
        val borrowed = ArcSSAValue("borrowed")
        val slot = ArcSSASlot("slot")
        val version = ArcSSASlotVersion("slot.0")
        val borrow = ArcSSABorrowId("borrow.0")
        val cfg = linear(listOf(
            ArcSSAOperation.Introduce(root, ArcOwnership.Owned),
            ArcSSAOperation.InitializeOwned(slot, version, root),
            ArcSSAOperation.Borrow(slot, version, root, borrowed, borrow),
            ArcSSAOperation.Use(borrowed, ArcSSAUseKind.Borrow),
            ArcSSAOperation.EndBorrow(borrow),
            ArcSSAOperation.DestroyOwned(slot, version, root),
        ))

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))

        assertTrue(result.issues.isEmpty())
        assertEquals(root, result.identity(borrowed)?.singleRoot)
        assertEquals(setOf(root), result.identity(borrowed)?.anchorRoots)
    }

    @Test
    fun destroyIsAConsumeBarrierButMoveIsNot() {
        val root = ArcSSAValue("root")
        val slot = ArcSSASlot("slot")
        val moved = ArcSSASlot("moved")
        val first = ArcSSASlotVersion("slot.0")
        val second = ArcSSASlotVersion("moved.0")
        val cfg = linear(listOf(
            ArcSSAOperation.Introduce(root, ArcOwnership.Owned),
            ArcSSAOperation.InitializeOwned(slot, first, root),
            ArcSSAOperation.MoveOwned(slot, first, moved, second, root),
            ArcSSAOperation.DestroyOwned(moved, second, root),
        ))

        val result = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))

        assertEquals(listOf(ArcRCBarrierKind.Consume), result.barriers.map { it.kind })
        assertTrue(ArcRCLifetimeFrontier.BeforeBarrier(result.barriers.single()) in result.liveness.lifetimeFrontier(root))
    }

    private fun linear(operations: List<ArcSSAOperation>): ArcOwnershipSSAInput = ArcOwnershipSSAInput(
        entry,
        mapOf(entry to ArcSSABlock(entry, operations)),
        emptySet(),
    )

    private fun diamond(
        leftOperations: List<ArcSSAOperation>,
        rightOperations: List<ArcSSAOperation>,
        mergeOperations: List<ArcSSAOperation>,
    ): ArcOwnershipSSAInput = ArcOwnershipSSAInput(
        entry,
        linkedMapOf(
            entry to ArcSSABlock(entry, emptyList()),
            leftBlock to ArcSSABlock(leftBlock, leftOperations),
            rightBlock to ArcSSABlock(rightBlock, rightOperations),
            merge to ArcSSABlock(merge, mergeOperations),
            exit to ArcSSABlock(exit, emptyList()),
        ),
        setOf(
            ArcSSAEdge(entry, leftBlock),
            ArcSSAEdge(entry, rightBlock),
            ArcSSAEdge(leftBlock, merge),
            ArcSSAEdge(rightBlock, merge),
            ArcSSAEdge(merge, exit),
        ),
    )
}
