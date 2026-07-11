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
    LOAD_FROM_UNINITIALIZED_STORAGE,
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
    private data class ValueState(val ownership: ArcOwnership, val live: Boolean)

    private data class PathState(
        val values: Map<ArcValue, ValueState>,
        val initializedStorage: Set<ArcStorage>,
    )

    fun verify(plan: ArcFunctionPlan): ArcOwnershipVerificationResult {
        val violations = mutableListOf<ArcOwnershipViolation>()
        val entryBlock = plan.blocks[plan.entry]
        if (entryBlock == null) {
            violations += violation(plan, ArcOwnershipViolationCode.MISSING_ENTRY_BLOCK, null, null, null, "missing ${plan.entry}")
            return ArcOwnershipVerificationResult.Failure(violations)
        }

        val entryState = PathState(
            plan.entryValues.mapValues { ValueState(it.value, live = true) },
            plan.entryInitializedStorage,
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
                        incoming[successor] = state
                        worklist += successor
                    }
                    previous != state -> violations += violation(
                        plan, ArcOwnershipViolationCode.INCOMPATIBLE_PATH_STATES, successor, null, null,
                        "incoming ownership states differ: $previous versus $state"
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

        fun define(value: ArcValue, ownership: ArcOwnership) {
            if (value in values) {
                violations += violation(plan, ArcOwnershipViolationCode.VALUE_ALREADY_DEFINED, block, index, operation.location, "$value")
            } else {
                values[value] = ValueState(ownership, live = true)
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
                if (value.ownership != ArcOwnership.Owned) {
                    violations += violation(
                        plan, ArcOwnershipViolationCode.DESTROY_OF_NON_OWNED, block, index, operation.location,
                        "$operation destroys ${value.ownership}"
                    )
                } else {
                    values[operation.value] = value.copy(live = false)
                }
            }
            is ArcOperation.Borrow -> live(operation.source)?.let { define(operation.result, ArcOwnership.Guaranteed) }
            is ArcOperation.StrongStore -> live(operation.value)?.let { storage += operation.storage }
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
        return PathState(values, storage)
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
        state.values.forEach { (value, valueState) ->
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
