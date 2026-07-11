/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal data class RustCargoWorkspaceSpec(
    val packageName: String,
    val targetTriple: String,
    val mainRs: String,
    val outputDirectory: Path,
    val release: Boolean = true,
) {
    init {
        require(CARGO_PACKAGE_NAME.matches(packageName)) { "Invalid Cargo package name: $packageName" }
        require(targetTriple.isNotBlank() && targetTriple.none { it.isWhitespace() || it.isISOControl() }) {
            "A Rust target triple must be non-blank and contain no whitespace"
        }
        require(mainRs.isNotBlank()) { "The generated Rust program must not be blank" }
    }
}

internal data class RustCargoWorkspace(
    val directory: Path,
    val manifest: Path,
    val mainSource: Path,
    val packageName: String,
    val targetTriple: String,
    val release: Boolean,
)

/** Writes a single-package Cargo workspace without timestamps or machine-specific paths. */
internal object RustCargoWorkspaceEmitter {
    fun emit(spec: RustCargoWorkspaceSpec): RustCargoWorkspace {
        val directory = spec.outputDirectory.toAbsolutePath().normalize()
        val sourceDirectory = directory.resolve("src")
        Files.createDirectories(sourceDirectory)

        val manifest = directory.resolve("Cargo.toml")
        val mainSource = sourceDirectory.resolve("main.rs")
        writeIfChanged(manifest, cargoManifest(spec.packageName))
        writeIfChanged(mainSource, spec.mainRs.withSingleTrailingNewline())

        return RustCargoWorkspace(directory, manifest, mainSource, spec.packageName, spec.targetTriple, spec.release)
    }

    private fun cargoManifest(packageName: String): String = """
        [package]
        name = "$packageName"
        version = "0.0.0"
        edition = "2021"
        publish = false

        [[bin]]
        name = "$packageName"
        path = "src/main.rs"

        [workspace]
    """.trimIndent().withSingleTrailingNewline()

    private fun writeIfChanged(path: Path, contents: String) {
        val bytes = contents.toByteArray(StandardCharsets.UTF_8)
        if (Files.exists(path) && Files.readAllBytes(path).contentEquals(bytes)) return

        val temporary = path.resolveSibling(".${path.fileName}.tmp")
        Files.write(temporary, bytes)
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal data class RustProgramArtifact(
    val executable: Path,
    val llvmBitcode: Path?,
    val workspace: RustCargoWorkspace,
    val compilerOutput: String,
)

internal class RustToolExecutionException(
    val command: List<String>,
    val exitCode: Int,
    val compilerOutput: String,
) : IllegalStateException(
    buildString {
        append("Rust compiler command failed with exit code ").append(exitCode).append(": ")
        append(command.joinToString(" ") { quoteCommandArgument(it) })
        if (compilerOutput.isNotBlank()) appendLine().append(compilerOutput.trimEnd())
    }
)

/** Invokes Cargo only after workspace emission, keeping process execution separately testable. */
internal class RustCargoRunner(
    private val cargoExecutable: String = "cargo",
    private val environment: Map<String, String> = emptyMap(),
) {
    fun buildProgram(workspace: RustCargoWorkspace): RustProgramArtifact {
        val targetDirectory = workspace.directory.resolve("target")
        val commonArguments = listOf(
            "--manifest-path", workspace.manifest.toString(),
            "--target", workspace.targetTriple,
            "--target-dir", targetDirectory.toString(),
        )
        val clippyCommand = buildList {
            addAll(listOf(cargoExecutable, "clippy"))
            addAll(commonArguments)
            if (workspace.release) add("--release")
            addAll(listOf("--", "-D", "warnings"))
        }
        val clippyOutput = runCommand(clippyCommand, workspace.directory)

        val rustcCommand = buildList {
            addAll(listOf(
                cargoExecutable,
                "rustc",
            ))
            addAll(commonArguments)
            if (workspace.release) add("--release")
            addAll(listOf("--", "--emit=llvm-bc,link"))
        }
        val rustcOutput = runCommand(rustcCommand, workspace.directory)

        val profile = if (workspace.release) "release" else "debug"
        val artifactDirectory = targetDirectory.resolve(workspace.targetTriple).resolve(profile)
        val executable = artifactDirectory.resolve(workspace.packageName + executableSuffix(workspace.targetTriple))
        check(Files.isRegularFile(executable)) {
            "Cargo succeeded but did not produce the expected Rust executable: $executable"
        }

        val bitcode = Files.list(artifactDirectory.resolve("deps")).use { paths ->
            paths
                .filter { path -> path.fileName.toString().startsWith(workspace.packageName.replace('-', '_')) }
                .filter { path -> path.fileName.toString().endsWith(".bc") }
                .sorted()
                .findFirst()
                .orElse(null)
        }
        val compilerOutput = listOf(clippyOutput, rustcOutput).filter(String::isNotBlank).joinToString("\n")
        return RustProgramArtifact(executable, bitcode, workspace, compilerOutput)
    }

    private fun runCommand(command: List<String>, workingDirectory: Path): String {
        val process = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .apply { environment().putAll(this@RustCargoRunner.environment) }
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) throw RustToolExecutionException(command, exitCode, output)
        return output
    }
}

/** Compiler-facing entry point for the PROGRAM prototype. */
internal class RustProgramCompiler(
    private val runner: RustCargoRunner = RustCargoRunner(),
) {
    fun compile(
        packageName: String,
        targetTriple: String,
        renderedMainRs: String,
        outputDirectory: Path,
        release: Boolean = true,
    ): RustProgramArtifact {
        val workspace = RustCargoWorkspaceEmitter.emit(
            RustCargoWorkspaceSpec(packageName, targetTriple, renderedMainRs, outputDirectory, release)
        )
        return runner.buildProgram(workspace)
    }

    fun compile(
        packageName: String,
        targetTriple: String,
        renderedMainRs: String,
        outputDirectory: File,
        release: Boolean = true,
    ): RustProgramArtifact = compile(packageName, targetTriple, renderedMainRs, outputDirectory.toPath(), release)
}

private val CARGO_PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_-]*")

private fun String.withSingleTrailingNewline(): String = trimEnd('\r', '\n') + "\n"

private fun executableSuffix(targetTriple: String): String = if ("windows" in targetTriple) ".exe" else ""

private fun quoteCommandArgument(argument: String): String = if (argument.any(Char::isWhitespace)) "\"$argument\"" else argument
