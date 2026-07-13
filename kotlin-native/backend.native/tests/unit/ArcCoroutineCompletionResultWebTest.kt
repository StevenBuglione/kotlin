/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcCoroutineCompletionResultWebTest {
    @Test
    fun exactWebBalancesEveryNormalAndExceptionalPath() {
        val selection = ArcCoroutineCompletionResultAnalysis.select(
            validBindings(), validMode(), validProof(),
        ).selection!!

        assertEquals(
            ArcOwnershipVerificationResult.Success,
            ArcOwnershipVerifier.verify(selection.normalAndUnwindOwnershipProof),
        )
        assertEquals(
            ArcOwnershipVerificationResult.Success,
            ArcOwnershipVerifier.verify(selection.suspendedOwnershipProof),
        )
        assertEquals(
            ArcOwnershipVerificationResult.Success,
            ArcOwnershipVerifier.verify(selection.failureConstructionOwnershipProof),
        )
        assertEquals(ArcCoroutineCompletionResultPath.values().toSet(), selection.pathLedger.map { it.path }.toSet())
        assertFalse(selection.emissionAuthorized)
    }

    @Test
    fun reductionCreditsOnlyTheOwnedBackedgeMove() {
        val reduction = ArcCoroutineCompletionResultAnalysis.select(
            validBindings(), validMode(), validProof(),
        ).selection!!.reduction

        assertEquals(1, reduction.backedgeStackUpdatesRemoved)
        assertEquals(1, reduction.backedgeRetainsRemoved)
        assertEquals(1, reduction.backedgeSourceReleasesRemoved)
        assertEquals(0, reduction.completionProjectionTrafficRemoved)
        assertEquals(0, reduction.terminalTrafficRemoved)
        assertEquals(1, reduction.preservedCompletionRetains)
        assertEquals(2, reduction.preservedReplacedSlotReleases)
        assertEquals(1, reduction.preservedCaughtExceptionReleases)
    }

    @Test
    fun ledgersKeepAllRequiredNormalAndUnwindCleanup() {
        val ledgers = ArcCoroutineCompletionResultAnalysis.select(
            validBindings(), validMode(), validProof(),
        ).selection!!.pathLedger.associateBy { it.path }

        assertTrue(
            ArcCoroutineCompletionResultEvent.DestroySuspendedInvokeResult in
                    ledgers.getValue(ArcCoroutineCompletionResultPath.SuspendedReturn).requiredEvents,
        )
        listOf(
            ArcCoroutineCompletionResultPath.SuccessBackedge,
            ArcCoroutineCompletionResultPath.FailureBackedge,
        ).forEach { path ->
            val events = ledgers.getValue(path).requiredEvents
            assertTrue(ArcCoroutineCompletionResultEvent.RetainCompletionBeforeCurrentReplacement in events)
            assertTrue(ArcCoroutineCompletionResultEvent.ConsumeOutcomeIntoParameter in events)
            assertTrue(ArcCoroutineCompletionResultEvent.ReleaseReplacedParameterOwner in events)
        }
        assertTrue(
            ArcCoroutineCompletionResultEvent.DestroyOutcomeOnReleaseUnwind in
                    ledgers.getValue(ArcCoroutineCompletionResultPath.SuccessReleaseInterceptedUnwind).requiredEvents,
        )
        assertTrue(
            ArcCoroutineCompletionResultEvent.DestroyOutcomeOnTerminalUnwind in
                    ledgers.getValue(ArcCoroutineCompletionResultPath.SuccessTerminalResumeUnwind).requiredEvents,
        )
        assertTrue(
            ArcCoroutineCompletionResultEvent.DestroyCaughtExceptionOnFailureFactoryUnwind in
                    ledgers.getValue(ArcCoroutineCompletionResultPath.FailureFactoryUnwind).requiredEvents,
        )
        listOf(
            ArcCoroutineCompletionResultPath.FailureBackedge,
            ArcCoroutineCompletionResultPath.FailureTerminalReturn,
            ArcCoroutineCompletionResultPath.FailureReleaseInterceptedUnwind,
            ArcCoroutineCompletionResultPath.FailureTerminalResumeUnwind,
        ).forEach { path ->
            assertTrue(
                ArcCoroutineCompletionResultEvent.ReleaseCaughtExceptionOwnerAfterFailureMaterialization in
                        ledgers.getValue(path).requiredEvents,
            )
        }
        assertTrue(ledgers.values.all {
            ArcCoroutineCompletionResultEvent.EndCompletionBorrow in it.requiredEvents
        })
    }

    @Test
    fun unwindLedgerNeverConflatesSuccessFailureOrFactoryEdges() {
        val ledgers = ArcCoroutineCompletionResultAnalysis.select(
            validBindings(), validMode(), validProof(),
        ).selection!!.pathLedger.associateBy { it.path }
        val caughtCleanup = ArcCoroutineCompletionResultEvent.ReleaseCaughtExceptionOwnerAfterFailureMaterialization
        val outcomeCleanup = setOf(
            ArcCoroutineCompletionResultEvent.DestroyOutcomeOnReleaseUnwind,
            ArcCoroutineCompletionResultEvent.DestroyOutcomeOnTerminalUnwind,
        )

        listOf(
            ArcCoroutineCompletionResultPath.SuccessReleaseInterceptedUnwind,
            ArcCoroutineCompletionResultPath.SuccessTerminalResumeUnwind,
        ).forEach { path -> assertFalse(caughtCleanup in ledgers.getValue(path).requiredEvents) }
        listOf(
            ArcCoroutineCompletionResultPath.FailureReleaseInterceptedUnwind,
            ArcCoroutineCompletionResultPath.FailureTerminalResumeUnwind,
        ).forEach { path -> assertTrue(caughtCleanup in ledgers.getValue(path).requiredEvents) }

        val factoryUnwind = ledgers.getValue(ArcCoroutineCompletionResultPath.FailureFactoryUnwind).requiredEvents
        assertTrue(ArcCoroutineCompletionResultEvent.DestroyCaughtExceptionOnFailureFactoryUnwind in factoryUnwind)
        assertTrue(factoryUnwind.intersect(outcomeCleanup).isEmpty())
        assertFalse(ArcCoroutineCompletionResultEvent.PreserveOutcomeAcrossReleaseIntercepted in factoryUnwind)
    }

    @Test
    fun physicalBackedgeMoveHasOneExactNonThrowingSelfAliasSafeTrace() {
        val selection = ArcCoroutineCompletionResultAnalysis.select(
            validBindings(), validMode(), validProof(),
        ).selection!!
        val move = selection.physicalBackedgeMove
        val verification = ArcCoroutineCompletionPhysicalMoveVerifier.verify(move, selection.bindings)

        assertTrue(verification.rejections.toString(), verification.isSuccess)
        val exactOrder = ArcCoroutineCompletionPhysicalMoveAction.values().toList()
        listOf(
            ArcCoroutineCompletionResultPath.SuccessBackedge,
            ArcCoroutineCompletionResultPath.FailureBackedge,
        ).forEach { path ->
            val actions = move.pathActions.getValue(path)
            assertEquals(exactOrder, actions.map { it.action })
            assertEquals(exactOrder.size, actions.map { it.action }.toSet().size)
            assertTrue(actions.none { it.canUnwind })
            ArcCoroutineCompletionPhysicalMoveLedger(selection, selection.bindings.function, path).also { ledger ->
                actions.forEach { ledger.consume(it.action, it.bindingIdentities) }
                ledger.verifyComplete()
            }
        }
        ArcCoroutineCompletionResultPath.values().filterNot {
            it === ArcCoroutineCompletionResultPath.SuccessBackedge ||
                    it === ArcCoroutineCompletionResultPath.FailureBackedge
        }.forEach { path ->
            assertTrue(move.pathActions.getValue(path).isEmpty())
            ArcCoroutineCompletionPhysicalMoveLedger(selection, selection.bindings.function, path).verifyComplete()
        }
        assertFalse(selection.emissionAuthorized)
    }

    @Test
    fun physicalMoveLedgerRejectsWrongOrderDuplicatesMissingActionsAndIdentityDrift() {
        val selection = ArcCoroutineCompletionResultAnalysis.select(
            validBindings(), validMode(), validProof(),
        ).selection!!
        val actions = selection.physicalBackedgeMove.pathActions.getValue(
            ArcCoroutineCompletionResultPath.SuccessBackedge,
        )

        expectFailure {
            ArcCoroutineCompletionPhysicalMoveLedger(
                selection, selection.bindings.function, ArcCoroutineCompletionResultPath.SuccessBackedge,
            ).consume(actions[1].action, actions[1].bindingIdentities)
        }
        expectFailure {
            ArcCoroutineCompletionPhysicalMoveLedger(
                selection, selection.bindings.function, ArcCoroutineCompletionResultPath.SuccessBackedge,
            ).consume(actions[0].action, actions[0].bindingIdentities.mapIndexed { index, identity ->
                if (index == 0) Any() else identity
            })
        }
        expectFailure {
            ArcCoroutineCompletionPhysicalMoveLedger(
                selection, selection.bindings.function, ArcCoroutineCompletionResultPath.SuccessBackedge,
            ).also { ledger ->
                ledger.consume(actions[0].action, actions[0].bindingIdentities)
                ledger.verifyComplete()
            }
        }
        expectFailure {
            ArcCoroutineCompletionPhysicalMoveLedger(
                selection, selection.bindings.function, ArcCoroutineCompletionResultPath.SuccessBackedge,
            ).also { ledger ->
                actions.forEach { ledger.consume(it.action, it.bindingIdentities) }
                ledger.consume(actions.last().action, actions.last().bindingIdentities)
            }
        }
        expectFailure {
            ArcCoroutineCompletionPhysicalMoveLedger(
                selection, Any(), ArcCoroutineCompletionResultPath.SuccessBackedge,
            )
        }

        // An unwind path has an exact empty trace. No transfer can start on that edge.
        expectFailure {
            ArcCoroutineCompletionPhysicalMoveLedger(
                selection, selection.bindings.function,
                ArcCoroutineCompletionResultPath.SuccessReleaseInterceptedUnwind,
            ).consume(actions.first().action, actions.first().bindingIdentities)
        }
    }

    @Test
    fun physicalMoveVerifierRejectsUnsafeAliasOrderingAndAnyUnwindSurface() {
        val selection = ArcCoroutineCompletionResultAnalysis.select(
            validBindings(), validMode(), validProof(),
        ).selection!!
        val move = selection.physicalBackedgeMove
        val path = ArcCoroutineCompletionResultPath.SuccessBackedge
        val actions = move.pathActions.getValue(path)

        val release = actions.indexOfFirst {
            it.action === ArcCoroutineCompletionPhysicalMoveAction.ReleaseReplacedParameterOwner
        }
        val store = actions.indexOfFirst {
            it.action === ArcCoroutineCompletionPhysicalMoveAction.StoreOutcomeIntoParameterWithoutRetain
        }
        val unsafe = actions.toMutableList().also {
            val releaseAction = it.removeAt(release)
            it.add(store, releaseAction)
        }
        assertPhysicalRejected(
            move.copy(pathActions = move.pathActions + (path to unsafe)),
            selection.bindings,
            ArcCoroutineCompletionPhysicalMoveRejection.SelfAliasUnsafeOrder,
        )

        val throwing = actions.toMutableList().also { it[5] = it[5].copy(canUnwind = true) }
        assertPhysicalRejected(
            move.copy(pathActions = move.pathActions + (path to throwing)),
            selection.bindings,
            ArcCoroutineCompletionPhysicalMoveRejection.PotentiallyUnwindingTransfer,
        )

        assertPhysicalRejected(
            move.copy(pathActions = move.pathActions +
                    (ArcCoroutineCompletionResultPath.SuccessTerminalResumeUnwind to listOf(actions.first()))),
            selection.bindings,
            ArcCoroutineCompletionPhysicalMoveRejection.ActionOnNonBackedgePath,
        )

        val drifted = actions.toMutableList().also {
            it[0] = it[0].copy(bindingIdentities = listOf(Any(), it[0].bindingIdentities.last()))
        }
        assertPhysicalRejected(
            move.copy(pathActions = move.pathActions + (path to drifted)),
            selection.bindings,
            ArcCoroutineCompletionPhysicalMoveRejection.BindingIdentityMismatch,
        )
        val duplicated = actions.toMutableList().also { it.add(1, it.first()) }
        assertPhysicalRejected(
            move.copy(pathActions = move.pathActions + (path to duplicated)),
            selection.bindings,
            ArcCoroutineCompletionPhysicalMoveRejection.DuplicateAction,
        )
        assertPhysicalRejected(
            move.copy(pathActions = move.pathActions - ArcCoroutineCompletionResultPath.SuspendedReturn),
            selection.bindings,
            ArcCoroutineCompletionPhysicalMoveRejection.IncompletePathInventory,
        )
        assertPhysicalRejected(
            move.copy(functionBinding = Any()),
            selection.bindings,
            ArcCoroutineCompletionPhysicalMoveRejection.FunctionIdentityMismatch,
        )
    }

    @Test
    fun everyStructuralFactIsMandatory() {
        val proof = validProof()
        listOf(
            proof.copy(exactBaseContinuationSelection = false),
            proof.copy(exactFinalStrongCompletionField = false),
            proof.copy(exactNullCheckedCompletionProjection = false),
            proof.copy(completionUsesExhaustive = false),
            proof.copy(currentAnchorCoversNormalAndUnwind = false),
            proof.copy(invokeSuspendProducesOwnedResult = false),
            proof.copy(suspendedExitDestroysOwnedResult = false),
            proof.copy(successResultIsIdentityForward = false),
            proof.copy(failureFactoryProducesOwnedResult = false),
            proof.copy(failureFactoryUnwindDestroysCaughtException = false),
            proof.copy(failureCopiesExceptionBeforeCatchOwnerRelease = false),
            proof.copy(exactReferenceResultCatchOwnershipABI = false),
            proof.copy(outcomeJoinIsExactlySuccessOrFailure = false),
            proof.copy(outcomeUsesExhaustive = false),
            proof.copy(releaseUnwindDestroysOutcome = false),
            proof.copy(backedgeRetainsCompletionBeforeReplacingCurrent = false),
            proof.copy(backedgeConsumesOutcomeIntoParameter = false),
            proof.copy(terminalCallBorrowsOutcome = false),
            proof.copy(terminalNormalDestroysOutcome = false),
            proof.copy(terminalUnwindDestroysOutcome = false),
            proof.copy(everyExitEndsCompletionBorrow = false),
        ).forEach { incomplete ->
            val result = ArcCoroutineCompletionResultAnalysis.select(validBindings(), validMode(), incomplete)
            assertNull(result.selection)
            assertEquals(ArcCoroutineCompletionResultRejectionReason.IncompleteStructuralProof, result.rejection)
        }
    }

    @Test
    fun unsupportedModesAndAliasedIdentitiesFailClosed() {
        val mode = validMode()
        listOf(
            mode.copy(arcEnabled = false), mode.copy(linuxX64 = false), mode.copy(finalBinary = false),
            mode.copy(optimizationsEnabled = false), mode.copy(debugInfoDisabled = false),
            mode.copy(diagnosticsDisabled = false), mode.copy(sanitizerDisabled = false),
            mode.copy(coverageDisabled = false),
        ).forEach { unsupported ->
            assertEquals(
                ArcCoroutineCompletionResultRejectionReason.UnsupportedCompilationMode,
                ArcCoroutineCompletionResultAnalysis.select(validBindings(), unsupported, validProof()).rejection,
            )
        }

        val bindings = validBindings()
        assertEquals(
            ArcCoroutineCompletionResultRejectionReason.DuplicateStructuralIdentity,
            ArcCoroutineCompletionResultAnalysis.select(
                bindings.copy(outcomeJoin = bindings.invokeOwnedResult), validMode(), validProof(),
            ).rejection,
        )
    }

    private fun validBindings(): ArcCoroutineCompletionResultBindings<Any> {
        val values = List(27) { Any() }
        return ArcCoroutineCompletionResultBindings(
            values[0], values[1], values[2], values[3], values[4], values[5], values[6],
            values[7], values[8], values[9], values[10], values[11], values[12], values[13],
            values[14], values[15], values[16], values[17], values[18], values[19], values[20], values[21],
            ArcCoroutineCompletionPhysicalMoveOperands(
                values[22], values[23], values[24], values[25], values[26],
            ),
        )
    }

    private fun validMode() = ArcCoroutineCompletionResultMode(
        arcEnabled = true, linuxX64 = true, finalBinary = true, optimizationsEnabled = true,
        debugInfoDisabled = true, diagnosticsDisabled = true, sanitizerDisabled = true,
        coverageDisabled = true,
    )

    private fun validProof() = ArcCoroutineCompletionResultProof(
        exactBaseContinuationSelection = true,
        exactFinalStrongCompletionField = true,
        exactNullCheckedCompletionProjection = true,
        completionUsesExhaustive = true,
        currentAnchorCoversNormalAndUnwind = true,
        invokeSuspendProducesOwnedResult = true,
        suspendedExitDestroysOwnedResult = true,
        successResultIsIdentityForward = true,
        failureFactoryProducesOwnedResult = true,
        failureFactoryUnwindDestroysCaughtException = true,
        failureCopiesExceptionBeforeCatchOwnerRelease = true,
        exactReferenceResultCatchOwnershipABI = true,
        outcomeJoinIsExactlySuccessOrFailure = true,
        outcomeUsesExhaustive = true,
        releaseUnwindDestroysOutcome = true,
        backedgeRetainsCompletionBeforeReplacingCurrent = true,
        backedgeConsumesOutcomeIntoParameter = true,
        terminalCallBorrowsOutcome = true,
        terminalNormalDestroysOutcome = true,
        terminalUnwindDestroysOutcome = true,
        everyExitEndsCompletionBorrow = true,
    )

    private fun assertPhysicalRejected(
        move: ArcCoroutineCompletionPhysicalBackedgeMove<Any>,
        bindings: ArcCoroutineCompletionResultBindings<Any>,
        reason: ArcCoroutineCompletionPhysicalMoveRejection,
    ) {
        val verification = ArcCoroutineCompletionPhysicalMoveVerifier.verify(move, bindings)
        assertTrue(verification.rejections.toString(), reason in verification.rejections)
        assertFalse(verification.isSuccess)
    }

    private fun expectFailure(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue("expected fail-closed physical emission rejection", failed)
    }
}
