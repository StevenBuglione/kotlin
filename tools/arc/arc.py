#!/usr/bin/env python3
"""Safe local-to-Linux orchestration for Kotlin/Native ARC development."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
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
    "ci2": {
        "ARC_REMOTE": "olfa@10.10.10.12",
        "ARC_REMOTE_DIR": "/home/olfa/codex-kotlin-arc-ci2",
        "ARC_REMOTE_GIT": "/home/olfa/codex-kotlin-rust",
        "ARC_MAX_WORKERS": "16",
    },
}
EXCLUDED_PATHS = (
    ".arc-runs",
    "wasm/wasm.debug.browsers",
    "tools/arc/__pycache__",
)
PROFILES = (
    "dist", "runtime", "sanity", "full", "arc-smoke", "arc-stress", "arc-race", "arc-race-tsan",
    "arc-unowned-death", "arc-no-collector",
    "arc-sanitize", "arc-sanitize-asan", "arc-sanitize-ubsan", "arc-sanitize-tsan", "arc-bench",
)


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


def create_snapshot_ref() -> tuple[str, str]:
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
        exclusions = [
            pattern
            for path in EXCLUDED_PATHS
            for pattern in (f":(exclude){path}", f":(exclude){path}/**")
        ]
        run(["git", "add", "-A", "--", ".", *exclusions], env=environment)
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


def remote_snapshot() -> None:
    reference, commit = create_snapshot_ref()
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
    if profile == "arc-sanitize":
        return ["bash", "tools/arc/sanitizer_probe.sh", "all"]
    if profile.startswith("arc-sanitize-"):
        sanitizer = profile.removeprefix("arc-sanitize-")
        return ["bash", "tools/arc/sanitizer_probe.sh", sanitizer]
    if profile == "arc-bench":
        return ["bash", "tools/arc/benchmark_compare.sh"]
    raise SystemExit(f"Unknown remote profile: {profile}")


def remote_run(profile: str) -> None:
    remote("start", profile, *profile_command(profile))
    remote("follow", profile)


def remote_status(profile: str) -> None:
    remote("status", profile)


def remote_log(profile: str) -> None:
    remote("log", profile)


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
    subparsers.add_parser("remote-snapshot")
    run_parser = subparsers.add_parser("run")
    run_parser.add_argument("profile", choices=PROFILES)
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
        remote_snapshot()
    elif arguments.command == "status":
        remote_status(arguments.profile)
    elif arguments.command == "log":
        remote_log(arguments.profile)
    else:
        remote_run(arguments.profile)


if __name__ == "__main__":
    main()
