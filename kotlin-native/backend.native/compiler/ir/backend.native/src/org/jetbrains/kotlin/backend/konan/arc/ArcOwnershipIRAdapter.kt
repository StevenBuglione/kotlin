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
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
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
