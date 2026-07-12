@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(kotlin.native.internal.InternalForKotlinNative::class)

import kotlin.native.internal.GCUnsafeCall
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

private class Payload(val value: Int)

private fun produce(): Payload = Payload(42)
private fun produceOther(): Payload = Payload(7)

private fun forwardOwnedResultThroughAliases(): Payload {
    val owned = produce()
    val firstAlias = owned
    val returnedAlias = firstAlias
    return returnedAlias
}

private fun forwardNestedArrayResult(): ByteArray = run { run { ByteArray(32) } }

private class ContinuationLike {
    fun intercepted(): ContinuationLike = this
}

private fun forwardInterceptedResult(value: ContinuationLike): ContinuationLike = run { value.intercepted() }

private fun forwardAllOwnedBranchResults(selectFirst: Boolean): Payload =
    if (selectFirst) produce() else produceOther()

private fun retainMixedBorrowedBranchResult(selectNew: Boolean, existing: Payload): Payload =
    if (selectNew) produce() else existing

private fun retainAcrossThrowingSuffix(shouldThrow: Boolean): Payload {
    val produced = produce()
    if (shouldThrow) error("suffix")
    return produced
}

@GCUnsafeCall("ReadHeapRefNoLock")
private external fun readHeapRefNoLock(holder: Any, index: Int): Payload

private class Holder(val first: Payload)

private fun retainExternalResult(holder: Holder): Payload = readHeapRefNoLock(holder, 0)

private suspend fun immediateSuspendStep(value: Int): Payload = suspendCoroutine { continuation ->
    continuation.resume(Payload(value))
}

private var delayedContinuation: Continuation<Payload>? = null

private suspend fun delayedSuspendStep(): Payload = suspendCoroutine { continuation ->
    delayedContinuation = continuation
}

private suspend fun immediateSuspendAdapter(value: Int): Payload = immediateSuspendStep(value)

private suspend fun throwingSuspendStep(value: Int): Payload {
    if (value < 0) error("throw before result")
    return Payload(value)
}

private suspend fun throwingDirectSuspendAdapter(value: Int): Payload = throwingSuspendStep(value)

private suspend fun mixedSuspendAdapter(value: Int): Payload {
    if (value < 0) return Payload(-value)
    return immediateSuspendStep(value)
}

private suspend fun lambdaSuspendAdapter(value: Int): Payload =
    (suspend { immediateSuspendAdapter(value) })()

private suspend fun delayedSuspendAdapter(): Payload = delayedSuspendStep()

private class RecordingCompletion : Continuation<Payload> {
    override val context: CoroutineContext = EmptyCoroutineContext
    var result: Result<Payload>? = null

    override fun resumeWith(result: Result<Payload>) {
        this.result = result
    }
}

private fun start(block: suspend () -> Payload): RecordingCompletion = RecordingCompletion().also {
    block.startCoroutine(it)
}

fun main() {
    val result = forwardOwnedResultThroughAliases()
    check(result.value == 42)
    check(forwardNestedArrayResult().size == 32)
    check(forwardInterceptedResult(ContinuationLike()) is ContinuationLike)
    check(forwardAllOwnedBranchResults(true).value == 42)
    check(forwardAllOwnedBranchResults(false).value == 7)
    val existing = Payload(11)
    check(retainMixedBorrowedBranchResult(false, existing) === existing)
    check(retainAcrossThrowingSuffix(false).value == 42)
    val held = Payload(13)
    check(retainExternalResult(Holder(held)) === held)

    val immediate = start { immediateSuspendAdapter(17) }
    check(immediate.result?.getOrThrow()?.value == 17)
    val throwing = start { throwingDirectSuspendAdapter(-1) }
    check(throwing.result?.isFailure == true)
    check(start { mixedSuspendAdapter(-23) }.result?.getOrThrow()?.value == 23)
    check(start { mixedSuspendAdapter(24) }.result?.getOrThrow()?.value == 24)
    println("OK")
}
