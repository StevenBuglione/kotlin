import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

private var completed = 0
private var finallyCount = 0
private var checksum = 0L

private suspend fun exercise(index: Int): Long = try {
    val resumed = suspendCoroutine<Int> { continuation -> continuation.resume(index) }
    if (resumed % 7 == 0) error("expected-$resumed")
    resumed.toLong() * 3L
} catch (_: IllegalStateException) {
    -index.toLong()
} finally {
    finallyCount++
}

private suspend fun exerciseAll(iterations: Int): Long {
    var sum = 0L
    repeat(iterations) { index -> sum += exercise(index) }
    return sum
}

private val starter: suspend (Int) -> Long = ::exerciseAll

private val completion = object : Continuation<Long> {
    override val context: CoroutineContext = EmptyCoroutineContext

    override fun resumeWith(result: Result<Long>) {
        checksum += result.getOrThrow()
        completed++
    }
}

fun main(args: Array<String>) {
    val iterations = args.firstOrNull()?.toInt() ?: 100_000
    val expected = (0 until iterations).sumOf { index ->
        if (index % 7 == 0) -index.toLong() else index.toLong() * 3L
    }
    starter.startCoroutine(iterations, completion)
    check(completed == 1)
    check(finallyCount == iterations)
    check(checksum == expected)
    println("ARC_COROUTINE_STRESS_OK iterations=$iterations checksum=$checksum finally=$finallyCount")
}
