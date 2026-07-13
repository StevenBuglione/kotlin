/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcCoroutineSuspendedScopedBorrowIRSelectorTest {
    @Test
    fun exactLoweredInventoryAdaptsToScopedBorrow() {
        val selection = adaptVerifiedCoroutineSuspendedBorrowIR(validBindings(), validMode(), validShape()).selection!!
        assertEquals(ArcOwnershipVerificationResult.Success, ArcOwnershipVerifier.verify(selection.ownership.ownershipProof))
        assertTrue(selection.ownership.candidate.call === selection.bindings.call)
        assertTrue(selection.ownership.candidate.comparison === selection.bindings.comparison)
    }

    @Test
    fun everyRealWalkerFactIsMandatory() {
        val shape = validShape()
        listOf(
            shape.copy(exactPropertyGetterBody = false), shape.copy(exactGlobalInitializer = false),
            shape.copy(exactEnumGetterZero = false), shape.copy(exactSharedImmutableRoot = false),
            shape.copy(exactRootedArrayProjection = false), shape.copy(restrictedRootWrites = false),
            shape.copy(onlyVerifiedRootUses = false),
            shape.copy(exactIdentityComparisonOperand = false), shape.copy(completeLoweredIRWalk = false),
        ).forEach { malformed ->
            val result = adaptVerifiedCoroutineSuspendedBorrowIR(validBindings(), validMode(), malformed)
            assertNull(result.selection)
            assertEquals(ArcCoroutineSuspendedBorrowIRRejectionReason.InvalidStructuralProof, result.rejection)
        }
    }

    @Test
    fun duplicateIdentityAndUnsupportedModesFailClosed() {
        val bindings = validBindings()
        listOf(
            bindings.copy(call = bindings.comparison),
            bindings.copy(comparison = bindings.call),
            bindings.copy(function = bindings.call),
            bindings.copy(propertyGetter = bindings.call),
            bindings.copy(propertyReturn = bindings.call),
            bindings.copy(initializerCall = bindings.call),
            bindings.copy(initializer = bindings.call),
            bindings.copy(enumGetterCall = bindings.call),
            bindings.copy(enumGetter = bindings.call),
            bindings.copy(enumClass = bindings.call),
            bindings.copy(valuesRoot = bindings.call),
            bindings.copy(enumGetterReturn = bindings.call),
            bindings.copy(rootRead = bindings.call),
            bindings.copy(arrayGet = bindings.call),
        ).forEach { duplicate ->
            assertEquals(
                ArcCoroutineSuspendedBorrowIRRejectionReason.DuplicateStructuralIdentity,
                adaptVerifiedCoroutineSuspendedBorrowIR(duplicate, validMode(), validShape()).rejection,
            )
        }
        val mode = validMode()
        listOf(
            mode.copy(arcEnabled = false), mode.copy(linuxX64 = false), mode.copy(finalBinary = false),
            mode.copy(optimizationsEnabled = false), mode.copy(debugInfoDisabled = false),
            mode.copy(diagnosticsDisabled = false), mode.copy(sanitizerDisabled = false),
            mode.copy(coverageDisabled = false),
        ).forEach { unsupported ->
            val result = adaptVerifiedCoroutineSuspendedBorrowIR(bindings, unsupported, validShape())
            assertEquals(ArcCoroutineSuspendedBorrowIRRejectionReason.OwnershipAnalysisRejected, result.rejection)
            assertEquals(ArcCoroutineSuspendedBorrowRejectionReason.UnsupportedCompilationMode, result.ownershipRejection)
        }
    }

    private fun validBindings() = ArcCoroutineSuspendedBorrowIRBindings(
        call = Any(), comparison = Any(), function = Any(), propertyGetter = Any(),
        propertyReturn = Any(), initializerCall = Any(), initializer = Any(),
        enumGetterCall = Any(), enumGetter = Any(), enumClass = Any(), valuesRoot = Any(),
        enumGetterReturn = Any(), rootRead = Any(), arrayGet = Any(),
    )

    private fun validMode() = ArcCoroutineSuspendedBorrowMode(true, true, true, true, true, true, true, true)

    private fun validShape() = ArcCoroutineSuspendedBorrowIRShape(
        exactPropertyGetterBody = true, exactGlobalInitializer = true, exactEnumGetterZero = true,
        exactSharedImmutableRoot = true, exactRootedArrayProjection = true,
        restrictedRootWrites = true, exactIdentityComparisonOperand = true,
        onlyVerifiedRootUses = true,
        completeLoweredIRWalk = true,
    )
}
