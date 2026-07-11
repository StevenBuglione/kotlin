/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.backend.konan.llvm.Lifetime
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.reportCompilationError
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.allParameters
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

internal data class ArcOwnershipPlanningInput(
    val module: org.jetbrains.kotlin.ir.declarations.IrModuleFragment,
    val lifetimes: Map<IrElement, Lifetime>,
)

internal data class ArcOwnershipPlanningReport(
    val analyzedFunctions: Int,
    val skippedFunctions: Int,
    val plans: List<ArcFunctionPlan>,
) {
    companion object {
        val Disabled = ArcOwnershipPlanningReport(0, 0, emptyList())
    }
}

/**
 * ARC-only first-slice ownership planner.
 *
 * It deliberately accepts only straight-line, non-suspend functions made from reference vals,
 * direct reference-producing calls, and strong field loads/stores. Unsupported functions are
 * skipped without changing IR. Every accepted plan is path-verified immediately.
 */
internal fun runArcOwnershipPlanning(
    generationState: NativeGenerationState,
    input: ArcOwnershipPlanningInput,
): ArcOwnershipPlanningReport {
    if (generationState.context.memoryModel != MemoryModel.ARC) return ArcOwnershipPlanningReport.Disabled

    val plans = mutableListOf<ArcFunctionPlan>()
    var skipped = 0
    input.module.files.forEach { file ->
        file.acceptVoid(object : IrElementVisitorVoid {
            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                val plan = CuratedArcOwnershipPlanBuilder(declaration, input.lifetimes).build()
                if (plan == null) {
                    skipped++
                } else {
                    when (val result = ArcOwnershipVerifier.verify(plan)) {
                        ArcOwnershipVerificationResult.Success -> plans += plan
                        is ArcOwnershipVerificationResult.Failure -> reportFailure(generationState, file, declaration, result)
                    }
                }
                declaration.acceptChildrenVoid(this)
            }
        })
    }
    return ArcOwnershipPlanningReport(plans.size, skipped, plans)
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

private class CuratedArcOwnershipPlanBuilder(
    private val function: IrSimpleFunction,
    @Suppress("unused") private val lifetimes: Map<IrElement, Lifetime>,
) {
    private val entry = ArcBlockId("entry")
    private val operations = mutableListOf<ArcOperation>()
    private val values = mutableMapOf<org.jetbrains.kotlin.ir.symbols.IrValueSymbol, ArcValue>()
    private val ownership = mutableMapOf<ArcValue, ArcOwnership>()
    private val entryValues = linkedMapOf<ArcValue, ArcOwnership>()
    private val initializedStorage = linkedSetOf<ArcStorage>()
    private val localDefinitionOrder = mutableListOf<ArcValue>()
    private var nextValue = 0
    private var returnedValue: ArcValue? = null

    fun build(): ArcFunctionPlan? {
        if (function.isSuspend || function.body !is org.jetbrains.kotlin.ir.expressions.IrBlockBody) return null

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
                is IrReturn -> {
                    if (!isLast || !planReturn(statement)) return null
                }
                is IrExpression -> return null
                else -> return null
            }
        }

        localDefinitionOrder.asReversed().forEach { value ->
            if (value != returnedValue && ownership[value] == ArcOwnership.Owned) {
                operations += ArcOperation.Destroy(value, ArcPlanLocation("lexical scope exit"))
            }
        }

        val block = ArcBasicBlock(entry, operations, ArcTerminator.Return(returnedValue))
        return ArcFunctionPlan(
            functionName = function.fqNameForIrSerialization.asString(),
            entry = entry,
            entryValues = entryValues,
            entryInitializedStorage = initializedStorage,
            blocks = mapOf(entry to block),
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
                operations += ArcOperation.Define(result, ArcOwnership.Immortal, location)
                ArcOwnership.Immortal
            }
            is IrGetValue -> {
                val source = values[initializer.symbol] ?: return false
                operations += ArcOperation.Copy(source, result, location)
                if (ownership[source] == ArcOwnership.Immortal) ArcOwnership.Immortal else ArcOwnership.Owned
            }
            is IrGetField -> {
                val storage = storage(initializer)
                initializedStorage += storage
                operations += ArcOperation.StrongLoad(storage, result, location)
                ArcOwnership.Owned
            }
            is IrConstructorCall, is IrCall -> {
                operations += ArcOperation.Define(result, ArcOwnership.Owned, location)
                ArcOwnership.Owned
            }
            else -> return false
        }
        values[variable.symbol] = result
        ownership[result] = resultOwnership
        localDefinitionOrder += result
        return true
    }

    private fun planStrongStore(setField: IrSetField): Boolean {
        if (!setField.symbol.owner.type.binaryTypeIsReference()) return true
        val valueExpression = setField.value as? IrGetValue ?: return false
        val value = values[valueExpression.symbol] ?: return false
        operations += ArcOperation.StrongStore(storage(setField), value, location(setField, "strong field store"))
        return true
    }

    private fun planReturn(expression: IrReturn): Boolean {
        if (function.returnType.isUnit()) return expression.value.type.isUnit()
        if (!function.returnType.binaryTypeIsReference()) return true
        val getValue = expression.value as? IrGetValue ?: return false
        returnedValue = values[getValue.symbol] ?: return false
        return true
    }

    private fun newValue(hint: String): ArcValue = ArcValue("${nextValue++}_$hint")

    private fun storage(getField: IrGetField): ArcStorage = ArcStorage(getField.symbol.owner.fqNameForIrSerialization.asString())

    private fun storage(setField: IrSetField): ArcStorage = ArcStorage(setField.symbol.owner.fqNameForIrSerialization.asString())

    private fun location(element: IrElement, description: String) = ArcPlanLocation(description, element.startOffset)
}
