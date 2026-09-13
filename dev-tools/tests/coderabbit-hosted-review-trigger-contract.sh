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
chmod +x "$TEST_REPO/dev-tools/request-coderabbit-review.sh" "$TEST_REPO/dev-tools/validation/check-coderabbit-review.py"
git -C "$TEST_REPO" init -q
git -C "$TEST_REPO" config user.email test@example.test
git -C "$TEST_REPO" config user.name test
touch "$TEST_REPO/tracked"
git -C "$TEST_REPO" add tracked
git -C "$TEST_REPO" commit -qm initial

cat >"$MOCK_BIN/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$1 $2" == "repo view" ]]; then
  printf '%s\n' 'owner/repo'
  exit 0
fi
if [[ "$1 $2" == "pr view" ]]; then
  printf '%s\n' '{"state":"OPEN","headRefOid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
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
grep -q 'state: awaiting_response' "$TMP_DIR/reconcile.out"
[[ "$(jq -r '.status' "$record")" == "posted" ]]
[[ "$(jq -r '.trigger.id' "$record")" == "101" ]]
[[ "$(<"$MOCK_STATE/count")" == "1" ]]

exec {held_fd}>"$record_dir/request.lock"
flock -n "$held_fd"
if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" dev-tools/request-coderabbit-review.sh 42 --repo owner/repo) >"$TMP_DIR/lock.out" 2>&1; then
  exit 1
fi
grep -q 'another hosted CodeRabbit request operation is in progress' "$TMP_DIR/lock.out"
[[ "$(<"$MOCK_STATE/count")" == "1" ]]

echo "hosted CodeRabbit trigger wrapper contract checks passed"
