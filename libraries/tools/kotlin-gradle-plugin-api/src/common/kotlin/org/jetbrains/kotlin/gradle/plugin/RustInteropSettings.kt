/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin

import org.gradle.api.Named
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

/**
 * Configures bindings to a Rust crate for a Kotlin/Native compilation.
 */
@ExperimentalKotlinGradlePluginApi
interface RustInteropSettings : Named {
    /** The Cargo package name of the crate. */
    val crateName: Property<String>

    /** The Cargo package version requirement of the crate. */
    val crateVersion: Property<String>

    /** Configures the Cargo package name and version requirement. */
    fun crate(name: String, version: String) {
        crateName.set(name)
        crateVersion.set(version)
    }

    /** The package for the generated Kotlin facade. */
    val packageName: Property<String>

    /** The operation mapping used to generate the Rust bridge and Kotlin facade. */
    val definitionFile: RegularFileProperty

    /** Cargo features enabled for this crate. */
    val features: SetProperty<String>
}
