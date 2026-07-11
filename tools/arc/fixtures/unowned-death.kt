import kotlin.native.arc.ArcUnowned
import kotlin.native.arc.ArcWeak

private class Target(val value: Int)

private class Holder(target: Target) {
    @ArcWeak
    var weakTarget: Target? = target

    @ArcUnowned
    var target: Target = target

    fun reassign(target: Target) {
        weakTarget = target
        this.target = target
    }
}

private class Churn(val value: Int)

private fun churnAllocator(): Long {
    val slots = arrayOfNulls<Churn>(256)
    var checksum = 0L
    repeat(100_000) { index ->
        slots[index and 255] = Churn(index + 1)
        checksum += slots[(index xor 127) and 255]?.value ?: 0
    }
    return checksum
}

private fun holderWithReassignedExpiredTarget(): Holder {
    val first = Target(1)
    val holder = Holder(first)
    check(holder.target.value == 1)

    val second = Target(42)
    holder.reassign(second)
    check(holder.target.value == 42)
    return holder
}

fun main() {
    val holder = holderWithReassignedExpiredTarget()
    check(holder.weakTarget == null) { "the reassigned target must be dead before allocator churn" }
    check(churnAllocator() > 0L)

    try {
        println(holder.target.value)
    } catch (failure: Throwable) {
        error("expired @ArcUnowned access was catchable as ${failure::class.simpleName}")
    }
    error("expired @ArcUnowned access unexpectedly survived")
}
