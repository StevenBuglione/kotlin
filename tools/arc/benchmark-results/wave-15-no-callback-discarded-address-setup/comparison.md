# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `176bd56a31a46bf34740fc51f3e815a4c459123f`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| platform-c-dynamic-cstring | 0.298310 | 0.313452 | 5.076% | 95.169% | -22.222% | FAIL |
| platform-c-interop | 0.288033 | 0.001871 | -99.350% | 15395.254% | -20.000% | PASS |
| platform-c-leaf | 0.157847 | 0.406767 | 157.697% | 38.805% | -8.696% | FAIL |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.04 | 9.67 | -3.685% |
| Compile peak RSS KiB | 551084 | 632520 | 14.777% |
| Binary bytes | 507200 | 492664 | -2.866% |
| Retain callsites | 12 | 438 | 3550.000% |
| Release callsites | 0 | 453 | 45300.000% |
| Emitted allocation callsites | 269 | 199 | -26.022% |

Throughput geomean: **384.525%**

## Failed gates

- platform-c-dynamic-cstring: latency regression 5.076% > 5.000%
- platform-c-leaf: latency regression 157.697% > 5.000%
