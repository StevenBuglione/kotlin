/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.native.interop.rust

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class RustInteropBridgeArtifacts(
    val cargoManifest: String,
    val rustSource: String,
    val cHeader: String,
    val cInteropPackage: String,
    val kotlinFacades: Map<String, String>,
)

class RustInteropBridgeGenerationException(message: String) : IllegalArgumentException(message)

/** Renders the deterministic source artifacts for one Rust static library per Native compilation. */
object RustInteropBridgeArtifactGenerator {
    fun generate(plan: RustInteropBridgePlan): RustInteropBridgeArtifacts = generate(listOf(plan))

    fun generate(
        plans: List<RustInteropBridgePlan>,
        crateName: String = "kotlin_rust_interop",
    ): RustInteropBridgeArtifacts {
        if (plans.isEmpty()) fail("at least one bridge plan is required")
        if (!CARGO_NAME.matches(crateName)) fail("invalid generated Cargo package name '$crateName'")

        val orderedPlans = plans.distinctBy { RustInteropBridgePlanHash.planHash(it) }
            .sortedWith(compareBy({ it.crate.name }, { RustInteropBridgePlanHash.planHash(it) }))
        validate(orderedPlans)
        val aggregate = Aggregate(orderedPlans, crateName)
        return RustInteropBridgeArtifacts(
            cargoManifest = renderCargoManifest(aggregate),
            rustSource = renderRustSource(aggregate),
            cHeader = renderCHeader(aggregate),
            cInteropPackage = aggregate.cInteropPackage,
            kotlinFacades = renderKotlinFacades(aggregate),
        )
    }

    private fun validate(plans: List<RustInteropBridgePlan>) {
        plans.forEach { plan ->
            if (plan.schemaVersion != RustInteropBridgePlanBuilder.SUPPORTED_SCHEMA_VERSION) {
                fail("plan for crate '${plan.crate.name}' uses unsupported schema ${plan.schemaVersion}")
            }
            if (plan.handles.any { it.threading != RustInteropHandleThreading.SEND_SYNC }) {
                fail("plan for crate '${plan.crate.name}' contains a handle that is not send-sync; registry-backed v1 bridges require send-sync")
            }
            if (plan.operations.any { it.targetPolicy.includedTargets.isNotEmpty() || it.targetPolicy.excludedTargets.isNotEmpty() }) {
                fail("plan for crate '${plan.crate.name}' contains target filters; artifact rendering requires a resolved target plan")
            }
            plan.operations.forEach { validateOperation(plan, it) }
            plan.handles.forEach { handle ->
                val constructors = plan.operations.count { (it.returnType as? RustInteropBridgeType.Handle)?.id == handle.id }
                val closes = plan.operations.count { it.kind == RustInteropOperationKind.CLOSE && it.receiver.handleId == handle.id }
                if (constructors != 1 || closes != 1) {
                    fail("handle '${handle.id}' in crate '${plan.crate.name}' requires exactly one constructor and one close operation; found $constructors constructor(s) and $closes close operation(s)")
                }
            }
        }

        plans.groupBy { it.crate.name }.entries.forEach { entry ->
            val name = entry.key
            val matching = entry.value
            val coordinates = matching.map { Triple(it.crate.version, it.crate.features.sorted(), it.crate.defaultFeatures) }.distinct()
            if (coordinates.size != 1) fail("crate '$name' has conflicting versions, features, or default-features settings")
        }
        val symbols = plans.flatMap { plan -> plan.operations.map { RustInteropBridgeSymbols.bindingSymbol(plan, it) } }
        if (symbols.size != symbols.toSet().size) fail("bridge plans contain colliding exported symbols")
        val kotlinTypes = plans.flatMap { plan -> plan.handles.map { plan.kotlinPackage + "." + it.kotlinType } }
        if (kotlinTypes.size != kotlinTypes.toSet().size) fail("bridge plans contain colliding Kotlin handle types")
    }

    private fun validateOperation(plan: RustInteropBridgePlan, operation: RustInteropOperation) {
        if (operation.asyncPolicy != RustInteropAsyncPolicy.SYNCHRONOUS) {
            fail("operation '${operation.id}' in crate '${plan.crate.name}' has unsupported async shape '${operation.asyncPolicy.externalName}'")
        }
        val supported = when (operation.kind) {
            RustInteropOperationKind.CONSTRUCTOR ->
                operation.receiver.ownership == RustInteropReceiverOwnership.NONE &&
                        operation.parameters.map { it.type } == listOf(RustInteropBridgeType.Utf8String) &&
                        operation.returnType is RustInteropBridgeType.Handle &&
                        operation.errorPolicy.mode == RustInteropErrorMode.KOTLIN_EXCEPTION
            RustInteropOperationKind.METHOD ->
                operation.receiver.ownership == RustInteropReceiverOwnership.SHARED &&
                        operation.parameters.map { it.type } == listOf(RustInteropBridgeType.Utf8String) &&
                        operation.returnType == RustInteropBridgeType.Primitive(RustInteropPrimitive.BOOLEAN) &&
                        operation.errorPolicy.mode == RustInteropErrorMode.NONE
            RustInteropOperationKind.PROPERTY_GET ->
                operation.receiver.ownership == RustInteropReceiverOwnership.SHARED &&
                        operation.parameters.isEmpty() && operation.returnType == RustInteropBridgeType.Utf8String &&
                        operation.errorPolicy.mode == RustInteropErrorMode.NONE
            RustInteropOperationKind.CLOSE ->
                operation.receiver.ownership == RustInteropReceiverOwnership.CONSUMING &&
                        operation.parameters.isEmpty() && operation.returnType == RustInteropBridgeType.Unit &&
                        operation.errorPolicy.mode == RustInteropErrorMode.NONE
            RustInteropOperationKind.FUNCTION -> false
        }
        if (!supported) {
            fail("operation '${operation.id}' in crate '${plan.crate.name}' has an unsupported v1 bridge shape (${operation.kind.externalName})")
        }
        val receiverId = operation.receiver.handleId
        if (receiverId != null && plan.handles.none { it.id == receiverId }) {
            fail("operation '${operation.id}' refers to unknown handle '$receiverId'")
        }
    }

    private data class Aggregate(val plans: List<RustInteropBridgePlan>, val crateName: String) {
        val hash = sha256(plans.joinToString("\u0000") { RustInteropBridgePlanHash.planHash(it) })
        val prefix = "knri_v${RustInteropBridgePlanBuilder.SUPPORTED_SCHEMA_VERSION}_a${hash.take(24)}"
        val cInteropPackage = "kotlinx.rustinterop.generated.a" + hash.take(16)
    }

    private fun renderCargoManifest(aggregate: Aggregate): String = buildString {
        append("[package]\n")
        append("name = \"").append(aggregate.crateName).append("\"\n")
        append("version = \"0.0.0\"\n")
        append("edition = \"2021\"\n")
        append("publish = false\n\n")
        append("[lib]\ncrate-type = [\"staticlib\"]\n\n[dependencies]\n")
        aggregate.plans.map { it.crate }.distinctBy { it.name }.sortedBy { it.name }.forEach { crate ->
            append(crate.name).append(" = { version = \"=").append(crate.version).append('"')
            if (!crate.defaultFeatures) append(", default-features = false")
            if (crate.features.isNotEmpty()) {
                append(", features = [")
                crate.features.sorted().forEachIndexed { index, feature ->
                    if (index != 0) append(", ")
                    append('"').append(feature).append('"')
                }
                append(']')
            }
            append(" }\n")
        }
        append("\n[profile.release]\npanic = \"unwind\"\n")
    }

    private fun renderRustSource(aggregate: Aggregate): String = buildString {
        append("#![allow(clippy::missing_safety_doc)]\n\n")
        append("use std::any::Any;\nuse std::collections::HashMap;\nuse std::panic::{catch_unwind, AssertUnwindSafe};\n")
        append("use std::slice;\nuse std::sync::{Arc, Mutex, OnceLock};\n\n")
        append("const KNRI_OK: i32 = 0;\nconst KNRI_ERROR: i32 = 1;\nconst KNRI_PANIC: i32 = 2;\n")
        append("const KNRI_INVALID_INPUT: i32 = 3;\nconst KNRI_CLOSED_HANDLE: i32 = 4;\n\n")
        append("#[repr(C)]\npub struct KnriUtf8 { pub data: *mut u8, pub len: usize }\n\n")
        append("struct Failure { status: i32, message: String }\n\n")
        append("unsafe fn read_utf8<'a>(data: *const u8, len: usize) -> Result<&'a str, Failure> {\n")
        append("    if len == 0 { return Ok(\"\"); }\n    if data.is_null() { return Err(Failure { status: KNRI_INVALID_INPUT, message: \"null UTF-8 pointer\".into() }); }\n")
        append("    std::str::from_utf8(slice::from_raw_parts(data, len)).map_err(|error| Failure { status: KNRI_INVALID_INPUT, message: error.to_string() })\n}\n\n")
        append("unsafe fn write_utf8(output: *mut KnriUtf8, value: String) -> Result<(), Failure> {\n")
        append("    if output.is_null() { return Err(Failure { status: KNRI_INVALID_INPUT, message: \"null output pointer\".into() }); }\n")
        append("    let mut bytes = value.into_bytes().into_boxed_slice();\n    (*output).len = bytes.len();\n    (*output).data = bytes.as_mut_ptr();\n    std::mem::forget(bytes);\n    Ok(())\n}\n\n")
        append("unsafe fn report_failure(output: *mut KnriUtf8, failure: Failure) -> i32 {\n    let status = failure.status;\n    let _ = write_utf8(output, failure.message);\n    status\n}\n\n")
        append("fn panic_message(payload: Box<dyn Any + Send>) -> String {\n")
        append("    if let Some(value) = payload.downcast_ref::<&str>() { (*value).to_owned() } else if let Some(value) = payload.downcast_ref::<String>() { value.clone() } else { \"Rust panic\".into() }\n}\n\n")
        append("#[no_mangle]\npub unsafe extern \"C\" fn ").append(aggregate.prefix).append("_free_utf8(data: *mut u8, len: usize) {\n")
        append("    if !data.is_null() { drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(data, len))); }\n}\n\n")

        aggregate.plans.forEach { plan ->
            plan.handles.forEach { handle -> appendRegistry(plan, handle) }
        }
        aggregate.plans.forEach { plan ->
            plan.operations.sortedBy { it.id }.forEach { operation -> appendOperation(plan, operation) }
        }
    }

    private fun StringBuilder.appendRegistry(plan: RustInteropBridgePlan, handle: RustInteropHandle) {
        val name = registryName(plan, handle.id)
        append("static ").append(name).append(": OnceLock<Mutex<HashMap<u64, Arc<").append(handle.rustType).append(">>>> = OnceLock::new();\n")
        append("static ").append(name).append("_NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);\n")
        append("fn ").append(name.lowercase()).append("() -> &'static Mutex<HashMap<u64, Arc<").append(handle.rustType).append(">>> {\n")
        append("    ").append(name).append(".get_or_init(|| Mutex::new(HashMap::new()))\n}\n\n")
    }

    private fun StringBuilder.appendOperation(plan: RustInteropBridgePlan, operation: RustInteropOperation) {
        val symbol = RustInteropBridgeSymbols.bindingSymbol(plan, operation)
        val receiverId = operation.receiver.handleId
        val registry = receiverId?.let { registryName(plan, it) }
        val parameter = operation.parameters.singleOrNull()?.name ?: "value"
        val panicMode = operation.panicPolicy.mode
        append("#[no_mangle]\npub unsafe extern \"C\" fn ").append(symbol).append('(')
        when (operation.kind) {
            RustInteropOperationKind.CONSTRUCTOR -> append(parameter).append("_data: *const u8, ").append(parameter).append("_len: usize, output: *mut u64, error: *mut KnriUtf8")
            RustInteropOperationKind.METHOD -> append("handle: u64, ").append(parameter).append("_data: *const u8, ").append(parameter).append("_len: usize, output: *mut bool, error: *mut KnriUtf8")
            RustInteropOperationKind.PROPERTY_GET -> append("handle: u64, output: *mut KnriUtf8, error: *mut KnriUtf8")
            RustInteropOperationKind.CLOSE -> append("handle: u64, error: *mut KnriUtf8")
            RustInteropOperationKind.FUNCTION -> error("validated above")
        }
        append(") -> i32 {\n    let result = catch_unwind(AssertUnwindSafe(|| -> Result<(), Failure> {\n")
        when (operation.kind) {
            RustInteropOperationKind.CONSTRUCTOR -> {
                val handle = (operation.returnType as RustInteropBridgeType.Handle).id
                val outputRegistry = registryName(plan, handle)
                append("        if output.is_null() { return Err(Failure { status: KNRI_INVALID_INPUT, message: \"null output pointer\".into() }); }\n")
                append("        let ").append(parameter).append(" = read_utf8(").append(parameter).append("_data, ").append(parameter).append("_len)?;\n")
                append("        let value = ").append(operation.rustPath).append('(').append(parameter).append(").map_err(|error| Failure { status: KNRI_ERROR, message: error.to_string() })?;\n")
                append("        let token = ").append(outputRegistry).append("_NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);\n")
                append("        ").append(outputRegistry.lowercase()).append("().lock().unwrap_or_else(|poisoned| poisoned.into_inner()).insert(token, Arc::new(value));\n")
                append("        *output = token;\n")
            }
            RustInteropOperationKind.METHOD -> {
                append("        if output.is_null() { return Err(Failure { status: KNRI_INVALID_INPUT, message: \"null output pointer\".into() }); }\n")
                append("        let ").append(parameter).append(" = read_utf8(").append(parameter).append("_data, ").append(parameter).append("_len)?;\n")
                append("        let value = ").append(registry!!.lowercase()).append("().lock().unwrap_or_else(|poisoned| poisoned.into_inner()).get(&handle).cloned()")
                append(".ok_or_else(|| Failure { status: KNRI_CLOSED_HANDLE, message: \"closed handle\".into() })?;\n")
                append("        *output = ").append(operation.rustPath).append("(value.as_ref(), ").append(parameter).append(");\n")
            }
            RustInteropOperationKind.PROPERTY_GET -> {
                append("        let value = ").append(registry!!.lowercase()).append("().lock().unwrap_or_else(|poisoned| poisoned.into_inner()).get(&handle).cloned()")
                append(".ok_or_else(|| Failure { status: KNRI_CLOSED_HANDLE, message: \"closed handle\".into() })?;\n")
                append("        write_utf8(output, ").append(operation.rustPath).append("(value.as_ref()).to_owned())?;\n")
            }
            RustInteropOperationKind.CLOSE -> {
                append("        ").append(registry!!.lowercase()).append("().lock().unwrap_or_else(|poisoned| poisoned.into_inner()).remove(&handle)")
                append(".ok_or_else(|| Failure { status: KNRI_CLOSED_HANDLE, message: \"closed handle\".into() })?;\n")
            }
            RustInteropOperationKind.FUNCTION -> error("validated above")
        }
        append("        Ok(())\n    }));\n    match result {\n        Ok(Ok(())) => KNRI_OK,\n        Ok(Err(failure)) => report_failure(error, failure),\n")
        when (panicMode) {
            RustInteropPanicMode.ABORT -> append("        Err(_) => std::process::abort(),\n")
            RustInteropPanicMode.FATAL, RustInteropPanicMode.KOTLIN_EXCEPTION ->
                append("        Err(payload) => report_failure(error, Failure { status: KNRI_PANIC, message: panic_message(payload) }),\n")
        }
        append("    }\n}\n\n")
    }

    private fun renderCHeader(aggregate: Aggregate): String = buildString {
        val guard = (aggregate.prefix + "_H").uppercase()
        append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n")
        append("#include <stdbool.h>\n#include <stddef.h>\n#include <stdint.h>\n\n")
        append("#ifdef __cplusplus\nextern \"C\" {\n#endif\n\n")
        append("typedef struct knri_utf8 { uint8_t *data; size_t len; } knri_utf8;\n\n")
        append("enum { KNRI_OK = 0, KNRI_ERROR = 1, KNRI_PANIC = 2, KNRI_INVALID_INPUT = 3, KNRI_CLOSED_HANDLE = 4 };\n\n")
        append("void ").append(aggregate.prefix).append("_free_utf8(uint8_t *data, size_t len);\n")
        aggregate.plans.forEach { plan ->
            plan.operations.sortedBy { it.id }.forEach { operation ->
                append("int32_t ").append(RustInteropBridgeSymbols.bindingSymbol(plan, operation)).append('(')
                val parameter = operation.parameters.singleOrNull()?.name ?: "value"
                when (operation.kind) {
                    RustInteropOperationKind.CONSTRUCTOR -> append("const uint8_t *").append(parameter).append("_data, size_t ").append(parameter).append("_len, uint64_t *output, knri_utf8 *error")
                    RustInteropOperationKind.METHOD -> append("uint64_t handle, const uint8_t *").append(parameter).append("_data, size_t ").append(parameter).append("_len, bool *output, knri_utf8 *error")
                    RustInteropOperationKind.PROPERTY_GET -> append("uint64_t handle, knri_utf8 *output, knri_utf8 *error")
                    RustInteropOperationKind.CLOSE -> append("uint64_t handle, knri_utf8 *error")
                    RustInteropOperationKind.FUNCTION -> error("validated above")
                }
                append(");\n")
            }
        }
        append("\n#ifdef __cplusplus\n}\n#endif\n\n#endif\n")
    }

    private fun renderKotlinFacades(aggregate: Aggregate): Map<String, String> = buildMap {
        aggregate.plans.forEach { plan ->
            val fileName = "knri_" + RustInteropBridgePlanHash.planHash(plan).take(12) + ".kt"
            val relativePath = plan.kotlinPackage.replace('.', '/') + "/" + fileName
            put(relativePath, renderKotlinFacade(aggregate, plan))
        }
    }.toSortedMap()

    private fun renderKotlinFacade(aggregate: Aggregate, plan: RustInteropBridgePlan): String = buildString {
        val kotlinPackage = plan.kotlinPackage
        append("@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)\n\n")
        append("package ").append(kotlinPackage).append("\n\n")
        append("import kotlin.concurrent.AtomicLong\nimport kotlin.native.ref.createCleaner\nimport kotlinx.cinterop.*\n")
        append("import ").append(aggregate.cInteropPackage).append(".*\n\n")
        append("private const val KNRI_OK_STATUS = 0\n\n")
        append("private fun knriMessage(value: knri_utf8): String {\n")
        append("    val result = if (value.data == null || value.len.toLong() == 0L) \"\" else value.data!!.readBytes(value.len.toInt()).decodeToString()\n")
        append("    ").append(aggregate.prefix).append("_free_utf8(value.data, value.len)\n    return result\n}\n\n")
        plan.operations.flatMap { operation ->
            listOfNotNull(operation.errorPolicy.kotlinException, operation.panicPolicy.kotlinException)
        }.filter { '.' !in it }.distinct().sorted().forEach { exception ->
            append("public class ").append(exception).append("(message: String) : RuntimeException(message)\n\n")
        }
        plan.handles.forEach { handle -> appendKotlinHandle(aggregate, plan, handle) }
    }

    private fun StringBuilder.appendKotlinHandle(aggregate: Aggregate, plan: RustInteropBridgePlan, handle: RustInteropHandle) {
        val operations = plan.operations.filter {
            it.receiver.handleId == handle.id || (it.returnType as? RustInteropBridgeType.Handle)?.id == handle.id
        }
        val close = operations.single { it.kind == RustInteropOperationKind.CLOSE }
        val constructor = operations.single { it.kind == RustInteropOperationKind.CONSTRUCTOR }
        val closeSymbol = RustInteropBridgeSymbols.bindingSymbol(plan, close)
        append("private class ").append(handle.kotlinType).append("State(token: ULong) {\n    val token = AtomicLong(token.toLong())\n}\n\n")
        append("public class ").append(handle.kotlinType).append(" private constructor(private val state: ").append(handle.kotlinType).append("State) : AutoCloseable {\n")
        append("    private val cleaner = createCleaner(state) { resource ->\n        val token = resource.token.getAndSet(0L)\n        if (token != 0L) memScoped {\n            val error = alloc<knri_utf8>()\n            val status = ").append(closeSymbol).append("(token.toULong(), error.ptr)\n            if (status != KNRI_OK_STATUS) knriMessage(error)\n        }\n    }\n\n")
        operations.filter { it.kind == RustInteropOperationKind.METHOD || it.kind == RustInteropOperationKind.PROPERTY_GET }.sortedBy { it.id }.forEach { operation ->
            val member = operation.kotlinName.substringAfterLast('.')
            val symbol = RustInteropBridgeSymbols.bindingSymbol(plan, operation)
            if (operation.kind == RustInteropOperationKind.PROPERTY_GET) {
                append("    public val ").append(member).append(": String\n        get() = memScoped {\n            val token = state.token.value\n            check(token != 0L) { \"").append(handle.kotlinType).append(" is closed\" }\n")
                append("            val output = alloc<knri_utf8>()\n            val error = alloc<knri_utf8>()\n            val status = ").append(symbol).append("(token.toULong(), output.ptr, error.ptr)\n")
                append("            if (status != KNRI_OK_STATUS) throw ").append(exceptionFor(operation)).append("(knriMessage(error))\n            knriMessage(output)\n        }\n\n")
            } else {
                val parameter = operation.parameters.single().name
                append("    public fun ").append(member).append('(').append(parameter).append(": String): Boolean = memScoped {\n")
                append("        val token = state.token.value\n        check(token != 0L) { \"").append(handle.kotlinType).append(" is closed\" }\n        val bytes = ").append(parameter).append(".encodeToByteArray()\n        val output = alloc<BooleanVar>()\n        val error = alloc<knri_utf8>()\n")
                append("        val status = bytes.usePinned { pinned -> ").append(symbol).append("(token.toULong(), if (bytes.isEmpty()) null else pinned.addressOf(0).reinterpret(), bytes.size.convert(), output.ptr, error.ptr) }\n")
                append("        if (status != KNRI_OK_STATUS) throw ").append(exceptionFor(operation)).append("(knriMessage(error))\n        output.value\n    }\n\n")
            }
        }
        append("    override fun close() {\n        val token = state.token.getAndSet(0L)\n        if (token == 0L) return\n        memScoped {\n            val error = alloc<knri_utf8>()\n            val status = ").append(closeSymbol).append("(token.toULong(), error.ptr)\n")
        append("            if (status != KNRI_OK_STATUS) throw ").append(exceptionFor(close)).append("(knriMessage(error))\n        }\n    }\n\n")
        val parameter = constructor.parameters.single().name
        val constructorSymbol = RustInteropBridgeSymbols.bindingSymbol(plan, constructor)
        append("    public companion object {\n        public fun ").append(constructor.kotlinName.substringAfterLast('.')).append('(').append(parameter).append(": String): ").append(handle.kotlinType).append(" = memScoped {\n")
        append("            val bytes = ").append(parameter).append(".encodeToByteArray()\n            val output = alloc<ULongVar>()\n            val error = alloc<knri_utf8>()\n")
        append("            val status = bytes.usePinned { pinned -> ").append(constructorSymbol).append("(if (bytes.isEmpty()) null else pinned.addressOf(0).reinterpret(), bytes.size.convert(), output.ptr, error.ptr) }\n")
        append("            if (status != KNRI_OK_STATUS) throw ").append(exceptionFor(constructor)).append("(knriMessage(error))\n            ").append(handle.kotlinType).append('(').append(handle.kotlinType).append("State(output.value))\n        }\n    }\n}\n\n")
    }

    private fun exceptionFor(operation: RustInteropOperation): String =
        operation.errorPolicy.kotlinException ?: operation.panicPolicy.kotlinException ?: "IllegalStateException"

    private fun registryName(plan: RustInteropBridgePlan, handleId: String): String =
        "REGISTRY_" + RustInteropBridgePlanHash.planHash(plan).take(12).uppercase() + "_" + handleId.replace('-', '_').uppercase()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun fail(message: String): Nothing = throw RustInteropBridgeGenerationException(message)

    private val CARGO_NAME = Regex("[A-Za-z0-9_-]+")
}

/** Compatibility name used by Gradle task wiring. */
object RustInteropBridgeArtifactRenderer {
    fun render(
        plans: List<RustInteropBridgePlan>,
        packageName: String = "kotlin_rust_interop",
    ): RustInteropBridgeArtifacts = RustInteropBridgeArtifactGenerator.generate(plans, packageName)
}
