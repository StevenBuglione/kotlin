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
import platform.posix.strlen

private data class BenchResult(val checksum: Long, val operations: Long, val allocations: Long)

private class Payload(val value: Int, val padding: Long = value.toLong())
private class ChainNode(val value: Int, val next: ChainNode?)
private class MutableFields(var first: Long, var second: Long, var reference: Payload?)
private class CycleNode(val value: Int) { var next: CycleNode? = null }

private fun allocationWork(): BenchResult {
    val count = 2_000_000
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
    val count = 100_000
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
    val count = 20_000_000
    val fields = MutableFields(1, 2, Payload(3))
    repeat(count) { index ->
        fields.first += fields.second
        fields.second = fields.first xor index.toLong()
        if ((index and 0x3fff) == 0) fields.reference = Payload(index)
    }
    return BenchResult(fields.first xor fields.second xor (fields.reference?.value?.toLong() ?: 0L), count.toLong(), 2L + (count + 0x3fff) / 0x4000)
}

private fun arraysWork(): BenchResult {
    val count = 20_000_000
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
    val count = 150_000
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
    val count = 20_000_000
    val operations = arrayOf<Operation>(Add(3), Multiply(5), Xor(0x55aa))
    var checksum = 1
    repeat(count) { index -> checksum = operations[index % operations.size].apply(checksum) }
    return BenchResult(checksum.toLong(), count.toLong(), 4)
}

// Deliberately large, with a cold recursive edge, so the optimized benchmark keeps a real direct
// call boundary. The stable-suffix ARC optimization can then remove the owning argument copies
// without the benchmark depending on a Kotlin inline function or a newer compiler annotation.
private fun consumeArguments(first: Payload, marker: Int, second: Payload): Long {
    if (marker == Int.MIN_VALUE) return consumeArguments(second, marker + 1, first)
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
    val count = 20_000_000
    var first = Payload(1)
    var second = Payload(7)
    var checksum = 0L
    repeat(count) { index ->
        if ((index and 0x3fff) == 0) first = Payload(index)
        checksum += consumeArguments(first, index, second)
    }
    check(checksum == 1_898_607_728_141_440L)
    val replacements = (count + 0x3fff) / 0x4000
    return BenchResult(checksum, count.toLong(), 2L + replacements)
}

private fun closuresWork(): BenchResult {
    val count = 1_000_000
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
    val count = 200_000
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
    var checksum = seed.toLong()
    repeat(2_000_000) { checksum = checksum * 1664525L + 1013904223L }
    return checksum
}

private fun workersWork(): BenchResult {
    val workers = List(4) { Worker.start(name = "arc-bench-$it") }
    val futures = workers.mapIndexed { index, worker ->
        worker.execute(TransferMode.SAFE, { index }) { workerLoop(it) }
    }
    val checksum = futures.sumOf { it.result }
    workers.forEach { it.requestTermination().result }
    return BenchResult(checksum, 8_000_000, 12)
}

private fun atomicsWork(): BenchResult {
    val count = 10_000_000
    val value = AtomicInt(0)
    var checksum = 0L
    repeat(count) { checksum += value.addAndGet(1) }
    return BenchResult(checksum, count.toLong(), 1)
}

private fun platformCInteropWork(): BenchResult {
    val count = 2_000_000
    val value = "kotlin-native-arc"
    var checksum = 0L
    repeat(count) { checksum += strlen(value).toLong() }
    return BenchResult(checksum, count.toLong(), 0)
}

private fun boundedCyclesWork(): BenchResult {
    val count = 25_000
    var checksum = 0L
    repeat(count) { index ->
        val first = CycleNode(index)
        val second = CycleNode(index + 1)
        first.next = second
        second.next = first
        checksum += first.next!!.value + second.next!!.value
    }
    return BenchResult(checksum, count.toLong(), count * 2L)
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
        "bounded-cycles" -> boundedCyclesWork()
        else -> error("unknown scenario: $scenario")
    }
    println("ARC_BENCH_OK scenario=$scenario checksum=${result.checksum} operations=${result.operations} allocations=${result.allocations}")
}
