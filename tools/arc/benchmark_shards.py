#!/usr/bin/env python3
"""Validate and deterministically merge disjoint same-host ARC benchmark shards."""

from __future__ import annotations

import csv
import json
import os
from pathlib import Path
import re
import shutil
import sys

import benchmark_plan
import benchmark_report


REQUIRED = {
    "inputs.json", "hardware.json", "candidate-provenance.json", "baseline-provenance.json",
    "raw.tsv", "compile-raw.tsv", "static.tsv", "summary.tsv", "summary.json", "gate.json",
    "schedule.json", "correctness.json", "shard.json",
}
COMPATIBLE_INPUT_KEYS = (
    "candidateCommit", "candidateTree", "candidateRuntimeTree", "candidateRuntimePatchBase",
    "candidateRuntimePatchSha256", "baselineCommit", "baselineTag", "baselineMemoryModel", "candidateMemoryModel",
    "commonCompilerFlags", "fixture", "fixtureSha256", "interopDefinition",
    "interopDefinitionSha256", "interopHeader", "interopHeaderSha256", "repetitions",
    "warmups", "correctnessRunsPerModel", "correctnessConsumesFirstWarmup",
    "compileRepetitions", "quickDiagnostic", "logicalAllocationDefinition",
    "emittedCallsiteMethod", "optimizerEliminationGate", "thresholdEnvironment",
    "benchmarkJavaOpts", "benchmarkJvmTools", "benchmarkJvmEnvironment",
)
CORRECTNESS_POLICY = "exact-observable-output-before-warmup-or-timing"
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def read_json(path: Path) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise SystemExit(f"invalid JSON {path}: {error}") from error
    if not isinstance(value, dict):
        raise SystemExit(f"expected JSON object in {path}")
    return value


def read_tsv(path: Path) -> tuple[list[str], list[dict[str, str]]]:
    with path.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        if reader.fieldnames is None:
            raise SystemExit(f"missing TSV header in {path}")
        return reader.fieldnames, list(reader)


def write_tsv(path: Path, fieldnames: list[str], rows: list[dict[str, str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def require_int(payload: dict[str, object], key: str, source: Path, *, minimum: int) -> int:
    value = payload.get(key)
    if type(value) is not int or value < minimum:
        raise SystemExit(f"invalid {key!r} in {source}: {value!r}")
    return value


def validate_correctness(
    source: Path, scenarios: list[str]
) -> dict[str, object]:
    correctness = read_json(source / "correctness.json")
    if correctness.get("schemaVersion") != 1 or correctness.get("policy") != CORRECTNESS_POLICY:
        raise SystemExit(f"invalid correctness schema/policy in {source}")
    results = correctness.get("results")
    if correctness.get("passed") is not True or not isinstance(results, list):
        raise SystemExit(f"correctness gate did not pass in {source}")
    expected_keys = {
        (scenario, model)
        for scenario in scenarios
        for model in (benchmark_report.BASELINE, benchmark_report.CANDIDATE)
    }
    observed: dict[tuple[str, str], str] = {}
    for row in results:
        if not isinstance(row, dict):
            raise SystemExit(f"invalid correctness result in {source}")
        scenario, model, digest = row.get("scenario"), row.get("model"), row.get("output_sha256")
        key = (scenario, model)
        if key not in expected_keys or key in observed or not isinstance(digest, str) or not SHA256.fullmatch(digest):
            raise SystemExit(f"invalid or duplicate correctness result {key!r} in {source}")
        observed[key] = digest
    if set(observed) != expected_keys:
        raise SystemExit(f"correctness coverage does not match scenarios in {source}")
    for scenario in scenarios:
        if observed[(scenario, benchmark_report.BASELINE)] != observed[(scenario, benchmark_report.CANDIDATE)]:
            raise SystemExit(f"correctness output digest differs for {scenario!r} in {source}")
    return correctness


def validate_execution_evidence(
    metadata: dict[str, object], source: Path
) -> tuple[list[str], list[dict[str, str]], dict[str, object]]:
    scenarios = metadata.get("scenarios")
    if not isinstance(scenarios, list) or not scenarios or any(not isinstance(value, str) for value in scenarios):
        raise SystemExit(f"invalid scenario metadata in {source}")
    inputs = read_json(source / "inputs.json")
    repetitions = require_int(inputs, "repetitions", source, minimum=1)
    warmups = require_int(inputs, "warmups", source, minimum=0)
    compile_repetitions = require_int(inputs, "compileRepetitions", source, minimum=1)
    expected_schedule = benchmark_plan.execution_schedule(
        scenarios, warmups, repetitions, compile_repetitions
    )
    schedule = read_json(source / "schedule.json")
    if schedule != expected_schedule:
        raise SystemExit(f"execution schedule does not match inputs/scenarios in {source}")

    header, rows = read_tsv(source / "raw.tsv")
    observed_sequence: list[tuple[str, int, str]] = []
    observed_grid: set[tuple[str, str, int]] = set()
    for row in rows:
        try:
            repetition = int(row["repetition"])
            scenario = row["scenario"]
            model = row["model"]
        except (KeyError, ValueError) as error:
            raise SystemExit(f"invalid raw benchmark row in {source}: {row}") from error
        key = (scenario, model, repetition)
        if key in observed_grid:
            raise SystemExit(f"duplicate raw benchmark grid entry {key!r} in {source}")
        observed_grid.add(key)
        observed_sequence.append((scenario, repetition, model))
    expected_grid = {
        (scenario, model, repetition)
        for scenario in scenarios
        for model in (benchmark_report.BASELINE, benchmark_report.CANDIDATE)
        for repetition in range(1, repetitions + 1)
    }
    if observed_grid != expected_grid:
        missing = sorted(expected_grid - observed_grid)
        unexpected = sorted(observed_grid - expected_grid)
        raise SystemExit(f"raw benchmark grid mismatch in {source}: missing={missing} unexpected={unexpected}")
    expected_sequence = [
        (str(scenario["scenario"]), int(pair["repetition"]), str(model))
        for scenario in expected_schedule["scenarios"]
        for pair in scenario["measurements"]
        for model in pair["order"]
    ]
    if observed_sequence != expected_sequence:
        raise SystemExit(f"raw benchmark execution order differs from schedule in {source}")

    compile_header, compile_rows = read_tsv(source / "compile-raw.tsv")
    compile_sequence: list[tuple[int, str]] = []
    compile_grid: set[tuple[str, int]] = set()
    for row in compile_rows:
        try:
            repetition = int(row["repetition"])
            model = row["model"]
        except (KeyError, ValueError) as error:
            raise SystemExit(f"invalid compile benchmark row in {source}: {row}") from error
        key = (model, repetition)
        if key in compile_grid:
            raise SystemExit(f"duplicate compile benchmark grid entry {key!r} in {source}")
        compile_grid.add(key)
        compile_sequence.append((repetition, model))
    expected_compile_grid = {
        (model, repetition)
        for model in (benchmark_report.BASELINE, benchmark_report.CANDIDATE)
        for repetition in range(1, compile_repetitions + 1)
    }
    if compile_grid != expected_compile_grid:
        raise SystemExit(f"compile benchmark grid mismatch in {source}")
    expected_compile_sequence = [
        (int(pair["repetition"]), str(model))
        for pair in expected_schedule["compilePairs"]
        for model in pair["order"]
    ]
    if compile_sequence != expected_compile_sequence:
        raise SystemExit(f"compile benchmark execution order differs from schedule in {source}")
    # The header is returned for deterministic merged output. compile_header is
    # intentionally consumed above so a missing header fails through row access/grid.
    _ = compile_header
    return header, rows, schedule


def validate_shard_identity(metadata: dict[str, object], source: Path) -> None:
    required_metadata = (
        "candidateCommit", "candidateTree", "candidateRuntimeTree",
        "candidateRuntimePatchBase", "candidateRuntimePatchSha256",
        "baselineCommit", "baselineTree", "hostname",
    )
    missing = [key for key in required_metadata if not isinstance(metadata.get(key), str) or not metadata[key]]
    if missing:
        raise SystemExit(f"invalid shard identity in {source}: missing {missing}")
    if len(str(metadata["candidateRuntimePatchSha256"])) != 64:
        raise SystemExit(f"invalid runtime patch digest in {source}")

    candidate = read_json(source / "candidate-provenance.json")
    candidate_identity = (candidate.get("role"), candidate.get("commit"), candidate.get("tree"))
    expected_candidate = ("candidate", metadata["candidateCommit"], metadata["candidateTree"])
    if candidate_identity != expected_candidate:
        raise SystemExit(f"candidate provenance does not match shard identity in {source}")

    baseline = read_json(source / "baseline-provenance.json")
    baseline_identity = (baseline.get("role"), baseline.get("commit"), baseline.get("tree"))
    expected_baseline = ("baseline-strict", metadata["baselineCommit"], metadata["baselineTree"])
    if baseline_identity != expected_baseline:
        raise SystemExit(f"baseline provenance does not match shard identity in {source}")

    inputs = read_json(source / "inputs.json")
    for key in (
        "candidateCommit", "candidateTree", "candidateRuntimeTree",
        "candidateRuntimePatchBase", "candidateRuntimePatchSha256", "baselineCommit",
    ):
        if inputs.get(key) != metadata[key]:
            raise SystemExit(f"input {key!r} does not match shard identity in {source}")
    hardware = read_json(source / "hardware.json")
    if hardware.get("hostname") != metadata["hostname"]:
        raise SystemExit(f"hardware hostname does not match shard identity in {source}")


def merge(destination: Path, sources: list[Path]) -> int:
    if destination.exists():
        raise SystemExit(f"merge destination already exists: {destination}")
    if len(sources) < 2:
        raise SystemExit("at least two shard bundles are required")

    shards: list[tuple[dict[str, object], Path]] = []
    for source in sources:
        missing = REQUIRED - {path.name for path in source.iterdir()} if source.is_dir() else REQUIRED
        if missing:
            raise SystemExit(f"invalid shard {source}: missing {sorted(missing)}")
        metadata = read_json(source / "shard.json")
        if metadata.get("schemaVersion") != 2:
            raise SystemExit(f"unsupported shard schema in {source}")
        validate_shard_identity(metadata, source)
        shards.append((metadata, source))
    shards.sort(key=lambda item: str(item[0].get("shardId", "")))

    ids = [str(metadata.get("shardId", "")) for metadata, _ in shards]
    if any(not shard_id for shard_id in ids) or len(ids) != len(set(ids)):
        raise SystemExit(f"shard ids must be nonempty and unique: {ids}")
    counts = {int(metadata.get("shardCount", 0)) for metadata, _ in shards}
    if counts != {len(shards)}:
        raise SystemExit(f"shard count metadata {sorted(counts)} does not match {len(shards)} inputs")

    candidate_commits = {str(metadata.get("candidateCommit", "")) for metadata, _ in shards}
    candidate_trees = {str(metadata.get("candidateTree", "")) for metadata, _ in shards}
    runtime_trees = {str(metadata.get("candidateRuntimeTree", "")) for metadata, _ in shards}
    runtime_patch_bases = {str(metadata.get("candidateRuntimePatchBase", "")) for metadata, _ in shards}
    runtime_patch_hashes = {str(metadata.get("candidateRuntimePatchSha256", "")) for metadata, _ in shards}
    baseline_trees = {str(metadata.get("baselineTree", "")) for metadata, _ in shards}
    baseline_commits = {str(metadata.get("baselineCommit", "")) for metadata, _ in shards}
    if len(candidate_commits) != 1 or "" in candidate_commits or len(candidate_trees) != 1 or "" in candidate_trees:
        raise SystemExit("candidate commit/tree identity differs across shards")
    if (len(runtime_trees) != 1 or "" in runtime_trees or
            len(runtime_patch_bases) != 1 or "" in runtime_patch_bases or
            len(runtime_patch_hashes) != 1 or "" in runtime_patch_hashes):
        raise SystemExit("candidate runtime tree/patch identity differs across shards")
    if len(baseline_trees) != 1 or "" in baseline_trees or len(baseline_commits) != 1:
        raise SystemExit("baseline identity differs across shards")

    canonical_inputs = read_json(shards[0][1] / "inputs.json")
    for _, source in shards[1:]:
        inputs = read_json(source / "inputs.json")
        for key in COMPATIBLE_INPUT_KEYS:
            if inputs.get(key) != canonical_inputs.get(key):
                raise SystemExit(f"incompatible shard input {key!r}: {source}")

    scenario_owner: dict[str, str] = {}
    correctness_by_shard: dict[str, dict[str, object]] = {}
    schedule_by_shard: dict[str, dict[str, object]] = {}
    raw_header: list[str] | None = None
    raw_rows: list[dict[str, str]] = []
    for metadata, source in shards:
        shard_id = str(metadata["shardId"])
        scenarios = metadata.get("scenarios")
        if not isinstance(scenarios, list) or not scenarios or any(not isinstance(value, str) for value in scenarios):
            raise SystemExit(f"invalid scenario metadata in {source}")
        for scenario in scenarios:
            if scenario in scenario_owner:
                raise SystemExit(f"scenario {scenario!r} overlaps shards {scenario_owner[scenario]!r} and {shard_id!r}")
            scenario_owner[scenario] = shard_id

    for metadata, source in shards:
        shard_id = str(metadata["shardId"])
        scenarios = metadata["scenarios"]
        assert isinstance(scenarios, list)
        correctness_by_shard[shard_id] = validate_correctness(source, scenarios)
        header, rows, schedule = validate_execution_evidence(metadata, source)
        schedule_by_shard[shard_id] = schedule
        if raw_header is None:
            raw_header = header
        elif header != raw_header:
            raise SystemExit(f"raw.tsv schema differs in {source}")
        raw_rows.extend(rows)

    model_order = {benchmark_report.BASELINE: 0, benchmark_report.CANDIDATE: 1}
    raw_rows.sort(key=lambda row: (row["scenario"], int(row["repetition"]), model_order.get(row["model"], 9)))
    destination.mkdir(parents=True)
    assert raw_header is not None
    write_tsv(destination / "raw.tsv", raw_header, raw_rows)

    canonical_source = shards[0][1]
    shutil.copy2(canonical_source / "compile-raw.tsv", destination / "compile-raw.tsv")
    shutil.copy2(canonical_source / "static.tsv", destination / "static.tsv")
    shutil.copy2(canonical_source / "candidate-provenance.json", destination / "candidate-provenance.json")
    shutil.copy2(canonical_source / "baseline-provenance.json", destination / "baseline-provenance.json")

    merged_inputs = dict(canonical_inputs)
    merged_inputs["scenarios"] = sorted(scenario_owner)
    merged_inputs["executionPrefix"] = "per-shard; see shards.json"
    merged_inputs["sharded"] = True
    merged_inputs["candidateTree"] = next(iter(candidate_trees))
    (destination / "inputs.json").write_text(
        json.dumps(merged_inputs, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    hardware = {
        str(metadata["shardId"]): read_json(source / "hardware.json")
        for metadata, source in shards
    }
    (destination / "hardware.json").write_text(
        json.dumps(hardware, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (destination / "correctness.json").write_text(
        json.dumps({
            "schemaVersion": 1,
            "passed": True,
            "policy": "all-shards-exact-output-before-warmup-or-timing",
            "shards": correctness_by_shard,
        }, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (destination / "schedule.json").write_text(
        json.dumps({
            "schemaVersion": 1,
            "policy": "scenario-balanced-deterministic-paired-ab-per-shard",
            "shards": schedule_by_shard,
        }, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    shard_payload = {
        "schemaVersion": 2,
        "shardId": "merged",
        "shardCount": len(shards),
        "scenarios": sorted(scenario_owner),
        "candidateCommit": next(iter(candidate_commits)),
        "candidateTree": next(iter(candidate_trees)),
        "candidateRuntimeTree": next(iter(runtime_trees)),
        "candidateRuntimePatchBase": next(iter(runtime_patch_bases)),
        "candidateRuntimePatchSha256": next(iter(runtime_patch_hashes)),
        "baselineCommit": next(iter(baseline_commits)),
        "baselineTree": next(iter(baseline_trees)),
        "pairing": "same-host-scenario-balanced-deterministic-paired-ab-per-shard",
        "shards": [metadata for metadata, _ in shards],
    }
    (destination / "shards.json").write_text(
        json.dumps(shard_payload, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (destination / "shard.json").write_text(
        json.dumps(shard_payload, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )

    status = benchmark_report.report(
        destination / "raw.tsv", destination / "compile-raw.tsv", destination / "static.tsv", destination
    )
    with (destination / "comparison.md").open("a", encoding="utf-8") as stream:
        stream.write("\n## Benchmark shards\n\n")
        for metadata, _ in shards:
            stream.write(
                f"- `{metadata['shardId']}` on `{metadata['hostname']}`: "
                f"{', '.join(metadata['scenarios'])}\n"
            )
    print(f"Merged benchmark shards: {destination}")
    return status


def main() -> int:
    if len(sys.argv) < 5 or sys.argv[1] != "merge":
        raise SystemExit("usage: benchmark_shards.py merge DESTINATION SHARD_BUNDLE SHARD_BUNDLE [...]")
    return merge(Path(sys.argv[2]), [Path(value) for value in sys.argv[3:]])


if __name__ == "__main__":
    raise SystemExit(main())
