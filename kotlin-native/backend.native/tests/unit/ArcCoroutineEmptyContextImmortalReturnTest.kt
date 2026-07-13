/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ArcCoroutineEmptyContextImmortalReturnTest {
    @Test
    fun exactPermanentGetterBuildsVerifiedImmortalReturn() {
        val result = ArcCoroutineEmptyContextReturnAnalysis.select(validCandidate())

        assertNull(result.rejection)
        val selection = result.selection!!
        assertEquals(ArcCoroutineEmptyContextReturnReduction(1, 1), selection.reduction)
        assertEquals(ArcOwnershipVerificationResult.Success, ArcOwnershipVerifier.verify(selection.ownershipProof))

        ArcCoroutineEmptyContextReturnConsumptionLedger(selection).also { ledger ->
            ledger.consume(
                selection.candidate.getterBinding,
                selection.candidate.objectBinding,
                selection.candidate.returnBinding,
            )
            ledger.verifyComplete()
        }
    }

    @Test
    fun everyNonProductionModeFallsBack() {
        val candidate = validCandidate()
        listOf(
            candidate.mode.copy(arcEnabled = false),
            candidate.mode.copy(linuxX64 = false),
            candidate.mode.copy(finalBinary = false),
            candidate.mode.copy(optimizationsEnabled = false),
            candidate.mode.copy(debugInfoDisabled = false),
            candidate.mode.copy(diagnosticsDisabled = false),
            candidate.mode.copy(sanitizerDisabled = false),
            candidate.mode.copy(coverageDisabled = false),
            candidate.mode.copy(nonSuspendFunction = false),
            candidate.mode.copy(nonExternalFunction = false),
        ).forEach { mode ->
            assertRejected(
                candidate.copy(mode = mode),
                ArcCoroutineEmptyContextReturnRejectionReason.UnsupportedCompilationMode,
            )
        }
    }

    @Test
    fun everyDeclarationAndPermanenceIdentityIsRequired() {
        val candidate = validCandidate()
        val identity = candidate.identity
        listOf(
            identity.copy(exactStdlibLibrary = false),
            identity.copy(exactRestrictedContinuationImplClass = false),
            identity.copy(exactContextGetterOverride = false),
            identity.copy(exactCoroutineContextReturnType = false),
            identity.copy(exactEmptyCoroutineContextObject = false),
            identity.copy(finalPermanentObject = false),
            identity.copy(runtimeAllocatesObjectAsImmortal = false),
        ).forEach { proof ->
            assertRejected(
                candidate.copy(identity = proof),
                ArcCoroutineEmptyContextReturnRejectionReason.DeclarationIdentityMismatch,
            )
        }
    }

    @Test
    fun everyExactBodyFactIsRequired() {
        val candidate = validCandidate()
        val body = candidate.body
        listOf(
            body.copy(singleEntryBlock = false),
            body.copy(singleReturn = false),
            body.copy(returnTargetsExactGetter = false),
            body.copy(returnValueIsExactObjectLoad = false),
            body.copy(noReceiverOrParameterUse = false),
            body.copy(noMutableLoad = false),
            body.copy(noNestedDeclaration = false),
            body.copy(noExceptionalEdge = false),
        ).forEach { proof ->
            assertRejected(
                candidate.copy(body = proof),
                ArcCoroutineEmptyContextReturnRejectionReason.LoweredBodyShapeMismatch,
            )
        }
    }

    @Test
    fun reorderedDuplicatedOrOwnershipObservableEffectsFallBack() {
        val candidate = validCandidate()
        val allowed = candidate.effects
        val rejectedEffects = mutableListOf(
            allowed.reversed(),
            allowed + ArcCoroutineEmptyContextReturnEffect.PermanentObjectLoad,
            listOf(ArcCoroutineEmptyContextReturnEffect.PermanentObjectLoad),
        )
        listOf(
            ArcCoroutineEmptyContextReturnEffect.MutableLoad,
            ArcCoroutineEmptyContextReturnEffect.HeapStore,
            ArcCoroutineEmptyContextReturnEffect.UserCall,
            ArcCoroutineEmptyContextReturnEffect.VirtualCall,
            ArcCoroutineEmptyContextReturnEffect.ExternalCall,
            ArcCoroutineEmptyContextReturnEffect.ForeignCall,
            ArcCoroutineEmptyContextReturnEffect.Suspension,
            ArcCoroutineEmptyContextReturnEffect.TryRegion,
            ArcCoroutineEmptyContextReturnEffect.Unknown,
        ).mapTo(rejectedEffects) { allowed + it }
        rejectedEffects.forEach { effects ->
            assertRejected(
                candidate.copy(effects = effects),
                ArcCoroutineEmptyContextReturnRejectionReason.UnsupportedEffect,
            )
        }
    }

    @Test
    fun consumptionLedgerRejectsIdentityDriftDuplicatesAndMissingEmission() {
        val selection = ArcCoroutineEmptyContextReturnAnalysis.select(validCandidate()).selection!!
        val candidate = selection.candidate
        val alien = Any()

        expectFailure("getter identity") {
            ArcCoroutineEmptyContextReturnConsumptionLedger(selection)
                .consume(alien, candidate.objectBinding, candidate.returnBinding)
        }
        expectFailure("object identity") {
            ArcCoroutineEmptyContextReturnConsumptionLedger(selection)
                .consume(candidate.getterBinding, alien, candidate.returnBinding)
        }
        expectFailure("return identity") {
            ArcCoroutineEmptyContextReturnConsumptionLedger(selection)
                .consume(candidate.getterBinding, candidate.objectBinding, alien)
        }
        expectFailure("not emitted") { ArcCoroutineEmptyContextReturnConsumptionLedger(selection).verifyComplete() }

        val ledger = ArcCoroutineEmptyContextReturnConsumptionLedger(selection)
        ledger.consume(candidate.getterBinding, candidate.objectBinding, candidate.returnBinding)
        expectFailure("twice") {
            ledger.consume(candidate.getterBinding, candidate.objectBinding, candidate.returnBinding)
        }
    }

    private fun validCandidate(): ArcCoroutineEmptyContextReturnCandidate<Any> =
        ArcCoroutineEmptyContextReturnCandidate(
            getterBinding = Any(),
            objectBinding = Any(),
            returnBinding = Any(),
            mode = ArcCoroutineEmptyContextReturnMode(
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
            ),
            identity = ArcCoroutineEmptyContextReturnIdentityProof(
                exactStdlibLibrary = true,
                exactRestrictedContinuationImplClass = true,
                exactContextGetterOverride = true,
                exactCoroutineContextReturnType = true,
                exactEmptyCoroutineContextObject = true,
                finalPermanentObject = true,
                runtimeAllocatesObjectAsImmortal = true,
            ),
            body = ArcCoroutineEmptyContextReturnBodyProof(
                singleEntryBlock = true,
                singleReturn = true,
                returnTargetsExactGetter = true,
                returnValueIsExactObjectLoad = true,
                noReceiverOrParameterUse = true,
                noMutableLoad = true,
                noNestedDeclaration = true,
                noExceptionalEdge = true,
            ),
            effects = listOf(
                ArcCoroutineEmptyContextReturnEffect.PermanentObjectLoad,
                ArcCoroutineEmptyContextReturnEffect.ReturnSlotProjection,
            ),
        )

    private fun assertRejected(
        candidate: ArcCoroutineEmptyContextReturnCandidate<Any>,
        reason: ArcCoroutineEmptyContextReturnRejectionReason,
    ) {
        val result = ArcCoroutineEmptyContextReturnAnalysis.select(candidate)
        assertNull(result.selection)
        assertEquals(reason, result.rejection)
    }

    private fun expectFailure(message: String, block: () -> Unit) {
        try {
            block()
            fail("expected failure containing '$message'")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(message))
        }
    }
}
