import arc.references.klib.ArcReferencePayload
import arc.references.klib.KlibWeakHolder
import arc.references.klib.arcReferencePayloadDeinitCount
import arc.references.klib.unownedReader
import arc.references.klib.weakReader

private class Readers(
    val weak: () -> Int?,
    val unowned: () -> Int,
)

private fun escapedReaders(): Readers {
    val owner = ArcReferencePayload(42)
    val weak = weakReader(owner)
    val unowned = unownedReader(owner)
    check(weak() == 42)
    check(unowned() == 42)
    return Readers(weak, unowned)
}

private fun dependencyGetterDoesNotLeaveAFrameRoot() {
    val deinitCountBefore = arcReferencePayloadDeinitCount()
    var owner: ArcReferencePayload? = ArcReferencePayload(7)
    val holder = KlibWeakHolder(owner!!)
    check(holder.weak?.value == 7)
    owner = null
    check(arcReferencePayloadDeinitCount() == deinitCountBefore + 1)
    check(holder.weak == null)
}

fun main() {
    val readers = escapedReaders()
    check(readers.weak() == null)
    dependencyGetterDoesNotLeaveAFrameRoot()
    println("ARC_REFERENCE_KLIB_OK")
}
