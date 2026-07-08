// WITH_STDLIB

fun box(): String {
    val list = emptySequence<String>().toList()
    if (list.isNotEmpty()) return "Fail: $list"
    return "OK"
}
