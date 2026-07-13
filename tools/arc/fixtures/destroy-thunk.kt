@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlin.native.arc.ArcDeinit
import kotlinx.cinterop.StableRef

private var events = 0

private class Payload(val id: Int)

private open class Base(var base: Payload?) {
    @ArcDeinit
    private fun destroyBase() {
        check(base != null)
        check(events == 1)
        events = 2
    }
}

private class Derived(base: Payload?, var first: Payload?, var second: Payload?) : Base(base) {
    @ArcDeinit
    private fun destroyDerived() {
        check(base != null && first != null && second != null)
        check(events == 0)
        events = 1
    }
}

private fun exercise(): Int {
    val stable = StableRef.create(Derived(Payload(1), Payload(2), Payload(3)))
    val value = stable.get()
    val result = value.base!!.id + value.first!!.id + value.second!!.id
    stable.dispose()
    return result
}

fun main() {
    check(exercise() == 6)
    check(events == 2)
    println("ARC_DESTROY_THUNK_OK")
}
