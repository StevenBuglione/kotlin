#!/usr/bin/env bash
set -euo pipefail

profile=${1:-}
case "$profile" in
    smoke|stress|race|unowned-death) ;;
    *) echo "usage: $0 smoke|stress|race|unowned-death" >&2; exit 2 ;;
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

if [[ "$profile" == unowned-death ]]; then
    set +e
    "$executable" >"$artifacts/runtime.log" 2>&1
    exit_code=$?
    set -e
    cat "$artifacts/runtime.log"
    (( exit_code != 0 )) || { echo "expired @ArcUnowned access unexpectedly returned successfully" >&2; exit 1; }
    grep -Fq 'attempted to access an expired @ArcUnowned reference' "$artifacts/runtime.log" || {
        echo "expired @ArcUnowned access did not emit its lifetime diagnostic" >&2
        exit 1
    }
    echo "ARC_UNOWNED_DEATH_OK exitCode=$exit_code"
elif [[ "$profile" == stress ]]; then
    command -v /usr/bin/time >/dev/null || { echo "/usr/bin/time is required for the ARC RSS bound" >&2; exit 1; }
    /usr/bin/time -f '%M' -o "$artifacts/max-rss-kib" "$executable"
    max_rss_kib=$(cat "$artifacts/max-rss-kib")
    limit_kib=${ARC_STRESS_MAX_RSS_KIB:-524288}
    [[ "$max_rss_kib" =~ ^[0-9]+$ ]] || { echo "invalid maximum RSS: $max_rss_kib" >&2; exit 1; }
    (( max_rss_kib <= limit_kib )) || {
        echo "ARC stress exceeded RSS bound: ${max_rss_kib} KiB > ${limit_kib} KiB" >&2
        exit 1
    }
    echo "ARC_STRESS_MAX_RSS_KIB=$max_rss_kib"
else
    "$executable"
fi
