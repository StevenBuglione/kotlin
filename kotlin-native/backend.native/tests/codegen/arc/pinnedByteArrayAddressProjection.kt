@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pointed
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

private fun projectedRead(bytes: ByteArray, index: Int): Byte = bytes.usePinned { pinned ->
    pinned.addressOf(index).pointed.value
}

// Returning the interior pointer is deliberately outside the first non-escaping slice.
private fun escapingProjection(bytes: ByteArray): CPointer<ByteVar> = bytes.usePinned { pinned ->
    pinned.addressOf(0)
}

// OPT-LABEL: define internal signext i8 @"kfun:#projectedRead
// OPT: {{call|invoke}} i8* @Kotlin_Interop_getPinnedByteArrayAddressArc
// OPT-NOT: {{call|invoke}} %struct.ObjHeader* @Kotlin_Interop_derefStablePointer
// OPT: {{call|invoke}} void @Kotlin_Interop_disposeStablePointer
// OPT: ret i8
// OPT-LABEL: define internal i8* @"kfun:#escapingProjection
// OPT-NOT: @Kotlin_Interop_getPinnedByteArrayAddressArc
// OPT: @Kotlin_Interop_derefStablePointer

// DEBUG-LABEL: define internal signext i8 @"kfun:#projectedRead
// DEBUG-NOT: @Kotlin_Interop_getPinnedByteArrayAddressArc
// DEBUG: @Kotlin_Interop_derefStablePointer

// DIAGNOSTIC-LABEL: define internal signext i8 @"kfun:#projectedRead
// DIAGNOSTIC-NOT: @Kotlin_Interop_getPinnedByteArrayAddressArc
// DIAGNOSTIC: @Kotlin_Interop_derefStablePointer

// SANITIZER-LABEL: define internal signext i8 @"kfun:#projectedRead
// SANITIZER-NOT: @Kotlin_Interop_getPinnedByteArrayAddressArc
// SANITIZER: @Kotlin_Interop_derefStablePointer

// STRICT-LABEL: define internal signext i8 @"kfun:#projectedRead
// STRICT-NOT: @Kotlin_Interop_getPinnedByteArrayAddressArc
// STRICT: @Kotlin_Interop_derefStablePointer

fun main() {
    val bytes = byteArrayOf(11, 22, 33)
    check(projectedRead(bytes, 1) == 22.toByte())
    try {
        projectedRead(bytes, -1)
        error("negative index must retain the canonical bounds failure")
    } catch (_: IndexOutOfBoundsException) {
    }
    try {
        projectedRead(bytes, bytes.size)
        error("upper-bound index must retain the canonical bounds failure")
    } catch (_: IndexOutOfBoundsException) {
    }
    // Materialize the fallback function without dereferencing its deliberately escaped pointer.
    escapingProjection(bytes)
    println("OK")
}
