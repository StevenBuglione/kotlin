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

class RustGcRootIntegrationTest {
    @Test
    fun kotlinObjectSurvivesForcedGcInRustFrameAndReferenceResultSlots() {
        val distributionPath = System.getenv(DISTRIBUTION_ENV)
        assumeTrue("Set $DISTRIBUTION_ENV to opt in to the Rust GC-root integration test", distributionPath != null)
        assumeTrue("The initial Rust GC-root integration fixture supports Linux x64 hosts only", isLinuxX64Host())

        val distribution = Paths.get(distributionPath!!)
        val compiler = distribution.resolve("bin/konanc")
        assertTrue(Files.isRegularFile(compiler), "Kotlin/Native compiler not found: $compiler")

        withTemporaryDirectory { directory ->
            val source = directory.resolve("gc-root.kt").apply {
                writeText(
                    """
                        @file:OptIn(
                            kotlin.experimental.ExperimentalNativeApi::class,
                            kotlin.native.runtime.NativeRuntimeApi::class,
                        )

                        import kotlin.native.ref.WeakReference
                        import kotlin.native.runtime.GC

                        class Payload(val value: Int)

                        private var weakPayload: WeakReference<Payload>? = null
                        private var weakControl: WeakReference<Payload>? = null
                        private var forcedCount = 0

                        fun createPayload(): Payload {
                            val payload = Payload(42)
                            weakPayload = WeakReference(payload)
                            return payload
                        }

                        fun collectNow() {
                            repeat(3) { GC.collect() }
                        }

                        fun makeCollectable() {
                            weakControl = WeakReference(Payload(7))
                        }

                        fun forceGc() {
                            forcedCount++
                            collectNow()
                        }

                        fun holdAcrossGc(): Payload {
                            val payload = createPayload()
                            forceGc()
                            return payload
                        }

                        fun holdAcrossGcAgain(): Payload {
                            val payload = createPayload()
                            forceGc()
                            return payload
                        }

                        fun main() {
                            makeCollectable()
                            collectNow()
                            val controlCollected = weakControl?.value == null
                            val payload = holdAcrossGc()
                            val payloadAgain = holdAcrossGcAgain()
                            collectNow()
                            val alive = weakPayload?.value === payloadAgain
                            println("${'$'}controlCollected:${'$'}forcedCount:${'$'}{if (alive) payload.value + payloadAgain.value else -1}:${'$'}alive")
                        }
                    """.trimIndent()
                )
            }
            val profiles: List<Pair<String, List<String>>> = listOf("debug" to emptyList(), "opt" to listOf("-opt"))
            for (profileAndArguments in profiles) {
                val profile = profileAndArguments.first
                val extraArguments = profileAndArguments.second
                val outputName = "gc-root-hybrid-$profile"
                val output = directory.resolve(outputName)
                val compilation = runProcess(
                    compiler.absolutePathString(),
                    source.absolutePathString(),
                    "-target", "linux_x64",
                    "-Xnative-codegen=rust-hybrid",
                    *extraArguments.toTypedArray(),
                    "-o", output.absolutePathString(),
                )
                assertEquals(0, compilation.exitCode, compilation.output)

                val executable = output.resolveSibling(output.fileName.toString() + ".kexe")
                assertTrue(Files.isExecutable(executable), "Executable not found: $executable")
                val execution = runProcess(executable.absolutePathString())
                assertEquals(0, execution.exitCode, execution.output)
                assertEquals("true:2:84:true", execution.output.trim())

                val workspace = directory.resolve(".kotlin-rust/$outputName")
                assertTrue(
                    Files.isRegularFile(workspace.resolve("rust-bitcode-preflight-ok")),
                    "Rust bitcode preflight did not complete for the reference-bearing function",
                )
                val generatedSource = workspace.resolve("src/lib.rs")
                assertTrue(Files.isRegularFile(generatedSource), "Generated Rust source not found: $generatedSource")
                val generatedText = Files.readAllBytes(generatedSource).toString(StandardCharsets.UTF_8)
                assertContains(generatedText, "#[export_name = \"kfun:#holdAcrossGc")
                assertContains(generatedText, "#[export_name = \"kfun:#holdAcrossGcAgain")
                assertContains(generatedText, "#[link_name = \"kfun:#createPayload")
                assertContains(generatedText, "#[link_name = \"kfun:#forceGc")
                assertEquals(1, generatedText.split("pub type KRef").size - 1, "Managed bodies must share one runtime prelude")
                assertContains(generatedText, "getCurrentFrame")
                assertContains(generatedText, "SetCurrentFrame")
                assertOrdered(
                    generatedText.substring(generatedText.indexOf("pub unsafe extern \"C-unwind\" fn")),
                    "EnterFrame(frame",
                    "let created =",
                    "UpdateStackRef(root, created)",
                    "core::ptr::read(root)",
                    "UpdateReturnRef(return_slot, result)",
                    "frame_guard.leave()",
                )
            }
        }
    }

    private fun assertOrdered(text: String, vararg tokens: String) {
        var previous = -1
        for (token in tokens) {
            val current = text.indexOf(token, previous + 1)
            assertTrue(current > previous, "Expected '$token' after ${tokens.getOrNull(tokens.indexOf(token) - 1)} in:\n$text")
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
        val directory = Files.createTempDirectory("kotlin-native-rust-gc-root-test")
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
