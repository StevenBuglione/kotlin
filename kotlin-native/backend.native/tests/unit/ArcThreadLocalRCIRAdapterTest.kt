/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.llvm.Lifetime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcThreadLocalRCIRAdapterTest {
    private val entry = ArcBlockId("entry")

    @Test
    fun directLocalHeapAllocationProducesStableBoundEventsAndClosedCoverage() {
        val root = ArcSSAValue("root")
        val allocation = Any()
        val release = Any()
        val body = linear(
            listOf(
                allocation(allocation, root),
                ArcThreadLocalRCLoweredEvent.Release(release, root),
            )
        )

        val result = ArcThreadLocalRCIRAdapter.adapt(body)

        assertTrue(result.rejected.toString(), result.rejected.isEmpty())
        val selection = result.accepted.single()
        assertEquals(setOf(root), selection.plan.provenanceRoots)
        assertTrue(selection.coverage.allReferenceRelationsEnumerated)
        assertTrue(selection.coverage.allOwnershipOperationsEnumerated)
        assertTrue(selection.coverage.allEscapesAndCallsClassified)
        assertSame(allocation, selection.eventBindings.getValue(ArcThreadLocalRCEventId(entry, 0)).binding)
        assertSame(release, selection.eventBindings.getValue(ArcThreadLocalRCEventId(entry, 1)).binding)
        assertEquals(setOf(ArcThreadLocalRCEventId(entry, 1)), selection.expectedOwnershipEvents)

        val id = ArcThreadLocalRCEventId(entry, 1)
        ArcThreadLocalRCEventConsumptionLedger(selection).also { ledger ->
            ledger.consume(id, release, ArcThreadLocalRCIRSemantic.Release)
            ledger.verifyComplete()
        }
        expectFailure { ArcThreadLocalRCEventConsumptionLedger(selection).verifyComplete() }
        expectFailure {
            ArcThreadLocalRCEventConsumptionLedger(selection)
                .consume(id, Any(), ArcThreadLocalRCIRSemantic.Release)
        }
        expectFailure {
            ArcThreadLocalRCEventConsumptionLedger(selection).also { ledger ->
                ledger.consume(id, release, ArcThreadLocalRCIRSemantic.Release)
                ledger.consume(id, release, ArcThreadLocalRCIRSemantic.Release)
            }
        }
    }

    @Test
    fun branchAllocationsAndPhiBecomeOneCompleteIdentityPlan() {
        val left = ArcBlockId("left")
        val right = ArcBlockId("right")
        val merge = ArcBlockId("merge")
        val leftValue = ArcSSAValue("left")
        val rightValue = ArcSSAValue("right")
        val joined = ArcSSAValue("joined")
        val body = ArcThreadLocalRCLoweredBody(
            functionBinding = Any(),
            entry = entry,
            blocks = linkedMapOf(
                entry to block(entry),
                left to block(left, allocation(Any(), leftValue)),
                right to block(right, allocation(Any(), rightValue)),
                merge to block(
                    merge,
                    ArcThreadLocalRCLoweredEvent.Phi(
                        Any(), joined, linkedMapOf(left to leftValue, right to rightValue),
                    ),
                    ArcThreadLocalRCLoweredEvent.Release(Any(), joined),
                ),
            ),
            edges = setOf(
                ArcSSAEdge(entry, left), ArcSSAEdge(entry, right),
                ArcSSAEdge(left, merge), ArcSSAEdge(right, merge),
            ),
            isSuspendFunction = false,
            completeLoweredIRWalk = true,
        )

        val result = ArcThreadLocalRCIRAdapter.adapt(body)

        assertTrue(result.rejected.toString(), result.rejected.isEmpty())
        assertEquals(setOf(leftValue, rightValue), result.accepted.single().plan.provenanceRoots)
        assertEquals(4, result.accepted.single().eventBindings.size)
    }

    @Test
    fun strongFieldsAndCapturesCloseTheWholeLocalObjectGraph() {
        val owner = ArcSSAValue("owner")
        val child = ArcSSAValue("child")
        val closure = ArcSSAValue("closure")
        val body = linear(
            listOf(
                allocation(Any(), owner),
                allocation(Any(), child),
                allocation(Any(), closure),
                ArcThreadLocalRCLoweredEvent.StrongField(Any(), owner, child),
                ArcThreadLocalRCLoweredEvent.StrongCapture(Any(), closure, owner),
                ArcThreadLocalRCLoweredEvent.Release(Any(), owner),
                ArcThreadLocalRCLoweredEvent.Release(Any(), child),
                ArcThreadLocalRCLoweredEvent.Release(Any(), closure),
            )
        )

        val result = ArcThreadLocalRCIRAdapter.adapt(body)

        assertTrue(result.rejected.toString(), result.rejected.isEmpty())
        val plan = result.accepted.single().plan
        assertEquals(setOf(owner, child, closure), plan.provenanceRoots)
        assertEquals(
            setOf(ArcThreadLocalRCEffectKind.StrongField, ArcThreadLocalRCEffectKind.StrongCapture),
            plan.effects.mapTo(linkedSetOf()) { it.kind },
        )
    }

    @Test
    fun exactPreSplitExceptionalCleanupIsAuthenticated() {
        val normal = ArcBlockId("normal")
        val handler = ArcBlockId("handler")
        val root = ArcSSAValue("root")
        val exceptional = ArcSSAEdge(entry, handler, ArcSSAEdgeKind.Exceptional)
        val body = ArcThreadLocalRCLoweredBody(
            functionBinding = Any(),
            entry = entry,
            blocks = linkedMapOf(
                entry to block(
                    entry,
                    allocation(Any(), root),
                    ArcThreadLocalRCLoweredEvent.LocalCall(
                        Any(), root, mayThrow = true, hasClosedNoPublicationSummary = true,
                    ),
                ),
                normal to block(normal, ArcThreadLocalRCLoweredEvent.Release(Any(), root)),
                handler to block(handler, ArcThreadLocalRCLoweredEvent.Release(Any(), root)),
            ),
            edges = setOf(ArcSSAEdge(entry, normal), exceptional),
            isSuspendFunction = false,
            completeLoweredIRWalk = true,
            exceptionCleanups = listOf(
                ArcThreadLocalRCLoweredExceptionCleanup(
                    ArcThreadLocalRCEventId(entry, 1), exceptional, ArcThreadLocalRCEventId(handler, 0),
                )
            ),
        )

        val result = ArcThreadLocalRCIRAdapter.adapt(body)

        assertTrue(result.rejected.toString(), result.rejected.isEmpty())
        assertEquals(1, result.accepted.single().plan.exceptionCleanups.size)

        val missing = ArcThreadLocalRCIRAdapter.adapt(body.copy(exceptionCleanups = emptyList()))
        assertRejected(missing, ArcThreadLocalRCIRRejectionReason.AnalysisRejected)
    }

    @Test
    fun everyHiddenIntrinsicAndPublicationFamilyRejectsTheWholeBody() {
        val forbidden = listOf(
            ArcThreadLocalRCEffectKind.WorkerTransfer,
            ArcThreadLocalRCEffectKind.Atomic,
            ArcThreadLocalRCEffectKind.Weak,
            ArcThreadLocalRCEffectKind.Unowned,
            ArcThreadLocalRCEffectKind.StableRef,
            ArcThreadLocalRCEffectKind.ForeignCall,
            ArcThreadLocalRCEffectKind.Callback,
            ArcThreadLocalRCEffectKind.Suspension,
            ArcThreadLocalRCEffectKind.GlobalStore,
            ArcThreadLocalRCEffectKind.ThreadLocalStore,
            ArcThreadLocalRCEffectKind.Return,
            ArcThreadLocalRCEffectKind.Throw,
            ArcThreadLocalRCEffectKind.UnknownCall,
            ArcThreadLocalRCEffectKind.UnknownEscape,
        )
        forbidden.forEach { kind ->
            val root = ArcSSAValue("root_${kind.name}")
            val result = ArcThreadLocalRCIRAdapter.adapt(
                linear(
                    listOf(
                        allocation(Any(), root),
                        ArcThreadLocalRCLoweredEvent.Forbidden(Any(), root, kind),
                        ArcThreadLocalRCLoweredEvent.Release(Any(), root),
                    )
                )
            )
            assertTrue("$kind unexpectedly accepted", result.accepted.isEmpty())
            assertRejected(result, ArcThreadLocalRCIRRejectionReason.ForbiddenPublicationFamily)
        }
    }

    @Test
    fun incompleteWalkUnknownNodeDuplicateBindingAndUnauthenticatedCallFailClosed() {
        val root = ArcSSAValue("root")
        val complete = linear(
            listOf(allocation(Any(), root), ArcThreadLocalRCLoweredEvent.Release(Any(), root))
        )
        assertRejected(
            ArcThreadLocalRCIRAdapter.adapt(complete.copy(completeLoweredIRWalk = false)),
            ArcThreadLocalRCIRRejectionReason.IncompleteLoweredIRWalk,
        )
        assertRejected(
            ArcThreadLocalRCIRAdapter.adapt(
                linear(listOf(allocation(Any(), root), ArcThreadLocalRCLoweredEvent.Unclassified(Any(), "new intrinsic")))
            ),
            ArcThreadLocalRCIRRejectionReason.UnclassifiedLoweredIR,
        )

        val sharedBinding = Any()
        assertRejected(
            ArcThreadLocalRCIRAdapter.adapt(
                linear(listOf(allocation(sharedBinding, root), ArcThreadLocalRCLoweredEvent.Release(sharedBinding, root)))
            ),
            ArcThreadLocalRCIRRejectionReason.DuplicateBinding,
        )
        assertRejected(
            ArcThreadLocalRCIRAdapter.adapt(
                linear(listOf(
                    allocation(Any(), root),
                    ArcThreadLocalRCLoweredEvent.LocalCall(
                        Any(), root, mayThrow = false, hasClosedNoPublicationSummary = false,
                    ),
                    ArcThreadLocalRCLoweredEvent.Release(Any(), root),
                ))
            ),
            ArcThreadLocalRCIRRejectionReason.UnauthenticatedLocalCall,
        )
    }

    @Test
    fun nonLocalArenaAndCoroutineStateAllocationsNeverQualify() {
        val root = ArcSSAValue("root")
        assertRejected(
            ArcThreadLocalRCIRAdapter.adapt(
                linear(listOf(
                    allocation(Any(), root, lifetime = Lifetime.GLOBAL),
                    ArcThreadLocalRCLoweredEvent.Release(Any(), root),
                ))
            ),
            ArcThreadLocalRCIRRejectionReason.NonLocalAllocation,
        )
        assertRejected(
            ArcThreadLocalRCIRAdapter.adapt(
                linear(listOf(
                    allocation(Any(), root, isHeap = false),
                    ArcThreadLocalRCLoweredEvent.Release(Any(), root),
                ))
            ),
            ArcThreadLocalRCIRRejectionReason.NonHeapAllocation,
        )
        val coroutine = linear(
            listOf(
                allocation(Any(), root, coroutineState = true),
                ArcThreadLocalRCLoweredEvent.Release(Any(), root),
            )
        ).copy(isSuspendFunction = true)
        val result = ArcThreadLocalRCIRAdapter.adapt(coroutine)
        assertRejected(result, ArcThreadLocalRCIRRejectionReason.SuspendFunction)
        assertRejected(result, ArcThreadLocalRCIRRejectionReason.CoroutineStateObject)
    }

    private fun linear(events: List<ArcThreadLocalRCLoweredEvent<Any>>) = ArcThreadLocalRCLoweredBody(
        functionBinding = Any(),
        entry = entry,
        blocks = mapOf(entry to ArcThreadLocalRCLoweredBlock(entry, events)),
        edges = emptySet(),
        isSuspendFunction = false,
        completeLoweredIRWalk = true,
    )

    private fun block(
        id: ArcBlockId,
        vararg events: ArcThreadLocalRCLoweredEvent<Any>,
    ) = ArcThreadLocalRCLoweredBlock(id, events.toList())

    private fun allocation(
        binding: Any,
        value: ArcSSAValue,
        lifetime: Lifetime = Lifetime.LOCAL,
        isHeap: Boolean = true,
        coroutineState: Boolean = false,
    ) = ArcThreadLocalRCLoweredEvent.Allocation(
        binding, value, lifetime, isHeapAllocation = isHeap, isCoroutineStateObject = coroutineState,
    )

    private fun assertRejected(
        result: ArcThreadLocalRCIRAdapterResult<Any>,
        reason: ArcThreadLocalRCIRRejectionReason,
    ) {
        assertTrue(result.rejected.toString(), result.rejected.any { it.reason === reason })
    }

    private fun expectFailure(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue("expected fail-closed ledger rejection", failed)
    }
}
