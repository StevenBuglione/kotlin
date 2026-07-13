/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import java.util.Collections
import java.util.IdentityHashMap

internal const val ARC_CREATE_SCOPED_CSTRING = "CreateCStringFromStringWithReplacement"
internal const val ARC_DISPOSE_SCOPED_CSTRING = "DisposeCString"

/** Fail-closed boundary for caching one immutable String's UTF-8 buffer across loop iterations. */
internal data class ArcScopedCStringLoopCacheMode(
    val arcEnabled: Boolean,
    val linuxX64: Boolean,
    val finalBinary: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val diagnosticsDisabled: Boolean,
    val sanitizerDisabled: Boolean,
    val coverageDisabled: Boolean,
    val cLanguage: Boolean,
    val nonSuspendFunction: Boolean,
    val sourceFunction: Boolean,
)

internal fun ArcScopedCStringLoopCacheMode.isAuthorized(): Boolean =
    arcEnabled && linuxX64 && finalBinary && optimizationsEnabled && debugInfoDisabled &&
            diagnosticsDisabled && sanitizerDisabled && coverageDisabled && cLanguage &&
            nonSuspendFunction && sourceFunction

/** Declaration facts authenticated by symbol, annotation, signature, and KLIB provenance. */
internal data class ArcScopedCStringLoopCacheDeclarationProof<T : Any>(
    val interopLibraryBinding: T,
    val foreignFunctionBinding: T,
    val cStringParameterBinding: T,
    val exactInteropLibraryIdentity: Boolean,
    val exactCCallSymbolAnnotation: Boolean,
    val exactSerializedNoCallbackAnnotation: Boolean,
    val exactSingleFixedCStringParameter: Boolean,
    val constCharPointee: Boolean,
    val nonVariadicCFunction: Boolean,
    val primitiveOrUnitResult: Boolean,
    val noReceiverOrFunctionPointerDispatch: Boolean,
)

/** One natural-loop web whose String identity may change less often than the C call executes. */
internal data class ArcScopedCStringLoopCacheLifetimeProof<T : Any>(
    val functionBinding: T,
    val loopBinding: T,
    val sourceSlotBinding: T,
    val sourceLoadBinding: T,
    val cacheSourceSlotBinding: T,
    val cachePointerSlotBinding: T,
    val identityGuardBinding: T,
    val conversionBinding: T,
    val foreignCallBinding: T,
    val replacementDisposeBinding: T,
    val normalExitDisposeBinding: T,
    val exceptionalExitDisposeBinding: T,
    val exactSingleNaturalLoop: Boolean,
    val loopHeaderDominatesEveryCall: Boolean,
    val exactStrongStringSourceSlot: Boolean,
    val sourceEvaluatedOncePerIteration: Boolean,
    val identityGuardDominatesConversionAndCall: Boolean,
    val everySourceDefinitionFlowsThroughGuard: Boolean,
    val noSourceDefinitionBetweenGuardAndCall: Boolean,
    val cachedSourceStronglyOwnsConvertedIdentity: Boolean,
    val pointerHasOneNonEscapingCallUsePerIteration: Boolean,
    val noCallbackOrSuspensionBoundary: Boolean,
    val normalCleanupPostDominatesLoop: Boolean,
    val exceptionalCleanupCoversEveryLoopExit: Boolean,
    val replacementCleanupIsExactlyOnce: Boolean,
)

/** Observable behavior that must remain identical to per-call scoped conversion. */
internal data class ArcScopedCStringLoopCacheSemanticsProof(
    val referenceIdentityComparison: Boolean,
    val exactUtf8ReplacementConverter: Boolean,
    val embeddedNulBehaviorPreserved: Boolean,
    val constPointeePreventsPersistentMutation: Boolean,
    val createNewBeforeDisposeOld: Boolean,
    val retainNewSourceBeforeReleaseOld: Boolean,
    val conversionFailureLeavesOldCacheOwned: Boolean,
    val argumentEvaluationOrderPreserved: Boolean,
    val foreignCallExceptionalSuccessorPreserved: Boolean,
)

internal data class ArcScopedCStringLoopCacheCandidate<T : Any>(
    val mode: ArcScopedCStringLoopCacheMode,
    val declarations: ArcScopedCStringLoopCacheDeclarationProof<T>,
    val lifetime: ArcScopedCStringLoopCacheLifetimeProof<T>,
    val semantics: ArcScopedCStringLoopCacheSemanticsProof,
    val completeLoweredIRWalk: Boolean,
)

internal enum class ArcScopedCStringLoopCacheRejectionReason {
    UnsupportedCompilationMode,
    DeclarationIdentityMismatch,
    IncompleteLoweredIRWalk,
    InvalidLoopIdentityWeb,
    EscapingOrMutableCString,
    IncompleteCleanupFrontier,
    EvaluationOrExceptionMismatch,
    DuplicateStructuralBinding,
}

internal data class ArcScopedCStringLoopCacheRejection<T : Any>(
    val reason: ArcScopedCStringLoopCacheRejectionReason,
    val binding: T,
)

/** Exact obligations consumed later by the lowered-IR selector and C bridge emitter. */
internal enum class ArcScopedCStringLoopCacheActionId {
    InitializeEmptyCache,
    TransactionalRefreshOnIdentityChange,
    UseCachedPointerForForeignCall,
    DestroyCacheOnNormalAndExceptionalExit,
}

internal data class ArcScopedCStringLoopCacheAction<T : Any>(
    val id: ArcScopedCStringLoopCacheActionId,
    val bindingIdentities: List<T>,
)

internal data class ArcScopedCStringLoopCachePlan<T : Any>(
    val functionBinding: T,
    val loopBinding: T,
    val sourceSlotBinding: T,
    val cacheSourceSlotBinding: T,
    val cachePointerSlotBinding: T,
    val foreignCallBinding: T,
    val createHelper: String,
    val disposeHelper: String,
    val actions: Map<ArcScopedCStringLoopCacheActionId, ArcScopedCStringLoopCacheAction<T>>,
)

internal data class ArcScopedCStringLoopCacheSelectionResult<T : Any>(
    val plan: ArcScopedCStringLoopCachePlan<T>?,
    val rejections: List<ArcScopedCStringLoopCacheRejection<T>>,
)

/**
 * Swift-style loop lifetime widening for a native resource, guarded by Kotlin String RC identity.
 * The adapter does not trust names: a future real-IR walker must supply every exact identity and
 * prove the complete normal/exceptional cleanup frontier before this produces emission actions.
 */
internal object ArcScopedCStringLoopCacheAnalysis {
    fun <T : Any> select(
        candidate: ArcScopedCStringLoopCacheCandidate<T>,
    ): ArcScopedCStringLoopCacheSelectionResult<T> {
        val lifetime = candidate.lifetime
        fun reject(reason: ArcScopedCStringLoopCacheRejectionReason, binding: T) =
            ArcScopedCStringLoopCacheSelectionResult<T>(
                plan = null,
                rejections = listOf(ArcScopedCStringLoopCacheRejection(reason, binding)),
            )

        if (!candidate.mode.isAuthorized()) {
            return reject(ArcScopedCStringLoopCacheRejectionReason.UnsupportedCompilationMode, lifetime.functionBinding)
        }
        if (!candidate.declarations.isExact()) {
            return reject(
                ArcScopedCStringLoopCacheRejectionReason.DeclarationIdentityMismatch,
                candidate.declarations.foreignFunctionBinding,
            )
        }
        if (!candidate.completeLoweredIRWalk) {
            return reject(ArcScopedCStringLoopCacheRejectionReason.IncompleteLoweredIRWalk, lifetime.functionBinding)
        }
        if (!lifetime.hasExactLoopIdentityWeb()) {
            return reject(ArcScopedCStringLoopCacheRejectionReason.InvalidLoopIdentityWeb, lifetime.loopBinding)
        }
        if (!lifetime.hasReadOnlyNonEscapingCString()) {
            return reject(ArcScopedCStringLoopCacheRejectionReason.EscapingOrMutableCString, lifetime.foreignCallBinding)
        }
        if (!lifetime.hasCompleteCleanupFrontier()) {
            return reject(
                ArcScopedCStringLoopCacheRejectionReason.IncompleteCleanupFrontier,
                lifetime.exceptionalExitDisposeBinding,
            )
        }
        if (!candidate.semantics.isExact()) {
            return reject(
                ArcScopedCStringLoopCacheRejectionReason.EvaluationOrExceptionMismatch,
                lifetime.identityGuardBinding,
            )
        }
        if (!candidate.hasDistinctStructuralBindings()) {
            return reject(
                ArcScopedCStringLoopCacheRejectionReason.DuplicateStructuralBinding,
                lifetime.functionBinding,
            )
        }

        val actions = linkedMapOf(
            ArcScopedCStringLoopCacheActionId.InitializeEmptyCache to ArcScopedCStringLoopCacheAction(
                ArcScopedCStringLoopCacheActionId.InitializeEmptyCache,
                listOf(lifetime.functionBinding, lifetime.cacheSourceSlotBinding, lifetime.cachePointerSlotBinding),
            ),
            ArcScopedCStringLoopCacheActionId.TransactionalRefreshOnIdentityChange to
                    ArcScopedCStringLoopCacheAction(
                        ArcScopedCStringLoopCacheActionId.TransactionalRefreshOnIdentityChange,
                        listOf(
                            lifetime.sourceLoadBinding,
                            lifetime.identityGuardBinding,
                            lifetime.conversionBinding,
                            lifetime.replacementDisposeBinding,
                            lifetime.cacheSourceSlotBinding,
                            lifetime.cachePointerSlotBinding,
                        ),
                    ),
            ArcScopedCStringLoopCacheActionId.UseCachedPointerForForeignCall to
                    ArcScopedCStringLoopCacheAction(
                        ArcScopedCStringLoopCacheActionId.UseCachedPointerForForeignCall,
                        listOf(lifetime.cachePointerSlotBinding, lifetime.foreignCallBinding),
                    ),
            ArcScopedCStringLoopCacheActionId.DestroyCacheOnNormalAndExceptionalExit to
                    ArcScopedCStringLoopCacheAction(
                        ArcScopedCStringLoopCacheActionId.DestroyCacheOnNormalAndExceptionalExit,
                        listOf(
                            lifetime.normalExitDisposeBinding,
                            lifetime.exceptionalExitDisposeBinding,
                            lifetime.cachePointerSlotBinding,
                            lifetime.cacheSourceSlotBinding,
                        ),
                    ),
        )
        return ArcScopedCStringLoopCacheSelectionResult(
            plan = ArcScopedCStringLoopCachePlan(
                functionBinding = lifetime.functionBinding,
                loopBinding = lifetime.loopBinding,
                sourceSlotBinding = lifetime.sourceSlotBinding,
                cacheSourceSlotBinding = lifetime.cacheSourceSlotBinding,
                cachePointerSlotBinding = lifetime.cachePointerSlotBinding,
                foreignCallBinding = lifetime.foreignCallBinding,
                createHelper = ARC_CREATE_SCOPED_CSTRING,
                disposeHelper = ARC_DISPOSE_SCOPED_CSTRING,
                actions = actions,
            ),
            rejections = emptyList(),
        )
    }
}

private fun <T : Any> ArcScopedCStringLoopCacheDeclarationProof<T>.isExact(): Boolean =
    exactInteropLibraryIdentity && exactCCallSymbolAnnotation && exactSerializedNoCallbackAnnotation &&
            exactSingleFixedCStringParameter && constCharPointee && nonVariadicCFunction &&
            primitiveOrUnitResult && noReceiverOrFunctionPointerDispatch

private fun <T : Any> ArcScopedCStringLoopCacheLifetimeProof<T>.hasExactLoopIdentityWeb(): Boolean =
    exactSingleNaturalLoop && loopHeaderDominatesEveryCall && exactStrongStringSourceSlot &&
            sourceEvaluatedOncePerIteration && identityGuardDominatesConversionAndCall &&
            everySourceDefinitionFlowsThroughGuard && noSourceDefinitionBetweenGuardAndCall &&
            cachedSourceStronglyOwnsConvertedIdentity

private fun <T : Any> ArcScopedCStringLoopCacheLifetimeProof<T>.hasReadOnlyNonEscapingCString(): Boolean =
    pointerHasOneNonEscapingCallUsePerIteration && noCallbackOrSuspensionBoundary

private fun <T : Any> ArcScopedCStringLoopCacheLifetimeProof<T>.hasCompleteCleanupFrontier(): Boolean =
    normalCleanupPostDominatesLoop && exceptionalCleanupCoversEveryLoopExit &&
            replacementCleanupIsExactlyOnce

private fun ArcScopedCStringLoopCacheSemanticsProof.isExact(): Boolean =
    referenceIdentityComparison && exactUtf8ReplacementConverter && embeddedNulBehaviorPreserved &&
            constPointeePreventsPersistentMutation && createNewBeforeDisposeOld &&
            retainNewSourceBeforeReleaseOld && conversionFailureLeavesOldCacheOwned &&
            argumentEvaluationOrderPreserved && foreignCallExceptionalSuccessorPreserved

private fun <T : Any> ArcScopedCStringLoopCacheCandidate<T>.hasDistinctStructuralBindings(): Boolean {
    val identities = listOf(
        declarations.interopLibraryBinding,
        declarations.foreignFunctionBinding,
        declarations.cStringParameterBinding,
        lifetime.functionBinding,
        lifetime.loopBinding,
        lifetime.sourceSlotBinding,
        lifetime.sourceLoadBinding,
        lifetime.cacheSourceSlotBinding,
        lifetime.cachePointerSlotBinding,
        lifetime.identityGuardBinding,
        lifetime.conversionBinding,
        lifetime.foreignCallBinding,
        lifetime.replacementDisposeBinding,
        lifetime.normalExitDisposeBinding,
        lifetime.exceptionalExitDisposeBinding,
    )
    val unique = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    return identities.all(unique::add)
}

/** Identity ledger preventing a selected proof from being partially or broadly emitted. */
internal class ArcScopedCStringLoopCacheConsumptionLedger<T : Any>(
    private val plan: ArcScopedCStringLoopCachePlan<T>,
    functionBinding: T,
) {
    private val consumed = mutableSetOf<ArcScopedCStringLoopCacheActionId>()

    init {
        check(functionBinding === plan.functionBinding) { "scoped CString cache function identity drift" }
    }

    fun consume(action: ArcScopedCStringLoopCacheAction<T>) {
        val expected = plan.actions[action.id] ?: error("unexpected scoped CString cache action ${action.id}")
        check(consumed.add(action.id)) { "duplicate scoped CString cache action ${action.id}" }
        check(expected.bindingIdentities.size == action.bindingIdentities.size &&
                expected.bindingIdentities.zip(action.bindingIdentities).all { (left, right) -> left === right }) {
            "scoped CString cache action identity drift: ${action.id}"
        }
    }

    fun verifyComplete() {
        check(consumed == plan.actions.keys) { "incomplete scoped CString cache emission" }
    }
}
