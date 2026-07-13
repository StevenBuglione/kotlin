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

class ArcCoroutineEmptyContextImmortalReturnIRSelectorTest {
    @Test
    fun exactLoweredInventoryAdaptsToImmortalReturnProof() {
        val result = adaptVerifiedCoroutineEmptyContextReturnIR(
            validBindings(),
            validMode(),
            validShape(),
        )

        assertNull(result.rejection)
        assertNull(result.ownershipRejection)
        val selection = result.selection!!
        assertEquals(ArcCoroutineEmptyContextReturnReduction(1, 1), selection.ownership.reduction)
        assertEquals(
            ArcOwnershipVerificationResult.Success,
            ArcOwnershipVerifier.verify(selection.ownership.ownershipProof),
        )
    }

    @Test
    fun everyRealWalkerFactIsMandatory() {
        val shape = validShape()
        listOf(
            shape.copy(exactRestrictedContinuationDeclaration = false),
            shape.copy(exactContinuationContextOverride = false),
            // A non-null/different canonical accessor is not the sealed Native lowered form.
            shape.copy(continuationPropertyGetterStripped = false),
            shape.copy(exactSingleReturnBody = false),
            shape.copy(exactSyntheticObjectGetterCall = false),
            shape.copy(exactEmptyCoroutineContextDeclaration = false),
            shape.copy(exactPrivateFinalStaticRoot = false),
            shape.copy(exactRootGetterBody = false),
            shape.copy(exactConstantObjectInitializer = false),
            shape.copy(noRootWrites = false),
            shape.copy(completeLoweredIRWalk = false),
        ).forEach { malformed ->
            val result = adaptVerifiedCoroutineEmptyContextReturnIR(
                validBindings(),
                validMode(),
                malformed,
            )
            assertNull(result.selection)
            assertEquals(
                ArcCoroutineEmptyContextReturnIRRejectionReason.InvalidStructuralProof,
                result.rejection,
            )
        }
    }

    @Test
    fun duplicateNodeIdentityRejectsBeforeOwnershipAnalysis() {
        val bindings = validBindings()
        listOf(
            bindings.copy(baseContinuationClass = bindings.restrictedContinuationClass),
            bindings.copy(restrictedContextProperty = bindings.function),
            bindings.copy(baseContextGetter = bindings.function),
            bindings.copy(baseContextProperty = bindings.restrictedContextProperty),
            bindings.copy(continuationContextProperty = bindings.baseContextProperty),
            bindings.copy(rootLoad = bindings.rootGetterReturn),
        ).forEach { duplicate ->
            val result = adaptVerifiedCoroutineEmptyContextReturnIR(duplicate, validMode(), validShape())
            assertNull(result.selection)
            assertEquals(
                ArcCoroutineEmptyContextReturnIRRejectionReason.DuplicateStructuralIdentity,
                result.rejection,
            )
        }
    }

    @Test
    fun semanticProductionModeStillFailsClosedBehindExactIR() {
        val bindings = validBindings()
        val mode = validMode()
        listOf(
            mode.copy(arcEnabled = false),
            mode.copy(linuxX64 = false),
            mode.copy(finalBinary = false),
            mode.copy(optimizationsEnabled = false),
            mode.copy(debugInfoDisabled = false),
            mode.copy(diagnosticsDisabled = false),
            mode.copy(sanitizerDisabled = false),
            mode.copy(coverageDisabled = false),
            mode.copy(nonSuspendFunction = false),
            mode.copy(nonExternalFunction = false),
        ).forEach { unsupported ->
            val result = adaptVerifiedCoroutineEmptyContextReturnIR(bindings, unsupported, validShape())
            assertNull(result.selection)
            assertEquals(
                ArcCoroutineEmptyContextReturnIRRejectionReason.OwnershipAnalysisRejected,
                result.rejection,
            )
            assertEquals(
                ArcCoroutineEmptyContextReturnRejectionReason.UnsupportedCompilationMode,
                result.ownershipRejection,
            )
        }
    }

    @Test
    fun selectedSemanticLedgerRemainsTiedToExactRealIRNodes() {
        val selection = adaptVerifiedCoroutineEmptyContextReturnIR(
            validBindings(),
            validMode(),
            validShape(),
        ).selection!!
        val bindings = selection.bindings
        val ledger = ArcCoroutineEmptyContextReturnConsumptionLedger(selection.ownership)

        ledger.consumeExact(bindings.exactIdentityInventory())
        ledger.verifyComplete()
        assertTrue(selection.ownership.candidate.getterBinding === bindings.function)
        assertTrue(selection.ownership.candidate.objectBinding === bindings.constantObject)
        assertTrue(selection.ownership.candidate.returnBinding === bindings.returned)
    }

    @Test
    fun emissionLedgerRejectsDriftInEveryLoweredIdentity() {
        val selection = adaptVerifiedCoroutineEmptyContextReturnIR(
            validBindings(),
            validMode(),
            validShape(),
        ).selection!!
        val exact = selection.bindings.exactIdentityInventory()
        exact.indices.forEach { index ->
            val drifted = exact.toMutableList().also { it[index] = Any() }
            expectFailure("exact identity inventory drifted") {
                ArcCoroutineEmptyContextReturnConsumptionLedger(selection.ownership)
                    .consumeExact(drifted)
            }
        }
    }

    private fun validBindings(): ArcCoroutineEmptyContextReturnIRBindings<Any> =
        ArcCoroutineEmptyContextReturnIRBindings(
            function = Any(),
            restrictedContinuationClass = Any(),
            baseContinuationClass = Any(),
            continuationClass = Any(),
            restrictedContextProperty = Any(),
            baseContextGetter = Any(),
            baseContextProperty = Any(),
            continuationContextGetter = Any(),
            continuationContextProperty = Any(),
            returned = Any(),
            objectGetterCall = Any(),
            emptyContextClass = Any(),
            objectProperty = Any(),
            objectGetter = Any(),
            rootField = Any(),
            rootGetterReturn = Any(),
            rootLoad = Any(),
            constantObject = Any(),
            constantConstructor = Any(),
        )

    private fun validShape() = ArcCoroutineEmptyContextReturnIRShape(
        exactRestrictedContinuationDeclaration = true,
        exactContinuationContextOverride = true,
        continuationPropertyGetterStripped = true,
        exactSingleReturnBody = true,
        exactSyntheticObjectGetterCall = true,
        exactEmptyCoroutineContextDeclaration = true,
        exactPrivateFinalStaticRoot = true,
        exactRootGetterBody = true,
        exactConstantObjectInitializer = true,
        noRootWrites = true,
        completeLoweredIRWalk = true,
    )

    private fun validMode() = ArcCoroutineEmptyContextReturnMode(
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
    )

    private fun expectFailure(message: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("expected failure containing '$message'", failure != null)
        assertTrue(failure!!.message.orEmpty().contains(message))
    }
}
