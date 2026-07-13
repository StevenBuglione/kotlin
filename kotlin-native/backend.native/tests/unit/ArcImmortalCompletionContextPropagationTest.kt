/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ArcImmortalCompletionContextPropagationTest {
    @Test
    fun exactFreshPermanentFieldBuildsTwoVerifiedOwnershipProofs() {
        val selection = ArcImmortalCompletionContextAnalysis.select(validCandidate()).selection!!

        assertEquals(
            ArcOwnershipVerificationResult.Success,
            ArcOwnershipVerifier.verify(selection.initializerOwnershipProof),
        )
        assertEquals(
            ArcOwnershipVerificationResult.Success,
            ArcOwnershipVerifier.verify(selection.getterOwnershipProof),
        )
        assertEquals(ArcImmortalCompletionContextReduction(), selection.reduction)
        assertEquals(
            listOf(
                ArcImmortalCompletionContextRewrite.PropagatePermanentRootIntoField,
                ArcImmortalCompletionContextRewrite.RawInitializeFreshField,
                ArcImmortalCompletionContextRewrite.EliminatePermanentFieldCopy,
                ArcImmortalCompletionContextRewrite.EliminatePermanentFieldDestroy,
                ArcImmortalCompletionContextRewrite.PublishPermanentResultReleasingPriorSlot,
            ),
            selection.rewrites,
        )
        assertEquals(
            ArcImmortalCompletionContextEmissionPolicy(
                rawInitializeOnlyFreshField = true,
                initializeDoesNotReleaseOldValue = true,
                publishWithMoveThatReleasesPriorResultSlot = true,
                neverRawReplaceInitializedStorage = true,
            ),
            selection.emissionPolicy,
        )

        ArcImmortalCompletionContextConsumptionLedger(selection).also { ledger ->
            val candidate = selection.candidate
            ledger.consumeInitializer(
                candidate.constructorBinding,
                candidate.fieldBinding,
                candidate.initializerBinding,
                candidate.permanentRootBinding,
            )
            ledger.consumeGetter(
                candidate.getterBinding,
                candidate.fieldLoadBinding,
                candidate.returnBinding,
            )
            ledger.verifyComplete()
        }
    }

    @Test
    fun everyNonProductionModeFallsBack() {
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
        ).forEach { mode ->
            assertRejected(
                candidate.copy(mode = mode),
                ArcImmortalCompletionContextRejectionReason.UnsupportedCompilationMode,
            )
        }
    }

    @Test
    fun ordinaryHeapObjectsAndWritableRootsAreRejected() {
        val candidate = validCandidate()
        val root = candidate.root
        listOf(
            root.copy(exactCoroutineContextType = false),
            root.copy(exactEmptyCoroutineContextObject = false),
            root.copy(exactPrivateFinalStaticRoot = false),
            root.copy(exactConstantObjectInitializer = false),
            root.copy(exactPrivatePrimaryObjectConstructor = false),
            root.copy(runtimeUsesPermanentTagOne = false),
            root.copy(noRootWrites = false),
        ).forEach { malformed ->
            assertRejected(
                candidate.copy(root = malformed),
                ArcImmortalCompletionContextRejectionReason.PermanentRootMismatch,
            )
        }
    }

    @Test
    fun mutableOverridableSubclassedAndEscapingFieldsAreRejected() {
        val candidate = validCandidate()
        val field = candidate.field
        listOf(
            field.copy(exactContinuationContextOverride = false),
            field.copy(ownerIsExactUserCompletionClass = false),
            field.copy(ownerClassFinal = false),
            field.copy(noOwnerSubclasses = false),
            field.copy(propertyIsImmutable = false),
            field.copy(propertyEffectivelyFinal = false),
            field.copy(getterEffectivelyFinal = false),
            field.copy(noGetterOverrides = false),
            field.copy(backingFieldPrivateFinalStrongReference = false),
            field.copy(fieldAddressDoesNotEscape = false),
            field.copy(exactlyOneFieldWrite = false),
            field.copy(onlyWriteIsSelectedConstructorStore = false),
        ).forEach { malformed ->
            assertRejected(
                candidate.copy(field = malformed),
                ArcImmortalCompletionContextRejectionReason.MutableOrOverridableField,
            )
        }
    }

    @Test
    fun replacementEscapeAndConstructorFailureShapesAreRejected() {
        val candidate = validCandidate()
        val initializer = candidate.initializer
        listOf(
            initializer.copy(exactConstructorAndAllocation = false),
            initializer.copy(receiverIsFreshZeroedAllocation = false),
            initializer.copy(receiverHasNotEscaped = false),
            initializer.copy(fieldDefinitelyUninitialized = false),
            initializer.copy(noFieldReadBeforeInitialization = false),
            initializer.copy(valueIsDirectPermanentRootLoad = false),
            initializer.copy(storeDominatesEveryReceiverEscape = false),
            initializer.copy(storeOccursOnEverySuccessfulConstructorPath = false),
            initializer.copy(noDelegatingConstructorDrift = false),
            initializer.copy(noExceptionalEdgeBeforeOrAtStore = false),
            initializer.copy(noCatchOrFinallyAroundStore = false),
        ).forEach { malformed ->
            assertRejected(
                candidate.copy(initializer = malformed),
                ArcImmortalCompletionContextRejectionReason.UnsafeOrFailableInitialization,
            )
        }
    }

    @Test
    fun nontrivialMutableAndOverridableGetterShapesAreRejected() {
        val candidate = validCandidate()
        val getter = candidate.getter
        listOf(
            getter.copy(exactSingleReturnBody = false),
            getter.copy(returnTargetsExactGetter = false),
            getter.copy(valueIsDirectSelectedFieldLoad = false),
            getter.copy(receiverOnlyUsedForSelectedLoad = false),
            getter.copy(returnTypeMatchesFieldType = false),
            getter.copy(noMutableOrVolatileRead = false),
            getter.copy(noNestedDeclaration = false),
            getter.copy(noExceptionalEdge = false),
        ).forEach { malformed ->
            assertRejected(
                candidate.copy(getter = malformed),
                ArcImmortalCompletionContextRejectionReason.GetterShapeMismatch,
            )
        }
    }

    @Test
    fun reorderedDuplicatedReplacementAndUnknownEffectsFallBack() {
        val candidate = validCandidate()
        val allowed = candidate.effects
        val rejected = mutableListOf(
            allowed.reversed(),
            allowed.dropLast(1),
            allowed + ArcImmortalCompletionContextEffect.PermanentRootLoad,
        )
        listOf(
            ArcImmortalCompletionContextEffect.StrongFieldReplacement,
            ArcImmortalCompletionContextEffect.MutableLoad,
            ArcImmortalCompletionContextEffect.ReceiverEscape,
            ArcImmortalCompletionContextEffect.UserCall,
            ArcImmortalCompletionContextEffect.VirtualCall,
            ArcImmortalCompletionContextEffect.ExternalCall,
            ArcImmortalCompletionContextEffect.Suspension,
            ArcImmortalCompletionContextEffect.TryRegion,
            ArcImmortalCompletionContextEffect.Unknown,
        ).mapTo(rejected) { allowed + it }
        rejected.forEach { effects ->
            assertRejected(
                candidate.copy(effects = effects),
                ArcImmortalCompletionContextRejectionReason.UnsupportedEffect,
            )
        }
    }

    @Test
    fun duplicateIdentityRejectsAndEmissionLedgerIsExactAndComplete() {
        val candidate = validCandidate()
        assertRejected(
            candidate.copy(
                exactIdentityBindings = candidate.exactIdentityBindings.toMutableList().also {
                    it[it.lastIndex] = it.first()
                },
            ),
            ArcImmortalCompletionContextRejectionReason.DuplicateStructuralIdentity,
        )

        val selection = ArcImmortalCompletionContextAnalysis.select(candidate).selection!!
        val alien = Any()
        expectFailure("constructor identity") {
            ArcImmortalCompletionContextConsumptionLedger(selection).consumeInitializer(
                alien,
                candidate.fieldBinding,
                candidate.initializerBinding,
                candidate.permanentRootBinding,
            )
        }
        expectFailure("field identity") {
            ArcImmortalCompletionContextConsumptionLedger(selection).consumeInitializer(
                candidate.constructorBinding,
                alien,
                candidate.initializerBinding,
                candidate.permanentRootBinding,
            )
        }
        expectFailure("getter identity") {
            ArcImmortalCompletionContextConsumptionLedger(selection).consumeGetter(
                alien,
                candidate.fieldLoadBinding,
                candidate.returnBinding,
            )
        }
        expectFailure("initializer was not emitted") {
            ArcImmortalCompletionContextConsumptionLedger(selection).verifyComplete()
        }

        val ledger = ArcImmortalCompletionContextConsumptionLedger(selection)
        ledger.consumeInitializer(
            candidate.constructorBinding,
            candidate.fieldBinding,
            candidate.initializerBinding,
            candidate.permanentRootBinding,
        )
        expectFailure("getter was not emitted") { ledger.verifyComplete() }
        ledger.consumeGetter(candidate.getterBinding, candidate.fieldLoadBinding, candidate.returnBinding)
        ledger.verifyComplete()
        expectFailure("getter consumed twice") {
            ledger.consumeGetter(candidate.getterBinding, candidate.fieldLoadBinding, candidate.returnBinding)
        }
    }

    private fun validCandidate(): ArcImmortalCompletionContextCandidate<Any> =
        ArcImmortalCompletionContextCandidate(
            constructorBinding = Any(),
            fieldBinding = Any(),
            initializerBinding = Any(),
            getterBinding = Any(),
            fieldLoadBinding = Any(),
            returnBinding = Any(),
            permanentRootBinding = Any(),
            mode = ArcImmortalCompletionContextMode(true, true, true, true, true, true, true, true),
            root = ArcImmortalCompletionContextRootProof(true, true, true, true, true, true, true),
            field = ArcImmortalCompletionContextFieldProof(
                true, true, true, true, true, true, true, true, true, true, true, true,
            ),
            initializer = ArcImmortalCompletionContextInitializerProof(
                true, true, true, true, true, true, true, true, true, true, true,
            ),
            getter = ArcImmortalCompletionContextGetterProof(true, true, true, true, true, true, true, true),
            effects = listOf(
                ArcImmortalCompletionContextEffect.FreshAllocation,
                ArcImmortalCompletionContextEffect.PermanentRootLoad,
                ArcImmortalCompletionContextEffect.FirstStrongFieldInitialization,
                ArcImmortalCompletionContextEffect.FinalStrongFieldLoad,
                ArcImmortalCompletionContextEffect.OwnedResultSlotPublication,
            ),
        )

    private fun assertRejected(
        candidate: ArcImmortalCompletionContextCandidate<Any>,
        reason: ArcImmortalCompletionContextRejectionReason,
    ) {
        val result = ArcImmortalCompletionContextAnalysis.select(candidate)
        assertNull(result.selection)
        assertEquals(reason, result.rejection)
    }

    private fun expectFailure(message: String, block: () -> Unit) {
        try {
            block()
            fail("expected failure containing '$message'")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(message))
        }
    }
}
