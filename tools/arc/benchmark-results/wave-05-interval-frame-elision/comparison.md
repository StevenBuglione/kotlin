# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `57606354d5e6cefc02c60ee43b0abab19c0acff3`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| call-arguments | 0.135774 | 0.121977 | -10.162% | 111.311% | -12.500% | PASS |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.06 | 9.5 | -5.567% |
| Compile peak RSS KiB | 571580 | 662956 | 15.987% |
| Binary bytes | 497216 | 487424 | -1.969% |
| Retain callsites | 12 | 496 | 4033.333% |
| Release callsites | 0 | 511 | 51100.000% |
| Emitted allocation callsites | 257 | 194 | -24.514% |

Throughput geomean: **111.311%**

**All hard gates passed.**
