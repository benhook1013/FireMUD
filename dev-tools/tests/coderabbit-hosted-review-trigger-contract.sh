#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WRAPPER="$ROOT_DIR/dev-tools/request-coderabbit-review.sh"
CHECKER="$ROOT_DIR/dev-tools/validation/check-coderabbit-review.py"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

TEST_REPO="$TMP_DIR/repo"
MOCK_BIN="$TMP_DIR/bin"
MOCK_STATE="$TMP_DIR/mock-state"
mkdir -p "$TEST_REPO/dev-tools/validation" "$MOCK_BIN" "$MOCK_STATE"
cp "$WRAPPER" "$TEST_REPO/dev-tools/request-coderabbit-review.sh"
cp "$CHECKER" "$TEST_REPO/dev-tools/validation/check-coderabbit-review.py"
chmod +x "$TEST_REPO/dev-tools/request-coderabbit-review.sh"
chmod 644 "$TEST_REPO/dev-tools/validation/check-coderabbit-review.py"
git -C "$TEST_REPO" init -q
git -C "$TEST_REPO" config user.email test@example.test
git -C "$TEST_REPO" config user.name test
touch "$TEST_REPO/tracked"
git -C "$TEST_REPO" add tracked dev-tools/request-coderabbit-review.sh dev-tools/validation/check-coderabbit-review.py
git -C "$TEST_REPO" commit -qm initial

cat >"$MOCK_BIN/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$1 $2" == "repo view" ]]; then
  printf '%s\n' 'owner/repo'
  exit 0
fi
if [[ "$1 $2" == "pr view" ]]; then
  count=0
  [[ ! -f "$MOCK_STATE/pr-view-count" ]] || count="$(<"$MOCK_STATE/pr-view-count")"
  count="$((count + 1))"
  printf '%s\n' "$count" >"$MOCK_STATE/pr-view-count"
  head_sha='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
  state='OPEN'
  if [[ -f "$MOCK_STATE/pr-heads" ]]; then
    head_sha="$(sed -n "${count}p" "$MOCK_STATE/pr-heads")"
    [[ -n "$head_sha" ]] || head_sha="$(tail -n 1 "$MOCK_STATE/pr-heads")"
  fi
  if [[ -f "$MOCK_STATE/pr-states" ]]; then
    state="$(sed -n "${count}p" "$MOCK_STATE/pr-states")"
    [[ -n "$state" ]] || state="$(tail -n 1 "$MOCK_STATE/pr-states")"
  fi
  jq -n --arg state "$state" --arg headRefOid "$head_sha" '{state:$state,headRefOid:$headRefOid}'
  exit 0
fi
if [[ "$1 $2" == "api graphql" ]]; then
  comments='[]'
  if [[ -f "$MOCK_STATE/posted" ]]; then
    comments='[{"id":"IC_test","databaseId":101,"author":{"login":"owner"},"body":"@coderabbitai full review","createdAt":"2026-09-14T01:00:00Z","updatedAt":"2026-09-14T01:00:00Z","url":"https://example.test/comments/101"}]'
  fi
  jq -n --argjson comments "$comments" '{data:{repository:{pullRequest:{headRefOid:"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",commits:{nodes:[{commit:{oid:"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",committedDate:"2026-09-14T00:00:00Z"}}]},reviewThreads:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}},comments:{nodes:$comments,pageInfo:{hasNextPage:false,endCursor:null}},reviews:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}}}}}}'
  exit 0
fi
if [[ "$1" == "api" && "$2" == "repos/owner/repo/issues/42/comments" ]]; then
  [[ "$*" == *"--method POST"* ]]
  [[ "$*" == *"body=@coderabbitai full review"* ]]
  count=0
  [[ ! -f "$MOCK_STATE/count" ]] || count="$(<"$MOCK_STATE/count")"
  printf '%s\n' "$((count + 1))" >"$MOCK_STATE/count"
  touch "$MOCK_STATE/posted"
  printf '%s\n' '{"id":101,"created_at":"2026-09-14T01:00:00Z","html_url":"https://example.test/comments/101"}'
  exit 0
fi
printf 'unexpected gh arguments: %s\n' "$*" >&2
exit 1
EOF
chmod +x "$MOCK_BIN/gh"

export MOCK_STATE
expect_invalid_wait() {
  if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" dev-tools/request-coderabbit-review.sh 42 --repo owner/repo "$@") >"$TMP_DIR/invalid-wait.out" 2>&1; then
    exit 1
  fi
}

expect_invalid_wait --timeout 10
expect_invalid_wait --wait --timeout nan
expect_invalid_wait --wait --timeout inf
expect_invalid_wait --wait --timeout 0
expect_invalid_wait --wait --timeout 86401
expect_invalid_wait --wait --poll-interval 0
expect_invalid_wait --wait --poll-interval 301
expect_invalid_wait --wait --timeout 2 --poll-interval 3
expect_invalid_wait --wait --timeout 2 --timeout 3 --poll-interval 1
expect_invalid_wait --wait --poll-interval 1 --poll-interval 2
[[ ! -f "$MOCK_STATE/count" ]]

[[ ! -x "$TEST_REPO/dev-tools/validation/check-coderabbit-review.py" ]]
mv "$TEST_REPO/dev-tools/validation/check-coderabbit-review.py" \
  "$TEST_REPO/dev-tools/validation/check-coderabbit-review.py.missing"
if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" dev-tools/request-coderabbit-review.sh 42 --repo owner/repo) >"$TMP_DIR/missing-checker.out" 2>&1; then
  exit 1
fi
grep -q 'checker is not a readable regular file' "$TMP_DIR/missing-checker.out"
mv "$TEST_REPO/dev-tools/validation/check-coderabbit-review.py.missing" \
  "$TEST_REPO/dev-tools/validation/check-coderabbit-review.py"
chmod 000 "$TEST_REPO/dev-tools/validation/check-coderabbit-review.py"
if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" dev-tools/request-coderabbit-review.sh 42 --repo owner/repo) >"$TMP_DIR/unreadable-checker.out" 2>&1; then
  exit 1
fi
grep -q 'checker is not a readable regular file' "$TMP_DIR/unreadable-checker.out"
chmod 644 "$TEST_REPO/dev-tools/validation/check-coderabbit-review.py"

(cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" dev-tools/request-coderabbit-review.sh 42 --repo owner/repo) >"$TMP_DIR/first.out"
record="$TEST_REPO/.git/coderabbit-review-logs/hosted/owner_repo/pr-42/trigger.json"
[[ "$(<"$MOCK_STATE/count")" == "1" ]]
[[ "$(jq -r '.trigger.command' "$record")" == "@coderabbitai full review" ]]
[[ "$(jq -r '.trigger.id' "$record")" == "101" ]]
[[ "$(jq -r '.head_sha' "$record")" == "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" ]]
[[ "$(stat -c '%a' "$record")" == "600" ]]
[[ -z "$(find "$(dirname "$record")" -maxdepth 1 -name '.trigger.*' -print -quit)" ]]

if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" dev-tools/request-coderabbit-review.sh 42 --repo owner/repo) >"$TMP_DIR/second.out" 2>&1; then
  exit 1
fi
grep -q 'state: awaiting_response' "$TMP_DIR/second.out"
[[ "$(<"$MOCK_STATE/count")" == "1" ]]

record_dir="$(dirname "$record")"
rm -f "$record"
jq -n '{schema_version:1,status:"posting",repository:"owner/repo",pr_number:42,head_sha:"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",request_started_at:"2026-09-14T00:59:59Z"}' >"$record"
chmod 600 "$record"
if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" dev-tools/request-coderabbit-review.sh 42 --repo owner/repo) >"$TMP_DIR/crash.out" 2>&1; then
  exit 1
fi
grep -q 'state: unattributed' "$TMP_DIR/crash.out"
[[ "$(<"$MOCK_STATE/count")" == "1" ]]

printf '%s\n' '{"id":101,"created_at":"2026-09-14T01:00:00Z","html_url":"https://example.test/comments/101"}' >"$record_dir/post-response.pending.json"
if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" dev-tools/request-coderabbit-review.sh 42 --repo owner/repo) >"$TMP_DIR/reconcile.out" 2>&1; then
  exit 1
fi
grep -q 'state: ambiguous' "$TMP_DIR/reconcile.out"
[[ "$(jq -r '.status' "$record")" == "posted_boundary_unverified" ]]
[[ "$(jq -r '.trigger.id' "$record")" == "101" ]]
[[ "$(<"$MOCK_STATE/count")" == "1" ]]

rm -f "$record" "$record_dir/post-response.pending.json" "$MOCK_STATE/posted" \
  "$MOCK_STATE/count" "$MOCK_STATE/pr-view-count"
printf '%s\n' 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' >"$MOCK_STATE/pr-heads"
if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" dev-tools/request-coderabbit-review.sh 42 --repo owner/repo) >"$TMP_DIR/pre-post-head-race.out" 2>&1; then
  exit 1
fi
grep -q 'head changed after the review-state gate' "$TMP_DIR/pre-post-head-race.out"
[[ ! -f "$MOCK_STATE/count" ]]

rm -f "$MOCK_STATE/pr-view-count"
printf '%s\n' 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' >"$MOCK_STATE/pr-heads"
printf '%s\n' 'CLOSED' >"$MOCK_STATE/pr-states"
if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" dev-tools/request-coderabbit-review.sh 42 --repo owner/repo) >"$TMP_DIR/pre-post-close-race.out" 2>&1; then
  exit 1
fi
grep -q 'closed before the posting boundary' "$TMP_DIR/pre-post-close-race.out"
[[ ! -f "$MOCK_STATE/count" ]]

rm -f "$MOCK_STATE/pr-view-count"
rm -f "$MOCK_STATE/pr-states"
printf '%s\n' \
  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
  'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' >"$MOCK_STATE/pr-heads"
if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" dev-tools/request-coderabbit-review.sh 42 --repo owner/repo) >"$TMP_DIR/post-head-race.out" 2>&1; then
  exit 1
fi
grep -q 'changed across the posting boundary' "$TMP_DIR/post-head-race.out"
[[ "$(<"$MOCK_STATE/count")" == "1" ]]
[[ "$(jq -r '.status' "$record")" == "posted_boundary_changed" ]]
[[ "$(jq -r '.head_sha' "$record")" == "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" ]]
[[ "$(jq -r '.posting_boundary.observed_head_sha' "$record")" == "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" ]]

exec {held_fd}>"$record_dir/request.lock"
flock -n "$held_fd"
if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" dev-tools/request-coderabbit-review.sh 42 --repo owner/repo) >"$TMP_DIR/lock.out" 2>&1; then
  exit 1
fi
grep -q 'another hosted CodeRabbit request operation is in progress' "$TMP_DIR/lock.out"
[[ "$(<"$MOCK_STATE/count")" == "1" ]]

echo "hosted CodeRabbit trigger wrapper contract checks passed"
