#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
llvm=${ARC_LLVM_DIR:-${HOME}/.konan/dependencies/llvm-11.1.0-linux-x64-2}
output_dir=${ARC_FRAME_ELISION_UNIT_DIR:-${root}/.arc-runs/frame-elision-unit}

if [[ ! -x "$llvm/bin/clang++" || ! -x "$llvm/bin/llvm-config" ]]; then
    echo "ARC frame-elision unit error: prepared LLVM 11 toolchain not found at $llvm" >&2
    exit 1
fi

mkdir -p "$output_dir"

read -r -a llvm_cxxflags <<<"$("$llvm/bin/llvm-config" --cxxflags | tr '\n' ' ')"
read -r -a llvm_ldflags <<<"$("$llvm/bin/llvm-config" --ldflags --libs all | tr '\n' ' ')"

"$llvm/bin/clang++" \
    "${llvm_cxxflags[@]}" \
    -Wno-deprecated-declarations \
    -std=c++17 \
    -I"$root/kotlin-native/libllvmext/src/main/include" \
    "$root/kotlin-native/libllvmext/src/test/cpp/ArcFrameElisionTest.cpp" \
    "$root/kotlin-native/libllvmext/src/main/cpp/ArcFrameElision.cpp" \
    "${llvm_ldflags[@]}" \
    -Wl,-l:libz.so.1 \
    -lrt -ldl -lpthread -lm \
    -o "$output_dir/ArcFrameElisionTest"

"$output_dir/ArcFrameElisionTest"
