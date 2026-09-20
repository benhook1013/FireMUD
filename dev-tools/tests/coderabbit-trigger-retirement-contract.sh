#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

TEST_REPO="$TMP_DIR/repo"
MOCK_BIN="$TMP_DIR/bin"
MOCK_STATE="$TMP_DIR/mock-state"
mkdir -p "$TEST_REPO/dev-tools/validation" "$MOCK_BIN" "$MOCK_STATE"
export MOCK_STATE
cp "$ROOT_DIR/dev-tools/request-coderabbit-review.sh" "$TEST_REPO/dev-tools/request-coderabbit-review.sh"
cp "$ROOT_DIR/dev-tools/validation/check-coderabbit-review.py" "$TEST_REPO/dev-tools/validation/check-coderabbit-review.py"
chmod +x "$TEST_REPO/dev-tools/request-coderabbit-review.sh"
git -C "$TEST_REPO" init -q
git -C "$TEST_REPO" config user.email test@example.test
git -C "$TEST_REPO" config user.name test
touch "$TEST_REPO/tracked"
git -C "$TEST_REPO" add tracked dev-tools
git -C "$TEST_REPO" commit -qm initial

cat >"$MOCK_BIN/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

head_sha='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
trigger='{"databaseId":101,"author":{"login":"owner"},"body":"@coderabbitai full review","createdAt":"2026-09-14T01:00:00Z","updatedAt":"2026-09-14T01:00:00Z","url":"https://example.test/comments/101"}'
scenario="${MOCK_SCENARIO:-retirement}"

if [[ "$1 $2" == "repo view" ]]; then
  printf '%s\n' 'owner/repo'
  exit 0
fi
if [[ "$1 $2" == "pr view" ]]; then
  jq -n --arg head "$head_sha" '{state:"OPEN",headRefOid:$head}'
  exit 0
fi
if [[ "$1 $2" == "api graphql" ]]; then
  comments='[]'
  if [[ "$scenario" == "retirement" || "$scenario" == "retirement-active" || "$scenario" == "retirement-ambiguous" || "$scenario" == "retirement-later-active" || "$scenario" == "retirement-superseded" || "$scenario" == "retry" ]]; then
    comments="[$trigger]"
  fi
  if [[ "$scenario" == "retirement-active" ]]; then
    comments="$(jq -cn --argjson trigger "$trigger" '[$trigger] + [{databaseId:102,author:{login:"coderabbitai"},body:"Full review triggered",createdAt:"2026-09-14T01:00:01Z",updatedAt:"2026-09-14T01:00:01Z",url:"https://example.test/comments/102"}]')"
  elif [[ "$scenario" == "retirement-ambiguous" ]]; then
    comments="$(jq -cn --argjson trigger "$trigger" '[$trigger] + [{databaseId:103,author:{login:"other"},body:"@coderabbitai full review",createdAt:"2026-09-14T01:00:00Z",updatedAt:"2026-09-14T01:00:00Z",url:"https://example.test/comments/103"}]')"
  elif [[ "$scenario" == "retirement-later-active" || "$scenario" == "retirement-superseded" ]]; then
    comments="$(jq -cn --argjson trigger "$trigger" '[$trigger] + [
      {databaseId:102,author:{login:"coderabbitai"},body:"Full review triggered",createdAt:"2026-09-14T01:00:01Z",updatedAt:"2026-09-14T01:00:01Z",url:"https://example.test/comments/102"},
      {databaseId:103,author:{login:"owner"},body:"@coderabbitai full review",createdAt:"2026-09-14T03:00:00Z",updatedAt:"2026-09-14T03:00:00Z",url:"https://example.test/comments/103"}
    ]')"
  fi
  reviews='[]'
  if [[ "$scenario" == "retirement-superseded" ]]; then
    reviews="$(jq -cn --arg head "$head_sha" '[{databaseId:105,author:{login:"coderabbitai"},state:"COMMENTED",submittedAt:"2026-09-14T03:10:00Z",body:"<!-- walkthrough_start -->",commit:{oid:$head},url:"https://example.test/reviews/105"}]')"
  fi
  jq -n --arg head "$head_sha" --argjson comments "$comments" --argjson reviews "$reviews" '{data:{repository:{pullRequest:{headRefOid:$head,commits:{nodes:[{commit:{oid:$head,committedDate:"2026-09-14T02:00:00Z"}}]},reviewThreads:{nodes:[],pageInfo:{hasNextPage:false,endCursor:null}},comments:{nodes:$comments,pageInfo:{hasNextPage:false,endCursor:null}},reviews:{nodes:$reviews,pageInfo:{hasNextPage:false,endCursor:null}}}}}}'
  exit 0
fi
if [[ "$1" == "api" && "$2" == "repos/owner/repo/issues/42/comments" ]]; then
  [[ "$*" == *"--method POST"* ]]
  [[ "$*" == *"body=@coderabbitai full review"* ]]
  printf '%s\n' 1 >"$MOCK_STATE/post-count"
  jq -n '{id:104,created_at:"2026-09-14T03:00:00Z",html_url:"https://example.test/comments/104"}'
  exit 0
fi
echo "unexpected gh arguments: $*" >&2
exit 92
EOF
chmod +x "$MOCK_BIN/gh"

HEAD='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
CAPTURED='cccccccccccccccccccccccccccccccccccccccc'
PAYLOAD="$TMP_DIR/payload.json"
RECORD="$TEST_REPO/.git/coderabbit-review-logs/hosted/owner_repo/pr-42/trigger.json"
mkdir -p "$(dirname "$RECORD")"
jq -n --arg head "$HEAD" '{
  data:{repository:{pullRequest:{
    headRefOid:$head,
    commits:{nodes:[{commit:{oid:$head,committedDate:"2026-09-14T02:00:00Z"}}]},
    reviewThreads:{nodes:[]},
    comments:{nodes:[{
      databaseId:101,
      author:{login:"owner"},
      body:"@coderabbitai full review",
      createdAt:"2026-09-14T01:00:00Z",
      updatedAt:"2026-09-14T01:00:00Z",
      url:"https://example.test/comments/101"
    }]},
    reviews:{nodes:[]}
  }}}
}' >"$PAYLOAD"
jq -n --arg repo owner/repo --arg captured "$CAPTURED" '{
  schema_version:1,status:"posted",repository:$repo,pr_number:42,head_sha:$captured,
  trigger:{id:101,created_at:"2026-09-14T01:00:00Z",url:"https://example.test/comments/101",type:"full",command:"@coderabbitai full review"}
}' >"$RECORD"
cp "$RECORD" "$TMP_DIR/posted-record.json"

for separator in $'\u0085' $'\u2028' $'\u2029'; do
  if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" MOCK_SCENARIO=retirement \
    dev-tools/request-coderabbit-review.sh 42 --repo owner/repo \
    --retire-trigger 101 --expected-head-sha "$HEAD" --reason "operator${separator}reason") \
    >"$TMP_DIR/separator.out" 2>&1; then
    exit 1
  fi
done

(cd "$TEST_REPO" && python3 dev-tools/validation/check-coderabbit-review.py \
  --repo owner/repo --pr 42 --input "$PAYLOAD" --trigger-record "$RECORD" \
  --wait --timeout 0 --json >"$TMP_DIR/timeout.json") || true
[[ "$(jq -r '.trigger_state.state' "$TMP_DIR/timeout.json")" == "timed_out" ]]
[[ "$(jq -r '.status' "$RECORD")" == "timed_out" ]]
[[ "$(jq -r '.timeout.observed_state' "$RECORD")" == "awaiting_response" ]]

if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" MOCK_SCENARIO=retirement \
  dev-tools/request-coderabbit-review.sh 42 --repo owner/repo \
  --retire-trigger 999 --expected-head-sha "$HEAD" --reason "operator adjudication") \
  >"$TMP_DIR/identity.out" 2>&1; then
  exit 1
fi
grep -q 'trigger identity does not match' "$TMP_DIR/identity.out" || {
  cat "$TMP_DIR/identity.out" >&2
  exit 1
}

if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" MOCK_SCENARIO=retirement \
  dev-tools/request-coderabbit-review.sh 42 --repo owner/repo \
  --retire-trigger 101 --expected-head-sha "$CAPTURED" --reason "operator adjudication") \
  >"$TMP_DIR/head.out" 2>&1; then
  exit 1
fi
grep -q 'expected head SHA does not match' "$TMP_DIR/head.out" || {
  cat "$TMP_DIR/head.out" >&2
  exit 1
}

jq --arg head "$HEAD" '.head_sha=$head' "$RECORD" >"$TMP_DIR/current-head-record.json"
mv "$TMP_DIR/current-head-record.json" "$RECORD"
if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" MOCK_SCENARIO=retirement-active \
  dev-tools/request-coderabbit-review.sh 42 --repo owner/repo \
  --retire-trigger 101 --expected-head-sha "$HEAD" --reason "operator adjudication") \
  >"$TMP_DIR/active.out" 2>&1; then
  exit 1
fi
grep -q 'active review' "$TMP_DIR/active.out" || {
  cat "$TMP_DIR/active.out" >&2
  exit 1
}
jq --arg captured "$CAPTURED" '.head_sha=$captured' "$RECORD" >"$TMP_DIR/captured-head-record.json"
mv "$TMP_DIR/captured-head-record.json" "$RECORD"

if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" MOCK_SCENARIO=retirement-ambiguous \
  dev-tools/request-coderabbit-review.sh 42 --repo owner/repo \
  --retire-trigger 101 --expected-head-sha "$HEAD" --reason "operator adjudication") \
  >"$TMP_DIR/ambiguous.out" 2>&1; then
  exit 1
fi
grep -q 'ambiguous response evidence' "$TMP_DIR/ambiguous.out" || {
  cat "$TMP_DIR/ambiguous.out" >&2
  exit 1
}

cp "$TMP_DIR/posted-record.json" "$RECORD"
(cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" MOCK_SCENARIO=retirement-active \
  dev-tools/request-coderabbit-review.sh 42 --repo owner/repo \
  --retire-trigger 101 --expected-head-sha "$HEAD" \
  --reason "bounded wait timed out; captured-head review is stale" >"$TMP_DIR/retired.json")
[[ "$(jq -r 'has("trigger_record")' "$TMP_DIR/retired.json")" == "false" ]]
[[ "$(jq -r '.status' "$RECORD")" == "retired" ]]
[[ "$(jq -r '.trigger.id' "$RECORD")" == "101" ]]
[[ "$(jq -r '.head_sha' "$RECORD")" == "$CAPTURED" ]]
[[ "$(jq -r '.retirement.expected_head_sha' "$RECORD")" == "$HEAD" ]]
[[ "$(jq -r '.retirement.evidence.state' "$RECORD")" == "active" ]]

(cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" MOCK_SCENARIO=retry \
  dev-tools/request-coderabbit-review.sh 42 --repo owner/repo >"$TMP_DIR/retry.out")
[[ "$(cat "$MOCK_STATE/post-count")" == "1" ]]
[[ "$(jq -r '.trigger.id' "$RECORD")" == "104" ]]
[[ "$(jq -r '.status' "$(dirname "$RECORD")/trigger-101.json")" == "retired" ]]

cp "$TMP_DIR/posted-record.json" "$RECORD"
if (cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" MOCK_SCENARIO=retirement-later-active \
  dev-tools/request-coderabbit-review.sh 42 --repo owner/repo \
  --retire-trigger 101 --expected-head-sha "$HEAD" --reason "operator adjudication") \
  >"$TMP_DIR/later-active.out" 2>&1; then
  exit 1
fi
grep -q 'ambiguous response evidence' "$TMP_DIR/later-active.out"
[[ "$(jq -r '.status' "$RECORD")" == "posted" ]]

(cd "$TEST_REPO" && PATH="$MOCK_BIN:$PATH" MOCK_SCENARIO=retirement-superseded \
  dev-tools/request-coderabbit-review.sh 42 --repo owner/repo \
  --retire-trigger 101 --expected-head-sha "$HEAD" \
  --reason "a later full review completed on a newer head" >"$TMP_DIR/superseded.json")
[[ "$(jq -r '.status' "$RECORD")" == "retired" ]]
[[ "$(jq -r '.retirement.evidence.state' "$RECORD")" == "ambiguous" ]]
[[ "$(jq -r '.retirement.evidence.superseding_request_at' "$RECORD")" == "2026-09-14T03:00:00Z" ]]
[[ "$(jq -r '.retirement.evidence.superseding_review_finished_at' "$RECORD")" == "2026-09-14T03:10:00Z" ]]

echo "CodeRabbit trigger retirement contract checks passed"
