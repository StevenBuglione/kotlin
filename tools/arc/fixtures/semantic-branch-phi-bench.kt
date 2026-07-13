import kotlin.system.measureNanoTime

private class SemanticPhiBenchPayload(val value: Int)

private fun selectLeft(
    flag: Boolean,
    left: SemanticPhiBenchPayload,
    right: SemanticPhiBenchPayload,
): Boolean {
    var selected: SemanticPhiBenchPayload? = null
    if (flag) selected = left else selected = right
    return selected === left
}

private fun selectRight(
    flag: Boolean,
    left: SemanticPhiBenchPayload,
    right: SemanticPhiBenchPayload,
): Boolean {
    var selected: SemanticPhiBenchPayload? = null
    if (flag) selected = left else selected = right
    return selected === right
}

fun main(args: Array<String>) {
    val operations = arrayOf(::selectLeft, ::selectRight)
    val operation = operations[(args.firstOrNull()?.toIntOrNull() ?: 0) and 1]
    val iterations = args.getOrNull(1)?.toIntOrNull() ?: 50_000_000
    val left = SemanticPhiBenchPayload(1)
    val right = SemanticPhiBenchPayload(2)
    var checksum = 0
    val elapsedNanos = measureNanoTime {
        var index = 0
        while (index < iterations) {
            if (operation((index and 1) == 0, left, right)) checksum++
            index++
        }
    }
    check(checksum == iterations / 2)
    println("ARC_SEMANTIC_BRANCH_PHI_BENCH:$checksum:$elapsedNanos")
}
