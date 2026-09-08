#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
preview="$ROOT_DIR/.github/workflows/preview.yml"
trusted="$ROOT_DIR/.github/workflows/hosted-identity-request.yml"
runtime="$ROOT_DIR/.github/workflows/runtime-images.yml"
publisher="$ROOT_DIR/.github/workflows/publish-pr-runtime-images.yml"
kubeconfig_action="$ROOT_DIR/.github/actions/write-kubeconfig/action.yml"
build_gradle="$ROOT_DIR/build.gradle.kts"
helm_values="$ROOT_DIR/k8s/helm/firemud/values-hosted-shared.example.yaml"
helm_ingress="$ROOT_DIR/k8s/helm/firemud/templates/ingress.yaml"
helm_certificate="$ROOT_DIR/k8s/helm/firemud/templates/tcp-proxy-certificate.yaml"
helm_apps="$ROOT_DIR/k8s/helm/firemud/templates/apps.yaml"
helm_network_policies="$ROOT_DIR/k8s/helm/firemud/templates/network-policies.yaml"
waiter="$ROOT_DIR/dev-tools/hosted/preview/wait-for-hosted-identity.sh"
validator="$ROOT_DIR/dev-tools/hosted/preview/validate-preview-artifact.py"
deploy_revalidator="$ROOT_DIR/dev-tools/hosted/preview/revalidate-preview-deploy.sh"

contains() {
  grep -Fq -- "$2" "$1" || {
    echo "$1 must contain: $2" >&2
    exit 1
  }
}

contains "$preview" 'Checkout PR merge for untrusted render'
contains "$preview" 'persist-credentials: false'
contains "$preview" 'validate-preview-artifact.py'
# shellcheck disable=SC2016 # Match the literal sanitizer command in workflow source.
contains "$preview" 'sanitize "$rendered" "$sanitized"'
contains "$preview" 'actions/upload-artifact@'
for forbidden in 'PREVIEW_RUNTIME_KUBECONFIG' 'HOSTED_IDENTITY_REQUESTER_KUBECONFIG' 'delete-hosted-namespace' 'ensure-grpc-tls-secret'; do
  if grep -Fq -- "$forbidden" "$preview"; then
    echo "untrusted preview workflow contains forbidden privileged material: $forbidden" >&2
    exit 1
  fi
done

# shellcheck disable=SC2016 # These assertions intentionally match literal workflow source.
for required in \
  'workflow_run:' \
  'pull_request_target:' \
  'source_path="$(gh api' \
  'test "$source_path" = ' \
  'apiVersion: platform.firemud.dev/v1alpha1' \
  'kind: HostedEnvironmentIdentity' \
  'namespace: firemud-system' \
  'desiredState: Active' \
  'desiredState: Retired' \
  '--retired "$IDENTITY_NAME"' \
  'delete hostedenvironmentidentity "$IDENTITY_NAME"' \
  'HOSTED_IDENTITY_REQUESTER_KUBECONFIG' \
  'PREVIEW_RUNTIME_KUBECONFIG' \
  'validate-preview-artifact.py' \
  'prune-stale-preview-namespaces.sh' \
  'allocate-preview-telnet-port.sh' \
  'annotate-preview-namespace.sh' \
  'inject "$ARTIFACT_DIR/preview-rendered-sanitized.yaml"' \
  'labels_valid=' \
  'preview-eligibility.py' \
  '--inspect-labels --labels-json "$labels_json"' \
  'Revalidate open PR before Active request' \
  'Create and annotate exact preview runtime namespace' \
  'Restore preview runtime kubeconfig' \
  'Wait for exact WebSocket identity projections' \
  'Final revalidate open PR before server dry-run and apply' \
  'Revalidate preview cleanup target before runtime deletion' \
  'Revalidate preview cleanup target before identity retirement' \
  'Validate trusted hosted bridge render' \
  'Validate controller-projected preview identity' \
  'python3 ./dev-tools/deploy/preflight.py hosted-bridge' \
  '--expected-hosted-telnet-node-port "$TELNET_PORT"' \
  '${IDENTITY_NAME}-gateway-internal-ws' \
  '${IDENTITY_NAME}-tcp-proxy-bridge' \
  '.data["tls.crt"]' \
  '.data["tls.key"]' \
  '.data["ca.crt"]' \
  'kubectl apply --dry-run=server --server-side' \
  'needs: [validate-target, deploy-runtime]' \
  'verify-runtime'; do
  contains "$trusted" "$required"
done
# The initial selector inspects labels; deploy mutation gates delegate their
# complete predicate to the shared revalidation helper.
# shellcheck disable=SC2016 # Match literal eligibility invocations in workflow source.
test "$(grep -Fc -- '--inspect-labels --labels-json "$labels_json"' "$trusted")" -eq 1
test "$(grep -Fc -- '--operation deploy' "$trusted")" -eq 0
for forbidden in \
  'ensure-hosted-identity-scope.sh' \
  'ensure-grpc-tls-secret' \
  'ensure-preview-namespace' \
  'ensure-dev-demo-identity' \
  'mint' \
  'identityReady' \
  'runtimeReady'; do
  if grep -Fq -- "$forbidden" "$trusted"; then
    echo "trusted workflow contains forbidden legacy identity behavior: $forbidden" >&2
    exit 1
  fi
done

contains "$runtime" 'hosted-environment-identity-controller'
contains "$runtime" 'services/hosted-environment-identity-controller/**'
contains "$publisher" 'hosted-environment-identity-controller'
contains "$build_gradle" '":hosted-environment-identity-controller:bootBuildImage"'

# shellcheck disable=SC2016 # These assertions intentionally match literal action source.
for required in 'using: composite' 'umask 077' 'test -n "$KUBECONFIG_CONTENT"' 'chmod 600 "$KUBECONFIG_PATH"'; do
  contains "$kubeconfig_action" "$required"
done
contains "$trusted" 'uses: ./.github/actions/write-kubeconfig'
if grep -Fq 'umask 077' "$trusted" || grep -Fq 'KUBECONFIG_PATH=' "$trusted" || grep -Fq 'chmod 600' "$trusted"; then
  echo "$trusted must delegate protected kubeconfig writes to the shared composite action" >&2
  exit 1
fi

contains "$helm_values" 'mode: hosted-controller'
contains "$helm_ingress" 'include "firemud.hostedControllerMode"'
contains "$helm_certificate" 'include "firemud.hostedControllerMode"'
# shellcheck disable=SC2016 # Match the literal Helm template variable.
contains "$helm_apps" '$hostedControllerMode'
for workload_template in \
  "$helm_apps" \
  "$ROOT_DIR/k8s/helm/firemud/templates/stateful-core.yaml" \
  "$ROOT_DIR/k8s/helm/firemud/templates/seed-job.yaml"; do
  contains "$workload_template" 'runAsNonRoot: true'
  contains "$workload_template" 'runAsUser:'
  contains "$workload_template" 'runAsGroup:'
  contains "$workload_template" 'fsGroup:'
  contains "$workload_template" 'allowPrivilegeEscalation: false'
  contains "$workload_template" 'type: RuntimeDefault'
done
for stateful_template in \
  "$ROOT_DIR/k8s/helm/firemud/templates/stateful-core.yaml" \
  "$ROOT_DIR/k8s/helm/firemud/templates/seed-job.yaml"; do
  contains "$stateful_template" 'readOnlyRootFilesystem: false'
done
contains "$ROOT_DIR/k8s/helm/firemud/templates/stateful-core.yaml" 'name: PGDATA'
contains "$ROOT_DIR/k8s/helm/firemud/templates/stateful-core.yaml" '/var/lib/postgresql/data/pgdata'
contains "$helm_network_policies" 'kubernetes.io/metadata.name: firemud-system'
contains "$helm_network_policies" 'app.kubernetes.io/name: hosted-environment-identity-controller'
contains "$helm_network_policies" 'app.kubernetes.io/component: controller'
contains "$ROOT_DIR/k8s/helm/firemud/templates/_helpers.tpl" 'define "firemud.certificateIdentityMode"'
contains "$ROOT_DIR/k8s/helm/firemud/templates/_helpers.tpl" 'standalone'
contains "$ROOT_DIR/k8s/helm/firemud/templates/_helpers.tpl" 'hosted-controller'
contains "$ROOT_DIR/k8s/helm/firemud/templates/_helpers.tpl" 'must be standalone or hosted-controller'

contains "$waiter" '.status.observedGeneration'
contains "$waiter" '--retired'
# shellcheck disable=SC2016 # Match the literal generation comparison in waiter source.
contains "$waiter" '"$ready_generation" == "$generation"'
contains "$waiter" '.status.conditions[]? | select(.type == "Ready")'
contains "$waiter" '.status.ingress.revision'
contains "$waiter" '.status.telnet.revision'
contains "$waiter" '.status.grpc.revision'
contains "$waiter" 'Pending'
contains "$waiter" 'Provisioning'
contains "$waiter" 'WaitingForCertificate'
contains "$waiter" 'RuntimeAbsent'
contains "$waiter" 'Syncing'
contains "$waiter" 'Verifying'
contains "$waiter" 'Blocked'
contains "$waiter" 'Degraded'
contains "$waiter" 'Retiring'
contains "$waiter" 'Retired'
contains "$validator" 'EXPECTED_SERVICE_PORTS'
contains "$validator" 'validate_service_consumers'
contains "$validator" 'retains a PR-selected nodePort'
contains "$validator" 'secretKeyRef'
contains "$validator" '_validate_image_reference'
contains "$validator" 'uses an untagged image'
contains "$validator" 'MIN_PREVIEW_TELNET_PORT = 32000'
contains "$validator" 'MAX_PREVIEW_TELNET_PORT = 32015'
contains "$validator" '_validate_restricted_pod_security'
contains "$validator" 'hostPath is forbidden'
contains "$validator" 'allowPrivilegeEscalation must be false'
contains "$validator" 'capabilities must drop ALL'
contains "$validator" 'account-service-controller-ingress'
contains "$validator" 'validate_network_policies'
contains "$validator" 'hostPort is forbidden'
contains "$validator" 'windowsOptions.hostProcess is forbidden'
contains "$validator" 'seLinuxOptions'
contains "$validator" 'probe/lifecycle action'

for namespace_script in \
  "$ROOT_DIR/dev-tools/hosted/preview/annotate-preview-namespace.sh" \
  "$ROOT_DIR/dev-tools/hosted/preview/ensure-preview-namespace.sh"; do
  for psa_label in \
    'pod-security.kubernetes.io/enforce=restricted' \
    'pod-security.kubernetes.io/audit=restricted' \
    'pod-security.kubernetes.io/warn=restricted'; do
    contains "$namespace_script" "$psa_label"
  done
done

python3 - "$trusted" "$deploy_revalidator" <<'PY'
import sys
from pathlib import Path

trusted_path = Path(sys.argv[1])
trusted = trusted_path.read_text(encoding="utf-8")
deploy_revalidator = Path(sys.argv[2]).read_text(encoding="utf-8")
deploy_start = trusted.index("  deploy-runtime:\n")
verify_start = trusted.index("  verify-runtime:\n", deploy_start)
deploy = trusted[deploy_start:verify_start]
if "  request-active:\n" in trusted:
    raise SystemExit("trusted preview workflow must not wait until after deploy to request identity")
ordered = (
    "annotate-preview-namespace.sh",
    "Write requester kubeconfig",
    "Revalidate open PR before Active request",
    "Apply canonical Active request",
    "Restore preview runtime kubeconfig",
    "Wait for exact WebSocket identity projections",
    "Validate trusted hosted bridge render",
    "Final revalidate open PR before server dry-run and apply",
    "kubectl apply --dry-run=server --server-side",
    "kubectl apply --server-side",
)
positions = [deploy.index(marker) for marker in ordered]
if positions != sorted(positions) or len(set(positions)) != len(positions):
    raise SystemExit(f"trusted preview identity/preflight/deploy order is invalid: {ordered}")
if "continue-on-error" in deploy[deploy.index("Apply canonical Active request"):deploy.index("Inject trusted allocated Telnet port")]:
    raise SystemExit("trusted preview identity provisioning must fail closed before deployment")

import yaml

document = yaml.safe_load(trusted)
steps = document["jobs"]["deploy-runtime"]["steps"]
by_name = {step.get("name"): step for step in steps if isinstance(step, dict)}
early_name = "Revalidate open PR before privileged deployment"
active_name = "Revalidate open PR before Active request"
final_name = "Final revalidate open PR before server dry-run and apply"
static_preflight_name = "Validate trusted hosted bridge render"
operator_preflight_name = "Validate controller-projected preview identity"
apply_name = "Apply validated PR runtime artifact"
for name in (
    early_name,
    active_name,
    final_name,
    static_preflight_name,
    operator_preflight_name,
    apply_name,
):
    if name not in by_name:
        raise SystemExit(f"trusted preview workflow is missing required step: {name}")
early = by_name[early_name]
active = by_name[active_name]
final = by_name[final_name]
if not (
    early.get("env") == active.get("env") == final.get("env")
    and early.get("run") == active.get("run") == final.get("run")
):
    raise SystemExit("all trusted PR mutation-boundary revalidations must remain identical")
expected_revalidation_script = (
    "bash ./dev-tools/hosted/preview/revalidate-preview-deploy.sh \\\n"
    '  "$PR_NUMBER" "$EXPECTED_HEAD_SHA"\n'
)
for name, step in ((early_name, early), (active_name, active), (final_name, final)):
    if step.get("run") != expected_revalidation_script:
        raise SystemExit(f"{name} must invoke the canonical PR/head revalidation helper")
helper_security_fragments = (
    "--revalidate-deploy",
    '--expected-repository "$GITHUB_REPOSITORY"',
    '--expected-head-sha "$expected_head_sha" <<<"$pull_request_json"',
    "${refusal_reason:-preview eligibility evaluation failed}",
)
for fragment in helper_security_fragments:
    if deploy_revalidator.count(fragment) != 1:
        raise SystemExit(f"canonical deploy revalidation helper must contain exactly one {fragment}")
step_names = [step.get("name") for step in steps if isinstance(step, dict)]
if step_names.index(static_preflight_name) + 1 != step_names.index(final_name):
    raise SystemExit("static trusted bridge preflight must be immediately before final PR revalidation")
if step_names.index(final_name) + 1 != step_names.index(apply_name):
    raise SystemExit("final trusted PR revalidation must be immediately before dry-run/apply")
if step_names.index("Wait for exact WebSocket identity projections") > step_names.index(final_name):
    raise SystemExit("final trusted PR revalidation must follow identity projection wait")
if step_names.index("Wait for fixed-head runtime images") > step_names.index(final_name):
    raise SystemExit("final trusted PR revalidation must follow image wait")
if step_names.index("Create canonical non-identity runtime credentials") > step_names.index(final_name):
    raise SystemExit("final trusted PR revalidation must follow credential creation")
preflight = by_name[static_preflight_name]
expected_preflight_env = {
    "RUNTIME_NAMESPACE": "${{ needs.validate-target.outputs.namespace }}",
    "RELEASE_NAME": "${{ needs.validate-target.outputs.namespace }}",
    "ARTIFACT_PATH": "${{ runner.temp }}/preview-rendered-with-port.yaml",
    "TELNET_PORT": "${{ steps.allocate-telnet-port.outputs.port }}",
}
if preflight.get("env") != expected_preflight_env:
    raise SystemExit("trusted bridge preflight does not bind the exact runtime identity and artifact")
preflight_script = preflight.get("run", "")
for fragment in (
    "set -euo pipefail",
    "FIREMUD_PREFLIGHT_CONTEXT=ci-static",
    "python3 ./dev-tools/deploy/preflight.py hosted-bridge",
    '"$ARTIFACT_PATH" "$RUNTIME_NAMESPACE" "$RELEASE_NAME"',
    '--expected-hosted-telnet-node-port "$TELNET_PORT"',
):
    if fragment not in preflight_script:
        raise SystemExit(f"trusted bridge preflight lacks fail-closed input: {fragment}")
if preflight.get("continue-on-error") is not None or "|| true" in preflight_script:
    raise SystemExit("static trusted bridge preflight must fail closed")
if not (
    step_names.index(apply_name)
    < step_names.index("Wait for exact controller identity readiness")
    < step_names.index("Wait for runtime rollouts before operator validation")
    < step_names.index(operator_preflight_name)
):
    raise SystemExit("operator bridge preflight must follow apply, identity readiness, and rollout readiness")
operator_preflight = by_name[operator_preflight_name]
if operator_preflight.get("env") != expected_preflight_env:
    raise SystemExit("operator bridge preflight does not bind the exact runtime identity and artifact")
operator_script = operator_preflight.get("run", "")
for fragment in (
    "set -euo pipefail",
    "FIREMUD_PREFLIGHT_CONTEXT=operator",
    "python3 ./dev-tools/deploy/preflight.py hosted-bridge",
    '"$ARTIFACT_PATH" "$RUNTIME_NAMESPACE" "$RELEASE_NAME"',
    '--expected-hosted-telnet-node-port "$TELNET_PORT"',
):
    if fragment not in operator_script:
        raise SystemExit(f"operator bridge preflight lacks fail-closed input: {fragment}")
if operator_preflight.get("continue-on-error") is not None or "|| true" in operator_script:
    raise SystemExit("operator bridge preflight must fail closed")

validate_job = document["jobs"]["validate-target"]
target_step = next(step for step in validate_job["steps"] if step.get("id") == "target")
target_script = target_step.get("run", "")
for fragment in (
    "ACTION=deploy",
    "ACTION=destroy",
    "emit_no_action",
    "expected_artifact_name=\"preview-render-pr-${PR_NUMBER}-${EXPECTED_HEAD_SHA}\"",
    'select(.name == $name and .expired == false)',
    "without exactly one current ${expected_artifact_name} artifact",
):
    if fragment not in target_script:
        raise SystemExit(f"trusted workflow-run lifecycle selection lacks {fragment}")
if "destroy_reason" in validate_job.get("outputs", {}):
    raise SystemExit("validate-target must not expose a removed destroy reason")

destroy_job = document["jobs"]["destroy-runtime"]
retire_job = document["jobs"]["retire-identity"]
expected_concurrency = {
    "group": "preview-allocation-lifecycle",
    "cancel-in-progress": False,
    "queue": "max",
}
for job_name, job in (("destroy-runtime", destroy_job), ("retire-identity", retire_job)):
    if job.get("concurrency") != expected_concurrency:
        raise SystemExit(f"{job_name} must share the non-cancelling lifecycle domain")

destroy_name = "Revalidate preview cleanup target before runtime deletion"
retire_name = "Revalidate preview cleanup target before identity retirement"
destroy_steps = destroy_job["steps"]
retire_steps = retire_job["steps"]
destroy_by_name = {step.get("name"): step for step in destroy_steps if isinstance(step, dict)}
retire_by_name = {step.get("name"): step for step in retire_steps if isinstance(step, dict)}
destroy_gate = destroy_by_name.get(destroy_name)
retire_gate = retire_by_name.get(retire_name)
if destroy_gate is None or retire_gate is None:
    raise SystemExit("trusted cleanup is missing a required live-state revalidation")
expected_cleanup_env = {
    "GH_TOKEN": "${{ github.token }}",
    "PR_NUMBER": "${{ needs.validate-target.outputs.pr_number }}",
    "EXPECTED_HEAD_SHA": "${{ needs.validate-target.outputs.head_sha }}",
}
if destroy_gate.get("env") != expected_cleanup_env or retire_gate.get("env") != expected_cleanup_env:
    raise SystemExit("trusted cleanup gates must bind the same exact PR and head")
if destroy_gate.get("run") != retire_gate.get("run"):
    raise SystemExit("runtime deletion and identity retirement must use identical cleanup revalidation")
cleanup_script = destroy_gate.get("run", "")
for fragment in (
    'gh api "repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER}"',
    '.head.repo.full_name // empty',
    '.head.sha // empty',
    '.state // empty',
    '[[ "$current_state" == closed ]]',
    "pull request is no longer closed",
):
    if fragment not in cleanup_script:
        raise SystemExit(f"trusted cleanup revalidation lacks {fragment}")
destroy_names = [step.get("name") for step in destroy_steps if isinstance(step, dict)]
retire_names = [step.get("name") for step in retire_steps if isinstance(step, dict)]
if destroy_names.index(destroy_name) + 1 != destroy_names.index("Delete runtime and wait for NotFound"):
    raise SystemExit("runtime cleanup revalidation must be immediately before namespace deletion")
if retire_names.index(retire_name) + 1 != retire_names.index("Apply canonical Retired request"):
    raise SystemExit("identity cleanup revalidation must be immediately before Retired mutation")

PY

python3 - "$validator" <<'PY'
import copy
import importlib.util
import sys
from pathlib import Path
from tempfile import TemporaryDirectory

import yaml

module_spec = importlib.util.spec_from_file_location("validate_preview_artifact", sys.argv[1])
validator = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(validator)


def deployment(secret_name, annotations=None):
    return {
        "apiVersion": "apps/v1",
        "kind": "Deployment",
        "metadata": {
            "name": "account-service",
            "namespace": "pr-42",
            "annotations": annotations,
        },
        "spec": {
            "template": {
                "metadata": {"annotations": annotations},
                "spec": {
                    "securityContext": {
                        "runAsNonRoot": True,
                        "runAsUser": 1000,
                        "runAsGroup": 1000,
                        "fsGroup": 1000,
                        "seccompProfile": {"type": "RuntimeDefault"},
                    },
                    "containers": [
                        {
                            "name": "account-service",
                            "image": "ghcr.io/benhook1013/account-service:image-tag",
                            "securityContext": {
                                "allowPrivilegeEscalation": False,
                                "runAsUser": 1000,
                                "runAsGroup": 1000,
                                "capabilities": {"drop": ["ALL"]},
                            },
                            "env": [
                                {
                                    "name": "EXAMPLE",
                                    "valueFrom": {
                                        "secretKeyRef": {
                                            "name": secret_name,
                                            "key": "example",
                                        }
                                    },
                                }
                            ],
                        }
                    ]
                }
            }
        },
    }


def network_policy(name):
    return {
        "apiVersion": "networking.k8s.io/v1",
        "kind": "NetworkPolicy",
        "metadata": {"name": name, "namespace": "pr-42"},
    }


def mtls_service():
    return {
        "apiVersion": "v1",
        "kind": "Service",
        "metadata": {"name": "spring-cloud-gateway-mtls", "namespace": "pr-42"},
        "spec": {
            "selector": {"app": "spring-cloud-gateway"},
            "ports": [
                {"name": "wss-mtls", "protocol": "TCP", "port": 443, "targetPort": 8443}
            ],
            "type": "ClusterIP",
        },
    }


def identity_consumer_deployment(service):
    result = deployment("firemud-secret")
    result["metadata"]["name"] = service
    pod = result["spec"]["template"]["spec"]
    pod["serviceAccountName"] = "firemud-app"
    container = pod["containers"][0]
    container["name"] = service
    container["image"] = f"ghcr.io/benhook1013/{service}:image-tag"
    mounts = [
        {"name": "grpc-tls", "mountPath": "/tls", "readOnly": True},
        {
            "name": "jwt-signing-keys",
            "mountPath": "/var/run/secrets/firemud/jwt",
            "readOnly": True,
        },
    ]
    volumes = [
        {"name": "grpc-tls", "secret": {"secretName": "firemud-grpc-tls"}},
        {
            "name": "jwt-signing-keys",
            "secret": {"secretName": "jwt-signing-keys"},
        },
    ]
    if service == "account-service":
        mounts.append(
            {
                "name": "jwt-jwks",
                "mountPath": "/var/run/secrets/firemud/jwks",
                "readOnly": True,
            }
        )
        volumes.append({"name": "jwt-jwks", "configMap": {"name": "jwt-jwks"}})
    if service == "spring-cloud-gateway":
        mounts.append(
            {
                "name": "gateway-ws-server-tls",
                "mountPath": "/gateway-ws-server-tls",
                "readOnly": True,
            }
        )
        volumes.append(
            {
                "name": "gateway-ws-server-tls",
                "secret": {
                    "secretName": "pr-42-gateway-internal-ws",
                    "items": [
                        {"key": "tls.crt", "path": "tls.crt"},
                        {"key": "tls.key", "path": "tls.key"},
                        {"key": "ca.crt", "path": "ca.crt"},
                    ],
                },
            }
        )
    if service == "tcp-proxy-service":
        mounts.extend(
            [
                {"name": "telnet-tls", "mountPath": "/telnet-tls", "readOnly": True},
                {
                    "name": "gateway-ws-client-tls",
                    "mountPath": "/gateway-ws-client-tls",
                    "readOnly": True,
                },
            ]
        )
        volumes.extend(
            [
                {"name": "telnet-tls", "secret": {"secretName": "pr-42-telnet-tls"}},
                {
                    "name": "gateway-ws-client-tls",
                    "secret": {
                        "secretName": "pr-42-tcp-proxy-bridge",
                        "items": [
                            {"key": "tls.crt", "path": "tls.crt"},
                            {"key": "tls.key", "path": "tls.key"},
                            {"key": "ca.crt", "path": "ca.crt"},
                        ],
                    },
                },
            ]
        )
    container["volumeMounts"] = mounts
    pod["volumes"] = volumes
    return result


with TemporaryDirectory() as temporary_directory:
    source = Path(temporary_directory) / "rendered.yaml"
    destination = Path(temporary_directory) / "sanitized.yaml"

    def expect_rejected(adversarial, marker, message):
        source.write_text(yaml.safe_dump(adversarial), encoding="utf-8")
        try:
            validator.sanitize(source, destination)
        except ValueError as error:
            assert marker in str(error), error
        else:
            raise AssertionError(message)

    exact_policy = network_policy("account-service-controller-ingress")
    exact_policy["spec"] = {
        "podSelector": {"matchLabels": {"app": "account-service"}},
        "policyTypes": ["Ingress"],
        "ingress": [
            {
                "from": [
                    {
                        "namespaceSelector": {
                            "matchLabels": {"kubernetes.io/metadata.name": "firemud-system"}
                        },
                        "podSelector": {
                            "matchLabels": {
                                "app.kubernetes.io/name": "hosted-environment-identity-controller",
                                "app.kubernetes.io/component": "controller",
                            }
                        },
                    }
                ],
                "ports": [{"protocol": "TCP", "port": 6565}],
            }
        ],
    }
    gateway_policy = network_policy("spring-cloud-gateway-ingress")
    gateway_policy["spec"] = {
        "podSelector": {"matchLabels": {"app": "spring-cloud-gateway"}},
        "policyTypes": ["Ingress"],
        "ingress": [
            {
                "from": [
                    {"podSelector": {"matchLabels": {"app": "tcp-proxy-service"}}}
                ],
                "ports": [{"protocol": "TCP", "port": 8443}],
            },
            {
                "from": [
                    {
                        "namespaceSelector": {
                            "matchLabels": {"kubernetes.io/metadata.name": "kube-system"}
                        },
                        "podSelector": {
                            "matchLabels": {"app.kubernetes.io/name": "traefik"}
                        },
                    }
                ],
                "ports": [{"protocol": "TCP", "port": 8080}],
            },
            {
                "from": [{"podSelector": {}}],
                "ports": [
                    {"protocol": "TCP", "port": 8080},
                    {"protocol": "TCP", "port": 6565},
                ],
            },
        ],
    }
    proxy_policy = network_policy("tcp-proxy-service-egress")
    proxy_policy["spec"] = {
        "podSelector": {"matchLabels": {"app": "tcp-proxy-service"}},
        "policyTypes": ["Egress"],
        "egress": [
            {
                "to": [
                    {
                        "namespaceSelector": {
                            "matchLabels": {"kubernetes.io/metadata.name": "kube-system"}
                        },
                        "podSelector": {"matchLabels": {"k8s-app": "kube-dns"}},
                    }
                ],
                "ports": [
                    {"protocol": "UDP", "port": 53},
                    {"protocol": "TCP", "port": 53},
                ],
            },
            {
                "to": [
                    {"podSelector": {"matchLabels": {"app": "spring-cloud-gateway"}}}
                ],
                "ports": [{"protocol": "TCP", "port": 8443}],
            },
            {
                "to": [
                    {"podSelector": {"matchLabels": {"app": "game-session-service"}}}
                ],
                "ports": [{"protocol": "TCP", "port": 6565}],
            },
        ],
    }
    policies = [
        network_policy("internal-services"),
        network_policy("internal-services-egress"),
        exact_policy,
        gateway_policy,
        proxy_policy,
    ]
    validator.validate_network_policies(
        policies
    )
    for adversarial, description in (
        (policies[:-1], "missing bridge NetworkPolicy"),
        (policies + [copy.deepcopy(proxy_policy)], "duplicate bridge NetworkPolicy"),
    ):
        try:
            validator.validate_network_policies(adversarial)
        except ValueError as error:
            assert "not closed" in str(error), error
        else:
            raise AssertionError(f"{description} was accepted")
    for policy_index, rule_index, field, value, description in (
        (3, 0, "from", [{"podSelector": {}}], "widened Gateway ingress peer"),
        (3, 0, "ports", [{"protocol": "TCP", "port": 8080}], "wrong Gateway port"),
        (
            3,
            1,
            "from",
            [
                {
                    "namespaceSelector": {
                        "matchLabels": {"kubernetes.io/metadata.name": "kube-system"}
                    },
                    "podSelector": {},
                }
            ],
            "widened Traefik ingress peer",
        ),
        (
            3,
            2,
            "ports",
            [{"protocol": "TCP", "port": 8080}],
            "missing same-namespace Gateway gRPC port",
        ),
        (4, 1, "to", [{"podSelector": {}}], "widened TCP Proxy egress peer"),
        (4, 1, "ports", [{"protocol": "TCP", "port": 443}], "wrong TCP Proxy port"),
        (4, 2, "to", [{"podSelector": {}}], "widened readiness egress peer"),
        (4, 2, "ports", [{"protocol": "TCP", "port": 8080}], "wrong readiness port"),
    ):
        adversarial = copy.deepcopy(policies)
        rules_key = "ingress" if policy_index == 3 else "egress"
        adversarial[policy_index]["spec"][rules_key][rule_index][field] = value
        try:
            validator.validate_network_policies(adversarial)
        except ValueError as error:
            assert "unsafe exception" in str(error), error
        else:
            raise AssertionError(f"{description} was accepted")
    for mutation, description in (
        (lambda policy: policy["spec"]["egress"].pop(), "missing readiness egress"),
        (
            lambda policy: policy["spec"]["egress"].append(
                copy.deepcopy(policy["spec"]["egress"][-1])
            ),
            "extra TCP Proxy egress rule",
        ),
    ):
        adversarial = copy.deepcopy(policies)
        mutation(adversarial[4])
        try:
            validator.validate_network_policies(adversarial)
        except ValueError as error:
            assert "unsafe exception" in str(error), error
        else:
            raise AssertionError(f"{description} was accepted")
    extra_policy = network_policy("untrusted-extra-policy")
    try:
        validator.validate_network_policies(
            policies + [extra_policy]
        )
    except ValueError as error:
        assert "not closed" in str(error), error
    else:
        raise AssertionError("arbitrary additional NetworkPolicy was accepted")
    expect_rejected(
        extra_policy,
        "not an approved runtime policy",
        "arbitrary NetworkPolicy artifact was accepted",
    )

    exact_service = mtls_service()
    validator.validate_services([exact_service])
    for mutation, description in (
        (
            lambda service: service["spec"].update(selector={"app": "tcp-proxy-service"}),
            "wrong mTLS Service selector",
        ),
        (
            lambda service: service["spec"]["ports"][0].update(name="tcp-443"),
            "wrong mTLS Service port name",
        ),
        (
            lambda service: service["spec"]["ports"][0].update(port=8443),
            "wrong mTLS Service port",
        ),
        (
            lambda service: service["spec"]["ports"][0].update(targetPort=8080),
            "wrong mTLS target port",
        ),
        (
            lambda service: service["spec"]["ports"][0].update(protocol="UDP"),
            "wrong mTLS Service protocol",
        ),
        (
            lambda service: service["spec"].update(externalIPs=["203.0.113.10"]),
            "extra mTLS Service spec field",
        ),
        (
            lambda service: service["spec"]["ports"].append(
                copy.deepcopy(service["spec"]["ports"][0])
            ),
            "duplicate mTLS Service port",
        ),
    ):
        adversarial = copy.deepcopy(exact_service)
        mutation(adversarial)
        try:
            validator.validate_services([adversarial])
        except ValueError as error:
            assert "Service/spring-cloud-gateway-mtls" in str(error), error
        else:
            raise AssertionError(f"{description} was accepted")

    consumers = [
        identity_consumer_deployment(service) for service in validator.SERVICE_IMAGES
    ]
    validator.validate_service_consumers(consumers, "pr-42")

    def mutate_consumer(service, mutation):
        adversarial = copy.deepcopy(consumers)
        target = next(
            document for document in adversarial if document["metadata"]["name"] == service
        )
        mutation(target["spec"]["template"]["spec"])
        return adversarial

    consumer_mutations = (
        (
            "spring-cloud-gateway",
            lambda pod: pod["containers"][0]["volumeMounts"].pop(),
            "missing Gateway TLS mount",
        ),
        (
            "tcp-proxy-service",
            lambda pod: pod["volumes"].append(copy.deepcopy(pod["volumes"][-1])),
            "duplicate TCP Proxy TLS volume",
        ),
        (
            "spring-cloud-gateway",
            lambda pod: pod["volumes"][-1]["secret"].update(
                secretName="pr-42-wrong-secret"
            ),
            "wrong Gateway TLS Secret",
        ),
        (
            "spring-cloud-gateway",
            lambda pod: pod["volumes"][-1]["secret"].update(
                secretName="pr-42-tcp-proxy-bridge"
            ),
            "reused TCP Proxy client Secret for Gateway",
        ),
        (
            "tcp-proxy-service",
            lambda pod: pod["containers"][0]["volumeMounts"][-1].update(readOnly=False),
            "writable TCP Proxy TLS mount",
        ),
        (
            "spring-cloud-gateway",
            lambda pod: pod["containers"][0]["volumeMounts"][-1].update(
                subPath="tls.crt"
            ),
            "partial Gateway TLS mount",
        ),
        (
            "tcp-proxy-service",
            lambda pod: pod["volumes"][-1]["secret"]["items"].pop(),
            "missing TCP Proxy CA item",
        ),
        (
            "spring-cloud-gateway",
            lambda pod: pod["volumes"][-1]["secret"]["items"].append(
                {"key": "token", "path": "token"}
            ),
            "extra Gateway Secret item",
        ),
    )
    for service, mutation, description in consumer_mutations:
        adversarial = mutate_consumer(service, mutation)
        try:
            validator.validate_service_consumers(adversarial, "pr-42")
        except ValueError as error:
            assert f"Deployment/{service}" in str(error), error
        else:
            raise AssertionError(f"{description} was accepted")

    for secret_name in ("pr-42-gateway-internal-ws", "pr-42-tcp-proxy-bridge"):
        source.write_text(yaml.safe_dump(deployment(secret_name)), encoding="utf-8")
        validator.sanitize(source, destination)
    source.write_text(
        yaml.safe_dump(deployment("pr-42-gateway-internal-ws-copy")), encoding="utf-8"
    )
    try:
        validator.sanitize(source, destination)
    except ValueError as error:
        assert "secretKeyRef.name" in str(error), error
    else:
        raise AssertionError("near-match WebSocket Secret suffix was accepted")
    expect_rejected(
        {
            "apiVersion": "v1",
            "kind": "Secret",
            "metadata": {
                "name": "pr-42-gateway-internal-ws",
                "namespace": "pr-42",
            },
            "data": {"tls.key": "credential-bytes"},
        },
        "forbidden kind Secret",
        "identity Secret object crossed the sanitized artifact boundary",
    )

    annotated = deployment(
        "firemud-secret",
        {"sidecar.istio.io/inject": "true"},
    )
    source.write_text(yaml.safe_dump(annotated), encoding="utf-8")
    validator.sanitize(source, destination)
    sanitized = yaml.safe_load(destination.read_text(encoding="utf-8"))
    assert "annotations" not in sanitized["metadata"], sanitized
    assert "annotations" not in sanitized["spec"]["template"]["metadata"], sanitized

    nested_only = deployment(
        "firemud-secret",
        {"sidecar.istio.io/inject": "true"},
    )
    nested_only["metadata"].pop("annotations")
    try:
        validator._validate_no_annotations(nested_only)
    except ValueError as error:
        assert "spec.template.metadata" in str(error), error
    else:
        raise AssertionError("untrusted pod-template annotations were accepted")

    source.write_text(yaml.safe_dump(deployment("unapproved-secret")), encoding="utf-8")
    try:
        validator.sanitize(source, destination)
    except ValueError as error:
        assert "secretKeyRef.name" in str(error), error
    else:
        raise AssertionError("unapproved nested secretKeyRef.name was accepted")

    adversarial = deployment("firemud-secret")
    adversarial["spec"]["template"]["spec"]["containers"][0]["securityContext"][
        "privileged"
    ] = True
    source.write_text(yaml.safe_dump(adversarial), encoding="utf-8")
    try:
        validator.sanitize(source, destination)
    except ValueError as error:
        assert "privileged" in str(error), error
    else:
        raise AssertionError("privileged PR-selected container was accepted")

    for mutation, marker in (
        (lambda value: value.update(runAsUser=0), "runAsUser"),
        (lambda value: value.pop("runAsUser"), "runAsUser"),
    ):
        adversarial = deployment("firemud-secret")
        mutation(adversarial["spec"]["template"]["spec"]["securityContext"])
        source.write_text(yaml.safe_dump(adversarial), encoding="utf-8")
        try:
            validator.sanitize(source, destination)
        except ValueError as error:
            assert marker in str(error), error
        else:
            raise AssertionError("root or unspecified effective runAsUser was accepted")

    adversarial = deployment("firemud-secret")
    adversarial["spec"]["template"]["spec"]["containers"][0]["securityContext"][
        "runAsUser"
    ] = 0
    source.write_text(yaml.safe_dump(adversarial), encoding="utf-8")
    try:
        validator.sanitize(source, destination)
    except ValueError as error:
        assert "runAsUser" in str(error), error
    else:
        raise AssertionError("container root runAsUser was accepted")

    for field in ("hostNetwork", "hostPID", "hostIPC"):
        adversarial = deployment("firemud-secret")
        adversarial["spec"]["template"]["spec"][field] = True
        source.write_text(yaml.safe_dump(adversarial), encoding="utf-8")
        try:
            validator.sanitize(source, destination)
        except ValueError as error:
            assert field in str(error), error
        else:
            raise AssertionError(f"PR-selected {field} was accepted")

    adversarial = deployment("firemud-secret")
    adversarial["spec"]["template"]["spec"]["volumes"] = [
        {"name": "host", "hostPath": {"path": "/"}}
    ]
    source.write_text(yaml.safe_dump(adversarial), encoding="utf-8")
    try:
        validator.sanitize(source, destination)
    except ValueError as error:
        assert "hostPath" in str(error), error
    else:
        raise AssertionError("PR-selected hostPath was accepted")

    adversarial = deployment("firemud-secret")
    adversarial["spec"]["template"]["spec"]["containers"][0]["securityContext"][
        "allowPrivilegeEscalation"
    ] = True
    source.write_text(yaml.safe_dump(adversarial), encoding="utf-8")
    try:
        validator.sanitize(source, destination)
    except ValueError as error:
        assert "allowPrivilegeEscalation" in str(error), error
    else:
        raise AssertionError("PR-selected privilege escalation was accepted")

    adversarial = deployment("firemud-secret")
    adversarial["spec"]["template"]["spec"]["containers"][0]["securityContext"][
        "capabilities"
    ] = {"drop": ["ALL"], "add": ["SYS_ADMIN"]}
    source.write_text(yaml.safe_dump(adversarial), encoding="utf-8")
    try:
        validator.sanitize(source, destination)
    except ValueError as error:
        assert "unsafe capabilities" in str(error), error
    else:
        raise AssertionError("PR-selected unsafe capability was accepted")

    adversarial = deployment("firemud-secret")
    adversarial["spec"]["template"]["spec"]["containers"][0]["ports"] = [
        {"containerPort": 8080, "hostPort": 8080}
    ]
    expect_rejected(adversarial, "hostPort", "container hostPort was accepted")

    for location in ("pod", "container"):
        for field, value in (
            ("user", "root"),
            ("role", "system_r"),
            ("type", "spc_t"),
        ):
            adversarial = deployment("firemud-secret")
            security = adversarial["spec"]["template"]["spec"]["securityContext"]
            if location == "container":
                security = adversarial["spec"]["template"]["spec"]["containers"][0][
                    "securityContext"
                ]
            security["seLinuxOptions"] = {field: value}
            expect_rejected(
                adversarial,
                "seLinuxOptions",
                f"{location} unsafe SELinux {field} was accepted",
            )

    for location in ("pod", "container"):
        adversarial = deployment("firemud-secret")
        security = adversarial["spec"]["template"]["spec"]["securityContext"]
        if location == "container":
            security = adversarial["spec"]["template"]["spec"]["containers"][0][
                "securityContext"
            ]
        security["appArmorProfile"] = {"type": "Unconfined"}
        expect_rejected(
            adversarial,
            "appArmorProfile",
            f"{location} unsafe AppArmor profile was accepted",
        )

    for location in ("pod", "container"):
        adversarial = deployment("firemud-secret")
        security = adversarial["spec"]["template"]["spec"]["securityContext"]
        if location == "container":
            security = adversarial["spec"]["template"]["spec"]["containers"][0][
                "securityContext"
            ]
        security["windowsOptions"] = {"hostProcess": True}
        expect_rejected(
            adversarial,
            "hostProcess",
            f"{location} Windows hostProcess was accepted",
        )

    adversarial = deployment("firemud-secret")
    adversarial["spec"]["template"]["spec"]["containers"][0]["livenessProbe"] = {
        "httpGet": {"path": "/", "port": 8080, "host": "untrusted.example"}
    }
    expect_rejected(adversarial, ".host", "probe host was accepted")
    adversarial = deployment("firemud-secret")
    adversarial["spec"]["template"]["spec"]["containers"][0]["lifecycle"] = {
        "preStop": {"httpGet": {"path": "/", "port": 8080, "host": "untrusted.example"}}
    }
    expect_rejected(adversarial, ".host", "lifecycle host was accepted")

try:
    validator._validate_image_reference(
        "spec.template.spec.containers[0].image",
        "ghcr.io/benhook1013/account-service",
        "image-tag",
    )
except ValueError as error:
    assert "untagged image" in str(error), error
else:
    raise AssertionError("untagged service image was accepted")

validator._validate_image_reference(
    "spec.template.spec.containers[0].image",
    "ghcr.io/benhook1013/account-service:image-tag",
    "image-tag",
)
PY

echo 'hosted identity controller workflow contract passed'
