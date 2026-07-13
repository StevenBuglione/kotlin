import arc.benchmark.cinterop.arc_benchmark_strlen_string

fun main() {
    val count = 16_384
    var value = "kotlin-native-arc-0"
    var checksum = 0L
    var index = 0
    while (index < count) {
        if ((index and 1023) == 0) value = "kotlin-native-arc-${index and 7}"
        checksum += arc_benchmark_strlen_string(value).toLong()
        index++
    }
    check(checksum == 311_296L)
    println("OK")
}
