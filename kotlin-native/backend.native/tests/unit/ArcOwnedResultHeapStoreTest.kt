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

class ArcOwnedResultHeapStoreTest {
    @Test
    fun exactResultBoxResumeWithShapeBuildsBalancedNormalAndExceptionalProof() {
        val result = ArcOwnedResultHeapStoreAnalysis.select(validCandidate())

        assertNull(result.rejection)
        assertTrue(result.selection !== null)
        val selection = result.selection!!
        assertEquals(ARC_OWNED_RESULT_HEAP_STORE_HELPER, selection.runtimeHelper)
        assertEquals(ArcOwnershipVerificationResult.Success, ArcOwnershipVerifier.verify(selection.ownershipProof))

        ArcOwnedResultHeapStoreConsumptionLedger(selection).also { ledger ->
            ledger.consumeProducer(selection.candidate.producerBinding, selection.candidate.sourceSlotBinding)
            ledger.consumeStore(selection.candidate.storeBinding, selection.candidate.destinationBinding)
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
        ).forEach { mode -> assertRejected(candidate.copy(mode = mode), ArcOwnedResultHeapStoreRejectionReason.UnsupportedCompilationMode) }
    }

    @Test
    fun namesWithoutEveryExactDeclarationIdentityNeverAuthorize() {
        val candidate = validCandidate()
        val identity = candidate.identity
        listOf(
            identity.copy(exactContinuationResumeWithOverride = false),
            identity.copy(exactResultValueParameter = false),
            identity.copy(exactStdlibResultBoxProducer = false),
            identity.copy(exactCapturedRefField = false),
            identity.copy(exactMutableStrongRefValueDestination = false),
            identity.copy(destinationIsInitialized = false),
            identity.copy(destinationIsNonVolatile = false),
            identity.copy(destinationIsNotWeakOrUnowned = false),
        ).forEach { proof -> assertRejected(candidate.copy(identity = proof), ArcOwnedResultHeapStoreRejectionReason.DeclarationIdentityMismatch) }
    }

    @Test
    fun normalAndExceptionalEdgesMustProveDistinctOwnershipStates() {
        val candidate = validCandidate()
        val lifetime = candidate.lifetime
        listOf(
            lifetime.copy(producerWritesOneOwnedResultToExactSourceSlotOnEveryNormalReturn = false),
            lifetime.copy(sourceSlotDominatesStoreAndFrameCleanup = false),
            lifetime.copy(storePostDominatesProducerNormalSuccessor = false),
        ).forEach { proof -> assertRejected(candidate.copy(lifetime = proof), ArcOwnedResultHeapStoreRejectionReason.InvalidNormalResultOwnership) }
        listOf(
            lifetime.copy(producerExceptionalEdgeLeavesSourceSlotEmpty = false),
            lifetime.copy(frameCleanupPostDominatesStore = false),
        ).forEach { proof -> assertRejected(candidate.copy(lifetime = proof), ArcOwnedResultHeapStoreRejectionReason.InvalidExceptionalCleanup) }
    }

    @Test
    fun escapingMultipleUseOrAmbiguousPhysicalSlotFallsBack() {
        val candidate = validCandidate()
        assertRejected(
            candidate.copy(destinationBinding = candidate.sourceSlotBinding),
            ArcOwnedResultHeapStoreRejectionReason.AliasedPhysicalSlots,
        )
        listOf(
            candidate.lifetime.copy(producerResultHasExactlyOneUse = false),
            candidate.lifetime.copy(sourceSlotHasNoInterveningWriteOrEscape = false),
        ).forEach { proof -> assertRejected(candidate.copy(lifetime = proof), ArcOwnedResultHeapStoreRejectionReason.EscapingOrMultiplyUsedResult) }
        listOf(
            candidate.lifetime.copy(destinationOwnerRemainsGuaranteedThroughStore = false),
            candidate.lifetime.copy(destinationAddressDoesNotEscape = false),
        ).forEach { proof -> assertRejected(candidate.copy(lifetime = proof), ArcOwnedResultHeapStoreRejectionReason.InvalidDestinationLifetime) }
    }

    @Test
    fun callsSuspensionTryAndUnknownEffectsAreHardBarriers() {
        val candidate = validCandidate()
        listOf(
            ArcOwnedResultHeapStoreInterveningEffect.UserCall,
            ArcOwnedResultHeapStoreInterveningEffect.VirtualCall,
            ArcOwnedResultHeapStoreInterveningEffect.ExternalCall,
            ArcOwnedResultHeapStoreInterveningEffect.ForeignCall,
            ArcOwnedResultHeapStoreInterveningEffect.Suspension,
            ArcOwnedResultHeapStoreInterveningEffect.NestedTry,
            ArcOwnedResultHeapStoreInterveningEffect.Unknown,
        ).forEach { effect -> assertRejected(
            candidate.copy(interveningEffects = candidate.interveningEffects + effect),
            ArcOwnedResultHeapStoreRejectionReason.UnsupportedInterveningEffect,
        ) }
    }

    @Test
    fun consumptionLedgerRejectsIdentityDriftDuplicatesAndIncompleteEmission() {
        val selection = ArcOwnedResultHeapStoreAnalysis.select(validCandidate()).selection!!
        val alien = Any()

        expectFailure("producer identity") {
            ArcOwnedResultHeapStoreConsumptionLedger(selection).consumeProducer(alien, selection.candidate.sourceSlotBinding)
        }
        expectFailure("source-slot identity") {
            ArcOwnedResultHeapStoreConsumptionLedger(selection).consumeProducer(selection.candidate.producerBinding, alien)
        }
        expectFailure("heap-store identity") {
            ArcOwnedResultHeapStoreConsumptionLedger(selection).consumeStore(alien, selection.candidate.destinationBinding)
        }
        expectFailure("destination identity") {
            ArcOwnedResultHeapStoreConsumptionLedger(selection).consumeStore(selection.candidate.storeBinding, alien)
        }
        expectFailure("incomplete") { ArcOwnedResultHeapStoreConsumptionLedger(selection).verifyComplete() }

        val ledger = ArcOwnedResultHeapStoreConsumptionLedger(selection)
        ledger.consumeProducer(selection.candidate.producerBinding, selection.candidate.sourceSlotBinding)
        expectFailure("twice") { ledger.consumeProducer(selection.candidate.producerBinding, selection.candidate.sourceSlotBinding) }
    }

    private fun validCandidate(): ArcOwnedResultHeapStoreCandidate<Any> = ArcOwnedResultHeapStoreCandidate(
        functionBinding = Any(),
        producerBinding = Any(),
        storeBinding = Any(),
        sourceSlotBinding = Any(),
        destinationBinding = Any(),
        mode = ArcOwnedResultHeapStoreMode(
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
        identity = ArcOwnedResultHeapStoreIdentityProof(
            exactContinuationResumeWithOverride = true,
            exactResultValueParameter = true,
            exactStdlibResultBoxProducer = true,
            exactCapturedRefField = true,
            exactMutableStrongRefValueDestination = true,
            destinationIsInitialized = true,
            destinationIsNonVolatile = true,
            destinationIsNotWeakOrUnowned = true,
        ),
        lifetime = ArcOwnedResultHeapStoreLifetimeProof(
            producerWritesOneOwnedResultToExactSourceSlotOnEveryNormalReturn = true,
            producerExceptionalEdgeLeavesSourceSlotEmpty = true,
            sourceSlotDominatesStoreAndFrameCleanup = true,
            storePostDominatesProducerNormalSuccessor = true,
            producerResultHasExactlyOneUse = true,
            sourceSlotHasNoInterveningWriteOrEscape = true,
            destinationOwnerRemainsGuaranteedThroughStore = true,
            destinationAddressDoesNotEscape = true,
            frameCleanupPostDominatesStore = true,
        ),
        interveningEffects = listOf(
            ArcOwnedResultHeapStoreInterveningEffect.LifetimeConstraintCheck,
            ArcOwnedResultHeapStoreInterveningEffect.ReceiverBitcast,
            ArcOwnedResultHeapStoreInterveningEffect.DestinationAddressProjection,
        ),
    )

    private fun assertRejected(
        candidate: ArcOwnedResultHeapStoreCandidate<Any>,
        reason: ArcOwnedResultHeapStoreRejectionReason,
    ) {
        val result = ArcOwnedResultHeapStoreAnalysis.select(candidate)
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
