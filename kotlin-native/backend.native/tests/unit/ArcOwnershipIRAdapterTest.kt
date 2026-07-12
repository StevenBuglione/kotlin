/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcOwnershipIRAdapterTest {
    private val accepted = ArcCanonicalReferenceJoinEligibility(
        arcEnabled = true,
        optimizationsEnabled = true,
        debugInfoDisabled = true,
        nonSuspendUnitFunction = true,
        immutableStrongReferenceLocal = true,
        strongOwnershipAnnotationsAbsent = true,
        exactTwoArmIf = true,
        simpleCondition = true,
        directOwnedArmProducers = true,
        exactLinearBorrowUse = true,
        noOtherUsesOrSuffixEffects = true,
    )

    @Test
    fun canonicalJoinIsAuthorized() {
        assertTrue(accepted.isAuthorized())
    }

    @Test
    fun everyWideningFailsClosed() {
        assertFalse(accepted.copy(arcEnabled = false).isAuthorized())
        assertFalse(accepted.copy(optimizationsEnabled = false).isAuthorized())
        assertFalse(accepted.copy(debugInfoDisabled = false).isAuthorized())
        assertFalse(accepted.copy(nonSuspendUnitFunction = false).isAuthorized())
        assertFalse(accepted.copy(immutableStrongReferenceLocal = false).isAuthorized())
        assertFalse(accepted.copy(strongOwnershipAnnotationsAbsent = false).isAuthorized())
        assertFalse(accepted.copy(exactTwoArmIf = false).isAuthorized())
        assertFalse(accepted.copy(simpleCondition = false).isAuthorized())
        assertFalse(accepted.copy(directOwnedArmProducers = false).isAuthorized())
        assertFalse(accepted.copy(exactLinearBorrowUse = false).isAuthorized())
        assertFalse(accepted.copy(noOtherUsesOrSuffixEffects = false).isAuthorized())
    }

    @Test
    fun returnedReceiverBorrowRequiresEverySafetyGate() {
        val eligible = ArcReturnedReceiverBorrowEligibility(
            arcEnabled = true,
            optimizationsEnabled = true,
            debugInfoDisabled = true,
            exactDispatchReceiverUse = true,
            directNonExternalNonVirtualCall = true,
            nonSuspendReferenceResult = true,
            everyNormalReturnIsReceiver = true,
            noTryOrNestedFunctionAmbiguity = true,
        )
        assertTrue(eligible.isAuthorized())
        assertFalse(eligible.copy(arcEnabled = false).isAuthorized())
        assertFalse(eligible.copy(optimizationsEnabled = false).isAuthorized())
        assertFalse(eligible.copy(debugInfoDisabled = false).isAuthorized())
        assertFalse(eligible.copy(exactDispatchReceiverUse = false).isAuthorized())
        assertFalse(eligible.copy(directNonExternalNonVirtualCall = false).isAuthorized())
        assertFalse(eligible.copy(nonSuspendReferenceResult = false).isAuthorized())
        assertFalse(eligible.copy(everyNormalReturnIsReceiver = false).isAuthorized())
        assertFalse(eligible.copy(noTryOrNestedFunctionAmbiguity = false).isAuthorized())
    }

    @Test
    fun returnedReceiverSlotReuseRequiresUniquePointerIdenticalCurrentBlockFact() {
        val eligible = ArcReturnedReceiverSlotReuseEligibility(
            arcEnabled = true,
            optimizationsEnabled = true,
            debugInfoDisabled = true,
            exactCallIdentitySelected = true,
            noRequestedResultSlot = true,
            uniqueCurrentBlockOwningSlot = true,
            pointerIdenticalReceiverFact = true,
        )
        assertTrue(eligible.isAuthorized())
        assertFalse(eligible.copy(arcEnabled = false).isAuthorized())
        assertFalse(eligible.copy(optimizationsEnabled = false).isAuthorized())
        assertFalse(eligible.copy(debugInfoDisabled = false).isAuthorized())
        assertFalse(eligible.copy(exactCallIdentitySelected = false).isAuthorized())
        assertFalse(eligible.copy(noRequestedResultSlot = false).isAuthorized())
        assertFalse(eligible.copy(uniqueCurrentBlockOwningSlot = false).isAuthorized())
        assertFalse(eligible.copy(pointerIdenticalReceiverFact = false).isAuthorized())
    }

    @Test
    fun discardedReturnedReceiverRequiresEverySemanticGate() {
        val eligible = ArcDiscardedReturnedReceiverEligibility(
            arcEnabled = true,
            optimizationsEnabled = true,
            debugInfoDisabled = true,
            exactDiscardedBlockStatement = true,
            exactStableStrongReceiver = true,
            directFinalNonExternalCall = true,
            nonSuspendReferenceResult = true,
            everyNormalReturnIsReceiver = true,
            noTrySuspendBranchOrNestedFunctionAmbiguity = true,
            weakOrUnownedShapeAbsent = true,
        )
        assertTrue(eligible.isAuthorized())
        assertFalse(eligible.copy(arcEnabled = false).isAuthorized())
        assertFalse(eligible.copy(optimizationsEnabled = false).isAuthorized())
        assertFalse(eligible.copy(debugInfoDisabled = false).isAuthorized())
        assertFalse(eligible.copy(exactDiscardedBlockStatement = false).isAuthorized())
        assertFalse(eligible.copy(exactStableStrongReceiver = false).isAuthorized())
        assertFalse(eligible.copy(directFinalNonExternalCall = false).isAuthorized())
        assertFalse(eligible.copy(nonSuspendReferenceResult = false).isAuthorized())
        assertFalse(eligible.copy(everyNormalReturnIsReceiver = false).isAuthorized())
        assertFalse(eligible.copy(noTrySuspendBranchOrNestedFunctionAmbiguity = false).isAuthorized())
        assertFalse(eligible.copy(weakOrUnownedShapeAbsent = false).isAuthorized())
    }

    @Test
    fun discardedResultContextsFailClosedInTheSemanticModel() {
        val base = ArcDiscardedReturnedReceiverEligibility(
            arcEnabled = true,
            optimizationsEnabled = true,
            debugInfoDisabled = true,
            exactDiscardedBlockStatement = true,
            exactStableStrongReceiver = true,
            directFinalNonExternalCall = true,
            nonSuspendReferenceResult = true,
            everyNormalReturnIsReceiver = true,
            noTrySuspendBranchOrNestedFunctionAmbiguity = true,
            weakOrUnownedShapeAbsent = true,
        )
        // Return, initializer, argument, branch value, and later-read shapes all fail the same
        // exact-discarded-statement fact; the selector must never infer non-use from result type.
        repeat(5) { assertFalse(base.copy(exactDiscardedBlockStatement = false).isAuthorized()) }
        // Try, suspension, and nested-function contexts invalidate the boundary proof.
        repeat(3) {
            assertFalse(base.copy(noTrySuspendBranchOrNestedFunctionAmbiguity = false).isAuthorized())
        }
        // Virtual/external calls share the direct-final gate; weak/unowned receivers have their own.
        assertFalse(base.copy(directFinalNonExternalCall = false).isAuthorized())
        assertFalse(base.copy(weakOrUnownedShapeAbsent = false).isAuthorized())
    }

    @Test
    fun sharedSeedsGroupOnlyPointerIdenticalReceiverAndBlockFacts() {
        class EqualToken(private val name: String) {
            override fun equals(other: Any?): Boolean = other is EqualToken && other.name == name
            override fun hashCode(): Int = name.hashCode()
        }

        val block = EqualToken("block")
        val equalButDifferentBlock = EqualToken("block")
        val receiver = EqualToken("receiver")
        val equalButDifferentReceiver = EqualToken("receiver")
        val groups = groupArcPointerIdenticalCandidates(
            listOf(
                ArcPointerIdentityCandidate(block, receiver, "first"),
                ArcPointerIdentityCandidate(block, receiver, "second"),
                ArcPointerIdentityCandidate(equalButDifferentBlock, receiver, "other-block"),
                ArcPointerIdentityCandidate(block, equalButDifferentReceiver, "other-receiver"),
            )
        )

        assertEquals(3, groups.size)
        assertEquals(listOf("first", "second"), groups.single {
            it.block === block && it.receiver === receiver
        }.values)
        assertEquals(listOf("other-block"), groups.single { it.block === equalButDifferentBlock }.values)
        assertEquals(listOf("other-receiver"), groups.single {
            it.receiver === equalButDifferentReceiver
        }.values)
    }

    @Test
    fun everyStructuralBarrierSplitsAReceiverSeedSegment() {
        val block = Any()
        val receiver = Any()
        ArcDiscardedReturnedReceiverBarrierKind.values().forEach { barrier ->
            val groups = groupArcDiscardedReturnedReceiverEvents(
                block,
                listOf(
                    ArcDiscardedReturnedReceiverStructuralEvent.Candidate(receiver, "before"),
                    ArcDiscardedReturnedReceiverStructuralEvent.Barrier(barrier),
                    ArcDiscardedReturnedReceiverStructuralEvent.Candidate(receiver, "after"),
                ),
            )
            assertEquals("barrier $barrier must split a seed", 2, groups.size)
            assertEquals(listOf("before"), groups[0].values)
            assertEquals(listOf("after"), groups[1].values)
        }
    }

    @Test
    fun contiguousCallsShareOnlyTheirExactReceiverSegment() {
        val block = Any()
        val receiver = Any()
        val otherReceiver = Any()
        val groups = groupArcDiscardedReturnedReceiverEvents(
            block,
            listOf(
                ArcDiscardedReturnedReceiverStructuralEvent.Candidate(receiver, "one"),
                ArcDiscardedReturnedReceiverStructuralEvent.Candidate(receiver, "two"),
                ArcDiscardedReturnedReceiverStructuralEvent.Candidate(otherReceiver, "other"),
            ),
        )
        assertEquals(2, groups.size)
        assertEquals(listOf("one", "two"), groups.single { it.receiver === receiver }.values)
        assertEquals(listOf("other"), groups.single { it.receiver === otherReceiver }.values)
    }
}
