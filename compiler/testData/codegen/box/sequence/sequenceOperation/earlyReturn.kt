// WITH_STDLIB

fun box(): String {
    val list = sequence {
        yield(1)
        if (true) return@sequence
        yield(2)
    }.toList()

    if (list != listOf(1)) return "Fail: $list"
    return "OK"
}
