#!/usr/bin/env python3
"""Build deterministic, balanced execution plans for ARC benchmarks."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re
from typing import Sequence


BASELINE = "baseline-strict"
CANDIDATE = "candidate-arc"
HOST_IDS = ("primary", "secondary")
SCENARIO_WEIGHTS = {
    "allocation": 2,
    "destruction": 2,
    "fields": 2,
    "arrays": 3,
    "strings": 5,
    "virtual-dispatch": 3,
    "call-arguments": 2,
    "closures": 4,
    "exceptions": 5,
    "coroutines": 7,
    "workers": 7,
    "atomics": 6,
    "platform-c-interop": 5,
    "platform-c-leaf": 4,
    "platform-c-dynamic-cstring": 6,
    "bounded-cycles": 5,
}
SAFE_SCENARIO = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


@dataclass(frozen=True)
class Assignment:
    host: str
    scenarios: tuple[str, ...]
    estimated_weight: int


def parse_scenarios(value: str | Sequence[str]) -> tuple[str, ...]:
    if isinstance(value, str):
        scenarios = tuple(part for part in re.split(r"[\s,]+", value.strip()) if part)
    else:
        scenarios = tuple(value)
    if not scenarios:
        raise ValueError("at least one benchmark scenario is required")
    if len(scenarios) != len(set(scenarios)):
        raise ValueError("benchmark scenarios must be unique")
    invalid = [scenario for scenario in scenarios if not SAFE_SCENARIO.fullmatch(scenario)]
    if invalid:
        raise ValueError(f"invalid benchmark scenarios: {invalid}")
    unknown = [scenario for scenario in scenarios if scenario not in SCENARIO_WEIGHTS]
    if unknown:
        raise ValueError(f"unknown benchmark scenarios: {unknown}")
    return scenarios


def assign_scenarios(value: str | Sequence[str]) -> tuple[Assignment, Assignment]:
    """Find a stable minimax weighted partition across two physical hosts."""
    scenarios = parse_scenarios(value)
    if len(scenarios) < len(HOST_IDS):
        raise ValueError("dual-host benchmarking requires at least two scenarios")
    total = sum(SCENARIO_WEIGHTS[scenario] for scenario in scenarios)
    best: tuple[tuple[int, int, int, int], int, int] | None = None
    # The first requested scenario is fixed on primary to remove mirror-equivalent
    # partitions. Sixteen curated scenarios make exhaustive minimax assignment cheap.
    for mask in range(1, 1 << len(scenarios), 2):
        if mask == (1 << len(scenarios)) - 1:
            continue
        primary_weight = sum(
            SCENARIO_WEIGHTS[scenario]
            for index, scenario in enumerate(scenarios)
            if mask & (1 << index)
        )
        secondary_weight = total - primary_weight
        primary_count = mask.bit_count()
        objective = (
            max(primary_weight, secondary_weight),
            abs(primary_weight - secondary_weight),
            abs(primary_count - (len(scenarios) - primary_count)),
            mask,
        )
        if best is None or objective < best[0]:
            best = objective, primary_weight, secondary_weight
    assert best is not None
    mask, loads = best[0][3], best[1:]
    selected = [
        [scenario for index, scenario in enumerate(scenarios) if bool(mask & (1 << index)) == primary]
        for primary in (True, False)
    ]
    return tuple(
        Assignment(
            host=host,
            scenarios=tuple(selected[index]),
            estimated_weight=loads[index],
        )
        for index, host in enumerate(HOST_IDS)
    )  # type: ignore[return-value]


def paired_models(scenario_index: int, pair_ordinal: int) -> tuple[str, str]:
    if scenario_index < 0 or pair_ordinal < 1:
        raise ValueError("scenario index must be nonnegative and pair ordinal must be positive")
    # Odd scenario/ordinal parity leads with the baseline. Alternating the scenario
    # index prevents one model from receiving the unavoidable extra lead in every
    # odd-sized sample set.
    return (BASELINE, CANDIDATE) if (scenario_index + pair_ordinal) % 2 else (CANDIDATE, BASELINE)


def execution_schedule(
    value: str | Sequence[str], warmups: int, repetitions: int, compile_repetitions: int
) -> dict[str, object]:
    scenarios = parse_scenarios(value)
    if warmups < 0 or repetitions < 1 or compile_repetitions < 1:
        raise ValueError("schedule counts must be positive (warmups may be zero)")
    compile_pairs = [
        {"repetition": repetition, "order": list(paired_models(0, repetition))}
        for repetition in range(1, compile_repetitions + 1)
    ]
    effective_warmups = max(1, warmups)
    scenario_plans = []
    for scenario_index, scenario in enumerate(scenarios):
        correctness = list(paired_models(scenario_index, 1))
        additional_warmup_pairs = [
            {"repetition": repetition, "order": list(paired_models(scenario_index, repetition))}
            for repetition in range(2, effective_warmups + 1)
        ]
        measured_pairs = [
            {
                "repetition": repetition,
                "pairOrdinal": effective_warmups + repetition,
                "order": list(paired_models(scenario_index, effective_warmups + repetition)),
            }
            for repetition in range(1, repetitions + 1)
        ]
        scenario_plans.append({
            "scenario": scenario,
            "scenarioIndex": scenario_index,
            "correctnessOrder": correctness,
            "additionalWarmups": additional_warmup_pairs,
            "measurements": measured_pairs,
        })
    return {
        "schemaVersion": 1,
        "policy": "scenario-balanced-deterministic-paired-ab",
        "requestedWarmups": warmups,
        "effectiveWarmupsIncludingCorrectness": effective_warmups,
        "compilePairs": compile_pairs,
        "scenarios": scenario_plans,
    }


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    assign = subparsers.add_parser("assign")
    assign.add_argument("--scenarios", required=True)
    schedule = subparsers.add_parser("schedule")
    schedule.add_argument("--scenarios", required=True)
    schedule.add_argument("--warmups", type=int, required=True)
    schedule.add_argument("--repetitions", type=int, required=True)
    schedule.add_argument("--compile-repetitions", type=int, required=True)
    schedule.add_argument("--output", type=Path)
    options = parser.parse_args(arguments)
    try:
        if options.command == "assign":
            payload: object = {
                "schemaVersion": 1,
                "policy": "stable-minimax-weighted-bipartition",
                "assignments": [assignment.__dict__ for assignment in assign_scenarios(options.scenarios)],
            }
        else:
            payload = execution_schedule(
                options.scenarios, options.warmups, options.repetitions, options.compile_repetitions
            )
    except ValueError as error:
        raise SystemExit(str(error)) from error
    rendered = json.dumps(payload, indent=2, sort_keys=True) + "\n"
    if getattr(options, "output", None):
        options.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
