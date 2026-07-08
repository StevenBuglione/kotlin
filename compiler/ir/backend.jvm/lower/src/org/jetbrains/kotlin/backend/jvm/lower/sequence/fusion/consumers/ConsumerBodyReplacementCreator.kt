/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.consumers

import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

private const val FOR_EACH = "forEach"
private const val FIND = "find"
private const val FIND_LAST = "findLast"
private const val FIRST = "first"
private const val FIRST_NOT_NULL_OF = "firstNotNullOf"
private const val FIRST_NOT_NULL_OF_OR_NULL = "firstNotNullOfOrNull"
private const val FIRST_OR_NULL = "firstOrNull"
private const val LAST = "last"
private const val LAST_OR_NULL = "lastOrNull"
private const val FILTER_TO = "filterTo"
private const val FILTER_NOT_TO = "filterNotTo"
private const val FILTER_NOT_NULL_TO = "filterNotNullTo"
private const val TO_LIST = "toList"
private const val TO_MUTABLE_LIST = "toMutableList"
private const val TO_SET = "toSet"
private const val TO_MUTABLE_SET = "toMutableSet"
private const val TO_COLLECTION = "toCollection"

internal abstract class ConsumerStrategy(val data: ConsumerData, val expression: IrExpression) {
    abstract fun initializeState(): List<IrVariable>

    abstract fun getConsumerBuilder(): ConsumerBodyBuilder?

    abstract fun finalizeResult(): IrExpression
    fun createSequenceReplacement(): SequenceReplacement? =
        getConsumerBuilder()?.let { SequenceReplacement(initializeState(), it, finalizeResult()) }

    abstract val returnsElement: Boolean
}

internal data class ConsumerData(
    val context: JvmBackendContext,
    val builder: IrBuilderWithScope,
    val parent: IrDeclarationParent,
    val sequenceData: SequenceData
)

internal fun createConsumerStrategy(
    expression: IrCall,
    functionName: String,
    data: ConsumerData,
): ConsumerStrategy? {
    return when (functionName) {
        FOR_EACH -> ForEachConsumerStrategy(data, expression)
        FIND -> FindConsumerStrategy(data, expression, isFirst = true)
        FIND_LAST -> FindConsumerStrategy(data, expression, isFirst = false)
        FIRST -> FirstLastConsumerStrategy(data, expression, isOrNull = false, isFirst = true)
        FIRST_OR_NULL -> FirstLastConsumerStrategy(data, expression, isOrNull = true, isFirst = true)
        FIRST_NOT_NULL_OF -> FirstNotNullOfConsumerStrategy(data, expression, isOrNull = false)
        FIRST_NOT_NULL_OF_OR_NULL -> FirstNotNullOfConsumerStrategy(data, expression, isOrNull = true)
        LAST -> FirstLastConsumerStrategy(data, expression, isOrNull = false, isFirst = false)
        LAST_OR_NULL -> FirstLastConsumerStrategy(data, expression, isOrNull = true, isFirst = false)
        FILTER_TO -> FilterToConsumerStrategy(data, expression, FilterVersion.Filter)
        FILTER_NOT_TO -> FilterToConsumerStrategy(data, expression, FilterVersion.FilterNot)
        FILTER_NOT_NULL_TO -> FilterToConsumerStrategy(data, expression, FilterVersion.FilterNotNull)
        TO_LIST -> ToCollectionConsumerStrategy(data, expression, CollectionVersion.List)
        TO_MUTABLE_LIST -> ToCollectionConsumerStrategy(data, expression, CollectionVersion.List)
        TO_SET -> ToCollectionConsumerStrategy(data, expression, CollectionVersion.Set)
        TO_MUTABLE_SET -> ToCollectionConsumerStrategy(data, expression, CollectionVersion.Set)
        TO_COLLECTION -> ToCollectionConsumerStrategy(data, expression, CollectionVersion.Collection)
        else -> null
    }
}

internal fun createConsumerStrategy(
    expression: IrBlock,
    data: ConsumerData,
): ConsumerStrategy? {
    val loopData = gatherLoopData(expression, data.parent, data.context) ?: return null
    return ForLoopConsumerStrategy(data, loopData, expression)
}

fun IrElement.rebindJumps(
    targetLoop: IrLoop,
    onBreak: (IrBreak) -> IrExpression,
    onContinue: (IrContinue) -> IrExpression
) {
    this.transformChildrenVoid(object : IrElementTransformerVoid() {
        override fun visitBreak(jump: IrBreak): IrExpression {
            if (jump.loop == targetLoop) return onBreak(jump)
            return super.visitBreak(jump)
        }

        override fun visitContinue(jump: IrContinue): IrExpression {
            if (jump.loop == targetLoop) return onContinue(jump)
            return super.visitContinue(jump)
        }
    })
}
