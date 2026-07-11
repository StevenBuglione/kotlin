/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust.codegen

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
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isDouble
import org.jetbrains.kotlin.ir.types.isFloat
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.isUInt
import org.jetbrains.kotlin.ir.types.isULong
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
        val reachableFunctions = collectReachableFunctions(validEntries, moduleFunctions)
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
                val invalidCallee = directModuleCalls(function, moduleFunctions).firstOrNull {
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
    ) {
        private val valueNames = IdentityHashMap<IrValueDeclaration, String>()
        private val loopNames = IdentityHashMap<IrLoop, String>()
        private val doWhileContinueNames = IdentityHashMap<IrLoop, String>()
        private var nextValueIndex = 0
        private var nextLoopIndex = 0

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
            is IrWhen -> renderWhen(expression)
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
            if (irReturn.returnTargetSymbol.owner !== function) {
                unsupported(irReturn, RustUnsupportedCode.UNSUPPORTED_RETURN_TARGET, "Only returns from the current function are supported")
            }
            return if (function.returnType.isUnit()) {
                // Unit is still a value-producing expression in IR and may carry side effects.
                "{ ${expression(irReturn.value)}; return; }"
            } else {
                "return ${expression(irReturn.value)}"
            }
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
            renderPrimitiveOperator(callee, arguments, call)?.let { return it }
            if (isUnitSingleton(callee)) return "()"
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
            if (conversion != "toInt" && conversion != "toLong") return null
            val fqName = callee.fqNameWhenAvailable?.asString()
            if (fqName != null && !fqName.startsWith("kotlin.")) return null
            val value = arguments.singleOrNull()
                ?: unsupported(call, RustUnsupportedCode.UNSUPPORTED_CALL, "Malformed primitive conversion call ${displayName(callee)}")
            val renderedValue = expression(value)
            return when {
                conversion == "toLong" && value.type.isInt() -> "($renderedValue as i64)"
                conversion == "toLong" && value.type.isLong() -> renderedValue
                conversion == "toInt" && value.type.isLong() -> "($renderedValue as i32)"
                conversion == "toInt" && value.type.isInt() -> renderedValue
                else -> unsupported(
                    call,
                    RustUnsupportedCode.UNSUPPORTED_CALL,
                    "Primitive conversion ${displayName(callee)} requires unsupported numeric conversion semantics",
                )
            }
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
            val integer = lhs.type.isInt() || lhs.type.isLong() || lhs.type.isUInt() || lhs.type.isULong()
            return when (name) {
                "plus" -> binary { a, b -> if (integer) "($a).wrapping_add($b)" else "($a + $b)" }
                "minus" -> binary { a, b -> if (integer) "($a).wrapping_sub($b)" else "($a - $b)" }
                "times" -> binary { a, b -> if (integer) "($a).wrapping_mul($b)" else "($a * $b)" }
                // Integer division by zero must throw Kotlin's ArithmeticException, so it cannot be
                // emitted for a zero or non-constant divisor until the exception ABI is available.
                "div" -> if (integer) {
                    if (arguments.size == 2 && rustType(lhs.type, call) == rustType(arguments[1].type, call)) {
                        if (!arguments[1].isNonZeroIntegerConstant()) {
                            unsupported(call, RustUnsupportedCode.UNSUPPORTED_CALL, "Integer division requires Kotlin exception interop")
                        }
                        binary { a, b -> "($a).wrapping_div($b)" }
                    } else null
                } else {
                    binary { a, b -> "($a / $b)" }
                }
                "rem", "mod" -> if (integer) {
                    if (arguments.size == 2 && rustType(lhs.type, call) == rustType(arguments[1].type, call)) {
                        if (!arguments[1].isNonZeroIntegerConstant()) {
                            unsupported(call, RustUnsupportedCode.UNSUPPORTED_CALL, "Integer remainder requires Kotlin exception interop")
                        }
                        binary { a, b -> "($a).wrapping_rem($b)" }
                    } else null
                } else {
                    binary { a, b -> "($a % $b)" }
                }
                "unaryMinus" -> unary { value -> if (integer) "($value).wrapping_neg()" else "(-$value)" }
                "unaryPlus" -> unary { value -> value }
                "inc" -> unary { value -> if (integer) "($value).wrapping_add(1)" else "($value + 1.0)" }
                "dec" -> unary { value -> if (integer) "($value).wrapping_sub(1)" else "($value - 1.0)" }
                "and" -> binary { a, b -> "($a & $b)" }
                "or" -> binary { a, b -> "($a | $b)" }
                "xor" -> binary { a, b -> "($a ^ $b)" }
                "not" -> unary { value -> "(!$value)" }
                "shl" -> if (integer && arguments.getOrNull(1)?.type?.isInt() == true) {
                    binary(requireSameType = false) { a, b -> "($a).wrapping_shl($b as u32)" }
                } else null
                "shr" -> if (integer && arguments.getOrNull(1)?.type?.isInt() == true) {
                    binary(requireSameType = false) { a, b -> "($a).wrapping_shr($b as u32)" }
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

        private fun IrExpression.isNonZeroIntegerConstant(): Boolean = when (this) {
            is IrConst -> when (kind) {
                IrConstKind.Int -> value as Int != 0
                IrConstKind.Long -> value as Long != 0L
                else -> false
            }
            else -> false
        }

        private fun renderConst(constant: IrConst): String = when (constant.kind) {
            IrConstKind.Boolean -> (constant.value as Boolean).toString()
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
        ): Set<IrSimpleFunction> {
            val reachable = linkedSetOf<IrSimpleFunction>()
            val worklist = ArrayDeque(entryPoints.sortedBy(::functionKey))
            while (worklist.isNotEmpty()) {
                val function = worklist.removeFirst()
                if (!reachable.add(function)) continue
                directModuleCalls(function, moduleFunctions)
                    .filterNot { it in reachable }
                    .sortedBy(::functionKey)
                    .forEach(worklist::addLast)
            }
            return reachable
        }

        fun directModuleCalls(
            function: IrSimpleFunction,
            moduleFunctions: Set<IrSimpleFunction>,
        ): Set<IrSimpleFunction> {
            val result = linkedSetOf<IrSimpleFunction>()
            function.body?.acceptVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitCall(expression: IrCall) {
                    val callee = expression.symbol.owner
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
                type.isInt() -> "i32"
                type.isLong() -> "i64"
                type.isFloat() -> "f32"
                type.isDouble() -> "f64"
                type.isUInt() -> "u32"
                type.isULong() -> "u64"
                else -> unsupported(element, RustUnsupportedCode.UNSUPPORTED_TYPE, "Unsupported Kotlin type $type")
            }
        }

        fun IrType.isSupportedPrimitive(): Boolean =
            !isNullable() && (isBoolean() || isInt() || isLong() || isFloat() || isDouble() || isUInt() || isULong())

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
    }
}
