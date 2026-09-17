#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ACTION="$ROOT_DIR/.github/actions/preserve-required-gate/action.yml"

[[ -f "$ACTION" ]] || {
  echo "required-gate preservation composite action is missing: $ACTION" >&2
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
  if grep -Fq '        if:' <<<"$harden_block" ||
    ! grep -Eq '        uses: step-security/harden-runner@[0-9a-f]{40}$' <<<"$harden_block" ||
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
    "codeql-gate": "${{ always() && github.event_name == 'pull_request' && (github.base_ref == 'develop' || github.base_ref == 'main') }}",
}
result_step_names = {
    "validation-gate": "Enforce validation success",
    "security-gate": "Enforce security success",
    "license-gate": "Verify ORT results",
    "smoke-gate": "Classify smoke gate execution",
    "codeql-gate": "Enforce successful CodeQL analysis",
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
if job_id not in expected_job_if or job_id not in result_step_names:
    raise SystemExit(
        f"{workflow} {gate}: unknown gate job ID {job_id!r}; "
        "add it to expected_job_if and result_step_names"
    )

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
  if [[ "$group_line" != *"&& 'metadata' || 'required' }}"* ]]; then
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
security.yml|security-gate|Security Gate|Security Checks|security.yml|.github/workflows/security.yml
license-scan.yml|license-gate|License Gate|License Checks|license-scan.yml|.github/workflows/license-scan.yml
smoke.yml|smoke-gate|Smoke Gate|PR Smoke Gate|smoke.yml|.github/workflows/smoke.yml
codeql.yml|codeql-gate|CodeQL Gate|CodeQL Analysis|codeql.yml|.github/workflows/codeql.yml
EOF

CODEQL_WORKFLOW="$ROOT_DIR/.github/workflows/codeql.yml"
OVERLAY_WORKFLOW="$ROOT_DIR/.github/workflows/validate-kustomize-overlays.yml"

grep -Fq 'needs: [changes, analyze]' "$CODEQL_WORKFLOW" || {
  echo "CodeQL gate must depend directly on change detection" >&2
  exit 1
}
grep -Fq "(github.base_ref == 'develop' || github.base_ref == 'main')" "$CODEQL_WORKFLOW" || {
  echo "CodeQL gate must run for both protected pull request bases" >&2
  exit 1
}
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
cat >"$tmp_dir/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
count_file="${GH_RETRY_COUNT_FILE:?}"
if [[ -n "${GH_CALL_COUNT_DIR:-}" ]]; then
  mkdir -p "$GH_CALL_COUNT_DIR"
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
  elif [[ "${GH_SCENARIO:-}" == "pending-preservation-step-not-concluded" &&
    "${job_id}" == "100" && "$(<"$count_file")" -le 3 ]]; then
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
  elif [[ "${GH_SCENARIO:-}" == "empty-wrong-head" ]]; then
    pull_requests='[]'
    display_title="${workflow_name} pr-${PR_NUMBER} base-${BASE_SHA} head-dddddddddddddddddddddddddddddddddddddddd"
  elif [[ "${GH_SCENARIO:-}" == "empty-wrong-title" ]]; then
    pull_requests='[]'
    display_title='not the canonical run title'
  elif [[ "${GH_SCENARIO:-}" == "empty-malformed-association" ]]; then
    pull_requests='null'
  elif [[ "${GH_SCENARIO:-}" == "other-pr-association" ]]; then
    pull_requests='[{"number":456}]'
  elif [[ "${GH_SCENARIO:-}" == "cross-workflow-same-name" ]]; then
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
case "${GH_SCENARIO:-failure-retry}" in
  latest-pending-preferred)
    if [[ "$count" -eq 1 ]]; then
      cat <<'JSON'
[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":101,"details_url":"https://github.com/example/firemud/actions/runs/101/job/101","status":"in_progress","conclusion":null,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]
JSON
    else
      cat <<'JSON'
[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":101,"details_url":"https://github.com/example/firemud/actions/runs/101/job/101","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]
JSON
    fi
    ;;
  missing-created-at)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  pending-predecessor)
    if [[ "$count" -eq 1 ]]; then
      cat <<'JSON'
[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"in_progress","conclusion":null,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]
JSON
    else
      cat <<'JSON'
[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]
JSON
    fi
    ;;
  queued-null-started-at)
    if [[ "$count" -eq 1 ]]; then
      printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"queued","conclusion":null,"started_at":null,"created_at":"2026-07-30T01:00:00Z"}]}]\n'
    else
      printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    fi
    ;;
  no-local-workflow-file)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  no-prior)
    printf '[{"check_runs":[]}]\n'
    ;;
  delayed-predecessor)
    if [[ "$count" -eq 1 ]]; then
      printf '[{"check_runs":[]}]\n'
    elif [[ "$count" -eq 2 ]]; then
      printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"in_progress","conclusion":null,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    else
      printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    fi
    ;;
  failed-predecessor)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"failure","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  newer-failure-over-success)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:50:00Z","created_at":"2026-07-30T00:50:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":101,"details_url":"https://github.com/example/firemud/actions/runs/101/job/101","status":"completed","conclusion":"failure","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:50:00Z","created_at":"2026-07-30T01:50:00Z"}]}]\n'
    ;;
  same-timestamp-newer-failure)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":101,"details_url":"https://github.com/example/firemud/actions/runs/101/job/101","status":"completed","conclusion":"failure","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  cross-workflow-same-name|fork-empty-association|dynamic-run-name|dynamic-run-name-fork-empty|wrong-run-name|wrong-job-workflow-name|empty-wrong-pr|empty-wrong-base|empty-wrong-head|empty-wrong-title|empty-malformed-association|other-pr-association)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":200,"details_url":"https://github.com/example/firemud/actions/runs/200/job/200","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  run-path-ref-suffix|run-path-ref-suffix-plus|run-path-ref-suffix-at|malformed-run-path-ref-suffix)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":200,"details_url":"https://github.com/example/firemud/actions/runs/200/job/200","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  details-url-query|details-url-fragment|malformed-details-url-suffix)
    details_suffix='?check_suite_focus=true'
    [[ "${GH_SCENARIO:-}" == "details-url-fragment" ]] && details_suffix='#step:1:2'
    [[ "${GH_SCENARIO:-}" == "malformed-details-url-suffix" ]] && details_suffix='@evil'
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":200,"details_url":"https://github.com/example/firemud/actions/runs/200/job/200%s","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n' "$details_suffix"
    ;;
  fork-head-same-sha)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":200,"details_url":"https://github.com/example/firemud/actions/runs/200/job/200","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  wrong-run-repository)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":200,"details_url":"https://github.com/example/firemud/actions/runs/200/job/200","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  unknown-app)
    printf '[{"check_runs":[{"app":{"slug":"other-checks"},"name":"Validation Gate","id":200,"details_url":"https://github.com/example/firemud/actions/runs/200/job/200","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n'
    ;;
  unknown-check-name)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Other Gate","id":200,"details_url":"https://github.com/example/firemud/actions/runs/200/job/200","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n'
    ;;
  invalid-job-id)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/not-a-number","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n'
    ;;
  missing-job-id)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n'
    ;;
  completed-preservation-step-lag)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n'
    ;;
  completed-preservation-step-persistent-lag)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n'
    ;;
  completed-cancelled-missing-step|completed-skipped-missing-step|completed-stale-missing-step)
    case "${GH_SCENARIO}" in
      completed-cancelled-missing-step) non_authoritative_conclusion=cancelled ;;
      completed-skipped-missing-step) non_authoritative_conclusion=skipped ;;
      completed-stale-missing-step) non_authoritative_conclusion=stale ;;
    esac
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"%s","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n' "$non_authoritative_conclusion"
    ;;
  unsupported-status)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"mysterious","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n'
    ;;
  missing-timestamp)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":null,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":99,"details_url":"https://github.com/example/firemud/actions/runs/99/job/99","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"}]}]\n'
    ;;
  multiple-metadata)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T01:00:00Z","started_at":"2026-07-30T00:50:00Z","created_at":"2026-07-30T00:50:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":101,"details_url":"https://github.com/example/firemud/actions/runs/101/job/101","status":"completed","conclusion":"failure","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:50:00Z","created_at":"2026-07-30T01:50:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":102,"details_url":"https://github.com/example/firemud/actions/runs/102/job/102","status":"completed","conclusion":"timed_out","completed_at":"2026-07-30T03:00:00Z","started_at":"2026-07-30T02:50:00Z","created_at":"2026-07-30T02:50:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":103,"details_url":"https://github.com/example/firemud/actions/runs/103/job/103","status":"completed","conclusion":"cancelled","completed_at":"2026-07-30T04:00:00Z","started_at":"2026-07-30T03:50:00Z","created_at":"2026-07-30T03:50:00Z"}]}]\n'
    ;;
  self-run-excluded)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":1,"details_url":"https://github.com/example/firemud/actions/runs/999/job/1","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"failure","started_at":"2026-07-30T02:00:00Z","created_at":"2026-07-30T02:00:00Z"}]}]\n'
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
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"%s","conclusion":%s,"completed_at":%s,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n' "$status" "$conclusion" "$completed_at"
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
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"%s","conclusion":%s,"completed_at":%s,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n' "$status" "$conclusion" "$completed_at"
    ;;
  cache-metadata-across-polls)
    if [[ "$count" -eq 1 ]]; then
      printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":101,"details_url":"https://github.com/example/firemud/actions/runs/101/job/101","status":"in_progress","conclusion":null,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    else
      printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"success","completed_at":"2026-07-30T02:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":101,"details_url":"https://github.com/example/firemud/actions/runs/101/job/101","status":"completed","conclusion":"success","completed_at":"2026-07-30T03:00:00Z","started_at":"2026-07-30T02:00:00Z","created_at":"2026-07-30T02:00:00Z"}]}]\n'
    fi
    ;;
  pending-missing-step-with-failed-substantive)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":101,"details_url":"https://github.com/example/firemud/actions/runs/101/job/101","status":"in_progress","conclusion":null,"completed_at":null,"started_at":"2026-07-30T02:00:00Z","created_at":"2026-07-30T02:00:00Z"},{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","conclusion":"failure","completed_at":"2026-07-30T03:00:00Z","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  timeout-pending)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"waiting","conclusion":null,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
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
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  job-failure-retry)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"name":"Validation Gate","id":100,"details_url":"https://github.com/example/firemud/actions/runs/100/job/100","status":"completed","completed_at":"2026-07-30T02:00:00Z","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
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

action_script="$(awk '
  /^      run: \|$/ {
    if (found) {
      ambiguous = 1
    }
    found = 1
    capture = 1
    next
  }
  capture {
    if ($0 != "" && $0 !~ /^        /) {
      capture = 0
      next
    }
    line = $0
    sub(/^        /, "", line)
    print line
  }
  END {
    if (!found || ambiguous) {
      exit 1
    }
  }
' "$ACTION")" || {
  echo "required-gate action must contain exactly one composite run script" >&2
  exit 1
}
# shellcheck disable=SC2016 # Reject literal GitHub expression syntax in executable shell.
if grep -Fq '${{' <<<"$action_script"; then
  echo "required-gate action run script must receive workflow context through env" >&2
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
  bash -euo pipefail -c "$action_script"
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
  bash -euo pipefail -c "$action_script" >"$output_file" 2>&1
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
run_action "$pending_count" none pending-predecessor
[[ "$(<"$pending_count")" == "2" ]] || {
  echo "required-gate action did not poll the relevant prior run while it was finishing" >&2
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
  run_action "$non_authoritative_count" none "$non_authoritative_scenario"
  [[ "$(<"$non_authoritative_count")" == "9" ]] || {
    echo "required-gate action did not discard the unresolved ${non_authoritative_scenario} candidate after its bounded refresh attempts" >&2
    exit 1
  }
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

max_attempts="$(sed -n 's/^max_attempts=\([1-9][0-9]*\)$/\1/p' <<<"$action_script")"
[[ "$max_attempts" =~ ^[1-9][0-9]*$ ]] || {
  echo "required-gate action must define one positive max_attempts polling bound" >&2
  exit 1
}
timeout_output="$tmp_dir/timeout-output"
set +e
run_action "$tmp_dir/count-timeout" none timeout-pending >"$timeout_output" 2>&1
timeout_status=$?
set -e
[[ "$timeout_status" -ne 0 ]] || {
  echo "required-gate action accepted a predecessor that never completed" >&2
  exit 1
}
[[ "$(<"$tmp_dir/count-timeout")" == "$max_attempts" ]] || {
  echo "required-gate action did not retain its ${max_attempts}-attempt polling limit" >&2
  exit 1
}
grep -Fq 'Timed out waiting for the relevant prior' "$timeout_output" || {
  echo "required-gate action did not report its bounded polling timeout" >&2
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

echo "PR required-gate context contract checks passed"
