/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.native.interop.rust

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RustInteropBridgePlanTest {
    @Test
    fun parsesRegexFacadeAsAnOperationShapedPlan() {
        val plan = RustInteropTomlParser.parsePlan(REGEX_DEFINITION, "regex.rustinterop.toml")

        assertEquals(1, plan.schemaVersion)
        assertEquals("rust.regex", plan.kotlinPackage)
        assertEquals(RustInteropCrate("regex", "1.11.1", listOf("unicode"), true), plan.crate)
        assertEquals(
            RustInteropHandle("regex", "regex::Regex", "Regex", RustInteropHandleThreading.SEND_SYNC),
            plan.handles.single(),
        )
        assertEquals(listOf("close", "compile", "is-match", "pattern"), plan.operations.map { it.id })

        val compile = plan.operations.single { it.id == "compile" }
        assertEquals(RustInteropOperationKind.CONSTRUCTOR, compile.kind)
        assertEquals(RustInteropReceiverOwnership.NONE, compile.receiver.ownership)
        assertEquals(RustInteropParameter("pattern", RustInteropBridgeType.Utf8String), compile.parameters.single())
        assertEquals(RustInteropBridgeType.Handle("regex"), compile.returnType)
        assertEquals(RustInteropErrorMode.KOTLIN_EXCEPTION, compile.errorPolicy.mode)
        assertEquals("RegexException", compile.errorPolicy.kotlinException)

        val isMatch = plan.operations.single { it.id == "is-match" }
        assertEquals(RustInteropReceiver(RustInteropReceiverOwnership.SHARED, "regex"), isMatch.receiver)
        assertEquals(RustInteropBridgeType.Primitive(RustInteropPrimitive.BOOLEAN), isMatch.returnType)

        val pattern = plan.operations.single { it.id == "pattern" }
        assertEquals(RustInteropOperationKind.PROPERTY_GET, pattern.kind)
        assertEquals(RustInteropBridgeType.Utf8String, pattern.returnType)

        val close = plan.operations.single { it.id == "close" }
        assertEquals(RustInteropReceiverOwnership.CONSUMING, close.receiver.ownership)
        assertEquals(RustInteropBridgeType.Unit, close.returnType)
    }

    @Test
    fun rendersCanonicalJsonIndependentOfUnorderedInputCollections() {
        val plan = RustInteropTomlParser.parsePlan(REGEX_DEFINITION)
        val reordered = plan.copy(
            crate = plan.crate.copy(features = listOf("unicode-perl", "unicode", "unicode")),
            handles = plan.handles.reversed(),
            operations = plan.operations.reversed(),
        )
        val equivalent = plan.copy(crate = plan.crate.copy(features = listOf("unicode", "unicode-perl")))

        assertEquals(RustInteropBridgePlanRenderer.render(equivalent), RustInteropBridgePlanRenderer.render(reordered))
        assertEquals(RustInteropBridgePlanHash.planHash(equivalent), RustInteropBridgePlanHash.planHash(reordered))
        assertEquals(EXPECTED_REGEX_JSON, RustInteropBridgePlanRenderer.render(plan))
    }

    @Test
    fun createsHashOnlyVersionedSymbols() {
        val plan = RustInteropTomlParser.parsePlan(REGEX_DEFINITION)
        val compile = plan.operations.single { it.id == "compile" }
        val isMatch = plan.operations.single { it.id == "is-match" }

        val compileSymbol = RustInteropBridgeSymbols.bindingSymbol(plan, compile)
        val matchSymbol = RustInteropBridgeSymbols.bindingSymbol(plan, isMatch)

        assertTrue(compileSymbol.matches(Regex("knri_v1_p[0-9a-f]{32}_b[0-9a-f]{16}")))
        assertNotEquals(compileSymbol, matchSymbol)
        assertFalse(compileSymbol.contains("compile", ignoreCase = true))
        assertFalse(matchSymbol.contains("match", ignoreCase = true))
        assertFalse(compileSymbol.contains("regex", ignoreCase = true))
        assertFailsWith<IllegalArgumentException> {
            RustInteropBridgeSymbols.bindingSymbol(plan, compile.copy(rustPath = "other::compile"))
        }
    }

    @Test
    fun rejectsUnknownAndUnsupportedTomlInsteadOfIgnoringIt() {
        val unknownKey = assertFailsWith<RustInteropDefinitionException> {
            RustInteropTomlParser.parseDefinition(
                """
                    schema = 1
                    package = "rust.regex"
                    crate-name = "regex"
                """.trimIndent(),
                "bad.rustinterop.toml",
            )
        }
        assertTrue(unknownKey.message.orEmpty().contains("unknown key 'crate-name'"))
        assertTrue(unknownKey.message.orEmpty().contains("bad.rustinterop.toml:3:1"))

        val unsupportedValue = assertFailsWith<RustInteropDefinitionException> {
            RustInteropTomlParser.parseDefinition(
                """
                    schema = 1
                    package = "rust.regex"
                    [crate]
                    name = "regex"
                    version = "1.11.1"
                    features = [{ name = "unicode" }]
                """.trimIndent(),
            )
        }
        assertTrue(unsupportedValue.message.orEmpty().contains("malformed TOML value"))
    }

    @Test
    fun reportsMissingPoliciesAndInvalidOwnership() {
        val missingPolicy = assertFailsWith<RustInteropDefinitionException> {
            RustInteropTomlParser.parseDefinition(REGEX_DEFINITION.replace("panic = \"kotlin-exception:RegexException\"\n", ""))
        }
        assertTrue(missingPolicy.diagnostics.any { it.path.endsWith(".panic") && it.message == "missing required key" })

        val invalidOwnership = assertFailsWith<RustInteropDefinitionException> {
            RustInteropTomlParser.parsePlan(REGEX_DEFINITION.replaceFirst("receiver = \"borrow:regex\"", "receiver = \"borrow:missing\""))
        }
        assertTrue(invalidOwnership.diagnostics.any { it.path.endsWith(".receiver") && it.message.contains("unknown handle 'missing'") })
    }

    @Test
    fun supportsExplicitOptionTypesButRejectsNestedOptions() {
        val plan = RustInteropTomlParser.parsePlan(
            REGEX_DEFINITION.replaceFirst("return = \"string\"", "return = \"option:string\"")
        )
        assertIs<RustInteropBridgeType.Option>(plan.operations.single { it.id == "pattern" }.returnType)

        val failure = assertFailsWith<RustInteropDefinitionException> {
            RustInteropTomlParser.parsePlan(
                REGEX_DEFINITION.replaceFirst("return = \"string\"", "return = \"option:option:string\"")
            )
        }
        assertTrue(failure.message.orEmpty().contains("option must wrap a non-unit, non-option bridge type"))
    }

    private companion object {
        val REGEX_DEFINITION = """
            schema = 1
            package = "rust.regex"

            [crate]
            name = "regex"
            version = "1.11.1"
            features = ["unicode"]
            default-features = true

            [[handle]]
            id = "regex"
            rust-type = "regex::Regex"
            kotlin-type = "Regex"
            threading = "send-sync"

            [[operation]]
            id = "compile"
            kind = "constructor"
            rust-path = "regex::Regex::new"
            kotlin-name = "Regex.compile"
            receiver = "none"
            parameters = ["pattern:string"]
            return = "handle:regex"
            error = "kotlin-exception:RegexException"
            panic = "kotlin-exception:RegexException"
            threading = "caller"
            async = false

            [[operation]]
            id = "is-match"
            kind = "method"
            rust-path = "regex::Regex::is_match"
            kotlin-name = "Regex.isMatch"
            receiver = "borrow:regex"
            parameters = ["input:string"]
            return = "bool"
            error = "none"
            panic = "kotlin-exception:RegexException"
            threading = "caller"
            async = false

            [[operation]]
            id = "pattern"
            kind = "property-get"
            rust-path = "regex::Regex::as_str"
            kotlin-name = "Regex.pattern"
            receiver = "borrow:regex"
            parameters = []
            return = "string"
            error = "none"
            panic = "kotlin-exception:RegexException"
            threading = "caller"
            async = false

            [[operation]]
            id = "close"
            kind = "close"
            rust-path = "core::mem::drop"
            kotlin-name = "Regex.close"
            receiver = "consume:regex"
            parameters = []
            return = "unit"
            error = "none"
            panic = "kotlin-exception:RegexException"
            threading = "caller"
            async = false
        """.trimIndent()

        val EXPECTED_REGEX_JSON = """
            {"schema":1,"package":"rust.regex","crate":{"name":"regex","version":"1.11.1","features":["unicode"],"defaultFeatures":true},"handles":[{"id":"regex","rustType":"regex::Regex","kotlinType":"Regex","threading":"send-sync"}],"operations":[{"id":"close","kind":"close","rustPath":"core::mem::drop","kotlinName":"Regex.close","receiver":{"ownership":"consume","handle":"regex"},"parameters":[],"return":"unit","error":{"mode":"none","exception":null},"panic":{"mode":"kotlin-exception","exception":"RegexException"},"threading":"caller","async":"synchronous","targets":[],"excludedTargets":[]},{"id":"compile","kind":"constructor","rustPath":"regex::Regex::new","kotlinName":"Regex.compile","receiver":{"ownership":"none","handle":null},"parameters":[{"name":"pattern","type":"string"}],"return":"handle:regex","error":{"mode":"kotlin-exception","exception":"RegexException"},"panic":{"mode":"kotlin-exception","exception":"RegexException"},"threading":"caller","async":"synchronous","targets":[],"excludedTargets":[]},{"id":"is-match","kind":"method","rustPath":"regex::Regex::is_match","kotlinName":"Regex.isMatch","receiver":{"ownership":"borrow","handle":"regex"},"parameters":[{"name":"input","type":"string"}],"return":"bool","error":{"mode":"none","exception":null},"panic":{"mode":"kotlin-exception","exception":"RegexException"},"threading":"caller","async":"synchronous","targets":[],"excludedTargets":[]},{"id":"pattern","kind":"property-get","rustPath":"regex::Regex::as_str","kotlinName":"Regex.pattern","receiver":{"ownership":"borrow","handle":"regex"},"parameters":[],"return":"string","error":{"mode":"none","exception":null},"panic":{"mode":"kotlin-exception","exception":"RegexException"},"threading":"caller","async":"synchronous","targets":[],"excludedTargets":[]}]}
        """.trimIndent() + "\n"
    }
}
