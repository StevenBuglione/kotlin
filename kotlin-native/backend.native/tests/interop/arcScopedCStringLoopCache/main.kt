import arc.scopedcstring.arc_scoped_cstring_length

private fun normalExit(): Long {
    var value = "kotlin-native-arc-0"
    var checksum = 0L
    var index = 0
    while (index < 16_384) {
        if ((index and 1023) == 0) value = "kotlin-native-arc-${index and 7}"
        checksum += arc_scoped_cstring_length(value).toLong()
        index++
    }
    return checksum
}

private fun exceptionalExit(): Boolean {
    var value = "prefix\u0000suffix-0"
    return try {
        var index = 0
        while (index < 4_096) {
            if ((index and 255) == 0) value = "prefix\u0000suffix-${index and 3}"
            check(arc_scoped_cstring_length(value).toLong() == 6L)
            if (index == 2_047) error("intentional cache-scope unwind")
            index++
        }
        false
    } catch (error: IllegalStateException) {
        error.message == "intentional cache-scope unwind"
    }
}

fun main() {
    check(normalExit() == 311_296L)
    check(exceptionalExit())
    println("ARC_SCOPED_CSTRING_LOOP_CACHE_OK")
}
