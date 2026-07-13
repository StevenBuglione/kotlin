/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.backend.konan.getBoxFunction
import org.jetbrains.kotlin.backend.konan.ir.konanLibrary
import org.jetbrains.kotlin.backend.konan.isFinalBinary
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrReturnableBlock
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrSuspendableExpression
import org.jetbrains.kotlin.ir.expressions.IrSuspensionPoint
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.isOverridable
import org.jetbrains.kotlin.ir.util.isReal
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.util.Collections
import java.util.IdentityHashMap

internal data class ArcSafeContinuationSROAIRBindings<T : Any>(
    val function: T,
    val safeClass: T,
    val local: T,
    val allocation: T,
    val constructor: T,
    /** Borrowed receiver passed to intercepted(); distinct from the owned intercepted result. */
    val interceptedReceiver: T,
    val interceptedCall: T,
    val delegateField: T,
    val resultRefField: T,
    val resumeWith: T,
    val resumeCall: T,
    val resumeReceiver: T,
    val resumeResultArgument: T,
    val resumeResultParameter: T,
    val resumeValueProducer: T,
    val resultCompanionGetter: T,
    val resultBoxIntrinsic: T,
    val resultConstructor: T,
    val structuralUnitCalls: List<T>,
    val getOrThrow: T,
    val getOrThrowCall: T,
    val getOrThrowReceiver: T,
    /** Declarations and body nodes that authenticate the stdlib state-machine implementation. */
    val contractBindings: List<T>,
)

internal fun <T : Any> ArcSafeContinuationSROAIRBindings<T>.exactIdentityInventory(): List<T> = listOf(
    function, safeClass, local, allocation, constructor, interceptedReceiver, interceptedCall, delegateField,
    resultRefField, resumeWith, resumeCall, resumeReceiver, resumeResultArgument,
    resumeResultParameter, resumeValueProducer, resultCompanionGetter, resultBoxIntrinsic,
    resultConstructor, getOrThrow, getOrThrowCall, getOrThrowReceiver,
) + structuralUnitCalls + contractBindings

internal data class ArcSafeContinuationSROAIRShape(
    val exactStdlibDeclarations: Boolean,
    val exactFunctionContainer: Boolean,
    val exactLocalConstructorInitialization: Boolean,
    val immutableLocal: Boolean,
    val wholeFunctionNoLocalWrites: Boolean,
    val exactSecondaryConstructorEffectInventory: Boolean,
    val exactPrivateStrongFields: Boolean,
    val exactDirectResumeReceiver: Boolean,
    val exactDirectGetReceiver: Boolean,
    val exactlyTwoLocalReads: Boolean,
    val completeTransitiveUseWalk: Boolean,
    val singleLinearRegion: Boolean,
    val resumePrecedesAndDominatesGet: Boolean,
    val getPostDominatesResume: Boolean,
    val oneSynchronousResume: Boolean,
    val undecidedInitialStateProven: Boolean,
    val suspendedAndDelegateBranchesUnreachable: Boolean,
    val noNestedFunctionCapture: Boolean,
    val noThreadOrWorkerBoundary: Boolean,
    val noForeignOrExternalBoundary: Boolean,
    val noTryRegion: Boolean,
    val noSuspensionPoint: Boolean,
    val noBackedgeOrStateTransition: Boolean,
    val noCoroutineSuspendedReturn: Boolean,
    val noUnsupportedAncestorContext: Boolean,
    val noConditionalBranch: Boolean,
    val noEarlyControlTransfer: Boolean,
    val exactStructuralCallInventory: Boolean,
    val exactResumeArgumentDataflow: Boolean,
    val exactSafeContinuationMethodContract: Boolean,
    val exactUnitStructuralEdges: Boolean,
    val exactBranchOrderAndDataflow: Boolean,
    val ordinaryHeapOwnershipPreserved: Boolean,
)

private fun ArcSafeContinuationSROAIRShape.isExact(): Boolean =
    exactStdlibDeclarations && exactFunctionContainer && exactLocalConstructorInitialization && immutableLocal &&
            wholeFunctionNoLocalWrites && exactSecondaryConstructorEffectInventory && exactPrivateStrongFields &&
            exactDirectResumeReceiver && exactDirectGetReceiver && exactlyTwoLocalReads &&
            completeTransitiveUseWalk && singleLinearRegion && resumePrecedesAndDominatesGet &&
            getPostDominatesResume && oneSynchronousResume && undecidedInitialStateProven &&
            suspendedAndDelegateBranchesUnreachable && noNestedFunctionCapture &&
            noThreadOrWorkerBoundary && noForeignOrExternalBoundary && noTryRegion &&
            noSuspensionPoint && noBackedgeOrStateTransition && noCoroutineSuspendedReturn &&
            noUnsupportedAncestorContext && noConditionalBranch && noEarlyControlTransfer && exactStructuralCallInventory &&
            exactResumeArgumentDataflow && exactSafeContinuationMethodContract &&
            exactUnitStructuralEdges && exactBranchOrderAndDataflow && ordinaryHeapOwnershipPreserved

internal data class ArcSafeContinuationSROAIRSelection<T : Any>(
    val bindings: ArcSafeContinuationSROAIRBindings<T>,
    val semantic: ArcSafeContinuationSROASelection<T>,
)

internal enum class ArcSafeContinuationSROAIRRejectionReason {
    InvalidStructuralProof,
    DuplicateStructuralIdentity,
    SemanticAnalysisRejected,
}

internal data class ArcSafeContinuationSROAIRSelectorResult<T : Any>(
    val selection: ArcSafeContinuationSROAIRSelection<T>?,
    val rejection: ArcSafeContinuationSROAIRRejectionReason?,
    val semanticRejection: ArcSafeContinuationSROARejectionReason? = null,
)

/** Identity-token adapter used by both the real lowered-IR walk and exhaustive unit tests. */
internal fun <T : Any> adaptVerifiedSafeContinuationSROAIR(
    bindings: ArcSafeContinuationSROAIRBindings<T>,
    mode: ArcSafeContinuationSROAMode,
    shape: ArcSafeContinuationSROAIRShape,
): ArcSafeContinuationSROAIRSelectorResult<T> {
    if (!shape.isExact()) return ArcSafeContinuationSROAIRSelectorResult(
        null, ArcSafeContinuationSROAIRRejectionReason.InvalidStructuralProof,
    )
    if (!bindings.exactIdentityInventory().haveUniqueIRIdentity()) {
        return ArcSafeContinuationSROAIRSelectorResult(
            null, ArcSafeContinuationSROAIRRejectionReason.DuplicateStructuralIdentity,
        )
    }
    val analyzed = ArcSafeContinuationSROAAnalysis.select(
        ArcSafeContinuationSROACandidate(
            functionBinding = bindings.function,
            allocationBinding = bindings.allocation,
            localBinding = bindings.local,
            constructorBinding = bindings.constructor,
            delegateBinding = bindings.interceptedReceiver,
            resumeBinding = bindings.resumeCall,
            resumeResultBinding = bindings.resumeResultArgument,
            getOrThrowBinding = bindings.getOrThrowCall,
            scalarDelegateSlotBinding = bindings.delegateField,
            scalarStateSlotBinding = bindings.resultRefField,
            scalarResultSlotBinding = bindings.resumeResultParameter,
            interceptedBinding = bindings.interceptedCall,
            resumeValueProducerBinding = bindings.resumeValueProducer,
            resultCompanionBinding = bindings.resultCompanionGetter,
            resultBoxBinding = bindings.resultBoxIntrinsic,
            resultConstructorBinding = bindings.resultConstructor,
            unitInstanceBindings = bindings.structuralUnitCalls,
            exactIdentityBindings = bindings.exactIdentityInventory(),
            mode = mode,
            identity = ArcSafeContinuationSROAIdentityProof(
                exactStdlibLibrary = true,
                exactSafeContinuationClass = true,
                exactConstructor = true,
                exactResumeWith = true,
                exactGetOrThrow = true,
                exactDelegateField = true,
                exactResultRefField = true,
                declarationsHaveStableSignatures = true,
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
                delegateIsOrdinaryHeapReference = true,
                resultMayBeOrdinaryHeapReference = true,
                interceptedCallProducesOwnedPlusOne = true,
                delegateScalarConsumesInterceptedPlusOne = true,
                resultBoxProducesOwnedPlusOne = true,
                resultScalarConsumesBoxPlusOne = true,
                undecidedStateIsImmortal = true,
                freezableAtomicIdentityDoesNotEscape = true,
                storesRetainBeforeReplacing = true,
                normalResultIsMovedExactlyOnce = true,
                successOnlyResultProven = true,
                constructorFailureCleanupDestroysInitializedSlots = true,
                noImmortalInferenceForDelegateOrResult = true,
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
        ),
    )
    val selection = analyzed.selection ?: return ArcSafeContinuationSROAIRSelectorResult(
        null,
        ArcSafeContinuationSROAIRRejectionReason.SemanticAnalysisRejected,
        analyzed.rejection,
    )
    return ArcSafeContinuationSROAIRSelectorResult(
        ArcSafeContinuationSROAIRSelection(bindings, selection),
        null,
    )
}

internal data class ArcSafeContinuationSROAKotlinIRSelection(
    val function: IrFunction,
    val sites: List<ArcSafeContinuationSROAIRSelection<IrElement>>,
)

/**
 * Find the deliberately narrow post-lowering shape:
 *
 * `val safe = SafeContinuation(delegate); safe.resumeWith(value); safe.getOrThrow()`
 *
 * The three operations must occupy one linear IR container. The complete use walk rejects even a
 * harmless-looking extra alias; this is the same fail-closed rule Swift uses before promoting an
 * alloc_box or captured reference. The exact stdlib constructor identity authenticates its
 * `UNDECIDED` initial state, so the first resume cannot take the suspended/delegate branch.
 */
internal fun selectVerifiedSynchronousSafeContinuationSROA(
    generationState: NativeGenerationState,
    module: IrModuleFragment,
): List<ArcSafeContinuationSROAKotlinIRSelection> {
    val context = generationState.context
    val config = context.config
    val mode = ArcSafeContinuationSROAMode(
        arcEnabled = context.memoryModel == MemoryModel.ARC,
        linuxX64 = config.target == KonanTarget.LINUX_X64,
        finalBinary = config.isFinalBinary,
        optimizationsEnabled = config.optimizationsEnabled,
        debugInfoDisabled = !context.shouldContainAnyDebugInfo(),
        diagnosticsDisabled = !config.arcDiagnosticsEnabled,
        sanitizerDisabled = config.sanitizer == null && !config.undefinedBehaviorSanitizer,
        coverageDisabled = !generationState.coverage.enabled,
        nonSuspendFunction = true,
        exactLoweredSuspendFunction = false,
        nonExternalFunction = true,
    )
    if (!mode.isSelectableProductionMode()) return emptyList()
    val stdlib = context.stdlibModule.konanLibrary ?: return emptyList()
    val safeClass = collectClasses(module).singleOrNull {
        it.konanLibrary === stdlib &&
                it.fqNameForIrSerialization.asString() == "kotlin.coroutines.SafeContinuation"
    } ?: return emptyList()
    if (safeClass.symbol.signature == null) return emptyList()
    val delegateField = safeClass.declarations.filterIsInstance<IrField>().singleOrNull {
        it.name.asString() == "delegate" && it.visibility == DescriptorVisibilities.PRIVATE &&
                it.isFinal && !it.isStatic && it.type.binaryTypeIsReference() &&
                (it.symbol.signature ?: it.symbol.privateSignature) != null
    } ?: return emptyList()
    val resultRefField = safeClass.declarations.filterIsInstance<IrField>().singleOrNull {
        it.name.asString() == "resultRef" && it.visibility == DescriptorVisibilities.PRIVATE &&
                !it.isStatic && it.type.binaryTypeIsReference() &&
                (it.symbol.signature ?: it.symbol.privateSignature) != null
    } ?: return emptyList()
    val resumeWith = safeClass.declarations.filterIsInstance<IrSimpleFunction>().singleOrNull {
        it.konanLibrary === stdlib && it.name.asString() == "resumeWith" && it.isReal &&
                !it.isExternal && !it.isOverridable && !it.isSuspend &&
                it.dispatchReceiverParameter != null && it.extensionReceiverParameter == null &&
                it.valueParameters.singleOrNull()?.type?.binaryTypeIsReference() == true &&
                it.returnType.isUnit() && (it.symbol.signature ?: it.symbol.privateSignature) != null
    } ?: return emptyList()
    val getOrThrow = safeClass.declarations.filterIsInstance<IrSimpleFunction>().singleOrNull {
        it.konanLibrary === stdlib && it.name.asString() == "getOrThrow" && it.isReal &&
                !it.isExternal && !it.isOverridable && !it.isSuspend &&
                it.dispatchReceiverParameter != null && it.extensionReceiverParameter == null &&
                it.valueParameters.isEmpty() && it.returnType.binaryTypeIsReference() &&
                (it.symbol.signature ?: it.symbol.privateSignature) != null
    } ?: return emptyList()
    val contract = authenticateExactSafeContinuationMethodContract(
        safeClass, delegateField, resultRefField, resumeWith, getOrThrow, context,
    ) ?: return emptyList()

    val selected = IdentityHashMap<IrFunction, MutableList<ArcSafeContinuationSROAIRSelection<IrElement>>>()
    module.files.forEach { file ->
        file.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitFunction(declaration: IrFunction) {
                val exactLoweredSuspend = (declaration as? IrSimpleFunction)
                    ?.isExactSROALoweredSuspendContainer() == true
                if (!declaration.isExternal &&
                    ((declaration as? IrSimpleFunction)?.isSuspend != true || exactLoweredSuspend)
                ) {
                    selectInFunction(
                        declaration, safeClass, delegateField, resultRefField, resumeWith,
                        getOrThrow, stdlib, contract,
                        mode.copy(
                            nonSuspendFunction = !exactLoweredSuspend,
                            exactLoweredSuspendFunction = exactLoweredSuspend,
                        ),
                        context,
                    ).takeIf { it.isNotEmpty() }?.let { selected[declaration] = it.toMutableList() }
                }
                // Each function owns an independent escape/use census.
            }
        })
    }
    context.log { "ARC synchronous SafeContinuation SROA planned (not emitted): ${selected.values.sumOf { it.size }}" }
    return selected.map { (function, sites) -> ArcSafeContinuationSROAKotlinIRSelection(function, sites) }
}

private fun selectInFunction(
    function: IrFunction,
    safeClass: IrClass,
    delegateField: IrField,
    resultRefField: IrField,
    resumeWith: IrSimpleFunction,
    getOrThrow: IrSimpleFunction,
    stdlib: Any,
    contract: ExactSafeContinuationMethodContract,
    mode: ArcSafeContinuationSROAMode,
    context: org.jetbrains.kotlin.backend.konan.Context,
): List<ArcSafeContinuationSROAIRSelection<IrElement>> {
    val body = function.body ?: return emptyList()
    val variables = mutableListOf<IrVariable>()
    val ancestry = IdentityHashMap<IrElement, List<IrElement>>()
    val stack = mutableListOf<IrElement>()
    var containsNestedFunction = false
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            ancestry[element] = stack.toList()
            stack += element
            element.acceptChildrenVoid(this)
            stack.removeAt(stack.lastIndex)
        }

        override fun visitFunction(declaration: IrFunction) {
            containsNestedFunction = true
        }

        override fun visitVariable(declaration: IrVariable) {
            ancestry[declaration] = stack.toList()
            if (declaration.parent === function) variables += declaration
            stack += declaration
            declaration.acceptChildrenVoid(this)
            stack.removeAt(stack.lastIndex)
        }
    })
    if (containsNestedFunction) return emptyList()

    return variables.mapNotNull { local ->
        if (local.isVar) return@mapNotNull null
        val allocation = local.initializer as? IrConstructorCall ?: return@mapNotNull null
        val constructor = allocation.symbol.owner
        if (constructor.parent !== safeClass || constructor.konanLibrary !== stdlib ||
            constructor.isExternal || constructor.valueParameters.size != 1 ||
            constructor.dispatchReceiverParameter != null || constructor.extensionReceiverParameter != null ||
            (constructor.symbol.signature ?: constructor.symbol.privateSignature) == null ||
            allocation.valueArgumentsCount != 1 || allocation.typeArgumentsCount != 1
        ) return@mapNotNull null
        val secondaryContract = authenticateExactUndecidedConstructor(constructor, safeClass, context)
            ?: return@mapNotNull null
        val delegate = allocation.getValueArgument(0) ?: return@mapNotNull null
        val intercepted = delegate as? IrCall ?: return@mapNotNull null
        if (intercepted.symbol.owner.fqNameForIrSerialization.asString() !=
            "kotlin.coroutines.intrinsics.intercepted" || intercepted.dispatchReceiver == null ||
            intercepted.extensionReceiver != null || intercepted.valueArgumentsCount != 0 ||
            intercepted.symbol.owner.konanLibrary !== stdlib
        ) return@mapNotNull null
        val interceptedReceiver = intercepted.dispatchReceiver ?: return@mapNotNull null

        val reads = mutableListOf<IrGetValue>()
        val calls = mutableListOf<IrCall>()
        var localWrite = false
        body.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
            override fun visitFunction(declaration: IrFunction) = Unit
            override fun visitGetValue(expression: IrGetValue) {
                if (expression.symbol == local.symbol) reads += expression
                expression.acceptChildrenVoid(this)
            }
            override fun visitSetValue(expression: IrSetValue) {
                if (expression.symbol == local.symbol) localWrite = true
                expression.acceptChildrenVoid(this)
            }
        })
        if (localWrite) return@mapNotNull null
        body.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
            override fun visitFunction(declaration: IrFunction) = Unit
            override fun visitCall(expression: IrCall) {
                if (expression.dispatchReceiver.unwrapSROALocal()?.symbol == local.symbol) calls += expression
                expression.acceptChildrenVoid(this)
            }
        })
        val resumeCall = calls.singleOrNull { it.symbol.owner === resumeWith } ?: return@mapNotNull null
        val getCall = calls.singleOrNull { it.symbol.owner === getOrThrow } ?: return@mapNotNull null
        if (calls.size != 2 || reads.size != 2 || resumeCall.valueArgumentsCount != 1 ||
            getCall.valueArgumentsCount != 0
        ) return@mapNotNull null
        val resumeReceiver = resumeCall.dispatchReceiver.unwrapSROALocal() ?: return@mapNotNull null
        val getReceiver = getCall.dispatchReceiver.unwrapSROALocal() ?: return@mapNotNull null
        if (reads.none { it === resumeReceiver } || reads.none { it === getReceiver }) return@mapNotNull null
        val resumeResult = resumeCall.getValueArgument(0) ?: return@mapNotNull null
        val linear = proveLinearRegion(local, resumeCall, getCall, ancestry) ?: return@mapNotNull null
        if (hasUnsupportedAncestorContext(local, resumeCall, getCall, ancestry)) return@mapNotNull null
        val structuralCalls = linear.authenticateExactStructuralCalls(
            allocation, resumeCall, getCall, intercepted, resumeResult, ancestry, context,
        ) ?: return@mapNotNull null

        val bindings = ArcSafeContinuationSROAIRBindings<IrElement>(
            function, safeClass, local, allocation, constructor, interceptedReceiver, intercepted, delegateField,
            resultRefField, resumeWith, resumeCall, resumeReceiver, resumeResult,
            resumeWith.valueParameters.single(), structuralCalls.resumeValueProducer,
            structuralCalls.resultCompanionGetter, structuralCalls.resultBoxIntrinsic,
            structuralCalls.resultConstructor, structuralCalls.unitInstances,
            getOrThrow, getCall, getReceiver, contract.identityBindings + secondaryContract.identityBindings,
        )
        adaptVerifiedSafeContinuationSROAIR(
            bindings,
            mode,
            ArcSafeContinuationSROAIRShape(
                exactStdlibDeclarations = true,
                exactFunctionContainer = true,
                exactLocalConstructorInitialization = true,
                immutableLocal = true,
                wholeFunctionNoLocalWrites = true,
                exactSecondaryConstructorEffectInventory = true,
                exactPrivateStrongFields = true,
                exactDirectResumeReceiver = true,
                exactDirectGetReceiver = true,
                exactlyTwoLocalReads = true,
                completeTransitiveUseWalk = true,
                singleLinearRegion = true,
                resumePrecedesAndDominatesGet = true,
                getPostDominatesResume = true,
                oneSynchronousResume = true,
                undecidedInitialStateProven = true,
                suspendedAndDelegateBranchesUnreachable = true,
                noNestedFunctionCapture = true,
                noThreadOrWorkerBoundary = true,
                noForeignOrExternalBoundary = true,
                noTryRegion = true,
                noSuspensionPoint = true,
                noBackedgeOrStateTransition = true,
                noCoroutineSuspendedReturn = true,
                noUnsupportedAncestorContext = true,
                noConditionalBranch = true,
                noEarlyControlTransfer = true,
                exactStructuralCallInventory = true,
                exactResumeArgumentDataflow = true,
                exactSafeContinuationMethodContract = true,
                exactUnitStructuralEdges = true,
                exactBranchOrderAndDataflow = true,
                ordinaryHeapOwnershipPreserved = true,
            ),
        ).selection
    }
}

private data class ExactSafeContinuationMethodContract(
    /** Exact declarations and critical body nodes consumed by any future emitter. */
    val identityBindings: List<IrElement>,
)

/**
 * Authenticate the Kotlin 1.9.10 stdlib implementation whose two allocations SROA removes.
 * Declaration names alone are insufficient: this fingerprints the primary constructor's writes,
 * the complete write census for both fields, and the atomic operations in resume/getOrThrow.
 */
private fun authenticateExactSafeContinuationMethodContract(
    safeClass: IrClass,
    delegateField: IrField,
    resultRefField: IrField,
    resumeWith: IrSimpleFunction,
    getOrThrow: IrSimpleFunction,
    context: org.jetbrains.kotlin.backend.konan.Context,
): ExactSafeContinuationMethodContract? {
    val primary = safeClass.constructors.singleOrNull { it.isPrimary } ?: return null
    if (primary.valueParameters.size != 2 || primary.body !is IrBlockBody) return null
    val delegateParameter = primary.valueParameters[0]
    val initialResultParameter = primary.valueParameters[1]
    val primaryWrites = mutableListOf<IrSetField>()
    val primaryDelegations = mutableListOf<IrDelegatingConstructorCall>()
    val primaryConstructors = mutableListOf<IrConstructorCall>()
    val primaryCalls = mutableListOf<IrCall>()
    var invalidPrimaryEffect = false
    primary.body!!.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
        override fun visitFunction(declaration: IrFunction) = Unit
        override fun visitWhen(expression: IrWhen) { invalidPrimaryEffect = true }
        override fun visitLoop(loop: IrLoop) { invalidPrimaryEffect = true }
        override fun visitTry(aTry: IrTry) { invalidPrimaryEffect = true }
        override fun visitThrow(expression: IrThrow) { invalidPrimaryEffect = true }
        override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall) {
            primaryDelegations += expression
            expression.acceptChildrenVoid(this)
        }
        override fun visitConstructorCall(expression: IrConstructorCall) {
            primaryConstructors += expression
            expression.acceptChildrenVoid(this)
        }
        override fun visitCall(expression: IrCall) {
            primaryCalls += expression
            expression.acceptChildrenVoid(this)
        }
        override fun visitSetField(expression: IrSetField) {
            primaryWrites += expression
            expression.acceptChildrenVoid(this)
        }
    })
    val delegateInitialization = primaryWrites.singleOrNull { it.symbol.owner === delegateField }
        ?: return null
    val resultRefInitialization = primaryWrites.singleOrNull { it.symbol.owner === resultRefField }
        ?: return null
    if (primaryWrites.size != 2 ||
        (delegateInitialization.receiver as? IrGetValue)?.symbol != safeClass.thisReceiver?.symbol ||
        (delegateInitialization.value as? IrGetValue)?.symbol != delegateParameter.symbol ||
        (resultRefInitialization.receiver as? IrGetValue)?.symbol != safeClass.thisReceiver?.symbol
    ) return null

    val atomicInitialization = resultRefInitialization.value as? IrConstructorCall ?: return null
    val atomicClass = resultRefField.type.getClass() ?: return null
    if (atomicClass.fqNameForIrSerialization.asString() !=
        "kotlin.native.concurrent.FreezableAtomicReference" ||
        atomicClass.konanLibrary !== context.stdlibModule.konanLibrary
    ) return null
    val atomicConstructor = atomicClass.constructors.singleOrNull { it.isPrimary } ?: return null
    if (atomicInitialization.symbol.owner !== atomicConstructor ||
        atomicInitialization.valueArgumentsCount != 1 ||
        (atomicInitialization.getValueArgument(0) as? IrGetValue)?.symbol != initialResultParameter.symbol
    ) return null
    val anyPrimary = context.irBuiltIns.anyClass.owner.constructors.singleOrNull { it.isPrimary }
        ?: return null
    val structuralReturn = (primary.body as IrBlockBody).statements.lastOrNull() as? IrReturn ?: return null
    val structuralUnit = structuralReturn.value as? IrCall ?: return null
    if (invalidPrimaryEffect || (primary.body as IrBlockBody).statements.size != 4 ||
        primaryDelegations.singleOrNull()?.symbol?.owner !== anyPrimary ||
        primaryConstructors.singleOrNull() !== atomicInitialization ||
        primaryCalls.singleOrNull() !== structuralUnit ||
        structuralUnit.symbol != context.ir.symbols.theUnitInstance ||
        structuralReturn.returnTargetSymbol != primary.symbol
    ) return null

    val allDelegateWrites = mutableListOf<IrSetField>()
    val allResultRefWrites = mutableListOf<IrSetField>()
    safeClass.acceptChildrenVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
        override fun visitSetField(expression: IrSetField) {
            when (expression.symbol.owner) {
                delegateField -> allDelegateWrites += expression
                resultRefField -> allResultRefWrites += expression
            }
            expression.acceptChildrenVoid(this)
        }
    })
    if (allDelegateWrites.singleOrNull() !== delegateInitialization ||
        allResultRefWrites.singleOrNull() !== resultRefInitialization
    ) return null

    val atomicGetter = atomicClass.declarations.filterIsInstance<IrSimpleFunction>().singleOrNull {
        it.correspondingPropertySymbol?.owner?.name?.asString() == "value" &&
            it.valueParameters.isEmpty() && it.dispatchReceiverParameter != null &&
            !it.isExternal && !it.isOverridable
    } ?: return null
    val atomicCompareAndSet = atomicClass.declarations.filterIsInstance<IrSimpleFunction>().singleOrNull {
        it.name.asString() == "compareAndSet" && it.valueParameters.size == 2 &&
            it.dispatchReceiverParameter != null && !it.isExternal && !it.isOverridable
    } ?: return null
    /*
     * Trust boundary: these are compiler-owned declarations from the exact stdlib KLIB loaded by
     * this compilation. Their stable signatures define the FreezableAtomicReference ABI contract;
     * this SROA does not attempt to infer atomic semantics from a same-named user implementation.
     * A stdlib ABI/signature change therefore fails selection and uses ordinary code generation.
     */
    if (atomicClass.symbol.signature == null ||
        (atomicConstructor.symbol.signature ?: atomicConstructor.symbol.privateSignature) == null ||
        (atomicGetter.symbol.signature ?: atomicGetter.symbol.privateSignature) == null ||
        (atomicCompareAndSet.symbol.signature ?: atomicCompareAndSet.symbol.privateSignature) == null ||
        atomicConstructor.konanLibrary !== context.stdlibModule.konanLibrary ||
        atomicGetter.konanLibrary !== context.stdlibModule.konanLibrary ||
        atomicCompareAndSet.konanLibrary !== context.stdlibModule.konanLibrary
    ) return null

    val resumeFingerprint = fingerprintSafeContinuationMethod(
        resumeWith, delegateField, resultRefField, atomicGetter, atomicCompareAndSet,
        expectedResultRefLoads = 3, expectedAtomicGets = 1, expectedAtomicCas = 2,
        expectedEnumIds = listOf(1, 2, 2), expectedUnitReturns = 3,
        expectedVariables = 1, expectedAssignments = 0, expectedTargetReturns = 3, expectedBranches = 5,
        requireLoop = true, requireDelegateResume = true, requireFailureProjection = false, context = context,
    ) ?: return null
    val getFingerprint = fingerprintSafeContinuationMethod(
        getOrThrow, delegateField, resultRefField, atomicGetter, atomicCompareAndSet,
        expectedResultRefLoads = 3, expectedAtomicGets = 2, expectedAtomicCas = 1,
        expectedEnumIds = listOf(1, 2, 2), expectedUnitReturns = 0,
        expectedVariables = 1, expectedAssignments = 1, expectedTargetReturns = 2, expectedBranches = 5,
        requireLoop = false, requireDelegateResume = false, requireFailureProjection = true, context = context,
    ) ?: return null

    return ExactSafeContinuationMethodContract(listOf(
        primary, atomicClass, atomicConstructor, atomicGetter, atomicCompareAndSet,
        primaryDelegations.single(), delegateInitialization, resultRefInitialization,
        atomicInitialization, structuralReturn, structuralUnit,
    ) + resumeFingerprint + getFingerprint)
}

private fun fingerprintSafeContinuationMethod(
    function: IrSimpleFunction,
    delegateField: IrField,
    resultRefField: IrField,
    atomicGetter: IrSimpleFunction,
    atomicCompareAndSet: IrSimpleFunction,
    expectedResultRefLoads: Int,
    expectedAtomicGets: Int,
    expectedAtomicCas: Int,
    expectedEnumIds: List<Int>,
    expectedUnitReturns: Int,
    expectedVariables: Int,
    expectedAssignments: Int,
    expectedTargetReturns: Int,
    expectedBranches: Int,
    requireLoop: Boolean,
    requireDelegateResume: Boolean,
    requireFailureProjection: Boolean,
    context: org.jetbrains.kotlin.backend.konan.Context,
): List<IrElement>? {
    val body = function.body as? IrBlockBody ?: return null
    val resultRefLoads = mutableListOf<IrGetField>()
    val delegateLoads = mutableListOf<IrGetField>()
    val atomicCalls = mutableListOf<IrCall>()
    val unitCalls = mutableListOf<IrCall>()
    val unitReturns = mutableListOf<IrCall>()
    val enumIds = mutableListOf<Int>()
    val delegateResumeCalls = mutableListOf<IrCall>()
    val failureProjections = mutableListOf<IrGetField>()
    val allFieldLoads = mutableListOf<IrGetField>()
    val typeOperators = mutableListOf<IrTypeOperatorCall>()
    val throws = mutableListOf<IrThrow>()
    val variables = mutableListOf<IrVariable>()
    val assignments = mutableListOf<IrSetValue>()
    val targetReturns = mutableListOf<IrReturn>()
    val identityCalls = mutableListOf<IrCall>()
    val allCalls = mutableListOf<IrCall>()
    val constructorCalls = mutableListOf<IrConstructorCall>()
    val enumCalls = mutableListOf<IrCall>()
    val suspendedCalls = mutableListOf<IrCall>()
    val reinterpretCalls = mutableListOf<IrCall>()
    val enumInitializerCalls = mutableListOf<IrCall>()
    val orderedBranches = mutableListOf<IrBranch>()
    val ancestry = IdentityHashMap<IrElement, List<IrElement>>()
    val ancestryStack = mutableListOf<IrElement>()
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            ancestry[element] = ancestryStack.toList()
            ancestryStack += element
            element.acceptChildrenVoid(this)
            ancestryStack.removeAt(ancestryStack.lastIndex)
        }
    })
    var loops = 0
    var failureTests = 0
    var invalid = false
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
        override fun visitFunction(declaration: IrFunction) { invalid = true }
        override fun visitTry(aTry: IrTry) { invalid = true }
        override fun visitBreak(jump: IrBreak) { invalid = true }
        override fun visitContinue(jump: IrContinue) { invalid = true }
        override fun visitSuspendableExpression(expression: IrSuspendableExpression) { invalid = true }
        override fun visitSuspensionPoint(expression: IrSuspensionPoint) { invalid = true }
        override fun visitLoop(loop: IrLoop) { loops++; loop.acceptChildrenVoid(this) }
        override fun visitWhen(expression: IrWhen) {
            orderedBranches += expression.branches
            expression.acceptChildrenVoid(this)
        }
        override fun visitVariable(declaration: IrVariable) {
            variables += declaration
            declaration.acceptChildrenVoid(this)
        }
        override fun visitSetValue(expression: IrSetValue) {
            assignments += expression
            expression.acceptChildrenVoid(this)
        }
        override fun visitReturn(expression: IrReturn) {
            if (expression.returnTargetSymbol == function.symbol) targetReturns += expression
            (expression.value as? IrCall)?.takeIf {
                it.symbol == context.ir.symbols.theUnitInstance && expression.returnTargetSymbol == function.symbol
            }?.let { unitReturns += it }
            expression.acceptChildrenVoid(this)
        }
        override fun visitSetField(expression: IrSetField) {
            invalid = true
            expression.acceptChildrenVoid(this)
        }
        override fun visitGetField(expression: IrGetField) {
            allFieldLoads += expression
            when (expression.symbol.owner) {
                resultRefField -> resultRefLoads += expression
                delegateField -> delegateLoads += expression
            }
            if (expression.symbol.owner.name.asString() == "exception" &&
                (expression.symbol.owner.parent as? IrClass)?.fqNameForIrSerialization?.asString() == "kotlin.Result.Failure"
            ) failureProjections += expression
            expression.acceptChildrenVoid(this)
        }
        override fun visitTypeOperator(expression: IrTypeOperatorCall) {
            typeOperators += expression
            if (expression.operator == IrTypeOperator.INSTANCEOF &&
                expression.typeOperand.getClass()?.fqNameForIrSerialization?.asString() == "kotlin.Result.Failure"
            ) failureTests++
            expression.acceptChildrenVoid(this)
        }
        override fun visitThrow(expression: IrThrow) {
            throws += expression
            expression.acceptChildrenVoid(this)
        }
        override fun visitCall(expression: IrCall) {
            allCalls += expression
            when (expression.symbol.owner) {
                atomicGetter, atomicCompareAndSet -> atomicCalls += expression
            }
            if (expression.symbol == context.irBuiltIns.eqeqeqSymbol) identityCalls += expression
            if (expression.symbol == context.ir.symbols.theUnitInstance) unitCalls += expression
            if (expression.symbol.owner.name.asString() == "\$getEnumAt") {
                enumCalls += expression
                ((expression.getValueArgument(0) as? IrConst<*>)?.value as? Int)?.let { enumIds += it }
            }
            when (expression.symbol.owner.fqNameForIrSerialization.asString()) {
                "kotlin.coroutines.intrinsics.<get-COROUTINE_SUSPENDED>" -> suspendedCalls += expression
                "kotlin.native.internal.reinterpret" -> reinterpretCalls += expression
            }
            if (expression.symbol.owner.name.asString() == "\$init_global" &&
                (expression.symbol.owner.parent as? IrClass)?.fqNameForIrSerialization?.asString() ==
                    "kotlin.coroutines.intrinsics.CoroutineSingletons"
            ) enumInitializerCalls += expression
            val delegateLoad = expression.dispatchReceiver as? IrGetField
            if (delegateLoad?.symbol?.owner === delegateField &&
                expression.symbol.owner.name.asString() == "resumeWith"
            ) delegateResumeCalls += expression
            expression.acceptChildrenVoid(this)
        }
        override fun visitConstructorCall(expression: IrConstructorCall) {
            constructorCalls += expression
            expression.acceptChildrenVoid(this)
        }
    })
    val expectedLoops = if (requireLoop) 1 else 0
    val expectedDelegateCalls = if (requireDelegateResume) 1 else 0
    val expectedFailureNodes = if (requireFailureProjection) 1 else 0
    if (invalid || loops != expectedLoops ||
        resultRefLoads.size != expectedResultRefLoads ||
        atomicCalls.count { it.symbol.owner === atomicGetter } != expectedAtomicGets ||
        atomicCalls.count { it.symbol.owner === atomicCompareAndSet } != expectedAtomicCas ||
        atomicCalls.size != expectedAtomicGets + expectedAtomicCas || enumIds.sorted() != expectedEnumIds ||
        unitCalls.size != expectedUnitReturns || unitReturns.size != expectedUnitReturns ||
        unitCalls.indices.any { unitCalls[it] !== unitReturns[it] } ||
        delegateResumeCalls.size != expectedDelegateCalls ||
        delegateLoads.size != expectedDelegateCalls ||
        failureTests != expectedFailureNodes ||
        failureProjections.size != expectedFailureNodes ||
        variables.size != expectedVariables || assignments.size != expectedAssignments ||
        targetReturns.size != expectedTargetReturns || orderedBranches.size != expectedBranches ||
        throws.size != 1 || typeOperators.size != expectedFailureNodes ||
        allFieldLoads.size != resultRefLoads.size + delegateLoads.size + failureProjections.size
    ) return null
    if (resultRefLoads.any { load ->
            (load.receiver as? IrGetValue)?.symbol != function.dispatchReceiverParameter?.symbol ||
                atomicCalls.none { it.dispatchReceiver === load }
        }
    ) return null
    val exactAllowedCalls = buildList {
        addAll(atomicCalls)
        addAll(unitCalls)
        addAll(enumCalls)
        addAll(identityCalls)
        addAll(suspendedCalls)
        addAll(reinterpretCalls)
        addAll(enumInitializerCalls)
        addAll(delegateResumeCalls)
    }
    if (exactAllowedCalls.size != allCalls.size ||
        allCalls.indices.any { allCalls[it] !in exactAllowedCalls } ||
        enumCalls.any {
            (it.symbol.owner.parent as? IrClass)?.fqNameForIrSerialization?.asString() !=
                "kotlin.coroutines.intrinsics.CoroutineSingletons" ||
                it.symbol.owner.konanLibrary !== context.stdlibModule.konanLibrary
        }
    ) return null
    if (requireDelegateResume) {
        val alreadyResumed = constructorCalls.singleOrNull() ?: return null
        if ((alreadyResumed.symbol.owner.parent as? IrClass)?.fqNameForIrSerialization?.asString() !=
            "kotlin.IllegalStateException" ||
            (alreadyResumed.getValueArgument(0) as? IrConst<*>)?.value != "Already resumed" ||
            reinterpretCalls.size != 1 || suspendedCalls.size != 2 || enumInitializerCalls.size != 1
        ) return null
    } else if (constructorCalls.isNotEmpty() || reinterpretCalls.isNotEmpty() ||
        suspendedCalls.size != 3 || enumInitializerCalls.isNotEmpty()
    ) return null
    if (requireDelegateResume && !provesExactResumeStateDataflow(
            function, atomicCalls, atomicGetter, atomicCompareAndSet, variables, identityCalls,
            delegateResumeCalls.single(), unitCalls, ancestry, context,
        )
    ) return null
    if (requireFailureProjection && !provesExactGetOrThrowStateDataflow(
            atomicCalls, atomicGetter, atomicCompareAndSet, variables, assignments,
            identityCalls, targetReturns, failureProjections.single(), ancestry, context,
        )
    ) return null
    return resultRefLoads + delegateLoads + variables + assignments + targetReturns + orderedBranches +
        allCalls + constructorCalls + failureProjections
}

private fun provesExactResumeStateDataflow(
    function: IrSimpleFunction,
    atomicCalls: List<IrCall>,
    atomicGetter: IrSimpleFunction,
    atomicCompareAndSet: IrSimpleFunction,
    variables: List<IrVariable>,
    identityCalls: List<IrCall>,
    delegateResume: IrCall,
    unitCalls: List<IrCall>,
    ancestry: IdentityHashMap<IrElement, List<IrElement>>,
    context: org.jetbrains.kotlin.backend.konan.Context,
): Boolean {
    val resultParameter = function.valueParameters.singleOrNull() ?: return false
    val getter = atomicCalls.singleOrNull { it.symbol.owner === atomicGetter } ?: return false
    val current = variables.singleOrNull { it.name.asString() == "cur" && it.initializer === getter }
        ?: return false
    val cas = atomicCalls.filter { it.symbol.owner === atomicCompareAndSet }
    val successCas = cas.singleOrNull {
        it.getValueArgument(0).isExactCoroutineEnum(2) &&
            (it.getValueArgument(1) as? IrCall)?.let { update ->
                update.symbol.owner.fqNameForIrSerialization.asString() == "kotlin.native.internal.reinterpret" &&
                    update.containsExactValueRead(resultParameter)
            } == true
    } ?: return false
    val delegateCas = cas.singleOrNull {
        it.getValueArgument(0).isExactCoroutineSuspended() && it.getValueArgument(1).isExactCoroutineEnum(1)
    } ?: return false
    val undecidedTest = identityCalls.singleOrNull {
        it.hasExactIdentityArguments(current, enumId = 2)
    } ?: return false
    val suspendedTest = identityCalls.singleOrNull {
        it.hasExactIdentityArguments(current, suspended = true)
    } ?: return false
    val successBranch = successCas.enclosingConditionBranch(ancestry) ?: return false
    val delegateBranch = delegateCas.enclosingConditionBranch(ancestry) ?: return false
    val undecidedBranch = undecidedTest.enclosingConditionBranch(ancestry) ?: return false
    val suspendedBranch = suspendedTest.enclosingConditionBranch(ancestry) ?: return false
    val outerWhen = ancestry[undecidedBranch].orEmpty().filterIsInstance<IrWhen>().lastOrNull() ?: return false
    if (outerWhen !== ancestry[suspendedBranch].orEmpty().filterIsInstance<IrWhen>().lastOrNull() ||
        outerWhen.branches.size != 3 || outerWhen.branches[0] !== undecidedBranch ||
        outerWhen.branches[1] !== suspendedBranch ||
        (outerWhen.branches[2].condition as? IrConst<*>)?.value != true ||
        outerWhen.branches[2].result !is IrThrow
    ) return false
    val successWhen = undecidedBranch.result as? IrWhen ?: return false
    val delegateWhen = suspendedBranch.result as? IrWhen ?: return false
    if (successWhen.branches.singleOrNull() !== successBranch ||
        delegateWhen.branches.singleOrNull() !== delegateBranch
    ) return false
    val successReturn = successBranch.result as? IrReturn ?: return false
    if (successReturn.value !== unitCalls[0] || successReturn.returnTargetSymbol != function.symbol) return false
    val delegateBlock = delegateBranch.result as? IrBlock ?: return false
    val delegateReturn = delegateBlock.statements.lastOrNull() as? IrReturn ?: return false
    if (delegateBlock.statements.size != 2 || delegateBlock.statements[0] !== delegateResume ||
        delegateReturn.value !== unitCalls[1] || delegateReturn.returnTargetSymbol != function.symbol
    ) return false
    if (successBranch !in ancestry[unitCalls[0]].orEmpty() || undecidedBranch !in ancestry[successCas].orEmpty() ||
        delegateBranch !in ancestry[delegateResume].orEmpty() || delegateBranch !in ancestry[unitCalls[1]].orEmpty() ||
        suspendedBranch !in ancestry[delegateCas].orEmpty()
    ) return false
    return delegateResume.getValueArgument(0).containsExactValueRead(resultParameter) &&
        unitCalls.last() !in listOf(unitCalls[0], unitCalls[1]) &&
        context.ir.symbols.theUnitInstance == unitCalls.last().symbol
}

private fun provesExactGetOrThrowStateDataflow(
    atomicCalls: List<IrCall>,
    atomicGetter: IrSimpleFunction,
    atomicCompareAndSet: IrSimpleFunction,
    variables: List<IrVariable>,
    assignments: List<IrSetValue>,
    identityCalls: List<IrCall>,
    targetReturns: List<IrReturn>,
    failureProjection: IrGetField,
    ancestry: IdentityHashMap<IrElement, List<IrElement>>,
    context: org.jetbrains.kotlin.backend.konan.Context,
): Boolean {
    val result = variables.singleOrNull { it.name.asString() == "result" && it.isVar } ?: return false
    val getters = atomicCalls.filter { it.symbol.owner === atomicGetter }
    if (getters.size != 2 || result.initializer !== getters[0]) return false
    val reload = assignments.singleOrNull { it.symbol == result.symbol && it.value === getters[1] } ?: return false
    val cas = atomicCalls.singleOrNull { it.symbol.owner === atomicCompareAndSet } ?: return false
    if (!cas.getValueArgument(0).isExactCoroutineEnum(2) ||
        !cas.getValueArgument(1).isExactCoroutineSuspended() || cas.enclosingConditionBranch(ancestry) == null
    ) return false
    if (identityCalls.size != 2 || targetReturns.size != 2) return false
    val undecidedTest = identityCalls.singleOrNull { it.hasExactIdentityArguments(result, enumId = 2) }
        ?: return false
    val undecidedBranch = undecidedTest.enclosingConditionBranch(ancestry) ?: return false
    val outerWhen = ancestry[undecidedBranch].orEmpty().filterIsInstance<IrWhen>().lastOrNull() ?: return false
    if (outerWhen.branches.singleOrNull() !== undecidedBranch) return false
    val undecidedBlock = undecidedBranch.result as? IrBlock ?: return false
    val casBranch = cas.enclosingConditionBranch(ancestry) ?: return false
    val casWhen = ancestry[casBranch].orEmpty().filterIsInstance<IrWhen>().lastOrNull() ?: return false
    val suspendedReturn = casBranch.result as? IrReturn ?: return false
    if (undecidedBlock.statements.size != 2 || undecidedBlock.statements[0] !== casWhen ||
        undecidedBlock.statements[1] !== reload || casWhen.branches.singleOrNull() !== casBranch ||
        !suspendedReturn.value.isExactCoroutineSuspended() ||
        undecidedBranch !in ancestry[cas].orEmpty() || undecidedBranch !in ancestry[reload].orEmpty()
    ) return false
    val finalReturn = targetReturns.singleOrNull { it.value is IrWhen } ?: return false
    val finalWhen = finalReturn.value as IrWhen
    val resumedTest = identityCalls.singleOrNull { it.hasExactIdentityArguments(result, enumId = 1) }
        ?: return false
    if (finalWhen.branches.size != 3 || finalWhen.branches[0].condition !== resumedTest ||
        finalWhen !in ancestry[failureProjection].orEmpty()
    ) return false
    val resumedBranch = finalWhen.branches[0]
    if (!resumedBranch.result.isExactCoroutineSuspended()) return false
    val failureBranch = finalWhen.branches[1]
    val failureTest = failureBranch.condition as? IrTypeOperatorCall ?: return false
    val failureThrow = failureBranch.result as? IrThrow ?: return false
    if (failureTest.operator != IrTypeOperator.INSTANCEOF ||
        (failureTest.argument as? IrGetValue)?.symbol != result.symbol ||
        failureThrow !in ancestry[failureProjection].orEmpty()
    ) return false
    val successBranch = finalWhen.branches.lastOrNull() ?: return false
    return (successBranch.condition as? IrConst<*>)?.value == true &&
        (successBranch.result as? IrGetValue)?.symbol == result.symbol &&
        context.irBuiltIns.eqeqeqSymbol == resumedTest.symbol
}

private fun IrExpression?.isExactCoroutineEnum(id: Int): Boolean {
    val call = this.unwrapSROAEnumGetter() ?: return false
    return call.symbol.owner.name.asString() == "\$getEnumAt" &&
        (call.getValueArgument(0) as? IrConst<*>)?.value == id
}

private fun IrExpression?.isExactCoroutineSuspended(): Boolean =
    (this as? IrCall)?.symbol?.owner?.fqNameForIrSerialization?.asString() ==
        "kotlin.coroutines.intrinsics.<get-COROUTINE_SUSPENDED>"

private fun IrExpression?.containsExactValueRead(parameter: org.jetbrains.kotlin.ir.declarations.IrValueParameter): Boolean {
    val expression = this ?: return false
    val reads = mutableListOf<IrGetValue>()
    expression.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
        override fun visitGetValue(expression: IrGetValue) {
            reads += expression
            expression.acceptChildrenVoid(this)
        }
    })
    return reads.singleOrNull()?.symbol == parameter.symbol
}

private fun IrCall.hasExactIdentityArguments(
    variable: IrVariable,
    enumId: Int? = null,
    suspended: Boolean = false,
): Boolean {
    val arguments = listOfNotNull(getValueArgument(0), getValueArgument(1))
    return arguments.size == 2 && arguments.any { (it as? IrGetValue)?.symbol == variable.symbol } &&
        arguments.any { if (enumId != null) it.isExactCoroutineEnum(enumId) else suspended && it.isExactCoroutineSuspended() }
}

private fun IrCall.enclosingConditionBranch(
    ancestry: IdentityHashMap<IrElement, List<IrElement>>,
): IrBranch? = ancestry[this].orEmpty().filterIsInstance<IrBranch>().lastOrNull { it.condition === this }

private data class ExactUndecidedConstructorContract(val identityBindings: List<IrElement>)

/** Authenticate every effect of the secondary `this(delegate, UNDECIDED)` constructor. */
private fun authenticateExactUndecidedConstructor(
    constructor: IrConstructor,
    safeClass: IrClass,
    context: org.jetbrains.kotlin.backend.konan.Context,
): ExactUndecidedConstructorContract? {
    val body = constructor.body as? IrBlockBody ?: return null
    val delegatingCalls = mutableListOf<IrDelegatingConstructorCall>()
    val calls = mutableListOf<IrCall>()
    val constructors = mutableListOf<IrConstructorCall>()
    var unsupported = false
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
        override fun visitFunction(declaration: IrFunction) = Unit
        override fun visitWhen(expression: IrWhen) { unsupported = true }
        override fun visitLoop(loop: IrLoop) { unsupported = true }
        override fun visitTry(aTry: IrTry) { unsupported = true }
        override fun visitThrow(expression: IrThrow) { unsupported = true }
        override fun visitSetField(expression: IrSetField) { unsupported = true }
        override fun visitSetValue(expression: IrSetValue) { unsupported = true }
        override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall) {
            delegatingCalls += expression
            expression.acceptChildrenVoid(this)
        }
        override fun visitConstructorCall(expression: IrConstructorCall) {
            constructors += expression
            expression.acceptChildrenVoid(this)
        }
        override fun visitCall(expression: IrCall) {
            calls += expression
            expression.acceptChildrenVoid(this)
        }
    })
    val delegation = delegatingCalls.singleOrNull() ?: return null
    val primary = delegation.symbol.owner
    if (unsupported || body.statements.size != 2 || body.statements.first() !== delegation ||
        constructors.isNotEmpty() || primary.parent !== safeClass || !primary.isPrimary ||
        primary === constructor || primary.valueParameters.size != 2 ||
        delegation.valueArgumentsCount != 2 || delegation.typeArgumentsCount != 1 ||
        (delegation.getValueArgument(0) as? IrGetValue)?.symbol != constructor.valueParameters.single().symbol
    ) return null
    val initialResult = delegation.getValueArgument(1) as? IrBlock ?: return null
    if (initialResult.statements.size != 2) return null
    val enumInitializer = initialResult.statements[0] as? IrCall ?: return null
    val undecidedGetter = (initialResult.statements[1] as? IrExpression).unwrapSROAEnumGetter() ?: return null
    val enumGetter = undecidedGetter.symbol.owner
    val enumClass = enumGetter.parent as? IrClass ?: return null
    val structuralReturn = body.statements.last() as? IrReturn ?: return null
    val structuralUnit = structuralReturn.value as? IrCall ?: return null
    if (calls.size != 3 || calls[0] !== enumInitializer || calls[1] !== undecidedGetter ||
        calls[2] !== structuralUnit || structuralUnit.symbol != context.ir.symbols.theUnitInstance ||
        structuralReturn.returnTargetSymbol != constructor.symbol ||
        enumInitializer.symbol.owner.name.asString() != "\$init_global" ||
        enumInitializer.symbol.owner.parent !== enumClass
    ) return null
    if (enumClass.fqNameForIrSerialization.asString() != "kotlin.coroutines.intrinsics.CoroutineSingletons" ||
        enumGetter.name.asString() != "\$getEnumAt" || enumGetter.isOverridable ||
        enumGetter.valueParameters.size != 1 || undecidedGetter.valueArgumentsCount != 1 ||
        (undecidedGetter.getValueArgument(0) as? IrConst<*>)?.value != 2 ||
        enumGetter.returnType != undecidedGetter.type ||
        enumClass.konanLibrary !== context.stdlibModule.konanLibrary
    ) return null
    return ExactUndecidedConstructorContract(
        listOf(delegation, enumInitializer, undecidedGetter, structuralReturn, structuralUnit),
    )
}

private fun IrExpression?.unwrapSROAEnumGetter(): IrCall? = when (this) {
    is IrCall -> this
    is IrBlock -> (statements.lastOrNull() as? IrExpression).unwrapSROAEnumGetter()
    is IrTypeOperatorCall -> if (operator == IrTypeOperator.IMPLICIT_CAST) argument.unwrapSROAEnumGetter() else null
    else -> null
}

private data class LinearSROARegion(
    val statements: List<IrElement>,
) {
    fun authenticateExactStructuralCalls(
        allocation: IrConstructorCall,
        resume: IrCall,
        get: IrCall,
        intercepted: IrCall,
        resumeResult: IrExpression,
        ancestry: IdentityHashMap<IrElement, List<IrElement>>,
        context: org.jetbrains.kotlin.backend.konan.Context,
    ): ExactSafeContinuationStructuralCalls? {
        val calls = mutableListOf<IrCall>()
        val constructors = mutableListOf<IrConstructorCall>()
        var unsupported = false
        statements.forEach { statement ->
            statement.acceptVoid(object : IrElementVisitorVoid {
                override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
                override fun visitFunction(declaration: IrFunction) { unsupported = true }
                override fun visitTry(aTry: IrTry) { unsupported = true }
                override fun visitWhen(expression: IrWhen) { unsupported = true }
                override fun visitLoop(loop: IrLoop) { unsupported = true }
                override fun visitBreak(jump: IrBreak) { unsupported = true }
                override fun visitContinue(jump: IrContinue) { unsupported = true }
                override fun visitThrow(expression: IrThrow) { unsupported = true }
                override fun visitReturn(expression: IrReturn) {
                    if (!expression.isTerminalLexicalReturn(get, ancestry)) unsupported = true
                    expression.acceptChildrenVoid(this)
                }
                override fun visitSuspendableExpression(expression: IrSuspendableExpression) { unsupported = true }
                override fun visitSuspensionPoint(expression: IrSuspensionPoint) { unsupported = true }
                override fun visitSetField(expression: IrSetField) {
                    unsupported = true
                    expression.acceptChildrenVoid(this)
                }
                override fun visitGetField(expression: IrGetField) {
                    unsupported = true
                    expression.acceptChildrenVoid(this)
                }
                override fun visitSetValue(expression: IrSetValue) {
                    unsupported = true
                    expression.acceptChildrenVoid(this)
                }
                override fun visitTypeOperator(expression: IrTypeOperatorCall) {
                    if (expression.operator != IrTypeOperator.IMPLICIT_CAST) unsupported = true
                    expression.acceptChildrenVoid(this)
                }
                override fun visitCall(expression: IrCall) {
                    val callee = expression.symbol.owner
                    val fqName = callee.fqNameForIrSerialization.asString()
                    if (callee.isSuspend || fqName.contains("COROUTINE_SUSPENDED") ||
                        fqName.startsWith("kotlin.native.concurrent.Worker") ||
                        fqName.contains("executeAfter") || fqName.contains("TransferMode")
                    ) unsupported = true
                    calls += expression
                    expression.acceptChildrenVoid(this)
                }
                override fun visitConstructorCall(expression: IrConstructorCall) {
                    constructors += expression
                    expression.acceptChildrenVoid(this)
                }
            })
        }
        if (unsupported || constructors.singleOrNull() !== allocation ||
            calls.count { it === resume } != 1 || calls.count { it === get } != 1 ||
            calls.count { it === intercepted } != 1
        ) return null

        val structural = calls.filter { it !== resume && it !== get && it !== intercepted }
        val producer = structural.singleOrNull { call ->
            call.symbol == context.irBuiltIns.intPlusSymbol &&
                    call.symbol.owner.isExternal && call.origin?.toString() == "PLUS" &&
                    call.dispatchReceiver?.type?.isInt() == true && call.valueArgumentsCount == 1 &&
                    call.getValueArgument(0)?.type?.isInt() == true && call.type.isInt()
        } ?: return null
        val resultClass = context.ir.symbols.kotlinResult.owner
        val exactCompanionGetter = resultClass.declarations.filterIsInstance<IrSimpleFunction>().singleOrNull {
            it.name.asString() == "<get-\$companion>" && it.valueParameters.isEmpty() &&
                    it.dispatchReceiverParameter == null && it.extensionReceiverParameter == null
        } ?: return null
        val companion = structural.singleOrNull { call ->
            call.symbol.owner === exactCompanionGetter &&
            call.symbol.owner.name.asString() == "<get-\$companion>" &&
                    call.symbol.owner.parent === resultClass &&
                    call.dispatchReceiver == null && call.extensionReceiver == null &&
                    call.valueArgumentsCount == 0
        } ?: return null
        val exactBox = context.getBoxFunction(context.irBuiltIns.intClass.owner)
        val box = structural.singleOrNull { call ->
            call.symbol.owner === exactBox && call.dispatchReceiver == null &&
                    call.extensionReceiver == null && call.valueArgumentsCount == 1 &&
                    call.getValueArgument(0)?.type?.isInt() == true && call.type.binaryTypeIsReference()
        } ?: return null
        val exactResultConstructor = resultClass.constructors.singleOrNull { it.isPrimary } ?: return null
        val resultConstructor = structural.singleOrNull { call ->
            call.symbol.owner === exactResultConstructor && call.symbol.owner.parent === resultClass &&
                    call.dispatchReceiver == null && call.extensionReceiver == null &&
                    call.valueArgumentsCount == 1 && call.type.isUnit()
        } ?: return null
        val units = structural.filter { call ->
            call.symbol == context.ir.symbols.theUnitInstance &&
                    call.dispatchReceiver == null && call.extensionReceiver == null &&
                    call.valueArgumentsCount == 0 && call.type.isUnit()
        }
        if (units.size != 2 || structural.size != 6) return null
        if (units.any { unit ->
                val structuralReturn = ancestry[unit].orEmpty().lastOrNull { it is IrReturn } as? IrReturn
                structuralReturn?.value !== unit || !structuralReturn.isTerminalLexicalReturn(get, ancestry)
            }
        ) return null
        if (listOf(companion, box, resultConstructor).any { call ->
                resumeResult !in ancestry[call].orEmpty()
            }
        ) return null
        if (!provesExactResumeArgumentDataflow(
                statements, resumeResult, producer, box, resultConstructor, ancestry,
            )
        ) return null
        return ExactSafeContinuationStructuralCalls(producer, companion, box, resultConstructor, units)
    }
}

private fun IrReturn.isTerminalLexicalReturn(
    expectedGet: IrCall,
    ancestry: IdentityHashMap<IrElement, List<IrElement>>,
): Boolean {
    val target = returnTargetSymbol.owner as? IrReturnableBlock ?: return false
    val ancestors = ancestry[this].orEmpty()
    val targetIndex = ancestors.indexOfLast { it === target }
    if (targetIndex < 0) return false
    val directChild = ancestors.getOrNull(targetIndex + 1) ?: this
    if (target.statements.lastOrNull() !== directChild) return false
    // A return targeting a block that also encloses the expected get is an early transfer unless
    // that exact get is the returned value. Returns from nested inline-expression blocks are
    // structural and cannot bypass the later get.
    if (target in ancestry[expectedGet].orEmpty()) {
        return value === expectedGet || value in ancestry[expectedGet].orEmpty()
    }
    return true
}

/**
 * Authenticate the benchmark's exact inline `Result.success(value + 1)` dataflow. The primitive
 * producer owns one temporary, the box consumes its sole read, the Result initializer consumes
 * the boxed temporary, and the same boxed value is the terminal resume argument. Containment in
 * the resume expression alone is insufficient.
 */
private fun provesExactResumeArgumentDataflow(
    statements: List<IrElement>,
    resumeResult: IrExpression,
    producer: IrCall,
    box: IrCall,
    resultConstructor: IrCall,
    ancestry: IdentityHashMap<IrElement, List<IrElement>>,
): Boolean {
    val producedVariable = ancestry[producer].orEmpty().filterIsInstance<IrVariable>()
        .lastOrNull { it.initializer === producer } ?: return false
    val producedRead = box.getValueArgument(0) as? IrGetValue ?: return false
    if (producedRead.symbol != producedVariable.symbol) return false

    val boxedVariable = ancestry[box].orEmpty().filterIsInstance<IrVariable>()
        .lastOrNull { it.initializer === box } ?: return false
    val resultConstructorRead = resultConstructor.getValueArgument(0) as? IrGetValue ?: return false
    if (resultConstructorRead.symbol != boxedVariable.symbol) return false

    val resultBlock = ancestry[boxedVariable].orEmpty().filterIsInstance<IrBlock>().lastOrNull { block ->
        resumeResult in ancestry[block].orEmpty() &&
                block.statements.size == 3 && block.statements[0] === boxedVariable &&
                block.statements[1] === resultConstructor &&
                (block.statements[2] as? IrGetValue)?.symbol == boxedVariable.symbol
    } ?: return false
    val terminalRead = resultBlock.statements[2] as IrGetValue

    val producedReads = mutableListOf<IrGetValue>()
    val boxedReads = mutableListOf<IrGetValue>()
    statements.forEach { statement ->
        statement.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
            override fun visitFunction(declaration: IrFunction) = Unit
            override fun visitGetValue(expression: IrGetValue) {
                when (expression.symbol) {
                    producedVariable.symbol -> producedReads += expression
                    boxedVariable.symbol -> boxedReads += expression
                }
                expression.acceptChildrenVoid(this)
            }
        })
    }
    return producedReads.singleOrNull() === producedRead && boxedReads.size == 2 &&
            boxedReads.any { it === resultConstructorRead } && boxedReads.any { it === terminalRead }
}

private data class ExactSafeContinuationStructuralCalls(
    val resumeValueProducer: IrCall,
    val resultCompanionGetter: IrCall,
    val resultBoxIntrinsic: IrCall,
    val resultConstructor: IrCall,
    val unitInstances: List<IrCall>,
)

private fun proveLinearRegion(
    local: IrVariable,
    resume: IrCall,
    get: IrCall,
    ancestry: IdentityHashMap<IrElement, List<IrElement>>,
): LinearSROARegion? {
    val localAncestors = ancestry[local].orEmpty()
    val resumeAncestors = ancestry[resume].orEmpty()
    val getAncestors = ancestry[get].orEmpty()
    val container = localAncestors.filterIsInstance<IrContainerExpression>().lastOrNull {
        it in resumeAncestors && it in getAncestors
    } ?: return null
    fun statementIndex(target: IrElement, ancestors: List<IrElement>): Int {
        if (target in container.statements) return container.statements.indexOfFirst { it === target }
        val containerIndex = ancestors.indexOfFirst { it === container }
        if (containerIndex < 0 || containerIndex + 1 >= ancestors.size) return -1
        val directChild = ancestors[containerIndex + 1]
        return container.statements.indexOfFirst { it === directChild }
    }
    val localIndex = statementIndex(local, localAncestors)
    val resumeIndex = statementIndex(resume, resumeAncestors)
    val getIndex = statementIndex(get, getAncestors)
    if (localIndex < 0 || resumeIndex <= localIndex || getIndex <= resumeIndex) return null
    return LinearSROARegion(container.statements.subList(localIndex, getIndex + 1))
}

/**
 * The linear sublist alone is insufficient: placing it inside an outer branch, loop, try, or
 * suspension region changes initialization frequency and exceptional cleanup frontiers.
 */
private fun hasUnsupportedAncestorContext(
    local: IrVariable,
    resume: IrCall,
    get: IrCall,
    ancestry: IdentityHashMap<IrElement, List<IrElement>>,
): Boolean = listOf(local, resume, get).any { element ->
    ancestry[element].orEmpty().any { ancestor ->
        ancestor is IrTry || ancestor is IrLoop || ancestor is IrWhen ||
                ancestor is IrSuspendableExpression || ancestor is IrSuspensionPoint
    }
}

private fun IrExpression?.unwrapSROALocal(): IrGetValue? = when (this) {
    is IrGetValue -> this
    is IrTypeOperatorCall -> if (operator == IrTypeOperator.IMPLICIT_CAST) argument.unwrapSROALocal() else null
    else -> null
}

private fun collectClasses(module: IrModuleFragment): List<IrClass> = buildList {
    module.files.forEach { file ->
        file.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
            override fun visitClass(declaration: IrClass) {
                add(declaration)
                declaration.acceptChildrenVoid(this)
            }
        })
    }
}

private fun ArcSafeContinuationSROAMode.isSelectableProductionMode(): Boolean =
    arcEnabled && linuxX64 && finalBinary && optimizationsEnabled && debugInfoDisabled &&
            diagnosticsDisabled && sanitizerDisabled && coverageDisabled

private fun IrSimpleFunction.isExactSROALoweredSuspendContainer(): Boolean =
    origin == IrDeclarationOrigin.LOWERED_SUSPEND_FUNCTION &&
            valueParameters.count { it.origin == IrDeclarationOrigin.CONTINUATION } == 1

private fun <T : Any> List<T>.haveUniqueIRIdentity(): Boolean {
    val seen = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    return all { seen.add(it) }
}
