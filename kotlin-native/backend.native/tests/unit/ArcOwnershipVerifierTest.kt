/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

class ArcOwnershipVerifierTest {
    private val entry = ArcBlockId("entry")

    @Test
    fun balancedCopiesBorrowsAndStrongReferencesVerify() {
        val owner = ArcValue("owner")
        val borrowed = ArcValue("borrowed")
        val loaded = ArcValue("loaded")
        val storage = ArcStorage("field")
        val plan = plan(
            ArcOperation.Define(owner, ArcOwnership.Owned),
            ArcOperation.Borrow(owner, borrowed),
            ArcOperation.Use(borrowed),
            ArcOperation.StrongStore(storage, borrowed),
            ArcOperation.StrongLoad(storage, loaded),
            ArcOperation.Destroy(loaded),
            ArcOperation.EndBorrow(borrowed),
            ArcOperation.Destroy(owner),
        )

        assertEquals(ArcOwnershipVerificationResult.Success, ArcOwnershipVerifier.verify(plan))
    }

    @Test
    fun ownerCannotEndWhileBorrowIsLive() {
        val owner = ArcValue("owner")
        val borrowed = ArcValue("borrowed")
        val result = ArcOwnershipVerifier.verify(
            plan(
                ArcOperation.Define(owner, ArcOwnership.Owned),
                ArcOperation.Borrow(owner, borrowed),
                ArcOperation.Destroy(owner),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.OWNER_ENDED_WITH_LIVE_BORROW)
        assertFailureCode(result, ArcOwnershipViolationCode.LIVE_BORROW_AT_EXIT)
    }

    @Test
    fun borrowMustEndBeforeFunctionExit() {
        val owner = ArcValue("owner")
        val borrowed = ArcValue("borrowed")
        val result = ArcOwnershipVerifier.verify(
            plan(
                ArcOperation.Define(owner, ArcOwnership.Owned),
                ArcOperation.Borrow(owner, borrowed),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.LIVE_BORROW_AT_EXIT)
    }

    @Test
    fun useAfterEndBorrowIsRejected() {
        val owner = ArcValue("owner")
        val borrowed = ArcValue("borrowed")
        val result = ArcOwnershipVerifier.verify(
            plan(
                ArcOperation.Define(owner, ArcOwnership.Owned),
                ArcOperation.Borrow(owner, borrowed),
                ArcOperation.EndBorrow(borrowed),
                ArcOperation.Use(borrowed),
                ArcOperation.Destroy(owner),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.USE_AFTER_DESTROY)
    }

    @Test
    fun endBorrowRejectsOrdinaryGuaranteedValue() {
        val argument = ArcValue("argument")
        val result = ArcOwnershipVerifier.verify(
            plan(
                operations = listOf(ArcOperation.EndBorrow(argument)),
                entryValues = mapOf(argument to ArcOwnership.Guaranteed),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.END_BORROW_OF_NON_BORROWED)
    }

    @Test
    fun doubleDestroyIsReportedAsUseAfterDestroy() {
        val value = ArcValue("value")
        val result = ArcOwnershipVerifier.verify(
            plan(
                ArcOperation.Define(value, ArcOwnership.Owned),
                ArcOperation.Destroy(value),
                ArcOperation.Destroy(value),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.USE_AFTER_DESTROY)
    }

    @Test
    fun guaranteedValueCannotBeDestroyed() {
        val argument = ArcValue("argument")
        val result = ArcOwnershipVerifier.verify(
            plan(
                operations = listOf(ArcOperation.Destroy(argument)),
                entryValues = mapOf(argument to ArcOwnership.Guaranteed),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.DESTROY_OF_NON_OWNED)
    }

    @Test
    fun strongLoadRequiresDominatingInitialization() {
        val result = ArcOwnershipVerifier.verify(
            plan(ArcOperation.StrongLoad(ArcStorage("field"), ArcValue("loaded")))
        )

        assertFailureCode(result, ArcOwnershipViolationCode.LOAD_FROM_UNINITIALIZED_STORAGE)
    }

    @Test
    fun liveOwnedValueAtReturnIsReported() {
        val value = ArcValue("value")
        val result = ArcOwnershipVerifier.verify(plan(ArcOperation.Define(value, ArcOwnership.Owned)))

        assertFailureCode(result, ArcOwnershipViolationCode.LEAKED_OWNED_VALUE)
    }

    @Test
    fun divergentPathOwnershipStatesAreRejected() {
        val value = ArcValue("value")
        val left = ArcBlockId("left")
        val right = ArcBlockId("right")
        val merge = ArcBlockId("merge")
        val blocks = listOf(
            ArcBasicBlock(
                entry,
                listOf(ArcOperation.Define(value, ArcOwnership.Owned)),
                ArcTerminator.Branch(left, right),
            ),
            ArcBasicBlock(left, listOf(ArcOperation.Destroy(value)), ArcTerminator.Jump(merge)),
            ArcBasicBlock(right, emptyList(), ArcTerminator.Jump(merge)),
            ArcBasicBlock(merge, emptyList(), ArcTerminator.Unreachable),
        ).associateBy { it.id }

        val result = ArcOwnershipVerifier.verify(
            ArcFunctionPlan("divergent", entry, emptyMap(), emptySet(), blocks)
        )

        assertFailureCode(result, ArcOwnershipViolationCode.INCOMPATIBLE_PATH_STATES)
    }

    @Test
    fun branchLocalOwnedValueDestroyedBeforeMergeVerifies() {
        val temporary = ArcValue("temporary")
        val left = ArcBlockId("left")
        val right = ArcBlockId("right")
        val merge = ArcBlockId("merge")
        val blocks = listOf(
            ArcBasicBlock(entry, emptyList(), ArcTerminator.Branch(left, right)),
            ArcBasicBlock(
                left,
                listOf(
                    ArcOperation.Define(temporary, ArcOwnership.Owned),
                    ArcOperation.Destroy(temporary),
                ),
                ArcTerminator.Jump(merge),
            ),
            ArcBasicBlock(right, emptyList(), ArcTerminator.Jump(merge)),
            ArcBasicBlock(merge, emptyList(), ArcTerminator.Return()),
        ).associateBy { it.id }

        assertEquals(
            ArcOwnershipVerificationResult.Success,
            ArcOwnershipVerifier.verify(ArcFunctionPlan("branchLocal", entry, emptyMap(), emptySet(), blocks)),
        )
    }

    @Test
    fun branchLocalLiveValueAtMergeIsRejected() {
        val temporary = ArcValue("temporary")
        val left = ArcBlockId("left")
        val right = ArcBlockId("right")
        val merge = ArcBlockId("merge")
        val blocks = listOf(
            ArcBasicBlock(entry, emptyList(), ArcTerminator.Branch(left, right)),
            ArcBasicBlock(left, listOf(ArcOperation.Define(temporary, ArcOwnership.Owned)), ArcTerminator.Jump(merge)),
            ArcBasicBlock(right, emptyList(), ArcTerminator.Jump(merge)),
            ArcBasicBlock(merge, emptyList(), ArcTerminator.Return()),
        ).associateBy { it.id }

        val result = ArcOwnershipVerifier.verify(ArcFunctionPlan("branchLive", entry, emptyMap(), emptySet(), blocks))

        assertFailureCode(result, ArcOwnershipViolationCode.INCOMPATIBLE_PATH_STATES)
    }

    @Test
    fun duplicateValueDefinitionsInDisjointBranchesAreRejected() {
        val duplicate = ArcValue("duplicate")
        val left = ArcBlockId("left")
        val right = ArcBlockId("right")
        val merge = ArcBlockId("merge")
        fun branch(id: ArcBlockId) = ArcBasicBlock(
            id,
            listOf(
                ArcOperation.Define(duplicate, ArcOwnership.Owned),
                ArcOperation.Destroy(duplicate),
            ),
            ArcTerminator.Jump(merge),
        )
        val blocks = listOf(
            ArcBasicBlock(entry, emptyList(), ArcTerminator.Branch(left, right)),
            branch(left),
            branch(right),
            ArcBasicBlock(merge, emptyList(), ArcTerminator.Return()),
        ).associateBy { it.id }

        val result = ArcOwnershipVerifier.verify(ArcFunctionPlan("duplicateDefs", entry, emptyMap(), emptySet(), blocks))

        assertFailureCode(result, ArcOwnershipViolationCode.VALUE_ALREADY_DEFINED)
    }

    @Test
    fun branchLocalDestroyedValueUsedAfterMergeIsUnknown() {
        val temporary = ArcValue("temporary")
        val left = ArcBlockId("left")
        val right = ArcBlockId("right")
        val merge = ArcBlockId("merge")
        val blocks = listOf(
            ArcBasicBlock(entry, emptyList(), ArcTerminator.Branch(left, right)),
            ArcBasicBlock(
                left,
                listOf(
                    ArcOperation.Define(temporary, ArcOwnership.Owned),
                    ArcOperation.Destroy(temporary),
                ),
                ArcTerminator.Jump(merge),
            ),
            ArcBasicBlock(right, emptyList(), ArcTerminator.Jump(merge)),
            ArcBasicBlock(
                merge,
                listOf(ArcOperation.StrongStore(ArcStorage("field"), temporary)),
                ArcTerminator.Return(),
            ),
        ).associateBy { it.id }

        val result = ArcOwnershipVerifier.verify(ArcFunctionPlan("useAfterJoin", entry, emptyMap(), emptySet(), blocks))

        assertFailureCode(result, ArcOwnershipViolationCode.UNKNOWN_VALUE)
    }

    @Test
    fun verifyOrThrowPreservesStructuredViolations() {
        val value = ArcValue("leaked")
        val exception = try {
            ArcOwnershipVerifier.verifyOrThrow(plan(ArcOperation.Define(value, ArcOwnership.Owned)))
            fail("Expected ArcOwnershipVerificationException")
            error("unreachable")
        } catch (failure: ArcOwnershipVerificationException) {
            failure
        }

        assertTrue(exception.failure.violations.any { it.code === ArcOwnershipViolationCode.LEAKED_OWNED_VALUE })
    }

    private fun plan(
        vararg operations: ArcOperation,
        entryValues: Map<ArcValue, ArcOwnership> = emptyMap(),
    ): ArcFunctionPlan = plan(operations.asList(), entryValues)

    private fun plan(
        operations: List<ArcOperation>,
        entryValues: Map<ArcValue, ArcOwnership> = emptyMap(),
    ): ArcFunctionPlan {
        val block = ArcBasicBlock(entry, operations, ArcTerminator.Return())
        return ArcFunctionPlan("test", entry, entryValues, emptySet(), mapOf(entry to block))
    }

    private fun assertFailureCode(result: ArcOwnershipVerificationResult, code: ArcOwnershipViolationCode) {
        val failure = result as? ArcOwnershipVerificationResult.Failure
            ?: fail("Expected failure, got $result").let { error("unreachable") }
        assertTrue("Expected $code in ${failure.render()}", failure.violations.any { it.code === code })
    }
}
