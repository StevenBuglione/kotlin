private class ArcShutdownCycle(var next: ArcShutdownCycle? = null)

fun main() {
    val first = ArcShutdownCycle()
    val second = ArcShutdownCycle()
    first.next = second
    second.next = first
    println("ARC_LEAK_CHECK_FIXTURE")
}
