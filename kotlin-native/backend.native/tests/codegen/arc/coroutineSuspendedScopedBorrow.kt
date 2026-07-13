import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine

// OPT-LABEL: define void @"kfun:kotlin.coroutines.native.internal.BaseContinuationImpl#resumeWith
// OPT-NOT: call %struct.ObjHeader* @"kfun:kotlin.coroutines.intrinsics#<get-COROUTINE_SUSPENDED>
// OPT: call void @CallInitGlobalPossiblyLock(i32* @"state_global$kotlin.coroutines.intrinsics.CoroutineSingletons", void ()* @"kfun:kotlin.coroutines.intrinsics.CoroutineSingletons.$init_global#internal")
// OPT: %[[SUSPENDED:[0-9]+]] = {{call|invoke}} %struct.ObjHeader* @Kotlin_Array_get_borrowed(%struct.ObjHeader* {{%[0-9]+}}, i32 0)
// OPT: icmp eq %struct.ObjHeader* {{%[0-9]+}}, %[[SUSPENDED]]
// OPT-NOT: call %struct.ObjHeader* @"kfun:kotlin.coroutines.intrinsics#<get-COROUTINE_SUSPENDED>

// OPT-OWNED-LABEL: define internal %struct.ObjHeader* @"kfun:ownedSuspended#internal"(%struct.ObjHeader** %0)
// OPT-OWNED-NOT: call %struct.ObjHeader* @Kotlin_Array_get_borrowed
// OPT-OWNED: call %struct.ObjHeader* @"kfun:kotlin.coroutines.intrinsics#<get-COROUTINE_SUSPENDED>(){}kotlin.Any"(%struct.ObjHeader** %0)
// OPT-OWNED-NOT: call %struct.ObjHeader* @Kotlin_Array_get_borrowed
// OPT-OWNED: ret %struct.ObjHeader*

// OWNED-ABI-LABEL: define %struct.ObjHeader* @"kfun:kotlin.coroutines.intrinsics#<get-COROUTINE_SUSPENDED>(){}kotlin.Any"(%struct.ObjHeader** %0)
// OWNED-ABI: call %struct.ObjHeader* @"kfun:kotlin.coroutines.intrinsics.CoroutineSingletons#$getEnumAt#static(kotlin.Int){}kotlin.coroutines.intrinsics.CoroutineSingletons"(i32 0, %struct.ObjHeader** %0)
// OWNED-ABI-LABEL: define %struct.ObjHeader* @"kfun:kotlin.coroutines.intrinsics.CoroutineSingletons#$getEnumAt#static(kotlin.Int){}kotlin.coroutines.intrinsics.CoroutineSingletons"
// OWNED-ABI: call void @UpdateReturnRef

// FALLBACK-LABEL: define void @"kfun:kotlin.coroutines.native.internal.BaseContinuationImpl#resumeWith
// FALLBACK-NOT: call %struct.ObjHeader* @Kotlin_Array_get_borrowed
// FALLBACK: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.coroutines.intrinsics#<get-COROUTINE_SUSPENDED>(){}kotlin.Any"
// FALLBACK-NOT: call %struct.ObjHeader* @Kotlin_Array_get_borrowed

private fun ownedSuspended(): Any = COROUTINE_SUSPENDED

private fun runRestricted(value: Int): Int {
    var result = -1
    suspend { value }.startCoroutine(object : Continuation<Int> {
        override val context: CoroutineContext = EmptyCoroutineContext
        override fun resumeWith(outcome: Result<Int>) {
            result = outcome.getOrThrow()
        }
    })
    check(ownedSuspended() === COROUTINE_SUSPENDED)
    return result
}

fun main() {
    val checksum = runRestricted(42)
    check(checksum == 42)
    println("ARC_COROUTINE_SUSPENDED_SCOPED_BORROW_OK checksum=$checksum")
}
