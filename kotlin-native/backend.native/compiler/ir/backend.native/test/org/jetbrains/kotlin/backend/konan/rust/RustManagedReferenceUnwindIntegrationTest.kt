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

class RustManagedReferenceUnwindIntegrationTest {
    @Test
    fun kotlinExceptionUnwindsRustManagedFrameAndRestoresShadowStack() {
        val distributionPath = System.getenv(DISTRIBUTION_ENV)
        assumeTrue("Set $DISTRIBUTION_ENV to opt in to the Rust unwind integration test", distributionPath != null)
        assumeTrue("The Rust managed-reference unwind fixture supports Linux x64 hosts only", isLinuxX64Host())

        val distribution = Paths.get(distributionPath!!)
        val compiler = distribution.resolve("bin/konanc")
        assertTrue(Files.isRegularFile(compiler), "Kotlin/Native compiler not found: $compiler")

        withTemporaryDirectory { directory ->
            val source = directory.resolve("managed-reference-unwind.kt").apply {
                writeText(
                    """
                        @file:OptIn(
                            kotlin.experimental.ExperimentalNativeApi::class,
                            kotlin.native.runtime.NativeRuntimeApi::class,
                        )

                        import kotlin.native.ref.WeakReference
                        import kotlin.native.runtime.GC

                        class Payload(val value: Int)

                        private var unwoundPayload: WeakReference<Payload>? = null
                        private var survivorPayload: WeakReference<Payload>? = null

                        fun createUnwoundPayload(): Payload {
                            val payload = Payload(42)
                            unwoundPayload = WeakReference(payload)
                            return payload
                        }

                        fun throwFromLlvm() {
                            throw IllegalStateException("through Rust")
                        }

                        fun collectNow() {
                            repeat(3) { GC.collect() }
                        }

                        fun throughRustManagedFrame(): Payload {
                            val payload = createUnwoundPayload()
                            throwFromLlvm()
                            return payload
                        }

                        fun main() {
                            var caught = false
                            try {
                                throughRustManagedFrame()
                                println("not caught")
                            } catch (_: IllegalStateException) {
                                caught = true
                            }

                            collectNow()
                            val unwoundRootWasReleased = unwoundPayload?.value == null

                            val survivor = Payload(99)
                            survivorPayload = WeakReference(survivor)
                            collectNow()
                            val survivorStayedAlive = survivorPayload?.value === survivor

                            println("${'$'}caught:${'$'}unwoundRootWasReleased:${'$'}{survivor.value}:${'$'}survivorStayedAlive")
                        }
                    """.trimIndent()
                )
            }

            val profiles: List<Pair<String, List<String>>> = listOf("debug" to emptyList(), "opt" to listOf("-opt"))
            for (profileAndArguments in profiles) {
                val profile = profileAndArguments.first
                val extraArguments = profileAndArguments.second
                val outputName = "managed-reference-unwind-$profile"
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
                assertEquals("true:true:99:true", execution.output.trim())

                val workspace = directory.resolve(".kotlin-rust/$outputName")
                assertTrue(
                    Files.isRegularFile(workspace.resolve("rust-bitcode-preflight-ok")),
                    "Rust bitcode preflight did not complete for the unwinding managed-reference function",
                )
                val generatedSource = workspace.resolve("src/lib.rs")
                assertTrue(Files.isRegularFile(generatedSource), "Generated Rust source not found: $generatedSource")
                val generatedText = Files.readAllBytes(generatedSource).toString(StandardCharsets.UTF_8)
                assertContains(generatedText, "#[export_name = \"kfun:#throughRustManagedFrame")
                assertContains(generatedText, "#[link_name = \"kfun:#throwFromLlvm")
                assertContains(generatedText, "impl Drop for")
                assertContains(generatedText, "SetCurrentFrame(self.previous)")
                assertOrdered(
                    generatedText.substring(generatedText.indexOf("pub unsafe extern \"C-unwind\" fn")),
                    "let previous = getCurrentFrame()",
                    "EnterFrame(frame",
                    "let mut frame_guard =",
                    "let created =",
                    "UpdateStackRef(root, created)",
                    "_unit_",
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
        val directory = Files.createTempDirectory("kotlin-native-rust-managed-unwind-test")
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
