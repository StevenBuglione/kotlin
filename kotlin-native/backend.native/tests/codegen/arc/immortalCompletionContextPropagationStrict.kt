import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

// STRICT-LABEL: define internal void @"kfun:StrictCompletion.<init>#internal"
// STRICT-NOT: call void @MoveReferenceIntoReturnSlotArc
// STRICT: call void @UpdateHeapRef

// STRICT-LABEL: define internal %struct.ObjHeader* @"kfun:StrictCompletion.<get-context>#internal"
// STRICT-SAME: (%struct.ObjHeader* {{%[0-9]+}}, %struct.ObjHeader** [[STRICT_RETURN_SLOT:%[0-9]+]])
// STRICT-NOT: call void @MoveReferenceIntoReturnSlotArc
// STRICT: store %struct.ObjHeader* {{%[0-9]+}}, %struct.ObjHeader** [[STRICT_RETURN_SLOT]], align 8

private class StrictCompletion : Continuation<Int> {
    override val context: CoroutineContext = EmptyCoroutineContext

    override fun resumeWith(result: Result<Int>) = Unit
}

fun main() {
    check(StrictCompletion().context === EmptyCoroutineContext)
}
