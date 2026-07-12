/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.arc

/** Ownership of a reference in the ARC lowering plan. */
internal enum class ArcOwnership {
    /** A plus-one reference that must be consumed or destroyed exactly once on every path. */
    Owned,

    /** A borrowed reference whose lifetime is bounded by another live reference. */
    Guaranteed,

    /** A permanent reference on which copy and destroy are no-ops. */
    Immortal,
}

@JvmInline
internal value class ArcValue(val name: String) {
    override fun toString(): String = "%$name"
}

@JvmInline
internal value class ArcStorage(val name: String) {
    override fun toString(): String = "@$name"
}

internal data class ArcPlanLocation(
    val description: String,
    val sourceOffset: Int? = null,
)

/**
 * The provenance of a guaranteed value.
 *
 * [Identity] borrows the source value itself. [Projection] borrows a reference reached through
 * source-owned storage, such as a strong field. Keeping these distinct prevents an optimizer from
 * replacing a projected value with its owner while retaining the same lifetime dependency.
 */
internal enum class ArcBorrowKind {
    Identity,
    Projection,
}

/** Explicit reference-counting operations consumed by the path verifier and, later, codegen. */
internal sealed class ArcOperation(open val location: ArcPlanLocation?) {
    data class Define(
        val result: ArcValue,
        val ownership: ArcOwnership,
        override val location: ArcPlanLocation? = null,
    ) : ArcOperation(location)

    data class Copy(
        val source: ArcValue,
        val result: ArcValue,
        override val location: ArcPlanLocation? = null,
    ) : ArcOperation(location)

    data class Destroy(
        val value: ArcValue,
        override val location: ArcPlanLocation? = null,
    ) : ArcOperation(location)

    data class Borrow(
        val source: ArcValue,
        val result: ArcValue,
        val kind: ArcBorrowKind = ArcBorrowKind.Identity,
        override val location: ArcPlanLocation? = null,
    ) : ArcOperation(location)

    /** Ends the lexical use interval introduced by [Borrow]. */
    data class EndBorrow(
        val value: ArcValue,
        override val location: ArcPlanLocation? = null,
    ) : ArcOperation(location)

    /** A non-consuming use, such as passing a guaranteed argument to a Kotlin call. */
    data class Use(
        val value: ArcValue,
        override val location: ArcPlanLocation? = null,
    ) : ArcOperation(location)

    data class StrongStore(
        val storage: ArcStorage,
        val value: ArcValue,
        override val location: ArcPlanLocation? = null,
    ) : ArcOperation(location)

    /**
     * Replaces initialized strong [storage] with a projected [newBorrow]. The replacement retains
     * the new value before it consumes/releases [oldOwner], and atomically ends the projection
     * borrow that depends on that exact old owner. This is the ownership operation implemented by
     * retain-before-release self replacement such as `cursor = cursor.next`.
     */
    data class StrongReplace(
        val storage: ArcStorage,
        val oldOwner: ArcValue,
        val newBorrow: ArcValue,
        override val location: ArcPlanLocation? = null,
    ) : ArcOperation(location)

    data class StrongLoad(
        val storage: ArcStorage,
        val result: ArcValue,
        override val location: ArcPlanLocation? = null,
    ) : ArcOperation(location)
}

@JvmInline
internal value class ArcBlockId(val name: String) {
    override fun toString(): String = name
}

internal sealed class ArcTerminator {
    data class Jump(val target: ArcBlockId) : ArcTerminator()
    data class Branch(val trueTarget: ArcBlockId, val falseTarget: ArcBlockId) : ArcTerminator()
    data class Return(val value: ArcValue? = null) : ArcTerminator()
    object Throw : ArcTerminator()
    object Unreachable : ArcTerminator()
}

internal data class ArcBasicBlock(
    val id: ArcBlockId,
    val operations: List<ArcOperation>,
    val terminator: ArcTerminator,
)

internal data class ArcFunctionPlan(
    val functionName: String,
    val entry: ArcBlockId,
    val entryValues: Map<ArcValue, ArcOwnership>,
    val entryInitializedStorage: Set<ArcStorage>,
    val blocks: Map<ArcBlockId, ArcBasicBlock>,
)
