@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import arc.compat.c.arc_compat_c_add

fun main() {
    check(arc_compat_c_add(20, 22) == 42)
    println("CINTEROP_OK")
}
