@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

import kotlin.native.arc.ArcDebug

fun main() {
    println("ARC_DIAGNOSTIC_SYMBOL_AUDIT:${ArcDebug.detectCycles().size}")
}
