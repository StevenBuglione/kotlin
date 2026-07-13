# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `2dcf6721f09fe876ba719b0ce48529bff9daa616`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| allocation | 0.278884 | 0.166133 | -40.219% | 167.277% | -23.333% | PASS |
| arrays | 0.170965 | 0.157050 | -9.223% | 110.160% | -4.348% | PASS |
| atomics | 0.171771 | 0.169734 | 0.012% | 99.988% | -4.545% | PASS |
| bounded-cycles | 0.181097 | 0.057592 | -66.709% | 300.381% | -4.545% | PASS |
| call-arguments | 0.134872 | 0.121522 | -9.536% | 110.541% | -12.500% | PASS |
| closures | 0.317529 | 0.161580 | -49.375% | 197.532% | -4.545% | PASS |
| coroutines | 0.148405 | 0.113883 | -23.333% | 130.434% | -27.586% | PASS |
| destruction | 0.232789 | 0.136426 | -41.403% | 170.657% | -5.741% | PASS |
| exceptions | 0.347414 | 0.296816 | -14.849% | 117.439% | -77.729% | PASS |
| fields | 0.156482 | 0.161055 | 3.654% | 96.475% | -12.500% | PASS |
| platform-c-dynamic-cstring | 0.290824 | 0.006589 | -97.750% | 4444.761% | -16.000% | PASS |
| platform-c-interop | 0.287497 | 0.001954 | -99.321% | 14723.501% | -16.000% | PASS |
| platform-c-leaf | 0.146689 | 0.148350 | 0.966% | 99.044% | -8.696% | PASS |
| strings | 0.159540 | 0.112977 | -28.124% | 139.128% | -27.586% | PASS |
| virtual-dispatch | 0.148274 | 0.143408 | -2.525% | 102.590% | -4.545% | PASS |
| workers | 0.175614 | 0.175353 | -0.180% | 100.180% | -14.815% | PASS |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 9.96 | 10.49 | 5.321% |
| Compile peak RSS KiB | 631748 | 608776 | -3.636% |
| Binary bytes | 507200 | 521688 | 2.856% |
| Retain callsites | 12 | 472 | 3833.333% |
| Release callsites | 0 | 584 | 58400.000% |
| Emitted allocation callsites | 269 | 186 | -30.855% |

Throughput geomean: **219.122%**

**All hard gates passed.**
