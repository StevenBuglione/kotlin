/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

internal enum class NativeCodegenMode(val cliArgument: String) {
    LLVM("llvm"),
    RUST_HYBRID("rust-hybrid"),
    RUST_STRICT("rust-strict"),
    ;

    companion object {
        fun parse(cliArgument: String): NativeCodegenMode? = entries.singleOrNull { it.cliArgument == cliArgument }
    }
}

private val NATIVE_CODEGEN_MODE = CompilerConfigurationKey.create<NativeCodegenMode>("NATIVE_CODEGEN_MODE")

internal var CompilerConfiguration.nativeCodegenMode: NativeCodegenMode
    get() = get(NATIVE_CODEGEN_MODE, NativeCodegenMode.LLVM)
    set(value) = put(NATIVE_CODEGEN_MODE, value)
