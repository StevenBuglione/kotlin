# Wave 20: ARC string-concatenation capacity planning

Linux x64 release ARC now gives compiler-generated string concatenations an eager,
bounded `StringBuilder` capacity computed from exact literal UTF-16 lengths and a small
per-interpolation allowance. Swift's `DefaultStringInterpolation` uses the same shape,
`literalCapacity + interpolationCount * 2`; Kotlin/Native uses eight UTF-16 code units
per interpolation because its backing store is an exact-sized `CharArray` and primitive
append paths reserve substantially more than two code units.

The exact benchmark expression has five literal UTF-16 units and two interpolations, so
the generated constructor receives 21. This prevents `append(Int)` from growing the
default ten-element array merely to satisfy its eleven-character reservation.

## Results

All figures are from `venus`, pinned to CPU 0. The 25 timing pairs alternate order; every
binary produced the same checksum and logical allocation declaration.

| Metric | Wave 20 | Wave 19 ARC | 1.9.10 strict |
|---|---:|---:|---:|
| Median latency | 104.129 ms | 131.051 ms | 113.140 ms |
| Paired throughput geomean | — | **+25.617%** | **+8.514%** |
| Median peak RSS | 2,652 KiB | 2,644 KiB | 3,184 KiB |
| Binary size | 485,680 B | 489,688 B | 507,168 B |
| Compile time | 7.46 s | 7.56 s | 7.85 s |
| Compile peak RSS | 624,572 KiB | 625,692 KiB | 556,332 KiB |
| Retain/release callsites | 429 / 442 | 453 / 466 | 12 / 0 |
| Allocation callsites | 194 | 194 | 269 |
| Dynamic array allocations | 1,800,008 | 2,400,010 | — |
| Dynamic heap-reference updates | 1,200,006 | 2,400,010 | — |
| Dynamic retains | 3,600,028 | 5,400,024 | — |
| Dynamic RC decrements | 5,400,025 | 7,800,033 | — |

The candidate is 20.543% lower latency than Wave 19 and 7.964% lower than strict. Its
binary is 0.818% smaller than Wave 19 and 4.237% smaller than strict. Median runtime RSS
is effectively unchanged versus Wave 19 (+0.303%) and 16.709% below strict.

Locked `bpftrace` uprobes on the exact timed binaries prove the mechanism: the candidate
removes 600,002 array allocations, 1,200,004 heap-reference updates, 1,799,996 retains,
and 2,400,008 RC decrements from the 600,000-iteration workload. Both profiled executions
produced the benchmark checksum above. The probes targeted `AllocArrayInstanceRelaxed`,
`UpdateHeapRefRelaxed`, the relaxed manager's internal `addHeapRef`, and `decrementRC`.

## Verification

- `ArcOwnershipIRAdapterTest`: 16 tests, zero failures/errors.
- Compiler build covered backend.common plus JVM, JS, Wasm, and Native consumers, preserving
  the original one-argument `StringConcatenationLowering` constructor API.
- Four emitted LLVM FileChecks passed: optimized ARC, ARC debug, ARC diagnostics, and strict.
- Optimized ARC emits `StringBuilder.<init>(Int)` with constant 21 only for the generated
  concatenation. Explicit user-authored `StringBuilder()` remains the default constructor.
- Debug, diagnostics, and strict all retain the default generated construction.
- Compiling the focused fixture in strict mode with Wave 20 and Wave 19, using the same
  output basename, produced byte-identical Codegen LLVM (`676e57bd...`) and byte-identical
  executables (`55b521f5...`) with identical runtime output.
- Capacity arithmetic uses `Long`, rejects negative inputs and overflow, declines capacities
  above the 4,096-unit eager-allocation bound, and never evaluates interpolations.
- The final overflow-hardened compiler reproduced the timed candidate executable byte for
  byte (`68176cd2...`) when compiled with the same output basename, so the recorded 25-pair
  runtime and dynamic-probe measurements apply to the final source tree.

Raw measurements are in `raw.tsv` and `rss.tsv`; machine-readable provenance and summaries
are in `results.json`.
