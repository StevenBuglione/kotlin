/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.KonanFqNames
import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isNullable
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.getArgumentsWithIr
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isElseBranch
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The exact post-lowering Kotlin IR identities which authorized the first branch-phi web.
 *
 * This is intentionally not a general `if` matcher. Codegen may consume [semanticSelection] only
 * for these objects, and must retain ordinary owning-slot emission for every rejected function.
 */
internal data class ArcBranchGuaranteedPhiKotlinIRSelection(
    val function: IrSimpleFunction,
    val conditionParameter: IrValueParameter,
    val thenSourceParameter: IrValueParameter,
    val elseSourceParameter: IrValueParameter,
    val selectedVariable: IrVariable,
    val conditional: IrWhen,
    val thenBranch: IrBranch,
    val elseBranch: IrBranch,
    val thenStore: IrSetValue,
    val elseStore: IrSetValue,
    val conditionRead: IrGetValue,
    val thenSourceRead: IrGetValue,
    val elseSourceRead: IrGetValue,
    val terminalReturn: IrReturn,
    val identityEquality: IrCall,
    val selectedRead: IrGetValue,
    val comparisonRead: IrGetValue,
    val semanticSelection: ArcBranchGuaranteedPhiIRSelection<IrElement>,
)

/**
 * Authenticate the deliberately tiny first non-suspend multi-definition ownership web:
 *
 * ```
 * var selected: Payload? = null
 * if (flag) selected = left else selected = right
 * return selected === left // Either incoming parameter is accepted on the right.
 * ```
 *
 * Every fact supplied to [ArcBranchGuaranteedPhiIRAdapter] is derived here from IR object,
 * symbol, type, and parent identity. The final all-node seal rejects an otherwise similar body
 * containing any hidden cast, nested block/declaration, extra read/write, call, exceptional node,
 * capture, or control-flow construct.
 */
internal fun selectVerifiedBranchGuaranteedPhiWeb(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
): ArcBranchGuaranteedPhiKotlinIRSelection? {
    fun reject(reason: String): ArcBranchGuaranteedPhiKotlinIRSelection? {
        generationState.context.log {
            "ARC branch guaranteed-phi selector ${function.fqNameForIrSerialization.asString()}: $reason"
        }
        return null
    }

    val mode = ArcBranchGuaranteedPhiCompilationMode(
        arcEnabled = generationState.context.memoryModel == MemoryModel.ARC,
        optimizationsEnabled = generationState.context.config.optimizationsEnabled,
        debugInfoDisabled = !generationState.context.shouldContainDebugInfo(),
        diagnosticsDisabled = !generationState.context.config.arcDiagnosticsEnabled,
        nonSuspendFunction = !function.isArcSuspendLike(),
        nonExternalFunction = !function.isExternal,
    )
    if (!mode.arcEnabled || !mode.optimizationsEnabled || !mode.debugInfoDisabled ||
        !mode.diagnosticsDisabled || !mode.nonSuspendFunction || !mode.nonExternalFunction
    ) return null
    if (!function.returnType.isBoolean() || function.dispatchReceiverParameter != null ||
        function.extensionReceiverParameter != null || function.typeParameters.isNotEmpty() ||
        function.valueParameters.size != 3
    ) return reject("function ABI shape")

    val body = function.body as? IrBlockBody ?: return reject("block body")
    if (body.statements.size != 3) return reject("body statements=${body.statements.size}")
    val selected = body.statements[0] as? IrVariable ?: return reject("selected declaration")
    val conditional = body.statements[1] as? IrWhen ?: return reject("conditional")
    val terminalReturn = body.statements[2] as? IrReturn ?: return reject("terminal return")

    val nullInitializer = selected.initializer as? IrConst<*> ?: return reject("null initializer expression")
    val strongSelected = !selected.hasAnnotation(KonanFqNames.arcWeak) &&
            !selected.hasAnnotation(KonanFqNames.arcUnowned) &&
            !selected.hasAnnotation(KonanFqNames.volatile)
    if (selected.parent !== function || !selected.isVar || !selected.type.binaryTypeIsReference() ||
        !selected.type.isNullable() || nullInitializer.value != null || !strongSelected
    ) return reject("selected local shape")

    if (conditional.origin != IrStatementOrigin.IF || !conditional.type.isUnit() ||
        conditional.branches.size != 2
    ) return reject("direct two-arm if")
    val thenBranch = conditional.branches[0]
    val elseBranch = conditional.branches[1]
    if (isElseBranch(thenBranch) || !isElseBranch(elseBranch) ||
        (elseBranch.condition as? IrConst<*>)?.value != true
    ) return reject("then/else coverage")
    val conditionRead = thenBranch.condition as? IrGetValue ?: return reject("direct condition read")
    val conditionParameter = conditionRead.symbol.owner as? IrValueParameter ?: return reject("condition parameter")
    if (conditionParameter.parent !== function || !conditionParameter.type.isBoolean() ||
        conditionRead.type != conditionParameter.type
    ) return reject("Boolean condition identity")

    val thenStore = thenBranch.result as? IrSetValue ?: return reject("direct then store")
    val elseStore = elseBranch.result as? IrSetValue ?: return reject("direct else store")
    if (!thenStore.type.isUnit() || !elseStore.type.isUnit()) return reject("Unit branch stores")
    val thenSourceRead = thenStore.value as? IrGetValue ?: return reject("direct then source read")
    val elseSourceRead = elseStore.value as? IrGetValue ?: return reject("direct else source read")
    val thenSourceParameter = thenSourceRead.symbol.owner as? IrValueParameter ?: return reject("then source parameter")
    val elseSourceParameter = elseSourceRead.symbol.owner as? IrValueParameter ?: return reject("else source parameter")
    if (thenStore.symbol != selected.symbol || elseStore.symbol != selected.symbol ||
        thenSourceParameter.parent !== function || elseSourceParameter.parent !== function ||
        thenSourceParameter === elseSourceParameter || conditionParameter === thenSourceParameter ||
        conditionParameter === elseSourceParameter ||
        !thenSourceParameter.type.binaryTypeIsReference() || thenSourceParameter.type.isNullable() ||
        !elseSourceParameter.type.binaryTypeIsReference() || elseSourceParameter.type.isNullable() ||
        thenSourceRead.type != thenSourceParameter.type || elseSourceRead.type != elseSourceParameter.type ||
        thenSourceParameter.hasArcOwnershipAnnotation() || elseSourceParameter.hasArcOwnershipAnnotation()
    ) return reject("incoming source identity/type")
    val exactParameters = identitySetOf(conditionParameter, thenSourceParameter, elseSourceParameter)
    if (exactParameters.size != 3 || function.valueParameters.any { it !in exactParameters }) {
        return reject("complete parameter coverage")
    }

    if (terminalReturn.returnTargetSymbol != function.symbol) return reject("return target")
    val equality = terminalReturn.value as? IrCall ?: return reject("terminal identity equality")
    if (equality.symbol != generationState.context.irBuiltIns.eqeqeqSymbol ||
        !equality.type.isBoolean() || equality.dispatchReceiver != null || equality.extensionReceiver != null
    ) return reject("eqeqeq identity")
    val equalityArguments = equality.getArgumentsWithIr().map { it.second }
    if (equalityArguments.size != 2) return reject("eqeqeq arguments=${equalityArguments.size}")
    val selectedRead = equalityArguments[0] as? IrGetValue ?: return reject("selected terminal read")
    val comparisonRead = equalityArguments[1] as? IrGetValue ?: return reject("comparison terminal read")
    val comparisonParameter = comparisonRead.symbol.owner as? IrValueParameter ?: return reject("comparison parameter")
    if (selectedRead.symbol != selected.symbol ||
        (comparisonParameter !== thenSourceParameter && comparisonParameter !== elseSourceParameter) ||
        comparisonRead.symbol != comparisonParameter.symbol
    ) return reject("terminal identity relationship")

    // This exact identity inventory is the complete effect/use walk. Since every runtime IR node
    // is sealed, there is no caller-controlled "pure" bit and no unclassified nested operation.
    val expectedElements = identitySetOf<IrElement>(
        body,
        selected,
        nullInitializer,
        conditional,
        thenBranch,
        conditionRead,
        thenStore,
        thenSourceRead,
        elseBranch,
        elseBranch.condition,
        elseStore,
        elseSourceRead,
        terminalReturn,
        equality,
        selectedRead,
        comparisonRead,
    )
    if (expectedElements.size != 16) return reject("overlapping syntactic roles")
    val observedElements = Collections.newSetFromMap(IdentityHashMap<IrElement, Boolean>())
    val observedDeclarations = Collections.newSetFromMap(IdentityHashMap<IrDeclaration, Boolean>())
    val observedExpressions = Collections.newSetFromMap(IdentityHashMap<IrExpression, Boolean>())
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            observedElements += element
            if (element is IrDeclaration) observedDeclarations += element
            if (element is IrExpression) observedExpressions += element
            element.acceptChildrenVoid(this)
        }
    })
    val expectedExpressions = expectedElements.filterIsInstanceTo(
        Collections.newSetFromMap(IdentityHashMap<IrExpression, Boolean>())
    )
    if (!observedElements.sameIdentityMembers(expectedElements) ||
        !observedDeclarations.sameIdentityMembers(identitySetOf(selected)) ||
        !observedExpressions.sameIdentityMembers(expectedExpressions)
    ) return reject(
        "unclassified body node: elements=${observedElements.size}/${expectedElements.size}, " +
                "declarations=${observedDeclarations.size}/1, expressions=${observedExpressions.size}/${expectedExpressions.size}",
    )

    val thenParameter = ArcBranchGuaranteedPhiParameter<IrElement>(
        declarationBinding = thenSourceParameter,
        isReference = thenSourceParameter.type.binaryTypeIsReference(),
        isNullable = thenSourceParameter.type.isNullable(),
    )
    val elseParameter = ArcBranchGuaranteedPhiParameter<IrElement>(
        declarationBinding = elseSourceParameter,
        isReference = elseSourceParameter.type.binaryTypeIsReference(),
        isNullable = elseSourceParameter.type.isNullable(),
    )
    val loweredBody = ArcBranchGuaranteedPhiLoweredBody<IrElement>(
        functionBinding = function,
        mode = mode,
        variable = ArcBranchGuaranteedPhiVariable(
            declarationBinding = selected,
            nullInitializerBinding = nullInitializer,
            initializerIsExactNull = nullInitializer.value == null,
            isMutable = selected.isVar,
            isNullableReference = selected.type.binaryTypeIsReference() && selected.type.isNullable(),
            isStrong = strongSelected,
        ),
        diamond = ArcBranchGuaranteedPhiDiamond(
            expressionBinding = conditional,
            conditionReadBinding = conditionRead,
            conditionParameterBinding = conditionParameter,
            conditionIsBoolean = conditionRead.type.isBoolean(),
            conditionIsDirectParameterRead = conditionRead.symbol == conditionParameter.symbol,
            arms = listOf(
                ArcBranchGuaranteedPhiArm(
                    kind = ArcBranchGuaranteedPhiArmKind.Then,
                    branchBinding = thenBranch,
                    storeBinding = thenStore,
                    sourceReadBinding = thenSourceRead,
                    sourceParameter = thenParameter,
                    storeTargetsSelectedVariable = thenStore.symbol == selected.symbol,
                    sourceReadTargetsParameter = thenSourceRead.symbol == thenSourceParameter.symbol,
                    directToMerge = thenBranch.result === thenStore,
                ),
                ArcBranchGuaranteedPhiArm(
                    kind = ArcBranchGuaranteedPhiArmKind.Else,
                    branchBinding = elseBranch,
                    storeBinding = elseStore,
                    sourceReadBinding = elseSourceRead,
                    sourceParameter = elseParameter,
                    storeTargetsSelectedVariable = elseStore.symbol == selected.symbol,
                    sourceReadTargetsParameter = elseSourceRead.symbol == elseSourceParameter.symbol,
                    directToMerge = elseBranch.result === elseStore,
                ),
            ),
            entryBranchesDirectlyToArms = conditional.branches[0] === thenBranch &&
                    conditional.branches[1] === elseBranch,
            hasSingleMerge = body.statements[2] === terminalReturn,
        ),
        terminalUse = ArcBranchGuaranteedPhiTerminalUse(
            exitBinding = terminalReturn,
            equalityBinding = equality,
            selectedReadBinding = selectedRead,
            comparisonReadBinding = comparisonRead,
            comparisonParameterBinding = comparisonParameter,
            selectedReadTargetsVariable = selectedRead.symbol == selected.symbol,
            comparisonReadTargetsParameter = comparisonRead.symbol == comparisonParameter.symbol,
            isSolePostMergeUse = observedExpressions.count {
                it is IrGetValue && it.symbol == selected.symbol
            } == 1,
            isReferenceIdentityEquality = equality.symbol == generationState.context.irBuiltIns.eqeqeqSymbol,
            isNonThrowing = equality.symbol == generationState.context.irBuiltIns.eqeqeqSymbol,
        ),
        unsupportedEffects = emptySet(),
        completeLoweredIRWalk = observedElements.sameIdentityMembers(expectedElements),
    )
    val adapted = ArcBranchGuaranteedPhiIRAdapter.adapt(loweredBody)
    val semanticSelection = adapted.selection ?: return reject("semantic adapter: ${adapted.rejections}")
    return ArcBranchGuaranteedPhiKotlinIRSelection(
        function,
        conditionParameter,
        thenSourceParameter,
        elseSourceParameter,
        selected,
        conditional,
        thenBranch,
        elseBranch,
        thenStore,
        elseStore,
        conditionRead,
        thenSourceRead,
        elseSourceRead,
        terminalReturn,
        equality,
        selectedRead,
        comparisonRead,
        semanticSelection,
    )
}

private fun IrValueParameter.hasArcOwnershipAnnotation(): Boolean =
    hasAnnotation(KonanFqNames.arcWeak) || hasAnnotation(KonanFqNames.arcUnowned) ||
            hasAnnotation(KonanFqNames.volatile)

private fun <T : Any> identitySetOf(vararg elements: T): MutableSet<T> =
    Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).apply { addAll(elements) }

private fun <T : Any> Set<T>.sameIdentityMembers(other: Set<T>): Boolean =
    size == other.size && all { element -> other.any { it === element } }
