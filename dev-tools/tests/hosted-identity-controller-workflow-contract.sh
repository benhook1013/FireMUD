#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
trusted="$ROOT_DIR/.github/workflows/hosted-identity-request.yml"
preview="$ROOT_DIR/.github/workflows/preview.yml"
dev_demo="$ROOT_DIR/.github/workflows/dev-demo.yml"
runtime="$ROOT_DIR/.github/workflows/runtime-images.yml"
publisher="$ROOT_DIR/.github/workflows/publish-pr-runtime-images.yml"
kubeconfig_action="$ROOT_DIR/.github/actions/write-kubeconfig/action.yml"
helm_action="$ROOT_DIR/.github/actions/setup-helm/action.yml"
build_gradle="$ROOT_DIR/build.gradle.kts"
controller_build_gradle="$ROOT_DIR/services/hosted-environment-identity-controller/build.gradle.kts"
controller_dockerfile="$ROOT_DIR/services/hosted-environment-identity-controller/Dockerfile"
bootstrap="$ROOT_DIR/dev-tools/hosted/controller/bootstrap-hosted-identity-controller.sh"
waiter="$ROOT_DIR/dev-tools/hosted/preview/wait-for-hosted-identity.sh"
mode_resolver="$ROOT_DIR/dev-tools/hosted/shared/resolve-certificate-identity-mode.py"
requester="$ROOT_DIR/dev-tools/hosted/shared/request-hosted-identity.sh"
artifact_validator="$ROOT_DIR/dev-tools/hosted/preview/validate-preview-artifact.py"
render_preview_values="$ROOT_DIR/dev-tools/hosted/preview/render-preview-values.py"
preview_annotator="$ROOT_DIR/dev-tools/hosted/preview/annotate-preview-namespace.sh"
runtime_rollout_waiter="$ROOT_DIR/dev-tools/hosted/shared/wait-for-hosted-runtime-rollouts.sh"

contains() {
  grep -Fq -- "$2" "$1" || {
    echo "$1 must contain: $2" >&2
    exit 1
  }
}

contains "$runtime" 'hosted-environment-identity-controller'
contains "$runtime" 'services/hosted-environment-identity-controller/**'
contains "$publisher" 'hosted-environment-identity-controller'
contains "$build_gradle" '":hosted-environment-identity-controller:bootBuildImage"'
contains "$controller_build_gradle" 'archiveFileName.set("hosted-environment-identity-controller.jar")'
contains "$controller_dockerfile" 'COPY --chown=firemud:firemud --chmod=644 hosted-environment-identity-controller.jar app.jar'
if grep -Fq -- '*.jar' "$controller_dockerfile"; then
  echo "$controller_dockerfile must copy only the canonical controller artifact" >&2
  exit 1
fi

# The shared kubeconfig action is the only workflow credential-file writer.
# shellcheck disable=SC2016 # These assertions intentionally match literal action source.
for required in \
  'using: composite' \
  'umask 077' \
  'test -n "$KUBECONFIG_CONTENT"' \
  'command -v kubectl >/dev/null 2>&1' \
  'mkdir -p -- "$destination_directory"' \
  'temporary_path="$(mktemp -- "$destination_directory/.${destination_name}.XXXXXX")"' \
  'trap cleanup EXIT' \
  'kubectl --kubeconfig "$temporary_path" config view --minify >/dev/null' \
  'mv -fT -- "$temporary_path" "$KUBECONFIG_PATH"'; do
  contains "$kubeconfig_action" "$required"
done
# shellcheck disable=SC2016 # These assertions intentionally match literal action source.
for required in \
  'using: composite' \
  "helm_version='v3.20.1'" \
  "helm_sha256='0165ee4a2db012cc657381001e593e981f42aa5707acdd50658326790c9d0dc3'" \
  'RUNNER_OS' \
  'RUNNER_ARCH' \
  'RUNNER_TEMP' \
  'curl -fsSL --retry 3 --retry-delay 2 --retry-max-time 30' \
  'sha256sum --check --status' \
  'echo "$install_dir" >> "$GITHUB_PATH"' \
  'version --template' \
  'Helm version mismatch' \
  'Expected ${helm_version}, but the installed Helm binary reported ${reported_version}.'; do
  contains "$helm_action" "$required"
done
if grep -Fq -- '--retry-all-errors' "$helm_action"; then
  echo "$helm_action must not retry non-transient curl failures" >&2
  exit 1
fi
contains "$trusted" 'uses: ./.github/actions/write-kubeconfig'
if grep -Fq 'KUBECONFIG_PATH=' "$trusted"; then
  echo "$trusted must delegate protected kubeconfig writes to the shared composite action" >&2
  exit 1
fi

# Controller bootstrap stays digest-pinned, trusted-operator-only, and paused by default.
for required in \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR \
  --server-side \
  --field-manager \
  --grpc-trust-anchor-sha256 \
  'ACTIVATION_MODE="paused"' \
  'paused|observe|active' \
  '@sha256:[0-9a-f]{64}'; do
  contains "$bootstrap" "$required"
done

# Shared lifecycle helpers retain the complete controller projection boundary.
contains "$waiter" '.status.observedGeneration'
contains "$waiter" '--retired'
contains "$waiter" '--ignore-not-found'
# shellcheck disable=SC2016 # Match the literal generation comparison in waiter source.
contains "$waiter" '"$ready_generation" == "$generation"'
contains "$waiter" '.status.conditions[]? | select(.type == "Ready")'
contains "$waiter" '.status.ingress.revision'
contains "$waiter" '.status.telnet.revision'
contains "$waiter" '.status.grpc.revision'
contains "$waiter" '.status.gatewayInternalWs.revision'
contains "$waiter" '.status.tcpProxyBridge.revision'
contains "$waiter" '--projections'
contains "$waiter" 'dev-demo identity requires the dev runtime namespace'
# shellcheck disable=SC2016 # Match the literal namespace-mismatch diagnostic.
contains "$waiter" 'PR identity ${identity_name} requires matching runtime namespace ${identity_name}'
contains "$waiter" 'firemud.dev/managed-by'
contains "$waiter" 'firemud.dev/requested-preview-head-sha'
contains "$waiter" 'firemud.dev/last-preview-head-sha'
contains "$waiter" 'all_projections_ready=true'
contains "$waiter" 'projection_attempted=false'
contains "$waiter" 'tls.crt,tls.key,ca.crt,client.crt,client.key'
# shellcheck disable=SC2016 # Match literal shell source in the waiter.
contains "$waiter" 'get secret "$secret_name" --ignore-not-found -o json'
# shellcheck disable=SC2016 # Match literal shell source in the waiter.
contains "$waiter" 'kubectl get namespace "$runtime_namespace" --ignore-not-found -o json'
# shellcheck disable=SC2016 # Match literal shell source in the waiter.
contains "$waiter" 'get hostedenvironmentidentity "$identity_name" --ignore-not-found -o json'
contains "$waiter" 'Unable to determine controller projection'
contains "$waiter" 'Unable to determine runtime namespace'
contains "$waiter" 'Unable to determine HostedEnvironmentIdentity'
for phase in \
  Pending Provisioning WaitingForCertificate RuntimeAbsent Syncing Verifying \
  Blocked Degraded Retiring Retired; do
  contains "$waiter" "$phase"
done
contains "$mode_resolver" 'UniqueKeyLoader'
contains "$mode_resolver" 'ALLOWED_MODES = frozenset({"standalone", "hosted-controller"})'
# shellcheck disable=SC2016 # Match the literal desired-state interpolation in the helper.
contains "$requester" 'desiredState: ${desired_state}'
contains "$trusted" 'actions: read # Inspect the completed source workflow and its artifacts.'
contains "$trusted" 'contents: read # Check out the trusted default-branch workflow implementation.'
[[ -x "$runtime_rollout_waiter" ]] || {
  echo "$runtime_rollout_waiter must be executable" >&2
  exit 1
}
# shellcheck disable=SC2016 # These assertions intentionally match literal helper source.
contains "$runtime_rollout_waiter" 'usage: $0 <namespace> <per_deployment_timeout_seconds>'
contains "$runtime_rollout_waiter" 'per_deployment_timeout_seconds must be a positive integer'
contains "$runtime_rollout_waiter" 'This bound applies independently to each deployment rollout.'

# Dev-demo is the prerequisite's only active consumer integration. It preserves
# standalone operation and gates controller requests/waits on resolved mode.
contains "$dev_demo" 'resolve-certificate-identity-mode.py'
contains "$dev_demo" "steps.certificate-identity.outputs.mode == 'hosted-controller'"
contains "$dev_demo" 'request-hosted-identity.sh dev-demo Active'
contains "$dev_demo" 'request-hosted-identity.sh dev-demo Retired'
contains "$dev_demo" '--projections dev-demo dev 900'
contains "$dev_demo" 'wait-for-hosted-identity.sh'
contains "$dev_demo" 'ensure-grpc-tls-secret.sh'
contains "$dev_demo" "steps.certificate-identity.outputs.mode == 'standalone'"

python3 - "$trusted" "$preview" "$preview_annotator" "$dev_demo" <<'PY'
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
preview_workflow = yaml.safe_load(Path(sys.argv[2]).read_text(encoding="utf-8"))
preview_annotator = Path(sys.argv[3]).read_text(encoding="utf-8")
dev_demo_workflow = yaml.safe_load(Path(sys.argv[4]).read_text(encoding="utf-8"))
triggers = workflow.get("on", workflow.get(True))
assert list(triggers) == ["workflow_run", "pull_request_target"], triggers
assert triggers["workflow_run"] == {
    "workflows": ["PR Preview Environment"],
    "types": ["completed"],
}
assert triggers["pull_request_target"] == {"types": ["closed"]}
assert workflow["permissions"] == {
    "actions": "read",
    "contents": "read",
}

jobs = workflow["jobs"]
expected_gates = {
    "deploy-runtime": "needs.validate-target.outputs.action == 'deploy'",
    "verify-runtime": "needs.validate-target.outputs.action == 'deploy'",
    "destroy-runtime": "needs.validate-target.outputs.action == 'destroy'",
    "retire-identity": "needs.validate-target.outputs.action == 'destroy'",
}
assert set(jobs) == {"validate-target", *expected_gates}, jobs.keys()
for job_name, required_gate in expected_gates.items():
    condition = jobs[job_name].get("if", "")
    assert required_gate in condition, (job_name, condition)

validate_job = jobs["validate-target"]
assert validate_job["outputs"]["certificate_identity_mode"] == (
    "${{ steps.certificate-identity.outputs.mode }}"
)
mode_step = next(
    step for step in validate_job["steps"] if step.get("id") == "certificate-identity"
)
assert mode_step["run"].count("resolve-certificate-identity-mode.py") == 1
for job_name in ("deploy-runtime", "verify-runtime", "destroy-runtime", "retire-identity"):
    assert "certificate_identity_mode == 'hosted-controller'" in jobs[job_name]["if"], job_name
assert validate_job["if"] == (
    "${{ (github.event_name == 'workflow_run' && "
    "github.event.workflow_run.event == 'pull_request' && "
    "github.event.workflow_run.conclusion == 'success' && "
    "github.event.workflow_run.head_repository.full_name == github.repository && "
    "github.event.workflow_run.name == 'PR Preview Environment') || "
    "(github.event_name == 'pull_request_target' && "
    "github.event.action == 'closed') }}"
)
assert validate_job["permissions"] == {
    "actions": "read",
    "contents": "read",
    "pull-requests": "read",
}
assert validate_job["timeout-minutes"] == 10
assert jobs["deploy-runtime"]["permissions"] == {
    "actions": "read",
    "contents": "read",
    "issues": "write",
    "pull-requests": "read",
}
assert jobs["verify-runtime"]["permissions"] == {
    "contents": "read",
    "pull-requests": "read",
    "issues": "write",
}
assert jobs["destroy-runtime"]["permissions"] == {
    "contents": "read",
    "pull-requests": "read",
    "issues": "write",
}
assert jobs["retire-identity"]["permissions"] == {
    "contents": "read",
    "pull-requests": "read",
}
for job_name in ("verify-runtime", "destroy-runtime", "retire-identity"):
    assert jobs[job_name]["timeout-minutes"] == 60, job_name
assert jobs["deploy-runtime"]["timeout-minutes"] == 90
target_step = next(step for step in validate_job["steps"] if step.get("id") == "target")
target_script = target_step["run"]
for fragment in (
    'test "$source_path" = ',
    'expected_artifact_name="preview-render-pr-${PR_NUMBER}-${EXPECTED_HEAD_SHA}"',
    'select(.name == $name and .expired == false)',
    '[[ "$artifact_count" == 1 ]] || emit_no_action',
    '[[ "$base_ref" == main || "$base_ref" == develop ]] || emit_no_action',
    'Ignoring closed pull request because its base branch is unsupported.',
    '[[ "$state" == closed ]] || emit_no_action',
    'Ignoring closed pull request because it has been reopened.',
    '[[ "$current_head_sha" == "$EXPECTED_HEAD_SHA" ]] || emit_no_action',
    'Ignoring stale lifecycle event for an earlier PR head.',
):
    assert fragment in target_script, fragment
assert 'if [[ "$ACTION" == deploy ]]' not in target_script
source_step = next(step for step in validate_job["steps"] if step.get("id") == "source")
assert "steps.target.outputs.action == 'deploy'" in source_step["if"]
source_script = source_step["run"]
assert 'render_run_id="$SOURCE_RUN_ID"' in source_script
assert 'if [[ -z "$render_run_id" ]]' not in source_script
assert 'actions/workflows/preview.yml/runs?' not in source_script

deploy_steps = jobs["deploy-runtime"]["steps"]
deploy_by_name = {
    step.get("name"): step for step in deploy_steps if isinstance(step, dict)
}
active_request = deploy_by_name["Apply canonical Active request"]
assert active_request["run"] == (
    'bash ./dev-tools/hosted/shared/request-hosted-identity.sh "$IDENTITY_NAME" Active'
)
assert "Set up Helm" not in deploy_by_name
requested_step_index = next(
    index
    for index, step in enumerate(deploy_steps)
    if step.get("name") == "Create and annotate exact preview runtime namespace"
)
apply_step_index = next(
    index
    for index, step in enumerate(deploy_steps)
    if step.get("name") == "Apply validated PR runtime artifact"
)
deployed_step_index = next(
    index
    for index, step in enumerate(deploy_steps)
    if step.get("name") == "Record exact deployed preview head"
)
assert requested_step_index < apply_step_index < deployed_step_index
apply_step = deploy_steps[apply_step_index]
deployed_step = deploy_steps[deployed_step_index]
assert apply_step["id"] == "deploy-runtime-artifact"
assert deployed_step["if"] == "${{ steps.deploy-runtime-artifact.outcome == 'success' }}"
assert "firemud.dev/last-preview-head-sha=${HEAD_SHA}" in deployed_step["run"]
inject_step = deploy_by_name["Inject trusted allocated Telnet port"]["run"]
assert '"$RUNTIME_NAMESPACE" "$TELNET_PORT"' in inject_step
target_step = deploy_by_name["Validate trusted preview runtime target"]["run"]
assert '[[ "$RUNTIME_NAMESPACE" == "pr-${PR_NUMBER}" ]]' in target_step
assert "validate-preview-artifact.py" in target_step
assert 'runtime-target "$ARTIFACT_PATH" "$RUNTIME_NAMESPACE" "$TELNET_PORT"' in target_step
apply_run = apply_step["run"]
target_validation = (
    'runtime-target "$ARTIFACT_PATH" "$RUNTIME_NAMESPACE" "$TELNET_PORT"'
)
revalidate_target = "bash ./dev-tools/hosted/preview/revalidate-preview-deploy.sh"
assert apply_step["env"]["GH_TOKEN"] == "${{ github.token }}"
assert apply_step["env"]["PR_NUMBER"] == "${{ needs.validate-target.outputs.pr_number }}"
assert apply_step["env"]["EXPECTED_HEAD_SHA"] == "${{ needs.validate-target.outputs.head_sha }}"
assert apply_run.count(target_validation) == 1
assert apply_run.count(revalidate_target) == 2
dry_run = "kubectl apply --dry-run=server"
actual_apply = "kubectl apply --server-side"
validation_position = apply_run.index(target_validation)
first_revalidation = apply_run.index(revalidate_target)
dry_run_position = apply_run.index(dry_run)
second_revalidation = apply_run.index(revalidate_target, first_revalidation + 1)
actual_apply_position = apply_run.index(actual_apply)
assert (
    validation_position
    < first_revalidation
    < dry_run_position
    < second_revalidation
    < actual_apply_position
)
apply_lines = [line.strip() for line in apply_run.splitlines()]
dry_run_line = next(index for index, line in enumerate(apply_lines) if dry_run in line)
actual_apply_line = next(index for index, line in enumerate(apply_lines) if actual_apply in line)
assert apply_lines[dry_run_line - 2] == revalidate_target + " \\"
assert apply_lines[dry_run_line - 1] == '"$PR_NUMBER" "$EXPECTED_HEAD_SHA"'
assert apply_lines[dry_run_line + 1] == revalidate_target + " \\"
assert apply_lines[dry_run_line + 2] == '"$PR_NUMBER" "$EXPECTED_HEAD_SHA"'
assert actual_apply_line == dry_run_line + 3

deploy_failure = next(
    step
    for step in deploy_steps
    if step.get("name") == "Publish trusted preview deployment failure"
)
assert deploy_failure["if"] == "${{ !cancelled() && failure() }}"
assert deploy_failure["uses"] == "actions/github-script@3a2844b7e9c422d3c10d287c895573f7108da1b3"
assert deploy_failure["env"] == {
    "PREVIEW_PR_NUMBER": "${{ needs.validate-target.outputs.pr_number }}",
    "PREVIEW_HEAD_SHA": "${{ needs.validate-target.outputs.head_sha }}",
    "PREVIEW_IMAGE_TAG": "${{ needs.validate-target.outputs.image_tag }}",
    "PREVIEW_HOSTNAME": "${{ needs.validate-target.outputs.hostname }}",
}
deploy_failure_script = deploy_failure["with"]["script"]
for fragment in (
    'mode: "failure"',
    'markerPolicy: "replace"',
    'statePolicy: "expected-open"',
    'telnetPort: "unavailable"',
    'failureStage: "deploy-runtime"',
):
    assert fragment in deploy_failure_script, fragment

runtime_rollout_call = (
    'bash ./dev-tools/hosted/shared/wait-for-hosted-runtime-rollouts.sh'
)
for rollout_steps, step_name in (
    (deploy_steps, "Wait for runtime rollouts before operator validation"),
    (jobs["verify-runtime"]["steps"], "Wait for runtime rollouts"),
):
    rollout_step = next(step for step in rollout_steps if step.get("name") == step_name)
    assert rollout_step["run"].count(runtime_rollout_call) == 1, step_name
    assert '"$RUNTIME_NAMESPACE" 120' in rollout_step["run"], step_name
    assert "for deployment in" not in rollout_step["run"], step_name
    assert "rollout status" not in rollout_step["run"], step_name

verify_steps = jobs["verify-runtime"]["steps"]
verify_success_index = next(
    index
    for index, step in enumerate(verify_steps)
    if step.get("name") == "Publish trusted preview success"
)
verify_failure_index = next(
    index
    for index, step in enumerate(verify_steps)
    if step.get("name") == "Publish trusted preview verification failure"
)
assert verify_success_index < verify_failure_index
verify_success = verify_steps[verify_success_index]
assert verify_success["if"] == "${{ success() }}"
verify_failure = verify_steps[verify_failure_index]
assert verify_failure["if"] == "${{ !cancelled() && failure() }}"
failure_script = verify_failure["with"]["script"]
assert 'mode: "failure"' in failure_script
assert 'markerPolicy: "replace"' in failure_script
assert 'statePolicy: "expected-open"' in failure_script
assert 'telnetPort: "unavailable"' in failure_script
assert 'failureStage: "verify-runtime"' in failure_script
assert verify_failure["uses"] == verify_success["uses"]

dev_demo_steps = dev_demo_workflow["jobs"]["dev-demo-deploy"]["steps"]
dev_demo_by_name = {
    step.get("name"): step
    for step in dev_demo_steps
    if isinstance(step, dict)
}
dev_demo_render_index = next(
    index
    for index, step in enumerate(dev_demo_steps)
    if step.get("name") == "Validate dev-demo chart render"
)
dev_demo_deploy_index = next(
    index
    for index, step in enumerate(dev_demo_steps)
    if step.get("name") == "Deploy dev-demo release"
)
dev_demo_rollout_index = next(
    index
    for index, step in enumerate(dev_demo_steps)
    if step.get("name") == "Wait for dev-demo runtime rollouts"
)
dev_demo_ready_index = next(
    index
    for index, step in enumerate(dev_demo_steps)
    if step.get("name") == "Wait for exact dev-demo controller readiness"
)
dev_demo_operator_index = next(
    index
    for index, step in enumerate(dev_demo_steps)
    if step.get("name") == "Validate controller-projected dev-demo identity"
)
dev_demo_smoke_index = next(
    index
    for index, step in enumerate(dev_demo_steps)
    if step.get("name") == "Smoke dev-demo over TCP"
)
assert (
    dev_demo_render_index
    < dev_demo_deploy_index
    < dev_demo_rollout_index
    < dev_demo_ready_index
    < dev_demo_operator_index
    < dev_demo_smoke_index
)
assert dev_demo_workflow["jobs"]["dev-demo-deploy"]["timeout-minutes"] == 150
dev_demo_rollout_step = dev_demo_by_name["Wait for dev-demo runtime rollouts"]
assert dev_demo_rollout_step["run"].count(runtime_rollout_call) == 1
assert "for deployment in" not in dev_demo_rollout_step["run"]
assert "rollout status" not in dev_demo_rollout_step["run"]
dev_demo_render_step = dev_demo_by_name["Validate dev-demo chart render"]
dev_demo_render_run = dev_demo_render_step["run"]
dev_demo_preflight = dev_demo_render_run
hosted_mode_guard = (
    'if [[ "${{ steps.certificate-identity.outputs.mode }}" '
    '== "hosted-controller" ]]; then'
)
assert dev_demo_preflight.count(hosted_mode_guard) == 1
assert dev_demo_preflight.count(
    "python3 ./dev-tools/deploy/preflight.py hosted-bridge"
) == 1
preflight_start = dev_demo_preflight.index(hosted_mode_guard)
preflight_end = dev_demo_preflight.index("\nfi\n", preflight_start)
preflight_command_start = dev_demo_preflight.index(
    "FIREMUD_PREFLIGHT_CONTEXT=ci-static \\", preflight_start
)
assert preflight_start < preflight_command_start < preflight_end
preflight_lines = [line.strip() for line in dev_demo_preflight.splitlines()]
preflight_line_start = preflight_lines.index("FIREMUD_PREFLIGHT_CONTEXT=ci-static \\")
assert preflight_lines[preflight_line_start : preflight_line_start + 6] == [
    "FIREMUD_PREFLIGHT_CONTEXT=ci-static \\",
    "python3 ./dev-tools/deploy/preflight.py hosted-bridge \\",
    "/tmp/dev-demo-rendered.yaml \\",
    '"${{ needs.dev-demo-plan.outputs.namespace }}" \\',
    '"${{ needs.dev-demo-plan.outputs.release_name }}" \\',
    '--expected-hosted-telnet-node-port "${{ needs.dev-demo-plan.outputs.telnet_port }}"',
]
render_position = dev_demo_preflight.index(">/tmp/dev-demo-rendered.yaml")
dry_run_position = dev_demo_preflight.index("kubectl apply --dry-run=server")
assert render_position < preflight_command_start < dry_run_position
assert "helm upgrade --install" in dev_demo_by_name["Deploy dev-demo release"]["run"]

operator_step = dev_demo_by_name["Validate controller-projected dev-demo identity"]
assert operator_step["if"] == (
    "${{ steps.cluster-access.outputs.available == 'true' && "
    "steps.deploy-release.outcome == 'success' && "
    "steps.certificate-identity.outputs.mode == 'hosted-controller' }}"
)
operator_run = operator_step["run"]
assert "FIREMUD_PREFLIGHT_CONTEXT=operator" in operator_run
assert "python3 ./dev-tools/deploy/preflight.py hosted-bridge" in operator_run
assert '--expected-hosted-telnet-node-port "$TELNET_PORT"' in operator_run

assert preview_workflow["jobs"]["preview-deploy"]["timeout-minutes"] == 60
preview_steps = preview_workflow["jobs"]["preview-deploy"]["steps"]
preview_mode_step = next(
    step
    for step in preview_steps
    if step.get("name") == "Resolve certificate identity mode"
)
assert preview_mode_step["id"] == "certificate-identity"
assert "resolve-certificate-identity-mode.py" in preview_mode_step["run"]
preview_ensure_step = next(
    step
    for step in preview_steps
    if step.get("name") == "Ensure preview gRPC TLS secret exists"
)
assert "steps.certificate-identity.outputs.mode == 'standalone'" in preview_ensure_step["if"]
preview_namespace_step = next(
    step for step in preview_steps if step.get("name") == "Show namespace state"
)
assert 'steps.certificate-identity.outputs.mode' in preview_namespace_step["run"]
assert '== "standalone"' in preview_namespace_step["run"]
preview_requested_index = next(
    index
    for index, step in enumerate(preview_steps)
    if step.get("name") == "Record reconciled preview target"
)
preview_deploy_index = next(
    index
    for index, step in enumerate(preview_steps)
    if step.get("name") == "Deploy preview release"
)
preview_deployed_index = next(
    index
    for index, step in enumerate(preview_steps)
    if step.get("name") == "Record exact deployed preview head"
)
assert preview_requested_index < preview_deploy_index < preview_deployed_index
preview_deployed_step = preview_steps[preview_deployed_index]
assert "steps.deploy-release.outcome == 'success'" in preview_deployed_step["if"]
assert '[[ "$HEAD_SHA" =~ ^[0-9A-Fa-f]{40}$ ]]' in preview_deployed_step["run"]
assert 'head_sha="${HEAD_SHA,,}"' in preview_deployed_step["run"]
assert "firemud.dev/last-preview-head-sha=${head_sha}" in preview_deployed_step["run"]
assert "firemud.dev/last-preview-head-sha=${HEAD_SHA}" not in preview_deployed_step["run"]
assert "firemud.dev/requested-preview-head-sha=${head_sha}" in preview_annotator
assert "firemud.dev/last-preview-head-sha=${head_sha}" not in preview_annotator
assert preview_annotator.index(
    '"firemud.dev/requested-preview-head-sha=${head_sha}"'
) < preview_annotator.index('"firemud.dev/last-preview-image-tag=${image_tag}"')

projection_wait = next(
    step["run"]
    for step in deploy_steps
    if step.get("name") == "Wait for all controller identity projections"
)
projection_lines = [line.strip() for line in projection_wait.splitlines()]
assert projection_lines == [
    "bash ./dev-tools/hosted/preview/wait-for-hosted-identity.sh \\",
    '--projections "$IDENTITY_NAME" "$RUNTIME_NAMESPACE" 900',
]
assert "kubectl" not in projection_wait
assert "deadline=" not in projection_wait

retirement_wait = next(
    step["run"]
    for step in jobs["retire-identity"]["steps"]
    if step.get("name") == "Observe terminal retirement and delete request"
)
assert '--retired "$IDENTITY_NAME" 600' in retirement_wait
identity_existence = next(
    step
    for step in jobs["retire-identity"]["steps"]
    if step.get("name") == "Check HostedEnvironmentIdentity existence before retirement"
)
assert identity_existence["id"] == "identity-existence"
assert '--ignore-not-found -o json' in identity_existence["run"]
assert 'exists=false' in identity_existence["run"]
assert 'exists=true' in identity_existence["run"]
for step_name in ("Apply canonical Retired request", "Observe terminal retirement and delete request"):
    gated_step = next(
        step for step in jobs["retire-identity"]["steps"] if step.get("name") == step_name
    )
    assert gated_step["if"] == "${{ steps.identity-existence.outputs.exists == 'true' }}"
retired_request = next(
    step
    for step in jobs["retire-identity"]["steps"]
    if step.get("name") == "Apply canonical Retired request"
)
assert retired_request["run"] == (
    'bash ./dev-tools/hosted/shared/request-hosted-identity.sh "$IDENTITY_NAME" Retired'
)

credential_step = next(
    step["run"]
    for step in deploy_steps
    if step.get("name") == "Create canonical non-identity runtime credentials"
)
for fragment in (
    'read_secret_if_present firemud-secret',
    'read_secret_if_present minio-credentials',
    'read_secret_if_present jwt-signing-keys',
    'read_configmap_if_present jwt-jwks',
    'validate_secret_shape firemud-secret',
    'validate_secret_shape minio-credentials',
    'validate_secret_shape jwt-signing-keys',
    'validate_configmap_shape jwt-jwks',
    'validate_diagnostic_jwks "$signing_key_sha256"',
    '[[ "$postgres_user" != firemud ]]',
    'Recycle the disposable preview namespace before retrying',
    'postgres_password="$(openssl rand -hex 32)"',
    'asset_store_access_key="$(openssl rand -hex 16)"',
    'asset_store_secret_key="$(openssl rand -hex 32)"',
    'umask 077',
    'credential_files_dir="$(mktemp -d -- "${RUNNER_TEMP:?}/firemud-runtime-credentials.XXXXXX")"',
    'trap cleanup_credential_files EXIT',
    'chmod 700 "$credential_files_dir"',
    'chmod 600 "$credential_files_dir/$file_name"',
    '--from-file="ASSET_STORE_ACCESS_KEY=${credential_files_dir}/ASSET_STORE_ACCESS_KEY"',
    '--from-file="ASSET_STORE_SECRET_KEY=${credential_files_dir}/ASSET_STORE_SECRET_KEY"',
    '--from-file="accessKey=${credential_files_dir}/accessKey"',
    '--from-file="secretKey=${credential_files_dir}/secretKey"',
    '--from-file="current.key=${credential_files_dir}/current.key"',
    '--from-file="jwks.json=${credential_files_dir}/jwks.json"',
    'signing_key_sha256="$(printf \'%s\' "$signing_key" | sha256sum',
    'jq -nc --arg fingerprint "$signing_key_sha256"',
    'keys:[]',
    'purpose:"shared-hmac-secret-path-fingerprint"',
    'sha256:$fingerprint',
):
    assert fragment in credential_step, fragment
for forbidden in (
    '--from-literal',
    '--from-literal=FIREMUD_POSTGRES_PASSWORD=firemud',
    '--from-literal=accessKey=minio',
    '--from-literal=secretKey=minio123',
    'openssl base64',
    'kty:"oct"',
    'k:$key',
    '--arg key "$signing_key"',
):
    assert forbidden not in credential_step, forbidden
PY

fixture_signing_key="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
actual_signing_key_sha256="$(printf '%s' "$fixture_signing_key" | sha256sum | awk '{print $1}')"
diagnostic_jwks="$(
  jq -nc --arg fingerprint "$actual_signing_key_sha256" \
    '{keys:[],firemudDiagnostic:{purpose:"shared-hmac-secret-path-fingerprint",sha256:$fingerprint}}'
)"
python3 - "$fixture_signing_key" "$diagnostic_jwks" <<'PY'
import base64
import hashlib
import json
import sys

signing_key = sys.argv[1].encode("ascii")
jwks = json.loads(sys.argv[2])
expected_fingerprint = hashlib.sha256(signing_key).hexdigest()
expected_secret_jwk = base64.urlsafe_b64encode(signing_key).decode("ascii").rstrip("=")

if jwks.get("keys") != []:
    raise SystemExit("trusted hosted diagnostic JWKS unexpectedly publishes verifier keys")
if jwks.get("firemudDiagnostic") != {
    "purpose": "shared-hmac-secret-path-fingerprint",
    "sha256": expected_fingerprint,
}:
    raise SystemExit("trusted hosted diagnostic fingerprint does not match exact signing-key bytes")
serialized = json.dumps(jwks, sort_keys=True)
if sys.argv[1] in serialized or expected_secret_jwk in serialized:
    raise SystemExit("trusted hosted diagnostic JWKS publishes shared-HMAC signing material")
PY

# Execute the trusted selector with the old producer's actual boundary: a
# successful workflow run with no validated artifact. It must emit only no-op.
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

# Execute the shared rollout inventory with a strict kubectl stub. This proves
# every canonical deployment is checked in order with the caller-supplied
# namespace and timeout.
runtime_rollout_stub_dir="$TEMP_DIR/runtime-rollout-stubs"
runtime_rollout_log="$TEMP_DIR/runtime-rollouts.log"
mkdir -p "$runtime_rollout_stub_dir"
cat >"$runtime_rollout_stub_dir/kubectl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 6 ]]
[[ "$1" == -n && "$2" == "${RUNTIME_ROLLOUT_NAMESPACE:?}" ]]
[[ "$3" == rollout && "$4" == status ]]
[[ "$5" == deployment/* ]]
[[ "$6" == "--timeout=${RUNTIME_ROLLOUT_TIMEOUT:?}s" ]]
printf '%s\n' "$5" >>"${RUNTIME_ROLLOUT_LOG:?}"
SH
chmod +x "$runtime_rollout_stub_dir/kubectl"
env \
  PATH="$runtime_rollout_stub_dir:$PATH" \
  RUNTIME_ROLLOUT_NAMESPACE=pr-42 \
  RUNTIME_ROLLOUT_TIMEOUT=120 \
  RUNTIME_ROLLOUT_LOG="$runtime_rollout_log" \
  bash "$runtime_rollout_waiter" pr-42 120
expected_runtime_rollouts=$'deployment/postgres\ndeployment/redis-coord\ndeployment/redis-cache\ndeployment/minio\ndeployment/account-service\ndeployment/automation-scripting-service\ndeployment/entity-management-service\ndeployment/game-design-service\ndeployment/game-logic-service\ndeployment/game-session-service\ndeployment/logging-admin-service\ndeployment/social-groups-service\ndeployment/spring-cloud-gateway\ndeployment/tcp-proxy-service\ndeployment/world-management-service'
test "$(<"$runtime_rollout_log")" = "$expected_runtime_rollouts"

# Execute the exact credential step with a stateful Kubernetes stub. The first
# pass creates the application, MinIO, and JWT resources from random values;
# the second pass must reuse every exact stored byte without regenerating them.
credential_script="$TEMP_DIR/create-runtime-credentials.sh"
python3 - "$trusted" "$credential_script" <<'PY'
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
step = next(
    step
    for step in workflow["jobs"]["deploy-runtime"]["steps"]
    if step.get("name") == "Create canonical non-identity runtime credentials"
)
Path(sys.argv[2]).write_text(step["run"], encoding="utf-8")
PY

credential_stub_dir="$TEMP_DIR/credential-stubs"
credential_state_root="$TEMP_DIR/credential-state"
mkdir -p "$credential_stub_dir" "$credential_state_root"
cat >"$credential_stub_dir/openssl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 3 && "$1" == rand && "$2" == -hex ]]
case "$3" in
  16)
    value=11111111111111111111111111111111
    ;;
  32)
    count=0
    if [[ -f "${CREDENTIAL_OPENSSL_COUNT:?}" ]]; then
      count="$(<"$CREDENTIAL_OPENSSL_COUNT")"
    fi
    count=$((count + 1))
    printf '%s\n' "$count" >"$CREDENTIAL_OPENSSL_COUNT"
    case "$count" in
      1) value=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ;;
      2) value=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb ;;
      3) value=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc ;;
      *) value=dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd ;;
    esac
    ;;
  *)
    exit 2
    ;;
esac
printf 'rand-hex-%s\n' "$3" >>"${CREDENTIAL_OPENSSL_LOG:?}"
printf '%s\n' "$value"
SH
cat >"$credential_stub_dir/kubectl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$1" == apply ]]; then
  [[ "$2" == -f && "$3" == - ]]
  cat >/dev/null
  printf 'apply\n' >>"${CREDENTIAL_KUBECTL_LOG:?}"
  exit 0
fi

[[ "$1" == -n && "$2" == "${RUNTIME_NAMESPACE:?}" ]]
namespace="$2"
shift 2
validate_from_files() {
  local argument specification key path
  for argument in "$@"; do
    [[ "$argument" == --from-file=*=* ]]
    specification="${argument#--from-file=}"
    key="${specification%%=*}"
    path="${specification#*=}"
    [[ -n "$key" && -f "$path" ]]
    [[ "$(stat -c '%a' "$path")" == 600 ]]
    [[ "$(stat -c '%a' "$(dirname "$path")")" == 700 ]]
    printf '%s\n' "$path" >>"${CREDENTIAL_FILE_LOG:?}"
  done
}
if [[ "$1" == get && ( "$2" == secret || "$2" == configmap ) ]]; then
  [[ $# -eq 6 && "$4" == --ignore-not-found && "$5" == -o && "$6" == json ]]
  resource_name="$3"
  printf 'get %s\n' "$resource_name" >>"${CREDENTIAL_KUBECTL_LOG:?}"
  state_path="${CREDENTIAL_STATE_DIR:?}/${resource_name}.json"
  if [[ -f "$state_path" ]]; then
    cat "$state_path"
  fi
  exit 0
fi
if [[ "$1" == create && "$2" == serviceaccount ]]; then
  printf 'apiVersion: v1\nkind: ServiceAccount\nmetadata:\n  name: firemud-app\n  namespace: %s\n' "$namespace"
  exit 0
fi
if [[ "$1" == create && "$2" == configmap ]]; then
  configmap_name="$3"
  shift 3
  state_path="${CREDENTIAL_STATE_DIR:?}/${configmap_name}.json"
  [[ ! -e "$state_path" ]]
  validate_from_files "$@"
  python3 - "$state_path" "$namespace" "$configmap_name" "$@" <<'PY'
import json
import sys
from pathlib import Path

state_path = Path(sys.argv[1])
namespace = sys.argv[2]
name = sys.argv[3]
data = {}
for argument in sys.argv[4:]:
    if not argument.startswith("--from-file="):
        raise SystemExit(f"unexpected create argument: {argument}")
    key, path = argument.removeprefix("--from-file=").split("=", 1)
    data[key] = Path(path).read_text(encoding="utf-8")
state_path.write_text(
    json.dumps(
        {
            "apiVersion": "v1",
            "kind": "ConfigMap",
            "metadata": {"name": name, "namespace": namespace},
            "data": data,
        },
        sort_keys=True,
    ),
    encoding="utf-8",
)
PY
  printf 'create %s\n' "$configmap_name" >>"${CREDENTIAL_KUBECTL_LOG:?}"
  exit 0
fi
if [[ "$1" == create && "$2" == secret && "$3" == generic ]]; then
  secret_name="$4"
  shift 4
  if [[ " $* " == *" --dry-run=client "* ]]; then
    printf 'apiVersion: v1\nkind: Secret\nmetadata:\n  name: %s\n  namespace: %s\ntype: Opaque\n' \
      "$secret_name" "$namespace"
    exit 0
  fi
  state_path="${CREDENTIAL_STATE_DIR:?}/${secret_name}.json"
  [[ ! -e "$state_path" ]]
  validate_from_files "$@"
  if [[ "${CREDENTIAL_FAIL_CREATE:-}" == "$secret_name" ]]; then
    exit 42
  fi
  python3 - "$state_path" "$namespace" "$secret_name" "$@" <<'PY'
import base64
import json
import sys
from pathlib import Path

state_path = Path(sys.argv[1])
namespace = sys.argv[2]
name = sys.argv[3]
data = {}
for argument in sys.argv[4:]:
    if not argument.startswith("--from-file="):
        raise SystemExit(f"unexpected create argument: {argument}")
    key, path = argument.removeprefix("--from-file=").split("=", 1)
    data[key] = base64.b64encode(Path(path).read_bytes()).decode("ascii")
state_path.write_text(
    json.dumps(
        {
            "apiVersion": "v1",
            "kind": "Secret",
            "metadata": {"name": name, "namespace": namespace},
            "type": "Opaque",
            "data": data,
        },
        sort_keys=True,
    ),
    encoding="utf-8",
)
PY
  printf 'create %s\n' "$secret_name" >>"${CREDENTIAL_KUBECTL_LOG:?}"
  exit 0
fi

printf 'unexpected fake kubectl invocation: %s\n' "$*" >&2
exit 2
SH
chmod +x "$credential_stub_dir/openssl" "$credential_stub_dir/kubectl"

run_credential_step() {
  local state_dir="$1"
  local output="$2"
  local error="$3"
  local fail_create="${4:-}"
  env \
    PATH="$credential_stub_dir:$PATH" \
    RUNTIME_NAMESPACE=pr-42 \
    CREDENTIAL_STATE_DIR="$state_dir" \
    CREDENTIAL_OPENSSL_COUNT="$state_dir/openssl-count" \
    CREDENTIAL_OPENSSL_LOG="$state_dir/openssl.log" \
    CREDENTIAL_KUBECTL_LOG="$state_dir/kubectl.log" \
    CREDENTIAL_FILE_LOG="$state_dir/credential-files.log" \
    CREDENTIAL_FAIL_CREATE="$fail_create" \
    RUNNER_TEMP="$state_dir" \
    bash "$credential_script" >"$output" 2>"$error"
}

assert_credential_files_removed() {
  local state_dir="$1"
  local path
  if [[ ! -e "$state_dir/credential-files.log" ]]; then
    return
  fi
  while IFS= read -r path; do
    [[ ! -e "$path" ]]
    [[ ! -e "$(dirname "$path")" ]]
  done <"$state_dir/credential-files.log"
}

create_once_state="$credential_state_root/create-once"
mkdir -p "$create_once_state"
run_credential_step "$create_once_state" \
  "$TEMP_DIR/create-once.output" "$TEMP_DIR/create-once.error"
assert_credential_files_removed "$create_once_state"
python3 - "$create_once_state" <<'PY'
import base64
import hashlib
import json
import sys
from pathlib import Path

state = Path(sys.argv[1])
firemud = json.loads((state / "firemud-secret.json").read_text(encoding="utf-8"))
minio = json.loads((state / "minio-credentials.json").read_text(encoding="utf-8"))
jwt_signing = json.loads((state / "jwt-signing-keys.json").read_text(encoding="utf-8"))
jwt_jwks = json.loads((state / "jwt-jwks.json").read_text(encoding="utf-8"))
decode = lambda value: base64.b64decode(value, validate=True).decode("utf-8")
firemud_data = {key: decode(value) for key, value in firemud["data"].items()}
minio_data = {key: decode(value) for key, value in minio["data"].items()}
jwt_signing_data = {
    key: decode(value) for key, value in jwt_signing["data"].items()
}
jwks = json.loads(jwt_jwks["data"]["jwks.json"])
assert set(firemud_data) == {
    "FIREMUD_POSTGRES_USER",
    "FIREMUD_POSTGRES_PASSWORD",
    "ASSET_STORE_ACCESS_KEY",
    "ASSET_STORE_SECRET_KEY",
}
assert set(minio_data) == {"accessKey", "secretKey"}
assert firemud_data["FIREMUD_POSTGRES_USER"] == "firemud"
assert firemud_data["FIREMUD_POSTGRES_PASSWORD"] != "firemud"
assert firemud_data["ASSET_STORE_ACCESS_KEY"] == minio_data["accessKey"]
assert firemud_data["ASSET_STORE_SECRET_KEY"] == minio_data["secretKey"]
assert minio_data["accessKey"] != "minio"
assert minio_data["secretKey"] != "minio123"
assert set(jwt_signing_data) == {"current.key"}
assert len(jwt_signing_data["current.key"]) == 64
assert set(jwt_signing_data["current.key"]) <= set("0123456789abcdef")
assert jwks == {
    "keys": [],
    "firemudDiagnostic": {
        "purpose": "shared-hmac-secret-path-fingerprint",
        "sha256": hashlib.sha256(
            jwt_signing_data["current.key"].encode("ascii")
        ).hexdigest(),
    },
}
PY
firemud_before="$(sha256sum "$create_once_state/firemud-secret.json")"
minio_before="$(sha256sum "$create_once_state/minio-credentials.json")"
jwt_signing_before="$(sha256sum "$create_once_state/jwt-signing-keys.json")"
jwt_jwks_before="$(sha256sum "$create_once_state/jwt-jwks.json")"
openssl_lines_before="$(wc -l <"$create_once_state/openssl.log")"
run_credential_step "$create_once_state" \
  "$TEMP_DIR/reuse.output" "$TEMP_DIR/reuse.error"
test "$(sha256sum "$create_once_state/firemud-secret.json")" = "$firemud_before"
test "$(sha256sum "$create_once_state/minio-credentials.json")" = "$minio_before"
test "$(sha256sum "$create_once_state/jwt-signing-keys.json")" = "$jwt_signing_before"
test "$(sha256sum "$create_once_state/jwt-jwks.json")" = "$jwt_jwks_before"
test "$(wc -l <"$create_once_state/openssl.log")" -eq "$openssl_lines_before"
test "$(grep -c '^create firemud-secret$' "$create_once_state/kubectl.log")" -eq 1
test "$(grep -c '^create minio-credentials$' "$create_once_state/kubectl.log")" -eq 1
test "$(grep -c '^create jwt-signing-keys$' "$create_once_state/kubectl.log")" -eq 1
test "$(grep -c '^create jwt-jwks$' "$create_once_state/kubectl.log")" -eq 1

cleanup_failure_state="$credential_state_root/cleanup-failure"
mkdir -p "$cleanup_failure_state"
if run_credential_step "$cleanup_failure_state" \
  "$TEMP_DIR/cleanup-failure.output" "$TEMP_DIR/cleanup-failure.error" \
  minio-credentials; then
  echo "credential step unexpectedly succeeded after forced create failure" >&2
  exit 1
fi
assert_credential_files_removed "$cleanup_failure_state"
test -e "$cleanup_failure_state/firemud-secret.json"
test ! -e "$cleanup_failure_state/minio-credentials.json"
test ! -e "$cleanup_failure_state/jwt-signing-keys.json"
test ! -e "$cleanup_failure_state/jwt-jwks.json"
firemud_after_failure="$(sha256sum "$cleanup_failure_state/firemud-secret.json")"
run_credential_step "$cleanup_failure_state" \
  "$TEMP_DIR/cleanup-retry.output" "$TEMP_DIR/cleanup-retry.error"
assert_credential_files_removed "$cleanup_failure_state"
test "$(sha256sum "$cleanup_failure_state/firemud-secret.json")" = "$firemud_after_failure"
test -e "$cleanup_failure_state/minio-credentials.json"
test -e "$cleanup_failure_state/jwt-signing-keys.json"
test -e "$cleanup_failure_state/jwt-jwks.json"

partial_root="$credential_state_root/valid-partials"
firemud_only_state="$partial_root/firemud-only"
mkdir -p "$firemud_only_state"
cp \
  "$create_once_state/firemud-secret.json" \
  "$create_once_state/jwt-signing-keys.json" \
  "$create_once_state/jwt-jwks.json" \
  "$firemud_only_state/"
firemud_only_before="$(sha256sum "$firemud_only_state/firemud-secret.json")"
jwt_signing_before="$(sha256sum "$firemud_only_state/jwt-signing-keys.json")"
jwt_jwks_before="$(sha256sum "$firemud_only_state/jwt-jwks.json")"
run_credential_step "$firemud_only_state" \
  "$TEMP_DIR/firemud-only.output" "$TEMP_DIR/firemud-only.error"
test "$(sha256sum "$firemud_only_state/firemud-secret.json")" = "$firemud_only_before"
test "$(sha256sum "$firemud_only_state/jwt-signing-keys.json")" = "$jwt_signing_before"
test "$(sha256sum "$firemud_only_state/jwt-jwks.json")" = "$jwt_jwks_before"
test ! -e "$firemud_only_state/openssl.log"
test "$(grep -c '^create minio-credentials$' "$firemud_only_state/kubectl.log")" -eq 1
test "$(grep -Ec '^create (firemud-secret|jwt-signing-keys|jwt-jwks)$' "$firemud_only_state/kubectl.log" || true)" -eq 0

minio_only_state="$partial_root/minio-only"
mkdir -p "$minio_only_state"
cp \
  "$create_once_state/minio-credentials.json" \
  "$create_once_state/jwt-signing-keys.json" \
  "$create_once_state/jwt-jwks.json" \
  "$minio_only_state/"
minio_only_before="$(sha256sum "$minio_only_state/minio-credentials.json")"
jwt_signing_before="$(sha256sum "$minio_only_state/jwt-signing-keys.json")"
jwt_jwks_before="$(sha256sum "$minio_only_state/jwt-jwks.json")"
run_credential_step "$minio_only_state" \
  "$TEMP_DIR/minio-only.output" "$TEMP_DIR/minio-only.error"
test "$(sha256sum "$minio_only_state/minio-credentials.json")" = "$minio_only_before"
test "$(sha256sum "$minio_only_state/jwt-signing-keys.json")" = "$jwt_signing_before"
test "$(sha256sum "$minio_only_state/jwt-jwks.json")" = "$jwt_jwks_before"
test "$(cat "$minio_only_state/openssl.log")" = 'rand-hex-32'
test "$(grep -c '^create firemud-secret$' "$minio_only_state/kubectl.log")" -eq 1
test "$(grep -Ec '^create (minio-credentials|jwt-signing-keys|jwt-jwks)$' "$minio_only_state/kubectl.log" || true)" -eq 0
python3 - "$firemud_only_state" "$minio_only_state" <<'PY'
import base64
import json
import sys
from pathlib import Path


def data(state, name):
    document = json.loads((state / f"{name}.json").read_text(encoding="utf-8"))
    return {
        key: base64.b64decode(value, validate=True).decode("utf-8")
        for key, value in document["data"].items()
    }


for state_name in sys.argv[1:]:
    state = Path(state_name)
    firemud = data(state, "firemud-secret")
    minio = data(state, "minio-credentials")
    assert firemud["FIREMUD_POSTGRES_USER"] == "firemud"
    assert firemud["ASSET_STORE_ACCESS_KEY"] == minio["accessKey"]
    assert firemud["ASSET_STORE_SECRET_KEY"] == minio["secretKey"]
PY

jwt_secret_only_state="$partial_root/jwt-secret-only"
mkdir -p "$jwt_secret_only_state"
cp \
  "$create_once_state/firemud-secret.json" \
  "$create_once_state/minio-credentials.json" \
  "$create_once_state/jwt-signing-keys.json" \
  "$jwt_secret_only_state/"
jwt_signing_before="$(sha256sum "$jwt_secret_only_state/jwt-signing-keys.json")"
run_credential_step "$jwt_secret_only_state" \
  "$TEMP_DIR/jwt-secret-only.output" "$TEMP_DIR/jwt-secret-only.error"
test "$(sha256sum "$jwt_secret_only_state/jwt-signing-keys.json")" = "$jwt_signing_before"
test ! -e "$jwt_secret_only_state/openssl.log"
test "$(grep -c '^create jwt-jwks$' "$jwt_secret_only_state/kubectl.log")" -eq 1
test "$(sha256sum "$jwt_secret_only_state/jwt-jwks.json" | awk '{print $1}')" = \
  "$(sha256sum "$create_once_state/jwt-jwks.json" | awk '{print $1}')"
test "$(grep -Ec '^create (firemud-secret|minio-credentials|jwt-signing-keys)$' "$jwt_secret_only_state/kubectl.log" || true)" -eq 0
for partial_state in \
  "$firemud_only_state" "$minio_only_state" "$jwt_secret_only_state"; do
  python3 - "$partial_state/kubectl.log" <<'PY'
import sys
from pathlib import Path

lines = Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
first_mutation = min(
    index
    for index, line in enumerate(lines)
    if line == "apply" or line.startswith("create ")
)
for resource in (
    "firemud-secret",
    "minio-credentials",
    "jwt-signing-keys",
    "jwt-jwks",
):
    assert lines.index(f"get {resource}") < first_mutation
PY
done

rejection_root="$credential_state_root/rejections"
python3 - "$rejection_root" <<'PY'
import base64
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])


def encoded(value):
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def write_secret(directory, name, data):
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"{name}.json").write_text(
        json.dumps(
            {
                "apiVersion": "v1",
                "kind": "Secret",
                "metadata": {"name": name, "namespace": "pr-42"},
                "type": "Opaque",
                "data": data,
            },
            sort_keys=True,
        ),
        encoding="utf-8",
    )


valid_firemud = {
    "FIREMUD_POSTGRES_USER": encoded("firemud"),
    "FIREMUD_POSTGRES_PASSWORD": encoded("strong-postgres-password"),
    "ASSET_STORE_ACCESS_KEY": encoded("strong-minio-access"),
    "ASSET_STORE_SECRET_KEY": encoded("strong-minio-secret"),
}
valid_minio = {
    "accessKey": encoded("strong-minio-access"),
    "secretKey": encoded("strong-minio-secret"),
}

cases = {
    "wrong-postgres-user": (
        {**valid_firemud, "FIREMUD_POSTGRES_USER": encoded("other-user")},
        valid_minio,
    ),
    "missing-key": (
        {key: value for key, value in valid_firemud.items() if key != "ASSET_STORE_SECRET_KEY"},
        valid_minio,
    ),
    "extra-key": (valid_firemud, {**valid_minio, "unexpected": encoded("value")}),
    "empty-key": ({**valid_firemud, "FIREMUD_POSTGRES_PASSWORD": ""}, valid_minio),
    "malformed-key": (valid_firemud, {**valid_minio, "accessKey": "%%%"}),
    "legacy-default": (
        {
            **valid_firemud,
            "FIREMUD_POSTGRES_PASSWORD": encoded("firemud"),
            "ASSET_STORE_ACCESS_KEY": encoded("minio"),
            "ASSET_STORE_SECRET_KEY": encoded("minio123"),
        },
        {"accessKey": encoded("minio"), "secretKey": encoded("minio123")},
    ),
    "mismatched-minio": (
        valid_firemud,
        {"accessKey": encoded("other-access"), "secretKey": encoded("other-secret")},
    ),
}
for case_name, (firemud_data, minio_data) in cases.items():
    directory = root / case_name
    write_secret(directory, "firemud-secret", firemud_data)
    write_secret(directory, "minio-credentials", minio_data)
PY

for scenario in \
  wrong-postgres-user missing-key extra-key empty-key malformed-key \
  legacy-default mismatched-minio; do
  scenario_dir="$rejection_root/$scenario"
  firemud_before="$(sha256sum "$scenario_dir/firemud-secret.json")"
  minio_before="$(sha256sum "$scenario_dir/minio-credentials.json")"
  if run_credential_step "$scenario_dir" \
    "$TEMP_DIR/${scenario}.output" "$TEMP_DIR/${scenario}.error"; then
    echo "credential step accepted ${scenario}" >&2
    exit 1
  fi
  grep -Fq 'Recycle the disposable preview namespace before retrying; credentials were not changed.' \
    "$TEMP_DIR/${scenario}.error"
  test "$(sha256sum "$scenario_dir/firemud-secret.json")" = "$firemud_before"
  test "$(sha256sum "$scenario_dir/minio-credentials.json")" = "$minio_before"
  test ! -e "$scenario_dir/openssl.log"
  if [[ -e "$scenario_dir/kubectl.log" ]]; then
    if grep -Eq '^(apply|create (firemud-secret|minio-credentials|jwt-signing-keys|jwt-jwks))$' "$scenario_dir/kubectl.log"; then
      echo "credential step mutated ${scenario}" >&2
      exit 1
    fi
  fi
done
grep -Fq 'PostgreSQL user is not canonical' \
  "$TEMP_DIR/wrong-postgres-user.error"

jwt_rejection_root="$rejection_root/jwt"
python3 - "$create_once_state" "$jwt_rejection_root" <<'PY'
import json
import shutil
import sys
from pathlib import Path

source = Path(sys.argv[1])
root = Path(sys.argv[2])
for scenario in ("jwks-only", "mismatched-jwks"):
    directory = root / scenario
    directory.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source / "firemud-secret.json", directory)
    shutil.copy2(source / "minio-credentials.json", directory)
    shutil.copy2(source / "jwt-jwks.json", directory)
    if scenario == "mismatched-jwks":
        shutil.copy2(source / "jwt-signing-keys.json", directory)
        path = directory / "jwt-jwks.json"
        document = json.loads(path.read_text(encoding="utf-8"))
        jwks = json.loads(document["data"]["jwks.json"])
        jwks["firemudDiagnostic"]["sha256"] = "0" * 64
        document["data"]["jwks.json"] = json.dumps(jwks, sort_keys=True)
        path.write_text(json.dumps(document, sort_keys=True), encoding="utf-8")
PY

for scenario in jwks-only mismatched-jwks; do
  scenario_dir="$jwt_rejection_root/$scenario"
  before="$(sha256sum "$scenario_dir"/*.json)"
  if run_credential_step "$scenario_dir" \
    "$TEMP_DIR/${scenario}.output" "$TEMP_DIR/${scenario}.error"; then
    echo "credential step accepted ${scenario}" >&2
    exit 1
  fi
  grep -Fq 'Recycle the disposable preview namespace before retrying; credentials were not changed.' \
    "$TEMP_DIR/${scenario}.error"
  test "$(sha256sum "$scenario_dir"/*.json)" = "$before"
  test ! -e "$scenario_dir/openssl.log"
  if grep -Eq '^(apply|create )' "$scenario_dir/kubectl.log"; then
    echo "credential step mutated ${scenario}" >&2
    exit 1
  fi
done
grep -Fq 'signing Secret is absent and cannot be reconstructed' \
  "$TEMP_DIR/jwks-only.error"
grep -Fq 'diagnostic content does not match the signing Secret' \
  "$TEMP_DIR/mismatched-jwks.error"
if grep -Eq \
  '(strong-postgres-password|strong-minio-(access|secret)|11111111111111111111111111111111|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa|bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb|cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc)' \
  "$TEMP_DIR"/*.output "$TEMP_DIR"/*.error; then
  echo 'credential step exposed secret material in command output' >&2
  exit 1
fi

# Exercise the real downstream Helm producer before checking mutations. This
# proves that the closed metadata allowlist accepts exactly the top-level and
# pod-template labels emitted by the chart.
rendered_values="$TEMP_DIR/preview-values.yaml"
rendered_manifest="$TEMP_DIR/preview-rendered.yaml"
python3 "$render_preview_values" \
  "$ROOT_DIR/k8s/helm/firemud/values-hosted-shared.example.yaml" \
  "$rendered_values" 42 pr-42 pr-42 pr-42.preview.example.test \
  pr-42-head-42 32000
helm template pr-42 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$rendered_values" --namespace pr-42 \
  --show-only templates/apps.yaml >"$rendered_manifest"

python3 - "$artifact_validator" "$rendered_manifest" <<'PY'
import runpy
import sys

import yaml

validator = runpy.run_path(sys.argv[1])
documents = [
    document
    for document in yaml.safe_load_all(open(sys.argv[2], encoding="utf-8"))
    if document is not None
]

for document in documents:
    metadata = validator["_validate_object_metadata"](document, "pr-42")
    assert metadata["labels"] == {
        **validator["EXPECTED_TOP_LEVEL_LABELS"],
        "app.kubernetes.io/instance": "pr-42",
    }
    if document["kind"] == "Deployment":
        validator["_validate_workload_selector_metadata"](document)
PY

# Explicit-null pod templates are authored artifact errors, not validator
# tracebacks, in both the sanitizer and trusted manifest-validation paths.
null_template_manifest="$TEMP_DIR/null-template.yaml"
cat >"$null_template_manifest" <<'YAML'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: account-service
  namespace: pr-42
spec:
  template: null
YAML
null_template_output="$TEMP_DIR/null-template-output.yaml"
null_template_error="$TEMP_DIR/null-template-sanitize-error"
if python3 "$artifact_validator" sanitize "$null_template_manifest" \
  "$null_template_output" >"$TEMP_DIR/null-template-sanitize-output" \
  2>"$null_template_error"; then
  echo "preview artifact sanitizer accepted an explicit-null pod template" >&2
  exit 1
fi
grep -Fxq \
  'preview artifact rejected: Deployment/account-service.spec.template.spec is not a pod specification' \
  "$null_template_error"
test ! -e "$null_template_output"

null_template_metadata="$TEMP_DIR/null-template-metadata.json"
python3 - "$null_template_manifest" "$null_template_metadata" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

manifest = Path(sys.argv[1])
metadata = {
    "schemaVersion": 1,
    "event": "pull_request",
    "repository": "example/FireMUD",
    "sourceWorkflow": ".github/workflows/preview.yml",
    "sourceRunId": 42,
    "prNumber": 42,
    "baseSha": "base-42",
    "headSha": "head-42",
    "mergeSha": "merge-42",
    "hostname": "pr-42.preview.example.test",
    "imageTag": "pr-42-head-42",
    "manifestSha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
}
Path(sys.argv[2]).write_text(json.dumps(metadata), encoding="utf-8")
PY
null_template_error="$TEMP_DIR/null-template-validate-error"
if python3 "$artifact_validator" \
  "$null_template_metadata" "$null_template_manifest" example/FireMUD 42 42 \
  base-42 head-42 merge-42 pr-42-head-42 pr-42.preview.example.test \
  >"$TEMP_DIR/null-template-validate-output" 2>"$null_template_error"; then
  echo "preview artifact validator accepted an explicit-null pod template" >&2
  exit 1
fi
grep -Fxq \
  'preview artifact rejected: Deployment/account-service.spec.template.spec is not a pod specification' \
  "$null_template_error"

# Fixed-image infrastructure pods are accepted only in the exact shape emitted
# by the hosted chart. In particular, a PR render cannot turn those trusted
# images into a Secret-reading or arbitrary-command execution primitive.
python3 - "$artifact_validator" <<'PY'
import copy
import runpy
import sys

validator = runpy.run_path(sys.argv[1])
expected_specs = validator["EXPECTED_INFRASTRUCTURE_DEPLOYMENT_SPECS"]
validate = validator["validate_infrastructure_deployments"]
clean_config_map = validator["_clean_config_map"]


def fixture():
    return [
        {
            "apiVersion": "apps/v1",
            "kind": "Deployment",
            "metadata": {"name": name},
            "spec": copy.deepcopy(spec),
        }
        for name, spec in expected_specs.items()
    ]


validate(fixture())

mutations = {}

sensitive_mount = fixture()
postgres_pod = sensitive_mount[0]["spec"]["template"]["spec"]
postgres_pod["containers"][0]["volumeMounts"].append(
    {
        "name": "stolen-jwt",
        "mountPath": "/stolen-jwt",
        "readOnly": True,
    }
)
postgres_pod["volumes"].append(
    {"name": "stolen-jwt", "secret": {"secretName": "jwt-signing-keys"}}
)
mutations["sensitive Secret mount"] = sensitive_mount

command = fixture()
command[1]["spec"]["template"]["spec"]["containers"][0]["command"] = [
    "/bin/sh",
    "-c",
]
mutations["command override"] = command

environment = fixture()
environment[1]["spec"]["template"]["spec"]["containers"][0]["env"] = [
    {"name": "UNTRUSTED", "value": "true"}
]
mutations["environment override"] = environment

service_account = fixture()
service_account[2]["spec"]["template"]["spec"]["serviceAccountName"] = "firemud-app"
mutations["ServiceAccount override"] = service_account

image = fixture()
image[3]["spec"]["template"]["spec"]["containers"][0]["image"] = "postgres:16"
mutations["image override"] = image

pod_label = fixture()
pod_label[2]["spec"]["template"]["metadata"]["labels"]["app"] = "unselected"
mutations["egress-selector label override"] = pod_label

for description, documents in mutations.items():
    try:
        validate(documents)
    except ValueError as exc:
        if "has an unsafe infrastructure spec" not in str(exc):
            raise AssertionError((description, str(exc))) from exc
    else:
        raise AssertionError(f"validator accepted {description}")

cleaned_config = clean_config_map(
    {
        "metadata": {"name": "firemud-config"},
        "data": {"SAFE_VALUE": "retained", "API_TOKEN": "removed"},
    }
)
assert cleaned_config["data"] == {"SAFE_VALUE": "retained"}
for malformed_data in (["not", "a", "mapping"], "not-a-mapping"):
    try:
        clean_config_map(
            {
                "metadata": {"name": "firemud-config"},
                "data": malformed_data,
            }
        )
    except ValueError as exc:
        assert str(exc) == "ConfigMap/firemud-config.data is not an object"
    else:
        raise AssertionError(f"sanitizer accepted ConfigMap data {malformed_data!r}")
PY

# Trusted post-validation preparation makes the runtime namespace explicit on
# every object and permits exactly the allocator-owned TCP Proxy NodePort.
python3 - "$artifact_validator" "$TEMP_DIR" <<'PY'
import copy
import runpy
import sys
from pathlib import Path

import yaml

validator = runpy.run_path(sys.argv[1])
inject = validator["inject_telnet_port"]
validate_target = validator["validate_runtime_target"]
tmp = Path(sys.argv[2])
source = tmp / "runtime-target-source.yaml"
prepared = tmp / "runtime-target-prepared.yaml"
documents = [
    {
        "apiVersion": "v1",
        "kind": "Service",
        "metadata": {
            "name": "tcp-proxy-service",
            "labels": {
                **validator["EXPECTED_TOP_LEVEL_LABELS"],
                "app.kubernetes.io/instance": "pr-42",
            },
        },
        "spec": {"ports": [{"port": 2323}]},
    },
    {
        "apiVersion": "v1",
        "kind": "ConfigMap",
        "metadata": {
            "name": "firemud-config",
            "namespace": "pr-42",
            "labels": {
                **validator["EXPECTED_TOP_LEVEL_LABELS"],
                "app.kubernetes.io/instance": "pr-42",
            },
        },
        "data": {},
    },
    {
        "apiVersion": "apps/v1",
        "kind": "Deployment",
        "metadata": {
            "name": "account-service",
            "labels": {
                **validator["EXPECTED_TOP_LEVEL_LABELS"],
                "app.kubernetes.io/instance": "pr-42",
            },
        },
        "spec": {
            "selector": {"matchLabels": {"app": "account-service"}},
            "template": {"metadata": {"labels": {"app": "account-service"}}},
        },
    },
]
source.write_text(yaml.safe_dump_all(documents), encoding="utf-8")
inject(source, prepared, 32000, "pr-42")
validate_target(prepared, "pr-42", 32000)
prepared_documents = list(yaml.safe_load_all(prepared.read_text(encoding="utf-8")))
if any(document["metadata"].get("namespace") != "pr-42" for document in prepared_documents):
    raise SystemExit("trusted runtime preparation left a namespace implicit")


def expect_rejected(case_name, mutation, expected_message=None):
    mutated = copy.deepcopy(prepared_documents)
    mutation(mutated)
    path = tmp / f"runtime-target-{case_name}.yaml"
    path.write_text(yaml.safe_dump_all(mutated), encoding="utf-8")
    try:
        validate_target(path, "pr-42", 32000)
    except ValueError as exc:
        if expected_message is not None and expected_message not in str(exc):
            raise AssertionError((case_name, str(exc))) from exc
        return
    raise SystemExit(f"runtime target validator accepted {case_name}")


expect_rejected(
    "missing-namespace",
    lambda current: current[1]["metadata"].pop("namespace"),
)
expect_rejected(
    "wrong-namespace",
    lambda current: current[1]["metadata"].__setitem__("namespace", "pr-43"),
)
expect_rejected(
    "extra-node-port",
    lambda current: current[1].setdefault("spec", {}).__setitem__("nodePort", 32001),
)
expect_rejected(
    "finalizers",
    lambda current: current[0]["metadata"].__setitem__(
        "finalizers", ["untrusted.example/finalizer"]
    ),
    "metadata contains unsupported fields: ['finalizers']",
)
expect_rejected(
    "owner-references",
    lambda current: current[0]["metadata"].__setitem__(
        "ownerReferences",
        [{"apiVersion": "v1", "kind": "Secret", "name": "capture"}],
    ),
    "metadata contains unsupported fields: ['ownerReferences']",
)
expect_rejected(
    "unknown-metadata-field",
    lambda current: current[0]["metadata"].__setitem__("generateName", "escape-"),
    "metadata contains unsupported fields: ['generateName']",
)
expect_rejected(
    "unknown-top-level-label",
    lambda current: current[0]["metadata"]["labels"].__setitem__(
        "untrusted.example/route", "capture"
    ),
    "unsafe Helm metadata labels",
)
expect_rejected(
    "modified-helm-label",
    lambda current: current[0]["metadata"]["labels"].__setitem__(
        "app.kubernetes.io/instance", "pr-43"
    ),
    "unsafe Helm metadata labels",
)
expect_rejected(
    "selector-label",
    lambda current: current[2]["spec"]["template"]["metadata"]["labels"].__setitem__(
        "app", "postgres"
    ),
    "unsafe pod-template metadata",
)
expect_rejected(
    "unknown-pod-label",
    lambda current: current[2]["spec"]["template"]["metadata"]["labels"].__setitem__(
        "untrusted.example/route", "capture"
    ),
    "unsafe pod-template metadata",
)
expect_rejected(
    "workload-selector",
    lambda current: current[2]["spec"]["selector"]["matchLabels"].__setitem__(
        "app", "postgres"
    ),
    "unsafe selector",
)
try:
    validate_target(prepared, "pr-42", 32001)
except ValueError:
    pass
else:
    raise SystemExit("runtime target validator accepted the wrong allocated port")
PY

# Execute the exact trusted apply and deployed-head blocks. The changing PR
# fixtures prove that close/head races after server dry-run stop the real apply
# and therefore cannot publish deployed-head evidence.
apply_runtime_step="$TEMP_DIR/apply-runtime-step.sh"
record_preview_head_step="$TEMP_DIR/record-preview-head-step.sh"
record_source_preview_head_step="$TEMP_DIR/record-source-preview-head-step.sh"
prepared_render="$TEMP_DIR/runtime-target-prepared.yaml"
python3 - "$trusted" "$preview" "$apply_runtime_step" "$record_preview_head_step" \
  "$record_source_preview_head_step" <<'PY'
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
preview_workflow = yaml.safe_load(Path(sys.argv[2]).read_text(encoding="utf-8"))
steps = workflow["jobs"]["deploy-runtime"]["steps"]
apply_step = next(
    step for step in steps if step.get("name") == "Apply validated PR runtime artifact"
)
record_step = next(
    step for step in steps if step.get("name") == "Record exact deployed preview head"
)
source_record_step = next(
    step
    for step in preview_workflow["jobs"]["preview-deploy"]["steps"]
    if step.get("name") == "Record exact deployed preview head"
)
Path(sys.argv[3]).write_text(apply_step["run"], encoding="utf-8")
Path(sys.argv[4]).write_text(record_step["run"], encoding="utf-8")
Path(sys.argv[5]).write_text(source_record_step["run"], encoding="utf-8")
PY

apply_stub_dir="$TEMP_DIR/apply-stubs"
mkdir -p "$apply_stub_dir"
cat >"$apply_stub_dir/gh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 2 && "$1" == api && "$2" == "repos/example/FireMUD/pulls/42" ]]
count=0
if [[ -f "${TEST_GH_COUNT:?}" ]]; then
  count="$(<"$TEST_GH_COUNT")"
fi
count=$((count + 1))
printf '%s\n' "$count" >"$TEST_GH_COUNT"
printf 'gh-%s\n' "$count" >>"${TEST_APPLY_LOG:?}"

state=open
head_sha="${TEST_EXPECTED_HEAD:?}"
case "${TEST_APPLY_SCENARIO:?}" in
  success)
    ;;
  closed-first)
    state=closed
    ;;
  closed-second)
    if (( count == 2 )); then
      state=closed
    fi
    ;;
  stale-second)
    if (( count == 2 )); then
      head_sha=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
    fi
    ;;
  *)
    exit 2
    ;;
esac
jq -nc \
  --arg state "$state" \
  --arg head "$head_sha" \
  '{state:$state,head:{sha:$head,repo:{full_name:"example/FireMUD"}},base:{ref:"develop"},user:{login:"trusted-user"},labels:[]}'
SH
cat >"$apply_stub_dir/kubectl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == apply && "$2" == --dry-run=server ]]; then
  [[ $# -eq 8 ]]
  [[ "$3" == --server-side ]]
  [[ "$4" == --field-manager=trusted-hosted-preview ]]
  [[ "$5" == -n && "$6" == pr-42 && "$7" == -f ]]
  [[ "$8" == "${ARTIFACT_PATH:?}" ]]
  printf 'dry-run\n' >>"${TEST_APPLY_LOG:?}"
  exit 0
fi
if [[ "$1" == apply && "$2" == --server-side ]]; then
  [[ $# -eq 7 ]]
  [[ "$3" == --field-manager=trusted-hosted-preview ]]
  [[ "$4" == -n && "$5" == pr-42 && "$6" == -f ]]
  [[ "$7" == "${ARTIFACT_PATH:?}" ]]
  printf 'apply\n' >>"${TEST_APPLY_LOG:?}"
  exit 0
fi
if [[ "$1" == annotate ]]; then
  [[ $# -eq 5 ]]
  [[ "$2" == namespace && "$3" == pr-42 ]]
  [[ "$4" == "firemud.dev/last-preview-head-sha=${TEST_EXPECTED_HEAD:?}" ]]
  [[ "$5" == --overwrite ]]
  printf 'deployed-head=%s\n' "$4" >>"${TEST_APPLY_LOG:?}"
  exit 0
fi
printf 'unexpected fake kubectl invocation: %s\n' "$*" >&2
exit 2
SH
chmod +x "$apply_stub_dir/gh" "$apply_stub_dir/kubectl"

run_apply_fixture() {
  local scenario="$1"
  local expected_log="$2"
  local expected_error="${3:-}"
  local apply_log="$TEMP_DIR/apply-${scenario}.log"
  local gh_count="$TEMP_DIR/apply-${scenario}.gh-count"
  local apply_error="$TEMP_DIR/apply-${scenario}.error"
  local head_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  local status

  : >"$apply_log"
  set +e
  (
    cd "$ROOT_DIR"
    env \
      PATH="$apply_stub_dir:$PATH" \
      GH_TOKEN=fake \
      GITHUB_REPOSITORY=example/FireMUD \
      PR_NUMBER=42 \
      EXPECTED_HEAD_SHA="$head_sha" \
      RUNTIME_NAMESPACE=pr-42 \
      ARTIFACT_PATH="$prepared_render" \
      TELNET_PORT=32000 \
      TEST_APPLY_SCENARIO="$scenario" \
      TEST_EXPECTED_HEAD="$head_sha" \
      TEST_GH_COUNT="$gh_count" \
      TEST_APPLY_LOG="$apply_log" \
      bash "$apply_runtime_step"
  ) >"$TEMP_DIR/apply-${scenario}.output" 2>"$apply_error"
  status=$?
  set -e

  if [[ "$scenario" == success ]]; then
    [[ "$status" -eq 0 ]]
    (
      cd "$ROOT_DIR"
      env \
        PATH="$apply_stub_dir:$PATH" \
        RUNTIME_NAMESPACE=pr-42 \
        PR_NUMBER=42 \
        HEAD_SHA="$head_sha" \
        TEST_EXPECTED_HEAD="$head_sha" \
        TEST_APPLY_LOG="$apply_log" \
        bash "$record_preview_head_step"
    )
  else
    [[ "$status" -ne 0 ]]
    grep -Fq "$expected_error" "$apply_error"
  fi

  [[ "$(paste -sd ' ' "$apply_log")" == "$expected_log" ]]
}

run_apply_fixture closed-first \
  "gh-1" \
  "pull request is not open"
run_apply_fixture closed-second \
  "gh-1 dry-run gh-2" \
  "pull request is not open"
run_apply_fixture stale-second \
  "gh-1 dry-run gh-2" \
  "head is stale"
run_apply_fixture success \
  "gh-1 dry-run gh-2 apply deployed-head=firemud.dev/last-preview-head-sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

mixed_case_head=AaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAaAa
source_preview_log="$TEMP_DIR/source-preview-head.log"
: >"$source_preview_log"
env \
  PATH="$apply_stub_dir:$PATH" \
  RUNTIME_NAMESPACE=pr-42 \
  PR_NUMBER=42 \
  HEAD_SHA="$mixed_case_head" \
  TEST_EXPECTED_HEAD=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  TEST_APPLY_LOG="$source_preview_log" \
  bash "$record_source_preview_head_step"
[[ "$(<"$source_preview_log")" == \
  "deployed-head=firemud.dev/last-preview-head-sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" ]]

# The retired waiter treats kubectl's structured NotFound result as successful
# terminal absence, while permission/API failures remain immediately fatal.
retirement_stub_dir="$TEMP_DIR/retirement-waiter-stubs"
mkdir -p "$retirement_stub_dir"
real_sleep_path="$(command -v sleep)"
cat >"$retirement_stub_dir/kubectl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${WAITER_KUBECTL_LOG:?}"
[[ "$*" == "-n firemud-system get hostedenvironmentidentity pr-42 --ignore-not-found -o json" ]]
case "${WAITER_SCENARIO:?}" in
  not-found)
    exit 0
    ;;
  forbidden)
    printf 'Error from server (Forbidden): hostedenvironmentidentities is forbidden\n' >&2
    exit 42
    ;;
  api-error)
    printf 'Unable to connect to the server: dial tcp 10.0.0.1:443: i/o timeout\n' >&2
    exit 43
    ;;
  *)
    printf 'unexpected waiter scenario: %s\n' "$WAITER_SCENARIO" >&2
    exit 2
    ;;
esac
SH
cat >"$retirement_stub_dir/sleep" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${WAITER_SLEEP_LOG:?}"
exec "${REAL_SLEEP_PATH:?}" 0.2
SH
chmod +x "$retirement_stub_dir/kubectl" "$retirement_stub_dir/sleep"

run_retirement_waiter_fixture() {
  local scenario="$1"
  local expected_status="$2"
  local kubectl_log="$TEMP_DIR/retirement-${scenario}.kubectl.log"
  local sleep_log="$TEMP_DIR/retirement-${scenario}.sleep.log"
  local output="$TEMP_DIR/retirement-${scenario}.output"
  local error="$TEMP_DIR/retirement-${scenario}.error"
  local status

  : >"$kubectl_log"
  : >"$sleep_log"
  set +e
  env \
    PATH="$retirement_stub_dir:$PATH" \
    REAL_SLEEP_PATH="$real_sleep_path" \
    WAITER_SCENARIO="$scenario" \
    WAITER_KUBECTL_LOG="$kubectl_log" \
    WAITER_SLEEP_LOG="$sleep_log" \
    bash "$waiter" --retired pr-42 2 \
    >"$output" 2>"$error"
  status=$?
  set -e

  [[ "$status" -eq "$expected_status" ]]
  [[ "$(head -n 1 "$kubectl_log")" == "-n firemud-system get hostedenvironmentidentity pr-42 --ignore-not-found -o json" ]]
  if [[ "$scenario" == not-found ]]; then
    [[ "$(wc -l <"$kubectl_log")" -eq 1 ]]
    [[ ! -s "$sleep_log" ]]
    grep -Fq 'phase=Retired' "$output"
    [[ ! -s "$error" ]]
  else
    [[ "$(wc -l <"$kubectl_log")" -eq 1 ]]
    [[ ! -s "$sleep_log" ]]
    grep -Fq 'kubectl get failed' "$error"
    case "$scenario" in
      forbidden)
        grep -Fq 'Error from server (Forbidden)' "$error"
        ;;
      api-error)
        grep -Fq 'Unable to connect to the server' "$error"
        ;;
    esac
  fi
}

run_retirement_waiter_fixture not-found 0
run_retirement_waiter_fixture forbidden 42
run_retirement_waiter_fixture api-error 43

# Projection namespace mismatches fail before any Secret read, including the
# dev-demo-to-dev and PR-identity-to-identical-PR namespace boundaries.
projection_mismatch_stub_dir="$TEMP_DIR/projection-mismatch-waiter-stubs"
mkdir -p "$projection_mismatch_stub_dir"
cat >"$projection_mismatch_stub_dir/kubectl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${PROJECTION_KUBECTL_LOG:?}"
exit 99
SH
chmod +x "$projection_mismatch_stub_dir/kubectl"

run_projection_namespace_mismatch_fixture() {
  local identity_name="$1"
  local runtime_namespace="$2"
  local expected_message="$3"
  local suffix="$4"
  local kubectl_log="$TEMP_DIR/projection-mismatch-${suffix}.kubectl.log"
  local error="$TEMP_DIR/projection-mismatch-${suffix}.error"
  local status

  : >"$kubectl_log"
  set +e
  env \
    PATH="$projection_mismatch_stub_dir:$PATH" \
    PROJECTION_KUBECTL_LOG="$kubectl_log" \
    bash "$waiter" --projections "$identity_name" "$runtime_namespace" 1 \
    >"$TEMP_DIR/projection-mismatch-${suffix}.output" 2>"$error"
  status=$?
  set -e

  [[ "$status" -eq 2 ]]
  grep -Fq -- "$expected_message" "$error"
  [[ ! -s "$kubectl_log" ]]
}

run_projection_namespace_mismatch_fixture \
  dev-demo pr-42 \
  'dev-demo identity requires the dev runtime namespace' \
  dev-demo-pr
run_projection_namespace_mismatch_fixture \
  pr-42 dev \
  'PR identity pr-42 requires matching runtime namespace pr-42' \
  pr-42-dev

# The default readiness waiter rejects the same mismatches before reading the
# runtime namespace or HostedEnvironmentIdentity.
run_active_namespace_mismatch_fixture() {
  local identity_name="$1"
  local runtime_namespace="$2"
  local expected_message="$3"
  local suffix="$4"
  local kubectl_log="$TEMP_DIR/active-mismatch-${suffix}.kubectl.log"
  local error="$TEMP_DIR/active-mismatch-${suffix}.error"
  local status
  local expected_head=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

  : >"$kubectl_log"
  set +e
  env \
    PATH="$projection_mismatch_stub_dir:$PATH" \
    PROJECTION_KUBECTL_LOG="$kubectl_log" \
    bash "$waiter" "$identity_name" "$expected_head" "$runtime_namespace" 1 \
    >"$TEMP_DIR/active-mismatch-${suffix}.output" 2>"$error"
  status=$?
  set -e

  [[ "$status" -eq 2 ]]
  grep -Fq -- "$expected_message" "$error"
  [[ ! -s "$kubectl_log" ]]
}

run_active_namespace_mismatch_fixture \
  dev-demo pr-42 \
  'dev-demo identity requires the dev runtime namespace' \
  dev-demo-pr
run_active_namespace_mismatch_fixture \
  pr-42 dev \
  'PR identity pr-42 requires matching runtime namespace pr-42' \
  pr-42-dev

# Active waiter reads treat a successful empty --ignore-not-found response as
# absence, while a kubectl failure is an immediately fatal API/auth/transport
# error. Nonempty incomplete projection state remains retryable.
waiter_stub_dir="$TEMP_DIR/active-waiter-stubs"
mkdir -p "$waiter_stub_dir"
cat >"$waiter_stub_dir/kubectl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

scenario="${WAITER_SCENARIO:?}"
printf '%s\n' "$*" >>"${WAITER_KUBECTL_LOG:?}"

next_count() {
  local name="$1"
  local path="${WAITER_COUNT_ROOT:?}/${name}"
  local count=0
  if [[ -f "$path" ]]; then
    count="$(<"$path")"
  fi
  count=$((count + 1))
  printf '%s\n' "$count" >"$path"
  printf '%s' "$count"
}

namespace_json() {
  jq -nc --arg expected_head "${WAITER_EXPECTED_HEAD:?}" '{
    metadata: {
      uid: "uid-pr-42",
      annotations: {
        "firemud.dev/requested-preview-head-sha": $expected_head,
        "firemud.dev/last-preview-head-sha": $expected_head
      }
    }
  }'
}

identity_json() {
  jq -nc --arg expected_head "${WAITER_EXPECTED_HEAD:?}" '{
    metadata: {generation: 1},
    status: {
      observedGeneration: 1,
      phase: "Ready",
      conditions: [{type: "Ready", status: "True", observedGeneration: 1}],
      profile: {
        runtimeNamespaceUid: "uid-pr-42",
        requestedHeadSha: $expected_head,
        deployedHeadSha: $expected_head
      },
      ingress: {revision: "ingress-1"},
      telnet: {revision: "telnet-1"},
      grpc: {revision: "grpc-1"},
      gatewayInternalWs: {revision: "gateway-1"},
      tcpProxyBridge: {revision: "bridge-1"}
    }
  }'
}

if [[ "$1" == -n && "$2" == pr-42 && "$3" == get && "$4" == secret ]]; then
  [[ $# -eq 8 && "$6" == --ignore-not-found && "$7" == -o && "$8" == json ]]
  secret_name="$5"
  if [[ "$scenario" == projection-command-failure ]]; then
    printf 'Error from server (Forbidden): secrets are forbidden\n' >&2
    exit 42
  fi
  if [[ "$scenario" == projection-absence && "$secret_name" == pr-42-tls ]]; then
    projection_count="$(next_count projection)"
    if (( projection_count == 1 )); then
      exit 0
    fi
    if (( projection_count == 2 )); then
      printf '{}'
      exit 0
    fi
  fi
  case "$secret_name" in
    pr-42-tls)
      printf '%s' '{"metadata":{"name":"pr-42-tls","labels":{"firemud.dev/managed-by":"hosted-identity-controller","firemud.dev/identity-name":"pr-42","firemud.dev/role":"ingress","firemud.dev/retention":"retained"}},"data":{"tls.crt":"cert","tls.key":"key"}}'
      ;;
    pr-42-telnet-tls)
      printf '%s' '{"metadata":{"name":"pr-42-telnet-tls","labels":{"firemud.dev/managed-by":"hosted-identity-controller","firemud.dev/identity-name":"pr-42","firemud.dev/role":"telnet","firemud.dev/retention":"retained"}},"data":{"tls.crt":"cert","tls.key":"key"}}'
      ;;
    pr-42-gateway-internal-ws)
      printf '%s' '{"metadata":{"name":"pr-42-gateway-internal-ws","labels":{"firemud.dev/managed-by":"hosted-identity-controller","firemud.dev/identity-name":"pr-42","firemud.dev/role":"gateway-internal-ws","firemud.dev/retention":"retained"}},"data":{"tls.crt":"cert","tls.key":"key","ca.crt":"ca"}}'
      ;;
    pr-42-tcp-proxy-bridge)
      printf '%s' '{"metadata":{"name":"pr-42-tcp-proxy-bridge","labels":{"firemud.dev/managed-by":"hosted-identity-controller","firemud.dev/identity-name":"pr-42","firemud.dev/role":"tcp-proxy-bridge","firemud.dev/retention":"retained"}},"data":{"tls.crt":"cert","tls.key":"key","ca.crt":"ca"}}'
      ;;
    firemud-grpc-tls)
      printf '%s' '{"metadata":{"name":"firemud-grpc-tls","labels":{"firemud.dev/managed-by":"hosted-identity-controller","firemud.dev/identity-name":"pr-42","firemud.dev/role":"grpc","firemud.dev/retention":"retained"}},"data":{"tls.crt":"cert","tls.key":"key","ca.crt":"ca","client.crt":"client-cert","client.key":"client-key"}}'
      ;;
    *)
      printf 'unexpected projection Secret: %s\n' "$secret_name" >&2
      exit 2
      ;;
  esac
  exit 0
fi

if [[ "$1" == get && "$2" == namespace ]]; then
  [[ $# -eq 6 && "$3" == pr-42 && "$4" == --ignore-not-found && "$5" == -o && "$6" == json ]]
  if [[ "$scenario" == namespace-command-failure ]]; then
    printf 'Error from server (Forbidden): namespaces are forbidden\n' >&2
    exit 43
  fi
  if [[ "$scenario" == namespace-absence ]]; then
    namespace_count="$(next_count namespace)"
    if (( namespace_count == 1 )); then
      exit 0
    fi
  fi
  namespace_json
  exit 0
fi

if [[ "$1" == -n && "$2" == firemud-system && "$3" == get && "$4" == hostedenvironmentidentity ]]; then
  [[ $# -eq 8 && "$5" == pr-42 && "$6" == --ignore-not-found && "$7" == -o && "$8" == json ]]
  if [[ "$scenario" == identity-command-failure ]]; then
    printf 'Error from server (Forbidden): hostedenvironmentidentities are forbidden\n' >&2
    exit 44
  fi
  if [[ "$scenario" == identity-absence ]]; then
    identity_count="$(next_count identity)"
    if (( identity_count == 1 )); then
      exit 0
    fi
  fi
  identity_json
  exit 0
fi

printf 'unexpected waiter kubectl invocation: %s\n' "$*" >&2
exit 2
SH
cat >"$waiter_stub_dir/sleep" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${WAITER_SLEEP_LOG:?}"
SH
chmod +x "$waiter_stub_dir/kubectl" "$waiter_stub_dir/sleep"

run_projection_waiter_fixture() {
  local scenario="$1"
  local expected_status="$2"
  local expected_kubectl_calls="$3"
  local expected_sleep_calls="$4"
  local kubectl_log="$TEMP_DIR/${scenario}.kubectl.log"
  local sleep_log="$TEMP_DIR/${scenario}.sleep.log"
  local count_root="$TEMP_DIR/${scenario}.counts"
  local output="$TEMP_DIR/${scenario}.output"
  local error="$TEMP_DIR/${scenario}.error"
  local status
  local expected_head=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

  mkdir -p "$count_root"
  : >"$kubectl_log"
  : >"$sleep_log"
  set +e
  env \
    PATH="$waiter_stub_dir:$PATH" \
    WAITER_SCENARIO="$scenario" \
    WAITER_EXPECTED_HEAD="$expected_head" \
    WAITER_COUNT_ROOT="$count_root" \
    WAITER_KUBECTL_LOG="$kubectl_log" \
    WAITER_SLEEP_LOG="$sleep_log" \
    bash "$waiter" --projections pr-42 pr-42 2 \
    >"$output" 2>"$error"
  status=$?
  set -e

  [[ "$status" -eq "$expected_status" ]]
  [[ "$(wc -l <"$kubectl_log")" -eq "$expected_kubectl_calls" ]]
  [[ "$(wc -l <"$sleep_log")" -eq "$expected_sleep_calls" ]]
  [[ "$(head -n 1 "$kubectl_log")" == \
    "-n pr-42 get secret pr-42-tls --ignore-not-found -o json" ]]
  if [[ "$expected_status" -eq 0 ]]; then
    grep -Fq 'projections=ready' "$output"
  else
    grep -Fq 'kubectl get failed' "$error"
    grep -Fq 'Error from server (Forbidden)' "$error"
  fi
}

run_projection_waiter_fixture projection-absence 0 7 2
run_projection_waiter_fixture projection-command-failure 42 1 0

run_active_waiter_fixture() {
  local scenario="$1"
  local expected_status="$2"
  local expected_kubectl_calls="$3"
  local expected_sleep_calls="$4"
  local kubectl_log="$TEMP_DIR/${scenario}.kubectl.log"
  local sleep_log="$TEMP_DIR/${scenario}.sleep.log"
  local count_root="$TEMP_DIR/${scenario}.counts"
  local output="$TEMP_DIR/${scenario}.output"
  local error="$TEMP_DIR/${scenario}.error"
  local status
  local expected_head=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

  mkdir -p "$count_root"
  : >"$kubectl_log"
  : >"$sleep_log"
  set +e
  env \
    PATH="$waiter_stub_dir:$PATH" \
    WAITER_SCENARIO="$scenario" \
    WAITER_EXPECTED_HEAD="$expected_head" \
    WAITER_COUNT_ROOT="$count_root" \
    WAITER_KUBECTL_LOG="$kubectl_log" \
    WAITER_SLEEP_LOG="$sleep_log" \
    bash "$waiter" pr-42 "$expected_head" pr-42 2 \
    >"$output" 2>"$error"
  status=$?
  set -e

  [[ "$status" -eq "$expected_status" ]]
  [[ "$(wc -l <"$kubectl_log")" -eq "$expected_kubectl_calls" ]]
  [[ "$(wc -l <"$sleep_log")" -eq "$expected_sleep_calls" ]]
  if [[ "$expected_status" -eq 0 ]]; then
    grep -Fq 'identity=pr-42' "$output"
  else
    grep -Fq 'kubectl get failed' "$error"
    grep -Fq 'Error from server (Forbidden)' "$error"
  fi
}

run_active_waiter_fixture namespace-absence 0 3 1
run_active_waiter_fixture identity-absence 0 4 1
run_active_waiter_fixture namespace-command-failure 43 1 0
run_active_waiter_fixture identity-command-failure 44 2 0

python3 - "$kubeconfig_action" "$TEMP_DIR/write-kubeconfig.sh" <<'PY'
import sys
from pathlib import Path

import yaml

action = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
Path(sys.argv[2]).write_text(action["runs"]["steps"][0]["run"], encoding="utf-8")
PY
mkdir -p "$TEMP_DIR/bin"
cat >"$TEMP_DIR/bin/kubectl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 5 ]]
[[ "$1" == --kubeconfig ]]
[[ "$3" == config && "$4" == view && "$5" == --minify ]]
python3 - "$2" <<'PY'
import sys
from pathlib import Path

import yaml

config = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert isinstance(config, dict)
assert config.get("apiVersion") == "v1"
assert config.get("kind") == "Config"
current_context = config.get("current-context")
assert isinstance(current_context, str) and current_context
contexts = config.get("contexts")
assert isinstance(contexts, list)
assert any(
    isinstance(context, dict)
    and context.get("name") == current_context
    and isinstance(context.get("context"), dict)
    for context in contexts
)
PY
SH
chmod +x "$TEMP_DIR/bin/kubectl"
valid_kubeconfig=$'apiVersion: v1\nkind: Config\ncurrent-context: preview\ncontexts:\n  - name: preview\n    context:\n      cluster: preview\n      user: preview\nclusters:\n  - name: preview\n    cluster:\n      server: https://127.0.0.1\nusers:\n  - name: preview\n    user:\n      token: test-token'
valid_kubeconfig_parent="$TEMP_DIR/missing parent"
valid_kubeconfig_path="$valid_kubeconfig_parent/valid config.kubeconfig"
valid_github_env="$TEMP_DIR/valid-github-env"
test ! -e "$valid_kubeconfig_parent"
: >"$valid_github_env"
PATH="$TEMP_DIR/bin:$PATH" \
  KUBECONFIG_CONTENT="$valid_kubeconfig" \
  KUBECONFIG_PATH="$valid_kubeconfig_path" \
  GITHUB_ENV="$valid_github_env" \
  bash "$TEMP_DIR/write-kubeconfig.sh"
test -f "$valid_kubeconfig_path"
test ! -L "$valid_kubeconfig_path"
test "$(stat -c '%a' "$valid_kubeconfig_path")" = 600
test "$(cat "$valid_kubeconfig_path")" = "$valid_kubeconfig"
grep -Fxq "KUBECONFIG=$valid_kubeconfig_path" "$valid_github_env"

symlink_target="$TEMP_DIR/symlink-target.kubeconfig"
symlink_path="$TEMP_DIR/symlink.kubeconfig"
symlink_github_env="$TEMP_DIR/symlink-github-env"
printf 'protected symlink target\n' >"$symlink_target"
ln -s "$symlink_target" "$symlink_path"
: >"$symlink_github_env"
PATH="$TEMP_DIR/bin:$PATH" \
  KUBECONFIG_CONTENT="$valid_kubeconfig" \
  KUBECONFIG_PATH="$symlink_path" \
  GITHUB_ENV="$symlink_github_env" \
  bash "$TEMP_DIR/write-kubeconfig.sh"
test ! -L "$symlink_path"
test -f "$symlink_path"
test "$(stat -c '%a' "$symlink_path")" = 600
test "$(cat "$symlink_path")" = "$valid_kubeconfig"
test "$(cat "$symlink_target")" = 'protected symlink target'
grep -Fxq "KUBECONFIG=$symlink_path" "$symlink_github_env"

malformed_kubeconfig_path="$TEMP_DIR/malformed.kubeconfig"
malformed_github_env="$TEMP_DIR/malformed-github-env"
printf 'prior malformed destination\n' >"$malformed_kubeconfig_path"
chmod 644 "$malformed_kubeconfig_path"
: >"$malformed_github_env"
if PATH="$TEMP_DIR/bin:$PATH" \
  KUBECONFIG_CONTENT=$'apiVersion: v1\nkind: Config\ncontexts: [' \
  KUBECONFIG_PATH="$malformed_kubeconfig_path" \
  GITHUB_ENV="$malformed_github_env" \
  bash "$TEMP_DIR/write-kubeconfig.sh" >/dev/null 2>&1; then
  echo "shared kubeconfig action accepted malformed content" >&2
  exit 1
fi
test "$(cat "$malformed_kubeconfig_path")" = 'prior malformed destination'
test "$(stat -c '%a' "$malformed_kubeconfig_path")" = 644
test ! -s "$malformed_github_env"
test -z "$(find "$TEMP_DIR" -maxdepth 1 -name '.*.kubeconfig.*' -print -quit)"

missing_kubectl_bin="$TEMP_DIR/missing-kubectl-bin"
missing_kubectl_path="$TEMP_DIR/missing-kubectl.kubeconfig"
missing_kubectl_github_env="$TEMP_DIR/missing-kubectl-github-env"
mkdir -p "$missing_kubectl_bin"
printf 'prior destination without kubectl\n' >"$missing_kubectl_path"
: >"$missing_kubectl_github_env"
if PATH="$missing_kubectl_bin" \
  KUBECONFIG_CONTENT="$valid_kubeconfig" \
  KUBECONFIG_PATH="$missing_kubectl_path" \
  GITHUB_ENV="$missing_kubectl_github_env" \
  "$BASH" "$TEMP_DIR/write-kubeconfig.sh" >/dev/null 2>&1; then
  echo "shared kubeconfig action succeeded without kubectl" >&2
  exit 1
fi
test "$(cat "$missing_kubectl_path")" = 'prior destination without kubectl'
test ! -s "$missing_kubectl_github_env"
test -z "$(find "$TEMP_DIR" -maxdepth 1 -name '.*.kubeconfig.*' -print -quit)"

python3 - "$trusted" "$TEMP_DIR/target.sh" <<'PY'
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
target = next(
    step
    for step in workflow["jobs"]["validate-target"]["steps"]
    if step.get("id") == "target"
)
Path(sys.argv[2]).write_text(target["run"], encoding="utf-8")
PY
cat >"$TEMP_DIR/bin/gh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
resource=''
jq_expression=''
previous=''
for argument in "$@"; do
  if [[ "$argument" == repos/* ]]; then
    resource="$argument"
  elif [[ "$previous" == --jq ]]; then
    jq_expression="$argument"
  fi
  previous="$argument"
done
case "$resource" in
  repos/example/FireMUD/actions/runs/42)
    if [[ -z "$jq_expression" ]]; then
      printf '%s\n' "$*" >>"${SOURCE_GH_LOG:?}"
      printf '%s' '{"conclusion":"success","head_sha":"cccccccccccccccccccccccccccccccccccccccc","path":".github/workflows/preview.yml","event":"pull_request","repository":{"full_name":"example/FireMUD"},"pull_requests":[{"number":900}]}'
    elif [[ "$jq_expression" == .path ]]; then
      printf '%s' '.github/workflows/preview.yml'
    else
      printf '%s' 900
    fi
    ;;
  repos/example/FireMUD/pulls/900)
    jq -nc \
      --arg state "${TEST_PR_STATE:-open}" \
      --arg head "${TEST_PR_HEAD_SHA:-cccccccccccccccccccccccccccccccccccccccc}" \
      --arg repository "${TEST_PR_HEAD_REPOSITORY:-example/FireMUD}" \
      --arg base_ref "${TEST_PR_BASE_REF:-develop}" \
      '{state:$state,head:{sha:$head,repo:{full_name:$repository}},base:{ref:$base_ref,sha:"base-900"},merge_commit_sha:"merge-900",labels:[]}'
    ;;
  repos/example/FireMUD/actions/runs/42/artifacts\?per_page=100)
    printf '%s' '[{"artifacts":[]}]'
    ;;
  *)
    printf 'unexpected fake gh invocation: %s\n' "$*" >&2
    exit 2
    ;;
esac
SH
chmod +x "$TEMP_DIR/bin/gh"
target_gh_log="$TEMP_DIR/target-gh.log"
(
  cd "$ROOT_DIR"
  PATH="$TEMP_DIR/bin:$PATH" \
    GH_TOKEN=fake \
    GITHUB_REPOSITORY=example/FireMUD \
    EVENT_NAME=workflow_run \
    EVENT_ACTION=completed \
    WORKFLOW_RUN_ID=42 \
    WORKFLOW_RUN_HEAD_SHA=cccccccccccccccccccccccccccccccccccccccc \
    EVENT_PR_NUMBER='' \
    EVENT_HEAD_SHA='' \
    INPUT_PR_NUMBER='' \
    INPUT_HEAD_SHA='' \
    INPUT_ACTION='' \
    SOURCE_GH_LOG="$target_gh_log" \
    GITHUB_OUTPUT="$TEMP_DIR/output" \
    bash "$TEMP_DIR/target.sh"
)
test "$(cat "$TEMP_DIR/output")" = 'action=none'
test "$(cat "$target_gh_log")" = 'api repos/example/FireMUD/actions/runs/42'

source_step="$TEMP_DIR/source.sh"
python3 - "$trusted" "$source_step" <<'PY'
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
step = next(step for step in workflow["jobs"]["validate-target"]["steps"] if step.get("id") == "source")
Path(sys.argv[2]).write_text(step["run"], encoding="utf-8")
PY
source_output="$TEMP_DIR/source-output"
source_gh_log="$TEMP_DIR/source-gh.log"
(
  cd "$ROOT_DIR"
  PATH="$TEMP_DIR/bin:$PATH" \
    GH_TOKEN=fake \
    GITHUB_REPOSITORY=example/FireMUD \
    SOURCE_RUN_ID=42 \
    HEAD_SHA=cccccccccccccccccccccccccccccccccccccccc \
    PR_NUMBER=900 \
    SOURCE_GH_LOG="$source_gh_log" \
    GITHUB_OUTPUT="$source_output" \
    bash "$source_step"
)
test "$(cat "$source_output")" = "$(cat <<'EOF'
render_run_id=42
artifact_name=preview-render-pr-900-cccccccccccccccccccccccccccccccccccccccc
EOF
)"
test "$(cat "$source_gh_log")" = 'api repos/example/FireMUD/actions/runs/42'

run_closed_target_fixture() {
  local scenario="$1"
  local state="$2"
  local head_repository="$3"
  local base_ref="$4"
  local current_head_sha="$5"
  local event_head_sha="$6"
  local expected_status="$7"
  local output="$TEMP_DIR/closed-target-${scenario}.output"
  local error="$TEMP_DIR/closed-target-${scenario}.error"
  local status

  : >"$output"
  set +e
  (
    cd "$ROOT_DIR"
    env \
      PATH="$TEMP_DIR/bin:$PATH" \
      GH_TOKEN=fake \
      GITHUB_REPOSITORY=example/FireMUD \
      EVENT_NAME=pull_request_target \
      EVENT_ACTION=closed \
      WORKFLOW_RUN_ID='' \
      WORKFLOW_RUN_HEAD_SHA='' \
      EVENT_PR_NUMBER=900 \
      EVENT_HEAD_SHA="$event_head_sha" \
      INPUT_PR_NUMBER='' \
      INPUT_HEAD_SHA='' \
      INPUT_ACTION='' \
      TEST_PR_STATE="$state" \
      TEST_PR_HEAD_REPOSITORY="$head_repository" \
      TEST_PR_BASE_REF="$base_ref" \
      TEST_PR_HEAD_SHA="$current_head_sha" \
      GITHUB_OUTPUT="$output" \
      bash "$TEMP_DIR/target.sh"
  ) >"$TEMP_DIR/closed-target-${scenario}.stdout" 2>"$error"
  status=$?
  set -e

  [[ "$status" -eq "$expected_status" ]]
}

closed_head=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
run_closed_target_fixture accepted closed example/FireMUD develop \
  "$closed_head" "$closed_head" 0
test "$(cat "$TEMP_DIR/closed-target-accepted.output")" = "$(cat <<EOF
action=destroy
pr_number=900
base_sha=base-900
head_sha=${closed_head}
merge_sha=merge-900
image_tag=${closed_head}
namespace=pr-900
hostname=pr-900.preview.firedevops.net
EOF
)"
run_closed_target_fixture open-state open example/FireMUD develop \
  "$closed_head" "$closed_head" 0
test "$(cat "$TEMP_DIR/closed-target-open-state.output")" = 'action=none'
grep -Fxq 'Ignoring closed pull request because it has been reopened.' \
  "$TEMP_DIR/closed-target-open-state.stdout"
run_closed_target_fixture fork closed attacker/Fork develop \
  "$closed_head" "$closed_head" 0
test "$(cat "$TEMP_DIR/closed-target-fork.output")" = 'action=none'
uppercase_head=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
run_closed_target_fixture uppercase-head closed example/FireMUD develop \
  "$uppercase_head" "$uppercase_head" 0
grep -Fxq "head_sha=${closed_head}" "$TEMP_DIR/closed-target-uppercase-head.output"
grep -Fxq "image_tag=${closed_head}" "$TEMP_DIR/closed-target-uppercase-head.output"
run_closed_target_fixture invalid-head closed example/FireMUD develop \
  not-a-sha not-a-sha 0
test "$(cat "$TEMP_DIR/closed-target-invalid-head.output")" = 'action=none'
run_closed_target_fixture unsupported-base closed example/FireMUD feature \
  "$closed_head" "$closed_head" 0
test "$(cat "$TEMP_DIR/closed-target-unsupported-base.output")" = 'action=none'
grep -Fxq 'Ignoring closed pull request because its base branch is unsupported.' \
  "$TEMP_DIR/closed-target-unsupported-base.stdout"
run_closed_target_fixture stale-head closed example/FireMUD develop \
  "$closed_head" bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 0
test "$(cat "$TEMP_DIR/closed-target-stale-head.output")" = 'action=none'
grep -Fxq 'Ignoring stale lifecycle event for an earlier PR head.' \
  "$TEMP_DIR/closed-target-stale-head.stdout"

echo 'hosted identity controller workflow contract passed'
