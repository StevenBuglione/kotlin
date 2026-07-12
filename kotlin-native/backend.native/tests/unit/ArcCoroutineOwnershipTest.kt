/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcCoroutineOwnershipTest {
    private val spill = ArcCoroutineSlot("continuation.result")
    private val result = ArcCoroutineSlot("caller.returnSlot")

    @Test
    fun selectsExactOwnedSpillMove() {
        val plan = requireNotNull(ArcCoroutineOwnershipAnalysis.select(candidate(ArcCoroutineTransferKind.MoveOwnedToResult)))

        assertEquals(ArcCoroutineOwnershipReduction(0, 1), plan.reduction)
        assertEquals(
            listOf(
                ArcCoroutineOwnershipOperation.InitializeOwned(spill),
                ArcCoroutineOwnershipOperation.MoveOwned(spill, result),
                ArcCoroutineOwnershipOperation.ReturnOwnedResult,
            ),
            plan.normalPath,
        )
        assertEquals(listOf(ArcCoroutineOwnershipOperation.ExceptionalExit), plan.producerExceptionalPath)
        assertNull(plan.consumerExceptionalPath)
        assertTrue(ArcCoroutineOwnershipPlanVerifier.verify(plan))
    }

    @Test
    fun selectsBorrowThenMoveAndCleansUpOnConsumerException() {
        val plan = requireNotNull(ArcCoroutineOwnershipAnalysis.select(candidate(ArcCoroutineTransferKind.BorrowThenMove)))

        assertEquals(ArcCoroutineOwnershipReduction(1, 1), plan.reduction)
        assertEquals(
            listOf(
                ArcCoroutineOwnershipOperation.EndBorrow(borrowOf(plan)),
                ArcCoroutineOwnershipOperation.DestroyOwned(spill),
                ArcCoroutineOwnershipOperation.ExceptionalExit,
            ),
            plan.consumerExceptionalPath,
        )
        assertTrue(ArcCoroutineOwnershipPlanVerifier.verify(plan))
    }

    @Test
    fun selectsBorrowOnlyWithoutResultSlotTraffic() {
        val plan = requireNotNull(
            ArcCoroutineOwnershipAnalysis.select(
                candidate(ArcCoroutineTransferKind.BorrowOnly).copy(
                    resultSlot = null,
                    exactResultSlotIdentity = false,
                    spillLastUseAtMove = false,
                )
            )
        )

        assertEquals(ArcCoroutineOwnershipReduction(1, 0), plan.reduction)
        assertEquals(ArcCoroutineOwnershipOperation.ExitNormally, plan.normalPath.last())
        assertTrue(ArcCoroutineOwnershipPlanVerifier.verify(plan))
    }

    @Test
    fun nonThrowingBorrowHasNoSyntheticExceptionalConsumerEdge() {
        val plan = requireNotNull(
            ArcCoroutineOwnershipAnalysis.select(
                candidate(ArcCoroutineTransferKind.BorrowThenMove).copy(
                    borrowedConsumerMayThrow = false,
                    borrowEndsOnExceptionalEdge = false,
                )
            )
        )

        assertNull(plan.consumerExceptionalPath)
    }

    @Test
    fun rejectsDisabledOrUnoptimizedOrDebugCompilation() {
        val base = candidate(ArcCoroutineTransferKind.MoveOwnedToResult)
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(arcEnabled = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(optimizationsEnabled = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(debugInfoDisabled = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(loweredCoroutineFunction = false)))
    }

    @Test
    fun rejectsNonExactCallShapes() {
        val base = candidate(ArcCoroutineTransferKind.MoveOwnedToResult)
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(exactDirectKotlinCall = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(nonExternalCall = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(nonVirtualCall = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(ownedReferenceResult = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(exactSingleSpillSlot = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(exactResultSlotIdentity = false)))
    }

    @Test
    fun rejectsSuspensionTryForeignAndAmbiguousPaths() {
        val base = candidate(ArcCoroutineTransferKind.BorrowThenMove)
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(noSuspensionBoundary = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(noTryBoundary = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(noForeignCall = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(linearUnambiguousPath = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(noAliasOrEscape = false)))
    }

    @Test
    fun rejectsIncompleteNormalOrExceptionalOwnershipProofs() {
        val base = candidate(ArcCoroutineTransferKind.BorrowThenMove)
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(producerInitializesSpillOnNormalEdge = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(producerLeavesSpillUninitializedOnExceptionalEdge = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(exactSingleBorrowCopy = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(borrowEndsOnNormalEdge = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(borrowEndsOnExceptionalEdge = false)))
        assertNull(ArcCoroutineOwnershipAnalysis.select(base.copy(spillLastUseAtMove = false)))
    }

    @Test
    fun verifierRejectsMoveWhileBorrowIsLive() {
        val plan = ArcCoroutineOwnershipPlan(
            transferKind = ArcCoroutineTransferKind.BorrowThenMove,
            spillSlot = spill,
            resultSlot = result,
            normalPath = listOf(
                ArcCoroutineOwnershipOperation.InitializeOwned(spill),
                ArcCoroutineOwnershipOperation.BeginBorrow(spill, ArcCoroutineBorrow("consumer")),
                ArcCoroutineOwnershipOperation.MoveOwned(spill, result),
                ArcCoroutineOwnershipOperation.ReturnOwnedResult,
            ),
            producerExceptionalPath = listOf(ArcCoroutineOwnershipOperation.ExceptionalExit),
            consumerExceptionalPath = null,
            consumerMayThrow = false,
            reduction = ArcCoroutineOwnershipReduction(1, 1),
        )

        assertFalse(ArcCoroutineOwnershipPlanVerifier.verify(plan))
    }

    @Test
    fun slotIdentityIsOpaqueAndEqualSlotsAreRejected() {
        val sameNameResult = ArcCoroutineSlot(spill.debugName)
        val valid = requireNotNull(
            ArcCoroutineOwnershipAnalysis.select(
                candidate(ArcCoroutineTransferKind.MoveOwnedToResult).copy(resultSlot = sameNameResult)
            )
        )
        assertTrue(ArcCoroutineOwnershipPlanVerifier.verify(valid))

        assertNull(
            ArcCoroutineOwnershipAnalysis.select(
                candidate(ArcCoroutineTransferKind.MoveOwnedToResult).copy(resultSlot = spill)
            )
        )

        val impostorSpill = ArcCoroutineSlot(spill.debugName)
        assertFalse(
            ArcCoroutineOwnershipPlanVerifier.verify(
                valid.copy(normalPath = valid.normalPath.toMutableList().also {
                    it[0] = ArcCoroutineOwnershipOperation.InitializeOwned(impostorSpill)
                })
            )
        )
    }

    @Test
    fun borrowTokenMustMatchOnNormalAndExceptionalEdges() {
        val plan = selected(ArcCoroutineTransferKind.BorrowThenMove)
        val otherBorrow = ArcCoroutineBorrow("consumer")

        assertFalse(
            ArcCoroutineOwnershipPlanVerifier.verify(
                plan.copy(normalPath = plan.normalPath.toMutableList().also {
                    it[2] = ArcCoroutineOwnershipOperation.EndBorrow(otherBorrow)
                })
            )
        )
        assertFalse(
            ArcCoroutineOwnershipPlanVerifier.verify(
                plan.copy(consumerExceptionalPath = listOf(
                    ArcCoroutineOwnershipOperation.EndBorrow(otherBorrow),
                    ArcCoroutineOwnershipOperation.DestroyOwned(spill),
                    ArcCoroutineOwnershipOperation.ExceptionalExit,
                ))
            )
        )
    }

    @Test
    fun exactEdgeRolesRejectWrongTerminalsAndProducerCleanup() {
        val plan = selected(ArcCoroutineTransferKind.MoveOwnedToResult)
        assertFalse(
            ArcCoroutineOwnershipPlanVerifier.verify(
                plan.copy(normalPath = plan.normalPath.dropLast(1) + ArcCoroutineOwnershipOperation.ExceptionalExit)
            )
        )
        assertFalse(
            ArcCoroutineOwnershipPlanVerifier.verify(
                plan.copy(producerExceptionalPath = listOf(
                    ArcCoroutineOwnershipOperation.InitializeOwned(spill),
                    ArcCoroutineOwnershipOperation.ExceptionalExit,
                ))
            )
        )
        assertFalse(
            ArcCoroutineOwnershipPlanVerifier.verify(
                plan.copy(producerExceptionalPath = listOf(
                    ArcCoroutineOwnershipOperation.DestroyOwned(spill),
                    ArcCoroutineOwnershipOperation.ExceptionalExit,
                ))
            )
        )
    }

    @Test
    fun throwingConsumerRequiresOneExactCleanupEdge() {
        val plan = selected(ArcCoroutineTransferKind.BorrowThenMove)
        assertFalse(ArcCoroutineOwnershipPlanVerifier.verify(plan.copy(consumerExceptionalPath = null)))
        assertFalse(
            ArcCoroutineOwnershipPlanVerifier.verify(
                plan.copy(consumerExceptionalPath = listOf(
                    ArcCoroutineOwnershipOperation.EndBorrow(borrowOf(plan)),
                    ArcCoroutineOwnershipOperation.ExceptionalExit,
                ))
            )
        )
        assertFalse(
            ArcCoroutineOwnershipPlanVerifier.verify(
                plan.copy(consumerExceptionalPath = listOf(
                    ArcCoroutineOwnershipOperation.EndBorrow(borrowOf(plan)),
                    ArcCoroutineOwnershipOperation.MoveOwned(spill, result),
                    ArcCoroutineOwnershipOperation.ExceptionalExit,
                ))
            )
        )
    }

    @Test
    fun transferKindsRequireTheirExactResultSlotShape() {
        assertNull(
            ArcCoroutineOwnershipAnalysis.select(
                candidate(ArcCoroutineTransferKind.BorrowOnly)
            )
        )
        assertNull(
            ArcCoroutineOwnershipAnalysis.select(
                candidate(ArcCoroutineTransferKind.MoveOwnedToResult).copy(resultSlot = null)
            )
        )
    }

    @Test
    fun verifierRejectsNegativeInflatedAndMismatchedReductions() {
        val plan = selected(ArcCoroutineTransferKind.BorrowThenMove)
        assertFalse(ArcCoroutineOwnershipPlanVerifier.verify(plan.copy(reduction = ArcCoroutineOwnershipReduction(-1, 1))))
        assertFalse(ArcCoroutineOwnershipPlanVerifier.verify(plan.copy(reduction = ArcCoroutineOwnershipReduction(2, 1))))
        assertFalse(ArcCoroutineOwnershipPlanVerifier.verify(plan.copy(reduction = ArcCoroutineOwnershipReduction(1, 2))))
        assertFalse(ArcCoroutineOwnershipPlanVerifier.verify(plan.copy(reduction = ArcCoroutineOwnershipReduction(0, 0))))
    }

    @Test
    fun verifierRejectsDuplicateOwnershipOperations() {
        val plan = selected(ArcCoroutineTransferKind.BorrowOnly)
        val duplicateDestroy = plan.normalPath.toMutableList().also {
            it.add(it.lastIndex, ArcCoroutineOwnershipOperation.DestroyOwned(spill))
        }
        assertFalse(ArcCoroutineOwnershipPlanVerifier.verify(plan.copy(normalPath = duplicateDestroy)))
    }

    @Test
    fun nonThrowingBorrowTransfersRejectSyntheticConsumerExceptionalEdges() {
        val borrowThenMove = requireNotNull(
            ArcCoroutineOwnershipAnalysis.select(
                candidate(ArcCoroutineTransferKind.BorrowThenMove).copy(
                    borrowedConsumerMayThrow = false,
                    borrowEndsOnExceptionalEdge = false,
                )
            )
        )
        assertFalse(
            ArcCoroutineOwnershipPlanVerifier.verify(
                borrowThenMove.copy(consumerExceptionalPath = syntheticConsumerExceptionalPath(borrowThenMove))
            )
        )

        val borrowOnly = requireNotNull(
            ArcCoroutineOwnershipAnalysis.select(
                candidate(ArcCoroutineTransferKind.BorrowOnly).copy(
                    resultSlot = null,
                    borrowedConsumerMayThrow = false,
                    borrowEndsOnExceptionalEdge = false,
                )
            )
        )
        assertFalse(
            ArcCoroutineOwnershipPlanVerifier.verify(
                borrowOnly.copy(consumerExceptionalPath = syntheticConsumerExceptionalPath(borrowOnly))
            )
        )
    }

    @Test
    fun sameNameImpostorResultSlotIsRejected() {
        val plan = selected(ArcCoroutineTransferKind.MoveOwnedToResult)
        val impostorResult = ArcCoroutineSlot(result.debugName)
        assertFalse(
            ArcCoroutineOwnershipPlanVerifier.verify(
                plan.copy(normalPath = plan.normalPath.toMutableList().also {
                    it[1] = ArcCoroutineOwnershipOperation.MoveOwned(spill, impostorResult)
                })
            )
        )
    }

    @Test
    fun adjustedReductionCannotAuthorizeExtraBorrowOrMoveOperations() {
        val borrowPlan = selected(ArcCoroutineTransferKind.BorrowThenMove)
        val extraBorrow = ArcCoroutineBorrow("extra")
        val withExtraBorrow = borrowPlan.normalPath.toMutableList().also {
            it.add(3, ArcCoroutineOwnershipOperation.BeginBorrow(spill, extraBorrow))
            it.add(4, ArcCoroutineOwnershipOperation.EndBorrow(extraBorrow))
        }
        assertFalse(
            ArcCoroutineOwnershipPlanVerifier.verify(
                borrowPlan.copy(
                    normalPath = withExtraBorrow,
                    reduction = ArcCoroutineOwnershipReduction(updateStackRefs = 2, updateReturnRefs = 1),
                )
            )
        )

        val movePlan = selected(ArcCoroutineTransferKind.MoveOwnedToResult)
        val withExtraMove = movePlan.normalPath.toMutableList().also {
            it.add(2, ArcCoroutineOwnershipOperation.MoveOwned(spill, result))
        }
        assertFalse(
            ArcCoroutineOwnershipPlanVerifier.verify(
                movePlan.copy(
                    normalPath = withExtraMove,
                    reduction = ArcCoroutineOwnershipReduction(updateStackRefs = 0, updateReturnRefs = 2),
                )
            )
        )
    }

    private fun selected(kind: ArcCoroutineTransferKind): ArcCoroutineOwnershipPlan {
        val input = candidate(kind).let {
            if (kind === ArcCoroutineTransferKind.BorrowOnly) it.copy(resultSlot = null) else it
        }
        return requireNotNull(ArcCoroutineOwnershipAnalysis.select(input))
    }

    private fun borrowOf(plan: ArcCoroutineOwnershipPlan): ArcCoroutineBorrow =
        (plan.normalPath.single { it is ArcCoroutineOwnershipOperation.BeginBorrow }
                as ArcCoroutineOwnershipOperation.BeginBorrow).borrow

    private fun syntheticConsumerExceptionalPath(plan: ArcCoroutineOwnershipPlan) = listOf(
        ArcCoroutineOwnershipOperation.EndBorrow(borrowOf(plan)),
        ArcCoroutineOwnershipOperation.DestroyOwned(spill),
        ArcCoroutineOwnershipOperation.ExceptionalExit,
    )

    private fun candidate(kind: ArcCoroutineTransferKind) = ArcCoroutineOwnershipCandidate(
        transferKind = kind,
        spillSlot = spill,
        resultSlot = result,
        borrow = ArcCoroutineBorrow("consumer"),
        arcEnabled = true,
        optimizationsEnabled = true,
        debugInfoDisabled = true,
        loweredCoroutineFunction = true,
        exactDirectKotlinCall = true,
        nonExternalCall = true,
        nonVirtualCall = true,
        ownedReferenceResult = true,
        exactSingleSpillSlot = true,
        producerInitializesSpillOnNormalEdge = true,
        producerLeavesSpillUninitializedOnExceptionalEdge = true,
        noSuspensionBoundary = true,
        noTryBoundary = true,
        noForeignCall = true,
        linearUnambiguousPath = true,
        noAliasOrEscape = true,
        exactResultSlotIdentity = true,
        spillLastUseAtMove = true,
        exactSingleBorrowCopy = true,
        borrowEndsOnNormalEdge = true,
        borrowedConsumerMayThrow = true,
        borrowEndsOnExceptionalEdge = true,
    )
}
