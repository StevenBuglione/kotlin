/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RustCargoCompilerClippyTest {
    @Test
    fun checksProgramWithClippyBeforeRustc() = withTemporaryDirectory { directory ->
        val commands = mutableListOf<List<String>>()
        val executor = RustCommandExecutor { command, _ ->
            commands += command
            if (command[1] == "rustc") createProgramArtifacts(directory, "generated_program", TARGET)
            command[1] + " output"
        }
        val artifact = RustProgramCompiler(RustCargoRunner(commandExecutor = executor)).compile(
            packageName = "generated_program",
            targetTriple = TARGET,
            renderedMainRs = "fn main() {}",
            outputDirectory = directory,
            dependencies = listOf(RustCargoDependency("direct_dep", "1.2.3", localPathOverride = directory.resolve("direct-dep"))),
        )

        assertEquals(listOf("clippy", "rustc"), commands.map { it[1] })
        assertContainsInOrder(
            commands.first(),
            "--bin", "generated_program", "--message-format=short", "--no-deps", "--", "-D", "warnings",
        )
        assertContainsInOrder(commands.last(), "--message-format=short", "--", "--emit=llvm-bc,link")
        assertContains(
            Files.readAllBytes(artifact.workspace.manifest).toString(StandardCharsets.UTF_8),
            "direct_dep = { version = \"=1.2.3\", path = ",
        )
        assertEquals("clippy output\nrustc output", artifact.compilerOutput)
    }

    @Test
    fun checksNoStdLibraryWithoutCleaningCargoOutputs() = withTemporaryDirectory { directory ->
        val staleBitcode = bitcodeDirectory(directory, TARGET).resolve("generated_library-stale.bc")
        Files.createDirectories(staleBitcode.parent)
        Files.write(staleBitcode, byteArrayOf(1))
        val commands = mutableListOf<List<String>>()
        var staleArtifactPresentAtRustc = true
        val executor = RustCommandExecutor { command, _ ->
            commands += command
            if (command[1] == "rustc") {
                staleArtifactPresentAtRustc = Files.exists(staleBitcode)
                Files.write(bitcodeDirectory(directory, TARGET).resolve("generated_library-current.bc"), byteArrayOf(2))
            }
            ""
        }

        val artifact = RustBitcodeLibraryCompiler(commandExecutor = executor).compile(
            packageName = "generated_library",
            targetTriple = TARGET,
            renderedLibraryRs = "#![no_std]\npub fn answer() -> i32 { 42 }",
            outputDirectory = directory,
        )

        assertEquals(listOf("clippy", "rustc"), commands.map { it[1] })
        assertFalse(commands.flatten().contains("clean"))
        assertContainsInOrder(
            commands.first(),
            "--lib", "--release", "--message-format=short", "--no-deps", "--", "-D", "warnings",
        )
        assertContainsInOrder(commands.last(), "--lib", "--release", "--message-format=short", "--", "--emit=llvm-bc")
        assertFalse(staleArtifactPresentAtRustc, "Only stale root bitcode should be removed before rustc")
        assertEquals("generated_library-current.bc", artifact.llvmBitcode.fileName.toString())
        assertEquals(null, artifact.staticLibrary)
        assertTrue(artifact.nativeStaticLibraries.isEmpty())
    }

    @Test
    fun emitsCompanionStaticLibraryForRegistryDependencies() = withTemporaryDirectory { directory ->
        val commands = mutableListOf<List<String>>()
        val staticLibrary = directory.resolve("target").resolve(TARGET).resolve("release").resolve("libgenerated_library.a")
        val staleStaticLibrary = byteArrayOf(1)
        Files.createDirectories(staticLibrary.parent)
        Files.write(staticLibrary, staleStaticLibrary)
        var staleStaticLibraryPresentAtRustc = true
        val executor = RustCommandExecutor { command, _ ->
            commands += command
            if (command[1] == "rustc") {
                staleStaticLibraryPresentAtRustc = Files.exists(staticLibrary)
                Files.createDirectories(bitcodeDirectory(directory, TARGET))
                Files.write(bitcodeDirectory(directory, TARGET).resolve("generated_library-current.bc"), byteArrayOf(2))
                Files.write(staticLibrary, byteArrayOf(3))
            }
            if (command[1] == "rustc") {
                "note: native-static-libs: -lgcc_s -lutil -lrt -lpthread -lm -ldl -lpthread -lc\n"
            } else {
                ""
            }
        }

        val artifact = RustBitcodeLibraryCompiler(commandExecutor = executor).compile(
            packageName = "generated_library",
            targetTriple = TARGET,
            renderedLibraryRs = "#![no_std]\npub fn answer() -> i32 { direct_dep::answer() }",
            outputDirectory = directory,
            dependencies = listOf(RustCargoDependency("direct_dep", "1.2.3")),
        )

        assertEquals(listOf("clippy", "rustc"), commands.map { it[1] })
        assertContainsInOrder(
            commands.last(),
            "--lib", "--release", "--message-format=short", "--", "--emit=llvm-bc,link", "-C", "embed-bitcode=yes",
            "--print", "native-static-libs",
        )
        assertFalse(staleStaticLibraryPresentAtRustc, "The dependency archive must be produced by the current rustc invocation")
        assertEquals(staticLibrary, artifact.staticLibrary)
        assertEquals(
            listOf("-lgcc_s", "-lutil", "-lrt", "-lpthread", "-lm", "-ldl", "-lpthread", "-lc"),
            artifact.nativeStaticLibraries,
        )
        assertTrue(Files.isRegularFile(artifact.llvmBitcode))
    }

    @Test
    fun doesNotInvokeRustcAfterClippyFailure() = withTemporaryDirectory { directory ->
        val commands = mutableListOf<List<String>>()
        val executor = RustCommandExecutor { command, _ ->
            commands += command
            throw RustToolExecutionException(command, 17, "src/main.rs:1:1: warning: generated warning")
        }
        val failure = assertFailsWith<RustToolExecutionException> {
            RustProgramCompiler(RustCargoRunner(commandExecutor = executor)).compile(
                packageName = "warning_program",
                targetTriple = TARGET,
                renderedMainRs = "fn main() {}",
                outputDirectory = directory,
            )
        }

        assertEquals(listOf("clippy"), commands.map { it[1] })
        assertEquals(17, failure.exitCode)
        assertContains(failure.message.orEmpty(), "-D warnings")
        assertContains(failure.message.orEmpty(), "generated warning")
    }

    @Test
    fun normalizesToolDiagnostics() = withTemporaryDirectory { directory ->
        val absoluteDirectory = directory.toAbsolutePath().normalize()
        val output = "\u001B[31m${absoluteDirectory.resolve("src/lib.rs")}:1: warning\u001B[0m\r\n"

        val normalized = normalizeRustToolOutput(output, directory)

        assertEquals("<rust-workspace>${directory.fileSystem.separator}src${directory.fileSystem.separator}lib.rs:1: warning", normalized)
        assertFalse('\u001B' in normalized)
        assertFalse('\r' in normalized)
    }

    private fun createProgramArtifacts(directory: Path, packageName: String, target: String) {
        val artifactDirectory = directory.resolve("target").resolve(target).resolve("release")
        Files.createDirectories(artifactDirectory.resolve("deps"))
        Files.write(artifactDirectory.resolve(packageName), byteArrayOf(1))
        Files.write(artifactDirectory.resolve("deps").resolve("$packageName-current.bc"), byteArrayOf(2))
    }

    private fun bitcodeDirectory(directory: Path, target: String): Path =
        directory.resolve("target").resolve(target).resolve("release").resolve("deps")

    private fun assertContainsInOrder(arguments: List<String>, vararg expected: String) {
        var index = 0
        for (argument in arguments) {
            if (index < expected.size && argument == expected[index]) index++
        }
        assertTrue(index == expected.size, "Expected ${expected.toList()} in order in $arguments")
    }

    private inline fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("rust-cargo-clippy-test")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val TARGET = "x86_64-unknown-linux-gnu"
    }
}
