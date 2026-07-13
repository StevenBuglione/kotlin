/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.llvm.Lifetime
import java.util.Collections
import java.util.IdentityHashMap

/** Stable within one lowered function. Codegen must also check [ArcThreadLocalRCEventBinding.binding] by identity. */
internal data class ArcThreadLocalRCEventId(val block: ArcBlockId, val operationIndex: Int)

internal enum class ArcThreadLocalRCIRSemantic {
    Allocation,
    Alias,
    Phi,
    StrongField,
    StrongCapture,
    LocalCall,
    Retain,
    Release,
}

internal sealed class ArcThreadLocalRCLoweredEvent<T : Any> {
    abstract val binding: T

    data class Allocation<T : Any>(
        override val binding: T,
        val result: ArcSSAValue,
        val lifetime: Lifetime,
        val isHeapAllocation: Boolean,
        /** Coroutine state objects are publication-capable even if an intermediate lifetime fact is local. */
        val isCoroutineStateObject: Boolean = false,
    ) : ArcThreadLocalRCLoweredEvent<T>()

    data class Alias<T : Any>(
        override val binding: T,
        val source: ArcSSAValue,
        val result: ArcSSAValue,
    ) : ArcThreadLocalRCLoweredEvent<T>()

    data class Phi<T : Any>(
        override val binding: T,
        val result: ArcSSAValue,
        val incoming: Map<ArcBlockId, ArcSSAValue>,
    ) : ArcThreadLocalRCLoweredEvent<T>()

    data class StrongField<T : Any>(
        override val binding: T,
        val owner: ArcSSAValue,
        val value: ArcSSAValue,
    ) : ArcThreadLocalRCLoweredEvent<T>()

    data class StrongCapture<T : Any>(
        override val binding: T,
        val closure: ArcSSAValue,
        val value: ArcSSAValue,
    ) : ArcThreadLocalRCLoweredEvent<T>()

    /** A direct non-external, non-virtual call with a closed no-publication summary. */
    data class LocalCall<T : Any>(
        override val binding: T,
        val value: ArcSSAValue,
        val mayThrow: Boolean,
        val hasClosedNoPublicationSummary: Boolean,
    ) : ArcThreadLocalRCLoweredEvent<T>()

    data class Retain<T : Any>(override val binding: T, val value: ArcSSAValue) :
        ArcThreadLocalRCLoweredEvent<T>()

    data class Release<T : Any>(override val binding: T, val value: ArcSSAValue) :
        ArcThreadLocalRCLoweredEvent<T>()

    /** Any lowered intrinsic, call, or publication family which the first slice cannot authenticate. */
    data class Forbidden<T : Any>(
        override val binding: T,
        val value: ArcSSAValue?,
        val kind: ArcThreadLocalRCEffectKind,
    ) : ArcThreadLocalRCLoweredEvent<T>()

    /** A node the lowered-IR walk did not classify. It invalidates the complete-body seal. */
    data class Unclassified<T : Any>(override val binding: T, val description: String) :
        ArcThreadLocalRCLoweredEvent<T>()
}

internal data class ArcThreadLocalRCLoweredBlock<T : Any>(
    val id: ArcBlockId,
    val events: List<ArcThreadLocalRCLoweredEvent<T>>,
)

internal data class ArcThreadLocalRCLoweredExceptionCleanup(
    val call: ArcThreadLocalRCEventId,
    val exceptionalEdge: ArcSSAEdge,
    val release: ArcThreadLocalRCEventId,
)

/**
 * A normalized view of one fully lowered IR body.
 *
 * The real IR walker owns [completeLoweredIRWalk]. Tests use the same boundary with identity tokens.
 * A caller cannot obtain a selection from a partial or nested-declaration-skipping traversal.
 */
internal data class ArcThreadLocalRCLoweredBody<T : Any>(
    val functionBinding: T,
    val entry: ArcBlockId,
    val blocks: Map<ArcBlockId, ArcThreadLocalRCLoweredBlock<T>>,
    val edges: Set<ArcSSAEdge>,
    val isSuspendFunction: Boolean,
    val completeLoweredIRWalk: Boolean,
    val exceptionCleanups: List<ArcThreadLocalRCLoweredExceptionCleanup> = emptyList(),
)

internal data class ArcThreadLocalRCEventBinding<T : Any>(
    val id: ArcThreadLocalRCEventId,
    val binding: T,
    val semantic: ArcThreadLocalRCIRSemantic,
    val values: Set<ArcSSAValue>,
)

/** Exact IR identities and the proof they are allowed to consume. */
internal data class ArcThreadLocalRCIRSelection<T : Any>(
    val functionBinding: T,
    val plan: ArcThreadLocalRCPlan,
    val eventBindings: Map<ArcThreadLocalRCEventId, ArcThreadLocalRCEventBinding<T>>,
    val coverage: ArcThreadLocalRCCoverage,
) {
    val expectedOwnershipEvents: Set<ArcThreadLocalRCEventId> = eventBindings.values
        .filter { it.semantic == ArcThreadLocalRCIRSemantic.Retain || it.semantic == ArcThreadLocalRCIRSemantic.Release }
        .mapTo(linkedSetOf()) { it.id }
}

/**
 * Codegen-side shape-drift guard for the exact ownership events authenticated by the adapter.
 * Event id, semantic kind, and lowered IR object identity must all match; completion is mandatory.
 */
internal class ArcThreadLocalRCEventConsumptionLedger<T : Any>(
    selection: ArcThreadLocalRCIRSelection<T>,
) {
    private val expected = selection.eventBindings.filterValues {
        it.semantic == ArcThreadLocalRCIRSemantic.Retain || it.semantic == ArcThreadLocalRCIRSemantic.Release
    }
    private val consumed = linkedSetOf<ArcThreadLocalRCEventId>()

    fun consume(id: ArcThreadLocalRCEventId, binding: T, semantic: ArcThreadLocalRCIRSemantic) {
        val event = expected[id] ?: error("unknown proven-local ARC ownership event: $id")
        check(event.binding === binding) { "proven-local ARC event binding identity drifted: $id" }
        check(event.semantic === semantic) { "proven-local ARC event semantic drifted: $id" }
        check(consumed.add(id)) { "duplicate proven-local ARC ownership event: $id" }
    }

    fun verifyComplete() {
        check(consumed == expected.keys) {
            "proven-local ARC emission incomplete: missing=${expected.keys - consumed}, unexpected=${consumed - expected.keys}"
        }
    }
}

internal enum class ArcThreadLocalRCIRRejectionReason {
    SuspendFunction,
    IncompleteLoweredIRWalk,
    EmptyBody,
    DuplicateBinding,
    InvalidCFG,
    InvalidEvent,
    NonLocalAllocation,
    NonHeapAllocation,
    CoroutineStateObject,
    UnauthenticatedLocalCall,
    ForbiddenPublicationFamily,
    UnclassifiedLoweredIR,
    InvalidExceptionalCleanup,
    AnalysisRejected,
    IncompleteEventBinding,
}

internal data class ArcThreadLocalRCIRRejection(
    val reason: ArcThreadLocalRCIRRejectionReason,
    val detail: String,
    val event: ArcThreadLocalRCEventId? = null,
)

internal data class ArcThreadLocalRCIRAdapterResult<T : Any>(
    val accepted: List<ArcThreadLocalRCIRSelection<T>>,
    val rejected: List<ArcThreadLocalRCIRRejection>,
)

/**
 * Converts a complete lowered-IR inventory into the closed ownership/identity proof.
 *
 * This adapter is intentionally all-or-nothing per function. One unclassified intrinsic or hidden
 * publication family prevents every non-atomic selection in that body; widening can happen only
 * after the IR walker and the effect model grow together.
 */
internal object ArcThreadLocalRCIRAdapter {
    fun <T : Any> adapt(body: ArcThreadLocalRCLoweredBody<T>): ArcThreadLocalRCIRAdapterResult<T> {
        val structural = validateStructure(body)
        if (structural.isNotEmpty()) return ArcThreadLocalRCIRAdapterResult(emptyList(), structural)

        val cfgBlocks = linkedMapOf<ArcBlockId, ArcSSABlock>()
        val rootFacts = linkedMapOf<ArcSSAValue, ArcThreadLocalRootFact>()
        val effects = mutableListOf<ArcThreadLocalRCEffect>()
        val ownership = mutableListOf<ArcThreadLocalRCOperation>()
        val bindings = linkedMapOf<ArcThreadLocalRCEventId, ArcThreadLocalRCEventBinding<T>>()
        val modeledValues = linkedSetOf<ArcSSAValue>()

        body.blocks.values.forEach { block ->
            val operations = mutableListOf<ArcSSAOperation>()
            block.events.forEachIndexed { index, event ->
                val position = ArcRCPosition(block.id, index)
                val id = ArcThreadLocalRCEventId(block.id, index)
                val operation = when (event) {
                    is ArcThreadLocalRCLoweredEvent.Allocation -> {
                        rootFacts[event.result] = ArcThreadLocalRootFact(
                            event.result,
                            event.lifetime,
                            ArcThreadLocalRootOrigin.LocalHeapAllocation,
                            event.isHeapAllocation,
                        )
                        modeledValues += event.result
                        bind(bindings, id, event.binding, ArcThreadLocalRCIRSemantic.Allocation, setOf(event.result))
                        ArcSSAOperation.Introduce(event.result, ArcOwnership.Owned)
                    }
                    is ArcThreadLocalRCLoweredEvent.Alias -> {
                        modeledValues += event.source
                        modeledValues += event.result
                        effects += ArcThreadLocalRCEffect(position, ArcThreadLocalRCEffectKind.Alias, event.source, event.result)
                        bind(bindings, id, event.binding, ArcThreadLocalRCIRSemantic.Alias, setOf(event.source, event.result))
                        ArcSSAOperation.Forward(event.source, event.result)
                    }
                    is ArcThreadLocalRCLoweredEvent.Phi -> {
                        modeledValues += event.result
                        modeledValues += event.incoming.values
                        event.incoming.values.forEach { incoming ->
                            effects += ArcThreadLocalRCEffect(position, ArcThreadLocalRCEffectKind.Alias, incoming, event.result)
                        }
                        bind(
                            bindings, id, event.binding, ArcThreadLocalRCIRSemantic.Phi,
                            event.incoming.values.toSet() + event.result,
                        )
                        ArcSSAOperation.Join(event.result, event.incoming)
                    }
                    is ArcThreadLocalRCLoweredEvent.StrongField -> {
                        modeledValues += event.owner
                        modeledValues += event.value
                        effects += ArcThreadLocalRCEffect(
                            position, ArcThreadLocalRCEffectKind.StrongField, event.owner, event.value,
                        )
                        bind(
                            bindings, id, event.binding, ArcThreadLocalRCIRSemantic.StrongField,
                            setOf(event.owner, event.value),
                        )
                        ArcSSAOperation.Use(event.value, ArcSSAUseKind.Borrow)
                    }
                    is ArcThreadLocalRCLoweredEvent.StrongCapture -> {
                        modeledValues += event.closure
                        modeledValues += event.value
                        effects += ArcThreadLocalRCEffect(
                            position, ArcThreadLocalRCEffectKind.StrongCapture, event.closure, event.value,
                        )
                        bind(
                            bindings, id, event.binding, ArcThreadLocalRCIRSemantic.StrongCapture,
                            setOf(event.closure, event.value),
                        )
                        ArcSSAOperation.Use(event.value, ArcSSAUseKind.Borrow)
                    }
                    is ArcThreadLocalRCLoweredEvent.LocalCall -> {
                        modeledValues += event.value
                        if (event.mayThrow) effects += ArcThreadLocalRCEffect(
                            position, ArcThreadLocalRCEffectKind.ThrowingLocalCall, event.value,
                        )
                        bind(bindings, id, event.binding, ArcThreadLocalRCIRSemantic.LocalCall, setOf(event.value))
                        ArcSSAOperation.Use(event.value, ArcSSAUseKind.Borrow, mayThrow = event.mayThrow)
                    }
                    is ArcThreadLocalRCLoweredEvent.Retain -> {
                        modeledValues += event.value
                        ownership += ArcThreadLocalRCOperation(
                            position, 0, ArcThreadLocalRCOperationKind.Retain, event.value,
                        )
                        bind(bindings, id, event.binding, ArcThreadLocalRCIRSemantic.Retain, setOf(event.value))
                        ArcSSAOperation.Use(event.value, ArcSSAUseKind.Borrow)
                    }
                    is ArcThreadLocalRCLoweredEvent.Release -> {
                        modeledValues += event.value
                        ownership += ArcThreadLocalRCOperation(
                            position, 0, ArcThreadLocalRCOperationKind.Release, event.value,
                        )
                        bind(bindings, id, event.binding, ArcThreadLocalRCIRSemantic.Release, setOf(event.value))
                        ArcSSAOperation.Use(event.value, ArcSSAUseKind.Consume)
                    }
                    is ArcThreadLocalRCLoweredEvent.Forbidden,
                    is ArcThreadLocalRCLoweredEvent.Unclassified -> error("validated above")
                }
                operations += operation
            }
            cfgBlocks[block.id] = ArcSSABlock(block.id, operations)
        }

        val cleanups = body.exceptionCleanups.map { cleanup ->
            ArcThreadLocalExceptionCleanup(
                ArcRCPosition(cleanup.call.block, cleanup.call.operationIndex),
                cleanup.exceptionalEdge,
                ArcRCPosition(cleanup.release.block, cleanup.release.operationIndex),
                releaseOrdinal = 0,
            )
        }
        val coverage = ArcThreadLocalRCCoverage(
            modeledValues,
            allReferenceRelationsEnumerated = true,
            allOwnershipOperationsEnumerated = true,
            allEscapesAndCallsClassified = true,
        )
        val proof = ArcThreadLocalRCAnalysis.analyze(
            ArcThreadLocalRCInput(
                ArcRCIdentityInput(ArcOwnershipSSAInput(body.entry, cfgBlocks, body.edges), emptyList()),
                rootFacts,
                effects,
                ownership,
                cleanups,
                coverage,
            )
        )
        if (proof.rejected.isNotEmpty() || proof.accepted.isEmpty()) {
            val detail = proof.rejected.joinToString { "${it.reason}: ${it.detail}" }
            return ArcThreadLocalRCIRAdapterResult(
                emptyList(),
                listOf(ArcThreadLocalRCIRRejection(ArcThreadLocalRCIRRejectionReason.AnalysisRejected, detail)),
            )
        }

        val selected = proof.accepted.map { plan ->
            val planBindings = bindings.filterValues { binding -> binding.values.any { it in plan.values } }
            ArcThreadLocalRCIRSelection(body.functionBinding, plan, planBindings, coverage)
        }
        val expectedBoundEvents = body.blocks.values.sumOf { it.events.size }
        if (bindings.size != expectedBoundEvents || selected.flatMap { it.eventBindings.keys }.toSet() != bindings.keys) {
            return ArcThreadLocalRCIRAdapterResult(
                emptyList(),
                listOf(ArcThreadLocalRCIRRejection(
                    ArcThreadLocalRCIRRejectionReason.IncompleteEventBinding,
                    "every lowered event must be bound to exactly one accepted identity plan",
                )),
            )
        }
        return ArcThreadLocalRCIRAdapterResult(selected, emptyList())
    }

    private fun <T : Any> validateStructure(body: ArcThreadLocalRCLoweredBody<T>): List<ArcThreadLocalRCIRRejection> = buildList {
        if (body.isSuspendFunction) add(reject(ArcThreadLocalRCIRRejectionReason.SuspendFunction, "suspend functions cannot prove one-thread lifetime"))
        if (!body.completeLoweredIRWalk) add(reject(
            ArcThreadLocalRCIRRejectionReason.IncompleteLoweredIRWalk,
            "the lowered body walk did not classify every nested node and intrinsic",
        ))
        if (body.blocks.isEmpty() || body.blocks.values.all { it.events.isEmpty() }) {
            add(reject(ArcThreadLocalRCIRRejectionReason.EmptyBody, "no lowered ownership events"))
        }
        if (body.entry !in body.blocks || body.blocks.any { it.key != it.value.id } ||
            body.edges.any { it.from !in body.blocks || it.to !in body.blocks }
        ) add(reject(ArcThreadLocalRCIRRejectionReason.InvalidCFG, "missing or inconsistent CFG block"))

        val bindings = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        val definitions = linkedSetOf<ArcSSAValue>()
        val ids = linkedSetOf<ArcThreadLocalRCEventId>()
        body.blocks.values.forEach { block ->
            block.events.forEachIndexed { index, event ->
                val id = ArcThreadLocalRCEventId(block.id, index)
                if (!ids.add(id)) add(reject(ArcThreadLocalRCIRRejectionReason.InvalidEvent, "duplicate event id", id))
                if (!bindings.add(event.binding)) add(reject(
                    ArcThreadLocalRCIRRejectionReason.DuplicateBinding,
                    "one lowered IR node was classified more than once", id,
                ))
                when (event) {
                    is ArcThreadLocalRCLoweredEvent.Allocation -> {
                        if (!definitions.add(event.result)) add(reject(
                            ArcThreadLocalRCIRRejectionReason.InvalidEvent, "duplicate allocation definition", id,
                        ))
                        if (event.lifetime !== Lifetime.LOCAL) add(reject(
                            ArcThreadLocalRCIRRejectionReason.NonLocalAllocation,
                            "${event.result} has lifetime ${event.lifetime}, not LOCAL", id,
                        ))
                        if (!event.isHeapAllocation) add(reject(
                            ArcThreadLocalRCIRRejectionReason.NonHeapAllocation,
                            "${event.result} is not an individual heap allocation", id,
                        ))
                        if (event.isCoroutineStateObject) add(reject(
                            ArcThreadLocalRCIRRejectionReason.CoroutineStateObject,
                            "coroutine state can publish through COROUTINE_SUSPENDED or continuation callbacks", id,
                        ))
                    }
                    is ArcThreadLocalRCLoweredEvent.Alias -> if (!definitions.add(event.result)) add(reject(
                        ArcThreadLocalRCIRRejectionReason.InvalidEvent, "duplicate alias definition", id,
                    ))
                    is ArcThreadLocalRCLoweredEvent.Phi -> if (event.incoming.isEmpty() || !definitions.add(event.result)) add(reject(
                        ArcThreadLocalRCIRRejectionReason.InvalidEvent, "empty or duplicate phi definition", id,
                    ))
                    is ArcThreadLocalRCLoweredEvent.LocalCall -> if (!event.hasClosedNoPublicationSummary) add(reject(
                        ArcThreadLocalRCIRRejectionReason.UnauthenticatedLocalCall,
                        "even a direct local call must have a closed no-publication summary", id,
                    ))
                    is ArcThreadLocalRCLoweredEvent.Forbidden -> add(reject(
                        ArcThreadLocalRCIRRejectionReason.ForbiddenPublicationFamily,
                        "${event.kind} is never eligible for the first non-atomic slice", id,
                    ))
                    is ArcThreadLocalRCLoweredEvent.Unclassified -> add(reject(
                        ArcThreadLocalRCIRRejectionReason.UnclassifiedLoweredIR, event.description, id,
                    ))
                    is ArcThreadLocalRCLoweredEvent.StrongField,
                    is ArcThreadLocalRCLoweredEvent.StrongCapture,
                    is ArcThreadLocalRCLoweredEvent.Retain,
                    is ArcThreadLocalRCLoweredEvent.Release -> Unit
                }
            }
        }
        if (definitions.none { value -> body.blocks.values.any { block ->
                block.events.any { it is ArcThreadLocalRCLoweredEvent.Allocation && it.result == value }
            } }) add(reject(ArcThreadLocalRCIRRejectionReason.InvalidEvent, "no local heap allocation root"))

        body.exceptionCleanups.forEach { cleanup ->
            val call = body.event(cleanup.call)
            val release = body.event(cleanup.release)
            if (call !is ArcThreadLocalRCLoweredEvent.LocalCall || !call.mayThrow ||
                release !is ArcThreadLocalRCLoweredEvent.Release || cleanup.exceptionalEdge !in body.edges ||
                cleanup.exceptionalEdge.from != cleanup.call.block || cleanup.exceptionalEdge.kind != ArcSSAEdgeKind.Exceptional
            ) add(reject(
                ArcThreadLocalRCIRRejectionReason.InvalidExceptionalCleanup,
                "cleanup must bind one exact throwing call, exceptional edge, and release", cleanup.call,
            ))
        }
    }

    private fun <T : Any> ArcThreadLocalRCLoweredBody<T>.event(id: ArcThreadLocalRCEventId): ArcThreadLocalRCLoweredEvent<T>? =
        blocks[id.block]?.events?.getOrNull(id.operationIndex)

    private fun reject(
        reason: ArcThreadLocalRCIRRejectionReason,
        detail: String,
        event: ArcThreadLocalRCEventId? = null,
    ) = ArcThreadLocalRCIRRejection(reason, detail, event)

    private fun <T : Any> bind(
        target: MutableMap<ArcThreadLocalRCEventId, ArcThreadLocalRCEventBinding<T>>,
        id: ArcThreadLocalRCEventId,
        binding: T,
        semantic: ArcThreadLocalRCIRSemantic,
        values: Set<ArcSSAValue>,
    ) {
        check(target.put(id, ArcThreadLocalRCEventBinding(id, binding, semantic, values)) == null) {
            "duplicate stable ARC event id: $id"
        }
    }
}
