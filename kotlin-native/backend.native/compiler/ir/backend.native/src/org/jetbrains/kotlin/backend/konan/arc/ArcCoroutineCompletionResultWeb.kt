/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import java.util.Collections
import java.util.IdentityHashMap

/** Production boundary for the exact `BaseContinuationImpl.resumeWith` result web. */
internal data class ArcCoroutineCompletionResultMode(
    val arcEnabled: Boolean,
    val linuxX64: Boolean,
    val finalBinary: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val diagnosticsDisabled: Boolean,
    val sanitizerDisabled: Boolean,
    val coverageDisabled: Boolean,
)

/**
 * Facts which must be derived from the complete lowered body. They are deliberately granular:
 * adding a new forwarding wrapper, result use, catch, or exit invalidates the proof instead of
 * silently extending the lifetime of a borrowed field projection or an owned result.
 */
internal data class ArcCoroutineCompletionResultProof(
    val exactBaseContinuationSelection: Boolean,
    val exactFinalStrongCompletionField: Boolean,
    val exactNullCheckedCompletionProjection: Boolean,
    val completionUsesExhaustive: Boolean,
    val currentAnchorCoversNormalAndUnwind: Boolean,
    val invokeSuspendProducesOwnedResult: Boolean,
    val suspendedExitDestroysOwnedResult: Boolean,
    val successResultIsIdentityForward: Boolean,
    val failureFactoryProducesOwnedResult: Boolean,
    val failureFactoryUnwindDestroysCaughtException: Boolean,
    val failureCopiesExceptionBeforeCatchOwnerRelease: Boolean,
    val exactReferenceResultCatchOwnershipABI: Boolean,
    val outcomeJoinIsExactlySuccessOrFailure: Boolean,
    val outcomeUsesExhaustive: Boolean,
    val releaseUnwindDestroysOutcome: Boolean,
    val backedgeRetainsCompletionBeforeReplacingCurrent: Boolean,
    val backedgeConsumesOutcomeIntoParameter: Boolean,
    val terminalCallBorrowsOutcome: Boolean,
    val terminalNormalDestroysOutcome: Boolean,
    val terminalUnwindDestroysOutcome: Boolean,
    val everyExitEndsCompletionBorrow: Boolean,
)

internal enum class ArcCoroutineCompletionResultPath {
    SuspendedReturn,
    SuccessBackedge,
    FailureBackedge,
    SuccessTerminalReturn,
    FailureTerminalReturn,
    FailureFactoryUnwind,
    SuccessReleaseInterceptedUnwind,
    FailureReleaseInterceptedUnwind,
    SuccessTerminalResumeUnwind,
    FailureTerminalResumeUnwind,
}

internal enum class ArcCoroutineCompletionResultEvent {
    BorrowCompletionProjection,
    DestroySuspendedInvokeResult,
    PreserveOutcomeAcrossReleaseIntercepted,
    ReleaseCaughtExceptionOwnerAfterFailureMaterialization,
    DestroyCaughtExceptionOnFailureFactoryUnwind,
    DestroyOutcomeOnReleaseUnwind,
    RetainCompletionBeforeCurrentReplacement,
    ReleaseReplacedCurrentOwner,
    ConsumeOutcomeIntoParameter,
    ClearConsumedOutcomeRoot,
    ReleaseReplacedParameterOwner,
    BorrowOutcomeForTerminalResume,
    DestroyOutcomeAfterTerminalResume,
    DestroyOutcomeOnTerminalUnwind,
    EndCompletionBorrow,
}

/** Exact events required on each executable path after a future emitter consumes this proof. */
internal data class ArcCoroutineCompletionResultPathLedger(
    val path: ArcCoroutineCompletionResultPath,
    val requiredEvents: Set<ArcCoroutineCompletionResultEvent>,
)

/**
 * Measured semantic reduction for the only path which currently performs redundant ARC traffic.
 * The completion projection is already a raw load in optimized LLVM and terminal calls are +0,
 * so neither is credited with a reduction. A backedge move removes the retain of the new `param`
 * value and the later destroy of its source owner; releasing the overwritten `param` is mandatory.
 */
internal data class ArcCoroutineCompletionResultReduction(
    val backedgeStackUpdatesRemoved: Int,
    val backedgeRetainsRemoved: Int,
    val backedgeSourceReleasesRemoved: Int,
    val completionProjectionTrafficRemoved: Int,
    val terminalTrafficRemoved: Int,
    val preservedCompletionRetains: Int,
    val preservedReplacedSlotReleases: Int,
    val preservedCaughtExceptionReleases: Int,
)

/**
 * Physical operations required to turn the owned outcome temporary into the next loop parameter.
 *
 * This is deliberately a proof model, not an emitter API. In particular, the two raw stores below
 * are safe only as one indivisible, non-throwing sequence: the source owner is cleared without a
 * release, ownership is installed in the destination, and only then is the replaced destination
 * owner released. That ordering remains valid when both owners contain the same object pointer.
 */
internal enum class ArcCoroutineCompletionPhysicalMoveAction {
    RetainCompletionOwner,
    SnapshotReplacedCurrentOwner,
    StoreCompletionIntoCurrentWithoutRetain,
    ReleaseReplacedCurrentOwner,
    SnapshotOutcomeOwner,
    ClearOutcomeRootWithoutRelease,
    SnapshotReplacedParameterOwner,
    StoreOutcomeIntoParameterWithoutRetain,
    ReleaseReplacedParameterOwner,
    EndCompletionBorrow,
}

internal data class ArcCoroutineCompletionPhysicalMoveActionBinding<T : Any>(
    val action: ArcCoroutineCompletionPhysicalMoveAction,
    val bindingIdentities: List<T>,
    /** A partially completed transfer must never acquire an unwind successor. */
    val canUnwind: Boolean = false,
)

/** Exact path-sensitive physical trace a future emitter would have to consume. */
internal data class ArcCoroutineCompletionPhysicalBackedgeMove<T : Any>(
    val functionBinding: T,
    val pathActions: Map<ArcCoroutineCompletionResultPath, List<ArcCoroutineCompletionPhysicalMoveActionBinding<T>>>,
)

internal enum class ArcCoroutineCompletionPhysicalMoveRejection {
    FunctionIdentityMismatch,
    IncompletePathInventory,
    BackedgeActionMismatch,
    BindingIdentityMismatch,
    DuplicateAction,
    PotentiallyUnwindingTransfer,
    SelfAliasUnsafeOrder,
    ActionOnNonBackedgePath,
}

internal data class ArcCoroutineCompletionPhysicalMoveVerification(
    val rejections: Set<ArcCoroutineCompletionPhysicalMoveRejection>,
) {
    val isSuccess: Boolean get() = rejections.isEmpty()
}

/**
 * Re-authenticates the complete trace independently of the structural selector. The state-machine
 * check is intentionally redundant with the canonical order check: it documents and enforces why
 * releasing an aliased old value cannot deallocate the value being transferred.
 */
internal object ArcCoroutineCompletionPhysicalMoveVerifier {
    fun <T : Any> verify(
        move: ArcCoroutineCompletionPhysicalBackedgeMove<T>,
        bindings: ArcCoroutineCompletionResultBindings<T>,
    ): ArcCoroutineCompletionPhysicalMoveVerification {
        val rejected = linkedSetOf<ArcCoroutineCompletionPhysicalMoveRejection>()
        if (move.functionBinding !== bindings.function) {
            rejected += ArcCoroutineCompletionPhysicalMoveRejection.FunctionIdentityMismatch
        }
        if (move.pathActions.keys != ArcCoroutineCompletionResultPath.values().toSet()) {
            rejected += ArcCoroutineCompletionPhysicalMoveRejection.IncompletePathInventory
        }

        val expected = exactPhysicalBackedgeActions(bindings)
        val backedges = setOf(
            ArcCoroutineCompletionResultPath.SuccessBackedge,
            ArcCoroutineCompletionResultPath.FailureBackedge,
        )
        ArcCoroutineCompletionResultPath.values().forEach { path ->
            val actions = move.pathActions[path].orEmpty()
            if (path !in backedges) {
                if (actions.isNotEmpty()) {
                    rejected += ArcCoroutineCompletionPhysicalMoveRejection.ActionOnNonBackedgePath
                }
                return@forEach
            }
            if (actions.map { it.action } != expected.map { it.action }) {
                rejected += ArcCoroutineCompletionPhysicalMoveRejection.BackedgeActionMismatch
            }
            if (actions.map { it.action }.toSet().size != actions.size) {
                rejected += ArcCoroutineCompletionPhysicalMoveRejection.DuplicateAction
            }
            actions.zip(expected).forEach { (actual, canonical) ->
                if (!actual.bindingIdentities.identityEquals(canonical.bindingIdentities)) {
                    rejected += ArcCoroutineCompletionPhysicalMoveRejection.BindingIdentityMismatch
                }
            }
            if (actions.any { it.canUnwind }) {
                rejected += ArcCoroutineCompletionPhysicalMoveRejection.PotentiallyUnwindingTransfer
            }
            if (!isSelfAliasSafe(actions.map { it.action })) {
                rejected += ArcCoroutineCompletionPhysicalMoveRejection.SelfAliasUnsafeOrder
            }
        }
        return ArcCoroutineCompletionPhysicalMoveVerification(rejected)
    }

    private fun isSelfAliasSafe(actions: List<ArcCoroutineCompletionPhysicalMoveAction>): Boolean {
        var completionOwnerHeld = false
        var replacedCurrentSnapshotted = false
        var completionInstalled = false
        var replacedCurrentReleased = false
        var outcomeOwnerSnapshotted = false
        var outcomeRootCleared = false
        var replacedParameterSnapshotted = false
        var outcomeInstalled = false
        var replacedParameterReleased = false
        for (action in actions) when (action) {
            ArcCoroutineCompletionPhysicalMoveAction.RetainCompletionOwner -> {
                if (completionOwnerHeld) return false
                completionOwnerHeld = true
            }
            ArcCoroutineCompletionPhysicalMoveAction.SnapshotReplacedCurrentOwner -> {
                if (!completionOwnerHeld || replacedCurrentSnapshotted) return false
                replacedCurrentSnapshotted = true
            }
            ArcCoroutineCompletionPhysicalMoveAction.StoreCompletionIntoCurrentWithoutRetain -> {
                if (!completionOwnerHeld || !replacedCurrentSnapshotted || completionInstalled) return false
                completionOwnerHeld = false
                completionInstalled = true
            }
            ArcCoroutineCompletionPhysicalMoveAction.ReleaseReplacedCurrentOwner -> {
                // The new owner is already rooted, so pointer self-aliasing is harmless.
                if (!completionInstalled || !replacedCurrentSnapshotted || replacedCurrentReleased) return false
                replacedCurrentReleased = true
            }
            ArcCoroutineCompletionPhysicalMoveAction.SnapshotOutcomeOwner -> {
                if (!replacedCurrentReleased || outcomeOwnerSnapshotted) return false
                outcomeOwnerSnapshotted = true
            }
            ArcCoroutineCompletionPhysicalMoveAction.ClearOutcomeRootWithoutRelease -> {
                if (!outcomeOwnerSnapshotted || outcomeRootCleared) return false
                outcomeRootCleared = true
            }
            ArcCoroutineCompletionPhysicalMoveAction.SnapshotReplacedParameterOwner -> {
                if (!outcomeRootCleared || replacedParameterSnapshotted) return false
                replacedParameterSnapshotted = true
            }
            ArcCoroutineCompletionPhysicalMoveAction.StoreOutcomeIntoParameterWithoutRetain -> {
                if (!outcomeRootCleared || !replacedParameterSnapshotted || outcomeInstalled) return false
                outcomeInstalled = true
            }
            ArcCoroutineCompletionPhysicalMoveAction.ReleaseReplacedParameterOwner -> {
                // Install before release keeps one +1 even when old and new pointers are equal.
                if (!outcomeInstalled || replacedParameterReleased) return false
                replacedParameterReleased = true
            }
            ArcCoroutineCompletionPhysicalMoveAction.EndCompletionBorrow -> {
                if (!replacedCurrentReleased || !replacedParameterReleased) return false
            }
        }
        return !completionOwnerHeld && completionInstalled && replacedCurrentReleased &&
                outcomeRootCleared && outcomeInstalled && replacedParameterReleased &&
                actions.lastOrNull() == ArcCoroutineCompletionPhysicalMoveAction.EndCompletionBorrow
    }
}

/**
 * Fail-closed, exact-once ledger reserved for a future physical emitter. Constructing or consuming
 * this ledger does not authorize emission; [ArcCoroutineCompletionResultSelection.emissionAuthorized]
 * remains false for this proof-only slice.
 */
internal class ArcCoroutineCompletionPhysicalMoveLedger<T : Any>(
    selection: ArcCoroutineCompletionResultSelection<T>,
    functionBinding: T,
    path: ArcCoroutineCompletionResultPath,
) {
    private val expected: List<ArcCoroutineCompletionPhysicalMoveActionBinding<T>>
    private var next = 0

    init {
        check(selection.bindings.function === functionBinding) {
            "completion-result physical move ledger was opened for a different function identity"
        }
        val verification = ArcCoroutineCompletionPhysicalMoveVerifier.verify(
            selection.physicalBackedgeMove,
            selection.bindings,
        )
        check(verification.isSuccess) {
            "invalid completion-result physical move: ${verification.rejections}"
        }
        expected = selection.physicalBackedgeMove.pathActions.getValue(path)
    }

    fun consume(action: ArcCoroutineCompletionPhysicalMoveAction, bindingIdentities: List<T>) {
        check(next < expected.size) { "unexpected completion-result physical move action: $action" }
        val actionBinding = expected[next]
        check(actionBinding.action == action) {
            "out-of-order completion-result physical move action: expected=${actionBinding.action}, actual=$action"
        }
        check(actionBinding.bindingIdentities.identityEquals(bindingIdentities)) {
            "completion-result physical move binding identity drifted: $action"
        }
        next++
    }

    fun verifyComplete() {
        check(next == expected.size) {
            "completion-result physical move incomplete: missing=${expected.drop(next).map { it.action }}"
        }
    }
}

private fun <T : Any> List<T>.identityEquals(other: List<T>): Boolean =
    size == other.size && indices.all { this[it] === other[it] }

internal data class ArcCoroutineCompletionResultBindings<T : Any>(
    val function: T,
    val baseSelection: T,
    val currentAnchor: T,
    val completionField: T,
    val completionFieldLoad: T,
    val completionNullableTemporary: T,
    val completionProjection: T,
    val invokeSuspend: T,
    val invokeOwnedResult: T,
    val suspendedComparison: T,
    val suspendedReturn: T,
    val successIdentityForward: T,
    val caughtException: T,
    val failureFactory: T,
    val failureOwnedResult: T,
    val outcomeJoin: T,
    val releaseIntercepted: T,
    val completionTypeTest: T,
    val currentBackedgeStore: T,
    val outcomeBackedgeStore: T,
    val terminalResume: T,
    val terminalReturn: T,
    /** Exact source/destination IR operands a future physical backedge emitter may consume. */
    val physicalMoveOperands: ArcCoroutineCompletionPhysicalMoveOperands<T>,
    /**
     * Exhaustive identities authenticated by the Kotlin-IR selector in addition to the semantic
     * bindings above. Kept as one closed inventory because these proof-only topology nodes are not
     * emitter operands; a future emitter must promote every operand it consumes to a named field.
     */
    val authenticatedIRIdentities: List<T> = emptyList(),
)

internal data class ArcCoroutineCompletionPhysicalMoveOperands<T : Any>(
    val currentOwnerSlot: T,
    val parameterOwnerSlot: T,
    val completionBackedgeValue: T,
    val completionBackedgeRead: T,
    val outcomeBackedgeRead: T,
)

internal fun <T : Any> ArcCoroutineCompletionResultBindings<T>.exactIdentityInventory(): List<T> = listOf(
    function, baseSelection, currentAnchor, completionField, completionFieldLoad,
    completionNullableTemporary, completionProjection, invokeSuspend, invokeOwnedResult,
    suspendedComparison, suspendedReturn, successIdentityForward, caughtException,
    failureFactory, failureOwnedResult, outcomeJoin, releaseIntercepted, completionTypeTest,
    currentBackedgeStore, outcomeBackedgeStore, terminalResume, terminalReturn,
) + listOf(
    physicalMoveOperands.currentOwnerSlot,
    physicalMoveOperands.parameterOwnerSlot,
    physicalMoveOperands.completionBackedgeValue,
    physicalMoveOperands.completionBackedgeRead,
    physicalMoveOperands.outcomeBackedgeRead,
) + authenticatedIRIdentities

internal data class ArcCoroutineCompletionResultSelection<T : Any>(
    val bindings: ArcCoroutineCompletionResultBindings<T>,
    val normalAndUnwindOwnershipProof: ArcFunctionPlan,
    val suspendedOwnershipProof: ArcFunctionPlan,
    val failureConstructionOwnershipProof: ArcFunctionPlan,
    val pathLedger: List<ArcCoroutineCompletionResultPathLedger>,
    val physicalBackedgeMove: ArcCoroutineCompletionPhysicalBackedgeMove<T>,
    val reduction: ArcCoroutineCompletionResultReduction,
    /** This slice is proof-only. Codegen must not infer an emission authorization from selection. */
    val emissionAuthorized: Boolean = false,
)

private fun <T : Any> exactPhysicalBackedgeActions(
    bindings: ArcCoroutineCompletionResultBindings<T>,
): List<ArcCoroutineCompletionPhysicalMoveActionBinding<T>> = listOf(
    ArcCoroutineCompletionPhysicalMoveActionBinding(
        ArcCoroutineCompletionPhysicalMoveAction.RetainCompletionOwner,
        listOf(
            bindings.completionProjection,
            bindings.physicalMoveOperands.completionBackedgeRead,
            bindings.currentBackedgeStore,
        ),
    ),
    ArcCoroutineCompletionPhysicalMoveActionBinding(
        ArcCoroutineCompletionPhysicalMoveAction.SnapshotReplacedCurrentOwner,
        listOf(bindings.physicalMoveOperands.currentOwnerSlot, bindings.currentBackedgeStore),
    ),
    ArcCoroutineCompletionPhysicalMoveActionBinding(
        ArcCoroutineCompletionPhysicalMoveAction.StoreCompletionIntoCurrentWithoutRetain,
        listOf(
            bindings.physicalMoveOperands.completionBackedgeValue,
            bindings.physicalMoveOperands.completionBackedgeRead,
            bindings.physicalMoveOperands.currentOwnerSlot,
            bindings.currentBackedgeStore,
        ),
    ),
    ArcCoroutineCompletionPhysicalMoveActionBinding(
        ArcCoroutineCompletionPhysicalMoveAction.ReleaseReplacedCurrentOwner,
        listOf(bindings.physicalMoveOperands.currentOwnerSlot, bindings.currentBackedgeStore),
    ),
    ArcCoroutineCompletionPhysicalMoveActionBinding(
        ArcCoroutineCompletionPhysicalMoveAction.SnapshotOutcomeOwner,
        listOf(
            bindings.outcomeJoin,
            bindings.physicalMoveOperands.outcomeBackedgeRead,
            bindings.outcomeBackedgeStore,
        ),
    ),
    ArcCoroutineCompletionPhysicalMoveActionBinding(
        ArcCoroutineCompletionPhysicalMoveAction.ClearOutcomeRootWithoutRelease,
        listOf(
            bindings.outcomeJoin,
            bindings.physicalMoveOperands.outcomeBackedgeRead,
            bindings.outcomeBackedgeStore,
        ),
    ),
    ArcCoroutineCompletionPhysicalMoveActionBinding(
        ArcCoroutineCompletionPhysicalMoveAction.SnapshotReplacedParameterOwner,
        listOf(bindings.physicalMoveOperands.parameterOwnerSlot, bindings.outcomeBackedgeStore),
    ),
    ArcCoroutineCompletionPhysicalMoveActionBinding(
        ArcCoroutineCompletionPhysicalMoveAction.StoreOutcomeIntoParameterWithoutRetain,
        listOf(
            bindings.physicalMoveOperands.outcomeBackedgeRead,
            bindings.physicalMoveOperands.parameterOwnerSlot,
            bindings.outcomeBackedgeStore,
        ),
    ),
    ArcCoroutineCompletionPhysicalMoveActionBinding(
        ArcCoroutineCompletionPhysicalMoveAction.ReleaseReplacedParameterOwner,
        listOf(bindings.physicalMoveOperands.parameterOwnerSlot, bindings.outcomeBackedgeStore),
    ),
    ArcCoroutineCompletionPhysicalMoveActionBinding(
        ArcCoroutineCompletionPhysicalMoveAction.EndCompletionBorrow,
        listOf(bindings.completionProjection, bindings.currentBackedgeStore),
    ),
)

private fun <T : Any> exactPhysicalBackedgeMove(
    bindings: ArcCoroutineCompletionResultBindings<T>,
): ArcCoroutineCompletionPhysicalBackedgeMove<T> {
    val actions = exactPhysicalBackedgeActions(bindings)
    return ArcCoroutineCompletionPhysicalBackedgeMove(
        functionBinding = bindings.function,
        pathActions = ArcCoroutineCompletionResultPath.values().associateWith { path ->
            when (path) {
                ArcCoroutineCompletionResultPath.SuccessBackedge,
                ArcCoroutineCompletionResultPath.FailureBackedge -> actions
                else -> emptyList()
            }
        },
    )
}

internal enum class ArcCoroutineCompletionResultRejectionReason {
    UnsupportedCompilationMode,
    DuplicateStructuralIdentity,
    IncompleteStructuralProof,
    OwnershipVerifierRejected,
}

internal data class ArcCoroutineCompletionResultAnalysisResult<T : Any>(
    val selection: ArcCoroutineCompletionResultSelection<T>?,
    val rejection: ArcCoroutineCompletionResultRejectionReason?,
)

internal object ArcCoroutineCompletionResultAnalysis {
    fun <T : Any> select(
        bindings: ArcCoroutineCompletionResultBindings<T>,
        mode: ArcCoroutineCompletionResultMode,
        proof: ArcCoroutineCompletionResultProof,
    ): ArcCoroutineCompletionResultAnalysisResult<T> {
        if (!mode.isProductionMode()) return rejected(
            ArcCoroutineCompletionResultRejectionReason.UnsupportedCompilationMode,
        )
        val unique = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        if (bindings.exactIdentityInventory().any { !unique.add(it) }) return rejected(
            ArcCoroutineCompletionResultRejectionReason.DuplicateStructuralIdentity,
        )
        if (!proof.isComplete()) return rejected(
            ArcCoroutineCompletionResultRejectionReason.IncompleteStructuralProof,
        )
        val normal = normalAndUnwindOwnershipProof()
        val suspended = suspendedOwnershipProof()
        val failureConstruction = failureConstructionOwnershipProof()
        if (ArcOwnershipVerifier.verify(normal) !== ArcOwnershipVerificationResult.Success ||
            ArcOwnershipVerifier.verify(suspended) !== ArcOwnershipVerificationResult.Success ||
            ArcOwnershipVerifier.verify(failureConstruction) !== ArcOwnershipVerificationResult.Success
        ) return rejected(ArcCoroutineCompletionResultRejectionReason.OwnershipVerifierRejected)
        return ArcCoroutineCompletionResultAnalysisResult(
            ArcCoroutineCompletionResultSelection(
                bindings,
                normal,
                suspended,
                failureConstruction,
                exactPathLedger(),
                exactPhysicalBackedgeMove(bindings),
                ArcCoroutineCompletionResultReduction(
                    backedgeStackUpdatesRemoved = 1,
                    backedgeRetainsRemoved = 1,
                    backedgeSourceReleasesRemoved = 1,
                    completionProjectionTrafficRemoved = 0,
                    terminalTrafficRemoved = 0,
                    preservedCompletionRetains = 1,
                    preservedReplacedSlotReleases = 2,
                    preservedCaughtExceptionReleases = 1,
                ),
            ),
            null,
        )
    }

    private fun <T : Any> rejected(reason: ArcCoroutineCompletionResultRejectionReason) =
        ArcCoroutineCompletionResultAnalysisResult<T>(null, reason)

    private fun normalAndUnwindOwnershipProof(): ArcFunctionPlan {
        val current = ArcValue("current.anchor")
        val completion = ArcValue("completion.borrow")
        val outcome = ArcValue("outcome.owned")
        val nextCurrent = ArcValue("completion.materialized")
        val currentSlot = ArcStorage("current")
        val parameterSlot = ArcStorage("param")
        val entry = ArcBlockId("entry")
        val releaseNormal = ArcBlockId("release.normal")
        val releaseUnwind = ArcBlockId("release.unwind")
        val backedge = ArcBlockId("backedge")
        val terminalCall = ArcBlockId("terminal.call")
        val terminalNormal = ArcBlockId("terminal.normal")
        val terminalUnwind = ArcBlockId("terminal.unwind")
        return ArcFunctionPlan(
            functionName = "BaseContinuationImpl.resumeWith completion/result web",
            entry = entry,
            entryValues = mapOf(current to ArcOwnership.Guaranteed),
            entryInitializedStorage = setOf(currentSlot, parameterSlot),
            blocks = linkedMapOf(
                entry to ArcBasicBlock(
                    entry,
                    listOf(
                        ArcOperation.Borrow(current, completion, ArcBorrowKind.Projection),
                        ArcOperation.Define(outcome, ArcOwnership.Owned),
                        ArcOperation.Use(current, ArcPlanLocation("releaseIntercepted receiver")),
                    ),
                    // This branch models the normal and exceptional successors of the call.
                    ArcTerminator.Branch(releaseNormal, releaseUnwind),
                ),
                releaseUnwind to ArcBasicBlock(
                    releaseUnwind,
                    listOf(ArcOperation.Destroy(outcome), ArcOperation.EndBorrow(completion)),
                    ArcTerminator.Throw,
                ),
                releaseNormal to ArcBasicBlock(
                    releaseNormal,
                    listOf(ArcOperation.Use(completion, ArcPlanLocation("BaseContinuationImpl type test"))),
                    ArcTerminator.Branch(backedge, terminalCall),
                ),
                backedge to ArcBasicBlock(
                    backedge,
                    listOf(
                        // Retain before replacing current: the old owner contains this field.
                        ArcOperation.Copy(completion, nextCurrent),
                        ArcOperation.StrongStore(currentSlot, nextCurrent),
                        ArcOperation.Destroy(nextCurrent),
                        // StrongStore+Destroy is the verified consuming-store semantic primitive.
                        ArcOperation.StrongStore(parameterSlot, outcome),
                        ArcOperation.Destroy(outcome),
                        ArcOperation.EndBorrow(completion),
                    ),
                    ArcTerminator.Return(),
                ),
                terminalCall to ArcBasicBlock(
                    terminalCall,
                    listOf(
                        ArcOperation.Use(completion, ArcPlanLocation("Continuation.resumeWith receiver")),
                        ArcOperation.Use(outcome, ArcPlanLocation("Continuation.resumeWith +0 result")),
                    ),
                    ArcTerminator.Branch(terminalNormal, terminalUnwind),
                ),
                terminalNormal to ArcBasicBlock(
                    terminalNormal,
                    listOf(ArcOperation.Destroy(outcome), ArcOperation.EndBorrow(completion)),
                    ArcTerminator.Return(),
                ),
                terminalUnwind to ArcBasicBlock(
                    terminalUnwind,
                    listOf(ArcOperation.Destroy(outcome), ArcOperation.EndBorrow(completion)),
                    ArcTerminator.Throw,
                ),
            ),
        )
    }

    private fun suspendedOwnershipProof(): ArcFunctionPlan {
        val current = ArcValue("current.anchor")
        val completion = ArcValue("completion.borrow")
        val invokeResult = ArcValue("invokeSuspend.result")
        val entry = ArcBlockId("suspended")
        return ArcFunctionPlan(
            functionName = "BaseContinuationImpl.resumeWith suspended exit",
            entry = entry,
            entryValues = mapOf(current to ArcOwnership.Guaranteed),
            entryInitializedStorage = emptySet(),
            blocks = linkedMapOf(
                entry to ArcBasicBlock(
                    entry,
                    listOf(
                        ArcOperation.Borrow(current, completion, ArcBorrowKind.Projection),
                        ArcOperation.Define(invokeResult, ArcOwnership.Owned),
                        ArcOperation.Use(invokeResult, ArcPlanLocation("COROUTINE_SUSPENDED identity test")),
                        ArcOperation.Destroy(invokeResult),
                        ArcOperation.EndBorrow(completion),
                    ),
                    ArcTerminator.Return(),
                ),
            ),
        )
    }

    private fun failureConstructionOwnershipProof(): ArcFunctionPlan {
        val exception = ArcValue("caught.exception")
        val failure = ArcValue("Result.Failure.owned")
        val entry = ArcBlockId("failure")
        val factoryNormal = ArcBlockId("failure.factory.normal")
        val factoryUnwind = ArcBlockId("failure.factory.unwind")
        return ArcFunctionPlan(
            functionName = "BaseContinuationImpl.resumeWith failure construction",
            entry = entry,
            entryValues = mapOf(exception to ArcOwnership.Owned),
            entryInitializedStorage = emptySet(),
            blocks = linkedMapOf(
                entry to ArcBasicBlock(
                    entry,
                    listOf(
                        // createFailure borrows the caught exception while initializing the new
                        // Failure object's independently owning strong field.
                        ArcOperation.Use(exception, ArcPlanLocation("createFailure argument")),
                    ),
                    // A throwing factory has not produced a Failure owner. The catch parameter's
                    // +1 must still be consumed on the exceptional edge.
                    ArcTerminator.Branch(factoryNormal, factoryUnwind),
                ),
                factoryNormal to ArcBasicBlock(
                    factoryNormal,
                    listOf(
                        ArcOperation.Define(failure, ArcOwnership.Owned),
                        ArcOperation.Destroy(exception),
                        ArcOperation.Destroy(failure),
                    ),
                    ArcTerminator.Return(),
                ),
                factoryUnwind to ArcBasicBlock(
                    factoryUnwind,
                    listOf(ArcOperation.Destroy(exception)),
                    ArcTerminator.Throw,
                ),
            ),
        )
    }

    private fun exactPathLedger(): List<ArcCoroutineCompletionResultPathLedger> {
        val borrow = ArcCoroutineCompletionResultEvent.BorrowCompletionProjection
        val end = ArcCoroutineCompletionResultEvent.EndCompletionBorrow
        val preserve = ArcCoroutineCompletionResultEvent.PreserveOutcomeAcrossReleaseIntercepted
        val backedge = setOf(
            borrow, preserve,
            ArcCoroutineCompletionResultEvent.RetainCompletionBeforeCurrentReplacement,
            ArcCoroutineCompletionResultEvent.ReleaseReplacedCurrentOwner,
            ArcCoroutineCompletionResultEvent.ConsumeOutcomeIntoParameter,
            ArcCoroutineCompletionResultEvent.ClearConsumedOutcomeRoot,
            ArcCoroutineCompletionResultEvent.ReleaseReplacedParameterOwner,
            end,
        )
        val terminal = setOf(
            borrow, preserve, ArcCoroutineCompletionResultEvent.BorrowOutcomeForTerminalResume,
            ArcCoroutineCompletionResultEvent.DestroyOutcomeAfterTerminalResume, end,
        )
        val caughtExceptionCleanup =
            ArcCoroutineCompletionResultEvent.ReleaseCaughtExceptionOwnerAfterFailureMaterialization
        return listOf(
            ArcCoroutineCompletionResultPathLedger(
                ArcCoroutineCompletionResultPath.SuspendedReturn,
                setOf(borrow, ArcCoroutineCompletionResultEvent.DestroySuspendedInvokeResult, end),
            ),
            ArcCoroutineCompletionResultPathLedger(ArcCoroutineCompletionResultPath.SuccessBackedge, backedge),
            ArcCoroutineCompletionResultPathLedger(
                ArcCoroutineCompletionResultPath.FailureBackedge, backedge + caughtExceptionCleanup,
            ),
            ArcCoroutineCompletionResultPathLedger(ArcCoroutineCompletionResultPath.SuccessTerminalReturn, terminal),
            ArcCoroutineCompletionResultPathLedger(
                ArcCoroutineCompletionResultPath.FailureTerminalReturn, terminal + caughtExceptionCleanup,
            ),
            ArcCoroutineCompletionResultPathLedger(
                ArcCoroutineCompletionResultPath.FailureFactoryUnwind,
                setOf(
                    borrow, ArcCoroutineCompletionResultEvent.DestroyCaughtExceptionOnFailureFactoryUnwind, end,
                ),
            ),
            ArcCoroutineCompletionResultPathLedger(
                ArcCoroutineCompletionResultPath.SuccessReleaseInterceptedUnwind,
                setOf(borrow, preserve, ArcCoroutineCompletionResultEvent.DestroyOutcomeOnReleaseUnwind, end),
            ),
            ArcCoroutineCompletionResultPathLedger(
                ArcCoroutineCompletionResultPath.FailureReleaseInterceptedUnwind,
                setOf(
                    borrow, preserve, ArcCoroutineCompletionResultEvent.DestroyOutcomeOnReleaseUnwind,
                    caughtExceptionCleanup, end,
                ),
            ),
            ArcCoroutineCompletionResultPathLedger(
                ArcCoroutineCompletionResultPath.SuccessTerminalResumeUnwind,
                setOf(
                    borrow, preserve, ArcCoroutineCompletionResultEvent.BorrowOutcomeForTerminalResume,
                    ArcCoroutineCompletionResultEvent.DestroyOutcomeOnTerminalUnwind, end,
                ),
            ),
            ArcCoroutineCompletionResultPathLedger(
                ArcCoroutineCompletionResultPath.FailureTerminalResumeUnwind,
                setOf(
                    borrow, preserve, ArcCoroutineCompletionResultEvent.BorrowOutcomeForTerminalResume,
                    ArcCoroutineCompletionResultEvent.DestroyOutcomeOnTerminalUnwind,
                    caughtExceptionCleanup, end,
                ),
            ),
        )
    }
}

private fun ArcCoroutineCompletionResultMode.isProductionMode(): Boolean =
    arcEnabled && linuxX64 && finalBinary && optimizationsEnabled && debugInfoDisabled &&
            diagnosticsDisabled && sanitizerDisabled && coverageDisabled

private fun ArcCoroutineCompletionResultProof.isComplete(): Boolean =
    exactBaseContinuationSelection && exactFinalStrongCompletionField &&
            exactNullCheckedCompletionProjection && completionUsesExhaustive &&
            currentAnchorCoversNormalAndUnwind && invokeSuspendProducesOwnedResult &&
            suspendedExitDestroysOwnedResult && successResultIsIdentityForward &&
            failureFactoryProducesOwnedResult && failureFactoryUnwindDestroysCaughtException &&
            exactReferenceResultCatchOwnershipABI && outcomeJoinIsExactlySuccessOrFailure &&
            failureCopiesExceptionBeforeCatchOwnerRelease &&
            outcomeUsesExhaustive && releaseUnwindDestroysOutcome &&
            backedgeRetainsCompletionBeforeReplacingCurrent &&
            backedgeConsumesOutcomeIntoParameter && terminalCallBorrowsOutcome &&
            terminalNormalDestroysOutcome && terminalUnwindDestroysOutcome &&
            everyExitEndsCompletionBorrow
