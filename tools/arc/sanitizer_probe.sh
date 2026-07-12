#!/usr/bin/env bash
set -uo pipefail

requested=${1:-}
case "$requested" in
    all|asan|ubsan|tsan) ;;
    *) echo "usage: $0 all|asan|ubsan|tsan" >&2; exit 2 ;;
esac

root=$(git rev-parse --show-toplevel)
state=${ARC_RUN_STATE_DIR:?ARC_RUN_STATE_DIR is set by the durable remote runner}
dist=${ARC_DIST_DIR:-$root/kotlin-native/dist}
compiler="$dist/bin/konanc"
source="$root/tools/arc/fixtures/sanitizer.kt"
race_source="$root/tools/arc/fixtures/tsan_race.kt"

[[ -x "$compiler" ]] || {
    echo "ARC sanitizer probe requires a built Kotlin/Native distribution; run remote-dist first" >&2
    exit 1
}
[[ -f "$source" ]] || { echo "missing sanitizer fixture: $source" >&2; exit 1; }

if [[ "$requested" == all ]]; then
    matrix="$state/sanitizer-matrix.tsv"
    printf 'sanitizer\tstatus\texit\n' >"$matrix"
    overall=0
    for kind in asan ubsan tsan; do
        child="$state/sanitizer-$kind"
        mkdir -p "$child"
        ARC_RUN_STATE_DIR="$child" bash "$0" "$kind"
        result=$?
        status=FAIL
        [[ $result -eq 0 ]] && status=PASS
        [[ $result -eq 77 ]] && status=UNSUPPORTED
        printf '%s\t%s\t%s\n' "$kind" "$status" "$result" | tee -a "$matrix"
        if [[ $result -ne 0 && $result -ne 77 ]]; then
            overall=1
        elif [[ $result -eq 77 && $overall -eq 0 ]]; then
            overall=77
        fi
    done
    echo "Sanitizer matrix written to $matrix"
    if [[ $overall -eq 77 ]]; then
        echo "One or more requested sanitizers are unsupported; matrix is incomplete, not passed" >&2
        exit 77
    fi
    exit "$overall"
fi

case "$requested" in
    asan)
        compiler_option='-Xbinary=sanitizer=address'
        symbol_regex='__asan_(init|report|load|store)'
        runtime_env=(env 'ASAN_OPTIONS=detect_leaks=1:halt_on_error=1:abort_on_error=1')
        ;;
    ubsan)
        compiler_option='-Xbinary=undefinedBehaviorSanitizer=true'
        symbol_regex='__ubsan_handle_'
        runtime_env=(env 'UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1')
        ;;
    tsan)
        compiler_option='-Xbinary=sanitizer=thread'
        symbol_regex='__tsan_(init|func_entry|read|write)'
        runtime_env=(env 'TSAN_OPTIONS=halt_on_error=1:history_size=7:second_deadlock_stack=1')
        ;;
esac

artifacts="$state/artifacts"
output="$artifacts/arc-sanitizer-$requested"
executable="$output.kexe"
compiler_log="$artifacts/compiler.log"
runtime_log="$artifacts/runtime.log"
symbols="$artifacts/symbols.txt"
disassembly="$artifacts/disassembly.txt"
result_tsv="$artifacts/result.tsv"
result_json="$artifacts/result.json"
mkdir -p "$artifacts"
rm -f "$output" "$executable" "$compiler_log" "$runtime_log" "$symbols" "$disassembly" "$result_tsv" "$result_json"

publish_result() {
    local status=$1 reason=$2
    printf 'sanitizer\tstatus\treason\n%s\t%s\t%s\n' "$requested" "$status" "$reason" >"$result_tsv"
    printf '{"sanitizer":"%s","status":"%s","reason":"%s"}\n' "$requested" "$status" "$reason" >"$result_json"
    echo "SANITIZER_RESULT sanitizer=$requested status=$status reason=$reason"
}

"$compiler" "$source" -target linux_x64 -memory-model arc -opt \
    "$compiler_option" -o "$output" >"$compiler_log" 2>&1
compile_status=$?
cat "$compiler_log"

unsupported_regex='sanitizer is unsupported|sanitizer is not supported|sanitizer is not supported yet|incorrect value.*sanitizer|unknown.*sanitizer'
if grep -Eiq "$unsupported_regex" "$compiler_log"; then
    publish_result UNSUPPORTED compiler_rejected_sanitizer
    exit 77
fi
if [[ $compile_status -ne 0 ]]; then
    publish_result FAIL compiler_failed_without_unsupported_diagnostic
    exit 1
fi
if [[ ! -x "$executable" && -x "$output" ]]; then
    executable=$output
fi
if [[ ! -x "$executable" ]]; then
    publish_result FAIL compiler_produced_no_executable
    exit 1
fi

if ! command -v readelf >/dev/null; then
    publish_result FAIL readelf_unavailable
    exit 1
fi
readelf -Ws "$executable" >"$symbols"
if ! grep -Eq "$symbol_regex" "$symbols"; then
    publish_result UNSUPPORTED no_instrumentation_symbols
    exit 77
fi
if [[ "$requested" == tsan ]] && ! grep -Eq '__interceptor_(malloc|pthread_create)' "$symbols"; then
    publish_result FAIL tsan_interceptors_missing_from_linked_executable
    exit 1
fi
if [[ "$requested" == ubsan ]]; then
    if ! command -v objdump >/dev/null; then
        publish_result FAIL objdump_unavailable
        exit 1
    fi
    objdump -d "$executable" >"$disassembly"
    if ! grep -Eq 'call[q]?[[:space:]]+.*<__ubsan_handle_' "$disassembly"; then
        publish_result UNSUPPORTED no_ubsan_instrumentation_calls
        exit 77
    fi
fi

runtime_command=("${runtime_env[@]}" "$executable")
if [[ "$requested" == tsan && "$(uname -s)" == Linux ]]; then
    # The LLVM 11 TSan runtime reserves a fixed shadow-memory range that can
    # collide with high-entropy ASLR on modern Linux kernels. Disabling ASLR
    # for this diagnostic process is the upstream-compatible workaround; it
    # does not affect the compiler output or non-TSan programs.
    if ! command -v setarch >/dev/null || ! setarch "$(uname -m)" -R true; then
        publish_result FAIL tsan_aslr_compatibility_unavailable
        exit 1
    fi
    echo "TSAN_ASLR_WORKAROUND=setarch_$(uname -m)_-R"
    runtime_command=(setarch "$(uname -m)" -R "${runtime_command[@]}")
fi

"${runtime_command[@]}" >"$runtime_log" 2>&1
runtime_status=$?
cat "$runtime_log"
if [[ $runtime_status -ne 0 ]]; then
    publish_result FAIL instrumented_runtime_failed
    exit 1
fi
if ! grep -Fq ARC_SANITIZER_OK "$runtime_log"; then
    publish_result FAIL fixture_success_marker_missing
    exit 1
fi

if [[ "$requested" == tsan ]]; then
    race_output="$artifacts/arc-tsan-race"
    race_executable="$race_output.kexe"
    race_compiler_log="$artifacts/race-compiler.log"
    race_runtime_log="$artifacts/race-runtime.log"
    "$compiler" "$race_source" -target linux_x64 -memory-model arc -opt \
        "$compiler_option" -o "$race_output" >"$race_compiler_log" 2>&1
    race_compile_status=$?
    cat "$race_compiler_log"
    if [[ $race_compile_status -ne 0 ]]; then
        publish_result FAIL intentional_race_fixture_compile_failed
        exit 1
    fi
    if [[ ! -x "$race_executable" && -x "$race_output" ]]; then
        race_executable=$race_output
    fi
    if [[ ! -x "$race_executable" ]]; then
        publish_result FAIL intentional_race_fixture_missing
        exit 1
    fi

    setarch "$(uname -m)" -R env \
        'TSAN_OPTIONS=halt_on_error=1:history_size=7:second_deadlock_stack=1' \
        "$race_executable" >"$race_runtime_log" 2>&1
    race_runtime_status=$?
    cat "$race_runtime_log"
    if [[ $race_runtime_status -eq 0 ]]; then
        publish_result FAIL intentional_data_race_was_not_detected
        exit 1
    fi
    if ! grep -Fq 'WARNING: ThreadSanitizer: data race' "$race_runtime_log"; then
        publish_result FAIL intentional_race_failed_without_tsan_report
        exit 1
    fi
fi

if [[ "$requested" == tsan ]]; then
    publish_result PASS instrumented_runtime_and_race_detection_passed_with_aslr_disabled
else
    publish_result PASS instrumented_compile_and_runtime_passed
fi
exit 0
