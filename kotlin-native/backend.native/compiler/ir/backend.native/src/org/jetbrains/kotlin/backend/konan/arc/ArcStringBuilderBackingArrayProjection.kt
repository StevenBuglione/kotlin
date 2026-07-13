/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.KonanFqNames
import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.NativeGenerationState
import org.jetbrains.kotlin.backend.konan.binaryTypeIsReference
import org.jetbrains.kotlin.backend.konan.ir.getAnnotationArgumentValue
import org.jetbrains.kotlin.backend.konan.ir.konanLibrary
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isCharArray
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isNullable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.getArgumentsWithIr
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isOverridable
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.name.Name
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Eligibility for the deliberately narrow StringBuilder backing-array projection.
 *
 * Swift's RC-identity optimizations strip ownership traffic only while a dominating owner proves
 * the complete projected lifetime. Here the guaranteed StringBuilder receiver owns `array` across
 * one exact runtime call. The borrow frontier is the call's normal or exceptional successor.
 */
internal data class ArcStringBuilderBackingArrayProjectionEligibility(
    val arcEnabled: Boolean,
    val linuxX64: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val diagnosticsDisabled: Boolean,
    val sanitizerDisabled: Boolean,
    val coverageDisabled: Boolean,
    val exactStdlibDeclarationIdentities: Boolean,
    val finalNonExternalNonSuspendMethod: Boolean,
    val guaranteedDispatchReceiver: Boolean,
    val privateStrongCharArrayField: Boolean,
    val exactGCUnsafeConsumer: Boolean,
    val fieldLoadIsFirstArgument: Boolean,
    val remainingArgumentsAreOwnershipEffectFree: Boolean,
    val exactlyOneProjectionConsumer: Boolean,
    val normalAndExceptionalLifetimeProofAccepted: Boolean,
)

internal fun ArcStringBuilderBackingArrayProjectionEligibility.isAuthorized(): Boolean =
    arcEnabled && linuxX64 && optimizationsEnabled && debugInfoDisabled && diagnosticsDisabled &&
            sanitizerDisabled && coverageDisabled && exactStdlibDeclarationIdentities &&
            finalNonExternalNonSuspendMethod && guaranteedDispatchReceiver &&
            privateStrongCharArrayField && exactGCUnsafeConsumer && fieldLoadIsFirstArgument &&
            remainingArgumentsAreOwnershipEffectFree && exactlyOneProjectionConsumer &&
            normalAndExceptionalLifetimeProofAccepted

internal data class ArcStringBuilderBackingArrayProjectionPlan(
    val function: IrSimpleFunction,
    val backingFieldLoad: IrGetField,
    val consumer: IrCall,
)

/** Exact identities carried to codegen so shape drift cannot silently broaden the optimization. */
internal class ArcStringBuilderBackingArrayProjectionConsumptionLedger(
    plan: ArcStringBuilderBackingArrayProjectionPlan,
) {
    private val expectedLoad = plan.backingFieldLoad
    private val expectedConsumer = plan.consumer
    private var consumed = false

    fun consume(load: IrGetField, consumer: IrCall) {
        check(!consumed) { "duplicate StringBuilder backing-array projection emission" }
        check(load === expectedLoad && consumer === expectedConsumer) {
            "StringBuilder backing-array projection emission identity drift"
        }
        consumed = true
    }

    fun verifyComplete() {
        check(consumed) { "selected StringBuilder backing-array projection was not emitted" }
    }
}

private data class ArcStringBuilderBackingArrayConsumer(
    val symbol: IrSimpleFunctionSymbol,
    val nativeSymbol: String,
    val authenticatedBoundary: Boolean,
)

/**
 * Selects only the 1.9.10 stdlib's three direct backing-array consumers:
 *
 *  * StringBuilder.append(String?) -> Kotlin_StringBuilder_insertString
 *  * StringBuilder.append(Int) -> Kotlin_StringBuilder_insertInt
 *  * StringBuilder.toString() -> Kotlin_String_unsafeStringFromCharArray
 *
 * The external calls cannot callback into Kotlin. A throwing edge still leaves the array owned by
 * the guaranteed receiver, so the projection needs neither an unwind retain nor an unwind release.
 */
internal fun selectVerifiedStringBuilderBackingArrayProjection(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
): ArcStringBuilderBackingArrayProjectionPlan? {
    val context = generationState.context
    val config = context.config
    val stdlib = context.stdlibModule.konanLibrary ?: return null
    val stringBuilder = context.ir.symbols.stringBuilder.owner
    if (stringBuilder.modality != Modality.FINAL || stringBuilder.konanLibrary !== stdlib) return null
    val exactMethod = resolveExactStringBuilderProjectionMethod(generationState, function) ?: return null
    val exactConsumer = resolveExactStringBuilderProjectionConsumer(generationState, function) ?: return null
    if (function !== exactMethod || function.konanLibrary !== stdlib) return null
    fun reject(reason: String): ArcStringBuilderBackingArrayProjectionPlan? {
        context.log {
            "ARC StringBuilder backing-array projection rejected " +
                    "${function.fqNameForIrSerialization.asString()}: $reason"
        }
        return null
    }
    val backingField = resolveCanonicalStringBuilderBackingField(generationState)
        ?: return reject("canonical three-method backing-field identity")
    val exactStringLengthGetter = resolveExactStringLengthGetter(generationState)

    var nestedDepth = 0
    var unsupportedBoundary = false
    var exactConsumerCallCount = 0
    val consumersByLoad = IdentityHashMap<IrGetField, IrCall>()
    function.body?.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

        override fun visitFunction(declaration: IrFunction) {
            if (declaration !== function) {
                nestedDepth++
                unsupportedBoundary = true
                nestedDepth--
            } else {
                declaration.acceptChildrenVoid(this)
            }
        }

        override fun visitCall(expression: IrCall) {
            if (nestedDepth != 0 || expression.symbol !== exactConsumer.symbol) {
                expression.acceptChildrenVoid(this)
                return
            }
            exactConsumerCallCount++
            val arguments = expression.getArgumentsWithIr()
            val fieldLoad = arguments.firstOrNull()?.second as? IrGetField
            val field = fieldLoad?.symbol?.owner
            if (field === backingField &&
                fieldLoad.receiver.usesExactDispatchReceiver(function) &&
                arguments.drop(1).all { (_, argument) ->
                    argument.isOwnershipEffectFreeProjectionSuffix(function, exactStringLengthGetter)
                }
            ) {
                consumersByLoad[fieldLoad] = expression
            }
            expression.acceptChildrenVoid(this)
        }
    })
    val selected = consumersByLoad.entries.singleOrNull()
        ?: return reject(
            "consumer shape exactCalls=$exactConsumerCallCount matchingLoads=${consumersByLoad.size} " +
                    "stringLengthGetter=${exactStringLengthGetter != null}",
        )
    val verifierAccepted = verifyStringBuilderBackingArrayProjectionLifetime()
    val field = selected.key.symbol.owner
    val consumer = selected.value.symbol.owner
    val eligibility = ArcStringBuilderBackingArrayProjectionEligibility(
        arcEnabled = context.memoryModel == MemoryModel.ARC,
        linuxX64 = config.target == KonanTarget.LINUX_X64,
        optimizationsEnabled = config.optimizationsEnabled,
        debugInfoDisabled = !context.shouldContainAnyDebugInfo(),
        diagnosticsDisabled = !config.arcDiagnosticsEnabled,
        sanitizerDisabled = config.sanitizer == null && !config.undefinedBehaviorSanitizer,
        coverageDisabled = !generationState.coverage.enabled,
        exactStdlibDeclarationIdentities = function.konanLibrary === stdlib &&
                field === backingField && field.parent === stringBuilder &&
                field.konanLibrary === stdlib && consumer.konanLibrary === stdlib,
        finalNonExternalNonSuspendMethod = !function.isOverridable && !function.isExternal &&
                !function.isArcSuspendLike() && !unsupportedBoundary,
        guaranteedDispatchReceiver = function.dispatchReceiverParameter?.type?.binaryTypeIsReference() == true,
        privateStrongCharArrayField = field.name.asString() == "array" && !field.isStatic &&
                field.visibility == DescriptorVisibilities.PRIVATE && field.type.isCharArray() &&
                !field.hasAnnotation(KonanFqNames.volatile) &&
                !field.hasAnnotation(KonanFqNames.arcWeak) &&
                !field.hasAnnotation(KonanFqNames.arcUnowned),
        exactGCUnsafeConsumer = exactConsumer.authenticatedBoundary,
        fieldLoadIsFirstArgument = selected.value.getArgumentsWithIr().firstOrNull()?.second === selected.key,
        remainingArgumentsAreOwnershipEffectFree = selected.value.getArgumentsWithIr().drop(1)
            .all { (_, argument) ->
                argument.isOwnershipEffectFreeProjectionSuffix(function, exactStringLengthGetter)
            },
        exactlyOneProjectionConsumer = exactConsumerCallCount == 1 && consumersByLoad.size == 1,
        normalAndExceptionalLifetimeProofAccepted = verifierAccepted,
    )
    if (!eligibility.isAuthorized()) return reject("authorization facts=$eligibility")
    return ArcStringBuilderBackingArrayProjectionPlan(function, selected.key, selected.value)
}

private fun resolveExactStringBuilderProjectionMethod(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
): IrSimpleFunction? {
    val stringBuilder = generationState.context.ir.symbols.stringBuilder.owner
    val string = generationState.context.ir.symbols.string
    return stringBuilder.functions.singleOrNull { candidate -> when {
        function.name.asString() == "append" && function.valueParameters.singleOrNull()?.type?.isInt() == true ->
            candidate.name.asString() == "append" && candidate.valueParameters.singleOrNull()?.type?.isInt() == true &&
                    candidate.returnType.classifierOrNull == stringBuilder.symbol
        function.name.asString() == "append" && function.valueParameters.singleOrNull()?.type?.classifierOrNull == string ->
            candidate.name.asString() == "append" &&
                    candidate.valueParameters.singleOrNull()?.type?.classifierOrNull == string &&
                    candidate.valueParameters.single().type.isNullable() &&
                    candidate.returnType.classifierOrNull == stringBuilder.symbol
        function.name.asString() == "toString" && function.valueParameters.isEmpty() ->
            candidate.name.asString() == "toString" && candidate.valueParameters.isEmpty() &&
                    candidate.returnType.classifierOrNull == string
        else -> false
    } }
}

private fun resolveExactStringBuilderProjectionConsumer(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
): ArcStringBuilderBackingArrayConsumer? {
    val (name, nativeSymbol) = when {
        function.name.asString() == "append" && function.valueParameters.singleOrNull()?.type?.isInt() == true ->
            "insertInt" to "Kotlin_StringBuilder_insertInt"
        function.name.asString() == "append" ->
            "insertString" to "Kotlin_StringBuilder_insertString"
        function.name.asString() == "toString" ->
            "unsafeStringFromCharArray" to "Kotlin_String_unsafeStringFromCharArray"
        else -> return null
    }
    val directSymbol = generationState.context.irBuiltIns
        .findFunctions(Name.identifier(name), "kotlin", "text")
        .singleOrNull { candidate ->
            if (!candidate.isBound) return@singleOrNull false
            val owner = candidate.owner
            owner.isExternal && !owner.isSuspend && owner.valueParameters.firstOrNull()?.type?.isCharArray() == true &&
                    owner.getAnnotationArgumentValue<String>(KonanFqNames.gcUnsafeCall, "callee") == nativeSymbol &&
                    when (name) {
                        "insertInt" -> owner.valueParameters.size == 3 && owner.valueParameters.drop(1).all { it.type.isInt() } &&
                                owner.returnType.isInt()
                        "insertString" -> owner.valueParameters.size == 5 && owner.valueParameters[1].type.isInt() &&
                                owner.valueParameters[2].type.classifierOrNull == generationState.context.ir.symbols.string &&
                                owner.valueParameters.drop(3).all { it.type.isInt() } && owner.returnType.isInt()
                        else -> owner.valueParameters.size == 3 && owner.valueParameters.drop(1).all { it.type.isInt() } &&
                                owner.returnType.classifierOrNull == generationState.context.ir.symbols.string
                    }
        } ?: return null
    if (name != "insertString") {
        return ArcStringBuilderBackingArrayConsumer(directSymbol, nativeSymbol, authenticatedBoundary = true)
    }
    val wrapper = resolveAuthenticatedInsertStringWrapper(generationState, function, directSymbol) ?: return null
    return ArcStringBuilderBackingArrayConsumer(wrapper, nativeSymbol, authenticatedBoundary = true)
}

/** Authenticate the stdlib's exact three-argument default wrapper around the five-argument FFI call. */
private fun resolveAuthenticatedInsertStringWrapper(
    generationState: NativeGenerationState,
    appendString: IrSimpleFunction,
    directExternal: IrSimpleFunctionSymbol,
): IrSimpleFunctionSymbol? {
    val context = generationState.context
    val stdlib = context.stdlibModule.konanLibrary ?: return null
    val string = context.ir.symbols.string
    val candidates = Collections.newSetFromMap(IdentityHashMap<IrSimpleFunctionSymbol, Boolean>())
    appendString.body?.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

        override fun visitFunction(declaration: IrFunction) {
            if (declaration === appendString) declaration.acceptChildrenVoid(this)
        }

        override fun visitCall(expression: IrCall) {
            val callee = expression.symbol.owner
            val arguments = expression.getArgumentsWithIr()
            if (callee.name.asString() == "insertString" && callee.konanLibrary === stdlib &&
                !callee.isExternal && !callee.isSuspend && !callee.isOverridable &&
                callee.dispatchReceiverParameter == null && callee.extensionReceiverParameter == null &&
                callee.valueParameters.size == 3 &&
                callee.valueParameters[0].type.isCharArray() && callee.valueParameters[1].type.isInt() &&
                callee.valueParameters[2].type.classifierOrNull == string && !callee.valueParameters[2].type.isNullable() &&
                callee.returnType.isInt() && arguments.size == 3 &&
                arguments[0].second.type.isCharArray() && arguments[1].second.type.isInt() &&
                arguments[2].second.type.classifierOrNull == string
            ) {
                candidates += expression.symbol
            }
            expression.acceptChildrenVoid(this)
        }
    })
    val wrapper = candidates.singleOrNull()?.owner ?: return null
    val arrayParameter = wrapper.valueParameters[0]
    val startParameter = wrapper.valueParameters[1]
    val valueParameter = wrapper.valueParameters[2]
    var nestedFunction = false
    var directCalls = 0
    var exactDirectCall = false
    var exactLengthCalls = 0
    var otherCalls = 0
    var arrayReads = 0
    wrapper.body?.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

        override fun visitFunction(declaration: IrFunction) {
            if (declaration === wrapper) declaration.acceptChildrenVoid(this) else nestedFunction = true
        }

        override fun visitGetValue(expression: IrGetValue) {
            if (expression.symbol == arrayParameter.symbol) arrayReads++
            expression.acceptChildrenVoid(this)
        }

        override fun visitCall(expression: IrCall) {
            when (expression.symbol) {
                directExternal -> {
                    directCalls++
                    val arguments = expression.getArgumentsWithIr().map { it.second }
                    exactDirectCall = arguments.size == 5 &&
                            (arguments[0] as? IrGetValue)?.symbol == arrayParameter.symbol &&
                            (arguments[1] as? IrGetValue)?.symbol == startParameter.symbol &&
                            (arguments[2] as? IrGetValue)?.symbol == valueParameter.symbol &&
                            (arguments[3] as? IrConst<*>)?.value == 0 &&
                            (arguments[4] as? IrCall)?.let { length ->
                                length.isExactStringLengthRead(generationState, valueParameter)
                            } == true
                }
                else -> if (expression.isExactStringLengthRead(generationState, valueParameter)) {
                    exactLengthCalls++
                } else {
                    otherCalls++
                }
            }
            expression.acceptChildrenVoid(this)
        }
    })
    return wrapper.symbol.takeIf {
        !nestedFunction && directCalls == 1 && exactDirectCall && exactLengthCalls == 1 &&
                otherCalls == 0 && arrayReads == 1
    }
}

private fun IrCall.isExactStringLengthRead(
    generationState: NativeGenerationState,
    valueParameter: org.jetbrains.kotlin.ir.declarations.IrValueParameter,
): Boolean {
    val context = generationState.context
    val stdlib = context.stdlibModule.konanLibrary ?: return false
    val callee = symbol.owner
    return callee.name.asString() == "<get-length>" && callee.konanLibrary === stdlib &&
            callee.dispatchReceiverParameter?.type?.classifierOrNull == context.ir.symbols.string &&
            callee.extensionReceiverParameter == null && callee.valueParameters.isEmpty() &&
            callee.returnType.isInt() && !callee.isSuspend &&
            (dispatchReceiver as? IrGetValue)?.symbol == valueParameter.symbol &&
            extensionReceiver == null && valueArgumentsCount == 0 && typeArgumentsCount == 0
}

/**
 * Post-lowering Native IR does not retain the private field in StringBuilder.declarations. Derive
 * its identity from the three canonical calls that actually consume it, and require every method
 * to project the same exact field symbol before any individual projection is authorized.
 */
private fun resolveCanonicalStringBuilderBackingField(
    generationState: NativeGenerationState,
): IrField? {
    val stringBuilder = generationState.context.ir.symbols.stringBuilder.owner
    val methods = stringBuilder.functions.filter { function ->
        resolveExactStringBuilderProjectionMethod(generationState, function) === function
    }.toList()
    if (methods.size != 3) return null
    val fields = methods.mapNotNull { function ->
        val consumer = resolveExactStringBuilderProjectionConsumer(generationState, function)
            ?: return@mapNotNull null
        val loads = Collections.newSetFromMap(IdentityHashMap<IrGetField, Boolean>())
        var callCount = 0
        function.body?.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitFunction(declaration: IrFunction) {
                if (declaration === function) declaration.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                if (expression.symbol === consumer.symbol) {
                    callCount++
                    val load = expression.getArgumentsWithIr().firstOrNull()?.second as? IrGetField
                    val field = load?.symbol?.owner
                    if (field?.parent === stringBuilder && field.name.asString() == "array" &&
                        field.type.isCharArray() && load.receiver.usesExactDispatchReceiver(function)
                    ) {
                        loads += load
                    }
                }
                expression.acceptChildrenVoid(this)
            }
        })
        if (callCount == 1) loads.singleOrNull()?.symbol?.owner else null
    }.toList()
    val canonical = fields.firstOrNull() ?: return null
    return canonical.takeIf { fields.size == 3 && fields.all { it === canonical } }
}

private fun IrExpression?.usesExactDispatchReceiver(function: IrSimpleFunction): Boolean =
    (this as? IrGetValue)?.symbol == function.dispatchReceiverParameter?.symbol

private fun resolveExactStringLengthGetter(
    generationState: NativeGenerationState,
): IrSimpleFunctionSymbol? {
    val context = generationState.context
    val stdlib = context.stdlibModule.konanLibrary ?: return null
    val string = context.ir.symbols.string.owner
    return string.functions.singleOrNull { candidate ->
        candidate.name.asString() == "<get-length>" && candidate.konanLibrary === stdlib &&
                candidate.dispatchReceiverParameter?.type?.classifierOrNull == string.symbol &&
                candidate.extensionReceiverParameter == null && candidate.valueParameters.isEmpty() &&
                candidate.returnType.isInt() && !candidate.isExternal && !candidate.isSuspend &&
                !candidate.isOverridable
    }?.symbol
}

private fun IrExpression.isOwnershipEffectFreeProjectionSuffix(
    function: IrSimpleFunction,
    exactStringLengthGetter: IrSimpleFunctionSymbol?,
): Boolean = when (this) {
    is IrGetValue, is IrConst<*> -> true
    is IrGetField -> !type.binaryTypeIsReference() && receiver.usesExactDispatchReceiver(function)
    is IrCall -> symbol === exactStringLengthGetter && dispatchReceiver is IrGetValue &&
            extensionReceiver == null && valueArgumentsCount == 0 && typeArgumentsCount == 0 &&
            type.isInt()
    else -> false
}

/** Both call successors must end the same projection borrow before their exit. */
internal fun verifyStringBuilderBackingArrayProjectionLifetime(): Boolean {
    val owner = ArcValue("stringBuilder")
    val array = ArcValue("array")
    val entry = ArcBlockId("entry")
    val normal = ArcBlockId("normal")
    val exceptional = ArcBlockId("exceptional")
    val plan = ArcFunctionPlan(
        functionName = "kotlin.text.StringBuilder#borrow-array",
        entry = entry,
        entryValues = mapOf(owner to ArcOwnership.Guaranteed),
        entryInitializedStorage = emptySet(),
        blocks = mapOf(
            entry to ArcBasicBlock(
                entry,
                listOf(
                    ArcOperation.Borrow(owner, array, ArcBorrowKind.Projection),
                    ArcOperation.Use(array, ArcPlanLocation("exact GCUnsafeCall")),
                ),
                ArcTerminator.Branch(normal, exceptional),
            ),
            normal to ArcBasicBlock(
                normal,
                listOf(ArcOperation.EndBorrow(array)),
                ArcTerminator.Return(),
            ),
            exceptional to ArcBasicBlock(
                exceptional,
                listOf(ArcOperation.EndBorrow(array)),
                ArcTerminator.Throw,
            ),
        ),
    )
    return ArcOwnershipVerifier.verify(plan) === ArcOwnershipVerificationResult.Success
}
