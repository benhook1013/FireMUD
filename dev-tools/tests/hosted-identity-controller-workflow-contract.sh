#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

for required_command in helm jq kubectl openssl python3; do
  if ! command -v "$required_command" >/dev/null 2>&1; then
    echo "Missing required command: $required_command" >&2
    exit 1
  fi
done

trusted="$ROOT_DIR/.github/workflows/hosted-identity-request.yml"
preview="$ROOT_DIR/.github/workflows/preview.yml"
dev_demo="$ROOT_DIR/.github/workflows/dev-demo.yml"
runtime="$ROOT_DIR/.github/workflows/runtime-images.yml"
publisher="$ROOT_DIR/.github/workflows/publish-pr-runtime-images.yml"
kubeconfig_action="$ROOT_DIR/.github/actions/write-kubeconfig/action.yml"
helm_action="$ROOT_DIR/.github/actions/setup-helm/action.yml"
janitor="$ROOT_DIR/.github/workflows/preview-janitor.yml"
build_gradle="$ROOT_DIR/build.gradle.kts"
controller_build_gradle="$ROOT_DIR/services/hosted-environment-identity-controller/build.gradle.kts"
controller_dockerfile="$ROOT_DIR/services/hosted-environment-identity-controller/Dockerfile"
bootstrap="$ROOT_DIR/dev-tools/hosted/controller/bootstrap-hosted-identity-controller.sh"
waiter="$ROOT_DIR/dev-tools/hosted/preview/wait-for-hosted-identity.sh"
mode_resolver="$ROOT_DIR/dev-tools/hosted/shared/resolve-certificate-identity-mode.py"
mode_action="$ROOT_DIR/.github/actions/resolve-certificate-identity-mode/action.yml"
requester="$ROOT_DIR/dev-tools/hosted/shared/request-hosted-identity.sh"
artifact_validator="$ROOT_DIR/dev-tools/hosted/preview/validate-preview-artifact.py"
render_preview_values="$ROOT_DIR/dev-tools/hosted/preview/render-preview-values.py"
preview_annotator="$ROOT_DIR/dev-tools/hosted/preview/annotate-preview-namespace.sh"
runtime_rollout_waiter="$ROOT_DIR/dev-tools/hosted/shared/wait-for-hosted-runtime-rollouts.sh"
credential_source="$ROOT_DIR/dev-tools/hosted/preview/provision-runtime-credentials.sh"

contains() {
  grep -Fq -- "$2" "$1" || {
    echo "$1 must contain: $2" >&2
    exit 1
  }
}

contains "$runtime" 'services/hosted-environment-identity-controller/**'
# shellcheck disable=SC2016 # These assertions intentionally match literal publisher shell.
contains "$publisher" 'if ! docker image inspect "$image" >/dev/null 2>&1; then'
# shellcheck disable=SC2016 # This assertion intentionally matches literal publisher shell.
contains "$publisher" 'Required source artifact image for $service is missing: $image.'
contains "$build_gradle" '"buildHostedEnvironmentIdentityControllerImage"'
python3 - "$build_gradle" <<'PY'
import sys
from pathlib import Path

build_gradle = Path(sys.argv[1]).read_text(encoding="utf-8")
aggregate_start = build_gradle.index('tasks.register("buildDockerImages")')
aggregate_end = build_gradle.index(
    'tasks.register("buildHostedEnvironmentIdentityControllerImage")'
)
aggregate = build_gradle[aggregate_start:aggregate_end]
assert '"buildHostedEnvironmentIdentityControllerImage"' in aggregate
assert ':hosted-environment-identity-controller:bootBuildImage' not in aggregate
PY
contains "$controller_build_gradle" 'archiveFileName.set("hosted-environment-identity-controller.jar")'
contains "$controller_dockerfile" 'COPY --chown=firemud:firemud --chmod=644 hosted-environment-identity-controller.jar app.jar'
if grep -Fq -- '*.jar' "$controller_dockerfile"; then
  echo "$controller_dockerfile must copy only the canonical controller artifact" >&2
  exit 1
fi
python3 - "$runtime" "$publisher" <<'PY'
import re
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
publisher_workflow = yaml.safe_load(Path(sys.argv[2]).read_text(encoding="utf-8"))
image_meta = workflow["jobs"]["image-meta"]
assert image_meta["outputs"]["controller_smoke_required"] == (
    "${{ steps.controller_scope.outputs.controller_smoke_required }}"
)
assert image_meta["permissions"]["pull-requests"] == "read"
controller_scope = next(
    step
    for step in image_meta["steps"]
    if step.get("name") == "Detect controller image changes"
)
assert controller_scope["id"] == "controller_scope"
controller_scope_script = controller_scope["with"]["script"]
for required in (
    "let controllerSmokeRequired = true;",
    "github.rest.pulls.listFiles",
    "context.payload.pull_request.changed_files",
    "file.previous_filename",
    "services/hosted-environment-identity-controller/",
    "docker/base.Dockerfile",
    ".github/workflows/runtime-images.yml",
    "Controller change detection was incomplete; running its local smoke.",
    "Controller change detection failed; running its local smoke:",
    'core.setOutput("controller_smoke_required", String(controllerSmokeRequired))',
):
    assert required in controller_scope_script, required
for unrelated_service in (
    "services/account-service/",
    "services/game-session-service/",
    "services/tcp-proxy-service/",
):
    assert unrelated_service not in controller_scope_script

steps = workflow["jobs"]["pr-local-smoke"]["steps"]
steps_by_name = {
    step.get("name"): step for step in steps if isinstance(step, dict)
}
build_step = steps_by_name["Build controller image for credential-free local validation"]
smoke_step = steps_by_name["Smoke controller image entrypoint and paused health"]
export_step = steps_by_name["Export fixed-tag preview image artifact"]
upload_step = steps_by_name["Upload preview image artifact"]
assert steps.index(build_step) < steps.index(smoke_step) < steps.index(export_step)
controller_condition = (
    "${{ needs.image-meta.outputs.controller_smoke_required == 'true' }}"
)
assert build_step["if"] == controller_condition
assert smoke_step["if"] == controller_condition
assert "if" not in export_step
assert "if" not in upload_step
assert smoke_step["env"]["CONTROLLER_IMAGE"] == (
    "firemud-hosted-identity-controller-local:"
    "${{ needs.image-meta.outputs.image_tag }}"
)
smoke_run = smoke_step["run"]
exact_health_predicate = 'jq -e \'.status == "UP"\' <<<"$health" >/dev/null 2>&1'
for required in (
    'trap \'docker rm --force "$container_name" >/dev/null 2>&1 || true\' EXIT',
    'docker run --detach',
    '--env FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE=paused',
    '"$CONTROLLER_IMAGE"',
    'deadline=$((SECONDS + 300))',
    'while (( SECONDS < deadline )); do',
    'request_timeout=$((deadline - SECONDS))',
    '(( request_timeout > 0 )) || break',
    '(( request_timeout <= 5 )) || request_timeout=5',
    '--connect-timeout 2 --max-time "$request_timeout"',
    '(( SECONDS < deadline )) && sleep 1',
    "http://127.0.0.1:8081/actuator/health/liveness",
    exact_health_predicate,
    'docker rm --force "$container_name"',
):
    assert required in smoke_run, required
assert "--entrypoint" not in smoke_run
assert 'for _ in {1..300}; do' not in smoke_run
assert '[[ "$health" == *' not in smoke_run
export_run = export_step["run"]
assert "hosted-environment-identity-controller" not in export_run
assert "account-service" in export_run

runtime_services_match = re.search(
    r"for service in \\\n(?P<services>(?:\s+[a-z0-9-]+ \\\n)*\s+[a-z0-9-]+); do",
    export_run,
)
assert runtime_services_match, "runtime artifact service allowlist is not parseable"
runtime_services = [
    line.removesuffix(" \\").strip()
    for line in runtime_services_match.group("services").splitlines()
]
publisher_steps = publisher_workflow["jobs"]["publish"]["steps"]
publisher_run = next(
    step["run"]
    for step in publisher_steps
    if step.get("name") == "Publish fixed PR image tags"
)
publisher_services_match = re.search(
    r"^\s*services=\(\n(?P<services>(?:\s+[a-z0-9-]+\n)+)\s*\)$",
    publisher_run,
    re.MULTILINE,
)
assert publisher_services_match, "trusted publisher service allowlist is not parseable"
publisher_services = [
    line.strip()
    for line in publisher_services_match.group("services").splitlines()
]
assert publisher_services == runtime_services, (
    "trusted publisher service allowlist must exactly match the PR runtime artifact list",
    publisher_services,
    runtime_services,
)
PY

nested_status_health='{"components":{"controller":{"status":"UP"}}}'
if jq -e '.status == "UP"' <<<"$nested_status_health" >/dev/null 2>&1; then
  echo "controller health proof accepted a nested-only UP status" >&2
  exit 1
fi
jq -e '.status == "UP"' <<<'{"status":"UP","components":{"controller":{"status":"DOWN"}}}' \
  >/dev/null

# The shared kubeconfig action is the only workflow credential-file writer.
# shellcheck disable=SC2016 # These assertions intentionally match literal action source.
for required in \
  'description: Write a validated kubeconfig to a private runner file and write KUBECONFIG to GITHUB_ENV by default' \
  'export-to-github-env:' \
  "default: 'true'" \
  'using: composite' \
  'EXPORT_TO_GITHUB_ENV: ${{ inputs.export-to-github-env }}' \
  'umask 077' \
  '[[ -n "$KUBECONFIG_CONTENT" ]]' \
  'command -v kubectl >/dev/null 2>&1 || {' \
  '[[ "$EXPORT_TO_GITHUB_ENV" == true || "$EXPORT_TO_GITHUB_ENV" == false ]] || {' \
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
  "echo 'version=v3.20.1'" \
  "echo 'sha256=0165ee4a2db012cc657381001e593e981f42aa5707acdd50658326790c9d0dc3'" \
  'uses: actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9' \
  'path: ${{ runner.temp }}/firemud-helm/${{ steps.pinned-release.outputs.version }}/helm.tar.gz' \
  'key: firemud-helm-${{ runner.os }}-${{ runner.arch }}-${{ steps.pinned-release.outputs.version }}-${{ steps.pinned-release.outputs.sha256 }}' \
  'HELM_VERSION: ${{ steps.pinned-release.outputs.version }}' \
  'HELM_SHA256: ${{ steps.pinned-release.outputs.sha256 }}' \
  'RUNNER_OS' \
  'RUNNER_ARCH' \
  'RUNNER_TEMP' \
  'helm_root="${RUNNER_TEMP:?}/firemud-helm/${helm_version}"' \
  '[[ ! -f "$archive_path" ]]' \
  'temporary_archive="$(mktemp -- "${helm_root}/helm.tar.gz.XXXXXX")"' \
  'mv -fT -- "$temporary_archive" "$archive_path"' \
  'curl -fsSL --retry 3 --retry-delay 2 --retry-max-time 30' \
  'sha256sum --check --status' \
  'echo "$install_dir" >> "$GITHUB_PATH"' \
  'version --template' \
  'Helm version mismatch' \
  'Expected ${helm_version}, but the installed Helm binary reported ${reported_version}.'; do
  contains "$helm_action" "$required"
done
if [[ "$(grep -Fc 'sha256sum --check --status' "$helm_action")" -lt 2 ]]; then
  echo "$helm_action must verify both restored and downloaded Helm archives" >&2
  exit 1
fi
# shellcheck disable=SC2016 # These assertions intentionally match literal shell source.
for forbidden in \
  'if command -v helm' \
  'installed_helm="$(command -v helm)"' \
  'Using already-installed Helm'; do
  if grep -Fq -- "$forbidden" "$helm_action"; then
    echo "$helm_action must not reuse an arbitrary PATH Helm binary: $forbidden" >&2
    exit 1
  fi
done
if grep -Fq -- '--retry-all-errors' "$helm_action"; then
  echo "$helm_action must not retry non-transient curl failures" >&2
  exit 1
fi
python3 - "$helm_action" <<'PY'
import os
import subprocess
import sys

import yaml

with open(sys.argv[1], encoding="utf-8") as action_file:
    action = yaml.safe_load(action_file)

steps = action["runs"]["steps"]
step_by_name = {step["name"]: step for step in steps}
validation = step_by_name["Validate supported runner"]
restore = step_by_name["Restore pinned Helm archive"]
install = step_by_name["Install pinned Helm"]
assert steps.index(validation) < steps.index(restore) < steps.index(install)

validation_run = validation["run"]
for runner_os, runner_arch, expected_returncode in (
    ("Linux", "X64", 0),
    ("Windows", "X64", 1),
    ("Linux", "ARM64", 1),
):
    environment = os.environ.copy()
    environment.update(RUNNER_OS=runner_os, RUNNER_ARCH=runner_arch)
    result = subprocess.run(
        ["bash", "-c", validation_run],
        check=False,
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    assert result.returncode == expected_returncode, (runner_os, runner_arch, result)

install_run = install["run"]
remove_index = install_run.index('rm -rf -- "$extraction_dir"')
recreate_index = install_run.index('mkdir -p -- "$extraction_dir"')
extract_index = install_run.index(
    'tar -xzf "$archive_path" -C "$extraction_dir" -- linux-amd64/helm'
)
install_index = install_run.index(
    'install -m 0755 "$extraction_dir/linux-amd64/helm" "$install_dir/helm"'
)
assert remove_index < recreate_index < extract_index < install_index
assert 'tar -xzf "$archive_path" -C "$extraction_dir"\n' not in install_run
PY
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
python3 - "$bootstrap" <<'PY'
import sys
from pathlib import Path

source = Path(sys.argv[1]).read_text(encoding="utf-8")
start = source.index("controller_activation_mode() {")
end = source.index("\n}\n\nextract_named_yaml_document()", start)
probe = source[start:end]
assert "sed " not in probe
for required in (
    "yaml.safe_load_all",
    'document.get("kind") == "Deployment"',
    'document["metadata"].get("name") == deployment_name',
    'container.get("name") == "controller"',
    'entry.get("name") == "FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE"',
    "len(deployments) != 1",
    "len(controller_containers) != 1",
    "len(activation_entries) != 1",
    'operation == "read"',
    'operation == "replace"',
):
    assert required in probe, required
assert source.count("controller_activation_mode read") == 2
assert (
    'controller_activation_mode replace \\\n'
    '    "$initial_activation_mode" "$ACTIVATION_MODE"'
) in source
assert "/FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE/{n;" not in source
PY

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
contains "$waiter" 'projection_attempted=true'
# shellcheck disable=SC2016 # Match the literal timeout classification branch.
contains "$waiter" 'if [[ "$projection_attempted" == true ]]; then'
contains "$waiter" 'Skipped waiting for complete controller projection'
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
contains "$mode_resolver" 'found duplicate certificateIdentity.mode through YAML merge'
if grep -Fq -- 'found duplicate previewStack.certificateIdentity.mode through YAML merge' "$mode_resolver"; then
  echo "$mode_resolver must use a location-neutral duplicate merge diagnostic" >&2
  exit 1
fi
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
contains "$runtime_rollout_waiter" 'runtime namespace must match dev or pr-[1-9][0-9]{0,50}'
contains "$runtime_rollout_waiter" 'per_deployment_timeout_seconds must be an integer between 1 and 3600'
contains "$runtime_rollout_waiter" 'This bound applies independently to each deployment rollout.'

# Dev-demo is the prerequisite's only active consumer integration. It preserves
# standalone operation and gates controller requests/waits on resolved mode.
contains "$dev_demo" 'uses: ./.github/actions/resolve-certificate-identity-mode'
contains "$dev_demo" "steps.certificate-identity.outputs.mode == 'hosted-controller'"
contains "$dev_demo" 'request-hosted-identity.sh dev-demo Active'
contains "$dev_demo" 'request-hosted-identity.sh dev-demo Retired'
# shellcheck disable=SC2016 # This assertion intentionally matches literal workflow interpolation.
contains "$dev_demo" '--projections dev-demo "${{ needs.dev-demo-plan.outputs.namespace }}" 900'
contains "$dev_demo" 'wait-for-hosted-identity.sh'
contains "$dev_demo" 'ensure-grpc-tls-secret.sh'
contains "$dev_demo" "steps.certificate-identity.outputs.mode == 'standalone'"

python3 - "$trusted" "$preview" "$preview_annotator" "$dev_demo" "$publisher" "$credential_source" "$janitor" "$mode_action" "$runtime" <<'PY'
import os
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

trusted_source = Path(sys.argv[1]).read_text(encoding="utf-8")
workflow = yaml.safe_load(trusted_source)
preview_workflow = yaml.safe_load(Path(sys.argv[2]).read_text(encoding="utf-8"))
preview_annotator = Path(sys.argv[3]).read_text(encoding="utf-8")
dev_demo_workflow = yaml.safe_load(Path(sys.argv[4]).read_text(encoding="utf-8"))
publisher_workflow = yaml.safe_load(Path(sys.argv[5]).read_text(encoding="utf-8"))
janitor_workflow = yaml.safe_load(Path(sys.argv[7]).read_text(encoding="utf-8"))
mode_action = yaml.safe_load(Path(sys.argv[8]).read_text(encoding="utf-8"))
runtime_workflow = yaml.safe_load(Path(sys.argv[9]).read_text(encoding="utf-8"))

expected_mode_step = {
    "name": "Resolve certificate identity mode",
    "id": "certificate-identity",
    "uses": "./.github/actions/resolve-certificate-identity-mode",
    "with": {
        "values-file": "k8s/helm/firemud/values-hosted-shared.example.yaml",
    },
}


def assert_mode_step(step: dict, owner: str) -> None:
    assert step == expected_mode_step, (owner, step)


assert set(mode_action["inputs"]) == {"values-file"}
assert mode_action["inputs"]["values-file"]["required"] is True
assert set(mode_action["outputs"]) == {"mode"}
assert mode_action["outputs"]["mode"]["value"] == "${{ steps.resolve.outputs.mode }}"
assert mode_action["runs"]["using"] == "composite"
assert len(mode_action["runs"]["steps"]) == 1
resolve_step = mode_action["runs"]["steps"][0]
assert resolve_step["id"] == "resolve"
assert resolve_step["shell"] == "bash"
assert resolve_step["env"] == {"VALUES_FILE": "${{ inputs['values-file'] }}"}
resolve_run = resolve_step["run"]
assert '[[ -n "$VALUES_FILE" ]]' in resolve_run
assert (
    '"$GITHUB_ACTION_PATH/../../../dev-tools/hosted/shared/'
    'resolve-certificate-identity-mode.py"'
) in resolve_run
assert "$GITHUB_WORKSPACE" not in resolve_run
assert 'case "$mode" in' in resolve_run
assert "standalone|hosted-controller) ;;" in resolve_run
assert "Resolver output must be exactly standalone or hosted-controller." in resolve_run
assert resolve_run.index('case "$mode" in') < resolve_run.index(
    "printf 'mode=%s\\n' \"$mode\" >> \"$GITHUB_OUTPUT\""
)
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
    "prepare-runtime": "needs.validate-target.outputs.action == 'deploy'",
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
assert_mode_step(mode_step, "hosted identity request")
target_step = next(step for step in validate_job["steps"] if step.get("id") == "target")
assert "Unsupported lifecycle event" in target_step["run"]
for job_name in ("prepare-runtime", "deploy-runtime", "verify-runtime", "destroy-runtime", "retire-identity"):
    assert "certificate_identity_mode == 'hosted-controller'" in jobs[job_name]["if"], job_name
assert validate_job["if"] == (
    "${{ (github.event_name == 'workflow_run' && "
    "github.event.workflow_run.event == 'pull_request' && "
    "github.event.workflow_run.conclusion == 'success' && "
    "github.event.workflow_run.head_repository.full_name == github.repository && "
    "github.event.workflow_run.name == 'PR Preview Environment') || "
    "(github.event_name == 'pull_request_target' && "
    "github.event.action == 'closed' && "
    "github.event.pull_request.head.repo.full_name == github.repository) }}"
)
assert validate_job["permissions"] == {
    "actions": "read",
    "contents": "read",
    "pull-requests": "read",
}
assert validate_job["timeout-minutes"] == 10
assert jobs["prepare-runtime"]["permissions"] == {
    "actions": "read",
    "contents": "read",
    "issues": "write",
    "pull-requests": "read",
}
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
assert jobs["prepare-runtime"]["timeout-minutes"] == 45
assert jobs["deploy-runtime"]["timeout-minutes"] == 45
for job_name, step_name in (
    ("destroy-runtime", "Revalidate preview cleanup target before runtime deletion"),
    ("retire-identity", "Revalidate preview cleanup target before identity retirement"),
):
    cleanup_step = next(
        step for step in jobs[job_name]["steps"] if step.get("name") == step_name
    )
    assert cleanup_step["run"].strip() == (
        "bash ./dev-tools/hosted/preview/revalidate-preview-deploy.sh \\\n"
        '  --cleanup "$PR_NUMBER" "$EXPECTED_HEAD_SHA"'
    )

publisher_steps = publisher_workflow["jobs"]["publish"]["steps"]
publisher_script = next(
    step["run"]
    for step in publisher_steps
    if step.get("name") == "Publish fixed PR image tags"
)
missing_image_check = 'if ! docker image inspect "$image" >/dev/null 2>&1; then'
assert missing_image_check in publisher_script
assert publisher_script.count(
    'echo "Required source artifact image for $service is missing: $image." >&2'
) == 1
assert 'missing_source_images+=("$image")' in publisher_script
assert 'if (( ${#missing_source_images[@]} > 0 )); then' in publisher_script
preflight_index = publisher_script.index(missing_image_check)
failure_index = publisher_script.index(
    'echo "Required source artifact is incomplete; ${#missing_source_images[@]} runtime image(s) are missing. Nothing was published." >&2'
)
publish_index = publisher_script.index(
    'if docker manifest inspect "$image" >/dev/null 2>&1; then'
)
assert preflight_index < failure_index < publish_index
for obsolete_optional_controller_fragment in (
    "hosted-environment-identity-controller; do",
    "hosted-environment-identity-controller\\n",
    'if [[ "$service" == hosted-environment-identity-controller ]]; then',
    "Optional controller image unavailable",
    "firemud-hosted-identity-controller-image-unavailable",
    "Source artifact does not contain optional",
):
    assert obsolete_optional_controller_fragment not in publisher_script

with tempfile.TemporaryDirectory() as publisher_fixture_dir:
    fixture_root = Path(publisher_fixture_dir)
    docker_calls = fixture_root / "docker-calls"
    fake_docker = fixture_root / "docker"
    fake_docker.write_text(
        """#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$DOCKER_CALLS"
if [[ "$1 $2" == "image inspect" ]]; then
  case "$3" in
    *account-service*|*tcp-proxy-service*) exit 1 ;;
    *) exit 0 ;;
  esac
fi
if [[ "$1 $2" == "manifest inspect" ]]; then
  exit 1
fi
if [[ "$1" == push ]]; then
  exit 0
fi
exit 99
""",
        encoding="utf-8",
    )
    fake_docker.chmod(0o755)
    fixture_env = os.environ.copy()
    fixture_env.update(
        DOCKER_CALLS=str(docker_calls),
        IMAGE_TAG="fixture-head",
        PATH=f"{fixture_root}:{fixture_env['PATH']}",
    )
    result = subprocess.run(
        ["bash", "-c", publisher_script],
        check=False,
        env=fixture_env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    assert result.returncode == 1, result
    assert "account-service is missing" in result.stderr
    assert "tcp-proxy-service is missing" in result.stderr
    assert "2 runtime image(s) are missing. Nothing was published." in result.stderr
    calls = docker_calls.read_text(encoding="utf-8").splitlines()
    assert len([call for call in calls if call.startswith("image inspect ")]) == 11
    assert not any(call.startswith("manifest inspect ") for call in calls)
    assert not any(call.startswith("push ") for call in calls)

runtime_jobs = runtime_workflow["jobs"]
trusted_build = runtime_jobs["build-runtime-images"]
base_job = runtime_jobs["build-base-image"]
base_build_steps = [
    step for step in base_job["steps"]
    if step.get("uses", "").startswith("docker/build-push-action@")
]
assert len(base_build_steps) == 2
assert all(step["with"].get("push") is True for step in base_build_steps)
assert base_build_steps[0]["id"] == "build_base_image_first"
assert base_build_steps[1]["id"] == "build_base_image_retry"
assert "digest" in base_job["outputs"]
digest_capture_steps = [
    step for step in base_job["steps"]
    if "build_base_image_first.outputs.digest" in str(step)
    or "build_base_image_retry.outputs.digest" in str(step)
]
assert digest_capture_steps
assert any("sha256:[0-9a-f]" in str(step) for step in digest_capture_steps)
digest_selector = next(step for step in base_job["steps"] if step.get("id") == "select_base_image_digest")
digest_selector_run = digest_selector["run"]
assert "published_digests" in digest_selector_run
assert "FIRST_DIGEST" in digest_selector["env"]
assert "RETRY_DIGEST" in digest_selector["env"]
assert "sha256:[0-9a-f]" in digest_selector_run
assert "${#published_digests[@]}" in digest_selector_run
assert 'echo "digest=' in digest_selector_run
assert "github.event_name != 'pull_request'" in trusted_build["if"]
matrix_entries = trusted_build["strategy"]["matrix"]["service"]
assert "hosted-environment-identity-controller" not in matrix_entries
controller_build_job = runtime_jobs["build-hosted-identity-controller"]
assert controller_build_job["needs"] == ["image-meta", "build-base-image"]
assert controller_build_job["timeout-minutes"] == 25
assert all(token in controller_build_job["if"] for token in (
    "github.event_name != 'pull_request'", "github.ref == 'refs/heads/main'", "github.ref == 'refs/heads/develop'"
))
assert controller_build_job["permissions"] == {"contents": "read"}
assert all(
    permission not in controller_build_job["permissions"]
    for permission in ("packages", "id-token", "attestations")
)
assert controller_build_job["outputs"]["image_id"] == "${{ steps.smoke.outputs.image_id }}"
controller_build_steps = controller_build_job["steps"]
checkout = next(step for step in controller_build_steps if step.get("uses", "").startswith("actions/checkout@"))
assert checkout["with"]["persist-credentials"] is False
assert checkout["with"]["ref"] == "${{ needs.image-meta.outputs.checkout_ref }}"
assert controller_build_job["env"]["CONTROLLER_IMAGE"] == (
    "ghcr.io/benhook1013/firemud-hosted-identity-controller:${{ needs.image-meta.outputs.image_tag }}"
)
build_index = next(i for i, step in enumerate(controller_build_steps) if step.get("name") == "Build controller JAR and image locally")
smoke_index = next(i for i, step in enumerate(controller_build_steps) if step.get("name") == "Smoke exact controller image")
export_index = next(i for i, step in enumerate(controller_build_steps) if step.get("name") == "Export exact verified controller image artifact")
upload_index = next(i for i, step in enumerate(controller_build_steps) if step.get("name") == "Upload exact verified controller image artifact")
assert build_index < smoke_index < export_index < upload_index
assert "--tag \"$CONTROLLER_IMAGE\"" in controller_build_steps[build_index]["run"]
assert "ghcr.io/benhook1013/firemud-base@${{ needs.build-base-image.outputs.digest }}" in controller_build_steps[build_index]["run"]
trusted_smoke_run = controller_build_steps[smoke_index]["run"]
exact_health_predicate = 'jq -e \'.status == "UP"\' <<<"$health" >/dev/null 2>&1'
for required in (
    'deadline=$((SECONDS + 300))',
    'while (( SECONDS < deadline )); do',
    'request_timeout=$((deadline - SECONDS))',
    '(( request_timeout > 0 )) || break',
    '(( request_timeout <= 5 )) || request_timeout=5',
    '--connect-timeout 2 --max-time "$request_timeout"',
    '(( SECONDS < deadline )) && sleep 1',
    exact_health_predicate,
):
    assert required in trusted_smoke_run, required
assert 'for _ in {1..300}; do' not in trusted_smoke_run
assert '[[ "$health" == *' not in trusted_smoke_run
assert "docker/login-action@" not in str(controller_build_job)
assert "docker push" not in str(controller_build_job)
assert "actions/attest@" not in str(controller_build_job)
export_run = controller_build_steps[export_index]["run"]
assert controller_build_steps[export_index]["env"] == {
    "VERIFIED_IMAGE_ID": "${{ steps.smoke.outputs.image_id }}",
}
assert 'docker save "$CONTROLLER_IMAGE" | gzip -1 > "$RUNNER_TEMP/hosted-identity-controller.tar.gz"' in export_run
assert 'current_image_id="$(docker image inspect --format' in export_run
assert '[[ "$current_image_id" == "$VERIFIED_IMAGE_ID" ]]' in export_run
assert controller_build_steps[upload_index]["uses"] == (
    "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a"
)
assert controller_build_steps[upload_index]["with"] == {
    "name": "hosted-identity-controller-${{ needs.image-meta.outputs.image_tag }}",
    "path": "${{ runner.temp }}/hosted-identity-controller.tar.gz",
    "if-no-files-found": "error",
    "retention-days": 1,
}

controller_publish_job = runtime_jobs["publish-hosted-identity-controller"]
assert controller_publish_job["needs"] == [
    "image-meta",
    "build-base-image",
    "build-hosted-identity-controller",
]
assert controller_publish_job["timeout-minutes"] == 25
assert all(token in controller_publish_job["if"] for token in (
    "always()",
    "github.event_name != 'pull_request'",
    "github.ref == 'refs/heads/main'",
    "github.ref == 'refs/heads/develop'",
    "needs.image-meta.result == 'success'",
    "needs.build-base-image.result == 'success'",
    "needs.build-hosted-identity-controller.result == 'success'",
))
assert controller_publish_job["permissions"] == {
    "contents": "read",
    "packages": "write",
    "id-token": "write",
    "attestations": "write",
}
assert controller_publish_job["env"] == controller_build_job["env"]
controller_publish_steps = controller_publish_job["steps"]
download_index = next(i for i, step in enumerate(controller_publish_steps) if step.get("name") == "Download exact verified controller image artifact")
load_index = next(i for i, step in enumerate(controller_publish_steps) if step.get("name") == "Load and verify exact controller image")
login_index = next(i for i, step in enumerate(controller_publish_steps) if step.get("name") == "Login to GHCR")
publish_index = next(i for i, step in enumerate(controller_publish_steps) if step.get("name") == "Publish exact verified controller image")
assert download_index < load_index < login_index < publish_index
assert controller_publish_steps[download_index]["uses"] == (
    "actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c"
)
assert controller_publish_steps[download_index]["with"] == {
    "name": "hosted-identity-controller-${{ needs.image-meta.outputs.image_tag }}",
    "path": "${{ runner.temp }}/hosted-identity-controller-artifact",
}
load_run = controller_publish_steps[load_index]["run"]
assert controller_publish_steps[load_index]["env"] == {
    "CONTROLLER_ARCHIVE": "${{ runner.temp }}/hosted-identity-controller-artifact/hosted-identity-controller.tar.gz",
    "VERIFIED_IMAGE_ID": "${{ needs.build-hosted-identity-controller.outputs.image_id }}",
}
for required in (
    'gzip -dc "$CONTROLLER_ARCHIVE" | docker load',
    'loaded_image_id="$(docker image inspect --format',
    '[[ "$loaded_image_id" == "$VERIFIED_IMAGE_ID" ]]',
):
    assert required in load_run, required
publish_run = controller_publish_steps[publish_index]["run"]
assert "./gradlew" not in str(controller_publish_steps)
assert "docker build" not in str(controller_publish_steps)
assert "docker run" not in str(controller_publish_steps)
assert "health/liveness" not in str(controller_publish_steps)
assert 'docker push "$CONTROLLER_IMAGE"' in publish_run
assert "docker manifest inspect" not in publish_run
assert "current_image_id" in publish_run
assert "pushed_digest" in publish_run
assert "docker buildx imagetools inspect" not in publish_run
assert "push_output" in publish_run
assert "pushed_digests" in publish_run
assert '${#pushed_digests[@]} != 1' in publish_run
assert "BASH_REMATCH[1]" in publish_run
attest_step = next(step for step in controller_publish_steps if step.get("uses") == "actions/attest@1e69f48acb82d1966a394da916b4c1698aa569d6")
assert attest_step["with"]["subject-name"] == "${{ env.CONTROLLER_IMAGE_NAME }}"
assert attest_step["with"]["subject-digest"] == "${{ steps.publish.outputs.digest }}"
assert controller_publish_steps.index(attest_step) > publish_index
assert attest_step["with"]["subject-name"] == "${{ env.CONTROLLER_IMAGE_NAME }}"
assert attest_step["with"]["push-to-registry"] is True
assert attest_step["with"]["create-storage-record"] is False
assert controller_publish_steps[publish_index]["id"] == "publish"
assert "pushed_digest" in publish_run
assert "sha256:[0-9a-f]" in publish_run
assert "hosted-environment-identity-controller" not in publisher_script
janitor_steps = janitor_workflow["jobs"]["prune-stale-preview-namespaces"]["steps"]
janitor_mode_step = next(
    step for step in janitor_steps if step.get("id") == "certificate-identity"
)
assert_mode_step(janitor_mode_step, "preview janitor")
janitor_prune_step = next(
    step for step in janitor_steps if step.get("name") == "Prune stale preview namespaces"
)
janitor_runtime_writer = next(
    step for step in janitor_steps if step.get("name") == "Write preview kubeconfig"
)
janitor_requester_step = next(
    step
    for step in janitor_steps
    if step.get("name") == "Write hosted identity requester kubeconfig"
)
janitor_verify_step = next(
    step for step in janitor_steps if step.get("name") == "Verify preview cluster access"
)
janitor_cleanup_step = next(
    step
    for step in janitor_steps
    if step.get("name") == "Remove hosted identity requester kubeconfig"
)
assert janitor_requester_step["with"] == {
    "content": "${{ secrets.HOSTED_IDENTITY_REQUESTER_KUBECONFIG }}",
    "path": "${{ runner.temp }}/hosted-identity-requester.kubeconfig",
    "export-to-github-env": "false",
}
assert janitor_prune_step["env"]["HOSTED_IDENTITY_REQUESTER_KUBECONFIG"] == (
    "${{ runner.temp }}/hosted-identity-requester.kubeconfig"
)
janitor_runtime_writer_run = janitor_runtime_writer["run"]
for required in (
    'KUBECONFIG_PATH="$(bash ./dev-tools/hosted/shared/write-kubeconfig.sh)"',
    'persist-runner-kubeconfig.sh "$KUBECONFIG_PATH"',
    'echo "PREVIEW_RUNTIME_KUBECONFIG=$KUBECONFIG_PATH" >> "$GITHUB_ENV"',
):
    assert required in janitor_runtime_writer_run, required
assert 'echo "KUBECONFIG=$KUBECONFIG_PATH"' not in janitor_runtime_writer_run
janitor_runtime_path_expression = "${{ env.PREVIEW_RUNTIME_KUBECONFIG }}"
for consumer in (janitor_verify_step, janitor_prune_step):
    assert consumer["env"]["KUBECONFIG"] == janitor_runtime_path_expression
    assert "${{ runner.temp }}/preview-kubeconfig.yaml" not in str(consumer)
assert "export HOSTED_IDENTITY_REQUESTER_KUBECONFIG=" not in janitor_prune_step["run"]
assert "${{ runner.temp }}/hosted-identity-requester.kubeconfig" not in janitor_prune_step["run"]
assert not any(
    step.get("name") == "Restore preview kubeconfig" for step in janitor_steps
)
assert janitor_steps.index(janitor_runtime_writer) < janitor_steps.index(
    janitor_requester_step
) < janitor_steps.index(janitor_verify_step) < janitor_steps.index(janitor_prune_step)
assert janitor_steps.index(janitor_cleanup_step) > janitor_steps.index(janitor_prune_step)
assert janitor_cleanup_step.get("if") == "${{ always() }}"
assert janitor_cleanup_step.get("run") == (
    'rm -f -- "$RUNNER_TEMP/hosted-identity-requester.kubeconfig"'
)
target_step = next(step for step in validate_job["steps"] if step.get("id") == "target")
target_script = target_step["run"]
workflow_run_start = target_script.rindex('if [[ "$EVENT_NAME" == workflow_run ]]; then')
destroy_branch_start = target_script.index("else\n", workflow_run_start)
workflow_branch_end = target_script.index("\nfi\n", destroy_branch_start)
shared_head_guard = '[[ "$current_head_sha" == "$EXPECTED_HEAD_SHA" ]] || emit_no_action'
assert target_script.count(shared_head_guard) == 1
assert target_script.index(shared_head_guard) > workflow_branch_end
for label_fragment in (
    'labels_json="$(jq -c',
    'label_metadata="$(python3',
    'labels_valid="$(sed -n',
):
    label_index = target_script.index(label_fragment)
    assert workflow_run_start < label_index < destroy_branch_start, label_fragment
for fragment in (
    '[[ -n "$WORKFLOW_RUN_ID" ]] || {',
    '[[ "$source_path" == ',
    'Missing workflow run id',
    'Unexpected source workflow',
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
assert 'test -n "$render_run_id"' not in source_script
assert 'Missing source run id::Expected a non-empty workflow run id' in source_script
for source_field, expected in (
    ("conclusion", "success"),
    ("head", '"$HEAD_SHA"'),
    ("path", "'.github/workflows/preview.yml'"),
    ("event", "pull_request"),
    ("repository", '"$GITHUB_REPOSITORY"'),
):
    assert f"require_source_field {source_field} {expected}" in source_script
assert "Expected %q; actual %q." in source_script

deploy_steps = jobs["deploy-runtime"]["steps"]
deploy_by_name = {
    step.get("name"): step for step in deploy_steps if isinstance(step, dict)
}
artifact_verification = deploy_by_name[
    "Verify artifact provenance, checksum, and closed object set"
]
assert artifact_verification["env"]["PREVIEW_HOSTNAME"] == (
    "${{ needs.validate-target.outputs.hostname }}"
)
assert "HOSTNAME" not in artifact_verification["env"]
assert '"$HEAD_SHA" "$MERGE_SHA" "$IMAGE_TAG" "$PREVIEW_HOSTNAME"' in (
    artifact_verification["run"]
)
active_request = deploy_by_name["Apply canonical Active request"]
assert active_request["run"] == (
    'bash ./dev-tools/hosted/shared/request-hosted-identity.sh "$IDENTITY_NAME" Active'
)
deploy_runtime_write = deploy_by_name["Write runtime kubeconfig"]
deploy_requester_write = deploy_by_name["Write requester kubeconfig"]
assert deploy_runtime_write["with"]["path"] == (
    "${{ runner.temp }}/preview-runtime.kubeconfig"
)
assert deploy_requester_write["with"] == {
    "content": "${{ secrets.HOSTED_IDENTITY_REQUESTER_KUBECONFIG }}",
    "path": "${{ runner.temp }}/hosted-identity-requester.kubeconfig",
    "export-to-github-env": "false",
}
assert deploy_by_name["Apply canonical Active request"]["env"] == {
    "IDENTITY_NAME": "pr-${{ needs.validate-target.outputs.pr_number }}",
    "KUBECONFIG": "${{ runner.temp }}/hosted-identity-requester.kubeconfig",
}
assert (
    deploy_steps.index(deploy_runtime_write)
    < deploy_steps.index(deploy_requester_write)
    < deploy_steps.index(active_request)
)
deploy_requester_cleanup = deploy_by_name["Remove requester kubeconfig"]
assert deploy_requester_cleanup["if"] == "${{ always() }}"
assert deploy_requester_cleanup["run"] == (
    'rm -f -- "$RUNNER_TEMP/hosted-identity-requester.kubeconfig"'
)
assert deploy_steps.index(active_request) < deploy_steps.index(deploy_requester_cleanup)
assert "Remember preview runtime kubeconfig" not in deploy_by_name
assert "Restore preview runtime kubeconfig" not in deploy_by_name
runtime_kubeconfig_path = "${{ runner.temp }}/preview-runtime.kubeconfig"
runtime_kubeconfig_cleanup = 'rm -f -- "$RUNNER_TEMP/preview-runtime.kubeconfig"'
runtime_kubeconfig_jobs = set()
for job_name, job in jobs.items():
    steps = job.get("steps", [])
    writes = [
        step
        for step in steps
        if isinstance(step, dict)
        and isinstance(step.get("with"), dict)
        and step["with"].get("path") == runtime_kubeconfig_path
    ]
    if not writes:
        continue
    runtime_kubeconfig_jobs.add(job_name)
    assert len(writes) == 1, job_name
    cleanup = next(
        step for step in steps if step.get("name") == "Remove runtime kubeconfig"
    )
    assert cleanup["if"] == "${{ always() }}", job_name
    assert cleanup["run"] == runtime_kubeconfig_cleanup, job_name
    assert steps[-1] == cleanup, job_name
assert runtime_kubeconfig_jobs == {"deploy-runtime", "verify-runtime", "destroy-runtime"}
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
privileged_validation_guards = {
    "Allocate stable preview Telnet port": (
        ('[[ "$port" =~ ^32(00[0-9]|01[0-5])$ ]] || {', "Invalid allocated Telnet port"),
    ),
    "Create and annotate exact preview runtime namespace": (
        ('[[ "$RUNTIME_NAMESPACE" == "pr-${PR_NUMBER}" ]] || {', "Invalid preview runtime namespace"),
        ('[[ "$TELNET_PORT" =~ ^32(00[0-9]|01[0-5])$ ]] || {', "Invalid allocated Telnet port"),
        (
            '[[ "$ALLOCATION_TIMESTAMP" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[^[:space:]]+Z$ ]] || {',
            "Invalid allocation timestamp",
        ),
    ),
    "Record exact deployed preview head": (
        ('[[ "$RUNTIME_NAMESPACE" == "pr-${PR_NUMBER}" ]] || {', "Invalid preview runtime namespace"),
        ('[[ "$HEAD_SHA" =~ ^[0-9a-f]{40}$ ]] || {', "Invalid preview head SHA"),
    ),
}
for step_name, guards in privileged_validation_guards.items():
    step_run = deploy_by_name[step_name]["run"]
    for predicate, error_title in guards:
        assert predicate in step_run
        assert f"::error title={error_title}::" in step_run
inject_step = deploy_by_name["Inject trusted allocated Telnet port"]["run"]
assert '"$RUNTIME_NAMESPACE" "$TELNET_PORT"' in inject_step
for step_name in ("Allocate stable preview Telnet port", "Create and annotate exact preview runtime namespace"):
    assert "actual value was ${" in deploy_by_name[step_name]["run"]
assert "Validate trusted preview runtime target" not in deploy_by_name
assert "Final revalidate open PR before server dry-run and apply" not in deploy_by_name
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
    (jobs["verify-runtime"]["steps"], "Wait for runtime rollouts"),
):
    rollout_step = next(step for step in rollout_steps if step.get("name") == step_name)
    assert rollout_step["run"].count(runtime_rollout_call) == 1, step_name
    assert '"$RUNTIME_NAMESPACE" 120' in rollout_step["run"], step_name
    assert "for deployment in" not in rollout_step["run"], step_name
    assert "rollout status" not in rollout_step["run"], step_name

assert "concurrency" not in jobs["prepare-runtime"]
assert "concurrency" not in jobs["verify-runtime"]
for job_name in ("deploy-runtime", "destroy-runtime", "retire-identity"):
    assert jobs[job_name]["concurrency"] == {
        "group": "preview-allocation-lifecycle",
        "cancel-in-progress": False,
        "queue": "max",
    }
assert (
    "# destroy-runtime releases this lifecycle lock; retire-identity reacquires it "
    "and rechecks identity before retirement."
) in trusted_source
prepare_by_name = {
    step.get("name"): step
    for step in jobs["prepare-runtime"]["steps"]
    if isinstance(step, dict)
}
assert "Wait for fixed-head runtime images" in prepare_by_name
assert "Wait for fixed-head runtime images" not in deploy_by_name
assert "Wait for exact controller identity readiness" not in deploy_by_name
assert "Wait for runtime rollouts before operator validation" not in deploy_by_name

verify_steps = jobs["verify-runtime"]["steps"]
verify_by_name = {
    step.get("name"): step for step in verify_steps if isinstance(step, dict)
}
runtime_port_run = verify_by_name["Read allocated TCP port"]["run"]
assert "::error title=Invalid runtime Telnet port::" in runtime_port_run
assert "actual value was ${port:-empty}." in runtime_port_run
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

destroy_steps = jobs["destroy-runtime"]["steps"]
destroy_by_name = {
    step.get("name"): step for step in destroy_steps if isinstance(step, dict)
}
destroy_success = destroy_by_name["Publish trusted preview removal"]
destroy_failure = destroy_by_name["Publish trusted preview removal failure"]
assert destroy_success["if"] == "${{ success() }}"
assert destroy_failure["if"] == "${{ !cancelled() && failure() }}"
assert destroy_failure["uses"] == destroy_success["uses"]
assert destroy_failure["env"] == {
    "PREVIEW_PR_NUMBER": "${{ needs.validate-target.outputs.pr_number }}",
    "PREVIEW_HEAD_SHA": "${{ needs.validate-target.outputs.head_sha }}",
    "PREVIEW_IMAGE_TAG": "${{ needs.validate-target.outputs.image_tag }}",
    "PREVIEW_HOSTNAME": "${{ needs.validate-target.outputs.hostname }}",
}
destroy_failure_script = destroy_failure["with"]["script"]
for fragment in (
    "publishPreviewComment",
    'mode: "failure"',
    'markerPolicy: "replace"',
    'statePolicy: "expected-closed"',
    'telnetPort: "unavailable"',
    'failureStage: "destroy-runtime"',
    'staleDescription: "trusted preview removal failure"',
):
    assert fragment in destroy_failure_script, fragment
assert destroy_steps.index(destroy_success) < destroy_steps.index(destroy_failure)
assert destroy_steps.index(destroy_failure) < destroy_steps.index(
    destroy_by_name["Remove runtime kubeconfig"]
)

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
dev_demo_kubeconfig_step = dev_demo_by_name["Write dev-demo runtime kubeconfig"]
assert '"$RUNNER_TEMP/dev-demo-runtime.kubeconfig"' in dev_demo_kubeconfig_step[
    "run"
]
assert 'echo "DEV_DEMO_RUNTIME_KUBECONFIG=$KUBECONFIG_PATH" >> "$GITHUB_ENV"' in (
    dev_demo_kubeconfig_step["run"]
)
assert 'echo "KUBECONFIG=$KUBECONFIG_PATH" >> "$GITHUB_ENV"' in (
    dev_demo_kubeconfig_step["run"]
)
dev_demo_requester_write = dev_demo_by_name["Write hosted identity requester kubeconfig"]
assert dev_demo_requester_write["uses"] == "./.github/actions/write-kubeconfig"
assert dev_demo_requester_write["with"] == {
    "content": "${{ secrets.HOSTED_IDENTITY_REQUESTER_KUBECONFIG }}",
    "path": "${{ runner.temp }}/hosted-identity-requester.kubeconfig",
    "export-to-github-env": "false",
}
assert dev_demo_by_name["Apply fixed dev-demo Active request"]["env"] == {
    "KUBECONFIG": "${{ runner.temp }}/hosted-identity-requester.kubeconfig"
}
assert "Restore dev-demo runtime kubeconfig after Active request" not in dev_demo_by_name
assert dev_demo_render_step["env"]["CERTIFICATE_IDENTITY_MODE"] == (
    "${{ steps.certificate-identity.outputs.mode }}"
)
dev_demo_preflight = dev_demo_render_run
hosted_mode_guard = (
    'if [[ "$CERTIFICATE_IDENTITY_MODE" '
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

preview_plan_steps = preview_workflow["jobs"]["preview-plan"]["steps"]
preview_plan_outputs = preview_workflow["jobs"]["preview-plan"]["outputs"]
preview_plan_checkouts = [
    step
    for step in preview_plan_steps
    if step.get("uses", "").startswith("actions/checkout@")
]
assert preview_plan_checkouts == [
    {
        "name": "Check out preview candidate",
        "if": "${{ github.event_name != 'pull_request' || github.event.action != 'closed' }}",
        "uses": "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1",
        "with": {"persist-credentials": False},
    },
    {
        "name": "Check out trusted default branch for close lifecycle",
        "if": "${{ github.event_name == 'pull_request' && github.event.action == 'closed' }}",
        "uses": "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1",
        "with": {
            "ref": "${{ github.event.repository.default_branch }}",
            "persist-credentials": False,
        },
    },
]
assert preview_plan_outputs["close_certificate_identity_mode"] == (
    "${{ steps.close-certificate-identity.outputs.mode }}"
)
close_mode_step = next(
    step for step in preview_plan_steps if step.get("id") == "close-certificate-identity"
)
assert preview_plan_steps.index(preview_plan_checkouts[1]) < preview_plan_steps.index(
    close_mode_step
)
assert close_mode_step == {
    "name": "Resolve close lifecycle certificate identity owner",
    "id": "close-certificate-identity",
    "if": "${{ github.event_name == 'pull_request' && github.event.action == 'closed' }}",
    "uses": "./.github/actions/resolve-certificate-identity-mode",
    "with": {
        "values-file": "k8s/helm/firemud/values-hosted-shared.example.yaml",
    },
}
preview_destroy_condition = preview_workflow["jobs"]["preview-destroy"]["if"]
for required in (
    "needs.preview-plan.outputs.action == 'destroy'",
    "github.event_name != 'pull_request'",
    "github.event.action != 'closed'",
    "needs.preview-plan.outputs.close_certificate_identity_mode == 'standalone'",
):
    assert required in preview_destroy_condition, required
preview_derive_step = next(
    step for step in preview_plan_steps if step.get("id") == "derive"
)
preview_derive_run = preview_derive_step["run"]
for input_name, env_name in (
    ("action", "INPUT_ACTION"),
    ("head_sha", "INPUT_HEAD_SHA"),
    ("image_tag", "INPUT_IMAGE_TAG"),
    ("preview_domain", "INPUT_PREVIEW_DOMAIN"),
    ("pr_number", "INPUT_PR_NUMBER"),
):
    assert preview_derive_step["env"][env_name] == f"${{{{ inputs.{input_name} }}}}"
assert "${{ inputs." not in preview_derive_run
assert "set -euo pipefail" in preview_derive_run
pr_number_validation = '[[ ! "$PR_NUMBER" =~ ^[1-9][0-9]*$ ]]'
action_validation = '[[ "$ACTION" != deploy && "$ACTION" != destroy ]]'
assert pr_number_validation in preview_derive_run
assert action_validation in preview_derive_run
assert '[[ ! "$HEAD_SHA" =~ ^[0-9A-Fa-f]{40}$ ]]' in preview_derive_run
assert 'HEAD_SHA="${HEAD_SHA,,}"' in preview_derive_run
assert 'PR_NUMBER="$INPUT_PR_NUMBER"' in preview_derive_run
assert 'HEAD_SHA="$INPUT_HEAD_SHA"' in preview_derive_run
assert 'ACTION="$INPUT_ACTION"' in preview_derive_run
assert 'IMAGE_TAG="$INPUT_IMAGE_TAG"' in preview_derive_run
assert 'PREVIEW_DOMAIN="$INPUT_PREVIEW_DOMAIN"' in preview_derive_run
assert preview_derive_run.index('HEAD_SHA="${HEAD_SHA,,}"') < preview_derive_run.index(
    'if [ -z "$IMAGE_TAG" ]'
)
assert 'IMAGE_TAG="${HEAD_SHA}"' in preview_derive_run
image_tag_validation = '[[ ! "$IMAGE_TAG" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]]'
assert image_tag_validation in preview_derive_run
assert "Invalid preview domain" in preview_derive_run
for output in (
    'gh api "repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER}"',
    'echo "hostname=${PREVIEW_HOSTNAME}"',
    'echo "image_tag=${IMAGE_TAG}"',
):
    assert preview_derive_run.index(pr_number_validation) < preview_derive_run.index(output)
    assert preview_derive_run.index(action_validation) < preview_derive_run.index(output)
for output in ('echo "hostname=${PREVIEW_HOSTNAME}"', 'echo "image_tag=${IMAGE_TAG}"'):
    assert preview_derive_run.index(image_tag_validation) < preview_derive_run.index(output)
    assert preview_derive_run.index("Invalid preview domain") < preview_derive_run.index(output)
assert preview_workflow["jobs"]["preview-deploy"]["timeout-minutes"] == 120
preview_steps = preview_workflow["jobs"]["preview-deploy"]["steps"]
preview_mode_step = next(
    step
    for step in preview_steps
    if step.get("name") == "Resolve certificate identity mode"
)
assert preview_mode_step["id"] == "certificate-identity"
assert_mode_step(preview_mode_step, "preview deploy")
for job_name in ("dev-demo-deploy", "dev-demo-destroy"):
    dev_demo_mode_step = next(
        step
        for step in dev_demo_workflow["jobs"][job_name]["steps"]
        if step.get("id") == "certificate-identity"
    )
    assert_mode_step(dev_demo_mode_step, job_name)
preview_ensure_step = next(
    step
    for step in preview_steps
    if step.get("name") == "Ensure preview gRPC TLS secret exists"
)
assert "steps.certificate-identity.outputs.mode == 'standalone'" in preview_ensure_step["if"]
preview_namespace_step = next(
    step for step in preview_steps if step.get("name") == "Show namespace state"
)
assert preview_namespace_step["env"] == {
    "CERTIFICATE_IDENTITY_MODE": "${{ steps.certificate-identity.outputs.mode }}",
}
assert 'CERTIFICATE_IDENTITY_MODE' in preview_namespace_step["run"]
assert '== "standalone"' in preview_namespace_step["run"]
preview_kubeconfig_step = next(
    step for step in preview_steps if step.get("name") == "Write preview kubeconfig"
)
assert 'echo "PREVIEW_RUNTIME_KUBECONFIG=$KUBECONFIG_PATH" >> "$GITHUB_ENV"' in (
    preview_kubeconfig_step["run"]
)
preview_requester = next(
    step for step in preview_steps if step.get("name") == "Write hosted identity requester kubeconfig"
)
preview_active_request = next(
    step for step in preview_steps if step.get("name") == "Apply canonical Active request"
)
preview_restore = next(
    step for step in preview_steps if step.get("name") == "Restore preview runtime kubeconfig"
)
preview_cleanup = next(
    step for step in preview_steps if step.get("name") == "Remove hosted identity requester kubeconfig"
)
preview_projection_wait = next(
    step for step in preview_steps if step.get("name") == "Wait for all controller identity projections"
)
for step in (preview_requester, preview_active_request, preview_restore, preview_projection_wait):
    assert "steps.certificate-identity.outputs.mode == 'hosted-controller'" in step["if"]
assert preview_active_request["run"] == (
    'bash ./dev-tools/hosted/shared/request-hosted-identity.sh "pr-${{ needs.preview-plan.outputs.pr_number }}" Active'
)
assert preview_restore["if"].startswith("${{ always() &&")
assert 'echo "KUBECONFIG=$PREVIEW_RUNTIME_KUBECONFIG" >> "$GITHUB_ENV"' in preview_restore["run"]
assert preview_cleanup["if"] == "${{ always() }}"
assert preview_cleanup["run"] == 'rm -f -- "$RUNNER_TEMP/hosted-identity-requester.kubeconfig"'
projection_lines = [line.strip() for line in preview_projection_wait["run"].splitlines()]
assert projection_lines[-2:] == [
    "bash ./dev-tools/hosted/preview/wait-for-hosted-identity.sh \\",
    '--projections "pr-${{ needs.preview-plan.outputs.pr_number }}" "${{ needs.preview-plan.outputs.namespace }}" 900',
]
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
preview_render_index = next(
    index
    for index, step in enumerate(preview_steps)
    if step.get("name") == "Render preview values"
)
preview_projection_index = preview_steps.index(preview_projection_wait)
assert preview_requested_index < preview_steps.index(preview_requester)
assert preview_steps.index(preview_requester) < preview_steps.index(preview_active_request)
assert preview_steps.index(preview_active_request) < preview_steps.index(preview_restore)
assert preview_steps.index(preview_restore) < preview_steps.index(preview_cleanup)
assert preview_steps.index(preview_cleanup) < preview_projection_index
assert preview_projection_index < preview_render_index < preview_deploy_index
preview_deployed_step = preview_steps[preview_deployed_index]
assert "steps.deploy-release.outcome == 'success'" in preview_deployed_step["if"]
preview_deployed_guards = (
    (
        '[[ ! "$PR_NUMBER" =~ ^[1-9][0-9]*$ ]]',
        "::error title=Invalid preview PR number::Expected a canonical positive decimal integer.",
    ),
    (
        '[[ "$RUNTIME_NAMESPACE" != "pr-${PR_NUMBER}" ]]',
        "::error title=Invalid preview runtime namespace::Expected namespace pr-${PR_NUMBER}.",
    ),
    (
        '[[ ! "$HEAD_SHA" =~ ^[0-9A-Fa-f]{40}$ ]]',
        "::error title=Invalid preview head SHA::Expected exactly 40 hexadecimal characters.",
    ),
)
for predicate, diagnostic in preview_deployed_guards:
    assert predicate in preview_deployed_step["run"]
    assert diagnostic in preview_deployed_step["run"]
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
retire_steps = jobs["retire-identity"]["steps"]
retire_by_name = {
    step.get("name"): step for step in retire_steps if isinstance(step, dict)
}
retire_requester_cleanup = retire_by_name["Remove requester kubeconfig"]
assert retire_requester_cleanup["if"] == "${{ always() }}"
assert retire_requester_cleanup["run"] == (
    'rm -f -- "$RUNNER_TEMP/hosted-identity-requester.kubeconfig"'
)
assert retire_steps.index(
    retire_by_name["Observe terminal retirement and delete request"]
) < retire_steps.index(retire_requester_cleanup)

credential_step = next(
    step
    for step in deploy_steps
    if step.get("name") == "Create canonical non-identity runtime credentials"
)
assert credential_step["run"] == (
    "bash ./dev-tools/hosted/preview/provision-runtime-credentials.sh"
)
credential_source_text = Path(sys.argv[6]).read_text(encoding="utf-8")
for fragment in (
    'read_secret_if_present firemud-secret',
    'read_secret_if_present minio-credentials',
    'read_secret_if_present jwt-signing-keys',
    'read_configmap_if_present jwt-jwks',
    'validate_secret_shape firemud-secret',
    'validate_secret_shape minio-credentials',
    'validate_secret_shape jwt-signing-keys',
    '"expected exactly the canonical keys"',
    'validate_configmap_shape jwt-jwks',
    'validate_diagnostic_jwks "$signing_key_sha256"',
    '[[ "$postgres_user" != firemud ]]',
    'Recycle the disposable preview namespace before retrying',
    'postgres_password="$(openssl rand -hex 32)"',
    'asset_store_access_key="$(openssl rand -hex 16)"',
    'asset_store_secret_key="$(openssl rand -hex 32)"',
    'umask 077',
    'Canonical Base64 round-trip validation uses GNU coreutils --decode and --wrap=0',
    'supported on its Linux preview runner only',
    'for required_command in jq base64 openssl sha256sum; do',
    'command -v "$required_command"',
    'base64 --wrap=0 2>/dev/null',
    'base64 --decode 2>/dev/null',
    'base64 --wrap=0 support is required',
    'base64 --decode support is required',
    '[[ -z "${RUNNER_TEMP:-}" || ! -d "$RUNNER_TEMP" ]]',
    'RUNNER_TEMP must name an existing directory',
    'credential_files_dir="$(mktemp -d -- "${RUNNER_TEMP}/firemud-runtime-credentials.XXXXXX")"',
    'trap cleanup_credential_files EXIT',
    'chmod 700 "$credential_files_dir"',
    'chmod 600 "$credential_files_dir/$file_name"',
    '--from-file="ASSET_STORE_ACCESS_KEY=${credential_files_dir}/ASSET_STORE_ACCESS_KEY"',
    '--from-file="ASSET_STORE_SECRET_KEY=${credential_files_dir}/ASSET_STORE_SECRET_KEY"',
    '--from-file="accessKey=${credential_files_dir}/accessKey"',
    '--from-file="secretKey=${credential_files_dir}/secretKey"',
    '--from-file="current.key=${credential_files_dir}/current.key"',
    '--from-file="jwks.json=${credential_files_dir}/jwks.json"',
    'kubectl -n "$RUNTIME_NAMESPACE" apply -f -',
    'signing_key_sha256="$(printf \'%s\' "$signing_key" | sha256sum',
    'jq -nc --arg fingerprint "$signing_key_sha256"',
    'keys:[]',
    'purpose:"shared-hmac-secret-path-fingerprint"',
    'sha256:$fingerprint',
):
    assert fragment in credential_source_text, fragment
assert credential_source_text.index(
    'for required_command in jq base64 openssl sha256sum; do'
) < credential_source_text.index('credential_files_dir=""')
assert credential_source_text.index('credential_files_dir=""') < credential_source_text.index(
    "validate_secret_shape()"
)
for explicit_result_flow in (
    'firemud_secret_json="$(read_secret_if_present firemud-secret)"',
    'minio_secret_json="$(read_secret_if_present minio-credentials)"',
    'jwt_signing_secret_json="$(read_secret_if_present jwt-signing-keys)"',
    'jwt_jwks_json="$(read_configmap_if_present jwt-jwks)"',
    'postgres_user="$(decode_secret_key firemud-secret FIREMUD_POSTGRES_USER "$secret_json")"',
    'validate_diagnostic_jwks "$signing_key_sha256" "$jwt_jwks_json"',
):
    assert explicit_result_flow in credential_source_text, explicit_result_flow
for ambient_result_flow in (
    "if read_secret_if_present",
    "if read_configmap_if_present",
    'postgres_user="$decoded_secret_value"',
):
    assert ambient_result_flow not in credential_source_text, ambient_result_flow
assert "canonical non-empty keys" not in credential_source_text
assert credential_source_text.count(
    '"$asset_store_access_key" != "$minio_access_key"'
) == 1
assert credential_source_text.count(
    '"$asset_store_secret_key" != "$minio_secret_key"'
) == 1
assert (
    'if [[ "$firemud_secret_exists" == true && "$minio_secret_exists" == true ]]; then\n'
    "  validate_matching_minio_credentials\n"
    "fi"
) in credential_source_text
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
    assert forbidden not in credential_source_text, forbidden
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

preview_annotator_stub_dir="$TEMP_DIR/preview-annotator-stubs"
preview_annotator_log="$TEMP_DIR/preview-annotator-kubectl.log"
mkdir -p "$preview_annotator_stub_dir"
cat >"$preview_annotator_stub_dir/kubectl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${PREVIEW_ANNOTATOR_KUBECTL_LOG:?}"
SH
chmod +x "$preview_annotator_stub_dir/kubectl"

run_preview_annotator() {
  env \
    PATH="$preview_annotator_stub_dir:$PATH" \
    PREVIEW_ANNOTATOR_KUBECTL_LOG="$preview_annotator_log" \
    bash "$preview_annotator" "$@"
}

: >"$preview_annotator_log"
run_preview_annotator \
  pr-42 42 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  32015 2026-09-13T01:02:03Z
mapfile -t preview_annotator_calls <"$preview_annotator_log"
[[ "${#preview_annotator_calls[@]}" -eq 2 ]]
[[ "${preview_annotator_calls[0]}" == \
  "annotate namespace pr-42 firemud.dev/requested-preview-head-sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa firemud.dev/last-preview-image-tag=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa firemud.dev/last-preview-telnet-port=32015 firemud.dev/preview-allocated-at=2026-09-13T01:02:03Z "*" --overwrite" ]]
[[ "${preview_annotator_calls[1]}" == \
  "label namespace pr-42 firemud.dev/preview=true firemud.dev/pr-number=42 --overwrite" ]]

invalid_preview_annotator_cases=(
  "pr-042|042|32000|2026-09-13T01:02:03Z"
  "pr-43|42|32000|2026-09-13T01:02:03Z"
  "pr-42|42|31999|2026-09-13T01:02:03Z"
  "pr-42|42|32016|2026-09-13T01:02:03Z"
  "pr-42|42|032000|2026-09-13T01:02:03Z"
  "pr-42|42|32000|2026-02-30T01:02:03Z"
  "pr-42|42|32000|2026-09-13T01:02:03+00:00"
  "pr-42|42|32000|2026-09-13T01:02:03Z injected"
)
for invalid_preview_annotator_case in "${invalid_preview_annotator_cases[@]}"; do
  IFS='|' read -r invalid_namespace invalid_pr invalid_port invalid_timestamp \
    <<<"$invalid_preview_annotator_case"
  : >"$preview_annotator_log"
  if run_preview_annotator \
    "$invalid_namespace" "$invalid_pr" aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "$invalid_port" "$invalid_timestamp" \
    >"$TEMP_DIR/invalid-preview-annotator.output" \
    2>"$TEMP_DIR/invalid-preview-annotator.error"; then
    echo "preview namespace annotator accepted invalid target metadata: $invalid_preview_annotator_case" >&2
    exit 1
  fi
  if [[ -s "$preview_annotator_log" ]]; then
    echo "preview namespace annotator mutated Kubernetes before rejecting invalid target metadata" >&2
    exit 1
  fi
done

invalid_preview_annotator_heads=(
  AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  gaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
)
for invalid_preview_annotator_head in "${invalid_preview_annotator_heads[@]}"; do
  : >"$preview_annotator_log"
  if run_preview_annotator \
    pr-42 42 "$invalid_preview_annotator_head" \
    aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 32000 2026-09-13T01:02:03Z \
    >"$TEMP_DIR/invalid-preview-annotator-head.output" \
    2>"$TEMP_DIR/invalid-preview-annotator-head.error"; then
    echo "preview namespace annotator accepted invalid head SHA: $invalid_preview_annotator_head" >&2
    exit 1
  fi
  if [[ -s "$preview_annotator_log" ]]; then
    echo "preview namespace annotator mutated Kubernetes before rejecting an invalid head SHA" >&2
    exit 1
  fi
done

preview_image_tag_129="$(printf 'z%.0s' {1..129})"
invalid_preview_annotator_image_tags=(
  ""
  "bad/tag"
  "bad:tag"
  "-bad"
  "$preview_image_tag_129"
)
for invalid_preview_annotator_image_tag in "${invalid_preview_annotator_image_tags[@]}"; do
  : >"$preview_annotator_log"
  if run_preview_annotator \
    pr-42 42 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    "$invalid_preview_annotator_image_tag" 32000 2026-09-13T01:02:03Z \
    >"$TEMP_DIR/invalid-preview-annotator-image-tag.output" \
    2>"$TEMP_DIR/invalid-preview-annotator-image-tag.error"; then
    echo "preview namespace annotator accepted invalid image tag: $invalid_preview_annotator_image_tag" >&2
    exit 1
  fi
  if [[ -s "$preview_annotator_log" ]]; then
    echo "preview namespace annotator mutated Kubernetes before rejecting an invalid image tag" >&2
    exit 1
  fi
done

bootstrap_extract_source="$(sed -n '/^extract_named_yaml_document() {$/,/^}$/p' "$bootstrap")"
eval "$bootstrap_extract_source"
cat >"$TEMP_DIR/bootstrap-document.yaml" <<'YAML'
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
prelude:
  name: earlier-non-metadata-name
metadata:
  name: expected-policy
spec:
  name: later-nested-name
YAML
extract_named_yaml_document \
  "$TEMP_DIR/bootstrap-document.yaml" \
  ValidatingAdmissionPolicy \
  expected-policy \
  "$TEMP_DIR/extracted-bootstrap-document.yaml"
cmp -s "$TEMP_DIR/bootstrap-document.yaml" "$TEMP_DIR/extracted-bootstrap-document.yaml"

controller_publish_step="$TEMP_DIR/controller-publish-step.sh"
python3 - "$runtime" "$controller_publish_step" <<'PY'
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
publish_step = next(
    step
    for step in workflow["jobs"]["publish-hosted-identity-controller"]["steps"]
    if step.get("name") == "Publish exact verified controller image"
)
Path(sys.argv[2]).write_text(
    "#!/usr/bin/env bash\n" + publish_step["run"],
    encoding="utf-8",
)
PY
chmod +x "$controller_publish_step"

controller_publish_stub_dir="$TEMP_DIR/controller-publish-stubs"
mkdir -p "$controller_publish_stub_dir"
cat >"$controller_publish_stub_dir/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$1" == image && "$2" == inspect ]]; then
  printf '%s\n' "${VERIFIED_IMAGE_ID:?}"
  exit 0
fi
if [[ "$1" != push || "$2" != "${CONTROLLER_IMAGE:?}" ]]; then
  echo "unexpected docker invocation: $*" >&2
  exit 2
fi

push_count=0
if [[ -f "${CONTROLLER_PUSH_COUNT:?}" ]]; then
  push_count="$(<"$CONTROLLER_PUSH_COUNT")"
fi
push_count=$((push_count + 1))
printf '%s\n' "$push_count" >"$CONTROLLER_PUSH_COUNT"

failed_digest="sha256:$(printf '1%.0s' {1..64})"
successful_digest="sha256:$(printf '2%.0s' {1..64})"
case "${CONTROLLER_PUSH_MODE:?}" in
  failed-then-success)
    if ((push_count == 1)); then
      printf 'misleading: digest: %s size: 1\n' "$failed_digest"
      exit 1
    fi
    printf 'trusted: digest: %s size: 2\n' "$successful_digest"
    ;;
  duplicate-success)
    printf 'trusted: digest: %s size: 2\n' "$successful_digest"
    printf 'duplicate: digest: %s size: 2\n' "$successful_digest"
    ;;
  *)
    echo "unexpected controller push mode: $CONTROLLER_PUSH_MODE" >&2
    exit 2
    ;;
esac
SH
chmod +x "$controller_publish_stub_dir/docker"
cat >"$controller_publish_stub_dir/sleep" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$1" >>"${CONTROLLER_SLEEP_LOG:?}"
SH
chmod +x "$controller_publish_stub_dir/sleep"

controller_image="ghcr.io/example/controller:trusted"
controller_image_id="sha256:smoke-tested-controller"
controller_push_count="$TEMP_DIR/controller-push-count"
controller_sleep_log="$TEMP_DIR/controller-sleep.log"
controller_publish_output="$TEMP_DIR/controller-publish.output"
controller_publish_stdout="$TEMP_DIR/controller-publish.stdout"
successful_digest="sha256:$(printf '2%.0s' {1..64})"
env \
  PATH="$controller_publish_stub_dir:$PATH" \
  CONTROLLER_IMAGE="$controller_image" \
  VERIFIED_IMAGE_ID="$controller_image_id" \
  CONTROLLER_PUSH_MODE=failed-then-success \
  CONTROLLER_PUSH_COUNT="$controller_push_count" \
  CONTROLLER_SLEEP_LOG="$controller_sleep_log" \
  GITHUB_OUTPUT="$controller_publish_output" \
  bash "$controller_publish_step" >"$controller_publish_stdout"
test "$(<"$controller_push_count")" -eq 2
grep -Fxq '5' "$controller_sleep_log"
grep -Fxq "digest=$successful_digest" "$controller_publish_output"
if grep -Fq "sha256:$(printf '1%.0s' {1..64})" "$controller_publish_output"; then
  echo "failed controller push digest leaked into the attestation output" >&2
  exit 1
fi

controller_duplicate_count="$TEMP_DIR/controller-duplicate-count"
controller_duplicate_output="$TEMP_DIR/controller-duplicate.output"
if env \
  PATH="$controller_publish_stub_dir:$PATH" \
  CONTROLLER_IMAGE="$controller_image" \
  VERIFIED_IMAGE_ID="$controller_image_id" \
  CONTROLLER_PUSH_MODE=duplicate-success \
  CONTROLLER_PUSH_COUNT="$controller_duplicate_count" \
  CONTROLLER_SLEEP_LOG="$TEMP_DIR/controller-duplicate-sleep.log" \
  GITHUB_OUTPUT="$controller_duplicate_output" \
  bash "$controller_publish_step" >"$TEMP_DIR/controller-duplicate.stdout" \
  2>"$TEMP_DIR/controller-duplicate.stderr"; then
  echo "controller publication accepted more than one successful push digest" >&2
  exit 1
fi
grep -Fq 'did not report exactly one exact sha256 digest' \
  "$TEMP_DIR/controller-duplicate.stderr"
test ! -s "$controller_duplicate_output"

preview_derive_step="$TEMP_DIR/preview-derive-step.sh"
python3 - "$preview" "$preview_derive_step" <<'PY'
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
derive_step = next(
    step
    for step in workflow["jobs"]["preview-plan"]["steps"]
    if step.get("id") == "derive"
)
body = derive_step["run"]
for source, target in {
    "${{ github.event_name }}": "$EVENT_NAME",
    "${{ github.event.action }}": "$EVENT_ACTION",
    "${{ github.event.pull_request.number }}": "$EVENT_PR_NUMBER",
    "${{ github.event.pull_request.head.sha }}": "$EVENT_HEAD_SHA",
    "${{ github.event.pull_request.base.sha }}": "$EVENT_BASE_SHA",
}.items():
    body = body.replace(source, target)
Path(sys.argv[2]).write_text(
    "#!/usr/bin/env bash\nset -euo pipefail\n" + body,
    encoding="utf-8",
)
PY
chmod +x "$preview_derive_step"
preview_derive_stub_dir="$TEMP_DIR/preview-derive-stubs"
mkdir -p "$preview_derive_stub_dir"
cat >"$preview_derive_stub_dir/gh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == api && "$2" == repos/example/FireMUD/pulls/901 && "$3" == --jq ]]
printf '%s\n' "$*" >>"${PREVIEW_DERIVE_GH_LOG:?}"
case "$4" in
  .base.sha) printf '%s\n' base-901 ;;
  *) exit 2 ;;
esac
SH
chmod +x "$preview_derive_stub_dir/gh"

preview_derive_head_upper=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
preview_derive_head_lower=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
run_preview_derive_dispatch() {
  local input_pr_number="$1"
  local input_head_sha="$2"
  local input_action="$3"
  local input_image_tag="$4"
  local input_preview_domain="$5"
  local output_path="$6"
  local error_path="$7"
  env \
    PATH="$preview_derive_stub_dir:$PATH" \
    EVENT_NAME=workflow_dispatch \
    EVENT_ACTION='' \
    EVENT_PR_NUMBER='' \
    EVENT_HEAD_SHA='' \
    EVENT_BASE_SHA='' \
    INPUT_PR_NUMBER="$input_pr_number" \
    INPUT_HEAD_SHA="$input_head_sha" \
    INPUT_PREVIEW_DOMAIN="$input_preview_domain" \
    INPUT_ACTION="$input_action" \
    INPUT_IMAGE_TAG="$input_image_tag" \
    GITHUB_REPOSITORY=example/FireMUD \
    GITHUB_SHA="$preview_derive_head_lower" \
    PREVIEW_DERIVE_GH_LOG="${output_path}.gh.log" \
    GITHUB_OUTPUT="$output_path" \
    bash "$preview_derive_step" >"${output_path}.stdout" 2>"$error_path"
}

preview_derive_output="$TEMP_DIR/preview-derive-uppercase.output"
preview_derive_error="$TEMP_DIR/preview-derive-uppercase.error"
run_preview_derive_dispatch \
  901 \
  "$preview_derive_head_upper" \
  deploy \
  Pr-901-CustomTag \
  preview.firedevops.net \
  "$preview_derive_output" \
  "$preview_derive_error"
grep -Fxq "head_sha=$preview_derive_head_lower" "$preview_derive_output"
grep -Fxq 'image_tag=Pr-901-CustomTag' "$preview_derive_output"
grep -Fxq 'base_sha=base-901' "$preview_derive_output"
grep -Fxq 'api repos/example/FireMUD/pulls/901 --jq .base.sha' \
  "${preview_derive_output}.gh.log"

preview_derive_invalid_output="$TEMP_DIR/preview-derive-invalid.output"
preview_derive_invalid_error="$TEMP_DIR/preview-derive-invalid.error"
if run_preview_derive_dispatch \
  901 \
  not-a-sha \
  deploy \
  Pr-901-CustomTag \
  preview.firedevops.net \
  "$preview_derive_invalid_output" \
  "$preview_derive_invalid_error"; then
  echo "preview plan accepted a noncanonical workflow-dispatch head SHA" >&2
  exit 1
fi
grep -Fxq \
  '::error title=Invalid preview head SHA::Expected exactly 40 hexadecimal characters.' \
  "$preview_derive_invalid_error"
test ! -e "${preview_derive_invalid_output}.gh.log"

preview_derive_default_output="$TEMP_DIR/preview-derive-default.output"
run_preview_derive_dispatch \
  901 \
  "$preview_derive_head_upper" \
  deploy \
  '' \
  preview.firedevops.net \
  "$preview_derive_default_output" \
  "$TEMP_DIR/preview-derive-default.error"
grep -Fxq "image_tag=${preview_derive_head_lower}" "$preview_derive_default_output"

hostile_dispatch_marker="$TEMP_DIR/hostile-dispatch-executed"
hostile_dispatch_payload="\"; printf injected >\"$hostile_dispatch_marker\"; #"
invalid_dispatch_index=0
for invalid_dispatch_case in hostile-pr hostile-sha invalid-action; do
  invalid_dispatch_index=$((invalid_dispatch_index + 1))
  invalid_dispatch_output="$TEMP_DIR/preview-derive-invalid-dispatch-${invalid_dispatch_index}.output"
  invalid_dispatch_error="$TEMP_DIR/preview-derive-invalid-dispatch-${invalid_dispatch_index}.error"
  input_pr_number=901
  input_head_sha="$preview_derive_head_upper"
  input_action=deploy
  expected_error=''
  case "$invalid_dispatch_case" in
    hostile-pr)
      input_pr_number="$hostile_dispatch_payload"
      expected_error='::error title=Invalid preview PR number::'
      ;;
    hostile-sha)
      input_head_sha="$hostile_dispatch_payload"
      expected_error='::error title=Invalid preview head SHA::'
      ;;
    invalid-action)
      input_action='deploy; destroy'
      expected_error='::error title=Invalid preview action::'
      ;;
  esac
  if run_preview_derive_dispatch \
    "$input_pr_number" \
    "$input_head_sha" \
    "$input_action" \
    Pr-901-CustomTag \
    preview.firedevops.net \
    "$invalid_dispatch_output" \
    "$invalid_dispatch_error"; then
    echo "preview plan accepted invalid workflow-dispatch identity input" >&2
    exit 1
  fi
  grep -Fq "$expected_error" "$invalid_dispatch_error"
  test ! -s "$invalid_dispatch_output"
  test ! -e "${invalid_dispatch_output}.gh.log"
  test ! -e "$hostile_dispatch_marker"
done

preview_image_tag_128="$(printf 'z%.0s' {1..128})"
preview_derive_tag_128_output="$TEMP_DIR/preview-derive-tag-128.output"
run_preview_derive_dispatch \
  901 \
  "$preview_derive_head_upper" \
  deploy \
  "$preview_image_tag_128" \
  preview.firedevops.net \
  "$preview_derive_tag_128_output" \
  "$TEMP_DIR/preview-derive-tag-128.error"
grep -Fxq "image_tag=${preview_image_tag_128}" "$preview_derive_tag_128_output"
grep -Fxq 'hostname=pr-901.preview.firedevops.net' "$preview_derive_tag_128_output"

preview_image_tag_129="$(printf 'z%.0s' {1..129})"
invalid_image_index=0
for invalid_image_tag in \
  "$preview_image_tag_129" \
  'bad/tag' \
  $'bad"\nforged-output'; do
  invalid_image_index=$((invalid_image_index + 1))
  invalid_image_output="$TEMP_DIR/preview-derive-invalid-image-${invalid_image_index}.output"
  invalid_image_error="$TEMP_DIR/preview-derive-invalid-image-${invalid_image_index}.error"
  if run_preview_derive_dispatch \
    901 \
    "$preview_derive_head_upper" \
    deploy \
    "$invalid_image_tag" \
    preview.firedevops.net \
    "$invalid_image_output" \
    "$invalid_image_error"; then
    echo "preview plan accepted invalid workflow-dispatch image tag" >&2
    exit 1
  fi
  grep -Fq '::error title=Invalid preview image tag::' "$invalid_image_error"
  test ! -s "$invalid_image_output"
  test ! -e "${invalid_image_output}.gh.log"
done

invalid_domain_index=0
for invalid_preview_domain in \
  'preview."invalid' \
  $'preview.firedevops.net\nforged-output'; do
  invalid_domain_index=$((invalid_domain_index + 1))
  invalid_domain_output="$TEMP_DIR/preview-derive-invalid-domain-${invalid_domain_index}.output"
  invalid_domain_error="$TEMP_DIR/preview-derive-invalid-domain-${invalid_domain_index}.error"
  if run_preview_derive_dispatch \
    901 \
    "$preview_derive_head_upper" \
    deploy \
    Pr-901-CustomTag \
    "$invalid_preview_domain" \
    "$invalid_domain_output" \
    "$invalid_domain_error"; then
    echo "preview plan accepted invalid workflow-dispatch preview domain" >&2
    exit 1
  fi
  grep -Fq '::error title=Invalid preview domain::' "$invalid_domain_error"
  test ! -s "$invalid_domain_output"
  test ! -e "${invalid_domain_output}.gh.log"
done

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

for runtime_rollout_case in "dev 1" "pr-1 3600"; do
  read -r runtime_rollout_namespace runtime_rollout_timeout <<<"$runtime_rollout_case"
  : >"$runtime_rollout_log"
  env \
    PATH="$runtime_rollout_stub_dir:$PATH" \
    RUNTIME_ROLLOUT_NAMESPACE="$runtime_rollout_namespace" \
    RUNTIME_ROLLOUT_TIMEOUT="$runtime_rollout_timeout" \
    RUNTIME_ROLLOUT_LOG="$runtime_rollout_log" \
    bash "$runtime_rollout_waiter" "$runtime_rollout_namespace" "$runtime_rollout_timeout"
  test "$(<"$runtime_rollout_log")" = "$expected_runtime_rollouts"
done

for invalid_runtime_rollout_namespace in "" dev-identity pr-0 pr-01 pr-abc pr-42-identity; do
  if bash "$runtime_rollout_waiter" "$invalid_runtime_rollout_namespace" 120 \
    >"$TEMP_DIR/invalid-runtime-rollout-namespace.output" \
    2>"$TEMP_DIR/invalid-runtime-rollout-namespace.error"; then
    echo "runtime rollout waiter accepted invalid namespace: $invalid_runtime_rollout_namespace" >&2
    exit 1
  fi
  expected_runtime_rollout_namespace_error='runtime namespace must match dev or pr-[1-9][0-9]{0,50}'
  if [[ -z "$invalid_runtime_rollout_namespace" ]]; then
    expected_runtime_rollout_namespace_error='runtime namespace is required'
  fi
  grep -Fxq "$expected_runtime_rollout_namespace_error" \
    "$TEMP_DIR/invalid-runtime-rollout-namespace.error"
done
for invalid_runtime_rollout_timeout in 0 invalid 3601 99999999999999999999; do
  if bash "$runtime_rollout_waiter" pr-42 "$invalid_runtime_rollout_timeout" \
    >"$TEMP_DIR/invalid-runtime-rollout-timeout.output" \
    2>"$TEMP_DIR/invalid-runtime-rollout-timeout.error"; then
    echo "runtime rollout waiter accepted invalid timeout: $invalid_runtime_rollout_timeout" >&2
    exit 1
  fi
  grep -Fxq 'per_deployment_timeout_seconds must be an integer between 1 and 3600' \
    "$TEMP_DIR/invalid-runtime-rollout-timeout.error"
done

# Execute the exact credential step with a stateful Kubernetes stub. The first
# pass creates the application, MinIO, and JWT resources from random values;
# the second pass must reuse every exact stored byte without regenerating them.
credential_script="$credential_source"

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

[[ "$1" == -n && "$2" == "${RUNTIME_NAMESPACE:?}" ]]
namespace="$2"
shift 2
if [[ "$1" == apply ]]; then
  [[ "$2" == -f && "$3" == - ]]
  cat >/dev/null
  printf 'apply\n' >>"${CREDENTIAL_KUBECTL_LOG:?}"
  exit 0
fi
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
  if [[ "${CREDENTIAL_RACE_CREATE:-}" == "$configmap_name" ]]; then
    cp -- "${CREDENTIAL_RACE_SOURCE:?}" "$state_path"
    printf 'race %s\n' "$configmap_name" >>"${CREDENTIAL_KUBECTL_LOG:?}"
    echo "Error from server (AlreadyExists): configmaps \"${configmap_name}\" already exists" >&2
    exit 1
  fi
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
  if [[ "${CREDENTIAL_RACE_CREATE:-}" == "$secret_name" ]]; then
    cp -- "${CREDENTIAL_RACE_SOURCE:?}" "$state_path"
    printf 'race %s\n' "$secret_name" >>"${CREDENTIAL_KUBECTL_LOG:?}"
    echo "Error from server (AlreadyExists): secrets \"${secret_name}\" already exists" >&2
    exit 1
  fi
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
  local runtime_namespace="${5-pr-42}"
  local race_create="${6:-}"
  local race_source="${7:-}"
  env \
    PATH="$credential_stub_dir:$PATH" \
    RUNTIME_NAMESPACE="$runtime_namespace" \
    CREDENTIAL_STATE_DIR="$state_dir" \
    CREDENTIAL_OPENSSL_COUNT="$state_dir/openssl-count" \
    CREDENTIAL_OPENSSL_LOG="$state_dir/openssl.log" \
    CREDENTIAL_KUBECTL_LOG="$state_dir/kubectl.log" \
    CREDENTIAL_FILE_LOG="$state_dir/credential-files.log" \
    CREDENTIAL_FAIL_CREATE="$fail_create" \
    CREDENTIAL_RACE_CREATE="$race_create" \
    CREDENTIAL_RACE_SOURCE="$race_source" \
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

for invalid_runtime_namespace in "" dev pr-0 pr-01 pr-abc pr-42/escape; do
  invalid_state="$credential_state_root/invalid-${invalid_runtime_namespace//\//-}"
  mkdir -p "$invalid_state"
  if run_credential_step "$invalid_state" \
    "$TEMP_DIR/invalid-credential-${invalid_runtime_namespace//\//-}.output" \
    "$TEMP_DIR/invalid-credential-${invalid_runtime_namespace//\//-}.error" \
    "" "$invalid_runtime_namespace"; then
    echo "credential step accepted invalid runtime namespace: $invalid_runtime_namespace" >&2
    exit 1
  fi
  test ! -e "$invalid_state/kubectl.log"
  invalid_error="$TEMP_DIR/invalid-credential-${invalid_runtime_namespace//\//-}.error"
  if [[ -z "$invalid_runtime_namespace" ]]; then
    grep -Fxq 'runtime namespace is required' "$invalid_error"
  else
    grep -Fxq 'runtime namespace must match pr-[1-9][0-9]{0,50}' "$invalid_error"
  fi
done

for missing_dependency in jq openssl base64 sha256sum; do
  missing_dependency_dir="$credential_state_root/missing-${missing_dependency}-bin"
  missing_dependency_error="$TEMP_DIR/missing-${missing_dependency}.error"
  mkdir -p "$missing_dependency_dir"
  for available_dependency in jq openssl base64 sha256sum; do
    if [[ "$available_dependency" != "$missing_dependency" ]]; then
      ln -s "$(command -v "$available_dependency")" \
        "$missing_dependency_dir/$available_dependency"
    fi
  done
  if env \
    PATH="$missing_dependency_dir" \
    RUNTIME_NAMESPACE=pr-42 \
    "$BASH" "$credential_script" \
    >"$TEMP_DIR/missing-${missing_dependency}.output" \
    2>"$missing_dependency_error"; then
    echo "credential step succeeded without $missing_dependency" >&2
    exit 1
  fi
  test "$(<"$missing_dependency_error")" = "$missing_dependency is required"
done

real_base64="$(command -v base64)"
for unsupported_base64_option in wrap decode; do
  unsupported_base64_dir="$credential_state_root/unsupported-base64-${unsupported_base64_option}"
  mkdir -p "$unsupported_base64_dir"
  cat >"$unsupported_base64_dir/base64" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${UNSUPPORTED_BASE64_OPTION:?}" == wrap && "${1:-}" == --wrap=0 ]] ||
  [[ "$UNSUPPORTED_BASE64_OPTION" == decode && "${1:-}" == --decode ]]; then
  exit 2
fi
exec "${REAL_BASE64:?}" "$@"
SH
  chmod +x "$unsupported_base64_dir/base64"
  unsupported_base64_error="$TEMP_DIR/unsupported-base64-${unsupported_base64_option}.error"
  if env \
    PATH="$unsupported_base64_dir:$credential_stub_dir:$PATH" \
    REAL_BASE64="$real_base64" \
    UNSUPPORTED_BASE64_OPTION="$unsupported_base64_option" \
    RUNTIME_NAMESPACE=pr-42 \
    RUNNER_TEMP="$unsupported_base64_dir" \
    "$BASH" "$credential_script" \
    >"$TEMP_DIR/unsupported-base64-${unsupported_base64_option}.output" \
    2>"$unsupported_base64_error"; then
    echo "credential step succeeded without base64 --${unsupported_base64_option} support" >&2
    exit 1
  fi
  if [[ "$unsupported_base64_option" == wrap ]]; then
    test "$(<"$unsupported_base64_error")" = 'base64 --wrap=0 support is required'
  else
    test "$(<"$unsupported_base64_error")" = 'base64 --decode support is required'
  fi
done

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

create_race_state="$credential_state_root/create-race"
create_race_source="$credential_state_root/create-race-jwt-signing-keys.json"
mkdir -p "$create_race_state"
python3 - "$create_once_state/jwt-signing-keys.json" "$create_race_source" <<'PY'
import base64
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["data"]["current.key"] = base64.b64encode(b"e" * 64).decode("ascii")
Path(sys.argv[2]).write_text(json.dumps(source, sort_keys=True), encoding="utf-8")
PY
run_credential_step "$create_race_state" \
  "$TEMP_DIR/create-race.output" "$TEMP_DIR/create-race.error" \
  "" pr-42 jwt-signing-keys "$create_race_source"
assert_credential_files_removed "$create_race_state"
test "$(sha256sum "$create_race_state/jwt-signing-keys.json" | awk '{print $1}')" = \
  "$(sha256sum "$create_race_source" | awk '{print $1}')"
grep -Fxq 'race jwt-signing-keys' "$create_race_state/kubectl.log"
grep -Fq 'AlreadyExists' "$TEMP_DIR/create-race.error"
python3 - "$create_race_state" <<'PY'
import base64
import hashlib
import json
import sys
from pathlib import Path

state = Path(sys.argv[1])
signing = json.loads((state / "jwt-signing-keys.json").read_text(encoding="utf-8"))
jwks_resource = json.loads((state / "jwt-jwks.json").read_text(encoding="utf-8"))
signing_key = base64.b64decode(
    signing["data"]["current.key"], validate=True
).decode("ascii")
jwks = json.loads(jwks_resource["data"]["jwks.json"])
assert jwks["firemudDiagnostic"]["sha256"] == hashlib.sha256(
    signing_key.encode("ascii")
).hexdigest()
PY

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
rendered_full_manifest="$TEMP_DIR/preview-rendered-full.yaml"
python3 "$render_preview_values" \
  "$ROOT_DIR/k8s/helm/firemud/values-hosted-shared.example.yaml" \
  "$rendered_values" 42 pr-42 pr-42 pr-42.preview.example.test \
  pr-42-head-42 32000
helm template pr-42 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$rendered_values" --namespace pr-42 \
  --show-only templates/apps.yaml >"$rendered_manifest"
helm template pr-42 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$rendered_values" --namespace pr-42 >"$rendered_full_manifest"

python3 - "$runtime_rollout_waiter" "$rendered_full_manifest" <<'PY'
import re
import sys
from pathlib import Path

import yaml

waiter = Path(sys.argv[1]).read_text(encoding="utf-8")
inventory_match = re.search(
    r"readonly runtime_deployments=\(\s*(.*?)\s*\)", waiter, re.DOTALL
)
assert inventory_match, "rollout waiter inventory is missing"
inventory = inventory_match.group(1).split()
assert inventory and len(inventory) == len(set(inventory)), inventory
rendered_deployments = {
    document["metadata"]["name"]
    for document in yaml.safe_load_all(Path(sys.argv[2]).read_text(encoding="utf-8"))
    if document
    and document.get("kind") == "Deployment"
    and isinstance(document.get("metadata"), dict)
    and document["metadata"].get("name")
}
assert set(inventory) == rendered_deployments, (
    f"rollout inventory {inventory} does not match rendered Deployments "
    f"{sorted(rendered_deployments)}"
)
PY

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
        **validator["_expected_top_level_labels"](),
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
  labels:
    app.kubernetes.io/name: firemud
    app.kubernetes.io/managed-by: Helm
    helm.sh/chart: firemud-0.1.0
    app.kubernetes.io/instance: pr-42
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
  'preview artifact rejected: Deployment/account-service.spec.template is not an object' \
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
  'preview artifact rejected: Deployment/account-service.spec.template is not an object' \
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
                **validator["_expected_top_level_labels"](),
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
                **validator["_expected_top_level_labels"](),
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
                **validator["_expected_top_level_labels"](),
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
    "label 'app.kubernetes.io/instance' mismatch: expected 'pr-42', actual 'pr-43'",
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
    bash "$waiter" --retired pr-42 60 \
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
        "firemud.dev/last-preview-head-sha": $expected_head,
        "firemud.dev/last-preview-telnet-port": "32000"
      }
    }
  }'
}

identity_json() {
  profile_telnet_port=32000
  if [[ "${WAITER_SCENARIO:?}" == telnet-port-mismatch ]]; then
    profile_telnet_count="$(next_count profile-telnet-port)"
    if (( profile_telnet_count == 1 )); then
      profile_telnet_port=32001
    fi
  fi
  jq -nc \
    --arg expected_head "${WAITER_EXPECTED_HEAD:?}" \
    --argjson profile_telnet_port "$profile_telnet_port" '{
    metadata: {generation: 1},
    status: {
      observedGeneration: 1,
      phase: "Ready",
      conditions: [{type: "Ready", status: "True", observedGeneration: 1}],
      profile: {
        runtimeNamespaceUid: "uid-pr-42",
        requestedHeadSha: $expected_head,
        deployedHeadSha: $expected_head,
        telnetPort: $profile_telnet_port
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
  case "$scenario" in
    projection-command-failure)
      printf 'Error from server (Forbidden): secrets are forbidden\n' >&2
      exit 42
      ;;
    projection-command-not-found)
      printf 'Error from server (NotFound): secrets "pr-42-tls" not found\n' >&2
      exit 46
      ;;
    projection-command-unauthorized)
      printf 'Error from server (Unauthorized): the server has asked for credentials\n' >&2
      exit 47
      ;;
    projection-command-usage-error)
      printf 'error: unknown flag: --invalid\n' >&2
      exit 2
      ;;
  esac
  if [[ "$scenario" == projection-transport-recovery && "$secret_name" == pr-42-tls ]]; then
    projection_transport_count="$(next_count projection-transport)"
    if (( projection_transport_count <= 2 )); then
      printf 'Unable to connect to the server: dial tcp 10.0.0.1:443: i/o timeout\n' >&2
      exit 45
    fi
  fi
  if [[ "$scenario" == projection-transport-exhaustion ]]; then
    printf 'Unable to connect to the server: dial tcp 10.0.0.1:443: i/o timeout\n' >&2
    exit 45
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
  local expected_error="${5:-}"
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
    bash "$waiter" --projections pr-42 pr-42 60 \
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
    grep -Fq "$expected_error" "$error"
  fi
}

run_projection_waiter_fixture projection-absence 0 7 2
run_projection_waiter_fixture projection-command-failure 42 1 0 'Error from server (Forbidden)'
run_projection_waiter_fixture projection-command-not-found 46 1 0 'Error from server (NotFound)'
run_projection_waiter_fixture projection-command-unauthorized 47 1 0 'Error from server (Unauthorized)'
run_projection_waiter_fixture projection-command-usage-error 2 1 0 'error: unknown flag'
run_projection_waiter_fixture projection-transport-recovery 0 7 2
run_projection_waiter_fixture projection-transport-exhaustion 45 3 2 'Unable to connect to the server'

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
    bash "$waiter" pr-42 "$expected_head" pr-42 60 \
    >"$output" 2>"$error"
  status=$?
  set -e

  [[ "$status" -eq "$expected_status" ]]
  [[ "$(wc -l <"$kubectl_log")" -eq "$expected_kubectl_calls" ]]
  [[ "$(wc -l <"$sleep_log")" -eq "$expected_sleep_calls" ]]
  if [[ "$expected_status" -eq 0 ]]; then
    grep -Fq 'identity=pr-42' "$output"
    grep -Fq 'telnetPort=32000' "$output"
  else
    grep -Fq 'kubectl get failed' "$error"
    grep -Fq 'Error from server (Forbidden)' "$error"
  fi
}

run_active_waiter_fixture namespace-absence 0 3 1
run_active_waiter_fixture identity-absence 0 4 1
run_active_waiter_fixture telnet-port-mismatch 0 4 1
run_active_waiter_fixture namespace-command-failure 43 1 0
run_active_waiter_fixture identity-command-failure 44 2 0

# Every waiter mode keeps the existing positive-integer diagnostic while
# enforcing the shared bounded timeout ceiling.
run_waiter_rejects_timeout() {
  local suffix="$1"
  shift
  local kubectl_log="$TEMP_DIR/timeout-${suffix}.kubectl.log"
  local error="$TEMP_DIR/timeout-${suffix}.error"
  local output="$TEMP_DIR/timeout-${suffix}.output"
  local status

  : >"$kubectl_log"
  set +e
  env \
    PATH="$waiter_stub_dir:$PATH" \
    WAITER_KUBECTL_LOG="$kubectl_log" \
    bash "$waiter" "$@" >"$output" 2>"$error"
  status=$?
  set -e

  [[ "$status" -eq 2 ]]
  grep -Fxq 'timeout must be an integer between 1 and 3600' "$error"
  [[ ! -s "$kubectl_log" ]]
}

run_waiter_rejects_timeout projections --projections pr-42 pr-42 3601
run_waiter_rejects_timeout retired --retired pr-42 3601
run_waiter_rejects_timeout active pr-42 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa pr-42 3601

# Every waiter mode rejects a non-canonical identity through the shared
# identity-name validator before any Kubernetes read.
run_waiter_rejects_identity() {
  local suffix="$1"
  shift
  local kubectl_log="$TEMP_DIR/identity-${suffix}.kubectl.log"
  local error="$TEMP_DIR/identity-${suffix}.error"
  local output="$TEMP_DIR/identity-${suffix}.output"
  local status

  : >"$kubectl_log"
  set +e
  env \
    PATH="$waiter_stub_dir:$PATH" \
    WAITER_KUBECTL_LOG="$kubectl_log" \
    bash "$waiter" "$@" >"$output" 2>"$error"
  status=$?
  set -e

  [[ "$status" -eq 2 ]]
  grep -Fxq 'identity name is not canonical: pr-042' "$error"
  [[ ! -s "$kubectl_log" ]]
}

# shellcheck disable=SC2016 # Assert the literal shared validator call sites.
test "$(grep -Fc 'validate_identity_name "$identity_name"' "$waiter")" -eq 2
run_waiter_rejects_identity projections --projections pr-042 pr-042 60
run_waiter_rejects_identity retired --retired pr-042 60
run_waiter_rejects_identity active pr-042 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa pr-042 60

run_waiter_accepts_timeout() {
  local scenario="$1"
  local suffix="$2"
  shift 2
  local count_root="$TEMP_DIR/timeout-${suffix}.counts"
  local kubectl_log="$TEMP_DIR/timeout-${suffix}.accepted.kubectl.log"
  local sleep_log="$TEMP_DIR/timeout-${suffix}.accepted.sleep.log"
  local output="$TEMP_DIR/timeout-${suffix}.accepted.output"
  local error="$TEMP_DIR/timeout-${suffix}.accepted.error"

  mkdir -p "$count_root"
  : >"$kubectl_log"
  : >"$sleep_log"
  env \
    PATH="$waiter_stub_dir:$PATH" \
    WAITER_SCENARIO="$scenario" \
    WAITER_EXPECTED_HEAD=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    WAITER_COUNT_ROOT="$count_root" \
    WAITER_KUBECTL_LOG="$kubectl_log" \
    WAITER_SLEEP_LOG="$sleep_log" \
    bash "$waiter" "$@" >"$output" 2>"$error"
  grep -Fq 'identity=pr-42' "$output"
}

run_waiter_accepts_timeout projection-absence projections --projections pr-42 pr-42 3600
run_waiter_accepts_timeout identity-absence retired --retired pr-42 3600
run_waiter_accepts_timeout namespace-absence active pr-42 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa pr-42 3600

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
  EXPORT_TO_GITHUB_ENV=true \
  GITHUB_ENV="$valid_github_env" \
  bash "$TEMP_DIR/write-kubeconfig.sh"
test -f "$valid_kubeconfig_path"
test ! -L "$valid_kubeconfig_path"
test "$(stat -c '%a' "$valid_kubeconfig_path")" = 600
test "$(cat "$valid_kubeconfig_path")" = "$valid_kubeconfig"
grep -Fxq "KUBECONFIG=$valid_kubeconfig_path" "$valid_github_env"

opt_out_kubeconfig_path="$TEMP_DIR/opt-out.kubeconfig"
opt_out_github_env="$TEMP_DIR/opt-out-github-env"
: >"$opt_out_github_env"
PATH="$TEMP_DIR/bin:$PATH" \
  KUBECONFIG_CONTENT="$valid_kubeconfig" \
  KUBECONFIG_PATH="$opt_out_kubeconfig_path" \
  EXPORT_TO_GITHUB_ENV=false \
  GITHUB_ENV="$opt_out_github_env" \
  bash "$TEMP_DIR/write-kubeconfig.sh"
test -f "$opt_out_kubeconfig_path"
test "$(cat "$opt_out_kubeconfig_path")" = "$valid_kubeconfig"
test ! -s "$opt_out_github_env"

invalid_export_path="$TEMP_DIR/invalid-export.kubeconfig"
invalid_export_github_env="$TEMP_DIR/invalid-export-github-env"
: >"$invalid_export_github_env"
if PATH="$TEMP_DIR/bin:$PATH" \
  KUBECONFIG_CONTENT="$valid_kubeconfig" \
  KUBECONFIG_PATH="$invalid_export_path" \
  EXPORT_TO_GITHUB_ENV=maybe \
  GITHUB_ENV="$invalid_export_github_env" \
  bash "$TEMP_DIR/write-kubeconfig.sh" >/dev/null 2>&1; then
  echo "shared kubeconfig action accepted an invalid export-to-github-env value" >&2
  exit 1
fi
test ! -e "$invalid_export_path"
test ! -s "$invalid_export_github_env"

symlink_target="$TEMP_DIR/symlink-target.kubeconfig"
symlink_path="$TEMP_DIR/symlink.kubeconfig"
symlink_github_env="$TEMP_DIR/symlink-github-env"
printf 'protected symlink target\n' >"$symlink_target"
ln -s "$symlink_target" "$symlink_path"
: >"$symlink_github_env"
PATH="$TEMP_DIR/bin:$PATH" \
  KUBECONFIG_CONTENT="$valid_kubeconfig" \
  KUBECONFIG_PATH="$symlink_path" \
  EXPORT_TO_GITHUB_ENV=true \
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
  EXPORT_TO_GITHUB_ENV=true \
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
  EXPORT_TO_GITHUB_ENV=true \
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
      if [[ -n "${FAKE_WORKFLOW_RUN_JSON:-}" ]]; then
        printf '%s' "$FAKE_WORKFLOW_RUN_JSON"
      else
        printf '%s' '{"conclusion":"success","head_sha":"cccccccccccccccccccccccccccccccccccccccc","path":".github/workflows/preview.yml","event":"pull_request","repository":{"full_name":"example/FireMUD"},"pull_requests":[{"number":900}]}'
      fi
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
      --argjson labels "${TEST_PR_LABELS_JSON:-[]}" \
      '{state:$state,head:{sha:$head,repo:{full_name:$repository}},base:{ref:$base_ref,sha:"base-900"},merge_commit_sha:"merge-900",labels:$labels}'
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

run_target_without_pull_request_metadata() {
  local scenario="$1"
  local workflow_run_json="$2"
  local output="$TEMP_DIR/target-${scenario}.output"
  local gh_log="$TEMP_DIR/target-${scenario}.gh.log"

  : >"$output"
  : >"$gh_log"
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
      FAKE_WORKFLOW_RUN_JSON="$workflow_run_json" \
      SOURCE_GH_LOG="$gh_log" \
      GITHUB_OUTPUT="$output" \
      bash "$TEMP_DIR/target.sh"
  )
  test "$(cat "$output")" = 'action=none'
  test "$(cat "$gh_log")" = 'api repos/example/FireMUD/actions/runs/42'
}

run_target_without_pull_request_metadata \
  missing \
  '{"conclusion":"success","head_sha":"cccccccccccccccccccccccccccccccccccccccc","path":".github/workflows/preview.yml","event":"pull_request","repository":{"full_name":"example/FireMUD"}}'
run_target_without_pull_request_metadata \
  empty \
  '{"conclusion":"success","head_sha":"cccccccccccccccccccccccccccccccccccccccc","path":".github/workflows/preview.yml","event":"pull_request","repository":{"full_name":"example/FireMUD"},"pull_requests":[]}'

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

missing_source_stderr="$TEMP_DIR/source-missing-id.stderr"
missing_source_gh_log="$TEMP_DIR/source-missing-id-gh.log"
: >"$missing_source_gh_log"
if (
  cd "$ROOT_DIR"
  PATH="$TEMP_DIR/bin:$PATH" \
    GH_TOKEN=fake \
    GITHUB_REPOSITORY=example/FireMUD \
    SOURCE_RUN_ID='' \
    HEAD_SHA=cccccccccccccccccccccccccccccccccccccccc \
    PR_NUMBER=900 \
    SOURCE_GH_LOG="$missing_source_gh_log" \
    GITHUB_OUTPUT="$TEMP_DIR/source-missing-id-output" \
    bash "$source_step"
) 2>"$missing_source_stderr"; then
  echo "source validation must reject a missing workflow run id" >&2
  exit 1
fi
grep -Fxq \
  '::error title=Missing source run id::Expected a non-empty workflow run id; actual value was empty.' \
  "$missing_source_stderr"
test ! -s "$missing_source_gh_log"

run_invalid_source_field() {
  local scenario="$1"
  local source_json="$2"
  local expected_diagnostic="$3"
  local output="$TEMP_DIR/source-${scenario}-output"
  local stderr="$TEMP_DIR/source-${scenario}.stderr"
  local gh_log="$TEMP_DIR/source-${scenario}-gh.log"

  : >"$gh_log"
  if (
    cd "$ROOT_DIR"
    PATH="$TEMP_DIR/bin:$PATH" \
      GH_TOKEN=fake \
      GITHUB_REPOSITORY=example/FireMUD \
      SOURCE_RUN_ID=42 \
      HEAD_SHA=cccccccccccccccccccccccccccccccccccccccc \
      PR_NUMBER=900 \
      FAKE_WORKFLOW_RUN_JSON="$source_json" \
      SOURCE_GH_LOG="$gh_log" \
      GITHUB_OUTPUT="$output" \
      bash "$source_step"
  ) 2>"$stderr"; then
    echo "source validation must reject an unexpected ${scenario}" >&2
    exit 1
  fi
  grep -Fxq "$expected_diagnostic" "$stderr"
  test ! -s "$output"
  test "$(cat "$gh_log")" = 'api repos/example/FireMUD/actions/runs/42'
}

run_invalid_source_field \
  conclusion \
  '{"conclusion":"failure","head_sha":"cccccccccccccccccccccccccccccccccccccccc","path":".github/workflows/preview.yml","event":"pull_request","repository":{"full_name":"example/FireMUD"}}' \
  '::error title=Unexpected source run conclusion::Expected success; actual failure.'
run_invalid_source_field \
  head \
  '{"conclusion":"success","head_sha":"dddddddddddddddddddddddddddddddddddddddd","path":".github/workflows/preview.yml","event":"pull_request","repository":{"full_name":"example/FireMUD"}}' \
  '::error title=Unexpected source run head::Expected cccccccccccccccccccccccccccccccccccccccc; actual dddddddddddddddddddddddddddddddddddddddd.'
run_invalid_source_field \
  path \
  '{"conclusion":"success","head_sha":"cccccccccccccccccccccccccccccccccccccccc","path":".github/workflows/other.yml","event":"pull_request","repository":{"full_name":"example/FireMUD"}}' \
  '::error title=Unexpected source run path::Expected .github/workflows/preview.yml; actual .github/workflows/other.yml.'
run_invalid_source_field \
  event \
  '{"conclusion":"success","head_sha":"cccccccccccccccccccccccccccccccccccccccc","path":".github/workflows/preview.yml","event":"push","repository":{"full_name":"example/FireMUD"}}' \
  '::error title=Unexpected source run event::Expected pull_request; actual push.'
run_invalid_source_field \
  repository \
  '{"conclusion":"success","head_sha":"cccccccccccccccccccccccccccccccccccccccc","path":".github/workflows/preview.yml","event":"pull_request","repository":{"full_name":"fork/FireMUD"}}' \
  '::error title=Unexpected source run repository::Expected example/FireMUD; actual fork/FireMUD.'

target_python_log="$TEMP_DIR/closed-target-python.log"
closed_target_bin="$TEMP_DIR/closed-target-bin"
mkdir -p "$closed_target_bin"
cat >"$closed_target_bin/python3" <<SH
#!/usr/bin/env bash
printf 'unexpected label parser invocation\n' >>"$target_python_log"
printf 'labels_valid=true\npriority=false\n'
SH
chmod +x "$closed_target_bin/python3"

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
      PATH="$closed_target_bin:$TEMP_DIR/bin:$PATH" \
      GH_TOKEN=fake \
      GITHUB_REPOSITORY=example/FireMUD \
      EVENT_NAME=pull_request_target \
      EVENT_ACTION=closed \
      WORKFLOW_RUN_ID='' \
      WORKFLOW_RUN_HEAD_SHA='' \
      EVENT_PR_NUMBER=900 \
      EVENT_HEAD_SHA="$event_head_sha" \
      TEST_PR_LABELS_JSON='{}' \
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
test ! -s "$target_python_log"
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
