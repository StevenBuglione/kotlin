#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
llvm=${ARC_LLVM_DIR:-${HOME}/.konan/dependencies/llvm-11.1.0-linux-x64-2}
output_dir=${root}/.arc-runs/return-update-coalescing-unit

if [[ ! -x "$llvm/bin/clang++" || ! -x "$llvm/bin/llvm-config" ]]; then
    echo "ARC return-update coalescing unit error: prepared LLVM 11 toolchain not found at $llvm" >&2
    exit 1
fi

cmake \
    -S "$root/kotlin-native/libllvmext" \
    -B "$output_dir" \
    -DLLVM_DIR="$llvm" \
    -DKONAN_LLVMEXT_BUILD_TESTS=ON \
    -DCMAKE_BUILD_TYPE=Release
cmake --build "$output_dir" --target ArcReturnUpdateCoalescingTest -j "${ARC_BUILD_JOBS:-8}"
ctest --test-dir "$output_dir" --output-on-failure -R '^ArcReturnUpdateCoalescingTest$'
