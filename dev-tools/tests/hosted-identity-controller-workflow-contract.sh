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

contains() {
  grep -Fq -- "$2" "$1" || {
    echo "$1 must contain: $2" >&2
    exit 1
  }
}

contains "$preview" 'Checkout PR merge for untrusted render'
contains "$preview" 'persist-credentials: false'
contains "$preview" 'validate-preview-artifact.py'
contains "$preview" 'sanitize "$rendered" "$sanitized"'
contains "$preview" 'actions/upload-artifact@'
for forbidden in 'PREVIEW_RUNTIME_KUBECONFIG' 'HOSTED_IDENTITY_REQUESTER_KUBECONFIG' 'delete-hosted-namespace' 'ensure-grpc-tls-secret'; do
  if grep -Fq -- "$forbidden" "$preview"; then
    echo "untrusted preview workflow contains forbidden privileged material: $forbidden" >&2
    exit 1
  fi
done

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
  'preview:paused' \
  'labels_valid=' \
  'Revalidate unpaused PR before Active request' \
  'Create and annotate exact preview runtime namespace' \
  'needs: [validate-target, deploy-runtime]' \
  'verify-runtime'; do
  contains "$trusted" "$required"
done
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

for required in 'using: composite' 'umask 077' 'test -n "$KUBECONFIG_CONTENT"' 'chmod 600 "$KUBECONFIG_PATH"'; do
  contains "$kubeconfig_action" "$required"
done
for workflow in \
  "$ROOT_DIR/.github/workflows/hosted-identity-request.yml" \
  "$ROOT_DIR/.github/workflows/dev-demo.yml" \
  "$ROOT_DIR/.github/workflows/dev-demo-reconciler.yml" \
  "$ROOT_DIR/.github/workflows/preview-reconciler.yml" \
  "$ROOT_DIR/.github/workflows/preview-janitor.yml"; do
  contains "$workflow" 'uses: ./.github/actions/write-kubeconfig'
  if grep -Fq 'umask 077' "$workflow" || grep -Fq 'KUBECONFIG_PATH=' "$workflow" || grep -Fq 'chmod 600' "$workflow"; then
    echo "$workflow must delegate protected kubeconfig writes to the shared composite action" >&2
    exit 1
  fi
done

contains "$helm_values" 'mode: hosted-controller'
contains "$helm_ingress" 'include "firemud.hostedControllerMode"'
contains "$helm_certificate" 'include "firemud.hostedControllerMode"'
contains "$helm_apps" 'include "firemud.hostedControllerMode"'
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
  "$ROOT_DIR/dev-tools/hosted/preview/ensure-preview-namespace.sh" \
  "$ROOT_DIR/dev-tools/hosted/dev-demo/annotate-dev-demo-namespace.sh" \
  "$ROOT_DIR/dev-tools/hosted/dev-demo/ensure-dev-demo-namespace.sh"; do
  for psa_label in \
    'pod-security.kubernetes.io/enforce=restricted' \
    'pod-security.kubernetes.io/audit=restricted' \
    'pod-security.kubernetes.io/warn=restricted'; do
    contains "$namespace_script" "$psa_label"
  done
done

python3 - "$trusted" "$ROOT_DIR/.github/workflows/dev-demo.yml" <<'PY'
import sys
from pathlib import Path

for workflow_path, apply_marker in (
    (Path(sys.argv[1]), "Apply validated PR runtime artifact"),
    (Path(sys.argv[2]), "Deploy dev-demo release"),
):
    workflow = workflow_path.read_text(encoding="utf-8")
    annotation_marker = "annotate-preview-namespace.sh" if "PR runtime" in apply_marker else "annotate-dev-demo-namespace.sh"
    annotation_index = workflow.index(annotation_marker)
    apply_index = workflow.index(apply_marker)
    assert annotation_index < apply_index, (workflow_path, annotation_marker, apply_marker)
PY

python3 - "$validator" <<'PY'
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
    validator.validate_network_policies(
        [
            network_policy("internal-services"),
            network_policy("internal-services-egress"),
            exact_policy,
        ]
    )
    extra_policy = network_policy("untrusted-extra-policy")
    try:
        validator.validate_network_policies(
            [
                network_policy("internal-services"),
                network_policy("internal-services-egress"),
                exact_policy,
                extra_policy,
            ]
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
