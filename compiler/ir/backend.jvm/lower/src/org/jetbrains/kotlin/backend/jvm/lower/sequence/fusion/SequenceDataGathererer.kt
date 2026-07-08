/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion

import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

private const val SEQUENCE_OF = "sequenceOf"
private const val AS_SEQUENCE = "asSequence"
private const val GENERATE_SEQUENCE = "generateSequence"
private const val SEQUENCE = "sequence"
internal const val MAP = "map"
internal const val MAP_INDEXED = "mapIndexed"
internal const val MAP_NOT_NULL = "mapNotNull"
internal const val MAP_NOT_NULL_INDEXED = "mapIndexedNotNull"
internal const val FILTER = "filter"
internal const val FILTER_NOT = "filterNot"
internal const val FILTER_NOT_NULL = "filterNotNull"
internal const val TAKE = "take"

// this is stored for expressions, intended to be passed either to value declarations or to for loops iterated over the expression result
internal var IrExpression.sequenceDataOfExpression: SequenceData? by irAttribute(true)

// this is stored to be one of the future sources of sequence data of expressions
internal var IrValueDeclaration.sequenceDataOfVariable: SequenceData? by irAttribute(true)
// In general, sequence data is gathered from `sequenceOf` or existing sequence variables, modified `by` map calls,
// and consumed by for loops and variable declarations

internal sealed class FilterVersion {
    object Filter : FilterVersion()
    object FilterNot : FilterVersion()
    object FilterNotNull : FilterVersion()
}

private fun isSafeToLowerFromSequenceOf(expression: IrExpression): Boolean {
    if (containsMutable(expression)) return false
    if (!expression.isSafeToMove()) return false // skip lowering if an expression contains something that has to be evaluated only once
    return true
}

internal fun gatherVarargArgument(argument: IrExpression): List<IrExpression>? {
    return if (argument is IrVararg) {
        // argument is vararg arguments
        if (argument.elements.any { it is IrSpreadElement }) return null // skip lowering sequenceOf with spread arguments
        if (argument.elements.any { !isSafeToLowerFromSequenceOf(it as IrExpression) }) return null
        argument.elements.map { it as IrExpression }
    } else {
        // single argument
        if (!isSafeToLowerFromSequenceOf(argument)) return null
        listOf(argument)
    }
}

private fun isSafeToLower(reference: IrRichFunctionReference): Boolean {
    if (reference.boundValues.isNotEmpty()) return false
    if (reference.invokeFunction.dispatchReceiverParameter != null) return false
    return true
}

private fun isSafeToLower(expression: IrExpression): Boolean {
    if (containsMutable(expression)) return false
    when (expression) {
        is IrRichFunctionReference -> {
            return isSafeToLower(expression)
        }
    }
    return true
}

private fun IrExpression.isSafeToMove(): Boolean {
    var safe = true
    this.acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            if (safe) element.acceptChildrenVoid(this)
        }

        override fun visitCall(expression: IrCall) {
            if (!expression.isPrimitiveIntrinsic()) {
                safe = false
            }
            super.visitCall(expression)
        }

        override fun visitSetValue(expression: IrSetValue) {
            safe = false
            super.visitSetValue(expression)
        }

        override fun visitSetField(expression: IrSetField) {
            safe = false
            super.visitSetField(expression)
        }

        override fun visitGetValue(expression: IrGetValue) {
            val owner = expression.symbol.owner
            if (owner is IrVariable && owner.isVar) safe = false
        }

        override fun visitConst(expression: IrConst) {}
    })
    return safe
}

private fun IrCall.isPrimitiveIntrinsic(): Boolean {
    val owner = symbol.owner

    val parentClass = owner.parent as? IrClass ?: return false
    return parentClass.defaultType.isPrimitiveType() || parentClass.defaultType.isString()
}

private fun containsMutable(expression: IrExpression): Boolean {
    var found = false
    expression.acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            if (!found) {
                element.acceptChildrenVoid(this)
            }
        }

        override fun visitGetValue(expression: IrGetValue) {
            val variable = expression.symbol.owner as? IrVariable ?: return
            if (variable.isVar) {
                found = true
            }
        }
    })
    return found
}

internal class SequenceDataGatherer(val context: JvmBackendContext) : IrVisitorVoid() {
    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitVariable(declaration: IrVariable) {
        super.visitVariable(declaration)
        if (declaration.isVar) return
        if (!isElementSequence(context, declaration)) return
        val expressionSequenceData = declaration.initializer?.sequenceDataOfExpression
        declaration.symbol.owner.sequenceDataOfVariable = if (expressionSequenceData?.sequenceSource is SequenceSource.GenerateSequence &&
            expressionSequenceData.sequenceSource.initialValue is GenerateSequenceInitialValue.NoInitialValue &&
            (declaration.usageCounter ?: 0) > 1
        ) {
            SequenceData(
                SequenceSource.Variable(declaration.symbol),
                emptyList()
            )
        } else {
            expressionSequenceData
        }
    }

    // assigns sequence data of the variable to the corresponding expression
    override fun visitGetValue(expression: IrGetValue) {
        super.visitGetValue(expression)
        // now the children have assigned appropriate sequence data
        if (!isElementSequence(context, expression)) return
        val variableDeclaration = expression.symbol.owner
        variableDeclaration.accept(this, null)
        expression.sequenceDataOfExpression = variableDeclaration.sequenceDataOfVariable ?: SequenceData(
            SequenceSource.Variable(expression.symbol),
            emptyList()
        )
    }

    private fun matchWithMap(
        expression: IrCall,
        isIndexed: Boolean,
        isNotNull: Boolean,
    ) {
        val receiver = expression.arguments.getOrNull(0) ?: return
        val receiverData = receiver.sequenceDataOfExpression ?: return
        val fnArg = getPredicateArgument(expression, 1) ?: return
        if (fnArg is IrCall) return
        val invokeSymbol = fnArg.type.classOrNull?.owner?.declarations
            ?.filterIsInstance<IrSimpleFunction>()
            ?.first { it.name.asString() == "invoke" }?.symbol
        val nonIndexedPredicateCall: MapPredicate = { builderWithParent ->
            val builder = builderWithParent.first
            val parent = builderWithParent.second
            when (fnArg) {
                is IrRichFunctionReference ->
                    { sequenceElement ->
                        builder.callRichFunctionReference(fnArg.deepCopyWithSymbols(parent), parent, builder.irGet(sequenceElement))
                    }
                else ->
                    { sequenceElement ->
                        invokeSymbol?.let {
                            builder.irCall(it).apply {
                                dispatchReceiver = fnArg.deepCopyWithSymbols(parent)
                                arguments[1] = builder.irGet(sequenceElement)
                            }
                        }
                            ?: error("Didn't find invoke for the predicate argument of map: ${fnArg.dump()}")
                    }
            }
        }
        val indexedPredicateCall: MapIndexedPredicate = { builderWithParent ->
            val builder = builderWithParent.first
            val parent = builderWithParent.second
            when (fnArg) {
                is IrRichFunctionReference ->
                    { indexVariable, sequenceElement ->
                        builder.callRichFunctionReference(
                            fnArg.deepCopyWithSymbols(parent),
                            parent,
                            builder.irGet(indexVariable),
                            builder.irGet(sequenceElement)
                        )
                    }
                else ->
                    { indexVariable, sequenceElement ->
                        invokeSymbol?.let {
                            builder.irCall(it).apply {
                                dispatchReceiver = fnArg.deepCopyWithSymbols(parent)
                                arguments[1] = builder.irGet(indexVariable)
                                arguments[2] = builder.irGet(sequenceElement)
                            }
                        }
                            ?: error("Didn't find invoke for the predicate argument of map: ${fnArg.dump()}")
                    }
            }
        }
        val predicateCall = if (isIndexed) {
            MapPredicateCall.Indexed(indexedPredicateCall)
        } else {
            MapPredicateCall.NonIndexed(nonIndexedPredicateCall)
        }
        val transformers = listOf(
            SequenceTransformer.Map(
                predicateCall,
                isIndexed,
                isNotNull,
                expression.startOffset,
                expression.endOffset
            )
        ) + receiverData.transformers
        expression.sequenceDataOfExpression = SequenceData(receiverData.sequenceSource, transformers)
    }

    private fun matchWithTake(
        call: IrCall,
    ) {
        val receiver = call.arguments.getOrNull(0) ?: return
        val argumentExpression = call.arguments.getOrNull(1) ?: return
        val receiverData = receiver.sequenceDataOfExpression ?: return
        if (!isSafeToLower(argumentExpression)) return
        val transformers =
            listOf(SequenceTransformer.Take(argumentExpression, call.startOffset, call.endOffset)) + receiverData.transformers
        call.sequenceDataOfExpression = SequenceData(receiverData.sequenceSource, transformers)
    }

    private fun matchWithGenerateSequence(expression: IrCall) {
        val results = when (expression.arguments.size) {
            1 -> {
                // generateSequence(() -> T?)
                val func = expression.arguments.getOrNull(0) as? IrRichFunctionReference ?: return
                GenerateSequenceInitialValue.NoInitialValue to func
            }
            2 -> {
                val initialValueOrFunction = expression.arguments.getOrNull(0)
                val func = expression.arguments.getOrNull(1) as? IrRichFunctionReference ?: return
                when (initialValueOrFunction) {
                    is IrRichFunctionReference -> {
                        // generateSequence(() -> T?, (T) -> T?)
                        GenerateSequenceInitialValue.InitialFunction(initialValueOrFunction) to func
                    }
                    else -> {
                        // generateSequence(T?, (T) -> T?)
                        if (initialValueOrFunction == null) return
                        if (!isSafeToLower(initialValueOrFunction)) return
                        GenerateSequenceInitialValue.InitialValue(initialValueOrFunction) to func
                    }
                }
            }
            else -> {
                return
            }
        }
        val initialValue = results.first
        val func = results.second
        val elementType = extractSequenceArgumentType(expression.type) ?: return
        expression.sequenceDataOfExpression = SequenceData(
            SequenceSource.GenerateSequence(initialValue, func, elementType),
            emptyList()
        )
    }

    private fun extractSequenceArgumentType(sequenceType: IrType): IrType? =
        (sequenceType as? IrSimpleType)?.arguments?.singleOrNull()?.let { return it.typeOrNull }

    private fun matchWithFilter(call: IrCall, version: FilterVersion) {
        val receiver = call.arguments.getOrNull(0) ?: return
        val receiverData = receiver.sequenceDataOfExpression ?: return
        val invokeSymbol = call.arguments.getOrNull(1)?.type?.classOrNull?.owner?.declarations
            ?.filterIsInstance<IrSimpleFunction>()
            ?.first { it.name.asString() == "invoke" }?.symbol
        val predicate = if (version == FilterVersion.FilterNotNull) null else (getPredicateArgument(call, 1) ?: return)
        if (predicate is IrCall) return
        val filterFunction: (IrBuilderWithParent) -> (IrValueDeclaration) -> IrExpression = { builderWithParent: IrBuilderWithParent ->
            val parent = builderWithParent.second
            with(builderWithParent.first) {
                when (predicate) {
                    is IrRichFunctionReference -> when (version) {
                        FilterVersion.Filter -> { sequenceElement -> callRichFunctionReference(predicate, parent, irGet(sequenceElement)) }
                        FilterVersion.FilterNot -> { sequenceElement ->
                            irNot(
                                callRichFunctionReference(
                                    predicate,
                                    parent,
                                    irGet(sequenceElement)
                                )
                            )
                        }
                        FilterVersion.FilterNotNull -> { _ -> error("FilterNotNullTo with a third argument: ${predicate.dump()}") }
                    }
                    else -> when (version) {
                        FilterVersion.Filter -> { sequenceElement ->
                            invokeSymbol?.let {
                                irCall(it).apply {
                                    dispatchReceiver = predicate!!
                                    arguments[1] = irGet(sequenceElement)
                                }
                            }
                                ?: error("Didn't find invoke for the predicate argument of filter: ${predicate?.dump()}")
                        }
                        FilterVersion.FilterNot -> { sequenceElement ->
                            invokeSymbol?.let {
                                irNot(irCall(it).apply {
                                    dispatchReceiver = predicate!!
                                    arguments[1] = irGet(sequenceElement)
                                })
                            }
                                ?: error("Didn't find invoke for the predicate argument of filterNot: ${predicate?.dump()}")
                        }
                        FilterVersion.FilterNotNull -> if (predicate != null) error("FilterNotNull with a second argument: ${predicate.dump()}") else
                            { sequenceElement -> irNot(irEquals(irGet(sequenceElement), irNull())) }
                    }
                }
            }
        }
        val transformers = listOf(SequenceTransformer.Filter(filterFunction, call.startOffset, call.endOffset)) + receiverData.transformers
        call.sequenceDataOfExpression = SequenceData(receiverData.sequenceSource, transformers)
    }

    private fun matchWithSequenceOf(expression: IrCall) {
        // store the sequence of arguments inside the sequence source
        if (expression.arguments.size > 1) return
        val elementType = extractSequenceArgumentType(expression.type) ?: return
        if (expression.arguments.isEmpty()) {
            expression.sequenceDataOfExpression = SequenceData(
                SequenceSource.SequenceOf(listOf(), elementType),
                emptyList()
            )
            return
        }
        val argument = expression.arguments.getOrNull(0) ?: return
        val sequenceOfArguments = gatherVarargArgument(argument) ?: return
        expression.sequenceDataOfExpression = SequenceData(
            SequenceSource.SequenceOf(sequenceOfArguments, elementType),
            emptyList()
        )
    }


    private fun matchWithAsSequence(expression: IrCall) {
        val receiver = expression.arguments.getOrNull(0) ?: return
        if (receiver is IrGetValue) {
            if (!isSafeToLower(receiver)) return
            if (!receiver.type.isSubtypeOfClass(context.irBuiltIns.iterableClass)) return
        }
        expression.sequenceDataOfExpression = SequenceData(
            SequenceSource.AsSequence(receiver),
            emptyList()
        )
    }

    private fun matchWithSequence(expression: IrCall) {
        val sequenceScope = expression.arguments.getOrNull(0) as? IrRichFunctionReference ?: return
        expression.sequenceDataOfExpression = SequenceData(
            SequenceSource.Sequence(sequenceScope),
            emptyList()
        )
    }

    override fun visitCall(expression: IrCall) {
        super.visitCall(expression)
        if (!isElementSequence(context, expression)) return
        if (!isCallFromKotlinSequences(expression)) return
        val functionName = expression.symbol.owner.name.asString()
        when (functionName) {
            MAP -> matchWithMap(expression, isIndexed = false, isNotNull = false)
            MAP_INDEXED -> matchWithMap(expression, isIndexed = true, isNotNull = false)
            MAP_NOT_NULL -> matchWithMap(expression, isIndexed = false, isNotNull = true)
            MAP_NOT_NULL_INDEXED -> matchWithMap(expression, isIndexed = true, isNotNull = true)
            FILTER -> matchWithFilter(expression, FilterVersion.Filter)
            FILTER_NOT -> matchWithFilter(expression, FilterVersion.FilterNot)
            FILTER_NOT_NULL -> matchWithFilter(expression, FilterVersion.FilterNotNull)
            TAKE -> matchWithTake(expression)
            GENERATE_SEQUENCE -> matchWithGenerateSequence(expression)
            SEQUENCE_OF -> matchWithSequenceOf(expression)
            AS_SEQUENCE -> matchWithAsSequence(expression)
            SEQUENCE -> matchWithSequence(expression)
        }
    }
}

