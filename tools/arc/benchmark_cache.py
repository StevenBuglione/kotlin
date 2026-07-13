#!/usr/bin/env python3
"""Validate immutable benchmark distributions before reusing them."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import tempfile


SCHEMA = 1
EXCLUDED = {
    ".arc-benchmark-provenance.json",
    ".arc-benchmark-baseline-cache.json",
    ".arc-benchmark-candidate-cache.json",
}


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


def _validate_manifest(path: Path, dist: Path, fields: dict[str, object]) -> bool:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return False
    if any(payload.get(key) != value for key, value in fields.items()):
        return False
    fingerprint = payload.get("distributionSha256")
    return isinstance(fingerprint, str) and fingerprint == distribution_fingerprint(dist)


def write_manifest(path: Path, dist: Path, commit: str, tree: str, source: str) -> None:
    _write_manifest(path, dist, expected_fields(commit, tree, source))


def validate_manifest(path: Path, dist: Path, commit: str, tree: str, source: str) -> bool:
    return _validate_manifest(path, dist, expected_fields(commit, tree, source))


def write_candidate_manifest(path: Path, dist: Path, commit: str, tree: str, source: str) -> None:
    _write_manifest(path, dist, candidate_expected_fields(commit, tree, source))


def validate_candidate_manifest(path: Path, dist: Path, commit: str, tree: str, source: str) -> bool:
    return _validate_manifest(path, dist, candidate_expected_fields(commit, tree, source))


def main() -> int:
    actions = {"write", "validate", "write-candidate", "validate-candidate"}
    if len(sys.argv) != 7 or sys.argv[1] not in actions:
        raise SystemExit(
            "usage: benchmark_cache.py "
            "write|validate|write-candidate|validate-candidate MANIFEST DIST COMMIT TREE SOURCE"
        )
    action, manifest, dist, commit, tree, source = sys.argv[1:]
    manifest_path = Path(manifest)
    dist_path = Path(dist)
    if action == "write":
        write_manifest(manifest_path, dist_path, commit, tree, source)
        return 0
    if action == "validate":
        valid = validate_manifest(manifest_path, dist_path, commit, tree, source)
    elif action == "write-candidate":
        write_candidate_manifest(manifest_path, dist_path, commit, tree, source)
        return 0
    else:
        valid = validate_candidate_manifest(manifest_path, dist_path, commit, tree, source)
    return 0 if valid else 1


if __name__ == "__main__":
    raise SystemExit(main())
