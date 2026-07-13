/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcSafeContinuationResumeBorrowPlanningTest {
    private val accepted = ArcSafeContinuationResumeBorrowEligibility(
        arcEnabled = true,
        optimizationsEnabled = true,
        debugInfoDisabled = true,
        diagnosticsDisabled = true,
        exactStdlibDeclarationIdentities = true,
        finalNonExternalNonSuspendFunction = true,
        guaranteedDispatchReceiver = true,
        privateStrongNonVolatileField = true,
        exactlyThreeLoads = true,
        exactlyOneGetterAndTwoDirectCompareAndSetConsumers = true,
        exactlyOneConstructorWriteAndNoOtherWriter = true,
        noNestedFunctionOrSuspensionBoundary = true,
        verifierAcceptedEveryNormalAndExceptionalBorrow = true,
    )

    @Test
    fun exactCanonicalWebIsAuthorized() {
        assertTrue(accepted.isAuthorized())
    }

    @Test
    fun mutableOrOtherwiseWriteableResultRefFallsBack() {
        assertFalse(accepted.copy(exactlyOneConstructorWriteAndNoOtherWriter = false).isAuthorized())
        assertFalse(accepted.copy(privateStrongNonVolatileField = false).isAuthorized())
    }

    @Test
    fun nonDirectConsumerFallsBack() {
        assertFalse(
            accepted.copy(exactlyOneGetterAndTwoDirectCompareAndSetConsumers = false).isAuthorized()
        )
    }

    @Test
    fun additionalFourthLoadFallsBack() {
        assertFalse(accepted.copy(exactlyThreeLoads = false).isAuthorized())
    }

    @Test
    fun nestedOrSuspendingBoundaryFallsBack() {
        assertFalse(accepted.copy(noNestedFunctionOrSuspensionBoundary = false).isAuthorized())
        assertFalse(accepted.copy(finalNonExternalNonSuspendFunction = false).isAuthorized())
    }

    @Test
    fun debugDiagnosticsAndStrictModesFallBack() {
        assertFalse(accepted.copy(debugInfoDisabled = false).isAuthorized())
        assertFalse(accepted.copy(diagnosticsDisabled = false).isAuthorized())
        assertFalse(accepted.copy(arcEnabled = false).isAuthorized())
    }

    @Test
    fun failedExceptionalLifetimeProofFallsBack() {
        assertFalse(
            accepted.copy(verifierAcceptedEveryNormalAndExceptionalBorrow = false).isAuthorized()
        )
    }
}
