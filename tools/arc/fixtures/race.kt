@file:OptIn(
    kotlin.experimental.ExperimentalNativeApi::class,
    kotlin.ExperimentalStdlibApi::class,
    kotlin.native.concurrent.ObsoleteWorkersApi::class,
    kotlin.native.runtime.NativeRuntimeApi::class,
    kotlin.native.internal.InternalForKotlinNative::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.concurrent.Volatile
import kotlin.native.MemoryModel
import kotlin.native.Platform
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.native.ref.WeakReference
import kotlin.native.ref.createCleaner
import kotlin.native.runtime.GC
import kotlin.native.internal.GCUnsafeCall
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_tVar
import platform.posix.usleep

private const val ATOMIC_WORKERS = 4
private const val ATOMIC_ITERATIONS = 250_000
private const val WEAK_PROMOTIONS = 2_000_000

private class RaceBox(val value: Int, val padding: ByteArray = ByteArray(64))

private val sharedReference = AtomicReference<RaceBox?>(null)
@Volatile
private var volatileReference: RaceBox? = null

private fun atomicReferenceWorker(id: Int): Long {
    var checksum = 0L
    repeat(ATOMIC_ITERATIONS) { iteration ->
        val replacement = RaceBox(id * ATOMIC_ITERATIONS + iteration)
        volatileReference = replacement
        val displaced = sharedReference.getAndSet(replacement)
        if (displaced != null) checksum = checksum xor displaced.value.toLong()
        if ((iteration and 7) == 0 && sharedReference.compareAndSet(replacement, null)) {
            checksum += replacement.value
        }
        sharedReference.value?.let { checksum = checksum xor it.value.toLong() }
        volatileReference?.let { checksum += it.value }
    }
    return checksum
}

private fun runAtomicReferenceRace(): Long {
    val workers = List(ATOMIC_WORKERS) { index -> Worker.start(name = "arc-atomic-$index") }
    return try {
        val futures = workers.mapIndexed { index, worker ->
            worker.execute(TransferMode.SAFE, { index }) { atomicReferenceWorker(it) }
        }
        futures.fold(0L) { checksum, future -> checksum xor future.result }
    } finally {
        sharedReference.value = null
        volatileReference = null
        workers.forEach { it.requestTermination().result }
    }
}

private class WeakRace(
    val owner: AtomicReference<RaceBox?>,
    val weak: WeakReference<RaceBox>,
    val readerStarted: AtomicInt,
)

private fun newWeakRace(): WeakRace {
    val value = RaceBox(42)
    return WeakRace(AtomicReference(value), WeakReference(value), AtomicInt(0))
}

private fun weakIsCleared(weak: WeakReference<RaceBox>): Boolean = weak.get() == null

private fun runWeakPromotionRace(): Int {
    val race = newWeakRace()
    val reader = Worker.start(name = "arc-weak-reader")
    val releaser = Worker.start(name = "arc-weak-releaser")
    return try {
        val reads = reader.execute(TransferMode.SAFE, { race }) { state ->
            var promoted = 0
            state.readerStarted.value = 1
            repeat(WEAK_PROMOTIONS) {
                val value = state.weak.get()
                if (value != null) promoted = promoted xor value.value
            }
            promoted
        }
        val release = releaser.execute(TransferMode.SAFE, { race }) { state ->
            while (state.readerStarted.value == 0) {
            }
            state.owner.value = null
        }
        release.result
        val result = reads.result
        repeat(100) {
            if (weakIsCleared(race.weak)) return result
            GC.collect()
            usleep(1_000u)
        }
        check(weakIsCleared(race.weak)) { "Weak target survived final strong release" }
        result
    } finally {
        reader.requestTermination().result
        releaser.requestTermination().result
    }
}

private val publishedCleanup = AtomicInt(0)

private fun publishCleanup(token: Int) {
    publishedCleanup.value = token
}

private class CleanerOwner {
    @Suppress("unused")
    private val cleaner = createCleaner(73, ::publishCleanup)
}

private fun createCleanerOwner(): WeakReference<CleanerOwner> {
    val owner = CleanerOwner()
    return WeakReference(owner)
}

private fun runCleanerPublication() {
    val owner = createCleanerOwner()
    repeat(2_000) {
        if (publishedCleanup.value == 73 && owner.get() == null) return
        GC.collect()
        usleep(1_000u)
    }
    check(owner.get() == null) { "Cleaner owner was not released" }
    check(publishedCleanup.value == 73) { "Cleaner resource was not safely published" }
}

private val foreignReleaseDone = AtomicInt(0)

@GCUnsafeCall("Kotlin_Interop_disposeStablePointer")
private external fun disposeForeignStablePointer(pointer: COpaquePointer)

private fun newForeignStableRef(): Pair<COpaquePointer, WeakReference<RaceBox>> {
    val value = RaceBox(91)
    return StableRef.create(value).asCPointer() to WeakReference(value)
}

private fun waitForForeignRelease(done: AtomicInt, weak: WeakReference<RaceBox>) {
    while (done.value == 0) {
        weak.get()?.value
    }
}

private fun runForeignThreadStableRelease() {
    val (pointer, weak) = newForeignStableRef()
    memScoped {
        val thread = alloc<pthread_tVar>()
        check(pthread_create(thread.ptr, null, staticCFunction { argument ->
            disposeForeignStablePointer(argument!!)
            foreignReleaseDone.value = 1
            null as COpaquePointer?
        }, pointer) == 0)
        waitForForeignRelease(foreignReleaseDone, weak)
        check(pthread_join(thread.value, null) == 0)
    }
    repeat(100) {
        if (weakIsCleared(weak)) return
        GC.collect()
        usleep(1_000u)
    }
    check(weakIsCleared(weak)) { "StableRef target survived foreign-thread disposal" }
}

fun main() {
    check(Platform.memoryModel == MemoryModel.ARC) {
        "Expected ARC memory model, got ${Platform.memoryModel}"
    }

    val atomicChecksum = runAtomicReferenceRace()
    val weakChecksum = runWeakPromotionRace()
    runCleanerPublication()
    runForeignThreadStableRelease()

    println(
        "ARC_RACE_OK model=${Platform.memoryModel} atomicChecksum=$atomicChecksum weakChecksum=$weakChecksum " +
                "cleanerPublication=ok ownerResurrection=not-exposed-by-public-api foreignStableRelease=ok"
    )
}
