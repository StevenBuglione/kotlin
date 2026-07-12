@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(kotlin.native.internal.InternalForKotlinNative::class)

import kotlin.native.internal.GCUnsafeCall
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

private class Payload(val value: Int)

private fun produce(): Payload = Payload(42)
private fun produceOther(): Payload = Payload(7)

// CHECK-LABEL: "kfun:forwardOwnedResultThroughAliases#internal"
private fun forwardOwnedResultThroughAliases(): Payload {
    // CHECK: call %struct.ObjHeader* @"kfun:produce#internal"(%struct.ObjHeader** %0)
    val owned = produce()
    val firstAlias = owned
    val returnedAlias = firstAlias
    // CHECK-NOT: call void @UpdateReturnRef
    // CHECK: ret %struct.ObjHeader*
    return returnedAlias
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:forwardNestedArrayResult#internal"
private fun forwardNestedArrayResult(): ByteArray = run {
    run {
        // The allocation ABI initializes the function result slot with +1 ownership. Both inline
        // returnable blocks forward that exact slot and must not update it again.
        // CHECK: call %struct.ObjHeader* @AllocArrayInstance({{%struct.TypeInfo\*|%struct.TypeInfoOpaque\*}} {{[^,]+}}, i32 32, %struct.ObjHeader** %0)
        // CHECK-NOT: call void @UpdateReturnRef
        ByteArray(32)
    }
// CHECK: ret %struct.ObjHeader*
}

private class ContinuationLike {
    fun intercepted(): ContinuationLike = this
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:forwardInterceptedResult#internal"
private fun forwardInterceptedResult(value: ContinuationLike): ContinuationLike = run {
    // This is the same call/result-slot shape as the coroutine `intercepted()` path: the direct
    // Kotlin call writes +1 into %1, so the transparent inline return must not copy it back.
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:ContinuationLike.intercepted#internal"(%struct.ObjHeader* %0, %struct.ObjHeader** %1)
    // CHECK-NOT: call void @UpdateReturnRef
    value.intercepted()
// CHECK: ret %struct.ObjHeader*
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:forwardAllOwnedBranchResults#internal"
private fun forwardAllOwnedBranchResults(selectFirst: Boolean): Payload =
    if (selectFirst) {
        // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:produce#internal"(%struct.ObjHeader** %1)
        produce()
    } else {
        // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:produceOther#internal"(%struct.ObjHeader** %1)
        produceOther()
    }
// CHECK-NOT: call void @UpdateReturnRef
// CHECK: ret %struct.ObjHeader*

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:retainMixedBorrowedBranchResult#internal"
private fun retainMixedBorrowedBranchResult(selectNew: Boolean, existing: Payload): Payload =
    if (selectNew) produce() else existing
// A mixed owned/borrowed join is deliberately not authorized; the borrowed edge must acquire +1.
// CHECK: call void @UpdateReturnRef(%struct.ObjHeader** %2,
// CHECK: ret %struct.ObjHeader*

@GCUnsafeCall("ReadHeapRefNoLock")
private external fun readHeapRefNoLock(holder: Any, index: Int): Payload

private class Holder(val first: Payload)

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:retainExternalResult#internal"
private fun retainExternalResult(holder: Holder): Payload = readHeapRefNoLock(holder, 0)
// The external object getter can overwrite the exact result slot, but is deliberately ineligible
// for establishing a forwarding proof. The final update must remain conservative.
// CHECK: {{call|invoke}} %struct.ObjHeader* @ReadHeapRefNoLock(%struct.ObjHeader* %0, i32 0, %struct.ObjHeader** %1)
// CHECK: call void @UpdateReturnRef(%struct.ObjHeader** %1,
// CHECK: ret %struct.ObjHeader*

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:retainAcrossThrowingSuffix#internal"
private fun retainAcrossThrowingSuffix(shouldThrow: Boolean): Payload {
    val produced = produce()
    if (shouldThrow) error("suffix")
    // The producer cannot target the function return slot across a potentially throwing suffix.
    // CHECK: call void @UpdateReturnRef(%struct.ObjHeader** %1,
    return produced
}

private suspend fun suspendStep(value: Int): Payload = suspendCoroutine { continuation ->
    continuation.resumeWith(Result.success(Payload(value)))
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:directSuspendAdapter#internal"
private suspend fun directSuspendAdapter(value: Int): Payload = suspendStep(value)
// The lowered adapter and its direct lowered callee use the exact same object-result slot. The
// callee establishes +1 ownership on its normal edge, so the adapter must not copy it back.
// CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:suspendStep#internal"({{.*}}%struct.ObjHeader** %[[DIRECT_SLOT:[0-9]+]])
// CHECK-NOT: call void @UpdateReturnRef(%struct.ObjHeader** %[[DIRECT_SLOT]],
// CHECK: ret %struct.ObjHeader*

private suspend fun throwingSuspendStep(value: Int): Payload {
    if (value < 0) error("throw before result")
    return Payload(value)
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:throwingDirectSuspendAdapter#internal"
private suspend fun throwingDirectSuspendAdapter(value: Int): Payload = throwingSuspendStep(value)
// A selected direct callee may unwind before initializing the result slot. Only its normal edge
// carries the ownership fact; the landing path must remain intact and must not synthesize a result.
// CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:throwingSuspendStep#internal"({{.*}}%struct.ObjHeader** %[[THROWING_SLOT:[0-9]+]])
// CHECK-NOT: call void @UpdateReturnRef(%struct.ObjHeader** %[[THROWING_SLOT]],
// CHECK: ret %struct.ObjHeader*

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:mixedSuspendAdapter#internal"
private suspend fun mixedSuspendAdapter(value: Int): Payload {
    if (value < 0) return Payload(-value)
    return suspendStep(value)
}
// The direct tail edge owns the exact result slot, but the ordinary allocation edge is not marked
// by that call proof. The shared epilogue therefore remains conservative for the mixed join.
// CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:suspendStep#internal"
// CHECK: call void @UpdateReturnRef
// CHECK: ret %struct.ObjHeader*

private suspend fun lambdaSuspendAdapter(value: Int): Payload =
    (suspend { directSuspendAdapter(value) })()

private fun observePayload(payload: Payload): Int = payload.value

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:$differentSlotSuspendAdapterCOROUTINE$0.invokeSuspend#internal"
private suspend fun differentSlotSuspendAdapter(value: Int): Payload {
    val result = suspendStep(value)
    observePayload(result)
    return result
}
// A value used after the call needs its own owning slot; returning it still updates the function
// result slot and must not inherit the exact-tail-call fact.
// CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:suspendStep#internal"
// CHECK: call void @UpdateReturnRef
// CHECK: ret %struct.ObjHeader*

private interface SuspendSource {
    suspend fun load(value: Int): Payload
}

private class SuspendSourceImpl : SuspendSource {
    override suspend fun load(value: Int): Payload = suspendStep(value)
}

private class OtherSuspendSourceImpl : SuspendSource {
    override suspend fun load(value: Int): Payload = suspendStep(value + 1)
}

private var selectOtherSuspendSource = false

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:virtualSuspendAdapter#internal"
private suspend fun virtualSuspendAdapter(source: SuspendSource, value: Int): Payload = source.load(value)
// Even when global hierarchy analysis expands the virtual call to known implementations, the
// source call was overridable and cannot establish one exact result-slot ownership fact.
// CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:SuspendSourceImpl.load#internal"
// CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:OtherSuspendSourceImpl.load#internal"
// CHECK: call void @UpdateReturnRef
// CHECK: ret %struct.ObjHeader*

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:externalSuspendAdapter#internal"
private suspend fun externalSuspendAdapter(holder: Holder): Payload = readHeapRefNoLock(holder, 0)
// CHECK: {{call|invoke}} %struct.ObjHeader* @ReadHeapRefNoLock
// CHECK: call void @UpdateReturnRef
// CHECK: ret %struct.ObjHeader*

private var suspendFinallyCount = 0

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:$exceptionalSuspendAdapterCOROUTINE$1.invokeSuspend#internal"
private suspend fun exceptionalSuspendAdapter(value: Int): Payload = try {
    suspendStep(value)
} finally {
    suspendFinallyCount++
}
// A try/finally boundary keeps its ordinary return update and unwind cleanup.
// CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:suspendStep#internal"
// CHECK: call void @UpdateReturnRef
// CHECK: ret %struct.ObjHeader*

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:lambdaSuspendAdapter$lambda$0#internal"
// The compiler-generated suspend lambda adapter is also eligible only when it resolves to a
// direct lowered invoke and forwards the enclosing function's exact result slot.
// CHECK-NOT: call void @UpdateReturnRef
// CHECK: ret %struct.ObjHeader*

private object IgnoringCompletion : Continuation<Payload> {
    override val context: CoroutineContext = EmptyCoroutineContext
    override fun resumeWith(result: Result<Payload>) = Unit
}

fun main() {
    check(forwardOwnedResultThroughAliases().value == 42)
    check(forwardNestedArrayResult().size == 32)
    check(forwardInterceptedResult(ContinuationLike()) is ContinuationLike)
    check(forwardAllOwnedBranchResults(true).value == 42)
    check(forwardAllOwnedBranchResults(false).value == 7)
    val existing = Payload(11)
    check(retainMixedBorrowedBranchResult(false, existing) === existing)
    check(retainAcrossThrowingSuffix(false).value == 42)
    val held = Payload(13)
    check(retainExternalResult(Holder(held)) === held)
    listOf<Any>(
        ::lambdaSuspendAdapter,
        ::throwingDirectSuspendAdapter,
        ::mixedSuspendAdapter,
        ::differentSlotSuspendAdapter,
        ::virtualSuspendAdapter,
        ::externalSuspendAdapter,
        ::exceptionalSuspendAdapter,
    ).hashCode()
    val suspendSource: SuspendSource =
        if (selectOtherSuspendSource) OtherSuspendSourceImpl() else SuspendSourceImpl()
    suspend { virtualSuspendAdapter(suspendSource, 29) }.startCoroutine(IgnoringCompletion)
}
