@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

import kotlin.native.internal.ExportForCppRuntime
import kotlin.native.internal.GCUnsafeCall

@ExportForCppRuntime("kotlin_increment")
fun kotlinIncrement(value: Int): Int = value + 1

@GCUnsafeCall("rust_round_trip")
private external fun rustRoundTrip(value: Int): Int

fun main() {
    println(rustRoundTrip(20))
}
