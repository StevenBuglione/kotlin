/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.optimizations

import llvm.LLVMKotlinCoalesceAdjacentArcReturnUpdates
import llvm.LLVMModuleRef

internal fun coalesceAdjacentArcReturnUpdates(module: LLVMModuleRef): Int =
        LLVMKotlinCoalesceAdjacentArcReturnUpdates(module)
