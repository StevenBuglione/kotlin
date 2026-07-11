/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import org.jetbrains.kotlin.backend.konan.rust.codegen.RustDirectInteropCall
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustDirectInteropCallResolver
import org.jetbrains.kotlin.backend.konan.rust.codegen.RustDirectInteropBoundaryPolicy
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isByte
import org.jetbrains.kotlin.ir.types.isDouble
import org.jetbrains.kotlin.ir.types.isFloat
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.types.isShort
import org.jetbrains.kotlin.ir.types.isUByte
import org.jetbrains.kotlin.ir.types.isUInt
import org.jetbrains.kotlin.ir.types.isULong
import org.jetbrains.kotlin.ir.types.isUShort
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.presetName
import org.jetbrains.kotlin.native.interop.rust.RustInteropAsyncPolicy
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgePlan
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgePlanParser
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgeSymbols
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgeType
import org.jetbrains.kotlin.native.interop.rust.RustInteropCrate
import org.jetbrains.kotlin.native.interop.rust.RustInteropErrorMode
import org.jetbrains.kotlin.native.interop.rust.RustInteropOperation
import org.jetbrains.kotlin.native.interop.rust.RustInteropOperationKind
import org.jetbrains.kotlin.native.interop.rust.RustInteropPanicMode
import org.jetbrains.kotlin.native.interop.rust.RustInteropPrimitive
import org.jetbrains.kotlin.native.interop.rust.RustInteropReceiverOwnership
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/** Exact plan bindings that may replace calls to generated primitive Kotlin facades. */
internal class RustDirectInteropPlan private constructor(
    private val bindingsByKotlinName: Map<String, Binding>,
) : RustDirectInteropCallResolver {
    private val usedBindings = linkedSetOf<Binding>()
    internal val bindingCount: Int get() = bindingsByKotlinName.values.count { it.boundaryPolicy.isSupported }
    internal val fallbackBindingCount: Int get() = bindingsByKotlinName.size - bindingCount

    override fun resolve(callee: IrSimpleFunction): RustDirectInteropCall? {
        val kotlinName = callee.fqNameWhenAvailable?.asString() ?: return null
        val binding = bindingsByKotlinName[kotlinName] ?: return null
        if (!binding.matchesSignature(callee) || !callee.callsBindingSymbol(binding.bindingSymbol)) return null
        if (binding.boundaryPolicy.isSupported) usedBindings += binding
        return RustDirectInteropCall(binding.rustPath, binding.boundaryPolicy)
    }

    fun usedCargoDependencies(cratePathOverrides: Map<String, java.nio.file.Path> = emptyMap()): List<RustCargoDependency> = usedBindings
        .map { it.crate }
        .distinct()
        .sortedBy { it.name }
        .map { crate ->
            RustCargoDependency(
                crate.name,
                crate.version,
                crate.features,
                crate.defaultFeatures,
                cratePathOverrides[crate.name],
            )
        }

    internal data class Binding(
        val kotlinName: String,
        val rustPath: String,
        val bindingSymbol: String,
        val parameters: List<RustInteropPrimitive>,
        val returnType: RustInteropPrimitive,
        val crate: RustInteropCrate,
        val boundaryPolicy: RustDirectInteropBoundaryPolicy,
    ) {
        fun matchesSignature(function: IrSimpleFunction): Boolean =
            function.parameters.size == parameters.size &&
                    function.parameters.zip(parameters).all { pair ->
                        pair.first.kind == IrParameterKind.Regular && pair.first.type.matches(pair.second)
                    } && function.returnType.matches(returnType)
    }

    companion object {
        val EMPTY = RustDirectInteropPlan(emptyMap())

        fun load(paths: List<String>, target: KonanTarget): RustDirectInteropPlan {
            if (paths.isEmpty()) return EMPTY
            val plans = paths.distinct().sorted().map { path ->
                val planPath = Paths.get(path)
                val contents = Files.readAllBytes(planPath).toString(StandardCharsets.UTF_8)
                RustInteropBridgePlanParser.parse(contents, planPath.toString())
            }
            return fromPlans(plans, target.presetName)
        }

        internal fun fromPlans(plans: List<RustInteropBridgePlan>, targetName: String): RustDirectInteropPlan {
            validateCrateCoordinates(plans)
            val bindings = plans.flatMap { plan ->
                plan.operations.mapNotNull { operation -> plan.directBinding(operation, targetName) }
            }
            val duplicates = bindings.groupBy { it.kotlinName }.filterValues { it.size > 1 }.keys.sorted()
            require(duplicates.isEmpty()) {
                "Rust interop plans contain duplicate direct Kotlin bindings: ${duplicates.joinToString()}"
            }
            return RustDirectInteropPlan(bindings.associateBy { it.kotlinName })
        }

        private fun validateCrateCoordinates(plans: List<RustInteropBridgePlan>) {
            plans.groupBy { it.crate.name }.entries.forEach { entry ->
                val coordinates = entry.value.map {
                    Triple(it.crate.version, it.crate.features.distinct().sorted(), it.crate.defaultFeatures)
                }.distinct()
                require(coordinates.size == 1) { "Rust interop crate '${entry.key}' has conflicting Cargo coordinates" }
            }
        }

        private fun RustInteropBridgePlan.directBinding(
            operation: RustInteropOperation,
            targetName: String,
        ): Binding? {
            val targetPolicy = operation.targetPolicy
            if (targetPolicy.includedTargets.isNotEmpty() && targetName !in targetPolicy.includedTargets) return null
            if (targetName in targetPolicy.excludedTargets) return null
            if (operation.kind != RustInteropOperationKind.FUNCTION || '.' in operation.kotlinName) return null
            if (operation.receiver.ownership != RustInteropReceiverOwnership.NONE || operation.receiver.handleId != null) return null
            if (operation.asyncPolicy != RustInteropAsyncPolicy.SYNCHRONOUS) return null
            val parameters = operation.parameters.map { (it.type as? RustInteropBridgeType.Primitive)?.kind ?: return null }
            val returnType = (operation.returnType as? RustInteropBridgeType.Primitive)?.kind ?: return null
            val boundaryPolicy = when {
                operation.errorPolicy.mode != RustInteropErrorMode.NONE -> RustDirectInteropBoundaryPolicy.Unsupported(
                    "Rust direct interop operation '${operation.id}' requests error policy " +
                            "'${operation.errorPolicy.mode.externalName}', but direct Rust calls cannot convert Rust errors " +
                            "to Kotlin exceptions yet",
                )
                operation.panicPolicy.mode != RustInteropPanicMode.ABORT -> RustDirectInteropBoundaryPolicy.Unsupported(
                    "Rust direct interop operation '${operation.id}' requests panic policy " +
                            "'${operation.panicPolicy.mode.externalName}', but direct Rust calls currently require 'abort' " +
                            "so a Rust panic cannot cross the Kotlin boundary",
                )
                else -> RustDirectInteropBoundaryPolicy.CatchRustPanicAndAbort
            }
            return Binding(
                kotlinName = "$kotlinPackage.${operation.kotlinName}",
                rustPath = operation.rustPath,
                bindingSymbol = RustInteropBridgeSymbols.bindingSymbol(this, operation),
                parameters = parameters,
                returnType = returnType,
                crate = crate,
                boundaryPolicy = boundaryPolicy,
            )
        }
    }
}

private val RustDirectInteropBoundaryPolicy.isSupported: Boolean
    get() = this === RustDirectInteropBoundaryPolicy.CatchRustPanicAndAbort

private fun IrSimpleFunction.callsBindingSymbol(bindingSymbol: String): Boolean {
    var found = false
    body?.acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            if (!found) element.acceptChildrenVoid(this)
        }

        override fun visitSimpleFunction(declaration: IrSimpleFunction) {
            // Local declarations cannot be part of the generated top-level facade marker.
        }

        override fun visitCall(expression: org.jetbrains.kotlin.ir.expressions.IrCall) {
            if (expression.symbol.owner.name.asString() == bindingSymbol) {
                found = true
                return
            }
            super.visitCall(expression)
        }
    })
    return found
}

private fun IrType.matches(primitive: RustInteropPrimitive): Boolean = when (primitive) {
    RustInteropPrimitive.BOOLEAN -> isBoolean()
    RustInteropPrimitive.INT8 -> isByte()
    RustInteropPrimitive.INT16 -> isShort()
    RustInteropPrimitive.INT32 -> isInt()
    RustInteropPrimitive.INT64 -> isLong()
    RustInteropPrimitive.UINT8 -> isUByte()
    RustInteropPrimitive.UINT16 -> isUShort()
    RustInteropPrimitive.UINT32 -> isUInt()
    RustInteropPrimitive.UINT64 -> isULong()
    RustInteropPrimitive.FLOAT32 -> isFloat()
    RustInteropPrimitive.FLOAT64 -> isDouble()
}
