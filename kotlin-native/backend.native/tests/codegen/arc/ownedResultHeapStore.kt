import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

// OPT-LABEL: define internal void @"kfun:object-1.resumeWith#internal"
// OPT-NOT: call void @UpdateHeapRef
// OPT: call void @MoveReferenceIntoHeapSlotArc
// OPT-NOT: call void @MoveReferenceIntoHeapSlotArc
// OPT-NOT: call void @UpdateHeapRef
// OPT: ret void

// DEBUG-LABEL: define internal void @"kfun:object-1.resumeWith#internal"
// DEBUG-NOT: call void @MoveReferenceIntoHeapSlotArc
// DEBUG: call void @UpdateHeapRef
// DEBUG-NOT: call void @MoveReferenceIntoHeapSlotArc
// DEBUG: ret void

// DIAGNOSTIC-LABEL: define internal void @"kfun:object-1.resumeWith#internal"
// DIAGNOSTIC-NOT: call void @MoveReferenceIntoHeapSlotArc
// DIAGNOSTIC: call void @UpdateHeapRef
// DIAGNOSTIC-NOT: call void @MoveReferenceIntoHeapSlotArc
// DIAGNOSTIC: ret void

// STRICT-LABEL: define internal void @"kfun:object-1.resumeWith#internal"
// STRICT-NOT: call void @MoveReferenceIntoHeapSlotArc
// STRICT: call void @UpdateHeapRef
// STRICT-NOT: call void @MoveReferenceIntoHeapSlotArc
// STRICT: ret void

private fun runOwnedResultHeapStore(value: Int): Int {
    var outcome: Result<Int>? = null
    suspend { value }.startCoroutine(object : Continuation<Int> {
        override val context: CoroutineContext = EmptyCoroutineContext

        override fun resumeWith(result: Result<Int>) {
            outcome = result
        }
    })
    return outcome!!.getOrThrow()
}

fun main() {
    val checksum = runOwnedResultHeapStore(42)
    check(checksum == 42)
    println("ARC_OWNED_RESULT_HEAP_STORE_OK checksum=$checksum")
}
