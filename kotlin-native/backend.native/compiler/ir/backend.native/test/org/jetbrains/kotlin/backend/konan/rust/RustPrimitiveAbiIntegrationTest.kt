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

class RustPrimitiveAbiIntegrationTest {
    @Test
    fun booleanFloatAndDoubleMatchLlvmAbi() {
        val distributionPath = System.getenv(DISTRIBUTION_ENV)
        assumeTrue("Set $DISTRIBUTION_ENV to opt in to the Rust primitive ABI integration test", distributionPath != null)
        assumeTrue("The Rust primitive ABI integration fixture supports Linux x64 hosts only", isLinuxX64Host())

        val compiler = Paths.get(distributionPath!!).resolve("bin/konanc")
        assertTrue(Files.isRegularFile(compiler), "Kotlin/Native compiler not found: $compiler")

        withTemporaryDirectory { directory ->
            val source = directory.resolve("primitive-abi.kt").apply {
                writeText(
                    """
                        fun booleanRoundTrip(value: Boolean): Boolean = value

                        fun floatRoundTrip(value: Float): Float = value

                        fun doubleRoundTrip(value: Double): Double = value

                        fun main() {
                            println(booleanRoundTrip(true))
                            println(booleanRoundTrip(false))
                            println(floatRoundTrip(1.25f))
                            println(doubleRoundTrip(-2.5))
                        }
                    """.trimIndent()
                )
            }

            val profiles: List<Pair<String, List<String>>> = listOf("debug" to emptyList(), "opt" to listOf("-opt"))
            for (profileAndArguments in profiles) {
                val profile = profileAndArguments.first
                val extraArguments = profileAndArguments.second
                val llvmOutput = directory.resolve("primitive-abi-llvm-$profile")
                val llvmCompilation = compile(compiler, source, llvmOutput, "llvm", extraArguments)
                assertEquals(0, llvmCompilation.exitCode, llvmCompilation.output)
                val llvmExecution = runProgram(executable(llvmOutput))
                assertEquals(listOf("true", "false", "1.25", "-2.5"), outputLines(llvmExecution.output))

                val hybridOutputName = "primitive-abi-hybrid-$profile"
                val hybridOutput = directory.resolve(hybridOutputName)
                val hybridCompilation = compile(compiler, source, hybridOutput, "rust-hybrid", extraArguments)
                assertEquals(0, hybridCompilation.exitCode, hybridCompilation.output)
                val hybridExecution = runProgram(executable(hybridOutput))
                assertEquals(llvmExecution.output, hybridExecution.output, "Hybrid behavior differs from LLVM in $profile mode")

                val workspace = directory.resolve(".kotlin-rust/$hybridOutputName")
                val preflightMarker = workspace.resolve("rust-bitcode-preflight-ok")
                assertTrue(
                    Files.isRegularFile(preflightMarker) && Files.size(preflightMarker) > 0L,
                    "Rust bitcode ABI preflight did not complete in $profile mode: $preflightMarker",
                )
                val generatedSource = workspace.resolve("src/lib.rs")
                assertTrue(Files.isRegularFile(generatedSource), "Generated Rust source not found: $generatedSource")
                val generatedText = Files.readAllBytes(generatedSource).toString(StandardCharsets.UTF_8)
                assertPrimitiveSignature(generatedText, "booleanRoundTrip", "bool")
                assertPrimitiveSignature(generatedText, "floatRoundTrip", "f32")
                assertPrimitiveSignature(generatedText, "doubleRoundTrip", "f64")
            }
        }
    }

    private fun assertPrimitiveSignature(source: String, functionName: String, rustType: String) {
        val function = exportedFunction(source, "kfun:#$functionName")
        assertContains(function, "value_0: $rustType")
        assertTrue(
            Regex("""\) -> $rustType\s*\{""").containsMatchIn(function),
            "Generated Rust function '$functionName' does not return $rustType:\n$function",
        )
    }

    private fun exportedFunction(source: String, exportNamePrefix: String): String {
        val start = source.indexOf("#[export_name = \"$exportNamePrefix")
        assertTrue(start >= 0, "Generated Rust function '$exportNamePrefix' was not found in:\n$source")
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
        val directory = Files.createTempDirectory("kotlin-native-rust-primitive-abi-test")
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
