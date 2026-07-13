# Candidate versus Kotlin/Native 1.9.10 strict

| Metric | Strict | Candidate |
|---|---:|---:|
| Median elapsed seconds | 0.161219714 | 0.114060340 |
| Throughput geomean | 100.000% | 140.814596% |
| Median max RSS | 3,328 KiB | 2,688 KiB |
| Binary size | 507,168 bytes | 526,624 bytes |

The 25 measured pairs followed five warmups, alternated execution order, and were pinned to CPU 0.
Every run produced stdout SHA-256
`4a9b12298a97d8141fd58362a6546e7fb884244ebc38eddbd0d1923975b74f16`.
