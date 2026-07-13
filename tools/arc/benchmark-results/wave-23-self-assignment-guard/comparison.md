# Wave 23: exact-barrier ARC self-assignment guards

Wave 23 adds an ARC-only final-module guard around each reference-update barrier. The generated
shell loads the old slot value and returns immediately when `old == new`; every changed path calls
the exact original stack, return, or heap barrier once. It does not reproduce retain, store, release,
marker, assertion, or diagnostic semantics.

## Result

| Scenario | Candidate median | Wave 22 median | Candidate throughput |
|---|---:|---:|---:|
| Coroutines | 161.463 ms | 161.385 ms | 99.952% |
| Fields | 157.662 ms | 156.085 ms | 99.000% |
| Call arguments | 101.943 ms | 122.862 ms | 120.521% |
| Direct non-final RC | 230.252 ms | 238.467 ms | 103.568% |

The standard benchmark binary is 0.167% larger than Wave 22; the direct-RC binary is 0.143%
larger. All incremental scenarios remain above the 95% acceptance floor. The split retain/release
variant regressed substantially on the Intel verification host, while the combined replacement-leaf
variant had already regressed the direct-RC diagnostic on the AMD build host; both were rejected.

## Safety and verification

- Separate stack, return, and heap shells preserve the exact original barrier identity.
- Candidate calls are frozen before shell creation, so a shell's original call cannot be rewritten.
- Unsupported calls or runtime-shape drift reject the module before mutation.
- The pass requires a versioned marker emitted only by the standard uninstrumented ARC runtime.
- Debug, assertion, diagnostic, sanitizer, coverage, strict, non-final, and non-Linux builds do not run it.
- Three C++ structural tests and all six real compiler FileChecks pass.
- Optimized smoke and five-million-allocation stress tests pass.
- ASAN and UBSAN compile-and-runtime probes pass.
- `strict.bc`, `strict_ubsan.bc`, and a paired strict executable are byte-identical to the oracle.

The authoritative run used 25 samples per model in a three-position Latin rotation on
`olfa@10.10.10.12`. Exact hashes, medians, paired results, and provenance are in `results.json`.
