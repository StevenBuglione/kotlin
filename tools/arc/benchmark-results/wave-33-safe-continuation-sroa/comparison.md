# Kotlin/Native ARC benchmark comparison

Baseline: strict v1.9.10 `3db61efe5e892bf27115f1ebcab957d903067ed4`
Candidate: ARC `f7f3ffb8d589969103cf0123f369886d847360d9`

| Scenario | Baseline median (s) | ARC median (s) | Latency delta | Throughput | Peak RSS delta | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| coroutines | 0.108607 | 0.083345 | -23.116% | 130.067% | -28.726% | PASS |
| strings | 0.153103 | 0.105927 | -30.365% | 143.606% | -22.222% | PASS |

## Binary and compile metrics

| Metric | Baseline | ARC | Delta |
|---|---:|---:|---:|
| Compile seconds | 7.59 | 8.01 | 5.534% |
| Compile peak RSS KiB | 955220 | 1028200 | 7.640% |
| Binary bytes | 507200 | 521696 | 2.858% |
| Retain callsites | 12 | 473 | 3841.667% |
| Release callsites | 0 | 585 | 58500.000% |
| Emitted allocation callsites | 269 | 186 | -30.855% |

Throughput geomean: **136.669%**

**All hard gates passed.**

## Benchmark shards

- `primary` on `venus`: coroutines
- `secondary` on `posidon`: strings
