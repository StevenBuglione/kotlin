import kotlin.native.arc.ArcUnowned

private class Target(val value: Int)

private class Holder(target: Target) {
    @ArcUnowned
    var target: Target = target
}

private fun holderWithExpiredTarget(): Holder {
    val target = Target(42)
    return Holder(target)
}

fun main() {
    val holder = holderWithExpiredTarget()
    println(holder.target.value)
    error("expired @ArcUnowned access unexpectedly survived")
}
