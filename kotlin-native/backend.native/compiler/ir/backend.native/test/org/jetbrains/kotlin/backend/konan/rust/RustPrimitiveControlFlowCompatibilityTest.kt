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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RustPrimitiveControlFlowCompatibilityTest {
    @Test
    fun primitiveControlFlowKeepsUnsupportedLeafOperationsOnLlvm() {
        val distribution = System.getenv(DISTRIBUTION_ENV)?.let(Paths::get) ?: return
        val hostOs = System.getProperty("os.name")
        val windows = hostOs.startsWith("Windows", ignoreCase = true)
        if (!windows && !hostOs.startsWith("Linux", ignoreCase = true)) return
        val compiler = distribution.resolve("bin").resolve(if (windows) "konanc.bat" else "konanc")
        assertTrue(Files.isRegularFile(compiler), "Kotlin/Native compiler not found: $compiler")

        withTemporaryDirectory { directory ->
            val source = directory.resolve("primitive-gaps.kt").apply {
                writeText(
                    """
                        fun floatControl(seed: Float): Float {
                            var value = seed
                            var count = 0
                            while (count < 2) {
                                if (value > 0.0f) value += 1.0f else value -= 1.0f
                                count++
                            }
                            return value
                        }

                        fun doubleControl(seed: Double): Double =
                            if (seed > 0.0) seed + 1.0 else seed - 1.0

                        fun integerDivision(value: Int): Int = 42 / value

                        fun widen(value: Int): Long = value.toLong()

                        fun rangeSum(limit: Int): Int {
                            var sum = 0
                            for (index in 0 until limit) sum += index
                            return sum
                        }

                        fun main() {
                            println(floatControl(1.5f))
                            println(doubleControl(2.25))
                            println(integerDivision(2))
                            println(widen(42))
                            println(rangeSum(4))
                        }
                    """.trimIndent()
                )
            }

            val hybridOutput = directory.resolve("primitive-gaps-hybrid")
            val hybridCompilation = compile(compiler, source, hybridOutput, "rust-hybrid", windows)
            assertEquals(0, hybridCompilation.exitCode, hybridCompilation.output)
            assertEquals(
                listOf("3.5", "3.25", "21", "42", "6"),
                runProgram(executable(hybridOutput, windows)).output.lineSequence().filter(String::isNotBlank).toList(),
            )
            val generatedSource = directory.resolve(".kotlin-rust/primitive-gaps-hybrid/src/lib.rs")
            assertTrue(Files.isRegularFile(generatedSource), "Generated Rust source not found: $generatedSource")
            val generatedText = Files.readAllBytes(generatedSource).toString(StandardCharsets.UTF_8)
            assertContains(generatedText, "floatControl")
            assertContains(generatedText, "doubleControl")
            assertContains(generatedText, "integerDivision")
            assertContains(generatedText, "widen")
            assertContains(generatedText, "rangeSum")
            assertContains(generatedText, "__llvm")

            val strictCompilation = compile(
                compiler,
                source,
                directory.resolve("primitive-gaps-strict"),
                "rust-strict",
                windows,
            )
            assertNotEquals(0, strictCompilation.exitCode)
            assertContains(strictCompilation.output, "rust strict backend cannot lower the reachable Kotlin program")
            assertContains(strictCompilation.output, "UNSUPPORTED_TYPE")
            assertContains(strictCompilation.output, "println supports only primitive values and string literals")
            assertContains(strictCompilation.output, "UNSUPPORTED_CALL")
            assertContains(strictCompilation.output, "Integer division requires Kotlin exception interop")
            assertContains(strictCompilation.output, source.fileName.toString())
        }
    }

    @Test
    fun programAndBitcodeCompilersRunClippyBeforeRustcWithWarningsDenied() {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return

        withTemporaryDirectory { directory ->
            val commandLog = directory.resolve("cargo-commands.txt")
            val cargo = fakeCargo(directory, commandLog)
            val target = "x86_64-unknown-linux-gnu"

            val programWorkspace = RustCargoWorkspaceEmitter.emit(
                RustCargoWorkspaceSpec(
                    packageName = "contract_program",
                    targetTriple = target,
                    mainRs = "fn main() {}",
                    outputDirectory = directory.resolve("program"),
                )
            )
            RustCargoRunner(cargo.absolutePathString()).buildProgram(programWorkspace)

            RustBitcodeLibraryCompiler(cargo.absolutePathString()).compile(
                packageName = "contract_library",
                targetTriple = target,
                renderedLibraryRs = "#![no_std]\n",
                outputDirectory = directory.resolve("library"),
            )

            val commands = Files.readAllLines(commandLog, StandardCharsets.UTF_8)
            assertEquals(4, commands.size)
            assertCargoClippyContract(commands[0], target, library = false)
            assertTrue(commands[1].startsWith("rustc "), commands[1])
            assertTrue(commands[1].endsWith(" --release --message-format=short -- --emit=llvm-bc,link"), commands[1])
            assertCargoClippyContract(commands[2], target, library = true)
            assertTrue(commands[3].startsWith("rustc "), commands[3])
            assertContains(commands[3], " --lib ")
            assertTrue(commands[3].endsWith(" --release --message-format=short -- --emit=llvm-bc"), commands[3])
        }
    }

    private fun assertCargoClippyContract(command: String, target: String, library: Boolean) {
        assertTrue(command.startsWith("clippy "), command)
        assertContains(command, " --target $target ")
        assertContains(command, " --target-dir ")
        if (library) {
            assertContains(command, " --lib ")
        } else {
            assertContains(command, " --bin contract_program ")
        }
        assertContains(command, " --release ")
        assertTrue(command.endsWith(" --message-format=short --no-deps -- -D warnings"), command)
    }

    private fun fakeCargo(directory: Path, commandLog: Path): Path {
        val script = directory.resolve("fake-cargo")
        val quotedLog = commandLog.absolutePathString().replace("'", "'\"'\"'")
        script.writeText(
            """
                #!/bin/sh
                printf '%s\n' "${'$'}*" >> '$quotedLog'
                target=''
                target_dir=''
                profile='debug'
                pending=''
                for argument in "${'$'}@"; do
                  if [ "${'$'}pending" = 'target' ]; then target="${'$'}argument"; pending=''; continue; fi
                  if [ "${'$'}pending" = 'target-dir' ]; then target_dir="${'$'}argument"; pending=''; continue; fi
                  case "${'$'}argument" in
                    --target) pending='target' ;;
                    --target-dir) pending='target-dir' ;;
                    --release) profile='release' ;;
                  esac
                done
                if [ "${'$'}1" = 'rustc' ]; then
                  artifact_dir="${'$'}target_dir/${'$'}target/${'$'}profile"
                  mkdir -p "${'$'}artifact_dir/deps"
                  printf 'program\n' > "${'$'}artifact_dir/contract_program"
                  printf 'program bitcode\n' > "${'$'}artifact_dir/deps/contract_program-deadbeef.bc"
                  printf 'library bitcode\n' > "${'$'}artifact_dir/deps/contract_library-deadbeef.bc"
                fi
            """.trimIndent() + "\n"
        )
        check(script.toFile().setExecutable(true)) { "Cannot make fake Cargo executable: $script" }
        return script
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
        val process = ProcessBuilder(command.toList()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.waitFor(), output)
    }

    private inline fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("kotlin-native-rust-primitive-test")
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
