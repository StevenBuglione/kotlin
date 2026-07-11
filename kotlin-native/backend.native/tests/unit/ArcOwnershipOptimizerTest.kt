/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.llvm.Lifetime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcOwnershipOptimizerTest {
    private val entry = ArcBlockId("entry")

    @Test
    fun escapeLifetimesClassifyProducedReferences() {
        assertSame(ArcOwnership.Immortal, classifyArcProducedReference(isPermanent = true, lifetime = Lifetime.GLOBAL))
        assertSame(ArcOwnership.Guaranteed, classifyArcProducedReference(isPermanent = false, lifetime = Lifetime.STACK))
        assertSame(ArcOwnership.Guaranteed, classifyArcProducedReference(isPermanent = false, lifetime = Lifetime.LOCAL))
        assertSame(
            ArcOwnership.Owned,
            classifyArcProducedReference(isPermanent = false, lifetime = Lifetime.STACK, requiresHeapAllocation = true),
        )
        assertSame(ArcOwnership.Owned, classifyArcProducedReference(isPermanent = false, lifetime = Lifetime.RETURN_VALUE))
        assertSame(ArcOwnership.Owned, classifyArcProducedReference(isPermanent = false, lifetime = Lifetime.GLOBAL))
        assertSame(ArcOwnership.Owned, classifyArcProducedReference(isPermanent = false, lifetime = null))
    }

    @Test
    fun forwardsOwnedProducerResultIntoReturnedCopy() {
        val allocated = ArcValue("allocated")
        val returned = ArcValue("returned")
        val input = plan(
            operations = listOf(
                ArcOperation.Define(allocated, ArcOwnership.Owned),
                ArcOperation.Copy(allocated, returned),
                ArcOperation.Destroy(allocated),
            ),
            terminator = ArcTerminator.Return(returned),
        )

        val result = ArcOwnershipOptimizer.optimizeVerified(input)
        val operations = result.plan.blocks.getValue(entry).operations

        assertEquals(listOf(ArcOperation.Define(returned, ArcOwnership.Owned)), operations)
        assertEquals(1, result.metrics.forwardedOwnedResults)
        assertEquals(0, result.metrics.copyDestroyPairsEliminated)
        assertEquals(2, result.metrics.eliminatedReferenceCountingOperations)
        assertEquals(100, result.metrics.eliminationPercentage)
        assertSame(ArcOwnershipVerificationResult.Success, ArcOwnershipVerifier.verify(result.plan))
    }

    @Test
    fun eliminatesDeadCopyDestroyPair() {
        val argument = ArcValue("argument")
        val temporary = ArcValue("temporary")
        val input = plan(
            operations = listOf(
                ArcOperation.Copy(argument, temporary),
                ArcOperation.Destroy(temporary),
            ),
            entryValues = mapOf(argument to ArcOwnership.Guaranteed),
        )

        val result = ArcOwnershipOptimizer.optimizeVerified(input)

        assertTrue(result.plan.blocks.getValue(entry).operations.isEmpty())
        assertEquals(0, result.metrics.forwardedOwnedResults)
        assertEquals(1, result.metrics.copyDestroyPairsEliminated)
        assertEquals(2, result.metrics.eliminatedReferenceCountingOperations)
        assertSame(ArcOwnershipVerificationResult.Success, ArcOwnershipVerifier.verify(result.plan))
    }

    @Test
    fun keepsCopyThatFeedsStrongStorage() {
        val argument = ArcValue("argument")
        val temporary = ArcValue("temporary")
        val input = plan(
            operations = listOf(
                ArcOperation.Copy(argument, temporary),
                ArcOperation.StrongStore(ArcStorage("field"), temporary),
                ArcOperation.Destroy(temporary),
            ),
            entryValues = mapOf(argument to ArcOwnership.Guaranteed),
        )

        val result = ArcOwnershipOptimizer.optimizeVerified(input)

        assertSame(input, result.plan)
        assertEquals(0, result.metrics.plansChanged)
        assertEquals(0, result.metrics.eliminatedReferenceCountingOperations)
    }

    @Test
    fun eliminatesContainedOwnedCopyUsedByBorrowsAndStrongStores() {
        val source = ArcValue("source")
        val copied = ArcValue("copied")
        val borrowed = ArcValue("borrowed")
        val input = plan(
            operations = listOf(
                ArcOperation.Copy(source, copied),
                ArcOperation.Borrow(copied, borrowed),
                ArcOperation.StrongStore(ArcStorage("directField"), copied),
                ArcOperation.StrongStore(ArcStorage("borrowedField"), borrowed),
                ArcOperation.Destroy(copied),
                ArcOperation.Destroy(source),
            ),
            entryValues = mapOf(source to ArcOwnership.Owned),
        )

        val result = ArcOwnershipOptimizer.optimizeVerified(input)

        assertEquals(
            listOf(
                ArcOperation.Borrow(source, borrowed),
                ArcOperation.StrongStore(ArcStorage("directField"), source),
                ArcOperation.StrongStore(ArcStorage("borrowedField"), borrowed),
                ArcOperation.Destroy(source),
            ),
            result.plan.blocks.getValue(entry).operations,
        )
        assertEquals(1, result.metrics.containedOwnedCopiesEliminated)
        assertEquals(0, result.metrics.copyDestroyPairsEliminated)
        assertEquals(2, result.metrics.eliminatedReferenceCountingOperations)
        assertTrue(result.metrics.render().contains("1 contained owned copies eliminated"))
        assertSame(ArcOwnershipVerificationResult.Success, ArcOwnershipVerifier.verify(result.plan))
    }

    @Test
    fun eliminatesContainedOwnedCopyWhenSourceIsReturned() {
        val source = ArcValue("source")
        val copied = ArcValue("copied")
        val input = plan(
            operations = listOf(
                ArcOperation.Copy(source, copied),
                ArcOperation.StrongStore(ArcStorage("field"), copied),
                ArcOperation.Destroy(copied),
            ),
            entryValues = mapOf(source to ArcOwnership.Owned),
            terminator = ArcTerminator.Return(source),
        )

        val result = ArcOwnershipOptimizer.optimizeVerified(input)

        assertEquals(
            listOf(ArcOperation.StrongStore(ArcStorage("field"), source)),
            result.plan.blocks.getValue(entry).operations,
        )
        assertEquals(1, result.metrics.containedOwnedCopiesEliminated)
        assertSame(ArcOwnershipVerificationResult.Success, ArcOwnershipVerifier.verify(result.plan))
    }

    @Test
    fun keepsContainedCopyWhenSourceDiesBeforeCopiedValue() {
        val source = ArcValue("source")
        val copied = ArcValue("copied")
        val input = plan(
            operations = listOf(
                ArcOperation.Copy(source, copied),
                ArcOperation.StrongStore(ArcStorage("field"), copied),
                ArcOperation.Destroy(source),
                ArcOperation.Destroy(copied),
            ),
            entryValues = mapOf(source to ArcOwnership.Owned),
        )

        val result = ArcOwnershipOptimizer.optimizeVerified(input)

        assertSame(input, result.plan)
        assertEquals(0, result.metrics.containedOwnedCopiesEliminated)
    }

    @Test
    fun keepsContainedCopyWhoseResultIsReturned() {
        val source = ArcValue("source")
        val copied = ArcValue("copied")
        val input = plan(
            operations = listOf(
                ArcOperation.Copy(source, copied),
                ArcOperation.StrongStore(ArcStorage("field"), source),
                ArcOperation.Destroy(source),
            ),
            entryValues = mapOf(source to ArcOwnership.Owned),
            terminator = ArcTerminator.Return(copied),
        )

        val result = ArcOwnershipOptimizer.optimizeVerified(input)

        assertSame(input, result.plan)
        assertEquals(0, result.metrics.containedOwnedCopiesEliminated)
    }

    @Test
    fun keepsContainedCopyWhenBorrowEscapesItsLifetime() {
        val source = ArcValue("source")
        val copied = ArcValue("copied")
        val borrowed = ArcValue("borrowed")
        val input = plan(
            operations = listOf(
                ArcOperation.Copy(source, copied),
                ArcOperation.Borrow(copied, borrowed),
                ArcOperation.Destroy(copied),
                ArcOperation.StrongStore(ArcStorage("field"), borrowed),
                ArcOperation.Destroy(source),
            ),
            entryValues = mapOf(source to ArcOwnership.Owned),
        )

        val result = ArcOwnershipOptimizer.optimizeVerified(input)

        assertSame(input, result.plan)
        assertEquals(0, result.metrics.containedOwnedCopiesEliminated)
    }

    @Test
    fun keepsContainedCopyWithAnAmbiguousConsumingUse() {
        val source = ArcValue("source")
        val copied = ArcValue("copied")
        val escapedCopy = ArcValue("escapedCopy")
        val input = plan(
            operations = listOf(
                ArcOperation.Copy(source, copied),
                ArcOperation.Copy(copied, escapedCopy),
                ArcOperation.StrongStore(ArcStorage("field"), escapedCopy),
                ArcOperation.Destroy(copied),
                ArcOperation.Destroy(escapedCopy),
                ArcOperation.Destroy(source),
            ),
            entryValues = mapOf(source to ArcOwnership.Owned),
        )

        val result = ArcOwnershipOptimizer.optimizeVerified(input)

        assertSame(input, result.plan)
        assertEquals(0, result.metrics.containedOwnedCopiesEliminated)
    }

    @Test
    fun curatedPlanReportsCompleteNaiveCopyDestroyElimination() {
        val argument = ArcValue("argument")
        val operations = buildList {
            repeat(10) { index ->
                val temporary = ArcValue("temporary$index")
                add(ArcOperation.Copy(argument, temporary))
                add(ArcOperation.Destroy(temporary))
            }
        }
        val input = plan(operations, entryValues = mapOf(argument to ArcOwnership.Guaranteed))

        val result = ArcOwnershipOptimizer.optimizeVerified(input)

        assertEquals(10, result.metrics.copyDestroyPairsEliminated)
        assertEquals(20, result.metrics.referenceCountingOperationsBefore)
        assertEquals(0, result.metrics.referenceCountingOperationsAfter)
        assertEquals(100, result.metrics.eliminationPercentage)
        assertTrue(result.metrics.render().contains("20/20 reference-counting operations eliminated (100%)"))
    }

    private fun plan(
        operations: List<ArcOperation>,
        entryValues: Map<ArcValue, ArcOwnership> = emptyMap(),
        terminator: ArcTerminator = ArcTerminator.Return(),
    ): ArcFunctionPlan {
        val block = ArcBasicBlock(entry, operations, terminator)
        return ArcFunctionPlan("optimizerTest", entry, entryValues, emptySet(), mapOf(entry to block))
    }
}
