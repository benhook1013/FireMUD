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
# shellcheck disable=SC2016 # Assert literal action input interpolation syntax.
if ! grep -Fq 'GH_TOKEN: ${{ github.token }}' "$ACTION" ||
  ! grep -Fq 'HEAD_SHA: ${{ github.event.pull_request.head.sha }}' "$ACTION" ||
  ! grep -Fq 'REQUIRED_GATE_NAME: ${{ inputs.gate-name }}' "$ACTION"; then
  echo "required-gate action must retain the caller token, pull request head, and gate input" >&2
  exit 1
fi

while IFS='|' read -r workflow gate_job_id gate; do
  [[ -n "$workflow" ]] || continue
  path="$ROOT_DIR/.github/workflows/$workflow"
  [[ -f "$path" ]] || {
    echo "required-gate caller workflow is missing: $path" >&2
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
  grep -Fq '      contents: read' <<<"$gate_block" || {
    echo "$workflow $gate caller must retain contents: read" >&2
    exit 1
  }
  # security.yml has no changes job, so it cannot gate on change detection.
  if [[ "$workflow" != "security.yml" ]] && ! grep -Fq 'needs.changes.result' <<<"$gate_block"; then
    echo "$workflow $gate must fail closed when change detection fails" >&2
    exit 1
  fi

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
ci.yml|validation-gate|Validation Gate
security.yml|security-gate|Security Gate
license-scan.yml|license-gate|License Gate
smoke.yml|smoke-gate|Smoke Gate
codeql.yml|codeql-gate|CodeQL Gate
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
if [[ "$*" != *"/repos/${GITHUB_REPOSITORY}/commits/${HEAD_SHA}/check-runs"* ]]; then
  echo "simulated gh api call did not target the pull request head check-runs endpoint" >&2
  exit 90
fi
if [[ "$*" != *"--paginate"* || "$*" != *"--slurp"* ]]; then
  echo "simulated gh api call omitted pagination/slurp" >&2
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
[{"check_runs":[{"app":{"slug":"github-actions"},"details_url":"https://github.com/example/firemud/actions/runs/100","status":"completed","conclusion":"success","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"},{"app":{"slug":"github-actions"},"details_url":"https://github.com/example/firemud/actions/runs/101","status":"in_progress","conclusion":null,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]
JSON
    else
      cat <<'JSON'
[{"check_runs":[{"app":{"slug":"github-actions"},"details_url":"https://github.com/example/firemud/actions/runs/100","status":"completed","conclusion":"success","started_at":"2026-07-30T00:00:00Z","created_at":"2026-07-30T00:00:00Z"},{"app":{"slug":"github-actions"},"details_url":"https://github.com/example/firemud/actions/runs/101","status":"completed","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]
JSON
    fi
    ;;
  pending-predecessor)
    if [[ "$count" -eq 1 ]]; then
      cat <<'JSON'
[{"check_runs":[{"app":{"slug":"github-actions"},"details_url":"https://github.com/example/firemud/actions/runs/100","status":"in_progress","conclusion":null,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]
JSON
    else
      cat <<'JSON'
[{"check_runs":[{"app":{"slug":"github-actions"},"details_url":"https://github.com/example/firemud/actions/runs/100","status":"completed","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]
JSON
    fi
    ;;
  no-prior)
    printf '[{"check_runs":[]}]\n'
    ;;
  failed-predecessor)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"details_url":"https://github.com/example/firemud/actions/runs/100","status":"completed","conclusion":"failure","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
    ;;
  self-run-excluded)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"details_url":"https://github.com/example/firemud/actions/runs/100","status":"completed","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"},{"app":{"slug":"github-actions"},"details_url":"https://github.com/example/firemud/actions/runs/999/job/1","status":"completed","conclusion":"failure","started_at":"2026-07-30T02:00:00Z","created_at":"2026-07-30T02:00:00Z"}]}]\n'
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
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"details_url":"https://github.com/example/firemud/actions/runs/100","status":"%s","conclusion":%s,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n' "$status" "$conclusion"
    ;;
  timeout-pending)
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"details_url":"https://github.com/example/firemud/actions/runs/100","status":"waiting","conclusion":null,"started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
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
    printf '[{"check_runs":[{"app":{"slug":"github-actions"},"details_url":"https://github.com/example/firemud/actions/runs/100","status":"completed","conclusion":"success","started_at":"2026-07-30T01:00:00Z","created_at":"2026-07-30T01:00:00Z"}]}]\n'
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
  GH_RETRY_COUNT_FILE="$count_file" \
  GH_FAILURE_MODE="$failure_mode" \
  GH_SCENARIO="$scenario" \
  PATH="$tmp_dir:$PATH" \
  GITHUB_EVENT_NAME=pull_request \
  GITHUB_REPOSITORY=example/firemud \
  GITHUB_RUN_ID=999 \
  GH_TOKEN=test-token \
  HEAD_SHA=deadbeef \
  REQUIRED_GATE_NAME='Validation Gate' \
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
  HEAD_SHA="$head_sha" \
  REQUIRED_GATE_NAME='Validation Gate' \
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

successful_predecessor_count="$tmp_dir/count-successful-predecessor-preferred"
run_action "$successful_predecessor_count" none latest-pending-preferred
[[ "$(<"$successful_predecessor_count")" == "1" ]] || {
  echo "required-gate action did not preserve an existing success while a concurrent run was pending" >&2
  exit 1
}

pending_count="$tmp_dir/count-pending-predecessor"
run_action "$pending_count" none pending-predecessor
[[ "$(<"$pending_count")" == "2" ]] || {
  echo "required-gate action did not poll the relevant prior run while it was finishing" >&2
  exit 1
}

alternate_pending_count="$tmp_dir/count-alternate-pending"
run_action "$alternate_pending_count" none alternate-pending
[[ "$(<"$alternate_pending_count")" == "4" ]] || {
  echo "required-gate action did not treat all GitHub pending statuses as pending" >&2
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

no_prior_output="$tmp_dir/no-prior-output"
set +e
run_action "$tmp_dir/count-no-prior" none no-prior >"$no_prior_output" 2>&1
no_prior_status=$?
set -e
[[ "$no_prior_status" -ne 0 ]] || {
  echo "required-gate action accepted the absence of a prior completed run" >&2
  exit 1
}
[[ "$(<"$tmp_dir/count-no-prior")" == "1" ]] || {
  echo "required-gate action silently exhausted polling attempts with no prior run" >&2
  exit 1
}
grep -Fq 'No prior completed' "$no_prior_output" || {
  echo "required-gate action did not clearly report the absence of a prior completed run" >&2
  exit 1
}

context_output="$tmp_dir/context-output"
set +e
run_guard_action "$context_output" push deadbeef "$tmp_dir/count-context"
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
grep -Fxq 'Required-gate preservation requires a nonempty pull request head SHA; refusing to poll.' "$head_output" || {
  echo "required-gate action did not report the exact empty-head guard message" >&2
  exit 1
}

echo "PR required-gate context contract checks passed"
