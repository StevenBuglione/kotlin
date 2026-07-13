/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.isFinalBinary
import org.jetbrains.kotlin.backend.konan.ir.konanLibrary
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isNullable
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isOverridable
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

internal data class ArcStringConcatenationRCIdentityEligibility(
    val arcEnabled: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val diagnosticsDisabled: Boolean,
    val nonSuspendFunction: Boolean,
)

internal fun ArcStringConcatenationRCIdentityEligibility.isAuthorized(): Boolean =
    arcEnabled && optimizationsEnabled && debugInfoDisabled && diagnosticsDisabled && nonSuspendFunction

internal data class ArcStringSelectiveInliningEligibility(
    val arcEnabled: Boolean,
    val linuxX64: Boolean,
    val finalBinary: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val diagnosticsDisabled: Boolean,
    val sanitizerDisabled: Boolean,
    val coverageDisabled: Boolean,
)

internal fun ArcStringSelectiveInliningEligibility.isAuthorized(): Boolean =
    arcEnabled && linuxX64 && finalBinary && optimizationsEnabled && debugInfoDisabled &&
            diagnosticsDisabled && sanitizerDisabled && coverageDisabled

internal data class ArcStringAppendInlineDeclarationEligibility(
    val loweringOwnedGroup: Boolean,
    val finalStdlibStringBuilder: Boolean,
    val exactAppendName: Boolean,
    val directMember: Boolean,
    val nonExternalNonSuspendFinal: Boolean,
    val returnsStringBuilder: Boolean,
    val singleNullableStringParameter: Boolean,
)

internal fun ArcStringAppendInlineDeclarationEligibility.isAuthorized(): Boolean =
    loweringOwnedGroup && finalStdlibStringBuilder && exactAppendName && directMember &&
            nonExternalNonSuspendFinal && returnsStringBuilder && singleNullableStringParameter

/**
 * Recovers the RC identity which is intentionally implicit in StringConcatenationLowering.
 *
 * That lowering creates one private StringBuilder temporary followed by direct `append` statements.
 * Application compilation cannot depend on deserialized stdlib bodies being available to prove
 * `append` returns `this`, but the lowering and the final StringBuilder symbols form a stronger,
 * compiler-owned contract. Keep this adapter separate from general user fluent-call discovery.
 */
internal fun selectVerifiedStringConcatenationRCIdentityGroups(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
): List<ArcDiscardedReturnedReceiverGroup> {
    val eligibility = ArcStringConcatenationRCIdentityEligibility(
        arcEnabled = generationState.context.memoryModel == MemoryModel.ARC,
        optimizationsEnabled = generationState.context.config.optimizationsEnabled,
        debugInfoDisabled = !generationState.context.shouldContainDebugInfo(),
        diagnosticsDisabled = !generationState.context.config.arcDiagnosticsEnabled,
        nonSuspendFunction = !function.isArcSuspendLike(),
    )
    if (!eligibility.isAuthorized()) return emptyList()

    val stringBuilder = generationState.context.ir.symbols.stringBuilder.owner
    if (stringBuilder.modality != Modality.FINAL) return emptyList()
    // ARC capacity planning may replace the lowering's no-arg construction with the exact
    // StringBuilder(Int) overload. Both still create the same private compiler-owned temporary.
    val defaultConstructor = stringBuilder.constructors.singleOrNull {
        it.valueParameters.isEmpty()
    }?.symbol ?: return emptyList()
    val capacityConstructor = stringBuilder.constructors.singleOrNull {
        it.valueParameters.singleOrNull()?.type?.isInt() == true
    }?.symbol
    val constructors = linkedSetOf(defaultConstructor).apply {
        capacityConstructor?.let(::add)
    }
    val appendSymbols = stringBuilder.functions.filterTo(linkedSetOf()) { callee ->
        callee.name.asString() == "append" && callee.valueParameters.size == 1 &&
                callee.extensionReceiverParameter == null && callee.dispatchReceiverParameter != null &&
                !callee.isExternal && !callee.isSuspend && callee.returnType.classifierOrNull == stringBuilder.symbol
    }.mapTo(linkedSetOf()) { it.symbol }
    if (appendSymbols.isEmpty()) return emptyList()
    val memberToString = generationState.context.ir.symbols.memberToString
    val terminalSymbols = stringBuilder.functions.filterTo(linkedSetOf()) { callee ->
        callee.name.asString() == "toString" && callee.valueParameters.isEmpty() &&
                callee.extensionReceiverParameter == null && callee.dispatchReceiverParameter != null
    }.mapTo(linkedSetOf()) { it.symbol }.apply { add(memberToString) }
    val result = mutableListOf<ArcDiscardedReturnedReceiverGroup>()

    function.body?.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: org.jetbrains.kotlin.ir.declarations.IrFunction) = Unit
        override fun visitDeclaration(declaration: IrDeclarationBase) = Unit
        override fun visitVariable(declaration: IrVariable) {
            declaration.initializer?.acceptVoid(this)
        }
        override fun visitTry(aTry: IrTry) = Unit

        override fun visitBlockBody(body: IrBlockBody) {
            result += selectExactLoweredBlock(
                body, body.statements, stringBuilder, constructors, appendSymbols, terminalSymbols
            )
            body.acceptChildrenVoid(this)
        }

        override fun visitContainerExpression(expression: IrContainerExpression) {
            result += selectExactLoweredBlock(
                expression, expression.statements, stringBuilder, constructors, appendSymbols, terminalSymbols
            )
            expression.acceptChildrenVoid(this)
        }
    })
    return result
}

/**
 * Selects the only calls whose bodies the private stdlib companion is allowed to provide.
 *
 * The enclosing group is already a compiler-lowering-owned linear StringBuilder web. This
 * second, deliberately exact filter admits only the final stdlib `append(String?)` declaration;
 * it is not a general symbol-name or fluent-call matcher.
 */
internal fun selectVerifiedStringConcatenationArcInlineCalls(
    generationState: NativeGenerationState,
    group: ArcDiscardedReturnedReceiverGroup,
): Set<IrCall> {
    // Dependency KLIB bodies can be materialized when caches are unavailable,
    // but they are not part of this final source compilation's authorization.
    val sourceFunction = group.receiver.parent as? IrSimpleFunction ?: return emptySet()
    if (sourceFunction.konanLibrary != null) return emptySet()
    val context = generationState.context
    val config = context.config
    val eligibility = ArcStringSelectiveInliningEligibility(
        arcEnabled = context.memoryModel == MemoryModel.ARC,
        linuxX64 = config.target == KonanTarget.LINUX_X64,
        finalBinary = config.isFinalBinary,
        optimizationsEnabled = config.optimizationsEnabled,
        debugInfoDisabled = !context.shouldContainAnyDebugInfo(),
        diagnosticsDisabled = !config.arcDiagnosticsEnabled,
        sanitizerDisabled = config.sanitizer == null && !config.undefinedBehaviorSanitizer,
        coverageDisabled = !generationState.coverage.enabled,
    )
    if (!eligibility.isAuthorized()) return emptySet()

    val stdlib = context.stdlibModule.konanLibrary ?: return emptySet()
    val stringBuilder = context.ir.symbols.stringBuilder.owner
    if (stringBuilder.modality != Modality.FINAL || stringBuilder.konanLibrary !== stdlib) return emptySet()
    val string = context.ir.symbols.string
    val exactAppend = stringBuilder.functions.singleOrNull { callee ->
        ArcStringAppendInlineDeclarationEligibility(
            loweringOwnedGroup = group.calls.isNotEmpty(),
            finalStdlibStringBuilder = stringBuilder.modality == Modality.FINAL &&
                    stringBuilder.konanLibrary === stdlib && callee.konanLibrary === stdlib,
            exactAppendName = callee.name.asString() == "append",
            directMember = callee.extensionReceiverParameter == null &&
                    callee.dispatchReceiverParameter != null && callee.parent === stringBuilder,
            nonExternalNonSuspendFinal = !callee.isExternal && !callee.isSuspend && !callee.isOverridable,
            returnsStringBuilder = callee.returnType.classifierOrNull == stringBuilder.symbol,
            singleNullableStringParameter = callee.valueParameters.size == 1 &&
                    callee.valueParameters.single().type.classifierOrNull == string &&
                    callee.valueParameters.single().type.isNullable(),
        ).isAuthorized()
    } ?: return emptySet()

    return group.calls.filterTo(linkedSetOf()) { call ->
        call.symbol === exactAppend.symbol && call.symbol.owner === exactAppend &&
                call.dispatchReceiver != null && call.extensionReceiver == null &&
                call.valueArgumentsCount == 1 && call.typeArgumentsCount == 0
    }
}

private fun selectExactLoweredBlock(
    block: IrElement,
    statements: List<IrStatement>,
    stringBuilder: IrClass,
    constructors: Set<IrConstructorSymbol>,
    appendSymbols: Set<IrSimpleFunctionSymbol>,
    terminalSymbols: Set<IrSimpleFunctionSymbol>,
): List<ArcDiscardedReturnedReceiverGroup> = statements.indices.flatMap { temporaryIndex ->
    val temporary = statements[temporaryIndex] as? IrVariable ?: return@flatMap emptyList()
    if (temporary.origin != IrDeclarationOrigin.IR_TEMPORARY_VARIABLE || temporary.isVar ||
        !temporary.initializer.isExactStringBuilderConstruction(constructors)
    ) {
        return@flatMap emptyList()
    }
    val terminal = (statements.lastOrNull() as? IrExpression)?.unwrapDiscardedCall()
        ?: return@flatMap emptyList()
    val terminalReceiver = terminal.dispatchReceiver as? IrGetValue ?: return@flatMap emptyList()
    if (terminalReceiver.symbol != temporary.symbol || terminal.symbol !in terminalSymbols) {
        return@flatMap emptyList()
    }

    val groups = mutableListOf<ArcDiscardedReturnedReceiverGroup>()
    val run = mutableListOf<IrCall>()
    fun finishRun() {
        if (run.size >= 2 && proveLinearStringBuilderRCIdentity(run.size)) {
            groups += ArcDiscardedReturnedReceiverGroup(block, temporary, run.toList())
        }
        run.clear()
    }
    statements.subList(temporaryIndex + 1, statements.lastIndex).forEach { statement ->
        val call = (statement as? IrExpression)?.unwrapDiscardedCall()
        val receiver = call?.dispatchReceiver as? IrGetValue
        val callee = call?.symbol?.owner
        if (receiver?.symbol == temporary.symbol && call.symbol in appendSymbols &&
            callee?.parent === stringBuilder && callee.valueParameters.size == 1 &&
            callee.extensionReceiverParameter == null &&
            (!callee.isOverridable || stringBuilder.modality == Modality.FINAL) &&
            call.type.classifierOrNull == stringBuilder.symbol
        ) {
            run += call
        } else {
            // Argument normalization can insert a returnable-block scaffold between appends.
            // It is an ownership/effect barrier: retain only the maximal linear web before it.
            finishRun()
        }
    }
    finishRun()
    groups
}

private fun IrExpression?.isExactStringBuilderConstruction(constructors: Set<IrConstructorSymbol>): Boolean {
    val call = this as? IrConstructorCall ?: return false
    return call.symbol in constructors
}

private fun IrExpression.unwrapDiscardedCall(): IrCall? = when (this) {
    is IrCall -> this
    is IrTypeOperatorCall ->
        if (operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) argument as? IrCall else null
    is IrContainerExpression ->
        (statements.singleOrNull() as? IrExpression)?.unwrapDiscardedCall()
    else -> null
}

/** Emission-independent proof used both by the selector and focused adversarial tests. */
internal fun proveLinearStringBuilderRCIdentity(callCount: Int): Boolean {
    if (callCount < 2) return false
    val block = ArcBlockId("string-concat")
    val root = ArcSSAValue("builder")
    val results = List(callCount) { ArcSSAValue("append-$it") }
    val operations = buildList {
        add(ArcSSAOperation.Introduce(root, ArcOwnership.Owned))
        results.forEach { result ->
            add(ArcSSAOperation.Use(root, ArcSSAUseKind.Borrow, mayThrow = true))
            add(ArcSSAOperation.Forward(root, result))
            add(ArcSSAOperation.Use(result, ArcSSAUseKind.Borrow))
        }
        add(ArcSSAOperation.Use(root, ArcSSAUseKind.Borrow, mayThrow = true))
    }
    val input = ArcOwnershipSSAInput(
        entry = block,
        blocks = mapOf(block to ArcSSABlock(block, operations)),
        edges = emptySet(),
    )
    val identities = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(input, emptyList()))
    if (identities.issues.isNotEmpty() || identities.identity(root)?.singleRoot != root) return false
    if (results.any { identities.identity(it)?.singleRoot != root }) return false
    if (identities.barriers.none { it.kind == ArcRCBarrierKind.ExceptionalCall }) return false
    if (identities.liveness.lifetimeFrontier(root).isEmpty()) return false
    // A throwing append/toString remains a hard motion barrier. The optimization only aliases the
    // normal-result slot; it never sinks a retain or hoists a release across this interval.
    return !identities.canMoveWithinBlock(root, block, 0, operations.lastIndex)
}
