/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.unitTests

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.util.MultiplatformExtensionTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalKotlinGradlePluginApi::class)
class RustInteropSettingsTest : MultiplatformExtensionTest() {
    @Test
    fun `rust interop settings expose lazy crate configuration`() {
        val compilation = kotlin.linuxX64().compilations.getByName("main")
        val rustInterop = compilation.rustInterops.create("regex") {
            it.crate("regex", "1.11.1")
            it.packageName.set("rust.regex")
            it.features.add("unicode")
        }

        assertEquals("regex", rustInterop.name)
        assertEquals("regex", rustInterop.crateName.get())
        assertEquals("1.11.1", rustInterop.crateVersion.get())
        assertEquals("rust.regex", rustInterop.packageName.get())
        assertEquals(setOf("unicode"), rustInterop.features.get())
        assertEquals(
            project.file("src/nativeInterop/rust/regex.rustinterop.toml"),
            rustInterop.definitionFile.get().asFile,
        )
    }
}
