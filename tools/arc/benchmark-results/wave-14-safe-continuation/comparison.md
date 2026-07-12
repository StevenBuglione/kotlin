# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `9ddd54dcbe8c7c3289f605232ffe168e8e0b660b`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| coroutines | 0.147597 | 0.201512 | 36.528% | 73.245% | -31.034% | FAIL |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.09 | 9.73 | -3.568% |
| Compile peak RSS KiB | 598656 | 576148 | -3.760% |
| Binary bytes | 507200 | 496760 | -2.058% |
| Retain callsites | 12 | 445 | 3608.333% |
| Release callsites | 0 | 460 | 46000.000% |
| Emitted allocation callsites | 269 | 199 | -26.022% |

Throughput geomean: **73.245%**

## Failed gates

- coroutines: latency regression 36.528% > 5.000%
- throughput geomean 73.245% < 100.000%

## Wave result and verification

This wave recognizes the exact Kotlin/Native 1.9.10 stdlib
`SafeContinuation.getOrThrow` ownership web. Its two atomic reads initialize one owning local slot;
identity checks, the failure type check, and the exception projection borrow from that owner; the
ordinary payload edge moves the final owner into the ABI return slot and clears the local. A
class-wide proof requires `resultRef` to have one constructor-only write, and all canonical class,
field, getter, compare-and-set, and function identities are revalidated before code generation.

Focused emitted code reduced the canonical function from 17 frame slots to 8, from 10
`UpdateStackRef` calls to zero, and from one `UpdateReturnRef` call to zero. Both atomic getter calls
share the same result slot; the successful edge contains one `MoveReferenceIntoReturnSlotArc`.
Behavior—including 2,000 resume/getOrThrow worker races—plus optimized, debug, diagnostics, and
strict variants passed on the final tree (554 Gradle tasks). Independent review found no remaining
P0/P1 ownership or ABI issue.

Relative to Wave 10's ARC coroutine median of 0.224356301 seconds, this tree's 0.201511693-second
median is 10.183% lower. The strict-normalized regression improves from 49.496% to 36.528%. This is
a material emitted-code and runtime improvement, but coroutines remain outside the 5% individual
gate and are not claimed complete.
