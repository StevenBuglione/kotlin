# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `4e19dc483b213abac694518bd48e9acfb4f457a1`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| strings | 0.113084 | 0.133071 | 17.674% | 84.980% | -22.864% | FAIL |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 7.78 | 7.44 | -4.370% |
| Compile peak RSS KiB | 975656 | 937404 | -3.921% |
| Binary bytes | 507200 | 485704 | -4.238% |
| Retain callsites | 12 | 429 | 3475.000% |
| Release callsites | 0 | 442 | 44200.000% |
| Emitted allocation callsites | 269 | 194 | -27.881% |

Throughput geomean: **84.980%**

## Failed gates

- strings: latency regression 17.674% > 5.000%
- throughput geomean 84.980% < 100.000%
