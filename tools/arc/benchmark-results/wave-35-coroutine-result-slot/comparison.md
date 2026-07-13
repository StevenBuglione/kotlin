# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `3c0eba1d56a87e7e71d3d32b69b04f30f146e38a`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| coroutines | 0.149805 | 0.112024 | -24.193% | 131.915% | -27.586% | PASS |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.34 | 10.8 | 4.449% |
| Compile peak RSS KiB | 547260 | 600796 | 9.783% |
| Binary bytes | 507200 | 521688 | 2.856% |
| Retain callsites | 12 | 472 | 3833.333% |
| Release callsites | 0 | 584 | 58400.000% |
| Emitted allocation callsites | 269 | 186 | -30.855% |

Throughput geomean: **131.915%**

**All hard gates passed.**
