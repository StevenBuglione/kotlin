/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RustCargoWorkspaceEmitterTest {
    @Test
    fun emitsDeterministicSinglePackageWorkspace() = withTemporaryDirectory { directory ->
        val workspace = RustCargoWorkspaceEmitter.emit(
            RustCargoWorkspaceSpec(
                packageName = "kotlin_rust_program",
                targetTriple = "x86_64-pc-windows-msvc",
                mainRs = "fn main() { println!(\"42\"); }",
                outputDirectory = directory,
            )
        )

        assertEquals(directory.toAbsolutePath().normalize(), workspace.directory)
        assertEquals(directory.resolve("Cargo.toml"), workspace.manifest)
        assertEquals(directory.resolve("src/main.rs"), workspace.mainSource)
        assertEquals(
            """
                [package]
                name = "kotlin_rust_program"
                version = "0.0.0"
                edition = "2021"
                publish = false

                [[bin]]
                name = "kotlin_rust_program"
                path = "src/main.rs"

                [workspace]
            """.trimIndent() + "\n",
            Files.readAllBytes(workspace.manifest).toString(StandardCharsets.UTF_8),
        )
        assertEquals(
            "fn main() { println!(\"42\"); }\n",
            Files.readAllBytes(workspace.mainSource).toString(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun doesNotRewriteUnchangedFiles() = withTemporaryDirectory { directory ->
        val spec = RustCargoWorkspaceSpec(
            packageName = "stable_program",
            targetTriple = "x86_64-unknown-linux-gnu",
            mainRs = "fn main() {}\n",
            outputDirectory = directory,
        )
        val first = RustCargoWorkspaceEmitter.emit(spec)
        val fixedTimestamp = FileTime.fromMillis(1_700_000_000_000L)
        Files.setLastModifiedTime(first.manifest, fixedTimestamp)
        Files.setLastModifiedTime(first.mainSource, fixedTimestamp)

        val second = RustCargoWorkspaceEmitter.emit(spec)

        assertEquals(fixedTimestamp, Files.getLastModifiedTime(second.manifest))
        assertEquals(fixedTimestamp, Files.getLastModifiedTime(second.mainSource))
        assertTrue(Files.notExists(directory.resolve(".Cargo.toml.tmp")))
        assertTrue(Files.notExists(directory.resolve("src/.main.rs.tmp")))
    }

    @Test
    fun emitsNoStdBitcodeLibraryWorkspace() = withTemporaryDirectory { directory ->
        val workspace = RustBitcodeLibraryWorkspaceEmitter.emit(
            RustBitcodeLibraryWorkspaceSpec(
                packageName = "kotlin_rust_module",
                targetTriple = "x86_64-unknown-linux-gnu",
                libraryRs = "#![no_std]\npub fn twice(value: i32) -> i32 { value * 2 }",
                outputDirectory = directory,
                release = false,
            )
        )

        assertEquals(directory.resolve("src/lib.rs"), workspace.librarySource)
        assertEquals(
            """
                [package]
                name = "kotlin_rust_module"
                version = "0.0.0"
                edition = "2021"
                publish = false

                [lib]
                name = "kotlin_rust_module"
                path = "src/lib.rs"
                crate-type = ["rlib"]

                [profile.dev]
                codegen-units = 1
                opt-level = 1
                overflow-checks = false
                panic = "unwind"

                [profile.release]
                codegen-units = 1
                overflow-checks = false
                panic = "unwind"

                [workspace]
            """.trimIndent() + "\n",
            Files.readAllBytes(workspace.manifest).toString(StandardCharsets.UTF_8),
        )
        assertEquals(
            "#![no_std]\npub fn twice(value: i32) -> i32 { value * 2 }\n",
            Files.readAllBytes(workspace.librarySource).toString(StandardCharsets.UTF_8),
        )
    }

    private inline fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("rust-workspace-emitter-test")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
