# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `573ac885c9f92c3a8c6018bad2d5ec6f6b420717`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| strings | 0.156344 | 0.192318 | 23.010% | 81.294% | -34.375% | FAIL |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.26 | 9.7 | -5.458% |
| Compile peak RSS KiB | 548580 | 542476 | -1.113% |
| Binary bytes | 497216 | 475856 | -4.296% |
| Retain callsites | 12 | 443 | 3591.667% |
| Release callsites | 0 | 456 | 45600.000% |
| Emitted allocation callsites | 257 | 189 | -26.459% |

Throughput geomean: **81.294%**

## Failed gates

- strings: latency regression 23.010% > 5.000%
- throughput geomean 81.294% < 100.000%
