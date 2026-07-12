/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcRootedProjectionLoopPlanningTest {
    @Test
    fun nestedInlineLoopAcceptsOnlyStrictDeclarationPathPrefix() {
        // Lowered `repeat { val cursor = ...; repeat { cursor = cursor.next!! } }` keeps the
        // declaration in the synthetic outer statement while the target loop has one more path
        // component. Equal paths and the reverse relation must remain fail-closed.
        assertTrue(arcStrictLexicalPrefixDominates(declarationPathSize = 2, usePathSize = 3))
        assertFalse(arcStrictLexicalPrefixDominates(declarationPathSize = 2, usePathSize = 2))
        assertFalse(arcStrictLexicalPrefixDominates(declarationPathSize = 3, usePathSize = 2))
    }

    @Test
    fun nestedInlineLoopStructuralFallbackRequiresExactAncestorReentryPath() {
        // Benchmark shape: cursor and anchor are recreated in the sole ancestor loop, and the
        // cursor declaration is visited before the lowered inner loop.
        assertTrue(
            arcNestedStructuralDominanceFallback(
                targetAncestorDepth = 1,
                declarationLoopPathMatches = true,
                declarationVisitedBeforeTarget = true,
            )
        )

        // Ancestor-reentry negative: a cursor declared outside the outer loop has depth zero and
        // cannot be reused after an outer-tail graph mutation.
        assertFalse(
            arcNestedStructuralDominanceFallback(
                targetAncestorDepth = 1,
                declarationLoopPathMatches = false,
                declarationVisitedBeforeTarget = true,
            )
        )
        assertFalse(
            arcNestedStructuralDominanceFallback(
                targetAncestorDepth = 0,
                declarationLoopPathMatches = true,
                declarationVisitedBeforeTarget = true,
            )
        )
        assertFalse(
            arcNestedStructuralDominanceFallback(
                targetAncestorDepth = 1,
                declarationLoopPathMatches = true,
                declarationVisitedBeforeTarget = false,
            )
        )
    }

    @Test
    fun rootedProjectionLoopRequiresEverySafetyGate() {
        val eligible = ArcRootedProjectionLoopEligibility(
            arcEnabled = true,
            optimizationsEnabled = true,
            debugInfoDisabled = true,
            nonSuspendFunction = true,
            mutableStrongLocalCursor = true,
            cursorNotCaptured = true,
            exactlyOneProjectionLoop = true,
            directLiveAnchorInitializer = true,
            anchorDominatesAndEnclosesLoop = true,
            everyAssignmentCanonicalSelfProjection = true,
            everyReadImmediateDirectFieldReceiver = true,
            noUsesAfterRegion = true,
            noUnknownOrUserCall = true,
            noTryFinally = true,
            noSuspension = true,
            noNestedFunctionOrCallback = true,
            noAllocation = true,
            noFieldWrite = true,
            noOtherReferenceOwnershipEffects = true,
            noAlternateAssignment = true,
            noReturnBreakOrContinue = true,
            strongNonVolatileTransitionField = true,
            transitionFieldNotWritten = true,
            verifierProofAccepted = true,
        )

        assertTrue(eligible.isAuthorized())
        assertFalse(eligible.copy(arcEnabled = false).isAuthorized())
        assertFalse(eligible.copy(optimizationsEnabled = false).isAuthorized())
        assertFalse(eligible.copy(debugInfoDisabled = false).isAuthorized())
        assertFalse(eligible.copy(nonSuspendFunction = false).isAuthorized())
        assertFalse(eligible.copy(mutableStrongLocalCursor = false).isAuthorized())
        assertFalse(eligible.copy(cursorNotCaptured = false).isAuthorized())
        assertFalse(eligible.copy(exactlyOneProjectionLoop = false).isAuthorized())
        assertFalse(eligible.copy(directLiveAnchorInitializer = false).isAuthorized())
        assertFalse(eligible.copy(anchorDominatesAndEnclosesLoop = false).isAuthorized())
        assertFalse(eligible.copy(everyAssignmentCanonicalSelfProjection = false).isAuthorized())
        assertFalse(eligible.copy(everyReadImmediateDirectFieldReceiver = false).isAuthorized())
        assertFalse(eligible.copy(noUsesAfterRegion = false).isAuthorized())
        assertFalse(eligible.copy(noUnknownOrUserCall = false).isAuthorized())
        assertFalse(eligible.copy(noTryFinally = false).isAuthorized())
        assertFalse(eligible.copy(noSuspension = false).isAuthorized())
        assertFalse(eligible.copy(noNestedFunctionOrCallback = false).isAuthorized())
        assertFalse(eligible.copy(noAllocation = false).isAuthorized())
        assertFalse(eligible.copy(noFieldWrite = false).isAuthorized())
        assertFalse(eligible.copy(noOtherReferenceOwnershipEffects = false).isAuthorized())
        assertFalse(eligible.copy(noAlternateAssignment = false).isAuthorized())
        assertFalse(eligible.copy(noReturnBreakOrContinue = false).isAuthorized())
        assertFalse(eligible.copy(strongNonVolatileTransitionField = false).isAuthorized())
        assertFalse(eligible.copy(transitionFieldNotWritten = false).isAuthorized())
        assertFalse(eligible.copy(verifierProofAccepted = false).isAuthorized())
    }
}
