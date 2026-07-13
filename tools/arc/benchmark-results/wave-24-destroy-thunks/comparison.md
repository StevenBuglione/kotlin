# Wave 24: per-class ARC field-destroy thunks

This wave reuses the existing `TypeInfo.processObjectInMark` slot only when the new, previously unused `TF_HAS_ARC_DESTROY_THUNK` flag is present. The TypeInfo layout and callable/KLIB ABI do not change. Old, prebuilt, unflagged, null-callback, and array types retain the exact metadata traversal fallback.

The generated callback has the slot's exact `void(void*, ObjHeader*)` signature and clears each physical reference field through `ZeroHeapRef`. That is the same authoritative operation used by the fallback: it nulls the slot, records diagnostics when enabled, and calls `ReleaseHeapRef`, whose final releases feed the existing thread-local iterative destruction worklist. Zero-filled uninitialized fields are safe, while fields initialized before constructor failure are still released after the successfully initialized deinit prefix.

This maps directly to Swift's `swift/lib/IRGen/GenHeap.cpp:createDtorFn`: visit canonical ascending layout elements, omit trivially destroyable fields, emit field destroys, and leave physical allocation release to the runtime.

## Verification

- Optimized normal fixture: `ARC_DESTROY_THUNK_OK`.
- Derived fixture TypeInfo: flag value `1792`, offsets `[8, 16, 24]`, and a private nounwind callback with exactly three ascending `ZeroHeapRef` calls.
- Ordinary, ASAN, and UBSAN ARC runtime suites passed. The reported suite contained 1,024 tests: 1,015 passed and nine expected skips.
- Flagged dispatch, forged legacy callback fallback, constructor failure, inherited deinit ordering, and deep nonrecursive destruction passed.
- Candidate and clean-oracle `strict.bc` and `strict_ubsan.bc` hashes are identical.
- Same-name candidate/oracle strict executables are byte-identical at `ca85e346adc8b99248303739dc245f1f98e7ec9d56833027a13d12393a7cd523`.

## Exact `.12` paired benchmark

Each model/scenario used 15 CPU-pinned samples with alternating execution order. Observable output was identical.

| Scenario | Candidate median | Clean oracle median | Candidate throughput |
|---|---:|---:|---:|
| One-reference destruction | 254.900 ms | 257.626 ms | 101.069% |
| Eight-reference field destruction | 297.695 ms | 309.167 ms | 103.853% |

Median RSS was unchanged: 2,432 KiB for destruction and 2,688 KiB for fields. The specialized benchmark binary grew from 379,912 to 388,104 bytes, an 8,192-byte or 2.156% increase. The performance and size results pass the wave's ±5% individual acceptance bound.

Raw samples are in `raw.tsv`; machine-readable provenance and hashes are in `results.json`.
