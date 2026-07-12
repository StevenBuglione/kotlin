#!/usr/bin/env python3
"""Safe local-to-Linux orchestration for Kotlin/Native ARC development."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
from pathlib import PurePosixPath
import shlex
import shutil
import subprocess
import sys
import tempfile
import uuid


ROOT = Path(__file__).resolve().parents[2]
REMOTE_HELPER = Path(__file__).with_name("remote.sh")
DEFAULT_HOST = "olfa@10.10.10.8"
DEFAULT_REMOTE_DIR = "/home/olfa/codex-kotlin-arc"
DEFAULT_REMOTE_GIT = "/home/olfa/codex-kotlin-rust"
DEFAULT_JAVA_HOME = "/usr/lib/jvm/java-17-openjdk-amd64"
EXPECTED_BRANCH = "codex/kotlin-native-arc-1.9.10"
DEFAULT_BASE_REF = "v1.9.10"
MAX_WORKERS = 28
MIN_AVAILABLE_GIB = 60
MACHINE_PROFILES = {
    "primary": {},
    "primary-bench": {
        "ARC_REMOTE": "olfa@10.10.10.8",
        "ARC_REMOTE_DIR": "/home/olfa/codex-kotlin-arc-primary-bench",
        "ARC_REMOTE_GIT": "/home/olfa/codex-kotlin-rust",
        "ARC_MAX_WORKERS": "16",
    },
    "ci2": {
        "ARC_REMOTE": "olfa@10.10.10.12",
        "ARC_REMOTE_DIR": "/home/olfa/codex-kotlin-arc-ci2",
        "ARC_REMOTE_GIT": "/home/olfa/codex-kotlin-rust",
        "ARC_MAX_WORKERS": "16",
    },
    "ci2-bench": {
        "ARC_REMOTE": "olfa@10.10.10.12",
        "ARC_REMOTE_DIR": "/home/olfa/codex-kotlin-arc-ci2-bench",
        "ARC_REMOTE_GIT": "/home/olfa/codex-kotlin-rust",
        "ARC_MAX_WORKERS": "16",
    },
    "ci2-cstring": {
        "ARC_REMOTE": "olfa@10.10.10.12",
        "ARC_REMOTE_DIR": "/home/olfa/codex-kotlin-arc-ci2-cstring",
        "ARC_REMOTE_GIT": "/home/olfa/codex-kotlin-rust",
        "ARC_MAX_WORKERS": "16",
        "ARC_BENCH_BASELINE_SOURCE": "/home/olfa/codex-kotlin-arc-ci2-bench-baseline-v1.9.10",
        "ARC_BENCH_BASELINE_DIST": "/home/olfa/codex-kotlin-arc-ci2-bench-baseline-v1.9.10/kotlin-native/dist",
    },
    "ci2-runtime": {
        "ARC_REMOTE": "olfa@10.10.10.12",
        "ARC_REMOTE_DIR": "/home/olfa/codex-kotlin-arc-ci2-runtime",
        "ARC_REMOTE_GIT": "/home/olfa/codex-kotlin-rust",
        "ARC_MAX_WORKERS": "8",
    },
    "primary-ssa": {
        "ARC_REMOTE": "olfa@10.10.10.8",
        "ARC_REMOTE_DIR": "/home/olfa/codex-kotlin-arc-ssa",
        "ARC_REMOTE_GIT": "/home/olfa/codex-kotlin-rust",
        "ARC_MAX_WORKERS": "14",
    },
    "primary-interop": {
        "ARC_REMOTE": "olfa@10.10.10.8",
        "ARC_REMOTE_DIR": "/home/olfa/codex-kotlin-arc-interop",
        "ARC_REMOTE_GIT": "/home/olfa/codex-kotlin-rust",
        "ARC_MAX_WORKERS": "12",
    },
}
EXCLUDED_PATHS = (
    ".arc-runs",
    "wasm/wasm.debug.browsers",
    "tools/arc/__pycache__",
)
PROFILES = (
    "dist", "runtime", "sanity", "full", "arc-smoke", "arc-stress", "arc-race", "arc-race-tsan",
    "arc-unowned-death", "arc-no-collector", "arc-frame-elision-unit", "arc-return-update-coalescing-unit",
    "arc-field-projection", "arc-rooted-loop", "arc-deinit-synthetic-root",
    "arc-sanitize", "arc-sanitize-asan", "arc-sanitize-ubsan", "arc-sanitize-tsan",
    "arc-bench-candidate", "arc-bench-baseline", "arc-bench",
)
BENCHMARK_ENVIRONMENT = (
    "ARC_BENCH_BUILD_WORKERS", "ARC_BENCH_REPETITIONS", "ARC_BENCH_WARMUPS",
    "ARC_BENCH_COMPILE_REPETITIONS", "ARC_BENCH_SCENARIOS", "ARC_BENCH_CPU",
    "ARC_BENCH_QUICK",
    "ARC_BENCH_SCENARIO_REGRESSION_PERCENT", "ARC_BENCH_THROUGHPUT_FLOOR_PERCENT",
    "ARC_BENCH_RSS_LIMIT_PERCENT", "ARC_BENCH_SIZE_LIMIT_PERCENT", "ARC_BENCH_ENFORCE",
    "ARC_BENCH_OBJDUMP",
    "ARC_BENCH_BASELINE_SOURCE", "ARC_BENCH_BASELINE_DIST",
)
BENCHMARK_PRESETS = {
    "coroutines": "coroutines",
    "hotspots": "strings,coroutines,platform-c-dynamic-cstring",
    "interop": "platform-c-interop,platform-c-leaf,platform-c-dynamic-cstring",
    "strings": "strings",
}


def run(
    command: list[str],
    *,
    cwd: Path = ROOT,
    env: dict[str, str] | None = None,
    capture: bool = False,
    input_text: str | None = None,
) -> subprocess.CompletedProcess[str]:
    print("+", shlex.join(command), flush=True)
    return subprocess.run(
        command,
        cwd=cwd,
        env=env,
        check=True,
        text=True,
        input=input_text,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )


def output(command: list[str], *, cwd: Path = ROOT, env: dict[str, str] | None = None) -> str:
    return run(command, cwd=cwd, env=env, capture=True).stdout.strip()


def setting(name: str, default: str) -> str:
    return os.environ.get(name, default)


def select_machine(name: str) -> None:
    """Apply named-machine defaults while preserving explicit environment overrides."""
    for key, value in MACHINE_PROFILES[name].items():
        os.environ.setdefault(key, value)


def workers() -> int:
    value = int(setting("ARC_MAX_WORKERS", str(MAX_WORKERS)))
    if not 1 <= value <= MAX_WORKERS:
        raise SystemExit(f"ARC_MAX_WORKERS must be between 1 and {MAX_WORKERS}, got {value}")
    return value


def selected_benchmark_scenarios(scenarios: str | None, benchmark_set: str | None) -> str | None:
    if scenarios and benchmark_set:
        raise SystemExit("--scenarios and --benchmark-set are mutually exclusive")
    return BENCHMARK_PRESETS[benchmark_set] if benchmark_set else scenarios


def remote(action: str, *arguments: str) -> None:
    helper = REMOTE_HELPER.read_text(encoding="utf-8").replace("\r\n", "\n")
    command = [
        "ssh",
        setting("ARC_REMOTE", DEFAULT_HOST),
        "bash",
        "-s",
        "--",
        action,
        setting("ARC_REMOTE_DIR", DEFAULT_REMOTE_DIR),
        setting("ARC_REMOTE_GIT", DEFAULT_REMOTE_GIT),
        setting("ARC_JAVA_HOME", DEFAULT_JAVA_HOME),
        str(workers()),
        setting("ARC_MIN_AVAILABLE_GIB", str(MIN_AVAILABLE_GIB)),
        *arguments,
    ]
    print("+", shlex.join(command), flush=True)
    result = subprocess.run(command, cwd=ROOT, check=False, input=helper.encode("utf-8"))
    if result.returncode != 0:
        raise SystemExit(result.returncode)


def doctor() -> None:
    missing = [tool for tool in ("git", "ssh") if shutil.which(tool) is None]
    if missing:
        raise SystemExit("Missing local tools: " + ", ".join(missing))
    active_root = Path(output(["git", "rev-parse", "--show-toplevel"])).resolve()
    if active_root != ROOT:
        raise SystemExit(f"{ROOT} is not the active Git worktree")
    branch = output(["git", "branch", "--show-current"])
    if branch != EXPECTED_BRANCH:
        raise SystemExit(f"ARC automation requires local branch {EXPECTED_BRANCH}, found {branch or 'detached HEAD'}")
    remote("doctor", setting("ARC_BASE_REF", DEFAULT_BASE_REF))
    print("ARC remote doctor passed")


def remote_init() -> None:
    remote("init", setting("ARC_BASE_REF", DEFAULT_BASE_REF))


def snapshot_pathspecs(paths: list[str] | None) -> list[str] | None:
    if paths is None:
        return None
    result: list[str] = []
    for value in paths:
        normalized = value.replace("\\", "/").removeprefix("./")
        path = PurePosixPath(normalized)
        if not normalized or path.is_absolute() or ".." in path.parts:
            raise SystemExit(f"invalid snapshot path: {value}")
        if any(normalized == excluded or normalized.startswith(excluded + "/") for excluded in EXCLUDED_PATHS):
            raise SystemExit(f"snapshot path is excluded: {value}")
        if normalized not in result:
            result.append(normalized)
    if not result:
        raise SystemExit("path-scoped snapshot requires at least one path")
    return result


def create_snapshot_ref(paths: list[str] | None = None) -> tuple[str, str]:
    """Create a commit of the worktree without reading or modifying the real index."""
    token = uuid.uuid4().hex
    reference = f"refs/codex/arc/snapshots/{token}"
    descriptor, index_name = tempfile.mkstemp(prefix="kotlin-arc-index-")
    os.close(descriptor)
    Path(index_name).unlink()
    environment = os.environ.copy()
    environment["GIT_INDEX_FILE"] = index_name
    try:
        run(["git", "read-tree", "HEAD"], env=environment)
        scoped_paths = snapshot_pathspecs(paths)
        if scoped_paths is None:
            exclusions = [
                pattern
                for path in EXCLUDED_PATHS
                for pattern in (f":(exclude){path}", f":(exclude){path}/**")
            ]
            run(["git", "add", "-A", "--", ".", *exclusions], env=environment)
        else:
            run(["git", "add", "-A", "--", *(f":(literal){path}" for path in scoped_paths)], env=environment)
        tree = output(["git", "write-tree"], env=environment)
        commit = output(
            ["git", "commit-tree", tree, "-p", "HEAD", "-m", f"Codex ARC snapshot {token}"],
            env=environment,
        )
        run(["git", "update-ref", reference, commit])
        return reference, commit
    finally:
        Path(index_name).unlink(missing_ok=True)


def remote_url() -> str:
    host = setting("ARC_REMOTE", DEFAULT_HOST)
    directory = setting("ARC_REMOTE_GIT", DEFAULT_REMOTE_GIT)
    return f"ssh://{host}{directory}/.git"


def remote_snapshot(paths: list[str] | None = None) -> None:
    reference, commit = create_snapshot_ref(paths)
    url = remote_url()
    pushed = False
    try:
        run(["git", "push", url, f"{reference}:{reference}"])
        pushed = True
        remote("checkout", reference, commit)
        print(f"Remote ARC snapshot: {commit}")
    finally:
        if pushed:
            subprocess.run(["git", "push", url, f":{reference}"], cwd=ROOT, check=False)
        subprocess.run(["git", "update-ref", "-d", reference], cwd=ROOT, check=False)


def tasks_from_environment(name: str, defaults: list[str]) -> list[str]:
    value = os.environ.get(name)
    return shlex.split(value) if value else defaults


def profile_command(profile: str) -> list[str]:
    gradle = ["./gradlew", "-Pkotlin.native.enabled=true", f"--max-workers={workers()}", "--no-daemon"]
    profiles = {
        "dist": ("ARC_DIST_TASKS", [":kotlin-native:dist", ":kotlin-native:distPlatformLibs"]),
        "runtime": ("ARC_RUNTIME_TASKS", [":kotlin-native:runtime:hostRuntimeTests"]),
        "sanity": ("ARC_SANITY_TASKS", [":kotlin-native:backend.native:tests:sanity"]),
        "full": ("ARC_FULL_TASKS", [":kotlin-native:backend.native:tests:run"]),
    }
    if profile in profiles:
        variable, defaults = profiles[profile]
        return gradle + tasks_from_environment(variable, defaults)
    if profile == "arc-smoke":
        override = os.environ.get("ARC_SMOKE_TASKS")
        return gradle + shlex.split(override) if override else ["bash", "tools/arc/run_fixture.sh", "smoke"]
    if profile == "arc-stress":
        override = os.environ.get("ARC_STRESS_TASKS")
        return gradle + shlex.split(override) if override else ["bash", "tools/arc/run_fixture.sh", "stress"]
    if profile == "arc-race":
        override = os.environ.get("ARC_RACE_TASKS")
        return gradle + shlex.split(override) if override else ["bash", "tools/arc/run_fixture.sh", "race"]
    if profile == "arc-race-tsan":
        override = os.environ.get("ARC_RACE_TSAN_TASKS")
        if override:
            return gradle + shlex.split(override)
        sanitizer = setting("ARC_RACE_SANITIZER", "thread")
        tsan_options = setting("ARC_TSAN_OPTIONS", "halt_on_error=1:history_size=7:second_deadlock_stack=1")
        return [
            "env",
            f"ARC_FIXTURE_SANITIZER={sanitizer}",
            f"TSAN_OPTIONS={tsan_options}",
            "bash",
            "tools/arc/run_fixture.sh",
            "race",
        ]
    if profile == "arc-unowned-death":
        return ["bash", "tools/arc/run_fixture.sh", "unowned-death"]
    if profile == "arc-no-collector":
        return ["bash", "tools/arc/run_fixture.sh", "no-collector"]
    if profile == "arc-frame-elision-unit":
        return ["bash", "tools/arc/run_frame_elision_unit.sh"]
    if profile == "arc-return-update-coalescing-unit":
        return ["bash", "tools/arc/run_return_update_coalescing_unit.sh"]
    if profile == "arc-field-projection":
        return gradle + [
            ":kotlin-native:backend.native:tests:test",
            ":kotlin-native:backend.native:tests:arc_borrowed_field_projection",
            ":kotlin-native:backend.native:tests:filecheck_arc_borrowed_field_projection",
        ]
    if profile == "arc-rooted-loop":
        return gradle + [
            ":kotlin-native:backend.native:tests:test",
            ":kotlin-native:backend.native:tests:arc_rooted_loop_borrowing",
            ":kotlin-native:backend.native:tests:filecheck_arc_rooted_loop_codegen",
            ":kotlin-native:backend.native:tests:filecheck_arc_rooted_loop_final",
        ]
    if profile == "arc-deinit-synthetic-root":
        return gradle + [
            ":kotlin-native:backend.native:tests:arc_deinit_synthetic_root",
        ]
    if profile == "arc-sanitize":
        return ["bash", "tools/arc/sanitizer_probe.sh", "all"]
    if profile.startswith("arc-sanitize-"):
        sanitizer = profile.removeprefix("arc-sanitize-")
        return ["bash", "tools/arc/sanitizer_probe.sh", sanitizer]
    if profile == "arc-bench-candidate":
        command = ["bash", "tools/arc/benchmark_candidate.sh"]
        values = (
            [f"ARC_BENCH_BUILD_WORKERS={os.environ['ARC_BENCH_BUILD_WORKERS']}"]
            if "ARC_BENCH_BUILD_WORKERS" in os.environ else []
        )
        return ["env", *values, *command] if values else command
    if profile == "arc-bench-baseline":
        command = ["bash", "tools/arc/benchmark_baseline.sh"]
        values = (
            [f"ARC_BENCH_BUILD_WORKERS={os.environ['ARC_BENCH_BUILD_WORKERS']}"]
            if "ARC_BENCH_BUILD_WORKERS" in os.environ else []
        )
        if "ARC_BENCH_REBUILD_BASELINE" in os.environ:
            values.append(f"ARC_BENCH_REBUILD_BASELINE={os.environ['ARC_BENCH_REBUILD_BASELINE']}")
        return ["env", *values, *command] if values else command
    if profile == "arc-bench":
        command = ["bash", "tools/arc/benchmark_compare.sh"]
        values = [f"{name}={os.environ[name]}" for name in BENCHMARK_ENVIRONMENT if name in os.environ]
        return ["env", *values, *command] if values else command
    raise SystemExit(f"Unknown remote profile: {profile}")


def remote_run(profile: str) -> None:
    remote("start", profile, *profile_command(profile))
    remote("follow", profile)


def remote_status(profile: str) -> None:
    remote("status", profile)


def remote_log(profile: str) -> None:
    remote("log", profile)


def benchmark_bundle(wave: str) -> None:
    if not wave or len(wave) > 64 or any(character not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_" for character in wave):
        raise SystemExit("benchmark wave must contain only letters, digits, '-' and '_'")
    destination = ROOT / "tools" / "arc" / "benchmark-results" / wave
    if destination.exists():
        raise SystemExit(f"benchmark result destination already exists: {destination}")
    remote_source = (
        f"{setting('ARC_REMOTE', DEFAULT_HOST)}:"
        f"{setting('ARC_REMOTE_DIR', DEFAULT_REMOTE_DIR)}/.arc-runs/arc-bench/artifacts/wave"
    )
    required = {
        "inputs.json", "hardware.json", "candidate-provenance.json", "baseline-provenance.json",
        "raw.tsv", "raw.json", "compile-raw.tsv", "static.tsv", "summary.tsv", "summary.json", "comparison.md",
    }
    with tempfile.TemporaryDirectory(prefix="arc-benchmark-bundle-") as temporary:
        temporary_path = Path(temporary)
        run(["scp", "-r", "--", remote_source, str(temporary_path)])
        downloaded = temporary_path / "wave"
        if not downloaded.is_dir():
            raise SystemExit("remote benchmark bundle did not contain a wave directory")
        entries = list(downloaded.iterdir())
        unsafe = [path.name for path in entries if path.is_symlink() or not path.is_file()]
        if unsafe:
            raise SystemExit(f"invalid benchmark bundle entries: {sorted(unsafe)}")
        names = {path.name for path in entries}
        missing = required - names
        unexpected = names - required
        if missing or unexpected:
            raise SystemExit(
                f"invalid benchmark bundle: missing={sorted(missing)} unexpected={sorted(unexpected)}"
            )
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=f".{wave}-", dir=destination.parent) as staging_root:
            staging = Path(staging_root) / "bundle"
            shutil.copytree(downloaded, staging)
            staging.replace(destination)
    print(f"Benchmark wave bundle: {destination}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--machine",
        choices=MACHINE_PROFILES,
        default=os.environ.get("ARC_MACHINE", "primary"),
        help="named remote machine (explicit ARC_* environment variables still take precedence)",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("doctor")
    subparsers.add_parser("remote-init")
    snapshot_parser = subparsers.add_parser("remote-snapshot")
    snapshot_parser.add_argument("--paths", nargs="+", help="snapshot only HEAD plus these lane-owned paths")
    bundle_parser = subparsers.add_parser("benchmark-bundle")
    bundle_parser.add_argument("wave")
    run_parser = subparsers.add_parser("run")
    run_parser.add_argument("profile", choices=PROFILES)
    run_parser.add_argument("--quick", action="store_true", help="use the non-enforcing development benchmark preset")
    run_parser.add_argument("--scenarios", help="comma- or space-separated benchmark scenarios")
    run_parser.add_argument(
        "--benchmark-set",
        choices=BENCHMARK_PRESETS,
        help="stable named benchmark subset",
    )
    status_parser = subparsers.add_parser("status")
    status_parser.add_argument("profile", choices=PROFILES)
    log_parser = subparsers.add_parser("log")
    log_parser.add_argument("profile", choices=PROFILES)
    arguments = parser.parse_args()
    select_machine(arguments.machine)
    if arguments.command == "doctor":
        doctor()
    elif arguments.command == "remote-init":
        remote_init()
    elif arguments.command == "remote-snapshot":
        remote_snapshot(arguments.paths)
    elif arguments.command == "benchmark-bundle":
        benchmark_bundle(arguments.wave)
    elif arguments.command == "status":
        remote_status(arguments.profile)
    elif arguments.command == "log":
        remote_log(arguments.profile)
    else:
        scenarios = selected_benchmark_scenarios(arguments.scenarios, arguments.benchmark_set)
        if arguments.quick:
            if not arguments.profile.startswith("arc-bench"):
                raise SystemExit("--quick is supported only for benchmark profiles")
            os.environ["ARC_BENCH_QUICK"] = "1"
            os.environ.setdefault("ARC_BENCH_ENFORCE", "0")
        if scenarios:
            if not arguments.profile.startswith("arc-bench"):
                raise SystemExit("--scenarios is supported only for benchmark profiles")
            os.environ["ARC_BENCH_SCENARIOS"] = scenarios
        remote_run(arguments.profile)


if __name__ == "__main__":
    main()
