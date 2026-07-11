/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
@file:OptIn(FreezingIsDeprecated::class, kotlin.experimental.ExperimentalNativeApi::class, kotlin.native.runtime.NativeRuntimeApi::class)

import kotlin.native.concurrent.*
import kotlin.native.internal.*
import kotlin.native.runtime.Debugging

private val usesSharedHeap: Boolean
    get() = Platform.memoryModel == MemoryModel.EXPERIMENTAL || Platform.memoryModel == MemoryModel.ARC

fun setHookAndThrow() {
    val hook = { throwable: Throwable ->
        print("Kotlin hook: ${throwable::class.simpleName}. Runnable state: ${Debugging.isThreadStateRunnable}")
    }
    if (!usesSharedHeap) {
        hook.freeze()
    }

    setUnhandledExceptionHook(hook)

    throw Exception("Error")
}
