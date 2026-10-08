#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ACTION="$ROOT_DIR/.github/actions/preserve-required-gate/action.yml"
POLL_SCRIPT="$ROOT_DIR/.github/actions/preserve-required-gate/poll-required-gate.sh"

[[ -f "$ACTION" ]] || {
  echo "required-gate preservation composite action is missing: $ACTION" >&2
  exit 1
}
[[ -f "$POLL_SCRIPT" ]] || {
  echo "required-gate preservation poll script is missing: $POLL_SCRIPT" >&2
  exit 1
}
# shellcheck disable=SC2016 # Assert literal composite action runtime path.
grep -Fxq '      run: bash "$GITHUB_ACTION_PATH/poll-required-gate.sh"' "$ACTION" || {
  echo "required-gate action must invoke its checked-in poll script" >&2
  exit 1
}
grep -Fq 'using: composite' "$ACTION" || {
  echo "required-gate preservation must be a composite action" >&2
  exit 1
}
grep -Fq '  gate-name:' "$ACTION" || {
  echo "required-gate preservation action must expose a gate-name input" >&2
  exit 1
}
if grep -Fq 'allow-pending' "$ACTION" || grep -Fq 'ALLOW_PENDING' "$POLL_SCRIPT"; then
  echo "required-gate preservation must not expose a pending-predecessor success override" >&2
  exit 1
fi

assert_required_input_declaration() {
  local action_path="$1"
  local input_name="$2"
  local input_block

  input_block="$(awk -v expected_input="$input_name" '
    /^inputs:$/ {
      in_inputs = 1
      next
    }
    in_inputs && /^[^[:space:]][A-Za-z0-9_-]*:/ {
      exit
    }
    in_inputs && $0 == "  " expected_input ":" {
      capture = 1
      print
      next
    }
    capture && /^ ? ?[A-Za-z0-9_-]+:/ {
      exit
    }
    capture {
      print
    }
  ' "$action_path")"
  if [[ -z "$input_block" ]] ||
    ! grep -Fxq "  ${input_name}:" <<<"$input_block" ||
    ! grep -Fxq '    required: true' <<<"$input_block"; then
    echo "required-gate preservation action must declare ${input_name} as a required input" >&2
    exit 1
  fi
}

for input_name in workflow-name workflow-file workflow-path; do
  assert_required_input_declaration "$ACTION" "$input_name"
done
# Keep a required declaration outside the inputs mapping from satisfying the
# final input's block-local requirement.
malformed_action="$(mktemp)"
sed \
  -e '/^  workflow-path:/,/^runs:/ s/^    required: true$/    required: false/' \
  -e '/^runs:/a\
    required: true' \
  "$ACTION" >"$malformed_action"
malformed_input_status=0
if (assert_required_input_declaration "$malformed_action" workflow-path) >/dev/null 2>&1; then
  malformed_input_status=0
else
  malformed_input_status=$?
fi
rm -f "$malformed_action"
if [[ "$malformed_input_status" -eq 0 ]]; then
  echo "required-gate preservation action accepted a required declaration outside its inputs block" >&2
  exit 1
fi
# shellcheck disable=SC2016 # Assert literal action input interpolation syntax.
if ! grep -Fq 'GH_TOKEN: ${{ github.token }}' "$ACTION" ||
  ! grep -Fq 'BASE_SHA: ${{ github.event.pull_request.base.sha }}' "$ACTION" ||
  ! grep -Fq 'HEAD_SHA: ${{ github.event.pull_request.head.sha }}' "$ACTION" ||
  ! grep -Fq 'REQUIRED_GATE_NAME: ${{ inputs.gate-name }}' "$ACTION" ||
  ! grep -Fq 'PR_NUMBER: ${{ github.event.pull_request.number }}' "$ACTION" ||
  ! grep -Fq 'EXPECTED_WORKFLOW_NAME: ${{ inputs.workflow-name }}' "$ACTION" ||
  ! grep -Fq 'EXPECTED_WORKFLOW_FILE: ${{ inputs.workflow-file }}' "$ACTION" ||
  ! grep -Fq 'EXPECTED_WORKFLOW_PATH: ${{ inputs.workflow-path }}' "$ACTION" ||
  grep -Fq 'EXPECTED_JOB_ID' "$ACTION"; then
  echo "required-gate action must retain caller, head, gate, and workflow identity inputs without local job identity" >&2
  exit 1
fi

while IFS='|' read -r workflow gate_job_id gate workflow_name workflow_file workflow_path; do
  [[ -n "$workflow" ]] || continue
  path="$ROOT_DIR/.github/workflows/$workflow"
  [[ -f "$path" ]] || {
    echo "required-gate caller workflow is missing: $path" >&2
    exit 1
  }
  expected_run_name="run-name: ${workflow_name} \${{ github.event_name == 'pull_request' && format('pr-{0} base-{1} head-{2}', github.event.pull_request.number, github.event.pull_request.base.sha, github.event.pull_request.head.sha) || github.ref_name }}"
  grep -Fxq "$expected_run_name" "$path" || {
    echo "$workflow must publish its canonical pull-request run title" >&2
    exit 1
  }
  gate_block="$(awk -v expected_job_id="$gate_job_id" '
    /^  [A-Za-z0-9_-]+:$/ {
      if (capture) {
        exit
      }
      capture = ($0 == "  " expected_job_id ":")
    }
    capture {
      print
    }
  ' "$path")"
  [[ -n "$gate_block" ]] || {
    echo "$workflow must contain required gate job ID $gate_job_id" >&2
    exit 1
  }
  if grep -Fq 'allow-pending:' <<<"$gate_block"; then
    echo "$workflow must retain fail-closed required-gate polling" >&2
    exit 1
  fi
  if ! grep -Fxq "    name: $gate" <<<"$gate_block"; then
    echo "$workflow must always emit the required $gate context" >&2
    exit 1
  fi
  if ! grep -Fq "Preserve successful required gate on metadata-only edit" <<<"$gate_block"; then
    echo "$workflow must preserve the required $gate context on metadata-only edits" >&2
    exit 1
  fi
  grep -Fq 'uses: ./.github/actions/preserve-required-gate' <<<"$gate_block" || {
    echo "$workflow must call the shared required-gate action" >&2
    exit 1
  }
  grep -Fxq "          gate-name: $gate" <<<"$gate_block" || {
    echo "$workflow must pass its required gate name to the shared action" >&2
    exit 1
  }
  grep -Fxq "          workflow-name: $workflow_name" <<<"$gate_block" || {
    echo "$workflow must pass its exact workflow name to the shared action" >&2
    exit 1
  }
  grep -Fxq "          workflow-file: $workflow_file" <<<"$gate_block" || {
    echo "$workflow must pass its workflow filename to the shared action" >&2
    exit 1
  }
  grep -Fxq "          workflow-path: $workflow_path" <<<"$gate_block" || {
    echo "$workflow must pass its exact workflow path to the shared action" >&2
    exit 1
  }
  preserve_block="$(awk '
    /^      - name: Preserve successful required gate on metadata-only edit$/ {
      if (found) exit
      found=1
      capture=1
    }
    capture {
      if (/^      - / && $0 != "      - name: Preserve successful required gate on metadata-only edit") exit
      print
    }
  ' <<<"$gate_block")"
  if [[ "$workflow" == "smoke.yml" ]]; then
    # shellcheck disable=SC2016 # Assert literal smoke classification output syntax.
    expected_preserve_condition="        if: \${{ steps.smoke_gate_context.outputs.required != 'true' }}"
  else
    # shellcheck disable=SC2016 # Assert literal metadata-only pull request syntax.
    expected_preserve_condition="        if: \${{ github.event_name == 'pull_request' && github.event.action == 'edited' && github.event.changes.base.ref == null }}"
    if [[ "$workflow" == "codeql.yml" ]]; then
      # CodeQL is pull-request-only at this gate, so its condition omits the redundant event-name check.
      expected_preserve_condition="        if: \${{ github.event.action == 'edited' && github.event.changes.base.ref == null }}"
    fi
  fi
  grep -Fq "$expected_preserve_condition" <<<"$preserve_block" || {
    echo "$workflow $gate preservation must retain its metadata-only condition" >&2
    exit 1
  }
  if grep -Fq 'allow-pending:' <<<"$preserve_block"; then
    echo "$workflow must retain fail-closed required-gate polling" >&2
    exit 1
  fi
  checkout_block="$(awk '
    /^      - name: Check out required-gate action$/ {
      if (found) exit
      found=1
      capture=1
    }
    capture {
      if (/^      - / && $0 != "      - name: Check out required-gate action") exit
      print
    }
  ' <<<"$gate_block")"
  # shellcheck disable=SC2016 # Assert literal trusted pull request base expression.
  grep -Fq '          ref: ${{ github.event.pull_request.base.sha }}' <<<"$checkout_block" || {
    echo "$workflow $gate must load the shared action from the trusted pull request base SHA" >&2
    exit 1
  }
  # shellcheck disable=SC2016 # Assert literal polling syntax is absent from gate callers.
  if grep -Fq 'gh api' <<<"$gate_block" ||
    grep -Eq 'for[[:space:]]+(attempt|poll_attempt|retry_attempt)[[:space:]]+in|while[[:space:]]+.*(attempt|poll|retry)' <<<"$gate_block"; then
    echo "$workflow must delegate required-gate polling to the shared action" >&2
    exit 1
  fi

  first_step="$(awk '/^    steps:$/ {in_steps=1; next} in_steps && /^      - / {print; exit}' <<<"$gate_block")"
  [[ "$first_step" == '      - name: Harden runner' ]] || {
    echo "$workflow $gate must harden the runner as its unconditional first step" >&2
    exit 1
  }
  harden_block="$(awk '/^      - name: Harden runner$/{in_harden=1} in_harden{if (/^      - / && $0 != "      - name: Harden runner") exit; print}' <<<"$gate_block")"
  harden_action_pattern='^        uses: step-security/harden-runner@[0-9a-f]{40}([[:blank:]]+#.*)?[[:blank:]]*$'
  fixture_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  for annotation in '' ' # v2.19.3' ' # verified upstream ref'; do
    fixture_uses="        uses: step-security/harden-runner@${fixture_sha}${annotation}"
    grep -Eq "$harden_action_pattern" <<<"$fixture_uses" || {
      echo "required-gate hardening pin rejected a valid immutable SHA annotation form" >&2
      exit 1
    }
  done
  for invalid_uses in \
    "        uses: actions/harden-runner@${fixture_sha}" \
    '        uses: step-security/harden-runner@v2' \
    "        uses: step-security/harden-runner@${fixture_sha%?}"; do
    if grep -Eq "$harden_action_pattern" <<<"$invalid_uses"; then
      echo "required-gate hardening pin accepted an invalid action reference: $invalid_uses" >&2
      exit 1
    fi
  done
  if grep -Fq '        if:' <<<"$harden_block" ||
    ! grep -Eq "$harden_action_pattern" <<<"$harden_block" ||
    ! grep -Fq '          egress-policy: audit' <<<"$harden_block"; then
    echo "$workflow $gate hardening must remain unconditional and retain its pinned audit configuration" >&2
    exit 1
  fi
  grep -Fq '      checks: read' <<<"$gate_block" || {
    echo "$workflow $gate caller must retain checks: read" >&2
    exit 1
  }
  grep -Fq '      actions: read' <<<"$gate_block" || {
    echo "$workflow $gate caller must retain actions: read for job-step classification" >&2
    exit 1
  }
  grep -Fq '      contents: read' <<<"$gate_block" || {
    echo "$workflow $gate caller must retain contents: read" >&2
    exit 1
  }
  python3 - "$path" "$gate_job_id" "$workflow" "$gate" "$workflow_name" <<'PY'
from pathlib import Path
import sys

import yaml


expected_job_if = {
    "validation-gate": "${{ always() }}",
    "security-gate": "${{ always() }}",
    "license-gate": "${{ always() }}",
    "smoke-gate": "${{ always() && github.event_name == 'pull_request' }}",
    "codeql-gate": "${{ always() && github.event_name == 'pull_request' }}",
}
result_step_names = {
    "validation-gate": "Enforce validation success",
    "security-gate": "Enforce security success",
    "license-gate": "Verify ORT results",
    "smoke-gate": "Classify smoke gate execution",
    "codeql-gate": "Enforce successful CodeQL analysis",
}
change_job_names = {
    "ci.yml": "Detect CI-Relevant Changes",
    "security.yml": "Detect Security-Relevant Changes",
    "license-scan.yml": "Detect License-Relevant Changes",
    "smoke.yml": "Detect Smoke-Relevant Changes",
    "codeql.yml": "Detect CodeQL-Relevant Changes",
}

path = Path(sys.argv[1])
job_id = sys.argv[2]
workflow = sys.argv[3]
gate = sys.argv[4]
expected_workflow_name = sys.argv[5]
try:
    data = yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    job = data["jobs"][job_id]
except (KeyError, TypeError, yaml.YAMLError) as exc:
    raise SystemExit(f"{workflow} must contain a structured {gate} job: {exc}") from exc

if not isinstance(job, dict):
    raise SystemExit(f"{workflow} {gate} job must be a mapping")
if data.get("name") != expected_workflow_name:
    raise SystemExit(
        f"{workflow} must declare the caller-supplied workflow name {expected_workflow_name!r}; "
        f"found {data.get('name')!r}"
    )
if data.get("jobs", {}).get("changes", {}).get("name") != change_job_names[workflow]:
    raise SystemExit(
        f"{workflow} changes job name must match the poller's expected "
        f"{change_job_names[workflow]!r} pattern"
    )
if job_id not in expected_job_if or job_id not in result_step_names:
    raise SystemExit(
        f"{workflow} {gate}: unknown gate job ID {job_id!r}; "
        "add it to expected_job_if and result_step_names"
    )

preserve_steps = [step for step in job["steps"] if step.get("name") == "Preserve successful required gate on metadata-only edit"]
deferred_steps = [step for step in job["steps"] if step.get("name") == "Report dependency-deferred required gate"]
if len(preserve_steps) != 1 or preserve_steps[0].get("id") != "preserved_gate":
    raise SystemExit(f"{workflow} must bind the preservation output")
proof_mode = "${{ github.event.pull_request.number == 3081 && github.event.pull_request.head.ref == 'codex/required-gate-native-rehearsal-v2' && 'assess' || 'poll' }}"
if preserve_steps[0].get("with", {}).get("assessment-mode") != proof_mode:
    raise SystemExit(f"{workflow} must assess only the fixed proof PR/ref and otherwise poll")
if len(deferred_steps) != 1 or deferred_steps[0].get("if") != "${{ steps.preserved_gate.outputs.assessment == 'dependency-deferred' }}":
    raise SystemExit(f"{workflow} must fail distinctly for dependency deferral")
if "exit 1" not in deferred_steps[0].get("run", ""):
    raise SystemExit(f"{workflow} deferral must never report success")

if job.get("if") != expected_job_if[job_id]:
    raise SystemExit(
        f"{workflow} {gate} must retain its exact job if condition: "
        f"{expected_job_if[job_id]!r}"
    )

needs = job.get("needs")
if isinstance(needs, str):
    dependencies = [needs]
elif isinstance(needs, list):
    dependencies = needs
else:
    raise SystemExit(f"{workflow} {gate} must define needs as a scalar or list")
if "changes" not in dependencies:
    raise SystemExit(f"{workflow} {gate} must depend on the changes job")

steps = job.get("steps")
if not isinstance(steps, list):
    raise SystemExit(f"{workflow} {gate} must define steps as a list")
result_steps = [
    step
    for step in steps
    if isinstance(step, dict) and step.get("name") == result_step_names[job_id]
]
if len(result_steps) != 1:
    raise SystemExit(
        f"{workflow} {gate} must contain exactly one {result_step_names[job_id]!r} step"
    )
result_env = result_steps[0].get("env")
if not isinstance(result_env, dict) or result_env.get("CHANGES_RESULT") != "${{ needs.changes.result }}":
    raise SystemExit(
        f"{workflow} {gate} must read needs.changes.result through the exact "
        f"{result_step_names[job_id]!r} CHANGES_RESULT field"
    )
PY

  group_line=$(grep -m1 '^  group:' "$path" || true)
  if [[ "$workflow" == "ci.yml" || "$workflow" == "security.yml" || "$workflow" == "smoke.yml" ]]; then
    if [[ "$group_line" != *"format('metadata-{0}', github.run_id) || 'required' }}"* ]]; then
      echo "$workflow concurrency group must give every metadata-only run a unique namespace" >&2
      exit 1
    fi
  elif [[ "$group_line" != *"&& 'metadata' || 'required' }}"* ]]; then
    echo "$workflow concurrency group must separate metadata and required PR runs" >&2
    exit 1
  fi
  if [[ "$group_line" != *"github.event.pull_request.number"* ]]; then
    echo "$workflow concurrency group must remain scoped to the PR number" >&2
    exit 1
  fi
  if ! grep -Fq '  cancel-in-progress: true' "$path"; then
    echo "$workflow must cancel only within its metadata/required concurrency namespace" >&2
    exit 1
  fi
  if grep -Fq "cancel-in-progress: \${{ github.event_name != 'pull_request' || github.event.action != 'edited'" "$path"; then
    echo "$workflow still uses shared-group conditional cancellation that can race required contexts" >&2
    exit 1
  fi
done <<'EOF'
ci.yml|validation-gate|Validation Gate|CI — Validation|ci.yml|.github/workflows/ci.yml
security.yml|security-gate|Security Gate|Security Gate|security.yml|.github/workflows/security.yml
license-scan.yml|license-gate|License Gate|License Gate|license-scan.yml|.github/workflows/license-scan.yml
smoke.yml|smoke-gate|Smoke Gate|PR Smoke Gate|smoke.yml|.github/workflows/smoke.yml
codeql.yml|codeql-gate|CodeQL Gate|CodeQL Analysis|codeql.yml|.github/workflows/codeql.yml
EOF

# Model two rapid metadata edits against the unchanged head. Each lightweight
# run gets an independent namespace, while the required gate contexts remain
# the sole authoritative checks and optional summaries cannot leave cancelled
# or failed residue in the aggregate rollup.
python3 - "$ROOT_DIR" <<'PY'
from pathlib import Path
import sys

import yaml

root = Path(sys.argv[1])
workflows = {
    "ci.yml": ("validation-gate", "Validation Gate", True),
    "security.yml": ("security-gate", "Security Gate", True),
    "license-scan.yml": ("license-gate", "License Gate", False),
    "smoke.yml": ("smoke-gate", "Smoke Gate", True),
    "codeql.yml": ("codeql-gate", "CodeQL Gate", False),
}

metadata_guard = "github.event.action != 'edited' || github.event.changes.base.ref != null"
for workflow, (gate_job, gate_name, run_scoped_metadata) in workflows.items():
    path = root / ".github" / "workflows" / workflow
    data = yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    concurrency = data["concurrency"]
    group = concurrency["group"]
    required_group_parts = (
        "github.event.pull_request.number",
        "github.event.action == 'edited'",
        "github.event.changes.base.ref == null",
        "|| 'required'",
    )
    if any(part not in group for part in required_group_parts):
        raise SystemExit(f"{workflow} concurrency expression lost PR-scoped metadata isolation")
    metadata_suffix = "format('metadata-{0}', github.run_id)" if run_scoped_metadata else "&& 'metadata' || 'required'"
    if metadata_suffix not in group:
        raise SystemExit(f"{workflow} concurrency expression changed its metadata group")
    if concurrency.get("cancel-in-progress") != "true":
        raise SystemExit(f"{workflow} must cancel obsolete required-gate runs")

    gate = data["jobs"][gate_job]
    if gate.get("name") != gate_name:
        raise SystemExit(f"{workflow} required gate context changed")

for workflow in ("ci.yml", "security.yml", "smoke.yml"):
    path = root / ".github" / "workflows" / workflow
    data = yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    for job_name, job in data["jobs"].items():
        if job_name == workflows[workflow][0]:
            continue
        condition = job.get("if", "") if isinstance(job, dict) else ""
        if metadata_guard not in condition:
            raise SystemExit(
                f"{workflow} job {job_name} can run for a metadata-only edited event"
            )
PY

# shellcheck disable=SC2016 # Match the checked-in arithmetic assignment literally.
poll_timeout_minutes="$(sed -n 's/^poll_timeout_seconds=\$((\([1-9][0-9]*\) \* 60))$/\1/p' "$POLL_SCRIPT")"
# shellcheck disable=SC2016 # Match the checked-in arithmetic assignment literally.
active_poll_timeout_minutes="$(sed -n 's/^active_poll_timeout_seconds=\$((\([1-9][0-9]*\) \* 60))$/\1/p' "$POLL_SCRIPT")"
poll_interval_seconds="$(sed -n 's/^poll_interval_seconds=\([1-9][0-9]*\)$/\1/p' "$POLL_SCRIPT")"
[[ "$poll_timeout_minutes" =~ ^[1-9][0-9]*$ ]] || {
  echo "required-gate action must define one positive minute-based polling timeout" >&2
  exit 1
}
poll_timeout_seconds=$((poll_timeout_minutes * 60))
[[ "$active_poll_timeout_minutes" =~ ^[1-9][0-9]*$ ]] || {
  echo "required-gate action must define one positive active-predecessor timeout" >&2
  exit 1
}
active_poll_timeout_seconds=$((active_poll_timeout_minutes * 60))
[[ "$poll_interval_seconds" =~ ^[1-9][0-9]*$ ]] || {
  echo "required-gate action must define one positive polling interval" >&2
  exit 1
}
(( poll_timeout_seconds > 19 * 60 )) || {
  echo "required-gate action polling timeout must exceed the retired 19-minute budget" >&2
  exit 1
}
(( poll_timeout_seconds < 25 * 60 )) || {
  echo "required-gate action must retain a short missing-predecessor polling budget" >&2
  exit 1
}
(( active_poll_timeout_seconds > poll_timeout_seconds )) || {
  echo "required-gate action must allow a longer wait only for a verified active predecessor" >&2
  exit 1
}
python3 - "$ROOT_DIR" "$active_poll_timeout_seconds" <<'PY'
from pathlib import Path
import sys

import yaml

root = Path(sys.argv[1])
active_poll_timeout_seconds = int(sys.argv[2])
callers = {
    "ci.yml": "validation-gate",
    "codeql.yml": "codeql-gate",
    "license-scan.yml": "license-gate",
    "security.yml": "security-gate",
    "smoke.yml": "smoke-gate",
}
for workflow, job_id in callers.items():
    path = root / ".github" / "workflows" / workflow
    data = yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    timeout_minutes = int(data["jobs"][job_id]["timeout-minutes"])
    if timeout_minutes * 60 < active_poll_timeout_seconds + 5 * 60:
        raise SystemExit(
            f"{workflow} {job_id} timeout must leave five minutes beyond the active-predecessor poll budget"
        )
PY

CODEQL_WORKFLOW="$ROOT_DIR/.github/workflows/codeql.yml"
OVERLAY_WORKFLOW="$ROOT_DIR/.github/workflows/validate-kustomize-overlays.yml"

grep -Fq 'needs: [changes, analyze]' "$CODEQL_WORKFLOW" || {
  echo "CodeQL gate must depend directly on change detection" >&2
  exit 1
}
# The structured caller check above requires CodeQL Gate for every PR base.
grep -Fq 'types: [opened, synchronize, reopened, edited]' "$OVERLAY_WORKFLOW" || {
  echo "Overlay validation must rerun when a pull request base is edited" >&2
  exit 1
}
grep -Fq "github.actor != 'dependabot[bot]' && (github.event_name != 'pull_request' || github.event.action != 'edited' || github.event.changes.base.ref != null)" "$OVERLAY_WORKFLOW" || {
  echo "Overlay validation must skip metadata-only edits without replacing the required context" >&2
  exit 1
}
grep -Fq "name: \${{ github.event_name == 'pull_request' && github.event.action == 'edited' && github.event.changes.base.ref == null && 'PR Metadata Edit (validate-overlays)' || 'validate-overlays' }}" "$OVERLAY_WORKFLOW" || {
  echo "Overlay validation must isolate metadata-only edits from its optional context" >&2
  exit 1
}

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

# The Smoke consumer must outlive its producer and still terminate fail closed.
smoke_clock_fixture="$tmp_dir/smoke-clock.js"
python3 - "$ROOT_DIR" "$smoke_clock_fixture" <<'PY'
from pathlib import Path
import re
import sys
import yaml

root = Path(sys.argv[1])
smoke = yaml.load((root / ".github/workflows/smoke.yml").read_text(), Loader=yaml.BaseLoader)
producer = yaml.load((root / ".github/workflows/runtime-images.yml").read_text(), Loader=yaml.BaseLoader)
job = smoke["jobs"]["smoke-gate"]
script = next(step["with"]["script"] for step in job["steps"] if step.get("name") == "Track full-stack smoke result from Build Runtime Images")
producer_minutes = int(re.search(r"const producerTimeoutMinutes = ([0-9]+);", script)[1])
overhead_minutes = int(re.search(r"const prerequisiteAndQueueAllowanceMinutes = ([0-9]+);", script)[1])
if producer_minutes != int(producer["jobs"]["pr-local-smoke"]["timeout-minutes"]):
    raise SystemExit("Smoke polling producer budget must match the actual full-stack producer timeout")
if overhead_minutes != 45 or int(job["timeout-minutes"]) < producer_minutes + overhead_minutes + 5:
    raise SystemExit("Smoke polling must retain bounded scheduling overhead and five-minute job margin")
if "const timeoutMs = (producerTimeoutMinutes + prerequisiteAndQueueAllowanceMinutes) * 60 * 1000;" not in script:
    raise SystemExit("Smoke polling deadline must use the checked producer and overhead authorities")
Path(sys.argv[2]).write_text(script)
PY

node - "$smoke_clock_fixture" <<'NODE'
const assert = require("node:assert/strict");
const script = require("node:fs").readFileSync(process.argv[2], "utf8");
const head = "a".repeat(40), base = "b".repeat(40), merge = "c".repeat(40);
const title = `Build Runtime Images secure-pr-artifact pr-42 base-${base} head-${head} merge-${merge} mode-required`;
const context = { repo: { owner: "example", repo: "firemud" }, sha: merge,
  payload: { pull_request: { number: 42, head: { sha: head }, base: { ref: "develop", sha: base }, created_at: "2026-09-01T00:00:00Z" } } };
const execute = new Function("github", "context", "core", "Date", "setTimeout", `return (async () => {\n${script}\n})()`);
async function scenario(outcome, completedAtMinutes) {
  let now = 1000;
  const start = now;
  const failures = [];
  const listRuns = async () => {}, listJobs = async () => {};
  const github = { rest: {
    pulls: { get: async () => ({ data: { state: "open", head: { sha: head }, base: { ref: "develop", sha: base }, merge_commit_sha: merge } }) },
    repos: { getCommit: async () => ({ data: { sha: merge, parents: [{ sha: base }, { sha: head }] } }) },
    actions: { listWorkflowRuns: listRuns, listJobsForWorkflowRun: listJobs },
  }, paginate: async (method, input) => {
    assert.equal(method, listJobs);
    assert.equal(input.run_id, 42);
    return [{ name: "PR Full-Stack Smoke", status: "completed", conclusion: "success",
      steps: [{ name: "Run credential-free full-stack smoke", status: "completed", conclusion: "success" }] }];
  } };
  github.paginate.iterator = async function* (method, input) {
    assert.equal(method, listRuns);
    assert.equal(input.workflow_id, "runtime-images.yml");
    if (input.event === "repository_dispatch") { yield { data: [] }; return; }
    assert.equal(input.head_sha, head);
    const complete = now - start >= completedAtMinutes * 60 * 1000;
    yield { data: [{ id: 42, event: "pull_request", head_sha: head, display_title: title, pull_requests: [],
      status: complete ? "completed" : "in_progress", conclusion: complete ? outcome : null }] };
  };
  const core = { info: () => {}, warning: () => {}, setFailed: message => failures.push(message) };
  const clock = { now: () => now, parse: Date.parse };
  const sleep = (callback, duration) => { now += duration; queueMicrotask(callback); };
  await execute(github, context, core, clock, sleep);
  return { failures, minutes: (now - start) / 60000 };
}
(async () => {
  const success = await scenario("success", 30);
  assert.deepEqual(success.failures, [], "exact producer success after 25 minutes must be admitted");
  assert.equal(success.minutes, 30);
  const failed = await scenario("failure", 30);
  assert.equal(failed.failures.length, 1);
  assert.match(failed.failures[0], /did not complete successfully/);
  assert.equal(failed.minutes, 30, "terminal failure must stop polling promptly");
  const cancelled = await scenario("cancelled", 0);
  assert.equal(cancelled.failures.length, 1);
  assert.match(cancelled.failures[0], /cancelled/i);
  assert.ok(cancelled.minutes <= 5.25, "cancelled source without an exact replacement remains bounded");
  const deadline = await scenario("success", Infinity);
  assert.equal(deadline.failures.length, 1);
  assert.match(deadline.failures[0], /Timed out waiting for the exact/);
  assert.equal(deadline.minutes, 90, "active producers cannot escape the total deadline");
  console.log("Smoke clock contract passed: late success, prompt failure, bounded cancellation and deadline");
})().catch(error => { console.error(error); process.exitCode = 1; });
NODE

cat >"$tmp_dir/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
count_file="${GH_RETRY_COUNT_FILE:?}"
if [[ -n "${GH_CALL_COUNT_DIR:-}" ]]; then
  mkdir -p "$GH_CALL_COUNT_DIR"
fi
if [[ "$*" == *"/actions/workflows/${EXPECTED_WORKFLOW_FILE}/runs"* ]]; then
  if [[ "$*" == *"head_sha="* || "$*" != *"event=pull_request"* ||
    "$*" != *"--paginate"* || "$*" != *"--slurp"* ]]; then
    echo "simulated active workflow lookup used a branch-tip filter or omitted event/pagination" >&2
    exit 90
  fi
  active_status=""
  for argument in "$@"; do
    case "$argument" in
      status=*) active_status="${argument#status=}" ;;
    esac
  done
  case "$active_status" in
    in_progress|queued|requested|waiting|pending) ;;
    *)
      echo "simulated active workflow lookup omitted a single supported status filter" >&2
      exit 90
      ;;
  esac
  if [[ -n "${GH_CALL_COUNT_DIR:-}" ]]; then
    active_call_file="$GH_CALL_COUNT_DIR/active-$active_status"
    active_call_count=0
    [[ -f "$active_call_file" ]] && active_call_count="$(<"$active_call_file")"
    printf '%s' "$((active_call_count + 1))" >"$active_call_file"
  fi
  case "${GH_SCENARIO:-}" in
    active-workflow-delayed-gate|active-workflow-late-arrival|active-workflow-wrong-base|active-workflow-metadata-only|active-workflow-mismatched-tuple)
      if [[ "${GH_SCENARIO}" == "active-workflow-late-arrival" &&
        "$(<"$count_file")" -ne 92 ]]; then
        printf '[{"workflow_runs":[]} ]\n'
        exit 0
      fi
      if [[ "$active_status" != "in_progress" ]]; then
        printf '[{"workflow_runs":[]} ]\n'
        exit 0
      fi
      display_title="CI — Validation pr-${PR_NUMBER} base-${BASE_SHA} head-${HEAD_SHA}"
      [[ "${GH_SCENARIO}" == "active-workflow-wrong-base" ]] && display_title="CI — Validation pr-${PR_NUMBER} base-cccccccccccccccccccccccccccccccccccccccc head-${HEAD_SHA}"
      run_head_sha="${HEAD_SHA}"
      pull_requests='[]'
      if [[ "${GH_SCENARIO}" == "active-workflow-delayed-gate" ||
        "${GH_SCENARIO}" == "active-workflow-late-arrival" ]]; then
        # GitHub may report the synthetic merge SHA for pull_request runs;
        # the populated PR tuple remains the authoritative identity.
        run_head_sha=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
        pull_requests="[{\"number\":${PR_NUMBER},\"base\":{\"sha\":\"${BASE_SHA}\"},\"head\":{\"sha\":\"${HEAD_SHA}\"}}]"
      elif [[ "${GH_SCENARIO}" == "active-workflow-mismatched-tuple" ]]; then
        pull_requests="[{\"number\":${PR_NUMBER},\"base\":{\"sha\":\"${BASE_SHA}\"},\"head\":{\"sha\":\"dddddddddddddddddddddddddddddddddddddddd\"}}]"
      fi
      printf '[{"workflow_runs":[{"id":100,"workflow_id":42,"name":"%s","path":".github/workflows/ci.yml","head_sha":"%s","display_title":"%s","repository":{"full_name":"example/firemud"},"event":"pull_request","status":"in_progress","pull_requests":%s}]}]\n' "$display_title" "$run_head_sha" "$display_title" "$pull_requests"
      ;;
    active-workflow-malformed-list)
      printf '[{"workflow_runs":"invalid"}]\n'
      ;;
    *)
      printf '[{"workflow_runs":[]}]\n'
      ;;
  esac
  exit 0
fi
if [[ "$*" == *"/actions/workflows/"* ]]; then
  if [[ "$*" != *"/actions/workflows/${EXPECTED_WORKFLOW_FILE}"* ]]; then
    echo "simulated workflow lookup did not target the expected workflow filename" >&2
    exit 90
  fi
  if [[ "${GH_SCENARIO:-}" == "no-local-workflow-file" ]]; then
    : >"$count_file"
  fi
  if [[ "${GH_SCENARIO:-}" == "workflow-identity-mismatch" ]]; then
    cat <<'JSON'
{"id":99,"name":"Security Checks","path":".github/workflows/security.yml"}
JSON
  else
    cat <<'JSON'
{"id":42,"name":"CI — Validation","path":".github/workflows/ci.yml"}
JSON
  fi
  exit 0
fi
if [[ "$*" == *"/actions/runs/100/jobs"* ]]; then
  if [[ "$*" != *"--paginate"* || "$*" != *"--slurp"* ]]; then
    echo "simulated workflow jobs lookup omitted pagination" >&2
    exit 90
  fi
  if [[ -n "${GH_CALL_COUNT_DIR:-}" ]]; then
    active_jobs_file="$GH_CALL_COUNT_DIR/run-jobs-100"
    active_jobs_count=0
    [[ -f "$active_jobs_file" ]] && active_jobs_count="$(<"$active_jobs_file")"
    printf '%s' "$((active_jobs_count + 1))" >"$active_jobs_file"
  fi
  change_conclusion=success
  [[ "${GH_SCENARIO:-}" == "active-workflow-metadata-only" ]] && change_conclusion=skipped
  printf '[{"jobs":[{"id":20,"name":"Detect CI-Relevant Changes","status":"completed","conclusion":"%s"}]}]\n' "$change_conclusion"
  exit 0
fi
if [[ "$*" == *"/actions/jobs/"* ]]; then
  job_endpoint="${!#}"
  job_id="${job_endpoint##*/}"
  run_id="$job_id"
  if [[ -n "${GH_CALL_COUNT_DIR:-}" ]]; then
    job_call_file="$GH_CALL_COUNT_DIR/jobs-$job_id"
    job_call_count=0
    [[ -f "$job_call_file" ]] && job_call_count="$(<"$job_call_file")"
    printf '%s' "$((job_call_count + 1))" >"$job_call_file"
  fi
  if [[ "${GH_SCENARIO:-}" == "job-failure-retry" && "$job_id" == "100" &&
    ! -e "${count_file}.job-failure-once" ]]; then
    : >"${count_file}.job-failure-once"
    echo "gh: HTTP 503 Service Unavailable" >&2
    exit 1
  fi
  job_workflow_name='CI — Validation'
  if [[ "${GH_SCENARIO:-}" == "dynamic-run-name" ||
    "${GH_SCENARIO:-}" == "dynamic-run-name-fork-empty" ]]; then
    job_workflow_name="${job_workflow_name} pr-${PR_NUMBER} base-${BASE_SHA} head-${HEAD_SHA}"
  elif [[ "${GH_SCENARIO:-}" == "wrong-job-workflow-name" ]]; then
    job_workflow_name='Security Checks'
  fi
  metadata=false
  if [[ "${GH_SCENARIO:-}" == "multiple-metadata" ]] ||
    [[ "${GH_SCENARIO:-}" == "latest-pending-preferred" && "${job_id}" == "101" ]] ||
    [[ "${GH_SCENARIO:-}" == "cache-metadata-across-polls" && "${job_id}" == "100" ]]; then
    metadata=true
  fi
  if [[ "${metadata}" == "true" ]]; then
    case "${GH_SCENARIO}:${job_id}" in
      multiple-metadata:100) preserve_conclusion=success ;;
      multiple-metadata:101) preserve_conclusion=failure ;;
      multiple-metadata:102) preserve_conclusion=timed_out ;;
      multiple-metadata:103) preserve_conclusion=cancelled ;;
      *) preserve_conclusion=success ;;
    esac
    printf '{"id":%s,"run_id":%s,"name":"Validation Gate","workflow_name":"%s","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","check_run_url":"https://api.github.com/repos/example/firemud/check-runs/%s","steps":[{"name":"Preserve successful required gate on metadata-only edit","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"%s"}]}\n' "$job_id" "$run_id" "$job_workflow_name" "$job_id" "$preserve_conclusion"
  elif [[ "${GH_SCENARIO:-}" == "unverified-pending-gate" && "${job_id}" == "100" ]]; then
    printf '{"id":%s,"run_id":%s,"name":"Validation Gate","workflow_name":"CI — Validation","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","check_run_url":"https://api.github.com/repos/example/firemud/check-runs/%s","steps":[{"name":"Preserve successful required gate on metadata-only edit","status":"in_progress","completed_at":null,"conclusion":null}]}\n' "$job_id" "$run_id" "$job_id"
  elif [[ "${GH_SCENARIO:-}" == "pending-preservation-step-not-concluded" &&
    "${job_id}" == "100" && "$(<"$count_file")" -le 3 ]]; then
    printf '{"id":%s,"run_id":%s,"name":"Validation Gate","workflow_name":"CI — Validation","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","check_run_url":"https://api.github.com/repos/example/firemud/check-runs/%s","steps":[{"name":"Preserve successful required gate on metadata-only edit","status":"in_progress","completed_at":null,"conclusion":null}]}\n' "$job_id" "$run_id" "$job_id"
  elif [[ "${GH_SCENARIO:-}" == "pending-gate-discovery-throttle" &&
    "${job_id}" == "100" && "$(<"$count_file")" -le 5 ]]; then
    printf '{"id":%s,"run_id":%s,"name":"Validation Gate","workflow_name":"CI — Validation","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","check_run_url":"https://api.github.com/repos/example/firemud/check-runs/%s","steps":[{"name":"Preserve successful required gate on metadata-only edit","status":"in_progress","completed_at":null,"conclusion":null}]}\n' "$job_id" "$run_id" "$job_id"
  elif [[ "${GH_SCENARIO:-}" == "pending-missing-step-with-failed-substantive" &&
    "${job_id}" == "101" ]]; then
    printf '{"id":%s,"run_id":%s,"name":"Validation Gate","workflow_name":"CI — Validation","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","check_run_url":"https://api.github.com/repos/example/firemud/check-runs/%s","steps":[{"name":"Preserve successful required gate on metadata-only edit","status":"in_progress","completed_at":null,"conclusion":null}]}\n' "$job_id" "$run_id" "$job_id"
  elif [[ "${GH_SCENARIO:-}" == "completed-preservation-step-lag" &&
    "${job_id}" == "100" && "$(<"$count_file")" -eq 1 ]]; then
    printf '{"id":%s,"run_id":%s,"name":"Validation Gate","workflow_name":"CI — Validation","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","check_run_url":"https://api.github.com/repos/example/firemud/check-runs/%s","steps":[{"name":"Preserve successful required gate on metadata-only edit","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":null}]}\n' "$job_id" "$run_id" "$job_id"
  elif [[ "${GH_SCENARIO:-}" == "completed-preservation-step-persistent-lag" &&
    "${job_id}" == "100" ]]; then
    printf '{"id":%s,"run_id":%s,"name":"Validation Gate","workflow_name":"CI — Validation","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","check_run_url":"https://api.github.com/repos/example/firemud/check-runs/%s","steps":[{"name":"Preserve successful required gate on metadata-only edit","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":null}]}\n' "$job_id" "$run_id" "$job_id"
  elif [[ "${GH_SCENARIO:-}" =~ ^completed-(cancelled|skipped|stale)-missing-step$ &&
    "${job_id}" == "100" ]]; then
    printf '{"id":%s,"run_id":%s,"name":"Validation Gate","workflow_name":"CI — Validation","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","check_run_url":"https://api.github.com/repos/example/firemud/check-runs/%s","steps":[{"name":"Preserve successful required gate on metadata-only edit","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":null}]}\n' "$job_id" "$run_id" "$job_id"
  else
    printf '{"id":%s,"run_id":%s,"name":"Validation Gate","workflow_name":"%s","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","check_run_url":"https://api.github.com/repos/example/firemud/check-runs/%s","steps":[{"name":"Preserve successful required gate on metadata-only edit","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"skipped"}]}\n' "$job_id" "$run_id" "$job_workflow_name" "$job_id"
  fi
  exit 0
fi
if [[ "$*" == *"/actions/runs/"* ]]; then
  run_endpoint="${!#}"
  run_id="${run_endpoint##*/}"
  if [[ -n "${GH_CALL_COUNT_DIR:-}" ]]; then
    run_call_file="$GH_CALL_COUNT_DIR/runs-$run_id"
    run_call_count=0
    [[ -f "$run_call_file" ]] && run_call_count="$(<"$run_call_file")"
    printf '%s' "$((run_call_count + 1))" >"$run_call_file"
  fi
  workflow_name='CI — Validation'
  workflow_path='.github/workflows/ci.yml'
  workflow_id=42
  run_repository='example/firemud'
  head_repository='example/firemud'
  pull_requests='[{"number":123}]'
  display_title="${workflow_name} pr-${PR_NUMBER} base-${BASE_SHA} head-${HEAD_SHA}"
  if [[ "${GH_SCENARIO:-}" == "dynamic-run-name" ||
    "${GH_SCENARIO:-}" == "dynamic-run-name-fork-empty" ]]; then
    workflow_name="$display_title"
    display_title="$workflow_name"
  elif [[ "${GH_SCENARIO:-}" == "wrong-run-name" ]]; then
    workflow_name='Security Checks'
  fi
  if [[ "${GH_SCENARIO:-}" == "valid-new-tuple" ]]; then
    pull_requests="[{\"number\":${PR_NUMBER},\"base\":{\"sha\":\"${BASE_SHA}\"},\"head\":{\"sha\":\"${HEAD_SHA}\"}}]"
  elif [[ "${GH_SCENARIO:-}" == "stale-then-current-tuple" ]]; then
    if [[ "${run_id}" == "200" ]]; then
      display_title="${workflow_name} pr-${PR_NUMBER} base-cccccccccccccccccccccccccccccccccccccccc head-${HEAD_SHA}"
      pull_requests="[{\"number\":${PR_NUMBER},\"base\":{\"sha\":\"cccccccccccccccccccccccccccccccccccccccc\"},\"head\":{\"sha\":\"${HEAD_SHA}\"}}]"
    else
      pull_requests="[{\"number\":${PR_NUMBER},\"base\":{\"sha\":\"${BASE_SHA}\"},\"head\":{\"sha\":\"${HEAD_SHA}\"}}]"
    fi
  fi
  if [[ "${GH_SCENARIO:-}" == "fork-empty-association" ||
    "${GH_SCENARIO:-}" == "dynamic-run-name-fork-empty" ]]; then
    pull_requests='[]'
    head_repository='other-owner/firemud'
  elif [[ "${GH_SCENARIO:-}" == "empty-wrong-pr" ]]; then
    pull_requests='[]'
    display_title="${workflow_name} pr-456 base-${BASE_SHA} head-${HEAD_SHA}"
  elif [[ "${GH_SCENARIO:-}" == "empty-wrong-base" ]]; then
    pull_requests='[]'
    display_title="${workflow_name} pr-${PR_NUMBER} base-cccccccccccccccccccccccccccccccccccccccc head-${HEAD_SHA}"
  elif [[ "${GH_SCENARIO:-}" == "populated-wrong-base" ]]; then
    display_title="${workflow_name} pr-${PR_NUMBER} base-cccccccccccccccccccccccccccccccccccccccc head-${HEAD_SHA}"
    pull_requests="[{\"number\":${PR_NUMBER},\"base\":{\"sha\":\"cccccccccccccccccccccccccccccccccccccccc\"},\"head\":{\"sha\":\"${HEAD_SHA}\"}}]"
  elif [[ "${GH_SCENARIO:-}" == "empty-wrong-head" ]]; then
    pull_requests='[]'
    display_title="${workflow_name} pr-${PR_NUMBER} base-${BASE_SHA} head-dddddddddddddddddddddddddddddddddddddddd"
  elif [[ "${GH_SCENARIO:-}" == "populated-wrong-head" ]]; then
    display_title="${workflow_name} pr-${PR_NUMBER} base-${BASE_SHA} head-dddddddddddddddddddddddddddddddddddddddd"
    pull_requests="[{\"number\":${PR_NUMBER},\"base\":{\"sha\":\"${BASE_SHA}\"},\"head\":{\"sha\":\"dddddddddddddddddddddddddddddddddddddddd\"}}]"
  elif [[ "${GH_SCENARIO:-}" == "empty-wrong-title" ]]; then
    pull_requests='[]'
    display_title='not the canonical run title'
  elif [[ "${GH_SCENARIO:-}" == "empty-malformed-association" ]]; then
    pull_requests='null'
  elif [[ "${GH_SCENARIO:-}" == "other-pr-association" ]]; then
    pull_requests='[{"number":456}]'
  elif [[ "${GH_SCENARIO:-}" == "cross-workflow-same-name" ||
    "${GH_SCENARIO:-}" == "duplicate-invalid-run-identity" ]]; then
    workflow_path='.github/workflows/other.yml'
    workflow_id=99
  elif [[ "${GH_SCENARIO:-}" == "run-path-ref-suffix" ]]; then
    workflow_path='.github/workflows/ci.yml@main'
  elif [[ "${GH_SCENARIO:-}" == "run-path-ref-suffix-plus" ]]; then
    workflow_path='.github/workflows/ci.yml@feature+metadata'
  elif [[ "${GH_SCENARIO:-}" == "run-path-ref-suffix-at" ]]; then
    workflow_path='.github/workflows/ci.yml@feature@metadata'
  elif [[ "${GH_SCENARIO:-}" == "malformed-run-path-ref-suffix" ]]; then
    workflow_path='.github/workflows/ci.yml@'
  elif [[ "${GH_SCENARIO:-}" == "fork-head-same-sha" ]]; then
    head_repository='other-owner/firemud'
  elif [[ "${GH_SCENARIO:-}" == "wrong-run-repository" ]]; then
    run_repository='other-owner/firemud'
    head_repository='other-owner/firemud'
  fi
  printf '{"id":%s,"workflow_id":%s,"name":"%s","path":"%s","head_sha":"%s","display_title":"%s","repository":{"full_name":"%s"},"head_repository":{"full_name":"%s"},"event":"pull_request","pull_requests":%s}\n' "$run_id" "$workflow_id" "$workflow_name" "$workflow_path" "$HEAD_SHA" "$display_title" "$run_repository" "$head_repository" "$pull_requests"
  exit 0
fi
if [[ "$*" != *"/repos/${GITHUB_REPOSITORY}/commits/${HEAD_SHA}/check-runs"* ]]; then
  echo "simulated gh api call did not target the pull request head check-runs endpoint" >&2
  exit 90
fi
if [[ "$*" != *"--paginate"* || "$*" != *"--slurp"* ]]; then
  echo "simulated gh api call omitted pagination/slurp" >&2
  exit 90
fi
if [[ "$*" != *"check_name=${REQUIRED_GATE_NAME}"* || "$*" != *"filter=all"* ]]; then
  echo "simulated gh api call did not scope the query to the required gate name" >&2
  exit 90
fi
if [[ "$*" == *"--jq"* ]]; then
  echo "simulated gh api call must leave filtering to external jq" >&2
  exit 90
fi
count=0
if [[ -f "$count_file" ]]; then
  count="$(<"$count_file")"
fi
count=$((count + 1))
printf '%s' "$count" >"$count_file"
if [[ "${GH_SCENARIO:-}" == check-coverage-* ]]; then
  python3 - "$GH_SCENARIO" <<'PYTHON'
import json
import sys
scenario = sys.argv[1]
def check(identity):
    return {"app": {"slug": "github-actions"}, "name": "Validation Gate", "id": identity,
            "details_url": f"https://github.com/example/firemud/actions/runs/{identity}/job/{identity}",
            "status": "completed", "conclusion": "success", "completed_at": "2026-07-30T02:00:00Z",
            "started_at": "2026-07-30T01:00:00Z"}
pages = [{"total_count": 1, "check_runs": [check(100)]}]
if scenario == "check-coverage-truncated":
    # Omit the newer failed original from the existing newer-failure case.
    pages[0]["total_count"] = 2
elif scenario == "check-coverage-missing-total":
    pages[0].pop("total_count")
elif scenario == "check-coverage-malformed-total":
    pages[0]["total_count"] = "1"
elif scenario == "check-coverage-overcount":
    pages[0]["total_count"] = 0
elif scenario == "check-coverage-invalid-id":
    pages[0]["check_runs"][0]["id"] = "100"
elif scenario == "check-coverage-empty-pages":
    pages = []
elif scenario == "check-coverage-duplicate":
    pages = [{"total_count": 2, "check_runs": [check(100)]}, {"total_count": 2, "check_runs": [check(100)]}]
elif scenario in {"check-coverage-multi", "check-coverage-inconsistent-total"}:
    pages = [{"total_count": 2, "check_runs": [check(100)]},
             {"total_count": 3 if scenario.endswith("inconsistent-total") else 2, "check_runs": [check(101)]}]
else:
    raise AssertionError(scenario)
print(json.dumps(pages))
PYTHON
  exit 0
fi
case "${GH_SCENARIO:-failure-retry}" in
  latest-pending-preferred)
    if [[ "$count" -eq 1 ]]; then
      cat <<'JSON'
[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":101,"details_url":"https://github.com/example/firemud/actions/runs/101/job/101","status":"in_progress","conclusion":null,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]
JSON
    else
      cat <<'JSON'
[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":101,"details_url":"https://github.com/example/firemud/actions/runs/101/job/101","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]
JSON
    fi
    ;;
  missing-created-at)
    printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  pending-predecessor)
    if [[ "$count" -eq 1 ]]; then
      cat <<'JSON'
[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"in_progress","conclusion":null,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]
JSON
    else
      cat <<'JSON'
[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]
JSON
    fi
    ;;
  queued-null-started-at)
    if [[ "$count" -eq 1 ]]; then
      printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"queued","conclusion":null,"started_at":null,"created_at":"2026-07-30T01:00:00Z"}]}]\n'
    else
      printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    fi
    ;;
  no-local-workflow-file)
    printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  no-prior|active-workflow-wrong-base|active-workflow-metadata-only|active-workflow-mismatched-tuple|active-workflow-malformed-list)
    printf '[{"total_count":0,"check_runs":[]}]\n'
    ;;
  delayed-predecessor)
    if [[ "$count" -eq 1 ]]; then
      printf '[{"total_count":0,"check_runs":[]}]\n'
    elif [[ "$count" -eq 2 ]]; then
      printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"in_progress","conclusion":null,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    else
      printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    fi
    ;;
  delayed-predecessor-after-19-minutes)
    if [[ "$count" -le 77 ]]; then
      printf '[{"total_count":0,"check_runs":[]}]\n'
    else
      printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:20:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    fi
    ;;
  active-workflow-delayed-gate)
    if [[ "$count" -le 97 ]]; then
      printf '[{"total_count":0,"check_runs":[]}]\n'
    else
      printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:30:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    fi
    ;;
  active-workflow-late-arrival)
    if [[ "$count" -le 92 ]]; then
      printf '[{"total_count":0,"check_runs":[]} ]\n'
    else
      printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:30:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    fi
    ;;
  failed-predecessor)
    printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"failure","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  newer-failure-over-success)
    printf '[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:50:00Z","created_at":"2026-07-30T00:50:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":101,"details_url":"https://github.com/example/firemud/actions/runs/101/job/101","status":"completed","conclusion":"failure","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:50:00Z","created_at":"2026-07-30T01:50:00Z"}]}]\n'
    ;;
  same-timestamp-newer-failure)
    printf '[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":101,"details_url":"https://github.com/example/firemud/actions/runs/101/job/101","status":"completed","conclusion":"failure","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  stale-then-current-tuple)
    printf '[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":200,"details_url":"https://github.com/example/firemud/actions/runs/200/job/200","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":201,"details_url":"https://github.com/example/firemud/actions/runs/201/job/201","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  cross-workflow-same-name|fork-empty-association|dynamic-run-name|dynamic-run-name-fork-empty|wrong-run-name|wrong-job-workflow-name|empty-wrong-pr|empty-wrong-base|populated-wrong-base|empty-wrong-head|populated-wrong-head|empty-wrong-title|empty-malformed-association|other-pr-association|valid-new-tuple)
    printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":200,"details_url":"https://github.com/example/firemud/actions/runs/200/job/200","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  duplicate-invalid-run-identity)
    printf '[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":200,"details_url":"https://github.com/example/firemud/actions/runs/200/job/200","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":201,"details_url":"https://github.com/example/firemud/actions/runs/200/job/201","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  run-path-ref-suffix|run-path-ref-suffix-plus|run-path-ref-suffix-at|malformed-run-path-ref-suffix)
    printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":200,"details_url":"https://github.com/example/firemud/actions/runs/200/job/200","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  details-url-query|details-url-fragment|malformed-details-url-suffix)
    details_suffix='?check_suite_focus=true'
    [[ "${GH_SCENARIO:-}" == "details-url-fragment" ]] && details_suffix='#step:1:2'
    [[ "${GH_SCENARIO:-}" == "malformed-details-url-suffix" ]] && details_suffix='@evil'
    printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":200,"details_url":"https://github.com/example/firemud/actions/runs/200/job/200%s","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n' "$details_suffix"
    ;;
  fork-head-same-sha)
    printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":200,"details_url":"https://github.com/example/firemud/actions/runs/200/job/200","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  wrong-run-repository)
    printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":200,"details_url":"https://github.com/example/firemud/actions/runs/200/job/200","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  unknown-app)
    printf '[{"total_count":2,"check_runs":[{"app":{"slug":"other-checks"},"name":"Validation Gate","id":200,"details_url":"https://github.com/example/firemud/actions/runs/200/job/200","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n'
    ;;
  unknown-check-name)
    printf '[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Other Gate","id":200,"details_url":"https://github.com/example/firemud/actions/runs/200/job/200","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n'
    ;;
  invalid-job-id)
    printf '[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/not-a-number","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n'
    ;;
  missing-job-id)
    printf '[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n'
    ;;
  completed-preservation-step-lag)
    printf '[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n'
    ;;
  completed-preservation-step-persistent-lag)
    printf '[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n'
    ;;
  completed-cancelled-missing-step|completed-skipped-missing-step|completed-stale-missing-step)
    case "${GH_SCENARIO}" in
      completed-cancelled-missing-step) non_authoritative_conclusion=cancelled ;;
      completed-skipped-missing-step) non_authoritative_conclusion=skipped ;;
      completed-stale-missing-step) non_authoritative_conclusion=stale ;;
    esac
    printf '[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"%s","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n' "$non_authoritative_conclusion"
    ;;
  unsupported-status)
    printf '[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"mysterious","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n'
    ;;
  missing-timestamp)
    printf '[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":null,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n'
    ;;
  multiple-metadata)
    printf '[{"total_count":4,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:50:00Z","created_at":"2026-07-30T00:50:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":101,"details_url":"https://github.com/example/firemud/actions/runs/101/job/101","status":"completed","conclusion":"failure","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:50:00Z","created_at":"2026-07-30T01:50:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":102,"details_url":"https://github.com/example/firemud/actions/runs/102/job/102","status":"completed","conclusion":"timed_out","completed_at":"2026-07-30T03:00:00Z","started_at":"2026-07-30T02:50:00Z","created_at":"2026-07-30T02:50:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":103,"details_url":"https://github.com/example/firemud/actions/runs/103/job/103","status":"completed","conclusion":"cancelled","completed_at":"2026-07-30T04:00:00Z","started_at":"2026-07-30T03:50:00Z","created_at":"2026-07-30T03:50:00Z"}]}]\n'
    ;;
  self-run-excluded)
    printf '[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":1,"details_url":"https://github.com/example/firemud/actions/runs/999/job/1","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"failure","started_at":"2026-07-30T02:00:00Z","created_at":"2026-07-30T02:00:00Z"}]}]\n'
    ;;
  alternate-pending)
    case "$count" in
      1) status=requested ;;
      2) status=waiting ;;
      3) status=pending ;;
      *) status=completed ;;
    esac
    if [[ "$status" == "completed" ]]; then
      conclusion='"success"'
    else
      conclusion=null
    fi
    completed_at=null
    if [[ "$status" == "completed" ]]; then
      completed_at='"2026-07-30T02:00:00Z"'
    fi
    printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"%s","conclusion":%s,"completed_at":%s,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n' "$status" "$conclusion" "$completed_at"
    ;;
  pending-preservation-step-not-concluded)
    case "$count" in
      1) status=queued ;;
      2) status=in_progress ;;
      3) status=waiting ;;
      *) status=completed ;;
    esac
    if [[ "$status" == "completed" ]]; then
      conclusion='"success"'
      completed_at='"2026-07-30T02:00:00Z"'
    else
      conclusion=null
      completed_at=null
    fi
    printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"%s","conclusion":%s,"completed_at":%s,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n' "$status" "$conclusion" "$completed_at"
    ;;
  pending-gate-discovery-throttle)
    if [[ "$count" -le 5 ]]; then
      printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"in_progress","conclusion":null,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    else
      printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    fi
    ;;
  cache-metadata-across-polls)
    if [[ "$count" -eq 1 ]]; then
      printf '[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":101,"details_url":"https://github.com/example/firemud/actions/runs/101/job/101","status":"in_progress","conclusion":null,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    else
      printf '[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":101,"details_url":"https://github.com/example/firemud/actions/runs/101/job/101","status":"completed","conclusion":"success","completed_at":"2026-07-30T03:00:00Z","started_at":"2026-07-30T02:00:00Z","created_at":"2026-07-30T02:00:00Z"}]}]\n'
    fi
    ;;
  pending-missing-step-with-failed-substantive)
    printf '[{"total_count":2,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":101,"details_url":"https://github.com/example/firemud/actions/runs/101/job/101","status":"in_progress","conclusion":null,"completed_at":null,"started_at":"2026-07-30T02:00:00Z","created_at":"2026-07-30T02:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"failure","completed_at":"2026-07-30T03:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  timeout-pending|unverified-pending-gate)
    printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"waiting","conclusion":null,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  failure-retry)
    case "${GH_FAILURE_MODE:-transient}" in
      transient|network)
        if [[ "$count" -eq 1 ]]; then
          if [[ "${GH_FAILURE_MODE}" == "network" ]]; then
            echo "gh: error connecting to api.github.com" >&2
          else
            echo "gh: HTTP 503 Service Unavailable" >&2
          fi
          exit 1
        fi
        ;;
      rate-limit)
        if [[ "$count" -eq 1 ]]; then
          echo "gh: HTTP 403 API rate limit exceeded" >&2
          exit 1
        fi
        ;;
      permanent)
        echo "gh: HTTP 422 Unprocessable Entity" >&2
        exit 1
        ;;
      permission)
        echo "gh: HTTP 403 Resource not accessible by integration; SSO authorization required" >&2
        exit 1
        ;;
      *)
        echo "unknown simulated gh failure mode" >&2
        exit 91
        ;;
    esac
    printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  job-failure-retry)
    printf '[{"total_count":1,"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  *)
    echo "unknown simulated gh scenario" >&2
    exit 91
    ;;
esac
EOF
cat >"$tmp_dir/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$tmp_dir/gh" "$tmp_dir/sleep"

action_script="$(<"$POLL_SCRIPT")"
max_attempts="$(sed -n 's/^max_attempts=\([1-9][0-9]*\)$/\1/p' <<<"$action_script")"
[[ "$max_attempts" =~ ^[1-9][0-9]*$ ]] || {
  echo "required-gate action must define one positive max_attempts polling bound" >&2
  exit 1
}
# shellcheck disable=SC2016 # Reject literal GitHub expression syntax in executable shell.
if grep -Fq '${{' <<<"$action_script"; then
  echo "required-gate action run script must receive workflow context through env" >&2
  exit 1
fi
grep -Fq 'retry_error_text=""' <<<"$action_script" || {
  echo "required-gate action must reset retry error text for each attempt" >&2
  exit 1
}
# shellcheck disable=SC2016 # Assert literal shell parameter expansion syntax.
grep -Fq 'retry_error_text="${workflow_run_error}"' <<<"$action_script" || {
  echo "required-gate action must capture workflow-run API error text at the failure site" >&2
  exit 1
}
# shellcheck disable=SC2016 # Assert literal shell parameter expansion syntax.
grep -Fq 'retry_error_text="${job_error}"' <<<"$action_script" || {
  echo "required-gate action must capture retryable API error text at the failure site" >&2
  exit 1
}
# Assert the exact discovery-loop declaration so stray mentions cannot mask a
# removed active status from the actual API query.
grep -Fxq '  for active_workflow_status in in_progress queued requested waiting pending; do' <<<"$action_script" || {
  echo "required-gate action must query every supported active workflow status" >&2
  exit 1
}
retry_branch="$(awk '/^  if \[\[ "\$\{job_lookup_retryable\}" == "true" \]\]; then$/{capture=1} capture{print} capture && /^  fi$/{exit}' <<<"$action_script")"
# shellcheck disable=SC2016 # Assert literal shell parameter expansion syntax.
grep -Fq '[[ -z "${retry_error_text}" ]] || printf' <<<"$retry_branch" || {
  echo "required-gate action must print captured retry error text" >&2
  exit 1
}
if grep -Fq 'api_error_file' <<<"$retry_branch"; then
  echo "required-gate action retry branch must not reread the mutable API error file" >&2
  exit 1
fi
run_action() {
  local count_file="$1"
  local failure_mode="$2"
  local scenario="${3:-failure-retry}"
  local call_count_dir="${4:-}"
  GH_RETRY_COUNT_FILE="$count_file" \
  GH_FAILURE_MODE="$failure_mode" \
  GH_SCENARIO="$scenario" \
  GH_CALL_COUNT_DIR="$call_count_dir" \
  PATH="$tmp_dir:$PATH" \
  GITHUB_EVENT_NAME=pull_request \
  GITHUB_REPOSITORY=example/firemud \
  GITHUB_RUN_ID=999 \
  GH_TOKEN=test-token \
  BASE_SHA=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  HEAD_SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  PR_NUMBER=123 \
  REQUIRED_GATE_NAME='Validation Gate' \
  EXPECTED_WORKFLOW_NAME='CI — Validation' \
  EXPECTED_WORKFLOW_FILE=ci.yml \
  EXPECTED_WORKFLOW_PATH=.github/workflows/ci.yml \
  bash "$POLL_SCRIPT"
}

# Incomplete or inconsistent successful API responses cannot manufacture proof.
for coverage_mode in poll assess; do
  for coverage_case in truncated missing-total malformed-total overcount invalid-id empty-pages duplicate inconsistent-total; do
    coverage_scenario="check-coverage-$coverage_case"
    coverage_count="$tmp_dir/$coverage_mode-$coverage_scenario-count"
    coverage_output="$tmp_dir/$coverage_mode-$coverage_scenario-output"
    coverage_log="$tmp_dir/$coverage_mode-$coverage_scenario-log"
    if PRESERVATION_MODE="$coverage_mode" GITHUB_OUTPUT="$coverage_output" \
      run_action "$coverage_count" none "$coverage_scenario" >"$coverage_log" 2>&1; then
      echo "required-gate $coverage_mode accepted invalid check coverage: $coverage_case" >&2
      exit 1
    fi
    [[ "$(<"$coverage_count")" == 1 && ! -s "$coverage_output" ]] || {
      echo "invalid check coverage was retried or emitted an assessment: $coverage_case" >&2
      exit 1
    }
    grep -Fq 'malformed or incomplete check-run data' "$coverage_log" || {
      echo "invalid check coverage did not fail at the coverage boundary: $coverage_case" >&2
      exit 1
    }
  done
  coverage_output="$tmp_dir/$coverage_mode-complete-multi-output"
  PRESERVATION_MODE="$coverage_mode" GITHUB_OUTPUT="$coverage_output" \
    run_action "$tmp_dir/$coverage_mode-complete-multi-count" none check-coverage-multi
  [[ "$(<"$coverage_output")" == assessment=success ]] || {
    echo "complete multi-page check coverage lost successful proof" >&2
    exit 1
  }
done

sleep_until_poll_deadline_script="$(awk '
  /^sleep_until_poll_deadline\(\) \{$/ { capture=1 }
  capture {
    print
    if ($0 == "}") exit
  }
' <<<"$action_script")"
[[ -n "$sleep_until_poll_deadline_script" ]] || {
  echo "required-gate action must define its bounded polling sleep helper" >&2
  exit 1
}
deadline_clamp_output="$tmp_dir/deadline-clamp-output"
deadline_clamp_script="poll_interval_seconds=$poll_interval_seconds
poll_deadline=1
${sleep_until_poll_deadline_script}
sleep_until_poll_deadline"
PATH="$tmp_dir:$PATH" bash -euo pipefail -c "$deadline_clamp_script" >"$deadline_clamp_output" 2>&1
grep -Fxq 'Polling delay bounded to 1s by the deadline.' "$deadline_clamp_output" || {
  echo "required-gate action did not report a polling delay clamped by its deadline" >&2
  exit 1
}
deadline_unclamped_output="$tmp_dir/deadline-unclamped-output"
deadline_unclamped_script="poll_interval_seconds=$poll_interval_seconds
poll_deadline=30
${sleep_until_poll_deadline_script}
sleep_until_poll_deadline"
PATH="$tmp_dir:$PATH" bash -euo pipefail -c "$deadline_unclamped_script" >"$deadline_unclamped_output" 2>&1
if grep -Fq 'Polling delay bounded' "$deadline_unclamped_output"; then
  echo "required-gate action logged a polling delay that was not clamped" >&2
  exit 1
fi

refresh_active_workflow_state_script="$(awk '
  /^refresh_active_workflow_state\(\) \{$/ { capture=1 }
  capture {
    print
    if ($0 == "}") exit
  }
' <<<"$action_script")"
[[ -n "$refresh_active_workflow_state_script" ]] || {
  echo "required-gate action must define active-workflow state refresh" >&2
  exit 1
}
late_deadline_discovery_script="poll_interval_seconds=$poll_interval_seconds
attempt=3
poll_attempt_limit=$max_attempts
substantive_wait_extended=false
last_uncertain_substantive_workflow=false
active_substantive_workflow=false
uncertain_substantive_workflow=false
discovery_count=0
find_active_substantive_workflow() { discovery_count=\$((discovery_count + 1)); }
poll_deadline=\$((SECONDS + 2 * poll_interval_seconds))
${refresh_active_workflow_state_script}
refresh_active_workflow_state
[[ \$discovery_count == 1 ]]"
PATH="$tmp_dir:$PATH" bash -euo pipefail -c "$late_deadline_discovery_script" || {
  echo "required-gate action did not rediscover when the wall-clock deadline approached before the final attempt" >&2
  exit 1
}

run_guard_action() {
  local output_file="$1"
  local event_name="$2"
  local head_sha="$3"
  local count_file="$4"
  GH_RETRY_COUNT_FILE="$count_file" \
  PATH="$tmp_dir:$PATH" \
  GITHUB_EVENT_NAME="$event_name" \
  GITHUB_REPOSITORY=example/firemud \
  GITHUB_RUN_ID=999 \
  GH_TOKEN=test-token \
  BASE_SHA=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  HEAD_SHA="$head_sha" \
  PR_NUMBER=123 \
  REQUIRED_GATE_NAME='Validation Gate' \
  EXPECTED_WORKFLOW_NAME='CI — Validation' \
  EXPECTED_WORKFLOW_FILE=ci.yml \
  EXPECTED_WORKFLOW_PATH=.github/workflows/ci.yml \
  bash "$POLL_SCRIPT" >"$output_file" 2>&1
}

for failure_mode in transient network rate-limit; do
  count_file="$tmp_dir/count-${failure_mode}"
  run_action "$count_file" "$failure_mode" failure-retry
  [[ "$(<"$count_file")" == "2" ]] || {
    echo "required-gate action did not retry simulated ${failure_mode} failure" >&2
    exit 1
  }
done

job_failure_count="$tmp_dir/count-job-failure-retry"
job_failure_output="$tmp_dir/job-failure-retry-output"
run_action "$job_failure_count" none job-failure-retry >"$job_failure_output" 2>&1
[[ "$(<"$job_failure_count")" == "2" ]] || {
  echo "required-gate action did not retry a retryable job metadata lookup failure" >&2
  exit 1
}
grep -Fq 'Retryable GitHub API failure during job API lookup' "$job_failure_output" || {
  echo "required-gate action did not identify a retryable job API lookup" >&2
  exit 1
}

for failure_mode in permanent permission; do
  permanent_output="$tmp_dir/${failure_mode}-output"
  set +e
  run_action "$tmp_dir/count-${failure_mode}" "$failure_mode" >"$permanent_output" 2>&1
  permanent_status=$?
  set -e
  [[ "$permanent_status" -ne 0 ]] || {
    echo "required-gate action retried or accepted a permanent ${failure_mode} gh api failure" >&2
    exit 1
  }
  [[ "$(<"$tmp_dir/count-${failure_mode}")" == "1" ]] || {
    echo "required-gate action polled again after a permanent ${failure_mode} gh api failure" >&2
    exit 1
  }
  grep -Fq 'Permanent GitHub API/configuration failure' "$permanent_output" || {
    echo "required-gate action did not report the permanent ${failure_mode} gh api failure" >&2
    exit 1
  }
done

workflow_identity_output="$tmp_dir/workflow-identity-output"
set +e
run_action "$tmp_dir/count-workflow-identity-mismatch" none workflow-identity-mismatch >"$workflow_identity_output" 2>&1
workflow_identity_status=$?
set -e
[[ "$workflow_identity_status" -ne 0 ]] || {
  echo "required-gate action accepted a mismatched initial workflow identity" >&2
  exit 1
}
[[ ! -e "$tmp_dir/count-workflow-identity-mismatch" ]] || {
  echo "required-gate action polled check runs after an initial workflow identity mismatch" >&2
  exit 1
}
grep -Fxq 'GitHub API returned malformed expected workflow identity; refusing to preserve.' "$workflow_identity_output" || {
  echo "required-gate action did not report the exact initial workflow identity mismatch message" >&2
  exit 1
}

duplicate_run_identity_output="$tmp_dir/duplicate-run-identity-output"
duplicate_run_call_counts="$tmp_dir/duplicate-run-call-counts"
mkdir -p "$duplicate_run_call_counts"
set +e
run_action "$tmp_dir/count-duplicate-run-identity" none duplicate-invalid-run-identity "$duplicate_run_call_counts" >"$duplicate_run_identity_output" 2>&1
duplicate_run_identity_status=$?
set -e
[[ "$duplicate_run_identity_status" -ne 0 ]] || {
  echo "required-gate action accepted duplicate candidates with invalid workflow-run identity" >&2
  exit 1
}
[[ "$(<"$duplicate_run_call_counts/runs-200")" == "1" ]] || {
  echo "required-gate action refetched duplicate candidates' immutable workflow-run metadata" >&2
  exit 1
}
grep -Fq 'Ambiguous prior' "$duplicate_run_identity_output" || {
  echo "required-gate action did not fail closed for duplicate candidates with invalid workflow-run identity" >&2
  exit 1
}

no_local_workflow_file_count="$tmp_dir/count-no-local-workflow-file"
run_action "$no_local_workflow_file_count" none no-local-workflow-file
[[ "$(<"$no_local_workflow_file_count")" == "1" ]] || {
  echo "required-gate action rejected valid API identity without a local workflow file" >&2
  exit 1
}

successful_predecessor_count="$tmp_dir/count-successful-predecessor-preferred"
run_action "$successful_predecessor_count" none latest-pending-preferred
[[ "$(<"$successful_predecessor_count")" == "1" ]] || {
  echo "required-gate action did not preserve an existing success while a concurrent run was pending" >&2
  exit 1
}

missing_created_at_count="$tmp_dir/count-missing-created-at"
run_action "$missing_created_at_count" none missing-created-at
[[ "$(<"$missing_created_at_count")" == "1" ]] || {
  echo "required-gate action rejected a valid check-run without created_at while preserving an existing success" >&2
  exit 1
}

pending_count="$tmp_dir/count-pending-predecessor"
pending_output="$tmp_dir/pending-predecessor-output"
run_action "$pending_count" none pending-predecessor >"$pending_output" 2>&1
[[ "$(<"$pending_count")" == "2" ]] || {
  echo "required-gate action did not poll the relevant prior run while it was finishing" >&2
  exit 1
}
grep -Fq 'verified substantive Validation Gate is still pending' "$pending_output" || {
  echo "required-gate action did not identify the pending substantive gate" >&2
  exit 1
}

queued_null_started_at_count="$tmp_dir/count-queued-null-started-at"
run_action "$queued_null_started_at_count" none queued-null-started-at
[[ "$(<"$queued_null_started_at_count")" == "2" ]] || {
  echo "required-gate action did not poll a queued run with a null started_at until completion" >&2
  exit 1
}

delayed_count="$tmp_dir/count-delayed-predecessor"
run_action "$delayed_count" none delayed-predecessor
[[ "$(<"$delayed_count")" == "3" ]] || {
  echo "required-gate action did not tolerate check-run publication delay" >&2
  exit 1
}

delayed_after_19_minutes_count="$tmp_dir/count-delayed-predecessor-after-19-minutes"
run_action "$delayed_after_19_minutes_count" none delayed-predecessor-after-19-minutes
[[ "$(<"$delayed_after_19_minutes_count")" == "78" ]] || {
  echo "required-gate action did not preserve a substantive predecessor published after 19 minutes" >&2
  exit 1
}

slow_substantive_output="$tmp_dir/slow-substantive-output"
slow_substantive_count="$tmp_dir/count-slow-substantive"
slow_substantive_api_counts="$tmp_dir/slow-substantive-api-counts"
mkdir -p "$slow_substantive_api_counts"
run_action "$slow_substantive_count" none active-workflow-delayed-gate "$slow_substantive_api_counts" >"$slow_substantive_output" 2>&1
(( 97 * poll_interval_seconds > poll_timeout_seconds )) || {
  echo "slow-substantive fixture must exceed the former missing-gate polling budget" >&2
  exit 1
}
[[ "$(<"$slow_substantive_count")" == "98" ]] || {
  echo "required-gate action did not wait past its short budget for an active substantive workflow" >&2
  exit 1
}
grep -Fq 'substantive workflow is active' "$slow_substantive_output" || {
  echo "required-gate action did not distinguish active substantive work from a missing predecessor" >&2
  exit 1
}
for active_status in in_progress queued requested waiting pending; do
  [[ "$(<"$slow_substantive_api_counts/active-$active_status")" == "1" ]] || {
    echo "required-gate action rediscovered $active_status after extending for a verified active run" >&2
    exit 1
  }
done
[[ "$(<"$slow_substantive_api_counts/run-jobs-100")" == "1" ]] || {
  echo "required-gate action did not reuse completed change-detector evidence for the active run" >&2
  exit 1
}

late_active_count="$tmp_dir/count-late-active-arrival"
late_active_api_counts="$tmp_dir/late-active-api-counts"
mkdir -p "$late_active_api_counts"
run_action "$late_active_count" none active-workflow-late-arrival "$late_active_api_counts" >"$tmp_dir/late-active-output" 2>&1
[[ "$(<"$late_active_count")" == "93" ]] || {
  echo "required-gate action missed an active substantive workflow appearing on the final short-bound discovery" >&2
  exit 1
}
for active_status in in_progress queued requested waiting pending; do
  [[ "$(<"$late_active_api_counts/active-$active_status")" == "24" ]] || {
    echo "required-gate action did not run active-workflow discovery every fourth poll plus the final short-bound poll" >&2
    exit 1
  }
done
[[ "$(<"$late_active_api_counts/run-jobs-100")" == "1" ]] || {
  echo "required-gate action did not fetch completed change-detector evidence once for the late active run" >&2
  exit 1
}

for absent_scenario in active-workflow-wrong-base active-workflow-metadata-only active-workflow-mismatched-tuple; do
  absent_output="$tmp_dir/${absent_scenario}-output"
  absent_count="$tmp_dir/count-${absent_scenario}"
  set +e
  run_action "$absent_count" none "$absent_scenario" >"$absent_output" 2>&1
  absent_status=$?
  set -e
  [[ "$absent_status" -ne 0 && "$(<"$absent_count")" == "$max_attempts" ]] || {
    echo "required-gate action extended or accepted a $absent_scenario predecessor (status=$absent_status attempts=$(<"$absent_count"))" >&2
    cat "$absent_output" >&2
    exit 1
  }
done

malformed_active_output="$tmp_dir/malformed-active-output"
set +e
run_action "$tmp_dir/count-malformed-active" none active-workflow-malformed-list >"$malformed_active_output" 2>&1
malformed_active_status=$?
set -e
[[ "$malformed_active_status" -ne 0 && "$(<"$tmp_dir/count-malformed-active")" == "1" ]] || {
  echo "required-gate action did not fail closed on malformed active-workflow data" >&2
  exit 1
}

alternate_pending_count="$tmp_dir/count-alternate-pending"
run_action "$alternate_pending_count" none alternate-pending
[[ "$(<"$alternate_pending_count")" == "4" ]] || {
  echo "required-gate action did not treat all GitHub pending statuses as pending" >&2
  exit 1
}

pending_step_count="$tmp_dir/count-pending-preservation-step"
run_action "$pending_step_count" none pending-preservation-step-not-concluded
[[ "$(<"$pending_step_count")" == "4" ]] || {
  echo "required-gate action did not treat pending preservation jobs without a concluded step as pending" >&2
  exit 1
}

pending_discovery_count="$tmp_dir/count-pending-gate-discovery-throttle"
pending_discovery_api_counts="$tmp_dir/pending-gate-discovery-api-counts"
mkdir -p "$pending_discovery_api_counts"
run_action "$pending_discovery_count" none pending-gate-discovery-throttle "$pending_discovery_api_counts"
[[ "$(<"$pending_discovery_count")" == "6" ]] || {
  echo "required-gate action did not continue polling a pending gate through completion" >&2
  exit 1
}
for active_status in in_progress queued requested waiting pending; do
  [[ "$(<"$pending_discovery_api_counts/active-$active_status")" == "2" ]] || {
    echo "required-gate action did not throttle active-workflow discovery for a pending gate" >&2
    exit 1
  }
done

completed_step_lag_count="$tmp_dir/count-completed-preservation-step-lag"
completed_step_lag_output="$tmp_dir/completed-preservation-step-lag-output"
run_action "$completed_step_lag_count" none completed-preservation-step-lag >"$completed_step_lag_output" 2>&1
[[ "$(<"$completed_step_lag_count")" == "2" ]] || {
  echo "required-gate action did not retry a completed check while its preservation step snapshot lagged" >&2
  exit 1
}
grep -Fq 'Retrying preservation-step snapshot refresh' "$completed_step_lag_output" || {
  echo "required-gate action did not identify a preservation-step snapshot refresh" >&2
  exit 1
}
if grep -Fq 'GitHub API failure' "$completed_step_lag_output"; then
  echo "required-gate action mislabeled a preservation-step snapshot refresh as an API failure" >&2
  exit 1
fi

persistent_step_lag_count="$tmp_dir/count-completed-preservation-step-persistent-lag"
persistent_step_lag_output="$tmp_dir/completed-preservation-step-persistent-lag-output"
set +e
run_action "$persistent_step_lag_count" none completed-preservation-step-persistent-lag >"$persistent_step_lag_output" 2>&1
persistent_step_lag_status=$?
set -e
[[ "$persistent_step_lag_status" -ne 0 ]] || {
  echo "required-gate action allowed an unresolved authoritative candidate to mask an older success" >&2
  exit 1
}
[[ "$(<"$persistent_step_lag_count")" == "9" ]] || {
  echo "required-gate action did not stop refreshing an unresolved authoritative candidate after its bounded attempts" >&2
  exit 1
}
grep -Fxq 'Ambiguous prior Validation Gate run metadata; refusing to preserve.' "$persistent_step_lag_output" || {
  echo "required-gate action did not fail closed for an unresolved authoritative candidate" >&2
  exit 1
}

for non_authoritative_scenario in completed-cancelled-missing-step completed-skipped-missing-step completed-stale-missing-step; do
  non_authoritative_count="$tmp_dir/count-${non_authoritative_scenario}"
  non_authoritative_output="$tmp_dir/${non_authoritative_scenario}-output"
  run_action "$non_authoritative_count" none "$non_authoritative_scenario" >"$non_authoritative_output" 2>&1
  [[ "$(<"$non_authoritative_count")" == "1" ]] || {
    echo "required-gate action did not immediately discard the unresolved ${non_authoritative_scenario} candidate" >&2
    exit 1
  }
  if grep -Fq 'Retrying preservation-step snapshot refresh' "$non_authoritative_output"; then
    echo "required-gate action refreshed a non-authoritative ${non_authoritative_scenario} candidate" >&2
    exit 1
  fi
done

cache_call_counts="$tmp_dir/cache-call-counts"
mkdir -p "$cache_call_counts"
run_action "$tmp_dir/count-cache-metadata" none pending-preservation-step-not-concluded "$cache_call_counts"
[[ "$(<"$cache_call_counts/runs-100")" == "1" ]] || {
  echo "required-gate action refetched immutable workflow-run metadata across polls" >&2
  exit 1
}
[[ "$(<"$cache_call_counts/jobs-100")" == "4" ]] || {
  echo "required-gate action did not refresh the pending job and final job metadata" >&2
  exit 1
}

terminal_cache_counts="$tmp_dir/terminal-cache-call-counts"
mkdir -p "$terminal_cache_counts"
run_action "$tmp_dir/count-terminal-cache" none cache-metadata-across-polls "$terminal_cache_counts"
[[ "$(<"$terminal_cache_counts/runs-100")" == "1" && "$(<"$terminal_cache_counts/jobs-100")" == "1" ]] || {
  echo "required-gate action refetched a verified terminal metadata job across polls" >&2
  exit 1
}
[[ "$(<"$terminal_cache_counts/runs-101")" == "1" && "$(<"$terminal_cache_counts/jobs-101")" == "2" ]] || {
  echo "required-gate action did not refresh the pending job until it completed" >&2
  exit 1
}

pending_step_failure_output="$tmp_dir/pending-step-failure-output"
set +e
run_action "$tmp_dir/count-pending-step-failure" none pending-missing-step-with-failed-substantive >"$pending_step_failure_output" 2>&1
pending_step_failure_status=$?
set -e
[[ "$pending_step_failure_status" -ne 0 ]] || {
  echo "required-gate action allowed a pending preservation job to mask a substantive failure" >&2
  exit 1
}
[[ "$(<"$tmp_dir/count-pending-step-failure")" == "1" ]] || {
  echo "required-gate action retried after selecting the substantive failure beside a pending preservation job" >&2
  exit 1
}
grep -Fq 'concluded failure' "$pending_step_failure_output" || {
  echo "required-gate action did not retain substantive failure authority beside a pending preservation job" >&2
  exit 1
}

(( max_attempts * poll_interval_seconds >= poll_timeout_seconds )) || {
  echo "required-gate action attempt bound must cover its polling timeout" >&2
  exit 1
}
active_max_attempts=$((active_poll_timeout_seconds / poll_interval_seconds))
timeout_output="$tmp_dir/timeout-output"
set +e
run_action "$tmp_dir/count-timeout" none timeout-pending >"$timeout_output" 2>&1
timeout_status=$?
set -e
[[ "$timeout_status" -ne 0 ]] || {
  echo "required-gate action accepted a predecessor that never completed" >&2
  exit 1
}
[[ "$(<"$tmp_dir/count-timeout")" == "$active_max_attempts" ]] || {
  echo "required-gate action did not retain its ${active_max_attempts}-attempt active-predecessor polling limit" >&2
  exit 1
}
grep -Fq 'Timed out waiting for the relevant prior' "$timeout_output" || {
  echo "required-gate action did not report its bounded polling timeout" >&2
  exit 1
}

unverified_pending_output="$tmp_dir/unverified-pending-output"
set +e
run_action "$tmp_dir/count-unverified-pending" none unverified-pending-gate >"$unverified_pending_output" 2>&1
unverified_pending_status=$?
set -e
[[ "$unverified_pending_status" -ne 0 && "$(<"$tmp_dir/count-unverified-pending")" == "$max_attempts" ]] || {
  echo "required-gate action extended or accepted an unverified pending metadata gate" >&2
  exit 1
}
grep -Fq 'substantive identity is not yet verified' "$unverified_pending_output" || {
  echo "required-gate action did not distinguish an unverified pending gate" >&2
  exit 1
}

self_run_count="$tmp_dir/count-self-run"
run_action "$self_run_count" none self-run-excluded
[[ "$(<"$self_run_count")" == "1" ]] || {
  echo "required-gate action did not exclude its current workflow run" >&2
  exit 1
}

failed_prior_output="$tmp_dir/failed-prior-output"
set +e
run_action "$tmp_dir/count-failed-prior" none failed-predecessor >"$failed_prior_output" 2>&1
failed_prior_status=$?
set -e
[[ "$failed_prior_status" -ne 0 ]] || {
  echo "required-gate action accepted a failed prior gate" >&2
  exit 1
}
[[ "$(<"$tmp_dir/count-failed-prior")" == "1" ]] || {
  echo "required-gate action retried a completed failed prior gate" >&2
  exit 1
}
grep -Fq 'concluded failure' "$failed_prior_output" || {
  echo "required-gate action did not report the failed prior conclusion" >&2
  exit 1
}

newer_failure_output="$tmp_dir/newer-failure-output"
set +e
run_action "$tmp_dir/count-newer-failure" none newer-failure-over-success >"$newer_failure_output" 2>&1
newer_failure_status=$?
set -e
[[ "$newer_failure_status" -ne 0 ]] || {
  echo "required-gate action allowed an older success to mask a newer completed failure" >&2
  exit 1
}
[[ "$(<"$tmp_dir/count-newer-failure")" == "1" ]] || {
  echo "required-gate action retried after selecting the newest authoritative failure" >&2
  exit 1
}
grep -Fq 'concluded failure' "$newer_failure_output" || {
  echo "required-gate action did not report the newest authoritative failure" >&2
  exit 1
}

same_timestamp_output="$tmp_dir/same-timestamp-output"
set +e
run_action "$tmp_dir/count-same-timestamp" none same-timestamp-newer-failure >"$same_timestamp_output" 2>&1
same_timestamp_status=$?
set -e
[[ "$same_timestamp_status" -ne 0 ]] || {
  echo "required-gate action allowed an older same-timestamp success to mask a newer failure" >&2
  exit 1
}
[[ "$(<"$tmp_dir/count-same-timestamp")" == "1" ]] || {
  echo "required-gate action retried after selecting the deterministic same-timestamp failure" >&2
  exit 1
}
grep -Fq 'concluded failure' "$same_timestamp_output" || {
  echo "required-gate action did not report the deterministic same-timestamp failure" >&2
  exit 1
}

fork_head_count="$tmp_dir/count-fork-head"
run_action "$fork_head_count" none fork-head-same-sha
[[ "$(<"$fork_head_count")" == "1" ]] || {
  echo "required-gate action rejected a valid fork pull request run owned by the base repository" >&2
  exit 1
}

fork_empty_count="$tmp_dir/count-fork-empty-association"
run_action "$fork_empty_count" none fork-empty-association
[[ "$(<"$fork_empty_count")" == "1" ]] || {
  echo "required-gate action rejected a fork pull request run with an empty association array and canonical title" >&2
  exit 1
}

dynamic_run_count="$tmp_dir/count-dynamic-run-name"
run_action "$dynamic_run_count" none dynamic-run-name
[[ "$(<"$dynamic_run_count")" == "1" ]] || {
  echo "required-gate action rejected a dynamic workflow run/job name with a populated PR association" >&2
  exit 1
}

dynamic_fork_empty_count="$tmp_dir/count-dynamic-run-name-fork-empty"
run_action "$dynamic_fork_empty_count" none dynamic-run-name-fork-empty
[[ "$(<"$dynamic_fork_empty_count")" == "1" ]] || {
  echo "required-gate action rejected a dynamic workflow run/job name with an empty fork association and canonical title" >&2
  exit 1
}

run_path_ref_count="$tmp_dir/count-run-path-ref-suffix"
run_action "$run_path_ref_count" none run-path-ref-suffix
[[ "$(<"$run_path_ref_count")" == "1" ]] || {
  echo "required-gate action rejected a valid workflow-run path ref suffix" >&2
  exit 1
}

for run_path_ref_scenario in run-path-ref-suffix-plus run-path-ref-suffix-at; do
  run_path_ref_count="$tmp_dir/count-${run_path_ref_scenario}"
  run_action "$run_path_ref_count" none "$run_path_ref_scenario"
  [[ "$(<"$run_path_ref_count")" == "1" ]] || {
    echo "required-gate action rejected a valid ${run_path_ref_scenario} workflow-run path ref suffix" >&2
    exit 1
  }
done

for details_url_scenario in details-url-query details-url-fragment; do
  details_url_count="$tmp_dir/count-${details_url_scenario}"
  run_action "$details_url_count" none "$details_url_scenario"
  [[ "$(<"$details_url_count")" == "1" ]] || {
    echo "required-gate action rejected a valid ${details_url_scenario} check-run URL" >&2
    exit 1
  }
done

valid_new_tuple_count="$tmp_dir/count-valid-new-tuple"
run_action "$valid_new_tuple_count" none valid-new-tuple
[[ "$(<"$valid_new_tuple_count")" == "1" ]] || {
  echo "required-gate action rejected a valid populated current workflow tuple" >&2
  exit 1
}

stale_then_current_tuple_count="$tmp_dir/count-stale-then-current-tuple"
run_action "$stale_then_current_tuple_count" none stale-then-current-tuple
[[ "$(<"$stale_then_current_tuple_count")" == "1" ]] || {
  echo "required-gate action did not skip a stale populated tuple before accepting the valid current tuple" >&2
  exit 1
}

for stale_scenario in populated-wrong-base populated-wrong-head; do
  stale_output="$tmp_dir/${stale_scenario}-output"
  stale_count="$tmp_dir/count-${stale_scenario}"
  set +e
  run_action "$stale_count" none "$stale_scenario" >"$stale_output" 2>&1
  stale_status=$?
  set -e
  [[ "$stale_status" -ne 0 && "$(<"$stale_count")" == "$max_attempts" ]] || {
    echo "required-gate action did not skip stale populated ${stale_scenario} tuple (status=$stale_status attempts=$(<"$stale_count"))" >&2
    cat "$stale_output" >&2
    exit 1
  }
  if grep -Fq 'Ambiguous prior' "$stale_output"; then
    echo "required-gate action treated stale populated ${stale_scenario} tuple as ambiguous" >&2
    exit 1
  fi
done

for malformed_scenario in cross-workflow-same-name malformed-run-path-ref-suffix malformed-details-url-suffix wrong-run-repository wrong-run-name wrong-job-workflow-name unknown-app unknown-check-name invalid-job-id missing-job-id unsupported-status missing-timestamp empty-wrong-pr empty-wrong-base empty-wrong-head empty-wrong-title empty-malformed-association other-pr-association; do
  malformed_output="$tmp_dir/${malformed_scenario}-output"
  set +e
  run_action "$tmp_dir/count-${malformed_scenario}" none "$malformed_scenario" >"$malformed_output" 2>&1
  malformed_status=$?
  set -e
  [[ "$malformed_status" -ne 0 ]] || {
    echo "required-gate action accepted malformed ${malformed_scenario} metadata" >&2
    exit 1
  }
  [[ "$(<"$tmp_dir/count-${malformed_scenario}")" == "1" ]] || {
    echo "required-gate action retried after malformed ${malformed_scenario} metadata" >&2
    exit 1
  }
  grep -Fq 'Ambiguous prior' "$malformed_output" || {
    echo "required-gate action did not fail closed for malformed ${malformed_scenario} metadata" >&2
    exit 1
  }
done

no_prior_output="$tmp_dir/no-prior-output"
set +e
run_action "$tmp_dir/count-no-prior" none no-prior >"$no_prior_output" 2>&1
no_prior_status=$?
set -e
[[ "$no_prior_status" -ne 0 ]] || {
  echo "required-gate action accepted the absence of a prior completed run" >&2
  exit 1
}
[[ "$(<"$tmp_dir/count-no-prior")" == "$max_attempts" ]] || {
  echo "required-gate action did not apply its bounded polling limit when no prior run appeared" >&2
  exit 1
}
grep -Fq 'Timed out waiting for a prior' "$no_prior_output" || {
  echo "required-gate action did not clearly report the missing prior-run timeout" >&2
  exit 1
}
grep -Fq 'No attributable substantive workflow is visible yet' "$no_prior_output" || {
  echo "required-gate action did not distinguish an absent workflow from a delayed gate" >&2
  exit 1
}

multiple_metadata_output="$tmp_dir/multiple-metadata-output"
set +e
run_action "$tmp_dir/count-multiple-metadata" none multiple-metadata >"$multiple_metadata_output" 2>&1
multiple_metadata_status=$?
set -e
[[ "$multiple_metadata_status" -ne 0 ]] || {
  echo "required-gate action accepted metadata-preservation runs without a prior full gate" >&2
  exit 1
}
[[ "$(<"$tmp_dir/count-multiple-metadata")" == "$max_attempts" ]] || {
  echo "required-gate action did not keep polling when only metadata-preservation runs were visible" >&2
  exit 1
}
grep -Fq 'Timed out waiting for a prior' "$multiple_metadata_output" || {
  echo "required-gate action did not report the missing prior full gate with multiple metadata runs" >&2
  exit 1
}

context_output="$tmp_dir/context-output"
set +e
run_guard_action "$context_output" push aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "$tmp_dir/count-context"
context_status=$?
set -e
[[ "$context_status" -ne 0 ]] || {
  echo "required-gate action accepted a non-PR context" >&2
  exit 1
}
[[ ! -e "$tmp_dir/count-context" ]] || {
  echo "required-gate action polled before rejecting a non-PR context" >&2
  exit 1
}
grep -Fxq 'Required-gate preservation requires pull_request context; refusing to poll.' "$context_output" || {
  echo "required-gate action did not report the exact non-PR guard message" >&2
  exit 1
}

head_output="$tmp_dir/head-output"
set +e
run_guard_action "$head_output" pull_request "   " "$tmp_dir/count-empty-head"
head_status=$?
set -e
[[ "$head_status" -ne 0 ]] || {
  echo "required-gate action accepted an empty pull request head SHA" >&2
  exit 1
}
[[ ! -e "$tmp_dir/count-empty-head" ]] || {
  echo "required-gate action polled before rejecting an empty head SHA" >&2
  exit 1
}
grep -Fxq 'Required-gate preservation requires a valid pull request head SHA; refusing to poll.' "$head_output" || {
  echo "required-gate action did not report the exact empty-head guard message" >&2
  exit 1
}

# Exercise one assessment through the same predecessor fixtures as polling.
for assessment_scenario in no-prior pending-predecessor queued-null-started-at multiple-metadata no-local-workflow-file fork-empty-association; do
  assessment_file="$tmp_dir/assessment-$assessment_scenario"
  assessment_count="$tmp_dir/assessment-count-$assessment_scenario"
  PRESERVATION_MODE=assess GITHUB_OUTPUT="$assessment_file" \
    run_action "$assessment_count" none "$assessment_scenario"
  [[ "$(<"$assessment_count")" == 1 ]] || {
    echo "one assessment polled again for $assessment_scenario" >&2
    exit 1
  }
  case "$assessment_scenario" in
    no-local-workflow-file|fork-empty-association) expected_assessment=success ;;
    *) expected_assessment=dependency-deferred ;;
  esac
  [[ "$(<"$assessment_file")" == "assessment=$expected_assessment" ]] || {
    echo "incorrect one-assessment result for $assessment_scenario" >&2
    exit 1
  }
done
for assessment_scenario in failed-predecessor newer-failure-over-success wrong-run-repository empty-wrong-base; do
  assessment_file="$tmp_dir/rejected-assessment-$assessment_scenario"
  if PRESERVATION_MODE=assess GITHUB_OUTPUT="$assessment_file" \
    run_action "$tmp_dir/rejected-assessment-count-$assessment_scenario" none "$assessment_scenario"; then
    echo "one assessment accepted invalid original proof: $assessment_scenario" >&2
    exit 1
  fi
  [[ ! -s "$assessment_file" ]] || {
    echo "invalid original proof was converted into dependency deferral" >&2
    exit 1
  }
done
if PRESERVATION_MODE=assess GITHUB_OUTPUT="$tmp_dir/api-assessment" \
  run_action "$tmp_dir/api-assessment-count" transient failure-retry; then
  echo "one assessment accepted an API failure" >&2
  exit 1
fi
[[ "$(<"$tmp_dir/api-assessment-count")" == 1 ]] || {
  echo "one assessment retried an API failure" >&2
  exit 1
}

RESOLVER="$ROOT_DIR/.github/actions/preserve-required-gate/resolve-deferred-gate.sh"
python3 - "$ROOT_DIR" <<'PY'
from pathlib import Path
import sys
import yaml

root = Path(sys.argv[1])
workflow = yaml.load((root / ".github/workflows/resolve-required-gates.yml").read_text(), Loader=yaml.BaseLoader)
action = yaml.load((root / ".github/actions/preserve-required-gate/action.yml").read_text(), Loader=yaml.BaseLoader)
assert action["inputs"]["assessment-mode"]["default"] == "poll"
assert action["outputs"]["assessment"]["value"] == "${{ steps.preserve.outputs.assessment }}"
assert workflow["on"]["workflow_run"]["types"] == ["completed"]
assert set(workflow["on"]["workflow_run"]["workflows"]) == {
    "CI — Validation", "Security Gate", "CodeQL Analysis", "License Gate", "PR Smoke Gate",
}
assert workflow["concurrency"] == {
    "group": "required-gate-resolution-${{ github.event.workflow_run.workflow_id }}-${{ github.event.workflow_run.head_sha }}",
    "cancel-in-progress": "false",
}
job = workflow["jobs"]["resolve"]
import copy
import json

def assert_isolated_resolution(candidate):
    assert candidate["if"] == "${{ github.event.workflow_run.head_branch == 'codex/required-gate-native-rehearsal-v2' && github.event.workflow_run.event == 'pull_request' }}"
    assert json.loads(candidate["env"]["REQUIRED_GATE_PROOF_ALLOWLIST"]) == [
        {"pr": 3081, "head_branch": "codex/required-gate-native-rehearsal-v2"},
    ]

assert_isolated_resolution(job)
unsafe_scopes = [
    {"if": "${{ github.event.workflow_run.event == 'pull_request' }}"},
    {"if": "${{ true }}"},
    {"env": {"REQUIRED_GATE_PROOF_ALLOWLIST": "[]"}},
    {"env": {"REQUIRED_GATE_PROOF_ALLOWLIST": '[{"pr":3082,"head_branch":"codex/required-gate-native-rehearsal-v2"}]'}},
    {"env": {"REQUIRED_GATE_PROOF_ALLOWLIST": '[{"pr":"3081","head_branch":"codex/required-gate-native-rehearsal-v2"}]'}},
    {"env": {"REQUIRED_GATE_PROOF_ALLOWLIST": '[{"pr":3081,"head_branch":"ordinary-work"}]'}},
]
for override in unsafe_scopes:
    unsafe = copy.deepcopy(job)
    unsafe.update(override)
    try:
        assert_isolated_resolution(unsafe)
    except AssertionError:
        pass
    else:
        raise AssertionError(f"unsafe resolver proof scope accepted: {override}")
assert job["permissions"] == {"contents": "read", "checks": "read", "pull-requests": "read", "actions": "write"}
checkouts = [step for step in job["steps"] if step.get("uses", "").startswith("actions/checkout@")]
assert len(checkouts) == 1 and checkouts[0]["with"] == {"ref": "${{ github.sha }}", "persist-credentials": "false"}
preflight = next(step for step in job["steps"] if step["name"] == "Preflight deferred gate resolution")
admission = next(step for step in job["steps"] if step["name"] == "Admit deferred gate reruns")
assert preflight["id"] == "preflight" and preflight["run"].endswith(" preflight")
assert admission["id"] == "admission"
assert admission["if"] == "${{ steps.preflight.outputs.eligible == 'true' }}" and admission["run"].endswith(" admit")
confirmation = job["steps"][job["steps"].index(admission) + 1]
assert confirmation["name"] == "Confirm admitted deferred gate jobs ${{ steps.admission.outputs.accepted_job_ids }}"
assert confirmation["if"] == "${{ steps.admission.outcome == 'success' }}" and confirmation["run"] == ":"
PY

mkdir "$tmp_dir/resolver-bin"
cat >"$tmp_dir/resolver-bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${GITHUB_EVENT_NAME:-}" == pull_request ]]; then
  exec "${MOCK_ORIGINAL_GH:?}" "$@"
fi
python3 - "$@" <<'PY'
import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
state_path = Path(os.environ["RESOLVER_STATE"])
state = json.loads(state_path.read_text())
method = args[args.index("--method") + 1]
endpoint = next(arg for arg in args if arg.startswith("/repos/"))
scenario = state["scenario"]
head, base = "a" * 40, "b" * 40
title = f"CI — Validation pr-123 base-{base} head-{head}"
repository = "other-owner/firemud" if scenario == "fork-empty" else "example/firemud"
# The existing fork predecessor fixture is run 200. Keep its resolver target
# distinct so the shared selector correctly excludes only the target itself.
target_id = 300 if scenario == "fork-empty" else 200
admission_scenario = scenario.startswith("admission-") or scenario == "partial-admission" or scenario.startswith("window-")
if scenario == "admission-distinct" and state.get("posts", 0):
    target_id = 201
current_id = int(os.environ["GITHUB_RUN_ID"])
source_id = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())["workflow_run"]["id"]
queue_scenario = scenario.startswith(("queued-", "pending-"))
equal_time_scenario = queue_scenario and "-equal" in scenario
rejected_later = scenario in {"pending-callback-started", "pending-callback-malformed", "pending-callback-attempt", "pending-callback-concluded"}
result = None
exit_status = 0
state.setdefault("calls", []).append(f"{method} {endpoint}")

def run(run_id):
    return {"id": run_id, "workflow_id": 42, "name": "CI — Validation", "path": ".github/workflows/ci.yml",
            "created_at": "2026-07-30T01:00:00Z" if run_id == 100 else "2026-07-30T02:00:00Z",
            "display_title": title, "head_sha": head, "head_branch": "proof-branch",
            "repository": {"full_name": "example/firemud"}, "head_repository": {"full_name": repository},
            "event": "pull_request", "status": "completed", "conclusion": "failure" if run_id in {target_id, 200, 201} else "success",
            "run_attempt": state.get("attempt", 1), "pull_requests": [] if scenario == "fork-empty" else [{"number": 123}]}

def resolver_run(run_id):
    source = source_id if run_id == current_id else 100
    status = "in_progress" if run_id == current_id else "completed"
    created = "2026-07-30T03:00:00Z" if run_id == 9000 else "2026-07-30T02:59:00Z"
    if queue_scenario:
        source = 100 if run_id == 9000 else 200
        created = "2026-07-30T03:00:00Z" if run_id == 9000 else "2026-07-30T03:01:00Z"
        if equal_time_scenario:
            created = "2026-07-30T03:00:00Z"
        if run_id == 9001 and current_id == 9000:
            status = "pending" if scenario.startswith("pending-") else "queued"
    if scenario == "chronology-before-target":
        created = "2026-07-30T01:30:00Z" if run_id == 9000 else "2026-07-30T03:00:00Z"
    attempt = state.get("resolver_attempt", 1) if run_id == current_id else 1
    if scenario in {"prior-rerun-skipped", "pending-callback-attempt"} and run_id == 9001:
        attempt = 2
    if scenario in {"prior-queued-old", "prior-pending-old"} and run_id == 9001:
        status = "pending" if scenario == "prior-pending-old" else "queued"
    if scenario == "pending-callback-started" and run_id == 9001:
        status = "in_progress"
    if scenario == "pending-callback-malformed" and run_id == 9001:
        created = None
    if scenario.startswith("window-") and run_id == 9001:
        created = "2026-07-30T02:00:00Z"
    if admission_scenario and not scenario.startswith("window-"):
        created = "2026-07-30T03:00:00Z" if run_id == 9000 else "2026-07-30T03:01:00Z"
    if scenario == "cancelled-pending-rerun" and run_id == 9001:
        attempt = 2
    if scenario == "cancelled-pending-unknown" and run_id == 9001:
        status = "unknown"
    return {"id": run_id, "workflow_id": 777, "name": "Resolve Required Gates",
            "path": ".github/workflows/resolve-required-gates.yml", "event": "workflow_run",
            "repository": {"full_name": "example/firemud"},
            "head_sha": "d" * 40 if scenario in {"queued-new-revision", "pending-new-revision"} and run_id == 9001 else "c" * 40,
            "run_attempt": attempt, "status": status, "created_at": created,
            "conclusion": None if scenario == "cancelled-pending-unknown" and run_id == 9001 else "cancelled" if scenario.startswith("cancelled-pending-") and run_id == 9001 and scenario != "cancelled-pending-unknown" else "failure" if scenario == "pending-callback-concluded" and run_id == 9001 else None if status in {"queued", "pending", "in_progress"} else "success",
            "display_title": f"Resolve Required Gates workflow-42 head-{head} source-{source}"}

if method == "POST":
    job_id = int(endpoint.split("/")[-2])
    assert job_id in {2000, 2001} and endpoint.endswith(f"/actions/jobs/{job_id}/rerun"), endpoint
    state["posts"] = state.get("posts", 0) + 1
    state.setdefault("post_jobs", []).append(job_id)
    if scenario in {"ambiguous-post", "same-id-rerun"} or (scenario == "partial-admission" and job_id == 2001):
        state["ambiguous_admission"] = True
        exit_status = 1
        print("simulated connection lost after admission", file=sys.stderr)
    elif not admission_scenario:
        state["attempt"] = 2
elif endpoint.endswith("/actions/workflows/resolve-required-gates.yml"):
    result = {"id": 777, "name": "Resolve Required Gates", "path": ".github/workflows/resolve-required-gates.yml"}
elif endpoint.endswith("/actions/runs/9000") or endpoint.endswith("/actions/runs/9001"):
    result = resolver_run(int(endpoint.rsplit("/", 1)[1]))
elif endpoint.endswith("/actions/workflows/resolve-required-gates.yml/runs"):
    assert "--paginate" not in args and "--slurp" not in args
    assert "created=>=2026-07-30T02:00:00Z" in args and "per_page=100" in args
    state["history_floor"] = "2026-07-30T02:00:00Z"
    assert "head_sha=" + head not in args and not any(arg.startswith("status=") for arg in args)
    page = int(next(arg.split("=", 1)[1] for arg in args if arg.startswith("page=")))
    assert 1 <= page <= 10
    state.setdefault("history_pages", []).append(page)
    runs = [resolver_run(9000)]
    if scenario.startswith("history-") or scenario in {"window-final-admitted", "window-final-ambiguous"}:
        count = 1000 if scenario == "history-ceiling" else 201
        for index in range(count - 2):
            unrelated = resolver_run(10000 + index)
            unrelated["display_title"] = f"Resolve Required Gates workflow-43 head-{'e' * 40} source-{10000 + index}"
            runs.append(unrelated)
        # A matching admission on the final page must not be skipped.
        runs.append(resolver_run(9001))
        if scenario == "history-duplicate":
            runs[100] = runs[1]
        elif scenario == "history-missing-current":
            runs[0] = runs[2].copy()
            runs[0]["id"] = 20000
        elif scenario == "history-before-floor":
            runs[100]["created_at"] = "2026-07-29T01:00:00Z"
    elif scenario.startswith(("prior-", "cancelled-pending-", "window-")) or queue_scenario or (
        state.get("ambiguous_admission") and scenario != "same-id-rerun"
    ):
        runs.append(resolver_run(9001))
    if current_id == 9001 and not any(item["id"] == current_id for item in runs):
        runs.append(resolver_run(current_id))
    if scenario == "window-old-source":
        old = [dict(resolver_run(20000 + index), created_at="2026-07-30T01:30:00Z") for index in range(1001)]
        runs.extend(old)
    if scenario != "history-before-floor":
        runs = [item for item in runs if item["created_at"] is None or item["created_at"] >= "2026-07-30T02:00:00Z"]
    total = 1001 if scenario in {"prior-oversized", "history-overcap"} else len(runs) + (1 if scenario == "prior-incomplete" else 0)
    result = {"total_count": total, "workflow_runs": runs[(page - 1) * 100:page * 100]}
    if page == 2:
        if scenario == "history-incomplete":
            result["workflow_runs"].pop()
        elif scenario == "history-missing-total":
            result.pop("total_count")
        elif scenario == "history-malformed-total":
            result["total_count"] = str(total)
        elif scenario == "history-inconsistent-total":
            result["total_count"] += 1
        elif scenario == "history-malformed-page":
            result["workflow_runs"] = None
        elif scenario == "history-unavailable-page":
            exit_status = 1
elif endpoint.endswith("/actions/runs/9001/attempts/1/jobs") or endpoint.endswith("/actions/runs/9000/attempts/1/jobs"):
    prior_id = int(endpoint.split("/")[-4])
    if queue_scenario:
        assert (current_id == 9001 and prior_id == 9000) or (
            (equal_time_scenario or rejected_later) and current_id == 9000 and prior_id == 9001
        ), "strictly later waiting callback was queried as potentially admitted"
    assert "--paginate" not in args and "--slurp" not in args and "per_page=20" in args
    if scenario == "prior-unavailable":
        exit_status = 1
    else:
        conclusion = "skipped" if scenario in {"prior-noop", "prior-cancelled-between-steps", "prior-rerun-skipped"} or (scenario.startswith("history-") and scenario != "history-ambiguous-tail") else "failure"
        if queue_scenario and not rejected_later:
            conclusion = "success"
        if scenario.startswith("window-"):
            conclusion = "failure" if scenario in {"window-boundary-ambiguous", "window-final-ambiguous"} else "success"
        if scenario == "prior-cancelled-admission":
            conclusion = "cancelled"
        steps = [{"name": "Preflight deferred gate resolution", "status": "completed", "conclusion": "success"},
                 {"name": "Admit deferred gate reruns", "status": "completed", "conclusion": conclusion}]
        if scenario in {"prior-missing-step", "prior-queued-old", "prior-pending-old"}:
            steps.pop()
        result = {"total_count": 1, "jobs": [{"id": prior_id * 10, "run_id": prior_id, "name": "Resolve deferred metadata gate",
                             "head_sha": resolver_run(prior_id)["head_sha"],
                             "status": "completed", "conclusion": "cancelled" if scenario.startswith("prior-cancelled") else "failure",
                             "steps": steps}]}
        record = state.get("native_admissions", {}).get(str(prior_id))
        if record:
            steps[-1].update(status="completed", conclusion=record["admission_conclusion"])
            steps.extend(record["confirmations"])
        elif conclusion == "success":
            steps.append({"name": "Confirm admitted deferred gate jobs " + ("[2000]" if scenario in {"window-boundary-admitted", "window-final-admitted", "window-source-expiry", "window-ties"} else "[]"), "status": "completed", "conclusion": "success"})
        if scenario.startswith("cancelled-pending-"):
            result = {"total_count": 0, "jobs": []}
            if scenario == "cancelled-pending-nonempty":
                result = {"total_count": 1, "jobs": [{"id": 90010, "run_id": prior_id,
                    "head_sha": resolver_run(prior_id)["head_sha"], "name": "Resolve deferred metadata gate",
                    "status": "completed", "conclusion": "cancelled", "steps": []}]}
            elif scenario == "cancelled-pending-incomplete":
                result["total_count"] = 1
            elif scenario == "cancelled-pending-malformed":
                result["jobs"] = None
            elif scenario == "cancelled-pending-missing":
                result.pop("total_count")
            elif scenario == "cancelled-pending-started":
                result = {"total_count": 1, "jobs": [{"id": 90010, "run_id": prior_id,
                    "head_sha": resolver_run(prior_id)["head_sha"], "name": "Resolve deferred metadata gate",
                    "status": "in_progress", "conclusion": None, "steps": []}]}
        if equal_time_scenario and current_id == 9000:
            job = result["jobs"][0]
            job.update(status="queued", conclusion=None, started_at=None, completed_at=None, steps=[])
            if scenario in {"queued-equal-started", "pending-equal-started"}:
                job.update(status="in_progress", started_at="2026-07-30T03:00:00Z")
            elif scenario in {"queued-equal-missing", "pending-equal-missing"}:
                job.pop("started_at")
            elif scenario in {"queued-equal-malformed", "pending-equal-malformed"}:
                job["steps"] = None
            elif scenario in {"queued-equal-incomplete", "pending-equal-incomplete"}:
                result["total_count"] = 2
            elif scenario in {"queued-equal-wrong-job", "pending-equal-wrong-job"}:
                job["run_id"] = 9002
            elif scenario == "pending-equal-started-step":
                job["steps"] = [{"name": "Set up job", "status": "in_progress", "conclusion": None}]
elif endpoint.endswith("/actions/workflows/ci.yml"):
    result = {"id": 42, "name": "CI — Validation", "path": ".github/workflows/ci.yml"}
elif endpoint.endswith("/actions/workflows/ci.yml/runs"):
    assert "--paginate" not in args and "--slurp" not in args
    assert "head_sha=" + head in args and "event=pull_request" in args and "per_page=20" in args
    state["source_listing_reads"] = state.get("source_listing_reads", 0) + 1
    runs = [run(100), run(200 if admission_scenario else target_id)]
    if scenario in {"partial-admission", "window-ties", "window-new-target"} or (scenario == "admission-distinct" and state.get("posts", 0)):
        runs.append(run(201))
    if scenario == "window-source-expiry":
        runs = [run(200)]
    total_count = 21 if scenario == "source-oversized" else len(runs)
    if scenario == "source-transient-row-count" and state["source_listing_reads"] == 1:
        total_count += 1
    elif scenario == "source-third-read-complete" and state["source_listing_reads"] <= 2:
        total_count += 1
    elif scenario == "source-incomplete-then-malformed":
        if state["source_listing_reads"] == 1:
            total_count += 1
        else:
            runs = ["bad"]
            total_count = 1
    elif scenario == "source-transient-missing-source" and state["source_listing_reads"] == 1:
        runs = [run(200)]
        total_count = len(runs)
    elif scenario == "source-empty-then-complete" and state["source_listing_reads"] == 1:
        runs = []
        total_count = 0
    elif scenario == "source-persistent-incomplete":
        total_count += 1
    elif scenario == "source-empty-persistent":
        runs = []
        total_count = 0
    elif scenario == "source-malformed-schema":
        runs[0].pop("created_at")
    elif scenario == "source-identity-mismatch":
        runs[0]["head_sha"] = "f" * 40
    elif scenario == "source-malformed-row":
        runs = ["bad"]
        total_count = 1
    elif scenario == "source-malformed-repository":
        runs[0]["repository"] = "bad"
    result = {"total_count": total_count, "workflow_runs": runs}
    if scenario == "source-scalar-response":
        result = "bad"
    elif scenario == "source-array-response":
        result = []
elif endpoint.endswith("/pulls/123"):
    state["pr_reads"] = state.get("pr_reads", 0) + 1
    result = {"number": 123, "state": "open", "base": {"sha": base, "ref": "develop", "repo": {"full_name": "example/firemud"}},
              "head": {"sha": "c" * 40 if scenario == "stale-pr" else head, "ref": "proof-branch", "repo": {"full_name": repository}}}
    if state["pr_reads"] >= 2 and scenario == "fresh-base-branch":
        result["base"]["ref"] = "main"
    elif state["pr_reads"] >= 2 and scenario == "fresh-head-repository":
        result["head"]["repo"]["full_name"] = "other-owner/firemud"
elif endpoint.endswith(f"/actions/runs/{target_id}/attempts/1/jobs") or (admission_scenario and any(endpoint.endswith(f"/actions/runs/{run_id}/attempts/1/jobs") for run_id in (200, 201))):
    requested_target = int(endpoint.split("/")[-4])
    assert "--paginate" in args and "--slurp" in args
    steps = [{"name": "Preserve successful required gate on metadata-only edit", "status": "completed", "conclusion": "success"}]
    if scenario != "ordinary-failure":
        steps.append({"name": "Report dependency-deferred required gate", "status": "completed", "conclusion": "failure"})
    result = [{"total_count": 1, "jobs": [{"id": 2001 if requested_target == 201 else 2000, "run_id": requested_target, "head_sha": head, "status": "completed", "conclusion": "failure",
                         "name": "Validation Gate", "steps": steps}]}]
    if scenario.startswith("target-jobs-"):
        gate = result[0]["jobs"][0]
        if scenario == "target-jobs-truncated":
            # A second gate job is omitted, so uniqueness is unproved.
            result[0]["total_count"] = 2
        elif scenario == "target-jobs-missing-total":
            result[0].pop("total_count")
        elif scenario == "target-jobs-malformed-total":
            result[0]["total_count"] = "1"
        elif scenario == "target-jobs-overcount":
            result[0]["total_count"] = 0
        elif scenario == "target-jobs-invalid-id":
            gate["id"] = "2000"
        elif scenario == "target-jobs-empty-pages":
            result = []
        elif scenario == "target-jobs-zero":
            result = [{"total_count": 0, "jobs": []}]
        elif scenario == "target-jobs-duplicate":
            result = [{"total_count": 2, "jobs": [gate]}, {"total_count": 2, "jobs": [gate]}]
        elif scenario in {"target-jobs-multi", "target-jobs-inconsistent-total"}:
            other = dict(gate, id=2002, name="Other job")
            result = [{"total_count": 2, "jobs": [other]},
                      {"total_count": 3 if scenario.endswith("inconsistent-total") else 2, "jobs": [gate]}]
        else:
            raise AssertionError(scenario)
elif endpoint.endswith(f"/actions/runs/{target_id}") or (admission_scenario and any(endpoint.endswith(f"/actions/runs/{run_id}") for run_id in (200, 201))):
    requested_target = int(endpoint.rsplit("/", 1)[1])
    state["target_reads"] = state.get("target_reads", 0) + 1
    state.setdefault("target_reads_by_id", {})[str(requested_target)] = state.get("target_reads_by_id", {}).get(str(requested_target), 0) + 1
    result = run(requested_target)
    if scenario == "window-new-target" and requested_target == 201 and not state.get("history_pages"):
        result["status"] = "queued"
    if scenario == "chronology-missing-created":
        result.pop("created_at")
    elif scenario == "chronology-invalid-created":
        result["created_at"] = "not-a-timestamp"
    elif scenario == "chronology-fresh-created" and state["target_reads"] >= 2:
        result["created_at"] = "2026-07-30T03:01:00Z"
    elif scenario == "consumed":
        result["run_attempt"] = 2
    elif scenario == "fresh-changed" and state["target_reads"] >= 2:
        result["status"] = "queued"
    elif scenario == "wrong-tuple":
        result["display_title"] = title.replace(base, "c" * 40)
    elif scenario == "wrong-association":
        result["pull_requests"] = [{"number": 456}]
    elif scenario == "malformed-path":
        result["path"] += "@"
elif endpoint.endswith("/actions/runs/100"):
    result = run(100)
else:
    raise AssertionError(endpoint)
state_path.write_text(json.dumps(state))
if result is not None:
    print(json.dumps(result))
sys.exit(exit_status)
PY
EOF
chmod +x "$tmp_dir/resolver-bin/gh"
cat >"$tmp_dir/resolver-bin/sleep" <<'EOF'
#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys
state_path = Path(os.environ["RESOLVER_STATE"])
state = json.loads(state_path.read_text())
state.setdefault("source_listing_waits", []).append(int(sys.argv[1]))
state_path.write_text(json.dumps(state))
EOF
chmod +x "$tmp_dir/resolver-bin/sleep"

run_resolver() {
  local scenario="$1" source_id="${2:-100}" original_scenario="${3:-no-local-workflow-file}"
  local phase="${4:-admit}"
  local resolver_id="${5:-9000}"
  local event_file="$tmp_dir/resolver-event-$scenario"
  local result_status=0
  : >"$tmp_dir/resolver-output-$scenario"
  printf '{"action":"completed","workflow_run":{"id":%s,"workflow_id":42,"head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","event":"pull_request"}}\n' \
    "$source_id" >"$event_file"
  PATH="$tmp_dir/resolver-bin:$PATH" MOCK_ORIGINAL_GH="$tmp_dir/gh" \
    RESOLVER_STATE="$tmp_dir/resolver-state-$scenario" \
    REQUIRED_GATE_PROOF_ALLOWLIST='[{"pr":123,"head_branch":"proof-branch"}]' \
    GITHUB_EVENT_NAME=workflow_run GITHUB_EVENT_PATH="$event_file" GITHUB_RUN_ID="$resolver_id" \
    GITHUB_OUTPUT="$tmp_dir/resolver-output-$scenario" \
    GITHUB_REPOSITORY=example/firemud GH_TOKEN=test-token \
    GH_RETRY_COUNT_FILE="$tmp_dir/resolver-proof-count-$scenario" GH_SCENARIO="$original_scenario" \
    GH_FAILURE_MODE=none bash "$RESOLVER" "$phase" >"$tmp_dir/resolver-log-$scenario" 2>&1 || result_status=$?
  if [[ "$phase" == admit ]]; then
    python3 - "$tmp_dir/resolver-state-$scenario" "$tmp_dir/resolver-output-$scenario" "$resolver_id" "$result_status" <<'PY'
import json
from pathlib import Path
import sys
state_path, output_path = map(Path, sys.argv[1:3])
run_id, result = sys.argv[3], int(sys.argv[4])
state = json.loads(state_path.read_text())
outputs = dict(line.split("=", 1) for line in output_path.read_text().splitlines())
encoded_ids = outputs.get("accepted_job_ids", "")
confirmations = []
if result == 0:
    # Emulate the following native confirmation step from the actual output.
    json.loads(encoded_ids)
    confirmation = {"name": "Confirm admitted deferred gate jobs " + encoded_ids,
                    "status": "completed", "conclusion": "success"}
    scenario = state["scenario"]
    if scenario == "admission-marker-failed":
        confirmation["conclusion"] = "failure"
    elif scenario == "admission-marker-cancelled":
        confirmation["conclusion"] = "cancelled"
    elif scenario == "admission-marker-incomplete":
        confirmation.update(status="in_progress", conclusion=None)
    elif scenario == "admission-marker-malformed":
        confirmation["name"] = "Confirm admitted deferred gate jobs not-json"
    elif scenario == "admission-marker-duplicate-ids":
        confirmation["name"] = "Confirm admitted deferred gate jobs [2000,2000]"
    elif scenario == "admission-marker-string-id":
        confirmation["name"] = 'Confirm admitted deferred gate jobs ["2000"]'
    elif scenario == "admission-marker-oversized":
        confirmation["name"] = "Confirm admitted deferred gate jobs " + json.dumps(list(range(1, 22)), separators=(",", ":"))
    confirmations.append(confirmation)
    if scenario == "admission-marker-missing":
        confirmations = []
    elif scenario == "admission-marker-duplicate-step":
        confirmations.append(confirmation.copy())
else:
    assert "accepted_job_ids" not in outputs, "uncertain admission published acknowledged IDs"
state.setdefault("native_admissions", {})[run_id] = {
    "admission_conclusion": "success" if result == 0 else "failure", "confirmations": confirmations,
}
state_path.write_text(json.dumps(state))
PY
  fi
  return "$result_status"
}
resolver_posts() {
  jq -r '.posts // 0' "$tmp_dir/resolver-state-$1"
}
resolver_source_listing_reads() {
  jq -r '.source_listing_reads // 0' "$tmp_dir/resolver-state-$1"
}
resolver_source_listing_waits() {
  jq -c '.source_listing_waits // []' "$tmp_dir/resolver-state-$1"
}
for scenario in success metadata-last metadata-first consumed fresh-changed fresh-base-branch fresh-head-repository ordinary-failure stale-pr wrong-tuple wrong-association malformed-path fork-empty ambiguous-post original-failure preflight prior-noop prior-cancelled-between-steps prior-cancelled-admission prior-ambiguous prior-unavailable prior-incomplete prior-missing-step prior-oversized source-oversized source-transient-row-count source-transient-missing-source source-empty-then-complete source-third-read-complete source-incomplete-then-malformed source-persistent-incomplete source-empty-persistent source-malformed-schema source-identity-mismatch source-malformed-row source-malformed-repository source-scalar-response source-array-response same-id-rerun prior-rerun-skipped queued-callback queued-new-revision queued-equal queued-equal-started queued-equal-missing queued-equal-malformed queued-equal-incomplete queued-equal-wrong-job prior-queued-old pending-callback pending-new-revision pending-equal pending-equal-started pending-equal-missing pending-equal-malformed pending-equal-incomplete pending-equal-wrong-job pending-equal-started-step pending-callback-started pending-callback-malformed pending-callback-attempt pending-callback-concluded prior-pending-old history-multi history-ceiling history-ambiguous-tail history-duplicate history-missing-current history-before-floor history-overcap history-incomplete history-missing-total history-malformed-total history-inconsistent-total history-malformed-page history-unavailable-page admission-delayed admission-zero admission-distinct admission-marker-failed admission-marker-cancelled admission-marker-incomplete admission-marker-missing admission-marker-malformed admission-marker-duplicate-ids admission-marker-string-id admission-marker-oversized admission-marker-duplicate-step partial-admission cancelled-pending-zero cancelled-pending-nonempty cancelled-pending-incomplete cancelled-pending-malformed cancelled-pending-missing cancelled-pending-unknown cancelled-pending-rerun cancelled-pending-started; do
  printf '{"scenario":"%s"}\n' "$scenario" >"$tmp_dir/resolver-state-$scenario"
done
for target_coverage_case in truncated missing-total malformed-total overcount invalid-id empty-pages duplicate inconsistent-total zero multi; do
  target_coverage_scenario="target-jobs-$target_coverage_case"
  printf '{"scenario":"%s"}\n' "$target_coverage_scenario" >"$tmp_dir/resolver-state-$target_coverage_scenario"
  case "$target_coverage_case" in
    zero|multi)
      run_resolver "$target_coverage_scenario"
      expected_posts=0
      [[ "$target_coverage_case" != multi ]] || expected_posts=1
      [[ "$(resolver_posts "$target_coverage_scenario")" == "$expected_posts" ]] || {
        echo "complete target job coverage selected the wrong gate: $target_coverage_case" >&2
        exit 1
      }
      ;;
    *)
      if run_resolver "$target_coverage_scenario"; then
        echo "resolver accepted invalid target job coverage: $target_coverage_case" >&2
        exit 1
      fi
      [[ "$(resolver_posts "$target_coverage_scenario")" == 0 ]] || {
        echo "invalid target job coverage admitted a POST: $target_coverage_case" >&2
        exit 1
      }
      grep -Fq 'malformed or incomplete target jobs' "$tmp_dir/resolver-log-$target_coverage_scenario" || {
        echo "invalid target job coverage did not fail at coverage validation: $target_coverage_case" >&2
        exit 1
      }
      ;;
  esac
done

# A queued resolver cannot admit a target created after its own callback.
# The target's later callback remains eligible; no external receipt is needed.
for chronology_case in before-target missing-created invalid-created fresh-created; do
  chronology_scenario="chronology-$chronology_case"
  printf '{"scenario":"%s"}\n' "$chronology_scenario" >"$tmp_dir/resolver-state-$chronology_scenario"
  run_resolver "$chronology_scenario"
  [[ "$(resolver_posts "$chronology_scenario")" == 0 && "$(<"$tmp_dir/resolver-output-$chronology_scenario")" == 'accepted_job_ids=[]' ]] || {
    echo "unproved target creation chronology admitted a POST: $chronology_case" >&2; exit 1;
  }
done
[[ "$(jq '.target_reads' "$tmp_dir/resolver-state-chronology-fresh-created")" == 2 ]] || {
  echo "target chronology was not rechecked immediately before admission" >&2; exit 1;
}
run_resolver chronology-before-target 200 no-local-workflow-file admit 9001
[[ "$(resolver_posts chronology-before-target)" == 1 ]] || {
  echo "target's later callback did not resolve a target refused by the earlier queued resolver" >&2; exit 1;
}

# A passive deployment must not even invoke the CLI.
REQUIRED_GATE_PROOF_ALLOWLIST='[]' PATH="$tmp_dir/resolver-bin:$PATH" bash "$RESOLVER"
run_resolver success
run_resolver success
[[ "$(resolver_posts success)" == 1 ]] || { echo "duplicate completion replayed a targeted rerun" >&2; exit 1; }
run_resolver metadata-last 200
[[ "$(resolver_posts metadata-last)" == 1 ]] || { echo "metadata completion after original success was not resolved" >&2; exit 1; }
run_resolver metadata-first 200 pending-predecessor
[[ "$(resolver_posts metadata-first)" == 0 ]] || { echo "pending original admitted a rerun" >&2; exit 1; }
run_resolver metadata-first 100 pending-predecessor
[[ "$(resolver_posts metadata-first)" == 1 ]] || { echo "later original completion did not resolve metadata deferral" >&2; exit 1; }
for scenario in consumed fresh-changed fresh-base-branch fresh-head-repository ordinary-failure stale-pr wrong-tuple wrong-association malformed-path; do
  run_resolver "$scenario"
  [[ "$(resolver_posts "$scenario")" == 0 ]] || { echo "resolver reran rejected target: $scenario" >&2; exit 1; }
done
for scenario in source-transient-row-count source-transient-missing-source source-empty-then-complete; do
  run_resolver "$scenario"
  [[ "$(resolver_posts "$scenario")" == 1 && "$(resolver_source_listing_reads "$scenario")" == 2 &&
    "$(resolver_source_listing_waits "$scenario")" == '[1]' ]] || {
    echo "valid incomplete source listing was not reread before admission: $scenario" >&2; exit 1;
  }
  grep -F "Exact-head source listing is incomplete (read 1/3):" "$tmp_dir/resolver-log-$scenario" >/dev/null || {
    echo "source-list retry diagnostic was missing: $scenario" >&2; exit 1;
  }
done
run_resolver source-third-read-complete
[[ "$(resolver_posts source-third-read-complete)" == 1 &&
  "$(resolver_source_listing_reads source-third-read-complete)" == 3 &&
  "$(resolver_source_listing_waits source-third-read-complete)" == '[1,2]' ]] || {
  echo "third-read source recovery exceeded or bypassed the read/wait budget" >&2; exit 1;
}
if run_resolver source-incomplete-then-malformed; then
  echo "source listing that became malformed did not fail closed" >&2; exit 1
fi
[[ "$(resolver_posts source-incomplete-then-malformed)" == 0 &&
  "$(resolver_source_listing_reads source-incomplete-then-malformed)" == 2 &&
  "$(resolver_source_listing_waits source-incomplete-then-malformed)" == '[1]' ]] || {
  echo "source listing retried after becoming malformed" >&2; exit 1;
}
grep -F 'Exact-head source coverage rejected: classification=malformed-row, total_count=1, returned_rows=1, source_present=false, limit=20.' \
  "$tmp_dir/resolver-log-source-incomplete-then-malformed" >/dev/null || {
  echo "source listing that became malformed lost its classification" >&2; exit 1;
}
for scenario in source-persistent-incomplete source-empty-persistent; do
  if run_resolver "$scenario"; then
    echo "persistent exact-head source undercoverage did not fail closed: $scenario" >&2; exit 1
  fi
  [[ "$(resolver_posts "$scenario")" == 0 && "$(resolver_source_listing_reads "$scenario")" == 3 &&
    "$(resolver_source_listing_waits "$scenario")" == '[1,2]' ]] || {
    echo "persistent source undercoverage exceeded or bypassed the read budget: $scenario" >&2; exit 1;
  }
done
grep -F 'Exact-head source coverage remains incomplete after 3 reads: classification=incomplete-row-count, total_count=3, returned_rows=2, source_present=true, limit=20.' \
  "$tmp_dir/resolver-log-source-persistent-incomplete" >/dev/null || {
  echo "persistent source undercoverage diagnostic did not report exact counts" >&2; exit 1;
}
grep -F 'Exact-head source coverage remains incomplete after 3 reads: classification=incomplete-missing-source, total_count=0, returned_rows=0, source_present=false, limit=20.' \
  "$tmp_dir/resolver-log-source-empty-persistent" >/dev/null || {
  echo "persistent empty source listing was not reported as missing its event source" >&2; exit 1;
}
for scenario in source-malformed-schema source-identity-mismatch source-oversized source-malformed-row source-malformed-repository source-scalar-response source-array-response; do
  if run_resolver "$scenario"; then
    echo "malformed, misattributed, or over-limit source listing was accepted: $scenario" >&2; exit 1
  fi
  [[ "$(resolver_posts "$scenario")" == 0 && "$(resolver_source_listing_reads "$scenario")" == 1 &&
    "$(resolver_source_listing_waits "$scenario")" == '[]' ]] || {
    echo "malformed, misattributed, or over-limit source listing was retried or admitted: $scenario" >&2; exit 1;
  }
  case "$scenario" in
    source-malformed-schema) expected_diagnostic='classification=malformed-created-at, total_count=2, returned_rows=2, source_present=true, limit=20.' ;;
    source-identity-mismatch) expected_diagnostic='classification=identity-mismatch, total_count=2, returned_rows=2, source_present=true, limit=20.' ;;
    source-oversized) expected_diagnostic='classification=overflow-total-count, total_count=21, returned_rows=2, source_present=true, limit=20.' ;;
    source-malformed-row) expected_diagnostic='classification=malformed-row, total_count=1, returned_rows=1, source_present=false, limit=20.' ;;
    source-malformed-repository) expected_diagnostic='classification=malformed-row-repository, total_count=2, returned_rows=2, source_present=true, limit=20.' ;;
    source-scalar-response|source-array-response) expected_diagnostic='classification=malformed-response, total_count=unknown, returned_rows=unknown, source_present=false, limit=20.' ;;
  esac
  grep -F "Exact-head source coverage rejected: $expected_diagnostic" "$tmp_dir/resolver-log-$scenario" >/dev/null || {
    echo "source-list rejection omitted its cause or bounded numeric evidence: $scenario" >&2; exit 1;
  }
done
run_resolver fork-empty 100 fork-empty-association
[[ "$(resolver_posts fork-empty)" == 1 ]] || { echo "resolver lost fork/empty-association attribution" >&2; exit 1; }
if run_resolver ambiguous-post; then
  echo "ambiguous rerun POST did not fail closed" >&2
  exit 1
fi
[[ "$(resolver_posts ambiguous-post)" == 1 ]] || { echo "ambiguous POST was replayed" >&2; exit 1; }
if run_resolver ambiguous-post; then
  echo "a prior ambiguous admission with a still-visible attempt 1 did not require recovery" >&2
  exit 1
fi
[[ "$(resolver_posts ambiguous-post)" == 1 ]] || { echo "later completion replayed an ambiguous POST" >&2; exit 1; }
if run_resolver same-id-rerun; then
  echo "initial ambiguous same-ID admission did not fail closed" >&2; exit 1
fi
jq '.resolver_attempt = 2' "$tmp_dir/resolver-state-same-id-rerun" >"$tmp_dir/resolver-state-update"
mv "$tmp_dir/resolver-state-update" "$tmp_dir/resolver-state-same-id-rerun"
if run_resolver same-id-rerun; then
  echo "current resolver rerun hid its ambiguous first attempt" >&2; exit 1
fi
[[ "$(resolver_posts same-id-rerun)" == 1 ]] || { echo "same-ID resolver rerun replayed admission" >&2; exit 1; }
for scenario in queued-callback queued-new-revision queued-equal pending-callback pending-new-revision pending-equal; do
  run_resolver "$scenario"
  [[ "$(resolver_posts "$scenario")" == 1 ]] || { echo "later waiting callback poisoned current resolution: $scenario" >&2; exit 1; }
  run_resolver "$scenario" 200 no-local-workflow-file admit 9001
  [[ "$(resolver_posts "$scenario")" == 1 ]] || { echo "successor callback repeated consumed admission: $scenario" >&2; exit 1; }
done
run_resolver preflight 100 no-local-workflow-file preflight
[[ "$(resolver_posts preflight)" == 0 && "$(<"$tmp_dir/resolver-output-preflight")" == 'eligible=true' ]] || {
  echo "preflight performed admission or lost native eligibility output" >&2; exit 1;
}
for scenario in prior-noop prior-cancelled-between-steps; do
  run_resolver "$scenario"
  [[ "$(resolver_posts "$scenario")" == 1 ]] || { echo "clean skipped admission blocked resolution" >&2; exit 1; }
done
for scenario in prior-ambiguous prior-cancelled-admission prior-unavailable prior-incomplete prior-missing-step prior-oversized source-oversized prior-rerun-skipped prior-queued-old queued-equal-started queued-equal-missing queued-equal-malformed queued-equal-incomplete queued-equal-wrong-job pending-equal-started pending-equal-missing pending-equal-malformed pending-equal-incomplete pending-equal-wrong-job pending-equal-started-step pending-callback-started pending-callback-malformed pending-callback-attempt pending-callback-concluded prior-pending-old history-ambiguous-tail history-duplicate history-missing-current history-before-floor history-overcap history-incomplete history-missing-total history-malformed-total history-inconsistent-total history-malformed-page history-unavailable-page; do
  if run_resolver "$scenario"; then
    echo "unproved prior admission did not require recovery: $scenario" >&2; exit 1
  fi
  [[ "$(resolver_posts "$scenario")" == 0 ]] || { echo "unproved prior admission was replayed" >&2; exit 1; }
done
for scenario in history-multi history-ceiling; do
  run_resolver "$scenario"
  [[ "$(resolver_posts "$scenario")" == 1 ]] || { echo "complete resolver history did not admit once: $scenario" >&2; exit 1; }
  expected_pages=3
  [[ "$scenario" != history-ceiling ]] || expected_pages=10
  jq -e --argjson pages "$expected_pages" '.history_pages == [range(1; $pages + 1)]' \
    "$tmp_dir/resolver-state-$scenario" >/dev/null || { echo "resolver history pagination was incomplete: $scenario" >&2; exit 1; }
done
for scenario in prior-oversized history-overcap; do
  [[ "$(jq '.history_pages | length' "$tmp_dir/resolver-state-$scenario")" == 1 ]] || {
    echo "resolver fetched more history after observing the API ceiling: $scenario" >&2; exit 1;
  }
done
[[ "$(jq '[.calls[] | select(endswith("/actions/runs/9001/attempts/1/jobs"))] | length' "$tmp_dir/resolver-state-prior-rerun-skipped")" == 0 ]] || {
  echo "historical rerun relied on a latest skipped snapshot over its ambiguous first attempt" >&2; exit 1;
}
run_resolver admission-delayed
[[ "$(<"$tmp_dir/resolver-output-admission-delayed")" == 'accepted_job_ids=[2000]' ]] || {
  echo "acknowledged POST did not publish its exact job ID" >&2; exit 1;
}
run_resolver admission-delayed 200 no-local-workflow-file admit 9001
[[ "$(resolver_posts admission-delayed)" == 1 && "$(<"$tmp_dir/resolver-output-admission-delayed")" == 'accepted_job_ids=[]' ]] || {
  echo "confirmed admission replayed while target attempt remained one" >&2; exit 1;
}
run_resolver admission-zero 200 pending-predecessor
[[ "$(resolver_posts admission-zero)" == 0 && "$(<"$tmp_dir/resolver-output-admission-zero")" == 'accepted_job_ids=[]' ]] || {
  echo "successful zero-POST admission did not publish an empty set" >&2; exit 1;
}
run_resolver admission-zero 100 no-local-workflow-file admit 9001
[[ "$(resolver_posts admission-zero)" == 1 ]] || { echo "confirmed zero-POST no-op poisoned later admission" >&2; exit 1; }
run_resolver admission-distinct
run_resolver admission-distinct 201 no-local-workflow-file admit 9001
jq -e '.post_jobs == [2000,2001]' "$tmp_dir/resolver-state-admission-distinct" >/dev/null || {
  echo "one acknowledged job blocked a distinct eligible gate or replayed itself" >&2; exit 1;
}
[[ "$(<"$tmp_dir/resolver-output-admission-distinct")" == 'accepted_job_ids=[2001]' ]] || {
  echo "admission output included a previously acknowledged job" >&2; exit 1;
}
for scenario in admission-marker-failed admission-marker-cancelled admission-marker-incomplete admission-marker-missing admission-marker-malformed admission-marker-duplicate-ids admission-marker-string-id admission-marker-oversized admission-marker-duplicate-step; do
  run_resolver "$scenario"
  if run_resolver "$scenario" 200 no-local-workflow-file admit 9001; then
    echo "uncertain native confirmation allowed a later admission: $scenario" >&2; exit 1;
  fi
  [[ "$(resolver_posts "$scenario")" == 1 ]] || { echo "uncertain confirmation replayed a POST: $scenario" >&2; exit 1; }
done
if run_resolver partial-admission; then
  echo "partial admission did not fail closed" >&2; exit 1;
fi
if run_resolver partial-admission 200 no-local-workflow-file admit 9001; then
  echo "partial admission did not require explicit recovery" >&2; exit 1;
fi
[[ "$(resolver_posts partial-admission)" == 2 ]] || { echo "partial admission replayed a POST" >&2; exit 1; }
run_resolver cancelled-pending-zero
[[ "$(resolver_posts cancelled-pending-zero)" == 1 ]] || { echo "cancelled-before-start zero-job callback blocked admission" >&2; exit 1; }
for scenario in cancelled-pending-nonempty cancelled-pending-incomplete cancelled-pending-malformed cancelled-pending-missing cancelled-pending-unknown cancelled-pending-rerun cancelled-pending-started; do
  if run_resolver "$scenario"; then
    echo "uncertain cancelled callback was treated as never admitted: $scenario" >&2; exit 1;
  fi
  [[ "$(resolver_posts "$scenario")" == 0 ]] || { echo "uncertain cancelled callback admitted a POST: $scenario" >&2; exit 1; }
done
if run_resolver original-failure 100 failed-predecessor; then
  echo "resolver accepted failed original proof" >&2
  exit 1
fi
[[ "$(resolver_posts original-failure)" == 0 ]] || { echo "failed original admitted a rerun" >&2; exit 1; }

# History starts at the earliest frozen eligible target, including ties. Old
# substantive evidence and unrelated earlier traffic do not widen that window.
for scenario in window-old-source window-boundary-admitted window-boundary-ambiguous window-final-admitted window-final-ambiguous window-source-expiry window-ties window-new-target; do
  printf '{"scenario":"%s"}\n' "$scenario" >"$tmp_dir/resolver-state-$scenario"
  source_id=100
  [[ "$scenario" != window-source-expiry ]] || source_id=200
  if [[ "$scenario" == *ambiguous ]]; then
    if run_resolver "$scenario" "$source_id"; then
      echo "target-boundary ambiguous admission was ignored: $scenario" >&2; exit 1
    fi
  else
    run_resolver "$scenario" "$source_id"
  fi
  python3 - "$tmp_dir/resolver-state-$scenario" "$scenario" <<'PY_WINDOW'
import json
from pathlib import Path
import sys
state = json.loads(Path(sys.argv[1]).read_text())
scenario = sys.argv[2]
assert state["history_floor"] == "2026-07-30T02:00:00Z"
expected = [2000] if scenario in {"window-old-source", "window-new-target"} else [2001] if scenario == "window-ties" else []
assert state.get("post_jobs", []) == expected, (scenario, state)
if "final" in scenario:
    assert state["history_pages"] == [1, 2, 3], state
if scenario == "window-new-target":
    assert state["target_reads_by_id"]["201"] == 1, state
PY_WINDOW
done
# Ineligible or consumed targets need neither history nor recovery inference.
for scenario in ordinary-failure consumed chronology-missing-created chronology-invalid-created; do
  printf '{"scenario":"%s"}\n' "$scenario" >"$tmp_dir/resolver-state-$scenario"
  run_resolver "$scenario"
  python3 - "$tmp_dir/resolver-state-$scenario" <<'PY_WINDOW'
import json
from pathlib import Path
import sys
state = json.loads(Path(sys.argv[1]).read_text())
assert not state.get("history_pages") and not state.get("posts"), state
PY_WINDOW
done

echo "PR required-gate context contract checks passed"
