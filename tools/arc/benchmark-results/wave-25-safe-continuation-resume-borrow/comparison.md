# Wave 25: borrow `SafeContinuation.resumeWith` atomic projections

Wave 25 removes the three owning stack temporaries created for the exact Kotlin 1.9.10 stdlib
`SafeContinuation.resumeWith` loads of its private `resultRef` field. The guaranteed dispatch
receiver owns the field across each immediate `FreezableAtomicReference.value` or `compareAndSet`
call, including exceptional exits, so these projections can remain `+0`.

## Result

| Metric | `bea5a8f7c` | Wave 25 | Change |
|---|---:|---:|---:|
| Coroutine median, 25 alternating pairs | 124.021 ms | 118.011 ms | 5.11% more throughput |
| Paired throughput geomean | 100% | 105.02% | +5.02% |
| `SafeContinuation.resumeWith` Codegen `UpdateStackRef` sites | 3 | 0 | -3 |
| Dynamically executed selected stack updates per coroutine | 2 | 0 | -2 |
| All focused-fixture stack updates per coroutine | 3 | 1 | caller outcome load deliberately unchanged |
| Heap updates per coroutine | 12 | 12 | unchanged |
| Return updates per `Int` coroutine | 12 | 12 | unchanged |
| ARC retain-path entries per coroutine | 29 | 27 | -2 |
| Benchmark binary size | 514,400 bytes | 514,416 bytes | +16 bytes (+0.0031%) |

Against the unmodified Kotlin/Native 1.9.10 strict manager, the same 25-pair sample measured
118.336 ms for Wave 25 versus 107.980 ms for strict. Wave 25 therefore reaches 91.15% of strict
throughput on this coroutine workload. This is a large improvement over earlier waves but still
misses the per-scenario 5% release gate; the remaining caller/result/getter ownership families are
separate work and are not mixed into this proof.

## Safety boundary

- Selection requires ARC optimized mode with debug and ARC diagnostics disabled.
- The caller, class, field, getter and CAS declarations must be the exact stdlib KLIB identities.
- `resumeWith` and both consumers must be real, nonexternal, nonvirtual and nonsuspending.
- The field must be private, strong, nonvolatile and written exactly once by a constructor.
- The complete body must contain exactly three projections: one getter and two direct CAS calls.
- Nested functions, suspension boundaries, an extra load, another writer or another consumer fail closed.
- An ownership verifier proves each bounded normal and exceptional borrow.
- An identity-keyed codegen ledger consumes all three selected projections exactly once.
- Heap/return ownership, public ABI and atomic reference counting are unchanged.

## Verification

- `dist` and opt/debug/diagnostic/strict FileChecks: pass.
- `SafeContinuation` runtime/worker-race fixture: pass.
- Seven eligibility/negative unit tests: pass.
- Exception/finally coroutine stress: 100,000 iterations pass.
- ASAN and UBSAN stress: 25,000 iterations pass; instrumentation symbols are present.
- Ordinary ARC executable no-collector symbol audit: pass.
- Current versus `bea5a8f7c` strict executable: byte-identical, SHA-256
  `ebb39712bb1e77bab35b9c3071ae593130b7db07de2b6ebb199a733b3f30922c`.
- Exact output for all benchmark binaries:
  `ARC_BENCH_OK scenario=coroutines checksum=45000150000 operations=300000 allocations=900000`.

Raw alternating measurements are in `runtime_bea5_candidate.csv` and
`runtime_strict_candidate.csv`. Machine-readable counts and verification outcomes are in
`results.json` and `verification.txt`.

## Provenance

- Oracle: `bea5a8f7c926efebf40d2b10693515e2fc220a18`.
- Candidate snapshot: `092f7a774f5dcfedf3d6c4fc8fa2977f3c7e06a0`, tree
  `b48aa35cb5f4db20373ed77a6e3f0f8714fd3235`.
- Machine: `olfa@10.10.10.8`, Linux x64, pinned CPU 16.
