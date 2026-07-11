/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */
package org.jetbrains.kotlin.backend.konan

enum class MemoryModel {
    STRICT,
    RELAXED,
    EXPERIMENTAL,
    ARC;

    val usesSharedHeap: Boolean
        get() = this == EXPERIMENTAL || this == ARC

    val usesTracingGC: Boolean
        get() = this == EXPERIMENTAL

    val usesThreadState: Boolean
        get() = usesSharedHeap
}
