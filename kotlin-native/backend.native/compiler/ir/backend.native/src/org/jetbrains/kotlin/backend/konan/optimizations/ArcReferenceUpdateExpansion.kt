/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.optimizations

import kotlinx.cinterop.useContents
import llvm.LLVMKotlinExpandArcReferenceUpdates
import llvm.LLVMModuleRef
import org.jetbrains.kotlin.backend.konan.BitcodePostProcessingContext
import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.RuntimeAssertsMode
import org.jetbrains.kotlin.backend.konan.isFinalBinary
import org.jetbrains.kotlin.konan.target.KonanTarget

internal data class ArcReferenceUpdateExpansionResult(
    val candidates: Int,
    val expanded: Int,
    val rejected: Int,
    val missingRuntime: Int,
)

internal fun expandArcReferenceUpdates(module: LLVMModuleRef): ArcReferenceUpdateExpansionResult =
    LLVMKotlinExpandArcReferenceUpdates(module).useContents {
        ArcReferenceUpdateExpansionResult(candidates, expanded, rejected, missingRuntime)
    }

internal fun BitcodePostProcessingContext.shouldExpandArcReferenceUpdates(): Boolean =
    config.memoryModel == MemoryModel.ARC &&
            config.target == KonanTarget.LINUX_X64 &&
            config.isFinalBinary &&
            config.optimizationsEnabled &&
            !shouldContainAnyDebugInfo() &&
            // The expanded stack shell deliberately omits the runtime-only
            // kInitializingSingleton assertion. It is legal only when runtime
            // assertions are semantically disabled.
            config.runtimeAssertsMode == RuntimeAssertsMode.IGNORE &&
            !config.arcDiagnosticsEnabled &&
            config.sanitizer == null &&
            !config.undefinedBehaviorSanitizer &&
            (this !is NativeGenerationState || !coverage.enabled)
