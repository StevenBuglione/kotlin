/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcFreshOwnedFieldStoreTest {
    @Test
    fun exactDirectConstructorIntoInitializedStrongFieldIsAuthorized() {
        assertTrue(validEligibility().isAuthorized())
    }

    @Test
    fun everyModeIdentityLifetimeAndExceptionalProofBitFailsClosed() {
        val valid = validEligibility()
        listOf(
            valid.copy(arcEnabled = false),
            valid.copy(linuxX64 = false),
            valid.copy(finalBinary = false),
            valid.copy(optimizationsEnabled = false),
            valid.copy(debugInfoDisabled = false),
            valid.copy(diagnosticsDisabled = false),
            valid.copy(sanitizerDisabled = false),
            valid.copy(coverageDisabled = false),
            valid.copy(nonSuspendFunction = false),
            valid.copy(nonExternalFunction = false),
            valid.copy(directConstructorStoreValue = false),
            valid.copy(directImmutableLocalReceiver = false),
            valid.copy(exactInitializedReceiverClass = false),
            valid.copy(mutableReferenceField = false),
            valid.copy(nonVolatileStrongField = false),
            valid.copy(nonInitializerStore = false),
            valid.copy(freshFinalLocalKotlinClass = false),
            valid.copy(producerUsesOwningResultSlot = false),
            valid.copy(producerHasExactlyOneDirectUse = false),
            valid.copy(receiverEvaluatedBeforeProducer = false),
            valid.copy(normalAndExceptionalOwnershipVerified = false),
        ).forEach { rejected ->
            assertFalse(rejected.toString(), rejected.isAuthorized())
        }
    }

    private fun validEligibility() = ArcFreshOwnedFieldStoreEligibility(
        arcEnabled = true,
        linuxX64 = true,
        finalBinary = true,
        optimizationsEnabled = true,
        debugInfoDisabled = true,
        diagnosticsDisabled = true,
        sanitizerDisabled = true,
        coverageDisabled = true,
        nonSuspendFunction = true,
        nonExternalFunction = true,
        directConstructorStoreValue = true,
        directImmutableLocalReceiver = true,
        exactInitializedReceiverClass = true,
        mutableReferenceField = true,
        nonVolatileStrongField = true,
        nonInitializerStore = true,
        freshFinalLocalKotlinClass = true,
        producerUsesOwningResultSlot = true,
        producerHasExactlyOneDirectUse = true,
        receiverEvaluatedBeforeProducer = true,
        normalAndExceptionalOwnershipVerified = true,
    )
}
