/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust.codegen

import org.jetbrains.kotlin.backend.konan.KonanFqNames
import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isTopLevel

internal sealed interface RustManagedFieldCodegenResult {
    data class Generated(
        val source: String,
        val generatedFunctions: List<IrSimpleFunction>,
        val fallbackFunctions: List<IrSimpleFunction>,
    ) : RustManagedFieldCodegenResult

    data class Unsupported(val function: IrSimpleFunction, val reason: String) : RustManagedFieldCodegenResult
}

/** Generates the first managed heap-write slice while retaining Native's GC and layout ABI. */
internal fun generateRustManagedFieldFunction(
    function: IrSimpleFunction,
    linkerSymbolNamer: RustLinkerSymbolNamer,
    frameOverlayWords: Int,
    isAvailableFallback: (IrSimpleFunction) -> Boolean = { true },
    fieldOffsetBytes: (IrField) -> Long?,
): RustManagedFieldCodegenResult {
    fun unsupported(reason: String) = RustManagedFieldCodegenResult.Unsupported(function, reason)
    if (frameOverlayWords <= 0) return unsupported("the target FrameOverlay size must be positive")
    if (!function.isTopLevel || function.parent !is IrFile) return unsupported("only top-level functions are supported")
    if (function.isExternal || function.isSuspend || function.typeParameters.isNotEmpty()) return unsupported("unsupported function kind")
    if (function.parameters.isNotEmpty()) return unsupported("the field slice must have no parameters")
    if (!function.returnType.isManagedReference()) return unsupported("the field slice must return a managed reference")
    val body = function.body as? IrBlockBody ?: return unsupported("the field slice must have a block body")

    val returnExpression = body.statements.lastOrNull() as? IrReturn ?: return unsupported("the last statement must return the holder")
    if (returnExpression.returnTargetSymbol.owner != function) return unsupported("the return must target the generated function")
    val holderIndex = body.statements.indexOfFirst { it is IrVariable }
    if (holderIndex < 0) return unsupported("the holder local is missing")
    val holder = body.statements[holderIndex] as IrVariable
    if (holder.isVar || !holder.type.isManagedReference()) return unsupported("the holder must be an immutable managed local")
    val holderFactoryCall = holder.initializer as? IrCall ?: return unsupported("the holder must come from a direct factory call")
    val holderFactory = holderFactoryCall.symbol.owner
    if (invalidManagedFactory(holderFactoryCall) || !isAvailableFallback(holderFactory)) return unsupported("the holder factory is unavailable")
    val returnedHolder = returnExpression.value.unwrapImplicitCasts() as? IrGetValue ?: return unsupported("the return must read the holder")
    if (returnedHolder.symbol.owner != holder) return unsupported("the returned value must be the holder")

    val setIndex = body.statements.indexOfFirst { statement ->
        statement is IrSetField || statement is IrCall && statement.symbol.owner.correspondingPropertySymbol?.owner?.backingField != null
    }
    if (setIndex <= holderIndex || setIndex >= body.statements.lastIndex) return unsupported("one managed field write is required")
    val setStatement = body.statements[setIndex]
    val field: IrField
    val receiverExpression: org.jetbrains.kotlin.ir.expressions.IrExpression
    val valueExpression: org.jetbrains.kotlin.ir.expressions.IrExpression
    when (setStatement) {
        is IrSetField -> {
            field = setStatement.symbol.owner
            receiverExpression = setStatement.receiver ?: return unsupported("the field receiver is missing")
            valueExpression = setStatement.value
        }
        is IrCall -> {
            field = setStatement.symbol.owner.correspondingPropertySymbol?.owner?.backingField
                ?: return unsupported("the property setter has no backing field")
            val arguments = setStatement.arguments.filterNotNull()
            if (arguments.size != 2) return unsupported("the property setter must have receiver and value arguments")
            receiverExpression = arguments[0]
            valueExpression = arguments[1]
        }
        else -> return unsupported("one managed field write is required")
    }
    val receiver = receiverExpression.unwrapImplicitCasts() as? IrGetValue ?: return unsupported("the field receiver must be the holder")
    if (receiver.symbol.owner != holder) return unsupported("the field receiver must be the holder")
    if (field.isStatic || !field.type.isManagedReference()) return unsupported("the field must be an instance managed reference")
    val offset = fieldOffsetBytes(field) ?: return unsupported("the Native field offset is unavailable")
    if (offset < 0) return unsupported("the Native field offset is negative")
    val heapUpdateFunction = if (field.hasAnnotation(KonanFqNames.volatile)) "UpdateVolatileHeapRef" else "UpdateHeapRef"
    val payloadFactoryCall = valueExpression.unwrapImplicitCasts() as? IrCall ?: return unsupported("the field value must come from a direct factory call")
    val payloadFactory = payloadFactoryCall.symbol.owner
    if (invalidManagedFactory(payloadFactoryCall) || !isAvailableFallback(payloadFactory)) return unsupported("the payload factory is unavailable")

    fun unitCalls(from: Int, until: Int): List<IrCall>? = buildList {
        for (index in from until until) {
            val call = body.statements[index] as? IrCall ?: return null
            if (invalidUnitFallback(call) || !isAvailableFallback(call.symbol.owner)) return null
            add(call)
        }
    }
    val callsBeforeHolder = unitCalls(0, holderIndex) ?: return unsupported("only Unit calls may precede the holder")
    val callsBeforeWrite = unitCalls(holderIndex + 1, setIndex) ?: return unsupported("only Unit calls may precede the field write")
    val callsAfterWrite = unitCalls(setIndex + 1, body.statements.lastIndex) ?: return unsupported("only Unit calls may follow the field write")
    if (callsAfterWrite.isEmpty()) return unsupported("a post-write Unit call is required")
    val unitCalls = callsBeforeHolder + callsBeforeWrite + callsAfterWrite
    val fallbackFunctions = (listOf(holderFactory, payloadFactory) + unitCalls.map { it.symbol.owner }).distinct()

    val exportedName = linkerSymbolNamer.linkerName(function)
    val prefix = "kn_field_${stableFieldHash(exportedName)}"
    val unitNames = unitCalls.map { it.symbol.owner }.distinct()
        .mapIndexed { index, fallback -> fallback to "${prefix}_unit_$index" }
        .toMap()
    val source = buildString {
        appendLine("// Kotlin/Native managed field-write Rust lowering for ${function.name.asString()}")
        appendLine("const ${prefix.uppercase()}_OVERLAY: usize = $frameOverlayWords;")
        appendLine("const ${prefix.uppercase()}_WORDS: usize = ${frameOverlayWords + 2};")
        appendLine("extern \"C-unwind\" {")
        appendLine("    #[link_name = \"${escapeFieldString(linkerSymbolNamer.linkerName(holderFactory))}\"]")
        appendLine("    fn ${prefix}_holder(return_slot: *mut KRef) -> KRef;")
        appendLine("    #[link_name = \"${escapeFieldString(linkerSymbolNamer.linkerName(payloadFactory))}\"]")
        appendLine("    fn ${prefix}_payload(return_slot: *mut KRef) -> KRef;")
        for (entry in unitNames.entries) {
            appendLine("    #[link_name = \"${escapeFieldString(linkerSymbolNamer.linkerName(entry.key))}\"]")
            appendLine("    fn ${entry.value}();")
        }
        appendLine("}")
        appendLine("#[allow(non_camel_case_types)]")
        appendLine("struct ${prefix}_Guard { previous: *mut KRef, frame: *mut KRef, active: bool }")
        appendLine("impl ${prefix}_Guard {")
        appendLine("    fn leave(&mut self) { if self.active { unsafe { LeaveFrame(self.frame, 0, ${prefix.uppercase()}_WORDS as i32); } self.active = false; } }")
        appendLine("}")
        appendLine("impl Drop for ${prefix}_Guard {")
        appendLine("    fn drop(&mut self) { if self.active { unsafe { SetCurrentFrame(self.previous); } self.active = false; } }")
        appendLine("}")
        appendLine("/// # Safety")
        appendLine("/// `return_slot` must be a valid Kotlin/Native managed-reference result slot.")
        appendLine("#[export_name = \"${escapeFieldString(exportedName)}\"]")
        appendLine("pub unsafe extern \"C-unwind\" fn ${prefix}_entry(return_slot: *mut KRef) -> KRef {")
        appendLine("    unsafe {")
        appendLine("        let mut storage: [KRef; ${prefix.uppercase()}_WORDS] = [core::ptr::null_mut(); ${prefix.uppercase()}_WORDS];")
        appendLine("        let frame = core::ptr::addr_of_mut!(storage).cast::<KRef>();")
        appendLine("        let holder_root = frame.add(${prefix.uppercase()}_OVERLAY);")
        appendLine("        let payload_root = holder_root.add(1);")
        appendLine("        let previous = getCurrentFrame();")
        appendLine("        EnterFrame(frame, 0, ${prefix.uppercase()}_WORDS as i32);")
        appendLine("        let mut frame_guard = ${prefix}_Guard { previous, frame, active: true };")
        appendLine("        Kotlin_mm_safePointFunctionPrologue();")
        callsBeforeHolder.forEach { appendLine("        ${unitNames.getValue(it.symbol.owner)}();") }
        appendLine("        let holder = ${prefix}_holder(holder_root);")
        appendLine("        UpdateStackRef(holder_root, holder);")
        callsBeforeWrite.forEach { appendLine("        ${unitNames.getValue(it.symbol.owner)}();") }
        appendLine("        let payload = ${prefix}_payload(payload_root);")
        appendLine("        UpdateStackRef(payload_root, payload);")
        appendLine("        let field_location = holder.cast::<u8>().add(${offset}usize).cast::<KRef>();")
        appendLine("        $heapUpdateFunction(field_location, payload);")
        appendLine("        UpdateStackRef(payload_root, core::ptr::null_mut());")
        callsAfterWrite.forEach { appendLine("        ${unitNames.getValue(it.symbol.owner)}();") }
        appendLine("        let result = core::ptr::read(holder_root);")
        appendLine("        UpdateReturnRef(return_slot, result);")
        appendLine("        frame_guard.leave();")
        appendLine("        result")
        appendLine("    }")
        appendLine("}")
    }
    return RustManagedFieldCodegenResult.Generated(source, listOf(function), fallbackFunctions)
}

private fun invalidManagedFactory(call: IrCall): Boolean {
    val callee = call.symbol.owner
    return !callee.isTopLevel || callee.parent !is IrFile || callee.isExternal || callee.isSuspend ||
            callee.typeParameters.isNotEmpty() || callee.parameters.isNotEmpty() || call.arguments.any { it != null } ||
            !callee.returnType.isManagedReference()
}

private fun invalidUnitFallback(call: IrCall): Boolean {
    val callee = call.symbol.owner
    return !callee.isTopLevel || callee.parent !is IrFile || callee.isExternal || callee.isSuspend ||
            callee.typeParameters.isNotEmpty() || callee.parameters.isNotEmpty() || call.arguments.any { it != null } || !callee.returnType.isUnit()
}

private fun org.jetbrains.kotlin.ir.types.IrType.isManagedReference(): Boolean =
    binaryTypeIsReference() && !isUnit() && !isNothing()

private fun org.jetbrains.kotlin.ir.expressions.IrExpression.unwrapImplicitCasts(): org.jetbrains.kotlin.ir.expressions.IrExpression {
    var result = this
    while (result is IrTypeOperatorCall && result.operator in setOf(IrTypeOperator.IMPLICIT_CAST, IrTypeOperator.IMPLICIT_NOTNULL)) result = result.argument
    return result
}

private fun stableFieldHash(value: String): String {
    var hash = 0xcbf29ce484222325UL
    value.forEach { hash = (hash xor it.code.toULong()) * 0x100000001b3UL }
    return hash.toString(16)
}

private fun escapeFieldString(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
