/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcMatchingSetClosureTest {
    private val entry = ArcBlockId("entry")
    private val left = ArcBlockId("left")
    private val right = ArcBlockId("right")
    private val merge = ArcBlockId("merge")
    private val exit = ArcBlockId("exit")

    @Test
    fun oneIncrementAndTwoPathDisjointDecrementsReachMutualClosure() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val cfg = cfg(
            blocks = listOf(
                block(entry, introduce(anchor), forward(anchor, copy)),
                block(left, borrow(copy)),
                block(right, borrow(copy)),
                block(merge, borrow(anchor)),
            ),
            edges = setOf(edge(entry, left), edge(entry, right), edge(left, merge), edge(right, merge)),
        )

        val result = analyze(cfg, listOf(
            increment("inc", entry, 1, copy, anchor, "left.dec", "right.dec"),
            decrement("left.dec", left, 0, copy),
            decrement("right.dec", right, 0, copy),
        ))

        assertTrue(result.rejected.isEmpty())
        assertEquals(setOf(id("inc")), result.accepted.single().increments)
        assertEquals(setOf(id("left.dec"), id("right.dec")), result.accepted.single().decrements)
    }

    @Test
    fun twoPathDisjointIncrementsAndOneDecrementReachMutualClosure() {
        val anchor = ArcSSAValue("anchor")
        val leftCopy = ArcSSAValue("leftCopy")
        val rightCopy = ArcSSAValue("rightCopy")
        val joined = ArcSSAValue("joined")
        val cfg = cfg(
            blocks = listOf(
                block(entry, introduce(anchor)),
                block(left, forward(anchor, leftCopy)),
                block(right, forward(anchor, rightCopy)),
                block(
                    merge,
                    ArcSSAOperation.Join(joined, linkedMapOf(left to leftCopy, right to rightCopy)),
                    borrow(joined),
                    borrow(anchor),
                ),
            ),
            edges = setOf(edge(entry, left), edge(entry, right), edge(left, merge), edge(right, merge)),
        )

        val result = analyze(cfg, listOf(
            increment("left.inc", left, 0, leftCopy, anchor, "dec"),
            increment("right.inc", right, 0, rightCopy, anchor, "dec"),
            decrement("dec", merge, 1, joined),
        ))

        assertTrue(result.rejected.isEmpty())
        assertEquals(setOf(id("left.inc"), id("right.inc")), result.accepted.single().increments)
        assertEquals(setOf(id("dec")), result.accepted.single().decrements)
    }

    @Test
    fun nestedPairsAreExposedInTwoFixedPointRounds() {
        val anchor = ArcSSAValue("anchor")
        val outer = ArcSSAValue("outer")
        val inner = ArcSSAValue("inner")
        val cfg = cfg(
            listOf(block(
                entry,
                introduce(anchor),
                forward(anchor, outer),
                forward(outer, inner),
                borrow(inner),
                borrow(outer),
                borrow(anchor),
            )),
        )

        val result = analyze(cfg, listOf(
            increment("outer.inc", entry, 1, outer, anchor, "outer.dec"),
            increment("inner.inc", entry, 2, inner, anchor, "inner.dec"),
            decrement("inner.dec", entry, 3, inner),
            decrement("outer.dec", entry, 4, outer),
        ))

        assertTrue(result.rejected.isEmpty())
        assertEquals(2, result.accepted.size)
        assertEquals(setOf(id("inner.inc"), id("inner.dec")), result.accepted[0].increments + result.accepted[0].decrements)
        assertEquals(0, result.accepted[0].round)
        assertEquals(setOf(id("outer.inc"), id("outer.dec")), result.accepted[1].increments + result.accepted[1].decrements)
        assertEquals(1, result.accepted[1].round)
    }

    @Test
    fun anchoredZeroDeltaReducibleLoopIsAccepted() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val loop = ArcBlockId("loop")
        val cfg = cfg(
            blocks = listOf(
                block(entry, introduce(anchor)),
                block(loop, forward(anchor, copy), borrow(copy), borrow(anchor)),
                block(exit),
            ),
            edges = setOf(edge(entry, loop), edge(loop, loop), edge(loop, exit)),
        )

        val result = analyze(cfg, listOf(
            increment("loop.inc", loop, 0, copy, anchor, "loop.dec"),
            decrement("loop.dec", loop, 1, copy),
        ))

        assertTrue(result.rejected.isEmpty())
        assertEquals(1, result.accepted.size)
    }

    @Test
    fun loopWithTwoEntryHeadersIsRejectedAsIrreducible() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val firstHeader = ArcBlockId("firstHeader")
        val secondHeader = ArcBlockId("secondHeader")
        val cfg = cfg(
            blocks = listOf(
                block(entry, introduce(anchor)),
                block(firstHeader, forward(anchor, copy), borrow(copy), borrow(anchor)),
                block(secondHeader),
                block(exit),
            ),
            edges = setOf(
                edge(entry, firstHeader),
                edge(entry, secondHeader),
                edge(firstHeader, secondHeader),
                edge(secondHeader, firstHeader),
                edge(firstHeader, exit),
            ),
        )

        val result = analyze(cfg, listOf(
            increment("inc", firstHeader, 0, copy, anchor, "dec"),
            decrement("dec", firstHeader, 1, copy),
        ))

        assertEquals(ArcMatchingSetRejectionReason.NonReducibleLoop, result.rejected.single().reason)
    }

    @Test
    fun loopWithNonZeroBackedgeDeltaIsRejected() {
        val anchor = ArcSSAValue("anchor")
        val first = ArcSSAValue("first")
        val second = ArcSSAValue("second")
        val loop = ArcBlockId("loop")
        val cfg = cfg(
            blocks = listOf(
                block(entry, introduce(anchor)),
                block(loop, forward(anchor, first), forward(first, second), borrow(second), borrow(anchor)),
                block(exit),
            ),
            edges = setOf(edge(entry, loop), edge(loop, loop), edge(loop, exit)),
        )

        val result = analyze(cfg, listOf(
            increment("first.inc", loop, 0, first, anchor, "dec"),
            increment("second.inc", loop, 1, second, anchor, "dec"),
            decrement("dec", loop, 2, second),
        ))

        assertTrue(result.accepted.none { id("first.inc") in it.increments })
        val firstRejection = result.rejected.single { it.increment?.name == "first.inc" }
        assertEquals(ArcMatchingSetRejectionReason.NonZeroPathDelta, firstRejection.reason)
    }

    @Test
    fun pathWhichSkipsDecrementIsRejected() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val cfg = cfg(
            blocks = listOf(
                block(entry, introduce(anchor), forward(anchor, copy)),
                block(left, borrow(copy)),
                block(right),
                block(merge, borrow(anchor)),
            ),
            edges = setOf(edge(entry, left), edge(entry, right), edge(left, merge), edge(right, merge)),
        )

        val result = analyze(cfg, listOf(
            increment("inc", entry, 1, copy, anchor, "dec"),
            decrement("dec", left, 0, copy),
        ))

        assertEquals(ArcMatchingSetRejectionReason.PathWithoutMatch, result.rejected.single().reason)
    }

    @Test
    fun cycleThatCanAvoidDecrementIsRejected() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val loopHeader = ArcBlockId("loopHeader")
        val loopLatch = ArcBlockId("loopLatch")
        val cfg = cfg(
            blocks = listOf(
                block(entry, introduce(anchor), forward(anchor, copy)),
                block(loopHeader),
                block(loopLatch),
                block(exit, borrow(copy), borrow(anchor)),
            ),
            edges = setOf(
                edge(entry, loopHeader),
                edge(loopHeader, loopLatch),
                edge(loopHeader, exit),
                edge(loopLatch, loopHeader),
            ),
        )

        val result = analyze(cfg, listOf(
            increment("inc", entry, 1, copy, anchor, "dec"),
            decrement("dec", exit, 0, copy),
        ))

        assertEquals(ArcMatchingSetRejectionReason.CycleWithoutMatch, result.rejected.single().reason)
    }

    @Test
    fun deinitializationBarrierRejectsSequence() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val cfg = cfg(listOf(block(
            entry,
            introduce(anchor),
            forward(anchor, copy),
            ArcSSAOperation.DeinitBarrier(),
            borrow(copy),
            borrow(anchor),
        )))

        val result = analyze(cfg, listOf(
            increment("inc", entry, 1, copy, anchor, "dec"),
            decrement("dec", entry, 3, copy),
        ))

        assertEquals(ArcMatchingSetRejectionReason.OwnershipBarrier, result.rejected.single().reason)
    }

    @Test
    fun unrelatedRcEventIsAnAliasingBarrierWithoutDisjointnessProof() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val otherAnchor = ArcSSAValue("otherAnchor")
        val other = ArcSSAValue("other")
        val cfg = cfg(listOf(block(
            entry,
            introduce(anchor),
            introduce(otherAnchor),
            forward(anchor, copy),
            forward(otherAnchor, other),
            borrow(copy),
            borrow(anchor),
            borrow(otherAnchor),
        )))

        val result = analyze(cfg, listOf(
            increment("inc", entry, 2, copy, anchor, "dec"),
            increment("other.inc", entry, 3, other, otherAnchor),
            decrement("dec", entry, 4, copy),
        ))

        val primaryRejection = result.rejected.single { it.increment?.name == "inc" }
        assertEquals(ArcMatchingSetRejectionReason.OwnershipBarrier, primaryRejection.reason)
    }

    @Test
    fun missingOrSelfAnchorIsRejected() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val cfg = cfg(listOf(block(entry, introduce(anchor), forward(anchor, copy), borrow(copy), borrow(anchor))))

        val missing = analyze(cfg, listOf(
            ArcMatchingSetEvent(id("inc"), ArcRCPosition(entry, 1), ArcMatchingSetEventKind.Increment, copy),
            decrement("dec", entry, 2, copy),
        ))
        assertEquals(ArcMatchingSetRejectionReason.MissingLifetimeWitness, missing.rejected.single().reason)

        val self = analyze(cfg, listOf(
            increment("inc", entry, 1, copy, copy, "dec"),
            decrement("dec", entry, 2, copy),
        ))
        assertEquals(ArcMatchingSetRejectionReason.MissingLifetimeWitness, self.rejected.single().reason)
    }

    @Test
    fun emptyProvenanceSetsNeverAuthenticateRcIdentity() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val cfg = cfg(listOf(block(entry, introduce(anchor), forward(anchor, copy), borrow(copy), borrow(anchor))))
        val identityMap = mapOf(
            anchor to ArcRCIdentity(anchor, emptySet(), emptySet(), null),
            copy to ArcRCIdentity(copy, emptySet(), emptySet(), null),
        )
        val forgedRootlessIdentities = ArcRCIdentityResult(
            identityMap,
            emptyList(),
            ArcRCPrunedLiveness(cfg, identityMap, emptyList()),
            emptyList(),
        )

        val result = ArcMatchingSetClosure.analyze(ArcMatchingSetInput(
            cfg,
            forgedRootlessIdentities,
            ArcSSASlotFlowAnalysis.analyze(cfg, emptyList()),
            listOf(
                increment("inc", entry, 1, copy, anchor, "dec"),
                decrement("dec", entry, 2, copy),
            ),
        ))

        assertTrue(result.accepted.isEmpty())
        assertEquals(ArcMatchingSetRejectionReason.OwnershipBarrier, result.rejected.single().reason)
    }

    @Test
    fun duplicateSsaDefinitionsAreRejectedEvenBeforeIdentityConsumption() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val cfg = cfg(listOf(block(
            entry,
            introduce(anchor),
            introduce(anchor),
            forward(anchor, copy),
            borrow(copy),
            borrow(anchor),
        )))
        val identities = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))

        val result = ArcMatchingSetClosure.analyze(ArcMatchingSetInput(
            cfg,
            identities,
            ArcSSASlotFlowAnalysis.analyze(cfg, emptyList()),
            listOf(
                increment("inc", entry, 2, copy, anchor, "dec"),
                decrement("dec", entry, 3, copy),
            ),
        ))

        assertTrue(result.accepted.isEmpty())
        assertTrue(result.rejected.any { rejection ->
            when (rejection.reason) {
                ArcMatchingSetRejectionReason.MalformedInput -> true
                else -> false
            }
        })
    }

    @Test
    fun witnessMustExplicitlyCoverEveryMatchedDecrement() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val cfg = cfg(listOf(block(entry, introduce(anchor), forward(anchor, copy), borrow(copy))))

        val result = analyze(cfg, listOf(
            increment("inc", entry, 1, copy, anchor),
            decrement("dec", entry, 2, copy),
        ))

        assertEquals(ArcMatchingSetRejectionReason.WitnessDoesNotCoverDecrement, result.rejected.single().reason)
    }

    @Test
    fun liveAliasCannotSubstituteForDestroyedExactOwningSlotToken() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val alias = ArcSSAValue("alias")
        val sourceSlot = ArcSSASlot("source")
        val destinationSlot = ArcSSASlot("destination")
        val sourceVersion = ArcSSASlotVersion("source.v1")
        val destinationVersion = ArcSSASlotVersion("destination.v1")
        val cfg = cfg(listOf(block(
            entry,
            introduceOwned(anchor),
            ArcSSAOperation.InitializeOwned(sourceSlot, sourceVersion, anchor),
            forward(anchor, copy),
            forward(copy, alias),
            ArcSSAOperation.MoveOwned(
                sourceSlot,
                sourceVersion,
                destinationSlot,
                destinationVersion,
                anchor,
            ),
            borrow(copy),
            borrow(alias),
            ArcSSAOperation.DestroyOwned(destinationSlot, destinationVersion, anchor),
        )))
        val identities = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))
        val slotFlow = ArcSSASlotFlowAnalysis.analyze(cfg, emptyList())
        assertTrue(slotFlow.verified)

        val result = ArcMatchingSetClosure.analyze(ArcMatchingSetInput(
            cfg,
            identities,
            slotFlow,
            listOf(
                ArcMatchingSetEvent(
                    id("inc"),
                    ArcRCPosition(entry, 2),
                    ArcMatchingSetEventKind.Increment,
                    copy,
                    ArcMatchingSetLifetimeWitness.OwningSlot(
                        anchor,
                        sourceSlot,
                        sourceVersion,
                        setOf(id("dec")),
                    ),
                ),
                decrement("dec", entry, 5, copy),
            ),
        ))

        assertTrue(result.accepted.isEmpty())
        assertEquals(ArcMatchingSetRejectionReason.WitnessNotLiveThroughDecrement, result.rejected.single().reason)
        // The family remains live through alias, demonstrating why family liveness cannot authorize deletion.
        assertTrue(identities.liveness.isLiveAfter(anchor, entry, 5))
    }

    @Test
    fun borrowWitnessEndingOnOnlyOnePathCannotCoverMergedDecrement() {
        val owner = ArcSSAValue("owner")
        val anchor = ArcSSAValue("borrowedAnchor")
        val copy = ArcSSAValue("copy")
        val slot = ArcSSASlot("owner")
        val version = ArcSSASlotVersion("owner.v1")
        val borrowId = ArcSSABorrowId("scope.borrow")
        val cfg = cfg(
            blocks = listOf(
                block(
                    entry,
                    introduceOwned(owner),
                    ArcSSAOperation.InitializeOwned(slot, version, owner),
                    ArcSSAOperation.Borrow(slot, version, owner, anchor, borrowId),
                    forward(anchor, copy),
                ),
                block(left, ArcSSAOperation.EndBorrow(borrowId)),
                block(right),
                block(merge, borrow(copy)),
            ),
            edges = setOf(edge(entry, left), edge(entry, right), edge(left, merge), edge(right, merge)),
        )
        val identities = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))
        val slotFlow = ArcSSASlotFlowAnalysis.analyze(cfg, emptyList())

        val result = ArcMatchingSetClosure.analyze(ArcMatchingSetInput(
            cfg,
            identities,
            slotFlow,
            listOf(
                ArcMatchingSetEvent(
                    id("inc"),
                    ArcRCPosition(entry, 3),
                    ArcMatchingSetEventKind.Increment,
                    copy,
                    ArcMatchingSetLifetimeWitness.Guaranteed(
                        anchor,
                        ArcMatchingSetGuaranteedToken.Borrow(borrowId),
                        setOf(id("dec")),
                    ),
                ),
                decrement("dec", merge, 0, copy),
            ),
        ))

        assertTrue(result.accepted.isEmpty())
        assertEquals(ArcMatchingSetRejectionReason.WitnessNotLiveThroughDecrement, result.rejected.single().reason)
    }

    @Test
    fun owningSlotDestroyStrictlyAfterCoveredDecrementIsAccepted() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val slot = ArcSSASlot("source")
        val version = ArcSSASlotVersion("source.v1")
        val cfg = cfg(listOf(block(
            entry,
            introduceOwned(anchor),
            ArcSSAOperation.InitializeOwned(slot, version, anchor),
            forward(anchor, copy),
            borrow(copy),
            ArcSSAOperation.DestroyOwned(slot, version, anchor),
        )))
        val identities = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))
        val slotFlow = ArcSSASlotFlowAnalysis.analyze(cfg, emptyList())
        assertTrue(slotFlow.verified)

        val result = ArcMatchingSetClosure.analyze(ArcMatchingSetInput(
            cfg,
            identities,
            slotFlow,
            listOf(
                ArcMatchingSetEvent(
                    id("inc"),
                    ArcRCPosition(entry, 2),
                    ArcMatchingSetEventKind.Increment,
                    copy,
                    ArcMatchingSetLifetimeWitness.OwningSlot(anchor, slot, version, setOf(id("dec"))),
                ),
                decrement("dec", entry, 3, copy),
            ),
        ))

        assertTrue(result.rejected.isEmpty())
        assertEquals(1, result.accepted.size)
    }

    @Test
    fun blankAbiScopeCannotAuthenticateGuaranteedWitness() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val cfg = cfg(listOf(block(entry, introduce(anchor), forward(anchor, copy), borrow(copy))))
        val identities = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))
        val slotFlow = ArcSSASlotFlowAnalysis.analyze(cfg, emptyList())

        val result = ArcMatchingSetClosure.analyze(ArcMatchingSetInput(
            cfg,
            identities,
            slotFlow,
            listOf(
                ArcMatchingSetEvent(
                    id("inc"),
                    ArcRCPosition(entry, 1),
                    ArcMatchingSetEventKind.Increment,
                    copy,
                    ArcMatchingSetLifetimeWitness.Guaranteed(
                        anchor,
                        ArcMatchingSetGuaranteedToken.AbiScope(ArcMatchingSetGuaranteedScopeId("")),
                        setOf(id("dec")),
                    ),
                ),
                decrement("dec", entry, 2, copy),
            ),
        ))

        assertTrue(result.accepted.isEmpty())
        assertEquals(ArcMatchingSetRejectionReason.WitnessKindMismatch, result.rejected.single().reason)
    }

    @Test
    fun exactExceptionalCleanupOnNormalAndExceptionalPathsIsAccepted() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val handler = ArcBlockId("handler")
        val normalExit = ArcBlockId("normalExit")
        val exceptionalExit = ArcBlockId("exceptionalExit")
        val cfg = cfg(
            blocks = listOf(
                block(entry, introduce(anchor), forward(anchor, copy), ArcSSAOperation.Use(copy, ArcSSAUseKind.Borrow, mayThrow = true), borrow(copy)),
                block(handler, borrow(copy)),
                block(normalExit, borrow(anchor)),
                block(exceptionalExit, borrow(anchor)),
            ),
            edges = setOf(
                edge(entry, normalExit),
                edge(entry, handler, ArcSSAEdgeKind.Exceptional),
                edge(handler, exceptionalExit),
            ),
        )

        val result = analyze(cfg, listOf(
            increment("inc", entry, 1, copy, anchor, "normal.dec", "exception.dec"),
            decrement("normal.dec", entry, 3, copy),
            decrement("exception.dec", handler, 0, copy),
        ))

        assertTrue(result.rejected.isEmpty())
        assertEquals(setOf(id("normal.dec"), id("exception.dec")), result.accepted.single().decrements)
    }

    @Test
    fun throwingCallWithoutExactExceptionalCleanupIsRejected() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val cfg = cfg(listOf(block(
            entry,
            introduce(anchor),
            forward(anchor, copy),
            ArcSSAOperation.Use(copy, ArcSSAUseKind.Borrow, mayThrow = true),
            borrow(copy),
            borrow(anchor),
        )))

        val result = analyze(cfg, listOf(
            increment("inc", entry, 1, copy, anchor, "dec"),
            decrement("dec", entry, 3, copy),
        ))

        assertEquals(ArcMatchingSetRejectionReason.PathWithoutMatch, result.rejected.single().reason)
    }

    @Test
    fun unsplitCriticalExceptionalEdgeIsRejected() {
        val anchor = ArcSSAValue("anchor")
        val copy = ArcSSAValue("copy")
        val handler = ArcBlockId("handler")
        val otherThrower = ArcBlockId("otherThrower")
        val normal = ArcBlockId("normal")
        val cfg = cfg(
            blocks = listOf(
                block(entry, introduce(anchor), forward(anchor, copy), ArcSSAOperation.Use(copy, ArcSSAUseKind.Borrow, mayThrow = true)),
                block(otherThrower, ArcSSAOperation.Use(anchor, ArcSSAUseKind.Borrow, mayThrow = true)),
                block(handler, borrow(copy), borrow(anchor)),
                block(normal, borrow(copy), borrow(anchor)),
            ),
            edges = setOf(
                edge(entry, normal),
                edge(entry, handler, ArcSSAEdgeKind.Exceptional),
                edge(otherThrower, handler, ArcSSAEdgeKind.Exceptional),
            ),
        )

        val result = analyze(cfg, listOf(
            increment("inc", entry, 1, copy, anchor, "normal.dec", "exception.dec"),
            decrement("normal.dec", normal, 0, copy),
            decrement("exception.dec", handler, 0, copy),
        ))

        assertEquals(ArcMatchingSetRejectionReason.CriticalEdge, result.rejected.single().reason)
    }

    private fun analyze(cfg: ArcOwnershipSSAInput, events: List<ArcMatchingSetEvent>): ArcMatchingSetResult {
        val identities = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(cfg, emptyList()))
        val slotFlow = ArcSSASlotFlowAnalysis.analyze(cfg, emptyList())
        return ArcMatchingSetClosure.analyze(ArcMatchingSetInput(cfg, identities, slotFlow, events))
    }

    private fun cfg(
        blocks: List<ArcSSABlock>,
        edges: Set<ArcSSAEdge> = emptySet(),
    ) = ArcOwnershipSSAInput(entry, blocks.associateByTo(linkedMapOf()) { it.id }, edges)

    private fun block(id: ArcBlockId, vararg operations: ArcSSAOperation) = ArcSSABlock(id, operations.toList())
    private fun edge(from: ArcBlockId, to: ArcBlockId, kind: ArcSSAEdgeKind = ArcSSAEdgeKind.Normal) = ArcSSAEdge(from, to, kind)
    private fun introduce(value: ArcSSAValue) = ArcSSAOperation.Introduce(value, ArcOwnership.Guaranteed)
    private fun introduceOwned(value: ArcSSAValue) = ArcSSAOperation.Introduce(value, ArcOwnership.Owned)
    private fun forward(source: ArcSSAValue, result: ArcSSAValue) = ArcSSAOperation.Forward(source, result)
    private fun borrow(value: ArcSSAValue) = ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow)
    private fun id(name: String) = ArcMatchingSetEventId(name)
    private fun increment(
        name: String,
        block: ArcBlockId,
        index: Int,
        value: ArcSSAValue,
        anchor: ArcSSAValue,
        vararg coveredDecrements: String,
    ) = ArcMatchingSetEvent(
        id(name),
        ArcRCPosition(block, index),
        ArcMatchingSetEventKind.Increment,
        value,
        ArcMatchingSetLifetimeWitness.Guaranteed(
            anchor,
            ArcMatchingSetGuaranteedToken.AbiScope(ArcMatchingSetGuaranteedScopeId("scope:${anchor.name}")),
            coveredDecrements.mapTo(linkedSetOf()) { id(it) },
        ),
    )
    private fun decrement(name: String, block: ArcBlockId, index: Int, value: ArcSSAValue) =
        ArcMatchingSetEvent(id(name), ArcRCPosition(block, index), ArcMatchingSetEventKind.Decrement, value)
}
