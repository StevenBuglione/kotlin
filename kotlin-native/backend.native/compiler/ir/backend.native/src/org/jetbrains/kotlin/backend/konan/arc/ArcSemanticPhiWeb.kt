/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

/** Deterministic limits keep malformed or adversarial ownership graphs from making compilation unbounded. */
internal data class ArcSemanticARCBudget(
    val maximumInstructions: Int = 100_000,
    val maximumUses: Int = 400_000,
    val maximumRewriteSteps: Int = 100_000,
)

/**
 * An owned copy of a guaranteed value is the only seed accepted by the generalized phi transform.
 * The adapter must provide every lifetime anchor on which [guaranteedSource] depends.
 */
internal data class ArcGuaranteedCopySeed(
    val copy: ArcSSAValue,
    val guaranteedSource: ArcSSAValue,
    val anchorDependencies: Set<ArcSSAValue>,
)

internal enum class ArcSemanticARCBarrierKind {
    Deinitialization,
    Suspension,
    ForeignCall,
}

/** A barrier occurs immediately before [operationIndex]. */
internal data class ArcSemanticARCBarrier(
    val block: ArcBlockId,
    val operationIndex: Int,
    val kind: ArcSemanticARCBarrierKind,
)

internal data class ArcSemanticARCInput(
    val cfg: ArcOwnershipSSAInput,
    val guaranteedCopies: Set<ArcGuaranteedCopySeed>,
    val deadEndBlocks: Set<ArcBlockId> = emptySet(),
    /** Exits whose modeled terminator consumes the lifetime (for example return/throw payloads). */
    val exitLifetimeUses: Set<ArcBlockId> = emptySet(),
    val barriers: Set<ArcSemanticARCBarrier> = emptySet(),
    val budget: ArcSemanticARCBudget = ArcSemanticARCBudget(),
)

internal enum class ArcPrunedBlockLiveness { Dead, LiveWithin, LiveOut }

internal sealed class ArcSemanticLifetimeFrontier {
    data class AfterOperation(val block: ArcBlockId, val operationIndex: Int) : ArcSemanticLifetimeFrontier()
    data class BeforeBarrier(val barrier: ArcSemanticARCBarrier) : ArcSemanticLifetimeFrontier()
    data class BeforeExit(val block: ArcBlockId) : ArcSemanticLifetimeFrontier()

    /** [requiresEdgeSplit] is the explicit authorization required by a later CFG adapter. */
    data class OnEdge(val edge: ArcSSAEdge, val requiresEdgeSplit: Boolean) : ArcSemanticLifetimeFrontier()
}

/** A guaranteed phi operand is a reborrow of the incoming scope on exactly one predecessor edge. */
internal data class ArcSemanticReborrow(
    val edge: ArcSSAEdge,
    val incoming: ArcSSAValue,
    val joined: ArcSSAValue,
    val anchorDependencies: Set<ArcSSAValue>,
)

internal sealed class ArcSemanticARCRewrite {
    /** One atomic ownership-valid rewrite: reborrow all incoming edges, convert all phis, and end scopes. */
    data class PrepareJoinedWeb(
        val joins: Set<ArcSSAValue>,
        val reborrows: Set<ArcSemanticReborrow>,
        val frontier: Set<ArcSemanticLifetimeFrontier>,
    ) : ArcSemanticARCRewrite()

    data class EliminateGuaranteedCopy(val copy: ArcSSAValue, val source: ArcSSAValue) : ArcSemanticARCRewrite()
}

internal enum class ArcSemanticARCRejectionReason {
    MalformedCFG,
    BudgetExhausted,
    IncompletePhiWeb,
    InvalidGuaranteedSeed,
    ConflictingRCIdentity,
    BorrowEscape,
    MultipleConsumesOnPath,
    BarrierCrossing,
    InvalidLifetime,
    RewriteVerificationFailed,
}

internal data class ArcSemanticARCRejection(
    val seed: ArcSSAValue,
    val reason: ArcSemanticARCRejectionReason,
    val detail: String,
)

/** Immutable proof consumed by the future IR/LLVM adapter. */
internal data class ArcSemanticPhiWebPlan(
    val seeds: Set<ArcGuaranteedCopySeed>,
    val members: Set<ArcSSAValue>,
    val joins: Set<ArcSSAValue>,
    val canonicalRCRoots: Set<ArcSSAValue>,
    val reborrows: Set<ArcSemanticReborrow>,
    val liveness: Map<ArcBlockId, ArcPrunedBlockLiveness>,
    val frontier: Set<ArcSemanticLifetimeFrontier>,
    val rewrites: List<ArcSemanticARCRewrite>,
    /** One successful full verification after each rewrite, including the final plan. */
    val verificationEpochs: Int,
)

internal data class ArcSemanticARCResult(
    val accepted: List<ArcSemanticPhiWebPlan>,
    val rejected: List<ArcSemanticARCRejection>,
)

/**
 * Swift SemanticARC-style owned-to-guaranteed phi conversion. This constructs proof records only;
 * it cannot mutate IR. Every uncertainty fails closed and the future emitter must consume the
 * complete plan atomically.
 */
internal object ArcSemanticPhiWebAnalysis {
    private data class Definition(val block: ArcBlockId, val index: Int, val operation: ArcSSAOperation)
    private data class SemanticUse(
        val block: ArcBlockId,
        val index: Int,
        val value: ArcSSAValue,
        val kind: ArcSSAUseKind,
        val mayThrow: Boolean = false,
    )
    private data class Liveness(
        val before: Map<ArcBlockId, BooleanArray>,
        val after: Map<ArcBlockId, BooleanArray>,
        val liveIn: Map<ArcBlockId, Boolean>,
        val liveOut: Map<ArcBlockId, Boolean>,
    )
    private data class TransformedState(
        val preparedJoins: Set<ArcSSAValue> = emptySet(),
        val installedReborrows: Set<ArcSemanticReborrow> = emptySet(),
        val installedFrontier: Set<ArcSemanticLifetimeFrontier> = emptySet(),
        val eliminatedCopies: Map<ArcSSAValue, ArcSSAValue> = emptyMap(),
    )

    fun analyze(rawInput: ArcSemanticARCInput): ArcSemanticARCResult {
        val implicitDeinitializationBarriers = rawInput.cfg.blocks.values.flatMapTo(linkedSetOf()) { block ->
            block.operations.mapIndexedNotNull { index, operation ->
                if (operation is ArcSSAOperation.DeinitBarrier) {
                    ArcSemanticARCBarrier(block.id, index, ArcSemanticARCBarrierKind.Deinitialization)
                } else null
            }
        }
        val input = rawInput.copy(barriers = rawInput.barriers + implicitDeinitializationBarriers)
        val definitions = linkedMapOf<ArcSSAValue, Definition>()
        val duplicates = linkedSetOf<ArcSSAValue>()
        var instructionCount = 0
        var useCount = 0
        input.cfg.blocks.values.sortedBy { it.id.name }.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                instructionCount++
                useCount += operation.semanticOperands().size
                operation.semanticResultOrNull()?.let { result ->
                    if (definitions.put(result, Definition(block.id, index, operation)) != null) duplicates += result
                }
            }
        }
        val malformed = input.cfg.entry !in input.cfg.blocks ||
                input.cfg.edges.any { it.from !in input.cfg.blocks || it.to !in input.cfg.blocks } ||
                input.deadEndBlocks.any { it !in input.cfg.blocks } ||
                input.exitLifetimeUses.any { block -> block !in input.cfg.blocks ||
                        input.cfg.edges.any { it.from == block } || block in input.deadEndBlocks } ||
                input.barriers.any { it.block !in input.cfg.blocks ||
                        it.operationIndex !in 0..input.cfg.blocks.getValue(it.block).operations.size }
        val overBudget = instructionCount > input.budget.maximumInstructions || useCount > input.budget.maximumUses
        val accepted = mutableListOf<ArcSemanticPhiWebPlan>()
        val rejected = mutableListOf<ArcSemanticARCRejection>()
        val claimed = linkedSetOf<ArcSSAValue>()
        val processedSeeds = linkedSetOf<ArcSSAValue>()

        input.guaranteedCopies.sortedBy { it.copy.name }.forEach { initialSeed ->
            if (initialSeed.copy in processedSeeds || accepted.any { initialSeed.copy in it.members } ||
                rejected.any { it.seed == initialSeed.copy }) return@forEach
            fun reject(reason: ArcSemanticARCRejectionReason, detail: String) {
                rejected += ArcSemanticARCRejection(initialSeed.copy, reason, detail)
            }
            if (malformed || initialSeed.copy in duplicates) {
                reject(ArcSemanticARCRejectionReason.MalformedCFG, "CFG, barrier, or SSA definition is malformed")
                return@forEach
            }
            if (input.guaranteedCopies.count { it.copy == initialSeed.copy } != 1) {
                reject(ArcSemanticARCRejectionReason.ConflictingRCIdentity,
                    "one owned copy has more than one guaranteed source or anchor identity")
                return@forEach
            }
            if (overBudget) {
                reject(ArcSemanticARCRejectionReason.BudgetExhausted,
                    "graph has $instructionCount instructions and $useCount uses")
                return@forEach
            }
            val members = discoverCompleteWeb(initialSeed.copy, definitions, input.cfg) ?: run {
                reject(ArcSemanticARCRejectionReason.IncompletePhiWeb, "a forwarding or phi dependency is undefined")
                return@forEach
            }
            if (members.any { it in duplicates } || members.any { it in claimed }) {
                reject(ArcSemanticARCRejectionReason.ConflictingRCIdentity,
                    "web overlaps a duplicate definition or a previously accepted RC identity")
                return@forEach
            }
            if (!hasValidSSA(input.cfg, members, definitions)) {
                reject(ArcSemanticARCRejectionReason.MalformedCFG,
                    "join predecessor keys, reachability, or SSA dominance are invalid")
                return@forEach
            }
            val seeds = input.guaranteedCopies.filterTo(linkedSetOf()) { it.copy in members }
            processedSeeds += seeds.map { it.copy }
            if (seeds.groupBy { it.copy }.any { it.value.size != 1 }) {
                reject(ArcSemanticARCRejectionReason.ConflictingRCIdentity,
                    "a complete phi web contains conflicting guaranteed-copy identities")
                return@forEach
            }
            val ownedIntroducers = members.mapNotNullTo(linkedSetOf()) { value ->
                (definitions[value]?.operation as? ArcSSAOperation.Introduce)
                    ?.takeIf { it.ownership == ArcOwnership.Owned }?.result
            }
            if (ownedIntroducers != seeds.mapTo(linkedSetOf()) { it.copy } || seeds.isEmpty()) {
                reject(ArcSemanticARCRejectionReason.IncompletePhiWeb,
                    "every owned introducer in the complete web must be a guaranteed-copy seed")
                return@forEach
            }
            val invalidSeed = seeds.firstOrNull { seed ->
                val actualDependencies = guaranteedDependencies(seed.guaranteedSource, definitions, mutableSetOf())
                val claimedDependencies = seed.anchorDependencies.mapTo(linkedSetOf()) {
                    canonicalRoot(it, definitions, mutableSetOf())
                }
                definitions[seed.copy]?.operation.let { it !is ArcSSAOperation.Introduce || it.ownership != ArcOwnership.Owned } ||
                        inferOwnership(seed.guaranteedSource, definitions, mutableSetOf()) != ArcOwnership.Guaranteed ||
                        seed.anchorDependencies.isEmpty() || seed.anchorDependencies.any { it !in definitions } ||
                        actualDependencies == null || claimedDependencies != actualDependencies ||
                        actualDependencies.any { it in members } ||
                        !seedScopeDominatesCopy(seed, input.cfg, definitions)
            }
            if (invalidSeed != null) {
                reject(ArcSemanticARCRejectionReason.InvalidGuaranteedSeed,
                    "${invalidSeed.copy} is not an owned copy of a defined guaranteed scope")
                return@forEach
            }
            val unsupportedTransfer = input.cfg.blocks.values.asSequence().flatMap { block ->
                block.operations.asSequence().mapIndexedNotNull { index, operation ->
                    operation.unsupportedSlotTransferValue()?.takeIf { it in members }
                        ?.let { Triple(block.id, index, operation) }
                }
            }.firstOrNull()
            if (unsupportedTransfer != null) {
                reject(ArcSemanticARCRejectionReason.BorrowEscape,
                    "ownership transfer ${unsupportedTransfer.third.semanticOperationName()} at ${unsupportedTransfer.first}:${unsupportedTransfer.second}")
                return@forEach
            }
            val uses = classifiedUses(input.cfg, members)
            val escaping = uses.firstOrNull { it.kind == ArcSSAUseKind.Escape || it.kind == ArcSSAUseKind.UnknownConsume }
            if (escaping != null) {
                reject(ArcSemanticARCRejectionReason.BorrowEscape, "${escaping.kind} at ${escaping.block}:${escaping.index}")
                return@forEach
            }
            if (hasSequentialConsumes(input.cfg, uses) || uses.any {
                    it.kind == ArcSSAUseKind.Consume && isReachable(it.block, it.block, input.cfg)
                }) {
                reject(ArcSemanticARCRejectionReason.MultipleConsumesOnPath, "two lifetime-ending uses are reachable on one path")
                return@forEach
            }
            if (hasUseAfterConsume(input.cfg, members, uses)) {
                reject(ArcSemanticARCRejectionReason.InvalidLifetime, "a consumed RC identity is used again")
                return@forEach
            }

            val liveness = computePrunedLiveness(input, members)
            val crossed = input.barriers.firstOrNull { barrier ->
                val before = liveness.before[barrier.block]
                val after = liveness.after[barrier.block]
                when (barrier.operationIndex) {
                    0 -> liveness.liveIn[barrier.block] == true && (before?.firstOrNull() == true)
                    input.cfg.blocks.getValue(barrier.block).operations.size -> liveness.liveOut[barrier.block] == true
                    else -> before?.getOrNull(barrier.operationIndex) == true && after?.getOrNull(barrier.operationIndex - 1) == true
                }
            }
            if (crossed != null) {
                reject(ArcSemanticARCRejectionReason.BarrierCrossing,
                    "guaranteed lifetime would cross ${crossed.kind} at ${crossed.block}:${crossed.operationIndex}")
                return@forEach
            }
            val joins = members.filterTo(linkedSetOf()) { definitions[it]?.operation is ArcSSAOperation.Join }
            val roots = seeds.flatMapTo(linkedSetOf()) { it.anchorDependencies.map { anchor ->
                canonicalRoot(anchor, definitions, mutableSetOf())
            } }
            val reborrows = buildReborrows(input.cfg, joins, definitions, seeds)
            val frontier = computeFrontier(input, uses, liveness)
            val rewriteList = buildRewrites(seeds, joins, reborrows, frontier)
            if (rewriteList.size > input.budget.maximumRewriteSteps) {
                reject(ArcSemanticARCRejectionReason.BudgetExhausted, "plan requires ${rewriteList.size} rewrites")
                return@forEach
            }
            var verificationEpochs = 0
            var transformedState = TransformedState()
            if (rewriteList.any { rewrite ->
                    transformedState = applyRewrite(transformedState, rewrite)
                    val verified = verifyTransformedState(
                        input, members, seeds, joins, reborrows, frontier, transformedState, definitions,
                    )
                    verificationEpochs++
                    !verified
                } || !verifyFinal(input, members, seeds, joins, reborrows, frontier, transformedState, definitions)) {
                reject(ArcSemanticARCRejectionReason.RewriteVerificationFailed,
                    "mandatory ownership verification failed after rewrite $verificationEpochs")
                return@forEach
            }
            verificationEpochs++
            accepted += ArcSemanticPhiWebPlan(
                seeds, members, joins, roots, reborrows,
                input.cfg.blocks.keys.sortedBy { it.name }.associateWith { block ->
                    when {
                        liveness.liveOut[block] == true -> ArcPrunedBlockLiveness.LiveOut
                        liveness.before[block]?.any { it } == true || liveness.after[block]?.any { it } == true ->
                            ArcPrunedBlockLiveness.LiveWithin
                        else -> ArcPrunedBlockLiveness.Dead
                    }
                },
                frontier, rewriteList, verificationEpochs,
            )
            claimed += members
        }
        return ArcSemanticARCResult(accepted, rejected)
    }

    private fun discoverCompleteWeb(
        seed: ArcSSAValue,
        definitions: Map<ArcSSAValue, Definition>,
        cfg: ArcOwnershipSSAInput,
    ): Set<ArcSSAValue>? {
        val result = linkedSetOf(seed)
        var changed = true
        while (changed) {
            changed = false
            cfg.blocks.values.sortedBy { it.id.name }.forEach { block ->
                block.operations.forEach { operation ->
                    when (operation) {
                        is ArcSSAOperation.Forward -> if (operation.source in result || operation.result in result) {
                            changed = result.add(operation.source) || changed
                            changed = result.add(operation.result) || changed
                        }
                        is ArcSSAOperation.Join -> if (operation.result in result || operation.incoming.values.any { it in result }) {
                            changed = result.add(operation.result) || changed
                            operation.incoming.toSortedMap(compareBy { it.name }).values.forEach { changed = result.add(it) || changed }
                        }
                        else -> Unit
                    }
                }
            }
        }
        return result.takeIf { web -> web.all { it in definitions } }
    }

    private fun computePrunedLiveness(input: ArcSemanticARCInput, members: Set<ArcSSAValue>): Liveness {
        val liveIn = input.cfg.blocks.keys.associateWithTo(linkedMapOf()) { false }
        val liveOut = input.cfg.blocks.keys.associateWithTo(linkedMapOf()) { false }
        val before = linkedMapOf<ArcBlockId, BooleanArray>()
        val after = linkedMapOf<ArcBlockId, BooleanArray>()
        var changed = true
        while (changed) {
            changed = false
            input.cfg.blocks.values.sortedByDescending { it.id.name }.forEach { block ->
                val successors = input.cfg.edges.filter { it.from == block.id }.map { it.to }
                val newOut = block.id in input.exitLifetimeUses || successors.any { liveIn[it] == true }
                var live = newOut
                val newBefore = BooleanArray(block.operations.size)
                val newAfter = BooleanArray(block.operations.size)
                block.operations.indices.reversed().forEach { index ->
                    val operation = block.operations[index]
                    newAfter[index] = live
                    live = when {
                        operation is ArcSSAOperation.Introduce && operation.result in members -> false
                        operation.semanticUseValue()?.let { it in members } == true -> true
                        operation.semanticOperands().any { it in members } -> live
                        else -> live
                    }
                    newBefore[index] = live
                }
                if (newOut != liveOut[block.id] || live != liveIn[block.id] ||
                    !newBefore.contentEquals(before[block.id] ?: BooleanArray(block.operations.size)) ||
                    !newAfter.contentEquals(after[block.id] ?: BooleanArray(block.operations.size))) {
                    liveOut[block.id] = newOut
                    liveIn[block.id] = live
                    before[block.id] = newBefore
                    after[block.id] = newAfter
                    changed = true
                }
            }
        }
        return Liveness(before, after, liveIn, liveOut)
    }

    private fun computeFrontier(
        input: ArcSemanticARCInput,
        uses: List<SemanticUse>,
        liveness: Liveness,
    ): Set<ArcSemanticLifetimeFrontier> {
        val result = linkedSetOf<ArcSemanticLifetimeFrontier>()
        uses.sortedWith(compareBy({ it.block.name }, { it.index })).forEach { use ->
            if (use.kind == ArcSSAUseKind.Consume || liveness.after[use.block]?.getOrNull(use.index) != true) {
                result += ArcSemanticLifetimeFrontier.AfterOperation(use.block, use.index)
            }
            if (use.mayThrow && liveness.before[use.block]?.getOrNull(use.index) == true) {
                input.cfg.edges.filter { it.from == use.block && it.kind == ArcSSAEdgeKind.Exceptional &&
                        liveness.liveIn[it.to] != true }
                    .forEach { edge ->
                        result += ArcSemanticLifetimeFrontier.OnEdge(edge, isCritical(edge, input.cfg))
                    }
            }
        }
        input.barriers.sortedWith(compareBy({ it.block.name }, { it.operationIndex }, { it.kind.name })).forEach { barrier ->
            val hasEarlierUse = uses.any { it.block == barrier.block && it.index < barrier.operationIndex }
            val hasLaterUse = uses.any { it.block == barrier.block && it.index >= barrier.operationIndex }
            if (hasEarlierUse && !hasLaterUse) result += ArcSemanticLifetimeFrontier.BeforeBarrier(barrier)
        }
        input.cfg.edges.sortedWith(compareBy({ it.from.name }, { it.to.name }, { it.kind.name })).forEach { edge ->
            if (liveness.liveOut[edge.from] == true && liveness.liveIn[edge.to] != true) {
                result += ArcSemanticLifetimeFrontier.OnEdge(edge, isCritical(edge, input.cfg))
            }
        }
        input.exitLifetimeUses.sortedBy { it.name }.filter { liveness.liveOut[it] == true }
            .forEach { result += ArcSemanticLifetimeFrontier.BeforeExit(it) }
        return result
    }

    private fun buildReborrows(
        cfg: ArcOwnershipSSAInput,
        joins: Set<ArcSSAValue>,
        definitions: Map<ArcSSAValue, Definition>,
        seeds: Set<ArcGuaranteedCopySeed>,
    ): Set<ArcSemanticReborrow> = buildSet {
        joins.sortedBy { it.name }.forEach { joined ->
            val join = definitions.getValue(joined).operation as ArcSSAOperation.Join
            join.incoming.toSortedMap(compareBy { it.name }).forEach { (predecessor, incoming) ->
                val anchors = seeds.filter { seed -> reaches(seed.copy, incoming, definitions) }
                    .flatMapTo(linkedSetOf()) { it.anchorDependencies }
                val edge = cfg.edges.singleOrNull { it.from == predecessor && it.to == definitions.getValue(joined).block }
                if (edge != null) add(ArcSemanticReborrow(edge, incoming, joined, anchors))
            }
        }
    }

    private fun buildRewrites(
        seeds: Set<ArcGuaranteedCopySeed>,
        joins: Set<ArcSSAValue>,
        reborrows: Set<ArcSemanticReborrow>,
        frontier: Set<ArcSemanticLifetimeFrontier>,
    ): List<ArcSemanticARCRewrite> = buildList {
        add(ArcSemanticARCRewrite.PrepareJoinedWeb(joins, reborrows, frontier))
        seeds.sortedBy { it.copy.name }.forEach { add(ArcSemanticARCRewrite.EliminateGuaranteedCopy(it.copy, it.guaranteedSource)) }
    }

    private fun applyRewrite(state: TransformedState, rewrite: ArcSemanticARCRewrite): TransformedState = when (rewrite) {
        is ArcSemanticARCRewrite.PrepareJoinedWeb -> state.copy(
            preparedJoins = state.preparedJoins + rewrite.joins,
            installedReborrows = state.installedReborrows + rewrite.reborrows,
            installedFrontier = state.installedFrontier + rewrite.frontier,
        )
        is ArcSemanticARCRewrite.EliminateGuaranteedCopy -> state.copy(
            eliminatedCopies = state.eliminatedCopies + (rewrite.copy to rewrite.source),
        )
    }

    private fun verifyTransformedState(
        input: ArcSemanticARCInput,
        members: Set<ArcSSAValue>,
        seeds: Set<ArcGuaranteedCopySeed>,
        joins: Set<ArcSSAValue>,
        reborrows: Set<ArcSemanticReborrow>,
        frontier: Set<ArcSemanticLifetimeFrontier>,
        state: TransformedState,
        definitions: Map<ArcSSAValue, Definition>,
    ): Boolean {
        if (!hasValidSSA(input.cfg, members, definitions)) return false
        if (state.preparedJoins != joins || state.installedReborrows != reborrows || state.installedFrontier != frontier) return false
        val expectedReborrowOperands = joins.flatMapTo(linkedSetOf()) { joined ->
            val join = definitions.getValue(joined).operation as ArcSSAOperation.Join
            join.incoming.map { (block, value) -> Triple(block, value, joined) }
        }
        val installedOperands = state.installedReborrows.mapTo(linkedSetOf()) {
            Triple(it.edge.from, it.incoming, it.joined)
        }
        if (expectedReborrowOperands != installedOperands || state.installedReborrows.any {
                it.anchorDependencies.isEmpty() || it.incoming !in members || it.joined !in joins
            }) return false
        if (state.eliminatedCopies.any { (copy, source) ->
                val seed = seeds.singleOrNull { it.copy == copy && it.guaranteedSource == source }
                val actualDependencies = guaranteedDependencies(source, definitions, mutableSetOf())
                seed == null || inferOwnership(source, definitions, mutableSetOf()) != ArcOwnership.Guaranteed ||
                        actualDependencies == null || seed.anchorDependencies.mapTo(linkedSetOf()) {
                            canonicalRoot(it, definitions, mutableSetOf())
                        } != actualDependencies
            }) return false
        return state.eliminatedCopies.keys.all { copy ->
            // Once eliminated, every ownership-phi use is represented by a verified edge reborrow.
            state.installedReborrows.any { reaches(copy, it.incoming, definitions) }
        }
    }

    private fun verifyFinal(
        input: ArcSemanticARCInput,
        members: Set<ArcSSAValue>,
        seeds: Set<ArcGuaranteedCopySeed>,
        joins: Set<ArcSSAValue>,
        reborrows: Set<ArcSemanticReborrow>,
        frontier: Set<ArcSemanticLifetimeFrontier>,
        state: TransformedState,
        definitions: Map<ArcSSAValue, Definition>,
    ): Boolean = verifyTransformedState(input, members, seeds, joins, reborrows, frontier, state, definitions) &&
            seeds.isNotEmpty() && state.eliminatedCopies.keys == seeds.mapTo(linkedSetOf()) { it.copy } &&
            joins.all { it in members } && frontier.none {
                it is ArcSemanticLifetimeFrontier.BeforeExit && it.block in input.deadEndBlocks
            }

    private fun hasValidSSA(
        cfg: ArcOwnershipSSAInput,
        members: Set<ArcSSAValue>,
        definitions: Map<ArcSSAValue, Definition>,
    ): Boolean {
        val reachable = reachableBlocks(cfg)
        if (members.any { definitions[it]?.block !in reachable }) return false
        val dominators = computeDominators(cfg, reachable)
        fun dominates(value: ArcSSAValue, block: ArcBlockId, index: Int): Boolean {
            val definition = definitions[value] ?: return false
            return definition.block in dominators[block].orEmpty() &&
                    (definition.block != block || definition.index < index)
        }
        return cfg.blocks.values.all { block -> block.operations.withIndex().all { (index, operation) ->
            if (operation is ArcSSAOperation.Join) {
                val predecessors = cfg.edges.filter { it.to == block.id && it.kind == ArcSSAEdgeKind.Normal }
                    .mapTo(linkedSetOf()) { it.from }
                operation.incoming.keys == predecessors && operation.incoming.all { (predecessor, value) ->
                    value !in members || dominates(value, predecessor, cfg.blocks.getValue(predecessor).operations.size)
                }
            } else operation.semanticOperands().all { value -> value !in members || dominates(value, block.id, index) }
        } }
    }

    private fun seedScopeDominatesCopy(
        seed: ArcGuaranteedCopySeed,
        cfg: ArcOwnershipSSAInput,
        definitions: Map<ArcSSAValue, Definition>,
    ): Boolean {
        val copyDefinition = definitions[seed.copy] ?: return false
        val reachable = reachableBlocks(cfg)
        val dominators = computeDominators(cfg, reachable)
        fun dominates(value: ArcSSAValue): Boolean {
            val definition = definitions[value] ?: return false
            return definition.block in dominators[copyDefinition.block].orEmpty() &&
                    (definition.block != copyDefinition.block || definition.index < copyDefinition.index)
        }
        return dominates(seed.guaranteedSource) && seed.anchorDependencies.all(::dominates)
    }

    private fun reachableBlocks(cfg: ArcOwnershipSSAInput): Set<ArcBlockId> {
        if (cfg.entry !in cfg.blocks) return emptySet()
        val result = linkedSetOf<ArcBlockId>()
        val worklist = ArrayDeque<ArcBlockId>().apply { add(cfg.entry) }
        while (worklist.isNotEmpty()) {
            val block = worklist.removeFirst()
            if (!result.add(block)) continue
            cfg.edges.filter { it.from == block }.forEach { worklist += it.to }
        }
        return result
    }

    private fun computeDominators(
        cfg: ArcOwnershipSSAInput,
        reachable: Set<ArcBlockId>,
    ): Map<ArcBlockId, Set<ArcBlockId>> {
        val result = reachable.associateWithTo(linkedMapOf()) { if (it == cfg.entry) setOf(it) else reachable }
        var changed = true
        while (changed) {
            changed = false
            reachable.filter { it != cfg.entry }.sortedBy { it.name }.forEach { block ->
                val predecessors = cfg.edges.filter { it.to == block && it.from in reachable }.map { it.from }
                val value = if (predecessors.isEmpty()) setOf(block) else
                    predecessors.map { result.getValue(it) }.reduce { left, right -> left intersect right } + block
                if (result[block] != value) {
                    result[block] = value
                    changed = true
                }
            }
        }
        return result
    }

    private fun hasSequentialConsumes(
        cfg: ArcOwnershipSSAInput,
        uses: List<SemanticUse>,
    ): Boolean {
        val consumes = uses.filter { it.kind == ArcSSAUseKind.Consume }
        return consumes.any { first -> consumes.any { second -> first !== second &&
                ((first.block == second.block && first.index < second.index) ||
                        (first.block != second.block && isReachable(first.block, second.block, cfg)))
        } }
    }

    private fun hasUseAfterConsume(
        cfg: ArcOwnershipSSAInput,
        members: Set<ArcSSAValue>,
        uses: List<SemanticUse>,
    ): Boolean = uses.filter { it.kind == ArcSSAUseKind.Consume }.any { consume ->
        cfg.blocks.getValue(consume.block).operations.drop(consume.index + 1).any { operation ->
            operation.semanticOperands().any { it in members }
        } || cfg.blocks.keys.any { block -> block != consume.block && isReachable(consume.block, block, cfg) &&
                cfg.blocks.getValue(block).operations.any { operation -> operation.semanticOperands().any { it in members } }
        }
    }

    private fun classifiedUses(cfg: ArcOwnershipSSAInput, members: Set<ArcSSAValue>): List<SemanticUse> = buildList {
        cfg.blocks.values.sortedBy { it.id.name }.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                when (operation) {
                    is ArcSSAOperation.Use -> if (operation.value in members) {
                        add(SemanticUse(block.id, index, operation.value, operation.kind, operation.mayThrow))
                    }
                    is ArcSSAOperation.Borrow -> if (operation.source in members) {
                        add(SemanticUse(block.id, index, operation.source, ArcSSAUseKind.Borrow))
                    }
                    is ArcSSAOperation.DestroyOwned -> if (operation.value in members) {
                        add(SemanticUse(block.id, index, operation.value, ArcSSAUseKind.Consume))
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun reaches(from: ArcSSAValue, to: ArcSSAValue, definitions: Map<ArcSSAValue, Definition>): Boolean {
        if (from == to) return true
        val visited = linkedSetOf<ArcSSAValue>()
        val worklist = ArrayDeque<ArcSSAValue>().apply { add(to) }
        while (worklist.isNotEmpty()) {
            val value = worklist.removeFirst()
            if (!visited.add(value)) continue
            val dependencies = when (val operation = definitions[value]?.operation) {
                is ArcSSAOperation.Forward -> listOf(operation.source)
                is ArcSSAOperation.Join -> operation.incoming.values
                else -> emptyList()
            }
            if (from in dependencies) return true
            worklist.addAll(dependencies)
        }
        return false
    }

    private fun inferOwnership(
        value: ArcSSAValue,
        definitions: Map<ArcSSAValue, Definition>,
        visiting: MutableSet<ArcSSAValue>,
    ): ArcOwnership? {
        if (!visiting.add(value)) return null
        return when (val operation = definitions[value]?.operation) {
            is ArcSSAOperation.Introduce -> operation.ownership
            is ArcSSAOperation.Forward -> inferOwnership(operation.source, definitions, visiting)
            is ArcSSAOperation.Reborrow, is ArcSSAOperation.Borrow -> ArcOwnership.Guaranteed
            is ArcSSAOperation.Join -> operation.incoming.values.mapNotNull {
                inferOwnership(it, definitions, visiting.toMutableSet())
            }.toSet().singleOrNull()
            else -> null
        }
    }

    private fun guaranteedDependencies(
        value: ArcSSAValue,
        definitions: Map<ArcSSAValue, Definition>,
        visiting: MutableSet<ArcSSAValue>,
    ): Set<ArcSSAValue>? {
        if (!visiting.add(value)) return null
        val result = when (val operation = definitions[value]?.operation) {
            is ArcSSAOperation.Introduce -> if (operation.ownership == ArcOwnership.Guaranteed) {
                operation.anchorDependencies.mapTo(linkedSetOf()) {
                    canonicalRoot(it, definitions, mutableSetOf())
                }.takeIf { it.isNotEmpty() }
            } else null
            is ArcSSAOperation.Forward -> guaranteedDependencies(operation.source, definitions, visiting)
            is ArcSSAOperation.Reborrow -> operation.anchorDependencies.mapTo(linkedSetOf()) {
                canonicalRoot(it, definitions, mutableSetOf())
            }.takeIf { it.isNotEmpty() }
            is ArcSSAOperation.Borrow -> setOf(canonicalRoot(operation.source, definitions, mutableSetOf()))
            is ArcSSAOperation.Join -> {
                val incoming = operation.incoming.values.map {
                    guaranteedDependencies(it, definitions, visiting.toMutableSet())
                }
                incoming.takeIf { it.none { dependencies -> dependencies == null } }
                    ?.filterNotNull()?.flatten()?.toCollection(linkedSetOf())
            }
            else -> null
        }
        visiting.remove(value)
        return result
    }

    private fun canonicalRoot(
        value: ArcSSAValue,
        definitions: Map<ArcSSAValue, Definition>,
        visiting: MutableSet<ArcSSAValue>,
    ): ArcSSAValue {
        if (!visiting.add(value)) return value
        return when (val operation = definitions[value]?.operation) {
            is ArcSSAOperation.Forward -> canonicalRoot(operation.source, definitions, visiting)
            else -> value
        }
    }

    private fun isReachable(from: ArcBlockId, to: ArcBlockId, cfg: ArcOwnershipSSAInput): Boolean {
        val visited = linkedSetOf<ArcBlockId>()
        val worklist = ArrayDeque<ArcBlockId>().apply { add(from) }
        while (worklist.isNotEmpty()) {
            val block = worklist.removeFirst()
            if (!visited.add(block)) continue
            cfg.edges.filter { it.from == block }.forEach { edge ->
                if (edge.to == to) return true
                worklist += edge.to
            }
        }
        return false
    }

    private fun isCritical(edge: ArcSSAEdge, cfg: ArcOwnershipSSAInput): Boolean =
        cfg.edges.count { it.from == edge.from } > 1 && cfg.edges.count { it.to == edge.to } > 1
}

private fun ArcSSAOperation.semanticOperands(): List<ArcSSAValue> = when (this) {
    is ArcSSAOperation.Introduce -> anchorDependencies.toList()
    is ArcSSAOperation.Forward -> listOf(source)
    is ArcSSAOperation.Reborrow -> listOf(source) + anchorDependencies
    is ArcSSAOperation.Join -> incoming.values.toList()
    is ArcSSAOperation.InitializeOwned -> listOf(value)
    is ArcSSAOperation.InitializeImmortal -> listOf(value)
    is ArcSSAOperation.JoinSlot -> listOf(value)
    is ArcSSAOperation.Borrow -> listOf(source)
    is ArcSSAOperation.EndBorrow -> emptyList()
    is ArcSSAOperation.MoveOwned -> listOf(value)
    is ArcSSAOperation.DestroyOwned -> listOf(value)
    is ArcSSAOperation.Use -> listOf(value)
    is ArcSSAOperation.DeinitBarrier -> emptyList()
}

private fun ArcSSAOperation.semanticResultOrNull(): ArcSSAValue? = when (this) {
    is ArcSSAOperation.Introduce -> result
    is ArcSSAOperation.Forward -> result
    is ArcSSAOperation.Reborrow -> result
    is ArcSSAOperation.Join -> result
    is ArcSSAOperation.Borrow -> result
    else -> null
}

private fun ArcSSAOperation.semanticUseValue(): ArcSSAValue? = when (this) {
    is ArcSSAOperation.Use -> value
    is ArcSSAOperation.Borrow -> source
    is ArcSSAOperation.DestroyOwned -> value
    else -> null
}

private fun ArcSSAOperation.unsupportedSlotTransferValue(): ArcSSAValue? = when (this) {
    is ArcSSAOperation.InitializeOwned -> value
    is ArcSSAOperation.InitializeImmortal -> value
    is ArcSSAOperation.JoinSlot -> value
    is ArcSSAOperation.MoveOwned -> value
    else -> null
}

private fun ArcSSAOperation.semanticOperationName(): String = when (this) {
    is ArcSSAOperation.Introduce -> "Introduce"
    is ArcSSAOperation.Forward -> "Forward"
    is ArcSSAOperation.Reborrow -> "Reborrow"
    is ArcSSAOperation.Join -> "Join"
    is ArcSSAOperation.InitializeOwned -> "InitializeOwned"
    is ArcSSAOperation.InitializeImmortal -> "InitializeImmortal"
    is ArcSSAOperation.JoinSlot -> "JoinSlot"
    is ArcSSAOperation.Borrow -> "Borrow"
    is ArcSSAOperation.EndBorrow -> "EndBorrow"
    is ArcSSAOperation.MoveOwned -> "MoveOwned"
    is ArcSSAOperation.DestroyOwned -> "DestroyOwned"
    is ArcSSAOperation.Use -> "Use"
    is ArcSSAOperation.DeinitBarrier -> "DeinitBarrier"
}
