/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.native.interop.gen

import org.jetbrains.kotlin.native.interop.indexer.*
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class NoCallbackFunctionsTest {
    private val intType = IntegerType(4, true, "int")

    private fun function(
            name: String,
            parameters: List<Parameter> = emptyList(),
            returnType: Type = intType,
            isVararg: Boolean = false
    ) = FunctionDecl(name, parameters, returnType, name, false, isVararg)

    @Test
    fun acceptsExactCLeaf() {
        validateNoCallbackFunctions(
                Language.C,
                listOf(function("leaf", listOf(Parameter("value", PointerType(CharType), false)))),
                setOf("leaf"),
                emptySet()
        )
    }

    @Test
    fun rejectsUnknownAndExcludedNames() {
        val unknown = assertFailsWith<IllegalArgumentException> {
            validateNoCallbackFunctions(Language.C, listOf(function("leaf")), setOf("missing"), emptySet())
        }
        assertContains(unknown.message.orEmpty(), "unknown C function 'missing'")

        val excluded = assertFailsWith<IllegalArgumentException> {
            validateNoCallbackFunctions(Language.C, listOf(function("leaf")), setOf("leaf"), setOf("leaf"))
        }
        assertContains(excluded.message.orEmpty(), "excluded function 'leaf'")
    }

    @Test
    fun rejectsAmbiguousDeclarations() {
        val error = assertFailsWith<IllegalArgumentException> {
            validateNoCallbackFunctions(
                    Language.C,
                    listOf(function("leaf"), function("leaf", returnType = CharType)),
                    setOf("leaf"),
                    emptySet()
            )
        }
        assertContains(error.message.orEmpty(), "ambiguous C function 'leaf'")
    }

    @Test
    fun rejectsCppAndVarargs() {
        val cpp = assertFailsWith<IllegalArgumentException> {
            validateNoCallbackFunctions(Language.CPP, listOf(function("leaf")), setOf("leaf"), emptySet())
        }
        assertContains(cpp.message.orEmpty(), "only for C declarations")

        val vararg = assertFailsWith<IllegalArgumentException> {
            validateNoCallbackFunctions(Language.C, listOf(function("leaf", isVararg = true)), setOf("leaf"), emptySet())
        }
        assertContains(vararg.message.orEmpty(), "variadic C function 'leaf'")
    }

    @Test
    fun rejectsCallbackParametersAndReturns() {
        val callbackType = PointerType(FunctionType(listOf(intType), intType))
        val parameter = assertFailsWith<IllegalArgumentException> {
            validateNoCallbackFunctions(
                    Language.C,
                    listOf(function("leaf", listOf(Parameter("callback", callbackType, false)))),
                    setOf("leaf"),
                    emptySet()
            )
        }
        assertContains(parameter.message.orEmpty(), "callback-shaped C function 'leaf'")

        val result = assertFailsWith<IllegalArgumentException> {
            validateNoCallbackFunctions(
                    Language.C,
                    listOf(function("leaf", returnType = callbackType)),
                    setOf("leaf"),
                    emptySet()
            )
        }
        assertContains(result.message.orEmpty(), "callback-shaped C function 'leaf'")
    }
}
