import kotlin.native.arc.ArcDeinit

private var observedDeinitValue = "not-run"

private fun deinitHelper(): String = "ARC_DEINIT_SYNTHETIC_ROOT_OK"

private val deinitValue: String
    get() = deinitHelper()

private class Victim {
    @ArcDeinit
    private fun deinit() {
        // The runtime calls this method through TypeInfo, so neither this computed-property
        // getter nor its helper has any ordinary call path from main.
        observedDeinitValue = deinitValue
    }
}

private class VictimBox(var value: Victim?)

private fun releaseVictim() {
    val box = VictimBox(Victim())
    check(box.value != null)
    box.value = null
    check(box.value == null)
}

fun main() {
    releaseVictim()

    check(observedDeinitValue == "ARC_DEINIT_SYNTHETIC_ROOT_OK")
    println(observedDeinitValue)
}
