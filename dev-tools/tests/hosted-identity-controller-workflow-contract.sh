#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
trusted="$ROOT_DIR/.github/workflows/hosted-identity-request.yml"
dev_demo="$ROOT_DIR/.github/workflows/dev-demo.yml"
runtime="$ROOT_DIR/.github/workflows/runtime-images.yml"
publisher="$ROOT_DIR/.github/workflows/publish-pr-runtime-images.yml"
kubeconfig_action="$ROOT_DIR/.github/actions/write-kubeconfig/action.yml"
build_gradle="$ROOT_DIR/build.gradle.kts"
bootstrap="$ROOT_DIR/dev-tools/hosted/controller/bootstrap-hosted-identity-controller.sh"
waiter="$ROOT_DIR/dev-tools/hosted/preview/wait-for-hosted-identity.sh"
mode_resolver="$ROOT_DIR/dev-tools/hosted/shared/resolve-certificate-identity-mode.py"
requester="$ROOT_DIR/dev-tools/hosted/shared/request-hosted-identity.sh"

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

# The shared kubeconfig action is the only workflow credential-file writer.
# shellcheck disable=SC2016 # These assertions intentionally match literal action source.
for required in \
  'using: composite' \
  'umask 077' \
  'test -n "$KUBECONFIG_CONTENT"' \
  'chmod 600 "$KUBECONFIG_PATH"'; do
  contains "$kubeconfig_action" "$required"
done
contains "$trusted" 'uses: ./.github/actions/write-kubeconfig'
if grep -Fq 'umask 077' "$trusted" || \
  grep -Fq 'KUBECONFIG_PATH=' "$trusted" || \
  grep -Fq 'chmod 600' "$trusted"; then
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
# shellcheck disable=SC2016 # Match the literal generation comparison in waiter source.
contains "$waiter" '"$ready_generation" == "$generation"'
contains "$waiter" '.status.conditions[]? | select(.type == "Ready")'
contains "$waiter" '.status.ingress.revision'
contains "$waiter" '.status.telnet.revision'
contains "$waiter" '.status.grpc.revision'
contains "$waiter" '.status.gatewayInternalWs.revision'
contains "$waiter" '.status.tcpProxyBridge.revision'
contains "$waiter" '--projections'
contains "$waiter" 'firemud.dev/managed-by'
contains "$waiter" 'tls.crt,tls.key,ca.crt,client.crt,client.key'
for phase in \
  Pending Provisioning WaitingForCertificate RuntimeAbsent Syncing Verifying \
  Blocked Degraded Retiring Retired; do
  contains "$waiter" "$phase"
done
contains "$mode_resolver" 'UniqueKeyLoader'
contains "$mode_resolver" 'ALLOWED_MODES = frozenset({"standalone", "hosted-controller"})'
# shellcheck disable=SC2016 # Match the literal desired-state interpolation in the helper.
contains "$requester" 'desiredState: ${desired_state}'

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

python3 - "$trusted" <<'PY'
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
triggers = workflow.get("on", workflow.get(True))
assert list(triggers) == ["workflow_run"], triggers
assert triggers["workflow_run"] == {
    "workflows": ["PR Preview Environment"],
    "types": ["completed"],
}
assert workflow["permissions"] == {
    "actions": "read",
    "contents": "read",
    "issues": "read",
    "pull-requests": "read",
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
target_step = next(step for step in validate_job["steps"] if step.get("id") == "target")
target_script = target_step["run"]
for fragment in (
    'test "$source_path" = ',
    'expected_artifact_name="preview-render-pr-${PR_NUMBER}-${EXPECTED_HEAD_SHA}"',
    'select(.name == $name and .expired == false)',
    '[[ "$artifact_count" == 1 ]] || emit_no_action',
):
    assert fragment in target_script, fragment
source_step = next(step for step in validate_job["steps"] if step.get("id") == "source")
assert "steps.target.outputs.action == 'deploy'" in source_step["if"]
PY

# Execute the trusted selector with the old producer's actual boundary: a
# successful workflow run with no validated artifact. It must emit only no-op.
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
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
mkdir -p "$TEMP_DIR/bin"
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
    if [[ "$jq_expression" == .path ]]; then
      printf '%s' '.github/workflows/preview.yml'
    else
      printf '%s' 900
    fi
    ;;
  repos/example/FireMUD/pulls/900)
    printf '%s' '{"state":"open","head":{"sha":"head-900","repo":{"full_name":"example/FireMUD"}},"base":{"ref":"develop","sha":"base-900"},"merge_commit_sha":"merge-900","labels":[]}'
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
(
  cd "$ROOT_DIR"
  PATH="$TEMP_DIR/bin:$PATH" \
    GH_TOKEN=fake \
    GITHUB_REPOSITORY=example/FireMUD \
    EVENT_NAME=workflow_run \
    EVENT_ACTION=completed \
    WORKFLOW_RUN_ID=42 \
    WORKFLOW_RUN_HEAD_SHA=head-900 \
    EVENT_PR_NUMBER='' \
    EVENT_HEAD_SHA='' \
    INPUT_PR_NUMBER='' \
    INPUT_HEAD_SHA='' \
    INPUT_ACTION='' \
    GITHUB_OUTPUT="$TEMP_DIR/output" \
    bash "$TEMP_DIR/target.sh"
)
test "$(cat "$TEMP_DIR/output")" = 'action=none'

echo 'hosted identity controller workflow contract passed'
