# Wave 21: out-of-line ARC cold deallocation

## Accepted change

The accepted candidate keeps the existing Kotlin/Native CAS retain/release state machine byte-for-byte and marks only `drainArcDestructionWorklist` `NO_INLINE RUNTIME_NOTHROW`. This follows Swift's split between the inline release fast path and its out-of-line deallocation path.

On `posidon` (`11th Gen Intel Core i7-11700K`), 25 pinned, order-rotated three-way samples produced these throughput ratios:

| Scenario | vs Wave20 ARC | vs Kotlin 1.9.10 strict | Candidate median | Wave20 median | Strict median |
|---|---:|---:|---:|---:|---:|
| Allocation | 106.925% | 165.890% | 165.614 ms | 178.567 ms | 276.709 ms |
| Destruction | 105.193% | 167.782% | 138.110 ms | 145.300 ms | 232.940 ms |
| Closures | 100.173% | 195.032% | 165.470 ms | 164.812 ms | 321.926 ms |
| Coroutines | 103.815% | 84.755% | 178.499 ms | 184.539 ms | 152.093 ms |
| Direct non-final RC | 108.661% | 74.225% | 378.199 ms | 406.550 ms | 279.835 ms |

The separate 50-pair pinned closure resample measured 100.015% Wave20 throughput with equal 2,688 KiB median RSS. Observable outputs and logical allocation counts matched for every sample.

The final executable was 513,544 bytes, 0.394% above the 511,528-byte strict executable and therefore within the 5% strict-baseline gate. Candidate compilation took 10.27 seconds and 594,412 KiB peak RSS, versus strict's 10.96 seconds and 575,668 KiB (3.255% higher compile RSS, within the 5% gate). Runtime RSS was equal to or below strict in every measured scenario.

## Emitted/runtime proof

- Ordinary retain and release remain the original retrying CAS operations; final release remains the original ACQ_REL zero-to-deallocating transition.
- The final runtime contains no ARC refcount `atomicrmw add/sub` introduced by this wave.
- Zero-count destruction is emitted as 13 calls/tail-calls to one out-of-line `drainArcDestructionWorklist` slow path instead of pulling its vector frame, exception personality, and destruction loop into the common release function.
- The strict runtime bitcode is byte-identical to Wave20 (`ee435386...`, `cmp` exit 0).
- Same-source, same-basename strict executables from the candidate and Wave20 distributions are byte-identical (`22d5aebe...`, `cmp` exit 0).

## Verification

- Normal ARC runtime: 1,013 passed, 9 expected skips.
- ASAN ARC runtime: 1,013 passed, 9 expected skips.
- UBSAN ARC runtime: 1,013 passed, 9 expected skips.
- TSAN ARC sanitizer fixture: pass; intentional heap race detected with a ThreadSanitizer report.
- All three full runtime variants completed in one green Gradle invocation in 2m59s.

## Rejected fetch-RMW experiments

Two broader atomic-RMW variants were tested and rejected. The first used unconditional fetch-add/fetch-sub and regressed allocation throughput 4.45% versus Wave20. The refined variant restored a one-RMW final CAS but retained fetch-RMW for non-final operations; although curated workloads improved, a direct 100-million-operation anchored retain/release workload took about 0.54 seconds versus Wave20's 0.41 seconds, a roughly 32% regression. Swift itself uses CAS for these packed counts. The final patch therefore contains none of the rejected state-machine changes.

`raw-rejected-fetch-v1-25.tsv` and `raw-rejected-fetch-v2-25.tsv` preserve those results separately from `raw-accepted-25.tsv`.
