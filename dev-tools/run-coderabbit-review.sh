#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: dev-tools/run-coderabbit-review.sh <pull-request-number> [--repo <owner/name>]

Run the complete committed candidate through CodeRabbit CLI using the pull request's
current base. The source worktree may be dirty; its committed HEAD is reviewed from
an isolated temporary worktree and uncommitted edits are preserved.
EOF
}

die() {
  local message="$1"

  printf 'error: %s\n' "$message" >&2
  if [[ -n "${log_dir:-}" ]]; then
    printf 'error: %s\n' "$message" >>"$log_dir/preflight-error.log"
  fi
  exit 1
}

count_nul_paths() {
  local count=0

  while IFS= read -r -d '' _; do
    count=$((count + 1))
  done <"$1"
  printf '%s\n' "$count"
}

# shellcheck disable=SC2317 # ShellCheck does not follow EXIT trap callbacks.
cleanup() {
  local exit_status=$?

  if [[ -n "${candidate_worktree:-}" && -d "$candidate_worktree" ]]; then
    git -C "$source_root" worktree remove --force "$candidate_worktree" >/dev/null 2>&1 || true
  fi
  if [[ -n "${pinned_base_ref:-}" && -n "${base_sha:-}" ]]; then
    git -C "$source_root" update-ref -d "$pinned_base_ref" "$base_sha" >/dev/null 2>&1 || true
  fi
  if [[ -n "${temp_root:-}" && -d "$temp_root" ]]; then
    rmdir "$temp_root" >/dev/null 2>&1 || true
  fi

  exit "$exit_status"
}

if [[ ${1:-} == "--help" || ${1:-} == "-h" ]]; then
  usage
  exit 0
fi

if [[ $# -lt 1 ]]; then
  usage >&2
  exit 2
fi

pr_number="$1"
shift
repo=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      repo="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      printf 'error: unsupported argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

[[ "$pr_number" =~ ^[1-9][0-9]*$ ]] || {
  printf 'error: pull request number must be a positive integer\n' >&2
  exit 2
}

for dependency in git gh jq coderabbit flock; do
  command -v "$dependency" >/dev/null 2>&1 || {
    printf 'error: required executable not found: %s\n' "$dependency" >&2
    exit 1
  }
done

source_root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  printf 'error: run this command inside a Git worktree\n' >&2
  exit 1
}

git_common_dir="$(git -C "$source_root" rev-parse --git-common-dir)"
if [[ "$git_common_dir" != /* ]]; then
  git_common_dir="$source_root/$git_common_dir"
fi

log_root="$git_common_dir/coderabbit-review-logs"
mkdir -p "$log_root"
chmod 700 "$log_root"
lock_file="$log_root/review.lock"
exec {lock_fd}>"$lock_file"
chmod 600 "$lock_file"
flock -n "$lock_fd" || die "another CodeRabbit wrapper is already running (lock: $lock_file)"
log_dir="$(mktemp -d "$log_root/run.XXXXXX")"
chmod 700 "$log_dir"
temp_root="$(mktemp -d "${TMPDIR:-/tmp}/firemud-coderabbit-review.XXXXXX")"
candidate_worktree="$temp_root/candidate"
trap cleanup EXIT

if [[ -z "$repo" ]]; then
  repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner 2>"$log_dir/repo.stderr")" ||
    die "could not infer the GitHub repository; pass --repo owner/name"
fi
[[ "$repo" =~ ^[^/]+/[^/]+$ ]] || die "repository must be owner/name"

remote="origin"
git -C "$source_root" remote get-url "$remote" >"$log_dir/remote-url" 2>"$log_dir/remote.stderr" ||
  die "Git remote origin is required to fetch the pull request base"

git -C "$source_root" status --porcelain >"$log_dir/source-status" || die "could not inspect source worktree"
candidate_sha="$(git -C "$source_root" rev-parse 'HEAD^{commit}')" || die "could not resolve committed HEAD"

gh pr view "$pr_number" --repo "$repo" \
  --json state,baseRefName,baseRefOid,headRefName,headRefOid,changedFiles,files \
  >"$log_dir/pull-request.json" 2>"$log_dir/pull-request.stderr" ||
  die "could not read pull request metadata"

pr_state="$(jq -er '.state | strings | ascii_upcase' "$log_dir/pull-request.json")" ||
  die "pull request metadata has no state"
[[ "$pr_state" == "OPEN" ]] || die "pull request is not OPEN (state: $pr_state)"
base_ref_name="$(jq -er '.baseRefName | select(type == "string" and length > 0)' "$log_dir/pull-request.json")" ||
  die "pull request metadata has no base branch"
base_sha="$(jq -er '.baseRefOid | select(type == "string" and test("^[0-9a-fA-F]{40}$"))' "$log_dir/pull-request.json")" ||
  die "pull request metadata has no full base commit"
pr_head_sha="$(jq -er '.headRefOid | select(type == "string" and test("^[0-9a-fA-F]{40}$"))' "$log_dir/pull-request.json")" ||
  die "pull request metadata has no full head commit"
expected_files="$(jq -er '.changedFiles | select(type == "number" and floor == . and . >= 0)' "$log_dir/pull-request.json")" ||
  die "pull request metadata has no changed-file count"
jq -jr '.files[]? | (.path, "\u0000")' "$log_dir/pull-request.json" | sort -z >"$log_dir/expected-files.nul" ||
  die "pull request metadata has no readable file list"
expected_path_count="$(count_nul_paths "$log_dir/expected-files.nul")"
[[ "$expected_path_count" == "$expected_files" ]] ||
  die "pull request file list/count mismatch (files: $expected_path_count, changedFiles: $expected_files)"

git -C "$source_root" fetch --no-tags "$remote" "refs/heads/$base_ref_name" \
  >"$log_dir/base-fetch.stdout" 2>"$log_dir/base-fetch.stderr" ||
  die "could not fetch pull request base branch $base_ref_name"
fetched_base_sha="$(git -C "$source_root" rev-parse 'FETCH_HEAD^{commit}')" ||
  die "could not resolve fetched pull request base"
[[ "${fetched_base_sha,,}" == "${base_sha,,}" ]] ||
  die "pull request base moved during fetch (expected $base_sha, fetched $fetched_base_sha); refusing to review an ambiguous base"

if ! git -C "$source_root" cat-file -e "$pr_head_sha^{commit}" 2>/dev/null; then
  git -C "$source_root" fetch --no-tags "$remote" "refs/pull/$pr_number/head" \
    >"$log_dir/head-fetch.stdout" 2>"$log_dir/head-fetch.stderr" ||
    die "pull request head is not available in this checkout and could not be fetched"
  fetched_head_sha="$(git -C "$source_root" rev-parse 'FETCH_HEAD^{commit}')" ||
    die "could not resolve fetched pull request head"
  [[ "${fetched_head_sha,,}" == "${pr_head_sha,,}" ]] ||
    die "pull request head changed during fetch; refusing an ambiguous candidate"
fi

git -C "$source_root" merge-base "$base_sha" "$candidate_sha" >"$log_dir/merge-base" ||
  die "candidate and pull request base are unrelated histories"
git -C "$source_root" merge-base --is-ancestor "$pr_head_sha" "$candidate_sha" ||
  die "committed HEAD is neither the pull request head nor a descendant containing its fixes"

merge_base="$(<"$log_dir/merge-base")"
git -C "$source_root" diff --name-only -z "$base_sha...$pr_head_sha" >"$log_dir/published-files.nul" ||
  die "could not calculate the published pull request scope"
sort -z -o "$log_dir/published-files.nul" "$log_dir/published-files.nul"
published_files="$(count_nul_paths "$log_dir/published-files.nul")"
[[ "$published_files" != 0 ]] || die "pull request has zero changed files; refusing to spend review quota"
cmp -s "$log_dir/expected-files.nul" "$log_dir/published-files.nul" ||
  die "published pull request file paths do not match GitHub's file list"
git -C "$source_root" diff --name-only -z "$base_sha...$candidate_sha" >"$log_dir/candidate-files.nul" ||
  die "could not calculate the candidate scope"
sort -z -o "$log_dir/candidate-files.nul" "$log_dir/candidate-files.nul"
candidate_files="$(count_nul_paths "$log_dir/candidate-files.nul")"
[[ "$candidate_files" != 0 ]] || die "candidate has zero changed files; refusing to spend review quota"
unpublished_commits="$(git -C "$source_root" rev-list --count "$pr_head_sha..$candidate_sha")"
if [[ "$unpublished_commits" == 0 ]]; then
  published_status="published-head"
else
  published_status="unpublished-commits-ahead:$unpublished_commits"
fi

run_name="$(basename "$log_dir")"
pinned_base_ref="refs/heads/codex-review-base/$run_name"
git -C "$source_root" update-ref "$pinned_base_ref" "$base_sha" ||
  die "could not create immutable temporary base ref"

git -C "$source_root" worktree add --detach "$candidate_worktree" "$candidate_sha" \
  >"$log_dir/worktree.stdout" 2>"$log_dir/worktree.stderr" ||
  die "could not create isolated candidate worktree"

cat >"$log_dir/metadata" <<EOF
repository=$repo
pull_request=$pr_number
pr_state=$pr_state
source_root=$source_root
candidate_worktree=$candidate_worktree
candidate_sha=$candidate_sha
pr_head_sha=$pr_head_sha
base_ref_name=$base_ref_name
base_sha=$base_sha
pinned_base_ref=$pinned_base_ref
merge_base=$merge_base
expected_files=$expected_files
published_files=$published_files
candidate_files=$candidate_files
published_status=$published_status
EOF
{
  printf 'argv='
  printf '%q ' review --agent --committed --base "$pinned_base_ref"
  printf '\n'
} >"$log_dir/argv"

printf 'repository=%s\n' "$repo"
printf 'pull_request=%s\n' "$pr_number"
printf 'candidate_sha=%s\n' "$candidate_sha"
printf 'base_sha=%s\n' "$base_sha"
printf 'pinned_base_ref=%s\n' "$pinned_base_ref"
printf 'merge_base=%s\n' "$merge_base"
printf 'expected_files=%s\n' "$expected_files"
printf 'published_files=%s\n' "$published_files"
printf 'candidate_files=%s\n' "$candidate_files"
printf 'published_status=%s\n' "$published_status"
printf 'log_dir=%s\n' "$log_dir"

set +e
(
  cd "$candidate_worktree"
  coderabbit review --agent --committed --base "$pinned_base_ref" \
    >"$log_dir/stdout" 2>"$log_dir/stderr"
)
cli_status=$?
set -e
printf '%s\n' "$cli_status" >"$log_dir/exit-status"

cat "$log_dir/stdout"
if [[ -s "$log_dir/stderr" ]]; then
  cat "$log_dir/stderr" >&2
fi
exit "$cli_status"
