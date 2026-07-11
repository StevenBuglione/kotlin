/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.rust

import org.jetbrains.kotlin.native.interop.rust.RustInteropAsyncPolicy
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgePlan
import org.jetbrains.kotlin.native.interop.rust.RustInteropBridgeType
import org.jetbrains.kotlin.native.interop.rust.RustInteropCrate
import org.jetbrains.kotlin.native.interop.rust.RustInteropErrorMode
import org.jetbrains.kotlin.native.interop.rust.RustInteropErrorPolicy
import org.jetbrains.kotlin.native.interop.rust.RustInteropOperation
import org.jetbrains.kotlin.native.interop.rust.RustInteropOperationKind
import org.jetbrains.kotlin.native.interop.rust.RustInteropOperationThreading
import org.jetbrains.kotlin.native.interop.rust.RustInteropPanicMode
import org.jetbrains.kotlin.native.interop.rust.RustInteropPanicPolicy
import org.jetbrains.kotlin.native.interop.rust.RustInteropParameter
import org.jetbrains.kotlin.native.interop.rust.RustInteropPrimitive
import org.jetbrains.kotlin.native.interop.rust.RustInteropReceiver
import org.jetbrains.kotlin.native.interop.rust.RustInteropReceiverOwnership
import org.jetbrains.kotlin.native.interop.rust.RustInteropTargetPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RustDirectInteropPlanTest {
    @Test
    fun selectsOnlyTargetedAbortPrimitiveFunctions() {
        val direct = operation()
        val wrongTarget = operation(id = "other", kotlinName = "other").copy(
            targetPolicy = RustInteropTargetPolicy(listOf("mingwX64"), emptyList())
        )
        val throwing = operation(id = "throwing", kotlinName = "throwing").copy(
            panicPolicy = RustInteropPanicPolicy(RustInteropPanicMode.KOTLIN_EXCEPTION, "FixtureException")
        )

        val plan = RustDirectInteropPlan.fromPlans(
            listOf(plan(operations = listOf(direct, wrongTarget, throwing))),
            targetName = "linuxX64",
        )

        assertEquals(1, plan.bindingCount)
    }

    @Test
    fun rejectsDuplicateKotlinBindingsAndConflictingCrateCoordinates() {
        assertFailsWith<IllegalArgumentException> {
            RustDirectInteropPlan.fromPlans(
                listOf(plan(), plan(crate = RustInteropCrate("fixture", "1.0.0", emptyList(), true))),
                "linuxX64",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            RustDirectInteropPlan.fromPlans(
                listOf(plan(), plan(crate = RustInteropCrate("fixture", "2.0.0", emptyList(), true))),
                "linuxX64",
            )
        }
    }

    private fun plan(
        crate: RustInteropCrate = RustInteropCrate("fixture", "1.0.0", emptyList(), true),
        operations: List<RustInteropOperation> = listOf(operation()),
    ) = RustInteropBridgePlan(1, "rust.fixture", crate, emptyList(), operations)

    private fun operation(
        id: String = "add",
        kotlinName: String = "add",
    ) = RustInteropOperation(
        id = id,
        kind = RustInteropOperationKind.FUNCTION,
        rustPath = "fixture::add",
        kotlinName = kotlinName,
        receiver = RustInteropReceiver(RustInteropReceiverOwnership.NONE, null),
        parameters = listOf(
            RustInteropParameter("left", RustInteropBridgeType.Primitive(RustInteropPrimitive.INT32)),
            RustInteropParameter("right", RustInteropBridgeType.Primitive(RustInteropPrimitive.INT32)),
        ),
        returnType = RustInteropBridgeType.Primitive(RustInteropPrimitive.INT32),
        errorPolicy = RustInteropErrorPolicy(RustInteropErrorMode.NONE, null),
        panicPolicy = RustInteropPanicPolicy(RustInteropPanicMode.ABORT, null),
        threading = RustInteropOperationThreading.CALLER,
        asyncPolicy = RustInteropAsyncPolicy.SYNCHRONOUS,
        targetPolicy = RustInteropTargetPolicy(emptyList(), emptyList()),
    )
}
