# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `176bd56a31a46bf34740fc51f3e815a4c459123f`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| platform-c-leaf | 0.149688 | 0.149369 | -0.214% | 100.214% | -8.696% | PASS |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.48 | 10.15 | -3.149% |
| Compile peak RSS KiB | 559540 | 560388 | 0.152% |
| Binary bytes | 507200 | 492664 | -2.866% |
| Retain callsites | 12 | 438 | 3550.000% |
| Release callsites | 0 | 453 | 45300.000% |
| Emitted allocation callsites | 269 | 199 | -26.022% |

Throughput geomean: **100.214%**

**All hard gates passed.**
