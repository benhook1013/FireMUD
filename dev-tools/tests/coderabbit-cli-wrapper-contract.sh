#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WRAPPER="$ROOT_DIR/dev-tools/run-coderabbit-review.sh"
TMP_DIR="$(mktemp -d)"

MOCK_BIN="$TMP_DIR/bin"
REPO="$TMP_DIR/repo"
REMOTE="$TMP_DIR/remote.git"
ARGS_FILE="$TMP_DIR/args"
HEAD_FILE="$TMP_DIR/candidate-head"
BASE_FILE="$TMP_DIR/candidate-base"
STATUS_FILE="$TMP_DIR/candidate-status"
INVOCATIONS_FILE="$TMP_DIR/invocations"
STARTED_FILE="$TMP_DIR/review-started"
RELEASE_FILE="$TMP_DIR/review-release"
BLOCK_OUTPUT="$TMP_DIR/block-output"
BLOCK_ERROR="$TMP_DIR/block-error"
WEIRD_PATH=$'line\nbreak.txt'
BLOCK_PID=""
mkdir -p "$MOCK_BIN" "$REPO"

cleanup() {
  if [[ -n "$BLOCK_PID" ]] && kill -0 "$BLOCK_PID" 2>/dev/null; then
    touch "$RELEASE_FILE"
    wait "$BLOCK_PID" 2>/dev/null || true
  fi
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

cat >"$MOCK_BIN/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$1" == "api" ]]; then
  [[ "$2" == repos/example/FireMUD/git/ref/heads/* ]] || {
    echo "unexpected base ref lookup" >&2
    exit 91
  }
  if [[ "${TEST_BASE_TIP_MODE:-}" == "unavailable" ]]; then
    echo "base branch unavailable" >&2
    exit 90
  fi
  printf '%s\n' "$TEST_BASE_TIP_SHA"
  exit 0
fi

if [[ "$1" != "pr" || "$2" != "view" ]]; then
  echo "unexpected gh invocation" >&2
  exit 91
fi

scenario="$TEST_SCENARIO"
case "$scenario" in
  normal|closed|merged|unmerged|unavailable|unrelated|ambiguous)
    base_ref="develop"
    base_sha="$TEST_BASE_SHA"
    head_sha="$TEST_PR_HEAD_SHA"
    changed_files=2
    files_json="$(jq -cn --arg weird "$TEST_WEIRD_PATH" '[{path:"feature.txt"},{path:$weird}]')"
    ;;
  empty)
    base_ref="develop"
    base_sha="$TEST_BASE_SHA"
    head_sha="$TEST_BASE_SHA"
    changed_files=0
    files_json='[]'
    ;;
  stacked)
    base_ref="stack-base"
    base_sha="$TEST_STACK_BASE_SHA"
    head_sha="$TEST_STACK_PR_HEAD_SHA"
    changed_files=1
    files_json='[{"path":"stack-feature.txt"}]'
    ;;
  *)
    echo "unexpected test scenario: $scenario" >&2
    exit 92
    ;;
esac

state="$TEST_PR_STATE"
if [[ "$scenario" == "closed" ]]; then
  state="CLOSED"
fi

jq -cn \
  --arg state "$state" \
  --arg base_ref "$base_ref" \
  --arg base_sha "$base_sha" \
  --arg head_sha "$head_sha" \
  --argjson changed_files "$changed_files" \
  --argjson files "$files_json" \
  '{state:$state,baseRefName:$base_ref,baseRefOid:$base_sha,headRefName:"remote-feature",headRefOid:$head_sha,changedFiles:$changed_files,files:$files}'
EOF

cat >"$MOCK_BIN/coderabbit" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$@" >"$TEST_ARGS_FILE"
printf '%s\n' "${TEST_INVOCATIONS:-}" >>"$TEST_INVOCATIONS_FILE"
git rev-parse HEAD >"$TEST_HEAD_FILE"
base_ref=""
previous=""
for argument in "$@"; do
  if [[ "$previous" == "--base" ]]; then
    base_ref="$argument"
    break
  fi
  previous="$argument"
done
[[ -n "$base_ref" ]] || exit 92
git rev-parse "$base_ref" >"$TEST_BASE_FILE"
git status --porcelain >"$TEST_STATUS_FILE"
if [[ "${TEST_ADVANCE_BASE:-0}" == "1" ]]; then
  git --git-dir="$TEST_REMOTE_REPO" update-ref refs/heads/develop "$TEST_ADVANCE_TO"
fi
if [[ "$TEST_MODE" == "block" ]]; then
  : >"$TEST_STARTED_FILE"
  while [[ ! -e "$TEST_RELEASE_FILE" ]]; do
    sleep 0.02
  done
fi
printf 'mock review stdout\n'
printf 'mock review stderr\n' >&2
if [[ "${TEST_MODE:-success}" == "failure" ]]; then
  exit 23
fi
EOF
chmod +x "$MOCK_BIN/gh" "$MOCK_BIN/coderabbit"

git -C "$REPO" init -q -b develop
git -C "$REPO" config user.name "FireMUD Test"
git -C "$REPO" config user.email "test@example.com"
printf 'baseline\n' >"$REPO/README.md"
git -C "$REPO" add README.md
git -C "$REPO" commit -q -m "baseline"
BASE_SHA="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" clone --bare "$REPO" "$REMOTE" >/dev/null
git -C "$REPO" remote add origin "$REMOTE"
git -C "$REPO" push -q origin develop

printf 'feature\n' >"$REPO/feature.txt"
printf 'newline path\n' >"$REPO/$WEIRD_PATH"
git -C "$REPO" add feature.txt
git -C "$REPO" add -- "$WEIRD_PATH"
git -C "$REPO" commit -q -m "feature"
PR_HEAD_SHA="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" branch -M codex/local-alias

printf 'local fix\n' >"$REPO/local-fix.txt"
git -C "$REPO" add local-fix.txt
git -C "$REPO" commit -q -m "local fix ahead of published head"
LOCAL_HEAD_SHA="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" push -q origin codex/local-alias
printf 'uncommitted source edit\n' >"$REPO/dirty.txt"

git -C "$REPO" switch -q -c empty-candidate "$PR_HEAD_SHA"
git -C "$REPO" rm -q -- feature.txt "$WEIRD_PATH"
git -C "$REPO" commit -q -m "revert all published changes"
EMPTY_CANDIDATE_SHA="$(git -C "$REPO" rev-parse HEAD)"

git -C "$REPO" switch -q -c stack-base "$BASE_SHA"
printf 'stack base initial\n' >"$REPO/stack-base.txt"
git -C "$REPO" add stack-base.txt
git -C "$REPO" commit -q -m "stack base initial"
STACK_INITIAL_SHA="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" switch -q -c stack-candidate
printf 'stack feature\n' >"$REPO/stack-feature.txt"
git -C "$REPO" add stack-feature.txt
git -C "$REPO" commit -q -m "stack feature"
STACK_PR_HEAD_SHA="$(git -C "$REPO" rev-parse HEAD)"
printf 'stack local fix\n' >"$REPO/stack-local-fix.txt"
git -C "$REPO" add stack-local-fix.txt
git -C "$REPO" commit -q -m "stack local fix"
STACK_LOCAL_HEAD_SHA="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" switch -q stack-base
printf 'stack base advance\n' >"$REPO/stack-base-advance.txt"
git -C "$REPO" add stack-base-advance.txt
git -C "$REPO" commit -q -m "advance stack base after candidate fork"
STACK_BASE_SHA="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" push -q origin stack-base
git -C "$REPO" switch -q codex/local-alias

git -C "$REPO" switch -q -c develop-advance "$BASE_SHA"
printf 'base branch only\n' >"$REPO/base-branch-only.txt"
git -C "$REPO" add base-branch-only.txt
git -C "$REPO" commit -q -m "advance develop after candidate fork"
DEVELOP_ADVANCE_SHA="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" push -q origin develop-advance
git -C "$REPO" switch -q -c merged-candidate codex/local-alias
git -C "$REPO" merge -q --no-ff develop-advance -m "merge current develop into candidate"
MERGED_CANDIDATE_SHA="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" switch -q codex/local-alias

git -C "$REPO" switch -q -c ambiguous-a "$BASE_SHA"
printf 'ambiguous A\n' >"$REPO/ambiguous-a.txt"
git -C "$REPO" add ambiguous-a.txt
git -C "$REPO" commit -q -m "ambiguous base A"
AMBIGUOUS_A_SHA="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" switch -q -c ambiguous-b "$BASE_SHA"
printf 'ambiguous B\n' >"$REPO/ambiguous-b.txt"
git -C "$REPO" add ambiguous-b.txt
git -C "$REPO" commit -q -m "ambiguous base B"
git -C "$REPO" switch -q ambiguous-a
git -C "$REPO" merge -q --no-ff ambiguous-b -m "ambiguous merge A"
AMBIGUOUS_TIP_A_SHA="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" switch -q ambiguous-b
git -C "$REPO" merge -q --no-ff "$AMBIGUOUS_A_SHA" -m "ambiguous merge B"
AMBIGUOUS_TIP_B_SHA="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" push -q origin HEAD:refs/heads/ambiguous-base-tip
git -C "$REPO" switch -q -c ambiguous-candidate codex/local-alias
git -C "$REPO" merge -q --no-ff "$AMBIGUOUS_TIP_A_SHA" -m "merge ambiguous candidate history"
AMBIGUOUS_CANDIDATE_SHA="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" switch -q codex/local-alias

UNRELATED_BASE_BLOB="$(printf 'unrelated base\n' | git -C "$REPO" hash-object -w --stdin)"
UNRELATED_BASE_TREE="$(printf '100644 blob %s\tunrelated.txt\n' "$UNRELATED_BASE_BLOB" | git -C "$REPO" mktree)"
UNRELATED_BASE_SHA="$(printf 'unrelated base\n' | git -C "$REPO" -c user.name='FireMUD Test' -c user.email='test@example.com' commit-tree "$UNRELATED_BASE_TREE")"
git -C "$REPO" push -q origin "$UNRELATED_BASE_SHA:refs/heads/unrelated-base"

export PATH="$MOCK_BIN:$PATH"
export TEST_BASE_SHA="$BASE_SHA"
export TEST_PR_HEAD_SHA="$PR_HEAD_SHA"
export TEST_STACK_BASE_SHA="$STACK_BASE_SHA"
export TEST_STACK_INITIAL_SHA="$STACK_INITIAL_SHA"
export TEST_DEVELOP_ADVANCE_SHA="$DEVELOP_ADVANCE_SHA"
export TEST_MERGED_CANDIDATE_SHA="$MERGED_CANDIDATE_SHA"
export TEST_AMBIGUOUS_TIP_B_SHA="$AMBIGUOUS_TIP_B_SHA"
export TEST_AMBIGUOUS_CANDIDATE_SHA="$AMBIGUOUS_CANDIDATE_SHA"
export TEST_UNRELATED_BASE_SHA="$UNRELATED_BASE_SHA"
export TEST_STACK_PR_HEAD_SHA="$STACK_PR_HEAD_SHA"
export TEST_WEIRD_PATH="$WEIRD_PATH"
export TEST_PR_STATE=OPEN
export TEST_REMOTE_REPO="$REMOTE"
export TEST_ADVANCE_TO="$LOCAL_HEAD_SHA"
export TEST_ARGS_FILE="$ARGS_FILE"
export TEST_HEAD_FILE="$HEAD_FILE"
export TEST_BASE_FILE="$BASE_FILE"
export TEST_STATUS_FILE="$STATUS_FILE"
export TEST_INVOCATIONS_FILE="$INVOCATIONS_FILE"
export TEST_STARTED_FILE="$STARTED_FILE"
export TEST_RELEASE_FILE="$RELEASE_FILE"

help_output="$("$WRAPPER" --help)"
if [[ "$help_output" == *"Launches the CodeRabbit CLI"* && "$help_output" == *"consumes the separate CLI review quota"* && "$help_output" == *"report-pr-review-checkpoints.py"* ]]; then
  :
else
  echo "wrapper --help failed" >&2
  exit 1
fi

run_wrapper() {
  local mode="$1"
  local advance_base="$2"
  local scenario="${3:-normal}"
  local output_file="${4:-$TMP_DIR/output}"
  local error_file="${5:-$TMP_DIR/error}"

  local base_tip_sha
  local base_tip_mode=""
  case "$scenario" in
    merged|unmerged)
      base_tip_sha="$TEST_DEVELOP_ADVANCE_SHA"
      git --git-dir="$REMOTE" update-ref refs/heads/develop "$base_tip_sha"
      ;;
    stacked)
      base_tip_sha="$STACK_BASE_SHA"
      git --git-dir="$REMOTE" update-ref refs/heads/stack-base "$base_tip_sha"
      ;;
    ambiguous)
      base_tip_sha="$TEST_AMBIGUOUS_TIP_B_SHA"
      git --git-dir="$REMOTE" update-ref refs/heads/develop "$base_tip_sha"
      ;;
    unrelated)
      base_tip_sha="$TEST_UNRELATED_BASE_SHA"
      git --git-dir="$REMOTE" update-ref refs/heads/develop "$base_tip_sha"
      ;;
    unavailable)
      base_tip_sha="$BASE_SHA"
      base_tip_mode=unavailable
      git --git-dir="$REMOTE" update-ref refs/heads/develop "$base_tip_sha"
      ;;
    *)
      base_tip_sha="${TEST_FETCH_BASE_SHA:-$BASE_SHA}"
      git --git-dir="$REMOTE" update-ref refs/heads/develop "$base_tip_sha"
      ;;
  esac
  : >"$ARGS_FILE"
  : >"$HEAD_FILE"
  : >"$BASE_FILE"
  : >"$STATUS_FILE"
  (
    cd "$REPO"
    env \
      TEST_MODE="$mode" \
      TEST_ADVANCE_BASE="$advance_base" \
      TEST_INVOCATIONS="$mode" \
      TEST_SCENARIO="$scenario" \
      TEST_BASE_TIP_SHA="$base_tip_sha" \
      TEST_BASE_TIP_MODE="$base_tip_mode" \
      "$WRAPPER" 2694 --repo example/FireMUD
  ) >"$output_file" 2>"$error_file"
  RUN_STATUS="$?"
  RUN_OUTPUT="$(<"$output_file")"
  RUN_ERROR="$(<"$error_file")"
  RUN_LOG_DIR="$(sed -n 's/^log_dir=//p' "$output_file")"
  RUN_ID="$(basename "$RUN_LOG_DIR")"
  if [[ "$RUN_STATUS" == 0 ]]; then
    [[ "$(sed -n 's/^checkpoint_marker=//p' "$output_file")" == "<!-- firemud-cli-run: $RUN_ID -->" ]] || exit 1
  fi
  return "$RUN_STATUS"
}

set +e
run_wrapper success 1
first_status="$?"
set -e
[[ "$first_status" == 0 ]] || {
  echo "successful wrapper run failed: $RUN_ERROR" >&2
  exit 1
}
[[ "$RUN_OUTPUT" == *"expected_files=2"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"published_files=2"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"candidate_files=3"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"published_status=unpublished-commits-ahead:1"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"pr_base_sha=$BASE_SHA"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"base_tip_sha=$BASE_SHA"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"report_command=python3 dev-tools/validation/report-pr-review-checkpoints.py --repo example/FireMUD --pr 2694"* ]] || exit 1
[[ "$(cat "$HEAD_FILE")" == "$LOCAL_HEAD_SHA" ]] || exit 1
[[ "$(cat "$BASE_FILE")" == "$BASE_SHA" ]] || exit 1
[[ -z "$(cat "$STATUS_FILE")" ]] || {
  echo "dirty source edits leaked into candidate worktree" >&2
  exit 1
}
[[ -f "$RUN_LOG_DIR/metadata" && -f "$RUN_LOG_DIR/argv" && -f "$RUN_LOG_DIR/stdout" ]] || exit 1
grep -q "^run_id=$RUN_ID$" "$RUN_LOG_DIR/metadata" || exit 1
[[ "$(cat "$RUN_LOG_DIR/exit-status")" == 0 ]] || exit 1
candidate_path="$(sed -n 's/^candidate_worktree=//p' "$RUN_LOG_DIR/metadata")"
[[ ! -e "$candidate_path" ]] || exit 1
pinned_ref="$(sed -n 's/^pinned_base_ref=//p' "$RUN_LOG_DIR/metadata")"
! git -C "$REPO" show-ref --verify --quiet "$pinned_ref" || exit 1
[[ "$(stat -c '%a' "$RUN_LOG_DIR")" == 700 ]] || exit 1
[[ -f "$REPO/dirty.txt" ]] || exit 1
[[ "$(sed -n '1p' "$ARGS_FILE")" == review ]] || exit 1
[[ "$(sed -n '2p' "$ARGS_FILE")" == --agent ]] || exit 1
[[ "$(sed -n '3p' "$ARGS_FILE")" == --committed ]] || exit 1
[[ "$(sed -n '4p' "$ARGS_FILE")" == --base ]] || exit 1
grep -q '^refs/heads/codex-review-base/run\.' < <(sed -n '5p' "$ARGS_FILE")
[[ "$(wc -l <"$ARGS_FILE")" == 5 ]] || exit 1

# A candidate that merged a newer base tip excludes the base-only file while
# retaining the published feature and the unpublished local fix.
git -C "$REPO" switch -q merged-candidate
set +e
run_wrapper success 0 merged
merged_status="$?"
set -e
[[ "$merged_status" == 0 ]] || {
  echo "merged-base wrapper run failed: $RUN_ERROR" >&2
  exit 1
}
[[ "$RUN_OUTPUT" == *"pr_base_sha=$BASE_SHA"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"base_tip_sha=$DEVELOP_ADVANCE_SHA"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"base_sha=$DEVELOP_ADVANCE_SHA"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"candidate_files=3"* ]] || exit 1
[[ "$(cat "$BASE_FILE")" == "$DEVELOP_ADVANCE_SHA" ]] || exit 1

# When the base advances without being merged, use the inherited fork point so
# base-only files remain outside the candidate scope.
git -C "$REPO" switch -q codex/local-alias
set +e
run_wrapper success 0 unmerged
unmerged_status="$?"
set -e
[[ "$unmerged_status" == 0 ]] || {
  echo "unmerged-base wrapper run failed: $RUN_ERROR" >&2
  exit 1
}
[[ "$RUN_OUTPUT" == *"base_tip_sha=$DEVELOP_ADVANCE_SHA"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"base_sha=$BASE_SHA"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"candidate_files=3"* ]] || exit 1
[[ "$(cat "$BASE_FILE")" == "$BASE_SHA" ]] || exit 1

# The named base branch can advance after GitHub records the pull request base.
# The recorded PR base still validates the published scope independently.
set +e
TEST_FETCH_BASE_SHA="$DEVELOP_ADVANCE_SHA" run_wrapper success 0
advanced_base_status="$?"
set -e
[[ "$advanced_base_status" == 0 ]] || {
  echo "advanced-base wrapper run failed: $RUN_ERROR" >&2
  exit 1
}
[[ "$RUN_OUTPUT" == *"base_sha=$BASE_SHA"* ]] || exit 1
[[ "$(cat "$BASE_FILE")" == "$BASE_SHA" ]] || exit 1

# A non-default stacked base may advance independently after the candidate fork.
# The derived merge base retains the stack change and local candidate fix.
git -C "$REPO" switch -q stack-candidate
set +e
run_wrapper success 0 stacked
stacked_status="$?"
set -e
[[ "$stacked_status" == 0 ]] || {
  echo "stacked-base wrapper run failed: $RUN_ERROR" >&2
  exit 1
}
[[ "$RUN_OUTPUT" == *"pr_base_sha=$STACK_BASE_SHA"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"base_tip_sha=$STACK_BASE_SHA"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"base_sha=$STACK_INITIAL_SHA"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"published_files=1"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"candidate_files=2"* ]] || exit 1
[[ "$(cat "$HEAD_FILE")" == "$STACK_LOCAL_HEAD_SHA" ]] || exit 1
[[ "$(cat "$BASE_FILE")" == "$STACK_INITIAL_SHA" ]] || exit 1

git -C "$REPO" switch -q codex/local-alias
invocations_before_base_failures="$(wc -l <"$INVOCATIONS_FILE")"
set +e
run_wrapper success 0 unavailable
unavailable_status="$?"
set -e
[[ "$unavailable_status" == 1 ]] || exit 1
[[ "$RUN_ERROR" == *"could not resolve current pull request base branch"* ]] || exit 1
[[ "$(wc -l <"$INVOCATIONS_FILE")" == "$invocations_before_base_failures" ]] || exit 1

set +e
run_wrapper success 0 unrelated
unrelated_base_status="$?"
set -e
[[ "$unrelated_base_status" == 1 ]] || exit 1
[[ "$RUN_ERROR" == *"current pull request base tip are unrelated histories"* ]] || exit 1
[[ "$(wc -l <"$INVOCATIONS_FILE")" == "$invocations_before_base_failures" ]] || exit 1

git -C "$REPO" switch -q ambiguous-candidate
set +e
run_wrapper success 0 ambiguous
ambiguous_base_status="$?"
set -e
[[ "$ambiguous_base_status" == 1 ]] || exit 1
[[ "$RUN_ERROR" == *"current pull request base tip have ambiguous merge bases"* ]] || exit 1
[[ "$(wc -l <"$INVOCATIONS_FILE")" == "$invocations_before_base_failures" ]] || exit 1
git -C "$REPO" switch -q codex/local-alias

# Scope and log provenance must be visible before the review process completes,
# and a concurrent wrapper must fail before a second CodeRabbit invocation.
git -C "$REPO" switch -q codex/local-alias
rm -f "$STARTED_FILE" "$RELEASE_FILE" "$BLOCK_OUTPUT" "$BLOCK_ERROR"
git --git-dir="$REMOTE" update-ref refs/heads/develop "$BASE_SHA"
(
  cd "$REPO"
  env \
    TEST_MODE=block \
    TEST_ADVANCE_BASE=0 \
    TEST_INVOCATIONS=block \
    TEST_SCENARIO=normal \
    TEST_BASE_TIP_SHA="$BASE_SHA" \
    "$WRAPPER" 2694 --repo example/FireMUD
) >"$BLOCK_OUTPUT" 2>"$BLOCK_ERROR" &
BLOCK_PID=$!
for _ in {1..250}; do
  [[ -e "$STARTED_FILE" ]] && break
  sleep 0.02
done
[[ -e "$STARTED_FILE" ]] || {
  echo "blocking mock review did not start" >&2
  exit 1
}
grep -q '^candidate_sha=' "$BLOCK_OUTPUT" || {
  echo "scope was not visible before the review completed" >&2
  exit 1
}
grep -q '^log_dir=' "$BLOCK_OUTPUT" || exit 1
invocations_while_blocked="$(wc -l <"$INVOCATIONS_FILE")"
set +e
run_wrapper success 0 normal "$TMP_DIR/concurrent-output" "$TMP_DIR/concurrent-error"
concurrent_status="$?"
set -e
[[ "$concurrent_status" == 1 ]] || exit 1
[[ "$RUN_ERROR" == *"another CodeRabbit wrapper is already running"* ]] || exit 1
[[ "$(wc -l <"$INVOCATIONS_FILE")" == "$invocations_while_blocked" ]] || exit 1
touch "$RELEASE_FILE"
set +e
wait "$BLOCK_PID"
blocked_status="$?"
set -e
BLOCK_PID=""
[[ "$blocked_status" == 0 ]] || exit 1
blocked_log_dir="$(sed -n 's/^log_dir=//p' "$BLOCK_OUTPUT")"
blocked_candidate_path="$(sed -n 's/^candidate_worktree=//p' "$blocked_log_dir/metadata")"
[[ ! -e "$blocked_candidate_path" ]] || exit 1
blocked_pinned_ref="$(sed -n 's/^pinned_base_ref=//p' "$blocked_log_dir/metadata")"
! git -C "$REPO" show-ref --verify --quiet "$blocked_pinned_ref" || exit 1

set +e
run_wrapper failure 0
failure_status="$?"
set -e
[[ "$failure_status" == 23 ]] || exit 1
[[ "$RUN_OUTPUT" == *"mock review stdout"* ]] || exit 1
[[ "$RUN_ERROR" == *"mock review stderr"* ]] || exit 1
[[ "$(cat "$RUN_LOG_DIR/exit-status")" == 23 ]] || exit 1
failure_candidate_path="$(sed -n 's/^candidate_worktree=//p' "$RUN_LOG_DIR/metadata")"
[[ ! -e "$failure_candidate_path" ]] || exit 1
failure_pinned_ref="$(sed -n 's/^pinned_base_ref=//p' "$RUN_LOG_DIR/metadata")"
! git -C "$REPO" show-ref --verify --quiet "$failure_pinned_ref" || exit 1

invocations_before_rejection="$(wc -l <"$INVOCATIONS_FILE")"

set +e
run_wrapper success 0 closed
closed_status="$?"
set -e
[[ "$closed_status" == 1 ]] || exit 1
[[ "$RUN_ERROR" == *"pull request is not OPEN"* ]] || exit 1
[[ "$(wc -l <"$INVOCATIONS_FILE")" == "$invocations_before_rejection" ]] || exit 1

git -C "$REPO" switch --detach -q "$EMPTY_CANDIDATE_SHA"
set +e
run_wrapper success 0 normal
empty_candidate_status="$?"
set -e
[[ "$empty_candidate_status" == 1 ]] || exit 1
[[ "$RUN_ERROR" == *"candidate has zero changed files"* ]] || exit 1
[[ "$(wc -l <"$INVOCATIONS_FILE")" == "$invocations_before_rejection" ]] || exit 1

git -C "$REPO" switch --detach -q "$BASE_SHA"
set +e
run_wrapper success 0 empty
empty_status="$?"
set -e
[[ "$empty_status" == 1 ]] || exit 1
[[ "$RUN_ERROR" == *"zero changed files"* ]] || exit 1
[[ "$(wc -l <"$INVOCATIONS_FILE")" == "$invocations_before_rejection" ]] || exit 1

git -C "$REPO" switch --detach -q "$BASE_SHA"
set +e
run_wrapper success 0
rejected_status="$?"
set -e
[[ "$rejected_status" == 1 ]] || exit 1
[[ "$RUN_ERROR" == *"neither the pull request head nor a descendant"* ]] || exit 1
[[ "$(wc -l <"$INVOCATIONS_FILE")" == "$invocations_before_rejection" ]] || exit 1

git -C "$REPO" switch -q --orphan unrelated
git -C "$REPO" rm -q -r --cached . 2>/dev/null || true
printf 'unrelated\n' >"$REPO/unrelated.txt"
git -C "$REPO" add unrelated.txt
git -C "$REPO" commit -q -m "unrelated history"
set +e
run_wrapper success 0
unrelated_status="$?"
set -e
[[ "$unrelated_status" == 1 ]] || exit 1
[[ "$RUN_ERROR" == *"unrelated histories"* ]] || exit 1
[[ "$(wc -l <"$INVOCATIONS_FILE")" == "$invocations_before_rejection" ]] || exit 1

echo "CodeRabbit CLI wrapper contract checks passed"
