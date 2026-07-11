/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl

import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.plugin.KotlinNativeTargetConfigurator
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCompilation
import org.jetbrains.kotlin.gradle.targets.native.tasks.GenerateRustInteropBridgePlan
import org.jetbrains.kotlin.gradle.utils.asValidTaskName
import org.jetbrains.kotlin.gradle.utils.lowerCamelCaseName

internal val KotlinCreateNativeRustInteropBridgePlanTasksSideEffect =
    KotlinCompilationSideEffect<KotlinNativeCompilation> { compilation ->
        val project = compilation.project

        compilation.rustInterops.all { interop ->
            val bridgePlanGenerationTaskName = compilation.rustInteropBridgePlanTaskName(interop.name)
            val bridgePlanFile = project.layout.buildDirectory.file(
                "rustInterop/${compilation.target.name}/${compilation.name}/${interop.name}/bridge-plan.json"
            )

            val bridgePlanTask = project.tasks.register(
                bridgePlanGenerationTaskName,
                GenerateRustInteropBridgePlan::class.java,
            ) { task ->
                task.group = KotlinNativeTargetConfigurator.INTEROP_GROUP
                task.description = "Generates Rust interop bridge plan '${interop.name}' " +
                        "for compilation '${compilation.compilationName}' of target '${compilation.target.name}'."

                task.interopName.set(interop.name)
                task.crateName.set(interop.crateName)
                task.crateVersion.set(interop.crateVersion)
                task.packageName.set(interop.packageName)
                task.features.set(interop.features)
                task.definitionFile.set(interop.definitionFile)
                task.bridgePlanFile.set(bridgePlanFile)
            }

            compilation.compileTaskProvider.configure { task ->
                task.dependsOn(bridgePlanTask)
                task.inputs.files(
                    interop.localCrateDirectory.map { directory ->
                        project.fileTree(directory).matching {
                            it.exclude(".git/**", "target/**")
                        }.files
                    }.orElse(emptySet())
                )
                    .withPropertyName("rustInteropLocalCrateSources.${interop.name}")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                task.outputs.cacheIf("Local Rust crate paths are machine-specific") {
                    !interop.localCrateDirectory.isPresent
                }
                task.compilerOptions.freeCompilerArgs.add(
                    bridgePlanFile.map { bridgePlan ->
                        "-Xrust-interop-bridge-plan=${bridgePlan.asFile.absolutePath}"
                    }
                )
                task.compilerOptions.freeCompilerArgs.addAll(
                    interop.crateName.zip(interop.localCrateDirectory) { crateName, directory ->
                        listOf("-Xrust-interop-crate-path=$crateName=${directory.asFile.absolutePath}")
                    }.orElse(emptyList())
                )
            }
        }
    }

internal fun KotlinNativeCompilation.rustInteropBridgePlanTaskName(interopName: String): String = lowerCamelCaseName(
    "generate",
    target.name,
    name,
    interopName,
    "rustInteropBridgePlan",
).asValidTaskName()
