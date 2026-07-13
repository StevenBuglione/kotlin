import os
from pathlib import Path
import subprocess
import sys
import tempfile
from contextlib import redirect_stderr, redirect_stdout
import io
import json
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).parent))
import arc
import benchmark_cache
import benchmark_report
import benchmark_shards
import no_collector_symbols


class ArcProfileTest(unittest.TestCase):
    def test_ci2_machine_defaults_are_independent(self):
        with patch.dict(os.environ, {}, clear=True):
            arc.select_machine("ci2")
            self.assertEqual("olfa@10.10.10.12", os.environ["ARC_REMOTE"])
            self.assertEqual("/home/olfa/codex-kotlin-arc-ci2", os.environ["ARC_REMOTE_DIR"])
            self.assertEqual("/home/olfa/codex-kotlin-rust", os.environ["ARC_REMOTE_GIT"])
            self.assertEqual(16, arc.workers())
            self.assertEqual(
                "ssh://olfa@10.10.10.12/home/olfa/codex-kotlin-rust/.git",
                arc.remote_url(),
            )

    def test_ci2_machine_preserves_explicit_overrides(self):
        with patch.dict(os.environ, {"ARC_REMOTE": "builder@example"}, clear=True):
            arc.select_machine("ci2")
            self.assertEqual("builder@example", os.environ["ARC_REMOTE"])
            self.assertEqual(16, arc.workers())

    def test_ci2_benchmark_machine_has_a_distinct_managed_worktree(self):
        with patch.dict(os.environ, {}, clear=True):
            arc.select_machine("ci2")
            compiler_host = os.environ["ARC_REMOTE"]
            compiler_dir = os.environ["ARC_REMOTE_DIR"]
            compiler_git = os.environ["ARC_REMOTE_GIT"]
        with patch.dict(os.environ, {}, clear=True):
            arc.select_machine("ci2-bench")
            self.assertEqual(compiler_host, os.environ["ARC_REMOTE"])
            self.assertEqual(compiler_git, os.environ["ARC_REMOTE_GIT"])
            self.assertEqual("/home/olfa/codex-kotlin-arc-ci2-bench", os.environ["ARC_REMOTE_DIR"])
            self.assertNotEqual(compiler_dir, os.environ["ARC_REMOTE_DIR"])
            self.assertEqual(16, arc.workers())

    def test_primary_benchmark_machine_is_isolated_on_primary_host(self):
        with patch.dict(os.environ, {}, clear=True):
            arc.select_machine("primary-bench")
            self.assertEqual("olfa@10.10.10.8", os.environ["ARC_REMOTE"])
            self.assertEqual("/home/olfa/codex-kotlin-arc-primary-bench", os.environ["ARC_REMOTE_DIR"])
            self.assertEqual(16, arc.workers())

    def test_ci2_cstring_machine_is_an_isolated_benchmark_lane(self):
        with patch.dict(os.environ, {}, clear=True):
            arc.select_machine("ci2-cstring")
            self.assertEqual("olfa@10.10.10.12", os.environ["ARC_REMOTE"])
            self.assertEqual("/home/olfa/codex-kotlin-arc-ci2-cstring", os.environ["ARC_REMOTE_DIR"])
            self.assertEqual("/home/olfa/codex-kotlin-rust", os.environ["ARC_REMOTE_GIT"])
            self.assertEqual(
                "/home/olfa/codex-kotlin-arc-ci2-bench-baseline-v1.9.10",
                os.environ["ARC_BENCH_BASELINE_SOURCE"],
            )
            self.assertEqual(16, arc.workers())
        script = (Path(__file__).parent / "remote.sh").read_text()
        self.assertIn("/home/olfa/codex-kotlin-arc-ci2-cstring", script)

    def test_parallel_compiler_runtime_and_ssa_lanes_have_distinct_worktrees(self):
        configurations = {}
        for machine in ("ci2", "ci2-bench", "ci2-cstring", "ci2-runtime", "primary-bench", "primary-ssa", "primary-interop"):
            with patch.dict(os.environ, {}, clear=True):
                arc.select_machine(machine)
                configurations[machine] = (
                    os.environ["ARC_REMOTE"],
                    os.environ["ARC_REMOTE_DIR"],
                    arc.workers(),
                )
        self.assertEqual(7, len({value[1] for value in configurations.values()}))
        self.assertEqual(("olfa@10.10.10.12", "/home/olfa/codex-kotlin-arc-ci2-runtime", 8), configurations["ci2-runtime"])
        self.assertEqual(("olfa@10.10.10.8", "/home/olfa/codex-kotlin-arc-ssa", 14), configurations["primary-ssa"])
        self.assertEqual(("olfa@10.10.10.8", "/home/olfa/codex-kotlin-arc-interop", 12), configurations["primary-interop"])

    def test_path_scoped_snapshots_are_normalized_deduplicated_and_exclude_generated_trees(self):
        self.assertEqual(
            ["kotlin-native/runtime/src/legacymm/cpp/Memory.cpp", "tools/arc/arc.py"],
            arc.snapshot_pathspecs([
                ".\\kotlin-native\\runtime\\src\\legacymm\\cpp\\Memory.cpp",
                "tools/arc/arc.py",
                "tools/arc/arc.py",
            ]),
        )
        for invalid in ("../outside", "/absolute", "wasm/wasm.debug.browsers/file", "tools/arc/__pycache__/x.pyc"):
            with self.assertRaises(SystemExit):
                arc.snapshot_pathspecs([invalid])
        with self.assertRaises(SystemExit):
            arc.snapshot_pathspecs([])

    def test_ci2_benchmark_recipe_uses_only_the_benchmark_machine(self):
        justfile = (Path(__file__).parents[2] / "Justfile").read_text()
        self.assertIn("ci2-arc-bench wave: ci2-bench-snapshot", justfile)
        recipe = justfile.split("ci2-arc-bench wave: ci2-bench-snapshot", 1)[1].split(
            "ci2-arc-bench-quick scenarios:", 1
        )[0]
        self.assertEqual(4, recipe.count("--machine ci2-bench"))
        self.assertNotIn("--machine ci2 ", recipe)

    def test_quick_benchmark_preset_is_explicit_and_non_evidence_producing(self):
        with patch.dict(
            os.environ,
            {
                "ARC_BENCH_QUICK": "1",
                "ARC_BENCH_SCENARIOS": "strings,coroutines",
                "ARC_BENCH_ENFORCE": "0",
            },
            clear=True,
        ):
            command = arc.profile_command("arc-bench")
        self.assertIn("ARC_BENCH_QUICK=1", command)
        self.assertIn("ARC_BENCH_SCENARIOS=strings,coroutines", command)
        self.assertIn("ARC_BENCH_ENFORCE=0", command)

        justfile = (Path(__file__).parents[2] / "Justfile").read_text()
        self.assertIn("ci2-arc-bench-quick scenarios: ci2-bench-snapshot", justfile)
        quick_recipe = justfile.split(
            "ci2-arc-bench-quick scenarios: ci2-bench-snapshot", 1
        )[1].split("ci2-bench-status profile:", 1)[0]
        self.assertEqual(1, quick_recipe.count("--quick"))
        self.assertEqual(1, quick_recipe.count("--scenarios"))
        self.assertIn("run arc-bench-quick", quick_recipe)
        self.assertNotIn("benchmark-bundle", quick_recipe)

        script = (Path(__file__).parent / "benchmark_compare.sh").read_text()
        self.assertIn('quick=${ARC_BENCH_QUICK:-0}', script)
        self.assertIn('compile_repetitions=${ARC_BENCH_COMPILE_REPETITIONS:-1}', script)
        self.assertIn('"quickDiagnostic": os.environ.get("ARC_BENCH_QUICK", "0") == "1"', script)

    def test_remote_runner_serializes_only_evidence_measurements_with_the_common_git_lock(self):
        script = (Path(__file__).parent / "remote.sh").read_text()
        self.assertIn("rev-parse --path-format=absolute --git-common-dir", script)
        self.assertIn("codex-arc-host.lock", script)
        self.assertIn("command -v flock", script)
        self.assertIn("mode=-s", script)
        self.assertIn('[[ "$profile" == arc-bench* ]] && mode=-x', script)
        self.assertIn("flock %q %q", script)
        self.assertGreaterEqual(script.count("acquire_shared_host_lock"), 3)
        self.assertIn("mv %q %q", script)

    def test_active_remote_profile_reuse_requires_exact_canonical_command(self):
        script = (Path(__file__).parent / "remote.sh").read_text()
        canonicalization = "requested_command=$(canonical_command \"$@\")"
        running_check = 'if profile_is_running "$profile"; then'
        identity_check = '[[ "$stored_command" == "$requested_command" ]] ||'

        self.assertIn("canonical_command()", script)
        self.assertIn("printf '%q ' \"$@\"", script)
        self.assertIn(canonicalization, script)
        self.assertIn('stored_command=$(<"$state/command")', script)
        self.assertIn(identity_check, script)
        self.assertIn(
            "profile $profile is already running with a different command; "
            "stored command: $stored_command; requested command: $requested_command",
            script,
        )
        self.assertLess(script.index(canonicalization), script.index(running_check, script.index('    start)')))
        self.assertLess(script.index(identity_check), script.index('echo "profile=$profile state=running'))
        self.assertIn("printf '%s\\n' \"$requested_command\" >\"$state/command\"", script)

    def test_finished_remote_profile_still_resets_command_identity(self):
        script = (Path(__file__).parent / "remote.sh").read_text()
        start_case = script.split("    start)", 1)[1].split("    follow)", 1)[0]
        self.assertIn('if [[ -f "$state/pid" && ! -f "$state/exit-status" ]]; then', start_case)
        reset_start = 'rm -f "$state/build.log" "$state/pid" "$state/exit-status" "$state/exit-status.tmp"'
        self.assertIn(reset_start, start_case)
        self.assertIn('"$state/started-at" "$state/command" "$state/runner.sh"', start_case)
        self.assertLess(start_case.index(reset_start), start_case.index("printf '%s\\n' \"$requested_command\""))

    def test_remote_runner_allows_only_exact_managed_paths(self):
        script = (Path(__file__).parent / "remote.sh").read_text()
        self.assertIn('resolved_repo=$(readlink -m -- "$repo")', script)
        self.assertIn('resolved_source=$(readlink -m -- "$source_repo")', script)
        for path in (
            "/home/olfa/codex-kotlin-arc",
            "/home/olfa/codex-kotlin-arc-primary-bench",
            "/home/olfa/codex-kotlin-arc-ci2",
            "/home/olfa/codex-kotlin-arc-ci2-bench",
            "/home/olfa/codex-kotlin-arc-ci2-runtime",
            "/home/olfa/codex-kotlin-arc-ssa",
            "/home/olfa/codex-kotlin-arc-interop",
        ):
            self.assertIn(path, script)
        self.assertIn(
            '[[ "$resolved_source" == /home/olfa/codex-kotlin-rust ]]',
            script,
        )
        self.assertLess(script.index("validate_managed_paths\n"), script.index('case "$action" in'))

    def test_distribution_includes_linux_platform_libraries(self):
        with patch.dict(os.environ, {}, clear=True):
            command = arc.profile_command("dist")
        self.assertIn(":kotlin-native:dist", command)
        self.assertIn(":kotlin-native:distPlatformLibs", command)

    def test_smoke_uses_distribution_fixture(self):
        with patch.dict(os.environ, {}, clear=True):
            command = arc.profile_command("arc-smoke")
        self.assertEqual(["bash", "tools/arc/run_fixture.sh", "smoke"], command)

    def test_frame_elision_unit_uses_remote_llvm_runner(self):
        with patch.dict(os.environ, {}, clear=True):
            command = arc.profile_command("arc-frame-elision-unit")
        self.assertEqual(["bash", "tools/arc/run_frame_elision_unit.sh"], command)

        script = (Path(__file__).parent / "run_frame_elision_unit.sh").read_text()
        self.assertIn('"$llvm/bin/clang++"', script)
        self.assertIn("-Wl,-l:libz.so.1", script)
        self.assertIn("ArcFrameElisionTest.cpp", script)

    def test_return_update_coalescing_unit_is_wired_to_both_remote_builders(self):
        with patch.dict(os.environ, {}, clear=True):
            command = arc.profile_command("arc-return-update-coalescing-unit")
        self.assertEqual(["bash", "tools/arc/run_return_update_coalescing_unit.sh"], command)

        script = (Path(__file__).parent / "run_return_update_coalescing_unit.sh").read_text()
        self.assertNotIn("ARC_RETURN_UPDATE_UNIT_DIR", script)
        self.assertNotIn("rm -rf", script)
        self.assertIn("-DKONAN_LLVMEXT_BUILD_TESTS=ON", script)
        self.assertIn("ctest --test-dir", script)

        justfile = (Path(__file__).parents[2] / "Justfile").read_text()
        self.assertIn("remote-arc-return-update-coalescing-unit: remote-snapshot", justfile)
        self.assertIn("ci2-arc-return-update-coalescing-unit: ci2-snapshot", justfile)
        self.assertGreaterEqual(justfile.count("run arc-return-update-coalescing-unit"), 2)

    def test_field_projection_profile_runs_emitted_ir_and_machine_gates_on_ci2(self):
        with patch.dict(os.environ, {}, clear=True):
            command = arc.profile_command("arc-field-projection")
        self.assertIn(":kotlin-native:backend.native:tests:test", command)
        self.assertIn(":kotlin-native:backend.native:tests:arc_borrowed_field_projection", command)
        self.assertIn(":kotlin-native:backend.native:tests:filecheck_arc_borrowed_field_projection", command)

        justfile = (Path(__file__).parents[2] / "Justfile").read_text()
        self.assertIn("ci2-arc-field-projection: ci2-snapshot", justfile)
        recipe = justfile.split("ci2-arc-field-projection: ci2-snapshot", 1)[1].split(
            "ci2-bench-doctor:", 1
        )[0]
        self.assertIn("--machine ci2 run arc-field-projection", recipe)

    def test_rooted_loop_profile_runs_behavior_codegen_and_final_ir_gates(self):
        with patch.dict(os.environ, {}, clear=True):
            command = arc.profile_command("arc-rooted-loop")
        self.assertIn(":kotlin-native:backend.native:tests:test", command)
        self.assertIn(":kotlin-native:backend.native:tests:arc_rooted_loop_borrowing", command)
        self.assertIn(":kotlin-native:backend.native:tests:filecheck_arc_rooted_loop_codegen", command)
        self.assertIn(":kotlin-native:backend.native:tests:filecheck_arc_rooted_loop_final", command)

        justfile = (Path(__file__).parents[2] / "Justfile").read_text()
        self.assertIn("remote-arc-rooted-loop: remote-snapshot", justfile)
        self.assertIn("ci2-arc-rooted-loop: ci2-snapshot", justfile)
        self.assertGreaterEqual(justfile.count("run arc-rooted-loop"), 2)

    def test_deinit_synthetic_root_profile_is_reproducible_on_both_builders(self):
        with patch.dict(os.environ, {}, clear=True):
            command = arc.profile_command("arc-deinit-synthetic-root")
        self.assertIn(
            ":kotlin-native:backend.native:tests:arc_deinit_synthetic_root",
            command,
        )

        justfile = (Path(__file__).parents[2] / "Justfile").read_text()
        self.assertIn(
            "remote-arc-deinit-synthetic-root: remote-snapshot",
            justfile,
        )
        self.assertIn(
            "ci2-arc-deinit-synthetic-root: ci2-snapshot",
            justfile,
        )
        self.assertGreaterEqual(
            justfile.count("run arc-deinit-synthetic-root"),
            2,
        )

    def test_arc_fixture_tasks_remain_overrideable(self):
        with patch.dict(os.environ, {"ARC_STRESS_TASKS": ":custom:arcStress"}, clear=True):
            command = arc.profile_command("arc-stress")
        self.assertIn(":custom:arcStress", command)
        self.assertIn("--max-workers=28", command)

    def test_race_tsan_uses_thread_sanitizer_without_changing_general_sanitize(self):
        with patch.dict(os.environ, {}, clear=True):
            command = arc.profile_command("arc-race-tsan")
        self.assertIn("ARC_FIXTURE_SANITIZER=thread", command)
        self.assertTrue(any(value.startswith("TSAN_OPTIONS=halt_on_error=1") for value in command))
        self.assertEqual("race", command[-1])

    def test_fixture_rejects_ignored_sanitizer_requests(self):
        script = (Path(__file__).parent / "run_fixture.sh").read_text()
        self.assertIn("sanitizer was not enabled", script)
        self.assertIn("sanitizer is unsupported", script)
        self.assertIn("sanitizer produced no instrumentation symbols", script)

    def test_unowned_death_requires_the_lifetime_diagnostic(self):
        with patch.dict(os.environ, {}, clear=True):
            command = arc.profile_command("arc-unowned-death")
        self.assertEqual(["bash", "tools/arc/run_fixture.sh", "unowned-death"], command)
        script = (Path(__file__).parent / "run_fixture.sh").read_text()
        self.assertIn("grep -Fxq", script)
        self.assertIn("kotlin.IllegalStateException", script)
        self.assertIn("attempted to access an expired @ArcUnowned reference", script)
        self.assertIn("expired @ArcUnowned access was catchable", script)
        self.assertIn("expired @ArcUnowned access unexpectedly survived", script)

        fixture = (Path(__file__).parent / "fixtures" / "unowned-death.kt").read_text()
        self.assertIn("holder.reassign(second)", fixture)
        self.assertIn("holderWithReassignedExpiredTarget()", fixture)
        self.assertIn("check(holder.weakTarget == null)", fixture)
        self.assertIn("churnAllocator()", fixture)
        self.assertIn("catch (failure: Throwable)", fixture)

    def test_no_collector_profile_uses_ordinary_arc_fixture(self):
        with patch.dict(os.environ, {}, clear=True):
            command = arc.profile_command("arc-no-collector")
        self.assertEqual(["bash", "tools/arc/run_fixture.sh", "no-collector"], command)

        script = (Path(__file__).parent / "run_fixture.sh").read_text()
        self.assertIn('if [[ "$profile" == no-collector ]]', script)
        self.assertIn("no_collector_symbols.py", script)
        justfile = (Path(__file__).parents[2] / "Justfile").read_text()
        self.assertIn("remote-arc-no-collector: remote-snapshot", justfile)
        self.assertIn("tools/arc/arc.py run arc-no-collector", justfile)

    def test_no_collector_symbol_command_requests_full_demangled_defined_symbols(self):
        with patch.dict(os.environ, {"ARC_NM": "/opt/llvm/bin/llvm-nm"}, clear=True):
            command = no_collector_symbols.symbol_command(Path("program.kexe"))
        self.assertEqual(
            ["/opt/llvm/bin/llvm-nm", "-a", "-C", "--defined-only", "program.kexe"],
            command,
        )

    def test_no_collector_gate_rejects_specific_collector_implementations(self):
        symbols = """
0001 T EnterFrameArc
0002 T kotlin::gc::ConcurrentMarkAndSweep::PerformFullGC()
0003 t (anonymous namespace)::collectCycles(MemoryState*)
"""
        matches = no_collector_symbols.verify_symbols(symbols)
        self.assertEqual(
            {"concurrent mark-and-sweep collector", "legacy Bacon cycle traversal"},
            {reason for reason, _ in matches},
        )

    def test_no_collector_gate_allows_compatibility_and_unrelated_symbols(self):
        symbols = """
0001 T EnterFrameArc
0002 T Kotlin_native_internal_GC_collect
0003 T Kotlin_getCurrentStackTrace
0004 T user.project.MarkAndSweepReport
0005 T scanBlackboardImage
"""
        self.assertEqual([], no_collector_symbols.verify_symbols(symbols))

    def test_no_collector_gate_requires_arc_symbol_evidence(self):
        with self.assertRaisesRegex(ValueError, "no ARC frame/runtime evidence"):
            no_collector_symbols.verify_symbols("0001 T Kotlin_native_internal_GC_collect\n")

    def test_sanitizer_matrix_and_individual_probes_are_explicit(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(
                ["bash", "tools/arc/sanitizer_probe.sh", "all"],
                arc.profile_command("arc-sanitize"),
            )
            self.assertEqual(
                ["bash", "tools/arc/sanitizer_probe.sh", "tsan"],
                arc.profile_command("arc-sanitize-tsan"),
            )

    def test_benchmark_profiles_prepare_distinct_candidate_and_tag_baseline(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(
                ["bash", "tools/arc/benchmark_candidate.sh"],
                arc.profile_command("arc-bench-candidate"),
            )
            self.assertEqual(
                ["bash", "tools/arc/benchmark_baseline.sh"],
                arc.profile_command("arc-bench-baseline"),
            )
            self.assertEqual(["bash", "tools/arc/benchmark_compare.sh"], arc.profile_command("arc-bench"))

    def test_benchmark_compiler_construction_never_uses_candidate_strict(self):
        script = (Path(__file__).parent / "benchmark_compare.sh").read_text()
        self.assertIn('finalize_compile baseline-strict "$baseline_compiler" strict "$baseline_head"', script)
        self.assertIn('finalize_compile candidate-arc "$candidate_compiler" arc "$candidate_head"', script)
        self.assertNotIn('compile_one candidate-arc "$candidate_compiler" strict', script)
        self.assertIn('common_flags=(-target linux_x64 -opt)', script)
        self.assertIn('validate_provenance "$candidate_provenance"', script)
        self.assertIn('validate_provenance "$baseline_dist/.arc-benchmark-provenance.json"', script)

    def test_benchmark_profile_forwards_only_declared_measurement_settings(self):
        with patch.dict(os.environ, {"ARC_BENCH_SCENARIOS": "call-arguments,fields", "UNRELATED": "no"}, clear=True):
            command = arc.profile_command("arc-bench")
        self.assertEqual("env", command[0])
        self.assertIn("ARC_BENCH_SCENARIOS=call-arguments,fields", command)
        self.assertNotIn("UNRELATED=no", command)
        self.assertEqual(["bash", "tools/arc/benchmark_compare.sh"], command[-2:])

    def test_benchmark_shards_are_explicit_and_merge_rejects_overlap(self):
        self.assertIn("ARC_BENCH_SHARD_ID", arc.BENCHMARK_ENVIRONMENT)
        self.assertIn("ARC_BENCH_SHARD_COUNT", arc.BENCHMARK_ENVIRONMENT)
        script = (Path(__file__).parent / "benchmark_compare.sh").read_text()
        self.assertIn('shard_id=${ARC_BENCH_SHARD_ID:-full}', script)
        self.assertIn('shard_count=${ARC_BENCH_SHARD_COUNT:-1}', script)
        self.assertIn('"$artifacts/shard.json"', script)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = []
            for shard_id in ("left", "right"):
                source = root / shard_id
                source.mkdir()
                sources.append(source)
                metadata = {
                    "schemaVersion": 2,
                    "shardId": shard_id,
                    "shardCount": 2,
                    "scenarios": ["strings"],
                    "candidateCommit": "candidate-commit",
                    "candidateTree": "candidate-tree",
                    "candidateRuntimeTree": "runtime-tree",
                    "candidateRuntimePatchBase": "runtime-base",
                    "candidateRuntimePatchSha256": "a" * 64,
                    "baselineTree": "baseline-tree",
                    "baselineCommit": "baseline",
                    "hostname": "test-host",
                }
                (source / "shard.json").write_text(__import__("json").dumps(metadata))
                inputs = {
                    "candidateCommit": "candidate-commit",
                    "candidateTree": "candidate-tree",
                    "candidateRuntimeTree": "runtime-tree",
                    "candidateRuntimePatchBase": "runtime-base",
                    "candidateRuntimePatchSha256": "a" * 64,
                    "baselineCommit": "baseline",
                }
                (source / "inputs.json").write_text(__import__("json").dumps(inputs))
                (source / "hardware.json").write_text('{"hostname":"test-host"}')
                (source / "candidate-provenance.json").write_text(
                    '{"role":"candidate","commit":"candidate-commit","tree":"candidate-tree"}'
                )
                (source / "baseline-provenance.json").write_text(
                    '{"role":"baseline-strict","commit":"baseline","tree":"baseline-tree"}'
                )
                for name in benchmark_shards.REQUIRED - {
                    "shard.json", "inputs.json", "hardware.json",
                    "candidate-provenance.json", "baseline-provenance.json",
                }:
                    (source / name).write_text("{}")
                (source / "raw.tsv").write_text(
                    "model\tscenario\trepetition\telapsed_seconds\tthroughput_ops_per_second\tmax_rss_kib\toperations\tlogical_allocations\n"
                    "baseline-strict\tstrings\t1\t1.0\t1.0\t1\t1\t1\n"
                )
            with self.assertRaisesRegex(SystemExit, "overlaps shards"):
                benchmark_shards.merge(root / "merged", sources)

    def test_benchmark_build_profiles_ignore_scenarios_but_receive_quick_cache_mode(self):
        with patch.dict(
            os.environ,
            {
                "ARC_BENCH_BUILD_WORKERS": "6",
                "ARC_BENCH_SCENARIOS": "strings",
                "ARC_BENCH_QUICK": "1",
                "ARC_BENCH_CANDIDATE_CACHE_MAX_ENTRIES": "5",
                "ARC_BENCH_RESERVED_CODE_CACHE_SIZE": "384m",
                "JAVA_OPTS": "-Xmx4g",
            },
            clear=True,
        ):
            candidate = arc.profile_command("arc-bench-candidate")
            baseline = arc.profile_command("arc-bench-baseline")
            comparison = arc.profile_command("arc-bench")
        for command in (candidate, baseline):
            self.assertIn("ARC_BENCH_BUILD_WORKERS=6", command)
            self.assertNotIn("ARC_BENCH_SCENARIOS=strings", command)
            self.assertIn("ARC_BENCH_QUICK=1", command)
            self.assertIn("JAVA_OPTS=-Xmx4g", command)
        self.assertIn("ARC_BENCH_CANDIDATE_CACHE_MAX_ENTRIES=5", candidate)
        self.assertIn("ARC_BENCH_RESERVED_CODE_CACHE_SIZE=384m", comparison)
        self.assertIn("JAVA_OPTS=-Xmx4g", comparison)

    def test_named_benchmark_sets_are_stable_and_disjoint_lanes_are_wired(self):
        self.assertEqual("coroutines", arc.BENCHMARK_PRESETS["coroutines"])
        self.assertEqual("strings", arc.BENCHMARK_PRESETS["strings"])
        self.assertEqual(
            "platform-c-interop,platform-c-leaf,platform-c-dynamic-cstring",
            arc.BENCHMARK_PRESETS["interop"],
        )
        self.assertIn("primary-bench", arc.MACHINE_PROFILES)
        self.assertIn("ci2-bench", arc.MACHINE_PROFILES)
        self.assertNotEqual(
            arc.MACHINE_PROFILES["primary-bench"]["ARC_REMOTE"],
            arc.MACHINE_PROFILES["ci2-bench"]["ARC_REMOTE"],
        )
        self.assertEqual(
            "strings,coroutines,platform-c-dynamic-cstring",
            arc.selected_benchmark_scenarios(None, "hotspots"),
        )
        self.assertEqual("fields,arrays", arc.selected_benchmark_scenarios("fields,arrays", None))
        with self.assertRaisesRegex(SystemExit, "mutually exclusive"):
            arc.selected_benchmark_scenarios("strings", "coroutines")

    def test_baseline_cache_fingerprint_rejects_artifact_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dist = root / "dist"
            dist.mkdir()
            (dist / "compiler.jar").write_bytes(b"compiler")
            (dist / "bin").mkdir()
            (dist / "bin" / "konanc").write_text("launcher")
            manifest = dist / ".arc-benchmark-baseline-cache.json"
            benchmark_cache.write_manifest(manifest, dist, "commit", "tree", "/baseline")
            self.assertTrue(
                benchmark_cache.validate_manifest(manifest, dist, "commit", "tree", "/baseline")
            )
            (dist / ".arc-benchmark-provenance.json").write_text("updated metadata")
            self.assertTrue(
                benchmark_cache.validate_manifest(manifest, dist, "commit", "tree", "/baseline")
            )
            self.assertFalse(
                benchmark_cache.validate_manifest(manifest, dist, "commit", "tree", "/other-host")
            )
            (dist / "compiler.jar").write_bytes(b"mutated")
            self.assertFalse(
                benchmark_cache.validate_manifest(manifest, dist, "commit", "tree", "/baseline")
            )

    def test_candidate_cache_is_exact_and_rejects_artifact_or_source_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dist = root / "dist"
            dist.mkdir()
            (dist / "compiler.jar").write_bytes(b"compiler")
            (dist / "bin").mkdir()
            (dist / "bin" / "konanc").write_text("launcher")
            manifest = dist / ".arc-benchmark-candidate-cache.json"
            benchmark_cache.write_candidate_manifest(manifest, dist, "commit", "tree", "/candidate")
            self.assertTrue(
                benchmark_cache.validate_candidate_manifest(
                    manifest, dist, "commit", "tree", "/candidate"
                )
            )
            self.assertFalse(
                benchmark_cache.validate_candidate_manifest(
                    manifest, dist, "commit", "other-tree", "/candidate"
                )
            )
            self.assertFalse(
                benchmark_cache.validate_candidate_manifest(
                    manifest, dist, "commit", "tree", "/other-host"
                )
            )
            (dist / "compiler.jar").write_bytes(b"mutated")
            self.assertFalse(
                benchmark_cache.validate_candidate_manifest(
                    manifest, dist, "commit", "tree", "/candidate"
                )
            )

    def test_candidate_content_key_covers_tree_and_external_compiler_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            with patch.object(
                benchmark_cache, "compiler_build_inputs", return_value={"toolchain": "a"}
            ):
                first = benchmark_cache.candidate_content_key("commit", "tree", source)
                self.assertEqual(first, benchmark_cache.candidate_content_key("commit", "tree", source))
                self.assertNotEqual(
                    first, benchmark_cache.candidate_content_key("commit", "other-tree", source)
                )
            with patch.object(
                benchmark_cache, "compiler_build_inputs", return_value={"toolchain": "b"}
            ):
                self.assertNotEqual(
                    first, benchmark_cache.candidate_content_key("commit", "tree", source)
                )

    def test_candidate_content_key_rejects_changed_local_properties_and_native_environment(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            with patch.object(benchmark_cache, "_tool_identity", return_value="pinned-tool"):
                with patch.dict(os.environ, {}, clear=True):
                    initial = benchmark_cache.candidate_content_key("commit", "tree", source)
                    (source / "local.properties").write_text("konan.data.dir=/first\n", encoding="utf-8")
                    local_changed = benchmark_cache.candidate_content_key("commit", "tree", source)
                with patch.dict(
                    os.environ,
                    {
                        "CC": "/opt/clang -fuse-ld=lld", "CFLAGS": "-O3",
                        "KONAN_DATA_DIR": "/konan", "JAVA_OPTS": "-Xmx4g",
                    },
                    clear=True,
                ):
                    environment_changed = benchmark_cache.candidate_content_key(
                        "commit", "tree", source
                    )
                    inputs = benchmark_cache.compiler_build_inputs(source)
            self.assertNotEqual(initial, local_changed)
            self.assertNotEqual(local_changed, environment_changed)
            self.assertEqual("/opt/clang -fuse-ld=lld", inputs["nativeTools"]["cc"]["command"])
            self.assertEqual("-O3", inputs["nativeEnvironment"]["CFLAGS"])
            self.assertEqual("/konan", inputs["nativeEnvironment"]["KONAN_DATA_DIR"])
            self.assertEqual("-Xmx4g", inputs["javaOpts"])

    def test_candidate_content_key_changes_with_java_opts(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            with patch.object(benchmark_cache, "_tool_identity", return_value="pinned-tool"):
                with patch.dict(os.environ, {"JAVA_OPTS": "-Xmx3g"}, clear=True):
                    first = benchmark_cache.candidate_content_key("commit", "tree", source)
                with patch.dict(os.environ, {"JAVA_OPTS": "-Xmx4g"}, clear=True):
                    second = benchmark_cache.candidate_content_key("commit", "tree", source)
            self.assertNotEqual(first, second)

    def test_content_addressed_candidate_is_sealed_and_fully_fingerprinted(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            dist = root / "build-dist"
            (dist / "bin").mkdir(parents=True)
            for launcher in ("konanc", "cinterop"):
                path = dist / "bin" / launcher
                path.write_text("launcher", encoding="utf-8")
                path.chmod(0o755)
            cache = root / "cache"
            inputs = {"toolchain": "pinned"}
            with patch.object(benchmark_cache, "compiler_build_inputs", return_value=inputs):
                cached = benchmark_cache.publish_content_candidate(
                    cache, dist, "commit", "tree", source
                )
                manifest = cached.parent / "manifest.json"
                self.assertEqual(
                    benchmark_cache.candidate_content_key("commit", "tree", source),
                    cached.parent.name,
                )
                self.assertFalse(cached.stat().st_mode & 0o200)
                self.assertTrue(benchmark_cache.validate_content_candidate_manifest(
                    manifest, cached, "commit", "tree", source
                ))
                self.assertTrue(benchmark_cache.validate_content_candidate_manifest(
                    manifest, cached, "commit", "tree", source, fast=True
                ))
                compiler = cached / "bin" / "konanc"
                compiler.chmod(0o755)
                compiler.write_text("mutated", encoding="utf-8")
                self.assertFalse(benchmark_cache.validate_content_candidate_manifest(
                    manifest, cached, "commit", "tree", source
                ))

    def test_candidate_cache_retention_is_owned_lru_leased_and_safe(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            dist = root / "dist"
            (dist / "bin").mkdir(parents=True)
            for launcher in ("konanc", "cinterop"):
                (dist / "bin" / launcher).write_text("launcher", encoding="utf-8")
            cache = root / "cache"
            with patch.object(benchmark_cache, "compiler_build_inputs", return_value={"tool": "x"}):
                keys = []
                for index in range(3):
                    cached = benchmark_cache.publish_content_candidate(
                        cache, dist, f"commit-{index}", "tree", source
                    )
                    keys.append(cached.parent.name)
                    benchmark_cache.mark_content_candidate_used(
                        cache, cached.parent.name, now_ns=(index + 1) * 100
                    )

            lease_id = "f" * 64
            removed = benchmark_cache.maintain_content_candidates(
                cache, keys[0], 1, now_ns=1_000, lease_id=lease_id
            )
            self.assertEqual([keys[1], keys[2]], removed)
            self.assertTrue((cache / "candidate" / keys[0]).is_dir())
            self.assertTrue((cache / "candidate-leases" / f"{lease_id}.json").is_file())
            self.assertFalse((cache / "candidate-last-used" / f"{keys[1]}.json").exists())
            self.assertEqual([], list((cache / "candidate-trash").iterdir()))

            # Zero disables pruning but still updates metadata.
            self.assertEqual(
                [], benchmark_cache.maintain_content_candidates(cache, keys[0], 0, now_ns=2_000)
            )
            access = json.loads(
                (cache / "candidate-last-used" / f"{keys[0]}.json").read_text(encoding="utf-8")
            )
            self.assertEqual(2_000, access["lastUsedNs"])

    def test_candidate_cache_refuses_foreign_root_and_preserves_sentinels(self):
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary) / "foreign"
            key = "a" * 64
            entry = cache / "candidate" / key
            entry.mkdir(parents=True)
            sentinel = entry / "sentinel"
            sentinel.write_text("foreign", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "foreign candidate cache root"):
                benchmark_cache.maintain_content_candidates(cache, key, 1)
            self.assertEqual("foreign", sentinel.read_text(encoding="utf-8"))

    def test_candidate_cache_leases_prevent_cross_worktree_pruning(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            dist = root / "dist"
            (dist / "bin").mkdir(parents=True)
            for launcher in ("konanc", "cinterop"):
                (dist / "bin" / launcher).write_text("launcher", encoding="utf-8")
            cache = root / "cache"
            with patch.object(benchmark_cache, "compiler_build_inputs", return_value={"tool": "x"}):
                first = benchmark_cache.publish_content_candidate(cache, dist, "first", "tree", source).parent.name
            benchmark_cache.maintain_content_candidates(
                cache, first, 1, now_ns=1_000, lease_id="1" * 64
            )
            with patch.object(benchmark_cache, "compiler_build_inputs", return_value={"tool": "x"}):
                second = benchmark_cache.publish_content_candidate(cache, dist, "second", "tree", source).parent.name
            removed = benchmark_cache.maintain_content_candidates(
                cache, second, 1, now_ns=2_000, lease_id="2" * 64
            )
            self.assertEqual([], removed)
            self.assertTrue((cache / "candidate" / first).is_dir())
            self.assertTrue((cache / "candidate" / second).is_dir())

    def test_candidate_cache_metadata_rejects_invalid_times(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            entry = root / "candidate" / ("a" * 64)
            entry.mkdir(parents=True)
            (entry / "manifest.json").write_text("{}", encoding="utf-8")
            fallback = (entry / "manifest.json").stat().st_mtime_ns
            path = root / "candidate-last-used" / f"{entry.name}.json"
            path.parent.mkdir()
            for value in (True, -1, 10**30):
                path.write_text(json.dumps({"schema": 1, "cacheKey": entry.name, "lastUsedNs": value}))
                self.assertEqual(
                    fallback, benchmark_cache._candidate_last_used_ns(root, entry, 1_000)
                )

    def test_candidate_cache_rejects_symlinked_root_and_managed_directories(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            actual = root / "actual"
            actual.mkdir()
            (actual / "owner.json").write_text(json.dumps(benchmark_cache.CACHE_OWNER))
            linked_root = root / "linked-root"
            try:
                linked_root.symlink_to(actual, target_is_directory=True)
            except OSError as error:
                self.skipTest(f"directory symlinks unavailable: {error}")
            with self.assertRaisesRegex(ValueError, "root must be a real directory"):
                benchmark_cache._ensure_cache_root_owned(linked_root)

            for index, name in enumerate(benchmark_cache.MANAGED_CACHE_DIRECTORIES):
                cache = root / f"cache-{index}"
                cache.mkdir()
                (cache / "owner.json").write_text(json.dumps(benchmark_cache.CACHE_OWNER))
                target = root / f"target-{index}"
                target.mkdir()
                (cache / name).symlink_to(target, target_is_directory=True)
                with self.subTest(name=name), self.assertRaisesRegex(
                    ValueError, "managed path must be a real directory"
                ):
                    benchmark_cache._ensure_cache_root_owned(cache)

    def test_candidate_cache_recovers_only_exact_marked_trash(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            dist = root / "dist"
            (dist / "bin").mkdir(parents=True)
            for launcher in ("konanc", "cinterop"):
                (dist / "bin" / launcher).write_text("launcher", encoding="utf-8")
            cache = root / "cache"
            with patch.object(benchmark_cache, "compiler_build_inputs", return_value={"tool": "x"}):
                current = benchmark_cache.publish_content_candidate(
                    cache, dist, "current", "tree", source
                ).parent.name
                victim = benchmark_cache.publish_content_candidate(
                    cache, dist, "victim", "tree", source
                ).parent.name
            benchmark_cache.mark_content_candidate_used(cache, victim, now_ns=100)

            trash = cache / "candidate-trash"
            retired_name = f"{victim}-{'1' * 32}"
            tombstone = benchmark_cache._write_retirement_tombstone(
                cache, victim, retired_name
            )
            retired = trash / retired_name
            (cache / "candidate" / victim).replace(retired)

            external = root / "external"
            external.mkdir()
            sentinel = external / "sentinel"
            sentinel.write_text("preserved", encoding="utf-8")
            try:
                (retired / "external-link").symlink_to(external, target_is_directory=True)
            except OSError as error:
                self.skipTest(f"directory symlinks unavailable: {error}")
            foreign = trash / "foreign-unmarked"
            foreign.mkdir()
            (foreign / "sentinel").write_text("foreign", encoding="utf-8")
            invalid_tombstone = trash / "invalid.tombstone.json"
            invalid_tombstone.write_text('{"schema":1,"cacheKey":"wrong"}', encoding="utf-8")

            self.assertEqual(
                [], benchmark_cache.maintain_content_candidates(cache, current, 0, now_ns=200)
            )
            self.assertFalse(retired.exists())
            self.assertFalse(tombstone.exists())
            self.assertFalse((cache / "candidate-last-used" / f"{victim}.json").exists())
            self.assertEqual("preserved", sentinel.read_text(encoding="utf-8"))
            self.assertEqual("foreign", (foreign / "sentinel").read_text(encoding="utf-8"))
            self.assertTrue(invalid_tombstone.exists())

            # Even an exact tombstone cannot authorize following a symlinked retired tree.
            symlink_name = f"{victim}-{'2' * 32}"
            symlink_tombstone = benchmark_cache._write_retirement_tombstone(
                cache, victim, symlink_name
            )
            symlink_retired = trash / symlink_name
            symlink_retired.symlink_to(external, target_is_directory=True)
            benchmark_cache.maintain_content_candidates(cache, current, 0, now_ns=300)
            self.assertTrue(symlink_retired.is_symlink())
            self.assertTrue(symlink_tombstone.exists())
            self.assertEqual("preserved", sentinel.read_text(encoding="utf-8"))

    def test_candidate_build_cache_is_exact_and_force_rebuild_is_explicit(self):
        script = (Path(__file__).parent / "benchmark_candidate.sh").read_text()
        self.assertIn("ARC_BENCH_REBUILD_CANDIDATE", script)
        self.assertIn('valid_provenance &&', script)
        self.assertIn("legacy_validation=validate-candidate", script)
        self.assertIn("ARC_BENCH_CANDIDATE_CACHE_HIT", script)
        self.assertIn("ARC_BENCH_CANDIDATE_CACHE_MISS", script)
        self.assertIn("ARC_BENCH_CANDIDATE_CONTENT_CACHE_HIT", script)
        self.assertIn("candidate-key", script)
        compare = (Path(__file__).parent / "benchmark_compare.sh").read_text()
        self.assertIn("validate-content-candidate", compare)
        self.assertIn("candidate distribution fingerprint is missing, stale, or corrupt", compare)

    def test_benchmark_compilers_share_recorded_non_overriding_java_options(self):
        script = (Path(__file__).parent / "benchmark_compare.sh").read_text()
        self.assertIn('reserved_code_cache_size=${ARC_BENCH_RESERVED_CODE_CACHE_SIZE:-256m}', script)
        self.assertIn('if [[ ! "$benchmark_java_opts" =~ (^|[[:space:]])-XX:ReservedCodeCacheSize= ]]', script)
        self.assertIn('benchmark_java_opts="${benchmark_java_opts:+$benchmark_java_opts }-XX:ReservedCodeCacheSize=$reserved_code_cache_size"', script)
        self.assertEqual(2, script.count('"${benchmark_java_env[@]}"'))
        self.assertIn('"benchmarkJavaOpts": benchmark_java_opts', script)
        self.assertIn('payload["benchmarkJavaOpts"] = sys.argv[3]', script)
        for key in ("benchmarkJavaOpts", "benchmarkJvmTools", "benchmarkJvmEnvironment"):
            self.assertIn(key, benchmark_shards.COMPATIBLE_INPUT_KEYS)

    def test_full_evidence_requires_current_provenance_and_full_content_validation(self):
        candidate = (Path(__file__).parent / "benchmark_candidate.sh").read_text()
        compare = (Path(__file__).parent / "benchmark_compare.sh").read_text()
        self.assertIn('>"$pointer_dir/candidate-provenance.json.tmp"', candidate)
        self.assertIn(
            'mv "$pointer_dir/candidate-provenance.json.tmp" "$pointer_dir/candidate-provenance.json"',
            candidate,
        )
        self.assertIn(
            'candidate_provenance="$root/.arc-runs/benchmark-cache/candidate-provenance.json"',
            compare,
        )
        self.assertIn('validate_provenance "$candidate_provenance"', compare)
        self.assertIn("candidate_cache_action=validate-content-candidate", compare)
        self.assertIn('[[ "$quick" == 1 ]] && candidate_cache_action=validate-content-candidate-fast', compare)
        self.assertIn('cp "$candidate_provenance" "$wave/candidate-provenance.json"', compare)

    def test_candidate_rebuild_setting_is_forwarded_only_to_candidate_profile(self):
        with patch.dict(os.environ, {"ARC_BENCH_REBUILD_CANDIDATE": "1"}, clear=True):
            candidate = arc.profile_command("arc-bench-candidate")
            baseline = arc.profile_command("arc-bench-baseline")
            comparison = arc.profile_command("arc-bench")
        self.assertIn("ARC_BENCH_REBUILD_CANDIDATE=1", candidate)
        self.assertNotIn("ARC_BENCH_REBUILD_CANDIDATE=1", baseline)
        self.assertNotIn("ARC_BENCH_REBUILD_CANDIDATE=1", comparison)

    def test_baseline_cache_is_exact_and_force_rebuild_is_explicit(self):
        script = (Path(__file__).parent / "benchmark_baseline.sh").read_text()
        self.assertIn("ARC_BENCH_REBUILD_BASELINE", script)
        self.assertIn('valid_provenance &&', script)
        self.assertIn("validation=validate", script)
        self.assertIn("ARC_BENCH_BASELINE_CACHE_HIT", script)
        self.assertIn("ARC_BENCH_BASELINE_CACHE_MISS", script)
        compare = (Path(__file__).parent / "benchmark_compare.sh").read_text()
        self.assertIn('benchmark_cache.py"', compare)
        self.assertIn("baseline distribution fingerprint is missing, stale, or corrupt", compare)

    def test_benchmark_baseline_is_pinned_and_separate(self):
        script = (Path(__file__).parent / "benchmark_baseline.sh").read_text()
        self.assertIn("3db61efe5e892bf27115f1ebcab957d903067ed4", script)
        self.assertIn('[[ "$baseline" != "$root" ]]', script)
        self.assertIn("worktree add --detach", script)
        self.assertIn("codex-arc-benchmark-baseline-v1.9.10", script)

    def test_benchmark_fixture_covers_required_scenarios(self):
        fixture = (Path(__file__).parent / "fixtures" / "benchmark.kt").read_text()
        for scenario in (
            "allocation", "destruction", "fields", "arrays", "strings", "virtual-dispatch",
            "call-arguments", "closures", "exceptions", "coroutines", "workers", "atomics",
            "platform-c-interop", "platform-c-leaf", "platform-c-dynamic-cstring", "bounded-cycles",
        ):
            self.assertIn(f'"{scenario}" ->', fixture)
        self.assertIn("operations=${result.operations}", fixture)
        self.assertIn("allocations=${result.allocations}", fixture)

    def test_c_interop_benchmarks_separate_static_leaf_and_conversion_costs(self):
        root = Path(__file__).parent
        fixture = (root / "fixtures" / "benchmark.kt").read_text()
        header = (root / "fixtures" / "benchmark_cinterop.h").read_text()
        script = (root / "benchmark_compare.sh").read_text()

        definition = (root / "fixtures" / "benchmark_cinterop.def").read_text()
        self.assertIn('__attribute__((noinline)) size_t arc_benchmark_strlen_ptr', header)
        self.assertIn('__attribute__((noinline)) size_t arc_benchmark_strlen_string', header)
        self.assertIn('noStringConversion = arc_benchmark_strlen_ptr', definition)
        self.assertIn(
            'noCallbackFunctions = arc_benchmark_strlen_ptr arc_benchmark_strlen_string',
            definition,
        )
        self.assertIn('val phase = getpid() and 7', fixture)
        self.assertIn('val address = pinned.addressOf(0)', fixture)
        self.assertIn('arc_benchmark_strlen_ptr(address)', fixture)
        self.assertNotIn('arc_benchmark_strlen_ptr(pinned.addressOf(0))', fixture)
        self.assertIn('buffer[previousTerminator] = \'x\'.code.toByte()', fixture)
        self.assertIn('buffer[terminator] = 0', fixture)
        self.assertIn('check(checksum == 230_000_000L)', fixture)
        self.assertIn('var value = "kotlin-native-arc-0"', fixture)
        self.assertIn('if ((index and 1023) == 0) value =', fixture)
        self.assertIn('arc_benchmark_strlen_string(value)', fixture)
        self.assertIn('check(checksum == 34_200_000L)', fixture)
        self.assertIn('prepare_interop baseline-strict "$baseline_compiler"', script)
        self.assertIn('-library "$artifacts/$label-benchmark-cinterop.klib"', script)
        self.assertIn('platform-c-interop platform-c-leaf platform-c-dynamic-cstring', script)
        self.assertIn('"interopDefinitionSha256"', script)
        self.assertIn('"interopHeaderSha256"', script)

        count = 20_000_000
        for phase in range(8):
            full_cycles, remainder = divmod(count, 8)
            checksum = full_cycles * sum(range(8, 16))
            checksum += sum(8 + ((index + phase) & 7) for index in range(remainder))
            self.assertEqual(230_000_000, checksum)

    def test_call_arguments_benchmark_exercises_stable_suffix_codegen(self):
        fixture = (Path(__file__).parent / "fixtures" / "benchmark.kt").read_text()
        script = (Path(__file__).parent / "benchmark_compare.sh").read_text()
        self.assertIn("private fun consumeArguments(first: Payload, marker: Int, second: Payload): Long", fixture)
        self.assertIn("if (marker == Int.MIN_VALUE)", fixture)
        self.assertNotIn("return consumeArguments(", fixture)
        self.assertIn("val count = 80_000_000", fixture)
        self.assertIn("var first = Payload(1)", fixture)
        self.assertIn("var second = Payload(7)", fixture)
        self.assertIn("if ((index and 0x3fff) == 0) first = Payload(index)", fixture)
        self.assertIn("checksum += consumeArguments(first, index, second)", fixture)
        self.assertIn("check(checksum == 30_394_430_133_801_472L)", fixture)
        self.assertIn("2L + replacements", fixture)
        self.assertIn("virtual-dispatch call-arguments closures", script)
        self.assertIn('${scenario_selection//,/ }', script)

        count = 80_000_000
        block = 0x4000
        full_blocks, remainder = divmod(count, block)
        full_coefficients = sum(range(1, 17)) * (block // 16)
        remainder_coefficients = sum((index & 15) + 1 for index in range(remainder))
        payload_component = sum(
            (block_index * block) * full_coefficients for block_index in range(full_blocks)
        ) + (full_blocks * block) * remainder_coefficients
        checksum = count * (count - 1) // 2 + 7 * count + payload_component
        self.assertEqual(30_394_430_133_801_472, checksum)
        self.assertEqual(4_885, 2 + (count + block - 1) // block)

    def test_short_benchmarks_are_scaled_without_expanding_cycle_leaks(self):
        fixture = (Path(__file__).parent / "fixtures" / "benchmark.kt").read_text()
        script = (Path(__file__).parent / "benchmark_compare.sh").read_text()

        def body(name, next_name):
            return fixture.split(f"private fun {name}", 1)[1].split(f"private fun {next_name}", 1)[0]

        expected_counts = {
            "allocationWork": ("destructionWork", "6_000_000"),
            "destructionWork": ("fieldsWork", "1_500_000"),
            "fieldsWork": ("arraysWork", "200_000_000"),
            "arraysWork": ("stringsWork", "120_000_000"),
            "stringsWork": ("virtualDispatchWork", "600_000"),
            "virtualDispatchWork": ("consumeArguments", "80_000_000"),
            "callArgumentsWork": ("closuresWork", "80_000_000"),
            "closuresWork": ("exceptionsWork", "600_000_000"),
            "coroutinesWork": ("workerLoop", "300_000"),
            "atomicsWork": ("platformCInteropWork", "40_000_000"),
            "platformCInteropWork": ("platformCLeafWork", "1_800_000"),
            "platformCLeafWork": ("platformCDynamicCStringWork", "20_000_000"),
            "platformCDynamicCStringWork": ("boundedCyclesWork", "1_800_000"),
        }
        for name, (next_name, count) in expected_counts.items():
            self.assertIn(f"val count = {count}", body(name, next_name), name)
        worker_loop = body("workerLoop", "workersWork")
        self.assertIn("val value = AtomicInt(seed)", worker_loop)
        self.assertIn("repeat(40_000_000)", worker_loop)
        self.assertIn("checksum += value.addAndGet(1)", worker_loop)
        self.assertIn("BenchResult(checksum, 160_000_000L, 16)", body("workersWork", "atomicsWork"))
        exceptions = fixture.split("private fun exceptionsWork", 1)[1].split("private suspend fun suspendStep", 1)[0]
        self.assertIn("val count = 100_000", exceptions)
        self.assertIn("repetitions=${ARC_BENCH_REPETITIONS:-9}", script)

        cycles = body("boundedCyclesWork", "main")
        self.assertIn("val count = 25_000", cycles)
        self.assertIn("val traversalsPerCycle = 10_240", cycles)
        self.assertIn("count * 2L", cycles)
        cycle_count = 25_000
        traversals = 10_240
        checksum = (traversals // 2) * cycle_count * cycle_count
        self.assertEqual(3_200_000_000_000, checksum)
        self.assertEqual(256_000_000, cycle_count * traversals)
        self.assertEqual(50_000, cycle_count * 2)
        self.assertIn(f"check(checksum == {checksum:_}L)", cycles)

        logical_allocations = {
            "allocation": 6_000_001,
            "destruction": 1_500_000,
            "fields": 12_210,
            "arrays": 1,
            "strings": 1_800_000,
            "virtual-dispatch": 4,
            "call-arguments": 4_885,
            "closures": 600_000_000,
            "coroutines": 900_000,
            "workers": 16,
            "atomics": 1,
            "bounded-cycles": 50_000,
        }
        modeled_allocations = {
            "allocation": 6_000_000 + 1,
            "destruction": 1_500_000,
            "fields": 2 + (200_000_000 + 0x3FFF) // 0x4000,
            "arrays": 1,
            "strings": 600_000 * 3,
            "virtual-dispatch": 4,
            "call-arguments": 2 + (80_000_000 + 0x3FFF) // 0x4000,
            "closures": 600_000_000,
            "coroutines": 300_000 * 3,
            "workers": 16,
            "atomics": 1,
            "bounded-cycles": cycle_count * 2,
        }
        self.assertEqual(logical_allocations, modeled_allocations)

    def test_benchmark_callsite_counter_counts_only_emitted_calls(self):
        disassembly = """
0000 <UpdateStackRef>:
  10: callq 100 <UpdateStackRef>
  20: call 200 <SetHeapRef>
  30: call 300 <LeaveFrameArc>
  40: lea 400 <ReleaseHeapRef>
  50: call 500 <AllocArrayInstance>
"""
        self.assertEqual((2, 3), benchmark_report.ownership_calls(disassembly))
        self.assertEqual((2, 3, 1), benchmark_report.emitted_calls(disassembly))

    def test_benchmark_compile_and_callsites_are_reported_but_not_hard_gates(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "raw.tsv").write_text(
                "model\tscenario\trepetition\telapsed_seconds\tthroughput_ops_per_second\tmax_rss_kib\toperations\tlogical_allocations\n"
                "baseline-strict\tfields\t1\t1.0\t100.0\t100\t100\t1\n"
                "candidate-arc\tfields\t1\t1.0\t100.0\t100\t100\t1\n"
            )
            (root / "compile.tsv").write_text(
                "model\trepetition\tcompile_seconds\tcompile_max_rss_kib\n"
                "baseline-strict\t1\t1.0\t100\n"
                "candidate-arc\t1\t10.0\t1000\n"
            )
            (root / "static.tsv").write_text(
                "model\tcompile_seconds\tcompile_max_rss_kib\tbinary_bytes\tretain_callsites\trelease_callsites\tallocation_callsites\tcompiler_commit\tmemory_model\tcompiler\n"
                "baseline-strict\t1.0\t100\t1000\t1\t1\t1\tbase\tstrict\t/base/konanc\n"
                "candidate-arc\t10.0\t1000\t1000\t100\t100\t100\tcandidate\tarc\t/candidate/konanc\n"
            )
            with patch.dict(os.environ, {}, clear=True):
                with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                    result = benchmark_report.report(
                        root / "raw.tsv", root / "compile.tsv", root / "static.tsv", root / "out"
                    )
            self.assertEqual(0, result)
            summary = (root / "out" / "summary.json").read_text()
            self.assertIn('"compileSecondsDeltaPercent": 900.0', summary)
            self.assertIn('"retainCallsitesDeltaPercent": 9900.0', summary)

    def test_benchmark_hard_defaults_reject_more_than_five_percent_latency(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "raw.tsv").write_text(
                "model\tscenario\trepetition\telapsed_seconds\tthroughput_ops_per_second\tmax_rss_kib\toperations\tlogical_allocations\n"
                "baseline-strict\tfields\t1\t1.0\t100.0\t100\t100\t1\n"
                "candidate-arc\tfields\t1\t1.06\t94.34\t100\t100\t1\n"
            )
            (root / "compile.tsv").write_text(
                "model\trepetition\tcompile_seconds\tcompile_max_rss_kib\n"
                "baseline-strict\t1\t1.0\t100\n"
                "candidate-arc\t1\t1.0\t100\n"
            )
            (root / "static.tsv").write_text(
                "model\tcompile_seconds\tcompile_max_rss_kib\tbinary_bytes\tretain_callsites\trelease_callsites\tallocation_callsites\tcompiler_commit\tmemory_model\tcompiler\n"
                "baseline-strict\t1.0\t100\t1000\t1\t1\t1\tbase\tstrict\t/base/konanc\n"
                "candidate-arc\t1.0\t100\t1000\t1\t1\t1\tcandidate\tarc\t/candidate/konanc\n"
            )
            with patch.dict(os.environ, {}, clear=True):
                with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                    result = benchmark_report.report(
                        root / "raw.tsv", root / "compile.tsv", root / "static.tsv", root / "out"
                    )
            self.assertEqual(1, result)
            self.assertIn("latency regression 6.000% > 5.000%", (root / "out" / "comparison.md").read_text())

    def test_benchmark_recipe_exports_commit_ready_wave_even_on_gate_failure(self):
        justfile = (Path(__file__).parents[2] / "Justfile").read_text()
        self.assertIn("remote-arc-bench wave: remote-snapshot", justfile)
        self.assertIn("run arc-bench-candidate", justfile)
        self.assertIn("run arc-bench-baseline", justfile)
        self.assertIn("benchmark-bundle {{wave}}", justfile)
        script = (Path(__file__).parent / "benchmark_compare.sh").read_text()
        for artifact in ("inputs.json", "hardware.json", "raw.tsv", "raw.json", "compile-raw.tsv", "comparison.md"):
            self.assertIn(artifact, script)

    def test_sanitizer_probe_requires_binary_instrumentation_evidence(self):
        script = (Path(__file__).parent / "sanitizer_probe.sh").read_text()
        self.assertIn("readelf -Ws", script)
        self.assertIn("objdump -d", script)
        self.assertIn("no_ubsan_instrumentation_calls", script)
        self.assertIn("-Xbinary=undefinedBehaviorSanitizer=true", script)
        self.assertIn("UNSUPPORTED", script)
        self.assertIn("exit 77", script)

    def test_snapshot_pathspec_excludes_browser_checkout(self):
        self.assertIn(".arc-runs", arc.EXCLUDED_PATHS)
        self.assertIn("wasm/wasm.debug.browsers", arc.EXCLUDED_PATHS)
        self.assertIn("tools/arc/__pycache__", arc.EXCLUDED_PATHS)

    def test_push_targets_shared_source_repository(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(
                "ssh://olfa@10.10.10.8/home/olfa/codex-kotlin-rust/.git",
                arc.remote_url(),
            )

    def test_all_durable_profiles_have_commands(self):
        with patch.dict(os.environ, {}, clear=True):
            for profile in arc.PROFILES:
                self.assertTrue(arc.profile_command(profile), profile)


if __name__ == "__main__":
    unittest.main()
