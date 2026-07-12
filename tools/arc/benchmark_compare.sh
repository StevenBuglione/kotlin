#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

root=$(git rev-parse --show-toplevel)
state=${ARC_RUN_STATE_DIR:?ARC_RUN_STATE_DIR is set by the durable remote runner}
candidate_dist=${ARC_DIST_DIR:-$root/kotlin-native/dist}
baseline_source=${ARC_BENCH_BASELINE_SOURCE:-${root}-baseline-v1.9.10}
baseline_dist=${ARC_BENCH_BASELINE_DIST:-$baseline_source/kotlin-native/dist}
expected_baseline=3db61efe5e892bf27115f1ebcab957d903067ed4
source="$root/tools/arc/fixtures/benchmark.kt"
reporter="$root/tools/arc/benchmark_report.py"
artifacts="$state/artifacts"
repetitions=${ARC_BENCH_REPETITIONS:-5}
warmups=${ARC_BENCH_WARMUPS:-1}
compile_repetitions=${ARC_BENCH_COMPILE_REPETITIONS:-3}

[[ "$repetitions" =~ ^[0-9]+$ && $repetitions -ge 3 && $((repetitions % 2)) -eq 1 ]] || {
    echo "ARC_BENCH_REPETITIONS must be an odd integer of at least 3" >&2
    exit 2
}
[[ "$warmups" =~ ^[0-9]+$ ]] || { echo "ARC_BENCH_WARMUPS must be a nonnegative integer" >&2; exit 2; }
[[ "$compile_repetitions" =~ ^[0-9]+$ && $compile_repetitions -ge 3 && $((compile_repetitions % 2)) -eq 1 ]] || {
    echo "ARC_BENCH_COMPILE_REPETITIONS must be an odd integer of at least 3" >&2
    exit 2
}
[[ -f "$source" && -x /usr/bin/time ]] || { echo "benchmark fixture and /usr/bin/time are required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }
objdump=${ARC_BENCH_OBJDUMP:-objdump}
command -v "$objdump" >/dev/null || { echo "$objdump is required for ownership callsite counts" >&2; exit 1; }

candidate_compiler="$candidate_dist/bin/konanc"
baseline_compiler="$baseline_dist/bin/konanc"
[[ -x "$candidate_compiler" ]] || { echo "candidate dist is missing; run arc-bench-candidate first" >&2; exit 1; }
[[ -x "$baseline_compiler" ]] || { echo "baseline dist is missing; run arc-bench-baseline first" >&2; exit 1; }
[[ "$(readlink -f "$candidate_dist")" != "$(readlink -f "$baseline_dist")" ]] || {
    echo "baseline and candidate distributions resolve to the same path" >&2
    exit 1
}
[[ "$(readlink -f "$baseline_source")" != "$(readlink -f "$root")" ]] || {
    echo "baseline source resolves to the candidate checkout" >&2
    exit 1
}

candidate_head=$(git -C "$root" rev-parse HEAD)
candidate_tree=$(git -C "$root" rev-parse HEAD^{tree})
baseline_head=$(git -C "$baseline_source" rev-parse HEAD)
baseline_tree=$(git -C "$baseline_source" rev-parse HEAD^{tree})
[[ "$baseline_head" == "$expected_baseline" ]] || {
    echo "baseline HEAD is $baseline_head; exact v1.9.10 $expected_baseline is required" >&2
    exit 1
}
[[ -z "$(git -C "$baseline_source" status --porcelain --untracked-files=normal)" ]] || {
    echo "baseline checkout is dirty; refusing benchmark" >&2
    exit 1
}

validate_provenance() {
    local path=$1 role=$2 commit=$3 tree=$4 source=$5
    python3 - "$path" "$role" "$commit" "$tree" "$source" <<'PY'
import json
import pathlib
import sys

path, role, commit, tree, source = sys.argv[1:]
try:
    value = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
except (OSError, ValueError) as error:
    raise SystemExit(f"invalid benchmark dist provenance {path}: {error}")
if (value.get("role"), value.get("commit"), value.get("tree"), value.get("source")) != (role, commit, tree, source):
    raise SystemExit(
        f"benchmark provenance mismatch in {path}: "
        f"found role/commit/tree/source={value.get('role')!r}/{value.get('commit')!r}/"
        f"{value.get('tree')!r}/{value.get('source')!r}"
    )
PY
}
validate_provenance "$candidate_dist/.arc-benchmark-provenance.json" candidate "$candidate_head" "$candidate_tree" "$root"
validate_provenance "$baseline_dist/.arc-benchmark-provenance.json" baseline-strict "$expected_baseline" "$baseline_tree" "$baseline_source"

mkdir -p "$artifacts"
rm -f "$artifacts"/raw.tsv "$artifacts"/raw.json "$artifacts"/static.tsv \
    "$artifacts"/summary.tsv "$artifacts"/summary.json "$artifacts"/comparison.md \
    "$artifacts"/*-benchmark "$artifacts"/*-benchmark.kexe "$artifacts"/*.time \
    "$artifacts"/*.log "$artifacts"/*.disassembly
printf 'model\tscenario\trepetition\telapsed_seconds\tthroughput_ops_per_second\tmax_rss_kib\toperations\tlogical_allocations\n' \
    >"$artifacts/raw.tsv"
printf 'model\tcompile_seconds\tcompile_max_rss_kib\tbinary_bytes\tretain_callsites\trelease_callsites\tallocation_callsites\tcompiler_commit\tmemory_model\tcompiler\n' \
    >"$artifacts/static.tsv"
printf 'model\trepetition\tcompile_seconds\tcompile_max_rss_kib\n' >"$artifacts/compile-raw.tsv"

run_prefix=()
if command -v taskset >/dev/null; then
    allowed=$(taskset -pc $$ 2>/dev/null | sed 's/.*: //' | tr -d ' ')
    cpu=${ARC_BENCH_CPU:-${allowed%%,*}}
    cpu=${cpu%%-*}
    if [[ "$cpu" =~ ^[0-9]+$ ]] && taskset -c "$cpu" true >/dev/null 2>&1; then
        run_prefix=(taskset -c "$cpu")
    elif [[ -n "${ARC_BENCH_CPU:-}" ]]; then
        echo "requested ARC_BENCH_CPU=$ARC_BENCH_CPU is unavailable" >&2
        exit 2
    else
        echo "warning: reliable CPU pinning is unavailable; continuing unpinned" >&2
    fi
fi

common_flags=(-target linux_x64 -opt)
compile_one() {
    local label=$1 compiler=$2 memory_model=$3 repetition=$4
    local output="$artifacts/$label-benchmark"
    local timing="$artifacts/$label-compile.time"
    local log="$artifacts/$label-compiler.log"
    if ! /usr/bin/time -f $'%e\t%M' -o "$timing" \
            "$compiler" "$source" "${common_flags[@]}" -memory-model "$memory_model" -o "$output" >"$log" 2>&1; then
        cat "$log" >&2
        echo "$label benchmark compilation failed" >&2
        exit 1
    fi
    local executable="$output.kexe"
    [[ -x "$executable" ]] || executable=$output
    [[ -x "$executable" ]] || { echo "$label compiler produced no executable" >&2; exit 1; }
    local compile_seconds compile_rss
    read -r compile_seconds compile_rss <"$timing"
    printf '%s\t%s\t%s\t%s\n' "$label" "$repetition" "$compile_seconds" "$compile_rss" >>"$artifacts/compile-raw.tsv"
}

median_compile() {
    local label=$1 column=$2
    awk -F '\t' -v label="$label" -v column="$column" 'NR > 1 && $1 == label { print $column }' \
        "$artifacts/compile-raw.tsv" | sort -n | awk '{ values[NR] = $1 } END { print values[(NR + 1) / 2] }'
}

finalize_compile() {
    local label=$1 compiler=$2 memory_model=$3 commit=$4
    local executable="$artifacts/$label-benchmark.kexe"
    [[ -x "$executable" ]] || executable="$artifacts/$label-benchmark"
    local compile_seconds compile_rss binary_bytes retain_calls release_calls allocation_calls
    compile_seconds=$(median_compile "$label" 3)
    compile_rss=$(median_compile "$label" 4)
    binary_bytes=$(stat -c '%s' "$executable")
    "$objdump" -d -C "$executable" >"$artifacts/$label.disassembly"
    read -r retain_calls release_calls allocation_calls < <(python3 "$reporter" calls "$artifacts/$label.disassembly")
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$label" "$compile_seconds" "$compile_rss" "$binary_bytes" "$retain_calls" "$release_calls" "$allocation_calls" \
        "$commit" "$memory_model" "$compiler" >>"$artifacts/static.tsv"
}

# The only varying compiler flag is the memory model required by each independently built dist.
# In particular, candidate strict is never compiled: the strict executable always comes from v1.9.10.
for ((iteration = 1; iteration <= compile_repetitions; iteration++)); do
    if (( iteration % 2 == 1 )); then
        compile_one baseline-strict "$baseline_compiler" strict "$iteration"
        compile_one candidate-arc "$candidate_compiler" arc "$iteration"
    else
        compile_one candidate-arc "$candidate_compiler" arc "$iteration"
        compile_one baseline-strict "$baseline_compiler" strict "$iteration"
    fi
done
finalize_compile baseline-strict "$baseline_compiler" strict "$baseline_head"
finalize_compile candidate-arc "$candidate_compiler" arc "$candidate_head"

default_scenarios='allocation destruction fields arrays strings virtual-dispatch call-arguments closures exceptions coroutines workers atomics platform-c-interop bounded-cycles'
scenario_selection=${ARC_BENCH_SCENARIOS:-$default_scenarios}
read -r -a scenarios <<<"${scenario_selection//,/ }"
[[ ${#scenarios[@]} -gt 0 ]] || { echo "ARC_BENCH_SCENARIOS selected no scenarios" >&2; exit 2; }
for scenario in "${scenarios[@]}"; do
    [[ " $default_scenarios " == *" $scenario "* ]] || { echo "unknown benchmark scenario: $scenario" >&2; exit 2; }
    scenario_prefix=("${run_prefix[@]}")
    # Pin single-threaded scenarios for lower scheduler noise. Worker throughput intentionally retains
    # the machine's inherited CPU set so the worker scenario continues to measure real parallelism.
    [[ "$scenario" == workers ]] && scenario_prefix=()
    expected="$artifacts/$scenario.expected"
    rm -f "$expected"
    for ((iteration = 1; iteration <= warmups; iteration++)); do
        (( iteration % 2 == 1 )) && labels=(baseline-strict candidate-arc) || labels=(candidate-arc baseline-strict)
        for label in "${labels[@]}"; do
            executable="$artifacts/$label-benchmark.kexe"
            [[ -x "$executable" ]] || executable="$artifacts/$label-benchmark"
            "${scenario_prefix[@]}" "$executable" "$scenario" >"$artifacts/$label-$scenario-warmup-$iteration.log"
        done
    done
    for ((iteration = 1; iteration <= repetitions; iteration++)); do
        (( iteration % 2 == 1 )) && labels=(baseline-strict candidate-arc) || labels=(candidate-arc baseline-strict)
        for label in "${labels[@]}"; do
            executable="$artifacts/$label-benchmark.kexe"
            [[ -x "$executable" ]] || executable="$artifacts/$label-benchmark"
            timing="$artifacts/$label-$scenario-$iteration.time"
            runtime_log="$artifacts/$label-$scenario-$iteration.log"
            started_ns=$(date +%s%N)
            /usr/bin/time -f '%M' -o "$timing" "${scenario_prefix[@]}" "$executable" "$scenario" >"$runtime_log"
            finished_ns=$(date +%s%N)
            runtime_output=$(cat "$runtime_log")
            if [[ ! "$runtime_output" =~ ^ARC_BENCH_OK[[:space:]]scenario=$scenario[[:space:]]checksum=-?[0-9]+[[:space:]]operations=([0-9]+)[[:space:]]allocations=([0-9]+)$ ]]; then
                echo "$label/$scenario emitted an invalid result: $runtime_output" >&2
                exit 1
            fi
            operations=${BASH_REMATCH[1]}
            allocations=${BASH_REMATCH[2]}
            if [[ ! -f "$expected" ]]; then
                printf '%s\n' "$runtime_output" >"$expected"
            elif [[ "$runtime_output" != "$(cat "$expected")" ]]; then
                echo "observable output differs for $label/$scenario" >&2
                exit 1
            fi
            rss=$(cat "$timing")
            elapsed=$(awk -v nanoseconds="$((finished_ns - started_ns))" 'BEGIN { printf "%.9f", nanoseconds / 1000000000 }')
            [[ "$elapsed" =~ ^[0-9]+([.][0-9]+)?$ && "$rss" =~ ^[0-9]+$ ]] || {
                echo "invalid timing result for $label/$scenario: elapsed=$elapsed rss=$rss" >&2
                exit 1
            }
            throughput=$(awk -v operations="$operations" -v elapsed="$elapsed" \
                'BEGIN { if (elapsed <= 0) exit 1; printf "%.6f", operations / elapsed }') || {
                echo "$label/$scenario completed below /usr/bin/time resolution; increase fixture work" >&2
                exit 1
            }
            printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
                "$label" "$scenario" "$iteration" "$elapsed" "$throughput" "$rss" "$operations" "$allocations" \
                >>"$artifacts/raw.tsv"
        done
    done
done

python3 - "$artifacts/inputs.json" "$artifacts/hardware.json" "$source" "$candidate_head" "$baseline_head" \
    "$repetitions" "$warmups" "$compile_repetitions" "${run_prefix[*]}" "${common_flags[*]}" "${scenarios[*]}" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import platform
import sys

inputs_path, hardware_path, source_path, candidate, baseline, repetitions, warmups, compile_repetitions, affinity, flags, scenarios = sys.argv[1:]
source = Path(source_path)
inputs = {
    "candidateCommit": candidate,
    "baselineCommit": baseline,
    "baselineTag": "v1.9.10",
    "candidateMemoryModel": "arc",
    "baselineMemoryModel": "strict",
    "commonCompilerFlags": flags.split(),
    "fixture": "tools/arc/fixtures/benchmark.kt",
    "fixtureSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "repetitions": int(repetitions),
    "warmups": int(warmups),
    "compileRepetitions": int(compile_repetitions),
    "executionPrefix": affinity.split(),
    "scenarios": scenarios.split(),
    "logicalAllocationDefinition": "Fixture-declared source-level object, array, closure, continuation, and exception creations per invocation.",
    "emittedCallsiteMethod": "Deterministic objdump -d -C call-target count for Update*Ref, Set/Zero/ReleaseHeapRef, LeaveFrame ownership operations, and Alloc*Instance operations; these are linked-binary callsites, not runtime event counts.",
    "optimizerEliminationGate": "Separate curated compiler corpus must eliminate at least 90%; linked-binary callsites are not used as its proxy.",
    "thresholdEnvironment": {
        key: os.environ.get(key, default)
        for key, default in {
            "ARC_BENCH_SCENARIO_REGRESSION_PERCENT": "5",
            "ARC_BENCH_THROUGHPUT_FLOOR_PERCENT": "100",
            "ARC_BENCH_RSS_LIMIT_PERCENT": "5",
            "ARC_BENCH_SIZE_LIMIT_PERCENT": "5",
            "ARC_BENCH_ENFORCE": "1",
        }.items()
    },
}
cpu_model = "unknown"
for line in Path("/proc/cpuinfo").read_text(encoding="utf-8", errors="replace").splitlines():
    if line.lower().startswith("model name"):
        cpu_model = line.split(":", 1)[1].strip()
        break
mem_total_kib = None
for line in Path("/proc/meminfo").read_text(encoding="utf-8", errors="replace").splitlines():
    if line.startswith("MemTotal:"):
        mem_total_kib = int(line.split()[1])
        break
hardware = {
    "hostname": platform.node(),
    "platform": platform.platform(),
    "kernel": platform.release(),
    "machine": platform.machine(),
    "cpuModel": cpu_model,
    "logicalCpuCount": os.cpu_count(),
    "memoryTotalKiB": mem_total_kib,
    "processCpuAffinity": sorted(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else "unavailable",
    "loadAverage": Path("/proc/loadavg").read_text(encoding="utf-8", errors="replace").strip() if Path("/proc/loadavg").exists() else "unavailable",
    "uptime": Path("/proc/uptime").read_text(encoding="utf-8", errors="replace").strip() if Path("/proc/uptime").exists() else "unavailable",
}
governors = {}
for governor in sorted(Path("/sys/devices/system/cpu").glob("cpu[0-9]*/cpufreq/scaling_governor")):
    try:
        governors[governor.parts[-3]] = governor.read_text(encoding="utf-8").strip()
    except OSError:
        governors[governor.parts[-3]] = "unavailable"
hardware["cpuScalingGovernors"] = governors or "unavailable"
Path(inputs_path).write_text(json.dumps(inputs, indent=2, sort_keys=True) + "\n", encoding="utf-8")
Path(hardware_path).write_text(json.dumps(hardware, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

set +e
python3 "$reporter" report "$artifacts/raw.tsv" "$artifacts/compile-raw.tsv" "$artifacts/static.tsv" "$artifacts"
report_status=$?
set -e

wave="$artifacts/wave"
rm -rf "$wave"
mkdir -p "$wave"
cp "$artifacts"/inputs.json "$artifacts"/hardware.json "$artifacts"/raw.tsv "$artifacts"/raw.json "$artifacts"/compile-raw.tsv \
    "$artifacts"/static.tsv "$artifacts"/summary.tsv "$artifacts"/summary.json "$artifacts"/comparison.md "$wave/"
cp "$candidate_dist/.arc-benchmark-provenance.json" "$wave/candidate-provenance.json"
cp "$baseline_dist/.arc-benchmark-provenance.json" "$wave/baseline-provenance.json"
echo "ARC_BENCH_WAVE_READY path=$wave status=$report_status"
exit "$report_status"
