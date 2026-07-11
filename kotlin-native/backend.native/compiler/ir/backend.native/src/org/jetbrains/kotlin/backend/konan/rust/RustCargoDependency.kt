/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import java.nio.file.Path

internal data class RustCargoDependency(
    val packageName: String,
    val version: String,
    val features: List<String> = emptyList(),
    val defaultFeatures: Boolean = true,
    val localPathOverride: Path? = null,
) {
    init {
        require(RUST_DEPENDENCY_PACKAGE_NAME.matches(packageName)) { "Invalid Cargo dependency package name: $packageName" }
        require(RUST_EXACT_DEPENDENCY_VERSION.matches(version)) {
            "Invalid exact Cargo dependency version for '$packageName': $version"
        }
        require(features.all(RUST_DEPENDENCY_FEATURE::matches)) {
            "Invalid Cargo feature for dependency '$packageName': ${features.firstOrNull { !RUST_DEPENDENCY_FEATURE.matches(it) }}"
        }
    }
}

internal fun validateRustCargoDependencies(dependencies: List<RustCargoDependency>) {
    val crateNames = dependencies.map { it.packageName.replace('-', '_') }
    require(crateNames.size == crateNames.toSet().size) {
        "Cargo dependency package names must remain unique after replacing '-' with '_'"
    }
}

internal fun StringBuilder.appendRustCargoDependencies(dependencies: List<RustCargoDependency>) {
    if (dependencies.isEmpty()) return
    append("\n[dependencies]\n")
    dependencies.sortedBy { it.packageName }.forEach { dependency ->
        append(dependency.packageName).append(" = { version = \"=").append(dependency.version).append('"')
        dependency.localPathOverride?.let { path ->
            append(", path = \"")
                .append(path.toAbsolutePath().normalize().toString().tomlBasicStringContent())
                .append('"')
        }
        if (!dependency.defaultFeatures) append(", default-features = false")
        val features = dependency.features.distinct().sorted()
        if (features.isNotEmpty()) {
            append(", features = [")
            features.forEachIndexed { index, feature ->
                if (index != 0) append(", ")
                append('"').append(feature).append('"')
            }
            append(']')
        }
        append(" }\n")
    }
}

private fun String.tomlBasicStringContent(): String = buildString(length) {
    for (character in this@tomlBasicStringContent) {
        when (character) {
            '\b' -> append("\\b")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\u000C' -> append("\\f")
            '\r' -> append("\\r")
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> if (character.code <= 0x1f || character.code == 0x7f) {
                append("\\u").append(character.code.toString(16).uppercase().padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
}

private val RUST_DEPENDENCY_PACKAGE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
private val RUST_DEPENDENCY_FEATURE = Regex("[A-Za-z0-9_+./?-]+")
private val RUST_EXACT_DEPENDENCY_VERSION =
    Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?")
