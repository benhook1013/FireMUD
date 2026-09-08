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

reconcile_steps = reconciler["jobs"]["reconcile-dev-demo"]["steps"]
reconcile_run = next(
    step["run"]
    for step in reconcile_steps
    if step.get("name") == "Dispatch dev-demo deploy when stale or missing"
)
for required in (
    "set -euo pipefail",
    "--ignore-not-found",
    '.headSha == $head',
    '"${candidate_status}" == queued || "${candidate_status}" == in_progress',
    '"${candidate_conclusion}" == success',
    '"${candidate_conclusion}" != success',
    "Redispatching failed dev-demo candidate",
):
    if required not in reconcile_run:
        raise SystemExit(f"dev-demo reconciler lacks {required}")
PY
