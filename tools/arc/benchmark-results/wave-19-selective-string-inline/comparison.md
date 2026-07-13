# Wave 19 selective StringBuilder.append(String?) inlining

The selective path is a measurable same-ARC win, but strings remain below the exact Kotlin/Native 1.9.10 strict oracle.

| Comparison | 25-pair throughput geomean | Median latency | Binary size |
|---|---:|---:|---:|
| Tagged ARC vs same compiler with `SelectiveArcGeneratedInlining` disabled | **101.895%** | 131.520 ms vs 133.277 ms | 489,648 vs 485,624 bytes (+0.829%) |
| Tagged ARC vs exact 1.9.10 strict | **86.425%** | 131.148 ms vs 113.310 ms | 489,648 vs 507,160 bytes (-3.453%) |

All 100 measured executions produced the identical checksum and operation/allocation counts. Each comparison used 25 alternating, CPU-0-pinned pairs on `venus` (AMD Ryzen AI Max+ 395); timings were never compared across hosts.

The same-ARC oracle used the exact candidate compiler and distribution with only:

```text
-Xdisable-phases=SelectiveArcGeneratedInlining
```

The benchmark candidate reported `tagged=12, inlined=12, rejected=0, missingBody=0, budgetSkipped=0, failures=0`. The focused emitted fixture reported exactly three tagged and three inlined compiler-generated `append(String?)` calls. Its `append(Int)` call and both user-authored fluent `append(String?)` calls stayed out of line, and GlobalDCE removed the private companion clone.

Debug, ARC diagnostics, ASAN, and strict pre-postprocessing IR contained zero tags. Linux x64 coverage is rejected as unsupported by this 1.9.10 compiler before code generation.

Machine-readable provenance, every raw paired latency, raw-artifact hashes, binary sizes, emitted counts, and verification results are in `results.json`. The authoritative remote raw TSVs were:

- `/home/olfa/codex-kotlin-arc-wave19/.arc-runs/wave19-string-ab/raw-25.tsv`
- `/home/olfa/codex-kotlin-arc-wave19/.arc-runs/wave19-string-ab/raw-strict-25.tsv`
