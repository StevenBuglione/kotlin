/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.native.tasks

import org.gradle.api.GradleException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

internal fun stageRustInteropLocalCrates(workspace: Path, cratePaths: Map<String, String>) {
    cratePaths.toSortedMap().forEach { entry ->
        val crateName = entry.key
        if (!CARGO_PACKAGE_NAME.matches(crateName)) {
            throw GradleException("Invalid local Rust interop Cargo package name '$crateName'")
        }
        val source = Paths.get(entry.value).toAbsolutePath().normalize()
        if (!Files.isDirectory(source) || !Files.isRegularFile(source.resolve("Cargo.toml"))) {
            throw GradleException("Local Rust interop crate '$crateName' must be a directory containing Cargo.toml: '$source'")
        }
        val destination = workspace.resolve("local-crates").resolve(crateName)
        Files.walk(source).use { paths ->
            paths.forEach { input ->
                val relative = source.relativize(input)
                if (relative.nameCount == 0 || relative.getName(0).toString() !in EXCLUDED_DIRECTORIES) {
                    val output = destination.resolve(relative.toString())
                    when {
                        Files.isDirectory(input) -> Files.createDirectories(output)
                        Files.isRegularFile(input) -> {
                            Files.createDirectories(output.parent)
                            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                }
            }
        }
    }
}

private val CARGO_PACKAGE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
private val EXCLUDED_DIRECTORIES = setOf(".git", "target")
