/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.llvm.Lifetime

/**
 * Closed, emission-independent proof that an entire RC identity graph remains on one thread.
 *
 * This is deliberately stricter than escape analysis alone. [Lifetime.LOCAL] is necessary, but
 * the adapter must also enumerate every reference relation and every ownership operation. Codegen
 * must not select non-atomic runtime leaves unless it consumes one complete [ArcThreadLocalRCPlan].
 */
internal enum class ArcThreadLocalRootOrigin {
    LocalHeapAllocation,
    Parameter,
    Global,
    ThreadLocal,
    Unknown,
}

internal data class ArcThreadLocalRootFact(
    val root: ArcSSAValue,
    val lifetime: Lifetime,
    val origin: ArcThreadLocalRootOrigin,
    /** Stack-promoted objects have no heap RC and must not enter this optimization. */
    val isHeapAllocation: Boolean,
)

internal enum class ArcThreadLocalRCEffectKind {
    /** Complete reference-identity edges. These merge their endpoints into one proof component. */
    Alias,
    StrongField,
    StrongCapture,

    /** A known local call which may unwind and therefore requires an explicit cleanup proof. */
    ThrowingLocalCall,

    /** Any one of these effects permanently disqualifies the complete identity component. */
    GlobalStore,
    ThreadLocalStore,
    Return,
    Throw,
    Weak,
    Unowned,
    StableRef,
    Atomic,
    ForeignCall,
    Callback,
    WorkerTransfer,
    Suspension,
    UnknownCall,
    UnknownEscape,
}

internal data class ArcThreadLocalRCEffect(
    val position: ArcRCPosition,
    val kind: ArcThreadLocalRCEffectKind,
    val value: ArcSSAValue,
    val relatedValue: ArcSSAValue? = null,
) {
    val values: Set<ArcSSAValue> get() = setOfNotNull(value, relatedValue)
}

internal enum class ArcThreadLocalRCOperationKind { Retain, Release }

/** [ordinal] distinguishes multiple ownership operations emitted for one source operation. */
internal data class ArcThreadLocalRCOperation(
    val position: ArcRCPosition,
    val ordinal: Int,
    val kind: ArcThreadLocalRCOperationKind,
    val value: ArcSSAValue,
)

internal data class ArcThreadLocalExceptionCleanup(
    val callPosition: ArcRCPosition,
    val exceptionalEdge: ArcSSAEdge,
    val releasePosition: ArcRCPosition,
    val releaseOrdinal: Int,
)

/**
 * The IR adapter may construct this token only after walking the complete reachable body. The
 * booleans are intentionally explicit so partial adapters fail closed during incremental rollout.
 */
internal data class ArcThreadLocalRCCoverage(
    val modeledValues: Set<ArcSSAValue>,
    val allReferenceRelationsEnumerated: Boolean,
    val allOwnershipOperationsEnumerated: Boolean,
    val allEscapesAndCallsClassified: Boolean,
)

internal data class ArcThreadLocalRCInput(
    val identityInput: ArcRCIdentityInput,
    val rootFacts: Map<ArcSSAValue, ArcThreadLocalRootFact>,
    val effects: List<ArcThreadLocalRCEffect>,
    val ownershipOperations: List<ArcThreadLocalRCOperation>,
    val exceptionCleanups: List<ArcThreadLocalExceptionCleanup>,
    val coverage: ArcThreadLocalRCCoverage,
)

/**
 * Strict legacy MM selects non-atomic RC dynamically for CONTAINER_TAG_LOCAL, atomic RC for frozen
 * or shared containers, and no heap RC for stack containers. The new proof is more conservative:
 * it proves at compile time that a heap identity can never make the local-to-shareable transition.
 */
internal enum class ArcStrictContainerState { Stack, Local, Frozen, Shared }

internal object ArcStrictNonAtomicSelectionSemantics {
    fun usesAtomicHeapRC(state: ArcStrictContainerState): Boolean? = when (state) {
        ArcStrictContainerState.Stack -> null
        ArcStrictContainerState.Local -> false
        ArcStrictContainerState.Frozen, ArcStrictContainerState.Shared -> true
    }
}

internal data class ArcThreadLocalRCPlan(
    val provenanceRoots: Set<ArcSSAValue>,
    val values: Set<ArcSSAValue>,
    val effects: List<ArcThreadLocalRCEffect>,
    val ownershipOperations: List<ArcThreadLocalRCOperation>,
    val exceptionCleanups: List<ArcThreadLocalExceptionCleanup>,
) {
    /** Every accepted plan has the same RC selection as a strict local container. */
    val strictComparison: ArcStrictContainerState get() = ArcStrictContainerState.Local
}

internal enum class ArcThreadLocalRCRejectionReason {
    MalformedCFG,
    IrreducibleCFG,
    IncompleteCoverage,
    IncompleteAliasModel,
    MissingRootFact,
    NonLocalRoot,
    NonHeapRoot,
    InvalidRootOrigin,
    UnresolvedIdentity,
    OwnershipIdentityIssue,
    UnmodeledValue,
    InvalidEffect,
    ForbiddenEffect,
    InvalidOwnershipOperation,
    DuplicateOwnershipOperation,
    MissingTerminalRelease,
    MissingExceptionalCleanup,
    InvalidExceptionalCleanup,
}

internal data class ArcThreadLocalRCRejection(
    val provenanceRoots: Set<ArcSSAValue>,
    val reason: ArcThreadLocalRCRejectionReason,
    val detail: String,
)

internal data class ArcThreadLocalRCResult(
    val accepted: List<ArcThreadLocalRCPlan>,
    val rejected: List<ArcThreadLocalRCRejection>,
)

internal object ArcThreadLocalRCAnalysis {
    private val relationKinds = setOf(
        ArcThreadLocalRCEffectKind.Alias,
        ArcThreadLocalRCEffectKind.StrongField,
        ArcThreadLocalRCEffectKind.StrongCapture,
    )
    private val forbiddenKinds = ArcThreadLocalRCEffectKind.values().toSet() - relationKinds -
            ArcThreadLocalRCEffectKind.ThrowingLocalCall

    fun analyze(input: ArcThreadLocalRCInput): ArcThreadLocalRCResult {
        val cfg = input.identityInput.cfg
        val identityResult = ArcRCIdentityAnalysis.analyze(input.identityInput)
        val allRoots = identityResult.identities.values.flatMapTo(linkedSetOf()) { it.provenanceRoots }
        if (allRoots.isEmpty()) {
            val unresolved = identityResult.issues.map { issue ->
                ArcThreadLocalRCRejection(
                    setOf(issue.value),
                    ArcThreadLocalRCRejectionReason.OwnershipIdentityIssue,
                    "${issue.kind} for ${issue.value}: ${issue.detail}",
                )
            }
            return ArcThreadLocalRCResult(emptyList(), unresolved)
        }

        val globalRejections = mutableListOf<Pair<ArcThreadLocalRCRejectionReason, String>>()
        if (!cfg.isWellFormedAndReachable()) {
            globalRejections += ArcThreadLocalRCRejectionReason.MalformedCFG to
                    "the CFG has missing endpoints, unreachable blocks, or invalid operation positions"
        } else if (!cfg.isReducible()) {
            globalRejections += ArcThreadLocalRCRejectionReason.IrreducibleCFG to
                    "non-atomic RC requires a reducible whole-body CFG"
        }
        if (!input.coverage.allReferenceRelationsEnumerated ||
            !input.coverage.allOwnershipOperationsEnumerated ||
            !input.coverage.allEscapesAndCallsClassified
        ) {
            globalRejections += ArcThreadLocalRCRejectionReason.IncompleteCoverage to
                    "reference relations, RC traffic, and escape/call effects must all be complete"
        }

        val hasMalformedPosition = input.effects.any { !cfg.contains(it.position) } ||
                input.ownershipOperations.any { !cfg.contains(it.position) }
        if (hasMalformedPosition) {
            globalRejections += ArcThreadLocalRCRejectionReason.InvalidEffect to
                    "an effect or ownership operation names a position outside the CFG"
        }

        val components = buildComponents(identityResult, input.effects)
        val accepted = mutableListOf<ArcThreadLocalRCPlan>()
        val rejected = mutableListOf<ArcThreadLocalRCRejection>()
        components.forEach { component ->
            val roots = component.flatMapTo(linkedSetOf()) {
                identityResult.identity(it)?.provenanceRoots.orEmpty()
            }
            val failures = globalRejections.toMutableList()
            validateComponent(input, identityResult, component, roots, failures)
            if (failures.isNotEmpty()) {
                failures.distinct().forEach { (reason, detail) ->
                    rejected += ArcThreadLocalRCRejection(roots, reason, detail)
                }
            } else {
                val effects = input.effects.filter { effect -> effect.values.any { it in component } }
                val operations = input.ownershipOperations.filter { it.value in component }
                val cleanups = input.exceptionCleanups.filter { cleanup ->
                    effects.any { it.kind == ArcThreadLocalRCEffectKind.ThrowingLocalCall && it.position == cleanup.callPosition }
                }
                accepted += ArcThreadLocalRCPlan(roots, component, effects, operations, cleanups)
            }
        }
        return ArcThreadLocalRCResult(accepted, rejected)
    }

    private fun validateComponent(
        input: ArcThreadLocalRCInput,
        identityResult: ArcRCIdentityResult,
        component: Set<ArcSSAValue>,
        roots: Set<ArcSSAValue>,
        failures: MutableList<Pair<ArcThreadLocalRCRejectionReason, String>>,
    ) {
        val cfg = input.identityInput.cfg
        roots.forEach { root ->
            val fact = input.rootFacts[root]
            when {
                fact == null || fact.root != root -> failures += ArcThreadLocalRCRejectionReason.MissingRootFact to
                        "no escape-analysis allocation fact for $root"
                fact.lifetime !== Lifetime.LOCAL -> failures += ArcThreadLocalRCRejectionReason.NonLocalRoot to
                        "$root has escape-analysis lifetime ${fact.lifetime}, not LOCAL"
                !fact.isHeapAllocation -> failures += ArcThreadLocalRCRejectionReason.NonHeapRoot to
                        "$root is stack/permanent storage rather than a heap RC allocation"
                fact.origin != ArcThreadLocalRootOrigin.LocalHeapAllocation ->
                    failures += ArcThreadLocalRCRejectionReason.InvalidRootOrigin to
                            "$root originates from ${fact.origin}, not a local allocation"
            }
        }

        val identityIssues = identityResult.issues.filter { issue ->
            val issueRoots = identityResult.identity(issue.value)?.provenanceRoots.orEmpty()
            issue.value in component || issueRoots.any { it in roots }
        }
        identityIssues.forEach { issue ->
            failures += ArcThreadLocalRCRejectionReason.OwnershipIdentityIssue to
                    "${issue.kind} for ${issue.value}: ${issue.detail}"
        }

        val unmodeled = component - input.coverage.modeledValues
        if (unmodeled.isNotEmpty()) {
            failures += ArcThreadLocalRCRejectionReason.UnmodeledValue to
                    "identity values are missing from the closed model: ${unmodeled.joinToString()}"
        }

        expectedAliases(cfg, component).forEach { expected ->
            val found = input.effects.any { effect ->
                effect.kind == ArcThreadLocalRCEffectKind.Alias &&
                        effect.position == expected.position &&
                        effect.value == expected.value && effect.relatedValue == expected.relatedValue
            }
            if (!found) failures += ArcThreadLocalRCRejectionReason.IncompleteAliasModel to
                    "missing alias relation ${expected.value} -> ${expected.relatedValue} at ${expected.position}"
        }

        val relevantEffects = input.effects.filter { effect -> effect.values.any { it in component } }
        relevantEffects.forEach { effect ->
            val related = effect.relatedValue
            if (effect.kind in relationKinds && (related == null || related !in identityResult.identities)) {
                failures += ArcThreadLocalRCRejectionReason.InvalidEffect to
                        "${effect.kind} at ${effect.position} has no resolved reference endpoint"
            }
            if (effect.values.any { it !in input.coverage.modeledValues || it !in identityResult.identities }) {
                failures += ArcThreadLocalRCRejectionReason.UnmodeledValue to
                        "${effect.kind} at ${effect.position} touches an unresolved or unmodeled value"
            }
            if (effect.kind in forbiddenKinds) {
                failures += ArcThreadLocalRCRejectionReason.ForbiddenEffect to
                        "${effect.kind} at ${effect.position} can publish or share this RC identity"
            }
        }

        val operations = input.ownershipOperations.filter { it.value in component }
        val duplicateOperations = operations.groupBy { it.position to it.ordinal }.filterValues { it.size != 1 }
        if (duplicateOperations.isNotEmpty()) {
            failures += ArcThreadLocalRCRejectionReason.DuplicateOwnershipOperation to
                    "ownership operation ids must be unique: ${duplicateOperations.keys.joinToString()}"
        }
        operations.filter { operation ->
            operation.ordinal < 0 || operation.value !in input.coverage.modeledValues ||
                    operation.value !in identityResult.identities
        }.forEach { operation ->
            failures += ArcThreadLocalRCRejectionReason.InvalidOwnershipOperation to
                    "invalid ${operation.kind} operation at ${operation.position}#${operation.ordinal}"
        }

        val requiredReleases = buildList {
            cfg.blocks.values.forEach { block ->
                block.operations.forEachIndexed { index, operation ->
                    val value = when (operation) {
                        is ArcSSAOperation.DestroyOwned -> operation.value
                        is ArcSSAOperation.Use -> operation.value.takeIf { operation.kind == ArcSSAUseKind.Consume }
                        else -> null
                    }
                    if (value != null && value in component) add(ArcRCPosition(block.id, index) to value)
                }
            }
        }
        requiredReleases.forEach { (position, value) ->
            if (operations.none {
                    it.position == position && it.kind == ArcThreadLocalRCOperationKind.Release &&
                            identityResult.sameFamily(it.value, value)
                }
            ) {
                failures += ArcThreadLocalRCRejectionReason.MissingTerminalRelease to
                        "consume/destroy of $value at $position has no enumerated release"
            }
        }
        val releasedRoots = operations.filter { it.kind == ArcThreadLocalRCOperationKind.Release }
            .flatMapTo(linkedSetOf()) { identityResult.identity(it.value)?.provenanceRoots.orEmpty() }
        if (!releasedRoots.containsAll(roots)) {
            failures += ArcThreadLocalRCRejectionReason.MissingTerminalRelease to
                    "no complete lifetime-ending release covers roots ${(roots - releasedRoots).joinToString()}"
        }

        validateExceptionalCleanup(input, identityResult, component, relevantEffects, operations, failures)
    }

    private fun validateExceptionalCleanup(
        input: ArcThreadLocalRCInput,
        identityResult: ArcRCIdentityResult,
        component: Set<ArcSSAValue>,
        effects: List<ArcThreadLocalRCEffect>,
        operations: List<ArcThreadLocalRCOperation>,
        failures: MutableList<Pair<ArcThreadLocalRCRejectionReason, String>>,
    ) {
        val cfg = input.identityInput.cfg
        val throwing = effects.filter { it.kind == ArcThreadLocalRCEffectKind.ThrowingLocalCall }
        throwing.forEach { call ->
            val mayThrowOperations = cfg.blocks.getValue(call.position.block).operations
                .mapIndexedNotNull { index, operation ->
                    (operation as? ArcSSAOperation.Use)?.takeIf { it.mayThrow }?.let { index to it }
                }
            val exceptionalEdges = cfg.edges.filter {
                it.from == call.position.block && it.kind == ArcSSAEdgeKind.Exceptional
            }
            val cleanups = input.exceptionCleanups.filter { it.callPosition == call.position }
            if (mayThrowOperations.size != 1 || mayThrowOperations.singleOrNull()?.first != call.position.operationIndex ||
                mayThrowOperations.singleOrNull()?.second?.value != call.value
            ) {
                failures += ArcThreadLocalRCRejectionReason.InvalidExceptionalCleanup to
                        "${call.position.block} must contain exactly one modeled may-throw operation"
                return@forEach
            }
            if (exceptionalEdges.isEmpty() || cleanups.map { it.exceptionalEdge }.toSet() != exceptionalEdges.toSet()) {
                failures += ArcThreadLocalRCRejectionReason.MissingExceptionalCleanup to
                        "throwing local call at ${call.position} must cover every exceptional edge exactly once"
                return@forEach
            }
            if (cleanups.groupBy { it.exceptionalEdge }.any { it.value.size != 1 }) {
                failures += ArcThreadLocalRCRejectionReason.InvalidExceptionalCleanup to
                        "throwing local call at ${call.position} has duplicate edge cleanup"
            }
            cleanups.forEach { cleanup ->
                val release = operations.singleOrNull {
                    it.position == cleanup.releasePosition && it.ordinal == cleanup.releaseOrdinal &&
                            it.kind == ArcThreadLocalRCOperationKind.Release
                }
                val targetPredecessors = cfg.edges.filter { it.to == cleanup.exceptionalEdge.to }
                if (cleanup.exceptionalEdge !in exceptionalEdges || release == null ||
                    cleanup.releasePosition.block != cleanup.exceptionalEdge.to ||
                    targetPredecessors.singleOrNull() != cleanup.exceptionalEdge ||
                    !identityResult.sameFamily(release.value, call.value) || release.value !in component
                ) {
                    failures += ArcThreadLocalRCRejectionReason.InvalidExceptionalCleanup to
                            "cleanup for ${call.position} must be a family release in a pre-split exceptional target with one predecessor"
                }
            }
        }

        val modeledThrowPositions = throwing.mapTo(linkedSetOf()) { it.position }
        cfg.blocks.values.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                if (operation is ArcSSAOperation.Use && operation.mayThrow && operation.value in component &&
                    ArcRCPosition(block.id, index) !in modeledThrowPositions
                ) {
                    failures += ArcThreadLocalRCRejectionReason.MissingExceptionalCleanup to
                            "throwing ownership use at ${ArcRCPosition(block.id, index)} is not classified"
                }
            }
        }
    }

    private fun buildComponents(
        identities: ArcRCIdentityResult,
        effects: List<ArcThreadLocalRCEffect>,
    ): List<Set<ArcSSAValue>> {
        val unresolved = identities.identities.keys.toMutableSet()
        val components = mutableListOf<Set<ArcSSAValue>>()
        while (unresolved.isNotEmpty()) {
            val component = linkedSetOf(unresolved.first())
            var changed = true
            while (changed) {
                changed = false
                val roots = component.flatMapTo(linkedSetOf()) { identities.identity(it)?.provenanceRoots.orEmpty() }
                identities.identities.values.forEach { identity ->
                    if (identity.value !in component && identity.provenanceRoots.any { it in roots }) {
                        component += identity.value
                        changed = true
                    }
                }
                effects.filter { it.kind in relationKinds }.forEach { effect ->
                    if (effect.values.any { it in component } && component.addAll(effect.values)) changed = true
                }
            }
            unresolved.removeAll(component)
            components += component
        }
        return components
    }

    private fun expectedAliases(cfg: ArcOwnershipSSAInput, component: Set<ArcSSAValue>): List<ArcThreadLocalRCEffect> = buildList {
        cfg.blocks.values.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                val position = ArcRCPosition(block.id, index)
                when (operation) {
                    is ArcSSAOperation.Forward -> addAlias(position, operation.source, operation.result, component)
                    is ArcSSAOperation.Reborrow -> addAlias(position, operation.source, operation.result, component)
                    is ArcSSAOperation.Borrow -> addAlias(position, operation.source, operation.result, component)
                    is ArcSSAOperation.Join -> operation.incoming.values.forEach {
                        addAlias(position, it, operation.result, component)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun MutableList<ArcThreadLocalRCEffect>.addAlias(
        position: ArcRCPosition,
        source: ArcSSAValue,
        result: ArcSSAValue,
        component: Set<ArcSSAValue>,
    ) {
        if (source in component || result in component) {
            add(ArcThreadLocalRCEffect(position, ArcThreadLocalRCEffectKind.Alias, source, result))
        }
    }

    private fun ArcRCIdentityResult.sameFamily(left: ArcSSAValue, right: ArcSSAValue): Boolean {
        val leftRoots = identity(left)?.provenanceRoots ?: return false
        val rightRoots = identity(right)?.provenanceRoots ?: return false
        return leftRoots.any { it in rightRoots }
    }

    private fun ArcOwnershipSSAInput.contains(position: ArcRCPosition): Boolean =
        blocks[position.block]?.operations?.indices?.contains(position.operationIndex) == true

    private fun ArcOwnershipSSAInput.isWellFormedAndReachable(): Boolean {
        if (entry !in blocks || edges.any { it.from !in blocks || it.to !in blocks }) return false
        val reachable = linkedSetOf<ArcBlockId>()
        val worklist = ArrayDeque<ArcBlockId>().apply { add(entry) }
        while (worklist.isNotEmpty()) {
            val block = worklist.removeFirst()
            if (!reachable.add(block)) continue
            edges.filter { it.from == block }.forEach { worklist += it.to }
        }
        return reachable == blocks.keys
    }

    /** Every cyclic SCC must have one externally-entered header which dominates the SCC. */
    private fun ArcOwnershipSSAInput.isReducible(): Boolean {
        val predecessors = blocks.keys.associateWith { block -> edges.filter { it.to == block }.map { it.from }.toSet() }
        val dominators = blocks.keys.associateWithTo(linkedMapOf()) { block ->
            if (block == entry) mutableSetOf(entry) else blocks.keys.toMutableSet()
        }
        var changed = true
        while (changed) {
            changed = false
            blocks.keys.filter { it != entry }.forEach { block ->
                val preds = predecessors.getValue(block)
                val intersection = if (preds.isEmpty()) emptySet() else
                    preds.map { dominators.getValue(it).toSet() }.reduce { a, b -> a intersect b }
                val next = (intersection + block).toMutableSet()
                if (dominators[block] != next) {
                    dominators[block] = next
                    changed = true
                }
            }
        }
        stronglyConnectedComponents().forEach { scc ->
            val cyclic = scc.size > 1 || edges.any { it.from in scc && it.to == it.from }
            if (!cyclic) return@forEach
            val entries = scc.filter { node ->
                node == entry || predecessors.getValue(node).any { it !in scc }
            }
            if (entries.size != 1) return false
            val header = entries.single()
            if (scc.any { header !in dominators.getValue(it) }) return false
        }
        return true
    }

    private fun ArcOwnershipSSAInput.stronglyConnectedComponents(): List<Set<ArcBlockId>> {
        var index = 0
        val indices = mutableMapOf<ArcBlockId, Int>()
        val low = mutableMapOf<ArcBlockId, Int>()
        val stack = ArrayDeque<ArcBlockId>()
        val onStack = mutableSetOf<ArcBlockId>()
        val result = mutableListOf<Set<ArcBlockId>>()
        fun visit(node: ArcBlockId) {
            indices[node] = index
            low[node] = index++
            stack.addLast(node)
            onStack += node
            edges.filter { it.from == node }.forEach { edge ->
                val target = edge.to
                if (target !in indices) {
                    visit(target)
                    low[node] = minOf(low.getValue(node), low.getValue(target))
                } else if (target in onStack) {
                    low[node] = minOf(low.getValue(node), indices.getValue(target))
                }
            }
            if (low[node] == indices[node]) {
                val component = linkedSetOf<ArcBlockId>()
                do {
                    val member = stack.removeLast()
                    onStack -= member
                    component += member
                } while (member != node)
                result += component
            }
        }
        blocks.keys.forEach { if (it !in indices) visit(it) }
        return result
    }

}
