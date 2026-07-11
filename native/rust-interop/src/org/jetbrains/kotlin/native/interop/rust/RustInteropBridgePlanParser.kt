/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.native.interop.rust

/** Reads only the canonical JSON emitted by [RustInteropBridgePlanRenderer]. */
object RustInteropBridgePlanParser {
    fun parse(text: String, sourceName: String = "<bridge-plan>"): RustInteropBridgePlan {
        val canonicalText = text.trimEnd('\r', '\n') + "\n"
        val root = JsonParser(canonicalText, sourceName).parse().objectValue(sourceName, "")
        val plan = RustInteropBridgePlan(
            schemaVersion = root.requiredInt(sourceName, "schema"),
            kotlinPackage = root.requiredString(sourceName, "package"),
            crate = root.requiredObject(sourceName, "crate").toCrate(sourceName),
            handles = root.requiredArray(sourceName, "handles").mapIndexed { index, value ->
                value.objectValue(sourceName, "handles[$index]").toHandle(sourceName)
            },
            operations = root.requiredArray(sourceName, "operations").mapIndexed { index, value ->
                value.objectValue(sourceName, "operations[$index]").toOperation(sourceName)
            },
        )
        if (plan.schemaVersion != RustInteropBridgePlanBuilder.SUPPORTED_SCHEMA_VERSION) {
            fail(sourceName, "unsupported bridge-plan schema ${plan.schemaVersion}")
        }
        if (RustInteropBridgePlanRenderer.render(plan) != canonicalText) {
            fail(sourceName, "plan is valid JSON but is not in canonical bridge-plan form")
        }
        return plan
    }
}

class RustInteropBridgePlanParseException(message: String) : IllegalArgumentException(message)

private fun Map<String, JsonValue>.toCrate(sourceName: String): RustInteropCrate = RustInteropCrate(
    name = requiredString(sourceName, "name"),
    version = requiredString(sourceName, "version"),
    features = requiredStringArray(sourceName, "features"),
    defaultFeatures = requiredBoolean(sourceName, "defaultFeatures"),
)

private fun Map<String, JsonValue>.toHandle(sourceName: String): RustInteropHandle = RustInteropHandle(
    id = requiredString(sourceName, "id"),
    rustType = requiredString(sourceName, "rustType"),
    kotlinType = requiredString(sourceName, "kotlinType"),
    threading = enumValue(sourceName, "threading", RustInteropHandleThreading.entries) { it.externalName },
)

private fun Map<String, JsonValue>.toOperation(sourceName: String): RustInteropOperation {
    val receiver = requiredObject(sourceName, "receiver")
    val error = requiredObject(sourceName, "error")
    val panic = requiredObject(sourceName, "panic")
    return RustInteropOperation(
        id = requiredString(sourceName, "id"),
        kind = enumValue(sourceName, "kind", RustInteropOperationKind.entries) { it.externalName },
        rustPath = requiredString(sourceName, "rustPath"),
        kotlinName = requiredString(sourceName, "kotlinName"),
        receiver = RustInteropReceiver(
            ownership = receiver.enumValue(sourceName, "ownership", RustInteropReceiverOwnership.entries) { it.externalName },
            handleId = receiver.requiredNullableString(sourceName, "handle"),
        ),
        parameters = requiredArray(sourceName, "parameters").mapIndexed { index, value ->
            val parameter = value.objectValue(sourceName, "parameters[$index]")
            RustInteropParameter(
                parameter.requiredString(sourceName, "name"),
                parseBridgeType(sourceName, parameter.requiredString(sourceName, "type")),
            )
        },
        returnType = parseBridgeType(sourceName, requiredString(sourceName, "return")),
        errorPolicy = RustInteropErrorPolicy(
            error.enumValue(sourceName, "mode", RustInteropErrorMode.entries) { it.externalName },
            error.requiredNullableString(sourceName, "exception"),
        ),
        panicPolicy = RustInteropPanicPolicy(
            panic.enumValue(sourceName, "mode", RustInteropPanicMode.entries) { it.externalName },
            panic.requiredNullableString(sourceName, "exception"),
        ),
        threading = enumValue(sourceName, "threading", RustInteropOperationThreading.entries) { it.externalName },
        asyncPolicy = enumValue(sourceName, "async", RustInteropAsyncPolicy.entries) { it.externalName },
        targetPolicy = RustInteropTargetPolicy(
            requiredStringArray(sourceName, "targets"),
            requiredStringArray(sourceName, "excludedTargets"),
        ),
    )
}

private fun parseBridgeType(sourceName: String, value: String): RustInteropBridgeType = when (value) {
    "unit" -> RustInteropBridgeType.Unit
    "string" -> RustInteropBridgeType.Utf8String
    "bytes" -> RustInteropBridgeType.ByteArray
    else -> RustInteropPrimitive.entries.singleOrNull { it.externalName == value }
        ?.let(RustInteropBridgeType::Primitive)
        ?: value.removePrefixOrNull("handle:")?.let(RustInteropBridgeType::Handle)
        ?: value.removePrefixOrNull("option:")?.let { RustInteropBridgeType.Option(parseBridgeType(sourceName, it)) }
        ?: fail(sourceName, "unsupported canonical bridge type '$value'")
}

private fun String.removePrefixOrNull(prefix: String): String? =
    if (startsWith(prefix) && length > prefix.length) substring(prefix.length) else null

private inline fun <T> Map<String, JsonValue>.enumValue(
    sourceName: String,
    key: String,
    values: List<T>,
    externalName: (T) -> String,
): T {
    val value = requiredString(sourceName, key)
    return values.singleOrNull { externalName(it) == value }
        ?: fail(sourceName, "property '$key' has unsupported value '$value'")
}

private fun Map<String, JsonValue>.required(key: String, sourceName: String): JsonValue =
    this[key] ?: fail(sourceName, "missing required property '$key'")

private fun Map<String, JsonValue>.requiredString(sourceName: String, key: String): String =
    (required(key, sourceName) as? JsonValue.StringValue)?.value
        ?: fail(sourceName, "property '$key' must be a string")

private fun Map<String, JsonValue>.requiredNullableString(sourceName: String, key: String): String? = when (val value = required(key, sourceName)) {
    JsonValue.Null -> null
    is JsonValue.StringValue -> value.value
    else -> fail(sourceName, "property '$key' must be a string or null")
}

private fun Map<String, JsonValue>.requiredInt(sourceName: String, key: String): Int =
    (required(key, sourceName) as? JsonValue.IntValue)?.value
        ?: fail(sourceName, "property '$key' must be a 32-bit integer")

private fun Map<String, JsonValue>.requiredBoolean(sourceName: String, key: String): Boolean =
    (required(key, sourceName) as? JsonValue.BooleanValue)?.value
        ?: fail(sourceName, "property '$key' must be a boolean")

private fun Map<String, JsonValue>.requiredObject(sourceName: String, key: String): Map<String, JsonValue> =
    required(key, sourceName).objectValue(sourceName, key)

private fun Map<String, JsonValue>.requiredArray(sourceName: String, key: String): List<JsonValue> =
    (required(key, sourceName) as? JsonValue.ArrayValue)?.values
        ?: fail(sourceName, "property '$key' must be an array")

private fun Map<String, JsonValue>.requiredStringArray(sourceName: String, key: String): List<String> =
    requiredArray(sourceName, key).mapIndexed { index, value ->
        (value as? JsonValue.StringValue)?.value
            ?: fail(sourceName, "property '$key[$index]' must be a string")
    }

private fun JsonValue.objectValue(sourceName: String, path: String): Map<String, JsonValue> =
    (this as? JsonValue.ObjectValue)?.properties
        ?: fail(sourceName, if (path.isEmpty()) "bridge plan root must be an object" else "property '$path' must be an object")

private sealed interface JsonValue {
    data class ObjectValue(val properties: Map<String, JsonValue>) : JsonValue
    data class ArrayValue(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class IntValue(val value: Int) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

private class JsonParser(private val text: String, private val sourceName: String) {
    private var index = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        if (index != text.length) error("unexpected trailing content")
        return value
    }

    private fun parseValue(): JsonValue = when (peek()) {
        '{' -> parseObject()
        '[' -> parseArray()
        '"' -> JsonValue.StringValue(parseString())
        't' -> literal("true", JsonValue.BooleanValue(true))
        'f' -> literal("false", JsonValue.BooleanValue(false))
        'n' -> literal("null", JsonValue.Null)
        '-', in '0'..'9' -> parseInt()
        else -> error("expected a JSON value")
    }

    private fun parseObject(): JsonValue.ObjectValue {
        expect('{')
        skipWhitespace()
        val properties = linkedMapOf<String, JsonValue>()
        if (consume('}')) return JsonValue.ObjectValue(properties)
        while (true) {
            if (peek() != '"') error("expected an object property name")
            val key = parseString()
            if (key in properties) error("duplicate object property '$key'")
            skipWhitespace()
            expect(':')
            skipWhitespace()
            properties[key] = parseValue()
            skipWhitespace()
            if (consume('}')) return JsonValue.ObjectValue(properties)
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseArray(): JsonValue.ArrayValue {
        expect('[')
        skipWhitespace()
        val values = mutableListOf<JsonValue>()
        if (consume(']')) return JsonValue.ArrayValue(values)
        while (true) {
            values += parseValue()
            skipWhitespace()
            if (consume(']')) return JsonValue.ArrayValue(values)
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseString(): String {
        expect('"')
        return buildString {
            while (true) {
                val character = nextOrError("unterminated string")
                when (character) {
                    '"' -> return@buildString
                    '\\' -> append(parseEscape())
                    else -> {
                        if (character.code < 0x20) error("unescaped control character in string")
                        append(character)
                    }
                }
            }
        }
    }

    private fun parseEscape(): Char = when (val escape = nextOrError("unterminated escape sequence")) {
        '"', '\\', '/' -> escape
        'b' -> '\b'
        'f' -> '\u000C'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
            val end = index + 4
            if (end > text.length) error("incomplete Unicode escape")
            val code = text.substring(index, end).toIntOrNull(16) ?: error("invalid Unicode escape")
            index = end
            code.toChar()
        }
        else -> error("invalid escape sequence '\\$escape'")
    }

    private fun parseInt(): JsonValue.IntValue {
        val start = index
        if (peek() == '-') index++
        if (peek() !in '0'..'9') error("invalid integer")
        while (peek() in '0'..'9') index++
        val value = text.substring(start, index).toIntOrNull() ?: error("integer is outside the supported 32-bit range")
        return JsonValue.IntValue(value)
    }

    private fun <T : JsonValue> literal(literal: String, value: T): T {
        if (!text.startsWith(literal, index)) error("expected '$literal'")
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') index++
    }

    private fun expect(character: Char) {
        if (!consume(character)) error("expected '$character'")
    }

    private fun consume(character: Char): Boolean {
        if (peek() != character) return false
        index++
        return true
    }

    private fun peek(): Char = text.getOrNull(index) ?: '\u0000'

    private fun nextOrError(message: String): Char = text.getOrNull(index++) ?: error(message)

    private fun error(message: String): Nothing = fail(sourceName, "$message at character ${index + 1}")
}

private fun fail(sourceName: String, message: String): Nothing =
    throw RustInteropBridgePlanParseException("$sourceName: $message")
