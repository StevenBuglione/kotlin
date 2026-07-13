/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

fun generatedCapacity(index: Int): String = "arc-${index and 1023}-${index.toString(16)}"

fun explicitDefaultCapacity(index: Int): String = StringBuilder()
    .append("arc-")
    .append(index)
    .toString()

fun main() {
    println(generatedCapacity(17))
    println(explicitDefaultCapacity(17))
}

// OPT-LABEL: define %struct.ObjHeader* @"kfun:#generatedCapacity(kotlin.Int){}kotlin.String"
// OPT: {{call|invoke}} void @"kfun:kotlin.text.StringBuilder#<init>(kotlin.Int){}"({{.*}}i32 21
// OPT-NOT: @"kfun:kotlin.text.StringBuilder#<init>(){}"
// OPT-LABEL: define %struct.ObjHeader* @"kfun:#explicitDefaultCapacity(kotlin.Int){}kotlin.String"
// OPT: {{call|invoke}} void @"kfun:kotlin.text.StringBuilder#<init>(){}"

// NEG-LABEL: define %struct.ObjHeader* @"kfun:#generatedCapacity(kotlin.Int){}kotlin.String"
// NEG: {{call|invoke}} void @"kfun:kotlin.text.StringBuilder#<init>(){}"
// NEG-NOT: @"kfun:kotlin.text.StringBuilder#<init>(kotlin.Int){}"
// NEG-LABEL: define %struct.ObjHeader* @"kfun:#explicitDefaultCapacity(kotlin.Int){}kotlin.String"
// NEG: {{call|invoke}} void @"kfun:kotlin.text.StringBuilder#<init>(){}"
