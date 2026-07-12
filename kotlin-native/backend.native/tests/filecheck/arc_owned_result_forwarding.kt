@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(kotlin.native.internal.InternalForKotlinNative::class)

import kotlin.native.internal.GCUnsafeCall

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
}
