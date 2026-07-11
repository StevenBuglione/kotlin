#!/usr/bin/env bash
set -euo pipefail

action=$1
repo=$2
source_repo=$3
java_home=$4
workers=$5
min_available_gib=$6
shift 6

fail() {
    echo "ARC remote error: $*" >&2
    exit 1
}

check_java() {
    [[ -x "$java_home/bin/java" ]] || fail "JDK 17 not found at $java_home"
    local version
    version=$("$java_home/bin/java" -version 2>&1 | head -n 1)
    [[ "$version" == *'version "17.'* ]] || fail "ARC requires JDK 17; found $version"
}

check_disk() {
    local probe available required
    probe=$source_repo
    [[ -e "$repo" ]] && probe=$repo
    available=$(df -Pk "$probe" | awk 'NR == 2 { print $4 }')
    required=$((min_available_gib * 1024 * 1024))
    (( available >= required )) || fail "only $((available / 1024 / 1024)) GiB disk space is available; $min_available_gib GiB required"
}

marker_path() {
    git -C "$repo" rev-parse --git-path codex-arc-managed
}

check_managed_clean_repo() {
    git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "$repo is not a Git worktree"
    [[ -f "$(marker_path)" ]] || fail "$repo is not a tools/arc-managed checkout; refusing to touch it"
    [[ -z "$(git -C "$repo" status --porcelain --untracked-files=normal)" ]] || fail "$repo has local changes; refusing to touch it"
}

case "$action" in
    doctor)
        base_ref=$1
        check_java
        command -v git >/dev/null || fail "git is unavailable"
        command -v bash >/dev/null || fail "bash is unavailable"
        command -v cmake >/dev/null || fail "cmake is unavailable"
        command -v ninja >/dev/null || fail "ninja is unavailable"
        git -C "$source_repo" rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "$source_repo is not the shared source repository"
        git -C "$source_repo" rev-parse --verify "$base_ref^{commit}" >/dev/null 2>&1 || fail "$source_repo has no $base_ref commit"
        check_disk
        echo "remote=$(hostname) java=17 workers=$workers disk_gib=$(($(df -Pk "$source_repo" | awk 'NR == 2 { print $4 }') / 1024 / 1024)) ram_available_gib=$(($(awk '/^MemAvailable:/ { print $2 }' /proc/meminfo) / 1024 / 1024))"
        ;;
    init)
        base_ref=$1
        check_java
        if [[ -e "$repo" ]]; then
            check_managed_clean_repo
        else
            git -C "$source_repo" rev-parse --verify "$base_ref^{commit}" >/dev/null 2>&1 || fail "$source_repo has no $base_ref commit"
            git -C "$source_repo" worktree add --detach "$repo" "$base_ref"
            touch "$(marker_path)"
        fi
        check_disk
        ;;
    checkout)
        reference=$1
        expected=$2
        check_managed_clean_repo
        actual=$(git -C "$repo" rev-parse "$reference^{commit}")
        [[ "$actual" == "$expected" ]] || fail "snapshot ref resolved to $actual, expected $expected"
        git -C "$repo" checkout --detach "$expected"
        check_managed_clean_repo
        ;;
    run)
        check_java
        check_disk
        check_managed_clean_repo
        (( workers >= 1 && workers <= 28 )) || fail "worker count must be between 1 and 28"
        [[ $# -gt 0 ]] || fail "no build command supplied"
        export JAVA_HOME="$java_home"
        export PATH="$JAVA_HOME/bin:$PATH"
        cd "$repo"
        exec "$@"
        ;;
    *)
        fail "unknown action: $action"
        ;;
esac
