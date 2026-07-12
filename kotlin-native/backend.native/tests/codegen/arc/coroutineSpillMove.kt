@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.native.arc.ArcDeinit
import kotlin.native.internal.IntrinsicType
import kotlin.native.internal.TypedIntrinsic

private var deinitCount = 0

private class Payload(val value: Int) {
    @ArcDeinit
    private fun deinit() {
        deinitCount++
    }
}

private class ProducerFailure : Exception()

private fun produce(value: Int, fail: Boolean): Payload {
    val result = Payload(value)
    if (fail) throw ProducerFailure()
    return result
}

@TypedIntrinsic(IntrinsicType.IDENTITY)
private fun typedIdentity(value: Payload): Payload = value

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:typedIntrinsicNotMoved#internal"
// CHECK-NOT: call void @MoveReferenceIntoReturnSlotArc
// CHECK: ret %struct.ObjHeader*
private suspend fun typedIntrinsicNotMoved(value: Payload): Payload {
    val result = typedIdentity(value)
    return result
}

private open class VirtualProducer {
    open fun produce(value: Int): Payload = Payload(value)
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:virtualProducerNotMoved#internal"
// CHECK-NOT: call void @MoveReferenceIntoReturnSlotArc
// CHECK: ret %struct.ObjHeader*
private suspend fun virtualProducerNotMoved(producer: VirtualProducer): Payload {
    val result = producer.produce(7)
    return result
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:tryProducerNotMoved#internal"
// CHECK-NOT: call void @MoveReferenceIntoReturnSlotArc
// CHECK: ret %struct.ObjHeader*
private suspend fun tryProducerNotMoved(fail: Boolean): Payload {
    val result = try {
        produce(8, fail)
    } catch (_: ProducerFailure) {
        Payload(9)
    }
    return result
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:wrappedReturnNotMoved#internal"
// CHECK-NOT: call void @MoveReferenceIntoReturnSlotArc
// CHECK: ret %struct.ObjHeader*
private suspend fun wrappedReturnNotMoved(): Payload {
    val result = produce(10, false)
    return run { result }
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:coroutineSpillMove#internal"
// CHECK-SAME: {{.*}}%struct.ObjHeader** [[RETURN_SLOT:%[-a-zA-Z$._0-9]+]])
// DEBUG-LABEL: define internal %struct.ObjHeader* @"kfun:coroutineSpillMove#internal"
// DEBUG-NOT: call void @MoveReferenceIntoReturnSlotArc
// DEBUG: ret %struct.ObjHeader*
private suspend fun coroutineSpillMove(value: Int, fail: Boolean): Payload {
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:produce#internal"({{.*}}%struct.ObjHeader** [[SPILL_SLOT:%[-a-zA-Z$._0-9]+]])
    // CHECK-NOT: call void @UpdateStackRef
    val result = produce(value, fail)
    // CHECK: call void @MoveReferenceIntoReturnSlotArc(%struct.ObjHeader** [[RETURN_SLOT]], %struct.ObjHeader*
    // CHECK-NEXT: store %struct.ObjHeader* null, %struct.ObjHeader** [[SPILL_SLOT]]
    // CHECK-NOT: call void @UpdateReturnRef
    return result
    // CHECK: ret %struct.ObjHeader*
}

private class Completion : Continuation<Payload> {
    override val context: CoroutineContext = EmptyCoroutineContext
    var result: Result<Payload>? = null
    override fun resumeWith(result: Result<Payload>) {
        this.result = result
    }
}

private fun run(value: Int, fail: Boolean): Result<Payload> {
    val completion = Completion()
    suspend {
        // Keep the negative selector fixtures reachable without executing them.
        if (deinitCount == Int.MIN_VALUE) {
            typedIntrinsicNotMoved(Payload(0))
            virtualProducerNotMoved(VirtualProducer())
            tryProducerNotMoved(false)
            wrappedReturnNotMoved()
        }
        coroutineSpillMove(value, fail)
    }.startCoroutine(completion)
    return completion.result ?: error("coroutine did not complete synchronously")
}

private fun consumeSuccess(): Int = run(42, false).getOrThrow().value

fun main() {
    check(consumeSuccess() == 42)
    check(deinitCount == 1)
    try {
        run(-1, true).getOrThrow()
        error("sentinel: missing producer exception")
    } catch (_: ProducerFailure) {
        check(deinitCount == 2)
    }
    println("OK")
}
