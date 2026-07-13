/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import java.util.Collections
import java.util.IdentityHashMap

internal data class ArcBranchGuaranteedPhiCompilationMode(
    val arcEnabled: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val diagnosticsDisabled: Boolean,
    val nonSuspendFunction: Boolean,
    val nonExternalFunction: Boolean,
)

internal data class ArcBranchGuaranteedPhiParameter<T : Any>(
    val declarationBinding: T,
    val isReference: Boolean,
    val isNullable: Boolean,
)

internal data class ArcBranchGuaranteedPhiVariable<T : Any>(
    val declarationBinding: T,
    val nullInitializerBinding: T,
    val initializerIsExactNull: Boolean,
    val isMutable: Boolean,
    val isNullableReference: Boolean,
    val isStrong: Boolean,
)

internal enum class ArcBranchGuaranteedPhiArmKind { Then, Else }

internal data class ArcBranchGuaranteedPhiArm<T : Any>(
    val kind: ArcBranchGuaranteedPhiArmKind,
    val branchBinding: T,
    val storeBinding: T,
    val sourceReadBinding: T,
    val sourceParameter: ArcBranchGuaranteedPhiParameter<T>,
    val storeTargetsSelectedVariable: Boolean,
    val sourceReadTargetsParameter: Boolean,
    /** True only when this store is the complete branch result and directly reaches the merge. */
    val directToMerge: Boolean,
)

internal data class ArcBranchGuaranteedPhiDiamond<T : Any>(
    val expressionBinding: T,
    val conditionReadBinding: T,
    val conditionParameterBinding: T,
    val conditionIsBoolean: Boolean,
    val conditionIsDirectParameterRead: Boolean,
    val arms: List<ArcBranchGuaranteedPhiArm<T>>,
    val entryBranchesDirectlyToArms: Boolean,
    val hasSingleMerge: Boolean,
)

/** The first slice accepts only non-throwing reference identity equality after the merge. */
internal data class ArcBranchGuaranteedPhiTerminalUse<T : Any>(
    val exitBinding: T,
    val equalityBinding: T,
    val selectedReadBinding: T,
    val comparisonReadBinding: T,
    val comparisonParameterBinding: T,
    val selectedReadTargetsVariable: Boolean,
    val comparisonReadTargetsParameter: Boolean,
    val isSolePostMergeUse: Boolean,
    val isReferenceIdentityEquality: Boolean,
    val isNonThrowing: Boolean,
)

internal enum class ArcBranchGuaranteedPhiUnsupportedEffect {
    NestedExpression,
    AdditionalReferenceRead,
    AdditionalReferenceWrite,
    Capture,
    Call,
    Throw,
    Try,
    Loop,
    Suspension,
    Deinitialization,
    ForeignEffect,
    ExceptionalEdge,
    CriticalEdge,
    NonDirectControlFlow,
}

/**
 * A complete inventory produced by a future Kotlin IR walker. Each binding is the exact IR object
 * identity for the named role; [completeLoweredIRWalk] is the all-nodes-visited seal.
 */
internal data class ArcBranchGuaranteedPhiLoweredBody<T : Any>(
    val functionBinding: T,
    val mode: ArcBranchGuaranteedPhiCompilationMode,
    val variable: ArcBranchGuaranteedPhiVariable<T>,
    val diamond: ArcBranchGuaranteedPhiDiamond<T>,
    val terminalUse: ArcBranchGuaranteedPhiTerminalUse<T>,
    val unsupportedEffects: Set<ArcBranchGuaranteedPhiUnsupportedEffect> = emptySet(),
    val completeLoweredIRWalk: Boolean,
)

internal enum class ArcBranchGuaranteedPhiBindingRole {
    EntryBlock,
    ThenBlock,
    ElseBlock,
    MergeBlock,
    ExitBlock,
    ThenAnchor,
    ThenGuaranteedSource,
    ThenOwnedCopy,
    ElseAnchor,
    ElseGuaranteedSource,
    ElseOwnedCopy,
    JoinedPhi,
    TerminalUse,
}

/** Unique semantic-site identity retaining the exact lowered IR owner which authorized it. */
internal class ArcBranchGuaranteedPhiExactBinding<T : Any>(
    val owner: T,
    val role: ArcBranchGuaranteedPhiBindingRole,
)

internal data class ArcBranchGuaranteedPhiIRSelection<T : Any>(
    val body: ArcBranchGuaranteedPhiLoweredBody<T>,
    val plan: ArcSemanticPhiWebPlan,
    val emission: ArcSemanticPhiEmissionSelection<ArcBranchGuaranteedPhiExactBinding<T>>,
    val exactBindings: Map<ArcBranchGuaranteedPhiBindingRole, ArcBranchGuaranteedPhiExactBinding<T>>,
)

internal enum class ArcBranchGuaranteedPhiIRRejectionReason {
    UnsupportedCompilationMode,
    IncompleteLoweredIRWalk,
    DuplicateIRIdentity,
    InvalidVariable,
    InvalidCondition,
    InvalidArmBinding,
    NullableParameterAmbiguity,
    MissingTwoArmCoverage,
    NonDirectDiamond,
    InvalidTerminalUse,
    UnsupportedEffect,
    SemanticAnalysisRejected,
    UnexpectedSemanticPlan,
    EmissionAdapterRejected,
}

internal data class ArcBranchGuaranteedPhiIRRejection(
    val reason: ArcBranchGuaranteedPhiIRRejectionReason,
    val detail: String,
)

internal data class ArcBranchGuaranteedPhiIRAdapterResult<T : Any>(
    val selection: ArcBranchGuaranteedPhiIRSelection<T>?,
    val rejections: List<ArcBranchGuaranteedPhiIRRejection>,
)

/**
 * Proves the first non-suspend, multi-definition owned-to-guaranteed branch phi.
 *
 * This adapter does not mutate Kotlin IR or LLVM. It accepts only a direct two-arm diamond and
 * delegates ownership validity and emission-site authentication to the generalized SemanticARC
 * analysis. Any inventory uncertainty retains ordinary owning-slot codegen.
 */
internal object ArcBranchGuaranteedPhiIRAdapter {
    fun <T : Any> adapt(body: ArcBranchGuaranteedPhiLoweredBody<T>): ArcBranchGuaranteedPhiIRAdapterResult<T> {
        val rejections = mutableListOf<ArcBranchGuaranteedPhiIRRejection>()
        fun reject(reason: ArcBranchGuaranteedPhiIRRejectionReason, detail: String) {
            rejections += ArcBranchGuaranteedPhiIRRejection(reason, detail)
        }

        val mode = body.mode
        if (!mode.arcEnabled || !mode.optimizationsEnabled || !mode.debugInfoDisabled ||
            !mode.diagnosticsDisabled || !mode.nonSuspendFunction || !mode.nonExternalFunction
        ) reject(
            ArcBranchGuaranteedPhiIRRejectionReason.UnsupportedCompilationMode,
            "branch phi requires optimized non-debug ARC in a non-suspend, non-external function",
        )
        if (!body.completeLoweredIRWalk) reject(
            ArcBranchGuaranteedPhiIRRejectionReason.IncompleteLoweredIRWalk,
            "the lowered IR walk did not classify every nested node and effect",
        )
        if (!body.variable.initializerIsExactNull || !body.variable.isMutable ||
            !body.variable.isNullableReference || !body.variable.isStrong
        ) reject(
            ArcBranchGuaranteedPhiIRRejectionReason.InvalidVariable,
            "the selected local must be one strong mutable nullable reference initialized by null",
        )
        if (!body.diamond.conditionIsBoolean || !body.diamond.conditionIsDirectParameterRead) reject(
            ArcBranchGuaranteedPhiIRRejectionReason.InvalidCondition,
            "the condition must be one direct Boolean parameter read",
        )

        val armsByKind = body.diamond.arms.groupBy { it.kind }
        if (body.diamond.arms.size != 2 || armsByKind[ArcBranchGuaranteedPhiArmKind.Then]?.size != 1 ||
            armsByKind[ArcBranchGuaranteedPhiArmKind.Else]?.size != 1
        ) reject(
            ArcBranchGuaranteedPhiIRRejectionReason.MissingTwoArmCoverage,
            "the ownership join must cover exactly one then arm and one else arm",
        )
        if (!body.diamond.entryBranchesDirectlyToArms || !body.diamond.hasSingleMerge ||
            body.diamond.arms.any { !it.directToMerge }
        ) reject(
            ArcBranchGuaranteedPhiIRRejectionReason.NonDirectDiamond,
            "both branch stores must directly reach one merge block",
        )

        val parameters = body.diamond.arms.map { it.sourceParameter }
        if (parameters.any { !it.isReference || it.isNullable }) reject(
            ArcBranchGuaranteedPhiIRRejectionReason.NullableParameterAmbiguity,
            "both incoming ownership anchors must be non-null ABI-guaranteed references",
        )
        if (parameters.size == 2 && parameters[0].declarationBinding === parameters[1].declarationBinding) reject(
            ArcBranchGuaranteedPhiIRRejectionReason.NullableParameterAmbiguity,
            "the first multi-definition slice requires two distinct parameter identities",
        )
        if (parameters.any { it.declarationBinding === body.diamond.conditionParameterBinding }) reject(
            ArcBranchGuaranteedPhiIRRejectionReason.InvalidCondition,
            "the Boolean condition parameter must be distinct from both reference sources",
        )
        if (body.diamond.arms.any { !it.storeTargetsSelectedVariable || !it.sourceReadTargetsParameter }) reject(
            ArcBranchGuaranteedPhiIRRejectionReason.InvalidArmBinding,
            "each arm must store to the selected local from its exact declared parameter",
        )

        val use = body.terminalUse
        if (!use.isReferenceIdentityEquality || !use.isNonThrowing ||
            !use.selectedReadTargetsVariable || !use.comparisonReadTargetsParameter ||
            !use.isSolePostMergeUse ||
            parameters.none { it.declarationBinding === use.comparisonParameterBinding }
        ) reject(
            ArcBranchGuaranteedPhiIRRejectionReason.InvalidTerminalUse,
            "the sole post-merge use must be non-throwing identity equality against one incoming parameter",
        )
        if (body.unsupportedEffects.isNotEmpty()) reject(
            ArcBranchGuaranteedPhiIRRejectionReason.UnsupportedEffect,
            "unsupported lowered effects: ${body.unsupportedEffects.sortedBy { it.name }}",
        )

        val exactIRNodes = buildList {
            add(body.functionBinding)
            add(body.variable.declarationBinding)
            add(body.variable.nullInitializerBinding)
            add(body.diamond.expressionBinding)
            add(body.diamond.conditionReadBinding)
            add(body.diamond.conditionParameterBinding)
            body.diamond.arms.forEach { arm ->
                add(arm.branchBinding)
                add(arm.storeBinding)
                add(arm.sourceReadBinding)
            }
            parameters.forEach { add(it.declarationBinding) }
            add(use.exitBinding)
            add(use.equalityBinding)
            add(use.selectedReadBinding)
            add(use.comparisonReadBinding)
        }
        val uniqueIRNodes = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
        if (exactIRNodes.any { !uniqueIRNodes.add(it) }) reject(
            ArcBranchGuaranteedPhiIRRejectionReason.DuplicateIRIdentity,
            "one lowered IR object was assigned more than one exact syntactic role",
        )

        if (rejections.isNotEmpty()) return ArcBranchGuaranteedPhiIRAdapterResult(null, rejections)

        val thenArm = armsByKind.getValue(ArcBranchGuaranteedPhiArmKind.Then).single()
        val elseArm = armsByKind.getValue(ArcBranchGuaranteedPhiArmKind.Else).single()
        val entry = ArcBlockId("branch_phi_entry")
        val thenBlock = ArcBlockId("branch_phi_then")
        val elseBlock = ArcBlockId("branch_phi_else")
        val merge = ArcBlockId("branch_phi_merge")
        val exit = ArcBlockId("branch_phi_exit")
        val thenAnchor = ArcSSAValue("branch_phi_then_anchor")
        val thenSource = ArcSSAValue("branch_phi_then_source")
        val thenCopy = ArcSSAValue("branch_phi_then_copy")
        val elseAnchor = ArcSSAValue("branch_phi_else_anchor")
        val elseSource = ArcSSAValue("branch_phi_else_source")
        val elseCopy = ArcSSAValue("branch_phi_else_copy")
        val joined = ArcSSAValue("branch_phi_joined")
        val cfg = ArcOwnershipSSAInput(
            entry,
            linkedMapOf(
                entry to ArcSSABlock(entry, listOf(
                    ArcSSAOperation.Introduce(thenAnchor, ArcOwnership.Immortal),
                    ArcSSAOperation.Introduce(thenSource, ArcOwnership.Guaranteed, setOf(thenAnchor)),
                    ArcSSAOperation.Introduce(elseAnchor, ArcOwnership.Immortal),
                    ArcSSAOperation.Introduce(elseSource, ArcOwnership.Guaranteed, setOf(elseAnchor)),
                )),
                thenBlock to ArcSSABlock(thenBlock, listOf(
                    ArcSSAOperation.Introduce(thenCopy, ArcOwnership.Owned),
                )),
                elseBlock to ArcSSABlock(elseBlock, listOf(
                    ArcSSAOperation.Introduce(elseCopy, ArcOwnership.Owned),
                )),
                merge to ArcSSABlock(merge, listOf(
                    ArcSSAOperation.Join(joined, linkedMapOf(thenBlock to thenCopy, elseBlock to elseCopy)),
                    ArcSSAOperation.Use(joined, ArcSSAUseKind.Borrow),
                )),
                exit to ArcSSABlock(exit, emptyList()),
            ),
            setOf(
                ArcSSAEdge(entry, thenBlock), ArcSSAEdge(entry, elseBlock),
                ArcSSAEdge(thenBlock, merge), ArcSSAEdge(elseBlock, merge), ArcSSAEdge(merge, exit),
            ),
        )
        val input = ArcSemanticARCInput(
            cfg,
            setOf(
                ArcGuaranteedCopySeed(thenCopy, thenSource, setOf(thenAnchor)),
                ArcGuaranteedCopySeed(elseCopy, elseSource, setOf(elseAnchor)),
            ),
        )
        val semantic = ArcSemanticPhiWebAnalysis.analyze(input)
        val plan = semantic.accepted.singleOrNull()
        if (semantic.rejected.isNotEmpty() || plan == null) return ArcBranchGuaranteedPhiIRAdapterResult(
            null,
            listOf(ArcBranchGuaranteedPhiIRRejection(
                ArcBranchGuaranteedPhiIRRejectionReason.SemanticAnalysisRejected,
                "${semantic.rejected}",
            )),
        )
        val expectedFrontier = setOf(ArcSemanticLifetimeFrontier.AfterOperation(merge, 1))
        if (plan.seeds.size != 2 || plan.members != setOf(thenCopy, elseCopy, joined) ||
            plan.joins != setOf(joined) || plan.reborrows.size != 2 ||
            plan.reborrows.mapTo(linkedSetOf()) { it.edge } !=
                    setOf(ArcSSAEdge(thenBlock, merge), ArcSSAEdge(elseBlock, merge)) ||
            plan.frontier != expectedFrontier ||
            plan.rewrites.filterIsInstance<ArcSemanticARCRewrite.EliminateGuaranteedCopy>().size != 2 ||
            plan.rewrites.filterIsInstance<ArcSemanticARCRewrite.PrepareJoinedWeb>().size != 1
        ) return ArcBranchGuaranteedPhiIRAdapterResult(
            null,
            listOf(ArcBranchGuaranteedPhiIRRejection(
                ArcBranchGuaranteedPhiIRRejectionReason.UnexpectedSemanticPlan,
                "SemanticARC did not reproduce the exact two-seed branch-phi plan",
            )),
        )

        fun binding(owner: T, role: ArcBranchGuaranteedPhiBindingRole) =
            ArcBranchGuaranteedPhiExactBinding(owner, role)
        val exactBindings = linkedMapOf(
            ArcBranchGuaranteedPhiBindingRole.EntryBlock to binding(body.functionBinding, ArcBranchGuaranteedPhiBindingRole.EntryBlock),
            ArcBranchGuaranteedPhiBindingRole.ThenBlock to binding(thenArm.branchBinding, ArcBranchGuaranteedPhiBindingRole.ThenBlock),
            ArcBranchGuaranteedPhiBindingRole.ElseBlock to binding(elseArm.branchBinding, ArcBranchGuaranteedPhiBindingRole.ElseBlock),
            ArcBranchGuaranteedPhiBindingRole.MergeBlock to binding(body.diamond.expressionBinding, ArcBranchGuaranteedPhiBindingRole.MergeBlock),
            ArcBranchGuaranteedPhiBindingRole.ExitBlock to binding(use.exitBinding, ArcBranchGuaranteedPhiBindingRole.ExitBlock),
            ArcBranchGuaranteedPhiBindingRole.ThenAnchor to binding(thenArm.sourceParameter.declarationBinding, ArcBranchGuaranteedPhiBindingRole.ThenAnchor),
            ArcBranchGuaranteedPhiBindingRole.ThenGuaranteedSource to binding(thenArm.sourceReadBinding, ArcBranchGuaranteedPhiBindingRole.ThenGuaranteedSource),
            ArcBranchGuaranteedPhiBindingRole.ThenOwnedCopy to binding(thenArm.storeBinding, ArcBranchGuaranteedPhiBindingRole.ThenOwnedCopy),
            ArcBranchGuaranteedPhiBindingRole.ElseAnchor to binding(elseArm.sourceParameter.declarationBinding, ArcBranchGuaranteedPhiBindingRole.ElseAnchor),
            ArcBranchGuaranteedPhiBindingRole.ElseGuaranteedSource to binding(elseArm.sourceReadBinding, ArcBranchGuaranteedPhiBindingRole.ElseGuaranteedSource),
            ArcBranchGuaranteedPhiBindingRole.ElseOwnedCopy to binding(elseArm.storeBinding, ArcBranchGuaranteedPhiBindingRole.ElseOwnedCopy),
            ArcBranchGuaranteedPhiBindingRole.JoinedPhi to binding(body.diamond.expressionBinding, ArcBranchGuaranteedPhiBindingRole.JoinedPhi),
            ArcBranchGuaranteedPhiBindingRole.TerminalUse to binding(use.selectedReadBinding, ArcBranchGuaranteedPhiBindingRole.TerminalUse),
        )
        val lowered = ArcSemanticPhiLoweredBody(
            functionBinding = exactBindings.getValue(ArcBranchGuaranteedPhiBindingRole.EntryBlock),
            cfg = cfg,
            blockBindings = mapOf(
                entry to exactBindings.getValue(ArcBranchGuaranteedPhiBindingRole.EntryBlock),
                thenBlock to exactBindings.getValue(ArcBranchGuaranteedPhiBindingRole.ThenBlock),
                elseBlock to exactBindings.getValue(ArcBranchGuaranteedPhiBindingRole.ElseBlock),
                merge to exactBindings.getValue(ArcBranchGuaranteedPhiBindingRole.MergeBlock),
                exit to exactBindings.getValue(ArcBranchGuaranteedPhiBindingRole.ExitBlock),
            ),
            operationBindings = mapOf(
                ArcSemanticEmissionOperationId(entry, 0) to exactBindings.getValue(ArcBranchGuaranteedPhiBindingRole.ThenAnchor),
                ArcSemanticEmissionOperationId(entry, 1) to exactBindings.getValue(ArcBranchGuaranteedPhiBindingRole.ThenGuaranteedSource),
                ArcSemanticEmissionOperationId(entry, 2) to exactBindings.getValue(ArcBranchGuaranteedPhiBindingRole.ElseAnchor),
                ArcSemanticEmissionOperationId(entry, 3) to exactBindings.getValue(ArcBranchGuaranteedPhiBindingRole.ElseGuaranteedSource),
                ArcSemanticEmissionOperationId(thenBlock, 0) to exactBindings.getValue(ArcBranchGuaranteedPhiBindingRole.ThenOwnedCopy),
                ArcSemanticEmissionOperationId(elseBlock, 0) to exactBindings.getValue(ArcBranchGuaranteedPhiBindingRole.ElseOwnedCopy),
                ArcSemanticEmissionOperationId(merge, 0) to exactBindings.getValue(ArcBranchGuaranteedPhiBindingRole.JoinedPhi),
                ArcSemanticEmissionOperationId(merge, 1) to exactBindings.getValue(ArcBranchGuaranteedPhiBindingRole.TerminalUse),
            ),
            completeLoweredIRWalk = true,
        )
        val emission = ArcSemanticPhiEmissionAdapter.adapt(input, plan, lowered)
        val selection = emission.selection
        if (emission.rejections.isNotEmpty() || selection == null || selection.actions.size != 6 ||
            selection.actions.keys.filterIsInstance<ArcSemanticEmissionActionId.ConvertJoin>().size != 1 ||
            selection.actions.keys.filterIsInstance<ArcSemanticEmissionActionId.Reborrow>().size != 2 ||
            selection.actions.keys.filterIsInstance<ArcSemanticEmissionActionId.EliminateCopy>().size != 2 ||
            selection.actions.keys.filterIsInstance<ArcSemanticEmissionActionId.EndAfterOperation>().size != 1 ||
            selection.actions.keys.any { it is ArcSemanticEmissionActionId.EndOnEdge ||
                    it is ArcSemanticEmissionActionId.EndBeforeBarrier || it is ArcSemanticEmissionActionId.EndBeforeExit }
        ) return ArcBranchGuaranteedPhiIRAdapterResult(
            null,
            listOf(ArcBranchGuaranteedPhiIRRejection(
                ArcBranchGuaranteedPhiIRRejectionReason.EmissionAdapterRejected,
                "${emission.rejections}",
            )),
        )
        return ArcBranchGuaranteedPhiIRAdapterResult(
            ArcBranchGuaranteedPhiIRSelection(body, plan, selection, exactBindings),
            emptyList(),
        )
    }
}
