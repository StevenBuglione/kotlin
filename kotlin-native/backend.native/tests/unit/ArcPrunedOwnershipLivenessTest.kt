/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcPrunedOwnershipLivenessTest {
    private val entry = ArcBlockId("entry")
    private val middle = ArcBlockId("middle")
    private val exit = ArcBlockId("exit")
    private val exceptional = ArcBlockId("exceptional")
    private val value = ArcSSAValue("value")

    @Test
    fun placesLifetimeEndAfterLastLocalUse() {
        val cfg = cfg(block(entry, introduce(value), use(value)))
        val proof = analyze(
            cfg,
            definitions = setOf(def(value, entry, 0)),
            uses = setOf(opUse(value, entry, 1)),
        ).proof!!

        assertEquals(ArcPrunedBlockLiveness.LiveWithin, proof.blockLiveness.getValue(entry))
        assertEquals(listOf(false, true), proof.liveBeforeOperations.getValue(entry))
        assertEquals(listOf(true, false), proof.liveAfterOperations.getValue(entry))
        assertEquals(setOf(ArcPrunedOwnershipBoundaryPoint.AfterOperation(entry, 1)), proof.boundary)
    }

    @Test
    fun placesLifetimeEndAfterDeadDefinition() {
        val definition = def(value, entry, 0)
        val proof = analyze(
            cfg(block(entry, introduce(value))),
            definitions = setOf(definition),
        ).proof!!

        assertEquals(
            setOf(ArcPrunedOwnershipBoundaryPoint.AfterDeadDefinition(definition)),
            proof.boundary,
        )
    }

    @Test
    fun propagatesPrunedLivenessAcrossBlocksOnlyAsFarAsTheDefinition() {
        val cfg = cfg(
            block(entry, introduce(value)),
            block(middle),
            block(exit, use(value)),
            edges = setOf(edge(entry, middle), edge(middle, exit)),
        )
        val proof = analyze(
            cfg,
            definitions = setOf(def(value, entry, 0)),
            uses = setOf(opUse(value, exit, 0)),
        ).proof!!

        assertEquals(ArcPrunedBlockLiveness.LiveOut, proof.blockLiveness.getValue(entry))
        assertEquals(ArcPrunedBlockLiveness.LiveOut, proof.blockLiveness.getValue(middle))
        assertEquals(ArcPrunedBlockLiveness.LiveWithin, proof.blockLiveness.getValue(exit))
        assertFalse(proof.liveBeforeOperations.getValue(entry).single())
        assertEquals(setOf(ArcPrunedOwnershipBoundaryPoint.AfterOperation(exit, 0)), proof.boundary)
    }

    @Test
    fun handlesDefinitionUseHolesAndDeadForwardedDefinitions() {
        val forwarded = ArcSSAValue("forwarded")
        val forwardedDefinition = def(forwarded, entry, 1)
        val cfg = cfg(block(entry, introduce(value), ArcSSAOperation.Forward(value, forwarded)))
        val proof = analyze(
            cfg,
            definitions = setOf(def(value, entry, 0), forwardedDefinition),
            uses = setOf(opUse(value, entry, 1)),
        ).proof!!

        assertTrue(ArcPrunedOwnershipBoundaryPoint.AfterDeadDefinition(forwardedDefinition) in proof.boundary)
        assertTrue(ArcPrunedOwnershipBoundaryPoint.AfterOperation(entry, 1) in proof.boundary)
    }

    @Test
    fun computesLoopFrontierWithoutUnrollingTheLoop() {
        val loop = ArcBlockId("loop")
        val cfg = cfg(
            block(entry, introduce(value)),
            block(loop, use(value)),
            block(exit),
            edges = setOf(edge(entry, loop), edge(loop, loop), edge(loop, exit)),
        )
        val proof = analyze(
            cfg,
            definitions = setOf(def(value, entry, 0)),
            uses = setOf(opUse(value, loop, 0)),
        ).proof!!

        assertEquals(ArcPrunedBlockLiveness.LiveOut, proof.blockLiveness.getValue(loop))
        assertTrue(proof.dataflowSteps < 100)
        assertEquals(
            setOf(ArcPrunedOwnershipBoundaryPoint.OnEdge(edge(loop, exit), false, false)),
            proof.boundary,
        )
    }

    @Test
    fun marksCriticalTerminatorFrontiersForEdgeSplitting() {
        val branch = ArcBlockId("branch")
        val other = ArcBlockId("other")
        val merge = ArcBlockId("merge")
        val sideExit = ArcBlockId("sideExit")
        val cfg = cfg(
            block(entry, introduce(value)),
            block(branch, use(value)),
            block(other),
            block(merge),
            block(sideExit),
            edges = setOf(
                edge(entry, branch), edge(entry, other), edge(branch, merge), edge(branch, sideExit), edge(other, merge),
            ),
        )
        val proof = analyze(
            cfg,
            definitions = setOf(def(value, entry, 0)),
            uses = setOf(opUse(value, branch, 0, terminator = true)),
        ).proof!!

        assertTrue(ArcPrunedOwnershipBoundaryPoint.OnEdge(edge(branch, merge), true, false) in proof.boundary)
        assertTrue(ArcPrunedOwnershipBoundaryPoint.OnEdge(edge(branch, sideExit), false, false) in proof.boundary)
    }

    @Test
    fun reusesExistingLifetimeEndAndAppliesSwiftMeetRules() {
        val cfg = cfg(block(entry, introduce(value), use(value)))
        val local = analyze(
            cfg,
            definitions = setOf(def(value, entry, 0)),
            uses = setOf(
                opUse(value, entry, 1, ArcPrunedOwnershipUseLifetime.LifetimeEnding),
                opUse(value, entry, 1, ArcPrunedOwnershipUseLifetime.NonLifetimeEnding),
            ),
        ).proof!!
        assertEquals(setOf(ArcPrunedOwnershipBoundaryPoint.AfterOperation(entry, 1)), local.boundary)

        val terminator = analyze(
            cfg,
            definitions = setOf(def(value, entry, 0)),
            uses = setOf(
                opUse(value, entry, 1, ArcPrunedOwnershipUseLifetime.LifetimeEnding, terminator = true),
                opUse(value, entry, 1, ArcPrunedOwnershipUseLifetime.NonLifetimeEnding, terminator = true),
            ),
        ).proof!!
        val existing = terminator.boundary.single() as ArcPrunedOwnershipBoundaryPoint.ExistingLifetimeEnd
        assertEquals(ArcPrunedOwnershipUseLifetime.LifetimeEnding, existing.use.lifetime)
    }

    @Test
    fun carriesPhiEdgeUsesIntoTheJoinedDefinition() {
        val joined = ArcSSAValue("joined")
        val merge = ArcBlockId("merge")
        val incomingEdge = edge(entry, merge)
        val cfg = cfg(
            block(entry, introduce(value)),
            block(merge, ArcSSAOperation.Join(joined, mapOf(entry to value)), use(joined)),
            edges = setOf(incomingEdge),
        )
        val proof = analyze(
            cfg,
            definitions = setOf(def(value, entry, 0), def(joined, merge, 0)),
            uses = setOf(
                ArcPrunedOwnershipUse.Edge(
                    value, incomingEdge, ArcPrunedOwnershipUseLifetime.NonLifetimeEnding,
                ),
                opUse(joined, merge, 1),
            ),
        ).proof!!

        assertTrue(proof.liveOnEdges.getValue(incomingEdge))
        assertEquals(setOf(ArcPrunedOwnershipBoundaryPoint.AfterOperation(merge, 1)), proof.boundary)
    }

    @Test
    fun requiresExceptionalCleanupForAThrowingLastUse() {
        val throwing = ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow, mayThrow = true)
        val exceptionalEdge = ArcSSAEdge(entry, exceptional, ArcSSAEdgeKind.Exceptional)
        val cfg = cfg(
            block(entry, introduce(value), throwing),
            block(exit),
            block(exceptional),
            edges = setOf(edge(entry, exit), exceptionalEdge),
        )
        val proof = analyze(
            cfg,
            definitions = setOf(def(value, entry, 0)),
            uses = setOf(opUse(value, entry, 1)),
        ).proof!!

        assertTrue(ArcPrunedOwnershipBoundaryPoint.AfterOperation(entry, 1) in proof.boundary)
        assertTrue(ArcPrunedOwnershipBoundaryPoint.OnEdge(exceptionalEdge, false, true) in proof.boundary)

        val endingProof = analyze(
            cfg,
            definitions = setOf(def(value, entry, 0)),
            uses = setOf(opUse(value, entry, 1, ArcPrunedOwnershipUseLifetime.LifetimeEnding)),
        ).proof!!
        assertTrue(endingProof.boundary.any { it is ArcPrunedOwnershipBoundaryPoint.ExistingLifetimeEnd })
        assertTrue(ArcPrunedOwnershipBoundaryPoint.OnEdge(exceptionalEdge, false, true) in endingProof.boundary)
    }

    @Test
    fun rejectsLifetimeThatCrossesAnOwnershipBarrier() {
        val cfg = cfg(block(entry, introduce(value), use(value)))
        val result = analyze(
            cfg,
            definitions = setOf(def(value, entry, 0)),
            uses = setOf(opUse(value, entry, 1)),
            barriers = setOf(ArcSemanticARCBarrier(entry, 1, ArcSemanticARCBarrierKind.Suspension)),
        )

        assertNull(result.proof)
        assertEquals(ArcPrunedOwnershipLivenessRejectionReason.BarrierCrossing, result.rejection!!.reason)

        val exitUse = ArcPrunedOwnershipUse.Exit(
            value, entry, ArcPrunedOwnershipUseLifetime.LifetimeEnding,
        )
        val exitBarrier = analyze(
            cfg(block(entry, introduce(value))),
            definitions = setOf(def(value, entry, 0)),
            uses = setOf(exitUse),
            barriers = setOf(ArcSemanticARCBarrier(entry, 1, ArcSemanticARCBarrierKind.ForeignCall)),
        )
        assertEquals(ArcPrunedOwnershipLivenessRejectionReason.BarrierCrossing, exitBarrier.rejection!!.reason)
    }

    @Test
    fun suppressesLifetimeEndsOnDeadEndOnlyPaths() {
        val dead = ArcBlockId("dead")
        val cfg = cfg(
            block(entry, introduce(value)),
            block(dead, use(value)),
            edges = setOf(edge(entry, dead)),
        )
        val result = analyze(
            cfg,
            definitions = setOf(def(value, entry, 0)),
            uses = setOf(opUse(value, dead, 0)),
            deadEnds = setOf(dead),
        )

        assertNotNull(result.proof)
        assertTrue(result.proof!!.boundary.isEmpty())
    }

    @Test
    fun modelsExitPayloadAsAnExistingLifetimeEnd() {
        val cfg = cfg(block(entry, introduce(value)))
        val exitUse = ArcPrunedOwnershipUse.Exit(
            value, entry, ArcPrunedOwnershipUseLifetime.LifetimeEnding,
        )
        val proof = analyze(
            cfg,
            definitions = setOf(def(value, entry, 0)),
            uses = setOf(exitUse),
        ).proof!!

        assertEquals(setOf(ArcPrunedOwnershipBoundaryPoint.ExistingLifetimeEnd(exitUse)), proof.boundary)
    }

    @Test
    fun failsClosedForIncompleteMalformedAndOverBudgetInventories() {
        val cfg = cfg(block(entry, introduce(value), use(value)))
        val incomplete = ArcPrunedOwnershipLivenessAnalysis.analyze(
            ArcPrunedOwnershipLivenessInput(
                cfg, setOf(def(value, entry, 0)), setOf(opUse(value, entry, 1)), completeInventory = false,
            ),
        )
        assertEquals(ArcPrunedOwnershipLivenessRejectionReason.IncompleteInventory, incomplete.rejection!!.reason)

        val omittedActualUse = analyze(cfg, definitions = setOf(def(value, entry, 0)))
        assertEquals(
            ArcPrunedOwnershipLivenessRejectionReason.IncompleteInventory,
            omittedActualUse.rejection!!.reason,
        )

        val invalidUse = analyze(
            cfg,
            definitions = setOf(def(value, entry, 0)),
            uses = setOf(opUse(value, entry, 9)),
        )
        assertEquals(ArcPrunedOwnershipLivenessRejectionReason.InvalidUse, invalidUse.rejection!!.reason)

        val overBudget = ArcPrunedOwnershipLivenessAnalysis.analyze(
            ArcPrunedOwnershipLivenessInput(
                cfg,
                setOf(def(value, entry, 0)),
                setOf(opUse(value, entry, 1)),
                budget = ArcPrunedOwnershipLivenessBudget(maximumUses = 1, maximumDataflowSteps = 1),
                completeInventory = true,
            ),
        )
        assertEquals(ArcPrunedOwnershipLivenessRejectionReason.BudgetExhausted, overBudget.rejection!!.reason)
    }

    @Test
    fun rejectsEscapeAndUnknownConsumeDestroyFrontiers() {
        val escapingCFG = cfg(
            block(entry, introduce(value), ArcSSAOperation.Use(value, ArcSSAUseKind.Escape)),
        )
        val result = analyze(
            escapingCFG,
            definitions = setOf(def(value, entry, 0)),
            uses = setOf(opUse(value, entry, 1, ArcPrunedOwnershipUseLifetime.LifetimeEnding)),
        )

        assertEquals(ArcPrunedOwnershipLivenessRejectionReason.InvalidUse, result.rejection!!.reason)

        val consumingCFG = cfg(
            block(entry, introduce(value), ArcSSAOperation.Use(value, ArcSSAUseKind.Consume)),
        )
        val misclassified = analyze(
            consumingCFG,
            definitions = setOf(def(value, entry, 0)),
            uses = setOf(opUse(value, entry, 1)),
        )
        assertEquals(ArcPrunedOwnershipLivenessRejectionReason.InvalidUse, misclassified.rejection!!.reason)
    }

    private fun analyze(
        cfg: ArcOwnershipSSAInput,
        definitions: Set<ArcPrunedOwnershipDefinition>,
        uses: Set<ArcPrunedOwnershipUse> = emptySet(),
        deadEnds: Set<ArcBlockId> = emptySet(),
        barriers: Set<ArcSemanticARCBarrier> = emptySet(),
    ): ArcPrunedOwnershipLivenessResult = ArcPrunedOwnershipLivenessAnalysis.analyze(
        ArcPrunedOwnershipLivenessInput(
            cfg, definitions, uses, deadEnds, barriers, completeInventory = true,
        ),
    )

    private fun cfg(
        vararg blocks: ArcSSABlock,
        edges: Set<ArcSSAEdge> = emptySet(),
    ) = ArcOwnershipSSAInput(blocks.first().id, blocks.associateBy { it.id }, edges)

    private fun block(id: ArcBlockId, vararg operations: ArcSSAOperation) = ArcSSABlock(id, operations.toList())
    private fun edge(from: ArcBlockId, to: ArcBlockId) = ArcSSAEdge(from, to)
    private fun introduce(value: ArcSSAValue) = ArcSSAOperation.Introduce(value, ArcOwnership.Owned)
    private fun use(value: ArcSSAValue) = ArcSSAOperation.Use(value, ArcSSAUseKind.Borrow)
    private fun def(value: ArcSSAValue, block: ArcBlockId, index: Int) =
        ArcPrunedOwnershipDefinition(value, block, index)

    private fun opUse(
        value: ArcSSAValue,
        block: ArcBlockId,
        index: Int,
        lifetime: ArcPrunedOwnershipUseLifetime = ArcPrunedOwnershipUseLifetime.NonLifetimeEnding,
        terminator: Boolean = false,
    ) = ArcPrunedOwnershipUse.Operation(value, block, index, lifetime, terminator)
}
