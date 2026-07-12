import kotlin.native.arc.ArcUnowned

private class ExpiredCapturedPayload(val value: Int)

private fun escapedUnownedReader(): () -> Int {
    val owner = ExpiredCapturedPayload(13)
    @ArcUnowned val captured: ExpiredCapturedPayload = owner
    val reader = { captured.value }
    check(reader() == 13)
    return reader
}

fun main() {
    val reader = escapedUnownedReader()
    reader()
    error("expired @ArcUnowned capture unexpectedly remained readable")
}
