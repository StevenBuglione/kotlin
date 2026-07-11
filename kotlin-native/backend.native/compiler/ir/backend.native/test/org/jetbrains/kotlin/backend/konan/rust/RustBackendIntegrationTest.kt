/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

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

class RustBackendIntegrationTest {
    @Test
    fun strictAndHybridPrograms() {
        val distribution = System.getenv(DISTRIBUTION_ENV)?.let(Paths::get) ?: return
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val compiler = distribution.resolve("bin").resolve(if (windows) "konanc.bat" else "konanc")
        assertTrue(Files.isRegularFile(compiler), "Kotlin/Native compiler not found: $compiler")

        withTemporaryDirectory { directory ->
            val strictSource = directory.resolve("strict.kt").apply {
                writeText(
                    """
                        fun twice(x: Int) = x * 2

                        fun main() {
                            var value = 20
                            if (value > 0) value++
                            println(twice(value))
                        }
                    """.trimIndent()
                )
            }
            val strictOutput = directory.resolve("strict")
            val strictCompilation = compile(compiler, strictSource, strictOutput, "rust-strict", windows)
            assertEquals(0, strictCompilation.exitCode, strictCompilation.output)
            assertEquals("42", runProgram(executable(strictOutput, windows)).output.trim())

            val unsupportedSource = directory.resolve("unsupported.kt").apply {
                writeText(
                    """
                        class Box(val value: Int)

                        fun main() {
                            println(Box(42).value)
                        }
                    """.trimIndent()
                )
            }
            val hybridOutput = directory.resolve("hybrid")
            val hybridCompilation = compile(compiler, unsupportedSource, hybridOutput, "rust-hybrid", windows)
            assertEquals(0, hybridCompilation.exitCode, hybridCompilation.output)
            assertEquals("42", runProgram(executable(hybridOutput, windows)).output.trim())

            val unsupportedCompilation = compile(
                compiler,
                unsupportedSource,
                directory.resolve("unsupported-strict"),
                "rust-strict",
                windows,
            )
            assertNotEquals(0, unsupportedCompilation.exitCode)
            assertContains(unsupportedCompilation.output, "rust strict backend cannot lower")
            assertContains(unsupportedCompilation.output, "UNSUPPORTED_CALL")
            assertContains(unsupportedCompilation.output, unsupportedSource.fileName.toString())

            val initializerSource = directory.resolve("initializer.kt").apply {
                writeText(
                    """
                        val initialized = run {
                            println("init")
                            1
                        }

                        fun main() {
                            println(42)
                        }
                    """.trimIndent()
                )
            }
            val initializerHybridOutput = directory.resolve("initializer-hybrid")
            val initializerHybridCompilation = compile(
                compiler,
                initializerSource,
                initializerHybridOutput,
                "rust-hybrid",
                windows,
            )
            assertEquals(0, initializerHybridCompilation.exitCode, initializerHybridCompilation.output)
            val initializerOutput = runProgram(executable(initializerHybridOutput, windows)).output
                .lineSequence()
                .filter(String::isNotBlank)
                .toList()
            assertEquals(
                listOf("init", "42"),
                initializerOutput,
            )

            val initializerStrictCompilation = compile(
                compiler,
                initializerSource,
                directory.resolve("initializer-strict"),
                "rust-strict",
                windows,
            )
            assertNotEquals(0, initializerStrictCompilation.exitCode)
            assertContains(initializerStrictCompilation.output, "rust strict backend cannot lower")
            assertContains(initializerStrictCompilation.output, initializerSource.fileName.toString())
        }
    }

    private fun compile(
        compiler: Path,
        source: Path,
        output: Path,
        mode: String,
        windows: Boolean,
    ): ProcessResult = runProcess(
        compiler.absolutePathString(),
        source.absolutePathString(),
        "-target", if (windows) "mingw_x64" else "linux_x64",
        "-Xnative-codegen=$mode",
        "-o", output.absolutePathString(),
    )

    private fun executable(output: Path, windows: Boolean): Path =
        output.resolveSibling(output.fileName.toString() + (if (windows) ".exe" else ".kexe"))

    private fun runProgram(executable: Path): ProcessResult {
        assertTrue(Files.isExecutable(executable), "Executable not found: $executable")
        return runProcess(executable.absolutePathString()).also {
            assertEquals(0, it.exitCode, it.output)
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
        val directory = Files.createTempDirectory("kotlin-native-rust-backend-test")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private data class ProcessResult(val exitCode: Int, val output: String)

    private companion object {
        const val DISTRIBUTION_ENV = "KOTLIN_NATIVE_RUST_TEST_DIST"
    }
}
