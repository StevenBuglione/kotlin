set shell := ["sh", "-cu"]
set windows-shell := ["powershell.exe", "-NoLogo", "-NoProfile", "-Command"]

python := env_var_or_default("ARC_PYTHON", "python")

doctor:
    {{python}} tools/arc/arc.py doctor

remote-init:
    {{python}} tools/arc/arc.py remote-init

remote-snapshot: remote-init
    {{python}} tools/arc/arc.py remote-snapshot

remote-dist: remote-snapshot
    {{python}} tools/arc/arc.py run dist

remote-runtime: remote-snapshot
    {{python}} tools/arc/arc.py run runtime

remote-sanity: remote-snapshot
    {{python}} tools/arc/arc.py run sanity

remote-full: remote-snapshot
    {{python}} tools/arc/arc.py run full

remote-arc-smoke: remote-snapshot
    {{python}} tools/arc/arc.py run arc-smoke

remote-arc-stress: remote-snapshot
    {{python}} tools/arc/arc.py run arc-stress

remote-arc-race: remote-snapshot
    {{python}} tools/arc/arc.py run arc-race

remote-arc-race-tsan: remote-snapshot
    {{python}} tools/arc/arc.py run arc-race-tsan

remote-arc-unowned-death: remote-snapshot
    {{python}} tools/arc/arc.py run arc-unowned-death

remote-arc-no-collector: remote-snapshot
    {{python}} tools/arc/arc.py run arc-no-collector

remote-arc-frame-elision-unit: remote-snapshot
    {{python}} tools/arc/arc.py run arc-frame-elision-unit

remote-arc-return-update-coalescing-unit: remote-snapshot
    {{python}} tools/arc/arc.py run arc-return-update-coalescing-unit

remote-arc-rooted-loop: remote-snapshot
    {{python}} tools/arc/arc.py run arc-rooted-loop

remote-arc-deinit-synthetic-root: remote-snapshot
    {{python}} tools/arc/arc.py run arc-deinit-synthetic-root

remote-arc-sanitize: remote-snapshot
    {{python}} tools/arc/arc.py run arc-sanitize

remote-arc-sanitize-asan: remote-snapshot
    {{python}} tools/arc/arc.py run arc-sanitize-asan

remote-arc-sanitize-ubsan: remote-snapshot
    {{python}} tools/arc/arc.py run arc-sanitize-ubsan

remote-arc-sanitize-tsan: remote-snapshot
    {{python}} tools/arc/arc.py run arc-sanitize-tsan

remote-arc-bench wave: remote-snapshot
    {{python}} tools/arc/arc.py benchmark-wave {{wave}}

remote-status profile:
    {{python}} tools/arc/arc.py status {{profile}}

remote-log profile:
    {{python}} tools/arc/arc.py log {{profile}}

# Independent secondary Linux builder (olfa@10.10.10.12).
ci2-doctor:
    {{python}} tools/arc/arc.py --machine ci2 doctor

ci2-init:
    {{python}} tools/arc/arc.py --machine ci2 remote-init

ci2-snapshot: ci2-init
    {{python}} tools/arc/arc.py --machine ci2 remote-snapshot

ci2-run profile: ci2-snapshot
    {{python}} tools/arc/arc.py --machine ci2 run {{profile}}

ci2-dist: ci2-snapshot
    {{python}} tools/arc/arc.py --machine ci2 run dist

ci2-runtime: ci2-snapshot
    {{python}} tools/arc/arc.py --machine ci2 run runtime

ci2-sanity: ci2-snapshot
    {{python}} tools/arc/arc.py --machine ci2 run sanity

ci2-full: ci2-snapshot
    {{python}} tools/arc/arc.py --machine ci2 run full

ci2-arc-frame-elision-unit: ci2-snapshot
    {{python}} tools/arc/arc.py --machine ci2 run arc-frame-elision-unit

ci2-arc-return-update-coalescing-unit: ci2-snapshot
    {{python}} tools/arc/arc.py --machine ci2 run arc-return-update-coalescing-unit

ci2-arc-field-projection: ci2-snapshot
    {{python}} tools/arc/arc.py --machine ci2 run arc-field-projection

ci2-arc-rooted-loop: ci2-snapshot
    {{python}} tools/arc/arc.py --machine ci2 run arc-rooted-loop

ci2-arc-deinit-synthetic-root: ci2-snapshot
    {{python}} tools/arc/arc.py --machine ci2 run arc-deinit-synthetic-root

ci2-bench-doctor:
    {{python}} tools/arc/arc.py --machine ci2-bench doctor

ci2-bench-init:
    {{python}} tools/arc/arc.py --machine ci2-bench remote-init

ci2-bench-snapshot: ci2-bench-init
    {{python}} tools/arc/arc.py --machine ci2-bench remote-snapshot

ci2-arc-bench wave: ci2-bench-snapshot
    {{python}} tools/arc/arc.py --machine ci2-bench benchmark-wave {{wave}}

# Fast development oracle: cached distributions, one compile, three pinned runs, no warmup,
# and no release-gate failure. One remote profile holds the host lock for the complete run.
# Full `ci2-arc-bench` remains the only evidence-producing benchmark recipe.
ci2-arc-bench-quick scenarios: ci2-bench-snapshot
    {{python}} tools/arc/arc.py --machine ci2-bench run arc-bench-quick --quick --scenarios "{{scenarios}}"

ci2-bench-status profile:
    {{python}} tools/arc/arc.py --machine ci2-bench status {{profile}}

ci2-bench-log profile:
    {{python}} tools/arc/arc.py --machine ci2-bench log {{profile}}

ci2-status profile:
    {{python}} tools/arc/arc.py --machine ci2 status {{profile}}

ci2-log profile:
    {{python}} tools/arc/arc.py --machine ci2 log {{profile}}

# Run disjoint same-host A/B shards concurrently on the two benchmark machines, then download
# each bundle and merge them with tools/arc/benchmark_shards.py.
primary-arc-bench-shard shard count scenarios:
    {{python}} tools/arc/arc.py --machine primary-bench remote-init
    {{python}} tools/arc/arc.py --machine primary-bench remote-snapshot --paths Justfile tools/arc/arc.py tools/arc/benchmark_baseline.sh tools/arc/benchmark_candidate.sh tools/arc/benchmark_cache.py tools/arc/benchmark_compare.sh tools/arc/benchmark_plan.py tools/arc/benchmark_report.py tools/arc/benchmark_shards.py tools/arc/fixtures/benchmark.kt tools/arc/fixtures/benchmark_cinterop.def tools/arc/fixtures/benchmark_cinterop.h tools/arc/test_arc.py
    {{python}} tools/arc/arc.py --machine primary-bench run arc-bench-candidate --shard-id "{{shard}}" --shard-count {{count}} --scenarios "{{scenarios}}"
    {{python}} tools/arc/arc.py --machine primary-bench run arc-bench-baseline --shard-id "{{shard}}" --shard-count {{count}} --scenarios "{{scenarios}}"
    {{python}} tools/arc/arc.py --machine primary-bench run arc-bench --shard-id "{{shard}}" --shard-count {{count}} --scenarios "{{scenarios}}"

ci2-arc-bench-shard shard count scenarios:
    {{python}} tools/arc/arc.py --machine ci2-cstring remote-init
    {{python}} tools/arc/arc.py --machine ci2-cstring remote-snapshot --paths Justfile tools/arc/arc.py tools/arc/benchmark_baseline.sh tools/arc/benchmark_candidate.sh tools/arc/benchmark_cache.py tools/arc/benchmark_compare.sh tools/arc/benchmark_plan.py tools/arc/benchmark_report.py tools/arc/benchmark_shards.py tools/arc/fixtures/benchmark.kt tools/arc/fixtures/benchmark_cinterop.def tools/arc/fixtures/benchmark_cinterop.h tools/arc/test_arc.py
    {{python}} tools/arc/arc.py --machine ci2-cstring run arc-bench-candidate --shard-id "{{shard}}" --shard-count {{count}} --scenarios "{{scenarios}}"
    {{python}} tools/arc/arc.py --machine ci2-cstring run arc-bench-baseline --shard-id "{{shard}}" --shard-count {{count}} --scenarios "{{scenarios}}"
    {{python}} tools/arc/arc.py --machine ci2-cstring run arc-bench --shard-id "{{shard}}" --shard-count {{count}} --scenarios "{{scenarios}}"

# Backward-compatible explicit assignment for hardware-specific investigations.
parallel-arc-bench wave primary_scenarios secondary_scenarios:
    {{python}} tools/arc/benchmark_parallel.py {{wave}} --primary-scenarios "{{primary_scenarios}}" --secondary-scenarios "{{secondary_scenarios}}"

parallel-arc-bench-dry-run wave primary_scenarios secondary_scenarios:
    {{python}} tools/arc/benchmark_parallel.py {{wave}} --primary-scenarios "{{primary_scenarios}}" --secondary-scenarios "{{secondary_scenarios}}" --dry-run

# Automatic minimax assignment across both Linux benchmark hosts.
parallel-arc-bench-auto wave scenarios:
    {{python}} tools/arc/benchmark_parallel.py {{wave}} --scenarios "{{scenarios}}"

parallel-arc-bench-auto-dry-run wave scenarios:
    {{python}} tools/arc/benchmark_parallel.py {{wave}} --scenarios "{{scenarios}}" --dry-run

# Dedicated parallel lanes. These never reset the compiler or benchmark worktrees.
ci2-runtime-init:
    {{python}} tools/arc/arc.py --machine ci2-runtime remote-init

ci2-runtime-snapshot: ci2-runtime-init
    {{python}} tools/arc/arc.py --machine ci2-runtime remote-snapshot

ci2-runtime-run profile: ci2-runtime-snapshot
    {{python}} tools/arc/arc.py --machine ci2-runtime run {{profile}}

ci2-runtime-status profile:
    {{python}} tools/arc/arc.py --machine ci2-runtime status {{profile}}

ssa-init:
    {{python}} tools/arc/arc.py --machine primary-ssa remote-init

ssa-snapshot: ssa-init
    {{python}} tools/arc/arc.py --machine primary-ssa remote-snapshot

ssa-run profile: ssa-snapshot
    {{python}} tools/arc/arc.py --machine primary-ssa run {{profile}}

ssa-status profile:
    {{python}} tools/arc/arc.py --machine primary-ssa status {{profile}}

interop-init:
    {{python}} tools/arc/arc.py --machine primary-interop remote-init

interop-snapshot: interop-init
    {{python}} tools/arc/arc.py --machine primary-interop remote-snapshot

interop-run profile: interop-snapshot
    {{python}} tools/arc/arc.py --machine primary-interop run {{profile}}

interop-status profile:
    {{python}} tools/arc/arc.py --machine primary-interop status {{profile}}
