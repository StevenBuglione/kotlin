#!/usr/bin/env python3
"""Validate immutable benchmark distributions before reusing them."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import shlex
import stat
import subprocess
import sys
import tempfile


SCHEMA = 1
CONTENT_SCHEMA = 2
EXCLUDED = {
    ".arc-benchmark-provenance.json",
    ".arc-benchmark-baseline-cache.json",
    ".arc-benchmark-candidate-cache.json",
    ".arc-benchmark-content-candidate-cache.json",
}


def _sha256_file(path: Path) -> str:
    if not path.is_file():
        return "missing"
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _tool_identity(command: list[str]) -> str:
    try:
        result = subprocess.run(
            command, check=False, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, timeout=10,
        )
    except (OSError, subprocess.TimeoutExpired):
        return "unavailable"
    output = result.stdout.replace("\r\n", "\n").strip()
    return hashlib.sha256(f"{result.returncode}\n{output}".encode("utf-8")).hexdigest()


def _configured_tool(environment_name: str, default: str) -> dict[str, str]:
    value = os.environ.get(environment_name, default)
    try:
        command = shlex.split(value)
    except ValueError:
        command = [value]
    if not command:
        command = [default]
    return {
        "command": value,
        "identity": _tool_identity([*command, "--version"]),
    }


def compiler_build_inputs(source: Path) -> dict[str, object]:
    """Return artifact-affecting host inputs not already covered by the Git tree."""
    java_home_value = os.environ.get("JAVA_HOME")
    java = Path(java_home_value) / "bin" / "java" if java_home_value else Path("java")
    return {
        "schema": CONTENT_SCHEMA,
        "hostSystem": platform.system(),
        "hostMachine": platform.machine(),
        "java": _tool_identity([str(java), "-version"]),
        "cmake": _tool_identity(["cmake", "--version"]),
        "ninja": _tool_identity(["ninja", "--version"]),
        "gradleWrapperSha256": _sha256_file(source / "gradle" / "wrapper" / "gradle-wrapper.jar"),
        "gradlePropertiesSha256": _sha256_file(source / "gradle.properties"),
        "localPropertiesSha256": _sha256_file(source / "local.properties"),
        "tasks": [":kotlin-native:dist", ":kotlin-native:distPlatformLibs"],
        "target": "linux_x64",
        "javaToolOptions": os.environ.get("JAVA_TOOL_OPTIONS", ""),
        "gradleOpts": os.environ.get("GRADLE_OPTS", ""),
        "nativeEnvironment": {
            name: os.environ.get(name, "")
            for name in (
                "CFLAGS", "CXXFLAGS", "LDFLAGS", "KONAN_DATA_DIR", "KONAN_HOME",
                "KONAN_USE_INTERNAL_SERVER",
            )
        },
        "nativeTools": {
            "cc": _configured_tool("CC", "cc"),
            "cxx": _configured_tool("CXX", "c++"),
            "ld": _configured_tool("LD", "ld"),
            "ar": _configured_tool("AR", "ar"),
            "clang": _configured_tool("CLANG", "clang"),
            "clangxx": _configured_tool("CLANGXX", "clang++"),
            "lld": _configured_tool("LLD", "lld"),
        },
    }


def candidate_content_key(commit: str, tree: str, source: Path) -> str:
    payload = {
        "schema": CONTENT_SCHEMA,
        "role": "candidate",
        "commit": commit,
        "tree": tree,
        "compilerBuildInputs": compiler_build_inputs(source),
    }
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def distribution_fingerprint(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*"), key=lambda value: value.relative_to(root).as_posix()):
        relative = path.relative_to(root).as_posix()
        if relative in EXCLUDED:
            continue
        metadata = path.lstat()
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(f"{stat.S_IMODE(metadata.st_mode):o}".encode("ascii"))
        digest.update(b"\0")
        if path.is_symlink():
            digest.update(b"link\0")
            digest.update(os.readlink(path).encode("utf-8"))
        elif path.is_file():
            digest.update(b"file\0")
            digest.update(str(metadata.st_size).encode("ascii"))
            digest.update(b"\0")
            with path.open("rb") as stream:
                while chunk := stream.read(1024 * 1024):
                    digest.update(chunk)
        elif path.is_dir():
            digest.update(b"dir")
        else:
            digest.update(b"other")
        digest.update(b"\0")
    return digest.hexdigest()


def expected_fields(commit: str, tree: str, source: str) -> dict[str, object]:
    return {
        "schema": SCHEMA,
        "role": "baseline-strict",
        "tag": "v1.9.10",
        "commit": commit,
        "tree": tree,
        "source": source,
    }


def candidate_expected_fields(commit: str, tree: str, source: str) -> dict[str, object]:
    return {
        "schema": SCHEMA,
        "role": "candidate",
        "commit": commit,
        "tree": tree,
        "source": source,
    }


def _write_manifest(path: Path, dist: Path, fields: dict[str, object]) -> None:
    payload = dict(fields)
    payload["distributionSha256"] = distribution_fingerprint(dist)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        json.dump(payload, stream, indent=2, sort_keys=True)
        stream.write("\n")
    temporary.replace(path)


def _validate_manifest(path: Path, dist: Path, fields: dict[str, object], *, fast: bool = False) -> bool:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return False
    if any(payload.get(key) != value for key, value in fields.items()):
        return False
    fingerprint = payload.get("distributionSha256")
    if not isinstance(fingerprint, str):
        return False
    if fast:
        # Preparation and quick profiles are non-evidence-producing. The cache publisher
        # seals entries read-only; checking exact identity and launchers avoids repeatedly
        # reading every compiler/platform-library byte during optimization iteration.
        return (
            (dist / "bin" / "konanc").is_file()
            and (dist / "bin" / "cinterop").is_file()
            and (
                fields.get("role") != "candidate-content-cache"
                or not (dist.stat().st_mode & stat.S_IWUSR)
            )
        )
    return fingerprint == distribution_fingerprint(dist)


def write_manifest(path: Path, dist: Path, commit: str, tree: str, source: str) -> None:
    _write_manifest(path, dist, expected_fields(commit, tree, source))


def validate_manifest(path: Path, dist: Path, commit: str, tree: str, source: str) -> bool:
    return _validate_manifest(path, dist, expected_fields(commit, tree, source))


def write_candidate_manifest(path: Path, dist: Path, commit: str, tree: str, source: str) -> None:
    _write_manifest(path, dist, candidate_expected_fields(commit, tree, source))


def validate_candidate_manifest(path: Path, dist: Path, commit: str, tree: str, source: str) -> bool:
    return _validate_manifest(path, dist, candidate_expected_fields(commit, tree, source))


def content_candidate_expected_fields(commit: str, tree: str, source: Path) -> dict[str, object]:
    return {
        "schema": CONTENT_SCHEMA,
        "role": "candidate-content-cache",
        "commit": commit,
        "tree": tree,
        "cacheKey": candidate_content_key(commit, tree, source),
        "compilerBuildInputs": compiler_build_inputs(source),
    }


def write_content_candidate_manifest(
    path: Path, dist: Path, commit: str, tree: str, source: Path
) -> None:
    fields = content_candidate_expected_fields(commit, tree, source)
    fields["builtFromSource"] = str(source)
    _write_manifest(path, dist, fields)


def validate_content_candidate_manifest(
    path: Path, dist: Path, commit: str, tree: str, source: Path, *, fast: bool = False
) -> bool:
    return _validate_manifest(
        path, dist, content_candidate_expected_fields(commit, tree, source), fast=fast
    )


def _make_writable(path: Path) -> None:
    if path.is_symlink():
        return
    mode = stat.S_IMODE(path.stat().st_mode)
    path.chmod(mode | stat.S_IWUSR)


def _seal_distribution(dist: Path) -> None:
    paths = sorted(dist.rglob("*"), key=lambda value: len(value.parts), reverse=True)
    for path in paths:
        if path.is_symlink():
            continue
        mode = stat.S_IMODE(path.stat().st_mode)
        path.chmod(mode & ~(stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH))
    mode = stat.S_IMODE(dist.stat().st_mode)
    dist.chmod(mode & ~(stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH))


def publish_content_candidate(
    cache_root: Path, source_dist: Path, commit: str, tree: str, source: Path
) -> Path:
    """Atomically publish a sealed distribution and return its immutable path."""
    key = candidate_content_key(commit, tree, source)
    entry = cache_root / "candidate" / key
    destination = entry / "dist"
    manifest = entry / "manifest.json"
    if validate_content_candidate_manifest(manifest, destination, commit, tree, source):
        return destination
    entry.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{key}-", dir=entry.parent))
    try:
        staged_dist = staging / "dist"
        shutil.copytree(source_dist, staged_dist, symlinks=True)
        for name in EXCLUDED:
            metadata = staged_dist / name
            if metadata.is_file() or metadata.is_symlink():
                metadata.unlink()
        _seal_distribution(staged_dist)
        write_content_candidate_manifest(
            staging / "manifest.json", staged_dist, commit, tree, source
        )
        try:
            staging.replace(entry)
        except FileExistsError:
            if not validate_content_candidate_manifest(manifest, destination, commit, tree, source):
                raise RuntimeError(f"candidate cache entry is corrupt: {entry}")
        if not validate_content_candidate_manifest(manifest, destination, commit, tree, source):
            raise RuntimeError(f"published candidate cache entry failed validation: {entry}")
        return destination
    finally:
        if staging.exists():
            for path in sorted(staging.rglob("*"), key=lambda value: len(value.parts), reverse=True):
                _make_writable(path)
            _make_writable(staging)
            shutil.rmtree(staging)


def main() -> int:
    if len(sys.argv) == 5 and sys.argv[1] == "candidate-key":
        _, _, commit, tree, source = sys.argv
        print(candidate_content_key(commit, tree, Path(source)))
        return 0
    if len(sys.argv) == 7 and sys.argv[1] == "publish-content-candidate":
        _, _, cache_root, dist, commit, tree, source = sys.argv
        print(publish_content_candidate(
            Path(cache_root), Path(dist), commit, tree, Path(source)
        ))
        return 0
    actions = {
        "write", "validate", "validate-fast", "write-candidate", "validate-candidate",
        "validate-candidate-fast", "write-content-candidate", "validate-content-candidate",
        "validate-content-candidate-fast",
    }
    if len(sys.argv) != 7 or sys.argv[1] not in actions:
        raise SystemExit(
            "usage: benchmark_cache.py "
            "ACTION MANIFEST DIST COMMIT TREE SOURCE | candidate-key COMMIT TREE SOURCE"
        )
    action, manifest, dist, commit, tree, source = sys.argv[1:]
    manifest_path = Path(manifest)
    dist_path = Path(dist)
    if action == "write":
        write_manifest(manifest_path, dist_path, commit, tree, source)
        return 0
    if action in {"validate", "validate-fast"}:
        valid = _validate_manifest(
            manifest_path, dist_path, expected_fields(commit, tree, source),
            fast=action.endswith("-fast"),
        )
    elif action == "write-candidate":
        write_candidate_manifest(manifest_path, dist_path, commit, tree, source)
        return 0
    elif action in {"validate-candidate", "validate-candidate-fast"}:
        valid = _validate_manifest(
            manifest_path, dist_path, candidate_expected_fields(commit, tree, source),
            fast=action.endswith("-fast"),
        )
    elif action == "write-content-candidate":
        write_content_candidate_manifest(manifest_path, dist_path, commit, tree, Path(source))
        return 0
    else:
        valid = validate_content_candidate_manifest(
            manifest_path, dist_path, commit, tree, Path(source),
            fast=action.endswith("-fast"),
        )
    return 0 if valid else 1


if __name__ == "__main__":
    raise SystemExit(main())
