/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl

import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.plugin.KotlinNativeTargetConfigurator
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCompilation
import org.jetbrains.kotlin.gradle.targets.native.tasks.GenerateRustInteropBridgeArtifacts
import org.jetbrains.kotlin.gradle.targets.native.tasks.GenerateRustInteropBridgePlan
import org.jetbrains.kotlin.konan.target.presetName

internal val KotlinCreateNativeRustInteropBridgeArtifactTaskSideEffect =
    KotlinCompilationSideEffect<KotlinNativeCompilation> { compilation ->
        val project = compilation.project

        var artifactTask: TaskProvider<GenerateRustInteropBridgeArtifacts>? = null
        compilation.rustInterops.all { interop ->
            val aggregateTask = artifactTask ?: project.tasks.register(
                compilation.rustInteropBridgeArtifactTaskName(),
                GenerateRustInteropBridgeArtifacts::class.java,
            ) { task ->
                task.group = KotlinNativeTargetConfigurator.INTEROP_GROUP
                task.description = "Generates aggregate Rust interop bridge artifacts " +
                        "for compilation '${compilation.compilationName}' of target '${compilation.target.name}'."
                task.targetName.set(compilation.konanTarget.presetName)
                task.outputDirectory.set(
                    project.layout.buildDirectory.dir(
                        "rustInterop/${compilation.target.name}/${compilation.name}/bridge"
                    )
                )
            }.also { registeredTask ->
                artifactTask = registeredTask
                compilation.compileTaskProvider.configure { task ->
                    task.dependsOn(registeredTask)
                }
            }

            val planTask = project.tasks.named(
                compilation.rustInteropBridgePlanTaskName(interop.name),
                GenerateRustInteropBridgePlan::class.java,
            )
            aggregateTask.configure { task ->
                task.dependsOn(planTask)
                task.bridgePlanFiles.from(planTask.flatMap { it.bridgePlanFile })
                task.definitionFiles.from(interop.definitionFile)
            }
        }
    }
