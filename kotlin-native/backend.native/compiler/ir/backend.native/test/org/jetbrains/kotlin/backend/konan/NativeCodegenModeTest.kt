/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.config.CompilerConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeCodegenModeTest {
    @Test
    fun parsesCliValues() {
        assertEquals(NativeCodegenMode.LLVM, NativeCodegenMode.parse("llvm"))
        assertEquals(NativeCodegenMode.RUST_HYBRID, NativeCodegenMode.parse("rust-hybrid"))
        assertEquals(NativeCodegenMode.RUST_STRICT, NativeCodegenMode.parse("rust-strict"))
    }

    @Test
    fun rejectsUnknownCliValue() {
        assertNull(NativeCodegenMode.parse("rust"))
    }

    @Test
    fun defaultsToLlvm() {
        assertEquals(NativeCodegenMode.LLVM, CompilerConfiguration.create().nativeCodegenMode)
    }
}
