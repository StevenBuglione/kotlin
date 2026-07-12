package arc.references.klib

import kotlin.native.arc.ArcUnowned
import kotlin.native.arc.ArcWeak

class ArcReferencePayload(val value: Int)

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
