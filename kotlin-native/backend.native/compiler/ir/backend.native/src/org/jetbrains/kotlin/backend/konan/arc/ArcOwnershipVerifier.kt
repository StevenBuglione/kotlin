/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

internal enum class ArcOwnershipViolationCode {
    MISSING_ENTRY_BLOCK,
    MISSING_SUCCESSOR,
    VALUE_ALREADY_DEFINED,
    USE_AFTER_DESTROY,
    UNKNOWN_VALUE,
    DESTROY_OF_NON_OWNED,
    END_BORROW_OF_NON_BORROWED,
    OWNER_ENDED_WITH_LIVE_BORROW,
    LIVE_BORROW_AT_EXIT,
    LOAD_FROM_UNINITIALIZED_STORAGE,
    STRONG_REPLACE_OF_UNINITIALIZED_STORAGE,
    STRONG_REPLACE_REQUIRES_OWNED_OLD_OWNER,
    STRONG_REPLACE_REQUIRES_PROJECTED_BORROW,
    STRONG_REPLACE_BORROW_OWNER_MISMATCH,
    ROOTED_PROJECTION_REQUIRES_LIVE_ANCHOR,
    ROOTED_PROJECTION_STORAGE_ALREADY_ACTIVE,
    ROOTED_PROJECTION_NOT_ACTIVE,
    ROOTED_PROJECTION_ANCHOR_MISMATCH,
    ROOTED_PROJECTION_ANCHOR_ENDED,
    LIVE_ROOTED_PROJECTION_AT_EXIT,
    INCOMPATIBLE_PATH_STATES,
    LEAKED_OWNED_VALUE,
    RETURN_OF_NON_OWNED_VALUE,
}

internal data class ArcOwnershipViolation(
    val code: ArcOwnershipViolationCode,
    val functionName: String,
    val block: ArcBlockId?,
    val operationIndex: Int?,
    val location: ArcPlanLocation?,
    val detail: String,
) {
    fun render(): String = buildString {
        append("[").append(code).append("] ").append(functionName)
        block?.let { append(" block ").append(it) }
        operationIndex?.let { append(" operation ").append(it) }
        location?.let { append(" (").append(it.description).append(")") }
        append(": ").append(detail)
    }
}

internal sealed class ArcOwnershipVerificationResult {
    object Success : ArcOwnershipVerificationResult()
    data class Failure(val violations: List<ArcOwnershipViolation>) : ArcOwnershipVerificationResult() {
        fun render(): String = violations.joinToString(separator = "\n") { it.render() }
    }
}

internal class ArcOwnershipVerificationException(
    val failure: ArcOwnershipVerificationResult.Failure,
) : IllegalStateException("ARC ownership verification failed:\n${failure.render()}")

internal object ArcOwnershipVerifier {
    private data class ValueState(
        val ownership: ArcOwnership,
        val live: Boolean,
        val borrowedFrom: ArcValue? = null,
        val borrowKind: ArcBorrowKind? = null,
    )

    private data class PathState(
        val values: Map<ArcValue, ValueState>,
        val initializedStorage: Set<ArcStorage>,
        val rootedProjectionStorage: Map<ArcStorage, ArcValue>,
    ) {
        /** Dead SSA values cannot affect any successor and must not poison otherwise compatible joins. */
        fun forSuccessor(): PathState = copy(values = values.filterValues { it.live })
    }

    fun verify(plan: ArcFunctionPlan): ArcOwnershipVerificationResult {
        val violations = mutableListOf<ArcOwnershipViolation>()
        val entryBlock = plan.blocks[plan.entry]
        if (entryBlock == null) {
            violations += violation(plan, ArcOwnershipViolationCode.MISSING_ENTRY_BLOCK, null, null, null, "missing ${plan.entry}")
            return ArcOwnershipVerificationResult.Failure(violations)
        }

        // Definition uniqueness is a function-wide SSA invariant. Checking it independently of
        // path traversal prevents disjoint branches (and unreachable blocks) from defining the
        // same ArcValue and then appearing compatible after dead-state normalization.
        val definedValues = plan.entryValues.keys.toMutableSet()
        plan.blocks.values.forEach { block ->
            block.operations.forEachIndexed { index, operation ->
                operation.definedResult()?.let { result ->
                    if (!definedValues.add(result)) {
                        violations += violation(
                            plan,
                            ArcOwnershipViolationCode.VALUE_ALREADY_DEFINED,
                            block.id,
                            index,
                            operation.location,
                            "$result is defined more than once in the function",
                        )
                    }
                }
            }
        }

        val entryState = PathState(
            plan.entryValues.mapValues { ValueState(it.value, live = true) },
            plan.entryInitializedStorage,
            emptyMap(),
        )
        val incoming = mutableMapOf(plan.entry to entryState)
        val worklist = ArrayDeque<ArcBlockId>().apply { add(plan.entry) }

        while (worklist.isNotEmpty()) {
            val blockId = worklist.removeFirst()
            val block = plan.blocks[blockId] ?: continue
            var state = incoming.getValue(blockId)
            block.operations.forEachIndexed { index, operation ->
                state = applyOperation(plan, blockId, index, operation, state, violations)
            }

            val successors = when (val terminator = block.terminator) {
                is ArcTerminator.Jump -> listOf(terminator.target)
                is ArcTerminator.Branch -> listOf(terminator.trueTarget, terminator.falseTarget)
                is ArcTerminator.Return -> {
                    checkExit(plan, blockId, block.operations.size, terminator.value, state, violations)
                    emptyList()
                }
                ArcTerminator.Throw -> {
                    checkExit(plan, blockId, block.operations.size, null, state, violations)
                    emptyList()
                }
                ArcTerminator.Unreachable -> emptyList()
            }

            val outgoingState = state.forSuccessor()

            successors.forEach { successor ->
                if (successor !in plan.blocks) {
                    violations += violation(
                        plan, ArcOwnershipViolationCode.MISSING_SUCCESSOR, blockId, block.operations.size, null,
                        "successor $successor does not exist"
                    )
                    return@forEach
                }
                val previous = incoming[successor]
                when {
                    previous == null -> {
                        incoming[successor] = outgoingState
                        worklist += successor
                    }
                    previous != outgoingState -> violations += violation(
                        plan, ArcOwnershipViolationCode.INCOMPATIBLE_PATH_STATES, successor, null, null,
                        "incoming ownership states differ: $previous versus $outgoingState"
                    )
                }
            }
        }

        return if (violations.isEmpty()) ArcOwnershipVerificationResult.Success
        else ArcOwnershipVerificationResult.Failure(violations.distinct())
    }

    fun verifyOrThrow(plan: ArcFunctionPlan) {
        val result = verify(plan)
        if (result is ArcOwnershipVerificationResult.Failure) throw ArcOwnershipVerificationException(result)
    }

    private fun applyOperation(
        plan: ArcFunctionPlan,
        block: ArcBlockId,
        index: Int,
        operation: ArcOperation,
        state: PathState,
        violations: MutableList<ArcOwnershipViolation>,
    ): PathState {
        val values = state.values.toMutableMap()
        val storage = state.initializedStorage.toMutableSet()
        val rootedProjectionStorage = state.rootedProjectionStorage.toMutableMap()

        fun define(
            value: ArcValue,
            ownership: ArcOwnership,
            borrowedFrom: ArcValue? = null,
            borrowKind: ArcBorrowKind? = null,
        ) {
            if (value in values) {
                violations += violation(plan, ArcOwnershipViolationCode.VALUE_ALREADY_DEFINED, block, index, operation.location, "$value")
            } else {
                values[value] = ValueState(
                    ownership,
                    live = true,
                    borrowedFrom = borrowedFrom,
                    borrowKind = borrowKind,
                )
            }
        }

        fun live(value: ArcValue): ValueState? {
            val valueState = values[value]
            when {
                valueState == null -> violations += violation(
                    plan, ArcOwnershipViolationCode.UNKNOWN_VALUE, block, index, operation.location, "$value"
                )
                !valueState.live -> violations += violation(
                    plan, ArcOwnershipViolationCode.USE_AFTER_DESTROY, block, index, operation.location, "$value"
                )
            }
            return valueState?.takeIf { it.live }
        }

        when (operation) {
            is ArcOperation.Define -> define(operation.result, operation.ownership)
            is ArcOperation.Copy -> live(operation.source)?.let { source ->
                define(operation.result, if (source.ownership == ArcOwnership.Immortal) ArcOwnership.Immortal else ArcOwnership.Owned)
            }
            is ArcOperation.Destroy -> live(operation.value)?.let { value ->
                if (operation.value in rootedProjectionStorage.values) {
                    violations += violation(
                        plan, ArcOwnershipViolationCode.ROOTED_PROJECTION_ANCHOR_ENDED,
                        block, index, operation.location,
                        "${operation.value} is destroyed while rooted projection storage remains active",
                    )
                }
                if (value.ownership != ArcOwnership.Owned) {
                    violations += violation(
                        plan, ArcOwnershipViolationCode.DESTROY_OF_NON_OWNED, block, index, operation.location,
                        "$operation destroys ${value.ownership}"
                    )
                } else {
                    if (values.any { (_, candidate) -> candidate.live && candidate.borrowedFrom == operation.value }) {
                        violations += violation(
                            plan, ArcOwnershipViolationCode.OWNER_ENDED_WITH_LIVE_BORROW,
                            block, index, operation.location,
                            "${operation.value} is destroyed while one of its borrows is live",
                        )
                    }
                    values[operation.value] = value.copy(live = false)
                }
            }
            is ArcOperation.Borrow -> live(operation.source)?.let {
                define(
                    operation.result,
                    ArcOwnership.Guaranteed,
                    borrowedFrom = operation.source,
                    borrowKind = operation.kind,
                )
            }
            is ArcOperation.EndBorrow -> live(operation.value)?.let { value ->
                if (operation.value in rootedProjectionStorage.values) {
                    violations += violation(
                        plan, ArcOwnershipViolationCode.ROOTED_PROJECTION_ANCHOR_ENDED,
                        block, index, operation.location,
                        "${operation.value} ends while rooted projection storage remains active",
                    )
                }
                if (value.borrowedFrom == null) {
                    violations += violation(
                        plan, ArcOwnershipViolationCode.END_BORROW_OF_NON_BORROWED,
                        block, index, operation.location,
                        "${operation.value} was not produced by Borrow",
                    )
                } else {
                    if (values.any { (_, candidate) -> candidate.live && candidate.borrowedFrom == operation.value }) {
                        violations += violation(
                            plan, ArcOwnershipViolationCode.OWNER_ENDED_WITH_LIVE_BORROW,
                            block, index, operation.location,
                            "${operation.value} ends while a nested borrow is live",
                        )
                    }
                    values[operation.value] = value.copy(live = false)
                }
            }
            is ArcOperation.Use -> {
                live(operation.value)
            }
            is ArcOperation.BeginRootedProjection -> {
                val anchor = values[operation.anchor]
                val liveAnchor = anchor?.takeIf { it.live }
                if (liveAnchor == null) {
                    violations += violation(
                        plan, ArcOwnershipViolationCode.ROOTED_PROJECTION_REQUIRES_LIVE_ANCHOR,
                        block, index, operation.location,
                        "${operation.anchor} is not a live rooted projection anchor",
                    )
                }
                if (operation.storage in rootedProjectionStorage || operation.storage in storage) {
                    violations += violation(
                        plan, ArcOwnershipViolationCode.ROOTED_PROJECTION_STORAGE_ALREADY_ACTIVE,
                        block, index, operation.location,
                        "${operation.storage} is already active strong or rooted storage",
                    )
                } else if (liveAnchor != null) {
                    rootedProjectionStorage[operation.storage] = operation.anchor
                }
            }
            is ArcOperation.AdvanceRootedProjection -> {
                val activeAnchor = rootedProjectionStorage[operation.storage]
                when {
                    activeAnchor == null -> violations += violation(
                        plan, ArcOwnershipViolationCode.ROOTED_PROJECTION_NOT_ACTIVE,
                        block, index, operation.location,
                        "${operation.storage} has no active rooted projection",
                    )
                    activeAnchor != operation.anchor -> violations += violation(
                        plan, ArcOwnershipViolationCode.ROOTED_PROJECTION_ANCHOR_MISMATCH,
                        block, index, operation.location,
                        "${operation.storage} is rooted by $activeAnchor, not ${operation.anchor}",
                    )
                    values[operation.anchor]?.live != true -> violations += violation(
                        plan, ArcOwnershipViolationCode.ROOTED_PROJECTION_REQUIRES_LIVE_ANCHOR,
                        block, index, operation.location,
                        "${operation.anchor} is not a live rooted projection anchor",
                    )
                    else -> Unit // A loop backedge reaches the same abstract rooted-storage state.
                }
            }
            is ArcOperation.EndRootedProjection -> {
                val activeAnchor = rootedProjectionStorage[operation.storage]
                when {
                    activeAnchor == null -> violations += violation(
                        plan, ArcOwnershipViolationCode.ROOTED_PROJECTION_NOT_ACTIVE,
                        block, index, operation.location,
                        "${operation.storage} has no active rooted projection",
                    )
                    activeAnchor != operation.anchor -> violations += violation(
                        plan, ArcOwnershipViolationCode.ROOTED_PROJECTION_ANCHOR_MISMATCH,
                        block, index, operation.location,
                        "${operation.storage} is rooted by $activeAnchor, not ${operation.anchor}",
                    )
                    values[operation.anchor]?.live != true -> violations += violation(
                        plan, ArcOwnershipViolationCode.ROOTED_PROJECTION_REQUIRES_LIVE_ANCHOR,
                        block, index, operation.location,
                        "${operation.anchor} is not a live rooted projection anchor",
                    )
                    else -> rootedProjectionStorage.remove(operation.storage)
                }
            }
            is ArcOperation.StrongStore -> live(operation.value)?.let {
                if (operation.storage in rootedProjectionStorage) {
                    violations += violation(
                        plan, ArcOwnershipViolationCode.ROOTED_PROJECTION_STORAGE_ALREADY_ACTIVE,
                        block, index, operation.location,
                        "${operation.storage} is already active rooted projection storage",
                    )
                } else {
                    storage += operation.storage
                }
            }
            is ArcOperation.StrongReplace -> {
                val oldOwner = live(operation.oldOwner)
                val newBorrow = live(operation.newBorrow)
                var valid = oldOwner != null && newBorrow != null

                if (operation.storage !in storage) {
                    violations += violation(
                        plan,
                        ArcOwnershipViolationCode.STRONG_REPLACE_OF_UNINITIALIZED_STORAGE,
                        block,
                        index,
                        operation.location,
                        "${operation.storage} has no dominating strong store or entry initialization",
                    )
                    valid = false
                }
                if (oldOwner != null && oldOwner.ownership != ArcOwnership.Owned) {
                    violations += violation(
                        plan,
                        ArcOwnershipViolationCode.STRONG_REPLACE_REQUIRES_OWNED_OLD_OWNER,
                        block,
                        index,
                        operation.location,
                        "${operation.oldOwner} is ${oldOwner.ownership}, not an owned old value",
                    )
                    valid = false
                }
                if (newBorrow != null &&
                    (newBorrow.ownership != ArcOwnership.Guaranteed ||
                            newBorrow.borrowedFrom == null ||
                            newBorrow.borrowKind != ArcBorrowKind.Projection)
                ) {
                    violations += violation(
                        plan,
                        ArcOwnershipViolationCode.STRONG_REPLACE_REQUIRES_PROJECTED_BORROW,
                        block,
                        index,
                        operation.location,
                        "${operation.newBorrow} is not a live projected guaranteed borrow",
                    )
                    valid = false
                } else if (newBorrow != null && newBorrow.borrowedFrom != operation.oldOwner) {
                    violations += violation(
                        plan,
                        ArcOwnershipViolationCode.STRONG_REPLACE_BORROW_OWNER_MISMATCH,
                        block,
                        index,
                        operation.location,
                        "${operation.newBorrow} depends on ${newBorrow.borrowedFrom}, not ${operation.oldOwner}",
                    )
                    valid = false
                }

                if (oldOwner != null && values.any { (value, candidate) ->
                        value != operation.newBorrow && candidate.live &&
                                candidate.borrowedFrom == operation.oldOwner
                    }
                ) {
                    violations += violation(
                        plan,
                        ArcOwnershipViolationCode.OWNER_ENDED_WITH_LIVE_BORROW,
                        block,
                        index,
                        operation.location,
                        "${operation.oldOwner} has another live borrow during strong replacement",
                    )
                    valid = false
                }
                if (newBorrow != null && values.any { (_, candidate) ->
                        candidate.live && candidate.borrowedFrom == operation.newBorrow
                    }
                ) {
                    violations += violation(
                        plan,
                        ArcOwnershipViolationCode.OWNER_ENDED_WITH_LIVE_BORROW,
                        block,
                        index,
                        operation.location,
                        "${operation.newBorrow} has a nested live borrow during strong replacement",
                    )
                    valid = false
                }

                if (valid) {
                    if (operation.oldOwner in rootedProjectionStorage.values) {
                        violations += violation(
                            plan, ArcOwnershipViolationCode.ROOTED_PROJECTION_ANCHOR_ENDED,
                            block, index, operation.location,
                            "${operation.oldOwner} is consumed while rooted projection storage remains active",
                        )
                        valid = false
                    }
                }

                if (valid) {
                    // StrongReplace is one atomic ownership event: retain/publish the projected
                    // value first, then consume the old slot owner and end its projection borrow.
                    values[operation.newBorrow] = newBorrow!!.copy(live = false)
                    values[operation.oldOwner] = oldOwner!!.copy(live = false)
                }
            }
            is ArcOperation.StrongLoad -> {
                if (operation.storage !in storage) {
                    violations += violation(
                        plan, ArcOwnershipViolationCode.LOAD_FROM_UNINITIALIZED_STORAGE, block, index, operation.location,
                        "${operation.storage} has no dominating strong store or entry initialization"
                    )
                } else {
                    define(operation.result, ArcOwnership.Owned)
                }
            }
        }
        return PathState(values, storage, rootedProjectionStorage)
    }

    private fun checkExit(
        plan: ArcFunctionPlan,
        block: ArcBlockId,
        index: Int,
        returned: ArcValue?,
        state: PathState,
        violations: MutableList<ArcOwnershipViolation>,
    ) {
        if (returned != null) {
            val returnedState = state.values[returned]
            if (returnedState == null || !returnedState.live || returnedState.ownership == ArcOwnership.Guaranteed) {
                violations += violation(
                    plan, ArcOwnershipViolationCode.RETURN_OF_NON_OWNED_VALUE, block, index, null,
                    "$returned is not a live owned or immortal value"
                )
            }
        }
        state.rootedProjectionStorage.forEach { (storage, anchor) ->
            violations += violation(
                plan, ArcOwnershipViolationCode.LIVE_ROOTED_PROJECTION_AT_EXIT, block, index, null,
                "$storage rooted by $anchor remains active at exit",
            )
        }
        state.values.forEach { (value, valueState) ->
            if (valueState.live && valueState.borrowedFrom != null) {
                violations += violation(
                    plan, ArcOwnershipViolationCode.LIVE_BORROW_AT_EXIT, block, index, null,
                    "$value borrowed from ${valueState.borrowedFrom} remains live at exit",
                )
            }
            if (valueState.live && valueState.ownership == ArcOwnership.Owned && value != returned) {
                violations += violation(
                    plan, ArcOwnershipViolationCode.LEAKED_OWNED_VALUE, block, index, null,
                    "$value remains owned at exit"
                )
            }
        }
    }

    private fun violation(
        plan: ArcFunctionPlan,
        code: ArcOwnershipViolationCode,
        block: ArcBlockId?,
        operationIndex: Int?,
        location: ArcPlanLocation?,
        detail: String,
    ) = ArcOwnershipViolation(code, plan.functionName, block, operationIndex, location, detail)
}

private fun ArcOperation.definedResult(): ArcValue? = when (this) {
    is ArcOperation.Define -> result
    is ArcOperation.Copy -> result
    is ArcOperation.Borrow -> result
    is ArcOperation.StrongLoad -> result
    is ArcOperation.Destroy, is ArcOperation.EndBorrow, is ArcOperation.Use,
    is ArcOperation.BeginRootedProjection, is ArcOperation.AdvanceRootedProjection,
    is ArcOperation.EndRootedProjection, is ArcOperation.StrongStore,
    is ArcOperation.StrongReplace -> null
}
