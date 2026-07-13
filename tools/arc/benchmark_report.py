#!/usr/bin/env python3
"""Analyze ARC benchmark artifacts and enforce release-quality comparison gates."""

from __future__ import annotations

import csv
import json
import math
import os
from pathlib import Path
import re
import statistics
import sys


BASELINE = "baseline-strict"
CANDIDATE = "candidate-arc"
CALL_RE = re.compile(r"\bcallq?\b.*<([^>]+)>", re.IGNORECASE)
RETAIN_TARGETS = re.compile(r"(Update(Stack|Return)Ref|SetHeapRef|SwapHeapRef|ReadHeapRef)", re.IGNORECASE)
RELEASE_TARGETS = re.compile(
    r"(Update(Stack|Return)Ref|SetHeapRef|ZeroHeapRef|ReleaseHeapRef|LeaveFrame(?:Arc)?)",
    re.IGNORECASE,
)
ALLOCATION_TARGETS = re.compile(r"Alloc(?:Array)?Instance", re.IGNORECASE)


def emitted_calls(disassembly: str) -> tuple[int, int, int]:
    retain = release = allocation = 0
    for line in disassembly.splitlines():
        match = CALL_RE.search(line)
        if not match:
            continue
        target = match.group(1)
        retain += bool(RETAIN_TARGETS.search(target))
        release += bool(RELEASE_TARGETS.search(target))
        allocation += bool(ALLOCATION_TARGETS.search(target))
    return retain, release, allocation


def ownership_calls(disassembly: str) -> tuple[int, int]:
    retain, release, _ = emitted_calls(disassembly)
    return retain, release


def number(name: str, default: float) -> float:
    value = os.environ.get(name, str(default))
    try:
        parsed = float(value)
    except ValueError as error:
        raise SystemExit(f"{name} must be a nonnegative number, got {value!r}") from error
    if not math.isfinite(parsed) or parsed < 0:
        raise SystemExit(f"{name} must be a nonnegative finite number, got {value!r}")
    return parsed


def delta_percent(candidate: float, baseline: float) -> float:
    if baseline == 0:
        # Keep JSON standards-compliant while still making any non-zero candidate fail a percentage gate.
        return 0.0 if candidate == 0 else 100.0 * candidate
    return 100.0 * (candidate / baseline - 1.0)


def ratio_percent(candidate: float, baseline: float) -> float:
    if baseline == 0:
        return 100.0 if candidate == 0 else 100.0 * candidate
    return 100.0 * candidate / baseline


def read_rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def typed_raw(row: dict[str, str]) -> dict[str, object]:
    return {
        "model": row["model"],
        "scenario": row["scenario"],
        "repetition": int(row["repetition"]),
        "elapsedSeconds": float(row["elapsed_seconds"]),
        "throughputOpsPerSecond": float(row["throughput_ops_per_second"]),
        "maxRssKiB": int(row["max_rss_kib"]),
        "operations": int(row["operations"]),
        "logicalAllocations": int(row["logical_allocations"]),
    }


def typed_static(row: dict[str, str]) -> dict[str, object]:
    return {
        "model": row["model"],
        "compileSeconds": float(row["compile_seconds"]),
        "compileMaxRssKiB": int(row["compile_max_rss_kib"]),
        "binaryBytes": int(row["binary_bytes"]),
        "retainCallsites": int(row["retain_callsites"]),
        "releaseCallsites": int(row["release_callsites"]),
        "allocationCallsites": int(row["allocation_callsites"]),
        "compilerCommit": row["compiler_commit"],
        "memoryModel": row["memory_model"],
        "compiler": row["compiler"],
    }


def typed_compile(row: dict[str, str]) -> dict[str, object]:
    return {
        "model": row["model"],
        "repetition": int(row["repetition"]),
        "compileSeconds": float(row["compile_seconds"]),
        "compileMaxRssKiB": int(row["compile_max_rss_kib"]),
    }


def report(raw_path: Path, compile_path: Path, static_path: Path, output: Path) -> int:
    raw = [typed_raw(row) for row in read_rows(raw_path)]
    compile_raw = [typed_compile(row) for row in read_rows(compile_path)]
    static_rows = [typed_static(row) for row in read_rows(static_path)]
    static = {str(row["model"]): row for row in static_rows}
    if set(static) != {BASELINE, CANDIDATE}:
        raise SystemExit(f"static.tsv must contain exactly {BASELINE} and {CANDIDATE}")

    scenario_limit = number("ARC_BENCH_SCENARIO_REGRESSION_PERCENT", 5)
    throughput_floor = number("ARC_BENCH_THROUGHPUT_FLOOR_PERCENT", 100)
    rss_limit = number("ARC_BENCH_RSS_LIMIT_PERCENT", 5)
    size_limit = number("ARC_BENCH_SIZE_LIMIT_PERCENT", 5)
    enforce = os.environ.get("ARC_BENCH_ENFORCE", "1")
    if enforce not in {"0", "1"}:
        raise SystemExit("ARC_BENCH_ENFORCE must be 0 or 1")

    scenarios = sorted({str(row["scenario"]) for row in raw})
    summaries: list[dict[str, object]] = []
    failures: list[str] = []
    throughput_ratios: list[float] = []
    for scenario in scenarios:
        by_model = {
            model: [row for row in raw if row["scenario"] == scenario and row["model"] == model]
            for model in (BASELINE, CANDIDATE)
        }
        by_repetition: dict[str, dict[int, dict[str, object]]] = {}
        invalid_grid = False
        for model in (BASELINE, CANDIDATE):
            model_rows: dict[int, dict[str, object]] = {}
            for row in by_model[model]:
                repetition = int(row["repetition"])
                if repetition in model_rows:
                    failures.append(f"{scenario}: duplicate {model} repetition {repetition}")
                    invalid_grid = True
                model_rows[repetition] = row
            by_repetition[model] = model_rows
        maximum = max(
            (repetition for values in by_repetition.values() for repetition in values),
            default=0,
        )
        expected_repetitions = set(range(1, maximum + 1))
        if maximum == 0 or any(set(values) != expected_repetitions for values in by_repetition.values()):
            failures.append(f"{scenario}: missing, unbalanced, or non-contiguous repetitions")
            invalid_grid = True
        if invalid_grid:
            continue
        baseline_elapsed = statistics.median(float(row["elapsedSeconds"]) for row in by_model[BASELINE])
        candidate_elapsed = statistics.median(float(row["elapsedSeconds"]) for row in by_model[CANDIDATE])
        baseline_throughput = statistics.median(float(row["throughputOpsPerSecond"]) for row in by_model[BASELINE])
        candidate_throughput = statistics.median(float(row["throughputOpsPerSecond"]) for row in by_model[CANDIDATE])
        baseline_rss = max(int(row["maxRssKiB"]) for row in by_model[BASELINE])
        candidate_rss = max(int(row["maxRssKiB"]) for row in by_model[CANDIDATE])
        baseline_allocations = {int(row["logicalAllocations"]) for row in by_model[BASELINE]}
        candidate_allocations = {int(row["logicalAllocations"]) for row in by_model[CANDIDATE]}
        paired_latency_ratios = [
            float(by_repetition[CANDIDATE][repetition]["elapsedSeconds"])
            / float(by_repetition[BASELINE][repetition]["elapsedSeconds"])
            for repetition in sorted(expected_repetitions)
        ]
        paired_throughput_ratios = [
            float(by_repetition[CANDIDATE][repetition]["throughputOpsPerSecond"])
            / float(by_repetition[BASELINE][repetition]["throughputOpsPerSecond"])
            for repetition in sorted(expected_repetitions)
        ]
        paired_rss_ratios = [
            int(by_repetition[CANDIDATE][repetition]["maxRssKiB"])
            / int(by_repetition[BASELINE][repetition]["maxRssKiB"])
            for repetition in sorted(expected_repetitions)
        ]
        latency_delta = 100.0 * (statistics.median(paired_latency_ratios) - 1.0)
        throughput_ratio = 100.0 * statistics.median(paired_throughput_ratios)
        # The release gate is peak RSS of the candidate versus peak RSS of the baseline.
        # Per-repetition ratios remain useful diagnostics, but their maximum compares
        # potentially unrelated peaks and can falsely fail a candidate whose peak is lower.
        rss_delta = delta_percent(candidate_rss, baseline_rss)
        throughput_ratios.append(throughput_ratio / 100.0)
        passed = True
        if latency_delta > scenario_limit:
            failures.append(f"{scenario}: latency regression {latency_delta:.3f}% > {scenario_limit:.3f}%")
            passed = False
        if rss_delta > rss_limit:
            failures.append(f"{scenario}: peak RSS regression {rss_delta:.3f}% > {rss_limit:.3f}%")
            passed = False
        if len(baseline_allocations) != 1 or baseline_allocations != candidate_allocations:
            failures.append(f"{scenario}: logical allocation count differs")
            passed = False
        summaries.append(
            {
                "scenario": scenario,
                "baselineMedianSeconds": baseline_elapsed,
                "candidateMedianSeconds": candidate_elapsed,
                "latencyDeltaPercent": latency_delta,
                "pairedLatencyRatios": paired_latency_ratios,
                "baselineMedianThroughput": baseline_throughput,
                "candidateMedianThroughput": candidate_throughput,
                "throughputRatioPercent": throughput_ratio,
                "pairedThroughputRatios": paired_throughput_ratios,
                "baselinePeakRssKiB": baseline_rss,
                "candidatePeakRssKiB": candidate_rss,
                "rssDeltaPercent": rss_delta,
                "pairedRssRatios": paired_rss_ratios,
                "logicalAllocations": next(iter(baseline_allocations)) if len(baseline_allocations) == 1 else None,
                "passed": passed,
            }
        )

    geomean_throughput = 100.0 * math.exp(statistics.mean(math.log(value) for value in throughput_ratios)) if throughput_ratios else 0.0
    if geomean_throughput < throughput_floor:
        failures.append(f"throughput geomean {geomean_throughput:.3f}% < {throughput_floor:.3f}%")

    aggregate = {"throughputGeomeanPercent": geomean_throughput}
    for key in (
        "compileSeconds", "compileMaxRssKiB", "binaryBytes", "retainCallsites", "releaseCallsites",
        "allocationCallsites",
    ):
        baseline_value = float(static[BASELINE][key])
        candidate_value = float(static[CANDIDATE][key])
        delta = delta_percent(candidate_value, baseline_value)
        aggregate[f"{key}DeltaPercent"] = delta
    if aggregate["binaryBytesDeltaPercent"] > size_limit:
        failures.append(
            f"binary size regression {aggregate['binaryBytesDeltaPercent']:.3f}% > {size_limit:.3f}%"
        )

    payload = {
        "baseline": static[BASELINE],
        "candidate": static[CANDIDATE],
        "thresholds": {
            "scenarioRegressionPercent": scenario_limit,
            "throughputFloorPercent": throughput_floor,
            "rssLimitPercent": rss_limit,
            "sizeLimitPercent": size_limit,
        },
        "raw": {"runtime": raw, "compilation": compile_raw},
        "scenarios": summaries,
        "aggregate": aggregate,
        "failures": failures,
        "passed": not failures,
    }
    output.mkdir(parents=True, exist_ok=True)
    (output / "raw.json").write_text(
        json.dumps({"runtime": raw, "compilation": compile_raw}, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (output / "summary.json").write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    gate = {
        "schemaVersion": 1,
        "passed": not failures,
        "failures": failures,
        "throughputGeomeanPercent": geomean_throughput,
        "binaryBytesDeltaPercent": aggregate["binaryBytesDeltaPercent"],
        "candidateCommit": static[CANDIDATE]["compilerCommit"],
        "baselineCommit": static[BASELINE]["compilerCommit"],
        "scenarios": {
            str(row["scenario"]): {
                "passed": row["passed"],
                "throughputRatioPercent": row["throughputRatioPercent"],
                "latencyDeltaPercent": row["latencyDeltaPercent"],
                "rssDeltaPercent": row["rssDeltaPercent"],
            }
            for row in summaries
        },
    }
    (output / "gate.json").write_text(
        json.dumps(gate, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )

    with (output / "summary.tsv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
        writer.writerow(["scenario", "baseline_median_seconds", "candidate_median_seconds", "latency_delta_percent", "throughput_ratio_percent", "baseline_peak_rss_kib", "candidate_peak_rss_kib", "rss_delta_percent", "logical_allocations", "passed"])
        for row in summaries:
            writer.writerow([row["scenario"], row["baselineMedianSeconds"], row["candidateMedianSeconds"], f'{row["latencyDeltaPercent"]:.3f}', f'{row["throughputRatioPercent"]:.3f}', row["baselinePeakRssKiB"], row["candidatePeakRssKiB"], f'{row["rssDeltaPercent"]:.3f}', row["logicalAllocations"], row["passed"]])

    markdown = [
        "# Kotlin/Native ARC benchmark comparison",
        "",
        f"Baseline: strict v1.9.10 `{static[BASELINE]['compilerCommit']}`",
        f"Candidate: ARC `{static[CANDIDATE]['compilerCommit']}`",
        "",
        "| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |",
        "|---|---:|---:|---:|---:|---:|:---:|",
    ]
    for row in summaries:
        markdown.append(
            f"| {row['scenario']} | {row['baselineMedianSeconds']:.6f} | {row['candidateMedianSeconds']:.6f} | "
            f"{row['latencyDeltaPercent']:.3f}% | {row['throughputRatioPercent']:.3f}% | "
            f"{row['rssDeltaPercent']:.3f}% | {'PASS' if row['passed'] else 'FAIL'} |"
        )
    markdown += [
        "",
        "## Binary and compile metrics",
        "",
        "| Metric | Baseline | ARC | Delta |",
        "|---|---:|---:|---:|",
    ]
    for key, label in (("compileSeconds", "Compile seconds"), ("compileMaxRssKiB", "Compile peak RSS KiB"), ("binaryBytes", "Binary bytes"), ("retainCallsites", "Retain callsites"), ("releaseCallsites", "Release callsites"), ("allocationCallsites", "Emitted allocation callsites")):
        markdown.append(f"| {label} | {static[BASELINE][key]} | {static[CANDIDATE][key]} | {aggregate[key + 'DeltaPercent']:.3f}% |")
    markdown += ["", f"Throughput geomean: **{geomean_throughput:.3f}%**", ""]
    if failures:
        markdown += ["## Failed gates", ""] + [f"- {failure}" for failure in failures]
    else:
        markdown += ["**All hard gates passed.**"]
    (output / "comparison.md").write_text("\n".join(markdown) + "\n", encoding="utf-8")

    print((output / "summary.tsv").read_text(encoding="utf-8"), end="")
    if failures:
        for failure in failures:
            print(f"ARC benchmark gate failed: {failure}", file=sys.stderr)
    if enforce == "0":
        print("ARC_BENCH_ENFORCE=0: results captured without enforcing hard gates")
        return 0
    return int(bool(failures))


def main() -> int:
    if len(sys.argv) >= 3 and sys.argv[1] == "calls":
        retain, release, allocation = emitted_calls(
            Path(sys.argv[2]).read_text(encoding="utf-8", errors="replace")
        )
        print(f"{retain}\t{release}\t{allocation}")
        return 0
    if len(sys.argv) == 6 and sys.argv[1] == "report":
        return report(Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]), Path(sys.argv[5]))
    raise SystemExit(
        "usage: benchmark_report.py calls DISASSEMBLY | "
        "report RUNTIME_RAW_TSV COMPILE_RAW_TSV STATIC_TSV OUTPUT_DIR"
    )


if __name__ == "__main__":
    raise SystemExit(main())
