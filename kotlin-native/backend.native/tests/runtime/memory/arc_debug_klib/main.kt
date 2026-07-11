import arc.debug.klib.detectArcCyclesFromKlib
import kotlin.test.assertTrue

private class ArcKlibNode(var next: ArcKlibNode? = null)

fun main() {
    val first = ArcKlibNode()
    val second = ArcKlibNode()
    first.next = second
    second.next = first

    assertTrue(detectArcCyclesFromKlib().any { cycle ->
        cycle.objectCount == 2 && cycle.typeNames.all { it.endsWith("ArcKlibNode") }
    })

    first.next = null
    second.next = null
}
