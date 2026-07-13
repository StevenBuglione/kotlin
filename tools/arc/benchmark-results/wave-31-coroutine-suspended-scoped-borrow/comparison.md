# Wave 31: scoped `COROUTINE_SUSPENDED` borrow

## Outcome

The production ARC backend now proves and emits a scoped `+0` projection for the two exact
`COROUTINE_SUSPENDED` identity comparisons in the focused coroutine program. All semantic,
ownership, ABI, fallback, sanitizer, and runtime gates passed at commit `03a2cc99d`.

This is deliberately recorded as **ownership infrastructure, not a performance optimization**.
The final linked parent and candidate executables have identical dynamic ownership traffic and
identical linked ARC callsite counts. LLVM already canonicalized the parent's owned getter path,
so a timing A/B would measure noise rather than an attributable improvement.

## Dynamic ownership traffic

The same GDB counter script was run at coroutine counts 0, 1, and 2. The steady-state row is
count 2 minus count 1.

| Model | Sample | Stack updates | Heap updates | Return updates | `addHeapRef` |
|---|---:|---:|---:|---:|---:|
| Parent (`549bf6efe`) | 0 | 0 | 3 | 6 | 9 |
| Candidate (`03a2cc99d`) | 0 | 0 | 3 | 6 | 9 |
| Parent | 1 | 1 | 26 | 22 | 51 |
| Candidate | 1 | 1 | 26 | 22 | 51 |
| Parent | 2 | 2 | 37 | 32 | 75 |
| Candidate | 2 | 2 | 37 | 32 | 75 |
| Parent | steady-state 1→2 | 1 | 11 | 10 | 24 |
| Candidate | steady-state 1→2 | 1 | 11 | 10 | 24 |

## Final executable evidence

| Metric | Parent | Candidate | Delta |
|---|---:|---:|---:|
| `UpdateReturnRefRelaxed` linked callsites | 276 | 276 | 0 |
| `addHeapRef` linked callsites | 8 | 8 | 0 |
| Profile executable bytes | 447,664 | 447,672 | +8 |

The immediate Git parent `4a7efb59c` changes benchmark evidence only. There are no compiler,
runtime, or stdlib changes between the emitted semantic baseline `549bf6efe` and `4a7efb59c`, so
the cached Wave 29 profile executable is the exact relevant parent artifact.

## Semantic gates

- Exact selector count: 2.
- Optimized scoped-borrow FileCheck: pass.
- Owned-result and public `+1` ABI FileChecks: pass.
- Debug, no-opt, diagnostics, ASAN, UBSAN, and strict fallback FileChecks: pass.
- Runtime checksum fixture: pass (`checksum=42`).
- Focused semantic and real-IR selector unit tests: pass.

Raw counters are in `dynamic-counters.csv`; final linked counts are in
`final-symbol-counts.csv`; hashes and commands are pinned in `provenance.json`.
