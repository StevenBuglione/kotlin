/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust.codegen

import org.jetbrains.kotlin.backend.konan.ir.isUnbox
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrDoWhileLoop
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrInlinedFunctionBlock
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrReturnableBlock
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isByte
import org.jetbrains.kotlin.ir.types.isChar
import org.jetbrains.kotlin.ir.types.isDouble
import org.jetbrains.kotlin.ir.types.isFloat
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isShort
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.isUByte
import org.jetbrains.kotlin.ir.types.isUInt
import org.jetbrains.kotlin.ir.types.isULong
import org.jetbrains.kotlin.ir.types.isUShort
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isTopLevel
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import java.lang.Long.toUnsignedString
import java.util.ArrayDeque
import java.util.IdentityHashMap

/**
 * Lowers the deliberately small Wave 1 subset of lowered Kotlin IR to a deterministic Rust module.
 *
 * A body is emitted atomically: encountering one unsupported node moves that function to
 * [RustCodegenResult.fallbackFunctions]. This lets hybrid mode import the existing LLVM body while
 * strict mode reports the exact same structured diagnostic.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class RustIrCodegen(
    private val linkerSymbolNamer: RustLinkerSymbolNamer = RustLinkerSymbolNamer(::defaultLinkerName),
    private val allowRustStandardIo: Boolean = true,
    private val functionPrologue: String? = null,
    private val directInteropCallResolver: RustDirectInteropCallResolver = RustDirectInteropCallResolver.NONE,
) {
    fun generate(module: IrModuleFragment): RustCodegenResult =
        generate(module, collectTopLevelFunctions(module))

    fun generate(
        module: IrModuleFragment,
        entryPoints: Collection<IrSimpleFunction>,
        moduleFunctionScope: Collection<IrSimpleFunction>? = null,
    ): RustCodegenResult {
        val allModuleFunctions = collectTopLevelFunctions(module).toSet()
        val moduleFunctions = moduleFunctionScope?.filterTo(linkedSetOf()) { it in allModuleFunctions }
            ?: allModuleFunctions
        val diagnostics = mutableListOf<RustUnsupportedDiagnostic>()
        val validEntries = entryPoints.filterTo(linkedSetOf()) { entry ->
            if (entry in moduleFunctions) {
                true
            } else {
                diagnostics += diagnostic(
                    entry,
                    entry,
                    RustUnsupportedCode.INVALID_ENTRY_POINT,
                    "Entry point is not a top-level function in the supplied module",
                )
                false
            }
        }
        val reachableFunctions = collectReachableFunctions(validEntries, moduleFunctions, directInteropCallResolver)
            .sortedBy(::functionKey)
        val names = reachableFunctions.associateWith { function ->
            val rustName = defaultRustName(function)
            RustGeneratedFunction(function, rustName, linkerSymbolNamer.linkerName(function))
        }

        val generatedBodies = linkedMapOf<IrSimpleFunction, String>()
        val fallbackFunctions = linkedSetOf<IrSimpleFunction>()

        for (function in reachableFunctions) {
            try {
                validateSignature(function)
                generatedBodies[function] = FunctionRenderer(
                    function,
                    moduleFunctions,
                    names,
                    emptySet(),
                    allowRustStandardIo,
                    functionPrologue,
                    directInteropCallResolver,
                ).render()
            } catch (unsupported: UnsupportedIr) {
                diagnostics += diagnostic(function, unsupported.element, unsupported.code, unsupported.message.orEmpty())
                if (hasSupportedSignature(function)) fallbackFunctions += function
            }
        }

        // If a supported body calls a declaration whose signature cannot cross the Rust ABI at all,
        // move the caller to fallback as well instead of returning Rust that cannot type-check.
        var changed: Boolean
        do {
            changed = false
            for (function in generatedBodies.keys.toList()) {
                val invalidCallee = directModuleCalls(function, moduleFunctions, directInteropCallResolver).firstOrNull {
                    it !in generatedBodies && it !in fallbackFunctions
                } ?: continue
                generatedBodies.remove(function)
                if (hasSupportedSignature(function)) fallbackFunctions += function
                diagnostics += diagnostic(
                    function,
                    invalidCallee,
                    RustUnsupportedCode.UNSUPPORTED_CALL,
                    "Call target ${displayName(invalidCallee)} has no Rust-compatible ABI signature",
                )
                changed = true
            }
        } while (changed)

        val finalizedBodies = generatedBodies.keys.associateWith { function ->
            FunctionRenderer(
                function,
                moduleFunctions,
                names,
                fallbackFunctions,
                allowRustStandardIo,
                functionPrologue,
                directInteropCallResolver,
            ).render()
        }
        val generated = finalizedBodies.keys.map { names.getValue(it) }
        val fallbacks = fallbackFunctions.sortedBy(::functionKey).map { names.getValue(it) }
        return RustCodegenResult(
            source = renderModule(finalizedBodies, fallbacks),
            generatedFunctions = generated,
            fallbackFunctions = fallbacks,
            diagnostics = diagnostics.distinctBy { listOf(it.functionName, it.code, it.location.startOffset, it.message) },
        )
    }

    private fun renderModule(
        generatedBodies: Map<IrSimpleFunction, String>,
        fallbacks: List<RustGeneratedFunction>,
    ): String = buildString {
        appendLine("// Generated by the Kotlin/Native experimental Rust backend. DO NOT EDIT.")
        appendLine("#![allow(")
        appendLine("    dead_code,")
        appendLine("    non_snake_case,")
        appendLine("    unused_mut,")
        appendLine("    unused_parens,")
        appendLine("    unused_variables,")
        appendLine("    clippy::needless_return,")
        appendLine("    clippy::no_effect,")
        appendLine("    clippy::unnecessary_operation,")
        appendLine("    clippy::unused_unit,")
        appendLine(")]")
        appendLine()
        if (fallbacks.isNotEmpty()) {
            appendLine("extern \"C-unwind\" {")
            for (fallback in fallbacks) {
                val function = fallback.declaration
                append("    #[link_name = \"")
                append(escapeRustString(fallback.linkerName))
                appendLine("\"]")
                append("    fn ")
                append(fallback.rustName)
                append("__llvm")
                append('(')
                append(renderParameters(function))
                append(')')
                append(renderReturnType(function.returnType))
                appendLine(";")
            }
            appendLine("}")
            appendLine()
        }
        for (entry in generatedBodies) {
            val function = entry.key
            val body = entry.value
            val generated = RustGeneratedFunction(function, defaultRustName(function), linkerSymbolNamer.linkerName(function))
            // rustc can merge signed and unsigned narrow-return functions before Kotlin/Native's
            // return extension attributes are normalized. Keep the zero-extended class distinct;
            // after linkage the ABI attributes themselves prevent unsafe cross-class merging.
            if (function.returnType.requiresNarrowUnsignedMergeBarrier()) appendLine("#[inline(never)]")
            append("#[export_name = \"")
            append(escapeRustString(generated.linkerName))
            appendLine("\"]")
            append("pub extern \"C-unwind\" fn ")
            append(generated.rustName)
            append('(')
            append(renderParameters(function))
            append(')')
            append(renderReturnType(function.returnType))
            append(' ')
            appendLine(body)
            appendLine()
        }
    }.trimEnd() + "\n"

    private class FunctionRenderer(
        private val function: IrSimpleFunction,
        private val moduleFunctions: Set<IrSimpleFunction>,
        private val functionNames: Map<IrSimpleFunction, RustGeneratedFunction>,
        private val fallbackFunctions: Set<IrSimpleFunction>,
        private val allowRustStandardIo: Boolean,
        private val functionPrologue: String?,
        private val directInteropCallResolver: RustDirectInteropCallResolver,
    ) {
        private val valueNames = IdentityHashMap<IrValueDeclaration, String>()
        private val loopNames = IdentityHashMap<IrLoop, String>()
        private val doWhileContinueNames = IdentityHashMap<IrLoop, String>()
        private val returnableBlockNames = IdentityHashMap<IrReturnableBlock, String>()
        private var nextValueIndex = 0
        private var nextLoopIndex = 0
        private var nextReturnableBlockIndex = 0

        init {
            function.parameters.forEach { valueName(it) }
        }

        fun render(): String {
            val body = function.body ?: unsupported(function, RustUnsupportedCode.MISSING_BODY, "Function has no body")
            return when (body) {
                is IrBlockBody -> buildString {
                    appendLine("{")
                    functionPrologue?.let { appendLine(indent(it)) }
                    body.statements.forEach { appendLine(indent(statement(it))) }
                    append('}')
                }
                is IrExpressionBody -> buildString {
                    appendLine("{")
                    functionPrologue?.let { appendLine(indent(it)) }
                    appendLine(indent(expression(body.expression)))
                    append('}')
                }
                else -> unsupported(body, RustUnsupportedCode.UNSUPPORTED_DECLARATION, "Unsupported function body ${body::class.simpleName}")
            }
        }

        private fun statement(statement: IrStatement): String = when (statement) {
            is IrVariable -> renderVariable(statement)
            is IrSetValue -> "${valueName(statement.symbol.owner)} = ${expression(statement.value)};"
            is IrReturn -> "${renderReturn(statement)};"
            is IrWhileLoop -> renderWhile(statement)
            is IrDoWhileLoop -> renderDoWhile(statement)
            is IrBreak -> "${renderBreak(statement)};"
            is IrContinue -> "${renderContinue(statement)};"
            is IrBlock -> "${expression(statement)};"
            is IrComposite -> "${expression(statement)};"
            is IrExpression -> "${expression(statement)};"
            else -> unsupported(statement, RustUnsupportedCode.UNSUPPORTED_DECLARATION, "Unsupported statement ${statement::class.simpleName}")
        }

        private fun renderVariable(variable: IrVariable): String {
            val type = rustType(variable.type, variable)
            val initializer = variable.initializer?.let { " = ${expression(it)}" }.orEmpty()
            return "let mut ${valueName(variable)}: $type$initializer;"
        }

        private fun expression(expression: IrExpression): String = when (expression) {
            is IrConst -> renderConst(expression)
            is IrGetValue -> valueNames[expression.symbol.owner]
                ?: unsupported(expression, RustUnsupportedCode.MALFORMED_IR, "Value is read outside its declaring function")
            is IrSetValue -> "{ ${valueName(expression.symbol.owner)} = ${expression(expression.value)}; () }"
            is IrCall -> renderCall(expression)
            is IrGetField -> renderPrimitiveCarrierFieldRead(expression)
            is IrWhen -> renderWhen(expression)
            is IrReturnableBlock -> renderReturnableBlock(expression)
            is IrBlock -> renderContainer(expression.statements, expression.type)
            is IrComposite -> renderContainer(expression.statements, expression.type)
            is IrReturn -> renderReturn(expression)
            is IrBreak -> renderBreak(expression)
            is IrContinue -> renderContinue(expression)
            is IrWhileLoop -> "{\n${indent(renderWhile(expression))}\n    ()\n}"
            is IrDoWhileLoop -> "{\n${indent(renderDoWhile(expression))}\n    ()\n}"
            is IrGetObjectValue -> if (expression.type.isUnit()) "()" else {
                unsupported(expression, RustUnsupportedCode.UNSUPPORTED_EXPRESSION, "Only the kotlin.Unit object is supported")
            }
            is IrTypeOperatorCall -> renderTypeOperator(expression)
            else -> unsupported(expression, RustUnsupportedCode.UNSUPPORTED_EXPRESSION, "Unsupported expression ${expression::class.simpleName}")
        }

        private fun renderContainer(statements: List<IrStatement>, type: IrType): String = buildString {
            appendLine("{")
            if (statements.isEmpty()) {
                appendLine("    ()")
            } else if (type.isUnit()) {
                statements.forEach { appendLine(indent(statement(it))) }
                appendLine("    ()")
            } else {
                statements.dropLast(1).forEach { appendLine(indent(statement(it))) }
                val result = statements.last() as? IrExpression
                    ?: unsupported(statements.last(), RustUnsupportedCode.MALFORMED_IR, "Value-producing block ends in a declaration")
                appendLine(indent(expression(result)))
            }
            append('}')
        }

        private fun renderWhen(irWhen: IrWhen): String {
            if (irWhen.branches.isEmpty()) {
                if (irWhen.type.isUnit()) return "()"
                unsupported(irWhen, RustUnsupportedCode.MALFORMED_IR, "Value-producing when has no branches")
            }
            val hasElse = irWhen.branches.last().condition.isTrueConstant()
            if (!hasElse && !irWhen.type.isUnit()) {
                unsupported(irWhen, RustUnsupportedCode.MALFORMED_IR, "Value-producing when has no else branch")
            }
            return buildString {
                appendLine("{")
                irWhen.branches.forEachIndexed { index, branch ->
                    val isElse = index == irWhen.branches.lastIndex && branch.condition.isTrueConstant()
                    when {
                        index == 0 && !isElse -> append("    if ${expression(branch.condition)} ")
                        index > 0 && !isElse -> append(" else if ${expression(branch.condition)} ")
                        index == 0 -> Unit
                        else -> append(" else ")
                    }
                    append(renderBranch(branch.result))
                }
                if (!hasElse) append(" else { () }")
                appendLine()
                append('}')
            }
        }

        private fun renderBranch(result: IrExpression): String = when (result) {
            is IrBlock -> expression(result)
            else -> "{ ${expression(result)} }"
        }

        private fun renderWhile(loop: IrWhileLoop): String {
            val label = loopLabel(loop)
            val body = loop.body?.let(::statement) ?: "() ;"
            return "'$label: while ${expression(loop.condition)} {\n${indent(body)}\n}"
        }

        private fun renderDoWhile(loop: IrDoWhileLoop): String {
            val label = loopLabel(loop)
            val continueLabel = doWhileContinueLabel(loop)
            val body = loop.body?.let(::statement).orEmpty()
            return buildString {
                appendLine("'$label: loop {")
                appendLine("    '$continueLabel: {")
                if (body.isNotEmpty()) appendLine(indent(body, 8))
                appendLine("    }")
                appendLine("    if !(${expression(loop.condition)}) { break '$label; }")
                append('}')
            }
        }

        private fun renderBreak(irBreak: IrBreak): String {
            val target = irBreak.loop
            if (target !in loopNames) {
                unsupported(irBreak, RustUnsupportedCode.UNSUPPORTED_LOOP_TARGET, "Break target is not an enclosing loop")
            }
            return "break '${loopLabel(target)}"
        }

        private fun renderContinue(irContinue: IrContinue): String {
            val target = irContinue.loop
            if (target !in loopNames) {
                unsupported(irContinue, RustUnsupportedCode.UNSUPPORTED_LOOP_TARGET, "Continue target is not an enclosing loop")
            }
            return if (target is IrDoWhileLoop) {
                "break '${doWhileContinueLabel(target)}"
            } else {
                "continue '${loopLabel(target)}"
            }
        }

        private fun renderReturn(irReturn: IrReturn): String {
            val target = irReturn.returnTargetSymbol.owner
            if (target is IrReturnableBlock) {
                val label = returnableBlockNames[target]
                    ?: unsupported(irReturn, RustUnsupportedCode.UNSUPPORTED_RETURN_TARGET, "Returnable block is not an enclosing expression")
                return if (target.type.isUnit()) {
                    "break '$label { ${expression(irReturn.value)}; () }"
                } else {
                    val renderedValue = expression(irReturn.value)
                    val valueType = rustType(irReturn.value.type, irReturn)
                    val targetType = rustType(target.type, target)
                    val adaptedValue = when {
                        valueType == targetType -> renderedValue
                        irReturn.value.type.isSupportedInteger() && target.type.isSupportedInteger() -> "$renderedValue as $targetType"
                        else -> unsupported(
                            irReturn,
                            RustUnsupportedCode.UNSUPPORTED_RETURN_TARGET,
                            "Returnable block cannot adapt $valueType to $targetType",
                        )
                    }
                    "break '$label ($adaptedValue)"
                }
            }
            if (target !== function) {
                unsupported(irReturn, RustUnsupportedCode.UNSUPPORTED_RETURN_TARGET, "Only returns from the current function or an enclosing returnable block are supported")
            }
            return if (function.returnType.isUnit()) {
                // Unit is still a value-producing expression in IR and may carry side effects.
                "{ ${expression(irReturn.value)}; return; }"
            } else {
                "return ${expression(irReturn.value)}"
            }
        }

        private fun renderReturnableBlock(block: IrReturnableBlock): String {
            if (block in returnableBlockNames) {
                unsupported(block, RustUnsupportedCode.MALFORMED_IR, "Returnable block is recursively nested in itself")
            }
            val label = "block_${nextReturnableBlockIndex++}"
            returnableBlockNames[block] = label
            return try {
                "'$label: ${renderContainer(block.statements, block.type)}"
            } finally {
                returnableBlockNames.remove(block)
            }
        }

        private fun renderPrimitiveCarrierFieldRead(read: IrGetField): String {
            if (!read.type.isSupportedPrimitive() || read.symbol.owner.type != read.type) {
                unsupported(read, RustUnsupportedCode.UNSUPPORTED_EXPRESSION, "Unsupported field read")
            }
            val fieldName = read.symbol.owner.fqNameWhenAvailable?.asString()
            if (fieldName !in PRIMITIVE_CARRIER_FIELD_NAMES) {
                unsupported(read, RustUnsupportedCode.UNSUPPORTED_EXPRESSION, "Unsupported field read $fieldName")
            }
            val carrier = read.receiver as? IrInlinedFunctionBlock
                ?: unsupported(read, RustUnsupportedCode.UNSUPPORTED_EXPRESSION, "Primitive carrier field requires an inlined block")
            if (!carrier.type.isNothing()) {
                unsupported(read, RustUnsupportedCode.UNSUPPORTED_EXPRESSION, "Primitive carrier field requires a Nothing-typed inlined block")
            }
            return expression(carrier)
        }

        private fun renderTypeOperator(call: IrTypeOperatorCall): String = when (call.operator) {
            IrTypeOperator.IMPLICIT_CAST, IrTypeOperator.IMPLICIT_NOTNULL -> {
                val from = rustType(call.argument.type, call)
                val to = rustType(call.typeOperand, call)
                if (from != to) unsupported(call, RustUnsupportedCode.UNSUPPORTED_EXPRESSION, "Primitive cast from $from to $to is not implicit in Rust")
                expression(call.argument)
            }
            IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> "{ ${expression(call.argument)}; () }"
            else -> unsupported(call, RustUnsupportedCode.UNSUPPORTED_EXPRESSION, "Unsupported type operator ${call.operator}")
        }

        private fun renderCall(call: IrCall): String {
            val callee = call.symbol.owner
            val arguments = call.arguments.map { argument ->
                argument ?: unsupported(call, RustUnsupportedCode.MALFORMED_IR, "Call to ${displayName(callee)} has a missing argument")
            }
            renderPrintln(callee, arguments)?.let { return it }
            renderPrimitiveConversion(callee, arguments, call)?.let { return it }
            renderPrimitiveReinterpret(callee, arguments, call)?.let { return it }
            renderUnsignedPrimitiveRepresentation(callee, arguments, call)?.let { return it }
            renderPrimitiveOperator(callee, arguments, call)?.let { return it }
            primitiveCarrierArgument(call)?.let { return expression(it) }
            if (isUnitSingleton(callee)) return "()"
            directInteropCallResolver.resolve(callee)?.let { directCall ->
                val renderedArguments = arguments.joinToString { expression(it) }
                return when (directCall.panicPolicy) {
                    RustDirectInteropPanicPolicy.ABORT -> buildString {
                        append("match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| ")
                        append(directCall.rustPath).append('(').append(renderedArguments).appendLine("))) {")
                        appendLine("    Ok(value) => value,")
                        appendLine("    Err(_) => std::process::abort(),")
                        append('}')
                    }
                }
            }
            if (callee in moduleFunctions) {
                validateSignature(callee)
                val target = functionNames[callee]
                    ?: unsupported(call, RustUnsupportedCode.MALFORMED_IR, "Reachable call target has no assigned Rust name")
                val renderedArguments = arguments.joinToString { expression(it) }
                return if (callee in fallbackFunctions) {
                    "unsafe { ${target.rustName}__llvm($renderedArguments) }"
                } else {
                    "${target.rustName}($renderedArguments)"
                }
            }
            unsupported(call, RustUnsupportedCode.UNSUPPORTED_CALL, "Unsupported call to ${displayName(callee)}")
        }

        private fun renderPrintln(callee: IrSimpleFunction, arguments: List<IrExpression>): String? {
            if (callee.name.asString() != "println") return null
            val fqName = callee.fqNameWhenAvailable?.asString()
            if (fqName != "kotlin.io.println") return null
            if (!allowRustStandardIo) {
                unsupported(callee, RustUnsupportedCode.UNSUPPORTED_CALL, "println requires the Kotlin/Native runtime bridge")
            }
            if (arguments.isEmpty()) return "println!()"
            if (arguments.size != 1) return null
            val argument = unwrapBoxForPrint(arguments.single())
            if (argument.type.isString() && argument !is IrConst) {
                unsupported(argument, RustUnsupportedCode.UNSUPPORTED_EXPRESSION, "Only string literals are supported by println")
            }
            if (!argument.type.isSupportedPrintType()) {
                unsupported(argument, RustUnsupportedCode.UNSUPPORTED_TYPE, "println supports only primitive values and string literals")
            }
            return "println!(\"{}\", ${expression(argument)})"
        }

        private fun renderPrimitiveConversion(
            callee: IrSimpleFunction,
            arguments: List<IrExpression>,
            call: IrCall,
        ): String? {
            val conversion = callee.name.asString()
            if (conversion !in INTEGER_CONVERSION_NAMES) return null
            val fqName = callee.fqNameWhenAvailable?.asString()
            if (fqName != null && !fqName.startsWith("kotlin.")) return null
            val value = arguments.singleOrNull()
                ?: unsupported(call, RustUnsupportedCode.UNSUPPORTED_CALL, "Malformed primitive conversion call ${displayName(callee)}")
            if (!value.type.isSupportedInteger() || !call.type.isSupportedInteger()) {
                unsupported(
                    call,
                    RustUnsupportedCode.UNSUPPORTED_CALL,
                    "Primitive conversion ${displayName(callee)} requires unsupported numeric conversion semantics",
                )
            }
            val renderedValue = expression(value)
            val sourceType = rustType(value.type, call)
            val targetType = rustType(call.type, call)
            return if (sourceType == targetType) renderedValue else "$renderedValue as $targetType"
        }

        private fun renderUnsignedPrimitiveRepresentation(
            callee: IrSimpleFunction,
            arguments: List<IrExpression>,
            call: IrCall,
        ): String? {
            val fqName = callee.fqNameWhenAvailable?.asString() ?: return null
            val argument = arguments.singleOrNull()?.takeIf { it.type.isSupportedInteger() } ?: return null
            return when {
                fqName in UNSIGNED_PRIMITIVE_CONSTRUCTORS && callee.returnType.isUnit() && call.type.isUnit() -> {
                    if (callee.parameters.singleOrNull()?.type?.isSupportedInteger() != true) return null
                    "{ let _ = ${expression(argument)}; () }"
                }
                fqName in UNSIGNED_PRIMITIVE_GETTERS && callee.returnType.isSupportedInteger() && call.type.isSupportedInteger() -> {
                    val rendered = expression(argument)
                    val sourceType = rustType(argument.type, argument)
                    val targetType = rustType(call.type, call)
                    if (sourceType == targetType) rendered else "$rendered as $targetType"
                }
                else -> null
            }
        }

        private fun renderPrimitiveReinterpret(
            callee: IrSimpleFunction,
            arguments: List<IrExpression>,
            call: IrCall,
        ): String? {
            if (callee.fqNameWhenAvailable?.asString() != "kotlin.native.internal.reinterpret") return null
            val argument = arguments.singleOrNull() ?: return null
            val sourceWidth = argument.type.supportedIntegerBitWidth() ?: return null
            val targetWidth = call.type.supportedIntegerBitWidth() ?: return null
            if (sourceWidth != targetWidth) {
                unsupported(call, RustUnsupportedCode.UNSUPPORTED_CALL, "Primitive reinterpret requires equal-width integer carriers")
            }
            val rendered = expression(argument)
            val sourceType = rustType(argument.type, argument)
            val targetType = rustType(call.type, call)
            return if (sourceType == targetType) rendered else "$rendered as $targetType"
        }

        private fun unwrapBoxForPrint(argument: IrExpression): IrExpression {
            val boxCall = argument as? IrCall ?: return argument
            val calleeName = boxCall.symbol.owner.name.asString()
            if (!calleeName.endsWith("-box>")) return argument
            val unboxed = boxCall.arguments.singleOrNull() ?: return argument
            return unboxed.takeIf { it.type.isSupportedPrimitive() } ?: argument
        }

        private fun renderPrimitiveOperator(
            callee: IrSimpleFunction,
            arguments: List<IrExpression>,
            call: IrCall,
        ): String? {
            val name = callee.name.asString()
            if (name !in PRIMITIVE_OPERATOR_NAMES) return null
            val fqName = callee.fqNameWhenAvailable?.asString()
            if (fqName != null && !fqName.startsWith("kotlin.")) return null
            if (arguments.isEmpty()) return null
            val lhs = arguments[0]
            if (!lhs.type.isSupportedPrimitive()) return null
            fun unary(render: (String) -> String): String? =
                if (arguments.size == 1) render(expression(lhs)) else null
            fun binary(requireSameType: Boolean = true, render: (String, String) -> String): String? =
                if (arguments.size == 2 && arguments[1].type.isSupportedPrimitive() &&
                    (!requireSameType || rustType(lhs.type, call) == rustType(arguments[1].type, call))
                ) {
                    render(expression(lhs), expression(arguments[1]))
                } else null
            fun coerceInteger(value: IrExpression, rendered: String, targetRustType: String): String =
                if (rustType(value.type, call) == targetRustType) rendered else "$rendered as $targetRustType"
            fun integerUnary(render: (String) -> String): String? {
                if (arguments.size != 1 || !lhs.type.isSupportedInteger() || !call.type.isSupportedInteger()) return null
                val targetType = rustType(call.type, call)
                return render(coerceInteger(lhs, expression(lhs), targetType))
            }
            fun integerBinary(render: (String, String) -> String): String? {
                val rhs = arguments.getOrNull(1) ?: return null
                if (arguments.size != 2 || !lhs.type.isSupportedInteger() || !rhs.type.isSupportedInteger() ||
                    !call.type.isSupportedInteger()
                ) return null
                val targetType = rustType(call.type, call)
                return render(
                    coerceInteger(lhs, expression(lhs), targetType),
                    coerceInteger(rhs, expression(rhs), targetType),
                )
            }
            fun integerShift(render: (String, String) -> String): String? {
                val rhs = arguments.getOrNull(1) ?: return null
                if (arguments.size != 2 || !lhs.type.isSupportedInteger() || !call.type.isSupportedInteger() || !rhs.type.isInt()) {
                    return null
                }
                val targetType = rustType(call.type, call)
                return render(coerceInteger(lhs, expression(lhs), targetType), expression(rhs))
            }
            val integer = lhs.type.isSupportedInteger()
            return when (name) {
                "plus" -> if (integer) integerBinary { a, b -> "($a).wrapping_add($b)" } else binary { a, b -> "($a + $b)" }
                "minus" -> if (integer) integerBinary { a, b -> "($a).wrapping_sub($b)" } else binary { a, b -> "($a - $b)" }
                "times" -> if (integer) integerBinary { a, b -> "($a).wrapping_mul($b)" } else binary { a, b -> "($a * $b)" }
                // Integer division by zero must throw Kotlin's ArithmeticException, so it cannot be
                // emitted for a zero or non-constant divisor until the exception ABI is available.
                "div" -> if (integer) {
                    if (arguments.size == 2 && arguments[1].type.isSupportedInteger() && call.type.isSupportedInteger()) {
                        if (!arguments[1].isNonZeroIntegerConstant()) {
                            unsupported(call, RustUnsupportedCode.UNSUPPORTED_CALL, "Integer division requires Kotlin exception interop")
                        }
                        integerBinary { a, b -> "($a).wrapping_div($b)" }
                    } else null
                } else {
                    binary { a, b -> "($a / $b)" }
                }
                "rem", "mod" -> if (integer) {
                    if (arguments.size == 2 && arguments[1].type.isSupportedInteger() && call.type.isSupportedInteger()) {
                        if (!arguments[1].isNonZeroIntegerConstant()) {
                            unsupported(call, RustUnsupportedCode.UNSUPPORTED_CALL, "Integer remainder requires Kotlin exception interop")
                        }
                        integerBinary { a, b -> "($a).wrapping_rem($b)" }
                    } else null
                } else {
                    binary { a, b -> "($a % $b)" }
                }
                "unaryMinus" -> if (integer) integerUnary { value -> "($value).wrapping_neg()" } else unary { value -> "(-$value)" }
                "unaryPlus" -> if (integer) integerUnary { it } else unary { it }
                "inc" -> if (integer) integerUnary { value -> "($value).wrapping_add(1)" } else unary { value -> "($value + 1.0)" }
                "dec" -> if (integer) integerUnary { value -> "($value).wrapping_sub(1)" } else unary { value -> "($value - 1.0)" }
                "and" -> if (integer) integerBinary { a, b -> "($a & $b)" } else binary { a, b -> "($a & $b)" }
                "or" -> if (integer) integerBinary { a, b -> "($a | $b)" } else binary { a, b -> "($a | $b)" }
                "xor" -> if (integer) integerBinary { a, b -> "($a ^ $b)" } else binary { a, b -> "($a ^ $b)" }
                "not" -> if (integer) integerUnary { value -> "(!$value)" } else unary { value -> "(!$value)" }
                "shl" -> if (integer && arguments.getOrNull(1)?.type?.isInt() == true) {
                    integerShift { a, b -> "($a).wrapping_shl($b as u32)" }
                } else null
                "shr" -> if (integer && arguments.getOrNull(1)?.type?.isInt() == true) {
                    integerShift { a, b -> "($a).wrapping_shr($b as u32)" }
                } else null
                "ushr" -> if (arguments.getOrNull(1)?.type?.isInt() != true) null
                    else if (lhs.type.isInt()) binary { a, b -> "(($a as u32).wrapping_shr($b as u32) as i32)" }
                    else if (lhs.type.isLong()) binary(requireSameType = false) { a, b -> "(($a as u64).wrapping_shr($b as u32) as i64)" }
                    else if (lhs.type.isUInt()) binary(requireSameType = false) { a, b -> "($a).wrapping_shr($b as u32)" }
                    else if (lhs.type.isULong()) binary(requireSameType = false) { a, b -> "($a).wrapping_shr($b as u32)" }
                    else null
                "less" -> binary { a, b -> "($a < $b)" }
                "lessOrEqual" -> binary { a, b -> "($a <= $b)" }
                "greater" -> binary { a, b -> "($a > $b)" }
                "greaterOrEqual" -> binary { a, b -> "($a >= $b)" }
                "EQEQ", "EQEQEQ", "eqeq", "eqeqeq", "areEqualByValue", "ieee754equals" ->
                    binary { a, b -> "($a == $b)" }
                "ANDAND", "andand" -> binary { a, b -> "($a && $b)" }
                "OROR", "oror" -> binary { a, b -> "($a || $b)" }
                else -> null
            } ?: unsupported(call, RustUnsupportedCode.UNSUPPORTED_CALL, "Malformed primitive operator call ${displayName(callee)}")
        }

        private fun IrExpression.isNonZeroIntegerConstant(): Boolean =
            isNonZeroIntegerConstant(mutableSetOf())

        private fun IrExpression.isNonZeroIntegerConstant(seenVariables: MutableSet<IrVariable>): Boolean = when (this) {
            is IrConst -> when (kind) {
                IrConstKind.Byte -> value as Byte != 0.toByte()
                IrConstKind.Short -> value as Short != 0.toShort()
                IrConstKind.Char -> value as Char != '\u0000'
                IrConstKind.Int -> value as Int != 0
                IrConstKind.Long -> value as Long != 0L
                else -> false
            }
            is IrGetValue -> {
                val variable = symbol.owner as? IrVariable
                variable != null && !variable.isVar && seenVariables.add(variable) &&
                        variable.initializer?.isNonZeroIntegerConstant(seenVariables) == true
            }
            else -> false
        }

        private fun renderConst(constant: IrConst): String = when (constant.kind) {
            IrConstKind.Boolean -> (constant.value as Boolean).toString()
            IrConstKind.Byte -> {
                val value = constant.value as Byte
                if (constant.type.isUByte()) "${value.toInt() and 0xff}_u8" else when (value) {
                    Byte.MIN_VALUE -> "i8::MIN"
                    else -> "${value}_i8"
                }
            }
            IrConstKind.Short -> {
                val value = constant.value as Short
                if (constant.type.isUShort()) "${value.toInt() and 0xffff}_u16" else when (value) {
                    Short.MIN_VALUE -> "i16::MIN"
                    else -> "${value}_i16"
                }
            }
            IrConstKind.Char -> "${(constant.value as Char).code}_u16"
            IrConstKind.Int -> {
                val value = constant.value as Int
                if (constant.type.isUInt()) "${Integer.toUnsignedString(value)}_u32" else when (value) {
                    Int.MIN_VALUE -> "i32::MIN"
                    else -> "${value}_i32"
                }
            }
            IrConstKind.Long -> {
                val value = constant.value as Long
                if (constant.type.isULong()) "${toUnsignedString(value)}_u64" else when (value) {
                    Long.MIN_VALUE -> "i64::MIN"
                    else -> "${value}_i64"
                }
            }
            IrConstKind.Float -> {
                val bits = (constant.value as Float).toRawBits()
                "f32::from_bits(0x${Integer.toUnsignedString(bits, 16).padStart(8, '0')})"
            }
            IrConstKind.Double -> {
                val bits = (constant.value as Double).toRawBits()
                "f64::from_bits(0x${toUnsignedString(bits, 16).padStart(16, '0')})"
            }
            IrConstKind.String -> "\"${escapeRustString(constant.value as String)}\""
            else -> unsupported(constant, RustUnsupportedCode.UNSUPPORTED_EXPRESSION, "Unsupported constant kind ${constant.kind}")
        }

        private fun valueName(value: IrValueDeclaration): String = valueNames.getOrPut(value) {
            val base = sanitizeIdentifier(value.name.asString()).ifEmpty { "value" }
            "${base}_${nextValueIndex++}"
        }

        private fun loopLabel(loop: IrLoop): String = loopNames.getOrPut(loop) { "loop_${nextLoopIndex++}" }

        private fun doWhileContinueLabel(loop: IrLoop): String =
            doWhileContinueNames.getOrPut(loop) { "continue_${nextLoopIndex++}" }
    }

    private class UnsupportedIr(
        val element: IrElement?,
        val code: RustUnsupportedCode,
        message: String,
    ) : RuntimeException(message)

    private companion object {
        fun collectTopLevelFunctions(module: IrModuleFragment): List<IrSimpleFunction> = module.files
            .flatMap { file -> file.declarations.filterIsInstance<IrSimpleFunction>() }
            .filter { it.isTopLevel }
            .sortedBy(::functionKey)

        fun collectReachableFunctions(
            entryPoints: Set<IrSimpleFunction>,
            moduleFunctions: Set<IrSimpleFunction>,
            directInteropCallResolver: RustDirectInteropCallResolver,
        ): Set<IrSimpleFunction> {
            val reachable = linkedSetOf<IrSimpleFunction>()
            val worklist = ArrayDeque(entryPoints.sortedBy(::functionKey))
            while (worklist.isNotEmpty()) {
                val function = worklist.removeFirst()
                if (!reachable.add(function)) continue
                directModuleCalls(function, moduleFunctions, directInteropCallResolver)
                    .filterNot { it in reachable }
                    .sortedBy(::functionKey)
                    .forEach(worklist::addLast)
            }
            return reachable
        }

        fun directModuleCalls(
            function: IrSimpleFunction,
            moduleFunctions: Set<IrSimpleFunction>,
            directInteropCallResolver: RustDirectInteropCallResolver,
        ): Set<IrSimpleFunction> {
            val result = linkedSetOf<IrSimpleFunction>()
            function.body?.acceptVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitCall(expression: IrCall) {
                    val callee = expression.symbol.owner
                    if (directInteropCallResolver.resolve(callee) != null) {
                        expression.arguments.filterNotNull().forEach { it.acceptVoid(this) }
                        return
                    }
                    primitiveCarrierArgument(expression)?.let { argument ->
                        argument.acceptVoid(this)
                        return
                    }
                    when {
                        isKotlinPrintln(callee) -> {
                            expression.arguments.filterNotNull().forEach { argument ->
                                unwrapPrimitiveBox(argument).acceptVoid(this)
                            }
                            return
                        }
                        isPrimitiveOperator(callee) -> {
                            expression.arguments.filterNotNull().forEach { it.acceptVoid(this) }
                            return
                        }
                        isPrimitiveBox(expression) -> {
                            expression.arguments.singleOrNull()?.acceptVoid(this)
                            return
                        }
                        isUnitSingleton(callee) -> return
                    }
                    if (callee in moduleFunctions) result += callee
                    super.visitCall(expression)
                }
            })
            return result
        }

        fun validateSignature(function: IrSimpleFunction) {
            if (!function.isTopLevel || function.parent !is IrFile) {
                unsupported(function, RustUnsupportedCode.NON_TOP_LEVEL_FUNCTION, "Only top-level functions are supported")
            }
            if (function.typeParameters.isNotEmpty()) {
                unsupported(function, RustUnsupportedCode.GENERIC_FUNCTION, "Generic functions are not supported")
            }
            if (function.isSuspend) {
                unsupported(function, RustUnsupportedCode.SUSPEND_FUNCTION, "Suspend functions are not supported")
            }
            function.parameters.forEach { parameter ->
                if (parameter.kind != IrParameterKind.Regular || parameter.varargElementType != null) {
                    unsupported(parameter, RustUnsupportedCode.UNSUPPORTED_PARAMETER, "Only regular non-vararg parameters are supported")
                }
                rustType(parameter.type, parameter)
            }
            rustType(function.returnType, function)
        }

        fun hasSupportedSignature(function: IrSimpleFunction): Boolean = try {
            validateSignature(function)
            true
        } catch (_: UnsupportedIr) {
            false
        }

        fun renderParameters(function: IrSimpleFunction): String = function.parameters.mapIndexed { index, parameter ->
            "${sanitizeIdentifier(parameter.name.asString()).ifEmpty { "arg" }}_$index: ${rustType(parameter.type, parameter)}"
        }.joinToString()

        fun renderReturnType(type: IrType): String =
            if (type.isUnit()) "" else " -> ${rustType(type, null)}"

        fun rustType(type: IrType, element: IrElement?): String {
            if (type.isNullable()) {
                unsupported(element, RustUnsupportedCode.UNSUPPORTED_TYPE, "Nullable types are not supported")
            }
            return when {
                type.isUnit() -> "()"
                type.isBoolean() -> "bool"
                type.isByte() -> "i8"
                type.isShort() -> "i16"
                type.isChar() -> "u16"
                type.isInt() -> "i32"
                type.isLong() -> "i64"
                type.isFloat() -> "f32"
                type.isDouble() -> "f64"
                type.isUByte() -> "u8"
                type.isUShort() -> "u16"
                type.isUInt() -> "u32"
                type.isULong() -> "u64"
                else -> unsupported(element, RustUnsupportedCode.UNSUPPORTED_TYPE, "Unsupported Kotlin type $type")
            }
        }

        fun IrType.isSupportedPrimitive(): Boolean =
            !isNullable() && (
                    isBoolean() || isByte() || isShort() || isChar() || isInt() || isLong() || isFloat() || isDouble() ||
                            isUByte() || isUShort() || isUInt() || isULong()
                    )

        fun IrType.isSupportedInteger(): Boolean =
            !isNullable() && (
                    isByte() || isShort() || isChar() || isInt() || isLong() ||
                            isUByte() || isUShort() || isUInt() || isULong()
                    )

        fun IrType.supportedIntegerBitWidth(): Int? = when {
            !isSupportedInteger() -> null
            isByte() || isUByte() -> 8
            isShort() || isChar() || isUShort() -> 16
            isInt() || isUInt() -> 32
            isLong() || isULong() -> 64
            else -> null
        }

        fun IrType.requiresNarrowUnsignedMergeBarrier(): Boolean =
            !isNullable() && (isChar() || isUByte() || isUShort())

        fun IrType.isSupportedPrintType(): Boolean =
            !isNullable() && (isBoolean() || isInt() || isLong() || isString())

        fun IrExpression.isTrueConstant(): Boolean =
            this is IrConst && kind == IrConstKind.Boolean && value == true

        fun functionKey(function: IrSimpleFunction): String = buildString {
            append(displayName(function))
            append('(')
            function.parameters.joinTo(this) { it.type.toString() }
            append("): ")
            append(function.returnType)
        }

        fun displayName(function: IrSimpleFunction): String =
            function.fqNameWhenAvailable?.asString() ?: function.name.asString()

        fun isKotlinPrintln(function: IrSimpleFunction): Boolean =
            function.name.asString() == "println" && function.fqNameWhenAvailable?.asString() == "kotlin.io.println"

        fun isPrimitiveOperator(function: IrSimpleFunction): Boolean {
            if (function.name.asString() !in PRIMITIVE_OPERATOR_NAMES) return false
            val fqName = function.fqNameWhenAvailable?.asString()
            return fqName == null || fqName.startsWith("kotlin.")
        }

        fun isPrimitiveBox(call: IrCall): Boolean = call.symbol.owner.name.asString().endsWith("-box>")

        fun primitiveCarrierArgument(call: IrCall): IrInlinedFunctionBlock? {
            val callee = call.symbol.owner
            if (!callee.isUnbox() || !callee.returnType.isSupportedPrimitive() || !call.type.isSupportedPrimitive()) return null
            if (callee.returnType != call.type) return null
            val argument = call.arguments.singleOrNull() as? IrInlinedFunctionBlock ?: return null
            return argument.takeIf { it.type.isNothing() }
        }

        fun unwrapPrimitiveBox(expression: IrExpression): IrExpression {
            val call = expression as? IrCall ?: return expression
            if (!isPrimitiveBox(call)) return expression
            return call.arguments.singleOrNull() ?: expression
        }

        fun isUnitSingleton(function: IrSimpleFunction): Boolean =
            function.fqNameWhenAvailable?.asString() == "kotlin.native.internal.theUnitInstance"

        fun defaultRustName(function: IrSimpleFunction): String {
            val readable = sanitizeIdentifier(displayName(function)).take(64).ifEmpty { "function" }
            return "kn_${readable}_${stableHash(functionKey(function))}"
        }

        fun defaultLinkerName(function: IrSimpleFunction): String = defaultRustName(function)

        fun stableHash(value: String): String {
            var hash = -3750763034362895579L // FNV-1a 64-bit offset basis.
            value.encodeToByteArray().forEach { byte ->
                hash = hash xor (byte.toLong() and 0xffL)
                hash *= 1099511628211L
            }
            return toUnsignedString(hash, 16).padStart(16, '0')
        }

        fun sanitizeIdentifier(value: String): String = buildString(value.length) {
            value.forEach { char -> append(if (char.isLetterOrDigit() || char == '_') char else '_') }
        }.let { sanitized ->
            when {
                sanitized.isEmpty() -> sanitized
                sanitized.first().isDigit() -> "_$sanitized"
                sanitized in RUST_KEYWORDS -> "${sanitized}_"
                else -> sanitized
            }
        }

        fun escapeRustString(value: String): String = buildString(value.length) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\u0000' -> append("\\0")
                    else -> if (char.code < 0x20 || char == '\u007f') {
                        append("\\u{")
                        append(char.code.toString(16))
                        append('}')
                    } else {
                        append(char)
                    }
                }
            }
        }

        fun diagnostic(
            function: IrSimpleFunction,
            element: IrElement?,
            code: RustUnsupportedCode,
            message: String,
        ): RustUnsupportedDiagnostic {
            val file = function.parent as? IrFile
            val diagnosticElement = element ?: function
            val offset = diagnosticElement.startOffset
            val hasOffset = file != null && offset != UNDEFINED_OFFSET
            return RustUnsupportedDiagnostic(
                code = code,
                message = message,
                functionName = displayName(function),
                elementKind = diagnosticElement::class.simpleName ?: "IrElement",
                location = RustSourceLocation(
                    path = file?.fileEntry?.name.orEmpty(),
                    line = if (hasOffset) file.fileEntry.getLineNumber(offset) + 1 else null,
                    column = if (hasOffset) file.fileEntry.getColumnNumber(offset) + 1 else null,
                    startOffset = diagnosticElement.startOffset,
                    endOffset = diagnosticElement.endOffset,
                ),
            )
        }

        fun unsupported(element: IrElement?, code: RustUnsupportedCode, message: String): Nothing =
            throw UnsupportedIr(element, code, message)

        fun indent(value: String, spaces: Int = 4): String {
            val prefix = " ".repeat(spaces)
            return value.lineSequence().joinToString("\n") { line -> if (line.isEmpty()) line else prefix + line }
        }

        private val RUST_KEYWORDS = setOf(
            "as", "break", "const", "continue", "crate", "else", "enum", "extern", "false", "fn", "for",
            "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return",
            "self", "Self", "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where",
            "while", "async", "await", "dyn", "abstract", "become", "box", "do", "final", "macro", "override",
            "priv", "typeof", "unsized", "virtual", "yield", "try",
        )

        private val PRIMITIVE_OPERATOR_NAMES = setOf(
            "plus", "minus", "times", "div", "rem", "mod", "unaryMinus", "unaryPlus", "inc", "dec",
            "and", "or", "xor", "not", "shl", "shr", "ushr", "less", "lessOrEqual", "greater",
            "greaterOrEqual", "EQEQ", "EQEQEQ", "eqeq", "eqeqeq", "areEqualByValue", "ieee754equals",
            "ANDAND", "andand", "OROR", "oror",
        )

        private val INTEGER_CONVERSION_NAMES = setOf(
            "toByte", "toShort", "toChar", "toInt", "toLong", "toUByte", "toUShort", "toUInt", "toULong",
        )

        private val UNSIGNED_PRIMITIVE_CONSTRUCTORS = setOf(
            "kotlin.UByte.<constructor>", "kotlin.UShort.<constructor>",
            "kotlin.UInt.<constructor>", "kotlin.ULong.<constructor>",
        )

        private val UNSIGNED_PRIMITIVE_GETTERS = setOf(
            "kotlin.UByte.<get-data>", "kotlin.UShort.<get-data>",
            "kotlin.UInt.<get-data>", "kotlin.ULong.<get-data>",
        )

        private val PRIMITIVE_CARRIER_FIELD_NAMES = setOf(
            "kotlin.Boolean.value", "kotlin.Byte.value", "kotlin.Short.value", "kotlin.Char.value",
            "kotlin.Int.value", "kotlin.Long.value", "kotlin.Float.value", "kotlin.Double.value",
            "kotlin.UByte.data", "kotlin.UShort.data", "kotlin.UInt.data", "kotlin.ULong.data",
        )
    }
}
