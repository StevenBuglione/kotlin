@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlin.native.runtime.NativeRuntimeApi::class)

import kotlin.native.MemoryModel
import kotlin.native.Platform
import kotlin.native.arc.ArcDebug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class ArcDebugNode(var next: ArcDebugNode? = null)

@Test
fun detectsWithoutCollectingArcCycles() {
    if (Platform.memoryModel != MemoryModel.ARC) return

    val first = ArcDebugNode()
    val second = ArcDebugNode()
    first.next = second
    second.next = first

    val cycle = ArcDebug.detectCycles().single { info ->
        info.objectCount == 2 && info.typeNames.all { it.endsWith("ArcDebugNode") }
    }
    assertEquals(2, cycle.objectCount)

    // Detection must not clear or rewrite either edge.
    assertTrue(first.next === second)
    assertTrue(second.next === first)

    first.next = null
    second.next = null
}
