#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
trusted="$ROOT_DIR/.github/workflows/hosted-identity-request.yml"
dev_demo="$ROOT_DIR/.github/workflows/dev-demo.yml"
runtime="$ROOT_DIR/.github/workflows/runtime-images.yml"
publisher="$ROOT_DIR/.github/workflows/publish-pr-runtime-images.yml"
kubeconfig_action="$ROOT_DIR/.github/actions/write-kubeconfig/action.yml"
build_gradle="$ROOT_DIR/build.gradle.kts"
controller_build_gradle="$ROOT_DIR/services/hosted-environment-identity-controller/build.gradle.kts"
controller_dockerfile="$ROOT_DIR/services/hosted-environment-identity-controller/Dockerfile"
bootstrap="$ROOT_DIR/dev-tools/hosted/controller/bootstrap-hosted-identity-controller.sh"
waiter="$ROOT_DIR/dev-tools/hosted/preview/wait-for-hosted-identity.sh"
mode_resolver="$ROOT_DIR/dev-tools/hosted/shared/resolve-certificate-identity-mode.py"
requester="$ROOT_DIR/dev-tools/hosted/shared/request-hosted-identity.sh"
artifact_validator="$ROOT_DIR/dev-tools/hosted/preview/validate-preview-artifact.py"

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
  'mkdir -p -- "$destination_directory"' \
  'temporary_path="$(mktemp -- "$destination_directory/.${destination_name}.XXXXXX")"' \
  'trap cleanup EXIT' \
  'kubectl --kubeconfig "$temporary_path" config view --minify >/dev/null' \
  'mv -fT -- "$temporary_path" "$KUBECONFIG_PATH"'; do
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

deploy_steps = jobs["deploy-runtime"]["steps"]
projection_wait = next(
    step["run"]
    for step in deploy_steps
    if step.get("name") == "Wait for exact WebSocket identity projections"
)
loop = (
    'for secret_name in "${IDENTITY_NAME}-gateway-internal-ws" '
    '"${IDENTITY_NAME}-tcp-proxy-bridge"; do'
)
assert projection_wait.count("deadline=$((SECONDS + 900))") == 1
assert projection_wait.count("projection_ready=false") == 1
assert projection_wait.count("projection_ready=true") == 1
assert 'if [[ "$projection_ready" != true ]]' in projection_wait
assert projection_wait.index(loop) < projection_wait.index("deadline=$((SECONDS + 900))")
assert projection_wait.index("deadline=$((SECONDS + 900))") < projection_wait.index(
    "while (( SECONDS < deadline )); do"
)
assert projection_wait.index("projection_ready=true") < projection_wait.index("break")

credential_step = next(
    step["run"]
    for step in deploy_steps
    if step.get("name") == "Create canonical non-identity runtime credentials"
)
for fragment in (
    'signing_key_sha256="$(printf \'%s\' "$signing_key" | sha256sum',
    'jq -n --arg fingerprint "$signing_key_sha256"',
    'keys:[]',
    'purpose:"shared-hmac-secret-path-fingerprint"',
    'sha256:$fingerprint',
):
    assert fragment in credential_step, fragment
for forbidden in ('openssl base64', 'kty:"oct"', 'k:$key', '--arg key'):
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
