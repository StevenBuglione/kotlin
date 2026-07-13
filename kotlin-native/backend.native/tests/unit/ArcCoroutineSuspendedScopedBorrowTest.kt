/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcCoroutineSuspendedScopedBorrowTest {
    @Test
    fun exactRootedIdentityUseBuildsVerifiedScopedBorrow() {
        val selection = ArcCoroutineSuspendedBorrowAnalysis.select(validCandidate()).selection!!
        assertEquals(ArcOwnershipVerificationResult.Success, ArcOwnershipVerifier.verify(selection.ownershipProof))
        ArcCoroutineSuspendedBorrowConsumptionLedger(listOf(selection)).also { ledger ->
            ledger.consume(selection.candidate.call, selection.candidate.exactIdentityBindings)
            ledger.verifyComplete()
        }
    }

    @Test
    fun everyNonProductionModeFallsBack() {
        val candidate = validCandidate()
        listOf(
            candidate.mode.copy(arcEnabled = false), candidate.mode.copy(linuxX64 = false),
            candidate.mode.copy(finalBinary = false), candidate.mode.copy(optimizationsEnabled = false),
            candidate.mode.copy(debugInfoDisabled = false), candidate.mode.copy(diagnosticsDisabled = false),
            candidate.mode.copy(sanitizerDisabled = false), candidate.mode.copy(coverageDisabled = false),
        ).forEach { mode -> assertRejected(candidate.copy(mode = mode), ArcCoroutineSuspendedBorrowRejectionReason.UnsupportedCompilationMode) }
    }

    @Test
    fun rootAndUseProofsFailClosedIndependently() {
        val candidate = validCandidate()
        val proof = candidate.proof
        listOf(
            proof.copy(exactStdlibGetter = false), proof.copy(exactGlobalInitializerCall = false),
            proof.copy(exactCoroutineSingletonsEnum = false), proof.copy(exactEnumGetterZero = false),
            proof.copy(exactSharedImmutableValuesRoot = false), proof.copy(exactBorrowedArrayProjection = false),
            proof.copy(rootWritesRestrictedToInitializer = false),
        ).forEach { malformed ->
            assertRejected(candidate.copy(proof = malformed), ArcCoroutineSuspendedBorrowRejectionReason.DeclarationOrRootMismatch)
        }
        listOf(
            proof.copy(immediateIdentityComparison = false), proof.copy(noOwnedResultSlot = false),
            proof.copy(noStoreReturnOrEscape = false),
        ).forEach { escaping ->
            assertRejected(candidate.copy(proof = escaping), ArcCoroutineSuspendedBorrowRejectionReason.EscapingUse)
        }
    }

    @Test
    fun ledgerRejectsMissingDuplicateAndDriftedEmission() {
        val selection = ArcCoroutineSuspendedBorrowAnalysis.select(validCandidate()).selection!!
        val exact = selection.candidate.exactIdentityBindings
        assertTrue(runCatching { ArcCoroutineSuspendedBorrowConsumptionLedger(listOf(selection)).verifyComplete() }.isFailure)
        exact.indices.forEach { index ->
            val drifted = exact.toMutableList().also { it[index] = Any() }
            assertTrue(runCatching {
                ArcCoroutineSuspendedBorrowConsumptionLedger(listOf(selection)).consume(selection.candidate.call, drifted)
            }.isFailure)
        }
        val ledger = ArcCoroutineSuspendedBorrowConsumptionLedger(listOf(selection))
        ledger.consume(selection.candidate.call, exact)
        assertTrue(runCatching { ledger.consume(selection.candidate.call, exact) }.isFailure)
    }

    private fun validCandidate(): ArcCoroutineSuspendedBorrowCandidate<Any> =
        ArcCoroutineSuspendedBorrowCandidate(
            call = Any(), comparison = Any(), getter = Any(), initializer = Any(),
            enumGetter = Any(), root = Any(), rootRead = Any(),
            mode = ArcCoroutineSuspendedBorrowMode(true, true, true, true, true, true, true, true),
            proof = ArcCoroutineSuspendedBorrowProof(
                exactStdlibGetter = true, exactGlobalInitializerCall = true,
                exactCoroutineSingletonsEnum = true, exactEnumGetterZero = true,
                exactSharedImmutableValuesRoot = true, exactBorrowedArrayProjection = true,
                rootWritesRestrictedToInitializer = true, immediateIdentityComparison = true,
                noOwnedResultSlot = true, noStoreReturnOrEscape = true,
            ),
        )

    private fun assertRejected(
        candidate: ArcCoroutineSuspendedBorrowCandidate<Any>,
        reason: ArcCoroutineSuspendedBorrowRejectionReason,
    ) {
        val result = ArcCoroutineSuspendedBorrowAnalysis.select(candidate)
        assertNull(result.selection)
        assertEquals(reason, result.rejection)
    }
}
