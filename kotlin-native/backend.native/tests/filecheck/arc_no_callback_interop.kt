@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import arc_no_callback_interop.arc_callback_capable_control
import arc_no_callback_interop.arc_no_callback_leaf

// ARC-LABEL: define internal i32 @"kfun:markedLeaf#internal"(i32
// ARC-NOT: call void @Kotlin_mm_switchThreadStateNative()
// ARC: {{call|invoke}} i32 @_{{.*}}_knbridge{{[0-9]+}}(i32
// ARC-NOT: call void @Kotlin_mm_switchThreadStateRunnable()
// ARC: ret i32
// CONSERVATIVE-LABEL: define internal i32 @"kfun:markedLeaf#internal"(i32
// CONSERVATIVE: call void @Kotlin_mm_switchThreadStateNative()
// CONSERVATIVE: {{call|invoke}} i32 @_{{.*}}_knbridge{{[0-9]+}}(i32
// CONSERVATIVE: call void @Kotlin_mm_switchThreadStateRunnable()
// CONSERVATIVE: ret i32
// STRICT-LABEL: define internal i32 @"kfun:markedLeaf#internal"(i32
// STRICT-NOT: call void @Kotlin_mm_switchThreadStateNative()
// STRICT: {{call|invoke}} i32 @_{{.*}}_knbridge{{[0-9]+}}(i32
// STRICT-NOT: call void @Kotlin_mm_switchThreadStateRunnable()
// STRICT: ret i32
private fun markedLeaf(value: Int): Int = arc_no_callback_leaf(value)

// ARC-LABEL: define internal i32 @"kfun:unmarkedControl#internal"(i32
// ARC: call void @Kotlin_mm_switchThreadStateNative()
// ARC: {{call|invoke}} i32 @_{{.*}}_knbridge{{[0-9]+}}(i32
// ARC: call void @Kotlin_mm_switchThreadStateRunnable()
// ARC: ret i32
// CONSERVATIVE-LABEL: define internal i32 @"kfun:unmarkedControl#internal"(i32
// CONSERVATIVE: call void @Kotlin_mm_switchThreadStateNative()
// CONSERVATIVE: {{call|invoke}} i32 @_{{.*}}_knbridge{{[0-9]+}}(i32
// CONSERVATIVE: call void @Kotlin_mm_switchThreadStateRunnable()
// CONSERVATIVE: ret i32
// STRICT-LABEL: define internal i32 @"kfun:unmarkedControl#internal"(i32
// STRICT-NOT: call void @Kotlin_mm_switchThreadStateNative()
// STRICT: {{call|invoke}} i32 @_{{.*}}_knbridge{{[0-9]+}}(i32
// STRICT-NOT: call void @Kotlin_mm_switchThreadStateRunnable()
// STRICT: ret i32
private fun unmarkedControl(value: Int): Int = arc_callback_capable_control(value)

fun main() {
    println(markedLeaf(40) + unmarkedControl(0))
}
