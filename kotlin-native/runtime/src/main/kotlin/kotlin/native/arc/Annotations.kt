/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.native.arc

/** Marks a mutable nullable reference as a zeroing weak ARC reference. */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.LOCAL_VARIABLE)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class ArcWeak

/** Marks a non-null reference as a non-retaining, non-zeroing ARC reference. */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.LOCAL_VARIABLE)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class ArcUnowned

/** Marks the single member function that is invoked when an ARC-managed object is deinitialized. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class ArcDeinit
