/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

/**
 * Emission-independent model for auditing every use of one physical object-reference slot.
 *
 * LLVM integration is expected to translate pointer-producing instructions and uses into this
 * closed model. An optimization must not proceed unless [usesComplete] is true: an unreported use
 * is indistinguishable from an escaping alias or an unmodelled mutation.
 */
internal sealed class ArcPhysicalPointerDefinition<P, B> {
    abstract val result: P

    data class Bitcast<P, B>(override val result: P, val source: P) : ArcPhysicalPointerDefinition<P, B>()
    data class Gep<P, B>(override val result: P, val source: P) : ArcPhysicalPointerDefinition<P, B>()
    data class Phi<P, B>(
        override val result: P,
        val parent: B,
        val incoming: Map<B, P>,
    ) : ArcPhysicalPointerDefinition<P, B>()
    data class Select<P, B>(override val result: P, val onTrue: P, val onFalse: P) :
        ArcPhysicalPointerDefinition<P, B>()

    /** Any pointer transformation whose alias behavior has not been proven transparent. */
    data class OpaqueDerived<P, B>(override val result: P, val source: P) : ArcPhysicalPointerDefinition<P, B>()
}

/** Referential identity token for one compiler-owned runtime declaration. */
internal class ArcExactRuntimeFunctionIdentity(val debugName: String)

internal sealed class ArcPhysicalPointerUse<P> {
    abstract val pointer: P

    /** Loading the reference stored in the slot does not mutate or expose the slot address. */
    data class Load<P>(override val pointer: P) : ArcPhysicalPointerUse<P>()
    /** Pointer equality/debug inspection is safe only when it does not publish the address. */
    data class Inspect<P>(override val pointer: P) : ArcPhysicalPointerUse<P>()
    data class StoreThrough<P>(override val pointer: P) : ArcPhysicalPointerUse<P>()
    data class StorePointer<P>(override val pointer: P) : ArcPhysicalPointerUse<P>()
    data class ReturnPointer<P>(override val pointer: P) : ArcPhysicalPointerUse<P>()
    data class UnknownCall<P>(override val pointer: P) : ArcPhysicalPointerUse<P>()
    data class TrustedRuntimeCall<P>(
        override val pointer: P,
        val function: ArcExactRuntimeFunctionIdentity,
    ) : ArcPhysicalPointerUse<P>()
}

internal data class ArcPhysicalSlotUseChain<P, B>(
    val root: P,
    val definitions: List<ArcPhysicalPointerDefinition<P, B>>,
    val uses: List<ArcPhysicalPointerUse<P>>,
    val usesComplete: Boolean,
)

internal enum class ArcPhysicalSlotUseRejection {
    INCOMPLETE_USE_CHAIN,
    DUPLICATE_POINTER_DEFINITION,
    CYCLIC_POINTER_DEFINITION,
    OPAQUE_DERIVED_ALIAS,
    MUTATION_THROUGH_ALIAS,
    POINTER_ESCAPE,
    UNKNOWN_CALL,
    UNTRUSTED_RUNTIME_CALL,
    TRUSTED_CALL_REQUIRES_EXACT_ROOT,
}

internal data class ArcPhysicalSlotUseVerification<P>(
    val aliases: Set<P>,
    val rejections: Set<ArcPhysicalSlotUseRejection>,
) {
    val accepted: Boolean get() = rejections.isEmpty()
}

internal object ArcPhysicalSlotUseChainVerifier {
    fun <P, B> verify(
        chain: ArcPhysicalSlotUseChain<P, B>,
        trustedRuntimeFunctions: Set<ArcExactRuntimeFunctionIdentity>,
    ): ArcPhysicalSlotUseVerification<P> {
        val rejections = linkedSetOf<ArcPhysicalSlotUseRejection>()
        if (!chain.usesComplete) rejections += ArcPhysicalSlotUseRejection.INCOMPLETE_USE_CHAIN

        val definitions = linkedMapOf<P, ArcPhysicalPointerDefinition<P, B>>()
        chain.definitions.forEach { definition ->
            if (definition.result == chain.root || definitions.put(definition.result, definition) != null) {
                rejections += ArcPhysicalSlotUseRejection.DUPLICATE_POINTER_DEFINITION
            }
        }

        val aliases = linkedSetOf(chain.root)
        var changed: Boolean
        do {
            changed = false
            definitions.values.forEach { definition ->
                val mayAlias = when (definition) {
                    is ArcPhysicalPointerDefinition.Bitcast -> definition.source in aliases
                    is ArcPhysicalPointerDefinition.Gep -> definition.source in aliases
                    is ArcPhysicalPointerDefinition.Phi -> definition.incoming.values.any { it in aliases }
                    is ArcPhysicalPointerDefinition.Select ->
                        definition.onTrue in aliases || definition.onFalse in aliases
                    is ArcPhysicalPointerDefinition.OpaqueDerived -> definition.source in aliases
                }
                if (mayAlias && aliases.add(definition.result)) changed = true
            }
        } while (changed)

        val aliasDefinitions = definitions.filterKeys { it in aliases }
        if (hasAliasDefinitionCycle(aliasDefinitions)) {
            rejections += ArcPhysicalSlotUseRejection.CYCLIC_POINTER_DEFINITION
        }
        if (aliasDefinitions.values.any { it is ArcPhysicalPointerDefinition.OpaqueDerived }) {
            rejections += ArcPhysicalSlotUseRejection.OPAQUE_DERIVED_ALIAS
        }

        chain.uses.filter { it.pointer in aliases }.forEach { use ->
            when (use) {
                is ArcPhysicalPointerUse.Load, is ArcPhysicalPointerUse.Inspect -> Unit
                is ArcPhysicalPointerUse.StoreThrough ->
                    rejections += ArcPhysicalSlotUseRejection.MUTATION_THROUGH_ALIAS
                is ArcPhysicalPointerUse.StorePointer, is ArcPhysicalPointerUse.ReturnPointer ->
                    rejections += ArcPhysicalSlotUseRejection.POINTER_ESCAPE
                is ArcPhysicalPointerUse.UnknownCall ->
                    rejections += ArcPhysicalSlotUseRejection.UNKNOWN_CALL
                is ArcPhysicalPointerUse.TrustedRuntimeCall -> when {
                    trustedRuntimeFunctions.none { it === use.function } ->
                        rejections += ArcPhysicalSlotUseRejection.UNTRUSTED_RUNTIME_CALL
                    use.pointer != chain.root ->
                        rejections += ArcPhysicalSlotUseRejection.TRUSTED_CALL_REQUIRES_EXACT_ROOT
                }
            }
        }
        return ArcPhysicalSlotUseVerification(aliases, rejections)
    }

    private fun <P, B> hasAliasDefinitionCycle(
        definitions: Map<P, ArcPhysicalPointerDefinition<P, B>>,
    ): Boolean {
        val visiting = mutableSetOf<P>()
        val visited = mutableSetOf<P>()
        fun sources(definition: ArcPhysicalPointerDefinition<P, B>): Collection<P> = when (definition) {
            is ArcPhysicalPointerDefinition.Bitcast -> listOf(definition.source)
            is ArcPhysicalPointerDefinition.Gep -> listOf(definition.source)
            is ArcPhysicalPointerDefinition.Phi -> definition.incoming.values
            is ArcPhysicalPointerDefinition.Select -> listOf(definition.onTrue, definition.onFalse)
            is ArcPhysicalPointerDefinition.OpaqueDerived -> listOf(definition.source)
        }
        fun visit(pointer: P): Boolean {
            if (pointer in visiting) return true
            if (!visited.add(pointer)) return false
            val definition = definitions[pointer] ?: return false
            visiting += pointer
            val cyclic = sources(definition).filter { it in definitions }.any(::visit)
            visiting -= pointer
            return cyclic
        }
        return definitions.keys.any(::visit)
    }
}

internal enum class ArcPhysicalCfgEdgeKind { NORMAL, UNWIND }

internal data class ArcPhysicalCfgEdge<B>(val from: B, val to: B, val kind: ArcPhysicalCfgEdgeKind)

internal data class ArcPhysicalCfg<B>(
    val entry: B,
    val blocks: Set<B>,
    val edges: Set<ArcPhysicalCfgEdge<B>>,
) {
    fun reachableBlocks(): Set<B> {
        if (entry !in blocks || edges.any { it.from !in blocks || it.to !in blocks }) return emptySet()
        val outgoing = edges.groupBy { it.from }
        val reachable = linkedSetOf(entry)
        val pending = ArrayDeque<B>().apply { add(entry) }
        while (pending.isNotEmpty()) {
            outgoing[pending.removeFirst()].orEmpty().forEach { edge ->
                if (reachable.add(edge.to)) pending.add(edge.to)
            }
        }
        return reachable
    }

    fun reachablePredecessors(block: B): Set<B> {
        val reachable = reachableBlocks()
        if (block !in reachable) return emptySet()
        return edges.asSequence().filter { it.to == block && it.from in reachable }.mapTo(linkedSetOf()) { it.from }
    }
}

internal data class ArcPhysicalSlotExactState<B, V>(
    val value: V,
    val coverage: Map<B, Set<B>> = emptyMap(),
)

internal data class ArcPhysicalValuePhi<B, V, T>(
    val value: V,
    val parent: B,
    val type: T,
    val incoming: Map<B, V>,
)

internal enum class ArcPhysicalSlotJoinRejection {
    MALFORMED_CFG,
    UNREACHABLE_JOIN,
    INCOMPLETE_EDGE_STATES,
    UNWIND_OR_OTHER_EDGE_HAS_NO_OWNER,
    PHI_PARENT_MISMATCH,
    PHI_TYPE_MISMATCH,
    PHI_PREDECESSOR_MISMATCH,
    PHI_VALUE_MISMATCH,
    CONFLICTING_COVERAGE,
}

internal data class ArcPhysicalSlotJoinResult<B, V>(
    val state: ArcPhysicalSlotExactState<B, V>?,
    val rejection: ArcPhysicalSlotJoinRejection?,
) {
    val accepted: Boolean get() = state != null && rejection == null
}

internal object ArcPhysicalSlotCfgVerifier {
    /** An invoke preserves an exact slot fact only on normal success. */
    fun <B, V> invokeEdgeStates(
        normalSuccess: B,
        unwind: B,
        state: ArcPhysicalSlotExactState<B, V>,
    ): Map<B, ArcPhysicalSlotExactState<B, V>?> = linkedMapOf(
        normalSuccess to state,
        unwind to null,
    )

    fun <B, V, T> proveJoin(
        cfg: ArcPhysicalCfg<B>,
        block: B,
        phi: ArcPhysicalValuePhi<B, V, T>,
        expectedType: T,
        incomingStates: Map<B, ArcPhysicalSlotExactState<B, V>?>,
    ): ArcPhysicalSlotJoinResult<B, V> {
        val reachable = cfg.reachableBlocks()
        if (reachable.isEmpty()) return rejected(ArcPhysicalSlotJoinRejection.MALFORMED_CFG)
        if (block !in reachable) return rejected(ArcPhysicalSlotJoinRejection.UNREACHABLE_JOIN)
        val predecessors = cfg.reachablePredecessors(block)
        if (predecessors.isEmpty()) return rejected(ArcPhysicalSlotJoinRejection.PHI_PREDECESSOR_MISMATCH)
        if (incomingStates.keys != predecessors) {
            return rejected(ArcPhysicalSlotJoinRejection.INCOMPLETE_EDGE_STATES)
        }
        if (incomingStates.values.any { it == null }) {
            return rejected(ArcPhysicalSlotJoinRejection.UNWIND_OR_OTHER_EDGE_HAS_NO_OWNER)
        }
        if (phi.parent != block) return rejected(ArcPhysicalSlotJoinRejection.PHI_PARENT_MISMATCH)
        if (phi.type != expectedType) return rejected(ArcPhysicalSlotJoinRejection.PHI_TYPE_MISMATCH)
        val reachablePhiIncoming = phi.incoming.filterKeys { it in reachable }
        if (reachablePhiIncoming.keys != predecessors) {
            return rejected(ArcPhysicalSlotJoinRejection.PHI_PREDECESSOR_MISMATCH)
        }

        val exactStates = incomingStates.mapValues { requireNotNull(it.value) }
        if (predecessors.any { predecessor ->
                exactStates.getValue(predecessor).value != reachablePhiIncoming.getValue(predecessor)
            }
        ) return rejected(ArcPhysicalSlotJoinRejection.PHI_VALUE_MISMATCH)

        val coverage = linkedMapOf<B, Set<B>>()
        exactStates.values.forEach { state ->
            state.coverage.forEach { (coveredBlock, coveredPredecessors) ->
                val previous = coverage[coveredBlock]
                if (previous != null && previous != coveredPredecessors) {
                    return rejected(ArcPhysicalSlotJoinRejection.CONFLICTING_COVERAGE)
                }
                coverage[coveredBlock] = coveredPredecessors
            }
        }
        val previous = coverage[block]
        if (previous != null && previous != predecessors) {
            return rejected(ArcPhysicalSlotJoinRejection.CONFLICTING_COVERAGE)
        }
        coverage[block] = predecessors
        return ArcPhysicalSlotJoinResult(ArcPhysicalSlotExactState(phi.value, coverage), null)
    }

    /** Revalidate every frozen join after the complete CFG, including late loop backedges, exists. */
    fun <B, V> coverageIsComplete(
        cfg: ArcPhysicalCfg<B>,
        state: ArcPhysicalSlotExactState<B, V>,
    ): Boolean {
        val reachable = cfg.reachableBlocks()
        if (reachable.isEmpty()) return false
        return state.coverage.all { (block, frozenPredecessors) ->
            block in reachable && cfg.reachablePredecessors(block) == frozenPredecessors
        }
    }

    private fun <B, V> rejected(reason: ArcPhysicalSlotJoinRejection) =
        ArcPhysicalSlotJoinResult<B, V>(null, reason)
}
