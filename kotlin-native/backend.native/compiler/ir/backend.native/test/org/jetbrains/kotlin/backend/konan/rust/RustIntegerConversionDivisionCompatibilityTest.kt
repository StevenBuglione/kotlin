/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import org.junit.Assume.assumeTrue
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RustIntegerConversionDivisionCompatibilityTest {
    @Test
    fun conversionsAndConstantNonzeroDivisionMatchLlvmAtEdgeValues() {
        val compiler = compilerForLinuxX64()

        withTemporaryDirectory { directory ->
            val source = directory.resolve("integer-conversion-division.kt").apply {
                writeText(
                    """
                        fun widen(value: Int): Long = value.toLong()

                        fun narrow(value: Long): Int = value.toInt()

                        fun intDivThree(value: Int): Int = value / 3

                        fun intRemThree(value: Int): Int = value % 3

                        fun intDivMinusOne(value: Int): Int = value / -1

                        fun intRemMinusOne(value: Int): Int = value % -1

                        fun longDivThree(value: Long): Long = value / 3L

                        fun longRemThree(value: Long): Long = value % 3L

                        fun longDivMinusOne(value: Long): Long = value / -1L

                        fun longRemMinusOne(value: Long): Long = value % -1L

                        fun main() {
                            println(widen(Int.MIN_VALUE))
                            println(widen(Int.MAX_VALUE))
                            println(narrow(Long.MIN_VALUE))
                            println(narrow(Long.MAX_VALUE))
                            println(intDivThree(Int.MIN_VALUE))
                            println(intRemThree(Int.MIN_VALUE))
                            println(intDivMinusOne(Int.MIN_VALUE))
                            println(intRemMinusOne(Int.MIN_VALUE))
                            println(longDivThree(Long.MIN_VALUE))
                            println(longRemThree(Long.MIN_VALUE))
                            println(longDivMinusOne(Long.MIN_VALUE))
                            println(longRemMinusOne(Long.MIN_VALUE))
                        }
                    """.trimIndent()
                )
            }
            val expectedLines = listOf(
                "-2147483648",
                "2147483647",
                "0",
                "-1",
                "-715827882",
                "-2",
                "-2147483648",
                "0",
                "-3074457345618258602",
                "-2",
                "-9223372036854775808",
                "0",
            )

            for (profileAndArguments in PROFILES) {
                val profile = profileAndArguments.first
                val extraArguments = profileAndArguments.second
                val llvmOutput = directory.resolve("integer-conversion-division-llvm-$profile")
                val llvmCompilation = compile(compiler, source, llvmOutput, "llvm", extraArguments)
                assertEquals(0, llvmCompilation.exitCode, llvmCompilation.output)
                val llvmExecution = runProgram(executable(llvmOutput))
                assertEquals(expectedLines, outputLines(llvmExecution.output))

                val hybridOutputName = "integer-conversion-division-hybrid-$profile"
                val hybridOutput = directory.resolve(hybridOutputName)
                val hybridCompilation = compile(compiler, source, hybridOutput, "rust-hybrid", extraArguments)
                assertEquals(0, hybridCompilation.exitCode, hybridCompilation.output)
                assertEquals(llvmExecution.output, runProgram(executable(hybridOutput)).output)

                val workspace = directory.resolve(".kotlin-rust/$hybridOutputName")
                assertPreflightSucceeded(workspace)
                val generatedSource = Files.readAllBytes(workspace.resolve("src/lib.rs")).toString(StandardCharsets.UTF_8)
                assertGeneratedBodyContains(generatedSource, "widen", "value_0 as i64")
                assertGeneratedBodyContains(generatedSource, "narrow", "value_0 as i32")
                listOf("intDivThree", "intDivMinusOne", "longDivThree", "longDivMinusOne").forEach {
                    assertGeneratedBodyContains(generatedSource, it, ").wrapping_div(")
                }
                listOf("intRemThree", "intRemMinusOne", "longRemThree", "longRemMinusOne").forEach {
                    assertGeneratedBodyContains(generatedSource, it, ").wrapping_rem(")
                }
            }
        }
    }

    @Test
    fun variableAndZeroDivisorsRemainLlvmFallbacksWithStrictDiagnostics() {
        val compiler = compilerForLinuxX64()

        withTemporaryDirectory { directory ->
            val source = directory.resolve("integer-divisor-fallback.kt").apply {
                writeText(
                    """
                        fun generatedLeaf(value: Int): Int = value + 1

                        fun variableIntDiv(value: Int, divisor: Int): Int = value / divisor

                        fun variableLongRem(value: Long, divisor: Long): Long = value % divisor

                        fun zeroIntDiv(value: Int): Int = value / 0

                        fun zeroLongRem(value: Long): Long = value % 0L

                        fun main() {
                            println(generatedLeaf(40))
                            println(variableIntDiv(20, 4))
                            println(variableLongRem(20L, 6L))
                            try {
                                zeroIntDiv(1)
                                println("missing Int exception")
                            } catch (_: ArithmeticException) {
                                println("Int zero")
                            }
                            try {
                                zeroLongRem(1L)
                                println("missing Long exception")
                            } catch (_: ArithmeticException) {
                                println("Long zero")
                            }
                        }
                    """.trimIndent()
                )
            }
            val expectedLines = listOf("41", "5", "2", "Int zero", "Long zero")

            for (profileAndArguments in PROFILES) {
                val profile = profileAndArguments.first
                val extraArguments = profileAndArguments.second
                val llvmOutput = directory.resolve("integer-divisor-fallback-llvm-$profile")
                val llvmCompilation = compile(compiler, source, llvmOutput, "llvm", extraArguments)
                assertEquals(0, llvmCompilation.exitCode, llvmCompilation.output)
                val llvmExecution = runProgram(executable(llvmOutput))
                assertEquals(expectedLines, outputLines(llvmExecution.output))

                val hybridOutputName = "integer-divisor-fallback-hybrid-$profile"
                val hybridOutput = directory.resolve(hybridOutputName)
                val hybridCompilation = compile(compiler, source, hybridOutput, "rust-hybrid", extraArguments)
                assertEquals(0, hybridCompilation.exitCode, hybridCompilation.output)
                assertEquals(llvmExecution.output, runProgram(executable(hybridOutput)).output)

                val workspace = directory.resolve(".kotlin-rust/$hybridOutputName")
                assertPreflightSucceeded(workspace)
                val generatedSource = Files.readAllBytes(workspace.resolve("src/lib.rs")).toString(StandardCharsets.UTF_8)
                assertGeneratedBodyContains(generatedSource, "generatedLeaf", ").wrapping_add(")
                listOf("variableIntDiv", "variableLongRem", "zeroIntDiv", "zeroLongRem").forEach {
                    assertLlvmFallback(generatedSource, it)
                }
            }

            val strictCompilation = compile(
                compiler,
                source,
                directory.resolve("integer-divisor-fallback-strict"),
                "rust-strict",
                emptyList(),
            )
            assertNotEquals(0, strictCompilation.exitCode)
            assertContains(strictCompilation.output, "rust strict backend cannot lower the reachable Kotlin program")
            assertStrictDiagnostic(
                strictCompilation.output,
                "variableIntDiv",
                "Integer division requires Kotlin exception interop",
            )
            assertStrictDiagnostic(
                strictCompilation.output,
                "variableLongRem",
                "Integer remainder requires Kotlin exception interop",
            )
            assertStrictDiagnostic(
                strictCompilation.output,
                "zeroIntDiv",
                "Integer division requires Kotlin exception interop",
            )
            assertStrictDiagnostic(
                strictCompilation.output,
                "zeroLongRem",
                "Integer remainder requires Kotlin exception interop",
            )
            assertContains(strictCompilation.output, source.fileName.toString())
        }
    }

    private fun assertPreflightSucceeded(workspace: Path) {
        val marker = workspace.resolve("rust-bitcode-preflight-ok")
        assertTrue(
            Files.isRegularFile(marker) && Files.size(marker) > 0L,
            "Rust bitcode ABI preflight did not complete: $marker",
        )
    }

    private fun assertGeneratedBodyContains(source: String, functionName: String, expected: String) {
        val body = exportedFunction(source, "kfun:#$functionName")
        assertContains(body, expected)
        assertTrue("__llvm" !in body, "Function '$functionName' unexpectedly fell back to LLVM:\n$body")
    }

    private fun assertLlvmFallback(source: String, functionName: String) {
        val declaration = linkedFallback(source, "kfun:#$functionName")
        assertContains(declaration, "__llvm")
    }

    private fun exportedFunction(source: String, exportNamePrefix: String): String {
        val start = source.indexOf("#[export_name = \"$exportNamePrefix")
        assertTrue(start >= 0, "Generated Rust function '$exportNamePrefix' was not found in:\n$source")
        val nextExport = source.indexOf("#[export_name = \"", start + 1)
        return if (nextExport < 0) source.substring(start) else source.substring(start, nextExport)
    }

    private fun linkedFallback(source: String, linkNamePrefix: String): String {
        val start = source.indexOf("#[link_name = \"$linkNamePrefix")
        assertTrue(start >= 0, "LLVM fallback '$linkNamePrefix' was not found in:\n$source")
        val end = source.indexOf(";\n", start)
        assertTrue(end >= 0, "LLVM fallback '$linkNamePrefix' has no declaration terminator")
        return source.substring(start, end + 1)
    }

    private fun assertStrictDiagnostic(output: String, functionName: String, message: String) {
        assertContains(output, "[UNSUPPORTED_CALL] $functionName: $message")
    }

    private fun compilerForLinuxX64(): Path {
        val distributionPath = System.getenv(DISTRIBUTION_ENV)
        assumeTrue("Set $DISTRIBUTION_ENV to opt in to the Rust integer integration test", distributionPath != null)
        assumeTrue("The Rust integer integration fixture supports Linux x64 hosts only", isLinuxX64Host())
        return Paths.get(distributionPath!!).resolve("bin/konanc").also {
            assertTrue(Files.isRegularFile(it), "Kotlin/Native compiler not found: $it")
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

    private fun executable(output: Path): Path = output.resolveSibling(output.fileName.toString() + ".kexe")

    private fun runProgram(executable: Path): ProcessResult {
        assertTrue(Files.isExecutable(executable), "Executable not found: $executable")
        return runProcess(executable.absolutePathString()).also {
            assertEquals(0, it.exitCode, it.output)
        }
    }

    private fun outputLines(output: String): List<String> = output.lineSequence().filter(String::isNotBlank).toList()

    private fun runProcess(vararg command: String): ProcessResult {
        val process = ProcessBuilder(command.toList()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.waitFor(), output)
    }

    private inline fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("kotlin-native-rust-integer-test")
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
        val PROFILES = listOf("debug" to emptyList(), "opt" to listOf("-opt"))
    }
}
