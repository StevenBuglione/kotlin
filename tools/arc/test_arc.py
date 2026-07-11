import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).parent))
import arc


class ArcProfileTest(unittest.TestCase):
    def test_smoke_uses_distribution_fixture(self):
        with patch.dict(os.environ, {}, clear=True):
            command = arc.profile_command("arc-smoke")
        self.assertEqual(["bash", "tools/arc/run_fixture.sh", "smoke"], command)

    def test_arc_fixture_tasks_remain_overrideable(self):
        with patch.dict(os.environ, {"ARC_STRESS_TASKS": ":custom:arcStress"}, clear=True):
            command = arc.profile_command("arc-stress")
        self.assertIn(":custom:arcStress", command)
        self.assertIn("--max-workers=28", command)

    def test_sanitizer_is_configurable(self):
        with patch.dict(os.environ, {"ARC_SANITIZER": "thread"}, clear=True):
            command = arc.profile_command("arc-sanitize")
        self.assertIn("-Psanitizer=thread", command)
        self.assertIn(":kotlin-native:runtime:hostRuntimeTests", command)

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
