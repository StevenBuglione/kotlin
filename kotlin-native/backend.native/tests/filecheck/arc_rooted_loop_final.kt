import kotlin.native.Retain

private class FinalRootedLoopNode(val value: Int, var next: FinalRootedLoopNode?)

// FINAL-LABEL: define internal i64 @"kfun:rootedGuaranteedTraversal#internal"
// FINAL-SAME: (%struct.ObjHeader*{{.*}} [[ANCHOR:%[0-9]+]], i32
// FINAL-NOT: alloca
// FINAL-NOT: @UpdateStackRef
@Retain
private fun rootedGuaranteedTraversal(anchor: FinalRootedLoopNode, steps: Int): Long {
    var cursor = anchor
    var checksum = 0L
    var index = 0
    while (index < steps) {
        checksum += cursor.value
        // FINAL: while_loop:
        // FINAL-NEXT: [[ROOTED_CURSOR:%[A-Za-z0-9_.]+]] = phi %struct.ObjHeader* [ [[ROOTED_NEXT:%[A-Za-z0-9_.]+]], %[[ROOTED_LATCH:[A-Za-z0-9_.]+]] ], [ [[ANCHOR]], %{{[A-Za-z0-9_.]+}} ]
        // FINAL-NEXT: [[ROOTED_CHECKSUM:%[A-Za-z0-9_.]+]] = phi i64
        // FINAL-NEXT: [[ROOTED_INDEX:%[A-Za-z0-9_.]+]] = phi i32
        // FINAL: [[ROOTED_NEXT_HEADER:%[0-9]+]] = getelementptr inbounds %struct.ObjHeader, %struct.ObjHeader* [[ROOTED_CURSOR]], i64 1
        // FINAL: [[ROOTED_NEXT_PTR:%[0-9]+]] = bitcast %struct.ObjHeader* [[ROOTED_NEXT_HEADER]] to %struct.ObjHeader**
        // FINAL: [[ROOTED_NEXT]] = load %struct.ObjHeader*, %struct.ObjHeader** [[ROOTED_NEXT_PTR]]
        // FINAL-NOT: @UpdateStackRef
        // FINAL: [[ROOTED_LATCH]]:
        // FINAL: [[ROOTED_VALUE_HEADER:%[0-9]+]] = getelementptr inbounds %struct.ObjHeader, %struct.ObjHeader* [[ROOTED_CURSOR]], i64 2
        // FINAL: [[ROOTED_VALUE_PTR:%[0-9]+]] = bitcast %struct.ObjHeader* [[ROOTED_VALUE_HEADER]] to i32*
        // FINAL: [[ROOTED_VALUE:%[0-9]+]] = load i32, i32* [[ROOTED_VALUE_PTR]]
        cursor = cursor.next!!
        index++
    }
    // FINAL-NOT: @UpdateStackRef
    // FINAL: ret i64
    return checksum
}

// FINAL-LABEL: define internal void @"kfun:rootedNullUnwindTraversal#internal"
// FINAL-SAME: (%struct.ObjHeader*{{.*}} [[NULL_ANCHOR:%[0-9]+]]
// FINAL-NOT: alloca
// FINAL-NOT: @UpdateStackRef
@Retain
private fun rootedNullUnwindTraversal(anchor: FinalRootedLoopNode) {
    var cursor = anchor
    var index = 0
    while (index < 4) {
        // The constant trip count is fully unrolled. Every projected edge remains an SSA value
        // rooted by the original parameter; no cursor slot or ownership replacement survives.
        // FINAL: [[NULL_HEADER_1:%[0-9]+]] = getelementptr inbounds %struct.ObjHeader, %struct.ObjHeader* [[NULL_ANCHOR]], i64 1
        // FINAL: [[NULL_PTR_1:%[0-9]+]] = bitcast %struct.ObjHeader* [[NULL_HEADER_1]] to %struct.ObjHeader**
        // FINAL: [[NULL_NEXT_1:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** [[NULL_PTR_1]]
        // FINAL-NOT: store %struct.ObjHeader*
        // FINAL-NOT: @UpdateStackRef
        // FINAL: {{.*}}call{{.*}} void @ThrowNullPointerException
        // FINAL: [[NULL_HEADER_2:%[0-9]+]] = getelementptr inbounds %struct.ObjHeader, %struct.ObjHeader* [[NULL_NEXT_1]], i64 1
        // FINAL: [[NULL_PTR_2:%[0-9]+]] = bitcast %struct.ObjHeader* [[NULL_HEADER_2]] to %struct.ObjHeader**
        // FINAL: [[NULL_NEXT_2:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** [[NULL_PTR_2]]
        // FINAL-NOT: store %struct.ObjHeader*
        // FINAL-NOT: @UpdateStackRef
        cursor = cursor.next!!
        index++
    }
}

// FINAL-LABEL: define internal i64 @"kfun:boundedStackCycleTraversal#internal"
// FINAL-NOT: @AllocInstance
// FINAL-NOT: @UpdateStackRef
@Retain
private fun boundedStackCycleTraversal(firstValue: Int, secondValue: Int, steps: Int): Long {
    // These exact nonescaping objects must retain Lifetime.STACK. Only the two class bodies may be
    // stack storage; the traversal cursor must remain an SSA value rather than a frame/root slot.
    // FINAL: [[FIRST_BODY:%[0-9]+]] = alloca %"kclassbody:FinalRootedLoopNode#internal"
    // FINAL: [[FIRST_HEADER:%[A-Za-z0-9_.]+]] = getelementptr inbounds %"kclassbody:FinalRootedLoopNode#internal", %"kclassbody:FinalRootedLoopNode#internal"* [[FIRST_BODY]], i64 0, i32 0
    // FINAL: [[SECOND_BODY:%[0-9]+]] = alloca %"kclassbody:FinalRootedLoopNode#internal"
    // FINAL: [[SECOND_HEADER:%[A-Za-z0-9_.]+]] = getelementptr inbounds %"kclassbody:FinalRootedLoopNode#internal", %"kclassbody:FinalRootedLoopNode#internal"* [[SECOND_BODY]], i64 0, i32 0
    // FINAL-NOT: @AllocInstance
    // The anchors and their deterministic destruction legitimately require a root frame. The
    // loop-carried cursor itself must remain the pointer phi below and never use ARC replacement.
    // FINAL-NOT: @UpdateStackRef
    val first = FinalRootedLoopNode(firstValue, null)
    val second = FinalRootedLoopNode(secondValue, first)
    first.next = second
    var cursor = first
    var checksum = 0L
    var index = 0
    while (index < steps) {
        checksum += cursor.value
        // FINAL: while_loop:
        // FINAL-NEXT: [[STACK_CURSOR:%[A-Za-z0-9_.]+]] = phi %struct.ObjHeader* [ [[STACK_NEXT:%[A-Za-z0-9_.]+]], %[[STACK_LATCH:[A-Za-z0-9_.]+]] ], [ [[FIRST_HEADER]], %{{[A-Za-z0-9_.]+}} ]
        // FINAL-NEXT: [[STACK_CHECKSUM:%[A-Za-z0-9_.]+]] = phi i64
        // FINAL-NEXT: [[STACK_INDEX:%[A-Za-z0-9_.]+]] = phi i32
        // FINAL: [[STACK_NEXT_HEADER:%[0-9]+]] = getelementptr inbounds %struct.ObjHeader, %struct.ObjHeader* [[STACK_CURSOR]], i64 1
        // FINAL: [[STACK_NEXT_PTR:%[0-9]+]] = bitcast %struct.ObjHeader* [[STACK_NEXT_HEADER]] to %struct.ObjHeader**
        // FINAL: [[STACK_NEXT]] = load %struct.ObjHeader*, %struct.ObjHeader** [[STACK_NEXT_PTR]]
        // FINAL-NOT: @UpdateStackRef
        // FINAL: [[STACK_LATCH]]:
        cursor = cursor.next!!
        index++
    }
    // FINAL-NOT: @AllocInstance
    // FINAL-NOT: @UpdateStackRef
    // FINAL: ret i64
    return checksum
}

fun main() {
    val fourth = FinalRootedLoopNode(17, null)
    val third = FinalRootedLoopNode(11, fourth)
    val second = FinalRootedLoopNode(7, third)
    val first = FinalRootedLoopNode(5, second)
    check(rootedGuaranteedTraversal(first, 3) == 23L)
    check(boundedStackCycleTraversal(13, 29, 10_000) == 210_000L)
    try {
        rootedNullUnwindTraversal(first)
        error("the fourth edge must be null")
    } catch (_: NullPointerException) {
        check(first.value == 5)
    }
}
