#!/usr/bin/env bash
set -euo pipefail

root=$(git rev-parse --show-toplevel)
state=${ARC_RUN_STATE_DIR:?ARC_RUN_STATE_DIR is set by the durable remote runner}
workers=${ARC_BENCH_BUILD_WORKERS:-8}
dist=${ARC_DIST_DIR:-$root/kotlin-native/dist}

[[ "$workers" =~ ^[0-9]+$ && $workers -ge 1 && $workers -le 28 ]] || {
    echo "ARC_BENCH_BUILD_WORKERS must be between 1 and 28" >&2
    exit 2
}
[[ -z "$(git -C "$root" status --porcelain --untracked-files=normal -- . ':(exclude).arc-runs' ':(exclude).arc-runs/**')" ]] || {
    echo "candidate checkout is dirty; refusing to stamp a benchmark distribution" >&2
    exit 1
}

head=$(git -C "$root" rev-parse HEAD)
tree=$(git -C "$root" rev-parse HEAD^{tree})
"$root/gradlew" -Pkotlin.native.enabled=true --max-workers="$workers" --no-daemon \
    :kotlin-native:dist :kotlin-native:distPlatformLibs
[[ -x "$dist/bin/konanc" ]] || { echo "candidate distribution did not produce bin/konanc" >&2; exit 1; }
[[ -z "$(git -C "$root" status --porcelain --untracked-files=normal -- . ':(exclude).arc-runs' ':(exclude).arc-runs/**')" ]] || {
    echo "candidate build modified tracked or unignored source files" >&2
    exit 1
}

mkdir -p "$state/artifacts"
printf '{"role":"candidate","commit":"%s","tree":"%s","source":"%s"}\n' \
    "$head" "$tree" "$root" | tee "$state/artifacts/candidate-provenance.json" >"$dist/.arc-benchmark-provenance.json"
echo "ARC_BENCH_CANDIDATE_READY commit=$head tree=$tree dist=$dist"
