/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.KonanFqNames
import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.backend.konan.descriptors.isBuiltInOperator
import org.jetbrains.kotlin.backend.konan.descriptors.isTypedIntrinsic
import org.jetbrains.kotlin.backend.konan.ir.getSuperClassNotAny
import org.jetbrains.kotlin.backend.konan.ir.KonanNameConventions
import org.jetbrains.kotlin.backend.konan.ir.getAnnotationArgumentValue
import org.jetbrains.kotlin.backend.konan.ir.konanLibrary
import org.jetbrains.kotlin.backend.konan.lower.DECLARATION_ORIGIN_ENUM
import org.jetbrains.kotlin.backend.konan.isObjCBridgeBased
import org.jetbrains.kotlin.backend.konan.llvm.Lifetime
import org.jetbrains.kotlin.backend.konan.llvm.IntrinsicType
import org.jetbrains.kotlin.backend.konan.llvm.tryGetIntrinsicType
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.reportCompilationError
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrSuspendableExpression
import org.jetbrains.kotlin.ir.expressions.IrSuspensionPoint
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isCharArray
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.allParameters
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getArgumentsWithIr
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.isElseBranch
import org.jetbrains.kotlin.ir.util.isOverridable
import org.jetbrains.kotlin.ir.util.isReal
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.library.KotlinLibrary
import java.util.Collections
import java.util.IdentityHashMap

internal data class ArcOwnershipPlanningInput(
    val module: org.jetbrains.kotlin.ir.declarations.IrModuleFragment,
    val lifetimes: Map<IrElement, Lifetime>,
)

internal data class ArcOwnershipPlanningReport(
    val analyzedFunctions: Int,
    val skippedFunctions: Int,
    val plans: List<ArcFunctionPlan>,
    val classifications: ArcOwnershipClassificationCounts,
    val optimization: ArcOwnershipOptimizationMetrics,
    val codegenPlan: ArcCodegenOwnershipPlan,
) {
    companion object {
        val Disabled = ArcOwnershipPlanningReport(
            analyzedFunctions = 0,
            skippedFunctions = 0,
            plans = emptyList(),
            classifications = ArcOwnershipClassificationCounts(),
            optimization = ArcOwnershipOptimizationMetrics(),
            codegenPlan = ArcCodegenOwnershipPlan.Empty,
        )
    }
}

/**
 * The deliberately small part of a verified ownership plan that codegen is allowed to consume.
 *
 * IR declarations are used as identity keys. This plan never crosses the compilation boundary and
 * therefore does not affect KLIB metadata or ABI.
 */
internal data class ArcCodegenOwnershipPlan(
    val ownedResultForwarding: Map<IrSimpleFunction, ArcOwnedResultForwarding>,
    val coroutineResultSlotForwardingCalls: Set<IrCall>,
    val lockedReadResultSlotForwardingCalls: Map<IrCall, ArcLockedReadCanonicalPlan>,
    val returnedReceiverBorrowCalls: Set<IrCall>,
    val discardedReturnedReceiverGroupsByCall: Map<IrCall, ArcDiscardedReturnedReceiverGroup>,
    val coroutineSpillMovesByVariable: Map<IrVariable, ArcCoroutineSpillMovePlan>,
    val coroutineSpillMovesByReturn: Map<IrReturn, ArcCoroutineSpillMovePlan>,
    val safeContinuationOwnedResultVariables: Set<IrVariable>,
    val safeContinuationOwnedResultAssignments: Set<IrSetValue>,
    val safeContinuationMovedResultReads: Set<IrGetValue>,
    val joinedReferenceSlots: Map<IrVariable, ArcJoinedReferenceSlotPlan>,
    val borrowedGuaranteedAliases: Set<IrVariable>,
    val borrowedMutableReads: Set<IrGetValue>,
    val borrowedFieldReceivers: Set<IrGetValue>,
    val borrowedStrongFieldLoads: Set<IrGetField>,
    val borrowedStrongCallFieldLoads: Set<IrGetField>,
    val borrowedStrongProjectionStores: Set<IrSetValue>,
    val rootedProjectionVariables: Set<IrVariable>,
    val rootedProjectionReads: Set<IrGetValue>,
    val rootedProjectionFieldLoads: Set<IrGetField>,
    val rootedProjectionStores: Set<IrSetValue>,
    val borrowedArrayElementCalls: Set<IrCall>,
    val mutableConstructorInitializers: Set<IrVariable>,
    val scopedArcReferenceLoads: Map<IrCall, IrExpression>,
    val rootedGlobalProjections: Map<IrCall, ArcRootedGlobalProjectionPlan>,
) {
    val scopedArcReferenceLoadBoundaries: Set<IrExpression> =
        Collections.newSetFromMap(IdentityHashMap<IrExpression, Boolean>()).apply {
            addAll(scopedArcReferenceLoads.values)
        }

    companion object {
        val Empty = ArcCodegenOwnershipPlan(
            ownedResultForwarding = emptyMap(),
            coroutineResultSlotForwardingCalls = emptySet(),
            lockedReadResultSlotForwardingCalls = emptyMap(),
            returnedReceiverBorrowCalls = emptySet(),
            discardedReturnedReceiverGroupsByCall = emptyMap(),
            coroutineSpillMovesByVariable = emptyMap(),
            coroutineSpillMovesByReturn = emptyMap(),
            safeContinuationOwnedResultVariables = emptySet(),
            safeContinuationOwnedResultAssignments = emptySet(),
            safeContinuationMovedResultReads = emptySet(),
            joinedReferenceSlots = emptyMap(),
            borrowedGuaranteedAliases = emptySet(),
            borrowedMutableReads = emptySet(),
            borrowedFieldReceivers = emptySet(),
            borrowedStrongFieldLoads = emptySet(),
            borrowedStrongCallFieldLoads = emptySet(),
            borrowedStrongProjectionStores = emptySet(),
            rootedProjectionVariables = emptySet(),
            rootedProjectionReads = emptySet(),
            rootedProjectionFieldLoads = emptySet(),
            rootedProjectionStores = emptySet(),
            borrowedArrayElementCalls = emptySet(),
            mutableConstructorInitializers = emptySet(),
            scopedArcReferenceLoads = emptyMap(),
            rootedGlobalProjections = emptyMap(),
        )
    }
}

internal data class ArcRootedGlobalProjectionPlan(
    val rootField: IrField,
    val getter: IrSimpleFunction,
    val getterId: Int,
)

internal data class ArcOwnedResultForwarding(
    val producer: IrVariable,
    val returned: IrVariable,
)

internal data class ArcCoroutineSpillMovePlan(
    val function: IrSimpleFunction,
    val producer: IrCall,
    val spillVariable: IrVariable,
    val returnExpression: IrReturn,
    val ownership: ArcCoroutineOwnershipPlan,
)

internal data class ArcLockedReadCanonicalPlan(
    val ownerClass: IrClass,
    val getter: IrSimpleFunction,
    val runtimeGetter: IrSimpleFunction,
    val tailCall: IrCall,
    val getterSignature: IdSignature,
    val runtimeGetterSignature: IdSignature,
    val ownerClassSignature: IdSignature,
    val stdlibLibrary: KotlinLibrary,
)

private data class ArcSafeContinuationGetOrThrowPlan(
    val function: IrSimpleFunction,
    val resultVariable: IrVariable,
    val resultAssignments: Set<IrSetValue>,
    val borrowedResultReads: Set<IrGetValue>,
    val movedResultRead: IrGetValue,
    val borrowedResultRefLoads: Set<IrGetField>,
)

internal data class ArcSuspendLikeMarkers(
    val sourceSuspend: Boolean,
    val loweredSuspendOrigin: Boolean,
    val coroutineImplFunctionOrigin: Boolean,
    val coroutineImplParentOrigin: Boolean,
    val continuationParameter: Boolean,
)

internal fun ArcSuspendLikeMarkers.isSuspendLike(): Boolean =
    sourceSuspend || loweredSuspendOrigin || coroutineImplFunctionOrigin ||
            coroutineImplParentOrigin || continuationParameter

/** Fail closed on every stable marker left by continuation-stub and state-machine lowering. */
internal fun IrSimpleFunction.isArcSuspendLike(): Boolean {
    if (isSuspend || origin == IrDeclarationOrigin.LOWERED_SUSPEND_FUNCTION ||
        origin.name == "COROUTINE_IMPL" || (parent as? IrClass)?.origin?.name == "COROUTINE_IMPL"
    ) return true
    return valueParameters.any {
        it.origin == IrDeclarationOrigin.CONTINUATION ||
                it.type.getClass()?.fqNameForIrSerialization?.asString() == "kotlin.coroutines.Continuation"
    }
}

/** Authorization for remembering that a direct object call initialized its exact ARC result slot. */
internal data class ArcResultSlotForwardingEligibility(
    val arcEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val explicitResultSlot: Boolean,
    val directKotlinCall: Boolean,
    val nonExternalCall: Boolean,
    val nonSuspendCall: Boolean,
    val referenceResult: Boolean,
    val nonUnitResult: Boolean,
    val nonNothingResult: Boolean,
)

internal fun ArcResultSlotForwardingEligibility.isAuthorized(): Boolean =
    arcEnabled && debugInfoDisabled && explicitResultSlot && directKotlinCall && nonExternalCall &&
            nonSuspendCall && referenceResult && nonUnitResult && nonNothingResult

/** Authorization for one identity-selected lowered suspend adapter tail call. */
internal data class ArcCoroutineResultSlotForwardingEligibility(
    val arcEnabled: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val exactCallIdentitySelected: Boolean,
    val explicitResultSlot: Boolean,
    val exactResultSlotIdentity: Boolean,
    val directKotlinCall: Boolean,
    val nonExternalCall: Boolean,
    val nonVirtualCall: Boolean,
    val ownedResultConvention: Boolean,
    val referenceResult: Boolean,
    val nonUnitResult: Boolean,
    val nonNothingResult: Boolean,
    val allNormalReturnsInitializeSlot: Boolean,
    val noDifferentSlotOrSuspendBoundaryWidening: Boolean,
    val normalSuccessEdgeOnly: Boolean,
)

internal fun ArcCoroutineResultSlotForwardingEligibility.isAuthorized(): Boolean =
    arcEnabled && optimizationsEnabled && debugInfoDisabled && exactCallIdentitySelected &&
            explicitResultSlot && exactResultSlotIdentity && directKotlinCall && nonExternalCall &&
            nonVirtualCall && ownedResultConvention && referenceResult && nonUnitResult &&
            nonNothingResult && allNormalReturnsInitializeSlot &&
            noDifferentSlotOrSuspendBoundaryWidening && normalSuccessEdgeOnly

/**
 * A deliberately redundant checklist for each mutable read that ARC codegen may borrow.
 * Keeping this as a value object makes widening the authorization boundary an explicit change.
 */
internal data class ArcBorrowedMutableReadEligibility(
    val arcEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val nonSuspendFunction: Boolean,
    val directKotlinCall: Boolean,
    val mutableLocalReference: Boolean,
    val notCaptured: Boolean,
    val strongStorage: Boolean,
    val sideEffectFreeArgumentWrapper: Boolean,
    val ownerNotAssignedInSuffix: Boolean,
    val callFreeSuffix: Boolean,
    val nonSuspendingSuffix: Boolean,
    val nonThrowingSuffix: Boolean,
    val linearControlFlowSuffix: Boolean,
)

internal fun ArcBorrowedMutableReadEligibility.isAuthorized(): Boolean =
    arcEnabled && debugInfoDisabled && nonSuspendFunction && directKotlinCall &&
            mutableLocalReference && notCaptured && strongStorage && sideEffectFreeArgumentWrapper &&
            ownerNotAssignedInSuffix && callFreeSuffix && nonSuspendingSuffix && nonThrowingSuffix &&
            linearControlFlowSuffix

/** Authorization for borrowing a mutable local only while computing one direct field address/load. */
internal data class ArcBorrowedFieldReceiverEligibility(
    val arcEnabled: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val nonSuspendFunction: Boolean,
    val exactDirectInstanceFieldReceiver: Boolean,
    val nonVolatileField: Boolean,
    val strongFieldStorage: Boolean,
    val mutableLocalReferenceOwner: Boolean,
    val ownerNotCaptured: Boolean,
    val strongOwnerStorage: Boolean,
    val immediateAddressAndLoadOnly: Boolean,
    val ownerLivesThroughLoad: Boolean,
)

internal fun ArcBorrowedFieldReceiverEligibility.isAuthorized(): Boolean =
    arcEnabled && optimizationsEnabled && debugInfoDisabled && nonSuspendFunction &&
            exactDirectInstanceFieldReceiver && nonVolatileField && strongFieldStorage &&
            mutableLocalReferenceOwner && ownerNotCaptured && strongOwnerStorage &&
            immediateAddressAndLoadOnly && ownerLivesThroughLoad

/** Authorization for borrowing a strong CharArray field through one exact safe library call. */
internal data class ArcBorrowedStrongCallFieldEligibility(
    val arcEnabled: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val nonSuspendFunction: Boolean,
    val exactDirectInstanceFieldArgument: Boolean,
    val referenceField: Boolean,
    val nonVolatileField: Boolean,
    val strongFieldStorage: Boolean,
    val ownerIsCurrentDispatchReceiver: Boolean,
    val ownerReferenceIsGuaranteedForCall: Boolean,
    val exactAllowlistedCharArrayConsumer: Boolean,
    val ownerNotAssignedInSuffix: Boolean,
    val callFreeSuffix: Boolean,
    val nonSuspendingSuffix: Boolean,
    val nonThrowingSuffix: Boolean,
    val linearControlFlowSuffix: Boolean,
    val ownershipEffectFreeSuffix: Boolean,
    val verifierProofAccepted: Boolean,
)

internal fun ArcBorrowedStrongCallFieldEligibility.isAuthorized(): Boolean =
    arcEnabled && optimizationsEnabled && debugInfoDisabled && nonSuspendFunction &&
            exactDirectInstanceFieldArgument && referenceField && nonVolatileField && strongFieldStorage &&
            ownerIsCurrentDispatchReceiver && ownerReferenceIsGuaranteedForCall &&
            exactAllowlistedCharArrayConsumer && ownerNotAssignedInSuffix && callFreeSuffix &&
            nonSuspendingSuffix && nonThrowingSuffix && linearControlFlowSuffix &&
            ownershipEffectFreeSuffix && verifierProofAccepted

/**
 * Authorization for the exact loop-carried projection `owner = owner.field` (including the
 * canonical lowering of `owner.field!!`). The owning mutable stack slot remains live until the
 * final strong store has retained the projected value, so neither intermediate load needs to own.
 */
internal data class ArcBorrowedStrongFieldProjectionEligibility(
    val arcEnabled: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val nonSuspendFunction: Boolean,
    val exactDirectInstanceFieldRead: Boolean,
    val nonVolatileField: Boolean,
    val referenceField: Boolean,
    val strongFieldStorage: Boolean,
    val mutableLocalReferenceOwner: Boolean,
    val ownerNotCaptured: Boolean,
    val strongOwnerStorage: Boolean,
    val exactReceiverRead: Boolean,
    val canonicalSelfReplacement: Boolean,
    val ownerUnchangedUntilFinalStore: Boolean,
    val noUnmodeledCallOrSuspension: Boolean,
    val noTryReturnWriteOrEscape: Boolean,
    val exactMutableStrongReplacementStore: Boolean,
)

internal fun ArcBorrowedStrongFieldProjectionEligibility.isAuthorized(): Boolean =
    arcEnabled && optimizationsEnabled && debugInfoDisabled && nonSuspendFunction &&
            exactDirectInstanceFieldRead && nonVolatileField && referenceField && strongFieldStorage &&
            mutableLocalReferenceOwner && ownerNotCaptured && strongOwnerStorage && exactReceiverRead &&
            canonicalSelfReplacement && ownerUnchangedUntilFinalStore && noUnmodeledCallOrSuspension &&
            noTryReturnWriteOrEscape && exactMutableStrongReplacementStore

/** Fail-closed authorization boundary for a non-owning cursor anchored by a live strong graph. */
internal data class ArcRootedProjectionLoopEligibility(
    val arcEnabled: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val nonSuspendFunction: Boolean,
    val mutableStrongLocalCursor: Boolean,
    val cursorNotCaptured: Boolean,
    val exactlyOneProjectionLoop: Boolean,
    val directLiveAnchorInitializer: Boolean,
    val anchorDominatesAndEnclosesLoop: Boolean,
    val everyAssignmentCanonicalSelfProjection: Boolean,
    val everyReadImmediateDirectFieldReceiver: Boolean,
    val noUsesAfterRegion: Boolean,
    val noUnknownOrUserCall: Boolean,
    val noTryFinally: Boolean,
    val noSuspension: Boolean,
    val noNestedFunctionOrCallback: Boolean,
    val noAllocation: Boolean,
    val noFieldWrite: Boolean,
    val noOtherReferenceOwnershipEffects: Boolean,
    val noAlternateAssignment: Boolean,
    val noReturnBreakOrContinue: Boolean,
    val strongNonVolatileTransitionField: Boolean,
    val transitionFieldNotWritten: Boolean,
    val verifierProofAccepted: Boolean,
)

internal fun ArcRootedProjectionLoopEligibility.isAuthorized(): Boolean =
    arcEnabled && optimizationsEnabled && debugInfoDisabled && nonSuspendFunction &&
            mutableStrongLocalCursor && cursorNotCaptured && exactlyOneProjectionLoop &&
            directLiveAnchorInitializer && anchorDominatesAndEnclosesLoop &&
            everyAssignmentCanonicalSelfProjection && everyReadImmediateDirectFieldReceiver &&
            noUsesAfterRegion && noUnknownOrUserCall && noTryFinally && noSuspension &&
            noNestedFunctionOrCallback && noAllocation && noFieldWrite && noAlternateAssignment &&
            noOtherReferenceOwnershipEffects && noReturnBreakOrContinue && strongNonVolatileTransitionField &&
            transitionFieldNotWritten && verifierProofAccepted

internal fun ArcRootedProjectionLoopEligibility.rejectedRequirements(): List<String> = listOf(
    "arcEnabled" to arcEnabled,
    "optimizationsEnabled" to optimizationsEnabled,
    "debugInfoDisabled" to debugInfoDisabled,
    "nonSuspendFunction" to nonSuspendFunction,
    "mutableStrongLocalCursor" to mutableStrongLocalCursor,
    "cursorNotCaptured" to cursorNotCaptured,
    "exactlyOneProjectionLoop" to exactlyOneProjectionLoop,
    "directLiveAnchorInitializer" to directLiveAnchorInitializer,
    "anchorDominatesAndEnclosesLoop" to anchorDominatesAndEnclosesLoop,
    "everyAssignmentCanonicalSelfProjection" to everyAssignmentCanonicalSelfProjection,
    "everyReadImmediateDirectFieldReceiver" to everyReadImmediateDirectFieldReceiver,
    "noUsesAfterRegion" to noUsesAfterRegion,
    "noUnknownOrUserCall" to noUnknownOrUserCall,
    "noTryFinally" to noTryFinally,
    "noSuspension" to noSuspension,
    "noNestedFunctionOrCallback" to noNestedFunctionOrCallback,
    "noAllocation" to noAllocation,
    "noFieldWrite" to noFieldWrite,
    "noOtherReferenceOwnershipEffects" to noOtherReferenceOwnershipEffects,
    "noAlternateAssignment" to noAlternateAssignment,
    "noReturnBreakOrContinue" to noReturnBreakOrContinue,
    "strongNonVolatileTransitionField" to strongNonVolatileTransitionField,
    "transitionFieldNotWritten" to transitionFieldNotWritten,
    "verifierProofAccepted" to verifierProofAccepted,
).filterNot { it.second }.map { it.first }

/**
 * Authorization for replacing the owning Array.get ABI with the ARC-only borrowed projection ABI.
 * Every item is intentionally explicit: widening one part of the projection lifetime must not
 * silently weaken the owner, use-site, or exceptional-edge proof.
 */
internal data class ArcBorrowedArrayElementEligibility(
    val arcEnabled: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val nonSuspendFunction: Boolean,
    val exactReferenceArrayGet: Boolean,
    val immediateKotlinConsumer: Boolean,
    val referenceConsumerParameter: Boolean,
    val strongLocalValOwner: Boolean,
    val exactFreshStackArrayAllocation: Boolean,
    val ownerNotCaptured: Boolean,
    val ownerNeverAssigned: Boolean,
    val ownerNeverAliased: Boolean,
    val ownerNeverReturned: Boolean,
    val ownerNeverEscaped: Boolean,
    val arrayNeverMutated: Boolean,
    val onlyVerifiedOwnerReads: Boolean,
    val suffixDoesNotObserveOwner: Boolean,
    val callFreeSuffix: Boolean,
    val nonSuspendingSuffix: Boolean,
    val nonThrowingSuffix: Boolean,
    val linearControlFlowSuffix: Boolean,
    val ownerLivesThroughNormalAndUnwindEdges: Boolean,
)

internal fun ArcBorrowedArrayElementEligibility.isAuthorized(): Boolean =
    arcEnabled && optimizationsEnabled && debugInfoDisabled && nonSuspendFunction &&
            exactReferenceArrayGet && immediateKotlinConsumer && referenceConsumerParameter &&
            strongLocalValOwner && exactFreshStackArrayAllocation && ownerNotCaptured &&
            ownerNeverAssigned && ownerNeverAliased && ownerNeverReturned && ownerNeverEscaped &&
            arrayNeverMutated && onlyVerifiedOwnerReads && suffixDoesNotObserveOwner &&
            callFreeSuffix && nonSuspendingSuffix && nonThrowingSuffix && linearControlFlowSuffix &&
            ownerLivesThroughNormalAndUnwindEdges

/** A deliberately exact authorization boundary for representing an unmodified `var` as a borrowed SSA value. */
internal data class ArcBorrowedGuaranteedAliasEligibility(
    val arcEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val nonSuspendFunction: Boolean,
    val mutableLocalReference: Boolean,
    val initializedFromGuaranteedParameter: Boolean,
    val strongStorage: Boolean,
    val exactlyOneUse: Boolean,
    val neverAssigned: Boolean,
    val notCaptured: Boolean,
    val notReturned: Boolean,
    val finalExplicitReferenceArgument: Boolean,
    val directKotlinCall: Boolean,
)

internal fun ArcBorrowedGuaranteedAliasEligibility.isAuthorized(): Boolean =
    arcEnabled && debugInfoDisabled && nonSuspendFunction && mutableLocalReference &&
            initializedFromGuaranteedParameter && strongStorage && exactlyOneUse && neverAssigned &&
            notCaptured && notReturned && finalExplicitReferenceArgument && directKotlinCall

internal data class ArcOwnershipClassificationCounts(
    val owned: Int = 0,
    val guaranteed: Int = 0,
    val immortal: Int = 0,
) {
    operator fun plus(other: ArcOwnershipClassificationCounts) = ArcOwnershipClassificationCounts(
        owned = owned + other.owned,
        guaranteed = guaranteed + other.guaranteed,
        immortal = immortal + other.immortal,
    )
}

internal fun classifyArcProducedReference(
    isPermanent: Boolean,
    lifetime: Lifetime?,
    requiresHeapAllocation: Boolean = false,
): ArcOwnership = when {
    isPermanent -> ArcOwnership.Immortal
    !requiresHeapAllocation && (lifetime === Lifetime.STACK || lifetime === Lifetime.LOCAL) -> ArcOwnership.Guaranteed
    else -> ArcOwnership.Owned
}

/**
 * ARC-only first-slice ownership planner.
 *
 * It deliberately accepts only non-suspend functions made from reference vals, direct
 * reference-producing calls, strong field loads/stores, and one narrowly shaped Unit `if`
 * whose two arms contain compatible strong stores. Unsupported functions are skipped without
 * changing IR. Every accepted plan is path-verified immediately.
 */
internal fun runArcOwnershipPlanning(
    generationState: NativeGenerationState,
    input: ArcOwnershipPlanningInput,
): ArcOwnershipPlanningReport {
    if (generationState.context.memoryModel != MemoryModel.ARC) return ArcOwnershipPlanningReport.Disabled

    val plans = mutableListOf<ArcFunctionPlan>()
    var classifications = ArcOwnershipClassificationCounts()
    var optimization = ArcOwnershipOptimizationMetrics()
    val ownedResultForwarding = linkedMapOf<IrSimpleFunction, ArcOwnedResultForwarding>()
    val coroutineResultSlotForwardingCalls = Collections.newSetFromMap(IdentityHashMap<IrCall, Boolean>())
    val lockedReadResultSlotForwardingCalls = Collections.synchronizedMap(
        IdentityHashMap<IrCall, ArcLockedReadCanonicalPlan>()
    )
    val returnedReceiverBorrowCalls = Collections.newSetFromMap(IdentityHashMap<IrCall, Boolean>())
    val discardedReturnedReceiverGroupsByCall = Collections.synchronizedMap(
        IdentityHashMap<IrCall, ArcDiscardedReturnedReceiverGroup>()
    )
    val coroutineSpillMovesByVariable = linkedMapOf<IrVariable, ArcCoroutineSpillMovePlan>()
    val coroutineSpillMovesByReturn = Collections.synchronizedMap(IdentityHashMap<IrReturn, ArcCoroutineSpillMovePlan>())
    val safeContinuationOwnedResultVariables = Collections.newSetFromMap(IdentityHashMap<IrVariable, Boolean>())
    val safeContinuationOwnedResultAssignments = Collections.newSetFromMap(IdentityHashMap<IrSetValue, Boolean>())
    val safeContinuationMovedResultReads = Collections.newSetFromMap(IdentityHashMap<IrGetValue, Boolean>())
    val joinedReferenceSlots = linkedMapOf<IrVariable, ArcJoinedReferenceSlotPlan>()
    val borrowedGuaranteedAliases = linkedSetOf<IrVariable>()
    val borrowedMutableReads = linkedSetOf<IrGetValue>()
    val borrowedFieldReceivers = Collections.newSetFromMap(IdentityHashMap<IrGetValue, Boolean>())
    val borrowedStrongFieldLoads = Collections.newSetFromMap(IdentityHashMap<IrGetField, Boolean>())
    val borrowedStrongCallFieldLoads = Collections.newSetFromMap(IdentityHashMap<IrGetField, Boolean>())
    val borrowedStrongProjectionStores = Collections.newSetFromMap(IdentityHashMap<IrSetValue, Boolean>())
    val rootedProjectionVariables = Collections.newSetFromMap(IdentityHashMap<IrVariable, Boolean>())
    val rootedProjectionReads = Collections.newSetFromMap(IdentityHashMap<IrGetValue, Boolean>())
    val rootedProjectionFieldLoads = Collections.newSetFromMap(IdentityHashMap<IrGetField, Boolean>())
    val rootedProjectionStores = Collections.newSetFromMap(IdentityHashMap<IrSetValue, Boolean>())
    val borrowedArrayElementCalls = linkedSetOf<IrCall>()
    val mutableConstructorInitializers = linkedSetOf<IrVariable>()
    val scopedArcReferenceLoads = linkedMapOf<IrCall, IrExpression>()
    val rootedGlobalProjections = selectVerifiedRootedGlobalProjections(generationState, input.module)
    // ArcReferencesLowering removes the source annotation and may remove the property/accessor
    // association before this planner runs. Discover exact rewritten function symbols first so
    // call-site selection neither depends on declaration order nor guesses from lowered names.
    val discoveredArcReferenceLoadAccessors = collectArcReferenceLoadAccessors(generationState, input.module)
    val arcReferenceLoadAccessorSignatures = generationState.context.mapping.arcReferenceLoadAccessorSignatures +
            discoveredArcReferenceLoadAccessors.mapNotNullTo(linkedSetOf()) { it.signature ?: it.privateSignature }
    val localArcReferenceLoadAccessorDeclarations =
            generationState.context.mapping.localArcReferenceLoadAccessorDeclarations +
                    discoveredArcReferenceLoadAccessors.filter { it.signature == null && it.privateSignature == null }
                        .mapTo(linkedSetOf()) {
                            it.owner.attributeOwnerId as? IrSimpleFunction ?: it.owner
                        }
    val borrowedCharArrayConsumerSymbols = resolveBorrowedCharArrayConsumerSymbols(generationState)
    val canonicalLockedReadPlans = resolveCanonicalLockedReadPlans(generationState, input.module)
    canonicalLockedReadPlans.forEach { plan ->
        lockedReadResultSlotForwardingCalls[plan.tailCall] = plan
    }
    selectCanonicalSafeContinuationGetOrThrow(generationState, input.module, canonicalLockedReadPlans)?.let { plan ->
        safeContinuationOwnedResultVariables += plan.resultVariable
        safeContinuationOwnedResultAssignments += plan.resultAssignments
        safeContinuationMovedResultReads += plan.movedResultRead
        borrowedMutableReads += plan.borrowedResultReads.filterNot { it === plan.movedResultRead }
        borrowedStrongFieldLoads += plan.borrowedResultRefLoads
    }
    // The historical curated-plan visitor below intentionally analyzes top-level functions only.
    // This selector targets an exact class-member pattern, so give it an independent recursive
    // walk instead of silently broadening every existing ownership-plan family to class members.
    input.module.files.forEach { file ->
        file.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                returnedReceiverBorrowCalls += selectVerifiedReturnedReceiverBorrowCalls(generationState, declaration)
                selectVerifiedDiscardedReturnedReceiverGroups(generationState, declaration).forEach { group ->
                    group.calls.forEach { call ->
                        discardedReturnedReceiverGroupsByCall[call] = group
                    }
                }
                coroutineResultSlotForwardingCalls +=
                    selectVerifiedCoroutineResultSlotForwardingCalls(generationState, declaration)
                borrowedCharArrayConsumerSymbols?.let { exactConsumers ->
                    borrowedStrongCallFieldLoads += selectVerifiedBorrowedStrongCallFieldLoads(
                        generationState, declaration, exactConsumers
                    )
                }
                declaration.acceptChildrenVoid(this)
            }
        })
    }
    var skipped = 0
    input.module.files.forEach { file ->
        file.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                selectVerifiedCoroutineSpillMove(generationState, declaration, input.lifetimes)?.let { plan ->
                    coroutineSpillMovesByVariable[plan.spillVariable] = plan
                    coroutineSpillMovesByReturn[plan.returnExpression] = plan
                }
                joinedReferenceSlots += selectVerifiedJoinedReferenceSlots(
                    generationState,
                    declaration,
                    input.lifetimes,
                )
                val guaranteedAliases = selectVerifiedBorrowedGuaranteedAliases(generationState, declaration)
                borrowedGuaranteedAliases += guaranteedAliases
                borrowedMutableReads += selectVerifiedBorrowedMutableReads(
                    generationState,
                    declaration,
                    guaranteedAliases,
                )
                borrowedFieldReceivers += selectVerifiedBorrowedFieldReceivers(generationState, declaration)
                selectVerifiedBorrowedStrongFieldProjections(generationState, declaration).let { selection ->
                    borrowedStrongFieldLoads += selection.fieldLoads
                    borrowedStrongProjectionStores += selection.replacementStores
                }
                selectVerifiedRootedProjectionLoops(generationState, declaration, input.lifetimes).let { selection ->
                    rootedProjectionVariables += selection.variables
                    rootedProjectionReads += selection.reads
                    rootedProjectionFieldLoads += selection.fieldLoads
                    rootedProjectionStores += selection.stores
                }
                borrowedArrayElementCalls += selectVerifiedBorrowedArrayElementCalls(
                    generationState,
                    declaration,
                    input.lifetimes,
                )
                mutableConstructorInitializers += selectVerifiedMutableConstructorInitializers(
                    generationState,
                    declaration,
                    input.lifetimes,
                )
                scopedArcReferenceLoads += selectScopedArcReferenceLoads(
                    generationState,
                    declaration,
                    arcReferenceLoadAccessorSignatures,
                    localArcReferenceLoadAccessorDeclarations,
                )
                val builtPlan = CuratedArcOwnershipPlanBuilder(declaration, input.lifetimes).build()
                if (builtPlan == null) {
                    skipped++
                } else {
                    when (val result = ArcOwnershipVerifier.verify(builtPlan.plan)) {
                        ArcOwnershipVerificationResult.Success -> {
                            val optimized = ArcOwnershipOptimizer.optimizeVerified(builtPlan.plan)
                            plans += optimized.plan
                            classifications += optimized.plan.classificationCounts()
                            optimization += optimized.metrics
                            val forwarding = selectOwnedResultForwarding(declaration, builtPlan, optimized)
                            generationState.context.log {
                                "ARC ownership plan ${optimized.plan.functionName}: " +
                                        "blocks=${optimized.plan.blocks.size}, " +
                                        "changed=${optimized.metrics.plansChanged != 0}, " +
                                        "containedOwnedCopiesEliminated=${optimized.metrics.containedOwnedCopiesEliminated}, " +
                                        "ownedResultForwarding=${forwarding != null}, " +
                                        "guaranteedEntryCopiesEliminated=${optimized.metrics.guaranteedEntryCopiesEliminated}"
                            }
                            forwarding?.let {
                                ownedResultForwarding[declaration] = it
                            }
                        }
                        is ArcOwnershipVerificationResult.Failure -> reportFailure(generationState, file, declaration, result)
                    }
                }
                declaration.acceptChildrenVoid(this)
            }
        })
    }
    return ArcOwnershipPlanningReport(
        plans.size,
        skipped,
        plans,
        classifications,
        optimization,
        ArcCodegenOwnershipPlan(
            ownedResultForwarding,
            coroutineResultSlotForwardingCalls,
            lockedReadResultSlotForwardingCalls,
            returnedReceiverBorrowCalls,
            discardedReturnedReceiverGroupsByCall,
            coroutineSpillMovesByVariable,
            coroutineSpillMovesByReturn,
            safeContinuationOwnedResultVariables,
            safeContinuationOwnedResultAssignments,
            safeContinuationMovedResultReads,
            joinedReferenceSlots,
            borrowedGuaranteedAliases,
            borrowedMutableReads,
            borrowedFieldReceivers,
            borrowedStrongFieldLoads,
            borrowedStrongCallFieldLoads,
            borrowedStrongProjectionStores,
            rootedProjectionVariables,
            rootedProjectionReads,
            rootedProjectionFieldLoads,
            rootedProjectionStores,
            borrowedArrayElementCalls,
            mutableConstructorInitializers,
            scopedArcReferenceLoads,
            rootedGlobalProjections,
        ),
    )
}

private fun collectArcReferenceLoadAccessors(
    generationState: NativeGenerationState,
    module: org.jetbrains.kotlin.ir.declarations.IrModuleFragment,
): Set<IrSimpleFunctionSymbol> {
    val weakLoad = generationState.context.ir.symbols.arcWeakReferenceLoad
    val unownedLoad = generationState.context.ir.symbols.arcUnownedReferenceLoad
    val accessors = linkedSetOf<IrSimpleFunctionSymbol>()
    module.files.forEach { file ->
        file.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                if (declaration.returnType.binaryTypeIsReference()) {
                    var containsArcReferenceLoad = false
                    declaration.body?.acceptVoid(object : IrElementVisitorVoid {
                        override fun visitElement(element: IrElement) {
                            element.acceptChildrenVoid(this)
                        }

                        override fun visitFunction(declaration: IrFunction) = Unit

                        override fun visitCall(expression: IrCall) {
                            if (expression.symbol == weakLoad || expression.symbol == unownedLoad) {
                                containsArcReferenceLoad = true
                            }
                            expression.acceptChildrenVoid(this)
                        }
                    })
                    if (containsArcReferenceLoad) accessors += declaration.symbol
                }
                declaration.acceptChildrenVoid(this)
            }
        })
    }
    return accessors
}

/**
 * Select ARC property promotions only inside non-escaping full-expression statements. Calls in
 * declarations, assignments, returns, field stores, and throws retain the normal durable root.
 */
private fun selectScopedArcReferenceLoads(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
    arcReferenceLoadAccessorSignatures: Set<IdSignature>,
    localArcReferenceLoadAccessorDeclarations: Set<IrSimpleFunction>,
): Map<IrCall, IrExpression> {
    if (generationState.context.shouldContainDebugInfo()) return emptyMap()
    val body = function.body as? org.jetbrains.kotlin.ir.expressions.IrBlockBody ?: return emptyMap()
    val selected = linkedMapOf<IrCall, IrExpression>()
    body.statements.filterIsInstance<IrExpression>().forEach { statement ->
        var safe = true
        val candidates = linkedMapOf<IrCall, IrExpression>()
        val ancestors = mutableListOf<IrElement>()
        statement.acceptVoid(object : IrElementVisitorVoid {
            private fun descend(element: IrElement) {
                ancestors += element
                element.acceptChildrenVoid(this)
                ancestors.removeAt(ancestors.lastIndex)
            }

            override fun visitElement(element: IrElement) {
                descend(element)
            }

            override fun visitFunction(declaration: IrFunction) = Unit

            override fun visitVariable(declaration: IrVariable) {
                // Top-level source declarations are excluded before this visitor. Any immutable
                // variable reached here is lexically contained in the accepted full expression,
                // including inliner-generated parameter temporaries.
                if (declaration.isVar) {
                    safe = false
                    return
                }
                descend(declaration)
            }

            override fun visitSetValue(expression: org.jetbrains.kotlin.ir.expressions.IrSetValue) {
                safe = false
            }

            override fun visitSetField(expression: IrSetField) {
                safe = false
            }

            override fun visitReturn(expression: IrReturn) {
                // Lowered Unit/primitive functions commonly wrap their whole body in an implicit
                // return. Traverse its value without treating the terminator as a lifetime
                // boundary. Only an actual reference return from this function escapes.
                if (expression.returnTargetSymbol == function.symbol && expression.value.type.binaryTypeIsReference()) {
                    safe = false
                } else {
                    descend(expression)
                }
            }

            override fun visitThrow(expression: org.jetbrains.kotlin.ir.expressions.IrThrow) {
                // A throw outside a try exits this frame, whose LeaveFrame consumes all roots.
                // Do not select promotions inside the thrown value, but keep normal-path sibling
                // candidates eligible for their earlier non-reference boundaries.
            }

            override fun visitLoop(loop: org.jetbrains.kotlin.ir.expressions.IrLoop) {
                safe = false
            }

            override fun visitSuspendableExpression(expression: org.jetbrains.kotlin.ir.expressions.IrSuspendableExpression) {
                safe = false
            }

            override fun visitSuspensionPoint(expression: org.jetbrains.kotlin.ir.expressions.IrSuspensionPoint) {
                safe = false
            }

            override fun visitTry(aTry: org.jetbrains.kotlin.ir.expressions.IrTry) {
                safe = false
            }

            override fun visitCall(expression: IrCall) {
                val property = expression.symbol.owner.correspondingPropertySymbol?.owner
                val accessorSignature = expression.symbol.signature ?: expression.symbol.privateSignature
                val canonicalAccessor = expression.symbol.owner.attributeOwnerId as? IrSimpleFunction ?: expression.symbol.owner
                if (accessorSignature in arcReferenceLoadAccessorSignatures ||
                    canonicalAccessor in localArcReferenceLoadAccessorDeclarations ||
                    property != null && (property.annotations.hasAnnotation(KonanFqNames.arcWeak) ||
                            property.annotations.hasAnnotation(KonanFqNames.arcUnowned)) ||
                    expression.symbol == generationState.context.ir.symbols.arcWeakReferenceLoad ||
                    expression.symbol == generationState.context.ir.symbols.arcUnownedReferenceLoad) {
                    // Walk through reference-producing aliases/containers. A primitive or Unit
                    // consumer cannot return the promoted reference raw. If none exists, the
                    // verified top-level statement result is ignored, so its own normal exit is
                    // the lifetime boundary.
                    val boundary = ancestors.asReversed().filterIsInstance<IrExpression>()
                        .firstOrNull {
                            it !is IrReturn && it !is org.jetbrains.kotlin.ir.expressions.IrThrow &&
                                    !it.type.binaryTypeIsReference()
                        }
                        ?: statement
                    candidates[expression] = boundary
                }
                descend(expression)
            }
        })
        if (safe && verifyScopedArcReferencePromotionProof(function)) selected += candidates
    }
    return selected
}

private fun verifyScopedArcReferencePromotionProof(function: IrSimpleFunction): Boolean {
    val promotion = ArcValue("scoped_promotion")
    val entry = ArcBlockId("entry")
    val proof = ArcFunctionPlan(
        functionName = "${function.fqNameForIrSerialization.asString()}#scoped-arc-reference",
        entry = entry,
        entryValues = emptyMap(),
        entryInitializedStorage = emptySet(),
        blocks = mapOf(
            entry to ArcBasicBlock(
                entry,
                listOf(
                    ArcOperation.Define(promotion, ArcOwnership.Owned),
                    ArcOperation.Use(promotion),
                    ArcOperation.Destroy(promotion),
                ),
                ArcTerminator.Return(),
            )
        ),
    )
    return ArcOwnershipVerifier.verify(proof) === ArcOwnershipVerificationResult.Success
}

/**
 * Forward only a direct constructor allocation into its mutable owner's stack slot. This removes
 * the otherwise anonymous allocation root without changing later mutable-load behavior.
 */
private fun selectVerifiedMutableConstructorInitializers(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
    lifetimes: Map<IrElement, Lifetime>,
): Set<IrVariable> {
    if (generationState.context.shouldContainDebugInfo()) return emptySet()
    val body = function.body ?: return emptySet()
    val selected = linkedSetOf<IrVariable>()
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) = Unit

        override fun visitVariable(declaration: IrVariable) {
            val constructor = declaration.initializer?.unwrapDirectConstructor()
            val constructorLifetime = constructor?.let { lifetimes[it] }
            val eligible = declaration.isVar && declaration.type.binaryTypeIsReference() &&
                    declaration.parent === function && constructor?.isDirectKotlinCall(generationState) == true &&
                    constructorLifetime !== Lifetime.STACK && constructorLifetime !== Lifetime.LOCAL &&
                    !declaration.hasAnnotation(KonanFqNames.arcWeak) &&
                    !declaration.hasAnnotation(KonanFqNames.arcUnowned) &&
                    !declaration.hasAnnotation(KonanFqNames.volatile)
            if (eligible && verifyMutableConstructorInitializerProof(function, declaration)) {
                selected += declaration
            }
            declaration.acceptChildrenVoid(this)
        }
    })
    return selected
}

private fun IrExpression.unwrapDirectConstructor(): IrConstructorCall? = when (this) {
    is IrConstructorCall -> this
    is IrTypeOperatorCall -> if (operator == IrTypeOperator.IMPLICIT_CAST) argument.unwrapDirectConstructor() else null
    else -> null
}

private fun verifyMutableConstructorInitializerProof(function: IrSimpleFunction, variable: IrVariable): Boolean {
    val allocation = ArcValue("allocation_${variable.name}")
    val slot = ArcStorage("local_${variable.name}")
    val entry = ArcBlockId("entry")
    val proof = ArcFunctionPlan(
        functionName = "${function.fqNameForIrSerialization.asString()}#init-${variable.name}",
        entry = entry,
        entryValues = emptyMap(),
        entryInitializedStorage = emptySet(),
        blocks = mapOf(
            entry to ArcBasicBlock(
                entry,
                listOf(
                    ArcOperation.Define(allocation, ArcOwnership.Owned),
                    ArcOperation.StrongStore(slot, allocation),
                    ArcOperation.Destroy(allocation),
                ),
                ArcTerminator.Return(),
            )
        ),
    )
    return ArcOwnershipVerifier.verify(proof) === ArcOwnershipVerificationResult.Success
}

private data class ArcGuaranteedAliasUseAnalysis(
    val useCount: Int,
    val assigned: Boolean,
    val captured: Boolean,
    val returned: Boolean,
    val finalExplicitReferenceArgument: Boolean,
    val directKotlinCall: Boolean,
)

/**
 * Select an unmodified local `var` whose sole value is a guaranteed parameter and whose only read
 * is already passed +0 to a direct Kotlin call. Codegen may represent this exact variable as an
 * immutable SSA value: the parameter frame owns the object throughout the complete use interval.
 */
private fun selectVerifiedBorrowedGuaranteedAliases(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
): Set<IrVariable> {
    val nonSuspendFunction = !function.isArcSuspendLike()
    if (generationState.context.memoryModel != MemoryModel.ARC ||
        generationState.context.shouldContainDebugInfo() || !nonSuspendFunction
    ) return emptySet()
    val body = function.body ?: return emptySet()
    val guaranteedParameters = function.allParameters
        .filter { it.type.binaryTypeIsReference() }
        .mapTo(linkedSetOf()) { it.symbol }
    if (guaranteedParameters.isEmpty()) return emptySet()

    val selected = linkedSetOf<IrVariable>()
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) = Unit

        override fun visitVariable(declaration: IrVariable) {
            val initializer = declaration.initializer as? IrGetValue
            val analysis = analyzeGuaranteedAliasUses(generationState, function, declaration)
            val strongStorage = !declaration.hasAnnotation(KonanFqNames.arcWeak) &&
                    !declaration.hasAnnotation(KonanFqNames.arcUnowned) &&
                    !declaration.hasAnnotation(KonanFqNames.volatile)
            val eligibility = ArcBorrowedGuaranteedAliasEligibility(
                arcEnabled = generationState.context.memoryModel == MemoryModel.ARC,
                debugInfoDisabled = !generationState.context.shouldContainDebugInfo(),
                nonSuspendFunction = nonSuspendFunction,
                mutableLocalReference = declaration.isVar && declaration.type.binaryTypeIsReference() &&
                        declaration.parent === function,
                initializedFromGuaranteedParameter = initializer?.symbol in guaranteedParameters,
                strongStorage = strongStorage,
                exactlyOneUse = analysis.useCount == 1,
                neverAssigned = !analysis.assigned,
                notCaptured = !analysis.captured,
                notReturned = !analysis.returned,
                finalExplicitReferenceArgument = analysis.finalExplicitReferenceArgument,
                directKotlinCall = analysis.directKotlinCall,
            )
            if (eligibility.isAuthorized() && verifyGuaranteedAliasEliminationProof(function, declaration)) {
                selected += declaration
            }
            declaration.acceptChildrenVoid(this)
        }
    })
    return selected
}

private fun analyzeGuaranteedAliasUses(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
    variable: IrVariable,
): ArcGuaranteedAliasUseAnalysis {
    val uses = mutableListOf<IrGetValue>()
    var assigned = false
    var captured = false
    var returned = false
    var nestedFunctionDepth = 0
    var returnDepth = 0
    var qualifyingUse: IrGetValue? = null
    var directKotlinCall = false

    function.body?.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) {
            nestedFunctionDepth++
            declaration.acceptChildrenVoid(this)
            nestedFunctionDepth--
        }

        override fun visitReturn(expression: IrReturn) {
            returnDepth++
            expression.acceptChildrenVoid(this)
            returnDepth--
        }

        override fun visitGetValue(expression: IrGetValue) {
            if (expression.symbol == variable.symbol) {
                uses += expression
                if (nestedFunctionDepth != 0) captured = true
                if (returnDepth != 0) returned = true
            }
        }

        override fun visitSetValue(expression: org.jetbrains.kotlin.ir.expressions.IrSetValue) {
            if (expression.symbol == variable.symbol) assigned = true
            expression.acceptChildrenVoid(this)
        }

        override fun visitFunctionAccess(expression: IrFunctionAccessExpression) {
            if (nestedFunctionDepth == 0) {
                val (parameter, argument) = expression.getArgumentsWithIr().lastOrNull() ?: run {
                    expression.acceptChildrenVoid(this)
                    return
                }
                val read = argument as? IrGetValue
                if (read?.symbol == variable.symbol && parameter.type.binaryTypeIsReference()) {
                    qualifyingUse = read
                    directKotlinCall = expression.isDirectKotlinCall(generationState)
                }
            }
            expression.acceptChildrenVoid(this)
        }
    })

    return ArcGuaranteedAliasUseAnalysis(
        useCount = uses.size,
        assigned = assigned,
        captured = captured,
        returned = returned,
        finalExplicitReferenceArgument = qualifyingUse != null && qualifyingUse === uses.singleOrNull(),
        directKotlinCall = directKotlinCall,
    )
}

private fun verifyGuaranteedAliasEliminationProof(function: IrSimpleFunction, variable: IrVariable): Boolean {
    val parameter = ArcValue("parameter_${variable.name}")
    val alias = ArcValue("alias_${variable.name}")
    val entry = ArcBlockId("entry")
    val proof = ArcFunctionPlan(
        functionName = "${function.fqNameForIrSerialization.asString()}#guaranteed-alias-${variable.name}",
        entry = entry,
        entryValues = mapOf(parameter to ArcOwnership.Guaranteed),
        entryInitializedStorage = emptySet(),
        blocks = mapOf(
            entry to ArcBasicBlock(
                entry,
                listOf(
                    ArcOperation.Copy(parameter, alias),
                    ArcOperation.Use(alias, ArcPlanLocation("final direct-call argument")),
                    ArcOperation.Destroy(alias),
                ),
                ArcTerminator.Return(),
            )
        ),
    )
    val optimized = ArcOwnershipOptimizer.optimizeVerified(proof)
    return optimized.metrics.guaranteedEntryCopiesEliminated == 1 &&
            optimized.plan.blocks.getValue(entry).operations ==
            listOf(ArcOperation.Use(parameter, ArcPlanLocation("final direct-call argument"))) &&
            ArcOwnershipVerifier.verify(optimized.plan) === ArcOwnershipVerificationResult.Success
}

private data class ArcArgumentSuffixAnalysis(
    var ownerAssigned: Boolean = false,
    var ownerObserved: Boolean = false,
    var containsCall: Boolean = false,
    var containsSuspension: Boolean = false,
    var containsThrow: Boolean = false,
    var containsControlFlow: Boolean = false,
    var containsReferenceOwnershipEffect: Boolean = false,
)

/**
 * Analyze only arguments evaluated after [variable]'s load. `getArgumentsWithIr` defines this
 * order for dispatch receivers, extension receivers, and value arguments. This first slice keeps
 * the interval deliberately linear and call-free; later CFG-aware ownership planning can widen it
 * without weakening this authorization boundary.
 */
private fun analyzeBorrowedArgumentSuffix(
    suffix: List<IrExpression>,
    variable: IrValueDeclaration,
): ArcArgumentSuffixAnalysis = ArcArgumentSuffixAnalysis().also { analysis ->
    suffix.forEach { expression ->
        expression.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) {
                // Creating or traversing a nested declaration is not a linear value-only suffix.
                analysis.containsControlFlow = true
            }

            override fun visitSetValue(expression: org.jetbrains.kotlin.ir.expressions.IrSetValue) {
                if (expression.symbol == variable.symbol) analysis.ownerAssigned = true
                if (expression.symbol.owner.type.binaryTypeIsReference()) {
                    analysis.containsReferenceOwnershipEffect = true
                }
                expression.acceptChildrenVoid(this)
            }

            override fun visitSetField(expression: IrSetField) {
                // Even a store to an unrelated field may release its previous value and run an
                // arbitrary ARC deinitializer. Keep this deliberately fail-closed until effects
                // are represented in the ownership plan.
                analysis.containsReferenceOwnershipEffect = true
                expression.acceptChildrenVoid(this)
            }

            override fun visitVariable(declaration: IrVariable) {
                if (declaration.type.binaryTypeIsReference()) {
                    analysis.containsReferenceOwnershipEffect = true
                }
                declaration.acceptChildrenVoid(this)
            }

            override fun visitGetValue(expression: IrGetValue) {
                if (expression.symbol == variable.symbol) analysis.ownerObserved = true
            }

            override fun visitFunctionAccess(expression: IrFunctionAccessExpression) {
                analysis.containsCall = true
                expression.acceptChildrenVoid(this)
            }

            override fun visitSuspendableExpression(expression: org.jetbrains.kotlin.ir.expressions.IrSuspendableExpression) {
                analysis.containsSuspension = true
                expression.acceptChildrenVoid(this)
            }

            override fun visitSuspensionPoint(expression: org.jetbrains.kotlin.ir.expressions.IrSuspensionPoint) {
                analysis.containsSuspension = true
                expression.acceptChildrenVoid(this)
            }

            override fun visitThrow(expression: org.jetbrains.kotlin.ir.expressions.IrThrow) {
                analysis.containsThrow = true
                expression.acceptChildrenVoid(this)
            }

            override fun visitWhen(expression: IrWhen) {
                analysis.containsControlFlow = true
                expression.acceptChildrenVoid(this)
            }

            override fun visitLoop(loop: org.jetbrains.kotlin.ir.expressions.IrLoop) {
                analysis.containsControlFlow = true
                loop.acceptChildrenVoid(this)
            }

            override fun visitTry(aTry: org.jetbrains.kotlin.ir.expressions.IrTry) {
                analysis.containsControlFlow = true
                aTry.acceptChildrenVoid(this)
            }

            override fun visitReturn(expression: IrReturn) {
                analysis.containsControlFlow = true
                expression.acceptChildrenVoid(this)
            }

            override fun visitBreak(jump: org.jetbrains.kotlin.ir.expressions.IrBreak) {
                analysis.containsControlFlow = true
            }

            override fun visitContinue(jump: org.jetbrains.kotlin.ir.expressions.IrContinue) {
                analysis.containsControlFlow = true
            }
        })
    }
}

private fun collectVariablesCapturedByNestedFunctions(function: IrSimpleFunction): Set<IrVariable> {
    var nestedFunctionDepth = 0
    val captured = linkedSetOf<IrVariable>()
    function.body?.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) {
            nestedFunctionDepth++
            declaration.acceptChildrenVoid(this)
            nestedFunctionDepth--
        }

        override fun visitGetValue(expression: IrGetValue) {
            if (nestedFunctionDepth != 0) (expression.symbol.owner as? IrVariable)?.let { captured += it }
        }

        override fun visitSetValue(expression: org.jetbrains.kotlin.ir.expressions.IrSetValue) {
            if (nestedFunctionDepth != 0) (expression.symbol.owner as? IrVariable)?.let { captured += it }
            expression.acceptChildrenVoid(this)
        }
    })
    return captured
}

private data class ArcBorrowedArrayElementCandidate(
    val consumer: IrFunctionAccessExpression,
    val parameterReference: Boolean,
    val arrayGet: IrCall,
    val owner: IrVariable,
    val suffix: ArcArgumentSuffixAnalysis,
)

private data class ArcArrayOwnerUseAnalysis(
    var assigned: Boolean = false,
    var aliased: Boolean = false,
    var returned: Boolean = false,
    var escaped: Boolean = false,
    var mutated: Boolean = false,
    var onlyVerifiedReads: Boolean = true,
)

/**
 * Borrow an element only from one exact, nonescaping local Array allocation. The complete set of
 * reads of the owner is audited by identity: apart from the selected gets, only Array.size reads
 * used before the element projection are accepted. This deliberately rejects parameters, fields,
 * aliases, stored arrays, captures, and any shape whose lifetime depends on interprocedural facts.
 */
private fun selectVerifiedBorrowedArrayElementCalls(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
    lifetimes: Map<IrElement, Lifetime>,
): Set<IrCall> {
    val nonSuspendFunction = !function.isArcSuspendLike()
    if (generationState.context.memoryModel != MemoryModel.ARC ||
        !generationState.context.config.optimizationsEnabled ||
        generationState.context.shouldContainDebugInfo() || !nonSuspendFunction
    ) return emptySet()
    val body = function.body ?: return emptySet()
    val symbols = generationState.context.ir.symbols
    val exactArrayGet = symbols.arrayGet[symbols.array] ?: return emptySet()
    val exactArraySize = symbols.arraySize[symbols.array] ?: return emptySet()
    val exactArraySet = symbols.arraySet[symbols.array] ?: return emptySet()
    val capturedVariables = collectVariablesCapturedByNestedFunctions(function)
    val candidates = mutableListOf<ArcBorrowedArrayElementCandidate>()

    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) = Unit

        override fun visitFunctionAccess(expression: IrFunctionAccessExpression) {
            val arguments = expression.getArgumentsWithIr()
            arguments.forEachIndexed { index, (parameter, argument) ->
                val arrayGet = argument as? IrCall ?: return@forEachIndexed
                if (arrayGet.symbol != exactArrayGet || !arrayGet.type.binaryTypeIsReference()) return@forEachIndexed
                val ownerRead = arrayGet.dispatchReceiver as? IrGetValue ?: return@forEachIndexed
                val owner = ownerRead.symbol.owner as? IrVariable ?: return@forEachIndexed
                val suffix = analyzeBorrowedArgumentSuffix(arguments.drop(index + 1).map { it.second }, owner)
                candidates += ArcBorrowedArrayElementCandidate(
                    expression,
                    parameter.type.binaryTypeIsReference(),
                    arrayGet,
                    owner,
                    suffix,
                )
            }
            expression.acceptChildrenVoid(this)
        }
    })
    if (candidates.isEmpty()) return emptySet()

    val selected = linkedSetOf<IrCall>()
    candidates.groupBy { it.owner }.forEach { (owner, ownerCandidates) ->
        val allowedReads = Collections.newSetFromMap(IdentityHashMap<IrGetValue, Boolean>())
        ownerCandidates.mapNotNullTo(allowedReads) { it.arrayGet.dispatchReceiver as? IrGetValue }
        body.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) = Unit

            override fun visitCall(expression: IrCall) {
                if (expression.symbol == exactArraySize) {
                    val receiver = expression.dispatchReceiver as? IrGetValue
                    if (receiver?.symbol == owner.symbol) allowedReads += receiver
                }
                expression.acceptChildrenVoid(this)
            }
        })

        val uses = ArcArrayOwnerUseAnalysis()
        body.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) = Unit

            override fun visitVariable(declaration: IrVariable) {
                if (declaration !== owner && declaration.initializer?.unwrapDirectMutableRead()?.symbol == owner.symbol) {
                    uses.aliased = true
                }
                declaration.acceptChildrenVoid(this)
            }

            override fun visitReturn(expression: IrReturn) {
                if (expression.value.unwrapDirectMutableRead()?.symbol == owner.symbol) uses.returned = true
                expression.acceptChildrenVoid(this)
            }

            override fun visitSetValue(expression: org.jetbrains.kotlin.ir.expressions.IrSetValue) {
                if (expression.symbol == owner.symbol) uses.assigned = true
                expression.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                if (expression.symbol == exactArraySet &&
                    (expression.dispatchReceiver as? IrGetValue)?.symbol == owner.symbol
                ) uses.mutated = true
                if (expression.symbol != exactArrayGet && expression.symbol != exactArraySize &&
                    expression.getArgumentsWithIr().any { (_, argument) ->
                        argument.unwrapDirectMutableRead()?.symbol == owner.symbol
                    }
                ) uses.escaped = true
                expression.acceptChildrenVoid(this)
            }

            override fun visitGetValue(expression: IrGetValue) {
                if (expression.symbol == owner.symbol && expression !in allowedReads) {
                    uses.onlyVerifiedReads = false
                }
            }
        })

        val strongLocalValOwner = !owner.isVar && owner.parent === function &&
                owner.type.getClass()?.symbol == symbols.array &&
                !owner.hasAnnotation(KonanFqNames.arcWeak) &&
                !owner.hasAnnotation(KonanFqNames.arcUnowned) &&
                !owner.hasAnnotation(KonanFqNames.volatile)
        val exactFreshStackAllocation = owner.initializer?.isCanonicalFreshStackArrayInitializer(
            symbols.arrayOf,
            symbols.array,
            exactArraySet,
            lifetimes,
        ) == true
        val commonOwnerProof = strongLocalValOwner && exactFreshStackAllocation &&
                owner !in capturedVariables && !uses.assigned && !uses.aliased && !uses.returned &&
                !uses.escaped && !uses.mutated && uses.onlyVerifiedReads

        ownerCandidates.forEach { candidate ->
            val eligibility = ArcBorrowedArrayElementEligibility(
                arcEnabled = generationState.context.memoryModel == MemoryModel.ARC,
                optimizationsEnabled = generationState.context.config.optimizationsEnabled,
                debugInfoDisabled = !generationState.context.shouldContainDebugInfo(),
                nonSuspendFunction = nonSuspendFunction,
                exactReferenceArrayGet = candidate.arrayGet.symbol == exactArrayGet &&
                        candidate.arrayGet.type.binaryTypeIsReference(),
                immediateKotlinConsumer = candidate.consumer.isKotlinBorrowConsumer(generationState),
                referenceConsumerParameter = candidate.parameterReference,
                strongLocalValOwner = strongLocalValOwner,
                exactFreshStackArrayAllocation = exactFreshStackAllocation,
                ownerNotCaptured = owner !in capturedVariables,
                ownerNeverAssigned = !uses.assigned,
                ownerNeverAliased = !uses.aliased,
                ownerNeverReturned = !uses.returned,
                ownerNeverEscaped = !uses.escaped,
                arrayNeverMutated = !uses.mutated,
                onlyVerifiedOwnerReads = uses.onlyVerifiedReads,
                suffixDoesNotObserveOwner = !candidate.suffix.ownerObserved,
                callFreeSuffix = !candidate.suffix.containsCall,
                nonSuspendingSuffix = !candidate.suffix.containsSuspension,
                nonThrowingSuffix = !candidate.suffix.containsThrow,
                linearControlFlowSuffix = !candidate.suffix.containsControlFlow,
                ownerLivesThroughNormalAndUnwindEdges = commonOwnerProof &&
                        verifyBorrowedArrayElementProof(function, owner),
            )
            if (eligibility.isAuthorized()) selected += candidate.arrayGet
        }
    }
    return selected
}

private fun IrExpression.isCanonicalFreshStackArrayInitializer(
    arrayOfSymbol: IrSimpleFunctionSymbol,
    arraySymbol: org.jetbrains.kotlin.ir.symbols.IrClassSymbol,
    arraySet: IrSimpleFunctionSymbol,
    lifetimes: Map<IrElement, Lifetime>,
): Boolean {
    val arrayOf = this as? IrCall ?: return false
    if (arrayOf.symbol != arrayOfSymbol || arrayOf.dispatchReceiver != null ||
        arrayOf.extensionReceiver != null || arrayOf.type.getClass()?.symbol != arraySymbol
    ) return false
    val block = arrayOf.getArgumentsWithIr().singleOrNull()?.second as? IrBlock ?: return false
    if (block.type.getClass()?.symbol != arraySymbol || block.statements.size < 2) return false
    val result = block.statements.last() as? IrGetValue ?: return false
    val arrayVariable = result.symbol.owner as? IrVariable ?: return false
    val allocation = arrayVariable.initializer as? IrConstructorCall ?: return false
    if (allocation.symbol.owner.constructedClass.symbol != arraySymbol || lifetimes[allocation] !== Lifetime.STACK) {
        return false
    }
    val allocationIndex = block.statements.indexOfFirst { it === arrayVariable }
    if (allocationIndex < 0 || allocationIndex == block.statements.lastIndex) return false
    // Canonical VarargLowering evaluates element temporaries, creates one Array, initializes only
    // that Array's slots, and returns the exact allocation variable. No branch or alternate Array
    // source can satisfy this structural result-provenance proof.
    if (block.statements.take(allocationIndex).any {
        it !is IrVariable || it.type.getClass()?.symbol == arraySymbol
    }) return false
    return block.statements.subList(allocationIndex + 1, block.statements.lastIndex).all { statement ->
        val set = statement as? IrCall ?: return@all false
        val receiver = set.dispatchReceiver as? IrGetValue
        receiver?.symbol == arrayVariable.symbol &&
                (set.symbol == arraySet || set.symbol.owner.name == KonanNameConventions.setWithoutBoundCheck)
    }
}

private fun IrFunctionAccessExpression.isKotlinBorrowConsumer(generationState: NativeGenerationState): Boolean = when (this) {
    is IrConstructorCall -> false
    is IrCall -> {
        val callee = symbol.owner
        callee.isReal && !callee.isExternal && !callee.isBuiltInOperator && !callee.isTypedIntrinsic &&
                !callee.isObjCBridgeBased() && !callee.isArcSuspendLike() &&
                symbol != generationState.context.ir.symbols.arcWeakReferenceLoad &&
                symbol != generationState.context.ir.symbols.arcUnownedReferenceLoad
    }
    else -> false
}

private fun verifyBorrowedArrayElementProof(function: IrSimpleFunction, ownerVariable: IrVariable): Boolean {
    val owner = ArcValue("array_owner_${ownerVariable.name}")
    val element = ArcValue("borrowed_element_${ownerVariable.name}")
    val entry = ArcBlockId("entry")
    val invoke = ArcBlockId("invoke")
    val normal = ArcBlockId("normal")
    val unwind = ArcBlockId("unwind")
    val proof = ArcFunctionPlan(
        functionName = "${function.fqNameForIrSerialization.asString()}#borrow-array-${ownerVariable.name}",
        entry = entry,
        entryValues = emptyMap(),
        entryInitializedStorage = emptySet(),
        blocks = linkedMapOf(
            entry to ArcBasicBlock(
                entry,
                listOf(ArcOperation.Define(owner, ArcOwnership.Owned), ArcOperation.Borrow(owner, element)),
                ArcTerminator.Jump(invoke),
            ),
            invoke to ArcBasicBlock(
                invoke,
                listOf(ArcOperation.Use(element, ArcPlanLocation("immediate Kotlin consumer"))),
                ArcTerminator.Branch(normal, unwind),
            ),
            normal to ArcBasicBlock(
                normal,
                listOf(ArcOperation.EndBorrow(element), ArcOperation.Destroy(owner)),
                ArcTerminator.Return(),
            ),
            unwind to ArcBasicBlock(
                unwind,
                listOf(ArcOperation.EndBorrow(element), ArcOperation.Destroy(owner)),
                ArcTerminator.Throw,
            ),
        ),
    )
    return ArcOwnershipVerifier.verify(proof) === ArcOwnershipVerificationResult.Success
}

/**
 * Select a +0 load when a direct Kotlin call bounds the borrow and every later explicit argument
 * is a linear, call-free expression that cannot replace the owning local. The local stack slot
 * remains the +1 owner across both normal evaluation and the final call.
 */
private fun selectVerifiedBorrowedMutableReads(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
    borrowedGuaranteedAliases: Set<IrVariable>,
): Set<IrGetValue> {
    val nonSuspendFunction = !function.isArcSuspendLike()
    if (generationState.context.shouldContainDebugInfo() || !nonSuspendFunction) return emptySet()
    val body = function.body ?: return emptySet()
    val selected = linkedSetOf<IrGetValue>()
    val capturedVariables = collectVariablesCapturedByNestedFunctions(function)

    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        // A read of an outer mutable local is captured even if an earlier lowering has not boxed it.
        override fun visitFunction(declaration: IrFunction) = Unit

        override fun visitFunctionAccess(expression: IrFunctionAccessExpression) {
            val arguments = expression.getArgumentsWithIr()
            if (arguments.isEmpty()) {
                expression.acceptChildrenVoid(this)
                return
            }
            val directCall = expression.isDirectKotlinCall(generationState)
            arguments.forEachIndexed { index, (parameter, argument) ->
                val read = argument.unwrapBorrowedMutableRead(generationState)
                val variable = read?.symbol?.owner as? IrVariable
                val mutableReference = variable?.let { it.isVar && it.type.binaryTypeIsReference() } == true &&
                        parameter.type.binaryTypeIsReference()
                val notCaptured = variable?.let {
                    it.parent === function && it !in capturedVariables
                } == true
                val strongStorage = variable?.let {
                    !it.hasAnnotation(KonanFqNames.arcWeak) &&
                            !it.hasAnnotation(KonanFqNames.arcUnowned) &&
                            !it.hasAnnotation(KonanFqNames.volatile)
                } == true
                val suffix = if (variable == null) ArcArgumentSuffixAnalysis() else
                    analyzeBorrowedArgumentSuffix(arguments.drop(index + 1).map { it.second }, variable)
                val eligibility = ArcBorrowedMutableReadEligibility(
                    arcEnabled = generationState.context.memoryModel == MemoryModel.ARC,
                    debugInfoDisabled = !generationState.context.shouldContainDebugInfo(),
                    nonSuspendFunction = nonSuspendFunction,
                    directKotlinCall = directCall,
                    mutableLocalReference = mutableReference,
                    notCaptured = notCaptured,
                    strongStorage = strongStorage,
                    sideEffectFreeArgumentWrapper = read != null,
                    ownerNotAssignedInSuffix = !suffix.ownerAssigned,
                    callFreeSuffix = !suffix.containsCall,
                    nonSuspendingSuffix = !suffix.containsSuspension,
                    nonThrowingSuffix = !suffix.containsThrow,
                    linearControlFlowSuffix = !suffix.containsControlFlow,
                )
                if (read != null && variable !in borrowedGuaranteedAliases && eligibility.isAuthorized() &&
                    verifyBorrowedReadProof(function, variable!!)
                ) {
                    selected += read
                }
            }
            expression.acceptChildrenVoid(this)
        }
    })
    return selected
}

/** Borrow any exact mutable-local receiver for only the immediate direct field address/load. */
private fun selectVerifiedBorrowedFieldReceivers(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
): Set<IrGetValue> {
    val nonSuspendFunction = !function.isArcSuspendLike()
    if (generationState.context.memoryModel != MemoryModel.ARC ||
        !generationState.context.config.optimizationsEnabled ||
        generationState.context.shouldContainDebugInfo() || !nonSuspendFunction
    ) return emptySet()
    val body = function.body ?: return emptySet()
    val capturedVariables = collectVariablesCapturedByNestedFunctions(function)
    val selected = Collections.newSetFromMap(IdentityHashMap<IrGetValue, Boolean>())

    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) = Unit

        override fun visitGetField(expression: IrGetField) {
            val receiver = expression.receiver as? IrGetValue
            val owner = receiver?.symbol?.owner as? IrVariable
            val field = expression.symbol.owner
            val eligibility = ArcBorrowedFieldReceiverEligibility(
                arcEnabled = generationState.context.memoryModel == MemoryModel.ARC,
                optimizationsEnabled = generationState.context.config.optimizationsEnabled,
                debugInfoDisabled = !generationState.context.shouldContainDebugInfo(),
                nonSuspendFunction = nonSuspendFunction,
                exactDirectInstanceFieldReceiver = receiver != null && expression.receiver === receiver && !field.isStatic,
                nonVolatileField = !field.hasAnnotation(KonanFqNames.volatile),
                strongFieldStorage = !field.hasAnnotation(KonanFqNames.arcWeak) &&
                        !field.hasAnnotation(KonanFqNames.arcUnowned),
                mutableLocalReferenceOwner = owner != null && owner.isVar && owner.parent === function &&
                        owner.type.binaryTypeIsReference(),
                ownerNotCaptured = owner != null && owner !in capturedVariables,
                strongOwnerStorage = owner != null &&
                        !owner.hasAnnotation(KonanFqNames.arcWeak) &&
                        !owner.hasAnnotation(KonanFqNames.arcUnowned) &&
                        !owner.hasAnnotation(KonanFqNames.volatile),
                immediateAddressAndLoadOnly = receiver != null && expression.receiver === receiver,
                ownerLivesThroughLoad = owner != null,
            )
            if (receiver != null && owner != null && eligibility.isAuthorized() &&
                verifyBorrowedFieldReceiverProof(function, owner, field.name.asString())
            ) {
                selected += receiver
            }
            expression.acceptChildrenVoid(this)
        }
    })
    return selected
}

private fun verifyBorrowedFieldReceiverProof(
    function: IrSimpleFunction,
    ownerVariable: IrVariable,
    fieldName: String,
): Boolean {
    val owner = ArcValue("field_receiver_${ownerVariable.name}")
    val receiver = ArcValue("borrowed_receiver_${ownerVariable.name}")
    val entry = ArcBlockId("entry")
    val proof = ArcFunctionPlan(
        functionName = "${function.fqNameForIrSerialization.asString()}#borrow-field-receiver-$fieldName",
        entry = entry,
        entryValues = emptyMap(),
        entryInitializedStorage = emptySet(),
        blocks = mapOf(
            entry to ArcBasicBlock(
                entry,
                listOf(
                    ArcOperation.Define(owner, ArcOwnership.Owned),
                    ArcOperation.Borrow(owner, receiver, ArcBorrowKind.Identity),
                    ArcOperation.Use(receiver, ArcPlanLocation("immediate field address/load")),
                    ArcOperation.EndBorrow(receiver),
                    ArcOperation.Destroy(owner),
                ),
                ArcTerminator.Return(),
            )
        ),
    )
    return ArcOwnershipVerifier.verify(proof) === ArcOwnershipVerificationResult.Success
}

/**
 * Select only the structural replacement produced by `cursor = cursor.next` or
 * `cursor = cursor.next!!`. No getter call, safe-call, cast, alternate owner, or expression with
 * user-controlled evaluation can enter this first slice.
 */
private data class ArcBorrowedStrongFieldProjectionSelection(
    val fieldLoads: Set<IrGetField>,
    val replacementStores: Set<IrSetValue>,
)

private data class ArcBorrowedCharArrayConsumerSymbols(
    val sizeGetter: IrSimpleFunctionSymbol,
    val sizedCopy: IrSimpleFunctionSymbol,
)

private fun resolveBorrowedCharArrayConsumerSymbols(
    generationState: NativeGenerationState,
): ArcBorrowedCharArrayConsumerSymbols? {
    val symbols = generationState.context.ir.symbols
    val sizeGetter = symbols.arraySize[symbols.charArray] ?: return null
    val sizedCopy = generationState.context.irBuiltIns
        .findFunctions(Name.identifier("copyOf"), "kotlin", "collections")
        .singleOrNull { symbol ->
            val function = symbol.owner
            !function.isExpect && function.isReal && !function.isExternal &&
                    function.extensionReceiverParameter?.type?.isCharArray() == true &&
                    function.valueParameters.singleOrNull()?.type?.isInt() == true &&
                    function.returnType.isCharArray()
        } ?: return null
    return ArcBorrowedCharArrayConsumerSymbols(sizeGetter, sizedCopy)
}

/**
 * Borrow `this.charArrayField` only through the two non-mutating CharArray consumers used by
 * StringBuilder capacity growth. The callee never receives `this`, so it cannot replace the
 * owning field; both normal and exceptional exits remain bounded by the receiver parameter.
 */
private fun selectVerifiedBorrowedStrongCallFieldLoads(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
    exactConsumers: ArcBorrowedCharArrayConsumerSymbols,
): Set<IrGetField> {
    val nonSuspendFunction = !function.isArcSuspendLike()
    if (generationState.context.memoryModel != MemoryModel.ARC ||
        !generationState.context.config.optimizationsEnabled ||
        generationState.context.shouldContainDebugInfo() || !nonSuspendFunction
    ) return emptySet()
    val body = function.body ?: return emptySet()
    val dispatchReceiver = function.dispatchReceiverParameter ?: return emptySet()
    val selected = Collections.newSetFromMap(IdentityHashMap<IrGetField, Boolean>())

    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) = Unit

        override fun visitCall(expression: IrCall) {
            val arguments = expression.getArgumentsWithIr()
            arguments.forEachIndexed { index, (_, argument) ->
                val fieldLoad = argument as? IrGetField ?: return@forEachIndexed
                val ownerRead = fieldLoad.receiver as? IrGetValue
                val field = fieldLoad.symbol.owner
                val exactAllowlistedCharArrayConsumer =
                    expression.isAllowlistedBorrowedCharArrayConsumer(fieldLoad, exactConsumers)
                if (!exactAllowlistedCharArrayConsumer) return@forEachIndexed
                val suffix = analyzeBorrowedArgumentSuffix(
                    arguments.drop(index + 1).map { it.second }, dispatchReceiver
                )
                val verifierAccepted = verifyBorrowedStrongCallFieldProof(
                    function, field.name.asString(), expression.symbol.owner.name.asString()
                )
                val eligibility = ArcBorrowedStrongCallFieldEligibility(
                    arcEnabled = generationState.context.memoryModel == MemoryModel.ARC,
                    optimizationsEnabled = generationState.context.config.optimizationsEnabled,
                    debugInfoDisabled = !generationState.context.shouldContainDebugInfo(),
                    nonSuspendFunction = nonSuspendFunction,
                    exactDirectInstanceFieldArgument = !field.isStatic && fieldLoad.receiver === ownerRead,
                    referenceField = fieldLoad.type.binaryTypeIsReference() && fieldLoad.type.isCharArray(),
                    nonVolatileField = !field.hasAnnotation(KonanFqNames.volatile),
                    strongFieldStorage = !field.hasAnnotation(KonanFqNames.arcWeak) &&
                            !field.hasAnnotation(KonanFqNames.arcUnowned),
                    ownerIsCurrentDispatchReceiver = ownerRead?.symbol == dispatchReceiver.symbol,
                    ownerReferenceIsGuaranteedForCall = dispatchReceiver.type.binaryTypeIsReference(),
                    exactAllowlistedCharArrayConsumer = exactAllowlistedCharArrayConsumer,
                    ownerNotAssignedInSuffix = !suffix.ownerAssigned,
                    callFreeSuffix = !suffix.containsCall,
                    nonSuspendingSuffix = !suffix.containsSuspension,
                    nonThrowingSuffix = !suffix.containsThrow,
                    linearControlFlowSuffix = !suffix.containsControlFlow,
                    ownershipEffectFreeSuffix = !suffix.containsReferenceOwnershipEffect,
                    verifierProofAccepted = verifierAccepted,
                )
                if (eligibility.isAuthorized()) {
                    generationState.context.log {
                        "ARC borrowed strong call field ${function.fqNameForIrSerialization.asString()}::${field.name} " +
                                "-> ${expression.symbol.owner.fqNameForIrSerialization.asString()}"
                    }
                    selected += fieldLoad
                }
            }
            expression.acceptChildrenVoid(this)
        }
    })
    return selected
}

private fun IrCall.isAllowlistedBorrowedCharArrayConsumer(
    fieldLoad: IrGetField,
    exactConsumers: ArcBorrowedCharArrayConsumerSymbols,
): Boolean {
    val callee = symbol.owner
    if (!callee.isReal || callee.isExternal || callee.isSuspend || callee.isArcSuspendLike()) return false
    val arguments = getArgumentsWithIr()
    val fieldIndex = arguments.indexOfFirst { it.second === fieldLoad }
    if (fieldIndex < 0 || !fieldLoad.type.isCharArray()) return false
    val sizeGetter = symbol == exactConsumers.sizeGetter &&
            callee.returnType.isInt() && arguments.size == 1 && fieldIndex == 0 &&
            arguments[0].first.type.isCharArray()
    val sizedCopy = symbol == exactConsumers.sizedCopy &&
            callee.returnType.isCharArray() && arguments.size == 2 && fieldIndex == 0 &&
            arguments[0].first.type.isCharArray() && arguments[1].first.type.isInt() &&
            arguments[1].second.type.isInt()
    return sizeGetter || sizedCopy
}

private fun verifyBorrowedStrongCallFieldProof(
    function: IrSimpleFunction,
    fieldName: String,
    consumerName: String,
): Boolean {
    val owner = ArcValue("call_field_owner_$fieldName")
    val projection = ArcValue("call_field_projection_$fieldName")
    val entry = ArcBlockId("entry")
    val invoke = ArcBlockId("invoke")
    val normal = ArcBlockId("normal")
    val unwind = ArcBlockId("unwind")
    val proof = ArcFunctionPlan(
        functionName = "${function.fqNameForIrSerialization.asString()}#borrow-call-field-$fieldName-$consumerName",
        entry = entry,
        entryValues = emptyMap(),
        entryInitializedStorage = emptySet(),
        blocks = linkedMapOf(
            entry to ArcBasicBlock(
                entry,
                listOf(
                    ArcOperation.Define(owner, ArcOwnership.Guaranteed),
                    ArcOperation.Borrow(owner, projection, ArcBorrowKind.Projection),
                ),
                ArcTerminator.Jump(invoke),
            ),
            invoke to ArcBasicBlock(
                invoke,
                listOf(ArcOperation.Use(projection, ArcPlanLocation("allowlisted CharArray call argument"))),
                ArcTerminator.Branch(normal, unwind),
            ),
            normal to ArcBasicBlock(
                normal,
                listOf(ArcOperation.EndBorrow(projection)),
                ArcTerminator.Return(),
            ),
            unwind to ArcBasicBlock(
                unwind,
                listOf(ArcOperation.EndBorrow(projection)),
                ArcTerminator.Throw,
            ),
        ),
    )
    return ArcOwnershipVerifier.verify(proof) === ArcOwnershipVerificationResult.Success
}

private fun selectVerifiedBorrowedStrongFieldProjections(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
): ArcBorrowedStrongFieldProjectionSelection {
    fun emptySelection() = ArcBorrowedStrongFieldProjectionSelection(emptySet(), emptySet())
    val nonSuspendFunction = !function.isArcSuspendLike()
    if (generationState.context.memoryModel != MemoryModel.ARC ||
        !generationState.context.config.optimizationsEnabled ||
        generationState.context.shouldContainDebugInfo() || !nonSuspendFunction
    ) return emptySelection()
    val body = function.body ?: return emptySelection()
    val capturedVariables = collectVariablesCapturedByNestedFunctions(function)
    val selectedFields = Collections.newSetFromMap(IdentityHashMap<IrGetField, Boolean>())
    val selectedStores = Collections.newSetFromMap(IdentityHashMap<IrSetValue, Boolean>())

    body.acceptVoid(object : IrElementVisitorVoid {
        var tryDepth = 0
        var returnDepth = 0
        var callDepth = 0
        var fieldWriteDepth = 0
        var nestedSetValueDepth = 0

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) = Unit

        override fun visitTry(aTry: IrTry) {
            tryDepth++
            aTry.acceptChildrenVoid(this)
            tryDepth--
        }

        override fun visitReturn(expression: IrReturn) {
            returnDepth++
            expression.acceptChildrenVoid(this)
            returnDepth--
        }

        override fun visitFunctionAccess(expression: IrFunctionAccessExpression) {
            callDepth++
            expression.acceptChildrenVoid(this)
            callDepth--
        }

        override fun visitSetField(expression: IrSetField) {
            fieldWriteDepth++
            expression.acceptChildrenVoid(this)
            fieldWriteDepth--
        }

        override fun visitSetValue(expression: IrSetValue) {
            val owner = expression.symbol.owner as? IrVariable
            val field = expression.value.unwrapCanonicalStrongFieldProjection(generationState)
            val receiver = field?.receiver as? IrGetValue
            val fieldOwner = field?.symbol?.owner
            val canonicalExpression = field != null &&
                    (expression.value is IrGetField || expression.value is IrBlock)
            val canonicalSelfReplacement = receiver?.symbol == expression.symbol
            val outsideForbiddenAncestors = tryDepth == 0 && returnDepth == 0 && callDepth == 0 &&
                    fieldWriteDepth == 0 && nestedSetValueDepth == 0
            val strongOwnerStorage = owner?.let {
                !it.hasAnnotation(KonanFqNames.arcWeak) &&
                        !it.hasAnnotation(KonanFqNames.arcUnowned) &&
                        !it.hasAnnotation(KonanFqNames.volatile)
            } == true
            val eligibility = ArcBorrowedStrongFieldProjectionEligibility(
                arcEnabled = generationState.context.memoryModel == MemoryModel.ARC,
                optimizationsEnabled = generationState.context.config.optimizationsEnabled,
                debugInfoDisabled = !generationState.context.shouldContainDebugInfo(),
                nonSuspendFunction = nonSuspendFunction,
                exactDirectInstanceFieldRead = field != null && field.receiver === receiver &&
                        fieldOwner?.isStatic == false,
                nonVolatileField = fieldOwner?.hasAnnotation(KonanFqNames.volatile) == false,
                referenceField = field?.type?.binaryTypeIsReference() == true,
                strongFieldStorage = fieldOwner != null &&
                        !fieldOwner.hasAnnotation(KonanFqNames.arcWeak) &&
                        !fieldOwner.hasAnnotation(KonanFqNames.arcUnowned),
                mutableLocalReferenceOwner = owner != null && owner.isVar && owner.parent === function &&
                        owner.type.binaryTypeIsReference(),
                ownerNotCaptured = owner != null && owner !in capturedVariables,
                strongOwnerStorage = strongOwnerStorage,
                exactReceiverRead = receiver != null && field.receiver === receiver,
                canonicalSelfReplacement = canonicalSelfReplacement,
                // Direct field projection and the exact compiler-generated `!!` block contain no
                // evaluation point at which user code can replace or escape the owning slot.
                ownerUnchangedUntilFinalStore = canonicalExpression && canonicalSelfReplacement,
                noUnmodeledCallOrSuspension = canonicalExpression && callDepth == 0,
                noTryReturnWriteOrEscape = canonicalExpression && outsideForbiddenAncestors,
                exactMutableStrongReplacementStore = canonicalExpression && canonicalSelfReplacement &&
                        owner != null && owner.isVar && owner.type.binaryTypeIsReference(),
            )
            if (owner != null && receiver != null && eligibility.isAuthorized() &&
                verifyBorrowedStrongFieldProjectionProof(function, owner, field)
            ) {
                selectedFields += field
                selectedStores += expression
            }
            nestedSetValueDepth++
            expression.acceptChildrenVoid(this)
            nestedSetValueDepth--
        }
    })
    return ArcBorrowedStrongFieldProjectionSelection(selectedFields, selectedStores)
}

/**
 * Causally attach codegen authorization to the mandatory path verifier. The normal edge retains
 * the projected value into the cursor slot before ending the projection borrow and consuming the
 * old owner. A failing `!!`/unwind edge ends the borrow and consumes only the old owner.
 */
private fun verifyBorrowedStrongFieldProjectionProof(
    function: IrSimpleFunction,
    ownerVariable: IrVariable,
    field: IrGetField,
): Boolean {
    val owner = ArcValue("field_owner_${ownerVariable.name}")
    val projected = ArcValue("field_projection_${ownerVariable.name}")
    val cursorSlot = ArcStorage("local_${ownerVariable.name}")
    val entry = ArcBlockId("entry")
    val checked = ArcBlockId("checked")
    val normal = ArcBlockId("normal")
    val unwind = ArcBlockId("unwind")
    val proof = ArcFunctionPlan(
        functionName = "${function.fqNameForIrSerialization.asString()}#borrow-field-${field.symbol.owner.name}",
        entry = entry,
        entryValues = emptyMap(),
        entryInitializedStorage = setOf(cursorSlot),
        blocks = linkedMapOf(
            entry to ArcBasicBlock(
                entry,
                listOf(
                    ArcOperation.Define(owner, ArcOwnership.Owned),
                    ArcOperation.Borrow(owner, projected, ArcBorrowKind.Projection),
                ),
                ArcTerminator.Jump(checked),
            ),
            checked to ArcBasicBlock(
                checked,
                listOf(ArcOperation.Use(projected, ArcPlanLocation("field projection/null check"))),
                ArcTerminator.Branch(normal, unwind),
            ),
            normal to ArcBasicBlock(
                normal,
                listOf(
                    ArcOperation.StrongReplace(cursorSlot, owner, projected),
                ),
                ArcTerminator.Return(),
            ),
            unwind to ArcBasicBlock(
                unwind,
                listOf(ArcOperation.EndBorrow(projected), ArcOperation.Destroy(owner)),
                ArcTerminator.Throw,
            ),
        ),
    )
    return ArcOwnershipVerifier.verify(proof) === ArcOwnershipVerificationResult.Success
}

private data class ArcRootedProjectionLoopSelection(
    val variables: Set<IrVariable>,
    val reads: Set<IrGetValue>,
    val fieldLoads: Set<IrGetField>,
    val stores: Set<IrSetValue>,
) {
    companion object {
        val Empty = ArcRootedProjectionLoopSelection(emptySet(), emptySet(), emptySet(), emptySet())
    }
}

private data class ArcLexicalStatementPosition(val container: IrElement, val index: Int)

/** A declaration in a synthetic outer statement dominates only a strictly nested use path. */
internal fun arcStrictLexicalPrefixDominates(declarationPathSize: Int, usePathSize: Int): Boolean =
    declarationPathSize < usePathSize

internal fun arcNestedStructuralDominanceFallback(
    targetAncestorDepth: Int,
    declarationLoopPathMatches: Boolean,
    declarationVisitedBeforeTarget: Boolean,
): Boolean = targetAncestorDepth > 0 && declarationLoopPathMatches && declarationVisitedBeforeTarget

private fun lexicallyDeclaredBefore(
    declaration: IrElement,
    use: IrElement,
    positions: IdentityHashMap<IrElement, List<ArcLexicalStatementPosition>>,
): Boolean {
    val declarationPath = positions[declaration] ?: return false
    val usePath = positions[use] ?: return false
    val common = minOf(declarationPath.size, usePath.size)
    for (index in 0 until common) {
        val declarationPosition = declarationPath[index]
        val usePosition = usePath[index]
        if (declarationPosition.container !== usePosition.container) return false
        if (declarationPosition.index != usePosition.index) {
            return declarationPosition.index < usePosition.index
        }
    }
    // Inline lowering may wrap a sequence of declarations and a nested loop in one synthetic
    // statement. In that shape the declaration path is a strict prefix of the loop path even
    // though the declaration executes before entering the nested container. A cursor cannot be
    // referenced from its own initializer in valid IR, so only the strict-prefix direction is a
    // lexical dominance relation; equal or reverse-prefix paths remain fail-closed.
    return arcStrictLexicalPrefixDominates(declarationPath.size, usePath.size)
}

/**
 * Select a cursor that may remain +0 because a separately live strong anchor owns the entire
 * closed projection graph. This first slice is intentionally structural and loop-local: any
 * unrecognized operation rejects the candidate rather than widening the rooted lifetime.
 */
private fun selectVerifiedRootedProjectionLoops(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
    lifetimes: Map<IrElement, Lifetime>,
): ArcRootedProjectionLoopSelection {
    val nonSuspendFunction = !function.isArcSuspendLike()
    if (generationState.context.memoryModel != MemoryModel.ARC ||
        !generationState.context.config.optimizationsEnabled ||
        generationState.context.shouldContainDebugInfo() || !nonSuspendFunction
    ) return ArcRootedProjectionLoopSelection.Empty
    val body = function.body ?: return ArcRootedProjectionLoopSelection.Empty
    val capturedVariables = collectVariablesCapturedByNestedFunctions(function)
    val variables = mutableListOf<IrVariable>()
    val lexicalPositions = IdentityHashMap<IrElement, List<ArcLexicalStatementPosition>>()
    val declarationLoopPaths = IdentityHashMap<IrVariable, List<IrLoop>>()
    val structuralOrder = IdentityHashMap<IrElement, Int>()
    body.acceptVoid(object : IrElementVisitorVoid {
        val path = mutableListOf<ArcLexicalStatementPosition>()
        val loops = mutableListOf<IrLoop>()
        var nextStructuralOrder = 0

        private fun recordStructuralOrder(element: IrElement) {
            if (!structuralOrder.containsKey(element)) structuralOrder[element] = nextStructuralOrder++
        }

        private fun visitStatements(container: IrElement, statements: List<org.jetbrains.kotlin.ir.IrStatement>) {
            statements.forEachIndexed { index, statement ->
                path += ArcLexicalStatementPosition(container, index)
                lexicalPositions[statement] = path.toList()
                statement.acceptVoid(this)
                path.removeAt(path.lastIndex)
            }
        }

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) = Unit

        override fun visitLoop(loop: IrLoop) {
            recordStructuralOrder(loop)
            loops += loop
            loop.acceptChildrenVoid(this)
            loops.removeAt(loops.lastIndex)
        }

        override fun visitBlockBody(body: IrBlockBody) {
            visitStatements(body, body.statements)
        }

        override fun visitContainerExpression(expression: IrContainerExpression) {
            visitStatements(expression, expression.statements)
        }

        override fun visitVariable(declaration: IrVariable) {
            recordStructuralOrder(declaration)
            variables += declaration
            declarationLoopPaths[declaration] = loops.toList()
            declaration.acceptChildrenVoid(this)
        }
    })

    val selectedVariables = Collections.newSetFromMap(IdentityHashMap<IrVariable, Boolean>())
    val selectedReads = Collections.newSetFromMap(IdentityHashMap<IrGetValue, Boolean>())
    val selectedFields = Collections.newSetFromMap(IdentityHashMap<IrGetField, Boolean>())
    val selectedStores = Collections.newSetFromMap(IdentityHashMap<IrSetValue, Boolean>())

    variables.forEach { cursor ->
        val strongCursor = cursor.isVar && cursor.type.binaryTypeIsReference() &&
                !cursor.hasAnnotation(KonanFqNames.arcWeak) &&
                !cursor.hasAnnotation(KonanFqNames.arcUnowned) &&
                !cursor.hasAnnotation(KonanFqNames.volatile)
        val anchorRead = cursor.initializer as? IrGetValue
        val anchor = anchorRead?.symbol?.owner
        val parameterAnchor = anchor is IrValueParameter && anchor in function.allParameters &&
                anchor.type.binaryTypeIsReference()
        val variableAnchor = anchor as? IrVariable
        val anchorAllocation = variableAnchor?.initializer as? IrConstructorCall
        val stackAllocationAnchor = variableAnchor != null && !variableAnchor.isVar &&
                variableAnchor.type.binaryTypeIsReference() && anchorAllocation != null &&
                lifetimes[anchorAllocation] === Lifetime.STACK &&
                variableAnchor !in capturedVariables &&
                !variableAnchor.hasAnnotation(KonanFqNames.arcWeak) &&
                !variableAnchor.hasAnnotation(KonanFqNames.arcUnowned) &&
                !variableAnchor.hasAnnotation(KonanFqNames.volatile)
        val directAnchor = parameterAnchor || stackAllocationAnchor
        if (!strongCursor || !directAnchor || cursor in capturedVariables) return@forEach

        val reads = mutableListOf<IrGetValue>()
        val stores = mutableListOf<Pair<IrSetValue, List<IrLoop>>>()
        val receiverFields = IdentityHashMap<IrGetValue, IrGetField>()
        val readLoopPaths = IdentityHashMap<IrGetValue, List<IrLoop>>()
        val loopPaths = IdentityHashMap<IrLoop, List<IrLoop>>()
        val loopStack = mutableListOf<IrLoop>()
        body.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) = Unit

            override fun visitLoop(loop: IrLoop) {
                loopStack += loop
                loopPaths[loop] = loopStack.toList()
                loop.acceptChildrenVoid(this)
                loopStack.removeAt(loopStack.lastIndex)
            }

            override fun visitGetField(expression: IrGetField) {
                val receiver = expression.receiver as? IrGetValue
                if (receiver?.symbol == cursor.symbol) receiverFields[receiver] = expression
                expression.acceptChildrenVoid(this)
            }

            override fun visitGetValue(expression: IrGetValue) {
                if (expression.symbol == cursor.symbol) {
                    reads += expression
                    readLoopPaths[expression] = loopStack.toList()
                }
            }

            override fun visitSetValue(expression: IrSetValue) {
                if (expression.symbol == cursor.symbol) stores += expression to loopStack.toList()
                expression.acceptChildrenVoid(this)
            }
        })

        val storeLoops = stores.mapNotNull { it.second.lastOrNull() }.toSet()
        val targetLoop = storeLoops.singleOrNull()
        val targetPath = targetLoop?.let { loopPaths[it] }.orEmpty()
        val exactlyOneLoop = targetLoop != null && stores.isNotEmpty() &&
                stores.all { (_, path) ->
                    path.size == targetPath.size && path.indices.all { path[it] === targetPath[it] }
                } &&
                reads.all { read ->
                    val path = readLoopPaths[read].orEmpty()
                    path.size == targetPath.size && path.indices.all { path[it] === targetPath[it] }
                }

        val transitionFields = stores.mapNotNull { (store, _) ->
            store.value.unwrapCanonicalStrongFieldProjection(generationState)
        }
        val exactTransitionFields = Collections.newSetFromMap(IdentityHashMap<IrGetField, Boolean>()).apply {
            addAll(transitionFields)
        }
        val canonicalCheckNotNullTemporaries = Collections.newSetFromMap(IdentityHashMap<IrVariable, Boolean>())
        stores.forEach { (store, _) ->
            val block = store.value as? IrBlock
            if (block?.unwrapLoweredCheckNotNullInitializer(generationState) is IrGetField) {
                (block.statements.firstOrNull() as? IrVariable)?.let { canonicalCheckNotNullTemporaries += it }
            }
        }
        val transitionField = transitionFields.map { it.symbol }.toSet().singleOrNull()?.owner
        val everyAssignmentCanonical = transitionFields.size == stores.size && stores.all { (store, _) ->
            val field = store.value.unwrapCanonicalStrongFieldProjection(generationState)
            val receiver = field?.receiver as? IrGetValue
            receiver?.symbol == cursor.symbol && field.symbol.owner === transitionField
        }
        val strongTransition = transitionField != null && !transitionField.isStatic &&
                transitionField.type.binaryTypeIsReference() &&
                !transitionField.hasAnnotation(KonanFqNames.volatile) &&
                !transitionField.hasAnnotation(KonanFqNames.arcWeak) &&
                !transitionField.hasAnnotation(KonanFqNames.arcUnowned)
        val everyReadDirectField = reads.isNotEmpty() && reads.all { read ->
            val field = receiverFields[read]
            field != null && field.receiver === read && !field.symbol.owner.isStatic &&
                    !field.symbol.owner.hasAnnotation(KonanFqNames.volatile) &&
                    !field.symbol.owner.hasAnnotation(KonanFqNames.arcWeak) &&
                    !field.symbol.owner.hasAnnotation(KonanFqNames.arcUnowned) &&
                    (!field.type.binaryTypeIsReference() || field in exactTransitionFields)
        }

        var seenTargetLoop = false
        var afterTargetLoop = false
        var useAfterRegion = false
        if (targetLoop != null) {
            body.acceptVoid(object : IrElementVisitorVoid {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitFunction(declaration: IrFunction) = Unit

                override fun visitLoop(loop: IrLoop) {
                    if (loop === targetLoop) {
                        seenTargetLoop = true
                        loop.acceptChildrenVoid(this)
                        afterTargetLoop = true
                    } else {
                        loop.acceptChildrenVoid(this)
                    }
                }

                override fun visitGetValue(expression: IrGetValue) {
                    if (expression.symbol == cursor.symbol && afterTargetLoop) useAfterRegion = true
                }

                override fun visitSetValue(expression: IrSetValue) {
                    if (expression.symbol == cursor.symbol && afterTargetLoop) useAfterRegion = true
                    expression.acceptChildrenVoid(this)
                }
            })
        }

        var unknownCall = false
        var tryFinally = false
        var suspension = false
        var nestedFunction = false
        var allocation = false
        var fieldWrite = false
        var otherReferenceOwnershipEffect = false
        var controlExit = false
        var nestedLoop = false
        val observedAllowedCallSymbols = linkedSetOf<String>()
        val rejectedCallSymbols = linkedSetOf<String>()
        val exactCursorStores = stores.mapTo(Collections.newSetFromMap(IdentityHashMap<IrSetValue, Boolean>())) { it.first }
        if (targetLoop != null) {
            fun inspectLoopPart(element: IrElement?) {
                element?.acceptVoid(object : IrElementVisitorVoid {
                    override fun visitElement(element: IrElement) {
                        when (element) {
                            is IrTry -> tryFinally = true
                            is IrSuspendableExpression, is IrSuspensionPoint -> suspension = true
                            // Inline lowering leaves returns to synthetic returnable blocks in the
                            // bodies of ordinary `repeat` loops. They do not exit this function or
                            // outlive the rooted cursor region. Real function returns, loop exits,
                            // and throws remain fail-closed.
                            is IrReturn -> if (element.returnTargetSymbol == function.symbol) controlExit = true
                            is IrBreak, is IrContinue, is IrThrow -> controlExit = true
                            is IrFunctionReference, is IrVararg -> allocation = true
                        }
                        element.acceptChildrenVoid(this)
                    }

                    override fun visitFunction(declaration: IrFunction) {
                        nestedFunction = true
                    }

                    override fun visitVariable(declaration: IrVariable) {
                        if (declaration.type.binaryTypeIsReference() &&
                            declaration !in canonicalCheckNotNullTemporaries
                        ) otherReferenceOwnershipEffect = true
                        declaration.acceptChildrenVoid(this)
                    }

                    override fun visitGetValue(expression: IrGetValue) {
                        if (expression.type.binaryTypeIsReference()) {
                            val declaration = expression.symbol.owner
                            val exactCursorRead = declaration === cursor
                            val exactNullTempRead = declaration is IrVariable &&
                                    declaration in canonicalCheckNotNullTemporaries
                            if (!exactCursorRead && !exactNullTempRead) otherReferenceOwnershipEffect = true
                        }
                    }

                    override fun visitSetValue(expression: IrSetValue) {
                        if (expression.symbol.owner.type.binaryTypeIsReference()) {
                            if (expression !in exactCursorStores) otherReferenceOwnershipEffect = true
                        }
                        expression.acceptChildrenVoid(this)
                    }

                    override fun visitGetField(expression: IrGetField) {
                        if (expression.type.binaryTypeIsReference() &&
                            expression !in exactTransitionFields
                        ) otherReferenceOwnershipEffect = true
                        expression.acceptChildrenVoid(this)
                    }

                    override fun visitLoop(loop: IrLoop) {
                        nestedLoop = true
                    }

                    override fun visitSetField(expression: IrSetField) {
                        fieldWrite = true
                        expression.acceptChildrenVoid(this)
                    }

                    override fun visitFunctionAccess(expression: IrFunctionAccessExpression) {
                        if (expression is IrConstructorCall) {
                            allocation = true
                        } else {
                            val call = expression as? IrCall
                            val explicitSafe = call != null &&
                                    (call.symbol == generationState.context.irBuiltIns.eqeqeqSymbol ||
                                            call.symbol == generationState.context.ir.symbols.throwNullPointerException ||
                                            call.symbol == generationState.context.ir.symbols.reinterpret ||
                                            call.symbol == generationState.context.ir.symbols.theUnitInstance)
                            val purePrimitiveIntrinsic = when (call?.let(::tryGetIntrinsicType)) {
                                IntrinsicType.PLUS, IntrinsicType.MINUS, IntrinsicType.TIMES,
                                IntrinsicType.SIGNED_DIV, IntrinsicType.SIGNED_REM,
                                IntrinsicType.UNSIGNED_DIV, IntrinsicType.UNSIGNED_REM,
                                IntrinsicType.INC, IntrinsicType.DEC,
                                IntrinsicType.UNARY_PLUS, IntrinsicType.UNARY_MINUS,
                                IntrinsicType.SHL, IntrinsicType.SHR, IntrinsicType.USHR,
                                IntrinsicType.AND, IntrinsicType.OR, IntrinsicType.XOR, IntrinsicType.INV,
                                IntrinsicType.SIGN_EXTEND, IntrinsicType.ZERO_EXTEND,
                                IntrinsicType.INT_TRUNCATE, IntrinsicType.FLOAT_TRUNCATE,
                                IntrinsicType.FLOAT_EXTEND, IntrinsicType.SIGNED_TO_FLOAT,
                                IntrinsicType.UNSIGNED_TO_FLOAT, IntrinsicType.FLOAT_TO_SIGNED,
                                IntrinsicType.SIGNED_COMPARE_TO, IntrinsicType.UNSIGNED_COMPARE_TO,
                                IntrinsicType.NOT, IntrinsicType.EXTRACT_ELEMENT,
                                IntrinsicType.ARE_EQUAL_BY_VALUE, IntrinsicType.IEEE_754_EQUALS -> true
                                else -> false
                            }
                            val primitiveBuiltin = call != null &&
                                    (call.symbol.owner.isBuiltInOperator || purePrimitiveIntrinsic) &&
                                    !call.type.binaryTypeIsReference() &&
                                    call.getArgumentsWithIr().all { (_, argument) ->
                                        !argument.type.binaryTypeIsReference()
                                    }
                            val allowed = explicitSafe || primitiveBuiltin
                            val symbolName = call?.symbol?.owner?.fqNameForIrSerialization?.asString() ?: "<non-call>"
                            if (allowed) {
                                observedAllowedCallSymbols += symbolName
                            } else {
                                unknownCall = true
                                rejectedCallSymbols += symbolName
                            }
                        }
                        expression.acceptChildrenVoid(this)
                    }
                })
            }
            inspectLoopPart(targetLoop.body)
            inspectLoopPart(targetLoop.condition)
        }

        val targetAncestors = targetPath.dropLast(1)
        val cursorDeclarationLoops = declarationLoopPaths[cursor].orEmpty()
        val anchorDeclarationLoops = variableAnchor?.let { declarationLoopPaths[it] }.orEmpty()
        val cursorReentryPathMatches = cursorDeclarationLoops.size == targetAncestors.size &&
                cursorDeclarationLoops.indices.all { cursorDeclarationLoops[it] === targetAncestors[it] }
        val anchorReentryPathMatches = variableAnchor != null &&
                anchorDeclarationLoops.size == targetAncestors.size &&
                anchorDeclarationLoops.indices.all { anchorDeclarationLoops[it] === targetAncestors[it] }
        val cursorVisitedBeforeTarget = targetLoop != null &&
                structuralOrder[cursor]?.let { cursorOrder ->
                    structuralOrder[targetLoop]?.let { targetOrder -> cursorOrder < targetOrder }
                } == true
        val anchorVisitedBeforeCursor = variableAnchor != null &&
                structuralOrder[variableAnchor]?.let { anchorOrder ->
                    structuralOrder[cursor]?.let { cursorOrder -> anchorOrder < cursorOrder }
                } == true
        val cursorDeclaredBeforeTarget = targetLoop != null &&
                (lexicallyDeclaredBefore(cursor, targetLoop, lexicalPositions) ||
                        arcNestedStructuralDominanceFallback(
                            targetAncestors.size, cursorReentryPathMatches, cursorVisitedBeforeTarget
                        ))
        val anchorDeclaredBeforeCursor = variableAnchor != null &&
                (lexicallyDeclaredBefore(variableAnchor, cursor, lexicalPositions) ||
                        arcNestedStructuralDominanceFallback(
                            targetAncestors.size, anchorReentryPathMatches, anchorVisitedBeforeCursor
                        ))
        val declarationsReexecuteBeforeNestedTarget = targetLoop != null &&
                (targetAncestors.isEmpty() ||
                        (cursorReentryPathMatches && cursorDeclaredBeforeTarget &&
                                (parameterAnchor || (variableAnchor != null &&
                                        anchorReentryPathMatches && anchorDeclaredBeforeCursor))))
        val anchorDominates = directAnchor && targetLoop != null && declarationsReexecuteBeforeNestedTarget &&
                cursorDeclaredBeforeTarget && (parameterAnchor || anchorDeclaredBeforeCursor)
        val verifierAccepted = targetLoop != null && verifyRootedProjectionLoopProof(function, cursor)
        val eligibility = ArcRootedProjectionLoopEligibility(
            arcEnabled = generationState.context.memoryModel == MemoryModel.ARC,
            optimizationsEnabled = generationState.context.config.optimizationsEnabled,
            debugInfoDisabled = !generationState.context.shouldContainDebugInfo(),
            nonSuspendFunction = nonSuspendFunction,
            mutableStrongLocalCursor = strongCursor,
            cursorNotCaptured = cursor !in capturedVariables,
            exactlyOneProjectionLoop = exactlyOneLoop && !nestedLoop,
            directLiveAnchorInitializer = directAnchor,
            anchorDominatesAndEnclosesLoop = anchorDominates,
            everyAssignmentCanonicalSelfProjection = everyAssignmentCanonical,
            everyReadImmediateDirectFieldReceiver = everyReadDirectField,
            noUsesAfterRegion = seenTargetLoop && !useAfterRegion,
            noUnknownOrUserCall = !unknownCall,
            noTryFinally = !tryFinally,
            noSuspension = !suspension,
            noNestedFunctionOrCallback = !nestedFunction,
            noAllocation = !allocation,
            noFieldWrite = !fieldWrite,
            noOtherReferenceOwnershipEffects = !otherReferenceOwnershipEffect,
            noAlternateAssignment = stores.isNotEmpty() && stores.size == transitionFields.size,
            noReturnBreakOrContinue = !controlExit,
            strongNonVolatileTransitionField = strongTransition,
            transitionFieldNotWritten = transitionField != null && !fieldWrite,
            verifierProofAccepted = verifierAccepted,
        )
        generationState.context.log {
            "ARC rooted projection candidate ${function.fqNameForIrSerialization.asString()}::${cursor.name}: " +
                    "authorized=${eligibility.isAuthorized()}, rejected=${eligibility.rejectedRequirements()}, " +
                    "targetDepth=${targetPath.size}, reads=${reads.size}, stores=${stores.size}, " +
                    "allowedCalls=$observedAllowedCallSymbols, rejectedCalls=$rejectedCallSymbols, " +
                    "stackAnchor=$stackAllocationAnchor, parameterAnchor=$parameterAnchor, " +
                    "targetAncestorDepth=${targetAncestors.size}, cursorLoopDepth=${cursorDeclarationLoops.size}, " +
                    "anchorLoopDepth=${anchorDeclarationLoops.size}, cursorPathMatches=$cursorReentryPathMatches, " +
                    "anchorPathMatches=$anchorReentryPathMatches, cursorBeforeTarget=$cursorDeclaredBeforeTarget, " +
                    "anchorBeforeCursor=$anchorDeclaredBeforeCursor, cursorVisitedBeforeTarget=$cursorVisitedBeforeTarget, " +
                    "anchorVisitedBeforeCursor=$anchorVisitedBeforeCursor"
        }
        if (eligibility.isAuthorized()) {
            selectedVariables += cursor
            selectedReads += reads
            selectedFields += transitionFields
            selectedStores += stores.map { it.first }
        }
    }

    return ArcRootedProjectionLoopSelection(selectedVariables, selectedReads, selectedFields, selectedStores)
}

private fun verifyRootedProjectionLoopProof(function: IrSimpleFunction, cursorVariable: IrVariable): Boolean {
    val anchor = ArcValue("root_anchor_${cursorVariable.name}")
    val cursor = ArcStorage("rooted_cursor_${cursorVariable.name}")
    val entry = ArcBlockId("entry")
    val loop = ArcBlockId("loop")
    val exit = ArcBlockId("exit")
    val proof = ArcFunctionPlan(
        functionName = "${function.fqNameForIrSerialization.asString()}#rooted-projection-${cursorVariable.name}",
        entry = entry,
        entryValues = emptyMap(),
        entryInitializedStorage = emptySet(),
        blocks = linkedMapOf(
            entry to ArcBasicBlock(
                entry,
                listOf(
                    ArcOperation.Define(anchor, ArcOwnership.Guaranteed),
                    ArcOperation.BeginRootedProjection(cursor, anchor),
                ),
                ArcTerminator.Jump(loop),
            ),
            loop to ArcBasicBlock(
                loop,
                listOf(ArcOperation.AdvanceRootedProjection(cursor, anchor)),
                ArcTerminator.Branch(loop, exit),
            ),
            exit to ArcBasicBlock(
                exit,
                listOf(ArcOperation.EndRootedProjection(cursor, anchor)),
                ArcTerminator.Return(),
            ),
        ),
    )
    return ArcOwnershipVerifier.verify(proof) === ArcOwnershipVerificationResult.Success
}

private fun IrExpression.unwrapCanonicalStrongFieldProjection(
    generationState: NativeGenerationState,
): IrGetField? = when (this) {
    is IrGetField -> this
    is IrBlock -> unwrapLoweredCheckNotNullInitializer(generationState) as? IrGetField
    else -> null
}

private fun IrExpression.unwrapDirectMutableRead(): IrGetValue? = when (this) {
    is IrGetValue -> this
    is IrTypeOperatorCall -> if (operator == IrTypeOperator.IMPLICIT_CAST) argument.unwrapDirectMutableRead() else null
    else -> null
}

private fun IrExpression.unwrapBorrowedMutableRead(generationState: NativeGenerationState): IrGetValue? =
    unwrapDirectMutableRead() ?: (this as? IrBlock)?.unwrapLoweredCheckNotNull(generationState)

/**
 * Match only the three-statement block emitted by BuiltinOperatorLowering.lowerCheckNotNull:
 * immutable temp initialization, `temp === null` throwing NPE, and the same temp as result.
 * No arbitrary block, user call, or additional statement is accepted.
 */
private fun IrBlock.unwrapLoweredCheckNotNull(generationState: NativeGenerationState): IrGetValue? {
    return unwrapLoweredCheckNotNullInitializer(generationState)?.unwrapDirectMutableRead()
}

private fun IrBlock.unwrapLoweredCheckNotNullInitializer(generationState: NativeGenerationState): IrExpression? {
    if (origin != null || statements.size != 3) return null
    val temporary = statements[0] as? IrVariable ?: return null
    if (temporary.origin != IrDeclarationOrigin.IR_TEMPORARY_VARIABLE || temporary.isVar) return null
    val initializer = temporary.initializer ?: return null
    val guard = statements[1] as? IrWhen ?: return null
    if (!guard.type.isUnit() || guard.branches.size != 1) return null
    val branch = guard.branches.single()
    val failure = branch.result as? IrCall ?: return null
    if (failure.symbol != generationState.context.ir.symbols.throwNullPointerException ||
        failure.getArgumentsWithIr().isNotEmpty()
    ) return null
    if (!branch.condition.isGeneratedNullCheckOf(generationState, temporary)) return null
    val result = statements[2] as? IrGetValue ?: return null
    if (result.symbol != temporary.symbol) return null
    return initializer
}

private fun IrExpression.isGeneratedNullCheckOf(
    generationState: NativeGenerationState,
    temporary: IrVariable,
): Boolean {
    val equality = this as? IrCall ?: return false
    if (equality.symbol != generationState.context.irBuiltIns.eqeqeqSymbol) return false
    val operands = equality.getArgumentsWithIr().map { it.second }
    if (operands.size != 2) return false
    return (operands[0].isReinterpretedGetOf(generationState, temporary) && operands[1] is IrConst<*> &&
            (operands[1] as IrConst<*>).value == null) ||
            (operands[1].isReinterpretedGetOf(generationState, temporary) && operands[0] is IrConst<*> &&
                    (operands[0] as IrConst<*>).value == null)
}

private fun IrExpression.isReinterpretedGetOf(
    generationState: NativeGenerationState,
    temporary: IrVariable,
): Boolean = when (this) {
    is IrGetValue -> symbol == temporary.symbol
    is IrTypeOperatorCall -> operator == IrTypeOperator.IMPLICIT_CAST &&
            argument.isReinterpretedGetOf(generationState, temporary)
    is IrCall -> symbol == generationState.context.ir.symbols.reinterpret &&
            getArgumentsWithIr().singleOrNull()?.second?.isReinterpretedGetOf(generationState, temporary) == true
    else -> false
}

private fun IrFunctionAccessExpression.isDirectKotlinCall(generationState: NativeGenerationState): Boolean = when (this) {
    is IrConstructorCall -> !symbol.owner.isExternal && !symbol.owner.constructedClass.isExternal
    is IrCall -> {
        val callee = symbol.owner
        !callee.isExternal && !callee.isBuiltInOperator && !callee.isArcSuspendLike() &&
                (!callee.isOverridable || superQualifierSymbol != null) &&
                symbol != generationState.context.ir.symbols.arcWeakReferenceLoad &&
                symbol != generationState.context.ir.symbols.arcUnownedReferenceLoad
    }
    else -> false
}

/**
 * Select only a lowered suspend adapter's exact direct tail call. Codegen passes the enclosing
 * function's object-result slot to this expression and records ownership only after the invoke's
 * normal successor, so exceptional cleanup remains unchanged. Calls inside a state-machine,
 * try/catch, or another suspend boundary deliberately remain outside this first slice.
 */
private fun selectVerifiedCoroutineResultSlotForwardingCalls(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
): Set<IrCall> {
    if (generationState.context.memoryModel != MemoryModel.ARC ||
        !generationState.context.config.optimizationsEnabled ||
        generationState.context.shouldContainDebugInfo() ||
        !function.isArcSuspendLike() || function.isExternal ||
        !function.returnType.binaryTypeIsReference() || function.returnType.isUnit() ||
        function.returnType.isNothing()
    ) return emptySet()
    val body = function.body as? IrBlockBody ?: return emptySet()
    val selected = Collections.newSetFromMap(IdentityHashMap<IrCall, Boolean>())
    var forbiddenBoundaryDepth = 0

    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) = Unit

        override fun visitTry(aTry: IrTry) {
            forbiddenBoundaryDepth++
            aTry.acceptChildrenVoid(this)
            forbiddenBoundaryDepth--
        }

        override fun visitSuspendableExpression(expression: IrSuspendableExpression) {
            forbiddenBoundaryDepth++
            expression.acceptChildrenVoid(this)
            forbiddenBoundaryDepth--
        }

        override fun visitSuspensionPoint(expression: IrSuspensionPoint) {
            forbiddenBoundaryDepth++
            expression.acceptChildrenVoid(this)
            forbiddenBoundaryDepth--
        }

        override fun visitReturn(expression: IrReturn) {
            val call = expression.value.unwrapExactCoroutineTailCall()
            if (forbiddenBoundaryDepth == 0 && expression.returnTargetSymbol == function.symbol && call != null &&
                call.isExactDirectLoweredSuspendAdapterCall(generationState) &&
                (call.symbol.owner as? IrSimpleFunction)?.allNormalReturnsInitializeArcResultSlot() == true
            ) {
                selected += call
                generationState.context.log {
                    "ARC coroutine result-slot forwarding ${function.fqNameForIrSerialization.asString()} -> " +
                            call.symbol.owner.fqNameForIrSerialization.asString()
                }
            }
            expression.acceptChildrenVoid(this)
        }
    })
    return selected
}

/**
 * First real lowered-coroutine spill slice: one owned direct producer initializes one immutable
 * spill, whose sole read is the function's final return. The abstract coroutine plan is bound back
 * to the exact IR call/variable/return identities consumed by codegen.
 */
private fun selectVerifiedCoroutineSpillMove(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
    lifetimes: Map<IrElement, Lifetime>,
): ArcCoroutineSpillMovePlan? {
    if (generationState.context.memoryModel != MemoryModel.ARC ||
        !generationState.context.config.optimizationsEnabled ||
        generationState.context.shouldContainDebugInfo() || !function.isArcSuspendLike() ||
        function.isExternal || !function.returnType.binaryTypeIsReference() ||
        function.returnType.isUnit() || function.returnType.isNothing()
    ) return null
    val body = function.body as? IrBlockBody ?: return null
    if (body.statements.size != 2) return null
    val spill = body.statements[0] as? IrVariable ?: return null
    val producer = spill.initializer as? IrCall ?: return null
    val returned = body.statements[1] as? IrReturn ?: return null
    val returnedRead = returned.value.unwrapExactArcCoroutineSpillRead() ?: return null
    if (spill.isVar || !spill.type.binaryTypeIsReference() || returned.returnTargetSymbol != function.symbol ||
        returnedRead.symbol != spill.symbol || spill.hasAnnotation(KonanFqNames.arcWeak) ||
        spill.hasAnnotation(KonanFqNames.arcUnowned)
    ) return null

    val callee = producer.symbol.owner as? IrSimpleFunction ?: return null
    val directOwnedProducer = callee.isReal && !callee.isExternal && !callee.isBuiltInOperator &&
            !callee.isTypedIntrinsic && !callee.isObjCBridgeBased() && !callee.isArcSuspendLike() &&
            (!callee.isOverridable || producer.superQualifierSymbol != null) &&
            callee.returnType.binaryTypeIsReference() && !callee.returnType.isUnit() &&
            !callee.returnType.isNothing() &&
            classifyArcProducedReference(isPermanent = false, lifetime = lifetimes[producer]) == ArcOwnership.Owned &&
            producer.symbol != generationState.context.ir.symbols.arcWeakReferenceLoad &&
            producer.symbol != generationState.context.ir.symbols.arcUnownedReferenceLoad
    if (!directOwnedProducer) return null

    var exactReads = 0
    var forbiddenBoundary = false
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) {
            forbiddenBoundary = true
        }

        override fun visitTry(aTry: IrTry) {
            forbiddenBoundary = true
        }

        override fun visitSuspendableExpression(expression: IrSuspendableExpression) {
            forbiddenBoundary = true
        }

        override fun visitSuspensionPoint(expression: IrSuspensionPoint) {
            forbiddenBoundary = true
        }

        override fun visitGetValue(expression: IrGetValue) {
            if (expression.symbol == spill.symbol) exactReads++
            expression.acceptChildrenVoid(this)
        }
    })
    if (forbiddenBoundary || exactReads != 1) return null

    val spillSlot = ArcCoroutineSlot("${function.name}.${spill.name}")
    val resultSlot = ArcCoroutineSlot("${function.name}.return")
    val ownership = ArcCoroutineOwnershipAnalysis.select(
        ArcCoroutineOwnershipCandidate(
            transferKind = ArcCoroutineTransferKind.MoveOwnedToResult,
            spillSlot = spillSlot,
            resultSlot = resultSlot,
            arcEnabled = true,
            optimizationsEnabled = true,
            debugInfoDisabled = true,
            loweredCoroutineFunction = true,
            exactDirectKotlinCall = true,
            nonExternalCall = true,
            nonVirtualCall = true,
            ownedReferenceResult = true,
            exactSingleSpillSlot = true,
            producerInitializesSpillOnNormalEdge = true,
            producerLeavesSpillUninitializedOnExceptionalEdge = true,
            noSuspensionBoundary = true,
            noTryBoundary = true,
            noForeignCall = true,
            linearUnambiguousPath = true,
            noAliasOrEscape = true,
            exactResultSlotIdentity = true,
            spillLastUseAtMove = true,
        )
    ) ?: return null
    if (ownership.reduction != ArcCoroutineOwnershipReduction(updateStackRefs = 0, updateReturnRefs = 1)) return null
    return ArcCoroutineSpillMovePlan(function, producer, spill, returned, ownership)
}

/**
 * Select only the stdlib atomic-reference value getter's exact tail call to the ARC-owned runtime
 * ABI. The runtime locks, retains +1, and initializes the supplied result slot before returning.
 */
private fun resolveCanonicalLockedReadPlans(
    generationState: NativeGenerationState,
    module: org.jetbrains.kotlin.ir.declarations.IrModuleFragment,
): List<ArcLockedReadCanonicalPlan> {
    if (generationState.context.memoryModel != MemoryModel.ARC ||
        !generationState.context.config.optimizationsEnabled ||
        generationState.context.shouldContainDebugInfo()
    ) return emptyList()
    val expectedClasses = setOf(
        "kotlin.native.concurrent.FreezableAtomicReference",
        "kotlin.concurrent.AtomicReference",
    )
    val stdlibLibrary = generationState.context.stdlibModule.konanLibrary ?: return emptyList()
    val candidates = mutableListOf<IrClass>()
    module.files.forEach { file ->
        file.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.konanLibrary === stdlibLibrary &&
                    declaration.fqNameForIrSerialization.asString() in expectedClasses &&
                    declaration.symbol.signature != null
                ) candidates += declaration
                declaration.acceptChildrenVoid(this)
            }
        })
    }
    return candidates.mapNotNull { ownerClass ->
        val functions = mutableListOf<IrSimpleFunction>()
        ownerClass.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) = Unit

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                functions += declaration
                declaration.acceptChildrenVoid(this)
            }
        })
        val getter = functions.singleOrNull {
            it.konanLibrary === stdlibLibrary && it.isReal && !it.isExternal &&
                    it.correspondingPropertySymbol?.owner?.name?.asString() == "value" &&
                    it.valueParameters.isEmpty() && it.dispatchReceiverParameter != null &&
                    it.returnType.binaryTypeIsReference()
        } ?: return@mapNotNull null
        val runtimeGetter = functions.singleOrNull {
            it.konanLibrary === stdlibLibrary && it.isReal && it.isExternal &&
                    it.visibility == DescriptorVisibilities.PRIVATE && it.name.asString() == "getImpl" &&
                    !it.isTypedIntrinsic && it.valueParameters.isEmpty() &&
                    it.dispatchReceiverParameter != null && it.returnType.binaryTypeIsReference() &&
                    it.getAnnotationArgumentValue<String>(KonanFqNames.gcUnsafeCall, "callee") ==
                    "Kotlin_AtomicReference_get"
        } ?: return@mapNotNull null
        val getterSignature = getter.symbol.signature ?: getter.symbol.privateSignature ?: return@mapNotNull null
        val runtimeSignature = runtimeGetter.symbol.signature ?: runtimeGetter.symbol.privateSignature ?: return@mapNotNull null
        val ownerClassSignature = ownerClass.symbol.signature ?: return@mapNotNull null
        val body = getter.body as? IrBlockBody ?: return@mapNotNull null
        val returned = body.statements.singleOrNull() as? IrReturn ?: return@mapNotNull null
        if (returned.returnTargetSymbol != getter.symbol) return@mapNotNull null
        val tailCall = returned.value.unwrapExactLockedReadTailCall() ?: return@mapNotNull null
        if (tailCall.symbol != runtimeGetter.symbol || tailCall.extensionReceiver != null ||
            tailCall.valueArgumentsCount != 0 ||
            (tailCall.dispatchReceiver as? IrGetValue)?.symbol != getter.dispatchReceiverParameter?.symbol
        ) return@mapNotNull null
        ArcLockedReadCanonicalPlan(
            ownerClass, getter, runtimeGetter, tailCall,
            getterSignature, runtimeSignature, ownerClassSignature, stdlibLibrary,
        )
    }
}

/**
 * Selects references whose lifetime is guaranteed by an immutable compiler-owned process root.
 *
 * Kotlin enum entries are ordinary heap objects, not permanent headers. The generated final
 * `$VALUES` global nevertheless owns every entry until process teardown. A load from the exact
 * generated getter can therefore stay +0 until a strong store independently acquires its +1.
 *
 * Keep this deliberately narrow. Any changed declaration, initializer, write set, getter body,
 * enum index, library identity, debug mode, or ARC diagnostics request falls back to the ordinary
 * owned-result ABI.
 */
private fun selectVerifiedRootedGlobalProjections(
    generationState: NativeGenerationState,
    module: org.jetbrains.kotlin.ir.declarations.IrModuleFragment,
): Map<IrCall, ArcRootedGlobalProjectionPlan> {
    val context = generationState.context
    if (context.memoryModel != MemoryModel.ARC || !context.config.optimizationsEnabled ||
        context.shouldContainDebugInfo() || context.config.arcDiagnosticsEnabled
    ) return emptyMap()
    val stdlib = context.stdlibModule.konanLibrary ?: return emptyMap()
    val classes = mutableListOf<IrClass>()
    val allFieldWrites = Collections.synchronizedMap(IdentityHashMap<IrField, MutableSet<IrSetField>>())
    module.files.forEach { file ->
        file.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.konanLibrary === stdlib) classes += declaration
                declaration.acceptChildrenVoid(this)
            }

            override fun visitSetField(expression: IrSetField) {
                allFieldWrites.getOrPut(expression.symbol.owner) {
                    Collections.newSetFromMap(IdentityHashMap<IrSetField, Boolean>())
                } += expression
                expression.acceptChildrenVoid(this)
            }
        })
    }

    val enumClass = classes.singleOrNull {
        it.fqNameForIrSerialization.asString() == "kotlin.coroutines.intrinsics.CoroutineSingletons" &&
                it.kind == ClassKind.ENUM_CLASS && it.symbol.signature != null
    }
    val enumGetterPlan = enumClass?.let { owner ->
        val getter = owner.declarations.filterIsInstance<IrSimpleFunction>().singleOrNull {
            it.origin == DECLARATION_ORIGIN_ENUM && it.name.asString() == "\$getEnumAt" &&
                    it.dispatchReceiverParameter == null && it.extensionReceiverParameter == null &&
                    it.valueParameters.singleOrNull()?.type?.isInt() == true &&
                    it.returnType.getClass()?.symbol == owner.symbol && !it.isOverridable
        } ?: return@let null
        val returned = (getter.body as? IrBlockBody)?.statements?.singleOrNull() as? IrReturn
            ?: return@let null
        if (returned.returnTargetSymbol != getter.symbol) return@let null
        val arrayGet = returned.value as? IrCall ?: return@let null
        val exactArrayGet = context.ir.symbols.array.owner.functions.singleOrNull {
            it.name == KonanNameConventions.getWithoutBoundCheck && it.valueParameters.size == 1 &&
                    it.dispatchReceiverParameter?.type?.getClass()?.symbol == context.ir.symbols.array
        } ?: return@let null
        if (arrayGet.symbol.owner !== exactArrayGet ||
            arrayGet.type.getClass()?.symbol != owner.symbol || arrayGet.extensionReceiver != null ||
            (arrayGet.getValueArgument(0) as? IrGetValue)?.symbol != getter.valueParameters.single().symbol
        ) return@let null
        val rootRead = arrayGet.dispatchReceiver as? IrGetField ?: return@let null
        val root = rootRead.symbol.owner
        if (!root.isStatic || !root.isFinal || root.visibility != DescriptorVisibilities.PRIVATE ||
            root.parent !== owner || root.origin != DECLARATION_ORIGIN_ENUM ||
            root.name.asString() != "\$VALUES" || !root.hasAnnotation(KonanFqNames.sharedImmutable) ||
            root.initializer == null || root.type.getClass()?.symbol != context.ir.symbols.array ||
            (root.type as? IrSimpleType)?.arguments?.singleOrNull()?.typeOrNull?.getClass()?.symbol != owner.symbol
        ) return@let null
        val initializerWrites = Collections.newSetFromMap(IdentityHashMap<IrSetField, Boolean>())
        root.initializer!!.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitSetField(expression: IrSetField) {
                if (expression.symbol.owner === root) initializerWrites += expression
                expression.acceptChildrenVoid(this)
            }
        })
        // Enum lowering pre-publishes the freshly allocated array once before constructors run.
        // The enclosing global initializer stores that same array again; there must be no IR write
        // to the root outside this exact initializer and no second pre-publication write.
        if (initializerWrites.size != 1 || allFieldWrites[root].orEmpty() != initializerWrites) return@let null
        if (!hasOnlyVerifiedEnumRootUses(module, root, rootRead, exactArrayGet, context)) return@let null
        Triple(getter, root, owner)
    }

    if (enumGetterPlan == null) return emptyMap()
    val selected = Collections.synchronizedMap(
        IdentityHashMap<IrCall, ArcRootedGlobalProjectionPlan>()
    )
    module.files.forEach { file ->
        file.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                enumGetterPlan.let { (getter, root, _) ->
                    if (expression.symbol.owner === getter && expression.dispatchReceiver == null &&
                        expression.extensionReceiver == null && expression.valueArgumentsCount == 1
                    ) {
                        val getterId = (expression.getValueArgument(0) as? IrConst<*>)?.value as? Int
                        // Getter ids are alphabetical: RESUMED=1 and UNDECIDED=2. The suspended
                        // marker remains on the ordinary ABI until its separate getter is proven.
                        if (getterId == 1 || getterId == 2) {
                            selected[expression] = ArcRootedGlobalProjectionPlan(root, getter, getterId)
                        }
                    }
                }
                expression.acceptChildrenVoid(this)
            }
        })
    }
    context.log { "ARC rooted global projections: ${selected.size}" }
    return selected
}

private fun hasOnlyVerifiedEnumRootUses(
    module: org.jetbrains.kotlin.ir.declarations.IrModuleFragment,
    root: IrField,
    getterRootRead: IrGetField,
    exactArrayGet: IrSimpleFunction,
    context: org.jetbrains.kotlin.backend.konan.Context,
): Boolean {
    val parents = IdentityHashMap<IrElement, IrElement?>()
    val rootReads = Collections.newSetFromMap(IdentityHashMap<IrGetField, Boolean>())
    val valueReads = Collections.synchronizedMap(IdentityHashMap<IrVariable, MutableSet<IrGetValue>>())
    var parent: IrElement? = null
    module.files.forEach { file ->
        file.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                parents[element] = parent
                val previous = parent
                parent = element
                element.acceptChildrenVoid(this)
                parent = previous
            }

            override fun visitGetField(expression: IrGetField) {
                if (expression.symbol.owner === root) rootReads += expression
                visitElement(expression)
            }

            override fun visitGetValue(expression: IrGetValue) {
                (expression.symbol.owner as? IrVariable)?.let { variable ->
                    valueReads.getOrPut(variable) {
                        Collections.newSetFromMap(IdentityHashMap<IrGetValue, Boolean>())
                    } += expression
                }
                visitElement(expression)
            }
        })
    }

    fun IrGetValue.isExactArrayGetReceiver(): Boolean {
        val call = parents[this] as? IrCall ?: return false
        return call.symbol.owner === exactArrayGet && call.dispatchReceiver === this &&
                call.extensionReceiver == null && call.valueArgumentsCount == 1
    }

    return rootReads.all { read ->
        if (read === getterRootRead) return@all true
        when (val use = parents[read]) {
            is IrCall -> when {
                use.symbol.owner === exactArrayGet && use.dispatchReceiver === read -> true
                use.symbol == context.ir.symbols.valuesForEnum &&
                        use.getValueArgument(0) === read -> true
                use.symbol == context.ir.symbols.valueOfForEnum &&
                        use.getValueArgument(1) === read -> true
                else -> false
            }
            is IrVariable -> !use.isVar && use.initializer === read &&
                    valueReads[use].orEmpty().isNotEmpty() &&
                    valueReads[use].orEmpty().all { it.isExactArrayGetReceiver() }
            else -> false
        }
    }
}

/**
 * Select the exact stdlib `SafeContinuation.getOrThrow` ownership web. Both atomic reads produce
 * +1 into the mutable `result` slot. All other reads are bounded borrows of that slot, while the
 * `resultRef` field is rooted by the guaranteed dispatch receiver for the complete call.
 *
 * This deliberately keys on stdlib declaration identities and the complete two-read/one-reload
 * body shape. A changed stdlib implementation falls back to ordinary ARC barriers.
 */
private fun selectCanonicalSafeContinuationGetOrThrow(
    generationState: NativeGenerationState,
    module: org.jetbrains.kotlin.ir.declarations.IrModuleFragment,
    lockedReads: List<ArcLockedReadCanonicalPlan>,
): ArcSafeContinuationGetOrThrowPlan? {
    if (generationState.context.memoryModel != MemoryModel.ARC ||
        !generationState.context.config.optimizationsEnabled ||
        generationState.context.shouldContainDebugInfo() ||
        generationState.context.config.arcDiagnosticsEnabled
    ) return null
    val stdlib = generationState.context.stdlibModule.konanLibrary ?: return null
    val atomic = lockedReads.singleOrNull {
        it.ownerClass.fqNameForIrSerialization.asString() ==
                "kotlin.native.concurrent.FreezableAtomicReference" &&
                !it.getter.isOverridable && !it.runtimeGetter.isOverridable
    } ?: return null
    val safeClass = buildList<IrClass> {
        module.files.forEach { file ->
            file.acceptChildrenVoid(object : IrElementVisitorVoid {
                override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
                override fun visitClass(declaration: IrClass) {
                    if (declaration.konanLibrary === stdlib &&
                        declaration.fqNameForIrSerialization.asString() == "kotlin.coroutines.SafeContinuation"
                    ) add(declaration)
                    declaration.acceptChildrenVoid(this)
                }
            })
        }
    }.singleOrNull() ?: return null
    if (safeClass.symbol.signature == null) return null
    val function = safeClass.declarations.filterIsInstance<IrSimpleFunction>().singleOrNull {
        it.konanLibrary === stdlib && it.name.asString() == "getOrThrow" && it.isReal &&
                !it.isExternal && !it.isOverridable && !it.isArcSuspendLike() && it.valueParameters.isEmpty() &&
                it.dispatchReceiverParameter != null && it.extensionReceiverParameter == null &&
                it.visibility == DescriptorVisibilities.INTERNAL &&
                (it.symbol.signature ?: it.symbol.privateSignature) != null &&
                it.returnType.binaryTypeIsReference()
    } ?: return null
    val body = function.body as? IrBlockBody ?: return null
    val atomicCalls = mutableListOf<IrCall>()
    val variables = mutableListOf<IrVariable>()
    var unsupportedBoundary = false
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
        override fun visitFunction(declaration: IrFunction) { unsupportedBoundary = true }
        override fun visitSuspendableExpression(expression: IrSuspendableExpression) { unsupportedBoundary = true }
        override fun visitSuspensionPoint(expression: IrSuspensionPoint) { unsupportedBoundary = true }
        override fun visitVariable(declaration: IrVariable) {
            variables += declaration
            declaration.acceptChildrenVoid(this)
        }
        override fun visitCall(expression: IrCall) {
            if (expression.symbol == atomic.getter.symbol) atomicCalls += expression
            expression.acceptChildrenVoid(this)
        }
    })
    if (unsupportedBoundary || atomicCalls.size != 2) return null
    val result = variables.singleOrNull {
        it.parent === function && it.name.asString() == "result" && it.isVar &&
                it.type.binaryTypeIsReference() &&
                it.initializer.unwrapExactSafeContinuationLockedRead()?.symbol == atomic.getter.symbol &&
                !it.hasAnnotation(KonanFqNames.arcWeak) && !it.hasAnnotation(KonanFqNames.arcUnowned) &&
                !it.hasAnnotation(KonanFqNames.volatile)
    } ?: return null
    val assignments = Collections.newSetFromMap(IdentityHashMap<IrSetValue, Boolean>())
    val reads = Collections.newSetFromMap(IdentityHashMap<IrGetValue, Boolean>())
    val resultRefLoads = Collections.newSetFromMap(IdentityHashMap<IrGetField, Boolean>())
    var invalid = false
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
        override fun visitFunction(declaration: IrFunction) { invalid = true }
        override fun visitSetValue(expression: IrSetValue) {
            if (expression.symbol == result.symbol) {
                if (expression.value.unwrapExactSafeContinuationLockedRead()?.symbol != atomic.getter.symbol) {
                    invalid = true
                } else {
                    assignments += expression
                }
            }
            expression.acceptChildrenVoid(this)
        }
        override fun visitGetValue(expression: IrGetValue) {
            if (expression.symbol == result.symbol) {
                reads += expression
            }
            expression.acceptChildrenVoid(this)
        }
        override fun visitSetField(expression: IrSetField) {
            if (expression.symbol.owner.name.asString() == "resultRef" &&
                expression.symbol.owner.parent === safeClass
            ) invalid = true
            expression.acceptChildrenVoid(this)
        }
        override fun visitGetField(expression: IrGetField) {
            if (expression.symbol.owner.name.asString() == "resultRef" &&
                expression.symbol.owner.parent === safeClass
            ) resultRefLoads += expression
            expression.acceptChildrenVoid(this)
        }
    })
    if (invalid || assignments.size != 1 || reads.size != 5) return null
    val targetReturns = mutableListOf<IrReturn>()
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
        override fun visitFunction(declaration: IrFunction) = Unit
        override fun visitReturn(expression: IrReturn) {
            if (expression.returnTargetSymbol == function.symbol) targetReturns += expression
            expression.acceptChildrenVoid(this)
        }
    })
    val successReads = targetReturns.mapNotNull { it.value.exactSafeContinuationWhenSuccessRead(result) }
    val movedResultRead = successReads.singleOrNull() ?: return null
    val permittedReads = Collections.newSetFromMap(IdentityHashMap<IrGetValue, Boolean>())
    permittedReads += movedResultRead
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
        override fun visitFunction(declaration: IrFunction) = Unit
        override fun visitCall(expression: IrCall) {
            if (expression.symbol == generationState.context.irBuiltIns.eqeqeqSymbol) {
                expression.getArgumentsWithIr().mapNotNullTo(permittedReads) {
                    it.second.unwrapExactSafeContinuationResultRead(result)
                }
            }
            expression.acceptChildrenVoid(this)
        }
        override fun visitTypeOperator(expression: IrTypeOperatorCall) {
            if (expression.operator == IrTypeOperator.INSTANCEOF) {
                expression.argument.unwrapExactSafeContinuationResultRead(result)?.let { permittedReads += it }
            }
            expression.acceptChildrenVoid(this)
        }
        override fun visitGetField(expression: IrGetField) {
            val receiver = expression.receiver.unwrapExactSafeContinuationResultRead(result)
            if (receiver != null &&
                expression.symbol.owner.name.asString() == "exception" &&
                (expression.symbol.owner.parent as? IrClass)?.fqNameForIrSerialization?.asString() ==
                        "kotlin.Result.Failure"
            ) permittedReads += receiver
            expression.acceptChildrenVoid(this)
        }
    })
    // This proves that the owned local never escapes: every use is one bounded identity check,
    // type check, failure projection, or the unique success edge of the function return `when`.
    if (permittedReads.size != reads.size || reads.any { it !in permittedReads }) return null
    if (resultRefLoads.size != 3) return null
    resultRefLoads.forEach { field ->
        if (field.symbol.owner.parent !== safeClass || field.symbol.owner.name.asString() != "resultRef" ||
            field.symbol.owner.isStatic || field.symbol.owner.hasAnnotation(KonanFqNames.volatile) ||
            field.symbol.owner.hasAnnotation(KonanFqNames.arcWeak) ||
            field.symbol.owner.hasAnnotation(KonanFqNames.arcUnowned) ||
            field.symbol.owner.visibility != DescriptorVisibilities.PRIVATE ||
            (field.symbol.owner.symbol.signature ?: field.symbol.owner.symbol.privateSignature) == null ||
            !field.type.binaryTypeIsReference() ||
            (field.receiver as? IrGetValue)?.symbol != function.dispatchReceiverParameter?.symbol
        ) return null
    }
    val resultRefField = resultRefLoads.first().symbol
    if (resultRefLoads.any { it.symbol != resultRefField }) return null
    var currentWriter: IrFunction? = null
    var constructorWrites = 0
    var invalidWriter = false
    safeClass.acceptChildrenVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
        override fun visitFunction(declaration: IrFunction) {
            val previous = currentWriter
            currentWriter = declaration
            declaration.acceptChildrenVoid(this)
            currentWriter = previous
        }
        override fun visitSetField(expression: IrSetField) {
            if (expression.symbol == resultRefField) {
                val constructor = currentWriter as? IrConstructor
                val receiver = expression.receiver as? IrGetValue
                if (constructor == null || constructor.parent !== safeClass ||
                    receiver?.symbol != safeClass.thisReceiver?.symbol
                ) {
                    invalidWriter = true
                } else {
                    constructorWrites++
                }
            }
            expression.acceptChildrenVoid(this)
        }
    })
    if (invalidWriter || constructorWrites != 1) return null
    val resultRefReceiverCalls = mutableListOf<IrCall>()
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
        override fun visitFunction(declaration: IrFunction) = Unit
        override fun visitCall(expression: IrCall) {
            if (expression.dispatchReceiver.unwrapExactSafeContinuationResultRefLoad() in resultRefLoads) {
                resultRefReceiverCalls += expression
            }
            expression.acceptChildrenVoid(this)
        }
    })
    if (resultRefReceiverCalls.size != 3 ||
        resultRefReceiverCalls.count { it.symbol == atomic.getter.symbol } != 2 ||
        resultRefReceiverCalls.singleOrNull { it.symbol != atomic.getter.symbol }?.let {
            it.symbol.owner.parent === atomic.ownerClass && it.symbol.owner.name.asString() == "compareAndSet" &&
                    !it.symbol.owner.isExternal && !it.symbol.owner.isOverridable &&
                    it.symbol.owner.extensionReceiverParameter == null &&
                    (it.symbol.owner.symbol.signature ?: it.symbol.owner.symbol.privateSignature) != null &&
                    it.valueArgumentsCount == 2
        } != true ||
        atomicCalls.none { it === result.initializer.unwrapExactSafeContinuationLockedRead() } ||
        atomicCalls.none { call -> assignments.single().value.unwrapExactSafeContinuationLockedRead() === call }
    ) return null
    generationState.context.log {
        "ARC SafeContinuation.getOrThrow ownership web: borrowedReads=${reads.size}, " +
                "borrowedResultRefLoads=${resultRefLoads.size}"
    }
    return ArcSafeContinuationGetOrThrowPlan(function, result, assignments, reads, movedResultRead, resultRefLoads)
}

private fun IrExpression.exactSafeContinuationWhenSuccessRead(result: IrVariable): IrGetValue? = when (this) {
    is IrWhen -> branches.lastOrNull()?.takeIf { isElseBranch(it) }?.result
        ?.unwrapExactSafeContinuationResultRead(result)
    is IrBlock -> (statements.lastOrNull() as? IrExpression)?.exactSafeContinuationWhenSuccessRead(result)
    is IrTypeOperatorCall -> if (operator == IrTypeOperator.IMPLICIT_CAST) {
        argument.exactSafeContinuationWhenSuccessRead(result)
    } else null
    else -> null
}

private fun IrExpression?.unwrapExactSafeContinuationResultRead(result: IrVariable): IrGetValue? = when (this) {
    is IrGetValue -> takeIf { symbol == result.symbol }
    is IrTypeOperatorCall -> if (operator == IrTypeOperator.IMPLICIT_CAST) {
        argument.unwrapExactSafeContinuationResultRead(result)
    } else null
    else -> null
}

private fun IrExpression?.unwrapExactSafeContinuationLockedRead(): IrCall? = when (this) {
    is IrCall -> this
    is IrTypeOperatorCall -> if (operator == IrTypeOperator.IMPLICIT_CAST) {
        argument.unwrapExactSafeContinuationLockedRead()
    } else null
    else -> null
}

private fun IrExpression?.unwrapExactSafeContinuationResultRefLoad(): IrGetField? = when (this) {
    is IrGetField -> this
    is IrTypeOperatorCall -> if (operator == IrTypeOperator.IMPLICIT_CAST) {
        argument.unwrapExactSafeContinuationResultRefLoad()
    } else null
    else -> null
}

private fun IrExpression.unwrapExactLockedReadTailCall(): IrCall? = when (this) {
    is IrCall -> this
    is IrTypeOperatorCall -> if (operator == IrTypeOperator.IMPLICIT_CAST) {
        argument.unwrapExactLockedReadTailCall()
    } else {
        null
    }
    else -> null
}

/** Exact grammar shared with coroutine spill codegen: one local read plus implicit ABI casts only. */
internal fun IrExpression.unwrapExactArcCoroutineSpillRead(): IrGetValue? = when (this) {
    is IrGetValue -> this
    is IrTypeOperatorCall -> if (operator == IrTypeOperator.IMPLICIT_CAST) {
        argument.unwrapExactArcCoroutineSpillRead()
    } else {
        null
    }
    else -> null
}

private fun IrExpression.unwrapExactCoroutineTailCall(): IrCall? = when (this) {
    is IrCall -> this
    is IrReturn -> value.unwrapExactCoroutineTailCall()
    is IrTypeOperatorCall -> if (operator == IrTypeOperator.IMPLICIT_CAST) argument.unwrapExactCoroutineTailCall() else null
    is IrBlock -> if (origin == null && statements.size == 1) {
        (statements.single() as? IrExpression)?.unwrapExactCoroutineTailCall()
    } else {
        null
    }
    else -> null
}

private fun IrCall.isExactDirectLoweredSuspendAdapterCall(generationState: NativeGenerationState): Boolean {
    val callee = symbol.owner as? IrSimpleFunction ?: return false
    return callee.isArcSuspendLike() && !callee.isExternal && !callee.isBuiltInOperator &&
            (!callee.isOverridable || superQualifierSymbol != null) &&
            callee.returnType.binaryTypeIsReference() && !callee.returnType.isUnit() &&
            !callee.returnType.isNothing() && symbol != generationState.context.ir.symbols.arcWeakReferenceLoad &&
            symbol != generationState.context.ir.symbols.arcUnownedReferenceLoad
}

/**
 * Every ordinary return from this direct Kotlin callee writes an object through its result ABI.
 * Lowered state machines may express their final value through nested returnable blocks and leave
 * no `IrReturn` targeting the function itself; that zero-return case is vacuously safe because a
 * normal LLVM return is still emitted only through the object-result epilogue.
 */
private fun IrSimpleFunction.allNormalReturnsInitializeArcResultSlot(): Boolean {
    val body = body as? IrBlockBody ?: return false
    var invalidReturn = false
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) = Unit

        override fun visitReturn(expression: IrReturn) {
            if (expression.returnTargetSymbol == symbol) {
                val returnedValue = expression.value.unwrapNestedCoroutineReturnValue()
                if (!returnedValue.type.binaryTypeIsReference() || returnedValue.type.isNothing()) {
                    invalidReturn = true
                }
            }
            expression.acceptChildrenVoid(this)
        }
    })
    return !invalidReturn
}

private fun IrExpression.unwrapNestedCoroutineReturnValue(): IrExpression = when (this) {
    is IrReturn -> value.unwrapNestedCoroutineReturnValue()
    is IrTypeOperatorCall -> if (operator == IrTypeOperator.IMPLICIT_CAST) {
        argument.unwrapNestedCoroutineReturnValue()
    } else {
        this
    }
    is IrBlock -> if (origin == null && statements.size == 1) {
        (statements.single() as? IrExpression)?.unwrapNestedCoroutineReturnValue() ?: this
    } else {
        this
    }
    else -> this
}

/** Tie the existing +0 load authorization causally to contained-copy elimination. */
private fun verifyBorrowedReadProof(function: IrSimpleFunction, variable: IrVariable): Boolean {
    val owner = ArcValue("owner_${variable.name}")
    val copied = ArcValue("copied_${variable.name}")
    val entry = ArcBlockId("entry")
    val use = ArcBlockId("use")
    val proof = ArcFunctionPlan(
        functionName = "${function.fqNameForIrSerialization.asString()}#borrow-${variable.name}",
        entry = entry,
        entryValues = emptyMap(),
        entryInitializedStorage = emptySet(),
        blocks = linkedMapOf(
            entry to ArcBasicBlock(
                entry,
                listOf(
                    ArcOperation.Define(owner, ArcOwnership.Owned),
                    ArcOperation.Copy(owner, copied),
                ),
                ArcTerminator.Jump(use),
            ),
            use to ArcBasicBlock(
                use,
                listOf(
                    ArcOperation.Use(copied, ArcPlanLocation("guaranteed call argument")),
                    ArcOperation.Destroy(copied),
                    ArcOperation.Destroy(owner),
                ),
                ArcTerminator.Return(),
            ),
        ),
    )
    val optimized = ArcOwnershipOptimizer.optimizeVerified(proof)
    val copyEliminationVerified = optimized.metrics.containedOwnedCopiesEliminated == 1 &&
            optimized.plan.blocks.getValue(entry).operations ==
            listOf(ArcOperation.Define(owner, ArcOwnership.Owned)) &&
            optimized.plan.blocks.getValue(use).operations == listOf(
                ArcOperation.Use(owner, ArcPlanLocation("guaranteed call argument")),
                ArcOperation.Destroy(owner),
            ) && ArcOwnershipVerifier.verify(optimized.plan) === ArcOwnershipVerificationResult.Success
    if (!copyEliminationVerified) return false

    // The target invocation may unwind even though its argument-evaluation suffix is call-free.
    // Both exits end the +0 borrow before the owning frame slot is released.
    val borrowed = ArcValue("borrowed_${variable.name}")
    val invoke = ArcBlockId("invoke")
    val normal = ArcBlockId("normal")
    val unwind = ArcBlockId("unwind")
    val intervalProof = ArcFunctionPlan(
        functionName = "${function.fqNameForIrSerialization.asString()}#borrow-interval-${variable.name}",
        entry = entry,
        entryValues = emptyMap(),
        entryInitializedStorage = emptySet(),
        blocks = linkedMapOf(
            entry to ArcBasicBlock(
                entry,
                listOf(
                    ArcOperation.Define(owner, ArcOwnership.Owned),
                    ArcOperation.Borrow(owner, borrowed),
                ),
                ArcTerminator.Jump(invoke),
            ),
            invoke to ArcBasicBlock(
                invoke,
                listOf(ArcOperation.Use(borrowed, ArcPlanLocation("direct Kotlin call argument"))),
                ArcTerminator.Branch(normal, unwind),
            ),
            normal to ArcBasicBlock(
                normal,
                listOf(ArcOperation.EndBorrow(borrowed), ArcOperation.Destroy(owner)),
                ArcTerminator.Return(),
            ),
            unwind to ArcBasicBlock(
                unwind,
                listOf(ArcOperation.EndBorrow(borrowed), ArcOperation.Destroy(owner)),
                ArcTerminator.Throw,
            ),
        ),
    )
    return ArcOwnershipVerifier.verify(intervalProof) === ArcOwnershipVerificationResult.Success
}

private data class CuratedArcFunctionPlan(
    val plan: ArcFunctionPlan,
    val variableValues: Map<org.jetbrains.kotlin.ir.symbols.IrVariableSymbol, ArcValue>,
)

/**
 * Select the first production codegen slice only when a verified plan describes a non-immortal
 * call result forwarded through one or more immutable aliases. The IR restriction is
 * intentionally tighter than the planner restriction: no operation may occur between the call
 * and return, so forwarding into the caller's return slot cannot delay destruction on an
 * exceptional path.
 */
private fun selectOwnedResultForwarding(
    function: IrSimpleFunction,
    builtPlan: CuratedArcFunctionPlan,
    optimized: ArcOwnershipOptimizationResult,
): ArcOwnedResultForwarding? {
    // Multi-block plans currently feed verification and optimization metrics only. Keep the
    // codegen authorization boundary explicit even if the IR-shape checks below are broadened.
    if (builtPlan.plan.blocks.size != 1) return null
    val body = function.body as? org.jetbrains.kotlin.ir.expressions.IrBlockBody ?: return null
    val returnExpression = body.statements.lastOrNull() as? IrReturn ?: return null
    if (returnExpression.returnTargetSymbol != function.symbol) return null
    val returnedGet = returnExpression.value as? IrGetValue ?: return null
    val returned = returnedGet.symbol.owner as? IrVariable ?: return null

    val variables = body.statements.dropLast(1).map { it as? IrVariable ?: return null }
    if (variables.size < 2 || variables.last() !== returned || variables.any { it.isVar || !it.type.binaryTypeIsReference() }) {
        return null
    }

    for (index in variables.lastIndex downTo 1) {
        val alias = variables[index].initializer as? IrGetValue ?: return null
        if (alias.symbol.owner !== variables[index - 1]) return null
    }
    val producer = variables.first()
    if (producer.initializer !is IrCall) return null

    val producerValue = builtPlan.variableValues[producer.symbol] ?: return null
    val returnedValue = builtPlan.variableValues[returned.symbol] ?: return null
    val verifiedBlock = builtPlan.plan.blocks[builtPlan.plan.entry] ?: return null
    if (verifiedBlock.terminator != ArcTerminator.Return(returnedValue)) return null
    val producerDefinition = verifiedBlock.operations
        .filterIsInstance<ArcOperation.Define>()
        .singleOrNull { it.result == producerValue }
        ?: return null
    if (producerDefinition.ownership == ArcOwnership.Immortal) return null

    // optimizeVerified() has already re-verified this result. Requiring its entry block here ties
    // codegen authorization to the verified optimizer output even when escape analysis classified
    // the original call result as Guaranteed in its temporary anonymous slot.
    optimized.plan.blocks[optimized.plan.entry] ?: return null

    return ArcOwnedResultForwarding(producer, returned)
}

private fun ArcFunctionPlan.classificationCounts(): ArcOwnershipClassificationCounts {
    var result = ArcOwnershipClassificationCounts()
    fun record(ownership: ArcOwnership) {
        result = when (ownership) {
            ArcOwnership.Owned -> result.copy(owned = result.owned + 1)
            ArcOwnership.Guaranteed -> result.copy(guaranteed = result.guaranteed + 1)
            ArcOwnership.Immortal -> result.copy(immortal = result.immortal + 1)
        }
    }
    entryValues.values.forEach(::record)
    blocks.values.forEach { block ->
        block.operations.forEach { operation ->
            if (operation is ArcOperation.Define) record(operation.ownership)
        }
    }
    return result
}

private fun reportFailure(
    generationState: NativeGenerationState,
    file: IrFile,
    function: IrSimpleFunction,
    failure: ArcOwnershipVerificationResult.Failure,
): Nothing = generationState.reportCompilationError(
    buildString {
        appendLine("ARC ownership invariant violation in curated post-escape plan")
        append(failure.render())
    },
    file,
    function,
)

/**
 * Seal the first curated real-CFG shape after all merge-block cleanups have been planned.
 *
 * Branches are deliberately restricted to strong stores and must initialize the same storage.
 * This is stronger than the verifier requires when storage was initialized on entry, but it
 * prevents an unsupported source-level `if` from becoming a path-state compiler error.
 */
internal fun buildCuratedArcUnitIfPlan(
    functionName: String,
    entryValues: Map<ArcValue, ArcOwnership>,
    entryInitializedStorage: Set<ArcStorage>,
    entryOperations: List<ArcOperation>,
    trueOperations: List<ArcOperation>,
    falseOperations: List<ArcOperation>,
    mergeOperations: List<ArcOperation>,
    returnedValue: ArcValue?,
): ArcFunctionPlan? {
    fun branchStorage(operations: List<ArcOperation>): Set<ArcStorage>? {
        if (operations.isEmpty() || operations.any { it !is ArcOperation.StrongStore }) return null
        return operations.mapTo(linkedSetOf()) { (it as ArcOperation.StrongStore).storage }
    }

    val trueStorage = branchStorage(trueOperations) ?: return null
    val falseStorage = branchStorage(falseOperations) ?: return null
    if (trueStorage != falseStorage) return null

    val entry = ArcBlockId("entry")
    val trueBlock = ArcBlockId("when0_then")
    val falseBlock = ArcBlockId("when0_else")
    val mergeBlock = ArcBlockId("when0_merge")
    val blocks = linkedMapOf(
        entry to ArcBasicBlock(entry, entryOperations, ArcTerminator.Branch(trueBlock, falseBlock)),
        trueBlock to ArcBasicBlock(trueBlock, trueOperations, ArcTerminator.Jump(mergeBlock)),
        falseBlock to ArcBasicBlock(falseBlock, falseOperations, ArcTerminator.Jump(mergeBlock)),
        mergeBlock to ArcBasicBlock(mergeBlock, mergeOperations, ArcTerminator.Return(returnedValue)),
    )
    return ArcFunctionPlan(functionName, entry, entryValues, entryInitializedStorage, blocks)
}

private class CuratedArcOwnershipPlanBuilder(
    private val function: IrSimpleFunction,
    private val lifetimes: Map<IrElement, Lifetime>,
) {
    private val entry = ArcBlockId("entry")
    private val entryOperations = mutableListOf<ArcOperation>()
    private var currentOperations = entryOperations
    private var conditional: CuratedUnitIf? = null
    private val values = mutableMapOf<org.jetbrains.kotlin.ir.symbols.IrValueSymbol, ArcValue>()
    private val ownership = mutableMapOf<ArcValue, ArcOwnership>()
    private val entryValues = linkedMapOf<ArcValue, ArcOwnership>()
    private val initializedStorage = linkedSetOf<ArcStorage>()
    private val localDefinitionOrder = mutableListOf<ArcValue>()
    private var nextValue = 0
    private var returnedValue: ArcValue? = null

    private data class CuratedUnitIf(
        val trueOperations: List<ArcOperation>,
        val falseOperations: List<ArcOperation>,
        val mergeOperations: MutableList<ArcOperation>,
    )

    fun build(): CuratedArcFunctionPlan? {
        if (function.isArcSuspendLike() || function.body !is org.jetbrains.kotlin.ir.expressions.IrBlockBody) return null

        function.allParameters.forEach { parameter ->
            if (parameter.type.binaryTypeIsReference()) {
                val value = ArcValue("arg${values.size}_${parameter.name}")
                values[parameter.symbol] = value
                ownership[value] = ArcOwnership.Guaranteed
                entryValues[value] = ArcOwnership.Guaranteed
            }
        }

        val statements = (function.body as org.jetbrains.kotlin.ir.expressions.IrBlockBody).statements
        for ((index, statement) in statements.withIndex()) {
            val isLast = index == statements.lastIndex
            when (statement) {
                is IrVariable -> if (!planVariable(statement)) return null
                is IrSetField -> if (!planStrongStore(statement)) return null
                is IrWhen -> if (!planUnitIf(statement)) return null
                is IrReturn -> {
                    if (!isLast || !planReturn(statement)) return null
                }
                is IrExpression -> return null
                else -> return null
            }
        }

        localDefinitionOrder.asReversed().forEach { value ->
            if (value != returnedValue && ownership[value] == ArcOwnership.Owned) {
                currentOperations += ArcOperation.Destroy(value, ArcPlanLocation("lexical scope exit"))
            }
        }

        val functionName = function.fqNameForIrSerialization.asString()
        val plannedIf = conditional
        val plan = if (plannedIf == null) {
            ArcFunctionPlan(
                functionName = functionName,
                entry = entry,
                entryValues = entryValues,
                entryInitializedStorage = initializedStorage,
                blocks = mapOf(entry to ArcBasicBlock(entry, entryOperations, ArcTerminator.Return(returnedValue))),
            )
        } else {
            buildCuratedArcUnitIfPlan(
                functionName,
                entryValues,
                initializedStorage,
                entryOperations,
                plannedIf.trueOperations,
                plannedIf.falseOperations,
                plannedIf.mergeOperations,
                returnedValue,
            ) ?: return null
        }
        return CuratedArcFunctionPlan(
            plan,
            values.mapNotNull { (symbol, value) ->
                (symbol as? org.jetbrains.kotlin.ir.symbols.IrVariableSymbol)?.let { it to value }
            }.toMap(),
        )
    }

    private fun planVariable(variable: IrVariable): Boolean {
        if (variable.isVar) return false
        val initializer = variable.initializer ?: return !variable.type.binaryTypeIsReference()
        if (!variable.type.binaryTypeIsReference()) return true

        val result = newValue(variable.name.asString())
        val location = location(variable, "val ${variable.name}")
        val resultOwnership = when (initializer) {
            is IrConst<*>, is IrGetObjectValue -> {
                classifyArcProducedReference(isPermanent = true, lifetime = lifetimes[initializer]).also {
                    currentOperations += ArcOperation.Define(result, it, location)
                }
            }
            is IrGetValue -> {
                val source = values[initializer.symbol] ?: return false
                currentOperations += ArcOperation.Copy(source, result, location)
                if (ownership[source] == ArcOwnership.Immortal) ArcOwnership.Immortal else ArcOwnership.Owned
            }
            is IrGetField -> {
                val storage = storage(initializer)
                initializedStorage += storage
                currentOperations += ArcOperation.StrongLoad(storage, result, location)
                ArcOwnership.Owned
            }
            is IrConstructorCall, is IrCall -> {
                val requiresHeapAllocation = initializer is IrConstructorCall &&
                        initializer.symbol.owner.constructedClass.hasArcDeinitInHierarchy()
                classifyArcProducedReference(
                    isPermanent = false,
                    lifetime = lifetimes[initializer],
                    requiresHeapAllocation = requiresHeapAllocation,
                ).also {
                    currentOperations += ArcOperation.Define(result, it, location)
                }
            }
            else -> return false
        }
        values[variable.symbol] = result
        ownership[result] = resultOwnership
        localDefinitionOrder += result
        return true
    }

    private fun planStrongStore(
        setField: IrSetField,
        destination: MutableList<ArcOperation> = currentOperations,
        requireReferenceStore: Boolean = false,
    ): Boolean {
        if (requireReferenceStore && !setField.symbol.owner.type.binaryTypeIsReference()) return false
        if (!setField.symbol.owner.type.binaryTypeIsReference()) return true
        val valueExpression = setField.value as? IrGetValue ?: return false
        val value = values[valueExpression.symbol] ?: return false
        destination += ArcOperation.StrongStore(storage(setField), value, location(setField, "strong field store"))
        return true
    }

    private fun planUnitIf(expression: IrWhen): Boolean {
        if (conditional != null || expression.origin !== IrStatementOrigin.IF || !expression.type.isUnit()) return false
        if (expression.branches.size != 2) return false
        val trueBranch = expression.branches[0]
        val falseBranch = expression.branches[1]
        if (isElseBranch(trueBranch) || !isElseBranch(falseBranch)) return false
        if (!isSupportedCondition(trueBranch.condition)) return false

        val trueStores = branchStores(trueBranch.result) ?: return false
        val falseStores = branchStores(falseBranch.result) ?: return false
        // ArcStorage is currently keyed by field declaration, not receiver identity. Requiring
        // the same declaration in both arms is sufficient for this metrics-only CFG slice; no
        // multi-block plan may authorize codegen until receiver-specific storage is modeled.
        val trueStorage = trueStores.mapTo(linkedSetOf()) { storage(it) }
        val falseStorage = falseStores.mapTo(linkedSetOf()) { storage(it) }
        if (trueStorage != falseStorage) return false

        val trueOperations = mutableListOf<ArcOperation>()
        val falseOperations = mutableListOf<ArcOperation>()
        if (!trueStores.all { planStrongStore(it, trueOperations, requireReferenceStore = true) }) return false
        if (!falseStores.all { planStrongStore(it, falseOperations, requireReferenceStore = true) }) return false

        val mergeOperations = mutableListOf<ArcOperation>()
        conditional = CuratedUnitIf(trueOperations, falseOperations, mergeOperations)
        currentOperations = mergeOperations
        return true
    }

    private fun isSupportedCondition(condition: IrExpression): Boolean {
        if (!condition.type.isBoolean()) return false
        return condition is IrGetValue || (condition is IrConst<*> && condition.value is Boolean)
    }

    private fun branchStores(result: IrExpression): List<IrSetField>? {
        if (!result.type.isUnit()) return null
        val stores = when (result) {
            is IrSetField -> listOf(result)
            is IrBlock -> result.statements.map { it as? IrSetField ?: return null }
            else -> return null
        }
        if (stores.isEmpty()) return null
        return stores.takeIf { candidates ->
            candidates.all { store ->
                store.type.isUnit() &&
                        store.symbol.owner.type.binaryTypeIsReference() &&
                        (store.receiver == null || store.receiver is IrGetValue) &&
                        store.value is IrGetValue
            }
        }
    }

    private fun planReturn(expression: IrReturn): Boolean {
        if (function.returnType.isUnit()) return expression.value.type.isUnit()
        if (!function.returnType.binaryTypeIsReference()) return true
        val getValue = expression.value as? IrGetValue ?: return false
        val value = values[getValue.symbol] ?: return false
        returnedValue = if (ownership[value] == ArcOwnership.Guaranteed) {
            // Parameters and receivers are +0 at the Kotlin ABI boundary, while object results
            // are +1. Model that transfer explicitly so returning a borrowed parameter (for
            // example intArrayOf's vararg array) has a balanced ownership plan.
            val ownedResult = newValue("return")
            currentOperations += ArcOperation.Copy(value, ownedResult, location(expression, "owned return copy"))
            ownership[ownedResult] = ArcOwnership.Owned
            localDefinitionOrder += ownedResult
            ownedResult
        } else {
            value
        }
        return true
    }

    private fun newValue(hint: String): ArcValue = ArcValue("${nextValue++}_$hint")

    private fun storage(getField: IrGetField): ArcStorage = ArcStorage(getField.symbol.owner.fqNameForIrSerialization.asString())

    private fun storage(setField: IrSetField): ArcStorage = ArcStorage(setField.symbol.owner.fqNameForIrSerialization.asString())

    private fun location(element: IrElement, description: String) = ArcPlanLocation(description, element.startOffset)

    private fun IrClass.hasArcDeinitInHierarchy(): Boolean =
        generateSequence(this) { it.getSuperClassNotAny() }.any { irClass ->
            irClass.declarations.any {
                it is IrSimpleFunction && it.annotations.hasAnnotation(KonanFqNames.arcDeinit)
            }
        }
}
