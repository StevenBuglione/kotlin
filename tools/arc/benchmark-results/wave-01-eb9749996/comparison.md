# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `e5dd1305e080c016ce19699dbf540a0e4abb8b86`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| allocation | 0.094046 | 0.059420 | -36.818% | 158.274% | -23.333% | PASS |
| arrays | 0.029173 | 0.036167 | 23.972% | 80.663% | -8.696% | FAIL |
| atomics | 0.044010 | 0.044218 | 0.472% | 99.530% | -9.091% | PASS |
| bounded-cycles | 0.002181 | 0.002166 | -0.674% | 100.679% | -4.545% | PASS |
| closures | 0.002496 | 0.002111 | -15.408% | 118.215% | -9.091% | PASS |
| coroutines | 0.102007 | 0.177952 | 74.451% | 57.323% | -31.034% | FAIL |
| destruction | 0.011504 | 0.014239 | 23.770% | 80.795% | -5.209% | FAIL |
| exceptions | 0.342039 | 0.299224 | -12.518% | 114.309% | -78.523% | PASS |
| fields | 0.017126 | 0.017691 | 3.302% | 96.804% | -8.696% | PASS |
| platform-c-interop | 0.340087 | 0.388849 | 14.338% | 87.460% | -16.000% | FAIL |
| strings | 0.040383 | 0.079398 | 96.614% | 50.861% | -25.926% | FAIL |
| virtual-dispatch | 0.038592 | 0.062556 | 62.097% | 61.691% | -9.091% | FAIL |
| workers | 0.001848 | 0.001735 | -6.105% | 106.502% | -18.519% | PASS |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.06 | 8.79 | -12.624% |
| Compile peak RSS KiB | 653368 | 530448 | -18.813% |
| Binary bytes | 497144 | 421312 | -15.254% |
| Retain callsites | 12 | 581 | 4741.667% |
| Release callsites | 0 | 1046 | 104600.000% |
| Emitted allocation callsites | 252 | 183 | -27.381% |

Throughput geomean: **89.206%**

## Failed gates

- arrays: latency regression 23.972% > 5.000%
- coroutines: latency regression 74.451% > 5.000%
- destruction: latency regression 23.770% > 5.000%
- platform-c-interop: latency regression 14.338% > 5.000%
- strings: latency regression 96.614% > 5.000%
- virtual-dispatch: latency regression 62.097% > 5.000%
- throughput geomean 89.206% < 100.000%
