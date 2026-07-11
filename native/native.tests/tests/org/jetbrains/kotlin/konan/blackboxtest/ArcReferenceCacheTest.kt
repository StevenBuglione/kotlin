/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import com.intellij.testFramework.TestDataPath
import org.jetbrains.kotlin.konan.blackboxtest.support.ClassLevelProperty
import org.jetbrains.kotlin.konan.blackboxtest.support.EnforcedHostTarget
import org.jetbrains.kotlin.konan.blackboxtest.support.EnforcedProperty
import org.jetbrains.kotlin.konan.blackboxtest.support.TestCase
import org.jetbrains.kotlin.konan.blackboxtest.support.TestCompilerArgs
import org.jetbrains.kotlin.konan.blackboxtest.support.TestKind
import org.jetbrains.kotlin.konan.blackboxtest.support.compilation.ExistingDependency
import org.jetbrains.kotlin.konan.blackboxtest.support.compilation.TestCompilationDependencyType.LibraryStaticCache
import org.jetbrains.kotlin.konan.blackboxtest.support.compilation.TestCompilationResult.Companion.assertSuccess
import org.jetbrains.kotlin.konan.blackboxtest.support.runner.TestExecutable
import org.jetbrains.kotlin.konan.blackboxtest.support.settings.CacheMode
import org.jetbrains.kotlin.konan.blackboxtest.support.settings.KotlinNativeTargets
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.test.TestMetadata
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

@Tag("arc")
@Tag("caches")
@EnforcedHostTarget
@EnforcedProperty(ClassLevelProperty.MEMORY_MODEL, "ARC")
@TestMetadata(ArcReferenceCacheTest.TEST_DATA_PATH)
@TestDataPath("\$PROJECT_ROOT")
class ArcReferenceCacheTest : AbstractNativeSimpleTest() {
    @Test
    fun twoModuleStaticCachePreservesArcReferenceStorage() {
        assumeTrue(testRunSettings.get<KotlinNativeTargets>().hostTarget.family == Family.LINUX)
        assumeTrue(testRunSettings.get<CacheMode>() is CacheMode.WithStaticCache)

        val testData = File(TEST_DATA_PATH)
        val library = compileToLibrary(testData.resolve("lib"), buildDir.resolve("library"))
        val cache = compileToStaticCache(library, buildDir.resolve("library-cache").apply { mkdirs() })
            .assertSuccess()
            .resultingArtifact

        val mainFile = testData.resolve("main/main.kt")
        val testCase = generateTestCaseWithSingleFile(
            mainFile,
            TestCompilerArgs.EMPTY,
            TestKind.STANDALONE_NO_TR,
            TestCase.NoTestRunnerExtras("main"),
        )
        val executable = compileToExecutable(
            testCase,
            true,
            library.asLibraryDependency(),
            ExistingDependency(cache, LibraryStaticCache),
        ).assertSuccess()

        runExecutableAndVerify(testCase, TestExecutable.fromCompilationResult(testCase, executable))
    }

    companion object {
        const val TEST_DATA_PATH = "native/native.tests/testData/caches/arcReferences"
    }
}
