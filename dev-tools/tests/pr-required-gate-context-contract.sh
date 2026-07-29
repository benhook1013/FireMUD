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

# shellcheck disable=SC2016 # Assert literal workflow interpolation syntax.
if ! grep -Fq '.app.slug == \"github-actions\"' "$ACTION" ||
  ! grep -Fq 'contains(\"/actions/runs/${GITHUB_RUN_ID}/\") | not' "$ACTION" ||
  ! grep -Fq -- '--paginate' "$ACTION" ||
  ! grep -Fq -- '--slurp' "$ACTION" ||
  ! grep -Fq '.[].check_runs[]?' "$ACTION" ||
  ! grep -Fq 'sort_by(.started_at // .created_at) | last' "$ACTION" ||
  ! grep -Fq 'prior_status' "$ACTION"; then
  echo "required-gate action must paginate and select the latest prior GitHub Actions check run" >&2
  exit 1
fi
# shellcheck disable=SC2016 # Assert literal workflow shell syntax.
if ! grep -Fq 'for attempt in $(seq 1 80)' "$ACTION" ||
  ! grep -Fq 'sleep 15' "$ACTION" ||
  ! grep -Fq 'Timed out waiting for the prior' "$ACTION"; then
  echo "required-gate action must retain bounded 80-attempt, 15-second polling" >&2
  exit 1
fi
# shellcheck disable=SC2016 # Assert literal retry syntax.
if ! grep -Fq 'prior_state="$(gh api' "$ACTION" ||
  ! grep -Fq 'is_retryable_gh_failure' "$ACTION" ||
  ! grep -Fq 'api_status' "$ACTION"; then
  echo "required-gate action must classify gh api failures before retrying" >&2
  exit 1
fi
grep -Fq 'Retryable GitHub API failure' "$ACTION" || {
  echo "required-gate action must report retryable gh api failures" >&2
  exit 1
}
grep -Fq 'Permanent GitHub API/configuration failure' "$ACTION" || {
  echo "required-gate action must fail immediately for permanent gh api failures" >&2
  exit 1
}
grep -Fq 'continue' "$ACTION" || {
  echo "required-gate action must continue polling after transient gh api failures" >&2
  exit 1
}
grep -Fq 'GITHUB_EVENT_NAME:-' "$ACTION" || {
  echo "required-gate action must validate pull request context before polling" >&2
  exit 1
}
grep -Fq 'nonempty pull request head SHA' "$ACTION" || {
  echo "required-gate action must validate a nonempty pull request head SHA before polling" >&2
  exit 1
}

while IFS='|' read -r workflow gate; do
  [[ -n "$workflow" ]] || continue
  path="$ROOT_DIR/.github/workflows/$workflow"
  if ! grep -Fq "    name: $gate" "$path"; then
    echo "$workflow must always emit the required $gate context" >&2
    exit 1
  fi
  if ! grep -Fq "Preserve successful required gate on metadata-only edit" "$path"; then
    echo "$workflow must preserve the required $gate context on metadata-only edits" >&2
    exit 1
  fi
  grep -Fq 'uses: ./.github/actions/preserve-required-gate' "$path" || {
    echo "$workflow must call the shared required-gate action" >&2
    exit 1
  }
  grep -Fq "gate-name: $gate" "$path" || {
    echo "$workflow must pass its required gate name to the shared action" >&2
    exit 1
  }
  # shellcheck disable=SC2016 # Assert literal polling syntax is absent from callers.
  if grep -Fq 'gh api' "$path" || grep -Fq 'for attempt in $(seq 1 80)' "$path"; then
    echo "$workflow must delegate required-gate polling to the shared action" >&2
    exit 1
  fi

  gate_block="$(awk '/^  [A-Za-z0-9_-]+:/{in_gate=0} /    name: '"$gate"'$/{in_gate=1} in_gate{print}' "$path")"
  grep -Fq '      checks: read' <<<"$gate_block" || {
    echo "$workflow $gate caller must retain checks: read" >&2
    exit 1
  }
  grep -Fq '      contents: read' <<<"$gate_block" || {
    echo "$workflow $gate caller must retain contents: read" >&2
    exit 1
  }

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
ci.yml|Validation Gate
security.yml|Security Gate
license-scan.yml|License Gate
smoke.yml|Smoke Gate
codeql.yml|CodeQL Gate
EOF

grep -Fq 'needs.changes.result' "$ROOT_DIR/.github/workflows/ci.yml" || {
  echo "Validation gate must fail closed when change detection fails" >&2
  exit 1
}
grep -Fq 'needs.changes.result' "$ROOT_DIR/.github/workflows/codeql.yml" || {
  echo "CodeQL gate must fail closed when change detection fails" >&2
  exit 1
}
grep -Fq 'needs.changes.result' "$ROOT_DIR/.github/workflows/license-scan.yml" || {
  echo "License gate must fail closed when change detection fails" >&2
  exit 1
}
grep -Fq 'needs.changes.result' "$ROOT_DIR/.github/workflows/smoke.yml" || {
  echo "Smoke gate must fail closed when change detection fails" >&2
  exit 1
}

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
if [[ "$*" != *"--paginate"* || "$*" != *"--slurp"* ]]; then
  echo "simulated gh api call omitted pagination/slurp" >&2
  exit 90
fi
count=0
if [[ -f "$count_file" ]]; then
  count="$(<"$count_file")"
fi
count=$((count + 1))
printf '%s' "$count" >"$count_file"
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
  *)
    echo "unknown simulated gh failure mode" >&2
    exit 91
    ;;
esac
printf 'completed\tsuccess\n'
EOF
cat >"$tmp_dir/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$tmp_dir/gh" "$tmp_dir/sleep"

action_script="$(awk '/^      run: \|$/{capture=1; next} capture {if ($0 != "" && $0 !~ /^        /) exit; sub(/^        /, ""); print}' "$ACTION")"
run_action() {
  local count_file="$1"
  local failure_mode="$2"
  GH_RETRY_COUNT_FILE="$count_file" \
  GH_FAILURE_MODE="$failure_mode" \
  PATH="$tmp_dir:$PATH" \
  GITHUB_EVENT_NAME=pull_request \
  GITHUB_REPOSITORY=example/firemud \
  GITHUB_RUN_ID=999 \
  GH_TOKEN=test-token \
  HEAD_SHA=deadbeef \
  REQUIRED_GATE_NAME='Validation Gate' \
  bash -euo pipefail -c "$action_script"
}

for failure_mode in transient network rate-limit; do
  count_file="$tmp_dir/count-${failure_mode}"
  run_action "$count_file" "$failure_mode"
  [[ "$(<"$count_file")" == "2" ]] || {
    echo "required-gate action did not retry simulated ${failure_mode} failure" >&2
    exit 1
  }
done

permanent_output="$tmp_dir/permanent-output"
set +e
run_action "$tmp_dir/count-permanent" permanent >"$permanent_output" 2>&1
permanent_status=$?
set -e
[[ "$permanent_status" -ne 0 ]] || {
  echo "required-gate action retried or accepted a permanent gh api failure" >&2
  exit 1
}
[[ "$(<"$tmp_dir/count-permanent")" == "1" ]] || {
  echo "required-gate action polled again after a permanent gh api failure" >&2
  exit 1
}
grep -Fq 'Permanent GitHub API/configuration failure' "$permanent_output" || {
  echo "required-gate action did not report the permanent gh api failure" >&2
  exit 1
}

context_output="$tmp_dir/context-output"
set +e
GH_RETRY_COUNT_FILE="$tmp_dir/count-context" \
PATH="$tmp_dir:$PATH" \
GITHUB_EVENT_NAME=push \
GITHUB_REPOSITORY=example/firemud \
GITHUB_RUN_ID=999 \
GH_TOKEN=test-token \
HEAD_SHA=deadbeef \
REQUIRED_GATE_NAME='Validation Gate' \
bash -euo pipefail -c "$action_script" >"$context_output" 2>&1
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

head_output="$tmp_dir/head-output"
set +e
GH_RETRY_COUNT_FILE="$tmp_dir/count-empty-head" \
PATH="$tmp_dir:$PATH" \
GITHUB_EVENT_NAME=pull_request \
GITHUB_REPOSITORY=example/firemud \
GITHUB_RUN_ID=999 \
GH_TOKEN=test-token \
HEAD_SHA="" \
REQUIRED_GATE_NAME='Validation Gate' \
bash -euo pipefail -c "$action_script" >"$head_output" 2>&1
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

echo "PR required-gate context contract checks passed"
