# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `4022aee98e659d711530dc554cce283da6a47c09`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| strings | 0.114486 | 0.134017 | 17.060% | 85.426% | -21.977% | FAIL |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 7.71 | 7.39 | -4.150% |
| Compile peak RSS KiB | 1005032 | 954068 | -5.071% |
| Binary bytes | 507200 | 485728 | -4.233% |
| Retain callsites | 12 | 431 | 3491.667% |
| Release callsites | 0 | 444 | 44400.000% |
| Emitted allocation callsites | 269 | 194 | -27.881% |

Throughput geomean: **85.426%**

## Failed gates

- strings: latency regression 17.060% > 5.000%
- throughput geomean 85.426% < 100.000%
