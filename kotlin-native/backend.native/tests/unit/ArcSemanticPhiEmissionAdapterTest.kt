/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcSemanticPhiEmissionAdapterTest {
    private val entry = ArcBlockId("entry")
    private val left = ArcBlockId("left")
    private val right = ArcBlockId("right")
    private val merge = ArcBlockId("merge")
    private val normal = ArcBlockId("normal")
    private val handler = ArcBlockId("handler")

    @Test
    fun exactExceptionalOperationAndSuccessorProduceConsumableActions() {
        val fixture = exceptionalFixture(throwingUses = 1)
        val body = loweredBody(fixture.input)
        val call = ArcSemanticEmissionOperationId(merge, 1)
        val edge = ArcSSAEdge(merge, handler, ArcSSAEdgeKind.Exceptional)
        val bound = body.copy(exceptionalSuccessors = listOf(
            ArcSemanticExceptionalSuccessorBinding(
                call,
                body.operationBindings.getValue(call),
                edge,
                body.blockBindings.getValue(handler),
            )
        ))

        val result = ArcSemanticPhiEmissionAdapter.adapt(fixture.input, fixture.plan, bound)

        assertTrue(result.rejections.toString(), result.rejections.isEmpty())
        val selection = result.selection!!
        val edgeAction = selection.actions.keys.filterIsInstance<ArcSemanticEmissionActionId.EndOnEdge>().single()
        assertEquals(call, edgeAction.throwingOperation)
        assertEquals(edge, edgeAction.edge)

        ArcSemanticPhiEmissionLedger(selection, selection.functionBinding).also { ledger ->
            selection.actions.values.forEach { ledger.consume(it.id, it.bindingIdentities) }
            ledger.verifyComplete()
        }
    }

    @Test
    fun twoMayThrowOperationsInOneBlockCannotInstantiateBlockOnlyExceptionalFrontier() {
        val fixture = exceptionalFixture(throwingUses = 2)
        val body = loweredBody(fixture.input)
        val call = ArcSemanticEmissionOperationId(merge, 1)
        val edge = ArcSSAEdge(merge, handler, ArcSSAEdgeKind.Exceptional)
        val result = ArcSemanticPhiEmissionAdapter.adapt(
            fixture.input,
            fixture.plan,
            body.copy(exceptionalSuccessors = listOf(
                ArcSemanticExceptionalSuccessorBinding(
                    call,
                    body.operationBindings.getValue(call),
                    edge,
                    body.blockBindings.getValue(handler),
                )
            )),
        )

        assertRejected(result, ArcSemanticPhiEmissionRejectionReason.AmbiguousExceptionalSuccessor)
    }

    @Test
    fun duplicateCriticalEdgeMappingIsRejectedEvenWhenBothMappingsNameThePlannedEdge() {
        val fixture = criticalEdgeFixture()
        val body = loweredBody(fixture.input)
        val edge = fixture.plan.frontier.filterIsInstance<ArcSemanticLifetimeFrontier.OnEdge>()
            .single { it.requiresEdgeSplit }.edge
        val result = ArcSemanticPhiEmissionAdapter.adapt(
            fixture.input,
            fixture.plan,
            body.copy(criticalEdgeSplits = listOf(
                ArcSemanticCriticalEdgeSplitBinding(edge, ArcBlockId("split.one"), Any()),
                ArcSemanticCriticalEdgeSplitBinding(edge, ArcBlockId("split.two"), Any()),
            )),
        )

        assertRejected(result, ArcSemanticPhiEmissionRejectionReason.DuplicateCriticalEdgeMapping)
    }

    @Test
    fun criticalEdgeSelectionCarriesUniqueSplitBlockIdentity() {
        val fixture = criticalEdgeFixture()
        val body = loweredBody(fixture.input)
        val edge = fixture.plan.frontier.filterIsInstance<ArcSemanticLifetimeFrontier.OnEdge>()
            .single { it.requiresEdgeSplit }.edge
        val splitId = ArcBlockId("split.merge.dead")
        val splitBinding = Any()
        val result = ArcSemanticPhiEmissionAdapter.adapt(
            fixture.input,
            fixture.plan,
            body.copy(criticalEdgeSplits = listOf(
                ArcSemanticCriticalEdgeSplitBinding(edge, splitId, splitBinding),
            )),
        )

        assertTrue(result.rejections.toString(), result.rejections.isEmpty())
        val action = result.selection!!.actions.values.single {
            val id = it.id
            id is ArcSemanticEmissionActionId.EndOnEdge && sameEdge(id.edge, edge)
        }
        assertTrue(action.bindingIdentities.last() === splitBinding)
        assertEquals(splitId, (action.id as ArcSemanticEmissionActionId.EndOnEdge).splitBlock)
    }

    @Test
    fun adapterAndLedgerRejectShapeDriftAndMissingConsumption() {
        val fixture = exceptionalFixture(throwingUses = 1)
        val body = loweredBody(fixture.input)
        val call = ArcSemanticEmissionOperationId(merge, 1)
        val edge = ArcSSAEdge(merge, handler, ArcSSAEdgeKind.Exceptional)
        val complete = body.copy(exceptionalSuccessors = listOf(
            ArcSemanticExceptionalSuccessorBinding(
                call,
                body.operationBindings.getValue(call),
                edge,
                body.blockBindings.getValue(handler),
            )
        ))
        val missingOperation = complete.copy(operationBindings = complete.operationBindings - call)
        assertRejected(
            ArcSemanticPhiEmissionAdapter.adapt(fixture.input, fixture.plan, missingOperation),
            ArcSemanticPhiEmissionRejectionReason.ShapeDrift,
        )

        val selection = ArcSemanticPhiEmissionAdapter.adapt(fixture.input, fixture.plan, complete).selection!!
        expectFailure { ArcSemanticPhiEmissionLedger(selection, selection.functionBinding).verifyComplete() }
        expectFailure { ArcSemanticPhiEmissionLedger(selection, Any()) }

        val first = selection.actions.values.first()
        expectFailure {
            ArcSemanticPhiEmissionLedger(selection, selection.functionBinding).consume(
                first.id,
                first.bindingIdentities.mapIndexed { index, binding -> if (index == 0) Any() else binding },
            )
        }
        expectFailure {
            ArcSemanticPhiEmissionLedger(selection, selection.functionBinding).also { ledger ->
                ledger.consume(first.id, first.bindingIdentities)
                ledger.consume(first.id, first.bindingIdentities)
            }
        }
    }

    @Test
    fun barrierMustBindTheExactLoweredOperationAtItsPosition() {
        val fixture = barrierFixture()
        val body = loweredBody(fixture.input)
        val barrier = fixture.plan.frontier.filterIsInstance<ArcSemanticLifetimeFrontier.BeforeBarrier>()
            .single().barrier
        val position = ArcSemanticEmissionOperationId(barrier.block, barrier.operationIndex)

        val forged = ArcSemanticPhiEmissionAdapter.adapt(
            fixture.input,
            fixture.plan,
            body.copy(barrierBindings = listOf(ArcSemanticBarrierEmissionBinding(barrier, Any()))),
        )
        assertRejected(forged, ArcSemanticPhiEmissionRejectionReason.MissingBarrierBinding)

        val exact = ArcSemanticPhiEmissionAdapter.adapt(
            fixture.input,
            fixture.plan,
            body.copy(barrierBindings = listOf(
                ArcSemanticBarrierEmissionBinding(barrier, body.operationBindings.getValue(position)),
            )),
        )
        assertTrue(exact.rejections.toString(), exact.rejections.isEmpty())
    }

    private data class Fixture(val input: ArcSemanticARCInput, val plan: ArcSemanticPhiWebPlan)

    private fun exceptionalFixture(throwingUses: Int): Fixture {
        val values = values("exceptional")
        val throwing = List(throwingUses) { ArcSSAOperation.Use(values.joined, ArcSSAUseKind.Borrow, mayThrow = true) }
        val cfg = ArcOwnershipSSAInput(
            entry,
            linkedMapOf(
                entry to block(entry),
                left to seedBlock(left, values.leftAnchor, values.leftSource, values.leftCopy),
                right to seedBlock(right, values.rightAnchor, values.rightSource, values.rightCopy),
                merge to block(
                    merge,
                    ArcSSAOperation.Join(values.joined, linkedMapOf(left to values.leftCopy, right to values.rightCopy)),
                    *throwing.toTypedArray(),
                ),
                normal to block(normal),
                handler to block(handler),
            ),
            setOf(
                ArcSSAEdge(entry, left), ArcSSAEdge(entry, right),
                ArcSSAEdge(left, merge), ArcSSAEdge(right, merge),
                ArcSSAEdge(merge, normal), ArcSSAEdge(merge, handler, ArcSSAEdgeKind.Exceptional),
            ),
        )
        return fixture(cfg, values)
    }

    private fun criticalEdgeFixture(): Fixture {
        val live = ArcBlockId("live")
        val dead = ArcBlockId("dead")
        val other = ArcBlockId("other")
        val values = values("critical")
        val cfg = ArcOwnershipSSAInput(
            entry,
            linkedMapOf(
                entry to block(entry),
                left to seedBlock(left, values.leftAnchor, values.leftSource, values.leftCopy),
                right to seedBlock(right, values.rightAnchor, values.rightSource, values.rightCopy),
                merge to block(
                    merge,
                    ArcSSAOperation.Join(values.joined, linkedMapOf(left to values.leftCopy, right to values.rightCopy)),
                    ArcSSAOperation.Use(values.joined, ArcSSAUseKind.Borrow),
                ),
                live to block(live, ArcSSAOperation.Use(values.joined, ArcSSAUseKind.Borrow)),
                dead to block(dead),
                other to block(other),
            ),
            setOf(
                ArcSSAEdge(entry, left), ArcSSAEdge(entry, right),
                ArcSSAEdge(left, merge), ArcSSAEdge(right, merge),
                ArcSSAEdge(merge, live), ArcSSAEdge(merge, dead), ArcSSAEdge(other, dead),
            ),
        )
        return fixture(cfg, values)
    }

    private fun barrierFixture(): Fixture {
        val values = values("barrier")
        val cfg = ArcOwnershipSSAInput(
            entry,
            linkedMapOf(
                entry to block(entry),
                left to seedBlock(left, values.leftAnchor, values.leftSource, values.leftCopy),
                right to seedBlock(right, values.rightAnchor, values.rightSource, values.rightCopy),
                merge to block(
                    merge,
                    ArcSSAOperation.Join(values.joined, linkedMapOf(left to values.leftCopy, right to values.rightCopy)),
                    ArcSSAOperation.Use(values.joined, ArcSSAUseKind.Borrow),
                    ArcSSAOperation.DeinitBarrier(),
                ),
                normal to block(normal),
            ),
            setOf(
                ArcSSAEdge(entry, left), ArcSSAEdge(entry, right),
                ArcSSAEdge(left, merge), ArcSSAEdge(right, merge), ArcSSAEdge(merge, normal),
            ),
        )
        return fixture(cfg, values)
    }

    private data class Values(
        val leftAnchor: ArcSSAValue,
        val leftSource: ArcSSAValue,
        val leftCopy: ArcSSAValue,
        val rightAnchor: ArcSSAValue,
        val rightSource: ArcSSAValue,
        val rightCopy: ArcSSAValue,
        val joined: ArcSSAValue,
    )

    private fun values(prefix: String) = Values(
        ArcSSAValue("$prefix.left.anchor"),
        ArcSSAValue("$prefix.left.source"),
        ArcSSAValue("$prefix.left.copy"),
        ArcSSAValue("$prefix.right.anchor"),
        ArcSSAValue("$prefix.right.source"),
        ArcSSAValue("$prefix.right.copy"),
        ArcSSAValue("$prefix.joined"),
    )

    private fun fixture(cfg: ArcOwnershipSSAInput, values: Values): Fixture {
        val input = ArcSemanticARCInput(
            cfg,
            setOf(
                ArcGuaranteedCopySeed(values.leftCopy, values.leftSource, setOf(values.leftAnchor)),
                ArcGuaranteedCopySeed(values.rightCopy, values.rightSource, setOf(values.rightAnchor)),
            ),
        )
        val result = ArcSemanticPhiWebAnalysis.analyze(input)
        assertTrue(result.rejected.toString(), result.rejected.isEmpty())
        return Fixture(input, result.accepted.single())
    }

    private fun loweredBody(input: ArcSemanticARCInput): ArcSemanticPhiLoweredBody<Any> =
        ArcSemanticPhiLoweredBody(
            functionBinding = Any(),
            cfg = input.cfg,
            blockBindings = input.cfg.blocks.keys.associateWith { Any() },
            operationBindings = input.cfg.blocks.values.flatMap { block ->
                block.operations.indices.map { ArcSemanticEmissionOperationId(block.id, it) to Any() }
            }.toMap(),
            completeLoweredIRWalk = true,
        )

    private fun seedBlock(
        id: ArcBlockId,
        anchor: ArcSSAValue,
        source: ArcSSAValue,
        copy: ArcSSAValue,
    ) = block(
        id,
        ArcSSAOperation.Introduce(anchor, ArcOwnership.Immortal),
        ArcSSAOperation.Introduce(source, ArcOwnership.Guaranteed, setOf(anchor)),
        ArcSSAOperation.Introduce(copy, ArcOwnership.Owned),
    )

    private fun block(id: ArcBlockId, vararg operations: ArcSSAOperation) = ArcSSABlock(id, operations.toList())

    private fun assertRejected(
        result: ArcSemanticPhiEmissionAdapterResult<Any>,
        reason: ArcSemanticPhiEmissionRejectionReason,
    ) {
        assertTrue(result.rejections.toString(), result.rejections.any { it.reason === reason })
        assertTrue(result.selection === null)
    }

    private fun expectFailure(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue("expected fail-closed emission rejection", failed)
    }

    private fun sameEdge(left: ArcSSAEdge, right: ArcSSAEdge): Boolean =
        left.from.name.compareTo(right.from.name) == 0 &&
                left.to.name.compareTo(right.to.name) == 0 && left.kind === right.kind
}
