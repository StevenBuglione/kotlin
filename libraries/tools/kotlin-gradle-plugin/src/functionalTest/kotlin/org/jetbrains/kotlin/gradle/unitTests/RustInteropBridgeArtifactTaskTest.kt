/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.unitTests

import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinNativeTargetConfigurator
import org.jetbrains.kotlin.gradle.targets.native.tasks.GenerateRustInteropBridgeArtifacts
import org.jetbrains.kotlin.gradle.targets.native.tasks.GenerateRustInteropBridgePlan
import org.jetbrains.kotlin.gradle.util.MultiplatformExtensionTest
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgeArtifactGenerator
import org.jetbrains.kotlin.native.interop.rust.RustInteropTomlParser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalKotlinGradlePluginApi::class)
class RustInteropBridgeArtifactTaskTest : MultiplatformExtensionTest() {
    @Test
    fun `does not register aggregate artifacts for an LLVM-only compilation`() {
        kotlin.linuxX64()

        project.evaluate()

        assertFalse(ARTIFACT_TASK_NAME in project.tasks.names)
    }

    @Test
    fun `registers one aggregate artifact task for all compilation interops`() {
        val compilation = kotlin.linuxX64().compilations.getByName("main")
        addRegexInterop("regex", "regex.rustinterop.toml")
        addRegexInterop("regexAlias", "regex-alias.rustinterop.toml")

        project.evaluate()

        val task = project.tasks.getByName(ARTIFACT_TASK_NAME) as GenerateRustInteropBridgeArtifacts
        assertEquals(KotlinNativeTargetConfigurator.INTEROP_GROUP, task.group)
        assertEquals(
            "Generates aggregate Rust interop bridge artifacts for compilation 'main' of target 'linuxX64'.",
            task.description,
        )
        assertEquals(
            project.layout.buildDirectory.dir("rustInterop/linuxX64/main/bridge").get().asFile,
            task.outputDirectory.get().asFile,
        )
        assertEquals(
            setOf("regex.rustinterop.toml", "regex-alias.rustinterop.toml"),
            task.definitionFiles.files.mapTo(mutableSetOf()) { it.name },
        )
        assertEquals(
            setOf(
                "generateLinuxX64MainRegexRustInteropBridgePlan",
                "generateLinuxX64MainRegexAliasRustInteropBridgePlan",
            ),
            task.taskDependencies.getDependencies(task).mapTo(mutableSetOf()) { it.name },
        )

        val compileTask = compilation.compileTaskProvider.get()
        assertTrue(
            task in compileTask.taskDependencies.getDependencies(compileTask),
            "The Native compile task must depend on the aggregate Rust bridge-artifact task",
        )
    }

    @Test
    fun `writes the four canonical aggregate artifacts`() {
        addRegexInterop("regex", "regex.rustinterop.toml")
        project.evaluate()

        val planTask = project.tasks.getByName("generateLinuxX64MainRegexRustInteropBridgePlan") as GenerateRustInteropBridgePlan
        planTask.generate()
        val task = project.tasks.getByName(ARTIFACT_TASK_NAME) as GenerateRustInteropBridgeArtifacts
        task.generate()

        val expected = RustInteropBridgeArtifactGenerator.generate(
            RustInteropTomlParser.parsePlan(REGEX_DEFINITION, "regex.rustinterop.toml")
        )
        val root = task.outputDirectory.get().asFile
        assertEquals(expected.cargoManifest, root.resolve("Cargo.toml").readText())
        assertEquals(expected.rustSource, root.resolve("src/lib.rs").readText())
        assertEquals(expected.cHeader, root.resolve("include/kotlin_rust_interop.h").readText())
        assertEquals(
            expected.kotlinFacades,
            root.resolve("kotlin").walkTopDown()
                .filter { it.isFile }
                .associate { it.relativeTo(root.resolve("kotlin")).invariantSeparatorsPath to it.readText() },
        )
    }

    @Test
    fun `local crate produces a stable relative Cargo dependency and tracked filtered sources`() {
        val compilation = kotlin.linuxX64().compilations.getByName("main")
        val definition = project.file("src/nativeInterop/rust/regex.rustinterop.toml").apply {
            parentFile.mkdirs()
            writeText(REGEX_DEFINITION)
        }
        val localCrate = project.file("rust-crates/regex")
        val manifest = localCrate.resolve("Cargo.toml").apply {
            parentFile.mkdirs()
            writeText("[package]\nname = \"regex\"\nversion = \"1.11.1\"\n")
        }
        val source = localCrate.resolve("src/lib.rs").apply {
            parentFile.mkdirs()
            writeText("pub struct Regex;\n")
        }
        val ignored = localCrate.resolve("target/debug/stale").apply {
            parentFile.mkdirs()
            writeText("stale")
        }
        compilation.rustInterops.create("regex") {
            it.crate("regex", "1.11.1")
            it.packageName.set("rust.regex")
            it.definitionFile.set(definition)
            it.features.add("unicode")
            it.localCrateDirectory.set(localCrate)
        }
        project.evaluate()

        (project.tasks.getByName("generateLinuxX64MainRegexRustInteropBridgePlan") as GenerateRustInteropBridgePlan).generate()
        val task = project.tasks.getByName(ARTIFACT_TASK_NAME) as GenerateRustInteropBridgeArtifacts
        task.generate()

        val cargoManifest = task.outputDirectory.file("Cargo.toml").get().asFile.readText()
        assertContains(
            cargoManifest,
            "regex = { version = \"=1.11.1\", path = \"local-crates/regex\", features = [\"unicode\"] }",
        )
        assertFalse(localCrate.absolutePath in cargoManifest)
        assertEquals(mapOf("regex" to localCrate.absolutePath), task.localCratePaths.get())
        assertTrue(manifest in task.localCrateDirectories.files)
        assertTrue(source in task.localCrateDirectories.files)
        assertTrue(ignored !in task.localCrateDirectories.files)
    }

    @Test
    fun `rejects a persisted plan that was changed after plan generation`() {
        addRegexInterop("regex", "regex.rustinterop.toml")
        project.evaluate()

        val planTask = project.tasks.getByName("generateLinuxX64MainRegexRustInteropBridgePlan") as GenerateRustInteropBridgePlan
        planTask.generate()
        planTask.bridgePlanFile.get().asFile.writeText("{}\n")

        val failure = assertFailsWith<GradleException> {
            (project.tasks.getByName(ARTIFACT_TASK_NAME) as GenerateRustInteropBridgeArtifacts).generate()
        }
        assertEquals(
            "Generated Rust interop bridge plans do not match their definition files. " +
                    "Run the bridge-plan generation tasks again.",
            failure.message,
        )
    }

    private fun addRegexInterop(name: String, definitionName: String) {
        val compilation = kotlin.linuxX64().compilations.getByName("main")
        val definition = project.file("src/nativeInterop/rust/$definitionName")
        definition.parentFile.mkdirs()
        definition.writeText(REGEX_DEFINITION)
        compilation.rustInterops.create(name) {
            it.crate("regex", "1.11.1")
            it.packageName.set("rust.regex")
            it.definitionFile.set(definition)
            it.features.add("unicode")
        }
    }

    private companion object {
        const val ARTIFACT_TASK_NAME = "generateLinuxX64MainRustInteropBridgeArtifacts"

        val REGEX_DEFINITION = """
            schema = 1
            package = "rust.regex"

            [crate]
            name = "regex"
            version = "1.11.1"
            features = ["unicode"]
            default-features = true

            [[handle]]
            id = "regex"
            rust-type = "regex::Regex"
            kotlin-type = "Regex"
            threading = "send-sync"

            [[operation]]
            id = "compile"
            kind = "constructor"
            rust-path = "regex::Regex::new"
            kotlin-name = "Regex.compile"
            receiver = "none"
            parameters = ["pattern:string"]
            return = "handle:regex"
            error = "kotlin-exception:RegexException"
            panic = "kotlin-exception:RegexException"
            threading = "caller"
            async = false

            [[operation]]
            id = "is-match"
            kind = "method"
            rust-path = "regex::Regex::is_match"
            kotlin-name = "Regex.isMatch"
            receiver = "borrow:regex"
            parameters = ["input:string"]
            return = "bool"
            error = "none"
            panic = "kotlin-exception:RegexException"
            threading = "caller"
            async = false

            [[operation]]
            id = "pattern"
            kind = "property-get"
            rust-path = "regex::Regex::as_str"
            kotlin-name = "Regex.pattern"
            receiver = "borrow:regex"
            parameters = []
            return = "string"
            error = "none"
            panic = "kotlin-exception:RegexException"
            threading = "caller"
            async = false

            [[operation]]
            id = "close"
            kind = "close"
            rust-path = "core::mem::drop"
            kotlin-name = "Regex.close"
            receiver = "consume:regex"
            parameters = []
            return = "unit"
            error = "none"
            panic = "kotlin-exception:RegexException"
            threading = "caller"
            async = false
        """.trimIndent()
    }
}
