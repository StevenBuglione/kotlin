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

class ArcImmortalCompletionContextPropagationIRSelectorTest {
    @Test
    fun exactLoweredInventoryAdaptsToVerifiedPropagation() {
        val result = adaptVerifiedImmortalCompletionContextIR(validBindings(), validMode(), validShape())

        assertNull(result.rejection)
        assertNull(result.ownershipRejection)
        val selection = result.selection!!
        assertEquals(
            ArcOwnershipVerificationResult.Success,
            ArcOwnershipVerifier.verify(selection.ownership.initializerOwnershipProof),
        )
        assertEquals(
            ArcOwnershipVerificationResult.Success,
            ArcOwnershipVerifier.verify(selection.ownership.getterOwnershipProof),
        )
        assertTrue(selection.ownership.candidate.constructorBinding === selection.bindings.constructor)
        assertTrue(selection.ownership.candidate.fieldBinding === selection.bindings.contextField)
        assertTrue(selection.ownership.candidate.permanentRootBinding === selection.bindings.permanentRootField)
    }

    @Test
    fun everyRealWalkerFactIsMandatoryIncludingFailureAndEscapeCensuses() {
        val shape = validShape()
        listOf(
            shape.copy(exactUserCompletionAllocationAndConstructor = false),
            shape.copy(exactContinuationContextOverrideChain = false),
            shape.copy(finalOwnerWithNoSubclasses = false),
            shape.copy(immutableFinalPropertyAndGetter = false),
            shape.copy(privateFinalStrongBackingField = false),
            shape.copy(exactFreshReceiverFieldStore = false),
            shape.copy(fieldUninitializedAtStore = false),
            shape.copy(initializerStoreDominatesEscapesAndSuccess = false),
            shape.copy(constructorHasNoPreStoreExceptionalEdge = false),
            shape.copy(exactSingleFieldGetterReturn = false),
            shape.copy(exactEmptyCoroutineContextRoot = false),
            shape.copy(exactConstantObjectInitializer = false),
            shape.copy(permanentTagOneRuntimeContract = false),
            shape.copy(exactlyOneFieldWriteInModule = false),
            shape.copy(noRootWritesInModule = false),
            shape.copy(noSubclassOverrideOrFieldAddressEscape = false),
            shape.copy(completeLoweredIRWalk = false),
        ).forEach { malformed ->
            val result = adaptVerifiedImmortalCompletionContextIR(validBindings(), validMode(), malformed)
            assertNull(result.selection)
            assertEquals(
                ArcImmortalCompletionContextIRRejectionReason.InvalidStructuralProof,
                result.rejection,
            )
        }
    }

    @Test
    fun everyLoweredIdentityMustBeDistinct() {
        val bindings = validBindings()
        val duplicates = listOf(
            bindings.copy(allocation = bindings.ownerClass),
            bindings.copy(constructor = bindings.ownerClass),
            bindings.copy(constructedReceiver = bindings.ownerClass),
            bindings.copy(contextProperty = bindings.ownerClass),
            bindings.copy(contextField = bindings.ownerClass),
            bindings.copy(initializerStore = bindings.ownerClass),
            bindings.copy(initializerRootLoad = bindings.ownerClass),
            bindings.copy(contextGetter = bindings.ownerClass),
            bindings.copy(getterReturn = bindings.ownerClass),
            bindings.copy(getterFieldLoad = bindings.ownerClass),
            bindings.copy(continuationClass = bindings.ownerClass),
            bindings.copy(continuationContextProperty = bindings.ownerClass),
            bindings.copy(continuationContextGetter = bindings.ownerClass),
            bindings.copy(emptyContextClass = bindings.ownerClass),
            bindings.copy(emptyContextProperty = bindings.ownerClass),
            bindings.copy(emptyContextGetter = bindings.ownerClass),
            bindings.copy(permanentRootField = bindings.ownerClass),
            bindings.copy(permanentRootReturn = bindings.ownerClass),
            bindings.copy(permanentRootLoad = bindings.ownerClass),
            bindings.copy(constantObject = bindings.ownerClass),
            bindings.copy(constantObjectConstructor = bindings.ownerClass),
        )
        duplicates.forEach { duplicate ->
            val result = adaptVerifiedImmortalCompletionContextIR(duplicate, validMode(), validShape())
            assertNull(result.selection)
            assertEquals(
                ArcImmortalCompletionContextIRRejectionReason.DuplicateStructuralIdentity,
                result.rejection,
            )
        }
    }

    @Test
    fun semanticProductionModeRemainsBehindTheStructuralGate() {
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
        ).forEach { unsupported ->
            val result = adaptVerifiedImmortalCompletionContextIR(validBindings(), unsupported, validShape())
            assertNull(result.selection)
            assertEquals(
                ArcImmortalCompletionContextIRRejectionReason.OwnershipAnalysisRejected,
                result.rejection,
            )
            assertEquals(
                ArcImmortalCompletionContextRejectionReason.UnsupportedCompilationMode,
                result.ownershipRejection,
            )
        }
    }

    @Test
    fun selectedEmissionLedgerStaysTiedToExactConstructorFieldGetterAndRoot() {
        val selected = adaptVerifiedImmortalCompletionContextIR(
            validBindings(),
            validMode(),
            validShape(),
        ).selection!!
        val bindings = selected.bindings
        val ledger = ArcImmortalCompletionContextConsumptionLedger(selected.ownership)

        ledger.consumeInitializer(
            bindings.constructor,
            bindings.contextField,
            bindings.initializerStore,
            bindings.permanentRootField,
        )
        ledger.consumeGetter(bindings.contextGetter, bindings.getterFieldLoad, bindings.getterReturn)
        ledger.verifyComplete()
    }

    private fun validBindings() = ArcImmortalCompletionContextIRBindings(
        ownerClass = Any(),
        allocation = Any(),
        constructor = Any(),
        constructedReceiver = Any(),
        contextProperty = Any(),
        contextField = Any(),
        initializerStore = Any(),
        initializerRootLoad = Any(),
        contextGetter = Any(),
        getterReturn = Any(),
        getterFieldLoad = Any(),
        continuationClass = Any(),
        continuationContextProperty = Any(),
        continuationContextGetter = Any(),
        emptyContextClass = Any(),
        emptyContextProperty = Any(),
        emptyContextGetter = Any(),
        permanentRootField = Any(),
        permanentRootReturn = Any(),
        permanentRootLoad = Any(),
        constantObject = Any(),
        constantObjectConstructor = Any(),
    )

    private fun validMode() =
        ArcImmortalCompletionContextMode(true, true, true, true, true, true, true, true)

    private fun validShape() = ArcImmortalCompletionContextIRShape(
        exactUserCompletionAllocationAndConstructor = true,
        exactContinuationContextOverrideChain = true,
        finalOwnerWithNoSubclasses = true,
        immutableFinalPropertyAndGetter = true,
        privateFinalStrongBackingField = true,
        exactFreshReceiverFieldStore = true,
        fieldUninitializedAtStore = true,
        initializerStoreDominatesEscapesAndSuccess = true,
        constructorHasNoPreStoreExceptionalEdge = true,
        exactSingleFieldGetterReturn = true,
        exactEmptyCoroutineContextRoot = true,
        exactConstantObjectInitializer = true,
        permanentTagOneRuntimeContract = true,
        exactlyOneFieldWriteInModule = true,
        noRootWritesInModule = true,
        noSubclassOverrideOrFieldAddressEscape = true,
        completeLoweredIRWalk = true,
    )
}
