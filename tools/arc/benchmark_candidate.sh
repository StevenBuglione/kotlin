#!/usr/bin/env bash
set -euo pipefail

root=$(git rev-parse --show-toplevel)
state=${ARC_RUN_STATE_DIR:?ARC_RUN_STATE_DIR is set by the durable remote runner}
workers=${ARC_BENCH_BUILD_WORKERS:-8}
dist=${ARC_DIST_DIR:-$root/kotlin-native/dist}
rebuild=${ARC_BENCH_REBUILD_CANDIDATE:-0}
quick=${ARC_BENCH_QUICK:-0}
cache_tool="$root/tools/arc/benchmark_cache.py"
common_dir=$(git -C "$root" rev-parse --path-format=absolute --git-common-dir)
content_cache_root=${ARC_BENCH_CANDIDATE_CACHE_ROOT:-$common_dir/codex-arc-benchmark-cache-v2}
pointer_dir="$root/.arc-runs/benchmark-cache"

[[ "$workers" =~ ^[0-9]+$ && $workers -ge 1 && $workers -le 28 ]] || {
    echo "ARC_BENCH_BUILD_WORKERS must be between 1 and 28" >&2
    exit 2
}
[[ "$rebuild" == 0 || "$rebuild" == 1 ]] || {
    echo "ARC_BENCH_REBUILD_CANDIDATE must be 0 or 1" >&2
    exit 2
}
[[ "$quick" == 0 || "$quick" == 1 ]] || {
    echo "ARC_BENCH_QUICK must be 0 or 1" >&2
    exit 2
}
[[ "$content_cache_root" = /* ]] || {
    echo "ARC_BENCH_CANDIDATE_CACHE_ROOT must be absolute" >&2
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
content_key=$(python3 "$cache_tool" candidate-key "$head" "$tree" "$root")
content_dist="$content_cache_root/candidate/$content_key/dist"
content_manifest="$content_cache_root/candidate/$content_key/manifest.json"
provenance="$dist/.arc-benchmark-provenance.json"
cache_manifest="$dist/.arc-benchmark-candidate-cache.json"
# Preparation never publishes evidence. It verifies exact build identity and sealed
# launchers without hashing the whole distribution; benchmark_compare.sh performs the
# independent full-byte validation immediately before every evidence measurement.
validation=validate-content-candidate-fast
legacy_validation=validate-candidate-fast

publish_run_identity() {
    local selected_dist=$1
    mkdir -p "$state/artifacts" "$pointer_dir"
    printf '{"role":"candidate","commit":"%s","tree":"%s","source":"%s"}\n' \
        "$head" "$tree" "$root" >"$pointer_dir/candidate-provenance.json.tmp"
    mv "$pointer_dir/candidate-provenance.json.tmp" "$pointer_dir/candidate-provenance.json"
    cp "$pointer_dir/candidate-provenance.json" "$state/artifacts/candidate-provenance.json"
    printf '%s\n' "$selected_dist" >"$pointer_dir/candidate-dist.tmp"
    mv "$pointer_dir/candidate-dist.tmp" "$pointer_dir/candidate-dist"
}
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
if [[ "$rebuild" == 0 && -x "$content_dist/bin/konanc" && -x "$content_dist/bin/cinterop" ]] &&
        python3 "$cache_tool" "$validation" "$content_manifest" "$content_dist" \
            "$head" "$tree" "$root"; then
    publish_run_identity "$content_dist"
    echo "ARC_BENCH_CANDIDATE_CONTENT_CACHE_HIT key=$content_key commit=$head tree=$tree dist=$content_dist"
    exit 0
fi

if [[ "$rebuild" == 0 && -x "$dist/bin/konanc" && -x "$dist/bin/cinterop" ]] &&
        valid_provenance &&
        python3 "$cache_tool" "$legacy_validation" \
            "$cache_manifest" "$dist" "$head" "$tree" "$root"; then
    cached_dist=$(python3 "$cache_tool" publish-content-candidate \
        "$content_cache_root" "$dist" "$head" "$tree" "$root")
    publish_run_identity "$cached_dist"
    echo "ARC_BENCH_CANDIDATE_CACHE_HIT promotedKey=$content_key commit=$head tree=$tree dist=$cached_dist"
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
cached_dist=$(python3 "$cache_tool" publish-content-candidate \
    "$content_cache_root" "$dist" "$head" "$tree" "$root")
selected_dist=$cached_dist
[[ "$rebuild" == 1 ]] && selected_dist=$dist
publish_run_identity "$selected_dist"
echo "ARC_BENCH_CANDIDATE_READY cache=refreshed key=$content_key commit=$head tree=$tree dist=$selected_dist"
