/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

private class Payload(val value: Int)

private fun consume(first: Payload, marker: Int, second: Payload): Long {
    val left = first.value.toLong()
    val right = second.value.toLong()
    return when (marker and 3) {
        0 -> left + marker + right
        1 -> left * 2 + marker + right
        2 -> left * 3 + marker + right
        3 -> left * 4 + marker + right
        else -> error("unreachable")
    }
}

// Keep the body address-taken so the final binary proves the non-inlined call path too.
private val consumeReference: (Payload, Int, Payload) -> Long = ::consume

private class ExpectedFailure : Throwable()

private fun throwThroughOwnedFrame(value: Int): Nothing {
    val payload = Payload(value)
    if (payload.value == value) throw ExpectedFailure()
    error("unreachable")
}

fun main() {
    val first = Payload(3)
    val second = Payload(7)
    var checksum = 0L
    repeat(1_000_000) { marker -> checksum += consume(first, marker, second) }
    check(consumeReference(first, 5, second) == 18L)
    try {
        throwThroughOwnedFrame(41)
    } catch (_: ExpectedFailure) {
        // The nonempty exceptional frame must remain balanced beside an elided hot frame.
    }
    check(checksum == 500_014_000_000L)
    println("ARC_EMPTY_FRAME_OK")
}

// FINAL-NOT: konan.arc.empty-frame-elision
// FINAL-LABEL: define internal fastcc i64 @"kfun:consume#internal"
// FINAL-NOT: @_ZN12_GLOBAL__N_112currentFrameE
// FINAL-NOT: @LeaveFrameArc
// FINAL: ret i64
// FINAL-LABEL: define{{.*}} void @"kfun:#main(){}"
// FINAL: @_ZN12_GLOBAL__N_112currentFrameE
// FINAL: landingpad:
// FINAL: SetCurrentFrame.exit{{[0-9]*}}:
// FINAL: store {{.*}} @_ZN12_GLOBAL__N_112currentFrameE
// FINAL: LeaveFrame.exit{{[0-9]*}}:
// FINAL-NOT: asm sideeffect
// FINAL-NOT: konan.arc.empty-frame-elision
