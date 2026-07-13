/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

#include "Memory.h"
#include "MemorySharedRefs.hpp"
#include "Types.h"

#if !defined(KONAN_ARC_MEMORY_MANAGER) || KONAN_ARC_MEMORY_MANAGER != 1
#error "The ARC pinned-interop helper must be compiled only into the ARC runtime"
#endif

extern "C" {

KNativePtr Kotlin_Arrays_getByteArrayAddressOfElement(KRef thiz, KInt index);

// Internal ARC ABI for a compiler-proven dependent interior pointer. The live stable holder is
// the lifetime base; borrowing its reference avoids materializing Pinned.get() in an owning
// Kotlin return slot. Delegate to the canonical array entry point so evaluation, bounds checks,
// and exception behavior remain identical to Pinned<ByteArray>.addressOf.
__attribute__((visibility("hidden")))
KNativePtr Kotlin_Interop_getPinnedByteArrayAddressArc(KNativePtr pointer, KInt index) {
    kotlin::AssertThreadState(kotlin::ThreadState::kRunnable);
    KRefSharedHolder* holder = reinterpret_cast<KRefSharedHolder*>(pointer);
    KRef array = holder->ref<ErrorPolicy::kThrow>();
    return Kotlin_Arrays_getByteArrayAddressOfElement(array, index);
}

} // extern "C"
