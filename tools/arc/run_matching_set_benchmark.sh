#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <parent-artifact-or-library> <candidate-artifact-or-library>" >&2
    exit 2
fi

parent=$1
candidate=$2
iterations=${ARC_MATCHING_SET_BENCH_ITERATIONS:-100000000}
repetitions=${ARC_MATCHING_SET_BENCH_REPETITIONS:-25}
cpu=${ARC_MATCHING_SET_BENCH_CPU:-4}
driver=${ARC_MATCHING_SET_BENCH_DRIVER:-}

((iterations % 16 == 0)) || {
    echo "iterations must be divisible by 16 for PID-phase-independent checksums" >&2
    exit 2
}

if [[ -n "$driver" ]]; then
    [[ -x "$driver" ]] || { echo "driver is not executable: $driver" >&2; exit 2; }
    for library in "$parent" "$candidate"; do
        [[ -r "$library" ]] || { echo "library is not readable: $library" >&2; exit 2; }
    done
else
    for binary in "$parent" "$candidate"; do
        [[ -x "$binary" ]] || { echo "not executable: $binary" >&2; exit 2; }
    done
fi

run_benchmark() {
    local binary=$1
    local count=$2
    if [[ -n "$driver" ]]; then
        taskset -c "$cpu" "$driver" "$binary" "$count"
    else
        taskset -c "$cpu" "$binary" "$count"
    fi
}

time_benchmark() {
    local model=$1
    local repetition=$2
    local binary=$3
    local rss_file
    local output
    local elapsed_ns
    local checksum
    local max_rss
    rss_file=$(mktemp)
    if [[ -n "$driver" ]]; then
        output=$(/usr/bin/time -f '%M' -o "$rss_file" \
            taskset -c "$cpu" "$driver" "$binary" "$iterations")
        elapsed_ns=$(sed -n 's/.*elapsed_ns=\([0-9][0-9]*\).*/\1/p' <<<"$output")
        checksum=$(sed -n 's/.*checksum=\([0-9][0-9]*\).*/\1/p' <<<"$output")
    else
        local started
        local finished
        started=$(date +%s%N)
        /usr/bin/time -f '%M' -o "$rss_file" \
            taskset -c "$cpu" "$binary" "$iterations" >/dev/null
        finished=$(date +%s%N)
        elapsed_ns=$((finished - started))
        checksum=-
    fi
    max_rss=$(cat "$rss_file")
    rm -f "$rss_file"
    [[ -n "$elapsed_ns" && -n "$checksum" && -n "$max_rss" ]] || {
        echo "benchmark did not produce complete timing data" >&2
        exit 1
    }
    printf '%s\t%s\t%s\t%s\t%s\n' "$model" "$repetition" "$elapsed_ns" "$max_rss" "$checksum"
}

# Warm both instruction/data paths before alternating measurements on one fixed CPU.
run_benchmark "$parent" 8000000 >/dev/null
run_benchmark "$candidate" 8000000 >/dev/null

printf 'model\trepetition\telapsed_ns\tmax_rss_kib\tchecksum\n'
for ((repetition = 1; repetition <= repetitions; repetition++)); do
    if ((repetition % 2 == 1)); then
        time_benchmark parent "$repetition" "$parent"
        time_benchmark candidate "$repetition" "$candidate"
    else
        time_benchmark candidate "$repetition" "$candidate"
        time_benchmark parent "$repetition" "$parent"
    fi
done
