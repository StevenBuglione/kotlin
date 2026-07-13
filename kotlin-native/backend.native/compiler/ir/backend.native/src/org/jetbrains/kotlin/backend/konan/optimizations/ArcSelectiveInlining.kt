/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.optimizations

import kotlinx.cinterop.useContents
import llvm.LLVMKotlinArcSelectiveInlineStats
import llvm.LLVMKotlinCountArcTaggedCalls
import llvm.LLVMKotlinCreateArcSelectiveInlineCompanion
import llvm.LLVMKotlinInlineArcTaggedCalls
import llvm.LLVMModuleRef
import org.jetbrains.kotlin.backend.konan.BitcodePostProcessingContext
import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.isFinalBinary
import org.jetbrains.kotlin.backend.konan.isCache
import org.jetbrains.kotlin.konan.target.KonanTarget

internal const val ARC_SELECTIVE_INLINE_METADATA = "konan.arc.inline"
internal const val ARC_STRING_BUILDER_APPEND_STRING =
    "kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"
internal const val ARC_STRING_BUILDER_APPEND_STRING_CLONE =
    "__konan_arc_inline_string_builder_append_string_v1"

internal data class ArcSelectiveInliningResult(
    val tagged: Int,
    val inlined: Int,
    val rejected: Int,
    val missingBody: Int,
    val budgetSkipped: Int,
    val inlineFailures: Int,
)

internal fun countArcSelectiveInlineTags(module: LLVMModuleRef): Int =
    LLVMKotlinCountArcTaggedCalls(module, ARC_SELECTIVE_INLINE_METADATA)

internal fun createArcSelectiveInlineCompanion(module: LLVMModuleRef): LLVMModuleRef? =
    LLVMKotlinCreateArcSelectiveInlineCompanion(
        module,
        ARC_STRING_BUILDER_APPEND_STRING,
        ARC_STRING_BUILDER_APPEND_STRING_CLONE,
    )

internal fun inlineArcSelectiveCalls(module: LLVMModuleRef): ArcSelectiveInliningResult =
    LLVMKotlinInlineArcTaggedCalls(
        module,
        ARC_SELECTIVE_INLINE_METADATA,
        ARC_STRING_BUILDER_APPEND_STRING,
        ARC_STRING_BUILDER_APPEND_STRING_CLONE,
        96,
        192,
        4096,
    ).useContents {
        ArcSelectiveInliningResult(tagged, inlined, rejected, missingBody, budgetSkipped, inlineFailures)
    }

internal fun NativeGenerationState.shouldWriteArcSelectiveInlineCompanion(): Boolean =
    config.memoryModel == MemoryModel.ARC &&
            config.target == KonanTarget.LINUX_X64 &&
            config.produce.isCache &&
            !config.producePerFileCache &&
            producedLlvmModuleContainsStdlib &&
            config.sanitizer == null &&
            !config.undefinedBehaviorSanitizer &&
            !coverage.enabled

internal fun BitcodePostProcessingContext.shouldRunArcSelectiveInlining(): Boolean =
    config.memoryModel == MemoryModel.ARC &&
            config.target == KonanTarget.LINUX_X64 &&
            config.isFinalBinary &&
            config.optimizationsEnabled &&
            !shouldContainAnyDebugInfo() &&
            !config.arcDiagnosticsEnabled &&
            config.sanitizer == null &&
            !config.undefinedBehaviorSanitizer &&
            (this !is NativeGenerationState || !coverage.enabled)
