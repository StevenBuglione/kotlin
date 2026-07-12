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

remote-arc-sanitize: remote-snapshot
    {{python}} tools/arc/arc.py run arc-sanitize

remote-arc-sanitize-asan: remote-snapshot
    {{python}} tools/arc/arc.py run arc-sanitize-asan

remote-arc-sanitize-ubsan: remote-snapshot
    {{python}} tools/arc/arc.py run arc-sanitize-ubsan

remote-arc-sanitize-tsan: remote-snapshot
    {{python}} tools/arc/arc.py run arc-sanitize-tsan

remote-arc-bench wave: remote-snapshot
    {{python}} tools/arc/arc.py run arc-bench-candidate
    {{python}} tools/arc/arc.py run arc-bench-baseline
    status=0; {{python}} tools/arc/arc.py run arc-bench || status=$?; {{python}} tools/arc/arc.py benchmark-bundle {{wave}}; exit "$status"

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

ci2-bench-doctor:
    {{python}} tools/arc/arc.py --machine ci2-bench doctor

ci2-bench-init:
    {{python}} tools/arc/arc.py --machine ci2-bench remote-init

ci2-bench-snapshot: ci2-bench-init
    {{python}} tools/arc/arc.py --machine ci2-bench remote-snapshot

ci2-arc-bench wave: ci2-bench-snapshot
    {{python}} tools/arc/arc.py --machine ci2-bench run arc-bench-candidate
    {{python}} tools/arc/arc.py --machine ci2-bench run arc-bench-baseline
    status=0; {{python}} tools/arc/arc.py --machine ci2-bench run arc-bench || status=$?; {{python}} tools/arc/arc.py --machine ci2-bench benchmark-bundle {{wave}}; exit "$status"

ci2-bench-status profile:
    {{python}} tools/arc/arc.py --machine ci2-bench status {{profile}}

ci2-bench-log profile:
    {{python}} tools/arc/arc.py --machine ci2-bench log {{profile}}

ci2-status profile:
    {{python}} tools/arc/arc.py --machine ci2 status {{profile}}

ci2-log profile:
    {{python}} tools/arc/arc.py --machine ci2 log {{profile}}
