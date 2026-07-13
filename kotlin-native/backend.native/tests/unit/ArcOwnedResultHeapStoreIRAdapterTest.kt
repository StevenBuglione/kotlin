/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcOwnedResultHeapStoreIRAdapterTest {
    @Test
    fun exactTwoStatementResumeWithBuildsIdentityCarryingSelection() {
        val bindings = bindings()
        val result = adaptVerifiedOwnedResultHeapStoreIR(bindings, mode(), shape())

        assertNull(result.rejection)
        assertNull(result.ownershipRejection)
        val selection = result.selection!!
        assertSame(bindings.producerCall, selection.exactBindings.getValue(
            ArcOwnedResultHeapStoreIRBindingRole.Producer,
        ).irIdentity)
        assertSame(bindings.producerCall, selection.exactBindings.getValue(
            ArcOwnedResultHeapStoreIRBindingRole.SourceResultSlot,
        ).irIdentity)
        assertTrue(
            selection.exactBindings.getValue(ArcOwnedResultHeapStoreIRBindingRole.Producer) !==
                    selection.exactBindings.getValue(ArcOwnedResultHeapStoreIRBindingRole.SourceResultSlot),
        )
        assertSame(bindings.store, selection.exactBindings.getValue(
            ArcOwnedResultHeapStoreIRBindingRole.DestinationElementSlot,
        ).irIdentity)
        assertEquals(ARC_OWNED_RESULT_HEAP_STORE_HELPER, selection.ownership.runtimeHelper)
        assertEquals(
            listOf(
                ArcOwnedResultHeapStoreInterveningEffect.LifetimeConstraintCheck,
                ArcOwnedResultHeapStoreInterveningEffect.DestinationAddressProjection,
            ),
            selection.ownership.candidate.interveningEffects,
        )
        assertEquals(
            ArcOwnershipVerificationResult.Success,
            ArcOwnershipVerifier.verify(selection.ownership.ownershipProof),
        )
    }

    @Test
    fun everyIncompleteRealIRProofFallsBackBeforeOwnershipSelection() {
        val exact = shape()
        listOf(
            exact.copy(exactSourceContinuationOverride = false),
            exact.copy(exactResultValueParameter = false),
            exact.copy(exactCapturedRefStorage = false),
            exact.copy(exactMutableStrongRefElement = false),
            exact.copy(exactStdlibResultBox = false),
            exact.copy(exactDirectStoreExpression = false),
            exact.copy(exactTrailingUnitReturn = false),
            exact.copy(completeLoweredIRWalk = false),
            exact.copy(receiverEvaluatedBeforeProducer = false),
            exact.copy(directNormalAndExceptionalFrontiers = false),
        ).forEach { shape ->
            val result = adaptVerifiedOwnedResultHeapStoreIR(bindings(), mode(), shape)
            assertNull(result.selection)
            assertEquals(ArcOwnedResultHeapStoreIRRejectionReason.InvalidStructuralProof, result.rejection)
        }
    }

    @Test
    fun duplicateKotlinIRIdentityCannotAcquireTwoStructuralRoles() {
        val bindings = bindings()
        val result = adaptVerifiedOwnedResultHeapStoreIR(
            bindings.copy(resultRead = bindings.producerCall),
            mode(),
            shape(),
        )

        assertNull(result.selection)
        assertEquals(ArcOwnedResultHeapStoreIRRejectionReason.DuplicateStructuralIdentity, result.rejection)
    }

    @Test
    fun debugDiagnosticsStrictAndSanitizerModesSelectZero() {
        val production = mode()
        listOf(
            production.copy(debugInfoDisabled = false),
            production.copy(diagnosticsDisabled = false),
            production.copy(arcEnabled = false),
            production.copy(sanitizerDisabled = false),
        ).forEach { mode ->
            val result = adaptVerifiedOwnedResultHeapStoreIR(bindings(), mode, shape())
            assertNull(result.selection)
            assertEquals(ArcOwnedResultHeapStoreIRRejectionReason.OwnershipAnalysisRejected, result.rejection)
            assertEquals(ArcOwnedResultHeapStoreRejectionReason.UnsupportedCompilationMode, result.ownershipRejection)
        }
    }

    @Test
    fun exactRoleTokensDriveThePrimitiveConsumptionLedger() {
        val selection = adaptVerifiedOwnedResultHeapStoreIR(bindings(), mode(), shape()).selection!!
        val ownership = selection.ownership
        ArcOwnedResultHeapStoreConsumptionLedger(ownership).also { ledger ->
            ledger.consumeProducer(
                selection.exactBindings.getValue(ArcOwnedResultHeapStoreIRBindingRole.Producer),
                selection.exactBindings.getValue(ArcOwnedResultHeapStoreIRBindingRole.SourceResultSlot),
            )
            ledger.consumeStore(
                selection.exactBindings.getValue(ArcOwnedResultHeapStoreIRBindingRole.Store),
                selection.exactBindings.getValue(ArcOwnedResultHeapStoreIRBindingRole.DestinationElementSlot),
            )
            ledger.verifyComplete()
        }
    }

    private fun bindings(): ArcOwnedResultHeapStoreIRBindings<Any> = ArcOwnedResultHeapStoreIRBindings(
        function = Any(),
        ownerClass = Any(),
        receiverParameter = Any(),
        resultParameter = Any(),
        continuationResumeWith = Any(),
        refClass = Any(),
        capturedRefField = Any(),
        capturedRefRead = Any(),
        refElementField = Any(),
        store = Any(),
        resultBoxFunction = Any(),
        producerCall = Any(),
        resultRead = Any(),
        trailingReturn = Any(),
        unitCall = Any(),
    )

    private fun mode() = ArcOwnedResultHeapStoreMode(
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

    private fun shape() = ArcOwnedResultHeapStoreIRShape(
        exactSourceContinuationOverride = true,
        exactResultValueParameter = true,
        exactCapturedRefStorage = true,
        exactMutableStrongRefElement = true,
        exactStdlibResultBox = true,
        exactDirectStoreExpression = true,
        exactTrailingUnitReturn = true,
        completeLoweredIRWalk = true,
        receiverEvaluatedBeforeProducer = true,
        directNormalAndExceptionalFrontiers = true,
    )
}
