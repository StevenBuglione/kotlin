# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `f3f458c73dac672cfe4da9e78755546331a7a7c4`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| platform-c-dynamic-cstring | 0.302482 | 0.132683 | -56.135% | 227.973% | -16.000% | PASS |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 11.17 | 11.07 | -0.895% |
| Compile peak RSS KiB | 564316 | 564564 | 0.044% |
| Binary bytes | 507200 | 485696 | -4.240% |
| Retain callsites | 12 | 431 | 3491.667% |
| Release callsites | 0 | 444 | 44400.000% |
| Emitted allocation callsites | 269 | 194 | -27.881% |

Throughput geomean: **227.973%**

**All hard gates passed.**
