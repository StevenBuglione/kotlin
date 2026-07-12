/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcOwnershipPlanningTest {
    @Test
    fun borrowedMutableReadAuthorizationRequiresEverySafetyGate() {
        val eligible = ArcBorrowedMutableReadEligibility(
            arcEnabled = true,
            debugInfoDisabled = true,
            directKotlinCall = true,
            lastExplicitArgument = true,
            mutableLocalReference = true,
            notCaptured = true,
            strongStorage = true,
            sideEffectFreeArgumentWrapper = true,
        )

        assertTrue(eligible.isAuthorized())
        assertFalse(eligible.copy(arcEnabled = false).isAuthorized())
        assertFalse(eligible.copy(debugInfoDisabled = false).isAuthorized())
        assertFalse(eligible.copy(directKotlinCall = false).isAuthorized())
        assertFalse(eligible.copy(lastExplicitArgument = false).isAuthorized())
        assertFalse(eligible.copy(mutableLocalReference = false).isAuthorized())
        assertFalse(eligible.copy(notCaptured = false).isAuthorized())
        assertFalse(eligible.copy(strongStorage = false).isAuthorized())
        assertFalse(eligible.copy(sideEffectFreeArgumentWrapper = false).isAuthorized())
    }

    @Test
    fun borrowedGuaranteedAliasAuthorizationRequiresEverySafetyGate() {
        val eligible = ArcBorrowedGuaranteedAliasEligibility(
            arcEnabled = true,
            debugInfoDisabled = true,
            nonSuspendFunction = true,
            mutableLocalReference = true,
            initializedFromGuaranteedParameter = true,
            strongStorage = true,
            exactlyOneUse = true,
            neverAssigned = true,
            notCaptured = true,
            notReturned = true,
            finalExplicitReferenceArgument = true,
            directKotlinCall = true,
        )

        assertTrue(eligible.isAuthorized())
        assertFalse(eligible.copy(arcEnabled = false).isAuthorized())
        assertFalse(eligible.copy(debugInfoDisabled = false).isAuthorized())
        assertFalse(eligible.copy(nonSuspendFunction = false).isAuthorized())
        assertFalse(eligible.copy(mutableLocalReference = false).isAuthorized())
        assertFalse(eligible.copy(initializedFromGuaranteedParameter = false).isAuthorized())
        assertFalse(eligible.copy(strongStorage = false).isAuthorized())
        assertFalse(eligible.copy(exactlyOneUse = false).isAuthorized())
        assertFalse(eligible.copy(neverAssigned = false).isAuthorized())
        assertFalse(eligible.copy(notCaptured = false).isAuthorized())
        assertFalse(eligible.copy(notReturned = false).isAuthorized())
        assertFalse(eligible.copy(finalExplicitReferenceArgument = false).isAuthorized())
        assertFalse(eligible.copy(directKotlinCall = false).isAuthorized())
    }

    @Test
    fun unitIfPlanBuildsVerifiedDiamondAndExposesCfgCopyOptimization() {
        val source = ArcValue("source")
        val alias = ArcValue("alias")
        val field = ArcStorage("Holder.value")

        val plan = buildCuratedArcUnitIfPlan(
            functionName = "branchStore",
            entryValues = emptyMap(),
            entryInitializedStorage = emptySet(),
            entryOperations = listOf(
                ArcOperation.Define(source, ArcOwnership.Owned),
                ArcOperation.Copy(source, alias),
            ),
            trueOperations = listOf(ArcOperation.StrongStore(field, alias)),
            falseOperations = listOf(ArcOperation.StrongStore(field, alias)),
            mergeOperations = listOf(ArcOperation.Destroy(alias), ArcOperation.Destroy(source)),
            returnedValue = null,
        ) ?: error("expected curated Unit if plan")

        val entry = ArcBlockId("entry")
        val thenBlock = ArcBlockId("when0_then")
        val elseBlock = ArcBlockId("when0_else")
        val merge = ArcBlockId("when0_merge")
        assertEquals(listOf(entry, thenBlock, elseBlock, merge), plan.blocks.keys.toList())
        assertEquals(ArcTerminator.Branch(thenBlock, elseBlock), plan.blocks.getValue(entry).terminator)
        assertEquals(ArcTerminator.Jump(merge), plan.blocks.getValue(thenBlock).terminator)
        assertEquals(ArcTerminator.Jump(merge), plan.blocks.getValue(elseBlock).terminator)
        assertEquals(ArcTerminator.Return(), plan.blocks.getValue(merge).terminator)
        assertSame(ArcOwnershipVerificationResult.Success, ArcOwnershipVerifier.verify(plan))

        val optimized = ArcOwnershipOptimizer.optimizeVerified(plan)

        assertEquals(
            listOf(ArcOperation.Define(source, ArcOwnership.Owned)),
            optimized.plan.blocks.getValue(entry).operations,
        )
        assertEquals(
            listOf(ArcOperation.StrongStore(field, source)),
            optimized.plan.blocks.getValue(thenBlock).operations,
        )
        assertEquals(
            listOf(ArcOperation.StrongStore(field, source)),
            optimized.plan.blocks.getValue(elseBlock).operations,
        )
        assertEquals(
            listOf(ArcOperation.Destroy(source)),
            optimized.plan.blocks.getValue(merge).operations,
        )
        assertEquals(1, optimized.metrics.containedOwnedCopiesEliminated)
        assertEquals(2, optimized.metrics.eliminatedReferenceCountingOperations)
        assertSame(ArcOwnershipVerificationResult.Success, ArcOwnershipVerifier.verify(optimized.plan))
    }

    @Test
    fun unitIfPlanRejectsDifferentBranchStorageBeforeVerification() {
        val value = ArcValue("value")

        val plan = buildCuratedArcUnitIfPlan(
            functionName = "incompatibleStores",
            entryValues = mapOf(value to ArcOwnership.Guaranteed),
            entryInitializedStorage = emptySet(),
            entryOperations = emptyList(),
            trueOperations = listOf(ArcOperation.StrongStore(ArcStorage("Holder.left"), value)),
            falseOperations = listOf(ArcOperation.StrongStore(ArcStorage("Holder.right"), value)),
            mergeOperations = emptyList(),
            returnedValue = null,
        )

        assertNull(plan)
    }

    @Test
    fun unitIfPlanRejectsEmptyOrNonStoreBranches() {
        val value = ArcValue("value")
        val field = ArcStorage("Holder.value")
        val commonArguments = mapOf(value to ArcOwnership.Guaranteed)

        assertNull(
            buildCuratedArcUnitIfPlan(
                "emptyBranch",
                commonArguments,
                emptySet(),
                emptyList(),
                emptyList(),
                listOf(ArcOperation.StrongStore(field, value)),
                emptyList(),
                null,
            )
        )
        assertNull(
            buildCuratedArcUnitIfPlan(
                "nonStoreBranch",
                commonArguments,
                emptySet(),
                emptyList(),
                listOf(ArcOperation.StrongStore(field, value), ArcOperation.Destroy(value)),
                listOf(ArcOperation.StrongStore(field, value)),
                emptyList(),
                null,
            )
        )
    }
}
