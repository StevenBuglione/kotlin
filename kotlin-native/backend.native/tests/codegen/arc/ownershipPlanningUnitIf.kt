private class Payload

private class Holder(var value: Payload? = null)

private fun produce(): Payload = Payload()

private fun branchStore(condition: Boolean, left: Holder, right: Holder) {
    val owned = produce()
    val alias = owned
    if (condition) {
        left.value = alias
    } else {
        right.value = alias
    }
}

fun main() {
    val trueLeft = Holder()
    val trueRight = Holder()
    branchStore(true, trueLeft, trueRight)
    check(trueLeft.value != null)
    check(trueRight.value == null)

    val falseLeft = Holder()
    val falseRight = Holder()
    branchStore(false, falseLeft, falseRight)
    check(falseLeft.value == null)
    check(falseRight.value != null)

    println("OK")
}
