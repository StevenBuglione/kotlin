/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertFalse
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
}
