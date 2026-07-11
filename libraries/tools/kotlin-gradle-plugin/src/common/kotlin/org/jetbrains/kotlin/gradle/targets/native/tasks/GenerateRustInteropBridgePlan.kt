/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.native.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgePlanRenderer
import org.jetbrains.kotlin.native.interop.rust.RustInteropTomlParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@CacheableTask
internal abstract class GenerateRustInteropBridgePlan : DefaultTask() {
    @get:Input
    abstract val interopName: Property<String>

    @get:Input
    abstract val crateName: Property<String>

    @get:Input
    abstract val crateVersion: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val features: SetProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val definitionFile: RegularFileProperty

    @get:OutputFile
    abstract val bridgePlanFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val definition = definitionFile.get().asFile
        val plan = RustInteropTomlParser.parsePlan(
            Files.readAllBytes(definition.toPath()).toString(StandardCharsets.UTF_8),
            definition.name,
        )

        val mismatches = buildList {
            mismatch("crate name", crateName.get(), plan.crate.name)
            mismatch("crate version", crateVersion.get(), plan.crate.version)
            mismatch("package name", packageName.get(), plan.kotlinPackage)

            val configuredFeatures = features.get()
            val definitionFeatures = plan.crate.features.toSet()
            if (configuredFeatures != definitionFeatures) {
                add(
                    "features: DSL declares ${configuredFeatures.sorted()}, " +
                            "but '${definition.name}' declares ${definitionFeatures.sorted()}"
                )
            }
        }
        if (mismatches.isNotEmpty()) {
            throw GradleException(
                "Rust interop '${interopName.get()}' does not match its canonical definition:\n" +
                        mismatches.joinToString(separator = "\n") { " - $it" }
            )
        }

        writeAtomically(bridgePlanFile.get().asFile.toPath(), RustInteropBridgePlanRenderer.render(plan))
    }

    private fun MutableList<String>.mismatch(label: String, configured: String, canonical: String) {
        if (configured != canonical) {
            add("$label: DSL declares '$configured', but the definition declares '$canonical'")
        }
    }

    private fun writeAtomically(output: java.nio.file.Path, contents: String) {
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
