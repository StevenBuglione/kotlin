/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

fun main() {
    var checksum = 0L
    repeat(50_000) { index ->
        val builder = StringBuilder()
        builder.append("arc\u0000")
        builder.append(index)
        builder.append(null as String?)
        val value = builder.toString()
        check(value.startsWith("arc\u0000"))
        check(value.endsWith("null"))
        checksum += value.length
    }
    check(checksum > 500_000L)
    println("ARC_STRING_BUILDER_BACKING_ARRAY_OK checksum=$checksum")
}
