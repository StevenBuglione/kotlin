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
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrSuspendableExpression
import org.jetbrains.kotlin.ir.expressions.IrSuspensionPoint
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import java.util.IdentityHashMap

internal data class ArcCoroutineGuaranteedPhiIRDiagnostics(
    val selectedWebs: Int,
    val seeds: Int,
    val joins: Int,
    val forwards: Int,
    val borrows: Int,
    val consumes: Int,
    val projectedUpdateStackRefsRemoved: Int,
    val projectedRetainsRemoved: Int,
    val projectedReleasesRemoved: Int,
)

/** Exact post-lowering IR identities authorized by the coroutine phi-web proof. */
internal data class ArcCoroutineGuaranteedPhiIRSelection(
    val function: IrSimpleFunction,
    val receiverParameter: org.jetbrains.kotlin.ir.declarations.IrValueParameter,
    val resultParameter: org.jetbrains.kotlin.ir.declarations.IrValueParameter,
    val loop: IrLoop,
    val current: IrVariable,
    val parameterState: IrVariable,
    val currentIterationBorrow: IrVariable,
    val currentJoinedRead: IrGetValue,
    val parameterJoinedRead: IrGetValue,
    val invokeSuspend: IrCall,
    val releaseIntercepted: IrCall,
    val currentBackedgeStore: IrSetValue,
    val parameterBackedgeStore: IrSetValue,
    val proof: ArcCoroutineGuaranteedPhiResult,
    val diagnostics: ArcCoroutineGuaranteedPhiIRDiagnostics,
)

/**
 * Extract the actual post-inlining `BaseContinuationImpl.resumeWith` loop.
 *
 * The selected identities are consumed by the narrow path-dependent guaranteed-phi codegen path.
 * It deliberately identifies declarations and expressions by IR object/symbol identity; names
 * are used only to identify the stdlib ABI entry points, never to equate slots or values.
 */
internal fun selectVerifiedCoroutineGuaranteedPhiWebs(
    generationState: NativeGenerationState,
    function: IrSimpleFunction,
): ArcCoroutineGuaranteedPhiIRSelection? {
    if (generationState.context.memoryModel != MemoryModel.ARC ||
        !generationState.context.config.optimizationsEnabled ||
        generationState.context.shouldContainDebugInfo()
    ) return null
    // This selector is visited for every class member. Reject by the cheap declaration key before
    // touching the stdlib module/symbol table; materializing those lookups for unrelated members
    // showed up directly in cold compiler RSS.
    if (function.name.asString() != "resumeWith") return null
    val owner = function.parent as? IrClass ?: return null
    val stdlib = generationState.context.stdlibModule.konanLibrary ?: return null
    val baseContinuation = generationState.context.ir.symbols.baseContinuationImpl.owner
    if (owner.symbol != baseContinuation.symbol || owner.konanLibrary !== stdlib ||
        owner.fqNameForIrSerialization.asString() != "kotlin.coroutines.native.internal.BaseContinuationImpl" ||
        function.isExternal || !function.returnType.isUnit()
    ) return null
    fun reject(reason: String): ArcCoroutineGuaranteedPhiIRSelection? {
        generationState.context.log {
            "ARC coroutine guaranteed-phi selector ${function.fqNameForIrSerialization.asString()}: $reason"
        }
        return null
    }
    val releaseInterceptedSymbol = baseContinuation.declarations.filterIsInstance<IrSimpleFunction>().singleOrNull {
        it.name.asString() == "releaseIntercepted" && it.konanLibrary === stdlib
    }?.symbol ?: return reject("release symbol")
    val receiver = function.dispatchReceiverParameter ?: return reject("receiver")
    val resultParameter = function.valueParameters.singleOrNull() ?: return reject("result parameter")
    if (!receiver.type.binaryTypeIsReference() || !resultParameter.type.binaryTypeIsReference()) return reject("ABI reference types")
    val body = function.body as? IrBlockBody ?: return reject("block body")
    if (body.statements.size != 5) return reject("body statements=${body.statements.size}")
    val probe = body.statements[0] as? IrCall ?: return reject("probe call")
    if (probe.symbol.owner.fqNameForIrSerialization.asString() !=
            "kotlin.coroutines.native.internal.probeCoroutineResumed" ||
        probe.symbol.owner.konanLibrary !== stdlib ||
        probe.valueArgumentsCount != 1 || !probe.getValueArgument(0).isExactReadOf(receiver.symbol)
    ) return reject("probe identity/shape")
    val current = body.statements[1] as? IrVariable ?: return reject("current variable")
    val parameterState = body.statements[2] as? IrVariable ?: return reject("parameter variable")
    val loop = body.statements[3] as? IrLoop ?: return reject("loop")
    val trailingReturn = body.statements[4] as? IrReturn ?: return reject("trailing return")
    if (!current.isVar || !parameterState.isVar || !current.type.binaryTypeIsReference() ||
        !parameterState.type.binaryTypeIsReference() || current.hasArcOwnershipAnnotation() ||
        parameterState.hasArcOwnershipAnnotation() || !current.initializer.isExactReadOf(receiver.symbol) ||
        !parameterState.initializer.isExactReadOf(resultParameter.symbol) ||
        (loop.condition as? IrConst<*>)?.value != true || trailingReturn.returnTargetSymbol != function.symbol
    ) return reject("entry variables/loop shape")

    val scan = CoroutineLoopScan(function, current, parameterState).apply { loop.acceptVoid(this) }
    if (scan.sawNestedFunction || scan.sawSuspension || scan.currentReads.size != 1 ||
        scan.parameterReads.size != 1 || scan.currentStores.size != 1 || scan.parameterStores.size != 1 ||
        scan.continues.any { it.loop === loop }
    ) return reject(
        "loop effects nested=${scan.sawNestedFunction} suspend=${scan.sawSuspension} " +
                "currentReads=${scan.currentReads.size} parameterReads=${scan.parameterReads.size} " +
                "currentStores=${scan.currentStores.size} parameterStores=${scan.parameterStores.size} " +
                "continues=${scan.continues.count { it.loop === loop }}",
    )
    val currentIterationBorrow = scan.variables.singleOrNull { variable ->
        !variable.isVar && variable.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE_FOR_INLINED_PARAMETER &&
                variable.type.binaryTypeIsReference() && variable.initializer.hasOnlyExactReadOf(current.symbol)
    } ?: return reject("iteration current borrow")
    if (scan.currentReads.single().symbol != current.symbol) return reject("iteration current identity")

    val completion = scan.variables.singleOrNull { variable ->
        !variable.isVar && variable.initializer.hasSingleFieldProjectionFrom(currentIterationBorrow) &&
                scan.readsOf(variable).size == 3
    } ?: return reject("completion projection")
    val outcome = scan.variables.singleOrNull { !it.isVar && it.initializer is IrTry } ?: return reject("outcome try")

    val invokeSuspend = scan.calls.singleOrNull { call ->
        call.symbol == generationState.context.ir.symbols.invokeSuspendFunction &&
                call.dispatchReceiver.isExactReadOf(currentIterationBorrow.symbol) &&
                call.valueArgumentsCount == 1 && call.getValueArgument(0).isExactReadOf(parameterState.symbol)
    } ?: return reject("invokeSuspend identity/shape")
    val releaseIntercepted = scan.calls.singleOrNull { call ->
        call.symbol == releaseInterceptedSymbol &&
                call.dispatchReceiver.isExactReadOf(currentIterationBorrow.symbol) && call.valueArgumentsCount == 0
    } ?: return reject("releaseIntercepted identity/shape")
    if (scan.tryDepth[invokeSuspend] != 1 || scan.tryDepth[releaseIntercepted] != 0) return reject("call try depth")

    val currentBackedgeStore = scan.currentStores.single()
    val parameterBackedgeStore = scan.parameterStores.single()
    val backedgeBlock = scan.nearestBlock[currentBackedgeStore]
    if (backedgeBlock == null || backedgeBlock !== scan.nearestBlock[parameterBackedgeStore] ||
        backedgeBlock.statements.size != 2 || backedgeBlock.statements[0] !== currentBackedgeStore ||
        backedgeBlock.statements[1] !== parameterBackedgeStore ||
        !currentBackedgeStore.value.isImplicitCastReadOf(completion.symbol) ||
        !parameterBackedgeStore.value.isExactReadOf(outcome.symbol)
    ) return reject("backedge stores")

    // The joined current identity may be used only for the field projection and the two virtual
    // calls. The joined parameter identity may be used only by invokeSuspend.
    if (scan.readsOf(currentIterationBorrow).size != 3 || scan.readsOf(parameterState).size != 1) {
        return reject(
            "joined reads current=${scan.readsOf(currentIterationBorrow).size} " +
                    "parameter=${scan.readsOf(parameterState).size}",
        )
    }

    val proof = buildCoroutineGuaranteedPhiProof(function, current, parameterState, invokeSuspend, releaseIntercepted)
    if (proof.rejected.isNotEmpty() || proof.accepted.size != 2 ||
        proof.totalReduction != ArcCoroutineGuaranteedPhiReduction(4, 4, 4)
    ) return reject("proof accepted=${proof.accepted.size} rejected=${proof.rejected}")
    val totals = proof.accepted.map { it.counts }.fold(ArcCoroutineGuaranteedPhiCounts(0, 0, 0, 0, 0)) { total, next ->
        ArcCoroutineGuaranteedPhiCounts(
            total.seeds + next.seeds,
            total.joins + next.joins,
            total.forwards + next.forwards,
            total.borrows + next.borrows,
            total.consumes + next.consumes,
        )
    }
    return ArcCoroutineGuaranteedPhiIRSelection(
        function,
        receiver,
        resultParameter,
        loop,
        current,
        parameterState,
        currentIterationBorrow,
        scan.currentReads.single(),
        scan.parameterReads.single(),
        invokeSuspend,
        releaseIntercepted,
        currentBackedgeStore,
        parameterBackedgeStore,
        proof,
        ArcCoroutineGuaranteedPhiIRDiagnostics(
            proof.accepted.size,
            totals.seeds,
            totals.joins,
            totals.forwards,
            totals.borrows,
            totals.consumes,
            proof.totalReduction.updateStackRefs,
            proof.totalReduction.retains,
            proof.totalReduction.releases,
        ),
    )
}

private class CoroutineLoopScan(
    private val function: IrSimpleFunction,
    private val current: IrVariable,
    private val parameterState: IrVariable,
) : IrElementVisitorVoid {
    val variables = mutableListOf<IrVariable>()
    val calls = mutableListOf<IrCall>()
    val currentReads = mutableListOf<IrGetValue>()
    val parameterReads = mutableListOf<IrGetValue>()
    val currentStores = mutableListOf<IrSetValue>()
    val parameterStores = mutableListOf<IrSetValue>()
    val continues = mutableListOf<IrContinue>()
    val tryDepth = IdentityHashMap<IrCall, Int>()
    val nearestBlock = IdentityHashMap<IrSetValue, IrBlock?>()
    private val reads = IdentityHashMap<IrVariable, MutableList<IrGetValue>>()
    private val blocks = ArrayDeque<IrBlock>()
    private var activeTryDepth = 0
    var sawNestedFunction = false
    var sawSuspension = false

    fun readsOf(variable: IrVariable): List<IrGetValue> = reads[variable].orEmpty()

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitSimpleFunction(declaration: IrSimpleFunction) {
        if (declaration !== function) sawNestedFunction = true
    }

    override fun visitVariable(declaration: IrVariable) {
        variables += declaration
        declaration.acceptChildrenVoid(this)
    }

    override fun visitBlock(expression: IrBlock) {
        blocks.addLast(expression)
        expression.acceptChildrenVoid(this)
        blocks.removeLast()
    }

    override fun visitTry(aTry: IrTry) {
        activeTryDepth++
        aTry.acceptChildrenVoid(this)
        activeTryDepth--
    }

    override fun visitSuspendableExpression(expression: IrSuspendableExpression) {
        sawSuspension = true
    }

    override fun visitSuspensionPoint(expression: IrSuspensionPoint) {
        sawSuspension = true
    }

    override fun visitCall(expression: IrCall) {
        calls += expression
        tryDepth[expression] = activeTryDepth
        expression.acceptChildrenVoid(this)
    }

    override fun visitGetValue(expression: IrGetValue) {
        when (expression.symbol) {
            current.symbol -> currentReads += expression
            parameterState.symbol -> parameterReads += expression
        }
        (expression.symbol.owner as? IrVariable)?.let { variable ->
            reads.getOrPut(variable) { mutableListOf() } += expression
        }
        expression.acceptChildrenVoid(this)
    }

    override fun visitSetValue(expression: IrSetValue) {
        when (expression.symbol) {
            current.symbol -> currentStores += expression
            parameterState.symbol -> parameterStores += expression
        }
        nearestBlock[expression] = blocks.lastOrNull()
        expression.acceptChildrenVoid(this)
    }

    override fun visitContinue(jump: IrContinue) {
        continues += jump
        jump.acceptChildrenVoid(this)
    }
}

private fun IrVariable.hasArcOwnershipAnnotation(): Boolean =
    hasAnnotation(KonanFqNames.arcWeak) || hasAnnotation(KonanFqNames.arcUnowned)

private fun IrExpression?.isExactReadOf(symbol: org.jetbrains.kotlin.ir.symbols.IrValueSymbol): Boolean =
    this is IrGetValue && this.symbol == symbol

private fun IrExpression?.hasOnlyExactReadOf(symbol: org.jetbrains.kotlin.ir.symbols.IrValueSymbol): Boolean {
    val expression = this ?: return false
    val reads = mutableListOf<IrGetValue>()
    var hasEffect = false
    expression.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
        override fun visitGetValue(expression: IrGetValue) {
            reads += expression
            expression.acceptChildrenVoid(this)
        }
        override fun visitCall(expression: IrCall) { hasEffect = true }
        override fun visitGetField(expression: IrGetField) { hasEffect = true }
        override fun visitSetValue(expression: IrSetValue) { hasEffect = true }
    })
    return !hasEffect && reads.size == 1 && reads.single().symbol == symbol
}

private fun IrExpression?.hasSingleFieldProjectionFrom(variable: IrVariable): Boolean {
    val expression = this ?: return false
    val fields = mutableListOf<IrGetField>()
    expression.acceptVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
        override fun visitGetField(expression: IrGetField) {
            fields += expression
            expression.acceptChildrenVoid(this)
        }
    })
    return fields.size == 1 && fields.single().receiver.isExactReadOf(variable.symbol)
}

private fun IrExpression.isImplicitCastReadOf(symbol: org.jetbrains.kotlin.ir.symbols.IrValueSymbol): Boolean = when (this) {
    is IrGetValue -> this.symbol == symbol
    is IrTypeOperatorCall -> operator == IrTypeOperator.IMPLICIT_CAST && argument.isImplicitCastReadOf(symbol)
    else -> false
}

private fun buildCoroutineGuaranteedPhiProof(
    function: IrSimpleFunction,
    current: IrVariable,
    parameterState: IrVariable,
    invokeSuspend: IrCall,
    releaseIntercepted: IrCall,
): ArcCoroutineGuaranteedPhiResult {
    val entry = ArcBlockId("${function.startOffset}.entry")
    val header = ArcBlockId("${function.startOffset}.loop")
    val afterInvoke = ArcBlockId("${function.startOffset}.afterInvoke")
    val backedge = ArcBlockId("${function.startOffset}.backedge")
    val exit = ArcBlockId("${function.startOffset}.exit")
    val unwind = ArcBlockId("${function.startOffset}.unwind")
    val currentScope = ArcSSAValue("${current.startOffset}.abi.scope")
    val parameterScope = ArcSSAValue("${parameterState.startOffset}.abi.scope")
    val currentEntry = ArcSSAValue("${current.startOffset}.entry")
    val parameterEntry = ArcSSAValue("${parameterState.startOffset}.entry")
    val currentOwner = ArcSSAValue("${current.startOffset}.backedge.owner")
    val parameterOwner = ArcSSAValue("${parameterState.startOffset}.backedge.owner")
    val currentBackedge = ArcSSAValue("${current.startOffset}.backedge.borrow")
    val parameterBackedge = ArcSSAValue("${parameterState.startOffset}.backedge.borrow")
    val currentJoin = ArcSSAValue("${current.startOffset}.phi")
    val parameterJoin = ArcSSAValue("${parameterState.startOffset}.phi")
    val currentForward = ArcSSAValue("${current.startOffset}.iteration")
    val parameterForward = ArcSSAValue("${parameterState.startOffset}.iteration")
    val entryEdge = ArcSSAEdge(entry, header)
    val headerNormal = ArcSSAEdge(header, afterInvoke)
    val headerUnwind = ArcSSAEdge(header, unwind, ArcSSAEdgeKind.Exceptional)
    val afterBackedge = ArcSSAEdge(afterInvoke, backedge)
    val afterExit = ArcSSAEdge(afterInvoke, exit)
    val afterUnwind = ArcSSAEdge(afterInvoke, unwind, ArcSSAEdgeKind.Exceptional)
    val backedgeEdge = ArcSSAEdge(backedge, header)
    val cfg = ArcOwnershipSSAInput(
        entry,
        linkedMapOf(
            entry to ArcSSABlock(entry, listOf(
                ArcSSAOperation.Introduce(currentScope, ArcOwnership.Immortal),
                ArcSSAOperation.Introduce(parameterScope, ArcOwnership.Immortal),
                ArcSSAOperation.Introduce(currentEntry, ArcOwnership.Guaranteed, setOf(currentScope)),
                ArcSSAOperation.Introduce(parameterEntry, ArcOwnership.Guaranteed, setOf(parameterScope)),
            )),
            header to ArcSSABlock(header, listOf(
                ArcSSAOperation.Join(currentJoin, linkedMapOf(entry to currentEntry, backedge to currentBackedge)),
                ArcSSAOperation.Join(parameterJoin, linkedMapOf(entry to parameterEntry, backedge to parameterBackedge)),
                ArcSSAOperation.Forward(currentJoin, currentForward),
                ArcSSAOperation.Forward(parameterJoin, parameterForward),
                ArcSSAOperation.Use(currentForward, ArcSSAUseKind.Borrow, mayThrow = true,
                    location = ArcPlanLocation("invokeSuspend receiver", invokeSuspend.startOffset)),
                ArcSSAOperation.Use(parameterForward, ArcSSAUseKind.Borrow,
                    location = ArcPlanLocation("invokeSuspend result", invokeSuspend.startOffset)),
            )),
            afterInvoke to ArcSSABlock(afterInvoke, listOf(
                ArcSSAOperation.Use(currentJoin, ArcSSAUseKind.Borrow, mayThrow = true,
                    location = ArcPlanLocation("releaseIntercepted receiver", releaseIntercepted.startOffset)),
                ArcSSAOperation.Use(currentJoin, ArcSSAUseKind.Borrow),
            )),
            backedge to ArcSSABlock(backedge, listOf(
                ArcSSAOperation.Introduce(currentOwner, ArcOwnership.Owned),
                ArcSSAOperation.Reborrow(currentOwner, currentBackedge, setOf(currentOwner)),
                ArcSSAOperation.Introduce(parameterOwner, ArcOwnership.Owned),
                ArcSSAOperation.Reborrow(parameterOwner, parameterBackedge, setOf(parameterOwner)),
            )),
            exit to ArcSSABlock(exit, emptyList()),
            unwind to ArcSSABlock(unwind, emptyList()),
        ),
        setOf(entryEdge, headerNormal, headerUnwind, afterBackedge, afterExit, afterUnwind, backedgeEdge),
    )
    fun barrier(position: ArcRCPosition, normal: ArcSSAEdge, exceptional: ArcSSAEdge) =
        ArcCoroutinePhiBarrierProof(position, normal, exceptional, true, true, true)
    val invokeProof = barrier(ArcRCPosition(header, 4), headerNormal, headerUnwind)
    val releaseProof = barrier(ArcRCPosition(afterInvoke, 0), afterExit, afterUnwind)
    fun candidate(
        join: ArcSSAValue,
        entryValue: ArcSSAValue,
        backedgeValue: ArcSSAValue,
        entryAnchor: ArcSSAValue,
        backedgeAnchor: ArcSSAValue,
        barriers: Set<ArcCoroutinePhiBarrierProof>,
        peers: Set<ArcCoroutinePhiPeerControlJoinProof>,
    ) = ArcCoroutineGuaranteedPhiCandidate(
        cfg, header, entryEdge, backedgeEdge, join, entryValue, backedgeValue, entryAnchor,
        backedgeAnchor, setOf(entryValue, backedgeValue), barriers, 2, 1,
        completeUseChain = true,
        strongNonVolatileStorage = true,
        entryGuaranteedByAbi = true,
        backedgeOwnershipMaterializedBeforeAnchorEnd = true,
        peerControlJoins = peers,
    )
    return ArcCoroutineGuaranteedPhiWebAnalysis.analyze(listOf(
        candidate(currentJoin, currentEntry, currentBackedge, currentScope, currentOwner,
            setOf(invokeProof, releaseProof),
            setOf(ArcCoroutinePhiPeerControlJoinProof(ArcRCPosition(header, 1), parameterJoin))),
        candidate(parameterJoin, parameterEntry, parameterBackedge, parameterScope, parameterOwner,
            setOf(invokeProof),
            setOf(ArcCoroutinePhiPeerControlJoinProof(ArcRCPosition(header, 0), currentJoin))),
    ))
}
