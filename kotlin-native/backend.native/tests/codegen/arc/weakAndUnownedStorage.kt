// WITH_STDLIB

import kotlin.native.arc.ArcUnowned
import kotlin.native.arc.ArcWeak

private class Payload(val value: Int)

private class Holder(owner: Payload) {
    @ArcWeak
    var weak: Payload? = owner

    @ArcUnowned
    var unowned: Payload = owner
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

    println("OK")
}
