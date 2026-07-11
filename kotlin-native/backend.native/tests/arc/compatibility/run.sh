#!/usr/bin/env bash
set -euo pipefail

konan_home=$1
output=$2
sources=$(cd "$(dirname "$0")" && pwd)
konanc="$konan_home/bin/kotlinc-native"
cinterop="$konan_home/bin/cinterop"
cc=${CC:-cc}
cxx=${CXX:-c++}

rm -rf -- "$output"
mkdir -p "$output/prebuilt" "$output/static" "$output/dynamic" "$output/cinterop"

# A KLIB has no ARC annotations or ARC-specific public ABI. Build it with the strict oracle and consume it
# from an ARC executable to guard Kotlin 1.9.10 KLIB compatibility.
"$konanc" \
    -target linux_x64 \
    -produce library \
    -memory-model strict \
    -output "$output/prebuilt/compat" \
    "$sources/prebuilt.kt"
"$konanc" \
    -target linux_x64 \
    -produce program \
    -memory-model arc \
    -library "$output/prebuilt/compat.klib" \
    -output "$output/prebuilt/consumer" \
    "$sources/prebuilt_main.kt"
"$output/prebuilt/consumer.kexe" | grep -qx PREBUILT_KLIB_OK

# Verify both native library output kinds through their generated C API.
"$konanc" \
    -target linux_x64 \
    -produce static \
    -memory-model arc \
    -output "$output/static/compat" \
    "$sources/exports.kt"
"$cc" -c \
    "$sources/exports_main.c" \
    -I"$output/static" \
    -o "$output/static/consumer.o"
"$cxx" \
    "$output/static/consumer.o" \
    "$output/static/libcompat.a" \
    -ldl -lpthread -lrt -lm \
    -o "$output/static/consumer"
"$output/static/consumer"

"$konanc" \
    -target linux_x64 \
    -produce dynamic \
    -memory-model arc \
    -output "$output/dynamic/compat" \
    "$sources/exports.kt"
"$cc" \
    "$sources/exports_main.c" \
    -I"$output/dynamic" \
    -L"$output/dynamic" \
    -lcompat \
    -Wl,-rpath,"$output/dynamic" \
    -o "$output/dynamic/consumer"
"$output/dynamic/consumer"

# Keep the cinterop path in the same gate so ARC runtime selection is tested with generated native stubs.
"$cinterop" \
    -target linux_x64 \
    -def "$sources/arc_compat_c.def" \
    -compiler-option "-I$sources" \
    -output "$output/cinterop/arc_compat_c"
"$konanc" \
    -target linux_x64 \
    -produce program \
    -memory-model arc \
    -library "$output/cinterop/arc_compat_c.klib" \
    -output "$output/cinterop/consumer" \
    "$sources/cinterop_main.kt"
"$output/cinterop/consumer.kexe" | grep -qx CINTEROP_OK

printf '%s\n' ARC_COMPATIBILITY_OUTPUT_KINDS_OK
