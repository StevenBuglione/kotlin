/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.KonanFqNames
import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.backend.konan.ir.KonanNameConventions
import org.jetbrains.kotlin.backend.konan.ir.konanLibrary
import org.jetbrains.kotlin.backend.konan.isFinalBinary
import org.jetbrains.kotlin.backend.konan.lower.DECLARATION_ORIGIN_ENUM
import org.jetbrains.kotlin.backend.konan.lower.DECLARATION_ORIGIN_STATIC_GLOBAL_INITIALIZER
import org.jetbrains.kotlin.backend.konan.lower.ObjectClassLowering
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getArgumentsWithIr
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isOverridable
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.util.Collections
import java.util.IdentityHashMap

internal data class ArcCoroutineSuspendedBorrowIRBindings<T : Any>(
    val call: T,
    val comparison: T,
    val function: T,
    val propertyGetter: T,
    val propertyReturn: T,
    val initializerCall: T,
    val initializer: T,
    val enumGetterCall: T,
    val enumGetter: T,
    val enumClass: T,
    val valuesRoot: T,
    val enumGetterReturn: T,
    val rootRead: T,
    val arrayGet: T,
)

internal fun <T : Any> ArcCoroutineSuspendedBorrowIRBindings<T>.exactIdentityInventory(): List<T> = listOf(
    call, comparison, function, propertyGetter, propertyReturn, initializerCall,
    initializer, enumGetterCall, enumGetter, enumClass, valuesRoot,
    enumGetterReturn, rootRead, arrayGet,
)

internal data class ArcCoroutineSuspendedBorrowIRShape(
    val exactPropertyGetterBody: Boolean,
    val exactGlobalInitializer: Boolean,
    val exactEnumGetterZero: Boolean,
    val exactSharedImmutableRoot: Boolean,
    val exactRootedArrayProjection: Boolean,
    val restrictedRootWrites: Boolean,
    val onlyVerifiedRootUses: Boolean,
    val exactIdentityComparisonOperand: Boolean,
    val completeLoweredIRWalk: Boolean,
)

private fun ArcCoroutineSuspendedBorrowIRShape.isExact(): Boolean =
    exactPropertyGetterBody && exactGlobalInitializer && exactEnumGetterZero &&
            exactSharedImmutableRoot && exactRootedArrayProjection && restrictedRootWrites &&
            onlyVerifiedRootUses && exactIdentityComparisonOperand && completeLoweredIRWalk

internal data class ArcCoroutineSuspendedBorrowIRSelection<T : Any>(
    val bindings: ArcCoroutineSuspendedBorrowIRBindings<T>,
    val ownership: ArcCoroutineSuspendedBorrowSelection<T>,
)

internal enum class ArcCoroutineSuspendedBorrowIRRejectionReason {
    InvalidStructuralProof,
    DuplicateStructuralIdentity,
    OwnershipAnalysisRejected,
}

internal data class ArcCoroutineSuspendedBorrowIRSelectorResult<T : Any>(
    val selection: ArcCoroutineSuspendedBorrowIRSelection<T>?,
    val rejection: ArcCoroutineSuspendedBorrowIRRejectionReason?,
    val ownershipRejection: ArcCoroutineSuspendedBorrowRejectionReason? = null,
)

internal fun <T : Any> adaptVerifiedCoroutineSuspendedBorrowIR(
    bindings: ArcCoroutineSuspendedBorrowIRBindings<T>,
    mode: ArcCoroutineSuspendedBorrowMode,
    shape: ArcCoroutineSuspendedBorrowIRShape,
): ArcCoroutineSuspendedBorrowIRSelectorResult<T> {
    if (!shape.isExact()) return ArcCoroutineSuspendedBorrowIRSelectorResult(
        null, ArcCoroutineSuspendedBorrowIRRejectionReason.InvalidStructuralProof,
    )
    val identities = bindings.exactIdentityInventory()
    val unique = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    if (identities.any { !unique.add(it) }) return ArcCoroutineSuspendedBorrowIRSelectorResult(
        null, ArcCoroutineSuspendedBorrowIRRejectionReason.DuplicateStructuralIdentity,
    )
    val analyzed = ArcCoroutineSuspendedBorrowAnalysis.select(
        ArcCoroutineSuspendedBorrowCandidate(
            call = bindings.call,
            comparison = bindings.comparison,
            getter = bindings.propertyGetter,
            initializer = bindings.initializer,
            enumGetter = bindings.enumGetter,
            root = bindings.valuesRoot,
            rootRead = bindings.rootRead,
            exactIdentityBindings = identities,
            mode = mode,
            proof = ArcCoroutineSuspendedBorrowProof(
                exactStdlibGetter = true,
                exactGlobalInitializerCall = true,
                exactCoroutineSingletonsEnum = true,
                exactEnumGetterZero = true,
                exactSharedImmutableValuesRoot = true,
                exactBorrowedArrayProjection = true,
                rootWritesRestrictedToInitializer = true,
                immediateIdentityComparison = true,
                noOwnedResultSlot = true,
                noStoreReturnOrEscape = true,
            ),
        ),
    )
    val ownership = analyzed.selection ?: return ArcCoroutineSuspendedBorrowIRSelectorResult(
        null,
        ArcCoroutineSuspendedBorrowIRRejectionReason.OwnershipAnalysisRejected,
        analyzed.rejection,
    )
    return ArcCoroutineSuspendedBorrowIRSelectorResult(
        ArcCoroutineSuspendedBorrowIRSelection(bindings, ownership), null,
    )
}

internal data class ArcCoroutineSuspendedBorrowKotlinIRSelection(
    val function: IrFunction,
    val sites: List<ArcCoroutineSuspendedBorrowIRSelection<IrElement>>,
)

/**
 * Select only `COROUTINE_SUSPENDED` calls used as an immediate operand of the identity-equality
 * intrinsic. The public getter and every owned/store/return use keep the ordinary +1 ABI.
 */
internal fun selectVerifiedCoroutineSuspendedScopedBorrows(
    generationState: NativeGenerationState,
    module: IrModuleFragment,
): List<ArcCoroutineSuspendedBorrowKotlinIRSelection> {
    val context = generationState.context
    val config = context.config
    val mode = ArcCoroutineSuspendedBorrowMode(
        arcEnabled = context.memoryModel == MemoryModel.ARC,
        linuxX64 = config.target == KonanTarget.LINUX_X64,
        finalBinary = config.isFinalBinary,
        optimizationsEnabled = config.optimizationsEnabled,
        debugInfoDisabled = !context.shouldContainAnyDebugInfo(),
        diagnosticsDisabled = !config.arcDiagnosticsEnabled,
        sanitizerDisabled = config.sanitizer == null && !config.undefinedBehaviorSanitizer,
        coverageDisabled = !generationState.coverage.enabled,
    )
    if (!mode.arcEnabled || !mode.linuxX64 || !mode.finalBinary || !mode.optimizationsEnabled ||
        !mode.debugInfoDisabled || !mode.diagnosticsDisabled || !mode.sanitizerDisabled ||
        !mode.coverageDisabled
    ) return emptyList()
    val stdlib = context.stdlibModule.konanLibrary ?: return emptyList()
    val propertyGetter = context.ir.symbols.coroutineSuspendedGetter.owner
    val property = propertyGetter.correspondingPropertySymbol?.owner ?: return emptyList()
    if (propertyGetter.konanLibrary !== stdlib || property.konanLibrary !== stdlib ||
        property.name.asString() != "COROUTINE_SUSPENDED" || property.getter !== propertyGetter ||
        propertyGetter.visibility != DescriptorVisibilities.PUBLIC ||
        propertyGetter.modality != Modality.FINAL || propertyGetter.isExternal ||
        propertyGetter.isSuspend || propertyGetter.dispatchReceiverParameter != null ||
        propertyGetter.extensionReceiverParameter != null || propertyGetter.valueParameters.isNotEmpty() ||
        propertyGetter.typeParameters.isNotEmpty() || !propertyGetter.returnType.binaryTypeIsReference() ||
        propertyGetter.returnType.getClass()?.symbol != context.irBuiltIns.anyClass ||
        propertyGetter.symbol.signature == null
    ) return emptyList()

    val propertyReturn = (propertyGetter.body as? IrBlockBody)?.statements?.singleOrNull() as? IrReturn
        ?: return emptyList()
    if (propertyReturn.returnTargetSymbol != propertyGetter.symbol) return emptyList()
    val enumGetterCall = propertyReturn.value as? IrCall ?: return emptyList()
    val enumGetter = enumGetterCall.symbol.owner
    val enumClass = enumGetter.parent as? IrClass ?: return emptyList()
    val initializer = enumClass.declarations.filterIsInstance<IrSimpleFunction>().singleOrNull {
        it.origin == DECLARATION_ORIGIN_STATIC_GLOBAL_INITIALIZER
    } ?: return emptyList()
    if (initializer.origin != DECLARATION_ORIGIN_STATIC_GLOBAL_INITIALIZER ||
        initializer.parent !== enumClass ||
        initializer.returnType != context.irBuiltIns.unitType ||
        enumClass.konanLibrary !== stdlib ||
        enumClass.fqNameForIrSerialization.asString() !=
            "kotlin.coroutines.intrinsics.CoroutineSingletons" ||
        enumClass.kind != ClassKind.ENUM_CLASS || enumClass.modality != Modality.FINAL ||
        enumClass.symbol.signature == null ||
        enumGetter.konanLibrary !== stdlib || enumGetter.parent !== enumClass ||
        enumGetter.origin != DECLARATION_ORIGIN_ENUM || enumGetter.name.asString() != "\$getEnumAt" ||
        enumGetter.dispatchReceiverParameter != null || enumGetter.extensionReceiverParameter != null ||
        enumGetter.valueParameters.singleOrNull()?.type?.isInt() != true ||
        enumGetter.returnType.getClass()?.symbol != enumClass.symbol || enumGetter.isOverridable ||
        enumGetterCall.dispatchReceiver != null || enumGetterCall.extensionReceiver != null ||
        enumGetterCall.valueArgumentsCount != 1 || enumGetterCall.typeArgumentsCount != 0 ||
        enumGetterCall.type.getClass()?.symbol != enumClass.symbol ||
        (enumGetterCall.getValueArgument(0) as? IrConst<*>)?.value != 0
    ) return emptyList()

    val enumGetterStatements = (enumGetter.body as? IrBlockBody)?.statements ?: return emptyList()
    if (enumGetterStatements.size != 2) return emptyList()
    val initializerCall = enumGetterStatements[0] as? IrCall ?: return emptyList()
    val enumGetterReturn = enumGetterStatements[1] as? IrReturn ?: return emptyList()
    if (initializerCall.symbol.owner !== initializer || initializerCall.dispatchReceiver != null ||
        initializerCall.extensionReceiver != null || initializerCall.valueArgumentsCount != 0 ||
        initializerCall.typeArgumentsCount != 0 || initializerCall.type != context.irBuiltIns.unitType
    ) return emptyList()
    if (enumGetterReturn.returnTargetSymbol != enumGetter.symbol) return emptyList()
    val arrayGet = enumGetterReturn.value as? IrCall ?: return emptyList()
    val exactArrayGet = context.ir.symbols.array.owner.functions.singleOrNull {
        it.name == KonanNameConventions.getWithoutBoundCheck && it.valueParameters.size == 1 &&
                it.dispatchReceiverParameter?.type?.getClass()?.symbol == context.ir.symbols.array
    } ?: return emptyList()
    val rootRead = arrayGet.dispatchReceiver as? IrGetField ?: return emptyList()
    val root = rootRead.symbol.owner
    if (arrayGet.symbol.owner !== exactArrayGet || arrayGet.extensionReceiver != null ||
        arrayGet.valueArgumentsCount != 1 ||
        (arrayGet.getValueArgument(0) as? org.jetbrains.kotlin.ir.expressions.IrGetValue)?.symbol !=
            enumGetter.valueParameters.single().symbol ||
        !root.isStatic || !root.isFinal || root.visibility != DescriptorVisibilities.PRIVATE ||
        root.parent !== enumClass || root.origin != DECLARATION_ORIGIN_ENUM ||
        root.name.asString() != "\$VALUES" || !root.hasAnnotation(KonanFqNames.sharedImmutable) ||
        root.initializer == null || root.type.getClass()?.symbol != context.ir.symbols.array ||
        (root.type as? IrSimpleType)?.arguments?.singleOrNull()?.typeOrNull?.getClass()?.symbol != enumClass.symbol
    ) return emptyList()

    val initializerWrites = Collections.newSetFromMap(IdentityHashMap<IrSetField, Boolean>())
    root.initializer!!.acceptChildrenVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
        override fun visitSetField(expression: IrSetField) {
            if (expression.symbol.owner === root) initializerWrites += expression
            expression.acceptChildrenVoid(this)
        }
    })
    val allWrites = Collections.newSetFromMap(IdentityHashMap<IrSetField, Boolean>())
    module.files.forEach { file ->
        file.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
            override fun visitSetField(expression: IrSetField) {
                if (expression.symbol.owner === root) allWrites += expression
                expression.acceptChildrenVoid(this)
            }
        })
    }
    val exactValuesInitializer = hasExactCoroutineSingletonValuesInitializer(root, enumClass, exactArrayGet, context)
    val exactRootUses = hasOnlyVerifiedEnumRootUses(module, root, rootRead, exactArrayGet, context)
    if (initializerWrites.size != 1 || allWrites != initializerWrites ||
        !exactValuesInitializer || !exactRootUses
    ) return emptyList()

    // ArcOwnershipAnalysisPhase is directly adjacent to CodegenPhase. The emitter revalidates the
    // selected local structure; any future IR-mutating phase inserted between them must rerun this
    // module-wide root/use census or explicitly invalidate these selections.
    val sitesByFunction = IdentityHashMap<IrFunction, MutableList<ArcCoroutineSuspendedBorrowIRSelection<IrElement>>>()
    module.files.forEach { file ->
        file.acceptVoid(object : IrElementVisitorVoid {
            private var function: IrFunction? = null
            private var parent: IrElement? = null

            override fun visitElement(element: IrElement) {
                val previous = parent
                parent = element
                element.acceptChildrenVoid(this)
                parent = previous
            }

            override fun visitFunction(declaration: IrFunction) {
                val previousFunction = function
                val previousParent = parent
                function = declaration
                parent = declaration
                declaration.acceptChildrenVoid(this)
                parent = previousParent
                function = previousFunction
            }

            override fun visitCall(expression: IrCall) {
                val comparison = parent as? IrCall
                val ownerFunction = function
                if (expression.symbol.owner === propertyGetter && ownerFunction != null &&
                    expression.dispatchReceiver == null && expression.extensionReceiver == null &&
                    expression.superQualifierSymbol == null && expression.valueArgumentsCount == 0 &&
                    expression.typeArgumentsCount == 0 && expression.type == propertyGetter.returnType &&
                    comparison?.symbol == context.irBuiltIns.eqeqeqSymbol &&
                    comparison.type == context.irBuiltIns.booleanType &&
                    comparison.getArgumentsWithIr().count { it.second === expression } == 1
                ) {
                    val bindings = ArcCoroutineSuspendedBorrowIRBindings<IrElement>(
                        expression, comparison, ownerFunction, propertyGetter, propertyReturn,
                        initializerCall, initializer, enumGetterCall, enumGetter,
                        enumClass, root, enumGetterReturn, rootRead, arrayGet,
                    )
                    val adapted = adaptVerifiedCoroutineSuspendedBorrowIR(
                        bindings,
                        mode,
                        ArcCoroutineSuspendedBorrowIRShape(
                            exactPropertyGetterBody = true,
                            exactGlobalInitializer = true,
                            exactEnumGetterZero = true,
                            exactSharedImmutableRoot = true,
                            exactRootedArrayProjection = true,
                            restrictedRootWrites = true,
                            onlyVerifiedRootUses = true,
                            exactIdentityComparisonOperand = true,
                            completeLoweredIRWalk = true,
                        ),
                    )
                    adapted.selection?.let { sitesByFunction.getOrPut(ownerFunction) { mutableListOf() } += it }
                }
                visitElement(expression)
            }
        })
    }
    context.log { "ARC COROUTINE_SUSPENDED scoped identity borrows: ${sitesByFunction.values.sumOf { it.size }}" }
    return sitesByFunction.map { (function, sites) ->
        ArcCoroutineSuspendedBorrowKotlinIRSelection(function, sites)
    }
}

/**
 * Seal EnumClassLowering.defineValuesField for this exact three-entry enum. In particular, prove
 * the allocated mutable Array has no alias/use beyond initialization, publication to `$VALUES`,
 * constructor reads, and the initializer's terminal value.
 */
internal fun hasExactCoroutineSingletonValuesInitializer(
    root: IrField,
    enumClass: IrClass,
    exactArrayGet: IrSimpleFunction,
    context: org.jetbrains.kotlin.backend.konan.Context,
): Boolean {
    val block = root.initializer?.expression as? IrBlock ?: return false
    if (block.statements.size != 6 || block.type.getClass()?.symbol != context.ir.symbols.array) return false
    val instances = block.statements[0] as? IrVariable ?: return false
    val publish = block.statements[1] as? IrSetField ?: return false
    val constructorInitializers = block.statements.subList(2, 5).map { it as? IrCall ?: return false }
    val terminal = block.statements[5] as? IrGetValue ?: return false
    if (instances.isVar || instances.type != root.type || publish.symbol.owner !== root ||
        publish.receiver != null || publish.origin != ObjectClassLowering.IrStatementOriginFieldPreInit ||
        (publish.value as? IrGetValue)?.symbol != instances.symbol || terminal.symbol != instances.symbol
    ) return false

    val arrayOf = instances.initializer as? IrCall ?: return false
    if (arrayOf.symbol != context.irBuiltIns.arrayOf || arrayOf.dispatchReceiver != null ||
        arrayOf.extensionReceiver != null || arrayOf.typeArgumentsCount != 1 ||
        arrayOf.getTypeArgument(0)?.getClass()?.symbol != enumClass.symbol ||
        arrayOf.valueArgumentsCount != 1 || arrayOf.type != root.type
    ) return false
    val allocation = arrayOf.getValueArgument(0) as? IrBlock ?: return false
    if (allocation.statements.size != 8 || allocation.type != root.type) return false
    val entries = allocation.statements.take(3).map { it as? IrVariable ?: return false }
    val array = allocation.statements[3] as? IrVariable ?: return false
    val stores = allocation.statements.subList(4, 7).map { it as? IrCall ?: return false }
    val allocationResult = allocation.statements[7] as? IrGetValue ?: return false
    if (entries.any { entry ->
            entry.isVar || entry.type.getClass()?.symbol != enumClass.symbol ||
                    (entry.initializer as? IrCall)?.let { call ->
                        call.symbol == context.ir.symbols.createUninitializedInstance &&
                                call.dispatchReceiver == null && call.extensionReceiver == null &&
                                call.typeArgumentsCount == 1 &&
                                call.getTypeArgument(0)?.getClass()?.symbol == enumClass.symbol &&
                                call.valueArgumentsCount == 0 && call.type.getClass()?.symbol == enumClass.symbol
                    } != true
        }
    ) return false
    val arrayConstructor = array.initializer as? IrConstructorCall ?: return false
    // The lowering deliberately leaves this construction temporary as `Array<T of Array>`;
    // authenticate its concrete element through the constructor type argument and every store.
    if (array.isVar || array.type.getClass()?.symbol != context.ir.symbols.array ||
        arrayConstructor.symbol.owner.constructedClass.symbol != context.ir.symbols.array ||
        arrayConstructor.typeArgumentsCount != 1 ||
        arrayConstructor.getTypeArgument(0)?.getClass()?.symbol != enumClass.symbol ||
        arrayConstructor.valueArgumentsCount != 1 ||
        (arrayConstructor.getValueArgument(0) as? IrConst<*>)?.value != 3 ||
        allocationResult.symbol != array.symbol
    ) return false
    val exactArraySet = context.ir.symbols.array.owner.functions.singleOrNull {
        it.name == KonanNameConventions.setWithoutBoundCheck && it.valueParameters.size == 2 &&
                it.dispatchReceiverParameter?.type?.getClass()?.symbol == context.ir.symbols.array
    } ?: return false
    stores.forEachIndexed { index, store ->
        if (store.symbol.owner !== exactArraySet || store.extensionReceiver != null ||
            store.typeArgumentsCount != 0 || store.valueArgumentsCount != 2 ||
            (store.dispatchReceiver as? IrGetValue)?.symbol != array.symbol ||
            (store.getValueArgument(0) as? IrConst<*>)?.value != index ||
            (store.getValueArgument(1) as? IrGetValue)?.symbol != entries[index].symbol
        ) return false
    }

    val enumConstructor = enumClass.constructors.singleOrNull { it.isPrimary } ?: return false
    val expected = listOf(
        Triple(0, "COROUTINE_SUSPENDED", 0),
        Triple(2, "UNDECIDED", 1),
        Triple(1, "RESUMED", 2),
    )
    constructorInitializers.zip(expected).forEach { (init, expectedEntry) ->
        if (init.symbol != context.ir.symbols.initInstance || init.dispatchReceiver != null ||
            init.extensionReceiver != null || init.typeArgumentsCount != 0 || init.valueArgumentsCount != 2
        ) return false
        val entryRead = init.getValueArgument(0) as? IrCall ?: return false
        val constructor = init.getValueArgument(1) as? IrConstructorCall ?: return false
        if (entryRead.symbol.owner !== exactArrayGet || entryRead.extensionReceiver != null ||
            entryRead.typeArgumentsCount != 0 || entryRead.valueArgumentsCount != 1 ||
            (entryRead.dispatchReceiver as? IrGetValue)?.symbol != instances.symbol ||
            (entryRead.getValueArgument(0) as? IrConst<*>)?.value != expectedEntry.first ||
            constructor.symbol.owner !== enumConstructor || constructor.typeArgumentsCount != 0 ||
            constructor.valueArgumentsCount != 2 ||
            (constructor.getValueArgument(0) as? IrConst<*>)?.value != expectedEntry.second ||
            (constructor.getValueArgument(1) as? IrConst<*>)?.value != expectedEntry.third
        ) return false
    }

    // Complete alias census for the published Array and the temporary construction Array.
    val instanceReads = Collections.newSetFromMap(IdentityHashMap<IrGetValue, Boolean>())
    val arrayReads = Collections.newSetFromMap(IdentityHashMap<IrGetValue, Boolean>())
    val entryReads = entries.associateWith {
        Collections.newSetFromMap(IdentityHashMap<IrGetValue, Boolean>())
    }
    block.acceptChildrenVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
        override fun visitGetValue(expression: IrGetValue) {
            when (expression.symbol.owner) {
                instances -> instanceReads += expression
                array -> arrayReads += expression
                in entries -> entryReads.getValue(expression.symbol.owner as IrVariable) += expression
            }
            expression.acceptChildrenVoid(this)
        }
    })
    return instanceReads.size == 5 && arrayReads.size == 4 && entryReads.values.all { it.size == 1 }
}
