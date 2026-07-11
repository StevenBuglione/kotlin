/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import kotlinx.cinterop.toKString
import llvm.LLVMCloneModule
import llvm.LLVMDisposeModule
import llvm.LLVMGetDataLayoutStr
import llvm.LLVMGetNamedFunction
import llvm.LLVMGetTarget
import llvm.LLVMLinkage
import llvm.LLVMModuleRef
import llvm.LLVMSetLinkage
import org.jetbrains.kotlin.backend.konan.NativeCodegenMode
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.RuntimeNames
import org.jetbrains.kotlin.backend.konan.llvm.getGlobalFunctionType
import org.jetbrains.kotlin.backend.konan.llvm.llvmLinkModules2
import org.jetbrains.kotlin.backend.konan.llvm.llvmtype2string
import org.jetbrains.kotlin.backend.konan.llvm.parseBitcodeFile
import org.jetbrains.kotlin.backend.konan.llvm.verifyModule
import org.jetbrains.kotlin.backend.konan.nativeCodegenMode
import org.jetbrains.kotlin.backend.konan.lower.isEagerStaticInitializer
import org.jetbrains.kotlin.backend.konan.lower.isLazyStaticInitializer
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustIrCodegen
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustLinkerSymbolNamer
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.moduleDescriptor
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isTopLevel
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal data class RustHybridModuleArtifact(
    val bitcodeFile: File,
    val generatedFunctions: List<IrSimpleFunction>,
    val fallbackFunctions: List<IrSimpleFunction>,
)

/**
 * Compiles the primitive, ABI-compatible portion of a lowered module as no-std Rust bitcode.
 *
 * This deliberately leaves the entry point, runtime startup, metadata, and every unsupported
 * function in the LLVM module. Returning null preserves atomic LLVM fallback before any function
 * body has been suppressed.
 */
internal fun tryCompileRustHybridModule(
    generationState: NativeGenerationState,
    irModule: IrModuleFragment,
): RustHybridModuleArtifact? {
    val config = generationState.config
    if (config.configuration.nativeCodegenMode != NativeCodegenMode.RUST_HYBRID) return null

    val targetTriple = when (config.target) {
        KonanTarget.MINGW_X64 -> "x86_64-pc-windows-gnu"
        KonanTarget.LINUX_X64 -> "x86_64-unknown-linux-gnu"
        else -> return null
    }
    if (generationState.cacheDeserializationStrategy != null) return null

    val entryPoint = generationState.context.symbols.entryPoint?.owner ?: return null
    val sourceModule = entryPoint.getPackageFragment().moduleDescriptor
    val candidates = irModule.files.asSequence()
        .flatMap { file -> file.declarations.asSequence() }
        .filterIsInstance<IrSimpleFunction>()
        .filter { function -> function.isRustBodyEligible(generationState, sourceModule, entryPoint) }
        .toList()
    if (candidates.isEmpty()) return null

    val symbolNamer = RustLinkerSymbolNamer { function ->
        generationState.llvmDeclarations.forFunction(function).name
            ?: error("Kotlin/Native produced an unnamed LLVM function for ${function.name}")
    }
    val result = RustIrCodegen(
        symbolNamer,
        allowRustStandardIo = false,
        functionPrologue = "unsafe { Kotlin_mm_safePointFunctionPrologue(); }",
    ).generate(irModule, candidates)
    val candidateSet = candidates.toSet()
    val generated = result.generatedFunctions.filter { it.declaration in candidateSet }
    if (generated.isEmpty()) return null
    if (generated.size != result.generatedFunctions.size) return null
    if (result.fallbackFunctions.any { it.declaration !in candidateSet }) return null
    if (result.generatedFunctions.any { it.declaration === entryPoint }) return null

    val outputFile = File(generationState.outputFiles.mainFileName).absoluteFile
    val workspace = outputFile.parentFile
        .resolve(".kotlin-rust")
        .resolve(outputFile.nameWithoutExtension)
    val successMarker = workspace.resolve("rust-bitcode-preflight-ok")
    return try {
        Files.deleteIfExists(successMarker.toPath())
        val artifact = RustBitcodeLibraryCompiler().compile(
            packageName = "kotlin_native_rust_module",
            targetTriple = targetTriple,
            renderedLibraryRs = buildString {
                appendLine("#![no_std]")
                append(result.source)
                appendLine()
                appendLine("extern \"C\" {")
                appendLine("    fn Kotlin_mm_safePointFunctionPrologue();")
                appendLine("}")
            },
            outputDirectory = workspace.toPath(),
            release = config.optimizationsEnabled,
        )
        val boundaryFunctions = (generated + result.fallbackFunctions)
            .associate { it.declaration to it.linkerName }
        if (!preflightRustBitcode(generationState, artifact.llvmBitcode, boundaryFunctions)) return null
        Files.write(successMarker.toPath(), (artifact.llvmBitcode.fileName.toString() + "\n").toByteArray())
        RustHybridModuleArtifact(
            artifact.llvmBitcode.toFile(),
            generated.map { it.declaration },
            result.fallbackFunctions.map { it.declaration },
        )
    } catch (failure: Exception) {
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        null
    }
}

private fun IrSimpleFunction.isRustBodyEligible(
    generationState: NativeGenerationState,
    sourceModule: ModuleDescriptor,
    entryPoint: IrSimpleFunction,
): Boolean =
    this !== entryPoint &&
            origin == IrDeclarationOrigin.DEFINED &&
            isTopLevel &&
            !isExternal &&
            !isSuspend &&
            typeParameters.isEmpty() &&
            body != null &&
            !isLazyStaticInitializer &&
            !isEagerStaticInitializer &&
            parameters.all { it.kind == IrParameterKind.Regular && it.type.isPrimitiveRustParameterType() } &&
            returnType.isPrimitiveRustReturnType() &&
            !hasAnnotation(RuntimeNames.exportedBridge) &&
            !hasAnnotation(RuntimeNames.exportForCppRuntime) &&
            getPackageFragment().moduleDescriptor == sourceModule &&
            generationState.llvmModuleSpecification.containsDeclaration(this)

private fun IrType.isPrimitiveRustParameterType(): Boolean =
    !isNullable() && (isInt() || isLong())

private fun IrType.isPrimitiveRustReturnType(): Boolean =
    !isNullable() && (isInt() || isLong() || isUnit())

private fun preflightRustBitcode(
    generationState: NativeGenerationState,
    bitcodePath: Path,
    boundaryFunctions: Map<IrSimpleFunction, String>,
): Boolean {
    var rustModule: LLVMModuleRef? = null
    var rustModuleConsumed = false
    var clone: LLVMModuleRef? = null
    return try {
        rustModule = parseBitcodeFile(
            generationState,
            generationState.diagnosticReporter,
            generationState.llvmContext,
            bitcodePath.toString(),
        )
        if (moduleTarget(rustModule) != moduleTarget(generationState.llvm.module)) return false
        if (moduleDataLayout(rustModule) != moduleDataLayout(generationState.llvm.module)) return false
        verifyModule(rustModule, "generated Rust bitcode")

        for (entry in boundaryFunctions.entries) {
            val rustFunction = LLVMGetNamedFunction(rustModule, entry.value) ?: return false
            val kotlinType = generationState.llvmDeclarations.forFunction(entry.key).functionType
            val rustType = getGlobalFunctionType(rustFunction)
            if (llvmtype2string(kotlinType) != llvmtype2string(rustType)) return false
        }

        clone = LLVMCloneModule(generationState.llvm.module)
            ?: error("Could not clone the Kotlin/Native LLVM module for Rust preflight")
        boundaryFunctions.values.forEach { symbolName ->
            val clonedFunction = LLVMGetNamedFunction(clone, symbolName) ?: return false
            LLVMSetLinkage(clonedFunction, LLVMLinkage.LLVMExternalLinkage)
        }
        rustModuleConsumed = true
        if (llvmLinkModules2(generationState, clone, rustModule) != 0) return false
        true
    } catch (failure: Throwable) {
        if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
        false
    } finally {
        if (!rustModuleConsumed) rustModule?.let { LLVMDisposeModule(it) }
        clone?.let { LLVMDisposeModule(it) }
    }
}

private fun moduleTarget(module: LLVMModuleRef): String = LLVMGetTarget(module)?.toKString().orEmpty()

private fun moduleDataLayout(module: LLVMModuleRef): String = LLVMGetDataLayoutStr(module)?.toKString().orEmpty()
