#!/usr/bin/env bash
# shellcheck disable=SC2016 # This contract intentionally matches literal workflow and source expressions.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

assert_job_condition() {
  local workflow="$1"
  local job="$2"
  local condition="$3"
  local path="$ROOT_DIR/.github/workflows/$workflow"

  if ! awk -v job="$job" -v condition="$condition" '
    $0 == "  " job ":" { in_job = 1; found = 1; next }
    in_job && /^  [A-Za-z0-9_-]+:/ { exit }
    in_job && index($0, condition) { matched = 1 }
    END { exit !(found && matched) }
  ' "$path"; then
    echo "$workflow job $job must be gated by PR base-ref changes" >&2
    exit 1
  fi
}

assert_job_contains() {
  local workflow="$1"
  local job="$2"
  local expected="$3"
  local path="$ROOT_DIR/.github/workflows/$workflow"

  if ! awk -v job="$job" -v expected="$expected" '
    $0 == "  " job ":" { in_job = 1; found = 1; next }
    in_job && /^  [A-Za-z0-9_-]+:/ { exit }
    in_job && index($0, expected) { matched = 1 }
    END { exit !(found && matched) }
  ' "$path"; then
    echo "$workflow job $job must contain: $expected" >&2
    exit 1
  fi
}

assert_job_excludes() {
  local workflow="$1"
  local job="$2"
  local forbidden="$3"
  local path="$ROOT_DIR/.github/workflows/$workflow"

  if ! awk -v job="$job" -v forbidden="$forbidden" '
    $0 == "  " job ":" { in_job = 1; found = 1; next }
    in_job && /^  [A-Za-z0-9_-]+:/ { exit }
    in_job && index($0, forbidden) { forbidden_found = 1 }
    END { exit !(found && !forbidden_found) }
  ' "$path"; then
    echo "$workflow job $job must exist and must not contain: $forbidden" >&2
    exit 1
  fi
}

require_contains() {
  local path="$1"
  local expected="$2"

  if ! grep -Fq -- "$expected" "$path"; then
    echo "$path must contain: $expected" >&2
    exit 1
  fi
}

require_contains_block() {
  local path="$1"
  local expected="$2"

  if ! python3 - "$path" "$expected" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
expected = sys.argv[2]
if expected not in path.read_text(encoding="utf-8"):
    raise SystemExit(1)
PY
  then
    echo "$path must contain the complete block:" >&2
    printf '%s\n' "$expected" >&2
    exit 1
  fi
}

require_exact_line() {
  local path="$1"
  local expected="$2"

  if ! grep -Fxq -- "$expected" "$path"; then
    echo "$path must contain the exact line: $expected" >&2
    exit 1
  fi
}

assert_publish_checkout_configuration() {
  local path="$1"

  if ! python3 - "$path" <<'PY'
import sys
from pathlib import Path

import yaml


path = Path(sys.argv[1])
workflow = yaml.safe_load(path.read_text(encoding="utf-8"))
jobs = workflow.get("jobs") if isinstance(workflow, dict) else None
checkouts = []
for job_name, job in (jobs.items() if isinstance(jobs, dict) else []):
    steps = job.get("steps") if isinstance(job, dict) else None
    for step in steps or []:
        if (
            isinstance(step, dict)
            and isinstance(step.get("uses"), str)
            and step["uses"].startswith("actions/checkout@")
        ):
            checkouts.append((job_name, step))
if len(checkouts) != 1:
    raise SystemExit("workflow must contain exactly one actions/checkout step")

checkout_job, checkout = checkouts[0]
if checkout_job != "publish":
    raise SystemExit("the workflow checkout must belong to the publish job")

checkout_with = checkout.get("with")
if not isinstance(checkout_with, dict):
    raise SystemExit("publish checkout must define a with mapping")
if set(checkout_with) != {"ref", "persist-credentials"}:
    raise SystemExit("publish checkout must define exactly ref and persist-credentials")
if checkout_with.get("ref") != "${{ github.event.repository.default_branch }}":
    raise SystemExit("publish checkout must use the repository default branch")
if type(checkout_with.get("persist-credentials")) is not bool or checkout_with["persist-credentials"] is not False:
    raise SystemExit("publish checkout must disable persisted credentials with boolean false")
PY
  then
    echo "$path publish job must have one default-branch, non-persisting checkout" >&2
    exit 1
  fi
}

require_ordered_sequence() {
  local path="$1"
  shift

  if ! awk -v sequence="$(printf '%s\034' "$@")" '
    BEGIN {
      count = split(sequence, expected, "\034") - 1
      current = 1
    }
    {
      remainder = $0
      while (current <= count) {
        matched_at = index(remainder, expected[current])
        if (!matched_at) {
          break
        }
        remainder = substr(remainder, matched_at + length(expected[current]))
        current++
      }
    }
    END { exit !(current > count) }
  ' "$path"; then
    echo "$path must contain the required sequence in order: $*" >&2
    exit 1
  fi
}

require_branch_return() {
  local path="$1"
  local predicate="$2"

  if ! python3 - "$path" "$predicate" <<'PY'
import re
import sys
from pathlib import Path


RETURN_STATEMENT_RE = re.compile(
    r"(?<![A-Za-z0-9_$])return(?![A-Za-z0-9_$])\s*;"
)


def mask_non_code(source: str) -> str:
    # This lightweight scanner does not parse JavaScript regex literals. Braces
    # inside regex literals can therefore affect branch-depth tracking.
    masked = list(source)
    state = "code"
    escaped = False
    block_comment = False
    syntax_depth = 0
    template_resume_depths: list[int] = []
    index = 0

    while index < len(source):
        char = source[index]
        next_char = source[index + 1] if index + 1 < len(source) else ""

        if block_comment:
            if char == "*" and next_char == "/":
                masked[index] = masked[index + 1] = " "
                block_comment = False
                index += 2
                continue
            if char != "\n":
                masked[index] = " "
            index += 1
            continue

        if state in {"single", "double"}:
            if char != "\n":
                masked[index] = " "
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif (state == "single" and char == "'") or (
                state == "double" and char == '"'
            ):
                state = "code"
            index += 1
            continue

        if state == "template":
            if char == "\\":
                masked[index] = " "
                if index + 1 < len(source):
                    if source[index + 1] != "\n":
                        masked[index + 1] = " "
                    index += 2
                    continue
            if char == "`":
                masked[index] = " "
                state = "code"
                index += 1
                continue
            if char == "$" and next_char == "{":
                masked[index] = " "
                template_resume_depths.append(syntax_depth)
                syntax_depth += 1
                index += 2
                state = "code"
                continue
            if char != "\n":
                masked[index] = " "
            index += 1
            continue

        if char == "/" and next_char == "/":
            while index < len(source) and source[index] != "\n":
                masked[index] = " "
                index += 1
            continue
        if char == "/" and next_char == "*":
            masked[index] = masked[index + 1] = " "
            block_comment = True
            index += 2
            continue
        if char == "'":
            masked[index] = " "
            state = "single"
        elif char == '"':
            masked[index] = " "
            state = "double"
        elif char == "`":
            masked[index] = " "
            state = "template"
        elif char == "{":
            syntax_depth += 1
        elif char == "}":
            syntax_depth -= 1
            if template_resume_depths and syntax_depth == template_resume_depths[-1]:
                template_resume_depths.pop()
                state = "template"
        index += 1

    return "".join(masked)


path = Path(sys.argv[1])
predicate = sys.argv[2]
# This helper intentionally anchors to the first predicate occurrence and accepts only a return
# directly inside that branch, not one nested in a child block.
source = path.read_text(encoding="utf-8")
predicate_index = source.find(predicate)
if predicate_index < 0:
    raise SystemExit(1)

masked = mask_non_code(source[predicate_index:])
branch_open = masked.find("{")
if branch_open < 0:
    raise SystemExit(1)

depth = 0
found_return = False
for index in range(branch_open, len(masked)):
    char = masked[index]
    if char == "{":
        depth += 1
    elif char == "}":
        depth -= 1
        if depth == 0:
            raise SystemExit(0 if found_return else 1)
    elif depth == 1 and RETURN_STATEMENT_RE.match(masked, index):
        found_return = True

raise SystemExit(1)
PY
  then
    echo "$path must return within the branch containing: $predicate" >&2
    exit 1
  fi
}

required_condition="github.event.action != 'edited' || github.event.changes.base.ref != null"
ci_path="$ROOT_DIR/.github/workflows/ci.yml"
runtime_images_path="$ROOT_DIR/.github/workflows/runtime-images.yml"
pr_image_publisher_path="$ROOT_DIR/.github/workflows/publish-pr-runtime-images.yml"
smoke_path="$ROOT_DIR/.github/workflows/smoke.yml"
image_wait_path="$ROOT_DIR/dev-tools/hosted/shared/wait-for-runtime-images.sh"
preview_path="$ROOT_DIR/.github/workflows/preview.yml"
trusted_preview_path="$ROOT_DIR/.github/workflows/hosted-identity-request.yml"
preview_reconciler_path="$ROOT_DIR/.github/workflows/preview-reconciler.yml"
preview_comment_publisher_path="$ROOT_DIR/dev-tools/hosted/preview/publish-preview-comment.js"
preview_comment_test_path="$ROOT_DIR/dev-tools/tests/publish-preview-comment.test.cjs"

node --test "$preview_comment_test_path"

security_path="$ROOT_DIR/.github/workflows/security.yml"

for path in "$ci_path" "$security_path" "$smoke_path"; do
  require_contains "$path" 'types: [opened, synchronize, reopened, edited]'
  require_contains "$path" "format('metadata-{0}', github.run_id) || 'required' }}"
  require_contains "$path" 'github.event.pull_request.number'
  require_contains "$path" '  cancel-in-progress: true'
done

require_exact_line "$ci_path" '    name: Validation Summary'
require_exact_line "$security_path" '    name: Security Summary'
require_exact_line "$smoke_path" '    name: Smoke Summary'
# A metadata-only edit starts a non-required controller job so its preservation
# failure cannot create a second failed branch-protection context named Smoke
# Gate. A base retarget still uses the canonical required name.
# shellcheck disable=SC2016 # Assert literal GitHub expression syntax.
assert_job_contains smoke.yml smoke-gate "name: \${{ github.event.action == 'edited' && github.event.changes.base.ref == null && 'PR Metadata Edit (Smoke Gate)' || 'Smoke Gate' }}"
assert_job_contains smoke.yml smoke-summary-pending 'name: Smoke Summary (Pending)'
assert_job_contains smoke.yml smoke-summary-pending 'tracked-by-smoke-gate'
assert_job_contains smoke.yml smoke-summary 'needs: [changes, smoke-gate, smoke-summary-pending]'
# shellcheck disable=SC2016 # This assertion intentionally matches a literal GitHub expression.
assert_job_contains smoke.yml smoke-summary 'SMOKE_GATE_RESULT: ${{ needs.smoke-gate.result }}'
if grep -Eq '^  smoke-lite:' "$smoke_path"; then
  echo "smoke.yml must not restore the redundant smoke-lite job" >&2
  exit 1
fi

for job in \
  changes \
  buf-check \
  docker-lint \
  shellcheck \
  python-script-validation \
  dev-tool-contract-checks \
  yaml-lint \
  metrics-cardinality-check \
  flyway-migration-sanity-checks \
  grpc-transport-config-sanity-checks \
  workflow-lint \
  helm-lint \
  helm-render-validation \
  docs-check \
  frontend-checks \
  build-and-test \
  validation-summary; do
  assert_job_condition ci.yml "$job" "$required_condition"
done

for job in changes smoke-summary-pending smoke-summary; do
  assert_job_condition smoke.yml "$job" "$required_condition"
done
assert_job_contains smoke.yml smoke-summary-pending 'const smokeGateStatus = fullEnabled ? "pending" : "not-required";'
assert_job_contains smoke.yml smoke-summary-pending 'Full-stack smoke runs in Build Runtime Images and is tracked by Smoke Gate'
assert_job_contains smoke.yml smoke-summary-pending 'firemud-smoke-summary'
assert_job_contains smoke.yml smoke-summary-pending 'comment.user?.login === "github-actions[bot]"'
assert_job_contains smoke.yml smoke-summary-pending 'github.paginate('
assert_job_contains smoke.yml smoke-summary-pending 'comment.created_at || ""'
assert_job_contains smoke.yml smoke-summary-pending 'candidateTimestamp < oldestTimestamp'
assert_job_contains smoke.yml smoke-summary-pending 'const seenCommentIds = new Set();'
assert_job_contains smoke.yml smoke-summary-pending 'String(comment.id) === String(existing?.id)'
assert_job_excludes smoke.yml smoke-summary-pending 'comment.updated_at'
assert_job_contains smoke.yml smoke-summary 'const smokeGateStatus ='
assert_job_contains smoke.yml smoke-summary 'firemud-smoke-summary'
assert_job_contains smoke.yml smoke-summary 'comment.user?.login === "github-actions[bot]"'
assert_job_contains smoke.yml smoke-summary 'github.paginate('
assert_job_contains smoke.yml smoke-summary 'comment.created_at || ""'
assert_job_contains smoke.yml smoke-summary 'candidateTimestamp < oldestTimestamp'
assert_job_contains smoke.yml smoke-summary 'const seenCommentIds = new Set();'
assert_job_contains smoke.yml smoke-summary 'String(comment.id) === String(existing?.id)'
assert_job_excludes smoke.yml smoke-summary 'comment.updated_at'
assert_job_contains smoke.yml smoke-gate 'pull-requests: read'
assert_job_contains smoke.yml smoke-gate 'github.rest.pulls.get'
assert_job_contains smoke.yml smoke-gate 'pullRequest.state !== "open"'
assert_job_contains smoke.yml smoke-gate 'pullRequest.head.sha !== headSha'
assert_job_contains smoke.yml smoke-gate 'pullRequest.base.ref !== baseRef'
assert_job_excludes smoke.yml smoke-gate 'pullRequest.base.sha'
assert_job_contains smoke.yml smoke-gate 'github.rest.git.getRef'
assert_job_contains smoke.yml smoke-gate 'github.rest.repos.getCommit'
assert_job_contains smoke.yml smoke-gate 'parents.length !== 2'
assert_job_contains smoke.yml smoke-gate 'parents[0]?.sha !== currentBaseSha'
assert_job_contains smoke.yml smoke-gate 'parents[1]?.sha !== headSha'
assert_job_contains smoke.yml smoke-gate 'const expectedRuntimeEvent = eventIdentityMatches(currentIdentity)'
assert_job_contains smoke.yml smoke-gate 'event: expectedRuntimeEvent'
assert_job_contains smoke.yml smoke-gate 'run.event !== expectedRuntimeEvent'
assert_job_contains smoke.yml smoke-gate 'expectedRuntimeEvent === "repository_dispatch"'
assert_job_contains smoke.yml smoke-gate 'no trusted refresh dispatch exists or it did not complete'
assert_job_contains smoke.yml smoke-gate 'The stale pull_request source is not accepted.'
assert_job_contains smoke.yml smoke-gate 'job.name === "PR Full-Stack Smoke"'
assert_job_contains smoke.yml smoke-gate 'step.name === "Run credential-free full-stack smoke"'
assert_job_contains smoke.yml smoke-gate 'Stopping stale smoke gate before accepting full-stack proof'
assert_job_contains smoke.yml smoke-gate 'continue smokeGatePolling'
assert_job_excludes smoke.yml smoke-gate 'github.rest.repos.createDispatchEvent'

# Reproduce the metadata-edit sequence structurally: the edit has its own
# concurrency namespace, all runtime construction jobs remain skipped, and no
# dispatch-capable step exists in Smoke Gate. A live tuple change is handled by
# the already-proved exact repository_dispatch refresh path instead.
python3 - "$smoke_path" "$runtime_images_path" <<'PY'
from pathlib import Path
import sys

import yaml

smoke_path, runtime_path = map(Path, sys.argv[1:])
smoke = yaml.load(smoke_path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
runtime = yaml.load(runtime_path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)

metadata_name = (
    "${{ github.event.action == 'edited' && github.event.changes.base.ref == null "
    "&& 'PR Metadata Edit (Smoke Gate)' || 'Smoke Gate' }}"
)
if smoke["jobs"]["smoke-gate"].get("name") != metadata_name:
    raise SystemExit("metadata-only smoke must use a non-required job context")

runtime_jobs = runtime["jobs"]
metadata_guard = "github.event.action != 'edited' || github.event.changes.base.ref != null"
for job_name in ("image-meta", "pr-local-smoke", "pr-controller-smoke"):
    condition = runtime_jobs[job_name].get("if", "")
    if metadata_guard not in condition:
        raise SystemExit(f"{job_name} can run for a metadata-only edited event")

concurrency_group = runtime["concurrency"]["group"]
if "&& 'metadata' || 'required'" not in concurrency_group:
    raise SystemExit("metadata-only runtime events can cancel substantive runtime builds")

dispatch_condition = runtime_jobs["dispatch-pr-base-refreshes"].get("if", "")
if "github.event_name == 'workflow_run'" not in dispatch_condition:
    raise SystemExit("runtime refresh dispatch is not isolated from pull-request metadata events")
PY

run_image_meta_exact_parent_fixture() {
  python3 - "$runtime_images_path" <<'PY'
import json
import subprocess
import sys
from pathlib import Path


workflow_path = Path(sys.argv[1])
workflow_source = workflow_path.read_text(encoding="utf-8")
predicate = (
    ".sha == $merge_sha and (.parents | type) == \"array\" and "
    "(.parents | length) == 2 and .parents[0].sha == $base_sha and "
    ".parents[1].sha == $head_sha"
)
if predicate not in workflow_source:
    raise SystemExit("runtime image metadata must retain the exact ordered-parent predicate")


def sha(prefix, fill):
    return prefix + fill * (40 - len(prefix))


stale_event_base = sha("d253", "a")
current_base = sha("7d995", "b")
head = sha("731", "c")
merge = sha("156", "d")
merge_commit = {
    "sha": merge,
    "parents": [{"sha": current_base}, {"sha": head}],
}


def image_meta_output(event_name, base_sha):
    completed = subprocess.run(
        [
            "jq",
            "-e",
            "--arg",
            "base_sha",
            base_sha,
            "--arg",
            "head_sha",
            head,
            "--arg",
            "merge_sha",
            merge,
            predicate,
        ],
        input=json.dumps(merge_commit),
        text=True,
        capture_output=True,
        check=False,
    )
    if completed.returncode != 0:
        return None
    return {
        "event": event_name,
        "base_sha": base_sha,
        "head_sha": head,
        "merge_sha": merge,
    }


if image_meta_output("pull_request", stale_event_base) is not None:
    raise SystemExit("stale ordinary PR tuple must not produce runtime metadata")
refresh_output = image_meta_output("repository_dispatch", current_base)
if refresh_output is None or refresh_output["event"] != "repository_dispatch":
    raise SystemExit("valid typed refresh tuple must produce runtime metadata")
if refresh_output["base_sha"] != current_base or refresh_output["head_sha"] != head or refresh_output["merge_sha"] != merge:
    raise SystemExit("typed refresh metadata did not preserve the exact current tuple")

PY
}

run_image_meta_exact_parent_fixture

require_contains "$runtime_images_path" 'types: [opened, synchronize, reopened, edited]'
require_contains "$runtime_images_path" 'types: [pr-runtime-base-refresh]'
require_contains "$runtime_images_path" 'repository_dispatch:'
require_contains "$runtime_images_path" "&& 'metadata' || 'required' }}"
require_contains "$runtime_images_path" '  cancel-in-progress: true'
require_contains "$runtime_images_path" 'run-name: Build Runtime Images '
require_contains "$runtime_images_path" "format('secure-pr-artifact pr-{0}"
require_contains "$runtime_images_path" 'github.event.pull_request.base.sha'
require_contains "$runtime_images_path" 'github.event.pull_request.head.sha'
require_contains "$runtime_images_path" 'github.event.client_payload.base_sha'
require_contains "$runtime_images_path" 'github.event.client_payload.head_sha'
require_contains "$runtime_images_path" 'github.event.client_payload.merge_sha'
require_contains "$runtime_images_path" 'github.sha'
require_contains "$runtime_images_path" 'mode-{4}'
require_contains "$runtime_images_path" 'Runtime PR refresh must run from the repository default branch.'
require_contains "$runtime_images_path" 'Runtime PR refresh base SHA is not the current ${BASE_REF} branch ref.'
require_contains "$runtime_images_path" 'Runtime PR refresh payload is stale, conflicted, fork-owned, closed, or has an invalid merge tuple.'
require_contains "$runtime_images_path" 'Runtime PR refresh merge commit does not have the exact current base/head parents.'
require_contains "$runtime_images_path" "PR event merge commit does not have the event's exact base/head parents; refusing a stale PR artifact."
require_contains "$runtime_images_path" 'GH_TOKEN: ${{ github.token }}'
require_contains "$runtime_images_path" "format('trusted-branch branch-{0} sha-{1}'"
require_contains "$runtime_images_path" "format('base-refresh-only branch-{0} sha-{1} ci-{2}'"
require_contains "$runtime_images_path" '### PR runtime images and applicable full-stack smoke'
require_contains "$runtime_images_path" 'built the PR merge commit and runs full-stack smoke when required by change scope'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'github.event.workflow_run.event == '\''push'\'''
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'github.event.workflow_run.status == '\''completed'\'''
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'github.event.workflow_run.head_repository.full_name == github.repository'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'contents: write'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'github.rest.git.getRef'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'github.rest.pulls.list'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'github.rest.repos.getCommit'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'currentBaseRef !== baseBranch'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'parents.length !== 2'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'parents[0] !== baseSha'
assert_job_excludes runtime-images.yml dispatch-pr-base-refreshes '.base.sha'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'event_type: "pr-runtime-base-refresh"'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'pr_number: tuple.prNumber'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'base_sha: tuple.baseSha'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'head_sha: tuple.headSha'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'merge_sha: tuple.mergeSha'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'event_type: "preview-deploy"'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'action: "deploy"'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes 'preview_domain: "preview.firedevops.net"'
assert_job_contains runtime-images.yml dispatch-pr-base-refreshes '".github/actions/load-workflow-tool-versions/"'
assert_job_excludes runtime-images.yml dispatch-pr-base-refreshes 'packages: write'
assert_job_excludes runtime-images.yml dispatch-pr-base-refreshes 'environment:'
assert_job_excludes runtime-images.yml dispatch-pr-base-refreshes 'secrets.'
assert_job_contains runtime-images.yml pr-local-smoke 'github.event_name == '\''repository_dispatch'\'''
assert_job_contains runtime-images.yml pr-local-smoke 'BASE_SHA="${{ needs.image-meta.outputs.base_sha }}"'
assert_job_contains runtime-images.yml pr-local-smoke 'MERGE_SHA="${{ needs.image-meta.outputs.merge_sha }}"'
assert_job_contains runtime-images.yml pr-controller-smoke 'github.event_name == '\''repository_dispatch'\'''
require_contains "$image_wait_path" 'display_title = run.get("display_title", "")'
require_contains "$image_wait_path" 'display_title.startswith("Build Runtime Images trusted-branch ")'
require_contains "$image_wait_path" 'and f"sha-{head_sha}" in tokens'
require_contains "$image_wait_path" 'and "branch-develop" in tokens'
require_contains "$image_wait_path" 'and f"base-{base_sha}" in tokens'
require_contains "$image_wait_path" 'and f"merge-{merge_sha}" in tokens'
require_contains "$image_wait_path" 'run.get("event") == "workflow_run"'
require_contains "$image_wait_path" 'gh api --paginate --slurp'
require_contains "$image_wait_path" 'GitHub API poll failed while %s; retrying within the existing wait deadline.'
# shellcheck disable=SC2016 # These assertions intentionally match literal shell source.
require_contains "$image_wait_path" 'if ! workflow_payload="$('
# shellcheck disable=SC2016 # These assertions intentionally match literal shell source.
require_contains "$image_wait_path" 'if ! publisher_payload="$('
if grep -Eq '^concurrency:' "$preview_path"; then
  echo "Preview workflow must not cancel an active lifecycle from workflow-level concurrency" >&2
  exit 1
fi
assert_job_contains preview.yml preview-plan "group: preview-plan-\${{ github.event_name == 'pull_request_target' && github.event.pull_request.number || github.event.client_payload.pr_number || github.ref }}"
assert_job_contains preview.yml preview-plan 'cancel-in-progress: true'
assert_job_excludes preview.yml preview-plan 'Publish preview lifecycle state'
assert_job_excludes preview.yml preview-plan 'firemud-preview-summary'
assert_job_excludes preview.yml preview-plan 'publish-preview-comment.js'
assert_job_excludes preview.yml preview-plan 'write-preview-summary.sh'
if grep -Eq '^  preview-(deploy|destroy):' "$preview_path"; then
  echo "Preview source workflow must not contain privileged deploy or destroy jobs" >&2
  exit 1
fi
for forbidden in \
  'self-hosted' \
  'environment: pr-preview' \
  'secrets.' \
  'issues: write' \
  'pull-requests: write' \
  'packages: write' \
  'kubectl ' \
  'helm upgrade'; do
  if grep -Fq -- "$forbidden" "$preview_path"; then
    echo "Preview source workflow must not contain privileged source-side content: $forbidden" >&2
    exit 1
  fi
done
require_contains "$preview_path" 'run-name: ${{ github.event_name == '
require_contains "$preview_path" "format('Preview dispatch pr-{0}-base-{1}-head-{2}-merge-{3}', github.event.client_payload.pr_number, github.event.client_payload.base_sha || 'unknown', github.event.client_payload.head_sha, github.event.client_payload.merge_sha || 'unknown')"
require_contains "$preview_path" 'contents: read'
require_contains "$preview_path" 'pull-requests: read'
require_contains "$preview_path" 'pull_request_target:'
require_contains "$preview_path" 'types: [preview-deploy, preview-destroy]'
require_contains "$preview_path" '      - opened'
require_contains "$preview_path" '      - synchronize'
require_contains "$preview_path" '      - reopened'
require_contains "$preview_path" '      - labeled'
require_contains "$preview_path" '      - unlabeled'
require_contains "$preview_path" '      - edited'
require_contains "$preview_path" 'CLIENT_HEAD_SHA: ${{ github.event.client_payload.head_sha }}'
require_contains "$preview_path" 'CLIENT_BASE_REF: ${{ github.event.client_payload.base_ref }}'
require_contains "$preview_path" 'CLIENT_BASE_SHA: ${{ github.event.client_payload.base_sha }}'
require_contains "$preview_path" 'CLIENT_MERGE_SHA: ${{ github.event.client_payload.merge_sha }}'
require_contains "$preview_path" 'CLIENT_IMAGE_TAG: ${{ github.event.client_payload.image_tag }}'
require_contains "$preview_path" 'CLIENT_PREVIEW_DOMAIN: ${{ github.event.client_payload.preview_domain }}'
if grep -Fq 'workflow_dispatch:' "$preview_path"; then
  echo "Preview source workflow must not expose a branch-selectable workflow_dispatch trigger" >&2
  exit 1
fi
if grep -Eq '^      - closed$' "$preview_path"; then
  echo "Preview source workflow must leave close cleanup to the trusted workflow" >&2
  exit 1
fi
require_contains "$preview_path" 'Check out trusted default branch for preview plan'
require_contains "$preview_path" 'ref: ${{ github.event.repository.default_branch }}'
require_contains "$preview_path" "github.event_name == 'pull_request_target'"
require_contains "$preview_path" "github.event_name == 'repository_dispatch'"
require_contains "$preview_path" 'github.event.pull_request.head.repo.full_name == github.repository'
require_contains "$preview_path" 'github.event.pull_request.base.repo.full_name == github.repository'
if grep -Fq 'github.event.pull_request.merge_commit_sha' "$preview_path"; then
  echo "Preview source must resolve a fresh REST test merge, not trust the event payload" >&2
  exit 1
fi
require_contains "$preview_path" 'MERGE_RETRY_LIMIT=5'
require_contains "$preview_path" 'Preview merge computation unavailable'
require_contains "$preview_path" 'Stale preview head SHA'
require_contains "$preview_path" '"$CURRENT_BASE_REF" != main && "$CURRENT_BASE_REF" != develop'
require_contains "$preview_path" 'Stale preview dispatch tuple'
require_contains "$preview_path" 'Stale preview image identity'
require_contains "$preview_path" 'Incomplete preview dispatch tuple'
require_contains "$preview_path" '-n "$PLANNED_IMAGE_TAG" ]]; then'

# Execute the preview plan's refresh predicate with the documented minimal
# repository_dispatch payload. A live stacked base must refresh its exact
# merge image even when the dispatch carries no planned tuple.
python3 - "$preview_path" <<'PY'
import shlex
import subprocess
import sys
from pathlib import Path


workflow_path = Path(sys.argv[1])
workflow = workflow_path.read_text(encoding="utf-8")
predicate_start = workflow.index(
    '            if [[ "$EVENT_NAME" == pull_request_target && "$CURRENT_BASE_REF" != main && "$CURRENT_BASE_REF" != develop ]] ||'
)
predicate_end = workflow.index(
    '\n            if [[ "$ACTION" != deploy ]]; then',
    predicate_start,
)
predicate = workflow[predicate_start:predicate_end]


def refresh_required(event_name, current_base_ref, planned_base_sha="", planned_image_tag=""):
    script = "\n".join(
        [
            "set -euo pipefail",
            f"EVENT_NAME={shlex.quote(event_name)}",
            f"CURRENT_BASE_REF={shlex.quote(current_base_ref)}",
            f"PLANNED_BASE_SHA={shlex.quote(planned_base_sha)}",
            f"PLANNED_IMAGE_TAG={shlex.quote(planned_image_tag)}",
            "BASE_REFRESH_REQUIRED=false",
            predicate,
            'printf \'%s\\n\' "$BASE_REFRESH_REQUIRED"',
        ]
    )
    completed = subprocess.run(
        ["bash", "-c", script],
        text=True,
        capture_output=True,
        check=False,
    )
    if completed.returncode != 0:
        raise SystemExit(
            f"preview refresh predicate failed: {completed.stderr.strip()}"
        )
    return completed.stdout.strip()


if refresh_required("repository_dispatch", "feature/parent") != "true":
    raise SystemExit(
        "minimal repository_dispatch payload must refresh a live stacked base image"
    )
if refresh_required("repository_dispatch", "develop") != "false":
    raise SystemExit("ordinary develop previews must continue reusing base images")
if refresh_required(
    "repository_dispatch", "develop", "a" * 40, "base-image"
) != "true":
    raise SystemExit("planned image refresh must remain enabled for ordinary bases")
PY

assert_job_contains preview.yml preview-plan 'resolve-preview-image-tag.sh'
assert_job_contains preview.yml preview-plan 'Expected the tested PR merge tag or immutable base SHA.'
assert_job_contains preview.yml preview-plan '"$MERGE_SHA" "$PR_NUMBER" "$BASE_SHA"'
assert_job_contains preview.yml preview-plan 'pr-merge-${MERGE_SHA}'
require_contains "$ROOT_DIR/dev-tools/hosted/preview/resolve-preview-image-tag.sh" 'expected_file_count'
require_contains "$ROOT_DIR/dev-tools/hosted/preview/resolve-preview-image-tag.sh" 'current_merge_sha'
require_contains "$ROOT_DIR/dev-tools/hosted/preview/resolve-preview-image-tag.sh" 'refusing to select base images'
assert_job_contains preview.yml preview-plan 'preview.firedevops.net'
assert_job_contains preview.yml preview-render 'runs-on: ubuntu-latest'
assert_job_contains preview.yml preview-render "needs.preview-plan.outputs.action == 'deploy'"
assert_job_contains preview.yml preview-render 'ref: refs/pull/${{ needs.preview-plan.outputs.pr_number }}/merge'
assert_job_contains preview.yml preview-render 'fetch-depth: 2'
assert_job_contains preview.yml preview-render 'expected_merge_parents="$MERGE_SHA $BASE_SHA $HEAD_SHA"'
assert_job_contains preview.yml preview-render 'The checked-out merge ref is not the planned two-parent merge'
assert_job_contains preview.yml preview-render 'forbidden = {'
assert_job_contains preview.yml preview-render 'render contains forbidden object not in explicit exclusion allowlist'
assert_job_contains preview.yml preview-render 'validate-preview-artifact.py'
assert_job_contains preview.yml preview-render 'sanitize "$filtered" "$sanitized"'
for binding in manifestSha256 repository sourceRunId prNumber baseSha headSha mergeSha imageTag hostname; do
  assert_job_contains preview.yml preview-render "$binding"
done
assert_job_contains preview.yml preview-render 'if $event == "repository_dispatch" then {action:$action}'
assert_job_contains preview.yml preview-render 'name: preview-render-pr-${{ needs.preview-plan.outputs.pr_number }}-${{ needs.preview-plan.outputs.head_sha }}'
assert_job_contains preview.yml preview-destroy-intent 'runs-on: ubuntu-latest'
assert_job_contains preview.yml preview-destroy-intent "github.event_name == 'repository_dispatch'"
assert_job_contains preview.yml preview-destroy-intent "needs.preview-plan.outputs.action == 'destroy'"
assert_job_contains preview.yml preview-destroy-intent '--arg event repository_dispatch'
assert_job_contains preview.yml preview-destroy-intent 'schemaVersion:1,event:$event,action:$action'
assert_job_contains preview.yml preview-destroy-intent 'name: preview-intent-pr-${{ needs.preview-plan.outputs.pr_number }}-${{ needs.preview-plan.outputs.head_sha }}'
assert_job_excludes preview.yml preview-destroy-intent "github.event_name == 'pull_request'"

# The trusted default-branch workflow owns all runtime and identity mutations.
require_contains "$trusted_preview_path" "github.event.workflow_run.head_repository.full_name == github.repository"
require_contains "$trusted_preview_path" "github.event.workflow_run.event == 'pull_request_target' || github.event.workflow_run.event == 'repository_dispatch'"
require_contains "$trusted_preview_path" "github.event_name == 'pull_request_target' && github.event.action == 'closed'"
require_contains "$trusted_preview_path" 'require_source_field head-branch "$DEFAULT_BRANCH"'
require_contains "$trusted_preview_path" 'preview-(render|intent)-pr-'
require_contains "$trusted_preview_path" 'preview-${ARTIFACT_KIND}-pr-${PR_NUMBER}-${EXPECTED_HEAD_SHA}'
require_contains "$trusted_preview_path" 'metadata_event="$(jq -r'
require_contains "$trusted_preview_path" 'metadata_action="$(jq -r'
require_contains "$trusted_preview_path" 'validate-preview-intent.py'
require_contains "$trusted_preview_path" 'repository="$(jq -r'
require_contains "$trusted_preview_path" '[[ "$repository" == "$GITHUB_REPOSITORY" ]]'
require_contains "$trusted_preview_path" 'base_ref="$(jq -r'
require_contains "$trusted_preview_path" 'Ignoring lifecycle event without the current pull-request test-merge SHA.'
require_contains "$trusted_preview_path" 'expected_merge_image_tag="pr-merge-${merge_sha}"'
require_contains "$trusted_preview_path" 'labels_json="$(jq -c'
require_contains "$trusted_preview_path" 'preview-eligibility.py'
require_contains "$trusted_preview_path" '[[ "$current_head_sha" == "$EXPECTED_HEAD_SHA" ]] || emit_no_action'
require_contains "$trusted_preview_path" 'revalidate-preview-source-binding.sh'
require_contains "$ROOT_DIR/dev-tools/hosted/preview/revalidate-preview-source-binding.sh" 'Preview source binding changed'
require_contains "$trusted_preview_path" 'CLEANUP_STATE=open'
require_contains "$trusted_preview_path" 'RETIRE_IDENTITY=true'
require_contains "$trusted_preview_path" "needs.validate-target.outputs.cleanup_state == 'open'"
require_contains "$trusted_preview_path" '--open-cleanup "$PR_NUMBER" "$EXPECTED_HEAD_SHA"'
require_contains "$trusted_preview_path" '--cleanup "$PR_NUMBER" "$EXPECTED_HEAD_SHA"'
require_contains "$trusted_preview_path" "needs.validate-target.outputs.retire_identity == 'true'"
for job in prepare-runtime deploy-runtime destroy-runtime retire-identity; do
  assert_job_contains hosted-identity-request.yml "$job" 'runs-on:'
  assert_job_contains hosted-identity-request.yml "$job" 'self-hosted'
  assert_job_contains hosted-identity-request.yml "$job" 'environment: trusted-hosted-cluster'
done
require_contains "$preview_reconciler_path" 'actions/runs?branch=${DEFAULT_BRANCH}&status=${active_status}&per_page=100'
require_contains "$preview_reconciler_path" 'gh api --paginate --slurp'
require_contains "$preview_reconciler_path" '.display_title == $run_name'
require_contains "$preview_reconciler_path" '"Preview dispatch pr-${pr_number}-base-${base_sha}-head-${head_sha}-merge-${merge_sha}"'
require_contains "$preview_reconciler_path" '"repos/${GITHUB_REPOSITORY}/dispatches"'
require_contains "$preview_reconciler_path" '-f event_type=preview-deploy'
require_contains "$preview_reconciler_path" 'client_payload[head_sha]=${head_sha}'
require_contains "$preview_reconciler_path" 'client_payload[base_ref]=${pr_base_ref}'
require_contains "$preview_reconciler_path" 'client_payload[base_sha]=${base_sha}'
require_contains "$preview_reconciler_path" 'client_payload[merge_sha]=${merge_sha}'
require_contains "$preview_reconciler_path" 'client_payload[image_tag]=${image_tag}'
require_contains "$preview_reconciler_path" 'client_payload[action]=deploy'
require_contains "$preview_reconciler_path" 'dispatch_candidates() {'
require_contains "$preview_reconciler_path" 'candidate_rows="$('
require_contains "$preview_reconciler_path" 'dispatch_candidates <<<"$candidate_rows"'
if python3 - "$preview_reconciler_path" <<'PY'
import re
import sys
from pathlib import Path

workflow = Path(sys.argv[1]).read_text(encoding="utf-8")
if any(re.search(r"\|\s*(?:IFS=[^;]+\s+)?while\b", line) for line in workflow.splitlines()):
    raise SystemExit("preview reconciler must not pipe candidate rows directly into while")
PY
then
  :
else
  echo "Preview reconciler candidate processing must keep dispatch state in the current shell" >&2
  exit 1
fi

if grep -Fq 'desired_image_tag=' "$preview_reconciler_path"; then
  echo "Preview reconciler must not retain an unused desired image tag" >&2
  exit 1
fi
require_contains "$preview_reconciler_path" 'resolve-preview-image-tag.sh'
for annotation in \
  'firemud.dev/last-preview-base-sha' \
  'firemud.dev/last-preview-head-sha' \
  'firemud.dev/last-preview-merge-sha' \
  'firemud.dev/last-preview-image-tag' \
  'firemud.dev/requested-preview-base-sha' \
  'firemud.dev/requested-preview-head-sha' \
  'firemud.dev/requested-preview-merge-sha' \
  'firemud.dev/requested-preview-image-tag'; do
  require_contains "$preview_reconciler_path" "$annotation"
done
require_contains "$preview_reconciler_path" 'mergeable_state'
require_contains "$ROOT_DIR/dev-tools/hosted/preview/revalidate-preview-source-binding.sh" '.mergeable == true'
if grep -Fq 'actions/workflows/preview.yml/dispatches' "$preview_reconciler_path" ||
  grep -Fq 'inputs[ref]=' "$preview_reconciler_path"; then
  echo "Preview reconciler must use typed repository_dispatch from the default branch" >&2
  exit 1
fi
for duplicate in \
  'const isBotAuthored' \
  'const isWorkflowComment' \
  'const isLegacyWorkflowComment' \
  'const commentTimestamp' \
  'const deleteCommentIfPresent'; do
  if grep -Fq "$duplicate" "$preview_path"; then
    echo "Preview workflow must keep shared comment logic in the canonical publisher: $duplicate" >&2
    exit 1
  fi
done
for helper in \
  'function commentTimestamp' \
  'const isBotAuthored' \
  'const isWorkflowComment' \
  'const isLegacyWorkflowComment' \
  'const existing = previewComments.reduce' \
  'async function deleteCommentIfPresent' \
  'markerPolicy === "preserve-reclaimed"' \
  'statePolicy = "manual-any"' \
  'module.exports = { publishPreviewComment };'; do
  require_contains "$preview_comment_publisher_path" "$helper"
done
if grep -Fq 'Clear previous preview summary comments' "$preview_path"; then
  echo "Preview workflow must update the canonical summary instead of clearing it" >&2
  exit 1
fi
require_contains "$trusted_preview_path" 'mode: "deploying"'
require_contains "$trusted_preview_path" 'mode: "success"'
require_contains "$trusted_preview_path" 'mode: "removed"'
for mode in deploying target unavailable success cleanup removed reclaimed failure; do
  require_contains "$ROOT_DIR/dev-tools/hosted/preview/write-preview-summary.sh" "  $mode)"
done
require_contains "$ROOT_DIR/dev-tools/hosted/preview/write-preview-summary.sh" '## ✅ Preview Removed'
require_contains "$ROOT_DIR/dev-tools/hosted/preview/write-preview-summary.sh" '- Web: pending'
require_contains "$ROOT_DIR/dev-tools/hosted/preview/write-preview-summary.sh" '- TCP: pending'
for job in image-meta pr-local-smoke; do
  assert_job_condition runtime-images.yml "$job" "$required_condition"
done
assert_job_contains runtime-images.yml pr-local-smoke 'timeout-minutes: 25'

for job in build-base-image build-runtime-images smoke-full; do
  assert_job_contains runtime-images.yml "$job" "github.event_name != 'pull_request'"
done

assert_job_contains runtime-images.yml pr-local-smoke 'SMOKE_IMAGE_LOCAL_ONLY:'
assert_job_contains runtime-images.yml pr-local-smoke 'build-local-smoke-images.sh'
assert_job_contains runtime-images.yml pr-local-smoke 'actions/upload-artifact@'
for forbidden in 'packages: write' 'docker/login-action@' 'push: true' 'type=registry,ref='; do
  assert_job_excludes runtime-images.yml pr-local-smoke "$forbidden"
done

require_contains "$pr_image_publisher_path" 'workflow_run:'
require_exact_line "$pr_image_publisher_path" 'permissions: {}'
# shellcheck disable=SC2016 # This assertion intentionally matches a literal GitHub expression.
require_contains "$pr_image_publisher_path" 'run-name: Publish PR Runtime Images ${{ github.event.workflow_run.display_title }}'
require_contains "$pr_image_publisher_path" "github.event.workflow_run.event == 'pull_request'"
require_contains "$pr_image_publisher_path" "github.event.workflow_run.conclusion == 'success'"
require_contains "$pr_image_publisher_path" 'github.event.workflow_run.head_repository.full_name == github.repository'
require_contains "$pr_image_publisher_path" 'TITLE_PATTERN = re.compile('
require_contains "$pr_image_publisher_path" 'mode-required$'
require_contains "$pr_image_publisher_path" 'actions: read'
require_contains "$pr_image_publisher_path" 'packages: write'
require_contains "$pr_image_publisher_path" '### Trusted PR runtime image publication'
require_contains "$pr_image_publisher_path" 'GitHub displays this run in the default-branch context'
require_contains "$pr_image_publisher_path" 'markdown_code(os.environ['
require_contains "$pr_image_publisher_path" 'from html import escape'
require_contains "$pr_image_publisher_path" '<code>{markdown_code(os.environ['
assert_job_contains publish-pr-runtime-images.yml publish 'contents: read'
# shellcheck disable=SC2016 # These are literal GitHub expression and shell source contracts.
require_contains "$pr_image_publisher_path" 'pr-runtime-images-{image_tag}'
# shellcheck disable=SC2016 # This assertion intentionally matches the unevaluated publisher script.
require_contains "$pr_image_publisher_path" 'docker push "$image"'
# shellcheck disable=SC2016 # These assertions intentionally match unevaluated publisher shell.
require_contains "$pr_image_publisher_path" 'docker manifest inspect "$image"'
require_contains "$pr_image_publisher_path" 'Fixed image tag already exists with the validated source image ID; preserving first publication'
require_contains "$pr_image_publisher_path" 'max_push_attempts=3'
# shellcheck disable=SC2016 # These assertions intentionally match unevaluated publisher shell.
require_contains "$pr_image_publisher_path" 'backoff_seconds=$((5 * 2 ** (push_attempt - 1)))'
# shellcheck disable=SC2016 # This assertion intentionally matches unevaluated publisher shell.
require_contains "$pr_image_publisher_path" 'sleep "$backoff_seconds"'
assert_publish_checkout_configuration "$pr_image_publisher_path"

python3 - "$pr_image_publisher_path" <<'PY'
import os
import re
import sys
import tempfile
import textwrap
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("trusted PR image publisher contract requires one workflow path")
workflow_text = Path(sys.argv[1]).read_text(encoding="utf-8")
matches = list(re.finditer(r"python3 - <<'PY'\n(?P<script>.*?)\n          PY", workflow_text, re.DOTALL))
if len(matches) != 2:
    raise SystemExit("trusted PR image publisher summary script was not found")

script = textwrap.dedent(matches[1].group("script"))
with tempfile.NamedTemporaryFile(mode="r+", encoding="utf-8") as summary_file:
    test_environment = {
        "GITHUB_STEP_SUMMARY": summary_file.name,
        "SOURCE_RUN_ID": "123",
        "SOURCE_RUN_URL": "https://github.example/runs/123",
        "SOURCE_RUN_TITLE": "build `title` <script>&\nnext",
        "SOURCE_HEAD_BRANCH": "feature/`branch` <b>&",
        "SOURCE_HEAD_SHA": "abc123",
        "HEAD_SHA": "abc123",
        "MERGE_SHA": "def456",
        "IMAGE_TAG": "pr-merge-def456",
    }
    previous_environment = os.environ.copy()
    try:
        os.environ.update(test_environment)
        exec(compile(script, "publish-pr-runtime-images-summary", "exec"), {})
    finally:
        os.environ.clear()
        os.environ.update(previous_environment)
    summary_file.seek(0)
    summary = summary_file.read()

if "<code>build `title` &lt;script&gt;&amp; next</code>" not in summary:
    raise SystemExit("trusted PR image publisher did not escape the run title")
if "<code>feature/`branch` &lt;b&gt;&amp;</code>" not in summary:
    raise SystemExit("trusted PR image publisher did not escape the head branch")
if "<script>" in summary:
    raise SystemExit("trusted PR image publisher emitted unescaped HTML")
PY

require_contains "$image_wait_path" 'publish-pr-runtime-images.yml/runs?event=workflow_run'
require_contains "$image_wait_path" '"Publish", "PR", "Runtime", "Images", "Build", "Runtime", "Images"'
require_contains "$image_wait_path" 're.fullmatch(r"pr-[1-9][0-9]{0,50}", tokens[8])'
require_contains "$image_wait_path" 'tokens[9] == f"base-{base_sha}"'
require_contains "$image_wait_path" 'tokens[10] == f"head-{head_sha}"'
require_contains "$image_wait_path" 'tokens[11] == f"merge-{merge_sha}"'
require_contains "$image_wait_path" 'tokens[12] == "mode-required"'
require_contains "$image_wait_path" 'wait_for_pr_publisher'
# shellcheck disable=SC2016 # This assertion intentionally matches a literal shell default expression.
require_contains "$image_wait_path" 'publisher_timeout_seconds="${HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS:-${timeout_seconds}}"'
# shellcheck disable=SC2016 # This assertion intentionally matches the unevaluated publisher deadline.
require_contains "$image_wait_path" 'local publisher_deadline=$((SECONDS + publisher_timeout_seconds))'

contract_fixture_dir="$(mktemp -d)"
trap 'rm -rf "$contract_fixture_dir"' EXIT
cat >"$contract_fixture_dir/publisher-checkout-other-job.yml" <<'EOF'
jobs:
  publish:
    steps:
      - uses: actions/checkout@fixture
        with:
          ref: ${{ github.event.repository.default_branch }}
          persist-credentials: false
  other:
    steps:
      - uses: actions/checkout@fixture
EOF
if (assert_publish_checkout_configuration \
  "$contract_fixture_dir/publisher-checkout-other-job.yml" \
  ) 2>"$contract_fixture_dir/publisher-checkout-other-job.error"; then
  echo "assert_publish_checkout_configuration must reject checkout steps in another job" >&2
  exit 1
fi
require_contains "$contract_fixture_dir/publisher-checkout-other-job.error" \
  'workflow must contain exactly one actions/checkout step'
cat >"$contract_fixture_dir/publisher-checkout-only-other-job.yml" <<'EOF'
jobs:
  other:
    steps:
      - uses: actions/checkout@fixture
EOF
if (assert_publish_checkout_configuration \
  "$contract_fixture_dir/publisher-checkout-only-other-job.yml" \
  ) 2>"$contract_fixture_dir/publisher-checkout-only-other-job.error"; then
  echo "assert_publish_checkout_configuration must require the checkout in the publish job" >&2
  exit 1
fi
require_contains "$contract_fixture_dir/publisher-checkout-only-other-job.error" \
  'the workflow checkout must belong to the publish job'
cat >"$contract_fixture_dir/publisher-checkout-wrong-ref.yml" <<'EOF'
jobs:
  publish:
    steps:
      - uses: actions/checkout@fixture
        with:
          ref: refs/heads/main
          persist-credentials: false
EOF
if (assert_publish_checkout_configuration \
  "$contract_fixture_dir/publisher-checkout-wrong-ref.yml" \
  ) 2>"$contract_fixture_dir/publisher-checkout-wrong-ref.error"; then
  echo "assert_publish_checkout_configuration must reject a non-default checkout ref" >&2
  exit 1
fi
require_contains "$contract_fixture_dir/publisher-checkout-wrong-ref.error" \
  'publish checkout must use the repository default branch'
cat >"$contract_fixture_dir/publisher-checkout-string-persist-credentials.yml" <<'EOF'
jobs:
  publish:
    steps:
      - uses: actions/checkout@fixture
        with:
          ref: ${{ github.event.repository.default_branch }}
          persist-credentials: 'false'
EOF
if (assert_publish_checkout_configuration \
  "$contract_fixture_dir/publisher-checkout-string-persist-credentials.yml" \
  ) 2>"$contract_fixture_dir/publisher-checkout-string-persist-credentials.error"; then
  echo "assert_publish_checkout_configuration must reject string false persisted credentials" >&2
  exit 1
fi
require_contains "$contract_fixture_dir/publisher-checkout-string-persist-credentials.error" \
  'publish checkout must disable persisted credentials with boolean false'
for unexpected_key in repository submodules fetch-depth; do
  cat >"$contract_fixture_dir/publisher-checkout-$unexpected_key.yml" <<EOF
jobs:
  publish:
    steps:
      - uses: actions/checkout@fixture
        with:
          ref: \${{ github.event.repository.default_branch }}
          persist-credentials: false
          $unexpected_key: fixture
EOF
  if (assert_publish_checkout_configuration \
    "$contract_fixture_dir/publisher-checkout-$unexpected_key.yml" \
    ) 2>"$contract_fixture_dir/publisher-checkout-$unexpected_key.error"; then
    echo "assert_publish_checkout_configuration must reject $unexpected_key" >&2
    exit 1
  fi
  require_contains "$contract_fixture_dir/publisher-checkout-$unexpected_key.error" \
    'publish checkout must define exactly ref and persist-credentials'
done
cat >"$contract_fixture_dir/ordered-sequence.txt" <<'EOF'
prefix first second suffix
third
EOF
require_ordered_sequence "$contract_fixture_dir/ordered-sequence.txt" first second third
if (require_ordered_sequence "$contract_fixture_dir/ordered-sequence.txt" second first) 2>/dev/null; then
  echo "require_ordered_sequence must preserve within-line ordering" >&2
  exit 1
fi
cat >"$contract_fixture_dir/branch-return.js" <<'EOF'
if (
  examplePredicate ||
  otherPredicate
) {
  const quoted = "{";
  const message = `template text } {
    ${JSON.stringify({ nested: "}" })}
  `;
  return;
}
EOF
require_branch_return "$contract_fixture_dir/branch-return.js" 'examplePredicate ||'
cat >"$contract_fixture_dir/branch-non-return.js" <<'EOF'
if (examplePredicate) {
  notreturn;
}
EOF
if (require_branch_return "$contract_fixture_dir/branch-non-return.js" examplePredicate) 2>/dev/null; then
  echo "require_branch_return must match return as a complete token" >&2
  exit 1
fi
cat >"$contract_fixture_dir/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${CONTRACT_GH_STATE_FILE:-}" ]]; then
  invocation=0
  [[ ! -f "$CONTRACT_GH_STATE_FILE" ]] || invocation="$(cat "$CONTRACT_GH_STATE_FILE")"
  printf '%s\n' "$((invocation + 1))" >"$CONTRACT_GH_STATE_FILE"
  if (( invocation == 0 )); then
    printf '{invalid-json'
    exit 0
  fi
fi

if [[ "$*" == *"publish-pr-runtime-images.yml"* ]]; then
  cat <<'JSON'
[{"workflow_runs":[{"id":201,"status":"completed","conclusion":"success","html_url":"https://example.test/publisher/201","display_title":"Publish PR Runtime Images Build Runtime Images secure-pr-artifact pr-1 base-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb head-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa merge-cccccccccccccccccccccccccccccccccccccccc mode-required","created_at":"2026-07-24T00:03:00Z"}]}]
JSON
else
  cat <<'JSON'
[{"workflow_runs":[{"id":102,"status":"completed","conclusion":"skipped","html_url":"https://example.test/runtime/102","event":"pull_request","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","display_title":"Build Runtime Images secure-pr-artifact pr-1 base-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb head-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa merge-cccccccccccccccccccccccccccccccccccccccc mode-metadata","created_at":"2026-07-24T00:02:00Z"}]},{"workflow_runs":[{"id":101,"status":"completed","conclusion":"success","html_url":"https://example.test/runtime/101","event":"pull_request","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","display_title":"Build Runtime Images secure-pr-artifact pr-1 base-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb head-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa merge-cccccccccccccccccccccccccccccccccccccccc mode-required","created_at":"2026-07-24T00:01:00Z"}]}]
JSON
fi
EOF
chmod +x "$contract_fixture_dir/gh"
PATH="$contract_fixture_dir:$PATH" \
GH_TOKEN=contract-token \
GITHUB_REPOSITORY=example/FireMUD \
HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=5 \
HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
bash "$image_wait_path" "cccccccccccccccccccccccccccccccccccccccc" \
  "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" \
  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
  >"$contract_fixture_dir/output"
require_contains "$contract_fixture_dir/output" 'Matching runtime-images workflow 101 succeeded'
require_contains "$contract_fixture_dir/output" 'Trusted PR image publisher 201 succeeded'

CONTRACT_GH_STATE_FILE="$contract_fixture_dir/gh-state" \
PATH="$contract_fixture_dir:$PATH" \
GH_TOKEN=contract-token \
GITHUB_REPOSITORY=example/FireMUD \
HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=5 \
HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
bash "$image_wait_path" "cccccccccccccccccccccccccccccccccccccccc" \
  "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" \
  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
  >"$contract_fixture_dir/retry-output" \
  2>"$contract_fixture_dir/retry-error"
require_contains "$contract_fixture_dir/retry-error" \
  'GitHub API response was empty or invalid while waiting for the runtime-images workflow; retrying.'
require_contains "$contract_fixture_dir/retry-output" 'Matching runtime-images workflow 101 succeeded'
require_contains "$contract_fixture_dir/retry-output" 'Trusted PR image publisher 201 succeeded'

assert_job_excludes runtime-images.yml smoke-full 'pull-requests: write'
if (assert_job_excludes runtime-images.yml missing-job 'pull-requests: write') 2>/dev/null; then
  echo "assert_job_excludes must fail when the requested job is absent" >&2
  exit 1
fi

require_contains "$smoke_path" 'const eventBaseSha = context.payload.pull_request.base.sha;'
require_contains "$smoke_path" 'const baseRef = context.payload.pull_request.base.ref;'
require_contains "$smoke_path" 'const eventMergeSha = context.sha;'
require_contains "$smoke_path" 'github.rest.pulls.get({'
require_contains "$smoke_path" 'pullRequest.state !== "open" ||'
require_contains "$smoke_path" 'pullRequest.head.sha !== headSha ||'
require_contains "$smoke_path" 'pullRequest.base.ref !== baseRef'
require_contains "$smoke_path" 'let matching = null;'
require_contains "$smoke_path" 'let fullSmokeJob = null;'
require_contains "$smoke_path" 'async function failNonTerminalSnapshot(reason) {'
# shellcheck disable=SC2016 # Assert literal JavaScript template syntax.
require_contains "$smoke_path" '`${reason}: ` +'
require_contains "$smoke_path" 'const maxCompletedJobSnapshotRetries = 8;'
require_contains "$smoke_path" 'const remainingBeforeJobLookupMs = timeoutMs - (Date.now() - started);'
require_contains "$smoke_path" 'if (remainingBeforeJobLookupMs <= 0) {'
require_contains "$smoke_path" 'completedJobSnapshotAttempt <= maxCompletedJobSnapshotRetries'
require_contains "$smoke_path" 'run_id: matching.id'
require_contains "$smoke_path" 'completedJobSnapshotAttempt < maxCompletedJobSnapshotRetries'
require_contains "$smoke_path" 'const remainingBeforeSnapshotSleepMs = timeoutMs - (Date.now() - started);'
require_contains "$smoke_path" 'if (remainingBeforeSnapshotSleepMs <= 0) {'
require_contains "$smoke_path" 'Math.min(sleepMs, remainingBeforeSnapshotSleepMs)'
require_contains "$smoke_path" 'did not expose a terminal PR Full-Stack Smoke job and smoke step '
require_contains "$smoke_path" "Runtime images run \${matching.id} succeeded, but PR Full-Stack Smoke job did not complete successfully:"
require_contains "$smoke_path" 'Stopping obsolete failed full-smoke gate for'
require_contains "$smoke_path" 'pollIteration % pullRequestCheckInterval === 0'
require_contains "$smoke_path" 'Stopping obsolete smoke gate for'
require_ordered_sequence \
  "$smoke_path" \
  'if (resolvedIdentity?.obsolete) {' \
  'return;' \
  'github.rest.actions.listWorkflowRuns,'
require_branch_return \
  "$smoke_path" \
  'if (resolvedIdentity?.obsolete) {'
require_contains "$smoke_path" 'mode-required'
require_contains "$smoke_path" 'Build Runtime Images secure-pr-artifact pr-'
require_contains "$smoke_path" 'workflowRunQuery.created = `${pullRequestCreatedAt}..*`;'
require_contains "$smoke_path" 'github.paginate.iterator('
require_contains "$smoke_path" 'for await (const response of github.paginate.iterator('
require_contains "$smoke_path" 'run.display_title !== expectedDisplayTitle'
require_contains "$smoke_path" 'const pullRequests = run.pull_requests ?? [];'
require_contains "$smoke_path" 'pullRequests.length > 0 &&'
require_contains "$smoke_path" '!pullRequests.some'
require_contains "$smoke_path" 'pullRequest.head?.sha === currentIdentity.headSha'
require_contains "$smoke_path" 'break workflowRunPages;'
require_contains "$smoke_path" 'workflowRunQuery.head_sha = currentIdentity.headSha;'
require_contains "$smoke_path" 'github.rest.actions.listJobsForWorkflowRun'
require_contains "$smoke_path" 'job.name === "PR Full-Stack Smoke"'
require_contains "$smoke_path" 'step.name === "Run credential-free full-stack smoke"'
require_contains "$smoke_path" 'fullSmokeJob?.status === "completed"'
require_contains "$smoke_path" 'fullSmokeJob?.conclusion !== "success"'
require_contains "$smoke_path" 'fullSmokeStep?.status === "completed"'
require_contains "$smoke_path" 'fullSmokeStep?.conclusion !== "success"'
require_contains "$smoke_path" 'credential-free full-stack smoke step did not pass'
require_ordered_sequence \
  "$smoke_path" \
  'if (fullSmokeJob?.status === "completed") {' \
  'if (fullSmokeJob?.conclusion !== "success") {' \
  'PR Full-Stack Smoke job did not complete successfully:'
# shellcheck disable=SC2016 # Assert literal GitHub expression syntax.
require_contains "$smoke_path" 'SMOKE_GATE_REQUIRED: ${{ github.event.action != '\''edited'\'' || github.event.changes.base.ref != null }}'
# shellcheck disable=SC2016 # Assert literal GitHub expression syntax.
require_contains "$smoke_path" 'CHANGES_RESULT: ${{ needs.changes.result }}'
# shellcheck disable=SC2016 # Assert literal shell source.
require_contains "$smoke_path" 'echo "required=$SMOKE_GATE_REQUIRED" >> "$GITHUB_OUTPUT"'
# shellcheck disable=SC2016 # Assert literal shell source.
require_contains "$smoke_path" 'if [ "$SMOKE_GATE_REQUIRED" = "true" ] && [ "$CHANGES_RESULT" = "success" ]; then'
# shellcheck disable=SC2016 # Assert literal shell source.
require_contains "$smoke_path" 'echo "execute=true" >> "$GITHUB_OUTPUT"'
# shellcheck disable=SC2016 # Assert literal shell source.
require_contains "$smoke_path" 'echo "execute=false" >> "$GITHUB_OUTPUT"'
# shellcheck disable=SC2016 # Assert literal GitHub expression syntax.
require_contains "$smoke_path" 'if: ${{ steps.smoke_gate_context.outputs.required == '\''true'\'' && steps.smoke_gate_context.outputs.execute != '\''true'\'' }}'
# shellcheck disable=SC2016 # Assert literal GitHub expression syntax.
if grep -Fq 'echo "required=${{' "$smoke_path" || grep -Fq 'echo "execute=${{' "$smoke_path"; then
  echo "smoke_gate_context must pass expressions through step env" >&2
  exit 1
fi
require_contains "$smoke_path" 'Stopping obsolete completed smoke gate for'
require_contains "$smoke_path" 'Stopping obsolete stale-snapshot smoke gate for'
require_ordered_sequence \
  "$smoke_path" \
  'github.rest.actions.listJobsForWorkflowRun,' \
  'if (await isCurrentPullRequestObsolete("Stopping obsolete completed smoke gate for")) {'
require_branch_return "$smoke_path" 'if (await isCurrentPullRequestObsolete("Stopping obsolete completed smoke gate for")) {'
require_ordered_sequence \
  "$smoke_path" \
  'async function failNonTerminalSnapshot(reason) {' \
  'if (await isCurrentPullRequestObsolete("Stopping obsolete stale-snapshot smoke gate for")) {' \
  'core.setFailed('
require_branch_return "$smoke_path" 'if (await isCurrentPullRequestObsolete("Stopping obsolete stale-snapshot smoke gate for")) {'
require_ordered_sequence \
  "$smoke_path" \
  'if (remainingBeforeJobLookupMs <= 0) {' \
  'await failNonTerminalSnapshot("before the workflow deadline");'
require_ordered_sequence \
  "$smoke_path" \
  'if (remainingBeforeSnapshotSleepMs <= 0) {' \
  'await failNonTerminalSnapshot("before the workflow deadline");'
require_ordered_sequence \
  "$smoke_path" \
  'const snapshotRetrySummary = maxCompletedJobSnapshotRetries === 0' \
  'await failNonTerminalSnapshot(snapshotRetrySummary);'
if grep -Fq 'const matching = runs.find((run) => run.head_sha === headSha);' "$smoke_path"; then
  echo "Smoke Gate must not accept a runtime-images run by head SHA alone" >&2
  exit 1
fi

python3 - "$smoke_path" "$runtime_images_path" <<'PY'
import re
import sys
from pathlib import Path

import yaml

if len(sys.argv) != 3:
    raise SystemExit("smoke/runtime-images parity contract requires two workflow paths")
smoke_path, runtime_images_path = map(Path, sys.argv[1:])
smoke = smoke_path.read_text(encoding="utf-8")
runtime_images = runtime_images_path.read_text(encoding="utf-8")


def quoted_entries(source, start_marker, end_marker):
    start = source.index(start_marker) + len(start_marker)
    end = source.index(end_marker, start)
    return re.findall(r'["\']([^"\']+)["\']', source[start:end])


def pull_request_paths(source, label):
    try:
        workflow = yaml.load(source, Loader=yaml.BaseLoader)
    except yaml.YAMLError as exc:
        raise AssertionError(f"{label} is not valid YAML: {exc}") from exc
    if not isinstance(workflow, dict):
        raise AssertionError(f"{label} must contain a workflow mapping")
    triggers = workflow.get("on")
    if not isinstance(triggers, dict) or "pull_request" not in triggers:
        raise AssertionError(f"{label} must define on.pull_request")
    pull_request = triggers["pull_request"]
    if not isinstance(pull_request, dict):
        raise AssertionError(f"{label} on.pull_request must be a mapping")
    paths = pull_request.get("paths")
    if not isinstance(paths, list) or not paths or not all(isinstance(path, str) for path in paths):
        raise AssertionError(f"{label} on.pull_request.paths must be a non-empty string list")
    return set(paths)


full_prefixes = quoted_entries(
    smoke,
    "const fullRelevantPrefixes = [",
    "];",
)
full_files = quoted_entries(
    smoke,
    "const fullRelevantFiles = new Set([",
    "]);",
)

lookup_start_marker = "fullSmokeJob = jobs.find("
lookup_end_marker = ") ?? null;"
try:
    lookup_start = smoke.index(lookup_start_marker)
    lookup_end = smoke.index(lookup_end_marker, lookup_start) + len(lookup_end_marker)
except ValueError as exc:
    raise SystemExit("smoke.yml must define the fullSmokeJob lookup block") from exc

job_name_literals = re.findall(
    r'job[.]name\s*===\s*["\']([^"\']+)["\']',
    smoke[lookup_start:lookup_end],
)
if job_name_literals != ["PR Full-Stack Smoke"]:
    raise SystemExit(
        "smoke.yml fullSmokeJob lookup must use exactly one PR Full-Stack Smoke literal: "
        + repr(job_name_literals)
    )

try:
    runtime_workflow = yaml.load(runtime_images, Loader=yaml.BaseLoader)
    runtime_job_name = runtime_workflow["jobs"]["pr-local-smoke"]["name"]
except (KeyError, TypeError, yaml.YAMLError) as exc:
    raise SystemExit(
        "runtime-images.yml must define the pr-local-smoke job name used by Smoke Gate"
    ) from exc
if runtime_job_name != "PR Full-Stack Smoke":
    raise SystemExit(
        "smoke.yml full-stack PR job lookup does not match runtime-images.yml: "
        f"smoke={'PR Full-Stack Smoke'!r}, runtime={runtime_job_name!r}"
    )

runtime_paths = pull_request_paths(runtime_images, "runtime-images.yml")

runtime_images_fixture = """on:
  pull_request:
    paths:
      - 'services/example/**'
      - 'literal  push: value'
  push:
    branches: [main]
"""
expected_runtime_fixture_paths = {
    "services/example/**",
    "literal  push: value",
}
if pull_request_paths(runtime_images_fixture, "runtime fixture") != expected_runtime_fixture_paths:
    raise SystemExit("runtime fixture paths were parsed incorrectly")

for invalid_source, expected_message in (
    ("on:\n  push:\n    branches: [main]\n", "must define on.pull_request"),
    ("on:\n  pull_request:\n    paths:\n      invalid: value\n", "must be a non-empty string list"),
):
    try:
        pull_request_paths(invalid_source, "invalid runtime fixture")
    except AssertionError as exc:
        if expected_message not in str(exc):
            raise SystemExit(
                f"invalid runtime fixture raised the wrong message: {exc}"
            ) from exc
    else:
        raise SystemExit(f"invalid runtime fixture was accepted: {expected_message}")


def path_pattern_covers_prefix(pattern, prefix):
    if pattern.startswith("!"):
        return False
    if pattern in {prefix, f"{prefix}**"}:
        return True
    return pattern.endswith("**") and prefix.startswith(pattern.removesuffix("**"))


if not path_pattern_covers_prefix("services/**", "services/common-library/"):
    raise SystemExit("services/** must cover services/common-library/")
if path_pattern_covers_prefix("web-client/**", "services/common-library/"):
    raise SystemExit("web-client/** must not cover services/common-library/")

missing_prefixes = [
    prefix
    for prefix in full_prefixes
    if not any(path_pattern_covers_prefix(pattern, prefix) for pattern in runtime_paths)
]
missing_files = [
    file
    for file in full_files
    if not any(
        pattern == file
        or (
            not pattern.startswith("!")
            and pattern.endswith("/**")
            and file.startswith(pattern.removesuffix("**"))
        )
        for pattern in runtime_paths
    )
]

if missing_prefixes or missing_files:
    details = []
    if missing_prefixes:
        details.append(f"prefixes={missing_prefixes}")
    if missing_files:
        details.append(f"files={missing_files}")
    raise SystemExit(
        "runtime-images.yml must trigger for every smoke.yml full-smoke declaration: "
        + ", ".join(details)
    )

print("smoke/runtime-images full-scope parity checks passed")
PY

python3 - "$smoke_path" <<'PY'
import json
import subprocess
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
scope_script = next(
    step["with"]["script"]
    for step in workflow["jobs"]["changes"]["steps"]
    if step.get("name") == "Compute smoke scope"
)


def run_scope(changed_paths):
    node_source = """
const scopeScript = %s;
const changedFiles = %s;
const outputs = {};
const github = {
  paginate: async () => changedFiles,
  rest: { pulls: { listFiles: async () => undefined } },
};
const context = {
  repo: { owner: "example", repo: "firemud" },
  payload: { pull_request: { number: 1, changed_files: changedFiles.length } },
};
const core = {
  warning: () => {},
  setOutput: (name, value) => { outputs[name] = value; },
};
const execute = new Function(
  "github",
  "context",
  "core",
  "return (async () => {\\n" + scopeScript + "\\n})()"
);
await execute(github, context, core);
process.stdout.write(JSON.stringify(outputs));
""" % (
        json.dumps(scope_script),
        json.dumps([{"filename": path} for path in changed_paths]),
    )
    result = subprocess.run(
        ["node", "--input-type=module", "-e", node_source],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if result.returncode != 0:
        raise SystemExit(result.stderr)
    return json.loads(result.stdout)


controller_service = "services/hosted-environment-identity-controller/src/Main.java"
controller_smoke_script = "dev-tools/hosted/controller/smoke-paused-controller-image.sh"
runtime_service = "services/account-service/src/Main.java"

assert run_scope([controller_service]) == {"run_smoke_full": "false"}
assert run_scope([controller_smoke_script]) == {"run_smoke_full": "false"}
assert run_scope([runtime_service]) == {"run_smoke_full": "true"}
assert run_scope([controller_service, runtime_service]) == {"run_smoke_full": "true"}
PY

smoke_gate_script="$contract_fixture_dir/smoke-gate.js"
python3 - "$smoke_path" "$smoke_gate_script" <<'PY'
import sys
import textwrap
from pathlib import Path

workflow_path, script_path = map(Path, sys.argv[1:])
lines = workflow_path.read_text(encoding="utf-8").splitlines()
anchor = next(
    index for index, line in enumerate(lines)
    if line.strip() == "- name: Track full-stack smoke result from Build Runtime Images"
)
start = next(index for index in range(anchor, len(lines)) if lines[index].strip() == "script: |")
base_indent = len(lines[start]) - len(lines[start].lstrip())
body = []
for line in lines[start + 1 :]:
    if line.strip() and len(line) - len(line.lstrip()) <= base_indent:
        break
    body.append(line)
script_path.write_text(textwrap.dedent("\n".join(body)) + "\n", encoding="utf-8")
PY

node - "$smoke_gate_script" <<'NODE'
const fs = require("node:fs");
const script = fs.readFileSync(process.argv[2], "utf8");
const headSha = "a".repeat(40);
const baseSha = "b".repeat(40);
const mergeSha = "c".repeat(40);
let currentBaseSha = baseSha;
let currentMergeSha = mergeSha;
let currentMergeParents = [{ sha: baseSha }, { sha: headSha }];
const context = {
  repo: { owner: "owner", repo: "repo" },
  sha: mergeSha,
  payload: {
    pull_request: {
      number: 42,
      created_at: "2026-09-01T00:00:00Z",
      head: { sha: headSha },
      base: { sha: baseSha, ref: "develop" },
    },
  },
};
const listWorkflowRuns = async () => undefined;
const listJobsForWorkflowRun = async () => undefined;
let smokeStepConclusion = "skipped";
const workflowRunQueries = [];
const workflowRunPageReads = [];
let olderMatchingRunVisited = false;
const jobQueries = [];
const github = {
  rest: {
    pulls: {
      get: async () => ({
        data: {
          state: "open",
          head: { sha: headSha },
          base: { sha: currentBaseSha, ref: "develop" },
          merge_commit_sha: currentMergeSha,
        },
      }),
    },
    git: {
      getRef: async () => ({ data: { object: { sha: currentBaseSha } } }),
    },
    repos: {
      getCommit: async () => ({
        data: {
          sha: currentMergeSha,
          parents: currentMergeParents,
        },
      }),
    },
    actions: { listWorkflowRuns, listJobsForWorkflowRun },
  },
  paginate: async (method, input) => {
    if (method === listJobsForWorkflowRun) {
      jobQueries.push(input);
      return [{
        name: "PR Full-Stack Smoke",
        status: "completed",
        conclusion: "success",
        steps: [{
          name: "Run credential-free full-stack smoke",
          status: "completed",
          conclusion: smokeStepConclusion,
        }],
      }];
    }
    throw new Error("unexpected paginated method");
  },
};
github.paginate.iterator = (method, input) => {
  if (method !== listWorkflowRuns) {
    throw new Error("unexpected workflow-run iterator method");
  }
  workflowRunQueries.push(input);
  const expectedTitle = `Build Runtime Images secure-pr-artifact pr-42 base-${currentBaseSha} head-${headSha} merge-${currentMergeSha} mode-required`;
  const pages = currentBaseSha === baseSha
    ? [[{
        id: 101,
        event: "pull_request",
        head_sha: headSha,
        display_title: expectedTitle,
        status: "completed",
        conclusion: "success",
        created_at: "2026-09-20T00:00:00Z",
        pull_requests: [],
      }]]
    : [
        [
          {
            id: 102,
            event: "pull_request",
            head_sha: headSha,
            display_title: `Build Runtime Images secure-pr-artifact pr-42 base-${baseSha} head-${headSha} merge-${mergeSha} mode-required`,
            status: "completed",
            conclusion: "success",
            created_at: "2026-09-20T00:00:00Z",
            pull_requests: [],
          },
          {
            id: 202,
            event: "repository_dispatch",
            head_sha: headSha,
            display_title: expectedTitle,
            status: "completed",
            conclusion: "success",
            created_at: "2026-09-21T00:00:00Z",
            pull_requests: [],
          },
          {
            id: 201,
            get event() {
              olderMatchingRunVisited = true;
              return "repository_dispatch";
            },
            head_sha: headSha,
            display_title: expectedTitle,
            status: "completed",
            conclusion: "success",
            created_at: "2026-09-20T00:00:00Z",
            pull_requests: [],
          },
        ],
        [{
          id: 203,
          event: "repository_dispatch",
          head_sha: headSha,
          display_title: expectedTitle,
          status: "completed",
          conclusion: "success",
          created_at: "2026-09-19T00:00:00Z",
          pull_requests: [],
        }],
      ];
  return (async function* workflowRunPages() {
    for (const runs of pages) {
      workflowRunPageReads.push(runs);
      yield { data: runs };
    }
  })();
};
const run = new Function("github", "context", "core", `return (async () => {\n${script}\n})()`);
async function check(conclusion, expectedFailure) {
  smokeStepConclusion = conclusion;
  const failures = [];
  const core = {
    info: () => {},
    warning: () => {},
    setFailed: (message) => failures.push(message),
  };
  await run(github, context, core);
  if (expectedFailure) {
    if (failures.length !== 1 || !failures[0].includes("credential-free full-stack smoke step did not pass")) {
      throw new Error(`skipped smoke step was accepted: ${JSON.stringify(failures)}`);
    }
  } else if (failures.length !== 0) {
    throw new Error(`successful smoke step was rejected: ${JSON.stringify(failures)}`);
  }
}
check("skipped", true).then(() => check("success", false)).then(() => {
  currentBaseSha = "d".repeat(40);
  currentMergeSha = "e".repeat(40);
  currentMergeParents = [{ sha: currentBaseSha }, { sha: headSha }];
  workflowRunQueries.length = 0;
  workflowRunPageReads.length = 0;
  jobQueries.length = 0;
  return check("success", false).then(() => {
    if (workflowRunQueries.length !== 1 || workflowRunQueries[0].event !== "repository_dispatch") {
      throw new Error("stale PR identity must select a repository_dispatch runtime run");
    }
    if (workflowRunQueries[0].created !== "2026-09-01T00:00:00Z..*") {
      throw new Error("repository_dispatch lookup must start at PR creation time");
    }
    if (workflowRunPageReads.length !== 1 || olderMatchingRunVisited) {
      throw new Error("Smoke Gate must stop after the first exact newest-first event/title match");
    }
    if (jobQueries.length !== 1 || jobQueries[0].run_id !== 202) {
      throw new Error("Smoke Gate must select the exact repository_dispatch event/title tuple");
    }
    console.log("Smoke Gate identity/event/title and step-conclusion contract passed");
  });
}).catch((error) => {
  console.error(error.stack || error);
  process.exit(1);
});
NODE

smoke_summary_scripts="$({
  python3 - "$smoke_path" <<'PY'
import json
import sys
from pathlib import Path

import yaml


workflow = yaml.load(Path(sys.argv[1]).read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
scripts = {}
for job_name in ("smoke-summary-pending", "smoke-summary"):
    scripts[job_name] = next(
        step["with"]["script"]
        for step in workflow["jobs"][job_name]["steps"]
        if step.get("name", "").startswith("💬 Publish")
    )
print(json.dumps(scripts))
PY
})"

SMOKE_SUMMARY_SCRIPTS="$smoke_summary_scripts" node <<'NODE'
const assert = require("node:assert/strict");

const scripts = JSON.parse(process.env.SMOKE_SUMMARY_SCRIPTS);
const context = {
  repo: { owner: "example", repo: "firemud" },
  issue: { number: 42 },
};

async function checkSummary(jobName, script) {
  const firstPageNoise = Array.from({ length: 35 }, (_, index) => ({
    id: index + 1,
    user: { login: "github-actions[bot]" },
    body: `### Unrelated Summary ${index + 1}`,
    created_at: `2026-08-${String((index % 28) + 1).padStart(2, "0")}T00:00:00Z`,
  }));
  const comments = [
    ...firstPageNoise,
    {
      id: 100,
      user: { login: "github-actions[bot]" },
      body: "### Smoke Summary\nold canonical",
      created_at: "2026-09-01T00:00:00Z",
      updated_at: "2026-09-03T00:00:00Z",
    },
    {
      id: 101,
      user: { login: "github-actions[bot]" },
      body: "<!-- firemud-smoke-summary -->\n### Smoke Summary\nlater duplicate",
      created_at: "2026-09-02T00:00:00Z",
      updated_at: "2026-09-01T00:00:00Z",
    },
    {
      id: 102,
      user: { login: "contributor" },
      body: "<!-- firemud-smoke-summary -->\n### Smoke Summary\nspoof",
      created_at: "2026-08-01T00:00:00Z",
    },
  ];
  const calls = { paginate: [], operations: [], creates: [] };
  const listComments = async () => undefined;
  const github = {
    rest: {
      issues: {
        listComments,
        updateComment: async ({ comment_id }) => calls.operations.push(`update:${comment_id}`),
        deleteComment: async ({ comment_id }) => calls.operations.push(`delete:${comment_id}`),
        createComment: async (request) => calls.creates.push(request),
      },
    },
    paginate: async (method, params) => {
      calls.paginate.push({ method, params });
      return comments;
    },
  };

  process.env.CHANGES_RESULT = "success";
  process.env.RUN_SMOKE_FULL = "true";
  process.env.SMOKE_GATE_RESULT = "success";
  const run = new Function("github", "context", `return (async () => {\n${script}\n})()`);
  await run(github, context);

  assert.equal(calls.paginate.length, 1, `${jobName} must paginate once`);
  assert.equal(calls.paginate[0].method, listComments, `${jobName} must paginate issue comments`);
  assert.equal(calls.paginate[0].params.per_page, 100, `${jobName} must request 100 comments per page`);
  assert.deepEqual(
    calls.operations,
    ["update:100", "delete:101"],
    `${jobName} must update the oldest bot summary before deleting later duplicates`,
  );
  assert.deepEqual(calls.creates, [], `${jobName} must not create another summary`);
}

(async () => {
  for (const [jobName, script] of Object.entries(scripts)) {
    await checkSummary(jobName, script);
  }
  console.log("Smoke summary pagination and deduplication contract passed");
})().catch((error) => {
  console.error(error.stack || error);
  process.exit(1);
});
NODE

echo "PR retarget workflow contract checks passed"
