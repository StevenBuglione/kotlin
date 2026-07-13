/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "UNCHECKED_CAST")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcWholeFunctionSemanticARCTest {
    private val identity = ArcWholeFunctionIdentityId("object")
    private val root = ArcWholeFunctionOwnedTokenId("root")
    private val entry = ArcBlockId("entry")
    private val left = ArcBlockId("left")
    private val right = ArcBlockId("right")
    private val merge = ArcBlockId("merge")
    private val normal = ArcBlockId("normal")
    private val handler = ArcBlockId("handler")
    private val exit = ArcBlockId("exit")

    @Test
    fun verifiesCompleteCopyBorrowStoreLoadDestroyStorageAndExceptionalInventory() {
        val slot = ArcWholeFunctionStorageId("field")
        val borrowed = ArcWholeFunctionBorrowTokenId("borrowed")
        val loaded = ArcWholeFunctionOwnedTokenId("loaded")
        val throwing = barrier("throwing", ArcWholeFunctionBarrierKind.ExceptionalCall, mayThrow = true)
        val inventory = inventory(
            blocks = listOf(
                block(
                    entry,
                    beginBorrow("begin", owned(root), borrowed),
                    store("store", slot, borrowed(borrowed), old = null),
                    endBorrow("end", borrowed),
                    load("load", slot, loaded),
                    destroy("destroy.load", loaded),
                    destroyStorage("destroy.storage", slot),
                    throwing,
                ),
                block(normal),
                block(handler),
            ),
            edges = setOf(
                edge(entry, normal),
                edge(entry, handler, ArcSSAEdgeKind.Exceptional, throwing.id),
            ),
        )

        assertSame(ArcWholeFunctionVerificationResult.Success, ArcWholeFunctionOwnershipVerifier.verify(inventory))
    }

    @Test
    fun everyThrowingBarrierRequiresExactlyOneExceptionalCleanupSuccessor() {
        val throwing = barrier("throwing", ArcWholeFunctionBarrierKind.ExceptionalCall, mayThrow = true)
        val missing = inventory(blocks = listOf(block(entry, throwing)))
        assertIssue(missing, ArcWholeFunctionVerificationCode.InvalidExceptionalEdge)

        val duplicate = inventory(
            blocks = listOf(block(entry, throwing), block(left), block(right)),
            edges = setOf(
                edge(entry, left, ArcSSAEdgeKind.Exceptional, throwing.id),
                edge(entry, right, ArcSSAEdgeKind.Exceptional, throwing.id),
            ),
        )
        val failure = ArcWholeFunctionOwnershipVerifier.verify(duplicate) as ArcWholeFunctionVerificationResult.Failure
        assertTrue(failure.issues.any {
            it.code === ArcWholeFunctionVerificationCode.InvalidExceptionalEdge && it.detail.contains("found 2")
        })
    }

    @Test
    fun childBorrowCannotOutliveItsParentBorrow() {
        val parent = ArcWholeFunctionBorrowTokenId("parent")
        val child = ArcWholeFunctionBorrowTokenId("child")
        val copied = ArcWholeFunctionOwnedTokenId("copied")
        val inventory = inventory(blocks = listOf(block(
            entry,
            beginBorrow("begin.parent", owned(root), parent),
            beginBorrow("begin.child", borrowed(parent), child),
            endBorrow("end.parent.early", parent),
            copy("copy.child", borrowed(child), copied),
            destroy("destroy.copy", copied),
            endBorrow("end.child", child),
            endBorrow("end.parent", parent),
        )))

        assertIssue(inventory, ArcWholeFunctionVerificationCode.OwnerEndedWithLiveBorrow)
    }

    @Test
    fun storageReplacementRejectsTransitiveStorageBorrow() {
        val slot = ArcWholeFunctionStorageId("slot")
        val parent = ArcWholeFunctionBorrowTokenId("parent")
        val child = ArcWholeFunctionBorrowTokenId("child")
        val inventory = inventory(
            blocks = listOf(block(
                entry,
                beginBorrow("begin.parent", stored(slot), parent),
                beginBorrow("begin.child", borrowed(parent), child),
                store("replace", slot, owned(root), old = identity),
                endBorrow("end.child", child),
                endBorrow("end.parent", parent),
            )),
            entryStorage = mapOf(slot to identity),
        )

        assertIssue(inventory, ArcWholeFunctionVerificationCode.StorageEndedWithLiveBorrow)
    }

    @Test
    fun storageDestructionRejectsTransitiveStorageBorrowAndSucceedsAfterBorrowEnds() {
        val slot = ArcWholeFunctionStorageId("slot")
        val parent = ArcWholeFunctionBorrowTokenId("parent")
        val child = ArcWholeFunctionBorrowTokenId("child")
        val invalid = inventory(
            blocks = listOf(block(
                entry,
                beginBorrow("begin.parent", stored(slot), parent),
                beginBorrow("begin.child", borrowed(parent), child),
                destroyStorage("destroy.storage", slot),
                endBorrow("end.child", child),
                endBorrow("end.parent", parent),
            )),
            entryStorage = mapOf(slot to identity),
        )
        assertIssue(invalid, ArcWholeFunctionVerificationCode.StorageEndedWithLiveBorrow)

        val valid = inventory(
            blocks = listOf(block(
                entry,
                beginBorrow("begin", stored(slot), parent),
                endBorrow("end", parent),
                destroyStorage("destroy.storage", slot),
            )),
            entryStorage = mapOf(slot to identity),
            expectedStorage = emptyMap(),
        )
        assertSame(ArcWholeFunctionVerificationResult.Success, ArcWholeFunctionOwnershipVerifier.verify(valid))
    }

    @Test
    fun joinedOwnershipWebIsDeletedAsOneVerifiedTransaction() {
        val leftToken = ArcWholeFunctionOwnedTokenId("left.copy")
        val rightToken = ArcWholeFunctionOwnedTokenId("right.copy")
        val joined = ArcWholeFunctionOwnedTokenId("joined")
        val phi = ArcWholeFunctionOwnershipPhi(
            ArcWholeFunctionPhiId("joined.phi"), merge, identity,
            linkedMapOf(left to leftToken, right to rightToken), joined, Any(),
        )
        val inventory = inventory(
            blocks = listOf(
                block(entry),
                block(left, copy("left.copy", owned(root), leftToken)),
                block(right, copy("right.copy", owned(root), rightToken)),
                block(merge, destroy("destroy.joined", joined)),
                block(exit),
            ),
            edges = setOf(
                edge(entry, left), edge(entry, right), edge(left, merge), edge(right, merge), edge(merge, exit),
            ),
            phis = listOf(phi),
        )

        val result = ArcWholeFunctionSemanticARCFixedPoint.optimize(inventory, listOf(ArcWholeFunctionJoinedWebRule))

        assertTrue(result.converged)
        assertEquals(1, result.committedTransactions.size)
        assertEquals(3, result.logicallyEliminatedEvents)
        assertTrue(result.optimizedProofSnapshot.phis.isEmpty())
        assertSame(ArcWholeFunctionVerificationResult.Success, ArcWholeFunctionOwnershipVerifier.verify(result.optimizedProofSnapshot))
    }

    @Test
    fun phisReadRawPredecessorStateAndApplyInParallel() {
        val first = ArcWholeFunctionOwnedTokenId("first.phi")
        val second = ArcWholeFunctionOwnedTokenId("second.phi")
        val inventory = inventory(
            blocks = listOf(block(entry), block(merge)),
            edges = setOf(edge(entry, merge)),
            phis = listOf(
                ArcWholeFunctionOwnershipPhi(
                    ArcWholeFunctionPhiId("first"), merge, identity, mapOf(entry to root), first, Any(),
                ),
                ArcWholeFunctionOwnershipPhi(
                    ArcWholeFunctionPhiId("sequential.exploit"), merge, identity, mapOf(entry to first), second, Any(),
                ),
            ),
            expectedOwned = mapOf(second to identity),
        )

        assertIssue(inventory, ArcWholeFunctionVerificationCode.InvalidPhi)
    }

    @Test
    fun exceptionalCleanupMustBalanceEveryExit() {
        val copied = ArcWholeFunctionOwnedTokenId("copy")
        val throwing = barrier("throw", ArcWholeFunctionBarrierKind.ExceptionalCall, mayThrow = true)
        val balanced = inventory(
            blocks = listOf(
                block(entry, copy("copy", owned(root), copied), throwing),
                block(normal, destroy("normal.destroy", copied)),
                block(handler, destroy("handler.destroy", copied)),
            ),
            edges = setOf(
                edge(entry, normal),
                edge(entry, handler, ArcSSAEdgeKind.Exceptional, throwing.id),
            ),
        )
        assertSame(ArcWholeFunctionVerificationResult.Success, ArcWholeFunctionOwnershipVerifier.verify(balanced))

        val unbalanced = balanced.copy(blocks = balanced.blocks.toMutableMap().apply {
            put(handler, getValue(handler).copy(events = emptyList()))
        })
        assertIssue(unbalanced, ArcWholeFunctionVerificationCode.ExitStateMismatch)
    }

    @Test
    fun semanticBarriersPreventPairFormation() {
        ArcWholeFunctionBarrierKind.values().forEach { kind ->
            val copied = ArcWholeFunctionOwnedTokenId("copy.$kind")
            val inventory = inventory(blocks = listOf(block(
                entry,
                copy("copy.$kind", owned(root), copied),
                barrier("barrier.$kind", kind, mayThrow = false),
                destroy("destroy.$kind", copied),
            )))

            val result = ArcWholeFunctionSemanticARCFixedPoint.optimize(
                inventory, rules = listOf(ArcWholeFunctionAdjacentPairRule),
            )

            assertTrue(kind.name, result.committedTransactions.isEmpty())
        }
    }

    @Test
    fun nestedPairsConvergeAcrossRepeatedWorklistRounds() {
        val outer = ArcWholeFunctionOwnedTokenId("outer")
        val inner = ArcWholeFunctionOwnedTokenId("inner")
        val inventory = inventory(blocks = listOf(block(
            entry,
            copy("copy.outer", owned(root), outer),
            copy("copy.inner", owned(outer), inner),
            destroy("destroy.inner", inner),
            destroy("destroy.outer", outer),
        )))

        val result = ArcWholeFunctionSemanticARCFixedPoint.optimize(inventory, listOf(ArcWholeFunctionAdjacentPairRule))

        assertTrue(result.converged)
        assertEquals(2, result.committedTransactions.size)
        assertEquals(4, result.logicallyEliminatedEvents)
        assertTrue(result.fixedPointRounds >= 3)
    }

    @Test
    fun failedPostVerificationRestoresTheCanonicalImmutableBaseline() {
        val copied = ArcWholeFunctionOwnedTokenId("copy")
        val inventory = inventory(blocks = listOf(block(
            entry, copy("copy", owned(root), copied), destroy("destroy", copied),
        )))
        val invalid = ArcWholeFunctionSemanticARCRule { snapshot ->
            val proofCopy = snapshot.event("copy")
            listOf(ArcWholeFunctionRewriteCandidate(
                snapshot,
                ArcWholeFunctionRewriteId("delete-only-copy"),
                listOf(ArcWholeFunctionRewriteAction.DeleteEvent(proofCopy.id, proofCopy.binding)),
                mapOf(entry to snapshot.blocks.getValue(entry).binding),
            ))
        }

        val result = ArcWholeFunctionSemanticARCFixedPoint.optimize(inventory, listOf(invalid))

        assertEquals(
            ArcWholeFunctionTransactionFailureKind.PostRewriteVerificationFailed,
            result.rolledBackTransactions.single().failure.kind,
        )
        assertEquals(2, result.optimizedProofSnapshot.blocks.getValue(entry).events.size)
    }

    @Test
    fun opaqueEventAndBlockCapabilityDriftAbortAtomically() {
        val copied = ArcWholeFunctionOwnedTokenId("copy")
        val inventory = inventory(blocks = listOf(block(
            entry, copy("copy", owned(root), copied), destroy("destroy", copied),
        )))
        val wrongEvent = ArcWholeFunctionSemanticARCRule { snapshot ->
            val proofCopy = snapshot.event("copy")
            listOf(ArcWholeFunctionRewriteCandidate(
                snapshot,
                ArcWholeFunctionRewriteId("wrong.event"),
                listOf(ArcWholeFunctionRewriteAction.DeleteEvent(
                    proofCopy.id, ArcWholeFunctionOpaqueBindingId(999),
                )),
                mapOf(entry to snapshot.blocks.getValue(entry).binding),
            ))
        }
        val eventResult = ArcWholeFunctionSemanticARCFixedPoint.optimize(inventory, listOf(wrongEvent))
        assertEquals(
            ArcWholeFunctionTransactionFailureKind.BindingIdentityDrift,
            eventResult.rolledBackTransactions.single().failure.kind,
        )

        val wrongBlock = ArcWholeFunctionSemanticARCRule { snapshot ->
            val proofCopy = snapshot.event("copy")
            val proofDestroy = snapshot.event("destroy")
            listOf(ArcWholeFunctionRewriteCandidate(
                snapshot,
                ArcWholeFunctionRewriteId("wrong.block"),
                listOf(
                    ArcWholeFunctionRewriteAction.DeleteEvent(proofCopy.id, proofCopy.binding),
                    ArcWholeFunctionRewriteAction.DeleteEvent(proofDestroy.id, proofDestroy.binding),
                ),
                mapOf(entry to ArcWholeFunctionOpaqueBindingId(1000)),
            ))
        }
        val blockResult = ArcWholeFunctionSemanticARCFixedPoint.optimize(inventory, listOf(wrongBlock))
        assertEquals(
            ArcWholeFunctionTransactionFailureKind.IncompleteTouchedBlocks,
            blockResult.rolledBackTransactions.single().failure.kind,
        )
    }

    @Test
    fun rulesCannotMutateCanonicalSnapshotCollectionsOrReachRawBindings() {
        val rawBinding = Any()
        val copied = ArcWholeFunctionOwnedTokenId("copy")
        val rawBlock = ArcWholeFunctionOwnershipBlock(
            entry,
            rawBinding,
            listOf(copy("copy", owned(root), copied), destroy("destroy", copied)),
        )
        val inventory = inventory(blocks = listOf(rawBlock))
        val malicious = ArcWholeFunctionSemanticARCRule { snapshot ->
            assertTrue(snapshot.blocks.getValue(entry).binding !== rawBinding)
            (snapshot.blocks as MutableMap<ArcBlockId, ArcWholeFunctionProofBlock>).clear()
            emptyList()
        }

        val result = ArcWholeFunctionSemanticARCFixedPoint.optimize(inventory, listOf(malicious))

        assertTrue(result.converged)
        assertEquals(1, result.ruleFailures.size)
        assertTrue(result.ruleFailures.single().detail.contains("UnsupportedOperationException"))
        assertEquals(2, result.optimizedProofSnapshot.blocks.getValue(entry).events.size)
        assertSame(ArcWholeFunctionVerificationResult.Success, result.finalVerification)
    }

    @Test
    fun nonFatalRuleErrorsAreQuarantinedWithoutMutatingTheProof() {
        val inventory = inventory(blocks = listOf(block(entry)))
        val malicious = ArcWholeFunctionSemanticARCRule { throw AssertionError("malicious rule") }

        val result = ArcWholeFunctionSemanticARCFixedPoint.optimize(inventory, listOf(malicious))

        assertTrue(result.converged)
        assertEquals(1, result.ruleFailures.size)
        assertTrue(result.ruleFailures.single().detail.contains("AssertionError: malicious rule"))
        assertSame(ArcWholeFunctionVerificationResult.Success, result.finalVerification)
    }

    @Test
    fun blockMapKeyMustMatchEmbeddedBlockIdentity() {
        val malformed = inventory(blocks = listOf(block(entry))).copy(
            blocks = mapOf(entry to block(left)),
        )
        assertIssue(malformed, ArcWholeFunctionVerificationCode.MissingBlock)
    }

    @Test
    fun fixedPointRefusesAnUnverifiedInitialInventory() {
        val invalid = inventory(blocks = listOf(block(entry))).copy(completeLoweredIRWalk = false)
        val result = ArcWholeFunctionSemanticARCFixedPoint.optimize(invalid)

        assertFalse(result.converged)
        assertEquals(0, result.fixedPointRounds)
        assertTrue(result.initialVerification is ArcWholeFunctionVerificationResult.Failure)
        assertSame(result.initialVerification, result.finalVerification)
    }

    @Test
    fun proofOnlyResultNeverClaimsPhysicalEmissionCredit() {
        val copied = ArcWholeFunctionOwnedTokenId("copy")
        val inventory = inventory(blocks = listOf(block(
            entry, copy("copy", owned(root), copied), destroy("destroy", copied),
        )))
        val result = ArcWholeFunctionSemanticARCFixedPoint.optimize(inventory)

        assertEquals(2, result.logicallyEliminatedEvents)
        assertFalse(result.emitted)
        assertNull(result.physicalOwnershipEventsRemoved)
    }

    private fun assertIssue(
        inventory: ArcWholeFunctionOwnershipInventory,
        code: ArcWholeFunctionVerificationCode,
    ) {
        val failure = ArcWholeFunctionOwnershipVerifier.verify(inventory) as ArcWholeFunctionVerificationResult.Failure
        assertTrue(failure.issues.joinToString(), failure.issues.any { it.code === code })
    }

    private fun ArcWholeFunctionProofSnapshot.event(name: String): ArcWholeFunctionProofEvent =
        blocks.values.flatMap { it.events }.single { it.id.name == name }

    private fun inventory(
        blocks: List<ArcWholeFunctionOwnershipBlock>,
        edges: Set<ArcWholeFunctionOwnershipEdge> = emptySet(),
        phis: List<ArcWholeFunctionOwnershipPhi> = emptyList(),
        entryStorage: Map<ArcWholeFunctionStorageId, ArcWholeFunctionIdentityId> = emptyMap(),
        expectedStorage: Map<ArcWholeFunctionStorageId, ArcWholeFunctionIdentityId> = entryStorage,
        expectedOwned: Map<ArcWholeFunctionOwnedTokenId, ArcWholeFunctionIdentityId> = mapOf(root to identity),
    ) = ArcWholeFunctionOwnershipInventory(
        functionName = "test",
        functionBinding = Any(),
        entry = entry,
        blocks = blocks.associateByTo(linkedMapOf()) { it.id },
        edges = edges,
        phis = phis,
        entryOwnedTokens = mapOf(root to identity),
        entryStorage = entryStorage,
        expectedExitOwnedTokens = expectedOwned,
        expectedExitStorage = expectedStorage,
        completeLoweredIRWalk = true,
    )

    private fun block(id: ArcBlockId, vararg events: ArcWholeFunctionOwnershipEvent) =
        ArcWholeFunctionOwnershipBlock(id, Any(), events.toList())

    private fun edge(
        from: ArcBlockId,
        to: ArcBlockId,
        kind: ArcSSAEdgeKind = ArcSSAEdgeKind.Normal,
        throwing: ArcWholeFunctionEventId? = null,
    ) = ArcWholeFunctionOwnershipEdge(from, to, kind, Any(), throwing)

    private fun owned(token: ArcWholeFunctionOwnedTokenId) = ArcWholeFunctionSource.Owned(token, identity)
    private fun borrowed(token: ArcWholeFunctionBorrowTokenId) = ArcWholeFunctionSource.Borrowed(token, identity)
    private fun stored(storage: ArcWholeFunctionStorageId) = ArcWholeFunctionSource.Storage(storage, identity)

    private fun copy(name: String, source: ArcWholeFunctionSource, result: ArcWholeFunctionOwnedTokenId) =
        ArcWholeFunctionOwnershipEvent.Copy(ArcWholeFunctionEventId(name), source, result, identity, Any())

    private fun destroy(name: String, token: ArcWholeFunctionOwnedTokenId) =
        ArcWholeFunctionOwnershipEvent.Destroy(ArcWholeFunctionEventId(name), token, identity, Any())

    private fun beginBorrow(name: String, source: ArcWholeFunctionSource, result: ArcWholeFunctionBorrowTokenId) =
        ArcWholeFunctionOwnershipEvent.BeginBorrow(ArcWholeFunctionEventId(name), source, result, identity, Any())

    private fun endBorrow(name: String, token: ArcWholeFunctionBorrowTokenId) =
        ArcWholeFunctionOwnershipEvent.EndBorrow(ArcWholeFunctionEventId(name), token, identity, Any())

    private fun store(
        name: String,
        storage: ArcWholeFunctionStorageId,
        source: ArcWholeFunctionSource,
        old: ArcWholeFunctionIdentityId?,
    ) = ArcWholeFunctionOwnershipEvent.StrongStore(
        ArcWholeFunctionEventId(name), storage, source, old, identity, Any(),
    )

    private fun load(name: String, storage: ArcWholeFunctionStorageId, result: ArcWholeFunctionOwnedTokenId) =
        ArcWholeFunctionOwnershipEvent.StrongLoad(ArcWholeFunctionEventId(name), storage, identity, result, Any())

    private fun destroyStorage(name: String, storage: ArcWholeFunctionStorageId) =
        ArcWholeFunctionOwnershipEvent.DestroyStorage(ArcWholeFunctionEventId(name), storage, identity, Any())

    private fun barrier(name: String, kind: ArcWholeFunctionBarrierKind, mayThrow: Boolean) =
        ArcWholeFunctionOwnershipEvent.Barrier(ArcWholeFunctionEventId(name), kind, mayThrow, Any())
}
