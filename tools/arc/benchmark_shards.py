#!/usr/bin/env python3
"""Validate and deterministically merge disjoint same-host ARC benchmark shards."""

from __future__ import annotations

import csv
import json
import os
from pathlib import Path
import shutil
import sys

import benchmark_report


REQUIRED = {
    "inputs.json", "hardware.json", "candidate-provenance.json", "baseline-provenance.json",
    "raw.tsv", "compile-raw.tsv", "static.tsv", "summary.tsv", "summary.json", "shard.json",
}
COMPATIBLE_INPUT_KEYS = (
    "baselineCommit", "baselineTag", "baselineMemoryModel", "candidateMemoryModel",
    "commonCompilerFlags", "fixture", "fixtureSha256", "interopDefinition",
    "interopDefinitionSha256", "interopHeader", "interopHeaderSha256", "repetitions",
    "warmups", "compileRepetitions", "quickDiagnostic", "logicalAllocationDefinition",
    "emittedCallsiteMethod", "optimizerEliminationGate", "thresholdEnvironment",
)


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
        if metadata.get("schemaVersion") != 1:
            raise SystemExit(f"unsupported shard schema in {source}")
        shards.append((metadata, source))
    shards.sort(key=lambda item: str(item[0].get("shardId", "")))

    ids = [str(metadata.get("shardId", "")) for metadata, _ in shards]
    if any(not shard_id for shard_id in ids) or len(ids) != len(set(ids)):
        raise SystemExit(f"shard ids must be nonempty and unique: {ids}")
    counts = {int(metadata.get("shardCount", 0)) for metadata, _ in shards}
    if counts != {len(shards)}:
        raise SystemExit(f"shard count metadata {sorted(counts)} does not match {len(shards)} inputs")

    candidate_trees = {str(metadata.get("candidateTree", "")) for metadata, _ in shards}
    baseline_trees = {str(metadata.get("baselineTree", "")) for metadata, _ in shards}
    baseline_commits = {str(metadata.get("baselineCommit", "")) for metadata, _ in shards}
    if len(candidate_trees) != 1 or "" in candidate_trees:
        raise SystemExit(f"candidate trees differ across shards: {sorted(candidate_trees)}")
    if len(baseline_trees) != 1 or "" in baseline_trees or len(baseline_commits) != 1:
        raise SystemExit("baseline identity differs across shards")

    canonical_inputs = read_json(shards[0][1] / "inputs.json")
    for _, source in shards[1:]:
        inputs = read_json(source / "inputs.json")
        for key in COMPATIBLE_INPUT_KEYS:
            if inputs.get(key) != canonical_inputs.get(key):
                raise SystemExit(f"incompatible shard input {key!r}: {source}")

    scenario_owner: dict[str, str] = {}
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
        header, rows = read_tsv(source / "raw.tsv")
        if raw_header is None:
            raw_header = header
        elif header != raw_header:
            raise SystemExit(f"raw.tsv schema differs in {source}")
        observed = {row["scenario"] for row in rows}
        if observed != set(scenarios):
            raise SystemExit(f"raw scenarios {sorted(observed)} do not match metadata {sorted(scenarios)} in {source}")
        raw_rows.extend(rows)

    model_order = {benchmark_report.BASELINE: 0, benchmark_report.CANDIDATE: 1}
    raw_rows.sort(key=lambda row: (row["scenario"], int(row["repetition"]), model_order.get(row["model"], 9)))
    destination.mkdir(parents=True)
    assert raw_header is not None
    write_tsv(destination / "raw.tsv", raw_header, raw_rows)

    canonical_source = shards[0][1]
    shutil.copy2(canonical_source / "compile-raw.tsv", destination / "compile-raw.tsv")
    shutil.copy2(canonical_source / "static.tsv", destination / "static.tsv")

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
    shard_payload = {
        "schemaVersion": 1,
        "shardId": "merged",
        "shardCount": len(shards),
        "scenarios": sorted(scenario_owner),
        "candidateTree": next(iter(candidate_trees)),
        "baselineCommit": next(iter(baseline_commits)),
        "baselineTree": next(iter(baseline_trees)),
        "pairing": "same-host-interleaved-baseline-candidate-per-shard",
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
