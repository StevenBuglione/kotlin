/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.driver.phases

import llvm.LLVMDumpModule
import llvm.LLVMModuleRef
import llvm.LLVMWriteBitcodeToFile
import org.jetbrains.kotlin.backend.common.LoggingContext
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.checkLlvmModuleExternalCalls
import org.jetbrains.kotlin.backend.konan.createLTOFinalPipelineConfig
import org.jetbrains.kotlin.backend.konan.driver.BasicPhaseContext
import org.jetbrains.kotlin.backend.konan.driver.PhaseContext
import org.jetbrains.kotlin.backend.konan.driver.PhaseEngine
import org.jetbrains.kotlin.backend.konan.driver.utilities.LlvmIrHolder
import org.jetbrains.kotlin.backend.konan.driver.utilities.getDefaultLlvmModuleActions
import org.jetbrains.kotlin.backend.konan.insertAliasToEntryPoint
import org.jetbrains.kotlin.backend.konan.llvm.coverage.runCoveragePass
import org.jetbrains.kotlin.backend.konan.llvm.verifyModule
import org.jetbrains.kotlin.backend.konan.optimizations.RemoveRedundantSafepointsPass
import org.jetbrains.kotlin.backend.konan.optimizations.coalesceAdjacentArcReturnUpdates
import org.jetbrains.kotlin.backend.konan.optimizations.prepareArcFrameElision
import org.jetbrains.kotlin.backend.konan.optimizations.removeMultipleThreadDataLoads
import org.jetbrains.kotlin.backend.konan.optimizations.removeEmptyArcFrames
import org.jetbrains.kotlin.backend.konan.optimizations.restoreArcFrameElision
import org.jetbrains.kotlin.backend.konan.optimizations.sealArcFrameElisionForLTO
import org.jetbrains.kotlin.konan.target.SanitizerKind
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File


internal data class WriteBitcodeFileInput(
        override val llvmModule: LLVMModuleRef,
        val outputFile: File,
) : LlvmIrHolder

/**
 * Write in-memory LLVM module to filesystem as a bitcode.
 */
internal val WriteBitcodeFilePhase = createSimpleNamedCompilerPhase<PhaseContext, WriteBitcodeFileInput>(
        "WriteBitcodeFile",
        "Write bitcode file",
) { context, (llvmModule, outputFile) ->
    // Insert `_main` after pipeline, so we won't worry about optimizations corrupting entry point.
    insertAliasToEntryPoint(context, llvmModule)
    LLVMWriteBitcodeToFile(llvmModule, outputFile.canonicalPath)
}

internal val CheckExternalCallsPhase = createSimpleNamedCompilerPhase<NativeGenerationState, Unit>(
        name = "CheckExternalCalls",
        description = "Check external calls",
        postactions = getDefaultLlvmModuleActions(),
) { context, _ ->
    checkLlvmModuleExternalCalls(context)
}

internal val RewriteExternalCallsCheckerGlobals = createSimpleNamedCompilerPhase<NativeGenerationState, Unit>(
        name = "RewriteExternalCallsCheckerGlobals",
        description = "Rewrite globals for external calls checker after optimizer run",
        postactions = getDefaultLlvmModuleActions(),
) { context, _ ->
    addFunctionsListSymbolForChecker(context)
}

internal class OptimizationState(
        konanConfig: KonanConfig,
        val llvmConfig: LlvmPipelineConfig
) : BasicPhaseContext(konanConfig)

internal fun optimizationPipelinePass(name: String, description: String, pipeline: (LlvmPipelineConfig, LoggingContext) -> LlvmOptimizationPipeline) =
        createSimpleNamedCompilerPhase<OptimizationState, LLVMModuleRef>(
                name = name,
                description = description,
                postactions = getDefaultLlvmModuleActions(),
        ) { context, module ->
            pipeline(context.llvmConfig, context).use {
                it.execute(module)
            }
        }


internal val MandatoryBitcodeLLVMPostprocessingPhase = optimizationPipelinePass(
        name = "MandatoryBitcodeLLVMPostprocessingPhase",
        description = "Mandatory bitcode llvm postprocessing",
        pipeline = ::MandatoryOptimizationPipeline,
)

internal val ModuleBitcodeOptimizationPhase = optimizationPipelinePass(
        name = "ModuleBitcodeOptimization",
        description = "Optimize bitcode",
        pipeline = ::ModuleOptimizationPipeline,
)

internal val RemoveEmptyArcFramesPhase = createSimpleNamedCompilerPhase<OptimizationState, LLVMModuleRef>(
        name = "RemoveEmptyArcFrames",
        description = "Checkpoint after removing proven empty non-unwinding ARC frames",
        postactions = getDefaultLlvmModuleActions(),
) { _, _ -> }

internal val LTOBitcodeOptimizationPhase = optimizationPipelinePass(
        name = "LTOBitcodeOptimization",
        description = "Runs llvm lto pipeline",
        pipeline = ::LTOOptimizationPipeline
)

internal val CoalesceAdjacentArcReturnUpdatesPhase = createSimpleNamedCompilerPhase<OptimizationState, LLVMModuleRef>(
        name = "CoalesceAdjacentArcReturnUpdates",
        description = "Remove literally adjacent identical ARC return-slot updates after LTO",
        postactions = getDefaultLlvmModuleActions(),
) { context, module ->
    val removed = coalesceAdjacentArcReturnUpdates(module)
    context.log { "Removed $removed adjacent identical ARC return-slot update(s)" }
}

internal val ThreadSanitizerPhase = optimizationPipelinePass(
        name = "ThreadSanitizer",
        description = "Prepare to run with thread sanitizer",
        pipeline = ::ThreadSanitizerPipeline,
)

internal val AddressSanitizerPhase = optimizationPipelinePass(
        name = "AddressSanitizer",
        description = "Prepare to run with address sanitizer",
        pipeline = ::AddressSanitizerPipeline,
)

internal val CoveragePhase = createSimpleNamedCompilerPhase<NativeGenerationState, Unit>(
        name = "Coverage",
        description = "Produce coverage information",
        postactions = getDefaultLlvmModuleActions(),
        op = { context, _ -> runCoveragePass(context) }
)

internal val RemoveRedundantSafepointsPhase = createSimpleNamedCompilerPhase<BitcodePostProcessingContext, Unit>(
        name = "RemoveRedundantSafepoints",
        description = "Remove function prologue safepoints inlined to another function",
        postactions = getDefaultLlvmModuleActions(),
        op = { context, _ ->
            RemoveRedundantSafepointsPass().runOnModule(
                    module = context.llvm.module,
                    isSafepointInliningAllowed = context.shouldInlineSafepoints()
            )
        }
)

internal val OptimizeTLSDataLoadsPhase = createSimpleNamedCompilerPhase<BitcodePostProcessingContext, Unit>(
        name = "OptimizeTLSDataLoads",
        description = "Optimize multiple loads of thread data",
        postactions = getDefaultLlvmModuleActions(),
        op = { context, _ -> removeMultipleThreadDataLoads(context) }
)

internal val CStubsPhase = createSimpleNamedCompilerPhase<NativeGenerationState, Unit>(
        name = "CStubs",
        description = "C stubs compilation",
        postactions = getDefaultLlvmModuleActions(),
        op = { context, _ -> produceCStubs(context) }
)

internal val LinkBitcodeDependenciesPhase = createSimpleNamedCompilerPhase<NativeGenerationState, List<File>>(
        name = "LinkBitcodeDependencies",
        description = "Link bitcode dependencies",
        postactions = getDefaultLlvmModuleActions(),
        op = { context, input -> linkBitcodeDependencies(context, input) }
)

internal val VerifyBitcodePhase = createSimpleNamedCompilerPhase<PhaseContext, LLVMModuleRef>(
        name = "VerifyBitcode",
        description = "Verify bitcode",
        op = { _, llvmModule -> verifyModule(llvmModule) }
)

internal val PrintBitcodePhase = createSimpleNamedCompilerPhase<PhaseContext, LLVMModuleRef>(
        name = "PrintBitcode",
        description = "Print bitcode",
        op = { _, llvmModule -> LLVMDumpModule(llvmModule) }
)

internal fun <T : BitcodePostProcessingContext> PhaseEngine<T>.runBitcodePostProcessing() {
    val optimizationConfig = createLTOFinalPipelineConfig(
            context,
            context.llvm.targetTriple,
            closedWorld = context.config.isFinalBinary,
            timePasses = context.config.flexiblePhaseConfig.needProfiling,
    )
    val arcFrameElisionConfigurationAllows = context is NativeGenerationState &&
            context.config.memoryModel == MemoryModel.ARC &&
            context.config.target == KonanTarget.LINUX_X64 &&
            context.config.optimizationsEnabled &&
            !context.shouldContainAnyDebugInfo() &&
            context.config.sanitizer == null &&
            !context.config.undefinedBehaviorSanitizer &&
            !context.coverage.enabled
    val arcFrameElisionRequested = arcFrameElisionConfigurationAllows &&
            context.config.flexiblePhaseConfig.isEnabled(RemoveEmptyArcFramesPhase)
    val arcReturnUpdateCoalescingRequested = context.config.memoryModel == MemoryModel.ARC &&
            context.config.optimizationsEnabled
    useContext(OptimizationState(context.config, optimizationConfig)) {
        val module = this@runBitcodePostProcessing.context.llvmModule
        it.runPhase(MandatoryBitcodeLLVMPostprocessingPhase, module)
        var arcFrameElisionActive = arcFrameElisionRequested && prepareArcFrameElision(module)
        var removedArcFrames = 0
        var arcFrameInlineFailures = 0
        try {
            it.runPhase(ModuleBitcodeOptimizationPhase, module)
            if (arcFrameElisionActive && !sealArcFrameElisionForLTO(module)) {
                restoreArcFrameElision(module)
                arcFrameElisionActive = false
            }
            // Preserve exact frame-wrapper calls through LTO so empty frames
            // exposed by late Kotlin-body inlining remain auditable.
            it.runPhase(LTOBitcodeOptimizationPhase, module)
            if (arcFrameElisionActive) {
                val result = removeEmptyArcFrames(module)
                removedArcFrames = result.removedFrames
                arcFrameInlineFailures = result.inlineFailures
            }
        } finally {
            // Failure paths restore attributes without transforming partially
            // optimized IR. The successful path is idempotently restored too.
            if (arcFrameElisionActive) restoreArcFrameElision(module)
        }
        if (arcFrameElisionActive) {
            context.log {
                "Removed $removedArcFrames empty ARC frame(s); " +
                        "$arcFrameInlineFailures retained wrapper call(s) could not be inlined"
            }
        }
        // User-visible phase checkpoint for dumps and verification. The
        // mutation above is deliberately not independently disable-able.
        it.runPhase(RemoveEmptyArcFramesPhase, module, disable = !arcFrameElisionActive)
        // Empty-frame finalization can make formerly separated return-slot updates literally
        // adjacent. Run the exact-pair scanner at that first safe point, still before sanitizers.
        it.runPhase(
                CoalesceAdjacentArcReturnUpdatesPhase,
                module,
                disable = !arcReturnUpdateCoalescingRequested,
        )
        when (context.config.sanitizer) {
            SanitizerKind.THREAD -> it.runPhase(ThreadSanitizerPhase, module)
            SanitizerKind.ADDRESS -> it.runPhase(AddressSanitizerPhase, module)
            null -> {}
        }
    }
    if (context is NativeGenerationState && context.coverage.enabled) {
        newEngine(context) { it.runPhase(CoveragePhase) }
    }
    if (context.config.memoryModel.usesTracingGC) {
        runPhase(RemoveRedundantSafepointsPhase)
    }
    if (context.config.optimizationsEnabled) {
        runPhase(OptimizeTLSDataLoadsPhase)
    }
}
