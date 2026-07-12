# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `2ec4e4ffead4495ca44cf270bdeb7406e846b468`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| strings | 0.159667 | 0.234416 | 46.816% | 68.113% | -37.500% | FAIL |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.36 | 9.93 | -4.151% |
| Compile peak RSS KiB | 558928 | 571032 | 2.166% |
| Binary bytes | 497216 | 487424 | -1.969% |
| Retain callsites | 12 | 485 | 3941.667% |
| Release callsites | 0 | 500 | 50000.000% |
| Emitted allocation callsites | 257 | 194 | -24.514% |

Throughput geomean: **68.113%**

## Failed gates

- strings: latency regression 46.816% > 5.000%
- throughput geomean 68.113% < 100.000%

## Wave result and verification

This diagnostic wave lowers exact strong `CharArray` field arguments as `+0` only for the
canonical `CharArray.size` and sized `kotlin.collections.copyOf` symbols while the dispatch
receiver remains guaranteed. `StringBuilder.ensureCapacity` consequently emits no
`UpdateStackRef` or `UpdateReturnRef` calls around those field projections; the remaining heap
replacement still uses `UpdateHeapRef`.

Compared with the Wave 08 strings median of 0.316248055 seconds, the exact final-tree median of
0.234416319 seconds is 25.876% lower. Its strict-normalized throughput ratio rose from 49.360% to
68.113%. This is a material emitted-code improvement, but the string scenario remains outside the
5% individual-regression gate and is not claimed complete.

The benchmark candidate `2ec4e4ffead4495ca44cf270bdeb7406e846b468` and final focused-test
snapshot `5f5d4aff881c034a1da50e3072d1b6c267598699` share the exact source tree
`a9e5fd4ec69dfc7e3d14c97e66c85a424df4c339`. On that tree, JVM ownership tests, functional
capacity growth, throwing `copyOf`, later-argument field replacement, and Codegen FileCheck all
passed. The full Linux x64 Native sanity suite also passed. `strings.expected` and one raw output
from each memory model are included to make observable-output equivalence independently auditable.
