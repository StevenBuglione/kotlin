@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import arcCatching.invokeKotlinCatching
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.staticCFunction
import kotlin.native.arc.ArcDeinit

private var localDeinitCount = 0
private var exceptionDeinitCount = 0

private class ExpectedFailure : Throwable() {
    @ArcDeinit
    private fun deinit() {
        exceptionDeinitCount++
    }
}

private class Tracked {
    @ArcDeinit
    private fun deinit() {
        localDeinitCount++
    }
}

private fun successfulCallback(context: COpaquePointer?) {
    check(context == null)
}

private fun checkCleared(value: Any?) {
    check(value == null)
}

private fun throwingCallback(context: COpaquePointer?) {
    check(context == null)
    val owned = Tracked()
    owned.hashCode()
    val failure = ExpectedFailure()
    throw failure
}

fun main() {
    var baseline: ExpectedFailure? = ExpectedFailure()
    baseline!!.hashCode()
    baseline = null
    checkCleared(baseline)
    check(exceptionDeinitCount == 1) { "baseline exception deinit count was $exceptionDeinitCount instead of 1" }
    exceptionDeinitCount = 0

    check(invokeKotlinCatching(staticCFunction(::successfulCallback), null) == 0)
    check(localDeinitCount == 0)
    check(exceptionDeinitCount == 0)

    check(invokeKotlinCatching(staticCFunction(::throwingCallback), null) == 1)
    check(localDeinitCount == 1) {
        "callback exceptional unwind released $localDeinitCount local objects instead of 1"
    }
    check(exceptionDeinitCount == 1) {
        "C++ released $exceptionDeinitCount caught exceptions instead of exactly 1"
    }
    println("OK")
}
