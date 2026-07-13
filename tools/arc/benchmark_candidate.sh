#!/usr/bin/env bash
set -euo pipefail

root=$(git rev-parse --show-toplevel)
state=${ARC_RUN_STATE_DIR:?ARC_RUN_STATE_DIR is set by the durable remote runner}
workers=${ARC_BENCH_BUILD_WORKERS:-8}
dist=${ARC_DIST_DIR:-$root/kotlin-native/dist}
rebuild=${ARC_BENCH_REBUILD_CANDIDATE:-0}
cache_tool="$root/tools/arc/benchmark_cache.py"

[[ "$workers" =~ ^[0-9]+$ && $workers -ge 1 && $workers -le 28 ]] || {
    echo "ARC_BENCH_BUILD_WORKERS must be between 1 and 28" >&2
    exit 2
}
[[ "$rebuild" == 0 || "$rebuild" == 1 ]] || {
    echo "ARC_BENCH_REBUILD_CANDIDATE must be 0 or 1" >&2
    exit 2
}
command -v python3 >/dev/null || { echo "python3 is required for candidate cache validation" >&2; exit 1; }
[[ -f "$cache_tool" ]] || { echo "candidate cache validator is missing: $cache_tool" >&2; exit 1; }
[[ -z "$(git -C "$root" status --porcelain --untracked-files=normal -- . ':(exclude).arc-runs' ':(exclude).arc-runs/**')" ]] || {
    echo "candidate checkout is dirty; refusing to stamp a benchmark distribution" >&2
    exit 1
}

head=$(git -C "$root" rev-parse HEAD)
tree=$(git -C "$root" rev-parse HEAD^{tree})
provenance="$dist/.arc-benchmark-provenance.json"
cache_manifest="$dist/.arc-benchmark-candidate-cache.json"
valid_provenance() {
    python3 - "$provenance" "$head" "$tree" "$root" <<'PY'
import json
import pathlib
import sys

path, commit, tree, source = sys.argv[1:]
try:
    value = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
except (OSError, ValueError):
    raise SystemExit(1)
expected = ("candidate", commit, tree, source)
actual = tuple(value.get(key) for key in ("role", "commit", "tree", "source"))
raise SystemExit(actual != expected)
PY
}

mkdir -p "$state/artifacts"
if [[ "$rebuild" == 0 && -x "$dist/bin/konanc" && -x "$dist/bin/cinterop" ]] &&
        valid_provenance &&
        python3 "$cache_tool" validate-candidate "$cache_manifest" "$dist" "$head" "$tree" "$root"; then
    cp "$provenance" "$state/artifacts/candidate-provenance.json"
    echo "ARC_BENCH_CANDIDATE_CACHE_HIT commit=$head tree=$tree dist=$dist"
    exit 0
fi

echo "ARC_BENCH_CANDIDATE_CACHE_MISS rebuild=$rebuild commit=$head tree=$tree"
"$root/gradlew" -Pkotlin.native.enabled=true --max-workers="$workers" --no-daemon \
    :kotlin-native:dist :kotlin-native:distPlatformLibs
[[ -x "$dist/bin/konanc" ]] || { echo "candidate distribution did not produce bin/konanc" >&2; exit 1; }
[[ -z "$(git -C "$root" status --porcelain --untracked-files=normal -- . ':(exclude).arc-runs' ':(exclude).arc-runs/**')" ]] || {
    echo "candidate build modified tracked or unignored source files" >&2
    exit 1
}

printf '{"role":"candidate","commit":"%s","tree":"%s","source":"%s"}\n' \
    "$head" "$tree" "$root" | tee "$state/artifacts/candidate-provenance.json" >"$provenance"
python3 "$cache_tool" write-candidate "$cache_manifest" "$dist" "$head" "$tree" "$root"
echo "ARC_BENCH_CANDIDATE_READY cache=refreshed commit=$head tree=$tree dist=$dist"
