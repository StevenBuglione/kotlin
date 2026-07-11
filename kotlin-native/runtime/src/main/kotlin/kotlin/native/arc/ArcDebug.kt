/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.native.arc

import kotlin.native.MemoryModel
import kotlin.native.Platform
import kotlin.native.internal.GCUnsafeCall

/** A non-owning description of one strongly connected component in the live ARC object graph. */
public data class ArcCycleInfo(public val typeNames: List<String>, public val objectCount: Int)

/** Explicit, non-collecting diagnostics for the Kotlin/Native ARC memory manager. */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
public object ArcDebug {
    /**
     * Takes a point-in-time snapshot and returns every cyclic strongly connected component.
     *
     * Detection temporarily retains snapshot objects for memory safety. It neither changes graph edges nor collects
     * cycles. Heap writes are quiesced only while adjacency is copied and resume before SCC analysis begins.
     * Direct calls currently require `-Xbinary=arcLeakCheck=report` or `-Xbinary=arcLeakCheck=fail`; automatic
     * reachability-based diagnostic runtime selection is not yet available.
     */
    public fun detectCycles(): List<ArcCycleInfo> {
        check(Platform.memoryModel == MemoryModel.ARC) {
            "ArcDebug.detectCycles() is only available with the ARC memory manager"
        }
        return detectCyclesImpl().map { component ->
            ArcCycleInfo(
                typeNames = component.map { it::class.qualifiedName ?: "<anonymous>" },
                objectCount = component.size,
            )
        }
    }

    @GCUnsafeCall("Kotlin_ArcDebug_detectCycles")
    private external fun detectCyclesImpl(): Array<Array<Any>>
}
