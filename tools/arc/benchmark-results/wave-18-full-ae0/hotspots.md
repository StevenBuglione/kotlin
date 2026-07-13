# Wave 18 emitted/runtime hotspot inspection

Source: `ae0bc2ea84484ad341ebc1643e0fa8585e750320`, tree
`3c7189ef17ca43ac8ff2f6c105eee913c2e336b3`. The authoritative 9-repetition
measurement ran on `posidon` (`olfa@10.10.10.12`). An independently built
binary on `olfa@10.10.10.8` corroborated the emitted counts and size.

## Linked binary

- Authoritative binary: 485,696 bytes. Independent binary: 485,648 bytes.
- Ownership callsites: 429 retain-classified and 442 release-classified.
- Direct helper targets in the independent binary: 283 `UpdateHeapRefRelaxed`,
  262 `UpdateReturnRefRelaxed`, 152 `UpdateStackRefRelaxed`, 23
  `ReleaseHeapRefRelaxed`, and one bulk array update.
- Allocation callsites: 194.

These are static linked-binary callsites. The rankings below combine those
callsites with the fixture's exact loop counts to identify runtime-hot traffic.

## 1. Strings

The 600,000-iteration loop retains three out-of-line
`StringBuilder.append(String?)` calls and one out-of-line `append(Int)` call per
iteration. Each retained append body has one `UpdateReturnRefRelaxed` and one
`UpdateStackRefRelaxed` callsite. Consequently the workload can execute roughly
4.8 million ownership updates before capacity growth and destruction traffic.
Strict LTO inlines the String append bodies. This remains the dominant reason
for the measured 19.771% regression.

The rejected callsite `alwaysinline` experiment proved that a Codegen callsite
attribute survives into LLVM IR but is ignored after the stdlib body is linked.
The next attempt must operate after bitcode linking or make the stdlib body
available to a selective inliner.

## 2. Coroutines

The 300,000-iteration coroutine workload retains complete ownership webs across
state-machine joins:

- generated `coroutinesWork` lambda: seven heap updates and three return
  updates;
- `BaseContinuationImpl.resumeWith`: seven stack updates, three return updates,
  and one heap update;
- `SafeContinuation.resumeWith`: three stack updates on the selected path;
- the generated lambda also contains two locked reads, a CAS, a move into the
  return slot, and an allocation.

This matches Swift's motivation for running guaranteed-copy peepholes to a
fixed point, recording every incoming introducer, then converting the complete
owned phi web to guaranteed ownership. The current Kotlin selectors remove
individual known webs but do not yet convert general joined coroutine webs.

## 3. Fields

An additional 25-pair, CPU-pinned, interleaved rerun measured strict at
0.152709240 seconds and ARC at 0.161026288 seconds: a 5.446% latency regression
(94.835% throughput). The normal ARC arithmetic loop is materially shorter
than strict and contains no ownership calls. Every 16,384 iterations, however,
ARC allocates a `Payload`, updates the field, and eagerly destroys the replaced
payload. That slow path executes 12,210 times. The remaining gap is therefore
allocation/final-release traffic rather than field-load/store traffic.

## Compile-time evidence

Median compile time improved from 10.17 seconds to 9.82 seconds (-3.441%). Peak
compiler RSS rose from 562,972 KiB to 571,028 KiB (+1.431%). There is no current
compile-time regression, but every benchmark lane recompiles the same fixture.
The sharded runner builds one candidate/baseline pair per host and measures
disjoint scenario sets concurrently; deterministic merge validates identical
trees, fixtures, flags, counts, and non-overlapping scenarios.
