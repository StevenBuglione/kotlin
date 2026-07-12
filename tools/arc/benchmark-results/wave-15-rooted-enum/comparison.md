# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `de6d755bfa72ea2b289a14cb2a0d858d9c9c7572`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| coroutines | 0.108867 | 0.138978 | 27.659% | 78.334% | -34.233% | FAIL |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 7.92 | 7.55 | -4.672% |
| Compile peak RSS KiB | 537052 | 606144 | 12.865% |
| Binary bytes | 507200 | 492664 | -2.866% |
| Retain callsites | 12 | 438 | 3550.000% |
| Release callsites | 0 | 453 | 45300.000% |
| Emitted allocation callsites | 269 | 199 | -26.022% |

Throughput geomean: **78.334%**

## Failed gates

- coroutines: latency regression 27.659% > 5.000%
- throughput geomean 78.334% < 100.000%
