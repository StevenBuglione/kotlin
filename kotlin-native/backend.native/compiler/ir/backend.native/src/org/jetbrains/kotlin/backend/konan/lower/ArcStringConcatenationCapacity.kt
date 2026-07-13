/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.lower

import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.MemoryModel
import org.jetbrains.kotlin.backend.konan.isFinalBinary
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.konan.target.KonanTarget

internal data class ArcStringConcatenationCapacityEligibility(
    val arcEnabled: Boolean,
    val linuxX64: Boolean,
    val finalBinary: Boolean,
    val optimizationsEnabled: Boolean,
    val debugInfoDisabled: Boolean,
    val diagnosticsDisabled: Boolean,
    val sanitizerDisabled: Boolean,
    val coverageDisabled: Boolean,
)

internal fun ArcStringConcatenationCapacityEligibility.isAuthorized(): Boolean =
    arcEnabled && linuxX64 && finalBinary && optimizationsEnabled && debugInfoDisabled &&
            diagnosticsDisabled && sanitizerDisabled && coverageDisabled

private const val NATIVE_STRING_BUILDER_DEFAULT_CAPACITY = 10
private const val ARC_INTERPOLATION_CAPACITY_ALLOWANCE = 8
private const val ARC_MAX_EAGER_STRING_BUILDER_CAPACITY = 4096

/**
 * Plans the backing CharArray for compiler-generated string concatenations.
 *
 * Kotlin/Native's StringBuilder stores UTF-16 code units, so Kotlin String.length is the exact
 * unit for literals. Dynamic interpolations remain deliberately unevaluated. The small per-value
 * allowance mirrors Swift StringInterpolation's capacity heuristic, adjusted from two bytes to
 * eight UTF-16 code units for Native's exact-sized CharArray. The bounded result cannot overflow
 * and avoids turning an unusually large dynamic concatenation into an eager allocation.
 */
internal fun planArcStringConcatenationCapacity(
    literalUtf16Lengths: List<Int>,
    interpolationCount: Int,
): Int? {
    if (interpolationCount < 0 || literalUtf16Lengths.any { it < 0 }) return null
    var capacity = interpolationCount.toLong() * ARC_INTERPOLATION_CAPACITY_ALLOWANCE
    if (capacity > ARC_MAX_EAGER_STRING_BUILDER_CAPACITY) return null
    for (length in literalUtf16Lengths) {
        capacity += length.toLong()
        if (capacity > ARC_MAX_EAGER_STRING_BUILDER_CAPACITY) return null
    }
    if (capacity <= NATIVE_STRING_BUILDER_DEFAULT_CAPACITY) return null
    return capacity.toInt()
}

internal fun Context.arcStringConcatenationCapacity(expression: IrStringConcatenation): Int? {
    val eligibility = ArcStringConcatenationCapacityEligibility(
        arcEnabled = memoryModel == MemoryModel.ARC,
        linuxX64 = config.target == KonanTarget.LINUX_X64,
        finalBinary = config.isFinalBinary,
        optimizationsEnabled = config.optimizationsEnabled,
        debugInfoDisabled = !shouldContainAnyDebugInfo(),
        diagnosticsDisabled = !config.arcDiagnosticsEnabled,
        sanitizerDisabled = config.sanitizer == null && !config.undefinedBehaviorSanitizer,
        coverageDisabled = !config.shouldCoverSources,
    )
    if (!eligibility.isAuthorized()) return null

    val literalLengths = ArrayList<Int>()
    var interpolationCount = 0
    for (argument in expression.arguments) {
        val literal = (argument as? IrConst<*>)
            ?.takeIf { it.kind == IrConstKind.String }
            ?.value as? String
        if (literal == null) interpolationCount++ else literalLengths += literal.length
    }
    return planArcStringConcatenationCapacity(literalLengths, interpolationCount)
}
