/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ArcScopedCStringLoopCacheTest {
    @Test
    fun exactNoCallbackConstCStringLoopBuildsTransactionalCachePlan() {
        val candidate = validCandidate()
        val result = ArcScopedCStringLoopCacheAnalysis.select(candidate)

        assertTrue(result.rejections.isEmpty())
        val plan = result.plan!!
        assertEquals(ARC_CREATE_SCOPED_CSTRING, plan.createHelper)
        assertEquals(ARC_DISPOSE_SCOPED_CSTRING, plan.disposeHelper)
        assertSame(candidate.lifetime.foreignCallBinding, plan.foreignCallBinding)
        assertEquals(ArcScopedCStringLoopCacheActionId.values().toSet(), plan.actions.keys)

        ArcScopedCStringLoopCacheConsumptionLedger(plan, candidate.lifetime.functionBinding).also { ledger ->
            plan.actions.values.forEach(ledger::consume)
            ledger.verifyComplete()
        }
    }

    @Test
    fun observableAndUnsupportedModesSelectZero() {
        val candidate = validCandidate()
        listOf(
            candidate.mode.copy(arcEnabled = false),
            candidate.mode.copy(linuxX64 = false),
            candidate.mode.copy(finalBinary = false),
            candidate.mode.copy(optimizationsEnabled = false),
            candidate.mode.copy(debugInfoDisabled = false),
            candidate.mode.copy(diagnosticsDisabled = false),
            candidate.mode.copy(sanitizerDisabled = false),
            candidate.mode.copy(coverageDisabled = false),
            candidate.mode.copy(cLanguage = false),
            candidate.mode.copy(nonSuspendFunction = false),
            candidate.mode.copy(sourceFunction = false),
        ).forEach { mode ->
            assertRejected(candidate.copy(mode = mode), ArcScopedCStringLoopCacheRejectionReason.UnsupportedCompilationMode)
        }
    }

    @Test
    fun forgedOrUnsupportedCDeclarationsSelectZero() {
        val candidate = validCandidate()
        val declarationDrift = listOf(
            candidate.declarations.copy(exactInteropLibraryIdentity = false),
            candidate.declarations.copy(exactCCallSymbolAnnotation = false),
            candidate.declarations.copy(exactSerializedNoCallbackAnnotation = false),
            candidate.declarations.copy(exactSingleFixedCStringParameter = false),
            candidate.declarations.copy(constCharPointee = false),
            candidate.declarations.copy(nonVariadicCFunction = false),
            candidate.declarations.copy(primitiveOrUnitResult = false),
            candidate.declarations.copy(noReceiverOrFunctionPointerDispatch = false),
        )
        declarationDrift.forEach { declarations ->
            assertRejected(
                candidate.copy(declarations = declarations),
                ArcScopedCStringLoopCacheRejectionReason.DeclarationIdentityMismatch,
            )
        }
    }

    @Test
    fun incompleteIdentityWebAndEscapingPointerSelectZero() {
        val candidate = validCandidate()
        listOf(
            candidate.lifetime.copy(exactSingleNaturalLoop = false),
            candidate.lifetime.copy(loopHeaderDominatesEveryCall = false),
            candidate.lifetime.copy(exactStrongStringSourceSlot = false),
            candidate.lifetime.copy(sourceEvaluatedOncePerIteration = false),
            candidate.lifetime.copy(identityGuardDominatesConversionAndCall = false),
            candidate.lifetime.copy(everySourceDefinitionFlowsThroughGuard = false),
            candidate.lifetime.copy(noSourceDefinitionBetweenGuardAndCall = false),
            candidate.lifetime.copy(cachedSourceStronglyOwnsConvertedIdentity = false),
        ).forEach { lifetime ->
            assertRejected(
                candidate.copy(lifetime = lifetime),
                ArcScopedCStringLoopCacheRejectionReason.InvalidLoopIdentityWeb,
            )
        }
        listOf(
            candidate.lifetime.copy(pointerHasOneNonEscapingCallUsePerIteration = false),
            candidate.lifetime.copy(noCallbackOrSuspensionBoundary = false),
        ).forEach { lifetime ->
            assertRejected(
                candidate.copy(lifetime = lifetime),
                ArcScopedCStringLoopCacheRejectionReason.EscapingOrMutableCString,
            )
        }
    }

    @Test
    fun everyNormalExceptionalAndReplacementCleanupIsMandatory() {
        val candidate = validCandidate()
        listOf(
            candidate.lifetime.copy(normalCleanupPostDominatesLoop = false),
            candidate.lifetime.copy(exceptionalCleanupCoversEveryLoopExit = false),
            candidate.lifetime.copy(replacementCleanupIsExactlyOnce = false),
        ).forEach { lifetime ->
            assertRejected(
                candidate.copy(lifetime = lifetime),
                ArcScopedCStringLoopCacheRejectionReason.IncompleteCleanupFrontier,
            )
        }
    }

    @Test
    fun identityEncodingMutationAndTransactionSemanticsAreMandatory() {
        val candidate = validCandidate()
        val exact = candidate.semantics
        listOf(
            exact.copy(referenceIdentityComparison = false),
            exact.copy(exactUtf8ReplacementConverter = false),
            exact.copy(embeddedNulBehaviorPreserved = false),
            exact.copy(constPointeePreventsPersistentMutation = false),
            exact.copy(createNewBeforeDisposeOld = false),
            exact.copy(retainNewSourceBeforeReleaseOld = false),
            exact.copy(conversionFailureLeavesOldCacheOwned = false),
            exact.copy(argumentEvaluationOrderPreserved = false),
            exact.copy(foreignCallExceptionalSuccessorPreserved = false),
        ).forEach { semantics ->
            assertRejected(
                candidate.copy(semantics = semantics),
                ArcScopedCStringLoopCacheRejectionReason.EvaluationOrExceptionMismatch,
            )
        }
    }

    @Test
    fun incompleteWalkDuplicateBindingsAndPartialEmissionFailClosed() {
        val candidate = validCandidate()
        assertRejected(
            candidate.copy(completeLoweredIRWalk = false),
            ArcScopedCStringLoopCacheRejectionReason.IncompleteLoweredIRWalk,
        )
        assertRejected(
            candidate.copy(lifetime = candidate.lifetime.copy(
                conversionBinding = candidate.lifetime.identityGuardBinding,
            )),
            ArcScopedCStringLoopCacheRejectionReason.DuplicateStructuralBinding,
        )

        val plan = ArcScopedCStringLoopCacheAnalysis.select(candidate).plan!!
        assertFails { ArcScopedCStringLoopCacheConsumptionLedger(plan, Any()) }
        ArcScopedCStringLoopCacheConsumptionLedger(plan, candidate.lifetime.functionBinding).also { ledger ->
            ledger.consume(plan.actions.getValue(ArcScopedCStringLoopCacheActionId.InitializeEmptyCache))
            assertFails { ledger.verifyComplete() }
        }
    }

    private fun validCandidate(): ArcScopedCStringLoopCacheCandidate<Any> =
        ArcScopedCStringLoopCacheCandidate(
            mode = ArcScopedCStringLoopCacheMode(
                arcEnabled = true,
                linuxX64 = true,
                finalBinary = true,
                optimizationsEnabled = true,
                debugInfoDisabled = true,
                diagnosticsDisabled = true,
                sanitizerDisabled = true,
                coverageDisabled = true,
                cLanguage = true,
                nonSuspendFunction = true,
                sourceFunction = true,
            ),
            declarations = ArcScopedCStringLoopCacheDeclarationProof(
                interopLibraryBinding = Any(),
                foreignFunctionBinding = Any(),
                cStringParameterBinding = Any(),
                exactInteropLibraryIdentity = true,
                exactCCallSymbolAnnotation = true,
                exactSerializedNoCallbackAnnotation = true,
                exactSingleFixedCStringParameter = true,
                constCharPointee = true,
                nonVariadicCFunction = true,
                primitiveOrUnitResult = true,
                noReceiverOrFunctionPointerDispatch = true,
            ),
            lifetime = ArcScopedCStringLoopCacheLifetimeProof(
                functionBinding = Any(),
                loopBinding = Any(),
                sourceSlotBinding = Any(),
                sourceLoadBinding = Any(),
                cacheSourceSlotBinding = Any(),
                cachePointerSlotBinding = Any(),
                identityGuardBinding = Any(),
                conversionBinding = Any(),
                foreignCallBinding = Any(),
                replacementDisposeBinding = Any(),
                normalExitDisposeBinding = Any(),
                exceptionalExitDisposeBinding = Any(),
                exactSingleNaturalLoop = true,
                loopHeaderDominatesEveryCall = true,
                exactStrongStringSourceSlot = true,
                sourceEvaluatedOncePerIteration = true,
                identityGuardDominatesConversionAndCall = true,
                everySourceDefinitionFlowsThroughGuard = true,
                noSourceDefinitionBetweenGuardAndCall = true,
                cachedSourceStronglyOwnsConvertedIdentity = true,
                pointerHasOneNonEscapingCallUsePerIteration = true,
                noCallbackOrSuspensionBoundary = true,
                normalCleanupPostDominatesLoop = true,
                exceptionalCleanupCoversEveryLoopExit = true,
                replacementCleanupIsExactlyOnce = true,
            ),
            semantics = ArcScopedCStringLoopCacheSemanticsProof(
                referenceIdentityComparison = true,
                exactUtf8ReplacementConverter = true,
                embeddedNulBehaviorPreserved = true,
                constPointeePreventsPersistentMutation = true,
                createNewBeforeDisposeOld = true,
                retainNewSourceBeforeReleaseOld = true,
                conversionFailureLeavesOldCacheOwned = true,
                argumentEvaluationOrderPreserved = true,
                foreignCallExceptionalSuccessorPreserved = true,
            ),
            completeLoweredIRWalk = true,
        )

    private fun assertRejected(
        candidate: ArcScopedCStringLoopCacheCandidate<Any>,
        reason: ArcScopedCStringLoopCacheRejectionReason,
    ) {
        val result = ArcScopedCStringLoopCacheAnalysis.select(candidate)
        assertNull(result.plan)
        assertEquals(reason, result.rejections.single().reason)
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("expected scoped CString cache proof to fail closed")
        } catch (_: IllegalStateException) {
        }
    }
}
