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

class RustHeapBarrierIntegrationTest {
    @Test
    fun managedFieldWriteUsesHeapBarrierAndSurvivesForcedGc() {
        val distributionPath = System.getenv(DISTRIBUTION_ENV)
        assumeTrue("Set $DISTRIBUTION_ENV to opt in to the Rust heap-barrier integration test", distributionPath != null)
        assumeTrue("The Rust heap-barrier integration fixture supports Linux x64 hosts only", isLinuxX64Host())

        val distribution = Paths.get(distributionPath!!)
        val compiler = distribution.resolve("bin/konanc")
        assertTrue(Files.isRegularFile(compiler), "Kotlin/Native compiler not found: $compiler")

        withTemporaryDirectory { directory ->
            val source = directory.resolve("heap-barrier.kt").apply {
                writeText(
                    """
                        @file:OptIn(
                            kotlin.experimental.ExperimentalNativeApi::class,
                            kotlin.native.runtime.NativeRuntimeApi::class,
                        )

                        import kotlin.native.ref.WeakReference
                        import kotlin.native.runtime.GC

                        class Payload(val value: Int)

                        class Holder {
                            var payload: Payload? = null
                        }

                        private var weakPayload: WeakReference<Payload>? = null
                        private var weakControl: WeakReference<Payload>? = null
                        private var forcedCount = 0

                        fun createHolder(): Holder = Holder()

                        fun createPayload(): Payload {
                            val payload = Payload(42)
                            weakPayload = WeakReference(payload)
                            return payload
                        }

                        fun makeCollectable() {
                            weakControl = WeakReference(Payload(7))
                        }

                        fun collectNow() {
                            repeat(3) { GC.collect() }
                        }

                        fun forceGc() {
                            forcedCount++
                            collectNow()
                        }

                        fun writeFieldAcrossGc(): Holder {
                            val holder = createHolder()
                            holder.payload = createPayload()
                            forceGc()
                            return holder
                        }

                        fun main() {
                            makeCollectable()
                            collectNow()
                            val controlCollected = weakControl?.value == null
                            val holder = writeFieldAcrossGc()
                            val payload = holder.payload
                            val fieldOwnsPayload = payload != null && weakPayload?.value === payload
                            println("${'$'}controlCollected:${'$'}forcedCount:${'$'}{payload?.value}:${'$'}fieldOwnsPayload")
                        }
                    """.trimIndent()
                )
            }

            val profiles: List<Pair<String, List<String>>> = listOf("debug" to emptyList(), "opt" to listOf("-opt"))
            for (profileAndArguments in profiles) {
                val profile = profileAndArguments.first
                val extraArguments = profileAndArguments.second
                val llvmOutput = directory.resolve("heap-barrier-llvm-$profile")
                val llvmCompilation = compile(compiler, source, llvmOutput, "llvm", extraArguments)
                assertEquals(0, llvmCompilation.exitCode, llvmCompilation.output)
                val llvmExecution = runProgram(executable(llvmOutput))
                assertEquals("true:1:42:true", llvmExecution.output.trim())

                val hybridOutputName = "heap-barrier-hybrid-$profile"
                val hybridOutput = directory.resolve(hybridOutputName)
                val hybridCompilation = compile(compiler, source, hybridOutput, "rust-hybrid", extraArguments)
                assertEquals(0, hybridCompilation.exitCode, hybridCompilation.output)
                val hybridExecution = runProgram(executable(hybridOutput))
                assertEquals(llvmExecution.output, hybridExecution.output, "Hybrid behavior differs from LLVM in $profile mode")

                val workspace = directory.resolve(".kotlin-rust/$hybridOutputName")
                assertTrue(
                    Files.isRegularFile(workspace.resolve("rust-bitcode-preflight-ok")),
                    "Rust bitcode preflight did not complete for the managed field-writing function",
                )
                val generatedSource = workspace.resolve("src/lib.rs")
                assertTrue(Files.isRegularFile(generatedSource), "Generated Rust source not found: $generatedSource")
                val generatedText = Files.readAllBytes(generatedSource).toString(StandardCharsets.UTF_8)
                assertContains(generatedText, "#[export_name = \"kfun:#writeFieldAcrossGc")
                assertContains(generatedText, "#[link_name = \"kfun:#createHolder")
                assertContains(generatedText, "#[link_name = \"kfun:#createPayload")
                assertContains(generatedText, "#[link_name = \"kfun:#forceGc")
                assertContains(generatedText, "fn UpdateHeapRef(location: *mut KRef, value: KRef);")

                val generatedFunction = generatedText.substring(generatedText.indexOf("#[export_name = \"kfun:#writeFieldAcrossGc"))
                assertContains(generatedFunction, "UpdateHeapRef(")
                assertOrdered(
                    generatedFunction,
                    "EnterFrame(frame",
                    "UpdateStackRef(",
                    "UpdateHeapRef(",
                    "core::ptr::null_mut()",
                    "UpdateReturnRef(",
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
        val directory = Files.createTempDirectory("kotlin-native-rust-heap-barrier-test")
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
