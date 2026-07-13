/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

/** Stable identity of one emission-independent retain or release event. */
@JvmInline
internal value class ArcMatchingSetEventId(val name: String) {
    override fun toString(): String = "arc-event($name)"
}

internal enum class ArcMatchingSetEventKind { Increment, Decrement }

@JvmInline
internal value class ArcMatchingSetGuaranteedScopeId(val name: String)

internal sealed class ArcMatchingSetGuaranteedToken {
    /** Authenticated +0 parameter/receiver scope supplied by the ABI adapter. */
    data class AbiScope(val id: ArcMatchingSetGuaranteedScopeId) : ArcMatchingSetGuaranteedToken()

    /** Authenticated lexical borrow already verified by ownership slot flow. */
    data class Borrow(val id: ArcSSABorrowId) : ArcMatchingSetGuaranteedToken()
}

/**
 * Exact ownership-token evidence, authenticated before matching-set analysis.
 *
 * [coveredDecrements] is deliberately explicit. RC-family liveness is insufficient because a
 * different alias can keep the object live after this exact ownership token has been consumed.
 */
internal sealed class ArcMatchingSetLifetimeWitness {
    abstract val anchor: ArcSSAValue
    abstract val coveredDecrements: Set<ArcMatchingSetEventId>

    data class Immortal(
        override val anchor: ArcSSAValue,
        override val coveredDecrements: Set<ArcMatchingSetEventId>,
    ) : ArcMatchingSetLifetimeWitness()

    data class OwningSlot(
        override val anchor: ArcSSAValue,
        val slot: ArcSSASlot,
        val version: ArcSSASlotVersion,
        override val coveredDecrements: Set<ArcMatchingSetEventId>,
    ) : ArcMatchingSetLifetimeWitness()

    data class Guaranteed(
        override val anchor: ArcSSAValue,
        val token: ArcMatchingSetGuaranteedToken,
        override val coveredDecrements: Set<ArcMatchingSetEventId>,
    ) : ArcMatchingSetLifetimeWitness()
}

/**
 * A potential ARC increment/decrement emitted for [value].
 *
 * Every increment must carry a distinct exact-token [lifetimeWitness]. Its anchor is not the
 * increment result: it is independently owned, guaranteed, or immortal and proves that deleting a
 * covered matched decrement cannot make that decrement final. Decrements carry no witness.
 */
internal data class ArcMatchingSetEvent(
    val id: ArcMatchingSetEventId,
    val position: ArcRCPosition,
    val kind: ArcMatchingSetEventKind,
    val value: ArcSSAValue,
    val lifetimeWitness: ArcMatchingSetLifetimeWitness? = null,
)

/**
 * Trusted compiler-adapter input only.
 *
 * The future IR adapter must authenticate every [events] entry as the stable identity of a real
 * copy or destroy node which would emit the corresponding RC operation. This proof must never be
 * invoked with event kinds synthesized from user input, inferred from names, or rediscovered by
 * codegen pattern matching. It must also derive every lifetime witness and decrement-coverage set
 * from verified [slotFlow], lexical borrow scope, or the ABI's guaranteed scope--never from RC
 * family liveness or a boolean rediscovered in codegen. A later emitter may consume only the exact
 * IDs returned in [ArcMatchingSetResult.eliminatedEvents].
 */
internal data class ArcMatchingSetInput(
    val cfg: ArcOwnershipSSAInput,
    val identities: ArcRCIdentityResult,
    val slotFlow: ArcSSASlotFlowResult,
    val events: List<ArcMatchingSetEvent>,
)

internal enum class ArcMatchingSetRejectionReason {
    MalformedInput,
    UnresolvedIdentity,
    MissingLifetimeWitness,
    WitnessKindMismatch,
    WitnessIdentityMismatch,
    WitnessDoesNotDominate,
    WitnessDoesNotCoverDecrement,
    WitnessNotLiveThroughDecrement,
    OwnershipBarrier,
    CriticalEdge,
    PathWithoutMatch,
    CycleWithoutMatch,
    NonReducibleLoop,
    NonZeroPathDelta,
}

internal data class ArcMatchingSetRejection(
    val increment: ArcMatchingSetEventId?,
    val reason: ArcMatchingSetRejectionReason,
    val detail: String,
)

/** One mutually closed set. All its events must be deleted atomically or not at all. */
internal data class ArcMatchingSet(
    val increments: Set<ArcMatchingSetEventId>,
    val decrements: Set<ArcMatchingSetEventId>,
    val anchors: Set<ArcSSAValue>,
    /** The zero-based fixed-point round in which this set became visible. */
    val round: Int,
)

internal data class ArcMatchingSetResult(
    val accepted: List<ArcMatchingSet>,
    val rejected: List<ArcMatchingSetRejection>,
) {
    val eliminatedEvents: Set<ArcMatchingSetEventId>
        get() = accepted.flatMapTo(linkedSetOf()) { it.increments + it.decrements }
}

/**
 * Fail-closed, emission-independent ARC sequence proof.
 *
 * This adapts Swift's bidirectional matching-set closure to Kotlin's explicit ownership SSA. The
 * analysis repeatedly computes the first decrements reachable from every increment and the first
 * increments reachable backwards from every decrement. A set is accepted only after that relation
 * reaches mutual closure, an independent anchor is live through every decrement, and a path-count
 * verifier proves zero net RC delta at every join, backedge and exit.
 *
 * No operation is moved. Accepted sets merely authorize a later integration to delete the complete
 * set atomically. Running to a fixed point exposes outer pairs after an inner pair is removed.
 */
internal object ArcMatchingSetClosure {
    private data class Definition(
        val position: ArcRCPosition,
        val operation: ArcSSAOperation,
    )

    private data class Point(val block: ArcBlockId, val operationIndex: Int)

    private data class SearchResult(
        val matches: Set<ArcMatchingSetEvent>,
        val rejection: ArcMatchingSetRejection? = null,
    )

    private data class CandidateResult(
        val set: ArcMatchingSet? = null,
        val rejection: ArcMatchingSetRejection? = null,
    )

    fun analyze(input: ArcMatchingSetInput): ArcMatchingSetResult {
        val globalRejections = validateInput(input)
        if (globalRejections.isNotEmpty()) return ArcMatchingSetResult(emptyList(), globalRejections)

        val definitions = definitions(input.cfg)
        val dominators = computeDominators(input.cfg)
        val irreducibleBlocks = irreducibleLoopBlocks(input.cfg, dominators)
        val active = input.events.associateByTo(linkedMapOf()) { it.id }
        val accepted = mutableListOf<ArcMatchingSet>()
        val attemptedRejections = linkedMapOf<ArcMatchingSetEventId, ArcMatchingSetRejection>()
        var round = 0

        while (true) {
            var selected: ArcMatchingSet? = null
            active.values.asSequence()
                .filter { it.kind == ArcMatchingSetEventKind.Increment }
                .sortedWith(eventOrder(input.cfg))
                .forEach { increment ->
                    if (selected != null) return@forEach
                    val candidate = proveCandidate(
                        input,
                        increment,
                        active.values.toList(),
                        definitions,
                        dominators,
                        irreducibleBlocks,
                        round,
                    )
                    candidate.rejection?.let { attemptedRejections.putIfAbsent(increment.id, it) }
                    if (candidate.set != null) selected = candidate.set
                }
            val matched = selected ?: break
            accepted += matched
            matched.increments.forEach(attemptedRejections::remove)
            matched.increments.forEach(active::remove)
            matched.decrements.forEach(active::remove)
            round++
        }

        val rejected = active.values.asSequence()
            .filter { it.kind == ArcMatchingSetEventKind.Increment }
            .sortedWith(eventOrder(input.cfg))
            .map { increment ->
                attemptedRejections[increment.id] ?: proveCandidate(
                    input,
                    increment,
                    active.values.toList(),
                    definitions,
                    dominators,
                    irreducibleBlocks,
                    round,
                ).rejection ?: ArcMatchingSetRejection(
                    increment.id,
                    ArcMatchingSetRejectionReason.PathWithoutMatch,
                    "increment has no mutually closed decrement set",
                )
            }
            .toList()
        return ArcMatchingSetResult(accepted, rejected)
    }

    private fun proveCandidate(
        input: ArcMatchingSetInput,
        seed: ArcMatchingSetEvent,
        activeEvents: List<ArcMatchingSetEvent>,
        definitions: Map<ArcSSAValue, Definition>,
        dominators: Map<ArcBlockId, Set<ArcBlockId>>,
        irreducibleBlocks: Set<ArcBlockId>,
        round: Int,
    ): CandidateResult {
        if (seed.position.block in irreducibleBlocks) {
            return rejected(seed, ArcMatchingSetRejectionReason.NonReducibleLoop, "candidate is inside a loop without one dominating header")
        }
        val increments = linkedSetOf(seed)
        val decrements = linkedSetOf<ArcMatchingSetEvent>()
        var changed = true
        while (changed) {
            changed = false
            increments.toList().forEach { increment ->
                val search = searchFirstMatches(input, activeEvents, increment, ArcMatchingSetEventKind.Decrement, forward = true)
                search.rejection?.let { return CandidateResult(rejection = it.copy(increment = seed.id)) }
                if (search.matches.isEmpty()) {
                    return rejected(seed, ArcMatchingSetRejectionReason.PathWithoutMatch, "no decrement is reachable from ${increment.id}")
                }
                if (decrements.addAll(search.matches)) changed = true
            }
            decrements.toList().forEach { decrement ->
                val search = searchFirstMatches(input, activeEvents, decrement, ArcMatchingSetEventKind.Increment, forward = false)
                search.rejection?.let { return CandidateResult(rejection = it.copy(increment = seed.id)) }
                if (search.matches.isEmpty()) {
                    return rejected(seed, ArcMatchingSetRejectionReason.PathWithoutMatch, "no increment reaches ${decrement.id}")
                }
                if (increments.addAll(search.matches)) changed = true
            }
        }

        val family = increments + decrements
        if (family.any { !sameIdentity(seed.value, it.value, input.identities) }) {
            return rejected(seed, ArcMatchingSetRejectionReason.UnresolvedIdentity, "matching closure contains more than one canonical RC identity")
        }
        if (family.any { it.position.block in irreducibleBlocks }) {
            return rejected(seed, ArcMatchingSetRejectionReason.NonReducibleLoop, "matching closure crosses a loop without one dominating header")
        }

        val anchors = linkedSetOf<ArcSSAValue>()
        increments.forEach { increment ->
            val witness = increment.lifetimeWitness
                ?: return rejected(seed, ArcMatchingSetRejectionReason.MissingLifetimeWitness, "${increment.id} has no exact-token lifetime witness")
            val anchor = witness.anchor
            if (anchor == increment.value) {
                return rejected(seed, ArcMatchingSetRejectionReason.MissingLifetimeWitness, "${increment.id} reuses its increment result as the witness anchor")
            }
            if (!sameIdentity(increment.value, anchor, input.identities)) {
                return rejected(seed, ArcMatchingSetRejectionReason.WitnessIdentityMismatch, "$anchor is not the RC identity retained by ${increment.id}")
            }
            val definition = definitions[anchor]
                ?: return rejected(seed, ArcMatchingSetRejectionReason.MissingLifetimeWitness, "$anchor has no ownership-SSA definition")
            if (!dominates(definition.position, increment.position, dominators)) {
                return rejected(seed, ArcMatchingSetRejectionReason.WitnessDoesNotDominate, "$anchor does not dominate ${increment.id}")
            }
            validateWitnessKind(input, witness, definitions)?.let { (reason, detail) ->
                return rejected(seed, reason, detail)
            }
            val witnessStart = witnessStartPosition(input.cfg, witness, definition.position)
                ?: return rejected(seed, ArcMatchingSetRejectionReason.WitnessKindMismatch, "exact witness introduction is missing")
            if (!dominates(witnessStart, increment.position, dominators)) {
                return rejected(seed, ArcMatchingSetRejectionReason.WitnessDoesNotDominate, "exact witness token does not dominate ${increment.id}")
            }
            decrements.forEach { decrement ->
                if (decrement.id !in witness.coveredDecrements) {
                    return rejected(
                        seed,
                        ArcMatchingSetRejectionReason.WitnessDoesNotCoverDecrement,
                        "${increment.id}'s exact-token witness does not cover ${decrement.id}",
                    )
                }
                if (!dominates(definition.position, decrement.position, dominators)) {
                    return rejected(seed, ArcMatchingSetRejectionReason.WitnessDoesNotDominate, "$anchor does not dominate ${decrement.id}")
                }
                if (!dominates(witnessStart, decrement.position, dominators)) {
                    return rejected(seed, ArcMatchingSetRejectionReason.WitnessDoesNotDominate, "exact witness token does not dominate ${decrement.id}")
                }
                if (!witnessIsActiveThrough(input, witness, witnessStart, decrement.position)) {
                    return rejected(
                        seed,
                        ArcMatchingSetRejectionReason.WitnessNotLiveThroughDecrement,
                        "the exact ownership token for $anchor ends before ${decrement.id}",
                    )
                }
            }
            anchors += anchor
        }

        val ids = family.mapTo(linkedSetOf()) { it.id }
        verifyZeroDelta(input, ids)?.let { return CandidateResult(rejection = it.copy(increment = seed.id)) }
        return CandidateResult(
            set = ArcMatchingSet(
                increments.mapTo(linkedSetOf()) { it.id },
                decrements.mapTo(linkedSetOf()) { it.id },
                anchors,
                round,
            )
        )
    }

    private fun searchFirstMatches(
        input: ArcMatchingSetInput,
        activeEvents: List<ArcMatchingSetEvent>,
        start: ArcMatchingSetEvent,
        targetKind: ArcMatchingSetEventKind,
        forward: Boolean,
    ): SearchResult {
        val eventAt = activeEvents.associateBy { it.position }
        val startPoint = Point(start.position.block, start.position.operationIndex + if (forward) 1 else -1)
        val worklist = ArrayDeque<Point>().apply { add(startPoint) }
        val visited = linkedSetOf<Point>()
        val adjacency = linkedMapOf<Point, Set<Point>>()
        val matches = linkedSetOf<ArcMatchingSetEvent>()

        while (worklist.isNotEmpty()) {
            val point = worklist.removeFirst()
            if (!visited.add(point)) continue
            val block = input.cfg.blocks.getValue(point.block)
            if (point.operationIndex in block.operations.indices) {
                val event = eventAt[ArcRCPosition(point.block, point.operationIndex)]
                if (event != null) {
                    if (!sameIdentity(start.value, event.value, input.identities)) {
                        return SearchResult(emptySet(), ArcMatchingSetRejection(
                            start.takeIf { it.kind == ArcMatchingSetEventKind.Increment }?.id,
                            ArcMatchingSetRejectionReason.OwnershipBarrier,
                            "unproven aliasing RC event ${event.id} blocks ${start.id}",
                        ))
                    }
                    if (event.kind == targetKind) {
                        matches += event
                        adjacency[point] = emptySet()
                        continue
                    }
                }

                val barrier = input.identities.barriers.firstOrNull { it.position == ArcRCPosition(point.block, point.operationIndex) }
                if (barrier != null && barrier.kind !in setOf(ArcRCBarrierKind.ControlJoin, ArcRCBarrierKind.ExceptionalCall)) {
                    return SearchResult(emptySet(), ArcMatchingSetRejection(
                        start.takeIf { it.kind == ArcMatchingSetEventKind.Increment }?.id,
                        ArcMatchingSetRejectionReason.OwnershipBarrier,
                        "${barrier.kind} at ${barrier.position} blocks matching",
                    ))
                }

                val next = linkedSetOf(Point(point.block, point.operationIndex + if (forward) 1 else -1))
                if (forward && barrier?.kind == ArcRCBarrierKind.ExceptionalCall) {
                    val exceptional = input.cfg.edges.filter { it.from == point.block && it.kind == ArcSSAEdgeKind.Exceptional }
                    if (exceptional.size != 1) {
                        return SearchResult(emptySet(), ArcMatchingSetRejection(
                            start.takeIf { it.kind == ArcMatchingSetEventKind.Increment }?.id,
                            ArcMatchingSetRejectionReason.PathWithoutMatch,
                            "exceptional call at ${barrier.position} has no exact cleanup edge",
                        ))
                    }
                    val edge = exceptional.single()
                    if (isCritical(edge, input.cfg)) {
                        return SearchResult(emptySet(), ArcMatchingSetRejection(
                            start.takeIf { it.kind == ArcMatchingSetEventKind.Increment }?.id,
                            ArcMatchingSetRejectionReason.CriticalEdge,
                            "matching requires unsplit critical edge ${edge.from} -> ${edge.to}",
                        ))
                    }
                    next += Point(edge.to, 0)
                }
                adjacency[point] = next
                next.forEach(worklist::add)
                continue
            }

            val edges = if (forward) {
                input.cfg.edges.filter { it.from == point.block && it.kind == ArcSSAEdgeKind.Normal }
            } else {
                input.cfg.edges.filter { it.to == point.block }
            }
            if (edges.isEmpty()) {
                return SearchResult(emptySet(), ArcMatchingSetRejection(
                    start.takeIf { it.kind == ArcMatchingSetEventKind.Increment }?.id,
                    ArcMatchingSetRejectionReason.PathWithoutMatch,
                    "a ${if (forward) "successor" else "predecessor"} path reaches the CFG boundary without a match",
                ))
            }
            val next = linkedSetOf<Point>()
            edges.forEach { edge ->
                if (isCritical(edge, input.cfg)) {
                    return SearchResult(emptySet(), ArcMatchingSetRejection(
                        start.takeIf { it.kind == ArcMatchingSetEventKind.Increment }?.id,
                        ArcMatchingSetRejectionReason.CriticalEdge,
                        "matching requires unsplit critical edge ${edge.from} -> ${edge.to}",
                    ))
                }
                if (forward) {
                    next += Point(edge.to, 0)
                } else if (edge.kind == ArcSSAEdgeKind.Exceptional) {
                    val throwing = throwingOperationIndex(input.cfg, edge.from)
                        ?: return SearchResult(emptySet(), ArcMatchingSetRejection(
                            start.takeIf { it.kind == ArcMatchingSetEventKind.Increment }?.id,
                            ArcMatchingSetRejectionReason.PathWithoutMatch,
                            "exceptional predecessor ${edge.from} has no unique throwing operation",
                        ))
                    next += Point(edge.from, throwing - 1)
                } else {
                    next += Point(edge.from, input.cfg.blocks.getValue(edge.from).operations.lastIndex)
                }
            }
            adjacency[point] = next
            next.forEach(worklist::add)
        }

        if (hasCycle(adjacency)) {
            return SearchResult(emptySet(), ArcMatchingSetRejection(
                start.takeIf { it.kind == ArcMatchingSetEventKind.Increment }?.id,
                ArcMatchingSetRejectionReason.CycleWithoutMatch,
                "a reachable cycle can avoid the first matching ${targetKind.name.lowercase()}",
            ))
        }
        return SearchResult(matches)
    }

    /** Exact path-count verifier; unequal join/backedge facts or non-zero exits fail closed. */
    private fun verifyZeroDelta(input: ArcMatchingSetInput, candidate: Set<ArcMatchingSetEventId>): ArcMatchingSetRejection? {
        val eventAt = input.events.filter { it.id in candidate }.associateBy { it.position }
        val entryCount = linkedMapOf(input.cfg.entry to 0)
        val worklist = ArrayDeque<ArcBlockId>().apply { add(input.cfg.entry) }
        val reachable = linkedSetOf<ArcBlockId>()
        while (worklist.isNotEmpty()) {
            val blockId = worklist.removeFirst()
            reachable += blockId
            var count = entryCount.getValue(blockId)
            val block = input.cfg.blocks.getValue(blockId)
            block.operations.forEachIndexed { index, _ ->
                val event = eventAt[ArcRCPosition(blockId, index)]
                if (event != null) {
                    count += if (event.kind == ArcMatchingSetEventKind.Increment) 1 else -1
                    if (count < 0) return ArcMatchingSetRejection(
                        null,
                        ArcMatchingSetRejectionReason.NonZeroPathDelta,
                        "candidate decrements before it owns a matching +1 at ${event.position}",
                    )
                }
                if (input.identities.barriers.any {
                        it.position == ArcRCPosition(blockId, index) && it.kind == ArcRCBarrierKind.ExceptionalCall
                    }
                ) {
                    input.cfg.edges.filter { it.from == blockId && it.kind == ArcSSAEdgeKind.Exceptional }.forEach { edge ->
                        propagateCount(edge.to, count, entryCount, worklist)?.let { return it }
                    }
                }
            }
            val normalSuccessors = input.cfg.edges.filter { it.from == blockId && it.kind == ArcSSAEdgeKind.Normal }
            if (normalSuccessors.isEmpty()) {
                if (count != 0) return ArcMatchingSetRejection(
                    null,
                    ArcMatchingSetRejectionReason.NonZeroPathDelta,
                    "CFG exit $blockId has candidate RC delta $count",
                )
            }
            normalSuccessors.forEach { edge ->
                propagateCount(edge.to, count, entryCount, worklist)?.let { return it }
            }
        }
        val unreachableCandidate = eventAt.values.firstOrNull { it.position.block !in reachable }
        if (unreachableCandidate != null) return ArcMatchingSetRejection(
            null,
            ArcMatchingSetRejectionReason.MalformedInput,
            "candidate event ${unreachableCandidate.id} is unreachable",
        )
        return null
    }

    private fun propagateCount(
        target: ArcBlockId,
        count: Int,
        entryCount: MutableMap<ArcBlockId, Int>,
        worklist: ArrayDeque<ArcBlockId>,
    ): ArcMatchingSetRejection? {
        val previous = entryCount[target]
        if (previous != null && previous != count) return ArcMatchingSetRejection(
            null,
            ArcMatchingSetRejectionReason.NonZeroPathDelta,
            "join/backedge $target receives candidate RC deltas $previous and $count",
        )
        if (previous == null) {
            entryCount[target] = count
            worklist += target
        }
        return null
    }

    private fun validateInput(input: ArcMatchingSetInput): List<ArcMatchingSetRejection> = buildList {
        if (input.cfg.entry !in input.cfg.blocks || input.cfg.edges.any { it.from !in input.cfg.blocks || it.to !in input.cfg.blocks }) {
            add(ArcMatchingSetRejection(null, ArcMatchingSetRejectionReason.MalformedInput, "CFG contains a missing entry or edge block"))
        }
        if (input.identities.issues.isNotEmpty()) {
            add(ArcMatchingSetRejection(null, ArcMatchingSetRejectionReason.UnresolvedIdentity, "RC identity analysis has unresolved issues"))
        }
        val duplicateDefinitions = input.cfg.blocks.values
            .flatMap { block -> block.operations.mapNotNull { operation -> operation.resultOrNull() } }
            .groupingBy { it }
            .eachCount()
            .filterValues { it != 1 }
            .keys
        if (duplicateDefinitions.isNotEmpty()) {
            add(ArcMatchingSetRejection(
                null,
                ArcMatchingSetRejectionReason.MalformedInput,
                "ownership SSA contains duplicate definitions: ${duplicateDefinitions.joinToString()}",
            ))
        }
        val duplicateIds = input.events.groupBy { it.id }.filterValues { it.size != 1 }.keys
        val duplicatePositions = input.events.groupBy { it.position }.filterValues { it.size != 1 }.keys
        if (duplicateIds.isNotEmpty() || duplicatePositions.isNotEmpty()) {
            add(ArcMatchingSetRejection(null, ArcMatchingSetRejectionReason.MalformedInput, "event IDs and positions must be unique"))
        }
        input.events.forEach { event ->
            val operationCount = input.cfg.blocks[event.position.block]?.operations?.size
            if (operationCount == null || event.position.operationIndex !in 0 until operationCount) {
                add(ArcMatchingSetRejection(event.id, ArcMatchingSetRejectionReason.MalformedInput, "${event.position} is not an operation"))
            }
            if (input.identities.identity(event.value) == null) {
                add(ArcMatchingSetRejection(event.id, ArcMatchingSetRejectionReason.UnresolvedIdentity, "${event.value} has no canonical RC identity"))
            }
            if (event.kind == ArcMatchingSetEventKind.Increment && event.lifetimeWitness == null) {
                add(ArcMatchingSetRejection(event.id, ArcMatchingSetRejectionReason.MissingLifetimeWitness, "increment has no exact-token witness"))
            }
            if (event.kind == ArcMatchingSetEventKind.Decrement && event.lifetimeWitness != null) {
                add(ArcMatchingSetRejection(event.id, ArcMatchingSetRejectionReason.MalformedInput, "decrement may not carry a lifetime witness"))
            }
        }
    }

    private fun validateWitnessKind(
        input: ArcMatchingSetInput,
        witness: ArcMatchingSetLifetimeWitness,
        definitions: Map<ArcSSAValue, Definition>,
    ): Pair<ArcMatchingSetRejectionReason, String>? {
        val ownership = ownershipOf(witness.anchor, definitions)
        return when (witness) {
            is ArcMatchingSetLifetimeWitness.Immortal -> if (ownership == ArcOwnership.Immortal) null else
                ArcMatchingSetRejectionReason.WitnessKindMismatch to "${witness.anchor} is not an immortal introduction"
            is ArcMatchingSetLifetimeWitness.Guaranteed -> {
                if (ownership != ArcOwnership.Guaranteed) {
                    ArcMatchingSetRejectionReason.WitnessKindMismatch to "${witness.anchor} is not guaranteed"
                } else when (val token = witness.token) {
                    is ArcMatchingSetGuaranteedToken.AbiScope -> if (token.id.name.isNotBlank()) null else
                        ArcMatchingSetRejectionReason.WitnessKindMismatch to "guaranteed ABI scope has no stable identity"
                    is ArcMatchingSetGuaranteedToken.Borrow -> {
                        val matchingBorrow = input.cfg.blocks.values.asSequence()
                            .flatMap { it.operations.asSequence() }
                            .filterIsInstance<ArcSSAOperation.Borrow>()
                            .any { it.borrowId == token.id && it.result == witness.anchor }
                        if (matchingBorrow) null else
                            ArcMatchingSetRejectionReason.WitnessKindMismatch to "${token.id} does not introduce ${witness.anchor}"
                    }
                }
            }
            is ArcMatchingSetLifetimeWitness.OwningSlot -> {
                val matchingInitialization = input.cfg.blocks.values.asSequence()
                    .flatMap { it.operations.asSequence() }
                    .filterIsInstance<ArcSSAOperation.InitializeOwned>()
                    .any { it.slot == witness.slot && it.version == witness.version && it.value == witness.anchor }
                when {
                    !input.slotFlow.verified -> ArcMatchingSetRejectionReason.WitnessKindMismatch to "owning slot witness has no verified slot-flow proof"
                    ownership != ArcOwnership.Owned -> ArcMatchingSetRejectionReason.WitnessKindMismatch to "${witness.anchor} is not owned"
                    !matchingInitialization -> ArcMatchingSetRejectionReason.WitnessKindMismatch to
                            "${witness.slot}/${witness.version} does not initialize ${witness.anchor}"
                    else -> null
                }
            }
        }
    }

    private fun witnessIsActiveThrough(
        input: ArcMatchingSetInput,
        witness: ArcMatchingSetLifetimeWitness,
        witnessDefinition: ArcRCPosition,
        decrement: ArcRCPosition,
    ): Boolean = when (witness) {
        is ArcMatchingSetLifetimeWitness.Immortal -> true
        is ArcMatchingSetLifetimeWitness.Guaranteed -> when (val token = witness.token) {
            is ArcMatchingSetGuaranteedToken.AbiScope -> true
            is ArcMatchingSetGuaranteedToken.Borrow -> !lifetimeEndCanReach(
                input.cfg,
                witnessDefinition,
                decrement,
            ) { operation -> operation is ArcSSAOperation.EndBorrow && operation.borrowId == token.id }
        }
        is ArcMatchingSetLifetimeWitness.OwningSlot -> !lifetimeEndCanReach(
            input.cfg,
            witnessDefinition,
            decrement,
        ) { operation ->
            (operation is ArcSSAOperation.DestroyOwned && operation.slot == witness.slot && operation.version == witness.version) ||
                    (operation is ArcSSAOperation.MoveOwned && operation.sourceSlot == witness.slot && operation.sourceVersion == witness.version)
        }
    }

    private fun witnessStartPosition(
        cfg: ArcOwnershipSSAInput,
        witness: ArcMatchingSetLifetimeWitness,
        anchorDefinition: ArcRCPosition,
    ): ArcRCPosition? = when (witness) {
        is ArcMatchingSetLifetimeWitness.Immortal,
        is ArcMatchingSetLifetimeWitness.Guaranteed -> anchorDefinition
        is ArcMatchingSetLifetimeWitness.OwningSlot -> cfg.blocks.values.asSequence()
            .flatMap { block -> block.operations.withIndex().asSequence().map { block.id to it } }
            .firstOrNull { (_, indexed) ->
                val operation = indexed.value
                operation is ArcSSAOperation.InitializeOwned && operation.slot == witness.slot &&
                        operation.version == witness.version && operation.value == witness.anchor
            }
            ?.let { (block, indexed) -> ArcRCPosition(block, indexed.index) }
    }

    /** Rejects when an exact token end is reachable after its definition and before [target]. */
    private fun lifetimeEndCanReach(
        cfg: ArcOwnershipSSAInput,
        definition: ArcRCPosition,
        target: ArcRCPosition,
        isLifetimeEnd: (ArcSSAOperation) -> Boolean,
    ): Boolean {
        val reachableFromDefinition = reachablePositions(cfg, Point(definition.block, definition.operationIndex + 1), forward = true)
        val canReachTarget = reachablePositions(cfg, Point(target.block, target.operationIndex - 1), forward = false)
        return cfg.blocks.values.any { block ->
            block.operations.indices.any { index ->
                val point = Point(block.id, index)
                point in reachableFromDefinition && point in canReachTarget && isLifetimeEnd(block.operations[index])
            }
        }
    }

    private fun reachablePositions(cfg: ArcOwnershipSSAInput, start: Point, forward: Boolean): Set<Point> {
        val result = linkedSetOf<Point>()
        val worklist = ArrayDeque<Point>().apply { add(start) }
        while (worklist.isNotEmpty()) {
            val point = worklist.removeFirst()
            if (!result.add(point)) continue
            val block = cfg.blocks.getValue(point.block)
            if (point.operationIndex in block.operations.indices) {
                worklist += Point(point.block, point.operationIndex + if (forward) 1 else -1)
                continue
            }
            val edges = if (forward) cfg.edges.filter { it.from == point.block } else cfg.edges.filter { it.to == point.block }
            edges.forEach { edge ->
                worklist += if (forward) Point(edge.to, 0) else
                    Point(edge.from, cfg.blocks.getValue(edge.from).operations.lastIndex)
            }
        }
        return result
    }

    private fun definitions(cfg: ArcOwnershipSSAInput): Map<ArcSSAValue, Definition> = buildMap {
        cfg.blocks.values.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                operation.resultOrNull()?.let { put(it, Definition(ArcRCPosition(block.id, index), operation)) }
            }
        }
    }

    private fun ownershipOf(value: ArcSSAValue, definitions: Map<ArcSSAValue, Definition>, seen: MutableSet<ArcSSAValue> = linkedSetOf()): ArcOwnership? {
        if (!seen.add(value)) return null
        return when (val operation = definitions[value]?.operation) {
            is ArcSSAOperation.Introduce -> operation.ownership
            is ArcSSAOperation.Forward -> ownershipOf(operation.source, definitions, seen)
            is ArcSSAOperation.Reborrow, is ArcSSAOperation.Borrow -> ArcOwnership.Guaranteed
            is ArcSSAOperation.Join -> operation.incoming.values.map { ownershipOf(it, definitions, seen.toMutableSet()) }.distinct().singleOrNull()
            else -> null
        }
    }

    private fun sameIdentity(left: ArcSSAValue, right: ArcSSAValue, identities: ArcRCIdentityResult): Boolean {
        val leftIdentity = identities.identity(left) ?: return false
        val rightIdentity = identities.identity(right) ?: return false
        val exactOwnershipWeb = leftIdentity.ownershipWeb != null && leftIdentity.ownershipWeb === rightIdentity.ownershipWeb
        val exactNonEmptyRoots = leftIdentity.provenanceRoots.isNotEmpty() &&
                leftIdentity.provenanceRoots == rightIdentity.provenanceRoots
        return exactOwnershipWeb || exactNonEmptyRoots
    }

    private fun dominates(definition: ArcRCPosition, use: ArcRCPosition, dominators: Map<ArcBlockId, Set<ArcBlockId>>): Boolean =
        definition.block in dominators.getValue(use.block) &&
                (definition.block != use.block || definition.operationIndex < use.operationIndex)

    private fun computeDominators(cfg: ArcOwnershipSSAInput): Map<ArcBlockId, Set<ArcBlockId>> {
        val all = cfg.blocks.keys
        val result = all.associateWithTo(linkedMapOf()) { if (it == cfg.entry) setOf(it) else all.toSet() }
        var changed = true
        while (changed) {
            changed = false
            all.filter { it != cfg.entry }.forEach { block ->
                val predecessors = cfg.edges.filter { it.to == block }.map { it.from }
                val next = if (predecessors.isEmpty()) setOf(block) else
                    predecessors.map { result.getValue(it) }.reduce { common, predecessor -> common intersect predecessor } + block
                if (result[block] != next) {
                    result[block] = next
                    changed = true
                }
            }
        }
        return result
    }

    /** Cyclic SCCs must have one entry header which dominates every member. */
    private fun irreducibleLoopBlocks(
        cfg: ArcOwnershipSSAInput,
        dominators: Map<ArcBlockId, Set<ArcBlockId>>,
    ): Set<ArcBlockId> {
        val index = mutableMapOf<ArcBlockId, Int>()
        val lowLink = mutableMapOf<ArcBlockId, Int>()
        val stack = ArrayDeque<ArcBlockId>()
        val onStack = mutableSetOf<ArcBlockId>()
        val components = mutableListOf<Set<ArcBlockId>>()
        var nextIndex = 0
        fun visit(block: ArcBlockId) {
            index[block] = nextIndex
            lowLink[block] = nextIndex++
            stack.addLast(block)
            onStack += block
            cfg.edges.filter { it.from == block }.forEach { edge ->
                if (edge.to !in index) {
                    visit(edge.to)
                    lowLink[block] = minOf(lowLink.getValue(block), lowLink.getValue(edge.to))
                } else if (edge.to in onStack) {
                    lowLink[block] = minOf(lowLink.getValue(block), index.getValue(edge.to))
                }
            }
            if (lowLink[block] == index[block]) {
                val component = linkedSetOf<ArcBlockId>()
                var member: ArcBlockId
                do {
                    member = stack.removeLast()
                    onStack -= member
                    component += member
                } while (member != block)
                components += component
            }
        }
        cfg.blocks.keys.forEach { if (it !in index) visit(it) }
        return components.flatMapTo(linkedSetOf()) { component ->
            val cyclic = component.size > 1 || cfg.edges.any { it.from in component && it.to == it.from }
            if (!cyclic) return@flatMapTo emptySet()
            val entryTargets = cfg.edges.filter { it.from !in component && it.to in component }.mapTo(linkedSetOf()) { it.to }
            val headers = if (cfg.entry in component) entryTargets + cfg.entry else entryTargets
            val header = headers.singleOrNull()
            if (header == null || component.any { header !in dominators.getValue(it) }) component else emptySet()
        }
    }

    private fun hasCycle(adjacency: Map<Point, Set<Point>>): Boolean {
        val visiting = mutableSetOf<Point>()
        val visited = mutableSetOf<Point>()
        fun visit(point: Point): Boolean {
            if (point in visiting) return true
            if (!visited.add(point)) return false
            visiting += point
            if (adjacency[point].orEmpty().any(::visit)) return true
            visiting -= point
            return false
        }
        return adjacency.keys.any(::visit)
    }

    private fun throwingOperationIndex(cfg: ArcOwnershipSSAInput, block: ArcBlockId): Int? =
        cfg.blocks.getValue(block).operations.indices.singleOrNull { index ->
            (cfg.blocks.getValue(block).operations[index] as? ArcSSAOperation.Use)?.mayThrow == true
        }

    private fun isCritical(edge: ArcSSAEdge, cfg: ArcOwnershipSSAInput): Boolean =
        cfg.edges.count { it.from == edge.from } > 1 && cfg.edges.count { it.to == edge.to } > 1

    private fun eventOrder(cfg: ArcOwnershipSSAInput): Comparator<ArcMatchingSetEvent> {
        val blockOrder = cfg.blocks.keys.withIndex().associate { it.value to it.index }
        return compareBy<ArcMatchingSetEvent>(
            { blockOrder[it.position.block] ?: Int.MAX_VALUE },
            { it.position.operationIndex },
            { it.id.name },
        )
    }

    private fun rejected(
        increment: ArcMatchingSetEvent,
        reason: ArcMatchingSetRejectionReason,
        detail: String,
    ) = CandidateResult(rejection = ArcMatchingSetRejection(increment.id, reason, detail))

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
}
