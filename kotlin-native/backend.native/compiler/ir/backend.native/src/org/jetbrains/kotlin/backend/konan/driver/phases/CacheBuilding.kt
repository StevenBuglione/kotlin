/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.driver.phases

import llvm.LLVMDisposeModule
import llvm.LLVMAddStripDeadPrototypesPass
import llvm.LLVMCreatePassManager
import llvm.LLVMDisposePassManager
import llvm.LLVMRunPassManager
import llvm.LLVMStripModuleDebugInfo
import llvm.LLVMWriteBitcodeToFile
import org.jetbrains.kotlin.backend.konan.CacheStorage
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.OutputFiles
import org.jetbrains.kotlin.backend.konan.descriptors.isFromInteropLibrary
import org.jetbrains.kotlin.backend.konan.driver.PhaseContext
import org.jetbrains.kotlin.backend.konan.driver.utilities.getDefaultIrActions
import org.jetbrains.kotlin.backend.konan.lower.CacheInfoBuilder
import org.jetbrains.kotlin.backend.konan.optimizations.createArcSelectiveInlineCompanion
import org.jetbrains.kotlin.backend.konan.optimizations.shouldWriteArcSelectiveInlineCompanion
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

internal val BuildAdditionalCacheInfoPhase = createSimpleNamedCompilerPhase<NativeGenerationState, IrModuleFragment>(
        name = "BuildAdditionalCacheInfo",
        description = "Build additional cache info (inline functions bodies and fields of classes)",
        preactions = getDefaultIrActions(),
        postactions = getDefaultIrActions(),
) { context, module ->
    // TODO: Use explicit parameter
    val parent = context.context
    val moduleDeserializer = parent.irLinker.moduleDeserializers[module.descriptor]
    if (moduleDeserializer == null) {
        require(module.descriptor.isFromInteropLibrary()) { "No module deserializer for ${module.descriptor}" }
    } else {
        CacheInfoBuilder(context, moduleDeserializer, module).build()
    }
}

/**
 * It is naturally a part of "produce LLVM module", so using NativeGenerationState context should be OK.
 */
internal val SaveAdditionalCacheInfoPhase = createSimpleNamedCompilerPhase<NativeGenerationState, Unit>(
        name = "SaveAdditionalCacheInfo",
        description = "Save additional cache info (inline functions bodies and fields of classes)"
) { context, _ ->
    // TODO: Extract necessary parts of context into explicit input.
    CacheStorage(context).saveAdditionalCacheInfo()
}

internal val SaveArcSelectiveInlineCompanionPhase = createSimpleNamedCompilerPhase<NativeGenerationState, Unit>(
        name = "SaveArcSelectiveInlineCompanion",
        description = "Save private stdlib bodies used by ARC selective inlining"
) { context, _ ->
    if (!context.shouldWriteArcSelectiveInlineCompanion()) return@createSimpleNamedCompilerPhase
    val output = requireNotNull(context.outputFiles.arcSelectiveInlineCompanionFile) {
        "ARC selective-inline companion requires cache output"
    }
    val companion = requireNotNull(createArcSelectiveInlineCompanion(context.llvm.module)) {
        "ARC stdlib cache does not define the supported StringBuilder.append(String?) body"
    }
    try {
        LLVMStripModuleDebugInfo(companion)
        val passManager = LLVMCreatePassManager()!!
        try {
            // CloneModule intentionally turns every excluded definition into a
            // declaration. Retain declarations actually referenced by the
            // five-function closure, but do not carry the entire stdlib's dead
            // prototype table in this private cache artifact.
            LLVMAddStripDeadPrototypesPass(passManager)
            LLVMRunPassManager(passManager, companion)
        } finally {
            LLVMDisposePassManager(passManager)
        }
        check(LLVMWriteBitcodeToFile(companion, output.absolutePath) == 0) {
            "Failed to write ARC selective-inline companion to ${output.absolutePath}"
        }
    } finally {
        LLVMDisposeModule(companion)
    }
}

internal val FinalizeCachePhase = createSimpleNamedCompilerPhase<PhaseContext, OutputFiles>(
        name = "FinalizeCache",
        description = "Finalize cache (rename temp to the final dist)"
) { _, outputFiles ->
    //  TODO: Explicit parameter
    CacheStorage.renameOutput(outputFiles)
}
