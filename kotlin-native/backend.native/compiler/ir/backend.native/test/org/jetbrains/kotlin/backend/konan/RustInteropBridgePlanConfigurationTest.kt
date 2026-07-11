/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.cli.common.arguments.K2NativeCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.config.CompilerConfiguration
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RustInteropBridgePlanConfigurationTest {
    @Test
    fun parsesRepeatedBridgePlanArgumentsWithoutSplittingPaths() {
        val arguments = K2NativeCompilerArguments()

        parseCommandLineArguments(
            listOf(
                "-Xrust-interop-bridge-plan=plans/first.json",
                "-Xrust-interop-bridge-plan=plans/second,with-comma.json",
            ),
            arguments,
        )

        assertContentEquals(
            arrayOf("plans/first.json", "plans/second,with-comma.json"),
            arguments.rustInteropBridgePlans,
        )
    }

    @Test
    fun storesNormalizedAbsolutePathsWithoutLoadingPlans() {
        val configuration = CompilerConfiguration.create()
        val missingPlan = "missing/../plans/not-created.json"

        configuration.configureRustInteropBridgePlanPaths(arrayOf(missingPlan, missingPlan))

        val expected = File(missingPlan).absoluteFile.normalize().path
        assertEquals(listOf(expected, expected), configuration.rustInteropBridgePlanPaths)
    }

    @Test
    fun defaultsToNoBridgePlans() {
        val configuration = CompilerConfiguration.create()
        assertEquals(emptyList(), configuration.rustInteropBridgePlanPaths)
        assertEquals(emptyMap(), configuration.rustInteropCratePaths)
    }

    @Test
    fun parsesAndStoresLocalCratePathOverrides() {
        val arguments = K2NativeCompilerArguments()
        parseCommandLineArguments(
            listOf(
                "-Xrust-interop-crate-path=fixture=crates/fixture",
                "-Xrust-interop-crate-path=other=crates/path=with-equals",
            ),
            arguments,
        )
        val configuration = CompilerConfiguration.create()
        configuration.configureRustInteropCratePaths(arguments.rustInteropCratePaths)

        assertEquals(
            linkedMapOf(
                "fixture" to File("crates/fixture").absoluteFile.normalize().path,
                "other" to File("crates/path=with-equals").absoluteFile.normalize().path,
            ),
            configuration.rustInteropCratePaths,
        )
    }

    @Test
    fun rejectsMalformedAndConflictingLocalCratePaths() {
        assertFailsWith<IllegalArgumentException> {
            CompilerConfiguration.create().configureRustInteropCratePaths(arrayOf("missing-separator"))
        }
        assertFailsWith<IllegalArgumentException> {
            CompilerConfiguration.create().configureRustInteropCratePaths(
                arrayOf("fixture=first", "fixture=second")
            )
        }
    }
}
