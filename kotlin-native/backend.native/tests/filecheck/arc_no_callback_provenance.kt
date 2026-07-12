@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(kotlin.native.internal.InternalForKotlinNative::class)

import kotlinx.cinterop.internal.CCall

// Deliberately forge the metadata that cinterop normally emits. The backend must
// ignore NoCallback here because this declaration has no native-cinterop provenance.
@CCall("arc_forged_no_callback")
@CCall.NoCallback
private external fun forgedNoCallback(): Int

// CHECK-LABEL: define internal i32 @"kfun:forgedMarkerFailsClosed#internal"()
// CHECK: call void @Kotlin_mm_switchThreadStateNative()
// CHECK: {{call|invoke}} i32 @_{{.*}}_knbridge{{[0-9]+}}()
// CHECK: call void @Kotlin_mm_switchThreadStateRunnable()
// CHECK: ret i32
private fun forgedMarkerFailsClosed(): Int = forgedNoCallback()

fun main() {
    // FileCheck stops after code generation, so the intentionally absent C symbol
    // is never linked or executed.
    println(forgedMarkerFailsClosed())
}
