/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcBranchGuaranteedPhiIRAdapterTest {
    @Test
    fun exactDirectDiamondBuildsTheGeneralSemanticPhiAndClosedEmissionPlan() {
        val body = validBody()
        val result = ArcBranchGuaranteedPhiIRAdapter.adapt(body)

        assertTrue(result.rejections.toString(), result.rejections.isEmpty())
        val selection = result.selection!!
        assertEquals(2, selection.plan.seeds.size)
        assertEquals(3, selection.plan.members.size)
        assertEquals(1, selection.plan.joins.size)
        assertEquals(2, selection.plan.reborrows.size)
        assertEquals(6, selection.emission.actions.size)
        assertEquals(2, selection.emission.actions.keys
            .filterIsInstance<ArcSemanticEmissionActionId.EliminateCopy>().size)
        assertEquals(2, selection.emission.actions.keys
            .filterIsInstance<ArcSemanticEmissionActionId.Reborrow>().size)
        assertEquals(1, selection.emission.actions.keys
            .filterIsInstance<ArcSemanticEmissionActionId.ConvertJoin>().size)
        assertEquals(1, selection.emission.actions.keys
            .filterIsInstance<ArcSemanticEmissionActionId.EndAfterOperation>().size)
        assertTrue(selection.emission.actions.keys.none { it is ArcSemanticEmissionActionId.EndOnEdge })

        val thenBinding = selection.exactBindings.getValue(ArcBranchGuaranteedPhiBindingRole.ThenOwnedCopy)
        val elseBinding = selection.exactBindings.getValue(ArcBranchGuaranteedPhiBindingRole.ElseOwnedCopy)
        assertSame(body.diamond.arms.single { it.kind === ArcBranchGuaranteedPhiArmKind.Then }.storeBinding,
            thenBinding.owner)
        assertSame(body.diamond.arms.single { it.kind === ArcBranchGuaranteedPhiArmKind.Else }.storeBinding,
            elseBinding.owner)

        ArcSemanticPhiEmissionLedger(selection.emission, selection.emission.functionBinding).also { ledger ->
            selection.emission.actions.values.forEach { ledger.consume(it.id, it.bindingIdentities) }
            ledger.verifyComplete()
        }
    }

    @Test
    fun everyCompilationModeGuardFailsClosed() {
        val body = validBody()
        val modes = listOf(
            body.mode.copy(arcEnabled = false),
            body.mode.copy(optimizationsEnabled = false),
            body.mode.copy(debugInfoDisabled = false),
            body.mode.copy(diagnosticsDisabled = false),
            body.mode.copy(nonSuspendFunction = false),
            body.mode.copy(nonExternalFunction = false),
        )
        modes.forEach { mode ->
            assertRejected(
                ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(mode = mode)),
                ArcBranchGuaranteedPhiIRRejectionReason.UnsupportedCompilationMode,
            )
        }
        assertRejected(
            ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(completeLoweredIRWalk = false)),
            ArcBranchGuaranteedPhiIRRejectionReason.IncompleteLoweredIRWalk,
        )
    }

    @Test
    fun nullableOrAliasedParameterIdentityIsAmbiguous() {
        val body = validBody()
        val thenArm = body.diamond.arms.single { it.kind === ArcBranchGuaranteedPhiArmKind.Then }
        val elseArm = body.diamond.arms.single { it.kind === ArcBranchGuaranteedPhiArmKind.Else }
        val nullable = thenArm.copy(sourceParameter = thenArm.sourceParameter.copy(isNullable = true))
        assertRejected(
            ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(
                diamond = body.diamond.copy(arms = listOf(nullable, elseArm)),
            )),
            ArcBranchGuaranteedPhiIRRejectionReason.NullableParameterAmbiguity,
        )

        val aliased = elseArm.copy(sourceParameter = elseArm.sourceParameter.copy(
            declarationBinding = thenArm.sourceParameter.declarationBinding,
        ))
        assertRejected(
            ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(
                diamond = body.diamond.copy(arms = listOf(thenArm, aliased)),
            )),
            ArcBranchGuaranteedPhiIRRejectionReason.NullableParameterAmbiguity,
        )
    }

    @Test
    fun conditionMustBeOneDirectBooleanParameterRead() {
        val body = validBody()
        assertRejected(
            ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(
                diamond = body.diamond.copy(conditionIsBoolean = false),
            )),
            ArcBranchGuaranteedPhiIRRejectionReason.InvalidCondition,
        )
        assertRejected(
            ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(
                diamond = body.diamond.copy(conditionIsDirectParameterRead = false),
            )),
            ArcBranchGuaranteedPhiIRRejectionReason.InvalidCondition,
        )
        val sourceParameter = body.diamond.arms.first().sourceParameter.declarationBinding
        assertRejected(
            ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(
                diamond = body.diamond.copy(conditionParameterBinding = sourceParameter),
            )),
            ArcBranchGuaranteedPhiIRRejectionReason.InvalidCondition,
        )
    }

    @Test
    fun initializerMustBeTheExactNullAssignedToTheSelectedVariable() {
        val body = validBody()
        assertRejected(
            ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(
                variable = body.variable.copy(initializerIsExactNull = false),
            )),
            ArcBranchGuaranteedPhiIRRejectionReason.InvalidVariable,
        )
    }

    @Test
    fun eachArmMustTargetTheSelectedVariableAndItsExactParameter() {
        val body = validBody()
        val thenArm = body.diamond.arms.single { it.kind === ArcBranchGuaranteedPhiArmKind.Then }
        val elseArm = body.diamond.arms.single { it.kind === ArcBranchGuaranteedPhiArmKind.Else }
        listOf(
            thenArm.copy(storeTargetsSelectedVariable = false),
            thenArm.copy(sourceReadTargetsParameter = false),
        ).forEach { invalid ->
            assertRejected(
                ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(
                    diamond = body.diamond.copy(arms = listOf(invalid, elseArm)),
                )),
                ArcBranchGuaranteedPhiIRRejectionReason.InvalidArmBinding,
            )
        }
    }

    @Test
    fun missingDuplicateOrNonDirectArmsNeverCreateAPartialPhi() {
        val body = validBody()
        val thenArm = body.diamond.arms.single { it.kind === ArcBranchGuaranteedPhiArmKind.Then }
        val elseArm = body.diamond.arms.single { it.kind === ArcBranchGuaranteedPhiArmKind.Else }
        listOf(
            listOf(thenArm),
            listOf(thenArm, thenArm.copy(branchBinding = Any(), storeBinding = Any(), sourceReadBinding = Any())),
            listOf(thenArm, elseArm, elseArm.copy(branchBinding = Any(), storeBinding = Any(), sourceReadBinding = Any())),
        ).forEach { arms ->
            assertRejected(
                ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(diamond = body.diamond.copy(arms = arms))),
                ArcBranchGuaranteedPhiIRRejectionReason.MissingTwoArmCoverage,
            )
        }

        assertRejected(
            ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(
                diamond = body.diamond.copy(arms = listOf(thenArm.copy(directToMerge = false), elseArm)),
            )),
            ArcBranchGuaranteedPhiIRRejectionReason.NonDirectDiamond,
        )
        assertRejected(
            ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(
                diamond = body.diamond.copy(hasSingleMerge = false),
            )),
            ArcBranchGuaranteedPhiIRRejectionReason.NonDirectDiamond,
        )
    }

    @Test
    fun nestedReadsWritesCapturesAndEveryEffectFamilyRejectTheWholeShape() {
        val body = validBody()
        ArcBranchGuaranteedPhiUnsupportedEffect.values().forEach { effect ->
            assertRejected(
                ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(unsupportedEffects = setOf(effect))),
                ArcBranchGuaranteedPhiIRRejectionReason.UnsupportedEffect,
            )
        }
    }

    @Test
    fun terminalUseMustBeNonThrowingIdentityEqualityAgainstAnIncomingParameter() {
        val body = validBody()
        assertRejected(
            ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(
                terminalUse = body.terminalUse.copy(isReferenceIdentityEquality = false),
            )),
            ArcBranchGuaranteedPhiIRRejectionReason.InvalidTerminalUse,
        )
        listOf(
            body.terminalUse.copy(selectedReadTargetsVariable = false),
            body.terminalUse.copy(comparisonReadTargetsParameter = false),
            body.terminalUse.copy(isSolePostMergeUse = false),
        ).forEach { invalid ->
            assertRejected(
                ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(terminalUse = invalid)),
                ArcBranchGuaranteedPhiIRRejectionReason.InvalidTerminalUse,
            )
        }
        assertRejected(
            ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(
                terminalUse = body.terminalUse.copy(isNonThrowing = false),
            )),
            ArcBranchGuaranteedPhiIRRejectionReason.InvalidTerminalUse,
        )
        assertRejected(
            ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(
                terminalUse = body.terminalUse.copy(comparisonParameterBinding = Any()),
            )),
            ArcBranchGuaranteedPhiIRRejectionReason.InvalidTerminalUse,
        )
    }

    @Test
    fun syntacticBindingsCannotBeReusedAcrossRoles() {
        val body = validBody()
        assertRejected(
            ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(
                terminalUse = body.terminalUse.copy(
                    selectedReadBinding = body.terminalUse.comparisonReadBinding,
                ),
            )),
            ArcBranchGuaranteedPhiIRRejectionReason.DuplicateIRIdentity,
        )
        assertRejected(
            ArcBranchGuaranteedPhiIRAdapter.adapt(body.copy(
                variable = body.variable.copy(nullInitializerBinding = body.variable.declarationBinding),
            )),
            ArcBranchGuaranteedPhiIRRejectionReason.DuplicateIRIdentity,
        )
    }

    private fun validBody(): ArcBranchGuaranteedPhiLoweredBody<Any> {
        val thenParameter = ArcBranchGuaranteedPhiParameter(Any(), isReference = true, isNullable = false)
        val elseParameter = ArcBranchGuaranteedPhiParameter(Any(), isReference = true, isNullable = false)
        val arms = listOf(
            ArcBranchGuaranteedPhiArm(
                ArcBranchGuaranteedPhiArmKind.Then,
                branchBinding = Any(),
                storeBinding = Any(),
                sourceReadBinding = Any(),
                sourceParameter = thenParameter,
                storeTargetsSelectedVariable = true,
                sourceReadTargetsParameter = true,
                directToMerge = true,
            ),
            ArcBranchGuaranteedPhiArm(
                ArcBranchGuaranteedPhiArmKind.Else,
                branchBinding = Any(),
                storeBinding = Any(),
                sourceReadBinding = Any(),
                sourceParameter = elseParameter,
                storeTargetsSelectedVariable = true,
                sourceReadTargetsParameter = true,
                directToMerge = true,
            ),
        )
        return ArcBranchGuaranteedPhiLoweredBody(
            functionBinding = Any(),
            mode = ArcBranchGuaranteedPhiCompilationMode(
                arcEnabled = true,
                optimizationsEnabled = true,
                debugInfoDisabled = true,
                diagnosticsDisabled = true,
                nonSuspendFunction = true,
                nonExternalFunction = true,
            ),
            variable = ArcBranchGuaranteedPhiVariable(
                declarationBinding = Any(),
                nullInitializerBinding = Any(),
                initializerIsExactNull = true,
                isMutable = true,
                isNullableReference = true,
                isStrong = true,
            ),
            diamond = ArcBranchGuaranteedPhiDiamond(
                expressionBinding = Any(),
                conditionReadBinding = Any(),
                conditionParameterBinding = Any(),
                conditionIsBoolean = true,
                conditionIsDirectParameterRead = true,
                arms = arms,
                entryBranchesDirectlyToArms = true,
                hasSingleMerge = true,
            ),
            terminalUse = ArcBranchGuaranteedPhiTerminalUse(
                exitBinding = Any(),
                equalityBinding = Any(),
                selectedReadBinding = Any(),
                comparisonReadBinding = Any(),
                comparisonParameterBinding = thenParameter.declarationBinding,
                selectedReadTargetsVariable = true,
                comparisonReadTargetsParameter = true,
                isSolePostMergeUse = true,
                isReferenceIdentityEquality = true,
                isNonThrowing = true,
            ),
            completeLoweredIRWalk = true,
        )
    }

    private fun assertRejected(
        result: ArcBranchGuaranteedPhiIRAdapterResult<Any>,
        reason: ArcBranchGuaranteedPhiIRRejectionReason,
    ) {
        assertTrue(result.rejections.toString(), result.rejections.any { it.reason === reason })
        assertTrue(result.selection === null)
    }
}
