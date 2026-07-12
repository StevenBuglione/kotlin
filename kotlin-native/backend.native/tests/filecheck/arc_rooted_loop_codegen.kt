import kotlin.concurrent.Volatile
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlin.native.arc.ArcDeinit
import kotlin.native.arc.ArcUnowned
import kotlin.native.arc.ArcWeak

private class NegativeRootedNode(val value: Int, var next: NegativeRootedNode?)

private fun consume(node: NegativeRootedNode): Int = node.value

// CHECK-LABEL: define internal void @"kfun:rootedNullUnwindTraversal#internal"
// CHECK-SAME: (%struct.ObjHeader* [[NULL_ANCHOR:%[0-9]+]]
// CHECK-NOT: @UpdateStackRef
private fun rootedNullUnwindTraversal(anchor: NegativeRootedNode) {
    var cursor = anchor
    var index = 0
    while (index < 4) {
        // CHECK: [[NULL_OWNER:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** [[NULL_CURSOR_SLOT:%cursor[^ ,)]*]]
        // CHECK: [[NULL_NEXT:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** [[NULL_FIELD:%[0-9]+]]
        // CHECK-NOT: store %struct.ObjHeader* [[NULL_NEXT]]
        // CHECK-NOT: @UpdateStackRef
        // CHECK: [[NULL_IS_DEAD:%[0-9]+]] = icmp eq %struct.ObjHeader* [[NULL_NEXT]], null
        // CHECK: br i1 [[NULL_IS_DEAD]], label %[[NULL_THROW:[A-Za-z0-9_.]+]], label %{{[A-Za-z0-9_.]+}}
        // CHECK: [[NULL_THROW]]:
        // CHECK-NOT: store %struct.ObjHeader*
        // CHECK-NOT: @UpdateStackRef
        // CHECK: call void @ThrowNullPointerException
        cursor = cursor.next!!
        index++
    }
}

// CHECK-LABEL: define internal i32 @"kfun:cursorUsedAfterLoop#internal"
private fun cursorUsedAfterLoop(anchor: NegativeRootedNode, steps: Int): Int {
    var cursor = anchor
    var index = 0
    while (index < steps) {
        // CHECK: [[USED_OWNER:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** [[USED_SLOT:%cursor[^ ,)]*]]
        // CHECK: [[USED_NEXT:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[USED_SLOT]], %struct.ObjHeader* [[USED_NEXT]])
        cursor = cursor.next!!
        index++
    }
    return cursor.value
}

// CHECK-LABEL: define internal i32 @"kfun:cursorPassedToCall#internal"
private fun cursorPassedToCall(anchor: NegativeRootedNode, steps: Int): Int {
    var cursor = anchor
    var checksum = 0
    repeat(steps) {
        // CHECK: [[CALL_NEXT:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[CALL_SLOT:%cursor[^ ,)]*]], %struct.ObjHeader* [[CALL_NEXT]])
        cursor = cursor.next!!
        // CHECK: [[CALL_RELOAD:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** [[CALL_SLOT]]
        // CHECK: {{call|invoke}} i32 @"kfun:consume#internal"(%struct.ObjHeader* [[CALL_RELOAD]])
        checksum += consume(cursor)
    }
    return checksum
}

// CHECK-LABEL: define internal i32 @"kfun:cursorPassedToCallback#internal"
private fun cursorPassedToCallback(
    anchor: NegativeRootedNode,
    steps: Int,
    callback: (NegativeRootedNode) -> Int,
): Int {
    var cursor = anchor
    var checksum = 0
    repeat(steps) {
        // CHECK: [[CALLBACK_NEXT:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[CALLBACK_SLOT:%cursor[^ ,)]*]], %struct.ObjHeader* [[CALLBACK_NEXT]])
        cursor = cursor.next!!
        // CHECK: [[CALLBACK_RELOAD:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** [[CALLBACK_SLOT]]
        // CHECK: {{call|invoke}} i32 {{.*}}(%struct.ObjHeader* {{%[0-9]+}}, %struct.ObjHeader* [[CALLBACK_RELOAD]])
        checksum += callback(cursor)
    }
    return checksum
}

// CHECK-LABEL: define internal i32 @"kfun:capturedCursor#internal"
private fun capturedCursor(anchor: NegativeRootedNode, steps: Int): Int {
    var cursor = anchor
    val readCursor = { cursor.value }
    repeat(steps) {
        // The captured local is boxed; bind the exact heap field update rather than accepting any
        // unrelated retain in the function.
        // CHECK: [[CAPTURE_OWNER:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
        // CHECK: [[CAPTURE_NEXT:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
        // CHECK: call void @UpdateHeapRef(%struct.ObjHeader** %{{[0-9]+}}, %struct.ObjHeader* [[CAPTURE_NEXT]])
        cursor = cursor.next!!
    }
    return readCursor()
}

// CHECK-LABEL: define internal i32 @"kfun:alternateAssignment#internal"
private fun alternateAssignment(anchor: NegativeRootedNode, alternate: NegativeRootedNode, steps: Int): Int {
    var cursor = anchor
    repeat(steps) { index ->
        // CHECK: [[ALTERNATE_VALUE:%[0-9]+]] = phi %struct.ObjHeader* [
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[ALTERNATE_SLOT:%cursor[^ ,)]*]], %struct.ObjHeader* [[ALTERNATE_VALUE]])
        cursor = if ((index and 1) == 0) cursor.next!! else alternate
    }
    return cursor.value
}

// CHECK-LABEL: define internal i32 @"kfun:differentTargetAssignment#internal"
private fun differentTargetAssignment(anchor: NegativeRootedNode, steps: Int): Int {
    var cursor = anchor
    var target = anchor
    repeat(steps) {
        // CHECK: [[DIFFERENT_NEXT:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[DIFFERENT_SLOT:%target[^ ,)]*]], %struct.ObjHeader* [[DIFFERENT_NEXT]])
        target = cursor.next!!
        cursor = target
    }
    return cursor.value
}

// CHECK-LABEL: define internal i32 @"kfun:ancestorReentryTailMutation#internal"
private fun ancestorReentryTailMutation(
    anchor: NegativeRootedNode,
    outerSteps: Int,
    innerSteps: Int,
): Int {
    // Cursor is declared outside the ancestor loop. Re-entering the inner loop after an outer-tail
    // mutation invalidates the rooted projection proof even when the first iteration is safe.
    var cursor = anchor
    var checksum = 0
    repeat(outerSteps) {
        repeat(innerSteps) {
            checksum += cursor.value
            // CHECK: [[ANCESTOR_NEXT:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
            // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[ANCESTOR_CURSOR_SLOT:%cursor[^ ,)]*]], %struct.ObjHeader* [[ANCESTOR_NEXT]])
            cursor = cursor.next!!
        }
        // CHECK: call void @UpdateHeapRef(%struct.ObjHeader** [[ANCESTOR_FIELD:%[0-9]+]], %struct.ObjHeader* null)
        anchor.next = null
    }
    return checksum
}

private class LoopDeinitTrigger {
    @ArcDeinit
    private fun deinit() {}
}

// CHECK-LABEL: define internal i32 @"kfun:nonCursorReferenceTransition#internal"
private fun nonCursorReferenceTransition(anchor: NegativeRootedNode, steps: Int): Int {
    var cursor = anchor
    var trigger: LoopDeinitTrigger? = LoopDeinitTrigger()
    var checksum = 0
    repeat(steps) {
        // Any unrelated strong replacement can synchronously execute arbitrary @ArcDeinit code.
        // Cursor must therefore use the ordinary owning replacement before trigger dies, even
        // though this focused fixture's hook is empty.
        checksum += cursor.value
        // CHECK: [[TRIGGER_NEXT:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[TRIGGER_CURSOR_SLOT:%cursor[^ ,)]*]], %struct.ObjHeader* [[TRIGGER_NEXT]])
        cursor = cursor.next!!
        trigger = null
    }
    check(trigger == null)
    return checksum
}

// CHECK-LABEL: define internal i32 @"kfun:fieldMutationInsideLoop#internal"
private fun fieldMutationInsideLoop(
    anchor: NegativeRootedNode,
    replacement: NegativeRootedNode,
    steps: Int,
): Int {
    var cursor = anchor
    repeat(steps) {
        // CHECK: [[MUTATED_NEXT:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** [[MUTATED_FIELD:%[0-9]+]]
        // CHECK: call void @UpdateHeapRef(%struct.ObjHeader** %{{[0-9]+}}, %struct.ObjHeader* %{{[0-9]+}})
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[MUTATED_SLOT:%cursor[^ ,)]*]], %struct.ObjHeader* [[MUTATED_NEXT]])
        val projected = cursor.next!!
        cursor.next = replacement
        cursor = projected
    }
    return cursor.value
}

private interface UnknownObserver {
    fun observe(node: NegativeRootedNode): Int
}

// CHECK-LABEL: define internal i32 @"kfun:unknownVirtualCallInsideLoop#internal"
private fun unknownVirtualCallInsideLoop(anchor: NegativeRootedNode, steps: Int, observer: UnknownObserver): Int {
    var cursor = anchor
    var checksum = 0
    repeat(steps) {
        // CHECK: [[VIRTUAL_NEXT:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[VIRTUAL_SLOT:%cursor[^ ,)]*]], %struct.ObjHeader* [[VIRTUAL_NEXT]])
        cursor = cursor.next!!
        // CHECK: [[VIRTUAL_RELOAD:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** [[VIRTUAL_SLOT]]
        // CHECK: {{call|invoke}} i32 {{.*}}(%struct.ObjHeader* %{{[0-9]+}}, %struct.ObjHeader* [[VIRTUAL_RELOAD]])
        checksum += observer.observe(cursor)
    }
    return checksum
}

private var finallyCount = 0

// CHECK-LABEL: define internal i32 @"kfun:tryFinallyLoop#internal"
private fun tryFinallyLoop(anchor: NegativeRootedNode, steps: Int): Int {
    var cursor = anchor
    try {
        repeat(steps) {
            // CHECK: [[TRY_NEXT:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
            // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[TRY_SLOT:%cursor[^ ,)]*]], %struct.ObjHeader* [[TRY_NEXT]])
            cursor = cursor.next!!
        }
    } finally {
        finallyCount++
    }
    return cursor.value
}

private var pendingContinuation: Continuation<Unit>? = null

private suspend fun realSuspensionPoint() {
    suspendCoroutine<Unit> { continuation -> pendingContinuation = continuation }
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:$realSuspendingLoopCOROUTINE$0.invokeSuspend#internal"
private suspend fun realSuspendingLoop(anchor: NegativeRootedNode, steps: Int): Int {
    var cursor = anchor
    repeat(steps) {
        // A real continuation spill must own cursor across suspension.
        // CHECK: [[SUSPEND_OWNER:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** [[SUSPEND_SLOT:%cursor[^ ,)]*]]
        // CHECK: [[SUSPEND_NEXT:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[SUSPEND_SLOT]], %struct.ObjHeader* [[SUSPEND_NEXT]])
        cursor = cursor.next!!
        realSuspensionPoint()
    }
    return cursor.value
}

private val realSuspendingLoopReference: suspend (NegativeRootedNode, Int) -> Int =
    ::realSuspendingLoop

private class VolatileRootedNode(val value: Int, next: VolatileRootedNode?) {
    @Volatile
    var next: VolatileRootedNode? = next
}

// CHECK-LABEL: define internal i32 @"kfun:volatileTransitionLoop#internal"
private fun volatileTransitionLoop(anchor: VolatileRootedNode, steps: Int): Int {
    var cursor = anchor
    repeat(steps) {
        // CHECK: [[VOLATILE_NEXT:%[0-9]+]] = {{call|invoke}} %struct.ObjHeader* @ReadVolatileHeapRef
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[VOLATILE_SLOT:%cursor[^ ,)]*]], %struct.ObjHeader* [[VOLATILE_NEXT]])
        cursor = cursor.next!!
    }
    return cursor.value
}

private class WeakRootedNode(val value: Int, next: WeakRootedNode?) {
    @ArcWeak
    var next: WeakRootedNode? = next
}

// CHECK-LABEL: define internal i32 @"kfun:weakTransitionLoop#internal"
private fun weakTransitionLoop(anchor: WeakRootedNode, steps: Int): Int {
    var cursor = anchor
    repeat(steps) {
        // CHECK: [[WEAK_RESULT_SLOT:%[0-9]+]] = getelementptr %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}, i32 {{[0-9]+}}
        // CHECK: [[WEAK_NEXT:%[0-9]+]] = {{call|invoke}} %struct.ObjHeader* @"kfun:WeakRootedNode.<get-next>#internal"(%struct.ObjHeader* %{{[0-9]+}}, %struct.ObjHeader** [[WEAK_RESULT_SLOT]])
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[WEAK_SLOT:%cursor[^ ,)]*]], %struct.ObjHeader* [[WEAK_NEXT]])
        cursor = cursor.next!!
    }
    return cursor.value
}

private class UnownedTransitionTarget(val value: Int)

private class UnownedTransitionHolder(next: UnownedTransitionTarget) {
    @ArcUnowned
    var next: UnownedTransitionTarget = next
}

// CHECK-LABEL: define internal i32 @"kfun:unownedTransitionLoop#internal"
private fun unownedTransitionLoop(
    holder: UnownedTransitionHolder,
    initial: UnownedTransitionTarget,
    steps: Int,
): Int {
    var cursor = initial
    repeat(steps) {
        // CHECK: [[UNOWNED_RESULT_SLOT:%[0-9]+]] = getelementptr %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}, i32 {{[0-9]+}}
        // CHECK: [[UNOWNED_NEXT:%[0-9]+]] = {{call|invoke}} %struct.ObjHeader* @"kfun:UnownedTransitionHolder.<get-next>#internal"(%struct.ObjHeader* %{{[0-9]+}}, %struct.ObjHeader** [[UNOWNED_RESULT_SLOT]])
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[UNOWNED_SLOT:%cursor[^ ,)]*]], %struct.ObjHeader* [[UNOWNED_NEXT]])
        cursor = holder.next
    }
    return cursor.value
}

private var escapedHeapAnchor: NegativeRootedNode? = null

private fun makeEscapingHeapAnchor(): NegativeRootedNode {
    val anchor = NegativeRootedNode(1, NegativeRootedNode(2, null))
    escapedHeapAnchor = anchor
    return anchor
}

// CHECK-LABEL: define internal i32 @"kfun:nonStackLocalAnchorLoop#internal"
private fun nonStackLocalAnchorLoop(steps: Int): Int {
    // CHECK: [[HEAP_RESULT_SLOT:%[0-9]+]] = getelementptr %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}, i32 {{[0-9]+}}
    // CHECK: [[HEAP_ANCHOR:%[0-9]+]] = {{call|invoke}} %struct.ObjHeader* @"kfun:makeEscapingHeapAnchor#internal"(%struct.ObjHeader** [[HEAP_RESULT_SLOT]])
    var cursor = makeEscapingHeapAnchor()
    repeat(steps) {
        // CHECK: [[HEAP_NEXT:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[HEAP_SLOT:%cursor[^ ,)]*]], %struct.ObjHeader* [[HEAP_NEXT]])
        cursor = cursor.next!!
    }
    return cursor.value
}

fun main() {
    val first = NegativeRootedNode(1, null)
    val second = NegativeRootedNode(2, first)
    first.next = second
    try {
        rootedNullUnwindTraversal(NegativeRootedNode(0, null))
        error("the first edge must be null")
    } catch (_: NullPointerException) {
        check(first.value == 1)
    }
    check(cursorUsedAfterLoop(first, 2) == 1)
    check(cursorPassedToCall(first, 2) == 3)
    check(cursorPassedToCallback(first, 2) { it.value } == 3)
    check(capturedCursor(first, 2) == 1)
    check(alternateAssignment(first, second, 1) == 2)
    check(differentTargetAssignment(first, 2) == 1)

    val ancestorFirst = NegativeRootedNode(11, null)
    val ancestorSecond = NegativeRootedNode(12, ancestorFirst)
    ancestorFirst.next = ancestorSecond
    check(ancestorReentryTailMutation(ancestorFirst, 1, 1) == 11)

    val triggerFirst = NegativeRootedNode(13, null)
    val triggerSecond = NegativeRootedNode(14, triggerFirst)
    triggerFirst.next = triggerSecond
    check(nonCursorReferenceTransition(triggerFirst, 1) == 13)

    val mutationFirst = NegativeRootedNode(3, null)
    val mutationSecond = NegativeRootedNode(4, mutationFirst)
    mutationFirst.next = mutationSecond
    check(fieldMutationInsideLoop(mutationFirst, mutationSecond, 1) == 4)

    val observer = object : UnknownObserver {
        override fun observe(node: NegativeRootedNode): Int = node.value
    }
    check(unknownVirtualCallInsideLoop(first, 2, observer) == 3)
    check(tryFinallyLoop(first, 2) == 1)
    check(finallyCount == 1)

    val volatileFirst = VolatileRootedNode(5, null)
    val volatileSecond = VolatileRootedNode(6, volatileFirst)
    volatileFirst.next = volatileSecond
    check(volatileTransitionLoop(volatileFirst, 2) == 5)

    val weakFirst = WeakRootedNode(7, null)
    val weakSecond = WeakRootedNode(8, weakFirst)
    weakFirst.next = weakSecond
    check(weakTransitionLoop(weakFirst, 2) == 7)

    val unownedTarget = UnownedTransitionTarget(9)
    val unownedHolder = UnownedTransitionHolder(unownedTarget)
    check(unownedTransitionLoop(unownedHolder, UnownedTransitionTarget(0), 2) == 9)

    check(nonStackLocalAnchorLoop(1) == 2)
    check(realSuspendingLoopReference.hashCode() != 0)
}
