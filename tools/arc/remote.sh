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

validate_managed_paths() {
    local resolved_repo resolved_source
    resolved_repo=$(readlink -m -- "$repo")
    resolved_source=$(readlink -m -- "$source_repo")
    case "$resolved_repo" in
        /home/olfa/codex-kotlin-arc|\
        /home/olfa/codex-kotlin-arc-primary-bench|\
        /home/olfa/codex-kotlin-arc-ci2|\
        /home/olfa/codex-kotlin-arc-ci2-bench|\
        /home/olfa/codex-kotlin-arc-ci2-runtime|\
        /home/olfa/codex-kotlin-arc-ssa|\
        /home/olfa/codex-kotlin-arc-interop)
            ;;
        *)
            fail "resolved checkout path $resolved_repo is not an approved ARC worktree"
            ;;
    esac
    [[ "$resolved_source" == /home/olfa/codex-kotlin-rust ]] ||
        fail "resolved source repository $resolved_source is not the approved shared object store"
    [[ "$resolved_repo" != "$resolved_source" ]] ||
        fail "managed checkout and shared source repository must be distinct"
}

validate_managed_paths

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

host_lock_path() {
    local common_dir
    common_dir=$(git -C "$source_repo" rev-parse --path-format=absolute --git-common-dir) ||
        fail "$source_repo has no Git common directory for host locking"
    printf '%s/codex-arc-host.lock\n' "$common_dir"
}

acquire_shared_host_lock() {
    local lock
    lock=$(host_lock_path)
    exec 9>"$lock"
    flock -s 9
}

marker_path() {
    printf '%s/codex-arc-managed\n' "$(git -C "$repo" rev-parse --absolute-git-dir)"
}

check_managed_repo() {
    git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "$repo is not a Git worktree"
    [[ -f "$(marker_path)" ]] || fail "$repo is not a tools/arc-managed checkout; refusing to touch it"
    [[ -z "$(git -C "$repo" ls-files -- .arc-runs)" ]] || fail "$repo has a tracked .arc-runs path; refusing unsafe state access"
}

check_managed_clean_repo() {
    check_managed_repo
    [[ -z "$(git -C "$repo" status --porcelain --untracked-files=normal -- . ':(exclude).arc-runs' ':(exclude).arc-runs/**')" ]] ||
        fail "$repo has local changes; refusing to touch it"
}

validate_profile() {
    [[ "$1" =~ ^[a-z0-9][a-z0-9-]*$ ]] || fail "invalid build profile: $1"
}

profile_dir() {
    validate_profile "$1"
    printf '%s/.arc-runs/%s\n' "$repo" "$1"
}

profile_is_running() {
    local state pid
    state=$(profile_dir "$1")
    [[ -f "$state/pid" && ! -f "$state/exit-status" ]] || return 1
    pid=$(cat "$state/pid")
    [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null
}

canonical_command() {
    # Persist a shell-escaped, single-line representation so repeated starts can
    # prove they refer to the exact same argv, including environment assignments.
    printf '%q ' "$@"
}

check_no_active_runs() {
    local state profile
    [[ -d "$repo/.arc-runs" ]] || return 0
    for state in "$repo/.arc-runs"/*; do
        [[ -d "$state" ]] || continue
        profile=${state##*/}
        profile_is_running "$profile" && fail "build profile $profile is still running; refusing to change its checkout"
    done
    return 0
}

write_runner() {
    local state=$1 log=$2 status=$3 runner=$4 profile=$5
    local lock mode argument quick_benchmark=0
    shift 5
    lock=$(host_lock_path)
    # Only the measurement/comparison phase is exclusive. Candidate and baseline
    # distribution preparation collect no timing data and remain ordinary shared jobs.
    # Quick diagnostics are deliberately non-evidence-producing, so allowing them to
    # share a host is more useful than queueing them behind a long correctness suite.
    for argument in "$@"; do
        [[ "$argument" == ARC_BENCH_QUICK=1 ]] && quick_benchmark=1
    done
    mode=-s
    [[ "$profile" == arc-bench && $quick_benchmark -eq 0 ]] && mode=-x
    {
        echo '#!/usr/bin/env bash'
        echo 'set +e'
        printf 'cd %q\n' "$repo"
        printf 'export JAVA_HOME=%q\n' "$java_home"
        printf 'export PATH=%q:$PATH\n' "$java_home/bin"
        printf 'export ARC_RUN_STATE_DIR=%q\n' "$state"
        printf 'flock %q %q ' "$mode" "$lock"
        printf '%q ' "$@"
        printf '>>%q 2>&1\n' "$log"
        echo 'result=$?'
        printf 'printf "%%s\\n" "$result" >%q\n' "$state/exit-status.tmp"
        printf 'mv %q %q\n' "$state/exit-status.tmp" "$status"
        echo 'exit "$result"'
    } >"$runner"
    chmod 700 "$runner"
}

case "$action" in
    doctor)
        base_ref=$1
        check_java
        command -v git >/dev/null || fail "git is unavailable"
        command -v bash >/dev/null || fail "bash is unavailable"
        command -v cmake >/dev/null || fail "cmake is unavailable"
        command -v flock >/dev/null || fail "flock is unavailable"
        command -v ninja >/dev/null || fail "ninja is unavailable"
        git -C "$source_repo" rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "$source_repo is not the shared source repository"
        git -C "$source_repo" rev-parse --verify "$base_ref^{commit}" >/dev/null 2>&1 || fail "$source_repo has no $base_ref commit"
        check_disk
        echo "remote=$(hostname) java=17 workers=$workers disk_gib=$(($(df -Pk "$source_repo" | awk 'NR == 2 { print $4 }') / 1024 / 1024)) ram_available_gib=$(($(awk '/^MemAvailable:/ { print $2 }' /proc/meminfo) / 1024 / 1024))"
        ;;
    init)
        base_ref=$1
        check_java
        acquire_shared_host_lock
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
        acquire_shared_host_lock
        check_managed_clean_repo
        check_no_active_runs
        actual=$(git -C "$repo" rev-parse "$reference^{commit}")
        [[ "$actual" == "$expected" ]] || fail "snapshot ref resolved to $actual, expected $expected"
        git -C "$repo" checkout --detach "$expected"
        check_managed_clean_repo
        ;;
    start)
        profile=$1
        shift
        check_java
        check_disk
        check_managed_clean_repo
        (( workers >= 1 && workers <= 28 )) || fail "worker count must be between 1 and 28"
        [[ $# -gt 0 ]] || fail "no build command supplied"
        state=$(profile_dir "$profile")
        requested_command=$(canonical_command "$@")
        if profile_is_running "$profile"; then
            [[ -f "$state/command" ]] ||
                fail "profile $profile is running without stored command identity; refusing to reuse it"
            stored_command=$(<"$state/command")
            [[ "$stored_command" == "$requested_command" ]] ||
                fail "profile $profile is already running with a different command; stored command: $stored_command; requested command: $requested_command"
            echo "profile=$profile state=running pid=$(cat "$state/pid")"
            exit 0
        fi
        if [[ -f "$state/pid" && ! -f "$state/exit-status" ]]; then
            fail "profile $profile has stale state without an exit status; inspect $state before retrying"
        fi
        mkdir -p "$state"
        rm -f "$state/build.log" "$state/pid" "$state/exit-status" "$state/exit-status.tmp" \
            "$state/started-at" "$state/command" "$state/runner.sh"
        log="$state/build.log"
        runner="$state/runner.sh"
        : >"$log"
        date --iso-8601=seconds >"$state/started-at"
        printf '%s\n' "$requested_command" >"$state/command"
        write_runner "$state" "$log" "$state/exit-status" "$runner" "$profile" "$@"
        nohup bash "$runner" </dev/null >/dev/null 2>&1 &
        pid=$!
        printf '%s\n' "$pid" >"$state/pid"
        echo "profile=$profile state=started pid=$pid log=$log"
        ;;
    follow)
        profile=$1
        check_managed_repo
        state=$(profile_dir "$profile")
        [[ -f "$state/pid" ]] || fail "profile $profile has not been started"
        pid=$(cat "$state/pid")
        [[ "$pid" =~ ^[0-9]+$ ]] || fail "profile $profile has invalid pid state"
        touch "$state/build.log"
        tail -n +1 --pid="$pid" -f "$state/build.log" || true
        for _ in {1..50}; do
            [[ -f "$state/exit-status" ]] && break
            sleep 0.1
        done
        [[ -f "$state/exit-status" ]] || fail "profile $profile stopped without publishing an exit status"
        exit "$(cat "$state/exit-status")"
        ;;
    status)
        profile=$1
        check_managed_repo
        state=$(profile_dir "$profile")
        if [[ ! -f "$state/pid" ]]; then
            echo "profile=$profile state=not-started"
        elif [[ -f "$state/exit-status" ]]; then
            echo "profile=$profile state=finished pid=$(cat "$state/pid") exit=$(cat "$state/exit-status") started=$(cat "$state/started-at")"
        elif profile_is_running "$profile"; then
            echo "profile=$profile state=running pid=$(cat "$state/pid") started=$(cat "$state/started-at")"
        else
            echo "profile=$profile state=stale pid=$(cat "$state/pid") started=$(cat "$state/started-at")"
        fi
        ;;
    log)
        profile=$1
        check_managed_repo
        state=$(profile_dir "$profile")
        [[ -f "$state/build.log" ]] || fail "profile $profile has no log"
        cat "$state/build.log"
        ;;
    *)
        fail "unknown action: $action"
        ;;
esac
