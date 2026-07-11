/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust.codegen

import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

internal sealed interface RustDirectInteropBoundaryPolicy {
    /** Evaluate Kotlin arguments first, then catch a Rust panic from the crate call and abort. */
    data object CatchRustPanicAndAbort : RustDirectInteropBoundaryPolicy

    /** Convert selected Rust `Result` errors and/or panics to `kotlin.RuntimeException`. */
    data class ThrowKotlinRuntimeException(
        val onResultError: Boolean,
        val onRustPanic: Boolean,
    ) : RustDirectInteropBoundaryPolicy {
        init {
            require(onResultError || onRustPanic) { "At least one Rust failure kind must be converted" }
        }
    }

    /** The bridge plan requests a conversion that this backend cannot represent safely yet. */
    data class Unsupported(val reason: String) : RustDirectInteropBoundaryPolicy {
        init {
            require(reason.isNotBlank()) { "An unsupported Rust boundary must have a diagnostic reason" }
        }
    }
}

internal data class RustDirectInteropCall(
    val rustPath: String,
    val boundaryPolicy: RustDirectInteropBoundaryPolicy = RustDirectInteropBoundaryPolicy.CatchRustPanicAndAbort,
) {
    init {
        require(RUST_ITEM_PATH.matches(rustPath)) { "Invalid direct Rust item path: $rustPath" }
    }

    private companion object {
        val RUST_ITEM_PATH = Regex("(?:::)?[A-Za-z_][A-Za-z0-9_]*(?:::[A-Za-z_][A-Za-z0-9_]*)*")
    }
}

internal fun interface RustDirectInteropCallResolver {
    fun resolve(callee: IrSimpleFunction): RustDirectInteropCall?

    companion object {
        val NONE: RustDirectInteropCallResolver = RustDirectInteropCallResolver { null }
    }
}
