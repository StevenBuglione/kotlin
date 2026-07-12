# ARC performance gate

The ARC comparison never uses the current fork's `strict` mode as its baseline. It builds two
independent distributions on one selected Linux builder:

- `candidate-arc`: the current detached snapshot, compiled with `-memory-model arc`;
- `baseline-strict`: a separate, clean worktree pinned to Kotlin `v1.9.10` commit
  `3db61efe5e892bf27115f1ebcab957d903067ed4`, compiled with `-memory-model strict`.

Both distributions receive the same source, target, and optimization flags. Dist provenance files
are written only after their builds succeed, and the comparison rejects missing, stale, same-path,
dirty, or wrong-commit baselines.

Run a named wave on the primary or secondary builder:

```text
just remote-arc-bench wave-01
just ci2-arc-bench wave-01-ci2
```

The durable remote run keeps binaries and verbose logs under `.arc-runs`. The final recipe exports
only the commit-ready evidence bundle to `tools/arc/benchmark-results/<wave>`: input and hardware
manifests, both provenance files, raw TSV/JSON, static metrics, summaries, and the Markdown report.
It exports the report even when a hard performance gate fails, while preserving the gate's non-zero
exit status.

Each runtime repetition records elapsed time, calculated throughput, peak RSS, operations, and the
fixture-declared logical allocation count. Static metrics record compiler elapsed time and peak RSS,
binary bytes, and deterministic ownership callsite counts from `objdump -d -C`. The callsite counter
counts calls targeting the Kotlin ownership operations (`Update*Ref`, heap reference operations, and
frame release), plus emitted `Alloc*Instance` callsites; it does not count symbol definitions or
address loads. Allocation callsites are emitted-code evidence, not runtime allocation-event counts.
Runtime elapsed time uses the Linux nanosecond wall-clock source around the process, while
`/usr/bin/time` supplies peak RSS; this avoids centisecond quantization for fast scenarios.

Runtime order alternates baseline/candidate on every repetition and uses a reliably available CPU
affinity when Linux exposes one (the parallel worker scenario retains the inherited CPU set).
Runtime scenarios use nine repetitions by default, preserving an odd median sample while giving
each model the first position four times across paired runs; the baseline leads the unavoidable
ninth sample. Short fixtures are scaled toward roughly 0.15–0.4 seconds per model so process startup
and timer noise do not dominate comparisons. When a genuine model-specific ownership cost makes
that band impossible for both models, the workload keeps the baseline statistically useful instead
of shrinking the candidate workload and hiding the regression.
Compilation order also alternates and the committed static metric is
the median of three compile repetitions by default; raw compilation repetitions remain in
`compile-raw.tsv`.

The `call-arguments` scenario is the focused emitted-code benchmark for stable-suffix borrowing.
It performs 80 million direct calls with two mutable `Payload` references around a primitive index,
replacing the first reference every 16,384 iterations. Its deliberately large consumer and cold
recursive edge keep a meaningful call boundary without compiler-version-specific annotations. The
checksum covers both object values and the argument position; the logical allocation count is the
two initial payloads plus the exact number of periodic replacements.

The `bounded-cycles` scenario intentionally keeps its leak surface fixed at 25,000 two-object
cycles. Timing is scaled with 10,240 deterministic edge traversals per cycle, not with additional
leaked allocations; its reported operation count is therefore 256 million traversals while logical
allocations remain 50,000.

The `workers` scenario launches four workers that each perform 40 million increments through a
worker-local `AtomicInt`, for 160 million operations in total. Its logical allocation count is a
conservative 16, including the four worker-local atomic objects.

The C interop measurements are intentionally split so one compiler optimization cannot hide a
different cost. `platform-c-interop` preserves the fixed ASCII Kotlin string case and measures
static-CString lowering and folding. `platform-c-leaf` calls a no-inline C `strlen` pointer wrapper
configured with cinterop's `noStringConversion`, using a
single pinned byte buffer whose NUL terminator moves at runtime; a process-specific phase only
rotates eight equally frequent lengths, so its checksum remains deterministic while the call cannot
be constant-folded. `platform-c-dynamic-cstring` passes a periodically replaced mutable Kotlin
String through a separate automatically converted no-inline wrapper and isolates repeated Kotlin
String-to-CString conversion.

For focused validation, `ARC_BENCH_SCENARIOS` accepts a comma-separated subset, for example
`call-arguments,platform-c-leaf,platform-c-dynamic-cstring`. Commas keep the selection intact through the SSH
command transport.

Default hard gates are configurable through `ARC_BENCH_*` variables:

- no scenario may regress median latency or peak RSS by more than 5%;
- geometric-mean throughput must be at least 100% of the v1.9.10 baseline;
- binary size may grow by at most 5%;
- logical allocation counts and observable checksums must match exactly.

Compile time, compile RSS, and emitted ownership callsites are reported comparison metrics, not hard
gates. Callsite totals are deliberately separate from the curated optimizer-plan gate, which requires
at least 90% elimination on its own controlled corpus; linked-binary callsites include runtime support
and cannot substitute for that optimizer measurement.

`ARC_BENCH_ENFORCE=0` still creates all evidence but disables the non-zero gate result. It does not
relax provenance, output-equivalence, or baseline isolation checks.
