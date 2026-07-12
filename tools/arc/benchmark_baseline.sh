#!/usr/bin/env bash
set -euo pipefail

root=$(git rev-parse --show-toplevel)
state=${ARC_RUN_STATE_DIR:?ARC_RUN_STATE_DIR is set by the durable remote runner}
expected=3db61efe5e892bf27115f1ebcab957d903067ed4
baseline=${ARC_BENCH_BASELINE_SOURCE:-${root}-baseline-v1.9.10}
dist=${ARC_BENCH_BASELINE_DIST:-$baseline/kotlin-native/dist}
workers=${ARC_BENCH_BUILD_WORKERS:-8}
rebuild=${ARC_BENCH_REBUILD_BASELINE:-0}
cache_tool="$root/tools/arc/benchmark_cache.py"

[[ "$workers" =~ ^[0-9]+$ && $workers -ge 1 && $workers -le 28 ]] || {
    echo "ARC_BENCH_BUILD_WORKERS must be between 1 and 28" >&2
    exit 2
}
[[ "$rebuild" == 0 || "$rebuild" == 1 ]] || {
    echo "ARC_BENCH_REBUILD_BASELINE must be 0 or 1" >&2
    exit 2
}
command -v python3 >/dev/null || { echo "python3 is required for baseline cache validation" >&2; exit 1; }
[[ -f "$cache_tool" ]] || { echo "baseline cache validator is missing: $cache_tool" >&2; exit 1; }
[[ "$baseline" = /* && "$dist" = /* ]] || { echo "baseline source and dist paths must be absolute" >&2; exit 2; }
[[ "$baseline" != "$root" ]] || { echo "baseline checkout must be separate from the candidate" >&2; exit 1; }
git -C "$root" cat-file -e "$expected^{commit}" 2>/dev/null || {
    echo "exact v1.9.10 baseline commit $expected is unavailable; transfer/fetch it before provisioning" >&2
    exit 1
}

if [[ ! -e "$baseline" ]]; then
    git -C "$root" worktree add --detach "$baseline" "$expected"
    touch "$(git -C "$baseline" rev-parse --absolute-git-dir)/codex-arc-benchmark-baseline-v1.9.10"
fi
git -C "$baseline" rev-parse --is-inside-work-tree >/dev/null 2>&1 || {
    echo "$baseline exists but is not a Git worktree" >&2
    exit 1
}
marker="$(git -C "$baseline" rev-parse --absolute-git-dir)/codex-arc-benchmark-baseline-v1.9.10"
[[ -f "$marker" ]] || { echo "$baseline is not a tools/arc-managed benchmark baseline; refusing to use it" >&2; exit 1; }
actual=$(git -C "$baseline" rev-parse HEAD)
[[ "$actual" == "$expected" ]] || { echo "baseline HEAD is $actual, expected exact v1.9.10 $expected" >&2; exit 1; }
[[ -z "$(git -C "$baseline" status --porcelain --untracked-files=normal)" ]] || {
    echo "baseline checkout is dirty; refusing to build an untrusted strict distribution" >&2
    exit 1
}

tree=$(git -C "$baseline" rev-parse HEAD^{tree})
provenance="$dist/.arc-benchmark-provenance.json"
cache_manifest="$dist/.arc-benchmark-baseline-cache.json"
valid_provenance() {
    python3 - "$provenance" "$actual" "$tree" "$baseline" <<'PY'
import json
import pathlib
import sys

path, commit, tree, source = sys.argv[1:]
try:
    value = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
except (OSError, ValueError):
    raise SystemExit(1)
expected = ("baseline-strict", "v1.9.10", commit, tree, source)
actual = tuple(value.get(key) for key in ("role", "tag", "commit", "tree", "source"))
raise SystemExit(actual != expected)
PY
}

mkdir -p "$state/artifacts"
if [[ "$rebuild" == 0 && -x "$dist/bin/konanc" && -x "$dist/bin/cinterop" ]] &&
        valid_provenance &&
        python3 "$cache_tool" validate "$cache_manifest" "$dist" "$actual" "$tree" "$baseline"; then
    cp "$provenance" "$state/artifacts/baseline-provenance.json"
    echo "ARC_BENCH_BASELINE_CACHE_HIT tag=v1.9.10 commit=$actual tree=$tree dist=$dist"
    exit 0
fi

echo "ARC_BENCH_BASELINE_CACHE_MISS rebuild=$rebuild tag=v1.9.10 commit=$actual tree=$tree"
"$baseline/gradlew" -p "$baseline" -Pkotlin.native.enabled=true --max-workers="$workers" --no-daemon \
    :kotlin-native:dist :kotlin-native:distPlatformLibs
[[ -x "$dist/bin/konanc" ]] || { echo "baseline distribution did not produce bin/konanc" >&2; exit 1; }
[[ -z "$(git -C "$baseline" status --porcelain --untracked-files=normal)" ]] || {
    echo "baseline build modified tracked or unignored source files" >&2
    exit 1
}

printf '{"role":"baseline-strict","tag":"v1.9.10","commit":"%s","tree":"%s","source":"%s"}\n' \
    "$actual" "$tree" "$baseline" | tee "$state/artifacts/baseline-provenance.json" >"$provenance"
python3 "$cache_tool" write "$cache_manifest" "$dist" "$actual" "$tree" "$baseline"
echo "ARC_BENCH_BASELINE_READY cache=refreshed tag=v1.9.10 commit=$actual tree=$tree dist=$dist"
