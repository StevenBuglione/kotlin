# Wave 30: scoped C-string loop cache

This evidence isolates commit `35c38259c0a1c6f12a08a507ef8e17ec1ec14273`
(tree `1c31cf4bb25b455a00dd3a82a86ccd1902afbeff`) from its immediate parent
`81a8c912b7fe325b08f58081b0ecfaf19f9678c7`. Both ARC revisions were measured
against an independently built, exact Kotlin/Native 1.9.10 strict baseline on
the same Linux x64 host.

## Result

| Comparison | Median seconds | Throughput ratio | Peak RSS | Binary bytes |
|---|---:|---:|---:|---:|
| strict in optimized run | 0.291177773 | 100.000% | 3200 KiB | 507200 |
| scoped-cache ARC | 0.006496735 | 4481.909% of strict | 2688 KiB | 526616 |
| strict in parent run | 0.292883372 | 100.000% | 3200 KiB | 507200 |
| parent ARC | 0.129061376 | 226.933% of strict | 2688 KiB | 526656 |
| scoped cache versus parent ARC | 0.006496735 / 0.129061376 | **1986.557%** | unchanged | -40 bytes |

The isolated commit is **19.8656x faster** than its parent for
`platform-c-dynamic-cstring`, reducing latency by **94.9662%**. Its emitted
conversion count fell from 1,800,000 to 1,758, a **99.9023% elimination** that
matches the workload's actual String replacement count.

The cross-run changes for the already optimized invariant-C-string and raw
C-leaf controls are below 2% and are treated as measurement noise. The scoped
cache adds no RSS or binary-size cost relative to its parent. Relative to strict,
the final ARC binary is 3.828% larger and remains within the 5% release gate.

## Protocol

- Host: `olfa@10.10.10.12` (`posidon`), Intel i7-11700K, Linux 6.8.0-134.
- Exact managed worktree: `/home/olfa/codex-kotlin-arc-interop`.
- Compiler flags: `-target linux_x64 -opt`; ARC uses `-memory-model arc`, the
  baseline uses `-memory-model strict`.
- One warmup, nine same-host interleaved repetitions, three alternating compiler
  invocations, and `taskset -c 0` for each single-threaded scenario.
- The durable benchmark runner held the host-wide exclusive ARC lock throughout
  each compile-and-measure sequence.
- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` and
  `JAVA_OPTS=-XX:ReservedCodeCacheSize=256m`.
- `optimized-runtime-pairs.csv` and `parent-runtime-pairs.csv` contain every
  byte-exact elapsed-time, derived-throughput, RSS, operation, and
  logical-allocation row retained by the two remote reports (9 samples x 2
  binaries x 3 scenarios).

Exact orchestration commands (with the listed environment applied) were:

```text
git -C /home/olfa/codex-kotlin-arc-interop checkout --detach 35c38259c0a1c6f12a08a507ef8e17ec1ec14273
python tools/arc/arc.py run arc-bench-candidate
python tools/arc/arc.py run arc-bench-baseline
ARC_BENCH_SCENARIOS=platform-c-interop,platform-c-leaf,platform-c-dynamic-cstring python tools/arc/arc.py run arc-bench --benchmark-set interop

git -C /home/olfa/codex-kotlin-arc-interop checkout --detach 81a8c912b7fe325b08f58081b0ecfaf19f9678c7
python tools/arc/arc.py run arc-bench-candidate
ARC_BENCH_SCENARIOS=platform-c-interop,platform-c-leaf,platform-c-dynamic-cstring python tools/arc/arc.py run arc-bench --benchmark-set interop
```

For every `arc.py` invocation:

```text
ARC_REMOTE=olfa@10.10.10.12
ARC_REMOTE_DIR=/home/olfa/codex-kotlin-arc-interop
ARC_REMOTE_GIT=/home/olfa/codex-kotlin-rust
ARC_MAX_WORKERS=16
ARC_BENCH_BUILD_WORKERS=16
```

The conversion counter was built from
`tools/arc/fixtures/count_scoped_cstring_refresh.c` and injected with
`LD_PRELOAD`; `ARC_CSTRING_REFRESH_RETURN_ADDRESS` was set to the return address
of the unique conversion-buffer `calloc` in each non-PIE executable. Raw results
are in `allocation-counter-raw.csv`.

```text
gcc -shared -fPIC -O2 tools/arc/fixtures/count_scoped_cstring_refresh.c -o count-scoped-cstring.so
ARC_CSTRING_REFRESH_RETURN_ADDRESS=0x431799 LD_PRELOAD=./count-scoped-cstring.so ./optimized-benchmark.kexe platform-c-dynamic-cstring
ARC_CSTRING_REFRESH_RETURN_ADDRESS=0x43a988 LD_PRELOAD=./count-scoped-cstring.so ./parent-benchmark.kexe platform-c-dynamic-cstring
```

## Profiling limitation

`perf record` could not be collected because the host has
`/proc/sys/kernel/perf_event_paranoid=4` and the account lacks `CAP_PERFMON`.
No privilege or sysctl change was attempted. Timing, RSS, conversion-counter,
binary, and disassembly evidence remain available and internally consistent.
The rejected commands used `perf record -F 999 -g` around repeated executions
of each exact benchmark binary; the kernel rejected access before sampling.
