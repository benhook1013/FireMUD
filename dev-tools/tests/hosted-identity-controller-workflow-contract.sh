#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
preview="$ROOT_DIR/.github/workflows/preview.yml"
trusted="$ROOT_DIR/.github/workflows/hosted-identity-request.yml"
runtime="$ROOT_DIR/.github/workflows/runtime-images.yml"
publisher="$ROOT_DIR/.github/workflows/publish-pr-runtime-images.yml"
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

contains "$helm_values" 'mode: hosted-controller'
contains "$helm_ingress" 'ne (default "standalone" .Values.previewStack.certificateIdentity.mode) "hosted-controller"'
contains "$helm_certificate" 'ne (default "standalone" .Values.previewStack.certificateIdentity.mode) "hosted-controller"'
contains "$helm_apps" 'ne (default "standalone" $.Values.previewStack.certificateIdentity.mode) "hosted-controller"'

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
contains "$validator" 'MIN_PREVIEW_TELNET_PORT = 32000'
contains "$validator" 'MAX_PREVIEW_TELNET_PORT = 32015'

echo 'hosted identity controller workflow contract passed'
