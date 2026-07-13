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
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.isOverridable
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Opaque lowered-IR identities needed by the future real walker. Keeping this adapter independent
 * of Kotlin IR lets the structural contract and its rejection matrix freeze before integration.
 */
internal data class ArcImmortalCompletionContextIRBindings<T : Any>(
    val ownerClass: T,
    val allocation: T,
    val constructor: T,
    val constructedReceiver: T,
    val contextProperty: T,
    val contextField: T,
    val initializerStore: T,
    val initializerRootLoad: T,
    val contextGetter: T,
    val getterReturn: T,
    val getterFieldLoad: T,
    val continuationClass: T,
    val continuationContextProperty: T,
    val continuationContextGetter: T,
    val emptyContextClass: T,
    val emptyContextProperty: T,
    val emptyContextGetter: T,
    val permanentRootField: T,
    val permanentRootReturn: T,
    val permanentRootLoad: T,
    val constantObject: T,
    val constantObjectConstructor: T,
)

internal fun <T : Any> ArcImmortalCompletionContextIRBindings<T>.exactIdentityInventory(): List<T> = listOf(
    ownerClass,
    allocation,
    constructor,
    constructedReceiver,
    contextProperty,
    contextField,
    initializerStore,
    initializerRootLoad,
    contextGetter,
    getterReturn,
    getterFieldLoad,
    continuationClass,
    continuationContextProperty,
    continuationContextGetter,
    emptyContextClass,
    emptyContextProperty,
    emptyContextGetter,
    permanentRootField,
    permanentRootReturn,
    permanentRootLoad,
    constantObject,
    constantObjectConstructor,
)

/** Facts that a complete module-wide lowered-IR walk must authenticate. */
internal data class ArcImmortalCompletionContextIRShape(
    val exactUserCompletionAllocationAndConstructor: Boolean,
    val exactContinuationContextOverrideChain: Boolean,
    val finalOwnerWithNoSubclasses: Boolean,
    val immutableFinalPropertyAndGetter: Boolean,
    val privateFinalStrongBackingField: Boolean,
    val exactFreshReceiverFieldStore: Boolean,
    val fieldUninitializedAtStore: Boolean,
    val initializerStoreDominatesEscapesAndSuccess: Boolean,
    val constructorHasNoPreStoreExceptionalEdge: Boolean,
    val exactSingleFieldGetterReturn: Boolean,
    val exactEmptyCoroutineContextRoot: Boolean,
    val exactConstantObjectInitializer: Boolean,
    val permanentTagOneRuntimeContract: Boolean,
    val exactlyOneFieldWriteInModule: Boolean,
    val noRootWritesInModule: Boolean,
    val noSubclassOverrideOrFieldAddressEscape: Boolean,
    val completeLoweredIRWalk: Boolean,
)

private fun ArcImmortalCompletionContextIRShape.isExact(): Boolean =
    exactUserCompletionAllocationAndConstructor && exactContinuationContextOverrideChain &&
            finalOwnerWithNoSubclasses && immutableFinalPropertyAndGetter &&
            privateFinalStrongBackingField && exactFreshReceiverFieldStore &&
            fieldUninitializedAtStore && initializerStoreDominatesEscapesAndSuccess &&
            constructorHasNoPreStoreExceptionalEdge && exactSingleFieldGetterReturn &&
            exactEmptyCoroutineContextRoot && exactConstantObjectInitializer &&
            permanentTagOneRuntimeContract && exactlyOneFieldWriteInModule &&
            noRootWritesInModule && noSubclassOverrideOrFieldAddressEscape && completeLoweredIRWalk

internal data class ArcImmortalCompletionContextIRSelection<T : Any>(
    val bindings: ArcImmortalCompletionContextIRBindings<T>,
    val ownership: ArcImmortalCompletionContextSelection<T>,
)

internal enum class ArcImmortalCompletionContextIRRejectionReason {
    InvalidStructuralProof,
    DuplicateStructuralIdentity,
    OwnershipAnalysisRejected,
}

internal data class ArcImmortalCompletionContextIRSelectorResult<T : Any>(
    val selection: ArcImmortalCompletionContextIRSelection<T>?,
    val rejection: ArcImmortalCompletionContextIRRejectionReason?,
    val ownershipRejection: ArcImmortalCompletionContextRejectionReason? = null,
)

/** Adapt an authenticated real-IR inventory into the semantic ownership proof. */
internal fun <T : Any> adaptVerifiedImmortalCompletionContextIR(
    bindings: ArcImmortalCompletionContextIRBindings<T>,
    mode: ArcImmortalCompletionContextMode,
    shape: ArcImmortalCompletionContextIRShape,
): ArcImmortalCompletionContextIRSelectorResult<T> {
    if (!shape.isExact()) return ArcImmortalCompletionContextIRSelectorResult(
        null,
        ArcImmortalCompletionContextIRRejectionReason.InvalidStructuralProof,
    )
    val identities = bindings.exactIdentityInventory()
    if (identities.hasIdentityDuplicate()) return ArcImmortalCompletionContextIRSelectorResult(
        null,
        ArcImmortalCompletionContextIRRejectionReason.DuplicateStructuralIdentity,
    )

    val analyzed = ArcImmortalCompletionContextAnalysis.select(
        ArcImmortalCompletionContextCandidate(
            constructorBinding = bindings.constructor,
            fieldBinding = bindings.contextField,
            initializerBinding = bindings.initializerStore,
            getterBinding = bindings.contextGetter,
            fieldLoadBinding = bindings.getterFieldLoad,
            returnBinding = bindings.getterReturn,
            permanentRootBinding = bindings.permanentRootField,
            exactIdentityBindings = identities,
            mode = mode,
            root = ArcImmortalCompletionContextRootProof(
                exactCoroutineContextType = true,
                exactEmptyCoroutineContextObject = true,
                exactPrivateFinalStaticRoot = true,
                exactConstantObjectInitializer = true,
                exactPrivatePrimaryObjectConstructor = true,
                runtimeUsesPermanentTagOne = true,
                noRootWrites = true,
            ),
            field = ArcImmortalCompletionContextFieldProof(
                exactContinuationContextOverride = true,
                ownerIsExactUserCompletionClass = true,
                ownerClassFinal = true,
                noOwnerSubclasses = true,
                propertyIsImmutable = true,
                propertyEffectivelyFinal = true,
                getterEffectivelyFinal = true,
                noGetterOverrides = true,
                backingFieldPrivateFinalStrongReference = true,
                fieldAddressDoesNotEscape = true,
                exactlyOneFieldWrite = true,
                onlyWriteIsSelectedConstructorStore = true,
            ),
            initializer = ArcImmortalCompletionContextInitializerProof(
                exactConstructorAndAllocation = true,
                receiverIsFreshZeroedAllocation = true,
                receiverHasNotEscaped = true,
                fieldDefinitelyUninitialized = true,
                noFieldReadBeforeInitialization = true,
                valueIsDirectPermanentRootLoad = true,
                storeDominatesEveryReceiverEscape = true,
                storeOccursOnEverySuccessfulConstructorPath = true,
                noDelegatingConstructorDrift = true,
                noExceptionalEdgeBeforeOrAtStore = true,
                noCatchOrFinallyAroundStore = true,
            ),
            getter = ArcImmortalCompletionContextGetterProof(
                exactSingleReturnBody = true,
                returnTargetsExactGetter = true,
                valueIsDirectSelectedFieldLoad = true,
                receiverOnlyUsedForSelectedLoad = true,
                returnTypeMatchesFieldType = true,
                noMutableOrVolatileRead = true,
                noNestedDeclaration = true,
                noExceptionalEdge = true,
            ),
            effects = listOf(
                ArcImmortalCompletionContextEffect.FreshAllocation,
                ArcImmortalCompletionContextEffect.PermanentRootLoad,
                ArcImmortalCompletionContextEffect.FirstStrongFieldInitialization,
                ArcImmortalCompletionContextEffect.FinalStrongFieldLoad,
                ArcImmortalCompletionContextEffect.OwnedResultSlotPublication,
            ),
        ),
    )
    val ownership = analyzed.selection ?: return ArcImmortalCompletionContextIRSelectorResult(
        null,
        ArcImmortalCompletionContextIRRejectionReason.OwnershipAnalysisRejected,
        analyzed.rejection,
    )
    return ArcImmortalCompletionContextIRSelectorResult(
        ArcImmortalCompletionContextIRSelection(bindings, ownership),
        null,
    )
}

internal data class ArcImmortalCompletionContextKotlinIRSelection(
    val ownerClass: IrClass,
    val allocation: IrConstructorCall,
    val constructor: IrConstructor,
    val contextProperty: IrProperty,
    val contextField: IrField,
    val initializerStore: IrSetField,
    val contextGetter: IrSimpleFunction,
    val getterReturn: IrReturn,
    val getterFieldLoad: IrGetField,
    val selection: ArcImmortalCompletionContextIRSelection<IrElement>,
)

internal enum class ArcImmortalCompletionContextKotlinIRRejectionStage {
    UnsupportedMode,
    PermanentRoot,
    ContinuationDeclaration,
    OwnerClosure,
    PropertyAndGetter,
    ConstructorInitialization,
    ModuleCensus,
    StructuralAdapter,
}

internal data class ArcImmortalCompletionContextKotlinIRRejection(
    val stage: ArcImmortalCompletionContextKotlinIRRejectionStage,
    val ownerName: String?,
    val detail: String,
)

/**
 * Result returned to integration rather than silently broadening or dropping candidates. The first
 * rejection is diagnostic evidence only; unrelated constructors are filtered before candidacy.
 */
internal data class ArcImmortalCompletionContextKotlinIRSelectorResult(
    val selections: List<ArcImmortalCompletionContextKotlinIRSelection>,
    val firstRejection: ArcImmortalCompletionContextKotlinIRRejection?,
)

/**
 * Real lowered-IR walker for final user `Continuation` implementations whose immutable `context`
 * field is initialized directly from the authenticated permanent `EmptyCoroutineContext` root.
 * It is deliberately not wired into code generation here: integration consumes the returned exact
 * identities only after this selector's focused tests and real-module probe are green.
 */
internal fun selectVerifiedImmortalCompletionContexts(
    generationState: NativeGenerationState,
    module: IrModuleFragment,
): ArcImmortalCompletionContextKotlinIRSelectorResult {
    val context = generationState.context
    val config = context.config
    val mode = ArcImmortalCompletionContextMode(
        arcEnabled = context.memoryModel == MemoryModel.ARC,
        linuxX64 = config.target == KonanTarget.LINUX_X64,
        finalBinary = config.isFinalBinary,
        optimizationsEnabled = config.optimizationsEnabled,
        debugInfoDisabled = !context.shouldContainAnyDebugInfo(),
        diagnosticsDisabled = !config.arcDiagnosticsEnabled,
        sanitizerDisabled = config.sanitizer == null && !config.undefinedBehaviorSanitizer,
        coverageDisabled = !generationState.coverage.enabled,
    )
    if (!mode.isSupportedRealSelectorMode()) {
        return ArcImmortalCompletionContextKotlinIRSelectorResult(
            emptyList(),
            ArcImmortalCompletionContextKotlinIRRejection(
                ArcImmortalCompletionContextKotlinIRRejectionStage.UnsupportedMode,
                null,
                "requires optimized final Linux x64 ARC without debug, diagnostics, sanitizer or coverage",
            ),
        )
    }

    val permanent = selectVerifiedCoroutineEmptyContextImmortalReturn(generationState, module)
        ?: return ArcImmortalCompletionContextKotlinIRSelectorResult(
            emptyList(),
            ArcImmortalCompletionContextKotlinIRRejection(
                ArcImmortalCompletionContextKotlinIRRejectionStage.PermanentRoot,
                null,
                "the exact EmptyCoroutineContext IrConstantObject/tag-1 root proof was unavailable",
            ),
        )
    val rootBindings = permanent.selection.bindings
    val permanentRootField = permanent.rootField

    val continuationClass = context.ir.symbols.continuationClass.owner
    val continuationProperty = continuationClass.properties.singleOrNull { it.name.asString() == "context" }
        ?: return ArcImmortalCompletionContextKotlinIRSelectorResult(
            emptyList(),
            ArcImmortalCompletionContextKotlinIRRejection(
                ArcImmortalCompletionContextKotlinIRRejectionStage.ContinuationDeclaration,
                continuationClass.fqNameForIrSerialization.asString(),
                "exact Continuation.context property was unavailable",
            ),
        )
    // Property lowering can strip the property's getter while retaining the abstract symbol in an
    // override chain. The already authenticated stdlib selector owns that exact retained identity.
    val continuationGetter = rootBindings.continuationContextGetter as IrSimpleFunction
    val stdlib = context.stdlibModule.konanLibrary
    if (continuationClass.konanLibrary !== stdlib || continuationClass.kind != ClassKind.INTERFACE ||
        continuationClass.fqNameForIrSerialization.asString() != "kotlin.coroutines.Continuation" ||
        continuationProperty.parent !== continuationClass || continuationProperty.name.asString() != "context" ||
        continuationGetter.correspondingPropertySymbol?.owner !== continuationProperty ||
        continuationGetter.modality != Modality.ABSTRACT ||
        continuationGetter.dispatchReceiverParameter?.type?.classifierOrNull != continuationClass.symbol ||
        continuationGetter.valueParameters.isNotEmpty() || continuationGetter.typeParameters.isNotEmpty() ||
        !continuationGetter.returnType.binaryTypeIsReference()
    ) {
        return ArcImmortalCompletionContextKotlinIRSelectorResult(
            emptyList(),
            ArcImmortalCompletionContextKotlinIRRejection(
                ArcImmortalCompletionContextKotlinIRRejectionStage.ContinuationDeclaration,
                continuationClass.fqNameForIrSerialization.asString(),
                "Continuation.context declaration identity or lowered shape drifted",
            ),
        )
    }

    val classes = collectImmortalCompletionClasses(module)
    val subclasses = IdentityHashMap<IrClass, Int>()
    classes.forEach { candidate ->
        candidate.superTypes.mapNotNullTo(mutableListOf()) { it.getClass() }.forEach { parent ->
            subclasses[parent] = (subclasses[parent] ?: 0) + 1
        }
    }
    val getterOverrides = IdentityHashMap<IrSimpleFunction, Int>()
    classes.forEach { candidate ->
        candidate.declarations.filterIsInstance<IrSimpleFunction>().forEach { function ->
            function.overriddenSymbols.forEach { overridden ->
                getterOverrides[overridden.owner] = (getterOverrides[overridden.owner] ?: 0) + 1
            }
        }
    }

    val allocations = mutableListOf<IrConstructorCall>()
    module.files.forEach { file ->
        file.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
            override fun visitConstructorCall(expression: IrConstructorCall) {
                allocations += expression
                expression.acceptChildrenVoid(this)
            }
        })
    }
    val fieldWrites = IdentityHashMap<IrField, MutableList<IrSetField>>()
    module.files.forEach { file ->
        file.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
            override fun visitSetField(expression: IrSetField) {
                fieldWrites.getOrPut(expression.symbol.owner) { mutableListOf() } += expression
                expression.acceptChildrenVoid(this)
            }
        })
    }

    val selections = mutableListOf<ArcImmortalCompletionContextKotlinIRSelection>()
    var firstRejection: ArcImmortalCompletionContextKotlinIRRejection? = null
    fun reject(stage: ArcImmortalCompletionContextKotlinIRRejectionStage, owner: IrClass?, detail: String) {
        if (firstRejection == null) {
            firstRejection = ArcImmortalCompletionContextKotlinIRRejection(
                stage,
                owner?.fqNameForIrSerialization?.asString(),
                detail,
            )
        }
    }

    allocations.forEach candidateLoop@ { allocation ->
        val constructor = allocation.symbol.owner
        val owner = constructor.parent as? IrClass ?: return@candidateLoop
        // Ignore unrelated allocations before recording a rejection. Only direct Continuation
        // implementations with a context property enter the exact candidate set.
        if (owner.superTypes.none { it.getClass()?.symbol == continuationClass.symbol }) return@candidateLoop
        val property = owner.properties.singleOrNull { it.name.asString() == "context" }
        if (property == null) {
            reject(
                ArcImmortalCompletionContextKotlinIRRejectionStage.PropertyAndGetter,
                owner,
                "direct Continuation implementation has no unique context property",
            )
            return@candidateLoop
        }

        if (owner.kind != ClassKind.CLASS || owner.modality != Modality.FINAL || owner.konanLibrary != null ||
            owner.constructors.singleOrNull() !== constructor || (subclasses[owner] ?: 0) != 0
        ) {
            reject(
                ArcImmortalCompletionContextKotlinIRRejectionStage.OwnerClosure,
                owner,
                "owner is not an exact final current-module class with one constructor and no subclasses",
            )
            return@candidateLoop
        }
        val field = property.backingField
        val getter = property.getter
        if (property.isVar || field == null || getter == null || property.parent !== owner ||
            field.parent !== owner || getter.parent !== owner || field.correspondingPropertySymbol?.owner !== property ||
            field.visibility != DescriptorVisibilities.PRIVATE || !field.isFinal || field.isStatic ||
            !field.type.binaryTypeIsReference() || field.type.classifierOrNull != continuationGetter.returnType.classifierOrNull ||
            getter.isExternal || getter.isSuspend || getter.extensionReceiverParameter != null ||
            getter.valueParameters.isNotEmpty() || getter.typeParameters.isNotEmpty() ||
            getter.dispatchReceiverParameter?.type?.classifierOrNull != owner.symbol ||
            getter.returnType.classifierOrNull != field.type.classifierOrNull ||
            getter.overriddenSymbols.singleOrNull()?.owner !== continuationGetter || getterOverrides[getter] != null ||
            (getter.modality != Modality.FINAL && owner.modality != Modality.FINAL)
        ) {
            reject(
                ArcImmortalCompletionContextKotlinIRRejectionStage.PropertyAndGetter,
                owner,
                "context property/backing field/getter is mutable, overridable or not the exact interface override",
            )
            return@candidateLoop
        }
        val getterReturn = (getter.body as? IrBlockBody)?.statements?.singleOrNull() as? IrReturn
        val getterLoad = getterReturn?.value.unwrapSelectedFieldLoad()
        val ownerThis = owner.thisReceiver?.symbol
        if (getterReturn == null || getterReturn.returnTargetSymbol != getter.symbol || getterLoad == null ||
            getterLoad.symbol.owner !== field ||
            (getterLoad.receiver as? IrGetValue)?.symbol != ownerThis
        ) {
            reject(
                ArcImmortalCompletionContextKotlinIRRejectionStage.PropertyAndGetter,
                owner,
                "getter is not one direct return of the selected field from the exact receiver",
            )
            return@candidateLoop
        }

        val writes = fieldWrites[field].orEmpty()
        val initializerStore = writes.singleOrNull()
        val rootLoad = initializerStore?.value.unwrapSelectedFieldLoad()
        if (initializerStore == null || initializerStore.symbol.owner !== field ||
            (initializerStore.receiver as? IrGetValue)?.symbol != ownerThis ||
            rootLoad == null || rootLoad.symbol.owner !== permanentRootField || rootLoad.receiver != null
        ) {
            reject(
                ArcImmortalCompletionContextKotlinIRRejectionStage.ConstructorInitialization,
                owner,
                "field is not written exactly once from the permanent root on the fresh constructor receiver",
            )
            return@candidateLoop
        }
        val constructorBody = constructor.body as? IrBlockBody
        if (constructorBody == null || !constructorBody.provesSafeFirstInitialization(constructor, initializerStore)) {
            reject(
                ArcImmortalCompletionContextKotlinIRRejectionStage.ConstructorInitialization,
                owner,
                "constructor has delegation, control-flow, throwing-call, read-before-write or escape drift",
            )
            return@candidateLoop
        }
        if ((subclasses[owner] ?: 0) != 0 || getterOverrides[getter] != null ||
            fieldWrites[field].orEmpty().singleOrNull() !== initializerStore
        ) {
            reject(
                ArcImmortalCompletionContextKotlinIRRejectionStage.ModuleCensus,
                owner,
                "module-wide subclass/override/write census drifted",
            )
            return@candidateLoop
        }

        val bindings = ArcImmortalCompletionContextIRBindings<IrElement>(
            ownerClass = owner,
            allocation = allocation,
            constructor = constructor,
            constructedReceiver = initializerStore.receiver as IrGetValue,
            contextProperty = property,
            contextField = field,
            initializerStore = initializerStore,
            initializerRootLoad = rootLoad,
            contextGetter = getter,
            getterReturn = getterReturn,
            getterFieldLoad = getterLoad,
            continuationClass = continuationClass,
            continuationContextProperty = continuationProperty,
            continuationContextGetter = continuationGetter,
            emptyContextClass = permanent.emptyContextClass,
            emptyContextProperty = rootBindings.objectProperty,
            emptyContextGetter = permanent.objectGetter,
            permanentRootField = permanentRootField,
            permanentRootReturn = rootBindings.rootGetterReturn,
            permanentRootLoad = permanent.rootLoad,
            constantObject = permanent.constantObject,
            constantObjectConstructor = rootBindings.constantConstructor,
        )
        val adapted = adaptVerifiedImmortalCompletionContextIR(
            bindings,
            mode,
            ArcImmortalCompletionContextIRShape(
                exactUserCompletionAllocationAndConstructor = true,
                exactContinuationContextOverrideChain = true,
                finalOwnerWithNoSubclasses = true,
                immutableFinalPropertyAndGetter = true,
                privateFinalStrongBackingField = true,
                exactFreshReceiverFieldStore = true,
                fieldUninitializedAtStore = true,
                initializerStoreDominatesEscapesAndSuccess = true,
                constructorHasNoPreStoreExceptionalEdge = true,
                exactSingleFieldGetterReturn = true,
                exactEmptyCoroutineContextRoot = true,
                exactConstantObjectInitializer = true,
                permanentTagOneRuntimeContract = true,
                exactlyOneFieldWriteInModule = true,
                noRootWritesInModule = true,
                noSubclassOverrideOrFieldAddressEscape = true,
                completeLoweredIRWalk = true,
            ),
        )
        val selection = adapted.selection
        if (selection == null) {
            reject(
                ArcImmortalCompletionContextKotlinIRRejectionStage.StructuralAdapter,
                owner,
                "adapter rejected ${adapted.rejection}/${adapted.ownershipRejection}",
            )
            return@candidateLoop
        }
        selections += ArcImmortalCompletionContextKotlinIRSelection(
            owner,
            allocation,
            constructor,
            property,
            field,
            initializerStore,
            getter,
            getterReturn,
            getterLoad,
            selection,
        )
    }

    context.log {
        "ARC immortal completion-context propagation planned (not emitted): ${selections.size}; " +
                "firstRejection=${firstRejection?.stage}:${firstRejection?.detail}"
    }
    return ArcImmortalCompletionContextKotlinIRSelectorResult(selections, firstRejection)
}

private fun IrExpression?.unwrapSelectedFieldLoad(): IrGetField? = when (this) {
    is IrGetField -> this
    is IrTypeOperatorCall -> if (operator == IrTypeOperator.IMPLICIT_CAST) {
        argument.unwrapSelectedFieldLoad()
    } else {
        null
    }
    else -> null
}

/**
 * Seal a linear constructor prefix through the selected store. The one allowed call is an exact
 * non-throwing delegation to Any; every other call/control-flow/throw or read of the selected field
 * before the store rejects. Statements after the store may initialize other fields, but the fresh
 * receiver must not escape before the selected store.
 */
private fun IrBlockBody.provesSafeFirstInitialization(
    constructor: IrConstructor,
    selectedStore: IrSetField,
): Boolean {
    val storeIndex = statements.indexOfFirst { it === selectedStore || it.containsExactElement(selectedStore) }
    if (storeIndex < 0) return false
    val allowedReceiverUses = Collections.newSetFromMap(IdentityHashMap<IrGetValue, Boolean>())
    statements.subList(0, storeIndex + 1).forEach { statement ->
        statement.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
            override fun visitSetField(expression: IrSetField) {
                (expression.receiver as? IrGetValue)?.let { allowedReceiverUses += it }
                expression.acceptChildrenVoid(this)
            }
        })
    }
    var safe = true
    statements.subList(0, storeIndex + 1).forEach { statement ->
        statement.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
            override fun visitFunction(declaration: IrFunction) { safe = false }
            override fun visitTry(aTry: IrTry) { safe = false }
            override fun visitThrow(expression: IrThrow) { safe = false }
            override fun visitCall(expression: IrCall) {
                safe = false
                expression.acceptChildrenVoid(this)
            }
            override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall) {
                val delegated = expression.symbol.owner
                val delegatedOwner = delegated.parent as? IrClass
                if (delegatedOwner?.fqNameForIrSerialization?.asString() != "kotlin.Any" ||
                    expression.valueArgumentsCount != 0 || expression.typeArgumentsCount != 0
                ) safe = false
                expression.acceptChildrenVoid(this)
            }
            override fun visitGetField(expression: IrGetField) {
                if (expression.symbol.owner === selectedStore.symbol.owner && expression !== selectedStore.value) {
                    safe = false
                }
                expression.acceptChildrenVoid(this)
            }
            override fun visitGetValue(expression: IrGetValue) {
                val ownerThis = constructor.constructedClass.thisReceiver?.symbol
                if (expression.symbol == ownerThis && expression !in allowedReceiverUses) {
                    safe = false
                }
                expression.acceptChildrenVoid(this)
            }
        })
    }
    return safe
}

private fun IrElement.containsExactElement(target: IrElement): Boolean {
    var found = false
    acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            if (element === target) found = true else element.acceptChildrenVoid(this)
        }
    })
    return found
}

private fun collectImmortalCompletionClasses(module: IrModuleFragment): List<IrClass> = buildList {
    module.files.forEach { file ->
        file.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
            override fun visitClass(declaration: IrClass) {
                add(declaration)
                declaration.acceptChildrenVoid(this)
            }
        })
    }
}

private fun ArcImmortalCompletionContextMode.isSupportedRealSelectorMode(): Boolean =
    arcEnabled && linuxX64 && finalBinary && optimizationsEnabled && debugInfoDisabled &&
            diagnosticsDisabled && sanitizerDisabled && coverageDisabled

private fun <T : Any> List<T>.hasIdentityDuplicate(): Boolean {
    indices.forEach { left ->
        for (right in 0 until left) if (this[left] === this[right]) return true
    }
    return false
}
