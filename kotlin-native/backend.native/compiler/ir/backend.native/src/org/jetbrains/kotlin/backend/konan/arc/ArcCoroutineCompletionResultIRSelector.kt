/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.KonanFqNames
import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.backend.konan.ir.konanLibrary
import org.jetbrains.kotlin.backend.konan.isFinalBinary
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
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
import org.jetbrains.kotlin.ir.expressions.IrCatch
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrInlinedFunctionBlock
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
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.getArgumentsWithIr
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.util.IdentityHashMap

internal data class ArcCoroutineCompletionResultIRShape(
    val exactCompletionProjection: Boolean,
    val exactCompletionNullCheck: Boolean,
    val exactSuccessIdentityChain: Boolean,
    val exactFailureIdentityChain: Boolean,
    val exactOutcomeJoin: Boolean,
    val exactSuspendedExit: Boolean,
    val exactTryCatchArmsAndEffects: Boolean,
    val exactDispatchTopology: Boolean,
    val exactContinuationResumeWithABI: Boolean,
    val exactReferenceResultCatchOwnershipABI: Boolean,
    val exactBackedgeTransfer: Boolean,
    val exactTerminalCallAndReturn: Boolean,
    val completeCompletionUseCensus: Boolean,
    val completeOutcomeUseCensus: Boolean,
    val completeProducerUseCensus: Boolean,
    val completeLoweredBodyWalk: Boolean,
    val completeIdentityInventory: Boolean,
)

private fun ArcCoroutineCompletionResultIRShape.isExact(): Boolean =
    exactCompletionProjection && exactCompletionNullCheck && exactSuccessIdentityChain && exactFailureIdentityChain &&
            exactOutcomeJoin && exactSuspendedExit && exactTryCatchArmsAndEffects &&
            exactDispatchTopology && exactContinuationResumeWithABI && exactBackedgeTransfer &&
            exactReferenceResultCatchOwnershipABI &&
            exactTerminalCallAndReturn && completeCompletionUseCensus &&
            completeOutcomeUseCensus && completeProducerUseCensus && completeLoweredBodyWalk &&
            completeIdentityInventory

internal enum class ArcCoroutineCompletionResultIRRejectionReason {
    InvalidStructuralProof,
    OwnershipAnalysisRejected,
}

internal data class ArcCoroutineCompletionResultIRSelectorResult<T : Any>(
    val selection: ArcCoroutineCompletionResultSelection<T>?,
    val rejection: ArcCoroutineCompletionResultIRRejectionReason?,
    val ownershipRejection: ArcCoroutineCompletionResultRejectionReason? = null,
)

internal fun <T : Any> adaptVerifiedCoroutineCompletionResultIR(
    bindings: ArcCoroutineCompletionResultBindings<T>,
    mode: ArcCoroutineCompletionResultMode,
    shape: ArcCoroutineCompletionResultIRShape,
): ArcCoroutineCompletionResultIRSelectorResult<T> {
    if (!shape.isExact()) return ArcCoroutineCompletionResultIRSelectorResult(
        null, ArcCoroutineCompletionResultIRRejectionReason.InvalidStructuralProof,
    )
    val analysis = ArcCoroutineCompletionResultAnalysis.select(
        bindings,
        mode,
        ArcCoroutineCompletionResultProof(
            exactBaseContinuationSelection = true,
            exactFinalStrongCompletionField = true,
            exactNullCheckedCompletionProjection = true,
            completionUsesExhaustive = true,
            currentAnchorCoversNormalAndUnwind = true,
            invokeSuspendProducesOwnedResult = true,
            suspendedExitDestroysOwnedResult = true,
            successResultIsIdentityForward = true,
            failureFactoryProducesOwnedResult = true,
            failureFactoryUnwindDestroysCaughtException = true,
            failureCopiesExceptionBeforeCatchOwnerRelease = true,
            exactReferenceResultCatchOwnershipABI = true,
            outcomeJoinIsExactlySuccessOrFailure = true,
            outcomeUsesExhaustive = true,
            releaseUnwindDestroysOutcome = true,
            backedgeRetainsCompletionBeforeReplacingCurrent = true,
            backedgeConsumesOutcomeIntoParameter = true,
            terminalCallBorrowsOutcome = true,
            terminalNormalDestroysOutcome = true,
            terminalUnwindDestroysOutcome = true,
            everyExitEndsCompletionBorrow = true,
        ),
    )
    val selection = analysis.selection ?: return ArcCoroutineCompletionResultIRSelectorResult(
        null,
        ArcCoroutineCompletionResultIRRejectionReason.OwnershipAnalysisRejected,
        analysis.rejection,
    )
    return ArcCoroutineCompletionResultIRSelectorResult(selection, null)
}

/**
 * Authenticate the current post-inlining stdlib body. This selector intentionally reuses the
 * already-frozen guaranteed-phi selection for the loop header, then performs a complete identity
 * census of the completion/result subgraph. It is proof-only and is not connected to codegen.
 */
internal fun selectVerifiedCoroutineCompletionResultWeb(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
): ArcCoroutineCompletionResultSelection<Any>? {
    val context = generationState.context
    val mode = ArcCoroutineCompletionResultMode(
        arcEnabled = context.memoryModel == MemoryModel.ARC,
        linuxX64 = context.config.target == KonanTarget.LINUX_X64,
        finalBinary = context.config.isFinalBinary,
        optimizationsEnabled = context.config.optimizationsEnabled,
        debugInfoDisabled = !context.shouldContainAnyDebugInfo(),
        diagnosticsDisabled = !context.config.arcDiagnosticsEnabled,
        sanitizerDisabled = context.config.sanitizer == null && !context.config.undefinedBehaviorSanitizer,
        coverageDisabled = !generationState.coverage.enabled,
    )
    val base = selectVerifiedCoroutineGuaranteedPhiWebs(generationState, function) ?: return null
    fun reject(reason: String): ArcCoroutineCompletionResultSelection<Any>? {
        context.log {
            "ARC coroutine completion/result selector ${function.fqNameForIrSerialization.asString()}: $reason"
        }
        return null
    }
    val owner = function.parent as? IrClass ?: return reject("owner")
    val stdlib = context.stdlibModule.konanLibrary ?: return reject("stdlib")
    val ownerFields = buildList {
        addAll(owner.declarations.filterIsInstance<IrField>())
        owner.properties.mapNotNull { it.backingField }.forEach { field ->
            if (none { it === field }) add(field)
        }
    }
    val completionField = ownerFields.singleOrNull {
        it.name.asString() == "completion" && it.konanLibrary === stdlib
    } ?: return reject("completion field")
    if (completionField.isStatic || !completionField.isFinal ||
        completionField.visibility != DescriptorVisibilities.PRIVATE ||
        !completionField.type.binaryTypeIsReference() ||
        completionField.hasAnnotation(KonanFqNames.arcWeak) ||
        completionField.hasAnnotation(KonanFqNames.arcUnowned)
    ) return reject("completion field ABI")

    val body = function.body as? IrBlockBody ?: return reject("body")
    val scan = CompletionResultIRScan(function).apply { body.acceptVoid(this) }
    if (scan.sawNestedFunction) return reject("nested function")

    val completionFieldLoad = scan.fieldLoads.singleOrNull {
        it.symbol.owner === completionField && it.receiver.isExactReadOf(base.currentIterationBorrow)
    } ?: return reject("completion field projection")
    val completionNullableTemporary = scan.variables.singleOrNull {
        !it.isVar && it.initializer === completionFieldLoad && scan.readsOf(it).size == 2
    } ?: return reject("completion nullable temporary")
    val completionProjection = scan.variables.singleOrNull { variable ->
        val initializer = variable.initializer as? IrBlock
        return@singleOrNull !variable.isVar && variable !== completionNullableTemporary &&
                initializer?.statements?.firstOrNull() === completionNullableTemporary &&
                scan.readsOf(variable).size == 3
    } ?: return reject(
        "completion projection variable candidates=" + scan.variables.joinToString { variable ->
            "${variable.name}:${variable.isVar}:${variable.initializer?.let { it::class.simpleName }}:" +
                    scan.readsOf(variable).size
        },
    )
    if (completionNullableTemporary.hasAnnotation(KonanFqNames.arcWeak) ||
        completionNullableTemporary.hasAnnotation(KonanFqNames.arcUnowned) ||
        completionProjection.hasAnnotation(KonanFqNames.arcWeak) ||
        completionProjection.hasAnnotation(KonanFqNames.arcUnowned)
    ) return reject("completion projection ownership ABI")

    // `completion!!` has already passed BuiltinOperatorLowering. Authenticate the whole wrapper:
    // block(temp = field; if (reinterpret(temp) === null) ThrowNullPointerException(); temp).
    val completionProjectionBlock = completionProjection.initializer as IrBlock
    if (completionProjectionBlock.origin != null || completionProjectionBlock.statements.size != 3 ||
        completionProjectionBlock.statements[0] !== completionNullableTemporary ||
        completionNullableTemporary.origin != IrDeclarationOrigin.IR_TEMPORARY_VARIABLE ||
        !completionProjection.type.binaryTypeIsReference() ||
        completionProjection.type.getClass()?.symbol != context.ir.symbols.continuationClass
    ) return reject("completion null-check block")
    val completionNullWhen = completionProjectionBlock.statements[1] as? IrWhen
        ?: return reject("completion null-check when")
    if (!completionNullWhen.type.isUnit()) return reject("completion null-check Unit guard")
    val completionProjectionRead = completionProjectionBlock.statements[2] as? IrGetValue
        ?: return reject("completion projection result")
    if (completionProjectionRead.symbol != completionNullableTemporary.symbol) {
        return reject("completion projection result read")
    }
    val completionNullBranch = completionNullWhen.branches.singleOrNull()
        ?: return reject("completion null-check branch count")
    val completionNullComparison = completionNullBranch.condition as? IrCall
        ?: return reject("completion null comparison")
    if (completionNullComparison.symbol != context.irBuiltIns.eqeqeqSymbol) {
        return reject("completion null comparison symbol")
    }
    val nullArguments = listOfNotNull(
        completionNullComparison.getValueArgument(0), completionNullComparison.getValueArgument(1),
    )
    if (nullArguments.size != 2 || nullArguments.count { it.isNullConstant() } != 1) {
        return reject("completion null comparison operands")
    }
    val completionNullReinterpret = nullArguments.singleOrNull { !it.isNullConstant() } as? IrCall
        ?: return reject("completion null checked operand")
    val completionNullRead = completionNullReinterpret.extensionReceiver as? IrGetValue
        ?: return reject("completion null checked read")
    if (completionNullReinterpret.symbol != context.ir.symbols.reinterpret ||
        completionNullReinterpret.valueArgumentsCount != 0 ||
        completionNullRead.symbol != completionNullableTemporary.symbol
    ) return reject("completion null checked reinterpret")
    val completionNullThrow = completionNullBranch.result as? IrCall
        ?: return reject("completion null throw")
    if (completionNullThrow.symbol != context.ir.symbols.throwNullPointerException ||
        completionNullThrow.getArgumentsWithIr().any()
    ) return reject("completion null throw ABI")
    val nullableReads = scan.readsOf(completionNullableTemporary)
    if (!nullableReads.exactlyCoveredBy(completionNullReinterpret, completionProjectionRead)) {
        return reject("completion nullable use census=${nullableReads.size}")
    }

    val outcomeJoin = scan.variables.singleOrNull { !it.isVar && it.initializer is IrTry }
        ?: return reject("outcome join")
    val outcomeTry = outcomeJoin.initializer as IrTry
    if (outcomeTry.catches.size != 1 || outcomeTry.finallyExpression != null) return reject("outcome try shape")
    val outcomeCatch = outcomeTry.catches.single()
    val caughtException = outcomeCatch.catchParameter
    val successArm = outcomeTry.tryResult as? IrBlock ?: return reject("success arm block")
    val failureArm = outcomeCatch.result as? IrBlock ?: return reject("failure arm block")

    // These values are modeled as ordinary strong owners/borrows by the proof. Authenticate the
    // erased post-inline ABI rather than inferring ownership merely from their positions in the CFG.
    if (!caughtException.type.binaryTypeIsReference() || caughtException.type != context.irBuiltIns.throwableType ||
        caughtException.hasAnnotation(KonanFqNames.arcWeak) ||
        caughtException.hasAnnotation(KonanFqNames.arcUnowned) ||
        !outcomeJoin.type.binaryTypeIsReference() ||
        outcomeJoin.hasAnnotation(KonanFqNames.arcWeak) || outcomeJoin.hasAnnotation(KonanFqNames.arcUnowned)
    ) return reject("catch/outcome ownership ABI")

    val invokeOwnedResult = scan.variables.singleOrNull {
        !it.isVar && it.initializer === base.invokeSuspend && scan.readsOf(it).size == 2
    } ?: return reject("invokeSuspend owned result")
    if (!invokeOwnedResult.type.binaryTypeIsReference() ||
        invokeOwnedResult.hasAnnotation(KonanFqNames.arcWeak) ||
        invokeOwnedResult.hasAnnotation(KonanFqNames.arcUnowned)
    ) return reject("invokeSuspend result ownership ABI")
    val suspendedComparison = scan.calls.singleOrNull { call ->
        call.symbol == context.irBuiltIns.eqeqeqSymbol && call.containsExactReadOf(invokeOwnedResult) &&
                call.getArgumentsWithIr().any { (_, argument) ->
                    (argument as? IrCall)?.symbol == context.ir.symbols.coroutineSuspendedGetter
                }
    } ?: return reject("suspended identity comparison")
    val suspendedGetter = suspendedComparison.getArgumentsWithIr().map { it.second }.filterIsInstance<IrCall>()
        .singleOrNull { it.symbol == context.ir.symbols.coroutineSuspendedGetter }
        ?: return reject("suspended getter")
    if (!suspendedComparison.hasExactArguments(invokeOwnedResult, suspendedGetter)) {
        return reject("suspended comparison operands")
    }
    val suspendedBranch = scan.nearestAncestor<IrBranch>(suspendedComparison)
        ?.takeIf { it.condition === suspendedComparison }
        ?: return reject("suspended condition branch")
    val suspendedWhen = scan.nearestAncestor<IrWhen>(suspendedBranch)
        ?: return reject("suspended when")
    if (suspendedWhen.branches.singleOrNull() !== suspendedBranch) return reject("suspended when topology")
    val suspendedReturn = suspendedBranch.result as? IrReturn ?: return reject("suspended return")
    val suspendedUnit = suspendedReturn.value as? IrCall ?: return reject("suspended Unit result")
    if (suspendedReturn.returnTargetSymbol != function.symbol ||
        suspendedUnit.symbol != context.ir.symbols.theUnitInstance
    ) return reject("suspended return ABI")

    val resultClass = context.ir.symbols.kotlinResult.owner
    if (resultClass.konanLibrary !== stdlib ||
        resultClass.fqNameForIrSerialization.asString() != "kotlin.Result" ||
        resultClass.modality != Modality.FINAL || !resultClass.isValue
    ) return reject("Result declaration ABI")
    val resultValueField = scan.fieldLoads.filter { field ->
        val declaration = field.symbol.owner
        declaration.name.asString() == "value" && declaration.parent === resultClass &&
                declaration.konanLibrary === stdlib && !declaration.isStatic && declaration.isFinal &&
                declaration.visibility == DescriptorVisibilities.PRIVATE && declaration.type.binaryTypeIsReference() &&
                !declaration.hasAnnotation(KonanFqNames.arcWeak) &&
                !declaration.hasAnnotation(KonanFqNames.arcUnowned)
    }
    val successIdentityForward = resultValueField.singleOrNull {
        outcomeTry.tryResult.containsIdentity(it) && it.containsExactReadOf(invokeOwnedResult)
    } ?: return reject("success Result identity forward")

    val failureFactory = scan.calls.singleOrNull { call ->
        val declaration = call.symbol.owner
        declaration.fqNameForIrSerialization.asString() == "kotlin.createFailure" &&
                declaration.konanLibrary === stdlib && declaration.dispatchReceiverParameter == null &&
                declaration.extensionReceiverParameter == null && declaration.typeParameters.isEmpty() &&
                declaration.valueParameters.singleOrNull()?.type?.binaryTypeIsReference() == true &&
                declaration.returnType.binaryTypeIsReference() && !declaration.isExternal && !declaration.isSuspend &&
                call.valueArgumentsCount == 1 && call.getValueArgument(0).isExactReadOf(caughtException)
    } ?: return reject("failure factory")
    val failureOwnedResult = scan.variables.singleOrNull {
        !it.isVar && it.initializer === failureFactory && scan.readsOf(it).size == 2
    } ?: return reject("failure owned result")
    if (!failureOwnedResult.type.binaryTypeIsReference() ||
        failureOwnedResult.hasAnnotation(KonanFqNames.arcWeak) ||
        failureOwnedResult.hasAnnotation(KonanFqNames.arcUnowned) ||
        outcomeTry.type != outcomeJoin.type || successArm.type != outcomeJoin.type ||
        failureArm.type != outcomeJoin.type || !outcomeTry.type.binaryTypeIsReference() ||
        !successArm.type.binaryTypeIsReference() || !failureArm.type.binaryTypeIsReference() ||
        !failureFactory.type.binaryTypeIsReference()
    ) return reject("Result join ownership ABI")
    val failureIdentityForward = resultValueField.singleOrNull {
        failureArm.containsIdentity(it) && it.containsExactReadOf(failureOwnedResult)
    } ?: return reject("failure Result identity forward")
    val resultValueDeclaration = successIdentityForward.symbol.owner
    if (successIdentityForward.symbol != failureIdentityForward.symbol ||
        successIdentityForward.type != resultValueDeclaration.type ||
        failureIdentityForward.type != resultValueDeclaration.type
    ) {
        return reject("Result.value field symbol identity")
    }

    val successResultWrapper = successArm.statements.getOrNull(2) as? IrReturnableBlock
        ?: return reject("success Result inline wrapper")
    val failureResultWrapper = failureArm.statements.singleOrNull() as? IrReturnableBlock
        ?: return reject("failure Result inline wrapper")
    if (successArm.statements.size != 3 || successArm.statements[0] !== invokeOwnedResult ||
        successArm.statements[1] !== suspendedWhen || successResultWrapper.type != outcomeJoin.type ||
        failureResultWrapper.type != outcomeJoin.type
    ) return reject("exact try/catch arm results")

    val successInlineReturn = successIdentityForward.receiver as? IrReturn
        ?: return reject("success Result.value receiver return")
    val failureInlineReturn = failureIdentityForward.receiver as? IrReturn
        ?: return reject("failure Result.value receiver return")
    if (successInlineReturn.returnTargetSymbol != successResultWrapper.symbol ||
        failureInlineReturn.returnTargetSymbol != failureResultWrapper.symbol
    ) return reject("Result inline return targets")
    val successPayloadBlock = successInlineReturn.value as? IrBlock
        ?: return reject("success Result payload block")
    val failurePayloadBlock = failureInlineReturn.value as? IrBlock
        ?: return reject("failure Result payload block")
    val successPayloadTemporary = successPayloadBlock.statements.getOrNull(0) as? IrVariable
        ?: return reject("success Result payload temporary")
    val successResultConstructor = successPayloadBlock.statements.getOrNull(1) as? IrCall
        ?: return reject("success Result constructor")
    val successPayloadRead = successPayloadBlock.statements.getOrNull(2) as? IrGetValue
        ?: return reject("success Result payload result")
    val failureResultConstructor = failurePayloadBlock.statements.getOrNull(1) as? IrCall
        ?: return reject("failure Result constructor")
    val failurePayloadRead = failurePayloadBlock.statements.getOrNull(2) as? IrGetValue
        ?: return reject("failure Result payload result")
    fun IrCall.isExactResultConstructor(payload: IrVariable): Boolean {
        val declaration = symbol.owner
        return declaration.name.asString() == "<constructor>" && declaration.parent === resultClass &&
                declaration.konanLibrary === stdlib && declaration.returnType.isUnit() && valueArgumentsCount == 1 &&
                dispatchReceiver == null && extensionReceiver == null &&
                getValueArgument(0).isExactReadOf(payload)
    }
    if (successPayloadBlock.statements.size != 3 || successPayloadTemporary.isVar ||
        !successPayloadTemporary.initializer.isExactReadOf(invokeOwnedResult) ||
        successPayloadRead.symbol != successPayloadTemporary.symbol ||
        !successResultConstructor.isExactResultConstructor(successPayloadTemporary) ||
        failurePayloadBlock.statements.size != 3 || failurePayloadBlock.statements[0] !== failureOwnedResult ||
        failurePayloadRead.symbol != failureOwnedResult.symbol ||
        !failureResultConstructor.isExactResultConstructor(failureOwnedResult)
    ) return reject("Result payload construction topology")

    fun exactResultCompanionGetter(wrapper: IrReturnableBlock): IrCall? = scan.calls.singleOrNull { call ->
        val declaration = call.symbol.owner
        scan.parentChainContains(call, wrapper) && declaration.parent === resultClass &&
                declaration.konanLibrary === stdlib && declaration.name.asString() == "<get-\$companion>" &&
                call.getArgumentsWithIr().none()
    }
    val successCompanionGetter = exactResultCompanionGetter(successResultWrapper)
        ?: return reject("success Result companion getter")
    val failureCompanionGetter = exactResultCompanionGetter(failureResultWrapper)
        ?: return reject("failure Result companion getter")
    val successCompanionTemporary = scan.variables.singleOrNull { variable ->
        scan.parentChainContains(variable, successResultWrapper) && variable !== successPayloadTemporary &&
                variable.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE_FOR_INLINED_PARAMETER &&
                variable.initializer?.containsIdentity(successCompanionGetter) == true &&
                scan.readsOf(variable).isEmpty()
    } ?: return reject("success Result companion temporary")
    val failureCompanionTemporary = scan.variables.singleOrNull { variable ->
        scan.parentChainContains(variable, failureResultWrapper) && variable !== failureOwnedResult &&
                variable.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE_FOR_INLINED_PARAMETER &&
                variable.initializer?.containsIdentity(failureCompanionGetter) == true &&
                scan.readsOf(variable).isEmpty()
    } ?: return reject("failure Result companion temporary")
    val successWrapperBlocks = scan.allBlocks.filter {
        it === successResultWrapper || scan.parentChainContains(it, successResultWrapper)
    }
    val failureWrapperBlocks = scan.allBlocks.filter {
        it === failureResultWrapper || scan.parentChainContains(it, failureResultWrapper)
    }
    val successComposites = scan.allElements.filterIsInstance<IrComposite>().filter {
        scan.parentChainContains(it, successResultWrapper)
    }
    val failureComposites = scan.allElements.filterIsInstance<IrComposite>().filter {
        scan.parentChainContains(it, failureResultWrapper)
    }
    if (successWrapperBlocks.size != 4 || failureWrapperBlocks.size != 4 ||
        successWrapperBlocks.count { it is IrInlinedFunctionBlock } != 1 ||
        failureWrapperBlocks.count { it is IrInlinedFunctionBlock } != 1 ||
        successComposites.size != 1 || failureComposites.size != 1
    ) return reject(
        "Result inline wrapper block topology=" +
                "${successWrapperBlocks.size}/${successWrapperBlocks.count { it is IrInlinedFunctionBlock }}/" +
                "${successComposites.size};" +
                "${failureWrapperBlocks.size}/${failureWrapperBlocks.count { it is IrInlinedFunctionBlock }}/" +
                failureComposites.size,
    )

    val currentBackedgeCast = base.currentBackedgeStore.value as? IrTypeOperatorCall
        ?: return reject("current backedge cast")
    val completionBackedgeRead = currentBackedgeCast.argument as? IrGetValue
        ?: return reject("current backedge source read")
    val outcomeBackedgeRead = base.parameterBackedgeStore.value as? IrGetValue
        ?: return reject("outcome backedge source read")
    if (currentBackedgeCast.operator != IrTypeOperator.IMPLICIT_CAST ||
        completionBackedgeRead.symbol != completionProjection.symbol ||
        outcomeBackedgeRead.symbol != outcomeJoin.symbol
    ) return reject("current backedge cast shape")
    val completionTypeTest = scan.typeOperators.singleOrNull {
        it.operator == IrTypeOperator.INSTANCEOF && it.typeOperand.getClass()?.symbol == owner.symbol &&
                it.argument.isExactReadOf(completionProjection)
    } ?: return reject("completion type test")
    val continuationClass = context.ir.symbols.continuationClass.owner
    val terminalResume = scan.calls.singleOrNull { call ->
        val declaration = call.symbol.owner
        declaration.name.asString() == "resumeWith" && declaration.parent === continuationClass &&
                call.dispatchReceiver.isExactReadOf(completionProjection) && call.valueArgumentsCount == 1 &&
                call.getValueArgument(0).isExactReadOf(outcomeJoin)
    } ?: return reject("terminal Continuation.resumeWith symbol")
    val continuationResumeWith = terminalResume.symbol.owner
    if (continuationClass.konanLibrary !== stdlib ||
        continuationClass.fqNameForIrSerialization.asString() != "kotlin.coroutines.Continuation" ||
        continuationClass.kind != ClassKind.INTERFACE ||
        continuationResumeWith.name.asString() != "resumeWith" ||
        continuationResumeWith.parent !== continuationClass || continuationResumeWith.konanLibrary !== stdlib ||
        continuationResumeWith.modality != Modality.ABSTRACT ||
        continuationResumeWith.dispatchReceiverParameter == null ||
        continuationResumeWith.extensionReceiverParameter != null ||
        continuationResumeWith.valueParameters.singleOrNull()?.type?.binaryTypeIsReference() != true ||
        continuationResumeWith.typeParameters.isNotEmpty() || !continuationResumeWith.returnType.isUnit() ||
        continuationResumeWith.isExternal || continuationResumeWith.isSuspend
    ) return reject("Continuation.resumeWith declaration ABI")
    val terminalBlock = scan.nearestBlock[terminalResume] ?: return reject("terminal block")
    val terminalIndex = terminalBlock.statements.indexOfFirst { it === terminalResume }
    val terminalReturn = terminalBlock.statements.getOrNull(terminalIndex + 1) as? IrReturn
        ?: return reject("terminal return adjacency")
    val terminalUnit = terminalReturn.value as? IrCall ?: return reject("terminal Unit result")
    if (terminalBlock.statements.size != 2 || terminalIndex != 0 ||
        terminalReturn.returnTargetSymbol != function.symbol ||
        terminalUnit.symbol != context.ir.symbols.theUnitInstance
    ) return reject("terminal call/return shape")

    val completionTypeBranch = scan.nearestAncestor<IrBranch>(completionTypeTest)
        ?.takeIf { it.condition === completionTypeTest }
        ?: return reject("completion type-test condition branch")
    val completionDispatch = scan.nearestAncestor<IrWhen>(completionTypeBranch)
        ?: return reject("completion dispatch when")
    if (completionDispatch.branches.size != 2 || completionDispatch.branches[0] !== completionTypeBranch) {
        return reject("completion dispatch branch order")
    }
    val terminalBranch = completionDispatch.branches[1]
    val backedgeBlock = completionTypeBranch.result as? IrBlock ?: return reject("backedge block")
    val loopBodyBlock = base.loop.body as? IrBlock ?: return reject("loop body block")
    if (backedgeBlock.statements.size != 2 || backedgeBlock.statements[0] !== base.currentBackedgeStore ||
        backedgeBlock.statements[1] !== base.parameterBackedgeStore ||
        !terminalBranch.condition.isTrueConstant() || terminalBranch.result !== terminalBlock ||
        !scan.parentChainContains(base.currentBackedgeStore, completionTypeBranch.result) ||
        !scan.parentChainContains(base.parameterBackedgeStore, completionTypeBranch.result) ||
        scan.parentChainContains(terminalResume, completionTypeBranch.result) ||
        scan.parentChainContains(base.currentBackedgeStore, terminalBranch.result) ||
        scan.parentChainContains(base.parameterBackedgeStore, terminalBranch.result)
    ) return reject("backedge/terminal dispatch topology")

    val iterationBlock = scan.nearestAncestor<IrBlock>(outcomeJoin)
        ?: return reject("iteration block")
    if (iterationBlock !== loopBodyBlock && !scan.parentChainContains(iterationBlock, loopBodyBlock)) {
        return reject("iteration block outside loop body")
    }
    if (scan.nearestBlock[base.releaseIntercepted] !== iterationBlock ||
        scan.nearestAncestor<IrBlock>(completionDispatch) !== iterationBlock
    ) return reject("outcome/release/dispatch block identity")
    val outcomeIndex = iterationBlock.statements.indexOfFirst { it === outcomeJoin }
    if (outcomeIndex < 0 || iterationBlock.statements.getOrNull(outcomeIndex + 1) !== base.releaseIntercepted ||
        iterationBlock.statements.getOrNull(outcomeIndex + 2) !== completionDispatch
    ) return reject("outcome/release/dispatch adjacency")

    val entryProbe = body.statements[0] as? IrCall ?: return reject("entry probe binding")
    val trailingReturn = body.statements[4] as? IrReturn ?: return reject("trailing return binding")
    val trailingUnit = trailingReturn.value as? IrCall ?: return reject("trailing Unit binding")
    if (trailingReturn.returnTargetSymbol != function.symbol ||
        trailingUnit.symbol != context.ir.symbols.theUnitInstance
    ) return reject("trailing return ABI")
    val coreAuthenticatedBlocks = (listOf(
        loopBodyBlock, iterationBlock, completionProjectionBlock, successArm, failureArm,
        backedgeBlock, terminalBlock,
    ) + successWrapperBlocks + failureWrapperBlocks).identityDistinct()
    val structuralBlocks = scan.allBlocks.filter { block -> coreAuthenticatedBlocks.none { it === block } }
    val structuralReturns = scan.returns.filter { returned ->
        val target = returned.returnTargetSymbol.owner as? IrReturnableBlock
        target != null && target !== successResultWrapper && target !== failureResultWrapper
    }
    val knownUnitCalls = listOf(suspendedUnit, terminalUnit, trailingUnit)
    val structuralUnitCalls = scan.calls.filter { call ->
        call.symbol == context.ir.symbols.theUnitInstance && knownUnitCalls.none { it === call }
    }
    if (structuralBlocks.size != 6 || structuralBlocks.count { it is IrReturnableBlock } != 3 ||
        structuralBlocks.count { it is IrInlinedFunctionBlock } != 2 || structuralReturns.size != 3 ||
        structuralUnitCalls.size != 2 || structuralUnitCalls.any { unit ->
            structuralReturns.none { returned -> returned.containsIdentity(unit) }
        } || structuralReturns.any { returned ->
            structuralUnitCalls.count { returned.containsIdentity(it) } != 1
        } || structuralUnitCalls.map { unit ->
            structuralReturns.count { returned -> returned.containsIdentity(unit) }
        }.sorted() != listOf(1, 2)
    ) return reject(
        "structural inline wrapper topology=" +
                "${structuralBlocks.size}/${structuralBlocks.count { it is IrReturnableBlock }}/" +
                "${structuralBlocks.count { it is IrInlinedFunctionBlock }};" +
                "returns=${structuralReturns.size};units=${structuralUnitCalls.size};values=" +
                structuralReturns.joinToString { returned ->
                    "${returned.value::class.simpleName}:" +
                            structuralUnitCalls.count { returned.containsIdentity(it) } + ":" +
                            returned.value.containsExactReadOf(base.current)
                },
    )
    val authenticatedBlocks = (coreAuthenticatedBlocks + structuralBlocks).identityDistinct()

    // `regionHasExactEffects` authenticates every ownership-relevant family below. The generic
    // visitor additionally records every IR element identity and rejects any class that this
    // selector does not understand. This closes the hole where a new wrapper/container kind could
    // be silently traversed while remaining absent from every typed effect list.
    if (scan.unhandledElements.isNotEmpty() ||
        scan.allElements.size != scan.allElements.identityDistinct().size
    ) return reject(
        "unhandled/duplicate body identities=" +
                "${scan.unhandledElements.map { it::class.simpleName }.distinct()}/" +
                "${scan.allElements.size - scan.allElements.identityDistinct().size}",
    )

    if (!scan.regionHasExactEffects(
            completionProjectionBlock,
            calls = listOf(completionNullComparison, completionNullReinterpret, completionNullThrow),
            fieldLoads = listOf(completionFieldLoad), returns = emptyList(), typeOperators = emptyList(),
            whens = listOf(completionNullWhen), branches = listOf(completionNullBranch),
            tries = emptyList(), catches = emptyList(),
        ) || !scan.regionHasExactEffects(
            outcomeTry,
            calls = listOf(
                base.invokeSuspend, suspendedComparison, suspendedGetter, suspendedUnit,
                successCompanionGetter, successResultConstructor, failureCompanionGetter,
                failureFactory, failureResultConstructor,
            ),
            fieldLoads = listOf(successIdentityForward, failureIdentityForward),
            returns = listOf(suspendedReturn, successInlineReturn, failureInlineReturn),
            typeOperators = emptyList(), whens = listOf(suspendedWhen), branches = listOf(suspendedBranch),
            tries = listOf(outcomeTry), catches = listOf(outcomeCatch),
            variables = listOf(
                invokeOwnedResult, successCompanionTemporary, successPayloadTemporary,
                caughtException, failureCompanionTemporary, failureOwnedResult,
            ),
            blocks = (listOf(successArm, failureArm) + successWrapperBlocks + failureWrapperBlocks).identityDistinct(),
        ) || !scan.regionHasExactEffects(
            completionDispatch,
            calls = listOf(terminalResume, terminalUnit), fieldLoads = emptyList(), returns = listOf(terminalReturn),
            typeOperators = listOf(completionTypeTest, currentBackedgeCast), whens = listOf(completionDispatch),
            branches = listOf(completionTypeBranch, terminalBranch), tries = emptyList(), catches = emptyList(),
            valueStores = listOf(base.currentBackedgeStore, base.parameterBackedgeStore),
        )
    ) return reject("completion/result effect census")

    if (!scan.regionHasExactEffects(
            body,
            calls = listOf(
                entryProbe, completionNullComparison, completionNullReinterpret, completionNullThrow,
                base.invokeSuspend, suspendedComparison, suspendedGetter, suspendedUnit,
                successCompanionGetter, successResultConstructor, failureCompanionGetter,
                failureFactory, failureResultConstructor, base.releaseIntercepted,
                terminalResume, terminalUnit, trailingUnit,
            ) + structuralUnitCalls,
            fieldLoads = listOf(completionFieldLoad, successIdentityForward, failureIdentityForward),
            returns = listOf(
                suspendedReturn, successInlineReturn, failureInlineReturn, terminalReturn, trailingReturn,
            ) + structuralReturns,
            typeOperators = listOf(currentBackedgeCast, completionTypeTest),
            whens = listOf(completionNullWhen, suspendedWhen, completionDispatch),
            branches = listOf(completionNullBranch, suspendedBranch, completionTypeBranch, terminalBranch),
            tries = listOf(outcomeTry), catches = listOf(outcomeCatch),
            valueStores = listOf(base.currentBackedgeStore, base.parameterBackedgeStore),
            loops = listOf(base.loop),
            variables = listOf(
                base.current, base.parameterState, base.currentIterationBorrow,
                completionNullableTemporary, completionProjection, outcomeJoin, invokeOwnedResult,
                successCompanionTemporary, successPayloadTemporary, caughtException,
                failureCompanionTemporary, failureOwnedResult,
            ),
            blocks = authenticatedBlocks,
        )
    ) return reject(
        "whole resumeWith effect census actual=" + listOf(
            scan.calls.size, scan.fieldLoads.size, scan.returns.size, scan.typeOperators.size,
            scan.whens.size, scan.branches.size, scan.tries.size, scan.catches.size,
            scan.valueStores.size, scan.loops.size, scan.variables.size, scan.allBlocks.size,
        ).joinToString("/") + "; typeops=" + scan.typeOperators.joinToString { it.operator.name } +
                "; calls=" + scan.calls.joinToString { it.symbol.owner.fqNameForIrSerialization.asString() } +
                "; returns=" + scan.returns.joinToString { it.returnTargetSymbol.owner::class.simpleName.orEmpty() } +
                "; blocks=" + scan.allBlocks.joinToString { it::class.simpleName.orEmpty() },
    )

    val completionReads = scan.readsOf(completionProjection)
    if (!completionReads.exactlyCoveredBy(
            completionTypeTest, base.currentBackedgeStore, terminalResume,
        )
    ) return reject("completion use census=${completionReads.size}")
    val outcomeReads = scan.readsOf(outcomeJoin)
    if (!outcomeReads.exactlyCoveredBy(base.parameterBackedgeStore, terminalResume)) {
        return reject("outcome use census=${outcomeReads.size}")
    }
    val invokeReads = scan.readsOf(invokeOwnedResult)
    if (!invokeReads.exactlyCoveredBy(suspendedComparison, successIdentityForward)) {
        return reject("invoke result census=${invokeReads.size}")
    }
    val failureReads = scan.readsOf(failureOwnedResult)
    if (failureReads.size != 2 || failureReads.any { !failureIdentityForward.containsIdentity(it) }) {
        return reject("failure result census=${failureReads.size}")
    }
    if (!successArm.containsIdentity(base.invokeSuspend) ||
        !successArm.containsIdentity(successIdentityForward) ||
        !failureArm.containsIdentity(failureFactory) ||
        !failureArm.containsIdentity(failureIdentityForward) ||
        !base.currentBackedgeStore.value.containsExactReadOf(completionProjection) ||
        !base.parameterBackedgeStore.value.isExactReadOf(outcomeJoin)
    ) return reject("producer/backedge identity chain")

    val primaryBindings = listOf<Any>(
        function, base, base.currentIterationBorrow, completionField, completionFieldLoad,
        completionNullableTemporary, completionProjection, base.invokeSuspend, invokeOwnedResult,
        suspendedComparison, suspendedReturn, successIdentityForward, caughtException, failureFactory,
        failureOwnedResult, outcomeJoin, base.releaseIntercepted, completionTypeTest,
        base.currentBackedgeStore, base.parameterBackedgeStore, terminalResume, terminalReturn,
        base.current, base.parameterState, currentBackedgeCast, completionBackedgeRead, outcomeBackedgeRead,
    )
    val externalDeclarations = listOf<Any>(
        owner, continuationClass, continuationResumeWith, resultClass, resultValueDeclaration,
        failureFactory.symbol.owner,
    )
    if (primaryBindings.identityDistinct().size != primaryBindings.size ||
        externalDeclarations.identityDistinct().size != externalDeclarations.size ||
        externalDeclarations.any { external -> primaryBindings.any { it === external } }
    ) return reject("primary/external identity alias")
    val remainingAuthenticatedBody = scan.allElements.filter { element ->
        primaryBindings.none { it === element } && externalDeclarations.none { it === element }
    }
    if (scan.allElements.any { element ->
            primaryBindings.none { it === element } && remainingAuthenticatedBody.none { it === element }
        }
    ) return reject("incomplete body identity inventory")

    val bindings = ArcCoroutineCompletionResultBindings<Any>(
        function = function,
        baseSelection = base,
        currentAnchor = base.currentIterationBorrow,
        completionField = completionField,
        completionFieldLoad = completionFieldLoad,
        completionNullableTemporary = completionNullableTemporary,
        completionProjection = completionProjection,
        invokeSuspend = base.invokeSuspend,
        invokeOwnedResult = invokeOwnedResult,
        suspendedComparison = suspendedComparison,
        suspendedReturn = suspendedReturn,
        successIdentityForward = successIdentityForward,
        caughtException = caughtException,
        failureFactory = failureFactory,
        failureOwnedResult = failureOwnedResult,
        outcomeJoin = outcomeJoin,
        releaseIntercepted = base.releaseIntercepted,
        completionTypeTest = completionTypeTest,
        currentBackedgeStore = base.currentBackedgeStore,
        outcomeBackedgeStore = base.parameterBackedgeStore,
        terminalResume = terminalResume,
        terminalReturn = terminalReturn,
        physicalMoveOperands = ArcCoroutineCompletionPhysicalMoveOperands(
            currentOwnerSlot = base.current,
            parameterOwnerSlot = base.parameterState,
            completionBackedgeValue = currentBackedgeCast,
            completionBackedgeRead = completionBackedgeRead,
            outcomeBackedgeRead = outcomeBackedgeRead,
        ),
        // Primary emitter operands are named fields above. Every other body node is retained in an
        // exhaustive identity inventory, while declarations outside the body are authenticated
        // separately. Any future emitter operand must be promoted out of this catch-all inventory.
        authenticatedIRIdentities = externalDeclarations + remainingAuthenticatedBody,
    )
    return adaptVerifiedCoroutineCompletionResultIR(
        bindings,
        mode,
        ArcCoroutineCompletionResultIRShape(
            exactCompletionProjection = true,
            exactCompletionNullCheck = true,
            exactSuccessIdentityChain = true,
            exactFailureIdentityChain = true,
            exactOutcomeJoin = true,
            exactSuspendedExit = true,
            exactTryCatchArmsAndEffects = true,
            exactDispatchTopology = true,
            exactContinuationResumeWithABI = true,
            exactReferenceResultCatchOwnershipABI = true,
            exactBackedgeTransfer = true,
            exactTerminalCallAndReturn = true,
            completeCompletionUseCensus = true,
            completeOutcomeUseCensus = true,
            completeProducerUseCensus = true,
            completeLoweredBodyWalk = true,
            completeIdentityInventory = true,
        ),
    ).selection ?: return reject("ownership proof")
}

/**
 * Production inventory for the proof-only completion/result selector.  Keep this traversal at the
 * ownership-planning boundary so the exact post-lowering stdlib body is checked in every optimized
 * ARC final binary, even though no physical ownership operation is emitted yet.
 */
internal fun selectVerifiedCoroutineCompletionResultWebs(
    generationState: NativeGenerationState,
    module: IrModuleFragment,
): List<ArcCoroutineCompletionResultSelection<Any>> {
    val selected = mutableListOf<ArcCoroutineCompletionResultSelection<Any>>()
    module.files.forEach { file ->
        file.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                // Avoid materializing the stdlib symbol graph for every unrelated declaration.
                if (declaration.name.asString() == "resumeWith") {
                    selectVerifiedCoroutineCompletionResultWeb(generationState, declaration)?.let { selection ->
                        generationState.context.log {
                            "ARC coroutine completion/result selected production function: " +
                                    declaration.fqNameForIrSerialization.asString() + "; emitted=false"
                        }
                        selected += selection
                    }
                }
                declaration.acceptChildrenVoid(this)
            }
        })
    }
    return selected
}

private class CompletionResultIRScan(private val function: IrFunction) : IrElementVisitorVoid {
    val allElements = mutableListOf<IrElement>()
    val unhandledElements = mutableListOf<IrElement>()
    val variables = mutableListOf<IrVariable>()
    val calls = mutableListOf<IrCall>()
    val constructorCalls = mutableListOf<IrConstructorCall>()
    val fieldLoads = mutableListOf<IrGetField>()
    val fieldStores = mutableListOf<IrSetField>()
    val valueStores = mutableListOf<IrSetValue>()
    val returns = mutableListOf<IrReturn>()
    val throws = mutableListOf<IrThrow>()
    val tries = mutableListOf<IrTry>()
    val catches = mutableListOf<IrCatch>()
    val whens = mutableListOf<IrWhen>()
    val branches = mutableListOf<IrBranch>()
    val allBlocks = mutableListOf<IrBlock>()
    val loops = mutableListOf<IrLoop>()
    val breaks = mutableListOf<IrBreak>()
    val continues = mutableListOf<IrContinue>()
    val suspendableExpressions = mutableListOf<IrSuspendableExpression>()
    val suspensionPoints = mutableListOf<IrSuspensionPoint>()
    val typeOperators = mutableListOf<IrTypeOperatorCall>()
    val nearestBlock = IdentityHashMap<IrElement, IrBlock>()
    private val reads = IdentityHashMap<IrVariable, MutableList<IrGetValue>>()
    private val parents = IdentityHashMap<IrElement, IrElement?>()
    private val blockStack = ArrayDeque<IrBlock>()
    private var parent: IrElement? = null
    var sawNestedFunction = false

    private fun record(element: IrElement) {
        allElements += element
        when (element) {
            is IrBlockBody, is IrBlock, is IrVariable, is IrCall, is IrConstructorCall,
            is IrGetField, is IrSetField, is IrSetValue, is IrGetValue, is IrReturn,
            is IrThrow, is IrTry, is IrCatch, is IrWhen, is IrBranch, is IrLoop,
            is IrBreak, is IrContinue, is IrSuspendableExpression, is IrSuspensionPoint,
            is IrTypeOperatorCall, is IrConst<*>, is IrComposite -> Unit
            else -> unhandledElements += element
        }
    }

    fun readsOf(variable: IrVariable): List<IrGetValue> = reads[variable].orEmpty()

    fun parentChainContains(element: IrElement, ancestor: IrElement): Boolean {
        var cursor = parents[element]
        while (cursor != null) {
            if (cursor === ancestor) return true
            cursor = parents[cursor]
        }
        return false
    }

    fun parentOf(element: IrElement): IrElement? = parents[element]

    inline fun <reified T : IrElement> nearestAncestor(element: IrElement): T? {
        var cursor = parents[element]
        while (cursor != null) {
            if (cursor is T) return cursor
            cursor = parents[cursor]
        }
        return null
    }

    override fun visitElement(element: IrElement) {
        record(element)
        val previous = parent
        parents[element] = previous
        parent = element
        element.acceptChildrenVoid(this)
        parent = previous
    }

    override fun visitFunction(declaration: IrFunction) {
        record(declaration)
        if (declaration !== function) {
            sawNestedFunction = true
            return
        }
        visitElement(declaration)
    }

    override fun visitBlock(expression: IrBlock) {
        record(expression)
        allBlocks += expression
        parents[expression] = parent
        val previous = parent
        parent = expression
        blockStack.addLast(expression)
        expression.acceptChildrenVoid(this)
        blockStack.removeLast()
        parent = previous
    }

    override fun visitVariable(declaration: IrVariable) {
        variables += declaration
        visitElement(declaration)
    }

    override fun visitCall(expression: IrCall) {
        calls += expression
        blockStack.lastOrNull()?.let { nearestBlock[expression] = it }
        visitElement(expression)
    }

    override fun visitConstructorCall(expression: IrConstructorCall) {
        constructorCalls += expression
        visitElement(expression)
    }

    override fun visitGetField(expression: IrGetField) {
        fieldLoads += expression
        visitElement(expression)
    }

    override fun visitSetField(expression: IrSetField) {
        fieldStores += expression
        visitElement(expression)
    }

    override fun visitSetValue(expression: IrSetValue) {
        valueStores += expression
        visitElement(expression)
    }

    override fun visitGetValue(expression: IrGetValue) {
        (expression.symbol.owner as? IrVariable)?.let { reads.getOrPut(it) { mutableListOf() } += expression }
        visitElement(expression)
    }

    override fun visitReturn(expression: IrReturn) {
        returns += expression
        visitElement(expression)
    }

    override fun visitThrow(expression: IrThrow) {
        throws += expression
        visitElement(expression)
    }

    override fun visitTry(aTry: IrTry) {
        tries += aTry
        visitElement(aTry)
    }

    override fun visitCatch(aCatch: IrCatch) {
        catches += aCatch
        visitElement(aCatch)
    }

    override fun visitWhen(expression: IrWhen) {
        whens += expression
        visitElement(expression)
    }

    override fun visitBranch(branch: IrBranch) {
        branches += branch
        visitElement(branch)
    }

    override fun visitLoop(loop: IrLoop) {
        loops += loop
        visitElement(loop)
    }

    override fun visitBreak(jump: IrBreak) {
        breaks += jump
        visitElement(jump)
    }

    override fun visitContinue(jump: IrContinue) {
        continues += jump
        visitElement(jump)
    }

    override fun visitSuspendableExpression(expression: IrSuspendableExpression) {
        suspendableExpressions += expression
        visitElement(expression)
    }

    override fun visitSuspensionPoint(expression: IrSuspensionPoint) {
        suspensionPoints += expression
        visitElement(expression)
    }

    override fun visitTypeOperator(expression: IrTypeOperatorCall) {
        typeOperators += expression
        visitElement(expression)
    }

    fun regionHasExactEffects(
        root: IrElement,
        calls: List<IrCall>,
        fieldLoads: List<IrGetField>,
        returns: List<IrReturn>,
        typeOperators: List<IrTypeOperatorCall>,
        whens: List<IrWhen>,
        branches: List<IrBranch>,
        tries: List<IrTry>,
        catches: List<IrCatch>,
        valueStores: List<IrSetValue> = emptyList(),
        loops: List<IrLoop> = emptyList(),
        variables: List<IrVariable>? = null,
        blocks: List<IrBlock>? = null,
    ): Boolean {
        fun IrElement.inRegion() = this === root || parentChainContains(this, root)
        return this.calls.filter { it.inRegion() }.identitySetEquals(calls) &&
                this.fieldLoads.filter { it.inRegion() }.identitySetEquals(fieldLoads) &&
                this.returns.filter { it.inRegion() }.identitySetEquals(returns) &&
                this.typeOperators.filter { it.inRegion() }.identitySetEquals(typeOperators) &&
                this.whens.filter { it.inRegion() }.identitySetEquals(whens) &&
                this.branches.filter { it.inRegion() }.identitySetEquals(branches) &&
                this.tries.filter { it.inRegion() }.identitySetEquals(tries) &&
                this.catches.filter { it.inRegion() }.identitySetEquals(catches) &&
                this.valueStores.filter { it.inRegion() }.identitySetEquals(valueStores) &&
                this.loops.filter { it.inRegion() }.identitySetEquals(loops) &&
                (variables == null || this.variables.filter { it.inRegion() }.identitySetEquals(variables)) &&
                (blocks == null || allBlocks.filter { it.inRegion() }.identitySetEquals(blocks)) &&
                constructorCalls.none { it.inRegion() } && fieldStores.none { it.inRegion() } &&
                throws.none { it.inRegion() } && breaks.none { it.inRegion() } &&
                continues.none { it.inRegion() } && suspendableExpressions.none { it.inRegion() } &&
                suspensionPoints.none { it.inRegion() } && unhandledElements.none { it.inRegion() }
    }
}

private fun <T : Any> List<T>.identitySetEquals(expected: List<T>): Boolean =
    size == expected.size && all { actual -> expected.count { it === actual } == 1 }

private fun <T : Any> List<T>.identityDistinct(): List<T> {
    val seen = java.util.Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    return filter { seen.add(it) }
}

private fun IrExpression.isNullConstant(): Boolean = this is IrConst<*> && value == null

private fun IrExpression.isTrueConstant(): Boolean = this is IrConst<*> && value == true

private fun IrCall.hasExactArguments(variable: IrVariable, other: IrExpression): Boolean {
    val arguments = listOfNotNull(getValueArgument(0), getValueArgument(1))
    return arguments.size == 2 && arguments.count { it.isExactReadOf(variable) } == 1 &&
            arguments.count { it === other } == 1
}

private fun IrExpression?.isExactReadOf(variable: IrVariable): Boolean =
    this is IrGetValue && symbol == variable.symbol

private fun IrExpression?.isExactReadOf(parameter: org.jetbrains.kotlin.ir.declarations.IrValueParameter): Boolean =
    this is IrGetValue && symbol == parameter.symbol

private fun IrElement.containsIdentity(target: IrElement): Boolean {
    var found = false
    acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            if (element === target) found = true else element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) = Unit
    })
    return found
}

private fun IrElement?.containsExactReadOf(variable: IrVariable): Boolean {
    val element = this ?: return false
    var found = false
    element.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) = Unit

        override fun visitGetValue(expression: IrGetValue) {
            if (expression.symbol == variable.symbol) found = true
            expression.acceptChildrenVoid(this)
        }
    })
    return found
}

private fun List<IrGetValue>.exactlyCoveredBy(vararg users: IrElement): Boolean =
    size == users.size && all { read -> users.count { it.containsIdentity(read) } == 1 }
