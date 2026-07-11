/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust.codegen

import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

/** A stable classification of constructs that the initial Rust backend cannot lower yet. */
internal enum class RustUnsupportedCode {
    INVALID_ENTRY_POINT,
    NON_TOP_LEVEL_FUNCTION,
    MISSING_BODY,
    GENERIC_FUNCTION,
    SUSPEND_FUNCTION,
    UNSUPPORTED_PARAMETER,
    UNSUPPORTED_TYPE,
    UNSUPPORTED_DECLARATION,
    UNSUPPORTED_EXPRESSION,
    UNSUPPORTED_CALL,
    UNSUPPORTED_DIRECT_INTEROP_BOUNDARY,
    UNSUPPORTED_RETURN_TARGET,
    UNSUPPORTED_LOOP_TARGET,
    MALFORMED_IR,
}

internal data class RustSourceLocation(
    val path: String,
    /** One-based line, or null when the IR has no source offset. */
    val line: Int?,
    /** One-based column, or null when the IR has no source offset. */
    val column: Int?,
    val startOffset: Int,
    val endOffset: Int,
)

internal data class RustUnsupportedDiagnostic(
    val code: RustUnsupportedCode,
    val message: String,
    val functionName: String,
    val elementKind: String,
    val location: RustSourceLocation,
)

/**
 * Connects an IR declaration to both names present in generated Rust.
 *
 * [rustName] is private to the generated crate. [linkerName] is the symbol that is exported for a
 * generated body, or imported when hybrid mode falls back to the LLVM body.
 */
internal data class RustGeneratedFunction(
    val declaration: IrSimpleFunction,
    val rustName: String,
    val linkerName: String,
)

internal data class RustCodegenResult(
    val source: String,
    val generatedFunctions: List<RustGeneratedFunction>,
    val fallbackFunctions: List<RustGeneratedFunction>,
    val diagnostics: List<RustUnsupportedDiagnostic>,
) {
    val isFullySupported: Boolean
        get() = diagnostics.isEmpty()
}

/** Supplies the final Kotlin/Native ABI symbol once the driver has selected its ABI policy. */
internal fun interface RustLinkerSymbolNamer {
    fun linkerName(function: IrSimpleFunction): String
}
