#!/usr/bin/env python3
"""Validate immutable benchmark distributions before reusing them."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import shlex
import stat
import subprocess
import sys
import tempfile
import time
import uuid


SCHEMA = 1
CONTENT_SCHEMA = 2
ACCESS_SCHEMA = 1
LEASE_SCHEMA = 1
TOMBSTONE_SCHEMA = 1
DEFAULT_CANDIDATE_CACHE_MAX_ENTRIES = 8
DEFAULT_CANDIDATE_CACHE_LEASE_SECONDS = 24 * 60 * 60
MAX_CANDIDATE_CACHE_LEASE_SECONDS = 7 * 24 * 60 * 60
MAX_ACCESS_CLOCK_SKEW_NS = 5 * 60 * 1_000_000_000
CACHE_KEY_PATTERN = re.compile(r"^[0-9a-f]{64}$")
RETIRED_NAME_PATTERN = re.compile(r"^([0-9a-f]{64})-([0-9a-f]{32})$")
CACHE_OWNER = {"schema": 1, "owner": "kotlin-native-arc-benchmark-cache"}
MANAGED_CACHE_DIRECTORIES = (
    "candidate", "candidate-last-used", "candidate-leases", "candidate-trash",
)
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
        "javaOpts": os.environ.get("JAVA_OPTS", ""),
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
    """Identify candidate artifacts by their build inputs, not snapshot provenance.

    The remote runner creates a fresh synthetic commit for every snapshot. Two of
    those commits may have the same tree and therefore produce identical compiler
    artifacts. Keep accepting ``commit`` at this API boundary because callers also
    record it as run provenance, but deliberately exclude it from cache identity.
    """
    del commit
    payload = {
        "schema": CONTENT_SCHEMA,
        "role": "candidate",
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
        "tree": tree,
        "cacheKey": candidate_content_key(commit, tree, source),
        "compilerBuildInputs": compiler_build_inputs(source),
    }


def write_content_candidate_manifest(
    path: Path, dist: Path, commit: str, tree: str, source: Path
) -> None:
    fields = content_candidate_expected_fields(commit, tree, source)
    # These fields explain where an immutable artifact was first built. They are
    # intentionally metadata rather than validation inputs: a later synthetic
    # snapshot commit with the same tree must reuse this distribution.
    fields["builtFromCommit"] = commit
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


def _validate_cache_key(key: str) -> None:
    if not CACHE_KEY_PATTERN.fullmatch(key):
        raise ValueError(f"invalid candidate cache key: {key!r}")


def _access_path(cache_root: Path, key: str) -> Path:
    _validate_cache_key(key)
    return cache_root / "candidate-last-used" / f"{key}.json"


def _entry_has_owned_manifest(entry: Path) -> bool:
    if not CACHE_KEY_PATTERN.fullmatch(entry.name) or not entry.is_dir() or entry.is_symlink():
        return False
    try:
        payload = json.loads((entry / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return False
    return (
        payload.get("schema") == CONTENT_SCHEMA
        and payload.get("role") == "candidate-content-cache"
        and payload.get("cacheKey") == entry.name
        and (entry / "dist").is_dir()
        and not (entry / "dist").is_symlink()
    )


def _validate_managed_cache_layout(cache_root: Path) -> None:
    if cache_root.is_symlink() or (cache_root.exists() and not cache_root.is_dir()):
        raise ValueError(f"candidate cache root must be a real directory: {cache_root}")
    marker = cache_root / "owner.json"
    if marker.is_symlink():
        raise ValueError(f"candidate cache ownership marker must not be a symlink: {marker}")
    for name in MANAGED_CACHE_DIRECTORIES:
        path = cache_root / name
        if path.is_symlink() or (path.exists() and not path.is_dir()):
            raise ValueError(f"candidate cache managed path must be a real directory: {path}")


def _ensure_cache_root_owned(cache_root: Path) -> None:
    _validate_managed_cache_layout(cache_root)
    marker = cache_root / "owner.json"
    try:
        if json.loads(marker.read_text(encoding="utf-8")) == CACHE_OWNER:
            return
        raise ValueError(f"candidate cache ownership marker is invalid: {marker}")
    except FileNotFoundError:
        pass
    except (OSError, ValueError) as error:
        raise ValueError(f"candidate cache ownership marker is invalid: {marker}") from error

    # Safely adopt caches created by the preceding schema only when every existing
    # active entry carries our exact content-cache manifest. Refuse arbitrary roots.
    if cache_root.exists():
        allowed = {"candidate"}
        unknown = [path for path in cache_root.iterdir() if path.name not in allowed]
        candidate_root = cache_root / "candidate"
        invalid = [] if not candidate_root.exists() else [
            path for path in candidate_root.iterdir() if not _entry_has_owned_manifest(path)
        ]
        if unknown or invalid:
            raise ValueError(f"refusing to adopt foreign candidate cache root: {cache_root}")
    cache_root.mkdir(parents=True, exist_ok=True)
    _validate_managed_cache_layout(cache_root)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=cache_root, delete=False) as stream:
        temporary = Path(stream.name)
        json.dump(CACHE_OWNER, stream, indent=2, sort_keys=True)
        stream.write("\n")
    try:
        temporary.replace(marker)
    finally:
        temporary.unlink(missing_ok=True)


def mark_content_candidate_used(cache_root: Path, key: str, *, now_ns: int | None = None) -> None:
    """Atomically record LRU metadata outside the sealed distribution."""
    _ensure_cache_root_owned(cache_root)
    entry = cache_root / "candidate" / key
    _validate_cache_key(key)
    if not _entry_has_owned_manifest(entry):
        raise ValueError(f"current candidate cache entry does not exist: {entry}")
    used_ns = time.time_ns() if now_ns is None else now_ns
    if type(used_ns) is not int or used_ns < 0:
        raise ValueError("candidate cache last-used time must be a nonnegative integer")
    path = _access_path(cache_root, key)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema": ACCESS_SCHEMA,
        "cacheKey": key,
        "lastUsedNs": used_ns,
    }
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        json.dump(payload, stream, indent=2, sort_keys=True)
        stream.write("\n")
    temporary.replace(path)


def _candidate_last_used_ns(cache_root: Path, entry: Path, now_ns: int) -> int:
    path = _access_path(cache_root, entry.name)
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        if (
            payload.get("schema") == ACCESS_SCHEMA
            and payload.get("cacheKey") == entry.name
            and type(payload.get("lastUsedNs")) is int
            and 0 <= payload["lastUsedNs"] <= now_ns + MAX_ACCESS_CLOCK_SKEW_NS
        ):
            return payload["lastUsedNs"]
    except (OSError, ValueError):
        pass
    try:
        return (entry / "manifest.json").stat().st_mtime_ns
    except OSError:
        return entry.stat().st_mtime_ns


def _lease_path(cache_root: Path, lease_id: str) -> Path:
    _validate_cache_key(lease_id)
    return cache_root / "candidate-leases" / f"{lease_id}.json"


def lease_content_candidate(
    cache_root: Path,
    key: str,
    lease_id: str,
    *,
    now_ns: int | None = None,
    lease_seconds: int = DEFAULT_CANDIDATE_CACHE_LEASE_SECONDS,
) -> None:
    _ensure_cache_root_owned(cache_root)
    _validate_cache_key(key)
    _validate_cache_key(lease_id)
    if not 1 <= lease_seconds <= MAX_CANDIDATE_CACHE_LEASE_SECONDS:
        raise ValueError("candidate cache lease must be between 1 second and 7 days")
    entry = cache_root / "candidate" / key
    if not _entry_has_owned_manifest(entry):
        raise ValueError(f"candidate cache lease target is invalid: {entry}")
    used_ns = time.time_ns() if now_ns is None else now_ns
    if type(used_ns) is not int or used_ns < 0:
        raise ValueError("candidate cache last-used time must be a nonnegative integer")
    path = _lease_path(cache_root, lease_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema": LEASE_SCHEMA,
        "leaseId": lease_id,
        "cacheKey": key,
        "lastUsedNs": used_ns,
        "expiresNs": used_ns + lease_seconds * 1_000_000_000,
    }
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        json.dump(payload, stream, indent=2, sort_keys=True)
        stream.write("\n")
    temporary.replace(path)


def _leased_candidate_keys(cache_root: Path, now_ns: int) -> set[str]:
    root = cache_root / "candidate-leases"
    if not root.is_dir():
        return set()
    leased: set[str] = set()
    for path in root.glob("*.json"):
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            expires = payload.get("expiresNs")
            key = payload.get("cacheKey")
            valid = (
                payload.get("schema") == LEASE_SCHEMA
                and payload.get("leaseId") == path.stem
                and isinstance(key, str)
                and CACHE_KEY_PATTERN.fullmatch(key)
                and type(expires) is int
                and now_ns < expires <= now_ns + MAX_CANDIDATE_CACHE_LEASE_SECONDS * 1_000_000_000
                and _entry_has_owned_manifest(cache_root / "candidate" / key)
            )
        except (OSError, ValueError):
            valid = False
        if valid:
            leased.add(key)
        else:
            path.unlink(missing_ok=True)
    return leased


def _remove_sealed_tree(path: Path) -> None:
    for child in sorted(path.rglob("*"), key=lambda value: len(value.parts), reverse=True):
        _make_writable(child)
    _make_writable(path)
    shutil.rmtree(path)


def _write_retirement_tombstone(cache_root: Path, key: str, retired_name: str) -> Path:
    _validate_cache_key(key)
    match = RETIRED_NAME_PATTERN.fullmatch(retired_name)
    if match is None or match.group(1) != key:
        raise ValueError(f"invalid candidate cache retirement name: {retired_name!r}")
    trash = cache_root / "candidate-trash"
    trash.mkdir(parents=True, exist_ok=True)
    _validate_managed_cache_layout(cache_root)
    path = trash / f"{retired_name}.tombstone.json"
    payload = {
        "schema": TOMBSTONE_SCHEMA,
        "cacheKey": key,
        "retiredName": retired_name,
    }
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=trash, delete=False) as stream:
        temporary = Path(stream.name)
        json.dump(payload, stream, indent=2, sort_keys=True)
        stream.write("\n")
    temporary.replace(path)
    return path


def _recover_retired_candidates(cache_root: Path) -> list[str]:
    """Reclaim only trash paired with an exact tool-owned retirement tombstone."""
    _validate_managed_cache_layout(cache_root)
    trash = cache_root / "candidate-trash"
    if not trash.exists():
        return []
    recovered: list[str] = []
    for tombstone in sorted(trash.glob("*.tombstone.json")):
        if tombstone.is_symlink() or not tombstone.is_file():
            continue
        try:
            payload = json.loads(tombstone.read_text(encoding="utf-8"))
            key = payload.get("cacheKey")
            retired_name = payload.get("retiredName")
            match = RETIRED_NAME_PATTERN.fullmatch(retired_name) if isinstance(retired_name, str) else None
            valid = (
                type(payload.get("schema")) is int
                and payload.get("schema") == TOMBSTONE_SCHEMA
                and isinstance(key, str)
                and CACHE_KEY_PATTERN.fullmatch(key)
                and match is not None
                and match.group(1) == key
                and tombstone.name == f"{retired_name}.tombstone.json"
            )
        except (OSError, ValueError):
            valid = False
        if not valid:
            continue
        retired = trash / retired_name
        if retired.is_symlink() or (retired.exists() and not retired.is_dir()):
            continue
        active = cache_root / "candidate" / key
        if retired.is_dir():
            if active.exists() or active.is_symlink():
                # Atomic rename cannot produce both generations. Preserve inconsistent
                # state for diagnosis rather than guessing which tree is authoritative.
                continue
            _remove_sealed_tree(retired)
            _access_path(cache_root, key).unlink(missing_ok=True)
            recovered.append(key)
        elif not active.exists():
            # Deletion completed before interruption; finish sidecar cleanup.
            _access_path(cache_root, key).unlink(missing_ok=True)
        tombstone.unlink()
    return recovered


def maintain_content_candidates(
    cache_root: Path,
    current_key: str,
    max_entries: int = DEFAULT_CANDIDATE_CACHE_MAX_ENTRIES,
    *,
    now_ns: int | None = None,
    lease_id: str | None = None,
    lease_seconds: int = DEFAULT_CANDIDATE_CACHE_LEASE_SECONDS,
) -> list[str]:
    """Touch current LRU metadata and atomically retire the oldest cache entries.

    A maximum of zero disables pruning while still recording current use. The current
    entry is never eligible for deletion. Callers serialize maintenance with the
    physical-host benchmark lock; renaming each victim before recursive deletion keeps
    partially deleted entries out of the active cache namespace.
    """
    if max_entries < 0:
        raise ValueError("candidate cache maximum must be nonnegative")
    _ensure_cache_root_owned(cache_root)
    _validate_cache_key(current_key)
    candidate_root = cache_root / "candidate"
    current = candidate_root / current_key
    if not _entry_has_owned_manifest(current):
        raise ValueError(f"current candidate cache entry does not exist: {current}")
    used_ns = time.time_ns() if now_ns is None else now_ns
    mark_content_candidate_used(cache_root, current_key, now_ns=used_ns)
    if lease_id is not None:
        lease_content_candidate(
            cache_root, current_key, lease_id, now_ns=used_ns, lease_seconds=lease_seconds
        )
    _recover_retired_candidates(cache_root)
    if max_entries == 0:
        return []

    entries = [
        path for path in candidate_root.iterdir() if _entry_has_owned_manifest(path)
    ]
    overflow = max(0, len(entries) - max_entries)
    leased_keys = _leased_candidate_keys(cache_root, used_ns)
    victims = sorted(
        (
            entry for entry in entries
            if entry.name != current_key and entry.name not in leased_keys
        ),
        key=lambda entry: (_candidate_last_used_ns(cache_root, entry, used_ns), entry.name),
    )[:overflow]
    if not victims:
        return []

    trash = cache_root / "candidate-trash"
    removed: list[str] = []
    for entry in victims:
        # Recheck identity immediately before the atomic rename. Never accept a symlink
        # or a nested/non-content-addressed path as a deletion target.
        if entry.parent != candidate_root or entry.name == current_key or entry.is_symlink():
            raise RuntimeError(f"unsafe candidate cache deletion target: {entry}")
        retired_name = f"{entry.name}-{uuid.uuid4().hex}"
        retired = trash / retired_name
        tombstone = _write_retirement_tombstone(cache_root, entry.name, retired_name)
        try:
            entry.replace(retired)
        except FileNotFoundError:
            tombstone.unlink(missing_ok=True)
            continue
        _remove_sealed_tree(retired)
        _access_path(cache_root, entry.name).unlink(missing_ok=True)
        tombstone.unlink()
        removed.append(entry.name)
    return removed


def publish_content_candidate(
    cache_root: Path, source_dist: Path, commit: str, tree: str, source: Path
) -> Path:
    """Atomically publish a sealed distribution and return its immutable path."""
    _ensure_cache_root_owned(cache_root)
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
    if len(sys.argv) in {5, 6} and sys.argv[1] == "maintain-content-candidates":
        _, _, cache_root, current_key, maximum, *lease = sys.argv
        try:
            max_entries = int(maximum)
        except ValueError as error:
            raise SystemExit("candidate cache maximum must be an integer") from error
        lease_id = lease[0] if lease else None
        removed = maintain_content_candidates(
            Path(cache_root), current_key, max_entries, lease_id=lease_id
        )
        print(
            f"ARC_BENCH_CANDIDATE_CACHE_RETENTION max={max_entries} "
            f"current={current_key} lease={lease_id or 'none'} "
            f"removed={','.join(removed) if removed else 'none'}"
        )
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
            "ACTION MANIFEST DIST COMMIT TREE SOURCE | candidate-key COMMIT TREE SOURCE | "
            "maintain-content-candidates CACHE_ROOT CURRENT_KEY MAX_ENTRIES"
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
