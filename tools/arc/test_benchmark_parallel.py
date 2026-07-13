from __future__ import annotations

from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).parent))
import benchmark_parallel
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
    }
    for name, value in files.items():
        (path / name).write_text(json.dumps(value), encoding="utf-8")
    for name in benchmark_shards.REQUIRED - set(files):
        (path / name).write_text("", encoding="utf-8")
    for name in {"raw.json", "comparison.md"}:
        (path / name).write_text("", encoding="utf-8")


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


if __name__ == "__main__":
    unittest.main()
