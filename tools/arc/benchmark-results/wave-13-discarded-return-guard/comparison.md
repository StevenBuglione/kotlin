# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `44d32f1d53d3c8568a313e4a89fcd710acaf0eed`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| strings | 0.159004 | 0.196095 | 23.327% | 81.085% | -27.586% | FAIL |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 10.35 | 9.87 | -4.638% |
| Compile peak RSS KiB | 571444 | 569796 | -0.288% |
| Binary bytes | 497216 | 475856 | -4.296% |
| Retain callsites | 12 | 443 | 3591.667% |
| Release callsites | 0 | 456 | 45600.000% |
| Emitted allocation callsites | 257 | 189 | -26.459% |

Throughput geomean: **81.085%**

## Failed gates

- strings: latency regression 23.327% > 5.000%
- throughput geomean 81.085% < 100.000%

## Disposition

This experiment combined one owning result slot for all four `StringBuilder.append` calls in a
lowered string template with an ABI-preserving callee guard that skipped `UpdateReturnRef` when
the result slot already contained the returned receiver. Fresh binary disassembly proved that all
four hot-loop calls passed the same slot and that both the `Int` and `String?` overloads executed
the conditional fast path.

Despite changing the emitted hot path, the result did not improve beyond prior host variation:
the ARC median is 1.964% slower than Wave 12's 0.192317852 seconds, while static ownership
callsites and binary size are unchanged. The implementation was therefore discarded rather than
landed. The likely explanation is that `UpdateReturnRef` already performs the pointer-equality
check cheaply, so moving the check into each append callee only exchanges call overhead for an
additional branch. This evidence is retained to prevent repeating the same optimization.
