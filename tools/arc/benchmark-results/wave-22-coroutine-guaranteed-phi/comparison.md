# Wave 22: verifier-backed coroutine guaranteed phis

Wave 22 converts the exact two loop-carried ownership webs in
`BaseContinuationImpl.resumeWith` from eagerly rooted values to path-dependent `+0` LLVM phis.
The ABI entry values remain guaranteed, while the separately owning nullable stack slots are
materialized only on the real loop backedge and remain available to normal and unwind cleanup.

## Result

| Metric | Wave 21 | Wave 22 | Change |
|---|---:|---:|---:|
| Coroutine median, 25 alternating pairs | 180.159 ms | 169.666 ms | 5.82% less latency; 6.18% more throughput |
| Runtime median max RSS | 2,688 KiB | 2,688 KiB | unchanged |
| `resumeWith` `UpdateStackRefRelaxed` sites | 7 | 2 | -5 |
| Coroutine pointer phis | 0 | 2 | +2 verified `+0` joins |
| Cold compile median, 7 alternating pairs | 10.03 s | 10.08 s | +0.50% |
| Cold compile median max RSS | 624,168 KiB | 595,884 KiB | -4.53% |
| Cold compile worst max RSS | 648,872 KiB | 650,744 KiB | +0.29% |
| Paired binary size | 485,624 bytes | 485,624 bytes | identical |

Against the Kotlin/Native 1.9.10 strict manager, the final 50-pair resample measured 169.814 ms
for Wave 22 versus 145.614 ms for strict: 16.62% more latency, or 85.75% of strict throughput.
This is a material improvement but does not close the remaining coroutine performance gate.

## Safety boundary

- Selection is restricted by actual stdlib declaration and call-symbol identity.
- The ownership proof requires two accepted joined webs and the exact projected reduction.
- The physical CFG verifier requires exactly entry plus one loop-header predecessor, a
  unique-predecessor chain from both owning stores to the natural backedge, and unconditional
  branches throughout that chain.
- Codegen accounts for every selected declaration, read, store, call, loop and phi incoming edge;
  shape drift fails compilation.
- Strict builds are untouched. Candidate and Wave 21 strict executables are byte-identical with
  SHA-256 `22d5aebe7409d2d303cf05efecfb2325c55340a70b05caa9e1c49f83c26090e5`.

## Verification

- Full ARC ownership unit suite: pass.
- Focused physical-CFG tests, including alternate-predecessor and multi-successor rejection: pass.
- `distCompiler`: pass.
- Exact output: `ARC_BENCH_OK scenario=coroutines checksum=45000150000 operations=300000 allocations=900000`.
- Exception/finally stress: normal and UBSAN pass; ASAN instrumentation is present and the test
  passes with leak detection disabled.
- With LSan enabled, Wave 22 and exact Wave 21 both report the same 1,600,192 bytes in 25,004
  allocations. This is a baseline-identical pre-existing `suspendCoroutine` leak and is recorded,
  not attributed to this transformation.

Raw alternating measurements are in `runtime_wave21_candidate.csv`,
`runtime_strict_candidate.csv`, and `compile_pairs.csv`. Sanitizer outcomes are in
`sanitizer_evidence.txt`.

## Provenance

- Exact Wave 21 oracle: `f8b1f00f3e41609fb51be6380ac2e49ec5d80b5f`.
- Candidate compiler snapshot: `c898ea3043b3a9266d227c14fcfdec6e802ff95a`, tree
  `22af946ee89169ab3d183db9b1be0e1ffb049ed8`.
- Final source/fixture snapshot: `6b6d83b6e9084a45fad80c154cfa32eb6da5676c`, tree
  `564c7402bfafa9f20eb473b326d58b65ea8a5c98`.
- Machine: `olfa@10.10.10.12`, Linux x64.
