/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

#ifndef LIBLLVMEXT_ARC_SELECTIVE_INLINING_H
#define LIBLLVMEXT_ARC_SELECTIVE_INLINING_H

#include <llvm-c/Core.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct LLVMKotlinArcSelectiveInlineStats {
  int tagged;
  int inlined;
  int rejected;
  int missingBody;
  int budgetSkipped;
  int inlineFailures;
} LLVMKotlinArcSelectiveInlineStats;

LLVMModuleRef LLVMKotlinCreateArcSelectiveInlineCompanion(
    LLVMModuleRef source, const char* originalName, const char* cloneName);

int LLVMKotlinCountArcTaggedCalls(LLVMModuleRef module, const char* metadataName);

LLVMKotlinArcSelectiveInlineStats LLVMKotlinInlineArcTaggedCalls(
    LLVMModuleRef module, const char* metadataName, const char* originalName,
    const char* cloneName, int maxInstructionsPerCallee,
    int maxInlineInstructionsPerCaller, int maxInlineInstructionsPerModule);

#ifdef __cplusplus
}
#endif

#endif
