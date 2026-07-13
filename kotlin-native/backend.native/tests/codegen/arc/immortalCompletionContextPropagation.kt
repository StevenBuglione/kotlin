import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.native.arc.ArcDeinit

// OPT-LABEL: define internal void @"kfun:SelectedCompletion.<init>#internal"
// OPT: [[CONTEXT_ADDRESS:%[0-9]+]] = getelementptr inbounds %"kclassbody:SelectedCompletion#internal", %"kclassbody:SelectedCompletion#internal"* {{%[0-9]+}}, i32 0, i32 1
// OPT-NEXT: store %struct.ObjHeader* {{%[0-9]+}}, %struct.ObjHeader** [[CONTEXT_ADDRESS]], align 8
// OPT: call void @UpdateHeapRef

// OPT-LABEL: define internal %struct.ObjHeader* @"kfun:SelectedCompletion.<get-context>#internal"
// OPT-SAME: (%struct.ObjHeader* {{%[0-9]+}}, %struct.ObjHeader** [[RETURN_SLOT:%[0-9]+]])
// OPT: call void @MoveReferenceIntoReturnSlotArc(%struct.ObjHeader** [[RETURN_SLOT]], %struct.ObjHeader*
// OPT-NOT: call void @UpdateReturnRef
// OPT: ret %struct.ObjHeader*

// OPT-LABEL: define internal void @"kfun:ReplacementCompletion.<init>#internal"
// OPT: call void @UpdateHeapRef

// OPT-LABEL: define internal void @"kfun:FailingCompletion.<init>#internal"
// OPT: call void @UpdateHeapRef

// OPT-LABEL: define internal void @"kfun:DuplicateAllocationCompletion.<init>#internal"
// OPT: call void @UpdateHeapRef

// OPT-LABEL: define internal void @"kfun:ConditionalCompletion.<init>#internal"
// OPT: call void @UpdateHeapRef

// OPT-LABEL: define private void @"karcdestroyfields:SelectedCompletion#internal"
// OPT-COUNT-1: call void @ZeroHeapRef
// OPT: ret void

// FALLBACK-LABEL: define internal void @"kfun:SelectedCompletion.<init>#internal"
// FALLBACK: call void @UpdateHeapRef
// FALLBACK-LABEL: define internal %struct.ObjHeader* @"kfun:SelectedCompletion.<get-context>#internal"
// FALLBACK-NOT: call void @MoveReferenceIntoReturnSlotArc
// FALLBACK: call void @UpdateReturnRef
// FALLBACK-LABEL: define private void @"karcdestroyfields:SelectedCompletion#internal"
// FALLBACK-COUNT-2: call void @ZeroHeapRef
// FALLBACK: ret void

private var checksum = 0
private var ordinaryDeinitCount = 0

private class Ordinary {
    @ArcDeinit
    private fun deinit() {
        ordinaryDeinitCount++
    }
}

private fun failAfterContextInitialization(fail: Boolean) {
    if (fail) error("post-store constructor failure")
}

private class SelectedCompletion(fail: Boolean) : Continuation<Int> {
    override val context: CoroutineContext = EmptyCoroutineContext
    private val ordinary = Ordinary()

    init {
        failAfterContextInitialization(fail)
    }

    override fun resumeWith(result: Result<Int>) {
        check((ordinary as Any) !== (context as Any))
        checksum += result.getOrThrow()
    }
}

private class ReplacementCompletion : Continuation<Int> {
    override var context: CoroutineContext = EmptyCoroutineContext

    init {
        context = EmptyCoroutineContext
    }

    override fun resumeWith(result: Result<Int>) = Unit
}

private fun contextThatMayFail(fail: Boolean): CoroutineContext {
    if (fail) error("constructor failure")
    return EmptyCoroutineContext
}

private class FailingCompletion(fail: Boolean) : Continuation<Int> {
    override val context: CoroutineContext = contextThatMayFail(fail)

    override fun resumeWith(result: Result<Int>) = Unit
}

private class DuplicateAllocationCompletion : Continuation<Int> {
    override val context: CoroutineContext = EmptyCoroutineContext

    override fun resumeWith(result: Result<Int>) = Unit
}

private class ConditionalCompletion(flag: Boolean) : Continuation<Int> {
    override val context: CoroutineContext

    init {
        if (flag) {
            context = EmptyCoroutineContext
        } else {
            error("conditional constructor failure")
        }
    }

    override fun resumeWith(result: Result<Int>) = Unit
}

private fun makeSelected(fail: Boolean): SelectedCompletion = SelectedCompletion(fail)

private fun runSelected() {
    suspend { 41 }.startCoroutine(makeSelected(false))
}

private fun runSelectedPostStoreFailure() {
    try {
        makeSelected(true)
        error("expected selected post-store constructor failure")
    } catch (_: IllegalStateException) {
    }
}

private fun runNegativeShapes() {
    check(ReplacementCompletion().context === EmptyCoroutineContext)
    check(FailingCompletion(false).context === EmptyCoroutineContext)
    check(DuplicateAllocationCompletion().context === EmptyCoroutineContext)
    check(DuplicateAllocationCompletion().context === EmptyCoroutineContext)
    check(ConditionalCompletion(true).context === EmptyCoroutineContext)
    try {
        FailingCompletion(true)
        error("expected constructor failure")
    } catch (_: IllegalStateException) {
        checksum += 1
    }
}

fun main() {
    runSelected()
    check(ordinaryDeinitCount == 1) {
        "selected success did not release exactly one ordinary field: $ordinaryDeinitCount"
    }
    runSelectedPostStoreFailure()
    check(ordinaryDeinitCount == 2) {
        "selected post-store failure did not release exactly one ordinary field: $ordinaryDeinitCount"
    }
    runNegativeShapes()
    check(checksum == 42)
    println("ARC_IMMORTAL_COMPLETION_CONTEXT_PROPAGATION_OK checksum=$checksum")
}
