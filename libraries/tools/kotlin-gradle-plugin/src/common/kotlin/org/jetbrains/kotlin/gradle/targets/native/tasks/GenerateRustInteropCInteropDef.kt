/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.native.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@CacheableTask
internal abstract class GenerateRustInteropCInteropDef : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headerFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cInteropPackageFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val staticLibraryFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeStaticLibrariesFile: RegularFileProperty

    @get:OutputFile
    abstract val definitionFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val cInteropPackage = readRequiredSingleLine(cInteropPackageFile.get().asFile.toPath(), "C interop package")
        if (!KOTLIN_PACKAGE.matches(cInteropPackage)) {
            throw GradleException("Rust interop generated an invalid C interop package '$cInteropPackage'")
        }

        val header = headerFile.get().asFile.toPath().toAbsolutePath().normalize()
        val staticLibrary = staticLibraryFile.get().asFile.toPath().toAbsolutePath().normalize()
        val nativeStaticLibraries = readUtf8(nativeStaticLibrariesFile.get().asFile.toPath()).trim()
        if (nativeStaticLibraries.any { it == '\r' || it == '\n' }) {
            throw GradleException("Rust interop native static libraries metadata must contain at most one line")
        }
        val definition = buildString {
            append("headers = ").append(quoteDefArgument(header.invariantSeparatorsPath())).append('\n')
            append("package = ").append(cInteropPackage).append('\n')
            append("staticLibraries = ").append(quoteDefArgument(staticLibrary.fileName.toString())).append('\n')
            append("libraryPaths = ").append(quoteDefArgument(staticLibrary.parent.invariantSeparatorsPath())).append('\n')
            if (nativeStaticLibraries.isNotEmpty()) {
                append("linkerOpts = ").append(nativeStaticLibraries).append('\n')
            }
        }
        writeAtomically(definitionFile.get().asFile.toPath(), definition)
    }

    private fun readRequiredSingleLine(path: Path, description: String): String {
        val value = readUtf8(path).trim()
        if (value.isEmpty() || value.any { it == '\r' || it == '\n' }) {
            throw GradleException("Rust interop $description metadata must contain exactly one non-empty line")
        }
        return value
    }

    private fun readUtf8(path: Path): String = Files.readAllBytes(path).toString(StandardCharsets.UTF_8)

    private fun Path.invariantSeparatorsPath(): String = toString().replace('\\', '/')

    private fun quoteDefArgument(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\', '"' -> append('\\').append(character)
                else -> append(character)
            }
        }
        append('"')
    }

    private fun writeAtomically(output: Path, contents: String) {
        val parent = output.parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${output.fileName}.", ".tmp")
        try {
            Files.write(temporary, contents.toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: IOException) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        val KOTLIN_PACKAGE = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    }
}
