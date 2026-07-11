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
    val dependencies: List<RustCargoRegistryDependency> = emptyList(),
) {
    init {
        require(RUST_LIBRARY_PACKAGE_NAME.matches(packageName)) { "Invalid Cargo package name: $packageName" }
        require(targetTriple.isNotBlank() && targetTriple.none { it.isWhitespace() || it.isISOControl() }) {
            "A Rust target triple must be non-blank and contain no whitespace"
        }
        require(libraryRs.isNotBlank()) { "The generated Rust library must not be blank" }
        val crateNames = dependencies.map { it.packageName.replace('-', '_') }
        require(crateNames.size == crateNames.toSet().size) {
            "Cargo dependency package names must remain unique after replacing '-' with '_'"
        }
    }
}

internal data class RustCargoRegistryDependency(
    val packageName: String,
    val version: String,
    val features: List<String> = emptyList(),
    val defaultFeatures: Boolean = true,
) {
    init {
        require(RUST_DEPENDENCY_PACKAGE_NAME.matches(packageName)) { "Invalid Cargo dependency package name: $packageName" }
        require(RUST_EXACT_DEPENDENCY_VERSION.matches(version)) {
            "Invalid exact Cargo dependency version for '$packageName': $version"
        }
        require(features.all(RUST_DEPENDENCY_FEATURE::matches)) {
            "Invalid Cargo feature for dependency '$packageName': ${features.firstOrNull { !RUST_DEPENDENCY_FEATURE.matches(it) }}"
        }
    }
}

internal data class RustBitcodeLibraryWorkspace(
    val directory: Path,
    val manifest: Path,
    val librarySource: Path,
    val packageName: String,
    val targetTriple: String,
    val release: Boolean,
    val hasDependencies: Boolean,
)

/** Writes the no-std Rust crate that is linked into the normal Kotlin/Native bitcode pipeline. */
internal object RustBitcodeLibraryWorkspaceEmitter {
    fun emit(spec: RustBitcodeLibraryWorkspaceSpec): RustBitcodeLibraryWorkspace {
        val directory = spec.outputDirectory.toAbsolutePath().normalize()
        val sourceDirectory = directory.resolve("src")
        Files.createDirectories(sourceDirectory)

        val manifest = directory.resolve("Cargo.toml")
        val librarySource = sourceDirectory.resolve("lib.rs")
        writeIfChanged(manifest, cargoManifest(spec.packageName, spec.dependencies))
        writeIfChanged(librarySource, spec.libraryRs.withSingleTrailingNewline())

        return RustBitcodeLibraryWorkspace(
            directory,
            manifest,
            librarySource,
            spec.packageName,
            spec.targetTriple,
            spec.release,
            spec.dependencies.isNotEmpty(),
        )
    }

    private fun cargoManifest(packageName: String, dependencies: List<RustCargoRegistryDependency>): String = buildString {
        append("[package]\n")
        append("name = \"").append(packageName).append("\"\n")
        append("version = \"0.0.0\"\n")
        append("edition = \"2021\"\n")
        append("publish = false\n\n")
        append("[lib]\n")
        append("name = \"").append(packageName).append("\"\n")
        append("path = \"src/lib.rs\"\n")
        append("crate-type = [\"rlib\"")
        if (dependencies.isNotEmpty()) append(", \"staticlib\"")
        append("]\n")
        if (dependencies.isNotEmpty()) {
            append("\n[dependencies]\n")
            dependencies.sortedBy { it.packageName }.forEach { dependency ->
                append(dependency.packageName).append(" = { version = \"=").append(dependency.version).append('"')
                if (!dependency.defaultFeatures) append(", default-features = false")
                val features = dependency.features.distinct().sorted()
                if (features.isNotEmpty()) {
                    append(", features = [")
                    features.forEachIndexed { index, feature ->
                        if (index != 0) append(", ")
                        append('"').append(feature).append('"')
                    }
                    append(']')
                }
                append(" }\n")
            }
        }
        append("\n[profile.dev]\n")
        append("codegen-units = 1\n")
        append("opt-level = 1\n")
        append("overflow-checks = false\n")
        append("panic = \"unwind\"\n")
        if (dependencies.isNotEmpty()) append("lto = \"fat\"\n")
        append("\n[profile.release]\n")
        append("codegen-units = 1\n")
        append("overflow-checks = false\n")
        append("panic = \"unwind\"\n")
        if (dependencies.isNotEmpty()) append("lto = \"fat\"\n")
        append("\n[workspace]\n")
    }

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
    val staticLibrary: Path?,
    val nativeStaticLibraries: List<String>,
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
        dependencies: List<RustCargoRegistryDependency> = emptyList(),
    ): RustBitcodeLibraryArtifact {
        val workspace = RustBitcodeLibraryWorkspaceEmitter.emit(
            RustBitcodeLibraryWorkspaceSpec(
                packageName,
                targetTriple,
                renderedLibraryRs,
                outputDirectory,
                release,
                dependencies,
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
        val profileDirectory = targetDirectory.resolve(workspace.targetTriple).resolve(profile)
        val artifactDirectory = profileDirectory.resolve("deps")
        val cratePrefix = workspace.packageName.replace('-', '_')
        deletePackageBitcodeArtifacts(artifactDirectory, cratePrefix)
        val staticLibrary = if (workspace.hasDependencies) profileDirectory.resolve(staticLibraryName(cratePrefix, workspace.targetTriple)) else null
        staticLibrary?.let { Files.deleteIfExists(it) }
        val rustcCommand = buildList {
            addAll(listOf(cargoExecutable, "rustc"))
            addAll(commonArguments)
            if (workspace.release) add("--release")
            add("--message-format=short")
            addAll(listOf("--", if (workspace.hasDependencies) "--emit=llvm-bc,link" else "--emit=llvm-bc"))
            if (workspace.hasDependencies) {
                addAll(listOf("-C", "embed-bitcode=yes"))
                addAll(listOf("--print", "native-static-libs"))
            }
        }
        val rustcOutput = commandExecutor.execute(rustcCommand, workspace.directory)

        val bitcode = bitcodeArtifacts(artifactDirectory, cratePrefix).singleOrNull()
            ?: error("Cargo must produce exactly one Rust LLVM bitcode file in $artifactDirectory")
        if (staticLibrary != null && !Files.isRegularFile(staticLibrary)) {
            error("Cargo must produce a Rust static library at $staticLibrary")
        }
        val nativeStaticLibraries = if (staticLibrary == null) {
            emptyList()
        } else {
            parseNativeStaticLibraries(rustcOutput).ifEmpty {
                error("rustc must report native-static-libs for dependency-enabled Rust bitcode library $staticLibrary")
            }
        }
        val compilerOutput = listOf(clippyOutput, rustcOutput).filter(String::isNotBlank).joinToString("\n")
        return RustBitcodeLibraryArtifact(bitcode, staticLibrary, nativeStaticLibraries, workspace, compilerOutput)
    }

    private fun bitcodeArtifacts(directory: Path, cratePrefix: String): List<Path> =
        Files.list(directory).use { paths ->
            paths.iterator().asSequence()
                .filter { path -> path.fileName.toString().startsWith(cratePrefix) }
                .filter { path -> path.fileName.toString().endsWith(".bc") }
                .sortedBy { it.fileName.toString() }
                .toList()
        }

    private fun parseNativeStaticLibraries(output: String): List<String> = output.lineSequence()
        .mapNotNull { line ->
            val markerIndex = line.indexOf(NATIVE_STATIC_LIBRARIES_MARKER)
            if (markerIndex < 0) null else line.substring(markerIndex + NATIVE_STATIC_LIBRARIES_MARKER.length).trim()
        }
        .filter(String::isNotEmpty)
        .flatMap { it.splitToSequence(Regex("\\s+")) }
        .toList()
}

private val RUST_LIBRARY_PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_-]*")
private val RUST_DEPENDENCY_PACKAGE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
private val RUST_DEPENDENCY_FEATURE = Regex("[A-Za-z0-9_+./?-]+")
private val RUST_EXACT_DEPENDENCY_VERSION =
    Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?")

private fun staticLibraryName(crateName: String, targetTriple: String): String =
    if (targetTriple.endsWith("-msvc")) "$crateName.lib" else "lib$crateName.a"

private const val NATIVE_STATIC_LIBRARIES_MARKER = "native-static-libs:"

private fun String.withSingleTrailingNewline(): String = trimEnd('\r', '\n') + "\n"
