#include <cstdint>

using KotlinCatchingCallback = void (*)(void* context);
using KRef = void*;

extern "C" int32_t Kotlin_runCatching(
        KotlinCatchingCallback callback,
        void* context,
        KRef* exceptionOut);
extern "C" void ReleaseHeapRef(const void* object);

extern "C" int invokeKotlinCatching(KotlinCatchingCallback callback, void* context) {
    KRef exception = nullptr;
    int32_t status = Kotlin_runCatching(callback, context, &exception);
    if (status == 0) {
        return exception == nullptr ? 0 : -1;
    }
    if (status == 1 && exception != nullptr) {
        ReleaseHeapRef(exception);
        return 1;
    }
    if (exception != nullptr) {
        ReleaseHeapRef(exception);
    }
    return -1;
}
