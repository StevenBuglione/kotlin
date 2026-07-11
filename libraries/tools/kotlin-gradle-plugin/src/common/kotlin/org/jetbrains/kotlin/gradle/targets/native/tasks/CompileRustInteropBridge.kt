/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.native.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.inject.Inject

@DisableCachingByDefault(because = "The rustc, Cargo, Clippy, and native linker toolchains are not fingerprinted yet")
internal abstract class CompileRustInteropBridge @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    init {
        // Until the complete Rust toolchain fingerprint is an input, every requested build must rerun Clippy and rustc.
        outputs.upToDateWhen { false }
    }

    @get:Input
    abstract val cargoExecutable: Property<String>

    @get:Input
    abstract val targetName: Property<String>

    @get:Input
    abstract val cargoTarget: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cargoManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustSource: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cargoLockFile: RegularFileProperty

    @get:Internal
    abstract val localCratePaths: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localCrateFiles: ConfigurableFileCollection

    @get:LocalState
    abstract val cargoWorkspaceDirectory: DirectoryProperty

    @get:LocalState
    abstract val cargoTargetDirectory: DirectoryProperty

    @get:OutputFile
    abstract val staticLibraryFile: RegularFileProperty

    @get:OutputFile
    abstract val nativeStaticLibrariesFile: RegularFileProperty

    init {
        cargoExecutable.convention("cargo")
        cargoTarget.convention(targetName.map(RustInteropCargoTargets::forKotlinNativeTarget))
    }

    @TaskAction
    fun compile() {
        val kotlinTarget = targetName.get()
        val expectedCargoTarget = RustInteropCargoTargets.forKotlinNativeTarget(kotlinTarget)
        val configuredCargoTarget = cargoTarget.get()
        if (configuredCargoTarget != expectedCargoTarget) {
            throw GradleException(
                "Rust interop target '$kotlinTarget' requires Cargo target '$expectedCargoTarget', " +
                        "but '$configuredCargoTarget' was configured"
            )
        }

        val workspace = cargoWorkspaceDirectory.get().asFile.toPath()
        resetDirectory(workspace)
        val stagedManifest = workspace.resolve("Cargo.toml")
        val stagedLock = workspace.resolve("Cargo.lock")
        val stagedSource = workspace.resolve("src/lib.rs")
        Files.createDirectories(stagedSource.parent)
        Files.copy(cargoManifest.get().asFile.toPath(), stagedManifest, StandardCopyOption.REPLACE_EXISTING)
        Files.copy(cargoLockFile.get().asFile.toPath(), stagedLock, StandardCopyOption.REPLACE_EXISTING)
        Files.copy(rustSource.get().asFile.toPath(), stagedSource, StandardCopyOption.REPLACE_EXISTING)
        stageRustInteropLocalCrates(workspace, localCratePaths.getOrElse(emptyMap()))

        val targetDirectory = cargoTargetDirectory.get().asFile.toPath()
        Files.createDirectories(targetDirectory)
        val archive = targetDirectory.resolve(configuredCargoTarget).resolve("release").resolve("libkotlin_rust_interop.a")
        runCargo(
            workspace = workspace,
            targetDirectory = targetDirectory,
            description = "check the generated Rust interop bridge with Clippy",
            arguments = listOf(
                "clippy",
                "--manifest-path", stagedManifest.toString(),
                "--locked",
                "--target", configuredCargoTarget,
                "--release",
                "--all-targets",
                "--",
                "-D", "warnings",
            ),
        )
        // Force rustc to run for the root staticlib so it always emits --print native-static-libs,
        // even when Cargo's dependency artifacts remain fresh in the local-state target directory.
        Files.deleteIfExists(archive)
        val rustcOutput = runCargo(
            workspace = workspace,
            targetDirectory = targetDirectory,
            description = "compile the generated Rust interop static library",
            arguments = listOf(
                "rustc",
                "--manifest-path", stagedManifest.toString(),
                "--locked",
                "--target", configuredCargoTarget,
                "--release",
                "--lib",
                "--",
                "--print", "native-static-libs",
            ),
        )

        if (!Files.isRegularFile(archive)) {
            throw GradleException(
                "Cargo did not generate the expected Rust interop static library at '$archive' " +
                        "for Kotlin/Native target '$kotlinTarget'"
            )
        }
        val nativeStaticLibraries = parseNativeStaticLibraries(rustcOutput, kotlinTarget)
        copyAtomically(archive, staticLibraryFile.get().asFile.toPath())
        writeAtomically(
            nativeStaticLibrariesFile.get().asFile.toPath(),
            nativeStaticLibraries.joinToString(separator = " ", postfix = "\n"),
        )
    }

    private fun runCargo(
        workspace: Path,
        targetDirectory: Path,
        description: String,
        arguments: List<String>,
    ): String {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec { exec ->
            exec.workingDir(workspace.toFile())
            exec.environment("CARGO_TARGET_DIR", targetDirectory.toString())
            exec.environment("CARGO_TERM_COLOR", "never")
            exec.commandLine(listOf(cargoExecutable.get()) + arguments)
            exec.standardOutput = output
            exec.errorOutput = output
            exec.isIgnoreExitValue = true
        }
        val outputText = output.toString(StandardCharsets.UTF_8.name())
        if (result.exitValue != 0) {
            throw GradleException(
                "Cargo failed to $description (exit code ${result.exitValue}):\n" + outputText.trimEnd()
            )
        }
        return outputText
    }

    private fun parseNativeStaticLibraries(output: String, kotlinTarget: String): List<String> {
        val libraries = output.lineSequence()
            .mapNotNull { line ->
                val markerIndex = line.indexOf(NATIVE_STATIC_LIBRARIES_MARKER)
                if (markerIndex < 0) null else line.substring(markerIndex + NATIVE_STATIC_LIBRARIES_MARKER.length).trim()
            }
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        if (libraries.isEmpty()) {
            throw GradleException(
                "rustc did not report native-static-libs for the Rust interop bridge targeting '$kotlinTarget'"
            )
        }
        return libraries
    }

    private fun resetDirectory(directory: Path) {
        if (Files.exists(directory)) {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
            }
        }
        Files.createDirectories(directory)
    }

    private fun copyAtomically(source: Path, output: Path) {
        val parent = output.parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${output.fileName}.", ".tmp")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            moveAtomically(temporary, output)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun writeAtomically(output: Path, contents: String) {
        val parent = output.parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${output.fileName}.", ".tmp")
        try {
            Files.write(temporary, contents.toByteArray(StandardCharsets.UTF_8))
            moveAtomically(temporary, output)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun moveAtomically(source: Path, output: Path) {
        try {
            Files.move(source, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            Files.move(source, output, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val NATIVE_STATIC_LIBRARIES_MARKER = "native-static-libs:"
    }
}

internal object RustInteropCargoTargets {
    private val cargoTargets = mapOf(
        "androidNativeArm32" to "armv7-linux-androideabi",
        "androidNativeArm64" to "aarch64-linux-android",
        "androidNativeX86" to "i686-linux-android",
        "androidNativeX64" to "x86_64-linux-android",
        "iosArm64" to "aarch64-apple-ios",
        "iosSimulatorArm64" to "aarch64-apple-ios-sim",
        "iosX64" to "x86_64-apple-ios",
        "linuxArm32Hfp" to "armv7-unknown-linux-gnueabihf",
        "linuxArm64" to "aarch64-unknown-linux-gnu",
        "linuxX64" to "x86_64-unknown-linux-gnu",
        "macosArm64" to "aarch64-apple-darwin",
        "macosX64" to "x86_64-apple-darwin",
        "mingwX64" to "x86_64-pc-windows-gnu",
        "tvosArm64" to "aarch64-apple-tvos",
        "tvosSimulatorArm64" to "aarch64-apple-tvos-sim",
        "tvosX64" to "x86_64-apple-tvos",
        "watchosArm32" to "armv7k-apple-watchos",
        "watchosArm64" to "arm64_32-apple-watchos",
        "watchosDeviceArm64" to "aarch64-apple-watchos",
        "watchosSimulatorArm64" to "aarch64-apple-watchos-sim",
        "watchosX64" to "x86_64-apple-watchos",
    )

    fun forKotlinNativeTarget(targetName: String): String = cargoTargets[targetName]
        ?: throw GradleException(
            "Rust interop does not support Kotlin/Native target '$targetName'. " +
                    "Supported targets: ${cargoTargets.keys.sorted().joinToString()}"
        )
}
