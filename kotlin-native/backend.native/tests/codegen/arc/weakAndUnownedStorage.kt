// WITH_STDLIB

import kotlin.native.arc.ArcUnowned
import kotlin.native.arc.ArcWeak
import kotlin.native.arc.ArcDeinit

private class Payload(val value: Int)

private class ExpectedInitializationFailure : Exception()
private class ExpectedPromotionFailure : Exception()

private class ThrowsDuringInitialization {
    init {
        throw ExpectedInitializationFailure()
    }
}

private var timingDeinitCount = 0

private class TimingPayload {
    @ArcDeinit
    private fun deinit() {
        timingDeinitCount++
    }
}

private class TimingWeakHolder(owner: TimingPayload) {
    @ArcWeak
    var weak: TimingPayload? = owner
}

private class TimingStrongBox(var strong: TimingPayload?)

private fun clear(box: TimingStrongBox): Int {
    box.strong = null
    check(timingDeinitCount == 0)
    return 0
}

private fun consume(payload: TimingPayload, ignored: Int) {
    payload.hashCode()
    check(ignored == 0)
    check(timingDeinitCount == 0)
}

private class Holder(owner: Payload) {
    @ArcWeak
    var weak: Payload? = owner

    @ArcUnowned
    var unowned: Payload = owner
}

private fun throwAfterWeakPromotion(holder: Holder): Unit = run {
    holder.weak
    throw ExpectedPromotionFailure()
}

fun main() {
    var strong: Payload? = Payload(42)
    val holder = Holder(strong!!)
    check(holder.weak?.value == 42)
    check(holder.unowned.value == 42)

    strong = null
    check(holder.weak == null)

    var localOwner: Payload? = Payload(7)
    @ArcWeak var captured: Payload? = localOwner
    val readCaptured = { captured?.value }
    check(readCaptured() == 7)
    localOwner = null
    check(readCaptured() == null)

    var durableOwner: Payload? = Payload(9)
    val durableHolder = Holder(durableOwner!!)
    val durablePromotion = durableHolder.weak
    durableOwner = null
    check(durablePromotion?.value == 9)
    check(durableHolder.weak?.value == 9)

    timingDeinitCount = 0
    var argumentOwner: TimingPayload? = TimingPayload()
    val argumentBox = TimingStrongBox(argumentOwner)
    val argumentHolder = TimingWeakHolder(argumentOwner!!)
    argumentOwner = null
    // The weak promotion must survive evaluation of the later clearing argument and the call.
    consume(argumentHolder.weak!!, clear(argumentBox))
    check(timingDeinitCount == 1)
    check(argumentHolder.weak == null)

    // The ARC constructor-forwarding side plan predeclares a normal root slot. Function prologue
    // zero-initialization must make cleanup safe when construction unwinds before producing a value.
    try {
        var throwing = ThrowsDuringInitialization()
        check(throwing.hashCode() != 0)
    } catch (_: ExpectedInitializationFailure) {
        // Expected.
    }

    // A selected promotion boundary may terminate without a normal continuation. Its callee
    // frame owns the promotion until exception cleanup; the caller catches only after LeaveFrame.
    val throwingOwner = Payload(13)
    val throwingHolder = Holder(throwingOwner)
    try {
        throwAfterWeakPromotion(throwingHolder)
    } catch (_: ExpectedPromotionFailure) {
        check(throwingHolder.weak === throwingOwner)
    }

    println("OK")
}
