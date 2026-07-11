/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl

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
                task.bridgePlanFile.set(
                    project.layout.buildDirectory.file(
                        "rustInterop/${compilation.target.name}/${compilation.name}/${interop.name}/bridge-plan.json"
                    )
                )
            }

            compilation.compileTaskProvider.configure { task ->
                task.dependsOn(bridgePlanTask)
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
