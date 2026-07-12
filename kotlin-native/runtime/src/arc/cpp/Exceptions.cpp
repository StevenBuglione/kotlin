/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

#include <exception>

#include "Exceptions.h"
#include "KAssert.h"
#include "Memory.h"

#if defined(KONAN_ARC_MEMORY_MANAGER) && KONAN_ARC_MEMORY_MANAGER

RUNTIME_NOTHROW KInt Kotlin_runCatching(KotlinCatchingCallback callback, KNativePtr context, KRef* exceptionOut) {
    RuntimeAssert(callback != nullptr, "Kotlin exception-catching callback must not be null");
    RuntimeAssert(exceptionOut != nullptr, "Kotlin exception-catching result slot must not be null");
    UpdateReturnRef(exceptionOut, nullptr);
#if KONAN_NO_EXCEPTIONS
    callback(context);
    return 0;
#else
    try {
        callback(context);
        return 0;
    } catch (ExceptionObjHolder& exception) {
        // Retain into caller-owned storage before the native holder releases its reference.
        UpdateReturnRef(exceptionOut, exception.GetExceptionObject());
        return 1;
    } catch (...) {
        // This ABI is exclusively for Kotlin exceptions. Never let a foreign exception cross it.
        std::terminate();
    }
#endif
}

#endif
