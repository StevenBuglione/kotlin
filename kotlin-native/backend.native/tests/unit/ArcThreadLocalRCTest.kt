/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.llvm.Lifetime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcThreadLocalRCTest {
    private val entry = ArcBlockId("entry")
    private val loop = ArcBlockId("loop")
    private val exit = ArcBlockId("exit")

    @Test
    fun fullyLocalReducibleObjectGraphProducesOneAtomicPlan() {
        val owner = ArcSSAValue("owner")
        val child = ArcSSAValue("child")
        val cfg = ArcOwnershipSSAInput(
            entry,
            linkedMapOf(
                entry to ArcSSABlock(entry, listOf(
                    ArcSSAOperation.Introduce(owner, ArcOwnership.Owned),
                    ArcSSAOperation.Introduce(child, ArcOwnership.Owned),
                    ArcSSAOperation.Use(owner, ArcSSAUseKind.Borrow),
                )),
                loop to ArcSSABlock(loop, listOf(ArcSSAOperation.Use(child, ArcSSAUseKind.Borrow))),
                exit to ArcSSABlock(exit, listOf(
                    ArcSSAOperation.DestroyOwned(ArcSSASlot("owner"), ArcSSASlotVersion("owner.1"), owner),
                    ArcSSAOperation.DestroyOwned(ArcSSASlot("child"), ArcSSASlotVersion("child.1"), child),
                )),
            ),
            setOf(
                ArcSSAEdge(entry, loop),
                ArcSSAEdge(loop, loop),
                ArcSSAEdge(loop, exit),
            ),
        )
        val input = ArcThreadLocalRCInput(
            ArcRCIdentityInput(cfg, emptyList()),
            rootFacts = mapOf(owner to localRoot(owner), child to localRoot(child)),
            effects = listOf(
                ArcThreadLocalRCEffect(
                    ArcRCPosition(entry, 1), ArcThreadLocalRCEffectKind.StrongField, owner, child,
                ),
                ArcThreadLocalRCEffect(
                    ArcRCPosition(entry, 2), ArcThreadLocalRCEffectKind.StrongCapture, child, owner,
                ),
            ),
            ownershipOperations = listOf(
                release(exit, 0, owner),
                release(exit, 1, child),
            ),
            exceptionCleanups = emptyList(),
            coverage = completeCoverage(setOf(owner, child)),
        )

        val result = ArcThreadLocalRCAnalysis.analyze(input)

        assertTrue(result.rejected.toString(), result.rejected.isEmpty())
        val plan = result.accepted.single()
        assertEquals(setOf(owner, child), plan.provenanceRoots)
        assertEquals(setOf(owner, child), plan.values)
        assertEquals(ArcStrictContainerState.Local, plan.strictComparison)
        assertEquals(2, plan.ownershipOperations.size)
    }

    @Test
    fun suspensionWorkerForeignWeakAndUnknownCallsAllFailClosed() {
        val blockers = listOf(
            ArcThreadLocalRCEffectKind.Suspension,
            ArcThreadLocalRCEffectKind.WorkerTransfer,
            ArcThreadLocalRCEffectKind.ForeignCall,
            ArcThreadLocalRCEffectKind.Weak,
            ArcThreadLocalRCEffectKind.UnknownCall,
        )

        blockers.forEach { blocker ->
            val result = ArcThreadLocalRCAnalysis.analyze(linearInput(blocker))
            assertTrue("$blocker unexpectedly accepted", result.accepted.isEmpty())
            assertTrue(
                "$blocker did not produce a forbidden-effect rejection: ${result.rejected}",
                result.rejected.any {
                    it.reason === ArcThreadLocalRCRejectionReason.ForbiddenEffect && blocker.name in it.detail
                },
            )
        }
    }

    @Test
    fun legacyLocalLifetimeWithoutClosedCoverageTokenIsRejected() {
        val input = linearInput(ArcThreadLocalRCEffectKind.StrongField)
        val incomplete = input.copy(
            // Lifetime.LOCAL is analogous to the legacy CONTAINER_TAG_LOCAL fast path, but it is
            // not by itself a whole-lifetime no-publication proof.
            effects = emptyList(),
            coverage = input.coverage.copy(allEscapesAndCallsClassified = false),
        )

        val result = ArcThreadLocalRCAnalysis.analyze(incomplete)

        assertTrue(result.accepted.isEmpty())
        assertTrue(result.rejected.any { it.reason === ArcThreadLocalRCRejectionReason.IncompleteCoverage })
    }

    @Test
    fun phiMixingLocalAllocationWithParameterOriginIsRejectedAsOneComponent() {
        val left = ArcBlockId("left")
        val right = ArcBlockId("right")
        val merge = ArcBlockId("merge")
        val local = ArcSSAValue("local")
        val parameter = ArcSSAValue("parameter")
        val joined = ArcSSAValue("joined")
        val cfg = ArcOwnershipSSAInput(
            entry,
            linkedMapOf(
                entry to ArcSSABlock(entry, emptyList()),
                left to ArcSSABlock(left, listOf(ArcSSAOperation.Introduce(local, ArcOwnership.Owned))),
                right to ArcSSABlock(right, listOf(ArcSSAOperation.Introduce(parameter, ArcOwnership.Guaranteed))),
                merge to ArcSSABlock(merge, listOf(
                    ArcSSAOperation.Join(joined, linkedMapOf(left to local, right to parameter)),
                    ArcSSAOperation.DestroyOwned(ArcSSASlot("joined"), ArcSSASlotVersion("joined.1"), joined),
                )),
            ),
            setOf(
                ArcSSAEdge(entry, left), ArcSSAEdge(entry, right),
                ArcSSAEdge(left, merge), ArcSSAEdge(right, merge),
            ),
        )
        val input = ArcThreadLocalRCInput(
            ArcRCIdentityInput(cfg, emptyList()),
            rootFacts = mapOf(
                local to localRoot(local),
                parameter to ArcThreadLocalRootFact(
                    parameter, Lifetime.ARGUMENT, ArcThreadLocalRootOrigin.Parameter, isHeapAllocation = true,
                ),
            ),
            effects = listOf(
                alias(merge, 0, local, joined),
                alias(merge, 0, parameter, joined),
            ),
            ownershipOperations = listOf(release(merge, 1, joined)),
            exceptionCleanups = emptyList(),
            coverage = completeCoverage(setOf(local, parameter, joined)),
        )

        val result = ArcThreadLocalRCAnalysis.analyze(input)

        assertTrue(result.accepted.isEmpty())
        assertEquals(1, result.rejected.map { it.provenanceRoots }.distinct().size)
        assertTrue(result.rejected.any { it.reason === ArcThreadLocalRCRejectionReason.NonLocalRoot })
    }

    @Test
    fun forwardingAliasThatEscapesToGlobalIsRejected() {
        val root = ArcSSAValue("root")
        val alias = ArcSSAValue("alias")
        val cfg = ArcOwnershipSSAInput(
            entry,
            mapOf(entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.Introduce(root, ArcOwnership.Owned),
                ArcSSAOperation.Forward(root, alias),
                ArcSSAOperation.Use(alias, ArcSSAUseKind.Escape),
                ArcSSAOperation.DestroyOwned(ArcSSASlot("root"), ArcSSASlotVersion("root.1"), root),
            ))),
            emptySet(),
        )
        val input = ArcThreadLocalRCInput(
            ArcRCIdentityInput(cfg, emptyList()),
            rootFacts = mapOf(root to localRoot(root)),
            effects = listOf(
                alias(entry, 1, root, alias),
                ArcThreadLocalRCEffect(ArcRCPosition(entry, 2), ArcThreadLocalRCEffectKind.GlobalStore, alias),
            ),
            ownershipOperations = listOf(release(entry, 3, root)),
            exceptionCleanups = emptyList(),
            coverage = completeCoverage(setOf(root, alias)),
        )

        val result = ArcThreadLocalRCAnalysis.analyze(input)

        assertTrue(result.accepted.isEmpty())
        assertTrue(result.rejected.any { it.reason === ArcThreadLocalRCRejectionReason.ForbiddenEffect })
        assertTrue(result.rejected.any { it.reason === ArcThreadLocalRCRejectionReason.OwnershipIdentityIssue })
    }

    @Test
    fun exceptionalLifetimeRequiresAnExactFamilyReleaseOnEveryUnwindEdge() {
        val normal = ArcBlockId("normal")
        val handler = ArcBlockId("handler")
        val root = ArcSSAValue("root")
        val exceptional = ArcSSAEdge(entry, handler, ArcSSAEdgeKind.Exceptional)
        val cfg = ArcOwnershipSSAInput(
            entry,
            linkedMapOf(
                entry to ArcSSABlock(entry, listOf(
                    ArcSSAOperation.Introduce(root, ArcOwnership.Owned),
                    ArcSSAOperation.Use(root, ArcSSAUseKind.Borrow, mayThrow = true),
                )),
                normal to ArcSSABlock(normal, listOf(
                    ArcSSAOperation.DestroyOwned(ArcSSASlot("normal"), ArcSSASlotVersion("normal.1"), root),
                )),
                handler to ArcSSABlock(handler, listOf(
                    ArcSSAOperation.DestroyOwned(ArcSSASlot("handler"), ArcSSASlotVersion("handler.1"), root),
                )),
            ),
            setOf(ArcSSAEdge(entry, normal), exceptional),
        )
        val effects = listOf(
            ArcThreadLocalRCEffect(
                ArcRCPosition(entry, 1), ArcThreadLocalRCEffectKind.ThrowingLocalCall, root,
            )
        )
        val operations = listOf(release(normal, 0, root), release(handler, 0, root))
        val base = ArcThreadLocalRCInput(
            ArcRCIdentityInput(cfg, emptyList()),
            rootFacts = mapOf(root to localRoot(root)),
            effects = effects,
            ownershipOperations = operations,
            exceptionCleanups = emptyList(),
            coverage = completeCoverage(setOf(root)),
        )

        val missing = ArcThreadLocalRCAnalysis.analyze(base)
        val complete = ArcThreadLocalRCAnalysis.analyze(
            base.copy(
                exceptionCleanups = listOf(
                    ArcThreadLocalExceptionCleanup(
                        ArcRCPosition(entry, 1), exceptional, ArcRCPosition(handler, 0), releaseOrdinal = 0,
                    )
                )
            )
        )
        val sharedHandler = ArcThreadLocalRCAnalysis.analyze(
            base.copy(
                identityInput = ArcRCIdentityInput(
                    cfg.copy(edges = cfg.edges + ArcSSAEdge(normal, handler)),
                    emptyList(),
                ),
                exceptionCleanups = listOf(
                    ArcThreadLocalExceptionCleanup(
                        ArcRCPosition(entry, 1), exceptional, ArcRCPosition(handler, 0), releaseOrdinal = 0,
                    )
                ),
            )
        )

        assertTrue(missing.accepted.isEmpty())
        assertTrue(missing.rejected.any { it.reason === ArcThreadLocalRCRejectionReason.MissingExceptionalCleanup })
        assertTrue(complete.rejected.toString(), complete.rejected.isEmpty())
        assertEquals(1, complete.accepted.single().exceptionCleanups.size)
        assertTrue(sharedHandler.accepted.isEmpty())
        assertTrue(sharedHandler.rejected.any { it.reason === ArcThreadLocalRCRejectionReason.InvalidExceptionalCleanup })
    }

    @Test
    fun unresolvedIdentityIssueCannotCollapseIntoEmptySuccess() {
        val missing = ArcSSAValue("missing")
        val alias = ArcSSAValue("alias")
        val cfg = ArcOwnershipSSAInput(
            entry,
            mapOf(entry to ArcSSABlock(entry, listOf(ArcSSAOperation.Forward(missing, alias)))),
            emptySet(),
        )
        val result = ArcThreadLocalRCAnalysis.analyze(
            ArcThreadLocalRCInput(
                ArcRCIdentityInput(cfg, emptyList()),
                rootFacts = emptyMap(),
                effects = listOf(alias(entry, 0, missing, alias)),
                ownershipOperations = emptyList(),
                exceptionCleanups = emptyList(),
                coverage = completeCoverage(setOf(missing, alias)),
            )
        )

        assertTrue(result.accepted.isEmpty())
        assertTrue(result.rejected.any { it.reason === ArcThreadLocalRCRejectionReason.OwnershipIdentityIssue })
    }

    @Test
    fun strictComparisonMatchesLegacyLocalVersusShareableSelection() {
        assertEquals(null, ArcStrictNonAtomicSelectionSemantics.usesAtomicHeapRC(ArcStrictContainerState.Stack))
        assertFalse(ArcStrictNonAtomicSelectionSemantics.usesAtomicHeapRC(ArcStrictContainerState.Local)!!)
        assertTrue(ArcStrictNonAtomicSelectionSemantics.usesAtomicHeapRC(ArcStrictContainerState.Frozen)!!)
        assertTrue(ArcStrictNonAtomicSelectionSemantics.usesAtomicHeapRC(ArcStrictContainerState.Shared)!!)
    }

    private fun linearInput(blocker: ArcThreadLocalRCEffectKind): ArcThreadLocalRCInput {
        val root = ArcSSAValue("root")
        val cfg = ArcOwnershipSSAInput(
            entry,
            mapOf(entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.Introduce(root, ArcOwnership.Owned),
                ArcSSAOperation.Use(root, ArcSSAUseKind.Borrow),
                ArcSSAOperation.DestroyOwned(ArcSSASlot("root"), ArcSSASlotVersion("root.1"), root),
            ))),
            emptySet(),
        )
        return ArcThreadLocalRCInput(
            ArcRCIdentityInput(cfg, emptyList()),
            rootFacts = mapOf(root to localRoot(root)),
            effects = listOf(ArcThreadLocalRCEffect(ArcRCPosition(entry, 1), blocker, root)),
            ownershipOperations = listOf(release(entry, 2, root)),
            exceptionCleanups = emptyList(),
            coverage = completeCoverage(setOf(root)),
        )
    }

    private fun localRoot(value: ArcSSAValue) = ArcThreadLocalRootFact(
        value, Lifetime.LOCAL, ArcThreadLocalRootOrigin.LocalHeapAllocation, isHeapAllocation = true,
    )

    private fun completeCoverage(values: Set<ArcSSAValue>) = ArcThreadLocalRCCoverage(
        values,
        allReferenceRelationsEnumerated = true,
        allOwnershipOperationsEnumerated = true,
        allEscapesAndCallsClassified = true,
    )

    private fun alias(
        block: ArcBlockId,
        operationIndex: Int,
        source: ArcSSAValue,
        result: ArcSSAValue,
    ) = ArcThreadLocalRCEffect(
        ArcRCPosition(block, operationIndex), ArcThreadLocalRCEffectKind.Alias, source, result,
    )

    private fun release(block: ArcBlockId, operationIndex: Int, value: ArcSSAValue) =
        ArcThreadLocalRCOperation(
            ArcRCPosition(block, operationIndex), ordinal = 0, ArcThreadLocalRCOperationKind.Release, value,
        )
}
