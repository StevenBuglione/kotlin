/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

private val RUST_INTEROP_BRIDGE_PLAN_PATHS =
    CompilerConfigurationKey.create<List<String>>("RUST_INTEROP_BRIDGE_PLAN_PATHS")

internal var CompilerConfiguration.rustInteropBridgePlanPaths: List<String>
    get() = get(RUST_INTEROP_BRIDGE_PLAN_PATHS, emptyList())
    set(value) = put(RUST_INTEROP_BRIDGE_PLAN_PATHS, value)

internal fun CompilerConfiguration.configureRustInteropBridgePlanPaths(paths: Array<String>) {
    rustInteropBridgePlanPaths = paths.map { java.io.File(it).absoluteFile.normalize().path }
}

private val RUST_INTEROP_CRATE_PATHS =
    CompilerConfigurationKey.create<Map<String, String>>("RUST_INTEROP_CRATE_PATHS")

internal var CompilerConfiguration.rustInteropCratePaths: Map<String, String>
    get() = get(RUST_INTEROP_CRATE_PATHS, emptyMap())
    set(value) = put(RUST_INTEROP_CRATE_PATHS, value)

internal fun CompilerConfiguration.configureRustInteropCratePaths(overrides: Array<String>) {
    val result = linkedMapOf<String, String>()
    overrides.forEach { override ->
        val separator = override.indexOf('=')
        require(separator > 0 && separator < override.lastIndex) {
            "Rust interop crate path must use '<crate>=<path>': $override"
        }
        val crateName = override.substring(0, separator)
        require(CARGO_PACKAGE_NAME.matches(crateName)) { "Invalid Rust interop Cargo package name: $crateName" }
        val path = java.io.File(override.substring(separator + 1)).absoluteFile.normalize().path
        val previous = result.putIfAbsent(crateName, path)
        require(previous == null || previous == path) {
            "Conflicting local paths for Rust interop crate '$crateName': '$previous' and '$path'"
        }
    }
    rustInteropCratePaths = result
}

private val CARGO_PACKAGE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
