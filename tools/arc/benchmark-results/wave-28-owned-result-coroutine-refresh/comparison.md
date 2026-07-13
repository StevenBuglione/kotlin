# Wave 28: owned-result coroutine refresh

This refresh measures the emitted owned `Result` heap-store transfer at commit
`9831d54f8b7f9d619868fbd2855d38094b874ced` against the cached strict memory-manager
binary on `olfa@10.10.10.8`. Both executables produced:

```text
ARC_BENCH_OK scenario=coroutines checksum=45000150000 operations=300000 allocations=900000
```

## Result

| Metric | Strict | Current ARC | ARC change |
|---|---:|---:|---:|
| Median time, 9 alternating pairs | 107.673 ms | 115.153 ms | — |
| Paired throughput geomean | 100% | 93.329% | -6.671% |
| Median paired throughput | 100% | 93.362% | -6.638% |
| Median maximum RSS | 3,696 KiB | 2,432 KiB | -34.20% |
| Binary size | 507,184 bytes | 526,656 bytes | +19,472 bytes (+3.84%) |

The old 74–83% coroutine regression is no longer current. The emitted candidate is now about
6.7% slower than strict on this focused workload. It narrowly misses the release requirement that
no individual scenario regress by more than 5%.

## Dynamic ownership traffic

The focused `Int` coroutine profile was run under GDB with counts 0, 1 and 2. Subtracting count 1
from count 2 gives the steady-state traffic for one completed coroutine:

| Runtime operation | Per coroutine |
|---|---:|
| `UpdateStackRefRelaxed` | 1 |
| `UpdateHeapRefRelaxed` | 11 |
| `UpdateReturnRefRelaxed` | 12 |
| `addHeapRef` | 26 |

The owned-result transfer removed one heap update and one retain-path entry from the previous
12-heap/27-retain profile. The next exact high-frequency opportunity is
`RestrictedContinuationImpl.context`, which returns the permanent `EmptyCoroutineContext` twice
per coroutine and currently uses an owned return update both times.

## Selector status

The immortal-return semantic proof (`6743ff4f1`) and exact real-IR selector (`ca0fdbfec`) are **not
emitted in this binary**. They authenticate the stdlib getter, the post-object-lowering static root,
its `IrConstantObject` permanent allocation, and a zero-write census. No ownership-planning or LLVM
codegen seam consumes that selection yet, so it removes zero runtime operations in this result.

Once emission is connected, the selected getter is expected to remove two return updates and two
retain-path entries per focused coroutine. That expectation is not counted as a benchmark result.

## Method and provenance

- Five warmups per binary preceded nine CPU-16-pinned alternating pairs.
- Raw timings are in `runtime_pairs.csv`; raw `/usr/bin/time` RSS samples are in `rss_pairs.csv`.
- `dynamic_counters.csv` contains the three absolute GDB samples and derived steady-state delta.
- `provenance.json` pins commits, Git trees, compiler jars, interop KLIBs, fixtures and executable
  SHA-256 hashes.
- The candidate dist was built in the dedicated owned-result worktree before the proof/selector
  files were copied there. Commit `9831d54f8` is the authoritative emitted source snapshot; the
  compiler-jar and executable hashes pin the actual measured artifacts.

This directory records evidence only. It does not claim the immortal selector is active.
