/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

/** A deliberately small Rust syntax tree used by the first Kotlin-to-Rust backend slice. */
internal data class RustSourceFile(
    val attributes: List<RustAttribute> = emptyList(),
    val items: List<RustItem>,
)

internal data class RustAttribute(
    val path: String,
    val arguments: String? = null,
) {
    init {
        require(path.isNotBlank()) { "A Rust attribute path must not be blank" }
        require('\n' !in path && '\r' !in path) { "A Rust attribute path must fit on one line" }
        require(arguments?.let { '\n' !in it && '\r' !in it } != false) { "Rust attribute arguments must fit on one line" }
    }
}

internal sealed interface RustItem

internal enum class RustVisibility {
    PRIVATE,
    PUBLIC,
}

internal data class RustFunction(
    val name: String,
    val parameters: List<RustParameter> = emptyList(),
    val returnType: RustType? = null,
    val body: RustBlock,
    val visibility: RustVisibility = RustVisibility.PRIVATE,
    val attributes: List<RustAttribute> = emptyList(),
    val isUnsafe: Boolean = false,
    val abi: String? = null,
) : RustItem {
    init {
        requireRustIdentifier(name, "function name")
        require(abi?.let { it.isNotBlank() && '\n' !in it && '\r' !in it && '"' !in it } != false) {
            "A Rust ABI must be a non-blank, single-line string without quotes"
        }
    }
}

internal data class RustRawItem(val source: String) : RustItem {
    init {
        require(source.isNotBlank()) { "A raw Rust item must not be blank" }
    }
}

internal data class RustParameter(val name: String, val type: RustType) {
    init {
        requireRustIdentifier(name, "parameter name")
    }
}

internal sealed interface RustType {
    data object Unit : RustType
    data object Never : RustType
    data class Named(val path: String) : RustType {
        init {
            requireRustPath(path, "type path")
        }
    }

    data class Tuple(val elements: List<RustType>) : RustType
    data class Reference(val referent: RustType, val mutable: Boolean = false) : RustType
    data class Pointer(val pointee: RustType, val mutable: Boolean) : RustType
}

internal data class RustBlock(
    val statements: List<RustStatement> = emptyList(),
    val result: RustExpression? = null,
)

internal sealed interface RustStatement

internal data class RustLet(
    val name: String,
    val value: RustExpression? = null,
    val type: RustType? = null,
    val mutable: Boolean = false,
) : RustStatement {
    init {
        requireRustIdentifier(name, "local name")
        require(value != null || type != null) { "A Rust local without an initializer must have an explicit type" }
    }
}

internal data class RustExpressionStatement(
    val expression: RustExpression,
    val semicolon: Boolean = true,
) : RustStatement

internal data class RustRawStatement(val source: String) : RustStatement {
    init {
        require(source.isNotBlank()) { "A raw Rust statement must not be blank" }
    }
}

internal sealed interface RustExpression

internal data object RustUnit : RustExpression
internal data class RustBoolean(val value: Boolean) : RustExpression
internal data class RustInteger(val value: String) : RustExpression {
    init {
        require(RUST_INTEGER.matches(value)) { "Invalid Rust integer literal: $value" }
    }
}

internal data class RustFloat(val value: String) : RustExpression {
    init {
        require(RUST_FLOAT.matches(value)) { "Invalid Rust floating-point literal: $value" }
    }
}

internal data class RustString(val value: String) : RustExpression
internal data class RustPath(val path: String) : RustExpression {
    init {
        requireRustPath(path, "expression path")
    }
}

internal data class RustCall(val callee: RustExpression, val arguments: List<RustExpression>) : RustExpression
internal data class RustUnary(val operator: String, val operand: RustExpression) : RustExpression {
    init {
        require(operator in setOf("!", "-", "*")) { "Unsupported Rust unary operator: $operator" }
    }
}

internal data class RustBinary(
    val left: RustExpression,
    val operator: String,
    val right: RustExpression,
) : RustExpression {
    init {
        require(operator in RUST_BINARY_OPERATORS) { "Unsupported Rust binary operator: $operator" }
    }
}

internal data class RustAssignment(
    val target: RustExpression,
    val value: RustExpression,
    val operator: String = "=",
) : RustExpression {
    init {
        require(operator in RUST_ASSIGNMENT_OPERATORS) { "Unsupported Rust assignment operator: $operator" }
    }
}

internal data class RustIf(
    val condition: RustExpression,
    val thenBlock: RustBlock,
    val elseBlock: RustBlock? = null,
) : RustExpression

internal data class RustWhile(val condition: RustExpression, val body: RustBlock) : RustExpression
internal data class RustLoop(val body: RustBlock) : RustExpression
internal data class RustBlockExpression(val block: RustBlock) : RustExpression
internal data class RustReturn(val value: RustExpression? = null) : RustExpression
internal data object RustBreak : RustExpression
internal data object RustContinue : RustExpression

/** Escape hatch for syntax not modeled yet. Prefer adding a structured node when a construct becomes common. */
internal data class RustRawExpression(val source: String) : RustExpression {
    init {
        require(source.isNotBlank()) { "A raw Rust expression must not be blank" }
    }
}

private val RUST_IDENTIFIER = Regex("(?:r#)?[A-Za-z_][A-Za-z0-9_]*")
private val RUST_PATH = Regex("(?:::)?(?:r#)?[A-Za-z_][A-Za-z0-9_]*(?:::(?:r#)?[A-Za-z_][A-Za-z0-9_]*)*")
private val RUST_INTEGER = Regex("(?:0[xob][0-9A-Fa-f_]+|[0-9][0-9_]*)(?:[iu](?:8|16|32|64|128|size))?")
private val RUST_FLOAT = Regex("(?:[0-9][0-9_]*\\.[0-9_]+|[0-9][0-9_]*(?:[eE][+-]?[0-9_]+))(?:f(?:32|64))?")
private val RUST_BINARY_OPERATORS = setOf(
    "+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", "==", "!=", "<", "<=", ">", ">=", "&&", "||",
)
private val RUST_ASSIGNMENT_OPERATORS = setOf("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=")

private fun requireRustIdentifier(value: String, role: String) {
    require(RUST_IDENTIFIER.matches(value)) { "Invalid Rust $role: $value" }
}

private fun requireRustPath(value: String, role: String) {
    require(RUST_PATH.matches(value)) { "Invalid Rust $role: $value" }
}
