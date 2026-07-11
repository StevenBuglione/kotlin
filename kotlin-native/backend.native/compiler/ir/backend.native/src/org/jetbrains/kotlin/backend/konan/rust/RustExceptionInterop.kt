/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

internal const val RUST_INTEROP_THROW_RUNTIME_EXCEPTION = "Kotlin_RustInterop_ThrowRuntimeException"

/** Runtime declaration used by generated Rust that translates a borrowed UTF-8 error buffer to a Kotlin exception. */
internal fun rustExceptionInteropRuntimePrelude(): String = """
    extern "C-unwind" {
        fn $RUST_INTEROP_THROW_RUNTIME_EXCEPTION(data: *const u8, size: usize) -> !;
    }
""".trimIndent()
