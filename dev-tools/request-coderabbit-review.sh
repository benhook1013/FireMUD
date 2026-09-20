#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: dev-tools/request-coderabbit-review.sh <pull-request-number> [--repo <owner/name>] [--wait] [--timeout <seconds>] [--poll-interval <seconds>]
       dev-tools/request-coderabbit-review.sh <pull-request-number> [--repo <owner/name>] \
         --retire-trigger <comment-id> --expected-head-sha <sha> --reason <one-line-reason>

Posts exactly one included-quota @coderabbitai full review command, records its
immutable GitHub identity and pull-request head privately under the shared Git
common directory, and optionally blocks until the attributed request terminates.
EOF
}

die() {
  printf 'error: %s\n' "$1" >&2
  exit 1
}

if [[ ${1:-} == "--help" || ${1:-} == "-h" ]]; then
  usage
  exit 0
fi
[[ $# -ge 1 ]] || { usage >&2; exit 2; }

pr_number="$1"
shift
repo=""
wait_requested=false
wait_tuning_requested=false
timeout_seconds="1800"
poll_interval_seconds="20"
timeout_set=false
poll_interval_set=false
retire_trigger_id=""
expected_head_sha=""
retirement_reason=""
retirement_requested=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      repo="$2"
      shift 2
      ;;
    --wait)
      [[ "$wait_requested" == "false" ]] || die "--wait may be specified only once"
      wait_requested=true
      shift
      ;;
    --timeout)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      [[ "$timeout_set" == "false" ]] || die "--timeout may be specified only once"
      timeout_seconds="$2"
      timeout_set=true
      wait_tuning_requested=true
      shift 2
      ;;
    --poll-interval)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      [[ "$poll_interval_set" == "false" ]] || die "--poll-interval may be specified only once"
      poll_interval_seconds="$2"
      poll_interval_set=true
      wait_tuning_requested=true
      shift 2
      ;;
    --retire-trigger)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      [[ "$retirement_requested" == "false" ]] || die "--retire-trigger may be specified only once"
      retirement_requested=true
      retire_trigger_id="$2"
      shift 2
      ;;
    --expected-head-sha|--head-sha)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      [[ -z "$expected_head_sha" ]] || die "--expected-head-sha may be specified only once"
      expected_head_sha="$2"
      shift 2
      ;;
    --reason)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      [[ -z "$retirement_reason" ]] || die "--reason may be specified only once"
      retirement_reason="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "unsupported argument: $1"
      ;;
  esac
done

[[ "$pr_number" =~ ^[1-9][0-9]*$ ]] || die "pull request number must be a positive integer"
if [[ "$wait_tuning_requested" == "true" && "$wait_requested" != "true" ]]; then
  die "--timeout and --poll-interval require --wait"
fi
retirement_flags=0
[[ "$retirement_requested" == "true" ]] && retirement_flags=$((retirement_flags + 1))
[[ -n "$expected_head_sha" ]] && retirement_flags=$((retirement_flags + 1))
[[ -n "$retirement_reason" ]] && retirement_flags=$((retirement_flags + 1))
if [[ "$retirement_flags" != "0" && "$retirement_flags" != "3" ]]; then
  die "--retire-trigger, --expected-head-sha, and --reason must be supplied together"
fi
if [[ "$retirement_requested" == "true" && "$wait_requested" == "true" ]]; then
  die "--retire-trigger cannot be combined with --wait"
fi
if [[ "$retirement_requested" == "true" ]]; then
  [[ "$retire_trigger_id" =~ ^[1-9][0-9]*$ ]] || die "trigger comment ID must be a positive integer"
  [[ "$expected_head_sha" =~ ^[0-9a-fA-F]{40}$ ]] || die "expected head SHA must be exactly 40 hexadecimal characters"
  if ! python3 - "$retirement_reason" <<'PY'
import sys

reason = sys.argv[1]
valid = (
    bool(reason.strip())
    and len(reason) <= 240
    and all(
        ord(character) >= 0x20
        and ord(character) not in {0x7F, 0x85, 0x2028, 0x2029}
        for character in reason
    )
)
raise SystemExit(0 if valid else 1)
PY
  then
    die "retirement reason must be non-empty, one line, and at most 240 characters"
  fi
fi
if ! python3 - "$timeout_seconds" "$poll_interval_seconds" <<'PY'
import math
import sys

try:
    timeout = float(sys.argv[1])
    poll_interval = float(sys.argv[2])
except ValueError:
    raise SystemExit(1) from None
valid = (
    math.isfinite(timeout)
    and math.isfinite(poll_interval)
    and 1 <= timeout <= 86400
    and 0.1 <= poll_interval <= 300
    and poll_interval <= timeout
)
raise SystemExit(0 if valid else 1)
PY
then
  die "wait values must be finite numbers with timeout in [1, 86400], poll interval in [0.1, 300], and poll interval no greater than timeout"
fi
wait_args=()
if [[ "$wait_requested" == "true" ]]; then
  wait_args=("--wait" "--timeout" "$timeout_seconds" "--poll-interval" "$poll_interval_seconds")
fi
for dependency in git gh jq flock mktemp python3; do
  command -v "$dependency" >/dev/null 2>&1 || die "required executable not found: $dependency"
done

source_root="$(git rev-parse --show-toplevel 2>/dev/null)" || die "run this command inside a Git worktree"
checker="$source_root/dev-tools/validation/check-coderabbit-review.py"
[[ -f "$checker" && -r "$checker" ]] || die "CodeRabbit review checker is not a readable regular file: $checker"
if [[ -z "$repo" ]]; then
  repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner)" || die "could not infer repository; pass --repo owner/name"
fi
[[ "$repo" =~ ^[^/]+/[^/]+$ ]] || die "repository must be owner/name"

git_common_dir="$(git -C "$source_root" rev-parse --git-common-dir)"
if [[ "$git_common_dir" != /* ]]; then
  git_common_dir="$source_root/$git_common_dir"
fi
record_dir="$git_common_dir/coderabbit-review-logs/hosted/${repo//\//_}/pr-$pr_number"
mkdir -p "$record_dir"
chmod 700 "$git_common_dir/coderabbit-review-logs" "$git_common_dir/coderabbit-review-logs/hosted" \
  "$git_common_dir/coderabbit-review-logs/hosted/${repo//\//_}" "$record_dir"
record="$record_dir/trigger.json"
response_pending="$record_dir/post-response.pending.json"
lock_file="$record_dir/request.lock"
exec {lock_fd}>"$lock_file"
chmod 600 "$lock_file"
flock -n "$lock_fd" || die "another hosted CodeRabbit request operation is in progress for $repo#$pr_number"

if [[ "$retirement_requested" == "true" ]]; then
  [[ -f "$record" ]] || die "no current hosted review trigger record exists for $repo#$pr_number"
  retirement_json="$(python3 "$checker" --repo "$repo" --pr "$pr_number" \
    --trigger-record "$record" --retire-trigger "$retire_trigger_id" \
    --expected-head-sha "$expected_head_sha" --reason "$retirement_reason" --json)" || true
  [[ -n "$retirement_json" ]] || die "trigger retirement evidence could not be read; no record was changed"
  if ! jq -e '
    .operation == "retire_trigger" and
    .status == "retired" and
    (.trigger_comment_id | type == "number" and floor == . and . > 0) and
    (.expected_head_sha | type == "string" and test("^[0-9a-fA-F]{40}$"))
  ' <<<"$retirement_json" >/dev/null; then
    error_message="$(jq -r '.error // empty' <<<"$retirement_json" 2>/dev/null || true)"
    if [[ -n "$error_message" ]]; then
      die "trigger retirement refused: $error_message"
    fi
    die "trigger retirement refused"
  fi
  jq . <<<"$retirement_json"
  exit 0
fi

finalize_post_response() {
  jq -e '
    .id | type == "number" and floor == . and . > 0
  ' "$response_pending" >/dev/null &&
    jq -e '.created_at | type == "string" and length > 0' "$response_pending" >/dev/null &&
    jq -e '.html_url | type == "string" and length > 0' "$response_pending" >/dev/null ||
    return 1
  local finalized_tmp
  finalized_tmp="$(mktemp "$record_dir/.trigger.XXXXXX")"
  jq --slurpfile response "$response_pending" '
    .status = "posted_boundary_unverified" |
    .trigger = {
      id: $response[0].id,
      created_at: $response[0].created_at,
      url: $response[0].html_url,
      type: "full",
      command: "@coderabbitai full review"
    }
  ' "$record" >"$finalized_tmp"
  chmod 600 "$finalized_tmp"
  mv "$finalized_tmp" "$record"
}

mark_posting_boundary() {
  local status="$1"
  local observed_state="$2"
  local observed_head="$3"
  local boundary_tmp
  boundary_tmp="$(mktemp "$record_dir/.trigger.XXXXXX")"
  jq --arg status "$status" --arg observed_state "$observed_state" \
    --arg observed_head "$observed_head" \
    '.status = $status | .posting_boundary = {observed_state: $observed_state, observed_head_sha: $observed_head}' \
    "$record" >"$boundary_tmp"
  chmod 600 "$boundary_tmp"
  mv "$boundary_tmp" "$record"
}

if [[ -f "$record" ]]; then
  if [[ "$(jq -r '.status // empty' "$record" 2>/dev/null || true)" == "posting" && -f "$response_pending" ]]; then
    finalize_post_response || die "saved GitHub response is incomplete; adjudicate before retrying (record: $record)"
  fi
  existing_json="$(python3 "$checker" --repo "$repo" --pr "$pr_number" --trigger-record "$record" --json 2>/dev/null)" || true
  existing_state="$(jq -er '.trigger_state.state' <<<"${existing_json:-}" 2>/dev/null || true)"
  if [[ "$existing_state" == "awaiting_response" ]]; then
    trigger_id="$(jq -r '.trigger_state.trigger_comment_id // "unknown"' <<<"$existing_json")"
    trigger_created_at="$(jq -r '.trigger_state.trigger_created_at // "unknown"' <<<"$existing_json")"
    trigger_age_seconds="$(jq -r '.trigger_state.age_seconds // "unknown"' <<<"$existing_json")"
    printf 'WARNING: Hosted CodeRabbit trigger %s has no attributable terminal response; age %s seconds (created %s). Manual Overseer adjudication required before retrying.\n' \
      "$trigger_id" "$trigger_age_seconds" "$trigger_created_at" >&2
    die "existing hosted review trigger requires completion or adjudication (state: $existing_state; record: $record)"
  fi
  if [[ "$existing_state" == "timed_out" ]]; then
    trigger_id="$(jq -r '.trigger_state.trigger_comment_id // "unknown"' <<<"$existing_json")"
    die "existing hosted review trigger timed out and requires explicit retirement (trigger: $trigger_id; record: $record)"
  fi
  if [[ "$existing_state" == "active" || "$existing_state" == "ambiguous" || \
        "$existing_state" == "unattributed" || -z "$existing_state" ]]; then
    die "existing hosted review trigger requires completion or adjudication (state: ${existing_state:-unreadable}; record: $record)"
  fi
  if [[ "$existing_state" == "rate_limited" ]]; then
    cooldown="$(jq -r '.trigger_state.cooldown_until // empty' <<<"$existing_json")"
    if [[ -z "$cooldown" ]]; then
      response_at="$(jq -r '.trigger_state.response_created_at // empty' <<<"$existing_json")"
      attributed="$(jq -r '.trigger_state.attributed // false' <<<"$existing_json")"
      [[ "$attributed" == "true" && -n "$response_at" ]] ||
        die "rate limit has no verified terminal response or cooldown (record: $record)"
      if ! python3 - "$response_at" <<'PY'
from datetime import datetime, timedelta, timezone
import sys

response_at = datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00"))
raise SystemExit(0 if response_at + timedelta(hours=1) <= datetime.now(timezone.utc) else 1)
PY
      then
        die "rate limit has no stated cooldown; retry only one hour after its terminal response"
      fi
    elif ! python3 - "$cooldown" <<'PY'
from datetime import datetime, timezone
import sys

cooldown = datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00"))
raise SystemExit(0 if cooldown <= datetime.now(timezone.utc) else 1)
PY
    then
      die "included-quota cooldown is still active until $cooldown"
    fi
  fi
  prior_id="$(jq -r '.trigger.id // "reservation"' "$record")"
  mv "$record" "$record_dir/trigger-$prior_id.json"
  rm -f "$response_pending"
fi

gate_json="$(python3 "$checker" --repo "$repo" --pr "$pr_number" --json 2>/dev/null)" || true
[[ "$(jq -r '.retrigger_review_allowed // false' <<<"${gate_json:-}")" == "true" ]] ||
  die "live review state does not permit another hosted review request"
gate_head_sha="$(jq -er '.head_sha | select(type == "string" and test("^[0-9a-fA-F]{40}$"))' <<<"$gate_json")" ||
  die "live review state has no exact head SHA"

pr_json="$(gh pr view "$pr_number" --repo "$repo" --json state,headRefOid)" || die "could not refresh pull request metadata at the posting boundary"
[[ "$(jq -r '.state' <<<"$pr_json")" == "OPEN" ]] || die "pull request closed before the posting boundary"
posting_head_sha="$(jq -er '.headRefOid | select(type == "string" and test("^[0-9a-fA-F]{40}$"))' <<<"$pr_json")" ||
  die "pull request metadata has no exact head SHA"
[[ "${posting_head_sha,,}" == "${gate_head_sha,,}" ]] ||
  die "pull request head changed after the review-state gate; rerun against the new head"
head_sha="$gate_head_sha"

reservation_tmp="$(mktemp "$record_dir/.trigger.XXXXXX")"
request_started_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
jq -n --arg repository "$repo" --argjson pr_number "$pr_number" --arg head_sha "$head_sha" \
  --arg request_started_at "$request_started_at" \
  '{schema_version:1,status:"posting",repository:$repository,pr_number:$pr_number,head_sha:$head_sha,request_started_at:$request_started_at}' \
  >"$reservation_tmp"
chmod 600 "$reservation_tmp"
mv "$reservation_tmp" "$record"

rm -f "$response_pending"
umask 077
if ! gh api "repos/$repo/issues/$pr_number/comments" --method POST \
  -f 'body=@coderabbitai full review' >"$response_pending"; then
  die "GitHub rejected the review command; durable posting reservation retained at $record"
fi
trigger_id="$(jq -er '.id | select(type == "number" and floor == . and . > 0)' "$response_pending")" ||
  die "GitHub response has no immutable numeric comment ID; reservation retained at $record"
trigger_created_at="$(jq -er '.created_at | select(type == "string" and length > 0)' "$response_pending")" ||
  die "GitHub response has no immutable creation time; reservation retained at $record"
trigger_url="$(jq -er '.html_url | select(type == "string" and length > 0)' "$response_pending")" ||
  die "GitHub response has no comment URL; reservation retained at $record"
finalize_post_response || die "GitHub response could not be persisted; reservation retained at $record"
if ! post_pr_json="$(gh pr view "$pr_number" --repo "$repo" --json state,headRefOid)"; then
  mark_posting_boundary "posted_boundary_unverified" "unknown" "unknown"
  rm -f "$response_pending"
  die "could not verify pull request state after posting; trigger retained for adjudication at $record"
fi
post_state="$(jq -r '.state // "unknown"' <<<"$post_pr_json")"
post_head_sha="$(jq -r '.headRefOid // "unknown"' <<<"$post_pr_json")"
if [[ "$post_state" != "OPEN" || "${post_head_sha,,}" != "${head_sha,,}" ]]; then
  mark_posting_boundary "posted_boundary_changed" "$post_state" "$post_head_sha"
  rm -f "$response_pending"
  die "pull request state or head changed across the posting boundary; trigger retained for adjudication at $record"
fi
mark_posting_boundary "posted" "$post_state" "$post_head_sha"
rm -f "$response_pending"

printf 'trigger_record=%s\n' "$record"
printf 'trigger_comment_id=%s\n' "$trigger_id"
printf 'trigger_created_at=%s\n' "$trigger_created_at"
printf 'trigger_url=%s\n' "$trigger_url"
printf 'head_sha=%s\n' "$head_sha"

flock -u "$lock_fd"
if [[ ${#wait_args[@]} -gt 0 ]]; then
  exec python3 "$checker" --repo "$repo" --pr "$pr_number" --trigger-record "$record" "${wait_args[@]}"
fi
