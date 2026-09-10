#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 "$ROOT_DIR/dev-tools/validation/check_dev_demo_summary.py" "$ROOT_DIR"
python3 "$ROOT_DIR/dev-tools/validation/test_check_dev_demo_summary.py"

mode_resolver="$ROOT_DIR/dev-tools/hosted/shared/resolve-certificate-identity-mode.py"
workflow="$ROOT_DIR/.github/workflows/dev-demo.yml"
reconciler="$ROOT_DIR/.github/workflows/dev-demo-reconciler.yml"
reconcile_step="$ROOT_DIR/dev-tools/hosted/dev-demo/reconcile-dev-demo.sh"
requester="$ROOT_DIR/dev-tools/hosted/shared/request-hosted-identity.sh"
waiter="$ROOT_DIR/dev-tools/hosted/preview/wait-for-hosted-identity.sh"
annotator="$ROOT_DIR/dev-tools/hosted/dev-demo/annotate-dev-demo-namespace.sh"
runtime_rollout_waiter="$ROOT_DIR/dev-tools/hosted/shared/wait-for-hosted-runtime-rollouts.sh"
[[ -x "$runtime_rollout_waiter" ]] || {
  echo "$runtime_rollout_waiter must be executable" >&2
  exit 1
}
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
expect_mode standalone $'previewStack:\n'
expect_mode standalone $'previewStack: null\n'
expect_mode standalone $'previewStack:\n  enabled: true\n'
expect_mode standalone $'previewStack:\n  certificateIdentity:\n'
expect_mode standalone $'previewStack:\n  certificateIdentity: null\n'
expect_mode standalone $'previewStack:\n  certificateIdentity:\n    mode: standalone\n'
expect_mode hosted-controller $'previewStack:\n  certificateIdentity:\n    mode: hosted-controller\n'
expect_mode standalone $'defaults: &defaults\n  certificateIdentity:\n    mode: hosted-controller\npreviewStack:\n  <<: *defaults\n  certificateIdentity:\n'
expect_mode hosted-controller $'defaults: &defaults\n  certificateIdentity:\n    mode: hosted-controller\npreviewStack:\n  <<: *defaults\n'
expect_invalid_mode $'previewStack: [\n'
expect_invalid_mode $'previewStack: []\n'
expect_invalid_mode $'previewStack: true\n'
expect_invalid_mode $'previewStack:\n  certificateIdentity: []\n'
expect_invalid_mode $'previewStack:\n  certificateIdentity: true\n'
expect_invalid_mode $'previewStack:\n  certificateIdentity:\n    mode: standalone\n    mode: hosted-controller\n'
expect_invalid_mode $'previewStack:\n  certificateIdentity:\n    mode: true\n'
expect_invalid_mode $'previewStack:\n  certificateIdentity:\n    mode: controller\n'

python3 - "$workflow" "$reconciler" "$requester" "$waiter" "$annotator" "$reconcile_step" <<'PY'
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

workflow_path, reconciler_path, requester_path, waiter_path, annotator_path, reconcile_script_path = map(
    Path, sys.argv[1:]
)
workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
reconciler = yaml.safe_load(reconciler_path.read_text(encoding="utf-8"))
requester = requester_path.read_text(encoding="utf-8")
waiter = waiter_path.read_text(encoding="utf-8")
annotator = annotator_path.read_text(encoding="utf-8")
reconcile_script = reconcile_script_path.read_text(encoding="utf-8")

if workflow["concurrency"] != {
    "group": "dev-demo-${{ github.workflow }}",
    "cancel-in-progress": False,
}:
    raise SystemExit("dev-demo lifecycle must remain non-cancelling")
if workflow["jobs"]["dev-demo-deploy"]["timeout-minutes"] != 150:
    raise SystemExit("dev-demo deploy timeout must remain 150 minutes")
if workflow["jobs"]["dev-demo-destroy"]["timeout-minutes"] != 60:
    raise SystemExit("dev-demo destroy timeout must remain 60 minutes")
if reconciler["concurrency"]["cancel-in-progress"] is not False:
    raise SystemExit("dev-demo reconciler must remain non-cancelling")
if reconciler["jobs"]["reconcile-dev-demo"]["timeout-minutes"] != 9:
    raise SystemExit("dev-demo reconciler timeout must remain shorter than its schedule cadence")

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
    'Invalid dev-demo action',
    'Invalid dev-demo head SHA',
    'Invalid dev-demo image tag',
    'Invalid dev-demo hostname',
    'Invalid dev-demo Telnet port',
):
    if required not in derive_run:
        raise SystemExit(f"dev-demo plan lacks pre-mutation validation: {required}")
normalization = 'HEAD_SHA="${HEAD_SHA,,}"'
image_tag_default = 'IMAGE_TAG="${HEAD_SHA}"'
head_validation = '[[ ! "$HEAD_SHA" =~ ^[0-9a-f]{40}$ ]]'
head_output = 'echo "head_sha=${HEAD_SHA}"'
for required in (normalization, image_tag_default, head_validation, head_output):
    if required not in derive_run:
        raise SystemExit(f"dev-demo plan lacks normalized head handling: {required}")
if not (
    derive_run.index(normalization)
    < derive_run.index(image_tag_default)
    < derive_run.index(head_validation)
    < derive_run.index(head_output)
):
    raise SystemExit("dev-demo head normalization must precede tag derivation, validation, and output")

with tempfile.NamedTemporaryFile() as output:
    derive_fixture = derive_run.replace(
        "${{ github.event_name }}", "workflow_dispatch"
    ).replace("${{ github.sha }}", "f" * 40)
    fixture_env = os.environ.copy()
    fixture_env.update(
        {
            "INPUT_ACTION": "deploy",
            "INPUT_HEAD_SHA": "ABCDEF1234567890ABCDEF1234567890ABCDEF12",
            "INPUT_HOSTNAME": "dev.preview.firedevops.net",
            "INPUT_TELNET_PORT": "32016",
            "INPUT_IMAGE_TAG": "",
            "GITHUB_SHA": "f" * 40,
            "GITHUB_OUTPUT": output.name,
        }
    )
    subprocess.run(["bash", "-c", derive_fixture], check=True, env=fixture_env)
    output.seek(0)
    derived_outputs = dict(
        line.decode("utf-8").rstrip("\n").split("=", 1) for line in output
    )
normalized_fixture_head = "abcdef1234567890abcdef1234567890abcdef12"
if derived_outputs.get("head_sha") != normalized_fixture_head:
    raise SystemExit("dev-demo plan did not normalize an uppercase dispatch head")
if derived_outputs.get("image_tag") != normalized_fixture_head:
    raise SystemExit("dev-demo default image tag did not use the normalized dispatch head")

deploy_steps = workflow["jobs"]["dev-demo-deploy"]["steps"]
deploy_by_name = {step.get("name"): step for step in deploy_steps if isinstance(step, dict)}
deploy_names = [step.get("name") for step in deploy_steps if isinstance(step, dict)]
deploy_mode_run = deploy_by_name["Resolve certificate identity mode"]["run"]
if 'case "$mode" in' not in deploy_mode_run:
    raise SystemExit("dev-demo deploy mode output lacks an explicit allowlist")
if "standalone|hosted-controller) ;;" not in deploy_mode_run:
    raise SystemExit("dev-demo deploy mode output allowlist is incomplete")
if "Resolver output must be exactly standalone or hosted-controller." not in deploy_mode_run:
    raise SystemExit("dev-demo deploy mode output lacks a fail-closed diagnostic")
if deploy_mode_run.index('case "$mode" in') >= deploy_mode_run.index(
    "printf 'mode=%s\\n' \"$mode\" >> \"$GITHUB_OUTPUT\""
):
    raise SystemExit("dev-demo deploy mode output is published before validation")
ordered = (
    "Record exact dev-demo runtime target",
    "Write hosted identity requester kubeconfig",
    "Apply fixed dev-demo Active request",
    "Restore dev-demo runtime kubeconfig",
    "Remove hosted identity requester kubeconfig",
    "Wait for all controller identity projections",
    "Deploy dev-demo release",
    "Record exact deployed dev-demo head",
    "Wait for dev-demo runtime rollouts",
    "Wait for exact dev-demo controller readiness",
    "Validate controller-projected dev-demo identity",
    "Smoke dev-demo over TCP",
    "Summarize dev-demo access",
)
positions = [deploy_names.index(name) for name in ordered]
if positions != sorted(positions):
    raise SystemExit(f"dev-demo hosted-controller lifecycle order is invalid: {ordered}")

controller_steps = (
    "Write hosted identity requester kubeconfig",
    "Apply fixed dev-demo Active request",
    "Restore dev-demo runtime kubeconfig",
    "Wait for all controller identity projections",
    "Wait for dev-demo runtime rollouts",
    "Wait for exact dev-demo controller readiness",
    "Validate controller-projected dev-demo identity",
)
for name in controller_steps:
    condition = deploy_by_name[name].get("if", "")
    if "steps.certificate-identity.outputs.mode == 'hosted-controller'" not in condition:
        raise SystemExit(f"{name} is not fail-closed behind hosted-controller mode")
restore_condition = deploy_by_name["Restore dev-demo runtime kubeconfig"].get("if", "")
if "always()" not in restore_condition:
    raise SystemExit("dev-demo runtime kubeconfig restore must run after earlier step failures")
if "steps.cluster-access.outputs.available == 'true'" not in restore_condition:
    raise SystemExit("dev-demo runtime kubeconfig restore lost its cluster-access guard")
restore_run = deploy_by_name["Restore dev-demo runtime kubeconfig"]["run"]
if '[[ -z "${DEV_DEMO_RUNTIME_KUBECONFIG:-}" ]]' not in restore_run:
    raise SystemExit("dev-demo runtime kubeconfig restore does not reject an uninitialized path")
if restore_run.index('[[ -z "${DEV_DEMO_RUNTIME_KUBECONFIG:-}" ]]') >= restore_run.index(
    'echo "KUBECONFIG=$DEV_DEMO_RUNTIME_KUBECONFIG" >> "$GITHUB_ENV"'
):
    raise SystemExit("dev-demo runtime kubeconfig restore publishes the path before validation")
deploy_requester_cleanup = deploy_by_name["Remove hosted identity requester kubeconfig"]
if deploy_requester_cleanup.get("if") != "${{ always() }}":
    raise SystemExit("dev-demo deploy requester credential cleanup must run after failures")
if deploy_requester_cleanup.get("run") != (
    'rm -f -- "$RUNNER_TEMP/hosted-identity-requester.kubeconfig"'
):
    raise SystemExit("dev-demo deploy requester credential cleanup targets the wrong file")
standalone_condition = deploy_by_name["Ensure dev-demo gRPC TLS secret exists"].get("if", "")
if "steps.certificate-identity.outputs.mode == 'standalone'" not in standalone_condition:
    raise SystemExit("standalone gRPC setup is not isolated from controller identity")
if "request-hosted-identity.sh dev-demo Active" not in deploy_by_name[
    "Apply fixed dev-demo Active request"
]["run"]:
    raise SystemExit("dev-demo activation is not the fixed-shape shared request")
runtime_rollout_call = (
    'bash ./dev-tools/hosted/shared/wait-for-hosted-runtime-rollouts.sh'
)
runtime_rollout_run = deploy_by_name["Wait for dev-demo runtime rollouts"]["run"]
if runtime_rollout_run.count(runtime_rollout_call) != 1:
    raise SystemExit("dev-demo rollout wait must use the shared runtime inventory helper")
if '"$RUNTIME_NAMESPACE" 120' not in runtime_rollout_run:
    raise SystemExit("dev-demo rollout wait must preserve its namespace and timeout")
if "for deployment in" in runtime_rollout_run or "rollout status" in runtime_rollout_run:
    raise SystemExit("dev-demo rollout wait must not duplicate the shared inventory")
if "firemud.dev/requested-dev-demo-head-sha=${head_sha}" not in annotator:
    raise SystemExit("pre-Helm namespace preparation lacks requested-head evidence")
if "firemud.dev/last-dev-demo-head-sha=${head_sha}" in annotator:
    raise SystemExit("pre-Helm namespace preparation falsely records deployed-head evidence")
deployed_head_step = deploy_by_name["Record exact deployed dev-demo head"]
if "steps.deploy-release.outcome == 'success'" not in deployed_head_step.get("if", ""):
    raise SystemExit("deployed-head evidence is not gated on successful Helm completion")
if "firemud.dev/last-dev-demo-head-sha=${HEAD_SHA}" not in deployed_head_step["run"]:
    raise SystemExit("successful Helm completion does not record exact deployed-head evidence")
if "success()" not in deploy_by_name["Smoke dev-demo over TCP"].get("if", ""):
    raise SystemExit("dev-demo smoke must not bypass an earlier identity/preflight failure")
success_condition = deploy_by_name["Summarize dev-demo access"].get("if", "")
for required in ("success()", "steps.smoke.outcome == 'success'"):
    if required not in success_condition:
        raise SystemExit(f"dev-demo success publication lacks {required}")

destroy_steps = workflow["jobs"]["dev-demo-destroy"]["steps"]
destroy_by_name = {step.get("name"): step for step in destroy_steps if isinstance(step, dict)}
destroy_names = [step.get("name") for step in destroy_steps if isinstance(step, dict)]
destroy_mode_run = destroy_by_name["Resolve certificate identity mode"]["run"]
if 'case "$mode" in' not in destroy_mode_run:
    raise SystemExit("dev-demo destroy mode output lacks an explicit allowlist")
if "standalone|hosted-controller) ;;" not in destroy_mode_run:
    raise SystemExit("dev-demo destroy mode output allowlist is incomplete")
if "Resolver output must be exactly standalone or hosted-controller." not in destroy_mode_run:
    raise SystemExit("dev-demo destroy mode output lacks a fail-closed diagnostic")
if destroy_mode_run.index('case "$mode" in') >= destroy_mode_run.index(
    "printf 'mode=%s\\n' \"$mode\" >> \"$GITHUB_OUTPUT\""
):
    raise SystemExit("dev-demo destroy mode output is published before validation")
destroy_order = (
    "Delete dev-demo namespace and release",
    "Confirm exact dev-demo runtime NotFound",
    "Write hosted identity requester kubeconfig",
    "Apply fixed dev-demo Retired request",
    "Observe terminal dev-demo retirement and delete request",
    "Remove hosted identity requester kubeconfig",
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
requester_cleanup = destroy_by_name["Remove hosted identity requester kubeconfig"]
if requester_cleanup.get("if") != "${{ always() }}":
    raise SystemExit("dev-demo requester credential cleanup must run after failures")
if requester_cleanup.get("run") != (
    'rm -f -- "$RUNNER_TEMP/hosted-identity-requester.kubeconfig"'
):
    raise SystemExit("dev-demo requester credential cleanup targets the wrong file")

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
    '.status.profile.requestedHeadSha // empty',
    '.status.profile.deployedHeadSha // empty',
    '.metadata.annotations["firemud.dev/requested-preview-head-sha"] // empty',
    '.metadata.annotations["firemud.dev/last-preview-head-sha"] // empty',
    '"$normalized_profile_requested_head" != "$expected_head_sha"',
    '"$normalized_profile_deployed_head" != "$expected_head_sha"',
):
    if required not in waiter:
        raise SystemExit(f"projection waiter lacks {required}")
retired_mode_marker = 'if [[ "${1:-}" == "--retired" ]]'
if waiter.count(retired_mode_marker) != 1:
    raise SystemExit(
        "projection waiter must contain exactly one --retired mode boundary marker"
    )
projection_waiter = waiter.split(retired_mode_marker, maxsplit=1)[0]
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
reconcile_step_run = next(
    step["run"]
    for step in reconcile_steps
    if step.get("name") == "Dispatch dev-demo deploy when stale or missing"
)
if "bash ./dev-tools/hosted/dev-demo/reconcile-dev-demo.sh" not in reconcile_step_run:
    raise SystemExit("workflow must invoke the extracted reconciler script")
reconcile_run = reconcile_script
if "command -v jq >/dev/null 2>&1" not in reconcile_run:
    raise SystemExit("dev-demo reconciler must explicitly require jq")
if "jq is required for dev-demo reconciliation." not in reconcile_run:
    raise SystemExit("dev-demo reconciler jq prerequisite lacks a clear diagnostic")
if reconcile_run.index("command -v jq") >= reconcile_run.index("jq -"):
    raise SystemExit("dev-demo reconciler uses jq before checking the prerequisite")
if '[[ -n "${GITHUB_REPOSITORY:-}" ]]' not in reconcile_run:
    raise SystemExit("dev-demo reconciler must require GITHUB_REPOSITORY")
if "GITHUB_REPOSITORY must be non-empty for dev-demo reconciliation." not in reconcile_run:
    raise SystemExit("dev-demo reconciler repository prerequisite lacks a clear diagnostic")
if reconcile_run.index('[[ -n "${GITHUB_REPOSITORY:-}" ]]') >= reconcile_run.index(
    'gh api "repos/${GITHUB_REPOSITORY}/branches/develop"'
):
    raise SystemExit("dev-demo reconciler uses GITHUB_REPOSITORY before checking it")
for required in (
    "set -euo pipefail",
    "export LC_ALL=C",
    '[[ -n "${KUBECONFIG:-}" ]]',
    "--ignore-not-found",
    '[[ -n "${candidate_status}" && "${candidate_status}" != completed ]]',
    '"${candidate_conclusion}" == success',
    '"${candidate_conclusion}" != success',
    "Redispatching failed dev-demo candidate",
    "max_failed_attempts=3",
    "max_history_pages=10",
    'failed_attempts >= max_failed_attempts',
    "Dev-demo retry budget exhausted",
    "automatic redispatch is stopped",
    'expected_deploy_title="Develop Dev Demo Environment deploy head-${desired_head_sha}"',
    'workflow_runs_api="repos/${GITHUB_REPOSITORY}/actions/workflows/dev-demo.yml/runs"',
    "history_not_before",
    "Dev-demo history bootstrap exhausted",
    "bootstrap_complete=false",
    'bootstrap_complete=true',
    '[[ "$bootstrap_complete" != true ]]',
    "Dev-demo history bootstrap invalid",
    "bootstrap_failed_attempts",
    "bootstrap_exact_run_count",
    'if (( bootstrap_page_size < 100 )); then',
    'if (( bootstrap_exact_run_count == 0 )); then',
    'history_not_before="1970-01-01T00:00:00Z"',
    "retained history is complete and dispatch may proceed",
    'if [[ "${current_head_sha}" == "${desired_head_sha}" ]]; then',
    "no retry or redispatch required",
    ".head_sha == $head and .display_title == $title",
    "No decisive exact deploy history",
    "refusing a history-blind dispatch",
    "-f event=push",
    "-F branch=develop",
    "-F per_page=100",
    '.status != "completed"',
    ".head_sha == $head",
    ".display_title == $title",
    ".created_at >= $not_before",
    "oldest_page_created_at",
    "page_failed_attempts",
    "unaligned_completed_attempts",
    "Dev-demo alignment retry budget exhausted",
    "runtime namespace remains unaligned",
    "while (( page <= max_history_pages )); do",
    "Dev-demo nonterminal history exhausted",
    "Dev-demo completed history exhausted",
    'if (( page > max_history_pages )); then',
):
    if required not in reconcile_run:
        raise SystemExit(f"dev-demo reconciler lacks {required}")
aligned_guard = 'if [[ "${current_head_sha}" == "${desired_head_sha}" ]]; then'
if reconcile_run.index(aligned_guard) > reconcile_run.index("develop_push_run="):
    raise SystemExit("dev-demo alignment must short-circuit before retry history is consumed")
if reconcile_run.index("export LC_ALL=C") > reconcile_run.index("created_at >= $not_before"):
    raise SystemExit("dev-demo reconciler must set the bytewise locale before timestamp comparisons")
if "gh run list" in reconcile_run or "--limit" in reconcile_run:
    raise SystemExit("dev-demo retry evidence must not use an evictable global run limit")
if "for nonterminal_status in" in reconcile_run:
    raise SystemExit("dev-demo active-run lookup must use one all-status traversal")
if reconcile_run.count('list_run_page "$page"') != 2:
    raise SystemExit("dev-demo active and completed history must use bounded all-status pages")
if "run_page_result" not in reconcile_run:
    raise SystemExit("dev-demo history page cache must return its result without a subshell")
if reconcile_run.count('-f "head_sha=${desired_head_sha}"') != 1:
    raise SystemExit("only the one-result develop push anchor may use head_sha search")
if reconcile_run.count("-f branch=develop") != 1:
    raise SystemExit("only the develop push anchor may use branch search")
if reconcile_run.count("-F branch=develop") != 1:
    raise SystemExit("all paginated history must remain scoped to develop")
if "-F event=workflow_dispatch" in reconcile_run:
    raise SystemExit("all paginated history must include push-triggered dev-demo runs")
if "while true" in reconcile_run.split("bootstrap_complete=false", 1)[1].split(
    'if [[ "$bootstrap_complete" != true ]]', 1
)[0]:
    raise SystemExit("dev-demo missing-anchor bootstrap must remain bounded")
if reconcile_run.count("while (( page <= max_history_pages )); do") != 2:
    raise SystemExit("both runtime history scans must enforce max_history_pages")
if reconcile_run.count('if (( page > max_history_pages )); then') != 2:
    raise SystemExit("both runtime history scans must fail closed after max_history_pages")
if sum(line.strip() == "failed_attempts=0" for line in reconcile_run.splitlines()) != 1:
    raise SystemExit("completed-history scan must initialize its own retry count")
if 'failed_attempts="${bootstrap_failed_attempts:-0}"' in reconcile_run:
    raise SystemExit("completed-history scan must not double-count bootstrap retry usage")

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


def bootstrap_history(
    pages: list[list[dict[str, object]]], max_pages: int = 10
):
    oldest = None
    candidate = None
    failures = 0
    for page in pages[:max_pages]:
        exact_runs = [
            run
            for run in page
            if run.get("head_sha") == head
            and run.get("display_title") == expected_title
        ]
        if candidate is None and exact_runs:
            candidate = exact_runs[0]
        failures += sum(
            run.get("status") == "completed"
            and run.get("conclusion") != "success"
            for run in exact_runs
        )
        if exact_runs:
            oldest = exact_runs[-1]["created_at"]
        if candidate is not None and (
            candidate.get("status") != "completed"
            or candidate.get("conclusion") == "success"
            or failures >= 3
        ):
            return oldest
        if len(page) < 100:
            return oldest
    raise RuntimeError(
        f"no decisive exact deploy history within {max_pages} pages"
    )


bootstrap_exact_success = {
    "id": 601,
    "head_sha": head,
    "status": "completed",
    "conclusion": "success",
    "created_at": "2026-09-09T05:20:00Z",
    "display_title": expected_title,
}
bootstrap_anchor = bootstrap_history([[bootstrap_exact_success]])
if bootstrap_anchor != "2026-09-09T05:20:00Z":
    raise SystemExit(
        f"bounded exact bootstrap selected the wrong timestamp: {bootstrap_anchor}"
    )

if bootstrap_history([[]]) is not None:
    raise SystemExit("empty retained history did not prove zero exact-target attempts")

full_irrelevant_page = irrelevant_history[:100]
short_final_pages = [full_irrelevant_page, irrelevant_history[100:101]]
if bootstrap_history(short_final_pages) is not None:
    raise SystemExit(
        "short final retained-history page did not prove zero exact-target attempts"
    )

full_pages = [
    [
        {
            "id": page_number * 100 + run_number,
            "head_sha": other_target,
            "status": "completed",
            "conclusion": "failure",
            "created_at": "2026-09-09T05:10:00Z",
            "display_title": expected_title,
        }
        for run_number in range(100)
    ]
    for page_number in range(10)
]
try:
    bootstrap_history(full_pages)
except RuntimeError:
    pass
else:
    raise SystemExit("bounded bootstrap accepted unrelated history at its page cap")

bootstrap_failures = failed_exact_history + irrelevant_history[:97]
bootstrap_anchor = bootstrap_history([bootstrap_failures])
if bootstrap_anchor != anchor_time:
    raise SystemExit(
        f"bounded bootstrap did not accept a complete retry budget: {bootstrap_anchor}"
    )
PY

# Execute the exact Helm and deployed-head workflow run blocks with strict
# command stubs. The static `if`/ordering assertions above establish which
# block GitHub selects; these fixtures prove the selected blocks preserve the
# required command order and mutation boundary.
deploy_release_step="$fixture_dir/deploy-release-step.sh"
record_deployed_head_step="$fixture_dir/record-deployed-head-step.sh"
python3 - "$workflow" >"$deploy_release_step" <<'PY'
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
steps = workflow["jobs"]["dev-demo-deploy"]["steps"]
run = next(step["run"] for step in steps if step.get("name") == "Deploy dev-demo release")
run = run.replace("${{ needs.dev-demo-plan.outputs.release_name }}", "dev-demo")
run = run.replace("${{ needs.dev-demo-plan.outputs.namespace }}", "dev")
print(run)
PY
python3 - "$workflow" >"$record_deployed_head_step" <<'PY'
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
steps = workflow["jobs"]["dev-demo-deploy"]["steps"]
print(
    next(
        step["run"]
        for step in steps
        if step.get("name") == "Record exact deployed dev-demo head"
    )
)
PY

deployment_stub_dir="$fixture_dir/deployment-evidence-stubs"
mkdir -p "$deployment_stub_dir"
cat >"$deployment_stub_dir/helm" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

expected=(
  upgrade --install dev-demo k8s/helm/firemud
  -f /tmp/dev-demo-values.yaml
  --namespace dev
  --wait
  --timeout 15m
)
actual=("$@")
[[ $# -eq ${#expected[@]} ]]
for index in "${!expected[@]}"; do
  [[ "${actual[$index]}" == "${expected[$index]}" ]]
done
printf 'helm-start\n' >>"${TEST_DEPLOYMENT_LOG:?}"
if [[ "${TEST_HELM_RESULT:?}" == failure ]]; then
  printf 'helm-failure\n' >>"$TEST_DEPLOYMENT_LOG"
  exit 42
fi
[[ "$TEST_HELM_RESULT" == success ]]
printf 'helm-success\n' >>"$TEST_DEPLOYMENT_LOG"
SH
cat >"$deployment_stub_dir/kubectl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 5 ]]
[[ "$1" == annotate ]]
[[ "$2" == namespace ]]
[[ "$3" == dev ]]
[[ "$4" == "firemud.dev/last-dev-demo-head-sha=${TEST_EXPECTED_HEAD:?}" ]]
[[ "$5" == --overwrite ]]
printf 'kubectl-deployed-head=%s\n' "$4" >>"${TEST_DEPLOYMENT_LOG:?}"
SH
chmod +x "$deployment_stub_dir/helm" "$deployment_stub_dir/kubectl"

run_deployment_evidence_fixture() {
  local scenario="$1"
  local deployment_log="$fixture_dir/deployment-${scenario}.log"
  local github_env="$fixture_dir/deployment-${scenario}.env"
  local head_sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  local deploy_status

  : >"$deployment_log"
  : >"$github_env"
  set +e
  (
    cd "$ROOT_DIR"
    env \
      PATH="$deployment_stub_dir:$PATH" \
      GITHUB_ENV="$github_env" \
      TEST_DEPLOYMENT_LOG="$deployment_log" \
      TEST_HELM_RESULT="$scenario" \
      bash "$deploy_release_step"
  )
  deploy_status=$?
  set -e

  if [[ "$deploy_status" -eq 0 ]]; then
    (
      cd "$ROOT_DIR"
      env \
        PATH="$deployment_stub_dir:$PATH" \
        RUNTIME_NAMESPACE=dev \
        HEAD_SHA="$head_sha" \
        TEST_DEPLOYMENT_LOG="$deployment_log" \
        TEST_EXPECTED_HEAD="$head_sha" \
        bash "$record_deployed_head_step"
    )
  fi

  if [[ "$scenario" == failure ]]; then
    [[ "$deploy_status" -eq 42 ]]
    mapfile -t actual <"$deployment_log"
    [[ "${actual[*]}" == "helm-start helm-failure" ]]
    if grep -q '^kubectl-deployed-head=' "$deployment_log"; then
      echo "failed Helm attempt recorded deployed-head evidence" >&2
      exit 1
    fi
    return
  fi

  [[ "$scenario" == success && "$deploy_status" -eq 0 ]]
  mapfile -t actual <"$deployment_log"
  [[ "${actual[*]}" == "helm-start helm-success kubectl-deployed-head=firemud.dev/last-dev-demo-head-sha=${head_sha}" ]]
  grep -Fxq 'DEV_DEMO_STAGE=deploy' "$github_env"
}

run_deployment_evidence_fixture failure
run_deployment_evidence_fixture success

# Execute the Ready waiter against strict bounded Kubernetes fixtures so each
# requested/deployed head component and the complete projection evidence are
# behaviorally required, rather than merely present as source fragments.
waiter_stub_dir="$fixture_dir/waiter-stubs"
mkdir -p "$waiter_stub_dir"
real_jq="$(command -v jq)"

cat >"$waiter_stub_dir/sleep" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 1 && "$1" == 5 ]]
/bin/sleep 0.05
SH

cat >"$waiter_stub_dir/kubectl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

if [[ $# -eq 6 && "$1" == get && "$2" == namespace && ( "$3" == dev || "$3" == pr-42 ) && "$4" == --ignore-not-found && "$5" == -o && "$6" == json ]]; then
  if [[ "$3" == dev ]]; then
    requested_annotation="firemud.dev/requested-dev-demo-head-sha"
    deployed_annotation="firemud.dev/last-dev-demo-head-sha"
  else
    requested_annotation="firemud.dev/requested-preview-head-sha"
    deployed_annotation="firemud.dev/last-preview-head-sha"
  fi
  "$REAL_JQ" -cn \
    --arg requested "${FAKE_NAMESPACE_REQUESTED_HEAD:?}" \
    --arg deployed "${FAKE_NAMESPACE_DEPLOYED_HEAD:?}" \
    --arg requested_annotation "$requested_annotation" \
    --arg deployed_annotation "$deployed_annotation" '
      {metadata:{uid:"runtime-uid",annotations:{}}}
      | if $requested == "__missing__" then .
        else .metadata.annotations[$requested_annotation] = $requested end
      | if $deployed == "__missing__" then .
        else .metadata.annotations[$deployed_annotation] = $deployed end
    '
  exit 0
fi

if [[ $# -eq 8 && "$1" == -n && "$2" == firemud-system && "$3" == get && "$4" == hostedenvironmentidentity && ( "$5" == dev-demo || "$5" == pr-42 ) && "$6" == --ignore-not-found && "$7" == -o && "$8" == json ]]; then
  revision="sha256:$(printf 'a%.0s' {1..64})"
  grpc_revision="$revision"
  if [[ "${FAKE_MISSING_ROLE:-}" == grpc ]]; then
    grpc_revision=""
  fi
  "$REAL_JQ" -cn \
    --arg requested "${FAKE_REQUESTED_HEAD:?}" \
    --arg deployed "${FAKE_DEPLOYED_HEAD:?}" \
    --arg profile_uid "${FAKE_PROFILE_UID:?}" \
    --arg revision "$revision" \
    --arg grpc_revision "$grpc_revision" '
      {
        metadata:{generation:7},
        status:{
          observedGeneration:7,
          phase:"Ready",
          conditions:[{type:"Ready",status:"True",reason:"Reconciled",message:"served"}],
          profile:{runtimeNamespaceUid:$profile_uid},
          ingress:{revision:$revision},
          telnet:{revision:$revision},
          gatewayInternalWs:{revision:$revision},
          tcpProxyBridge:{revision:$revision},
          grpc:{revision:$grpc_revision}
        }
      }
      | if $requested == "__missing__" then .
        else .status.profile.requestedHeadSha = $requested end
      | if $deployed == "__missing__" then .
        else .status.profile.deployedHeadSha = $deployed end
    '
  exit 0
fi

printf 'unexpected kubectl invocation: %q' "$1" >&2
printf ' %q' "${@:2}" >&2
printf '\n' >&2
exit 2
SH
chmod +x "$waiter_stub_dir/kubectl" "$waiter_stub_dir/sleep"

expected_waiter_head="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
mismatched_waiter_head="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

assert_waiter_rejects() {
  local name="$1"
  local namespace_requested_head="$2"
  local namespace_deployed_head="$3"
  local requested_head="$4"
  local deployed_head="$5"
  local profile_uid="$6"
  local missing_role="$7"
  local expected_message="$8"
  local output="$fixture_dir/waiter-${name}.out"
  local actual_status

  set +e
  PATH="$waiter_stub_dir:$PATH" \
    REAL_JQ="$real_jq" \
    FAKE_NAMESPACE_REQUESTED_HEAD="$namespace_requested_head" \
    FAKE_NAMESPACE_DEPLOYED_HEAD="$namespace_deployed_head" \
    FAKE_REQUESTED_HEAD="$requested_head" \
    FAKE_DEPLOYED_HEAD="$deployed_head" \
    FAKE_PROFILE_UID="$profile_uid" \
    FAKE_MISSING_ROLE="$missing_role" \
    bash "$waiter" dev-demo "$expected_waiter_head" dev 1 >"$output" 2>&1
  actual_status=$?
  set -e

  if [[ "$actual_status" -eq 0 ]]; then
    echo "waiter accepted invalid Ready fixture: ${name}" >&2
    cat "$output" >&2
    exit 1
  fi
  grep -Fq -- "$expected_message" "$output" || {
    echo "waiter rejection ${name} lacked expected evidence: ${expected_message}" >&2
    cat "$output" >&2
    exit 1
  }
}

assert_waiter_rejects \
  namespace-requested-missing __missing__ "$expected_waiter_head" \
  "$expected_waiter_head" "$expected_waiter_head" runtime-uid "" \
  "Runtime namespace dev has no canonical requested head; observed missing."
assert_waiter_rejects \
  namespace-requested-mismatch "$mismatched_waiter_head" "$expected_waiter_head" \
  "$expected_waiter_head" "$expected_waiter_head" runtime-uid "" \
  "Runtime namespace dev requested head is stale; expected ${expected_waiter_head}, observed ${mismatched_waiter_head}."
assert_waiter_rejects \
  namespace-deployed-missing "$expected_waiter_head" __missing__ \
  "$expected_waiter_head" "$expected_waiter_head" runtime-uid "" \
  "Runtime namespace dev has no canonical deployed head; observed missing."
assert_waiter_rejects \
  namespace-deployed-mismatch "$expected_waiter_head" "$mismatched_waiter_head" \
  "$expected_waiter_head" "$expected_waiter_head" runtime-uid "" \
  "Runtime namespace dev deployed head is stale; expected ${expected_waiter_head}, observed ${mismatched_waiter_head}."
assert_waiter_rejects \
  requested-missing "$expected_waiter_head" "$expected_waiter_head" \
  __missing__ "$expected_waiter_head" runtime-uid "" \
  "requested head missing, deployed head ${expected_waiter_head}"
assert_waiter_rejects \
  requested-mismatch "$expected_waiter_head" "$expected_waiter_head" \
  "$mismatched_waiter_head" "$expected_waiter_head" runtime-uid "" \
  "requested head ${mismatched_waiter_head}, deployed head ${expected_waiter_head}"
assert_waiter_rejects \
  deployed-missing "$expected_waiter_head" "$expected_waiter_head" \
  "$expected_waiter_head" __missing__ runtime-uid "" \
  "requested head ${expected_waiter_head}, deployed head missing"
assert_waiter_rejects \
  deployed-mismatch "$expected_waiter_head" "$expected_waiter_head" \
  "$expected_waiter_head" "$mismatched_waiter_head" runtime-uid "" \
  "requested head ${expected_waiter_head}, deployed head ${mismatched_waiter_head}"
assert_waiter_rejects \
  namespace-uid-mismatch "$expected_waiter_head" "$expected_waiter_head" \
  "$expected_waiter_head" "$expected_waiter_head" other-runtime-uid "" \
  "namespace UID other-runtime-uid, requested head ${expected_waiter_head}"
assert_waiter_rejects \
  projection-missing "$expected_waiter_head" "$expected_waiter_head" \
  "$expected_waiter_head" "$expected_waiter_head" runtime-uid grpc \
  "Ready identity has incomplete projected revisions"

waiter_success_output="$fixture_dir/waiter-success.out"
uppercase_waiter_head="${expected_waiter_head^^}"
PATH="$waiter_stub_dir:$PATH" \
  REAL_JQ="$real_jq" \
  FAKE_NAMESPACE_REQUESTED_HEAD="$uppercase_waiter_head" \
  FAKE_NAMESPACE_DEPLOYED_HEAD="$uppercase_waiter_head" \
  FAKE_REQUESTED_HEAD="$uppercase_waiter_head" \
  FAKE_DEPLOYED_HEAD="$uppercase_waiter_head" \
  FAKE_PROFILE_UID="runtime-uid" \
  FAKE_MISSING_ROLE="" \
  bash "$waiter" dev-demo "$expected_waiter_head" dev 1 >"$waiter_success_output"
for expected_line in \
  'identity=dev-demo' \
  'phase=Ready' \
  'observedGeneration=7' \
  'ingressRevision=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
  'telnetRevision=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
  'gatewayInternalWsRevision=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
  'tcpProxyBridgeRevision=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
  'grpcRevision=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'; do
  grep -Fxq -- "$expected_line" "$waiter_success_output"
done

assert_preview_waiter_rejects() {
  local name="$1"
  local namespace_requested_head="$2"
  local namespace_deployed_head="$3"
  local expected_message="$4"
  local output="$fixture_dir/preview-waiter-${name}.out"
  local actual_status

  set +e
  PATH="$waiter_stub_dir:$PATH" \
    REAL_JQ="$real_jq" \
    FAKE_NAMESPACE_REQUESTED_HEAD="$namespace_requested_head" \
    FAKE_NAMESPACE_DEPLOYED_HEAD="$namespace_deployed_head" \
    FAKE_REQUESTED_HEAD="$expected_waiter_head" \
    FAKE_DEPLOYED_HEAD="$expected_waiter_head" \
    FAKE_PROFILE_UID="runtime-uid" \
    FAKE_MISSING_ROLE="" \
    bash "$waiter" pr-42 "$expected_waiter_head" pr-42 1 >"$output" 2>&1
  actual_status=$?
  set -e

  if [[ "$actual_status" -eq 0 ]]; then
    echo "preview waiter accepted invalid namespace heads: ${name}" >&2
    cat "$output" >&2
    exit 1
  fi
  grep -Fq -- "$expected_message" "$output"
}

assert_preview_waiter_rejects \
  requested-missing __missing__ "$expected_waiter_head" \
  "Runtime namespace pr-42 has no canonical requested head; observed missing."
assert_preview_waiter_rejects \
  deployed-missing "$expected_waiter_head" __missing__ \
  "Runtime namespace pr-42 has no canonical deployed head; observed missing."

preview_waiter_success_output="$fixture_dir/preview-waiter-success.out"
PATH="$waiter_stub_dir:$PATH" \
  REAL_JQ="$real_jq" \
  FAKE_NAMESPACE_REQUESTED_HEAD="$uppercase_waiter_head" \
  FAKE_NAMESPACE_DEPLOYED_HEAD="$uppercase_waiter_head" \
  FAKE_REQUESTED_HEAD="$uppercase_waiter_head" \
  FAKE_DEPLOYED_HEAD="$uppercase_waiter_head" \
  FAKE_PROFILE_UID="runtime-uid" \
  FAKE_MISSING_ROLE="" \
  bash "$waiter" pr-42 "$expected_waiter_head" pr-42 1 >"$preview_waiter_success_output"
grep -Fxq -- "identity=pr-42" "$preview_waiter_success_output"

# Execute the exact reconciler workflow step against deterministic GitHub and
# Kubernetes command fixtures. The reference model above keeps the cases easy
# to read; this harness makes those cases discriminating against shell/JQ drift.
[[ -x "$reconcile_step" ]] || {
  echo "$reconcile_step must be executable" >&2
  exit 1
}

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

expected_last_jsonpath='jsonpath={.metadata.annotations.firemud\.dev/last-dev-demo-head-sha}'
expected_requested_jsonpath='jsonpath={.metadata.annotations.firemud\.dev/requested-dev-demo-head-sha}'
if [[ "${positional[*]}" == "annotate namespace dev firemud.dev/requested-dev-demo-head-sha=${TEST_HEAD_SHA:?} --overwrite" \
  && "$ignore_not_found" == false && -z "$output_format" ]]; then
  : >"${TEST_REPAIR_MARKER:?}"
  exit 0
fi
if [[ "${positional[*]}" != "get namespace dev" \
  || "$ignore_not_found" != true \
  || ("$output_format" != "$expected_last_jsonpath" \
    && "$output_format" != "$expected_requested_jsonpath") ]]; then
  echo "unexpected kubectl Namespace annotation lookup" >&2
  exit 2
fi
# The runtime Namespace is absent or has no annotation unless a fixture
# explicitly supplies the corresponding test value.
if [[ "$output_format" == "$expected_last_jsonpath" ]]; then
  printf '%s' "${TEST_CURRENT_HEAD_SHA:-}"
else
  printf '%s' "${TEST_REQUESTED_HEAD_SHA:-}"
fi
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
        branch=*) branch="${2#branch=}" ;;
        event=*) event="${2#event=}" ;;
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
  if [[ "$TEST_SCENARIO" == bootstrap-two-failures \
    || "$TEST_SCENARIO" == bootstrap-missing-created-at ]]; then
    printf '%s\n' '{"workflow_runs":[]}'
    exit 0
  fi
  printf '%s\n' '{"workflow_runs":[{"created_at":"2026-09-09T05:00:00Z"}]}'
  exit 0
fi

if [[ "$method" != GET || "$method_explicit" != true || -n "$jq_filter" \
  || ${#typed_fields[@]} -ne 3 ]] \
  || ! has_typed_field 'branch=develop' \
  || ! has_typed_field 'per_page=100' \
  || ! has_typed_field "page=${page}"; then
  echo "unexpected gh workflow-run page lookup" >&2
  exit 2
fi
[[ ${#raw_fields[@]} -eq 0 ]] || {
  echo "unexpected gh raw-field workflow-run lookup" >&2
  exit 2
}

empty_runs() {
  printf '%s\n' '{"workflow_runs":[]}'
}

printf 'runs status=all page=%s\n' "$page" >>"$TEST_GH_TRACE"

case "$TEST_SCENARIO:$page" in
  empty:*|aligned-no-record:*|aligned-request-repair:*)
    empty_runs
    ;;
  aligned-newest-failed:1)
    jq -nc --arg head "$TEST_HEAD_SHA" \
      '{workflow_runs:[{
        id:709,head_sha:$head,status:"completed",conclusion:"failure",created_at:"2026-09-09T05:03:00Z",display_title:("Develop Dev Demo Environment deploy head-" + $head)
      }]}'
    ;;
  aligned-failure-budget-repair:1)
    jq -nc --arg head "$TEST_HEAD_SHA" \
      '{workflow_runs:[
        {id:712,head_sha:$head,status:"completed",conclusion:"failure",created_at:"2026-09-09T05:03:00Z",display_title:("Develop Dev Demo Environment deploy head-" + $head)},
        {id:711,head_sha:$head,status:"completed",conclusion:"cancelled",created_at:"2026-09-09T05:02:00Z",display_title:("Develop Dev Demo Environment deploy head-" + $head)},
        {id:710,head_sha:$head,status:"completed",conclusion:"timed_out",created_at:"2026-09-09T05:01:00Z",display_title:("Develop Dev Demo Environment deploy head-" + $head)}
      ]}'
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
  one-failure:1)
    jq -nc --arg head "$TEST_HEAD_SHA" \
      '{workflow_runs:[{
        id:701,head_sha:$head,status:"completed",conclusion:"failure",created_at:"2026-09-09T05:00:00Z",display_title:("Develop Dev Demo Environment deploy head-" + $head)
      }]}'
    ;;
  one-failure:*)
    empty_runs
    ;;
  bootstrap-two-failures:1)
    jq -nc --arg head "$TEST_HEAD_SHA" \
      '{workflow_runs:[
        {id:702,head_sha:$head,status:"completed",conclusion:"failure",created_at:"2026-09-09T05:00:00Z",display_title:("Develop Dev Demo Environment deploy head-" + $head)},
        {id:701,head_sha:$head,status:"completed",conclusion:"cancelled",created_at:"2026-09-09T04:59:00Z",display_title:("Develop Dev Demo Environment deploy head-" + $head)}
      ]}'
    ;;
  bootstrap-two-failures:*)
    empty_runs
    ;;
  bootstrap-missing-created-at:1)
    jq -nc --arg head "$TEST_HEAD_SHA" \
      '{workflow_runs:[{
        id:703,head_sha:$head,status:"completed",conclusion:"success",
        display_title:("Develop Dev Demo Environment deploy head-" + $head)
      }]}'
    ;;
  bootstrap-missing-created-at:*)
    empty_runs
    ;;
  successful-unaligned:1)
    jq -nc --arg head "$TEST_HEAD_SHA" \
      '{workflow_runs:[{
        id:704,head_sha:$head,status:"completed",conclusion:"success",
        created_at:"2026-09-09T05:00:00Z",
        display_title:("Develop Dev Demo Environment deploy head-" + $head)
      }]}'
    ;;
  successful-unaligned:*)
    empty_runs
    ;;
  successful-unaligned-budget:1)
    jq -nc --arg head "$TEST_HEAD_SHA" \
      '{workflow_runs:[
        {id:707,head_sha:$head,status:"completed",conclusion:"success",created_at:"2026-09-09T05:02:00Z",display_title:("Develop Dev Demo Environment deploy head-" + $head)},
        {id:706,head_sha:$head,status:"completed",conclusion:"success",created_at:"2026-09-09T05:01:00Z",display_title:("Develop Dev Demo Environment deploy head-" + $head)},
        {id:705,head_sha:$head,status:"completed",conclusion:"success",created_at:"2026-09-09T05:00:00Z",display_title:("Develop Dev Demo Environment deploy head-" + $head)}
      ]}'
    ;;
  successful-unaligned-budget:*)
    empty_runs
    ;;
  successful-aligned:1)
    jq -nc --arg head "$TEST_HEAD_SHA" \
      '{workflow_runs:[{
        id:708,head_sha:$head,status:"completed",conclusion:"success",
        created_at:"2026-09-09T05:00:00Z",
        display_title:("Develop Dev Demo Environment deploy head-" + $head)
      }]}'
    ;;
  successful-aligned:*)
    empty_runs
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
    jq -nc --arg other "$TEST_OTHER_HEAD_SHA" \
      '{workflow_runs:[range(0;100) as $index | {
        id:(700 + $index), head_sha:$other, status:"requested", conclusion:null,
        created_at:"2026-09-09T05:10:00Z",
        display_title:("Develop Dev Demo Environment deploy head-" + $other)
      }]}'
    ;;
  nonterminal-old:2)
    : >"$TEST_ACTIVE_MARKER"
    jq -nc --arg head "$TEST_HEAD_SHA" \
      '{workflow_runs:[{
        id:699, head_sha:$head, status:"requested", conclusion:null,
        event:"push",
        created_at:"2026-09-09T05:00:00Z",
        display_title:("Develop Dev Demo Environment deploy head-" + $head)
      }]}'
    ;;
  nonterminal-before-anchor:1)
    jq -nc --arg other "$TEST_OTHER_HEAD_SHA" \
      '{workflow_runs:[range(0;100) as $index | {
        id:(800 + $index), head_sha:$other, status:"requested", conclusion:null,
        created_at:"2026-09-09T04:59:00Z",
        display_title:("Develop Dev Demo Environment deploy head-" + $other)
      }]}'
    ;;
  nonterminal-before-anchor:*)
    echo "unexpected history page after anchor cutoff" >&2
    exit 2
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

chmod +x "$stub_dir/gh" "$stub_dir/kubectl"
test_head_sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
test_other_head_sha="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

missing_kubeconfig_output="$fixture_dir/reconciler-missing-kubeconfig.out"
set +e
(
  cd "$ROOT_DIR"
  env -u KUBECONFIG \
    PATH="$stub_dir:$PATH" \
    GITHUB_REPOSITORY="benhook1013/FireMUD" \
    GH_TOKEN="fixture-token" \
    bash "$reconcile_step"
) >"$missing_kubeconfig_output" 2>&1
missing_kubeconfig_status=$?
set -e
if [[ "$missing_kubeconfig_status" -eq 0 ]] || ! grep -Fq \
  "KUBECONFIG must be non-empty for dev-demo reconciliation" "$missing_kubeconfig_output"; then
  echo "reconciler did not fail closed when KUBECONFIG was missing" >&2
  cat "$missing_kubeconfig_output" >&2
  exit 1
fi

run_reconcile_fixture() {
  local scenario="$1"
  local expected_status="$2"
  local expected_dispatches="$3"
  local expected_output="$4"
  local current_head_sha="${5:-}"
  local requested_head_sha="${6:-}"
  local command_log="$fixture_dir/${scenario}-gh.log"
  local trace_log="$fixture_dir/${scenario}-gh.trace"
  local active_marker="$fixture_dir/${scenario}-active-seen"
  local repair_marker="$fixture_dir/${scenario}-repair-seen"
  local output="$fixture_dir/${scenario}.out"
  local actual_status actual_dispatches
  : >"$command_log"
  : >"$trace_log"
  rm -f "$active_marker" "$repair_marker"

  set +e
  (
    cd "$ROOT_DIR"
    env \
      PATH="$stub_dir:$PATH" \
      GITHUB_REPOSITORY="benhook1013/FireMUD" \
      GH_TOKEN="fixture-token" \
      KUBECONFIG="$fixture_dir/fake-kubeconfig" \
      TEST_SCENARIO="$scenario" \
      TEST_HEAD_SHA="$test_head_sha" \
      TEST_OTHER_HEAD_SHA="$test_other_head_sha" \
      TEST_CURRENT_HEAD_SHA="$current_head_sha" \
      TEST_REQUESTED_HEAD_SHA="$requested_head_sha" \
      TEST_GH_LOG="$command_log" \
      TEST_GH_TRACE="$trace_log" \
      TEST_ACTIVE_MARKER="$active_marker" \
      TEST_REPAIR_MARKER="$fixture_dir/${scenario}-repair-seen" \
      bash "$reconcile_step"
  ) >"$output" 2>&1
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
  if [[ "$scenario" == aligned-request-repair \
    || "$scenario" == aligned-failure-budget-repair ]]; then
    [[ -f "$repair_marker" ]] || {
      echo "reconciler did not repair stale requested-head evidence" >&2
      cat "$output" >&2
      exit 1
    }
  elif [[ -f "$repair_marker" ]]; then
    echo "reconciler unexpectedly rewrote requested-head evidence" >&2
    exit 1
  fi

  if [[ "$scenario" == nonterminal-old ]]; then
    expected_trace=$'anchor\nruns status=all page=1\nruns status=all page=2'
    actual_trace="$(<"$trace_log")"
    if [[ "$actual_trace" != "$expected_trace" || ! -f "$active_marker" ]]; then
      echo "reconciler fixture $scenario did not discover the active re-run before completed history" >&2
      printf 'expected trace:\n%s\nactual trace:\n%s\n' "$expected_trace" "$actual_trace" >&2
      cat "$output" >&2
      exit 1
    fi
  fi
  if [[ "$scenario" == nonterminal-before-anchor ]]; then
    expected_trace=$'anchor\nruns status=all page=1'
    actual_trace="$(<"$trace_log")"
    if [[ "$actual_trace" != "$expected_trace" ]]; then
      echo "reconciler fixture $scenario crossed the history anchor" >&2
      printf 'expected trace:\n%s\nactual trace:\n%s\n' "$expected_trace" "$actual_trace" >&2
      cat "$output" >&2
      exit 1
    fi
  fi
  if [[ "$scenario" == aligned-newest-failed \
    || "$scenario" == aligned-failure-budget-repair ]]; then
    [[ ! -s "$trace_log" ]] || {
      echo "reconciler fixture $scenario consumed retry history despite namespace alignment" >&2
      cat "$trace_log" >&2
      exit 1
    }
  fi
}

run_reconcile_fixture empty 0 1 "Dispatching dev-demo deploy"
run_reconcile_fixture aligned-no-record 0 0 "no retry or redispatch required" "$test_head_sha" "$test_head_sha"
run_reconcile_fixture aligned-request-repair 0 0 "Repaired missing or stale requested dev-demo head annotation" "$test_head_sha" ""
run_reconcile_fixture aligned-newest-failed 0 0 "no retry or redispatch required" "$test_head_sha" "$test_head_sha"
run_reconcile_fixture aligned-failure-budget-repair 0 0 "Repaired missing or stale requested dev-demo head annotation" "$test_head_sha" ""
run_reconcile_fixture three-failures 1 0 "Dev-demo retry budget exhausted"
run_reconcile_fixture one-failure 0 1 "Redispatching failed dev-demo candidate"
run_reconcile_fixture bootstrap-two-failures 0 1 "Redispatching failed dev-demo candidate"
run_reconcile_fixture bootstrap-missing-created-at 1 0 "Dev-demo history bootstrap invalid"
run_reconcile_fixture successful-unaligned 0 1 "Redispatching successful dev-demo candidate"
run_reconcile_fixture successful-unaligned-budget 1 0 "Dev-demo alignment retry budget exhausted"
run_reconcile_fixture successful-aligned 0 0 "no retry or redispatch required" "$test_head_sha" "$test_head_sha"
run_reconcile_fixture nonterminal-old 0 0 "already converging develop head"
run_reconcile_fixture nonterminal-before-anchor 0 1 "Dispatching dev-demo deploy"
run_reconcile_fixture other-titles 0 1 "Dispatching dev-demo deploy"
