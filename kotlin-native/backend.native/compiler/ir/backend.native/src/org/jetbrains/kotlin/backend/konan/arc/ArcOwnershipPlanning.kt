/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.KonanFqNames
import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.backend.konan.ir.getSuperClassNotAny
import org.jetbrains.kotlin.backend.konan.llvm.Lifetime
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.reportCompilationError
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.allParameters
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isElseBranch
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
) {
    companion object {
        val Empty = ArcCodegenOwnershipPlan(emptyMap())
    }
}

internal data class ArcOwnedResultForwarding(
    val producer: IrVariable,
    val returned: IrVariable,
)

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
    var skipped = 0
    input.module.files.forEach { file ->
        file.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
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
                                        "ownedResultForwarding=${forwarding != null}"
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
        ArcCodegenOwnershipPlan(ownedResultForwarding),
    )
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
