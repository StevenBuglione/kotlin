# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `4d235dc344e509955ed614e66d5fe1fb342ae0c8`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| coroutines | 0.108129 | 0.136541 | 26.276% | 79.192% | -27.849% | FAIL |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 7.8 | 7.47 | -4.231% |
| Compile peak RSS KiB | 1005876 | 959304 | -4.630% |
| Binary bytes | 507200 | 485704 | -4.238% |
| Retain callsites | 12 | 429 | 3475.000% |
| Release callsites | 0 | 442 | 44200.000% |
| Emitted allocation callsites | 269 | 194 | -27.881% |

Throughput geomean: **79.192%**

## Failed gates

- coroutines: latency regression 26.276% > 5.000%
- throughput geomean 79.192% < 100.000%
