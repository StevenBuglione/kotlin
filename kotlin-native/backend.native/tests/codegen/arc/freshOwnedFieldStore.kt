/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

import kotlin.native.arc.ArcDeinit

private val deinitCounts = IntArray(8)
private var failedConstructorDeinitRan = false

private class ConstructorFailure : Throwable()

private class FreshPayload(val value: Int) {
    init {
        if (value < 0) throw ConstructorFailure()
    }

    @ArcDeinit
    private fun deinit() {
        if (value < 0) {
            failedConstructorDeinitRan = true
        } else {
            deinitCounts[value]++
        }
    }
}

private class FreshHolder(var reference: FreshPayload?)

// OPT-LABEL: define internal i32 @"kfun:selectedFreshStores#internal"
// OPT-NOT: call void @UpdateHeapRef({{.*}}, %struct.ObjHeader* %
// OPT: call void @MoveReferenceIntoHeapSlotArc
// OPT-NOT: call void @UpdateHeapRef({{.*}}, %struct.ObjHeader* %
// The third static move is on FreshPayload(-1)'s never-taken normal successor. Its unwind edge
// bypasses the move and leaves the incompletely initialized object in the source slot for cleanup.
// OPT: call void @MoveReferenceIntoHeapSlotArc
// OPT-NOT: call void @UpdateHeapRef({{.*}}, %struct.ObjHeader* %
// OPT: call void @MoveReferenceIntoHeapSlotArc
// OPT-NOT: call void @UpdateHeapRef({{.*}}, %struct.ObjHeader* %
// OPT: call void @UpdateHeapRef({{.*}}, %struct.ObjHeader* null)
// OPT: ret i32

// FALLBACK-LABEL: define internal i32 @"kfun:selectedFreshStores#internal"
// FALLBACK-NOT: call void @MoveReferenceIntoHeapSlotArc
// FALLBACK: call void @UpdateHeapRef
private fun selectedFreshStores(): Int {
    val holder = FreshHolder(null)
    holder.reference = FreshPayload(1)
    holder.reference = FreshPayload(2)
    check(deinitCounts[1] == 1)

    try {
        holder.reference = FreshPayload(-1)
        error("constructor unexpectedly returned")
    } catch (_: ConstructorFailure) {
    }
    check(holder.reference?.value == 2)
    check(!failedConstructorDeinitRan)
    return holder.reference!!.value
}

// OPT-LABEL: define internal void @"kfun:parameterReceiverFallsBack#internal"
// OPT-NOT: call void @MoveReferenceIntoHeapSlotArc
// OPT: call void @UpdateHeapRef
private fun parameterReceiverFallsBack(holder: FreshHolder) {
    holder.reference = FreshPayload(4)
}

// OPT-LABEL: define internal void @"kfun:mutableReceiverFallsBack#internal"
// OPT-NOT: call void @MoveReferenceIntoHeapSlotArc
// OPT: call void @UpdateHeapRef
private fun mutableReceiverFallsBack() {
    var holder = FreshHolder(null)
    holder.reference = FreshPayload(5)
    check(holder.reference?.value == 5)
}

fun main() {
    check(selectedFreshStores() == 2)
    check(deinitCounts[1] == 1)
    check(deinitCounts[2] == 1)
    check(!failedConstructorDeinitRan)

    val parameterHolder = FreshHolder(null)
    parameterReceiverFallsBack(parameterHolder)
    check(parameterHolder.reference?.value == 4)
    mutableReceiverFallsBack()
    println("ARC_FRESH_OWNED_FIELD_STORE_OK")
}
