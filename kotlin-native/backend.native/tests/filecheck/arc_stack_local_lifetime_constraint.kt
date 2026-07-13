/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

import kotlin.native.arc.ArcDeinit

private class StackLocalBox(var payload: Any?)

private class ArcDeinitBox(var payload: Any?) {
    @ArcDeinit
    private fun deinit() = Unit
}

// OPT-LABEL: define internal %struct.ObjHeader* @"kfun:exactPhysicalStackLocal#internal"
private fun exactPhysicalStackLocal(value: Any?): Any? {
    val box = StackLocalBox(null)
    // Escape analysis gives this allocation STACK lifetime and evaluateSetField receives the exact
    // registered ObjHeader pointer. The physical stack object may safely own any reference.
    // OPT-NOT: call void @CheckLifetimesConstraint
    // OPT: ret %struct.ObjHeader*
    box.payload = value
    return box.payload
}

// OPT-LABEL: define internal void @"kfun:parameterReceiver#internal"
private fun parameterReceiver(receiver: StackLocalBox, value: Any?) {
    // Parameters are borrowed values, not physical stack-allocation identities in this function.
    // OPT: call void @CheckLifetimesConstraint
    receiver.payload = value
}

// OPT-LABEL: define internal void @"kfun:mutableOrPhiReceiver#internal"
private fun mutableOrPhiReceiver(selectFresh: Boolean, value: Any?) {
    var receiver = StackLocalBox(null)
    if (selectFresh) receiver = StackLocalBox(null)
    // A mutable load/phi may designate either allocation and must fail the exact-identity proof.
    // OPT: call void @CheckLifetimesConstraint
    receiver.payload = value
}

private var escapedReceiver: StackLocalBox? = null

// OPT-LABEL: define internal void @"kfun:heapReceiver#internal"
private fun heapReceiver(value: Any?) {
    val receiver = StackLocalBox(null)
    escapedReceiver = receiver
    // Escape analysis assigns this allocation a non-stack lifetime.
    // OPT: call void @CheckLifetimesConstraint
    receiver.payload = value
}

// OPT-LABEL: define internal void @"kfun:arcDeinitPromotedReceiver#internal"
private fun arcDeinitPromotedReceiver(value: Any?) {
    // Even when escape analysis requests STACK/LOCAL lifetime, ARC deinitialization promotes this
    // object to the heap so its deterministic hook remains valid.
    // OPT: call void @CheckLifetimesConstraint
    ArcDeinitBox(null).payload = value
}

// NEG-LABEL: define internal %struct.ObjHeader* @"kfun:exactPhysicalStackLocal#internal"
// NEG: call void @CheckLifetimesConstraint

fun main() {
    check(exactPhysicalStackLocal("stack") == "stack")
    parameterReceiver(StackLocalBox(null), "parameter")
    mutableOrPhiReceiver(false, "mutable")
    mutableOrPhiReceiver(true, "phi")
    heapReceiver("heap")
    arcDeinitPromotedReceiver("deinit")
    println("OK")
}
