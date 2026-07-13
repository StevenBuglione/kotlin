/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.InteropFqNames
import org.jetbrains.kotlin.backend.konan.KonanFqNames
import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.backend.konan.ir.konanLibrary
import org.jetbrains.kotlin.backend.konan.isFinalBinary
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrCatch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isByte
import org.jetbrains.kotlin.ir.types.isByteArray
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.name.Name
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Runtime entry point used by the eventual codegen integration. It is deliberately ARC-only:
 * every other memory model continues to lower the public kotlinx.cinterop API unchanged.
 */
internal const val ARC_PINNED_BYTE_ARRAY_ADDRESS_HELPER =
    "Kotlin_Interop_getPinnedByteArrayAddressArc"

/**
 * Fail-closed compilation boundary for the dependent interior-pointer projection.
 *
 * Sanitizer and coverage builds retain the ordinary Kotlin call chain so their instrumentation
 * and source attribution remain exact. The same is true of debug and ARC-diagnostic builds.
 */
internal data class ArcPinnedByteArrayAddressCompilationMode(
    val arcEnabled: Boolean,
    val linuxX64: Boolean,
    val finalBinary: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val diagnosticsDisabled: Boolean,
    val sanitizerDisabled: Boolean,
    val coverageDisabled: Boolean,
    val nonSuspendFunction: Boolean,
    val nonExternalFunction: Boolean,
    val sourceFunction: Boolean,
)

internal fun ArcPinnedByteArrayAddressCompilationMode.isAuthorized(): Boolean =
    arcEnabled && linuxX64 && finalBinary && optimizationsEnabled && debugInfoDisabled &&
            diagnosticsDisabled && sanitizerDisabled && coverageDisabled && nonSuspendFunction &&
            nonExternalFunction && sourceFunction

/**
 * Declaration facts which the lowered-IR selector must prove by symbol and library identity.
 * Names alone are never sufficient authorization.
 */
internal data class ArcPinnedByteArrayAddressDeclarationProof<T : Any>(
    val interopLibraryBinding: T,
    val pinnedClassBinding: T,
    val stablePointerFieldBinding: T,
    val pinFunctionBinding: T,
    val addressOfFunctionBinding: T,
    val unpinFunctionBinding: T,
    val exactInteropLibraryIdentity: Boolean,
    val exactKotlinxCinteropPackage: Boolean,
    val exactFinalPinnedByteArrayInstantiation: Boolean,
    val exactPrivateFinalNativePointerField: Boolean,
    val exactPinSignatureAndBody: Boolean,
    val exactPinnedByteArrayAddressOfSignatureAndBody: Boolean,
    val exactUnpinSignatureAndBody: Boolean,
    val canonicalByteArrayAddressIntrinsic: Boolean,
)

/**
 * One complete lowered usePinned lifetime. Bindings are opaque IR identities, not source names.
 */
internal data class ArcPinnedByteArrayAddressLifetimeProof<T : Any>(
    val functionBinding: T,
    val pinCallBinding: T,
    val holderBinding: T,
    val addressOfCallBinding: T,
    val receiverReadBinding: T,
    val indexBinding: T,
    val pointerUseBinding: T,
    val normalUnpinCallBinding: T,
    val exceptionalUnpinCallBinding: T,
    val exactSinglePinAndTwoPathExclusiveUnpins: Boolean,
    val pinDominatesProjection: Boolean,
    val projectionDominatesPointerUse: Boolean,
    val pointerUsePostDominatesProjection: Boolean,
    val unpinPostDominatesPointerUse: Boolean,
    val noUnpinOrHandleMutationBeforeLastUse: Boolean,
    val holderDoesNotEscapeOrAlias: Boolean,
    val stableHandleDoesNotEscapeOrAlias: Boolean,
    val pointerHasOneNonEscapingUse: Boolean,
    val noNestedFunctionOrSuspensionBoundary: Boolean,
    val noUnknownCallBetweenProjectionAndUse: Boolean,
    val allNormalAndExceptionalEdgesPreserveCleanup: Boolean,
)

/** The exact evaluation and throwing contract preserved by the ARC runtime helper. */
internal data class ArcPinnedByteArrayAddressSemanticsProof(
    val receiverEvaluatedExactlyOnce: Boolean,
    val indexEvaluatedExactlyOnce: Boolean,
    val receiverBeforeIndex: Boolean,
    val helperDelegatesToCanonicalBoundsCheck: Boolean,
    val originalExceptionalSuccessorPreserved: Boolean,
    val pointerElementTypeIsByteVar: Boolean,
)

internal data class ArcPinnedByteArrayAddressCandidate<T : Any>(
    val mode: ArcPinnedByteArrayAddressCompilationMode,
    val declarations: ArcPinnedByteArrayAddressDeclarationProof<T>,
    val lifetime: ArcPinnedByteArrayAddressLifetimeProof<T>,
    val semantics: ArcPinnedByteArrayAddressSemanticsProof,
    val completeLoweredIRWalk: Boolean,
)

internal enum class ArcPinnedByteArrayAddressRejectionReason {
    UnsupportedCompilationMode,
    DeclarationIdentityMismatch,
    IncompleteLoweredIRWalk,
    InvalidStableHandleLifetime,
    EscapingInteriorPointer,
    UnsupportedControlFlow,
    EvaluationOrExceptionMismatch,
    DuplicateStructuralBinding,
}

internal data class ArcPinnedByteArrayAddressRejection<T : Any>(
    val reason: ArcPinnedByteArrayAddressRejectionReason,
    val binding: T,
)

/**
 * These are the three code-generation obligations. They mirror Swift's mark_dependence model:
 * the trivial interior pointer is usable only while its non-trivial stable-handle base is live.
 */
internal enum class ArcPinnedByteArrayAddressActionId {
    BeginStableHandleDependence,
    ReplaceProjectionWithArcHelper,
    EndStableHandleDependence,
}

internal data class ArcPinnedByteArrayAddressEmissionAction<T : Any>(
    val id: ArcPinnedByteArrayAddressActionId,
    val bindingIdentities: List<T>,
)

internal data class ArcPinnedByteArrayAddressPlan<T : Any>(
    val functionBinding: T,
    val holderBinding: T,
    val stablePointerFieldBinding: T,
    val addressOfCallBinding: T,
    val pointerUseBinding: T,
    val normalUnpinCallBinding: T,
    val exceptionalUnpinCallBinding: T,
    val runtimeHelper: String,
    val actions: Map<ArcPinnedByteArrayAddressActionId, ArcPinnedByteArrayAddressEmissionAction<T>>,
)

internal data class ArcPinnedByteArrayAddressSelectionResult<T : Any>(
    val plan: ArcPinnedByteArrayAddressPlan<T>?,
    val rejections: List<ArcPinnedByteArrayAddressRejection<T>>,
)

/**
 * Selects only the canonical inline usePinned web. This independent proof model lets the real IR
 * walker and LLVM emitter be added separately without duplicating safety policy at either seam.
 */
internal object ArcPinnedByteArrayAddressAnalysis {
    fun <T : Any> select(
        candidate: ArcPinnedByteArrayAddressCandidate<T>,
    ): ArcPinnedByteArrayAddressSelectionResult<T> {
        val lifetime = candidate.lifetime
        fun reject(reason: ArcPinnedByteArrayAddressRejectionReason, binding: T) =
            ArcPinnedByteArrayAddressSelectionResult<T>(
                plan = null,
                rejections = listOf(ArcPinnedByteArrayAddressRejection(reason, binding)),
            )

        if (!candidate.mode.isAuthorized()) {
            return reject(ArcPinnedByteArrayAddressRejectionReason.UnsupportedCompilationMode, lifetime.functionBinding)
        }
        if (!candidate.declarations.isExact()) {
            return reject(
                ArcPinnedByteArrayAddressRejectionReason.DeclarationIdentityMismatch,
                candidate.declarations.addressOfFunctionBinding,
            )
        }
        if (!candidate.completeLoweredIRWalk) {
            return reject(ArcPinnedByteArrayAddressRejectionReason.IncompleteLoweredIRWalk, lifetime.functionBinding)
        }
        if (!lifetime.hasExactStableHandleLifetime()) {
            return reject(ArcPinnedByteArrayAddressRejectionReason.InvalidStableHandleLifetime, lifetime.holderBinding)
        }
        if (!lifetime.hasNonEscapingPointer()) {
            return reject(ArcPinnedByteArrayAddressRejectionReason.EscapingInteriorPointer, lifetime.pointerUseBinding)
        }
        if (!lifetime.hasSupportedControlFlow()) {
            return reject(ArcPinnedByteArrayAddressRejectionReason.UnsupportedControlFlow, lifetime.addressOfCallBinding)
        }
        if (!candidate.semantics.isExact()) {
            return reject(ArcPinnedByteArrayAddressRejectionReason.EvaluationOrExceptionMismatch, lifetime.addressOfCallBinding)
        }
        if (!candidate.hasDistinctStructuralBindings()) {
            return reject(ArcPinnedByteArrayAddressRejectionReason.DuplicateStructuralBinding, lifetime.functionBinding)
        }

        val actions = linkedMapOf(
            ArcPinnedByteArrayAddressActionId.BeginStableHandleDependence to
                    ArcPinnedByteArrayAddressEmissionAction(
                        ArcPinnedByteArrayAddressActionId.BeginStableHandleDependence,
                        listOf(lifetime.pinCallBinding, lifetime.holderBinding, lifetime.receiverReadBinding),
                    ),
            ArcPinnedByteArrayAddressActionId.ReplaceProjectionWithArcHelper to
                    ArcPinnedByteArrayAddressEmissionAction(
                        ArcPinnedByteArrayAddressActionId.ReplaceProjectionWithArcHelper,
                        listOf(
                            lifetime.addressOfCallBinding,
                            lifetime.receiverReadBinding,
                            candidate.declarations.stablePointerFieldBinding,
                            lifetime.indexBinding,
                        ),
                    ),
            ArcPinnedByteArrayAddressActionId.EndStableHandleDependence to
                    ArcPinnedByteArrayAddressEmissionAction(
                        ArcPinnedByteArrayAddressActionId.EndStableHandleDependence,
                        listOf(
                            lifetime.pointerUseBinding,
                            lifetime.normalUnpinCallBinding,
                            lifetime.exceptionalUnpinCallBinding,
                        ),
                    ),
        )
        return ArcPinnedByteArrayAddressSelectionResult(
            plan = ArcPinnedByteArrayAddressPlan(
                functionBinding = lifetime.functionBinding,
                holderBinding = lifetime.holderBinding,
                stablePointerFieldBinding = candidate.declarations.stablePointerFieldBinding,
                addressOfCallBinding = lifetime.addressOfCallBinding,
                pointerUseBinding = lifetime.pointerUseBinding,
                normalUnpinCallBinding = lifetime.normalUnpinCallBinding,
                exceptionalUnpinCallBinding = lifetime.exceptionalUnpinCallBinding,
                runtimeHelper = ARC_PINNED_BYTE_ARRAY_ADDRESS_HELPER,
                actions = actions,
            ),
            rejections = emptyList(),
        )
    }
}

/** Exact lowered Kotlin identities carried from selection into the later LLVM integration. */
internal data class ArcPinnedByteArrayAddressKotlinIRSelection(
    val function: IrSimpleFunction,
    val sourceByteArray: IrValueParameter,
    val pinnedVariable: IrVariable,
    val pinCall: IrCall,
    val addressOfCall: IrCall,
    val addressReceiverRead: IrGetValue,
    val indexRead: IrGetValue,
    val pointerVariable: IrVariable,
    val pointerRead: IrGetValue,
    val terminalByteLoad: IrCall,
    val normalUnpinCall: IrCall,
    val exceptionalUnpinCall: IrCall,
    val stablePointerField: IrField,
    val plan: ArcPinnedByteArrayAddressPlan<Any>,
)

/**
 * Authenticate the exact post-inlining 1.9.10 `ByteArray.usePinned { addressOf(i).pointed.value }`
 * web observed after Native lowerings. In particular, `usePinned` is represented by one `pin`
 * and two mutually exclusive `unpin` calls: one after the normal Byte load and one in a
 * catch/rethrow cleanup. An escaped CPointer, a second pointer read, or any nested function falls
 * back to the ordinary stdlib call chain.
 */
internal fun selectVerifiedPinnedByteArrayAddressProjection(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
): ArcPinnedByteArrayAddressKotlinIRSelection? {
    val context = generationState.context
    val config = context.config
    fun reject(reason: String): ArcPinnedByteArrayAddressKotlinIRSelection? {
        context.log {
            "ARC Pinned<ByteArray>.addressOf selector ${function.fqNameForIrSerialization.asString()}: $reason"
        }
        return null
    }

    val mode = ArcPinnedByteArrayAddressCompilationMode(
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
        sourceFunction = function.konanLibrary == null,
    )
    if (!mode.isAuthorized()) return null

    val stdlib = context.stdlibModule.konanLibrary ?: return reject("missing stdlib identity")
    val pinnedClass = context.irBuiltIns
        .findClass(Name.identifier("Pinned"), InteropFqNames.packageName)
        ?.owner
        ?.takeIf {
            it.konanLibrary === stdlib && it.modality == Modality.FINAL &&
                    it.fqNameForIrSerialization.asString() == "kotlinx.cinterop.Pinned"
        } ?: return reject("Pinned declaration identity")
    val stablePointerField = pinnedClass.declarations.filterIsInstance<IrField>().singleOrNull {
        it.name.asString() == "stablePtr" && it.parent === pinnedClass && it.konanLibrary === stdlib &&
                !it.isStatic && it.isFinal && it.visibility == DescriptorVisibilities.PRIVATE &&
                !it.hasArcOwnershipStorageAnnotation()
    } ?: return reject("Pinned.stablePtr field identity")
    val exactUnpin = pinnedClass.functions.singleOrNull {
        it.name.asString() == "unpin" && it.parent === pinnedClass && it.konanLibrary === stdlib &&
                it.dispatchReceiverParameter?.type?.classifierOrNull == pinnedClass.symbol &&
                it.extensionReceiverParameter == null && it.valueParameters.isEmpty() &&
                it.typeParameters.isEmpty() && it.returnType.isUnit() && !it.isExternal &&
                !it.isSuspend && it.modality == Modality.FINAL
    } ?: return reject("Pinned.unpin declaration identity")
    val exactPin = context.irBuiltIns.findFunctions(Name.identifier("pin"), InteropFqNames.packageName)
        .map { it.owner }
        .singleOrNull {
            it.konanLibrary === stdlib && it.fqNameForIrSerialization.asString() == "kotlinx.cinterop.pin" &&
                    it.dispatchReceiverParameter == null && it.extensionReceiverParameter != null &&
                    it.valueParameters.isEmpty() && it.typeParameters.size == 1 && !it.isExternal &&
                    !it.isSuspend && it.returnType.classifierOrNull == pinnedClass.symbol
        } ?: return reject("pin declaration identity")
    val exactAddressOf = context.irBuiltIns
        .findFunctions(Name.identifier("addressOf"), InteropFqNames.packageName)
        .map { it.owner }
        .singleOrNull {
            it.konanLibrary === stdlib &&
                    it.fqNameForIrSerialization.asString() == "kotlinx.cinterop.addressOf" &&
                    it.dispatchReceiverParameter == null &&
                    it.extensionReceiverParameter?.type.isExactPinnedByteArray(pinnedClass) &&
                    it.valueParameters.singleOrNull()?.type?.isInt() == true &&
                    it.typeParameters.isEmpty() && !it.isExternal && !it.isSuspend &&
                    it.returnType.classifierOrNull == context.ir.symbols.interopCPointer
        } ?: return reject("Pinned<ByteArray>.addressOf declaration identity")

    val body = function.body ?: return reject("missing body")
    val parent = IdentityHashMap<IrElement, IrElement>()
    val order = IdentityHashMap<IrElement, Int>()
    val calls = mutableListOf<IrCall>()
    val variables = mutableListOf<IrVariable>()
    val reads = mutableListOf<IrGetValue>()
    val writes = mutableListOf<IrSetValue>()
    val tries = mutableListOf<IrTry>()
    val catches = mutableListOf<IrCatch>()
    val throws = mutableListOf<IrThrow>()
    val stack = mutableListOf<IrElement>()
    var ordinal = 0
    var nestedFunction = false
    body.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            if (element is IrFunction) {
                nestedFunction = true
                return
            }
            stack.lastOrNull()?.let { parent[element] = it }
            order[element] = ordinal++
            when (element) {
                is IrCall -> calls += element
                is IrVariable -> variables += element
                is IrGetValue -> reads += element
                is IrSetValue -> writes += element
                is IrTry -> tries += element
                is IrCatch -> catches += element
                is IrThrow -> throws += element
            }
            stack += element
            element.acceptChildrenVoid(this)
            check(stack.removeAt(stack.lastIndex) === element)
        }
    })
    if (nestedFunction) return reject("nested function boundary")

    val addressOfCall = calls.singleOrNull { it.symbol.owner === exactAddressOf }
        ?: return reject("exactly one addressOf call")
    val addressReceiverRead = addressOfCall.extensionReceiver as? IrGetValue
        ?: return reject("direct addressOf receiver read")
    val pinnedVariable = addressReceiverRead.symbol.owner as? IrVariable
        ?: return reject("addressOf receiver local")
    if (pinnedVariable.isVar || !pinnedVariable.type.isExactPinnedByteArray(pinnedClass)) {
        return reject("immutable Pinned<ByteArray> holder")
    }
    val pinCall = pinnedVariable.initializer as? IrCall ?: return reject("direct pin initializer")
    if (pinCall.symbol.owner !== exactPin || !pinCall.type.isExactPinnedByteArray(pinnedClass) ||
        pinCall.typeArgumentsCount != 1 || pinCall.getTypeArgument(0)?.isByteArray() != true
    ) return reject("exact ByteArray.pin call")
    val sourceRead = pinCall.extensionReceiver as? IrGetValue ?: return reject("direct ByteArray source read")
    val sourceByteArray = sourceRead.symbol.owner as? IrValueParameter ?: return reject("ByteArray source parameter")
    if (sourceByteArray.parent !== function || !sourceByteArray.type.isByteArray()) {
        return reject("ByteArray source identity")
    }
    val indexRead = addressOfCall.getValueArgument(0) as? IrGetValue ?: return reject("direct index read")
    val indexParameter = indexRead.symbol.owner as? IrValueParameter ?: return reject("index parameter")
    if (indexParameter.parent !== function || !indexParameter.type.isInt() ||
        reads.count { it.symbol == indexParameter.symbol } != 1
    ) return reject("single index evaluation")

    val unpins = calls.filter { it.symbol.owner === exactUnpin }
    if (unpins.size != 2) return reject("two path-exclusive unpin calls")
    val exactTry = tries.singleOrNull {
        addressOfCall.isDescendantOf(it.tryResult, parent) && it.catches.size == 1 && it.finallyExpression == null
    } ?: return reject("canonical usePinned try/catch")
    val exactCatch = exactTry.catches.single()
    if (catches.count { it === exactCatch } != 1) return reject("single cleanup catch")
    val exceptionalUnpin = unpins.singleOrNull { it.isDescendantOf(exactCatch.result, parent) }
        ?: return reject("exceptional unpin")
    val normalUnpin = unpins.singleOrNull {
        it !== exceptionalUnpin && !it.isDescendantOf(exactTry, parent) && !it.hasAncestor<IrCatch>(parent)
    } ?: return reject("normal unpin")
    val exceptionalReceiver = exceptionalUnpin.dispatchReceiver as? IrGetValue
        ?: return reject("exceptional unpin receiver")
    val normalReceiver = normalUnpin.dispatchReceiver as? IrGetValue
        ?: return reject("normal unpin receiver")
    val holderReads = reads.filter { it.symbol == pinnedVariable.symbol }
    if (holderReads.size != 3 || holderReads.none { it === addressReceiverRead } ||
        holderReads.none { it === exceptionalReceiver } || holderReads.none { it === normalReceiver } ||
        writes.any { it.symbol == pinnedVariable.symbol }
    ) return reject("complete Pinned holder use census")
    val cleanupThrow = throws.singleOrNull { it.isDescendantOf(exactCatch.result, parent) }
        ?: return reject("catch/rethrow cleanup")
    val thrownRead = cleanupThrow.value as? IrGetValue ?: return reject("direct rethrow")
    if (thrownRead.symbol != exactCatch.catchParameter.symbol) return reject("caught exception identity")

    val pointerVariable = addressOfCall.nearestAncestor<IrVariable>(parent)
        ?.takeIf { it.initializer?.containsIdentity(addressOfCall) == true && !it.isVar }
        ?: return reject("addressOf pointer temporary")
    val pointerReads = reads.filter { it.symbol == pointerVariable.symbol }
    val pointerRead = pointerReads.singleOrNull() ?: return reject("single pointer temporary read")
    val terminalByteLoad = pointerRead.ancestors(parent).filterIsInstance<IrCall>().firstOrNull {
        it.type.isByte() && it.symbol.owner.konanLibrary === stdlib &&
                it.symbol.owner.name.asString() == "<get-value>" &&
                it.symbol.owner.fqNameForIrSerialization.asString().startsWith("kotlinx.cinterop.")
    } ?: return reject("exact non-escaping Byte load")
    val projectionCalls = pointerRead.ancestors(parent).takeWhile { it !== terminalByteLoad }
        .filterIsInstance<IrCall>().toList()
    if (projectionCalls.any { it.symbol.owner.konanLibrary !== stdlib }) {
        return reject("non-stdlib projection call")
    }
    if ((order[pinCall] ?: Int.MAX_VALUE) >= (order[addressOfCall] ?: Int.MIN_VALUE) ||
        (order[terminalByteLoad] ?: Int.MAX_VALUE) >= (order[normalUnpin] ?: Int.MIN_VALUE)
    ) return reject("stable-handle lifetime order")

    val declarationProof = ArcPinnedByteArrayAddressDeclarationProof<Any>(
        interopLibraryBinding = stdlib,
        pinnedClassBinding = pinnedClass,
        stablePointerFieldBinding = stablePointerField,
        pinFunctionBinding = exactPin,
        addressOfFunctionBinding = exactAddressOf,
        unpinFunctionBinding = exactUnpin,
        exactInteropLibraryIdentity = pinnedClass.konanLibrary === stdlib &&
                exactPin.konanLibrary === stdlib && exactAddressOf.konanLibrary === stdlib &&
                exactUnpin.konanLibrary === stdlib && stablePointerField.konanLibrary === stdlib,
        exactKotlinxCinteropPackage = true,
        exactFinalPinnedByteArrayInstantiation = pinnedClass.modality == Modality.FINAL &&
                pinnedVariable.type.isExactPinnedByteArray(pinnedClass),
        exactPrivateFinalNativePointerField = stablePointerField.visibility == DescriptorVisibilities.PRIVATE &&
                stablePointerField.isFinal && !stablePointerField.isStatic,
        // The exact stdlib KLIB identity authenticates these non-inline declaration bodies.
        exactPinSignatureAndBody = pinCall.symbol.owner === exactPin,
        exactPinnedByteArrayAddressOfSignatureAndBody = addressOfCall.symbol.owner === exactAddressOf,
        exactUnpinSignatureAndBody = unpins.all { it.symbol.owner === exactUnpin },
        canonicalByteArrayAddressIntrinsic = true,
    )
    val lifetimeProof = ArcPinnedByteArrayAddressLifetimeProof<Any>(
        functionBinding = function,
        pinCallBinding = pinCall,
        holderBinding = pinnedVariable,
        addressOfCallBinding = addressOfCall,
        receiverReadBinding = addressReceiverRead,
        indexBinding = indexRead,
        pointerUseBinding = terminalByteLoad,
        normalUnpinCallBinding = normalUnpin,
        exceptionalUnpinCallBinding = exceptionalUnpin,
        exactSinglePinAndTwoPathExclusiveUnpins = calls.count { it.symbol.owner === exactPin } == 1 &&
                unpins.size == 2,
        pinDominatesProjection = order.getValue(pinCall) < order.getValue(addressOfCall),
        projectionDominatesPointerUse = order.getValue(addressOfCall) < order.getValue(terminalByteLoad),
        pointerUsePostDominatesProjection = pointerReads.size == 1,
        unpinPostDominatesPointerUse = order.getValue(terminalByteLoad) < order.getValue(normalUnpin),
        noUnpinOrHandleMutationBeforeLastUse = unpins.none {
            it !== exceptionalUnpin && order.getValue(it) < order.getValue(terminalByteLoad)
        },
        holderDoesNotEscapeOrAlias = holderReads.size == 3 && writes.none { it.symbol == pinnedVariable.symbol },
        stableHandleDoesNotEscapeOrAlias = holderReads.all {
            it === addressReceiverRead || it === normalReceiver || it === exceptionalReceiver
        },
        pointerHasOneNonEscapingUse = pointerReads.size == 1 &&
                terminalByteLoad.type.isByte() && !terminalByteLoad.isDescendantOf(exactCatch.result, parent),
        noNestedFunctionOrSuspensionBoundary = !nestedFunction && !function.isArcSuspendLike(),
        noUnknownCallBetweenProjectionAndUse = projectionCalls.all { it.symbol.owner.konanLibrary === stdlib },
        allNormalAndExceptionalEdgesPreserveCleanup = cleanupThrow.value === thrownRead &&
                exceptionalUnpin.isDescendantOf(exactCatch.result, parent),
    )
    val semanticsProof = ArcPinnedByteArrayAddressSemanticsProof(
        receiverEvaluatedExactlyOnce = holderReads.count { it === addressReceiverRead } == 1,
        indexEvaluatedExactlyOnce = reads.count { it.symbol == indexParameter.symbol } == 1,
        receiverBeforeIndex = order.getValue(addressReceiverRead) < order.getValue(indexRead),
        helperDelegatesToCanonicalBoundsCheck = true,
        originalExceptionalSuccessorPreserved = addressOfCall.isDescendantOf(exactTry.tryResult, parent),
        pointerElementTypeIsByteVar = exactAddressOf.extensionReceiverParameter?.type
            .isExactPinnedByteArray(pinnedClass),
    )
    val candidate = ArcPinnedByteArrayAddressCandidate(
        mode,
        declarationProof,
        lifetimeProof,
        semanticsProof,
        completeLoweredIRWalk = true,
    )
    val plan = ArcPinnedByteArrayAddressAnalysis.select(candidate).plan
        ?: return reject("closed ownership adapter")
    return ArcPinnedByteArrayAddressKotlinIRSelection(
        function,
        sourceByteArray,
        pinnedVariable,
        pinCall,
        addressOfCall,
        addressReceiverRead,
        indexRead,
        pointerVariable,
        pointerRead,
        terminalByteLoad,
        normalUnpin,
        exceptionalUnpin,
        stablePointerField,
        plan,
    )
}

private fun IrField.hasArcOwnershipStorageAnnotation(): Boolean =
    hasAnnotation(KonanFqNames.arcWeak) || hasAnnotation(KonanFqNames.arcUnowned) ||
            hasAnnotation(KonanFqNames.volatile)

private fun org.jetbrains.kotlin.ir.types.IrType?.isExactPinnedByteArray(pinnedClass: IrClass): Boolean {
    val type = this as? IrSimpleType ?: return false
    return type.classifierOrNull == pinnedClass.symbol &&
            type.arguments.singleOrNull()?.typeOrNull?.isByteArray() == true
}

private fun IrElement.isDescendantOf(
    ancestor: IrElement,
    parents: IdentityHashMap<IrElement, IrElement>,
): Boolean = ancestors(parents).any { it === ancestor }

private inline fun <reified T : IrElement> IrElement.hasAncestor(
    parents: IdentityHashMap<IrElement, IrElement>,
): Boolean = ancestors(parents).any { it is T }

private inline fun <reified T : IrElement> IrElement.nearestAncestor(
    parents: IdentityHashMap<IrElement, IrElement>,
): T? = ancestors(parents).filterIsInstance<T>().firstOrNull()

private fun IrElement.ancestors(
    parents: IdentityHashMap<IrElement, IrElement>,
): Sequence<IrElement> = generateSequence(parents[this]) { parents[it] }

private fun IrElement.containsIdentity(target: IrElement): Boolean {
    var found = false
    acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            if (element === target) {
                found = true
                return
            }
            if (!found) element.acceptChildrenVoid(this)
        }
    })
    return found
}

private fun <T : Any> ArcPinnedByteArrayAddressDeclarationProof<T>.isExact(): Boolean =
    exactInteropLibraryIdentity && exactKotlinxCinteropPackage &&
            exactFinalPinnedByteArrayInstantiation && exactPrivateFinalNativePointerField &&
            exactPinSignatureAndBody && exactPinnedByteArrayAddressOfSignatureAndBody &&
            exactUnpinSignatureAndBody && canonicalByteArrayAddressIntrinsic

private fun <T : Any> ArcPinnedByteArrayAddressLifetimeProof<T>.hasExactStableHandleLifetime(): Boolean =
    exactSinglePinAndTwoPathExclusiveUnpins && pinDominatesProjection && projectionDominatesPointerUse &&
            pointerUsePostDominatesProjection && unpinPostDominatesPointerUse &&
            noUnpinOrHandleMutationBeforeLastUse && holderDoesNotEscapeOrAlias &&
            stableHandleDoesNotEscapeOrAlias

private fun <T : Any> ArcPinnedByteArrayAddressLifetimeProof<T>.hasNonEscapingPointer(): Boolean =
    pointerHasOneNonEscapingUse

private fun <T : Any> ArcPinnedByteArrayAddressLifetimeProof<T>.hasSupportedControlFlow(): Boolean =
    noNestedFunctionOrSuspensionBoundary && noUnknownCallBetweenProjectionAndUse &&
            allNormalAndExceptionalEdgesPreserveCleanup

private fun ArcPinnedByteArrayAddressSemanticsProof.isExact(): Boolean =
    receiverEvaluatedExactlyOnce && indexEvaluatedExactlyOnce && receiverBeforeIndex &&
            helperDelegatesToCanonicalBoundsCheck && originalExceptionalSuccessorPreserved &&
            pointerElementTypeIsByteVar

private fun <T : Any> ArcPinnedByteArrayAddressCandidate<T>.hasDistinctStructuralBindings(): Boolean {
    val bindings = listOf(
        lifetime.functionBinding,
        lifetime.pinCallBinding,
        lifetime.holderBinding,
        lifetime.addressOfCallBinding,
        lifetime.receiverReadBinding,
        lifetime.indexBinding,
        lifetime.pointerUseBinding,
        lifetime.normalUnpinCallBinding,
        lifetime.exceptionalUnpinCallBinding,
        declarations.interopLibraryBinding,
        declarations.pinnedClassBinding,
        declarations.stablePointerFieldBinding,
        declarations.pinFunctionBinding,
        declarations.addressOfFunctionBinding,
        declarations.unpinFunctionBinding,
    )
    return bindings.indices.all { left ->
        (left + 1 until bindings.size).all { right -> bindings[left] !== bindings[right] }
    }
}

/** Exactly-once, exact-binding handoff from the ownership plan to LLVM emission. */
internal class ArcPinnedByteArrayAddressConsumptionLedger<T : Any>(
    private val plan: ArcPinnedByteArrayAddressPlan<T>,
    functionBinding: T,
) {
    private val consumed = linkedSetOf<ArcPinnedByteArrayAddressActionId>()
    private var nextAction = 0

    init {
        check(plan.functionBinding === functionBinding) {
            "pinned ByteArray projection plan belongs to another function"
        }
        check(plan.actions.keys == ArcPinnedByteArrayAddressActionId.values().toSet()) {
            "pinned ByteArray projection plan has an incomplete action set"
        }
    }

    fun consume(action: ArcPinnedByteArrayAddressActionId, bindingIdentities: List<T>) {
        val expectedAction = ArcPinnedByteArrayAddressActionId.values()[nextAction]
        check(action == expectedAction) {
            "pinned ByteArray projection action $action was emitted before $expectedAction"
        }
        val expectedBindings = plan.actions.getValue(action).bindingIdentities
        check(expectedBindings.size == bindingIdentities.size &&
                expectedBindings.indices.all { expectedBindings[it] === bindingIdentities[it] }) {
            "pinned ByteArray projection action $action was emitted for different IR bindings"
        }
        check(consumed.add(action)) { "pinned ByteArray projection action $action was emitted twice" }
        nextAction++
    }

    fun verifyComplete() {
        val missing = plan.actions.keys - consumed
        check(missing.isEmpty()) { "pinned ByteArray projection actions were not emitted: $missing" }
    }
}
