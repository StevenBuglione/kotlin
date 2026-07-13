/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.KonanFqNames
import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.backend.konan.ir.konanLibrary
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.allParameters
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.name.Name
import java.util.IdentityHashMap

/** The exact mutable `param` read borrowed by BaseContinuationImpl's virtual invokeSuspend call. */
internal data class ArcBaseContinuationParamBorrowPlan(
    val function: IrSimpleFunction,
    val paramVariable: IrVariable,
    val invokeSuspendCall: IrCall,
    val borrowedRead: IrGetValue,
)

/**
 * Fail-closed authorization for the one virtual call that is safe despite the general mutable-read
 * selector rejecting virtual and throwing calls. The mutable stack slot remains the +1 owner until
 * the call has completed normally or transferred control to the function's exception handler.
 */
internal data class ArcBaseContinuationParamBorrowEligibility(
    val arcEnabled: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val diagnosticsDisabled: Boolean,
    val linuxX64: Boolean,
    val exactStdlibFunctionIdentity: Boolean,
    val finalNonExternalUnitFunction: Boolean,
    val exactSingleLoop: Boolean,
    val exactStrongMutableParamRoot: Boolean,
    val rootDominatesCall: Boolean,
    val exactVirtualInvokeSuspendCall: Boolean,
    val exactSingleReferenceArgumentRead: Boolean,
    val noArgumentSuffix: Boolean,
    val noInterveningParamWrite: Boolean,
    val rootLivesOnNormalAndExceptionalEdges: Boolean,
    val loweredNonForeignCall: Boolean,
)

internal fun ArcBaseContinuationParamBorrowEligibility.isAuthorized(): Boolean =
    arcEnabled && optimizationsEnabled && debugInfoDisabled && diagnosticsDisabled &&
            linuxX64 && exactStdlibFunctionIdentity && finalNonExternalUnitFunction && exactSingleLoop &&
            exactStrongMutableParamRoot && rootDominatesCall && exactVirtualInvokeSuspendCall &&
            exactSingleReferenceArgumentRead && noArgumentSuffix && noInterveningParamWrite &&
            rootLivesOnNormalAndExceptionalEdges && loweredNonForeignCall

/**
 * Bind to Kotlin/Native 1.9.10's canonical stdlib body and exact post-lowering identities. This is
 * intentionally separate from the general call-argument borrow: invokeSuspend is virtual and may
 * throw, but neither fact can invalidate the +1 in `param` because the callee receives only its
 * loaded pointer and every unwind edge is still covered by the enclosing ARC frame.
 */
internal fun selectVerifiedBaseContinuationParamBorrow(
    generationState: NativeGenerationState,
): ArcBaseContinuationParamBorrowPlan? {
    val context = generationState.context
    val base = context.ir.symbols.baseContinuationImpl.owner
    val stdlib = context.stdlibModule.konanLibrary ?: return null
    val function = base.declarations.filterIsInstance<IrSimpleFunction>().singleOrNull {
        it.name == Name.identifier("resumeWith")
    } ?: return null
    val body = function.body ?: return null

    val variables = mutableListOf<IrVariable>()
    val calls = mutableListOf<IrCall>()
    val assignments = mutableListOf<IrSetValue>()
    val variableOrder = IdentityHashMap<IrVariable, Int>()
    val callOrder = IdentityHashMap<IrCall, Int>()
    val assignmentOrder = IdentityHashMap<IrSetValue, Int>()
    var visitationOrder = 0
    var loops = 0
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

        override fun visitFunction(declaration: IrFunction) {
            if (declaration === function) declaration.acceptChildrenVoid(this)
        }

        override fun visitVariable(declaration: IrVariable) {
            variableOrder[declaration] = visitationOrder++
            variables += declaration
            declaration.acceptChildrenVoid(this)
        }

        override fun visitCall(expression: IrCall) {
            callOrder[expression] = visitationOrder++
            calls += expression
            expression.acceptChildrenVoid(this)
        }

        override fun visitSetValue(expression: IrSetValue) {
            assignmentOrder[expression] = visitationOrder++
            assignments += expression
            expression.acceptChildrenVoid(this)
        }

        override fun visitWhileLoop(loop: IrWhileLoop) {
            loops++
            loop.acceptChildrenVoid(this)
        }
    })

    val param = variables.singleOrNull { it.name.asString() == "param" } ?: return null
    val resultParameter = function.valueParameters.singleOrNull() ?: return null
    val invokeSuspendSymbol = context.ir.symbols.invokeSuspendFunction
    val invokeCalls = calls.filter { it.symbol == invokeSuspendSymbol }
    val call = invokeCalls.singleOrNull() ?: return null
    if (call.valueArgumentsCount != 1) return null
    val argument = call.getValueArgument(0) ?: return null
    val read = argument.exactParamRead() ?: return null
    val paramWrites = assignments.filter { it.symbol.owner === param }

    // Preorder identities are stable after lowering even when deserialized stdlib declarations have
    // synthetic source offsets. Together with the exact one-loop/one-write shape they prove that the
    // root is established before the call and is not replaced until its loop backedge.
    val paramOrder = variableOrder[param] ?: return null
    val invokeOrder = callOrder[call] ?: return null
    val backedgeOrder = paramWrites.singleOrNull()?.let(assignmentOrder::get) ?: return null
    val rootDominatesCall = paramOrder < invokeOrder
    val backedgeAfterCall = invokeOrder < backedgeOrder

    val eligibility = ArcBaseContinuationParamBorrowEligibility(
        arcEnabled = context.memoryModel == MemoryModel.ARC,
        optimizationsEnabled = context.config.optimizationsEnabled,
        debugInfoDisabled = !context.shouldContainDebugInfo(),
        diagnosticsDisabled = !context.config.arcDiagnosticsEnabled,
        linuxX64 = context.config.target == KonanTarget.LINUX_X64,
        exactStdlibFunctionIdentity = function.parent === base && function.konanLibrary === stdlib &&
                base.konanLibrary === stdlib && function.body === body,
        finalNonExternalUnitFunction = function.modality == Modality.FINAL && !function.isExternal &&
                !function.isSuspend && function.returnType.isUnit() && function.allParameters.size == 2,
        exactSingleLoop = loops == 1,
        exactStrongMutableParamRoot = param.isVar && param.type.binaryTypeIsReference() &&
                param.initializer?.exactValueRead() === resultParameter &&
                !param.hasAnnotation(KonanFqNames.arcWeak) && !param.hasAnnotation(KonanFqNames.arcUnowned) &&
                !param.hasAnnotation(KonanFqNames.volatile),
        rootDominatesCall = rootDominatesCall,
        exactVirtualInvokeSuspendCall = invokeCalls.size == 1 && call.symbol == invokeSuspendSymbol &&
                call.superQualifierSymbol == null && invokeSuspendSymbol.owner.modality != Modality.FINAL,
        exactSingleReferenceArgumentRead = read.symbol.owner === param &&
                invokeSuspendSymbol.owner.valueParameters.singleOrNull()?.type?.binaryTypeIsReference() == true,
        noArgumentSuffix = call.valueArgumentsCount == 1,
        noInterveningParamWrite = paramWrites.size == 1 && backedgeAfterCall,
        rootLivesOnNormalAndExceptionalEdges = param.parent === function && paramWrites.size == 1,
        loweredNonForeignCall = !invokeSuspendSymbol.owner.isExternal && !invokeSuspendSymbol.owner.isSuspend,
    )
    return if (eligibility.isAuthorized()) {
        ArcBaseContinuationParamBorrowPlan(function, param, call, read)
    } else {
        null
    }
}

private fun IrExpression.exactParamRead(): IrGetValue? = when (this) {
    is IrGetValue -> this
    is IrTypeOperatorCall -> when (operator) {
        IrTypeOperator.IMPLICIT_CAST,
        IrTypeOperator.IMPLICIT_NOTNULL,
        IrTypeOperator.REINTERPRET_CAST -> argument.exactParamRead()
        else -> null
    }
    else -> null
}

private fun IrExpression.exactValueRead(): org.jetbrains.kotlin.ir.declarations.IrValueDeclaration? = when (this) {
    is IrGetValue -> symbol.owner
    is IrTypeOperatorCall -> when (operator) {
        IrTypeOperator.IMPLICIT_CAST,
        IrTypeOperator.IMPLICIT_NOTNULL,
        IrTypeOperator.REINTERPRET_CAST -> argument.exactValueRead()
        else -> null
    }
    else -> null
}
