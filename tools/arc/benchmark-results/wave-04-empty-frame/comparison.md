# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `41cab26cc50078345c8b13deace483c2e73c6fe5`

> This result is comparable only within this exact wave-04 baseline/candidate pair. The
> `call-arguments` fixture replaced the artificial recursive cold edge used by wave-02/wave-03
> with a deterministic nonrecursive edge, so historical timing deltas are not apples-to-apples.

The candidate still retains the per-iteration four-slot ARC frame. LTO stack-colors one alloca
for several mutually exclusive inlined frames, while this conservative compiler slice requires
exclusive alloca ownership before deleting frame storage. This wave therefore identifies the next
optimizer blocker; it does not claim a call-arguments performance win.

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| call-arguments | 0.136493 | 0.214703 | 57.300% | 63.573% | -16.667% | FAIL |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 9.9 | 9.25 | -6.566% |
| Compile peak RSS KiB | 571560 | 663560 | 16.096% |
| Binary bytes | 497216 | 487424 | -1.969% |
| Retain callsites | 12 | 496 | 4033.333% |
| Release callsites | 0 | 511 | 51100.000% |
| Emitted allocation callsites | 257 | 194 | -24.514% |

Throughput geomean: **63.573%**

## Failed gates

- call-arguments: latency regression 57.300% > 5.000%
- throughput geomean 63.573% < 100.000%
