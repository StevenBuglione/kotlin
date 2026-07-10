/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.preprocessors

import com.intellij.util.lang.JavaVersion
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.TestJdkKind
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.configuration.JvmEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.utils.ReplacingSourceTransformer

/**
 * Replaces the magic identifier `JDK_MAJOR_VERSION` in the test source file with an integer with the actual major SDK version that the test
 * is being run against.
 *
 * If this is a non-JVM test, the magic identifier is replaced with `999`.
 *
 * This is useful when we want to test some behavior that _slightly_ differs between JDK versions, so a separate test file would be
 * an overkill.
 * Instead, this magic identifier allows to write conditions like
 * ```
 * if (JDK_MAJOR_VERSION >= 20) {
 *   // ...
 * }
 * ```
 * while still making the test compilable on any Kotlin target, be it JVM, JS, Wasm or Native.
 */
class JdkVersionSourcePreprocessor(testServices: TestServices) : BackendDependentSourceFilePreprocessor(testServices) {
    companion object {
        private const val MAGIC_IDENTIFIER = "JDK_MAJOR_VERSION"
    }

    override fun selectTransformer(targetBackend: TargetBackend): ReplacingSourceTransformer {
        if (!targetBackend.isTransitivelyCompatibleWith(TargetBackend.JVM)) return ReplacingSourceTransformer(MAGIC_IDENTIFIER, "999")
        val jdkKind = JvmEnvironmentConfigurator.extractJdkKind(testServices.moduleStructure.allDirectives)
        val version = when (jdkKind) {
            TestJdkKind.MOCK_JDK -> JavaVersion.current().feature
            TestJdkKind.MODIFIED_MOCK_JDK -> JavaVersion.current().feature
            TestJdkKind.FULL_JDK_8 -> 8
            TestJdkKind.FULL_JDK_11 -> 11
            TestJdkKind.FULL_JDK_17 -> 17
            TestJdkKind.FULL_JDK_21 -> 21
            TestJdkKind.FULL_JDK -> JavaVersion.current().feature
        }
        return ReplacingSourceTransformer(MAGIC_IDENTIFIER, "$version")
    }
}
