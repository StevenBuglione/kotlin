/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust.codegen

import org.jetbrains.kotlin.backend.konan.KonanFqNames
import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isTopLevel

internal sealed interface RustPrimitiveFieldReadCodegenResult {
    data class Generated(
        val source: String,
        val generatedFunctions: List<IrSimpleFunction>,
        val fallbackFunctions: List<IrSimpleFunction>,
    ) : RustPrimitiveFieldReadCodegenResult

    data class Unsupported(val function: IrSimpleFunction, val reason: String) : RustPrimitiveFieldReadCodegenResult
}

/** Generates a rooted, nonvolatile Int field-read slice using Native's finalized object layout. */
internal fun generateRustPrimitiveFieldReadFunction(
    function: IrSimpleFunction,
    linkerSymbolNamer: RustLinkerSymbolNamer,
    frameOverlayWords: Int,
    isAvailableFallback: (IrSimpleFunction) -> Boolean = { true },
    fieldOffsetBytes: (IrField) -> Long?,
): RustPrimitiveFieldReadCodegenResult {
    fun unsupported(reason: String) = RustPrimitiveFieldReadCodegenResult.Unsupported(function, reason)
    if (frameOverlayWords <= 0) return unsupported("the target FrameOverlay size must be positive")
    if (!function.isTopLevel || function.parent !is IrFile) return unsupported("only top-level functions are supported")
    if (function.isExternal || function.isSuspend || function.typeParameters.isNotEmpty()) return unsupported("unsupported function kind")
    if (function.parameters.isNotEmpty()) return unsupported("the field-read slice must have no parameters")
    if (!function.returnType.isInt()) return unsupported("the field-read slice must return Int")
    val body = function.body as? IrBlockBody ?: return unsupported("the field-read slice must have a block body")

    val returnExpression = body.statements.lastOrNull() as? IrReturn ?: return unsupported("the last statement must return the field")
    if (returnExpression.returnTargetSymbol.owner != function) return unsupported("the return must target the generated function")
    val holderIndex = body.statements.indexOfFirst { it is IrVariable }
    if (holderIndex < 0) return unsupported("the holder local is missing")
    val holder = body.statements[holderIndex] as IrVariable
    if (holder.isVar || !holder.type.isManagedReference() || holder.type.isNullable()) {
        return unsupported("the holder must be an immutable non-null managed local")
    }
    val holderFactoryCall = holder.initializer as? IrCall ?: return unsupported("the holder must come from a direct factory call")
    val holderFactory = holderFactoryCall.symbol.owner
    if (invalidManagedFactory(holderFactoryCall) || !isAvailableFallback(holderFactory)) return unsupported("the holder factory is unavailable")

    val field: IrField
    val receiverExpression: org.jetbrains.kotlin.ir.expressions.IrExpression
    when (val returnedValue = returnExpression.value.unwrapImplicitCasts()) {
        is IrGetField -> {
            field = returnedValue.symbol.owner
            receiverExpression = returnedValue.receiver ?: return unsupported("the field receiver is missing")
        }
        is IrCall -> {
            val getter = returnedValue.symbol.owner
            if (getter.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) return unsupported("only default property getters are supported")
            field = getter.correspondingPropertySymbol?.owner?.backingField
                ?: return unsupported("the property getter has no backing field")
            val arguments = returnedValue.arguments.filterNotNull()
            if (arguments.size != 1) return unsupported("the property getter must have one receiver argument")
            receiverExpression = arguments.single()
        }
        else -> return unsupported("the return must directly read an Int field")
    }
    val receiver = receiverExpression.unwrapImplicitCasts() as? IrGetValue ?: return unsupported("the field receiver must be the holder")
    if (receiver.symbol.owner != holder) return unsupported("the field receiver must be the holder")
    val owner = field.parent as? IrClass ?: return unsupported("the field must belong to a class")
    if (owner.modality != Modality.FINAL || holder.type.classOrNull?.owner != owner) {
        return unsupported("the field owner and holder type must be the same final class")
    }
    if (field.isStatic || !field.type.isInt()) return unsupported("the field must be an instance Int")
    if (field.hasAnnotation(KonanFqNames.volatile)) return unsupported("volatile field reads require atomic lowering")
    val offset = fieldOffsetBytes(field) ?: return unsupported("the Native field offset is unavailable")
    if (offset < 0 || offset % Int.SIZE_BYTES != 0L) return unsupported("the Native Int field offset is not aligned")

    fun unitCalls(from: Int, until: Int): List<IrCall>? = buildList {
        for (index in from until until) {
            val call = body.statements[index] as? IrCall ?: return null
            if (invalidUnitFallback(call) || !isAvailableFallback(call.symbol.owner)) return null
            add(call)
        }
    }
    val callsBeforeHolder = unitCalls(0, holderIndex) ?: return unsupported("only Unit calls may precede the holder")
    val callsAfterHolder = unitCalls(holderIndex + 1, body.statements.lastIndex)
        ?: return unsupported("only Unit calls may precede the field read")
    if (callsAfterHolder.isEmpty()) return unsupported("a post-factory Unit call is required")
    val unitCalls = callsBeforeHolder + callsAfterHolder
    val fallbackFunctions = (listOf(holderFactory) + unitCalls.map { it.symbol.owner }).distinct()

    val exportedName = linkerSymbolNamer.linkerName(function)
    val prefix = "kn_field_read_${stableFieldReadHash(exportedName)}"
    val unitNames = unitCalls.map { it.symbol.owner }.distinct()
        .mapIndexed { index, fallback -> fallback to "${prefix}_unit_$index" }
        .toMap()
    val source = buildString {
        appendLine("// Kotlin/Native rooted Int field-read Rust lowering for ${function.name.asString()}")
        appendLine("const ${prefix.uppercase()}_OVERLAY: usize = $frameOverlayWords;")
        appendLine("const ${prefix.uppercase()}_WORDS: usize = ${frameOverlayWords + 1};")
        appendLine("extern \"C-unwind\" {")
        appendLine("    #[link_name = \"${escapeFieldReadString(linkerSymbolNamer.linkerName(holderFactory))}\"]")
        appendLine("    fn ${prefix}_holder(return_slot: *mut KRef) -> KRef;")
        for (entry in unitNames.entries) {
            appendLine("    #[link_name = \"${escapeFieldReadString(linkerSymbolNamer.linkerName(entry.key))}\"]")
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
        appendLine("/// Kotlin/Native must call this function while the current thread is runnable.")
        appendLine("#[export_name = \"${escapeFieldReadString(exportedName)}\"]")
        appendLine("pub unsafe extern \"C-unwind\" fn ${prefix}_entry() -> i32 {")
        appendLine("    unsafe {")
        appendLine("        let mut storage: [KRef; ${prefix.uppercase()}_WORDS] = [core::ptr::null_mut(); ${prefix.uppercase()}_WORDS];")
        appendLine("        let frame = core::ptr::addr_of_mut!(storage).cast::<KRef>();")
        appendLine("        let holder_root = frame.add(${prefix.uppercase()}_OVERLAY);")
        appendLine("        let previous = getCurrentFrame();")
        appendLine("        EnterFrame(frame, 0, ${prefix.uppercase()}_WORDS as i32);")
        appendLine("        let mut frame_guard = ${prefix}_Guard { previous, frame, active: true };")
        appendLine("        Kotlin_mm_safePointFunctionPrologue();")
        callsBeforeHolder.forEach { appendLine("        ${unitNames.getValue(it.symbol.owner)}();") }
        appendLine("        let holder = ${prefix}_holder(holder_root);")
        appendLine("        UpdateStackRef(holder_root, holder);")
        callsAfterHolder.forEach { appendLine("        ${unitNames.getValue(it.symbol.owner)}();") }
        appendLine("        let rooted_holder = core::ptr::read(holder_root);")
        appendLine("        let field_location = rooted_holder.cast::<u8>().add(${offset}usize).cast::<i32>();")
        appendLine("        let result = core::ptr::read(field_location);")
        appendLine("        frame_guard.leave();")
        appendLine("        result")
        appendLine("    }")
        appendLine("}")
    }
    return RustPrimitiveFieldReadCodegenResult.Generated(source, listOf(function), fallbackFunctions)
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

private fun stableFieldReadHash(value: String): String {
    var hash = 0xcbf29ce484222325UL
    value.forEach { hash = (hash xor it.code.toULong()) * 0x100000001b3UL }
    return hash.toString(16)
}

private fun escapeFieldReadString(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
