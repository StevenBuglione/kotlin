#define _GNU_SOURCE

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

extern void *__libc_calloc(size_t count, size_t size);

static uintptr_t refresh_return_address;
static unsigned long refresh_count;

__attribute__((constructor)) static void configure_refresh_counter(void) {
    const char *value = getenv("ARC_CSTRING_REFRESH_RETURN_ADDRESS");
    if (value != NULL) refresh_return_address = (uintptr_t)strtoull(value, NULL, 0);
}

void *calloc(size_t count, size_t size) {
    void *result = __libc_calloc(count, size);
    if ((uintptr_t)__builtin_return_address(0) == refresh_return_address) refresh_count++;
    return result;
}

__attribute__((destructor)) static void report_refresh_count(void) {
    fprintf(stderr, "ARC_CSTRING_REFRESH_COUNT=%lu\n", refresh_count);
}
