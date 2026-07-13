/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.backend.konan.ir.konanLibrary
import org.jetbrains.kotlin.backend.konan.isFinalBinary
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstantObject
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.util.Collections
import java.util.IdentityHashMap

/** Every exact lowered declaration and expression that authenticates the permanent return. */
internal data class ArcCoroutineEmptyContextReturnIRBindings<T : Any>(
    val function: T,
    val restrictedContinuationClass: T,
    val continuationClass: T,
    val continuationContextGetter: T,
    val returned: T,
    val objectGetterCall: T,
    val emptyContextClass: T,
    val objectProperty: T,
    val objectGetter: T,
    val rootField: T,
    val rootGetterReturn: T,
    val rootLoad: T,
    val constantObject: T,
    val constantConstructor: T,
)

/** Facts proven by the real IR walker before entering the semantic ownership selector. */
internal data class ArcCoroutineEmptyContextReturnIRShape(
    val exactRestrictedContinuationDeclaration: Boolean,
    val exactContinuationContextOverride: Boolean,
    val exactSingleReturnBody: Boolean,
    val exactSyntheticObjectGetterCall: Boolean,
    val exactEmptyCoroutineContextDeclaration: Boolean,
    val exactPrivateFinalStaticRoot: Boolean,
    val exactRootGetterBody: Boolean,
    val exactConstantObjectInitializer: Boolean,
    val noRootWrites: Boolean,
    val completeLoweredIRWalk: Boolean,
)

private fun ArcCoroutineEmptyContextReturnIRShape.isExact(): Boolean =
    exactRestrictedContinuationDeclaration && exactContinuationContextOverride &&
            exactSingleReturnBody && exactSyntheticObjectGetterCall &&
            exactEmptyCoroutineContextDeclaration && exactPrivateFinalStaticRoot &&
            exactRootGetterBody && exactConstantObjectInitializer && noRootWrites &&
            completeLoweredIRWalk

internal data class ArcCoroutineEmptyContextReturnIRSelection<T : Any>(
    val bindings: ArcCoroutineEmptyContextReturnIRBindings<T>,
    val ownership: ArcCoroutineEmptyContextReturnSelection<T>,
)

internal enum class ArcCoroutineEmptyContextReturnIRRejectionReason {
    InvalidStructuralProof,
    DuplicateStructuralIdentity,
    OwnershipAnalysisRejected,
}

internal data class ArcCoroutineEmptyContextReturnIRSelectorResult<T : Any>(
    val selection: ArcCoroutineEmptyContextReturnIRSelection<T>?,
    val rejection: ArcCoroutineEmptyContextReturnIRRejectionReason?,
    val ownershipRejection: ArcCoroutineEmptyContextReturnRejectionReason? = null,
)

/** Convert a sealed lowered-IR inventory into the already verified immortal-return proof. */
internal fun <T : Any> adaptVerifiedCoroutineEmptyContextReturnIR(
    bindings: ArcCoroutineEmptyContextReturnIRBindings<T>,
    mode: ArcCoroutineEmptyContextReturnMode,
    shape: ArcCoroutineEmptyContextReturnIRShape,
): ArcCoroutineEmptyContextReturnIRSelectorResult<T> {
    if (!shape.isExact()) return ArcCoroutineEmptyContextReturnIRSelectorResult(
        null,
        ArcCoroutineEmptyContextReturnIRRejectionReason.InvalidStructuralProof,
    )

    val identities = listOf(
        bindings.function,
        bindings.restrictedContinuationClass,
        bindings.continuationClass,
        bindings.continuationContextGetter,
        bindings.returned,
        bindings.objectGetterCall,
        bindings.emptyContextClass,
        bindings.objectProperty,
        bindings.objectGetter,
        bindings.rootField,
        bindings.rootGetterReturn,
        bindings.rootLoad,
        bindings.constantObject,
        bindings.constantConstructor,
    )
    val unique = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    if (identities.any { !unique.add(it) }) return ArcCoroutineEmptyContextReturnIRSelectorResult(
        null,
        ArcCoroutineEmptyContextReturnIRRejectionReason.DuplicateStructuralIdentity,
    )

    val analysis = ArcCoroutineEmptyContextReturnAnalysis.select(
        ArcCoroutineEmptyContextReturnCandidate(
            getterBinding = bindings.function,
            objectBinding = bindings.constantObject,
            returnBinding = bindings.returned,
            mode = mode,
            identity = ArcCoroutineEmptyContextReturnIdentityProof(
                exactStdlibLibrary = true,
                exactRestrictedContinuationImplClass = true,
                exactContextGetterOverride = true,
                exactCoroutineContextReturnType = true,
                exactEmptyCoroutineContextObject = true,
                finalPermanentObject = true,
                runtimeAllocatesObjectAsImmortal = true,
            ),
            body = ArcCoroutineEmptyContextReturnBodyProof(
                singleEntryBlock = true,
                singleReturn = true,
                returnTargetsExactGetter = true,
                returnValueIsExactObjectLoad = true,
                noReceiverOrParameterUse = true,
                noMutableLoad = true,
                noNestedDeclaration = true,
                noExceptionalEdge = true,
            ),
            effects = listOf(
                ArcCoroutineEmptyContextReturnEffect.PermanentObjectLoad,
                ArcCoroutineEmptyContextReturnEffect.ReturnSlotProjection,
            ),
        ),
    )
    val ownership = analysis.selection ?: return ArcCoroutineEmptyContextReturnIRSelectorResult(
        null,
        ArcCoroutineEmptyContextReturnIRRejectionReason.OwnershipAnalysisRejected,
        analysis.rejection,
    )
    return ArcCoroutineEmptyContextReturnIRSelectorResult(
        ArcCoroutineEmptyContextReturnIRSelection(bindings, ownership),
        null,
    )
}

internal data class ArcCoroutineEmptyContextReturnKotlinIRSelection(
    val function: IrSimpleFunction,
    val returned: IrReturn,
    val objectGetterCall: IrCall,
    val emptyContextClass: IrClass,
    val objectGetter: IrSimpleFunction,
    val rootField: IrField,
    val rootLoad: IrGetField,
    val constantObject: IrConstantObject,
    val selection: ArcCoroutineEmptyContextReturnIRSelection<IrElement>,
)

/**
 * Authenticate the post-ObjectClassLowering form of the exact Native stdlib getter:
 *
 *     RestrictedContinuationImpl.context -> EmptyCoroutineContext.instance()
 *     EmptyCoroutineContext.instance() -> static final constant-object root
 *
 * [IrConstantObject] is emitted by Kotlin/Native static data with a permanent object-header tag;
 * the root has no IR writes, so the result remains immortal for the entire process.
 */
internal fun selectVerifiedCoroutineEmptyContextImmortalReturn(
    generationState: NativeGenerationState,
    module: IrModuleFragment,
): ArcCoroutineEmptyContextReturnKotlinIRSelection? {
    val context = generationState.context
    val config = context.config
    val mode = ArcCoroutineEmptyContextReturnMode(
        arcEnabled = context.memoryModel == MemoryModel.ARC,
        linuxX64 = config.target == KonanTarget.LINUX_X64,
        finalBinary = config.isFinalBinary,
        optimizationsEnabled = config.optimizationsEnabled,
        debugInfoDisabled = !context.shouldContainAnyDebugInfo(),
        diagnosticsDisabled = !config.arcDiagnosticsEnabled,
        sanitizerDisabled = config.sanitizer == null && !config.undefinedBehaviorSanitizer,
        coverageDisabled = !generationState.coverage.enabled,
        nonSuspendFunction = true,
        nonExternalFunction = true,
    )
    val stdlib = context.stdlibModule.konanLibrary ?: return null
    val restricted = context.ir.symbols.restrictedContinuationImpl.owner
    val continuation = context.ir.symbols.continuationClass.owner
    if (restricted.konanLibrary !== stdlib || continuation.konanLibrary !== stdlib ||
        restricted.fqNameForIrSerialization.asString() !=
            "kotlin.coroutines.native.internal.RestrictedContinuationImpl" ||
        continuation.fqNameForIrSerialization.asString() != "kotlin.coroutines.Continuation" ||
        restricted.kind != ClassKind.CLASS || restricted.modality != Modality.ABSTRACT ||
        continuation.kind != ClassKind.INTERFACE ||
        restricted.superTypes.singleOrNull()?.classifierOrNull !=
            context.ir.symbols.baseContinuationImpl
    ) return null

    fun reject(reason: String): ArcCoroutineEmptyContextReturnKotlinIRSelection? {
        context.log { "ARC EmptyCoroutineContext immortal-return selector rejected: $reason" }
        return null
    }

    val continuationContextGetter = continuation.properties.singleOrNull {
        it.name.asString() == "context"
    }?.getter?.takeIf {
        it.parent === continuation && it.konanLibrary === stdlib &&
                it.modality == Modality.ABSTRACT && !it.isExternal && !it.isSuspend &&
                it.dispatchReceiverParameter != null && it.extensionReceiverParameter == null &&
                it.valueParameters.isEmpty() && it.typeParameters.isEmpty() &&
                it.returnType.binaryTypeIsReference()
    } ?: return reject("Continuation.context declaration")

    val function = restricted.properties.singleOrNull { it.name.asString() == "context" }
        ?.getter?.takeIf {
            it.parent === restricted && it.konanLibrary === stdlib &&
                    it.modality == Modality.OPEN && !it.isExternal && !it.isSuspend &&
                    it.dispatchReceiverParameter?.type?.classifierOrNull == restricted.symbol &&
                    it.extensionReceiverParameter == null && it.valueParameters.isEmpty() &&
                    it.typeParameters.isEmpty() &&
                    it.returnType.classifierOrNull == continuationContextGetter.returnType.classifierOrNull &&
                    it.overriddenSymbols.singleOrNull() == continuationContextGetter.symbol
        } ?: return reject("RestrictedContinuationImpl.context override")

    val returned = (function.body as? IrBlockBody)?.statements?.singleOrNull() as? IrReturn
        ?: return reject("single return body")
    if (returned.returnTargetSymbol != function.symbol) return reject("return target")
    val objectGetterCall = returned.value as? IrCall ?: return reject("object getter call")
    if (objectGetterCall.dispatchReceiver != null || objectGetterCall.extensionReceiver != null ||
        objectGetterCall.valueArgumentsCount != 0 || objectGetterCall.typeArgumentsCount != 0
    ) return reject("zero-argument object getter call")

    val objectGetter = objectGetterCall.symbol.owner
    val objectProperty = objectGetter.correspondingPropertySymbol?.owner
        ?: return reject("object getter property")
    val emptyContext = objectGetter.parent as? IrClass ?: return reject("object getter class")
    if (emptyContext.konanLibrary !== stdlib ||
        emptyContext.fqNameForIrSerialization.asString() != "kotlin.coroutines.EmptyCoroutineContext" ||
        emptyContext.kind != ClassKind.OBJECT || emptyContext.modality != Modality.FINAL ||
        emptyContext.visibility != DescriptorVisibilities.PUBLIC ||
        objectProperty.parent !== emptyContext || objectProperty.getter !== objectGetter ||
        objectGetter.konanLibrary !== stdlib || objectGetter.modality != Modality.FINAL ||
        objectGetter.isExternal || objectGetter.isSuspend ||
        objectGetter.dispatchReceiverParameter != null || objectGetter.extensionReceiverParameter != null ||
        objectGetter.valueParameters.isNotEmpty() || objectGetter.typeParameters.isNotEmpty() ||
        objectGetter.returnType.classifierOrNull != emptyContext.symbol ||
        objectGetterCall.type.classifierOrNull != emptyContext.symbol
    ) return reject("EmptyCoroutineContext object/getter identity")

    val rootField = objectProperty.backingField?.takeIf {
        it.parent === emptyContext && it.konanLibrary === stdlib && it.isStatic && it.isFinal &&
                it.visibility == DescriptorVisibilities.PRIVATE &&
                it.type.classifierOrNull == emptyContext.symbol
    } ?: return reject("private final static root")
    val rootGetterReturn = (objectGetter.body as? IrBlockBody)?.statements?.singleOrNull() as? IrReturn
        ?: return reject("root getter body")
    val rootLoad = rootGetterReturn.value as? IrGetField ?: return reject("root field load")
    if (rootGetterReturn.returnTargetSymbol != objectGetter.symbol ||
        rootLoad.symbol.owner !== rootField || rootLoad.receiver != null
    ) return reject("exact root getter")

    val constantObject = rootField.initializer?.expression as? IrConstantObject
        ?: return reject("constant-object root initializer")
    val constantConstructor = constantObject.constructor.owner
    if (constantConstructor.parent !== emptyContext || !constantConstructor.isPrimary ||
        constantConstructor.visibility != DescriptorVisibilities.PRIVATE ||
        constantConstructor.valueParameters.isNotEmpty() || constantObject.valueArguments.isNotEmpty()
    ) return reject("constant-object constructor")

    var rootWrites = 0
    module.files.forEach { file ->
        file.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
            override fun visitSetField(expression: IrSetField) {
                if (expression.symbol.owner === rootField) rootWrites++
                expression.acceptChildrenVoid(this)
            }
        })
    }
    if (rootWrites != 0) return reject("mutable root write census=$rootWrites")

    val bindings = ArcCoroutineEmptyContextReturnIRBindings<IrElement>(
        function,
        restricted,
        continuation,
        continuationContextGetter,
        returned,
        objectGetterCall,
        emptyContext,
        objectProperty,
        objectGetter,
        rootField,
        rootGetterReturn,
        rootLoad,
        constantObject,
        constantConstructor,
    )
    val adapted = adaptVerifiedCoroutineEmptyContextReturnIR(
        bindings,
        mode,
        ArcCoroutineEmptyContextReturnIRShape(
            exactRestrictedContinuationDeclaration = true,
            exactContinuationContextOverride = true,
            exactSingleReturnBody = true,
            exactSyntheticObjectGetterCall = true,
            exactEmptyCoroutineContextDeclaration = true,
            exactPrivateFinalStaticRoot = true,
            exactRootGetterBody = true,
            exactConstantObjectInitializer = true,
            noRootWrites = true,
            completeLoweredIRWalk = true,
        ),
    )
    val selection = adapted.selection ?: return reject(
        "adapter rejection=${adapted.rejection}/${adapted.ownershipRejection}",
    )
    return ArcCoroutineEmptyContextReturnKotlinIRSelection(
        function,
        returned,
        objectGetterCall,
        emptyContext,
        objectGetter,
        rootField,
        rootLoad,
        constantObject,
        selection,
    )
}
