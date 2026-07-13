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
import org.junit.Assert.fail
import org.junit.Test

class ArcSafeContinuationSROATest {
    @Test
    fun exactSynchronousNonescapingShapeBuildsVerifiedScalarOwnership() {
        val result = ArcSafeContinuationSROAAnalysis.select(validCandidate())

        assertNull(result.rejection)
        val selection = result.selection!!
        assertEquals(ArcOwnershipVerificationResult.Success, ArcOwnershipVerifier.verify(selection.ownershipPlan))
        assertEquals(
            ArcSafeContinuationSROAStorageVerificationResult.Success,
            ArcSafeContinuationSROAStorageVerifier.verify(selection.storagePlan),
        )
        val operations = selection.ownershipPlan.blocks.values.flatMap { it.operations }
        assertEquals(
            setOf("scalar.delegate", "scalar.state", "scalar.result"),
            operations.filterIsInstance<ArcOperation.StrongStore>().map { it.storage.name }.toSet(),
        )
        assertEquals(2, selection.ownershipPlan.blocks.values.count { it.terminator === ArcTerminator.Throw })
        assertTrue(operations.filterIsInstance<ArcOperation.StrongLoad>().size >= 5)
        assertEquals(5, selection.reduction.logicalStrongUpdates)
        assertEquals(7, selection.reduction.logicalOwnedLoadsOrRetains)
        assertEquals(2, selection.reduction.logicalHeapAllocations)
        assertEquals(2, selection.reduction.logicalHeapFrees)
        assertEquals(ArcSafeContinuationSROAReductionSite.entries, selection.reduction.sites)
        assertNull("physical 5/7 must come from emitted-code inspection", selection.reduction.measuredPhysical)
        assertFalse("a proof-only slice must not claim emitted reduction", selection.reduction.emitted)
    }

    @Test
    fun consumingStorageProofRejectsLeaksDoubleTakesAndUnbalancedExceptionalEdges() {
        val selection = ArcSafeContinuationSROAAnalysis.select(validCandidate()).selection!!
        val plan = selection.storagePlan
        val resultBlock = ArcBlockId("storage.resultComplete")

        fun replaceResultBlock(
            transform: (ArcSafeContinuationSROAStorageBlock) -> ArcSafeContinuationSROAStorageBlock,
        ): ArcSafeContinuationSROAStoragePlan = plan.copy(
            blocks = plan.blocks + (resultBlock to transform(plan.blocks.getValue(resultBlock))),
        )

        val leaked = replaceResultBlock { block ->
            block.copy(operations = block.operations.filterNot {
                it is ArcSafeContinuationSROAStorageOperation.DestroyStorage &&
                        it.storage.name == "storage.delegate"
            })
        }
        assertTrue(ArcSafeContinuationSROAStorageVerifier.verify(leaked) is
                ArcSafeContinuationSROAStorageVerificationResult.Failure)

        val doubleTake = replaceResultBlock { block ->
            val take = block.operations.filterIsInstance<ArcSafeContinuationSROAStorageOperation.Take>()
                .single { it.storage.name == "storage.result" }
            block.copy(operations = block.operations + take.copy(result = ArcValue("taken.twice")))
        }
        assertTrue(ArcSafeContinuationSROAStorageVerifier.verify(doubleTake) is
                ArcSafeContinuationSROAStorageVerificationResult.Failure)

        val failureBlock = ArcBlockId("storage.resultFailure")
        val unbalancedFailure = plan.copy(blocks = plan.blocks + (
                failureBlock to plan.blocks.getValue(failureBlock).copy(
                    operations = plan.blocks.getValue(failureBlock).operations.dropLast(1),
                )
                ))
        assertTrue(ArcSafeContinuationSROAStorageVerifier.verify(unbalancedFailure) is
                ArcSafeContinuationSROAStorageVerificationResult.Failure)
    }

    @Test
    fun everyNonProductionModeFallsBack() {
        val candidate = validCandidate()
        val mode = candidate.mode
        listOf(
            mode.copy(arcEnabled = false), mode.copy(linuxX64 = false),
            mode.copy(finalBinary = false), mode.copy(optimizationsEnabled = false),
            mode.copy(debugInfoDisabled = false), mode.copy(diagnosticsDisabled = false),
            mode.copy(sanitizerDisabled = false), mode.copy(coverageDisabled = false),
            mode.copy(nonSuspendFunction = false, exactLoweredSuspendFunction = false),
            mode.copy(nonExternalFunction = false),
        ).forEach { rejectedMode -> assertRejected(
            candidate.copy(mode = rejectedMode),
            ArcSafeContinuationSROARejectionReason.UnsupportedCompilationMode,
        ) }
    }

    @Test
    fun exactLoweredSuspendContainerIsAllowedOnlyByItsLocalSynchronousProof() {
        val candidate = validCandidate()
        val lowered = candidate.copy(mode = candidate.mode.copy(
            nonSuspendFunction = false,
            exactLoweredSuspendFunction = true,
        ))
        assertNull(ArcSafeContinuationSROAAnalysis.select(lowered).rejection)
        assertRejected(
            lowered.copy(lifetime = lowered.lifetime.copy(noSuspensionPoint = false)),
            ArcSafeContinuationSROARejectionReason.UnsupportedBoundary,
        )
        assertRejected(
            lowered.copy(lifetime = lowered.lifetime.copy(noBackedgeOrCoroutineStateTransition = false)),
            ArcSafeContinuationSROARejectionReason.UnsupportedBoundary,
        )
        assertRejected(
            lowered.copy(lifetime = lowered.lifetime.copy(noCoroutineSuspendedReturn = false)),
            ArcSafeContinuationSROARejectionReason.UnsupportedBoundary,
        )
    }

    @Test
    fun everyDeclarationIdentityIsMandatory() {
        val candidate = validCandidate()
        val identity = candidate.identity
        listOf(
            identity.copy(exactStdlibLibrary = false),
            identity.copy(exactSafeContinuationClass = false),
            identity.copy(exactConstructor = false),
            identity.copy(exactResumeWith = false),
            identity.copy(exactGetOrThrow = false),
            identity.copy(exactDelegateField = false),
            identity.copy(exactResultRefField = false),
            identity.copy(declarationsHaveStableSignatures = false),
        ).forEach { rejectedIdentity -> assertRejected(
            candidate.copy(identity = rejectedIdentity),
            ArcSafeContinuationSROARejectionReason.DeclarationIdentityMismatch,
        ) }
    }

    @Test
    fun escapingAliasCaptureAndMultipleResumeFallBack() {
        val candidate = validCandidate()
        val lifetime = candidate.lifetime
        listOf(
            lifetime.copy(allocationIsUniqueLocalDefinition = false),
            lifetime.copy(allocationHasNoAliases = false),
            lifetime.copy(allTransitiveUsesEnumerated = false),
            lifetime.copy(exactlyOneDirectResumeWith = false),
            lifetime.copy(exactlyOneDirectGetOrThrow = false),
            lifetime.copy(noNestedFunctionCapture = false),
        ).forEach { rejectedLifetime -> assertRejected(
            candidate.copy(lifetime = rejectedLifetime),
            ArcSafeContinuationSROARejectionReason.EscapingAllocation,
        ) }
    }

    @Test
    fun resumeMustDominateOnePostdominatingGetInOneLinearRegion() {
        val candidate = validCandidate()
        val lifetime = candidate.lifetime
        listOf(
            lifetime.copy(resumeDominatesGetOnEveryNormalPath = false),
            lifetime.copy(getPostDominatesResumeNormalSuccessor = false),
            lifetime.copy(structurallyLinearSingleEntryRegion = false),
            lifetime.copy(synchronousResumeProven = false),
        ).forEach { rejectedLifetime -> assertRejected(
            candidate.copy(lifetime = rejectedLifetime),
            ArcSafeContinuationSROARejectionReason.InvalidControlFlow,
        ) }
    }

    @Test
    fun realSuspensionAndDelegateResumePathsAreNeverScalarized() {
        val candidate = validCandidate()
        assertRejected(
            candidate.copy(lifetime = candidate.lifetime.copy(suspendedStateBranchUnreachable = false)),
            ArcSafeContinuationSROARejectionReason.SuspensionOrDelegatePathReachable,
        )
        assertRejected(
            candidate.copy(lifetime = candidate.lifetime.copy(delegateResumeBranchUnreachable = false)),
            ArcSafeContinuationSROARejectionReason.SuspensionOrDelegatePathReachable,
        )
    }

    @Test
    fun workerForeignTryAndSuspensionBoundariesFallBack() {
        val candidate = validCandidate()
        val lifetime = candidate.lifetime
        listOf(
            lifetime.copy(noThreadOrWorkerEscape = false),
            lifetime.copy(noForeignOrExternalEscape = false),
            lifetime.copy(noTryOrExceptionalRegionEscape = false),
            lifetime.copy(noSuspensionPoint = false),
            lifetime.copy(noBackedgeOrCoroutineStateTransition = false),
            lifetime.copy(noCoroutineSuspendedReturn = false),
        ).forEach { rejectedLifetime -> assertRejected(
            candidate.copy(lifetime = rejectedLifetime),
            ArcSafeContinuationSROARejectionReason.UnsupportedBoundary,
        ) }
        listOf(
            ArcSafeContinuationSROAEffect.ThreadOrWorkerTransfer,
            ArcSafeContinuationSROAEffect.ForeignOrExternalCall,
            ArcSafeContinuationSROAEffect.TryRegion,
            ArcSafeContinuationSROAEffect.Suspension,
            ArcSafeContinuationSROAEffect.UserCall,
            ArcSafeContinuationSROAEffect.VirtualOrInterfaceCall,
            ArcSafeContinuationSROAEffect.NestedFunction,
            ArcSafeContinuationSROAEffect.Unknown,
        ).forEach { effect -> assertRejected(
            candidate.copy(effects = candidate.effects + effect),
            ArcSafeContinuationSROARejectionReason.UnsupportedEffect,
        ) }
    }

    @Test
    fun ordinaryHeapDelegateAndResultKeepPlusOneOwnership() {
        val candidate = validCandidate()
        assertTrue(candidate.ownership.delegateIsOrdinaryHeapReference)
        assertTrue(candidate.ownership.resultMayBeOrdinaryHeapReference)
        assertNull(ArcSafeContinuationSROAAnalysis.select(candidate).rejection)

        val ownership = candidate.ownership
        listOf(
            ownership.copy(delegateIsOrdinaryHeapReference = false),
            ownership.copy(resultMayBeOrdinaryHeapReference = false),
            ownership.copy(interceptedCallProducesOwnedPlusOne = false),
            ownership.copy(delegateScalarConsumesInterceptedPlusOne = false),
            ownership.copy(resultBoxProducesOwnedPlusOne = false),
            ownership.copy(resultScalarConsumesBoxPlusOne = false),
            ownership.copy(undecidedStateIsImmortal = false),
            ownership.copy(freezableAtomicIdentityDoesNotEscape = false),
            ownership.copy(storesRetainBeforeReplacing = false),
            ownership.copy(normalResultIsMovedExactlyOnce = false),
            ownership.copy(successOnlyResultProven = false),
            ownership.copy(constructorFailureCleanupDestroysInitializedSlots = false),
            ownership.copy(noImmortalInferenceForDelegateOrResult = false),
        ).forEach { rejectedOwnership -> assertRejected(
            candidate.copy(ownership = rejectedOwnership),
            ArcSafeContinuationSROARejectionReason.InvalidOrdinaryHeapOwnership,
        ) }
    }

    @Test
    fun exactEffectOrderAndStructuralIdentitiesAreSealed() {
        val candidate = validCandidate()
        assertRejected(
            candidate.copy(effects = candidate.effects.reversed()),
            ArcSafeContinuationSROARejectionReason.UnsupportedEffect,
        )
        assertRejected(
            candidate.copy(exactIdentityBindings = candidate.exactIdentityBindings.dropLast(1) + candidate.functionBinding),
            ArcSafeContinuationSROARejectionReason.DuplicateStructuralIdentity,
        )
    }

    @Test
    fun emissionLedgerRejectsDriftDuplicatesAndIncompleteConsumption() {
        val selection = ArcSafeContinuationSROAAnalysis.select(validCandidate()).selection!!
        val candidate = selection.candidate
        val alien = Any()

        expectFailure("allocation identity") {
            ArcSafeContinuationSROAConsumptionLedger(selection)
                .consumeAllocation(alien, candidate.localBinding, candidate.constructorBinding)
        }
        expectFailure("resume identity") {
            ArcSafeContinuationSROAConsumptionLedger(selection)
                .consumeResume(alien, candidate.resumeResultBinding)
        }
        expectFailure("intercepted identity") {
            ArcSafeContinuationSROAConsumptionLedger(selection).consumeStructuralCalls(
                alien,
                candidate.resumeValueProducerBinding,
                candidate.resultCompanionBinding,
                candidate.resultBoxBinding,
                candidate.resultConstructorBinding,
                candidate.unitInstanceBindings,
            )
        }
        expectFailure("structural Unit identity") {
            ArcSafeContinuationSROAConsumptionLedger(selection).consumeStructuralCalls(
                candidate.interceptedBinding,
                candidate.resumeValueProducerBinding,
                candidate.resultCompanionBinding,
                candidate.resultBoxBinding,
                candidate.resultConstructorBinding,
                listOf(alien, candidate.unitInstanceBindings.last()),
            )
        }
        expectFailure("getOrThrow identity") {
            ArcSafeContinuationSROAConsumptionLedger(selection).consumeGetOrThrow(alien)
        }
        expectFailure("complete identity inventory") {
            ArcSafeContinuationSROAConsumptionLedger(selection)
                .consumeAuthenticatedInventory(candidate.exactIdentityBindings.dropLast(1) + alien)
        }
        expectFailure("reduction-site inventory") {
            ArcSafeContinuationSROAConsumptionLedger(selection)
                .consumeReductionSites(selection.reduction.sites.dropLast(1))
        }
        expectFailure("incomplete") { ArcSafeContinuationSROAConsumptionLedger(selection).verifyComplete() }

        val ledger = ArcSafeContinuationSROAConsumptionLedger(selection)
        ledger.consumeAllocation(candidate.allocationBinding, candidate.localBinding, candidate.constructorBinding)
        ledger.consumeStructuralCalls(
            candidate.interceptedBinding,
            candidate.resumeValueProducerBinding,
            candidate.resultCompanionBinding,
            candidate.resultBoxBinding,
            candidate.resultConstructorBinding,
            candidate.unitInstanceBindings,
        )
        ledger.consumeResume(candidate.resumeBinding, candidate.resumeResultBinding)
        ledger.consumeGetOrThrow(candidate.getOrThrowBinding)
        expectFailure("incomplete") { ledger.verifyComplete() }
        ledger.consumeAuthenticatedInventory(candidate.exactIdentityBindings)
        expectFailure("incomplete") { ledger.verifyComplete() }
        ledger.consumeReductionSites(selection.reduction.sites)
        ledger.verifyComplete()
        expectFailure("consumed twice") { ledger.consumeGetOrThrow(candidate.getOrThrowBinding) }
        expectFailure("consumed twice") { ledger.consumeReductionSites(selection.reduction.sites) }
    }

    private fun validCandidate(): ArcSafeContinuationSROACandidate<Any> =
        ArcSafeContinuationSROACandidate(
            functionBinding = Any(), allocationBinding = Any(), localBinding = Any(),
            constructorBinding = Any(), delegateBinding = Any(), resumeBinding = Any(),
            resumeResultBinding = Any(), getOrThrowBinding = Any(),
            scalarDelegateSlotBinding = Any(), scalarStateSlotBinding = Any(),
            scalarResultSlotBinding = Any(), interceptedBinding = Any(),
            resumeValueProducerBinding = Any(), resultCompanionBinding = Any(),
            resultBoxBinding = Any(), resultConstructorBinding = Any(),
            unitInstanceBindings = listOf(Any(), Any()),
            mode = ArcSafeContinuationSROAMode(
                true, true, true, true, true, true, true, true, true, false, true,
            ),
            identity = ArcSafeContinuationSROAIdentityProof(
                true, true, true, true, true, true, true, true,
            ),
            lifetime = ArcSafeContinuationSROALifetimeProof(
                allocationIsUniqueLocalDefinition = true,
                allocationHasNoAliases = true,
                allTransitiveUsesEnumerated = true,
                exactlyOneDirectResumeWith = true,
                exactlyOneDirectGetOrThrow = true,
                resumeDominatesGetOnEveryNormalPath = true,
                getPostDominatesResumeNormalSuccessor = true,
                structurallyLinearSingleEntryRegion = true,
                synchronousResumeProven = true,
                suspendedStateBranchUnreachable = true,
                delegateResumeBranchUnreachable = true,
                noNestedFunctionCapture = true,
                noThreadOrWorkerEscape = true,
                noForeignOrExternalEscape = true,
                noTryOrExceptionalRegionEscape = true,
                noSuspensionPoint = true,
                noBackedgeOrCoroutineStateTransition = true,
                noCoroutineSuspendedReturn = true,
            ),
            ownership = ArcSafeContinuationSROAOwnershipProof(
                true, true, true, true, true, true, true, true, true, true, true, true, true,
            ),
            effects = listOf(
                ArcSafeContinuationSROAEffect.InterceptDelegate,
                ArcSafeContinuationSROAEffect.ConstructExactSafeContinuation,
                ArcSafeContinuationSROAEffect.InitializeScalarState,
                ArcSafeContinuationSROAEffect.ProducePrimitiveResumeValue,
                ArcSafeContinuationSROAEffect.LoadResultCompanion,
                ArcSafeContinuationSROAEffect.BoxResumeValue,
                ArcSafeContinuationSROAEffect.ConstructResultSuccess,
                ArcSafeContinuationSROAEffect.DirectResumeWith,
                ArcSafeContinuationSROAEffect.ReplaceScalarResult,
                ArcSafeContinuationSROAEffect.StructuralUnit,
                ArcSafeContinuationSROAEffect.StructuralUnit,
                ArcSafeContinuationSROAEffect.DirectGetOrThrow,
                ArcSafeContinuationSROAEffect.ConsumeScalarResult,
                ArcSafeContinuationSROAEffect.DestroyScalarDelegate,
            ),
        )

    private fun assertRejected(
        candidate: ArcSafeContinuationSROACandidate<Any>,
        reason: ArcSafeContinuationSROARejectionReason,
    ) {
        val result = ArcSafeContinuationSROAAnalysis.select(candidate)
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
