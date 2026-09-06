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
contains "$ROOT_DIR/k8s/helm/firemud/templates/_helpers.tpl" 'define "firemud.certificateIdentityMode"'
contains "$ROOT_DIR/k8s/helm/firemud/templates/_helpers.tpl" 'standalone'
contains "$ROOT_DIR/k8s/helm/firemud/templates/_helpers.tpl" 'hosted-controller'
contains "$ROOT_DIR/k8s/helm/firemud/templates/_helpers.tpl" 'must be standalone or hosted-controller'

contains "$waiter" '.status.observedGeneration'
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

python3 - "$validator" <<'PY'
import importlib.util
import sys
from pathlib import Path
from tempfile import TemporaryDirectory

import yaml

module_spec = importlib.util.spec_from_file_location("validate_preview_artifact", sys.argv[1])
validator = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(validator)


def deployment(secret_name):
    return {
        "apiVersion": "apps/v1",
        "kind": "Deployment",
        "metadata": {"name": "account-service", "namespace": "pr-42"},
        "spec": {
            "template": {
                "spec": {
                    "containers": [
                        {
                            "name": "account-service",
                            "image": "ghcr.io/benhook1013/account-service:image-tag",
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


with TemporaryDirectory() as temporary_directory:
    source = Path(temporary_directory) / "rendered.yaml"
    destination = Path(temporary_directory) / "sanitized.yaml"
    source.write_text(yaml.safe_dump(deployment("firemud-secret")), encoding="utf-8")
    validator.sanitize(source, destination)

    source.write_text(yaml.safe_dump(deployment("unapproved-secret")), encoding="utf-8")
    try:
        validator.sanitize(source, destination)
    except ValueError as error:
        assert "secretKeyRef.name" in str(error), error
    else:
        raise AssertionError("unapproved nested secretKeyRef.name was accepted")

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
