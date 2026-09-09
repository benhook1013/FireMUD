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

if [[ "$1" != "pr" || "$2" != "view" ]]; then
  echo "unexpected gh invocation" >&2
  exit 91
fi

scenario="$TEST_SCENARIO"
case "$scenario" in
  normal|closed)
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

export PATH="$MOCK_BIN:$PATH"
export TEST_BASE_SHA="$BASE_SHA"
export TEST_PR_HEAD_SHA="$PR_HEAD_SHA"
export TEST_STACK_BASE_SHA="$STACK_BASE_SHA"
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

if "$WRAPPER" --help >/dev/null; then
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

  : >"$ARGS_FILE"
  : >"$HEAD_FILE"
  : >"$BASE_FILE"
  : >"$STATUS_FILE"
  git --git-dir="$REMOTE" update-ref refs/heads/develop "$BASE_SHA"
  (
    cd "$REPO"
    env \
      TEST_MODE="$mode" \
      TEST_ADVANCE_BASE="$advance_base" \
      TEST_INVOCATIONS="$mode" \
      TEST_SCENARIO="$scenario" \
      "$WRAPPER" 2694 --repo example/FireMUD
  ) >"$output_file" 2>"$error_file"
  RUN_STATUS="$?"
  RUN_OUTPUT="$(<"$output_file")"
  RUN_ERROR="$(<"$error_file")"
  RUN_LOG_DIR="$(sed -n 's/^log_dir=//p' "$output_file")"
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
[[ "$(cat "$HEAD_FILE")" == "$LOCAL_HEAD_SHA" ]] || exit 1
[[ "$(cat "$BASE_FILE")" == "$BASE_SHA" ]] || exit 1
[[ -z "$(cat "$STATUS_FILE")" ]] || {
  echo "dirty source edits leaked into candidate worktree" >&2
  exit 1
}
[[ -f "$RUN_LOG_DIR/metadata" && -f "$RUN_LOG_DIR/argv" && -f "$RUN_LOG_DIR/stdout" ]] || exit 1
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

# The base branch may advance independently after the candidate fork. GitHub and
# the wrapper must still agree on the published three-dot scope, while local fixes
# ahead of the published PR head may broaden the candidate scope.
git -C "$REPO" switch -q stack-candidate
set +e
run_wrapper success 0 stacked
stacked_status="$?"
set -e
[[ "$stacked_status" == 0 ]] || {
  echo "stacked-base wrapper run failed: $RUN_ERROR" >&2
  exit 1
}
[[ "$RUN_OUTPUT" == *"base_sha=$STACK_BASE_SHA"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"published_files=1"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"candidate_files=2"* ]] || exit 1
[[ "$(cat "$HEAD_FILE")" == "$STACK_LOCAL_HEAD_SHA" ]] || exit 1
[[ "$(cat "$BASE_FILE")" == "$STACK_BASE_SHA" ]] || exit 1

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
