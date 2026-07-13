/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

import org.jetbrains.kotlin.backend.konan.cgen.isAuthenticatedNoCallbackCFunction
import org.jetbrains.kotlin.backend.konan.cgen.isCStringParameter
import org.jetbrains.kotlin.backend.konan.ir.konanLibrary
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isNullable
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.isUnsigned
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

internal data class ArcScopedCStringLoopCacheIRSelection(
    val function: IrFunction,
    val loop: IrLoop,
    val foreignCall: IrCall,
    val sourceLoad: IrGetValue,
    val sourceVariable: IrVariable,
)

internal data class ArcScopedCStringLoopCacheIRShape(
    val authenticatedCandidateCount: Int,
    val invalidControlRegionCount: Int,
    val sourceDeclaredOutsideLoop: Boolean,
)

internal fun ArcScopedCStringLoopCacheIRShape.isExact(): Boolean =
    authenticatedCandidateCount == 1 && invalidControlRegionCount == 0 && sourceDeclaredOutsideLoop

/**
 * Real-IR fail-closed selector for the first scoped-CString cache slice.
 *
 * It intentionally accepts only one source-owned function, one loop, one authenticated fixed
 * `const char*` call and one mutable non-null String local declared outside the loop. Introducing
 * nested control regions, local functions, suspension, or function returns invalidates the slice.
 */
internal object ArcScopedCStringLoopCacheIRSelector {
    fun select(
        function: IrFunction,
        loop: IrLoop,
        stringClass: IrClassSymbol,
        isInteropStubsCompilation: Boolean,
    ): ArcScopedCStringLoopCacheIRSelection? {
        fun reject(): ArcScopedCStringLoopCacheIRSelection? {
            return null
        }
        if (function.isExternal || (function as? IrSimpleFunction)?.isSuspend == true ||
            function.konanLibrary != null || loop.body == null
        ) return reject()

        val candidates = mutableListOf<Pair<IrCall, IrGetValue>>()
        val declarationsInsideLoop = mutableSetOf<IrVariable>()
        val invalidReasons = mutableListOf<String>()

        loop.body!!.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitVariable(declaration: IrVariable) {
                declarationsInsideLoop += declaration
                super.visitVariable(declaration)
            }

            override fun visitLoop(loop: IrLoop) {
                invalidReasons += "nested loop"
            }

            override fun visitFunction(declaration: IrFunction) {
                invalidReasons += "local function"
            }

            override fun visitTry(aTry: IrTry) {
                invalidReasons += "try"
            }

            override fun visitReturn(expression: IrReturn) {
                if (expression.returnTargetSymbol == function.symbol) {
                    invalidReasons += "nonlocal function return"
                }
                super.visitReturn(expression)
            }

            override fun visitSuspensionPoint(expression: IrSuspensionPoint) {
                invalidReasons += "suspension point"
            }

            override fun visitSuspendableExpression(expression: IrSuspendableExpression) {
                invalidReasons += "suspendable expression"
            }

            override fun visitCall(expression: IrCall) {
                val callee = expression.symbol.owner as? IrSimpleFunction
                if (callee != null && callee.isAuthenticatedNoCallbackCFunction(isInteropStubsCompilation)) {
                    val language = callee.konanLibrary?.manifestProperties?.getProperty("language")
                    val parameter = callee.valueParameters.singleOrNull()
                    val sourceLoad = expression.getValueArgument(0) as? IrGetValue
                    val source = sourceLoad?.symbol?.owner as? IrVariable
                    // C interop KLIBs produced by this compiler omit the language manifest key;
                    // CBridgeGen authoritatively defaults that absence to C.
                    if ((language != null && language != "C") || parameter == null ||
                        parameter.varargElementType != null || !parameter.isCStringParameter() ||
                        expression.dispatchReceiver != null || expression.extensionReceiver != null ||
                        expression.valueArgumentsCount != 1 || source == null || !source.isVar ||
                        source.type.isNullable() || source.type.classifierOrNull != stringClass ||
                        !(callee.returnType.isPrimitiveType() || callee.returnType.isUnsigned() ||
                                callee.returnType.isUnit())
                    ) {
                        invalidReasons += "inexact authenticated C call ${callee.name}:${callee.returnType}"
                    } else {
                        candidates += expression to sourceLoad
                    }
                }
                super.visitCall(expression)
            }
        })

        val shape = ArcScopedCStringLoopCacheIRShape(
            authenticatedCandidateCount = candidates.size,
            invalidControlRegionCount = invalidReasons.size,
            sourceDeclaredOutsideLoop = candidates.singleOrNull()?.second?.symbol?.owner !in declarationsInsideLoop,
        )
        if (!shape.isExact()) return reject()
        val (call, sourceLoad) = candidates.single()
        val source = sourceLoad.symbol.owner as IrVariable
        return ArcScopedCStringLoopCacheIRSelection(function, loop, call, sourceLoad, source)
    }
}
