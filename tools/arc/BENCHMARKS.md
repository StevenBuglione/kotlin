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
Compilation order also alternates and the committed static metric is
the median of three compile repetitions by default; raw compilation repetitions remain in
`compile-raw.tsv`.

The `call-arguments` scenario is the focused emitted-code benchmark for stable-suffix borrowing.
It performs 20 million direct calls with two mutable `Payload` references around a primitive index,
replacing the first reference every 16,384 iterations. Its deliberately large consumer and cold
recursive edge keep a meaningful call boundary without compiler-version-specific annotations. The
checksum covers both object values and the argument position; the logical allocation count is the
two initial payloads plus the exact number of periodic replacements.

For focused validation, `ARC_BENCH_SCENARIOS` accepts a comma-separated subset, for example
`call-arguments,exceptions,platform-c-interop`. Commas keep the selection intact through the SSH
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
