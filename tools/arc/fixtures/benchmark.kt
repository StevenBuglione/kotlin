@file:OptIn(
    kotlin.native.concurrent.ObsoleteWorkersApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.native.concurrent.AtomicInt
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import arc.benchmark.cinterop.arc_benchmark_strlen_ptr
import arc.benchmark.cinterop.arc_benchmark_strlen_string
import platform.posix.getpid
import platform.posix.strlen

private data class BenchResult(val checksum: Long, val operations: Long, val allocations: Long)

private class Payload(val value: Int, val padding: Long = value.toLong())
private class ChainNode(val value: Int, val next: ChainNode?)
private class MutableFields(var first: Long, var second: Long, var reference: Payload?)
private class CycleNode(val value: Int) { var next: CycleNode? = null }

private fun allocationWork(): BenchResult {
    val count = 6_000_000
    val window = arrayOfNulls<Payload>(4096)
    var checksum = 0L
    repeat(count) { index ->
        val slot = index and (window.size - 1)
        val value = Payload(index)
        window[slot] = value
        checksum += value.value + value.padding
    }
    return BenchResult(checksum + window.sumOf { it?.value?.toLong() ?: 0L }, count.toLong(), count + 1L)
}

private fun destructionWork(): BenchResult {
    val count = 1_500_000
    var head: ChainNode? = null
    repeat(count) { index -> head = ChainNode(index, head) }
    var checksum = 0L
    var cursor = head
    while (cursor != null) {
        checksum += cursor.value
        cursor = cursor.next
    }
    head = null
    return BenchResult(checksum, count.toLong(), count.toLong())
}

private fun fieldsWork(): BenchResult {
    val count = 200_000_000
    val fields = MutableFields(1, 2, Payload(3))
    repeat(count) { index ->
        fields.first += fields.second
        fields.second = fields.first xor index.toLong()
        if ((index and 0x3fff) == 0) fields.reference = Payload(index)
    }
    return BenchResult(fields.first xor fields.second xor (fields.reference?.value?.toLong() ?: 0L), count.toLong(), 2L + (count + 0x3fff) / 0x4000)
}

private fun arraysWork(): BenchResult {
    val count = 120_000_000
    val values = LongArray(8192) { it.toLong() }
    var checksum = 0L
    repeat(count) { index ->
        val slot = index and (values.size - 1)
        val value = values[slot] + index
        values[slot] = value
        checksum = checksum xor value
    }
    return BenchResult(checksum, count.toLong(), 1)
}

private fun stringsWork(): BenchResult {
    val count = 600_000
    var checksum = 0L
    repeat(count) { index ->
        val value = "arc-${index and 1023}-${index.toString(16)}"
        checksum += value.length + value.hashCode()
    }
    return BenchResult(checksum, count.toLong(), count * 3L)
}

private interface Operation { fun apply(value: Int): Int }
private class Add(private val amount: Int) : Operation { override fun apply(value: Int) = value + amount }
private class Multiply(private val amount: Int) : Operation { override fun apply(value: Int) = value * amount }
private class Xor(private val amount: Int) : Operation { override fun apply(value: Int) = value xor amount }

private fun virtualDispatchWork(): BenchResult {
    val count = 80_000_000
    val operations = arrayOf<Operation>(Add(3), Multiply(5), Xor(0x55aa))
    var checksum = 1
    repeat(count) { index -> checksum = operations[index % operations.size].apply(checksum) }
    return BenchResult(checksum.toLong(), count.toLong(), 4)
}

// Deliberately large, with a cold nonrecursive edge, so the optimized benchmark keeps a real
// direct call boundary while an otherwise call-free ARC frame remains eligible for elimination.
// This input supersedes the recursive wave-02/wave-03 variant, whose frame was intentionally not
// empty and therefore measured unavoidable frame cleanup rather than argument passing alone.
private fun consumeArguments(first: Payload, marker: Int, second: Payload): Long {
    if (marker == Int.MIN_VALUE) {
        return second.value.toLong() - first.value.toLong() + marker.toLong()
    }
    val left = first.value.toLong()
    val right = second.value.toLong()
    val position = marker.toLong()
    return when (marker and 15) {
        0 -> left + position + right
        1 -> left * 2 + position + right
        2 -> left * 3 + position + right
        3 -> left * 4 + position + right
        4 -> left * 5 + position + right
        5 -> left * 6 + position + right
        6 -> left * 7 + position + right
        7 -> left * 8 + position + right
        8 -> left * 9 + position + right
        9 -> left * 10 + position + right
        10 -> left * 11 + position + right
        11 -> left * 12 + position + right
        12 -> left * 13 + position + right
        13 -> left * 14 + position + right
        14 -> left * 15 + position + right
        15 -> left * 16 + position + right
        else -> error("unreachable argument selector")
    }
}

private fun callArgumentsWork(): BenchResult {
    val count = 80_000_000
    var first = Payload(1)
    var second = Payload(7)
    var checksum = 0L
    repeat(count) { index ->
        if ((index and 0x3fff) == 0) first = Payload(index)
        checksum += consumeArguments(first, index, second)
    }
    check(checksum == 30_394_430_133_801_472L)
    val replacements = (count + 0x3fff) / 0x4000
    return BenchResult(checksum, count.toLong(), 2L + replacements)
}

private fun closuresWork(): BenchResult {
    val count = 600_000_000
    var checksum = 0L
    repeat(count) { index ->
        val captured = index
        val operation: (Int) -> Int = { it xor captured }
        checksum += operation(index + 17)
    }
    return BenchResult(checksum, count.toLong(), count.toLong())
}

private fun exceptionsWork(): BenchResult {
    val count = 100_000
    var checksum = 0L
    repeat(count) { index ->
        try {
            throw IllegalStateException(index.toString())
        } catch (failure: IllegalStateException) {
            checksum += failure.message!!.length
        }
    }
    return BenchResult(checksum, count.toLong(), count * 2L)
}

private suspend fun suspendStep(value: Int): Int = suspendCoroutine { continuation -> continuation.resume(value + 1) }

private fun coroutinesWork(): BenchResult {
    val count = 300_000
    var checksum = 0L
    repeat(count) { index ->
        var outcome: Result<Int>? = null
        suspend { suspendStep(index) }.startCoroutine(object : Continuation<Int> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<Int>) { outcome = result }
        })
        checksum += outcome!!.getOrThrow()
    }
    return BenchResult(checksum, count.toLong(), count * 3L)
}

private fun workerLoop(seed: Int): Long {
    val value = AtomicInt(seed)
    var checksum = 0L
    repeat(40_000_000) { checksum += value.addAndGet(1) }
    return checksum
}

private fun workersWork(): BenchResult {
    val workers = List(4) { Worker.start(name = "arc-bench-$it") }
    val futures = workers.mapIndexed { index, worker ->
        worker.execute(TransferMode.SAFE, { index }) { workerLoop(it) }
    }
    val checksum = futures.sumOf { it.result }
    workers.forEach { it.requestTermination().result }
    return BenchResult(checksum, 160_000_000L, 16)
}

private fun atomicsWork(): BenchResult {
    val count = 40_000_000
    val value = AtomicInt(0)
    var checksum = 0L
    repeat(count) { checksum += value.addAndGet(1) }
    return BenchResult(checksum, count.toLong(), 1)
}

private fun platformCInteropWork(): BenchResult {
    val count = 1_800_000
    val value = "kotlin-native-arc"
    var checksum = 0L
    repeat(count) { checksum += strlen(value).toLong() }
    return BenchResult(checksum, count.toLong(), 0)
}

private fun platformCLeafWork(): BenchResult {
    val count = 20_000_000
    val buffer = ByteArray(32) { 'x'.code.toByte() }
    buffer[31] = 0
    // Process identity is deliberately runtime-only, but it merely rotates eight equally
    // frequent lengths. The checksum is therefore identical in every benchmark process.
    val phase = getpid() and 7
    var previousTerminator = 31
    var checksum = 0L
    buffer.usePinned { pinned ->
        // Resolve the stable pinned address once. Repeating addressOf in the hot loop calls
        // Kotlin_initRuntimeIfNeeded and measures runtime setup rather than the C boundary.
        val address = pinned.addressOf(0)
        repeat(count) { index ->
            buffer[previousTerminator] = 'x'.code.toByte()
            val terminator = 8 + ((index + phase) and 7)
            buffer[terminator] = 0
            checksum += arc_benchmark_strlen_ptr(address).toLong()
            previousTerminator = terminator
        }
    }
    check(checksum == 230_000_000L)
    return BenchResult(checksum, count.toLong(), 1)
}

private fun platformCDynamicCStringWork(): BenchResult {
    val count = 1_800_000
    var value = "kotlin-native-arc-0"
    var checksum = 0L
    repeat(count) { index ->
        if ((index and 1023) == 0) value = "kotlin-native-arc-${index and 7}"
        checksum += arc_benchmark_strlen_string(value).toLong()
    }
    check(checksum == 34_200_000L)
    val replacements = (count + 1023) / 1024
    return BenchResult(checksum, count.toLong(), count * 2L + replacements)
}

private fun boundedCyclesWork(): BenchResult {
    val count = 25_000
    val traversalsPerCycle = 10_240
    var checksum = 0L
    repeat(count) { index ->
        val first = CycleNode(index)
        val second = CycleNode(index + 1)
        first.next = second
        second.next = first
        var cursor = first
        repeat(traversalsPerCycle) {
            cursor = cursor.next!!
            checksum += cursor.value
        }
    }
    check(checksum == 3_200_000_000_000L)
    return BenchResult(checksum, count.toLong() * traversalsPerCycle.toLong(), count * 2L)
}

fun main(args: Array<String>) {
    val scenario = args.singleOrNull() ?: error("expected one benchmark scenario")
    val result = when (scenario) {
        "allocation" -> allocationWork()
        "destruction" -> destructionWork()
        "fields" -> fieldsWork()
        "arrays" -> arraysWork()
        "strings" -> stringsWork()
        "virtual-dispatch" -> virtualDispatchWork()
        "call-arguments" -> callArgumentsWork()
        "closures" -> closuresWork()
        "exceptions" -> exceptionsWork()
        "coroutines" -> coroutinesWork()
        "workers" -> workersWork()
        "atomics" -> atomicsWork()
        "platform-c-interop" -> platformCInteropWork()
        "platform-c-leaf" -> platformCLeafWork()
        "platform-c-dynamic-cstring" -> platformCDynamicCStringWork()
        "bounded-cycles" -> boundedCyclesWork()
        else -> error("unknown scenario: $scenario")
    }
    println("ARC_BENCH_OK scenario=$scenario checksum=${result.checksum} operations=${result.operations} allocations=${result.allocations}")
}
