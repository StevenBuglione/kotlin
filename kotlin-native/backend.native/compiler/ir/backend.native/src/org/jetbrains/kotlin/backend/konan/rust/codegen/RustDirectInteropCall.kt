/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust.codegen

import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

internal enum class RustDirectInteropPanicPolicy {
    ABORT,
}

internal data class RustDirectInteropCall(
    val rustPath: String,
    val panicPolicy: RustDirectInteropPanicPolicy = RustDirectInteropPanicPolicy.ABORT,
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
