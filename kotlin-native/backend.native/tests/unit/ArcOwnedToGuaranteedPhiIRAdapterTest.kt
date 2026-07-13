/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcOwnedToGuaranteedPhiIRAdapterTest {
    private val entry = ArcBlockId("entry")
    private val left = ArcBlockId("left")
    private val right = ArcBlockId("right")
    private val merge = ArcBlockId("merge")

    @Test
    fun completeMultiDefinitionInventoryProducesExactAtomicActions() {
        val fixture = forwardingFixture()

        val result = ArcOwnedToGuaranteedPhiIRAdapter.adapt(fixture.inventory)

        assertTrue(result.rejections.toString(), result.rejections.isEmpty())
        val selection = result.selection!!
        assertEquals(1, selection.plans.size)
        assertEquals(fixture.inventory.blocks.size, selection.exactBindings.blocks.size)
        assertEquals(fixture.inventory.edges.size, selection.exactBindings.edges.size)
        assertEquals(3, selection.actions.keys.filterIsInstance<
                ArcOwnedToGuaranteedPhiIRActionId.ConvertForwardingDefinition>().size)
        assertEquals(1, selection.actions.keys.filterIsInstance<ArcOwnedToGuaranteedPhiIRActionId.ConvertJoin>().size)
        assertEquals(2, selection.actions.keys.filterIsInstance<ArcOwnedToGuaranteedPhiIRActionId.InstallReborrow>().size)
        assertEquals(2, selection.actions.keys.filterIsInstance<ArcOwnedToGuaranteedPhiIRActionId.EliminateCopy>().size)
        assertEquals(1, selection.actions.keys.filterIsInstance<ArcOwnedToGuaranteedPhiIRActionId.RemoveDestroy>().size)
        assertTrue(selection.actions.keys.any { it is ArcOwnedToGuaranteedPhiIRActionId.EndLifetime })

        ArcOwnedToGuaranteedPhiIRConsumptionLedger(selection, fixture.inventory.functionBinding).also { ledger ->
            selection.actions.values.forEach { ledger.stage(it.id, it.bindingIdentities) }
            ledger.commit()
        }
    }

    @Test
    fun transactionRejectsIdentityDriftDuplicatesIncompletenessAndWrongFunction() {
        val selection = ArcOwnedToGuaranteedPhiIRAdapter.adapt(forwardingFixture().inventory).selection!!
        val first = selection.actions.values.first()
        expectFailure {
            ArcOwnedToGuaranteedPhiIRConsumptionLedger(selection, Token("wrong.function"))
        }
        expectFailure {
            ArcOwnedToGuaranteedPhiIRConsumptionLedger(selection, selection.inventory.functionBinding).also { ledger ->
                ledger.stage(first.id, first.bindingIdentities.mapIndexed { index, binding ->
                    if (index == 0) Token("drift") else binding
                })
            }
        }
        expectFailure {
            ArcOwnedToGuaranteedPhiIRConsumptionLedger(selection, selection.inventory.functionBinding).also { ledger ->
                ledger.stage(first.id, first.bindingIdentities)
                ledger.stage(first.id, first.bindingIdentities)
            }
        }
        expectFailure {
            ArcOwnedToGuaranteedPhiIRConsumptionLedger(selection, selection.inventory.functionBinding).commit()
        }
        ArcOwnedToGuaranteedPhiIRConsumptionLedger(selection, selection.inventory.functionBinding).also { ledger ->
            ledger.abort()
            expectFailure { ledger.stage(first.id, first.bindingIdentities) }
        }
    }

    @Test
    fun incompleteWalkAndDuplicateIRIdentityFailBeforeAnalysis() {
        val fixture = forwardingFixture()
        assertRejected(
            fixture.inventory.copy(completeLoweredIRWalk = false),
            ArcOwnedToGuaranteedPhiIRRejectionReason.IncompleteLoweredIRWalk,
        )
        val firstBlock = fixture.inventory.blocks.first()
        val secondBlock = fixture.inventory.blocks[1]
        assertRejected(
            fixture.inventory.copy(blocks = fixture.inventory.blocks.map {
                if (it.id.name == secondBlock.id.name) it.copy(binding = firstBlock.binding) else it
            }),
            ArcOwnedToGuaranteedPhiIRRejectionReason.DuplicateIRIdentity,
        )
        val firstOperationBinding = fixture.inventory.blocks.first().operations.first().binding
        assertRejected(
            fixture.inventory.copy(blocks = fixture.inventory.blocks.mapIndexed { blockIndex, block ->
                if (blockIndex == 1) block.copy(operations = block.operations.mapIndexed { operationIndex, operation ->
                    if (operationIndex == 0) operation.copy(binding = firstOperationBinding) else operation
                }) else block
            }),
            ArcOwnedToGuaranteedPhiIRRejectionReason.DuplicateIRIdentity,
        )
    }

    @Test
    fun incompleteCfgAndMissingDefinitionFailClosed() {
        val fixture = forwardingFixture()
        assertRejected(
            fixture.inventory.copy(blocks = fixture.inventory.blocks + fixture.inventory.blocks.first()),
            ArcOwnedToGuaranteedPhiIRRejectionReason.DuplicateBlock,
        )
        assertRejected(
            fixture.inventory.copy(edges = fixture.inventory.edges + fixture.inventory.edges.first()),
            ArcOwnedToGuaranteedPhiIRRejectionReason.DuplicateEdge,
        )
        val unknown = ArcSSAValue("undefined")
        val mutated = fixture.cfg.copy(blocks = fixture.cfg.blocks.mapValues { (id, block) ->
            if (id.name == left.name) block.copy(operations = block.operations + ArcSSAOperation.Forward(unknown, ArcSSAValue("bad")))
            else block
        })
        assertRejected(buildFixture(mutated, fixture.seeds).inventory, ArcOwnedToGuaranteedPhiIRRejectionReason.MissingDefinition)
    }

    @Test
    fun seedMustCarryExactCopySourceAndAnchorIdentities() {
        val fixture = forwardingFixture()
        val seed = fixture.inventory.seeds.first()
        listOf(
            seed.copy(copyDefinitionBinding = Token("wrong.copy")),
            seed.copy(guaranteedSourceDefinitionBinding = Token("wrong.source")),
            seed.copy(anchorDefinitionBindings = seed.anchorDefinitionBindings.mapValues { Token("wrong.anchor") }),
        ).forEach { invalid ->
            assertRejected(
                fixture.inventory.copy(seeds = listOf(invalid) + fixture.inventory.seeds.drop(1)),
                ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidSeedIdentity,
            )
        }
    }

    @Test
    fun exactExceptionalOwnerAndDeadEndSealArePreserved() {
        val fixture = exceptionalDeadEndFixture()

        val accepted = ArcOwnedToGuaranteedPhiIRAdapter.adapt(fixture.inventory)

        assertTrue(accepted.rejections.toString(), accepted.rejections.isEmpty())
        val selection = accepted.selection!!
        assertEquals(setOf("dead"), selection.exactBindings.deadEndBlocks.keys.mapTo(linkedSetOf()) { it.name })
        val exceptional = fixture.inventory.edges.single { it.edge.kind === ArcSSAEdgeKind.Exceptional }
        assertNotNull(exceptional.throwingOperation)
        assertTrue(exceptional.throwingOperationBinding ===
                selection.exactBindings.operations.getValue(exceptional.throwingOperation!!))

        assertRejected(
            fixture.inventory.copy(edges = fixture.inventory.edges.map {
                if (it.edge.kind === ArcSSAEdgeKind.Exceptional) it.copy(throwingOperationBinding = Token("wrong.throw")) else it
            }),
            ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidExceptionalEdge,
        )
    }

    @Test
    fun barriersAndExitLifetimeUsesRequireExactBlockAndOperationIdentities() {
        val barrierFixture = preLifetimeBarrierFixture()
        val barrierAccepted = ArcOwnedToGuaranteedPhiIRAdapter.adapt(barrierFixture.inventory)
        assertTrue(barrierAccepted.rejections.toString(), barrierAccepted.rejections.isEmpty())
        assertEquals(1, barrierAccepted.selection!!.exactBindings.barriers.size)
        assertRejected(
            barrierFixture.inventory.copy(barriers = emptyList()),
            ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidBarrier,
        )

        val exitFixture = exitLifetimeFixture()
        val exitAccepted = ArcOwnedToGuaranteedPhiIRAdapter.adapt(exitFixture.inventory)
        assertTrue(exitAccepted.rejections.toString(), exitAccepted.rejections.isEmpty())
        assertTrue(exitAccepted.selection!!.plans.single().frontier.any {
            it is ArcSemanticLifetimeFrontier.BeforeExit && it.block.name == "exit"
        })
        val seal = exitFixture.inventory.exitLifetimeUses.single()
        assertRejected(
            exitFixture.inventory.copy(exitLifetimeUses = listOf(seal.copy(binding = Token("wrong.exit")))),
            ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidBlockSeal,
        )
        assertRejected(
            exitFixture.inventory.copy(deadEndBlocks = listOf(seal), exitLifetimeUses = listOf(seal)),
            ArcOwnedToGuaranteedPhiIRRejectionReason.InvalidBlockSeal,
        )
    }

    @Test
    fun criticalFrontierRequiresOneReservedExactSplitIdentity() {
        val fixture = criticalEdgeFixture()
        val missing = ArcOwnedToGuaranteedPhiIRAdapter.adapt(fixture.inventory)
        assertTrue(missing.selection === null)
        assertTrue(missing.rejections.any { it.reason ===
                ArcOwnedToGuaranteedPhiIRRejectionReason.MissingCriticalEdgeSplit })

        val analysisInput = ArcSemanticARCInput(
            fixture.cfg,
            fixture.seeds,
        )
        val plan = ArcOwnedToGuaranteedPhiWebAnalysis.analyze(analysisInput).accepted.single()
        val edge = plan.frontier.filterIsInstance<ArcSemanticLifetimeFrontier.OnEdge>()
            .single { it.requiresEdgeSplit }.edge
        val split = ArcOwnedToGuaranteedPhiIRCriticalEdgeSplit(
            edge, ArcBlockId("split.merge.dead"), Token("split.binding"),
        )
        val accepted = ArcOwnedToGuaranteedPhiIRAdapter.adapt(
            fixture.inventory.copy(criticalEdgeSplits = listOf(split)),
        )

        assertTrue(accepted.rejections.toString(), accepted.rejections.isEmpty())
        val selection = accepted.selection!!
        assertTrue(selection.actions.keys.any { it is ArcOwnedToGuaranteedPhiIRActionId.SplitCriticalEdge })
        assertTrue(selection.actions.keys.any {
            it is ArcOwnedToGuaranteedPhiIRActionId.EndLifetime &&
                    it.frontier is ArcSemanticLifetimeFrontier.OnEdge
        })
        assertRejected(
            fixture.inventory.copy(criticalEdgeSplits = listOf(split, split)),
            ArcOwnedToGuaranteedPhiIRRejectionReason.MissingCriticalEdgeSplit,
        )
    }

    @Test
    fun inventoryAndSemanticBudgetsAreBothEnforced() {
        val fixture = forwardingFixture()
        assertRejected(
            fixture.inventory.copy(budget = ArcSemanticARCBudget(
                maximumInstructions = 1, maximumUses = 1, maximumRewriteSteps = 1,
            )),
            ArcOwnedToGuaranteedPhiIRRejectionReason.BudgetExhausted,
        )
        assertRejected(
            fixture.inventory.copy(budget = ArcSemanticARCBudget(
                maximumInstructions = 100, maximumUses = 100, maximumRewriteSteps = 2,
            )),
            ArcOwnedToGuaranteedPhiIRRejectionReason.SemanticAnalysisRejected,
        )
    }

    private data class Fixture(
        val cfg: ArcOwnershipSSAInput,
        val seeds: Set<ArcGuaranteedCopySeed>,
        val inventory: ArcOwnedToGuaranteedPhiIRInventory<Token>,
    )

    private class Token(val description: String) {
        override fun toString(): String = description
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

    private fun values(prefix: String): Values = Values(
        ArcSSAValue("$prefix.left.anchor"), ArcSSAValue("$prefix.left.source"), ArcSSAValue("$prefix.left.copy"),
        ArcSSAValue("$prefix.right.anchor"), ArcSSAValue("$prefix.right.source"), ArcSSAValue("$prefix.right.copy"),
        ArcSSAValue("$prefix.joined"),
    )

    private fun forwardingFixture(): Fixture {
        val values = values("forwarding")
        val leftForward = ArcSSAValue("forwarding.left.forward")
        val leftReborrow = ArcSSAValue("forwarding.left.reborrow")
        val rightForward = ArcSSAValue("forwarding.right.forward")
        val cfg = cfg(
            blocks = listOf(
                block(entry,
                    ArcSSAOperation.Introduce(values.leftAnchor, ArcOwnership.Immortal),
                    ArcSSAOperation.Introduce(values.leftSource, ArcOwnership.Guaranteed, setOf(values.leftAnchor)),
                    ArcSSAOperation.Introduce(values.rightAnchor, ArcOwnership.Immortal),
                    ArcSSAOperation.Introduce(values.rightSource, ArcOwnership.Guaranteed, setOf(values.rightAnchor)),
                ),
                block(left,
                    ArcSSAOperation.Introduce(values.leftCopy, ArcOwnership.Owned),
                    ArcSSAOperation.Forward(values.leftCopy, leftForward),
                    ArcSSAOperation.Reborrow(leftForward, leftReborrow, setOf(values.leftAnchor)),
                ),
                block(right,
                    ArcSSAOperation.Introduce(values.rightCopy, ArcOwnership.Owned),
                    ArcSSAOperation.Forward(values.rightCopy, rightForward),
                ),
                block(merge,
                    ArcSSAOperation.Join(values.joined, linkedMapOf(left to leftReborrow, right to rightForward)),
                    ArcSSAOperation.Use(values.joined, ArcSSAUseKind.Borrow),
                    destroy(values.joined),
                ),
            ),
            edges = setOf(edge(entry, left), edge(entry, right), edge(left, merge), edge(right, merge)),
        )
        return buildFixture(cfg, seeds(values))
    }

    private fun exceptionalDeadEndFixture(): Fixture {
        val values = values("exceptional")
        val normal = ArcBlockId("normal")
        val dead = ArcBlockId("dead")
        val cfg = baseDiamond(
            values,
            mergeOperations = listOf(
                ArcSSAOperation.Join(values.joined, linkedMapOf(left to values.leftCopy, right to values.rightCopy)),
                ArcSSAOperation.Use(values.joined, ArcSSAUseKind.Borrow, mayThrow = true),
            ),
            additionalBlocks = listOf(block(normal, destroy(values.joined)), block(dead)),
            additionalEdges = setOf(
                edge(merge, normal), edge(merge, dead, ArcSSAEdgeKind.Exceptional),
            ),
        )
        return buildFixture(cfg, seeds(values), deadEnds = setOf(dead))
    }

    private fun preLifetimeBarrierFixture(): Fixture {
        val values = values("barrier")
        val cfg = cfg(
            blocks = listOf(
                block(entry,
                    ArcSSAOperation.Introduce(values.leftAnchor, ArcOwnership.Immortal),
                    ArcSSAOperation.Introduce(values.leftSource, ArcOwnership.Guaranteed, setOf(values.leftAnchor)),
                    ArcSSAOperation.Introduce(values.rightAnchor, ArcOwnership.Immortal),
                    ArcSSAOperation.Introduce(values.rightSource, ArcOwnership.Guaranteed, setOf(values.rightAnchor)),
                    ArcSSAOperation.DeinitBarrier(),
                ),
                block(left, ArcSSAOperation.Introduce(values.leftCopy, ArcOwnership.Owned)),
                block(right, ArcSSAOperation.Introduce(values.rightCopy, ArcOwnership.Owned)),
                block(merge,
                    ArcSSAOperation.Join(values.joined, linkedMapOf(left to values.leftCopy, right to values.rightCopy)),
                    ArcSSAOperation.Use(values.joined, ArcSSAUseKind.Borrow), destroy(values.joined),
                ),
            ),
            edges = setOf(edge(entry, left), edge(entry, right), edge(left, merge), edge(right, merge)),
        )
        return buildFixture(cfg, seeds(values))
    }

    private fun exitLifetimeFixture(): Fixture {
        val values = values("exit")
        val exit = ArcBlockId("exit")
        val cfg = baseDiamond(
            values,
            mergeOperations = listOf(ArcSSAOperation.Join(
                values.joined, linkedMapOf(left to values.leftCopy, right to values.rightCopy),
            )),
            additionalBlocks = listOf(block(exit, ArcSSAOperation.Use(values.joined, ArcSSAUseKind.Borrow))),
            additionalEdges = setOf(edge(merge, exit)),
        )
        return buildFixture(cfg, seeds(values), exits = setOf(exit))
    }

    private fun criticalEdgeFixture(): Fixture {
        val values = values("critical")
        val live = ArcBlockId("live")
        val dead = ArcBlockId("dead")
        val other = ArcBlockId("other")
        val cfg = baseDiamond(
            values,
            mergeOperations = listOf(
                ArcSSAOperation.Join(values.joined, linkedMapOf(left to values.leftCopy, right to values.rightCopy)),
            ),
            additionalBlocks = listOf(
                block(live, ArcSSAOperation.Use(values.joined, ArcSSAUseKind.Borrow), destroy(values.joined)),
                block(dead), block(other),
            ),
            additionalEdges = setOf(
                edge(entry, other), edge(merge, live), edge(merge, dead), edge(other, dead),
            ),
        )
        return buildFixture(cfg, seeds(values))
    }

    private fun baseDiamond(
        values: Values,
        mergeOperations: List<ArcSSAOperation>,
        additionalBlocks: List<ArcSSABlock> = emptyList(),
        additionalEdges: Set<ArcSSAEdge> = emptySet(),
    ): ArcOwnershipSSAInput = cfg(
        blocks = listOf(
            block(entry,
                ArcSSAOperation.Introduce(values.leftAnchor, ArcOwnership.Immortal),
                ArcSSAOperation.Introduce(values.leftSource, ArcOwnership.Guaranteed, setOf(values.leftAnchor)),
                ArcSSAOperation.Introduce(values.rightAnchor, ArcOwnership.Immortal),
                ArcSSAOperation.Introduce(values.rightSource, ArcOwnership.Guaranteed, setOf(values.rightAnchor)),
            ),
            block(left, ArcSSAOperation.Introduce(values.leftCopy, ArcOwnership.Owned)),
            block(right, ArcSSAOperation.Introduce(values.rightCopy, ArcOwnership.Owned)),
            ArcSSABlock(merge, mergeOperations),
        ) + additionalBlocks,
        edges = setOf(edge(entry, left), edge(entry, right), edge(left, merge), edge(right, merge)) + additionalEdges,
    )

    private fun seeds(values: Values) = setOf(
        ArcGuaranteedCopySeed(values.leftCopy, values.leftSource, setOf(values.leftAnchor)),
        ArcGuaranteedCopySeed(values.rightCopy, values.rightSource, setOf(values.rightAnchor)),
    )

    private fun buildFixture(
        cfg: ArcOwnershipSSAInput,
        seeds: Set<ArcGuaranteedCopySeed>,
        deadEnds: Set<ArcBlockId> = emptySet(),
        exits: Set<ArcBlockId> = emptySet(),
    ): Fixture {
        val blocks = cfg.blocks.values.map { block ->
            ArcOwnedToGuaranteedPhiIRBlock(
                block.id,
                Token("block.${block.id.name}"),
                block.operations.mapIndexed { index, operation ->
                    ArcOwnedToGuaranteedPhiIROperation(operation, Token("operation.${block.id.name}.$index"))
                },
            )
        }
        val blocksById = blocks.associateBy { it.id }
        val definitions = linkedMapOf<ArcSSAValue, Token>()
        blocks.forEach { block -> block.operations.forEach { operation ->
            resultOf(operation.operation)?.let { definitions[it] = operation.binding }
        } }
        val edges = cfg.edges.map { edge ->
            val throwingPosition = if (edge.kind === ArcSSAEdgeKind.Exceptional) {
                blocksById.getValue(edge.from).operations.withIndex().single { (_, operation) ->
                    val use = operation.operation as? ArcSSAOperation.Use
                    use?.mayThrow == true
                }.let { ArcSemanticEmissionOperationId(edge.from, it.index) }
            } else null
            ArcOwnedToGuaranteedPhiIREdge(
                edge,
                Token("edge.${edge.from.name}.${edge.to.name}.${edge.kind.name}"),
                throwingPosition,
                throwingPosition?.let { blocksById.getValue(it.block).operations[it.operationIndex].binding },
            )
        }
        val barriers = blocks.flatMap { block -> block.operations.mapIndexedNotNull { index, operation ->
            if (operation.operation is ArcSSAOperation.DeinitBarrier) ArcOwnedToGuaranteedPhiIRBarrier(
                ArcSemanticARCBarrier(block.id, index, ArcSemanticARCBarrierKind.Deinitialization),
                operation.binding,
            ) else null
        } }
        val inventorySeeds = seeds.map { seed -> ArcOwnedToGuaranteedPhiIRSeed(
            seed,
            definitions.getValue(seed.copy),
            definitions.getValue(seed.guaranteedSource),
            seed.anchorDependencies.associateWith { definitions.getValue(it) },
        ) }
        val inventory = ArcOwnedToGuaranteedPhiIRInventory(
            functionBinding = Token("function"),
            mode = ArcOwnedToGuaranteedPhiIRCompilationMode(true, true, true, true),
            entry = cfg.entry,
            blocks = blocks,
            edges = edges,
            seeds = inventorySeeds,
            barriers = barriers,
            deadEndBlocks = deadEnds.map { ArcOwnedToGuaranteedPhiIRBlockSeal(it, blocksById.getValue(it).binding) },
            exitLifetimeUses = exits.map { ArcOwnedToGuaranteedPhiIRBlockSeal(it, blocksById.getValue(it).binding) },
            completeLoweredIRWalk = true,
        )
        return Fixture(cfg, seeds, inventory)
    }

    private fun cfg(blocks: List<ArcSSABlock>, edges: Set<ArcSSAEdge>) =
        ArcOwnershipSSAInput(entry, blocks.associateByTo(linkedMapOf()) { it.id }, edges)
    private fun block(id: ArcBlockId, vararg operations: ArcSSAOperation) = ArcSSABlock(id, operations.toList())
    private fun edge(from: ArcBlockId, to: ArcBlockId, kind: ArcSSAEdgeKind = ArcSSAEdgeKind.Normal) =
        ArcSSAEdge(from, to, kind)
    private fun destroy(value: ArcSSAValue) = ArcSSAOperation.DestroyOwned(
        ArcSSASlot("slot.${value.name}"), ArcSSASlotVersion("version.${value.name}"), value,
    )

    private fun resultOf(operation: ArcSSAOperation): ArcSSAValue? = when (operation) {
        is ArcSSAOperation.Introduce -> operation.result
        is ArcSSAOperation.Forward -> operation.result
        is ArcSSAOperation.Reborrow -> operation.result
        is ArcSSAOperation.Join -> operation.result
        is ArcSSAOperation.Borrow -> operation.result
        else -> null
    }

    private fun assertRejected(
        inventory: ArcOwnedToGuaranteedPhiIRInventory<Token>,
        reason: ArcOwnedToGuaranteedPhiIRRejectionReason,
    ) {
        val result = ArcOwnedToGuaranteedPhiIRAdapter.adapt(inventory)
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
        assertTrue("expected fail-closed transaction rejection", failed)
    }
}
