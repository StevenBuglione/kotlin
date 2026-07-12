/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

private class FramePayload(val value: Int)

// Deliberately contains a cold recursive edge and an impossible allocating `else` branch. Before
// LLVM optimization this requires one owning result slot. SCCP proves the branch unreachable, and
// the inlined hot body is then an empty ARC frame.
private fun emptyFrame(first: FramePayload, marker: Int, second: FramePayload): Long {
    if (marker == Int.MIN_VALUE) return emptyFrame(second, marker + 1, first)
    val left = first.value.toLong()
    val right = second.value.toLong()
    val position = marker.toLong()
    return when (marker and 3) {
        0 -> left + position + right
        1 -> left * 2 + position + right
        2 -> left * 3 + position + right
        3 -> left * 4 + position + right
        else -> error("unreachable frame selector")
    }
}

private fun ownedFrame(value: Int): Int {
    val payload = FramePayload(value)
    // Keep the allocation and its owning frame observable without affecting the normal probe.
    if (value == Int.MIN_VALUE) println(payload)
    return payload.value
}

private var unwindSink: FramePayload? = null

private fun unwindFrame(payload: FramePayload): Int = try {
    if (payload.value < 0) error("negative")
    payload.value
} finally {
    unwindSink = payload
}

// Keep both negative probes address-taken so module optimization cannot erase their bodies.
private val ownedFrameReference: (Int) -> Int = ::ownedFrame
private val unwindFrameReference: (FramePayload) -> Int = ::unwindFrame

// BEFORE-LABEL: define internal i64 @"kfun:emptyFrame#internal"
// BEFORE: call void @EnterFrame
// BEFORE: call void @LeaveFrame

fun main() {
    val first = FramePayload(3)
    val second = FramePayload(7)
    var checksum = 0L
    repeat(100_000) { marker -> checksum += emptyFrame(first, marker, second) }
    check(ownedFrameReference(11) == 11)
    check(unwindFrameReference(first) == 3)
    println(checksum)
}

// AFTER-NOT: konan.arc.empty-frame-elision
// AFTER-LABEL: define{{.*}} void @"kfun:#main(){}"
// AFTER: @_ZN12_GLOBAL__N_112currentFrameE
// AFTER: when_exit.i:
// AFTER-NOT: @_ZN12_GLOBAL__N_112currentFrameE
// AFTER-NOT: @LeaveFrameArc
// AFTER: switch i32
// AFTER: call_success2:
// AFTER-NOT: @_ZN12_GLOBAL__N_112currentFrameE
// AFTER-NOT: @LeaveFrameArc
// AFTER: add i64
// AFTER-NOT: @_ZN12_GLOBAL__N_112currentFrameE
// AFTER-NOT: @LeaveFrameArc
// AFTER: br i1 {{.*}} label %returnable_block_exit14, label %when_exit.i

// AFTER-LABEL: define internal fastcc i32 @"kfun:$unwindFrame$FUNCTION_REFERENCE$1.invoke#internal"
// AFTER: load {{.*}} @_ZN12_GLOBAL__N_112currentFrameE
// AFTER: store {{.*}} @_ZN12_GLOBAL__N_112currentFrameE
// AFTER: landingpad.i:
// AFTER: load {{.*}} @_ZN12_GLOBAL__N_112currentFrameE
// AFTER: SetCurrentFrame.exit{{[0-9]*}}:
// AFTER: store {{.*}} @_ZN12_GLOBAL__N_112currentFrameE
// AFTER: cleanup_landingpad.i:
// AFTER: load {{.*}} @_ZN12_GLOBAL__N_112currentFrameE
// AFTER: SetCurrentFrame.exit{{[0-9]*}}:
// AFTER: store {{.*}} @_ZN12_GLOBAL__N_112currentFrameE
// AFTER: LeaveFrame.exit{{[0-9]*}}:
// AFTER: "kfun:unwindFrame#internal.exit":
// AFTER: store {{.*}} @_ZN12_GLOBAL__N_112currentFrameE
// AFTER: LeaveFrame.exit{{[0-9]*}}:
// AFTER-NOT: asm sideeffect
// AFTER-NOT: konan.arc.empty-frame-elision

// LTO: @llvm.used = appending global [7 x i8*] [
// LTO-DAG: @EnterFrame
// LTO-DAG: @Init_and_run_start
// LTO-DAG: @Konan_cxa_demangle
// LTO-DAG: @Konan_main
// LTO-DAG: @LeaveFrame
// LTO-DAG: @SetCurrentFrame
// LTO-DAG: @_Konan_constructors
// LTO-LABEL: define internal fastcc i32 @"kfun:$unwindFrame$FUNCTION_REFERENCE$1.invoke#internal"
// LTO: call{{.*}} @EnterFrame({{.*}}, i32 0, i32 5)
// LTO: call{{.*}} @SetCurrentFrame(
// LTO: call{{.*}} @LeaveFrame({{.*}}, i32 0, i32 5)
// LTO-LABEL: define{{.*}} void @LeaveFrame(
// LTO: call void asm sideeffect "", "r,r"(i32 %{{[0-9]+}}, i32 %{{[0-9]+}})
// LTO: "konan.arc.empty-frame-elision.inserted-used"{{.*}}"konan.arc.empty-frame-elision.leave-shape-guard"{{.*}}"konan.arc.empty-frame-elision.prepared"
// LTO: "konan.arc.empty-frame-elision.leave-shape-guard-call"

// NODIRECT-NOT: @EnterFrame to i8*
// NODIRECT-NOT: @LeaveFrame to i8*
// NODIRECT-NOT: @SetCurrentFrame to i8*
// NODIRECT-NOT: asm sideeffect
// NODIRECT-NOT: konan.arc.empty-frame-elision
// NODIRECT: @llvm.used = appending global [4 x i8*] [
// NODIRECT-DAG: @Init_and_run_start
// NODIRECT-DAG: @Konan_cxa_demangle
// NODIRECT-DAG: @Konan_main
// NODIRECT-DAG: @_Konan_constructors
// NODIRECT-NOT: call{{.*}} @EnterFrame(
// NODIRECT-NOT: call{{.*}} @LeaveFrame(
// NODIRECT-NOT: call{{.*}} @SetCurrentFrame(
// NODIRECT-NOT: @EnterFrame to i8*
// NODIRECT-NOT: @LeaveFrame to i8*
// NODIRECT-NOT: @SetCurrentFrame to i8*
// NODIRECT-NOT: asm sideeffect
// NODIRECT-NOT: konan.arc.empty-frame-elision
