/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import llvm.LLVMAddFunction
import llvm.LLVMAppendBasicBlockInContext
import llvm.LLVMBuildRet
import llvm.LLVMCountBasicBlocks
import llvm.LLVMCreateBuilderInContext
import llvm.LLVMDisposeBuilder
import llvm.LLVMGetNamedFunction
import llvm.LLVMGetParam
import llvm.LLVMPositionBuilderAtEnd
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.llvm.functionType
import org.jetbrains.kotlin.konan.target.KonanTarget

/**
 * Installs the cleanup personality required by Rust frame guards.
 *
 * Generated Rust bodies cannot originate Rust panics. This forwarding definition lets a foreign
 * Kotlin C++ exception execute Rust cleanup landingpads and continue through the same platform C++
 * unwinder, without linking a second exception runtime.
 */
internal fun installRustEhPersonality(generationState: NativeGenerationState) {
    val module = generationState.llvm.module
    LLVMGetNamedFunction(module, RUST_EH_PERSONALITY)?.let {
        require(LLVMCountBasicBlocks(it) > 0) { "$RUST_EH_PERSONALITY must be defined by the Rust backend" }
        return
    }

    val llvm = generationState.llvm
    val parameterTypes = when (generationState.config.target) {
        // The GNU SEH personality receives EXCEPTION_RECORD, establisher frame, CONTEXT,
        // and DISPATCHER_CONTEXT pointers. Linux x64 uses the Itanium ABI.
        KonanTarget.MINGW_X64 -> List(4) { llvm.pointerType }
        KonanTarget.LINUX_X64 -> listOf(llvm.int32Type, llvm.int32Type, llvm.int64Type, llvm.pointerType, llvm.pointerType)
        else -> error("Rust cleanup personality is not implemented for ${generationState.config.target}")
    }
    val personalityType = functionType(
        llvm.int32Type,
        isVarArg = false,
        paramTypes = parameterTypes,
    )
    // Materialize Kotlin/Native's canonical variadic declaration before adding the wrapper.
    // CodeGenerator reuses this callable for ordinary Kotlin landingpads.
    val cxxPersonality = llvm.gxxPersonalityFunction
    val rustPersonality = LLVMAddFunction(module, RUST_EH_PERSONALITY, personalityType)

    val block = LLVMAppendBasicBlockInContext(generationState.llvmContext, rustPersonality, "entry")
    val builder = LLVMCreateBuilderInContext(generationState.llvmContext)!!
    try {
        LLVMPositionBuilderAtEnd(builder, block)
        val arguments = parameterTypes.indices.map { LLVMGetParam(rustPersonality, it)!! }
        val result = cxxPersonality.buildCall(builder, arguments)
        LLVMBuildRet(builder, result)
    } finally {
        LLVMDisposeBuilder(builder)
    }
}

private const val RUST_EH_PERSONALITY = "rust_eh_personality"
