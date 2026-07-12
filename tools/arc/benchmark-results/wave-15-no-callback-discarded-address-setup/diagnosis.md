# Discarded platform-c-leaf address-setup measurement

This wave is retained as diagnostic evidence and is not an authoritative measurement of the
no-callback C-call boundary.

The original fixture evaluated `pinned.addressOf(0)` inside all 20 million loop iterations. Linked
disassembly showed that both strict and ARC consequently called `Kotlin_initRuntimeIfNeeded` on
every iteration before the direct `arc_benchmark_strlen_ptr` call. The candidate ARC implementation
of that runtime setup path was also materially larger (`0x38e` bytes versus strict's `0x271`). The
measured ARC median of 0.406767 seconds versus strict's 0.157847 seconds therefore combined repeated
pin/address runtime setup with the foreign call and reported a misleading 157.697% regression.

The corrected fixture resolves the stable pinned address once before entering the hot loop while
retaining the runtime-mutated terminator and the same deterministic checksum. The nine-repetition
corrected result is stored in `wave-15-no-callback-corrected`: strict 0.149688 seconds versus ARC
0.149369 seconds, or a 0.214% ARC latency improvement, with identical output and 8.696% lower ARC
peak RSS.
