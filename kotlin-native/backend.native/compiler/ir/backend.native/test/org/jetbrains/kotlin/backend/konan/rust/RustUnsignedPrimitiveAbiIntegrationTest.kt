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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RustUnsignedPrimitiveAbiIntegrationTest {
    @Test
    fun uintAndUlongRoundTripAcrossLlvmAndRust() {
        val distributionPath = System.getenv(DISTRIBUTION_ENV)
        assumeTrue("Set $DISTRIBUTION_ENV to opt in to the Rust unsigned ABI integration test", distributionPath != null)
        assumeTrue("The Rust unsigned ABI integration fixture supports Linux x64 hosts only", isLinuxX64Host())
        val compiler = Paths.get(distributionPath!!).resolve("bin/konanc")
        assertTrue(Files.isRegularFile(compiler), "Kotlin/Native compiler not found: $compiler")

        withTemporaryDirectory { directory ->
            val source = directory.resolve("unsigned-primitive-abi.kt").apply {
                writeText(
                    """
                        fun uintIdentity(value: UInt): UInt = value

                        fun ulongIdentity(value: ULong): ULong = value

                        fun uintDivByThree(value: UInt): UInt = value / 3u

                        fun ulongDivByThree(value: ULong): ULong = value / 3uL

                        fun nullableUIntIdentity(value: UInt?): UInt? = value

                        fun boxedUIntIdentity(value: Any): Any = value

                        fun main() {
                            println(uintIdentity(0u))
                            println(uintIdentity(UInt.MAX_VALUE))
                            println(ulongIdentity(0uL))
                            println(ulongIdentity(ULong.MAX_VALUE))
                            println(uintDivByThree(UInt.MAX_VALUE))
                            println(ulongDivByThree(ULong.MAX_VALUE))
                            println(nullableUIntIdentity(7u))
                            println(boxedUIntIdentity(9u))
                        }
                    """.trimIndent()
                )
            }
            val expectedLines = listOf(
                "0",
                "4294967295",
                "0",
                "18446744073709551615",
                "1431655765",
                "6148914691236517205",
                "7",
                "9",
            )

            for (profileAndArguments in PROFILES) {
                val profile = profileAndArguments.first
                val extraArguments = profileAndArguments.second
                val llvmOutput = directory.resolve("unsigned-primitive-abi-llvm-$profile")
                val llvmCompilation = compile(compiler, source, llvmOutput, "llvm", extraArguments)
                assertEquals(0, llvmCompilation.exitCode, llvmCompilation.output)
                val llvmExecution = runProgram(executable(llvmOutput))
                assertEquals(expectedLines, outputLines(llvmExecution.output))

                val hybridOutputName = "unsigned-primitive-abi-hybrid-$profile"
                val hybridOutput = directory.resolve(hybridOutputName)
                val hybridCompilation = compile(compiler, source, hybridOutput, "rust-hybrid", extraArguments)
                assertEquals(0, hybridCompilation.exitCode, hybridCompilation.output)
                assertEquals(llvmExecution.output, runProgram(executable(hybridOutput)).output)

                val workspace = directory.resolve(".kotlin-rust/$hybridOutputName")
                val marker = workspace.resolve("rust-bitcode-preflight-ok")
                assertTrue(
                    Files.isRegularFile(marker) && Files.size(marker) > 0L,
                    "Rust bitcode ABI preflight did not complete in $profile mode: $marker",
                )
                assertEquals(
                    "rust-boundary-normalized.bc",
                    Files.readAllBytes(marker).toString(StandardCharsets.UTF_8).trim(),
                    "Hybrid final linking did not consume the ABI-normalized Rust module in $profile mode",
                )
                val generatedSource = Files.readAllBytes(workspace.resolve("src/lib.rs")).toString(StandardCharsets.UTF_8)
                assertUnsignedSignature(generatedSource, "uintIdentity", "kotlin.UInt", "u32")
                assertUnsignedSignature(generatedSource, "ulongIdentity", "kotlin.ULong", "u64")
                assertLlvmFallback(generatedSource, "uintDivByThree", "kotlin.UInt")
                assertLlvmFallback(generatedSource, "ulongDivByThree", "kotlin.ULong")
                assertFalse("nullableUIntIdentity" in generatedSource)
                assertFalse("boxedUIntIdentity" in generatedSource)
            }

            val strictCompilation = compile(
                compiler,
                source,
                directory.resolve("unsigned-primitive-abi-strict"),
                "rust-strict",
                emptyList(),
            )
            assertNotEquals(0, strictCompilation.exitCode)
            assertContains(strictCompilation.output, "rust strict backend cannot lower the reachable Kotlin program")
            assertContains(
                strictCompilation.output,
                "[UNSUPPORTED_TYPE] nullableUIntIdentity: Nullable types are not supported",
            )
            assertContains(strictCompilation.output, "[UNSUPPORTED_TYPE] boxedUIntIdentity: Unsupported Kotlin type")
            assertContains(strictCompilation.output, source.fileName.toString())
        }
    }

    private fun assertUnsignedSignature(source: String, functionName: String, kotlinType: String, rustType: String) {
        val body = exportedFunction(source, functionName, kotlinType)
        assertContains(body, "value_0: $rustType")
        assertTrue(
            Regex("""\) -> $rustType\s*\{""").containsMatchIn(body),
            "Generated Rust function '$functionName' does not return $rustType:\n$body",
        )
        assertTrue("__llvm" !in body, "Function '$functionName' unexpectedly fell back to LLVM:\n$body")
    }

    private fun assertLlvmFallback(source: String, functionName: String, kotlinType: String) {
        val linkNamePrefix = "kfun:#$functionName($kotlinType){}$kotlinType"
        val start = source.indexOf("#[link_name = \"$linkNamePrefix")
        assertTrue(start >= 0, "LLVM fallback '$linkNamePrefix' was not found in:\n$source")
        val end = source.indexOf(";\n", start)
        assertTrue(end >= 0, "LLVM fallback '$linkNamePrefix' has no declaration terminator")
        assertContains(source.substring(start, end + 1), "__llvm")
    }

    private fun exportedFunction(source: String, functionName: String, kotlinType: String): String {
        val exportNamePrefix = "kfun:#$functionName($kotlinType){}$kotlinType"
        val start = source.indexOf("#[export_name = \"$exportNamePrefix")
        assertTrue(start >= 0, "Generated Rust symbol '$exportNamePrefix' was not found in:\n$source")
        val nextExport = source.indexOf("#[export_name = \"", start + 1)
        return if (nextExport < 0) source.substring(start) else source.substring(start, nextExport)
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
        val directory = Files.createTempDirectory("kotlin-native-rust-unsigned-abi-test")
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
