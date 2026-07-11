@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

import kotlin.native.Platform
import kotlin.native.arc.ArcDebug

private class ArcImplicitScanNode(var next: ArcImplicitScanNode? = null)

fun main() {
    // Select and exercise the diagnostic runtime without requesting an automatic shutdown scan.
    ArcDebug.detectCycles()

    // This unrelated checker forces orderly runtime teardown. It must not implicitly enable
    // ARC cycle reporting when arcLeakCheck remains disabled.
    Platform.isCleanersLeakCheckerActive = true

    val first = ArcImplicitScanNode()
    val second = ArcImplicitScanNode()
    first.next = second
    second.next = first
    println("ARC_DEBUG_EXPLICIT_ONLY")
}
