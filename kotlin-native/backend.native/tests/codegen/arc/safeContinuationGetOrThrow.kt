@file:Suppress("DEPRECATION")

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.native.concurrent.FreezableAtomicReference
import kotlin.native.concurrent.Future
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

private class ExpectedFailure : Exception()

private suspend fun immediate(value: Int): Int =
    suspendCoroutine { continuation -> continuation.resume(value) }

private suspend fun failing(): Int =
    suspendCoroutine { continuation ->
        continuation.resumeWith(Result.failure(ExpectedFailure()))
    }

private var pending: Continuation<Int>? = null

private suspend fun delayed(): Int =
    suspendCoroutine { continuation -> pending = continuation }

private var raceFuture: Future<Unit>? = null

private suspend fun raced(worker: Worker, value: Int): Int =
    suspendCoroutine { continuation ->
        raceFuture = worker.execute(TransferMode.UNSAFE, { continuation to value }) { (next, result) ->
            next.resume(result)
        }
    }

// Deliberately resembles the two-read portion of SafeContinuation.getOrThrow.
// Symbol identity, not a coincidental body shape, must authorize the fast path.
private class GetOrThrowLookalike(initial: Any?) {
    private val resultRef = FreezableAtomicReference<Any?>(initial)

    fun getOrThrow(): Any? {
        var result = resultRef.value
        if (result == null) result = resultRef.value
        return result
    }
}

private class Completion<T> : Continuation<T> {
    override val context: CoroutineContext = EmptyCoroutineContext
    var result: Result<T>? = null

    override fun resumeWith(result: Result<T>) {
        this.result = result
    }
}

private fun immediateResult(value: Int): Int {
    val completion = Completion<Int>()
    suspend { immediate(value) }.startCoroutine(completion)
    return completion.result?.getOrThrow() ?: error("immediate coroutine did not complete")
}

private fun failureResult(): Throwable {
    val completion = Completion<Int>()
    suspend { failing() }.startCoroutine(completion)
    return completion.result?.exceptionOrNull() ?: error("failure coroutine did not fail")
}

private fun delayedResult(value: Int): Int {
    val completion = Completion<Int>()
    suspend { delayed() }.startCoroutine(completion)
    check(completion.result == null)
    val continuation = pending ?: error("delayed coroutine did not suspend")
    pending = null
    continuation.resume(value)
    return completion.result?.getOrThrow() ?: error("delayed coroutine did not resume")
}

private fun raceResultStress(): Long {
    val worker = Worker.start(name = "safe-continuation-arc-race")
    var checksum = 0L
    repeat(2_000) { value ->
        val completion = Completion<Int>()
        suspend { raced(worker, value) }.startCoroutine(completion)
        raceFuture?.result ?: error("race worker was not scheduled")
        checksum += completion.result?.getOrThrow() ?: error("raced coroutine did not resume")
    }
    worker.requestTermination().result
    return checksum
}

// OPT-LABEL: define %struct.ObjHeader* @"kfun:kotlin.coroutines.SafeContinuation#getOrThrow
// OPT-SAME: %struct.ObjHeader** [[RETURN_SLOT:%[-a-zA-Z$._0-9]+]]
// Both canonical atomic reads must initialize the same owning local. Reusing the
// slot is safe because the second read is reached only after the failed CAS.
// OPT: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.native.concurrent.FreezableAtomicReference#<get-value>(){}1:0"({{.*}}%struct.ObjHeader** [[RESULT_SLOT:%[-a-zA-Z$._0-9]+]]
// OPT-NOT: call void @UpdateStackRef{{.*}}[[RESULT_SLOT]]
// OPT: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.native.concurrent.FreezableAtomicReference#<get-value>(){}1:0"({{.*}}%struct.ObjHeader** [[RESULT_SLOT]]
// OPT-NOT: call void @UpdateStackRef{{.*}}[[RESULT_SLOT]]
// The enum discriminators are ordinary heap objects whose lifetime is guaranteed by the
// compiler-owned immutable $VALUES root. Their exact projections stay borrowed.
// OPT-NOT: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.coroutines.intrinsics.CoroutineSingletons.\$getEnumAt
// OPT: {{call|invoke}} %struct.ObjHeader* @Kotlin_Array_get_borrowed
// The ordinary payload result is moved to the ABI return slot. Singleton and
// exception arms still perform their normal cleanup before this successful arm.
// OPT: call void @MoveReferenceIntoReturnSlotArc(%struct.ObjHeader** [[RETURN_SLOT]], %struct.ObjHeader*
// OPT-NEXT: store %struct.ObjHeader* null, %struct.ObjHeader** [[RESULT_SLOT]]
// OPT-NOT: call void @UpdateReturnRef{{.*}}[[RETURN_SLOT]]
// OPT: ret %struct.ObjHeader*
// OPT-LABEL: define internal %struct.ObjHeader* @"kfun:GetOrThrowLookalike.getOrThrow#internal
// OPT-NOT: call void @MoveReferenceIntoReturnSlotArc
// OPT: ret %struct.ObjHeader*

// DEBUG-LABEL: define %struct.ObjHeader* @"kfun:kotlin.coroutines.SafeContinuation#getOrThrow
// DEBUG-NOT: @Kotlin_Array_get_borrowed
// DEBUG-NOT: call void @MoveReferenceIntoReturnSlotArc
// DEBUG: call void @UpdateReturnRef
// DEBUG: ret %struct.ObjHeader*

// DIAGNOSTIC-LABEL: define %struct.ObjHeader* @"kfun:kotlin.coroutines.SafeContinuation#getOrThrow
// DIAGNOSTIC-NOT: @Kotlin_Array_get_borrowed
// DIAGNOSTIC-NOT: call void @MoveReferenceIntoReturnSlotArc
// DIAGNOSTIC: call void @UpdateReturnRef
// DIAGNOSTIC: ret %struct.ObjHeader*

// STRICT-LABEL: define %struct.ObjHeader* @"kfun:kotlin.coroutines.SafeContinuation#getOrThrow
// STRICT-NOT: @Kotlin_Array_get_borrowed
// STRICT-NOT: call void @MoveReferenceIntoReturnSlotArc
// STRICT: ret %struct.ObjHeader*

fun main() {
    check(immediateResult(41) == 41)
    check(delayedResult(42) == 42)
    check(failureResult() is ExpectedFailure)
    check(raceResultStress() == 1_999_000L)
    check(GetOrThrowLookalike(43).getOrThrow() == 43)
    println("OK")
}
