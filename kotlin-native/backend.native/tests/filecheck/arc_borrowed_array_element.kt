private interface ArrayOperation {
    fun apply(value: Int): Int
}
private object ArrayAdd : ArrayOperation {
    override fun apply(value: Int): Int = value + 1
}

private object ArrayMultiply : ArrayOperation {
    override fun apply(value: Int): Int = value * 2
}

private fun consumeArrayOperation(operation: ArrayOperation, marker: Int): Int = operation.apply(marker)

// CHECK-LABEL: define internal i32 @"kfun:borrowFreshArrayVirtualReceiver#internal"
private fun borrowFreshArrayVirtualReceiver(index: Int): Int {
    val operations = arrayOf<ArrayOperation>(ArrayAdd, ArrayMultiply)
    // The index and size are evaluated before the borrowed projection. The fresh stack Array is
    // the only owner route, and the virtual consumer receives only the element.
    // CHECK: [[ELEMENT:%[0-9]+]] = {{call|invoke}} %struct.ObjHeader* @Kotlin_Array_get_borrowed(%struct.ObjHeader* {{%[0-9]+}}, i32 {{%[0-9]+}})
    // CHECK-NOT: call void @UpdateReturnRef
    // CHECK: ret i32
    return operations[index % operations.size].apply(index)
}

private fun sideEffectingIndex(): Int = 0

// CHECK-LABEL: define internal i32 @"kfun:borrowAfterIndexEvaluation#internal"
private fun borrowAfterIndexEvaluation(): Int {
    val operations = arrayOf<ArrayOperation>(ArrayAdd)
    // Calls that finish before Array.get creates its +0 result do not extend the borrow interval.
    // CHECK: {{call|invoke}} i32 @"kfun:sideEffectingIndex#internal"()
    // CHECK: {{.*}}@Kotlin_Array_get_borrowed
    return operations[sideEffectingIndex()].apply(41)
}

// CHECK-LABEL: define internal i32 @"kfun:borrowAcrossGetterAndConsumerUnwind#internal"
private fun borrowAcrossGetterAndConsumerUnwind(index: Int): Int = try {
    val operations = arrayOf<ArrayOperation>(ArrayAdd)
    // Bounds failure and a throwing virtual consumer both use the current exception handler. The
    // Array owner remains live until either the normal or unwind edge leaves this scope.
    // CHECK: [[UNWIND_ELEMENT:%[0-9]+]] = invoke %struct.ObjHeader* @Kotlin_Array_get_borrowed
    // CHECK-NOT: call void @UpdateReturnRef
    // CHECK: invoke i32 %{{[0-9]+}}(%struct.ObjHeader* [[UNWIND_ELEMENT]]
    operations[index].apply(index)
} catch (_: IndexOutOfBoundsException) {
    -1
}

// CHECK-LABEL: define internal i32 @"kfun:retainMutatedArrayElement#internal"
private fun retainMutatedArrayElement(index: Int): Int {
    val operations = arrayOf<ArrayOperation>(ArrayAdd, ArrayMultiply)
    operations[0] = ArrayMultiply
    // Any post-allocation Array.set rejects the projection proof.
    // CHECK-NOT: @Kotlin_Array_get_borrowed
    // CHECK: {{call|invoke}} %struct.ObjHeader* @Kotlin_Array_get(%struct.ObjHeader* {{%[0-9]+}}, i32 {{%[0-9]+}}, %struct.ObjHeader** {{%[0-9]+}})
    return operations[index].apply(index)
}

// CHECK-LABEL: define internal i32 @"kfun:retainAliasedArrayElement#internal"
private fun retainAliasedArrayElement(index: Int): Int {
    val operations = arrayOf<ArrayOperation>(ArrayAdd, ArrayMultiply)
    val alias = operations
    // An alias means the selected owner is not the unique fresh allocation.
    // CHECK-NOT: @Kotlin_Array_get_borrowed
    // CHECK: {{call|invoke}} %struct.ObjHeader* @Kotlin_Array_get(%struct.ObjHeader* {{%[0-9]+}}, i32 {{%[0-9]+}}, %struct.ObjHeader** {{%[0-9]+}})
    return alias[index].apply(index)
}

private class CapturedArrayMutator : ArrayOperation {
    private var owner: Array<ArrayOperation>? = null

    fun install(owner: Array<ArrayOperation>) {
        this.owner = owner
    }

    override fun apply(value: Int): Int {
        owner?.set(0, ArrayMultiply)
        return value
    }
}

// CHECK-LABEL: define internal i32 @"kfun:retainSelfRemovingElement#internal"
private fun retainSelfRemovingElement(): Int {
    val element = CapturedArrayMutator()
    val operations = arrayOf<ArrayOperation>(element)
    // Passing the owner to an element gives the consumer a route to overwrite its own slot.
    element.install(operations)
    // CHECK-NOT: @Kotlin_Array_get_borrowed
    // CHECK: {{call|invoke}} %struct.ObjHeader* @Kotlin_Array_get(%struct.ObjHeader* {{%[0-9]+}}, i32 0, %struct.ObjHeader** {{%[0-9]+}})
    return operations[0].apply(7)
}

private val sharedOperations = arrayOf<ArrayOperation>(ArrayAdd)

// CHECK-LABEL: define internal i32 @"kfun:retainGlobalArrayElement#internal"
private fun retainGlobalArrayElement(): Int {
    // A field/global owner has no local fresh-allocation proof.
    // CHECK-NOT: @Kotlin_Array_get_borrowed
    // CHECK: {{call|invoke}} %struct.ObjHeader* @Kotlin_Array_get(%struct.ObjHeader* {{%[0-9]+}}, i32 0, %struct.ObjHeader** {{%[0-9]+}})
    return sharedOperations[0].apply(8)
}

// CHECK-LABEL: define internal i32 @"kfun:retainConditionalArraySource#internal"
private fun retainConditionalArraySource(useFresh: Boolean): Int {
    val operations = if (useFresh) arrayOf<ArrayOperation>(ArrayAdd) else sharedOperations
    // A constructor somewhere in the initializer is insufficient: every path must return the
    // exact fresh stack allocation, so the shared alternate keeps the owning getter ABI.
    // CHECK-NOT: @Kotlin_Array_get_borrowed
    // CHECK: {{call|invoke}} %struct.ObjHeader* @Kotlin_Array_get(%struct.ObjHeader* {{%[0-9]+}}, i32 0, %struct.ObjHeader** {{%[0-9]+}})
    return operations[0].apply(8)
}

// CHECK-LABEL: define internal i32 @"kfun:retainAcrossThrowingArraySuffix#internal"
private fun retainAcrossThrowingArraySuffix(shouldThrow: Boolean): Int {
    val operations = arrayOf<ArrayOperation>(ArrayAdd)
    // The suffix after the get is not a linear, nonthrowing borrow interval.
    // CHECK-NOT: @Kotlin_Array_get_borrowed
    // CHECK: {{call|invoke}} %struct.ObjHeader* @Kotlin_Array_get(%struct.ObjHeader* {{%[0-9]+}}, i32 0, %struct.ObjHeader** {{%[0-9]+}})
    return consumeArrayOperation(operations[0], if (shouldThrow) error("suffix") else 9)
}

// CHECK-LABEL: define internal i32 @"kfun:retainAcrossOwnerObservingSuffix#internal"
private fun retainAcrossOwnerObservingSuffix(): Int {
    val operations = arrayOf<ArrayOperation>(ArrayAdd)
    // A later owner observation is conservatively rejected even though this particular size read
    // cannot mutate the slot.
    // CHECK-NOT: @Kotlin_Array_get_borrowed
    // CHECK: {{call|invoke}} %struct.ObjHeader* @Kotlin_Array_get(%struct.ObjHeader* {{%[0-9]+}}, i32 0, %struct.ObjHeader** {{%[0-9]+}})
    return consumeArrayOperation(operations[0], operations.size)
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:retainTypedIdentityResult#internal"
private fun retainTypedIdentityResult(index: Int): String {
    val values = arrayOf("zero", "one")
    // String.toString is a typed IDENTITY intrinsic, not an ordinary +0 Kotlin consumer. Its
    // result could otherwise let the borrowed element escape the modeled interval.
    // CHECK-NOT: @Kotlin_Array_get_borrowed
    // CHECK: {{call|invoke}} %struct.ObjHeader* @Kotlin_Array_get(%struct.ObjHeader* {{%[0-9]+}}, i32 {{%[0-9]+}}, %struct.ObjHeader** {{%[0-9]+}})
    return values[index].toString()
}

fun main() {
    check(borrowFreshArrayVirtualReceiver(1) == 2)
    check(borrowAfterIndexEvaluation() == 42)
    check(borrowAcrossGetterAndConsumerUnwind(2) == -1)
    check(retainMutatedArrayElement(0) == 0)
    check(retainAliasedArrayElement(1) == 2)
    check(retainSelfRemovingElement() == 7)
    check(retainGlobalArrayElement() == 9)
    check(retainConditionalArraySource(false) == 9)
    check(retainAcrossThrowingArraySuffix(false) == 10)
    check(retainAcrossOwnerObservingSuffix() == 2)
    check(retainTypedIdentityResult(1) == "one")
}
