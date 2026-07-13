# Wave 29: EmptyCoroutineContext immortal return

## Outcome

The production ARC selector now authenticates the exact lowered Linux x64 stdlib getter and publishes the permanent `EmptyCoroutineContext` root without retaining it. The semantic, fallback, runtime, ASAN, and UBSAN gates passed.

Against the immediately preceding owned-result candidate, the nine-pair coroutine throughput geomean improved from `0.9332910868285101` to `0.9376811432041209`: a **0.4704% relative improvement** (`+0.4390` percentage points). The candidate remains **6.2319% below strict**, so the coroutine performance gate is still open.

## Paired benchmark

| Metric | Strict | Wave 29 candidate |
|---|---:|---:|
| Median seconds | 0.107732459 | 0.115088096 |
| Median paired throughput ratio | 1.0 | 0.935010855581908 |
| Geomean paired throughput ratio | 1.0 | 0.9376811432041209 |
| Binary bytes | 507,184 | 526,680 |

The candidate is 19,496 bytes (3.8440%) larger than strict and 24 bytes larger than the preceding owned-result candidate.

## Dynamic ownership counters

The same GDB counter script and `1 int` coroutine input were used for both ARC binaries.

| Binary | Stack updates | Heap updates | Return updates | `addHeapRef` |
|---|---:|---:|---:|---:|
| Preceding owned-result candidate | 1 | 26 | 24 | 53 |
| Wave 29 candidate | 1 | 26 | 22 | 51 |
| Delta | 0 | 0 | -2 | -2 |

This is the exact expected dynamic effect: two permanent context returns avoid retain traffic per one profiled coroutine invocation, without changing stack or heap reference writes.

## Semantic and fallback gates

- Focused IR selector unit suite passed, including the full 19-node duplicate identity census, every-identity emission-ledger drift rejection, the positive stripped-interface-accessor lowered form, and its negative non-null-accessor form.
- Optimized ARC selected exactly one `RestrictedContinuationImpl.context` getter, emitted `MoveReferenceIntoReturnSlotArc`, and emitted no `UpdateReturnRef` in that function.
- ARC debug and ARC diagnostics builds rejected the optimization, emitted `UpdateReturnRef`, and emitted no move helper.
- Strict emitted its authoritative raw result-slot `store` followed by `ret`, with neither ARC move helper nor `UpdateReturnRef`.
- ASAN and UBSAN rejected the optimization as `UnsupportedCompilationMode`, used `UpdateReturnRef`, emitted no move helper, and ran clean with `checksum=42` under halt-on-error settings.
- The optimized standalone runtime fixture printed `ARC_EMPTY_CONTEXT_IMMORTAL_RETURN_OK checksum=42`.

## Provenance

The benchmark candidate's relevant compiler and fixture sources match commit `549bf6efe1fce63db8eee0d1364ada8ed3169ccd` (`[K/N] Return permanent coroutine contexts without retains`), tree `188fe117d6d0eeda63bf88e9a1f17bd097009f1d`. The remote execution worktree retained a stale base HEAD and received the committed source overlay; the six codegen-relevant source hashes recorded in `provenance.json` match the committed local files exactly. Its build orchestration file also contained concurrent C-lane changes, but the benchmark was compiled by direct `konanc` invocation and did not consume Gradle test orchestration.

Raw measurements are preserved byte-for-byte in `summary.json` and `runtime_pairs.csv`. Complete machine-readable hashes, commands, gates, and counters are in `provenance.json`.
