@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(
    kotlin.experimental.ExperimentalNativeApi::class,
    kotlin.native.internal.InternalForKotlinNative::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

import kotlin.native.CName
import kotlin.native.internal.ExportForCppRuntime
import platform.posix.getpid

class MatchingSetBenchPayload(val value: Int)

private var matchingSetBenchSink = 0L
private var matchingSetBenchRuntimeGuard = 0

@ExportForCppRuntime("arc_matching_set_sink")
private fun consumeMatchingSetBench(marker: Int, payload: MatchingSetBenchPayload) {
    val value = payload.value
    val mixed = when (marker and 15) {
        0 -> value + marker
        1 -> value * 2 + marker
        2 -> value * 3 + marker
        3 -> value * 4 + marker
        4 -> value * 5 + marker
        5 -> value * 6 + marker
        6 -> value * 7 + marker
        7 -> value * 8 + marker
        8 -> value * 9 + marker
        9 -> value * 10 + marker
        10 -> value * 11 + marker
        11 -> value * 12 + marker
        12 -> value * 13 + marker
        13 -> value * 14 + marker
        14 -> value * 15 + marker
        else -> value * 16 + marker
    }
    matchingSetBenchSink += (mixed and 7).toLong()
}

@ExportForCppRuntime("arc_matching_set_borrow")
fun borrowMatchingSetManyTimes(owner: MatchingSetBenchPayload, marker: Int) {
    // Keep this as a real call boundary through final LTO. The runtime-populated guard makes the
    // large cold block unknowable at compile time, while normal benchmark executions never enter it.
    if (matchingSetBenchRuntimeGuard == Int.MIN_VALUE) {
        consumeMatchingSetBench(marker + 16, owner)
        consumeMatchingSetBench(marker + 17, owner)
        consumeMatchingSetBench(marker + 18, owner)
        consumeMatchingSetBench(marker + 19, owner)
        consumeMatchingSetBench(marker + 20, owner)
        consumeMatchingSetBench(marker + 21, owner)
        consumeMatchingSetBench(marker + 22, owner)
        consumeMatchingSetBench(marker + 23, owner)
        consumeMatchingSetBench(marker + 24, owner)
        consumeMatchingSetBench(marker + 25, owner)
        consumeMatchingSetBench(marker + 26, owner)
        consumeMatchingSetBench(marker + 27, owner)
        consumeMatchingSetBench(marker + 28, owner)
        consumeMatchingSetBench(marker + 29, owner)
        consumeMatchingSetBench(marker + 30, owner)
        consumeMatchingSetBench(marker + 31, owner)
        consumeMatchingSetBench(marker + 32, owner)
        consumeMatchingSetBench(marker + 33, owner)
        consumeMatchingSetBench(marker + 34, owner)
        consumeMatchingSetBench(marker + 35, owner)
        consumeMatchingSetBench(marker + 36, owner)
        consumeMatchingSetBench(marker + 37, owner)
        consumeMatchingSetBench(marker + 38, owner)
        consumeMatchingSetBench(marker + 39, owner)
        consumeMatchingSetBench(marker + 40, owner)
        consumeMatchingSetBench(marker + 41, owner)
        consumeMatchingSetBench(marker + 42, owner)
        consumeMatchingSetBench(marker + 43, owner)
        consumeMatchingSetBench(marker + 44, owner)
        consumeMatchingSetBench(marker + 45, owner)
        consumeMatchingSetBench(marker + 46, owner)
        consumeMatchingSetBench(marker + 47, owner)
    }
    var alias = owner
    consumeMatchingSetBench(marker, alias)
    consumeMatchingSetBench(marker + 1, alias)
    consumeMatchingSetBench(marker + 2, alias)
    consumeMatchingSetBench(marker + 3, alias)
    consumeMatchingSetBench(marker + 4, alias)
    consumeMatchingSetBench(marker + 5, alias)
    consumeMatchingSetBench(marker + 6, alias)
    consumeMatchingSetBench(marker + 7, alias)
    consumeMatchingSetBench(marker + 8, alias)
    consumeMatchingSetBench(marker + 9, alias)
    consumeMatchingSetBench(marker + 10, alias)
    consumeMatchingSetBench(marker + 11, alias)
    consumeMatchingSetBench(marker + 12, alias)
    consumeMatchingSetBench(marker + 13, alias)
    consumeMatchingSetBench(marker + 14, alias)
    consumeMatchingSetBench(marker + 15, alias)
}

@CName("arc_matching_set_run")
fun runMatchingSetBenchmark(iterations: Int, phase: Int): Long {
    resetMatchingSetBenchmark(phase)
    val owner = MatchingSetBenchPayload(0x5a5a)
    repeat(iterations) { borrowMatchingSetManyTimes(owner, it + phase) }
    return matchingSetBenchSink
}

fun resetMatchingSetBenchmark(phase: Int) {
    matchingSetBenchSink = 0
    matchingSetBenchRuntimeGuard = phase
}

fun matchingSetBenchmarkChecksum(): Long = matchingSetBenchSink

fun main(args: Array<String>) {
    val iterations = args.firstOrNull()?.toInt() ?: 100_000_000
    val result = runMatchingSetBenchmark(iterations, getpid() and 7)
    check(result >= 0)
    println("ARC_MATCHING_SET_BENCH_OK iterations=$iterations checksum=$matchingSetBenchSink")
}
