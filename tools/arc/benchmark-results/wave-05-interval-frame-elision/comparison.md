# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `9c19461c23448fe406a96d33f91bd577c0f58edf`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| call-arguments | 0.141601 | 0.104941 | -25.890% | 134.934% | -12.500% | PASS |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.42 | 9.76 | -6.334% |
| Compile peak RSS KiB | 566516 | 557452 | -1.600% |
| Binary bytes | 497216 | 491520 | -1.146% |
| Retain callsites | 12 | 496 | 4033.333% |
| Release callsites | 0 | 511 | 51100.000% |
| Emitted allocation callsites | 257 | 194 | -24.514% |

Throughput geomean: **134.934%**

**All hard gates passed.**
