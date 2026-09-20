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

for path in "$ci_path" "$smoke_path"; do
  require_contains "$path" 'types: [opened, synchronize, reopened, edited]'
  require_contains "$path" "&& 'metadata' || 'required' }}"
  require_contains "$path" '  cancel-in-progress: true'
done

require_contains "$ci_path" 'PR Metadata Edit (Validation Summary)'
require_contains "$smoke_path" 'PR Metadata Edit (Smoke Summary)'
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
assert_job_contains smoke.yml smoke-summary 'const smokeGateStatus ='
assert_job_contains smoke.yml smoke-summary 'firemud-smoke-summary'
assert_job_contains smoke.yml smoke-summary 'comment.user?.login === "github-actions[bot]"'
assert_job_contains smoke.yml smoke-gate 'pull-requests: read'
assert_job_contains smoke.yml smoke-gate 'github.rest.pulls.get'
assert_job_contains smoke.yml smoke-gate 'pullRequest.state !== "open"'
assert_job_contains smoke.yml smoke-gate 'pullRequest.head.sha !== headSha'
assert_job_contains smoke.yml smoke-gate 'pullRequest.base.ref !== baseRef'

require_contains "$runtime_images_path" 'types: [opened, synchronize, reopened, edited]'
require_contains "$runtime_images_path" "&& 'metadata' || 'required' }}"
require_contains "$runtime_images_path" '  cancel-in-progress: true'
require_contains "$runtime_images_path" 'run-name: Build Runtime Images '
require_contains "$runtime_images_path" "format('secure-pr-artifact pr-{0}"
require_contains "$runtime_images_path" 'github.event.pull_request.base.sha'
require_contains "$runtime_images_path" 'github.event.pull_request.head.sha'
require_contains "$runtime_images_path" 'github.sha'
require_contains "$runtime_images_path" 'mode-{4}'
require_contains "$runtime_images_path" '### PR runtime images and applicable full-stack smoke'
require_contains "$runtime_images_path" 'built the PR merge commit and runs full-stack smoke when required by change scope'
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
require_contains "$preview_path" "format('Preview dispatch pr-{0}-{1}', github.event.client_payload.pr_number, github.event.client_payload.head_sha)"
require_contains "$preview_path" 'contents: read'
require_contains "$preview_path" 'pull-requests: read'
require_contains "$preview_path" 'pull_request_target:'
require_contains "$preview_path" 'types: [preview-deploy, preview-destroy]'
require_contains "$preview_path" '      - opened'
require_contains "$preview_path" '      - synchronize'
require_contains "$preview_path" '      - reopened'
require_contains "$preview_path" '      - labeled'
require_contains "$preview_path" 'CLIENT_HEAD_SHA: ${{ github.event.client_payload.head_sha }}'
if grep -Fq 'CLIENT_IMAGE_TAG: ${{ github.event.client_payload.image_tag }}' "$preview_path"; then
  echo "Preview source must resolve the effective image tag independently of dispatch payload image_tag" >&2
  exit 1
fi
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
if grep -Fq 'github.event.pull_request.merge_commit_sha' "$preview_path"; then
  echo "Preview source must resolve a fresh REST test merge, not trust the event payload" >&2
  exit 1
fi
require_contains "$preview_path" 'MERGE_RETRY_LIMIT=5'
require_contains "$preview_path" 'Preview merge computation unavailable'
require_contains "$preview_path" 'Stale preview head SHA'
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
require_contains "$trusted_preview_path" '[[ "$base_ref" == main || "$base_ref" == develop ]]'
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
require_contains "$preview_reconciler_path" '--branch "${DEFAULT_BRANCH}"'
require_contains "$preview_reconciler_path" '--json databaseId,status,displayTitle'
require_contains "$preview_reconciler_path" '"Preview dispatch pr-${pr_number}-${head_sha}"'
require_contains "$preview_reconciler_path" '"repos/${GITHUB_REPOSITORY}/dispatches"'
require_contains "$preview_reconciler_path" '-f event_type=preview-deploy'
require_contains "$preview_reconciler_path" 'client_payload[head_sha]=${head_sha}'
require_contains "$preview_reconciler_path" 'client_payload[action]=deploy'
if grep -Fq 'desired_image_tag=' "$preview_reconciler_path"; then
  echo "Preview reconciler must not retain an unused desired image tag" >&2
  exit 1
fi
if grep -Fq 'client_payload[image_tag]' "$preview_reconciler_path"; then
  echo "Preview reconciler must let the trusted consumer resolve the effective image tag" >&2
  exit 1
fi
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

require_contains "$smoke_path" 'const baseSha = context.payload.pull_request.base.sha;'
require_contains "$smoke_path" 'const baseRef = context.payload.pull_request.base.ref;'
require_contains "$smoke_path" 'const mergeSha = context.sha;'
require_contains "$smoke_path" 'github.rest.pulls.get({'
require_contains "$smoke_path" 'pullRequest.state !== "open" ||'
require_contains "$smoke_path" 'pullRequest.head.sha !== headSha ||'
require_contains "$smoke_path" 'pullRequest.base.ref !== baseRef'
require_contains "$smoke_path" 'const matchingRuns = runs.filter((run) => {'
require_contains "$smoke_path" 'const matching = matchingRuns.reduce((newest, candidate) => {'
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
  'if (await isCurrentPullRequestObsolete("Stopping obsolete smoke gate for")) {' \
  'return;' \
  'github.rest.actions.listWorkflowRuns,'
require_branch_return \
  "$smoke_path" \
  'if (await isCurrentPullRequestObsolete("Stopping obsolete smoke gate for")) {'
require_contains "$smoke_path" 'head_sha: headSha,'
require_contains "$smoke_path" 'mode-required'
require_contains "$smoke_path" 'Build Runtime Images secure-pr-artifact pr-'
require_contains "$smoke_path" 'run.display_title !== expectedDisplayTitle'
require_contains "$smoke_path" 'const pullRequests = run.pull_requests ?? [];'
require_contains "$smoke_path" 'pullRequests.length === 0 || pullRequests.some'
require_contains "$smoke_path" 'pullRequest.base?.sha === baseSha'
require_contains "$smoke_path" 'pullRequest.head?.sha === headSha'
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
const context = {
  repo: { owner: "owner", repo: "repo" },
  sha: mergeSha,
  payload: {
    pull_request: {
      number: 42,
      head: { sha: headSha },
      base: { sha: baseSha, ref: "develop" },
    },
  },
};
const listWorkflowRuns = async () => undefined;
const listJobsForWorkflowRun = async () => undefined;
let smokeStepConclusion = "skipped";
const github = {
  rest: {
    pulls: {
      get: async () => ({
        data: {
          state: "open",
          head: { sha: headSha },
          base: { sha: baseSha, ref: "develop" },
        },
      }),
    },
    actions: { listWorkflowRuns, listJobsForWorkflowRun },
  },
  paginate: async (method) => {
    if (method === listWorkflowRuns) {
      return [{
        id: 101,
        event: "pull_request",
        head_sha: headSha,
        display_title: `Build Runtime Images secure-pr-artifact pr-42 base-${baseSha} head-${headSha} merge-${mergeSha} mode-required`,
        status: "completed",
        conclusion: "success",
        created_at: "2026-09-20T00:00:00Z",
        pull_requests: [],
      }];
    }
    if (method === listJobsForWorkflowRun) {
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
  console.log("Smoke Gate step-conclusion contract passed");
}).catch((error) => {
  console.error(error.stack || error);
  process.exit(1);
});
NODE

echo "PR retarget workflow contract checks passed"
