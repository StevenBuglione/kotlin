private class Payload(val value: Int)

private fun produce(): Payload = Payload(42)

// CHECK-LABEL: "kfun:forwardOwnedResultThroughAliases#internal"
private fun forwardOwnedResultThroughAliases(): Payload {
    // CHECK: call %struct.ObjHeader* @"kfun:produce#internal"(%struct.ObjHeader** %0)
    val owned = produce()
    val firstAlias = owned
    val returnedAlias = firstAlias
    // CHECK-NOT: call void @UpdateReturnRef
    // CHECK: ret %struct.ObjHeader*
    return returnedAlias
}

fun main() {
    check(forwardOwnedResultThroughAliases().value == 42)
}
