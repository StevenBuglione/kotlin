# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `06f840730cfbdce0e6aedad00ad3a59f80e72458`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| allocation | 0.270603 | 0.177574 | -34.379% | 152.389% | -23.333% | PASS |
| arrays | 0.169659 | 0.171905 | 1.323% | 98.694% | -4.348% | PASS |
| atomics | 0.167917 | 0.169243 | 0.790% | 99.217% | -4.545% | PASS |
| bounded-cycles | 0.173520 | 0.058014 | -66.566% | 299.098% | -4.545% | PASS |
| call-arguments | 0.133061 | 0.118804 | -10.715% | 112.000% | -12.500% | PASS |
| closures | 0.306466 | 0.161977 | -47.147% | 189.203% | -4.545% | PASS |
| coroutines | 0.150075 | 0.224356 | 49.496% | 66.891% | -27.586% | FAIL |
| destruction | 0.229814 | 0.141985 | -38.217% | 161.858% | -5.741% | PASS |
| exceptions | 0.346972 | 0.306019 | -11.803% | 113.382% | -48.837% | PASS |
| fields | 0.153025 | 0.156751 | 2.435% | 97.623% | -12.500% | PASS |
| platform-c-interop | 0.277886 | 0.001921 | -99.309% | 14466.230% | -16.000% | PASS |
| strings | 0.152921 | 0.185175 | 21.092% | 82.582% | -27.586% | FAIL |
| virtual-dispatch | 0.144141 | 0.143527 | -0.426% | 100.428% | -4.545% | PASS |
| workers | 0.173663 | 0.174187 | 0.302% | 99.699% | -14.815% | PASS |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.1 | 9.46 | -6.337% |
| Compile peak RSS KiB | 573500 | 576968 | 0.605% |
| Binary bytes | 497216 | 475856 | -4.296% |
| Retain callsites | 12 | 453 | 3675.000% |
| Release callsites | 0 | 466 | 46600.000% |
| Emitted allocation callsites | 257 | 189 | -26.459% |

Throughput geomean: **167.389%**

## Failed gates

- coroutines: latency regression 49.496% > 5.000%
- strings: latency regression 21.092% > 5.000%
