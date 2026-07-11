/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.test.blackbox

import com.intellij.testFramework.TestDataPath
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.test.blackbox.support.*
import org.jetbrains.kotlin.konan.test.blackbox.support.compilation.ExecutableCompilation
import org.jetbrains.kotlin.konan.test.blackbox.support.compilation.TestCompilationArtifact
import org.jetbrains.kotlin.konan.test.blackbox.support.compilation.TestCompilationResult.Companion.assertSuccess
import org.jetbrains.kotlin.konan.test.blackbox.support.runner.TestExecutable
import org.jetbrains.kotlin.konan.test.blackbox.support.runner.TestRunCheck
import org.jetbrains.kotlin.konan.test.blackbox.support.runner.TestRunChecks
import org.jetbrains.kotlin.konan.test.blackbox.support.settings.*
import org.jetbrains.kotlin.native.executors.runProcess
import org.jetbrains.kotlin.test.TestMetadata
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * Opt-in seam proof for importing rustc LLVM bitcode into the existing Kotlin/Native pipeline.
 *
 * This deliberately uses the existing `-native-library` compiler option. It proves that rustc
 * output can participate in Kotlin/Native linking and call the initialized Kotlin runtime in both
 * directions; it does not select the experimental Rust source backend.
 */
@TestMetadata("native/native.tests/testData/rustBitcodeLinkage")
@TestDataPath("\$PROJECT_ROOT")
class RustBitcodeLinkageTest : AbstractNativeSimpleTest() {
    @Test
    fun testRustBitcodeCallsKotlinAndReturnsToKotlinMain() {
        val rustc = System.getenv(RUSTC_ENVIRONMENT_VARIABLE)
        Assumptions.assumeTrue(!rustc.isNullOrBlank()) {
            "Set $RUSTC_ENVIRONMENT_VARIABLE to opt in to the rustc bitcode linkage test"
        }
        Assumptions.assumeTrue(testRunSettings.get<KotlinNativeTargets>().testTarget == KonanTarget.LINUX_X64) {
            "The initial rustc bitcode linkage fixture supports linuxX64 only"
        }

        val testDataDir = ForTestCompileRuntime.transformTestDataPath("native/native.tests/testData/rustBitcodeLinkage")
        val rustBitcode = buildDir.resolve("rustBitcodeLinkage.bc")
        runProcess(
            rustc!!,
            testDataDir.resolve("bridge.rs").absolutePath,
            "--crate-name", "kotlin_native_rust_linkage",
            "--crate-type", "lib",
            "--emit", "llvm-bc",
            "--target", "x86_64-unknown-linux-gnu",
            "-C", "panic=abort",
            "-C", "opt-level=1",
            "-o", rustBitcode.absolutePath,
        )

        val kotlinKlib = compileLibrary(
            settings = testRunSettings,
            source = testDataDir.resolve("main.kt"),
            freeCompilerArgs = listOf(
                "-opt-in=kotlin.native.internal.InternalForKotlinNative",
                "-native-library", rustBitcode.absolutePath,
            ),
        ).assertSuccess().resultingArtifact

        val testCase = TestCase(
            id = TestCaseId.Named("rustBitcodeLinkage"),
            kind = TestKind.STANDALONE_NO_TR,
            modules = emptySet(),
            freeCompilerArgs = TestCompilerArgs.EMPTY,
            nominalPackageName = PackageName("rustBitcodeLinkage"),
            extras = TestCase.NoTestRunnerExtras(entryPoint = "main"),
            checks = TestRunChecks.Default(testRunSettings.get<Timeouts>().executionTimeout).copy(
                outputDataFile = TestRunCheck.OutputDataFile(file = testDataDir.resolve("main.out")),
            ),
        ).apply {
            initialize(null, null)
        }
        val compilation = ExecutableCompilation(
            settings = testRunSettings,
            freeCompilerArgs = testCase.freeCompilerArgs,
            sourceModules = testCase.modules,
            extras = testCase.extras,
            dependencies = setOf(kotlinKlib.asLibraryDependency()),
            expectedArtifact = TestCompilationArtifact.Executable(
                buildDir.resolve("rustBitcodeLinkage.${testRunSettings.get<KotlinNativeTargets>().testTarget.family.exeSuffix}")
            ),
        ).result.assertSuccess()
        val executable = TestExecutable(
            compilation.resultingArtifact,
            compilation.loggedData,
            listOf(TestName("rustBitcodeLinkage")),
        )

        runExecutableAndVerify(testCase, executable)
    }

    private companion object {
        const val RUSTC_ENVIRONMENT_VARIABLE = "KOTLIN_NATIVE_RUSTC"
    }
}
