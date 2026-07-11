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
import llvm.LLVMGetValueName
import llvm.LLVMAliasGetAliasee
import llvm.LLVMLinkage
import llvm.LLVMModuleRef
import llvm.LLVMTypeRef
import llvm.LLVMOffsetOfElement
import llvm.LLVMSetLinkage
import llvm.LLVMStoreSizeOfType
import org.jetbrains.kotlin.backend.konan.NativeCodegenMode
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.RuntimeNames
import org.jetbrains.kotlin.backend.konan.llvm.getGlobalAliases
import org.jetbrains.kotlin.backend.konan.llvm.getGlobalFunctionType
import org.jetbrains.kotlin.backend.konan.llvm.llvmLinkModules2
import org.jetbrains.kotlin.backend.konan.llvm.llvmtype2string
import org.jetbrains.kotlin.backend.konan.llvm.parseBitcodeFile
import org.jetbrains.kotlin.backend.konan.llvm.verifyModule
import org.jetbrains.kotlin.backend.konan.nativeCodegenMode
import org.jetbrains.kotlin.backend.konan.rustInteropBridgePlanPaths
import org.jetbrains.kotlin.backend.konan.lower.isEagerStaticInitializer
import org.jetbrains.kotlin.backend.konan.lower.isLazyStaticInitializer
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustIrCodegen
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustLinkerSymbolNamer
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustManagedFieldCodegenResult
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustManagedReferenceCodegenResult
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustPrimitiveFieldReadCodegenResult
import org.jetbrains.kotlin.backend.konan.rust.codegen.generateRustManagedFieldFunction
import org.jetbrains.kotlin.backend.konan.rust.codegen.generateRustManagedReferenceFunction
import org.jetbrains.kotlin.backend.konan.rust.codegen.generateRustPrimitiveFieldReadFunction
import org.jetbrains.kotlin.backend.konan.rust.codegen.rustManagedReferenceRuntimePrelude
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.moduleDescriptor
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isByte
import org.jetbrains.kotlin.ir.types.isChar
import org.jetbrains.kotlin.ir.types.isDouble
import org.jetbrains.kotlin.ir.types.isFloat
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.types.isShort
import org.jetbrains.kotlin.ir.types.isUByte
import org.jetbrains.kotlin.ir.types.isUInt
import org.jetbrains.kotlin.ir.types.isULong
import org.jetbrains.kotlin.ir.types.isUShort
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isTopLevel
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal data class RustHybridModuleArtifact(
    val bitcodeFile: File,
    val generatedFunctions: List<IrSimpleFunction>,
    val fallbackFunctions: List<IrSimpleFunction>,
    val abiExpectations: List<RustBoundaryAbiExpectation>,
    val needsRustEhPersonality: Boolean,
    val linkerFlags: List<String>,
)

private data class PreparedRustBitcode(
    val path: Path,
    val abiExpectations: List<RustBoundaryAbiExpectation>,
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
    val sourceFunctions = irModule.files.asSequence()
        .flatMap { file -> file.declarations.asSequence() }
        .filterIsInstance<IrSimpleFunction>()
        .filter { function -> function.getPackageFragment().moduleDescriptor == sourceModule }
        .toList()
    val sourceFunctionSet = sourceFunctions.toSet()
    val bodyCandidates = sourceFunctions.asSequence()
        .filter { function -> function.isRustBodyCandidate(generationState, sourceModule, entryPoint) }
        .toList()
    if (bodyCandidates.isEmpty()) return null

    val symbolNamer = RustLinkerSymbolNamer { function ->
        generationState.llvmDeclarations.forFunction(function).name
            ?: error("Kotlin/Native produced an unnamed LLVM function for ${function.name}")
    }
    val frameOverlayWords = (
            LLVMStoreSizeOfType(generationState.runtime.targetData, generationState.runtime.frameOverlayType) /
                    generationState.runtime.pointerSize
            ).toInt()
    val managedResults = bodyCandidates.asSequence()
        .map { function ->
            generateRustManagedReferenceFunction(function, symbolNamer, frameOverlayWords, sourceFunctionSet::contains)
        }
        .filterIsInstance<RustManagedReferenceCodegenResult.Generated>()
        .toList()
    val managedFieldResults = bodyCandidates.asSequence()
        .map { function ->
            generateRustManagedFieldFunction(
                function,
                symbolNamer,
                frameOverlayWords,
                sourceFunctionSet::contains,
            ) { field ->
                val declaration = generationState.llvmDeclarations.forField(field)
                LLVMOffsetOfElement(generationState.runtime.targetData, declaration.classBodyType, declaration.index)
            }
        }
        .filterIsInstance<RustManagedFieldCodegenResult.Generated>()
        .toList()
    val primitiveFieldReadResults = bodyCandidates.asSequence()
        .map { function ->
            generateRustPrimitiveFieldReadFunction(
                function,
                symbolNamer,
                frameOverlayWords,
                sourceFunctionSet::contains,
            ) { field ->
                val declaration = generationState.llvmDeclarations.forField(field)
                LLVMOffsetOfElement(generationState.runtime.targetData, declaration.classBodyType, declaration.index)
            }
        }
        .filterIsInstance<RustPrimitiveFieldReadCodegenResult.Generated>()
        .toList()
    val managedDeclarations = (managedResults.flatMap { it.generatedFunctions + it.fallbackFunctions } +
            managedFieldResults.flatMap { it.generatedFunctions + it.fallbackFunctions } +
            primitiveFieldReadResults.flatMap { it.generatedFunctions + it.fallbackFunctions }).toSet()
    val primitiveCandidates = bodyCandidates.filter { function ->
        function.hasPrimitiveRustAbi() && function !in managedDeclarations
    }
    val directInteropPlan = RustDirectInteropPlan.load(config.configuration.rustInteropBridgePlanPaths, config.target)
    val result = RustIrCodegen(
        symbolNamer,
        allowRustStandardIo = false,
        functionPrologue = "unsafe { Kotlin_mm_safePointFunctionPrologue(); }",
        directInteropCallResolver = directInteropPlan,
    ).generate(irModule, primitiveCandidates, moduleFunctionScope = primitiveCandidates)
    val directInteropDependencies = directInteropPlan.usedCargoDependencies()
    val candidateSet = primitiveCandidates.toSet()
    val generated = result.generatedFunctions.filter { it.declaration in candidateSet }
    if (generated.isEmpty() && managedResults.isEmpty() && managedFieldResults.isEmpty() && primitiveFieldReadResults.isEmpty()) return null
    if (generated.size != result.generatedFunctions.size) return null
    if (result.fallbackFunctions.any { it.declaration !in candidateSet }) return null
    if (result.generatedFunctions.any { it.declaration === entryPoint }) return null
    val generatedFunctions = (generated.map { it.declaration } + managedResults.flatMap { it.generatedFunctions } +
            managedFieldResults.flatMap { it.generatedFunctions } +
            primitiveFieldReadResults.flatMap { it.generatedFunctions }).distinct()
    val fallbackFunctions = (
            result.fallbackFunctions.map { it.declaration } + managedResults.flatMap { it.fallbackFunctions } +
                    managedFieldResults.flatMap { it.fallbackFunctions } +
                    primitiveFieldReadResults.flatMap { it.fallbackFunctions }
            ).distinct()

    val outputFile = File(generationState.outputFiles.mainFileName).absoluteFile
    val workspace = outputFile.parentFile
        .resolve(".kotlin-rust")
        .resolve(outputFile.nameWithoutExtension)
    val successMarker = workspace.resolve("rust-bitcode-preflight-ok")
    val failureMarker = workspace.resolve("rust-bitcode-preflight-failure")
    return try {
        Files.deleteIfExists(successMarker.toPath())
        Files.deleteIfExists(failureMarker.toPath())
        val artifact = RustBitcodeLibraryCompiler().compile(
            packageName = "kotlin_native_rust_module",
            targetTriple = targetTriple,
            renderedLibraryRs = buildString {
                if (directInteropDependencies.isEmpty()) appendLine("#![no_std]")
                append(result.source)
                appendLine()
                if (managedResults.isEmpty() && managedFieldResults.isEmpty() && primitiveFieldReadResults.isEmpty()) {
                    appendLine("extern \"C\" {")
                    appendLine("    fn Kotlin_mm_safePointFunctionPrologue();")
                    appendLine("}")
                } else {
                    appendLine(rustManagedReferenceRuntimePrelude())
                    for (managedResult in managedResults) {
                        appendLine()
                        append(managedResult.source)
                    }
                    for (managedFieldResult in managedFieldResults) {
                        appendLine()
                        append(managedFieldResult.source)
                    }
                    for (primitiveFieldReadResult in primitiveFieldReadResults) {
                        appendLine()
                        append(primitiveFieldReadResult.source)
                    }
                }
            },
            outputDirectory = workspace.toPath(),
            release = config.optimizationsEnabled,
            dependencies = directInteropDependencies,
        )
        val generatedSymbols = generatedFunctions.associateWith(symbolNamer::linkerName)
        val fallbackSymbols = fallbackFunctions.associateWith(symbolNamer::linkerName)
        val preparedBitcode = prepareRustBitcode(
            generationState,
            artifact.llvmBitcode,
            generatedSymbols,
            fallbackSymbols,
            failureMarker.toPath(),
        ) ?: return null
        Files.write(successMarker.toPath(), (preparedBitcode.path.fileName.toString() + "\n").toByteArray())
        RustHybridModuleArtifact(
            preparedBitcode.path.toFile(),
            generatedFunctions,
            fallbackFunctions,
            preparedBitcode.abiExpectations,
            needsRustEhPersonality = managedResults.isNotEmpty() || managedFieldResults.isNotEmpty() ||
                    primitiveFieldReadResults.isNotEmpty(),
            linkerFlags = listOfNotNull(artifact.staticLibrary?.toAbsolutePath()?.toString()) + artifact.nativeStaticLibraries,
        )
    } catch (failure: Exception) {
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        null
    }
}

private fun IrSimpleFunction.isRustBodyCandidate(
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
            !hasAnnotation(RuntimeNames.exportedBridge) &&
            !hasAnnotation(RuntimeNames.exportForCppRuntime) &&
            getPackageFragment().moduleDescriptor == sourceModule &&
            generationState.llvmModuleSpecification.containsDeclaration(this)

private fun IrSimpleFunction.hasPrimitiveRustAbi(): Boolean =
    parameters.all { it.kind == IrParameterKind.Regular && it.type.isPrimitiveRustParameterType() } &&
            returnType.isPrimitiveRustReturnType()

private fun IrType.isPrimitiveRustParameterType(): Boolean =
    !isNullable() && (
            isBoolean() || isByte() || isShort() || isChar() || isInt() || isLong() || isFloat() || isDouble() ||
                    isUByte() || isUShort() || isUInt() || isULong()
            )

private fun IrType.isPrimitiveRustReturnType(): Boolean =
    !isNullable() && (
            isBoolean() || isByte() || isShort() || isChar() || isInt() || isLong() || isFloat() || isDouble() ||
                    isUByte() || isUShort() || isUInt() || isULong() || isUnit()
            )

private fun prepareRustBitcode(
    generationState: NativeGenerationState,
    bitcodePath: Path,
    generatedSymbols: Map<IrSimpleFunction, String>,
    fallbackSymbols: Map<IrSimpleFunction, String>,
    failureMarker: Path,
): PreparedRustBitcode? {
    var rustModule: LLVMModuleRef? = null
    var rustModuleConsumed = false
    var clone: LLVMModuleRef? = null
    val normalizedBitcode = bitcodePath.resolveSibling("rust-boundary-normalized.bc")
    var prepared = false
    return try {
        fun fail(message: String): Nothing = throw RustBitcodePreparationException(message)

        Files.deleteIfExists(normalizedBitcode)
        rustModule = parseBitcodeFile(
            generationState,
            generationState.diagnosticReporter,
            generationState.llvmContext,
            bitcodePath.toString(),
        )
        if (moduleTarget(rustModule) != moduleTarget(generationState.llvm.module)) fail("Rust and Kotlin modules have different target triples")
        if (moduleDataLayout(rustModule) != moduleDataLayout(generationState.llvm.module)) fail("Rust and Kotlin modules have different data layouts")
        verifyModule(rustModule, "generated Rust bitcode")

        val linkedBoundaryFunctions = LinkedHashMap<IrSimpleFunction, String>(generatedSymbols.size + fallbackSymbols.size)
        val abiExpectations = ArrayList<RustBoundaryAbiExpectation>(generatedSymbols.size + fallbackSymbols.size)
        for (entry in generatedSymbols.entries) {
            val kotlinFunction = generationState.llvmDeclarations.forFunction(entry.key)
            val rustType = rustBoundaryFunctionType(rustModule, entry.value)
                ?: fail("generated Rust boundary '${entry.value}' is missing")
            if (llvmtype2string(kotlinFunction.functionType) != llvmtype2string(rustType)) {
                fail("generated Rust boundary '${entry.value}' has a different function type")
            }
            abiExpectations += RustBoundaryAbiExpectation.capture(entry.value, kotlinFunction.asCallback())
                ?: fail("Kotlin boundary '${entry.value}' has unsupported extension attributes")
            linkedBoundaryFunctions[entry.key] = entry.value
        }
        for (entry in fallbackSymbols.entries) {
            val rustType = rustBoundaryFunctionType(rustModule, entry.value) ?: continue
            val kotlinFunction = generationState.llvmDeclarations.forFunction(entry.key)
            if (llvmtype2string(kotlinFunction.functionType) != llvmtype2string(rustType)) {
                fail("Rust fallback boundary '${entry.value}' has a different function type")
            }
            abiExpectations += RustBoundaryAbiExpectation.capture(entry.value, kotlinFunction.asCallback())
                ?: fail("Kotlin fallback boundary '${entry.value}' has unsupported extension attributes")
            linkedBoundaryFunctions[entry.key] = entry.value
        }
        val normalization = normalizeRustBoundaryAbi(rustModule, abiExpectations)
        if (!normalization.isSuccess) fail(normalization.failure ?: "Rust boundary ABI normalization failed")
        verifyModule(rustModule, "ABI-normalized generated Rust bitcode")
        if (!writeRustBoundaryBitcodeAtomically(rustModule, normalizedBitcode)) fail("could not write ABI-normalized Rust bitcode")

        clone = LLVMCloneModule(generationState.llvm.module)
            ?: error("Could not clone the Kotlin/Native LLVM module for Rust preflight")
        linkedBoundaryFunctions.values.forEach { symbolName ->
            val clonedFunction = LLVMGetNamedFunction(clone, symbolName)
                ?: fail("Kotlin preflight module is missing boundary '$symbolName'")
            LLVMSetLinkage(clonedFunction, LLVMLinkage.LLVMExternalLinkage)
        }
        rustModuleConsumed = true
        if (llvmLinkModules2(generationState, clone, rustModule) != 0) fail("LLVM rejected the normalized Rust module")
        val linkedVerification = verifyRustBoundaryAbi(clone, abiExpectations)
        if (!linkedVerification.isSuccess) {
            fail(linkedVerification.failure ?: "linked Rust boundary ABI verification failed")
        }
        prepared = true
        Files.deleteIfExists(failureMarker)
        PreparedRustBitcode(normalizedBitcode, abiExpectations.toList())
    } catch (failure: Throwable) {
        if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
        val message = failure.message ?: failure::class.java.name
        Files.write(failureMarker, (message + "\n").toByteArray(StandardCharsets.UTF_8))
        null
    } finally {
        if (!rustModuleConsumed) rustModule?.let { LLVMDisposeModule(it) }
        clone?.let { LLVMDisposeModule(it) }
        if (!prepared) Files.deleteIfExists(normalizedBitcode)
    }
}

private class RustBitcodePreparationException(message: String) : Exception(message)

private fun moduleTarget(module: LLVMModuleRef): String = LLVMGetTarget(module)?.toKString().orEmpty()

private fun moduleDataLayout(module: LLVMModuleRef): String = LLVMGetDataLayoutStr(module)?.toKString().orEmpty()

private fun rustBoundaryFunctionType(module: LLVMModuleRef, symbolName: String): LLVMTypeRef? {
    LLVMGetNamedFunction(module, symbolName)?.let { return getGlobalFunctionType(it) }
    val alias = getGlobalAliases(module).firstOrNull { LLVMGetValueName(it)?.toKString() == symbolName } ?: return null
    return LLVMAliasGetAliasee(alias)?.let(::getGlobalFunctionType)
}
