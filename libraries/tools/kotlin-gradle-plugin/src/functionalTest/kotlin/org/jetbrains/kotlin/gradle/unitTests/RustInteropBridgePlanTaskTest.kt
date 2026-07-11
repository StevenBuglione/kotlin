/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.unitTests

import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinNativeTargetConfigurator
import org.jetbrains.kotlin.gradle.targets.native.tasks.GenerateRustInteropBridgePlan
import org.jetbrains.kotlin.gradle.util.MultiplatformExtensionTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalKotlinGradlePluginApi::class)
class RustInteropBridgePlanTaskTest : MultiplatformExtensionTest() {
    @Test
    fun `registered task generates canonical regex bridge plan before native compilation`() {
        val compilation = kotlin.linuxX64().compilations.getByName("main")
        val definition = project.file("src/nativeInterop/rust/regex.rustinterop.toml")
        definition.parentFile.mkdirs()
        definition.writeText(REGEX_DEFINITION)

        compilation.rustInterops.create("regex") {
            it.crate("regex", "1.11.1")
            it.packageName.set("rust.regex")
            it.definitionFile.set(definition)
            it.features.add("unicode")
        }

        project.evaluate()

        val task = project.tasks.getByName(TASK_NAME) as GenerateRustInteropBridgePlan
        assertEquals(TASK_NAME, task.name)
        assertEquals(KotlinNativeTargetConfigurator.INTEROP_GROUP, task.group)
        assertEquals(
            "Generates Rust interop bridge plan 'regex' for compilation 'main' of target 'linuxX64'.",
            task.description,
        )
        assertEquals("regex", task.interopName.get())
        assertEquals("regex", task.crateName.get())
        assertEquals("1.11.1", task.crateVersion.get())
        assertEquals("rust.regex", task.packageName.get())
        assertEquals(setOf("unicode"), task.features.get())
        assertEquals(definition, task.definitionFile.get().asFile)
        assertEquals(
            project.layout.buildDirectory.file("rustInterop/linuxX64/main/regex/bridge-plan.json").get().asFile,
            task.bridgePlanFile.get().asFile,
        )

        val compileTask = compilation.compileTaskProvider.get()
        assertTrue(
            task in compileTask.taskDependencies.getDependencies(compileTask),
            "The Native compile task must depend on its Rust interop bridge-plan task",
        )

        task.generate()

        assertEquals(EXPECTED_REGEX_JSON, task.bridgePlanFile.get().asFile.readText())
    }

    @Test
    fun `task rejects crate configuration that differs from canonical definition`() {
        val compilation = kotlin.linuxX64().compilations.getByName("main")
        val definition = project.file("src/nativeInterop/rust/regex.rustinterop.toml")
        definition.parentFile.mkdirs()
        definition.writeText(REGEX_DEFINITION)

        compilation.rustInterops.create("regex") {
            it.crate("regex", "1.12.0")
            it.packageName.set("rust.regex")
            it.definitionFile.set(definition)
            it.features.add("unicode")
        }

        project.evaluate()

        val failure = assertFailsWith<GradleException> {
            (project.tasks.getByName(TASK_NAME) as GenerateRustInteropBridgePlan).generate()
        }
        assertTrue(failure.message.orEmpty().contains("Rust interop 'regex' does not match its canonical definition"))
        assertTrue(
            failure.message.orEmpty().contains("crate version: DSL declares '1.12.0', but the definition declares '1.11.1'"),
        )
    }

    private companion object {
        const val TASK_NAME = "generateLinuxX64MainRegexRustInteropBridgePlan"

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

        val EXPECTED_REGEX_JSON = """
            {"schema":1,"package":"rust.regex","crate":{"name":"regex","version":"1.11.1","features":["unicode"],"defaultFeatures":true},"handles":[{"id":"regex","rustType":"regex::Regex","kotlinType":"Regex","threading":"send-sync"}],"operations":[{"id":"close","kind":"close","rustPath":"core::mem::drop","kotlinName":"Regex.close","receiver":{"ownership":"consume","handle":"regex"},"parameters":[],"return":"unit","error":{"mode":"none","exception":null},"panic":{"mode":"kotlin-exception","exception":"RegexException"},"threading":"caller","async":"synchronous","targets":[],"excludedTargets":[]},{"id":"compile","kind":"constructor","rustPath":"regex::Regex::new","kotlinName":"Regex.compile","receiver":{"ownership":"none","handle":null},"parameters":[{"name":"pattern","type":"string"}],"return":"handle:regex","error":{"mode":"kotlin-exception","exception":"RegexException"},"panic":{"mode":"kotlin-exception","exception":"RegexException"},"threading":"caller","async":"synchronous","targets":[],"excludedTargets":[]},{"id":"is-match","kind":"method","rustPath":"regex::Regex::is_match","kotlinName":"Regex.isMatch","receiver":{"ownership":"borrow","handle":"regex"},"parameters":[{"name":"input","type":"string"}],"return":"bool","error":{"mode":"none","exception":null},"panic":{"mode":"kotlin-exception","exception":"RegexException"},"threading":"caller","async":"synchronous","targets":[],"excludedTargets":[]},{"id":"pattern","kind":"property-get","rustPath":"regex::Regex::as_str","kotlinName":"Regex.pattern","receiver":{"ownership":"borrow","handle":"regex"},"parameters":[],"return":"string","error":{"mode":"none","exception":null},"panic":{"mode":"kotlin-exception","exception":"RegexException"},"threading":"caller","async":"synchronous","targets":[],"excludedTargets":[]}]}
        """.trimIndent() + "\n"
    }
}
