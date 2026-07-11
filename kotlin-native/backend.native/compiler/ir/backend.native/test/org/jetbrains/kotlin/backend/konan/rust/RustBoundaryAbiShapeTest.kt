/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import kotlinx.cinterop.JvmCInteropCallbacks
import kotlinx.cinterop.toCValues
import llvm.LLVMAddAttributeAtIndex
import llvm.LLVMAddCallSiteAttribute
import llvm.LLVMAddFunction
import llvm.LLVMAppendBasicBlockInContext
import llvm.LLVMAttributeFunctionIndex
import llvm.LLVMAttributeReturnIndex
import llvm.LLVMBuildCall2
import llvm.LLVMBuildRet
import llvm.LLVMContextCreate
import llvm.LLVMCreateBuilderInContext
import llvm.LLVMCreateConstantRangeAttribute
import llvm.LLVMCreateStringAttribute
import llvm.LLVMCreateTypeAttribute
import llvm.LLVMDisposeBuilder
import llvm.LLVMDisposeModule
import llvm.LLVMGetParam
import llvm.LLVMInt16TypeInContext
import llvm.LLVMInt8TypeInContext
import llvm.LLVMModuleCreateWithNameInContext
import llvm.LLVMPointerTypeInContext
import llvm.LLVMPositionBuilderAtEnd
import llvm.LLVMValueRef
import org.jetbrains.kotlin.backend.konan.llvm.createLlvmEnumAttribute
import org.jetbrains.kotlin.backend.konan.llvm.functionType
import org.jetbrains.kotlin.backend.konan.llvm.getLlvmAttributeKindId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RustBoundaryAbiShapeTest {
    @BeforeTest
    fun initializeNativeMemory() {
        JvmCInteropCallbacks.init()
        llvm.loadLLVMStubs(null)
    }

    @AfterTest
    fun disposeNativeMemory() {
        JvmCInteropCallbacks.dispose()
    }

    @Test
    fun hardAbiAttributesMustMatchExactlyOnDefinitionAndCallSite() {
        withFixture { fixture ->
            val kotlinFunction = fixture.function("kotlin_boundary")
            fixture.addFunctionEnumAttribute(kotlinFunction, 1, "inreg")
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))

            val boundary = fixture.function("boundary")
            fixture.addFunctionEnumAttribute(boundary, 1, "inreg")
            val call = fixture.directCall("call_user", boundary)
            fixture.addCallEnumAttribute(call, 1, "inreg")

            assertTrue(normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).isSuccess)
        }
        withFixture { fixture ->
            val kotlinFunction = fixture.function("kotlin_boundary")
            fixture.addFunctionEnumAttribute(kotlinFunction, 1, "inreg")
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            fixture.function("boundary")

            assertEquals(
                "Rust boundary 'boundary' has different ABI-shaping attributes",
                normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).failure,
            )
        }
        withFixture { fixture ->
            val kotlinFunction = fixture.function("kotlin_boundary")
            fixture.addFunctionEnumAttribute(kotlinFunction, 1, "inreg")
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            val boundary = fixture.function("boundary")
            fixture.addFunctionEnumAttribute(boundary, 1, "nest")

            assertEquals(
                "Rust boundary 'boundary' has different ABI-shaping attributes",
                normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).failure,
            )
        }
        withFixture { fixture ->
            val kotlinFunction = fixture.function("kotlin_boundary")
            fixture.addFunctionEnumAttribute(kotlinFunction, 1, "inreg")
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            val boundary = fixture.function("boundary")
            fixture.addFunctionEnumAttribute(boundary, 1, "inreg")
            fixture.directCall("call_user", boundary)

            assertEquals(
                "a direct call to Rust boundary 'boundary' has an incompatible ABI",
                normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).failure,
            )
        }
    }

    @Test
    fun typeAttributesMustMatchExactlyOnDefinitionAndCallSite() {
        withFixture { fixture ->
            val kotlinFunction = fixture.pointerFunction("kotlin_boundary")
            fixture.addFunctionTypeAttribute(kotlinFunction, 1, "byval", fixture.i8)
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            val boundary = fixture.pointerFunction("boundary")
            fixture.addFunctionTypeAttribute(boundary, 1, "byval", fixture.i8)
            val call = fixture.directPointerCall("call_user", boundary)
            fixture.addCallTypeAttribute(call, 1, "byval", fixture.i8)

            assertTrue(normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).isSuccess)
        }
        withFixture { fixture ->
            val kotlinFunction = fixture.pointerFunction("kotlin_boundary")
            fixture.addFunctionTypeAttribute(kotlinFunction, 1, "byval", fixture.i8)
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            fixture.pointerFunction("boundary")

            assertEquals(
                "Rust boundary 'boundary' has different ABI-shaping attributes",
                normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).failure,
            )
        }
        withFixture { fixture ->
            val kotlinFunction = fixture.pointerFunction("kotlin_boundary")
            fixture.addFunctionTypeAttribute(kotlinFunction, 1, "byval", fixture.i8)
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            val boundary = fixture.pointerFunction("boundary")
            fixture.addFunctionTypeAttribute(boundary, 1, "byval", fixture.i16)

            assertEquals(
                "Rust boundary 'boundary' has different ABI-shaping attributes",
                normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).failure,
            )
        }
        withFixture { fixture ->
            val kotlinFunction = fixture.pointerFunction("kotlin_boundary")
            fixture.addFunctionTypeAttribute(kotlinFunction, 1, "byval", fixture.i8)
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            val boundary = fixture.pointerFunction("boundary")
            fixture.addFunctionTypeAttribute(boundary, 1, "byval", fixture.i8)
            val call = fixture.directPointerCall("call_user", boundary)
            fixture.addCallTypeAttribute(call, 1, "byval", fixture.i16)

            assertEquals(
                "a direct call to Rust boundary 'boundary' has an incompatible ABI",
                normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).failure,
            )
        }
    }

    @Test
    fun definitionAlignMatchesExactlyWhileCallSitesMayStrengthen() {
        withFixture { fixture ->
            val kotlinFunction = fixture.pointerFunction("kotlin_boundary")
            fixture.addFunctionEnumAttribute(kotlinFunction, 1, "align", 8)
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            val boundary = fixture.pointerFunction("boundary")
            fixture.addFunctionEnumAttribute(boundary, 1, "align", 8)
            val call = fixture.directPointerCall("call_user", boundary)
            fixture.addCallEnumAttribute(call, 1, "align", 8)

            assertTrue(normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).isSuccess)
        }
        withFixture { fixture ->
            val kotlinFunction = fixture.pointerFunction("kotlin_boundary")
            fixture.addFunctionEnumAttribute(kotlinFunction, 1, "align", 8)
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            val boundary = fixture.pointerFunction("boundary")
            fixture.addFunctionEnumAttribute(boundary, 1, "align", 16)

            assertEquals(
                "Rust boundary 'boundary' has different ABI-shaping attributes",
                normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).failure,
            )
        }
        withFixture { fixture ->
            val kotlinFunction = fixture.pointerFunction("kotlin_boundary")
            fixture.addFunctionEnumAttribute(kotlinFunction, 1, "align", 8)
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            val boundary = fixture.pointerFunction("boundary")
            fixture.addFunctionEnumAttribute(boundary, 1, "align", 8)
            fixture.directPointerCall("call_user", boundary)

            assertTrue(normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).isSuccess)
        }
        withFixture { fixture ->
            val kotlinFunction = fixture.pointerFunction("kotlin_boundary")
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            val boundary = fixture.pointerFunction("boundary")
            val call = fixture.directPointerCall("call_user", boundary)
            fixture.addCallEnumAttribute(call, 1, "align", 16)

            assertTrue(normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).isSuccess)
        }
        withFixture { fixture ->
            val kotlinFunction = fixture.pointerFunction("kotlin_boundary")
            fixture.addFunctionEnumAttribute(kotlinFunction, 1, "align", 16)
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            val boundary = fixture.pointerFunction("boundary")
            fixture.addFunctionEnumAttribute(boundary, 1, "align", 16)
            val call = fixture.directPointerCall("call_user", boundary)
            fixture.addCallEnumAttribute(call, 1, "align", 8)

            assertFalse(normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).isSuccess)
        }
    }

    @Test
    fun alignstackIsComparedOnDefinitionsButNotRequiredOnCalls() = withFixture { fixture ->
        val kotlinFunction = fixture.function("kotlin_boundary")
        fixture.addFunctionEnumAttribute(kotlinFunction, LLVMAttributeFunctionIndex, "alignstack", 16)
        val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
        val boundary = fixture.function("boundary")
        fixture.addFunctionEnumAttribute(boundary, LLVMAttributeFunctionIndex, "alignstack", 16)
        fixture.directCall("call_user", boundary)

        assertTrue(normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).isSuccess)
    }

    @Test
    fun rejectsPointerAssumptionsUnknownEnumsAndStringAttributes() {
        withFixture { fixture ->
            val pointerFunction = fixture.pointerFunction("pointer_boundary")
            fixture.addFunctionEnumAttribute(pointerFunction, 1, "nonnull")
            assertNull(RustBoundaryAbiExpectation.capture("pointer_boundary", pointerFunction))
        }
        withFixture { fixture ->
            val function = fixture.function("unknown_enum")
            fixture.addFunctionEnumAttribute(function, 1, "readonly")
            assertNull(RustBoundaryAbiExpectation.capture("unknown_enum", function))
        }
        withFixture { fixture ->
            val function = fixture.function("string_attribute")
            fixture.addFunctionStringAttribute(function, 1, "unknown-abi", "value")
            assertNull(RustBoundaryAbiExpectation.capture("string_attribute", function))
        }
    }

    @Test
    fun permitsCallSiteOnlyNonnullButRejectsDefinitionNonnull() {
        withFixture { fixture ->
            val kotlinFunction = fixture.pointerFunction("kotlin_boundary")
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            val boundary = fixture.pointerFunction("boundary")
            val call = fixture.directPointerCall("call_user", boundary)
            fixture.addCallEnumAttribute(call, 1, "nonnull")

            assertTrue(normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).isSuccess)
        }
        withFixture { fixture ->
            val kotlinFunction = fixture.pointerFunction("kotlin_boundary")
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            val boundary = fixture.pointerFunction("boundary")
            fixture.addFunctionEnumAttribute(boundary, 1, "nonnull")

            assertFalse(normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).isSuccess)
        }
    }

    @Test
    fun rejectsElementTypeAndImmargAttributes() {
        withFixture { fixture ->
            val function = fixture.pointerFunction("elementtype")
            fixture.addFunctionTypeAttribute(function, 1, "elementtype", fixture.i8)
            assertNull(RustBoundaryAbiExpectation.capture("elementtype", function))
        }
        withFixture { fixture ->
            val function = fixture.function("immarg")
            fixture.addFunctionEnumAttribute(function, 1, "immarg")
            assertNull(RustBoundaryAbiExpectation.capture("immarg", function))
        }
    }

    @Test
    fun permitsNoundefOnSafeSlotsWithoutRequiringAnExactMatch() = withFixture { fixture ->
        val kotlinFunction = fixture.function("kotlin_boundary")
        fixture.addFunctionEnumAttribute(kotlinFunction, LLVMAttributeReturnIndex, "noundef")
        fixture.addFunctionEnumAttribute(kotlinFunction, 1, "noundef")
        val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
        val shape = assertNotNull(expectation.shape)
        assertTrue(shape.returnAttributes.isEmpty())
        assertTrue(shape.parameterAttributes.single().isEmpty())

        val boundary = fixture.function("boundary")
        val call = fixture.directCall("call_user", boundary)
        fixture.addCallEnumAttribute(call, LLVMAttributeReturnIndex, "noundef")
        fixture.addCallEnumAttribute(call, 1, "noundef")

        assertTrue(normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).isSuccess)
    }

    @Test
    fun returnedIsAcceptedOnlyOnACompatibleDefinitionParameter() {
        withFixture { fixture ->
            val kotlinFunction = fixture.function("kotlin_boundary")
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            val boundary = fixture.definedFunction("boundary")
            fixture.addFunctionEnumAttribute(boundary, 1, "returned")
            fixture.directCall("call_user", boundary)

            assertTrue(normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).isSuccess)
        }
        withFixture { fixture ->
            val kotlinFunction = fixture.function("kotlin_boundary")
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            val boundary = fixture.function("boundary")
            fixture.addFunctionEnumAttribute(boundary, 1, "returned")

            assertFalse(normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).isSuccess)
        }
        withFixture { fixture ->
            val kotlinFunction = fixture.function("kotlin_boundary")
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            val boundary = fixture.definedFunction("boundary")
            val call = fixture.directCall("call_user", boundary)
            fixture.addCallEnumAttribute(call, 1, "returned")

            assertFalse(normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).isSuccess)
        }
    }

    @Test
    fun constantRangeIsAcceptedOnlyAsDefinitionOrCallSiteSemanticProof() {
        withFixture { fixture ->
            val kotlinFunction = fixture.function("kotlin_boundary")
            fixture.addFunctionRangeAttribute(kotlinFunction, LLVMAttributeReturnIndex)
            assertNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
        }
        withFixture { fixture ->
            val kotlinFunction = fixture.function("kotlin_boundary")
            val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
            val boundary = fixture.definedFunction("boundary")
            fixture.addFunctionRangeAttribute(boundary, LLVMAttributeReturnIndex)
            val call = fixture.directCall("call_user", boundary)
            fixture.addCallRangeAttribute(call, LLVMAttributeReturnIndex)

            assertTrue(normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).isSuccess)
        }
    }

    @Test
    fun rejectsVarargShapeMismatch() = withFixture { fixture ->
        val kotlinFunction = fixture.function("kotlin_boundary")
        val expectation = assertNotNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))
        fixture.varargFunction("boundary")

        assertEquals(
            "Rust boundary 'boundary' has a different vararg shape",
            normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).failure,
        )
    }

    @Test
    fun rejectsKotlinVarargBoundaryUntilPerCallAttributesAreModeled() = withFixture { fixture ->
        assertNull(RustBoundaryAbiExpectation.capture("boundary", fixture.varargFunction("kotlin_boundary")))
    }

    private inline fun withFixture(block: (LlvmFixture) -> Unit) {
        val fixture = LlvmFixture()
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private class LlvmFixture {
        val context = LLVMContextCreate()!!
        val module = LLVMModuleCreateWithNameInContext("rust-boundary-abi-shape-test", context)!!
        val i8 = LLVMInt8TypeInContext(context)!!
        val i16 = LLVMInt16TypeInContext(context)!!
        private val i8FunctionType = functionType(i8, isVarArg = false, paramTypes = listOf(i8))
        private val pointer = LLVMPointerTypeInContext(context, 0)!!
        private val pointerFunctionType = functionType(pointer, isVarArg = false, paramTypes = listOf(pointer))

        fun function(name: String): LLVMValueRef = LLVMAddFunction(module, name, i8FunctionType)!!

        fun varargFunction(name: String): LLVMValueRef =
            LLVMAddFunction(module, name, functionType(i8, isVarArg = true, paramTypes = listOf(i8)))!!

        fun pointerFunction(name: String): LLVMValueRef {
            return LLVMAddFunction(module, name, pointerFunctionType)!!
        }

        fun definedFunction(name: String): LLVMValueRef = function(name).also { function ->
            val block = LLVMAppendBasicBlockInContext(context, function, "entry")!!
            val builder = LLVMCreateBuilderInContext(context)!!
            try {
                LLVMPositionBuilderAtEnd(builder, block)
                LLVMBuildRet(builder, LLVMGetParam(function, 0)!!)
            } finally {
                LLVMDisposeBuilder(builder)
            }
        }

        fun directCall(userName: String, boundary: LLVMValueRef): LLVMValueRef {
            val user = function(userName)
            val block = LLVMAppendBasicBlockInContext(context, user, "entry")!!
            val builder = LLVMCreateBuilderInContext(context)!!
            return try {
                LLVMPositionBuilderAtEnd(builder, block)
                LLVMBuildCall2(
                    builder,
                    i8FunctionType,
                    boundary,
                    listOf(LLVMGetParam(user, 0)!!).toCValues(),
                    1,
                    "call",
                )!!.also { LLVMBuildRet(builder, it) }
            } finally {
                LLVMDisposeBuilder(builder)
            }
        }

        fun directPointerCall(userName: String, boundary: LLVMValueRef): LLVMValueRef {
            val user = LLVMAddFunction(module, userName, pointerFunctionType)!!
            val block = LLVMAppendBasicBlockInContext(context, user, "entry")!!
            val builder = LLVMCreateBuilderInContext(context)!!
            return try {
                LLVMPositionBuilderAtEnd(builder, block)
                LLVMBuildCall2(
                    builder,
                    pointerFunctionType,
                    boundary,
                    listOf(LLVMGetParam(user, 0)!!).toCValues(),
                    1,
                    "call",
                )!!.also { LLVMBuildRet(builder, it) }
            } finally {
                LLVMDisposeBuilder(builder)
            }
        }

        fun addFunctionEnumAttribute(function: LLVMValueRef, index: Int, name: String, value: Long = 0) {
            LLVMAddAttributeAtIndex(function, index, createLlvmEnumAttribute(context, getLlvmAttributeKindId(name), value))
        }

        fun addCallEnumAttribute(call: LLVMValueRef, index: Int, name: String, value: Long = 0) {
            LLVMAddCallSiteAttribute(call, index, createLlvmEnumAttribute(context, getLlvmAttributeKindId(name), value))
        }

        fun addFunctionTypeAttribute(function: LLVMValueRef, index: Int, name: String, type: llvm.LLVMTypeRef) {
            LLVMAddAttributeAtIndex(function, index, LLVMCreateTypeAttribute(context, getLlvmAttributeKindId(name).value, type))
        }

        fun addCallTypeAttribute(call: LLVMValueRef, index: Int, name: String, type: llvm.LLVMTypeRef) {
            LLVMAddCallSiteAttribute(call, index, LLVMCreateTypeAttribute(context, getLlvmAttributeKindId(name).value, type))
        }

        fun addFunctionRangeAttribute(function: LLVMValueRef, index: Int) {
            LLVMAddAttributeAtIndex(function, index, rangeAttribute())
        }

        fun addCallRangeAttribute(call: LLVMValueRef, index: Int) {
            LLVMAddCallSiteAttribute(call, index, rangeAttribute())
        }

        private fun rangeAttribute() = LLVMCreateConstantRangeAttribute(
            context,
            getLlvmAttributeKindId("range").value,
            8,
            longArrayOf(0L).toCValues(),
            longArrayOf(10L).toCValues(),
        )!!

        fun addFunctionStringAttribute(function: LLVMValueRef, index: Int, name: String, value: String) {
            LLVMAddAttributeAtIndex(
                function,
                index,
                LLVMCreateStringAttribute(context, name, name.length, value, value.length),
            )
        }

        fun close() {
            LLVMDisposeModule(module)
            llvm.LLVMContextDispose(context)
        }
    }
}
