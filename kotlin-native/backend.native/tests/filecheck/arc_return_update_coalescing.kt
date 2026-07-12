import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

private suspend fun suspendStep(value: Int): Int =
    suspendCoroutine { continuation -> continuation.resume(value + 1) }

private fun coroutineHotPath(value: Int): Int {
    var outcome: Result<Int>? = null
    suspend { suspendStep(value) }.startCoroutine(object : Continuation<Int> {
        override val context: CoroutineContext = EmptyCoroutineContext
        override fun resumeWith(result: Result<Int>) {
            outcome = result
        }
    })
    return outcome!!.getOrThrow()
}

// BEFORE-LABEL: define internal void @"kfun:kotlin.coroutines.SafeContinuation#resumeWith
// BEFORE: call fastcc void @UpdateReturnRefRelaxed(%struct.ObjHeader** nonnull %[[BEFORE_SLOT:[0-9]+]], %struct.ObjHeader* %[[BEFORE_VALUE:[0-9]+]])
// BEFORE-NEXT: call fastcc void @UpdateReturnRefRelaxed(%struct.ObjHeader** nonnull %[[BEFORE_SLOT]], %struct.ObjHeader* %[[BEFORE_VALUE]])
// BEFORE-LABEL: define internal %struct.ObjHeader* @"kfun:$coroutineHotPath$lambda$0$FUNCTION_REFERENCE$0.invoke#internal"
// BEFORE: ret %struct.ObjHeader*

// COALESCE-LABEL: define internal void @"kfun:kotlin.coroutines.SafeContinuation#resumeWith
// COALESCE: call fastcc void @UpdateReturnRefRelaxed(%struct.ObjHeader** nonnull %[[AFTER_SLOT:[0-9]+]], %struct.ObjHeader* %[[AFTER_VALUE:[0-9]+]])
// COALESCE-NOT: call fastcc void @UpdateReturnRefRelaxed(%struct.ObjHeader** nonnull %[[AFTER_SLOT]], %struct.ObjHeader* %[[AFTER_VALUE]])
// COALESCE: ret void
// COALESCE-LABEL: define internal %struct.ObjHeader* @"kfun:$coroutineHotPath$lambda$0$FUNCTION_REFERENCE$0.invoke#internal"
// The same result reaches three return updates, but frame cleanup and lifetime boundaries separate
// them. The adjacent-pair pass must keep all three rather than widening through those barriers.
// COALESCE: "kfun:coroutineHotPath$lambda$0#internal.exit":
// COALESCE: call fastcc void @UpdateReturnRefRelaxed(%struct.ObjHeader** %[[RESULT_SLOT:[0-9]+]], %struct.ObjHeader* %[[RESULT:[0-9]+]])
// COALESCE: call void @llvm.lifetime.end
// COALESCE: call fastcc void @UpdateReturnRefRelaxed(%struct.ObjHeader** %[[RESULT_SLOT]], %struct.ObjHeader* %[[RESULT]])
// COALESCE: call void @llvm.lifetime.end
// COALESCE: call fastcc void @UpdateReturnRefRelaxed(%struct.ObjHeader** %[[RESULT_SLOT]], %struct.ObjHeader* %[[RESULT]])
// COALESCE-NOT: call fastcc void @UpdateReturnRefRelaxed(%struct.ObjHeader** %[[RESULT_SLOT]], %struct.ObjHeader* %[[RESULT]])
// COALESCE: ret %struct.ObjHeader*

fun main() {
    var checksum = 0
    repeat(8) { checksum += coroutineHotPath(it) }
    check(checksum == 36)
}
