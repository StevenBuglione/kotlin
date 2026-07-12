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
    val guaranteedEntryCopiesEliminated: Int = 0,
    val copyDestroyPairsEliminated: Int = 0,
    val containedOwnedCopiesEliminated: Int = 0,
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
        guaranteedEntryCopiesEliminated = guaranteedEntryCopiesEliminated + other.guaranteedEntryCopiesEliminated,
        copyDestroyPairsEliminated = copyDestroyPairsEliminated + other.copyDestroyPairsEliminated,
        containedOwnedCopiesEliminated = containedOwnedCopiesEliminated + other.containedOwnedCopiesEliminated,
    )

    fun render(): String =
        "ARC ownership optimization: $plansChanged/$plansVisited plans changed, " +
                "$eliminatedReferenceCountingOperations/$referenceCountingOperationsBefore " +
                "reference-counting operations eliminated ($eliminationPercentage%), " +
                "$forwardedOwnedResults owned results forwarded, " +
                "$guaranteedEntryCopiesEliminated guaranteed entry copies eliminated, " +
                "$copyDestroyPairsEliminated copy/destroy pairs eliminated, " +
                "$containedOwnedCopiesEliminated contained owned copies eliminated"
}

internal data class ArcOwnershipOptimizationResult(
    val plan: ArcFunctionPlan,
    val metrics: ArcOwnershipOptimizationMetrics,
)

/**
 * A deliberately bounded side-plan optimizer. It rewrites verified straight-line plans and one conservative
 * family of acyclic CFG plans; no result from this optimizer affects generated code. Every result is reverified.
 */
internal object ArcOwnershipOptimizer {
    fun optimizeVerified(plan: ArcFunctionPlan): ArcOwnershipOptimizationResult {
        ArcOwnershipVerifier.verifyOrThrow(plan)

        var forwardedOwnedResults = 0
        var guaranteedEntryCopiesEliminated = 0
        var copyDestroyPairsEliminated = 0
        var containedOwnedCopiesEliminated = 0
        var workingPlan = plan
        while (true) {
            val rewritten = eliminateOneGuaranteedEntryCopy(workingPlan) ?: break
            workingPlan = rewritten
            guaranteedEntryCopiesEliminated++
        }

        val block = workingPlan.blocks[workingPlan.entry]
        val optimizedPlan = if (workingPlan.blocks.size == 1 && block != null) {
            var operations = block.operations
            while (true) {
                val rewritten = forwardOneOwnedResult(operations, block.terminator) ?: break
                operations = rewritten
                forwardedOwnedResults++
            }
            while (true) {
                val rewritten = eliminateOneContainedOwnedCopy(workingPlan.entryValues, operations, block.terminator) ?: break
                operations = rewritten
                containedOwnedCopiesEliminated++
            }
            while (true) {
                val rewritten = eliminateOneCopyDestroyPair(operations, block.terminator) ?: break
                operations = rewritten
                copyDestroyPairsEliminated++
            }
            if (operations == block.operations) workingPlan else workingPlan.copy(
                blocks = workingPlan.blocks + (block.id to block.copy(operations = operations))
            )
        } else {
            var rewrittenPlan = workingPlan
            val topologicalOrder = rewrittenPlan.conservativeTopologicalOrder()
            if (topologicalOrder == null) {
                rewrittenPlan
            } else {
                while (true) {
                    val rewritten = eliminateOneContainedOwnedCopyAcrossCfg(rewrittenPlan, topologicalOrder) ?: break
                    rewrittenPlan = rewritten
                    containedOwnedCopiesEliminated++
                }
                rewrittenPlan
            }
        }
        ArcOwnershipVerifier.verifyOrThrow(optimizedPlan)

        return ArcOwnershipOptimizationResult(
            optimizedPlan,
            metrics(
                plan,
                optimizedPlan,
                forwardedOwnedResults,
                guaranteedEntryCopiesEliminated,
                copyDestroyPairsEliminated,
                containedOwnedCopiesEliminated,
            ),
        )
    }

    /**
     * Eliminate a copied lifetime whose source is a guaranteed function entry value.
     *
     * Entry guarantees cover the complete function, so every non-consuming use can refer to the
     * source directly across arbitrary CFG shapes. Reject any unknown or ownership-transferring
     * consumer; the copied lifetime may end only through explicit destroys, all of which vanish
     * together with the copy. This is the bounded analogue of Swift's guaranteed copy-value opt.
     */
    private fun eliminateOneGuaranteedEntryCopy(plan: ArcFunctionPlan): ArcFunctionPlan? {
        plan.blocks.forEach { (candidateBlockId, candidateBlock) ->
            candidateBlock.operations.forEachIndexed { candidateIndex, operation ->
                val copy = operation as? ArcOperation.Copy ?: return@forEachIndexed
                if (plan.entryValues[copy.source] != ArcOwnership.Guaranteed) return@forEachIndexed

                var destroys = 0
                for (block in plan.blocks.values) {
                    if (block.terminator.uses(copy.result)) return@forEachIndexed
                    for (use in block.operations) {
                        if (!use.uses(copy.result)) continue
                        when (use) {
                            is ArcOperation.Destroy -> destroys++
                            is ArcOperation.Borrow, is ArcOperation.Use, is ArcOperation.StrongStore -> Unit
                            else -> return@forEachIndexed
                        }
                    }
                }
                if (destroys == 0) return@forEachIndexed

                return plan.copy(blocks = plan.blocks.mapValues { (blockId, block) ->
                    block.copy(operations = block.operations.mapIndexedNotNull { index, current ->
                        when {
                            blockId == candidateBlockId && index == candidateIndex -> null
                            current is ArcOperation.Destroy && current.value == copy.result -> null
                            else -> current.replacingUse(copy.result, copy.source)
                        }
                    })
                })
            }
        }
        return null
    }

    private fun metrics(
        before: ArcFunctionPlan,
        after: ArcFunctionPlan,
        forwardedOwnedResults: Int,
        guaranteedEntryCopiesEliminated: Int,
        copyDestroyPairsEliminated: Int,
        containedOwnedCopiesEliminated: Int,
    ): ArcOwnershipOptimizationMetrics {
        val beforeOperations = before.blocks.values.flatMap(ArcBasicBlock::operations)
        val afterOperations = after.blocks.values.flatMap(ArcBasicBlock::operations)
        return ArcOwnershipOptimizationMetrics(
            plansVisited = 1,
            plansChanged = if (before == after) 0 else 1,
            operationsBefore = beforeOperations.size,
            operationsAfter = afterOperations.size,
            referenceCountingOperationsBefore = beforeOperations.count(ArcOperation::isReferenceCountingOperation),
            referenceCountingOperationsAfter = afterOperations.count(ArcOperation::isReferenceCountingOperation),
            forwardedOwnedResults = forwardedOwnedResults,
            guaranteedEntryCopiesEliminated = guaranteedEntryCopiesEliminated,
            copyDestroyPairsEliminated = copyDestroyPairsEliminated,
            containedOwnedCopiesEliminated = containedOwnedCopiesEliminated,
        )
    }

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

    /**
     * Remove a contained retain/release pair while an independent owned source keeps the object alive.
     *
     * This is intentionally restricted to a single block. The copied value may only be borrowed, stored into
     * strong storage, and finally destroyed. The source must itself be owned and its destroy (or ownership-
     * transferring return) must follow the copied value's destroy. Consequently the copied +1 cannot be the
     * reference that controls deinitialization, and replacing its non-consuming uses with the source preserves
     * both object identity and deinitialization timing.
     */
    private fun eliminateOneContainedOwnedCopy(
        entryValues: Map<ArcValue, ArcOwnership>,
        operations: List<ArcOperation>,
        terminator: ArcTerminator,
    ): List<ArcOperation>? {
        val ownerships = entryValues.toMutableMap()

        operations.forEachIndexed { copyIndex, operation ->
            val copy = operation as? ArcOperation.Copy
            if (copy != null && ownerships[copy.source] == ArcOwnership.Owned && !terminator.uses(copy.result)) {
                val resultUses = operations.withIndex()
                    .filter { it.index > copyIndex && it.value.uses(copy.result) }
                val resultDestroy = resultUses.singleOrNull { it.value is ArcOperation.Destroy }
                if (resultDestroy != null) {
                    val nonDestroyUses = resultUses.filterNot { it.index == resultDestroy.index }
                    val usesAreContained =
                            nonDestroyUses.all { it.value is ArcOperation.Borrow || it.value is ArcOperation.StrongStore } &&
                            nonDestroyUses.all { it.index < resultDestroy.index } &&
                            borrowedResultsAreContained(nonDestroyUses, operations, resultDestroy.index, terminator)
                    val sourceDestroyIndex = operations.indexOfFirstAfter(copyIndex) {
                        it is ArcOperation.Destroy && it.value == copy.source
                    }
                    val sourceIsReturned = terminator is ArcTerminator.Return && terminator.value == copy.source
                    val sourceOutlivesResult = sourceDestroyIndex > resultDestroy.index ||
                            (sourceDestroyIndex < 0 && sourceIsReturned)

                    if (usesAreContained && sourceOutlivesResult) {
                        return operations.mapIndexedNotNull { index, current ->
                            when (index) {
                                copyIndex, resultDestroy.index -> null
                                else -> current.replacingUse(copy.result, copy.source)
                            }
                        }
                    }
                }
            }

            operation.definedValue(ownerships)?.let { (value, ownership) -> ownerships[value] = ownership }
        }
        return null
    }

    /** A borrow may not indirectly extend the eliminated copy's lifetime or escape through a return. */
    private fun borrowedResultsAreContained(
        resultUses: List<IndexedValue<ArcOperation>>,
        operations: List<ArcOperation>,
        resultDestroyIndex: Int,
        terminator: ArcTerminator,
    ): Boolean {
        val worklist = ArrayDeque<ArcValue>()
        resultUses.forEach { (it.value as? ArcOperation.Borrow)?.result?.let(worklist::addLast) }
        val visited = mutableSetOf<ArcValue>()
        while (worklist.isNotEmpty()) {
            val borrowed = worklist.removeFirst()
            if (!visited.add(borrowed) || terminator.uses(borrowed)) return false
            val uses = operations.withIndex().filter { it.value.uses(borrowed) }
            if (uses.any { it.index >= resultDestroyIndex }) return false
            uses.forEach { (it.value as? ArcOperation.Borrow)?.result?.let(worklist::addLast) }
        }
        return true
    }

    /**
     * Remove one copied lifetime that is contained by an independently owned source across an acyclic CFG.
     *
     * Borrows, ordinary uses, and strong stores are non-consuming and may use the copy; destroys must end its
     * lifetime on every path. Verified-plan semantics guarantee that every nested borrow scope ends before its
     * owning copied value is destroyed. Requiring the source to remain live until each copied lifetime ends
     * therefore also keeps it live through all of those borrow scopes. Source consumes are never moved, added,
     * or removed, so the final deinitialization point is identical after the redundant retain/releases vanish.
     */
    private fun eliminateOneContainedOwnedCopyAcrossCfg(
        plan: ArcFunctionPlan,
        topologicalOrder: List<ArcBlockId>,
    ): ArcFunctionPlan? {
        val ownerships = plan.valueOwnerships(topologicalOrder)
        topologicalOrder.forEach { blockId ->
            val block = plan.blocks.getValue(blockId)
            block.operations.forEachIndexed { operationIndex, operation ->
                val copy = operation as? ArcOperation.Copy ?: return@forEachIndexed
                if (ownerships[copy.source] != ArcOwnership.Owned) return@forEachIndexed
                val candidate = CfgCopyCandidate(blockId, operationIndex, copy)
                if (!plan.isSafelyContained(candidate, topologicalOrder)) return@forEachIndexed
                return plan.rewriteContainedCopy(candidate)
            }
        }
        return null
    }
}

private data class CfgCopyCandidate(
    val block: ArcBlockId,
    val operationIndex: Int,
    val copy: ArcOperation.Copy,
)

/** The deliberately small set of copied-value uses understood by the acyclic CFG lifetime proof. */
private enum class CfgContainedCopyUse {
    NON_CONSUMING,
    DESTROY,
}

private fun ArcOperation.cfgContainedCopyUse(value: ArcValue): CfgContainedCopyUse? = when {
    this is ArcOperation.Borrow && source == value -> CfgContainedCopyUse.NON_CONSUMING
    this is ArcOperation.Use && this.value == value -> CfgContainedCopyUse.NON_CONSUMING
    this is ArcOperation.StrongStore && this.value == value -> CfgContainedCopyUse.NON_CONSUMING
    this is ArcOperation.Destroy && this.value == value -> CfgContainedCopyUse.DESTROY
    else -> null
}

/**
 * Return a deterministic topological order only for the deliberately supported CFG subset.
 * Unreachable blocks, cycles, and `unreachable` terminators retain their original plans unchanged.
 */
private fun ArcFunctionPlan.conservativeTopologicalOrder(): List<ArcBlockId>? {
    val colors = mutableMapOf<ArcBlockId, Int>()
    val postorder = mutableListOf<ArcBlockId>()

    fun visit(blockId: ArcBlockId): Boolean {
        when (colors[blockId]) {
            1 -> return false
            2 -> return true
        }
        val block = blocks[blockId] ?: return false
        if (block.terminator === ArcTerminator.Unreachable) return false
        colors[blockId] = 1
        for (successor in block.terminator.successors()) {
            if (!visit(successor)) return false
        }
        colors[blockId] = 2
        postorder += blockId
        return true
    }

    if (!visit(entry) || colors.size != blocks.size) return null
    return postorder.asReversed()
}

private fun ArcFunctionPlan.valueOwnerships(topologicalOrder: List<ArcBlockId>): Map<ArcValue, ArcOwnership> {
    val result = entryValues.toMutableMap()
    topologicalOrder.forEach { blockId ->
        blocks.getValue(blockId).operations.forEach { operation ->
            when (operation) {
                is ArcOperation.Define -> result[operation.result] = operation.ownership
                is ArcOperation.Copy -> result[operation.result] =
                        if (result[operation.source] == ArcOwnership.Immortal) ArcOwnership.Immortal else ArcOwnership.Owned
                is ArcOperation.Borrow -> result[operation.result] = ArcOwnership.Guaranteed
                is ArcOperation.StrongLoad -> result[operation.result] = ArcOwnership.Owned
                is ArcOperation.Destroy, is ArcOperation.EndBorrow, is ArcOperation.Use, is ArcOperation.StrongStore -> Unit
            }
        }
    }
    return result
}

/** Forward lifetime proof for a candidate. `true` means the copied +1 is still live. */
private fun ArcFunctionPlan.isSafelyContained(
    candidate: CfgCopyCandidate,
    topologicalOrder: List<ArcBlockId>,
): Boolean {
    val source = candidate.copy.source
    val copied = candidate.copy.result
    var totalUses = 0
    blocks.values.forEach { block ->
        block.operations.forEach { operation ->
            if (operation.uses(copied)) {
                if (operation.cfgContainedCopyUse(copied) == null) return false
                totalUses++
            }
        }
        if (block.terminator.uses(copied)) return false
    }
    if (totalUses == 0) return false

    val incoming = mutableMapOf(candidate.block to true)
    var observedUses = 0
    var destroys = 0
    for (blockId in topologicalOrder) {
        var copiedIsLive = incoming[blockId] ?: continue
        val block = blocks.getValue(blockId)
        val firstOperation = if (blockId == candidate.block) candidate.operationIndex + 1 else 0
        for (index in firstOperation until block.operations.size) {
            val operation = block.operations[index]
            when (operation.cfgContainedCopyUse(copied)) {
                CfgContainedCopyUse.NON_CONSUMING -> {
                    if (!copiedIsLive) return false
                    observedUses++
                }
                CfgContainedCopyUse.DESTROY -> {
                    if (!copiedIsLive) return false
                    observedUses++
                    destroys++
                    copiedIsLive = false
                }
                null -> if (operation.uses(copied)) return false
            }
            if (copiedIsLive && operation is ArcOperation.Destroy && operation.value == source) return false
        }

        val terminator = block.terminator
        if (terminator.uses(copied)) return false
        if (copiedIsLive && terminator is ArcTerminator.Return && terminator.value == source) return false
        if (copiedIsLive && (terminator is ArcTerminator.Return || terminator === ArcTerminator.Throw)) return false

        for (successor in terminator.successors()) {
            val previous = incoming[successor]
            if (previous != null && previous != copiedIsLive) return false
            incoming[successor] = copiedIsLive
        }
    }
    return destroys > 0 && observedUses == totalUses
}

private fun ArcFunctionPlan.rewriteContainedCopy(candidate: CfgCopyCandidate): ArcFunctionPlan {
    val source = candidate.copy.source
    val copied = candidate.copy.result
    return copy(blocks = blocks.mapValues { (blockId, block) ->
        block.copy(operations = block.operations.mapIndexedNotNull { index, operation ->
            when {
                blockId == candidate.block && index == candidate.operationIndex -> null
                operation is ArcOperation.Destroy && operation.value == copied -> null
                else -> operation.replacingUse(copied, source)
            }
        })
    })
}

private fun List<ArcOperation>.indexOfFirstAfter(startIndex: Int, predicate: (ArcOperation) -> Boolean): Int {
    for (index in startIndex + 1 until size) if (predicate(this[index])) return index
    return -1
}

private fun ArcOperation.definedValue(ownerships: Map<ArcValue, ArcOwnership>): Pair<ArcValue, ArcOwnership>? = when (this) {
    is ArcOperation.Define -> result to ownership
    is ArcOperation.Copy -> result to if (ownerships[source] == ArcOwnership.Immortal) {
        ArcOwnership.Immortal
    } else {
        ArcOwnership.Owned
    }
    is ArcOperation.StrongLoad -> result to ArcOwnership.Owned
    is ArcOperation.Borrow -> result to ArcOwnership.Guaranteed
    is ArcOperation.Destroy, is ArcOperation.EndBorrow, is ArcOperation.Use, is ArcOperation.StrongStore -> null
}

private fun ArcOperation.replacingUse(from: ArcValue, to: ArcValue): ArcOperation = when (this) {
    is ArcOperation.Borrow -> if (source == from) copy(source = to) else this
    is ArcOperation.Use -> if (value == from) copy(value = to) else this
    is ArcOperation.StrongStore -> if (value == from) copy(value = to) else this
    else -> this
}

private fun ArcOperation.uses(value: ArcValue): Boolean = when (this) {
    is ArcOperation.Define -> false
    is ArcOperation.Copy -> source == value
    is ArcOperation.Destroy -> this.value == value
    is ArcOperation.Borrow -> source == value
    is ArcOperation.EndBorrow -> this.value == value
    is ArcOperation.Use -> this.value == value
    is ArcOperation.StrongStore -> this.value == value
    is ArcOperation.StrongLoad -> false
}

private fun ArcTerminator.uses(value: ArcValue): Boolean = this is ArcTerminator.Return && this.value == value

private fun ArcTerminator.successors(): List<ArcBlockId> = when (this) {
    is ArcTerminator.Jump -> listOf(target)
    is ArcTerminator.Branch -> listOf(trueTarget, falseTarget)
    is ArcTerminator.Return, ArcTerminator.Throw, ArcTerminator.Unreachable -> emptyList()
}

private fun ArcOperation.isReferenceCountingOperation(): Boolean = when (this) {
    is ArcOperation.Copy, is ArcOperation.Destroy, is ArcOperation.StrongStore, is ArcOperation.StrongLoad -> true
    is ArcOperation.Define, is ArcOperation.Borrow, is ArcOperation.EndBorrow, is ArcOperation.Use -> false
}
