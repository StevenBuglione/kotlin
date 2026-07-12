#!/usr/bin/env bash
set -euo pipefail

profile=${1:-}
case "$profile" in
    smoke|stress|race|unowned-death|no-collector) ;;
    *) echo "usage: $0 smoke|stress|race|unowned-death|no-collector" >&2; exit 2 ;;
esac

root=$(git rev-parse --show-toplevel)
state=${ARC_RUN_STATE_DIR:?ARC_RUN_STATE_DIR is set by the durable remote runner}
dist=${ARC_DIST_DIR:-$root/kotlin-native/dist}
compiler="$dist/bin/konanc"
source="$root/tools/arc/fixtures/$profile.kt"
artifacts="$state/artifacts"
output="$artifacts/arc-$profile"
executable="$output.kexe"
compiler_log="$artifacts/compiler.log"

[[ -x "$compiler" ]] || {
    echo "ARC fixture requires a built Kotlin/Native distribution at $dist; run remote-dist first" >&2
    exit 1
}
[[ -f "$source" ]] || { echo "missing ARC fixture: $source" >&2; exit 1; }

mkdir -p "$artifacts"
rm -f "$output" "$executable" "$artifacts/max-rss-kib" "$compiler_log"

compiler_args=("$source" -target linux_x64 -memory-model arc -o "$output")
if [[ -n "${ARC_FIXTURE_SANITIZER:-}" ]]; then
    if [[ "$ARC_FIXTURE_SANITIZER" == undefined ]]; then
        compiler_args+=("-Xbinary=undefinedBehaviorSanitizer=true")
    else
        compiler_args+=("-Xbinary=sanitizer=$ARC_FIXTURE_SANITIZER")
    fi
    echo "ARC_FIXTURE_SANITIZER=$ARC_FIXTURE_SANITIZER"
fi
"$compiler" "${compiler_args[@]}" 2>&1 | tee "$compiler_log"
if [[ -n "${ARC_FIXTURE_SANITIZER:-}" ]] &&
        grep -Eiq 'sanitizer is unsupported|sanitizer is not supported' "$compiler_log"; then
    echo "requested $ARC_FIXTURE_SANITIZER sanitizer was not enabled by the Kotlin/Native compiler" >&2
    exit 1
fi
if [[ ! -x "$executable" && -x "$output" ]]; then
    executable=$output
fi
[[ -x "$executable" ]] || { echo "ARC fixture executable was not produced: $executable" >&2; exit 1; }

if [[ "$profile" == no-collector ]]; then
    python3 "$root/tools/arc/no_collector_symbols.py" \
        "$executable" \
        --output "$artifacts/no-collector-symbols.txt"
fi

if [[ -n "${ARC_FIXTURE_SANITIZER:-}" ]]; then
    command -v readelf >/dev/null || { echo "readelf is required to verify sanitizer instrumentation" >&2; exit 1; }
    case "$ARC_FIXTURE_SANITIZER" in
        thread) sanitizer_symbol='__tsan_(init|func_entry|read|write)' ;;
        address) sanitizer_symbol='__asan_(init|report|load|store)' ;;
        undefined) sanitizer_symbol='__ubsan_handle_' ;;
        *) echo "no instrumentation proof rule for sanitizer $ARC_FIXTURE_SANITIZER" >&2; exit 1 ;;
    esac
    readelf -Ws "$executable" >"$artifacts/sanitizer-symbols.txt"
    grep -Eq "$sanitizer_symbol" "$artifacts/sanitizer-symbols.txt" || {
        echo "requested $ARC_FIXTURE_SANITIZER sanitizer produced no instrumentation symbols" >&2
        exit 1
    }
    if [[ "$ARC_FIXTURE_SANITIZER" == undefined ]]; then
        command -v objdump >/dev/null || { echo "objdump is required to verify UBSAN call sites" >&2; exit 1; }
        objdump -d "$executable" >"$artifacts/sanitizer-disassembly.txt"
        grep -Eq 'call[q]?[[:space:]]+.*<__ubsan_handle_' "$artifacts/sanitizer-disassembly.txt" || {
            echo "requested undefined sanitizer produced no instrumentation calls" >&2
            exit 1
        }
    fi
fi

runtime_command=("$executable")
if [[ "${ARC_FIXTURE_SANITIZER:-}" == thread && "$(uname -s)" == Linux ]]; then
    # LLVM 11 TSan needs its fixed shadow-memory range kept clear on modern
    # Linux kernels. Scope the ASLR change to the instrumented child process.
    if ! command -v setarch >/dev/null || ! setarch "$(uname -m)" -R true; then
        echo "LLVM 11 TSan requires setarch permission to reserve shadow memory" >&2
        exit 1
    fi
    echo "TSAN_ASLR_WORKAROUND=setarch_$(uname -m)_-R"
    runtime_command=(setarch "$(uname -m)" -R "$executable")
fi

if [[ "$profile" == unowned-death ]]; then
    expected_diagnostic='Uncaught Kotlin exception: kotlin.IllegalStateException: attempted to access an expired @ArcUnowned reference'
    set +e
    "${runtime_command[@]}" >"$artifacts/runtime.log" 2>&1
    exit_code=$?
    set -e
    cat "$artifacts/runtime.log"
    (( exit_code != 0 )) || { echo "expired @ArcUnowned access unexpectedly returned successfully" >&2; exit 1; }
    grep -Fxq "$expected_diagnostic" "$artifacts/runtime.log" || {
        echo "expired @ArcUnowned access did not emit its exact typed lifetime diagnostic" >&2
        exit 1
    }
    if grep -Fq 'expired @ArcUnowned access was catchable' "$artifacts/runtime.log" ||
            grep -Fq 'expired @ArcUnowned access unexpectedly survived' "$artifacts/runtime.log"; then
        echo "expired @ArcUnowned access continued after its lifetime failure" >&2
        exit 1
    fi
    echo "ARC_UNOWNED_DEATH_OK exitCode=$exit_code"
elif [[ "$profile" == stress ]]; then
    command -v /usr/bin/time >/dev/null || { echo "/usr/bin/time is required for the ARC RSS bound" >&2; exit 1; }
    /usr/bin/time -f '%M' -o "$artifacts/max-rss-kib" "${runtime_command[@]}"
    max_rss_kib=$(cat "$artifacts/max-rss-kib")
    limit_kib=${ARC_STRESS_MAX_RSS_KIB:-524288}
    [[ "$max_rss_kib" =~ ^[0-9]+$ ]] || { echo "invalid maximum RSS: $max_rss_kib" >&2; exit 1; }
    (( max_rss_kib <= limit_kib )) || {
        echo "ARC stress exceeded RSS bound: ${max_rss_kib} KiB > ${limit_kib} KiB" >&2
        exit 1
    }
    echo "ARC_STRESS_MAX_RSS_KIB=$max_rss_kib"
else
    "${runtime_command[@]}"
fi
