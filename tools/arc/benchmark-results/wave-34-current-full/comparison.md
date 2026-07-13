# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `ded32721f42e2ade83039dfa29b62909e7cdbfb2`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| allocation | 0.199338 | 0.137905 | -30.683% | 144.265% | -27.364% | PASS |
| arrays | 0.151227 | 0.128458 | -15.022% | 117.677% | -2.035% | PASS |
| atomics | 0.155879 | 0.155671 | -0.347% | 100.348% | -10.690% | PASS |
| bounded-cycles | 0.206551 | 0.203794 | -1.192% | 101.207% | -11.321% | PASS |
| call-arguments | 0.095950 | 0.061924 | -35.447% | 154.911% | -24.194% | PASS |
| closures | 0.248812 | 0.143110 | -42.568% | 174.119% | -17.925% | PASS |
| coroutines | 0.108002 | 0.083099 | -23.218% | 130.239% | -34.372% | PASS |
| destruction | 0.234196 | 0.137599 | -41.429% | 170.732% | -5.751% | PASS |
| exceptions | 0.234995 | 0.201359 | -14.198% | 116.547% | -51.831% | PASS |
| fields | 0.152497 | 0.159935 | 5.067% | 95.177% | -12.500% | FAIL |
| platform-c-dynamic-cstring | 0.294552 | 0.006538 | -97.784% | 4513.141% | -16.000% | PASS |
| platform-c-interop | 0.289786 | 0.001933 | -99.329% | 14904.386% | -16.000% | PASS |
| platform-c-leaf | 0.145969 | 0.147039 | -0.349% | 100.350% | -8.696% | PASS |
| strings | 0.157680 | 0.110880 | -29.940% | 142.735% | -36.364% | PASS |
| virtual-dispatch | 0.148469 | 0.145421 | -2.214% | 102.264% | -4.545% | PASS |
| workers | 0.175456 | 0.176197 | 0.477% | 99.525% | -14.815% | PASS |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 7.57 | 7.96 | 5.152% |
| Compile peak RSS KiB | 1015324 | 999096 | -1.598% |
| Binary bytes | 507200 | 521696 | 2.858% |
| Retain callsites | 12 | 473 | 3841.667% |
| Release callsites | 0 | 585 | 58500.000% |
| Emitted allocation callsites | 269 | 186 | -30.855% |

Throughput geomean: **206.868%**

## Failed gates

- fields: latency regression 5.067% > 5.000%

## Benchmark shards

- `primary` on `venus`: allocation, arrays, atomics, bounded-cycles, call-arguments, closures, coroutines, exceptions
- `secondary` on `posidon`: destruction, fields, platform-c-dynamic-cstring, platform-c-interop, platform-c-leaf, strings, virtual-dispatch, workers
