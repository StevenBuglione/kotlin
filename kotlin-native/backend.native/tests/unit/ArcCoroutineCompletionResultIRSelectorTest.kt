/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ArcCoroutineCompletionResultIRSelectorTest {
    @Test
    fun exactLoweredInventoryProducesProofOnlySelection() {
        val result = adaptVerifiedCoroutineCompletionResultIR(validBindings(), validMode(), validShape())

        assertEquals(null, result.rejection)
        assertFalse(result.selection!!.emissionAuthorized)
        assertEquals(
            ArcOwnershipVerificationResult.Success,
            ArcOwnershipVerifier.verify(result.selection.normalAndUnwindOwnershipProof),
        )
    }

    @Test
    fun everyRealWalkerFactIsMandatory() {
        val shape = validShape()
        listOf(
            shape.copy(exactCompletionProjection = false),
            shape.copy(exactCompletionNullCheck = false),
            shape.copy(exactSuccessIdentityChain = false),
            shape.copy(exactFailureIdentityChain = false),
            shape.copy(exactOutcomeJoin = false),
            shape.copy(exactSuspendedExit = false),
            shape.copy(exactTryCatchArmsAndEffects = false),
            shape.copy(exactDispatchTopology = false),
            shape.copy(exactContinuationResumeWithABI = false),
            shape.copy(exactReferenceResultCatchOwnershipABI = false),
            shape.copy(exactBackedgeTransfer = false),
            shape.copy(exactTerminalCallAndReturn = false),
            shape.copy(completeCompletionUseCensus = false),
            shape.copy(completeOutcomeUseCensus = false),
            shape.copy(completeProducerUseCensus = false),
            shape.copy(completeLoweredBodyWalk = false),
            shape.copy(completeIdentityInventory = false),
        ).forEach { malformed ->
            val result = adaptVerifiedCoroutineCompletionResultIR(validBindings(), validMode(), malformed)
            assertNull(result.selection)
            assertEquals(ArcCoroutineCompletionResultIRRejectionReason.InvalidStructuralProof, result.rejection)
        }
    }

    @Test
    fun modeAndIdentityFailuresRemainVisibleAtAdapterBoundary() {
        val unsupported = adaptVerifiedCoroutineCompletionResultIR(
            validBindings(), validMode().copy(debugInfoDisabled = false), validShape(),
        )
        assertEquals(ArcCoroutineCompletionResultIRRejectionReason.OwnershipAnalysisRejected, unsupported.rejection)
        assertEquals(
            ArcCoroutineCompletionResultRejectionReason.UnsupportedCompilationMode,
            unsupported.ownershipRejection,
        )

        val bindings = validBindings()
        val duplicate = adaptVerifiedCoroutineCompletionResultIR(
            bindings.copy(terminalReturn = bindings.terminalResume), validMode(), validShape(),
        )
        assertEquals(ArcCoroutineCompletionResultIRRejectionReason.OwnershipAnalysisRejected, duplicate.rejection)
        assertEquals(
            ArcCoroutineCompletionResultRejectionReason.DuplicateStructuralIdentity,
            duplicate.ownershipRejection,
        )
    }

    @Test
    fun swappedJoinArmsExtraUsesAndBaseContinuationLookalikesFailClosed() {
        val shape = validShape()
        val malformed = listOf(
            "swapped success/failure join arms" to shape.copy(exactOutcomeJoin = false),
            "success arm with an extra effect" to shape.copy(exactTryCatchArmsAndEffects = false),
            "catch arm with a second result" to shape.copy(exactTryCatchArmsAndEffects = false),
            "completion bang-bang without exact throw wrapper" to shape.copy(exactCompletionNullCheck = false),
            "extra completion use" to shape.copy(completeCompletionUseCensus = false),
            "extra outcome use" to shape.copy(completeOutcomeUseCensus = false),
            "lookalike resumeWith outside exact BaseContinuationImpl" to
                    shape.copy(completeLoweredBodyWalk = false),
        )
        malformed.forEach { (description, candidate) ->
            val result = adaptVerifiedCoroutineCompletionResultIR(validBindings(), validMode(), candidate)
            assertNull(description, result.selection)
            assertEquals(
                description,
                ArcCoroutineCompletionResultIRRejectionReason.InvalidStructuralProof,
                result.rejection,
            )
        }
    }

    @Test
    fun malformedControlFlowAndAbiNeverBecomeOwnershipProofs() {
        val shape = validShape()
        listOf(
            "suspended comparison outside its returning branch" to shape.copy(exactSuspendedExit = false),
            "suspended branch with a side effect before return" to shape.copy(exactTryCatchArmsAndEffects = false),
            "type test no longer dominates the backedge" to shape.copy(exactDispatchTopology = false),
            "terminal resume placed on the backedge arm" to shape.copy(exactDispatchTopology = false),
            "backedge stores placed on the terminal arm" to shape.copy(exactDispatchTopology = false),
            "name-only resumeWith lookalike" to shape.copy(exactContinuationResumeWithABI = false),
            "weak or unowned completion field" to shape.copy(exactReferenceResultCatchOwnershipABI = false),
            "non-reference Result or catch owner" to shape.copy(exactReferenceResultCatchOwnershipABI = false),
            "unbound topology identity" to shape.copy(completeIdentityInventory = false),
        ).forEach { (description, malformed) ->
            val result = adaptVerifiedCoroutineCompletionResultIR(validBindings(), validMode(), malformed)
            assertNull(description, result.selection)
            assertEquals(
                description,
                ArcCoroutineCompletionResultIRRejectionReason.InvalidStructuralProof,
                result.rejection,
            )
        }
    }

    @Test
    fun missingNormalOrExceptionalCleanupNeverSelects() {
        val proof = validShape()
        listOf(
            "releaseIntercepted unwind" to proof.copy(exactOutcomeJoin = false),
            "terminal normal cleanup" to proof.copy(exactTerminalCallAndReturn = false),
            "terminal unwind cleanup" to proof.copy(completeProducerUseCensus = false),
            "completion borrow exceptional lifetime" to proof.copy(exactCompletionProjection = false),
            "failure factory unwind loses caught owner" to
                    proof.copy(exactReferenceResultCatchOwnershipABI = false),
        ).forEach { (description, malformed) ->
            val result = adaptVerifiedCoroutineCompletionResultIR(validBindings(), validMode(), malformed)
            assertNull(description, result.selection)
            assertEquals(
                description,
                ArcCoroutineCompletionResultIRRejectionReason.InvalidStructuralProof,
                result.rejection,
            )
        }
    }

    private fun validBindings(): ArcCoroutineCompletionResultBindings<Any> {
        val values = List(35) { Any() }
        return ArcCoroutineCompletionResultBindings(
            values[0], values[1], values[2], values[3], values[4], values[5], values[6],
            values[7], values[8], values[9], values[10], values[11], values[12], values[13],
            values[14], values[15], values[16], values[17], values[18], values[19], values[20], values[21],
            ArcCoroutineCompletionPhysicalMoveOperands(
                values[22], values[23], values[24], values[25], values[26],
            ),
            values.drop(27),
        )
    }

    private fun validMode() = ArcCoroutineCompletionResultMode(
        arcEnabled = true, linuxX64 = true, finalBinary = true, optimizationsEnabled = true,
        debugInfoDisabled = true, diagnosticsDisabled = true, sanitizerDisabled = true,
        coverageDisabled = true,
    )

    private fun validShape() = ArcCoroutineCompletionResultIRShape(
        exactCompletionProjection = true,
        exactCompletionNullCheck = true,
        exactSuccessIdentityChain = true,
        exactFailureIdentityChain = true,
        exactOutcomeJoin = true,
        exactSuspendedExit = true,
        exactTryCatchArmsAndEffects = true,
        exactDispatchTopology = true,
        exactContinuationResumeWithABI = true,
        exactReferenceResultCatchOwnershipABI = true,
        exactBackedgeTransfer = true,
        exactTerminalCallAndReturn = true,
        completeCompletionUseCensus = true,
        completeOutcomeUseCensus = true,
        completeProducerUseCensus = true,
        completeLoweredBodyWalk = true,
        completeIdentityInventory = true,
    )
}
