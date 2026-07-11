/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

internal data class ArcOwnershipOptimizationMetrics(
    val plansVisited: Int = 0,
    val plansChanged: Int = 0,
    val operationsBefore: Int = 0,
    val operationsAfter: Int = 0,
    val referenceCountingOperationsBefore: Int = 0,
    val referenceCountingOperationsAfter: Int = 0,
    val forwardedOwnedResults: Int = 0,
    val copyDestroyPairsEliminated: Int = 0,
) {
    val eliminatedReferenceCountingOperations: Int
        get() = referenceCountingOperationsBefore - referenceCountingOperationsAfter

    val eliminationPercentage: Int
        get() = if (referenceCountingOperationsBefore == 0) 0
        else eliminatedReferenceCountingOperations * 100 / referenceCountingOperationsBefore

    operator fun plus(other: ArcOwnershipOptimizationMetrics) = ArcOwnershipOptimizationMetrics(
        plansVisited = plansVisited + other.plansVisited,
        plansChanged = plansChanged + other.plansChanged,
        operationsBefore = operationsBefore + other.operationsBefore,
        operationsAfter = operationsAfter + other.operationsAfter,
        referenceCountingOperationsBefore = referenceCountingOperationsBefore + other.referenceCountingOperationsBefore,
        referenceCountingOperationsAfter = referenceCountingOperationsAfter + other.referenceCountingOperationsAfter,
        forwardedOwnedResults = forwardedOwnedResults + other.forwardedOwnedResults,
        copyDestroyPairsEliminated = copyDestroyPairsEliminated + other.copyDestroyPairsEliminated,
    )

    fun render(): String =
        "ARC ownership optimization: $plansChanged/$plansVisited plans changed, " +
                "$eliminatedReferenceCountingOperations/$referenceCountingOperationsBefore " +
                "reference-counting operations eliminated ($eliminationPercentage%), " +
                "$forwardedOwnedResults owned results forwarded, " +
                "$copyDestroyPairsEliminated copy/destroy pairs eliminated"
}

internal data class ArcOwnershipOptimizationResult(
    val plan: ArcFunctionPlan,
    val metrics: ArcOwnershipOptimizationMetrics,
)

/**
 * A deliberately bounded side-plan optimizer. It only rewrites a verified, single-block plan and therefore
 * cannot affect generated code. The resulting plan is verified again before being returned.
 */
internal object ArcOwnershipOptimizer {
    fun optimizeVerified(plan: ArcFunctionPlan): ArcOwnershipOptimizationResult {
        ArcOwnershipVerifier.verifyOrThrow(plan)

        val block = plan.blocks[plan.entry]
        if (plan.blocks.size != 1 || block == null) return unchanged(plan)

        val operationsBefore = block.operations
        var operations = operationsBefore
        var forwardedOwnedResults = 0
        var copyDestroyPairsEliminated = 0

        while (true) {
            val rewritten = forwardOneOwnedResult(operations, block.terminator) ?: break
            operations = rewritten
            forwardedOwnedResults++
        }
        while (true) {
            val rewritten = eliminateOneCopyDestroyPair(operations, block.terminator) ?: break
            operations = rewritten
            copyDestroyPairsEliminated++
        }

        val optimizedPlan = if (operations == operationsBefore) plan else plan.copy(
            blocks = plan.blocks + (block.id to block.copy(operations = operations))
        )
        ArcOwnershipVerifier.verifyOrThrow(optimizedPlan)

        return ArcOwnershipOptimizationResult(
            optimizedPlan,
            metrics(
                operationsBefore,
                operations,
                forwardedOwnedResults,
                copyDestroyPairsEliminated,
            ),
        )
    }

    private fun unchanged(plan: ArcFunctionPlan): ArcOwnershipOptimizationResult {
        val operations = plan.blocks.values.sumOf { it.operations.size }
        val referenceCountingOperations = plan.blocks.values.sumOf { block ->
            block.operations.count(ArcOperation::isReferenceCountingOperation)
        }
        return ArcOwnershipOptimizationResult(
            plan,
            ArcOwnershipOptimizationMetrics(
                plansVisited = 1,
                operationsBefore = operations,
                operationsAfter = operations,
                referenceCountingOperationsBefore = referenceCountingOperations,
                referenceCountingOperationsAfter = referenceCountingOperations,
            ),
        )
    }

    private fun metrics(
        before: List<ArcOperation>,
        after: List<ArcOperation>,
        forwardedOwnedResults: Int,
        copyDestroyPairsEliminated: Int,
    ) = ArcOwnershipOptimizationMetrics(
        plansVisited = 1,
        plansChanged = if (before == after) 0 else 1,
        operationsBefore = before.size,
        operationsAfter = after.size,
        referenceCountingOperationsBefore = before.count(ArcOperation::isReferenceCountingOperation),
        referenceCountingOperationsAfter = after.count(ArcOperation::isReferenceCountingOperation),
        forwardedOwnedResults = forwardedOwnedResults,
        copyDestroyPairsEliminated = copyDestroyPairsEliminated,
    )

    /**
     * Forward a producer's plus-one result through its sole copy when the producer value is otherwise only destroyed.
     * This turns `define %source; copy %source -> %result; destroy %source` into `define %result`.
     */
    private fun forwardOneOwnedResult(
        operations: List<ArcOperation>,
        terminator: ArcTerminator,
    ): List<ArcOperation>? {
        operations.forEachIndexed { defineIndex, operation ->
            val define = operation as? ArcOperation.Define ?: return@forEachIndexed
            if (define.ownership != ArcOwnership.Owned || terminator.uses(define.result)) return@forEachIndexed

            val uses = operations.withIndex()
                .filter { it.index > defineIndex && it.value.uses(define.result) }
            if (uses.size != 2) return@forEachIndexed
            val copyUse = uses[0]
            val destroyUse = uses[1]
            val copy = copyUse.value as? ArcOperation.Copy ?: return@forEachIndexed
            val destroy = destroyUse.value as? ArcOperation.Destroy ?: return@forEachIndexed
            if (copy.source != define.result || destroy.value != define.result) return@forEachIndexed

            return operations.mapIndexedNotNull { index, current ->
                when (index) {
                    defineIndex -> define.copy(result = copy.result)
                    copyUse.index, destroyUse.index -> null
                    else -> current
                }
            }
        }
        return null
    }

    /** Remove a retain/release pair whose copied value has no intervening or escaping use. */
    private fun eliminateOneCopyDestroyPair(
        operations: List<ArcOperation>,
        terminator: ArcTerminator,
    ): List<ArcOperation>? {
        operations.forEachIndexed { copyIndex, operation ->
            val copy = operation as? ArcOperation.Copy ?: return@forEachIndexed
            if (terminator.uses(copy.result)) return@forEachIndexed

            val uses = operations.withIndex()
                .filter { it.index > copyIndex && it.value.uses(copy.result) }
            if (uses.size != 1) return@forEachIndexed
            val destroyUse = uses.single()
            val destroy = destroyUse.value as? ArcOperation.Destroy ?: return@forEachIndexed
            if (destroy.value != copy.result) return@forEachIndexed

            return operations.filterIndexed { index, _ -> index != copyIndex && index != destroyUse.index }
        }
        return null
    }
}

private fun ArcOperation.uses(value: ArcValue): Boolean = when (this) {
    is ArcOperation.Define -> false
    is ArcOperation.Copy -> source == value
    is ArcOperation.Destroy -> this.value == value
    is ArcOperation.Borrow -> source == value
    is ArcOperation.StrongStore -> this.value == value
    is ArcOperation.StrongLoad -> false
}

private fun ArcTerminator.uses(value: ArcValue): Boolean = this is ArcTerminator.Return && this.value == value

private fun ArcOperation.isReferenceCountingOperation(): Boolean = when (this) {
    is ArcOperation.Copy, is ArcOperation.Destroy, is ArcOperation.StrongStore, is ArcOperation.StrongLoad -> true
    is ArcOperation.Define, is ArcOperation.Borrow -> false
}
