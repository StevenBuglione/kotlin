import kotlin.native.arc.ArcDeinit

private var throwingDeinitCount = 0

private class Fluent {
    private var length = 0

    fun append(value: String): Fluent {
        length += value.length
        return this
    }

    fun finish(): Int = length
}

private class Ambiguous {
    fun append(value: String): Ambiguous {
        if (value.isEmpty()) return this
        return Ambiguous()
    }

    fun finish(): Int = 1
}

private class ExpectedAppendFailure : Exception()
private class ExpectedConsumerFailure : Exception()

private class ThrowingFluent {
    @ArcDeinit
    private fun deinit() {
        throwingDeinitCount++
    }

    fun append(value: String): ThrowingFluent {
        if (value == "throw") throw ExpectedAppendFailure()
        return this
    }

    fun finish(): Int = 0
}

// CHECK-LABEL: define internal i32 @"kfun:borrowFluentReceiver#internal"
private fun borrowFluentReceiver(): Int {
    // Every fluent result is the exact already-rooted receiver. All append calls must therefore
    // reuse one object-result slot instead of allocating one owning temporary per link.
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:Fluent.append#internal"({{.*}}%struct.ObjHeader** [[FLUENT_SLOT:%[-a-zA-Z$._0-9]+]])
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:Fluent.append#internal"({{.*}}%struct.ObjHeader** [[FLUENT_SLOT]])
    // CHECK: {{call|invoke}} i32 @"kfun:Fluent.finish#internal"
    return Fluent().append("arc").append("-receiver").finish()
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:keepAmbiguousOwned#internal"
private fun keepAmbiguousOwned(): Ambiguous {
    // A different normal return invalidates the summary and retains the ordinary result slot.
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:Ambiguous.append#internal"
    return Ambiguous().append("owned")
}

// CHECK-LABEL: define internal i32 @"kfun:consumeAmbiguous#internal"
// CHECK: [[AMBIGUOUS_FIRST:%[-a-zA-Z$._0-9]+]] = {{call|invoke}} %struct.ObjHeader* @"kfun:Ambiguous.append#internal"({{.*}}%struct.ObjHeader** [[AMBIGUOUS_FIRST_SLOT:%[-a-zA-Z$._0-9]+]])
// CHECK-NOT: @"kfun:Ambiguous.append#internal"({{.*}}%struct.ObjHeader** [[AMBIGUOUS_FIRST_SLOT]])
// CHECK: [[AMBIGUOUS_SECOND:%[-a-zA-Z$._0-9]+]] = {{call|invoke}} %struct.ObjHeader* @"kfun:Ambiguous.append#internal"({{.*}}%struct.ObjHeader** [[AMBIGUOUS_SECOND_SLOT:%[-a-zA-Z$._0-9]+]])
// CHECK: {{call|invoke}} i32 @"kfun:Ambiguous.finish#internal"(%struct.ObjHeader* [[AMBIGUOUS_SECOND]])
private fun consumeAmbiguous(): Int = Ambiguous().append("first").append("second").finish()

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:returnFluent#internal"
// A real caller-supplied return slot wins over receiver seeding/reuse.
// CHECK-SAME: (%struct.ObjHeader** [[CALLER_RESULT:%[-a-zA-Z$._0-9]+]])
// CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:Fluent.append#internal"({{.*}}%struct.ObjHeader** [[CALLER_RESULT]])
private fun returnFluent(): Fluent = Fluent().append("returned")

private fun unwindBorrowedReceiver() {
    ThrowingFluent().append("ok").append("throw").finish()
}

private fun unwindZeroedSeedBeforeReturn() {
    ThrowingFluent().append("throw").finish()
}

private fun throwingConsumer(@Suppress("UNUSED_PARAMETER") value: ThrowingFluent): Nothing =
    throw ExpectedConsumerFailure()

private fun unwindAfterSuccessfulBorrowedReturn() {
    throwingConsumer(ThrowingFluent().append("ok"))
}

fun main() {
    check(borrowFluentReceiver() == 12)
    keepAmbiguousOwned()
    check(returnFluent().finish() == 8)
    try {
        unwindZeroedSeedBeforeReturn()
        error("sentinel: missing seed exception")
    } catch (_: ExpectedAppendFailure) {
        check(throwingDeinitCount == 1)
    }
    try {
        unwindBorrowedReceiver()
        error("sentinel: missing append exception")
    } catch (_: ExpectedAppendFailure) {
        check(throwingDeinitCount == 2)
    }
    try {
        unwindAfterSuccessfulBorrowedReturn()
        error("sentinel: missing consumer exception")
    } catch (_: ExpectedConsumerFailure) {
        check(throwingDeinitCount == 3)
    }
    check(consumeAmbiguous() == 1)
    println("OK")
}
