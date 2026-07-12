/*
 * Copyright 2010-2023 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import cstdio.*
import kotlinx.cinterop.*

// In ARC mode, fixed const-char arguments use static bridge literals for direct and
// immutable-local ASCII constants. Dynamic/non-ASCII fixed arguments and every String
// vararg must not. Strict mode retains the original runtime CString/MemScope lowering.
// CSTRING-BRIDGE-DAG: c"ascii\00"
// CSTRING-BRIDGE-DAG: c"static\00"
// CSTRING-BRIDGE-DAG: c"[%s] [%s] [%s] [%s]\0A\00"
// CSTRING-BRIDGE-DAG: c"vararg writable: %d\0A\00"
// CSTRING-STRICT: define

val stdout
    get() = getStdout()

fun main(args: Array<String>) {
    fprintf(stdout, "%s %s %d %d %d %lld %.1f %.1lf %d %d\n",
            "a", "b".cstr, (-1).toByte(), 2.toShort(), 3, Long.MAX_VALUE, 0.1.toFloat(), 0.2, true, false)

    // In ARC mode, fixed const-char ASCII constants use static C storage. String varargs
    // (including the embedded-NUL value), dynamic values, and non-ASCII values retain the
    // runtime UTF-8/MemScope path in every memory model.
    val staticAscii = "static"
    val dynamic = args.getOrNull(0) ?: "dynamic"
    printf("[%s] [%s] [%s] [%s]\n", "a\u0000b", staticAscii, dynamic, "é")
    printf("fixed lengths: %d %d %d %d\n",
            fixedCStringLength("ascii"), fixedCStringLength(staticAscii),
            fixedCStringLength(dynamic), fixedCStringLength("é"))
    printf("vararg writable: %d\n", mutateFirstVararg(0, "mutable"))

    memScoped {
        val aVar = alloc<IntVar>()
        val bVar = alloc<IntVar>()
        val sscanfResult = sscanf("42", "%d%d", aVar.ptr, bVar.ptr)
        printf("%d %d\n", sscanfResult, aVar.value)
    }
}
