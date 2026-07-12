# Wave 15 emitted-code inspection

The exact coroutine lambda
`kfun:$coroutinesWork$lambda$1$FUNCTION_REFERENCE$1.invoke#internal`
contains four direct `UpdateReturnRefRelaxed` callsites after the rooted-enum
projection change, down from eight in Wave 14.

| Candidate | Calls | Disassembly SHA-256 |
|---|---:|---|
| Wave 14 `9ddd54dcbe8c` | 8 | `3552b8de735e8301e3969ca68380a5a00c4078521a82d0733cd89b84255b1bf2` |
| Wave 15 `de6d755bfa72` | 4 | `7f3bfceca65f354ea8d9759b1d22215f9fa9a444e95fe1b0c20a70a6235918b2` |

The inspection is constrained to the exact top-level objdump symbol and stops
at the next top-level symbol. Addresses and provenance are recorded in
`emitted-inspection.json`.

The full benchmark still fails the individual-performance gate: ARC coroutine
latency is 27.659% above strict and throughput is 78.334% of strict. Peak RSS
is 34.233% lower, the binary is 2.866% smaller, and median compilation is
4.672% faster.
