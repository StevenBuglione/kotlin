import kotlin.native.arc.ArcDeinit

private var destroyed = 0
private var failedReceiverDeinitRan = false

private class ExpectedFailure : Throwable()

private class Tracked {
    @ArcDeinit
    private fun deinit() {
        destroyed++
    }
}

private fun fail(value: Any): Nothing {
    value.hashCode()
    throw ExpectedFailure()
}

private fun directExceptionalUnwind() {
    val owned = Tracked()
    fail(owned)
}

private class ConstructorExceptionalUnwind {
    private val owned = Tracked()

    init {
        val local = Tracked()
        fail(local)
    }

    @ArcDeinit
    private fun deinit() {
        failedReceiverDeinitRan = true
    }
}

private fun constructAndFail(): Nothing {
    ConstructorExceptionalUnwind()
    error("constructor unexpectedly completed")
}

fun main() {
    try {
        directExceptionalUnwind()
    } catch (_: ExpectedFailure) {
    }
    check(destroyed == 1) { "direct exceptional unwind released $destroyed objects instead of 1" }

    try {
        constructAndFail()
    } catch (_: ExpectedFailure) {
    }
    check(destroyed == 3) { "constructor exceptional unwind produced $destroyed total releases instead of 3" }
    check(!failedReceiverDeinitRan) { "@ArcDeinit ran for an incompletely initialized receiver" }
    println("OK")
}
