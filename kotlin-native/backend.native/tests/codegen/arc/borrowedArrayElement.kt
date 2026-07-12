private interface ExecutableOperation {
    fun apply(value: Int): Int
}

private class TrackedOperation(
    private val delta: Int,
    private val throws: Boolean = false,
) : ExecutableOperation {
    override fun apply(value: Int): Int {
        if (throws) error("consumer")
        return value + delta
    }
}

private fun normalBorrow(): Int {
    val operations = arrayOf<ExecutableOperation>(TrackedOperation(1), TrackedOperation(2))
    return operations[1].apply(40)
}

private fun throwingConsumer(): Int = try {
    val operations = arrayOf<ExecutableOperation>(TrackedOperation(0, throws = true))
    operations[0].apply(0)
} catch (_: IllegalStateException) {
    17
}

private fun throwingBounds(): Int = try {
    val operations = arrayOf<ExecutableOperation>(TrackedOperation(0))
    operations[2].apply(0)
} catch (_: IndexOutOfBoundsException) {
    23
}

fun main() {
    check(normalBorrow() == 42)
    check(throwingConsumer() == 17)
    check(throwingBounds() == 23)
    println("OK")
}
