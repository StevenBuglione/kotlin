# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `bb5cbdbe4de2ac989440a6c153b4d20301c19ed9`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| allocation | 0.280225 | 0.173260 | -38.171% | 161.737% | -23.333% | PASS |
| arrays | 0.174556 | 0.173287 | -0.727% | 100.732% | -8.696% | PASS |
| atomics | 0.174959 | 0.172720 | -1.280% | 101.297% | -4.545% | PASS |
| bounded-cycles | 0.157886 | 2.378124 | 1406.228% | 6.639% | -4.545% | FAIL |
| call-arguments | 0.135757 | 0.123119 | -9.309% | 110.265% | -16.667% | PASS |
| closures | 0.315384 | 0.166320 | -47.264% | 189.624% | -4.545% | PASS |
| coroutines | 0.152899 | 0.269284 | 76.119% | 56.780% | -31.034% | FAIL |
| destruction | 0.236316 | 0.180977 | -23.417% | 130.578% | -5.912% | PASS |
| exceptions | 0.356612 | 0.303874 | -14.788% | 117.355% | -50.000% | PASS |
| fields | 0.156532 | 0.163496 | 4.449% | 95.740% | -12.500% | PASS |
| platform-c-interop | 0.288280 | 0.341814 | 18.570% | 84.338% | -16.000% | FAIL |
| strings | 0.155573 | 0.314492 | 102.150% | 49.468% | -34.375% | FAIL |
| virtual-dispatch | 0.147532 | 0.146703 | -0.562% | 100.565% | -4.545% | PASS |
| workers | 0.176646 | 0.176506 | -0.080% | 100.080% | -18.519% | PASS |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.3 | 9.67 | -6.117% |
| Compile peak RSS KiB | 637356 | 559052 | -12.286% |
| Binary bytes | 497216 | 487424 | -1.969% |
| Retain callsites | 12 | 496 | 4033.333% |
| Release callsites | 0 | 511 | 51100.000% |
| Emitted allocation callsites | 257 | 194 | -24.514% |

Throughput geomean: **83.503%**

## Failed gates

- bounded-cycles: latency regression 1406.228% > 5.000%
- coroutines: latency regression 76.119% > 5.000%
- platform-c-interop: latency regression 18.570% > 5.000%
- strings: latency regression 102.150% > 5.000%
- throughput geomean 83.503% < 100.000%
