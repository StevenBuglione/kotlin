# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `525462a10d23aef663e7ff0823f32208ff46278f`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| allocation | 0.281425 | 0.178248 | -36.662% | 157.884% | -23.333% | PASS |
| arrays | 0.173128 | 0.174876 | 1.010% | 99.001% | -4.348% | PASS |
| atomics | 0.174994 | 0.176854 | 1.063% | 98.948% | -4.545% | PASS |
| bounded-cycles | 0.171598 | 0.057585 | -66.442% | 297.989% | -4.545% | PASS |
| call-arguments | 0.137342 | 0.124863 | -9.086% | 109.994% | -12.500% | PASS |
| closures | 0.345156 | 0.174041 | -49.576% | 198.319% | -4.545% | PASS |
| coroutines | 0.149707 | 0.181777 | 21.422% | 82.357% | -27.586% | FAIL |
| destruction | 0.237226 | 0.144244 | -39.195% | 164.461% | -5.746% | PASS |
| exceptions | 0.357424 | 0.309242 | -13.480% | 115.581% | -86.580% | PASS |
| fields | 0.150153 | 0.161520 | 7.570% | 92.963% | -12.500% | FAIL |
| platform-c-dynamic-cstring | 0.295348 | 0.129210 | -56.252% | 228.580% | -16.000% | PASS |
| platform-c-interop | 0.292961 | 0.001928 | -99.342% | 15198.036% | -16.000% | PASS |
| platform-c-leaf | 0.145802 | 0.147789 | 1.363% | 98.655% | -4.348% | PASS |
| strings | 0.156566 | 0.187521 | 19.771% | 83.493% | -27.586% | FAIL |
| virtual-dispatch | 0.147557 | 0.145632 | -1.305% | 101.322% | -4.545% | PASS |
| workers | 0.175994 | 0.177332 | 0.761% | 99.245% | -18.519% | PASS |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.17 | 9.82 | -3.441% |
| Compile peak RSS KiB | 562972 | 571028 | 1.431% |
| Binary bytes | 507200 | 485696 | -4.240% |
| Retain callsites | 12 | 429 | 3475.000% |
| Release callsites | 0 | 442 | 44200.000% |
| Emitted allocation callsites | 269 | 194 | -27.881% |

Throughput geomean: **168.468%**

## Failed gates

- coroutines: latency regression 21.422% > 5.000%
- fields: latency regression 7.570% > 5.000%
- strings: latency regression 19.771% > 5.000%
