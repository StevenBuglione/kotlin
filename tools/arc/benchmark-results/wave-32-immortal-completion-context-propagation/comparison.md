# Wave 32: immortal completion-context propagation

## Outcome

The production ARC backend now recognizes exact final completion implementations whose immutable
`context` field is initialized once from `EmptyCoroutineContext`. For those classes it emits a raw
initialization store, publishes the permanent getter result directly into the caller return slot,
and omits destruction of that exact immortal field. Unsupported or ambiguous shapes retain the
ordinary ARC path.

The emitted slice passes its semantic and fallback matrix and improves the pinned full `coroutines`
benchmark by **1.17% geomean paired throughput** with **no binary-size growth**.

## Codegen ownership traffic

Counts are from LLVM IR saved immediately after `Codegen`, before optimization can inline ARC
helpers. This is the authoritative static layer for `ZeroHeapRef` and `AddHeapRef`.

| Artifact | Selected | Metric | Baseline | Candidate | Delta |
|---|---:|---|---:|---:|---:|
| Focused profile | 2 | `UpdateHeapRef` | 554 | 552 | -2 |
| Focused profile | 2 | `UpdateReturnRef` | 415 | 413 | -2 |
| Focused profile | 2 | `MoveReferenceIntoReturnSlotArc` | 2 | 4 | +2 |
| Focused profile | 2 | `ZeroHeapRef` | 455 | 453 | -2 |
| Focused profile | 2 | `AddHeapRef` | 0 | 0 | 0 |
| Full `coroutines` | 1 | `UpdateHeapRef` | 566 | 565 | -1 |
| Full `coroutines` | 1 | `UpdateReturnRef` | 416 | 415 | -1 |
| Full `coroutines` | 1 | `MoveReferenceIntoReturnSlotArc` | 2 | 3 | +1 |
| Full `coroutines` | 1 | `ZeroHeapRef` | 459 | 458 | -1 |
| Full `coroutines` | 1 | `AddHeapRef` | 0 | 0 | 0 |

The focused profile therefore changes exactly one init, getter, and destroy operation per selected
class. Its final executable also changes exactly two linked `UpdateHeapRef` and two linked
`UpdateReturnRef` callsites. The linked `addHeapRef` helper has eight callsites in both artifacts;
`ZeroHeapRef` is LTO-inlined, hence the pre-LTO evidence above.

## Dynamic ownership traffic

The GDB counter fixture ran both focused profile binaries with argument `1000` and the same checksum:

| Model | Stack updates | Heap updates | Return updates | `addHeapRef` |
|---|---:|---:|---:|---:|
| Baseline | 2,000 | 22,016 | 19,014 | 47,029 |
| Candidate | 2,000 | 20,016 | 15,014 | 41,029 |
| Delta | 0 | -2,000 | -4,000 | -6,000 |

## Pinned runtime A/B

The full benchmark was pinned to CPU 16. After five baseline/candidate warmup pairs, 21 measured
pairs alternated execution order and validated the exact `coroutines` checksum on every run.

| Metric | Baseline | Candidate | Result |
|---|---:|---:|---:|
| Median seconds | 0.115719156 | 0.114348457 | candidate faster |
| Median paired throughput | — | — | +1.2149% |
| Geomean paired throughput | — | — | +1.1697% |
| Binary bytes | 526,640 | 526,640 | 0 |

## Verification gates

- Exact optimized selector, getter, initialization, and destroy-thunk FileCheck: pass.
- Debug, no-opt, phase-disabled, diagnostics, ASAN, UBSAN, and strict fallbacks: pass.
- Optimized and phase-disabled runtime fixture, including constructor-failure cleanup: pass.
- Focused structural and real-IR selector unit tests: pass.
- Kotlin compiler compilation and `git diff --check`: pass.

Raw pair timings, static counts, dynamic counters, hashes, and exact commands are stored beside this
file.
