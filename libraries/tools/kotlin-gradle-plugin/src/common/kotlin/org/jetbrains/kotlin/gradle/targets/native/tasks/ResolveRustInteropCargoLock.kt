/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.native.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
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

@DisableCachingByDefault(because = "Cargo registry resolution and the Cargo toolchain are not fingerprinted yet")
internal abstract class ResolveRustInteropCargoLock @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val cargoExecutable: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cargoManifest: RegularFileProperty

    @get:LocalState
    abstract val cargoWorkspaceDirectory: DirectoryProperty

    @get:OutputFile
    abstract val cargoLockFile: RegularFileProperty

    init {
        cargoExecutable.convention("cargo")
    }

    @TaskAction
    fun resolve() {
        val workspace = cargoWorkspaceDirectory.get().asFile.toPath()
        resetDirectory(workspace)
        val stagedManifest = workspace.resolve("Cargo.toml")
        Files.copy(cargoManifest.get().asFile.toPath(), stagedManifest, StandardCopyOption.REPLACE_EXISTING)

        // Cargo validates that the generated static-library target exists while resolving dependencies.
        val sourceDirectory = workspace.resolve("src")
        Files.createDirectories(sourceDirectory)
        Files.write(sourceDirectory.resolve("lib.rs"), ByteArray(0))

        runCargo(
            workspace,
            listOf(
                "generate-lockfile",
                "--manifest-path", stagedManifest.toString(),
                "--color", "never",
            ),
        )

        val resolvedLock = workspace.resolve("Cargo.lock")
        if (!Files.isRegularFile(resolvedLock)) {
            throw GradleException("Cargo did not generate the expected Rust interop lock file at '$resolvedLock'")
        }
        copyAtomically(resolvedLock, cargoLockFile.get().asFile.toPath())
    }

    private fun runCargo(workspace: Path, arguments: List<String>) {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec { exec ->
            exec.workingDir(workspace.toFile())
            exec.environment("CARGO_TERM_COLOR", "never")
            exec.commandLine(listOf(cargoExecutable.get()) + arguments)
            exec.standardOutput = output
            exec.errorOutput = output
            exec.isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            throw GradleException(
                "Cargo failed to resolve the Rust interop lock file (exit code ${result.exitValue}):\n" +
                        output.toString(StandardCharsets.UTF_8.name()).trimEnd()
            )
        }
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
            try {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: IOException) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
