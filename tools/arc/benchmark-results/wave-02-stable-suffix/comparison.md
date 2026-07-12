# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `e4cb022e3ee93ae91ff388e36d9d3b2550403a2e`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| allocation | 0.095896 | 0.059779 | -37.662% | 160.417% | -23.333% | PASS |
| arrays | 0.033218 | 0.032965 | -0.761% | 100.767% | -8.696% | PASS |
| atomics | 0.047204 | 0.045907 | -2.749% | 102.827% | -4.545% | PASS |
| bounded-cycles | 0.002083 | 0.002167 | 4.033% | 96.124% | -4.545% | PASS |
| call-arguments | 0.038868 | 0.068435 | 76.071% | 56.795% | -13.043% | FAIL |
| closures | 0.002407 | 0.002119 | -11.988% | 113.620% | -9.091% | PASS |
| coroutines | 0.105112 | 0.182244 | 73.381% | 57.677% | -27.586% | FAIL |
| destruction | 0.011770 | 0.019609 | 66.595% | 60.026% | -6.891% | FAIL |
| exceptions | 0.361079 | 0.312574 | -13.433% | 115.518% | -52.273% | PASS |
| fields | 0.017521 | 0.018421 | 5.140% | 95.111% | -8.696% | FAIL |
| platform-c-interop | 0.333801 | 0.386772 | 15.869% | 86.304% | -20.000% | FAIL |
| strings | 0.041634 | 0.082773 | 98.813% | 50.299% | -25.926% | FAIL |
| virtual-dispatch | 0.038084 | 0.064064 | 68.217% | 59.447% | -9.091% | FAIL |
| workers | 0.001883 | 0.001741 | -7.522% | 108.133% | -18.519% | PASS |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.22 | 9.02 | -11.742% |
| Compile peak RSS KiB | 639104 | 535648 | -16.188% |
| Binary bytes | 497216 | 421392 | -15.250% |
| Retain callsites | 12 | 584 | 4766.667% |
| Release callsites | 0 | 1050 | 105000.000% |
| Emitted allocation callsites | 255 | 186 | -27.059% |

Throughput geomean: **85.383%**

## Failed gates

- call-arguments: latency regression 76.071% > 5.000%
- coroutines: latency regression 73.381% > 5.000%
- destruction: latency regression 66.595% > 5.000%
- fields: latency regression 5.140% > 5.000%
- platform-c-interop: latency regression 15.869% > 5.000%
- strings: latency regression 98.813% > 5.000%
- virtual-dispatch: latency regression 68.217% > 5.000%
- throughput geomean 85.383% < 100.000%
