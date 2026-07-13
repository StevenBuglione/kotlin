# Exact stack-local lifetime-check proof

The `fields` benchmark allocates `MutableFields` in the physical stack frame and replaces its
`reference` field every 16,384 iterations. Before this wave, the replacement path called
`CheckLifetimesConstraint` after every `Payload` allocation. The runtime check always accepted the
store because the destination header is local.

This wave proves the same fact in code generation from exact `StackLocal.objHeaderPtr` identity.
The proof deliberately does not follow loads, casts, phis, parameters, or other aliases.

Emitted-code comparison for `candidate-arc-benchmark.kexe`:

| Measurement | Before | After |
|---|---:|---:|
| SHA-256 | `0ab2fd68923f93b731299b7a454776ba932d530eec23affe9123a8bcb959e2cf` | `85aea50afd642555946965424392a27cd9b17a4f82b1c910e0970ba26ef20ae9` |
| `kfun:#main` size | 20,630 bytes | 20,582 bytes |
| Rare replacement-path lifetime check | present | absent |
| Replacement-path `UpdateHeapRefRelaxed` | present | present |

The workload performs 12,208 reference replacements. The optimized binary removes only those
redundant checks; it retains the constructor-path check and the owning heap-reference update.

Verification:

- optimized ARC exact-stack receiver: check absent;
- parameter, mutable/phi, escaped heap, and `@ArcDeinit`-promoted receivers: check present;
- optimized debug build: check present;
- optimized ARC leak-diagnostics build: check present;
- full 16-scenario paired matrix: all gates passed.
