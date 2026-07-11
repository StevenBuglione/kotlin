/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import kotlin.test.Test
import kotlin.test.assertEquals

class RustExceptionInteropTest {
    @Test
    fun runtimeTrampolineUsesTheUnwindingCAbiAndBorrowedUtf8Range() {
        assertEquals(
            """
                extern "C-unwind" {
                    fn Kotlin_RustInterop_ThrowRuntimeException(data: *const u8, size: usize) -> !;
                }
            """.trimIndent(),
            rustExceptionInteropRuntimePrelude(),
        )
    }
}
