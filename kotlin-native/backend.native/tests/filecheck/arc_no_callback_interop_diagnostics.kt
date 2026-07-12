@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import arc_no_callback_interop.arc_no_callback_leaf
import kotlin.native.arc.ArcDebug

// CHECK-LABEL: define internal i32 @"kfun:diagnosticMarkedLeaf#internal"(i32
// CHECK: call void @Kotlin_mm_switchThreadStateNative()
// CHECK: {{call|invoke}} i32 @_{{.*}}_knbridge{{[0-9]+}}(i32
// CHECK: call void @Kotlin_mm_switchThreadStateRunnable()
// CHECK: ret i32
private fun diagnosticMarkedLeaf(value: Int): Int = arc_no_callback_leaf(value)

fun main() {
    println(diagnosticMarkedLeaf(41))
    ArcDebug.detectCycles()
}
