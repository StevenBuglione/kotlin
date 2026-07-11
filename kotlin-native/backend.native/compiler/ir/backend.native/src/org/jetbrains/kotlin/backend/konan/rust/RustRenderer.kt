/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

internal object RustRenderer {
    fun render(file: RustSourceFile): String = buildString {
        file.attributes.forEach {
            append("#![")
            renderAttribute(it)
            appendLine("]")
        }
        if (file.attributes.isNotEmpty() && file.items.isNotEmpty()) appendLine()

        file.items.forEachIndexed { index, item ->
            if (index != 0) appendLine()
            renderItem(item)
            if (lastOrNull() != '\n') appendLine()
        }
    }

    private fun StringBuilder.renderItem(item: RustItem) {
        when (item) {
            is RustFunction -> renderFunction(item)
            is RustRawItem -> append(item.source.trimEnd()).appendLine()
        }
    }

    private fun StringBuilder.renderFunction(function: RustFunction) {
        function.attributes.forEach {
            append("#[")
            renderAttribute(it)
            appendLine("]")
        }
        if (function.visibility == RustVisibility.PUBLIC) append("pub ")
        if (function.isUnsafe) append("unsafe ")
        function.abi?.let { append("extern \"").append(escapeRustString(it)).append("\" ") }
        append("fn ").append(function.name).append('(')
        function.parameters.forEachIndexed { index, parameter ->
            if (index != 0) append(", ")
            append(parameter.name).append(": ")
            renderType(parameter.type)
        }
        append(')')
        function.returnType?.takeUnless { it == RustType.Unit }?.let {
            append(" -> ")
            renderType(it)
        }
        append(' ')
        renderBlock(function.body, 0)
        appendLine()
    }

    private fun StringBuilder.renderAttribute(attribute: RustAttribute) {
        append(attribute.path)
        attribute.arguments?.let { append('(').append(it).append(')') }
    }

    private fun StringBuilder.renderType(type: RustType) {
        when (type) {
            RustType.Unit -> append("()")
            RustType.Never -> append('!')
            is RustType.Named -> append(type.path)
            is RustType.Tuple -> {
                append('(')
                type.elements.forEachIndexed { index, element ->
                    if (index != 0) append(", ")
                    renderType(element)
                }
                if (type.elements.size == 1) append(',')
                append(')')
            }
            is RustType.Reference -> {
                append('&')
                if (type.mutable) append("mut ")
                renderType(type.referent)
            }
            is RustType.Pointer -> {
                append(if (type.mutable) "*mut " else "*const ")
                renderType(type.pointee)
            }
        }
    }

    private fun StringBuilder.renderBlock(block: RustBlock, indentation: Int) {
        appendLine("{")
        block.statements.forEach { renderStatement(it, indentation + 1) }
        block.result?.let {
            indent(indentation + 1)
            renderExpression(it, indentation + 1)
            appendLine()
        }
        indent(indentation)
        append('}')
    }

    private fun StringBuilder.renderStatement(statement: RustStatement, indentation: Int) {
        when (statement) {
            is RustLet -> {
                indent(indentation)
                append("let ")
                if (statement.mutable) append("mut ")
                append(statement.name)
                statement.type?.let {
                    append(": ")
                    renderType(it)
                }
                statement.value?.let {
                    append(" = ")
                    renderExpression(it, indentation)
                }
                appendLine(";")
            }
            is RustExpressionStatement -> {
                indent(indentation)
                renderExpression(statement.expression, indentation)
                if (statement.semicolon) append(';')
                appendLine()
            }
            is RustRawStatement -> {
                statement.source.trim().lineSequence().forEach {
                    indent(indentation)
                    appendLine(it)
                }
            }
        }
    }

    private fun StringBuilder.renderExpression(expression: RustExpression, indentation: Int) {
        when (expression) {
            RustUnit -> append("()")
            is RustBoolean -> append(expression.value)
            is RustInteger -> append(expression.value)
            is RustFloat -> append(expression.value)
            is RustString -> append('"').append(escapeRustString(expression.value)).append('"')
            is RustPath -> append(expression.path)
            is RustCall -> {
                renderParenthesized(expression.callee, indentation)
                append('(')
                expression.arguments.forEachIndexed { index, argument ->
                    if (index != 0) append(", ")
                    renderExpression(argument, indentation)
                }
                append(')')
            }
            is RustUnary -> {
                append(expression.operator)
                renderParenthesized(expression.operand, indentation)
            }
            is RustBinary -> {
                append('(')
                renderExpression(expression.left, indentation)
                append(' ').append(expression.operator).append(' ')
                renderExpression(expression.right, indentation)
                append(')')
            }
            is RustAssignment -> {
                renderExpression(expression.target, indentation)
                append(' ').append(expression.operator).append(' ')
                renderExpression(expression.value, indentation)
            }
            is RustIf -> {
                append("if ")
                renderExpression(expression.condition, indentation)
                append(' ')
                renderBlock(expression.thenBlock, indentation)
                expression.elseBlock?.let {
                    append(" else ")
                    renderBlock(it, indentation)
                }
            }
            is RustWhile -> {
                append("while ")
                renderExpression(expression.condition, indentation)
                append(' ')
                renderBlock(expression.body, indentation)
            }
            is RustLoop -> {
                append("loop ")
                renderBlock(expression.body, indentation)
            }
            is RustBlockExpression -> renderBlock(expression.block, indentation)
            is RustReturn -> {
                append("return")
                expression.value?.let {
                    append(' ')
                    renderExpression(it, indentation)
                }
            }
            RustBreak -> append("break")
            RustContinue -> append("continue")
            is RustRawExpression -> append(expression.source)
        }
    }

    private fun StringBuilder.renderParenthesized(expression: RustExpression, indentation: Int) {
        if (expression is RustPath || expression is RustCall) {
            renderExpression(expression, indentation)
        } else {
            append('(')
            renderExpression(expression, indentation)
            append(')')
        }
    }

    private fun StringBuilder.indent(level: Int) {
        repeat(level) { append("    ") }
    }

    private fun escapeRustString(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u0000' -> append("\\0")
                else -> if (character.isISOControl()) append("\\u{").append(character.code.toString(16)).append('}') else append(character)
            }
        }
    }
}
