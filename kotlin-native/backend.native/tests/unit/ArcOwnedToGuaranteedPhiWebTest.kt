/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcOwnedToGuaranteedPhiWebTest {
    private val entry = ArcBlockId("entry")
    private val left = ArcBlockId("left")
    private val right = ArcBlockId("right")
    private val merge = ArcBlockId("merge")
    private val exit = ArcBlockId("exit")

    @Test
    fun convertsMultipleIncomingOwnedDefinitionsAsOneFixedPointWeb() {
        val values = Values()
        val cfg = cfg(
            blocks = listOf(
                block(entry, values.anchorDefinition, values.sourceDefinition),
                block(left, owned(values.leftCopy)),
                block(right, owned(values.rightCopy)),
                block(
                    merge,
                    join(values.joined, left to values.leftCopy, right to values.rightCopy),
                    borrow(values.joined),
                    destroy(values.joined),
                ),
            ),
            edges = setOf(edge(entry, left), edge(entry, right), edge(left, merge), edge(right, merge)),
        )

        val result = analyze(cfg, setOf(
            seed(values.leftCopy, values),
            seed(values.rightCopy, values),
        ))

        assertTrue(result.rejected.isEmpty())
        val plan = result.accepted.single()
        assertEquals(setOf(values.leftCopy, values.rightCopy, values.joined), plan.members)
        assertEquals(setOf(values.leftCopy, values.rightCopy), plan.seeds.mapTo(linkedSetOf()) { it.copy })
        assertEquals(2, plan.reborrows.size)
        assertEquals(setOf(values.anchor), plan.canonicalRCRoots)
        assertEquals(ArcPrunedBlockLiveness.LiveOut, plan.liveness.getValue(left))
        assertEquals(ArcPrunedBlockLiveness.LiveOut, plan.liveness.getValue(right))
    }

    @Test
    fun followsNestedForwardingAndMultiplePhiDefinitionsToFixedPoint() {
        val values = Values()
        val firstForward = ArcSSAValue("first.forward")
        val secondForward = ArcSSAValue("second.forward")
        val secondMerge = ArcBlockId("secondMerge")
        val finalValue = ArcSSAValue("final")
        val cfg = cfg(
            blocks = listOf(
                block(entry, values.anchorDefinition, values.sourceDefinition, owned(values.leftCopy)),
                block(left, forward(values.leftCopy, firstForward)),
                block(right, owned(values.rightCopy), forward(values.rightCopy, secondForward)),
                block(merge, join(values.joined, left to firstForward, right to secondForward)),
                block(secondMerge, join(finalValue, merge to values.joined, exit to values.leftCopy), borrow(finalValue), destroy(finalValue)),
                block(exit),
            ),
            edges = setOf(
                edge(entry, left), edge(entry, right), edge(left, merge), edge(right, merge),
                edge(merge, secondMerge), edge(entry, exit), edge(exit, secondMerge),
            ),
        )

        val result = analyze(cfg, setOf(seed(values.leftCopy, values), seed(values.rightCopy, values)))

        assertTrue(result.rejected.isEmpty())
        val plan = result.accepted.single()
        assertEquals(setOf(values.joined, finalValue), plan.joins)
        assertEquals(4, plan.reborrows.size)
        assertTrue(firstForward in plan.members && secondForward in plan.members)
    }

    @Test
    fun acceptsLoopCarriedOwnershipPhiWithFixedPointDependencies() {
        val values = Values()
        val loop = ArcBlockId("loop")
        val latch = ArcBlockId("latch")
        val loopValue = ArcSSAValue("loop.value")
        val next = ArcSSAValue("loop.next")
        val cfg = cfg(
            blocks = listOf(
                block(entry, values.anchorDefinition, values.sourceDefinition, owned(values.leftCopy)),
                block(loop, join(loopValue, entry to values.leftCopy, latch to next), borrow(loopValue)),
                block(latch, forward(loopValue, next)),
                block(exit, borrow(loopValue), destroy(loopValue)),
            ),
            edges = setOf(edge(entry, loop), edge(loop, latch), edge(latch, loop), edge(loop, exit)),
        )

        val result = analyze(cfg, setOf(seed(values.leftCopy, values)))

        assertTrue(result.rejected.joinToString(), result.rejected.isEmpty())
        val plan = result.accepted.single()
        assertEquals(setOf(values.leftCopy, loopValue, next), plan.members)
        assertEquals(2, plan.reborrows.size)
        assertTrue(plan.reborrows.all { it.anchorDependencies == setOf(values.anchor) })
    }

    @Test
    fun preservesExplicitReborrowInOwnershipPhiWeb() {
        val values = Values()
        val reborrowed = ArcSSAValue("reborrowed")
        val cfg = cfg(
            blocks = listOf(
                block(entry, values.anchorDefinition, values.sourceDefinition),
                block(
                    left,
                    owned(values.leftCopy),
                    ArcSSAOperation.Reborrow(values.leftCopy, reborrowed, setOf(values.anchor)),
                ),
                block(right, owned(values.rightCopy)),
                block(merge, join(values.joined, left to reborrowed, right to values.rightCopy), borrow(values.joined), destroy(values.joined)),
            ),
            edges = setOf(edge(entry, left), edge(entry, right), edge(left, merge), edge(right, merge)),
        )

        val result = analyze(cfg, setOf(seed(values.leftCopy, values), seed(values.rightCopy, values)))

        assertTrue(result.rejected.isEmpty())
        assertTrue(reborrowed in result.accepted.single().members)
        assertTrue(result.accepted.single().reborrows.all { it.anchorDependencies == setOf(values.anchor) })
    }

    @Test
    fun rejectsWholeComponentWhenOneOwnedIncomingDefinitionHasNoSeed() {
        val values = Values()
        val cfg = diamond(values)

        val result = analyze(cfg, setOf(seed(values.leftCopy, values)))

        assertTrue(result.accepted.isEmpty())
        assertEquals(ArcSemanticARCRejectionReason.IncompletePhiWeb, result.rejected.single().reason)
    }

    @Test
    fun seedOrderDoesNotChangePlan() {
        val values = Values()
        val cfg = diamond(values)
        val first = analyze(cfg, linkedSetOf(seed(values.leftCopy, values), seed(values.rightCopy, values)))
        val second = analyze(cfg, linkedSetOf(seed(values.rightCopy, values), seed(values.leftCopy, values)))

        assertEquals(first, second)
    }

    @Test
    fun rejectsEscapeUnknownConsumeAndNonDestroyConsume() {
        listOf(ArcSSAUseKind.Consume, ArcSSAUseKind.Escape, ArcSSAUseKind.UnknownConsume).forEach { kind ->
            val values = Values(kind.name)
            val cfg = diamond(values, terminal = ArcSSAOperation.Use(values.joined, kind))
            val result = analyze(cfg, setOf(seed(values.leftCopy, values), seed(values.rightCopy, values)))

            assertTrue(kind.name, result.accepted.isEmpty())
            assertTrue(result.rejected.single().reason in setOf(
                ArcSemanticARCRejectionReason.BorrowEscape,
                ArcSemanticARCRejectionReason.MultipleConsumesOnPath,
            ))
        }
    }

    @Test
    fun rejectsLifetimeWhichCrossesDeinitializationSuspensionOrForeignCallBarrier() {
        ArcSemanticARCBarrierKind.values().forEach { kind ->
            val values = Values(kind.name)
            val cfg = diamond(values, includeBorrow = false, terminal = borrow(values.joined))
            val result = analyze(
                cfg,
                setOf(seed(values.leftCopy, values), seed(values.rightCopy, values)),
                barriers = setOf(ArcSemanticARCBarrier(merge, 1, kind)),
            )

            assertTrue(kind.name, result.accepted.isEmpty())
            assertEquals(ArcSemanticARCRejectionReason.BarrierCrossing, result.rejected.single().reason)
        }
    }

    @Test
    fun rejectsBorrowedPhiLifetimeAcrossLiveExceptionalHandler() {
        val values = Values()
        val handler = ArcBlockId("handler")
        val cfg = cfg(
            blocks = listOf(
                block(entry, values.anchorDefinition, values.sourceDefinition),
                block(left, owned(values.leftCopy)),
                block(right, owned(values.rightCopy)),
                block(merge, join(values.joined, left to values.leftCopy, right to values.rightCopy), throwingBorrow(values.joined)),
                block(handler, borrow(values.joined), destroy(values.joined)),
                block(exit, destroy(values.joined)),
            ),
            edges = setOf(
                edge(entry, left), edge(entry, right), edge(left, merge), edge(right, merge),
                edge(merge, exit), edge(merge, handler, ArcSSAEdgeKind.Exceptional),
            ),
        )

        val result = analyze(cfg, setOf(seed(values.leftCopy, values), seed(values.rightCopy, values)))

        assertTrue(result.accepted.isEmpty())
        assertEquals(ArcSemanticARCRejectionReason.BarrierCrossing, result.rejected.single().reason)
    }

    @Test
    fun deadEndExceptionalPathDoesNotReceiveLifetimeFrontier() {
        val values = Values()
        val dead = ArcBlockId("dead")
        val cfg = cfg(
            blocks = listOf(
                block(entry, values.anchorDefinition, values.sourceDefinition),
                block(left, owned(values.leftCopy)),
                block(right, owned(values.rightCopy)),
                block(merge, join(values.joined, left to values.leftCopy, right to values.rightCopy), throwingBorrow(values.joined)),
                block(dead),
                block(exit, destroy(values.joined)),
            ),
            edges = setOf(
                edge(entry, left), edge(entry, right), edge(left, merge), edge(right, merge),
                edge(merge, exit), edge(merge, dead, ArcSSAEdgeKind.Exceptional),
            ),
        )

        val result = analyze(
            cfg,
            setOf(seed(values.leftCopy, values), seed(values.rightCopy, values)),
            deadEndBlocks = setOf(dead),
        )

        assertTrue(result.rejected.joinToString(), result.rejected.isEmpty())
        assertFalse(result.accepted.single().frontier.any {
            it is ArcSemanticLifetimeFrontier.OnEdge && it.edge.to.name == dead.name
        })
    }

    @Test
    fun reportsCriticalLifetimeFrontierAsRequiringEdgeSplit() {
        val values = Values()
        val usePath = ArcBlockId("usePath")
        val deadPath = ArcBlockId("deadPath")
        val other = ArcBlockId("other")
        val finish = ArcBlockId("finish")
        val cfg = cfg(
            blocks = listOf(
                block(entry, values.anchorDefinition, values.sourceDefinition),
                block(left, owned(values.leftCopy)),
                block(right, owned(values.rightCopy)),
                block(merge, join(values.joined, left to values.leftCopy, right to values.rightCopy)),
                block(usePath, borrow(values.joined), destroy(values.joined)),
                block(deadPath), block(other), block(finish),
            ),
            edges = setOf(
                edge(entry, left), edge(entry, right), edge(left, merge), edge(right, merge),
                edge(merge, usePath), edge(merge, deadPath), edge(entry, other), edge(other, deadPath),
                edge(deadPath, finish), edge(usePath, finish),
            ),
        )

        val result = analyze(cfg, setOf(seed(values.leftCopy, values), seed(values.rightCopy, values)))

        assertTrue(result.rejected.joinToString(), result.rejected.isEmpty())
        val frontier = result.accepted.single().frontier.filterIsInstance<ArcSemanticLifetimeFrontier.OnEdge>()
            .singleOrNull { it.edge.from.name == merge.name && it.edge.to.name == deadPath.name }
        assertNotNull(frontier)
        assertTrue(frontier!!.requiresEdgeSplit)
    }

    @Test
    fun duplicateDefinitionAndMismatchedAnchorClaimFailClosed() {
        val duplicateValues = Values("duplicate")
        val duplicateCfg = cfg(
            listOf(block(
                entry,
                duplicateValues.anchorDefinition,
                duplicateValues.sourceDefinition,
                owned(duplicateValues.leftCopy),
                owned(duplicateValues.leftCopy),
            )),
        )
        val duplicate = analyze(duplicateCfg, setOf(seed(duplicateValues.leftCopy, duplicateValues)))
        assertEquals(ArcSemanticARCRejectionReason.MalformedCFG, duplicate.rejected.single().reason)

        val values = Values("anchors")
        val wrongAnchor = ArcSSAValue("wrong.anchor")
        val cfg = diamond(values, entryPrefix = listOf(owned(wrongAnchor)))
        val mismatch = analyze(cfg, setOf(
            seed(values.leftCopy, values).copy(anchorDependencies = setOf(wrongAnchor)),
            seed(values.rightCopy, values),
        ))
        assertTrue(mismatch.accepted.isEmpty())
        assertEquals(ArcSemanticARCRejectionReason.InvalidGuaranteedSeed, mismatch.rejected.first().reason)
    }

    @Test
    fun budgetAndAtomicRewriteVerificationAreExplicit() {
        val values = Values()
        val result = analyze(
            diamond(values),
            setOf(seed(values.leftCopy, values), seed(values.rightCopy, values)),
            budget = ArcSemanticARCBudget(maximumInstructions = 100, maximumUses = 100, maximumRewriteSteps = 2),
        )
        assertTrue(result.accepted.isEmpty())
        assertEquals(ArcSemanticARCRejectionReason.BudgetExhausted, result.rejected.first().reason)

        val accepted = analyze(diamond(values), setOf(seed(values.leftCopy, values), seed(values.rightCopy, values)))
            .accepted.single()
        assertEquals(accepted.rewrites.size + 1, accepted.verificationEpochs)
        assertTrue(accepted.rewrites.first() is ArcSemanticARCRewrite.PrepareJoinedWeb)
        assertEquals(setOf(values.leftCopy, values.rightCopy), accepted.rewrites.drop(1)
            .filterIsInstance<ArcSemanticARCRewrite.EliminateGuaranteedCopy>()
            .mapTo(linkedSetOf()) { it.copy })
    }

    private fun diamond(
        values: Values,
        includeBorrow: Boolean = true,
        terminal: ArcSSAOperation = destroy(values.joined),
        entryPrefix: List<ArcSSAOperation> = emptyList(),
    ): ArcOwnershipSSAInput = cfg(
        blocks = listOf(
            block(entry, *(entryPrefix + listOf(values.anchorDefinition, values.sourceDefinition)).toTypedArray()),
            block(left, owned(values.leftCopy)),
            block(right, owned(values.rightCopy)),
            block(
                merge,
                *buildList {
                    add(join(values.joined, left to values.leftCopy, right to values.rightCopy))
                    if (includeBorrow) add(borrow(values.joined))
                    add(terminal)
                }.toTypedArray(),
            ),
        ),
        edges = setOf(edge(entry, left), edge(entry, right), edge(left, merge), edge(right, merge)),
    )

    private fun analyze(
        cfg: ArcOwnershipSSAInput,
        seeds: Set<ArcGuaranteedCopySeed>,
        deadEndBlocks: Set<ArcBlockId> = emptySet(),
        barriers: Set<ArcSemanticARCBarrier> = emptySet(),
        budget: ArcSemanticARCBudget = ArcSemanticARCBudget(),
    ) = ArcOwnedToGuaranteedPhiWebAnalysis.analyze(ArcSemanticARCInput(
        cfg = cfg,
        guaranteedCopies = seeds,
        deadEndBlocks = deadEndBlocks,
        barriers = barriers,
        budget = budget,
    ))

    private fun cfg(blocks: List<ArcSSABlock>, edges: Set<ArcSSAEdge> = emptySet()) =
        ArcOwnershipSSAInput(entry, blocks.associateByTo(linkedMapOf()) { it.id }, edges)

    private fun block(id: ArcBlockId, vararg operations: ArcSSAOperation) = ArcSSABlock(id, operations.toList())
    private fun edge(from: ArcBlockId, to: ArcBlockId, kind: ArcSSAEdgeKind = ArcSSAEdgeKind.Normal) =
        ArcSSAEdge(from, to, kind)
    private fun owned(value: ArcSSAValue) = ArcSSAOperation.Introduce(value, ArcOwnership.Owned)
    private fun forward(source: ArcSSAValue, result: ArcSSAValue) = ArcSSAOperation.Forward(source, result)
    private fun join(result: ArcSSAValue, vararg incoming: Pair<ArcBlockId, ArcSSAValue>) =
        ArcSSAOperation.Join(result, linkedMapOf(*incoming))
    private fun borrow(value: ArcSSAValue) = ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow)
    private fun throwingBorrow(value: ArcSSAValue) = ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow, mayThrow = true)
    private fun destroy(value: ArcSSAValue) = ArcSSAOperation.DestroyOwned(
        ArcSSASlot("slot.${value.name}"), ArcSSASlotVersion("version.${value.name}"), value,
    )
    private fun seed(copy: ArcSSAValue, values: Values) =
        ArcGuaranteedCopySeed(copy, values.source, setOf(values.anchor))

    private class Values(suffix: String = "") {
        private val tag = suffix.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
        val anchor = ArcSSAValue("anchor$tag")
        val source = ArcSSAValue("source$tag")
        val leftCopy = ArcSSAValue("left.copy$tag")
        val rightCopy = ArcSSAValue("right.copy$tag")
        val joined = ArcSSAValue("joined$tag")
        val anchorDefinition = ArcSSAOperation.Introduce(anchor, ArcOwnership.Owned)
        val sourceDefinition = ArcSSAOperation.Introduce(source, ArcOwnership.Guaranteed, setOf(anchor))
    }
}
