#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WRAPPER="$ROOT_DIR/dev-tools/run-coderabbit-review.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

MOCK_BIN="$TMP_DIR/bin"
REPO="$TMP_DIR/repo"
REMOTE="$TMP_DIR/remote.git"
ARGS_FILE="$TMP_DIR/args"
HEAD_FILE="$TMP_DIR/candidate-head"
BASE_FILE="$TMP_DIR/candidate-base"
STATUS_FILE="$TMP_DIR/candidate-status"
INVOCATIONS_FILE="$TMP_DIR/invocations"
mkdir -p "$MOCK_BIN" "$REPO"

cat >"$MOCK_BIN/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$1" != "pr" || "$2" != "view" ]]; then
  echo "unexpected gh invocation" >&2
  exit 91
fi

printf '{"baseRefName":"develop","baseRefOid":"%s","headRefName":"remote-feature","headRefOid":"%s","changedFiles":1}\n' \
  "$TEST_BASE_SHA" "$TEST_PR_HEAD_SHA"
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
git -C "$REPO" add feature.txt
git -C "$REPO" commit -q -m "feature"
PR_HEAD_SHA="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" branch -M codex/local-alias

printf 'local fix\n' >"$REPO/local-fix.txt"
git -C "$REPO" add local-fix.txt
git -C "$REPO" commit -q -m "local fix ahead of published head"
LOCAL_HEAD_SHA="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" push -q origin codex/local-alias
printf 'uncommitted source edit\n' >"$REPO/dirty.txt"

export PATH="$MOCK_BIN:$PATH"
export TEST_BASE_SHA="$BASE_SHA"
export TEST_PR_HEAD_SHA="$PR_HEAD_SHA"
export TEST_REMOTE_REPO="$REMOTE"
export TEST_ADVANCE_TO="$LOCAL_HEAD_SHA"
export TEST_ARGS_FILE="$ARGS_FILE"
export TEST_HEAD_FILE="$HEAD_FILE"
export TEST_BASE_FILE="$BASE_FILE"
export TEST_STATUS_FILE="$STATUS_FILE"
export TEST_INVOCATIONS_FILE="$INVOCATIONS_FILE"

if "$WRAPPER" --help >/dev/null; then
  :
else
  echo "wrapper --help failed" >&2
  exit 1
fi

run_wrapper() {
  local mode="$1"
  local advance_base="$2"

  : >"$ARGS_FILE"
  : >"$HEAD_FILE"
  : >"$BASE_FILE"
  : >"$STATUS_FILE"
  git --git-dir="$REMOTE" update-ref refs/heads/develop "$BASE_SHA"
  (
    export TEST_MODE="$mode"
    export TEST_ADVANCE_BASE="$advance_base"
    export TEST_INVOCATIONS="$mode"
    cd "$REPO"
    "$WRAPPER" 2694 --repo example/FireMUD
  ) >"$TMP_DIR/output" 2>"$TMP_DIR/error"
  RUN_STATUS="$?"
  RUN_OUTPUT="$(<"$TMP_DIR/output")"
  RUN_ERROR="$(<"$TMP_DIR/error")"
  RUN_LOG_DIR="$(sed -n 's/^log_dir=//p' "$TMP_DIR/output")"
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
[[ "$RUN_OUTPUT" == *"expected_files=1"* ]] || exit 1
[[ "$RUN_OUTPUT" == *"candidate_files=2"* ]] || exit 1
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
[[ -f "$REPO/dirty.txt" ]] || exit 1
grep -Fxq review "$ARGS_FILE"
grep -Fxq -- --agent "$ARGS_FILE"
grep -Fxq -- --committed "$ARGS_FILE"
grep -Fxq -- --base "$ARGS_FILE"
grep -q '^refs/heads/codex-review-base/run\.' "$ARGS_FILE"

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

invocations_before_rejection="$(wc -l <"$INVOCATIONS_FILE")"
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
