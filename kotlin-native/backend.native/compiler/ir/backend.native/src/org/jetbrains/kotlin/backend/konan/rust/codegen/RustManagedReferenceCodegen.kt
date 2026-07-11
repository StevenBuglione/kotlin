/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust.codegen

import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.isTopLevel

/**
 * The deliberately narrow managed-reference slice supported by the first Rust GC-root integration.
 *
 * Unsupported shapes are returned to the caller with a reason so hybrid mode can retain the LLVM
 * definition, while strict mode can turn the same result into a source-mapped diagnostic.
 */
internal sealed interface RustManagedReferenceCodegenResult {
    data class Generated(
        val source: String,
        val generatedFunctions: List<IrSimpleFunction>,
        val fallbackFunctions: List<IrSimpleFunction>,
    ) : RustManagedReferenceCodegenResult

    data class Unsupported(
        val function: IrSimpleFunction,
        val reason: String,
    ) : RustManagedReferenceCodegenResult
}

/**
 * Generates exactly this lowered shape:
 *
 *     fun generated(): ManagedReference {
 *         val rooted = llvmManagedFactory()
 *         llvmUnitCall()
 *         // zero or more additional no-argument Unit calls
 *         return rooted
 *     }
 *
 * All Kotlin references in the emitted Rust stay as raw pointers. The only Rust reference is the
 * one required by [Drop.drop] for the frame guard itself; it never aliases a Kotlin object.
 */
internal fun generateRustManagedReferenceFunction(
    function: IrSimpleFunction,
    linkerSymbolNamer: RustLinkerSymbolNamer,
    frameOverlayWords: Int,
    isAvailableFallback: (IrSimpleFunction) -> Boolean = { true },
): RustManagedReferenceCodegenResult {
    fun unsupported(reason: String) = RustManagedReferenceCodegenResult.Unsupported(function, reason)

    if (frameOverlayWords <= 0) return unsupported("the target FrameOverlay size must be positive")
    if (!function.isTopLevel || function.parent !is IrFile) return unsupported("only top-level functions are supported")
    if (function.isExternal) return unsupported("external functions cannot have Rust-generated bodies")
    if (function.isSuspend) return unsupported("suspend functions are not supported by this slice")
    if (function.typeParameters.isNotEmpty()) return unsupported("generic functions are not supported by this slice")
    if (function.parameters.isNotEmpty()) return unsupported("the generated function must have no parameters")
    if (!function.returnType.isManagedReference()) return unsupported("the generated function must return a managed reference")

    val body = function.body as? IrBlockBody ?: return unsupported("the generated function must have a block body")
    if (body.statements.size < 3) {
        return unsupported("expected a rooted local, at least one Unit fallback call, and a return")
    }

    val rootedLocalIndex = body.statements.indexOfFirst { it is IrVariable }
    if (rootedLocalIndex < 0) return unsupported("the function must declare the rooted local")
    val rootedLocal = body.statements[rootedLocalIndex] as IrVariable
    if (rootedLocal.isVar) return unsupported("the rooted local must be immutable")
    if (!rootedLocal.type.isManagedReference()) return unsupported("the rooted local must have managed-reference type")

    val factoryCall = rootedLocal.initializer as? IrCall
        ?: return unsupported("the rooted local initializer must be a direct call")
    val factory = factoryCall.symbol.owner
    val invalidFactory = validateFallbackCall(factoryCall, expectedUnitResult = false)
    if (invalidFactory != null) return unsupported("managed-return fallback call $invalidFactory")
    if (!isAvailableFallback(factory)) return unsupported("managed-return fallback has no Native LLVM definition")
    if (factory == function) return unsupported("the managed-return fallback cannot recursively call the generated function")

    val returnExpression = body.statements.lastOrNull() as? IrReturn
        ?: return unsupported("the last statement must return the rooted local")
    if (returnExpression.returnTargetSymbol.owner != function) {
        return unsupported("the return must target the generated function")
    }
    val returnedValue = returnExpression.value as? IrGetValue
        ?: return unsupported("the returned expression must be the rooted local")
    if (returnedValue.symbol.owner != rootedLocal) return unsupported("the returned value must be the rooted local")

    fun collectUnitCalls(statements: List<org.jetbrains.kotlin.ir.IrStatement>): List<IrCall>? {
        val result = ArrayList<IrCall>(statements.size)
        for (statement in statements) {
            val call = statement as? IrCall ?: return null
            val invalidCall = validateFallbackCall(call, expectedUnitResult = true)
            if (invalidCall != null || call.symbol.owner == function || !isAvailableFallback(call.symbol.owner)) return null
            result += call
        }
        return result
    }
    val callsBeforeFactory = collectUnitCalls(body.statements.subList(0, rootedLocalIndex))
        ?: return unsupported("statements before the rooted local must be direct Unit fallback calls")
    val callsAfterFactory = ArrayList<IrCall>(body.statements.size - rootedLocalIndex - 2)
    for (statement in body.statements.subList(rootedLocalIndex + 1, body.statements.lastIndex)) {
        val call = statement as? IrCall
            ?: return unsupported("statements between the rooted local and return must be direct Unit calls")
        val invalidCall = validateFallbackCall(call, expectedUnitResult = true)
        if (invalidCall != null) return unsupported("Unit fallback call $invalidCall")
        if (!isAvailableFallback(call.symbol.owner)) return unsupported("Unit fallback call has no Native LLVM definition")
        if (call.symbol.owner == function) return unsupported("a Unit fallback cannot recursively call the generated function")
        callsAfterFactory += call
    }
    val unitCalls = callsBeforeFactory + callsAfterFactory
    if (unitCalls.isEmpty()) return unsupported("at least one Unit fallback call is required")

    val fallbackFunctions = buildList {
        add(factory)
        unitCalls.mapTo(this) { it.symbol.owner }
    }.distinct()
    val exportedLinkerName = linkerSymbolNamer.linkerName(function)
    val privatePrefix = "kn_mref_${stableHash(exportedLinkerName)}"
    val factoryRustName = "${privatePrefix}_factory"
    val unitRustNames = fallbackFunctions.drop(1).mapIndexed { index, fallback ->
        fallback to "${privatePrefix}_unit_$index"
    }.toMap()

    val source = buildString {
        appendLine("// Kotlin/Native managed-reference Rust lowering for ${function.name.asString()}")
        appendLine("const ${privatePrefix.uppercase()}_FRAME_OVERLAY_WORDS: usize = $frameOverlayWords;")
        appendLine("const ${privatePrefix.uppercase()}_FRAME_WORDS: usize = ${frameOverlayWords + 1};")
        appendLine()
        appendLine("extern \"C-unwind\" {")
        appendLine("    #[link_name = \"${escapeRustString(linkerSymbolNamer.linkerName(factory))}\"]")
        appendLine("    fn $factoryRustName(return_slot: *mut KRef) -> KRef;")
        for (entry in unitRustNames.entries) {
            appendLine("    #[link_name = \"${escapeRustString(linkerSymbolNamer.linkerName(entry.key))}\"]")
            appendLine("    fn ${entry.value}();")
        }
        appendLine("}")
        appendLine()
        appendLine("#[allow(non_camel_case_types)]")
        appendLine("struct ${privatePrefix}_FrameGuard {")
        appendLine("    previous: *mut KRef,")
        appendLine("    frame: *mut KRef,")
        appendLine("    active: bool,")
        appendLine("}")
        appendLine()
        appendLine("impl ${privatePrefix}_FrameGuard {")
        appendLine("    fn leave(&mut self) {")
        appendLine("        if self.active {")
        appendLine("            unsafe { LeaveFrame(self.frame, 0, ${privatePrefix.uppercase()}_FRAME_WORDS as i32); }")
        appendLine("            self.active = false;")
        appendLine("        }")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("impl Drop for ${privatePrefix}_FrameGuard {")
        appendLine("    fn drop(&mut self) {")
        appendLine("        if self.active {")
        appendLine("            unsafe { SetCurrentFrame(self.previous); }")
        appendLine("            self.active = false;")
        appendLine("        }")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("/// # Safety")
        appendLine("/// `return_slot` must be a valid Kotlin/Native managed-reference result slot.")
        appendLine("#[export_name = \"${escapeRustString(exportedLinkerName)}\"]")
        appendLine("pub unsafe extern \"C-unwind\" fn ${privatePrefix}_entry(return_slot: *mut KRef) -> KRef {")
        appendLine("    unsafe {")
        appendLine("        let mut frame_storage: [KRef; ${privatePrefix.uppercase()}_FRAME_WORDS] =")
        appendLine("            [core::ptr::null_mut(); ${privatePrefix.uppercase()}_FRAME_WORDS];")
        appendLine("        let frame = core::ptr::addr_of_mut!(frame_storage).cast::<KRef>();")
        appendLine("        let root = frame.add(${privatePrefix.uppercase()}_FRAME_OVERLAY_WORDS);")
        appendLine("        let previous = getCurrentFrame();")
        appendLine("        EnterFrame(frame, 0, ${privatePrefix.uppercase()}_FRAME_WORDS as i32);")
        appendLine("        let mut frame_guard = ${privatePrefix}_FrameGuard { previous, frame, active: true };")
        appendLine("        Kotlin_mm_safePointFunctionPrologue();")
        for (call in callsBeforeFactory) {
            appendLine("        ${unitRustNames.getValue(call.symbol.owner)}();")
        }
        appendLine("        let created = $factoryRustName(root);")
        appendLine("        UpdateStackRef(root, created);")
        for (call in callsAfterFactory) {
            appendLine("        ${unitRustNames.getValue(call.symbol.owner)}();")
        }
        appendLine("        let result = core::ptr::read(root);")
        appendLine("        UpdateReturnRef(return_slot, result);")
        appendLine("        frame_guard.leave();")
        appendLine("        result")
        appendLine("    }")
        appendLine("}")
    }

    return RustManagedReferenceCodegenResult.Generated(
        source = source,
        generatedFunctions = listOf(function),
        fallbackFunctions = fallbackFunctions,
    )
}

internal fun rustManagedReferenceRuntimePrelude(): String = """
    pub type KRef = *mut core::ffi::c_void;

    #[allow(non_snake_case)]
    extern "C" {
        fn EnterFrame(frame: *mut KRef, parameters: i32, count: i32);
        fn LeaveFrame(frame: *mut KRef, parameters: i32, count: i32);
        fn UpdateStackRef(location: *mut KRef, value: KRef);
        fn UpdateHeapRef(location: *mut KRef, value: KRef);
        fn UpdateReturnRef(location: *mut KRef, value: KRef);
        fn getCurrentFrame() -> *mut KRef;
        fn SetCurrentFrame(frame: *mut KRef);
        fn Kotlin_mm_safePointFunctionPrologue();
    }
""".trimIndent()

private fun validateFallbackCall(call: IrCall, expectedUnitResult: Boolean): String? {
    val callee = call.symbol.owner
    if (!callee.isTopLevel || callee.parent !is IrFile) return "must target a top-level function"
    if (callee.isExternal) return "must target an LLVM-defined Kotlin function"
    if (callee.isSuspend) return "cannot target a suspend function"
    if (callee.typeParameters.isNotEmpty()) return "cannot target a generic function"
    if (callee.parameters.isNotEmpty() || call.arguments.any { it != null }) return "must have no arguments"
    if (expectedUnitResult && !callee.returnType.isUnit()) return "must return Unit"
    if (!expectedUnitResult && !callee.returnType.isManagedReference()) return "must return a managed reference"
    return null
}

private fun org.jetbrains.kotlin.ir.types.IrType.isManagedReference(): Boolean =
    binaryTypeIsReference() && !isUnit() && !isNothing()

private fun stableHash(value: String): String {
    var hash = 0xcbf29ce484222325UL
    for (character in value) {
        hash = (hash xor character.code.toULong()) * 0x100000001b3UL
    }
    return hash.toString(16)
}

private fun escapeRustString(value: String): String = buildString(value.length) {
    for (character in value) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}
