private class MatchingPayload(val value: Int)

private var matchingSink = 0

private fun consumeMatching(marker: Int, payload: MatchingPayload) {
    matchingSink += marker + payload.value
}

// CHECK-LABEL: define internal void @"kfun:matchingSetLocalAlias#internal"
private fun matchingSetLocalAlias(owner: MatchingPayload) {
    // The verified matching set proves that the guaranteed parameter dominates both borrows and
    // every normal/unwind cleanup edge. The mutable spelling therefore needs no owning frame slot:
    // its initialization copy and LeaveFrame destroy disappear as one authenticated pair.
    // CHECK-NOT: %alias = getelementptr %struct.ObjHeader*
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: {{call|invoke}} void @"kfun:consumeMatching#internal"(i32 1, %struct.ObjHeader* %0)
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: {{call|invoke}} void @"kfun:consumeMatching#internal"(i32 2, %struct.ObjHeader* %0)
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: ret void
    var alias = owner
    consumeMatching(1, alias)
    consumeMatching(2, alias)
}

// CONSERVATIVE-LABEL: define internal void @"kfun:matchingSetLocalAlias#internal"
// CONSERVATIVE: %alias = getelementptr %struct.ObjHeader*
// CONSERVATIVE: call void @UpdateStackRef(%struct.ObjHeader** %alias,
// CONSERVATIVE: ret void

// STRICT-LABEL: define internal void @"kfun:matchingSetLocalAlias#internal"
// STRICT: %alias = getelementptr %struct.ObjHeader*
// STRICT: store %struct.ObjHeader* %0, %struct.ObjHeader** %alias
// STRICT: ret void

// CHECK-LABEL: define internal void @"kfun:rejectReassignedMatchingAlias#internal"
private fun rejectReassignedMatchingAlias(owner: MatchingPayload, replacement: MatchingPayload) {
    // Reassignment invalidates the RC identity proof and must retain the ordinary owning slot.
    // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %alias, %struct.ObjHeader* %0)
    var alias = owner
    consumeMatching(3, alias)
    alias = replacement
    consumeMatching(4, alias)
}

fun main() {
    val owner = MatchingPayload(10)
    matchingSetLocalAlias(owner)
    rejectReassignedMatchingAlias(owner, MatchingPayload(20))
    check(matchingSink == 60)
}
