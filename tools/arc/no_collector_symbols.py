#!/usr/bin/env python3
"""Prove that an ordinary ARC executable does not link a collector implementation."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys


@dataclass(frozen=True)
class ForbiddenSymbol:
    reason: str
    pattern: re.Pattern[str]


# These rules name Kotlin/Native collector implementation details, not generic GC
# compatibility APIs. ARC intentionally retains no-op entry points such as
# Kotlin_native_internal_GC_collect for source and ABI compatibility.
FORBIDDEN_SYMBOLS = (
    ForbiddenSymbol(
        "concurrent mark-and-sweep collector",
        re.compile(r"\bkotlin::gc::ConcurrentMarkAndSweep\b"),
    ),
    ForbiddenSymbol(
        "same-thread mark-and-sweep collector",
        re.compile(r"\bkotlin::gc::SameThreadMarkAndSweep\b"),
    ),
    ForbiddenSymbol(
        "automatic tracing-GC scheduler",
        re.compile(r"\bkotlin::gc::GCScheduler(?:Data|ThreadData|Config|Impl)?\b"),
    ),
    ForbiddenSymbol(
        "legacy concurrent cyclic collector",
        re.compile(r"(?:\(anonymous namespace\)::)?CyclicCollector(?:::|\b)"),
    ),
    ForbiddenSymbol(
        "legacy cyclic-collector scheduling",
        re.compile(r"\bcyclic(?:ScheduleGarbageCollect|LocalGC|CollectorCallback)\b"),
    ),
    ForbiddenSymbol(
        "legacy Bacon cycle traversal",
        re.compile(
            r"\(anonymous namespace\)::(?:collectCycles|markRoots|scanRoots|collectRoots|collectWhite|scanBlack)(?:<[^>]+>)?\("
        ),
    ),
)

ARC_RUNTIME_EVIDENCE = re.compile(
    r"\b(?:EnterFrameArc|LeaveFrameArc|SetCurrentFrameArc|MoveReferenceIntoReturnSlotArc)\b"
)


def forbidden_matches(symbols: str) -> list[tuple[str, str]]:
    matches: list[tuple[str, str]] = []
    for line in symbols.splitlines():
        for rule in FORBIDDEN_SYMBOLS:
            if rule.pattern.search(line):
                matches.append((rule.reason, line.strip()))
    return matches


def verify_symbols(symbols: str) -> list[tuple[str, str]]:
    if not ARC_RUNTIME_EVIDENCE.search(symbols):
        raise ValueError(
            "symbol table contains no ARC frame/runtime evidence; refusing an unproven or stripped binary"
        )
    return forbidden_matches(symbols)


def symbol_command(executable: Path) -> list[str]:
    override = os.environ.get("ARC_NM")
    if override:
        command = shlex.split(override)
    else:
        tool = shutil.which("llvm-nm") or shutil.which("nm")
        if tool is None:
            raise RuntimeError("llvm-nm or nm is required for the ARC no-collector linkage gate")
        command = [tool]
    return command + ["-a", "-C", "--defined-only", str(executable)]


def inspect_executable(executable: Path) -> str:
    if not executable.is_file():
        raise RuntimeError(f"ARC executable does not exist: {executable}")
    result = subprocess.run(
        symbol_command(executable),
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if result.returncode != 0:
        raise RuntimeError(f"symbol inspection failed with exit code {result.returncode}:\n{result.stdout}")
    return result.stdout


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("executable", type=Path)
    parser.add_argument("--output", type=Path, help="write the complete demangled symbol table here")
    args = parser.parse_args()

    try:
        symbols = inspect_executable(args.executable)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(symbols, encoding="utf-8")
        matches = verify_symbols(symbols)
    except (OSError, RuntimeError, ValueError) as error:
        print(f"ARC no-collector linkage gate failed: {error}", file=sys.stderr)
        return 1

    if matches:
        print("ARC no-collector linkage gate found forbidden collector symbols:", file=sys.stderr)
        for reason, symbol in matches:
            print(f"  {reason}: {symbol}", file=sys.stderr)
        return 1

    print("ARC_NO_COLLECTOR_SYMBOLS_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
