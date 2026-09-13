#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: dev-tools/request-coderabbit-review.sh <pull-request-number> [--repo <owner/name>] [--wait] [--timeout <seconds>] [--poll-interval <seconds>]

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
wait_args=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      repo="$2"
      shift 2
      ;;
    --wait)
      wait_args+=("--wait")
      shift
      ;;
    --timeout|--poll-interval)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      wait_args+=("$1" "$2")
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
for dependency in git gh jq flock mktemp python3; do
  command -v "$dependency" >/dev/null 2>&1 || die "required executable not found: $dependency"
done

source_root="$(git rev-parse --show-toplevel 2>/dev/null)" || die "run this command inside a Git worktree"
checker="$source_root/dev-tools/validation/check-coderabbit-review.py"
[[ -x "$checker" ]] || die "CodeRabbit review checker is not executable: $checker"
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
    .status = "posted" |
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

if [[ -f "$record" ]]; then
  if [[ "$(jq -r '.status // empty' "$record" 2>/dev/null || true)" == "posting" && -f "$response_pending" ]]; then
    finalize_post_response || die "saved GitHub response is incomplete; adjudicate before retrying (record: $record)"
  fi
  existing_json="$(python3 "$checker" --repo "$repo" --pr "$pr_number" --trigger-record "$record" --json 2>/dev/null)" || true
  existing_state="$(jq -er '.trigger_state.state' <<<"${existing_json:-}" 2>/dev/null || true)"
  if [[ "$existing_state" == "awaiting_response" || "$existing_state" == "active" || \
        "$existing_state" == "ambiguous" || "$existing_state" == "unattributed" || -z "$existing_state" ]]; then
    die "existing hosted review trigger requires completion or adjudication (state: ${existing_state:-unreadable}; record: $record)"
  fi
  if [[ "$existing_state" == "rate_limited" ]]; then
    cooldown="$(jq -r '.trigger_state.cooldown_until // empty' <<<"$existing_json")"
    [[ -n "$cooldown" ]] || die "existing rate limit has no attributable cooldown; adjudicate before retrying (record: $record)"
    if ! python3 - "$cooldown" <<'PY'
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

pr_json="$(gh pr view "$pr_number" --repo "$repo" --json state,headRefOid)" || die "could not read pull request metadata"
[[ "$(jq -r '.state' <<<"$pr_json")" == "OPEN" ]] || die "pull request is not open"
head_sha="$(jq -er '.headRefOid | select(type == "string" and test("^[0-9a-fA-F]{40}$"))' <<<"$pr_json")" ||
  die "pull request metadata has no exact head SHA"

gate_json="$(python3 "$checker" --repo "$repo" --pr "$pr_number" --json 2>/dev/null)" || true
[[ "$(jq -r '.retrigger_review_allowed // false' <<<"${gate_json:-}")" == "true" ]] ||
  die "live review state does not permit another hosted review request"

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
