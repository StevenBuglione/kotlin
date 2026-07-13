/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.llvm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcReturnedReceiverCallSiteInlineTest {
    @Test
    fun exactSelectedArcReleaseCallIsAuthorized() {
        val accepted = ArcReturnedReceiverCallSiteInlineEligibility(
            arcEnabled = true,
            optimizationsEnabled = true,
            debugInfoDisabled = true,
            diagnosticsDisabled = true,
            exactDiscardedGroupCall = true,
            exactCanonicalFunction = true,
        )
        assertTrue(accepted.isAuthorized())
        assertFalse(accepted.copy(arcEnabled = false).isAuthorized())
        assertFalse(accepted.copy(optimizationsEnabled = false).isAuthorized())
        assertFalse(accepted.copy(debugInfoDisabled = false).isAuthorized())
        assertFalse(accepted.copy(diagnosticsDisabled = false).isAuthorized())
        assertFalse(accepted.copy(exactDiscardedGroupCall = false).isAuthorized())
        assertFalse(accepted.copy(exactCanonicalFunction = false).isAuthorized())
    }
}
