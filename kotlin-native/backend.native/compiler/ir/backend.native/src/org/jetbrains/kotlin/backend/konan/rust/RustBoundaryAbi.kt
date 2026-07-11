/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import llvm.LLVMAddAttributeAtIndex
import llvm.LLVMAddCallSiteAttribute
import llvm.LLVMAttributeReturnIndex
import llvm.LLVMAttributeRef
import llvm.LLVMAttributeRefVar
import llvm.LLVMAttributeFunctionIndex
import llvm.LLVMCallConv
import llvm.LLVMCountParams
import llvm.LLVMCountBasicBlocks
import llvm.LLVMGetAttributeCountAtIndex
import llvm.LLVMGetAttributesAtIndex
import llvm.LLVMGetBasicBlockParent
import llvm.LLVMGetCalledValue
import llvm.LLVMGetCallSiteAttributeCount
import llvm.LLVMGetCallSiteAttributes
import llvm.LLVMGetEnumAttributeAtIndex
import llvm.LLVMGetEnumAttributeKind
import llvm.LLVMGetEnumAttributeValue
import llvm.LLVMGetFirstUse
import llvm.LLVMGetFunctionCallConv
import llvm.LLVMGetInstructionCallConv
import llvm.LLVMGetModuleContext
import llvm.LLVMGetNextUse
import llvm.LLVMGetParam
import llvm.LLVMGetReturnType
import llvm.LLVMGetTypeAttributeValue
import llvm.LLVMGetTypeKind
import llvm.LLVMGetUser
import llvm.LLVMIsACallInst
import llvm.LLVMIsACastInst
import llvm.LLVMIsAConstantExpr
import llvm.LLVMIsAFunction
import llvm.LLVMIsAGlobalAlias
import llvm.LLVMIsAInvokeInst
import llvm.LLVMIsEnumAttribute
import llvm.LLVMIsFunctionVarArg
import llvm.LLVMIsStringAttribute
import llvm.LLVMIsTypeAttribute
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
import org.jetbrains.kotlin.backend.konan.llvm.llvmtype2string
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
    val shape: Shape? = null,
) {
    internal enum class Extension {
        NONE,
        SIGN_EXTEND,
        ZERO_EXTEND,
    }

    internal data class Shape(
        val functionType: String,
        val isVarArg: Boolean,
        val functionAttributes: Set<AbiAttribute>,
        val returnAttributes: Set<AbiAttribute>,
        val parameterAttributes: List<Set<AbiAttribute>>,
    )

    internal data class AbiAttribute(
        val name: String,
        val value: String,
    )

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
            val shape = captureShape(kotlinFunction, functionType, allowReturned = false) ?: return null
            return RustBoundaryAbiExpectation(
                symbolName,
                LLVMGetFunctionCallConv(kotlinFunction),
                returnExtension,
                parameterExtensions,
                shape,
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

private fun captureShape(
    function: LLVMValueRef,
    functionType: LLVMTypeRef,
    allowReturned: Boolean,
): RustBoundaryAbiExpectation.Shape? {
    // Extra vararg operands carry their own ABI attributes. Keep the boundary fail-closed until
    // generated Rust has an explicit per-call vararg contract instead of inferring one here.
    if (LLVMIsFunctionVarArg(functionType) != 0) return null
    val returnedParameters = returnedParameterIndices(function)
    if (returnedParameters.size > 1) return null
    val returnType = LLVMGetReturnType(functionType) ?: return null
    val returnAttributes = captureDefinitionSlotAttributes(
        function,
        LLVMAttributeReturnIndex,
        returnType,
        returnedParameters,
        allowReturned,
    ) ?: return null
    val parameterAttributes = (0 until LLVMCountParams(function)).map { parameterIndex ->
        val parameterType = LLVMGetParam(function, parameterIndex)?.type ?: return null
        captureDefinitionSlotAttributes(
            function,
            parameterIndex + 1,
            parameterType,
            returnedParameters,
            allowReturned,
        ) ?: return null
    }
    return RustBoundaryAbiExpectation.Shape(
        functionType = llvmtype2string(functionType),
        isVarArg = false,
        functionAttributes = captureFunctionAbiAttributes(functionAttributesAt(function, LLVMAttributeFunctionIndex)) ?: return null,
        returnAttributes = returnAttributes,
        parameterAttributes = parameterAttributes,
    )
}

private fun captureDefinitionSlotAttributes(
    function: LLVMValueRef,
    attributeIndex: Int,
    slotType: LLVMTypeRef,
    returnedParameters: Set<Int>,
    allowReturned: Boolean,
): Set<RustBoundaryAbiExpectation.AbiAttribute>? {
    val attributes = functionAttributesAt(function, attributeIndex)
    return captureSlotAttributes(
        attributes,
        slotType,
        allowCallSitePointerFacts = false,
        allowUninspectableSemanticAttributes = allowReturned && LLVMCountBasicBlocks(function) > 0,
    ) { kindId ->
        if (kindId != RETURNED.value) return@captureSlotAttributes false
        val parameterIndex = attributeIndex - 1
        allowReturned &&
                attributeIndex > LLVMAttributeReturnIndex &&
                parameterIndex in returnedParameters &&
                LLVMCountBasicBlocks(function) > 0 &&
                LLVMGetParam(function, parameterIndex)?.type?.let(::llvmtype2string) ==
                LLVMGetReturnType(getGlobalFunctionType(function))?.let(::llvmtype2string)
    }
}

private fun captureCallSiteSlotAttributes(
    callSite: LLVMValueRef,
    attributeIndex: Int,
    slotType: LLVMTypeRef,
): Set<RustBoundaryAbiExpectation.AbiAttribute>? =
    captureSlotAttributes(
        callSiteAttributesAt(callSite, attributeIndex),
        slotType,
        allowCallSitePointerFacts = true,
        allowUninspectableSemanticAttributes = true,
    ) { false }

private fun captureSlotAttributes(
    attributes: List<LLVMAttributeRef>,
    slotType: LLVMTypeRef,
    allowCallSitePointerFacts: Boolean,
    allowUninspectableSemanticAttributes: Boolean,
    allowReturned: (Int) -> Boolean,
): Set<RustBoundaryAbiExpectation.AbiAttribute>? {
    val result = linkedSetOf<RustBoundaryAbiExpectation.AbiAttribute>()
    for (attribute in attributes) {
        if (LLVMIsStringAttribute(attribute) != 0) return null
        val isType = LLVMIsTypeAttribute(attribute) != 0
        val isEnum = LLVMIsEnumAttribute(attribute) != 0
        // LLVM 21's remaining slot-attribute class is ConstantRange. Its payload has no C
        // inspection API, but on a definition/callsite it is a local optimizer proof, not ABI.
        if (!isType && !isEnum) {
            if (allowUninspectableSemanticAttributes) continue
            return null
        }
        val kindId = LLVMGetEnumAttributeKind(attribute)
        when (kindId) {
            SIGN_EXTEND.value, ZERO_EXTEND.value -> Unit
            NO_UNDEF.value -> if (!slotType.isSafeNoundefType()) return null
            NON_NULL.value, DEREFERENCEABLE.value, DEREFERENCEABLE_OR_NULL.value ->
                if (!allowCallSitePointerFacts || LLVMGetTypeKind(slotType) != LLVMTypeKind.LLVMPointerTypeKind) return null
            RETURNED.value -> if (!allowReturned(kindId)) return null
            in HARD_ABI_ATTRIBUTES -> {
                val name = HARD_ABI_ATTRIBUTES.getValue(kindId)
                val value = if (isType) {
                    LLVMGetTypeAttributeValue(attribute)?.let(::llvmtype2string) ?: return null
                } else {
                    LLVMGetEnumAttributeValue(attribute).toString()
                }
                result += RustBoundaryAbiExpectation.AbiAttribute(name, value)
            }
            else -> return null
        }
    }
    return result.toSortedSet(compareBy({ it.name }, { it.value }))
}

/**
 * Captures only the enumerated function-index attributes that affect the calling sequence.
 * Function-level optimization, unwind, target, and memory-effect attributes are intentionally not
 * boundary ABI: they are module-local semantic contracts and need not match at a direct call.
 */
private fun captureFunctionAbiAttributes(attributes: List<LLVMAttributeRef>): Set<RustBoundaryAbiExpectation.AbiAttribute>? =
    attributes.mapNotNullTo(linkedSetOf()) { attribute ->
        if (LLVMIsStringAttribute(attribute) != 0) return@mapNotNullTo null
        val isType = LLVMIsTypeAttribute(attribute) != 0
        val isEnum = LLVMIsEnumAttribute(attribute) != 0
        if (!isType && !isEnum) return@mapNotNullTo null
        val kindId = LLVMGetEnumAttributeKind(attribute)
        val name = HARD_ABI_ATTRIBUTES[kindId] ?: return@mapNotNullTo null
        val value = if (isType) {
            LLVMGetTypeAttributeValue(attribute)?.let(::llvmtype2string) ?: return null
        } else {
            LLVMGetEnumAttributeValue(attribute).toString()
        }
        RustBoundaryAbiExpectation.AbiAttribute(name, value)
    }.toSortedSet(compareBy({ it.name }, { it.value }))

private fun returnedParameterIndices(function: LLVMValueRef): Set<Int> =
    (0 until LLVMCountParams(function)).filterTo(linkedSetOf()) { parameterIndex ->
        functionAttributesAt(function, parameterIndex + 1).any { attribute ->
            (LLVMIsEnumAttribute(attribute) != 0 || LLVMIsTypeAttribute(attribute) != 0) &&
                    LLVMGetEnumAttributeKind(attribute) == RETURNED.value
        }
    }

private fun functionAttributesAt(function: LLVMValueRef, attributeIndex: Int): List<LLVMAttributeRef> = memScoped {
    val count = LLVMGetAttributeCountAtIndex(function, attributeIndex)
    if (count == 0) return@memScoped emptyList()
    val attributes = allocArray<LLVMAttributeRefVar>(count)
    LLVMGetAttributesAtIndex(function, attributeIndex, attributes)
    (0 until count).map { attributes[it]!! }
}

private fun callSiteAttributesAt(callSite: LLVMValueRef, attributeIndex: Int): List<LLVMAttributeRef> = memScoped {
    val count = LLVMGetCallSiteAttributeCount(callSite, attributeIndex)
    if (count == 0) return@memScoped emptyList()
    val attributes = allocArray<LLVMAttributeRefVar>(count)
    LLVMGetCallSiteAttributes(callSite, attributeIndex, attributes)
    (0 until count).map { attributes[it]!! }
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
        functionShapeMismatch(function, expectation)?.let { mismatch ->
            return RustBoundaryAbiNormalizationResult.failure("Rust boundary '${expectation.symbolName}' $mismatch")
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
                if (callSiteShapeMismatch(instruction, expectation) != null ||
                    !normalizeCallSiteExtensions(instruction, expectation)
                ) {
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
        functionShapeMismatch(function, expectation)?.let { mismatch ->
            return RustBoundaryAbiNormalizationResult.failure("boundary '${expectation.symbolName}' $mismatch after linkage")
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
                if (callSiteShapeMismatch(instruction, expectation) != null || !callSiteMatches(instruction, expectation)) {
                    return RustBoundaryAbiNormalizationResult.failure(
                        "a direct call to boundary '${expectation.symbolName}' has a different ABI after linkage"
                    )
                }
            }
        }
    }
    return RustBoundaryAbiNormalizationResult.Success
}

private fun functionShapeMismatch(function: LLVMValueRef, expectation: RustBoundaryAbiExpectation): String? {
    val expected = expectation.shape ?: return null
    val functionType = getGlobalFunctionType(function)
    val actualVarArg = LLVMIsFunctionVarArg(functionType) != 0
    if (actualVarArg != expected.isVarArg) return "has a different vararg shape"
    if (llvmtype2string(functionType) != expected.functionType) return "has a different function type"
    val actual = captureShape(function, functionType, allowReturned = true)
        ?: return "has unsupported ABI-shaping attributes"
    if (actual.functionAttributes != expected.functionAttributes ||
        actual.returnAttributes != expected.returnAttributes ||
        actual.parameterAttributes != expected.parameterAttributes
    ) {
        return "has different ABI-shaping attributes"
    }
    return null
}

private fun callSiteShapeMismatch(callSite: LLVMValueRef, expectation: RustBoundaryAbiExpectation): String? {
    val expected = expectation.shape ?: return null
    if (expected.isVarArg) return "has an unsupported vararg boundary"
    val functionType = llvm.LLVMGetCalledFunctionType(callSite) ?: return "has no called function type"
    val actualVarArg = LLVMIsFunctionVarArg(functionType) != 0
    if (actualVarArg != expected.isVarArg) return "has a different vararg shape"
    if (llvmtype2string(functionType) != expected.functionType) return "has a different function type"
    val returnType = LLVMGetReturnType(functionType) ?: return "has no return type"
    val actualReturnAttributes = captureCallSiteSlotAttributes(callSite, LLVMAttributeReturnIndex, returnType)
        ?: return "has unsupported return ABI attributes"
    if (!callSiteSlotAttributesMatch(actualReturnAttributes, expected.returnAttributes, returnType)) {
        return "has different ABI-shaping attributes"
    }
    val argumentCount = llvm.LLVMGetNumArgOperands(callSite)
    if ((!expected.isVarArg && argumentCount != expected.parameterAttributes.size) ||
        (expected.isVarArg && argumentCount < expected.parameterAttributes.size)
    ) {
        return "has a different argument count"
    }
    expected.parameterAttributes.forEachIndexed { parameterIndex, expectedAttributes ->
        val parameterType = llvm.LLVMGetArgOperand(callSite, parameterIndex)?.type
            ?: return "is missing parameter $parameterIndex"
        val actualAttributes = captureCallSiteSlotAttributes(callSite, parameterIndex + 1, parameterType)
            ?: return "has unsupported ABI attributes on parameter $parameterIndex"
        if (!callSiteSlotAttributesMatch(actualAttributes, expectedAttributes, parameterType)) {
            return "has different ABI attributes on parameter $parameterIndex"
        }
    }
    for (argumentIndex in expected.parameterAttributes.size until argumentCount) {
        val argumentType = llvm.LLVMGetArgOperand(callSite, argumentIndex)?.type
            ?: return "is missing vararg $argumentIndex"
        val attributes = captureCallSiteSlotAttributes(callSite, argumentIndex + 1, argumentType)
            ?: return "has unsupported ABI attributes on vararg $argumentIndex"
        if (attributes.isNotEmpty()) return "has ABI-shaping attributes on vararg $argumentIndex"
    }
    return null
}

private fun callSiteSlotAttributesMatch(
    actual: Set<RustBoundaryAbiExpectation.AbiAttribute>,
    expected: Set<RustBoundaryAbiExpectation.AbiAttribute>,
    slotType: LLVMTypeRef,
): Boolean {
    val actualAlign = actual.singleOrNull { it.name == "align" }?.value?.toLongOrNull()
    val expectedAlign = expected.singleOrNull { it.name == "align" }?.value?.toLongOrNull()
    if (actual.filterNot { it.name == "align" }.toSet() != expected.filterNot { it.name == "align" }.toSet()) return false
    if (actualAlign == null) return true // The validated callee declaration still carries its alignment contract.
    if (LLVMGetTypeKind(slotType) != LLVMTypeKind.LLVMPointerTypeKind) return actualAlign == expectedAlign
    return expectedAlign == null || actualAlign >= expectedAlign
}

private fun RustBoundaryAbiExpectation.canEscapeWithoutSlotAttributes(): Boolean =
    callingConvention == LLVMCallConv.LLVMCCallConv.value &&
            returnExtension == RustBoundaryAbiExpectation.Extension.NONE &&
            parameterExtensions.all { it == RustBoundaryAbiExpectation.Extension.NONE } &&
            shape?.let { capturedShape ->
                !capturedShape.isVarArg &&
                        capturedShape.functionAttributes.isEmpty() &&
                        capturedShape.returnAttributes.isEmpty() &&
                        capturedShape.parameterAttributes.all(Set<*>::isEmpty)
            } == true

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
    val argumentCount = llvm.LLVMGetNumArgOperands(callSite)
    val isVarArg = expectation.shape?.isVarArg == true
    if ((!isVarArg && argumentCount != expectation.parameterExtensions.size) ||
        (isVarArg && argumentCount < expectation.parameterExtensions.size)
    ) return false
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
    val argumentCount = llvm.LLVMGetNumArgOperands(callSite)
    val isVarArg = expectation.shape?.isVarArg == true
    if ((!isVarArg && argumentCount != expectation.parameterExtensions.size) ||
        (isVarArg && argumentCount < expectation.parameterExtensions.size)
    ) return false
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

private fun LLVMTypeRef.isSafeNoundefType(): Boolean = when (LLVMGetTypeKind(this)) {
    LLVMTypeKind.LLVMVoidTypeKind,
    LLVMTypeKind.LLVMFunctionTypeKind,
    LLVMTypeKind.LLVMLabelTypeKind,
    LLVMTypeKind.LLVMMetadataTypeKind,
    LLVMTypeKind.LLVMTokenTypeKind,
    -> false
    else -> true
}

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
private val NO_UNDEF = getLlvmAttributeKindId("noundef")
private val NON_NULL = getLlvmAttributeKindId("nonnull")
private val DEREFERENCEABLE = getLlvmAttributeKindId("dereferenceable")
private val DEREFERENCEABLE_OR_NULL = getLlvmAttributeKindId("dereferenceable_or_null")
private val RETURNED = getLlvmAttributeKindId("returned")
private val HARD_ABI_ATTRIBUTES = listOf(
    "sret",
    "byval",
    "byref",
    "inalloca",
    "preallocated",
    "inreg",
    "align",
    "alignstack",
    "noext",
    "nest",
    "swiftself",
    "swifterror",
    "swiftasync",
    "swiftcoro",
).associate { attributeName -> getLlvmAttributeKindId(attributeName).value to attributeName }
private val CAST_OPCODES = setOf(LLVMOpcode.LLVMBitCast, LLVMOpcode.LLVMAddrSpaceCast)
