#!/usr/bin/env python3
"""Safe local-to-Linux orchestration for Kotlin/Native ARC development."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
from pathlib import PurePosixPath
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import uuid


ROOT = Path(__file__).resolve().parents[2]
REMOTE_HELPER = Path(__file__).with_name("remote.sh")
DEFAULT_HOST = "olfa@10.10.10.8"
DEFAULT_REMOTE_DIR = "/home/olfa/codex-kotlin-arc"
DEFAULT_REMOTE_GIT = "/home/olfa/codex-kotlin-arc-git"
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
        "ARC_REMOTE_GIT": "/home/olfa/codex-kotlin-arc-git",
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
        "ARC_REMOTE_GIT": "/home/olfa/codex-kotlin-arc-git",
        "ARC_MAX_WORKERS": "14",
    },
    "primary-interop": {
        "ARC_REMOTE": "olfa@10.10.10.8",
        "ARC_REMOTE_DIR": "/home/olfa/codex-kotlin-arc-interop",
        "ARC_REMOTE_GIT": "/home/olfa/codex-kotlin-arc-git",
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
    "arc-bench-candidate", "arc-bench-baseline", "arc-bench", "arc-bench-quick",
)
BENCHMARK_ENVIRONMENT = (
    "ARC_BENCH_BUILD_WORKERS", "ARC_BENCH_REPETITIONS", "ARC_BENCH_WARMUPS",
    "ARC_BENCH_COMPILE_REPETITIONS", "ARC_BENCH_SCENARIOS", "ARC_BENCH_CPU",
    "ARC_BENCH_QUICK",
    "ARC_BENCH_SCENARIO_REGRESSION_PERCENT", "ARC_BENCH_THROUGHPUT_FLOOR_PERCENT",
    "ARC_BENCH_RSS_LIMIT_PERCENT", "ARC_BENCH_SIZE_LIMIT_PERCENT", "ARC_BENCH_ENFORCE",
    "ARC_BENCH_OBJDUMP",
    "ARC_BENCH_BASELINE_SOURCE", "ARC_BENCH_BASELINE_DIST",
    "ARC_BENCH_CANDIDATE_CACHE_ROOT", "ARC_BENCH_CANDIDATE_CACHE_MAX_ENTRIES",
    "ARC_BENCH_RESERVED_CODE_CACHE_SIZE", "JAVA_OPTS", "JAVA_TOOL_OPTIONS",
    "ARC_BENCH_SHARD_ID", "ARC_BENCH_SHARD_COUNT",
    "ARC_BENCH_MACHINE_PROFILE", "ARC_BENCH_EXPECTED_COMMIT", "ARC_BENCH_EXPECTED_TREE",
    "ARC_BENCH_EXPECTED_RUNTIME_TREE", "ARC_BENCH_EXPECTED_RUNTIME_PATCH_SHA256",
)
BENCHMARK_PRESETS = {
    "coroutines": "coroutines",
    "hotspots": "strings,coroutines,platform-c-dynamic-cstring",
    "interop": "platform-c-interop,platform-c-leaf,platform-c-dynamic-cstring",
    "strings": "strings",
}
REMOTE_REF_DELETE_ATTEMPTS = 3
REMOTE_REF_COMMAND_TIMEOUT_SECONDS = 20
SAFE_SNAPSHOT_REFERENCE = re.compile(r"^refs/codex/arc/snapshots/[0-9a-f]{32}$")
SAFE_SSH_HOST = re.compile(r"^(?:[A-Za-z0-9._-]+@)?[A-Za-z0-9][A-Za-z0-9.-]*$")
SAFE_REMOTE_GIT = re.compile(r"^/[A-Za-z0-9._/-]+$")


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
        normalized = value.replace("\\", "/")
        path = PurePosixPath(normalized)
        canonical = str(path)
        drive_prefixed = len(canonical) >= 2 and canonical[0].isalpha() and canonical[1] == ":"
        if canonical == "." or path.is_absolute() or drive_prefixed or ".." in path.parts:
            raise SystemExit(f"invalid snapshot path: {value}")
        if any(canonical == excluded or canonical.startswith(excluded + "/") for excluded in EXCLUDED_PATHS):
            raise SystemExit(f"snapshot path is excluded: {value}")
        if canonical not in result:
            result.append(canonical)
    if not result:
        raise SystemExit("path-scoped snapshot requires at least one path")
    return result


def snapshot_changes(
    environment: dict[str, str], scoped_paths: list[str] | None, *, root: Path = ROOT
) -> list[str]:
    """Enumerate snapshot inputs without naming ignored paths to ``git add``."""
    candidates = run(
        [
            "git", "ls-files", "--modified", "--deleted", "--others", "--exclude-standard",
            *(f"--exclude=/{path}/" for path in EXCLUDED_PATHS),
            "-z",
        ],
        env=environment,
        capture=True,
        cwd=root,
    ).stdout.split("\0")

    def is_within(path: str, roots: tuple[str, ...]) -> bool:
        return any(path == root or path.startswith(root + "/") for root in roots)

    included_roots = tuple(scoped_paths) if scoped_paths is not None else ()
    result: list[str] = []
    seen: set[str] = set()
    for candidate in candidates:
        if not candidate or candidate in seen:
            continue
        if is_within(candidate, EXCLUDED_PATHS):
            continue
        if scoped_paths is not None and not is_within(candidate, included_roots):
            continue
        seen.add(candidate)
        result.append(candidate)
    return result


def create_snapshot_ref(paths: list[str] | None = None, *, root: Path = ROOT) -> tuple[str, str]:
    """Create a commit of the worktree without reading or modifying the real index."""
    token = uuid.uuid4().hex
    reference = f"refs/codex/arc/snapshots/{token}"
    descriptor, index_name = tempfile.mkstemp(prefix="kotlin-arc-index-")
    os.close(descriptor)
    Path(index_name).unlink()
    environment = os.environ.copy()
    environment["GIT_INDEX_FILE"] = index_name
    try:
        run(["git", "read-tree", "HEAD"], env=environment, cwd=root)
        scoped_paths = snapshot_pathspecs(paths)
        changes = snapshot_changes(environment, scoped_paths, root=root)
        if changes:
            run(
                ["git", "add", "-A", "--pathspec-from-file=-", "--pathspec-file-nul"],
                env=environment,
                cwd=root,
                input_text="".join(f":(literal){path}\0" for path in changes),
            )
        tree = output(["git", "write-tree"], env=environment, cwd=root)
        commit = output(
            ["git", "commit-tree", tree, "-p", "HEAD", "-m", f"Codex ARC snapshot {token}"],
            env=environment,
            cwd=root,
        )
        run(["git", "update-ref", reference, commit], cwd=root)
        return reference, commit
    finally:
        Path(index_name).unlink(missing_ok=True)


def remote_url() -> str:
    host = setting("ARC_REMOTE", DEFAULT_HOST)
    directory = setting("ARC_REMOTE_GIT", DEFAULT_REMOTE_GIT)
    return f"ssh://{host}{directory}/.git"


def delete_remote_ref(
    url: str,
    reference: str,
    *,
    attempts: int = REMOTE_REF_DELETE_ATTEMPTS,
    fallback_host: str | None = None,
    fallback_remote_git: str | None = None,
    expected_host: str | None = None,
    expected_remote_git: str | None = None,
) -> bool:
    """Delete a remote ref and verify absence without raising over primary work."""
    if attempts < 1:
        raise ValueError("remote ref deletion requires at least one attempt")
    fallback_values = (
        fallback_host, fallback_remote_git, expected_host, expected_remote_git,
    )
    fallback_enabled = any(value is not None for value in fallback_values)
    if fallback_enabled:
        if any(value is None for value in fallback_values):
            raise ValueError("host-local cleanup requires host, repository, and expected values")
        assert fallback_host is not None
        assert fallback_remote_git is not None
        assert expected_host is not None
        assert expected_remote_git is not None
        remote_path = PurePosixPath(fallback_remote_git)
        if not SAFE_SNAPSHOT_REFERENCE.fullmatch(reference):
            raise ValueError(f"unsafe snapshot reference for host-local cleanup: {reference!r}")
        if not SAFE_SSH_HOST.fullmatch(fallback_host) or fallback_host.startswith("-"):
            raise ValueError(f"unsafe SSH host for host-local cleanup: {fallback_host!r}")
        if fallback_host != expected_host or fallback_remote_git != expected_remote_git:
            raise ValueError("host-local cleanup does not match the configured host/repository")
        if (
            not remote_path.is_absolute()
            or not SAFE_REMOTE_GIT.fullmatch(fallback_remote_git)
            or str(remote_path) != fallback_remote_git
            or ".." in remote_path.parts
            or not remote_path.name
            or remote_path.name == ".git"
            or any(part.startswith("-") for part in remote_path.parts if part != "/")
        ):
            raise ValueError(f"unsafe remote Git directory for host-local cleanup: {fallback_remote_git!r}")
        if url != f"ssh://{fallback_host}{fallback_remote_git}/.git":
            raise ValueError("host-local cleanup host/repository does not match the remote URL")
    last_detail = "not attempted"
    for attempt in range(1, attempts + 1):
        try:
            deletion = subprocess.run(
                ["git", "push", url, f":{reference}"],
                cwd=ROOT,
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=REMOTE_REF_COMMAND_TIMEOUT_SECONDS,
            )
            last_detail = f"delete status {deletion.returncode}: {deletion.stdout.strip()}"
        except (OSError, subprocess.SubprocessError) as error:
            last_detail = f"delete invocation failed: {error}"

        try:
            verification = subprocess.run(
                ["git", "ls-remote", "--exit-code", url, reference],
                cwd=ROOT,
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=REMOTE_REF_COMMAND_TIMEOUT_SECONDS,
            )
            if verification.returncode == 2:
                return True
            if verification.returncode == 0:
                last_detail += "; ref still exists"
            else:
                last_detail += (
                    f"; verification status {verification.returncode}: "
                    f"{verification.stdout.strip()}"
                )
        except (OSError, subprocess.SubprocessError) as error:
            last_detail += f"; verification invocation failed: {error}"

        print(
            f"Remote snapshot cleanup attempt {attempt}/{attempts} failed for {url} {reference}: "
            f"{last_detail}",
            file=sys.stderr,
        )
        if attempt < attempts:
            time.sleep(0.25 * attempt)
    print(
        f"Remote snapshot ref remains unresolved after {attempts} attempts: {url} {reference}",
        file=sys.stderr,
    )
    if fallback_enabled:
        assert fallback_host is not None
        assert fallback_remote_git is not None
        try:
            fallback = subprocess.run(
                [
                    "ssh", fallback_host, "git", f"--git-dir={fallback_remote_git}/.git",
                    "update-ref", "-d", reference,
                ],
                cwd=ROOT,
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=REMOTE_REF_COMMAND_TIMEOUT_SECONDS,
            )
            fallback_detail = f"host-local status {fallback.returncode}: {fallback.stdout.strip()}"
        except (OSError, subprocess.SubprocessError) as error:
            fallback_detail = f"host-local invocation failed: {error}"
        try:
            verification = subprocess.run(
                ["git", "ls-remote", "--exit-code", url, reference],
                cwd=ROOT,
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=REMOTE_REF_COMMAND_TIMEOUT_SECONDS,
            )
            if verification.returncode == 2:
                return True
            fallback_detail += (
                f"; verification status {verification.returncode}: {verification.stdout.strip()}"
            )
        except (OSError, subprocess.SubprocessError) as error:
            fallback_detail += f"; verification invocation failed: {error}"
        print(
            f"Host-local snapshot cleanup remains unresolved for {fallback_host} "
            f"{fallback_remote_git} {reference}: {fallback_detail}",
            file=sys.stderr,
        )
    return False


def remote_snapshot(paths: list[str] | None = None) -> None:
    reference, commit = create_snapshot_ref(paths)
    host = setting("ARC_REMOTE", DEFAULT_HOST)
    remote_git = setting("ARC_REMOTE_GIT", DEFAULT_REMOTE_GIT)
    url = remote_url()
    push_attempted = False
    primary_completed = False
    cleanup_resolved = True
    try:
        push_attempted = True
        run(["git", "push", url, f"{reference}:{reference}"])
        remote("checkout", reference, commit)
        print(f"Remote ARC snapshot: {commit}")
        primary_completed = True
    finally:
        try:
            if push_attempted:
                try:
                    cleanup_resolved = delete_remote_ref(
                        url,
                        reference,
                        fallback_host=host,
                        fallback_remote_git=remote_git,
                        expected_host=host,
                        expected_remote_git=remote_git,
                    )
                except Exception as error:
                    cleanup_resolved = False
                    print(
                        f"Remote snapshot cleanup failed before verification for {url} "
                        f"{reference}: {error}",
                        file=sys.stderr,
                    )
        finally:
            subprocess.run(["git", "update-ref", "-d", reference], cwd=ROOT, check=False)
        if primary_completed and not cleanup_resolved:
            raise RuntimeError(f"remote snapshot ref cleanup remains unresolved: {url} {reference}")


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
        values = [
            f"{name}={os.environ[name]}"
            for name in (
                "ARC_BENCH_BUILD_WORKERS", "ARC_BENCH_REBUILD_CANDIDATE", "ARC_BENCH_QUICK",
                "ARC_BENCH_CANDIDATE_CACHE_ROOT", "ARC_BENCH_CANDIDATE_CACHE_MAX_ENTRIES",
                "JAVA_OPTS", "JAVA_TOOL_OPTIONS",
            )
            if name in os.environ
        ]
        return ["env", *values, *command] if values else command
    if profile == "arc-bench-baseline":
        command = ["bash", "tools/arc/benchmark_baseline.sh"]
        values = [
            f"{name}={os.environ[name]}"
            for name in ("ARC_BENCH_BUILD_WORKERS", "ARC_BENCH_QUICK", "JAVA_OPTS", "JAVA_TOOL_OPTIONS")
            if name in os.environ
        ]
        if "ARC_BENCH_REBUILD_BASELINE" in os.environ:
            values.append(f"ARC_BENCH_REBUILD_BASELINE={os.environ['ARC_BENCH_REBUILD_BASELINE']}")
        return ["env", *values, *command] if values else command
    if profile == "arc-bench":
        command = ["bash", "tools/arc/benchmark_compare.sh"]
        values = [f"{name}={os.environ[name]}" for name in BENCHMARK_ENVIRONMENT if name in os.environ]
        return ["env", *values, *command] if values else command
    if profile == "arc-bench-quick":
        command = ["bash", "tools/arc/benchmark_quick.sh"]
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
        "shard.json",
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
    run_parser.add_argument("--shard-id", help="stable identifier for this disjoint benchmark shard")
    run_parser.add_argument("--shard-count", type=int, help="total number of shards required by the merge")
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
        if arguments.shard_id is not None or arguments.shard_count is not None:
            if not arguments.profile.startswith("arc-bench"):
                raise SystemExit("--shard-id/--shard-count are supported only for benchmark profiles")
            if arguments.shard_id is None or arguments.shard_count is None:
                raise SystemExit("--shard-id and --shard-count must be supplied together")
            if arguments.shard_count < 1:
                raise SystemExit("--shard-count must be positive")
            os.environ["ARC_BENCH_SHARD_ID"] = arguments.shard_id
            os.environ["ARC_BENCH_SHARD_COUNT"] = str(arguments.shard_count)
        remote_run(arguments.profile)


if __name__ == "__main__":
    main()
