@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

import kotlin.native.CName

@CName("arc_compat_add")
fun arcCompatAdd(left: Int, right: Int): Int = left + right
