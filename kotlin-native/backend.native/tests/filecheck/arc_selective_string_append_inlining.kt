/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

fun generatedStrings(first: String, second: String, third: String, number: Int): String =
    "$first$second$third$number"

fun userAuthored(first: String, second: String): String =
    StringBuilder().append(first).append(second).toString()

fun main() {
    println(generatedStrings("a", "b", "c", 7))
    println(userAuthored("x", "y"))
}

// TAGGED-COUNT-3: !konan.arc.inline
// NEG-NOT: !konan.arc.inline

// INLINE-LABEL: define %struct.ObjHeader* @"kfun:#generatedStrings
// The three lowering-owned append(String?) calls are inlined. The Int overload remains a call.
// INLINE-NOT: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"
// INLINE: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.Int){}kotlin.text.StringBuilder"
// INLINE-LABEL: define %struct.ObjHeader* @"kfun:#userAuthored
// User-written fluent calls never receive the selective-inline authorization.
// INLINE: {{call|invoke}} %struct.ObjHeader* @"kfun:kotlin.text.StringBuilder#append(kotlin.String?){}kotlin.text.StringBuilder"

// DCE-NOT: __konan_arc_inline_string_builder_append_string_v1
