@file:Suppress("DEPRECATION")

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.native.arc.ArcDeinit
import kotlin.native.concurrent.FreezableAtomicReference
import kotlin.concurrent.AtomicReference

private var deinitCount = 0

private class LockedPayload(val value: Int) {
    @ArcDeinit
    private fun deinit() {
        deinitCount++
    }
}

// CHECK-LABEL: define %struct.ObjHeader* @"kfun:kotlin.native.concurrent.FreezableAtomicReference#<get-value>(){}1:0"
// CHECK-SAME: {{.*}}%struct.ObjHeader** [[PRODUCER_SLOT:%[-a-zA-Z$._0-9]+]])
// CHECK-COUNT-1: call %struct.ObjHeader* @Kotlin_AtomicReference_get({{.*}}%struct.ObjHeader** [[PRODUCER_SLOT]])
// CHECK-NOT: call void @UpdateReturnRef{{.*}}[[PRODUCER_SLOT]]
// CHECK: ret %struct.ObjHeader*
// DEBUG-LABEL: define %struct.ObjHeader* @"kfun:kotlin.native.concurrent.FreezableAtomicReference#<get-value>(){}1:0"
// DEBUG-SAME: {{.*}}%struct.ObjHeader** [[DEBUG_PRODUCER_SLOT:%[-a-zA-Z$._0-9]+]])
// DEBUG-COUNT-1: call %struct.ObjHeader* @Kotlin_AtomicReference_get({{.*}}%struct.ObjHeader** [[DEBUG_PRODUCER_SLOT]])
// DEBUG-COUNT-1: call void @UpdateReturnRef(%struct.ObjHeader** [[DEBUG_PRODUCER_SLOT]],
// DEBUG: ret %struct.ObjHeader*
// STRICT-LABEL: define %struct.ObjHeader* @"kfun:kotlin.native.concurrent.FreezableAtomicReference#<get-value>(){}1:0"
// STRICT-SAME: {{.*}}%struct.ObjHeader** [[STRICT_PRODUCER_SLOT:%[-a-zA-Z$._0-9]+]])
// STRICT: call %struct.ObjHeader* @Kotlin_AtomicReference_get({{.*}}%struct.ObjHeader** [[STRICT_PRODUCER_SLOT]])
// STRICT: store %struct.ObjHeader*
// STRICT: ret %struct.ObjHeader*

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:lockedReadSpill#internal"
private suspend fun lockedReadSpill(reference: FreezableAtomicReference<LockedPayload>): Int {
    // CHECK-COUNT-1: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.native.concurrent.FreezableAtomicReference#<get-value>(){}1:0"({{.*}}%struct.ObjHeader** [[SPILL:%[-a-zA-Z$._0-9]+]])
    // CHECK-NOT: call void @UpdateStackRef
    val result = reference.value
    // CHECK: call void @LeaveFrame
    // CHECK: ret %struct.ObjHeader*
    return result.value
}

// MODERN-LABEL: define %struct.ObjHeader* @"kfun:kotlin.concurrent.AtomicReference#<get-value>(){}1:0"
// MODERN-SAME: {{.*}}%struct.ObjHeader** [[MODERN_SLOT:%[-a-zA-Z$._0-9]+]])
// MODERN-COUNT-1: call %struct.ObjHeader* @Kotlin_AtomicReference_get({{.*}}%struct.ObjHeader** [[MODERN_SLOT]])
// MODERN-NOT: call void @UpdateReturnRef
// MODERN: ret %struct.ObjHeader*
private fun modernRead(reference: AtomicReference<LockedPayload>): Int = reference.value.value

private class AtomicReferenceLookalike<T>(private val stored: T) {
    val value: T get() = getImpl().also { check(it === stored) }
    private fun getImpl(): T = stored
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:AtomicReferenceLookalike.<get-value>#internal"
// CHECK-NOT: call %struct.ObjHeader* @Kotlin_AtomicReference_get
// CHECK: call void @UpdateReturnRef
// CHECK: ret %struct.ObjHeader*
private fun lookalikeRead(reference: AtomicReferenceLookalike<LockedPayload>): Int = reference.value.value

private class IntCompletion : Continuation<Int> {
    override val context: CoroutineContext = EmptyCoroutineContext
    var result: Result<Int>? = null
    override fun resumeWith(result: Result<Int>) {
        this.result = result
    }
}

private fun runLocked(reference: FreezableAtomicReference<LockedPayload>): Int {
    val completion = IntCompletion()
    suspend { lockedReadSpill(reference) }.startCoroutine(completion)
    return completion.result!!.getOrThrow()
}

private fun makeReference(value: Int): FreezableAtomicReference<LockedPayload> =
    FreezableAtomicReference(LockedPayload(value))

fun main() {
    val reference = makeReference(41)
    check(runLocked(reference) == 41)
    reference.value = LockedPayload(42)
    check(deinitCount == 1)
    check(runLocked(reference) == 42)
    check(modernRead(AtomicReference(LockedPayload(43))) == 43)
    check(lookalikeRead(AtomicReferenceLookalike(LockedPayload(44))) == 44)
    println("OK")
}
