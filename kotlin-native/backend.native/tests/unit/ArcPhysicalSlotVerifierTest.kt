/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.jetbrains.kotlin.backend.konan.arc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcPhysicalSlotVerifierTest {
    @Test
    fun rewriteAuthorizationRequiresReleaseModeAndEveryCompletedProof() {
        val accepted = ArcPhysicalSlotRewriteEligibility(
            arcEnabled = true,
            optimizationsEnabled = true,
            debugInfoDisabled = true,
            diagnosticsDisabled = true,
            exactOwnershipPlanIdentity = true,
            completeUseChainVerified = true,
            completeCfgCoverageVerified = true,
            normalAndUnwindEdgesModeled = true,
            deinitializationBarriersModeled = true,
        )
        assertTrue(accepted.isAuthorized())
        assertFalse(accepted.copy(arcEnabled = false).isAuthorized())
        assertFalse(accepted.copy(optimizationsEnabled = false).isAuthorized())
        assertFalse(accepted.copy(debugInfoDisabled = false).isAuthorized())
        assertFalse(accepted.copy(diagnosticsDisabled = false).isAuthorized())
        assertFalse(accepted.copy(exactOwnershipPlanIdentity = false).isAuthorized())
        assertFalse(accepted.copy(completeUseChainVerified = false).isAuthorized())
        assertFalse(accepted.copy(completeCfgCoverageVerified = false).isAuthorized())
        assertFalse(accepted.copy(normalAndUnwindEdgesModeled = false).isAuthorized())
        assertFalse(accepted.copy(deinitializationBarriersModeled = false).isAuthorized())
    }

    @Test
    fun bitcastGepPhiAndSelectRemainMayAliasesOfThePhysicalRoot() {
        val verification = ArcPhysicalSlotUseChainVerifier.verify(
            ArcPhysicalSlotUseChain(
                root = "slot",
                definitions = listOf(
                    ArcPhysicalPointerDefinition.Bitcast("cast", "slot"),
                    ArcPhysicalPointerDefinition.Gep("gep", "cast"),
                    ArcPhysicalPointerDefinition.Phi("phi", "join", mapOf("left" to "gep", "right" to "other")),
                    ArcPhysicalPointerDefinition.Select("select", "phi", "other"),
                ),
                uses = listOf(ArcPhysicalPointerUse.Load("select")),
                usesComplete = true,
            ),
            emptySet(),
        )
        assertTrue(verification.accepted)
        assertEquals(setOf("slot", "cast", "gep", "phi", "select"), verification.aliases)
    }

    @Test
    fun mutationsEscapesAndUnknownCallsThroughAnyDerivedAliasFailClosed() {
        val definitions = listOf<ArcPhysicalPointerDefinition<String, String>>(
            ArcPhysicalPointerDefinition.Bitcast("cast", "slot"),
            ArcPhysicalPointerDefinition.Gep("gep", "cast"),
        )
        fun rejection(use: ArcPhysicalPointerUse<String>) = ArcPhysicalSlotUseChainVerifier.verify(
            ArcPhysicalSlotUseChain("slot", definitions, listOf(use), usesComplete = true), emptySet()
        ).rejections.single()
        assertEquals(ArcPhysicalSlotUseRejection.MUTATION_THROUGH_ALIAS, rejection(ArcPhysicalPointerUse.StoreThrough("gep")))
        assertEquals(ArcPhysicalSlotUseRejection.POINTER_ESCAPE, rejection(ArcPhysicalPointerUse.StorePointer("cast")))
        assertEquals(ArcPhysicalSlotUseRejection.POINTER_ESCAPE, rejection(ArcPhysicalPointerUse.ReturnPointer("gep")))
        assertEquals(ArcPhysicalSlotUseRejection.UNKNOWN_CALL, rejection(ArcPhysicalPointerUse.UnknownCall("cast")))
    }

    @Test
    fun trustedRuntimeAuthorizationUsesDeclarationIdentityAndTheExactRootArgument() {
        val trusted = ArcExactRuntimeFunctionIdentity("UpdateReturnRef")
        val sameNameButDifferentDeclaration = ArcExactRuntimeFunctionIdentity("UpdateReturnRef")
        val definitions = listOf<ArcPhysicalPointerDefinition<String, String>>(
            ArcPhysicalPointerDefinition.Bitcast("cast", "slot")
        )
        fun verify(pointer: String, function: ArcExactRuntimeFunctionIdentity) =
            ArcPhysicalSlotUseChainVerifier.verify(
                ArcPhysicalSlotUseChain(
                    "slot", definitions,
                    listOf(ArcPhysicalPointerUse.TrustedRuntimeCall(pointer, function)),
                    usesComplete = true,
                ),
                setOf(trusted),
            )
        assertTrue(verify("slot", trusted).accepted)
        assertEquals(
            setOf(ArcPhysicalSlotUseRejection.UNTRUSTED_RUNTIME_CALL),
            verify("slot", sameNameButDifferentDeclaration).rejections,
        )
        assertEquals(
            setOf(ArcPhysicalSlotUseRejection.TRUSTED_CALL_REQUIRES_EXACT_ROOT),
            verify("cast", trusted).rejections,
        )
    }

    @Test
    fun incompleteOpaqueAndCyclicUseChainsReject() {
        val incomplete = ArcPhysicalSlotUseChainVerifier.verify(
            ArcPhysicalSlotUseChain<String, String>("slot", emptyList(), emptyList(), usesComplete = false), emptySet()
        )
        assertEquals(setOf(ArcPhysicalSlotUseRejection.INCOMPLETE_USE_CHAIN), incomplete.rejections)

        val opaque = ArcPhysicalSlotUseChainVerifier.verify(
            ArcPhysicalSlotUseChain(
                "slot",
                listOf<ArcPhysicalPointerDefinition<String, String>>(ArcPhysicalPointerDefinition.OpaqueDerived("opaque", "slot")),
                listOf(ArcPhysicalPointerUse.Load("opaque")),
                usesComplete = true,
            ),
            emptySet(),
        )
        assertTrue(ArcPhysicalSlotUseRejection.OPAQUE_DERIVED_ALIAS in opaque.rejections)

        val cyclic = ArcPhysicalSlotUseChainVerifier.verify(
            ArcPhysicalSlotUseChain(
                "slot",
                listOf<ArcPhysicalPointerDefinition<String, String>>(
                    ArcPhysicalPointerDefinition.Phi("a", "loop", mapOf("entry" to "slot", "loop" to "b")),
                    ArcPhysicalPointerDefinition.Bitcast("b", "a"),
                ),
                listOf(ArcPhysicalPointerUse.Load("a")),
                usesComplete = true,
            ),
            emptySet(),
        )
        assertTrue(ArcPhysicalSlotUseRejection.CYCLIC_POINTER_DEFINITION in cyclic.rejections)
    }

    @Test
    fun unwindEdgesParticipateInReachabilityAndJoinPredecessors() {
        val cfg = ArcPhysicalCfg(
            entry = "entry",
            blocks = setOf("entry", "normal", "catch", "join"),
            edges = setOf(
                ArcPhysicalCfgEdge("entry", "normal", ArcPhysicalCfgEdgeKind.NORMAL),
                ArcPhysicalCfgEdge("entry", "catch", ArcPhysicalCfgEdgeKind.UNWIND),
                ArcPhysicalCfgEdge("normal", "join", ArcPhysicalCfgEdgeKind.NORMAL),
                ArcPhysicalCfgEdge("catch", "join", ArcPhysicalCfgEdgeKind.NORMAL),
            ),
        )
        assertEquals(setOf("entry", "normal", "catch", "join"), cfg.reachableBlocks())
        assertEquals(setOf("normal", "catch"), cfg.reachablePredecessors("join"))
    }

    @Test
    fun invokeCarriesOwnershipOnlyOnNormalSuccessAndBlocksCatchJoinProof() {
        val state = ArcPhysicalSlotExactState<String, String>("owned")
        val invokeStates = ArcPhysicalSlotCfgVerifier.invokeEdgeStates("normal", "catch", state)
        assertEquals(state, invokeStates["normal"])
        assertNull(invokeStates["catch"])

        val cfg = invokeJoinCfg()
        val result = ArcPhysicalSlotCfgVerifier.proveJoin(
            cfg,
            "join",
            ArcPhysicalValuePhi("result", "join", "Obj*", mapOf("normal" to "owned", "catch" to "fallback")),
            "Obj*",
            mapOf("normal" to state, "catch" to null),
        )
        assertFalse(result.accepted)
        assertEquals(ArcPhysicalSlotJoinRejection.UNWIND_OR_OTHER_EDGE_HAS_NO_OWNER, result.rejection)
    }

    @Test
    fun exactNormalDiamondJoinProducesFrozenCoverage() {
        val cfg = ArcPhysicalCfg(
            entry = "entry",
            blocks = setOf("entry", "left", "right", "join"),
            edges = setOf(
                ArcPhysicalCfgEdge("entry", "left", ArcPhysicalCfgEdgeKind.NORMAL),
                ArcPhysicalCfgEdge("entry", "right", ArcPhysicalCfgEdgeKind.NORMAL),
                ArcPhysicalCfgEdge("left", "join", ArcPhysicalCfgEdgeKind.NORMAL),
                ArcPhysicalCfgEdge("right", "join", ArcPhysicalCfgEdgeKind.NORMAL),
            ),
        )
        val result = ArcPhysicalSlotCfgVerifier.proveJoin(
            cfg,
            "join",
            ArcPhysicalValuePhi("joined", "join", "Obj*", mapOf("left" to "a", "right" to "b")),
            "Obj*",
            mapOf(
                "left" to ArcPhysicalSlotExactState("a"),
                "right" to ArcPhysicalSlotExactState("b"),
            ),
        )
        assertTrue(result.accepted)
        assertEquals("joined", result.state?.value)
        assertEquals(setOf("left", "right"), result.state?.coverage?.get("join"))
    }

    @Test
    fun joinsRequireExactPhiParentTypePredecessorsAndValues() {
        val cfg = invokeJoinCfg()
        val states = mapOf(
            "normal" to ArcPhysicalSlotExactState<String, String>("owned"),
            "catch" to ArcPhysicalSlotExactState("fallback"),
        )
        fun rejection(phi: ArcPhysicalValuePhi<String, String, String>, expectedType: String = "Obj*") =
            ArcPhysicalSlotCfgVerifier.proveJoin(cfg, "join", phi, expectedType, states).rejection
        assertEquals(
            ArcPhysicalSlotJoinRejection.PHI_PARENT_MISMATCH,
            rejection(ArcPhysicalValuePhi("result", "other", "Obj*", mapOf("normal" to "owned", "catch" to "fallback"))),
        )
        assertEquals(
            ArcPhysicalSlotJoinRejection.PHI_TYPE_MISMATCH,
            rejection(ArcPhysicalValuePhi("result", "join", "i8*", mapOf("normal" to "owned", "catch" to "fallback"))),
        )
        assertEquals(
            ArcPhysicalSlotJoinRejection.PHI_PREDECESSOR_MISMATCH,
            rejection(ArcPhysicalValuePhi("result", "join", "Obj*", mapOf("normal" to "owned"))),
        )
        assertEquals(
            ArcPhysicalSlotJoinRejection.PHI_VALUE_MISMATCH,
            rejection(ArcPhysicalValuePhi("result", "join", "Obj*", mapOf("normal" to "wrong", "catch" to "fallback"))),
        )
    }

    @Test
    fun missingEdgeStateAndConflictingNestedCoverageFailClosed() {
        val cfg = invokeJoinCfg()
        val phi = ArcPhysicalValuePhi("result", "join", "Obj*", mapOf("normal" to "owned", "catch" to "fallback"))
        val missing = ArcPhysicalSlotCfgVerifier.proveJoin(
            cfg, "join", phi, "Obj*", mapOf("normal" to ArcPhysicalSlotExactState("owned"))
        )
        assertEquals(ArcPhysicalSlotJoinRejection.INCOMPLETE_EDGE_STATES, missing.rejection)

        val conflicting = ArcPhysicalSlotCfgVerifier.proveJoin(
            cfg,
            "join",
            phi,
            "Obj*",
            mapOf(
                "normal" to ArcPhysicalSlotExactState("owned", mapOf("earlier" to setOf("a"))),
                "catch" to ArcPhysicalSlotExactState("fallback", mapOf("earlier" to setOf("a", "backedge"))),
            ),
        )
        assertEquals(ArcPhysicalSlotJoinRejection.CONFLICTING_COVERAGE, conflicting.rejection)
    }

    @Test
    fun finalCoverageValidationRejectsALateLoopBackedge() {
        val cfg = ArcPhysicalCfg(
            entry = "entry",
            blocks = setOf("entry", "header", "body", "exit"),
            edges = setOf(
                ArcPhysicalCfgEdge("entry", "header", ArcPhysicalCfgEdgeKind.NORMAL),
                ArcPhysicalCfgEdge("header", "body", ArcPhysicalCfgEdgeKind.NORMAL),
                ArcPhysicalCfgEdge("header", "exit", ArcPhysicalCfgEdgeKind.NORMAL),
                ArcPhysicalCfgEdge("body", "header", ArcPhysicalCfgEdgeKind.NORMAL),
            ),
        )
        val frozenTooEarly = ArcPhysicalSlotExactState("value", mapOf("header" to setOf("entry")))
        val complete = ArcPhysicalSlotExactState("value", mapOf("header" to setOf("entry", "body")))
        assertFalse(ArcPhysicalSlotCfgVerifier.coverageIsComplete(cfg, frozenTooEarly))
        assertTrue(ArcPhysicalSlotCfgVerifier.coverageIsComplete(cfg, complete))
    }

    private fun invokeJoinCfg() = ArcPhysicalCfg(
        entry = "entry",
        blocks = setOf("entry", "normal", "catch", "join"),
        edges = setOf(
            ArcPhysicalCfgEdge("entry", "normal", ArcPhysicalCfgEdgeKind.NORMAL),
            ArcPhysicalCfgEdge("entry", "catch", ArcPhysicalCfgEdgeKind.UNWIND),
            ArcPhysicalCfgEdge("normal", "join", ArcPhysicalCfgEdgeKind.NORMAL),
            ArcPhysicalCfgEdge("catch", "join", ArcPhysicalCfgEdgeKind.NORMAL),
        ),
    )
}
