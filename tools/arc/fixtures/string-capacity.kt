/*
 * Focused emitted-code fixture for compiler-generated ARC string concatenation capacity planning.
 */

private fun generated(index: Int): String = "arc-${index and 1023}-${index.toString(16)}"

private fun explicit(index: Int): String = StringBuilder()
    .append("arc-")
    .append(index and 1023)
    .append('-')
    .append(index.toString(16))
    .toString()

fun main() {
    var checksum = 0L
    repeat(1024) { index ->
        val generated = generated(index)
        check(generated == explicit(index))
        checksum += generated.length + generated.hashCode()
    }
    println(checksum)
}
