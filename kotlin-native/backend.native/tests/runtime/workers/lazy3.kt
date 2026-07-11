@file:OptIn(
    FreezingIsDeprecated::class,
    kotlin.experimental.ExperimentalNativeApi::class,
    kotlin.native.runtime.NativeRuntimeApi::class,
)

import kotlin.native.concurrent.*
import kotlin.native.ref.*
import kotlin.test.*

fun main() {
    test1()
    test2()
}

fun test1() {
    if (Platform.memoryModel == MemoryModel.ARC) {
        // Before initialization the lazy delegate's initializer strongly captures its owner.
        // ARC deliberately leaves that cycle alive.
        ensureRemainsAlive { LazyCapturesThis() }
    } else {
        ensureGetsCollectedFrozenAndNotFrozen { LazyCapturesThis() }
    }
    ensureGetsCollectedFrozenAndNotFrozen {
        val l = LazyCapturesThis()
        l.bar
        l
    }
    ensureGetsCollected {
        val l = LazyCapturesThis().freeze()
        l.bar
        l
    }
}

@Suppress("ARC_STRONG_REFERENCE_CYCLE") // Deliberate ARC leak exercised by test1.
class LazyCapturesThis {
    fun foo() = 42
    val bar by lazy { foo() }
}

fun test2() {
    if (Platform.memoryModel == MemoryModel.ARC) {
        // Throwable's uninitialized lazy stack-trace delegate captures its owner.
        ensureRemainsAlive { Throwable() }
    } else {
        ensureGetsCollectedFrozenAndNotFrozen { Throwable() }
    }
    ensureGetsCollectedFrozenAndNotFrozen {
        val throwable = Throwable()
        throwable.getStackTrace()
        throwable
    }
    ensureGetsCollected {
        val throwable = Throwable().freeze()
        throwable.getStackTrace()
        throwable
    }
}

fun ensureGetsCollectedFrozenAndNotFrozen(create: () -> Any) {
    ensureGetsCollected { create().freeze() }
    ensureGetsCollected(create)
}

fun ensureGetsCollected(create: () -> Any) {
    val ref = makeWeakRef(create)
    kotlin.native.runtime.GC.collect()
    assertNull(ref.get())
}

fun ensureRemainsAlive(create: () -> Any) {
    val ref = makeWeakRef(create)
    kotlin.native.runtime.GC.collect()
    assertNotNull(ref.get())
}

fun makeWeakRef(create: () -> Any) = WeakReference(create())
