@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(kotlin.native.internal.InternalForKotlinNative::class)

import kotlin.native.internal.GCUnsafeCall

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
    println("OK")
}
