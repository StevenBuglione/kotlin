/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class RustPrimitiveFieldReadIntegrationTest {
    @Test
    fun primitiveFieldReadUsesNativeLayoutAndSurvivesForcedGc() {
        val distributionPath = System.getenv(DISTRIBUTION_ENV)
        assumeTrue("Set $DISTRIBUTION_ENV to opt in to the Rust primitive field-read integration test", distributionPath != null)
        assumeTrue("The Rust primitive field-read integration fixture supports Linux x64 hosts only", isLinuxX64Host())

        val distribution = Paths.get(distributionPath!!)
        val compiler = distribution.resolve("bin/konanc")
        assertTrue(Files.isRegularFile(compiler), "Kotlin/Native compiler not found: $compiler")

        withTemporaryDirectory { directory ->
            val source = directory.resolve("primitive-field-read.kt").apply {
                writeText(
                    """
                        @file:OptIn(
                            kotlin.experimental.ExperimentalNativeApi::class,
                            kotlin.native.runtime.NativeRuntimeApi::class,
                        )

                        import kotlin.native.runtime.GC

                        class Holder(val prefix: Long, val value: Int)

                        private var forcedCount = 0

                        fun createHolder(): Holder = Holder(99L, 42)

                        fun forceGc() {
                            forcedCount++
                            repeat(3) { GC.collect() }
                        }

                        fun readPrimitiveFieldAcrossGc(): Int {
                            val holder = createHolder()
                            forceGc()
                            return holder.value
                        }

                        fun main() {
                            val value = readPrimitiveFieldAcrossGc()
                            println("${'$'}forcedCount:${'$'}value")
                        }
                    """.trimIndent()
                )
            }

            val profiles: List<Pair<String, List<String>>> = listOf("debug" to emptyList(), "opt" to listOf("-opt"))
            for (profileAndArguments in profiles) {
                val profile = profileAndArguments.first
                val extraArguments = profileAndArguments.second
                val llvmOutput = directory.resolve("primitive-field-read-llvm-$profile")
                val llvmCompilation = compile(compiler, source, llvmOutput, "llvm", extraArguments)
                assertEquals(0, llvmCompilation.exitCode, llvmCompilation.output)
                val llvmExecution = runProgram(executable(llvmOutput))
                assertEquals("1:42", llvmExecution.output.trim())

                val hybridOutputName = "primitive-field-read-hybrid-$profile"
                val hybridOutput = directory.resolve(hybridOutputName)
                val hybridCompilation = compile(compiler, source, hybridOutput, "rust-hybrid", extraArguments)
                assertEquals(0, hybridCompilation.exitCode, hybridCompilation.output)
                val hybridExecution = runProgram(executable(hybridOutput))
                assertEquals(llvmExecution.output, hybridExecution.output, "Hybrid behavior differs from LLVM in $profile mode")

                val workspace = directory.resolve(".kotlin-rust/$hybridOutputName")
                assertTrue(
                    Files.isRegularFile(workspace.resolve("rust-bitcode-preflight-ok")),
                    "Rust bitcode preflight did not complete for the primitive field-reading function",
                )
                val generatedSource = workspace.resolve("src/lib.rs")
                assertTrue(Files.isRegularFile(generatedSource), "Generated Rust source not found: $generatedSource")
                val generatedText = Files.readAllBytes(generatedSource).toString(StandardCharsets.UTF_8)
                assertContains(generatedText, "#[export_name = \"kfun:#readPrimitiveFieldAcrossGc")
                assertContains(generatedText, "#[link_name = \"kfun:#createHolder")
                val forceGcAlias = linkedFunctionAlias(generatedText, "kfun:#forceGc")

                val generatedFunction = exportedFunction(generatedText, "kfun:#readPrimitiveFieldAcrossGc")
                assertTrue(
                    Regex(
                        """let field_location = rooted_holder\.cast::<u8>\(\)\.add\(\d+usize\)\.cast::<i32>\(\);"""
                    ).containsMatchIn(generatedFunction),
                    "The primitive field read must use the compiler-derived Native byte offset and raw i32 layout:\n$generatedFunction",
                )
                assertOrdered(
                    generatedFunction,
                    "EnterFrame(frame",
                    "UpdateStackRef(holder_root, holder)",
                    "$forceGcAlias();",
                    "let rooted_holder = core::ptr::read(holder_root);",
                    "let field_location =",
                    "let result = core::ptr::read(field_location);",
                    "frame_guard.leave()",
                )
            }
        }
    }

    private fun compile(
        compiler: Path,
        source: Path,
        output: Path,
        mode: String,
        extraArguments: List<String>,
    ): ProcessResult = runProcess(
        compiler.absolutePathString(),
        source.absolutePathString(),
        "-target", "linux_x64",
        "-Xnative-codegen=$mode",
        *extraArguments.toTypedArray(),
        "-o", output.absolutePathString(),
    )

    private fun executable(output: Path): Path =
        output.resolveSibling(output.fileName.toString() + ".kexe")

    private fun runProgram(executable: Path): ProcessResult {
        assertTrue(Files.isExecutable(executable), "Executable not found: $executable")
        return runProcess(executable.absolutePathString()).also {
            assertEquals(0, it.exitCode, it.output)
        }
    }

    private fun linkedFunctionAlias(source: String, linkerNamePrefix: String): String {
        val linkName = source.indexOf("#[link_name = \"$linkerNamePrefix")
        assertTrue(linkName >= 0, "Generated Rust link '$linkerNamePrefix' was not found in:\n$source")
        val functionStart = source.indexOf("fn ", linkName)
        assertTrue(functionStart >= 0, "Generated Rust declaration for '$linkerNamePrefix' was not found in:\n$source")
        val functionEnd = source.indexOf('(', functionStart)
        assertTrue(functionEnd > functionStart, "Generated Rust declaration for '$linkerNamePrefix' is malformed:\n$source")
        return source.substring(functionStart + "fn ".length, functionEnd)
    }

    private fun exportedFunction(source: String, exportNamePrefix: String): String {
        val start = source.indexOf("#[export_name = \"$exportNamePrefix")
        assertTrue(start >= 0, "Generated Rust function '$exportNamePrefix' was not found in:\n$source")
        val nextExport = source.indexOf("#[export_name = \"", start + 1)
        return if (nextExport < 0) source.substring(start) else source.substring(start, nextExport)
    }

    private fun assertOrdered(text: String, vararg tokens: String) {
        var previous = -1
        for (token in tokens) {
            val current = text.indexOf(token, previous + 1)
            assertTrue(current > previous, "Expected '$token' after index $previous in:\n$text")
            previous = current
        }
    }

    private fun runProcess(vararg command: String): ProcessResult {
        val process = ProcessBuilder(command.toList())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.waitFor(), output)
    }

    private inline fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("kotlin-native-rust-primitive-field-read-test")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun isLinuxX64Host(): Boolean =
        System.getProperty("os.name").startsWith("Linux", ignoreCase = true) &&
                System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64")

    private data class ProcessResult(val exitCode: Int, val output: String)

    private companion object {
        const val DISTRIBUTION_ENV = "KOTLIN_NATIVE_RUST_TEST_DIST"
    }
}
