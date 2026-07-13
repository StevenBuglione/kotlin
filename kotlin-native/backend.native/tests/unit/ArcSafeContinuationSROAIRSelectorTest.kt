/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcSafeContinuationSROAIRSelectorTest {
    @Test
    fun exactPostLoweringInventoryAdaptsToProofOnlySROAPlan() {
        val result = adaptVerifiedSafeContinuationSROAIR(validBindings(), validMode(), validShape())

        assertNull(result.rejection)
        val selection = result.selection!!
        assertTrue(selection.bindings.allocation === selection.semantic.candidate.allocationBinding)
        assertTrue(selection.bindings.resumeCall === selection.semantic.candidate.resumeBinding)
        assertTrue(selection.bindings.getOrThrowCall === selection.semantic.candidate.getOrThrowBinding)
        assertEquals(
            ArcOwnershipVerificationResult.Success,
            ArcOwnershipVerifier.verify(selection.semantic.ownershipPlan),
        )
        assertFalse(selection.semantic.reduction.emitted)
    }

    @Test
    fun everyPostLoweringWalkerFactIsMandatory() {
        val shape = validShape()
        listOf(
            shape.copy(exactStdlibDeclarations = false),
            shape.copy(exactFunctionContainer = false),
            shape.copy(exactLocalConstructorInitialization = false),
            shape.copy(immutableLocal = false),
            shape.copy(wholeFunctionNoLocalWrites = false),
            shape.copy(exactSecondaryConstructorEffectInventory = false),
            shape.copy(exactPrivateStrongFields = false),
            shape.copy(exactDirectResumeReceiver = false),
            shape.copy(exactDirectGetReceiver = false),
            shape.copy(exactlyTwoLocalReads = false),
            shape.copy(completeTransitiveUseWalk = false),
            shape.copy(singleLinearRegion = false),
            shape.copy(resumePrecedesAndDominatesGet = false),
            shape.copy(getPostDominatesResume = false),
            shape.copy(oneSynchronousResume = false),
            shape.copy(undecidedInitialStateProven = false),
            shape.copy(suspendedAndDelegateBranchesUnreachable = false),
            shape.copy(noNestedFunctionCapture = false),
            shape.copy(noThreadOrWorkerBoundary = false),
            shape.copy(noForeignOrExternalBoundary = false),
            shape.copy(noTryRegion = false),
            shape.copy(noSuspensionPoint = false),
            shape.copy(noBackedgeOrStateTransition = false),
            shape.copy(noCoroutineSuspendedReturn = false),
            shape.copy(noUnsupportedAncestorContext = false),
            shape.copy(noConditionalBranch = false),
            shape.copy(noEarlyControlTransfer = false),
            shape.copy(exactStructuralCallInventory = false),
            shape.copy(exactResumeArgumentDataflow = false),
            shape.copy(exactSafeContinuationMethodContract = false),
            shape.copy(exactUnitStructuralEdges = false),
            shape.copy(exactBranchOrderAndDataflow = false),
            shape.copy(ordinaryHeapOwnershipPreserved = false),
        ).forEach { rejectedShape ->
            val result = adaptVerifiedSafeContinuationSROAIR(validBindings(), validMode(), rejectedShape)
            assertNull(result.selection)
            assertEquals(ArcSafeContinuationSROAIRRejectionReason.InvalidStructuralProof, result.rejection)
        }
    }

    @Test
    fun realSuspensionEscapeMultipleResumeAndBoundariesFailClosed() {
        val shape = validShape()
        val rejected = listOf(
            shape.copy(suspendedAndDelegateBranchesUnreachable = false),
            shape.copy(completeTransitiveUseWalk = false),
            shape.copy(oneSynchronousResume = false),
            shape.copy(noThreadOrWorkerBoundary = false),
            shape.copy(noForeignOrExternalBoundary = false),
            shape.copy(noTryRegion = false),
            shape.copy(noSuspensionPoint = false),
            shape.copy(noBackedgeOrStateTransition = false),
            shape.copy(noCoroutineSuspendedReturn = false),
            shape.copy(noUnsupportedAncestorContext = false),
            shape.copy(noConditionalBranch = false),
            shape.copy(noEarlyControlTransfer = false),
            shape.copy(exactStructuralCallInventory = false),
            shape.copy(exactResumeArgumentDataflow = false),
            shape.copy(exactSafeContinuationMethodContract = false),
            shape.copy(exactUnitStructuralEdges = false),
        )
        rejected.forEach { facts ->
            assertEquals(
                ArcSafeContinuationSROAIRRejectionReason.InvalidStructuralProof,
                adaptVerifiedSafeContinuationSROAIR(validBindings(), validMode(), facts).rejection,
            )
        }
    }

    @Test
    fun conditionalResumeEarlyReturnAndDisconnectedProducerReject() {
        val shape = validShape()
        listOf(
            shape.copy(noConditionalBranch = false),
            shape.copy(noEarlyControlTransfer = false),
            shape.copy(exactResumeArgumentDataflow = false),
        ).forEach { malformed ->
            val result = adaptVerifiedSafeContinuationSROAIR(validBindings(), validMode(), malformed)
            assertNull(result.selection)
            assertEquals(ArcSafeContinuationSROAIRRejectionReason.InvalidStructuralProof, result.rejection)
        }
    }

    @Test
    fun everyBindingIdentityIsDistinctAndAuthenticated() {
        val bindings = validBindings()
        val duplicateBindings = listOf(
            bindings.copy(function = bindings.allocation),
            bindings.copy(safeClass = bindings.allocation),
            bindings.copy(local = bindings.allocation),
            bindings.copy(constructor = bindings.allocation),
            bindings.copy(interceptedReceiver = bindings.allocation),
            bindings.copy(interceptedReceiver = bindings.interceptedCall),
            bindings.copy(interceptedCall = bindings.allocation),
            bindings.copy(delegateField = bindings.allocation),
            bindings.copy(resultRefField = bindings.allocation),
            bindings.copy(resumeWith = bindings.allocation),
            bindings.copy(resumeCall = bindings.allocation),
            bindings.copy(resumeReceiver = bindings.allocation),
            bindings.copy(resumeResultArgument = bindings.allocation),
            bindings.copy(resumeResultParameter = bindings.allocation),
            bindings.copy(resumeValueProducer = bindings.allocation),
            bindings.copy(resultCompanionGetter = bindings.allocation),
            bindings.copy(resultBoxIntrinsic = bindings.allocation),
            bindings.copy(resultConstructor = bindings.allocation),
            bindings.copy(structuralUnitCalls = listOf(bindings.allocation, Any())),
            bindings.copy(getOrThrow = bindings.allocation),
            bindings.copy(getOrThrowCall = bindings.allocation),
            bindings.copy(getOrThrowReceiver = bindings.allocation),
            bindings.copy(contractBindings = listOf(bindings.allocation)),
        )
        duplicateBindings.forEach { duplicate ->
            assertEquals(
                ArcSafeContinuationSROAIRRejectionReason.DuplicateStructuralIdentity,
                adaptVerifiedSafeContinuationSROAIR(duplicate, validMode(), validShape()).rejection,
            )
        }
    }

    @Test
    fun unsupportedCompilationModesReachTheSemanticFailClosedGate() {
        val mode = validMode()
        listOf(
            mode.copy(arcEnabled = false), mode.copy(linuxX64 = false),
            mode.copy(finalBinary = false), mode.copy(optimizationsEnabled = false),
            mode.copy(debugInfoDisabled = false), mode.copy(diagnosticsDisabled = false),
            mode.copy(sanitizerDisabled = false), mode.copy(coverageDisabled = false),
            mode.copy(nonSuspendFunction = false, exactLoweredSuspendFunction = false),
            mode.copy(nonExternalFunction = false),
        ).forEach { rejectedMode ->
            val result = adaptVerifiedSafeContinuationSROAIR(validBindings(), rejectedMode, validShape())
            assertEquals(ArcSafeContinuationSROAIRRejectionReason.SemanticAnalysisRejected, result.rejection)
            assertEquals(
                ArcSafeContinuationSROARejectionReason.UnsupportedCompilationMode,
                result.semanticRejection,
            )
        }
    }

    private fun validBindings() = ArcSafeContinuationSROAIRBindings(
        function = Any(), safeClass = Any(), local = Any(), allocation = Any(),
        constructor = Any(), interceptedReceiver = Any(), interceptedCall = Any(), delegateField = Any(),
        resultRefField = Any(), resumeWith = Any(), resumeCall = Any(), resumeReceiver = Any(),
        resumeResultArgument = Any(), resumeResultParameter = Any(), resumeValueProducer = Any(),
        resultCompanionGetter = Any(), resultBoxIntrinsic = Any(), resultConstructor = Any(),
        structuralUnitCalls = listOf(Any(), Any()), getOrThrow = Any(),
        getOrThrowCall = Any(), getOrThrowReceiver = Any(),
        contractBindings = List(16) { Any() },
    )

    private fun validMode() = ArcSafeContinuationSROAMode(
        true, true, true, true, true, true, true, true, true, false, true,
    )

    private fun validShape() = ArcSafeContinuationSROAIRShape(
        exactStdlibDeclarations = true,
        exactFunctionContainer = true,
        exactLocalConstructorInitialization = true,
        immutableLocal = true,
        wholeFunctionNoLocalWrites = true,
        exactSecondaryConstructorEffectInventory = true,
        exactPrivateStrongFields = true,
        exactDirectResumeReceiver = true,
        exactDirectGetReceiver = true,
        exactlyTwoLocalReads = true,
        completeTransitiveUseWalk = true,
        singleLinearRegion = true,
        resumePrecedesAndDominatesGet = true,
        getPostDominatesResume = true,
        oneSynchronousResume = true,
        undecidedInitialStateProven = true,
        suspendedAndDelegateBranchesUnreachable = true,
        noNestedFunctionCapture = true,
        noThreadOrWorkerBoundary = true,
        noForeignOrExternalBoundary = true,
        noTryRegion = true,
        noSuspensionPoint = true,
        noBackedgeOrStateTransition = true,
        noCoroutineSuspendedReturn = true,
        noUnsupportedAncestorContext = true,
        noConditionalBranch = true,
        noEarlyControlTransfer = true,
        exactStructuralCallInventory = true,
        exactResumeArgumentDataflow = true,
        exactSafeContinuationMethodContract = true,
        exactUnitStructuralEdges = true,
        exactBranchOrderAndDataflow = true,
        ordinaryHeapOwnershipPreserved = true,
    )
}
