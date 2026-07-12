# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `981daa4431b1828876ff0b4fb666b6551ffdbbff`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| allocation | 0.284241 | 0.176862 | -37.778% | 160.714% | -23.333% | PASS |
| arrays | 0.176886 | 0.163752 | -7.425% | 108.021% | -4.348% | PASS |
| atomics | 0.174159 | 0.174881 | 0.415% | 99.587% | -4.545% | PASS |
| bounded-cycles | 0.179930 | 2.345965 | 1203.821% | 7.670% | -4.545% | FAIL |
| call-arguments | 0.140777 | 0.266818 | 89.532% | 52.761% | -16.667% | FAIL |
| closures | 0.320392 | 0.168751 | -47.330% | 189.861% | -9.091% | PASS |
| coroutines | 0.154198 | 0.270369 | 75.338% | 57.033% | -27.586% | FAIL |
| destruction | 0.241607 | 0.181903 | -24.711% | 132.822% | -5.722% | PASS |
| exceptions | 0.364033 | 0.311959 | -14.305% | 116.693% | -51.111% | PASS |
| fields | 0.158244 | 0.163314 | 3.203% | 96.896% | -12.500% | PASS |
| platform-c-interop | 0.293362 | 0.359269 | 22.466% | 81.655% | -16.000% | FAIL |
| strings | 0.158489 | 0.326280 | 105.869% | 48.575% | -34.375% | FAIL |
| virtual-dispatch | 0.147490 | 0.148386 | 0.608% | 99.396% | -4.545% | PASS |
| workers | 0.178212 | 0.178013 | -0.112% | 100.112% | -21.429% | PASS |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.36 | 9.16 | -11.583% |
| Compile peak RSS KiB | 560772 | 568776 | 1.427% |
| Binary bytes | 497216 | 421104 | -15.308% |
| Retain callsites | 12 | 496 | 4033.333% |
| Release callsites | 0 | 965 | 96500.000% |
| Emitted allocation callsites | 257 | 191 | -25.681% |

Throughput geomean: **80.119%**

## Failed gates

- bounded-cycles: latency regression 1203.821% > 5.000%
- call-arguments: latency regression 89.532% > 5.000%
- coroutines: latency regression 75.338% > 5.000%
- platform-c-interop: latency regression 22.466% > 5.000%
- strings: latency regression 105.869% > 5.000%
- throughput geomean 80.119% < 100.000%
