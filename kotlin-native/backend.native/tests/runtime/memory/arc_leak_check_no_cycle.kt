fun main() {
    repeat(10_000) { Any() }
    println("ARC_LEAK_CHECK_NO_CYCLE")
}
