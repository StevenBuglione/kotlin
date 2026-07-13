/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

@JvmInline internal value class ArcWholeFunctionRewriteId(val name: String)

/** Reference-identity capability. It never exposes the lowered IR object which it authenticates. */
internal class ArcWholeFunctionOpaqueBindingId internal constructor(private val ordinal: Int) {
    override fun toString(): String = "opaque-binding($ordinal)"
}

internal enum class ArcWholeFunctionProofEventKind {
    Copy,
    Destroy,
    BeginBorrow,
    EndBorrow,
    StrongStore,
    StrongLoad,
    DestroyStorage,
    Barrier,
}

/** Canonical ownership event visible to optimization rules. It contains no raw IR reference. */
internal data class ArcWholeFunctionProofEvent(
    val id: ArcWholeFunctionEventId,
    val kind: ArcWholeFunctionProofEventKind,
    val binding: ArcWholeFunctionOpaqueBindingId,
    val source: ArcWholeFunctionSource? = null,
    val ownedToken: ArcWholeFunctionOwnedTokenId? = null,
    val borrowToken: ArcWholeFunctionBorrowTokenId? = null,
    val storage: ArcWholeFunctionStorageId? = null,
    val expectedOldIdentity: ArcWholeFunctionIdentityId? = null,
    val identity: ArcWholeFunctionIdentityId? = null,
    val barrierKind: ArcWholeFunctionBarrierKind? = null,
    val mayThrow: Boolean = false,
)

internal data class ArcWholeFunctionProofBlock(
    val id: ArcBlockId,
    val binding: ArcWholeFunctionOpaqueBindingId,
    val events: List<ArcWholeFunctionProofEvent>,
)

internal data class ArcWholeFunctionProofEdge(
    val from: ArcBlockId,
    val to: ArcBlockId,
    val kind: ArcSSAEdgeKind,
    val binding: ArcWholeFunctionOpaqueBindingId,
    val throwingEvent: ArcWholeFunctionEventId?,
)

internal data class ArcWholeFunctionProofPhi(
    val id: ArcWholeFunctionPhiId,
    val block: ArcBlockId,
    val identity: ArcWholeFunctionIdentityId,
    val incoming: Map<ArcBlockId, ArcWholeFunctionOwnedTokenId>,
    val result: ArcWholeFunctionOwnedTokenId,
    val binding: ArcWholeFunctionOpaqueBindingId,
)

/**
 * Deep immutable, canonical rule input. Raw [Any] bindings and the caller's collection aliases are
 * retained only in the private transaction baseline and cannot be reached through rule properties.
 */
internal interface ArcWholeFunctionProofSnapshot {
    val functionName: String
    val functionBinding: ArcWholeFunctionOpaqueBindingId
    val entry: ArcBlockId
    val blocks: Map<ArcBlockId, ArcWholeFunctionProofBlock>
    val edges: Set<ArcWholeFunctionProofEdge>
    val phis: List<ArcWholeFunctionProofPhi>
}

/** Private, non-downcastable implementation: rule code can observe only the interface above. */
private class ArcWholeFunctionProofSnapshotImpl(
    override val functionName: String,
    override val functionBinding: ArcWholeFunctionOpaqueBindingId,
    override val entry: ArcBlockId,
    override val blocks: Map<ArcBlockId, ArcWholeFunctionProofBlock>,
    override val edges: Set<ArcWholeFunctionProofEdge>,
    override val phis: List<ArcWholeFunctionProofPhi>,
) : ArcWholeFunctionProofSnapshot

private fun captureProofSnapshot(raw: ArcWholeFunctionOwnershipInventory): ArcWholeFunctionProofSnapshot {
    val baseline = raw.deepImmutableCopy()
    val opaqueBindings = IdentityHashMap<Any, ArcWholeFunctionOpaqueBindingId>()
    var nextBinding = 0
    fun opaque(binding: Any): ArcWholeFunctionOpaqueBindingId =
        opaqueBindings.getOrPut(binding) { ArcWholeFunctionOpaqueBindingId(nextBinding++) }

    val blocks = baseline.blocks.values.sortedBy { it.id.name }.associateTo(linkedMapOf()) { block ->
        block.id to ArcWholeFunctionProofBlock(
            block.id,
            opaque(block.binding),
            immutableList(block.events.map { it.toProofEvent(opaque(it.binding)) }),
        )
    }
    val edges = baseline.edges.sortedWith(compareBy(
        { it.from.name },
        { it.to.name },
        ArcWholeFunctionOwnershipEdge::kind,
        { it.throwingEvent?.name.orEmpty() },
    )).mapTo(linkedSetOf()) { edge ->
        ArcWholeFunctionProofEdge(edge.from, edge.to, edge.kind, opaque(edge.binding), edge.throwingEvent)
    }
    val phis = baseline.phis.sortedBy { it.id.name }.map { phi ->
        ArcWholeFunctionProofPhi(
            phi.id, phi.block, phi.identity, immutableMap(phi.incoming), phi.result, opaque(phi.binding),
        )
    }
    val snapshot = ArcWholeFunctionProofSnapshotImpl(
        baseline.functionName,
        opaque(baseline.functionBinding),
        baseline.entry,
        immutableMap(blocks),
        immutableSet(edges),
        immutableList(phis),
    )
    registerBaseline(snapshot, baseline)
    return snapshot
}

private val snapshotBaselines = Collections.synchronizedMap(
    WeakHashMap<ArcWholeFunctionProofSnapshot, ArcWholeFunctionOwnershipInventory>()
)

private fun registerBaseline(
    snapshot: ArcWholeFunctionProofSnapshot,
    baseline: ArcWholeFunctionOwnershipInventory,
) {
    snapshotBaselines[snapshot] = baseline
}

private fun baselineFor(snapshot: ArcWholeFunctionProofSnapshot): ArcWholeFunctionOwnershipInventory =
    snapshotBaselines[snapshot] ?: error("canonical ownership snapshot has lost its private baseline")

private fun transact(candidate: ArcWholeFunctionRewriteCandidate): ArcWholeFunctionTransactionResult {
        val snapshot = candidate.snapshot
        val baseline = baselineFor(snapshot)
        val before = ArcWholeFunctionOwnershipVerifier.verify(baseline)
        if (before !is ArcWholeFunctionVerificationResult.Success) {
            return rollback(snapshot, candidate, ArcWholeFunctionTransactionFailure(
                ArcWholeFunctionTransactionFailureKind.InvalidOriginal,
                "pre-rewrite whole-function ownership verification failed",
                before,
            ))
        }
        if (candidate.actions.isEmpty()) {
            return rollback(snapshot, candidate, ArcWholeFunctionTransactionFailure(
                ArcWholeFunctionTransactionFailureKind.EmptyRewrite,
                "an atomic rewrite must contain at least one authenticated action",
            ))
        }
        if (candidate.actions.distinctBy { it.targetKey() }.size != candidate.actions.size) {
            return rollback(snapshot, candidate, ArcWholeFunctionTransactionFailure(
                ArcWholeFunctionTransactionFailureKind.DuplicateAction,
                "the transaction addresses one target more than once",
            ))
        }

        val proofEvents = snapshot.blocks.values.flatMap { it.events }.associateBy { it.id }
        val proofPhis = snapshot.phis.associateBy { it.id }
        candidate.actions.forEach { action ->
            val actual = when (action) {
                is ArcWholeFunctionRewriteAction.DeleteEvent -> proofEvents[action.event]?.binding
                is ArcWholeFunctionRewriteAction.DeletePhi -> proofPhis[action.phi]?.binding
            } ?: return rollback(snapshot, candidate, ArcWholeFunctionTransactionFailure(
                ArcWholeFunctionTransactionFailureKind.MissingTarget,
                "rewrite target ${action.targetKey()} is absent",
            ))
            val expected = when (action) {
                is ArcWholeFunctionRewriteAction.DeleteEvent -> action.expectedBinding
                is ArcWholeFunctionRewriteAction.DeletePhi -> action.expectedBinding
            }
            if (actual !== expected) {
                return rollback(snapshot, candidate, ArcWholeFunctionTransactionFailure(
                    ArcWholeFunctionTransactionFailureKind.BindingIdentityDrift,
                    "rewrite target ${action.targetKey()} no longer has its opaque binding capability",
                ))
            }
        }
        val actionBlocks = candidate.actions.mapTo(linkedSetOf()) { action ->
            when (action) {
                is ArcWholeFunctionRewriteAction.DeleteEvent -> snapshot.blocks.values
                    .single { block -> block.events.any { it.id == action.event } }.id
                is ArcWholeFunctionRewriteAction.DeletePhi -> proofPhis.getValue(action.phi).block
            }
        }
        val sealedBlocksAreExact = candidate.touchedBlockBindings.all { (block, binding) ->
            snapshot.blocks[block]?.binding === binding
        }
        if (!sealedBlocksAreExact || actionBlocks != candidate.touchedBlockBindings.keys) {
            return rollback(snapshot, candidate, ArcWholeFunctionTransactionFailure(
                ArcWholeFunctionTransactionFailureKind.IncompleteTouchedBlocks,
                "transaction block seal is ${candidate.touchedBlockBindings.keys}, but actions touch $actionBlocks",
            ))
        }

        val deletedEvents = candidate.actions.filterIsInstance<ArcWholeFunctionRewriteAction.DeleteEvent>()
            .mapTo(linkedSetOf()) { it.event }
        val deletedPhis = candidate.actions.filterIsInstance<ArcWholeFunctionRewriteAction.DeletePhi>()
            .mapTo(linkedSetOf()) { it.phi }
        val rewritten = baseline.copy(
            blocks = baseline.blocks.mapValuesTo(linkedMapOf()) { (_, block) ->
                block.copy(events = block.events.filterNot { it.id in deletedEvents })
            },
            phis = baseline.phis.filterNot { it.id in deletedPhis },
        ).deepImmutableCopy()
        val after = ArcWholeFunctionOwnershipVerifier.verify(rewritten)
        if (after !is ArcWholeFunctionVerificationResult.Success) {
            return rollback(snapshot, candidate, ArcWholeFunctionTransactionFailure(
                ArcWholeFunctionTransactionFailureKind.PostRewriteVerificationFailed,
                "post-rewrite whole-function ownership verification failed",
                after,
            ))
        }
        return ArcWholeFunctionTransactionResult.Committed(baseline, rewritten, candidate)
}

private fun rollback(
    snapshot: ArcWholeFunctionProofSnapshot,
    candidate: ArcWholeFunctionRewriteCandidate,
    failure: ArcWholeFunctionTransactionFailure,
): ArcWholeFunctionTransactionResult.RolledBack {
    val baseline = baselineFor(snapshot)
    return ArcWholeFunctionTransactionResult.RolledBack(baseline, baseline, candidate, failure)
}

internal sealed class ArcWholeFunctionRewriteAction {
    data class DeleteEvent(
        val event: ArcWholeFunctionEventId,
        val expectedBinding: ArcWholeFunctionOpaqueBindingId,
    ) : ArcWholeFunctionRewriteAction()

    data class DeletePhi(
        val phi: ArcWholeFunctionPhiId,
        val expectedBinding: ArcWholeFunctionOpaqueBindingId,
    ) : ArcWholeFunctionRewriteAction()
}

internal data class ArcWholeFunctionRewriteCandidate(
    val snapshot: ArcWholeFunctionProofSnapshot,
    val id: ArcWholeFunctionRewriteId,
    val actions: List<ArcWholeFunctionRewriteAction>,
    val touchedBlockBindings: Map<ArcBlockId, ArcWholeFunctionOpaqueBindingId>,
)

internal enum class ArcWholeFunctionTransactionFailureKind {
    InvalidOriginal,
    EmptyRewrite,
    DuplicateAction,
    MissingTarget,
    BindingIdentityDrift,
    IncompleteTouchedBlocks,
    PostRewriteVerificationFailed,
}

internal data class ArcWholeFunctionTransactionFailure(
    val kind: ArcWholeFunctionTransactionFailureKind,
    val detail: String,
    val verification: ArcWholeFunctionVerificationResult? = null,
)

private sealed class ArcWholeFunctionTransactionResult {
    abstract val original: ArcWholeFunctionOwnershipInventory

    data class Committed(
        override val original: ArcWholeFunctionOwnershipInventory,
        val rewritten: ArcWholeFunctionOwnershipInventory,
        val candidate: ArcWholeFunctionRewriteCandidate,
    ) : ArcWholeFunctionTransactionResult()

    data class RolledBack(
        override val original: ArcWholeFunctionOwnershipInventory,
        val restored: ArcWholeFunctionOwnershipInventory,
        val candidate: ArcWholeFunctionRewriteCandidate,
        val failure: ArcWholeFunctionTransactionFailure,
    ) : ArcWholeFunctionTransactionResult()
}

internal fun interface ArcWholeFunctionSemanticARCRule {
    /** [snapshot] is the rule's only input and has no raw binding or mutable collection surface. */
    fun candidates(snapshot: ArcWholeFunctionProofSnapshot): List<ArcWholeFunctionRewriteCandidate>
}

internal object ArcWholeFunctionAdjacentPairRule : ArcWholeFunctionSemanticARCRule {
    override fun candidates(snapshot: ArcWholeFunctionProofSnapshot): List<ArcWholeFunctionRewriteCandidate> =
        snapshot.blocks.values.sortedBy { it.id.name }.flatMap { block ->
            block.events.zipWithNext().mapNotNull { (producer, destroy) ->
                val tokenAndIdentity = when (producer.kind) {
                    ArcWholeFunctionProofEventKind.Copy, ArcWholeFunctionProofEventKind.StrongLoad ->
                        producer.ownedToken to producer.identity
                    else -> null
                } ?: return@mapNotNull null
                if (tokenAndIdentity.first == null || tokenAndIdentity.second == null ||
                    destroy.kind != ArcWholeFunctionProofEventKind.Destroy ||
                    destroy.ownedToken != tokenAndIdentity.first || destroy.identity != tokenAndIdentity.second
                ) return@mapNotNull null
                ArcWholeFunctionRewriteCandidate(
                    snapshot,
                    ArcWholeFunctionRewriteId("adjacent:${producer.id.name}:${destroy.id.name}"),
                    listOf(
                        ArcWholeFunctionRewriteAction.DeleteEvent(producer.id, producer.binding),
                        ArcWholeFunctionRewriteAction.DeleteEvent(destroy.id, destroy.binding),
                    ),
                    mapOf(block.id to block.binding),
                )
            }
        }
}

internal object ArcWholeFunctionJoinedWebRule : ArcWholeFunctionSemanticARCRule {
    override fun candidates(snapshot: ArcWholeFunctionProofSnapshot): List<ArcWholeFunctionRewriteCandidate> =
        snapshot.phis.sortedBy { it.id.name }.mapNotNull { phi ->
            val incomingEdges = snapshot.edges.filter { it.to == phi.block }
            if (incomingEdges.isEmpty() || incomingEdges.any { it.kind != ArcSSAEdgeKind.Normal }) return@mapNotNull null
            val copies = incomingEdges.map { edge ->
                snapshot.blocks.getValue(edge.from).events.lastOrNull()
                    ?.takeIf { it.kind == ArcWholeFunctionProofEventKind.Copy } ?: return@mapNotNull null
            }
            if (copies.any { copy ->
                    copy.identity != phi.identity || phi.incoming[copyBlock(snapshot, copy)] != copy.ownedToken
                }
            ) return@mapNotNull null
            val destroy = snapshot.blocks.getValue(phi.block).events.firstOrNull()
                ?.takeIf { it.kind == ArcWholeFunctionProofEventKind.Destroy } ?: return@mapNotNull null
            if (destroy.ownedToken != phi.result || destroy.identity != phi.identity) return@mapNotNull null

            ArcWholeFunctionRewriteCandidate(
                snapshot,
                ArcWholeFunctionRewriteId("joined:${phi.id.name}"),
                buildList {
                    copies.forEach { add(ArcWholeFunctionRewriteAction.DeleteEvent(it.id, it.binding)) }
                    add(ArcWholeFunctionRewriteAction.DeletePhi(phi.id, phi.binding))
                    add(ArcWholeFunctionRewriteAction.DeleteEvent(destroy.id, destroy.binding))
                },
                (incomingEdges.map { it.from } + phi.block).associateWith { snapshot.blocks.getValue(it).binding },
            )
        }

    private fun copyBlock(snapshot: ArcWholeFunctionProofSnapshot, copy: ArcWholeFunctionProofEvent): ArcBlockId =
        snapshot.blocks.values.single { block -> block.events.any { it === copy } }.id
}

internal data class ArcWholeFunctionRuleFailure(
    val ruleIndex: Int,
    val detail: String,
)

internal data class ArcWholeFunctionRollbackRecord(
    val candidate: ArcWholeFunctionRewriteId,
    val failure: ArcWholeFunctionTransactionFailure,
)

internal data class ArcWholeFunctionFixedPointResult(
    val original: ArcWholeFunctionOwnershipInventory,
    val optimizedProofSnapshot: ArcWholeFunctionOwnershipInventory,
    val initialVerification: ArcWholeFunctionVerificationResult,
    val finalVerification: ArcWholeFunctionVerificationResult,
    val committedTransactions: List<ArcWholeFunctionRewriteCandidate>,
    val rolledBackTransactions: List<ArcWholeFunctionRollbackRecord>,
    val ruleFailures: List<ArcWholeFunctionRuleFailure>,
    val fixedPointRounds: Int,
    val converged: Boolean,
) {
    val emitted: Boolean get() = false
    val physicalOwnershipEventsRemoved: Int? get() = null
    val logicallyEliminatedEvents: Int
        get() = committedTransactions.sumOf { candidate ->
            candidate.actions.count { it is ArcWholeFunctionRewriteAction.DeleteEvent }
        }
}

internal object ArcWholeFunctionSemanticARCFixedPoint {
    fun optimize(
        rawOriginal: ArcWholeFunctionOwnershipInventory,
        rules: List<ArcWholeFunctionSemanticARCRule> = listOf(
            ArcWholeFunctionAdjacentPairRule,
            ArcWholeFunctionJoinedWebRule,
        ),
        maximumRounds: Int = 100,
    ): ArcWholeFunctionFixedPointResult {
        require(maximumRounds > 0)
        val initialSnapshot = captureProofSnapshot(rawOriginal)
        val original = baselineFor(initialSnapshot)
        val initialVerification = ArcWholeFunctionOwnershipVerifier.verify(original)
        if (initialVerification !is ArcWholeFunctionVerificationResult.Success) {
            return ArcWholeFunctionFixedPointResult(
                original, original, initialVerification, initialVerification,
                emptyList(), emptyList(), emptyList(), fixedPointRounds = 0, converged = false,
            )
        }
        var current = original
        val committed = mutableListOf<ArcWholeFunctionRewriteCandidate>()
        val rolledBack = mutableListOf<ArcWholeFunctionRollbackRecord>()
        val ruleFailures = mutableListOf<ArcWholeFunctionRuleFailure>()
        var rounds = 0
        var converged = false

        while (rounds < maximumRounds) {
            rounds++
            val snapshot = captureProofSnapshot(current)
            val candidates = rules.flatMapIndexed { index, rule ->
                try {
                    rule.candidates(snapshot).also { candidates ->
                        if (candidates.any { it.snapshot !== snapshot }) {
                            throw IllegalArgumentException("rule returned a candidate sealed by another snapshot")
                        }
                    }
                } catch (failure: Throwable) {
                    if (failure.isFatalCompilerFailure()) throw failure
                    ruleFailures += ArcWholeFunctionRuleFailure(
                        index,
                        "${failure::class.java.simpleName}: ${failure.message.orEmpty()}",
                    )
                    emptyList()
                }
            }.distinctBy { it.id }.sortedBy { it.id.name }
            var changed = false
            for (candidate in candidates) {
                when (val result = transact(candidate)) {
                    is ArcWholeFunctionTransactionResult.Committed -> {
                        current = result.rewritten
                        committed += candidate
                        changed = true
                        break
                    }
                    is ArcWholeFunctionTransactionResult.RolledBack -> rolledBack +=
                        ArcWholeFunctionRollbackRecord(candidate.id, result.failure)
                }
            }
            if (!changed) {
                converged = true
                break
            }
        }
        val finalVerification = ArcWholeFunctionOwnershipVerifier.verify(current)
        return ArcWholeFunctionFixedPointResult(
            original, current, initialVerification, finalVerification,
            committed, rolledBack, ruleFailures, rounds, converged,
        )
    }
}

private fun Throwable.isFatalCompilerFailure(): Boolean =
    this is VirtualMachineError || this is ThreadDeath || this is LinkageError

private fun ArcWholeFunctionOwnershipEvent.toProofEvent(binding: ArcWholeFunctionOpaqueBindingId) = when (this) {
    is ArcWholeFunctionOwnershipEvent.Copy -> ArcWholeFunctionProofEvent(
        id, ArcWholeFunctionProofEventKind.Copy, binding, source = source, ownedToken = result, identity = identity,
    )
    is ArcWholeFunctionOwnershipEvent.Destroy -> ArcWholeFunctionProofEvent(
        id, ArcWholeFunctionProofEventKind.Destroy, binding, ownedToken = token, identity = identity,
    )
    is ArcWholeFunctionOwnershipEvent.BeginBorrow -> ArcWholeFunctionProofEvent(
        id, ArcWholeFunctionProofEventKind.BeginBorrow, binding, source = source, borrowToken = result, identity = identity,
    )
    is ArcWholeFunctionOwnershipEvent.EndBorrow -> ArcWholeFunctionProofEvent(
        id, ArcWholeFunctionProofEventKind.EndBorrow, binding, borrowToken = token, identity = identity,
    )
    is ArcWholeFunctionOwnershipEvent.StrongStore -> ArcWholeFunctionProofEvent(
        id, ArcWholeFunctionProofEventKind.StrongStore, binding, source = source, storage = storage,
        expectedOldIdentity = expectedOldIdentity, identity = identity,
    )
    is ArcWholeFunctionOwnershipEvent.StrongLoad -> ArcWholeFunctionProofEvent(
        id, ArcWholeFunctionProofEventKind.StrongLoad, binding, ownedToken = result, storage = storage, identity = identity,
    )
    is ArcWholeFunctionOwnershipEvent.DestroyStorage -> ArcWholeFunctionProofEvent(
        id, ArcWholeFunctionProofEventKind.DestroyStorage, binding, storage = storage,
        expectedOldIdentity = expectedIdentity, identity = expectedIdentity,
    )
    is ArcWholeFunctionOwnershipEvent.Barrier -> ArcWholeFunctionProofEvent(
        id, ArcWholeFunctionProofEventKind.Barrier, binding, barrierKind = kind, mayThrow = mayThrow,
    )
}

private fun ArcWholeFunctionRewriteAction.targetKey(): String = when (this) {
    is ArcWholeFunctionRewriteAction.DeleteEvent -> "event:${event.name}"
    is ArcWholeFunctionRewriteAction.DeletePhi -> "phi:${phi.name}"
}

private fun ArcWholeFunctionOwnershipInventory.deepImmutableCopy(): ArcWholeFunctionOwnershipInventory {
    val copiedBlocks = blocks.entries.sortedBy { it.key.name }.associateTo(linkedMapOf()) { (key, block) ->
        key to block.copy(events = immutableList(block.events.toList()))
    }
    val copiedEdges = edges.sortedWith(compareBy(
        { it.from.name },
        { it.to.name },
        ArcWholeFunctionOwnershipEdge::kind,
        { it.throwingEvent?.name.orEmpty() },
    )).toCollection(linkedSetOf())
    val copiedPhis = phis.sortedBy { it.id.name }.map { it.copy(incoming = immutableMap(it.incoming)) }
    return copy(
        blocks = immutableMap(copiedBlocks),
        edges = immutableSet(copiedEdges),
        phis = immutableList(copiedPhis),
        entryOwnedTokens = immutableMap(entryOwnedTokens),
        entryStorage = immutableMap(entryStorage),
        expectedExitOwnedTokens = immutableMap(expectedExitOwnedTokens),
        expectedExitStorage = immutableMap(expectedExitStorage),
    )
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(values.toList())

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
