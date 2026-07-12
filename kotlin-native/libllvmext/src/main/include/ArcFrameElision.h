/*
 * Copyright 2010-2026 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#ifndef LIBLLVMEXT_ARC_FRAME_ELISION_H
#define LIBLLVMEXT_ARC_FRAME_ELISION_H

#include <llvm-c/Core.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Temporarily prevents the three ARC frame ABI wrappers from being inlined by
 * the module optimization pipeline. The matching finalize or restore-only
 * entry point restores every attribute added by this function.
 */
int LLVMKotlinPrepareArcFrameElision(LLVMModuleRef module);

/**
 * Preserves the already-optimized exact wrapper ABI through LTO. Returns zero
 * without transforming the module when the preservation anchor cannot be
 * installed exactly.
 */
int LLVMKotlinSealArcFrameElisionForLTO(LLVMModuleRef module);

/**
 * Idempotently restores only attributes installed by the preparation entry
 * point. It never transforms frame IR and is safe on compiler-failure paths.
 */
void LLVMKotlinRestoreArcFrameElision(LLVMModuleRef module);

/**
 * Removes conservatively proven empty, non-unwinding ARC frames and restores
 * normal inlining for every retained ARC frame wrapper.
 *
 * Returns the number of removed frame pairs.
 */
int LLVMKotlinRemoveEmptyArcFrames(LLVMModuleRef module);

/** Returns the number of direct exact-wrapper calls left after finalization. */
int LLVMKotlinCountDirectArcFrameWrapperCalls(LLVMModuleRef module);

#ifdef __cplusplus
}
#endif

#endif // LIBLLVMEXT_ARC_FRAME_ELISION_H
