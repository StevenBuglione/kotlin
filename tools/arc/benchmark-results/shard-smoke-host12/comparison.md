# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `902fe461f50f96086e9821ae0f7c4d94b9767ea1`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| coroutines | 0.152621 | 0.181619 | 19.000% | 84.034% | -27.586% | FAIL |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.77 | 9.92 | -7.892% |
| Compile peak RSS KiB | 624316 | 554816 | -11.132% |
| Binary bytes | 507200 | 485696 | -4.240% |
| Retain callsites | 12 | 429 | 3475.000% |
| Release callsites | 0 | 442 | 44200.000% |
| Emitted allocation callsites | 269 | 194 | -27.881% |

Throughput geomean: **84.034%**

## Failed gates

- coroutines: latency regression 19.000% > 5.000%
- throughput geomean 84.034% < 100.000%
