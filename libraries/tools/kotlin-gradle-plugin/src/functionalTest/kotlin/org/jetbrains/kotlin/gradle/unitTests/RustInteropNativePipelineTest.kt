/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.unitTests

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultCInteropSettings
import org.jetbrains.kotlin.gradle.targets.native.tasks.CompileRustInteropBridge
import org.jetbrains.kotlin.gradle.targets.native.tasks.GenerateRustInteropBridgeArtifacts
import org.jetbrains.kotlin.gradle.targets.native.tasks.GenerateRustInteropCInteropDef
import org.jetbrains.kotlin.gradle.targets.native.tasks.ResolveRustInteropCargoLock
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import org.jetbrains.kotlin.gradle.util.MultiplatformExtensionTest
import org.jetbrains.kotlin.gradle.utils.getFile
import org.jetbrains.kotlin.konan.target.HostManager
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalKotlinGradlePluginApi::class)
class RustInteropNativePipelineTest : MultiplatformExtensionTest() {
    @Test
    fun `registers ordered native pipeline and wires generated cinterop and Kotlin facade sources`() {
        val compilation = kotlin.linuxX64().compilations.getByName("main")
        addConfiguredRustInterop()

        project.evaluate()

        val artifactTask = project.tasks.getByName(ARTIFACT_TASK_NAME) as GenerateRustInteropBridgeArtifacts
        val lockTask = project.tasks.getByName(LOCK_TASK_NAME) as ResolveRustInteropCargoLock
        val compileBridgeTask = project.tasks.getByName(COMPILE_BRIDGE_TASK_NAME) as CompileRustInteropBridge
        val defTask = project.tasks.getByName(DEF_TASK_NAME) as GenerateRustInteropCInteropDef
        val cInteropTask = project.tasks.getByName(CINTEROP_TASK_NAME) as CInteropProcess

        assertTrue(artifactTask in lockTask.taskDependencies.getDependencies(lockTask))
        assertTrue(lockTask in compileBridgeTask.taskDependencies.getDependencies(compileBridgeTask))
        assertTrue(artifactTask in compileBridgeTask.taskDependencies.getDependencies(compileBridgeTask))
        assertTrue(compileBridgeTask in defTask.taskDependencies.getDependencies(defTask))
        assertTrue(artifactTask in defTask.taskDependencies.getDependencies(defTask))
        assertTrue(defTask in cInteropTask.taskDependencies.getDependencies(cInteropTask))

        assertEquals("cargo", lockTask.cargoExecutable.get())
        assertEquals("cargo", compileBridgeTask.cargoExecutable.get())
        assertEquals("linuxX64", compileBridgeTask.targetName.get())
        assertEquals(LINUX_X64_CARGO_TARGET, compileBridgeTask.cargoTarget.get())
        assertEquals(
            project.layout.buildDirectory.file("rustInterop/linuxX64/main/cargo/Cargo.lock").get().asFile,
            lockTask.cargoLockFile.get().asFile,
        )
        assertEquals(
            project.layout.buildDirectory.dir("rustInterop/linuxX64/main/cargo/lock-workspace").get().asFile,
            lockTask.cargoWorkspaceDirectory.get().asFile,
        )
        assertEquals(
            project.layout.buildDirectory.dir("rustInterop/linuxX64/main/cargo/compile-workspace").get().asFile,
            compileBridgeTask.cargoWorkspaceDirectory.get().asFile,
        )
        assertEquals(
            project.layout.buildDirectory.file("rustInterop/linuxX64/main/cargo/lib/libkotlin_rust_interop.a").get().asFile,
            compileBridgeTask.staticLibraryFile.get().asFile,
        )
        assertEquals(
            project.layout.buildDirectory.file("rustInterop/linuxX64/main/cargo/native-static-libs.txt").get().asFile,
            compileBridgeTask.nativeStaticLibrariesFile.get().asFile,
        )
        val expectedDefinition = project.layout.buildDirectory
            .file("rustInterop/linuxX64/main/cinterop/rustInterop.def").get().asFile
        assertEquals(expectedDefinition, defTask.definitionFile.get().asFile)
        assertEquals(expectedDefinition, cInteropTask.definitionFile.getFile())

        val generatedCInterop = compilation.cinterops.getByName("rustInterop") as DefaultCInteropSettings
        assertTrue(generatedCInterop.isGeneratedCinterop)
        assertEquals(expectedDefinition, generatedCInterop.definitionFile.get().asFile)

        val facadeRoot = project.layout.buildDirectory
            .dir("rustInterop/linuxX64/main/bridge/kotlin").get().asFile
        assertTrue(facadeRoot in compilation.defaultSourceSet.kotlin.srcDirs)
        val facade = facadeRoot.resolve("rust/regex/generated.kt").apply {
            parentFile.mkdirs()
            writeText("package rust.regex\n")
        }
        assertTrue(facade in compilation.compileTaskProvider.get().sources.files)
        val nativeCompileTask = compilation.compileTaskProvider.get()
        assertTrue(cInteropTask in nativeCompileTask.taskDependencies.getDependencies(nativeCompileTask))
    }

    @Test
    fun `wires every local crate into aggregate lock and compile tasks`() {
        val compilation = kotlin.linuxX64().compilations.getByName("main")
        val expectedPaths = linkedMapOf<String, String>()
        listOf("fixtureOne", "fixtureTwo").forEach { crateName ->
            val definition = project.file("src/nativeInterop/rust/$crateName.rustinterop.toml").apply {
                parentFile.mkdirs()
                writeText("# Configuration-only fixture\n")
            }
            val localCrate = project.file("rust-crates/$crateName")
            localCrate.resolve("Cargo.toml").apply {
                parentFile.mkdirs()
                writeText("[package]\nname = \"$crateName\"\nversion = \"1.0.0\"\n")
            }
            compilation.rustInterops.create(crateName) {
                it.crate(crateName, "1.0.0")
                it.packageName.set("rust.$crateName")
                it.definitionFile.set(definition)
                it.localCrateDirectory.set(localCrate)
            }
            expectedPaths[crateName] = localCrate.absolutePath
        }

        project.evaluate()

        val lockTask = project.tasks.getByName(LOCK_TASK_NAME) as ResolveRustInteropCargoLock
        val compileTask = project.tasks.getByName(COMPILE_BRIDGE_TASK_NAME) as CompileRustInteropBridge
        assertEquals(expectedPaths, lockTask.localCratePaths.get())
        assertEquals(expectedPaths, compileTask.localCratePaths.get())
        expectedPaths.values.forEach { cratePath ->
            assertTrue(File(cratePath).resolve("Cargo.toml") in lockTask.localCrateFiles.files)
            assertTrue(File(cratePath).resolve("Cargo.toml") in compileTask.localCrateFiles.files)
        }
    }

    @Test
    fun `does not register Rust native pipeline for LLVM-only compilation`() {
        val compilation = kotlin.linuxX64().compilations.getByName("main")

        project.evaluate()

        assertFalse(ARTIFACT_TASK_NAME in project.tasks.names)
        assertFalse(LOCK_TASK_NAME in project.tasks.names)
        assertFalse(COMPILE_BRIDGE_TASK_NAME in project.tasks.names)
        assertFalse(DEF_TASK_NAME in project.tasks.names)
        assertFalse(CINTEROP_TASK_NAME in project.tasks.names)
        assertFalse("rustInterop" in compilation.cinterops.names)
        assertFalse(
            compilation.defaultSourceSet.kotlin.srcDirs.any {
                it.invariantSeparatorsPath.endsWith("/rustInterop/linuxX64/main/bridge/kotlin")
            }
        )
    }

    @Test
    fun `Cargo pipeline uses locked release Clippy and emits stable linker inputs`() {
        assumeFalse(HostManager.hostIsMingw, "The fake Cargo executable is a POSIX script")
        val cargoLog = project.file("fake-cargo.log")
        val cargo = fakeCargo(cargoLog)
        val bridgeRoot = project.layout.buildDirectory.dir("rustInterop/linuxX64/main/bridge").get().asFile
        val cargoRoot = project.layout.buildDirectory.dir("rustInterop/linuxX64/main/cargo").get().asFile
        val manifest = bridgeRoot.resolve("Cargo.toml").apply {
            parentFile.mkdirs()
            writeText(
                """
                    [package]
                    name = "kotlin_rust_interop"
                    version = "0.0.0"

                    [lib]
                    crate-type = ["staticlib"]
                """.trimIndent() + "\n"
            )
        }
        val rustSource = bridgeRoot.resolve("src/lib.rs").apply {
            parentFile.mkdirs()
            writeText("pub extern \"C\" fn placeholder() {}\n")
        }
        val localCrate = project.file("rust-crates/fixture").apply {
            resolve("Cargo.toml").apply {
                parentFile.mkdirs()
                writeText("[package]\nname = \"fixture\"\nversion = \"1.0.0\"\n")
            }
            resolve("src/lib.rs").apply {
                parentFile.mkdirs()
                writeText("pub fn answer() -> i32 { 42 }\n")
            }
            resolve("target/debug/stale").apply {
                parentFile.mkdirs()
                writeText("stale")
            }
        }
        val localCrateFiles = project.fileTree(localCrate).matching {
            it.exclude(".git/**", "target/**")
        }
        val lockFile = cargoRoot.resolve("Cargo.lock")
        val resolveTask = project.tasks.register("resolveTestRustInteropCargoLock", ResolveRustInteropCargoLock::class.java) {
            it.cargoExecutable.set(cargo.absolutePath)
            it.cargoManifest.set(manifest)
            it.cargoWorkspaceDirectory.set(project.layout.buildDirectory.dir("rustInterop/test/lock-workspace"))
            it.cargoLockFile.set(lockFile)
            it.localCratePaths.put("fixture", localCrate.absolutePath)
            it.localCrateFiles.from(localCrateFiles)
        }.get()
        resolveTask.resolve()
        val stagedLockCrate = resolveTask.cargoWorkspaceDirectory.dir("local-crates/fixture").get().asFile
        assertTrue(stagedLockCrate.resolve("Cargo.toml").isFile)
        assertTrue(stagedLockCrate.resolve("src/lib.rs").isFile)
        assertFalse(stagedLockCrate.resolve("target").exists())

        val archiveOutput = cargoRoot.resolve("lib/libkotlin_rust_interop.a")
        val nativeLibrariesOutput = cargoRoot.resolve("native-static-libs.txt")
        val compileTask = project.tasks.register("compileTestRustInteropBridge", CompileRustInteropBridge::class.java) {
            it.cargoExecutable.set(cargo.absolutePath)
            it.targetName.set("linuxX64")
            it.cargoTarget.set(LINUX_X64_CARGO_TARGET)
            it.cargoManifest.set(manifest)
            it.rustSource.set(rustSource)
            it.cargoLockFile.set(lockFile)
            it.cargoWorkspaceDirectory.set(project.layout.buildDirectory.dir("rustInterop/test/compile-workspace"))
            it.cargoTargetDirectory.set(project.layout.buildDirectory.dir("rustInterop/test/cargo-target"))
            it.staticLibraryFile.set(archiveOutput)
            it.nativeStaticLibrariesFile.set(nativeLibrariesOutput)
            it.localCratePaths.put("fixture", localCrate.absolutePath)
            it.localCrateFiles.from(localCrateFiles)
        }.get()
        compileTask.compile()
        val stagedCompileCrate = compileTask.cargoWorkspaceDirectory.dir("local-crates/fixture").get().asFile
        assertTrue(stagedCompileCrate.resolve("Cargo.toml").isFile)
        assertTrue(stagedCompileCrate.resolve("src/lib.rs").isFile)
        assertFalse(stagedCompileCrate.resolve("target").exists())

        assertEquals("# fake Cargo.lock\n", lockFile.readText())
        assertContentEquals("fake staticlib\n".toByteArray(), archiveOutput.readBytes())
        assertEquals("-ldl -lpthread -lm\n", nativeLibrariesOutput.readText())
        val commands = cargoLog.readLines()
        assertEquals(3, commands.size)
        assertTrue(commands[0].startsWith("generate-lockfile --manifest-path "))
        assertTrue(commands[0].endsWith(" --color never"))
        assertTrue(
            commands[1].matches(
                Regex(
                    "clippy --manifest-path .+ --locked --target $LINUX_X64_CARGO_TARGET " +
                            "--release --all-targets -- -D warnings"
                )
            ),
            commands[1],
        )
        assertTrue(
            commands[2].matches(
                Regex("rustc --manifest-path .+ --locked --target $LINUX_X64_CARGO_TARGET --release --lib -- --print native-static-libs")
            ),
            commands[2],
        )
    }

    @Test
    fun `reports unsupported Kotlin target before invoking Cargo`() {
        val task = project.tasks.register("compileUnsupportedRustInteropBridge", CompileRustInteropBridge::class.java) {
            it.targetName.set("wasmWasi")
            it.cargoTarget.set("wasm32-wasip1")
        }.get()

        val failure = assertFailsWith<GradleException> { task.compile() }

        assertEquals(
            "Rust interop does not support Kotlin/Native target 'wasmWasi'. Supported targets: " +
                    "androidNativeArm32, androidNativeArm64, androidNativeX64, androidNativeX86, iosArm64, " +
                    "iosSimulatorArm64, iosX64, linuxArm32Hfp, linuxArm64, linuxX64, macosArm64, macosX64, " +
                    "mingwX64, tvosArm64, tvosSimulatorArm64, tvosX64, watchosArm32, watchosArm64, " +
                    "watchosDeviceArm64, watchosSimulatorArm64, watchosX64",
            failure.message,
        )
    }

    @Test
    fun `reports Cargo tool failure with captured diagnostics`() {
        assumeFalse(HostManager.hostIsMingw, "The fake Cargo executable is a POSIX script")
        val manifest = project.file("Cargo.toml").apply { writeText("[workspace]\n") }
        val cargo = fakeFailingCargo()
        val task = project.tasks.register("resolveFailingRustInteropCargoLock", ResolveRustInteropCargoLock::class.java) {
            it.cargoExecutable.set(cargo.absolutePath)
            it.cargoManifest.set(manifest)
            it.cargoWorkspaceDirectory.set(project.layout.buildDirectory.dir("rustInterop/test/failing-workspace"))
            it.cargoLockFile.set(project.layout.buildDirectory.file("rustInterop/test/failing-Cargo.lock"))
        }.get()

        val failure = assertFailsWith<GradleException> { task.resolve() }

        assertEquals(
            "Cargo failed to resolve the Rust interop lock file (exit code 41):\nfake Cargo unavailable",
            failure.message,
        )
    }

    @Test
    fun `generates stable cinterop definition from Cargo outputs`() {
        val bridgeRoot = project.layout.buildDirectory.dir("rustInterop/linuxX64/main/bridge").get().asFile
        val cargoRoot = project.layout.buildDirectory.dir("rustInterop/linuxX64/main/cargo").get().asFile
        val header = bridgeRoot.resolve("include/kotlin_rust_interop.h").apply {
            parentFile.mkdirs()
            writeText("/* generated */\n")
        }
        val packageMetadata = bridgeRoot.resolve("metadata/cinterop-package.txt").apply {
            parentFile.mkdirs()
            writeText("rust.regex.internal\n")
        }
        val archive = cargoRoot.resolve("lib/libkotlin_rust_interop.a").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(0x61, 0x72))
        }
        val nativeStaticLibraries = cargoRoot.resolve("native-static-libs.txt").apply {
            parentFile.mkdirs()
            writeText("-ldl -lpthread -lm\n")
        }
        val output = project.layout.buildDirectory.file("rustInterop/linuxX64/main/cinterop/rustInterop.def")
        val task = project.tasks.register("generateTestRustInteropDef", GenerateRustInteropCInteropDef::class.java) {
            it.headerFile.set(header)
            it.cInteropPackageFile.set(packageMetadata)
            it.staticLibraryFile.set(archive)
            it.nativeStaticLibrariesFile.set(nativeStaticLibraries)
            it.definitionFile.set(output)
        }.get()

        task.generate()

        val archivePath = archive.parentFile.invariantSeparatorsPath
        assertEquals(
            "headers = \"${header.invariantSeparatorsPath}\"\n" +
                    "package = rust.regex.internal\n" +
                    "staticLibraries = \"libkotlin_rust_interop.a\"\n" +
                    "libraryPaths = \"$archivePath\"\n" +
                    "linkerOpts = -ldl -lpthread -lm\n",
            output.get().asFile.readText(),
        )
    }

    private fun fakeCargo(log: File): File {
        val script = project.file("fake-cargo")
        val quotedLog = log.absolutePath.replace("'", "'\"'\"'")
        script.writeText(
            """
                #!/bin/sh
                printf '%s\n' "${'$'}*" >> '$quotedLog'
                case "${'$'}1" in
                  generate-lockfile)
                    printf '# fake Cargo.lock\n' > Cargo.lock
                    ;;
                  rustc)
                    archive="${'$'}CARGO_TARGET_DIR/$LINUX_X64_CARGO_TARGET/release/libkotlin_rust_interop.a"
                    mkdir -p "${'$'}(dirname "${'$'}archive")"
                    printf 'fake staticlib\n' > "${'$'}archive"
                    printf 'note: native-static-libs: -ldl -lpthread -lm\n'
                    ;;
                esac
            """.trimIndent() + "\n"
        )
        check(script.setExecutable(true)) { "Cannot make fake Cargo executable" }
        return script
    }

    private fun fakeFailingCargo(): File = project.file("fake-failing-cargo").apply {
        writeText(
            """
                #!/bin/sh
                echo 'fake Cargo unavailable'
                exit 41
            """.trimIndent() + "\n"
        )
        check(setExecutable(true)) { "Cannot make fake Cargo executable" }
    }

    private fun addConfiguredRustInterop() {
        val definition = project.file("src/nativeInterop/rust/regex.rustinterop.toml").apply {
            parentFile.mkdirs()
            writeText("# Configuration-only fixture; generation is tested separately.\n")
        }
        kotlin.linuxX64().compilations.getByName("main").rustInterops.create("regex") {
            it.crate("regex", "1.11.1")
            it.packageName.set("rust.regex")
            it.definitionFile.set(definition)
            it.features.add("unicode")
        }
    }

    private companion object {
        const val LINUX_X64_CARGO_TARGET = "x86_64-unknown-linux-gnu"
        const val ARTIFACT_TASK_NAME = "generateLinuxX64MainRustInteropBridgeArtifacts"
        const val LOCK_TASK_NAME = "resolveLinuxX64MainRustInteropCargoLock"
        const val COMPILE_BRIDGE_TASK_NAME = "compileLinuxX64MainRustInteropBridge"
        const val DEF_TASK_NAME = "generateLinuxX64MainRustInteropCInteropDef"
        const val CINTEROP_TASK_NAME = "cinteropRustInteropLinuxX64"
    }
}
