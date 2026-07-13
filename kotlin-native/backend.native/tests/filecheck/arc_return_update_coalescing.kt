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

// BEFORE-LABEL: define internal %struct.ObjHeader* @"kfun:$coroutineHotPath$lambda$0$FUNCTION_REFERENCE$0.invoke#internal"
// BEFORE: call fastcc void @MoveReferenceIntoReturnSlotArc(%struct.ObjHeader** %[[BEFORE_RESULT_SLOT:[0-9]+]], %struct.ObjHeader* %[[BEFORE_RESULT:[0-9]+]])
// BEFORE: "kfun:coroutineHotPath$lambda$0#internal.exit":
// BEFORE-NOT: call fastcc void @UpdateReturnRefRelaxed(%struct.ObjHeader** %[[BEFORE_RESULT_SLOT]], %struct.ObjHeader* %[[BEFORE_RESULT]])
// BEFORE: ret %struct.ObjHeader* %[[BEFORE_RESULT]]

// COALESCE-LABEL: define internal %struct.ObjHeader* @"kfun:$coroutineHotPath$lambda$0$FUNCTION_REFERENCE$0.invoke#internal"
// The primitive source result is boxed behind a lowered-suspend returnable block. Its object-result
// ABI type must remain authoritative so the exact tail call forwards ownership into this slot.
// COALESCE: call fastcc void @MoveReferenceIntoReturnSlotArc(%struct.ObjHeader** %[[RESULT_SLOT:[0-9]+]], %struct.ObjHeader* %[[RESULT:[0-9]+]])
// COALESCE: "kfun:coroutineHotPath$lambda$0#internal.exit":
// COALESCE: call void @llvm.lifetime.end
// COALESCE-NOT: call fastcc void @UpdateReturnRefRelaxed(%struct.ObjHeader** %[[RESULT_SLOT]], %struct.ObjHeader* %[[RESULT]])
// COALESCE: ret %struct.ObjHeader* %[[RESULT]]

fun main() {
    var checksum = 0
    repeat(8) { checksum += coroutineHotPath(it) }
    check(checksum == 36)
}
