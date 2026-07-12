# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `08ee853dc3680be197dea5faac0a14982b9b480d`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| allocation | 0.284622 | 0.173593 | -39.009% | 163.959% | -23.333% | PASS |
| arrays | 0.174906 | 0.166295 | -4.923% | 105.178% | -4.348% | PASS |
| atomics | 0.172686 | 0.171595 | -0.632% | 100.636% | -4.545% | PASS |
| bounded-cycles | 0.186918 | 0.058408 | -68.752% | 320.020% | -9.091% | PASS |
| call-arguments | 0.136687 | 0.102880 | -24.733% | 132.861% | -12.500% | PASS |
| closures | 0.312311 | 0.162358 | -48.014% | 192.360% | -4.545% | PASS |
| coroutines | 0.149992 | 0.260955 | 73.979% | 57.478% | -27.586% | FAIL |
| destruction | 0.238975 | 0.144759 | -39.425% | 165.084% | -5.741% | PASS |
| exceptions | 0.355703 | 0.307045 | -13.679% | 115.847% | -78.602% | PASS |
| fields | 0.157271 | 0.163391 | 3.891% | 96.254% | -12.500% | PASS |
| platform-c-interop | 0.288642 | 0.346609 | 20.083% | 83.276% | -22.222% | FAIL |
| strings | 0.156101 | 0.316248 | 102.592% | 49.360% | -27.586% | FAIL |
| virtual-dispatch | 0.146019 | 0.146334 | 0.216% | 99.785% | -4.545% | PASS |
| workers | 0.175493 | 0.176874 | 0.787% | 99.219% | -18.519% | PASS |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.3 | 9.82 | -4.660% |
| Compile peak RSS KiB | 532644 | 573812 | 7.729% |
| Binary bytes | 497216 | 487424 | -1.969% |
| Retain callsites | 12 | 489 | 3975.000% |
| Release callsites | 0 | 504 | 50400.000% |
| Emitted allocation callsites | 257 | 194 | -24.514% |

Throughput geomean: **113.803%**

## Failed gates

- coroutines: latency regression 73.979% > 5.000%
- platform-c-interop: latency regression 20.083% > 5.000%
- strings: latency regression 102.592% > 5.000%

## Rooted-loop result

The focused bounded-cycle scenario improved from 0.186918100 seconds under the unmodified 1.9.10
strict manager to 0.058408222 seconds under ARC: 68.752% lower latency and 320.020% strict
throughput. Peak RSS fell 9.091%. The measured full-suite throughput geomean is 113.803%; the
pre-run projection from the prior 96.687% geomean was 112.658%.

The candidate inner loop has no ownership calls or cursor root-slot spills and performs four edge
traversals per branch. The optimized strict loop also has no out-of-line ownership call, but spills
the cursor through four frame slots and performs two traversals per branch. See
`candidate-hot-loop.asm.txt`, `baseline-hot-loop.asm.txt`, and `selector-proof.txt`.
