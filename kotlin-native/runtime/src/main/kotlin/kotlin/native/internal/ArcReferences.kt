/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.native.internal

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReferenceImpl
import kotlin.native.ref.getWeakReferenceImpl
import kotlin.native.terminateWithUnhandledException

private const val EXPIRED_UNOWNED_REFERENCE_MESSAGE = "attempted to access an expired @ArcUnowned reference"

/**
 * Creates the hidden storage used by ARC weak and unowned declarations.
 *
 * The returned value strongly owns only the weak-reference control block. It does not retain [value].
 * The type is deliberately erased so that the control-block type never becomes part of user-visible ABI.
 */
@ExportForCompiler
internal fun arcReferenceStorage(value: Any?): Any? = value?.let(::getWeakReferenceImpl)

/** Promotes a hidden ARC reference storage value to a strong reference, or returns null if it has expired. */
@ExportForCompiler
internal fun arcWeakReferenceLoad(storage: Any?): Any? = (storage as WeakReferenceImpl?)?.get()

/**
 * Promotes checked-unowned storage to a strong reference.
 *
 * Expiration is a programming error and terminates the process. In particular, it must not surface as a
 * catchable exception because doing so would make an unowned access appear to have ordinary Kotlin failure semantics.
 */
@OptIn(ExperimentalNativeApi::class)
@ExportForCompiler
internal fun arcUnownedReferenceLoad(storage: Any?): Any =
    (storage as WeakReferenceImpl?)?.get()
        ?: terminateWithUnhandledException(IllegalStateException(EXPIRED_UNOWNED_REFERENCE_MESSAGE))
