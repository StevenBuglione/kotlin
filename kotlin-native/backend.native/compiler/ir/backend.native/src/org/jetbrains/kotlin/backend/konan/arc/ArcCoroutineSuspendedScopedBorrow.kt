/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import java.util.IdentityHashMap

/** Production boundary for borrowing the exact COROUTINE_SUSPENDED process-root projection. */
internal data class ArcCoroutineSuspendedBorrowMode(
    val arcEnabled: Boolean,
    val linuxX64: Boolean,
    val finalBinary: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val diagnosticsDisabled: Boolean,
    val sanitizerDisabled: Boolean,
    val coverageDisabled: Boolean,
)

internal data class ArcCoroutineSuspendedBorrowProof(
    val exactStdlibGetter: Boolean,
    val exactGlobalInitializerCall: Boolean,
    val exactCoroutineSingletonsEnum: Boolean,
    val exactEnumGetterZero: Boolean,
    val exactSharedImmutableValuesRoot: Boolean,
    val exactBorrowedArrayProjection: Boolean,
    val rootWritesRestrictedToInitializer: Boolean,
    val immediateIdentityComparison: Boolean,
    val noOwnedResultSlot: Boolean,
    val noStoreReturnOrEscape: Boolean,
)

internal data class ArcCoroutineSuspendedBorrowCandidate<T : Any>(
    val call: T,
    val comparison: T,
    val getter: T,
    val initializer: T,
    val enumGetter: T,
    val root: T,
    val rootRead: T,
    val exactIdentityBindings: List<T> =
        listOf(call, comparison, getter, initializer, enumGetter, root, rootRead),
    val mode: ArcCoroutineSuspendedBorrowMode,
    val proof: ArcCoroutineSuspendedBorrowProof,
)

internal data class ArcCoroutineSuspendedBorrowSelection<T : Any>(
    val candidate: ArcCoroutineSuspendedBorrowCandidate<T>,
    val ownershipProof: ArcFunctionPlan,
)

internal enum class ArcCoroutineSuspendedBorrowRejectionReason {
    UnsupportedCompilationMode,
    DeclarationOrRootMismatch,
    EscapingUse,
    OwnershipVerifierRejected,
}

internal data class ArcCoroutineSuspendedBorrowAnalysisResult<T : Any>(
    val selection: ArcCoroutineSuspendedBorrowSelection<T>?,
    val rejection: ArcCoroutineSuspendedBorrowRejectionReason?,
)

/**
 * Proves a scoped +0 projection, not a borrowed public return convention. CoroutineSingletons
 * entries are ordinary heap objects. Their immutable `$VALUES` root keeps them alive for the
 * process, while the selected identity comparison neither stores nor destroys the projection.
 */
internal object ArcCoroutineSuspendedBorrowAnalysis {
    fun <T : Any> select(
        candidate: ArcCoroutineSuspendedBorrowCandidate<T>,
    ): ArcCoroutineSuspendedBorrowAnalysisResult<T> {
        if (!candidate.mode.isProductionMode()) {
            return rejected(ArcCoroutineSuspendedBorrowRejectionReason.UnsupportedCompilationMode)
        }
        with(candidate.proof) {
            if (!exactStdlibGetter || !exactGlobalInitializerCall || !exactCoroutineSingletonsEnum ||
                !exactEnumGetterZero || !exactSharedImmutableValuesRoot ||
                !exactBorrowedArrayProjection || !rootWritesRestrictedToInitializer
            ) return rejected(ArcCoroutineSuspendedBorrowRejectionReason.DeclarationOrRootMismatch)
            if (!immediateIdentityComparison || !noOwnedResultSlot || !noStoreReturnOrEscape) {
                return rejected(ArcCoroutineSuspendedBorrowRejectionReason.EscapingUse)
            }
        }
        val ownership = ownershipProof()
        if (ArcOwnershipVerifier.verify(ownership) !== ArcOwnershipVerificationResult.Success) {
            return rejected(ArcCoroutineSuspendedBorrowRejectionReason.OwnershipVerifierRejected)
        }
        return ArcCoroutineSuspendedBorrowAnalysisResult(
            ArcCoroutineSuspendedBorrowSelection(candidate, ownership),
            null,
        )
    }

    private fun <T : Any> rejected(reason: ArcCoroutineSuspendedBorrowRejectionReason) =
        ArcCoroutineSuspendedBorrowAnalysisResult<T>(null, reason)

    private fun ownershipProof(): ArcFunctionPlan {
        val root = ArcValue("CoroutineSingletons.\$VALUES")
        val suspended = ArcValue("COROUTINE_SUSPENDED")
        val entry = ArcBlockId("entry")
        return ArcFunctionPlan(
            functionName = "COROUTINE_SUSPENDED scoped identity borrow",
            entry = entry,
            // The heap array is not immortal. Its immutable process root guarantees this bounded
            // projection, and the code generator must still run the exact global initializer.
            entryValues = mapOf(root to ArcOwnership.Guaranteed),
            entryInitializedStorage = emptySet(),
            blocks = linkedMapOf(
                entry to ArcBasicBlock(
                    entry,
                    listOf(
                        ArcOperation.Borrow(root, suspended, ArcBorrowKind.Projection),
                        ArcOperation.Use(suspended, ArcPlanLocation("identity comparison")),
                        ArcOperation.EndBorrow(suspended),
                    ),
                    ArcTerminator.Return(),
                ),
            ),
        )
    }
}

internal class ArcCoroutineSuspendedBorrowConsumptionLedger<T : Any>(
    selections: Collection<ArcCoroutineSuspendedBorrowSelection<T>>,
) {
    private val pending = selections.associateByTo(
        IdentityHashMap<T, ArcCoroutineSuspendedBorrowSelection<T>>()
    ) { it.candidate.call }

    fun consume(call: T, identities: List<T>) {
        val selection = pending.remove(call)
            ?: error("unselected or duplicate COROUTINE_SUSPENDED scoped borrow")
        val expected = selection.candidate.exactIdentityBindings
        check(identities.size == expected.size && identities.indices.all { identities[it] === expected[it] }) {
            "COROUTINE_SUSPENDED scoped-borrow identity inventory drifted"
        }
    }

    fun verifyComplete() {
        check(pending.isEmpty()) { "selected COROUTINE_SUSPENDED scoped borrow was not emitted" }
    }
}

private fun ArcCoroutineSuspendedBorrowMode.isProductionMode(): Boolean =
    arcEnabled && linuxX64 && finalBinary && optimizationsEnabled && debugInfoDisabled &&
            diagnosticsDisabled && sanitizerDisabled && coverageDisabled
