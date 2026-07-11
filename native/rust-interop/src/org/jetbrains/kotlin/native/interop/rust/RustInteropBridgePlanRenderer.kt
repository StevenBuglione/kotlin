/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.native.interop.rust

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Canonical JSON is the stable persistence, cache-key, and symbol-hash input for bridge plans. */
object RustInteropBridgePlanRenderer {
    fun render(plan: RustInteropBridgePlan): String = buildString {
        append('{')
        property("schema", plan.schemaVersion)
        append(',')
        property("package", plan.kotlinPackage)
        append(',').appendJsonString("crate").append(':')
        appendCrate(plan.crate)
        append(',').appendJsonString("handles").append(':').append('[')
        plan.handles.sortedBy { it.id }.forEachIndexed { index, handle ->
            if (index != 0) append(',')
            appendHandle(handle)
        }
        append(']')
        append(',').appendJsonString("operations").append(':').append('[')
        plan.operations.sortedBy { it.id }.forEachIndexed { index, operation ->
            if (index != 0) append(',')
            appendOperation(operation)
        }
        append(']').append('}').append('\n')
    }

    internal fun renderOperation(operation: RustInteropOperation): String = buildString {
        appendOperation(operation)
        append('\n')
    }

    private fun StringBuilder.appendCrate(crate: RustInteropCrate) {
        append('{')
        property("name", crate.name)
        append(',')
        property("version", crate.version)
        append(',').appendJsonString("features").append(':')
        appendStringArray(crate.features.distinct().sorted())
        append(',')
        property("defaultFeatures", crate.defaultFeatures)
        append('}')
    }

    private fun StringBuilder.appendHandle(handle: RustInteropHandle) {
        append('{')
        property("id", handle.id)
        append(',')
        property("rustType", handle.rustType)
        append(',')
        property("kotlinType", handle.kotlinType)
        append(',')
        property("threading", handle.threading.externalName)
        append('}')
    }

    private fun StringBuilder.appendOperation(operation: RustInteropOperation) {
        append('{')
        property("id", operation.id)
        append(',')
        property("kind", operation.kind.externalName)
        append(',')
        property("rustPath", operation.rustPath)
        append(',')
        property("kotlinName", operation.kotlinName)
        append(',').appendJsonString("receiver").append(':').append('{')
        property("ownership", operation.receiver.ownership.externalName)
        append(',')
        nullableProperty("handle", operation.receiver.handleId)
        append('}')
        append(',').appendJsonString("parameters").append(':').append('[')
        operation.parameters.forEachIndexed { index, parameter ->
            if (index != 0) append(',')
            append('{')
            property("name", parameter.name)
            append(',')
            property("type", parameter.type.canonicalName())
            append('}')
        }
        append(']')
        append(',')
        property("return", operation.returnType.canonicalName())
        append(',').appendJsonString("error").append(':').append('{')
        property("mode", operation.errorPolicy.mode.externalName)
        append(',')
        nullableProperty("exception", operation.errorPolicy.kotlinException)
        append('}')
        append(',').appendJsonString("panic").append(':').append('{')
        property("mode", operation.panicPolicy.mode.externalName)
        append(',')
        nullableProperty("exception", operation.panicPolicy.kotlinException)
        append('}')
        append(',')
        property("threading", operation.threading.externalName)
        append(',')
        property("async", operation.asyncPolicy.externalName)
        append(',').appendJsonString("targets").append(':')
        appendStringArray(operation.targetPolicy.includedTargets.distinct().sorted())
        append(',').appendJsonString("excludedTargets").append(':')
        appendStringArray(operation.targetPolicy.excludedTargets.distinct().sorted())
        append('}')
    }

    private fun StringBuilder.property(name: String, value: String) {
        appendJsonString(name).append(':').appendJsonString(value)
    }

    private fun StringBuilder.property(name: String, value: Int) {
        appendJsonString(name).append(':').append(value)
    }

    private fun StringBuilder.property(name: String, value: Boolean) {
        appendJsonString(name).append(':').append(value)
    }

    private fun StringBuilder.nullableProperty(name: String, value: String?) {
        appendJsonString(name).append(':')
        if (value == null) append("null") else appendJsonString(value)
    }

    private fun StringBuilder.appendStringArray(values: List<String>) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index != 0) append(',')
            appendJsonString(value)
        }
        append(']')
    }
}

object RustInteropBridgePlanHash {
    fun planHash(plan: RustInteropBridgePlan): String = sha256(RustInteropBridgePlanRenderer.render(plan))

    fun bindingHash(plan: RustInteropBridgePlan, operation: RustInteropOperation): String = sha256(
        planHash(plan) + "\u0000" + RustInteropBridgePlanRenderer.renderOperation(operation)
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

object RustInteropBridgeSymbols {
    fun bindingSymbol(plan: RustInteropBridgePlan, operation: RustInteropOperation): String {
        require(operation in plan.operations) { "Operation '${operation.id}' is not part of the bridge plan" }
        return buildString {
            append("knri_v").append(plan.schemaVersion)
            append("_p").append(RustInteropBridgePlanHash.planHash(plan).take(32))
            append("_b").append(RustInteropBridgePlanHash.bindingHash(plan, operation).take(16))
        }
    }
}

fun RustInteropBridgeType.canonicalName(): String = when (this) {
    RustInteropBridgeType.Unit -> "unit"
    is RustInteropBridgeType.Primitive -> kind.externalName
    RustInteropBridgeType.Utf8String -> "string"
    RustInteropBridgeType.ByteArray -> "bytes"
    is RustInteropBridgeType.Handle -> "handle:$id"
    is RustInteropBridgeType.Option -> "option:${valueType.canonicalName()}"
}

private fun StringBuilder.appendJsonString(value: String): StringBuilder {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else append(character)
        }
    }
    return append('"')
}
