/*
 * Copyright 2010-2023 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.arc

/**
 * A closed ownership proof for the small, linear spill chains emitted by coroutine lowering.
 *
 * This deliberately does not inspect IR. The IR selector is expected to prove the facts in
 * [ArcCoroutineOwnershipCandidate] and attach the resulting plan to the exact selected call and
 * slots. Keeping this model independent makes the code generator consume a verified transition
 * plan instead of duplicating eligibility rules.
 */
internal object ArcCoroutineOwnershipAnalysis {
    fun select(candidate: ArcCoroutineOwnershipCandidate): ArcCoroutineOwnershipPlan? {
        if (!candidate.hasCommonEligibility()) return null

        val plan = when (candidate.transferKind) {
            ArcCoroutineTransferKind.MoveOwnedToResult -> candidate.movePlan()
            ArcCoroutineTransferKind.BorrowThenMove -> candidate.borrowThenMovePlan()
            ArcCoroutineTransferKind.BorrowOnly -> candidate.borrowOnlyPlan()
        } ?: return null

        return plan.takeIf(ArcCoroutineOwnershipPlanVerifier::verify)
    }
}

internal enum class ArcCoroutineTransferKind {
    /** Move the producer's +1 spill value into the exact caller result slot. */
    MoveOwnedToResult,

    /** Borrow the spill for one direct Kotlin consumer, then move its +1 into the result slot. */
    BorrowThenMove,

    /** Borrow the spill for one direct Kotlin consumer, then destroy the spill normally. */
    BorrowOnly,
}

/** Opaque identity; [debugName] is never used for ownership or slot equivalence. */
internal class ArcCoroutineSlot(val debugName: String) {
    override fun toString(): String = debugName
}

/** Opaque identity for one exact lexical borrow. */
internal class ArcCoroutineBorrow(val debugName: String) {
    override fun toString(): String = debugName
}

/**
 * Facts that must be proven from one exact, linear lowered-coroutine path.
 *
 * Negative facts are intentionally explicit. In particular, a syntactically similar chain is
 * not eligible when it crosses a suspension or try region, calls foreign/external/virtual code,
 * or has control-flow/alias ambiguity.
 */
internal data class ArcCoroutineOwnershipCandidate(
    val transferKind: ArcCoroutineTransferKind,
    val spillSlot: ArcCoroutineSlot,
    val resultSlot: ArcCoroutineSlot? = null,
    val borrow: ArcCoroutineBorrow = ArcCoroutineBorrow("borrow"),
    val arcEnabled: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val loweredCoroutineFunction: Boolean,
    val exactDirectKotlinCall: Boolean,
    val nonExternalCall: Boolean,
    val nonVirtualCall: Boolean,
    val ownedReferenceResult: Boolean,
    val exactSingleSpillSlot: Boolean,
    val producerInitializesSpillOnNormalEdge: Boolean,
    val producerLeavesSpillUninitializedOnExceptionalEdge: Boolean,
    val noSuspensionBoundary: Boolean,
    val noTryBoundary: Boolean,
    val noForeignCall: Boolean,
    val linearUnambiguousPath: Boolean,
    val noAliasOrEscape: Boolean,
    val exactResultSlotIdentity: Boolean = false,
    val spillLastUseAtMove: Boolean = false,
    val exactSingleBorrowCopy: Boolean = false,
    val borrowEndsOnNormalEdge: Boolean = false,
    val borrowedConsumerMayThrow: Boolean = true,
    val borrowEndsOnExceptionalEdge: Boolean = false,
)

internal data class ArcCoroutineOwnershipReduction(
    val updateStackRefs: Int,
    val updateReturnRefs: Int,
)

internal data class ArcCoroutineOwnershipPlan(
    val transferKind: ArcCoroutineTransferKind,
    val spillSlot: ArcCoroutineSlot,
    val resultSlot: ArcCoroutineSlot?,
    val normalPath: List<ArcCoroutineOwnershipOperation>,
    val producerExceptionalPath: List<ArcCoroutineOwnershipOperation>,
    val consumerExceptionalPath: List<ArcCoroutineOwnershipOperation>?,
    val consumerMayThrow: Boolean,
    val reduction: ArcCoroutineOwnershipReduction,
)

internal sealed interface ArcCoroutineOwnershipOperation {
    data class InitializeOwned(val slot: ArcCoroutineSlot) : ArcCoroutineOwnershipOperation
    data class BeginBorrow(val slot: ArcCoroutineSlot, val borrow: ArcCoroutineBorrow) : ArcCoroutineOwnershipOperation
    data class EndBorrow(val borrow: ArcCoroutineBorrow) : ArcCoroutineOwnershipOperation
    data class MoveOwned(val from: ArcCoroutineSlot, val to: ArcCoroutineSlot) : ArcCoroutineOwnershipOperation
    data class DestroyOwned(val slot: ArcCoroutineSlot) : ArcCoroutineOwnershipOperation
    object ReturnOwnedResult : ArcCoroutineOwnershipOperation
    object ExitNormally : ArcCoroutineOwnershipOperation
    object ExceptionalExit : ArcCoroutineOwnershipOperation
}

private fun ArcCoroutineOwnershipCandidate.hasCommonEligibility(): Boolean =
    arcEnabled &&
            optimizationsEnabled &&
            debugInfoDisabled &&
            loweredCoroutineFunction &&
            exactDirectKotlinCall &&
            nonExternalCall &&
            nonVirtualCall &&
            ownedReferenceResult &&
            exactSingleSpillSlot &&
            producerInitializesSpillOnNormalEdge &&
            producerLeavesSpillUninitializedOnExceptionalEdge &&
            noSuspensionBoundary &&
            noTryBoundary &&
            noForeignCall &&
            linearUnambiguousPath &&
            noAliasOrEscape

private fun ArcCoroutineOwnershipCandidate.hasMoveEligibility(): Boolean =
    resultSlot != null && resultSlot !== spillSlot && exactResultSlotIdentity && spillLastUseAtMove

private fun ArcCoroutineOwnershipCandidate.hasBorrowEligibility(): Boolean =
    exactSingleBorrowCopy &&
            borrowEndsOnNormalEdge &&
            (!borrowedConsumerMayThrow || borrowEndsOnExceptionalEdge)

private fun ArcCoroutineOwnershipCandidate.movePlan(): ArcCoroutineOwnershipPlan? {
    val result = resultSlot ?: return null
    if (!hasMoveEligibility()) return null

    return ArcCoroutineOwnershipPlan(
        transferKind = transferKind,
        spillSlot = spillSlot,
        resultSlot = result,
        normalPath = listOf(
            ArcCoroutineOwnershipOperation.InitializeOwned(spillSlot),
            ArcCoroutineOwnershipOperation.MoveOwned(spillSlot, result),
            ArcCoroutineOwnershipOperation.ReturnOwnedResult,
        ),
        producerExceptionalPath = listOf(ArcCoroutineOwnershipOperation.ExceptionalExit),
        consumerExceptionalPath = null,
        consumerMayThrow = false,
        reduction = ArcCoroutineOwnershipReduction(updateStackRefs = 0, updateReturnRefs = 1),
    )
}

private fun ArcCoroutineOwnershipCandidate.borrowThenMovePlan(): ArcCoroutineOwnershipPlan? {
    val result = resultSlot ?: return null
    if (!hasBorrowEligibility() || !hasMoveEligibility()) return null

    return ArcCoroutineOwnershipPlan(
        transferKind = transferKind,
        spillSlot = spillSlot,
        resultSlot = result,
        normalPath = listOf(
            ArcCoroutineOwnershipOperation.InitializeOwned(spillSlot),
            ArcCoroutineOwnershipOperation.BeginBorrow(spillSlot, borrow),
            ArcCoroutineOwnershipOperation.EndBorrow(borrow),
            ArcCoroutineOwnershipOperation.MoveOwned(spillSlot, result),
            ArcCoroutineOwnershipOperation.ReturnOwnedResult,
        ),
        producerExceptionalPath = listOf(ArcCoroutineOwnershipOperation.ExceptionalExit),
        consumerExceptionalPath = consumerExceptionalPath(),
        consumerMayThrow = borrowedConsumerMayThrow,
        reduction = ArcCoroutineOwnershipReduction(updateStackRefs = 1, updateReturnRefs = 1),
    )
}

private fun ArcCoroutineOwnershipCandidate.borrowOnlyPlan(): ArcCoroutineOwnershipPlan? {
    if (resultSlot != null || !hasBorrowEligibility()) return null

    return ArcCoroutineOwnershipPlan(
        transferKind = transferKind,
        spillSlot = spillSlot,
        resultSlot = null,
        normalPath = listOf(
            ArcCoroutineOwnershipOperation.InitializeOwned(spillSlot),
            ArcCoroutineOwnershipOperation.BeginBorrow(spillSlot, borrow),
            ArcCoroutineOwnershipOperation.EndBorrow(borrow),
            ArcCoroutineOwnershipOperation.DestroyOwned(spillSlot),
            ArcCoroutineOwnershipOperation.ExitNormally,
        ),
        producerExceptionalPath = listOf(ArcCoroutineOwnershipOperation.ExceptionalExit),
        consumerExceptionalPath = consumerExceptionalPath(),
        consumerMayThrow = borrowedConsumerMayThrow,
        reduction = ArcCoroutineOwnershipReduction(updateStackRefs = 1, updateReturnRefs = 0),
    )
}

private fun ArcCoroutineOwnershipCandidate.consumerExceptionalPath(): List<ArcCoroutineOwnershipOperation>? =
    if (!borrowedConsumerMayThrow) null else listOf(
        ArcCoroutineOwnershipOperation.EndBorrow(borrow),
        ArcCoroutineOwnershipOperation.DestroyOwned(spillSlot),
        ArcCoroutineOwnershipOperation.ExceptionalExit,
    )

/** Replays every edge and proves that each +1 is consumed exactly once and no borrow escapes. */
internal object ArcCoroutineOwnershipPlanVerifier {
    fun verify(plan: ArcCoroutineOwnershipPlan): Boolean {
        val normalBorrow = (plan.normalPath.singleOrNull { it is ArcCoroutineOwnershipOperation.BeginBorrow }
                as? ArcCoroutineOwnershipOperation.BeginBorrow)?.borrow
        if (!hasExactShape(plan, normalBorrow)) return false
        if (plan.reduction.updateStackRefs < 0 || plan.reduction.updateReturnRefs < 0) return false
        if (plan.reduction != recomputeReduction(plan)) return false

        return verifyPath(plan.normalPath, plan.spillSlot, plan.resultSlot) &&
                verifyPath(plan.producerExceptionalPath, plan.spillSlot, plan.resultSlot) &&
                (plan.consumerExceptionalPath?.let {
                    verifyPath(it, plan.spillSlot, plan.resultSlot, spillInitiallyOwned = true, initialBorrow = normalBorrow)
                } ?: true)
    }

    private fun hasExactShape(plan: ArcCoroutineOwnershipPlan, normalBorrow: ArcCoroutineBorrow?): Boolean {
        if (plan.producerExceptionalPath != listOf(ArcCoroutineOwnershipOperation.ExceptionalExit)) return false

        val expectedNormal = when (plan.transferKind) {
            ArcCoroutineTransferKind.MoveOwnedToResult -> {
                val result = plan.resultSlot ?: return false
                if (result === plan.spillSlot || plan.consumerMayThrow || normalBorrow != null) return false
                listOf(
                    ArcCoroutineOwnershipOperation.InitializeOwned(plan.spillSlot),
                    ArcCoroutineOwnershipOperation.MoveOwned(plan.spillSlot, result),
                    ArcCoroutineOwnershipOperation.ReturnOwnedResult,
                )
            }
            ArcCoroutineTransferKind.BorrowThenMove -> {
                val result = plan.resultSlot ?: return false
                val borrow = normalBorrow ?: return false
                if (result === plan.spillSlot) return false
                listOf(
                    ArcCoroutineOwnershipOperation.InitializeOwned(plan.spillSlot),
                    ArcCoroutineOwnershipOperation.BeginBorrow(plan.spillSlot, borrow),
                    ArcCoroutineOwnershipOperation.EndBorrow(borrow),
                    ArcCoroutineOwnershipOperation.MoveOwned(plan.spillSlot, result),
                    ArcCoroutineOwnershipOperation.ReturnOwnedResult,
                )
            }
            ArcCoroutineTransferKind.BorrowOnly -> {
                if (plan.resultSlot != null) return false
                val borrow = normalBorrow ?: return false
                listOf(
                    ArcCoroutineOwnershipOperation.InitializeOwned(plan.spillSlot),
                    ArcCoroutineOwnershipOperation.BeginBorrow(plan.spillSlot, borrow),
                    ArcCoroutineOwnershipOperation.EndBorrow(borrow),
                    ArcCoroutineOwnershipOperation.DestroyOwned(plan.spillSlot),
                    ArcCoroutineOwnershipOperation.ExitNormally,
                )
            }
        }
        if (plan.normalPath != expectedNormal) return false

        val expectedConsumerExceptional = if (!plan.consumerMayThrow) null else {
            val borrow = normalBorrow ?: return false
            listOf(
                ArcCoroutineOwnershipOperation.EndBorrow(borrow),
                ArcCoroutineOwnershipOperation.DestroyOwned(plan.spillSlot),
                ArcCoroutineOwnershipOperation.ExceptionalExit,
            )
        }
        return plan.consumerExceptionalPath == expectedConsumerExceptional
    }

    private fun recomputeReduction(plan: ArcCoroutineOwnershipPlan): ArcCoroutineOwnershipReduction =
        ArcCoroutineOwnershipReduction(
            updateStackRefs = plan.normalPath.count { it is ArcCoroutineOwnershipOperation.BeginBorrow },
            updateReturnRefs = plan.normalPath.count { it is ArcCoroutineOwnershipOperation.MoveOwned },
        )

    private fun verifyPath(
        operations: List<ArcCoroutineOwnershipOperation>,
        spillSlot: ArcCoroutineSlot,
        resultSlot: ArcCoroutineSlot?,
        spillInitiallyOwned: Boolean = false,
        initialBorrow: ArcCoroutineBorrow? = null,
    ): Boolean {
        var spillOwned = spillInitiallyOwned
        var resultOwned = false
        var liveBorrow: ArcCoroutineBorrow? = initialBorrow
        var exited = false

        for (operation in operations) {
            if (exited) return false
            when (operation) {
                is ArcCoroutineOwnershipOperation.InitializeOwned -> {
                    if (operation.slot != spillSlot || spillOwned) return false
                    spillOwned = true
                }
                is ArcCoroutineOwnershipOperation.BeginBorrow -> {
                    if (operation.slot != spillSlot || !spillOwned || liveBorrow != null) return false
                    liveBorrow = operation.borrow
                }
                is ArcCoroutineOwnershipOperation.EndBorrow -> {
                    if (operation.borrow != liveBorrow) return false
                    liveBorrow = null
                }
                is ArcCoroutineOwnershipOperation.MoveOwned -> {
                    if (operation.from != spillSlot || operation.to != resultSlot || !spillOwned || resultOwned || liveBorrow != null) return false
                    spillOwned = false
                    resultOwned = true
                }
                is ArcCoroutineOwnershipOperation.DestroyOwned -> {
                    if (operation.slot != spillSlot || !spillOwned || liveBorrow != null) return false
                    spillOwned = false
                }
                ArcCoroutineOwnershipOperation.ReturnOwnedResult -> {
                    if (!resultOwned || spillOwned || liveBorrow != null) return false
                    resultOwned = false
                    exited = true
                }
                ArcCoroutineOwnershipOperation.ExitNormally,
                ArcCoroutineOwnershipOperation.ExceptionalExit -> {
                    if (spillOwned || resultOwned || liveBorrow != null) return false
                    exited = true
                }
            }
        }
        return exited && !spillOwned && !resultOwned && liveBorrow == null
    }
}
