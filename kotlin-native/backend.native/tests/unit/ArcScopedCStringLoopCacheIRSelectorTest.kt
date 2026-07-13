/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcScopedCStringLoopCacheIRSelectorTest {
    @Test
    fun acceptsOnlyOneCandidateWithAnExternalSourceAndNoInvalidControlRegion() {
        val exact = ArcScopedCStringLoopCacheIRShape(
            authenticatedCandidateCount = 1,
            invalidControlRegionCount = 0,
            sourceDeclaredOutsideLoop = true,
        )
        assertTrue(exact.isExact())

        listOf(
            exact.copy(authenticatedCandidateCount = 0),
            exact.copy(authenticatedCandidateCount = 2),
            exact.copy(invalidControlRegionCount = 1),
            exact.copy(sourceDeclaredOutsideLoop = false),
        ).forEach { assertFalse(it.isExact()) }
    }
}
