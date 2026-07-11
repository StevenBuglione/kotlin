/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.native.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgeArtifactGenerator
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgePlan
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgePlanRenderer
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgeType
import org.jetbrains.kotlin.native.interop.rust.RustInteropTargetPolicy
import org.jetbrains.kotlin.native.interop.rust.RustInteropTomlParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@CacheableTask
internal abstract class GenerateRustInteropBridgeArtifacts : DefaultTask() {
    @get:Input
    abstract val targetName: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bridgePlanFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val definitionFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val canonicalPlans = definitionFiles.files
            .sortedBy { it.invariantSeparatorsPath }
            .map { definition ->
                RustInteropTomlParser.parsePlan(
                    Files.readAllBytes(definition.toPath()).toString(StandardCharsets.UTF_8),
                    definition.name,
                )
            }
            .sortedBy(RustInteropBridgePlanRenderer::render)

        val expectedPlans = canonicalPlans.map(RustInteropBridgePlanRenderer::render).sorted()
        val persistedPlans = bridgePlanFiles.files
            .map { Files.readAllBytes(it.toPath()).toString(StandardCharsets.UTF_8) }
            .sorted()
        if (persistedPlans != expectedPlans) {
            throw GradleException(
                "Generated Rust interop bridge plans do not match their definition files. " +
                        "Run the bridge-plan generation tasks again."
            )
        }

        val plans = canonicalPlans.mapNotNull { it.resolveForTarget(targetName.get()) }
        val artifacts = RustInteropBridgeArtifactGenerator.generate(plans)
        val root = outputDirectory.get().asFile.toPath()
        writeAtomically(root.resolve("Cargo.toml"), artifacts.cargoManifest)
        writeAtomically(root.resolve("src/lib.rs"), artifacts.rustSource)
        writeAtomically(root.resolve("include/kotlin_rust_interop.h"), artifacts.cHeader)
        writeAtomically(root.resolve("metadata/cinterop-package.txt"), artifacts.cInteropPackage + "\n")

        val kotlinRoot = root.resolve("kotlin")
        resetDirectory(kotlinRoot)
        artifacts.kotlinFacades.toSortedMap().forEach { (relativePath, contents) ->
            writeAtomically(safeRelativeOutput(kotlinRoot, relativePath), contents)
        }
    }

    private fun RustInteropBridgePlan.resolveForTarget(target: String): RustInteropBridgePlan? {
        val activeOperations = operations.mapNotNull { operation ->
            val policy = operation.targetPolicy
            val included = policy.includedTargets.isEmpty() || target in policy.includedTargets
            if (!included || target in policy.excludedTargets) return@mapNotNull null
            operation.copy(targetPolicy = RustInteropTargetPolicy(emptyList(), emptyList()))
        }
        if (activeOperations.isEmpty()) return null

        val activeHandleIds = buildSet {
            activeOperations.forEach { operation ->
                operation.receiver.handleId?.let { add(it) }
                operation.parameters.forEach { parameter -> addHandleIds(parameter.type) }
                addHandleIds(operation.returnType)
            }
        }
        return copy(
            handles = handles.filter { it.id in activeHandleIds },
            operations = activeOperations,
        )
    }

    private fun MutableSet<String>.addHandleIds(type: RustInteropBridgeType) {
        when (type) {
            is RustInteropBridgeType.Handle -> add(type.id)
            is RustInteropBridgeType.Option -> addHandleIds(type.valueType)
            else -> Unit
        }
    }

    private fun safeRelativeOutput(root: Path, relativePath: String): Path {
        if (relativePath.isBlank() || '\\' in relativePath) {
            throw GradleException("Rust interop generated an invalid Kotlin facade path: '$relativePath'")
        }
        val relative = root.fileSystem.getPath(relativePath).normalize()
        val output = root.resolve(relative).normalize()
        if (relative.isAbsolute || relative.startsWith("..") || !output.startsWith(root.normalize())) {
            throw GradleException("Rust interop generated an invalid Kotlin facade path: '$relativePath'")
        }
        if (!output.fileName.toString().endsWith(".kt")) {
            throw GradleException("Rust interop generated a non-Kotlin facade path: '$relativePath'")
        }
        return output
    }

    private fun resetDirectory(directory: Path) {
        if (Files.exists(directory)) {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
            }
        }
        Files.createDirectories(directory)
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
}
