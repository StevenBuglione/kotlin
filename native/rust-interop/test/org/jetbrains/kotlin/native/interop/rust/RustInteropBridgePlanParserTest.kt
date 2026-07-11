/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.native.interop.rust

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RustInteropBridgePlanParserTest {
    @Test
    fun roundTripsCanonicalPlanJson() {
        val plan = RustInteropTomlParser.parsePlan(PRIMITIVE_FUNCTION_DEFINITION)
        val json = RustInteropBridgePlanRenderer.render(plan)

        assertEquals(plan, RustInteropBridgePlanParser.parse(json, "fixture-plan.json"))
    }

    @Test
    fun rejectsJsonThatIsNotCanonical() {
        val canonical = RustInteropBridgePlanRenderer.render(
            RustInteropTomlParser.parsePlan(PRIMITIVE_FUNCTION_DEFINITION)
        )
        val reorderedRoot = canonical.replaceFirst(
            "{\"schema\":1,\"package\":\"rust.fixture\"",
            "{\"package\":\"rust.fixture\",\"schema\":1",
        )

        val failure = assertFailsWith<RustInteropBridgePlanParseException> {
            RustInteropBridgePlanParser.parse(reorderedRoot, "reordered.json")
        }
        assertTrue(failure.message.orEmpty().contains("not in canonical bridge-plan form"))
    }

    @Test
    fun rejectsDuplicatePropertiesAndUnknownTypes() {
        val canonical = RustInteropBridgePlanRenderer.render(
            RustInteropTomlParser.parsePlan(PRIMITIVE_FUNCTION_DEFINITION)
        )
        val duplicate = canonical.replaceFirst("\"schema\":1", "\"schema\":1,\"schema\":1")
        val duplicateFailure = assertFailsWith<RustInteropBridgePlanParseException> {
            RustInteropBridgePlanParser.parse(duplicate, "duplicate.json")
        }
        assertTrue(duplicateFailure.message.orEmpty().contains("duplicate object property 'schema'"))

        val unknownType = canonical.replaceFirst("\"type\":\"i32\"", "\"type\":\"usize\"")
        val typeFailure = assertFailsWith<RustInteropBridgePlanParseException> {
            RustInteropBridgePlanParser.parse(unknownType, "type.json")
        }
        assertTrue(typeFailure.message.orEmpty().contains("unsupported canonical bridge type 'usize'"))
    }

    private companion object {
        val PRIMITIVE_FUNCTION_DEFINITION = """
            schema = 1
            package = "rust.fixture"

            [crate]
            name = "kn-direct-fixture"
            version = "1.0.0"
            features = []
            default-features = false

            [[operation]]
            id = "add"
            kind = "function"
            rust-path = "kn_direct_fixture::add"
            kotlin-name = "add"
            receiver = "none"
            parameters = ["left:i32", "right:i32"]
            return = "i32"
            error = "none"
            panic = "abort"
            threading = "caller"
            async = false
        """.trimIndent()
    }
}
