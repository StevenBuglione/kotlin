/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.test.cases.components.relationProvider

import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDebugRenderer
import org.jetbrains.kotlin.analysis.test.framework.base.AbstractAnalysisApiBasedTest
import org.jetbrains.kotlin.analysis.test.framework.projectStructure.KtTestModule
import org.jetbrains.kotlin.analysis.test.framework.targets.getSingleTestTargetSymbolOfType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions

abstract class AbstractFakeOverrideOriginalTest : AbstractAnalysisApiBasedTest() {
    override fun doTestByMainFile(mainFile: KtFile, mainModule: KtTestModule, testServices: TestServices) {
        val actual = copyAwareAnalyzeForTest(mainFile) { contextFile ->
            val symbol = getSingleTestTargetSymbolOfType<KaCallableSymbol>(testDataPath, contextFile)
            val original = symbol.fakeOverrideOriginal

            val renderer = KaDebugRenderer(renderExpandedTypes = true)
            buildString {
                appendLine("IS_THE_SAME_SYMBOL:")
                appendLine(original == symbol)
                appendLine("FAKE_OVERRIDE_ORIGINAL:")
                appendLine(renderer.render(useSiteSession, original))
            }
        }
        testServices.assertions.assertEqualsToTestOutputFile(actual)
    }
}
