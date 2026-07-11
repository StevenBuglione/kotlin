/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.native.interop.rust

/** The source-facing, versioned Rust interop definition. */
data class RustInteropDefinition(
    val schemaVersion: Int,
    val kotlinPackage: String,
    val crate: RustInteropCrateDefinition,
    val handles: List<RustInteropHandleDefinition>,
    val operations: List<RustInteropOperationDefinition>,
)

data class RustInteropCrateDefinition(
    val name: String,
    val version: String,
    val features: List<String> = emptyList(),
    val defaultFeatures: Boolean = true,
)

data class RustInteropHandleDefinition(
    val id: String,
    val rustType: String,
    val kotlinType: String,
    val threading: String,
)

data class RustInteropOperationDefinition(
    val id: String,
    val kind: String,
    val rustPath: String,
    val kotlinName: String,
    val receiver: String,
    val parameters: List<String>,
    val returnType: String,
    val error: String,
    val panic: String,
    val threading: String,
    val isAsync: Boolean,
    val targets: List<String> = emptyList(),
    val excludedTargets: List<String> = emptyList(),
)

/** A backend-facing bridge plan. It contains operations, not Rust ABI declarations. */
data class RustInteropBridgePlan(
    val schemaVersion: Int,
    val kotlinPackage: String,
    val crate: RustInteropCrate,
    val handles: List<RustInteropHandle>,
    val operations: List<RustInteropOperation>,
)

data class RustInteropCrate(
    val name: String,
    val version: String,
    val features: List<String>,
    val defaultFeatures: Boolean,
)

data class RustInteropHandle(
    val id: String,
    val rustType: String,
    val kotlinType: String,
    val threading: RustInteropHandleThreading,
)

enum class RustInteropHandleThreading(val externalName: String) {
    LOCAL("local"),
    SEND("send"),
    SEND_SYNC("send-sync"),
}

data class RustInteropOperation(
    val id: String,
    val kind: RustInteropOperationKind,
    val rustPath: String,
    val kotlinName: String,
    val receiver: RustInteropReceiver,
    val parameters: List<RustInteropParameter>,
    val returnType: RustInteropBridgeType,
    val errorPolicy: RustInteropErrorPolicy,
    val panicPolicy: RustInteropPanicPolicy,
    val threading: RustInteropOperationThreading,
    val asyncPolicy: RustInteropAsyncPolicy,
    val targetPolicy: RustInteropTargetPolicy,
)

enum class RustInteropOperationKind(val externalName: String) {
    FUNCTION("function"),
    CONSTRUCTOR("constructor"),
    METHOD("method"),
    PROPERTY_GET("property-get"),
    CLOSE("close"),
}

data class RustInteropReceiver(
    val ownership: RustInteropReceiverOwnership,
    val handleId: String?,
)

enum class RustInteropReceiverOwnership(val externalName: String) {
    NONE("none"),
    SHARED("borrow"),
    EXCLUSIVE("borrow-mut"),
    CONSUMING("consume"),
}

data class RustInteropParameter(
    val name: String,
    val type: RustInteropBridgeType,
)

sealed interface RustInteropBridgeType {
    data object Unit : RustInteropBridgeType
    data class Primitive(val kind: RustInteropPrimitive) : RustInteropBridgeType
    data object Utf8String : RustInteropBridgeType
    data object ByteArray : RustInteropBridgeType
    data class Handle(val id: String) : RustInteropBridgeType
    data class Option(val valueType: RustInteropBridgeType) : RustInteropBridgeType
}

enum class RustInteropPrimitive(val externalName: String) {
    BOOLEAN("bool"),
    INT8("i8"),
    INT16("i16"),
    INT32("i32"),
    INT64("i64"),
    UINT8("u8"),
    UINT16("u16"),
    UINT32("u32"),
    UINT64("u64"),
    FLOAT32("f32"),
    FLOAT64("f64"),
}

data class RustInteropErrorPolicy(
    val mode: RustInteropErrorMode,
    val kotlinException: String?,
)

enum class RustInteropErrorMode(val externalName: String) {
    NONE("none"),
    KOTLIN_EXCEPTION("kotlin-exception"),
}

data class RustInteropPanicPolicy(
    val mode: RustInteropPanicMode,
    val kotlinException: String?,
)

enum class RustInteropPanicMode(val externalName: String) {
    ABORT("abort"),
    FATAL("fatal"),
    KOTLIN_EXCEPTION("kotlin-exception"),
}

enum class RustInteropOperationThreading(val externalName: String) {
    CALLER("caller"),
    ANY("any"),
}

enum class RustInteropAsyncPolicy(val externalName: String) {
    SYNCHRONOUS("synchronous"),
    RUST_FUTURE("rust-future"),
}

data class RustInteropTargetPolicy(
    val includedTargets: List<String>,
    val excludedTargets: List<String>,
)

data class RustInteropDefinitionDiagnostic(
    val sourceName: String,
    val path: String,
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
) {
    override fun toString(): String = buildString {
        append(sourceName)
        if (line != null) {
            append(':').append(line)
            if (column != null) append(':').append(column)
        }
        if (path.isNotEmpty()) append(" [").append(path).append(']')
        append(": ").append(message)
    }
}

class RustInteropDefinitionException(
    val diagnostics: List<RustInteropDefinitionDiagnostic>,
) : IllegalArgumentException(diagnostics.joinToString(separator = "\n"))
