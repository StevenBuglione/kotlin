#!/usr/bin/env bash
set -euo pipefail

# A single durable remote profile owns the host lock for distribution selection,
# focused compilation, and timing. This is diagnostic only; it never publishes a
# release evidence bundle and never enables benchmark gates.
export ARC_BENCH_QUICK=1
export ARC_BENCH_ENFORCE=0

bash tools/arc/benchmark_candidate.sh
bash tools/arc/benchmark_baseline.sh
bash tools/arc/benchmark_compare.sh
