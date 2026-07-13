/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.llvm

import kotlinx.cinterop.*
import llvm.*
import org.jetbrains.kotlin.backend.common.ir.inlineFunction
import org.jetbrains.kotlin.backend.common.ir.isUnconditional
import org.jetbrains.kotlin.backend.common.lower.coroutines.getOrCreateFunctionWithContinuationStub
import org.jetbrains.kotlin.backend.common.lower.inline.InlinerExpressionLocationHint
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.arc.isAuthorized
import org.jetbrains.kotlin.backend.konan.arc.isArcSuspendLike
import org.jetbrains.kotlin.backend.konan.arc.ArcReturnedReceiverSlotReuseEligibility
import org.jetbrains.kotlin.backend.konan.arc.ArcLockedReadCanonicalPlan
import org.jetbrains.kotlin.backend.konan.arc.ArcDiscardedReturnedReceiverGroup
import org.jetbrains.kotlin.backend.konan.arc.ArcRootedGlobalProjectionPlan
import org.jetbrains.kotlin.backend.konan.arc.ArcResultCompanionImmortalLoadPlan
import org.jetbrains.kotlin.backend.konan.arc.ArcCoroutineGuaranteedPhiIRSelection
import org.jetbrains.kotlin.backend.konan.arc.ArcMatchingSetConsumptionLedger
import org.jetbrains.kotlin.backend.konan.arc.ArcMatchingSetLocalAliasIRSelection
import org.jetbrains.kotlin.backend.konan.arc.ArcSafeContinuationResumeBorrowConsumptionLedger
import org.jetbrains.kotlin.backend.konan.arc.ArcSafeContinuationResumeBorrowPlan
import org.jetbrains.kotlin.backend.konan.arc.ArcSafeContinuationSROAKotlinIRCodegenPlan
import org.jetbrains.kotlin.backend.konan.arc.ArcSafeContinuationSROAPhysicalEmissionLedger
import org.jetbrains.kotlin.backend.konan.arc.ArcSafeContinuationSROAIRSelection
import org.jetbrains.kotlin.backend.konan.arc.ArcOwnedToGuaranteedPhiIRActionId
import org.jetbrains.kotlin.backend.konan.arc.ArcOwnedToGuaranteedPhiIRConsumptionLedger
import org.jetbrains.kotlin.backend.konan.arc.ArcOwnedToGuaranteedPhiKotlinIRBindingRole
import org.jetbrains.kotlin.backend.konan.arc.ArcOwnedToGuaranteedPhiKotlinIRSelection
import org.jetbrains.kotlin.backend.konan.arc.ArcStringBuilderBackingArrayProjectionConsumptionLedger
import org.jetbrains.kotlin.backend.konan.arc.ArcStringBuilderBackingArrayProjectionPlan
import org.jetbrains.kotlin.backend.konan.arc.ArcOwnedResultHeapStoreConsumptionLedger
import org.jetbrains.kotlin.backend.konan.arc.ArcOwnedResultHeapStoreIRBindingRole
import org.jetbrains.kotlin.backend.konan.arc.ArcOwnedResultHeapStoreKotlinIRSelection
import org.jetbrains.kotlin.backend.konan.arc.ArcFreshOwnedFieldStorePlan
import org.jetbrains.kotlin.backend.konan.arc.ArcCoroutineEmptyContextReturnConsumptionLedger
import org.jetbrains.kotlin.backend.konan.arc.ArcCoroutineEmptyContextReturnKotlinIRSelection
import org.jetbrains.kotlin.backend.konan.arc.ArcCoroutineSuspendedBorrowConsumptionLedger
import org.jetbrains.kotlin.backend.konan.arc.ArcCoroutineSuspendedBorrowIRSelection
import org.jetbrains.kotlin.backend.konan.arc.ArcCoroutineSuspendedBorrowKotlinIRSelection
import org.jetbrains.kotlin.backend.konan.arc.ArcImmortalCompletionContextCodegenPlan
import org.jetbrains.kotlin.backend.konan.arc.exactIdentityInventory
import org.jetbrains.kotlin.backend.konan.arc.hasExactCoroutineSingletonValuesInitializer
import org.jetbrains.kotlin.backend.konan.arc.hasExactCoroutinePhysicalBackedge
import org.jetbrains.kotlin.backend.konan.arc.unwrapExactArcCoroutineSpillRead
import org.jetbrains.kotlin.backend.konan.optimizations.ARC_SELECTIVE_INLINE_METADATA
import org.jetbrains.kotlin.backend.konan.cexport.CAdapterCodegen
import org.jetbrains.kotlin.backend.konan.cexport.CAdapterExportedElements
import org.jetbrains.kotlin.backend.konan.cgen.CBridgeOrigin
import org.jetbrains.kotlin.backend.konan.descriptors.*
import org.jetbrains.kotlin.backend.konan.ir.*
import org.jetbrains.kotlin.backend.konan.llvm.coverage.LLVMCoverageInstrumentation
import org.jetbrains.kotlin.backend.konan.lower.*
import org.jetbrains.kotlin.builtins.UnsignedType
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.konan.ForeignExceptionMode
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.uniqueName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import java.util.IdentityHashMap
import java.util.Collections

internal enum class FieldStorageKind {
    GLOBAL, // In the old memory model these are only accessible from the "main" thread.
    SHARED_FROZEN,
    THREAD_LOCAL
}

// TODO: maybe unannotated singleton objects shall be accessed from main thread only as well?
internal fun IrField.storageKind(context: Context): FieldStorageKind {
    // TODO: Is this correct?
    val annotations = correspondingPropertySymbol?.owner?.annotations ?: annotations
    val isLegacyMM = !context.memoryModel.usesSharedHeap
    // TODO: simplify, once IR types are fully there.
    val typeAnnotations = (type.classifierOrNull?.owner as? IrAnnotationContainer)?.annotations
    val typeFrozen = typeAnnotations?.hasAnnotation(KonanFqNames.frozen) == true ||
        (typeAnnotations?.hasAnnotation(KonanFqNames.frozenLegacyMM) == true && isLegacyMM)
    return when {
        annotations.hasAnnotation(KonanFqNames.threadLocal) -> FieldStorageKind.THREAD_LOCAL
        !isLegacyMM && !context.config.freezing.freezeImplicit -> FieldStorageKind.GLOBAL
        !isFinal -> FieldStorageKind.GLOBAL
        annotations.hasAnnotation(KonanFqNames.sharedImmutable) -> FieldStorageKind.SHARED_FROZEN
        typeFrozen -> FieldStorageKind.SHARED_FROZEN
        else -> FieldStorageKind.GLOBAL
    }
}

internal fun IrField.needsGCRegistration(context: Context) =
        context.memoryModel.usesTracingGC && // only for the tracing collector
                type.binaryTypeIsReference() && // only for references
                (hasNonConstInitializer || // which are initialized from heap object
                        !isFinal) // or are not final


internal fun IrField.isGlobalNonPrimitive(context: Context) = when  {
        type.computePrimitiveBinaryTypeOrNull() != null -> false
        else -> storageKind(context) == FieldStorageKind.GLOBAL
    }


internal fun IrField.shouldBeFrozen(context: Context): Boolean =
        this.storageKind(context) == FieldStorageKind.SHARED_FROZEN

internal fun IrFunction.shouldGenerateBody(): Boolean = when {
    this is IrConstructor && constructedClass.isInlined() -> false
    this is IrConstructor && isObjCConstructor -> false
    this is IrSimpleFunction && modality == Modality.ABSTRACT -> false
    isExternal -> false
    else -> true
}

private fun IrExpression.unwrapSelectedCompletionFieldLoad(): IrGetField? = when (this) {
    is IrGetField -> this
    is IrTypeOperatorCall -> if (operator == IrTypeOperator.IMPLICIT_CAST) {
        argument.unwrapSelectedCompletionFieldLoad()
    } else {
        null
    }
    else -> null
}

internal class RTTIGeneratorVisitor(
    generationState: NativeGenerationState,
    referencedFunctions: Set<IrFunction>?,
    immortalCompletionContextPlans: List<ArcImmortalCompletionContextCodegenPlan> = emptyList(),
) : IrElementVisitorVoid {
    val generator = RTTIGenerator(generationState, referencedFunctions, immortalCompletionContextPlans)

    val kotlinObjCClassInfoGenerator = KotlinObjCClassInfoGenerator(generationState)

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitClass(declaration: IrClass) {
        super.visitClass(declaration)
        if (declaration.requiresRtti()) {
            generator.generate(declaration)
        }
        if (declaration.isKotlinObjCClass()) {
            kotlinObjCClassInfoGenerator.generate(declaration)
        }
    }

    fun dispose() {
        generator.dispose()
    }
}

//-------------------------------------------------------------------------//


/**
 * Defines how to generate context-dependent operations.
 */
private interface CodeContext {

    /**
     * Generates `return` [value] operation.
     *
     * @param value may be null iff target type is `Unit`.
     */
    fun genReturn(target: IrSymbolOwner, value: LLVMValueRef?)

    fun getReturnSlot(target: IrSymbolOwner) : LLVMValueRef?

    fun genBreak(destination: IrBreak)

    fun genContinue(destination: IrContinue)

    val exceptionHandler: ExceptionHandler

    /**
     * Declares the variable.
     * @return index of declared variable.
     */
    fun genDeclareVariable(variable: IrVariable, value: LLVMValueRef?, variableLocation: VariableDebugLocation?): Int

    /**
     * @return index of value declared before, or -1 if no such variable has been declared yet.
     */
    fun getDeclaredValue(value: IrValueDeclaration): Int

    /**
     * Generates the code to obtain a value available in this context.
     *
     * @return the requested value
     */
    fun genGetValue(value: IrValueDeclaration, resultSlot: LLVMValueRef?): LLVMValueRef

    /**
     * Returns owning function scope.
     *
     * @return the requested value
     */
    fun functionScope(): CodeContext?

    /**
     * Returns owning file scope.
     *
     * @return the requested value if in the file scope or null.
     */
    fun fileScope(): CodeContext?

    /**
     * Returns owning class scope [ClassScope].
     *
     * @returns the requested value if in the class scope or null.
     */
    fun classScope(): CodeContext?

    fun addResumePoint(bbLabel: LLVMBasicBlockRef): Int

    /**
     * Returns owning returnable block scope [ReturnableBlockScope].
     *
     * @returns the requested value if in the returnableBlockScope scope or null.
     */
    fun returnableBlockScope(): CodeContext?

    /**
     * Returns location information for given source location [LocationInfo].
     */
    fun location(offset: Int): LocationInfo?

    /**
     * Returns [DIScopeOpaqueRef] instance for corresponding scope.
     */
    fun scope(): DIScopeOpaqueRef?

    /**
     * Called, when context is pushed on stack
     */
    fun onEnter() {}

    /**
     * Called, when context is removed from stack
     */
    fun onExit() {}
}

//-------------------------------------------------------------------------//

internal class CodeGeneratorVisitor(
        val generationState: NativeGenerationState,
        val irBuiltins: IrBuiltIns,
        val lifetimes: Map<IrElement, Lifetime>,
        val arcOwnership: org.jetbrains.kotlin.backend.konan.arc.ArcCodegenOwnershipPlan,
) : IrElementVisitorVoid {
    private val context = generationState.context
    private val llvm = generationState.llvm
    private val debugInfo: DebugInfo
        get() = generationState.debugInfo

    val codegen = CodeGenerator(generationState)

    // TODO: consider eliminating mutable state
    private var currentCodeContext: CodeContext = TopLevelCodeContext
    private var currentArcOwnedResultForwarding: org.jetbrains.kotlin.backend.konan.arc.ArcOwnedResultForwarding? = null
    private var currentArcPromotionSlots: MutableList<LLVMValueRef>? = null
    private var currentArcPromotionBoundary: IrExpression? = null
    private var currentDiscardedReturnedReceiverSeedSlots:
            MutableMap<ArcDiscardedReturnedReceiverGroup, LLVMValueRef>? = null
    private var currentCoroutineGuaranteedPhiEmission: CoroutineGuaranteedPhiEmission? = null
    private var currentMatchingSetLocalAliasEmission: MatchingSetLocalAliasEmission? = null
    private var currentSafeContinuationResumeBorrowEmission: SafeContinuationResumeBorrowEmission? = null
    private var currentSafeContinuationSROAEmission: SafeContinuationSROAEmission? = null
    private var currentBranchGuaranteedPhiEmission: BranchGuaranteedPhiEmission? = null
    private var currentStringBuilderBackingArrayProjectionEmission:
            StringBuilderBackingArrayProjectionEmission? = null
    private var currentOwnedResultHeapStoreEmission: OwnedResultHeapStoreEmission? = null
    private var currentFreshOwnedFieldStoreEmission: FreshOwnedFieldStoreEmission? = null
    private var currentCoroutineEmptyContextImmortalReturnEmission:
            CoroutineEmptyContextImmortalReturnEmission? = null
    private var currentCoroutineSuspendedScopedBorrowEmission:
            CoroutineSuspendedScopedBorrowEmission? = null
    private var currentImmortalCompletionContextEmission: ImmortalCompletionContextEmission? = null

    /** Emits the constructor first-store and getter result halves of one RTTI-authenticated plan. */
    private inner class ImmortalCompletionContextEmission(
        val plan: ArcImmortalCompletionContextCodegenPlan,
    ) {
        val selection = plan.selection

        fun emitInitializer(store: IrSetField, value: LLVMValueRef, address: LLVMValueRef) {
            val bindings = selection.selection.bindings
            val receiver = store.receiver as? IrGetValue
                ?: error("immortal completion-context initializer lost fresh receiver")
            val rootLoad = store.value.unwrapSelectedCompletionFieldLoad()
                ?: error("immortal completion-context initializer lost permanent root load")
            check(store === selection.initializerStore && store.symbol.owner === selection.contextField &&
                    receiver === bindings.constructedReceiver && receiver.symbol.owner ===
                    selection.constructor.constructedClass.thisReceiver?.symbol?.owner &&
                    rootLoad === bindings.initializerRootLoad &&
                    rootLoad.symbol.owner === bindings.permanentRootField && rootLoad.receiver == null &&
                    selection.selection.ownership.emissionPolicy.rawInitializeOnlyFreshField &&
                    selection.selection.ownership.emissionPolicy.initializeDoesNotReleaseOldValue &&
                    selection.selection.ownership.emissionPolicy.neverRawReplaceInitializedStorage) {
                "immortal completion-context raw initializer fingerprint drifted"
            }
            functionGenerationContext.store(
                value,
                address,
                alignment = generationState.llvmDeclarations.forField(selection.contextField).alignment,
            )
            plan.ledger.consumeInitializer(
                selection.constructor,
                selection.initializerWrapper,
                store,
                bindings.exactIdentityInventory(),
            )
        }

        fun emitGetter(returned: IrReturn, targetReturnSlot: LLVMValueRef): LLVMValueRef {
            val bindings = selection.selection.bindings
            val fieldLoad = selection.getterFieldLoad
            check(returned === selection.getterReturn && returned.value.unwrapSelectedCompletionFieldLoad() === fieldLoad &&
                    fieldLoad.symbol.owner === selection.contextField &&
                    selection.selection.ownership.emissionPolicy.publishWithMoveThatReleasesPriorResultSlot) {
                "immortal completion-context getter fingerprint drifted"
            }
            val root = bindings.permanentRootField as IrField
            check(root.isStatic && root.isFinal && root.type.binaryTypeIsReference()) {
                "immortal completion-context permanent root drifted"
            }
            if (context.config.threadsAreAllowed && root.isGlobalNonPrimitive(context)) {
                functionGenerationContext.checkGlobalsAccessible(currentCodeContext.exceptionHandler)
            }
            val permanent = functionGenerationContext.loadSlot(
                staticFieldPtr(root, functionGenerationContext),
                false,
                null,
                alignment = generationState.llvmDeclarations.forStaticField(root).alignment,
            )
            val publish = llvm.externalNativeRuntimeFunction(
                "MoveReferenceIntoReturnSlotArc",
                LlvmRetType(llvm.voidType),
                listOf(LlvmParamType(codegen.kObjHeaderPtrPtr), LlvmParamType(codegen.kObjHeaderPtr)),
                functionAttributes = listOf(LlvmFunctionAttribute.NoUnwind),
            )
            functionGenerationContext.call(publish, listOf(targetReturnSlot, permanent))
            functionGenerationContext.markReturnValueAlreadyInReturnSlot()
            plan.ledger.consumeGetter(
                selection.contextGetter,
                returned,
                fieldLoad,
                bindings.exactIdentityInventory(),
            )
            return permanent
        }
    }

    /** Emits exact process-rooted enum projections only into bounded identity comparisons. */
    private inner class CoroutineSuspendedScopedBorrowEmission(
        val selection: ArcCoroutineSuspendedBorrowKotlinIRSelection,
    ) {
        private val ledger = ArcCoroutineSuspendedBorrowConsumptionLedger(
            selection.sites.map { it.ownership },
        )

        fun emit(
            call: IrCall,
            site: ArcCoroutineSuspendedBorrowIRSelection<IrElement>,
        ): LLVMValueRef {
            val bindings = site.bindings
            val comparison = bindings.comparison as? IrCall
                ?: error("COROUTINE_SUSPENDED comparison lost call identity")
            val getter = bindings.propertyGetter as? IrSimpleFunction
                ?: error("COROUTINE_SUSPENDED getter lost function identity")
            val propertyReturn = bindings.propertyReturn as? IrReturn
                ?: error("COROUTINE_SUSPENDED getter return lost identity")
            val initializerCall = bindings.initializerCall as? IrCall
                ?: error("COROUTINE_SUSPENDED initializer call lost identity")
            val enumGetterCall = bindings.enumGetterCall as? IrCall
                ?: error("COROUTINE_SUSPENDED enum getter call lost identity")
            val initializer = bindings.initializer as? IrSimpleFunction
                ?: error("COROUTINE_SUSPENDED global initializer lost function identity")
            val root = bindings.valuesRoot as? IrField
                ?: error("COROUTINE_SUSPENDED values root lost field identity")
            val enumClass = bindings.enumClass as? IrClass
                ?: error("COROUTINE_SUSPENDED enum class lost identity")
            val exactArrayGet = (bindings.arrayGet as? IrCall)?.symbol?.owner
                ?: error("COROUTINE_SUSPENDED array projection lost identity")
            check(call === bindings.call && bindings.function === selection.function &&
                    call.symbol.owner === getter && call.dispatchReceiver == null &&
                    call.extensionReceiver == null && call.superQualifierSymbol == null &&
                    call.valueArgumentsCount == 0 && call.typeArgumentsCount == 0 &&
                    comparison.symbol == context.irBuiltIns.eqeqeqSymbol &&
                    comparison.getArgumentsWithIr().count { it.second === call } == 1 &&
                    propertyReturn.returnTargetSymbol == getter.symbol &&
                    propertyReturn.value === enumGetterCall &&
                    (bindings.enumGetter as? IrSimpleFunction)?.body.let { body ->
                        val statements = (body as? IrBlockBody)?.statements
                        statements?.size == 2 && statements[0] === initializerCall &&
                                statements[1] === bindings.enumGetterReturn
                    } &&
                    initializerCall.symbol.owner === initializer &&
                    enumGetterCall.symbol.owner === bindings.enumGetter &&
                    initializer.origin == DECLARATION_ORIGIN_STATIC_GLOBAL_INITIALIZER &&
                    root.isStatic && root.isFinal && root.type.binaryTypeIsReference() &&
                    hasExactCoroutineSingletonValuesInitializer(root, enumClass, exactArrayGet, context)) {
                "COROUTINE_SUSPENDED scoped-borrow structural fingerprint drifted"
            }

            // Preserve the public getter's exact initialization semantics before bypassing its
            // owned result. The selected value remains +0 and is consumed only by identity eq.
            evaluateFileGlobalInitializerCall(initializer)
            if (context.config.threadsAreAllowed && root.isGlobalNonPrimitive(context)) {
                functionGenerationContext.checkGlobalsAccessible(currentCodeContext.exceptionHandler)
            }
            val values = functionGenerationContext.loadSlot(
                staticFieldPtr(root, functionGenerationContext),
                false,
                null,
                alignment = generationState.llvmDeclarations.forStaticField(root).alignment,
            )
            val borrowedGetter = llvm.externalNativeRuntimeFunction(
                "Kotlin_Array_get_borrowed",
                LlvmRetType(codegen.kObjHeaderPtr),
                listOf(LlvmParamType(codegen.kObjHeaderPtr), LlvmParamType(llvm.int32Type)),
            )
            val result = functionGenerationContext.call(
                borrowedGetter,
                listOf(values, llvm.int32(0)),
                exceptionHandler = currentCodeContext.exceptionHandler,
                verbatim = true,
            )
            ledger.consume(call, bindings.exactIdentityInventory())
            return result
        }

        fun verifyConsumed() = ledger.verifyComplete()
    }

    /** Publishes one exact permanent EmptyCoroutineContext root without retaining it. */
    private inner class CoroutineEmptyContextImmortalReturnEmission(
        val selection: ArcCoroutineEmptyContextReturnKotlinIRSelection,
    ) {
        private val ledger = ArcCoroutineEmptyContextReturnConsumptionLedger(
            selection.selection.ownership,
        )
        private var emitted = false

        fun emit(returned: IrReturn, targetReturnSlot: LLVMValueRef): LLVMValueRef {
            check(!emitted && returned === selection.returned &&
                    returned.returnTargetSymbol.owner === selection.function) {
                "EmptyCoroutineContext immortal return identity drifted: ${ir2string(returned)}"
            }
            val root = selection.rootField
            check(root.isStatic && root.isFinal && root.type.binaryTypeIsReference() &&
                    selection.directRootLoad.symbol.owner === root && selection.directRootLoad.receiver == null &&
                    selection.rootLoad.symbol.owner === root && selection.rootLoad.receiver == null) {
                "EmptyCoroutineContext permanent root shape drifted"
            }
            if (context.config.threadsAreAllowed && root.isGlobalNonPrimitive(context)) {
                functionGenerationContext.checkGlobalsAccessible(currentCodeContext.exceptionHandler)
            }
            val permanent = functionGenerationContext.loadSlot(
                staticFieldPtr(root, functionGenerationContext),
                false,
                null,
                alignment = generationState.llvmDeclarations.forStaticField(root).alignment,
            )

            // The object-result slot may already own a value. This ARC helper performs a raw
            // publish of the immortal pointer and releases only that old slot value; unlike
            // UpdateReturnRef it never retains the permanent new value.
            val publish = llvm.externalNativeRuntimeFunction(
                "MoveReferenceIntoReturnSlotArc",
                LlvmRetType(llvm.voidType),
                listOf(LlvmParamType(codegen.kObjHeaderPtrPtr), LlvmParamType(codegen.kObjHeaderPtr)),
                functionAttributes = listOf(LlvmFunctionAttribute.NoUnwind),
            )
            functionGenerationContext.call(publish, listOf(targetReturnSlot, permanent))
            functionGenerationContext.markReturnValueAlreadyInReturnSlot()
            ledger.consumeExact(selection.selection.bindings.exactIdentityInventory())
            emitted = true
            return permanent
        }

        fun verifyConsumed() {
            check(emitted) { "selected EmptyCoroutineContext immortal return was not emitted" }
            ledger.verifyComplete()
        }
    }

    /** Moves one exact owned Result-box call result from its physical result slot into Ref.element. */
    private inner class OwnedResultHeapStoreEmission(
        val selection: ArcOwnedResultHeapStoreKotlinIRSelection,
    ) {
        private val exactBindings = selection.selection.exactBindings
        private val ledger = ArcOwnedResultHeapStoreConsumptionLedger(selection.selection.ownership)
        private var producedValue: LLVMValueRef? = null
        private var sourceSlot: LLVMValueRef? = null

        fun isProducer(call: IrCall): Boolean = call === selection.producerCall

        fun bindProducer(call: IrCall, value: LLVMValueRef, physicalSourceSlot: LLVMValueRef) {
            check(call === selection.producerCall && producedValue == null && sourceSlot == null) {
                "owned Result-box producer identity or physical-slot binding drifted: ${ir2string(call)}"
            }
            check(functionGenerationContext.arcResultIsAlreadyOwnedBySlot(value, physicalSourceSlot)) {
                "owned Result-box producer did not initialize its exact physical result slot"
            }
            ledger.consumeProducer(
                exactBindings.getValue(ArcOwnedResultHeapStoreIRBindingRole.Producer),
                exactBindings.getValue(ArcOwnedResultHeapStoreIRBindingRole.SourceResultSlot),
            )
            producedValue = value
            sourceSlot = physicalSourceSlot
        }

        fun moveIntoStore(store: IrSetField, value: LLVMValueRef, destination: LLVMValueRef) {
            check(store === selection.store && value == producedValue) {
                "owned Result-box heap-store identity or produced value drifted: ${ir2string(store)}"
            }
            val physicalSourceSlot = requireNotNull(sourceSlot) {
                "owned Result-box heap store reached before its normal-edge source-slot binding"
            }
            ledger.consumeStore(
                exactBindings.getValue(ArcOwnedResultHeapStoreIRBindingRole.Store),
                exactBindings.getValue(ArcOwnedResultHeapStoreIRBindingRole.DestinationElementSlot),
            )
            functionGenerationContext.moveArcOwnedReferenceIntoHeapSlot(
                value,
                physicalSourceSlot,
                destination,
            )
        }

        fun verifyConsumed() {
            ledger.verifyComplete()
            check(producedValue != null && sourceSlot != null) {
                "owned Result-box heap-store physical binding was incomplete"
            }
        }
    }

    /** Moves exact direct constructor +1 results into their initialized owning field stores. */
    private inner class FreshOwnedFieldStoreEmission(
        val plans: List<ArcFreshOwnedFieldStorePlan>,
    ) {
        private val byProducer = IdentityHashMap<IrConstructorCall, ArcFreshOwnedFieldStorePlan>()
        private val byStore = IdentityHashMap<IrSetField, ArcFreshOwnedFieldStorePlan>()
        private val producedValues = IdentityHashMap<ArcFreshOwnedFieldStorePlan, LLVMValueRef>()
        private val sourceSlots = IdentityHashMap<ArcFreshOwnedFieldStorePlan, LLVMValueRef>()
        private val consumedProducers = Collections.newSetFromMap(
            IdentityHashMap<ArcFreshOwnedFieldStorePlan, Boolean>()
        )
        private val consumedStores = Collections.newSetFromMap(
            IdentityHashMap<ArcFreshOwnedFieldStorePlan, Boolean>()
        )

        init {
            require(plans.isNotEmpty()) { "empty fresh owned field-store emission" }
            plans.forEach { plan ->
                check(byProducer.put(plan.producer, plan) == null && byStore.put(plan.store, plan) == null) {
                    "duplicate fresh owned field-store producer/store identity"
                }
                check(plan.store.value === plan.producer && plan.store.receiver === plan.receiverRead &&
                        plan.receiverRead.symbol.owner === plan.receiverVariable &&
                        plan.store.symbol.owner === plan.field) {
                    "fresh owned field-store IR identity drifted before codegen"
                }
            }
        }

        fun isProducer(expression: IrFunctionAccessExpression): Boolean =
            expression is IrConstructorCall && byProducer.containsKey(expression)

        fun bindProducer(
            producer: IrConstructorCall,
            value: LLVMValueRef,
            physicalSourceSlot: LLVMValueRef,
        ) {
            val plan = byProducer[producer]
                ?: error("unknown fresh owned field-store producer: ${ir2string(producer)}")
            check(consumedProducers.add(plan) && producedValues.put(plan, value) == null &&
                    sourceSlots.put(plan, physicalSourceSlot) == null) {
                "fresh owned field-store producer consumed twice"
            }
            check(functionGenerationContext.arcResultIsAlreadyOwnedBySlot(value, physicalSourceSlot)) {
                "fresh field producer did not initialize its exact physical result slot"
            }
        }

        fun planForStore(store: IrSetField): ArcFreshOwnedFieldStorePlan? = byStore[store]

        fun moveIntoStore(store: IrSetField, value: LLVMValueRef, destination: LLVMValueRef) {
            val plan = byStore[store]
                ?: error("unknown fresh owned field store: ${ir2string(store)}")
            val producedValue = producedValues[plan]
                ?: error("fresh owned field store reached before its normal producer successor")
            val physicalSourceSlot = sourceSlots[plan]
                ?: error("fresh owned field store has no exact physical source slot")
            check(consumedStores.add(plan) && value == producedValue && store.value === plan.producer &&
                    store.receiver === plan.receiverRead && store.symbol.owner === plan.field) {
                "fresh owned field-store identity/value drifted or store consumed twice"
            }
            functionGenerationContext.moveArcOwnedReferenceIntoHeapSlot(
                value,
                physicalSourceSlot,
                destination,
            )
        }

        fun verifyConsumed() {
            val expected = plans.toSet()
            check(consumedProducers == expected && consumedStores == expected &&
                    producedValues.keys == expected && sourceSlots.keys == expected) {
                "fresh owned field-store emission incomplete: producers=${consumedProducers.size}, " +
                        "stores=${consumedStores.size}, expected=${plans.size}"
            }
        }
    }

    /** Consumes one exact StringBuilder backing-field projection inside its authenticated call. */
    private inner class StringBuilderBackingArrayProjectionEmission(
        val plan: ArcStringBuilderBackingArrayProjectionPlan,
    ) {
        private val ledger = ArcStringBuilderBackingArrayProjectionConsumptionLedger(plan)
        private var activeConsumer: IrCall? = null

        fun beginConsumer(call: IrCall) {
            check(call === plan.consumer && activeConsumer == null) {
                "StringBuilder backing-array consumer identity drift: ${ir2string(call)}"
            }
            activeConsumer = call
        }

        fun markLoad(load: IrGetField) {
            val consumer = activeConsumer
            check(load === plan.backingFieldLoad && consumer === plan.consumer) {
                "StringBuilder backing-array load escaped its authenticated consumer: ${ir2string(load)}"
            }
            ledger.consume(load, requireNotNull(consumer))
        }

        fun endConsumer(call: IrCall) {
            check(call === plan.consumer && activeConsumer === call) {
                "StringBuilder backing-array consumer boundary drift: ${ir2string(call)}"
            }
            activeConsumer = null
        }

        fun verifyConsumed() {
            check(activeConsumer == null) { "unterminated StringBuilder backing-array consumer" }
            ledger.verifyComplete()
        }
    }

    /** Consumes one generalized, exact-identity diamond as a promotable non-owning local. */
    private inner class BranchGuaranteedPhiEmission(
        productionSelection: ArcOwnedToGuaranteedPhiKotlinIRSelection,
    ) {
        val selection = productionSelection.branchSelection
        private val generalized = productionSelection.generalizedSelection
        private val exactBindings = productionSelection.bindings
        private val ledger = ArcOwnedToGuaranteedPhiIRConsumptionLedger(
            generalized,
            exactBindings.getValue(ArcOwnedToGuaranteedPhiKotlinIRBindingRole.Function),
        )
        private val declarations = Collections.newSetFromMap(IdentityHashMap<IrVariable, Boolean>())
        private val reads = Collections.newSetFromMap(IdentityHashMap<IrGetValue, Boolean>())
        private val stores = Collections.newSetFromMap(IdentityHashMap<IrSetValue, Boolean>())
        private var conditionalEmissions = 0
        private var terminalEqualityEmissions = 0

        private fun consume(
            matches: (ArcOwnedToGuaranteedPhiIRActionId) -> Boolean,
            vararg roles: ArcOwnedToGuaranteedPhiKotlinIRBindingRole,
        ) {
            val bindings = roles.map(exactBindings::getValue)
            val action = generalized.actions.values.singleOrNull { candidate ->
                matches(candidate.id) && candidate.bindingIdentities.size == bindings.size &&
                        candidate.bindingIdentities.indices.all {
                            candidate.bindingIdentities[it] === bindings[it]
                        }
            } ?: error("missing exact generalized owned-to-guaranteed action for ${roles.toList()}")
            ledger.stage(action.id, bindings)
        }

        fun markDeclaration(variable: IrVariable) {
            check(variable === selection.selectedVariable && declarations.add(variable)) {
                "duplicate or drifted branch guaranteed-phi declaration: ${ir2string(variable)}"
            }
        }

        fun markOrdinaryRead(read: IrGetValue) {
            if (read !== selection.conditionRead && read !== selection.thenSourceRead &&
                read !== selection.elseSourceRead && read !== selection.comparisonRead
            ) return
            check(reads.add(read)) { "duplicate branch guaranteed-phi read: ${ir2string(read)}" }
        }

        @Suppress("UNUSED_PARAMETER")
        fun loadJoinedValue(read: IrGetValue, resultSlot: LLVMValueRef?): LLVMValueRef? {
            if (read !== selection.selectedRead) return null
            require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                    !context.shouldContainDebugInfo() && !context.config.arcDiagnosticsEnabled &&
                    read.symbol.owner === selection.selectedVariable) {
                "branch guaranteed-phi read escaped its exact +0 boundary: ${ir2string(read)}"
            }
            check(reads.add(read)) { "duplicate branch guaranteed-phi joined read: ${ir2string(read)}" }
            val index = currentCodeContext.getDeclaredValue(selection.selectedVariable)
            require(index >= 0) { "branch guaranteed-phi has no non-owning local record" }
            // The verifier proves both incoming parameters live through this sole non-throwing
            // use. Ignore the call-argument result slot so no synthetic owner is introduced.
            return functionGenerationContext.vars.loadRootedProjection(index)
        }

        fun markTerminalEquality(call: IrCall) {
            if (call !== selection.identityEquality) return
            check(terminalEqualityEmissions++ == 0) {
                "duplicate branch guaranteed-phi terminal equality: ${ir2string(call)}"
            }
            consume(
                { it is ArcOwnedToGuaranteedPhiIRActionId.ApplyPrunedLifetimeBoundary },
                ArcOwnedToGuaranteedPhiKotlinIRBindingRole.TerminalUse,
            )
        }

        fun markStore(store: IrSetValue) {
            val thenArm = store === selection.thenStore
            val elseArm = store === selection.elseStore
            if (!thenArm && !elseArm) return
            check(stores.add(store)) { "duplicate branch guaranteed-phi store: ${ir2string(store)}" }
            val copyRole = if (thenArm) ArcOwnedToGuaranteedPhiKotlinIRBindingRole.ThenCopyDefinition
                    else ArcOwnedToGuaranteedPhiKotlinIRBindingRole.ElseCopyDefinition
            val sourceRole = if (thenArm) ArcOwnedToGuaranteedPhiKotlinIRBindingRole.ThenSourceDefinition
                    else ArcOwnedToGuaranteedPhiKotlinIRBindingRole.ElseSourceDefinition
            val anchorRole = if (thenArm) ArcOwnedToGuaranteedPhiKotlinIRBindingRole.ThenAnchorDefinition
                    else ArcOwnedToGuaranteedPhiKotlinIRBindingRole.ElseAnchorDefinition
            val edgeRole = if (thenArm) ArcOwnedToGuaranteedPhiKotlinIRBindingRole.ThenMergeEdge
                    else ArcOwnedToGuaranteedPhiKotlinIRBindingRole.ElseMergeEdge
            consume(
                { it is ArcOwnedToGuaranteedPhiIRActionId.EliminateCopy },
                copyRole,
                sourceRole,
                anchorRole,
            )
            consume(
                { it is ArcOwnedToGuaranteedPhiIRActionId.InstallReborrow },
                edgeRole,
                copyRole,
                ArcOwnedToGuaranteedPhiKotlinIRBindingRole.JoinDefinition,
            )
        }

        fun markConditional(expression: IrWhen) {
            check(expression === selection.conditional && conditionalEmissions++ == 0) {
                "duplicate or drifted branch guaranteed-phi conditional: ${ir2string(expression)}"
            }
            consume(
                { it is ArcOwnedToGuaranteedPhiIRActionId.ConvertJoin },
                ArcOwnedToGuaranteedPhiKotlinIRBindingRole.JoinDefinition,
            )
        }

        fun verifyConsumed() {
            val expectedReads = listOf(
                selection.conditionRead,
                selection.thenSourceRead,
                selection.elseSourceRead,
                selection.selectedRead,
                selection.comparisonRead,
            )
            check(declarations.size == 1 && selection.selectedVariable in declarations &&
                    stores.size == 2 && selection.thenStore in stores && selection.elseStore in stores &&
                    reads.size == expectedReads.size && expectedReads.all { it in reads } &&
                    conditionalEmissions == 1 && terminalEqualityEmissions == 1 &&
                    functionGenerationContext.isAfterTerminator()) {
                "selected branch guaranteed-phi shape drifted after planning: " +
                        "decls=${declarations.size} reads=${reads.size} stores=${stores.size} " +
                        "conditionals=$conditionalEmissions terminalEqualities=$terminalEqualityEmissions"
            }
            ledger.commit()
        }
    }

    /** Consumes the exact stdlib `SafeContinuation.resumeWith` projection web once. */
    private inner class SafeContinuationSROAEmission(
        val plan: ArcSafeContinuationSROAKotlinIRCodegenPlan,
    ) {
        private inner class Site(
            val selection: ArcSafeContinuationSROAIRSelection<IrElement>,
        ) {
            val ledger = ArcSafeContinuationSROAPhysicalEmissionLedger<IrElement, LLVMValueRef>(selection)
            var delegateSlot: LLVMValueRef? = null
            var resultSlot: LLVMValueRef? = null
            var resultBoxBound = false
        }

        private val sites = plan.sites.map(::Site)
        private val sitesByLocal = IdentityHashMap<IrVariable, Site>()
        private val sitesBySelectedCall = IdentityHashMap<IrCall, Site>()
        private val sitesByStructuralCall = IdentityHashMap<IrCall, Site>()
        private var activeResumeSite: Site? = null

        init {
            sites.forEach { site ->
                val bindings = site.selection.bindings
                val local = bindings.local as? IrVariable
                    ?: error("SafeContinuation SROA local binding changed kind")
                check(sitesByLocal.put(local, site) == null) {
                    "SafeContinuation SROA local belongs to two physical sites"
                }
                listOf(bindings.resumeCall, bindings.getOrThrowCall).forEach { binding ->
                    val call = binding as? IrCall
                        ?: error("SafeContinuation SROA selected call binding changed kind")
                    check(sitesBySelectedCall.put(call, site) == null) {
                        "SafeContinuation SROA call belongs to two physical sites"
                    }
                }
                (listOf(
                    bindings.interceptedCall,
                    bindings.resumeValueProducer,
                    bindings.resultCompanionGetter,
                    bindings.resultBoxIntrinsic,
                    bindings.resultConstructor,
                ) + bindings.structuralUnitCalls).forEach { binding ->
                    val call = binding as? IrCall
                        ?: error("SafeContinuation SROA structural binding changed kind")
                    check(sitesByStructuralCall.put(call, site) == null) {
                        "SafeContinuation SROA structural call belongs to two physical sites"
                    }
                }
            }
        }

        /** Observe ordinary calls before their normal emission; selected calls are handled below. */
        fun observeStructuralCall(call: IrCall) {
            sitesByStructuralCall[call]?.ledger?.observeStructuralCall(call)
        }

        fun emitAllocation(variable: IrVariable): Boolean {
            val site = sitesByLocal[variable] ?: return false
            val bindings = site.selection.bindings
            val allocation = bindings.allocation as? IrConstructorCall
                ?: error("SafeContinuation SROA allocation binding changed kind")
            val constructor = bindings.constructor as? IrConstructor
                ?: error("SafeContinuation SROA constructor binding changed kind")
            val intercepted = bindings.interceptedCall as? IrCall
                ?: error("SafeContinuation SROA intercepted binding changed kind")
            require(variable.initializer === allocation && allocation.symbol.owner === constructor &&
                    context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                    !context.shouldContainDebugInfo() && !context.config.arcDiagnosticsEnabled) {
                "SafeContinuation SROA allocation escaped its authenticated emission boundary"
            }

            // Preserve the ordinary throwing intercepted() call and route its +1 result into the
            // first explicitly authenticated scalar slot; only the wrapper allocations and their
            // selected operations are suppressed.
            val physicalSlot = functionGenerationContext.vars.createAnonymousSlot()
            val delegate = evaluateExpression(intercepted, physicalSlot)
            require(functionGenerationContext.arcResultIsAlreadyOwnedBySlot(delegate, physicalSlot)) {
                "SafeContinuation SROA intercepted result did not initialize its exact scalar +1 slot"
            }
            site.ledger.consumeAllocation(variable, allocation, constructor, physicalSlot)
            site.delegateSlot = physicalSlot
            return true
        }

        fun isSelectedCall(call: IrCall): Boolean = call in sitesBySelectedCall

        /** Route the authenticated Result payload box directly into the preallocated scalar slot. */
        fun structuralResultSlot(call: IrCall): LLVMValueRef? {
            val site = activeResumeSite ?: return null
            if (call !== site.selection.bindings.resultBoxIntrinsic) return null
            check(sitesByStructuralCall[call] === site) {
                "SafeContinuation SROA Result box escaped its selected resume"
            }
            return requireNotNull(site.resultSlot) {
                "SafeContinuation SROA Result box has no preallocated scalar slot"
            }
        }

        /** Seal the exact stdlib box ABI's +1 write after its normal-success call edge. */
        fun bindStructuralResult(call: IrCall, result: LLVMValueRef, physicalSlot: LLVMValueRef?) {
            val site = activeResumeSite ?: return
            if (call !== site.selection.bindings.resultBoxIntrinsic) return
            val expectedSlot = requireNotNull(site.resultSlot)
            require(physicalSlot == expectedSlot && result.type == codegen.kObjHeaderPtr) {
                "SafeContinuation SROA Result box changed its explicit object-result ABI"
            }
            // The selector authenticates this exact compiler-owned stdlib box declaration and its
            // ordinary-heap +1 convention. Generic callDirect intentionally cannot infer that fact
            // for external/builtin producers, so publish it only at this identity-sealed boundary.
            functionGenerationContext.markArcResultOwnedBySlot(result, expectedSlot)
            require(functionGenerationContext.arcResultIsAlreadyOwnedBySlot(result, expectedSlot)) {
                "SafeContinuation SROA Result box did not initialize its scalar +1 slot"
            }
            check(!site.resultBoxBound) { "SafeContinuation SROA Result box emitted twice" }
            site.resultBoxBound = true
        }

        fun emitSelectedCall(call: IrCall, requestedResultSlot: LLVMValueRef?): LLVMValueRef {
            val site = requireNotNull(sitesBySelectedCall[call]) {
                "unknown SafeContinuation SROA selected call"
            }
            val bindings = site.selection.bindings
            return when {
                call === bindings.resumeCall -> {
                    check(site.delegateSlot != null && site.resultSlot == null) {
                        "SafeContinuation SROA resume reached before allocation or twice"
                    }
                    val argument = bindings.resumeResultArgument as? IrExpression
                        ?: error("SafeContinuation SROA resume argument binding changed kind")
                    val physicalSlot = functionGenerationContext.vars.createAnonymousSlot()
                    site.resultSlot = physicalSlot
                    check(activeResumeSite == null) { "nested SafeContinuation SROA resume emission" }
                    try {
                        activeResumeSite = site
                        evaluateExpression(argument, null)
                    } finally {
                        activeResumeSite = null
                    }
                    require(site.resultBoxBound) {
                        "SafeContinuation SROA resume result did not initialize its exact scalar +1 slot"
                    }
                    site.ledger.consumeResume(call, argument, physicalSlot)
                    codegen.theUnitInstanceRef.llvm
                }
                call === bindings.getOrThrowCall -> {
                    val sourceSlot = requireNotNull(site.resultSlot) {
                        "SafeContinuation SROA getOrThrow reached before resume"
                    }
                    val delegateSlot = requireNotNull(site.delegateSlot) {
                        "SafeContinuation SROA getOrThrow has no delegate slot"
                    }
                    val destinationSlot = requestedResultSlot ?: functionGenerationContext.vars.createAnonymousSlot()
                    require(sourceSlot != destinationSlot && delegateSlot != destinationSlot) {
                        "SafeContinuation SROA caller result slot aliases scalar storage"
                    }
                    val result = functionGenerationContext.loadSlot(sourceSlot, false, null)
                    functionGenerationContext.moveArcOwnedReferenceIntoReturnSlot(
                        result,
                        sourceSlot,
                        destinationSlot,
                        verifiedPhysicalSlotOwnership = true,
                    )
                    // Publish the result before releasing the intercepted delegate, matching the
                    // authenticated consuming-storage plan even if that release runs deinit code.
                    functionGenerationContext.storeStackRef(codegen.kNullObjHeaderPtr, delegateSlot)
                    site.ledger.consumeGetOrThrow(call, sourceSlot, destinationSlot, delegateSlot)
                    result
                }
                else -> error("SafeContinuation SROA selected-call identity drifted")
            }
        }

        fun verifyConsumed() {
            check(activeResumeSite == null) { "unterminated SafeContinuation SROA resume emission" }
            sites.forEach { site ->
                site.ledger.verifyComplete()
                val local = site.selection.bindings.local as IrVariable
                context.log {
                    "ARC synchronous SafeContinuation SROA emitted production site: " +
                            plan.function.fqNameForIrSerialization.asString() + "/${local.name}; emitted=true"
                }
            }
        }
    }

    /** Consumes the exact stdlib `SafeContinuation.resumeWith` projection web once. */
    private inner class SafeContinuationResumeBorrowEmission(
        val plan: ArcSafeContinuationResumeBorrowPlan,
    ) {
        private val ledger = ArcSafeContinuationResumeBorrowConsumptionLedger(plan.borrowedResultRefLoads)

        fun markLoad(load: IrGetField) {
            requireNotNull(plan.consumersByLoad[load]) {
                "SafeContinuation.resumeWith borrowed projection changed identity: ${ir2string(load)}"
            }
            require(load.symbol.owner === plan.resultRefField) {
                "SafeContinuation.resumeWith borrowed projection changed field identity"
            }
            ledger.consume(load)
        }

        fun verifyConsumed() = ledger.verifyComplete()
    }

    /** Consumes every identity-authenticated copy/destroy/use authorization exactly once. */
    private inner class MatchingSetLocalAliasEmission(
        selections: List<ArcMatchingSetLocalAliasIRSelection>,
    ) {
        private val selections = selections.toList()
        private val ledger = ArcMatchingSetConsumptionLedger(
            selections.flatMapTo(linkedSetOf()) { it.allEventIds }
        )
        private val declarations = Collections.newSetFromMap(IdentityHashMap<IrVariable, Boolean>())

        fun markDeclaration(selection: ArcMatchingSetLocalAliasIRSelection, variable: IrVariable) {
            check(variable === selection.variable && declarations.add(variable)) {
                "duplicate or drifted matching-set local declaration: ${ir2string(variable)}"
            }
            ledger.consume(selection.incrementEventId)
            // createImmutable allocates no owning frame slot, so the exact LeaveFrame-owned
            // destroy paired with this variable is suppressed together with initialization.
            ledger.consume(selection.decrementEventId)
        }

        fun markRead(selection: ArcMatchingSetLocalAliasIRSelection, read: IrGetValue) {
            ledger.consume(requireNotNull(selection.readEventIds[read]) {
                "matching-set local read changed identity: ${ir2string(read)}"
            })
        }

        fun verifyConsumed() {
            check(declarations.size == selections.size && selections.all { it.variable in declarations }) {
                "matching-set local declarations drifted: expected=${selections.size}, actual=${declarations.size}"
            }
            ledger.verifyComplete()
        }
    }

    /** One exact, verifier-selected `BaseContinuationImpl.resumeWith` emission. */
    private inner class CoroutineGuaranteedPhiEmission(
        val selection: ArcCoroutineGuaranteedPhiIRSelection,
    ) {
        lateinit var currentPhi: LLVMValueRef
        lateinit var parameterPhi: LLVMValueRef
        private val declarations = Collections.newSetFromMap(IdentityHashMap<IrVariable, Boolean>())
        private val reads = Collections.newSetFromMap(IdentityHashMap<IrGetValue, Boolean>())
        private val stores = Collections.newSetFromMap(IdentityHashMap<IrSetValue, Boolean>())
        private val calls = Collections.newSetFromMap(IdentityHashMap<IrCall, Boolean>())
        private var loopEmissions = 0
        private var entryIncoming = 0
        private var backedgeIncoming = 0
        private var currentStoreBlock: LLVMBasicBlockRef? = null
        private var parameterStoreBlock: LLVMBasicBlockRef? = null

        fun markDeclaration(variable: IrVariable) {
            check(declarations.add(variable)) { "duplicate selected coroutine declaration emission: ${ir2string(variable)}" }
        }

        fun markRead(read: IrGetValue) {
            check(reads.add(read)) { "duplicate selected coroutine read emission: ${ir2string(read)}" }
        }

        fun markStore(store: IrSetValue) {
            check(stores.add(store)) { "duplicate selected coroutine store emission: ${ir2string(store)}" }
            if (store === selection.currentBackedgeStore) currentStoreBlock = functionGenerationContext.currentBlock
            if (store === selection.parameterBackedgeStore) parameterStoreBlock = functionGenerationContext.currentBlock
        }

        fun markCall(call: IrCall) {
            if (call === selection.invokeSuspend || call === selection.releaseIntercepted) {
                check(calls.add(call)) { "duplicate selected coroutine call emission: ${ir2string(call)}" }
            }
        }

        fun markLoop() { check(loopEmissions++ == 0) { "duplicate selected coroutine loop emission" } }
        fun markEntryIncoming() { entryIncoming++ }
        fun markBackedgeIncoming(
            backedge: LLVMBasicBlockRef,
            loopHeader: LLVMBasicBlockRef,
            entryPredecessor: LLVMBasicBlockRef,
        ) {
            val storeBlock = currentStoreBlock
            check(storeBlock != null && parameterStoreBlock == storeBlock) {
                "selected coroutine owning stores were not emitted together"
            }
            val blocks = functionGenerationContext.function.basicBlocks().toList()
            val blockValues = blocks.associateBy { LLVMBasicBlockAsValue(it) }
            val successors = blocks.associateWith { block ->
                val terminator = LLVMGetBasicBlockTerminator(block)
                if (terminator == null) emptyList() else getOperands(terminator).mapNotNull(blockValues::get)
            }
            check(hasExactCoroutinePhysicalBackedge(
                successors, storeBlock, backedge, loopHeader, entryPredecessor,
            )) {
                "selected coroutine owning stores do not dominate an exact single physical backedge"
            }
            check(LLVMCountIncoming(currentPhi) == 2 && LLVMCountIncoming(parameterPhi) == 2) {
                "selected coroutine phis do not cover exactly entry plus one physical backedge"
            }
            backedgeIncoming++
        }

        fun verifyConsumed() {
            check(declarations.size == 3 &&
                    selection.current in declarations && selection.parameterState in declarations &&
                    selection.currentIterationBorrow in declarations &&
                    reads.size == 2 && selection.currentJoinedRead in reads && selection.parameterJoinedRead in reads &&
                    stores.size == 2 && selection.currentBackedgeStore in stores &&
                    selection.parameterBackedgeStore in stores &&
                    calls.size == 2 && selection.invokeSuspend in calls && selection.releaseIntercepted in calls &&
                    loopEmissions == 1 && entryIncoming == 1 && backedgeIncoming == 1) {
                "selected coroutine guaranteed-phi shape drifted after planning: " +
                        "decls=${declarations.size} reads=${reads.size} stores=${stores.size} calls=${calls.size} " +
                        "loops=$loopEmissions entry=$entryIncoming backedge=$backedgeIncoming"
            }
        }
    }

    private val intrinsicGeneratorEnvironment = object : IntrinsicGeneratorEnvironment {
        override val codegen: CodeGenerator
            get() = this@CodeGeneratorVisitor.codegen

        override val functionGenerationContext: FunctionGenerationContext
            get() = this@CodeGeneratorVisitor.functionGenerationContext

        override fun calculateLifetime(element: IrElement): Lifetime =
                resultLifetime(element)

        override val exceptionHandler: ExceptionHandler
            get() = currentCodeContext.exceptionHandler

        override fun evaluateCall(function: IrFunction, args: List<LLVMValueRef>, resultLifetime: Lifetime, superClass: IrClass?, resultSlot: LLVMValueRef?) =
                evaluateSimpleFunctionCall(function, args, resultLifetime, superClass, resultSlot)

        override fun evaluateExplicitArgs(expression: IrFunctionAccessExpression): List<LLVMValueRef> =
                this@CodeGeneratorVisitor.evaluateExplicitArgs(expression)

        override fun evaluateExpression(value: IrExpression, resultSlot: LLVMValueRef?): LLVMValueRef =
                this@CodeGeneratorVisitor.evaluateExpression(value, resultSlot)

        override fun getObjectFieldPointer(thisRef: LLVMValueRef, field: IrField): LLVMValueRef =
                this@CodeGeneratorVisitor.fieldPtrOfClass(thisRef, field)

        override fun getStaticFieldPointer(field: IrField) =
                this@CodeGeneratorVisitor.staticFieldPtr(field, functionGenerationContext)
    }

    private val intrinsicGenerator = IntrinsicGenerator(intrinsicGeneratorEnvironment)

    /**
     * Fake [CodeContext] that doesn't support any operation.
     *
     * During function code generation [FunctionScope] should be set up.
     */
    private object TopLevelCodeContext : CodeContext {
        private fun unsupported(any: Any? = null): Nothing = throw UnsupportedOperationException(if (any is IrElement) any.render() else any?.toString() ?: "")

        override fun genReturn(target: IrSymbolOwner, value: LLVMValueRef?) = unsupported(target)

        override fun getReturnSlot(target: IrSymbolOwner): LLVMValueRef? = unsupported(target)

        override fun genBreak(destination: IrBreak) = unsupported()

        override fun genContinue(destination: IrContinue) = unsupported()

        override val exceptionHandler get() = unsupported()

        override fun genDeclareVariable(variable: IrVariable, value: LLVMValueRef?, variableLocation: VariableDebugLocation?) = unsupported(variable)

        override fun getDeclaredValue(value: IrValueDeclaration) = -1

        override fun genGetValue(value: IrValueDeclaration, resultSlot: LLVMValueRef?) = unsupported(value)

        override fun functionScope(): CodeContext? = null

        override fun fileScope(): CodeContext? = null

        override fun classScope(): CodeContext? = null

        override fun addResumePoint(bbLabel: LLVMBasicBlockRef) = unsupported(bbLabel)

        override fun returnableBlockScope(): CodeContext? = null

        override fun location(offset: Int): LocationInfo? = unsupported()

        override fun scope(): DIScopeOpaqueRef? = unsupported()
    }

    /**
     * The [CodeContext] which can define some operations and delegate other ones to [outerContext]
     */
    private abstract class InnerScope(val outerContext: CodeContext) : CodeContext by outerContext

    /**
     * Convenient [InnerScope] implementation that is bound to the [currentCodeContext].
     */
    private abstract inner class InnerScopeImpl : InnerScope(currentCodeContext)
    /**
     * Executes [block] with [codeContext] substituted as [currentCodeContext].
     */
    private inline fun <R> using(codeContext: CodeContext?, block: () -> R): R {
        val oldCodeContext = currentCodeContext
        if (codeContext != null) {
            currentCodeContext = codeContext
            codeContext.onEnter()
        }
        try {
            return block()
        } finally {
            codeContext?.onExit()
            currentCodeContext = oldCodeContext
        }
    }

    private fun <T:IrElement> findCodeContext(entry: T, context:CodeContext?, predicate: CodeContext.(T) -> Boolean): CodeContext? {
        if(context == null)
            //TODO: replace `return null` with `throw NoContextFound()` ASAP.
            return null
        if (context.predicate(entry))
            return context
        return findCodeContext(entry, (context as? InnerScope)?.outerContext, predicate)
    }


    private inline fun <R> switchSymbolizationContextTo(symbol: IrFunctionSymbol, block: () -> R): R? {
        val functionContext = findCodeContext(symbol.owner, currentCodeContext) {
            val declaration = (this as? FunctionScope)?.declaration
            val returnableBlock = (this as? ReturnableBlockScope)?.returnableBlock
            val inlinedFunction = returnableBlock?.inlineFunction
            declaration == it || inlinedFunction == it
        } ?: return null

        /**
         * We can't switch context safely, only for symbolzation needs: location, scope detection.
         */
        using(object: InnerScopeImpl() {
            override fun location(offset: Int): LocationInfo? = functionContext.location(offset)

            override fun scope(): DIScopeOpaqueRef? = functionContext.scope()

        }) {
            return block()
        }
    }
    private fun appendCAdapters(elements: CAdapterExportedElements) {
        CAdapterCodegen(codegen, generationState).buildAllAdaptersRecursively(elements)
    }

    private fun FunctionGenerationContext.initThreadLocalField(irField: IrField) {
        val initializer = irField.initializer ?: return
        val address = staticFieldPtr(irField, this)
        storeAny(evaluateExpression(initializer.expression), address, false)
    }

    private fun FunctionGenerationContext.initGlobalField(irField: IrField) {
        val address = staticFieldPtr(irField, this)
        val initialValue = if (irField.hasNonConstInitializer) {
            val initialization = evaluateExpression(irField.initializer!!.expression)
            if (irField.shouldBeFrozen(context))
                freeze(initialization, currentCodeContext.exceptionHandler)
            initialization
        } else {
            null
        }
        if (irField.needsGCRegistration(context)) {
            call(llvm.initAndRegisterGlobalFunction, listOf(address, initialValue
                    ?: kNullObjHeaderPtr))
        } else if (initialValue != null) {
            storeAny(initialValue, address, false)
        }
    }

    private fun buildInitializerFunctions(scopeState: ScopeInitializersGenerationState) {
        scopeState.globalInitFunction?.let { fileInitFunction ->
            generateFunction(codegen, fileInitFunction, fileInitFunction.location(start = true), fileInitFunction.location(start = false)) {
                using(FunctionScope(fileInitFunction, this)) {
                    val parameterScope = ParameterScope(fileInitFunction, functionGenerationContext)
                    using(parameterScope) usingParameterScope@{
                        using(VariableScope()) usingVariableScope@{
                            scopeState.topLevelFields
                                    .filter { it.storageKind(context) != FieldStorageKind.THREAD_LOCAL }
                                    .filterNot { context.shouldBeInitializedEagerly(it) }
                                    .forEach { initGlobalField(it) }
                            ret(null)
                        }
                    }
                }
            }
        }

        scopeState.threadLocalInitFunction?.let { fileInitFunction ->
            generateFunction(codegen, fileInitFunction, fileInitFunction.location(start = true), fileInitFunction.location(start = false)) {
                using(FunctionScope(fileInitFunction, this)) {
                    val parameterScope = ParameterScope(fileInitFunction, functionGenerationContext)
                    using(parameterScope) usingParameterScope@{
                        using(VariableScope()) usingVariableScope@{
                            scopeState.topLevelFields
                                    .filter { it.storageKind(context) == FieldStorageKind.THREAD_LOCAL }
                                    .filterNot { context.shouldBeInitializedEagerly(it) }
                                    .forEach { initThreadLocalField(it) }
                            ret(null)
                        }
                    }
                }
            }
        }
    }

    private fun runAndProcessInitializers(konanLibrary: KotlinLibrary?, f: () -> Unit) {
        val oldScopeState = llvm.initializersGenerationState.reset(ScopeInitializersGenerationState())
        f()
        val scopeState = llvm.initializersGenerationState.reset(oldScopeState)
        scopeState.takeIf { !it.isEmpty() }?.let {
            buildInitializerFunctions(it)
            val initNode = createInitNode(createInitBody(it))
            llvm.irStaticInitializers.add(IrStaticInitializer(konanLibrary, createInitCtor(initNode)))
        }
    }

    //-------------------------------------------------------------------------//

    override fun visitElement(element: IrElement) {
        TODO(ir2string(element))
    }

    //-------------------------------------------------------------------------//
    override fun visitModuleFragment(declaration: IrModuleFragment) {
        context.log{"visitModule                    : ${ir2string(declaration)}"}

        generationState.coverage.collectRegions(declaration)

        initializeCachedBoxes(generationState)
        declaration.acceptChildrenVoid(this)

        runAndProcessInitializers(null) {
            // Note: it is here because it also generates some bitcode.
            generationState.objCExport.generate(codegen)

            codegen.objCDataGenerator?.finishModule()

            generationState.coverage.writeRegionInfo()
            overrideRuntimeGlobals()
            appendLlvmUsed("llvm.used", llvm.usedFunctions.map { it.toConstPointer().llvm } + llvm.usedGlobals)
            appendLlvmUsed("llvm.compiler.used", llvm.compilerUsedGlobals)
            if (context.config.produce.isNativeLibrary) {
                context.cAdapterExportedElements?.let { appendCAdapters(it) }
            }
        }

        appendStaticInitializers()
    }

    //-------------------------------------------------------------------------//

    val ctorFunctionSignature = LlvmFunctionSignature(LlvmRetType(llvm.voidType))
    val kNodeInitType = LLVMGetTypeByName(llvm.module, "struct.InitNode")!!
    val kMemoryStateType = LLVMGetTypeByName(llvm.module, "struct.MemoryState")!!
    val kInitFuncType = LlvmFunctionSignature(LlvmRetType(llvm.voidType), listOf(LlvmParamType(llvm.int32Type), LlvmParamType(pointerType(kMemoryStateType))))

    //-------------------------------------------------------------------------//

    // Must be synchronized with Runtime.cpp
    val ALLOC_THREAD_LOCAL_GLOBALS = 0
    val INIT_GLOBALS = 1
    val INIT_THREAD_LOCAL_GLOBALS = 2
    val DEINIT_GLOBALS = 3

    val FILE_NOT_INITIALIZED = 0
    val FILE_INITIALIZED = 2

    private fun createInitBody(state: ScopeInitializersGenerationState): LlvmCallable {
        val initFunctionProto = kInitFuncType.toProto("", null, LLVMLinkage.LLVMPrivateLinkage)
        return generateFunction(codegen, initFunctionProto) {
            using(FunctionScope(function, this)) {
                val bbInit = basicBlock("init", null)
                val bbLocalInit = basicBlock("local_init", null)
                val bbLocalAlloc = basicBlock("local_alloc", null)
                val bbGlobalDeinit = basicBlock("global_deinit", null)
                val bbDefault = basicBlock("default", null) {
                    unreachable()
                }

                switch(function.param(0),
                        listOf(llvm.int32(INIT_GLOBALS) to bbInit,
                                llvm.int32(INIT_THREAD_LOCAL_GLOBALS) to bbLocalInit,
                                llvm.int32(ALLOC_THREAD_LOCAL_GLOBALS) to bbLocalAlloc,
                                llvm.int32(DEINIT_GLOBALS) to bbGlobalDeinit),
                        bbDefault)

                // Globals initializers may contain accesses to objects, so visit them first.
                appendingTo(bbInit) {
                    state.topLevelFields
                            .filter { context.shouldBeInitializedEagerly(it) }
                            .filterNot { it.storageKind(context) == FieldStorageKind.THREAD_LOCAL }
                            .forEach { initGlobalField(it) }
                    ret(null)
                }

                appendingTo(bbLocalInit) {
                    state.topLevelFields
                            .filter { context.shouldBeInitializedEagerly(it) }
                            .filter { it.storageKind(context) == FieldStorageKind.THREAD_LOCAL }
                            .forEach { initThreadLocalField(it) }
                    ret(null)
                }

                appendingTo(bbLocalAlloc) {
                    if (llvm.tlsCount > 0) {
                        val memory = function.param(1)
                        call(llvm.addTLSRecord, listOf(memory, llvm.tlsKey, llvm.int32(llvm.tlsCount)))
                    }
                    ret(null)
                }

                appendingTo(bbGlobalDeinit) {
                    state.topLevelFields
                            // Only if a subject for memory management.
                            .forEach { irField ->
                                if (irField.type.binaryTypeIsReference() && irField.storageKind(context) != FieldStorageKind.THREAD_LOCAL) {
                                    val address = staticFieldPtr(irField, functionGenerationContext)
                                    storeHeapRef(codegen.kNullObjHeaderPtr, address)
                                }
                            }
                    state.globalSharedObjects.forEach { address ->
                        storeHeapRef(codegen.kNullObjHeaderPtr, address)
                    }
                    state.globalInitState?.let {
                        store(llvm.int32(FILE_NOT_INITIALIZED), it)
                    }
                    ret(null)
                }
            }
        }
    }

    //-------------------------------------------------------------------------//
    // Creates static struct InitNode $nodeName = {$initName, NULL};

    private fun createInitNode(initFunction: LlvmCallable): LLVMValueRef {
        val nextInitNode = LLVMConstNull(pointerType(kNodeInitType))
        val argList = cValuesOf(initFunction.toConstPointer().llvm, nextInitNode)
        // Create static object of class InitNode.
        val initNode = LLVMConstNamedStruct(kNodeInitType, argList, 2)!!
        // Create global variable with init record data.
        return codegen.staticData.placeGlobal("init_node", constPointer(initNode), isExported = false).llvmGlobal
    }

    //-------------------------------------------------------------------------//

    private fun createInitCtor(initNodePtr: LLVMValueRef): LlvmCallable {
        val ctorProto = ctorFunctionSignature.toProto("", null, LLVMLinkage.LLVMPrivateLinkage)
        val ctor = generateFunctionNoRuntime(codegen, ctorProto) {
            call(llvm.appendToInitalizersTail, listOf(initNodePtr))
            ret(null)
        }
        return ctor
    }

    //-------------------------------------------------------------------------//

    override fun visitFile(declaration: IrFile) {
        @Suppress("UNCHECKED_CAST")
        using(FileScope(declaration)) {
            runAndProcessInitializers(declaration.konanLibrary) {
                declaration.acceptChildrenVoid(this)
            }
        }
    }

    fun dispose() {
        arcOwnership.immortalCompletionContextsByClass.values.forEach { it.ledger.verifyComplete() }
    }

    //-------------------------------------------------------------------------//

    private open inner class StackLocalsScope() : InnerScopeImpl() {
        override fun onEnter() {
            functionGenerationContext.stackLocalsManager.enterScope()
        }
        override fun onExit() {
            functionGenerationContext.stackLocalsManager.exitScope()
        }
    }

    private inner class LoopScope(val loop: IrLoop) : StackLocalsScope() {
        val loopExit  = functionGenerationContext.basicBlock("loop_exit", loop.endLocation)
        val loopCheck = functionGenerationContext.basicBlock("loop_check", loop.condition.startLocation)

        override fun genBreak(destination: IrBreak) {
            if (destination.loop == loop)
                functionGenerationContext.br(loopExit)
            else
                super.genBreak(destination)
        }

        override fun genContinue(destination: IrContinue) {
            if (destination.loop == loop) {
                functionGenerationContext.br(loopCheck)
            } else
                super.genContinue(destination)
        }
    }

    //-------------------------------------------------------------------------//

    fun evaluateBreak(destination: IrBreak): LLVMValueRef {
        currentCodeContext.genBreak(destination)
        return codegen.kNothingFakeValue
    }

    //-------------------------------------------------------------------------//

    fun evaluateContinue(destination: IrContinue): LLVMValueRef {
        currentCodeContext.genContinue(destination)
        return codegen.kNothingFakeValue
    }

    //-------------------------------------------------------------------------//

    override fun visitConstructor(declaration: IrConstructor) {
        context.log{"visitConstructor               : ${ir2string(declaration)}"}
        visitFunction(declaration)
    }

    //-------------------------------------------------------------------------//

    override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer) {
        context.log{"visitAnonymousInitializer      : ${ir2string(declaration)}"}
    }

    //-------------------------------------------------------------------------//

    /**
     * The scope of variable visibility.
     */
    private inner class VariableScope : InnerScopeImpl() {

        override fun genDeclareVariable(variable: IrVariable, value: LLVMValueRef?, variableLocation: VariableDebugLocation?): Int {
            currentBranchGuaranteedPhiEmission?.let { emission ->
                if (variable === emission.selection.selectedVariable) {
                    require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                            !context.shouldContainDebugInfo() && !context.config.arcDiagnosticsEnabled &&
                            value != null && variable.isVar && variable.type.binaryTypeIsReference() &&
                            (variable.initializer as? IrConst<*>)?.value == null) {
                        "branch guaranteed-phi local escaped its exact declaration shape: ${ir2string(variable)}"
                    }
                    emission.markDeclaration(variable)
                    return functionGenerationContext.vars.createNonOwningReference(variable, value)
                }
            }
            arcOwnership.matchingSetLocalAliases[variable]?.let { selection ->
                val initializer = variable.initializer as? IrGetValue
                require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                        !context.shouldContainDebugInfo() && !context.config.arcDiagnosticsEnabled &&
                        value != null && variable.isVar && variable.type.binaryTypeIsReference() &&
                        initializer === selection.initializer && initializer.symbol.owner === selection.anchor &&
                        selection.function === functionGenerationContext.irFunction) {
                    "matching-set local alias escaped its exact declaration shape: ${ir2string(variable)}"
                }
                requireNotNull(currentMatchingSetLocalAliasEmission) {
                    "matching-set local alias was emitted outside its selected function"
                }.markDeclaration(selection, variable)
                return functionGenerationContext.vars.createImmutable(variable, value)
            }
            if (variable in arcOwnership.rootedProjectionVariables) {
                require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                        !context.shouldContainDebugInfo() && value != null && variable.isVar &&
                        variable.type.binaryTypeIsReference()) {
                    "rooted projection cursor escaped its verified declaration boundary: ${ir2string(variable)}"
                }
                return functionGenerationContext.vars.createNonOwningReference(variable, value)
            }
            if (variable in arcOwnership.borrowedGuaranteedAliases) {
                require(value != null) { "Borrowed guaranteed alias must have an initializer: ${ir2string(variable)}" }
                return functionGenerationContext.vars.createImmutable(variable, value)
            }
            if (variable in arcOwnership.resultCompanionImmortalVariables) {
                require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                        !context.shouldContainDebugInfo() && !context.config.arcDiagnosticsEnabled &&
                        value != null && !variable.isVar) {
                    "Result.Companion immortal temporary escaped its verified declaration boundary: ${ir2string(variable)}"
                }
                return functionGenerationContext.vars.createImmutable(variable, value)
            }
            return functionGenerationContext.vars.createVariable(variable, value, variableLocation)
        }

        override fun getDeclaredValue(value: IrValueDeclaration): Int {
            val index = functionGenerationContext.vars.indexOf(value)
            return if (index < 0) super.getDeclaredValue(value) else index
        }

        override fun genGetValue(value: IrValueDeclaration, resultSlot: LLVMValueRef?): LLVMValueRef {
            val index = functionGenerationContext.vars.indexOf(value)
            if (index < 0) {
                return super.genGetValue(value, resultSlot)
            } else {
                return functionGenerationContext.vars.load(index, resultSlot)
            }
        }
    }

    /**
     * The scope of parameter visibility.
     */
    private open inner class ParameterScope(
            function: IrFunction?,
            private val functionGenerationContext: FunctionGenerationContext): InnerScopeImpl() {

        val parameters = bindParameters(function)

        init {
            if (function != null) {
                parameters.forEach {
                    val parameter = it.key

                    if (context.shouldContainDebugInfo()) {
                        val local = functionGenerationContext.vars.createParameterOnStack(
                                parameter, debugInfoIfNeeded(function, parameter))
                        functionGenerationContext.mapParameterForDebug(local, it.value)
                    } else {
                        functionGenerationContext.vars.createParameter(parameter, it.value)
                    }
                }
            }
        }

        override fun genGetValue(value: IrValueDeclaration, resultSlot: LLVMValueRef?): LLVMValueRef {
            val index = functionGenerationContext.vars.indexOf(value)
            if (index < 0) {
                return super.genGetValue(value, resultSlot)
            } else {
                return functionGenerationContext.vars.load(index, resultSlot)
            }
        }
    }

    /**
     * The [CodeContext] enclosing the entire function body.
     */
    private inner class FunctionScope private constructor(
            val functionGenerationContext: FunctionGenerationContext,
            val declaration: IrFunction?,
            val llvmFunction: LlvmCallable) : InnerScopeImpl() {

        constructor(declaration: IrFunction, functionGenerationContext: FunctionGenerationContext) :
                this(functionGenerationContext, declaration, codegen.llvmFunction(declaration))

        constructor(llvmFunction: LlvmCallable, functionGenerationContext: FunctionGenerationContext) :
                this(functionGenerationContext, null, llvmFunction)

        val coverageInstrumentation: LLVMCoverageInstrumentation? =
                generationState.coverage.tryGetInstrumentation(declaration) { function, args -> functionGenerationContext.call(function, args) }

        override fun genReturn(target: IrSymbolOwner, value: LLVMValueRef?) {
            if (declaration == null || target == declaration) {
                if ((target as IrFunction).returnsUnit()) {
                    functionGenerationContext.ret(null)
                } else {
                    functionGenerationContext.ret(value!!)
                }
            } else {
                super.genReturn(target, value)
            }
        }

        override fun getReturnSlot(target: IrSymbolOwner) : LLVMValueRef? {
            return if (declaration == null || target == declaration) {
                functionGenerationContext.returnSlot
            } else {
                super.getReturnSlot(target)
            }
        }

        override val exceptionHandler: ExceptionHandler
            get() = ExceptionHandler.Caller

        override fun functionScope(): CodeContext = this


        private val scope by lazy {
            if (!context.shouldContainLocationDebugInfo() || declaration == null)
                return@lazy null
            declaration.scope() ?: llvmFunction.scope(0, debugInfo.subroutineType(codegen.llvmTargetData, listOf(context.irBuiltIns.intType)), false)
        }

        private val fileScope = (fileScope() as? FileScope)
        override fun location(offset: Int) = scope?.let { scope -> fileScope?.let{LocationInfo(scope, it.file.fileEntry.line(offset), it.file.fileEntry.column(offset)) } }

        override fun scope() = scope
    }

    private val functionGenerationContext
            get() = (currentCodeContext.functionScope() as FunctionScope).functionGenerationContext
    /**
     * Binds LLVM function parameters to IR parameter descriptors.
     */
    private fun bindParameters(function: IrFunction?): Map<IrValueParameter, LLVMValueRef> {
        if (function == null) return emptyMap()
        return function.allParameters.mapIndexed { i, irParameter ->
            val parameter = codegen.param(function, i)
            assert(irParameter.type.toLLVMType(llvm) == parameter.type)
            irParameter to parameter
        }.toMap()
    }

    private val IrDeclarationContainer.initVariableSuffix get() = when (this) {
        is IrFile -> "${fqName}\$${fileEntry.name}"
        else -> fqNameForIrSerialization.asString()
    }

    private fun getGlobalInitStateFor(container: IrDeclarationContainer): LLVMValueRef =
            llvm.initializersGenerationState.fileGlobalInitStates.getOrPut(container) {
                codegen.addGlobal("state_global$${container.initVariableSuffix}", llvm.int32Type, false).also {
                    LLVMSetInitializer(it, llvm.int32(FILE_NOT_INITIALIZED))
                    LLVMSetLinkage(it, LLVMLinkage.LLVMInternalLinkage)
                }
            }

    private fun getThreadLocalInitStateFor(container: IrDeclarationContainer): AddressAccess =
            llvm.initializersGenerationState.fileThreadLocalInitStates.getOrPut(container) {
                codegen.addKotlinThreadLocal("state_thread_local$${container.initVariableSuffix}", llvm.int32Type,
                        LLVMPreferredAlignmentOfType(llvm.runtime.targetData, llvm.int32Type)).also {
                    LLVMSetInitializer((it as GlobalAddressAccess).getAddress(null), llvm.int32(FILE_NOT_INITIALIZED))
                }
            }

    override fun visitFunction(declaration: IrFunction) {
        context.log{"visitFunction                  : ${ir2string(declaration)}"}

        val scopeState = llvm.initializersGenerationState.scopeState
        if (declaration.origin == DECLARATION_ORIGIN_STATIC_GLOBAL_INITIALIZER) {
            require(scopeState.globalInitFunction == null) { "There can only be at most one global file initializer" }
            require(declaration.body == null) { "The body of file initializer should be null" }
            require(declaration.valueParameters.isEmpty()) { "File initializer must be parameterless" }
            require(declaration.returnsUnit()) { "File initializer must return Unit" }
            scopeState.globalInitFunction = declaration
            scopeState.globalInitState = getGlobalInitStateFor(declaration.parent as IrDeclarationContainer)
        }
        if (declaration.origin == DECLARATION_ORIGIN_STATIC_THREAD_LOCAL_INITIALIZER
                || declaration.origin == DECLARATION_ORIGIN_STATIC_STANDALONE_THREAD_LOCAL_INITIALIZER) {
            require(scopeState.threadLocalInitFunction == null) { "There can only be at most one thread local file initializer" }
            require(declaration.body == null) { "The body of file initializer should be null" }
            require(declaration.valueParameters.isEmpty()) { "File initializer must be parameterless" }
            require(declaration.returnsUnit()) { "File initializer must return Unit" }
            scopeState.threadLocalInitFunction = declaration
            scopeState.threadLocalInitState = getThreadLocalInitStateFor(declaration.parent as IrDeclarationContainer)
        }


        if (!declaration.shouldGenerateBody())
            return
        // Some special functions may have empty body, thay are handled separetely.
        val body = declaration.body ?: return
        val file = if (declaration.origin != IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA)
            null
        else ((declaration as? IrSimpleFunction)?.attributeOwnerId as? IrSimpleFunction)?.let { context.irLinker.getFileOf(it) }?.takeIf {
            (currentCodeContext.fileScope() as FileScope).file != it
        }
        val scope = file?.let {
            FileScope(it)
        }
        val previousArcOwnedResultForwarding = currentArcOwnedResultForwarding
        val previousDiscardedReturnedReceiverSeedSlots = currentDiscardedReturnedReceiverSeedSlots
        val previousCoroutineGuaranteedPhiEmission = currentCoroutineGuaranteedPhiEmission
        val previousMatchingSetLocalAliasEmission = currentMatchingSetLocalAliasEmission
        val previousSafeContinuationResumeBorrowEmission = currentSafeContinuationResumeBorrowEmission
        val previousSafeContinuationSROAEmission = currentSafeContinuationSROAEmission
        val previousBranchGuaranteedPhiEmission = currentBranchGuaranteedPhiEmission
        val previousStringBuilderBackingArrayProjectionEmission =
                currentStringBuilderBackingArrayProjectionEmission
        val previousOwnedResultHeapStoreEmission = currentOwnedResultHeapStoreEmission
        val previousFreshOwnedFieldStoreEmission = currentFreshOwnedFieldStoreEmission
        val previousCoroutineEmptyContextImmortalReturnEmission =
            currentCoroutineEmptyContextImmortalReturnEmission
        val previousCoroutineSuspendedScopedBorrowEmission =
            currentCoroutineSuspendedScopedBorrowEmission
        val previousImmortalCompletionContextEmission = currentImmortalCompletionContextEmission
        currentDiscardedReturnedReceiverSeedSlots = IdentityHashMap()
        currentCoroutineGuaranteedPhiEmission = (declaration as? IrSimpleFunction)?.let {
            arcOwnership.coroutineGuaranteedPhiSelections[it]
        }?.let(::CoroutineGuaranteedPhiEmission)
        currentMatchingSetLocalAliasEmission = (declaration as? IrSimpleFunction)?.let {
            arcOwnership.matchingSetLocalAliasesByFunction[it]
        }?.takeIf { it.isNotEmpty() }?.let(::MatchingSetLocalAliasEmission)
        currentSafeContinuationResumeBorrowEmission = (declaration as? IrSimpleFunction)?.let {
            arcOwnership.safeContinuationResumeBorrowPlans[it]
        }?.let(::SafeContinuationResumeBorrowEmission)
        currentSafeContinuationSROAEmission = arcOwnership.safeContinuationSROAPlans[declaration]
            ?.let(::SafeContinuationSROAEmission)
        currentBranchGuaranteedPhiEmission = (declaration as? IrSimpleFunction)?.let {
            arcOwnership.branchGuaranteedPhiSelections[it]
        }?.let(::BranchGuaranteedPhiEmission)
        currentStringBuilderBackingArrayProjectionEmission = (declaration as? IrSimpleFunction)?.let {
            arcOwnership.stringBuilderBackingArrayProjectionPlans[it]
        }?.let(::StringBuilderBackingArrayProjectionEmission)
        currentOwnedResultHeapStoreEmission = (declaration as? IrSimpleFunction)?.let {
            arcOwnership.ownedResultHeapStoreSelections[it]
        }?.let(::OwnedResultHeapStoreEmission)
        currentFreshOwnedFieldStoreEmission = (declaration as? IrSimpleFunction)?.let {
            arcOwnership.freshOwnedFieldStorePlans[it]
        }?.takeIf { it.isNotEmpty() }?.let(::FreshOwnedFieldStoreEmission)
        currentCoroutineEmptyContextImmortalReturnEmission = (declaration as? IrSimpleFunction)?.let {
            arcOwnership.coroutineEmptyContextImmortalReturns[it]
        }?.let(::CoroutineEmptyContextImmortalReturnEmission)
        currentCoroutineSuspendedScopedBorrowEmission = arcOwnership
            .coroutineSuspendedScopedBorrowsByFunction[declaration]
            ?.let(::CoroutineSuspendedScopedBorrowEmission)
        currentImmortalCompletionContextEmission = when (declaration) {
            is IrConstructor -> arcOwnership.immortalCompletionContextsByConstructor[declaration]
            is IrSimpleFunction -> arcOwnership.immortalCompletionContextsByGetter[declaration]
            else -> null
        }?.let(::ImmortalCompletionContextEmission)
        currentArcOwnedResultForwarding = if (!context.shouldContainDebugInfo()) {
            (declaration as? IrSimpleFunction)?.let { arcOwnership.ownedResultForwarding[it] }
        } else {
            null
        }
        try {
            using(scope) {
                generateFunction(codegen, declaration,
                        declaration.location(start = true),
                        declaration.location(start = false)) {
                    using(FunctionScope(declaration, this)) {
                        val parameterScope = ParameterScope(declaration, functionGenerationContext)
                        using(parameterScope) usingParameterScope@{
                            using(VariableScope()) usingVariableScope@{
                                recordCoverage(body)
                                if (declaration.isReifiedInline) {
                                    callDirect(context.ir.symbols.throwIllegalStateExceptionWithMessage.owner,
                                            listOf(codegen.staticData.kotlinStringLiteral(
                                                    "unsupported call of reified inlined function `${declaration.fqNameForIrSerialization}`").llvm),
                                            Lifetime.IRRELEVANT, null)
                                    return@usingVariableScope
                                }
                                when (body) {
                                    is IrBlockBody -> for (statement in body.statements) {
                                        generateStatement(statement)
                                        // A lowered suspend lambda may append a synthetic function return after
                                        // the identity-selected tail return. Do not ask PositionHolder to emit that
                                        // unreachable statement: it would manufacture an `undef` epilogue edge and
                                        // defeat the exact result-slot proof recorded by the real return.
                                        if (declaration is IrSimpleFunction &&
                                            functionGenerationContext.isAfterTerminator() &&
                                            (statement as? IrExpression)?.containsSelectedCoroutineTailCall() == true
                                        ) break
                                    }
                                    is IrExpressionBody -> error("IrExpressionBody should've been lowered")
                                    is IrSyntheticBody -> throw AssertionError("Synthetic body ${body.kind} has not been lowered")
                                    else -> TODO(ir2string(body))
                                }
                                currentCoroutineGuaranteedPhiEmission?.verifyConsumed()
                                currentMatchingSetLocalAliasEmission?.verifyConsumed()
                                currentSafeContinuationResumeBorrowEmission?.verifyConsumed()
                                currentSafeContinuationSROAEmission?.verifyConsumed()
                                currentBranchGuaranteedPhiEmission?.verifyConsumed()
                                currentStringBuilderBackingArrayProjectionEmission?.verifyConsumed()
                                currentOwnedResultHeapStoreEmission?.verifyConsumed()
                                currentFreshOwnedFieldStoreEmission?.verifyConsumed()
                                currentCoroutineEmptyContextImmortalReturnEmission?.verifyConsumed()
                                currentCoroutineSuspendedScopedBorrowEmission?.verifyConsumed()
                            }
                        }
                    }
                }
            }
        } finally {
            currentArcOwnedResultForwarding = previousArcOwnedResultForwarding
            currentDiscardedReturnedReceiverSeedSlots = previousDiscardedReturnedReceiverSeedSlots
            currentCoroutineGuaranteedPhiEmission = previousCoroutineGuaranteedPhiEmission
            currentMatchingSetLocalAliasEmission = previousMatchingSetLocalAliasEmission
            currentSafeContinuationResumeBorrowEmission = previousSafeContinuationResumeBorrowEmission
            currentSafeContinuationSROAEmission = previousSafeContinuationSROAEmission
            currentBranchGuaranteedPhiEmission = previousBranchGuaranteedPhiEmission
            currentStringBuilderBackingArrayProjectionEmission =
                    previousStringBuilderBackingArrayProjectionEmission
            currentOwnedResultHeapStoreEmission = previousOwnedResultHeapStoreEmission
            currentFreshOwnedFieldStoreEmission = previousFreshOwnedFieldStoreEmission
            currentCoroutineEmptyContextImmortalReturnEmission =
                    previousCoroutineEmptyContextImmortalReturnEmission
            currentCoroutineSuspendedScopedBorrowEmission =
                    previousCoroutineSuspendedScopedBorrowEmission
            currentImmortalCompletionContextEmission = previousImmortalCompletionContextEmission
        }


        if (declaration.retainAnnotation(context.config.target)) {
            llvm.usedFunctions.add(codegen.llvmFunction(declaration))
        }

        if (context.shouldVerifyBitCode())
            verifyModule(llvm.module, "${declaration.descriptor.containingDeclaration}::${ir2string(declaration)}")
    }

    private fun IrFunction.location(start: Boolean) =
            if (context.shouldContainLocationDebugInfo() && startOffset != UNDEFINED_OFFSET) LocationInfo(
                scope = scope()!!,
                line = if (start) startLine() else endLine(),
                column = if (start) startColumn() else endColumn())
            else null

    //-------------------------------------------------------------------------//

    override fun visitClass(declaration: IrClass) {
        context.log{"visitClass                     : ${ir2string(declaration)}"}

        if (!declaration.requiresCodeGeneration()) {
            // For non-generated annotation classes generate only nested classes.
            declaration.declarations
                    .filterIsInstance<IrClass>()
                    .forEach { it.acceptVoid(this) }
            return
        }
        using(ClassScope(declaration)) {
            runAndProcessInitializers(declaration.konanLibrary) {
                declaration.declarations.forEach {
                    it.acceptVoid(this)
                }
            }
        }
    }

    override fun visitProperty(declaration: IrProperty) {
        declaration.getter?.acceptVoid(this)
        declaration.setter?.acceptVoid(this)
        declaration.backingField?.acceptVoid(this)
    }

    private fun needGlobalInit(field: IrField): Boolean {
        if (field.descriptor.containingDeclaration !is PackageFragmentDescriptor) return field.isStatic
        // TODO: add some smartness here. Maybe if package of the field is in never accessed
        // assume its global init can be actually omitted.
        return true
    }

    override fun visitField(declaration: IrField) {
        context.log{"visitField                     : ${ir2string(declaration)}"}
        debugFieldDeclaration(declaration)
        if (needGlobalInit(declaration)) {
            val type = declaration.type.toLLVMType(llvm)
            val globalPropertyAccess = generationState.llvmDeclarations.forStaticField(declaration).storageAddressAccess
            val initializer = declaration.initializer?.expression
            val globalProperty = (globalPropertyAccess as? GlobalAddressAccess)?.getAddress(null)
            if (globalProperty != null) {
                LLVMSetInitializer(globalProperty, when (initializer) {
                    is IrConst<*>, is IrConstantValue -> evaluateExpression(initializer)
                    else -> LLVMConstNull(type)
                })
                // (Cannot do this before the global is initialized).
                LLVMSetLinkage(globalProperty, LLVMLinkage.LLVMInternalLinkage)
            }
            llvm.initializersGenerationState.scopeState.topLevelFields.add(declaration)
        }
    }

    private fun recordCoverage(irElement: IrElement) {
        val scope = currentCodeContext.functionScope()
        if (scope is FunctionScope) {
            scope.coverageInstrumentation?.instrumentIrElement(irElement)
        }
    }

    //-------------------------------------------------------------------------//

    private fun evaluateExpression(value: IrExpression, resultSlot: LLVMValueRef? = null): LLVMValueRef {
        val opensPromotionBoundary = value in arcOwnership.scopedArcReferenceLoadBoundaries
        if (!opensPromotionBoundary) return evaluateExpressionImpl(value, resultSlot)

        val previousSlots = currentArcPromotionSlots
        val previousBoundary = currentArcPromotionBoundary
        val slots = mutableListOf<LLVMValueRef>()
        currentArcPromotionSlots = slots
        currentArcPromotionBoundary = value
        try {
            val result = evaluateExpressionImpl(value, resultSlot)
            if (!functionGenerationContext.isAfterTerminator()) {
                slots.asReversed().forEach { slot ->
                    functionGenerationContext.storeStackRef(codegen.kNullObjHeaderPtr, slot)
                }
            }
            return result
        } finally {
            currentArcPromotionSlots = previousSlots
            currentArcPromotionBoundary = previousBoundary
        }
    }

    private fun evaluateExpressionImpl(value: IrExpression, resultSlot: LLVMValueRef?): LLVMValueRef {
        updateBuilderDebugLocation(value)
        recordCoverage(value)
        when (value) {
            is IrTypeOperatorCall    -> return evaluateTypeOperator           (value, resultSlot)
            is IrCall                -> return evaluateCall                   (value, resultSlot)
            is IrDelegatingConstructorCall ->
                                        return evaluateCall                   (value, resultSlot)
            is IrConstructorCall     -> return evaluateCall                   (value, resultSlot)
            is IrInstanceInitializerCall ->
                                        return evaluateInstanceInitializerCall(value)
            is IrGetValue            -> return evaluateGetValue               (value, resultSlot)
            is IrSetValue            -> return evaluateSetValue               (value)
            is IrGetField            -> return evaluateGetField               (value, resultSlot)
            is IrSetField            -> return evaluateSetField               (value)
            is IrConst<*>            -> return evaluateConst                  (value).llvm
            is IrReturn              -> return evaluateReturn                 (value)
            is IrWhen                -> return evaluateWhen                   (value, resultSlot)
            is IrThrow               -> return evaluateThrow                  (value)
            is IrTry                 -> return evaluateTry                    (value)
            is IrReturnableBlock     -> return evaluateReturnableBlock        (value, resultSlot)
            is IrContainerExpression -> return evaluateContainerExpression    (value, resultSlot)
            is IrWhileLoop           -> return evaluateWhileLoop              (value)
            is IrDoWhileLoop         -> return evaluateDoWhileLoop            (value)
            is IrVararg              -> return evaluateVararg                 (value)
            is IrBreak               -> return evaluateBreak                  (value)
            is IrContinue            -> return evaluateContinue               (value)
            is IrGetObjectValue      -> return evaluateGetObjectValue         (value)
            is IrFunctionReference   -> return evaluateFunctionReference      (value)
            is IrSuspendableExpression ->
                                        return evaluateSuspendableExpression  (value, resultSlot)
            is IrSuspensionPoint     -> return evaluateSuspensionPoint        (value)
            is IrClassReference ->      return evaluateClassReference         (value)
            is IrConstantValue ->       return evaluateConstantValue          (value).llvm
            else                     -> {
                TODO(ir2string(value))
            }
        }
    }

    private fun generateStatement(statement: IrStatement) {
        when (statement) {
            is IrExpression -> evaluateExpression(statement)
            is IrVariable -> generateVariable(statement)
            else -> TODO(ir2string(statement))
        }
    }

    private fun IrStatement.generate() = generateStatement(this)

    //-------------------------------------------------------------------------//

    private fun evaluateGetObjectValue(value: IrGetObjectValue): LLVMValueRef {
        error("Should be lowered out: ${value.symbol.owner.render()}")
    }


    //-------------------------------------------------------------------------//

    private fun evaluateExpressionAndJump(expression: IrExpression, destination: ContinuationBlock) {
        val result = evaluateExpression(expression)

        // It is possible to check here whether the generated code has the normal continuation path
        // and do not generate any jump if not;
        // however such optimization can lead to phi functions with zero entries, which is not allowed by LLVM;
        // TODO: find the better solution.

        functionGenerationContext.jump(destination, result)
    }

    //-------------------------------------------------------------------------//

    /**
     * Represents the basic block which may expect a value:
     * when generating a [jump] to this block, one should provide the value.
     * Inside the block that value is accessible as [valuePhi].
     *
     * This class is designed to be used to generate Kotlin expressions that have a value and require branching.
     *
     * [valuePhi] may be `null`, which would mean `Unit` value is passed.
     */
    private data class ContinuationBlock(val block: LLVMBasicBlockRef, val valuePhi: LLVMValueRef?)

    private val ContinuationBlock.value: LLVMValueRef
        get() = this.valuePhi ?: codegen.theUnitInstanceRef.llvm

    /**
     * Jumps to [target] passing [value].
     */
    private fun FunctionGenerationContext.jump(target: ContinuationBlock, value: LLVMValueRef?) {
        val entry = target.block
        br(entry)
        if (target.valuePhi != null) {
            assignPhis(target.valuePhi to value!!)
        }
    }

    /**
     * Creates new [ContinuationBlock] that receives the value of given Kotlin type
     * and generates [code] starting from its beginning.
     */
    private fun continuationBlock(
            type: IrType, locationInfo: LocationInfo?, code: (ContinuationBlock) -> Unit = {}): ContinuationBlock {

        val entry = functionGenerationContext.basicBlock("continuation_block", locationInfo)

        functionGenerationContext.appendingTo(entry) {
            val valuePhi = if (type.isUnit()) {
                null
            } else {
                functionGenerationContext.phi(type.toLLVMType(llvm))
            }

            val result = ContinuationBlock(entry, valuePhi)
            code(result)
            return result
        }
    }

    //-------------------------------------------------------------------------//

    private fun evaluateVararg(value: IrVararg): LLVMValueRef {
        val elements = value.elements.map {
            if (it is IrExpression) {
                val mapped = evaluateExpression(it)
                if (mapped.isConst) {
                    return@map mapped
                }
            }

            throw IllegalStateException("IrVararg neither was lowered nor can be statically evaluated")
        }

        val arrayClass = value.type.getClass()!!

        // Note: even if all elements are const, they aren't guaranteed to be statically initialized.
        // E.g. an element may be a pointer to lazy-initialized object (aka singleton).
        // However it is guaranteed that all elements are already initialized at this point.
        return codegen.staticData.createConstKotlinArray(arrayClass, elements)
    }

    //-------------------------------------------------------------------------//

    private fun evaluateThrow(expression: IrThrow): LLVMValueRef {
        val exception = evaluateExpression(expression.value)
        currentCodeContext.exceptionHandler.genThrow(functionGenerationContext, exception)
        return codegen.kNothingFakeValue
    }

    //-------------------------------------------------------------------------//

    /**
     * The [CodeContext] that catches exceptions.
     */
    private inner abstract class CatchingScope : InnerScopeImpl() {

        /**
         * The LLVM `landingpad` such that if an invoked function throws an exception,
         * then this exception is passed to [handler].
         */
        private val landingpad: LLVMBasicBlockRef by lazy {
            using(outerContext) {
                functionGenerationContext.basicBlock("landingpad", endLocationInfoFromScope()) {
                    genLandingpad()
                }
            }
        }

        /**
         * The Kotlin exception handler, i.e. the [ContinuationBlock] which gets started
         * when the exception is caught, receiving this exception as its value.
         */
        private val handler by lazy {
            using(outerContext) {
                continuationBlock(context.ir.symbols.throwable.owner.defaultType, endLocationInfoFromScope()) {
                    genHandler(it.value)
                }
            }
        }

        private fun endLocationInfoFromScope(): LocationInfo? {
            val functionScope = currentCodeContext.functionScope()
            val irFunction = functionScope?.let {
                (functionScope as FunctionScope).declaration
            }
            return irFunction?.endLocation
        }

        private fun FunctionGenerationContext.jumpToHandler(exception: LLVMValueRef) {
            jump(handler, exception)
        }

        /**
         * Generates the LLVM `landingpad` that catches C++ exception with type `KotlinException`,
         * unwraps the Kotlin exception object and jumps to [handler].
         *
         * This method generates nearly the same code as `clang++` does for the following:
         * ```
         * catch (KotlinException& e) {
         *     KRef exception = e.exception_;
         *     return exception;
         * }
         * ```
         * except that our code doesn't check exception `typeid`.
         *
         * TODO: why does `clang++` check `typeid` even if there is only one catch clause?
         */
        private fun genLandingpad() {
            with(functionGenerationContext) {
                val exceptionPtr = catchKotlinException()
                jumpToHandler(exceptionPtr)
            }
        }

        override val exceptionHandler: ExceptionHandler
            get() = object : ExceptionHandler.Local() {
                override val unwind get() = landingpad

                override fun genThrow(functionGenerationContext: FunctionGenerationContext, kotlinException: LLVMValueRef) {
                    // Super class implementation would do too, so this is just an optimization:
                    // use local jump instead of wrapping to C++ exception, throwing, catching and unwrapping it:
                    functionGenerationContext.jumpToHandler(kotlinException)
                }
            }

        protected abstract fun genHandler(exception: LLVMValueRef)
    }

    /**
     * The [CatchingScope] that handles exceptions using Kotlin `catch` clauses.
     *
     * @param success the block to be used when the exception is successfully handled;
     * expects `catch` expression result as its value.
     */
    private inner class CatchScope(private val catches: List<IrCatch>,
                                   private val success: ContinuationBlock) : CatchingScope() {

        override fun genHandler(exception: LLVMValueRef) {

            for (catch in catches) {
                fun genCatchBlock() {
                    using(VariableScope()) {
                        currentCodeContext.genDeclareVariable(catch.catchParameter, exception)
                        functionGenerationContext.generateFrameCheck()
                        evaluateExpressionAndJump(catch.result, success)
                    }
                }

                if (catch.catchParameter.descriptor.type == context.builtIns.throwable.defaultType) {
                    genCatchBlock()
                    return      // Remaining catch clauses are unreachable.
                } else {
                    val isInstance = genInstanceOf(exception, catch.catchParameter.type.getClass()!!)
                    val body = functionGenerationContext.basicBlock("catch", catch.startLocation)
                    val nextCheck = functionGenerationContext.basicBlock("catchCheck", catch.endLocation)
                    functionGenerationContext.condBr(isInstance, body, nextCheck)

                    functionGenerationContext.appendingTo(body) {
                        genCatchBlock()
                    }

                    functionGenerationContext.positionAtEnd(nextCheck)
                }
            }
            // rethrow the exception if no clause can handle it.
            outerContext.exceptionHandler.genThrow(functionGenerationContext, exception)
        }
    }

    private fun evaluateTry(expression: IrTry): LLVMValueRef {
        // TODO: does basic block order influence machine code order?
        // If so, consider reordering blocks to reduce exception tables size.

        assert (expression.finallyExpression == null, { "All finally blocks should've been lowered" })

        val continuation = continuationBlock(expression.type, expression.endLocation)

        val catchScope = if (expression.catches.isEmpty())
                             null
                         else
                             CatchScope(expression.catches, continuation)
        using(catchScope) {
            evaluateExpressionAndJump(expression.tryResult, continuation)
        }
        functionGenerationContext.positionAtEnd(continuation.block)

        return continuation.value
    }

    //-------------------------------------------------------------------------//
    /* FIXME. Fix "when" type in frontend.
     * For the following code:
     *  fun foo(x: Int) {
     *      when (x) {
     *          0 -> 0
     *      }
     *  }
     *  we cannot determine if the result of when is assigned or not.
     */
    private inner class WhenEmittingContext(val expression: IrWhen, val lastBBOfWhenCases: LLVMBasicBlockRef) {
        val needsPhi = expression.branches.last().isUnconditional() && !expression.type.isUnit()
        val llvmType = expression.type.toLLVMType(llvm)
        val arcResultsAlreadyOwnedByResultSlot = mutableListOf<Boolean>()

        val bbExit = lazy {
            // bbExit must be positioned after all blocks of WHEN construct
            functionGenerationContext.appendingTo(lastBBOfWhenCases) {
                functionGenerationContext.basicBlock("when_exit", expression.endLocation)
            }
        }
        val resultPhi = lazy {
            functionGenerationContext.appendingTo(bbExit.value) {
                functionGenerationContext.phi(llvmType)
            }
        }
    }

    /** For WHEN { COND1 -> CASE1, COND2 -> CASE2, ELSE -> UNCONDITIONAL }
     * the following sequence of basic blocks is generated:
     * -- if COND1
     * -- CASE1
     * -- NEXT1(if COND2)
     * -- CASE2
     * -- NEXT2 (UNCONDITIONAL)
     * -- EXIT
     */
    private fun evaluateWhen(expression: IrWhen, resultSlot: LLVMValueRef?): LLVMValueRef {
        context.log{"evaluateWhen                   : ${ir2string(expression)}"}

        generateDebugTrambolineIf("when", expression)

        // First, generate all empty basic blocks for conditions and variants
        val bbOfFirstConditionCheck = functionGenerationContext.currentBlock
        val branchInfos: List<BranchCaseNextInfo> = expression.branches.map {
            // Carefully create empty basic blocks and position them one after another
            val bbCase = if (it.isUnconditional()) null else
                functionGenerationContext.basicBlock("when_case", it.startLocation, it.endLocation).apply { functionGenerationContext.positionAtEnd(this) }
            val bbNext = if (it.isUnconditional() || it == expression.branches.last()) null else
                functionGenerationContext.basicBlock("when_next", it.startLocation, it.endLocation).apply { functionGenerationContext.positionAtEnd(this) }
            BranchCaseNextInfo(it, bbCase, bbNext, resultSlot)
        }
        // Now, exit basic block can be positioned after all blocks of WHEN expression
        val whenEmittingContext = WhenEmittingContext(expression, lastBBOfWhenCases = functionGenerationContext.currentBlock)
        functionGenerationContext.positionAtEnd(bbOfFirstConditionCheck)

        branchInfos.forEach { generateWhenCase(whenEmittingContext, it) }

        if (whenEmittingContext.bbExit.isInitialized()) {
            functionGenerationContext.positionAtEnd(whenEmittingContext.bbExit.value)
            if (resultSlot != null && whenEmittingContext.resultPhi.isInitialized() &&
                    whenEmittingContext.arcResultsAlreadyOwnedByResultSlot.isNotEmpty() &&
                    whenEmittingContext.arcResultsAlreadyOwnedByResultSlot.all { it }) {
                functionGenerationContext.markArcResultOwnedBySlot(whenEmittingContext.resultPhi.value, resultSlot)
            }
        }

        val result = when {
            expression.type.isUnit() -> codegen.theUnitInstanceRef.llvm
            expression.type.isNothing() -> functionGenerationContext.kNothingFakeValue
            whenEmittingContext.resultPhi.isInitialized() -> whenEmittingContext.resultPhi.value
            else -> LLVMGetUndef(whenEmittingContext.llvmType)!!
        }
        currentBranchGuaranteedPhiEmission?.let { emission ->
            if (expression === emission.selection.conditional) emission.markConditional(expression)
        }
        return result
    }

    private fun generateDebugTrambolineIf(name: String, expression: IrExpression) {
        val generationContext = (currentCodeContext.functionScope() as? FunctionScope)?.functionGenerationContext
                .takeIf { context.config.generateDebugTrampoline }
        generationContext?.basicBlock(name, expression.startLocation)?.let {
            generationContext.br(it)
            generationContext.positionAtEnd(it)
        }
    }

    private data class BranchCaseNextInfo(val branch: IrBranch, val bbCase: LLVMBasicBlockRef?, val bbNext: LLVMBasicBlockRef?,
                                          val resultSlot: LLVMValueRef?)

    private fun generateWhenCase(whenEmittingContext: WhenEmittingContext, branchCaseNextInfo: BranchCaseNextInfo) {
        with(branchCaseNextInfo) {
            if (!branch.isUnconditional()) {
                val condition = evaluateExpression(branch.condition)
                functionGenerationContext.condBr(condition, bbCase, bbNext ?: whenEmittingContext.bbExit.value)
                functionGenerationContext.positionAtEnd(bbCase!!)
            }
            val brResult = evaluateExpression(branch.result, resultSlot)
            if (!functionGenerationContext.isAfterTerminator()) {
                if (whenEmittingContext.needsPhi) {
                    whenEmittingContext.arcResultsAlreadyOwnedByResultSlot += resultSlot != null &&
                            functionGenerationContext.arcResultIsAlreadyOwnedBySlot(brResult, resultSlot)
                }
                if (whenEmittingContext.needsPhi)
                    functionGenerationContext.assignPhis(whenEmittingContext.resultPhi.value to brResult)
                functionGenerationContext.br(whenEmittingContext.bbExit.value)
            }
            if (bbNext != null)
                functionGenerationContext.positionAtEnd(bbNext)
        }
    }
    //-------------------------------------------------------------------------//

    private fun evaluateWhileLoop(loop: IrWhileLoop): LLVMValueRef {
        val loopScope = LoopScope(loop)
        val phiEmission = currentCoroutineGuaranteedPhiEmission?.takeIf { it.selection.loop === loop }
        using(loopScope) {
            val loopBody = functionGenerationContext.basicBlock("while_loop", loop.startLocation)
            val entryPredecessor = functionGenerationContext.currentBlock
            val entryCurrent = phiEmission?.let {
                currentCodeContext.genGetValue(it.selection.receiverParameter, null)
            }
            val entryParameter = phiEmission?.let {
                currentCodeContext.genGetValue(it.selection.resultParameter, null)
            }
            functionGenerationContext.br(loopScope.loopCheck)

            functionGenerationContext.positionAtEnd(loopScope.loopCheck)
            phiEmission?.let {
                it.markLoop()
                it.currentPhi = functionGenerationContext.phi(llvm.kObjHeaderPtr, "arc.coroutine.current")
                it.parameterPhi = functionGenerationContext.phi(llvm.kObjHeaderPtr, "arc.coroutine.param")
                functionGenerationContext.addPhiIncoming(
                    it.currentPhi,
                    entryPredecessor to requireNotNull(entryCurrent),
                )
                functionGenerationContext.addPhiIncoming(
                    it.parameterPhi,
                    entryPredecessor to requireNotNull(entryParameter),
                )
                it.markEntryIncoming()
            }
            val condition = evaluateExpression(loop.condition)
            functionGenerationContext.condBr(condition, loopBody, loopScope.loopExit)

            functionGenerationContext.positionAtEnd(loopBody)
            if (context.memoryModel.usesTracingGC)
                call(llvm.Kotlin_mm_safePointWhileLoopBody, emptyList())
            loop.body?.generate()

            phiEmission?.let {
                check(!functionGenerationContext.isAfterTerminator()) {
                    "selected coroutine loop lost its sole natural backedge"
                }
                val backedge = functionGenerationContext.currentBlock
                val currentIndex = currentCodeContext.getDeclaredValue(it.selection.current)
                val parameterIndex = currentCodeContext.getDeclaredValue(it.selection.parameterState)
                check(currentIndex >= 0 && parameterIndex >= 0) {
                    "selected coroutine backedge owning slots are missing"
                }
                val currentBackedge = functionGenerationContext.vars.loadBorrowedMutableReference(currentIndex)
                val parameterBackedge = functionGenerationContext.vars.loadBorrowedMutableReference(parameterIndex)
                functionGenerationContext.addPhiIncoming(it.currentPhi, backedge to currentBackedge)
                functionGenerationContext.addPhiIncoming(it.parameterPhi, backedge to parameterBackedge)
                // Materialize the physical branch before proving predecessor and phi coverage.
                functionGenerationContext.br(loopScope.loopCheck)
                it.markBackedgeIncoming(backedge, loopScope.loopCheck, entryPredecessor)
            }
            if (phiEmission == null) functionGenerationContext.br(loopScope.loopCheck)
            functionGenerationContext.positionAtEnd(loopScope.loopExit)
        }

        assert(loop.type.isUnit())
        return codegen.theUnitInstanceRef.llvm
    }

    //-------------------------------------------------------------------------//

    private fun evaluateDoWhileLoop(loop: IrDoWhileLoop): LLVMValueRef {
        val loopScope = LoopScope(loop)
        using(loopScope) {
            val loopBody = functionGenerationContext.basicBlock("do_while_loop", loop.body?.startLocation ?: loop.startLocation)
            functionGenerationContext.br(loopBody)

            functionGenerationContext.positionAtEnd(loopBody)
            if (context.memoryModel.usesTracingGC)
                call(llvm.Kotlin_mm_safePointWhileLoopBody, emptyList())
            loop.body?.generate()
            functionGenerationContext.br(loopScope.loopCheck)

            functionGenerationContext.positionAtEnd(loopScope.loopCheck)
            val condition = evaluateExpression(loop.condition)
            functionGenerationContext.condBr(condition, loopBody, loopScope.loopExit)

            functionGenerationContext.positionAtEnd(loopScope.loopExit)
        }

        assert(loop.type.isUnit())
        return codegen.theUnitInstanceRef.llvm
    }

    //-------------------------------------------------------------------------//

    private fun evaluateGetValue(value: IrGetValue, resultSlot: LLVMValueRef?): LLVMValueRef {
        context.log{"evaluateGetValue               : ${ir2string(value)}"}
        currentBranchGuaranteedPhiEmission?.let { emission ->
            emission.loadJoinedValue(value, resultSlot)?.let { return it }
            emission.markOrdinaryRead(value)
        }
        arcOwnership.matchingSetLocalAliasReads[value]?.let { selection ->
            require(resultSlot == null && value.symbol.owner === selection.variable &&
                    context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                    !context.shouldContainDebugInfo() && !context.config.arcDiagnosticsEnabled) {
                "matching-set local read escaped its exact +0 call-argument shape: ${ir2string(value)}"
            }
            requireNotNull(currentMatchingSetLocalAliasEmission) {
                "matching-set local read was emitted outside its selected function"
            }.markRead(selection, value)
            // This authorization fixes the variable representation to a non-owning ValueRecord.
            // Do not let an older read optimization rediscover the same mutable source variable
            // as a frame slot after declaration emission has removed that slot.
            return currentCodeContext.genGetValue(value.symbol.owner, resultSlot)
        }
        currentCoroutineGuaranteedPhiEmission?.let { emission ->
            if (value === emission.selection.currentJoinedRead || value === emission.selection.parameterJoinedRead) {
                require(resultSlot == null && context.memoryModel == MemoryModel.ARC &&
                        context.config.optimizationsEnabled && !context.shouldContainDebugInfo() &&
                        !context.config.arcDiagnosticsEnabled) {
                    "selected coroutine guaranteed read escaped its exact +0 boundary: ${ir2string(value)}"
                }
                emission.markRead(value)
                return if (value === emission.selection.currentJoinedRead) emission.currentPhi else emission.parameterPhi
            }
        }
        if (value in arcOwnership.safeContinuationMovedResultReads) {
            val variable = value.symbol.owner as? IrVariable
            require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                    !context.shouldContainDebugInfo() && !context.config.arcDiagnosticsEnabled &&
                    resultSlot != null && resultSlot == functionGenerationContext.returnSlot &&
                    variable in arcOwnership.safeContinuationOwnedResultVariables) {
                "SafeContinuation.getOrThrow result move escaped its exact return boundary: ${ir2string(value)}"
            }
            val index = currentCodeContext.getDeclaredValue(variable!!)
            require(index >= 0) { "SafeContinuation.getOrThrow result has no owning local slot" }
            val owningSlot = functionGenerationContext.vars.addressOf(index)
            val result = functionGenerationContext.vars.loadBorrowedMutableReference(index)
            functionGenerationContext.moveArcOwnedReferenceIntoReturnSlot(
                    result, owningSlot, resultSlot, verifiedPhysicalSlotOwnership = true
            )
            return result
        }
        if (value in arcOwnership.rootedProjectionReads) {
            val variable = value.symbol.owner as? IrVariable
            require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                    !context.shouldContainDebugInfo() && resultSlot == null &&
                    variable in arcOwnership.rootedProjectionVariables) {
                "rooted projection read escaped its verified field-receiver boundary: ${ir2string(value)}"
            }
            val index = currentCodeContext.getDeclaredValue(variable!!)
            require(index >= 0) { "rooted projection cursor has no non-owning record: ${ir2string(value)}" }
            return functionGenerationContext.vars.loadRootedProjection(index)
        }
        if (resultSlot == null &&
            (value in arcOwnership.borrowedMutableReads || value in arcOwnership.borrowedFieldReceivers)
        ) {
            val variable = value.symbol.owner as IrVariable
            val index = currentCodeContext.getDeclaredValue(variable)
            require(index >= 0) { "ARC borrowed mutable/field receiver has no local slot: ${ir2string(value)}" }
            return functionGenerationContext.vars.loadBorrowedMutableReference(index)
        }
        return currentCodeContext.genGetValue(value.symbol.owner, resultSlot)
    }

    //-------------------------------------------------------------------------//

    private fun evaluateSetValue(value: IrSetValue): LLVMValueRef {
        context.log{"evaluateSetValue               : ${ir2string(value)}"}
        /*
         * Probably, here returnSlot optimization can be done, for not creating extra slot and reuse slot for a variable.
         * On the other side, eliminating extra slot is not so profitable, as eliminating all slots in a function,
         * while removing this slot is dangerous, as it needs to be accurate with setting variable inside expression.
         * So optimization was not implemented here for now.
         */
        val variable = currentCodeContext.getDeclaredValue(value.symbol.owner)
        val safeContinuationOwnedReload = value in arcOwnership.safeContinuationOwnedResultAssignments
        val result = evaluateExpression(
                value.value,
                if (safeContinuationOwnedReload) functionGenerationContext.vars.addressOf(variable) else null
        )
        val branchPhiEmission = currentBranchGuaranteedPhiEmission?.takeIf {
            value === it.selection.thenStore || value === it.selection.elseStore
        }
        if (branchPhiEmission != null) {
            require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                    !context.shouldContainDebugInfo() && !context.config.arcDiagnosticsEnabled &&
                    value.symbol.owner === branchPhiEmission.selection.selectedVariable &&
                    (value.value === branchPhiEmission.selection.thenSourceRead ||
                            value.value === branchPhiEmission.selection.elseSourceRead)) {
                "branch guaranteed-phi store escaped its exact +0 boundary: ${ir2string(value)}"
            }
            functionGenerationContext.vars.storeRootedProjection(result, variable)
            branchPhiEmission.markStore(value)
        } else if (value in arcOwnership.rootedProjectionStores) {
            val cursor = value.symbol.owner as? IrVariable
            val selectedProjection = value.value.selectedRootedProjectionField()
            require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                    !context.shouldContainDebugInfo() && cursor in arcOwnership.rootedProjectionVariables &&
                    selectedProjection != null &&
                    selectedProjection in arcOwnership.rootedProjectionFieldLoads) {
                "rooted projection advance escaped its verified loop boundary: ${ir2string(value)}"
            }
            functionGenerationContext.vars.storeRootedProjection(result, variable)
        } else if (value in arcOwnership.borrowedStrongProjectionStores) {
            val selectedProjection = value.value.selectedBorrowedStrongFieldProjection()
            require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                    !context.shouldContainDebugInfo() && selectedProjection != null) {
                "ARC borrowed strong replacement escaped its verified projection boundary: ${ir2string(value)}"
            }
            require(selectedProjection in arcOwnership.borrowedStrongFieldLoads) {
                "ARC borrowed strong replacement has no identity-selected field projection: ${ir2string(value)}"
            }
            functionGenerationContext.vars.storeBorrowedStrongProjection(result, variable)
        } else if (safeContinuationOwnedReload) {
            require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                    !context.shouldContainDebugInfo() && !context.config.arcDiagnosticsEnabled &&
                    value.symbol.owner in arcOwnership.safeContinuationOwnedResultVariables &&
                    functionGenerationContext.arcResultIsAlreadyOwnedBySlot(
                            result, functionGenerationContext.vars.addressOf(variable)
                    )) {
                "SafeContinuation.getOrThrow reload did not replace its exact owning result slot"
            }
        } else {
            functionGenerationContext.vars.store(result, variable)
        }
        currentCoroutineGuaranteedPhiEmission?.let { emission ->
            if (value === emission.selection.currentBackedgeStore ||
                value === emission.selection.parameterBackedgeStore
            ) emission.markStore(value)
        }
        assert(value.type.isUnit())
        return codegen.theUnitInstanceRef.llvm
    }

    private fun IrExpression.selectedBorrowedStrongFieldProjection(): IrGetField? {
        val selected = mutableListOf<IrGetField>()
        acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) = Unit

            override fun visitGetField(expression: IrGetField) {
                if (expression in arcOwnership.borrowedStrongFieldLoads) selected += expression
                expression.acceptChildrenVoid(this)
            }
        })
        return selected.singleOrNull()
    }

    private fun IrExpression.selectedRootedProjectionField(): IrGetField? {
        val selected = mutableListOf<IrGetField>()
        acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) = Unit

            override fun visitGetField(expression: IrGetField) {
                if (expression in arcOwnership.rootedProjectionFieldLoads) selected += expression
                expression.acceptChildrenVoid(this)
            }
        })
        return selected.singleOrNull()
    }

    //-------------------------------------------------------------------------//
    private fun debugInfoIfNeeded(function: IrFunction?, element: IrElement): VariableDebugLocation? {
        if (function == null || !element.needDebugInfo(context) || currentCodeContext.scope() == null) return null
        val locationInfo = element.startLocation ?: return null
        val location = codegen.generateLocationInfo(locationInfo)
        val file = (currentCodeContext.fileScope() as FileScope).file.file()
        return when (element) {
            is IrVariable -> if (shouldGenerateDebugInfo(element)) debugInfoLocalVariableLocation(
                    builder       = debugInfo.builder,
                    functionScope = locationInfo.scope,
                    diType        = with(debugInfo) { element.type.diType(codegen.llvmTargetData) },
                    name          = element.debugNameConversion(),
                    file          = file,
                    line          = locationInfo.line,
                    location      = location)
                    else null
            is IrValueParameter -> debugInfoParameterLocation(
                    builder       = debugInfo.builder,
                    functionScope = locationInfo.scope,
                    diType        = with(debugInfo) { element.type.diType(codegen.llvmTargetData) },
                    name          = element.debugNameConversion(),
                    argNo         = function.allParameters.indexOf(element) + 1,
                    file          = file,
                    line          = locationInfo.line,
                    location      = location)
            else -> throw Error("Unsupported element type: ${ir2string(element)}")
        }
    }

    private fun shouldGenerateDebugInfo(variable: IrVariable) = when(variable.origin) {
        IrDeclarationOrigin.FOR_LOOP_IMPLICIT_VARIABLE,
        IrDeclarationOrigin.FOR_LOOP_ITERATOR,
        IrDeclarationOrigin.IR_TEMPORARY_VARIABLE -> false
        else -> true
    }

    private fun generateVariable(variable: IrVariable) {
        context.log{"generateVariable               : ${ir2string(variable)}"}
        if (currentSafeContinuationSROAEmission?.emitAllocation(variable) == true) return
        currentCoroutineGuaranteedPhiEmission?.let { emission ->
            val selection = emission.selection
            if (variable === selection.current || variable === selection.parameterState) {
                require(variable.isVar && variable.type.binaryTypeIsReference() &&
                        context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                        !context.shouldContainDebugInfo() && !context.config.arcDiagnosticsEnabled) {
                    "selected coroutine owning slot escaped its verified declaration boundary: ${ir2string(variable)}"
                }
                // The ARC frame is zero initialized. Keep this nullable owning slot empty until the
                // first proven backedge handoff; the +0 ABI entry value is carried by the phi.
                currentCodeContext.genDeclareVariable(variable, null)
                emission.markDeclaration(variable)
                return
            }
            if (variable === selection.currentIterationBorrow) {
                require(!variable.isVar && variable.initializer != null &&
                        context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                        !context.shouldContainDebugInfo() && !context.config.arcDiagnosticsEnabled) {
                    "selected coroutine iteration borrow escaped its verified declaration boundary: ${ir2string(variable)}"
                }
                val value = evaluateExpression(variable.initializer!!, null)
                require(value == emission.currentPhi) {
                    "selected coroutine iteration borrow did not forward the exact current phi"
                }
                functionGenerationContext.vars.createImmutable(variable, value)
                emission.markDeclaration(variable)
                return
            }
        }
        val joinedReferencePlan = arcOwnership.joinedReferenceSlots[variable]
        val coroutineSpillMove = arcOwnership.coroutineSpillMovesByVariable[variable]
        val safeContinuationOwnedResult = variable in arcOwnership.safeContinuationOwnedResultVariables
        require((joinedReferencePlan == null && coroutineSpillMove == null && !safeContinuationOwnedResult) ||
                variable !in arcOwnership.mutableConstructorInitializers) {
            "ARC joined reference slot overlaps mutable constructor forwarding: ${ir2string(variable)}"
        }
        val preallocatedOwningSlot = if (variable in arcOwnership.mutableConstructorInitializers ||
                joinedReferencePlan != null || coroutineSpillMove != null || safeContinuationOwnedResult) {
            val index = this.currentCodeContext.genDeclareVariable(variable, null)
            functionGenerationContext.vars.addressOf(index)
        } else {
            null
        }
        val value = variable.initializer?.let {
            val resultSlot = when {
                preallocatedOwningSlot != null -> preallocatedOwningSlot
                currentArcOwnedResultForwarding?.producer === variable -> functionGenerationContext.returnSlot
                else -> null
            }
            val callSiteOrigin = (it as? IrBlock)?.origin as? InlinerExpressionLocationHint
            val inlineAtFunctionSymbol = callSiteOrigin?.inlineAtSymbol as? IrFunctionSymbol
            inlineAtFunctionSymbol?.run {
                switchSymbolizationContextTo(inlineAtFunctionSymbol) {
                    evaluateExpression(it, resultSlot)
                }
            } ?: evaluateExpression(it, resultSlot)
        }
        if (joinedReferencePlan != null) {
            require(variable.initializer === joinedReferencePlan.initializer &&
                    preallocatedOwningSlot != null && value != null &&
                    functionGenerationContext.arcResultIsAlreadyOwnedBySlot(value, preallocatedOwningSlot)) {
                "ARC joined reference initializer did not own its verified result slot: ${ir2string(variable)}"
            }
        }
        if (coroutineSpillMove != null) {
            require(variable.initializer === coroutineSpillMove.producer &&
                    preallocatedOwningSlot != null && value != null &&
                    functionGenerationContext.arcResultIsAlreadyOwnedBySlot(value, preallocatedOwningSlot)) {
                "ARC coroutine producer did not initialize its verified spill slot: ${ir2string(variable)}"
            }
        }
        if (safeContinuationOwnedResult) {
            require(preallocatedOwningSlot != null && value != null &&
                    functionGenerationContext.arcResultIsAlreadyOwnedBySlot(value, preallocatedOwningSlot)) {
                "SafeContinuation.getOrThrow initial atomic read did not initialize its owning result slot"
            }
        }
        if (preallocatedOwningSlot == null) {
            this.currentCodeContext.genDeclareVariable(variable, value)
        }
    }

    private fun CodeContext.genDeclareVariable(
            variable: IrVariable,
            value: LLVMValueRef?
    ) = genDeclareVariable(
            variable, value, debugInfoIfNeeded(
            (functionScope() as FunctionScope).declaration, variable))

    //-------------------------------------------------------------------------//

    private fun evaluateTypeOperator(value: IrTypeOperatorCall, resultSlot: LLVMValueRef?): LLVMValueRef {
        return when (value.operator) {
            IrTypeOperator.CAST                      -> evaluateCast(value, resultSlot)
            IrTypeOperator.IMPLICIT_INTEGER_COERCION -> evaluateIntegerCoercion(value)
            IrTypeOperator.IMPLICIT_CAST             -> evaluateExpression(value.argument, resultSlot)
            IrTypeOperator.IMPLICIT_NOTNULL          -> TODO(ir2string(value))
            IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> {
                evaluateExpression(value.argument)
                codegen.theUnitInstanceRef.llvm
            }
            IrTypeOperator.SAFE_CAST                 -> throw IllegalStateException("safe cast wasn't lowered")
            IrTypeOperator.INSTANCEOF                -> evaluateInstanceOf(value)
            IrTypeOperator.NOT_INSTANCEOF            -> evaluateNotInstanceOf(value)
            IrTypeOperator.SAM_CONVERSION            -> TODO(ir2string(value))
            IrTypeOperator.IMPLICIT_DYNAMIC_CAST     -> TODO(ir2string(value))
            IrTypeOperator.REINTERPRET_CAST          -> TODO(ir2string(value))
        }
    }

    //-------------------------------------------------------------------------//

    private fun IrType.isPrimitiveInteger(): Boolean {
        return this.isPrimitiveType() &&
               !this.isBoolean() &&
               !this.isFloat() &&
               !this.isDouble() &&
               !this.isChar()
    }

    private fun IrType.isUnsignedInteger(): Boolean = !isNullable() &&
                    UnsignedType.values().any { it.classId == this.getClass()?.descriptor?.classId }

    private fun evaluateIntegerCoercion(value: IrTypeOperatorCall): LLVMValueRef {
        context.log{"evaluateIntegerCoercion        : ${ir2string(value)}"}
        val type = value.typeOperand
        assert(type.isPrimitiveInteger() || type.isUnsignedInteger())
        val result = evaluateExpression(value.argument)
        assert(value.argument.type.isInt())
        val llvmSrcType = value.argument.type.toLLVMType(llvm)
        val llvmDstType = type.toLLVMType(llvm)
        val srcWidth    = LLVMGetIntTypeWidth(llvmSrcType)
        val dstWidth    = LLVMGetIntTypeWidth(llvmDstType)
        return when {
            srcWidth == dstWidth           -> result
            srcWidth > dstWidth            -> LLVMBuildTrunc(functionGenerationContext.builder, result, llvmDstType, "")!!
            else /* srcWidth < dstWidth */ -> LLVMBuildSExt(functionGenerationContext.builder, result, llvmDstType, "")!!
        }
    }

    //-------------------------------------------------------------------------//
    //   table of conversion with llvm for primitive types
    //   to be used in replacement fo primitive.toX() calls with
    //   translator intrinsics.
    //            | byte     short   int     long     float     double
    //------------|----------------------------------------------------
    //    byte    |   x       sext   sext    sext     sitofp    sitofp
    //    short   | trunc      x     sext    sext     sitofp    sitofp
    //    int     | trunc    trunc    x      sext     sitofp    sitofp
    //    long    | trunc    trunc   trunc     x      sitofp    sitofp
    //    float   | fptosi   fptosi  fptosi  fptosi      x      fpext
    //    double  | fptosi   fptosi  fptosi  fptosi   fptrunc      x

    private fun evaluateCast(value: IrTypeOperatorCall, resultSlot: LLVMValueRef?): LLVMValueRef {
        context.log{"evaluateCast                   : ${ir2string(value)}"}
        val dstClass = value.typeOperand.getClass()
                ?: error("No class for ${value.typeOperand.render()} from \n${functionGenerationContext.irFunction?.render()}")

        val srcArg = evaluateExpression(value.argument, resultSlot)
        assert(srcArg.type == codegen.kObjHeaderPtr)

        with(functionGenerationContext) {
            ifThen(not(genInstanceOf(srcArg, dstClass))) {
                if (dstClass.defaultType.isObjCObjectType()) {
                    val dstFullClassName = dstClass.fqNameWhenAvailable?.toString() ?: dstClass.name.toString()
                    callDirect(
                            context.ir.symbols.throwTypeCastException.owner,
                            listOf(srcArg, codegen.staticData.kotlinStringLiteral(dstFullClassName).llvm),
                            Lifetime.GLOBAL,
                            null
                    )
                } else {
                    val dstTypeInfo = functionGenerationContext.bitcast(llvm.int8PtrType, codegen.typeInfoValue(dstClass))
                    callDirect(
                            context.ir.symbols.throwClassCastException.owner,
                            listOf(srcArg, dstTypeInfo),
                            Lifetime.GLOBAL,
                            null
                    )
                }
            }
        }
        return srcArg
    }

    //-------------------------------------------------------------------------//

    private fun evaluateInstanceOf(value: IrTypeOperatorCall): LLVMValueRef {
        context.log{"evaluateInstanceOf             : ${ir2string(value)}"}

        val type     = value.typeOperand
        val srcArg   = evaluateExpression(value.argument)     // Evaluate src expression.

        val bbExit       = functionGenerationContext.basicBlock("instance_of_exit", value.startLocation)
        val bbInstanceOf = functionGenerationContext.basicBlock("instance_of_notnull", value.startLocation)
        val bbNull       = functionGenerationContext.basicBlock("instance_of_null", value.startLocation)

        val condition = functionGenerationContext.icmpEq(srcArg, codegen.kNullObjHeaderPtr)
        functionGenerationContext.condBr(condition, bbNull, bbInstanceOf)

        functionGenerationContext.positionAtEnd(bbNull)
        val resultNull = if (type.isNullable()) kTrue else kFalse
        functionGenerationContext.br(bbExit)

        functionGenerationContext.positionAtEnd(bbInstanceOf)
        val typeOperandClass = value.typeOperand.getClass()
        val resultInstanceOf = if (typeOperandClass != null) {
            genInstanceOf(srcArg, typeOperandClass)
        } else {
            // E.g. when generating type operation with reified type parameter in the original body of inline function.
            kTrue
            // TODO: this code should be unreachable, recheck.
        }
        functionGenerationContext.br(bbExit)
        val bbInstanceOfResult = functionGenerationContext.currentBlock

        functionGenerationContext.positionAtEnd(bbExit)
        val result = functionGenerationContext.phi(llvm.int1Type)
        functionGenerationContext.addPhiIncoming(result, bbNull to resultNull, bbInstanceOfResult to resultInstanceOf)
        return result
    }

    //-------------------------------------------------------------------------//

    private fun genInstanceOf(obj: LLVMValueRef, dstClass: IrClass): LLVMValueRef {
        if (dstClass.defaultType.isObjCObjectType()) {
            return genInstanceOfObjC(obj, dstClass)
        }

        val srcObjInfoPtr = functionGenerationContext.bitcast(codegen.kObjHeaderPtr, obj)

        return if (!context.ghaEnabled()) {
            call(llvm.isInstanceFunction, listOf(srcObjInfoPtr, codegen.typeInfoValue(dstClass)))
        } else {
            val dstHierarchyInfo = context.getLayoutBuilder(dstClass).hierarchyInfo
            if (!dstClass.isInterface) {
                call(llvm.isInstanceOfClassFastFunction,
                        listOf(srcObjInfoPtr, llvm.int32(dstHierarchyInfo.classIdLo), llvm.int32(dstHierarchyInfo.classIdHi)))
            } else {
                // Essentially: typeInfo.itable[place(interfaceId)].id == interfaceId
                val interfaceId = dstHierarchyInfo.interfaceId
                val typeInfo = functionGenerationContext.loadTypeInfo(srcObjInfoPtr)
                with(functionGenerationContext) {
                    val interfaceTableRecord = lookupInterfaceTableRecord(typeInfo, interfaceId)
                    icmpEq(load(structGep(interfaceTableRecord, 0 /* id */)), llvm.int32(interfaceId))
                }
            }
        }
    }

    private fun genInstanceOfObjC(obj: LLVMValueRef, dstClass: IrClass): LLVMValueRef {
        val objCObject = callDirect(
                context.ir.symbols.interopObjCObjectRawValueGetter.owner,
                listOf(obj),
                Lifetime.IRRELEVANT,
                null
        )

        return if (dstClass.isObjCClass()) {
            if (dstClass.isInterface) {
                val isMeta = if (dstClass.isObjCMetaClass()) kTrue else kFalse
                call(
                        llvm.Kotlin_Interop_DoesObjectConformToProtocol,
                        listOf(
                                objCObject,
                                genGetObjCProtocol(dstClass),
                                isMeta
                        )
                )
            } else {
                call(
                        llvm.Kotlin_Interop_IsObjectKindOfClass,
                        listOf(objCObject, genGetObjCClass(dstClass))
                )
            }.let {
                functionGenerationContext.icmpNe(it, kFalse)
            }


        } else {
            // e.g. ObjCObject, ObjCObjectBase etc.
            if (dstClass.isObjCMetaClass()) {
                val isClass = llvm.externalNativeRuntimeFunction(
                        "object_isClass",
                        LlvmRetType(llvm.int8Type),
                        listOf(LlvmParamType(llvm.int8PtrType))
                )
                call(isClass, listOf(objCObject)).let {
                    functionGenerationContext.icmpNe(it, llvm.int8(0))
                }
            } else if (dstClass.isObjCProtocolClass()) {
                // Note: it is not clear whether this class should be looked up this way.
                // clang does the same, however swiftc uses dynamic lookup.
                val protocolClass = functionGenerationContext.getObjCClassFromNativeRuntime("Protocol")
                call(
                        llvm.Kotlin_Interop_IsObjectKindOfClass,
                        listOf(objCObject, protocolClass)
                )
            } else {
                kTrue
            }
        }
    }

    //-------------------------------------------------------------------------//

    private fun evaluateNotInstanceOf(value: IrTypeOperatorCall): LLVMValueRef {
        val instanceOfResult = evaluateInstanceOf(value)
        return functionGenerationContext.not(instanceOfResult)
    }

    //-------------------------------------------------------------------------//

    private fun evaluateGetField(value: IrGetField, resultSlot: LLVMValueRef?): LLVMValueRef {
        context.log { "evaluateGetField               : ${ir2string(value)}" }
        val alignment : Int
        val order = when {
            value.symbol.owner.hasAnnotation(KonanFqNames.volatile) ->
                LLVMAtomicOrdering.LLVMAtomicOrderingSequentiallyConsistent
            else -> null
        }
        val fieldAddress: LLVMValueRef

        when {
            !value.symbol.owner.isStatic -> {
                fieldAddress = fieldPtrOfClass(evaluateExpression(value.receiver!!), value.symbol.owner)
                alignment = generationState.llvmDeclarations.forField(value.symbol.owner).alignment
            }
            value.symbol.owner.correspondingPropertySymbol?.owner?.isConst == true -> {
                // TODO: probably can be removed, as they are inlined.
                return evaluateConst(value.symbol.owner.initializer?.expression as IrConst<*>).llvm
            }
            else -> {
                if (context.config.threadsAreAllowed && value.symbol.owner.isGlobalNonPrimitive(context)) {
                    functionGenerationContext.checkGlobalsAccessible(currentCodeContext.exceptionHandler)
                }
                fieldAddress = staticFieldPtr(value.symbol.owner, functionGenerationContext)
                alignment = generationState.llvmDeclarations.forStaticField(value.symbol.owner).alignment
            }
        }
        if (value in arcOwnership.borrowedStrongFieldLoads ||
            value in arcOwnership.borrowedStrongCallFieldLoads ||
            value in arcOwnership.rootedProjectionFieldLoads
        ) {
            currentStringBuilderBackingArrayProjectionEmission?.let { emission ->
                if (value === emission.plan.backingFieldLoad) {
                    require(context.memoryModel == MemoryModel.ARC &&
                            context.config.target == KonanTarget.LINUX_X64 &&
                            context.config.optimizationsEnabled && !context.shouldContainAnyDebugInfo() &&
                            !context.config.arcDiagnosticsEnabled && context.config.sanitizer == null &&
                            !context.config.undefinedBehaviorSanitizer && !generationState.coverage.enabled) {
                        "StringBuilder backing-array projection escaped its exact compilation mode"
                    }
                    emission.markLoad(value)
                }
            }
            currentSafeContinuationResumeBorrowEmission?.let { emission ->
                if (value in emission.plan.borrowedResultRefLoads) emission.markLoad(value)
            }
            require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                    !context.shouldContainDebugInfo() &&
                    (value !in (currentSafeContinuationResumeBorrowEmission?.plan?.borrowedResultRefLoads.orEmpty()) ||
                            !context.config.arcDiagnosticsEnabled)) {
                "ARC borrowed/rooted strong field load escaped its optimization boundary: ${ir2string(value)}"
            }
            require(!value.symbol.owner.isStatic && order == null &&
                    value.type.binaryTypeIsReference() && resultSlot == null &&
                    !value.symbol.owner.hasAnnotation(KonanFqNames.arcWeak) &&
                    !value.symbol.owner.hasAnnotation(KonanFqNames.arcUnowned)) {
                "ARC borrowed strong field load requires a direct nonvolatile reference projection: ${ir2string(value)}"
            }
            // Either the selected receiver's mutable stack slot owns the target until the final
            // replacement, or the current dispatch receiver owns an exact CharArray field through
            // one allowlisted call. Both verified boundaries make an intermediate owning result
            // slot redundant.
            return functionGenerationContext.loadSlot(
                    fieldAddress, false, null, alignment = alignment
            )
        }
        if (context.memoryModel == MemoryModel.ARC &&
                order != null && value.type.binaryTypeIsReference()) {
            return functionGenerationContext.call(
                    llvm.ReadVolatileHeapRef,
                    listOf(fieldAddress),
                    resultLifetime(value),
                    resultSlot = resultSlot,
            )
        }
        return functionGenerationContext.loadSlot(
                fieldAddress, !value.symbol.owner.isFinal, resultSlot,
                memoryOrder = order,
                alignment = alignment
        )
    }

    //-------------------------------------------------------------------------//
    private fun needMutationCheck(irField: IrField): Boolean {
        // For now we omit mutation checks on immutable types, as this allows initialization in constructor
        // and it is assumed that API doesn't allow to change them.
        return context.config.freezing.enableFreezeChecks && !irField.parentAsClass.isFrozen(context) && !irField.hasAnnotation(KonanFqNames.volatile)
    }

    private fun needLifetimeConstraintsCheck(valueToAssign: LLVMValueRef, irClass: IrClass): Boolean {
        // TODO: Likely, we don't need isFrozen check here at all.
        return functionGenerationContext.isObjectType(valueToAssign.type) && !irClass.isFrozen(context)
    }

    private fun canElideLifetimeConstraintsCheckForExactStackLocal(receiver: LLVMValueRef): Boolean =
            context.memoryModel == MemoryModel.ARC &&
                    context.config.optimizationsEnabled &&
                    !context.shouldContainDebugInfo() &&
                    !context.config.arcDiagnosticsEnabled &&
                    functionGenerationContext.stackLocalsManager.isExactStackLocalObjectPointer(receiver)

    private fun isZeroConstValue(value: IrExpression): Boolean {
        if (value !is IrConst<*>) return false
        return when (value.kind) {
            IrConstKind.Null -> true
            IrConstKind.Boolean -> (value.value as Boolean) == false
            IrConstKind.Byte -> (value.value as Byte) == 0.toByte()
            IrConstKind.Char -> (value.value as Char) == 0.toChar()
            IrConstKind.Short -> (value.value as Short) == 0.toShort()
            IrConstKind.Int -> (value.value as Int) == 0
            IrConstKind.Long -> (value.value as Long) == 0L
            IrConstKind.Float -> (value.value as Float).toRawBits() == 0
            IrConstKind.Double -> (value.value as Double).toRawBits() == 0L
            IrConstKind.String -> false
        }
    }

    private fun evaluateSetField(value: IrSetField): LLVMValueRef {
        context.log{"evaluateSetField               : ${ir2string(value)}"}
        if (value.origin == IrStatementOrigin.INITIALIZE_FIELD
                && isZeroConstValue(value.value)) {
            check(value.receiver is IrGetValue) { "Only IrGetValue expected for receiver of a field initializer" }
            // All newly allocated objects are zeroed out, so it is redundant to initialize their
            // fields with the default values. This is also aligned with the Kotlin/JVM behavior.
            // See https://youtrack.jetbrains.com/issue/KT-39100 for details.
            return codegen.theUnitInstanceRef.llvm
        }

        val immortalCompletionInitializer = currentImmortalCompletionContextEmission?.takeIf {
            value === it.selection.initializerStore
        }
        val thisPtr = value.receiver?.let { evaluateExpression(it) }
        val valueToAssign = evaluateExpression(value.value)
        val address: LLVMValueRef
        val alignment: Int
        if (thisPtr != null) {
            require(!value.symbol.owner.isStatic) { "Unexpected receiver for a static field: ${value.render()}" }
            require(thisPtr.type == codegen.kObjHeaderPtr) {
                LLVMPrintTypeToString(thisPtr.type)?.toKString().toString()
            }
            val parentAsClass = value.symbol.owner.parentAsClass
            if (immortalCompletionInitializer == null && needMutationCheck(value.symbol.owner)) {
                functionGenerationContext.call(llvm.mutationCheck,
                        listOf(functionGenerationContext.bitcast(codegen.kObjHeaderPtr, thisPtr)),
                        Lifetime.IRRELEVANT, currentCodeContext.exceptionHandler)
            }
            if (immortalCompletionInitializer == null && needLifetimeConstraintsCheck(valueToAssign, parentAsClass) &&
                    !canElideLifetimeConstraintsCheckForExactStackLocal(thisPtr)) {
                functionGenerationContext.call(llvm.checkLifetimesConstraint, listOf(thisPtr, valueToAssign))
            }
            address = fieldPtrOfClass(thisPtr, value.symbol.owner)
            alignment = generationState.llvmDeclarations.forField(value.symbol.owner).alignment
        } else {
            require(value.symbol.owner.isStatic) { "A receiver expected for a non-static field: ${value.render()}" }
            if (context.config.threadsAreAllowed && value.symbol.owner.storageKind(context) == FieldStorageKind.GLOBAL)
                functionGenerationContext.checkGlobalsAccessible(currentCodeContext.exceptionHandler)
            if (value.symbol.owner.shouldBeFrozen(context) && value.origin != ObjectClassLowering.IrStatementOriginFieldPreInit)
                functionGenerationContext.freeze(valueToAssign, currentCodeContext.exceptionHandler)
            address = staticFieldPtr(value.symbol.owner, functionGenerationContext)
            alignment = generationState.llvmDeclarations.forStaticField(value.symbol.owner).alignment
        }
        val ownedResultHeapStore = currentOwnedResultHeapStoreEmission?.takeIf {
            value === it.selection.store
        }
        val freshOwnedFieldStore = currentFreshOwnedFieldStoreEmission?.planForStore(value)
        if (immortalCompletionInitializer != null) {
            require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                    !context.shouldContainDebugInfo() && !context.config.arcDiagnosticsEnabled &&
                    thisPtr != null && value.symbol.owner === immortalCompletionInitializer.selection.contextField &&
                    !value.symbol.owner.hasAnnotation(KonanFqNames.volatile)) {
                "immortal completion-context initializer escaped its production boundary"
            }
            immortalCompletionInitializer.emitInitializer(value, valueToAssign, address)
        } else if (ownedResultHeapStore != null) {
            require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                    !context.shouldContainDebugInfo() && !context.config.arcDiagnosticsEnabled &&
                    thisPtr != null && value.symbol.owner === ownedResultHeapStore.selection.refElementField &&
                    !value.symbol.owner.hasAnnotation(KonanFqNames.volatile)) {
                "owned Result-box move escaped its exact Ref.element production boundary"
            }
            ownedResultHeapStore.moveIntoStore(value, valueToAssign, address)
        } else if (freshOwnedFieldStore != null) {
            require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                    context.config.target == KonanTarget.LINUX_X64 && context.config.isFinalBinary &&
                    !context.shouldContainAnyDebugInfo() && !context.config.arcDiagnosticsEnabled &&
                    context.config.sanitizer == null && !context.config.undefinedBehaviorSanitizer &&
                    !generationState.coverage.enabled && thisPtr != null &&
                    value.receiver === freshOwnedFieldStore.receiverRead &&
                    value.value === freshOwnedFieldStore.producer &&
                    value.symbol.owner === freshOwnedFieldStore.field &&
                    !freshOwnedFieldStore.function.isArcSuspendLike() &&
                    !freshOwnedFieldStore.function.isExternal &&
                    !value.symbol.owner.hasAnnotation(KonanFqNames.volatile) &&
                    !value.symbol.owner.hasAnnotation(KonanFqNames.arcWeak) &&
                    !value.symbol.owner.hasAnnotation(KonanFqNames.arcUnowned)) {
                "fresh owned field move escaped its exact production boundary"
            }
            currentFreshOwnedFieldStoreEmission!!.moveIntoStore(value, valueToAssign, address)
        } else {
            functionGenerationContext.storeAny(
                    valueToAssign, address, false,
                    isVolatile = value.symbol.owner.hasAnnotation(KonanFqNames.volatile),
                    alignment = alignment,
            )
        }

        assert (value.type.isUnit())
        return codegen.theUnitInstanceRef.llvm
    }

    //-------------------------------------------------------------------------//
    private fun fieldPtrOfClass(thisPtr: LLVMValueRef, value: IrField): LLVMValueRef {
        val fieldInfo = generationState.llvmDeclarations.forField(value)

        val typePtr = pointerType(fieldInfo.classBodyType)

        val typedBodyPtr = functionGenerationContext.bitcast(typePtr, thisPtr)
        val fieldPtr = LLVMBuildStructGEP(functionGenerationContext.builder, typedBodyPtr, fieldInfo.index, "")
        return fieldPtr!!
    }

    private fun staticFieldPtr(value: IrField, context: FunctionGenerationContext) =
            generationState.llvmDeclarations
                    .forStaticField(value.symbol.owner)
                    .storageAddressAccess
                    .getAddress(context)

    //-------------------------------------------------------------------------//
    private fun evaluateStringConst(value: IrConst<String>) =
            codegen.staticData.kotlinStringLiteral(value.value)

    private fun evaluateConst(value: IrConst<*>): ConstValue {
        context.log{"evaluateConst                  : ${ir2string(value)}"}
        /* This suppression against IrConst<String> */
        @Suppress("UNCHECKED_CAST")
        return when (value.kind) {
            IrConstKind.Null -> constPointer(codegen.kNullObjHeaderPtr)
            IrConstKind.Boolean -> llvm.constInt1(value.value as Boolean)
            IrConstKind.Char -> llvm.constChar16(value.value as Char)
            IrConstKind.Byte -> llvm.constInt8(value.value as Byte)
            IrConstKind.Short -> llvm.constInt16(value.value as Short)
            IrConstKind.Int -> llvm.constInt32(value.value as Int)
            IrConstKind.Long -> llvm.constInt64(value.value as Long)
            IrConstKind.String -> evaluateStringConst(value as IrConst<String>)
            IrConstKind.Float -> llvm.constFloat32(value.value as Float)
            IrConstKind.Double -> llvm.constFloat64(value.value as Double)
        }
    }

    //-------------------------------------------------------------------------//

    private class IrConstValueCacheKey(val value: IrConstantValue) {
        override fun equals(other: Any?): Boolean {
            if (other !is IrConstValueCacheKey) return false
            return value.contentEquals(other.value)
        }

        override fun hashCode(): Int {
            return value.contentHashCode()
        }
    }

    private val constantValuesCache = mutableMapOf<IrConstValueCacheKey, ConstValue>()

    private fun evaluateConstantValue(value: IrConstantValue): ConstValue =
            constantValuesCache.getOrPut(IrConstValueCacheKey(value)) {
                evaluateConstantValueImpl(value)
            }

    private fun evaluateConstantValueImpl(value: IrConstantValue): ConstValue {
        val symbols = context.ir.symbols
        return when (value) {
            is IrConstantPrimitive -> {
                val constructedType = value.value.type
                if (context.getTypeConversion(constructedType, value.type) != null) {
                    if (value.value.kind == IrConstKind.Null) {
                        Zero(value.type.toLLVMType(llvm))
                    } else {
                        require(value.type.toLLVMType(llvm) == codegen.kObjHeaderPtr) {
                            "Can't wrap ${value.value.kind.asString} constant to type ${value.type.render()}"
                        }
                        value.toBoxCacheValue(generationState) ?: codegen.staticData.createConstKotlinObject(
                                constructedType.getClass()!!,
                                evaluateConst(value.value)
                        )
                    }
                } else {
                    evaluateConst(value.value)
                }
            }
            is IrConstantArray -> {
                val clazz = value.type.getClass()!!
                require(clazz.symbol == symbols.array || clazz.symbol in symbols.primitiveTypesToPrimitiveArrays.values) {
                    "Statically initialized array should have array type"
                }
                codegen.staticData.createConstKotlinArray(
                        value.type.getClass()!!,
                        value.elements.map { evaluateConstantValue(it) }
                )
            }
            is IrConstantObject -> {
                val constructedType = value.constructor.owner.constructedClassType
                val constructedClass = constructedType.getClass()!!
                val needUnBoxing = constructedType.getInlinedClassNative() != null &&
                        context.getTypeConversion(constructedType, value.type) == null
                if (needUnBoxing) {
                    val unboxed = value.valueArguments.singleOrNull()
                            ?: error("Inlined class should have exactly one constructor argument")
                    return evaluateConstantValue(unboxed)
                }
                val fields = if (value.constructor.owner.isConstantConstructorIntrinsic) {
                    intrinsicGenerator.evaluateConstantConstructorFields(value, value.valueArguments.map { evaluateConstantValue(it) })
                } else {
                    val fields = context.getLayoutBuilder(constructedClass).getFields(llvm)
                    val constructor = value.constructor.owner
                    val valueParameters = constructor.valueParameters.associateBy { it.name.toString() }
                    // support of initilaization of object in following case:
                    // open class Base(val field: ...)
                    // Child(val otherField: ...) : Base(constantValue)
                    //
                    //  Child(constantValue) could be initialized constantly. This is required for function references.
                    val delegatedCallConstants = constructor.body?.statements
                            ?.filterIsInstance<IrDelegatingConstructorCall>()
                            ?.singleOrNull()
                            ?.getArgumentsWithIr()
                            ?.filter { it.second is IrConstantValue }
                            ?.associate { it.first.name.toString() to it.second }
                            .orEmpty()
                    fields.map { field ->
                        val init = if (field.isConst) {
                            field.irField!!.initializer?.expression.also {
                                require(field.name !in valueParameters) {
                                    "Constant field ${field.name} of class ${constructedClass.name} shouldn't be a constructor parameter"
                                }
                            }
                        } else {
                            val index = valueParameters[field.name]?.index
                            if (index != null)
                                value.valueArguments[index]
                            else
                                delegatedCallConstants[field.name]
                        }
                        when (init) {
                            is IrConst<*> -> evaluateConst(init)
                            is IrConstantValue -> evaluateConstantValue(init)
                            null -> error("Bad statically initialized object: field ${field.name} value not set in ${constructedClass.name}")
                            else -> error("Unexpected constant initializer type: ${init::class}")
                        }
                    }.also {
                        require(it.size == value.valueArguments.size + fields.count { it.isConst } + delegatedCallConstants.size) {
                            "Bad statically initialized object of class ${constructedClass.name}: not all arguments are used"
                        }
                    }
                }

                require(value.type.toLLVMType(llvm) == codegen.kObjHeaderPtr) { "Constant object is not an object, but ${value.type.render()}" }
                codegen.staticData.createConstKotlinObject(
                        constructedClass,
                        *fields.toTypedArray()
                )
            }
            else -> TODO("Unimplemented IrConstantValue subclass ${value::class.qualifiedName}")
        }
    }

    //-------------------------------------------------------------------------//

    private fun evaluateReturn(expression: IrReturn): LLVMValueRef {
        context.log{"evaluateReturn                 : ${ir2string(expression)}"}
        val value = expression.value
        val target = expression.returnTargetSymbol.owner
        val containsSelectedCoroutineTailCall = value.containsSelectedCoroutineTailCall()

        val targetReturnSlot = currentCodeContext.getReturnSlot(target)
        currentImmortalCompletionContextEmission?.takeIf {
            expression === it.selection.getterReturn
        }?.let { emission ->
            require(target === emission.selection.contextGetter && targetReturnSlot != null) {
                "immortal completion-context getter escaped its exact owned-result ABI"
            }
            val permanent = emission.emitGetter(expression, targetReturnSlot)
            currentCodeContext.genReturn(target, permanent)
            return codegen.kNothingFakeValue
        }
        currentCoroutineEmptyContextImmortalReturnEmission?.takeIf {
            expression === it.selection.returned
        }?.let { emission ->
            require(target === emission.selection.function && targetReturnSlot != null) {
                "EmptyCoroutineContext immortal return escaped its exact function result ABI"
            }
            val permanent = emission.emit(expression, targetReturnSlot)
            currentCodeContext.genReturn(target, permanent)
            return codegen.kNothingFakeValue
        }
        val coroutineSpillMove = arcOwnership.coroutineSpillMovesByReturn[expression]
        val evaluated = evaluateExpression(value, if (coroutineSpillMove == null) targetReturnSlot else null)
        if (coroutineSpillMove != null) {
            require(target === coroutineSpillMove.function && targetReturnSlot != null &&
                    coroutineSpillMove.returnExpression === expression &&
                    (value.unwrapExactArcCoroutineSpillRead()?.symbol?.owner === coroutineSpillMove.spillVariable)) {
                "ARC coroutine spill move escaped its verified return identity"
            }
            val spillIndex = currentCodeContext.getDeclaredValue(coroutineSpillMove.spillVariable)
            require(spillIndex >= 0) { "ARC coroutine spill variable has no physical owning slot" }
            val spillSlot = functionGenerationContext.vars.addressOf(spillIndex)
            functionGenerationContext.moveArcOwnedReferenceIntoReturnSlot(evaluated, spillSlot, targetReturnSlot)
            functionGenerationContext.markReturnValueAlreadyInReturnSlot()
            currentCodeContext.genReturn(target, evaluated)
            return codegen.kNothingFakeValue
        }
        // Tail-suspend lowering wraps the real `return directCall()` in one synthetic outer
        // function return. The inner return has already branched to the epilogue and recorded the
        // exact result-slot ownership fact; emitting the outer return would add an unreachable
        // `undef` predecessor and make a no-predecessor edge look like an uninitialized normal
        // return. Suppress only the identity-selected shape after its inner return terminated.
        if (containsSelectedCoroutineTailCall && functionGenerationContext.isAfterTerminator()) {
            return codegen.kNothingFakeValue
        }
        val forwardedReturn = currentArcOwnedResultForwarding
        if (target == (currentCodeContext.functionScope() as? FunctionScope)?.declaration &&
                ((forwardedReturn != null &&
                        (value as? IrGetValue)?.symbol?.owner === forwardedReturn.returned) ||
                        (targetReturnSlot != null &&
                                functionGenerationContext.arcResultIsAlreadyOwnedBySlot(evaluated, targetReturnSlot)))) {
            functionGenerationContext.markReturnValueAlreadyInReturnSlot()
        }
        currentCodeContext.genReturn(target, evaluated)
        return codegen.kNothingFakeValue
    }

    private fun IrExpression.containsSelectedCoroutineTailCall(): Boolean {
        var found = false
        acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                if (!found) element.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) = Unit

            override fun visitCall(expression: IrCall) {
                if (expression in arcOwnership.coroutineResultSlotForwardingCalls) {
                    found = true
                } else {
                    expression.acceptChildrenVoid(this)
                }
            }
        })
        return found
    }

    //-------------------------------------------------------------------------//
    private inner class ReturnableBlockScope(val returnableBlock: IrReturnableBlock, val resultSlot: LLVMValueRef?) :
            FileScope(returnableBlock.inlineFunction?.let {
                generationState.inlineFunctionOrigins[it]?.irFile ?: it.fileOrNull
            }
                    ?: (currentCodeContext.fileScope() as? FileScope)?.file
                    ?: error("returnable block should belong to current file at least")) {

        var bbExit : LLVMBasicBlockRef? = null
        var resultPhi : LLVMValueRef? = null
        private val arcReturnsAlreadyOwnedByResultSlot = mutableListOf<Boolean>()
        private val functionScope by lazy {
            returnableBlock.inlineFunction?.let {
                it.scope(file().fileEntry.line(generationState.inlineFunctionOrigins[it]?.startOffset ?: it.startOffset))
            }
        }

        private fun getExit(): LLVMBasicBlockRef {
            val location = returnableBlock.inlineFunction?.let {
                location(generationState.inlineFunctionOrigins[it]?.endOffset ?: it.endOffset)
            } ?: returnableBlock.statements.lastOrNull()?.let {
                location(it.endOffset)
            }
            if (bbExit == null) bbExit = functionGenerationContext.basicBlock("returnable_block_exit", location)
            return bbExit!!
        }

        private fun getResult(): LLVMValueRef {
            if (resultPhi == null) {
                val bbCurrent = functionGenerationContext.currentBlock
                functionGenerationContext.positionAtEnd(getExit())
                resultPhi = functionGenerationContext.phi(returnableBlock.type.toLLVMType(llvm))
                functionGenerationContext.positionAtEnd(bbCurrent)
            }
            return resultPhi!!
        }

        override fun genReturn(target: IrSymbolOwner, value: LLVMValueRef?) {
            if (target != returnableBlock) {                                    // It is not our "local return".
                super.genReturn(target, value)
                return
            }
                                                                                // It is local return from current function.
            if (!returnableBlock.type.isUnit()) {
                arcReturnsAlreadyOwnedByResultSlot += resultSlot != null && value != null &&
                        functionGenerationContext.arcResultIsAlreadyOwnedBySlot(value, resultSlot)
            }
            functionGenerationContext.br(getExit())                                               // Generate branch on exit block.

            if (!returnableBlock.type.isUnit()) {                               // If function returns more then "unit"
                functionGenerationContext.assignPhis(getResult() to value!!)                      // Assign return value to result PHI node.
            }
        }

        fun markResultPhiOwnedByResultSlot() {
            val slot = resultSlot ?: return
            val phi = resultPhi ?: return
            if (arcReturnsAlreadyOwnedByResultSlot.isNotEmpty() && arcReturnsAlreadyOwnedByResultSlot.all { it }) {
                functionGenerationContext.markArcResultOwnedBySlot(phi, slot)
            }
        }

        override fun getReturnSlot(target: IrSymbolOwner) : LLVMValueRef? {
            return if (target == returnableBlock) {
                resultSlot
            } else {
                super.getReturnSlot(target)
            }
        }

        override fun returnableBlockScope(): CodeContext? = this

        override fun location(offset: Int): LocationInfo? {
            return if (returnableBlock.inlineFunction != null) {
                val diScope = functionScope ?: return null
                val inlinedAt = outerContext.location(returnableBlock.startOffset) ?: return null
                LocationInfo(diScope, file.fileEntry.line(offset), file.fileEntry.column(offset), inlinedAt)
            } else {
                outerContext.location(offset)
            }
        }

        /**
         * Note: DILexicalBlocks aren't nested, they should be scoped with the parent function.
         */
        private val scope by lazy {
            if (!context.shouldContainLocationDebugInfo() || returnableBlock.startOffset == UNDEFINED_OFFSET)
                return@lazy null
            val lexicalBlockFile = DICreateLexicalBlockFile(debugInfo.builder, functionScope()!!.scope(), super.file.file())
            DICreateLexicalBlock(debugInfo.builder, lexicalBlockFile, super.file.file(), returnableBlock.startLine(), returnableBlock.startColumn())!!
        }

        override fun scope() = scope

    }

    //-------------------------------------------------------------------------//

    private open inner class FileScope(val file: IrFile) : InnerScopeImpl() {
        override fun fileScope(): CodeContext? = this

        override fun location(offset: Int) = scope()?.let { LocationInfo(it, file.fileEntry.line(offset), file.fileEntry.column(offset)) }

        @Suppress("UNCHECKED_CAST")
        private val scope by lazy {
            if (!context.shouldContainLocationDebugInfo())
                return@lazy null
            file.file() as DIScopeOpaqueRef?
        }

        override fun scope() = scope
    }

    //-------------------------------------------------------------------------//

    private inner class ClassScope(val clazz:IrClass) : InnerScopeImpl() {
        val isExported
            get() = clazz.isExported()
        var offsetInBits = 0L
        val members = mutableListOf<DIDerivedTypeRef>()
        @Suppress("UNCHECKED_CAST")
        val scope = if (isExported && context.shouldContainDebugInfo())
            debugInfo.objHeaderPointerType
        else null
        override fun classScope(): CodeContext? = this
    }

    //-------------------------------------------------------------------------//
    private fun evaluateReturnableBlock(value: IrReturnableBlock, resultSlot: LLVMValueRef?): LLVMValueRef {
        context.log{"evaluateReturnableBlock         : ${value.statements.forEach { ir2string(it) }}"}

        val returnableBlockScope = ReturnableBlockScope(value, resultSlot)
        generateDebugTrambolineIf("inline", value)
        using(returnableBlockScope) {
            using(VariableScope()) {
                value.statements.forEach {
                    generateStatement(it)
                }
            }
        }

        val bbExit = returnableBlockScope.bbExit
        if (bbExit != null) {
            if (!functionGenerationContext.isAfterTerminator()) {                 // TODO should we solve this problem once and for all
                functionGenerationContext.unreachable()
            }
            functionGenerationContext.positionAtEnd(bbExit)
            returnableBlockScope.markResultPhiOwnedByResultSlot()
        }

        return returnableBlockScope.resultPhi ?: if (value.type.isUnit()) {
            codegen.theUnitInstanceRef.llvm
        } else {
            LLVMGetUndef(value.type.toLLVMType(llvm))!!
        }
    }

    //-------------------------------------------------------------------------//

    private fun evaluateContainerExpression(value: IrContainerExpression, resultSlot: LLVMValueRef?): LLVMValueRef {
        context.log{"evaluateContainerExpression    : ${value.statements.forEach { ir2string(it) }}"}

        val scope = if (value.isTransparentScope) {
            null
        } else {
            VariableScope()
        }

        using(scope) {
            value.statements.dropLast(1).forEach {
                generateStatement(it)
            }
            value.statements.lastOrNull()?.let {
                if (it is IrExpression) {
                    return evaluateExpression(it, resultSlot)
                } else {
                    generateStatement(it)
                }
            }

            assert(value.type.isUnit())
            return codegen.theUnitInstanceRef.llvm
        }
    }

    private fun evaluateInstanceInitializerCall(expression: IrInstanceInitializerCall): LLVMValueRef {
        assert (expression.type.isUnit())
        return codegen.theUnitInstanceRef.llvm
    }

    //-------------------------------------------------------------------------//
    private fun evaluateCall(value: IrFunctionAccessExpression, resultSlot: LLVMValueRef?): LLVMValueRef {
        val call = value as? IrCall ?: return evaluateCallBody(value, resultSlot)
        val projection = currentStringBuilderBackingArrayProjectionEmission?.takeIf {
            call === it.plan.consumer
        } ?: return evaluateCallBody(value, resultSlot)
        projection.beginConsumer(call)
        return try {
            evaluateCallBody(value, resultSlot)
        } finally {
            projection.endConsumer(call)
        }
    }

    private fun evaluateCallBody(value: IrFunctionAccessExpression, resultSlot: LLVMValueRef?): LLVMValueRef {
        context.log{"evaluateCall                   : ${ir2string(value)}"}
        if (value is IrCall) {
            currentSafeContinuationSROAEmission?.let { emission ->
                emission.observeStructuralCall(value)
                if (emission.isSelectedCall(value)) return emission.emitSelectedCall(value, resultSlot)
            }
            currentCoroutineGuaranteedPhiEmission?.markCall(value)
        }

        if (value is IrCall) {
            arcOwnership.coroutineSuspendedScopedBorrowsByCall[value]?.let { site ->
                require(resultSlot == null) {
                    "selected COROUTINE_SUSPENDED scoped borrow reached an owning result slot"
                }
                val emission = currentCoroutineSuspendedScopedBorrowEmission
                    ?: error("COROUTINE_SUSPENDED scoped borrow escaped its selected function")
                return emission.emit(value, site)
            }
        }

        if (value is IrCall) {
            arcOwnership.resultCompanionImmortalLoadsByCall[value]?.let { plan ->
                require(resultSlot == null) { "Result.Companion immortal load cannot initialize an owning result slot" }
                return evaluateResultCompanionImmortalLoad(value, plan)
            }
        }

        if (value is IrCall && resultSlot == null) {
            arcOwnership.rootedGlobalProjections[value]?.let { plan ->
                return evaluateRootedGlobalProjection(value, plan)
            }
        }

        if (value is IrCall && value in arcOwnership.borrowedArrayElementCalls) {
            require(resultSlot == null) { "Borrowed Array.get cannot initialize an owning result slot" }
            return evaluateBorrowedArrayElement(value)
        }

        val safeContinuationScalarResultSlot = (value as? IrCall)?.let { call ->
            currentSafeContinuationSROAEmission?.structuralResultSlot(call)
        }
        require(safeContinuationScalarResultSlot == null || resultSlot == null) {
            "SafeContinuation SROA scalar Result box collided with a caller result slot"
        }
        val requiredPromotionBoundary = (value as? IrCall)?.let { arcOwnership.scopedArcReferenceLoads[it] }
        val scopedPromotionSlot = if (resultSlot == null && requiredPromotionBoundary != null) {
            require(currentArcPromotionBoundary === requiredPromotionBoundary) {
                "Scoped ARC reference load escaped its verified full-expression boundary"
            }
            functionGenerationContext.vars.createAnonymousSlot()
        } else {
            null
        }
        val effectiveResultSlot = scopedPromotionSlot ?: safeContinuationScalarResultSlot ?: resultSlot

        fun recordScopedPromotion() {
            scopedPromotionSlot?.let {
                val slots = requireNotNull(currentArcPromotionSlots) {
                    "Scoped ARC reference load was generated outside a full-expression statement"
                }
                if (it !in slots) slots.add(it)
            }
        }

        intrinsicGenerator.tryEvaluateSpecialCall(value, effectiveResultSlot)?.let {
            recordScopedPromotion()
            (value as? IrCall)?.let { call -> currentBranchGuaranteedPhiEmission?.markTerminalEquality(call) }
            return it
        }

        val args = evaluateExplicitArgs(value)
        val returnedReceiverBorrow = value is IrCall && value in arcOwnership.returnedReceiverBorrowCalls
        val discardedReturnedReceiverGroup = (value as? IrCall)?.let {
            arcOwnership.discardedReturnedReceiverGroupsByCall[it]
        }
        val discardedReturnedReceiverSeedSlot = discardedReturnedReceiverGroup?.let { group ->
            val receiver = value.dispatchReceiver as? IrGetValue
            require(resultSlot == null && scopedPromotionSlot == null && effectiveResultSlot == null &&
                    returnedReceiverBorrow && receiver?.symbol?.owner === group.receiver &&
                    group.calls.any { it === value }) {
                "ARC discarded returned-receiver group escaped its exact statement/receiver boundary"
            }
            requireNotNull(currentDiscardedReturnedReceiverSeedSlots) {
                "ARC discarded returned-receiver call was generated outside its function"
            }.getOrPut(group) { functionGenerationContext.vars.createAnonymousSlot() }
        }
        val returnedReceiverSlot = if (returnedReceiverBorrow) {
            val receiverValue = args.firstOrNull()
            val slot = receiverValue?.let { functionGenerationContext.arcOwningSlotForValue(it) }
            val eligibility = ArcReturnedReceiverSlotReuseEligibility(
                    arcEnabled = context.memoryModel == MemoryModel.ARC,
                    optimizationsEnabled = context.config.optimizationsEnabled,
                    debugInfoDisabled = !context.shouldContainDebugInfo(),
                    exactCallIdentitySelected = true,
                    noRequestedResultSlot = effectiveResultSlot == null,
                    uniqueCurrentBlockOwningSlot = slot != null,
                    pointerIdenticalReceiverFact = receiverValue != null && slot != null &&
                            functionGenerationContext.arcResultIsAlreadyOwnedBySlot(receiverValue, slot),
            )
            // A summary can also be reached with a merely guaranteed receiver parameter. That
            // caller has no owning slot to reuse, so fail closed to the ordinary result ABI.
            slot.takeIf { eligibility.isAuthorized() && value.dispatchReceiver != null }
        } else null
        // The first call in a stack-promoted fluent chain has no ARC owning receiver slot yet.
        // Make the ABI's otherwise-implicit anonymous slot explicit so callDirect records its
        // normal-success ownership fact; later receiver-identical links can then reuse it. The
        // fresh frame slot is zero initialized, and ordinary frame cleanup owns every unwind edge.
        val returnedReceiverSeedSlot = if (discardedReturnedReceiverSeedSlot == null &&
                returnedReceiverBorrow && effectiveResultSlot == null &&
                returnedReceiverSlot == null) {
            functionGenerationContext.vars.createAnonymousSlot()
        } else null
        val ownedResultHeapStoreSlot = (value as? IrCall)?.let { call ->
            currentOwnedResultHeapStoreEmission?.takeIf { it.isProducer(call) }
        }?.let {
            require(resultSlot == null && scopedPromotionSlot == null && effectiveResultSlot == null &&
                    discardedReturnedReceiverSeedSlot == null && returnedReceiverSlot == null &&
                    returnedReceiverSeedSlot == null) {
                "owned Result-box producer collided with another result-slot ownership plan"
            }
            functionGenerationContext.vars.createAnonymousSlot()
        }
        val freshOwnedFieldStoreSlot = currentFreshOwnedFieldStoreEmission
            ?.takeIf { it.isProducer(value) }
            ?.let {
                require(resultSlot == null && scopedPromotionSlot == null && effectiveResultSlot == null &&
                        discardedReturnedReceiverSeedSlot == null && returnedReceiverSlot == null &&
                        returnedReceiverSeedSlot == null && ownedResultHeapStoreSlot == null &&
                        value is IrConstructorCall) {
                    "fresh owned field producer collided with another result-slot ownership plan"
                }
                functionGenerationContext.vars.createAnonymousSlot()
            }
        val callResultSlot = discardedReturnedReceiverSeedSlot ?: returnedReceiverSlot ?:
                returnedReceiverSeedSlot ?: ownedResultHeapStoreSlot ?: freshOwnedFieldStoreSlot ?:
                effectiveResultSlot

        updateBuilderDebugLocation(value)
        val result = when (value) {
            is IrDelegatingConstructorCall -> delegatingConstructorCall(value.symbol.owner, args)
            is IrConstructorCall -> evaluateConstructorCall(
                value,
                args,
                freshOwnedFieldStoreSlot ?: effectiveResultSlot,
            )
            else -> evaluateFunctionCall(
                    value as IrCall,
                    args,
                    resultLifetime(value),
                    callResultSlot,
                    returnedReceiverBorrow &&
                            (returnedReceiverSlot != null || discardedReturnedReceiverSeedSlot != null),
            )
        }
        if (discardedReturnedReceiverSeedSlot != null) {
            require(functionGenerationContext.arcResultIsAlreadyOwnedBySlot(
                result, discardedReturnedReceiverSeedSlot
            )) {
                "ARC discarded returned-receiver seed did not own the exact normal result"
            }
        }
        if (ownedResultHeapStoreSlot != null) {
            currentOwnedResultHeapStoreEmission?.bindProducer(
                value,
                result,
                ownedResultHeapStoreSlot,
            )
        }
        if (freshOwnedFieldStoreSlot != null) {
            currentFreshOwnedFieldStoreEmission?.bindProducer(
                value as IrConstructorCall,
                result,
                freshOwnedFieldStoreSlot,
            )
        }
        (value as? IrCall)?.let { call ->
            currentSafeContinuationSROAEmission?.bindStructuralResult(call, result, callResultSlot)
        }
        recordScopedPromotion()
        (value as? IrCall)?.let { call -> currentBranchGuaranteedPhiEmission?.markTerminalEquality(call) }
        return result
    }

    /**
     * Emits a +0 reference whose process lifetime is guaranteed by a verified compiler-owned
     * global. The ordinary object-result ABI is intentionally bypassed: creating an anonymous
     * result slot here would immediately retain the root and defeat the ownership proof.
     */
    private fun evaluateRootedGlobalProjection(
        value: IrCall,
        plan: ArcRootedGlobalProjectionPlan,
    ): LLVMValueRef {
        require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                !context.shouldContainDebugInfo() && !context.config.arcDiagnosticsEnabled) {
            "ARC rooted global projection escaped its verified compilation mode"
        }
        require(value.symbol.owner === plan.getter) {
            "ARC rooted global projection changed declaration identity"
        }
        val root = plan.rootField
        require(root.isStatic && root.isFinal && root.type.binaryTypeIsReference()) {
            "ARC rooted global projection lost its immutable reference root"
        }
        if (context.config.threadsAreAllowed && root.isGlobalNonPrimitive(context)) {
            functionGenerationContext.checkGlobalsAccessible(currentCodeContext.exceptionHandler)
        }
        val rootAddress = staticFieldPtr(root, functionGenerationContext)
        val rootValue = functionGenerationContext.loadSlot(
            rootAddress,
            false,
            null,
            alignment = generationState.llvmDeclarations.forStaticField(root).alignment,
        )
        require((value.getValueArgument(0) as? IrConst<*>)?.value == plan.getterId)
        val borrowedGetter = llvm.externalNativeRuntimeFunction(
            "Kotlin_Array_get_borrowed",
            LlvmRetType(codegen.kObjHeaderPtr),
            listOf(LlvmParamType(codegen.kObjHeaderPtr), LlvmParamType(llvm.int32Type)),
        )
        return functionGenerationContext.call(
            borrowedGetter,
            listOf(rootValue, llvm.int32(plan.getterId)),
            exceptionHandler = currentCodeContext.exceptionHandler,
            verbatim = true,
        )
    }

    /** Emit the +0 value of the exact permanent stdlib Result.Companion static root. */
    private fun evaluateResultCompanionImmortalLoad(
        value: IrCall,
        plan: ArcResultCompanionImmortalLoadPlan,
    ): LLVMValueRef {
        require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                !context.shouldContainDebugInfo() && !context.config.arcDiagnosticsEnabled &&
                value === plan.call && value.symbol.owner === plan.getter &&
                plan.getter.konanLibrary === plan.stdlibLibrary &&
                value.dispatchReceiver == null && value.extensionReceiver == null &&
                value.valueArgumentsCount == 0 && value.typeArgumentsCount == 0) {
            "Result.Companion immortal load escaped its verified call identity"
        }
        val root = plan.rootField
        require(root.isStatic && root.isFinal && root.type.classOrNull?.owner === plan.companionClass &&
                root.konanLibrary === plan.stdlibLibrary &&
                plan.companionClass.konanLibrary === plan.stdlibLibrary &&
                plan.resultClass.konanLibrary === plan.stdlibLibrary &&
                plan.companionClass.parent === plan.resultClass) {
            "Result.Companion immortal root lost its exact declaration shape"
        }
        if (context.config.threadsAreAllowed && root.isGlobalNonPrimitive(context)) {
            functionGenerationContext.checkGlobalsAccessible(currentCodeContext.exceptionHandler)
        }
        return functionGenerationContext.loadSlot(
            staticFieldPtr(root, functionGenerationContext),
            false,
            null,
            alignment = generationState.llvmDeclarations.forStaticField(root).alignment,
        )
    }

    /**
     * Emit the runtime's bounds-checked +0 Array projection. The ownership planner guarantees that
     * the fresh local array remains alive and its element slot cannot be overwritten until the
     * immediate Kotlin consumer returns or unwinds.
     */
    private fun evaluateBorrowedArrayElement(value: IrCall): LLVMValueRef {
        require(value.symbol == context.ir.symbols.arrayGet[context.ir.symbols.array])
        val args = evaluateExplicitArgs(value)
        require(args.size == 2)
        require(args[0].type == codegen.kObjHeaderPtr && args[1].type == llvm.int32Type)
        updateBuilderDebugLocation(value)
        val borrowedGetter = llvm.externalNativeRuntimeFunction(
            "Kotlin_Array_get_borrowed",
            LlvmRetType(codegen.kObjHeaderPtr),
            listOf(LlvmParamType(codegen.kObjHeaderPtr), LlvmParamType(llvm.int32Type)),
        )
        return functionGenerationContext.call(
            borrowedGetter,
            args,
            exceptionHandler = currentCodeContext.exceptionHandler,
            verbatim = true,
        )
    }

    //-------------------------------------------------------------------------//
    private fun file() = (currentCodeContext.fileScope() as FileScope).file

    //-------------------------------------------------------------------------//
    private fun updateBuilderDebugLocation(element: IrElement) {
        if (!context.shouldContainLocationDebugInfo() || currentCodeContext.functionScope() == null || element.startLocation == null) return
        functionGenerationContext.debugLocation(element.startLocation!!, element.endLocation!!)
    }

    private val IrElement.startLocation: LocationInfo?
        get() = if (!context.shouldContainLocationDebugInfo()) null
            else currentCodeContext.location(startOffset)

    private val IrElement.endLocation: LocationInfo?
        get() = if (!context.shouldContainLocationDebugInfo()) null
            else currentCodeContext.location(endOffset)

    //-------------------------------------------------------------------------//
    private fun IrElement.startLine() = file().fileEntry.line(this.startOffset)

    //-------------------------------------------------------------------------//
    private fun IrElement.startColumn() = file().fileEntry.column(this.startOffset)

    //-------------------------------------------------------------------------//
    private fun IrElement.endLine() = file().fileEntry.line(this.endOffset)

    //-------------------------------------------------------------------------//
    private fun IrElement.endColumn() = file().fileEntry.column(this.endOffset)

    //-------------------------------------------------------------------------//
    private fun debugFieldDeclaration(expression: IrField) {
        val scope = currentCodeContext.classScope() as? ClassScope ?: return
        if (!scope.isExported || !context.shouldContainDebugInfo()) return
        with(debugInfo) {
            val irFile = (currentCodeContext.fileScope() as FileScope).file
            val sizeInBits = expression.type.size
            scope.offsetInBits += sizeInBits
            val alignInBits = expression.type.alignment
            scope.offsetInBits = alignTo(scope.offsetInBits, alignInBits)
            @Suppress("UNCHECKED_CAST")
            scope.members.add(DICreateMemberType(
                    refBuilder = builder,
                    refScope = scope.scope as DIScopeOpaqueRef,
                    name = expression.computeSymbolName(),
                    file = irFile.file(),
                    lineNum = expression.startLine(),
                    sizeInBits = sizeInBits,
                    alignInBits = alignInBits,
                    offsetInBits = scope.offsetInBits,
                    flags = 0,
                    type = expression.type.diType(codegen.llvmTargetData)
            )!!)
        }
    }


    //-------------------------------------------------------------------------//
    private fun IrFile.file(): DIFileRef {
        return debugInfo.files.getOrPut(this.fileEntry.name) {
            val path = this.fileEntry.name.toFileAndFolder(context.config)
            DICreateFile(debugInfo.builder, path.file, path.folder)!!
        }
    }

    //-------------------------------------------------------------------------//

    // Saved calculated IrFunction scope which is used several time for getting locations and generating debug info.
    private var irFunctionSavedScope: Pair<IrFunction, DIScopeOpaqueRef?>? = null

    private fun IrFunction.scope(): DIScopeOpaqueRef? = if (startOffset != UNDEFINED_OFFSET) (
            if (irFunctionSavedScope != null && this == irFunctionSavedScope!!.first)
                irFunctionSavedScope!!.second
            else
                this.scope(startLine()).also { irFunctionSavedScope = Pair(this, it) }
            ) else null

    private val IrFunction.isReifiedInline:Boolean
        get() = isInline && typeParameters.any { it.isReified }

    @Suppress("UNCHECKED_CAST")
    private fun IrFunction.scope(startLine:Int): DIScopeOpaqueRef? {
        if (!context.shouldContainLocationDebugInfo())
            return null

        val functionLlvmValue = when {
            isReifiedInline -> null
            // TODO: May be tie up inline lambdas to their outer function?
            codegen.isExternal(this) && !KonanBinaryInterface.isExported(this) -> null
            this is IrSimpleFunction && isSuspend -> this.getOrCreateFunctionWithContinuationStub(context).let { codegen.llvmFunctionOrNull(it) }
            else -> codegen.llvmFunctionOrNull(this)
        }
        return with(debugInfo) {
            val f = this@scope
            val nodebug = f is IrConstructor && f.parentAsClass.isSubclassOf(context.irBuiltIns.throwableClass.owner)
            if (functionLlvmValue != null) {
                subprograms.getOrPut(functionLlvmValue) {
                    memScoped {
                        val subroutineType = subroutineType(codegen.llvmTargetData)
                        diFunctionScope(name.asString(), functionLlvmValue.name!!, startLine, subroutineType, nodebug).also {
                            if (!this@scope.isInline)
                                functionLlvmValue.addDebugInfoSubprogram(it)
                        }
                    }
                } as DIScopeOpaqueRef
            } else {
                inlinedSubprograms.getOrPut(this@scope) {
                    memScoped {
                        val subroutineType = subroutineType(codegen.llvmTargetData)
                        diFunctionScope(name.asString(), "<inlined-out:$name>", startLine, subroutineType, nodebug)
                    }
                } as DIScopeOpaqueRef
            }
        }

    }

    @Suppress("UNCHECKED_CAST")
    private fun LlvmCallable.scope(startLine:Int, subroutineType: DISubroutineTypeRef, nodebug: Boolean): DIScopeOpaqueRef? {
        return debugInfo.subprograms.getOrPut(this) {
            diFunctionScope(name!!, name!!, startLine, subroutineType, nodebug).also {
                this@scope.addDebugInfoSubprogram(it)
            }
        }  as DIScopeOpaqueRef
    }

    @Suppress("UNCHECKED_CAST")
    private fun diFunctionScope(name: String, linkageName: String, startLine: Int, subroutineType: DISubroutineTypeRef, nodebug: Boolean) = DICreateFunction(
                builder = debugInfo.builder,
                scope = debugInfo.compilationUnit,
                name = (if (nodebug) "<NODEBUG>" else "") + name,
                linkageName = linkageName,
                file = file().file(),
                lineNo = startLine,
                type = subroutineType,
                //TODO: need more investigations.
                isLocal = 0,
                isDefinition = 1,
                scopeLine = 0)!!

    //-------------------------------------------------------------------------//


    private fun IrFunction.returnsUnit() = returnType.isUnit().also {
        require(!isSuspend) { "Suspend functions should be lowered out at this point"}
    }

    /**
     * Evaluates all arguments of [expression] that are explicitly represented in the IR.
     * Returns results in the same order as LLVM function expects, assuming that all explicit arguments
     * exactly correspond to a tail of LLVM parameters.
     */
    private fun evaluateExplicitArgs(expression: IrFunctionAccessExpression): List<LLVMValueRef> {
        val result = expression.getArgumentsWithIr().map { (_, argExpr) ->
            evaluateExpression(argExpr)
        }
        val explicitParametersCount = expression.symbol.owner.explicitParametersCount
        if (result.size != explicitParametersCount) {
            error("Number of arguments explicitly represented in the IR ${result.size} differs from expected " +
                    "$explicitParametersCount in ${ir2string(expression)}")
        }
        return result
    }

    //-------------------------------------------------------------------------//

    private fun evaluateFunctionReference(expression: IrFunctionReference): LLVMValueRef {
        // TODO: consider creating separate IR element for pointer to function.
        assert (expression.type.getClass()?.descriptor?.fqNameUnsafe == InteropFqNames.cPointer) {
            "assert: ${expression.type.getClass()?.descriptor?.fqNameUnsafe} == ${InteropFqNames.cPointer}"
        }

        assert (expression.getArguments().isEmpty())

        val function = expression.symbol.owner
        assert (function.dispatchReceiverParameter == null)

        return codegen.functionEntryPointAddress(function)
    }

    //-------------------------------------------------------------------------//

    private inner class SuspendableExpressionScope(val resumePoints: MutableList<LLVMBasicBlockRef>) : InnerScopeImpl() {
        override fun addResumePoint(bbLabel: LLVMBasicBlockRef): Int {
            val result = resumePoints.size
            resumePoints.add(bbLabel)
            return result
        }
    }

    private fun evaluateSuspendableExpression(expression: IrSuspendableExpression, resultSlot: LLVMValueRef?): LLVMValueRef {
        val suspensionPointId = evaluateExpression(expression.suspensionPointId)
        val bbStart = functionGenerationContext.basicBlock("start", expression.result.startLocation)
        val bbDispatch = functionGenerationContext.basicBlock("dispatch", expression.suspensionPointId.startLocation)

        val resumePoints = mutableListOf<LLVMBasicBlockRef>()
        using (SuspendableExpressionScope(resumePoints)) {
            functionGenerationContext.condBr(functionGenerationContext.icmpEq(suspensionPointId, llvm.kNullInt8Ptr), bbStart, bbDispatch)

            functionGenerationContext.positionAtEnd(bbStart)
            val result = evaluateExpression(expression.result, resultSlot)

            functionGenerationContext.appendingTo(bbDispatch) {
                if (context.config.indirectBranchesAreAllowed)
                    functionGenerationContext.indirectBr(suspensionPointId, resumePoints)
                else {
                    val bbElse = functionGenerationContext.basicBlock("else", null) {
                        functionGenerationContext.unreachable()
                    }

                    val cases = resumePoints.withIndex().map { llvm.int32(it.index + 1) to it.value }
                    functionGenerationContext.switch(functionGenerationContext.ptrToInt(suspensionPointId, llvm.int32Type), cases, bbElse)
                }
            }
            return result
        }
    }

    private inner class SuspensionPointScope(val suspensionPointId: IrVariable,
                                             val bbResume: LLVMBasicBlockRef,
                                             val bbResumeId: Int): InnerScopeImpl() {
        override fun genGetValue(value: IrValueDeclaration, resultSlot: LLVMValueRef?): LLVMValueRef {
            if (value == suspensionPointId) {
                return if (context.config.indirectBranchesAreAllowed)
                           functionGenerationContext.blockAddress(bbResume)
                       else
                           functionGenerationContext.intToPtr(llvm.int32(bbResumeId + 1), llvm.int8PtrType)
            }
            return super.genGetValue(value, resultSlot)
        }
    }

    private fun evaluateSuspensionPoint(expression: IrSuspensionPoint): LLVMValueRef {
        val bbResume = functionGenerationContext.basicBlock("resume", expression.resumeResult.startLocation)
        val id = currentCodeContext.addResumePoint(bbResume)

        using (SuspensionPointScope(expression.suspensionPointIdParameter, bbResume, id)) {
            continuationBlock(expression.type, expression.result.startLocation).run {
                val normalResult = evaluateExpression(expression.result)
                functionGenerationContext.jump(this, normalResult)

                functionGenerationContext.positionAtEnd(bbResume)
                val resumeResult = evaluateExpression(expression.resumeResult)
                functionGenerationContext.jump(this, resumeResult)

                functionGenerationContext.positionAtEnd(this.block)
                return this.value
            }
        }
    }

    //-------------------------------------------------------------------------//

    private fun evaluateClassReference(classReference: IrClassReference): LLVMValueRef {
        val typeInfoPtr = codegen.typeInfoValue(classReference.symbol.owner as IrClass)
        return functionGenerationContext.bitcast(llvm.int8PtrType, typeInfoPtr)
    }

    //-------------------------------------------------------------------------//

    private fun evaluateFunctionCall(callee: IrCall, args: List<LLVMValueRef>,
                                     resultLifetime: Lifetime, resultSlot: LLVMValueRef?,
                                     verifiedReturnedReceiverBorrow: Boolean = false): LLVMValueRef {
        val function = callee.symbol.owner
        require(!function.isSuspend) { "Suspend functions should be lowered out at this point"}
        return when {
            function.isTypedIntrinsic -> intrinsicGenerator.evaluateCall(callee, args, resultSlot)
            function.isBuiltInOperator -> evaluateOperatorCall(callee, args)
            function.origin == DECLARATION_ORIGIN_STATIC_GLOBAL_INITIALIZER -> evaluateFileGlobalInitializerCall(function)
            function.origin == DECLARATION_ORIGIN_STATIC_THREAD_LOCAL_INITIALIZER -> evaluateFileThreadLocalInitializerCall(function)
            function.origin == DECLARATION_ORIGIN_STATIC_STANDALONE_THREAD_LOCAL_INITIALIZER -> evaluateFileStandaloneThreadLocalInitializerCall(function)
            else -> evaluateSimpleFunctionCall(
                    function,
                    args,
                    resultLifetime,
                    callee.superQualifierSymbol?.owner,
                    resultSlot,
                    callee in arcOwnership.coroutineResultSlotForwardingCalls,
                    verifiedReturnedReceiverBorrow,
                    arcOwnership.lockedReadResultSlotForwardingCalls[callee],
                    callee in arcOwnership.selectiveInlineStringAppendCalls,
            )
        }
    }

    private fun evaluateFileGlobalInitializerCall(fileInitializer: IrFunction) = with(functionGenerationContext) {
        val statePtr = getGlobalInitStateFor(fileInitializer.parent as IrDeclarationContainer)
        val initializerPtr = with(codegen) { fileInitializer.llvmFunction.asCallback() }

        val bbInit = basicBlock("label_init", null)
        val bbExit = basicBlock("label_continue", null)
        moveBlockAfterEntry(bbExit)
        moveBlockAfterEntry(bbInit)
        val state = load(statePtr, memoryOrder = LLVMAtomicOrdering.LLVMAtomicOrderingAcquire)
        condBr(icmpEq(state, llvm.int32(FILE_INITIALIZED)), bbExit, bbInit)
        positionAtEnd(bbInit)
        call(llvm.callInitGlobalPossiblyLock, listOf(statePtr, initializerPtr),
                exceptionHandler = currentCodeContext.exceptionHandler)
        br(bbExit)
        positionAtEnd(bbExit)
        codegen.theUnitInstanceRef.llvm
    }

    private fun evaluateFileThreadLocalInitializerCall(fileInitializer: IrFunction) = with(functionGenerationContext) {
        val globalStatePtr = getGlobalInitStateFor(fileInitializer.parent as IrDeclarationContainer)
        val localState = getThreadLocalInitStateFor(fileInitializer.parent as IrDeclarationContainer)
        val localStatePtr = localState.getAddress(functionGenerationContext)
        val initializerPtr = with(codegen) { fileInitializer.llvmFunction.asCallback() }

        val bbInit = basicBlock("label_init", null)
        val bbCheckLocalState = basicBlock("label_check_local", null)
        val bbExit = basicBlock("label_continue", null)
        moveBlockAfterEntry(bbExit)
        moveBlockAfterEntry(bbCheckLocalState)
        moveBlockAfterEntry(bbInit)
        val globalState = load(globalStatePtr)
        LLVMSetVolatile(globalState, 1)
        // Make sure we're not in the middle of global initializer invocation -
        // thread locals can be initialized only after all shared globals have been initialized.
        condBr(icmpNe(globalState, llvm.int32(FILE_INITIALIZED)), bbExit, bbCheckLocalState)
        positionAtEnd(bbCheckLocalState)
        condBr(icmpNe(load(localStatePtr), llvm.int32(FILE_INITIALIZED)), bbInit, bbExit)
        positionAtEnd(bbInit)
        call(llvm.callInitThreadLocal, listOf(globalStatePtr, localStatePtr, initializerPtr),
                exceptionHandler = currentCodeContext.exceptionHandler)
        br(bbExit)
        positionAtEnd(bbExit)
        codegen.theUnitInstanceRef.llvm
    }

    private fun evaluateFileStandaloneThreadLocalInitializerCall(fileInitializer: IrFunction) = with(functionGenerationContext) {
        val state = getThreadLocalInitStateFor(fileInitializer.parent as IrDeclarationContainer)
        val statePtr = state.getAddress(functionGenerationContext)
        val initializerPtr = with(codegen) { fileInitializer.llvmFunction.asCallback() }

        val bbInit = basicBlock("label_init", null)
        val bbExit = basicBlock("label_continue", null)
        moveBlockAfterEntry(bbExit)
        moveBlockAfterEntry(bbInit)
        condBr(icmpEq(load(statePtr), llvm.int32(FILE_INITIALIZED)), bbExit, bbInit)
        positionAtEnd(bbInit)
        call(llvm.callInitThreadLocal, listOf(llvm.kNullInt32Ptr, statePtr, initializerPtr),
                exceptionHandler = currentCodeContext.exceptionHandler)
        br(bbExit)
        positionAtEnd(bbExit)
        codegen.theUnitInstanceRef.llvm
    }

    //-------------------------------------------------------------------------//

    private fun evaluateSimpleFunctionCall(
            function: IrFunction, args: List<LLVMValueRef>,
            resultLifetime: Lifetime, superClass: IrClass? = null, resultSlot: LLVMValueRef? = null,
            verifiedCoroutineResultSlotForwarding: Boolean = false,
            verifiedReturnedReceiverBorrow: Boolean = false,
            verifiedLockedReadResultSlotForwarding: ArcLockedReadCanonicalPlan? = null,
            verifiedSelectiveStringAppendInline: Boolean = false): LLVMValueRef {
        //context.log{"evaluateSimpleFunctionCall : $tmpVariableName = ${ir2string(value)}"}
        if (superClass == null && function is IrSimpleFunction && function.isOverridable) {
            require(!verifiedCoroutineResultSlotForwarding) {
                "ARC coroutine result-slot forwarding escaped into a virtual call: ${function.fqNameForIrSerialization}"
            }
            require(verifiedLockedReadResultSlotForwarding == null) {
                "ARC locked-read result-slot forwarding escaped into a virtual call: ${function.fqNameForIrSerialization}"
            }
            require(!verifiedSelectiveStringAppendInline) {
                "ARC selective StringBuilder.append inline escaped into a virtual call"
            }
            return callVirtual(function, args, resultLifetime, resultSlot)
        } else {
            return callDirect(
                    function, args, resultLifetime, resultSlot,
                    verifiedCoroutineResultSlotForwarding, verifiedReturnedReceiverBorrow,
                    verifiedLockedReadResultSlotForwarding,
                    verifiedSelectiveStringAppendInline,
            )
        }
    }

    //-------------------------------------------------------------------------//
    private fun resultLifetime(callee: IrElement): Lifetime {
        return lifetimes.getOrElse(callee) { /* TODO: make IRRELEVANT */ Lifetime.GLOBAL }
    }

    private fun evaluateConstructorCall(callee: IrConstructorCall, args: List<LLVMValueRef>, resultSlot: LLVMValueRef?): LLVMValueRef {
        context.log{"evaluateConstructorCall        : ${ir2string(callee)}"}
        return memScoped {
            val constructedClass = callee.symbol.owner.constructedClass
            val requestedLifetime = resultLifetime(callee)
            val allocationLifetime = if (context.config.memoryModel == MemoryModel.ARC &&
                    constructedClass.hasArcDeinitInHierarchy() &&
                    (requestedLifetime == Lifetime.STACK || requestedLifetime == Lifetime.LOCAL)) {
                Lifetime.GLOBAL
            } else {
                requestedLifetime
            }
            val thisValue = when {
                constructedClass.isArray -> {
                    assert(args.isNotEmpty() && args[0].type == llvm.int32Type)
                    functionGenerationContext.allocArray(constructedClass, args[0],
                            allocationLifetime, currentCodeContext.exceptionHandler, resultSlot = resultSlot)
                }
                constructedClass == context.ir.symbols.string.owner -> {
                    // TODO: consider returning the empty string literal instead.
                    assert(args.isEmpty())
                    functionGenerationContext.allocArray(constructedClass, count = llvm.kImmInt32Zero,
                            lifetime = allocationLifetime, exceptionHandler = currentCodeContext.exceptionHandler, resultSlot = resultSlot)
                }

                constructedClass.isObjCClass() -> error("Call should've been lowered: ${callee.dump()}")

                else -> functionGenerationContext.allocInstance(constructedClass, allocationLifetime, resultSlot = resultSlot)
            }
            evaluateSimpleFunctionCall(callee.symbol.owner,
                    listOf(thisValue) + args, Lifetime.IRRELEVANT /* constructor doesn't return anything */)
            markArcDeinitInitialized(callee.symbol.owner, thisValue)
            thisValue
        }
    }

    private fun IrClass.hasArcDeinitInHierarchy(): Boolean =
            generateSequence(this) { it.getSuperClassNotAny() }.any { irClass ->
                irClass.declarations.any {
                    it is IrSimpleFunction && it.annotations.hasAnnotation(KonanFqNames.arcDeinit)
                }
            }

    private fun markArcDeinitInitialized(constructor: IrConstructor, instance: LLVMValueRef) {
        if (context.config.memoryModel != MemoryModel.ARC) return
        val constructedClass = constructor.constructedClass
        if (constructedClass.declarations.none {
                    it is IrSimpleFunction && it.annotations.hasAnnotation(KonanFqNames.arcDeinit)
                }) return

        call(
                llvm.Kotlin_ArcMarkDeinitInitialized,
                listOf(instance, with(codegen) { constructedClass.typeInfoPtr.llvm }),
                Lifetime.IRRELEVANT,
        )
    }

    private fun genGetObjCClass(irClass: IrClass): LLVMValueRef {
        return functionGenerationContext.getObjCClass(irClass, currentCodeContext.exceptionHandler)
    }

    private fun genGetObjCProtocol(irClass: IrClass): LLVMValueRef {
        // Note: this function will return the same result for Obj-C protocol and corresponding meta-class.

        assert(irClass.isInterface)
        assert(irClass.isExternalObjCClass())

        val annotation = irClass.annotations.findAnnotation(externalObjCClassFqName)!!
        val protocolGetterName = annotation.getAnnotationStringValue("protocolGetter")
        val protocolGetterProto = LlvmFunctionProto(
                protocolGetterName,
                LlvmFunctionSignature(LlvmRetType(llvm.int8PtrType)),
                origin = FunctionOrigin.OwnedBy(irClass),
                linkage = LLVMLinkage.LLVMExternalLinkage,
                independent = true // Protocol is header-only declaration.
        )
        val protocolGetter = llvm.externalFunction(protocolGetterProto)

        return call(protocolGetter, emptyList())
    }

    //-------------------------------------------------------------------------//
    private val kTrue = llvm.int1(true)
    private val kFalse = llvm.int1(false)

    // TODO: Intrinsify?
    private fun evaluateOperatorCall(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        context.log{"evaluateOperatorCall           : origin:${ir2string(callee)}"}
        val function = callee.symbol.owner
        val ib = context.irBuiltIns

        with(functionGenerationContext) {
            val functionSymbol = function.symbol
            return when (functionSymbol) {
                ib.eqeqeqSymbol -> icmpEq(args[0], args[1])
                ib.booleanNotSymbol -> icmpNe(args[0], kTrue)
                else -> {
                    val isFloatingPoint = args[0].type.isFloatingPoint()
                    // LLVM does not distinguish between signed/unsigned integers, so we must check
                    // the parameter type.
                    val shouldUseUnsignedComparison = function.valueParameters[0].type.isChar()
                    when {
                        functionSymbol.isComparisonFunction(ib.greaterFunByOperandType) -> {
                            when {
                                isFloatingPoint -> fcmpGt(args[0], args[1])
                                shouldUseUnsignedComparison -> icmpUGt(args[0], args[1])
                                else -> icmpGt(args[0], args[1])
                            }
                        }
                        functionSymbol.isComparisonFunction(ib.greaterOrEqualFunByOperandType) -> {
                            when {
                                isFloatingPoint -> fcmpGe(args[0], args[1])
                                shouldUseUnsignedComparison -> icmpUGe(args[0], args[1])
                                else -> icmpGe(args[0], args[1])
                            }
                        }
                        functionSymbol.isComparisonFunction(ib.lessFunByOperandType) -> {
                            when {
                                isFloatingPoint -> fcmpLt(args[0], args[1])
                                shouldUseUnsignedComparison -> icmpULt(args[0], args[1])
                                else -> icmpLt(args[0], args[1])
                            }
                        }
                        functionSymbol.isComparisonFunction(ib.lessOrEqualFunByOperandType) -> {
                            when {
                                isFloatingPoint -> fcmpLe(args[0], args[1])
                                shouldUseUnsignedComparison -> icmpULe(args[0], args[1])
                                else -> icmpLe(args[0], args[1])
                            }
                        }
                        functionSymbol == context.irBuiltIns.illegalArgumentExceptionSymbol -> {
                            callDirect(
                                    context.ir.symbols.throwIllegalArgumentExceptionWithMessage.owner,
                                    args,
                                    Lifetime.GLOBAL,
                                    null
                            )
                        }
                        else -> TODO(function.name.toString())
                    }
                }
            }
        }
    }

    //-------------------------------------------------------------------------//

    fun callDirect(
            function: IrFunction,
            args: List<LLVMValueRef>,
            resultLifetime: Lifetime,
            resultSlot: LLVMValueRef?,
            verifiedCoroutineResultSlotForwarding: Boolean = false,
            verifiedReturnedReceiverBorrow: Boolean = false,
            verifiedLockedReadResultSlotForwarding: ArcLockedReadCanonicalPlan? = null,
            verifiedSelectiveStringAppendInline: Boolean = false,
    ): LLVMValueRef {
        if (verifiedReturnedReceiverBorrow) {
            require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                    !context.shouldContainDebugInfo() && resultSlot != null && args.isNotEmpty()) {
                "ARC returned-receiver borrow escaped its codegen authorization boundary"
            }
        }
        val functionDeclarations = codegen.llvmFunction(function.target)
        return call(
            function, functionDeclarations, args, resultLifetime, resultSlot,
            verifiedSelectiveStringAppendInline,
        ).also { result ->
            if (verifiedLockedReadResultSlotForwarding != null) {
                val canonical = verifiedLockedReadResultSlotForwarding
                require(context.memoryModel == MemoryModel.ARC && context.config.optimizationsEnabled &&
                        !context.shouldContainDebugInfo() && resultSlot != null &&
                        resultSlot == functionGenerationContext.returnSlot &&
                        functionGenerationContext.irFunction === canonical.getter &&
                        function === canonical.runtimeGetter &&
                        function.parent === canonical.ownerClass &&
                        canonical.getter.correspondingPropertySymbol?.owner?.parent === canonical.ownerClass &&
                        canonical.stdlibLibrary === context.stdlibModule.konanLibrary &&
                        canonical.ownerClass.konanLibrary === canonical.stdlibLibrary &&
                        canonical.getter.konanLibrary === canonical.stdlibLibrary &&
                        canonical.runtimeGetter.konanLibrary === canonical.stdlibLibrary &&
                        canonical.ownerClass.fqNameForIrSerialization.asString() in setOf(
                            "kotlin.native.concurrent.FreezableAtomicReference",
                            "kotlin.concurrent.AtomicReference",
                        ) && canonical.ownerClass.symbol.signature == canonical.ownerClassSignature &&
                        (canonical.getter.symbol.signature ?: canonical.getter.symbol.privateSignature) == canonical.getterSignature &&
                        (function.symbol.signature ?: function.symbol.privateSignature) == canonical.runtimeGetterSignature) {
                    "ARC locked-read forwarding escaped its exact runtime result ABI"
                }
                functionGenerationContext.markArcResultOwnedBySlot(result, resultSlot)
            }
            val exactCoroutineResultSlotIdentity =
                    resultSlot != null && resultSlot == functionGenerationContext.returnSlot
            val eligibility = org.jetbrains.kotlin.backend.konan.arc.ArcResultSlotForwardingEligibility(
                    arcEnabled = context.memoryModel == MemoryModel.ARC,
                    debugInfoDisabled = !context.shouldContainDebugInfo(),
                    explicitResultSlot = resultSlot != null,
                    directKotlinCall = true,
                    nonExternalCall = !function.isExternal && !function.isBuiltInOperator,
                    nonSuspendCall = !function.isSuspend &&
                            (function as? IrSimpleFunction)?.isArcSuspendLike() != true,
                    referenceResult = function.returnType.binaryTypeIsReference(),
                    nonUnitResult = !function.returnType.isUnit(),
                    nonNothingResult = !function.returnType.isNothing(),
            )
            val coroutineEligibility = org.jetbrains.kotlin.backend.konan.arc.ArcCoroutineResultSlotForwardingEligibility(
                    arcEnabled = context.memoryModel == MemoryModel.ARC,
                    optimizationsEnabled = context.config.optimizationsEnabled,
                    debugInfoDisabled = !context.shouldContainDebugInfo(),
                    exactCallIdentitySelected = verifiedCoroutineResultSlotForwarding,
                    explicitResultSlot = resultSlot != null,
                    exactResultSlotIdentity = exactCoroutineResultSlotIdentity,
                    directKotlinCall = true,
                    nonExternalCall = !function.isExternal && !function.isBuiltInOperator,
                    nonVirtualCall = true,
                    ownedResultConvention = !function.isExternal && !function.isBuiltInOperator &&
                            function.returnType.binaryTypeIsReference(),
                    referenceResult = function.returnType.binaryTypeIsReference(),
                    nonUnitResult = !function.returnType.isUnit(),
                    nonNothingResult = !function.returnType.isNothing(),
                    allNormalReturnsInitializeSlot = verifiedCoroutineResultSlotForwarding,
                    noDifferentSlotOrSuspendBoundaryWidening = verifiedCoroutineResultSlotForwarding &&
                            exactCoroutineResultSlotIdentity,
                    normalSuccessEdgeOnly = true,
            )
            if (eligibility.isAuthorized() || coroutineEligibility.isAuthorized()) {
                functionGenerationContext.markArcResultOwnedBySlot(result, resultSlot)
            }
        }
    }

    //-------------------------------------------------------------------------//

    fun callVirtual(function: IrFunction, args: List<LLVMValueRef>, resultLifetime: Lifetime, resultSlot: LLVMValueRef?): LLVMValueRef {
        val functionDeclarations = functionGenerationContext.lookupVirtualImpl(args.first(), function)
        return call(function, functionDeclarations, args, resultLifetime, resultSlot)
    }

    //-------------------------------------------------------------------------//

    private val IrFunction.needsNativeThreadState: Boolean
        get() {
            // We assume that call site thread state switching is required for interop calls only.
            val kotlinToCBridge = context.memoryModel.usesThreadState &&
                    origin == CBridgeOrigin.KOTLIN_TO_C_BRIDGE
            if (kotlinToCBridge) {
                check(isExternal)
                check(!annotations.hasAnnotation(KonanFqNames.gcUnsafeCall))
                check(annotations.hasAnnotation(RuntimeNames.filterExceptions))
            }
            val canElideForNoCallbackArcBridge = kotlinToCBridge &&
                    annotations.hasAnnotation(RuntimeNames.cCallNoCallback) &&
                    context.memoryModel == MemoryModel.ARC &&
                    context.config.target == KonanTarget.LINUX_X64 &&
                    context.config.optimizationsEnabled &&
                    !context.shouldContainDebugInfo() &&
                    !context.config.arcDiagnosticsEnabled
            return kotlinToCBridge && !canElideForNoCallbackArcBridge
        }

    private fun call(function: IrFunction, llvmCallable: LlvmCallable, args: List<LLVMValueRef>,
                     resultLifetime: Lifetime, resultSlot: LLVMValueRef?,
                     verifiedSelectiveStringAppendInline: Boolean = false): LLVMValueRef {
        check(!function.isTypedIntrinsic)

        val needsNativeThreadState = function.needsNativeThreadState
        val exceptionHandler = function.annotations.findAnnotation(RuntimeNames.filterExceptions)?.let {
            val foreignExceptionMode = ForeignExceptionMode.byValue(it.getAnnotationValueOrNull<String>("mode"))
            functionGenerationContext.filteringExceptionHandler(
                    currentCodeContext.exceptionHandler,
                    foreignExceptionMode,
                    needsNativeThreadState
            )
        } ?: currentCodeContext.exceptionHandler

        if (needsNativeThreadState) {
            functionGenerationContext.switchThreadState(ThreadState.Native)
        }

        val result = call(
            llvmCallable, args, resultLifetime, exceptionHandler, resultSlot,
            if (verifiedSelectiveStringAppendInline) ARC_SELECTIVE_INLINE_METADATA else null,
        )

        when  {
            function.returnType.isNothing() -> functionGenerationContext.unreachable()
            needsNativeThreadState -> functionGenerationContext.switchThreadState(ThreadState.Runnable)
        }

        if (llvmCallable.returnType == llvm.voidType) {
            return codegen.theUnitInstanceRef.llvm
        }

        return result
    }

    private fun call(
            function: LlvmCallable, args: List<LLVMValueRef>,
            resultLifetime: Lifetime = Lifetime.IRRELEVANT,
            exceptionHandler: ExceptionHandler = currentCodeContext.exceptionHandler,
            resultSlot: LLVMValueRef? = null,
            instructionMetadata: String? = null,
    ): LLVMValueRef {
        return functionGenerationContext.call(
            function, args, resultLifetime, exceptionHandler,
            resultSlot = resultSlot,
            instructionMetadata = instructionMetadata,
        )
    }

    //-------------------------------------------------------------------------//

    private fun delegatingConstructorCall(constructor: IrConstructor, args: List<LLVMValueRef>): LLVMValueRef {

        val constructedClass = functionGenerationContext.constructedClass!!
        val thisPtr = currentCodeContext.genGetValue(constructedClass.thisReceiver!!, null)

        if (constructor.constructedClass.isExternalObjCClass() || constructor.constructedClass.isAny()) {
            assert(args.isEmpty())
            return codegen.theUnitInstanceRef.llvm
        }

        val thisPtrArgType = constructor.allParameters[0].type.toLLVMType(llvm)
        val thisPtrArg = if (thisPtr.type == thisPtrArgType) {
            thisPtr
        } else {
            // e.g. when array constructor calls super (i.e. Any) constructor.
            functionGenerationContext.bitcast(thisPtrArgType, thisPtr)
        }

        val result = callDirect(constructor, listOf(thisPtrArg) + args,
                Lifetime.IRRELEVANT /* no value returned */, null)
        markArcDeinitInitialized(constructor, thisPtrArg)
        return result
    }

    //-------------------------------------------------------------------------//

    private fun appendLlvmUsed(name: String, args: List<LLVMValueRef>) {
        if (args.isEmpty()) return

        val argsCasted = args.map { constPointer(it).bitcast(llvm.int8PtrType) }
        val llvmUsedGlobal = codegen.staticData.placeGlobalArray(name, llvm.int8PtrType, argsCasted)

        LLVMSetLinkage(llvmUsedGlobal.llvmGlobal, LLVMLinkage.LLVMAppendingLinkage)
        LLVMSetSection(llvmUsedGlobal.llvmGlobal, "llvm.metadata")
    }

    // Globals set this way cannot be const, but are overridable when producing final executable.
    private fun overrideRuntimeGlobal(name: String, value: ConstValue) =
            codegen.replaceExternalWeakOrCommonGlobalFromNativeRuntime(name, value)

    private fun overrideRuntimeGlobals() {
        if (!context.config.isFinalBinary)
            return

        overrideRuntimeGlobal("Kotlin_destroyRuntimeMode", llvm.constInt32(context.config.destroyRuntimeMode.value))
        overrideRuntimeGlobal("Kotlin_gcMarkSingleThreaded", llvm.constInt32(if (context.config.gcMarkSingleThreaded) 1 else 0))
        overrideRuntimeGlobal("Kotlin_workerExceptionHandling", llvm.constInt32(context.config.workerExceptionHandling.value))
        overrideRuntimeGlobal("Kotlin_suspendFunctionsFromAnyThreadFromObjC", llvm.constInt32(if (context.config.suspendFunctionsFromAnyThreadFromObjC) 1 else 0))
        val getSourceInfoFunctionName = when (context.config.sourceInfoType) {
            SourceInfoType.NOOP -> null
            SourceInfoType.LIBBACKTRACE -> "Kotlin_getSourceInfo_libbacktrace"
            SourceInfoType.CORESYMBOLICATION -> "Kotlin_getSourceInfo_core_symbolication"
        }
        if (getSourceInfoFunctionName != null) {
            val getSourceInfoFunction = LLVMGetNamedFunction(llvm.module, getSourceInfoFunctionName)
                    ?: LLVMAddFunction(llvm.module, getSourceInfoFunctionName,
                            functionType(llvm.int32Type, false, llvm.int8PtrType, llvm.int8PtrType, llvm.int32Type))
            overrideRuntimeGlobal("Kotlin_getSourceInfo_Function", constValue(getSourceInfoFunction!!))
        }
        if (context.config.target.family == Family.ANDROID && context.config.produce == CompilerOutputKind.PROGRAM) {
            val configuration = context.config.configuration
            val programType = configuration.get(BinaryOptions.androidProgramType) ?: AndroidProgramType.Default
            overrideRuntimeGlobal("Kotlin_printToAndroidLogcat", llvm.constInt32(if (programType.consolePrintsToLogcat) 1 else 0))
        }
        overrideRuntimeGlobal("Kotlin_appStateTracking", llvm.constInt32(context.config.appStateTracking.value))
        overrideRuntimeGlobal("Kotlin_mimallocUseDefaultOptions", llvm.constInt32(if (context.config.mimallocUseDefaultOptions) 1 else 0))
        overrideRuntimeGlobal("Kotlin_mimallocUseCompaction", llvm.constInt32(if (context.config.mimallocUseCompaction) 1 else 0))
        overrideRuntimeGlobal("Kotlin_objcDisposeOnMain", llvm.constInt32(if (context.config.objcDisposeOnMain) 1 else 0))
    }

    //-------------------------------------------------------------------------//
    // Create type { i32, void ()*, i8* }

    val kCtorType = llvm.structType(llvm.int32Type, pointerType(ctorFunctionSignature.llvmFunctionType), llvm.int8PtrType)

    //-------------------------------------------------------------------------//
    // Create object { i32, void ()*, i8* } { i32 1, void ()* @ctorFunction, i8* null }

    fun createGlobalCtor(ctorFunction: LlvmCallable): ConstPointer {
        val priority = if (context.config.target.family == Family.MINGW) {
            // Workaround MinGW bug. Using this value makes the compiler generate
            // '.ctors' section instead of '.ctors.XXXXX', which can't be recognized by ld
            // when string table is too long.
            // More details: https://youtrack.jetbrains.com/issue/KT-39548
            llvm.int32(65535)
            // Note: this difference in priorities doesn't actually make initializers
            // platform-dependent, because handling priorities for initializers
            // from different object files is platform-dependent anyway.
        } else {
            llvm.kImmInt32One
        }
        val data = llvm.kNullInt8Ptr
        val argList = cValuesOf(priority, ctorFunction.toConstPointer().llvm, data)
        val ctorItem = LLVMConstNamedStruct(kCtorType, argList, 3)!!
        return constPointer(ctorItem)
    }

    //-------------------------------------------------------------------------//
    fun appendStaticInitializers() {
        // Note: the list of libraries is topologically sorted (in order for initializers to be called correctly).
        val dependencies = (generationState.dependenciesTracker.allBitcodeDependencies + listOf(null)/* Null for "current" non-library module */)

        val libraryToInitializers = dependencies.associate { it?.library to mutableListOf<LlvmCallable>() }

        llvm.irStaticInitializers.forEach {
            val library = it.konanLibrary
            val initializers = libraryToInitializers[library]
                    ?: error("initializer for not included library ${library?.libraryFile}")

            initializers.add(it.initializer)
        }

        fun fileCtorName(libraryName: String, fileName: String) = "$libraryName:$fileName".moduleConstructorName

        fun ctorProto(ctorName: String): LlvmFunctionProto {
            return ctorFunctionSignature.toProto(ctorName, null, LLVMLinkage.LLVMExternalLinkage)
        }

        val ctorFunctions = dependencies.flatMap { dependency ->
            val library = dependency?.library
            val initializers = libraryToInitializers.getValue(library)

            val ctorName = when {
                // TODO: Try to not use moduleId.
                library == null -> (if (context.config.produce.isCache) generationState.outputFiles.cacheFileName else context.config.moduleId).moduleConstructorName
                library == context.config.libraryToCache?.klib
                        && context.config.producePerFileCache ->
                    fileCtorName(library.uniqueName, generationState.outputFiles.perFileCacheFileName)
                else -> library.moduleConstructorName
            }

            if (library == null || generationState.llvmModuleSpecification.containsLibrary(library)) {
                val otherInitializers = llvm.otherStaticInitializers.takeIf { library == null }.orEmpty()

                listOf(
                    appendStaticInitializers(ctorProto(ctorName), initializers + otherInitializers)
                )
            } else {
                // A cached library.
                check(initializers.isEmpty()) {
                    "found initializer from ${library.libraryFile}, which is not included into compilation"
                }

                val cache = context.config.cachedLibraries.getLibraryCache(library)
                        ?: error("Library ${library.libraryFile} is expected to be cached")

                when (cache) {
                    is CachedLibraries.Cache.Monolithic -> listOf(ctorProto(ctorName))
                    is CachedLibraries.Cache.PerFile -> {
                        val files = when (dependency.kind) {
                            is DependenciesTracker.DependencyKind.WholeModule ->
                                context.irLinker.klibToModuleDeserializerMap[library]!!.sortedFileIds
                            is DependenciesTracker.DependencyKind.CertainFiles ->
                                dependency.kind.files
                        }
                        files.map { ctorProto(fileCtorName(library.uniqueName, it)) }
                    }
                }.map {
                    codegen.addFunction(it)
                }
            }
        }

        appendGlobalCtors(ctorFunctions)
    }

    private fun appendStaticInitializers(ctorCallableProto: LlvmFunctionProto, initializers: List<LlvmCallable>) : LlvmCallable {
        return generateFunctionNoRuntime(codegen, ctorCallableProto) {
            val initGuardName = function.name.orEmpty() + "_guard"
            val initGuard = LLVMAddGlobal(llvm.module, llvm.int32Type, initGuardName)
            LLVMSetInitializer(initGuard, llvm.kImmInt32Zero)
            LLVMSetLinkage(initGuard, LLVMLinkage.LLVMPrivateLinkage)
            val bbInited = basicBlock("inited", null)
            val bbNeedInit = basicBlock("need_init", null)


            val value = LLVMBuildLoad(builder, initGuard, "")!!
            condBr(icmpEq(value, llvm.kImmInt32Zero), bbNeedInit, bbInited)

            appendingTo(bbInited) {
                ret(null)
            }

            appendingTo(bbNeedInit) {
                LLVMBuildStore(builder, llvm.kImmInt32One, initGuard)

                // TODO: shall we put that into the try block?
                initializers.forEach {
                    call(it, emptyList(), Lifetime.IRRELEVANT,
                            exceptionHandler = ExceptionHandler.Caller, verbatim = true)
                }
                ret(null)
            }
        }
    }

    private fun appendGlobalCtors(ctorFunctions: List<LlvmCallable>) {
        if (context.config.isFinalBinary) {
            // Generate function calling all [ctorFunctions].
            val ctorProto = ctorFunctionSignature.toProto(
                    name = "_Konan_constructors",
                    origin = null,
                    linkage = if (context.config.produce == CompilerOutputKind.PROGRAM) LLVMLinkage.LLVMExternalLinkage else LLVMLinkage.LLVMPrivateLinkage
            )
            val globalCtorCallable = generateFunctionNoRuntime(codegen, ctorProto) {
                ctorFunctions.forEach {
                    call(it, emptyList(), Lifetime.IRRELEVANT,
                            exceptionHandler = ExceptionHandler.Caller, verbatim = true)
                }
                ret(null)
            }

            // Append initializers of global variables in "llvm.global_ctors" array.
            val globalCtors = codegen.staticData.placeGlobalArray("llvm.global_ctors", kCtorType,
                    listOf(createGlobalCtor(globalCtorCallable)))
            LLVMSetLinkage(globalCtors.llvmGlobal, LLVMLinkage.LLVMAppendingLinkage)
            if (context.config.produce == CompilerOutputKind.PROGRAM) {
                // Provide an optional handle for calling .ctors, if standard constructors mechanism
                // is not available on the platform (i.e. WASM, embedded).
                appendLlvmUsed("llvm.used", listOf(globalCtorCallable.toConstPointer().llvm))
            }
        }
    }

    //-------------------------------------------------------------------------//

    fun FunctionGenerationContext.basicBlock(name: String, locationInfo: LocationInfo?, code: () -> Unit) = functionGenerationContext.basicBlock(name, locationInfo).apply {
        appendingTo(this) {
            code()
        }
    }
}

private val thisName = Name.special("<this>")
private val underscoreThisName = Name.identifier("_this")
private val doubleUnderscoreThisName = Name.identifier("__this")

/**
 * HACK: this is workaround for GH-2316, to let IDE some how operate with this.
 * We're experiencing issue with libclang which is used as compiler of expression in lldb
 * for current state support Kotlin in lldb:
 *   1. <this> isn't accepted by libclang as valid variable name.
 *   2. this is reserved name and compiled in special way.
 */
private fun IrValueDeclaration.debugNameConversion(): Name {
    val name = descriptor.name
    if (name == thisName) {
        return when (origin) {
            IrDeclarationOrigin.IR_TEMPORARY_VARIABLE_FOR_INLINED_EXTENSION_RECEIVER -> doubleUnderscoreThisName
            else -> underscoreThisName
        }
    }
    return name
}

internal class LocationInfo(val scope: DIScopeOpaqueRef,
                            val line: Int,
                            val column: Int,
                            val inlinedAt: LocationInfo? = null)

internal fun NativeGenerationState.generateRuntimeConstantsModule() : LLVMModuleRef {
    val llvmModule = LLVMModuleCreateWithNameInContext("constants", llvmContext)!!
    LLVMSetDataLayout(llvmModule, runtime.dataLayout)
    val static = StaticData(llvmModule, llvm)

    fun setRuntimeConstGlobal(name: String, value: ConstValue) {
        val global = static.placeGlobal(name, value)
        global.setConstant(true)
        global.setLinkage(LLVMLinkage.LLVMExternalLinkage)
    }

    setRuntimeConstGlobal("Kotlin_needDebugInfo", llvm.constInt32(if (shouldContainDebugInfo()) 1 else 0))
    setRuntimeConstGlobal("Kotlin_runtimeAssertsMode", llvm.constInt32(config.runtimeAssertsMode.value))
    val runtimeLogs = config.runtimeLogs?.let {
        static.cStringLiteral(it)
    } ?: NullPointer(llvm.int8Type)
    setRuntimeConstGlobal("Kotlin_runtimeLogs", runtimeLogs)
    setRuntimeConstGlobal("Kotlin_freezingEnabled", llvm.constInt32(if (config.freezing.enableFreezeAtRuntime) 1 else 0))
    setRuntimeConstGlobal("Kotlin_freezingChecksEnabled", llvm.constInt32(if (config.freezing.enableFreezeChecks) 1 else 0))
    setRuntimeConstGlobal("Kotlin_gcSchedulerType", llvm.constInt32(config.gcSchedulerType.value))
    setRuntimeConstGlobal("Kotlin_arcLeakCheck", llvm.constInt32(config.arcLeakCheck.ordinal))

    return llvmModule
}
