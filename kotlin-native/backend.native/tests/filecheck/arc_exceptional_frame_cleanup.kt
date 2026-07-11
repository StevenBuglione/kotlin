import kotlin.native.arc.ArcDeinit

private class Payload {
    @ArcDeinit
    private fun deinit() {}
}

private class ExpectedFailure : Throwable()

private fun fail(value: Any): Nothing {
    value.hashCode()
    throw ExpectedFailure()
}

// CHECK-LABEL: "kfun:directExceptionalUnwind#internal"
private fun directExceptionalUnwind() {
    // CHECK: call void @EnterFrame([[DIRECT_FRAME:[^,]+]],
    val owned = Payload()
    // CHECK: invoke void @"kfun:fail#internal"
    // CHECK: unwind label %cleanup_landingpad
    fail(owned)
    // CHECK: cleanup_landingpad:
    // CHECK: landingpad
    // CHECK: call void @SetCurrentFrame([[DIRECT_FRAME]])
    // CHECK: call void @LeaveFrame([[DIRECT_FRAME]],
    // CHECK: resume
}

private class ConstructorExceptionalUnwind {
    private val owned = Payload()

    // CHECK-LABEL: define internal void @"kfun:ConstructorExceptionalUnwind.<init>#internal
    init {
        // CHECK: call void @EnterFrame([[CONSTRUCTOR_FRAME:[^,]+]],
        val local = Payload()
        // CHECK: invoke void @"kfun:fail#internal"
        // CHECK: unwind label %cleanup_landingpad
        fail(local)
        // CHECK: cleanup_landingpad:
        // CHECK: landingpad
        // CHECK: call void @SetCurrentFrame([[CONSTRUCTOR_FRAME]])
        // CHECK: call void @LeaveFrame([[CONSTRUCTOR_FRAME]],
        // CHECK: resume
    }
}

fun main() {
    try {
        directExceptionalUnwind()
    } catch (_: ExpectedFailure) {
    }
    try {
        ConstructorExceptionalUnwind()
    } catch (_: ExpectedFailure) {
    }
}
