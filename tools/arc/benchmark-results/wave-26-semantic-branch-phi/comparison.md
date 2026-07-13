# Wave 26: exact SemanticARC branch phi

The candidate and oracle were built on `olfa@10.10.10.12` from the same
`933688524361fc7289c9d5f6dd08e2d686d61253` base. The candidate added only the
exact, fail-closed branch-phi selector and emission path. Each sample made 50,000,000 calls through
a runtime-selected function reference, so LLVM could not fold the dispatch to one known target.

The two candidate function-reference bridges each retain one object `select` and one identity
comparison, with no ARC frame or reference update. Each oracle bridge retains one `EnterFrame`,
four `UpdateStackRefRelaxed` calls, and one `LeaveFrame`. Both candidates and both runtime-selected
operations produced the expected checksum of 25,000,000 in every sample.

| Measurement | Candidate | Oracle | Delta |
|---|---:|---:|---:|
| Paired geomean time ratio | 0.564585 | 1.000000 | 43.54% faster |
| Median sample | 488,082,339 ns | 862,756,551 ns | 43.43% faster |
| Binary size | 395,168 B | 399,280 B | -4,112 B (-1.03%) |

The run used 25 alternating-order pairs pinned to CPU 15. Odd pairs ran candidate then oracle;
even pairs ran oracle then candidate. `raw.csv` contains all 50 measurements and `summary.json`
contains the machine-readable aggregate, binary hashes, provenance, size, and checksum result.

Focused verification also passed the optimized, diagnostic ARC, and strict FileChecks; optimized
runtime execution; ASAN and UBSAN smoke runs; and byte-for-byte strict and diagnostic oracle
comparisons.
