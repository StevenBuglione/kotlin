/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.KonanFqNames
import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.backend.konan.ir.konanLibrary
import org.jetbrains.kotlin.backend.konan.isFinalBinary
import org.jetbrains.kotlin.backend.common.lower.LocalDeclarationsLowering
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.util.Collections
import java.util.IdentityHashMap

/** Exact lowered Kotlin identities from which the physical producer and destination slots derive. */
internal data class ArcOwnedResultHeapStoreIRBindings<T : Any>(
    val function: T,
    val ownerClass: T,
    val receiverParameter: T,
    val resultParameter: T,
    val continuationResumeWith: T,
    val refClass: T,
    val capturedRefField: T,
    val capturedRefRead: T,
    val refElementField: T,
    val store: T,
    val resultBoxFunction: T,
    val producerCall: T,
    val resultRead: T,
    val trailingReturn: T,
    val unitCall: T,
)

/**
 * Structural seal shared by the real Kotlin-IR walker and adversarial identity-token tests.
 * Each bit represents a complete check performed by the walker, not a name-based assumption.
 */
internal data class ArcOwnedResultHeapStoreIRShape(
    val exactSourceContinuationOverride: Boolean,
    val exactResultValueParameter: Boolean,
    val exactCapturedRefStorage: Boolean,
    val exactMutableStrongRefElement: Boolean,
    val exactStdlibResultBox: Boolean,
    val exactDirectStoreExpression: Boolean,
    val exactTrailingUnitReturn: Boolean,
    val completeLoweredIRWalk: Boolean,
    val receiverEvaluatedBeforeProducer: Boolean,
    val directNormalAndExceptionalFrontiers: Boolean,
)

private fun ArcOwnedResultHeapStoreIRShape.isExact(): Boolean =
    exactSourceContinuationOverride && exactResultValueParameter && exactCapturedRefStorage &&
            exactMutableStrongRefElement && exactStdlibResultBox && exactDirectStoreExpression &&
            exactTrailingUnitReturn && completeLoweredIRWalk && receiverEvaluatedBeforeProducer &&
            directNormalAndExceptionalFrontiers

internal enum class ArcOwnedResultHeapStoreIRBindingRole {
    Function,
    Producer,
    Store,
    SourceResultSlot,
    DestinationElementSlot,
}

/** A unique role token which retains the exact Kotlin IR identity authorizing later emission. */
internal class ArcOwnedResultHeapStoreIRBinding<T : Any>(
    val irIdentity: T,
    val role: ArcOwnedResultHeapStoreIRBindingRole,
)

internal data class ArcOwnedResultHeapStoreIRSelection<T : Any>(
    val bindings: ArcOwnedResultHeapStoreIRBindings<T>,
    val exactBindings: Map<ArcOwnedResultHeapStoreIRBindingRole, ArcOwnedResultHeapStoreIRBinding<T>>,
    val ownership: ArcOwnedResultHeapStoreSelection<ArcOwnedResultHeapStoreIRBinding<T>>,
)

internal enum class ArcOwnedResultHeapStoreIRRejectionReason {
    InvalidStructuralProof,
    DuplicateStructuralIdentity,
    OwnershipAnalysisRejected,
}

internal data class ArcOwnedResultHeapStoreIRAdapterResult<T : Any>(
    val selection: ArcOwnedResultHeapStoreIRSelection<T>?,
    val rejection: ArcOwnedResultHeapStoreIRRejectionReason?,
    val ownershipRejection: ArcOwnedResultHeapStoreRejectionReason? = null,
)

/**
 * Convert a completely authenticated lowered-IR inventory into the generic ownership proof.
 * Synthetic source/destination role tokens are deliberately distinct even though their physical
 * addresses are materialized only by LLVM codegen.
 */
internal fun <T : Any> adaptVerifiedOwnedResultHeapStoreIR(
    bindings: ArcOwnedResultHeapStoreIRBindings<T>,
    mode: ArcOwnedResultHeapStoreMode,
    shape: ArcOwnedResultHeapStoreIRShape,
): ArcOwnedResultHeapStoreIRAdapterResult<T> {
    if (!shape.isExact()) return ArcOwnedResultHeapStoreIRAdapterResult(
        null,
        ArcOwnedResultHeapStoreIRRejectionReason.InvalidStructuralProof,
    )

    val identities = listOf(
        bindings.function,
        bindings.ownerClass,
        bindings.receiverParameter,
        bindings.resultParameter,
        bindings.continuationResumeWith,
        bindings.refClass,
        bindings.capturedRefField,
        bindings.capturedRefRead,
        bindings.refElementField,
        bindings.store,
        bindings.resultBoxFunction,
        bindings.producerCall,
        bindings.resultRead,
        bindings.trailingReturn,
        bindings.unitCall,
    )
    val unique = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    if (identities.any { !unique.add(it) }) return ArcOwnedResultHeapStoreIRAdapterResult(
        null,
        ArcOwnedResultHeapStoreIRRejectionReason.DuplicateStructuralIdentity,
    )

    val exactBindings = ArcOwnedResultHeapStoreIRBindingRole.values().associateWith { role ->
        val identity = when (role) {
            ArcOwnedResultHeapStoreIRBindingRole.Function -> bindings.function
            ArcOwnedResultHeapStoreIRBindingRole.Producer,
            ArcOwnedResultHeapStoreIRBindingRole.SourceResultSlot -> bindings.producerCall
            ArcOwnedResultHeapStoreIRBindingRole.Store,
            ArcOwnedResultHeapStoreIRBindingRole.DestinationElementSlot -> bindings.store
        }
        ArcOwnedResultHeapStoreIRBinding(identity, role)
    }
    val analysis = ArcOwnedResultHeapStoreAnalysis.select(
        ArcOwnedResultHeapStoreCandidate(
            functionBinding = exactBindings.getValue(ArcOwnedResultHeapStoreIRBindingRole.Function),
            producerBinding = exactBindings.getValue(ArcOwnedResultHeapStoreIRBindingRole.Producer),
            storeBinding = exactBindings.getValue(ArcOwnedResultHeapStoreIRBindingRole.Store),
            sourceSlotBinding = exactBindings.getValue(ArcOwnedResultHeapStoreIRBindingRole.SourceResultSlot),
            destinationBinding = exactBindings.getValue(ArcOwnedResultHeapStoreIRBindingRole.DestinationElementSlot),
            mode = mode,
            identity = ArcOwnedResultHeapStoreIdentityProof(
                exactContinuationResumeWithOverride = true,
                exactResultValueParameter = true,
                exactStdlibResultBoxProducer = true,
                exactCapturedRefField = true,
                exactMutableStrongRefValueDestination = true,
                destinationIsInitialized = true,
                destinationIsNonVolatile = true,
                destinationIsNotWeakOrUnowned = true,
            ),
            lifetime = ArcOwnedResultHeapStoreLifetimeProof(
                producerWritesOneOwnedResultToExactSourceSlotOnEveryNormalReturn = true,
                producerExceptionalEdgeLeavesSourceSlotEmpty = true,
                sourceSlotDominatesStoreAndFrameCleanup = true,
                storePostDominatesProducerNormalSuccessor = true,
                producerResultHasExactlyOneUse = true,
                sourceSlotHasNoInterveningWriteOrEscape = true,
                destinationOwnerRemainsGuaranteedThroughStore = true,
                destinationAddressDoesNotEscape = true,
                frameCleanupPostDominatesStore = true,
            ),
            interveningEffects = listOf(
                ArcOwnedResultHeapStoreInterveningEffect.DestinationAddressProjection,
            ),
        ),
    )
    val ownership = analysis.selection ?: return ArcOwnedResultHeapStoreIRAdapterResult(
        null,
        ArcOwnedResultHeapStoreIRRejectionReason.OwnershipAnalysisRejected,
        analysis.rejection,
    )
    return ArcOwnedResultHeapStoreIRAdapterResult(
        ArcOwnedResultHeapStoreIRSelection(bindings, exactBindings, ownership),
        null,
    )
}

/** Exact real-IR selection returned to the two later planning/emission integration seams. */
internal data class ArcOwnedResultHeapStoreKotlinIRSelection(
    val function: IrSimpleFunction,
    val ownerClass: IrClass,
    val receiverParameter: IrValueParameter,
    val resultParameter: IrValueParameter,
    val capturedRefField: IrField,
    val capturedRefRead: IrGetField,
    val refElementField: IrField,
    val store: IrSetField,
    val resultBoxFunction: IrSimpleFunction,
    val producerCall: IrCall,
    val resultRead: IrGetValue,
    val trailingReturn: IrReturn,
    val unitCall: IrCall,
    val selection: ArcOwnedResultHeapStoreIRSelection<IrElement>,
)

/**
 * Authenticate only the two-statement source-generated Continuation.resumeWith body observed in
 * the ARC coroutine benchmark after all Native lowerings:
 *
 *     capturedRef.element = <Result-box>(result)
 *     return theUnitInstance()
 *
 * The producer is the direct store value. Therefore it has no normal-edge use except the store,
 * and a throwing producer reaches frame cleanup before the result slot ever owns a value.
 */
internal fun selectVerifiedOwnedResultHeapStore(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
): ArcOwnedResultHeapStoreKotlinIRSelection? {
    val context = generationState.context
    val config = context.config
    if (function.name.asString() != "resumeWith") return null
    val mode = ArcOwnedResultHeapStoreMode(
        arcEnabled = context.memoryModel == MemoryModel.ARC,
        linuxX64 = config.target == KonanTarget.LINUX_X64,
        finalBinary = config.isFinalBinary,
        optimizationsEnabled = config.optimizationsEnabled,
        debugInfoDisabled = !context.shouldContainAnyDebugInfo(),
        diagnosticsDisabled = !config.arcDiagnosticsEnabled,
        sanitizerDisabled = config.sanitizer == null && !config.undefinedBehaviorSanitizer,
        coverageDisabled = !generationState.coverage.enabled,
        nonSuspendFunction = !function.isArcSuspendLike(),
        nonExternalFunction = !function.isExternal,
    )
    if (!mode.isOwnedResultProductionMode()) return null

    fun reject(reason: String): ArcOwnedResultHeapStoreKotlinIRSelection? {
        context.log {
            "ARC owned Result-box heap-store selector ${function.fqNameForIrSerialization.asString()}: $reason"
        }
        return null
    }

    val stdlib = context.stdlibModule.konanLibrary ?: return reject("missing stdlib identity")
    val owner = function.parent as? IrClass ?: return reject("class owner")
    val receiver = function.dispatchReceiverParameter ?: return reject("dispatch receiver")
    val resultParameter = function.valueParameters.singleOrNull() ?: return reject("single Result parameter")
    val continuationClass = context.ir.symbols.continuationClass.owner
    val resultClass = context.ir.symbols.kotlinResult.owner
    val refClass = context.ir.symbols.refClass.owner
    if (owner.konanLibrary != null || owner.modality != Modality.FINAL || owner.kind != ClassKind.CLASS ||
        owner.superTypes.singleOrNull()?.classifierOrNull != continuationClass.symbol ||
        continuationClass.konanLibrary !== stdlib ||
        continuationClass.fqNameForIrSerialization.asString() != "kotlin.coroutines.Continuation" ||
        continuationClass.kind != ClassKind.INTERFACE ||
        resultClass.konanLibrary !== stdlib || resultClass.fqNameForIrSerialization.asString() != "kotlin.Result" ||
        !resultClass.isValue || resultClass.modality != Modality.FINAL ||
        refClass.konanLibrary !== stdlib || refClass.fqNameForIrSerialization.asString() != "kotlin.native.internal.Ref" ||
        refClass.modality != Modality.FINAL
    ) return reject("owner/Continuation/Result/Ref declaration identity")

    val continuationResumeWith = continuationClass.functions.singleOrNull {
        it.name.asString() == "resumeWith" && it.parent === continuationClass && it.konanLibrary === stdlib &&
                it.modality == Modality.ABSTRACT && it.dispatchReceiverParameter != null &&
                it.extensionReceiverParameter == null && it.valueParameters.singleOrNull()?.type?.classifierOrNull == resultClass.symbol &&
                it.typeParameters.isEmpty() && it.returnType.isUnit() && !it.isExternal && !it.isSuspend
    } ?: return reject("Continuation.resumeWith declaration")
    if (function.overriddenSymbols.singleOrNull() != continuationResumeWith.symbol ||
        function.konanLibrary != null || function.modality != Modality.OPEN || function.returnType.isUnit().not() ||
        function.extensionReceiverParameter != null || function.typeParameters.isNotEmpty() ||
        resultParameter.type.classifierOrNull != resultClass.symbol ||
        !receiver.type.binaryTypeIsReference()
    ) return reject("source resumeWith override/signature")

    val refElementField = refClass.declarations.filterIsInstance<IrField>().singleOrNull {
        it.name.asString() == "element" && it.parent === refClass && it.konanLibrary === stdlib &&
                !it.isStatic && !it.isFinal && it.visibility == DescriptorVisibilities.PRIVATE &&
                it.type.binaryTypeIsReference() && !it.hasOwnedResultUnsupportedStorageAnnotation()
    } ?: return reject("Ref.element declaration")
    val capturedRefFields = owner.declarations.filterIsInstance<IrField>().filter {
        it.origin == LocalDeclarationsLowering.DECLARATION_ORIGIN_FIELD_FOR_CAPTURED_VALUE &&
                it.parent === owner && !it.isStatic && it.isFinal &&
                it.visibility == DescriptorVisibilities.PRIVATE && it.type.classifierOrNull == refClass.symbol &&
                (it.type as? IrSimpleType)?.arguments?.singleOrNull()?.typeOrNull?.classifierOrNull == resultClass.symbol &&
                !it.hasOwnedResultUnsupportedStorageAnnotation()
    }
    val capturedRefField = capturedRefFields.singleOrNull() ?: return reject("single captured Ref<Result?> field")

    val body = function.body as? IrBlockBody ?: return reject("block body")
    if (body.statements.size != 2) return reject("two-statement body")
    val store = body.statements[0] as? IrSetField ?: return reject("direct Ref.element store")
    val trailingReturn = body.statements[1] as? IrReturn ?: return reject("trailing Unit return")
    if (store.symbol.owner !== refElementField || store.superQualifierSymbol != null) {
        return reject("Ref.element store identity")
    }
    val capturedRefRead = store.receiver as? IrGetField ?: return reject("direct captured Ref receiver")
    val ownerRead = capturedRefRead.receiver as? IrGetValue ?: return reject("captured Ref owner read")
    if (capturedRefRead.symbol.owner !== capturedRefField || ownerRead.symbol != receiver.symbol) {
        return reject("captured Ref receiver identity")
    }

    val producerCall = store.value as? IrCall ?: return reject("direct Result-box producer")
    val resultBoxFunction = producerCall.symbol.owner
    val resultRead = producerCall.getValueArgument(0) as? IrGetValue ?: return reject("direct result read")
    if (resultBoxFunction.konanLibrary !== stdlib ||
        resultBoxFunction.fqNameForIrSerialization.asString() != "kotlin.<Result-box>" ||
        resultBoxFunction.parent !is org.jetbrains.kotlin.ir.declarations.IrFile ||
        resultBoxFunction.dispatchReceiverParameter != null || resultBoxFunction.extensionReceiverParameter != null ||
        resultBoxFunction.valueParameters.singleOrNull()?.type?.classifierOrNull != resultClass.symbol ||
        resultBoxFunction.typeParameters.size != 1 || !resultBoxFunction.returnType.isAny() ||
        resultBoxFunction.isExternal || resultBoxFunction.isSuspend || resultBoxFunction.modality != Modality.FINAL ||
        producerCall.valueArgumentsCount != 1 || resultRead.symbol != resultParameter.symbol ||
        producerCall.type.binaryTypeIsReference()
            .not()
    ) return reject("stdlib Result-box identity/shape")

    val unitCall = trailingReturn.value as? IrCall ?: return reject("Unit return call")
    if (trailingReturn.returnTargetSymbol != function.symbol || unitCall.symbol != context.ir.symbols.theUnitInstance ||
        unitCall.dispatchReceiver != null || unitCall.extensionReceiver != null || unitCall.valueArgumentsCount != 0
    ) return reject("canonical Unit return")

    val bindings = ArcOwnedResultHeapStoreIRBindings<IrElement>(
        function,
        owner,
        receiver,
        resultParameter,
        continuationResumeWith,
        refClass,
        capturedRefField,
        capturedRefRead,
        refElementField,
        store,
        resultBoxFunction,
        producerCall,
        resultRead,
        trailingReturn,
        unitCall,
    )
    val adapted = adaptVerifiedOwnedResultHeapStoreIR(
        bindings,
        mode,
        ArcOwnedResultHeapStoreIRShape(
            exactSourceContinuationOverride = true,
            exactResultValueParameter = true,
            exactCapturedRefStorage = true,
            exactMutableStrongRefElement = true,
            exactStdlibResultBox = true,
            exactDirectStoreExpression = true,
            exactTrailingUnitReturn = true,
            completeLoweredIRWalk = true,
            receiverEvaluatedBeforeProducer = true,
            directNormalAndExceptionalFrontiers = true,
        ),
    )
    val selection = adapted.selection ?: return reject(
        "ownership adapter rejection=${adapted.rejection}/${adapted.ownershipRejection}",
    )
    return ArcOwnedResultHeapStoreKotlinIRSelection(
        function,
        owner,
        receiver,
        resultParameter,
        capturedRefField,
        capturedRefRead,
        refElementField,
        store,
        resultBoxFunction,
        producerCall,
        resultRead,
        trailingReturn,
        unitCall,
        selection,
    )
}

/** Module-level inventory used by verification and later ownership-plan construction. */
internal fun selectVerifiedOwnedResultHeapStores(
    generationState: NativeGenerationState,
    module: IrModuleFragment,
): List<ArcOwnedResultHeapStoreKotlinIRSelection> {
    val selected = mutableListOf<ArcOwnedResultHeapStoreKotlinIRSelection>()
    module.files.forEach { file ->
        file.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                selectVerifiedOwnedResultHeapStore(generationState, declaration)?.let { selected += it }
                declaration.acceptChildrenVoid(this)
            }
        })
    }
    return selected
}

private fun ArcOwnedResultHeapStoreMode.isOwnedResultProductionMode(): Boolean =
    arcEnabled && linuxX64 && finalBinary && optimizationsEnabled && debugInfoDisabled && diagnosticsDisabled &&
            sanitizerDisabled && coverageDisabled && nonSuspendFunction && nonExternalFunction

private fun IrField.hasOwnedResultUnsupportedStorageAnnotation(): Boolean =
    hasAnnotation(KonanFqNames.arcWeak) || hasAnnotation(KonanFqNames.arcUnowned) ||
            hasAnnotation(KonanFqNames.volatile)
