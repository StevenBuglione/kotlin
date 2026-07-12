import kotlin.concurrent.Volatile
import kotlin.native.arc.ArcUnowned
import kotlin.native.arc.ArcWeak

private class ProjectionNode(val value: Int, var next: ProjectionNode?)

// CHECK-LABEL: define internal i32 @"kfun:canonicalFieldTraversal#internal"
private fun canonicalFieldTraversal(head: ProjectionNode, steps: Int): Int {
    var cursor = head
    var sum = cursor.value
    repeat(steps) {
        // The mutable cursor slot owns the current node. Loading `next` is +0 until the one
        // retaining store replaces cursor; no anonymous owning slot may appear between them.
        // CHECK: [[PROJECTED_OWNER:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %cursor
        // CHECK-NOT: call void @UpdateStackRef
        // CHECK: [[PROJECTED_NEXT:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
        // CHECK-NOT: call void @UpdateStackRef
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %cursor, %struct.ObjHeader* [[PROJECTED_NEXT]])
        // CHECK-NOT: call void @UpdateStackRef
        cursor = cursor.next!!
        sum += cursor.value
    }
    // CHECK: ret i32
    return sum
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:escapeFieldResult#internal"
private fun escapeFieldResult(owner: ProjectionNode): ProjectionNode {
    // A returned projection escapes its owner's guaranteed lifetime and must be promoted.
    // CHECK: load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
    // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %{{[0-9]+}}, %struct.ObjHeader* %{{[0-9]+}})
    return owner.next!!
}

private fun observe(node: ProjectionNode): Int = node.value

// CHECK-LABEL: define internal i32 @"kfun:retainAcrossInterveningCall#internal"
private fun retainAcrossInterveningCall(head: ProjectionNode): Int {
    var cursor = head
    cursor = run {
        // The call after the field read may run arbitrary Kotlin code, so the projection needs an
        // owning temporary until the final cursor store.
        // CHECK: [[CALL_FIELD:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %{{[0-9]+}}, %struct.ObjHeader* [[CALL_FIELD]])
        val projected = cursor.next!!
        observe(cursor)
        // CHECK: {{call|invoke}} i32 @"kfun:observe#internal"
        projected
    }
    return cursor.value
}

// CHECK-LABEL: define internal i32 @"kfun:retainCapturedOwner#internal"
private fun retainCapturedOwner(head: ProjectionNode): Int {
    var cursor = head
    // Capturing the owner prevents a stack-local lifetime proof. Capture storage and the projected
    // replacement therefore retain normally.
    val readOwner = { cursor.value }
    // CHECK: call void @UpdateHeapRef
    cursor = cursor.next!!
    // CHECK: call void @UpdateHeapRef
    return readOwner() + cursor.value
}

private class VolatileNode(val value: Int, next: VolatileNode?) {
    @Volatile
    var next: VolatileNode? = next
}

// CHECK-LABEL: define internal i32 @"kfun:retainVolatileField#internal"
private fun retainVolatileField(head: VolatileNode): Int {
    var cursor = head
    // Volatile reference reads use their atomic owning-load ABI, never the raw +0 projection.
    // CHECK: {{call|invoke}} %struct.ObjHeader* @ReadVolatileHeapRef
    cursor = cursor.next!!
    return cursor.value
}

private class WeakNode(val value: Int, next: WeakNode?) {
    @ArcWeak
    var next: WeakNode? = next
}

// CHECK-LABEL: define internal i32 @"kfun:retainWeakField#internal"
private fun retainWeakField(head: WeakNode): Int {
    var cursor = head
    // A weak read is a zeroing promotion with its own lifetime boundary.
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:WeakNode.<get-next>#internal"
    cursor = cursor.next!!
    return cursor.value
}

private class UnownedTarget(val value: Int)

private class UnownedHolder(next: UnownedTarget) {
    @ArcUnowned
    var next: UnownedTarget = next
}

// CHECK-LABEL: define internal i32 @"kfun:retainUnownedField#internal"
private fun retainUnownedField(holder: UnownedHolder, initial: UnownedTarget): Int {
    var target = initial
    // A checked unowned promotion must not be treated as direct strong field storage.
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:UnownedHolder.<get-next>#internal"
    target = holder.next
    return target.value
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:suspendFieldProjection#internal"
private suspend fun suspendFieldProjection(head: ProjectionNode): Int {
    var cursor = head
    // Coroutine spilling makes the local lifetime non-stack-local, so the ordinary owning load
    // remains mandatory.
    // CHECK: [[SUSPEND_FIELD:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
    // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %{{[0-9]+}}, %struct.ObjHeader* [[SUSPEND_FIELD]])
    cursor = cursor.next!!
    return cursor.value
}

private val suspendFieldProjectionReference: suspend (ProjectionNode) -> Int = ::suspendFieldProjection

private var finallyCount = 0

// CHECK-LABEL: define internal i32 @"kfun:retainInsideTry#internal"
private fun retainInsideTry(head: ProjectionNode): Int {
    var cursor = head
    try {
        // Exceptional control flow is deliberately fail-closed for this first projection slice.
        // CHECK: [[TRY_FIELD:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %{{[0-9]+}}, %struct.ObjHeader* [[TRY_FIELD]])
        cursor = cursor.next!!
    } finally {
        finallyCount++
    }
    return cursor.value
}

// CHECK-LABEL: define internal i32 @"kfun:retainAfterFieldWrite#internal"
private fun retainAfterFieldWrite(head: ProjectionNode, replacement: ProjectionNode): Int {
    var cursor = head
    cursor = run {
        // A field write between projection and final local store can release the selected value,
        // so the old field result must already be held by an owning temporary.
        // CHECK: [[WRITTEN_FIELD:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %{{[0-9]+}}, %struct.ObjHeader* [[WRITTEN_FIELD]])
        val projected = cursor.next!!
        // CHECK: call void @UpdateHeapRef
        cursor.next = replacement
        projected
    }
    return cursor.value
}

// CHECK-LABEL: define internal i32 @"kfun:retainDifferentTarget#internal"
private fun retainDifferentTarget(head: ProjectionNode): Int {
    var cursor = head
    var target = head
    // Only `cursor = cursor.next!!` has the verified self-replacement lifetime. Assigning the
    // projection to a different target must first make it owned.
    // CHECK: [[DIFFERENT_FIELD:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %{{[0-9]+}}
    // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %{{[0-9]+}}, %struct.ObjHeader* [[DIFFERENT_FIELD]])
    target = cursor.next!!
    return cursor.value + target.value
}

fun main() {
    val tail = ProjectionNode(3, null)
    val middle = ProjectionNode(2, tail)
    val head = ProjectionNode(1, middle)
    check(canonicalFieldTraversal(head, 2) == 6)
    check(escapeFieldResult(head) === middle)
    check(retainAcrossInterveningCall(head) == 2)
    check(retainCapturedOwner(head) == 4)

    val volatileTail = VolatileNode(7, null)
    check(retainVolatileField(VolatileNode(6, volatileTail)) == 7)

    val weakTail = WeakNode(9, null)
    check(retainWeakField(WeakNode(8, weakTail)) == 9)

    check(retainInsideTry(head) == 2)
    check(retainAfterFieldWrite(ProjectionNode(4, middle), tail) == 2)
    check(retainDifferentTarget(head) == 3)
    val unownedTarget = UnownedTarget(5)
    check(retainUnownedField(UnownedHolder(unownedTarget), UnownedTarget(0)) == 5)
    check(suspendFieldProjectionReference.hashCode() != 0)
}
