/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.driver.phases

import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.NativeCodegenMode
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.NativeSecondStageCompilationConfig
import org.jetbrains.kotlin.backend.konan.nativeCodegenMode
import org.jetbrains.kotlin.backend.konan.reportCompilationError
import org.jetbrains.kotlin.backend.konan.lower.isEagerStaticInitializer
import org.jetbrains.kotlin.backend.konan.lower.isLazyStaticInitializer
import org.jetbrains.kotlin.backend.konan.rust.RustProgramCompiler
import org.jetbrains.kotlin.backend.konan.rust.RustToolExecutionException
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustCodegenResult
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustIrCodegen
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustUnsupportedDiagnostic
import org.jetbrains.kotlin.backend.konan.serialization.CacheDeserializationStrategy
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.config.kotlinSourceRoots
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.konan.config.NativeConfigurationKeys
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Tries the first, deliberately self-contained Rust PROGRAM slice.
 *
 * Hybrid mode falls back atomically to the existing LLVM module when the reachable IR is not yet
 * supported. Strict mode reports the first source location and the complete unsupported-IR set.
 * Returning false means that the caller must execute the unmodified LLVM pipeline.
 */
internal fun tryCompileRustProgramPrototype(
    config: NativeSecondStageCompilationConfig,
    backendContext: Context,
    generationState: NativeGenerationState,
    irModule: IrModuleFragment,
    cacheDeserializationStrategy: CacheDeserializationStrategy?,
): Boolean {
    val mode = config.configuration.nativeCodegenMode
    // Hybrid mode now rejoins the normal Kotlin/Native bitcode and linker pipeline. Keep this
    // self-contained executable path only as the strict-mode bootstrap until its entry point and
    // runtime calls use the Native ABI as well.
    if (mode != NativeCodegenMode.RUST_STRICT) return false

    fun unsupportedConfiguration(message: String): Boolean {
        if (mode == NativeCodegenMode.RUST_HYBRID) return false
        backendContext.reportCompilationError("Rust strict backend: $message")
    }

    if (config.produce != CompilerOutputKind.PROGRAM) {
        return unsupportedConfiguration("only PROGRAM output is supported by the initial vertical slice")
    }
    val rustTargetTriple = when (config.target) {
        KonanTarget.MINGW_X64 -> "x86_64-pc-windows-gnu"
        KonanTarget.LINUX_X64 -> "x86_64-unknown-linux-gnu"
        else -> return unsupportedConfiguration("only mingwX64 and linuxX64 are supported by the initial vertical slice")
    }
    if (cacheDeserializationStrategy != null) {
        return unsupportedConfiguration("cache fragments are not supported by the initial vertical slice")
    }
    if (config.nativeLibraries.isNotEmpty() || config.includeBinaries.isNotEmpty()) {
        return unsupportedConfiguration("native libraries and included binaries require the existing linker pipeline")
    }
    if (config.configuration.getNotNull(NativeConfigurationKeys.LINKER_ARGS).isNotEmpty()) {
        return unsupportedConfiguration("custom linker arguments require the existing linker pipeline")
    }
    if (config.writeSerializedDependencies != null) {
        return unsupportedConfiguration("serialized backend dependency output is not supported by the initial vertical slice")
    }
    if (config.debug || config.sanitizer != null) {
        return unsupportedConfiguration("debug information and sanitizers are not supported by the initial vertical slice")
    }

    val sourcePaths = config.configuration.kotlinSourceRoots
        .mapTo(hashSetOf()) { Paths.get(it.path).toAbsolutePath().normalize() }
    val staticInitializer = irModule.files.asSequence()
        .flatMap { it.declarations.asSequence() }
        .filterIsInstance<IrSimpleFunction>()
        .firstOrNull {
            val ownerFile = it.parent as? IrFile
            ownerFile != null &&
                    Paths.get(ownerFile.fileEntry.name).toAbsolutePath().normalize() in sourcePaths &&
                    generationState.llvmModuleSpecification.containsDeclaration(it) &&
                    (it.isLazyStaticInitializer || it.isEagerStaticInitializer)
        }
    if (staticInitializer != null) {
        return unsupportedConfiguration("global and thread-local initializers require Kotlin/Native runtime startup")
    }

    val kotlinMain = backendContext.symbols.entryPoint?.owner
        ?: return unsupportedConfiguration("the module has no Kotlin entry point")
    val codegenResult = RustIrCodegen().generate(irModule, listOf(kotlinMain))
    if (!codegenResult.isFullySupported) {
        if (mode == NativeCodegenMode.RUST_HYBRID) return false
        reportUnsupportedRustIr(backendContext, codegenResult)
    }

    val generatedMain = codegenResult.generatedFunctions.singleOrNull { it.declaration === kotlinMain }
        ?: backendContext.reportCompilationError("Rust strict backend did not generate the Kotlin entry point")
    val rustSource = codegenResult.source + renderRustMain(generatedMain.rustName, kotlinMain)
    val outputFile = File(generationState.outputFiles.mainFileName).absoluteFile
    val workspace = outputFile.parentFile.resolve(".kotlin-rust").resolve(outputFile.nameWithoutExtension)

    try {
        val artifact = RustProgramCompiler().compile(
            packageName = "kotlin_native_rust_program",
            targetTriple = rustTargetTriple,
            renderedMainRs = rustSource,
            outputDirectory = workspace,
            release = config.optimizationsEnabled,
        )
        outputFile.parentFile.mkdirs()
        Files.copy(
            artifact.executable,
            outputFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
        )
    } catch (failure: Exception) {
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        if (mode == NativeCodegenMode.RUST_HYBRID) return false
        val message = if (failure is RustToolExecutionException) {
            failure.message
        } else {
            "Rust compiler integration failed: ${failure.message ?: failure::class.simpleName}"
        }
        backendContext.reportCompilationError(message ?: "Rust compiler integration failed")
    }
    return true
}

private fun renderRustMain(generatedName: String, kotlinMain: IrSimpleFunction): String {
    if (kotlinMain.parameters.isNotEmpty()) {
        error("A Kotlin main with parameters must have been rejected by Rust IR signature validation")
    }
    return "\nfn main() {\n    $generatedName();\n}\n"
}

private fun reportUnsupportedRustIr(context: Context, result: RustCodegenResult): Nothing {
    val diagnostics = result.diagnostics.sortedWith(
        compareBy<RustUnsupportedDiagnostic>({ it.location.path }, { it.location.line }, { it.location.column }, { it.code.name })
    )
    val locatedDiagnostic = diagnostics.firstOrNull {
        it.location.path.isNotBlank() && it.location.line != null && it.location.column != null
    }
    val message = buildString {
        appendLine("Rust strict backend cannot lower the reachable Kotlin program:")
        diagnostics.forEach { diagnostic ->
            append(" - [").append(diagnostic.code).append("] ")
            append(diagnostic.functionName).append(": ").append(diagnostic.message)
            if (diagnostic.elementKind.isNotBlank()) append(" (").append(diagnostic.elementKind).append(')')
            appendLine()
        }
    }.trimEnd()
    val location = if (locatedDiagnostic != null) {
        CompilerMessageLocation.create(
            locatedDiagnostic.location.path,
            locatedDiagnostic.location.line!!,
            locatedDiagnostic.location.column!!,
            null,
        )
    } else {
        null
    }
    context.reportCompilationError(message, location)
}
