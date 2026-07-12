/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.optimizations

import llvm.LLVMKotlinCountDirectArcFrameWrapperCalls
import llvm.LLVMKotlinPrepareArcFrameElision
import llvm.LLVMKotlinRemoveEmptyArcFrames
import llvm.LLVMKotlinRestoreArcFrameElision
import llvm.LLVMKotlinSealArcFrameElisionForLTO
import llvm.LLVMModuleRef

internal fun prepareArcFrameElision(module: LLVMModuleRef): Boolean =
        LLVMKotlinPrepareArcFrameElision(module) != 0

internal fun restoreArcFrameElision(module: LLVMModuleRef) {
    LLVMKotlinRestoreArcFrameElision(module)
}

internal fun sealArcFrameElisionForLTO(module: LLVMModuleRef): Boolean =
        LLVMKotlinSealArcFrameElisionForLTO(module) != 0

internal data class ArcFrameElisionResult(val removedFrames: Int, val inlineFailures: Int)

internal fun removeEmptyArcFrames(module: LLVMModuleRef): ArcFrameElisionResult =
        ArcFrameElisionResult(
                removedFrames = LLVMKotlinRemoveEmptyArcFrames(module),
                inlineFailures = LLVMKotlinCountDirectArcFrameWrapperCalls(module),
        )
