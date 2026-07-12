/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

/**
 * Emission-independent ownership SSA for joined reference values.
 *
 * This is intentionally a closed model rather than an LLVM or IR rewrite. The IR adapter is
 * expected to describe an acyclic lowered `IrWhen` diamond using identity-stable [ArcSSAValue]s,
 * run this analysis, and only later teach codegen how to consume [ArcOwnershipSSAWeb]. Keeping the
 * proof object independent lets the existing path verifier remain fail-closed until that seam is
 * wired.
 */
@JvmInline
internal value class ArcSSAValue(val name: String) {
    override fun toString(): String = "%$name"
}

@JvmInline
internal value class ArcSSASlot(val name: String) {
    override fun toString(): String = "slot($name)"
}

/** A unique proof token for one physical slot state. Tokens may never be reused after a move. */
@JvmInline
internal value class ArcSSASlotVersion(val name: String) {
    override fun toString(): String = "slot-version($name)"
}

/** A unique lexical borrow identity. A live borrow prevents moving or destroying its anchor. */
@JvmInline
internal value class ArcSSABorrowId(val name: String) {
    override fun toString(): String = "borrow($name)"
}

internal enum class ArcSSAEdgeKind { Normal, Exceptional }

internal data class ArcSSAEdge(
    val from: ArcBlockId,
    val to: ArcBlockId,
    val kind: ArcSSAEdgeKind = ArcSSAEdgeKind.Normal,
)

/** The ownership effects which must be classified before a joined web can be converted. */
internal enum class ArcSSAUseKind {
    Borrow,
    Consume,
    Escape,
    UnknownConsume,
}

internal sealed class ArcSSAOperation {
    abstract val location: ArcPlanLocation?

    data class Introduce(
        val result: ArcSSAValue,
        val ownership: ArcOwnership,
        /** Required for guaranteed introducers; ignored for owned and immortal introducers. */
        val anchorDependencies: Set<ArcSSAValue> = emptySet(),
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    data class Forward(
        val source: ArcSSAValue,
        val result: ArcSSAValue,
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    /** Produces a guaranteed projection whose complete dependency set is explicit. */
    data class Reborrow(
        val source: ArcSSAValue,
        val result: ArcSSAValue,
        val anchorDependencies: Set<ArcSSAValue>,
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    /** A phi-like definition emitted by an acyclic lowered `IrWhen` merge. */
    data class Join(
        val result: ArcSSAValue,
        val incoming: Map<ArcBlockId, ArcSSAValue>,
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    /** The owned value exists in [slot] only after this non-throwing normal-path operation. */
    data class InitializeOwned(
        val slot: ArcSSASlot,
        val version: ArcSSASlotVersion,
        val value: ArcSSAValue,
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    /** Permanent storage is tracked, but never contributes an owning RC alternative. */
    data class InitializeImmortal(
        val slot: ArcSSASlot,
        val version: ArcSSASlotVersion,
        val value: ArcSSAValue,
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    /**
     * Explicitly joins unequal path-conditional slot versions. [incoming] must name every incoming
     * normal edge and must correspond to one accepted path-disjoint ownership web.
     */
    data class JoinSlot(
        val slot: ArcSSASlot,
        val version: ArcSSASlotVersion,
        val value: ArcSSAValue,
        val incoming: Map<ArcSSAEdge, ArcSSASlotVersion>,
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    data class Borrow(
        val slot: ArcSSASlot,
        val version: ArcSSASlotVersion,
        val source: ArcSSAValue,
        val result: ArcSSAValue,
        val borrowId: ArcSSABorrowId,
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    data class EndBorrow(
        val borrowId: ArcSSABorrowId,
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    /** Consumes the sole active owning alternative in [sourceSlot] and creates [destinationVersion]. */
    data class MoveOwned(
        val sourceSlot: ArcSSASlot,
        val sourceVersion: ArcSSASlotVersion,
        val destinationSlot: ArcSSASlot,
        val destinationVersion: ArcSSASlotVersion,
        val value: ArcSSAValue,
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    /** Consumes exactly one active owning alternative and leaves the physical slot empty. */
    data class DestroyOwned(
        val slot: ArcSSASlot,
        val version: ArcSSASlotVersion,
        val value: ArcSSAValue,
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    data class Use(
        val value: ArcSSAValue,
        val kind: ArcSSAUseKind,
        /** The exceptional successor observes the pre-use ownership state. */
        val mayThrow: Boolean = false,
        override val location: ArcPlanLocation? = null,
    ) : ArcSSAOperation()

    /** A destroy may not be sunk across user deinitialization or runtime finalization. */
    data class DeinitBarrier(override val location: ArcPlanLocation? = null) : ArcSSAOperation()
}

internal data class ArcSSABlock(
    val id: ArcBlockId,
    val operations: List<ArcSSAOperation>,
)

/**
 * Frozen adapter boundary for the first ownership-SSA integration.
 *
 * [blocks] and [edges] must describe a finite CFG. Candidate discovery currently accepts only
 * canonical two-arm, acyclic `IrWhen` diamonds and deliberately rejects loop-carried or critical
 * join shapes.
 */
internal data class ArcOwnershipSSAInput(
    val entry: ArcBlockId,
    val blocks: Map<ArcBlockId, ArcSSABlock>,
    val edges: Set<ArcSSAEdge>,
)

internal sealed class ArcSSADestroyPlacement {
    data class BeforeOperation(val block: ArcBlockId, val operationIndex: Int) : ArcSSADestroyPlacement()
    data class BeforeExit(val block: ArcBlockId) : ArcSSADestroyPlacement()
    data class OnEdge(val edge: ArcSSAEdge) : ArcSSADestroyPlacement()
}

internal enum class ArcOwnershipSSARejectionReason {
    MALFORMED_CFG,
    NOT_ACYCLIC_WHEN_DIAMOND,
    INCOMPLETE_WEB,
    MIXED_INCOMING_OWNERSHIP,
    INCOMPATIBLE_ANCHOR_DEPENDENCIES,
    ANCHOR_DOES_NOT_DOMINATE_REBORROW,
    ESCAPE,
    UNKNOWN_CONSUME,
    MULTIPLE_CONSUME,
    CRITICAL_EDGE_DESTROY,
    INVALID_SSA_DOMINANCE,
    AMBIGUOUS_EXCEPTIONAL_CLEANUP,
    AMBIGUOUS_DESTROY_PLACEMENT,
    OVERLAPPING_WEB,
}

internal data class ArcOwnershipSSARejection(
    val join: ArcSSAValue,
    val reason: ArcOwnershipSSARejectionReason,
    val detail: String,
    val location: ArcPlanLocation? = null,
)

/** The immutable proof record which a later codegen integration is allowed to consume. */
internal data class ArcOwnershipSSAWeb(
    val join: ArcSSAValue,
    val mergeBlock: ArcBlockId,
    val ownership: ArcOwnership,
    val members: Set<ArcSSAValue>,
    /** Canonical introducer identities; equality is the guaranteed-lifetime join rule. */
    val anchorDependencies: Set<ArcSSAValue>,
    val destroyPlacements: Set<ArcSSADestroyPlacement>,
)

internal data class ArcOwnershipSSAResult(
    val accepted: List<ArcOwnershipSSAWeb>,
    val rejected: List<ArcOwnershipSSARejection>,
    val slotFlow: ArcSSASlotFlowResult,
)

internal sealed class ArcSSASlotRCIdentity {
    abstract val currentValue: ArcSSAValue

    data class Single(override val currentValue: ArcSSAValue) : ArcSSASlotRCIdentity()

    data class PathDisjoint(
        override val currentValue: ArcSSAValue,
        val ownershipWeb: ArcOwnershipSSAWeb,
        val alternatives: Map<ArcSSAEdge, ArcSSAValue>,
    ) : ArcSSASlotRCIdentity()
}

internal data class ArcSSASlotFact(
    val slot: ArcSSASlot,
    val version: ArcSSASlotVersion,
    val ownership: ArcOwnership,
    val identity: ArcSSASlotRCIdentity,
)

internal data class ArcSSAActiveBorrow(
    val id: ArcSSABorrowId,
    val anchorVersion: ArcSSASlotVersion,
    val result: ArcSSAValue,
)

internal data class ArcSSASlotState(
    val facts: Map<ArcSSASlot, ArcSSASlotFact> = emptyMap(),
    val activeBorrows: Map<ArcSSABorrowId, ArcSSAActiveBorrow> = emptyMap(),
    val destroyedOwnedIdentities: Set<ArcSSAValue> = emptySet(),
) {
    operator fun get(slot: ArcSSASlot): ArcSSASlotFact? = facts[slot]
}

internal enum class ArcSSASlotRejectionReason {
    MALFORMED_CFG,
    DUPLICATE_VERSION,
    DUPLICATE_BORROW_ID,
    BACKEDGE_STATE,
    MIXED_INITIALIZATION,
    MIXED_OWNERSHIP,
    TOKEN_REUSE,
    MISSING_EXPLICIT_JOIN,
    INVALID_PATH_DISJOINT_JOIN,
    SLOT_ALREADY_INITIALIZED,
    SLOT_NOT_INITIALIZED,
    OWNERSHIP_MISMATCH,
    VALUE_IDENTITY_MISMATCH,
    OWNED_IDENTITY_ALREADY_ACTIVE,
    DESTINATION_NOT_EMPTY,
    LIVE_BORROW,
    INVALID_BORROW_USE,
    UNKNOWN_BORROW,
    LIVE_BORROW_AT_JOIN,
    LIVE_BORROW_AT_EXIT,
    UNCONSUMED_OWNED_AT_EXIT,
    UNMODELED_EXCEPTIONAL_EDGE,
    AMBIGUOUS_EXCEPTIONAL_STATE,
    CRITICAL_EDGE_CLEANUP,
    INVALID_SLOT_OPERATION,
}

internal data class ArcSSASlotRejection(
    val reason: ArcSSASlotRejectionReason,
    val detail: String,
    val block: ArcBlockId? = null,
    val operationIndex: Int? = null,
    val edge: ArcSSAEdge? = null,
    val location: ArcPlanLocation? = null,
)

internal data class ArcSSASlotFlowResult(
    val blockEntryStates: Map<ArcBlockId, ArcSSASlotState>,
    val blockExitStates: Map<ArcBlockId, ArcSSASlotState>,
    val normalEdgeStates: Map<ArcSSAEdge, ArcSSASlotState>,
    val exceptionalEdgeStates: Map<ArcSSAEdge, ArcSSASlotState>,
    val rejections: List<ArcSSASlotRejection>,
) {
    val verified: Boolean get() = rejections.isEmpty()

    companion object {
        val Empty = ArcSSASlotFlowResult(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList())
    }
}

internal object ArcOwnershipSSAAnalysis {
    private data class Definition(val block: ArcBlockId, val index: Int, val operation: ArcSSAOperation)
    private data class Diamond(
        val header: ArcBlockId,
        val trueBlock: ArcBlockId,
        val falseBlock: ArcBlockId,
        val mergeBlock: ArcBlockId,
    )

    fun analyze(input: ArcOwnershipSSAInput): ArcOwnershipSSAResult {
        val definitions = linkedMapOf<ArcSSAValue, Definition>()
        val duplicateDefinitions = mutableSetOf<ArcSSAValue>()
        input.blocks.values.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                operation.resultOrNull()?.let { result ->
                    if (definitions.put(result, Definition(block.id, index, operation)) != null) {
                        duplicateDefinitions += result
                    }
                }
            }
        }

        val joins = definitions.values.mapNotNull { definition ->
            (definition.operation as? ArcSSAOperation.Join)?.let { it to definition }
        }
        val rejected = mutableListOf<ArcOwnershipSSARejection>()
        val accepted = mutableListOf<ArcOwnershipSSAWeb>()
        val malformed = input.entry !in input.blocks || input.edges.any { it.from !in input.blocks || it.to !in input.blocks }

        joins.forEach { (join, definition) ->
            if (malformed || join.result in duplicateDefinitions) {
                rejected += rejection(join, ArcOwnershipSSARejectionReason.MALFORMED_CFG, "missing block or duplicate SSA definition")
                return@forEach
            }
            val diamond = discoverDiamond(input, definition.block, join)
            if (diamond == null) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.NOT_ACYCLIC_WHEN_DIAMOND,
                    "join is not the merge of one canonical two-arm acyclic IrWhen diamond",
                )
                return@forEach
            }

            val members = discoverCompleteWeb(join.result, definitions, input.blocks)
            if (members == null) {
                rejected += rejection(join, ArcOwnershipSSARejectionReason.INCOMPLETE_WEB, "web contains an undefined or cyclic forwarding value")
                return@forEach
            }

            if (members.any { it in duplicateDefinitions }) {
                rejected += rejection(join, ArcOwnershipSSARejectionReason.MALFORMED_CFG, "web contains a duplicate SSA definition")
                return@forEach
            }

            val dominators = computeDominators(input)
            if (!hasValidDominance(input, members, definitions, dominators)) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.INVALID_SSA_DOMINANCE,
                    "a web definition does not dominate one of its uses or incoming edges",
                )
                return@forEach
            }

            val incomingOwnerships = join.incoming.values.map { inferOwnership(it, definitions, mutableSetOf()) }
            val ownerships = incomingOwnerships.filterNotNull().toSet()
            if (incomingOwnerships.any { it == null } || ownerships.size != 1) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.MIXED_INCOMING_OWNERSHIP,
                    "incoming ownerships are ${if (ownerships.isEmpty()) "unknown" else ownerships.joinToString()}",
                )
                return@forEach
            }
            val ownership = ownerships.single()
            val dependencySets = join.incoming.values.map { canonicalDependencies(it, definitions, mutableSetOf()) }
            if (dependencySets.any { it == null } || dependencySets.filterNotNull().distinct().size != 1) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.INCOMPATIBLE_ANCHOR_DEPENDENCIES,
                    "incoming guaranteed values do not have one canonical anchor dependency set",
                )
                return@forEach
            }
            val dependencies = dependencySets.firstOrNull().orEmpty()
            if (ownership == ArcOwnership.Guaranteed && dependencies.isEmpty()) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.INCOMPATIBLE_ANCHOR_DEPENDENCIES,
                    "guaranteed web has no lifetime anchor",
                )
                return@forEach
            }

            val invalidReborrow = members.asSequence()
                .mapNotNull { definitions[it] }
                .mapNotNull { definitionOfMember ->
                    (definitionOfMember.operation as? ArcSSAOperation.Reborrow)?.let { definitionOfMember to it }
                }
                .firstOrNull { (reborrowDefinition, reborrow) ->
                    reborrow.anchorDependencies.any { anchor ->
                        val anchorDefinition = definitions[canonicalAnchor(anchor, definitions, mutableSetOf())]
                        anchorDefinition == null || anchorDefinition.block !in dominators.getValue(reborrowDefinition.block) ||
                                (anchorDefinition.block == reborrowDefinition.block &&
                                        anchorDefinition.index >= reborrowDefinition.index)
                    }
                }
            if (invalidReborrow != null) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.ANCHOR_DOES_NOT_DOMINATE_REBORROW,
                    "a reborrow anchor does not dominate ${invalidReborrow.first.block}",
                    invalidReborrow.second.location,
                )
                return@forEach
            }

            val uses = classifiedUses(input, members)
            val forbiddenUse = uses.firstOrNull { it.third.kind == ArcSSAUseKind.Escape || it.third.kind == ArcSSAUseKind.UnknownConsume }
            if (forbiddenUse != null) {
                val reason = if (forbiddenUse.third.kind == ArcSSAUseKind.Escape) {
                    ArcOwnershipSSARejectionReason.ESCAPE
                } else {
                    ArcOwnershipSSARejectionReason.UNKNOWN_CONSUME
                }
                rejected += rejection(join, reason, "${forbiddenUse.third.kind} of ${forbiddenUse.third.value}", forbiddenUse.third.location)
                return@forEach
            }
            if (uses.count { it.third.kind == ArcSSAUseKind.Consume } > 1) {
                rejected += rejection(join, ArcOwnershipSSARejectionReason.MULTIPLE_CONSUME, "web has more than one consuming use")
                return@forEach
            }

            if (!hasUnambiguousExceptionalCleanup(input, members, uses)) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.AMBIGUOUS_EXCEPTIONAL_CLEANUP,
                    "throwing use is not uniquely associated with one dead-on-entry exceptional edge",
                )
                return@forEach
            }

            val placements = if (ownership == ArcOwnership.Owned) {
                computeDestroyPlacements(input, diamond, members, uses)
            } else emptySet()
            if (placements == null) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.AMBIGUOUS_DESTROY_PLACEMENT,
                    "no single destroy placement covers every normal path exactly once",
                )
                return@forEach
            }
            val critical = placements.filterIsInstance<ArcSSADestroyPlacement.OnEdge>()
                .firstOrNull { isCritical(it.edge, input) }
            if (critical != null) {
                rejected += rejection(
                    join,
                    ArcOwnershipSSARejectionReason.CRITICAL_EDGE_DESTROY,
                    "destroy requires unsplit critical edge ${critical.edge.from} -> ${critical.edge.to}",
                )
                return@forEach
            }

            accepted += ArcOwnershipSSAWeb(
                join = join.result,
                mergeBlock = diamond.mergeBlock,
                ownership = ownership,
                members = members,
                anchorDependencies = dependencies,
                destroyPlacements = placements,
            )
        }
        val overlapping = accepted.filter { candidate ->
            accepted.any { other -> other !== candidate && candidate.members.any { it in other.members } }
        }
        if (overlapping.isNotEmpty()) {
            accepted.removeAll(overlapping.toSet())
            overlapping.forEach { web ->
                rejected += ArcOwnershipSSARejection(
                    web.join,
                    ArcOwnershipSSARejectionReason.OVERLAPPING_WEB,
                    "ownership web overlaps another accepted join proof",
                )
            }
        }
        return ArcOwnershipSSAResult(
            accepted,
            rejected,
            ArcSSASlotFlowAnalysis.analyze(input, accepted),
        )
    }

    private fun discoverDiamond(input: ArcOwnershipSSAInput, merge: ArcBlockId, join: ArcSSAOperation.Join): Diamond? {
        if (join.incoming.size != 2 || join.incoming.keys.toSet() != input.predecessors(merge, ArcSSAEdgeKind.Normal).toSet()) return null
        val arms = join.incoming.keys.toList()
        if (arms.any { input.successors(it, ArcSSAEdgeKind.Normal) != listOf(merge) }) return null
        val commonHeaders = input.predecessors(arms[0], ArcSSAEdgeKind.Normal).toSet()
            .intersect(input.predecessors(arms[1], ArcSSAEdgeKind.Normal).toSet())
        val header = commonHeaders.singleOrNull() ?: return null
        if (input.successors(header, ArcSSAEdgeKind.Normal).toSet() != arms.toSet()) return null
        if (!isAcyclic(input)) return null
        return Diamond(header, arms[0], arms[1], merge)
    }

    /** Backward and forward closure is what prevents partial phi-web conversion. */
    private fun discoverCompleteWeb(
        root: ArcSSAValue,
        definitions: Map<ArcSSAValue, Definition>,
        blocks: Map<ArcBlockId, ArcSSABlock>,
    ): Set<ArcSSAValue>? {
        val members = linkedSetOf(root)
        var changed = true
        while (changed) {
            changed = false
            for (value in members.toList()) {
                val operation = definitions[value]?.operation ?: return null
                val dependencies = when (operation) {
                    is ArcSSAOperation.Introduce -> emptyList()
                    is ArcSSAOperation.Forward -> listOf(operation.source)
                    // A reborrow introduces a new guaranteed value. Its source and anchors are
                    // lifetime dependencies, not ownership-equivalent members of this phi web.
                    is ArcSSAOperation.Reborrow -> emptyList()
                    is ArcSSAOperation.Borrow -> emptyList()
                    is ArcSSAOperation.Join -> operation.incoming.values.toList()
                    else -> return null
                }
                dependencies.forEach { if (members.add(it)) changed = true }
            }
            blocks.values.forEach { block ->
                block.operations.forEach { operation ->
                    when (operation) {
                        is ArcSSAOperation.Forward -> if (operation.source in members && members.add(operation.result)) changed = true
                        is ArcSSAOperation.Reborrow, is ArcSSAOperation.Borrow -> Unit
                        is ArcSSAOperation.Join -> if (operation.incoming.values.any { it in members }) {
                            if (members.add(operation.result)) changed = true
                            operation.incoming.values.forEach { if (members.add(it)) changed = true }
                        }
                        else -> Unit
                    }
                }
            }
        }
        return members
    }

    private fun inferOwnership(
        value: ArcSSAValue,
        definitions: Map<ArcSSAValue, Definition>,
        visiting: MutableSet<ArcSSAValue>,
    ): ArcOwnership? {
        if (!visiting.add(value)) return null
        val result = when (val operation = definitions[value]?.operation) {
            is ArcSSAOperation.Introduce -> operation.ownership
            is ArcSSAOperation.Forward -> inferOwnership(operation.source, definitions, visiting)
            is ArcSSAOperation.Reborrow, is ArcSSAOperation.Borrow -> ArcOwnership.Guaranteed
            is ArcSSAOperation.Join -> operation.incoming.values.mapNotNull { inferOwnership(it, definitions, visiting.toMutableSet()) }
                .toSet().singleOrNull()
            else -> null
        }
        visiting.remove(value)
        return result
    }

    private fun canonicalDependencies(
        value: ArcSSAValue,
        definitions: Map<ArcSSAValue, Definition>,
        visiting: MutableSet<ArcSSAValue>,
    ): Set<ArcSSAValue>? {
        if (!visiting.add(value)) return null
        val result = when (val operation = definitions[value]?.operation) {
            is ArcSSAOperation.Introduce -> if (operation.ownership == ArcOwnership.Guaranteed) {
                operation.anchorDependencies.mapTo(linkedSetOf()) { canonicalAnchor(it, definitions, mutableSetOf()) }
            } else emptySet()
            is ArcSSAOperation.Forward -> canonicalDependencies(operation.source, definitions, visiting)
            is ArcSSAOperation.Reborrow -> operation.anchorDependencies.mapTo(linkedSetOf()) {
                canonicalAnchor(it, definitions, mutableSetOf())
            }
            is ArcSSAOperation.Borrow -> setOf(canonicalAnchor(operation.source, definitions, mutableSetOf()))
            is ArcSSAOperation.Join -> operation.incoming.values.map { canonicalDependencies(it, definitions, visiting.toMutableSet()) }
                .takeIf { sets -> sets.none { it == null } && sets.filterNotNull().distinct().size == 1 }
                ?.firstOrNull()
            else -> null
        }
        visiting.remove(value)
        return result
    }

    private fun canonicalAnchor(
        value: ArcSSAValue,
        definitions: Map<ArcSSAValue, Definition>,
        visiting: MutableSet<ArcSSAValue>,
    ): ArcSSAValue {
        if (!visiting.add(value)) return value
        return when (val operation = definitions[value]?.operation) {
            is ArcSSAOperation.Forward -> canonicalAnchor(operation.source, definitions, visiting)
            else -> value
        }
    }

    private fun classifiedUses(
        input: ArcOwnershipSSAInput,
        members: Set<ArcSSAValue>,
    ): List<Triple<ArcBlockId, Int, ArcSSAOperation.Use>> = buildList {
        input.blocks.values.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                if (operation is ArcSSAOperation.Use && operation.value in members) add(Triple(block.id, index, operation))
            }
        }
    }

    private fun computeDestroyPlacements(
        input: ArcOwnershipSSAInput,
        diamond: Diamond,
        members: Set<ArcSSAValue>,
        uses: List<Triple<ArcBlockId, Int, ArcSSAOperation.Use>>,
    ): Set<ArcSSADestroyPlacement>? {
        val result = linkedSetOf<ArcSSADestroyPlacement>()
        val postMergeReachable = reachableFrom(diamond.mergeBlock, input, normalOnly = true)
        val consume = uses.singleOrNull { it.third.kind == ArcSSAUseKind.Consume }

        // A throwing use owns the value only on its normal continuation. Unwind gets an explicit
        // edge cleanup, including for a consuming call whose callee never took ownership.
        uses.filter { it.third.mayThrow }.forEach { (block, _, _) ->
            input.edges.filter { it.from == block && it.kind == ArcSSAEdgeKind.Exceptional }
                .forEach { result += ArcSSADestroyPlacement.OnEdge(it) }
        }

        if (consume == null) {
            // Pruned liveness: if a deinit barrier is beyond the last real use, end lifetime before
            // it rather than extending an ARC value across observable destruction.
            val useBlocks = uses.mapTo(mutableSetOf()) { it.first }
            val futureUseBlocks = reverseReachable(useBlocks, input)
            val barrierPlacements = linkedMapOf<ArcBlockId, ArcSSADestroyPlacement.BeforeOperation>()
            postMergeReachable.forEach { blockId ->
                val block = input.blocks.getValue(blockId)
                val lastLocalUse = block.operations.indexOfLast { it is ArcSSAOperation.Use && it.value in members }
                val barrier = block.operations.withIndex().firstOrNull { (index, operation) ->
                    operation is ArcSSAOperation.DeinitBarrier && index > lastLocalUse &&
                            input.successors(blockId, ArcSSAEdgeKind.Normal).none { it in futureUseBlocks }
                }
                if (barrier != null) {
                    barrierPlacements[blockId] = ArcSSADestroyPlacement.BeforeOperation(blockId, barrier.index)
                }
            }

            result += barrierPlacements.values
            val pathCounts = linkedMapOf<ArcBlockId, MutableSet<Int>>()
            pathCounts.getOrPut(diamond.mergeBlock) { linkedSetOf() } += 0
            val worklist = ArrayDeque<ArcBlockId>().apply { add(diamond.mergeBlock) }
            while (worklist.isNotEmpty()) {
                val block = worklist.removeFirst()
                val outgoingCounts = pathCounts.getValue(block).mapTo(linkedSetOf()) {
                    (it + if (block in barrierPlacements) 1 else 0).coerceAtMost(2)
                }
                input.successors(block, ArcSSAEdgeKind.Normal).forEach { successor ->
                    val successorCounts = pathCounts.getOrPut(successor) { linkedSetOf() }
                    if (successorCounts.addAll(outgoingCounts)) worklist += successor
                }
            }
            postMergeReachable.filter { input.successors(it, ArcSSAEdgeKind.Normal).isEmpty() }.forEach { exit ->
                val counts = pathCounts[exit].orEmpty().mapTo(linkedSetOf()) {
                    it + if (exit in barrierPlacements) 1 else 0
                }
                when (counts) {
                    setOf(0) -> result += ArcSSADestroyPlacement.BeforeExit(exit)
                    setOf(1) -> Unit
                    else -> return null
                }
            }
        }
        return result
    }

    private fun hasValidDominance(
        input: ArcOwnershipSSAInput,
        members: Set<ArcSSAValue>,
        definitions: Map<ArcSSAValue, Definition>,
        dominators: Map<ArcBlockId, Set<ArcBlockId>>,
    ): Boolean {
        fun dominates(value: ArcSSAValue, block: ArcBlockId, index: Int): Boolean {
            val definition = definitions[value] ?: return false
            return definition.block in dominators.getValue(block) &&
                    (definition.block != block || definition.index < index)
        }
        if (members.any { value ->
                val block = definitions[value]?.block ?: return false
                input.entry !in dominators.getValue(block)
            }) return false
        return input.blocks.values.all { block ->
            block.operations.withIndex().all { (index, operation) ->
                when (operation) {
                    is ArcSSAOperation.Forward -> operation.source !in members || dominates(operation.source, block.id, index)
                    is ArcSSAOperation.Reborrow -> operation.result !in members ||
                            dominates(operation.source, block.id, index)
                    is ArcSSAOperation.Borrow -> operation.result !in members ||
                            dominates(operation.source, block.id, index)
                    is ArcSSAOperation.Join -> operation.incoming.all { (predecessor, value) ->
                        value !in members || dominates(value, predecessor, input.blocks.getValue(predecessor).operations.size)
                    }
                    is ArcSSAOperation.Use -> operation.value !in members || dominates(operation.value, block.id, index)
                    is ArcSSAOperation.Introduce -> operation.result !in members ||
                            operation.anchorDependencies.all { dominates(it, block.id, index) }
                    is ArcSSAOperation.InitializeOwned -> dominates(operation.value, block.id, index)
                    is ArcSSAOperation.InitializeImmortal -> dominates(operation.value, block.id, index)
                    is ArcSSAOperation.JoinSlot -> dominates(operation.value, block.id, index)
                    is ArcSSAOperation.MoveOwned -> dominates(operation.value, block.id, index)
                    is ArcSSAOperation.DestroyOwned -> dominates(operation.value, block.id, index)
                    is ArcSSAOperation.EndBorrow, is ArcSSAOperation.DeinitBarrier -> true
                }
            }
        }
    }

    private fun hasUnambiguousExceptionalCleanup(
        input: ArcOwnershipSSAInput,
        members: Set<ArcSSAValue>,
        uses: List<Triple<ArcBlockId, Int, ArcSSAOperation.Use>>,
    ): Boolean = uses.filter { it.third.mayThrow }.all { (blockId, operationIndex, _) ->
        val block = input.blocks.getValue(blockId)
        val throwingOperations = block.operations.withIndex().mapNotNull { (index, operation) ->
            (operation as? ArcSSAOperation.Use)?.takeIf { it.mayThrow }?.let { index to it }
        }
        val exceptionalEdges = input.edges.filter { it.from == blockId && it.kind == ArcSSAEdgeKind.Exceptional }
        throwingOperations.singleOrNull()?.first == operationIndex && exceptionalEdges.size == 1 &&
                reachableFrom(exceptionalEdges.single().to, input, normalOnly = false).none { reachable ->
                    input.blocks.getValue(reachable).operations.any { it is ArcSSAOperation.Use && it.value in members }
                }
    }

    private fun computeDominators(input: ArcOwnershipSSAInput): Map<ArcBlockId, Set<ArcBlockId>> {
        val all = input.blocks.keys
        val result = all.associateWithTo(linkedMapOf()) { if (it == input.entry) setOf(it) else all.toSet() }
        var changed = true
        while (changed) {
            changed = false
            all.filter { it != input.entry }.forEach { block ->
                val predecessors = input.predecessors(block, null)
                val newValue = if (predecessors.isEmpty()) setOf(block) else {
                    predecessors.map { result.getValue(it) }.reduce { left, right -> left intersect right } + block
                }
                if (newValue != result[block]) {
                    result[block] = newValue
                    changed = true
                }
            }
        }
        return result
    }

    private fun isAcyclic(input: ArcOwnershipSSAInput): Boolean {
        val color = mutableMapOf<ArcBlockId, Int>()
        fun visit(block: ArcBlockId): Boolean {
            when (color[block]) {
                1 -> return false
                2 -> return true
            }
            color[block] = 1
            if (input.successors(block, null).any { !visit(it) }) return false
            color[block] = 2
            return true
        }
        return input.blocks.keys.all { visit(it) }
    }

    private fun isCritical(edge: ArcSSAEdge, input: ArcOwnershipSSAInput): Boolean =
        input.successors(edge.from, null).size > 1 && input.predecessors(edge.to, null).size > 1

    private fun reachableFrom(start: ArcBlockId, input: ArcOwnershipSSAInput, normalOnly: Boolean): Set<ArcBlockId> {
        val result = linkedSetOf<ArcBlockId>()
        val worklist = ArrayDeque<ArcBlockId>().apply { add(start) }
        while (worklist.isNotEmpty()) {
            val block = worklist.removeFirst()
            if (!result.add(block)) continue
            input.successors(block, if (normalOnly) ArcSSAEdgeKind.Normal else null).forEach { worklist += it }
        }
        return result
    }

    private fun reverseReachable(starts: Set<ArcBlockId>, input: ArcOwnershipSSAInput): Set<ArcBlockId> {
        val result = linkedSetOf<ArcBlockId>()
        val worklist = ArrayDeque<ArcBlockId>().apply { addAll(starts) }
        while (worklist.isNotEmpty()) {
            val block = worklist.removeFirst()
            if (!result.add(block)) continue
            input.predecessors(block, ArcSSAEdgeKind.Normal).forEach { worklist += it }
        }
        return result
    }

    private fun ArcOwnershipSSAInput.successors(block: ArcBlockId, kind: ArcSSAEdgeKind?): List<ArcBlockId> =
        edges.asSequence().filter { it.from == block && (kind == null || it.kind == kind) }.map { it.to }.distinct().toList()

    private fun ArcOwnershipSSAInput.predecessors(block: ArcBlockId, kind: ArcSSAEdgeKind?): List<ArcBlockId> =
        edges.asSequence().filter { it.to == block && (kind == null || it.kind == kind) }.map { it.from }.distinct().toList()

    private fun ArcSSAOperation.resultOrNull(): ArcSSAValue? = when (this) {
        is ArcSSAOperation.Introduce -> result
        is ArcSSAOperation.Forward -> result
        is ArcSSAOperation.Reborrow -> result
        is ArcSSAOperation.Join -> result
        is ArcSSAOperation.Borrow -> result
        is ArcSSAOperation.Use,
        is ArcSSAOperation.InitializeOwned,
        is ArcSSAOperation.InitializeImmortal,
        is ArcSSAOperation.JoinSlot,
        is ArcSSAOperation.EndBorrow,
        is ArcSSAOperation.MoveOwned,
        is ArcSSAOperation.DestroyOwned,
        is ArcSSAOperation.DeinitBarrier -> null
    }

    private fun rejection(
        join: ArcSSAOperation.Join,
        reason: ArcOwnershipSSARejectionReason,
        detail: String,
        location: ArcPlanLocation? = join.location,
    ) = ArcOwnershipSSARejection(join.result, reason, detail, location)
}

/**
 * Forward slot-state verifier for the ownership facts that codegen will eventually consume.
 *
 * This deliberately rejects loops, implicit joins, token reuse, and ambiguous unwind state. It
 * proves only an acyclic, path-conditional slice; widening that slice requires new operations and
 * tests rather than weakening one of these checks.
 */
internal object ArcSSASlotFlowAnalysis {
    private data class GlobalValidationFailure(
        val detail: String,
        val block: ArcBlockId? = null,
        val operationIndex: Int? = null,
        val location: ArcPlanLocation? = null,
    )

    private data class MergeResult(
        val state: ArcSSASlotState,
        val appliedJoinSlots: Set<Int>,
    )

    fun analyze(input: ArcOwnershipSSAInput, ownershipWebs: List<ArcOwnershipSSAWeb>): ArcSSASlotFlowResult {
        if (input.blocks.values.none { block -> block.operations.any { it.isSlotOperation() } }) {
            return ArcSSASlotFlowResult.Empty
        }
        val rejections = mutableListOf<ArcSSASlotRejection>()
        val entryStates = linkedMapOf<ArcBlockId, ArcSSASlotState>()
        val exitStates = linkedMapOf<ArcBlockId, ArcSSASlotState>()
        val normalEdges = linkedMapOf<ArcSSAEdge, ArcSSASlotState>()
        val exceptionalEdges = linkedMapOf<ArcSSAEdge, ArcSSASlotState>()

        fun reject(
            reason: ArcSSASlotRejectionReason,
            detail: String,
            block: ArcBlockId? = null,
            operationIndex: Int? = null,
            edge: ArcSSAEdge? = null,
            location: ArcPlanLocation? = null,
        ): ArcSSASlotFlowResult {
            rejections += ArcSSASlotRejection(reason, detail, block, operationIndex, edge, location)
            return ArcSSASlotFlowResult(entryStates, exitStates, normalEdges, exceptionalEdges, rejections)
        }

        if (input.entry !in input.blocks || input.edges.any { it.from !in input.blocks || it.to !in input.blocks }) {
            return reject(ArcSSASlotRejectionReason.MALFORMED_CFG, "slot CFG references a missing entry or edge block")
        }
        validateGlobalSSA(input)?.let { failure ->
            return reject(
                ArcSSASlotRejectionReason.MALFORMED_CFG,
                failure.detail,
                failure.block,
                failure.operationIndex,
                location = failure.location,
            )
        }

        val versionDeclarations = linkedMapOf<ArcSSASlotVersion, Pair<ArcBlockId, Int>>()
        val borrowDeclarations = linkedMapOf<ArcSSABorrowId, Pair<ArcBlockId, Int>>()
        input.blocks.values.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                operation.declaredVersion()?.let { version ->
                    val previous = versionDeclarations.put(version, block.id to index)
                    if (previous != null) {
                        return reject(
                            ArcSSASlotRejectionReason.DUPLICATE_VERSION,
                            "$version is declared at both $previous and ${block.id to index}",
                            block.id,
                            index,
                            location = operation.location,
                        )
                    }
                }
                (operation as? ArcSSAOperation.Borrow)?.borrowId?.let { borrowId ->
                    val previous = borrowDeclarations.put(borrowId, block.id to index)
                    if (previous != null) {
                        return reject(
                            ArcSSASlotRejectionReason.DUPLICATE_BORROW_ID,
                            "$borrowId is declared at both $previous and ${block.id to index}",
                            block.id,
                            index,
                            location = operation.location,
                        )
                    }
                }
            }
        }

        val reachable = reachableFrom(input.entry, input)
        val indegree = reachable.associateWithTo(linkedMapOf()) { block ->
            input.edges.count { it.to == block && it.from in reachable }
        }
        val ready = ArrayDeque<ArcBlockId>().apply {
            indegree.filterValues { it == 0 }.keys.forEach(::add)
        }
        val order = mutableListOf<ArcBlockId>()
        while (ready.isNotEmpty()) {
            val block = ready.removeFirst()
            order += block
            input.edges.filter { it.from == block && it.to in reachable }.forEach { edge ->
                val remaining = indegree.getValue(edge.to) - 1
                indegree[edge.to] = remaining
                if (remaining == 0) ready += edge.to
            }
        }
        if (order.size != reachable.size) {
            return reject(
                ArcSSASlotRejectionReason.BACKEDGE_STATE,
                "slot facts do not cross loops or coroutine backedges until ownership-phi cleanup is modeled",
            )
        }
        val borrowDependencies = computeBorrowDependencies(input)
        val identityResult = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(input, ownershipWebs))
        val fatalIdentityIssues = identityResult.issues.filterNot { issue ->
            (issue.kind == ArcRCIdentityIssueKind.UnsupportedEscape ||
                    issue.kind == ArcRCIdentityIssueKind.UnsupportedUnknownConsume) &&
                    borrowDependencies[issue.value].orEmpty().isNotEmpty()
        }
        if (fatalIdentityIssues.isNotEmpty()) {
            return reject(
                ArcSSASlotRejectionReason.VALUE_IDENTITY_MISMATCH,
                "slot CFG contains unresolved RC identities: ${fatalIdentityIssues.joinToString { it.detail }}",
            )
        }

        for (blockId in order) {
            val block = input.blocks.getValue(blockId)
            val incoming = input.edges.filter { it.to == blockId }.associateWith { edge ->
                (if (edge.kind == ArcSSAEdgeKind.Exceptional) exceptionalEdges else normalEdges)[edge]
            }
            if (incoming.values.any { it == null }) {
                return reject(
                    ArcSSASlotRejectionReason.MALFORMED_CFG,
                    "an incoming edge has no verified predecessor state",
                    blockId,
                )
            }
            val merge = if (blockId == input.entry) {
                if (incoming.isNotEmpty()) {
                    return reject(ArcSSASlotRejectionReason.BACKEDGE_STATE, "entry has an incoming slot-state edge", blockId)
                }
                MergeResult(ArcSSASlotState(), emptySet())
            } else {
                mergeIncoming(
                    block,
                    incoming.mapValues { it.value!! },
                    ownershipWebs,
                    identityResult,
                ) ?: return reject(
                    mergeFailureReason(input, incoming.mapValues { it.value!! }),
                    mergeFailureDetail(block, incoming.mapValues { it.value!! }),
                    blockId,
                )
            }
            entryStates[blockId] = merge.state

            var facts = merge.state.facts.toMutableMap()
            var borrows = merge.state.activeBorrows.toMutableMap()
            var destroyed = merge.state.destroyedOwnedIdentities.toMutableSet()
            var exceptionalState: ArcSSASlotState? = null
            var throwingOperations = 0

            fun currentState() = ArcSSASlotState(facts.toMap(), borrows.toMap(), destroyed.toSet())
            fun factMatches(fact: ArcSSASlotFact, value: ArcSSAValue): Boolean = when (val identity = fact.identity) {
                is ArcSSASlotRCIdentity.Single -> sameIdentity(identity.currentValue, value, identityResult)
                is ArcSSASlotRCIdentity.PathDisjoint ->
                    identity.ownershipWeb === identityResult.identity(value)?.ownershipWeb &&
                            sameIdentity(identity.currentValue, value, identityResult)
            }
            fun hasActiveOwnedIdentity(value: ArcSSAValue): Boolean = facts.values.any { fact ->
                fact.ownership == ArcOwnership.Owned && identitiesOverlap(fact.identity.currentValue, value, identityResult)
            }

            block.operations.forEachIndexed { index, operation ->
                val unavailableDependencyBorrows = operation.dependenciesForBorrowValidation()
                    .flatMapTo(linkedSetOf()) { borrowDependencies[it].orEmpty() }
                    .filter { it !in borrows }
                if (unavailableDependencyBorrows.isNotEmpty()) {
                    return reject(
                        ArcSSASlotRejectionReason.INVALID_BORROW_USE,
                        "operation depends on ended or unavailable borrows $unavailableDependencyBorrows",
                        blockId, index, location = operation.location,
                    )
                }
                operation.resultOrNullForSlotFlow()?.let { result ->
                    val requiredBorrows = borrowDependencies[result].orEmpty()
                    val newlyDeclaredBorrow = (operation as? ArcSSAOperation.Borrow)?.borrowId
                    val unavailable = requiredBorrows.filter { it != newlyDeclaredBorrow && it !in borrows }
                    if (unavailable.isNotEmpty()) {
                        return reject(
                            ArcSSASlotRejectionReason.INVALID_BORROW_USE,
                            "alias $result depends on ended or unavailable borrows $unavailable",
                            blockId, index, location = operation.location,
                        )
                    }
                }
                if (operation is ArcSSAOperation.Use) {
                    val requiredBorrows = borrowDependencies[operation.value].orEmpty()
                    if (requiredBorrows.isNotEmpty() &&
                        (operation.kind != ArcSSAUseKind.Borrow || requiredBorrows.any { it !in borrows })
                    ) {
                        return reject(
                            ArcSSASlotRejectionReason.INVALID_BORROW_USE,
                            "borrow-dependent value ${operation.value} requires active borrows $requiredBorrows and Borrow effect",
                            blockId, index, location = operation.location,
                        )
                    }
                }
                if (operation is ArcSSAOperation.Use && destroyed.any {
                        identitiesOverlap(it, operation.value, identityResult)
                    }
                ) return reject(
                    ArcSSASlotRejectionReason.INVALID_SLOT_OPERATION,
                    "${operation.value} is used after its sole owning alternative was destroyed",
                    blockId, index, location = operation.location,
                )
                if (operation is ArcSSAOperation.Use && operation.mayThrow) {
                    val exceptionalSuccessors = input.edges.filter {
                        it.from == blockId && it.kind == ArcSSAEdgeKind.Exceptional
                    }
                    if ((facts.values.any { it.ownership == ArcOwnership.Owned } || borrows.isNotEmpty()) &&
                        exceptionalSuccessors.size != 1
                    ) return reject(
                        ArcSSASlotRejectionReason.UNMODELED_EXCEPTIONAL_EDGE,
                        "throwing operation with live ownership requires exactly one modeled exceptional edge",
                        blockId, index, location = operation.location,
                    )
                    throwingOperations++
                    exceptionalState = currentState()
                }
                when (operation) {
                    is ArcSSAOperation.InitializeOwned -> {
                        if (facts.containsKey(operation.slot)) return reject(
                            ArcSSASlotRejectionReason.SLOT_ALREADY_INITIALIZED,
                            "${operation.slot} already owns ${facts.getValue(operation.slot).identity.currentValue}",
                            blockId, index, location = operation.location,
                        )
                        if (identityResult.ownershipOf(operation.value, input) != ArcOwnership.Owned) return reject(
                            ArcSSASlotRejectionReason.OWNERSHIP_MISMATCH,
                            "${operation.value} is not an owned producer",
                            blockId, index, location = operation.location,
                        )
                        if (hasActiveOwnedIdentity(operation.value)) return reject(
                            ArcSSASlotRejectionReason.OWNED_IDENTITY_ALREADY_ACTIVE,
                            "${operation.value} already has an active owning slot alternative",
                            blockId, index, location = operation.location,
                        )
                        if (destroyed.any { identitiesOverlap(it, operation.value, identityResult) }) return reject(
                            ArcSSASlotRejectionReason.INVALID_SLOT_OPERATION,
                            "destroyed identity ${operation.value} cannot be initialized again",
                            blockId, index, location = operation.location,
                        )
                        facts[operation.slot] = ArcSSASlotFact(
                            operation.slot, operation.version, ArcOwnership.Owned,
                            ArcSSASlotRCIdentity.Single(operation.value),
                        )
                    }
                    is ArcSSAOperation.InitializeImmortal -> {
                        if (facts.containsKey(operation.slot)) return reject(
                            ArcSSASlotRejectionReason.SLOT_ALREADY_INITIALIZED,
                            "${operation.slot} is already initialized",
                            blockId, index, location = operation.location,
                        )
                        if (identityResult.ownershipOf(operation.value, input) != ArcOwnership.Immortal) return reject(
                            ArcSSASlotRejectionReason.OWNERSHIP_MISMATCH,
                            "${operation.value} is not immortal",
                            blockId, index, location = operation.location,
                        )
                        facts[operation.slot] = ArcSSASlotFact(
                            operation.slot, operation.version, ArcOwnership.Immortal,
                            ArcSSASlotRCIdentity.Single(operation.value),
                        )
                    }
                    is ArcSSAOperation.JoinSlot -> if (index !in merge.appliedJoinSlots) return reject(
                        ArcSSASlotRejectionReason.MISSING_EXPLICIT_JOIN,
                        "JoinSlot does not correspond to unequal incoming facts",
                        blockId, index, location = operation.location,
                    )
                    is ArcSSAOperation.Borrow -> {
                        val fact = facts[operation.slot] ?: return reject(
                            ArcSSASlotRejectionReason.SLOT_NOT_INITIALIZED,
                            "cannot borrow from empty ${operation.slot}",
                            blockId, index, location = operation.location,
                        )
                        if (fact.version != operation.version || !factMatches(fact, operation.source)) return reject(
                            ArcSSASlotRejectionReason.VALUE_IDENTITY_MISMATCH,
                            "borrow source/version does not match the active slot fact",
                            blockId, index, location = operation.location,
                        )
                        borrows[operation.borrowId] = ArcSSAActiveBorrow(
                            operation.borrowId, fact.version, operation.result,
                        )
                    }
                    is ArcSSAOperation.EndBorrow -> {
                        if (borrows.remove(operation.borrowId) == null) return reject(
                            ArcSSASlotRejectionReason.UNKNOWN_BORROW,
                            "${operation.borrowId} is not active",
                            blockId, index, location = operation.location,
                        )
                    }
                    is ArcSSAOperation.MoveOwned -> {
                        if (operation.sourceSlot == operation.destinationSlot || facts.containsKey(operation.destinationSlot)) {
                            return reject(
                                ArcSSASlotRejectionReason.DESTINATION_NOT_EMPTY,
                                "owned move destination must be a distinct empty slot",
                                blockId, index, location = operation.location,
                            )
                        }
                        val source = facts[operation.sourceSlot] ?: return reject(
                            ArcSSASlotRejectionReason.SLOT_NOT_INITIALIZED,
                            "owned move source is empty",
                            blockId, index, location = operation.location,
                        )
                        if (source.version != operation.sourceVersion || source.ownership != ArcOwnership.Owned ||
                            !factMatches(source, operation.value)
                        ) return reject(
                            ArcSSASlotRejectionReason.VALUE_IDENTITY_MISMATCH,
                            "owned move does not name the sole active source alternative",
                            blockId, index, location = operation.location,
                        )
                        if (borrows.values.any { it.anchorVersion == source.version }) return reject(
                            ArcSSASlotRejectionReason.LIVE_BORROW,
                            "cannot move ${source.version} while one of its borrows is live",
                            blockId, index, location = operation.location,
                        )
                        facts.remove(operation.sourceSlot)
                        facts[operation.destinationSlot] = source.copy(
                            slot = operation.destinationSlot,
                            version = operation.destinationVersion,
                        )
                    }
                    is ArcSSAOperation.DestroyOwned -> {
                        val source = facts[operation.slot] ?: return reject(
                            ArcSSASlotRejectionReason.SLOT_NOT_INITIALIZED,
                            "owned destroy source is empty",
                            blockId, index, location = operation.location,
                        )
                        if (source.version != operation.version || source.ownership != ArcOwnership.Owned ||
                            !factMatches(source, operation.value)
                        ) return reject(
                            ArcSSASlotRejectionReason.VALUE_IDENTITY_MISMATCH,
                            "owned destroy does not name the sole active alternative",
                            blockId, index, location = operation.location,
                        )
                        if (borrows.values.any { it.anchorVersion == source.version }) return reject(
                            ArcSSASlotRejectionReason.LIVE_BORROW,
                            "cannot destroy ${source.version} while one of its borrows is live",
                            blockId, index, location = operation.location,
                        )
                        facts.remove(operation.slot)
                        destroyed += source.identity.currentValue
                    }
                    is ArcSSAOperation.Introduce,
                    is ArcSSAOperation.Forward,
                    is ArcSSAOperation.Reborrow,
                    is ArcSSAOperation.Join,
                    is ArcSSAOperation.Use,
                    is ArcSSAOperation.DeinitBarrier -> Unit
                }
            }

            val outgoing = input.edges.filter { it.from == blockId }
            if (outgoing.isEmpty() && borrows.isNotEmpty()) {
                return reject(
                    ArcSSASlotRejectionReason.LIVE_BORROW_AT_EXIT,
                    "active borrows reach a CFG exit: ${borrows.keys.joinToString()}",
                    blockId,
                )
            }
            val unconsumedOwned = facts.values.filter { it.ownership == ArcOwnership.Owned }
            if (outgoing.isEmpty() && unconsumedOwned.isNotEmpty()) {
                return reject(
                    ArcSSASlotRejectionReason.UNCONSUMED_OWNED_AT_EXIT,
                    "owned slot alternatives reach a CFG exit without MoveOwned/DestroyOwned: " +
                            unconsumedOwned.joinToString { "${it.slot}:${it.version}" },
                    blockId,
                )
            }
            if (outgoing.any { it.kind == ArcSSAEdgeKind.Exceptional } && throwingOperations != 1) {
                return reject(
                    ArcSSASlotRejectionReason.AMBIGUOUS_EXCEPTIONAL_STATE,
                    "an exceptional slot edge requires exactly one throwing operation in its source block",
                    blockId,
                )
            }
            val finalState = currentState()
            exitStates[blockId] = finalState
            outgoing.forEach { edge ->
                if (edge.kind == ArcSSAEdgeKind.Exceptional) {
                    exceptionalEdges[edge] = exceptionalState!!
                } else {
                    normalEdges[edge] = finalState
                }
            }
        }
        return ArcSSASlotFlowResult(entryStates, exitStates, normalEdges, exceptionalEdges, rejections)
    }

    private fun mergeIncoming(
        block: ArcSSABlock,
        incoming: Map<ArcSSAEdge, ArcSSASlotState>,
        ownershipWebs: List<ArcOwnershipSSAWeb>,
        identities: ArcRCIdentityResult,
    ): MergeResult? {
        if (incoming.isEmpty()) return MergeResult(ArcSSASlotState(), emptySet())
        val states = incoming.values.toList()
        if (states.map { it.activeBorrows }.distinct().size != 1 ||
            states.map { it.destroyedOwnedIdentities }.distinct().size != 1
        ) return null
        val slots = states.first().facts.keys
        if (states.any { it.facts.keys != slots }) return null
        val facts = linkedMapOf<ArcSSASlot, ArcSSASlotFact>()
        val applied = linkedSetOf<Int>()
        val lastJoinPrefixIndex = block.operations.indexOfFirst { operation ->
            operation !is ArcSSAOperation.Join && operation !is ArcSSAOperation.JoinSlot
        }.let { if (it < 0) block.operations.size else it }
        slots.forEach { slot ->
            val byEdge = incoming.mapValues { it.value.facts.getValue(slot) }
            val versions = byEdge.values.map { it.version }.toSet()
            if (versions.size == 1) {
                if (byEdge.values.distinct().size != 1) return null
                facts[slot] = byEdge.values.first()
                return@forEach
            }
            if (byEdge.values.map { it.ownership }.toSet().size != 1 ||
                incoming.keys.any { it.kind != ArcSSAEdgeKind.Normal }
            ) return null
            val candidates = block.operations.withIndex().filter { (_, operation) ->
                operation is ArcSSAOperation.JoinSlot && operation.slot == slot
            }
            val candidate = candidates.singleOrNull() ?: return null
            if (candidate.index >= lastJoinPrefixIndex) return null
            val joinSlot = candidate.value as ArcSSAOperation.JoinSlot
            if (joinSlot.incoming != byEdge.mapValues { it.value.version }) return null
            val joinValue = block.operations.filterIsInstance<ArcSSAOperation.Join>()
                .singleOrNull { it.result == joinSlot.value } ?: return null
            if (joinValue.incoming != byEdge.mapKeys { it.key.from }.mapValues { it.value.identity.currentValue }) return null
            val web = ownershipWebs.singleOrNull { accepted ->
                accepted.join == joinSlot.value && accepted.mergeBlock == block.id &&
                        accepted.ownership == byEdge.values.first().ownership &&
                        joinValue.incoming.values.all { it in accepted.members }
            } ?: return null
            val joinedIdentity = identities.identity(joinSlot.value) ?: return null
            if (joinedIdentity.ownershipWeb !== web) return null
            facts[slot] = ArcSSASlotFact(
                slot,
                joinSlot.version,
                byEdge.values.first().ownership,
                ArcSSASlotRCIdentity.PathDisjoint(
                    joinSlot.value,
                    web,
                    byEdge.mapValues { it.value.identity.currentValue },
                ),
            )
            applied += candidate.index
        }
        return MergeResult(
            ArcSSASlotState(facts, states.first().activeBorrows, states.first().destroyedOwnedIdentities),
            applied,
        )
    }

    private fun mergeFailureReason(
        input: ArcOwnershipSSAInput,
        incoming: Map<ArcSSAEdge, ArcSSASlotState>,
    ): ArcSSASlotRejectionReason {
        if (incoming.values.map { it.activeBorrows }.distinct().size != 1 ||
            incoming.values.map { it.destroyedOwnedIdentities }.distinct().size != 1
        ) {
            return ArcSSASlotRejectionReason.LIVE_BORROW_AT_JOIN
        }
        val slotSets = incoming.values.map { it.facts.keys }
        if (slotSets.distinct().size != 1) {
            return if (incoming.keys.any { isCritical(it, input) }) {
                ArcSSASlotRejectionReason.CRITICAL_EDGE_CLEANUP
            } else ArcSSASlotRejectionReason.MIXED_INITIALIZATION
        }
        val mixedOwnership = slotSets.firstOrNull().orEmpty().any { slot ->
            incoming.values.map { it.facts.getValue(slot).ownership }.toSet().size != 1
        }
        return if (mixedOwnership) ArcSSASlotRejectionReason.MIXED_OWNERSHIP
        else ArcSSASlotRejectionReason.INVALID_PATH_DISJOINT_JOIN
    }

    private fun mergeFailureDetail(block: ArcSSABlock, incoming: Map<ArcSSAEdge, ArcSSASlotState>): String =
        "incoming slot facts at ${block.id} require an exact JoinSlot; edges=${incoming.keys.joinToString()}"

    private fun sameIdentity(left: ArcSSAValue, right: ArcSSAValue, identities: ArcRCIdentityResult): Boolean {
        val leftIdentity = identities.identity(left) ?: return false
        val rightIdentity = identities.identity(right) ?: return false
        return leftIdentity.provenanceRoots == rightIdentity.provenanceRoots &&
                leftIdentity.ownershipWeb === rightIdentity.ownershipWeb
    }

    private fun identitiesOverlap(left: ArcSSAValue, right: ArcSSAValue, identities: ArcRCIdentityResult): Boolean {
        val leftIdentity = identities.identity(left) ?: return false
        val rightIdentity = identities.identity(right) ?: return false
        return leftIdentity.provenanceRoots.any { it in rightIdentity.provenanceRoots } ||
                (leftIdentity.ownershipWeb != null && leftIdentity.ownershipWeb === rightIdentity.ownershipWeb)
    }

    private fun ArcRCIdentityResult.ownershipOf(
        value: ArcSSAValue,
        input: ArcOwnershipSSAInput,
    ): ArcOwnership? {
        val definitions = input.blocks.values.flatMap { it.operations }.associateBy { it.resultOrNullForSlotFlow() }
        fun resolve(current: ArcSSAValue, visiting: MutableSet<ArcSSAValue>): ArcOwnership? {
            if (!visiting.add(current)) return null
            return when (val operation = definitions[current]) {
                is ArcSSAOperation.Introduce -> operation.ownership
                is ArcSSAOperation.Forward -> resolve(operation.source, visiting)
                is ArcSSAOperation.Reborrow, is ArcSSAOperation.Borrow -> ArcOwnership.Guaranteed
                is ArcSSAOperation.Join -> operation.incoming.values.mapNotNull { resolve(it, visiting.toMutableSet()) }
                    .toSet().singleOrNull()
                else -> null
            }
        }
        return resolve(value, mutableSetOf())
    }

    private fun ArcSSAOperation.resultOrNullForSlotFlow(): ArcSSAValue? = when (this) {
        is ArcSSAOperation.Introduce -> result
        is ArcSSAOperation.Forward -> result
        is ArcSSAOperation.Reborrow -> result
        is ArcSSAOperation.Join -> result
        is ArcSSAOperation.Borrow -> result
        else -> null
    }

    private fun ArcSSAOperation.declaredVersion(): ArcSSASlotVersion? = when (this) {
        is ArcSSAOperation.InitializeOwned -> version
        is ArcSSAOperation.InitializeImmortal -> version
        is ArcSSAOperation.JoinSlot -> version
        is ArcSSAOperation.MoveOwned -> destinationVersion
        else -> null
    }

    private fun ArcSSAOperation.isSlotOperation(): Boolean = when (this) {
        is ArcSSAOperation.InitializeOwned,
        is ArcSSAOperation.InitializeImmortal,
        is ArcSSAOperation.JoinSlot,
        is ArcSSAOperation.Borrow,
        is ArcSSAOperation.EndBorrow,
        is ArcSSAOperation.MoveOwned,
        is ArcSSAOperation.DestroyOwned -> true
        else -> false
    }

    private fun reachableFrom(start: ArcBlockId, input: ArcOwnershipSSAInput): Set<ArcBlockId> {
        val result = linkedSetOf<ArcBlockId>()
        val worklist = ArrayDeque<ArcBlockId>().apply { add(start) }
        while (worklist.isNotEmpty()) {
            val block = worklist.removeFirst()
            if (!result.add(block)) continue
            input.edges.filter { it.from == block }.forEach { worklist += it.to }
        }
        return result
    }

    private fun isCritical(edge: ArcSSAEdge, input: ArcOwnershipSSAInput): Boolean =
        input.edges.count { it.from == edge.from } > 1 && input.edges.count { it.to == edge.to } > 1

    private fun validateGlobalSSA(input: ArcOwnershipSSAInput): GlobalValidationFailure? {
        input.blocks.forEach { (key, block) ->
            if (key != block.id) return GlobalValidationFailure(
                "block map key $key does not match embedded block id ${block.id}",
                block.id,
            )
        }
        val definitions = linkedMapOf<ArcSSAValue, Pair<ArcBlockId, Int>>()
        input.blocks.values.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                operation.resultOrNullForSlotFlow()?.let { result ->
                    val previous = definitions.put(result, block.id to index)
                    if (previous != null) return GlobalValidationFailure(
                        "SSA value $result is defined at both $previous and ${block.id to index}",
                        block.id, index, operation.location,
                    )
                }
            }
        }
        val dominators = computeGlobalDominators(input)
        input.blocks.values.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                if (operation is ArcSSAOperation.Join) {
                    val predecessorIds = input.edges.filter { it.to == block.id }.mapTo(linkedSetOf()) { it.from }
                    if (operation.incoming.keys != predecessorIds) return GlobalValidationFailure(
                        "Join incoming keys ${operation.incoming.keys} do not match CFG predecessors $predecessorIds",
                        block.id, index, operation.location,
                    )
                    operation.incoming.forEach { (predecessor, value) ->
                        val definition = definitions[value] ?: return GlobalValidationFailure(
                            "Join input $value has no definition", block.id, index, operation.location,
                        )
                        if (definition.first !in dominators.getValue(predecessor) ||
                            (definition.first == predecessor && definition.second >= input.blocks.getValue(predecessor).operations.size)
                        ) return GlobalValidationFailure(
                            "Join input $value does not dominate predecessor $predecessor",
                            block.id, index, operation.location,
                        )
                    }
                } else {
                    operation.dependenciesForGlobalValidation().forEach { dependency ->
                        val definition = definitions[dependency] ?: return GlobalValidationFailure(
                            "operation dependency $dependency has no definition",
                            block.id, index, operation.location,
                        )
                        if (definition.first !in dominators.getValue(block.id) ||
                            (definition.first == block.id && definition.second >= index)
                        ) return GlobalValidationFailure(
                            "definition of $dependency does not dominate its use",
                            block.id, index, operation.location,
                        )
                    }
                }
            }
        }
        return null
    }

    private fun computeGlobalDominators(input: ArcOwnershipSSAInput): Map<ArcBlockId, Set<ArcBlockId>> {
        val reachable = reachableFrom(input.entry, input)
        val result = input.blocks.keys.associateWithTo(linkedMapOf()) { block ->
            if (block == input.entry) setOf(block) else reachable.toSet()
        }
        var changed = true
        while (changed) {
            changed = false
            reachable.filter { it != input.entry }.forEach { block ->
                val predecessors = input.edges.filter { it.to == block && it.from in reachable }.map { it.from }
                val updated = if (predecessors.isEmpty()) setOf(block) else
                    predecessors.map { result.getValue(it) }.reduce { left, right -> left intersect right } + block
                if (result[block] != updated) {
                    result[block] = updated
                    changed = true
                }
            }
        }
        return result
    }

    private fun ArcSSAOperation.dependenciesForGlobalValidation(): List<ArcSSAValue> = when (this) {
        is ArcSSAOperation.Introduce -> anchorDependencies.toList()
        is ArcSSAOperation.Forward -> listOf(source)
        is ArcSSAOperation.Reborrow -> listOf(source) + anchorDependencies
        is ArcSSAOperation.Join -> emptyList()
        is ArcSSAOperation.InitializeOwned -> listOf(value)
        is ArcSSAOperation.InitializeImmortal -> listOf(value)
        is ArcSSAOperation.JoinSlot -> listOf(value)
        is ArcSSAOperation.Borrow -> listOf(source)
        is ArcSSAOperation.Use -> listOf(value)
        is ArcSSAOperation.MoveOwned -> listOf(value)
        is ArcSSAOperation.DestroyOwned -> listOf(value)
        is ArcSSAOperation.EndBorrow, is ArcSSAOperation.DeinitBarrier -> emptyList()
    }

    private fun computeBorrowDependencies(input: ArcOwnershipSSAInput): Map<ArcSSAValue, Set<ArcSSABorrowId>> {
        val dependencies = linkedMapOf<ArcSSAValue, Set<ArcSSABorrowId>>()
        input.blocks.values.flatMap { it.operations }.filterIsInstance<ArcSSAOperation.Borrow>().forEach { borrow ->
            dependencies[borrow.result] = setOf(borrow.borrowId)
        }
        var changed = true
        while (changed) {
            changed = false
            input.blocks.values.flatMap { it.operations }.forEach { operation ->
                val result = operation.resultOrNullForSlotFlow() ?: return@forEach
                val inherited = when (operation) {
                    is ArcSSAOperation.Introduce -> operation.anchorDependencies
                    is ArcSSAOperation.Forward -> setOf(operation.source)
                    is ArcSSAOperation.Reborrow -> setOf(operation.source) + operation.anchorDependencies
                    is ArcSSAOperation.Join -> operation.incoming.values.toSet()
                    is ArcSSAOperation.Borrow -> setOf(operation.source)
                    else -> emptySet()
                }.flatMapTo(linkedSetOf()) { dependencies[it].orEmpty() }
                val updated = dependencies[result].orEmpty() + inherited
                if (dependencies[result] != updated) {
                    dependencies[result] = updated
                    changed = true
                }
            }
        }
        return dependencies
    }

    private fun ArcSSAOperation.dependenciesForBorrowValidation(): List<ArcSSAValue> = when (this) {
        is ArcSSAOperation.Join -> incoming.values.toList()
        else -> dependenciesForGlobalValidation()
    }
}
