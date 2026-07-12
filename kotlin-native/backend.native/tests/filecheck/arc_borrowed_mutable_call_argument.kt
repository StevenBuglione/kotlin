import kotlin.native.arc.ArcWeak

private class BorrowedPayload(val value: Int)

private class BorrowedSink(val payload: BorrowedPayload)

private class BorrowedReceiver {
    fun consume(marker: Int, payload: BorrowedPayload): Int = marker + payload.value
}

private interface VirtualBorrowedReceiver {
    fun consume(payload: BorrowedPayload, marker: Int): Int
}

private class VirtualBorrowedReceiverImpl : VirtualBorrowedReceiver {
    override fun consume(payload: BorrowedPayload, marker: Int): Int = payload.value + marker
}

private class OtherVirtualBorrowedReceiverImpl : VirtualBorrowedReceiver {
    override fun consume(payload: BorrowedPayload, marker: Int): Int = payload.value - marker
}

private class WeakPromotionHolder(owner: BorrowedPayload) {
    @ArcWeak
    var weak: BorrowedPayload? = owner
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:borrowGuaranteedAlias#internal"
private fun borrowGuaranteedAlias(owner: BorrowedPayload): BorrowedSink {
    // The sole local alias is proven to remain inside the guaranteed parameter lifetime. It is
    // represented as an SSA value even though source semantics spell it `var`.
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: {{call|invoke}} void @"kfun:BorrowedSink.<init>#internal"(%struct.ObjHeader* {{%[0-9]+}}, %struct.ObjHeader* %0)
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: ret %struct.ObjHeader*
    var alias = owner
    val sink = BorrowedSink(alias)
    return sink
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:retainReassignedGuaranteedAlias#internal"
private fun retainReassignedGuaranteedAlias(owner: BorrowedPayload): BorrowedSink {
    // A reassignment rejects the SSA authorization and retains the ordinary owning local slot.
    // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %alias, %struct.ObjHeader* %0)
    var alias = owner
    // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %alias
    alias = BorrowedPayload(7)
    val sink = BorrowedSink(alias)
    return sink
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:retainCapturedGuaranteedAlias#internal"
private fun retainCapturedGuaranteedAlias(owner: BorrowedPayload): BorrowedSink {
    // A nested function capture rejects the authorization and keeps an owning capture field.
    // CHECK: call void @UpdateHeapRef(%struct.ObjHeader** {{%[0-9]+}}, %struct.ObjHeader* %0)
    var alias = owner
    fun capturedValue(): Int = alias.value
    check(capturedValue() == owner.value)
    val sink = BorrowedSink(owner)
    return sink
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:retainReturnedGuaranteedAlias#internal"
private fun retainReturnedGuaranteedAlias(owner: BorrowedPayload): BorrowedPayload {
    // Returning the alias transfers +1 ownership and therefore keeps the normal local slot.
    // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %alias, %struct.ObjHeader* %0)
    var alias = owner
    return alias
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:borrowLastMutableArgument#internal"
private fun borrowLastMutableArgument(): BorrowedSink {
    var owner = BorrowedPayload(42)
    // The mutable owner slot is the +1 root. Its final constructor argument is passed +0 and
    // must not be copied into an anonymous stack root that survives until LeaveFrame.
    // CHECK: call %struct.ObjHeader* @AllocInstance(%struct.TypeInfo* @"kclass:BorrowedPayload#internal", %struct.ObjHeader** %owner)
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: [[BORROWED:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %owner
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: {{call|invoke}} void @"kfun:BorrowedSink.<init>#internal"(%struct.ObjHeader* {{%[0-9]+}}, %struct.ObjHeader* [[BORROWED]])
    val sink = BorrowedSink(owner)
    owner = BorrowedPayload(7)
    return sink
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:borrowLoweredCheckNotNullArgument#internal"
private fun borrowLoweredCheckNotNullArgument(): BorrowedSink {
    var owner: BorrowedPayload? = BorrowedPayload(42)
    // BuiltinOperatorLowering turns `!!` into a temp/null-check/result block. The read remains
    // borrowed because that exact generated wrapper contains no sibling side effects.
    // CHECK: call %struct.ObjHeader* @AllocInstance(%struct.TypeInfo* @"kclass:BorrowedPayload#internal", %struct.ObjHeader** %owner)
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: [[CHECKED:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %owner
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: {{call|invoke}} void @"kfun:BorrowedSink.<init>#internal"(%struct.ObjHeader* {{%[0-9]+}}, %struct.ObjHeader* [[CHECKED]])
    val sink = BorrowedSink(owner!!)
    owner = null
    return sink
}

private fun consumeStablePrefix(first: BorrowedPayload, marker: Int, second: BorrowedPayload): Int =
    first.value + marker + second.value

private fun consumeThreeReferences(
    first: BorrowedPayload,
    second: BorrowedPayload,
    third: BorrowedPayload,
): Int = first.value + second.value + third.value

// CHECK-LABEL: define internal i32 @"kfun:borrowStablePrefixArgument#internal"
private fun borrowStablePrefixArgument(): Int {
    var first = BorrowedPayload(10)
    var second = BorrowedPayload(20)
    // The first argument's suffix contains only a primitive constant and another local read. Both
    // mutable slots remain +1 owners, so neither call argument needs an anonymous owning root.
    // CHECK: [[PREFIX_FIRST:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %first
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: [[PREFIX_SECOND:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %second
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: {{call|invoke}} i32 @"kfun:consumeStablePrefix#internal"(%struct.ObjHeader* [[PREFIX_FIRST]], i32 7, %struct.ObjHeader* [[PREFIX_SECOND]])
    val result = consumeStablePrefix(first, 7, second)
    first = BorrowedPayload(30)
    second = BorrowedPayload(40)
    return result
}

// CHECK-LABEL: define internal i32 @"kfun:borrowStableMutableReceiver#internal"
private fun borrowStableMutableReceiver(): Int {
    var receiver = BorrowedReceiver()
    var payload = BorrowedPayload(11)
    // Dispatch receivers participate in the same documented argument evaluation order.
    // CHECK: [[PREFIX_RECEIVER:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %receiver
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: [[RECEIVER_PAYLOAD:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %payload
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: {{call|invoke}} i32 @"kfun:BorrowedReceiver.consume#internal"(%struct.ObjHeader* [[PREFIX_RECEIVER]], i32 5, %struct.ObjHeader* [[RECEIVER_PAYLOAD]])
    val result = receiver.consume(5, payload)
    receiver = BorrowedReceiver()
    payload = BorrowedPayload(12)
    return result
}

// CHECK-LABEL: define internal i32 @"kfun:borrowAllStableReferenceArguments#internal"
private fun borrowAllStableReferenceArguments(): Int {
    var first = BorrowedPayload(1)
    var second = BorrowedPayload(2)
    var third = BorrowedPayload(3)
    // CHECK: [[MULTI_FIRST:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %first
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: [[MULTI_SECOND:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %second
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: [[MULTI_THIRD:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %third
    // CHECK-NOT: call void @UpdateStackRef
    // CHECK: {{call|invoke}} i32 @"kfun:consumeThreeReferences#internal"(%struct.ObjHeader* [[MULTI_FIRST]], %struct.ObjHeader* [[MULTI_SECOND]], %struct.ObjHeader* [[MULTI_THIRD]])
    val result = consumeThreeReferences(first, second, third)
    first = BorrowedPayload(4)
    second = BorrowedPayload(5)
    third = BorrowedPayload(6)
    return result
}

// CHECK-LABEL: define internal i32 @"kfun:retainBeforeSuffixReassignment#internal"
private fun retainBeforeSuffixReassignment(): Int {
    var owner = BorrowedPayload(1)
    var other = BorrowedPayload(3)
    // Argument evaluation must preserve the old owner even though a later inline suffix replaces
    // its owning slot before the target call.
    // CHECK: [[REASSIGNED_OLD:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %owner
    // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %{{[0-9]+}}, %struct.ObjHeader* [[REASSIGNED_OLD]])
    // CHECK: {{call|invoke}} i32 @"kfun:consumeStablePrefix#internal"
    val result = consumeStablePrefix(owner, run {
        owner = BorrowedPayload(2)
        7
    }, other)
    return result + owner.value
}

private fun consumeCapturedOwner(owner: BorrowedPayload, mutate: () -> Unit): Int {
    mutate()
    return owner.value
}

// CHECK-LABEL: define internal i32 @"kfun:retainCapturedMutableArgument#internal"
private fun retainCapturedMutableArgument(): Int {
    var owner = BorrowedPayload(1)
    val mutate = { owner = BorrowedPayload(2) }
    // The callee clears the captured owner before reading its +0 parameter. The ordinary argument
    // promotion is therefore required to keep the old object alive.
    // CHECK: [[CAPTURED_OLD:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** {{%[0-9]+}}
    // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %{{[0-9]+}}, %struct.ObjHeader* [[CAPTURED_OLD]])
    // CHECK: {{call|invoke}} i32 @"kfun:consumeCapturedOwner#internal"
    val oldValue = consumeCapturedOwner(owner, mutate)
    return oldValue * 10 + owner.value
}

// CHECK-LABEL: define internal i32 @"kfun:retainVirtualPrefixArgument#internal"
private fun retainVirtualPrefixArgument(receiver: VirtualBorrowedReceiver): Int {
    var owner = BorrowedPayload(9)
    // A virtual target has no fixed direct-call ownership boundary in this first slice.
    // CHECK: [[VIRTUAL_OWNER:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %owner
    // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %{{[0-9]+}}, %struct.ObjHeader* [[VIRTUAL_OWNER]])
    val result = receiver.consume(owner, 4)
    owner = BorrowedPayload(10)
    return result
}

// CHECK-LABEL: define internal i32 @"kfun:retainAcrossThrowingSuffix#internal"
private fun retainAcrossThrowingSuffix(shouldThrow: Boolean): Int {
    var owner = BorrowedPayload(8)
    var other = BorrowedPayload(2)
    return try {
        // Explicit exceptional control flow is a barrier until the ownership planner models every
        // suffix edge explicitly.
        // CHECK: [[THROW_OWNER:%[0-9]+]] = load %struct.ObjHeader*, %struct.ObjHeader** %owner
        // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %{{[0-9]+}}, %struct.ObjHeader* [[THROW_OWNER]])
        // CHECK: {{call|invoke}} i32 @"kfun:consumeStablePrefix#internal"
        consumeStablePrefix(owner, if (shouldThrow) throw IllegalStateException("suffix") else 6, other)
    } catch (_: IllegalStateException) {
        owner.value
    }
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:retainForArbitraryArgumentBlock#internal"
private fun retainForArbitraryArgumentBlock(): BorrowedSink {
    var owner = BorrowedPayload(1)
    // An arbitrary inline block is deliberately not authorized. Its final read keeps the normal
    // anonymous +1 root, visible as an UpdateStackRef to a numeric temporary slot.
    // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %owner
    // CHECK: call void @UpdateStackRef(%struct.ObjHeader** %{{[0-9]+}}
    // CHECK: {{call|invoke}} void @"kfun:BorrowedSink.<init>#internal"
    return BorrowedSink(run {
        owner = BorrowedPayload(2)
        owner
    })
}

// CHECK-LABEL: define internal void @"kfun:consumeScopedWeakPromotion#internal"
private fun consumeScopedWeakPromotion(holder: WeakPromotionHolder) {
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:WeakPromotionHolder.<get-weak>#internal"(%struct.ObjHeader* {{%[0-9]+}}, %struct.ObjHeader** [[PROMOTION:%[0-9]+]])
    // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[PROMOTION]], %struct.ObjHeader* null)
    check(holder.weak?.value == 42)
}

private fun consumePayload(payload: BorrowedPayload?): Int = payload?.value ?: -1

// CHECK-LABEL: define internal void @"kfun:consumeInlineRunWeakPromotion#internal"
private fun consumeInlineRunWeakPromotion(holder: WeakPromotionHolder) {
    // The inlined lambda places the accessor call directly in a nested IrReturn value. Visit that
    // value, keep its anonymous scoped root live through the consumer, and clear it at the boundary.
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:WeakPromotionHolder.<get-weak>#internal"(%struct.ObjHeader* {{%[0-9]+}}, %struct.ObjHeader** [[INLINE_PROMOTION:%[0-9]+]])
    // CHECK: {{call|invoke}} i32 @"kfun:consumePayload#internal"
    // CHECK: call void @UpdateStackRef(%struct.ObjHeader** [[INLINE_PROMOTION]], %struct.ObjHeader* null)
    check(consumePayload(run { holder.weak }) == 42)
}

// CHECK-LABEL: define internal %struct.ObjHeader* @"kfun:returnDurableWeakPromotion#internal"
private fun returnDurableWeakPromotion(holder: WeakPromotionHolder): BorrowedPayload? {
    // A value assigned and returned is not a scoped full-expression promotion.
    // CHECK: {{call|invoke}} %struct.ObjHeader* @"kfun:WeakPromotionHolder.<get-weak>#internal"(%struct.ObjHeader* {{%[0-9]+}}, %struct.ObjHeader** [[DURABLE:%[0-9]+]])
    // CHECK-NOT: call void @UpdateStackRef(%struct.ObjHeader** [[DURABLE]], %struct.ObjHeader* null)
    // CHECK: ret %struct.ObjHeader*
    val durable = holder.weak
    return durable
}

// CHECK-LABEL: define internal i32 @"kfun:nonescapingMutableConstructor#internal"
private fun nonescapingMutableConstructor(): Int {
    // Escape analysis may stack/local-allocate this constructor. Such allocations must never use
    // the predeclared mutable slot forwarding path.
    // CHECK-NOT: call %struct.ObjHeader* @AllocInstance({{%.*}}, %struct.ObjHeader** %local)
    // CHECK: ret i32
    var local = BorrowedPayload(5)
    return local.value
}

fun main() {
    val guaranteedOwner = BorrowedPayload(42)
    check(borrowGuaranteedAlias(guaranteedOwner).payload === guaranteedOwner)
    check(retainReassignedGuaranteedAlias(guaranteedOwner).payload.value == 7)
    check(retainCapturedGuaranteedAlias(guaranteedOwner).payload === guaranteedOwner)
    check(retainReturnedGuaranteedAlias(guaranteedOwner) === guaranteedOwner)
    check(borrowLastMutableArgument().payload.value == 42)
    check(borrowLoweredCheckNotNullArgument().payload.value == 42)
    check(borrowStablePrefixArgument() == 37)
    check(borrowStableMutableReceiver() == 16)
    check(borrowAllStableReferenceArguments() == 6)
    check(retainBeforeSuffixReassignment() == 13)
    check(retainCapturedMutableArgument() == 12)
    check(retainVirtualPrefixArgument(VirtualBorrowedReceiverImpl()) == 13)
    check(retainVirtualPrefixArgument(OtherVirtualBorrowedReceiverImpl()) == 5)
    check(retainAcrossThrowingSuffix(false) == 16)
    check(retainAcrossThrowingSuffix(true) == 8)
    check(retainForArbitraryArgumentBlock().payload.value == 2)
    val promotionOwner = BorrowedPayload(42)
    val promotionHolder = WeakPromotionHolder(promotionOwner)
    consumeScopedWeakPromotion(promotionHolder)
    consumeInlineRunWeakPromotion(promotionHolder)
    check(returnDurableWeakPromotion(promotionHolder) === promotionOwner)
    check(nonescapingMutableConstructor() == 5)
}
