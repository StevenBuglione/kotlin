# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `354ffe9925f83b66677a8e3f593810ea51d721e6`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| bounded-cycles | 0.196206 | 0.493098 | 151.316% | 39.790% | -9.091% | FAIL |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.33 | 9.72 | -5.905% |
| Compile peak RSS KiB | 553376 | 571116 | 3.206% |
| Binary bytes | 497216 | 487424 | -1.969% |
| Retain callsites | 12 | 491 | 3991.667% |
| Release callsites | 0 | 506 | 50600.000% |
| Emitted allocation callsites | 257 | 194 | -24.514% |

Throughput geomean: **39.790%**

## Failed gates

- bounded-cycles: latency regression 151.316% > 5.000%
- throughput geomean 39.790% < 100.000%

## Wave 06 delta

- ARC bounded-cycle median fell from **2.378124080 s** in wave 06 to **0.493098295 s** in wave 07: a **4.822x speedup** and **79.265% latency reduction**.
- The emitted hot-loop ownership calls fell from four `UpdateStackRef` calls per traversal to exactly one; there are zero other ownership calls in the loop body.
- The previously recorded like-for-like c62 projection replaced wave 06's bounded result and raised the projected full-matrix throughput geomean from **83.503% to 94.527%**. It remains below the required **100%** and therefore still **FAILS** the full-geomean release gate.
- Using the final wave-07 resampled strict/ARC ratio instead gives a 94.897% projection; this does not change the FAIL verdict.

## Explicit verdict

**FAIL**: bounded-cycle latency remains 151.316% above strict, exceeding the allowed 5%, and both the measured focused geomean and projected full geomean remain below 100%.
