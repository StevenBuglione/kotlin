/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl

import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.plugin.KotlinNativeTargetConfigurator
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCompilation
import org.jetbrains.kotlin.gradle.targets.native.tasks.CompileRustInteropBridge
import org.jetbrains.kotlin.gradle.targets.native.tasks.GenerateRustInteropBridgeArtifacts
import org.jetbrains.kotlin.gradle.targets.native.tasks.GenerateRustInteropCInteropDef
import org.jetbrains.kotlin.gradle.targets.native.tasks.ResolveRustInteropCargoLock
import org.jetbrains.kotlin.gradle.targets.native.tasks.RustInteropCargoTargets
import org.jetbrains.kotlin.gradle.utils.asValidTaskName
import org.jetbrains.kotlin.gradle.utils.lowerCamelCaseName
import org.jetbrains.kotlin.konan.target.presetName

internal val KotlinCreateNativeRustInteropPipelineSideEffect =
    KotlinCompilationSideEffect<KotlinNativeCompilation> { compilation ->
        val project = compilation.project
        var pipelineCreated = false

        compilation.rustInterops.all {
            if (pipelineCreated) return@all
            pipelineCreated = true

            val root = "rustInterop/${compilation.target.name}/${compilation.name}"
            val artifactTask = project.tasks.named(
                compilation.rustInteropBridgeArtifactTaskName(),
                GenerateRustInteropBridgeArtifacts::class.java,
            )
            val resolveLockTask = project.tasks.register(
                compilation.rustInteropCargoLockTaskName(),
                ResolveRustInteropCargoLock::class.java,
            ) { task ->
                task.group = KotlinNativeTargetConfigurator.INTEROP_GROUP
                task.description = "Resolves the aggregate Rust interop Cargo lock file " +
                        "for compilation '${compilation.compilationName}' of target '${compilation.target.name}'."
                task.cargoExecutable.convention("cargo")
                task.cargoManifest.set(artifactTask.flatMap { it.outputDirectory.file("Cargo.toml") })
                task.cargoWorkspaceDirectory.set(project.layout.buildDirectory.dir("$root/cargo/lock-workspace"))
                task.cargoLockFile.set(project.layout.buildDirectory.file("$root/cargo/Cargo.lock"))
            }
            val compileBridgeTask = project.tasks.register(
                compilation.rustInteropCompileBridgeTaskName(),
                CompileRustInteropBridge::class.java,
            ) { task ->
                task.group = KotlinNativeTargetConfigurator.INTEROP_GROUP
                task.description = "Compiles the aggregate Rust interop static library " +
                        "for compilation '${compilation.compilationName}' of target '${compilation.target.name}'."
                task.cargoExecutable.convention("cargo")
                task.targetName.set(compilation.konanTarget.presetName)
                task.cargoTarget.set(project.provider {
                    RustInteropCargoTargets.forKotlinNativeTarget(compilation.konanTarget.presetName)
                })
                task.cargoManifest.set(artifactTask.flatMap { it.outputDirectory.file("Cargo.toml") })
                task.rustSource.set(artifactTask.flatMap { it.outputDirectory.file("src/lib.rs") })
                task.cargoLockFile.set(resolveLockTask.flatMap { it.cargoLockFile })
                task.cargoWorkspaceDirectory.set(project.layout.buildDirectory.dir("$root/cargo/compile-workspace"))
                task.cargoTargetDirectory.set(project.layout.buildDirectory.dir("$root/cargo/target"))
                task.staticLibraryFile.set(
                    project.layout.buildDirectory.file("$root/cargo/lib/libkotlin_rust_interop.a")
                )
                task.nativeStaticLibrariesFile.set(
                    project.layout.buildDirectory.file("$root/cargo/native-static-libs.txt")
                )
            }
            val generateDefTask = project.tasks.register(
                compilation.rustInteropCInteropDefTaskName(),
                GenerateRustInteropCInteropDef::class.java,
            ) { task ->
                task.group = KotlinNativeTargetConfigurator.INTEROP_GROUP
                task.description = "Generates the aggregate Rust interop cinterop definition " +
                        "for compilation '${compilation.compilationName}' of target '${compilation.target.name}'."
                task.headerFile.set(artifactTask.flatMap { it.outputDirectory.file("include/kotlin_rust_interop.h") })
                task.cInteropPackageFile.set(
                    artifactTask.flatMap { it.outputDirectory.file("metadata/cinterop-package.txt") }
                )
                task.staticLibraryFile.set(compileBridgeTask.flatMap { it.staticLibraryFile })
                task.nativeStaticLibrariesFile.set(compileBridgeTask.flatMap { it.nativeStaticLibrariesFile })
                task.definitionFile.set(project.layout.buildDirectory.file("$root/cinterop/rustInterop.def"))
            }

            val existingCInterop = compilation.cinterops.findByName(GENERATED_CINTEROP_NAME)
            if (existingCInterop != null && !existingCInterop.isGeneratedCinterop) {
                throw GradleException(
                    "C interop name '$GENERATED_CINTEROP_NAME' is reserved by Rust interop " +
                            "for compilation '${compilation.compilationName}' of target '${compilation.target.name}'."
                )
            }
            val generatedCInterop = existingCInterop ?: compilation.cinterops.create(GENERATED_CINTEROP_NAME)
            generatedCInterop.isGeneratedCinterop = true
            generatedCInterop.definitionFile.set(generateDefTask.flatMap { it.definitionFile })

            compilation.defaultSourceSet.kotlin.srcDir(
                artifactTask.flatMap { it.outputDirectory.dir("kotlin") }
            )
        }
    }

internal fun KotlinNativeCompilation.rustInteropBridgeArtifactTaskName(): String = lowerCamelCaseName(
    "generate",
    target.name,
    name,
    "rustInteropBridgeArtifacts",
).asValidTaskName()

internal fun KotlinNativeCompilation.rustInteropCargoLockTaskName(): String = lowerCamelCaseName(
    "resolve",
    target.name,
    name,
    "rustInteropCargoLock",
).asValidTaskName()

internal fun KotlinNativeCompilation.rustInteropCompileBridgeTaskName(): String = lowerCamelCaseName(
    "compile",
    target.name,
    name,
    "rustInteropBridge",
).asValidTaskName()

internal fun KotlinNativeCompilation.rustInteropCInteropDefTaskName(): String = lowerCamelCaseName(
    "generate",
    target.name,
    name,
    "rustInteropCInteropDef",
).asValidTaskName()

private const val GENERATED_CINTEROP_NAME = "rustInterop"
