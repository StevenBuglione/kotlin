/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import org.jetbrains.kotlin.konan.blackboxtest.support.ClassLevelProperty
import org.jetbrains.kotlin.konan.blackboxtest.support.EnforcedHostTarget
import org.jetbrains.kotlin.konan.blackboxtest.support.EnforcedProperty
import org.jetbrains.kotlin.konan.blackboxtest.support.TestCase
import org.jetbrains.kotlin.konan.blackboxtest.support.TestCompilerArgs
import org.jetbrains.kotlin.konan.blackboxtest.support.TestKind
import org.jetbrains.kotlin.konan.blackboxtest.support.compilation.LibraryCompilation
import org.jetbrains.kotlin.konan.blackboxtest.support.compilation.TestCompilationResult
import org.jetbrains.kotlin.konan.blackboxtest.support.compilation.TestCompilationResult.Companion.assertSuccess
import org.jetbrains.kotlin.konan.blackboxtest.support.runner.TestExecutable
import org.jetbrains.kotlin.konan.blackboxtest.support.settings.KotlinNativeTargets
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertIs

private const val ARC_ONLY = "available only with the ARC memory model"
private const val ARC_REFERENCE_ONLY = "ARC reference annotations are applicable only to reference declarations"
private const val WEAK_SHAPE = "@ArcWeak is applicable only to mutable nullable reference declarations"
private const val UNOWNED_SHAPE = "@ArcUnowned is applicable only to non-null reference declarations"
private const val DEINIT_SHAPE =
    "@ArcDeinit is applicable only to a private final zero-argument non-suspend Unit member function"

private fun AbstractNativeSimpleTest.assumeLinuxHost() {
    assumeTrue(testRunSettings.get<KotlinNativeTargets>().hostTarget.family == Family.LINUX)
}

private fun AbstractNativeSimpleTest.writeSource(name: String, source: String): File {
    val sourceDir = buildDir.resolve(name).apply { mkdirs() }
    return sourceDir.resolve("$name.kt").apply { writeText(source.trimIndent()) }
}

private fun AbstractNativeSimpleTest.compileLibrary(name: String, source: String): TestCompilationResult<*> =
    generateTestCaseWithSingleModule(writeSource(name, source).parentFile).let { testCase ->
        LibraryCompilation(
            settings = testRunSettings,
            freeCompilerArgs = testCase.freeCompilerArgs,
            sourceModules = testCase.modules,
            dependencies = emptyList(),
            expectedArtifact = getLibraryArtifact(testCase, buildDir),
        ).result
    }

private fun AbstractNativeSimpleTest.assertCompiles(name: String, source: String) {
    compileLibrary(name, source).assertSuccess()
}

private fun AbstractNativeSimpleTest.assertFails(name: String, source: String, expectedMessage: String) {
    val failure = assertIs<TestCompilationResult.CompilationToolFailure>(compileLibrary(name, source))
    assertTrue(failure.loggedData.toolOutput.contains(expectedMessage)) {
        "Expected compiler output to contain '$expectedMessage', but was:\n${failure.loggedData.toolOutput}"
    }
}

private fun AbstractNativeSimpleTest.compileAndRun(name: String, source: String) {
    assumeLinuxHost()
    val sourceFile = writeSource(name, source)
    val testCase = generateTestCaseWithSingleFile(
        sourceFile,
        TestCompilerArgs.EMPTY,
        TestKind.STANDALONE_NO_TR,
        TestCase.NoTestRunnerExtras("main")
    )
    val success = compileToExecutable(testCase, tryPassSystemCacheDirectory = false).assertSuccess()
    runExecutableAndVerify(testCase, TestExecutable.fromCompilationResult(testCase, success))
}

private fun AbstractNativeSimpleTest.compileAndRunMemoryModelCheck() = compileAndRun(
    "memoryModelApi",
    """
    import kotlin.experimental.ExperimentalNativeApi
    import kotlin.native.MemoryModel
    import kotlin.native.Platform

    @OptIn(ExperimentalNativeApi::class)
    fun main() {
        check(MemoryModel.ARC.ordinal == 3)
        check(Platform.memoryModel == MemoryModel.ARC)
    }
    """
)

@Tag("arc")
@EnforcedHostTarget
@EnforcedProperty(ClassLevelProperty.MEMORY_MODEL, "DEFAULT")
class ArcDefaultMemoryModelTest : AbstractNativeSimpleTest() {
    @Test
    fun linuxUsesArcByDefault() = compileAndRunMemoryModelCheck()
}

@Tag("arc")
@EnforcedHostTarget
@EnforcedProperty(ClassLevelProperty.MEMORY_MODEL, "ARC")
class ArcExplicitMemoryModelTest : AbstractNativeSimpleTest() {
    @Test
    fun explicitArcExposesRuntimeApiAndStableOrdinal() = compileAndRunMemoryModelCheck()

    @Test
    fun sharedHeapLazyAndDisabledFreezingSemantics() {
        compileAndRun(
            "sharedHeapSemantics",
            """
            import kotlin.native.concurrent.ensureNeverFrozen
            import kotlin.native.concurrent.freeze
            import kotlin.native.concurrent.isFrozen

            private class Mutable(var value: Int)

            fun main() {
                var defaultInitializations = 0
                val defaultLazy = lazy { ++defaultInitializations }
                check(defaultLazy.value == 1)
                check(defaultLazy.value == 1)
                check(defaultInitializations == 1)

                for (mode in listOf(LazyThreadSafetyMode.SYNCHRONIZED, LazyThreadSafetyMode.PUBLICATION)) {
                    var initializations = 0
                    val value = lazy(mode) { ++initializations }
                    check(value.value == 1)
                    check(value.value == 1)
                    check(initializations == 1)
                }

                val mutable = Mutable(1)
                mutable.ensureNeverFrozen()
                check(!mutable.isFrozen)
                check(mutable.freeze() === mutable)
                check(!mutable.isFrozen)
                mutable.value = 2
                check(mutable.value == 2)
            }
            """
        )
    }

    @Test
    fun weakAndUnownedPropertiesFieldsAndLocalsCompile() {
        assumeLinuxHost()
        assertCompiles(
            "validReferences",
            """
            import kotlin.native.arc.ArcUnowned
            import kotlin.native.arc.ArcWeak

            class Holder {
                @ArcWeak
                var weakProperty: Any? = null

                @field:ArcWeak
                var weakField: Any? = null

                @ArcUnowned
                var unownedProperty: Any = Any()

                @field:ArcUnowned
                var unownedField: Any = Any()

                fun locals() {
                    @ArcWeak var weakLocal: Any? = null
                    @ArcUnowned val unownedLocal: Any = Any()
                    weakLocal = unownedLocal
                }
            }
            """
        )
    }

    @Test
    fun weakUnownedAndDeinitRuntimeSemantics() {
        compileAndRun(
            "arcReferenceAndDeinitSemantics",
            """
            import kotlin.native.arc.ArcDeinit
            import kotlin.native.arc.ArcUnowned
            import kotlin.native.arc.ArcWeak

            private class Target(val value: Int)

            private class References(target: Target) {
                @ArcWeak var weak: Target? = target
                @ArcUnowned var unowned: Target = target
            }

            private fun referencesWithoutOwner(): References {
                val target = Target(42)
                val references = References(target)
                check(references.weak?.value == 42)
                check(references.unowned.value == 42)
                return references
            }

            private fun weakIsNull(references: References): Boolean = references.weak == null

            private var deinitOrder = ""

            private open class Base(private val baseValue: Int = 1) {
                @ArcDeinit
                private fun deinitBase() {
                    check(baseValue == 1)
                    deinitOrder += "B"
                }
            }

            private class Derived(private val derivedValue: Int = 2) : Base() {
                @ArcDeinit
                private fun deinitDerived() {
                    check(derivedValue == 2)
                    deinitOrder += "D"
                }
            }

            private class ConstructorFailure : Base() {
                init { throw IllegalStateException("expected") }

                @ArcDeinit
                private fun deinitFailure() {
                    deinitOrder += "F"
                }
            }

            private fun releaseDerived() {
                val first = Derived()
                val second = first
                check(second === first)
            }

            private fun failConstruction() {
                try {
                    ConstructorFailure()
                    error("constructor unexpectedly returned")
                } catch (_: IllegalStateException) {
                }
            }

            fun main() {
                val references = referencesWithoutOwner()
                check(weakIsNull(references))

                releaseDerived()
                check(deinitOrder == "DB") { "unexpected deinit order: ${'$'}deinitOrder" }

                deinitOrder = ""
                failConstruction()
                check(deinitOrder == "B") { "unexpected constructor-failure order: ${'$'}deinitOrder" }
            }
            """
        )
    }

    @Test
    fun weakRequiresMutableNullableReferenceStorage() {
        assumeLinuxHost()
        assertFails(
            "weakImmutable",
            """
            import kotlin.native.arc.ArcWeak
            class Holder { @ArcWeak val reference: Any? = null }
            """,
            WEAK_SHAPE
        )
        assertFails(
            "weakNonNull",
            """
            import kotlin.native.arc.ArcWeak
            class Holder { @ArcWeak var reference: Any = Any() }
            """,
            WEAK_SHAPE
        )
        assertFails(
            "weakPrimitive",
            """
            import kotlin.native.arc.ArcWeak
            class Holder { @ArcWeak var value: Int = 0 }
            """,
            ARC_REFERENCE_ONLY
        )
    }

    @Test
    fun unownedRequiresNonNullReferenceStorage() {
        assumeLinuxHost()
        assertFails(
            "unownedNullable",
            """
            import kotlin.native.arc.ArcUnowned
            class Holder { @ArcUnowned var reference: Any? = null }
            """,
            UNOWNED_SHAPE
        )
        assertFails(
            "unownedPrimitive",
            """
            import kotlin.native.arc.ArcUnowned
            class Holder { @ArcUnowned var value: Int = 0 }
            """,
            ARC_REFERENCE_ONLY
        )
        assertFails(
            "unownedWithoutStorage",
            """
            import kotlin.native.arc.ArcUnowned
            class Holder { @ArcUnowned val reference: Any get() = Any() }
            """,
            "ARC reference annotations require a declaration with storage"
        )
    }

    @Test
    fun referenceAnnotationsRejectConflictsAndDelegatedProperties() {
        assumeLinuxHost()
        assertFails(
            "conflictingReferences",
            """
            import kotlin.native.arc.ArcUnowned
            import kotlin.native.arc.ArcWeak
            class Holder {
                @ArcWeak
                @field:ArcUnowned
                var reference: Any? = null
            }
            """,
            "@ArcWeak and @ArcUnowned cannot be used on the same declaration"
        )
        assertFails(
            "delegatedReference",
            """
            import kotlin.native.arc.ArcWeak
            import kotlin.reflect.KProperty

            class Delegate {
                operator fun getValue(thisRef: Any?, property: KProperty<*>): Any? = null
                operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Any?) {}
            }

            class Holder { @ArcWeak var reference: Any? by Delegate() }
            """,
            "ARC reference annotations are not supported on delegated properties"
        )
    }

    @Test
    fun deinitAcceptsOneValidMember() {
        assumeLinuxHost()
        assertCompiles(
            "validDeinit",
            """
            import kotlin.native.arc.ArcDeinit
            class Holder { @ArcDeinit private fun deinit() {} }
            """
        )
    }

    @Test
    fun deinitRejectsInvalidShapesAndDuplicates() {
        assumeLinuxHost()
        val invalidDeclarations = listOf(
            "public fun deinit() {}",
            "protected open fun deinit() {}",
            "private fun deinit(value: Int) {}",
            "private suspend fun deinit() {}",
            "private fun deinit(): Int = 0",
            "private fun String.deinit() {}",
        )
        invalidDeclarations.forEachIndexed { index, declaration ->
            assertFails(
                "invalidDeinit$index",
                """
                import kotlin.native.arc.ArcDeinit
                open class Holder { @ArcDeinit $declaration }
                """,
                DEINIT_SHAPE
            )
        }
        assertFails(
            "duplicateDeinit",
            """
            import kotlin.native.arc.ArcDeinit
            class Holder {
                @ArcDeinit private fun first() {}
                @ArcDeinit private fun second() {}
            }
            """,
            "a class may declare only one @ArcDeinit member function"
        )
    }
}

@Tag("arc")
@EnforcedHostTarget
@EnforcedProperty(ClassLevelProperty.MEMORY_MODEL, "EXPERIMENTAL")
class ArcAnnotationsOutsideArcTest : AbstractNativeSimpleTest() {
    @Test
    fun arcAnnotationsFailOutsideArc() {
        assumeLinuxHost()
        listOf(
            "ArcWeak" to "class Holder { @ArcWeak var reference: Any? = null }",
            "ArcUnowned" to "class Holder { @ArcUnowned var reference: Any = Any() }",
            "ArcDeinit" to "class Holder { @ArcDeinit private fun deinit() {} }",
        ).forEach { (annotation, declaration) ->
            assertFails(
                "outside$annotation",
                """
                import kotlin.native.arc.$annotation
                $declaration
                """,
                "@$annotation is $ARC_ONLY"
            )
        }
    }
}
