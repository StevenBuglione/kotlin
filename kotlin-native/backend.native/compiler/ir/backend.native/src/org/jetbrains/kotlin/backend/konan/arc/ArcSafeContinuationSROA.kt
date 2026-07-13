/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Production boundary for the first SafeContinuation scalar-replacement slice.
 *
 * This deliberately follows Swift's AllocBoxToStack/SROA rule: an allocation may be split only
 * after every transitive use is understood and its complete lifetime is bounded by one function.
 */
internal data class ArcSafeContinuationSROAMode(
    val arcEnabled: Boolean,
    val linuxX64: Boolean,
    val finalBinary: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val diagnosticsDisabled: Boolean,
    val sanitizerDisabled: Boolean,
    val coverageDisabled: Boolean,
    val nonSuspendFunction: Boolean,
    val exactLoweredSuspendFunction: Boolean,
    val nonExternalFunction: Boolean,
)

internal data class ArcSafeContinuationSROAIdentityProof(
    val exactStdlibLibrary: Boolean,
    val exactSafeContinuationClass: Boolean,
    val exactConstructor: Boolean,
    val exactResumeWith: Boolean,
    val exactGetOrThrow: Boolean,
    val exactDelegateField: Boolean,
    val exactResultRefField: Boolean,
    val declarationsHaveStableSignatures: Boolean,
)

/** Whole-region control-flow and escape proof computed from post-lowering IR. */
internal data class ArcSafeContinuationSROALifetimeProof(
    val allocationIsUniqueLocalDefinition: Boolean,
    val allocationHasNoAliases: Boolean,
    val allTransitiveUsesEnumerated: Boolean,
    val exactlyOneDirectResumeWith: Boolean,
    val exactlyOneDirectGetOrThrow: Boolean,
    val resumeDominatesGetOnEveryNormalPath: Boolean,
    val getPostDominatesResumeNormalSuccessor: Boolean,
    val structurallyLinearSingleEntryRegion: Boolean,
    val synchronousResumeProven: Boolean,
    val suspendedStateBranchUnreachable: Boolean,
    val delegateResumeBranchUnreachable: Boolean,
    val noNestedFunctionCapture: Boolean,
    val noThreadOrWorkerEscape: Boolean,
    val noForeignOrExternalEscape: Boolean,
    val noTryOrExceptionalRegionEscape: Boolean,
    val noSuspensionPoint: Boolean,
    val noBackedgeOrCoroutineStateTransition: Boolean,
    val noCoroutineSuspendedReturn: Boolean,
)

/**
 * SROA removes the wrapper, not the ownership of values formerly stored in it. Both scalar
 * reference slots retain ordinary +1 semantics even when the selected success path is trivial.
 */
internal data class ArcSafeContinuationSROAOwnershipProof(
    val delegateIsOrdinaryHeapReference: Boolean,
    val resultMayBeOrdinaryHeapReference: Boolean,
    val interceptedCallProducesOwnedPlusOne: Boolean,
    val delegateScalarConsumesInterceptedPlusOne: Boolean,
    val resultBoxProducesOwnedPlusOne: Boolean,
    val resultScalarConsumesBoxPlusOne: Boolean,
    val undecidedStateIsImmortal: Boolean,
    val freezableAtomicIdentityDoesNotEscape: Boolean,
    val storesRetainBeforeReplacing: Boolean,
    val normalResultIsMovedExactlyOnce: Boolean,
    val successOnlyResultProven: Boolean,
    val constructorFailureCleanupDestroysInitializedSlots: Boolean,
    val noImmortalInferenceForDelegateOrResult: Boolean,
)

internal enum class ArcSafeContinuationSROAEffect {
    ConstructExactSafeContinuation,
    InterceptDelegate,
    InitializeScalarState,
    ProducePrimitiveResumeValue,
    LoadResultCompanion,
    BoxResumeValue,
    ConstructResultSuccess,
    DirectResumeWith,
    ReplaceScalarResult,
    StructuralUnit,
    DirectGetOrThrow,
    ConsumeScalarResult,
    DestroyScalarDelegate,
    UserCall,
    VirtualOrInterfaceCall,
    ForeignOrExternalCall,
    ThreadOrWorkerTransfer,
    Suspension,
    TryRegion,
    NestedFunction,
    Unknown,
}

/** Exact logical sites removed by scalar replacement; these are not emitted-code measurements. */
internal enum class ArcSafeContinuationSROAReductionSite(
    val logicalStrongUpdates: Int = 0,
    val logicalOwnedLoadsOrRetains: Int = 0,
    val logicalHeapAllocations: Int = 0,
    val logicalHeapFrees: Int = 0,
) {
    SafeContinuationObjectLifetime(logicalHeapAllocations = 1, logicalHeapFrees = 1),
    ResultRefObjectLifetime(logicalHeapAllocations = 1, logicalHeapFrees = 1),
    DelegateFieldInitialization(logicalStrongUpdates = 1, logicalOwnedLoadsOrRetains = 1),
    ResultRefFieldInitialization(logicalStrongUpdates = 1, logicalOwnedLoadsOrRetains = 1),
    AtomicInitialValueStore(logicalStrongUpdates = 1, logicalOwnedLoadsOrRetains = 1),
    ResumeUndecidedCasStore(logicalStrongUpdates = 1, logicalOwnedLoadsOrRetains = 1),
    ResultRefCleanup(logicalStrongUpdates = 1, logicalOwnedLoadsOrRetains = 1),
    ResumeAtomicLoad(logicalOwnedLoadsOrRetains = 1),
    GetOrThrowAtomicLoad(logicalOwnedLoadsOrRetains = 1),
}

internal data class ArcSafeContinuationSROAPhysicalReduction(
    val heapUpdates: Int,
    val heapRefAdds: Int,
    val heapAllocations: Int,
    val heapFrees: Int,
)

/**
 * Owning scalar storage cannot be represented by [ArcFunctionPlan]: its [ArcOperation.StrongLoad]
 * is a retaining copy and deliberately leaves the source storage initialized. SROA needs two
 * additional consuming operations, so this small proof model keeps them explicit and rejects any
 * normal or exceptional exit with live owning storage.
 */
internal sealed class ArcSafeContinuationSROAStorageOperation {
    data class Define(val value: ArcValue, val ownership: ArcOwnership) : ArcSafeContinuationSROAStorageOperation()
    data class Initialize(val storage: ArcStorage, val value: ArcValue) : ArcSafeContinuationSROAStorageOperation()
    data class Take(val storage: ArcStorage, val result: ArcValue) : ArcSafeContinuationSROAStorageOperation()
    data class DestroyStorage(val storage: ArcStorage) : ArcSafeContinuationSROAStorageOperation()
    data class DestroyValue(val value: ArcValue) : ArcSafeContinuationSROAStorageOperation()
}

internal data class ArcSafeContinuationSROAStorageBlock(
    val id: ArcBlockId,
    val operations: List<ArcSafeContinuationSROAStorageOperation>,
    val terminator: ArcTerminator,
)

internal data class ArcSafeContinuationSROAStoragePlan(
    val entry: ArcBlockId,
    val blocks: Map<ArcBlockId, ArcSafeContinuationSROAStorageBlock>,
)

internal sealed class ArcSafeContinuationSROAStorageVerificationResult {
    object Success : ArcSafeContinuationSROAStorageVerificationResult()
    data class Failure(val detail: String) : ArcSafeContinuationSROAStorageVerificationResult()
}

internal object ArcSafeContinuationSROAStorageVerifier {
    private data class State(
        val values: Map<ArcValue, ArcOwnership> = emptyMap(),
        val storage: Map<ArcStorage, ArcOwnership> = emptyMap(),
    )

    fun verify(plan: ArcSafeContinuationSROAStoragePlan): ArcSafeContinuationSROAStorageVerificationResult {
        if (plan.entry !in plan.blocks) return failure("missing entry block ${plan.entry}")
        val incoming = mutableMapOf(plan.entry to State())
        val pending = ArrayDeque<ArcBlockId>().apply { add(plan.entry) }
        while (pending.isNotEmpty()) {
            val blockId = pending.removeFirst()
            val block = plan.blocks[blockId] ?: return failure("missing block $blockId")
            var state = incoming.getValue(blockId)
            for (operation in block.operations) {
                val values = state.values.toMutableMap()
                val storage = state.storage.toMutableMap()
                when (operation) {
                    is ArcSafeContinuationSROAStorageOperation.Define -> {
                        if (operation.value in values) return failure("${operation.value} defined twice")
                        values[operation.value] = operation.ownership
                    }
                    is ArcSafeContinuationSROAStorageOperation.Initialize -> {
                        val ownership = values.remove(operation.value)
                            ?: return failure("initialize from dead ${operation.value}")
                        if (operation.storage in storage) return failure("${operation.storage} initialized twice")
                        storage[operation.storage] = ownership
                    }
                    is ArcSafeContinuationSROAStorageOperation.Take -> {
                        val ownership = storage.remove(operation.storage)
                            ?: return failure("take from uninitialized ${operation.storage}")
                        if (operation.result in values) return failure("${operation.result} defined twice")
                        values[operation.result] = ownership
                    }
                    is ArcSafeContinuationSROAStorageOperation.DestroyStorage -> {
                        if (storage.remove(operation.storage) == null) {
                            return failure("destroy of uninitialized ${operation.storage}")
                        }
                    }
                    is ArcSafeContinuationSROAStorageOperation.DestroyValue -> {
                        if (values.remove(operation.value) == null) return failure("destroy of dead ${operation.value}")
                    }
                }
                state = State(values, storage)
            }
            val successors = when (val terminator = block.terminator) {
                is ArcTerminator.Jump -> listOf(terminator.target)
                is ArcTerminator.Branch -> listOf(terminator.trueTarget, terminator.falseTarget)
                is ArcTerminator.Return -> {
                    val returned = terminator.value
                    if (returned != null && state.values.removeReturned(returned) == null) {
                        return failure("return of dead $returned")
                    }
                    val remaining = if (returned == null) state.values else state.values - returned
                    if (state.storage.isNotEmpty()) return failure("live storage at return: ${state.storage.keys}")
                    if (remaining.any { it.value == ArcOwnership.Owned }) {
                        return failure("live owned value at return: ${remaining.keys}")
                    }
                    emptyList()
                }
                ArcTerminator.Throw, ArcTerminator.Unreachable -> {
                    if (state.storage.isNotEmpty()) return failure("live storage at exceptional exit: ${state.storage.keys}")
                    if (state.values.any { it.value == ArcOwnership.Owned }) {
                        return failure("live owned value at exceptional exit: ${state.values.keys}")
                    }
                    emptyList()
                }
            }
            successors.forEach { successor ->
                if (successor !in plan.blocks) return failure("missing successor $successor")
                val previous = incoming[successor]
                if (previous == null) {
                    incoming[successor] = state
                    pending += successor
                } else if (previous != state) {
                    return failure("incompatible ownership states at $successor")
                }
            }
        }
        return ArcSafeContinuationSROAStorageVerificationResult.Success
    }

    private fun Map<ArcValue, ArcOwnership>.removeReturned(value: ArcValue): ArcOwnership? = this[value]
    private fun failure(detail: String) = ArcSafeContinuationSROAStorageVerificationResult.Failure(detail)
}

internal data class ArcSafeContinuationSROAReductionPlan(
    val sites: List<ArcSafeContinuationSROAReductionSite>,
    val emitted: Boolean,
    /** Populated only from emitted-code inspection; never inferred from the logical ledger. */
    val measuredPhysical: ArcSafeContinuationSROAPhysicalReduction? = null,
) {
    init {
        require(emitted || measuredPhysical == null) {
            "proof-only SafeContinuation SROA cannot claim a physical reduction"
        }
    }
    val logicalStrongUpdates: Int get() = sites.sumOf { it.logicalStrongUpdates }
    val logicalOwnedLoadsOrRetains: Int get() = sites.sumOf { it.logicalOwnedLoadsOrRetains }
    val logicalHeapAllocations: Int get() = sites.sumOf { it.logicalHeapAllocations }
    val logicalHeapFrees: Int get() = sites.sumOf { it.logicalHeapFrees }
}

internal data class ArcSafeContinuationSROACandidate<T : Any>(
    val functionBinding: T,
    val allocationBinding: T,
    val localBinding: T,
    val constructorBinding: T,
    val delegateBinding: T,
    val resumeBinding: T,
    val resumeResultBinding: T,
    val getOrThrowBinding: T,
    val scalarDelegateSlotBinding: T,
    val scalarStateSlotBinding: T,
    val scalarResultSlotBinding: T,
    val interceptedBinding: T,
    val resumeValueProducerBinding: T,
    val resultCompanionBinding: T,
    val resultBoxBinding: T,
    val resultConstructorBinding: T,
    val unitInstanceBindings: List<T>,
    val exactIdentityBindings: List<T> = listOf(
        functionBinding, allocationBinding, localBinding, constructorBinding, delegateBinding,
        resumeBinding, resumeResultBinding, getOrThrowBinding, scalarDelegateSlotBinding,
        scalarStateSlotBinding, scalarResultSlotBinding, interceptedBinding,
        resumeValueProducerBinding, resultCompanionBinding, resultBoxBinding,
        resultConstructorBinding,
    ) + unitInstanceBindings,
    val mode: ArcSafeContinuationSROAMode,
    val identity: ArcSafeContinuationSROAIdentityProof,
    val lifetime: ArcSafeContinuationSROALifetimeProof,
    val ownership: ArcSafeContinuationSROAOwnershipProof,
    val effects: List<ArcSafeContinuationSROAEffect>,
)

internal data class ArcSafeContinuationSROASelection<T : Any>(
    val candidate: ArcSafeContinuationSROACandidate<T>,
    val ownershipPlan: ArcFunctionPlan,
    val storagePlan: ArcSafeContinuationSROAStoragePlan,
    val reduction: ArcSafeContinuationSROAReductionPlan = ArcSafeContinuationSROAReductionPlan(
        sites = ArcSafeContinuationSROAReductionSite.entries,
        emitted = false,
    ),
)

internal enum class ArcSafeContinuationSROARejectionReason {
    UnsupportedCompilationMode,
    DeclarationIdentityMismatch,
    DuplicateStructuralIdentity,
    EscapingAllocation,
    InvalidControlFlow,
    SuspensionOrDelegatePathReachable,
    UnsupportedBoundary,
    InvalidOrdinaryHeapOwnership,
    UnsupportedEffect,
    OwnershipVerifierRejected,
}

internal data class ArcSafeContinuationSROAAnalysisResult<T : Any>(
    val selection: ArcSafeContinuationSROASelection<T>?,
    val rejection: ArcSafeContinuationSROARejectionReason?,
)

internal object ArcSafeContinuationSROAAnalysis {
    fun <T : Any> select(candidate: ArcSafeContinuationSROACandidate<T>): ArcSafeContinuationSROAAnalysisResult<T> {
        if (!candidate.mode.isProductionArcMode()) {
            return rejected(ArcSafeContinuationSROARejectionReason.UnsupportedCompilationMode)
        }
        if (!candidate.identity.isExact()) {
            return rejected(ArcSafeContinuationSROARejectionReason.DeclarationIdentityMismatch)
        }
        if (!candidate.exactIdentityBindings.haveUniqueIdentity()) {
            return rejected(ArcSafeContinuationSROARejectionReason.DuplicateStructuralIdentity)
        }
        with(candidate.lifetime) {
            if (!allocationIsUniqueLocalDefinition || !allocationHasNoAliases ||
                !allTransitiveUsesEnumerated || !exactlyOneDirectResumeWith ||
                !exactlyOneDirectGetOrThrow || !noNestedFunctionCapture
            ) return rejected(ArcSafeContinuationSROARejectionReason.EscapingAllocation)
            if (!resumeDominatesGetOnEveryNormalPath || !getPostDominatesResumeNormalSuccessor ||
                !structurallyLinearSingleEntryRegion || !synchronousResumeProven
            ) return rejected(ArcSafeContinuationSROARejectionReason.InvalidControlFlow)
            if (!suspendedStateBranchUnreachable || !delegateResumeBranchUnreachable) {
                return rejected(ArcSafeContinuationSROARejectionReason.SuspensionOrDelegatePathReachable)
            }
            if (!noThreadOrWorkerEscape || !noForeignOrExternalEscape ||
                !noTryOrExceptionalRegionEscape || !noSuspensionPoint ||
                !noBackedgeOrCoroutineStateTransition || !noCoroutineSuspendedReturn
            ) return rejected(ArcSafeContinuationSROARejectionReason.UnsupportedBoundary)
        }
        if (!candidate.ownership.isExactOrdinaryHeapOwnership()) {
            return rejected(ArcSafeContinuationSROARejectionReason.InvalidOrdinaryHeapOwnership)
        }
        if (candidate.effects != exactEffects) {
            return rejected(ArcSafeContinuationSROARejectionReason.UnsupportedEffect)
        }
        val ownershipPlan = buildOwnershipPlan()
        if (ArcOwnershipVerifier.verify(ownershipPlan) != ArcOwnershipVerificationResult.Success) {
            return rejected(ArcSafeContinuationSROARejectionReason.OwnershipVerifierRejected)
        }
        val storagePlan = buildStoragePlan()
        if (ArcSafeContinuationSROAStorageVerifier.verify(storagePlan) !=
            ArcSafeContinuationSROAStorageVerificationResult.Success
        ) return rejected(ArcSafeContinuationSROARejectionReason.OwnershipVerifierRejected)
        return ArcSafeContinuationSROAAnalysisResult(
            ArcSafeContinuationSROASelection(candidate, ownershipPlan, storagePlan),
            null,
        )
    }

    private fun <T : Any> rejected(reason: ArcSafeContinuationSROARejectionReason) =
        ArcSafeContinuationSROAAnalysisResult<T>(null, reason)

    private fun buildOwnershipPlan(): ArcFunctionPlan {
        val entry = ArcBlockId("entry")
        val initialized = ArcBlockId("initialized")
        val constructorFailure = ArcBlockId("constructorFailure")
        val resultSuccess = ArcBlockId("resultSuccess")
        val resultFailure = ArcBlockId("resultFailure")
        val completion = ArcValue("completion+0")
        val intercepted = ArcValue("intercepted+1")
        val undecided = ArcValue("UNDECIDED")
        val failedDelegateCleanup = ArcValue("failed.delegate+1")
        val failedStateCleanup = ArcValue("failed.state+1")
        val failedResultDelegateCleanup = ArcValue("failed.result.delegate+1")
        val consumedState = ArcValue("consumed.state+1")
        val boxedResult = ArcValue("Result.success.box+1")
        val returnedResult = ArcValue("returned.result+1")
        val delegateCleanup = ArcValue("delegate.cleanup+1")
        val delegateStorage = ArcStorage("scalar.delegate")
        val stateStorage = ArcStorage("scalar.state")
        val resultStorage = ArcStorage("scalar.result")
        return ArcFunctionPlan(
            functionName = "synchronous nonescaping SafeContinuation SROA",
            entry = entry,
            entryValues = mapOf(completion to ArcOwnership.Guaranteed),
            entryInitializedStorage = emptySet(),
            blocks = linkedMapOf(
                entry to ArcBasicBlock(
                    entry,
                    listOf(
                        ArcOperation.Use(completion, ArcPlanLocation("intercepted borrowed parameter")),
                        ArcOperation.Define(intercepted, ArcOwnership.Owned, ArcPlanLocation("intercepted owned result")),
                        ArcOperation.StrongStore(delegateStorage, intercepted, ArcPlanLocation("move +1 into scalar delegate")),
                        ArcOperation.Destroy(intercepted, ArcPlanLocation("consume delegate store source")),
                    ),
                    ArcTerminator.Branch(initialized, constructorFailure),
                ),
                constructorFailure to ArcBasicBlock(
                    constructorFailure,
                    listOf(
                        ArcOperation.StrongLoad(delegateStorage, failedDelegateCleanup, ArcPlanLocation("constructor failure cleanup")),
                        ArcOperation.Destroy(failedDelegateCleanup, ArcPlanLocation("destroy initialized delegate on failure")),
                    ),
                    ArcTerminator.Throw,
                ),
                initialized to ArcBasicBlock(
                    initialized,
                    listOf(
                        ArcOperation.Define(undecided, ArcOwnership.Immortal, ArcPlanLocation("UNDECIDED scalar state")),
                        ArcOperation.StrongStore(stateStorage, undecided, ArcPlanLocation("initialize scalar state")),
                    ),
                    ArcTerminator.Branch(resultSuccess, resultFailure),
                ),
                resultFailure to ArcBasicBlock(
                    resultFailure,
                    listOf(
                        ArcOperation.StrongLoad(stateStorage, failedStateCleanup, ArcPlanLocation("result failure state cleanup")),
                        ArcOperation.Destroy(failedStateCleanup, ArcPlanLocation("destroy initialized state on failure")),
                        ArcOperation.StrongLoad(delegateStorage, failedResultDelegateCleanup, ArcPlanLocation("result failure delegate cleanup")),
                        ArcOperation.Destroy(failedResultDelegateCleanup, ArcPlanLocation("destroy delegate on result failure")),
                    ),
                    ArcTerminator.Throw,
                ),
                resultSuccess to ArcBasicBlock(
                    resultSuccess,
                    listOf(
                        ArcOperation.StrongLoad(stateStorage, consumedState, ArcPlanLocation("consume UNDECIDED state")),
                        ArcOperation.Define(boxedResult, ArcOwnership.Owned, ArcPlanLocation("Result.success boxed value")),
                        ArcOperation.StrongStore(resultStorage, boxedResult, ArcPlanLocation("move +1 into scalar result")),
                        ArcOperation.Destroy(boxedResult, ArcPlanLocation("consume result store source")),
                        ArcOperation.Destroy(consumedState, ArcPlanLocation("replace UNDECIDED with success state")),
                        ArcOperation.StrongLoad(resultStorage, returnedResult, ArcPlanLocation("move scalar result to caller")),
                        ArcOperation.StrongLoad(delegateStorage, delegateCleanup, ArcPlanLocation("delegate lifetime frontier")),
                        ArcOperation.Destroy(delegateCleanup, ArcPlanLocation("destroy scalar delegate")),
                    ),
                    ArcTerminator.Return(returnedResult),
                ),
            ),
        )
    }

    /**
     * Exact consuming-storage proof. The two failure edges after result-state initialization model
     * failure before a boxed owner exists and failure after it exists but before publication.
     */
    private fun buildStoragePlan(): ArcSafeContinuationSROAStoragePlan {
        val entry = ArcBlockId("storage.entry")
        val constructorComplete = ArcBlockId("storage.constructorComplete")
        val constructorFailure = ArcBlockId("storage.constructorFailure")
        val boxComplete = ArcBlockId("storage.boxComplete")
        val boxFailure = ArcBlockId("storage.boxFailure")
        val resultComplete = ArcBlockId("storage.resultComplete")
        val resultFailure = ArcBlockId("storage.resultFailure")
        val completion = ArcValue("storage.completion+0")
        val intercepted = ArcValue("storage.intercepted+1")
        val undecided = ArcValue("storage.UNDECIDED")
        val boxed = ArcValue("storage.boxed+1")
        val oldState = ArcValue("storage.oldState+1")
        val returned = ArcValue("storage.returned+1")
        val delegate = ArcStorage("storage.delegate")
        val state = ArcStorage("storage.state")
        val result = ArcStorage("storage.result")
        fun destroyInitialized(slots: List<ArcStorage>) = slots.map {
            ArcSafeContinuationSROAStorageOperation.DestroyStorage(it)
        }
        return ArcSafeContinuationSROAStoragePlan(entry, linkedMapOf(
            entry to ArcSafeContinuationSROAStorageBlock(entry, listOf(
                ArcSafeContinuationSROAStorageOperation.Define(completion, ArcOwnership.Guaranteed),
                ArcSafeContinuationSROAStorageOperation.Define(intercepted, ArcOwnership.Owned),
                ArcSafeContinuationSROAStorageOperation.Initialize(delegate, intercepted),
            ), ArcTerminator.Branch(constructorComplete, constructorFailure)),
            constructorFailure to ArcSafeContinuationSROAStorageBlock(
                constructorFailure, destroyInitialized(listOf(delegate)), ArcTerminator.Throw,
            ),
            constructorComplete to ArcSafeContinuationSROAStorageBlock(constructorComplete, listOf(
                ArcSafeContinuationSROAStorageOperation.Define(undecided, ArcOwnership.Immortal),
                ArcSafeContinuationSROAStorageOperation.Initialize(state, undecided),
            ), ArcTerminator.Branch(boxComplete, boxFailure)),
            boxFailure to ArcSafeContinuationSROAStorageBlock(
                boxFailure, destroyInitialized(listOf(state, delegate)), ArcTerminator.Throw,
            ),
            boxComplete to ArcSafeContinuationSROAStorageBlock(boxComplete, listOf(
                ArcSafeContinuationSROAStorageOperation.Define(boxed, ArcOwnership.Owned),
            ), ArcTerminator.Branch(resultComplete, resultFailure)),
            resultFailure to ArcSafeContinuationSROAStorageBlock(resultFailure, listOf(
                ArcSafeContinuationSROAStorageOperation.DestroyValue(boxed),
            ) + destroyInitialized(listOf(state, delegate)), ArcTerminator.Throw),
            resultComplete to ArcSafeContinuationSROAStorageBlock(resultComplete, listOf(
                ArcSafeContinuationSROAStorageOperation.Initialize(result, boxed),
                ArcSafeContinuationSROAStorageOperation.Take(state, oldState),
                ArcSafeContinuationSROAStorageOperation.DestroyValue(oldState),
                ArcSafeContinuationSROAStorageOperation.Take(result, returned),
                ArcSafeContinuationSROAStorageOperation.DestroyStorage(delegate),
            ), ArcTerminator.Return(returned)),
        ))
    }

    private val exactEffects = listOf(
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
    )
}

internal class ArcSafeContinuationSROAConsumptionLedger<T : Any>(
    private val selection: ArcSafeContinuationSROASelection<T>,
) {
    private var allocationConsumed = false
    private var structuralCallsConsumed = false
    private var resumeConsumed = false
    private var getOrThrowConsumed = false
    private var completeInventoryAuthenticated = false
    private var reductionSitesConsumed = false
    private val consumedIdentities = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

    private fun mark(binding: T) {
        consumedIdentities += binding
    }

    fun consumeAllocation(allocation: T, local: T, constructor: T) {
        check(allocation === selection.candidate.allocationBinding) { "SafeContinuation SROA allocation identity drifted" }
        check(local === selection.candidate.localBinding) { "SafeContinuation SROA local identity drifted" }
        check(constructor === selection.candidate.constructorBinding) { "SafeContinuation SROA constructor identity drifted" }
        check(!allocationConsumed) { "SafeContinuation SROA allocation consumed twice" }
        mark(allocation); mark(local); mark(constructor)
        allocationConsumed = true
    }

    fun consumeResume(call: T, result: T) {
        check(call === selection.candidate.resumeBinding) { "SafeContinuation SROA resume identity drifted" }
        check(result === selection.candidate.resumeResultBinding) { "SafeContinuation SROA resume-result identity drifted" }
        check(!resumeConsumed) { "SafeContinuation SROA resume consumed twice" }
        mark(call); mark(result)
        resumeConsumed = true
    }

    fun consumeStructuralCalls(
        intercepted: T,
        resumeValueProducer: T,
        resultCompanion: T,
        resultBox: T,
        resultConstructor: T,
        unitInstances: List<T>,
    ) {
        check(intercepted === selection.candidate.interceptedBinding) { "SafeContinuation SROA intercepted identity drifted" }
        check(resumeValueProducer === selection.candidate.resumeValueProducerBinding) { "SafeContinuation SROA value-producer identity drifted" }
        check(resultCompanion === selection.candidate.resultCompanionBinding) { "SafeContinuation SROA Result companion identity drifted" }
        check(resultBox === selection.candidate.resultBoxBinding) { "SafeContinuation SROA Result box identity drifted" }
        check(resultConstructor === selection.candidate.resultConstructorBinding) { "SafeContinuation SROA Result constructor identity drifted" }
        val expectedUnits = selection.candidate.unitInstanceBindings
        check(unitInstances.size == expectedUnits.size && unitInstances.indices.all { unitInstances[it] === expectedUnits[it] }) {
            "SafeContinuation SROA structural Unit identity inventory drifted"
        }
        check(!structuralCallsConsumed) { "SafeContinuation SROA structural calls consumed twice" }
        mark(intercepted); mark(resumeValueProducer); mark(resultCompanion); mark(resultBox); mark(resultConstructor)
        unitInstances.forEach(::mark)
        structuralCallsConsumed = true
    }

    fun consumeGetOrThrow(call: T) {
        check(call === selection.candidate.getOrThrowBinding) { "SafeContinuation SROA getOrThrow identity drifted" }
        check(!getOrThrowConsumed) { "SafeContinuation SROA getOrThrow consumed twice" }
        mark(call)
        getOrThrowConsumed = true
    }

    /** Seal every declaration/body token, including constructor and atomic-contract nodes. */
    fun consumeAuthenticatedInventory(inventory: List<T>) {
        val expected = selection.candidate.exactIdentityBindings
        check(inventory.size == expected.size && inventory.indices.all { inventory[it] === expected[it] }) {
            "SafeContinuation SROA complete identity inventory drifted"
        }
        check(!completeInventoryAuthenticated) { "SafeContinuation SROA complete identity inventory consumed twice" }
        inventory.forEach(::mark)
        completeInventoryAuthenticated = true
    }

    /** An emitter must account for each logical removal site exactly once before claiming output. */
    fun consumeReductionSites(sites: List<ArcSafeContinuationSROAReductionSite>) {
        check(sites == selection.reduction.sites) { "SafeContinuation SROA reduction-site inventory drifted" }
        check(!reductionSitesConsumed) { "SafeContinuation SROA reduction sites consumed twice" }
        reductionSitesConsumed = true
    }

    fun verifyComplete() {
        val fullInventoryConsumed = consumedIdentities.size == selection.candidate.exactIdentityBindings.size
        check(allocationConsumed && structuralCallsConsumed && resumeConsumed && getOrThrowConsumed &&
            completeInventoryAuthenticated && reductionSitesConsumed && fullInventoryConsumed
        ) {
            "SafeContinuation SROA emission incomplete: allocation=$allocationConsumed, " +
                    "structuralCalls=$structuralCallsConsumed, resume=$resumeConsumed, " +
                    "getOrThrow=$getOrThrowConsumed, fullInventory=$completeInventoryAuthenticated/" +
                    "$fullInventoryConsumed, reductionSites=$reductionSitesConsumed"
        }
    }
}

private fun ArcSafeContinuationSROAMode.isProductionArcMode(): Boolean =
    arcEnabled && linuxX64 && finalBinary && optimizationsEnabled && debugInfoDisabled &&
            diagnosticsDisabled && sanitizerDisabled && coverageDisabled &&
            (nonSuspendFunction || exactLoweredSuspendFunction) && nonExternalFunction

private fun ArcSafeContinuationSROAIdentityProof.isExact(): Boolean =
    exactStdlibLibrary && exactSafeContinuationClass && exactConstructor && exactResumeWith &&
            exactGetOrThrow && exactDelegateField && exactResultRefField && declarationsHaveStableSignatures

private fun ArcSafeContinuationSROAOwnershipProof.isExactOrdinaryHeapOwnership(): Boolean =
    delegateIsOrdinaryHeapReference && resultMayBeOrdinaryHeapReference &&
            interceptedCallProducesOwnedPlusOne && delegateScalarConsumesInterceptedPlusOne &&
            resultBoxProducesOwnedPlusOne && resultScalarConsumesBoxPlusOne &&
            undecidedStateIsImmortal && freezableAtomicIdentityDoesNotEscape &&
            storesRetainBeforeReplacing && normalResultIsMovedExactlyOnce && successOnlyResultProven &&
            constructorFailureCleanupDestroysInitializedSlots && noImmortalInferenceForDelegateOrResult

private fun <T : Any> List<T>.haveUniqueIdentity(): Boolean = indices.all { index ->
    (index + 1 until size).none { other -> this[index] === this[other] }
}
