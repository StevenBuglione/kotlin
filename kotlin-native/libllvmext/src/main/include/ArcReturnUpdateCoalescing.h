/*
 * Copyright 2010-2026 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#ifndef LIBLLVMEXT_ARC_RETURN_UPDATE_COALESCING_H
#define LIBLLVMEXT_ARC_RETURN_UPDATE_COALESCING_H

#include <llvm-c/Core.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Removes only literally adjacent direct calls to
 * UpdateReturnRefRelaxed(slot, value) with pointer-identical SSA operands.
 * Returns the number of erased calls.
 */
int LLVMKotlinCoalesceAdjacentArcReturnUpdates(LLVMModuleRef module);

#ifdef __cplusplus
}
#endif

#endif // LIBLLVMEXT_ARC_RETURN_UPDATE_COALESCING_H
