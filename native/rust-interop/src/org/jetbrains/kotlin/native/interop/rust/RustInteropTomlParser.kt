/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.native.interop.rust

/**
 * Parser for the intentionally bounded Rust interop TOML v1 dialect.
 *
 * Supported TOML constructs are basic strings, integers, booleans, arrays of basic strings,
 * `[crate]`, `[[handle]]`, and `[[operation]]`. Other TOML constructs fail explicitly so a
 * definition cannot acquire a different meaning when this dialect grows.
 */
object RustInteropTomlParser {
    fun parseDefinition(text: String, sourceName: String = "<rustinterop>"): RustInteropDefinition =
        Parser(text, sourceName).parse()

    fun parsePlan(text: String, sourceName: String = "<rustinterop>"): RustInteropBridgePlan {
        val definition = parseDefinition(text, sourceName)
        return RustInteropBridgePlanBuilder.build(definition, sourceName)
    }

    private class Parser(text: String, private val sourceName: String) {
        private val lines = text.splitToSequence('\n').map { it.removeSuffix("\r") }.toList()
        private val diagnostics = mutableListOf<RustInteropDefinitionDiagnostic>()
        private val root = RawTable(Section.ROOT, 1, "")
        private var crate: RawTable? = null
        private val handles = mutableListOf<RawTable>()
        private val operations = mutableListOf<RawTable>()
        private var current = root

        fun parse(): RustInteropDefinition {
            lines.forEachIndexed { index, rawLine -> parseLine(index + 1, rawLine) }
            if (diagnostics.isNotEmpty()) throw RustInteropDefinitionException(diagnostics.toList())

            val crateTable = crate
            if (crateTable == null) report("crate", "missing required [crate] table", 1, 1)
            val definition = RustInteropDefinition(
                schemaVersion = root.requiredInt("schema"),
                kotlinPackage = root.requiredString("package"),
                crate = if (crateTable != null) {
                    RustInteropCrateDefinition(
                        name = crateTable.requiredString("name"),
                        version = crateTable.requiredString("version"),
                        features = crateTable.optionalStringList("features"),
                        defaultFeatures = crateTable.optionalBoolean("default-features", true),
                    )
                } else RustInteropCrateDefinition("", ""),
                handles = handles.map { table ->
                    RustInteropHandleDefinition(
                        id = table.requiredString("id"),
                        rustType = table.requiredString("rust-type"),
                        kotlinType = table.requiredString("kotlin-type"),
                        threading = table.requiredString("threading"),
                    )
                },
                operations = operations.map { table ->
                    RustInteropOperationDefinition(
                        id = table.requiredString("id"),
                        kind = table.requiredString("kind"),
                        rustPath = table.requiredString("rust-path"),
                        kotlinName = table.requiredString("kotlin-name"),
                        receiver = table.requiredString("receiver"),
                        parameters = table.requiredStringList("parameters"),
                        returnType = table.requiredString("return"),
                        error = table.requiredString("error"),
                        panic = table.requiredString("panic"),
                        threading = table.requiredString("threading"),
                        isAsync = table.requiredBoolean("async"),
                        targets = table.optionalStringList("targets"),
                        excludedTargets = table.optionalStringList("excluded-targets"),
                    )
                },
            )
            if (diagnostics.isNotEmpty()) throw RustInteropDefinitionException(diagnostics.toList())
            return definition
        }

        private fun parseLine(lineNumber: Int, rawLine: String) {
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) return
            if (line.startsWith('[')) {
                parseHeader(lineNumber, line)
                return
            }

            val equals = findUnquoted(line, '=')
            if (equals <= 0) {
                report(current.path, "expected '<key> = <value>'", lineNumber, 1)
                return
            }
            val key = line.substring(0, equals).trim()
            if (!BARE_KEY.matches(key)) {
                report(current.path, "unsupported key '$key'; v1 requires a bare key", lineNumber, 1)
                return
            }
            if (key !in current.section.keys) {
                report(current.path, "unknown key '$key'", lineNumber, 1)
                return
            }
            if (key in current.values) {
                report(current.keyPath(key), "duplicate key", lineNumber, 1)
                return
            }
            val valueColumn = rawLine.indexOf('=') + 2
            val valueText = line.substring(equals + 1).trim()
            val value = ValueParser(valueText, sourceName, current.keyPath(key), lineNumber, valueColumn).parse()
            if (value is ParsedValue.Failure) {
                diagnostics += value.diagnostic
            } else {
                current.values[key] = LocatedValue((value as ParsedValue.Success).value, lineNumber, valueColumn)
            }
        }

        private fun parseHeader(lineNumber: Int, line: String) {
            when (line) {
                "[crate]" -> {
                    if (crate != null) {
                        report("crate", "duplicate [crate] table", lineNumber, 1)
                    } else {
                        current = RawTable(Section.CRATE, lineNumber, "crate").also { crate = it }
                    }
                }
                "[[handle]]" -> {
                    val index = handles.size
                    current = RawTable(Section.HANDLE, lineNumber, "handle[$index]").also(handles::add)
                }
                "[[operation]]" -> {
                    val index = operations.size
                    current = RawTable(Section.OPERATION, lineNumber, "operation[$index]").also(operations::add)
                }
                else -> report("", "unsupported table header '$line'", lineNumber, 1)
            }
        }

        private fun RawTable.requiredString(key: String): String = value(key, TomlValue.StringValue::class.java)?.value ?: ""
        private fun RawTable.requiredInt(key: String): Int = value(key, TomlValue.IntegerValue::class.java)?.value ?: 0
        private fun RawTable.requiredBoolean(key: String): Boolean = value(key, TomlValue.BooleanValue::class.java)?.value ?: false
        private fun RawTable.requiredStringList(key: String): List<String> =
            value(key, TomlValue.StringArrayValue::class.java)?.value ?: emptyList()

        private fun RawTable.optionalBoolean(key: String, default: Boolean): Boolean {
            if (key !in values) return default
            return value(key, TomlValue.BooleanValue::class.java)?.value ?: default
        }

        private fun RawTable.optionalStringList(key: String): List<String> {
            if (key !in values) return emptyList()
            return value(key, TomlValue.StringArrayValue::class.java)?.value ?: emptyList()
        }

        private fun <T : TomlValue> RawTable.value(key: String, expectedClass: Class<T>): T? {
            val located = values[key]
            if (located == null) {
                report(keyPath(key), "missing required key", headerLine, 1)
                return null
            }
            if (!expectedClass.isInstance(located.value)) {
                report(keyPath(key), "expected ${expectedClass.expectedName()}", located.line, located.column)
                return null
            }
            return expectedClass.cast(located.value)
        }

        private fun report(path: String, message: String, line: Int, column: Int) {
            diagnostics += RustInteropDefinitionDiagnostic(sourceName, path, message, line, column)
        }
    }

    private enum class Section(val keys: Set<String>) {
        ROOT(setOf("schema", "package")),
        CRATE(setOf("name", "version", "features", "default-features")),
        HANDLE(setOf("id", "rust-type", "kotlin-type", "threading")),
        OPERATION(
            setOf(
                "id", "kind", "rust-path", "kotlin-name", "receiver", "parameters", "return", "error", "panic",
                "threading", "async", "targets", "excluded-targets",
            )
        ),
    }

    private class RawTable(
        val section: Section,
        val headerLine: Int,
        val path: String,
        val values: MutableMap<String, LocatedValue> = linkedMapOf(),
    ) {
        fun keyPath(key: String): String = if (path.isEmpty()) key else "$path.$key"
    }

    private data class LocatedValue(val value: TomlValue, val line: Int, val column: Int)

    private sealed interface TomlValue {
        data class StringValue(val value: String) : TomlValue
        data class IntegerValue(val value: Int) : TomlValue
        data class BooleanValue(val value: Boolean) : TomlValue
        data class StringArrayValue(val value: List<String>) : TomlValue
    }

    private sealed interface ParsedValue {
        data class Success(val value: TomlValue) : ParsedValue
        data class Failure(val diagnostic: RustInteropDefinitionDiagnostic) : ParsedValue
    }

    private class ValueParser(
        private val text: String,
        private val sourceName: String,
        private val path: String,
        private val line: Int,
        private val baseColumn: Int,
    ) {
        private var index = 0

        fun parse(): ParsedValue {
            val value = when {
                text.startsWith('"') -> parseString()?.let { TomlValue.StringValue(it) }
                text.startsWith('[') -> parseStringArray()?.let { TomlValue.StringArrayValue(it) }
                text == "true" -> TomlValue.BooleanValue(true)
                text == "false" -> TomlValue.BooleanValue(false)
                INTEGER.matches(text) -> text.toIntOrNull()?.let(TomlValue::IntegerValue)
                    ?: return failure("integer is outside the supported 32-bit range")
                else -> return failure("unsupported TOML value; expected a basic string, integer, boolean, or array of basic strings")
            } ?: return failure("malformed TOML value")
            skipWhitespace()
            if (index != text.length && value !is TomlValue.BooleanValue && value !is TomlValue.IntegerValue) {
                return failure("unexpected trailing content")
            }
            return ParsedValue.Success(value)
        }

        private fun parseStringArray(): List<String>? {
            index++
            skipWhitespace()
            val result = mutableListOf<String>()
            if (peek() == ']') {
                index++
                return result
            }
            while (index < text.length) {
                if (peek() != '"') return null
                result += parseString() ?: return null
                skipWhitespace()
                when (peek()) {
                    ']' -> {
                        index++
                        return result
                    }
                    ',' -> {
                        index++
                        skipWhitespace()
                        if (peek() == ']') {
                            index++
                            return result
                        }
                    }
                    else -> return null
                }
            }
            return null
        }

        private fun parseString(): String? {
            if (peek() != '"') return null
            index++
            val result = StringBuilder()
            while (index < text.length) {
                when (val character = text[index++]) {
                    '"' -> return result.toString()
                    '\\' -> {
                        if (index == text.length) return null
                        when (val escaped = text[index++]) {
                            '"', '\\' -> result.append(escaped)
                            'b' -> result.append('\b')
                            't' -> result.append('\t')
                            'n' -> result.append('\n')
                            'f' -> result.append('\u000C')
                            'r' -> result.append('\r')
                            'u' -> {
                                if (index + 4 > text.length) return null
                                val digits = text.substring(index, index + 4)
                                val codePoint = digits.toIntOrNull(16) ?: return null
                                result.append(codePoint.toChar())
                                index += 4
                            }
                            else -> return null
                        }
                    }
                    else -> if (character.code < 0x20) return null else result.append(character)
                }
            }
            return null
        }

        private fun skipWhitespace() {
            while (peek() == ' ' || peek() == '\t') index++
        }

        private fun peek(): Char? = text.getOrNull(index)

        private fun failure(message: String): ParsedValue.Failure = ParsedValue.Failure(
            RustInteropDefinitionDiagnostic(sourceName, path, message, line, baseColumn + index)
        )
    }

    private val BARE_KEY = Regex("[A-Za-z][A-Za-z0-9_-]*")
    private val INTEGER = Regex("[+-]?[0-9]+")
}

private fun stripComment(line: String): String {
    var inString = false
    var escaped = false
    line.forEachIndexed { index, character ->
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        } else {
            when (character) {
                '"' -> inString = true
                '#' -> return line.substring(0, index)
            }
        }
    }
    return line
}

private fun findUnquoted(text: String, needle: Char): Int {
    var inString = false
    var escaped = false
    text.forEachIndexed { index, character ->
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        } else {
            when {
                character == '"' -> inString = true
                character == needle -> return index
            }
        }
    }
    return -1
}

private fun Class<out Any>.expectedName(): String =
    simpleName.removeSuffix("Value").replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
