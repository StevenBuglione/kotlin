#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
tmp=$(mktemp -d)
cleanup() {
    [[ -n "$tmp" && "$tmp" == /tmp/tmp.* ]] || return 1
    rm -rf -- "$tmp"
}
trap cleanup EXIT

repo="$tmp/worktree"
mkdir -p "$repo"
git -C "$repo" init -q
git -C "$repo" config user.email arc-test@example.invalid
git -C "$repo" config user.name arc-test
git -C "$repo" config commit.gpgsign false
echo baseline >"$repo/baseline"
git -C "$repo" add baseline
git -C "$repo" commit -qm baseline
touch "$(git -C "$repo" rev-parse --absolute-git-dir)/codex-arc-managed"

invoke() {
    bash "$root/tools/arc/remote.sh" "$1" "$repo" "$repo" "$tmp/jdk17" 1 0 "${@:2}"
}

[[ "$(invoke status arc-smoke)" == 'profile=arc-smoke state=not-started' ]]

state="$repo/.arc-runs/arc-smoke"
mkdir -p "$state"
echo 123 >"$state/pid"
echo 2026-01-01T00:00:00Z >"$state/started-at"
echo 0 >"$state/exit-status"
echo durable-log >"$state/build.log"

invoke status arc-smoke | grep -q 'profile=arc-smoke state=finished pid=123 exit=0'
[[ "$(invoke log arc-smoke)" == durable-log ]]
[[ -z "$(git -C "$repo" status --porcelain --untracked-files=normal -- . ':(exclude).arc-runs' ':(exclude).arc-runs/**')" ]]

mkdir -p "$tmp/jdk17/bin"
cat >"$tmp/jdk17/bin/java" <<'EOF'
#!/usr/bin/env bash
echo 'openjdk version "17.0.1"' >&2
EOF
chmod +x "$tmp/jdk17/bin/java"

invoke start durable-test bash -c 'echo started; sleep 1; echo completed'
invoke status durable-test | grep -q 'profile=durable-test state=running'
invoke follow durable-test | grep -q completed
invoke status durable-test | grep -q 'profile=durable-test state=finished.*exit=0'
[[ "$(invoke log durable-test)" == $'started\ncompleted' ]]

head=$(git -C "$repo" rev-parse HEAD)
invoke checkout HEAD "$head"
[[ "$(git -C "$repo" rev-parse HEAD)" == "$head" ]]

fixture_state="$tmp/fixture-state"
mkdir -p "$fixture_state"
if (cd "$repo" && ARC_RUN_STATE_DIR="$fixture_state" ARC_DIST_DIR="$tmp/missing-dist" \
    bash "$root/tools/arc/run_fixture.sh" smoke) >"$tmp/fixture-error" 2>&1; then
    echo "fixture unexpectedly accepted a missing distribution" >&2
    exit 1
fi
grep -q 'run remote-dist first' "$tmp/fixture-error"

echo 'remote state self-test passed'
