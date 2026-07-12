import os
from pathlib import Path
import subprocess
import sys
import tempfile
from contextlib import redirect_stderr, redirect_stdout
import io
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).parent))
import arc
import benchmark_report
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

    def test_ci2_benchmark_recipe_uses_only_the_benchmark_machine(self):
        justfile = (Path(__file__).parents[2] / "Justfile").read_text()
        self.assertIn("ci2-arc-bench wave: ci2-bench-snapshot", justfile)
        recipe = justfile.split("ci2-arc-bench wave: ci2-bench-snapshot", 1)[1].split(
            "ci2-bench-status profile:", 1
        )[0]
        self.assertEqual(4, recipe.count("--machine ci2-bench"))
        self.assertNotIn("--machine ci2 ", recipe)

    def test_remote_runner_serializes_only_measurements_with_the_common_git_lock(self):
        script = (Path(__file__).parent / "remote.sh").read_text()
        self.assertIn("rev-parse --path-format=absolute --git-common-dir", script)
        self.assertIn("codex-arc-host.lock", script)
        self.assertIn("command -v flock", script)
        self.assertIn("mode=-s", script)
        self.assertIn('[[ "$profile" == arc-bench ]] && mode=-x', script)
        self.assertIn("flock %q %q", script)
        self.assertGreaterEqual(script.count("acquire_shared_host_lock"), 3)
        self.assertIn("mv %q %q", script)

    def test_distribution_includes_linux_platform_libraries(self):
        with patch.dict(os.environ, {}, clear=True):
            command = arc.profile_command("dist")
        self.assertIn(":kotlin-native:dist", command)
        self.assertIn(":kotlin-native:distPlatformLibs", command)

    def test_smoke_uses_distribution_fixture(self):
        with patch.dict(os.environ, {}, clear=True):
            command = arc.profile_command("arc-smoke")
        self.assertEqual(["bash", "tools/arc/run_fixture.sh", "smoke"], command)

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
        self.assertIn('validate_provenance "$candidate_dist/.arc-benchmark-provenance.json"', script)
        self.assertIn('validate_provenance "$baseline_dist/.arc-benchmark-provenance.json"', script)

    def test_benchmark_profile_forwards_only_declared_measurement_settings(self):
        with patch.dict(os.environ, {"ARC_BENCH_SCENARIOS": "call-arguments,fields", "UNRELATED": "no"}, clear=True):
            command = arc.profile_command("arc-bench")
        self.assertEqual("env", command[0])
        self.assertIn("ARC_BENCH_SCENARIOS=call-arguments,fields", command)
        self.assertNotIn("UNRELATED=no", command)
        self.assertEqual(["bash", "tools/arc/benchmark_compare.sh"], command[-2:])

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
            "platform-c-interop", "bounded-cycles",
        ):
            self.assertIn(f'"{scenario}" ->', fixture)
        self.assertIn("operations=${result.operations}", fixture)
        self.assertIn("allocations=${result.allocations}", fixture)

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
            "platformCInteropWork": ("boundedCyclesWork", "1_800_000"),
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
