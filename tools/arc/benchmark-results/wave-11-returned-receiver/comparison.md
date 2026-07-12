# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `639c5c422984213605b0c22e61c02733674bbb46`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| strings | 0.152319 | 0.184048 | 20.830% | 82.761% | -27.586% | FAIL |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 9.9 | 9.51 | -3.939% |
| Compile peak RSS KiB | 568688 | 613508 | 7.881% |
| Binary bytes | 497216 | 475856 | -4.296% |
| Retain callsites | 12 | 453 | 3675.000% |
| Release callsites | 0 | 466 | 46600.000% |
| Emitted allocation callsites | 257 | 189 | -26.459% |

Throughput geomean: **82.761%**

## Failed gates

- strings: latency regression 20.830% > 5.000%
- throughput geomean 82.761% < 100.000%
