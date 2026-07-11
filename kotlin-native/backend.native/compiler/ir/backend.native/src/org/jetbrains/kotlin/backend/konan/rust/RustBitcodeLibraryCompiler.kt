/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal data class RustBitcodeLibraryWorkspaceSpec(
    val packageName: String,
    val targetTriple: String,
    val libraryRs: String,
    val outputDirectory: Path,
    val release: Boolean = true,
) {
    init {
        require(RUST_LIBRARY_PACKAGE_NAME.matches(packageName)) { "Invalid Cargo package name: $packageName" }
        require(targetTriple.isNotBlank() && targetTriple.none { it.isWhitespace() || it.isISOControl() }) {
            "A Rust target triple must be non-blank and contain no whitespace"
        }
        require(libraryRs.isNotBlank()) { "The generated Rust library must not be blank" }
    }
}

internal data class RustBitcodeLibraryWorkspace(
    val directory: Path,
    val manifest: Path,
    val librarySource: Path,
    val packageName: String,
    val targetTriple: String,
    val release: Boolean,
)

/** Writes the no-std Rust crate that is linked into the normal Kotlin/Native bitcode pipeline. */
internal object RustBitcodeLibraryWorkspaceEmitter {
    fun emit(spec: RustBitcodeLibraryWorkspaceSpec): RustBitcodeLibraryWorkspace {
        val directory = spec.outputDirectory.toAbsolutePath().normalize()
        val sourceDirectory = directory.resolve("src")
        Files.createDirectories(sourceDirectory)

        val manifest = directory.resolve("Cargo.toml")
        val librarySource = sourceDirectory.resolve("lib.rs")
        writeIfChanged(manifest, cargoManifest(spec.packageName))
        writeIfChanged(librarySource, spec.libraryRs.withSingleTrailingNewline())

        return RustBitcodeLibraryWorkspace(
            directory,
            manifest,
            librarySource,
            spec.packageName,
            spec.targetTriple,
            spec.release,
        )
    }

    private fun cargoManifest(packageName: String): String = """
        [package]
        name = "$packageName"
        version = "0.0.0"
        edition = "2021"
        publish = false

        [lib]
        name = "$packageName"
        path = "src/lib.rs"
        crate-type = ["rlib"]

        [profile.dev]
        codegen-units = 1
        opt-level = 1
        overflow-checks = false
        panic = "unwind"

        [profile.release]
        codegen-units = 1
        overflow-checks = false
        panic = "unwind"

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

internal data class RustBitcodeLibraryArtifact(
    val llvmBitcode: Path,
    val workspace: RustBitcodeLibraryWorkspace,
    val compilerOutput: String,
)

internal class RustBitcodeLibraryCompiler(
    private val cargoExecutable: String = "cargo",
    environment: Map<String, String> = emptyMap(),
    private val commandExecutor: RustCommandExecutor = RustProcessCommandExecutor(environment),
) {
    fun compile(
        packageName: String,
        targetTriple: String,
        renderedLibraryRs: String,
        outputDirectory: Path,
        release: Boolean = true,
    ): RustBitcodeLibraryArtifact {
        val workspace = RustBitcodeLibraryWorkspaceEmitter.emit(
            RustBitcodeLibraryWorkspaceSpec(
                packageName,
                targetTriple,
                renderedLibraryRs,
                outputDirectory,
                release,
            )
        )
        return build(workspace)
    }

    private fun build(workspace: RustBitcodeLibraryWorkspace): RustBitcodeLibraryArtifact {
        val targetDirectory = workspace.directory.resolve("target")
        val commonArguments = listOf(
            "--manifest-path", workspace.manifest.toString(),
            "--target", workspace.targetTriple,
            "--target-dir", targetDirectory.toString(),
            "--lib",
        )
        val clippyCommand = buildList {
            addAll(listOf(cargoExecutable, "clippy"))
            addAll(commonArguments)
            if (workspace.release) add("--release")
            addAll(listOf("--message-format=short", "--no-deps"))
            addAll(listOf("--", "-D", "warnings"))
        }
        val clippyOutput = commandExecutor.execute(clippyCommand, workspace.directory)

        val profile = if (workspace.release) "release" else "debug"
        val artifactDirectory = targetDirectory.resolve(workspace.targetTriple).resolve(profile).resolve("deps")
        val cratePrefix = workspace.packageName.replace('-', '_')
        deletePackageBitcodeArtifacts(artifactDirectory, cratePrefix)
        val rustcCommand = buildList {
            addAll(listOf(cargoExecutable, "rustc"))
            addAll(commonArguments)
            if (workspace.release) add("--release")
            add("--message-format=short")
            addAll(listOf("--", "--emit=llvm-bc"))
        }
        val rustcOutput = commandExecutor.execute(rustcCommand, workspace.directory)

        val bitcode = bitcodeArtifacts(artifactDirectory, cratePrefix).singleOrNull()
            ?: error("Cargo must produce exactly one Rust LLVM bitcode file in $artifactDirectory")
        val compilerOutput = listOf(clippyOutput, rustcOutput).filter(String::isNotBlank).joinToString("\n")
        return RustBitcodeLibraryArtifact(bitcode, workspace, compilerOutput)
    }

    private fun bitcodeArtifacts(directory: Path, cratePrefix: String): List<Path> =
        Files.list(directory).use { paths ->
            paths.iterator().asSequence()
                .filter { path -> path.fileName.toString().startsWith(cratePrefix) }
                .filter { path -> path.fileName.toString().endsWith(".bc") }
                .sortedBy { it.fileName.toString() }
                .toList()
        }
}

private val RUST_LIBRARY_PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_-]*")

private fun String.withSingleTrailingNewline(): String = trimEnd('\r', '\n') + "\n"
