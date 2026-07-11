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

remote-arc-bench: remote-snapshot
    {{python}} tools/arc/arc.py run arc-bench

remote-status profile:
    {{python}} tools/arc/arc.py status {{profile}}

remote-log profile:
    {{python}} tools/arc/arc.py log {{profile}}
