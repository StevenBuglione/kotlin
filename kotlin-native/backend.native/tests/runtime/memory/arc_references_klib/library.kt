package arc.references.klib

import kotlin.native.arc.ArcDeinit
import kotlin.native.arc.ArcUnowned
import kotlin.native.arc.ArcWeak

private var payloadDeinitCount = 0

fun arcReferencePayloadDeinitCount(): Int = payloadDeinitCount

class ArcReferencePayload(val value: Int) {
    @ArcDeinit
    private fun deinit() {
        payloadDeinitCount++
    }
}

class KlibWeakHolder(owner: ArcReferencePayload) {
    @ArcWeak
    var weak: ArcReferencePayload? = owner
}

inline fun weakReader(owner: ArcReferencePayload): () -> Int? {
    @ArcWeak var captured: ArcReferencePayload? = owner
    return { captured?.value }
}

inline fun unownedReader(owner: ArcReferencePayload): () -> Int {
    @ArcUnowned val captured: ArcReferencePayload = owner
    return { captured.value }
}
