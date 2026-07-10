fun foo() = 1
fun callee(a: Int, b: Int) {}

fun test() {
    callee(a = foo(), b = foo())

    callee(b = foo(), a = foo())
    callee(b = 1, a = foo())
    callee(b = foo(), a = 1)
}
