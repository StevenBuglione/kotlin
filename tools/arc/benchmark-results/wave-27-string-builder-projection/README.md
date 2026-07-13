# Wave 27: StringBuilder backing-array projection

Linux x64 evidence captured on `olfa@10.10.10.12` for the exact candidate snapshot
`58ed35e8795a2c4b9cfe5fb72685aeb2f0471d40`. The emitted-code parent oracle is
commit `ff2906222`; later intervening commits only add proof-model infrastructure and do not
change generated code. The strict oracle is unmodified Kotlin/Native 1.9.10.

The optimization authenticates exactly three stdlib consumers: `append(String?)` through the
verified three-argument `insertString` wrapper, direct `append(Int)`, and direct `toString()`.
All three project the same private strong `CharArray` field. Codegen consumes an identity ledger
only while the exact consumer is active.

Results:

- Dynamic 600,000-operation string workload: `UpdateStackRef` 3,000,009 -> 0 and
  `addHeapRef` 3,600,028 -> 600,019. Heap stores, returns, and allocations are unchanged.
- Against parent `ff2906222`: candidate throughput geomean 121.406%, equal median RSS,
  and binary size +0.772%.
- Against Kotlin/Native 1.9.10 strict: candidate throughput geomean 140.815%, median RSS
  2,688 vs 3,328 KiB, and binary size +3.836% (inside the 5% gate).
- Optimized ARC, strict, debug, and ARC diagnostics produced identical stdout hashes.
- ASAN and UBSAN instrumented compile/runtime probes passed. These modes correctly select zero
  backing-array projections.

The generic compiler FileCheck harness cannot see definitions stored in the stdlib archive at
either `Codegen` or `LinkBitcodeDependencies`, so it cannot assert these stdlib-owned functions.
The representative registered runtime test, selector/verifier unit tests, exact verbose selection
log, codegen identity ledger, rebuilt stdlib cache, and dynamic symbol probes provide the gate.

Files:

- `dynamic-counts.tsv`: emitted-code parent `ff2906222` versus this candidate.
- `selection.log`: exact real-IR selected declarations and authenticated wrapper facts.
- `modes-and-sanitizers.tsv`: fallback selection, output hashes, and sanitizer results.
- `parent-paired-raw.tsv` / `parent-comparison.md`: 25 alternating pairs versus `ff2906222`.
- `strict-paired-raw.tsv` / `strict-comparison.md`: 25 alternating pairs versus strict 1.9.10.
- `provenance.txt`: commits, hashes, sizes, host, pinning, and repetition policy.
