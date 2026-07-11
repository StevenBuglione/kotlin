/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import kotlinx.cinterop.toKString
import llvm.LLVMAddAttributeAtIndex
import llvm.LLVMAddCallSiteAttribute
import llvm.LLVMAttributeReturnIndex
import llvm.LLVMCallConv
import llvm.LLVMCountParams
import llvm.LLVMGetBasicBlockParent
import llvm.LLVMGetCalledValue
import llvm.LLVMGetEnumAttributeAtIndex
import llvm.LLVMGetFirstUse
import llvm.LLVMGetFunctionCallConv
import llvm.LLVMGetInstructionCallConv
import llvm.LLVMGetModuleContext
import llvm.LLVMGetNextUse
import llvm.LLVMGetParam
import llvm.LLVMGetReturnType
import llvm.LLVMGetTypeKind
import llvm.LLVMGetUser
import llvm.LLVMIsACallInst
import llvm.LLVMIsACastInst
import llvm.LLVMIsAConstantExpr
import llvm.LLVMIsAFunction
import llvm.LLVMIsAGlobalAlias
import llvm.LLVMIsAInvokeInst
import llvm.LLVMModuleRef
import llvm.LLVMOpcode
import llvm.LLVMRemoveCallSiteEnumAttribute
import llvm.LLVMRemoveEnumAttributeAtIndex
import llvm.LLVMTypeKind
import llvm.LLVMTypeRef
import llvm.LLVMValueRef
import llvm.LLVMWriteBitcodeToFile
import llvm.LLVMAliasGetAliasee
import llvm.LLVMGetConstOpcode
import llvm.LLVMGetOperand
import org.jetbrains.kotlin.backend.konan.llvm.LLVMAttributeKindId
import org.jetbrains.kotlin.backend.konan.llvm.createLlvmEnumAttribute
import org.jetbrains.kotlin.backend.konan.llvm.getBasicBlocks
import org.jetbrains.kotlin.backend.konan.llvm.getGlobalAliases
import org.jetbrains.kotlin.backend.konan.llvm.getGlobalFunctionType
import org.jetbrains.kotlin.backend.konan.llvm.getInstructions
import org.jetbrains.kotlin.backend.konan.llvm.getLlvmAttributeKindId
import org.jetbrains.kotlin.backend.konan.llvm.getFunctions
import org.jetbrains.kotlin.backend.konan.llvm.isFunctionCall
import org.jetbrains.kotlin.backend.konan.llvm.type
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal data class RustBoundaryAbiExpectation(
    val symbolName: String,
    val callingConvention: Int,
    val returnExtension: Extension,
    val parameterExtensions: List<Extension>,
) {
    internal enum class Extension {
        NONE,
        SIGN_EXTEND,
        ZERO_EXTEND,
    }

    companion object {
        fun capture(symbolName: String, kotlinFunction: LLVMValueRef): RustBoundaryAbiExpectation? {
            val functionType = getGlobalFunctionType(kotlinFunction)
            val returnExtension = extensionAt(kotlinFunction, LLVMAttributeReturnIndex) ?: return null
            if (!returnExtension.isValidFor(LLVMGetReturnType(functionType) ?: return null)) return null
            val parameterExtensions = (0 until LLVMCountParams(kotlinFunction)).map { parameterIndex ->
                val extension = extensionAt(kotlinFunction, parameterIndex + 1) ?: return null
                val parameterType = LLVMGetParam(kotlinFunction, parameterIndex)?.type ?: return null
                if (!extension.isValidFor(parameterType)) return null
                extension
            }
            return RustBoundaryAbiExpectation(
                symbolName,
                LLVMGetFunctionCallConv(kotlinFunction),
                returnExtension,
                parameterExtensions,
            )
        }
    }
}

internal data class RustBoundaryAbiNormalizationResult(val failure: String?) {
    val isSuccess: Boolean
        get() = failure == null

    companion object {
        val Success = RustBoundaryAbiNormalizationResult(null)

        fun failure(message: String) = RustBoundaryAbiNormalizationResult(message)
    }
}

internal fun normalizeRustBoundaryAbi(
    rustModule: LLVMModuleRef,
    expectations: Collection<RustBoundaryAbiExpectation>,
): RustBoundaryAbiNormalizationResult {
    val boundaries = expectations.map { expectation ->
        val boundaryValue = rustBoundaryValue(rustModule, expectation.symbolName)
            ?: return RustBoundaryAbiNormalizationResult.failure("missing Rust boundary '${expectation.symbolName}'")
        val function = resolveFunction(boundaryValue)
            ?: return RustBoundaryAbiNormalizationResult.failure("Rust boundary '${expectation.symbolName}' does not resolve to a function")
        if (LLVMCountParams(function) != expectation.parameterExtensions.size) {
            return RustBoundaryAbiNormalizationResult.failure("Rust boundary '${expectation.symbolName}' has a different parameter count")
        }
        if (LLVMGetFunctionCallConv(function) != expectation.callingConvention) {
            return RustBoundaryAbiNormalizationResult.failure("Rust boundary '${expectation.symbolName}' has a different calling convention")
        }
        if (!normalizeFunctionExtensions(function, expectation)) {
            return RustBoundaryAbiNormalizationResult.failure("Rust boundary '${expectation.symbolName}' has invalid extension attributes")
        }
        Boundary(boundaryValue, function, expectation)
    }

    for (boundary in boundaries) {
        if (!hasOnlyDirectBoundaryUses(boundary.boundaryValue, boundary.function, mutableSetOf())) {
            return RustBoundaryAbiNormalizationResult.failure("Rust boundary '${boundary.expectation.symbolName}' has an indirect or escaping use")
        }
        if (boundary.boundaryValue != boundary.function &&
            !hasOnlyDirectBoundaryUses(boundary.function, boundary.function, mutableSetOf())) {
            return RustBoundaryAbiNormalizationResult.failure("Rust boundary '${boundary.expectation.symbolName}' has an indirect or escaping function use")
        }
    }

    val expectationsByFunction = boundaries.associate { it.function to it.expectation }
    for (function in getFunctions(rustModule)) {
        for (block in getBasicBlocks(function)) {
            for (instruction in getInstructions(block)) {
                if (!instruction.isFunctionCall()) continue
                val calledFunction = LLVMGetCalledValue(instruction)?.let(::resolveFunction) ?: continue
                val expectation = expectationsByFunction[calledFunction] ?: continue
                if (!normalizeCallSiteExtensions(instruction, expectation)) {
                    return RustBoundaryAbiNormalizationResult.failure("a direct call to Rust boundary '${expectation.symbolName}' has an incompatible ABI")
                }
            }
        }
    }
    return verifyRustBoundaryAbi(rustModule, expectations)
}

internal fun verifyRustBoundaryAbi(
    module: LLVMModuleRef,
    expectations: Collection<RustBoundaryAbiExpectation>,
    allowAbiNeutralEscapes: Boolean = false,
): RustBoundaryAbiNormalizationResult {
    val boundaries = expectations.map { expectation ->
        val boundaryValue = rustBoundaryValue(module, expectation.symbolName)
            ?: return RustBoundaryAbiNormalizationResult.failure("missing boundary '${expectation.symbolName}' after linkage")
        val function = resolveFunction(boundaryValue)
            ?: return RustBoundaryAbiNormalizationResult.failure("boundary '${expectation.symbolName}' does not resolve to a function after linkage")
        if (LLVMCountParams(function) != expectation.parameterExtensions.size) {
            return RustBoundaryAbiNormalizationResult.failure("boundary '${expectation.symbolName}' has a different parameter count after linkage")
        }
        if (LLVMGetFunctionCallConv(function) != expectation.callingConvention) {
            return RustBoundaryAbiNormalizationResult.failure("boundary '${expectation.symbolName}' has a different calling convention after linkage")
        }
        val functionType = getGlobalFunctionType(function)
        if (extensionAt(function, LLVMAttributeReturnIndex) != expectation.returnExtension ||
            !expectation.returnExtension.isValidFor(LLVMGetReturnType(functionType) ?: return RustBoundaryAbiNormalizationResult.failure(
                "boundary '${expectation.symbolName}' has no return type after linkage"
            ))) {
            return RustBoundaryAbiNormalizationResult.failure("boundary '${expectation.symbolName}' has a different return extension after linkage")
        }
        expectation.parameterExtensions.forEachIndexed { parameterIndex, expectedExtension ->
            val parameter = LLVMGetParam(function, parameterIndex)
                ?: return RustBoundaryAbiNormalizationResult.failure("boundary '${expectation.symbolName}' is missing parameter $parameterIndex after linkage")
            if (extensionAt(function, parameterIndex + 1) != expectedExtension || !expectedExtension.isValidFor(parameter.type)) {
                return RustBoundaryAbiNormalizationResult.failure(
                    "boundary '${expectation.symbolName}' has a different extension on parameter $parameterIndex after linkage"
                )
            }
        }
        Boundary(boundaryValue, function, expectation)
    }

    for (boundary in boundaries) {
        val mayEscape = allowAbiNeutralEscapes && boundary.expectation.canEscapeWithoutSlotAttributes()
        if (!mayEscape && !hasOnlyDirectBoundaryUses(boundary.boundaryValue, boundary.function, mutableSetOf())) {
            return RustBoundaryAbiNormalizationResult.failure("boundary '${boundary.expectation.symbolName}' has an indirect or escaping use after linkage")
        }
        if (!mayEscape && boundary.boundaryValue != boundary.function &&
            !hasOnlyDirectBoundaryUses(boundary.function, boundary.function, mutableSetOf())) {
            return RustBoundaryAbiNormalizationResult.failure("boundary '${boundary.expectation.symbolName}' has an indirect or escaping function use after linkage")
        }
    }

    val expectationsByFunction = boundaries.associate { it.function to it.expectation }
    for (function in getFunctions(module)) {
        for (block in getBasicBlocks(function)) {
            for (instruction in getInstructions(block)) {
                if (!instruction.isFunctionCall()) continue
                val calledFunction = LLVMGetCalledValue(instruction)?.let(::resolveFunction) ?: continue
                val expectation = expectationsByFunction[calledFunction] ?: continue
                if (!callSiteMatches(instruction, expectation)) {
                    return RustBoundaryAbiNormalizationResult.failure(
                        "a direct call to boundary '${expectation.symbolName}' has a different ABI after linkage"
                    )
                }
            }
        }
    }
    return RustBoundaryAbiNormalizationResult.Success
}

private fun RustBoundaryAbiExpectation.canEscapeWithoutSlotAttributes(): Boolean =
    callingConvention == LLVMCallConv.LLVMCCallConv.value &&
            returnExtension == RustBoundaryAbiExpectation.Extension.NONE &&
            parameterExtensions.all { it == RustBoundaryAbiExpectation.Extension.NONE }

internal fun writeRustBoundaryBitcodeAtomically(module: LLVMModuleRef, output: Path): Boolean {
    val normalizedOutput = output.toAbsolutePath().normalize()
    val parent = normalizedOutput.parent ?: return false
    Files.createDirectories(parent)
    val temporary = Files.createTempFile(parent, ".${normalizedOutput.fileName}.", ".bc")
    try {
        if (LLVMWriteBitcodeToFile(module, temporary.toString()) != 0) return false
        try {
            Files.move(temporary, normalizedOutput, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            Files.move(temporary, normalizedOutput, StandardCopyOption.REPLACE_EXISTING)
        }
        return true
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private data class Boundary(
    val boundaryValue: LLVMValueRef,
    val function: LLVMValueRef,
    val expectation: RustBoundaryAbiExpectation,
)

private fun normalizeFunctionExtensions(
    function: LLVMValueRef,
    expectation: RustBoundaryAbiExpectation,
): Boolean {
    val functionType = getGlobalFunctionType(function)
    if (!normalizeFunctionExtension(
            function,
            LLVMAttributeReturnIndex,
            LLVMGetReturnType(functionType) ?: return false,
            expectation.returnExtension,
        )
    ) {
        return false
    }
    return expectation.parameterExtensions.withIndex().all { indexedExtension ->
        val parameterIndex = indexedExtension.index
        val extension = indexedExtension.value
        val parameterType = LLVMGetParam(function, parameterIndex)?.type ?: return false
        normalizeFunctionExtension(function, parameterIndex + 1, parameterType, extension)
    }
}

private fun normalizeFunctionExtension(
    function: LLVMValueRef,
    attributeIndex: Int,
    type: LLVMTypeRef,
    expected: RustBoundaryAbiExpectation.Extension,
): Boolean {
    val actual = extensionAt(function, attributeIndex) ?: return false
    if (!actual.isValidFor(type) || !expected.isValidFor(type)) return false
    LLVMRemoveEnumAttributeAtIndex(function, attributeIndex, SIGN_EXTEND.value)
    LLVMRemoveEnumAttributeAtIndex(function, attributeIndex, ZERO_EXTEND.value)
    expected.attributeKind?.let { kind ->
        val context = LLVMGetModuleContext(llvm.LLVMGetGlobalParent(function)) ?: return false
        LLVMAddAttributeAtIndex(function, attributeIndex, createLlvmEnumAttribute(context, kind))
    }
    return true
}

private fun normalizeCallSiteExtensions(
    callSite: LLVMValueRef,
    expectation: RustBoundaryAbiExpectation,
): Boolean {
    if (LLVMGetInstructionCallConv(callSite) != expectation.callingConvention) return false
    val calledFunctionType = llvm.LLVMGetCalledFunctionType(callSite) ?: return false
    if (!normalizeCallSiteExtension(
            callSite,
            LLVMAttributeReturnIndex,
            LLVMGetReturnType(calledFunctionType) ?: return false,
            expectation.returnExtension,
        )
    ) {
        return false
    }
    if (llvm.LLVMGetNumArgOperands(callSite) != expectation.parameterExtensions.size) return false
    return expectation.parameterExtensions.withIndex().all { indexedExtension ->
        val parameterIndex = indexedExtension.index
        val extension = indexedExtension.value
        val parameter = llvm.LLVMGetArgOperand(callSite, parameterIndex) ?: return false
        normalizeCallSiteExtension(callSite, parameterIndex + 1, parameter.type, extension)
    }
}

private fun normalizeCallSiteExtension(
    callSite: LLVMValueRef,
    attributeIndex: Int,
    type: LLVMTypeRef,
    expected: RustBoundaryAbiExpectation.Extension,
): Boolean {
    val actual = callSiteExtensionAt(callSite, attributeIndex) ?: return false
    if (!actual.isValidFor(type) || !expected.isValidFor(type)) return false
    LLVMRemoveCallSiteEnumAttribute(callSite, attributeIndex, SIGN_EXTEND.value)
    LLVMRemoveCallSiteEnumAttribute(callSite, attributeIndex, ZERO_EXTEND.value)
    expected.attributeKind?.let { kind ->
        val parentFunction = LLVMGetBasicBlockParent(llvm.LLVMGetInstructionParent(callSite)) ?: return false
        val context = LLVMGetModuleContext(llvm.LLVMGetGlobalParent(parentFunction)) ?: return false
        LLVMAddCallSiteAttribute(callSite, attributeIndex, createLlvmEnumAttribute(context, kind))
    }
    return true
}

private fun callSiteMatches(
    callSite: LLVMValueRef,
    expectation: RustBoundaryAbiExpectation,
): Boolean {
    if (LLVMGetInstructionCallConv(callSite) != expectation.callingConvention) return false
    val calledFunctionType = llvm.LLVMGetCalledFunctionType(callSite) ?: return false
    if (callSiteExtensionAt(callSite, LLVMAttributeReturnIndex) != expectation.returnExtension ||
        !expectation.returnExtension.isValidFor(LLVMGetReturnType(calledFunctionType) ?: return false)) return false
    if (llvm.LLVMGetNumArgOperands(callSite) != expectation.parameterExtensions.size) return false
    return expectation.parameterExtensions.withIndex().all { indexedExtension ->
        val parameter = llvm.LLVMGetArgOperand(callSite, indexedExtension.index) ?: return false
        callSiteExtensionAt(callSite, indexedExtension.index + 1) == indexedExtension.value &&
                indexedExtension.value.isValidFor(parameter.type)
    }
}

private fun extensionAt(function: LLVMValueRef, attributeIndex: Int): RustBoundaryAbiExpectation.Extension? =
    extension(
        LLVMGetEnumAttributeAtIndex(function, attributeIndex, SIGN_EXTEND.value) != null,
        LLVMGetEnumAttributeAtIndex(function, attributeIndex, ZERO_EXTEND.value) != null,
    )

private fun callSiteExtensionAt(callSite: LLVMValueRef, attributeIndex: Int): RustBoundaryAbiExpectation.Extension? =
    extension(
        llvm.LLVMGetCallSiteEnumAttribute(callSite, attributeIndex, SIGN_EXTEND.value) != null,
        llvm.LLVMGetCallSiteEnumAttribute(callSite, attributeIndex, ZERO_EXTEND.value) != null,
    )

private fun extension(signExtend: Boolean, zeroExtend: Boolean): RustBoundaryAbiExpectation.Extension? = when {
    signExtend && zeroExtend -> null
    signExtend -> RustBoundaryAbiExpectation.Extension.SIGN_EXTEND
    zeroExtend -> RustBoundaryAbiExpectation.Extension.ZERO_EXTEND
    else -> RustBoundaryAbiExpectation.Extension.NONE
}

private fun RustBoundaryAbiExpectation.Extension.isValidFor(type: LLVMTypeRef): Boolean =
    this == RustBoundaryAbiExpectation.Extension.NONE || LLVMGetTypeKind(type) == LLVMTypeKind.LLVMIntegerTypeKind

private val RustBoundaryAbiExpectation.Extension.attributeKind: LLVMAttributeKindId?
    get() = when (this) {
        RustBoundaryAbiExpectation.Extension.NONE -> null
        RustBoundaryAbiExpectation.Extension.SIGN_EXTEND -> SIGN_EXTEND
        RustBoundaryAbiExpectation.Extension.ZERO_EXTEND -> ZERO_EXTEND
    }

private fun rustBoundaryValue(module: LLVMModuleRef, symbolName: String): LLVMValueRef? =
    llvm.LLVMGetNamedFunction(module, symbolName)
        ?: getGlobalAliases(module).firstOrNull { llvm.LLVMGetValueName(it)?.toKString() == symbolName }

private fun resolveFunction(value: LLVMValueRef): LLVMValueRef? = when {
    LLVMIsAFunction(value) != null -> value
    LLVMIsAGlobalAlias(value) != null -> LLVMAliasGetAliasee(value)?.let(::resolveFunction)
    LLVMIsACastInst(value) != null -> LLVMGetOperand(value, 0)?.let(::resolveFunction)
    LLVMIsAConstantExpr(value) != null && LLVMGetConstOpcode(value) in CAST_OPCODES ->
        LLVMGetOperand(value, 0)?.let(::resolveFunction)
    else -> null
}

private fun hasOnlyDirectBoundaryUses(
    value: LLVMValueRef,
    boundaryFunction: LLVMValueRef,
    visited: MutableSet<LLVMValueRef>,
): Boolean {
    if (!visited.add(value)) return true
    return generateSequence(LLVMGetFirstUse(value)) { LLVMGetNextUse(it) }.all { use ->
        val user = LLVMGetUser(use) ?: return@all false
        when {
            LLVMIsACallInst(user) != null || LLVMIsAInvokeInst(user) != null ->
                LLVMGetCalledValue(user)?.let(::resolveFunction) == boundaryFunction
            LLVMIsAGlobalAlias(user) != null || LLVMIsACastInst(user) != null || LLVMIsAConstantExpr(user) != null ->
                hasOnlyDirectBoundaryUses(user, boundaryFunction, visited)
            else -> false
        }
    }
}

private val SIGN_EXTEND = getLlvmAttributeKindId("signext")
private val ZERO_EXTEND = getLlvmAttributeKindId("zeroext")
private val CAST_OPCODES = setOf(LLVMOpcode.LLVMBitCast, LLVMOpcode.LLVMAddrSpaceCast)
