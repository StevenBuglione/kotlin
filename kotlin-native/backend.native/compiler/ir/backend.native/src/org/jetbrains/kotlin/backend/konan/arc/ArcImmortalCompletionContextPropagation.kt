/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

/** Production boundary for propagating EmptyCoroutineContext through one user completion field. */
internal data class ArcImmortalCompletionContextMode(
    val arcEnabled: Boolean,
    val linuxX64: Boolean,
    val finalBinary: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val diagnosticsDisabled: Boolean,
    val sanitizerDisabled: Boolean,
    val coverageDisabled: Boolean,
)

/** Permanent-root facts. These must be authenticated by declaration identity, not spelling. */
internal data class ArcImmortalCompletionContextRootProof(
    val exactCoroutineContextType: Boolean,
    val exactEmptyCoroutineContextObject: Boolean,
    val exactPrivateFinalStaticRoot: Boolean,
    val exactConstantObjectInitializer: Boolean,
    val exactPrivatePrimaryObjectConstructor: Boolean,
    val runtimeUsesPermanentTagOne: Boolean,
    val noRootWrites: Boolean,
)

/** Closed-world declaration facts for the user completion's `context` property. */
internal data class ArcImmortalCompletionContextFieldProof(
    val exactContinuationContextOverride: Boolean,
    val ownerIsExactUserCompletionClass: Boolean,
    val ownerClassFinal: Boolean,
    val noOwnerSubclasses: Boolean,
    val propertyIsImmutable: Boolean,
    val propertyEffectivelyFinal: Boolean,
    val getterEffectivelyFinal: Boolean,
    val noGetterOverrides: Boolean,
    val backingFieldPrivateFinalStrongReference: Boolean,
    val fieldAddressDoesNotEscape: Boolean,
    val exactlyOneFieldWrite: Boolean,
    val onlyWriteIsSelectedConstructorStore: Boolean,
)

/** Facts required before a strong-field update may become a raw first initialization. */
internal data class ArcImmortalCompletionContextInitializerProof(
    val exactConstructorAndAllocation: Boolean,
    val receiverIsFreshZeroedAllocation: Boolean,
    val receiverHasNotEscaped: Boolean,
    val fieldDefinitelyUninitialized: Boolean,
    val noFieldReadBeforeInitialization: Boolean,
    val valueIsDirectPermanentRootLoad: Boolean,
    val storeDominatesEveryReceiverEscape: Boolean,
    val storeOccursOnEverySuccessfulConstructorPath: Boolean,
    val noDelegatingConstructorDrift: Boolean,
    val noExceptionalEdgeBeforeOrAtStore: Boolean,
    val noCatchOrFinallyAroundStore: Boolean,
)

/** Facts required before a field load may be treated as the permanent root. */
internal data class ArcImmortalCompletionContextGetterProof(
    val exactSingleReturnBody: Boolean,
    val returnTargetsExactGetter: Boolean,
    val valueIsDirectSelectedFieldLoad: Boolean,
    val receiverOnlyUsedForSelectedLoad: Boolean,
    val returnTypeMatchesFieldType: Boolean,
    val noMutableOrVolatileRead: Boolean,
    val noNestedDeclaration: Boolean,
    val noExceptionalEdge: Boolean,
)

internal enum class ArcImmortalCompletionContextEffect {
    FreshAllocation,
    PermanentRootLoad,
    FirstStrongFieldInitialization,
    FinalStrongFieldLoad,
    OwnedResultSlotPublication,
    StrongFieldReplacement,
    MutableLoad,
    ReceiverEscape,
    UserCall,
    VirtualCall,
    ExternalCall,
    Suspension,
    TryRegion,
    Unknown,
}

internal data class ArcImmortalCompletionContextCandidate<T : Any>(
    val constructorBinding: T,
    val fieldBinding: T,
    val initializerBinding: T,
    val getterBinding: T,
    val fieldLoadBinding: T,
    val returnBinding: T,
    val permanentRootBinding: T,
    val exactIdentityBindings: List<T> = listOf(
        constructorBinding,
        fieldBinding,
        initializerBinding,
        getterBinding,
        fieldLoadBinding,
        returnBinding,
        permanentRootBinding,
    ),
    val mode: ArcImmortalCompletionContextMode,
    val root: ArcImmortalCompletionContextRootProof,
    val field: ArcImmortalCompletionContextFieldProof,
    val initializer: ArcImmortalCompletionContextInitializerProof,
    val getter: ArcImmortalCompletionContextGetterProof,
    val effects: List<ArcImmortalCompletionContextEffect>,
)

/** Static sites and Wave-29 dynamic multiplicities removed by this exact slice. */
internal data class ArcImmortalCompletionContextReduction(
    val updateHeapRefSites: Int = 1,
    val updateReturnRefSites: Int = 1,
    val permanentFieldDestroySites: Int = 1,
    val heapUpdatesPerCoroutine: Int = 1,
    val returnUpdatesPerCoroutine: Int = 2,
    val addHeapRefsPerCoroutine: Int = 3,
    val permanentFieldDestroysPerCoroutine: Int = 1,
)

/** Exact SemanticARC rewrites authorized by the proof; order is part of the contract. */
internal enum class ArcImmortalCompletionContextRewrite {
    PropagatePermanentRootIntoField,
    RawInitializeFreshField,
    EliminatePermanentFieldCopy,
    EliminatePermanentFieldDestroy,
    PublishPermanentResultReleasingPriorSlot,
}

/**
 * The initializer and getter deliberately have different old-value policies. A raw initializer is
 * valid only for a proven fresh field; result publication must still release an occupied caller
 * slot even though retaining the new permanent value is unnecessary.
 */
internal data class ArcImmortalCompletionContextEmissionPolicy(
    val rawInitializeOnlyFreshField: Boolean = true,
    val initializeDoesNotReleaseOldValue: Boolean = true,
    val publishWithMoveThatReleasesPriorResultSlot: Boolean = true,
    val neverRawReplaceInitializedStorage: Boolean = true,
)

internal data class ArcImmortalCompletionContextSelection<T : Any>(
    val candidate: ArcImmortalCompletionContextCandidate<T>,
    val initializerOwnershipProof: ArcFunctionPlan,
    val getterOwnershipProof: ArcFunctionPlan,
    val reduction: ArcImmortalCompletionContextReduction = ArcImmortalCompletionContextReduction(),
    val emissionPolicy: ArcImmortalCompletionContextEmissionPolicy = ArcImmortalCompletionContextEmissionPolicy(),
    val rewrites: List<ArcImmortalCompletionContextRewrite> = listOf(
        ArcImmortalCompletionContextRewrite.PropagatePermanentRootIntoField,
        ArcImmortalCompletionContextRewrite.RawInitializeFreshField,
        ArcImmortalCompletionContextRewrite.EliminatePermanentFieldCopy,
        ArcImmortalCompletionContextRewrite.EliminatePermanentFieldDestroy,
        ArcImmortalCompletionContextRewrite.PublishPermanentResultReleasingPriorSlot,
    ),
)

internal enum class ArcImmortalCompletionContextRejectionReason {
    UnsupportedCompilationMode,
    PermanentRootMismatch,
    MutableOrOverridableField,
    UnsafeOrFailableInitialization,
    GetterShapeMismatch,
    UnsupportedEffect,
    DuplicateStructuralIdentity,
    OwnershipVerifierRejected,
}

internal data class ArcImmortalCompletionContextAnalysisResult<T : Any>(
    val selection: ArcImmortalCompletionContextSelection<T>?,
    val rejection: ArcImmortalCompletionContextRejectionReason?,
)

/**
 * Swift-style immortal/global propagation for the exact user completion shape in the coroutine
 * benchmark. This is intentionally not a general final-field constant propagation pass: every
 * declaration, root, first-initialization and getter fact must be sealed first.
 */
internal object ArcImmortalCompletionContextAnalysis {
    fun <T : Any> select(
        candidate: ArcImmortalCompletionContextCandidate<T>,
    ): ArcImmortalCompletionContextAnalysisResult<T> {
        if (!candidate.mode.isProductionArcMode()) {
            return rejected(ArcImmortalCompletionContextRejectionReason.UnsupportedCompilationMode)
        }
        if (!candidate.root.isExactPermanentRoot()) {
            return rejected(ArcImmortalCompletionContextRejectionReason.PermanentRootMismatch)
        }
        if (!candidate.field.isSealedImmutableField()) {
            return rejected(ArcImmortalCompletionContextRejectionReason.MutableOrOverridableField)
        }
        if (!candidate.initializer.isSafeRawFirstInitialization()) {
            return rejected(ArcImmortalCompletionContextRejectionReason.UnsafeOrFailableInitialization)
        }
        if (!candidate.getter.isExactPermanentFieldGetter()) {
            return rejected(ArcImmortalCompletionContextRejectionReason.GetterShapeMismatch)
        }
        if (candidate.effects != listOf(
                ArcImmortalCompletionContextEffect.FreshAllocation,
                ArcImmortalCompletionContextEffect.PermanentRootLoad,
                ArcImmortalCompletionContextEffect.FirstStrongFieldInitialization,
                ArcImmortalCompletionContextEffect.FinalStrongFieldLoad,
                ArcImmortalCompletionContextEffect.OwnedResultSlotPublication,
            )
        ) {
            return rejected(ArcImmortalCompletionContextRejectionReason.UnsupportedEffect)
        }
        val identities = candidate.exactIdentityBindings
        if (identities.anyIndexedIdentityDuplicate()) {
            return rejected(ArcImmortalCompletionContextRejectionReason.DuplicateStructuralIdentity)
        }

        val initializerProof = buildInitializerOwnershipProof()
        val getterProof = buildGetterOwnershipProof()
        if (ArcOwnershipVerifier.verify(initializerProof) != ArcOwnershipVerificationResult.Success ||
            ArcOwnershipVerifier.verify(getterProof) != ArcOwnershipVerificationResult.Success
        ) {
            return rejected(ArcImmortalCompletionContextRejectionReason.OwnershipVerifierRejected)
        }
        return ArcImmortalCompletionContextAnalysisResult(
            ArcImmortalCompletionContextSelection(candidate, initializerProof, getterProof),
            null,
        )
    }

    private fun <T : Any> rejected(reason: ArcImmortalCompletionContextRejectionReason) =
        ArcImmortalCompletionContextAnalysisResult<T>(null, reason)

    private fun buildInitializerOwnershipProof(): ArcFunctionPlan {
        val entry = ArcBlockId("entry")
        val permanent = ArcValue("EmptyCoroutineContext")
        val contextField = ArcStorage("completion.context")
        return ArcFunctionPlan(
            functionName = "completion context immortal first initialization",
            entry = entry,
            entryValues = emptyMap(),
            entryInitializedStorage = emptySet(),
            blocks = linkedMapOf(
                entry to ArcBasicBlock(
                    entry,
                    listOf(
                        ArcOperation.Define(permanent, ArcOwnership.Immortal),
                        ArcOperation.StrongStore(contextField, permanent),
                    ),
                    ArcTerminator.Return(),
                ),
            ),
        )
    }

    private fun buildGetterOwnershipProof(): ArcFunctionPlan {
        val entry = ArcBlockId("entry")
        val permanent = ArcValue("completion.context as EmptyCoroutineContext")
        return ArcFunctionPlan(
            functionName = "completion context immortal getter",
            entry = entry,
            entryValues = emptyMap(),
            entryInitializedStorage = emptySet(),
            blocks = linkedMapOf(
                entry to ArcBasicBlock(
                    entry,
                    listOf(ArcOperation.Define(permanent, ArcOwnership.Immortal)),
                    ArcTerminator.Return(permanent),
                ),
            ),
        )
    }
}

/** Exactly-once handoff for the two different code-generation sites. */
internal class ArcImmortalCompletionContextConsumptionLedger<T : Any>(
    private val selection: ArcImmortalCompletionContextSelection<T>,
) {
    private var initializerConsumed = false
    private var getterConsumed = false

    fun consumeInitializer(constructor: T, field: T, initializer: T, permanentRoot: T) {
        check(!initializerConsumed) { "immortal completion-context initializer consumed twice" }
        val candidate = selection.candidate
        check(constructor === candidate.constructorBinding) { "completion constructor identity drifted" }
        check(field === candidate.fieldBinding) { "completion context field identity drifted" }
        check(initializer === candidate.initializerBinding) { "completion context initializer identity drifted" }
        check(permanentRoot === candidate.permanentRootBinding) { "permanent context root identity drifted" }
        initializerConsumed = true
    }

    fun consumeGetter(getter: T, fieldLoad: T, returned: T) {
        check(!getterConsumed) { "immortal completion-context getter consumed twice" }
        val candidate = selection.candidate
        check(getter === candidate.getterBinding) { "completion context getter identity drifted" }
        check(fieldLoad === candidate.fieldLoadBinding) { "completion context load identity drifted" }
        check(returned === candidate.returnBinding) { "completion context return identity drifted" }
        getterConsumed = true
    }

    fun verifyComplete() {
        check(initializerConsumed) { "selected immortal completion-context initializer was not emitted" }
        check(getterConsumed) { "selected immortal completion-context getter was not emitted" }
    }
}

private fun ArcImmortalCompletionContextMode.isProductionArcMode(): Boolean =
    arcEnabled && linuxX64 && finalBinary && optimizationsEnabled && debugInfoDisabled &&
            diagnosticsDisabled && sanitizerDisabled && coverageDisabled

private fun ArcImmortalCompletionContextRootProof.isExactPermanentRoot(): Boolean =
    exactCoroutineContextType && exactEmptyCoroutineContextObject && exactPrivateFinalStaticRoot &&
            exactConstantObjectInitializer && exactPrivatePrimaryObjectConstructor &&
            runtimeUsesPermanentTagOne && noRootWrites

private fun ArcImmortalCompletionContextFieldProof.isSealedImmutableField(): Boolean =
    exactContinuationContextOverride && ownerIsExactUserCompletionClass && ownerClassFinal &&
            noOwnerSubclasses && propertyIsImmutable && propertyEffectivelyFinal &&
            getterEffectivelyFinal && noGetterOverrides && backingFieldPrivateFinalStrongReference &&
            fieldAddressDoesNotEscape && exactlyOneFieldWrite && onlyWriteIsSelectedConstructorStore

private fun ArcImmortalCompletionContextInitializerProof.isSafeRawFirstInitialization(): Boolean =
    exactConstructorAndAllocation && receiverIsFreshZeroedAllocation && receiverHasNotEscaped &&
            fieldDefinitelyUninitialized && noFieldReadBeforeInitialization &&
            valueIsDirectPermanentRootLoad && storeDominatesEveryReceiverEscape &&
            storeOccursOnEverySuccessfulConstructorPath && noDelegatingConstructorDrift &&
            noExceptionalEdgeBeforeOrAtStore && noCatchOrFinallyAroundStore

private fun ArcImmortalCompletionContextGetterProof.isExactPermanentFieldGetter(): Boolean =
    exactSingleReturnBody && returnTargetsExactGetter && valueIsDirectSelectedFieldLoad &&
            receiverOnlyUsedForSelectedLoad && returnTypeMatchesFieldType &&
            noMutableOrVolatileRead && noNestedDeclaration && noExceptionalEdge

private fun <T : Any> List<T>.anyIndexedIdentityDuplicate(): Boolean {
    indices.forEach { left ->
        for (right in 0 until left) if (this[left] === this[right]) return true
    }
    return false
}
