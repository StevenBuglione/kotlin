import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

// OPT-LABEL: define %struct.ObjHeader* @"kfun:kotlin.coroutines.native.internal.RestrictedContinuationImpl#<get-context>(){}kotlin.coroutines.CoroutineContext"
// OPT-NOT: call void @UpdateReturnRef
// OPT: call void @MoveReferenceIntoReturnSlotArc
// OPT-NOT: call void @UpdateReturnRef
// OPT: ret %struct.ObjHeader*

// DEBUG-LABEL: define %struct.ObjHeader* @"kfun:kotlin.coroutines.native.internal.RestrictedContinuationImpl#<get-context>(){}kotlin.coroutines.CoroutineContext"
// DEBUG-NOT: call void @MoveReferenceIntoReturnSlotArc
// DEBUG: call void @UpdateReturnRef
// DEBUG-NOT: call void @MoveReferenceIntoReturnSlotArc
// DEBUG: ret %struct.ObjHeader*

// DIAGNOSTIC-LABEL: define %struct.ObjHeader* @"kfun:kotlin.coroutines.native.internal.RestrictedContinuationImpl#<get-context>(){}kotlin.coroutines.CoroutineContext"
// DIAGNOSTIC-NOT: call void @MoveReferenceIntoReturnSlotArc
// DIAGNOSTIC: call void @UpdateReturnRef
// DIAGNOSTIC-NOT: call void @MoveReferenceIntoReturnSlotArc
// DIAGNOSTIC: ret %struct.ObjHeader*

// STRICT-LABEL: define %struct.ObjHeader* @"kfun:kotlin.coroutines.native.internal.RestrictedContinuationImpl#<get-context>(){}kotlin.coroutines.CoroutineContext"
// STRICT-NOT: call void @MoveReferenceIntoReturnSlotArc
// STRICT-NOT: call void @UpdateReturnRef
// STRICT: store %struct.ObjHeader* {{%[0-9]+}}, %struct.ObjHeader** {{%[0-9]+}}, align 8
// STRICT-NOT: call void @MoveReferenceIntoReturnSlotArc
// STRICT-NOT: call void @UpdateReturnRef
// STRICT: ret %struct.ObjHeader*

private fun runRestricted(value: Int): Int {
    var result = -1
    suspend { value }.startCoroutine(object : Continuation<Int> {
        override val context: CoroutineContext = EmptyCoroutineContext
        override fun resumeWith(outcome: Result<Int>) {
            result = outcome.getOrThrow()
        }
    })
    return result
}

fun main() {
    val checksum = runRestricted(42)
    check(checksum == 42)
    println("ARC_EMPTY_CONTEXT_IMMORTAL_RETURN_OK checksum=$checksum")
}
