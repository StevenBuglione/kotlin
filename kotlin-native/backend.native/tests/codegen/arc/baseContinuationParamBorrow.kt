import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

private class ExpectedFailure(val marker: Int) : Throwable()

private suspend fun step(value: Int, fail: Boolean): Int = suspendCoroutine { continuation ->
    if (fail) {
        continuation.resumeWith(Result.failure(ExpectedFailure(value)))
    } else {
        continuation.resumeWith(Result.success(value + 1))
    }
}

private suspend fun nested(value: Int, fail: Boolean): Int = step(value, fail) + 1

private fun runNested(value: Int, fail: Boolean): Result<Int> {
    var completed: Result<Int>? = null
    suspend { nested(value, fail) }.startCoroutine(object : Continuation<Int> {
        override val context: CoroutineContext = EmptyCoroutineContext
        override fun resumeWith(result: Result<Int>) {
            completed = result
        }
    })
    return completed ?: error("continuation did not complete synchronously")
}

// The canonical stdlib function is compiled in the same closed-world module for FileCheck. Its
// mutable `param` stack slot owns +1 across the virtual invoke and both of its successors.
// CHECK-LABEL: define void @"kfun:kotlin.coroutines.native.internal.BaseContinuationImpl#resumeWith(kotlin.Result<kotlin.Any?>){}"
// CHECK: [[PARAM:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %param
// CHECK-NOT: call void @UpdateStackRef
// CHECK: invoke %struct.ObjHeader* {{%[0-9]+}}(%struct.ObjHeader* {{%[0-9]+}}, %struct.ObjHeader* [[PARAM]], %struct.ObjHeader** {{%[0-9]+}})

// Debug and strict builds must retain the ordinary argument promotion.
// DEBUG-LABEL: define void @"kfun:kotlin.coroutines.native.internal.BaseContinuationImpl#resumeWith(kotlin.Result<kotlin.Any?>){}"
// DEBUG: [[DEBUG_PARAM:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %param
// DEBUG: call void @UpdateStackRef
// DEBUG: invoke %struct.ObjHeader*
// STRICT-LABEL: define void @"kfun:kotlin.coroutines.native.internal.BaseContinuationImpl#resumeWith(kotlin.Result<kotlin.Any?>){}"
// STRICT: [[STRICT_PARAM:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %param
// STRICT: store %struct.ObjHeader* [[STRICT_PARAM]], %struct.ObjHeader** {{%[0-9]+}}
// STRICT: invoke %struct.ObjHeader*

fun main() {
    var checksum = 0L
    repeat(20_000) { value ->
        checksum += runNested(value, fail = false).getOrThrow()
        val failure = runNested(value, fail = true).exceptionOrNull()
        check(failure is ExpectedFailure && failure.marker == value)
    }
    check(checksum == 200030000L)
}
