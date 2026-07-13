#!/usr/bin/env python3
"""Run immutable, provenance-checked ARC benchmark shards on both Linux hosts."""

from __future__ import annotations

import argparse
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass
import hashlib
import json
import os
from pathlib import Path
from pathlib import PurePosixPath
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
from threading import Lock
from typing import Sequence

import arc
import benchmark_plan
import benchmark_shards


RESULTS = arc.ROOT / "tools" / "arc" / "benchmark-results"
MACHINES = ("primary-bench", "ci2-bench")
MACHINE_ENVIRONMENT = {
    "ARC_REMOTE", "ARC_REMOTE_DIR", "ARC_REMOTE_GIT", "ARC_JAVA_HOME",
    "ARC_MAX_WORKERS", "ARC_MIN_AVAILABLE_GIB", "ARC_BENCH_BASELINE_SOURCE",
    "ARC_BENCH_BASELINE_DIST",
}
BUNDLE_FILES = benchmark_shards.REQUIRED | {"raw.json", "comparison.md"}
SAFE_WAVE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
SAFE_SHARD = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


@dataclass(frozen=True)
class SnapshotIdentity:
    commit: str
    tree: str
    runtime_tree: str
    runtime_patch_base: str
    runtime_patch_sha256: str


@dataclass(frozen=True)
class HostShard:
    machine: str
    host: str
    remote_dir: str
    remote_git: str
    workers: int
    shard_id: str
    scenarios: tuple[str, ...]


def parse_scenarios(value: str) -> tuple[str, ...]:
    scenarios = tuple(part for part in re.split(r"[\s,]+", value.strip()) if part)
    if not scenarios:
        raise SystemExit("each benchmark host requires at least one scenario")
    if len(scenarios) != len(set(scenarios)):
        raise SystemExit(f"duplicate benchmark scenario in {value!r}")
    if any(not SAFE_SHARD.fullmatch(scenario) for scenario in scenarios):
        raise SystemExit(f"invalid benchmark scenario in {value!r}")
    return scenarios


def machine_value(machine: str, key: str, fallback: str) -> str:
    return arc.MACHINE_PROFILES[machine].get(key, fallback)


def build_host_shards(primary: str, secondary: str) -> tuple[HostShard, HostShard]:
    selections = (parse_scenarios(primary), parse_scenarios(secondary))
    overlap = set(selections[0]) & set(selections[1])
    if overlap:
        raise SystemExit(f"parallel benchmark scenarios overlap: {sorted(overlap)}")
    shards: list[HostShard] = []
    for index, (machine, scenarios) in enumerate(zip(MACHINES, selections, strict=True)):
        profile = arc.MACHINE_PROFILES[machine]
        shards.append(HostShard(
            machine=machine,
            host=machine_value(machine, "ARC_REMOTE", arc.DEFAULT_HOST),
            remote_dir=machine_value(machine, "ARC_REMOTE_DIR", arc.DEFAULT_REMOTE_DIR),
            remote_git=machine_value(machine, "ARC_REMOTE_GIT", arc.DEFAULT_REMOTE_GIT),
            workers=int(machine_value(machine, "ARC_MAX_WORKERS", str(arc.MAX_WORKERS))),
            shard_id=("primary", "secondary")[index],
            scenarios=scenarios,
        ))
    if len({shard.host for shard in shards}) != len(shards):
        raise SystemExit("parallel benchmark machines must resolve to distinct hosts")
    if len({(shard.host, shard.remote_dir) for shard in shards}) != len(shards):
        raise SystemExit("parallel benchmark machines must use distinct remote worktrees")
    for shard in shards:
        if "bench" not in Path(shard.remote_dir).name:
            raise SystemExit(f"refusing non-benchmark remote worktree: {shard.remote_dir}")
        if not 1 <= shard.workers <= arc.MAX_WORKERS:
            raise SystemExit(f"invalid worker count for {shard.machine}: {shard.workers}")
    return shards[0], shards[1]


def build_balanced_host_shards(scenarios: str) -> tuple[HostShard, HostShard]:
    assignments = benchmark_plan.assign_scenarios(scenarios)
    return build_host_shards(
        ",".join(assignments[0].scenarios), ",".join(assignments[1].scenarios)
    )


def validate_remote_worktree(shard: HostShard) -> None:
    if shard.machine not in MACHINES:
        raise RuntimeError(f"refusing unapproved benchmark machine: {shard.machine}")
    expected_host = machine_value(shard.machine, "ARC_REMOTE", arc.DEFAULT_HOST)
    expected_dir = machine_value(shard.machine, "ARC_REMOTE_DIR", arc.DEFAULT_REMOTE_DIR)
    path = PurePosixPath(shard.remote_dir)
    if shard.host != expected_host or shard.remote_dir != expected_dir:
        raise RuntimeError(
            f"refusing remote mutation outside configured {shard.machine} worktree: "
            f"{shard.host}:{shard.remote_dir}"
        )
    if not path.is_absolute() or ".." in path.parts or "bench" not in path.name:
        raise RuntimeError(f"refusing unsafe benchmark worktree: {shard.remote_dir}")


def validate_distinct_hostnames(hostnames: Sequence[str], expected_count: int) -> None:
    values = set(hostnames)
    if len(values) != expected_count:
        raise RuntimeError(f"benchmark aliases resolved to the same host: {sorted(values)}")


def git_bytes(arguments: Sequence[str]) -> bytes:
    result = subprocess.run(
        ["git", *arguments], cwd=arc.ROOT, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    return result.stdout


def snapshot_identity(commit: str) -> SnapshotIdentity:
    def revision(value: str) -> str:
        return git_bytes(["rev-parse", value]).decode("ascii").strip()

    base = revision(f"{commit}^")
    patch = git_bytes([
        "diff", "--binary", "--no-ext-diff", base, commit, "--", "kotlin-native/runtime",
    ])
    return SnapshotIdentity(
        commit=revision(f"{commit}^{{commit}}"),
        tree=revision(f"{commit}^{{tree}}"),
        runtime_tree=revision(f"{commit}:kotlin-native/runtime"),
        runtime_patch_base=base,
        runtime_patch_sha256=hashlib.sha256(patch).hexdigest(),
    )


def command_environment(shard: HostShard, identity: SnapshotIdentity) -> dict[str, str]:
    environment = {key: value for key, value in os.environ.items() if key not in MACHINE_ENVIRONMENT}
    environment.update({
        "ARC_BENCH_MACHINE_PROFILE": shard.machine,
        "ARC_BENCH_EXPECTED_COMMIT": identity.commit,
        "ARC_BENCH_EXPECTED_TREE": identity.tree,
        "ARC_BENCH_EXPECTED_RUNTIME_TREE": identity.runtime_tree,
        "ARC_BENCH_EXPECTED_RUNTIME_PATCH_SHA256": identity.runtime_patch_sha256,
    })
    return environment


def arc_command(shard: HostShard, *arguments: str) -> list[str]:
    return [sys.executable, str(Path(arc.__file__).resolve()), "--machine", shard.machine, *arguments]


def run_command(command: Sequence[str], *, environment: dict[str, str], check: bool = True) -> int:
    print("+", shlex.join(command), flush=True)
    result = subprocess.run(command, cwd=arc.ROOT, env=environment, check=False)
    if check and result.returncode:
        raise RuntimeError(f"command failed with status {result.returncode}: {shlex.join(command)}")
    return result.returncode


def remote_action(shard: HostShard, action: str, *arguments: str) -> None:
    validate_remote_worktree(shard)
    helper = arc.REMOTE_HELPER.read_bytes().replace(b"\r\n", b"\n")
    command = [
        "ssh", shard.host, "bash", "-s", "--", action, shard.remote_dir, shard.remote_git,
        arc.DEFAULT_JAVA_HOME, str(shard.workers), str(arc.MIN_AVAILABLE_GIB), *arguments,
    ]
    print("+", shlex.join(command), flush=True)
    result = subprocess.run(command, cwd=arc.ROOT, input=helper, check=False)
    if result.returncode:
        raise RuntimeError(f"remote {action} failed on {shard.machine} with status {result.returncode}")


def remote_url(shard: HostShard) -> str:
    validate_remote_worktree(shard)
    return f"ssh://{shard.host}{shard.remote_git}/.git"


def probe_hostname(shard: HostShard) -> str:
    validate_remote_worktree(shard)
    command = ["ssh", shard.host, "hostname"]
    print("+", shlex.join(command), flush=True)
    result = subprocess.run(
        command, cwd=arc.ROOT, check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    hostname = result.stdout.strip()
    if result.returncode or not hostname or "\n" in hostname:
        raise RuntimeError(
            f"failed to establish physical hostname for {shard.machine}: "
            f"status={result.returncode} stderr={result.stderr.strip()!r}"
        )
    return hostname


def validate_bundle_entries(bundle: Path) -> None:
    entries = list(bundle.iterdir()) if bundle.is_dir() else []
    unsafe = [path.name for path in entries if path.is_symlink() or not path.is_file()]
    names = {path.name for path in entries}
    if unsafe or names != BUNDLE_FILES:
        raise RuntimeError(
            f"invalid benchmark bundle: unsafe={sorted(unsafe)} "
            f"missing={sorted(BUNDLE_FILES - names)} unexpected={sorted(names - BUNDLE_FILES)}"
        )


def download_bundle(shard: HostShard, destination: Path) -> Path:
    validate_remote_worktree(shard)
    destination.mkdir(parents=True)
    source = f"{shard.host}:{shard.remote_dir}/.arc-runs/arc-bench/artifacts/wave"
    run_command(["scp", "-r", "--", source, str(destination)], environment=os.environ.copy())
    bundle = destination / "wave"
    if not bundle.is_dir():
        raise RuntimeError(f"{shard.machine} did not publish a benchmark wave")
    validate_bundle_entries(bundle)
    return bundle


def validate_bundle(
    bundle: Path, shard: HostShard, identity: SnapshotIdentity, expected_scenarios: Sequence[str]
) -> str:
    metadata = benchmark_shards.read_json(bundle / "shard.json")
    expected = {
        "schemaVersion": 2,
        "shardId": shard.shard_id,
        "shardCount": 2,
        "scenarios": list(expected_scenarios),
        "candidateCommit": identity.commit,
        "candidateTree": identity.tree,
        "candidateRuntimeTree": identity.runtime_tree,
        "candidateRuntimePatchBase": identity.runtime_patch_base,
        "candidateRuntimePatchSha256": identity.runtime_patch_sha256,
        "machineProfile": shard.machine,
    }
    mismatches = {
        key: {"expected": value, "actual": metadata.get(key)}
        for key, value in expected.items() if metadata.get(key) != value
    }
    if mismatches:
        raise RuntimeError(f"stale or mismatched bundle from {shard.machine}: {mismatches}")
    benchmark_shards.validate_shard_identity(metadata, bundle)
    candidate = benchmark_shards.read_json(bundle / "candidate-provenance.json")
    if candidate.get("source") != shard.remote_dir:
        raise RuntimeError(f"candidate provenance came from another worktree on {shard.machine}")
    hostname = metadata.get("hostname")
    if not isinstance(hostname, str) or not hostname:
        raise RuntimeError(f"missing hostname provenance from {shard.machine}")
    return hostname


def run_host(
    shard: HostShard,
    reference: str,
    identity: SnapshotIdentity,
    output_root: Path,
    reference_push_attempted: Callable[[HostShard], None] | None = None,
) -> tuple[Path, int, str]:
    validate_remote_worktree(shard)
    environment = command_environment(shard, identity)
    run_command(arc_command(shard, "remote-init"), environment=environment)
    if reference_push_attempted is not None:
        reference_push_attempted(shard)
    run_command(["git", "push", remote_url(shard), f"{reference}:{reference}"], environment=environment)
    remote_action(shard, "checkout", reference, identity.commit)
    common = ["--shard-id", shard.shard_id, "--shard-count", "2", "--scenarios", ",".join(shard.scenarios)]
    run_command(arc_command(shard, "run", "arc-bench-candidate", *common), environment=environment)
    run_command(arc_command(shard, "run", "arc-bench-baseline", *common), environment=environment)
    benchmark_status = run_command(
        arc_command(shard, "run", "arc-bench", *common), environment=environment, check=False
    )
    bundle = download_bundle(shard, output_root / shard.shard_id)
    hostname = validate_bundle(bundle, shard, identity, shard.scenarios)
    return bundle, benchmark_status, hostname


def dry_run_payload(wave: str, shards: Sequence[HostShard], paths: Sequence[str] | None) -> dict[str, object]:
    return {
        "mode": "dry-run",
        "wave": wave,
        "snapshotPaths": list(paths) if paths else "complete worktree excluding ARC generated paths",
        "singleImmutableSnapshot": True,
        "parallelHosts": [asdict(shard) for shard in shards],
        "estimatedScenarioWeights": {
            shard.shard_id: sum(benchmark_plan.SCENARIO_WEIGHTS[scenario] for scenario in shard.scenarios)
            for shard in shards
        },
        "measurement": "same-host scenario-balanced deterministic paired baseline/candidate schedule",
        "correctnessGate": "both models must produce identical valid output before additional warmup or timing",
        "cacheReuse": "immutable content-addressed candidate dist and persistent exact-v1.9.10 baseline dist",
        "mergePolicy": "exact commit/tree/runtime-tree/runtime-patch and compatible manifests only",
    }


def execute(wave: str, shards: Sequence[HostShard], paths: list[str] | None) -> int:
    for shard in shards:
        validate_remote_worktree(shard)
    destination = RESULTS / wave
    if destination.exists():
        raise SystemExit(f"benchmark result destination already exists: {destination}")
    with ThreadPoolExecutor(max_workers=2, thread_name_prefix="arc-host-probe") as executor:
        hostnames = list(executor.map(probe_hostname, shards))
    validate_distinct_hostnames(hostnames, len(shards))
    reference, commit = arc.create_snapshot_ref(paths)
    push_attempted_shards: set[HostShard] = set()
    push_attempted_shards_lock = Lock()
    primary_succeeded = False

    def record_reference_push_attempt(shard: HostShard) -> None:
        with push_attempted_shards_lock:
            push_attempted_shards.add(shard)

    try:
        identity = snapshot_identity(commit)
        with tempfile.TemporaryDirectory(prefix="arc-parallel-benchmark-") as temporary:
            output_root = Path(temporary)
            results: list[tuple[Path, int, str]] = []
            with ThreadPoolExecutor(max_workers=2, thread_name_prefix="arc-benchmark") as executor:
                futures = {
                    executor.submit(
                        run_host, shard, reference, identity, output_root, record_reference_push_attempt
                    ): shard
                    for shard in shards
                }
                for future in as_completed(futures):
                    results.append(future.result())
            validate_distinct_hostnames([hostname for _, _, hostname in results], len(shards))
            status = benchmark_shards.merge(destination, [bundle for bundle, _, _ in results])
            remote_statuses = [remote_status for _, remote_status, _ in results]
            if any(remote_statuses) and status == 0:
                raise RuntimeError(f"remote benchmark gate failed but merged report passed: {remote_statuses}")
            primary_succeeded = status == 0
            return status
    finally:
        unresolved_hosts: list[str] = []
        try:
            for shard in push_attempted_shards:
                try:
                    resolved = arc.delete_remote_ref(
                        remote_url(shard),
                        reference,
                        fallback_host=shard.host,
                        fallback_remote_git=shard.remote_git,
                        expected_host=machine_value(shard.machine, "ARC_REMOTE", arc.DEFAULT_HOST),
                        expected_remote_git=machine_value(
                            shard.machine, "ARC_REMOTE_GIT", arc.DEFAULT_REMOTE_GIT
                        ),
                    )
                except Exception as error:
                    resolved = False
                    print(
                        f"Remote snapshot cleanup failed before verification for "
                        f"{shard.machine} {reference}: {error}",
                        file=sys.stderr,
                    )
                if not resolved:
                    unresolved_hosts.append(shard.machine)
        finally:
            subprocess.run(["git", "update-ref", "-d", reference], cwd=arc.ROOT, check=False)
        if primary_succeeded and unresolved_hosts:
            raise RuntimeError(
                f"remote snapshot ref cleanup remains unresolved for {sorted(unresolved_hosts)}: {reference}"
            )


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wave")
    parser.add_argument("--scenarios", help="deterministically balance this complete set across both hosts")
    parser.add_argument("--primary-scenarios")
    parser.add_argument("--secondary-scenarios")
    parser.add_argument("--paths", nargs="+", help="snapshot only HEAD plus these lane-owned paths")
    parser.add_argument("--dry-run", action="store_true", help="validate and print the plan without local or remote mutation")
    options = parser.parse_args(arguments)
    if not SAFE_WAVE.fullmatch(options.wave):
        raise SystemExit("benchmark wave must contain only letters, digits, '-' and '_'")
    if options.primary_scenarios or options.secondary_scenarios:
        if options.scenarios:
            parser.error("--scenarios cannot be combined with explicit host scenario sets")
        if not options.primary_scenarios or not options.secondary_scenarios:
            parser.error("both --primary-scenarios and --secondary-scenarios are required")
        shards = build_host_shards(options.primary_scenarios, options.secondary_scenarios)
    else:
        if not options.scenarios:
            parser.error("provide --scenarios or both explicit host scenario sets")
        try:
            shards = build_balanced_host_shards(options.scenarios)
        except ValueError as error:
            parser.error(str(error))
    paths = arc.snapshot_pathspecs(options.paths)
    if options.dry_run:
        print(json.dumps(dry_run_payload(options.wave, shards, paths), indent=2, sort_keys=True))
        return 0
    return execute(options.wave, shards, paths)


if __name__ == "__main__":
    raise SystemExit(main())
