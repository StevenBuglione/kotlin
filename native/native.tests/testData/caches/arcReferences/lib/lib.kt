package arc.cache

import kotlin.native.arc.ArcUnowned
import kotlin.native.arc.ArcWeak

class Payload(val value: Int)

class Holder(owner: Payload) {
    @ArcWeak
    var weak: Payload? = owner

    @ArcUnowned
    var unowned: Payload = owner
}

inline fun makeWeakReader(owner: Payload): () -> Int? {
    @ArcWeak var captured: Payload? = owner
    return { captured?.value }
}
