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
  local payload

  if ! payload="$(gh api --paginate --slurp "${endpoint}")"; then
    printf 'GitHub API poll failed while %s; retrying within the existing wait deadline.\n' \
      "${wait_context}" >&2
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

wait_mode, image_tag, merge_sha, base_sha, head_sha, *native = sys.argv[1:]
try:
    payload = json.load(sys.stdin)
except json.JSONDecodeError:
    raise SystemExit(1)
replacement = bool(native and native[0] == "replacement")
if native and not replacement:
    payload = {"workflow_runs": [payload]}
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
        display_title = run.get("display_title", "")
        tokens = display_title.split()
        return (
            run.get("event") == "workflow_run"
            and display_title.startswith("Build Runtime Images trusted-branch ")
            and "branch-develop" in tokens
            and f"sha-{head_sha}" in tokens
            and len(tokens) == 6
        )

    # A base refresh is dispatched as the typed pr-runtime-base-refresh event;
    # the workflow-run API exposes its source only as repository_dispatch.
    if run.get("event") not in {"pull_request", "repository_dispatch"}:
        return False
    display_title = run.get("display_title", "")
    return isinstance(display_title, str) and re.fullmatch(
        rf"Build Runtime Images secure-pr-artifact pr-[1-9][0-9]* base-{base_sha}"
        rf" head-{head_sha} merge-{merge_sha} mode-required", display_title
    )

matching_runs = [run for run in workflow_runs if is_matching_run(run)]
if replacement:
    original_id, original_created, original_workflow, budget = native[2:]
    matching_runs = [run for run in matching_runs if run.get("event") == "repository_dispatch"
                     and run.get("id") != int(original_id) and run.get("created_at", "") > original_created]
if not matching_runs:
    print("missing")
    raise SystemExit(0)

run = sorted(matching_runs, key=lambda item: item.get("created_at", ""), reverse=True)[0]
if replacement and (sum(candidate.get("created_at") == run.get("created_at") for candidate in matching_runs) != 1
        or run.get("status") != "completed" or run.get("conclusion") != "success"
        or run.get("workflow_id") != int(original_workflow)):
    print("missing")
    raise SystemExit(0)
# Every tab-delimited field must be nonempty so Bash IFS cannot shift positions.
def clean_field(value):
    return isinstance(value, str) and bool(value) and not any(character.isspace() or ord(character) < 32 or ord(character) == 127 for character in value)

run_attempt = run.get("run_attempt") if wait_mode == "pull-request" else "-"
workflow_id = run.get("workflow_id") if wait_mode == "pull-request" else "-"
if wait_mode == "pull-request":
    if any(type(value) is not int or value <= 0 for value in (run_attempt, workflow_id)):
        raise SystemExit(1)
    if native:
        repositories = [run.get(field) for field in ("repository", "head_repository")]
        if any(not isinstance(value, dict) or not clean_field(value.get("full_name")) for value in repositories):
            raise SystemExit(1)
        if any(value["full_name"] != native[1] for value in repositories):
            print("missing")
            raise SystemExit(0)
        if run.get("event") == "pull_request":
            if not isinstance(run.get("head_sha"), str) or not re.fullmatch(r"[0-9a-f]{40}", run["head_sha"]):
                raise SystemExit(1)
            if run["head_sha"] != head_sha:
                print("missing")
                raise SystemExit(0)
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
if replacement:
    import subprocess
    import time

    deadline = time.monotonic() + int(budget)
    path = ".github/workflows/runtime-images.yml"
    branch = run.get("head_branch")
    if (not clean_field(branch) or not re.fullmatch(r"[0-9a-f]{40}", run.get("head_sha", ""))
            or run.get("path") not in {path, f"{path}@{branch}", f"{path}@refs/heads/{branch}"}
            or run.get("name") not in {"Build Runtime Images", run["display_title"]}):
        raise SystemExit(1)
    fields = ("id", "workflow_id", "run_attempt", "path", "head_branch", "head_sha",
              "event", "status", "conclusion", "display_title")
    # Both the selected historical attempt and the current run must agree.
    # Attempt creation time can differ from original run creation time.
    for suffix in (f"/attempts/{run_attempt}", ""):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise SystemExit(1)
        try:
            observed = json.loads(subprocess.check_output(
                ["gh", "api", f"repos/{native[1]}/actions/runs/{run_id}{suffix}"],
                timeout=remaining, text=True))
        except (OSError, subprocess.SubprocessError, json.JSONDecodeError):
            raise SystemExit(1)
        if (not isinstance(observed, dict) or any(observed.get(field) != run[field] for field in fields)
                or observed.get("name") not in {"Build Runtime Images", run["display_title"]}
                or any(not isinstance(observed.get(field), dict) or
                       observed[field].get("full_name") != native[1]
                       for field in ("repository", "head_repository"))
                or (not suffix and observed.get("created_at") != run["created_at"])):
            raise SystemExit(1)
print(
    "found\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}".format(
        run_id,
        run["status"],
        conclusion,
        run["html_url"],
        run["event"],
        created_at,
        run_attempt,
        workflow_id,
    )
)
' "${wait_mode}" "${image_tag}" "${merge_sha}" "${base_sha}" "${head_sha}" "$@"
}

fetch_publisher_runs() {
  python3 - "${GITHUB_REPOSITORY}" "$1" "$2" "$3" "$4" "${5:-publish-pr-runtime-images.yml}" <<'PY_PUBLISHERS'
import json
import re
import subprocess
import sys
import time
from datetime import datetime, timezone

repository, event, lower_text, upper_text, remaining, workflow = sys.argv[1:]
deadline = time.monotonic() + int(remaining)

def unavailable(reason):
    role = "Publisher" if workflow == "publish-pr-runtime-images.yml" else "Runtime producer"
    print(f"{role} coverage unavailable: {reason}.", file=sys.stderr)
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
endpoint = f"repos/{repository}/actions/workflows/{workflow}/runs?event={event}&created={lower_text}..{upper_text}&per_page=100"
records, seen, total = [], set(), None
page_number = 1
while True:
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        unavailable("exact-window GitHub API deadline reached")
    try:
        result = subprocess.run(["gh", "api", f"{endpoint}&page={page_number}"],
                                capture_output=True, text=True, timeout=remaining)
    except subprocess.TimeoutExpired:
        unavailable("exact-window GitHub API deadline reached")
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

wait_mode, image_tag, merge_sha, base_sha, head_sha, source_run_id, source_run_attempt, source_event = sys.argv[1:]
if not all(re.fullmatch(r"[1-9][0-9]*", value) for value in (source_run_id, source_run_attempt)):
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
    source_suffix = f"source-run-{source_run_id} attempt-{source_run_attempt}"
    matching_runs = [
        run for run in workflow_runs
        if (run.get("event") == "repository_dispatch" and
            ((source_event == "repository_dispatch" and
              run.get("display_title") == f"Publish PR Runtime Images {source_suffix}")
             or (source_event == "pull_request" and
                 run.get("display_title") == f"Publish PR Runtime Images requalification {source_suffix}")))
        or (source_event in ("pull_request", "repository_dispatch") and run.get("event") == "workflow_run" and
        (tokens := run.get("display_title", "").split())[:8] == [
            "Publish", "PR", "Runtime", "Images", "Build", "Runtime", "Images",
            "secure-pr-artifact",
        ]
        and len(tokens) in (13, 15)
        and re.fullmatch(r"pr-[1-9][0-9]{0,50}", tokens[8])
        and tokens[9] == f"base-{base_sha}"
        and tokens[10] == f"head-{head_sha}"
        and tokens[11] == f"merge-{merge_sha}"
        and tokens[12] == "mode-required"
        and ((len(tokens) == 13 and source_event == "pull_request") or " ".join(tokens[13:]) == source_suffix))
    ]
else:
    matching_runs = []
if not matching_runs:
    print("missing")
    raise SystemExit(0)

# A failed explicit requalification can follow an already completed original
# publication for this exact source attempt. Keep that retained success usable
# only when the complete exact-tuple inventory has one earlier successful
# original publisher and every later candidate is an unambiguous failure.
# Each exact failed attempt must subsequently prove it skipped tag access.
# Other terminal failures, active candidates, and ambiguous histories retain
# their existing fail-closed behavior.
run_ids = [run.get("id") for run in matching_runs]
if any(type(run_id) is not int or run_id <= 0 for run_id in run_ids) or len(set(run_ids)) != len(run_ids):
    raise SystemExit(1)
ordered = sorted(matching_runs, key=lambda item: item.get("created_at", ""), reverse=True)
latest_created = ordered[0].get("created_at")
latest = [run for run in ordered if run.get("created_at") == latest_created]
if len(latest) != 1:
    raise SystemExit(1)
run = latest[0]
selection_reason = "latest"
requalification_title = f"Publish PR Runtime Images requalification source-run-{source_run_id} attempt-{source_run_attempt}"
if (len(ordered) > 1 and run.get("event") == "repository_dispatch"
        and run.get("display_title") == requalification_title
        and run.get("status") == "completed" and run.get("conclusion") == "failure"):
    failures, original = ordered[:-1], ordered[-1]
    if (len({candidate["created_at"] for candidate in ordered}) != len(ordered)
            or original.get("event") != "workflow_run"
            or original.get("status") != "completed"
            or original.get("conclusion") != "success"):
        raise SystemExit(1)
    descriptors = []
    for failed in failures:
        failed_id = failed.get("id")
        failed_attempt, failed_workflow = failed.get("run_attempt"), failed.get("workflow_id")
        if (failed.get("event") != "repository_dispatch" or failed.get("display_title") != requalification_title
                or failed.get("status") != "completed" or failed.get("conclusion") != "failure"
                or any(type(value) is not int or value <= 0 for value in (failed_attempt, failed_workflow))):
            raise SystemExit(1)
        descriptors.append(f"{failed_id}:{failed_attempt}:{failed_workflow}")
    selection_reason = "retained-after-failed-requalification:" + ",".join(descriptors)
    run = original
attempt, workflow_id = run.get("run_attempt"), run.get("workflow_id")
if any(type(value) is not int or value <= 0 for value in (attempt, workflow_id)):
    raise SystemExit(1)
title = run["display_title"]
if any(character in title for character in "\r\n\t"):
    raise SystemExit(1)
url = run.get("html_url") or "-"
print(
    "found\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}".format(
        run["id"], run.get("status"), run.get("conclusion"), url, attempt,
        workflow_id, run["event"], title, selection_reason,
    )
)
' "${wait_mode}" "${image_tag}" "${merge_sha}" "${base_sha}" "${head_sha}" "$1" "$2" "$3"
}

verify_publisher_attempt() {
  python3 - "$GITHUB_REPOSITORY" "$base_sha" "$head_sha" "$merge_sha" "$@" <<'PY_PUBLICATION'
import json
import re
import subprocess
import sys
import time
from datetime import datetime, timedelta

(repository, base_sha, head_sha, merge_sha, run_id, attempt, workflow_id, event, title,
 source_id, source_attempt, source_workflow_id, source_event, selection_reason, remaining) = sys.argv[1:]
deadline = time.monotonic() + int(remaining)

def unavailable(reason):
    print(f"PR runtime publication proof unavailable: {reason}.", file=sys.stderr)
    raise SystemExit(1)

def read_object(path):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        unavailable("API deadline elapsed")
    try:
        output = subprocess.check_output(["gh", "api", f"repos/{repository}/actions/{path}"],
                                         text=True, timeout=remaining)
        result = json.loads(output)
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError):
        unavailable("required GitHub API read failed")
    if not isinstance(result, dict):
        unavailable("required GitHub API record is malformed")
    return result

def positive_int(value):
    return type(value) is int and value > 0

def successful(record):
    return record.get("status") == "completed" and record.get("conclusion") == "success"

def verify_run(record, expected_id, expected_attempt, expected_workflow_id, expected_event, workflow_path,
               expected_conclusion="success"):
    branch, sha = record.get("head_branch"), record.get("head_sha")
    paths = {workflow_path, f"{workflow_path}@{branch}", f"{workflow_path}@refs/heads/{branch}"}
    if expected_event == "pull_request":
        title = record.get("display_title")
        if isinstance(title, str) and (match := re.match(r"Build Runtime Images secure-pr-artifact pr-([1-9][0-9]*) ", title)):
            paths.add(f"{workflow_path}@refs/pull/{match[1]}/merge")
    if (any(not positive_int(record.get(field)) or record[field] != int(expected)
            for field, expected in (("id", expected_id), ("run_attempt", expected_attempt),
                                    ("workflow_id", expected_workflow_id)))
            or record.get("event") != expected_event
            or record.get("status") != "completed" or record.get("conclusion") != expected_conclusion
            or not isinstance(branch, str) or not branch or not isinstance(sha, str)
            or not re.fullmatch(r"[0-9a-f]{40}", sha)
            or not isinstance(record.get("path"), str) or record["path"] not in paths
            or any(not isinstance(record.get(field), dict) or record[field].get("full_name") != repository
                   for field in ("repository", "head_repository"))):
        unavailable("exact workflow attempt identity or outcome is inconsistent")

def read_jobs(record, run_path):
    evidence = read_object(f"{run_path}/jobs?per_page=100&page=1")
    jobs, total = evidence.get("jobs"), evidence.get("total_count")
    if not positive_int(total) or total > 100 or not isinstance(jobs, list) or len(jobs) != total:
        unavailable("native job inventory is incomplete")
    seen = set()
    for job in jobs:
        if (not isinstance(job, dict) or not positive_int(job.get("id")) or job["id"] in seen
                or not positive_int(job.get("run_id")) or job["run_id"] != record["id"]
                or ("run_attempt" in job and (not positive_int(job["run_attempt"])
                    or job["run_attempt"] != record["run_attempt"]))):
            unavailable("native job identity is inconsistent")
        seen.add(job["id"])
    return jobs

def successful_job(record, run_path, names):
    matching = [job for job in read_jobs(record, run_path) if job.get("name") in names]
    if len(matching) != 1 or not successful(matching[0]) or matching[0].get("head_sha") != record["head_sha"]:
        unavailable("successful native job is missing or ambiguous")
    job = matching[0]
    if not isinstance(job.get("steps"), list) or any(not isinstance(step, dict) for step in job["steps"]):
        unavailable("native job step inventory is malformed")
    return job

def successful_step(job, name):
    steps = [step for step in job["steps"] if step.get("name") == name]
    if (len(steps) != 1 or not successful(steps[0]) or not positive_int(steps[0].get("number"))
            or sum(step.get("number") == steps[0]["number"] for step in job["steps"]) != 1):
        unavailable("successful native step is missing or ambiguous")
    return steps[0]

publisher_path = f"runs/{run_id}/attempts/{attempt}"
publisher = read_object(publisher_path)
verify_run(publisher, run_id, attempt, workflow_id, event, ".github/workflows/publish-pr-runtime-images.yml")
if publisher.get("display_title") != title or publisher.get("name") not in ("Publish PR Runtime Images", title):
    unavailable("native publisher title differs from the selected run")
requalification = title == f"Publish PR Runtime Images requalification source-run-{source_id} attempt-{source_attempt}"
if requalification and (event != "repository_dispatch" or source_event != "pull_request"):
    unavailable("publication requalification requires an original pull_request source run")

def prove_failed_requalification(requalification_run, failed_path):
    failed_jobs = read_jobs(requalification_run, failed_path)
    failed_publish_jobs = [job for job in failed_jobs if job.get("name") == "Publish Fixed PR Merge Image Tags"]
    if (len(failed_publish_jobs) != 1
            or failed_publish_jobs[0].get("head_sha") != requalification_run["head_sha"]
            or failed_publish_jobs[0].get("status") != "completed"):
        unavailable("failed requalification publisher job is unproven")
    failed_steps = failed_publish_jobs[0].get("steps")
    if not isinstance(failed_steps, list) or any(not isinstance(step, dict) for step in failed_steps):
        unavailable("failed requalification step inventory is unproven")
    tag_steps = [step for step in failed_steps if step.get("name") == "Publish fixed PR image tags"]
    if failed_publish_jobs[0].get("conclusion") == "skipped" and not failed_steps:
        resolvers = [job for job in failed_jobs if job.get("name") == "Resolve Exact Runtime Publication Source"]
        if (len(resolvers) != 1 or resolvers[0].get("head_sha") != requalification_run["head_sha"]
                or resolvers[0].get("status") != "completed" or resolvers[0].get("conclusion") != "failure"):
            unavailable("failed requalification did not prove its pre-publication expiry")
        resolver_steps = resolvers[0].get("steps")
        if not isinstance(resolver_steps, list) or any(not isinstance(step, dict) for step in resolver_steps):
            unavailable("failed requalification resolver steps are unproven")
        resolved = [step for step in resolver_steps if step.get("name") == "Resolve successful exact source run"]
        expired = [step for step in resolver_steps if str(step.get("name", "")).startswith("Exact runtime artifact expired")]
        if (len(resolved) != 1 or not successful(resolved[0]) or len(expired) != 1
                or expired[0].get("status") != "completed" or expired[0].get("conclusion") != "failure"):
            unavailable("failed requalification does not have a unique expired-artifact receipt")
        retained_expiry = re.fullmatch(
            rf"Exact runtime artifact expired source-{source_id}-{source_attempt}"
            r" artifact-([1-9][0-9]*) (sha256:[0-9a-f]{64})", expired[0]["name"],
        )
        if not retained_expiry:
            unavailable("failed requalification expiry differs from the selected source attempt")
        return retained_expiry.groups()
    elif (failed_publish_jobs[0].get("conclusion") != "failure"
            or len(tag_steps) != 1 or tag_steps[0].get("status") != "completed"
            or tag_steps[0].get("conclusion") != "skipped" or tag_steps[0].get("started_at") is not None):
        unavailable("failed requalification has no proof it skipped fixed-tag checks")
    return None

retained_expiries = []
if selection_reason != "latest":
    prefix = "retained-after-failed-requalification:"
    failures = selection_reason.removeprefix(prefix).split(",")
    if not selection_reason.startswith(prefix) or event != "workflow_run" or source_event != "pull_request":
        unavailable("failed requalification selection is not bound to the publisher workflow")
    seen_failures = {run_id}
    requalification_title = f"Publish PR Runtime Images requalification source-run-{source_id} attempt-{source_attempt}"
    for descriptor in failures:
        failed = re.fullmatch(r"([1-9][0-9]*):([1-9][0-9]*):([1-9][0-9]*)", descriptor)
        if not failed or failed[1] in seen_failures or failed[3] != workflow_id:
            unavailable("failed requalification selection is not bound to the publisher workflow")
        failed_id, failed_attempt, failed_workflow = failed.groups()
        seen_failures.add(failed_id)
        requalification_identity = None
        # The inventory exposes only the latest attempt for each run ID.
        # Every native attempt must prove that it never reached the fixed tags.
        for attempt_number in range(int(failed_attempt), 0, -1):
            failed_path = f"runs/{failed_id}/attempts/{attempt_number}"
            requalification_run = read_object(failed_path)
            verify_run(requalification_run, failed_id, attempt_number, failed_workflow, "repository_dispatch",
                       ".github/workflows/publish-pr-runtime-images.yml", "failure")
            if (requalification_run.get("display_title") != requalification_title
                    or requalification_run.get("name") not in ("Publish PR Runtime Images", requalification_title)):
                unavailable("failed requalification does not bind the selected source")
            workflow_revision = (requalification_run["head_branch"], requalification_run["head_sha"])
            if requalification_identity is not None and workflow_revision != requalification_identity:
                unavailable("failed requalification attempt history changed workflow revision")
            requalification_identity = workflow_revision
            expiry = prove_failed_requalification(requalification_run, failed_path)
            if expiry:
                retained_expiries.append(expiry)
job = successful_job(publisher, publisher_path, ("Publish Fixed PR Merge Image Tags",))
download = successful_step(job, "Download successful PR image artifacts")
validate = successful_step(job, "Validate exact PR source and artifact before registry login")
publish = successful_step(job, "Publish fixed PR image tags")
if not download["number"] < validate["number"] < publish["number"]:
    unavailable("publisher steps are out of order")

receipt_prefix = "PR runtime publication receipt"
historical = event == "workflow_run" and title.endswith(" mode-required")
receipts = [step for step in job["steps"] if isinstance(step.get("name"), str)
            and step["name"].startswith(receipt_prefix)]
if receipts:
    if historical:
        unavailable("historical publisher unexpectedly carries a publication receipt")
    if len(receipts) != 1:
        unavailable("publication receipt is ambiguous")
    receipt = successful_step(job, receipts[0]["name"])
    match = re.fullmatch(
        rf"{receipt_prefix} v1 source-{source_id}-{source_attempt} publisher-{run_id}-{attempt}"
        r" artifact-([1-9][0-9]*) (sha256:[0-9a-f]{64})", receipt["name"],
    )
    if not match or receipt["number"] <= publish["number"]:
        unavailable("publication receipt does not bind the successful producer and publisher attempts")
    if any(expiry != match.groups() for expiry in retained_expiries):
        unavailable("failed requalification expired a different artifact than the retained publication")
    print(f"{match[1]}\t{match[2]}")
    raise SystemExit(0)

if requalification or retained_expiries:
    unavailable("publication requalification requires a native receipt")

# Earlier publishers can still use the native artifact inventory while it
# identifies the original, successful upload. An expired artifact cannot
# establish that identity for a publication without a receipt.
source_path = f"runs/{source_id}/attempts/{source_attempt}"
source = read_object(source_path)
verify_run(source, source_id, source_attempt, source_workflow_id, source_event,
           ".github/workflows/runtime-images.yml")
source_title = source.get("display_title")
if (not isinstance(source_title, str) or not re.fullmatch(
        rf"Build Runtime Images secure-pr-artifact pr-[1-9][0-9]* base-{base_sha}"
        rf" head-{head_sha} merge-{merge_sha} mode-required", source_title)
        or source.get("name") not in ("Build Runtime Images", source_title)
        or (source_event == "pull_request" and source["head_sha"] != head_sha)):
    unavailable("legacy source attempt title or head does not match the selected comparison")
producer = successful_job(source, source_path, ("PR Full-Stack Smoke (runtime-required=true)",
                                               "PR Full-Stack Smoke (runtime-required=false)"))
upload = successful_step(producer, "Upload preview image artifact")

def timestamp(record, field):
    value = record.get(field)
    if not isinstance(value, str) or not re.fullmatch(
            r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,3})?(?:Z|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])", value):
        unavailable("legacy upload timestamp is missing or malformed")
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        unavailable("legacy upload timestamp is invalid")

attempt_start = timestamp(source, "run_started_at").replace(microsecond=0)
attempt_end = timestamp(source, "updated_at").replace(microsecond=0) + timedelta(seconds=1)
job_start, job_end = timestamp(producer, "started_at"), timestamp(producer, "completed_at")
upload_start, upload_end = timestamp(upload, "started_at"), timestamp(upload, "completed_at")
if not attempt_start <= job_start <= upload_start <= upload_end <= job_end < attempt_end:
    unavailable("legacy upload did not execute within the selected native source attempt")
if historical and (source_event != "pull_request" or title != f"Publish PR Runtime Images {source_title}"
        or timestamp(download, "started_at") < upload_end):
    unavailable("historical publisher download does not follow the selected source upload")

artifact_name = f"pr-runtime-images-pr-merge-{merge_sha}"
inventory = read_object(f"runs/{source_id}/artifacts?name={artifact_name}&per_page=100&page=1")
artifacts = inventory.get("artifacts")
if (not positive_int(inventory.get("total_count")) or inventory["total_count"] != 1
        or not isinstance(artifacts, list) or len(artifacts) != 1 or not isinstance(artifacts[0], dict)):
    unavailable("legacy publication requires the original artifact in the complete inventory")
artifact = artifacts[0]
origin, digest = artifact.get("workflow_run"), artifact.get("digest")
if (not positive_int(artifact.get("id")) or artifact.get("name") != artifact_name
        or artifact.get("expired") is not False or not isinstance(origin, dict)
        or not positive_int(origin.get("id")) or origin["id"] != source["id"] or origin.get("head_sha") != source["head_sha"]
        or not isinstance(digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest)):
    unavailable("legacy artifact identity, digest, or retention is inconsistent")
created = timestamp(artifact, "created_at")
if not upload_start.replace(microsecond=0) <= created < upload_end.replace(microsecond=0) + timedelta(seconds=1):
    unavailable("legacy artifact did not originate in the selected upload step")
print(f"{artifact['id']}\t{digest}")
PY_PUBLICATION
}

# A newer attempt can supersede this source before its publisher appears.
check_current_source() {
  local publisher_deadline="$1" source_run_id="$2" source_run_attempt="$3"
  local source_created_at="$4" source_event="$5" source_workflow_id="$6"
  local remaining current_payload current_state current_kind current_id current_status current_conclusion
  local _current_url current_event current_created_at current_attempt current_workflow_id
  remaining=$((publisher_deadline - SECONDS))
  if (( remaining <= 0 )) ||
    ! current_payload="$(timeout "${remaining}s" gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${source_run_id}")" ||
    ! current_state="$(read_run_state native "${GITHUB_REPOSITORY}" <<<"${current_payload}")"; then
    printf 'Current runtime-image producer evidence is unavailable for run %s attempt %s; retrying.\n' \
      "${source_run_id}" "${source_run_attempt}" >&2
    return 1
  fi
  IFS=$'\t' read -r current_kind current_id current_status current_conclusion _current_url current_event \
    current_created_at current_attempt current_workflow_id <<<"${current_state}"
  if [[ "${current_kind}" != "found" || "${current_id}" != "${source_run_id}" ||
    "${current_event}" != "${source_event}" || "${current_workflow_id}" != "${source_workflow_id}" ||
    "${current_created_at}" != "${source_created_at}" ]]; then
    printf 'Current runtime-image producer does not match the selected run %s for %s.\n' \
      "${source_run_id}" "${image_tag}" >&2
    return 2
  fi
  if (( current_attempt > source_run_attempt )); then
    printf 'Runtime-image producer %s advanced from attempt %s to %s; selecting fresh proof.\n' \
      "${source_run_id}" "${source_run_attempt}" "${current_attempt}" >&2
    return 75
  fi
  if [[ "${current_attempt}" != "${source_run_attempt}" ||
    "${current_status}" != "completed" || "${current_conclusion}" != "success" ]]; then
    printf 'Current runtime-image producer does not match the successful run %s attempt %s for %s.\n' \
      "${source_run_id}" "${source_run_attempt}" "${image_tag}" >&2
    return 2
  fi
}

select_pr_publisher() {
  local source_run_id="$1" source_run_attempt="$2" source_created_at="$3" source_event="$4" publisher_deadline="$5"
  local publisher_payload="" event_payload publisher_event poll_upper_bound remaining
  poll_upper_bound="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  # Preserve complete discovery within the verified source creation window.
  for publisher_event in workflow_run repository_dispatch; do
    remaining=$((publisher_deadline - SECONDS))
    (( remaining > 0 )) || return 1
    event_payload="$(fetch_publisher_runs "${publisher_event}" "${source_created_at}" "${poll_upper_bound}" "${remaining}")" ||
      return 1
    publisher_payload+="${publisher_payload:+,}${event_payload}"
  done
  if ! read_publisher_state "${source_run_id}" "${source_run_attempt}" "${source_event}" <<<"[${publisher_payload}]"; then
    printf 'GitHub API response was empty or invalid while waiting for the trusted PR image publisher; retrying.\n' >&2
    return 1
  fi
}

select_later_typed_source() {
  local original_id="$1" original_created="$2" original_workflow="$3" publisher_deadline="$4"
  local remaining payload upper_bound
  remaining=$((publisher_deadline - SECONDS))
  (( remaining > 0 )) || return 1
  upper_bound="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  payload="$(fetch_publisher_runs repository_dispatch "${original_created}" "${upper_bound}" "${remaining}" runtime-images.yml)" ||
    return 1
  remaining=$((publisher_deadline - SECONDS))
  (( remaining > 0 )) || return 1
  read_run_state replacement "${GITHUB_REPOSITORY}" "${original_id}" "${original_created}" \
    "${original_workflow}" "${remaining}" <<<"${payload}"
}

wait_for_pr_publisher() {
  local source_run_id="$1"
  local source_run_attempt="$2"
  local source_created_at="$3"
  local source_event="$4"
  local source_workflow_id="$5"
  local coverage_unavailable=false
  local failed_publisher=''
  local publisher_start_epoch="${SECONDS}"
  local publisher_deadline=$((SECONDS + publisher_timeout_seconds))
  if (( publisher_deadline > deadline )); then publisher_deadline="$deadline"; fi
  if (( replacement_deadline > 0 && publisher_deadline > replacement_deadline )); then
    publisher_deadline="$replacement_deadline"
  fi
  while (( SECONDS < publisher_deadline )); do
    local publisher_state source_status=0
    if ! publisher_state="$(select_pr_publisher "${source_run_id}" "${source_run_attempt}" \
      "${source_created_at}" "${source_event}" "${publisher_deadline}")"; then
      coverage_unavailable=true
      check_current_source "${publisher_deadline}" "${source_run_id}" "${source_run_attempt}" \
        "${source_created_at}" "${source_event}" "${source_workflow_id}" || source_status=$?
      if (( source_status > 1 )); then return "${source_status}"; fi
      sleep "${sleep_seconds}"
      continue
    fi
    coverage_unavailable=false

    local state run_id run_status run_conclusion run_url publisher_attempt publisher_workflow_id publisher_event publisher_title selection_reason
    IFS=$'\t' read -r state run_id run_status run_conclusion run_url publisher_attempt \
      publisher_workflow_id publisher_event publisher_title selection_reason <<<"${publisher_state}"

    if [[ "${state}" == "missing" || "${run_status}" != "completed" || "${run_conclusion}" != "success" ]]; then
      check_current_source "${publisher_deadline}" "${source_run_id}" "${source_run_attempt}" \
        "${source_created_at}" "${source_event}" "${source_workflow_id}" || source_status=$?
      if (( source_status > 1 )); then return "${source_status}"; fi
      if (( source_status == 1 )); then sleep "${sleep_seconds}"; continue; fi
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

    if [[ "${run_status}" == "completed" && "${run_conclusion}" == "success" ]]; then
      if [[ "${selection_reason}" == retained-after-failed-requalification:* ]]; then
        local source_status=0
        check_current_source "${publisher_deadline}" "${source_run_id}" "${source_run_attempt}" \
          "${source_created_at}" "${source_event}" "${source_workflow_id}" || source_status=$?
        case "${source_status}" in
          0) ;;
          1) sleep "${sleep_seconds}"; continue ;;
          *) return "${source_status}" ;;
        esac
      fi
      local artifact_evidence
      if ! artifact_evidence="$(verify_publisher_attempt "${run_id}" "${publisher_attempt}" \
        "${publisher_workflow_id}" "${publisher_event}" "${publisher_title}" \
        "${source_run_id}" "${source_run_attempt}" "${source_workflow_id}" "${source_event}" "${selection_reason}" "$((publisher_deadline - SECONDS))")"; then
        printf 'Exact PR image publication and artifact proof is unavailable for run %s attempt %s.\n' \
          "${run_id}" "${publisher_attempt}" >&2
        local source_status=0
        check_current_source "${publisher_deadline}" "${source_run_id}" "${source_run_attempt}" \
          "${source_created_at}" "${source_event}" "${source_workflow_id}" || source_status=$?
        if (( source_status > 1 )); then return "${source_status}"; fi
        sleep "${sleep_seconds}"
        continue
      fi
      local refreshed_publisher_state
      if ! refreshed_publisher_state="$(select_pr_publisher "${source_run_id}" "${source_run_attempt}" \
        "${source_created_at}" "${source_event}" "${publisher_deadline}")"; then
        coverage_unavailable=true
        sleep "${sleep_seconds}"
        continue
      fi
      if [[ "${refreshed_publisher_state}" != "${publisher_state}" ]]; then
        printf 'Trusted PR image publisher selection changed for %s; selecting fresh proof.\n' "${image_tag}"
        continue
      fi
      local source_status=0 artifact_id artifact_digest
      check_current_source "${publisher_deadline}" "${source_run_id}" "${source_run_attempt}" \
        "${source_created_at}" "${source_event}" "${source_workflow_id}" || source_status=$?
      case "${source_status}" in
        0) ;;
        1) sleep "${sleep_seconds}"; continue ;;
        *) return "${source_status}" ;;
      esac
      (( SECONDS < publisher_deadline )) || break
      IFS=$'\t' read -r artifact_id artifact_digest <<<"${artifact_evidence}"
      printf 'Trusted PR image publisher %s succeeded for %s using source artifact %s (%s).\n' \
        "${run_id}" "${image_tag}" "${artifact_id}" "${artifact_digest}"
      return 0
    fi
    if [[ "${run_status}" == "completed" ]]; then
      if [[ "${failed_publisher}" != "${run_id}/${publisher_attempt}" ]]; then
        failed_publisher="${run_id}/${publisher_attempt}"
        printf 'Trusted PR image publisher %s completed with %s for %s.\n' \
          "${run_id}" "${run_conclusion}" "${image_tag}" >&2
        [[ -z "${run_url}" || "${run_url}" == "-" ]] || printf 'Workflow URL: %s\n' "${run_url}" >&2
      fi
      local candidate
      if candidate="$(select_later_typed_source "${source_run_id}" "${source_created_at}" \
        "${source_workflow_id}" "${publisher_deadline}")" && [[ "${candidate}" == found$'\t'* ]]; then
        replacement_source="${candidate}"
        replacement_deadline="${publisher_deadline}"
        printf 'A distinct later exact typed runtime producer succeeded; checking its own publication.\n' >&2
        return 75
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

replacement_source=''
replacement_deadline=0
while (( SECONDS < deadline )); do
  if [[ -n "${replacement_source}" ]]; then
    run_state="${replacement_source}"
    replacement_source=''
  elif ! workflow_payload="$(
    fetch_workflow_runs \
      "repos/${GITHUB_REPOSITORY}/actions/workflows/runtime-images.yml/runs?per_page=100" \
      "waiting for the runtime-images workflow"
  )"; then
    sleep "${sleep_seconds}"
    continue
  elif ! run_state="$(read_run_state <<<"${workflow_payload}")"; then
    printf 'GitHub API response was empty or invalid while waiting for the runtime-images workflow; retrying.\n' >&2
    sleep "${sleep_seconds}"
    continue
  fi

  IFS=$'\t' read -r state run_id run_status run_conclusion run_url run_event run_created_at run_attempt run_workflow_id <<<"${run_state}"

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
    printf 'Matching runtime-images workflow %s succeeded for %s after %ss.\n' \
      "${run_id}" "${image_tag}" "$((SECONDS - start_epoch))"
    if [[ "${wait_mode}" == "pull-request" ]]; then
      publisher_status=0
      wait_for_pr_publisher "${run_id}" "${run_attempt}" "${run_created_at}" "${run_event}" "${run_workflow_id}" ||
        publisher_status=$?
      if (( publisher_status == 75 )); then
        if [[ -z "${replacement_source}" ]]; then sleep "${sleep_seconds}"; fi
        continue
      fi
      if (( publisher_status != 0 )); then exit 1; fi
    fi
    exit 0
  fi

  if [[ "${run_status}" == "completed" ]]; then
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
