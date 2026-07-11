/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.backend.konan.driver.phases.FrontendPhaseOutput
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

internal const val ARC_DIAGNOSTICS_KLIB_PROPERTY = "konan.arcDiagnosticsRequired"
private const val ARC_DETECT_CYCLES_FQ_NAME = "kotlin.native.arc.ArcDebug.detectCycles"

/** Uses frontend call resolution, so aliases and callable references are handled without source-text heuristics. */
internal fun FrontendPhaseOutput.Full.sourceCallsArcDetectCycles(): Boolean {
    var found = false
    environment.getSourceFiles().forEach { file ->
        file.accept(object : KtTreeVisitorVoid() {
            override fun visitExpression(expression: KtExpression) {
                if (!found) {
                    val descriptor = expression.getResolvedCall(bindingContext)?.resultingDescriptor
                    if (descriptor?.fqNameSafe?.asString() == ARC_DETECT_CYCLES_FQ_NAME) {
                        found = true
                        return
                    }
                    super.visitExpression(expression)
                }
            }
        })
    }
    return found
}

/** K2 serializes FIR through IR, so detect the same resolved call without relying on PSI binding data. */
internal fun IrModuleFragment.callsArcDetectCycles(): Boolean {
    var found = false

    fun isArcDetectCycles(function: IrFunction): Boolean =
            function.fqNameWhenAvailable?.asString() == ARC_DETECT_CYCLES_FQ_NAME

    acceptChildrenVoid(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            if (!found) element.acceptChildrenVoid(this)
        }

        override fun visitCall(expression: IrCall) {
            if (isArcDetectCycles(expression.symbol.owner)) {
                found = true
            } else {
                expression.acceptChildrenVoid(this)
            }
        }

        override fun visitFunctionReference(expression: IrFunctionReference) {
            if (isArcDetectCycles(expression.symbol.owner) ||
                    expression.reflectionTarget?.owner?.let(::isArcDetectCycles) == true) {
                found = true
            } else {
                expression.acceptChildrenVoid(this)
            }
        }
    })
    return found
}
