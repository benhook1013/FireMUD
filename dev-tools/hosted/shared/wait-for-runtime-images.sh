#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 && $# -ne 3 ]]; then
  echo "usage: $0 <image_tag> | $0 <merge_sha> <base_sha> <head_sha>" >&2
  exit 1
fi

if [[ -z "${GH_TOKEN:-}" || -z "${GITHUB_REPOSITORY:-}" ]]; then
  echo "GH_TOKEN and GITHUB_REPOSITORY are required" >&2
  exit 1
fi

if [[ $# -eq 1 ]]; then
  wait_mode=branch
  branch_name=develop
  image_tag="$1"
  merge_sha=""
  base_sha=""
  head_sha="$1"
else
  wait_mode=pull-request
  branch_name=""
  merge_sha="$1"
  base_sha="$2"
  head_sha="$3"
  for value_name in merge_sha base_sha head_sha; do
    value="${!value_name}"
    if [[ ! "$value" =~ ^[0-9a-fA-F]{40}$ ]]; then
      echo "${value_name} must be exactly 40 hexadecimal characters" >&2
      exit 1
    fi
    printf -v "$value_name" '%s' "${value,,}"
  done
  image_tag="pr-merge-${merge_sha}"
fi
timeout_seconds="${HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS:-${PREVIEW_IMAGE_WAIT_TIMEOUT_SECONDS:-1800}}"
sleep_seconds="${HOSTED_IMAGE_WAIT_SLEEP_SECONDS:-${PREVIEW_IMAGE_WAIT_SLEEP_SECONDS:-10}}"
missing_workflow_timeout_seconds="${HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS:-${PREVIEW_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS:-180}}"
publisher_timeout_seconds="${HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS:-${timeout_seconds}}"
start_epoch="${SECONDS}"
deadline=$((SECONDS + timeout_seconds))

fetch_workflow_runs() {
  local endpoint="$1"
  local wait_context="$2"
  local remaining_seconds="${3:-}"
  local payload
  local -a bound=()
  if [[ -n "$remaining_seconds" ]]; then
    (( remaining_seconds > 0 )) || return 1
    bound=(timeout --signal=KILL "$remaining_seconds")
  fi

  if ! payload="$("${bound[@]}" gh api --paginate --slurp "${endpoint}")"; then
    printf 'GitHub API poll failed while %s; retrying within the existing wait deadline.\n' \
      "${wait_context}" >&2
    return 1
  fi

  # REST pages are separate observations. Reject gaps, duplicates and changing
  # totals before any caller infers absence from the listed runs.
  if ! jq -se '
      length == 1 and (.[0] |
        type == "array" and length > 0 and
        all(.[]; type == "object" and (.workflow_runs | type == "array")) and
        (.[0].total_count) as $total |
        ($total | type == "number" and floor == . and . >= 0) and
        length == ([1, (($total / 100) | ceil)] | max) and
        all(to_entries[]; .key as $page | .value |
          .total_count == $total and
          (.workflow_runs | length) == ([100, $total - ($page * 100)] | min)) and
        [.[].workflow_runs[] | .id] as $ids |
        all($ids[]; type == "number" and floor == . and . > 0) and
        ($ids | unique | length) == $total)
    ' <<<"$payload" >/dev/null; then
    printf 'GitHub runtime-image producer inventory is incomplete or inconsistent while %s.\n' \
      "$wait_context" >&2
    return 1
  fi

  printf '%s' "${payload}"
}

read_run_state() {
  python3 -c '
import json
import sys
import re
from datetime import datetime, timezone

wait_mode, image_tag, merge_sha, base_sha, head_sha = sys.argv[1:6]
excluded_id = sys.argv[6] if len(sys.argv) > 6 else ""
selection = sys.argv[7] if len(sys.argv) > 7 else ""
alternates = selection in {"alternates", "active-alternates"}
branch_alternates = selection == "branch-alternates"
try:
    payload = json.load(sys.stdin)
except json.JSONDecodeError:
    raise SystemExit(1)
pages = payload if isinstance(payload, list) else [payload]
if not pages or any(
    not isinstance(page, dict) or not isinstance(page.get("workflow_runs"), list)
    for page in pages
):
    raise SystemExit(1)
workflow_runs = [
    run
    for page in pages
    for run in page["workflow_runs"]
]
if any(not isinstance(run, dict) for run in workflow_runs):
    raise SystemExit(1)

def is_matching_run(run):
    if wait_mode == "branch":
        return (
            run.get("event") == "workflow_run"
            and run.get("display_title") == f"Build Runtime Images trusted-branch branch-develop sha-{head_sha}"
        ) or (
            run.get("event") == "workflow_dispatch"
            and run.get("display_title") == "Build Runtime Images develop"
            and run.get("head_branch") == "develop"
            and run.get("head_sha") == head_sha
            and run.get("path") in {
                ".github/workflows/runtime-images.yml",
                ".github/workflows/runtime-images.yml@develop",
                ".github/workflows/runtime-images.yml@refs/heads/develop",
            }
        )

    # A base refresh is dispatched as the typed pr-runtime-base-refresh event;
    # the workflow-run API exposes its source only as repository_dispatch.
    if run.get("event") not in {"pull_request", "repository_dispatch"}:
        return False
    display_title = run.get("display_title", "")
    tokens = display_title.split()
    return (
        display_title.startswith("Build Runtime Images secure-pr-artifact ")
        and f"base-{base_sha}" in tokens
        and f"head-{head_sha}" in tokens
        and f"merge-{merge_sha}" in tokens
        and display_title.endswith(" mode-required")
    )

matching_runs = [run for run in workflow_runs if is_matching_run(run)]
if excluded_id and selection == "selected":
    matching_runs = [run for run in matching_runs if str(run.get("id")) == excluded_id]
    if len(matching_runs) > 1:
        raise SystemExit(1)
elif excluded_id:
    matching_runs = [run for run in matching_runs if str(run.get("id")) != excluded_id]
    if selection == "active-alternates":
        active_statuses = {"requested", "queued", "in_progress", "waiting", "pending"}
        active_runs = []
        for run in matching_runs:
            status, conclusion = run.get("status"), run.get("conclusion")
            if status == "completed" and isinstance(conclusion, str) and conclusion:
                continue
            if status not in active_statuses or conclusion not in (None, ""):
                raise SystemExit(1)
            active_runs.append(run)
        matching_runs = active_runs
    elif not branch_alternates:
        matching_runs = [run for run in matching_runs if run.get("status") == "completed"
                         and run.get("conclusion") == "success"]
if not matching_runs:
    print("missing")
    raise SystemExit(0)

ranked = sorted(matching_runs, key=lambda item: item.get("created_at", ""), reverse=True)
# Every tab-delimited field must be nonempty so Bash IFS cannot shift positions.
def clean_field(value):
    return isinstance(value, str) and bool(value) and not any(character.isspace() or ord(character) < 32 or ord(character) == 127 for character in value)

def format_run(run):
    run_id = run.get("id")
    if type(run_id) is not int or run_id <= 0:
        raise SystemExit(1)
    if not all(clean_field(run.get(field)) for field in ("status", "html_url", "event")):
        raise SystemExit(1)
    conclusion = run.get("conclusion")
    if conclusion is None or conclusion == "":
        if run["status"] == "completed":
            raise SystemExit(1)
        conclusion = "pending"
    elif not clean_field(conclusion):
        raise SystemExit(1)
    created_at = run.get("created_at")
    if not isinstance(created_at, str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", created_at):
        raise SystemExit(1)
    try:
        created = datetime.strptime(created_at, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError:
        raise SystemExit(1)
    if created > datetime.now(timezone.utc):
        raise SystemExit(1)
    return (
        "found\t{}\t{}\t{}\t{}\t{}\t{}".format(
            run_id,
            run["status"],
            conclusion,
            run["html_url"],
            run["event"],
            created_at,
        )
    )

# The branch fallback retains its one-page rule. PR receipt verification
# walks the complete matching inventory within the overall time budget.
limit = 100 if branch_alternates else len(ranked) if alternates else 1
seen = set()
for run in ranked[:limit]:
    line = format_run(run)
    if run["id"] in seen:
        if branch_alternates:
            raise SystemExit(1)
        continue
    print(line)
    seen.add(run["id"])
if (alternates or branch_alternates) and len(ranked) > limit:
    print("incomplete")
' "${wait_mode}" "${image_tag}" "${merge_sha}" "${base_sha}" "${head_sha}" "$@"
}

fetch_publisher_runs() {
  local remaining_seconds="${4:-}"
  local -a bound=()
  if [[ -n "$remaining_seconds" ]]; then
    (( remaining_seconds > 0 )) || return 1
    bound=(timeout --signal=KILL "$remaining_seconds")
  fi
  "${bound[@]}" python3 - "${GITHUB_REPOSITORY}" "$1" "$2" "$3" <<'PY_PUBLISHERS'
import json
import re
import subprocess
import sys
from datetime import datetime, timezone

repository, event, lower_text, upper_text = sys.argv[1:]

def unavailable(reason):
    print(f"Publisher coverage unavailable: {reason}.", file=sys.stderr)
    raise SystemExit(1)

def timestamp(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", value):
        unavailable("creation-window timestamp is malformed")
    try:
        return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError:
        unavailable("creation-window timestamp is invalid")

lower, upper = timestamp(lower_text), timestamp(upper_text)
if lower > upper or event not in {"workflow_run", "repository_dispatch"}:
    unavailable("creation window or event is invalid")
endpoint = f"repos/{repository}/actions/workflows/publish-pr-runtime-images.yml/runs?event={event}&created={lower_text}..{upper_text}&per_page=100"
records, seen, total = [], set(), None
page_number = 1
while True:
    result = subprocess.run(["gh", "api", f"{endpoint}&page={page_number}"], capture_output=True, text=True)
    if result.returncode:
        unavailable("exact-window GitHub API lookup failed")
    try:
        page = json.loads(result.stdout)
    except json.JSONDecodeError:
        unavailable("exact-window API response is malformed")
    if not isinstance(page, dict) or type(page.get("total_count")) is not int or not isinstance(page.get("workflow_runs"), list):
        unavailable("exact-window API envelope is malformed")
    count = page["total_count"]
    if count < 0 or count > 1000:
        unavailable("exact-window run count exceeds the 1000-record filtered API ceiling")
    if total is None:
        total = count
    elif total != count:
        unavailable("exact-window page totals changed")
    runs = page["workflow_runs"]
    if len(runs) != min(100, total - len(records)):
        unavailable("exact-window page coverage is incomplete")
    for run in runs:
        if not isinstance(run, dict) or type(run.get("id")) is not int or run["id"] <= 0 or run["id"] in seen or run.get("event") != event:
            unavailable("exact-window run identity or event is malformed")
        if not lower <= timestamp(run.get("created_at")) <= upper:
            unavailable("run creation time is outside the exact window")
        seen.add(run["id"])
        records.append(run)
    if len(records) == total:
        break
    page_number += 1
print(json.dumps({"workflow_runs": records}))
PY_PUBLISHERS
}

read_publisher_state() {
  python3 -c '
import json
import re
import sys

wait_mode, image_tag, merge_sha, base_sha, head_sha, source_run_id, source_event = sys.argv[1:8]
if not re.fullmatch(r"[1-9][0-9]*", source_run_id):
    raise SystemExit(1)
if source_event not in {"pull_request", "repository_dispatch"}:
    raise SystemExit(1)
try:
    payload = json.load(sys.stdin)
except json.JSONDecodeError:
    raise SystemExit(1)
pages = payload if isinstance(payload, list) else [payload]
if not pages or any(
    not isinstance(page, dict) or not isinstance(page.get("workflow_runs"), list)
    for page in pages
):
    raise SystemExit(1)
workflow_runs = [
    run
    for page in pages
    for run in page["workflow_runs"]
]
if any(not isinstance(run, dict) for run in workflow_runs):
    raise SystemExit(1)
if wait_mode == "pull-request":
    matching_runs = []
    for run in workflow_runs:
        tokens = run.get("display_title", "").split()
        tuple_matches = (
            tokens[:8] == [
                "Publish", "PR", "Runtime", "Images", "Build", "Runtime", "Images",
                "secure-pr-artifact",
            ]
            and re.fullmatch(r"pr-[1-9][0-9]{0,50}", tokens[8] if len(tokens) > 8 else "")
            and len(tokens) >= 13
            and tokens[9] == f"base-{base_sha}"
            and tokens[10] == f"head-{head_sha}"
            and tokens[11] == f"merge-{merge_sha}"
            and tokens[12] == "mode-required"
        )
        if run.get("event") == "repository_dispatch":
            if run.get("display_title") == f"Publish PR Runtime Images source-run-{source_run_id}":
                matching_runs.append(run)
        elif run.get("event") == "workflow_run" and tuple_matches and (
            (source_event == "pull_request" and len(tokens) == 13)
            or (source_event == "repository_dispatch" and tokens[13:] == [f"source-run-{source_run_id}"])
        ):
            matching_runs.append(run)
else:
    matching_runs = []
selectors = sys.argv[8:]
all_statuses = selectors[-1:] == ["all-statuses"]
all_candidates = all_statuses or selectors[-1:] == ["all"]
if all_candidates:
    selectors.pop()
polling = selectors == ["poll"]
if selectors and not polling:
    event, title = selectors
    matching_runs = [run for run in matching_runs if run.get("event") == event
                     and run.get("display_title") == title
                     and (all_statuses or (run.get("status") == "completed" and run.get("conclusion") == "success"))]
if not matching_runs:
    print("missing")
    raise SystemExit(0)

def poll_priority(run):
    # A completed success still needs exact publication proof. Active runs
    # keep the wait open even if a newer sibling publisher has failed.
    if run.get("status") != "completed":
        return 1
    return 2 if run.get("conclusion") == "success" else 0

ranked = sorted(matching_runs,
                key=lambda run: (poll_priority(run) if polling else 0, run.get("created_at", "")),
                reverse=True)
for run in ranked if all_candidates else ranked[:1]:
    print(
        "found\t{}\t{}\t{}\t{}".format(
            run.get("id", ""),
            run.get("status", ""),
            (run.get("conclusion") or "pending") if all_statuses else run.get("conclusion", ""),
            run.get("html_url", ""),
        )
    )
' "${wait_mode}" "${image_tag}" "${merge_sha}" "${base_sha}" "${head_sha}" "$@"
}

fetch_alternate_run_metadata() {
  local lookup_deadline="$1"
  local remaining=$((lookup_deadline - SECONDS))
  (( remaining > 0 )) || return 1
  timeout --signal=KILL "$remaining" gh api "$2"
}

publisher_event_budget() {
  local deadline="$1" events_left="$2"
  local remaining=$((deadline - SECONDS))
  local share=$(((remaining + events_left - 1) / events_left))
  # Reserve a share for each remaining path and avoid long per-event stalls.
  printf '%s\n' "$((share < 45 ? share : 45))"
}

publisher_inventory_budget() {
  local share="$1"
  # Leave time for receipt reads in the same share, even if discovery is slow.
  if (( share > 30 )); then
    printf '15\n'
  else
    printf '%s\n' "$(((share + 1) / 2))"
  fi
}

read_handoff_metadata() {
  local lookup_deadline="$1" endpoint="$2" proof_origin="$3"
  local response exit_status
  local http_error='(^|[[:space:](])http[[:space:]]+([1-5][0-9]{2})([[:space:])]|$)'
  local transport_error='could[[:space:]]not[[:space:]]resolve|failed[[:space:]]to[[:space:]](connect|make[[:space:]]request)|error[[:space:]]connecting|unable[[:space:]]to[[:space:]]connect|connection[[:space:]](reset|refused|timed|closed)|network[[:space:]]is[[:space:]]unreachable|timed[[:space:]]out|timeout|temporary[[:space:]]failure|tls|eof'
  if [[ "$proof_origin" != "handoff" ]]; then
    fetch_alternate_run_metadata "$lookup_deadline" "$endpoint" || return 1
    return 0
  fi
  if response="$(fetch_alternate_run_metadata "$lookup_deadline" "$endpoint" 2>&1)"; then
    printf '%s' "$response"
    return 0
  else
    exit_status=$?
  fi
  # A received HTTP status takes precedence over transport error text.
  response="${response,,}"
  if [[ "$response" =~ $http_error ]]; then
    local http_status="${BASH_REMATCH[2]}"
    if [[ "$http_status" == 429 || "$http_status" == 5?? ]] ||
      [[ "$http_status" == 403 && "$response" =~ (rate[[:space:]-]*limit|abuse) ]]; then
      return 2
    fi
    return 1
  fi
  if (( exit_status == 124 || exit_status == 137 || SECONDS >= lookup_deadline )) ||
    [[ "$response" =~ $transport_error ]]; then
    return 2
  fi
  return 1
}

# Resolve exactly one unexpired artifact. Producer proof can retain the ID and
# digest of an expired row or use the authenticated publisher's receipt when
# GitHub no longer lists the producer artifact. Status 2 is an unavailable read.
read_exact_run_artifact() {
  local deadline="$1" run_id="$2" name="$3" producer="${4:-false}"
  local inventory
  inventory="$(fetch_alternate_run_metadata "$deadline" "repos/${GITHUB_REPOSITORY}/actions/runs/${run_id}/artifacts?name=${name}&per_page=100")" || return 2
  jq -cer --argjson run "$run_id" --arg name "$name" --argjson producer "$producer" '
      select((.artifacts | type == "array") and .total_count == (.artifacts | length))
      | if $producer and .total_count == 0 then {id:null, digest:null}
        else select(.total_count == 1) | .artifacts[0]
          | select(.name == $name and .workflow_run.id == $run)
          | select(.id | type == "number" and floor == . and . > 0)
          | select(.digest | type == "string" and test("\\Asha256:[0-9a-f]{64}\\z"))
          | select(.expired == false or ($producer and .expired == true))
          | {id, digest}
        end' <<<"$inventory" || return 1
}

verify_exact_publication_receipt() {
  local deadline="$1" receipt_id="$2" receipt_digest="$3" expected="$4" remaining
  remaining=$((deadline - SECONDS))
  (( remaining > 0 )) || return 1
  timeout --signal=KILL "$remaining" gh api "repos/${GITHUB_REPOSITORY}/actions/artifacts/${receipt_id}/zip" |
    python3 -c '
import hashlib
import io
import json
import re
import sys
import zipfile

def closed_object(pairs):
    obj = dict(pairs)
    if len(obj) != len(pairs):
        raise ValueError("duplicate receipt key")
    return obj

try:
    data = sys.stdin.buffer.read(65537)
    if not data or len(data) > 65536 or "sha256:" + hashlib.sha256(data).hexdigest() != sys.argv[1]:
        raise ValueError("invalid retained archive digest")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        if (len(entries) != 1 or entries[0].filename != "exact-pr-runtime-publication.json"
                or not 0 < entries[0].file_size <= 8192):
            raise ValueError("invalid receipt inventory")
        with archive.open(entries[0]) as stream:
            payload = stream.read(8193)
        if len(payload) > 8192:
            raise ValueError("invalid receipt size")
    receipt = json.loads(payload, object_pairs_hook=closed_object)
    expected = json.loads(sys.argv[2])
    if type(receipt) is not dict or set(receipt) != set(expected):
        raise ValueError("invalid receipt inventory")
    # In the confirmed-empty producer inventory case the publisher receipt
    # retains the artifact identity. When metadata exists, require equality.
    if expected["producerArtifactId"] is None and expected["producerArtifactDigest"] is None:
        artifact_id = receipt["producerArtifactId"]
        artifact_digest = receipt["producerArtifactDigest"]
        if (type(artifact_id) is not int or artifact_id <= 0
                or type(artifact_digest) is not str
                or re.fullmatch(r"sha256:[0-9a-f]{64}", artifact_digest) is None):
            raise ValueError("invalid producer artifact identity")
        expected["producerArtifactId"] = artifact_id
        expected["producerArtifactDigest"] = artifact_digest
    if any(
            type(receipt[key]) != type(value) or receipt[key] != value
            for key, value in expected.items()):
        raise ValueError("invalid exact attempt receipt")
except (OSError, TypeError, ValueError, RuntimeError, zipfile.BadZipFile):
    sys.exit(1)
' "$receipt_digest" "$expected"
}

confirm_publication_source_attempt() {
  local lookup_deadline="$1" source_id="$2" workflow_id="$3" source_attempt="$4" source_title="$5" source_state="$6" source_branch="$7"
  local latest
  latest="$(fetch_alternate_run_metadata "$lookup_deadline" "repos/${GITHUB_REPOSITORY}/actions/runs/${source_id}")" || return 1
  jq -e --argjson id "$source_id" --argjson workflow "$workflow_id" --argjson attempt "$source_attempt" \
    --arg repo "$GITHUB_REPOSITORY" --arg title "$source_title" --arg head "$head_sha" --arg branch "$source_branch" '
    .id == $id and .workflow_id == $workflow and .run_attempt == $attempt
    and .repository.full_name == $repo and .head_repository.full_name == $repo
    and .display_title == $title and ((.head_branch // "") == $branch)
    and (.event != "pull_request" or .head_sha == $head)
  ' <<<"$latest" >/dev/null &&
    [[ "$(jq -c '{workflow_runs:[.]}' <<<"$latest" | read_run_state)" == "$source_state" ]]
}

# PR callbacks bind the immutable source tuple. Typed native and explicit
# publishers bind the exact source run ID as well as the tuple. Every path
# needs a positive publication step in the exact current attempt.
use_completed_duplicate_publication() {
  local state older_id older_created source_event inventory upper publisher_lines publisher_id publisher_status publisher_conclusion
  local source_state="$1" lookup_deadline="$2"
  local proof_origin="${3:-alternate}"
  local observed_inventory="${4:-}"
  local observed_event="${5:-}"
  local source source_attempt source_title source_branch callback_branch publisher_event expected_title
  local run attempt jobs latest publisher_path publisher_branch default_branch repository_info
  local source_workflow source_workflow_id source_pr producer_artifact publisher_artifact expected_receipt artifact_status
  local -a publisher_events=(workflow_run repository_dispatch)
  local pending_handoff=false publisher_seen=false publisher_selector=all
  [[ "$proof_origin" != "handoff" ]] || publisher_selector=all-statuses
  IFS=$'\t' read -r state older_id _ _ _ source_event older_created <<<"$source_state"
  [[ "$state" == "found" ]] || return 1
  source="$(fetch_alternate_run_metadata "$lookup_deadline" "repos/${GITHUB_REPOSITORY}/actions/runs/${older_id}")" || {
    if [[ "$proof_origin" == "handoff" ]]; then return 2; fi
    return 1
  }
  [[ "$(jq -c '{workflow_runs:[.]}' <<<"$source" | read_run_state)" == "$source_state" ]] || return 1
  source_title="$(jq -er '.display_title | select(type == "string")' <<<"$source")" || return 1
  source_branch="$(jq -er '(.head_branch // "") | select(type == "string")' <<<"$source")" || return 1
  source_attempt="$(jq -er --arg repo "$GITHUB_REPOSITORY" --arg head "$head_sha" '
    select(.repository.full_name == $repo and .head_repository.full_name == $repo)
    | select(.event != "pull_request" or .head_sha == $head)
    | .run_attempt | select(type == "number" and . >= 1 and floor == .)
  ' <<<"$source")" || return 1
  source_workflow="$(read_handoff_metadata "$lookup_deadline" "repos/${GITHUB_REPOSITORY}/actions/workflows/runtime-images.yml" "$proof_origin")" || return "$?"
  source_workflow_id="$(jq -ser '
    select(length == 1) | .[0]
    | select(.name == "Build Runtime Images" and .path == ".github/workflows/runtime-images.yml")
    | .id | select(type == "number" and floor == . and . > 0)
  ' <<<"$source_workflow")" || return 1
  jq -e --argjson workflow "$source_workflow_id" '
    .workflow_id == $workflow and (.path == ".github/workflows/runtime-images.yml"
       or ((.path | type == "string") and (.path | startswith(".github/workflows/runtime-images.yml@"))))
  ' <<<"$source" >/dev/null || return 1
  if [[ ! "$source_title" =~ ^Build\ Runtime\ Images\ secure-pr-artifact\ pr-([1-9][0-9]*)\ base- ]]; then
    return 1
  fi
  source_pr="${BASH_REMATCH[1]}"
  producer_artifact="$(read_exact_run_artifact "$lookup_deadline" "$older_id" "pr-runtime-images-${image_tag}-attempt-${source_attempt}" true)" || {
    artifact_status=$?
    if [[ "$proof_origin" == "handoff" && "$artifact_status" == 2 ]]; then return 2; fi
    return 1
  }
  [[ -z "$observed_event" ]] || publisher_events=("$observed_event")
  upper="$(date -u +%Y-%m-%dT%H:%M:%SZ)" || return 1
  repository_info="$(read_handoff_metadata "$lookup_deadline" "repos/${GITHUB_REPOSITORY}" "$proof_origin")" || return "$?"
  default_branch="$(jq -ser --arg repo "$GITHUB_REPOSITORY" '
    select(length == 1) | .[0] | select(.full_name == $repo) | .default_branch
    | select(type == "string" and test("^[A-Za-z0-9][A-Za-z0-9._/-]*$"))
  ' <<<"$repository_info")" || return 1
  local events_left=${#publisher_events[@]} event_share event_deadline
  for publisher_event in "${publisher_events[@]}"; do
    event_share="$(publisher_event_budget "$lookup_deadline" "$events_left")"
    events_left=$((events_left - 1))
    if (( event_share <= 0 )); then
      pending_handoff=true
      break
    fi
    event_deadline=$((SECONDS + event_share))
    (( event_deadline <= lookup_deadline )) || event_deadline="$lookup_deadline"
    if [[ "$publisher_event" == "workflow_run" ]]; then
      expected_title="Publish PR Runtime Images ${source_title}"
      [[ "$source_event" != "repository_dispatch" ]] || expected_title+=" source-run-${older_id}"
    else
      expected_title="Publish PR Runtime Images source-run-${older_id}"
    fi
    if [[ -n "$observed_inventory" ]]; then
      inventory="$observed_inventory"
    else
      if ! inventory="$(fetch_publisher_runs "$publisher_event" "$older_created" "$upper" \
        "$(publisher_inventory_budget "$event_share")")"; then
        [[ "$proof_origin" != "handoff" ]] || pending_handoff=true
        continue
      fi
    fi
    if ! publisher_lines="$(read_publisher_state "$older_id" "$source_event" "$publisher_event" "$expected_title" "$publisher_selector" <<<"$inventory")"; then
      [[ "$proof_origin" != "handoff" ]] || pending_handoff=true
      continue
    fi
    if [[ "$publisher_lines" == "missing" ]]; then
      continue
    fi
    publisher_seen=true
    while IFS=$'\t' read -r state publisher_id publisher_status publisher_conclusion _; do
      if [[ "$proof_origin" == "handoff" && "$state" == "found" &&
            "$publisher_id" =~ ^[1-9][0-9]*$ ]]; then
        case "$publisher_status/$publisher_conclusion" in
          queued/pending|requested/pending|pending/pending|waiting/pending|in_progress/pending|completed/success)
            pending_handoff=true
            ;;
        esac
      fi
      [[ "$state" == "found" && "$publisher_id" =~ ^[1-9][0-9]*$ &&
         "$publisher_status" == "completed" && "$publisher_conclusion" == "success" ]] || continue
      (( SECONDS < event_deadline )) || break
      run="$(fetch_alternate_run_metadata "$event_deadline" "repos/${GITHUB_REPOSITORY}/actions/runs/${publisher_id}")" || continue
      publisher_path="$(jq -er '.path | select(type == "string")' <<<"$run")" || continue
      # Either event runs on the default branch; a qualified path must agree.
      [[ "$publisher_path" == ".github/workflows/publish-pr-runtime-images.yml" ||
         "$publisher_path" == ".github/workflows/publish-pr-runtime-images.yml@${default_branch}" ||
         "$publisher_path" == ".github/workflows/publish-pr-runtime-images.yml@refs/heads/${default_branch}" ]] || continue
      # The receipt binds the actual publisher workflow ref. If a native PR
      # callback's REST record carries the source branch, require it to agree
      # with the exact producer and still verify that default-ref receipt.
      callback_branch=""
      if [[ "$publisher_event" == "workflow_run" && "$source_event" == "pull_request" ]]; then
        callback_branch="$source_branch"
      fi
      publisher_branch="$(jq -er --arg default "$default_branch" --arg callback "$callback_branch" '
        .head_branch | select(. == $default or ($callback != "" and . == $callback))
      ' <<<"$run")" || continue
      attempt="$(jq -er --argjson id "$publisher_id" --arg repo "$GITHUB_REPOSITORY" --arg path "$publisher_path" \
        --arg title "$expected_title" --arg event "$publisher_event" '
          select(.id == $id and .event == $event and .display_title == $title
            and .path == $path
            and .repository.full_name == $repo and .head_repository.full_name == $repo
            and .status == "completed" and .conclusion == "success")
          | .run_attempt | select(type == "number" and . >= 1 and floor == .)
        ' <<<"$run")" || continue
      jobs="$(fetch_alternate_run_metadata "$event_deadline" "repos/${GITHUB_REPOSITORY}/actions/runs/${publisher_id}/attempts/${attempt}/jobs?per_page=100")" || continue
      jq -e --argjson id "$publisher_id" --argjson attempt "$attempt" '
        (.jobs | type == "array") and (.total_count == (.jobs | length)) and
        ([.jobs[] | select(.name == "Publish Fixed PR Merge Image Tags")] as $jobs |
          ($jobs | length) == 1 and ($jobs[0] |
            .run_id == $id and ((has("run_attempt") | not) or .run_attempt == $attempt) and
            .status == "completed" and .conclusion == "success" and
            (.steps | type == "array") and
            ([.steps[] | select(.name == "Publish fixed PR image tags")] as $steps |
              ($steps | length) == 1 and $steps[0].status == "completed" and $steps[0].conclusion == "success")))
      ' <<<"$jobs" >/dev/null || continue
      publisher_artifact="$(read_exact_run_artifact "$event_deadline" "$publisher_id" "pr-runtime-publication-${publisher_id}-attempt-${attempt}")" || continue
      expected_receipt="$(jq -cn --arg repo "$GITHUB_REPOSITORY" --argjson pr "$source_pr" \
        --arg base "$base_sha" --arg head "$head_sha" --arg merge "$merge_sha" --arg tag "$image_tag" \
        --argjson workflow "$source_workflow_id" --argjson source "$older_id" --argjson sourceAttempt "$source_attempt" \
        --argjson artifact "$producer_artifact" --argjson publisher "$publisher_id" --argjson attempt "$attempt" \
        --arg publisherRef "${GITHUB_REPOSITORY}/.github/workflows/publish-pr-runtime-images.yml@refs/heads/${default_branch}" '
        {schema:1, repository:$repo, prNumber:$pr, baseSha:$base, headSha:$head, mergeSha:$merge, imageTag:$tag,
         producerWorkflowId:$workflow, producerRunId:$source, producerAttempt:$sourceAttempt,
         producerArtifactId:$artifact.id, producerArtifactDigest:$artifact.digest, publisherWorkflowRef:$publisherRef,
         publisherRunId:$publisher, publisherAttempt:$attempt, publishJob:"Publish Fixed PR Merge Image Tags",
         publishStep:"Publish fixed PR image tags"}')" || continue
      verify_exact_publication_receipt "$event_deadline" "$(jq -r '.id' <<<"$publisher_artifact")" \
        "$(jq -r '.digest' <<<"$publisher_artifact")" "$expected_receipt" || continue
      confirm_publication_source_attempt "$event_deadline" "$older_id" "$source_workflow_id" \
        "$source_attempt" "$source_title" "$source_state" "$source_branch" || continue
      latest="$(fetch_alternate_run_metadata "$event_deadline" "repos/${GITHUB_REPOSITORY}/actions/runs/${publisher_id}")" || continue
      jq -e --argjson id "$publisher_id" --argjson attempt "$attempt" --arg path "$publisher_path" --arg branch "$publisher_branch" \
        --arg title "$expected_title" --arg event "$publisher_event" --arg repo "$GITHUB_REPOSITORY" '
        .id == $id and .run_attempt == $attempt and .status == "completed" and .conclusion == "success"
        and .path == $path and .head_branch == $branch and .event == $event and .display_title == $title
        and .repository.full_name == $repo and .head_repository.full_name == $repo
      ' <<<"$latest" >/dev/null || continue
      # Observe the producer attempt last. A publisher read and a producer read
      # are separate observations; a newly observed producer attempt withholds proof.
      confirm_publication_source_attempt "$event_deadline" "$older_id" "$source_workflow_id" \
        "$source_attempt" "$source_title" "$source_state" "$source_branch" || continue
      (( SECONDS < event_deadline )) || break
      if [[ "$proof_origin" == "direct" ]]; then
        printf 'Trusted PR image publisher %s succeeded for %s as the direct source.\n' "$publisher_id" "$image_tag"
      else
        printf 'Trusted PR image publisher %s succeeded for %s via an alternate producer.\n' "$publisher_id" "$image_tag"
      fi
      return 0
    done <<<"$publisher_lines"
  done
  # Distinguish bounded handoff waits from terminal failure. This state is
  # never sufficient for publication acceptance.
  if [[ "$proof_origin" == "handoff" && ( "$pending_handoff" == "true" || "$publisher_seen" == "false" ) ]]; then
    return 2
  fi
  return 1
}

find_completed_alternate_publication() {
  local alternate_sources="$1" lookup_deadline="$2" proof_origin="${3:-alternate}"
  local -a candidates
  local index older_state status pending=false
  mapfile -t candidates <<<"$alternate_sources"
  for index in "${!candidates[@]}"; do
    older_state="${candidates[index]}"
    [[ "$older_state" != 'incomplete' ]] || return 2
    [[ "$older_state" == found$'\t'* ]] || continue
    local remaining=$((lookup_deadline - SECONDS))
    (( remaining > 0 )) || return 2
    # Share the remaining time across the exact candidates in this inventory.
    # Slow metadata on one producer cannot claim the whole wait.
    local candidates_left=$((${#candidates[@]} - index))
    local share=$(((remaining + candidates_left - 1) / candidates_left))
    local candidate_deadline=$((SECONDS + (share < 90 ? share : 90)))
    (( candidate_deadline <= lookup_deadline )) || candidate_deadline="$lookup_deadline"
    status=0
    use_completed_duplicate_publication "$older_state" "$candidate_deadline" "$proof_origin" || status=$?
    if (( status == 0 )); then return 0; fi
    if (( status == 2 || SECONDS >= candidate_deadline )); then pending=true; fi
  done
  if [[ "$pending" == "true" ]]; then return 2; fi
  return 1
}

branch_publication_attempt() {
  jq -er --argjson id "$1" --arg repo "$GITHUB_REPOSITORY" '
    select(.id == $id and .repository.full_name == $repo and .head_repository.full_name == $repo
      and .status == "completed" and .conclusion == "success")
    | .run_attempt | select(type == "number" and . >= 1 and floor == .)
  '
}

confirm_branch_publication_attempt() {
  local run_id="$1" selected_state="$2" attempt="$3" latest
  latest="$(fetch_alternate_run_metadata "$deadline" "repos/${GITHUB_REPOSITORY}/actions/runs/${run_id}")" || return 2
  [[ "$(branch_publication_attempt "$run_id" <<<"$latest")" == "$attempt" &&
     "$(jq -c '{workflow_runs:[.]}' <<<"$latest" | read_run_state)" == "$selected_state" ]] &&
    (( SECONDS < deadline )) || return 2
}

# Status 1 means the exact attempt has no matching promotion evidence.
# Status 2 keeps an unavailable or ambiguous observation pending.
verify_branch_publication() {
  local run_id="$1" selected_state="$2" endpoint run attempt jobs proof_status=0
  local promotion_conclusion="${3:-success}" show_attempt="${4:-false}"
  endpoint="repos/${GITHUB_REPOSITORY}/actions/runs/${run_id}"
  run="$(fetch_alternate_run_metadata "$deadline" "$endpoint")" || return 2
  [[ "$(jq -c '{workflow_runs:[.]}' <<<"$run" | read_run_state)" == "$selected_state" ]] || return 2
  attempt="$(branch_publication_attempt "$run_id" <<<"$run")" || return 2
  jobs="$(fetch_alternate_run_metadata "$deadline" "${endpoint}/attempts/${attempt}/jobs?per_page=100")" || return 2
  jq -se --argjson id "$run_id" --argjson attempt "$attempt" '
    length == 1 and (.[0] |
      (.jobs | type == "array") and .total_count == (.jobs | length) and
      all(.jobs[]; type == "object" and (.name | type == "string")) and
      ([.jobs[] | select(.name == "Promote Smoke-Tested Runtime Images")] |
        length <= 1 and all(.[]; .run_id == $id and
          ((has("run_attempt") | not) or .run_attempt == $attempt) and
          .status == "completed" and (.conclusion | type == "string"))))
  ' <<<"$jobs" >/dev/null || return 2
  jq -e --arg conclusion "$promotion_conclusion" '
    ([.jobs[] | select(.name == "Promote Smoke-Tested Runtime Images")] as $jobs |
      ($jobs | length) == 1 and ($jobs[0] |
        .conclusion == $conclusion and
        (if $conclusion == "skipped" then
          # A wholly skipped native promotion job is positive no-op evidence,
          # not a missing, failed, or partially executed promotion step.
          (.steps == null or (.steps | type == "array" and
            all(.[]; type == "object" and .status == "completed" and .conclusion == "skipped")))
        else
          (.steps | type == "array") and
          ([.steps[] | select(.name == "Promote smoke-tested service digests")] as $steps |
            ($steps | length) == 1 and $steps[0].status == "completed" and $steps[0].conclusion == "success")
        end)))
  ' <<<"$jobs" >/dev/null || proof_status=$?
  if (( proof_status > 1 )); then return 2; fi
  confirm_branch_publication_attempt "$run_id" "$selected_state" "$attempt" || return 2
  (( proof_status == 0 )) || return 1
  if [[ "$show_attempt" == "true" ]]; then
    printf '%s\n' "$attempt"
  fi
}

find_earlier_branch_publication() {
  local selected_id="$1" selected_state="$2" inventory="$3"
  local candidates candidate state candidate_id status conclusion skipped_attempt candidate_attempt proof_status=0 index
  local -a bypassed_ids=() bypassed_states=() bypassed_attempts=()
  # Only an authoritatively skipped exact current attempt may be bypassed.
  # Keep failures and unavailable or ambiguous proof as barriers to fallback.
  candidates="$(read_run_state "$selected_id" branch-alternates <<<"$inventory")" || return 2
  skipped_attempt="$(verify_branch_publication "$selected_id" "$selected_state" skipped true)" || proof_status=$?
  if (( proof_status != 0 )); then
    [[ "$candidates" != "missing" ]] || return "$proof_status"
    return 2
  fi
  [[ "$candidates" != "missing" ]] || return 1
  bypassed_ids+=("$selected_id")
  bypassed_states+=("$selected_state")
  bypassed_attempts+=("$skipped_attempt")
  while IFS= read -r candidate; do
    (( SECONDS < deadline )) || return 2
    IFS=$'\t' read -r state candidate_id status conclusion _ <<<"$candidate"
    [[ "$state" == "found" && "$status" == "completed" && "$conclusion" == "success" ]] || return 2
    proof_status=0
    candidate_attempt="$(verify_branch_publication "$candidate_id" "$candidate" success true)" || proof_status=$?
    if (( proof_status == 2 )); then return 2; fi
    if (( proof_status == 0 )); then
      for index in "${!bypassed_ids[@]}"; do
        confirm_branch_publication_attempt "${bypassed_ids[index]}" "${bypassed_states[index]}" "${bypassed_attempts[index]}" || return 2
      done
      # Read the selected success last. These are separate native observations.
      confirm_branch_publication_attempt "$candidate_id" "$candidate" "$candidate_attempt" || return 2
      printf 'Earlier trusted branch runtime-image run %s has verified promotion for %s after a newer skipped promotion.\n' \
        "$candidate_id" "$image_tag"
      return 0
    fi
    skipped_attempt="$(verify_branch_publication "$candidate_id" "$candidate" skipped true)" || return 2
    bypassed_ids+=("$candidate_id")
    bypassed_states+=("$candidate")
    bypassed_attempts+=("$skipped_attempt")
  done <<<"$candidates"
  return 1
}

wait_for_pr_publisher() {
  local source_state="$1"
  local alternate_sources="$2"
  local source_state_name source_run_id source_status source_conclusion source_state_event source_created_at
  local coverage_unavailable=false
  local reported_publisher_failure=""
  local publisher_start_epoch="${SECONDS}"
  local publisher_deadline=$((SECONDS + publisher_timeout_seconds))
  local -a direct_publisher_events=(workflow_run repository_dispatch)
  IFS=$'\t' read -r source_state_name source_run_id source_status source_conclusion _ source_state_event source_created_at <<<"$source_state"
  if [[ "$source_state_name" != "found" || "$source_status" != "completed" || "$source_conclusion" != "success" ]]; then
    printf 'Refusing publisher wait without the selected successful runtime source state.\n' >&2
    exit 1
  fi
  while (( SECONDS < publisher_deadline )); do
    local publisher_payload publisher_state event_payload event_state publisher_event fetch_failed
    local events_left=${#direct_publisher_events[@]} event_share event_deadline
    local state run_id run_status run_conclusion run_url
    publisher_payload=""
    fetch_failed=false
    local poll_upper_bound
    poll_upper_bound="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    # Preserve complete discovery within the verified source creation window.
    for publisher_event in "${direct_publisher_events[@]}"; do
      event_share="$(publisher_event_budget "$publisher_deadline" "$events_left")"
      events_left=$((events_left - 1))
      if (( event_share <= 0 )); then
        fetch_failed=true
        break
      fi
      event_deadline=$((SECONDS + event_share))
      (( event_deadline <= publisher_deadline )) || event_deadline="$publisher_deadline"
      if ! event_payload="$(
        fetch_publisher_runs "${publisher_event}" "${source_created_at}" "${poll_upper_bound}" \
          "$(publisher_inventory_budget "$event_share")"
      )"; then
        fetch_failed=true
        continue
      fi
      publisher_payload+="${publisher_payload:+,}${event_payload}"
      if ! event_state="$(read_publisher_state "$source_run_id" "$source_state_event" poll <<<"$event_payload")"; then
        fetch_failed=true
        continue
      fi
      IFS=$'\t' read -r state run_id run_status run_conclusion _ <<<"$event_state"
      if [[ "$run_status" == "completed" && "$run_conclusion" == "success" ]] &&
        use_completed_duplicate_publication "$source_state" "$event_deadline" direct "$event_payload" "$publisher_event"; then
        return 0
      fi
    done
    publisher_payload="[${publisher_payload}]"
    publisher_state=""
    if publisher_state="$(read_publisher_state "${source_run_id}" "${source_state_event}" poll <<<"${publisher_payload}")"; then
      IFS=$'\t' read -r state run_id run_status run_conclusion run_url <<<"${publisher_state}"
    fi
    # An inventory refresh can consume the rest of the deadline. Give
    # already-known exact candidates their bounded proof reads first.
    if find_completed_alternate_publication "$alternate_sources" "$publisher_deadline"; then
      return 0
    fi
    local refreshed_source_payload refreshed_alternates new_alternates active_replacement
    local selected_source selected_id selected_status selected_conclusion selected_event selected_created_at
    local source_refresh_unavailable=true
    active_replacement=""
    local source_refresh_remaining=$((publisher_deadline - SECONDS))
    if (( source_refresh_remaining > 0 )) && refreshed_source_payload="$(fetch_workflow_runs \
      "repos/${GITHUB_REPOSITORY}/actions/workflows/runtime-images.yml/runs?per_page=100" \
      "refreshing alternate runtime-image sources while waiting for publication" "$source_refresh_remaining")"; then
      selected_source="$(read_run_state "$source_run_id" selected <<<"$refreshed_source_payload")" || selected_source=""
      if [[ "$selected_source" == found$'\t'* ]]; then
        IFS=$'\t' read -r _ selected_id selected_status selected_conclusion _ selected_event selected_created_at <<<"$selected_source"
        if [[ "$selected_id" == "$source_run_id" && "$selected_event" == "$source_state_event" ]]; then
          case "$selected_status" in
            requested|queued|in_progress|waiting|pending)
              printf 'Selected runtime-images producer %s is active again; continuing bounded publication wait.\n' "$source_run_id"
              sleep "$sleep_seconds"
              continue
              ;;
            completed)
              if [[ "$selected_conclusion" == success && "$selected_source" != "$source_state" ]]; then
                source_state="$selected_source"
                source_created_at="$selected_created_at"
                continue
              fi
              ;;
          esac
        fi
      fi
      if refreshed_alternates="$(read_run_state "${source_run_id}" alternates <<<"${refreshed_source_payload}")"; then
        if active_replacement="$(read_run_state "${source_run_id}" active-alternates <<<"${refreshed_source_payload}")"; then
          source_refresh_unavailable=false
        fi
        if [[ "${refreshed_alternates}" == "missing" ]]; then
          alternate_sources=""
        else
          new_alternates="$(grep -Fvx -f <(printf '%s\n' "$alternate_sources") <<<"$refreshed_alternates" || true)"
          alternate_sources="${refreshed_alternates}"
          if [[ -n "$new_alternates" ]] &&
            find_completed_alternate_publication "$new_alternates" "$publisher_deadline"; then
            return 0
          fi
        fi
      fi
    fi
    if [[ "${fetch_failed}" == "true" ]]; then
      coverage_unavailable=true
      sleep "${sleep_seconds}"
      continue
    fi
    coverage_unavailable=false
    if [[ -z "${publisher_state}" ]]; then
      printf 'GitHub API response was empty or invalid while waiting for the trusted PR image publisher; retrying.\n' >&2
      sleep "${sleep_seconds}"
      continue
    fi

    if [[ "$run_status" == "completed" && "$run_conclusion" == "success" ]]; then
      printf 'Trusted PR image publisher %s completed successfully, but its exact current-attempt fixed-tag publication proof is unavailable; retrying.\n' \
        "$run_id" >&2
      sleep "${sleep_seconds}"
      continue
    fi

    if [[ ( "${state}" == "missing" || "${run_status}" == "completed" ) && "$active_replacement" == found$'\t'* ]]; then
      printf 'Another exact runtime-images producer is still active for %s; continuing bounded publication wait.\n' "$image_tag"
      sleep "${sleep_seconds}"
      continue
    fi

    if [[ "${state}" == "missing" || "${run_status}" == "completed" ]]; then
      local handoff_status=0
      find_completed_alternate_publication "$alternate_sources" "$publisher_deadline" handoff || handoff_status=$?
      if (( handoff_status == 0 )); then return 0; fi
      if (( handoff_status == 2 )); then
        printf 'Waiting for publication of an exact replacement producer for %s.\n' "$image_tag"
        sleep "${sleep_seconds}"
        continue
      fi
      if [[ "$source_refresh_unavailable" == "true" ]]; then
        printf 'Runtime-image producer coverage unavailable for %s; continuing bounded publication wait.\n' "$image_tag" >&2
        sleep "${sleep_seconds}"
        continue
      fi
      if [[ "${state}" == "missing" ]]; then
        local elapsed_seconds=$((SECONDS - publisher_start_epoch))
        if (( elapsed_seconds >= missing_workflow_timeout_seconds )); then
          printf 'No trusted PR image publisher appeared for %s after %ss.\n' \
            "${image_tag}" "${elapsed_seconds}" >&2
          exit 1
        fi
        printf 'Waiting for trusted PR image publisher for %s after %ss.\n' \
          "${image_tag}" "${elapsed_seconds}"
        sleep "${sleep_seconds}"
        continue
      fi
      # Publication callbacks alone do not identify which source attempt
      # failed once the source has rerun. Retain the first-attempt failure
      # diagnostic only while a fresh exact source still has no prior attempt.
      local latest_source latest_source_state
      if latest_source="$(fetch_alternate_run_metadata "$publisher_deadline" \
        "repos/${GITHUB_REPOSITORY}/actions/runs/${source_run_id}")" &&
        latest_source_state="$(jq -c '{workflow_runs:[.]}' <<<"$latest_source" | read_run_state)" &&
        [[ "$latest_source_state" == "$source_state" ]] &&
        jq -e --arg repo "$GITHUB_REPOSITORY" '.run_attempt == 1
          and .repository.full_name == $repo and .head_repository.full_name == $repo' \
          <<<"$latest_source" >/dev/null; then
        printf 'Trusted PR image publisher %s completed with %s for %s.\n' \
          "${run_id}" "${run_conclusion}" "${image_tag}" >&2
        [[ -z "${run_url}" ]] || printf 'Workflow URL: %s\n' "${run_url}" >&2
        exit 1
      fi
      if [[ "$reported_publisher_failure" != "${run_id}/${run_conclusion}" ]]; then
        printf 'Trusted PR image publisher %s completed with %s for %s. Waiting for exact current-attempt proof.\n' \
          "${run_id}" "${run_conclusion}" "${image_tag}" >&2
        [[ -z "${run_url}" ]] || printf 'Workflow URL: %s\n' "${run_url}" >&2
        reported_publisher_failure="${run_id}/${run_conclusion}"
      fi
      sleep "${sleep_seconds}"
      continue
    fi

    printf 'Waiting for trusted PR image publisher %s for %s. status=%s conclusion=%s\n' \
      "${run_id}" "${image_tag}" "${run_status}" "${run_conclusion:-pending}"
    sleep "${sleep_seconds}"
  done

  if [[ "${coverage_unavailable}" == "true" ]]; then
    printf 'Publisher coverage unavailable at the wait deadline for %s.\n' "${image_tag}" >&2
    exit 1
  fi
  printf 'Timed out waiting for trusted PR image publisher for %s.\n' "${image_tag}" >&2
  exit 1
}

while (( SECONDS < deadline )); do
  if ! workflow_payload="$(
    fetch_workflow_runs \
      "repos/${GITHUB_REPOSITORY}/actions/workflows/runtime-images.yml/runs?per_page=100" \
      "waiting for the runtime-images workflow" "$((deadline - SECONDS))"
  )"; then
    sleep "${sleep_seconds}"
    continue
  fi
  if ! run_state="$(read_run_state <<<"${workflow_payload}")"; then
    printf 'GitHub API response was empty or invalid while waiting for the runtime-images workflow; retrying.\n' >&2
    sleep "${sleep_seconds}"
    continue
  fi

  IFS=$'\t' read -r state run_id run_status run_conclusion run_url _run_event _ <<<"${run_state}"

  if [[ "${state}" == "missing" ]]; then
    elapsed_seconds=$((SECONDS - start_epoch))
    if (( elapsed_seconds >= missing_workflow_timeout_seconds )); then
      if [[ "${wait_mode}" == "branch" ]]; then
        printf 'No trusted branch runtime-image publication appeared for branch %s and exact head SHA %s after %ss.\n' \
          "${branch_name}" "${head_sha}" "${elapsed_seconds}" >&2
      else
        printf 'No runtime-images workflow appeared for %s after %ss.\n' \
          "${image_tag}" "${elapsed_seconds}" >&2
        printf 'The PR image source or trusted current-base refresh did not appear for the exact merge SHA.\n' >&2
      fi
      exit 1
    fi

    printf 'Waiting for runtime-images workflow for %s after %ss. Matching run not visible yet.\n' \
      "${image_tag}" "${elapsed_seconds}"
    sleep "${sleep_seconds}"
    continue
  fi

  if [[ "${run_status}" == "completed" && "${run_conclusion}" == "success" ]]; then
    branch_proof_status=0
    if [[ "${wait_mode}" == "branch" ]]; then
      verify_branch_publication "$run_id" "$run_state" || branch_proof_status=$?
    fi
    if (( branch_proof_status != 0 )); then
      if (( branch_proof_status == 1 )); then
        branch_proof_status=0
        find_earlier_branch_publication "$run_id" "$run_state" "$workflow_payload" || branch_proof_status=$?
        if (( branch_proof_status == 0 )); then exit 0; fi
      fi
      printf 'Trusted branch runtime-image run %s has no verified promotion for %s yet.\n' "$run_id" "$image_tag" >&2
      # A successful no-op can never provide promotion proof for an obsolete
      # target. Without promotion proof, only a positive trusted ref read can
      # end this wait early.
      if (( branch_proof_status == 1 )) &&
        current_branch_ref="$(fetch_alternate_run_metadata "$deadline" "repos/${GITHUB_REPOSITORY}/git/ref/heads/${branch_name}")" &&
        current_branch_sha="$(jq -ser --arg ref "refs/heads/${branch_name}" '
          select(length == 1) | .[0] | select(.ref == $ref) | .object.sha
          | select(type == "string" and length == 40 and test("^[0-9a-fA-F]{40}$"))
          | ascii_downcase
        ' <<<"$current_branch_ref")" &&
        [[ "$current_branch_sha" =~ ^[0-9a-f]{40}$ && "$current_branch_sha" != "${head_sha,,}" ]]; then
        printf 'Branch runtime-image target %s@%s is obsolete: current head is %s; refusing deployment without verified promotion.\n' \
          "$branch_name" "$head_sha" "$current_branch_sha" >&2
        exit 1
      fi
      sleep "${sleep_seconds}"
      continue
    fi
    printf 'Matching runtime-images workflow %s succeeded for %s after %ss.\n' \
      "${run_id}" "${image_tag}" "$((SECONDS - start_epoch))"
    if [[ "${wait_mode}" == "pull-request" ]]; then
      older_states=""
      if [[ "$SECONDS" -lt "$deadline" ]]; then
        older_states="$(read_run_state "$run_id" alternates <<<"${workflow_payload}")" || older_states=""
      fi
      wait_for_pr_publisher "${run_state}" "$older_states"
    fi
    exit 0
  fi

  alternate_status=1
  if [[ "$wait_mode" == "pull-request" && "$SECONDS" -lt "$deadline" ]] &&
    older_states="$(read_run_state "$run_id" alternates <<<"$workflow_payload")"; then
    alternate_scope=alternate
    [[ "$run_status" != "completed" ]] || alternate_scope=handoff
    alternate_status=0
    find_completed_alternate_publication "$older_states" "$deadline" "$alternate_scope" || alternate_status=$?
    if (( alternate_status == 0 )); then exit 0; fi
  fi

  if [[ "${run_status}" == "completed" ]]; then
    if [[ "$wait_mode" == "pull-request" ]] && (( SECONDS < deadline )); then
      terminal_alternates=""
      # A successful alternate can start a rerun during publication lookup.
      # Only a fresh, complete inventory can justify a terminal decision.
      if ! terminal_inventory="$(fetch_workflow_runs \
        "repos/${GITHUB_REPOSITORY}/actions/workflows/runtime-images.yml/runs?per_page=100" \
        "confirming terminal runtime-image failure" "$((deadline - SECONDS))")" ||
        ! terminal_state="$(read_run_state <<<"$terminal_inventory")" ||
        ! active_alternates="$(read_run_state "$run_id" active-alternates <<<"$terminal_inventory")" ||
        ! terminal_alternates="$(read_run_state "$run_id" alternates <<<"$terminal_inventory")"; then
        sleep "${sleep_seconds}"
        continue
      fi
      if [[ "$terminal_state" != "$run_state" || "$active_alternates" == found$'\t'* ]]; then
        printf 'Exact runtime-images producer ordering changed or a replacement is active for %s; continuing bounded wait.\n' "$image_tag"
        sleep "${sleep_seconds}"
        continue
      fi
      if [[ "$terminal_alternates" != "$older_states" && "$terminal_alternates" != missing ]]; then
        alternate_status=0
        find_completed_alternate_publication "$terminal_alternates" "$deadline" handoff || alternate_status=$?
        if (( alternate_status == 0 )); then exit 0; fi
      fi
    fi
    if (( alternate_status == 2 )); then
      printf 'Waiting for publication of an exact replacement producer for %s.\n' "$image_tag"
      sleep "${sleep_seconds}"
      continue
    fi
    (( SECONDS < deadline )) || break
    printf 'Matching runtime-images workflow %s completed with %s for %s.\n' \
      "${run_id}" "${run_conclusion}" "${image_tag}" >&2
    if [[ -n "${run_url}" ]]; then
      printf 'Workflow URL: %s\n' "${run_url}" >&2
    fi
    exit 1
  fi

  printf 'Waiting for runtime-images workflow %s for %s after %ss. status=%s conclusion=%s\n' \
    "${run_id}" "${image_tag}" "$((SECONDS - start_epoch))" "${run_status}" "${run_conclusion:-pending}"
  if [[ -n "${run_url}" ]]; then
    printf 'Workflow URL: %s\n' "${run_url}"
  fi
  sleep "${sleep_seconds}"
done

printf 'Timed out waiting for runtime-images workflow for %s after %ss.\n' \
  "${image_tag}" "$((SECONDS - start_epoch))" >&2
exit 1
