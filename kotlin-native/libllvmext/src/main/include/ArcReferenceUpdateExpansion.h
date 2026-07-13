/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

#ifndef LIBLLVMEXT_ARC_REFERENCE_UPDATE_EXPANSION_H
#define LIBLLVMEXT_ARC_REFERENCE_UPDATE_EXPANSION_H

#include <llvm-c/Core.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct LLVMKotlinArcReferenceUpdateExpansionStats {
  int candidates;
  int expanded;
  int rejected;
  int missingRuntime;
} LLVMKotlinArcReferenceUpdateExpansionStats;

/**
 * Replaces direct calls to the relaxed stack, return, and heap update wrappers
 * with always-inline assignment shells whose retain and release operations stay
 * in out-of-line ARC runtime leaves. The operation is fail-closed: malformed or
 * incomplete runtime shapes leave the module unchanged.
 */
LLVMKotlinArcReferenceUpdateExpansionStats LLVMKotlinExpandArcReferenceUpdates(
    LLVMModuleRef module);

#ifdef __cplusplus
}
#endif

#endif
