package arc.debug.klib

import kotlin.native.arc.ArcCycleInfo
import kotlin.native.arc.ArcDebug

fun detectArcCyclesFromKlib(): List<ArcCycleInfo> = ArcDebug::detectCycles.invoke()
