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
import llvm.LLVMAddGlobal
import llvm.LLVMAppendBasicBlockInContext
import llvm.LLVMAttributeReturnIndex
import llvm.LLVMBuildAlloca
import llvm.LLVMBuildCall2
import llvm.LLVMBuildInvoke2
import llvm.LLVMBuildLoad2
import llvm.LLVMBuildRet
import llvm.LLVMBuildStore
import llvm.LLVMBuildUnreachable
import llvm.LLVMContextCreate
import llvm.LLVMCreateBuilderInContext
import llvm.LLVMDisposeBuilder
import llvm.LLVMDisposeModule
import llvm.LLVMFloatTypeInContext
import llvm.LLVMGetCallSiteEnumAttribute
import llvm.LLVMGetEnumAttributeAtIndex
import llvm.LLVMGetFunctionCallConv
import llvm.LLVMGetParam
import llvm.LLVMInt8TypeInContext
import llvm.LLVMModuleCreateWithNameInContext
import llvm.LLVMPositionBuilderAtEnd
import llvm.LLVMSetFunctionCallConv
import llvm.LLVMSetInitializer
import llvm.LLVMSetInstructionCallConv
import llvm.LLVMTypeOf
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

class RustBoundaryAbiTest {
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
    fun capturesKotlinExtensionSnapshotAndRejectsAmbiguousOrNonIntegerAttributes() = withFixture { fixture ->
        val kotlinFunction = fixture.function("kotlin_boundary")
        fixture.addFunctionExtension(kotlinFunction, LLVMAttributeReturnIndex, SIGN_EXTEND)
        fixture.addFunctionExtension(kotlinFunction, 1, ZERO_EXTEND)

        assertEquals(
            RustBoundaryAbiExpectation(
                symbolName = "boundary",
                callingConvention = LLVMGetFunctionCallConv(kotlinFunction),
                returnExtension = RustBoundaryAbiExpectation.Extension.SIGN_EXTEND,
                parameterExtensions = listOf(RustBoundaryAbiExpectation.Extension.ZERO_EXTEND),
            ),
            RustBoundaryAbiExpectation.capture("boundary", kotlinFunction),
        )

        fixture.addFunctionExtension(kotlinFunction, LLVMAttributeReturnIndex, ZERO_EXTEND)
        assertNull(RustBoundaryAbiExpectation.capture("boundary", kotlinFunction))

        val floatFunction = fixture.floatFunction("non_integer_boundary")
        fixture.addFunctionExtension(floatFunction, LLVMAttributeReturnIndex, SIGN_EXTEND)
        assertNull(RustBoundaryAbiExpectation.capture("non_integer", floatFunction))
    }

    @Test
    fun addsLinuxExtensionsToDefinitionCallAndInvoke() = withFixture { fixture ->
        val boundary = fixture.function("boundary")
        val call = fixture.directCall("call_user", boundary)
        val invoke = fixture.directInvoke("invoke_user", boundary)
        val expectation = fixture.expectation(
            returnExtension = RustBoundaryAbiExpectation.Extension.SIGN_EXTEND,
            parameterExtension = RustBoundaryAbiExpectation.Extension.ZERO_EXTEND,
        )

        assertTrue(normalizeRustBoundaryAbi(fixture.module, listOf(expectation)).isSuccess)

        assertFunctionExtension(boundary, LLVMAttributeReturnIndex, SIGN_EXTEND)
        assertFunctionExtension(boundary, 1, ZERO_EXTEND)
        assertCallExtension(call, LLVMAttributeReturnIndex, SIGN_EXTEND)
        assertCallExtension(call, 1, ZERO_EXTEND)
        assertCallExtension(invoke, LLVMAttributeReturnIndex, SIGN_EXTEND)
        assertCallExtension(invoke, 1, ZERO_EXTEND)
    }

    @Test
    fun removesUnexpectedMinGWExtensionsFromDefinitionCallAndInvoke() = withFixture { fixture ->
        val boundary = fixture.function("boundary")
        val call = fixture.directCall("call_user", boundary)
        val invoke = fixture.directInvoke("invoke_user", boundary)
        listOf(boundary).forEach {
            fixture.addFunctionExtension(it, LLVMAttributeReturnIndex, ZERO_EXTEND)
            fixture.addFunctionExtension(it, 1, SIGN_EXTEND)
        }
        listOf(call, invoke).forEach {
            fixture.addCallExtension(it, LLVMAttributeReturnIndex, ZERO_EXTEND)
            fixture.addCallExtension(it, 1, SIGN_EXTEND)
        }

        assertTrue(
            normalizeRustBoundaryAbi(
                fixture.module,
                listOf(
                    fixture.expectation(
                        returnExtension = RustBoundaryAbiExpectation.Extension.NONE,
                        parameterExtension = RustBoundaryAbiExpectation.Extension.NONE,
                    )
                ),
            ).isSuccess
        )

        listOf(SIGN_EXTEND, ZERO_EXTEND).forEach { extension ->
            assertNull(LLVMGetEnumAttributeAtIndex(boundary, LLVMAttributeReturnIndex, extension.value))
            assertNull(LLVMGetEnumAttributeAtIndex(boundary, 1, extension.value))
            assertNull(LLVMGetCallSiteEnumAttribute(call, LLVMAttributeReturnIndex, extension.value))
            assertNull(LLVMGetCallSiteEnumAttribute(call, 1, extension.value))
            assertNull(LLVMGetCallSiteEnumAttribute(invoke, LLVMAttributeReturnIndex, extension.value))
            assertNull(LLVMGetCallSiteEnumAttribute(invoke, 1, extension.value))
        }
    }

    @Test
    fun failsClosedForBothExtensionsAndNonIntegerSlots() {
        withFixture { fixture ->
            val both = fixture.function("both")
            fixture.addFunctionExtension(both, LLVMAttributeReturnIndex, SIGN_EXTEND)
            fixture.addFunctionExtension(both, LLVMAttributeReturnIndex, ZERO_EXTEND)
            assertFalse(normalizeRustBoundaryAbi(fixture.module, listOf(fixture.expectation("both"))).isSuccess)
        }
        withFixture { fixture ->
            fixture.floatFunction("boundary")
            assertFalse(
                normalizeRustBoundaryAbi(
                    fixture.module,
                    listOf(
                        RustBoundaryAbiExpectation(
                            "boundary",
                            0,
                            RustBoundaryAbiExpectation.Extension.SIGN_EXTEND,
                            listOf(RustBoundaryAbiExpectation.Extension.SIGN_EXTEND),
                        )
                    ),
                ).isSuccess
            )
        }
    }

    @Test
    fun failsClosedForCallSiteBothExtensionsAndCallingConventionMismatch() {
        withFixture { fixture ->
            val boundary = fixture.function("boundary")
            val call = fixture.directCall("call_user", boundary)
            fixture.addCallExtension(call, 1, SIGN_EXTEND)
            fixture.addCallExtension(call, 1, ZERO_EXTEND)
            assertFalse(normalizeRustBoundaryAbi(fixture.module, listOf(fixture.expectation())).isSuccess)
        }
        withFixture { fixture ->
            val boundary = fixture.function("boundary")
            val call = fixture.directCall("call_user", boundary)
            LLVMSetInstructionCallConv(call, 8)
            assertFalse(normalizeRustBoundaryAbi(fixture.module, listOf(fixture.expectation())).isSuccess)
        }
        withFixture { fixture ->
            val boundary = fixture.function("boundary")
            LLVMSetFunctionCallConv(boundary, 8)
            assertFalse(normalizeRustBoundaryAbi(fixture.module, listOf(fixture.expectation())).isSuccess)
        }
    }

    @Test
    fun failsClosedForMissingBoundaryIndirectCallAndEscapingAddress() {
        withFixture { fixture ->
            assertFalse(normalizeRustBoundaryAbi(fixture.module, listOf(fixture.expectation())).isSuccess)
        }
        withFixture { fixture ->
            val boundary = fixture.function("boundary")
            fixture.indirectCall("indirect_user", boundary)
            assertFalse(normalizeRustBoundaryAbi(fixture.module, listOf(fixture.expectation())).isSuccess)
        }
        withFixture { fixture ->
            val boundary = fixture.function("boundary")
            val global = LLVMAddGlobal(fixture.module, LLVMTypeOf(boundary), "escaped_boundary")!!
            LLVMSetInitializer(global, boundary)
            assertFalse(normalizeRustBoundaryAbi(fixture.module, listOf(fixture.expectation())).isSuccess)
        }
    }

    private fun assertFunctionExtension(function: LLVMValueRef, index: Int, extension: ExtensionKind) {
        assertNotNull(LLVMGetEnumAttributeAtIndex(function, index, extension.value))
    }

    private fun assertCallExtension(call: LLVMValueRef, index: Int, extension: ExtensionKind) {
        assertNotNull(LLVMGetCallSiteEnumAttribute(call, index, extension.value))
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
        val module = LLVMModuleCreateWithNameInContext("rust-boundary-abi-test", context)!!
        private val i8 = LLVMInt8TypeInContext(context)!!
        private val i8FunctionType = functionType(i8, isVarArg = false, paramTypes = listOf(i8))

        fun function(name: String): LLVMValueRef = LLVMAddFunction(module, name, i8FunctionType)!!

        fun floatFunction(name: String): LLVMValueRef {
            val float = LLVMFloatTypeInContext(context)!!
            return LLVMAddFunction(module, name, functionType(float, isVarArg = false, paramTypes = listOf(float)))!!
        }

        fun expectation(
            symbolName: String = "boundary",
            returnExtension: RustBoundaryAbiExpectation.Extension = RustBoundaryAbiExpectation.Extension.NONE,
            parameterExtension: RustBoundaryAbiExpectation.Extension = RustBoundaryAbiExpectation.Extension.NONE,
        ) = RustBoundaryAbiExpectation(
            symbolName,
            0,
            returnExtension,
            listOf(parameterExtension),
        )

        fun directCall(userName: String, boundary: LLVMValueRef): LLVMValueRef = withBuilder(userName) { builder, parameter ->
            LLVMBuildCall2(builder, i8FunctionType, boundary, listOf(parameter).toCValues(), 1, "call")!!.also {
                LLVMBuildRet(builder, it)
            }
        }

        fun directInvoke(userName: String, boundary: LLVMValueRef): LLVMValueRef {
            val user = LLVMAddFunction(module, userName, i8FunctionType)!!
            val entry = LLVMAppendBasicBlockInContext(context, user, "entry")!!
            val success = LLVMAppendBasicBlockInContext(context, user, "success")!!
            val unwind = LLVMAppendBasicBlockInContext(context, user, "unwind")!!
            val builder = LLVMCreateBuilderInContext(context)!!
            return try {
                LLVMPositionBuilderAtEnd(builder, entry)
                val invoke = LLVMBuildInvoke2(
                    builder,
                    i8FunctionType,
                    boundary,
                    listOf(LLVMGetParam(user, 0)!!).toCValues(),
                    1,
                    success,
                    unwind,
                    "invoke",
                )!!
                LLVMPositionBuilderAtEnd(builder, success)
                LLVMBuildRet(builder, invoke)
                LLVMPositionBuilderAtEnd(builder, unwind)
                LLVMBuildUnreachable(builder)
                invoke
            } finally {
                LLVMDisposeBuilder(builder)
            }
        }

        fun indirectCall(userName: String, boundary: LLVMValueRef) {
            withBuilder(userName) { builder, parameter ->
                val slot = LLVMBuildAlloca(builder, LLVMTypeOf(boundary), "slot")!!
                LLVMBuildStore(builder, boundary, slot)
                val loaded = LLVMBuildLoad2(builder, LLVMTypeOf(boundary), slot, "loaded")!!
                LLVMBuildCall2(builder, i8FunctionType, loaded, listOf(parameter).toCValues(), 1, "indirect")!!.also {
                    LLVMBuildRet(builder, it)
                }
            }
        }

        fun addFunctionExtension(function: LLVMValueRef, index: Int, extension: ExtensionKind) {
            LLVMAddAttributeAtIndex(function, index, createLlvmEnumAttribute(context, extension))
        }

        fun addCallExtension(call: LLVMValueRef, index: Int, extension: ExtensionKind) {
            LLVMAddCallSiteAttribute(call, index, createLlvmEnumAttribute(context, extension))
        }

        fun close() {
            LLVMDisposeModule(module)
            llvm.LLVMContextDispose(context)
        }

        private inline fun <T> withBuilder(
            userName: String,
            block: (llvm.LLVMBuilderRef, LLVMValueRef) -> T,
        ): T {
            val user = LLVMAddFunction(module, userName, i8FunctionType)!!
            val entry = LLVMAppendBasicBlockInContext(context, user, "entry")!!
            val builder = LLVMCreateBuilderInContext(context)!!
            return try {
                LLVMPositionBuilderAtEnd(builder, entry)
                block(builder, LLVMGetParam(user, 0)!!)
            } finally {
                LLVMDisposeBuilder(builder)
            }
        }
    }

    private companion object {
        val SIGN_EXTEND: ExtensionKind
            get() = getLlvmAttributeKindId("signext")
        val ZERO_EXTEND: ExtensionKind
            get() = getLlvmAttributeKindId("zeroext")
    }
}

private typealias ExtensionKind = org.jetbrains.kotlin.backend.konan.llvm.LLVMAttributeKindId
