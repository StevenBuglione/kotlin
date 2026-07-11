private class Payload(val value: Int, val padding: Long = value.toLong())

private class ChainNode(val value: Int, val next: ChainNode?)

private fun allocationWork(): Long {
    val window = arrayOfNulls<Payload>(4096)
    var checksum = 0L
    repeat(8_000_000) { index ->
        val slot = index and (window.size - 1)
        val value = Payload(index)
        window[slot] = value
        checksum += value.value + value.padding
    }
    return checksum + window.sumOf { it?.value?.toLong() ?: 0L }
}

private fun destructionWork(): Long {
    var head: ChainNode? = null
    repeat(1_000_000) { index -> head = ChainNode(index, head) }
    var checksum = 0L
    var cursor = head
    while (cursor != null) {
        checksum += cursor.value
        cursor = cursor.next
    }
    head = null
    return checksum
}

fun main(args: Array<String>) {
    val scenario = args.singleOrNull() ?: error("expected allocation or destruction")
    val checksum = when (scenario) {
        "allocation" -> allocationWork()
        "destruction" -> destructionWork()
        else -> error("unknown scenario: $scenario")
    }
    println("ARC_BENCH_OK scenario=$scenario checksum=$checksum")
}
