import arc.cache.Holder
import arc.cache.Payload
import arc.cache.makeWeakReader

private class State(
    var owner: Payload?,
    val holder: Holder,
    val readCaptured: () -> Int?,
)

private fun makeState(): State {
    val owner = Payload(42)
    return State(owner, Holder(owner), makeWeakReader(owner))
}

fun main() {
    val state = makeState()

    check(state.holder.weak?.value == 42) { "cached weak property lost its live target" }
    check(state.holder.unowned.value == 42) { "cached unowned property lost its live target" }
    check(state.readCaptured() == 42) { "cached inline weak capture lost its live target" }

    val replacement = Payload(84)
    state.owner = replacement
    state.holder.weak = replacement
    state.holder.unowned = replacement
    check(state.holder.weak?.value == 84) { "cached weak property setter did not update storage" }
    check(state.holder.unowned.value == 84) { "cached unowned property setter did not update storage" }

    state.holder.weak = null
    check(state.holder.weak == null) { "cached weak property setter did not clear storage" }
}
