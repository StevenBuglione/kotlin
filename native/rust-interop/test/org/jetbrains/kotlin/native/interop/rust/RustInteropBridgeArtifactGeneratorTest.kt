/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.native.interop.rust

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RustInteropBridgeArtifactGeneratorTest {
    @Test
    fun generatesDeterministicRegexBridgeArtifacts() {
        val plan = RustInteropTomlParser.parsePlan(REGEX_DEFINITION, "regex.rustinterop.toml")

        val artifacts = RustInteropBridgeArtifactGenerator.generate(plan)
        val repeated = RustInteropBridgeArtifactGenerator.generate(plan)
        val kotlinEntry = artifacts.kotlinFacades.entries.single()
        val kotlinPath = kotlinEntry.key
        val kotlinFacade = kotlinEntry.value

        assertEquals(repeated, artifacts)
        assertEquals(EXPECTED_CARGO_MANIFEST, artifacts.cargoManifest)
        assertEquals("kotlinx.rustinterop.generated.a65c41e94865df702", artifacts.cInteropPackage)
        assertEquals(EXPECTED_C_HEADER_SHA256, sha256(artifacts.cHeader))
        assertEquals(EXPECTED_RUST_SOURCE_SHA256, sha256(artifacts.rustSource))
        assertEquals(EXPECTED_KOTLIN_PATH, kotlinPath)
        assertEquals(EXPECTED_KOTLIN_FACADE_SHA256, sha256(kotlinFacade))
        listOf(
            artifacts.cargoManifest,
            artifacts.cHeader,
            artifacts.rustSource,
            kotlinFacade,
        ).forEach { artifact ->
            assertTrue(artifact.endsWith('\n'), "generated text must have a trailing LF")
            assertFalse('\r' in artifact, "generated text must use canonical LF line endings")
        }
    }

    @Test
    fun bridgeAbiKeepsRustAndKotlinImplementationDetailsPrivate() {
        val plan = RustInteropTomlParser.parsePlan(REGEX_DEFINITION)
        val artifacts = RustInteropBridgeArtifactGenerator.generate(plan)
        val kotlinFacade = artifacts.kotlinFacades.values.single()
        val symbols = plan.operations.associateWith { RustInteropBridgeSymbols.bindingSymbol(plan, it) }

        symbols.forEach { entry ->
            val operation = entry.key
            val symbol = entry.value
            assertTrue(symbol.matches(Regex("knri_v1_p[0-9a-f]{32}_b[0-9a-f]{16}")))
            assertTrue(artifacts.cHeader.contains(symbol), "header must declare ${operation.id}")
            assertTrue(artifacts.rustSource.contains(symbol), "Rust source must export ${operation.id}")
            assertTrue(kotlinFacade.contains(symbol), "Kotlin facade must import ${operation.id}")
        }
        assertFalse(artifacts.cHeader.contains("regex::"), "Rust types must not escape through the C ABI")
        assertFalse(artifacts.cHeader.contains("Regex.compile"), "Kotlin names must not become C symbols")

        assertTrue(artifacts.cHeader.contains("const uint8_t *"))
        assertTrue(artifacts.cHeader.contains("size_t"))
        assertTrue(artifacts.rustSource.contains("from_raw_parts"))
        assertFalse(artifacts.rustSource.contains("CStr"), "UTF-8 must use pointer/length and preserve embedded NUL")
        assertTrue(artifacts.rustSource.contains("catch_unwind"), "every crate call must contain Rust panics")
        assertTrue(artifacts.rustSource.contains("AssertUnwindSafe"))

        assertTrue(artifacts.cHeader.contains("uint64_t handle"))
        assertTrue(artifacts.cHeader.contains("uint64_t *output"))
        assertTrue(artifacts.rustSource.contains("Arc<regex::Regex>"))
        assertTrue(artifacts.rustSource.contains(".cloned()"))
        assertTrue(artifacts.rustSource.contains(".remove("))
        assertTrue(artifacts.rustSource.contains("into_boxed_slice"))
        assertTrue(artifacts.rustSource.contains("Box::from_raw"))
        assertTrue(artifacts.cHeader.contains("_free_utf8"))
        assertTrue(kotlinFacade.contains("_free_utf8(value.data, value.len)"))
        assertTrue(kotlinFacade.contains("createCleaner"))
        assertTrue(kotlinFacade.contains("AtomicLong"))
        assertTrue(kotlinFacade.contains("getAndSet(0L)"))
        assertTrue(kotlinFacade.contains("override fun close()"))
    }

    @Test
    fun generatesPrimitiveFreeFunctionArtifactsWithContainedPanicsAndStableSymbols() {
        val plan = primitiveFunctionPlan()
        val operation = plan.operations.single()
        val repeatedPlan = primitiveFunctionPlan()
        val changedOperation = operation.copy(rustPath = "kn_direct_fixture::other_add")

        val artifacts = RustInteropBridgeArtifactGenerator.generate(plan)
        val repeated = RustInteropBridgeArtifactGenerator.generate(repeatedPlan)
        val kotlinFacade = artifacts.kotlinFacades.values.single()
        val symbol = RustInteropBridgeSymbols.bindingSymbol(plan, operation)

        assertEquals(artifacts, repeated)
        assertTrue(symbol.matches(Regex("knri_v1_p[0-9a-f]{32}_b[0-9a-f]{16}")), symbol)
        assertEquals(
            symbol,
            RustInteropBridgeSymbols.bindingSymbol(
                repeatedPlan,
                repeatedPlan.operations.single(),
            ),
        )
        assertNotEquals(
            symbol,
            RustInteropBridgeSymbols.bindingSymbol(
                plan.copy(operations = listOf(changedOperation)),
                changedOperation,
            ),
        )

        assertContains(artifacts.cargoManifest, "kn-direct-fixture = { version = \"=0.1.0\" }")
        assertContains(
            artifacts.cHeader,
            "int32_t $symbol(int32_t left, int32_t right, int32_t *output, knri_utf8 *error);",
        )
        assertFalse("kn_direct_fixture::add" in artifacts.cHeader, "Rust paths must remain private")

        assertContains(artifacts.rustSource, "#[no_mangle]\npub unsafe extern \"C\" fn $symbol(")
        assertContains(artifacts.rustSource, "left: i32, right: i32, output: *mut i32, error: *mut KnriUtf8")
        assertContains(artifacts.rustSource, "if output.is_null()")
        assertContains(artifacts.rustSource, "*output = kn_direct_fixture::add(left, right);")
        assertEquals(1, artifacts.rustSource.split("catch_unwind(AssertUnwindSafe").size - 1)
        assertContains(
            artifacts.rustSource,
            "Err(payload) => report_failure(error, Failure { status: KNRI_PANIC, message: panic_message(payload) })",
        )

        assertContains(kotlinFacade, "public class FixtureException(message: String) : RuntimeException(message)")
        assertContains(kotlinFacade, "public fun add(left: Int, right: Int): Int = memScoped {")
        assertContains(kotlinFacade, "val output = alloc<IntVar>()")
        assertContains(kotlinFacade, "val status = $symbol(left, right, output.ptr, error.ptr)")
        assertContains(kotlinFacade, "throw FixtureException(knriMessage(error))")
        assertContains(kotlinFacade, "output.value")
        assertFalse("kn_direct_fixture::add" in kotlinFacade, "Rust paths must remain private")
        assertFalse("AtomicLong" in kotlinFacade, "Primitive facades must not import handle ownership support")
        assertFalse("createCleaner" in kotlinFacade, "Primitive facades must not import Cleaner support")
        assertFalse("HashMap" in artifacts.rustSource, "Primitive shims must not emit handle registries")
    }

    @Test
    fun rejectsUnsupportedPrimitiveFreeFunctionShapesWithOperationContext() {
        val plan = primitiveFunctionPlan()
        val operation = plan.operations.single()
        val unsupportedShapes = listOf(
            Triple(
                "Kotlin name",
                operation.copy(kotlinName = "PrimitiveFixture.add"),
                "requires a top-level Kotlin name without a receiver",
            ),
            Triple(
                "receiver",
                operation.copy(receiver = RustInteropReceiver(RustInteropReceiverOwnership.SHARED, "fixture")),
                "requires receiver 'none'",
            ),
            Triple(
                "parameter",
                operation.copy(parameters = listOf(RustInteropParameter("value", RustInteropBridgeType.Utf8String))),
                "has unsupported parameter 'value' of type 'string'; only primitive parameters are supported",
            ),
            Triple(
                "return",
                operation.copy(returnType = RustInteropBridgeType.Unit),
                "has unsupported return type 'unit'; only primitive returns are supported",
            ),
            Triple(
                "error policy",
                operation.copy(
                    errorPolicy = RustInteropErrorPolicy(RustInteropErrorMode.KOTLIN_EXCEPTION, "FixtureException")
                ),
                "requires error policy 'none'",
            ),
        )

        unsupportedShapes.forEach { unsupportedShape ->
            val description = unsupportedShape.first
            val unsupportedOperation = unsupportedShape.second
            val expectedMessage = unsupportedShape.third
            val failure = assertFailsWith<RustInteropBridgeGenerationException>(description) {
                RustInteropBridgeArtifactGenerator.generate(plan.copy(operations = listOf(unsupportedOperation)))
            }
            assertContains(failure.message.orEmpty(), "operation 'add'")
            assertContains(failure.message.orEmpty(), expectedMessage)
        }

        val asyncFailure = assertFailsWith<RustInteropBridgeGenerationException> {
            RustInteropBridgeArtifactGenerator.generate(
                plan.copy(
                    operations = listOf(
                        operation.copy(asyncPolicy = RustInteropAsyncPolicy.RUST_FUTURE)
                    )
                )
            )
        }
        assertContains(asyncFailure.message.orEmpty(), "operation 'add'")
        assertContains(asyncFailure.message.orEmpty(), "unsupported async shape 'rust-future'")
    }

    @Test
    fun rejectsUnsupportedOperationShapeWithOperationContext() {
        val plan = RustInteropTomlParser.parsePlan(REGEX_DEFINITION)
        val pattern = plan.operations.single { it.id == "pattern" }
        val unsupported = plan.copy(
            operations = plan.operations.map {
                if (it == pattern) it.copy(asyncPolicy = RustInteropAsyncPolicy.RUST_FUTURE) else it
            },
        )

        val failure = assertFailsWith<RustInteropBridgeGenerationException> {
            RustInteropBridgeArtifactGenerator.generate(unsupported)
        }

        assertTrue(failure.message.orEmpty().contains("pattern"))
        assertTrue(failure.message.orEmpty().contains("rust-future"))
    }

    private companion object {
        fun primitiveFunctionPlan(): RustInteropBridgePlan {
            val int32 = RustInteropBridgeType.Primitive(RustInteropPrimitive.INT32)
            return RustInteropBridgePlan(
                schemaVersion = RustInteropBridgePlanBuilder.SUPPORTED_SCHEMA_VERSION,
                kotlinPackage = "fixture.primitive",
                crate = RustInteropCrate(
                    name = "kn-direct-fixture",
                    version = "0.1.0",
                    features = emptyList(),
                    defaultFeatures = true,
                ),
                handles = emptyList(),
                operations = listOf(
                    RustInteropOperation(
                        id = "add",
                        kind = RustInteropOperationKind.FUNCTION,
                        rustPath = "kn_direct_fixture::add",
                        kotlinName = "add",
                        receiver = RustInteropReceiver(RustInteropReceiverOwnership.NONE, null),
                        parameters = listOf(
                            RustInteropParameter("left", int32),
                            RustInteropParameter("right", int32),
                        ),
                        returnType = int32,
                        errorPolicy = RustInteropErrorPolicy(RustInteropErrorMode.NONE, null),
                        panicPolicy = RustInteropPanicPolicy(
                            RustInteropPanicMode.KOTLIN_EXCEPTION,
                            "FixtureException",
                        ),
                        threading = RustInteropOperationThreading.CALLER,
                        asyncPolicy = RustInteropAsyncPolicy.SYNCHRONOUS,
                        targetPolicy = RustInteropTargetPolicy(emptyList(), emptyList()),
                    )
                ),
            )
        }

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

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

        val EXPECTED_CARGO_MANIFEST = """
            [package]
            name = "kotlin_rust_interop"
            version = "0.0.0"
            edition = "2021"
            publish = false

            [lib]
            crate-type = ["staticlib"]

            [dependencies]
            regex = { version = "=1.11.1", features = ["unicode"] }

            [profile.release]
            panic = "unwind"
        """.trimIndent() + "\n"
        const val EXPECTED_C_HEADER_SHA256 = "c5d3fd5dda2ffcc1385f8c900f7325205240f4bb5553d5710268d690f9289033"
        const val EXPECTED_RUST_SOURCE_SHA256 = "a6e130ddc8e87a14f4651dfb5577659f874b7d6f5857c1538b6bf5d738536ff2"
        const val EXPECTED_KOTLIN_PATH = "rust/regex/knri_dc70ff53756f.kt"
        const val EXPECTED_KOTLIN_FACADE_SHA256 = "9abdd6fa916d40e620207960be1614bfff41af0daa22b362f5b1b3357acf1f31"
    }
}
