/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.KonanFqNames
import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.backend.konan.descriptors.isBuiltInOperator
import org.jetbrains.kotlin.backend.konan.llvm.Lifetime
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrSuspendableExpression
import org.jetbrains.kotlin.ir.expressions.IrSuspensionPoint
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.isElseBranch
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isOverridable
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Exact IR identities authorized to share one owning stack slot across a reference-valued `if`.
 *
 * The abstract SSA values are retained as proof only. Codegen is keyed exclusively by [variable]
 * and checks [initializer] by object identity before consuming the plan.
 */
internal data class ArcJoinedReferenceSlotPlan(
    val variable: IrVariable,
    val initializer: IrWhen,
    val armProducers: List<IrCall>,
    val ownershipWeb: ArcOwnershipSSAWeb,
    val joinedIdentity: ArcRCIdentity,
)

/** Exact direct calls whose result is proven to be the already-rooted dispatch receiver. */
internal data class ArcReturnedReceiverBorrowEligibility(
    val arcEnabled: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val exactDispatchReceiverUse: Boolean,
    val directNonExternalNonVirtualCall: Boolean,
    val nonSuspendReferenceResult: Boolean,
    val everyNormalReturnIsReceiver: Boolean,
    val noTryOrNestedFunctionAmbiguity: Boolean,
)

internal fun ArcReturnedReceiverBorrowEligibility.isAuthorized(): Boolean =
    arcEnabled && optimizationsEnabled && debugInfoDisabled && exactDispatchReceiverUse &&
            directNonExternalNonVirtualCall && nonSuspendReferenceResult &&
            everyNormalReturnIsReceiver && noTryOrNestedFunctionAmbiguity

/** Codegen-side authorization for reusing an existing receiver slot. */
internal data class ArcReturnedReceiverSlotReuseEligibility(
    val arcEnabled: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val exactCallIdentitySelected: Boolean,
    val noRequestedResultSlot: Boolean,
    val uniqueCurrentBlockOwningSlot: Boolean,
    val pointerIdenticalReceiverFact: Boolean,
)

internal fun ArcReturnedReceiverSlotReuseEligibility.isAuthorized(): Boolean =
    arcEnabled && optimizationsEnabled && debugInfoDisabled && exactCallIdentitySelected &&
            noRequestedResultSlot && uniqueCurrentBlockOwningSlot && pointerIdenticalReceiverFact

/**
 * Authorization for an exact returned-receiver call whose expression value is discarded.
 *
 * This is deliberately a semantic summary only. In particular, it does not authorize codegen to
 * ignore an already requested result slot. A later adapter must bind [exactDiscardedBlockStatement]
 * back to that exact anonymous slot before it may change the object-result ABI traffic.
 */
internal data class ArcDiscardedReturnedReceiverEligibility(
    val arcEnabled: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val exactDiscardedBlockStatement: Boolean,
    val exactStableStrongReceiver: Boolean,
    val directFinalNonExternalCall: Boolean,
    val nonSuspendReferenceResult: Boolean,
    val everyNormalReturnIsReceiver: Boolean,
    val noTrySuspendBranchOrNestedFunctionAmbiguity: Boolean,
    val weakOrUnownedShapeAbsent: Boolean,
)

internal fun ArcDiscardedReturnedReceiverEligibility.isAuthorized(): Boolean =
    arcEnabled && optimizationsEnabled && debugInfoDisabled && exactDiscardedBlockStatement &&
            exactStableStrongReceiver && directFinalNonExternalCall && nonSuspendReferenceResult &&
            everyNormalReturnIsReceiver && noTrySuspendBranchOrNestedFunctionAmbiguity &&
            weakOrUnownedShapeAbsent

/** Exact calls that may eventually share one seed, grouped by block and receiver identity. */
internal data class ArcDiscardedReturnedReceiverGroup(
    val block: IrElement,
    val receiver: org.jetbrains.kotlin.ir.declarations.IrValueDeclaration,
    val calls: List<IrCall>,
)

/** Small identity-only grouping model shared by the IR selector and adversarial unit tests. */
internal data class ArcPointerIdentityCandidate<T>(
    val block: Any,
    val receiver: Any,
    val value: T,
    val segment: Any = block,
)

internal data class ArcPointerIdentityGroup<T>(
    val block: Any,
    val receiver: Any,
    val values: List<T>,
)

internal fun <T> groupArcPointerIdenticalCandidates(
    candidates: List<ArcPointerIdentityCandidate<T>>,
): List<ArcPointerIdentityGroup<T>> {
    val groups = mutableListOf<ArcPointerIdentityBuildingGroup<T>>()
    candidates.forEach { candidate ->
        val index = groups.indexOfFirst {
            it.block === candidate.block && it.receiver === candidate.receiver &&
                    it.segment === candidate.segment
        }
        if (index < 0) {
            groups += ArcPointerIdentityBuildingGroup(
                candidate.block, candidate.receiver, candidate.segment, mutableListOf(candidate.value)
            )
        } else {
            groups[index].values += candidate.value
        }
    }
    return groups.map { group ->
        ArcPointerIdentityGroup(group.block, group.receiver, group.values.toList())
    }
}

private data class ArcPointerIdentityBuildingGroup<T>(
    val block: Any,
    val receiver: Any,
    val segment: Any,
    val values: MutableList<T>,
)

internal enum class ArcDiscardedReturnedReceiverBarrierKind {
    Try,
    When,
    Loop,
    Suspend,
    ForeignOrUnknownEffect,
    ReceiverWrite,
    Return,
    Throw,
    NestedDeclaration,
    OtherStatement,
}

internal sealed interface ArcDiscardedReturnedReceiverStructuralEvent<out T> {
    data class Candidate<T>(val receiver: Any, val value: T) : ArcDiscardedReturnedReceiverStructuralEvent<T>
    data class Barrier(val kind: ArcDiscardedReturnedReceiverBarrierKind) :
        ArcDiscardedReturnedReceiverStructuralEvent<Nothing>
}

/** Build maximal linear groups, starting a new opaque segment after every barrier. */
internal fun <T> groupArcDiscardedReturnedReceiverEvents(
    block: Any,
    events: List<ArcDiscardedReturnedReceiverStructuralEvent<T>>,
): List<ArcPointerIdentityGroup<T>> {
    var segment: Any = Any()
    val candidates = mutableListOf<ArcPointerIdentityCandidate<T>>()
    events.forEach { event ->
        when (event) {
            is ArcDiscardedReturnedReceiverStructuralEvent.Candidate ->
                candidates += ArcPointerIdentityCandidate(block, event.receiver, event.value, segment)
            is ArcDiscardedReturnedReceiverStructuralEvent.Barrier -> segment = Any()
        }
    }
    return groupArcPointerIdenticalCandidates(candidates)
}

/**
 * Select only calls that are direct statements in one exact block/body and whose values cannot be
 * observed. Calls used as returns, initializers, arguments, branch values, or final block values
 * never appear in [discardedStatements]. Calls below try/suspend/when boundaries are not visited.
 */
internal fun selectVerifiedDiscardedReturnedReceiverGroups(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
): List<ArcDiscardedReturnedReceiverGroup> {
    if (generationState.context.memoryModel != MemoryModel.ARC ||
        !generationState.context.config.optimizationsEnabled ||
        generationState.context.shouldContainDebugInfo() || function.isArcSuspendLike()
    ) return emptyList()

    val groups = mutableListOf<ArcDiscardedReturnedReceiverGroup>()

    fun classify(expression: IrExpression): Pair<org.jetbrains.kotlin.ir.declarations.IrValueDeclaration, IrCall>? {
        val call = expression as? IrCall ?: return null
        val receiverRead = call.dispatchReceiver as? IrGetValue ?: return null
        val receiver = receiverRead.symbol.owner
        val callee = call.symbol.owner as? IrSimpleFunction ?: return null
        val proof = callee.proveExactReturnedDispatchReceiver()
        val eligibility = ArcDiscardedReturnedReceiverEligibility(
            arcEnabled = true,
            optimizationsEnabled = true,
            debugInfoDisabled = true,
            exactDiscardedBlockStatement = true,
            exactStableStrongReceiver = receiver.type.binaryTypeIsReference() &&
                    (receiver !is IrVariable || !receiver.isVar),
            directFinalNonExternalCall = !callee.isExternal && !callee.isBuiltInOperator &&
                    !callee.isOverridable,
            nonSuspendReferenceResult = !callee.isArcSuspendLike() &&
                    callee.returnType.binaryTypeIsReference() && !callee.returnType.isUnit() &&
                    !callee.returnType.isNothing(),
            everyNormalReturnIsReceiver = proof.first,
            noTrySuspendBranchOrNestedFunctionAmbiguity = proof.second,
            weakOrUnownedShapeAbsent = !receiver.hasAnnotation(KonanFqNames.arcWeak) &&
                    !receiver.hasAnnotation(KonanFqNames.arcUnowned) &&
                    call.symbol != generationState.context.ir.symbols.arcWeakReferenceLoad &&
                    call.symbol != generationState.context.ir.symbols.arcUnownedReferenceLoad,
        )
        return if (eligibility.isAuthorized()) receiver to call else null
    }

    fun barrierKind(statement: IrElement): ArcDiscardedReturnedReceiverBarrierKind = when (statement) {
        is IrTry -> ArcDiscardedReturnedReceiverBarrierKind.Try
        is IrWhen -> ArcDiscardedReturnedReceiverBarrierKind.When
        is IrLoop -> ArcDiscardedReturnedReceiverBarrierKind.Loop
        is IrSuspendableExpression, is IrSuspensionPoint -> ArcDiscardedReturnedReceiverBarrierKind.Suspend
        is IrSetValue -> ArcDiscardedReturnedReceiverBarrierKind.ReceiverWrite
        is IrReturn -> ArcDiscardedReturnedReceiverBarrierKind.Return
        is IrThrow -> ArcDiscardedReturnedReceiverBarrierKind.Throw
        is IrDeclaration -> ArcDiscardedReturnedReceiverBarrierKind.NestedDeclaration
        is IrCall -> ArcDiscardedReturnedReceiverBarrierKind.ForeignOrUnknownEffect
        else -> ArcDiscardedReturnedReceiverBarrierKind.OtherStatement
    }

    fun scanLinearStatements(block: IrElement, statements: List<IrElement>, discardLast: Boolean) {
        val events = statements.mapIndexed { index, statement ->
            val mayBeDiscarded = discardLast || index != statements.lastIndex
            val classified = (statement as? IrExpression)?.takeIf { mayBeDiscarded }?.let(::classify)
            if (classified != null) {
                ArcDiscardedReturnedReceiverStructuralEvent.Candidate(classified.first, classified.second)
            } else {
                ArcDiscardedReturnedReceiverStructuralEvent.Barrier(barrierKind(statement))
            }
        }
        groupArcDiscardedReturnedReceiverEvents(block, events).forEach { group ->
            groups += ArcDiscardedReturnedReceiverGroup(
                block = block,
                receiver = group.receiver as org.jetbrains.kotlin.ir.declarations.IrValueDeclaration,
                calls = group.values,
            )
        }
    }

    function.body?.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: org.jetbrains.kotlin.ir.declarations.IrFunction) = Unit

        // These declarations own another object/frame lifetime. Never collect through them.
        override fun visitDeclaration(declaration: IrDeclarationBase) = Unit
        override fun visitClass(declaration: IrClass) = Unit
        override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer) = Unit
        override fun visitField(declaration: IrField) = Unit
        override fun visitVariable(declaration: IrVariable) = Unit

        // Direct statement calls were classified by their containing block. Never recurse into
        // argument/receiver expressions, returns, throws, or writes and accidentally classify a
        // value owned by a different expression boundary.
        override fun visitCall(expression: IrCall) = Unit
        override fun visitReturn(expression: IrReturn) = Unit
        override fun visitThrow(expression: IrThrow) = Unit
        override fun visitSetValue(expression: IrSetValue) = Unit
        override fun visitSetField(expression: IrSetField) = Unit

        override fun visitBlockBody(body: IrBlockBody) {
            scanLinearStatements(body, body.statements, discardLast = true)
            body.acceptChildrenVoid(this)
        }

        override fun visitContainerExpression(expression: IrContainerExpression) {
            scanLinearStatements(expression, expression.statements, discardLast = false)
            expression.acceptChildrenVoid(this)
        }

        // A direct statement nested below any of these boundaries is not a linear discarded value.
        override fun visitTry(aTry: IrTry) = Unit
        override fun visitWhen(expression: IrWhen) = Unit
        override fun visitLoop(loop: IrLoop) = Unit
        override fun visitSuspendableExpression(expression: IrSuspendableExpression) = Unit
        override fun visitSuspensionPoint(expression: IrSuspensionPoint) = Unit
    })

    return groups
}

/**
 * Select direct fluent calls whose complete callee body returns the dispatch receiver identity.
 * Codegen separately requires a unique existing receiver slot and no requested result slot, so
 * escaped/guaranteed receiver shapes fail closed even when this semantic summary is available.
 */
internal fun selectVerifiedReturnedReceiverBorrowCalls(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
): Set<IrCall> {
    if (generationState.context.memoryModel != MemoryModel.ARC ||
        !generationState.context.config.optimizationsEnabled ||
        generationState.context.shouldContainDebugInfo() || function.isArcSuspendLike()
    ) return emptySet()
    val selected = Collections.newSetFromMap(IdentityHashMap<IrCall, Boolean>())
    function.body?.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: org.jetbrains.kotlin.ir.declarations.IrFunction) = Unit

        // Keep the first slice out of local catch/finally CFGs. Their ownership edges are modeled
        // separately and must not inherit a linear immediate-receiver fact accidentally.
        override fun visitTry(aTry: IrTry) = Unit

        override fun visitCall(expression: IrCall) {
            expression.takeIf { it.dispatchReceiver != null }?.let { candidate ->
                val callee = candidate.symbol.owner as? IrSimpleFunction
                val proof = callee?.proveExactReturnedDispatchReceiver()
                val eligibility = ArcReturnedReceiverBorrowEligibility(
                    arcEnabled = true,
                    optimizationsEnabled = true,
                    debugInfoDisabled = true,
                    exactDispatchReceiverUse = true,
                    directNonExternalNonVirtualCall = callee != null && !callee.isExternal &&
                            !callee.isBuiltInOperator && (!callee.isOverridable || candidate.superQualifierSymbol != null),
                    nonSuspendReferenceResult = callee != null && !callee.isArcSuspendLike() &&
                            callee.returnType.binaryTypeIsReference() && !callee.returnType.isUnit() &&
                            !callee.returnType.isNothing(),
                    everyNormalReturnIsReceiver = proof?.first == true,
                    noTryOrNestedFunctionAmbiguity = proof?.second == true,
                )
                if (eligibility.isAuthorized()) {
                    selected += candidate
                }
            }
            expression.acceptChildrenVoid(this)
        }
    })
    return selected
}

/** Pair of (all normal returns are receiver, no try/nested-function ambiguity). */
private fun IrSimpleFunction.proveExactReturnedDispatchReceiver(): Pair<Boolean, Boolean> {
    val receiver = dispatchReceiverParameter ?: return false to false
    val body = body as? IrBlockBody ?: return false to false
    val terminal = body.statements.lastOrNull() as? IrReturn ?: return false to false
    if (terminal.returnTargetSymbol != symbol || !terminal.value.isExactReceiverRead(receiver)) return false to false
    var sawReturn = false
    var validReturns = true
    var ambiguity = false
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: org.jetbrains.kotlin.ir.declarations.IrFunction) {
            if (declaration !== this@proveExactReturnedDispatchReceiver) ambiguity = true
        }

        override fun visitTry(aTry: IrTry) {
            ambiguity = true
        }

        override fun visitWhen(expression: IrWhen) {
            ambiguity = true
        }

        override fun visitSuspendableExpression(expression: IrSuspendableExpression) {
            ambiguity = true
        }

        override fun visitSuspensionPoint(expression: IrSuspensionPoint) {
            ambiguity = true
        }

        override fun visitReturn(expression: IrReturn) {
            if (expression.returnTargetSymbol == symbol) {
                sawReturn = true
                if (!expression.value.isExactReceiverRead(receiver)) validReturns = false
            }
            expression.acceptChildrenVoid(this)
        }
    })
    return (sawReturn && validReturns) to !ambiguity
}

private fun IrExpression.isExactReceiverRead(receiver: org.jetbrains.kotlin.ir.declarations.IrValueParameter): Boolean = when (this) {
    is IrGetValue -> symbol == receiver.symbol
    is IrTypeOperatorCall -> operator == IrTypeOperator.IMPLICIT_CAST && argument.isExactReceiverRead(receiver)
    else -> false
}

/** A deliberately redundant authorization boundary for the first real ownership-SSA adapter. */
internal data class ArcCanonicalReferenceJoinEligibility(
    val arcEnabled: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val nonSuspendUnitFunction: Boolean,
    val immutableStrongReferenceLocal: Boolean,
    val strongOwnershipAnnotationsAbsent: Boolean,
    val exactTwoArmIf: Boolean,
    val simpleCondition: Boolean,
    val directOwnedArmProducers: Boolean,
    val exactLinearBorrowUse: Boolean,
    val noOtherUsesOrSuffixEffects: Boolean,
)

internal fun ArcCanonicalReferenceJoinEligibility.isAuthorized(): Boolean =
    arcEnabled && optimizationsEnabled && debugInfoDisabled && nonSuspendUnitFunction &&
            immutableStrongReferenceLocal && exactTwoArmIf && simpleCondition &&
            strongOwnershipAnnotationsAbsent &&
            directOwnedArmProducers && exactLinearBorrowUse && noOtherUsesOrSuffixEffects

/**
 * Adapt only `val joined = if (flag) directOwnedCall() else directOwnedCall(); consume(joined)`.
 * Returnable blocks, constructors, mixed ownership, additional uses, and non-linear suffixes remain
 * on the existing codegen path until their concrete CFG and cleanup identities are modeled.
 */
internal fun selectVerifiedJoinedReferenceSlots(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
    lifetimes: Map<IrElement, Lifetime>,
): Map<IrVariable, ArcJoinedReferenceSlotPlan> {
    val body = function.body as? IrBlockBody ?: return emptyMap()
    if (generationState.context.memoryModel != MemoryModel.ARC ||
        !generationState.context.config.optimizationsEnabled ||
        generationState.context.shouldContainDebugInfo() ||
        function.isArcSuspendLike() || !function.returnType.isUnit()
    ) return emptyMap()

    val result = linkedMapOf<IrVariable, ArcJoinedReferenceSlotPlan>()
    body.statements.forEachIndexed { index, statement ->
        val variable = statement as? IrVariable ?: return@forEachIndexed
        val initializer = variable.initializer as? IrWhen ?: return@forEachIndexed
        val consumerStatement = body.statements.getOrNull(index + 1) ?: return@forEachIndexed
        val consumer = when (consumerStatement) {
            is IrCall -> consumerStatement
            is IrReturn -> (consumerStatement.value as? IrCall)
                ?.takeIf { consumerStatement.returnTargetSymbol == function.symbol }
            else -> null
        } ?: return@forEachIndexed
        val remaining = body.statements.drop(index + 2)
        val uses = collectExactReads(function, variable)
        val consumerUse = uses.singleOrNull()
        val argumentIndex = consumerUse?.let { use ->
            (0 until consumer.valueArgumentsCount).singleOrNull { consumer.getValueArgument(it) === use }
        }
        val consumerParameter = argumentIndex?.let { consumer.symbol.owner.valueParameters.getOrNull(it) }
        val arms = initializer.branches.mapNotNull { it.result as? IrCall }

        val eligibility = ArcCanonicalReferenceJoinEligibility(
            arcEnabled = true,
            optimizationsEnabled = true,
            debugInfoDisabled = true,
            nonSuspendUnitFunction = true,
            immutableStrongReferenceLocal = !variable.isVar && variable.type.binaryTypeIsReference(),
            strongOwnershipAnnotationsAbsent = !variable.hasAnnotation(KonanFqNames.arcWeak) &&
                    !variable.hasAnnotation(KonanFqNames.arcUnowned),
            exactTwoArmIf = initializer.origin === IrStatementOrigin.IF &&
                    initializer.type.binaryTypeIsReference() && initializer.branches.size == 2 &&
                    !isElseBranch(initializer.branches[0]) && isElseBranch(initializer.branches[1]),
            simpleCondition = initializer.branches.firstOrNull()?.condition.isSimpleArcJoinCondition(),
            directOwnedArmProducers = arms.size == 2 && arms.all {
                it.isDirectOwnedArcJoinProducer(generationState, lifetimes)
            },
            exactLinearBorrowUse = consumerUse != null && argumentIndex != null &&
                    consumerParameter?.type?.binaryTypeIsReference() == true &&
                    consumer.isDirectArcJoinConsumer(),
            noOtherUsesOrSuffixEffects = uses.size == 1 && remaining.all {
                it is IrReturn && it.returnTargetSymbol == function.symbol && it.value.type.isUnit()
            },
        )
        if (!eligibility.isAuthorized()) return@forEachIndexed

        buildVerifiedArcJoinPlan(variable, initializer, arms)?.let { result[variable] = it }
    }
    return result
}

private fun buildVerifiedArcJoinPlan(
    variable: IrVariable,
    initializer: IrWhen,
    arms: List<IrCall>,
): ArcJoinedReferenceSlotPlan? {
    val header = ArcBlockId("header")
    val thenBlock = ArcBlockId("then")
    val elseBlock = ArcBlockId("else")
    val merge = ArcBlockId("merge")
    val normalExit = ArcBlockId("normalExit")
    val unwindExit = ArcBlockId("unwindExit")
    val thenValue = ArcSSAValue("thenResult")
    val elseValue = ArcSSAValue("elseResult")
    val joinedValue = ArcSSAValue("joined")
    val localValue = ArcSSAValue("local")
    val exceptionalEdge = ArcSSAEdge(merge, unwindExit, ArcSSAEdgeKind.Exceptional)
    val input = ArcOwnershipSSAInput(
        entry = header,
        blocks = linkedMapOf(
            header to ArcSSABlock(header, emptyList()),
            thenBlock to ArcSSABlock(thenBlock, listOf(
                ArcSSAOperation.Introduce(thenValue, ArcOwnership.Owned, location = arms[0].arcLocation("owned then result")),
            )),
            elseBlock to ArcSSABlock(elseBlock, listOf(
                ArcSSAOperation.Introduce(elseValue, ArcOwnership.Owned, location = arms[1].arcLocation("owned else result")),
            )),
            merge to ArcSSABlock(merge, listOf(
                ArcSSAOperation.Join(
                    joinedValue,
                    linkedMapOf(thenBlock to thenValue, elseBlock to elseValue),
                    initializer.arcLocation("reference when join"),
                ),
                ArcSSAOperation.Forward(joinedValue, localValue, variable.arcLocation("joined local")),
                ArcSSAOperation.Use(
                    localValue,
                    ArcSSAUseKind.Borrow,
                    mayThrow = true,
                    location = variable.arcLocation("linear consumer"),
                ),
            )),
            normalExit to ArcSSABlock(normalExit, emptyList()),
            unwindExit to ArcSSABlock(unwindExit, emptyList()),
        ),
        edges = setOf(
            ArcSSAEdge(header, thenBlock), ArcSSAEdge(header, elseBlock),
            ArcSSAEdge(thenBlock, merge), ArcSSAEdge(elseBlock, merge),
            ArcSSAEdge(merge, normalExit), exceptionalEdge,
        ),
    )
    val ownership = ArcOwnershipSSAAnalysis.analyze(input)
    if (ownership.rejected.isNotEmpty() || ownership.accepted.size != 1) return null
    val web = ownership.accepted.single()
    if (web.ownership != ArcOwnership.Owned || web.anchorDependencies.isNotEmpty() ||
        web.destroyPlacements != setOf(
            ArcSSADestroyPlacement.BeforeExit(normalExit),
            ArcSSADestroyPlacement.OnEdge(exceptionalEdge),
        )
    ) return null

    val identities = ArcRCIdentityAnalysis.analyze(ArcRCIdentityInput(input, listOf(web)))
    if (identities.issues.isNotEmpty()) return null
    val joinedIdentity = identities.identity(localValue) ?: return null
    if (joinedIdentity.provenanceRoots != setOf(thenValue, elseValue) ||
        joinedIdentity.anchorRoots.isNotEmpty() || joinedIdentity.ownershipWeb !== web ||
        identities.identity(thenValue)?.singleRoot != thenValue ||
        identities.identity(elseValue)?.singleRoot != elseValue
    ) return null
    return ArcJoinedReferenceSlotPlan(variable, initializer, arms, web, joinedIdentity)
}

private fun collectExactReads(function: IrSimpleFunction, variable: IrVariable): Set<IrGetValue> =
    Collections.newSetFromMap(IdentityHashMap<IrGetValue, Boolean>()).apply {
        function.body?.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitGetValue(expression: IrGetValue) {
                if (expression.symbol == variable.symbol) add(expression)
                expression.acceptChildrenVoid(this)
            }
        })
    }

private fun IrExpression?.isSimpleArcJoinCondition(): Boolean =
    this != null && type.isBoolean() && (this is IrGetValue || this is IrConst<*> && value is Boolean)

private fun IrCall.isDirectOwnedArcJoinProducer(
    generationState: NativeGenerationState,
    lifetimes: Map<IrElement, Lifetime>,
): Boolean {
    val callee = symbol.owner as? IrSimpleFunction ?: return false
    return !callee.isExternal && !callee.isBuiltInOperator && !callee.isArcSuspendLike() &&
            (!callee.isOverridable || superQualifierSymbol != null) &&
            callee.returnType.binaryTypeIsReference() && !callee.returnType.isUnit() &&
            !callee.returnType.isNothing() &&
            classifyArcProducedReference(isPermanent = false, lifetime = lifetimes[this]) == ArcOwnership.Owned &&
            symbol != generationState.context.ir.symbols.arcWeakReferenceLoad &&
            symbol != generationState.context.ir.symbols.arcUnownedReferenceLoad
}

private fun IrCall.isDirectArcJoinConsumer(): Boolean {
    val callee = symbol.owner as? IrSimpleFunction ?: return false
    return dispatchReceiver == null && extensionReceiver == null && !callee.isExternal &&
            !callee.isBuiltInOperator && !callee.isArcSuspendLike() &&
            (!callee.isOverridable || superQualifierSymbol != null) && callee.returnType.isUnit()
}

private fun IrElement.arcLocation(description: String) = ArcPlanLocation(description, startOffset)
