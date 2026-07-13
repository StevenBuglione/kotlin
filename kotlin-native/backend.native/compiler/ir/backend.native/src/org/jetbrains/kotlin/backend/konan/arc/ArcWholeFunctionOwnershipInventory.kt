/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

/** Stable identities authenticated by a future lowered-IR adapter. */
@JvmInline internal value class ArcWholeFunctionEventId(val name: String)
@JvmInline internal value class ArcWholeFunctionIdentityId(val name: String)
@JvmInline internal value class ArcWholeFunctionOwnedTokenId(val name: String)
@JvmInline internal value class ArcWholeFunctionBorrowTokenId(val name: String)
@JvmInline internal value class ArcWholeFunctionStorageId(val name: String)
@JvmInline internal value class ArcWholeFunctionPhiId(val name: String)

internal sealed class ArcWholeFunctionSource {
    abstract val identity: ArcWholeFunctionIdentityId

    data class Owned(
        val token: ArcWholeFunctionOwnedTokenId,
        override val identity: ArcWholeFunctionIdentityId,
    ) : ArcWholeFunctionSource()

    data class Borrowed(
        val token: ArcWholeFunctionBorrowTokenId,
        override val identity: ArcWholeFunctionIdentityId,
    ) : ArcWholeFunctionSource()

    data class Immortal(
        override val identity: ArcWholeFunctionIdentityId,
    ) : ArcWholeFunctionSource()

    data class Storage(
        val storage: ArcWholeFunctionStorageId,
        override val identity: ArcWholeFunctionIdentityId,
    ) : ArcWholeFunctionSource()
}

internal enum class ArcWholeFunctionBarrierKind {
    /** A call with a completely modeled normal and exceptional ARC state. */
    ExceptionalCall,
    Deinitialization,
    Suspension,
    ForeignCall,
    UnknownEffect,
}

/**
 * An exact, emission-independent ownership event from one complete lowered function walk.
 *
 * [binding] is compared by object identity. It is intentionally not interpreted by this layer:
 * a future adapter must bind it to the exact lowered node which would emit the event.
 */
internal sealed class ArcWholeFunctionOwnershipEvent {
    abstract val id: ArcWholeFunctionEventId
    abstract val binding: Any

    data class Copy(
        override val id: ArcWholeFunctionEventId,
        val source: ArcWholeFunctionSource,
        val result: ArcWholeFunctionOwnedTokenId,
        val identity: ArcWholeFunctionIdentityId,
        override val binding: Any,
    ) : ArcWholeFunctionOwnershipEvent()

    data class Destroy(
        override val id: ArcWholeFunctionEventId,
        val token: ArcWholeFunctionOwnedTokenId,
        val identity: ArcWholeFunctionIdentityId,
        override val binding: Any,
    ) : ArcWholeFunctionOwnershipEvent()

    data class BeginBorrow(
        override val id: ArcWholeFunctionEventId,
        val source: ArcWholeFunctionSource,
        val result: ArcWholeFunctionBorrowTokenId,
        val identity: ArcWholeFunctionIdentityId,
        override val binding: Any,
    ) : ArcWholeFunctionOwnershipEvent()

    data class EndBorrow(
        override val id: ArcWholeFunctionEventId,
        val token: ArcWholeFunctionBorrowTokenId,
        val identity: ArcWholeFunctionIdentityId,
        override val binding: Any,
    ) : ArcWholeFunctionOwnershipEvent()

    /** Retains [source], publishes it, and then releases the exact expected old slot value. */
    data class StrongStore(
        override val id: ArcWholeFunctionEventId,
        val storage: ArcWholeFunctionStorageId,
        val source: ArcWholeFunctionSource,
        val expectedOldIdentity: ArcWholeFunctionIdentityId?,
        val identity: ArcWholeFunctionIdentityId,
        override val binding: Any,
    ) : ArcWholeFunctionOwnershipEvent()

    data class StrongLoad(
        override val id: ArcWholeFunctionEventId,
        val storage: ArcWholeFunctionStorageId,
        val identity: ArcWholeFunctionIdentityId,
        val result: ArcWholeFunctionOwnedTokenId,
        override val binding: Any,
    ) : ArcWholeFunctionOwnershipEvent()

    /** Releases and clears one exact strong storage slot. */
    data class DestroyStorage(
        override val id: ArcWholeFunctionEventId,
        val storage: ArcWholeFunctionStorageId,
        val expectedIdentity: ArcWholeFunctionIdentityId,
        override val binding: Any,
    ) : ArcWholeFunctionOwnershipEvent()

    data class Barrier(
        override val id: ArcWholeFunctionEventId,
        val kind: ArcWholeFunctionBarrierKind,
        val mayThrow: Boolean,
        override val binding: Any,
    ) : ArcWholeFunctionOwnershipEvent()
}

internal data class ArcWholeFunctionOwnershipBlock(
    val id: ArcBlockId,
    val binding: Any,
    val events: List<ArcWholeFunctionOwnershipEvent>,
)

/** An exceptional edge is anchored to the exact throwing event, including mid-block invokes. */
internal data class ArcWholeFunctionOwnershipEdge(
    val from: ArcBlockId,
    val to: ArcBlockId,
    val kind: ArcSSAEdgeKind,
    val binding: Any,
    val throwingEvent: ArcWholeFunctionEventId? = null,
)

/** Normalizes path-specific +1 tokens before the destination block's first event. */
internal data class ArcWholeFunctionOwnershipPhi(
    val id: ArcWholeFunctionPhiId,
    val block: ArcBlockId,
    val identity: ArcWholeFunctionIdentityId,
    val incoming: Map<ArcBlockId, ArcWholeFunctionOwnedTokenId>,
    val result: ArcWholeFunctionOwnedTokenId,
    val binding: Any,
)

internal data class ArcWholeFunctionOwnershipInventory(
    val functionName: String,
    val functionBinding: Any,
    val entry: ArcBlockId,
    val blocks: Map<ArcBlockId, ArcWholeFunctionOwnershipBlock>,
    val edges: Set<ArcWholeFunctionOwnershipEdge>,
    val phis: List<ArcWholeFunctionOwnershipPhi>,
    val entryOwnedTokens: Map<ArcWholeFunctionOwnedTokenId, ArcWholeFunctionIdentityId> = emptyMap(),
    val entryStorage: Map<ArcWholeFunctionStorageId, ArcWholeFunctionIdentityId> = emptyMap(),
    val expectedExitOwnedTokens: Map<ArcWholeFunctionOwnedTokenId, ArcWholeFunctionIdentityId> = entryOwnedTokens,
    val expectedExitStorage: Map<ArcWholeFunctionStorageId, ArcWholeFunctionIdentityId> = entryStorage,
    val completeLoweredIRWalk: Boolean,
)

internal enum class ArcWholeFunctionVerificationCode {
    IncompleteInventory,
    MissingEntry,
    DuplicateEvent,
    DuplicatePhi,
    DuplicateBinding,
    MissingBlock,
    InvalidEdge,
    InvalidExceptionalEdge,
    UnreachableBlock,
    InvalidPhi,
    IncompatiblePathState,
    MissingSource,
    IdentityMismatch,
    DuplicateOwnedToken,
    MissingOwnedToken,
    DuplicateBorrowToken,
    MissingBorrowToken,
    OwnerEndedWithLiveBorrow,
    StorageMismatch,
    StorageEndedWithLiveBorrow,
    LiveBorrowAtExit,
    ExitStateMismatch,
}

internal data class ArcWholeFunctionVerificationIssue(
    val code: ArcWholeFunctionVerificationCode,
    val detail: String,
)

internal sealed class ArcWholeFunctionVerificationResult {
    object Success : ArcWholeFunctionVerificationResult()
    data class Failure(val issues: List<ArcWholeFunctionVerificationIssue>) : ArcWholeFunctionVerificationResult()
}

/** Whole-function verifier for normal and exceptional ownership paths. */
internal object ArcWholeFunctionOwnershipVerifier {
    private data class State(
        val owned: Map<ArcWholeFunctionOwnedTokenId, ArcWholeFunctionIdentityId>,
        val borrows: Map<ArcWholeFunctionBorrowTokenId, BorrowState>,
        val storage: Map<ArcWholeFunctionStorageId, ArcWholeFunctionIdentityId>,
    )

    private data class BorrowState(
        val identity: ArcWholeFunctionIdentityId,
        val source: ArcWholeFunctionSource,
    )

    fun verify(inventory: ArcWholeFunctionOwnershipInventory): ArcWholeFunctionVerificationResult {
        val structural = verifyStructure(inventory)
        if (structural.isNotEmpty()) return ArcWholeFunctionVerificationResult.Failure(structural)

        val issues = mutableListOf<ArcWholeFunctionVerificationIssue>()
        val incoming = linkedMapOf(inventory.entry to State(
            inventory.entryOwnedTokens,
            emptyMap(),
            inventory.entryStorage,
        ))
        val worklist = ArrayDeque<ArcBlockId>().apply { add(inventory.entry) }
        val visited = linkedSetOf<ArcBlockId>()

        fun propagate(edge: ArcWholeFunctionOwnershipEdge, rawState: State) {
            val state = applyPhis(inventory, edge, rawState, issues) ?: return
            val previous = incoming[edge.to]
            when {
                previous == null -> {
                    incoming[edge.to] = state
                    worklist += edge.to
                }
                previous != state -> issues += issue(
                    ArcWholeFunctionVerificationCode.IncompatiblePathState,
                    "${edge.to} receives $previous and $state",
                )
            }
        }

        while (worklist.isNotEmpty()) {
            val blockId = worklist.removeFirst()
            if (!visited.add(blockId)) continue
            val block = inventory.blocks.getValue(blockId)
            var state = incoming.getValue(blockId)
            block.events.forEach { event ->
                state = applyEvent(event, state, issues)
                if (event is ArcWholeFunctionOwnershipEvent.Barrier && event.mayThrow) {
                    inventory.edges.filter {
                        it.from == blockId && it.kind == ArcSSAEdgeKind.Exceptional && it.throwingEvent == event.id
                    }.forEach { propagate(it, state) }
                }
            }

            val normalEdges = inventory.edges.filter { it.from == blockId && it.kind == ArcSSAEdgeKind.Normal }
            if (normalEdges.isEmpty()) verifyExit(inventory, blockId, state, issues)
            else normalEdges.forEach { propagate(it, state) }
        }

        inventory.blocks.keys.filterNot { it in visited }.forEach {
            issues += issue(ArcWholeFunctionVerificationCode.UnreachableBlock, "$it is unreachable")
        }
        return if (issues.isEmpty()) ArcWholeFunctionVerificationResult.Success
        else ArcWholeFunctionVerificationResult.Failure(issues.distinct())
    }

    private fun verifyStructure(inventory: ArcWholeFunctionOwnershipInventory): List<ArcWholeFunctionVerificationIssue> {
        val issues = mutableListOf<ArcWholeFunctionVerificationIssue>()
        if (!inventory.completeLoweredIRWalk) {
            issues += issue(ArcWholeFunctionVerificationCode.IncompleteInventory, "lowered ownership walk is incomplete")
        }
        if (inventory.entry !in inventory.blocks) {
            issues += issue(ArcWholeFunctionVerificationCode.MissingEntry, "entry ${inventory.entry} is missing")
        }
        val events = inventory.blocks.values.flatMap { it.events }
        events.groupBy { it.id }.filterValues { it.size != 1 }.keys.forEach {
            issues += issue(ArcWholeFunctionVerificationCode.DuplicateEvent, "$it is not unique")
        }
        inventory.phis.groupBy { it.id }.filterValues { it.size != 1 }.keys.forEach {
            issues += issue(ArcWholeFunctionVerificationCode.DuplicatePhi, "$it is not unique")
        }
        val ownedDefinitions = buildList {
            addAll(inventory.entryOwnedTokens.keys)
            events.forEach { event ->
                when (event) {
                    is ArcWholeFunctionOwnershipEvent.Copy -> add(event.result)
                    is ArcWholeFunctionOwnershipEvent.StrongLoad -> add(event.result)
                    else -> Unit
                }
            }
            inventory.phis.forEach { add(it.result) }
        }
        ownedDefinitions.groupBy { it }.filterValues { it.size != 1 }.keys.forEach {
            issues += issue(ArcWholeFunctionVerificationCode.DuplicateOwnedToken, "$it has more than one definition")
        }
        events.filterIsInstance<ArcWholeFunctionOwnershipEvent.BeginBorrow>()
            .groupBy { it.result }
            .filterValues { it.size != 1 }
            .keys
            .forEach {
                issues += issue(ArcWholeFunctionVerificationCode.DuplicateBorrowToken, "$it has more than one definition")
            }
        val bindings = buildList<Any> {
            add(inventory.functionBinding)
            inventory.blocks.values.forEach { block -> add(block.binding); block.events.forEach { add(it.binding) } }
            inventory.edges.forEach { add(it.binding) }
            inventory.phis.forEach { add(it.binding) }
        }
        bindings.indices.forEach { left ->
            if ((left + 1 until bindings.size).any { bindings[left] === bindings[it] }) {
                issues += issue(ArcWholeFunctionVerificationCode.DuplicateBinding, "lowered binding identity is reused")
            }
        }
        inventory.edges.forEach { edge ->
            if (edge.from !in inventory.blocks || edge.to !in inventory.blocks) {
                issues += issue(ArcWholeFunctionVerificationCode.MissingBlock, "$edge references a missing block")
            }
            when (edge.kind) {
                ArcSSAEdgeKind.Normal -> if (edge.throwingEvent != null) {
                    issues += issue(ArcWholeFunctionVerificationCode.InvalidEdge, "normal edge has a throwing event")
                }
                ArcSSAEdgeKind.Exceptional -> {
                    val event = inventory.blocks[edge.from]?.events?.singleOrNull { it.id == edge.throwingEvent }
                    if (event !is ArcWholeFunctionOwnershipEvent.Barrier || !event.mayThrow) {
                        issues += issue(
                            ArcWholeFunctionVerificationCode.InvalidExceptionalEdge,
                            "exceptional edge ${edge.from} -> ${edge.to} is not anchored to one throwing barrier",
                        )
                    }
                }
            }
        }
        events.filterIsInstance<ArcWholeFunctionOwnershipEvent.Barrier>().forEach { barrier ->
            val owners = inventory.blocks.values.filter { block -> block.events.any { it === barrier } }
            if (owners.size != 1) {
                issues += issue(
                    ArcWholeFunctionVerificationCode.DuplicateEvent,
                    "barrier ${barrier.id} must belong to exactly one block",
                )
                return@forEach
            }
            val owner = owners.single().id
            val modeledEdges = inventory.edges.filter {
                it.from == owner && it.kind == ArcSSAEdgeKind.Exceptional && it.throwingEvent == barrier.id
            }
            if (barrier.mayThrow && modeledEdges.size != 1) {
                issues += issue(
                    ArcWholeFunctionVerificationCode.InvalidExceptionalEdge,
                    "throwing barrier ${barrier.id} requires exactly one exceptional cleanup successor, found ${modeledEdges.size}",
                )
            }
            if (!barrier.mayThrow && modeledEdges.isNotEmpty()) {
                issues += issue(
                    ArcWholeFunctionVerificationCode.InvalidExceptionalEdge,
                    "non-throwing barrier ${barrier.id} owns an exceptional edge",
                )
            }
        }
        inventory.edges.groupBy { listOf(it.from, it.to, it.kind, it.throwingEvent) }
            .filterValues { it.size != 1 }
            .keys
            .forEach { issues += issue(ArcWholeFunctionVerificationCode.InvalidEdge, "duplicate semantic edge $it") }
        inventory.phis.forEach { phi ->
            val predecessors = inventory.edges.filter { it.to == phi.block }.mapTo(linkedSetOf()) { it.from }
            if (phi.block !in inventory.blocks || phi.incoming.keys != predecessors || phi.incoming.isEmpty() ||
                phi.result in inventory.entryOwnedTokens
            ) {
                issues += issue(ArcWholeFunctionVerificationCode.InvalidPhi, "${phi.id} does not seal all predecessor tokens")
            }
        }
        inventory.blocks.forEach { (key, block) ->
            if (key != block.id) {
                issues += issue(
                    ArcWholeFunctionVerificationCode.MissingBlock,
                    "block map key $key does not match embedded id ${block.id}",
                )
            }
        }
        return issues.distinct()
    }

    private fun applyPhis(
        inventory: ArcWholeFunctionOwnershipInventory,
        edge: ArcWholeFunctionOwnershipEdge,
        rawState: State,
        issues: MutableList<ArcWholeFunctionVerificationIssue>,
    ): State? {
        val phis = inventory.phis.filter { it.block == edge.to }
        if (phis.isEmpty()) return rawState
        val incomingTokens = linkedMapOf<ArcWholeFunctionOwnershipPhi, ArcWholeFunctionOwnedTokenId>()
        phis.forEach { phi ->
            val incoming = phi.incoming[edge.from]
            val actual = incoming?.let { rawState.owned[it] }
            if (incoming == null || actual != phi.identity || phi.result in rawState.owned) {
                issues += issue(
                    ArcWholeFunctionVerificationCode.InvalidPhi,
                    "${phi.id} cannot map ${edge.from}'s $incoming/$actual to ${phi.result}:${phi.identity}",
                )
                return null
            }
            incomingTokens[phi] = incoming
        }
        if (incomingTokens.values.toSet().size != incomingTokens.size) {
            issues += issue(
                ArcWholeFunctionVerificationCode.InvalidPhi,
                "parallel phis at ${edge.to} consume the same incoming ownership token",
            )
            return null
        }
        return rawState.copy(owned = rawState.owned.toMutableMap().apply {
            incomingTokens.values.forEach { remove(it) }
            phis.forEach { put(it.result, it.identity) }
        })
    }

    private fun applyEvent(
        event: ArcWholeFunctionOwnershipEvent,
        input: State,
        issues: MutableList<ArcWholeFunctionVerificationIssue>,
    ): State {
        val owned = input.owned.toMutableMap()
        val borrows = input.borrows.toMutableMap()
        val storage = input.storage.toMutableMap()

        fun sourceExists(source: ArcWholeFunctionSource): Boolean {
            val actual = when (source) {
                is ArcWholeFunctionSource.Owned -> owned[source.token]
                is ArcWholeFunctionSource.Borrowed -> borrows[source.token]?.identity
                is ArcWholeFunctionSource.Immortal -> source.identity
                is ArcWholeFunctionSource.Storage -> storage[source.storage]
            }
            if (actual == null) {
                issues += issue(ArcWholeFunctionVerificationCode.MissingSource, "${event.id} has no live source $source")
                return false
            }
            if (actual != source.identity) {
                issues += issue(ArcWholeFunctionVerificationCode.IdentityMismatch, "${event.id} expected ${source.identity}, found $actual")
                return false
            }
            return true
        }

        when (event) {
            is ArcWholeFunctionOwnershipEvent.Copy -> {
                if (event.source.identity != event.identity) {
                    issues += issue(ArcWholeFunctionVerificationCode.IdentityMismatch, "${event.id}'s source is ${event.source.identity}")
                } else if (!sourceExists(event.source)) Unit
                else if (owned.putIfAbsent(event.result, event.identity) != null) {
                    issues += issue(ArcWholeFunctionVerificationCode.DuplicateOwnedToken, "${event.result} is already live")
                }
            }
            is ArcWholeFunctionOwnershipEvent.Destroy -> {
                val actual = owned[event.token]
                if (actual == null) issues += issue(ArcWholeFunctionVerificationCode.MissingOwnedToken, "${event.token} is not live")
                else if (actual != event.identity) issues += issue(ArcWholeFunctionVerificationCode.IdentityMismatch, "${event.token} is $actual")
                else if (borrows.keys.any { borrowDependsOnOwned(it, event.token, borrows) }) {
                    issues += issue(
                        ArcWholeFunctionVerificationCode.OwnerEndedWithLiveBorrow,
                        "${event.token} is destroyed with a dependent live borrow",
                    )
                } else owned.remove(event.token)
            }
            is ArcWholeFunctionOwnershipEvent.BeginBorrow -> {
                if (event.source.identity != event.identity) {
                    issues += issue(ArcWholeFunctionVerificationCode.IdentityMismatch, "${event.id}'s source is ${event.source.identity}")
                } else if (!sourceExists(event.source)) Unit
                else if (borrows.putIfAbsent(event.result, BorrowState(event.identity, event.source)) != null) {
                    issues += issue(ArcWholeFunctionVerificationCode.DuplicateBorrowToken, "${event.result} is already live")
                }
            }
            is ArcWholeFunctionOwnershipEvent.EndBorrow -> {
                val actual = borrows[event.token]
                if (actual == null) issues += issue(ArcWholeFunctionVerificationCode.MissingBorrowToken, "${event.token} is not live")
                else if (actual.identity != event.identity) issues += issue(ArcWholeFunctionVerificationCode.IdentityMismatch, "${event.token} is ${actual.identity}")
                else if (borrows.keys.any { it != event.token && borrowDependsOnBorrow(it, event.token, borrows) }) {
                    issues += issue(
                        ArcWholeFunctionVerificationCode.OwnerEndedWithLiveBorrow,
                        "${event.token} ends while a transitive reborrow remains live",
                    )
                } else borrows.remove(event.token)
            }
            is ArcWholeFunctionOwnershipEvent.StrongStore -> {
                val actualOld = storage[event.storage]
                if (actualOld != event.expectedOldIdentity) {
                    issues += issue(
                        ArcWholeFunctionVerificationCode.StorageMismatch,
                        "${event.storage} expected ${event.expectedOldIdentity}, found $actualOld",
                    )
                } else if (actualOld != null && borrows.keys.any { borrowDependsOnStorage(it, event.storage, borrows) }) {
                    issues += issue(
                        ArcWholeFunctionVerificationCode.StorageEndedWithLiveBorrow,
                        "${event.storage} is replaced while a transitive storage borrow remains live",
                    )
                } else if (event.source.identity != event.identity) {
                    issues += issue(ArcWholeFunctionVerificationCode.IdentityMismatch, "${event.id}'s source is ${event.source.identity}")
                } else if (!sourceExists(event.source)) Unit
                else storage[event.storage] = event.identity
            }
            is ArcWholeFunctionOwnershipEvent.StrongLoad -> {
                val actual = storage[event.storage]
                if (actual != event.identity) {
                    issues += issue(ArcWholeFunctionVerificationCode.StorageMismatch, "${event.storage} is $actual, not ${event.identity}")
                } else if (owned.putIfAbsent(event.result, event.identity) != null) {
                    issues += issue(ArcWholeFunctionVerificationCode.DuplicateOwnedToken, "${event.result} is already live")
                }
            }
            is ArcWholeFunctionOwnershipEvent.DestroyStorage -> {
                val actual = storage[event.storage]
                if (actual != event.expectedIdentity) {
                    issues += issue(
                        ArcWholeFunctionVerificationCode.StorageMismatch,
                        "${event.storage} is $actual, not ${event.expectedIdentity}",
                    )
                } else if (borrows.keys.any { borrowDependsOnStorage(it, event.storage, borrows) }) {
                    issues += issue(
                        ArcWholeFunctionVerificationCode.StorageEndedWithLiveBorrow,
                        "${event.storage} is destroyed while a transitive storage borrow remains live",
                    )
                } else storage.remove(event.storage)
            }
            is ArcWholeFunctionOwnershipEvent.Barrier -> Unit
        }
        return State(owned, borrows, storage)
    }

    private fun borrowDependsOnOwned(
        borrow: ArcWholeFunctionBorrowTokenId,
        owned: ArcWholeFunctionOwnedTokenId,
        borrows: Map<ArcWholeFunctionBorrowTokenId, BorrowState>,
        visiting: MutableSet<ArcWholeFunctionBorrowTokenId> = linkedSetOf(),
    ): Boolean {
        if (!visiting.add(borrow)) return true
        return when (val source = borrows[borrow]?.source) {
            is ArcWholeFunctionSource.Owned -> source.token == owned
            is ArcWholeFunctionSource.Borrowed -> borrowDependsOnOwned(source.token, owned, borrows, visiting)
            is ArcWholeFunctionSource.Immortal, is ArcWholeFunctionSource.Storage, null -> false
        }
    }

    private fun borrowDependsOnBorrow(
        borrow: ArcWholeFunctionBorrowTokenId,
        parent: ArcWholeFunctionBorrowTokenId,
        borrows: Map<ArcWholeFunctionBorrowTokenId, BorrowState>,
        visiting: MutableSet<ArcWholeFunctionBorrowTokenId> = linkedSetOf(),
    ): Boolean {
        if (!visiting.add(borrow)) return true
        return when (val source = borrows[borrow]?.source) {
            is ArcWholeFunctionSource.Borrowed ->
                source.token == parent || borrowDependsOnBorrow(source.token, parent, borrows, visiting)
            is ArcWholeFunctionSource.Owned, is ArcWholeFunctionSource.Immortal,
            is ArcWholeFunctionSource.Storage, null -> false
        }
    }

    private fun borrowDependsOnStorage(
        borrow: ArcWholeFunctionBorrowTokenId,
        storage: ArcWholeFunctionStorageId,
        borrows: Map<ArcWholeFunctionBorrowTokenId, BorrowState>,
        visiting: MutableSet<ArcWholeFunctionBorrowTokenId> = linkedSetOf(),
    ): Boolean {
        if (!visiting.add(borrow)) return true
        return when (val source = borrows[borrow]?.source) {
            is ArcWholeFunctionSource.Storage -> source.storage == storage
            is ArcWholeFunctionSource.Borrowed -> borrowDependsOnStorage(source.token, storage, borrows, visiting)
            is ArcWholeFunctionSource.Owned, is ArcWholeFunctionSource.Immortal, null -> false
        }
    }

    private fun verifyExit(
        inventory: ArcWholeFunctionOwnershipInventory,
        block: ArcBlockId,
        state: State,
        issues: MutableList<ArcWholeFunctionVerificationIssue>,
    ) {
        if (state.borrows.isNotEmpty()) {
            issues += issue(ArcWholeFunctionVerificationCode.LiveBorrowAtExit, "$block has live borrows ${state.borrows.keys}")
        }
        if (state.owned != inventory.expectedExitOwnedTokens || state.storage != inventory.expectedExitStorage) {
            issues += issue(
                ArcWholeFunctionVerificationCode.ExitStateMismatch,
                "$block exits with owned=${state.owned}, storage=${state.storage}",
            )
        }
    }

    private fun issue(code: ArcWholeFunctionVerificationCode, detail: String) =
        ArcWholeFunctionVerificationIssue(code, detail)
}
