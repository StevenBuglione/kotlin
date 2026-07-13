/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ArcBaseContinuationParamBorrowTest {
    private val eligible = ArcBaseContinuationParamBorrowEligibility(
        arcEnabled = true,
        optimizationsEnabled = true,
        debugInfoDisabled = true,
        diagnosticsDisabled = true,
        linuxX64 = true,
        exactStdlibFunctionIdentity = true,
        finalNonExternalUnitFunction = true,
        exactSingleLoop = true,
        exactStrongMutableParamRoot = true,
        rootDominatesCall = true,
        exactVirtualInvokeSuspendCall = true,
        exactSingleReferenceArgumentRead = true,
        noArgumentSuffix = true,
        noInterveningParamWrite = true,
        rootLivesOnNormalAndExceptionalEdges = true,
        loweredNonForeignCall = true,
    )

    @Test
    fun exactCanonicalShapeIsAuthorized() {
        assertTrue(eligible.isAuthorized())
    }

    @Test
    fun everySafetyGateFailsClosed() {
        assertFalse(eligible.copy(arcEnabled = false).isAuthorized())
        assertFalse(eligible.copy(optimizationsEnabled = false).isAuthorized())
        assertFalse(eligible.copy(debugInfoDisabled = false).isAuthorized())
        assertFalse(eligible.copy(diagnosticsDisabled = false).isAuthorized())
        assertFalse(eligible.copy(linuxX64 = false).isAuthorized())
        assertFalse(eligible.copy(exactStdlibFunctionIdentity = false).isAuthorized())
        assertFalse(eligible.copy(finalNonExternalUnitFunction = false).isAuthorized())
        assertFalse(eligible.copy(exactSingleLoop = false).isAuthorized())
        assertFalse(eligible.copy(exactStrongMutableParamRoot = false).isAuthorized())
        assertFalse(eligible.copy(rootDominatesCall = false).isAuthorized())
        assertFalse(eligible.copy(exactVirtualInvokeSuspendCall = false).isAuthorized())
        assertFalse(eligible.copy(exactSingleReferenceArgumentRead = false).isAuthorized())
        assertFalse(eligible.copy(noArgumentSuffix = false).isAuthorized())
        assertFalse(eligible.copy(noInterveningParamWrite = false).isAuthorized())
        assertFalse(eligible.copy(rootLivesOnNormalAndExceptionalEdges = false).isAuthorized())
        assertFalse(eligible.copy(loweredNonForeignCall = false).isAuthorized())
    }
}
