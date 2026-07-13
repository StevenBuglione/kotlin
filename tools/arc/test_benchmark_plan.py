from __future__ import annotations

import json
from pathlib import Path
import sys
import tempfile
import unittest


sys.path.insert(0, str(Path(__file__).parent))
import benchmark_plan


class BenchmarkPlanTest(unittest.TestCase):
    def test_weighted_assignment_is_stable_complete_and_disjoint(self):
        scenarios = "strings coroutines platform-c-interop exceptions closures atomics"
        first = benchmark_plan.assign_scenarios(scenarios)
        second = benchmark_plan.assign_scenarios(scenarios)
        self.assertEqual(first, second)
        self.assertEqual(
            set(scenarios.split()), set(first[0].scenarios) | set(first[1].scenarios)
        )
        self.assertFalse(set(first[0].scenarios) & set(first[1].scenarios))
        self.assertLessEqual(abs(first[0].estimated_weight - first[1].estimated_weight), 5)

    def test_each_model_receives_extra_lead_for_alternating_scenarios(self):
        schedule = benchmark_plan.execution_schedule("strings coroutines", 1, 9, 3)
        scenarios = schedule["scenarios"]
        self.assertEqual(1, schedule["effectiveWarmupsIncludingCorrectness"])
        self.assertEqual([], scenarios[0]["additionalWarmups"])
        first_leads = [row["measurements"][0]["order"][0] for row in scenarios]
        self.assertEqual([benchmark_plan.CANDIDATE, benchmark_plan.BASELINE], first_leads)
        lead_counts = {
            row["scenario"]: {
                model: sum(pair["order"][0] == model for pair in row["measurements"])
                for model in (benchmark_plan.BASELINE, benchmark_plan.CANDIDATE)
            }
            for row in scenarios
        }
        self.assertEqual({benchmark_plan.BASELINE: 4, benchmark_plan.CANDIDATE: 5}, lead_counts["strings"])
        self.assertEqual({benchmark_plan.BASELINE: 5, benchmark_plan.CANDIDATE: 4}, lead_counts["coroutines"])

    def test_correctness_supplies_minimum_warm_execution_for_quick_plan(self):
        schedule = benchmark_plan.execution_schedule("strings coroutines", 0, 3, 1)
        self.assertEqual(0, schedule["requestedWarmups"])
        self.assertEqual(1, schedule["effectiveWarmupsIncludingCorrectness"])
        self.assertEqual([], schedule["scenarios"][0]["additionalWarmups"])
        self.assertEqual(2, schedule["scenarios"][0]["measurements"][0]["pairOrdinal"])

    def test_cli_writes_machine_readable_schedule(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "schedule.json"
            status = benchmark_plan.main([
                "schedule", "--scenarios", "strings,coroutines", "--warmups", "1",
                "--repetitions", "3", "--compile-repetitions", "3", "--output", str(output),
            ])
            self.assertEqual(0, status)
            payload = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("scenario-balanced-deterministic-paired-ab", payload["policy"])
            self.assertEqual(2, len(payload["scenarios"]))

    def test_unknown_or_single_scenario_fails_closed_for_dual_host_assignment(self):
        with self.assertRaisesRegex(ValueError, "unknown"):
            benchmark_plan.assign_scenarios("strings not-a-benchmark")
        with self.assertRaisesRegex(ValueError, "at least two"):
            benchmark_plan.assign_scenarios("strings")


if __name__ == "__main__":
    unittest.main()
