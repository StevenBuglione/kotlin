@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

import kotlin.concurrent.AtomicReference
import kotlin.native.MemoryModel
import kotlin.native.Platform

private class Box(val value: Int)

private class ExpectedFailure(val box: Box) : Throwable()

private var finalizedFrames = 0

private fun throwThroughFrames(depth: Int): Nothing {
    val local = Box(depth)
    try {
        if (depth == 0) throw ExpectedFailure(local)
        throwThroughFrames(depth - 1)
    } finally {
        finalizedFrames += local.value + 1
    }
}

fun main() {
    check(Platform.memoryModel == MemoryModel.ARC) {
        "Expected ARC memory model, got ${Platform.memoryModel}"
    }

    val first = Box(1)
    val second = Box(2)
    val third = Box(3)
    val reference = AtomicReference<Box?>(first)
    check(reference.value === first)
    check(!reference.compareAndSet(Box(-1), second))
    check(reference.compareAndSet(first, second))
    check(reference.compareAndExchange(second, third) === second)
    check(reference.getAndSet(null) === third)
    check(reference.value == null)

    try {
        throwThroughFrames(63)
    } catch (failure: ExpectedFailure) {
        check(failure.box.value == 0)
    }
    check(finalizedFrames == (1..64).sum())

    println("ARC_SMOKE_OK model=${Platform.memoryModel} frames=$finalizedFrames atomics=ok")
}
