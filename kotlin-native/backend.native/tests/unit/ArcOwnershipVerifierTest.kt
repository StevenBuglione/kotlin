/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
    fun projectedBorrowRetainsItsOwnersLifetimeDependency() {
        val owner = ArcValue("owner")
        val projected = ArcValue("projected")
        val result = ArcOwnershipVerifier.verify(
            plan(
                ArcOperation.Define(owner, ArcOwnership.Owned),
                ArcOperation.Borrow(owner, projected, ArcBorrowKind.Projection),
                ArcOperation.Destroy(owner),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.OWNER_ENDED_WITH_LIVE_BORROW)
        assertFailureCode(result, ArcOwnershipViolationCode.LIVE_BORROW_AT_EXIT)
    }

    @Test
    fun projectedBorrowMayEndBeforeItsOwner() {
        val owner = ArcValue("owner")
        val projected = ArcValue("projected")
        val result = ArcOwnershipVerifier.verify(
            plan(
                ArcOperation.Define(owner, ArcOwnership.Owned),
                ArcOperation.Borrow(owner, projected, ArcBorrowKind.Projection),
                ArcOperation.Use(projected),
                ArcOperation.EndBorrow(projected),
                ArcOperation.Destroy(owner),
            )
        )

        assertEquals(ArcOwnershipVerificationResult.Success, result)
    }

    @Test
    fun strongReplaceAtomicallyTransfersProjectedBorrowAndConsumesOldOwner() {
        val oldOwner = ArcValue("oldOwner")
        val projected = ArcValue("projected")
        val cursor = ArcStorage("cursor")
        val result = ArcOwnershipVerifier.verify(
            plan(
                operations = listOf(
                    ArcOperation.Define(oldOwner, ArcOwnership.Owned),
                    ArcOperation.Borrow(oldOwner, projected, ArcBorrowKind.Projection),
                    // The runtime values may have the same identity (a self edge); ownership is
                    // still represented by distinct owner and projected SSA values.
                    ArcOperation.StrongReplace(cursor, oldOwner, projected),
                ),
                initializedStorage = setOf(cursor),
            )
        )

        assertEquals(ArcOwnershipVerificationResult.Success, result)
    }

    @Test
    fun strongReplaceMakesOldOwnerAndProjectionUnavailableAfterTransfer() {
        val oldOwner = ArcValue("oldOwner")
        val projected = ArcValue("projected")
        val cursor = ArcStorage("cursor")
        val result = ArcOwnershipVerifier.verify(
            plan(
                operations = listOf(
                    ArcOperation.Define(oldOwner, ArcOwnership.Owned),
                    ArcOperation.Borrow(oldOwner, projected, ArcBorrowKind.Projection),
                    ArcOperation.StrongReplace(cursor, oldOwner, projected),
                    ArcOperation.Use(oldOwner),
                    ArcOperation.Use(projected),
                ),
                initializedStorage = setOf(cursor),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.USE_AFTER_DESTROY)
    }

    @Test
    fun strongReplaceRejectsIdentityBorrow() {
        val oldOwner = ArcValue("oldOwner")
        val borrowed = ArcValue("borrowed")
        val cursor = ArcStorage("cursor")
        val result = ArcOwnershipVerifier.verify(
            plan(
                operations = listOf(
                    ArcOperation.Define(oldOwner, ArcOwnership.Owned),
                    ArcOperation.Borrow(oldOwner, borrowed, ArcBorrowKind.Identity),
                    ArcOperation.StrongReplace(cursor, oldOwner, borrowed),
                ),
                initializedStorage = setOf(cursor),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.STRONG_REPLACE_REQUIRES_PROJECTED_BORROW)
    }

    @Test
    fun strongReplaceRejectsProjectionFromDifferentOwner() {
        val oldOwner = ArcValue("oldOwner")
        val otherOwner = ArcValue("otherOwner")
        val projected = ArcValue("projected")
        val cursor = ArcStorage("cursor")
        val result = ArcOwnershipVerifier.verify(
            plan(
                operations = listOf(
                    ArcOperation.Define(oldOwner, ArcOwnership.Owned),
                    ArcOperation.Define(otherOwner, ArcOwnership.Owned),
                    ArcOperation.Borrow(otherOwner, projected, ArcBorrowKind.Projection),
                    ArcOperation.StrongReplace(cursor, oldOwner, projected),
                ),
                initializedStorage = setOf(cursor),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.STRONG_REPLACE_BORROW_OWNER_MISMATCH)
    }

    @Test
    fun strongReplaceRequiresInitializedStorage() {
        val oldOwner = ArcValue("oldOwner")
        val projected = ArcValue("projected")
        val result = ArcOwnershipVerifier.verify(
            plan(
                ArcOperation.Define(oldOwner, ArcOwnership.Owned),
                ArcOperation.Borrow(oldOwner, projected, ArcBorrowKind.Projection),
                ArcOperation.StrongReplace(ArcStorage("cursor"), oldOwner, projected),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.STRONG_REPLACE_OF_UNINITIALIZED_STORAGE)
    }

    @Test
    fun strongReplaceRequiresOwnedOldOwner() {
        val oldOwner = ArcValue("oldOwner")
        val projected = ArcValue("projected")
        val cursor = ArcStorage("cursor")
        val result = ArcOwnershipVerifier.verify(
            plan(
                operations = listOf(
                    ArcOperation.Borrow(oldOwner, projected, ArcBorrowKind.Projection),
                    ArcOperation.StrongReplace(cursor, oldOwner, projected),
                ),
                entryValues = mapOf(oldOwner to ArcOwnership.Guaranteed),
                initializedStorage = setOf(cursor),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.STRONG_REPLACE_REQUIRES_OWNED_OLD_OWNER)
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

    @Test
    fun rootedProjectionLoopReachesStableBackedgeState() {
        val anchor = ArcValue("anchor")
        val cursor = ArcStorage("cursor")
        val loop = ArcBlockId("loop")
        val exit = ArcBlockId("exit")
        val blocks = listOf(
            ArcBasicBlock(
                entry,
                listOf(ArcOperation.BeginRootedProjection(cursor, anchor)),
                ArcTerminator.Jump(loop),
            ),
            ArcBasicBlock(
                loop,
                listOf(ArcOperation.AdvanceRootedProjection(cursor, anchor)),
                ArcTerminator.Branch(loop, exit),
            ),
            ArcBasicBlock(
                exit,
                listOf(ArcOperation.EndRootedProjection(cursor, anchor)),
                ArcTerminator.Return(),
            ),
        ).associateBy(ArcBasicBlock::id)

        val result = ArcOwnershipVerifier.verify(
            ArcFunctionPlan("rootedLoop", entry, mapOf(anchor to ArcOwnership.Guaranteed), emptySet(), blocks)
        )

        assertSame(ArcOwnershipVerificationResult.Success, result)
    }

    @Test
    fun advanceRequiresActiveRootedProjection() {
        val anchor = ArcValue("anchor")
        val cursor = ArcStorage("cursor")

        val result = ArcOwnershipVerifier.verify(
            plan(
                ArcOperation.AdvanceRootedProjection(cursor, anchor),
                entryValues = mapOf(anchor to ArcOwnership.Guaranteed),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.ROOTED_PROJECTION_NOT_ACTIVE)
    }

    @Test
    fun rootedProjectionCannotBeginTwiceForSameStorage() {
        val anchor = ArcValue("anchor")
        val cursor = ArcStorage("cursor")

        val result = ArcOwnershipVerifier.verify(
            plan(
                ArcOperation.BeginRootedProjection(cursor, anchor),
                ArcOperation.BeginRootedProjection(cursor, anchor),
                ArcOperation.EndRootedProjection(cursor, anchor),
                entryValues = mapOf(anchor to ArcOwnership.Guaranteed),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.ROOTED_PROJECTION_STORAGE_ALREADY_ACTIVE)
    }

    @Test
    fun strongStoreRejectsActiveRootedProjectionStorage() {
        val anchor = ArcValue("anchor")
        val stored = ArcValue("stored")
        val cursor = ArcStorage("cursor")

        val result = ArcOwnershipVerifier.verify(
            plan(
                ArcOperation.BeginRootedProjection(cursor, anchor),
                ArcOperation.StrongStore(cursor, stored),
                ArcOperation.EndRootedProjection(cursor, anchor),
                entryValues = mapOf(
                    anchor to ArcOwnership.Guaranteed,
                    stored to ArcOwnership.Guaranteed,
                ),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.ROOTED_PROJECTION_STORAGE_ALREADY_ACTIVE)
    }

    @Test
    fun rootedProjectionRequiresExactAnchor() {
        val anchor = ArcValue("anchor")
        val other = ArcValue("other")
        val cursor = ArcStorage("cursor")

        val result = ArcOwnershipVerifier.verify(
            plan(
                ArcOperation.BeginRootedProjection(cursor, anchor),
                ArcOperation.AdvanceRootedProjection(cursor, other),
                ArcOperation.EndRootedProjection(cursor, anchor),
                entryValues = mapOf(
                    anchor to ArcOwnership.Guaranteed,
                    other to ArcOwnership.Guaranteed,
                ),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.ROOTED_PROJECTION_ANCHOR_MISMATCH)
    }

    @Test
    fun rootedProjectionAnchorCannotBeDestroyedWhileActive() {
        val anchor = ArcValue("anchor")
        val cursor = ArcStorage("cursor")

        val result = ArcOwnershipVerifier.verify(
            plan(
                ArcOperation.BeginRootedProjection(cursor, anchor),
                ArcOperation.Destroy(anchor),
                entryValues = mapOf(anchor to ArcOwnership.Owned),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.ROOTED_PROJECTION_ANCHOR_ENDED)
    }

    @Test
    fun rootedProjectionBorrowAnchorCannotEndWhileActive() {
        val owner = ArcValue("owner")
        val anchor = ArcValue("anchor")
        val cursor = ArcStorage("cursor")

        val result = ArcOwnershipVerifier.verify(
            plan(
                ArcOperation.Borrow(owner, anchor),
                ArcOperation.BeginRootedProjection(cursor, anchor),
                ArcOperation.EndBorrow(anchor),
                ArcOperation.Destroy(owner),
                entryValues = mapOf(owner to ArcOwnership.Owned),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.ROOTED_PROJECTION_ANCHOR_ENDED)
    }

    @Test
    fun rootedProjectionMustEndBeforeFunctionExit() {
        val anchor = ArcValue("anchor")
        val cursor = ArcStorage("cursor")

        val result = ArcOwnershipVerifier.verify(
            plan(
                ArcOperation.BeginRootedProjection(cursor, anchor),
                entryValues = mapOf(anchor to ArcOwnership.Guaranteed),
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.LIVE_ROOTED_PROJECTION_AT_EXIT)
    }

    @Test
    fun rootedProjectionJoinRejectsDifferentAnchors() {
        val leftAnchor = ArcValue("leftAnchor")
        val rightAnchor = ArcValue("rightAnchor")
        val cursor = ArcStorage("cursor")
        val left = ArcBlockId("left")
        val right = ArcBlockId("right")
        val merge = ArcBlockId("merge")
        val blocks = listOf(
            ArcBasicBlock(entry, emptyList(), ArcTerminator.Branch(left, right)),
            ArcBasicBlock(
                left,
                listOf(ArcOperation.BeginRootedProjection(cursor, leftAnchor)),
                ArcTerminator.Jump(merge),
            ),
            ArcBasicBlock(
                right,
                listOf(ArcOperation.BeginRootedProjection(cursor, rightAnchor)),
                ArcTerminator.Jump(merge),
            ),
            ArcBasicBlock(merge, emptyList(), ArcTerminator.Return()),
        ).associateBy(ArcBasicBlock::id)

        val result = ArcOwnershipVerifier.verify(
            ArcFunctionPlan(
                "rootedJoin",
                entry,
                mapOf(
                    leftAnchor to ArcOwnership.Guaranteed,
                    rightAnchor to ArcOwnership.Guaranteed,
                ),
                emptySet(),
                blocks,
            )
        )

        assertFailureCode(result, ArcOwnershipViolationCode.INCOMPATIBLE_PATH_STATES)
    }

    private fun plan(
        vararg operations: ArcOperation,
        entryValues: Map<ArcValue, ArcOwnership> = emptyMap(),
        initializedStorage: Set<ArcStorage> = emptySet(),
    ): ArcFunctionPlan = plan(operations.asList(), entryValues, initializedStorage)

    private fun plan(
        operations: List<ArcOperation>,
        entryValues: Map<ArcValue, ArcOwnership> = emptyMap(),
        initializedStorage: Set<ArcStorage> = emptySet(),
    ): ArcFunctionPlan {
        val block = ArcBasicBlock(entry, operations, ArcTerminator.Return())
        return ArcFunctionPlan("test", entry, entryValues, initializedStorage, mapOf(entry to block))
    }

    private fun assertFailureCode(result: ArcOwnershipVerificationResult, code: ArcOwnershipViolationCode) {
        val failure = result as? ArcOwnershipVerificationResult.Failure
            ?: fail("Expected failure, got $result").let { error("unreachable") }
        assertTrue("Expected $code in ${failure.render()}", failure.violations.any { it.code === code })
    }
}
