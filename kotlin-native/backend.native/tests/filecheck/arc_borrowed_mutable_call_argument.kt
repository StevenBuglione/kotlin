import kotlin.native.arc.ArcWeak

private class BorrowedPayload(val value: Int)

private class BorrowedSink(val payload: BorrowedPayload)

private class WeakPromotionHolder(owner: BorrowedPayload) {
    @ArcWeak
    var weak: BorrowedPayload? = owner
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
    check(borrowLastMutableArgument().payload.value == 42)
    check(borrowLoweredCheckNotNullArgument().payload.value == 42)
    check(retainForArbitraryArgumentBlock().payload.value == 2)
    val promotionOwner = BorrowedPayload(42)
    val promotionHolder = WeakPromotionHolder(promotionOwner)
    consumeScopedWeakPromotion(promotionHolder)
    check(returnDurableWeakPromotion(promotionHolder) === promotionOwner)
    check(nonescapingMutableConstructor() == 5)
}
