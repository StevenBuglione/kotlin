/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.getOrPut
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.KonanFqNames
import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/** Marks a physical field that owns a weak-reference control block rather than its source-level value. */
internal object ARC_REFERENCE_STORAGE_FIELD_ORIGIN : IrDeclarationOriginImpl("ARC_REFERENCE_STORAGE_FIELD")

private enum class ArcReferenceKind {
    WEAK,
    UNOWNED,
}

/**
 * Replaces ARC weak and checked-unowned declarations with hidden control-block storage.
 *
 * The composite is used by the ordinary file pipeline. The individual passes are internal so the Native inline
 * resolver can apply the same pre-inline sequence to lazily deserialized inline functions.
 */
internal class ArcReferencesLowering(private val context: Context) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        if (!context.usesArcReferenceStorage) return

        ArcReferenceFieldsCreationLowering(context).lower(irFile)
        ArcReferenceFieldsDeclarationLowering(context).lower(irFile)
        ArcReferenceUsageLowering(context).lower(irFile)
    }
}

internal class ArcReferenceFieldsCreationLowering(private val context: Context) : DeclarationTransformer {
    override val withLocalDeclarations: Boolean
        get() = true

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        if (!context.usesArcReferenceStorage || declaration !is IrField || declaration.arcReferenceKind() == null) return null

        val storageField = context.buildOrGetArcReferenceStorageField(declaration)
        val property = declaration.correspondingPropertySymbol?.owner
        return if (declaration !== storageField && (property == null || declaration.parent !== property.parent)) {
            listOf(storageField)
        } else {
            null
        }
    }
}

internal class ArcReferenceFieldsDeclarationLowering(private val context: Context) : DeclarationTransformer {
    override val withLocalDeclarations: Boolean
        get() = true

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        if (!context.usesArcReferenceStorage || declaration !is IrProperty || declaration.isFakeOverride) return null

        val backingField = declaration.backingField ?: return null
        if (backingField.arcReferenceKind() != null) {
            declaration.backingField = context.buildOrGetArcReferenceStorageField(backingField)
        }
        return null
    }
}

internal class ArcReferenceUsageLowering(private val context: Context) : BodyLoweringPass {
    override fun lower(irBody: IrBody, container: IrDeclaration) {
        if (!context.usesArcReferenceStorage) return

        val storageVariables = mutableMapOf<IrVariable, ArcStorageVariable>()

        irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitVariable(declaration: IrVariable): IrStatement {
                declaration.transformChildrenVoid(this)
                val kind = declaration.arcReferenceKind() ?: return declaration

                val storageVariable = buildVariable(
                    parent = declaration.parent,
                    startOffset = declaration.startOffset,
                    endOffset = declaration.endOffset,
                    origin = declaration.origin,
                    name = declaration.name,
                    type = context.irBuiltIns.anyNType,
                    isVar = declaration.isVar,
                ).also {
                    it.annotations = declaration.annotations.withoutArcReferenceAnnotations()
                    it.initializer = declaration.initializer?.let { value ->
                        context.arcReferenceStorageCall(declaration.symbol, value)
                    }
                }

                storageVariables[declaration] = ArcStorageVariable(storageVariable, declaration.type, kind)
                return storageVariable
            }
        })

        irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitGetValue(expression: IrGetValue): IrExpression {
                val storage = storageVariables[expression.symbol.owner] ?: return super.visitGetValue(expression)
                context.recordArcReferenceLoadAccessor(container)
                return context.arcReferenceLoad(
                    scope = container.symbol,
                    storage = IrGetValueImpl(
                        expression.startOffset,
                        expression.endOffset,
                        storage.variable.type,
                        storage.variable.symbol,
                        expression.origin,
                    ),
                    sourceType = storage.sourceType,
                    kind = storage.kind,
                )
            }

            override fun visitSetValue(expression: IrSetValue): IrExpression {
                expression.transformChildrenVoid(this)
                val storage = storageVariables[expression.symbol.owner] ?: return expression
                return IrSetValueImpl(
                    expression.startOffset,
                    expression.endOffset,
                    expression.type,
                    storage.variable.symbol,
                    context.arcReferenceStorageCall(container.symbol, expression.value),
                    expression.origin,
                )
            }

            override fun visitGetField(expression: IrGetField): IrExpression {
                expression.transformChildrenVoid(this)
                val originalField = expression.symbol.owner
                val kind = originalField.arcReferenceKind() ?: return expression
                context.recordArcReferenceLoadAccessor(container)
                val storageField = context.buildOrGetArcReferenceStorageField(originalField)
                val rawStorage = IrGetFieldImpl(
                    expression.startOffset,
                    expression.endOffset,
                    storageField.symbol,
                    storageField.type,
                    expression.receiver,
                    expression.origin,
                    expression.superQualifierSymbol,
                )
                return context.arcReferenceLoad(container.symbol, rawStorage, originalField.type, kind)
            }

            override fun visitSetField(expression: IrSetField): IrExpression {
                expression.transformChildrenVoid(this)
                val originalField = expression.symbol.owner
                if (originalField.arcReferenceKind() == null) return expression
                val storageField = context.buildOrGetArcReferenceStorageField(originalField)
                return IrSetFieldImpl(
                    expression.startOffset,
                    expression.endOffset,
                    storageField.symbol,
                    expression.receiver,
                    context.arcReferenceStorageCall(container.symbol, expression.value),
                    expression.type,
                    expression.origin,
                    expression.superQualifierSymbol,
                )
            }
        })
    }
}

private fun Context.recordArcReferenceLoadAccessor(container: IrDeclaration) {
    val function = container as? IrSimpleFunction ?: return
    if (function.returnType.binaryTypeIsReference()) {
        val signature = function.symbol.signature ?: function.symbol.privateSignature
        signature?.let { mapping.arcReferenceLoadAccessorSignatures += it }
        if (signature == null) {
            mapping.localArcReferenceLoadAccessorDeclarations +=
                    (function.attributeOwnerId as? IrSimpleFunction ?: function)
        }
    }
}

private data class ArcStorageVariable(
    val variable: IrVariable,
    val sourceType: org.jetbrains.kotlin.ir.types.IrType,
    val kind: ArcReferenceKind,
)

private val Context.usesArcReferenceStorage: Boolean
    get() = config.memoryModel == MemoryModel.ARC

private fun IrField.arcReferenceKind(): ArcReferenceKind? {
    if (origin == ARC_REFERENCE_STORAGE_FIELD_ORIGIN) return null
    val propertyAnnotations = correspondingPropertySymbol?.owner?.annotations.orEmpty()
    return arcReferenceKind(propertyAnnotations, annotations)
}

private fun IrVariable.arcReferenceKind(): ArcReferenceKind? = arcReferenceKind(annotations)

private fun arcReferenceKind(vararg annotationSets: List<IrConstructorCall>): ArcReferenceKind? = when {
    annotationSets.any { it.findAnnotation(KonanFqNames.arcWeak) != null } -> ArcReferenceKind.WEAK
    annotationSets.any { it.findAnnotation(KonanFqNames.arcUnowned) != null } -> ArcReferenceKind.UNOWNED
    else -> null
}

private fun List<IrConstructorCall>.withoutArcReferenceAnnotations(): List<IrConstructorCall> = filterNot {
    val annotationClass = it.symbol.owner.parent as? IrClass
    annotationClass?.fqNameWhenAvailable == KonanFqNames.arcWeak ||
            annotationClass?.fqNameWhenAvailable == KonanFqNames.arcUnowned
}

private fun Context.buildOrGetArcReferenceStorageField(originalField: IrField): IrField {
    if (originalField.origin == ARC_REFERENCE_STORAGE_FIELD_ORIGIN) return originalField

    return mapping.arcReferenceFieldToStorageField.getOrPut(originalField) {
        irFactory.buildField {
            updateFrom(originalField)
            origin = ARC_REFERENCE_STORAGE_FIELD_ORIGIN
            name = originalField.name
            type = irBuiltIns.anyNType
        }.apply {
            parent = originalField.parent
            correspondingPropertySymbol = originalField.correspondingPropertySymbol
            annotations = originalField.annotations.withoutArcReferenceAnnotations()
            initializer = originalField.initializer?.let { body ->
                IrExpressionBodyImpl(arcReferenceStorageCall(originalField.symbol, body.expression))
            }
        }
    }
}

private fun Context.arcReferenceStorageCall(scope: IrSymbol, value: IrExpression): IrExpression =
    createIrBuilder(scope, value.startOffset, value.endOffset).run {
        irCall(ir.symbols.arcReferenceStorage).apply {
            putValueArgument(0, value)
        }
    }

private fun Context.arcReferenceLoad(
    scope: IrSymbol,
    storage: IrExpression,
    sourceType: org.jetbrains.kotlin.ir.types.IrType,
    kind: ArcReferenceKind,
): IrExpression = createIrBuilder(scope, storage.startOffset, storage.endOffset).run {
    val load = irCall(
        when (kind) {
            ArcReferenceKind.WEAK -> ir.symbols.arcWeakReferenceLoad
            ArcReferenceKind.UNOWNED -> ir.symbols.arcUnownedReferenceLoad
        }
    ).apply {
        putValueArgument(0, storage)
    }
    irImplicitCast(load, sourceType)
}
