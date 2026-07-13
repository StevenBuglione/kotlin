/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

/**
 * General, emission-independent owned-to-guaranteed ownership-phi conversion.
 *
 * This is the Kotlin/Native counterpart of Swift SemanticARC's
 * `tryConvertOwnedPhisToGuaranteedPhis` plus the availability-boundary part of
 * `OwnershipLiveRange`. Unlike the first [ArcSemanticPhiWebAnalysis] slice, this analysis discovers
 * the complete connected ownership-phi component at a fixed point. Consequently, one component may
 * contain multiple owned copy introducers, forwarding definitions, nested or loop-carried phis, and
 * values which feed more than one phi.
 *
 * The result remains a proof record. It deliberately does not edit IR, split CFG edges, or delete
 * ownership operations. A future emitter must apply every rewrite in a plan atomically and rerun the
 * ownership verifier before accepting generated code.
 */
internal object ArcOwnedToGuaranteedPhiWebAnalysis {
    private data class Definition(val block: ArcBlockId, val index: Int, val operation: ArcSSAOperation)

    private data class ValueLiveness(
        val before: Map<ArcBlockId, List<Set<ArcSSAValue>>>,
        val after: Map<ArcBlockId, List<Set<ArcSSAValue>>>,
        val liveIn: Map<ArcBlockId, Set<ArcSSAValue>>,
        val liveOut: Map<ArcBlockId, Set<ArcSSAValue>>,
        val edgeLive: Map<ArcSSAEdge, Set<ArcSSAValue>>,
    ) {
        fun familyBefore(block: ArcBlockId, index: Int, members: Set<ArcSSAValue>): Boolean =
            before[block]?.getOrNull(index).orEmpty().any { it in members }

        fun familyAfter(block: ArcBlockId, index: Int, members: Set<ArcSSAValue>): Boolean =
            after[block]?.getOrNull(index).orEmpty().any { it in members }

        fun familyLiveIn(block: ArcBlockId, members: Set<ArcSSAValue>): Boolean =
            liveIn[block].orEmpty().any { it in members }

        fun familyLiveOut(block: ArcBlockId, members: Set<ArcSSAValue>): Boolean =
            liveOut[block].orEmpty().any { it in members }
    }

    private data class Component(
        val members: Set<ArcSSAValue>,
        val fixedPointIterations: Int,
    )

    fun analyze(rawInput: ArcSemanticARCInput): ArcSemanticARCResult {
        val implicitBarriers = rawInput.cfg.blocks.values.flatMapTo(linkedSetOf()) { block ->
            block.operations.mapIndexedNotNull { index, operation ->
                if (operation is ArcSSAOperation.DeinitBarrier) {
                    ArcSemanticARCBarrier(block.id, index, ArcSemanticARCBarrierKind.Deinitialization)
                } else null
            }
        }
        val input = rawInput.copy(barriers = rawInput.barriers + implicitBarriers)
        val definitions = linkedMapOf<ArcSSAValue, Definition>()
        val duplicates = linkedSetOf<ArcSSAValue>()
        var instructionCount = 0
        var useCount = 0
        input.cfg.blocks.values.sortedBy { it.id.name }.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                instructionCount++
                useCount += operation.phiWebOperands().size
                operation.phiWebResult()?.let { result ->
                    if (definitions.put(result, Definition(block.id, index, operation)) != null) duplicates += result
                }
            }
        }

        val malformed = input.cfg.entry !in input.cfg.blocks ||
                input.cfg.edges.any { it.from !in input.cfg.blocks || it.to !in input.cfg.blocks } ||
                input.deadEndBlocks.any { it !in input.cfg.blocks } ||
                input.exitLifetimeUses.any { it !in input.cfg.blocks || it in input.deadEndBlocks } ||
                input.barriers.any { barrier ->
                    barrier.block !in input.cfg.blocks ||
                            barrier.operationIndex !in 0..input.cfg.blocks.getValue(barrier.block).operations.size
                } ||
                input.budget.maximumInstructions < 1 || input.budget.maximumUses < 1 ||
                input.budget.maximumRewriteSteps < 1
        val overBudget = instructionCount > input.budget.maximumInstructions || useCount > input.budget.maximumUses
        val seedsByCopy = input.guaranteedCopies.groupBy { it.copy }
        val processed = linkedSetOf<ArcSSAValue>()
        val claimed = linkedSetOf<ArcSSAValue>()
        val accepted = mutableListOf<ArcSemanticPhiWebPlan>()
        val rejected = mutableListOf<ArcSemanticARCRejection>()

        input.guaranteedCopies.sortedWith(compareBy({ it.copy.name }, { it.guaranteedSource.name })).forEach { initialSeed ->
            if (initialSeed.copy in processed) return@forEach
            fun reject(reason: ArcSemanticARCRejectionReason, detail: String, component: Set<ArcSSAValue> = setOf(initialSeed.copy)) {
                val componentSeeds = input.guaranteedCopies.filter { it.copy in component }
                    .mapTo(linkedSetOf()) { it.copy }
                if (componentSeeds.isEmpty()) componentSeeds += initialSeed.copy
                rejected += ArcSemanticARCRejection(componentSeeds.minByOrNull { it.name } ?: initialSeed.copy, reason, detail)
                processed += componentSeeds
            }

            if (malformed || initialSeed.copy in duplicates) {
                reject(ArcSemanticARCRejectionReason.MalformedCFG, "CFG, barrier, budget, or SSA definition is malformed")
                return@forEach
            }
            if (overBudget) {
                reject(ArcSemanticARCRejectionReason.BudgetExhausted,
                    "graph has $instructionCount instructions and $useCount uses")
                return@forEach
            }
            if (seedsByCopy[initialSeed.copy]?.size != 1) {
                reject(ArcSemanticARCRejectionReason.ConflictingRCIdentity,
                    "one owned copy has more than one guaranteed source or anchor identity")
                return@forEach
            }

            val component = discoverComponentAtFixedPoint(initialSeed.copy, input.cfg, input.budget.maximumRewriteSteps)
            if (component == null) {
                reject(ArcSemanticARCRejectionReason.BudgetExhausted,
                    "ownership-phi fixed point exceeded ${input.budget.maximumRewriteSteps} steps")
                return@forEach
            }
            val members = component.members
            if (members.any { it !in definitions } || members.any { it in duplicates } || members.any { it in claimed }) {
                reject(ArcSemanticARCRejectionReason.ConflictingRCIdentity,
                    "phi web contains an undefined, duplicate, or previously claimed definition", members)
                return@forEach
            }
            val seeds = input.guaranteedCopies.filterTo(linkedSetOf()) { it.copy in members }
            processed += seeds.map { it.copy }
            if (seeds.isEmpty() || seeds.groupBy { it.copy }.any { it.value.size != 1 }) {
                reject(ArcSemanticARCRejectionReason.ConflictingRCIdentity,
                    "complete phi web has no unique guaranteed-copy introducers", members)
                return@forEach
            }

            val ownedIntroducers = members.mapNotNullTo(linkedSetOf()) { value ->
                (definitions[value]?.operation as? ArcSSAOperation.Introduce)
                    ?.takeIf { it.ownership == ArcOwnership.Owned }?.result
            }
            if (ownedIntroducers != seeds.mapTo(linkedSetOf()) { it.copy }) {
                reject(ArcSemanticARCRejectionReason.IncompletePhiWeb,
                    "every owned introducer in the fixed-point component must have one convertible guaranteed-copy seed", members)
                return@forEach
            }
            if (!members.all { isConvertibleDefinition(it, definitions) }) {
                reject(ArcSemanticARCRejectionReason.IncompletePhiWeb,
                    "component contains a definition that cannot forward guaranteed ownership", members)
                return@forEach
            }
            if (members.none { definitions[it]?.operation is ArcSSAOperation.Join }) {
                reject(ArcSemanticARCRejectionReason.IncompletePhiWeb,
                    "owned-to-guaranteed phi conversion requires at least one ownership join", members)
                return@forEach
            }

            val reachable = reachableBlocks(input.cfg)
            val dominators = computeDominators(input.cfg, reachable)
            if (!hasValidSSA(input.cfg, members, definitions, reachable, dominators)) {
                reject(ArcSemanticARCRejectionReason.MalformedCFG,
                    "phi predecessor coverage, reachability, or SSA dominance is invalid", members)
                return@forEach
            }

            val dependencyCache = linkedMapOf<ArcSSAValue, Set<ArcSSAValue>>()
            val invalidSeed = seeds.firstOrNull { seed ->
                val copyDefinition = definitions[seed.copy]
                val actualSourceDependencies = guaranteedDependenciesAtFixedPoint(
                    setOf(seed.guaranteedSource), definitions, emptyMap(), input.budget.maximumRewriteSteps,
                )?.get(seed.guaranteedSource)
                val claimedDependencies = seed.anchorDependencies.mapTo(linkedSetOf()) {
                    canonicalForwardRoot(it, definitions)
                }
                copyDefinition?.operation !is ArcSSAOperation.Introduce ||
                        (copyDefinition.operation as? ArcSSAOperation.Introduce)?.ownership != ArcOwnership.Owned ||
                        seed.guaranteedSource !in definitions || seed.guaranteedSource in members ||
                        seed.anchorDependencies.isEmpty() || seed.anchorDependencies.any { it !in definitions } ||
                        actualSourceDependencies == null || claimedDependencies != actualSourceDependencies ||
                        !dominates(seed.guaranteedSource, copyDefinition, definitions, dominators) ||
                        seed.anchorDependencies.any { !dominates(it, copyDefinition, definitions, dominators) }
            }
            if (invalidSeed != null) {
                reject(ArcSemanticARCRejectionReason.InvalidGuaranteedSeed,
                    "${invalidSeed.copy} is not an owned copy of one dominating guaranteed scope", members)
                return@forEach
            }

            val seedDependencies = seeds.associate { seed ->
                seed.copy to seed.anchorDependencies.mapTo(linkedSetOf()) { canonicalForwardRoot(it, definitions) }
            }
            val dependencies = guaranteedDependenciesAtFixedPoint(
                members, definitions, seedDependencies, input.budget.maximumRewriteSteps,
            )
            if (dependencies == null || members.any { dependencies[it].isNullOrEmpty() }) {
                reject(ArcSemanticARCRejectionReason.IncompletePhiWeb,
                    "forwarding or reborrow phi has no complete guaranteed lifetime dependency solution", members)
                return@forEach
            }
            dependencyCache.putAll(dependencies)

            val unsupportedEffect = firstUnsupportedEffect(input.cfg, members)
            if (unsupportedEffect != null) {
                reject(unsupportedEffect.first, unsupportedEffect.second, members)
                return@forEach
            }
            val identityResult = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(input.cfg, emptyList()))
            val identityIssue = identityResult.issues.firstOrNull { it.value in members && it.kind in setOf(
                ArcRCIdentityIssueKind.DuplicateDefinition,
                ArcRCIdentityIssueKind.MissingDefinition,
                ArcRCIdentityIssueKind.UseAfterConsume,
                ArcRCIdentityIssueKind.UnmodeledExceptionalConsume,
                ArcRCIdentityIssueKind.UnsupportedEscape,
                ArcRCIdentityIssueKind.UnsupportedUnknownConsume,
            ) }
            if (identityIssue != null) {
                reject(ArcSemanticARCRejectionReason.ConflictingRCIdentity,
                    "RC identity verification failed: ${identityIssue.kind}: ${identityIssue.detail}", members)
                return@forEach
            }
            if (!hasValidDestroyPaths(input.cfg, members, definitions, seeds.mapTo(linkedSetOf()) { it.copy })) {
                reject(ArcSemanticARCRejectionReason.MultipleConsumesOnPath,
                    "one owned provenance is destroyed twice or used after destruction on a reachable path", members)
                return@forEach
            }

            val liveness = computeValueLiveness(input, members, definitions, dominators)
            val crossedBarrier = input.barriers.firstOrNull { barrier ->
                val block = input.cfg.blocks.getValue(barrier.block)
                when (barrier.operationIndex) {
                    0 -> liveness.familyLiveIn(barrier.block, members) &&
                            liveness.familyBefore(barrier.block, 0, members)
                    block.operations.size -> liveness.familyLiveOut(barrier.block, members) &&
                            liveness.familyAfter(barrier.block, block.operations.lastIndex, members)
                    else -> liveness.familyAfter(barrier.block, barrier.operationIndex - 1, members) &&
                            liveness.familyBefore(barrier.block, barrier.operationIndex, members)
                }
            }
            if (crossedBarrier != null) {
                reject(ArcSemanticARCRejectionReason.BarrierCrossing,
                    "guaranteed phi lifetime crosses ${crossedBarrier.kind} at ${crossedBarrier.block}:${crossedBarrier.operationIndex}", members)
                return@forEach
            }
            val exceptionalProblem = exceptionalLifetimeProblem(input, members, liveness)
            if (exceptionalProblem != null) {
                reject(exceptionalProblem.first, exceptionalProblem.second, members)
                return@forEach
            }

            val joins = members.filterTo(linkedSetOf()) { definitions[it]?.operation is ArcSSAOperation.Join }
            val reborrows = buildReborrows(input.cfg, joins, definitions, dependencyCache)
            val expectedOperandCount = joins.sumOf { (definitions.getValue(it).operation as ArcSSAOperation.Join).incoming.size }
            if (reborrows.size != expectedOperandCount || reborrows.any { it.anchorDependencies.isEmpty() }) {
                reject(ArcSemanticARCRejectionReason.IncompletePhiWeb,
                    "not every ownership-phi operand has one edge-specific reborrow", members)
                return@forEach
            }
            val frontier = computeFrontier(input, members, liveness)
            val blockLiveness = input.cfg.blocks.keys.sortedBy { it.name }.associateWith { block ->
                when {
                    liveness.familyLiveOut(block, members) -> ArcPrunedBlockLiveness.LiveOut
                    liveness.familyLiveIn(block, members) ||
                            liveness.before[block].orEmpty().any { values -> values.any { it in members } } ||
                            liveness.after[block].orEmpty().any { values -> values.any { it in members } } ->
                        ArcPrunedBlockLiveness.LiveWithin
                    else -> ArcPrunedBlockLiveness.Dead
                }
            }
            val rewrites = buildList {
                add(ArcSemanticARCRewrite.PrepareJoinedWeb(joins, reborrows, frontier))
                seeds.sortedBy { it.copy.name }.forEach {
                    add(ArcSemanticARCRewrite.EliminateGuaranteedCopy(it.copy, it.guaranteedSource))
                }
            }
            if (rewrites.size + component.fixedPointIterations > input.budget.maximumRewriteSteps) {
                reject(ArcSemanticARCRejectionReason.BudgetExhausted,
                    "fixed-point discovery and atomic rewrite require ${rewrites.size + component.fixedPointIterations} steps", members)
                return@forEach
            }
            val plan = ArcSemanticPhiWebPlan(
                seeds = seeds,
                members = members,
                joins = joins,
                canonicalRCRoots = dependencies.values.flatten().toCollection(linkedSetOf()),
                reborrows = reborrows,
                liveness = blockLiveness,
                frontier = frontier,
                rewrites = rewrites,
                verificationEpochs = rewrites.size + 1,
            )
            if (!verifyPlan(input, plan, definitions, dependencies, liveness, component)) {
                reject(ArcSemanticARCRejectionReason.RewriteVerificationFailed,
                    "independent fixed-point, reborrow, liveness, frontier, or rewrite verification failed", members)
                return@forEach
            }
            accepted += plan
            claimed += members
        }
        return ArcSemanticARCResult(accepted, rejected)
    }

    private fun discoverComponentAtFixedPoint(
        initial: ArcSSAValue,
        cfg: ArcOwnershipSSAInput,
        maximumSteps: Int,
    ): Component? {
        val members = linkedSetOf(initial)
        var changed = true
        var iterations = 0
        while (changed) {
            if (++iterations > maximumSteps) return null
            changed = false
            cfg.blocks.values.sortedBy { it.id.name }.forEach { block ->
                block.operations.forEach { operation ->
                    val connection = when (operation) {
                        is ArcSSAOperation.Forward -> setOf(operation.source, operation.result)
                        is ArcSSAOperation.Reborrow -> setOf(operation.source, operation.result)
                        is ArcSSAOperation.Join -> operation.incoming.values + operation.result
                        else -> emptySet()
                    }
                    if (connection.any { it in members }) connection.sortedBy { it.name }.forEach {
                        if (members.add(it)) changed = true
                    }
                }
            }
        }
        return Component(members, iterations)
    }

    private fun isConvertibleDefinition(value: ArcSSAValue, definitions: Map<ArcSSAValue, Definition>): Boolean =
        when (val operation = definitions[value]?.operation) {
            is ArcSSAOperation.Introduce -> operation.ownership in setOf(
                ArcOwnership.Owned, ArcOwnership.Guaranteed, ArcOwnership.Immortal,
            )
            is ArcSSAOperation.Forward, is ArcSSAOperation.Reborrow, is ArcSSAOperation.Join -> true
            else -> false
        }

    private fun firstUnsupportedEffect(
        cfg: ArcOwnershipSSAInput,
        members: Set<ArcSSAValue>,
    ): Pair<ArcSemanticARCRejectionReason, String>? {
        cfg.blocks.values.sortedBy { it.id.name }.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                when (operation) {
                    is ArcSSAOperation.Use -> if (operation.value in members && operation.kind != ArcSSAUseKind.Borrow) {
                        return when (operation.kind) {
                            ArcSSAUseKind.Escape, ArcSSAUseKind.UnknownConsume -> ArcSemanticARCRejectionReason.BorrowEscape to
                                    "${operation.kind} at ${block.id}:$index"
                            ArcSSAUseKind.Consume -> ArcSemanticARCRejectionReason.MultipleConsumesOnPath to
                                    "non-destroy consuming use at ${block.id}:$index"
                            ArcSSAUseKind.Borrow -> error("covered above")
                        }
                    }
                    is ArcSSAOperation.Borrow -> if (operation.source in members || operation.result in members) {
                        return ArcSemanticARCRejectionReason.BorrowEscape to
                                "physical-slot borrow is outside the ownership-phi forwarding model at ${block.id}:$index"
                    }
                    is ArcSSAOperation.InitializeOwned,
                    is ArcSSAOperation.InitializeImmortal,
                    is ArcSSAOperation.JoinSlot,
                    is ArcSSAOperation.MoveOwned -> if (operation.phiWebOperands().any { it in members }) {
                        return ArcSemanticARCRejectionReason.BorrowEscape to
                                "slot ownership transfer at ${block.id}:$index"
                    }
                    is ArcSSAOperation.Introduce -> if (operation.result !in members &&
                        operation.anchorDependencies.any { it in members }) {
                        return ArcSemanticARCRejectionReason.BorrowEscape to
                                "member lifetime anchors an external guaranteed value at ${block.id}:$index"
                    }
                    else -> Unit
                }
            }
        }
        return null
    }

    private fun guaranteedDependenciesAtFixedPoint(
        values: Set<ArcSSAValue>,
        definitions: Map<ArcSSAValue, Definition>,
        seedDependencies: Map<ArcSSAValue, Set<ArcSSAValue>>,
        maximumSteps: Int,
    ): Map<ArcSSAValue, Set<ArcSSAValue>>? {
        val dependencies = linkedMapOf<ArcSSAValue, Set<ArcSSAValue>>()
        values.sortedBy { it.name }.forEach { value ->
            val operation = definitions[value]?.operation ?: return null
            when (operation) {
                is ArcSSAOperation.Introduce -> when (operation.ownership) {
                    ArcOwnership.Owned -> seedDependencies[value]?.let { dependencies[value] = it }
                    ArcOwnership.Guaranteed -> operation.anchorDependencies.takeIf { it.isNotEmpty() }?.let { anchors ->
                        dependencies[value] = anchors.mapTo(linkedSetOf()) { canonicalForwardRoot(it, definitions) }
                    }
                    ArcOwnership.Immortal -> dependencies[value] = setOf(value)
                }
                is ArcSSAOperation.Reborrow -> operation.anchorDependencies.takeIf { it.isNotEmpty() }?.let { anchors ->
                    dependencies[value] = anchors.mapTo(linkedSetOf()) { canonicalForwardRoot(it, definitions) }
                }
                else -> Unit
            }
        }
        var changed = true
        var steps = 0
        while (changed) {
            if (++steps > maximumSteps) return null
            changed = false
            values.sortedBy { it.name }.forEach { value ->
                val resolved = when (val operation = definitions[value]?.operation) {
                    is ArcSSAOperation.Forward -> dependencies[operation.source]
                    is ArcSSAOperation.Reborrow -> dependencies[value] ?: dependencies[operation.source]
                    is ArcSSAOperation.Join -> operation.incoming.values.mapNotNull { dependencies[it] }
                        .flatten().toCollection(linkedSetOf()).takeIf { it.isNotEmpty() }
                    else -> dependencies[value]
                }
                if (resolved != null && dependencies[value] != resolved) {
                    dependencies[value] = resolved
                    changed = true
                }
            }
        }
        return dependencies.takeIf { result -> values.all { it in result && result.getValue(it).isNotEmpty() } }
    }

    private fun computeValueLiveness(
        input: ArcSemanticARCInput,
        members: Set<ArcSSAValue>,
        definitions: Map<ArcSSAValue, Definition>,
        dominators: Map<ArcBlockId, Set<ArcBlockId>>,
    ): ValueLiveness {
        var liveIn = input.cfg.blocks.keys.associateWith { emptySet<ArcSSAValue>() }
        var liveOut = input.cfg.blocks.keys.associateWith { emptySet<ArcSSAValue>() }
        var before = input.cfg.blocks.mapValues { (_, block) -> List(block.operations.size) { emptySet<ArcSSAValue>() } }
        var after = before
        var edgeLive = input.cfg.edges.associateWith { emptySet<ArcSSAValue>() }
        var changed = true
        while (changed) {
            val nextEdgeLive = input.cfg.edges.sortedWith(compareBy({ it.from.name }, { it.to.name }, { it.kind.name }))
                .associateWithTo(linkedMapOf()) { edge ->
                    val successor = input.cfg.blocks.getValue(edge.to)
                    val values = liveIn[edge.to].orEmpty().toMutableSet()
                    if (edge.kind == ArcSSAEdgeKind.Normal) {
                        successor.operations.forEachIndexed { index, operation ->
                            if (operation is ArcSSAOperation.Join && operation.result in members) {
                                values.remove(operation.result)
                                if (after[edge.to]?.getOrNull(index).orEmpty().contains(operation.result)) {
                                    operation.incoming[edge.from]?.let { values += it }
                                }
                            }
                        }
                    }
                    values.filterTo(linkedSetOf()) { it in members }
                }
            val nextLiveOut = input.cfg.blocks.keys.associateWithTo(linkedMapOf()) { block ->
                val values = input.cfg.edges.filter { it.from == block }.flatMapTo(linkedSetOf()) {
                    nextEdgeLive[it].orEmpty()
                }
                if (block in input.exitLifetimeUses) {
                    members.filterTo(values) { value ->
                        definitions[value]?.block in dominators[block].orEmpty()
                    }
                }
                values
            }
            val nextBefore = linkedMapOf<ArcBlockId, List<Set<ArcSSAValue>>>()
            val nextAfter = linkedMapOf<ArcBlockId, List<Set<ArcSSAValue>>>()
            val nextLiveIn = linkedMapOf<ArcBlockId, Set<ArcSSAValue>>()
            input.cfg.blocks.values.sortedByDescending { it.id.name }.forEach { block ->
                val blockBefore = MutableList(block.operations.size) { emptySet<ArcSSAValue>() }
                val blockAfter = MutableList(block.operations.size) { emptySet<ArcSSAValue>() }
                val live = nextLiveOut[block.id].orEmpty().toMutableSet()
                block.operations.indices.reversed().forEach { index ->
                    val operation = block.operations[index]
                    blockAfter[index] = live.toSet()
                    operation.phiWebResult()?.let { live.remove(it) }
                    if (operation !is ArcSSAOperation.Join) {
                        operation.phiWebOperands().filterTo(live) { it in members }
                    }
                    blockBefore[index] = live.toSet()
                }
                nextBefore[block.id] = blockBefore
                nextAfter[block.id] = blockAfter
                nextLiveIn[block.id] = live.toSet()
            }
            changed = nextLiveIn != liveIn || nextLiveOut != liveOut || nextBefore != before ||
                    nextAfter != after || nextEdgeLive != edgeLive
            liveIn = nextLiveIn
            liveOut = nextLiveOut
            before = nextBefore
            after = nextAfter
            edgeLive = nextEdgeLive
        }
        return ValueLiveness(before, after, liveIn, liveOut, edgeLive)
    }

    private fun exceptionalLifetimeProblem(
        input: ArcSemanticARCInput,
        members: Set<ArcSSAValue>,
        liveness: ValueLiveness,
    ): Pair<ArcSemanticARCRejectionReason, String>? {
        input.cfg.blocks.values.sortedBy { it.id.name }.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                if (operation !is ArcSSAOperation.Use || !operation.mayThrow || operation.value !in members) return@forEachIndexed
                val exceptionalEdges = input.cfg.edges.filter {
                    it.from == block.id && it.kind == ArcSSAEdgeKind.Exceptional
                }
                if (exceptionalEdges.isEmpty()) {
                    return ArcSemanticARCRejectionReason.MalformedCFG to
                            "throwing use at ${block.id}:$index has no modeled exceptional successor"
                }
                exceptionalEdges.firstOrNull { edge ->
                    edge.to !in input.deadEndBlocks && liveness.edgeLive[edge].orEmpty().any { it in members }
                }?.let { edge ->
                    return ArcSemanticARCRejectionReason.BarrierCrossing to
                            "borrowed phi lifetime crosses exceptional edge ${edge.from} -> ${edge.to}"
                }
            }
        }
        return null
    }

    private fun computeFrontier(
        input: ArcSemanticARCInput,
        members: Set<ArcSSAValue>,
        liveness: ValueLiveness,
    ): Set<ArcSemanticLifetimeFrontier> = buildSet {
        input.cfg.blocks.values.sortedBy { it.id.name }.forEach { block ->
            if (block.id in input.deadEndBlocks) return@forEach
            block.operations.forEachIndexed { index, operation ->
                val isFamilyUse = operation.phiWebOperands().any { it in members } && operation !is ArcSSAOperation.Join
                if (isFamilyUse && !liveness.familyAfter(block.id, index, members)) {
                    add(ArcSemanticLifetimeFrontier.AfterOperation(block.id, index))
                }
            }
        }
        input.barriers.sortedWith(compareBy({ it.block.name }, { it.operationIndex }, { it.kind.name })).forEach { barrier ->
            if (barrier.block in input.deadEndBlocks) return@forEach
            val block = input.cfg.blocks.getValue(barrier.block)
            val liveBefore = when (barrier.operationIndex) {
                0 -> liveness.familyLiveIn(barrier.block, members)
                else -> liveness.familyAfter(barrier.block, barrier.operationIndex - 1, members)
            }
            val liveAfter = when (barrier.operationIndex) {
                block.operations.size -> liveness.familyLiveOut(barrier.block, members)
                else -> liveness.familyBefore(barrier.block, barrier.operationIndex, members)
            }
            if (liveBefore && !liveAfter) add(ArcSemanticLifetimeFrontier.BeforeBarrier(barrier))
        }
        input.cfg.edges.sortedWith(compareBy({ it.from.name }, { it.to.name }, { it.kind.name })).forEach { edge ->
            if (edge.from in input.deadEndBlocks || edge.to in input.deadEndBlocks) return@forEach
            if (liveness.familyLiveOut(edge.from, members) &&
                liveness.edgeLive[edge].orEmpty().none { it in members }) {
                add(ArcSemanticLifetimeFrontier.OnEdge(edge, isCritical(edge, input.cfg)))
            }
        }
        input.exitLifetimeUses.sortedBy { it.name }.filter { it !in input.deadEndBlocks && liveness.familyLiveOut(it, members) }
            .forEach { add(ArcSemanticLifetimeFrontier.BeforeExit(it)) }
    }

    private fun buildReborrows(
        cfg: ArcOwnershipSSAInput,
        joins: Set<ArcSSAValue>,
        definitions: Map<ArcSSAValue, Definition>,
        dependencies: Map<ArcSSAValue, Set<ArcSSAValue>>,
    ): Set<ArcSemanticReborrow> = buildSet {
        joins.sortedBy { it.name }.forEach { joined ->
            val definition = definitions.getValue(joined)
            val join = definition.operation as ArcSSAOperation.Join
            join.incoming.toSortedMap(compareBy { it.name }).forEach incomingLoop@{ (predecessor, incoming) ->
                val edge = cfg.edges.singleOrNull {
                    it.from == predecessor && it.to == definition.block && it.kind == ArcSSAEdgeKind.Normal
                } ?: return@incomingLoop
                add(ArcSemanticReborrow(edge, incoming, joined, dependencies[incoming].orEmpty()))
            }
        }
    }

    private fun verifyPlan(
        input: ArcSemanticARCInput,
        plan: ArcSemanticPhiWebPlan,
        definitions: Map<ArcSSAValue, Definition>,
        dependencies: Map<ArcSSAValue, Set<ArcSSAValue>>,
        liveness: ValueLiveness,
        component: Component,
    ): Boolean {
        val rediscovered = discoverComponentAtFixedPoint(
            plan.seeds.minByOrNull { it.copy.name }?.copy ?: return false,
            input.cfg,
            input.budget.maximumRewriteSteps,
        ) ?: return false
        if (rediscovered.members != component.members || rediscovered.members != plan.members) return false
        val expectedJoins = plan.members.filterTo(linkedSetOf()) { definitions[it]?.operation is ArcSSAOperation.Join }
        if (plan.joins != expectedJoins) return false
        val expectedReborrows = buildReborrows(input.cfg, expectedJoins, definitions, dependencies)
        if (plan.reborrows != expectedReborrows || expectedReborrows.any { it.anchorDependencies.isEmpty() }) return false
        val expectedFrontier = computeFrontier(input, plan.members, liveness)
        if (plan.frontier != expectedFrontier || plan.frontier.any {
                when (it) {
                    is ArcSemanticLifetimeFrontier.AfterOperation -> it.block in input.deadEndBlocks
                    is ArcSemanticLifetimeFrontier.BeforeBarrier -> it.barrier.block in input.deadEndBlocks
                    is ArcSemanticLifetimeFrontier.BeforeExit -> it.block in input.deadEndBlocks
                    is ArcSemanticLifetimeFrontier.OnEdge ->
                        it.edge.from in input.deadEndBlocks || it.edge.to in input.deadEndBlocks
                }
            }) return false
        val expectedLiveness = input.cfg.blocks.keys.sortedBy { it.name }.associateWith { block ->
            when {
                liveness.familyLiveOut(block, plan.members) -> ArcPrunedBlockLiveness.LiveOut
                liveness.familyLiveIn(block, plan.members) ||
                        liveness.before[block].orEmpty().any { values -> values.any { it in plan.members } } ||
                        liveness.after[block].orEmpty().any { values -> values.any { it in plan.members } } ->
                    ArcPrunedBlockLiveness.LiveWithin
                else -> ArcPrunedBlockLiveness.Dead
            }
        }
        if (plan.liveness != expectedLiveness || plan.canonicalRCRoots != dependencies.values.flatten().toSet()) return false
        if (plan.verificationEpochs != plan.rewrites.size + 1 || plan.rewrites.isEmpty()) return false
        val prepare = plan.rewrites.firstOrNull() as? ArcSemanticARCRewrite.PrepareJoinedWeb ?: return false
        if (prepare.joins != plan.joins || prepare.reborrows != plan.reborrows || prepare.frontier != plan.frontier) return false
        val eliminations = plan.rewrites.drop(1).mapNotNull { it as? ArcSemanticARCRewrite.EliminateGuaranteedCopy }
        if (eliminations.size != plan.seeds.size || eliminations.map { it.copy } != plan.seeds.map { it.copy }.sortedBy { it.name }) return false
        return eliminations.all { elimination ->
            plan.seeds.singleOrNull { it.copy == elimination.copy }?.guaranteedSource == elimination.source
        }
    }

    private fun hasValidSSA(
        cfg: ArcOwnershipSSAInput,
        members: Set<ArcSSAValue>,
        definitions: Map<ArcSSAValue, Definition>,
        reachable: Set<ArcBlockId>,
        dominators: Map<ArcBlockId, Set<ArcBlockId>>,
    ): Boolean {
        if (members.any { definitions[it]?.block !in reachable }) return false
        return cfg.blocks.values.all { block ->
            block.operations.withIndex().all { (index, operation) ->
                if (operation is ArcSSAOperation.Join && operation.result in members) {
                    val predecessors = cfg.edges.filter { it.to == block.id && it.kind == ArcSSAEdgeKind.Normal }
                        .mapTo(linkedSetOf()) { it.from }
                    operation.incoming.size >= 2 && operation.incoming.keys == predecessors &&
                            operation.incoming.all { (predecessor, incoming) ->
                                incoming in members && dominatesAtEnd(incoming, predecessor, definitions, dominators, cfg)
                            }
                } else {
                    val operands = if (operation.phiWebResult() in members &&
                        operation is ArcSSAOperation.Introduce ||
                        operation.phiWebResult() in members && operation is ArcSSAOperation.Reborrow) {
                        operation.phiWebOperands()
                    } else {
                        operation.phiWebOperands().filter { it in members }
                    }
                    operands.all { operand ->
                        dominates(operand, Definition(block.id, index, operation), definitions, dominators)
                    }
                }
            }
        }
    }

    private fun hasValidDestroyPaths(
        cfg: ArcOwnershipSSAInput,
        members: Set<ArcSSAValue>,
        definitions: Map<ArcSSAValue, Definition>,
        seeds: Set<ArcSSAValue>,
    ): Boolean {
        val origins = seedOriginsAtFixedPoint(members, definitions, seeds) ?: return false
        val destroys = buildList {
            cfg.blocks.values.forEach { block -> block.operations.forEachIndexed { index, operation ->
                if (operation is ArcSSAOperation.DestroyOwned && operation.value in members) {
                    add(Triple(block.id, index, operation.value))
                }
            } }
        }
        fun positionReaches(first: Triple<ArcBlockId, Int, ArcSSAValue>, secondBlock: ArcBlockId, secondIndex: Int): Boolean =
            if (first.first == secondBlock) first.second < secondIndex
            else isReachable(first.first, secondBlock, cfg)
        if (destroys.any { first ->
                val firstOrigins = origins[first.third].orEmpty()
                firstOrigins.isNotEmpty() && destroys.any { second ->
                    first !== second && firstOrigins.intersect(origins[second.third].orEmpty()).isNotEmpty() &&
                            positionReaches(first, second.first, second.second)
                }
            }) return false
        return destroys.none { destroy ->
            val destroyedOrigins = origins[destroy.third].orEmpty()
            cfg.blocks.values.any { block -> block.operations.withIndex().any { (index, operation) ->
                val usedOrigins = operation.phiWebOperands().filter { it in members }
                    .flatMapTo(linkedSetOf()) { origins[it].orEmpty() }
                operation !is ArcSSAOperation.DestroyOwned && destroyedOrigins.intersect(usedOrigins).isNotEmpty() &&
                        positionReaches(destroy, block.id, index)
            } }
        }
    }

    private fun seedOriginsAtFixedPoint(
        members: Set<ArcSSAValue>,
        definitions: Map<ArcSSAValue, Definition>,
        seeds: Set<ArcSSAValue>,
    ): Map<ArcSSAValue, Set<ArcSSAValue>>? {
        val result = members.associateWithTo(linkedMapOf()) { value -> if (value in seeds) setOf(value) else emptySet() }
        var changed = true
        var steps = 0
        while (changed) {
            if (++steps > members.size + 1) return null
            changed = false
            members.sortedBy { it.name }.forEach { value ->
                val next = when (val operation = definitions[value]?.operation) {
                    is ArcSSAOperation.Forward -> result[operation.source].orEmpty()
                    is ArcSSAOperation.Reborrow -> result[operation.source].orEmpty()
                    is ArcSSAOperation.Join -> operation.incoming.values.flatMapTo(linkedSetOf()) { result[it].orEmpty() }
                    else -> result[value].orEmpty()
                }
                if (next != result[value]) {
                    result[value] = next
                    changed = true
                }
            }
        }
        return result
    }

    private fun canonicalForwardRoot(value: ArcSSAValue, definitions: Map<ArcSSAValue, Definition>): ArcSSAValue {
        var current = value
        val visited = linkedSetOf<ArcSSAValue>()
        while (visited.add(current)) {
            val forward = definitions[current]?.operation as? ArcSSAOperation.Forward ?: return current
            current = forward.source
        }
        return current
    }

    private fun dominates(
        value: ArcSSAValue,
        use: Definition,
        definitions: Map<ArcSSAValue, Definition>,
        dominators: Map<ArcBlockId, Set<ArcBlockId>>,
    ): Boolean {
        val definition = definitions[value] ?: return false
        return definition.block in dominators[use.block].orEmpty() &&
                (definition.block != use.block || definition.index < use.index)
    }

    private fun dominatesAtEnd(
        value: ArcSSAValue,
        predecessor: ArcBlockId,
        definitions: Map<ArcSSAValue, Definition>,
        dominators: Map<ArcBlockId, Set<ArcBlockId>>,
        cfg: ArcOwnershipSSAInput,
    ): Boolean {
        val definition = definitions[value] ?: return false
        return definition.block in dominators[predecessor].orEmpty() &&
                (definition.block != predecessor || definition.index < cfg.blocks.getValue(predecessor).operations.size)
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
                val next = if (predecessors.isEmpty()) setOf(block) else
                    predecessors.map { result.getValue(it) }.reduce { left, right -> left intersect right } + block
                if (result[block] != next) {
                    result[block] = next
                    changed = true
                }
            }
        }
        return result
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

private fun ArcSSAOperation.phiWebResult(): ArcSSAValue? = when (this) {
    is ArcSSAOperation.Introduce -> result
    is ArcSSAOperation.Forward -> result
    is ArcSSAOperation.Reborrow -> result
    is ArcSSAOperation.Join -> result
    is ArcSSAOperation.Borrow -> result
    else -> null
}

private fun ArcSSAOperation.phiWebOperands(): List<ArcSSAValue> = when (this) {
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
