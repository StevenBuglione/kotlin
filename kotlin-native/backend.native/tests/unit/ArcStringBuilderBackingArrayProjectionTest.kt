/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcStringBuilderBackingArrayProjectionTest {
    private val accepted = ArcStringBuilderBackingArrayProjectionEligibility(
        arcEnabled = true,
        linuxX64 = true,
        optimizationsEnabled = true,
        debugInfoDisabled = true,
        diagnosticsDisabled = true,
        sanitizerDisabled = true,
        coverageDisabled = true,
        exactStdlibDeclarationIdentities = true,
        finalNonExternalNonSuspendMethod = true,
        guaranteedDispatchReceiver = true,
        privateStrongCharArrayField = true,
        exactGCUnsafeConsumer = true,
        fieldLoadIsFirstArgument = true,
        remainingArgumentsAreOwnershipEffectFree = true,
        exactlyOneProjectionConsumer = true,
        normalAndExceptionalLifetimeProofAccepted = true,
    )

    @Test
    fun exactCanonicalProjectionIsAuthorized() {
        assertTrue(accepted.isAuthorized())
        assertTrue(verifyStringBuilderBackingArrayProjectionLifetime())
    }

    @Test
    fun nonArcAndNonOptimizedModesFallBack() {
        assertFalse(accepted.copy(arcEnabled = false).isAuthorized())
        assertFalse(accepted.copy(optimizationsEnabled = false).isAuthorized())
        assertFalse(accepted.copy(linuxX64 = false).isAuthorized())
    }

    @Test
    fun observabilityAndSanitizerModesFallBack() {
        assertFalse(accepted.copy(debugInfoDisabled = false).isAuthorized())
        assertFalse(accepted.copy(diagnosticsDisabled = false).isAuthorized())
        assertFalse(accepted.copy(sanitizerDisabled = false).isAuthorized())
        assertFalse(accepted.copy(coverageDisabled = false).isAuthorized())
    }

    @Test
    fun declarationOrConsumerShapeDriftFallsBack() {
        assertFalse(accepted.copy(exactStdlibDeclarationIdentities = false).isAuthorized())
        assertFalse(accepted.copy(exactGCUnsafeConsumer = false).isAuthorized())
        assertFalse(accepted.copy(exactlyOneProjectionConsumer = false).isAuthorized())
    }

    @Test
    fun unsafeProjectionLifetimeFallsBack() {
        assertFalse(accepted.copy(guaranteedDispatchReceiver = false).isAuthorized())
        assertFalse(accepted.copy(privateStrongCharArrayField = false).isAuthorized())
        assertFalse(accepted.copy(fieldLoadIsFirstArgument = false).isAuthorized())
        assertFalse(accepted.copy(remainingArgumentsAreOwnershipEffectFree = false).isAuthorized())
        assertFalse(accepted.copy(normalAndExceptionalLifetimeProofAccepted = false).isAuthorized())
    }
}
