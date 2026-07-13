/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

/** Production boundary for the exact RestrictedContinuationImpl.context getter slice. */
internal data class ArcCoroutineEmptyContextReturnMode(
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

/** Declaration identities that an IR adapter must prove by symbol identity, never by name alone. */
internal data class ArcCoroutineEmptyContextReturnIdentityProof(
    val exactStdlibLibrary: Boolean,
    val exactRestrictedContinuationImplClass: Boolean,
    val exactContextGetterOverride: Boolean,
    val exactCoroutineContextReturnType: Boolean,
    val exactEmptyCoroutineContextObject: Boolean,
    val finalPermanentObject: Boolean,
    val runtimeAllocatesObjectAsImmortal: Boolean,
)

/** Closed lowered-body shape; any additional ownership-observable operation rejects the slice. */
internal data class ArcCoroutineEmptyContextReturnBodyProof(
    val singleEntryBlock: Boolean,
    val singleReturn: Boolean,
    val returnTargetsExactGetter: Boolean,
    val returnValueIsExactObjectLoad: Boolean,
    val noReceiverOrParameterUse: Boolean,
    val noMutableLoad: Boolean,
    val noNestedDeclaration: Boolean,
    val noExceptionalEdge: Boolean,
)

internal enum class ArcCoroutineEmptyContextReturnEffect {
    PermanentObjectLoad,
    ReturnSlotProjection,
    MutableLoad,
    HeapStore,
    UserCall,
    VirtualCall,
    ExternalCall,
    ForeignCall,
    Suspension,
    TryRegion,
    Unknown,
}

/** Opaque bindings keep a later IR adapter and code generator tied to the exact selected nodes. */
internal data class ArcCoroutineEmptyContextReturnCandidate<T : Any>(
    val getterBinding: T,
    val objectBinding: T,
    val returnBinding: T,
    val mode: ArcCoroutineEmptyContextReturnMode,
    val identity: ArcCoroutineEmptyContextReturnIdentityProof,
    val body: ArcCoroutineEmptyContextReturnBodyProof,
    val effects: List<ArcCoroutineEmptyContextReturnEffect>,
)

internal data class ArcCoroutineEmptyContextReturnReduction(
    val updateReturnRefs: Int,
    val addHeapRefs: Int,
)

internal data class ArcCoroutineEmptyContextReturnSelection<T : Any>(
    val candidate: ArcCoroutineEmptyContextReturnCandidate<T>,
    val ownershipProof: ArcFunctionPlan,
    val reduction: ArcCoroutineEmptyContextReturnReduction = ArcCoroutineEmptyContextReturnReduction(
        updateReturnRefs = 1,
        addHeapRefs = 1,
    ),
)

internal enum class ArcCoroutineEmptyContextReturnRejectionReason {
    UnsupportedCompilationMode,
    DeclarationIdentityMismatch,
    LoweredBodyShapeMismatch,
    UnsupportedEffect,
    OwnershipVerifierRejected,
}

internal data class ArcCoroutineEmptyContextReturnAnalysisResult<T : Any>(
    val selection: ArcCoroutineEmptyContextReturnSelection<T>?,
    val rejection: ArcCoroutineEmptyContextReturnRejectionReason?,
)

/**
 * Selects the semantic proof for one exact hot getter:
 *
 *     RestrictedContinuationImpl.context get() = EmptyCoroutineContext
 *
 * The runtime represents this stdlib object as permanent. Returning its pointer through the
 * caller's result slot therefore satisfies the owned-result ABI without retain/release traffic.
 * The selector intentionally accepts no general "object-looking" or name-based case.
 */
internal object ArcCoroutineEmptyContextReturnAnalysis {
    fun <T : Any> select(
        candidate: ArcCoroutineEmptyContextReturnCandidate<T>,
    ): ArcCoroutineEmptyContextReturnAnalysisResult<T> {
        if (!candidate.mode.isProductionArcMode()) {
            return rejected(ArcCoroutineEmptyContextReturnRejectionReason.UnsupportedCompilationMode)
        }
        if (!candidate.identity.isExactPermanentEmptyContext()) {
            return rejected(ArcCoroutineEmptyContextReturnRejectionReason.DeclarationIdentityMismatch)
        }
        if (!candidate.body.isExactConstantGetter()) {
            return rejected(ArcCoroutineEmptyContextReturnRejectionReason.LoweredBodyShapeMismatch)
        }
        if (candidate.effects != listOf(
                ArcCoroutineEmptyContextReturnEffect.PermanentObjectLoad,
                ArcCoroutineEmptyContextReturnEffect.ReturnSlotProjection,
            )
        ) {
            return rejected(ArcCoroutineEmptyContextReturnRejectionReason.UnsupportedEffect)
        }

        val proof = buildOwnershipProof()
        if (ArcOwnershipVerifier.verify(proof) != ArcOwnershipVerificationResult.Success) {
            return rejected(ArcCoroutineEmptyContextReturnRejectionReason.OwnershipVerifierRejected)
        }
        return ArcCoroutineEmptyContextReturnAnalysisResult(
            ArcCoroutineEmptyContextReturnSelection(candidate, proof),
            null,
        )
    }

    private fun <T : Any> rejected(reason: ArcCoroutineEmptyContextReturnRejectionReason) =
        ArcCoroutineEmptyContextReturnAnalysisResult<T>(null, reason)

    private fun buildOwnershipProof(): ArcFunctionPlan {
        val entry = ArcBlockId("entry")
        val emptyContext = ArcValue("EmptyCoroutineContext")
        return ArcFunctionPlan(
            functionName = "RestrictedContinuationImpl.context immortal return",
            entry = entry,
            entryValues = emptyMap(),
            entryInitializedStorage = emptySet(),
            blocks = linkedMapOf(
                entry to ArcBasicBlock(
                    entry,
                    listOf(ArcOperation.Define(emptyContext, ArcOwnership.Immortal)),
                    ArcTerminator.Return(emptyContext),
                ),
            ),
        )
    }
}

/** Exact-identity guard for the future raw result-slot emission. */
internal class ArcCoroutineEmptyContextReturnConsumptionLedger<T : Any>(
    private val selection: ArcCoroutineEmptyContextReturnSelection<T>,
) {
    private var consumed = false

    fun consume(getterBinding: T, objectBinding: T, returnBinding: T) {
        check(!consumed) { "EmptyCoroutineContext immortal return consumed twice" }
        check(getterBinding === selection.candidate.getterBinding) {
            "EmptyCoroutineContext getter identity drifted"
        }
        check(objectBinding === selection.candidate.objectBinding) {
            "EmptyCoroutineContext object identity drifted"
        }
        check(returnBinding === selection.candidate.returnBinding) {
            "EmptyCoroutineContext return identity drifted"
        }
        consumed = true
    }

    fun verifyComplete() {
        check(consumed) { "selected EmptyCoroutineContext immortal return was not emitted" }
    }
}

private fun ArcCoroutineEmptyContextReturnMode.isProductionArcMode(): Boolean =
    arcEnabled && linuxX64 && finalBinary && optimizationsEnabled && debugInfoDisabled &&
            diagnosticsDisabled && sanitizerDisabled && coverageDisabled && nonSuspendFunction &&
            nonExternalFunction

private fun ArcCoroutineEmptyContextReturnIdentityProof.isExactPermanentEmptyContext(): Boolean =
    exactStdlibLibrary && exactRestrictedContinuationImplClass && exactContextGetterOverride &&
            exactCoroutineContextReturnType && exactEmptyCoroutineContextObject &&
            finalPermanentObject && runtimeAllocatesObjectAsImmortal

private fun ArcCoroutineEmptyContextReturnBodyProof.isExactConstantGetter(): Boolean =
    singleEntryBlock && singleReturn && returnTargetsExactGetter && returnValueIsExactObjectLoad &&
            noReceiverOrParameterUse && noMutableLoad && noNestedDeclaration && noExceptionalEdge
