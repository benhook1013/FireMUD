#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 "$ROOT_DIR/dev-tools/validation/check_dev_demo_summary.py" "$ROOT_DIR"
python3 "$ROOT_DIR/dev-tools/validation/test_check_dev_demo_summary.py"

mode_resolver="$ROOT_DIR/dev-tools/hosted/shared/resolve-certificate-identity-mode.py"
workflow="$ROOT_DIR/.github/workflows/dev-demo.yml"
reconciler="$ROOT_DIR/.github/workflows/dev-demo-reconciler.yml"
requester="$ROOT_DIR/dev-tools/hosted/shared/request-hosted-identity.sh"
waiter="$ROOT_DIR/dev-tools/hosted/preview/wait-for-hosted-identity.sh"
fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT

expect_mode() {
  local expected="$1"
  local fixture="$2"
  local path="$fixture_dir/values.yaml"
  printf '%s' "$fixture" >"$path"
  actual="$(python3 "$mode_resolver" "$path")"
  [[ "$actual" == "$expected" ]] || {
    echo "expected mode ${expected}, got ${actual}" >&2
    exit 1
  }
}

expect_invalid_mode() {
  local fixture="$1"
  local path="$fixture_dir/values.yaml"
  printf '%s' "$fixture" >"$path"
  if python3 "$mode_resolver" "$path" >/dev/null 2>&1; then
    echo "mode resolver accepted an invalid values document" >&2
    exit 1
  fi
}

expect_mode standalone ''
expect_mode standalone $'previewStack:\n  enabled: true\n'
expect_mode standalone $'previewStack:\n  certificateIdentity:\n    mode: standalone\n'
expect_mode hosted-controller $'previewStack:\n  certificateIdentity:\n    mode: hosted-controller\n'
expect_invalid_mode $'previewStack: [\n'
expect_invalid_mode $'previewStack:\n  certificateIdentity:\n    mode: standalone\n    mode: hosted-controller\n'
expect_invalid_mode $'previewStack:\n  certificateIdentity:\n    mode: true\n'
expect_invalid_mode $'previewStack:\n  certificateIdentity:\n    mode: controller\n'

python3 - "$workflow" "$reconciler" "$requester" "$waiter" <<'PY'
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import yaml

workflow_path, reconciler_path, requester_path, waiter_path = map(Path, sys.argv[1:])
workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
reconciler = yaml.safe_load(reconciler_path.read_text(encoding="utf-8"))
requester = requester_path.read_text(encoding="utf-8")
waiter = waiter_path.read_text(encoding="utf-8")

if workflow["concurrency"] != {
    "group": "dev-demo-${{ github.workflow }}",
    "cancel-in-progress": False,
}:
    raise SystemExit("dev-demo lifecycle must remain non-cancelling")
if reconciler["concurrency"]["cancel-in-progress"] is not False:
    raise SystemExit("dev-demo reconciler must remain non-cancelling")

plan_steps = workflow["jobs"]["dev-demo-plan"]["steps"]
expected_run_name = (
    "Develop Dev Demo Environment ${{ inputs.action || 'deploy' }} "
    "head-${{ inputs.head_sha || github.sha }}"
)
if workflow.get("run-name") != expected_run_name:
    raise SystemExit("dev-demo lifecycle lacks the immutable action/target run name")
derive_run = next(step["run"] for step in plan_steps if step.get("id") == "derive")
for required in (
    'RUNTIME_NAMESPACE="dev"',
    'RELEASE_NAME="dev"',
    '[[ "$ACTION" == "deploy" || "$ACTION" == "destroy" ]]',
    '[[ "$HEAD_SHA" =~ ^[0-9a-f]{40}$ ]]',
    '[[ -n "$IMAGE_TAG" ]]',
    '[[ "$HOSTNAME" == "dev.preview.firedevops.net" ]]',
    '[[ "$RUNTIME_NAMESPACE" == "dev" ]]',
    '[[ "$RELEASE_NAME" == "dev" ]]',
    '[[ "$TELNET_PORT" == "32016" ]]',
):
    if required not in derive_run:
        raise SystemExit(f"dev-demo plan lacks pre-mutation validation: {required}")

deploy_steps = workflow["jobs"]["dev-demo-deploy"]["steps"]
deploy_by_name = {step.get("name"): step for step in deploy_steps if isinstance(step, dict)}
deploy_names = [step.get("name") for step in deploy_steps if isinstance(step, dict)]
ordered = (
    "Record exact dev-demo runtime target",
    "Write hosted identity requester kubeconfig",
    "Apply fixed dev-demo Active request",
    "Restore dev-demo runtime kubeconfig",
    "Wait for all controller identity projections",
    "Deploy dev-demo release",
    "Wait for dev-demo runtime rollouts",
    "Wait for exact dev-demo controller readiness",
    "Validate controller-projected dev-demo identity",
    "Smoke dev-demo over TCP",
    "Summarize dev-demo access",
)
positions = [deploy_names.index(name) for name in ordered]
if positions != sorted(positions):
    raise SystemExit(f"dev-demo hosted-controller lifecycle order is invalid: {ordered}")

controller_steps = ordered[1:5] + ordered[6:9]
for name in controller_steps:
    condition = deploy_by_name[name].get("if", "")
    if "steps.certificate-identity.outputs.mode == 'hosted-controller'" not in condition:
        raise SystemExit(f"{name} is not fail-closed behind hosted-controller mode")
standalone_condition = deploy_by_name["Ensure dev-demo gRPC TLS secret exists"].get("if", "")
if "steps.certificate-identity.outputs.mode == 'standalone'" not in standalone_condition:
    raise SystemExit("standalone gRPC setup is not isolated from controller identity")
if "request-hosted-identity.sh dev-demo Active" not in deploy_by_name[
    "Apply fixed dev-demo Active request"
]["run"]:
    raise SystemExit("dev-demo activation is not the fixed-shape shared request")
if "success()" not in deploy_by_name["Smoke dev-demo over TCP"].get("if", ""):
    raise SystemExit("dev-demo smoke must not bypass an earlier identity/preflight failure")
success_condition = deploy_by_name["Summarize dev-demo access"].get("if", "")
for required in ("success()", "steps.smoke.outcome == 'success'"):
    if required not in success_condition:
        raise SystemExit(f"dev-demo success publication lacks {required}")

destroy_steps = workflow["jobs"]["dev-demo-destroy"]["steps"]
destroy_by_name = {step.get("name"): step for step in destroy_steps if isinstance(step, dict)}
destroy_names = [step.get("name") for step in destroy_steps if isinstance(step, dict)]
destroy_order = (
    "Delete dev-demo namespace and release",
    "Confirm exact dev-demo runtime NotFound",
    "Write hosted identity requester kubeconfig",
    "Apply fixed dev-demo Retired request",
    "Observe terminal dev-demo retirement and delete request",
)
destroy_positions = [destroy_names.index(name) for name in destroy_order]
if destroy_positions != sorted(destroy_positions):
    raise SystemExit(f"dev-demo retirement order is invalid: {destroy_order}")
if "--ignore-not-found" not in destroy_by_name["Confirm exact dev-demo runtime NotFound"]["run"]:
    raise SystemExit("dev-demo retirement lacks an exact runtime NotFound observation")
if "request-hosted-identity.sh dev-demo Retired" not in destroy_by_name[
    "Apply fixed dev-demo Retired request"
]["run"]:
    raise SystemExit("dev-demo retirement is not the fixed-shape shared request")

for required in (
    '[[ "$desired_state" != Active && "$desired_state" != Retired ]]',
    "apiVersion: platform.firemud.dev/v1alpha1",
    "kind: HostedEnvironmentIdentity",
    "namespace: firemud-system",
    "desiredState: ${desired_state}",
):
    if required not in requester:
        raise SystemExit(f"shared identity requester lacks {required}")

for required in (
    '"${projection_prefix}-tls|ingress|tls.crt,tls.key"',
    '"${projection_prefix}-telnet-tls|telnet|tls.crt,tls.key"',
    '"${projection_prefix}-gateway-internal-ws|gateway-internal-ws|tls.crt,tls.key,ca.crt"',
    '"${projection_prefix}-tcp-proxy-bridge|tcp-proxy-bridge|tls.crt,tls.key,ca.crt"',
    '"firemud-grpc-tls|grpc|tls.crt,tls.key,ca.crt,client.crt,client.key"',
    '.metadata.labels["firemud.dev/managed-by"] == "hosted-identity-controller"',
    '.metadata.labels["firemud.dev/identity-name"] == $identity',
    '.metadata.labels["firemud.dev/role"] == $role',
):
    if required not in waiter:
        raise SystemExit(f"projection waiter lacks {required}")
projection_waiter = waiter.split('if [[ "${1:-}" == "--retired" ]]', maxsplit=1)[0]
for required in (
    "projection_ready=false",
    "projection_ready=true",
    'if [[ "$projection_ready" != true ]]',
):
    if required not in projection_waiter:
        raise SystemExit(f"projection waiter lacks per-projection completion proof: {required}")
if projection_waiter.count("deadline=$((SECONDS + timeout_seconds))") != 1:
    raise SystemExit("projection waiter must preserve one shared deadline")

reconcile_steps = reconciler["jobs"]["reconcile-dev-demo"]["steps"]
reconcile_run = next(
    step["run"]
    for step in reconcile_steps
    if step.get("name") == "Dispatch dev-demo deploy when stale or missing"
)
for required in (
    "set -euo pipefail",
    "--ignore-not-found",
    '[[ -n "${candidate_status}" && "${candidate_status}" != completed ]]',
    '"${candidate_conclusion}" == success',
    '"${candidate_conclusion}" != success',
    "Redispatching failed dev-demo candidate",
    "max_failed_attempts=3",
    'failed_attempts >= max_failed_attempts',
    "Dev-demo retry budget exhausted",
    "automatic redispatch is stopped",
    'expected_deploy_title="Develop Dev Demo Environment deploy head-${desired_head_sha}"',
    'workflow_runs_api="repos/${GITHUB_REPOSITORY}/actions/workflows/dev-demo.yml/runs"',
    "history_not_before",
    "Dev-demo history anchor missing",
    "-f event=push",
    "-F per_page=100",
    "for nonterminal_status in requested waiting pending queued in_progress",
    ".head_sha == $head",
    ".display_title == $title",
    ".created_at >= $not_before",
    "oldest_page_created_at",
    "page_failed_attempts",
):
    if required not in reconcile_run:
        raise SystemExit(f"dev-demo reconciler lacks {required}")
if "gh run list" in reconcile_run or "--limit" in reconcile_run:
    raise SystemExit("dev-demo retry evidence must not use an evictable global run limit")
if reconcile_run.count('-f "head_sha=${desired_head_sha}"') != 1:
    raise SystemExit("only the one-result develop push anchor may use head_sha search")
if reconcile_run.count("-f branch=develop") != 1:
    raise SystemExit("only the develop push anchor may use branch search")

nonterminal_guard = '[[ -n "${candidate_status}" && "${candidate_status}" != completed ]]'
for candidate_status in ("requested", "waiting", "pending"):
    subprocess.run(
        [
            "bash",
            "-c",
            f'candidate_status="$1"; if {nonterminal_guard}; then exit 0; fi; exit 1',
            "dev-demo-status-contract",
            candidate_status,
        ],
        check=True,
    )
subprocess.run(
    [
        "bash",
        "-c",
        f'candidate_status="$1"; if {nonterminal_guard}; then exit 1; fi; exit 0',
        "dev-demo-status-contract",
        "completed",
    ],
    check=True,
)

head = "a" * 40
other_target = "b" * 40
expected_title = f"Develop Dev Demo Environment deploy head-{head}"


def select_retry_state(
    history_pages: list[list[dict[str, object]]],
    nonterminal_pages: list[list[dict[str, object]]],
    not_before: str,
) -> tuple[dict[str, object] | None, int]:
    for page in nonterminal_pages:
        active = next(
            (
                run
                for run in page
                if run["head_sha"] == head
                and run["display_title"] == expected_title
            ),
            None,
        )
        if active is not None:
            return active, 0

    candidate = None
    failures = 0
    for page in history_pages:
        exact_runs = [
            run
            for run in page
            if run["head_sha"] == head
            and run["display_title"] == expected_title
            and run["created_at"] >= not_before
        ]
        if candidate is None and exact_runs:
            candidate = exact_runs[0]
        if candidate is not None and candidate["conclusion"] == "success":
            break
        failures += sum(
            run["status"] == "completed" and run["conclusion"] != "success"
            for run in exact_runs
        )
        if failures >= 3:
            break
        if page and page[-1]["created_at"] < not_before:
            break
    return candidate, failures


anchor_time = "2026-09-09T05:00:00Z"
selected_run, failure_count = select_retry_state([[]], [], anchor_time)
if selected_run is not None or failure_count != 0:
    raise SystemExit(
        "dev-demo empty history must remain dispatchable: "
        f"candidate={selected_run!r}, failures={failure_count}"
    )

irrelevant_history = [
    {
        "id": run_id,
        "head_sha": head,
        "status": "completed",
        "conclusion": "failure",
        "created_at": "2026-09-09T05:10:00Z",
        "display_title": (
            f"Develop Dev Demo Environment destroy head-{head}"
            if run_id % 2
            else f"Develop Dev Demo Environment deploy head-{other_target}"
        ),
    }
    for run_id in range(200)
]
failed_exact_history = [
    {
        "id": 301,
        "head_sha": head,
        "status": "completed",
        "conclusion": "cancelled",
        "created_at": anchor_time,
        "display_title": expected_title,
    },
    {
        "id": 300,
        "head_sha": head,
        "status": "completed",
        "conclusion": "failure",
        "created_at": anchor_time,
        "display_title": expected_title,
    },
    {
        "id": 299,
        "head_sha": head,
        "status": "completed",
        "conclusion": "timed_out",
        "created_at": anchor_time,
        "display_title": expected_title,
    },
]
history_pages = [
    irrelevant_history[:100],
    irrelevant_history[100:],
    failed_exact_history,
]
selected_run, failure_count = select_retry_state(history_pages, [], anchor_time)
if selected_run != failed_exact_history[0] or failure_count != 3:
    raise SystemExit(
        "dev-demo pagination allowed 200 later destroy/other-target runs to reset "
        f"the exact-head budget: candidate={selected_run!r}, failures={failure_count}"
    )

newer_completed_failure = {
    "id": 401,
    "head_sha": head,
    "status": "completed",
    "conclusion": "failure",
    "created_at": "2026-09-09T05:10:00Z",
    "display_title": expected_title,
}
older_active_rerun = {
    "id": 301,
    "head_sha": head,
    "status": "in_progress",
    "conclusion": None,
    "created_at": anchor_time,
    "display_title": expected_title,
}
selected_run, failure_count = select_retry_state(
    [[newer_completed_failure]], [[older_active_rerun]], anchor_time
)
if selected_run != older_active_rerun or failure_count != 0:
    raise SystemExit(
        "dev-demo selection did not prefer an older-record active re-run over newer "
        f"completed history: candidate={selected_run!r}, failures={failure_count}"
    )

bounded_no_history = [
    [
        {
            "id": 500 + run_id,
            "head_sha": head,
            "status": "completed",
            "conclusion": "failure",
            "created_at": "2026-09-09T05:10:00Z",
            "display_title": f"Develop Dev Demo Environment destroy head-{head}",
        }
        for run_id in range(100)
    ],
    [
        {
            "id": 499,
            "head_sha": other_target,
            "status": "completed",
            "conclusion": "failure",
            "created_at": "2026-09-09T04:59:59Z",
            "display_title": expected_title,
        }
    ],
]
selected_run, failure_count = select_retry_state(
    bounded_no_history, [], anchor_time
)
if selected_run is not None or failure_count != 0:
    raise SystemExit(
        "dev-demo no-history traversal did not stop safely at the develop push anchor: "
        f"candidate={selected_run!r}, failures={failure_count}"
    )
PY

# Execute the exact reconciler workflow step against deterministic GitHub and
# Kubernetes command fixtures. The reference model above keeps the cases easy
# to read; this harness makes those cases discriminating against shell/JQ drift.
reconcile_step="$fixture_dir/reconcile-step.sh"
python3 - "$reconciler" >"$reconcile_step" <<'PY'
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
steps = workflow["jobs"]["reconcile-dev-demo"]["steps"]
print(
    next(
        step["run"]
        for step in steps
        if step.get("name") == "Dispatch dev-demo deploy when stale or missing"
    )
)
PY

stub_dir="$fixture_dir/reconcile-stubs"
mkdir -p "$stub_dir"

cat >"$stub_dir/kubectl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

declare -a positional=()
ignore_not_found=false
output_format=""
while (($# > 0)); do
  case "$1" in
    --ignore-not-found)
      [[ "$ignore_not_found" == false ]] || exit 2
      ignore_not_found=true
      shift
      ;;
    -o)
      [[ -z "$output_format" && $# -ge 2 ]] || exit 2
      output_format="$2"
      shift 2
      ;;
    *)
      positional+=("$1")
      shift
      ;;
  esac
done

expected_jsonpath='jsonpath={.metadata.annotations.firemud\.dev/last-dev-demo-head-sha}'
if [[ "${positional[*]}" != "get namespace dev" \
  || "$ignore_not_found" != true \
  || "$output_format" != "$expected_jsonpath" ]]; then
  echo "unexpected kubectl Namespace annotation lookup" >&2
  exit 2
fi
# The runtime Namespace is absent or has no aligned-head annotation.
exit 0
SH

cat >"$stub_dir/gh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

[[ "${1:-}" == api ]] || {
  echo "expected gh api invocation" >&2
  exit 2
}
shift

method="GET"
method_explicit=false
page="1"
status=""
event=""
endpoint=""
jq_filter=""
declare -a raw_fields=()
declare -a typed_fields=()
while (($# > 0)); do
  case "$1" in
    --method)
      [[ "$method_explicit" == false && $# -ge 2 ]] || exit 2
      method="$2"
      method_explicit=true
      shift 2
      ;;
    --jq)
      [[ -z "$jq_filter" && $# -ge 2 ]] || exit 2
      jq_filter="$2"
      shift 2
      ;;
    -f)
      [[ $# -ge 2 ]] || exit 2
      raw_fields+=("$2")
      case "$2" in
        event=*) event="${2#event=}" ;;
        status=*) status="${2#status=}" ;;
      esac
      shift 2
      ;;
    -F)
      [[ $# -ge 2 ]] || exit 2
      typed_fields+=("$2")
      case "$2" in
        page=*) page="${2#page=}" ;;
      esac
      shift 2
      ;;
    repos/*)
      [[ -z "$endpoint" ]] || exit 2
      endpoint="$1"
      shift
      ;;
    *)
      echo "unexpected gh argument: $1" >&2
      exit 2
      ;;
  esac
done

has_raw_field() {
  local expected="$1"
  local field
  for field in "${raw_fields[@]}"; do
    [[ "$field" == "$expected" ]] && return 0
  done
  return 1
}

has_typed_field() {
  local expected="$1"
  local field
  for field in "${typed_fields[@]}"; do
    [[ "$field" == "$expected" ]] && return 0
  done
  return 1
}

branch_endpoint="repos/${GITHUB_REPOSITORY}/branches/develop"
runs_endpoint="repos/${GITHUB_REPOSITORY}/actions/workflows/dev-demo.yml/runs"
dispatch_endpoint="repos/${GITHUB_REPOSITORY}/actions/workflows/dev-demo.yml/dispatches"

if [[ "$endpoint" == "$branch_endpoint" ]]; then
  if [[ "$method" != GET || "$method_explicit" != false \
    || "$jq_filter" != '.commit.sha' \
    || ${#raw_fields[@]} -ne 0 || ${#typed_fields[@]} -ne 0 ]]; then
    echo "unexpected gh develop branch lookup" >&2
    exit 2
  fi
  printf '%s\n' "$TEST_HEAD_SHA"
  exit 0
fi

if [[ "$endpoint" == "$dispatch_endpoint" ]]; then
  if [[ "$method" != POST || "$method_explicit" != true || -n "$jq_filter" \
    || ${#raw_fields[@]} -ne 6 || ${#typed_fields[@]} -ne 0 ]] \
    || ! has_raw_field 'ref=develop' \
    || ! has_raw_field 'inputs[action]=deploy' \
    || ! has_raw_field "inputs[image_tag]=${TEST_HEAD_SHA}" \
    || ! has_raw_field "inputs[head_sha]=${TEST_HEAD_SHA}" \
    || ! has_raw_field 'inputs[hostname]=dev.preview.firedevops.net' \
    || ! has_raw_field 'inputs[telnet_port]=32016'; then
    echo "unexpected gh dev-demo dispatch" >&2
    exit 2
  fi
  printf 'dispatch\n' >>"$TEST_GH_LOG"
  exit 0
fi

if [[ "$endpoint" != "$runs_endpoint" ]]; then
  echo "unexpected gh endpoint: $endpoint" >&2
  exit 2
fi

if [[ "$event" == push ]]; then
  if [[ "$method" != GET || "$method_explicit" != true || -n "$jq_filter" \
    || ${#raw_fields[@]} -ne 3 || ${#typed_fields[@]} -ne 1 ]] \
    || ! has_raw_field 'branch=develop' \
    || ! has_raw_field 'event=push' \
    || ! has_raw_field "head_sha=${TEST_HEAD_SHA}" \
    || ! has_typed_field 'per_page=1'; then
    echo "unexpected gh develop push anchor lookup" >&2
    exit 2
  fi
  printf 'anchor\n' >>"$TEST_GH_TRACE"
  printf '%s\n' '{"workflow_runs":[{"created_at":"2026-09-09T05:00:00Z"}]}'
  exit 0
fi

if [[ "$method" != GET || "$method_explicit" != true || -n "$jq_filter" \
  || ${#typed_fields[@]} -ne 2 ]] \
  || ! has_typed_field 'per_page=100' \
  || ! has_typed_field "page=${page}"; then
  echo "unexpected gh workflow-run page lookup" >&2
  exit 2
fi
if [[ -n "$status" ]]; then
  if [[ ${#raw_fields[@]} -ne 1 ]] || ! has_raw_field "status=${status}"; then
    echo "unexpected gh status-filtered workflow-run lookup" >&2
    exit 2
  fi
elif [[ ${#raw_fields[@]} -ne 0 ]]; then
  echo "unexpected gh unfiltered workflow-run lookup" >&2
  exit 2
fi

empty_runs() {
  printf '%s\n' '{"workflow_runs":[]}'
}

if [[ -n "$status" ]]; then
  printf 'runs status=%s page=%s\n' "$status" "$page" >>"$TEST_GH_TRACE"
  if [[ "$TEST_SCENARIO" != nonterminal-old || "$status" != requested ]]; then
    empty_runs
  elif [[ "$page" == 1 ]]; then
    jq -nc --arg other "$TEST_OTHER_HEAD_SHA" \
      '{workflow_runs:[range(0;100) as $index | {
        id:(700 + $index), head_sha:$other, status:"requested", conclusion:null,
        created_at:"2026-09-09T05:10:00Z",
        display_title:("Develop Dev Demo Environment deploy head-" + $other)
      }]}'
  elif [[ "$page" == 2 ]]; then
    : >"$TEST_ACTIVE_MARKER"
    jq -nc --arg head "$TEST_HEAD_SHA" \
      '{workflow_runs:[{
        id:699, head_sha:$head, status:"requested", conclusion:null,
        created_at:"2026-09-09T05:00:00Z",
        display_title:("Develop Dev Demo Environment deploy head-" + $head)
      }]}'
  else
    empty_runs
  fi
  exit 0
fi

case "$TEST_SCENARIO:$page" in
  empty:*)
    empty_runs
    ;;
  three-failures:1|three-failures:2)
    jq -nc --arg other "$TEST_OTHER_HEAD_SHA" --argjson page "$page" \
      '{workflow_runs:[range(0;100) as $index | {
        id:(900 - (($page - 1) * 100) - $index), head_sha:$other,
        status:"completed", conclusion:"failure",
        created_at:"2026-09-09T05:10:00Z",
        display_title:("Develop Dev Demo Environment deploy head-" + $other)
      }]}'
    ;;
  three-failures:3)
    jq -nc --arg head "$TEST_HEAD_SHA" \
      '{workflow_runs:[
        {id:700,head_sha:$head,status:"completed",conclusion:"cancelled",created_at:"2026-09-09T05:00:00Z",display_title:("Develop Dev Demo Environment deploy head-" + $head)},
        {id:699,head_sha:$head,status:"completed",conclusion:"failure",created_at:"2026-09-09T05:00:00Z",display_title:("Develop Dev Demo Environment deploy head-" + $head)},
        {id:698,head_sha:$head,status:"completed",conclusion:"timed_out",created_at:"2026-09-09T05:00:00Z",display_title:("Develop Dev Demo Environment deploy head-" + $head)}
      ]}'
    ;;
  three-failures:*)
    empty_runs
    ;;
  other-titles:1)
    jq -nc --arg head "$TEST_HEAD_SHA" --arg other "$TEST_OTHER_HEAD_SHA" \
      '{workflow_runs:[
        {id:600,head_sha:$head,status:"completed",conclusion:"failure",created_at:"2026-09-09T05:10:00Z",display_title:("Develop Dev Demo Environment destroy head-" + $head)},
        {id:599,head_sha:$head,status:"completed",conclusion:"cancelled",created_at:"2026-09-09T05:09:00Z",display_title:("Develop Dev Demo Environment destroy head-" + $head)},
        {id:598,head_sha:$head,status:"completed",conclusion:"timed_out",created_at:"2026-09-09T05:08:00Z",display_title:("Develop Dev Demo Environment destroy head-" + $head)},
        {id:597,head_sha:$other,status:"completed",conclusion:"failure",created_at:"2026-09-09T05:07:00Z",display_title:("Develop Dev Demo Environment deploy head-" + $other)},
        {id:596,head_sha:$other,status:"completed",conclusion:"cancelled",created_at:"2026-09-09T05:06:00Z",display_title:("Develop Dev Demo Environment deploy head-" + $other)},
        {id:595,head_sha:$other,status:"completed",conclusion:"timed_out",created_at:"2026-09-09T05:05:00Z",display_title:("Develop Dev Demo Environment deploy head-" + $other)}
      ]}'
    ;;
  nonterminal-old:1)
    printf 'runs status=all page=1\n' >>"$TEST_GH_TRACE"
    jq -nc --arg head "$TEST_HEAD_SHA" \
      '{workflow_runs:[{
        id:800, head_sha:$head, status:"completed", conclusion:"failure",
        created_at:"2026-09-09T05:10:00Z",
        display_title:("Develop Dev Demo Environment deploy head-" + $head)
      }]}'
    ;;
  other-titles:*|nonterminal-old:*)
    empty_runs
    ;;
  *)
    echo "unexpected fixture request: $TEST_SCENARIO page $page" >&2
    exit 2
    ;;
esac
SH

chmod +x "$reconcile_step" "$stub_dir/gh" "$stub_dir/kubectl"
test_head_sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
test_other_head_sha="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

run_reconcile_fixture() {
  local scenario="$1"
  local expected_status="$2"
  local expected_dispatches="$3"
  local expected_output="$4"
  local command_log="$fixture_dir/${scenario}-gh.log"
  local trace_log="$fixture_dir/${scenario}-gh.trace"
  local active_marker="$fixture_dir/${scenario}-active-seen"
  local output="$fixture_dir/${scenario}.out"
  local actual_status actual_dispatches
  : >"$command_log"
  : >"$trace_log"
  rm -f "$active_marker"

  set +e
  env \
    PATH="$stub_dir:$PATH" \
    GITHUB_REPOSITORY="benhook1013/FireMUD" \
    GH_TOKEN="fixture-token" \
    TEST_SCENARIO="$scenario" \
    TEST_HEAD_SHA="$test_head_sha" \
    TEST_OTHER_HEAD_SHA="$test_other_head_sha" \
    TEST_GH_LOG="$command_log" \
    TEST_GH_TRACE="$trace_log" \
    TEST_ACTIVE_MARKER="$active_marker" \
    bash "$reconcile_step" >"$output" 2>&1
  actual_status=$?
  set -e

  if [[ "$actual_status" != "$expected_status" ]]; then
    echo "reconciler fixture $scenario exited $actual_status, expected $expected_status" >&2
    cat "$output" >&2
    exit 1
  fi
  actual_dispatches="$(wc -l <"$command_log")"
  if [[ "$actual_dispatches" != "$expected_dispatches" ]]; then
    echo "reconciler fixture $scenario dispatched $actual_dispatches times, expected $expected_dispatches" >&2
    cat "$output" >&2
    exit 1
  fi
  grep -Fq -- "$expected_output" "$output" || {
    echo "reconciler fixture $scenario lacks expected output: $expected_output" >&2
    cat "$output" >&2
    exit 1
  }

  if [[ "$scenario" == nonterminal-old ]]; then
    expected_trace=$'anchor\nruns status=requested page=1\nruns status=requested page=2'
    actual_trace="$(<"$trace_log")"
    if [[ "$actual_trace" != "$expected_trace" || ! -f "$active_marker" ]]; then
      echo "reconciler fixture $scenario did not discover the active re-run before completed history" >&2
      printf 'expected trace:\n%s\nactual trace:\n%s\n' "$expected_trace" "$actual_trace" >&2
      cat "$output" >&2
      exit 1
    fi
  fi
}

run_reconcile_fixture empty 0 1 "Dispatching dev-demo deploy"
run_reconcile_fixture three-failures 1 0 "Dev-demo retry budget exhausted"
run_reconcile_fixture nonterminal-old 0 0 "already converging develop head"
run_reconcile_fixture other-titles 0 1 "Dispatching dev-demo deploy"
