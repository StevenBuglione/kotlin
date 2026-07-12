@file:OptIn(
    kotlin.experimental.ExperimentalNativeApi::class,
    kotlin.native.concurrent.ObsoleteWorkersApi::class,
)

import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

private class SharedState(var value: Int)

private val shared = SharedState(0)
private val ready = AtomicInt(0)
private val start = AtomicInt(0)

private fun race() {
    ready.incrementAndGet()
    while (start.value == 0) {
    }
    repeat(1_000_000) {
        shared.value = shared.value + 1
    }
}

fun main() {
    val workers = List(2) { Worker.start(name = "arc-tsan-racer-$it") }
    try {
        val futures = workers.map { worker ->
            worker.execute(TransferMode.SAFE, { Unit }) { race() }
        }
        while (ready.value != workers.size) {
        }
        start.value = 1
        futures.forEach { it.result }
    } finally {
        workers.forEach { it.requestTermination().result }
    }
    println("TSAN_RACE_WAS_NOT_DETECTED value=${shared.value}")
}
