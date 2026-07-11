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

remote-arc-sanitize: remote-snapshot
    {{python}} tools/arc/arc.py run arc-sanitize

remote-arc-bench: remote-snapshot
    {{python}} tools/arc/arc.py run arc-bench

remote-status profile:
    {{python}} tools/arc/arc.py status {{profile}}

remote-log profile:
    {{python}} tools/arc/arc.py log {{profile}}
