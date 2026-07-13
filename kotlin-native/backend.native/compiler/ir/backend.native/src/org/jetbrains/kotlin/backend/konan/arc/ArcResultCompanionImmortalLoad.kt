/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.ir.konanLibrary
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstantObject
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.allParameters
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.library.KotlinLibrary
import java.util.IdentityHashMap

/** Exact declaration identities needed to emit a +0 load of Result's permanent companion root. */
internal data class ArcResultCompanionImmortalLoadPlan(
    val variable: IrVariable,
    val call: IrCall,
    val getter: IrSimpleFunction,
    val rootField: IrField,
    val companionClass: IrClass,
    val resultClass: IrClass,
    val stdlibLibrary: KotlinLibrary,
)

/** Fail-closed mode and use-shape boundary, independently adversarial-testable. */
internal data class ArcResultCompanionImmortalLoadEligibility(
    val arcEnabled: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val diagnosticsDisabled: Boolean,
    val suspendLikeFunction: Boolean,
    val exactInlineTemporary: Boolean,
    val immutableReferenceTemporary: Boolean,
    val exactGetterCall: Boolean,
    val zeroReads: Boolean,
    val zeroWrites: Boolean,
)

internal fun ArcResultCompanionImmortalLoadEligibility.isAuthorized(): Boolean =
    arcEnabled && optimizationsEnabled && debugInfoDisabled && diagnosticsDisabled &&
            suspendLikeFunction && exactInlineTemporary && immutableReferenceTemporary &&
            exactGetterCall && zeroReads && zeroWrites

/**
 * Find only the dead receiver temporary introduced when `Result.success` is inlined into a
 * lowered coroutine. The receiver is the permanent stdlib `Result.Companion` constant object;
 * retaining it in an ARC frame cannot affect lifetime and is therefore pure ownership traffic.
 */
internal fun selectVerifiedResultCompanionImmortalLoads(
    generationState: NativeGenerationState,
    module: IrModuleFragment,
): List<ArcResultCompanionImmortalLoadPlan> {
    val context = generationState.context
    if (context.memoryModel != MemoryModel.ARC || !context.config.optimizationsEnabled ||
        context.shouldContainDebugInfo() || context.config.arcDiagnosticsEnabled
    ) return emptyList()

    val stdlib = context.stdlibModule.konanLibrary ?: return emptyList()
    val resultClasses = mutableListOf<IrClass>()
    module.files.forEach { file ->
        file.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitClass(declaration: IrClass) {
                if (declaration.konanLibrary === stdlib &&
                    declaration.fqNameForIrSerialization.asString() == "kotlin.Result"
                ) resultClasses += declaration
                declaration.acceptChildrenVoid(this)
            }
        })
    }
    val resultClass = resultClasses.singleOrNull()?.takeIf {
        it.konanLibrary === stdlib && it.modality == Modality.FINAL && it.isValue
    } ?: return emptyList()
    val companion = resultClass.declarations.filterIsInstance<IrClass>().singleOrNull {
        it.isCompanion && it.parent === resultClass
    }?.takeIf {
        it.konanLibrary === stdlib && it.kind == ClassKind.OBJECT && it.modality == Modality.FINAL
    } ?: return emptyList()

    val property = resultClass.properties.singleOrNull { it.name.asString() == "\$companion" }
        ?: return emptyList()
    val field = property.backingField?.takeIf {
        it.parent === resultClass && it.konanLibrary === stdlib && it.isStatic && it.isFinal &&
                it.visibility == DescriptorVisibilities.PRIVATE && it.type.classOrNull?.owner === companion
    } ?: return emptyList()
    val initializer = field.initializer?.expression as? IrConstantObject
        ?: return emptyList()
    val constructor = initializer.constructor.owner
    if (constructor.parent !== companion || !constructor.isPrimary ||
        constructor.visibility != DescriptorVisibilities.PRIVATE || constructor.valueParameters.isNotEmpty()
    ) return emptyList()

    val getter = property.getter?.takeIf {
        it.parent === resultClass && it.konanLibrary === stdlib && it.correspondingPropertySymbol == property.symbol &&
                it.modality == Modality.FINAL && it.visibility == DescriptorVisibilities.PUBLIC &&
                !it.isExternal && !it.isSuspend && it.allParameters.isEmpty() &&
                it.returnType.classOrNull?.owner === companion && it.hasExactRootGetterBody(field)
    } ?: return emptyList()

    var rootWrites = 0
    module.files.forEach { file ->
        file.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
            override fun visitSetField(expression: IrSetField) {
                if (expression.symbol.owner === field) rootWrites++
                expression.acceptChildrenVoid(this)
            }
        })
    }
    if (rootWrites != 0) return emptyList()

    val selected = mutableListOf<ArcResultCompanionImmortalLoadPlan>()
    module.files.forEach { file ->
        file.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                val function = declaration
                if (function.isArcSuspendLike()) {
                    val body = function.body
                    val useCensus = function.buildVariableUseCensus()
                    body?.acceptVoid(object : IrElementVisitorVoid {
                        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
                        override fun visitFunction(declaration: IrFunction) {
                            if (declaration === function) declaration.acceptChildrenVoid(this)
                        }
                        override fun visitVariable(declaration: IrVariable) {
                            val variable = declaration
                            val call = variable.initializer?.singleWrappedCall()
                            if (call?.symbol?.owner === getter) {
                                val use = useCensus[variable] ?: ArcVariableUseCount()
                                val eligibility = ArcResultCompanionImmortalLoadEligibility(
                                    arcEnabled = true,
                                    optimizationsEnabled = true,
                                    debugInfoDisabled = true,
                                    diagnosticsDisabled = true,
                                    suspendLikeFunction = true,
                                    exactInlineTemporary = variable.origin ==
                                            IrDeclarationOrigin.IR_TEMPORARY_VARIABLE_FOR_INLINED_PARAMETER,
                                    immutableReferenceTemporary = !variable.isVar &&
                                            variable.type.classOrNull?.owner === companion,
                                    exactGetterCall = call.symbol.owner === getter &&
                                            call.dispatchReceiver == null && call.extensionReceiver == null &&
                                            call.valueArgumentsCount == 0 && call.typeArgumentsCount == 0,
                                    zeroReads = use.reads == 0,
                                    zeroWrites = use.writes == 0,
                                )
                                if (eligibility.isAuthorized()) {
                                    selected += ArcResultCompanionImmortalLoadPlan(
                                        variable, call, getter, field, companion, resultClass, stdlib
                                    )
                                }
                            }
                            declaration.acceptChildrenVoid(this)
                        }
                    })
                }
                function.acceptChildrenVoid(this)
            }
        })
    }
    return selected
}

private fun IrSimpleFunction.hasExactRootGetterBody(field: IrField): Boolean {
    val statements = (body as? IrBlockBody)?.statements ?: return false
    val returned = statements.singleOrNull() as? IrReturn ?: return false
    if (returned.returnTargetSymbol != symbol) return false
    val load = returned.value as? IrGetField ?: return false
    return load.symbol.owner === field && load.receiver == null
}

private fun IrExpression.singleWrappedCall(): IrCall? = when (this) {
    is IrCall -> this
    is IrBlock -> (statements.singleOrNull() as? IrExpression)?.singleWrappedCall()
    else -> null
}

private data class ArcVariableUseCount(var reads: Int = 0, var writes: Int = 0)

/** One identity census per function; candidate validation remains linear in the lowered body size. */
private fun IrSimpleFunction.buildVariableUseCensus(): Map<IrVariable, ArcVariableUseCount> {
    val result = IdentityHashMap<IrVariable, ArcVariableUseCount>()
    fun IrVariable.count(): ArcVariableUseCount = result.getOrPut(this) { ArcVariableUseCount() }
    body?.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
        override fun visitGetValue(expression: IrGetValue) {
            val variable = expression.symbol.owner as? IrVariable ?: return
            variable.count().reads++
        }
        override fun visitSetValue(expression: IrSetValue) {
            (expression.symbol.owner as? IrVariable)?.count()?.let { it.writes++ }
            expression.acceptChildrenVoid(this)
        }
    })
    return result
}
