private class Payload(val value: Int)
private class SingleField(val child: Payload?)
private class ManyFields(
    val first: Payload?,
    val second: Payload?,
    val third: Payload?,
    val fourth: Payload?,
    val fifth: Payload?,
    val sixth: Payload?,
    val seventh: Payload?,
    val eighth: Payload?,
)

private data class Result(val checksum: Long, val operations: Long)

private fun destruction(): Result {
    val count = 4_000_000
    val window = arrayOfNulls<SingleField>(1024)
    var checksum = 0L
    repeat(count) { index ->
        val value = SingleField(Payload(index))
        window[index and (window.size - 1)] = value
        checksum += value.child!!.value
    }
    return Result(checksum + window.sumOf { it?.child?.value?.toLong() ?: 0L }, count.toLong())
}

private fun fields(): Result {
    val count = 750_000
    val window = arrayOfNulls<ManyFields>(512)
    var checksum = 0L
    repeat(count) { index ->
        val value = ManyFields(
            Payload(index), Payload(index + 1), Payload(index + 2), Payload(index + 3),
            Payload(index + 4), Payload(index + 5), Payload(index + 6), Payload(index + 7),
        )
        window[index and (window.size - 1)] = value
        checksum += value.first!!.value + value.eighth!!.value
    }
    return Result(checksum + window.sumOf { it?.fourth?.value?.toLong() ?: 0L }, count.toLong())
}

fun main(args: Array<String>) {
    val scenario = args.single()
    val result = when (scenario) {
        "destruction" -> destruction()
        "fields" -> fields()
        else -> error("unknown scenario: $scenario")
    }
    println("ARC_DESTROY_BENCH_OK scenario=$scenario checksum=${result.checksum} operations=${result.operations}")
}
