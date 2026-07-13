#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>

#include "bench_api.h"

typedef bench_ExportedSymbols* (*bench_symbols_t)(void);

int main(int argc, char** argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s <library.so> <iterations>\n", argv[0]);
        return 2;
    }
    void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (library == NULL) {
        fprintf(stderr, "dlopen failed: %s\n", dlerror());
        return 2;
    }
    bench_symbols_t load_symbols = (bench_symbols_t)dlsym(library, "bench_symbols");
    if (load_symbols == NULL) {
        fprintf(stderr, "dlsym failed: %s\n", dlerror());
        return 2;
    }
    bench_ExportedSymbols* symbols = load_symbols();
    int32_t iterations = (int32_t)strtol(argv[2], NULL, 10);
    int32_t phase = (int32_t)(getpid() & 7);
    symbols->kotlin.root.resetMatchingSetBenchmark(phase);
    bench_kref_MatchingSetBenchPayload owner =
        symbols->kotlin.root.MatchingSetBenchPayload.MatchingSetBenchPayload(0x5a5a);
    struct timespec started;
    struct timespec finished;
    clock_gettime(CLOCK_MONOTONIC_RAW, &started);
    for (int32_t index = 0; index < iterations; ++index) {
        symbols->kotlin.root.borrowMatchingSetManyTimes(owner, index + phase);
    }
    clock_gettime(CLOCK_MONOTONIC_RAW, &finished);
    int64_t elapsed_ns =
        (int64_t)(finished.tv_sec - started.tv_sec) * 1000000000LL +
        (int64_t)(finished.tv_nsec - started.tv_nsec);
    int64_t checksum = symbols->kotlin.root.matchingSetBenchmarkChecksum();
    printf("ARC_MATCHING_SET_DYNAMIC_OK iterations=%d elapsed_ns=%lld checksum=%lld\n",
           iterations, (long long)elapsed_ns, (long long)checksum);
    symbols->DisposeStablePointer(owner.pinned);
    dlclose(library);
    return 0;
}
