@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

import kotlin.concurrent.AtomicReference
import kotlin.native.MemoryModel
import kotlin.native.Platform

private class Box(val value: Int)

fun main() {
    check(Platform.memoryModel == MemoryModel.ARC)
    val reference = AtomicReference<Box?>(Box(40))
    reference.value = Box(reference.value!!.value + 2)
    check(reference.value!!.value == 42)
    println("ARC_SANITIZER_OK model=${Platform.memoryModel}")
}
