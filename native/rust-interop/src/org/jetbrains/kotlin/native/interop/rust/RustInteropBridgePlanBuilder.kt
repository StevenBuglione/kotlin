/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.native.interop.rust

object RustInteropBridgePlanBuilder {
    fun build(definition: RustInteropDefinition, sourceName: String = "<rustinterop>"): RustInteropBridgePlan {
        val validator = Validator(sourceName)
        return validator.build(definition)
    }

    private class Validator(private val sourceName: String) {
        private val diagnostics = mutableListOf<RustInteropDefinitionDiagnostic>()

        fun build(definition: RustInteropDefinition): RustInteropBridgePlan {
            if (definition.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                report("schema", "unsupported schema ${definition.schemaVersion}; expected $SUPPORTED_SCHEMA_VERSION")
            }
            requireQualifiedName("package", definition.kotlinPackage)
            requireMatch("crate.name", definition.crate.name, CARGO_NAME, "Cargo crate name")
            requireNotBlank("crate.version", definition.crate.version)
            definition.crate.features.forEachIndexed { index, feature ->
                requireMatch("crate.features[$index]", feature, CARGO_FEATURE, "Cargo feature")
            }

            val duplicateHandleIds = duplicates(definition.handles.map { it.id })
            duplicateHandleIds.forEach { report("handle", "duplicate handle id '$it'") }
            val duplicateOperationIds = duplicates(definition.operations.map { it.id })
            duplicateOperationIds.forEach { report("operation", "duplicate operation id '$it'") }
            duplicates(definition.operations.map { it.kotlinName }).forEach {
                report("operation", "duplicate Kotlin operation name '$it'")
            }

            val handles = definition.handles.mapIndexedNotNull { index, handle -> buildHandle(index, handle) }
            val handleIds = handles.mapTo(mutableSetOf()) { it.id }
            val operations = definition.operations.mapIndexedNotNull { index, operation ->
                buildOperation(index, operation, handleIds)
            }

            if (definition.handles.isEmpty()) report("handle", "at least one opaque handle is required")
            if (definition.operations.isEmpty()) report("operation", "at least one operation is required")

            if (diagnostics.isNotEmpty()) throw RustInteropDefinitionException(diagnostics.toList())
            return RustInteropBridgePlan(
                schemaVersion = definition.schemaVersion,
                kotlinPackage = definition.kotlinPackage,
                crate = RustInteropCrate(
                    name = definition.crate.name,
                    version = definition.crate.version,
                    features = definition.crate.features.distinct().sorted(),
                    defaultFeatures = definition.crate.defaultFeatures,
                ),
                handles = handles.sortedBy { it.id },
                operations = operations.sortedBy { it.id },
            )
        }

        private fun buildHandle(index: Int, definition: RustInteropHandleDefinition): RustInteropHandle? {
            val path = "handle[$index]"
            val validId = requireMatch("$path.id", definition.id, ID, "handle id")
            val validRustType = requireMatch("$path.rust-type", definition.rustType, RUST_PATH, "Rust type path")
            val validKotlinType = requireQualifiedName("$path.kotlin-type", definition.kotlinType)
            val threading = RustInteropHandleThreading.entries.singleOrNull { it.externalName == definition.threading }
            if (threading == null) {
                report("$path.threading", "expected one of ${RustInteropHandleThreading.entries.joinToString { it.externalName }}")
            }
            return if (validId && validRustType && validKotlinType && threading != null) {
                RustInteropHandle(definition.id, definition.rustType, definition.kotlinType, threading)
            } else null
        }

        private fun buildOperation(
            index: Int,
            definition: RustInteropOperationDefinition,
            handleIds: Set<String>,
        ): RustInteropOperation? {
            val path = "operation[$index]"
            val validId = requireMatch("$path.id", definition.id, ID, "operation id")
            val kind = RustInteropOperationKind.entries.singleOrNull { it.externalName == definition.kind }
            if (kind == null) report("$path.kind", "expected one of ${RustInteropOperationKind.entries.joinToString { it.externalName }}")
            val validRustPath = requireMatch("$path.rust-path", definition.rustPath, RUST_PATH, "Rust item path")
            val validKotlinName = requireQualifiedName("$path.kotlin-name", definition.kotlinName)
            val receiver = parseReceiver("$path.receiver", definition.receiver, handleIds)
            val parameters = definition.parameters.mapIndexedNotNull { parameterIndex, parameter ->
                parseParameter("$path.parameters[$parameterIndex]", parameter, handleIds)
            }
            duplicates(parameters.map { it.name }).forEach { report("$path.parameters", "duplicate parameter name '$it'") }
            val returnType = parseType("$path.return", definition.returnType, handleIds)
            val errorPolicy = parseErrorPolicy("$path.error", definition.error)
            val panicPolicy = parsePanicPolicy("$path.panic", definition.panic)
            val threading = RustInteropOperationThreading.entries.singleOrNull { it.externalName == definition.threading }
            if (threading == null) {
                report("$path.threading", "expected one of ${RustInteropOperationThreading.entries.joinToString { it.externalName }}")
            }
            val asyncPolicy = if (definition.isAsync) RustInteropAsyncPolicy.RUST_FUTURE else RustInteropAsyncPolicy.SYNCHRONOUS

            val includedTargets = definition.targets.distinct().sorted()
            val excludedTargets = definition.excludedTargets.distinct().sorted()
            (includedTargets.toSet() intersect excludedTargets.toSet()).forEach {
                report("$path.targets", "target '$it' is both included and excluded")
            }
            (includedTargets + excludedTargets).forEachIndexed { targetIndex, target ->
                requireMatch("$path.targets[$targetIndex]", target, TARGET_NAME, "Kotlin/Native target name")
            }

            if (kind != null && receiver != null && returnType != null) {
                when (kind) {
                    RustInteropOperationKind.FUNCTION -> if (receiver.ownership != RustInteropReceiverOwnership.NONE) {
                        report("$path.receiver", "a function must use receiver 'none'")
                    }
                    RustInteropOperationKind.CONSTRUCTOR -> {
                        if (receiver.ownership != RustInteropReceiverOwnership.NONE) {
                            report("$path.receiver", "a constructor must use receiver 'none'")
                        }
                        if (returnType !is RustInteropBridgeType.Handle) {
                            report("$path.return", "a constructor must return an opaque handle")
                        }
                    }
                    RustInteropOperationKind.METHOD -> if (receiver.ownership == RustInteropReceiverOwnership.NONE) {
                        report("$path.receiver", "a method requires a handle receiver")
                    }
                    RustInteropOperationKind.PROPERTY_GET -> {
                        if (receiver.ownership == RustInteropReceiverOwnership.NONE) {
                            report("$path.receiver", "a property getter requires a handle receiver")
                        }
                        if (parameters.isNotEmpty()) report("$path.parameters", "a property getter cannot declare parameters")
                    }
                    RustInteropOperationKind.CLOSE -> {
                        if (receiver.ownership != RustInteropReceiverOwnership.CONSUMING) {
                            report("$path.receiver", "a close operation must consume its handle")
                        }
                        if (returnType != RustInteropBridgeType.Unit) report("$path.return", "a close operation must return unit")
                    }
                }
            }

            return if (
                validId && kind != null && validRustPath && validKotlinName && receiver != null &&
                parameters.size == definition.parameters.size && returnType != null && errorPolicy != null &&
                panicPolicy != null && threading != null
            ) {
                RustInteropOperation(
                    id = definition.id,
                    kind = kind,
                    rustPath = definition.rustPath,
                    kotlinName = definition.kotlinName,
                    receiver = receiver,
                    parameters = parameters,
                    returnType = returnType,
                    errorPolicy = errorPolicy,
                    panicPolicy = panicPolicy,
                    threading = threading,
                    asyncPolicy = asyncPolicy,
                    targetPolicy = RustInteropTargetPolicy(includedTargets, excludedTargets),
                )
            } else null
        }

        private fun parseReceiver(path: String, text: String, handleIds: Set<String>): RustInteropReceiver? {
            if (text == RustInteropReceiverOwnership.NONE.externalName) {
                return RustInteropReceiver(RustInteropReceiverOwnership.NONE, null)
            }
            val separator = text.indexOf(':')
            if (separator <= 0 || separator == text.lastIndex) {
                report(path, "expected 'none', 'borrow:<handle>', 'borrow-mut:<handle>', or 'consume:<handle>'")
                return null
            }
            val ownershipName = text.substring(0, separator)
            val handleId = text.substring(separator + 1)
            val ownership = RustInteropReceiverOwnership.entries.singleOrNull {
                it != RustInteropReceiverOwnership.NONE && it.externalName == ownershipName
            }
            if (ownership == null) {
                report(path, "unknown receiver ownership '$ownershipName'")
                return null
            }
            if (handleId !in handleIds) report(path, "unknown handle '$handleId'")
            return RustInteropReceiver(ownership, handleId)
        }

        private fun parseParameter(path: String, text: String, handleIds: Set<String>): RustInteropParameter? {
            val separator = text.indexOf(':')
            if (separator <= 0 || separator == text.lastIndex) {
                report(path, "expected '<name>:<type>'")
                return null
            }
            val name = text.substring(0, separator)
            val typeText = text.substring(separator + 1)
            val validName = requireMatch("$path.name", name, KOTLIN_IDENTIFIER, "parameter name")
            val type = parseType("$path.type", typeText, handleIds)
            if (type == RustInteropBridgeType.Unit) report("$path.type", "unit is not a valid parameter type")
            return if (validName && type != null && type != RustInteropBridgeType.Unit) RustInteropParameter(name, type) else null
        }

        private fun parseType(path: String, text: String, handleIds: Set<String>): RustInteropBridgeType? {
            val primitive = RustInteropPrimitive.entries.singleOrNull { it.externalName == text }
            if (primitive != null) return RustInteropBridgeType.Primitive(primitive)
            return when {
                text == "unit" -> RustInteropBridgeType.Unit
                text == "string" -> RustInteropBridgeType.Utf8String
                text == "bytes" -> RustInteropBridgeType.ByteArray
                text.startsWith("handle:") -> {
                    val handleId = text.removePrefix("handle:")
                    if (handleId !in handleIds) report(path, "unknown handle '$handleId'")
                    RustInteropBridgeType.Handle(handleId)
                }
                text.startsWith("option:") -> {
                    val value = parseType(path, text.removePrefix("option:"), handleIds) ?: return null
                    if (value == RustInteropBridgeType.Unit || value is RustInteropBridgeType.Option) {
                        report(path, "option must wrap a non-unit, non-option bridge type")
                        null
                    } else RustInteropBridgeType.Option(value)
                }
                else -> {
                    report(path, "unsupported bridge type '$text'")
                    null
                }
            }
        }

        private fun parseErrorPolicy(path: String, text: String): RustInteropErrorPolicy? = when {
            text == "none" -> RustInteropErrorPolicy(RustInteropErrorMode.NONE, null)
            text.startsWith("kotlin-exception:") -> {
                val exception = text.removePrefix("kotlin-exception:")
                if (requireQualifiedName(path, exception)) {
                    RustInteropErrorPolicy(RustInteropErrorMode.KOTLIN_EXCEPTION, exception)
                } else null
            }
            else -> {
                report(path, "expected 'none' or 'kotlin-exception:<qualified-name>'")
                null
            }
        }

        private fun parsePanicPolicy(path: String, text: String): RustInteropPanicPolicy? = when {
            text == "abort" -> RustInteropPanicPolicy(RustInteropPanicMode.ABORT, null)
            text == "fatal" -> RustInteropPanicPolicy(RustInteropPanicMode.FATAL, null)
            text.startsWith("kotlin-exception:") -> {
                val exception = text.removePrefix("kotlin-exception:")
                if (requireQualifiedName(path, exception)) {
                    RustInteropPanicPolicy(RustInteropPanicMode.KOTLIN_EXCEPTION, exception)
                } else null
            }
            else -> {
                report(path, "expected 'abort', 'fatal', or 'kotlin-exception:<qualified-name>'")
                null
            }
        }

        private fun requireNotBlank(path: String, value: String): Boolean {
            if (value.isNotBlank()) return true
            report(path, "must not be blank")
            return false
        }

        private fun requireQualifiedName(path: String, value: String): Boolean =
            requireMatch(path, value, QUALIFIED_KOTLIN_NAME, "Kotlin qualified name")

        private fun requireMatch(path: String, value: String, regex: Regex, description: String): Boolean {
            if (regex.matches(value)) return true
            report(path, "invalid $description '$value'")
            return false
        }

        private fun report(path: String, message: String) {
            diagnostics += RustInteropDefinitionDiagnostic(sourceName, path, message)
        }
    }

    const val SUPPORTED_SCHEMA_VERSION: Int = 1

    private val ID = Regex("[a-z][a-z0-9_-]*")
    private val CARGO_NAME = Regex("[A-Za-z0-9_-]+")
    private val CARGO_FEATURE = Regex("[A-Za-z0-9_+./?-]+")
    private val RUST_PATH = Regex("(?:::)?[A-Za-z_][A-Za-z0-9_]*(?:::[A-Za-z_][A-Za-z0-9_]*)*")
    private val KOTLIN_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val QUALIFIED_KOTLIN_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")
    private val TARGET_NAME = Regex("[a-z][A-Za-z0-9]*")
}

private fun <T> duplicates(values: List<T>): Set<T> {
    val seen = mutableSetOf<T>()
    return values.filterNotTo(linkedSetOf()) { seen.add(it) }
}
