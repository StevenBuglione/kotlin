/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcStrongFieldBorrowPlanningTest {
    @Test
    fun suspendLikeMarkersRejectEveryLoweredCoroutineShape() {
        val ordinary = ArcSuspendLikeMarkers(
            sourceSuspend = false,
            loweredSuspendOrigin = false,
            coroutineImplFunctionOrigin = false,
            coroutineImplParentOrigin = false,
            continuationParameter = false,
        )

        assertFalse(ordinary.isSuspendLike())
        assertTrue(ordinary.copy(sourceSuspend = true).isSuspendLike())
        assertTrue(ordinary.copy(loweredSuspendOrigin = true).isSuspendLike())
        assertTrue(ordinary.copy(coroutineImplFunctionOrigin = true).isSuspendLike())
        assertTrue(ordinary.copy(coroutineImplParentOrigin = true).isSuspendLike())
        assertTrue(ordinary.copy(continuationParameter = true).isSuspendLike())
    }

    @Test
    fun directFieldReceiverBorrowRequiresEverySafetyGate() {
        val eligible = ArcBorrowedFieldReceiverEligibility(
            arcEnabled = true,
            optimizationsEnabled = true,
            debugInfoDisabled = true,
            nonSuspendFunction = true,
            exactDirectInstanceFieldReceiver = true,
            nonVolatileField = true,
            strongFieldStorage = true,
            mutableLocalReferenceOwner = true,
            ownerNotCaptured = true,
            strongOwnerStorage = true,
            immediateAddressAndLoadOnly = true,
            ownerLivesThroughLoad = true,
        )

        assertTrue(eligible.isAuthorized())
        assertFalse(eligible.copy(arcEnabled = false).isAuthorized())
        assertFalse(eligible.copy(optimizationsEnabled = false).isAuthorized())
        assertFalse(eligible.copy(debugInfoDisabled = false).isAuthorized())
        assertFalse(eligible.copy(nonSuspendFunction = false).isAuthorized())
        assertFalse(eligible.copy(exactDirectInstanceFieldReceiver = false).isAuthorized())
        assertFalse(eligible.copy(nonVolatileField = false).isAuthorized())
        assertFalse(eligible.copy(strongFieldStorage = false).isAuthorized())
        assertFalse(eligible.copy(mutableLocalReferenceOwner = false).isAuthorized())
        assertFalse(eligible.copy(ownerNotCaptured = false).isAuthorized())
        assertFalse(eligible.copy(strongOwnerStorage = false).isAuthorized())
        assertFalse(eligible.copy(immediateAddressAndLoadOnly = false).isAuthorized())
        assertFalse(eligible.copy(ownerLivesThroughLoad = false).isAuthorized())
    }

    @Test
    fun strongCharArrayCallFieldBorrowRequiresEverySafetyGate() {
        val eligible = ArcBorrowedStrongCallFieldEligibility(
            arcEnabled = true,
            optimizationsEnabled = true,
            debugInfoDisabled = true,
            nonSuspendFunction = true,
            exactDirectInstanceFieldArgument = true,
            referenceField = true,
            nonVolatileField = true,
            strongFieldStorage = true,
            ownerIsCurrentDispatchReceiver = true,
            ownerReferenceIsGuaranteedForCall = true,
            exactAllowlistedCharArrayConsumer = true,
            ownerNotAssignedInSuffix = true,
            callFreeSuffix = true,
            nonSuspendingSuffix = true,
            nonThrowingSuffix = true,
            linearControlFlowSuffix = true,
            ownershipEffectFreeSuffix = true,
            verifierProofAccepted = true,
        )

        assertTrue(eligible.isAuthorized())
        assertFalse(eligible.copy(arcEnabled = false).isAuthorized())
        assertFalse(eligible.copy(optimizationsEnabled = false).isAuthorized())
        assertFalse(eligible.copy(debugInfoDisabled = false).isAuthorized())
        assertFalse(eligible.copy(nonSuspendFunction = false).isAuthorized())
        assertFalse(eligible.copy(exactDirectInstanceFieldArgument = false).isAuthorized())
        assertFalse(eligible.copy(referenceField = false).isAuthorized())
        assertFalse(eligible.copy(nonVolatileField = false).isAuthorized())
        assertFalse(eligible.copy(strongFieldStorage = false).isAuthorized())
        assertFalse(eligible.copy(ownerIsCurrentDispatchReceiver = false).isAuthorized())
        assertFalse(eligible.copy(ownerReferenceIsGuaranteedForCall = false).isAuthorized())
        assertFalse(eligible.copy(exactAllowlistedCharArrayConsumer = false).isAuthorized())
        assertFalse(eligible.copy(ownerNotAssignedInSuffix = false).isAuthorized())
        assertFalse(eligible.copy(callFreeSuffix = false).isAuthorized())
        assertFalse(eligible.copy(nonSuspendingSuffix = false).isAuthorized())
        assertFalse(eligible.copy(nonThrowingSuffix = false).isAuthorized())
        assertFalse(eligible.copy(linearControlFlowSuffix = false).isAuthorized())
        assertFalse(eligible.copy(ownershipEffectFreeSuffix = false).isAuthorized())
        assertFalse(eligible.copy(verifierProofAccepted = false).isAuthorized())
    }

    @Test
    fun strongFieldProjectionBorrowRequiresEverySafetyGate() {
        val eligible = ArcBorrowedStrongFieldProjectionEligibility(
            arcEnabled = true,
            optimizationsEnabled = true,
            debugInfoDisabled = true,
            nonSuspendFunction = true,
            exactDirectInstanceFieldRead = true,
            nonVolatileField = true,
            referenceField = true,
            strongFieldStorage = true,
            mutableLocalReferenceOwner = true,
            ownerNotCaptured = true,
            strongOwnerStorage = true,
            exactReceiverRead = true,
            canonicalSelfReplacement = true,
            ownerUnchangedUntilFinalStore = true,
            noUnmodeledCallOrSuspension = true,
            noTryReturnWriteOrEscape = true,
            exactMutableStrongReplacementStore = true,
        )

        assertTrue(eligible.isAuthorized())
        assertFalse(eligible.copy(arcEnabled = false).isAuthorized())
        assertFalse(eligible.copy(optimizationsEnabled = false).isAuthorized())
        assertFalse(eligible.copy(debugInfoDisabled = false).isAuthorized())
        assertFalse(eligible.copy(nonSuspendFunction = false).isAuthorized())
        assertFalse(eligible.copy(exactDirectInstanceFieldRead = false).isAuthorized())
        assertFalse(eligible.copy(nonVolatileField = false).isAuthorized())
        assertFalse(eligible.copy(referenceField = false).isAuthorized())
        assertFalse(eligible.copy(strongFieldStorage = false).isAuthorized())
        assertFalse(eligible.copy(mutableLocalReferenceOwner = false).isAuthorized())
        assertFalse(eligible.copy(ownerNotCaptured = false).isAuthorized())
        assertFalse(eligible.copy(strongOwnerStorage = false).isAuthorized())
        assertFalse(eligible.copy(exactReceiverRead = false).isAuthorized())
        assertFalse(eligible.copy(canonicalSelfReplacement = false).isAuthorized())
        assertFalse(eligible.copy(ownerUnchangedUntilFinalStore = false).isAuthorized())
        assertFalse(eligible.copy(noUnmodeledCallOrSuspension = false).isAuthorized())
        assertFalse(eligible.copy(noTryReturnWriteOrEscape = false).isAuthorized())
        assertFalse(eligible.copy(exactMutableStrongReplacementStore = false).isAuthorized())
    }
}
