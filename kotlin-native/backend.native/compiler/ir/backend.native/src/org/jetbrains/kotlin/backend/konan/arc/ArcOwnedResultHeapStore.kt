/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

internal const val ARC_OWNED_RESULT_HEAP_STORE_HELPER = "MoveReferenceIntoHeapSlotArc"

/** Production-only gate. Every other mode retains the ordinary copy/store/destroy sequence. */
internal data class ArcOwnedResultHeapStoreMode(
    val arcEnabled: Boolean,
    val linuxX64: Boolean,
    val finalBinary: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val diagnosticsDisabled: Boolean,
    val sanitizerDisabled: Boolean,
    val coverageDisabled: Boolean,
    val nonSuspendFunction: Boolean,
    val nonExternalFunction: Boolean,
)

/** Exact declaration and lowered-body identities required by the first Result-box slice. */
internal data class ArcOwnedResultHeapStoreIdentityProof(
    val exactContinuationResumeWithOverride: Boolean,
    val exactResultValueParameter: Boolean,
    val exactStdlibResultBoxProducer: Boolean,
    val exactCapturedRefField: Boolean,
    val exactMutableStrongRefValueDestination: Boolean,
    val destinationIsInitialized: Boolean,
    val destinationIsNonVolatile: Boolean,
    val destinationIsNotWeakOrUnowned: Boolean,
)

/** Effects allowed between the throwing producer's normal successor and the consuming store. */
internal enum class ArcOwnedResultHeapStoreInterveningEffect {
    LifetimeConstraintCheck,
    ReceiverBitcast,
    DestinationAddressProjection,
    UserCall,
    VirtualCall,
    ExternalCall,
    ForeignCall,
    Suspension,
    NestedTry,
    Unknown,
}

/**
 * Normal and exceptional lifetime-frontier proof.
 *
 * Ownership exists only after the producer's normal successor. The selected store is the single
 * normal-edge consuming frontier; the exceptional successor must retain the ordinary zeroed-frame
 * cleanup where the source slot never acquired a +1.
 */
internal data class ArcOwnedResultHeapStoreLifetimeProof(
    val producerWritesOneOwnedResultToExactSourceSlotOnEveryNormalReturn: Boolean,
    val producerExceptionalEdgeLeavesSourceSlotEmpty: Boolean,
    val sourceSlotDominatesStoreAndFrameCleanup: Boolean,
    val storePostDominatesProducerNormalSuccessor: Boolean,
    val producerResultHasExactlyOneUse: Boolean,
    val sourceSlotHasNoInterveningWriteOrEscape: Boolean,
    val destinationOwnerRemainsGuaranteedThroughStore: Boolean,
    val destinationAddressDoesNotEscape: Boolean,
    val frameCleanupPostDominatesStore: Boolean,
)

/** Identity-carrying normalized candidate produced by the Kotlin IR adapter. */
internal data class ArcOwnedResultHeapStoreCandidate<T : Any>(
    val functionBinding: T,
    val producerBinding: T,
    val storeBinding: T,
    val sourceSlotBinding: T,
    val destinationBinding: T,
    val mode: ArcOwnedResultHeapStoreMode,
    val identity: ArcOwnedResultHeapStoreIdentityProof,
    val lifetime: ArcOwnedResultHeapStoreLifetimeProof,
    val interveningEffects: List<ArcOwnedResultHeapStoreInterveningEffect>,
)

internal data class ArcOwnedResultHeapStoreSelection<T : Any>(
    val candidate: ArcOwnedResultHeapStoreCandidate<T>,
    val ownershipProof: ArcFunctionPlan,
    val runtimeHelper: String = ARC_OWNED_RESULT_HEAP_STORE_HELPER,
)

internal enum class ArcOwnedResultHeapStoreRejectionReason {
    UnsupportedCompilationMode,
    DeclarationIdentityMismatch,
    AliasedPhysicalSlots,
    InvalidNormalResultOwnership,
    InvalidExceptionalCleanup,
    EscapingOrMultiplyUsedResult,
    InvalidDestinationLifetime,
    UnsupportedInterveningEffect,
    OwnershipVerifierRejected,
}

internal data class ArcOwnedResultHeapStoreAnalysisResult<T : Any>(
    val selection: ArcOwnedResultHeapStoreSelection<T>?,
    val rejection: ArcOwnedResultHeapStoreRejectionReason?,
)

/** Fail-closed semantic gate shared by a future Kotlin-IR adapter and identity-token unit tests. */
internal object ArcOwnedResultHeapStoreAnalysis {
    fun <T : Any> select(candidate: ArcOwnedResultHeapStoreCandidate<T>): ArcOwnedResultHeapStoreAnalysisResult<T> {
        if (!candidate.mode.isProductionArcMode()) return rejected(ArcOwnedResultHeapStoreRejectionReason.UnsupportedCompilationMode)
        if (!candidate.identity.isExactResultBoxResumeWithShape()) {
            return rejected(ArcOwnedResultHeapStoreRejectionReason.DeclarationIdentityMismatch)
        }
        if (candidate.sourceSlotBinding === candidate.destinationBinding) {
            return rejected(ArcOwnedResultHeapStoreRejectionReason.AliasedPhysicalSlots)
        }

        val lifetime = candidate.lifetime
        if (!lifetime.producerWritesOneOwnedResultToExactSourceSlotOnEveryNormalReturn ||
            !lifetime.sourceSlotDominatesStoreAndFrameCleanup ||
            !lifetime.storePostDominatesProducerNormalSuccessor
        ) return rejected(ArcOwnedResultHeapStoreRejectionReason.InvalidNormalResultOwnership)
        if (!lifetime.producerExceptionalEdgeLeavesSourceSlotEmpty || !lifetime.frameCleanupPostDominatesStore) {
            return rejected(ArcOwnedResultHeapStoreRejectionReason.InvalidExceptionalCleanup)
        }
        if (!lifetime.producerResultHasExactlyOneUse || !lifetime.sourceSlotHasNoInterveningWriteOrEscape) {
            return rejected(ArcOwnedResultHeapStoreRejectionReason.EscapingOrMultiplyUsedResult)
        }
        if (!lifetime.destinationOwnerRemainsGuaranteedThroughStore || !lifetime.destinationAddressDoesNotEscape) {
            return rejected(ArcOwnedResultHeapStoreRejectionReason.InvalidDestinationLifetime)
        }
        if (candidate.interveningEffects.any { !it.isNonThrowingStructuralEffect() }) {
            return rejected(ArcOwnedResultHeapStoreRejectionReason.UnsupportedInterveningEffect)
        }

        val proof = buildOwnershipProof()
        if (ArcOwnershipVerifier.verify(proof) != ArcOwnershipVerificationResult.Success) {
            return rejected(ArcOwnedResultHeapStoreRejectionReason.OwnershipVerifierRejected)
        }
        return ArcOwnedResultHeapStoreAnalysisResult(ArcOwnedResultHeapStoreSelection(candidate, proof), null)
    }

    private fun <T : Any> rejected(reason: ArcOwnedResultHeapStoreRejectionReason) =
        ArcOwnedResultHeapStoreAnalysisResult<T>(null, reason)

    private fun buildOwnershipProof(): ArcFunctionPlan {
        val entry = ArcBlockId("entry")
        val normal = ArcBlockId("producer_normal")
        val exceptional = ArcBlockId("producer_exceptional")
        val receiver = ArcValue("completion")
        val produced = ArcValue("boxedResult")
        val destination = ArcStorage("capturedRef.value")
        return ArcFunctionPlan(
            functionName = "Continuation.resumeWith owned Result-box heap transfer",
            entry = entry,
            entryValues = mapOf(receiver to ArcOwnership.Guaranteed),
            entryInitializedStorage = setOf(destination),
            blocks = linkedMapOf(
                entry to ArcBasicBlock(entry, listOf(ArcOperation.Use(receiver)), ArcTerminator.Branch(normal, exceptional)),
                normal to ArcBasicBlock(
                    normal,
                    listOf(
                        ArcOperation.Define(produced, ArcOwnership.Owned),
                        // StrongStore + Destroy is the canonical copy/store/destroy semantics. The
                        // selected emission moves the same +1 into storage and consumes it there.
                        ArcOperation.StrongStore(destination, produced),
                        ArcOperation.Destroy(produced),
                    ),
                    ArcTerminator.Return(),
                ),
                exceptional to ArcBasicBlock(exceptional, emptyList(), ArcTerminator.Throw),
            ),
        )
    }
}

/** Exact-identity consumption guard for the producer and store authorized by one selection. */
internal class ArcOwnedResultHeapStoreConsumptionLedger<T : Any>(
    private val selection: ArcOwnedResultHeapStoreSelection<T>,
) {
    private var producerConsumed = false
    private var storeConsumed = false

    fun consumeProducer(binding: T, sourceSlotBinding: T) {
        check(binding === selection.candidate.producerBinding) { "owned-result producer identity drifted" }
        check(sourceSlotBinding === selection.candidate.sourceSlotBinding) { "owned-result source-slot identity drifted" }
        check(!producerConsumed) { "owned-result producer consumed twice" }
        producerConsumed = true
    }

    fun consumeStore(binding: T, destinationBinding: T) {
        check(binding === selection.candidate.storeBinding) { "owned-result heap-store identity drifted" }
        check(destinationBinding === selection.candidate.destinationBinding) { "owned-result destination identity drifted" }
        check(!storeConsumed) { "owned-result heap store consumed twice" }
        storeConsumed = true
    }

    fun verifyComplete() {
        check(producerConsumed && storeConsumed) {
            "owned-result heap-store emission incomplete: producer=$producerConsumed, store=$storeConsumed"
        }
    }
}

private fun ArcOwnedResultHeapStoreMode.isProductionArcMode(): Boolean =
    arcEnabled && linuxX64 && finalBinary && optimizationsEnabled && debugInfoDisabled &&
            diagnosticsDisabled && sanitizerDisabled && coverageDisabled && nonSuspendFunction && nonExternalFunction

private fun ArcOwnedResultHeapStoreIdentityProof.isExactResultBoxResumeWithShape(): Boolean =
    exactContinuationResumeWithOverride && exactResultValueParameter && exactStdlibResultBoxProducer &&
            exactCapturedRefField && exactMutableStrongRefValueDestination && destinationIsInitialized &&
            destinationIsNonVolatile && destinationIsNotWeakOrUnowned

private fun ArcOwnedResultHeapStoreInterveningEffect.isNonThrowingStructuralEffect(): Boolean = when (this) {
    ArcOwnedResultHeapStoreInterveningEffect.LifetimeConstraintCheck,
    ArcOwnedResultHeapStoreInterveningEffect.ReceiverBitcast,
    ArcOwnedResultHeapStoreInterveningEffect.DestinationAddressProjection -> true
    ArcOwnedResultHeapStoreInterveningEffect.UserCall,
    ArcOwnedResultHeapStoreInterveningEffect.VirtualCall,
    ArcOwnedResultHeapStoreInterveningEffect.ExternalCall,
    ArcOwnedResultHeapStoreInterveningEffect.ForeignCall,
    ArcOwnedResultHeapStoreInterveningEffect.Suspension,
    ArcOwnedResultHeapStoreInterveningEffect.NestedTry,
    ArcOwnedResultHeapStoreInterveningEffect.Unknown -> false
}
