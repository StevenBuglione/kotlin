from __future__ import annotations

from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).parent))
import benchmark_parallel
import benchmark_plan
import benchmark_shards


IDENTITY = benchmark_parallel.SnapshotIdentity(
    commit="a" * 40,
    tree="b" * 40,
    runtime_tree="c" * 40,
    runtime_patch_base="d" * 40,
    runtime_patch_sha256="e" * 64,
)


def write_bundle(path: Path, shard: benchmark_parallel.HostShard, *, runtime_hash: str | None = None) -> None:
    path.mkdir()
    digest = runtime_hash or IDENTITY.runtime_patch_sha256
    metadata = {
        "schemaVersion": 2,
        "shardId": shard.shard_id,
        "shardCount": 2,
        "scenarios": list(shard.scenarios),
        "candidateCommit": IDENTITY.commit,
        "candidateTree": IDENTITY.tree,
        "candidateRuntimeTree": IDENTITY.runtime_tree,
        "candidateRuntimePatchBase": IDENTITY.runtime_patch_base,
        "candidateRuntimePatchSha256": digest,
        "baselineCommit": "1" * 40,
        "baselineTree": "2" * 40,
        "hostname": f"host-{shard.shard_id}",
        "machineProfile": shard.machine,
        "pairing": "same-host-interleaved-baseline-candidate",
    }
    inputs = {
        "candidateCommit": IDENTITY.commit,
        "candidateTree": IDENTITY.tree,
        "candidateRuntimeTree": IDENTITY.runtime_tree,
        "candidateRuntimePatchBase": IDENTITY.runtime_patch_base,
        "candidateRuntimePatchSha256": digest,
        "baselineCommit": "1" * 40,
        "repetitions": 3,
        "warmups": 1,
        "compileRepetitions": 3,
    }
    files = {
        "shard.json": metadata,
        "inputs.json": inputs,
        "hardware.json": {"hostname": metadata["hostname"]},
        "candidate-provenance.json": {
            "role": "candidate", "commit": IDENTITY.commit, "tree": IDENTITY.tree,
            "source": shard.remote_dir,
        },
        "baseline-provenance.json": {
            "role": "baseline-strict", "commit": "1" * 40, "tree": "2" * 40,
            "source": "/baseline",
        },
        "correctness.json": {
            "schemaVersion": 1,
            "passed": True,
            "policy": benchmark_shards.CORRECTNESS_POLICY,
            "results": [
                {"scenario": scenario, "model": model, "output_sha256": "0" * 64}
                for scenario in shard.scenarios
                for model in (benchmark_plan.BASELINE, benchmark_plan.CANDIDATE)
            ],
        },
        "schedule.json": benchmark_plan.execution_schedule(shard.scenarios, 1, 3, 3),
        "gate.json": {"schemaVersion": 1, "passed": True, "failures": []},
    }
    for name, value in files.items():
        (path / name).write_text(json.dumps(value), encoding="utf-8")
    for name in benchmark_shards.REQUIRED - set(files):
        (path / name).write_text("", encoding="utf-8")
    for name in {"raw.json", "comparison.md"}:
        (path / name).write_text("", encoding="utf-8")


def write_passing_measurements(path: Path, scenarios: tuple[str, ...]) -> None:
    raw = [
        "model\tscenario\trepetition\telapsed_seconds\tthroughput_ops_per_second\tmax_rss_kib\toperations\tlogical_allocations"
    ]
    schedule = json.loads((path / "schedule.json").read_text(encoding="utf-8"))
    for scenario in schedule["scenarios"]:
        for pair in scenario["measurements"]:
            for model in pair["order"]:
                candidate = model == benchmark_plan.CANDIDATE
                raw.append(
                    f"{model}\t{scenario['scenario']}\t{pair['repetition']}\t"
                    f"{'0.9' if candidate else '1.0'}\t{'111.111' if candidate else '100.0'}\t"
                    f"{'90' if candidate else '100'}\t100\t1"
                )
    (path / "raw.tsv").write_text("\n".join(raw) + "\n", encoding="utf-8")
    compile_rows = ["model\trepetition\tcompile_seconds\tcompile_max_rss_kib"]
    for pair in schedule["compilePairs"]:
        for model in pair["order"]:
            candidate = model == benchmark_plan.CANDIDATE
            compile_rows.append(
                f"{model}\t{pair['repetition']}\t{'0.9' if candidate else '1.0'}\t{'90' if candidate else '100'}"
            )
    (path / "compile-raw.tsv").write_text("\n".join(compile_rows) + "\n", encoding="utf-8")
    (path / "static.tsv").write_text(
        "model\tcompile_seconds\tcompile_max_rss_kib\tbinary_bytes\tretain_callsites\trelease_callsites\tallocation_callsites\tcompiler_commit\tmemory_model\tcompiler\n"
        f"baseline-strict\t1.0\t100\t1000\t1\t1\t1\t{'1' * 40}\tstrict\t/baseline/konanc\n"
        f"candidate-arc\t0.9\t90\t990\t1\t1\t1\t{IDENTITY.commit}\tarc\t/candidate/konanc\n",
        encoding="utf-8",
    )


class ParallelBenchmarkTest(unittest.TestCase):
    def test_host_plan_is_disjoint_and_uses_dedicated_worktrees(self):
        primary, secondary = benchmark_parallel.build_host_shards(
            "strings,coroutines", "platform-c-interop platform-c-dynamic-cstring"
        )
        self.assertNotEqual(primary.host, secondary.host)
        self.assertNotEqual(primary.remote_dir, secondary.remote_dir)
        self.assertEqual(("strings", "coroutines"), primary.scenarios)
        self.assertEqual(("platform-c-interop", "platform-c-dynamic-cstring"), secondary.scenarios)
        with self.assertRaisesRegex(SystemExit, "overlap"):
            benchmark_parallel.build_host_shards("strings", "strings")

    def test_automatic_plan_balances_all_scenarios_without_overlap(self):
        scenarios = "strings coroutines platform-c-interop platform-c-dynamic-cstring exceptions"
        primary, secondary = benchmark_parallel.build_balanced_host_shards(scenarios)
        self.assertEqual(
            set(scenarios.split()), set(primary.scenarios) | set(secondary.scenarios)
        )
        self.assertFalse(set(primary.scenarios) & set(secondary.scenarios))
        primary_weight = sum(benchmark_plan.SCENARIO_WEIGHTS[item] for item in primary.scenarios)
        secondary_weight = sum(benchmark_plan.SCENARIO_WEIGHTS[item] for item in secondary.scenarios)
        self.assertLessEqual(abs(primary_weight - secondary_weight), 5)

    def test_remote_mutation_requires_exact_dedicated_benchmark_worktree(self):
        shard = benchmark_parallel.build_host_shards("strings", "coroutines")[0]
        unsafe = benchmark_parallel.HostShard(
            machine=shard.machine,
            host=shard.host,
            remote_dir="/home/olfa/codex-kotlin-arc",
            remote_git=shard.remote_git,
            workers=shard.workers,
            shard_id=shard.shard_id,
            scenarios=shard.scenarios,
        )
        with self.assertRaisesRegex(RuntimeError, "outside configured"):
            benchmark_parallel.validate_remote_worktree(unsafe)

    def test_aliases_resolving_to_same_actual_hostname_fail_closed(self):
        with self.assertRaisesRegex(RuntimeError, "same host"):
            benchmark_parallel.validate_distinct_hostnames(["builder", "builder"], 2)

    def test_same_physical_host_stops_before_snapshot_creation(self):
        shards = benchmark_parallel.build_host_shards("strings", "coroutines")
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(benchmark_parallel, "RESULTS", Path(temporary)), \
                patch.object(benchmark_parallel, "probe_hostname", return_value="builder"), \
                patch.object(benchmark_parallel.arc, "create_snapshot_ref") as snapshot:
            with self.assertRaisesRegex(RuntimeError, "same host"):
                benchmark_parallel.execute("test-wave", shards, None)
        snapshot.assert_not_called()

    def test_dry_run_has_no_snapshot_or_remote_mutation(self):
        output = io.StringIO()
        with patch.object(benchmark_parallel.arc, "create_snapshot_ref") as snapshot, redirect_stdout(output):
            status = benchmark_parallel.main([
                "wave-22", "--primary-scenarios", "strings", "--secondary-scenarios", "coroutines",
                "--dry-run",
            ])
        self.assertEqual(0, status)
        snapshot.assert_not_called()
        payload = json.loads(output.getvalue())
        self.assertTrue(payload["singleImmutableSnapshot"])
        self.assertEqual(2, len(payload["parallelHosts"]))

    def test_automatic_dry_run_assigns_both_physical_hosts(self):
        output = io.StringIO()
        with redirect_stdout(output):
            status = benchmark_parallel.main([
                "wave-auto", "--scenarios", "strings,coroutines,platform-c-interop", "--dry-run",
            ])
        self.assertEqual(0, status)
        payload = json.loads(output.getvalue())
        self.assertEqual(
            {"olfa@10.10.10.8", "olfa@10.10.10.12"},
            {host["host"] for host in payload["parallelHosts"]},
        )
        self.assertEqual(
            {"strings", "coroutines", "platform-c-interop"},
            {scenario for host in payload["parallelHosts"] for scenario in host["scenarios"]},
        )

    def test_bundle_validation_fails_closed_on_stale_runtime_patch(self):
        shard = benchmark_parallel.build_host_shards("strings", "coroutines")[0]
        with tempfile.TemporaryDirectory() as temporary:
            bundle = Path(temporary) / "bundle"
            write_bundle(bundle, shard, runtime_hash="f" * 64)
            with self.assertRaisesRegex(RuntimeError, "stale or mismatched"):
                benchmark_parallel.validate_bundle(bundle, shard, IDENTITY, shard.scenarios)

    def test_bundle_validation_accepts_exact_host_provenance(self):
        shard = benchmark_parallel.build_host_shards("strings", "coroutines")[0]
        with tempfile.TemporaryDirectory() as temporary:
            bundle = Path(temporary) / "bundle"
            write_bundle(bundle, shard)
            self.assertEqual(
                "host-primary",
                benchmark_parallel.validate_bundle(bundle, shard, IDENTITY, shard.scenarios),
            )

    def test_bundle_entries_reject_unexpected_and_symlink_content(self):
        with tempfile.TemporaryDirectory() as temporary:
            bundle = Path(temporary)
            for name in benchmark_parallel.BUNDLE_FILES:
                (bundle / name).write_text("", encoding="utf-8")
            (bundle / "stale.txt").write_text("", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "unexpected"):
                benchmark_parallel.validate_bundle_entries(bundle)
            (bundle / "stale.txt").unlink()
            original = Path.is_symlink
            with patch.object(
                Path,
                "is_symlink",
                autospec=True,
                side_effect=lambda path: path.name == "raw.json" or original(path),
            ):
                with self.assertRaisesRegex(RuntimeError, "raw.json"):
                    benchmark_parallel.validate_bundle_entries(bundle)

    def test_merge_rejects_different_runtime_patch_identities(self):
        shards = benchmark_parallel.build_host_shards("strings", "coroutines")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            left = root / "left"
            right = root / "right"
            write_bundle(left, shards[0])
            write_bundle(right, shards[1], runtime_hash="f" * 64)
            with self.assertRaisesRegex(SystemExit, "runtime tree/patch identity differs"):
                benchmark_shards.merge(root / "merged", [left, right])

    def test_successful_merge_writes_concise_gate_and_combined_proofs(self):
        shards = benchmark_parallel.build_host_shards("strings", "coroutines")
        with tempfile.TemporaryDirectory() as temporary, \
                patch.dict(os.environ, {"ARC_BENCH_ENFORCE": "1"}, clear=False):
            root = Path(temporary)
            bundles = []
            for index, shard in enumerate(shards):
                bundle = root / f"shard-{index}"
                write_bundle(bundle, shard)
                write_passing_measurements(bundle, shard.scenarios)
                bundles.append(bundle)
            destination = root / "merged"
            with redirect_stdout(io.StringIO()):
                self.assertEqual(0, benchmark_shards.merge(destination, bundles))
            gate = json.loads((destination / "gate.json").read_text(encoding="utf-8"))
            self.assertTrue(gate["passed"])
            self.assertEqual({"strings", "coroutines"}, set(gate["scenarios"]))
            correctness = json.loads((destination / "correctness.json").read_text(encoding="utf-8"))
            self.assertTrue(correctness["passed"])
            self.assertEqual({"primary", "secondary"}, set(correctness["shards"]))
            schedule = json.loads((destination / "schedule.json").read_text(encoding="utf-8"))
            self.assertEqual({"primary", "secondary"}, set(schedule["shards"]))

    def test_execution_evidence_rejects_truncated_duplicate_and_reordered_grids(self):
        shard = benchmark_parallel.build_host_shards("strings", "coroutines")[0]
        for mutation, expected in (
            ("truncate-runtime", "grid mismatch"),
            ("duplicate-runtime", "duplicate raw"),
            ("reorder-runtime", "execution order"),
            ("truncate-compile", "compile benchmark grid mismatch"),
            ("tamper-schedule", "execution schedule"),
        ):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                bundle = Path(temporary) / "bundle"
                write_bundle(bundle, shard)
                write_passing_measurements(bundle, shard.scenarios)
                if mutation.startswith(("truncate-runtime", "duplicate-runtime", "reorder-runtime")):
                    path = bundle / "raw.tsv"
                    lines = path.read_text(encoding="utf-8").splitlines()
                    if mutation == "truncate-runtime":
                        lines.pop()
                    elif mutation == "duplicate-runtime":
                        lines.append(lines[1])
                    else:
                        lines[1], lines[2] = lines[2], lines[1]
                    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
                elif mutation == "truncate-compile":
                    path = bundle / "compile-raw.tsv"
                    lines = path.read_text(encoding="utf-8").splitlines()
                    path.write_text("\n".join(lines[:-1]) + "\n", encoding="utf-8")
                else:
                    path = bundle / "schedule.json"
                    schedule = json.loads(path.read_text(encoding="utf-8"))
                    schedule["scenarios"][0]["measurements"][0]["order"].reverse()
                    path.write_text(json.dumps(schedule), encoding="utf-8")
                metadata = benchmark_shards.read_json(bundle / "shard.json")
                with self.assertRaisesRegex(SystemExit, expected):
                    benchmark_shards.validate_execution_evidence(metadata, bundle)

    def test_correctness_proof_rejects_schema_duplicates_invalid_and_mismatched_hashes(self):
        shard = benchmark_parallel.build_host_shards("strings", "coroutines")[0]
        for mutation, expected in (
            ("schema", "schema/policy"),
            ("policy", "schema/policy"),
            ("duplicate", "duplicate"),
            ("invalid-hash", "invalid"),
            ("mismatched-hash", "digest differs"),
        ):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                bundle = Path(temporary) / "bundle"
                write_bundle(bundle, shard)
                proof_path = bundle / "correctness.json"
                proof = json.loads(proof_path.read_text(encoding="utf-8"))
                if mutation == "schema":
                    proof["schemaVersion"] = 2
                elif mutation == "policy":
                    proof["policy"] = "untrusted"
                elif mutation == "duplicate":
                    proof["results"].append(dict(proof["results"][0]))
                elif mutation == "invalid-hash":
                    proof["results"][0]["output_sha256"] = "not-a-sha256"
                else:
                    proof["results"][0]["output_sha256"] = "f" * 64
                proof_path.write_text(json.dumps(proof), encoding="utf-8")
                with self.assertRaisesRegex(SystemExit, expected):
                    benchmark_shards.validate_correctness(bundle, list(shard.scenarios))


if __name__ == "__main__":
    unittest.main()
