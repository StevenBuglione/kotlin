private class Payload(val value: Int)

private fun produce(): Payload = Payload(42)

private fun forwardOwnedResultThroughAliases(): Payload {
    val owned = produce()
    val firstAlias = owned
    val returnedAlias = firstAlias
    return returnedAlias
}

fun main() {
    val result = forwardOwnedResultThroughAliases()
    check(result.value == 42)
    println("OK")
}
