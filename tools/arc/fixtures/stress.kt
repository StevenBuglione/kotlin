@file:OptIn(
    kotlin.experimental.ExperimentalNativeApi::class,
    kotlin.native.runtime.NativeRuntimeApi::class,
)

import kotlin.native.MemoryModel
import kotlin.native.Platform
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC

private const val ALLOCATION_COUNT = 5_000_000

private class Payload(val value: Int)

private class CycleNode(var next: CycleNode? = null)

private class ExpectedFailure : Throwable()

private var finalizedFrames = 0

private fun allocateAcyclicObjects(): Long {
    var checksum = 0L
    repeat(ALLOCATION_COUNT) { index ->
        val payload = Payload(index)
        checksum += payload.value
    }
    return checksum
}

private fun abandonStrongCycle(): WeakReference<CycleNode> {
    val first = CycleNode()
    val second = CycleNode(first)
    first.next = second
    return WeakReference(first)
}

private fun throwThroughFrames(depth: Int): Nothing {
    val payload = Payload(depth)
    try {
        if (depth == 0) throw ExpectedFailure()
        throwThroughFrames(depth - 1)
    } finally {
        finalizedFrames += payload.value + 1
    }
}

fun main() {
    check(Platform.memoryModel == MemoryModel.ARC) {
        "Expected ARC memory model, got ${Platform.memoryModel}"
    }

    val checksum = allocateAcyclicObjects()
    val expectedChecksum = ALLOCATION_COUNT.toLong() * (ALLOCATION_COUNT - 1L) / 2L
    check(checksum == expectedChecksum)

    val cycle = abandonStrongCycle()
    GC.collect()
    check(cycle.get() != null) { "ARC unexpectedly collected a strong reference cycle" }

    try {
        throwThroughFrames(127)
    } catch (_: ExpectedFailure) {
    }
    check(finalizedFrames == (1..128).sum())

    println(
        "ARC_STRESS_OK model=${Platform.memoryModel} allocations=$ALLOCATION_COUNT " +
                "checksum=$checksum strongCycle=retained frames=$finalizedFrames"
    )
}
