#ifndef KOTLIN_NATIVE_ARC_BENCHMARK_CINTEROP_H
#define KOTLIN_NATIVE_ARC_BENCHMARK_CINTEROP_H

#include <stddef.h>
#include <string.h>

// Keep strlen behind a real foreign boundary. In particular, this prevents LLVM from
// replacing the benchmark's runtime-mutated buffer or dynamic CString calls with a
// compile-time length while still exercising the ordinary cinterop call path.
static __attribute__((noinline)) size_t arc_benchmark_strlen_ptr(const char* value) {
    return strlen(value);
}

static __attribute__((noinline)) size_t arc_benchmark_strlen_string(const char* value) {
    return strlen(value);
}

#endif
