#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

root=$(git rev-parse --show-toplevel)
state=${ARC_RUN_STATE_DIR:?ARC_RUN_STATE_DIR is set by the durable remote runner}
dist=${ARC_DIST_DIR:-$root/kotlin-native/dist}
compiler="$dist/bin/konanc"
source="$root/tools/arc/fixtures/benchmark.kt"
artifacts="$state/artifacts"
repetitions=${ARC_BENCH_REPETITIONS:-5}
warmups=${ARC_BENCH_WARMUPS:-1}
throughput_floor=${ARC_BENCH_THROUGHPUT_FLOOR_PERCENT:-100}
individual_limit=${ARC_BENCH_INDIVIDUAL_REGRESSION_PERCENT:-5}
rss_limit=${ARC_BENCH_RSS_LIMIT_PERCENT:-5}
size_limit=${ARC_BENCH_SIZE_LIMIT_PERCENT:-5}
enforce=${ARC_BENCH_ENFORCE:-1}

[[ -x "$compiler" ]] || {
    echo "ARC benchmark comparison requires a built Kotlin/Native distribution; run remote-dist first" >&2
    exit 1
}
[[ -x /usr/bin/time ]] || { echo "/usr/bin/time is required" >&2; exit 1; }
[[ "$repetitions" =~ ^[0-9]+$ && $repetitions -ge 3 && $((repetitions % 2)) -eq 1 ]] || {
    echo "ARC_BENCH_REPETITIONS must be an odd integer of at least 3" >&2
    exit 2
}
[[ "$warmups" =~ ^[0-9]+$ ]] || { echo "ARC_BENCH_WARMUPS must be a nonnegative integer" >&2; exit 2; }
[[ "$enforce" == 0 || "$enforce" == 1 ]] || { echo "ARC_BENCH_ENFORCE must be 0 or 1" >&2; exit 2; }
for threshold in "$throughput_floor" "$individual_limit" "$rss_limit" "$size_limit"; do
    [[ "$threshold" =~ ^[0-9]+([.][0-9]+)?$ ]] || { echo "benchmark thresholds must be nonnegative numbers" >&2; exit 2; }
done

mkdir -p "$artifacts"
rm -f "$artifacts"/arc-benchmark* "$artifacts"/strict-benchmark* \
    "$artifacts"/raw.tsv "$artifacts"/summary.tsv "$artifacts"/summary.json
printf 'model\tscenario\trepetition\telapsed_seconds\tmax_rss_kib\n' >"$artifacts/raw.tsv"

for model in strict arc; do
    output="$artifacts/$model-benchmark"
    if ! "$compiler" "$source" -target linux_x64 -memory-model "$model" -opt -o "$output" \
            >"$artifacts/$model-compiler.log" 2>&1; then
        cat "$artifacts/$model-compiler.log" >&2
        echo "$model benchmark compilation failed" >&2
        exit 1
    fi
    executable="$output.kexe"
    [[ -x "$executable" ]] || executable=$output
    [[ -x "$executable" ]] || { echo "$model benchmark executable was not produced" >&2; exit 1; }
    stat -c '%s' "$executable" >"$artifacts/$model-binary-bytes"
done

for scenario in allocation destruction; do
    expected_output=
    for model in strict arc; do
        executable="$artifacts/$model-benchmark.kexe"
        [[ -x "$executable" ]] || executable="$artifacts/$model-benchmark"
        for ((iteration = 1; iteration <= warmups; iteration++)); do
            "$executable" "$scenario" >"$artifacts/$model-$scenario-warmup-$iteration.log"
        done
        for ((iteration = 1; iteration <= repetitions; iteration++)); do
            timing="$artifacts/$model-$scenario-$iteration.time"
            runtime_log="$artifacts/$model-$scenario-$iteration.log"
            /usr/bin/time -f $'%e\t%M' -o "$timing" "$executable" "$scenario" >"$runtime_log"
            runtime_output=$(cat "$runtime_log")
            [[ "$runtime_output" == ARC_BENCH_OK* ]] || {
                echo "$model/$scenario did not emit its success marker" >&2
                exit 1
            }
            if [[ -z "$expected_output" ]]; then
                expected_output=$runtime_output
            elif [[ "$runtime_output" != "$expected_output" ]]; then
                echo "observable output differs for $model/$scenario" >&2
                exit 1
            fi
            read -r elapsed rss <"$timing"
            [[ "$elapsed" =~ ^[0-9]+([.][0-9]+)?$ && "$rss" =~ ^[0-9]+$ ]] || {
                echo "invalid timing result for $model/$scenario: $elapsed $rss" >&2
                exit 1
            }
            printf '%s\t%s\t%s\t%s\t%s\n' "$model" "$scenario" "$iteration" "$elapsed" "$rss" \
                >>"$artifacts/raw.tsv"
        done
    done
done

median_for() {
    local model=$1 scenario=$2 column=$3
    awk -F '\t' -v model="$model" -v scenario="$scenario" -v column="$column" \
        'NR > 1 && $1 == model && $2 == scenario { print $column }' "$artifacts/raw.tsv" |
        sort -n | awk '{ value[NR] = $1 } END { print value[(NR + 1) / 2] }'
}

max_for_model() {
    local model=$1
    awk -F '\t' -v model="$model" 'NR > 1 && $1 == model && $5 > max { max = $5 } END { print max + 0 }' \
        "$artifacts/raw.tsv"
}

strict_allocation=$(median_for strict allocation 4)
arc_allocation=$(median_for arc allocation 4)
strict_destruction=$(median_for strict destruction 4)
arc_destruction=$(median_for arc destruction 4)
strict_geomean=$(awk -v a="$strict_allocation" -v b="$strict_destruction" 'BEGIN { print sqrt(a * b) }')
arc_geomean=$(awk -v a="$arc_allocation" -v b="$arc_destruction" 'BEGIN { print sqrt(a * b) }')
throughput_percent=$(awk -v strict="$strict_geomean" -v arc="$arc_geomean" 'BEGIN { printf "%.3f", 100 * strict / arc }')
allocation_regression=$(awk -v strict="$strict_allocation" -v arc="$arc_allocation" 'BEGIN { printf "%.3f", 100 * (arc / strict - 1) }')
destruction_regression=$(awk -v strict="$strict_destruction" -v arc="$arc_destruction" 'BEGIN { printf "%.3f", 100 * (arc / strict - 1) }')
strict_rss=$(max_for_model strict)
arc_rss=$(max_for_model arc)
rss_delta=$(awk -v strict="$strict_rss" -v arc="$arc_rss" 'BEGIN { printf "%.3f", 100 * (arc / strict - 1) }')
strict_size=$(cat "$artifacts/strict-binary-bytes")
arc_size=$(cat "$artifacts/arc-binary-bytes")
size_delta=$(awk -v strict="$strict_size" -v arc="$arc_size" 'BEGIN { printf "%.3f", 100 * (arc / strict - 1) }')

printf 'metric\tstrict\tarc\tdelta_or_ratio_percent\n' >"$artifacts/summary.tsv"
printf 'allocation_median_seconds\t%s\t%s\t%s\n' "$strict_allocation" "$arc_allocation" "$allocation_regression" >>"$artifacts/summary.tsv"
printf 'destruction_median_seconds\t%s\t%s\t%s\n' "$strict_destruction" "$arc_destruction" "$destruction_regression" >>"$artifacts/summary.tsv"
printf 'throughput_geomean\t%s\t%s\t%s\n' "$strict_geomean" "$arc_geomean" "$throughput_percent" >>"$artifacts/summary.tsv"
printf 'peak_rss_kib\t%s\t%s\t%s\n' "$strict_rss" "$arc_rss" "$rss_delta" >>"$artifacts/summary.tsv"
printf 'binary_bytes\t%s\t%s\t%s\n' "$strict_size" "$arc_size" "$size_delta" >>"$artifacts/summary.tsv"
printf '{"throughputPercent":%s,"allocationRegressionPercent":%s,"destructionRegressionPercent":%s,"strictPeakRssKiB":%s,"arcPeakRssKiB":%s,"rssDeltaPercent":%s,"strictBinaryBytes":%s,"arcBinaryBytes":%s,"sizeDeltaPercent":%s}\n' \
    "$throughput_percent" "$allocation_regression" "$destruction_regression" "$strict_rss" "$arc_rss" \
    "$rss_delta" "$strict_size" "$arc_size" "$size_delta" >"$artifacts/summary.json"
cat "$artifacts/summary.tsv"

failures=0
check_upper_bound() {
    local label=$1 actual=$2 limit=$3
    if ! awk -v actual="$actual" -v limit="$limit" 'BEGIN { exit !(actual <= limit) }'; then
        echo "ARC benchmark gate failed: $label ${actual}% > ${limit}%" >&2
        failures=1
    fi
}
if ! awk -v actual="$throughput_percent" -v floor="$throughput_floor" 'BEGIN { exit !(actual >= floor) }'; then
    echo "ARC benchmark gate failed: throughput ${throughput_percent}% < ${throughput_floor}%" >&2
    failures=1
fi
check_upper_bound allocation_regression "$allocation_regression" "$individual_limit"
check_upper_bound destruction_regression "$destruction_regression" "$individual_limit"
check_upper_bound peak_rss_delta "$rss_delta" "$rss_limit"
check_upper_bound binary_size_delta "$size_delta" "$size_limit"

if [[ $enforce -eq 0 ]]; then
    echo "ARC_BENCH_ENFORCE=0: results captured without enforcing release thresholds"
    exit 0
fi
exit "$failures"
