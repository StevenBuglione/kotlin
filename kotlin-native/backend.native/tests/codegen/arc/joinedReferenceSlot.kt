import kotlin.native.arc.ArcDeinit

private val deinitCounts = IntArray(128)

private class Payload(val value: Int) {
    @ArcDeinit
    private fun deinit() {
        deinitCounts[value]++
    }
}

private var sink: Payload? = null

private fun produceFirst(): Payload = Payload(41)
private fun produceSecond(): Payload = Payload(42)
private fun produceConsumerFirst(): Payload = Payload(61)
private fun produceConsumerSecond(): Payload = Payload(62)

private class ProducerFailure : Throwable()
private class ConsumerFailure : Throwable()

private fun produceThenThrow(value: Int): Payload {
    val payload = Payload(value)
    check(payload.value == value)
    throw ProducerFailure()
}

private fun consume(value: Payload) {
    sink = value
}

private fun consumeThenThrow(value: Payload) {
    check(value.value >= 0)
    throw ConsumerFailure()
}

private fun checkSinkValue(expected: Int) {
    check(sink?.value == expected)
}

// CHECK-LABEL: define internal void @"kfun:joinedLocal#internal"
private fun joinedLocal(first: Boolean) {
    // Both mutually exclusive +1 results must initialize the same owning local slot.
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:produceFirst#internal"(%struct.ObjHeader** [[JOIN_SLOT:%[-a-zA-Z$._0-9]+]])
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:produceSecond#internal"(%struct.ObjHeader** [[JOIN_SLOT]])
    val joined = if (first) produceFirst() else produceSecond()
    // The when phi aliases the selected object already owned by JOIN_SLOT; merging must not retain.
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: {{call|invoke}} void @"kfun:consume#internal"
    consume(joined)
    // Normal frame cleanup releases the one selected result exactly once.
    // CHECK: call void @LeaveFrame
}

private fun joinedProducerThrows(first: Boolean) {
    val joined = if (first) produceThenThrow(51) else produceThenThrow(52)
    consume(joined)
}

private fun joinedConsumerThrows(first: Boolean) {
    val joined = if (first) produceConsumerFirst() else produceConsumerSecond()
    consumeThenThrow(joined)
}

fun main() {
    joinedLocal(true)
    checkSinkValue(41)
    joinedLocal(false)
    checkSinkValue(42)
    sink = null
    check(deinitCounts[41] == 1) { "first normal result deinitialized ${deinitCounts[41]} times" }
    check(deinitCounts[42] == 1) { "second normal result deinitialized ${deinitCounts[42]} times" }

    try {
        joinedProducerThrows(true)
        error("first producer unexpectedly returned")
    } catch (_: ProducerFailure) {
    }
    try {
        joinedProducerThrows(false)
        error("second producer unexpectedly returned")
    } catch (_: ProducerFailure) {
    }
    check(deinitCounts[51] == 1) { "first throwing producer result deinitialized ${deinitCounts[51]} times" }
    check(deinitCounts[52] == 1) { "second throwing producer result deinitialized ${deinitCounts[52]} times" }

    try {
        joinedConsumerThrows(true)
        error("first consumer unexpectedly returned")
    } catch (_: ConsumerFailure) {
    }
    try {
        joinedConsumerThrows(false)
        error("second consumer unexpectedly returned")
    } catch (_: ConsumerFailure) {
    }
    check(deinitCounts[61] == 1) { "first consumer-throw result deinitialized ${deinitCounts[61]} times" }
    check(deinitCounts[62] == 1) { "second consumer-throw result deinitialized ${deinitCounts[62]} times" }
    println("OK")
}
