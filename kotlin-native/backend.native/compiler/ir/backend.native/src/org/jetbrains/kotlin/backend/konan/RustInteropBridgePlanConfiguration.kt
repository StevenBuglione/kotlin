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
