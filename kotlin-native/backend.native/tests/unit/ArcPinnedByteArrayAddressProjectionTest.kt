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

class ArcPinnedByteArrayAddressProjectionTest {
    @Test
    fun exactUsePinnedWebBuildsClosedDependentBorrowPlan() {
        val candidate = validCandidate()
        val result = ArcPinnedByteArrayAddressAnalysis.select(candidate)

        assertTrue(result.rejections.toString(), result.rejections.isEmpty())
        val plan = result.plan!!
        assertEquals(ARC_PINNED_BYTE_ARRAY_ADDRESS_HELPER, plan.runtimeHelper)
        assertEquals(3, plan.actions.size)

        ArcPinnedByteArrayAddressConsumptionLedger(plan, candidate.lifetime.functionBinding).also { ledger ->
            plan.actions.values.forEach { ledger.consume(it.id, it.bindingIdentities) }
            ledger.verifyComplete()
        }
    }

    @Test
    fun everyNonProductionCompilationModeFallsBack() {
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
            candidate.mode.copy(sourceFunction = false),
        ).forEach { mode -> assertRejected(candidate.copy(mode = mode),
            ArcPinnedByteArrayAddressRejectionReason.UnsupportedCompilationMode) }
    }

    @Test
    fun declarationNamesWithoutExactLibraryAndBodyIdentityNeverAuthorize() {
        val candidate = validCandidate()
        val declarations = candidate.declarations
        listOf(
            declarations.copy(exactInteropLibraryIdentity = false),
            declarations.copy(exactKotlinxCinteropPackage = false),
            declarations.copy(exactFinalPinnedByteArrayInstantiation = false),
            declarations.copy(exactPrivateFinalNativePointerField = false),
            declarations.copy(exactPinSignatureAndBody = false),
            declarations.copy(exactPinnedByteArrayAddressOfSignatureAndBody = false),
            declarations.copy(exactUnpinSignatureAndBody = false),
            declarations.copy(canonicalByteArrayAddressIntrinsic = false),
        ).forEach { proof -> assertRejected(candidate.copy(declarations = proof),
            ArcPinnedByteArrayAddressRejectionReason.DeclarationIdentityMismatch) }
    }

    @Test
    fun holderMustKeepTheStableHandleAliveUntilAfterThePointerUse() {
        val candidate = validCandidate()
        val lifetime = candidate.lifetime
        listOf(
            lifetime.copy(exactSinglePinAndTwoPathExclusiveUnpins = false),
            lifetime.copy(pinDominatesProjection = false),
            lifetime.copy(projectionDominatesPointerUse = false),
            lifetime.copy(pointerUsePostDominatesProjection = false),
            lifetime.copy(unpinPostDominatesPointerUse = false),
            lifetime.copy(noUnpinOrHandleMutationBeforeLastUse = false),
            lifetime.copy(holderDoesNotEscapeOrAlias = false),
            lifetime.copy(stableHandleDoesNotEscapeOrAlias = false),
        ).forEach { proof -> assertRejected(candidate.copy(lifetime = proof),
            ArcPinnedByteArrayAddressRejectionReason.InvalidStableHandleLifetime) }
    }

    @Test
    fun escapedPointerAndControlFlowAmbiguityFallBack() {
        val candidate = validCandidate()
        assertRejected(candidate.copy(lifetime = candidate.lifetime.copy(pointerHasOneNonEscapingUse = false)),
            ArcPinnedByteArrayAddressRejectionReason.EscapingInteriorPointer)
        listOf(
            candidate.lifetime.copy(noNestedFunctionOrSuspensionBoundary = false),
            candidate.lifetime.copy(noUnknownCallBetweenProjectionAndUse = false),
            candidate.lifetime.copy(allNormalAndExceptionalEdgesPreserveCleanup = false),
        ).forEach { proof -> assertRejected(candidate.copy(lifetime = proof),
            ArcPinnedByteArrayAddressRejectionReason.UnsupportedControlFlow) }
    }

    @Test
    fun evaluationOrderBoundsAndExceptionalSuccessorArePartOfTheProof() {
        val candidate = validCandidate()
        val semantics = candidate.semantics
        listOf(
            semantics.copy(receiverEvaluatedExactlyOnce = false),
            semantics.copy(indexEvaluatedExactlyOnce = false),
            semantics.copy(receiverBeforeIndex = false),
            semantics.copy(helperDelegatesToCanonicalBoundsCheck = false),
            semantics.copy(originalExceptionalSuccessorPreserved = false),
            semantics.copy(pointerElementTypeIsByteVar = false),
        ).forEach { proof -> assertRejected(candidate.copy(semantics = proof),
            ArcPinnedByteArrayAddressRejectionReason.EvaluationOrExceptionMismatch) }
    }

    @Test
    fun incompleteWalkAndReusedStructuralIdentityFailClosed() {
        val candidate = validCandidate()
        assertRejected(candidate.copy(completeLoweredIRWalk = false),
            ArcPinnedByteArrayAddressRejectionReason.IncompleteLoweredIRWalk)
        assertRejected(candidate.copy(lifetime = candidate.lifetime.copy(
            pointerUseBinding = candidate.lifetime.addressOfCallBinding,
        )), ArcPinnedByteArrayAddressRejectionReason.DuplicateStructuralBinding)
    }

    @Test
    fun ledgerRejectsWrongFunctionOrderBindingAndIncompleteEmission() {
        val candidate = validCandidate()
        val plan = ArcPinnedByteArrayAddressAnalysis.select(candidate).plan!!

        assertFails { ArcPinnedByteArrayAddressConsumptionLedger(plan, Any()) }

        ArcPinnedByteArrayAddressConsumptionLedger(plan, candidate.lifetime.functionBinding).also { ledger ->
            val end = plan.actions.getValue(ArcPinnedByteArrayAddressActionId.EndStableHandleDependence)
            assertFails { ledger.consume(end.id, end.bindingIdentities) }
        }
        ArcPinnedByteArrayAddressConsumptionLedger(plan, candidate.lifetime.functionBinding).also { ledger ->
            val begin = plan.actions.getValue(ArcPinnedByteArrayAddressActionId.BeginStableHandleDependence)
            assertFails { ledger.consume(begin.id, begin.bindingIdentities.map { Any() }) }
        }
        ArcPinnedByteArrayAddressConsumptionLedger(plan, candidate.lifetime.functionBinding).also { ledger ->
            val begin = plan.actions.getValue(ArcPinnedByteArrayAddressActionId.BeginStableHandleDependence)
            ledger.consume(begin.id, begin.bindingIdentities)
            assertFails { ledger.verifyComplete() }
        }
    }

    private fun validCandidate(): ArcPinnedByteArrayAddressCandidate<Any> =
        ArcPinnedByteArrayAddressCandidate(
            mode = ArcPinnedByteArrayAddressCompilationMode(
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
                sourceFunction = true,
            ),
            declarations = ArcPinnedByteArrayAddressDeclarationProof(
                interopLibraryBinding = Any(),
                pinnedClassBinding = Any(),
                stablePointerFieldBinding = Any(),
                pinFunctionBinding = Any(),
                addressOfFunctionBinding = Any(),
                unpinFunctionBinding = Any(),
                exactInteropLibraryIdentity = true,
                exactKotlinxCinteropPackage = true,
                exactFinalPinnedByteArrayInstantiation = true,
                exactPrivateFinalNativePointerField = true,
                exactPinSignatureAndBody = true,
                exactPinnedByteArrayAddressOfSignatureAndBody = true,
                exactUnpinSignatureAndBody = true,
                canonicalByteArrayAddressIntrinsic = true,
            ),
            lifetime = ArcPinnedByteArrayAddressLifetimeProof(
                functionBinding = Any(),
                pinCallBinding = Any(),
                holderBinding = Any(),
                addressOfCallBinding = Any(),
                receiverReadBinding = Any(),
                indexBinding = Any(),
                pointerUseBinding = Any(),
                normalUnpinCallBinding = Any(),
                exceptionalUnpinCallBinding = Any(),
                exactSinglePinAndTwoPathExclusiveUnpins = true,
                pinDominatesProjection = true,
                projectionDominatesPointerUse = true,
                pointerUsePostDominatesProjection = true,
                unpinPostDominatesPointerUse = true,
                noUnpinOrHandleMutationBeforeLastUse = true,
                holderDoesNotEscapeOrAlias = true,
                stableHandleDoesNotEscapeOrAlias = true,
                pointerHasOneNonEscapingUse = true,
                noNestedFunctionOrSuspensionBoundary = true,
                noUnknownCallBetweenProjectionAndUse = true,
                allNormalAndExceptionalEdgesPreserveCleanup = true,
            ),
            semantics = ArcPinnedByteArrayAddressSemanticsProof(
                receiverEvaluatedExactlyOnce = true,
                indexEvaluatedExactlyOnce = true,
                receiverBeforeIndex = true,
                helperDelegatesToCanonicalBoundsCheck = true,
                originalExceptionalSuccessorPreserved = true,
                pointerElementTypeIsByteVar = true,
            ),
            completeLoweredIRWalk = true,
        )

    private fun assertRejected(
        candidate: ArcPinnedByteArrayAddressCandidate<Any>,
        reason: ArcPinnedByteArrayAddressRejectionReason,
    ) {
        val result = ArcPinnedByteArrayAddressAnalysis.select(candidate)
        assertNull(result.plan)
        assertEquals(listOf(reason), result.rejections.map { it.reason })
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("expected ownership handoff to fail closed")
        } catch (_: IllegalStateException) {
        }
    }
}
