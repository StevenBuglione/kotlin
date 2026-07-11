/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import org.jetbrains.kotlin.native.interop.rust.RustInteropAsyncPolicy
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgePlan
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgePlanRenderer
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgeSymbols
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgeType
import org.jetbrains.kotlin.native.interop.rust.RustInteropCrate
import org.jetbrains.kotlin.native.interop.rust.RustInteropErrorMode
import org.jetbrains.kotlin.native.interop.rust.RustInteropErrorPolicy
import org.jetbrains.kotlin.native.interop.rust.RustInteropOperation
import org.jetbrains.kotlin.native.interop.rust.RustInteropOperationKind
import org.jetbrains.kotlin.native.interop.rust.RustInteropOperationThreading
import org.jetbrains.kotlin.native.interop.rust.RustInteropPanicMode
import org.jetbrains.kotlin.native.interop.rust.RustInteropPanicPolicy
import org.jetbrains.kotlin.native.interop.rust.RustInteropParameter
import org.jetbrains.kotlin.native.interop.rust.RustInteropPrimitive
import org.jetbrains.kotlin.native.interop.rust.RustInteropReceiver
import org.jetbrains.kotlin.native.interop.rust.RustInteropReceiverOwnership
import org.jetbrains.kotlin.native.interop.rust.RustInteropTargetPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RustDirectInteropIntegrationTest {
    @Test
    fun hybridCallsRegistryCrateInsteadOfKotlinFallback() {
        if (!System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) return
        val distribution = System.getenv(DISTRIBUTION_ENV)?.let(Paths::get) ?: return
        val compiler = distribution.resolve("bin/konanc")
        assertTrue(Files.isRegularFile(compiler), "Kotlin/Native compiler not found: $compiler")

        withTemporaryDirectory { directory ->
            val operation = operation()
            val plan = RustInteropBridgePlan(
                schemaVersion = 1,
                kotlinPackage = "rust.libm",
                crate = RustInteropCrate("libm", LIBM_VERSION, emptyList(), true),
                handles = emptyList(),
                operations = listOf(operation),
            )
            val bindingSymbol = RustInteropBridgeSymbols.bindingSymbol(plan, operation)
            val planFile = directory.resolve("bridge-plan.json").apply {
                writeText(RustInteropBridgePlanRenderer.render(plan))
            }
            val source = directory.resolve("direct.kt").apply {
                writeText(
                    """
                        package rust.libm

                        private fun $bindingSymbol(value: Double): Double = -999.0

                        fun sqrt(value: Double): Double = $bindingSymbol(value)

                        fun directValue(value: Double): Double = sqrt(value)

                        fun main() {
                            println(directValue(81.0))
                        }
                    """.trimIndent()
                )
            }

            val llvmOutput = directory.resolve("direct-llvm")
            assertEquals(0, compile(compiler, source, planFile, llvmOutput, "llvm").exitCode)
            assertEquals("-999.0", runProgram(llvmOutput.resolveSibling("direct-llvm.kexe")).trim())

            val hybridOutput = directory.resolve("direct-hybrid")
            val hybridCompilation = compile(compiler, source, planFile, hybridOutput, "rust-hybrid")
            assertEquals(0, hybridCompilation.exitCode, hybridCompilation.output)
            assertEquals("9.0", runProgram(hybridOutput.resolveSibling("direct-hybrid.kexe")).trim())

            val workspace = directory.resolve(".kotlin-rust/direct-hybrid")
            val rustSource = workspace.resolve("src/lib.rs").readText()
            assertContains(rustSource, "libm::sqrt")
            assertContains(rustSource, "std::panic::catch_unwind")
            assertContains(workspace.resolve("Cargo.toml").readText(), "libm = { version = \"=$LIBM_VERSION\"")
            assertTrue(
                Files.walk(workspace.resolve("target")).use { files ->
                    files.anyMatch { it.fileName.toString() == "libkotlin_native_rust_module.a" }
                },
                "Cargo did not emit the compiler-owned Rust dependency archive",
            )
        }
    }

    private fun compile(compiler: Path, source: Path, plan: Path, output: Path, mode: String): ProcessResult = runProcess(
        compiler.absolutePathString(),
        source.absolutePathString(),
        "-target", "linux_x64",
        "-entry", "rust.libm.main",
        "-Xnative-codegen=$mode",
        "-Xrust-interop-bridge-plan=${plan.absolutePathString()}",
        "-o", output.absolutePathString(),
    )

    private fun runProgram(executable: Path): String {
        assertTrue(Files.isExecutable(executable), "Executable not found: $executable")
        val result = runProcess(executable.absolutePathString())
        assertEquals(0, result.exitCode, result.output)
        return result.output
    }

    private fun runProcess(vararg command: String): ProcessResult {
        val process = ProcessBuilder(command.toList()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.waitFor(), output)
    }

    private inline fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("kotlin-native-rust-direct-interop-test")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun operation() = RustInteropOperation(
        id = "sqrt",
        kind = RustInteropOperationKind.FUNCTION,
        rustPath = "libm::sqrt",
        kotlinName = "sqrt",
        receiver = RustInteropReceiver(RustInteropReceiverOwnership.NONE, null),
        parameters = listOf(RustInteropParameter("value", RustInteropBridgeType.Primitive(RustInteropPrimitive.FLOAT64))),
        returnType = RustInteropBridgeType.Primitive(RustInteropPrimitive.FLOAT64),
        errorPolicy = RustInteropErrorPolicy(RustInteropErrorMode.NONE, null),
        panicPolicy = RustInteropPanicPolicy(RustInteropPanicMode.ABORT, null),
        threading = RustInteropOperationThreading.CALLER,
        asyncPolicy = RustInteropAsyncPolicy.SYNCHRONOUS,
        targetPolicy = RustInteropTargetPolicy(emptyList(), emptyList()),
    )

    private data class ProcessResult(val exitCode: Int, val output: String)

    private companion object {
        const val DISTRIBUTION_ENV = "KOTLIN_NATIVE_RUST_TEST_DIST"
        const val LIBM_VERSION = "0.2.15"
    }
}
