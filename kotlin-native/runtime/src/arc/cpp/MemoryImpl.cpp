/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

#include "Memory.h"
#include "../../legacymm/cpp/MemoryPrivate.hpp"

#if !defined(KONAN_ARC_MEMORY_MANAGER) || KONAN_ARC_MEMORY_MANAGER != 1
#error "The ARC runtime wrapper must be compiled with KONAN_ARC_MEMORY_MANAGER=1"
#endif

// The first ARC runtime slice reuses the legacy relaxed-model eager retain/release operations.
// ARC-specific frame hooks release eagerly owned local slots on normal and exceptional exits.
// Later slices can replace the remaining forwarding hooks without changing the public runtime
// ABI or the arc.bc artifact boundary.
extern "C" {

const MemoryModel CurrentMemoryModel = MemoryModel::kArc;

RUNTIME_NOTHROW OBJ_GETTER(AllocInstance, const TypeInfo* typeInfo) {
    RETURN_RESULT_OF(AllocInstanceRelaxed, typeInfo);
}

OBJ_GETTER(AllocArrayInstance, const TypeInfo* typeInfo, int32_t elements) {
    RETURN_RESULT_OF(AllocArrayInstanceRelaxed, typeInfo, elements);
}

RUNTIME_NOTHROW void ReleaseHeapRef(const ObjHeader* object) {
    ReleaseHeapRefRelaxed(object);
}

RUNTIME_NOTHROW void ReleaseHeapRefNoCollect(const ObjHeader* object) {
    ReleaseHeapRefNoCollectRelaxed(object);
}

RUNTIME_NOTHROW void SetStackRef(ObjHeader** location, const ObjHeader* object) {
    SetStackRefRelaxed(location, object);
}

RUNTIME_NOTHROW void SetHeapRef(ObjHeader** location, const ObjHeader* object) {
    SetHeapRefRelaxed(location, object);
}

RUNTIME_NOTHROW void ZeroStackRef(ObjHeader** location) {
    ZeroStackRefRelaxed(location);
}

RUNTIME_NOTHROW void UpdateHeapRef(ObjHeader** location, const ObjHeader* object) {
    UpdateHeapRefRelaxed(location, object);
}

RUNTIME_NOTHROW void UpdateReturnRef(ObjHeader** returnSlot, const ObjHeader* object) {
    UpdateReturnRefRelaxed(returnSlot, object);
}

RUNTIME_NOTHROW void EnterFrame(ObjHeader** start, int parameters, int count) {
    EnterFrameArc(start, parameters, count);
}

RUNTIME_NOTHROW void LeaveFrame(ObjHeader** start, int parameters, int count) {
    LeaveFrameArc(start, parameters, count);
}

RUNTIME_NOTHROW void SetCurrentFrame(ObjHeader** start) {
    SetCurrentFrameArc(start);
}

RUNTIME_NOTHROW void UpdateStackRef(ObjHeader** location, const ObjHeader* object) {
    UpdateStackRefRelaxed(location, object);
}

RUNTIME_NOTHROW void UpdateHeapRefsInsideOneArray(const ArrayHeader* array, int fromIndex, int toIndex, int count) {
    UpdateHeapRefsInsideOneArrayRelaxed(array, fromIndex, toIndex, count);
}

} // extern "C"
