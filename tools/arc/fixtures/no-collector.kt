@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

import kotlin.native.MemoryModel
import kotlin.native.Platform

private class OrdinaryArcObject(val value: Int)

fun main() {
    check(Platform.memoryModel == MemoryModel.ARC)
    check(OrdinaryArcObject(42).value == 42)
    println("ARC_NO_COLLECTOR_OK model=${Platform.memoryModel}")
}
