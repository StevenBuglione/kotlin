/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

private class Holder(var value: Any?)

private fun replace(holder: Holder, value: Any?): Any? {
    var local = holder.value
    holder.value = value
    local = value
    return local
}

fun main() {
    val holder = Holder(Any())
    check(replace(holder, null) == null)
}

// Public runtime forwarders are guarded, while every changed path delegates to
// its exact original barrier rather than reproducing ownership semantics.
// EXPAND-LABEL: define void @UpdateHeapRef(
// EXPAND: tail call void @__konan_arc_self_guard_update_heap_ref_v1(
// EXPAND: ret void
// EXPAND-LABEL: define void @UpdateReturnRef(
// EXPAND: tail call void @__konan_arc_self_guard_update_return_ref_v1(
// EXPAND: ret void
// EXPAND-LABEL: define void @UpdateStackRef(
// EXPAND: tail call void @__konan_arc_self_guard_update_stack_ref_v1(
// EXPAND: ret void

// Authenticate the exact standard, uninstrumented linked runtime contract.
// EXPAND-LABEL: define i32 @ArcReferenceUpdateExpansionContractV1()
// EXPAND: ret i32 1262572033

// EXPAND-LABEL: define internal void @__konan_arc_self_guard_update_stack_ref_v1(
// EXPAND: entry:
// EXPAND-NEXT: %old = load %struct.ObjHeader*, %struct.ObjHeader** %slot{{.*}}
// EXPAND-NEXT: %same = icmp eq %struct.ObjHeader* %old, %value
// EXPAND-NEXT: br i1 %same, label %exit, label %changed
// EXPAND: changed:
// EXPAND-NEXT: call void @UpdateStackRefRelaxed(%struct.ObjHeader** %slot, %struct.ObjHeader* %value)
// EXPAND-NEXT: br label %exit
// EXPAND: exit:
// EXPAND-NEXT: ret void

// EXPAND-LABEL: define internal void @__konan_arc_self_guard_update_return_ref_v1(
// EXPAND: changed:
// EXPAND-NEXT: call void @UpdateReturnRefRelaxed(%struct.ObjHeader** %slot, %struct.ObjHeader* %value)
// EXPAND-NEXT: br label %exit

// EXPAND-LABEL: define internal void @__konan_arc_self_guard_update_heap_ref_v1(
// EXPAND: changed:
// EXPAND-NEXT: call void @UpdateHeapRefRelaxed(%struct.ObjHeader** %slot, %struct.ObjHeader* %value)
// EXPAND-NEXT: br label %exit

// After LTO every shell is inlined away and changed paths still call the exact
// authoritative runtime barriers.
// FINAL-NOT: define {{.*}}@__konan_arc_self_guard_update_{{stack|return|heap}}_ref_v1
// FINAL: call{{.*}}void @Update{{Stack|Return|Heap}}RefRelaxed(
// FINAL-NOT: define {{.*}}@__konan_arc_self_guard_update_{{stack|return|heap}}_ref_v1

// Debug ARC and runtime-assertion builds retain the authoritative wrappers.
// DEBUG-NOT: __konan_arc_self_guard_update_
// DEBUG: {{call|tail call}}{{.*}}void @Update{{Stack|Return|Heap}}RefRelaxed(
// ASSERTS-NOT: __konan_arc_self_guard_update_
// ASSERTS: {{call|tail call}}{{.*}}void @Update{{Stack|Return|Heap}}RefRelaxed(

// The ARC-only contract and generated shells are absent from strict binaries.
// STRICT-NOT: @ArcReferenceUpdateExpansionContractV1
// STRICT-NOT: __konan_arc_self_guard_update_
// STRICT: {{call|tail call}}{{.*}}void @Update{{Stack|Return|Heap}}RefStrict(

// Diagnostic ARC uses an instrumented update barrier, so it exposes neither
// the standard-runtime contract marker nor any generated shell.
// DIAGNOSTICS-NOT: @ArcReferenceUpdateExpansionContractV1
// DIAGNOSTICS-NOT: __konan_arc_self_guard_update_
// DIAGNOSTICS: {{call|tail call}}{{.*}}void @Update{{Stack|Return|Heap}}RefRelaxed(
// DIAGNOSTICS-NOT: @ArcReferenceUpdateExpansionContractV1
// DIAGNOSTICS-NOT: __konan_arc_self_guard_update_
