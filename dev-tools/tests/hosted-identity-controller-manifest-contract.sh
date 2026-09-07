#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

ROOT_DIR="$(cd "$(dirname "$(dirname "$(dirname "$BASH_SOURCE")")")" && pwd)"
MANIFEST_DIR="$ROOT_DIR/k8s/hosted-identity-controller"
CONTROLLER_DIR="$ROOT_DIR/dev-tools/hosted/controller"
PREVIEW_RBAC="$ROOT_DIR/k8s/preview/preview-deployer-rbac.yaml"
FIREMUD_STATEFUL_CORE="$ROOT_DIR/k8s/helm/firemud/templates/stateful-core.yaml"

fail() {
  echo "hosted identity controller manifest contract: $*" >&2
  exit 1
}

require_file() {
  [[ -f "$1" ]] || fail "missing file: $1"
}

require_literal() {
  local file="$1"
  local literal="$2"
  rg -Fq -- "$literal" "$file" || fail "missing '$literal' in $file"
}

require_regex() {
  local file="$1"
  local expression="$2"
  rg --quiet --regexp "$expression" "$file" || fail "missing /$expression/ in $file"
}

forbid_literal() {
  local file="$1"
  local literal="$2"
  if rg -Fq -- "$literal" "$file"; then
    fail "forbidden '$literal' found in $file"
  fi
}

forbid_regex() {
  local file="$1"
  local expression="$2"
  if rg --quiet --regexp "$expression" "$file"; then
    fail "forbidden /$expression/ found in $file"
  fi
}

for file in \
  "$MANIFEST_DIR/kustomization.yaml" \
  "$MANIFEST_DIR/namespace.yaml" \
  "$MANIFEST_DIR/serviceaccounts.yaml" \
  "$MANIFEST_DIR/crd.yaml" \
  "$MANIFEST_DIR/admission.yaml" \
  "$MANIFEST_DIR/rbac.yaml" \
  "$MANIFEST_DIR/deployment.yaml" \
  "$MANIFEST_DIR/networkpolicy.yaml" \
  "$MANIFEST_DIR/README.md" \
  "$CONTROLLER_DIR/bootstrap-hosted-identity-controller.sh" \
  "$CONTROLLER_DIR/ensure-hosted-identity-scope.sh" \
  "$CONTROLLER_DIR/validate-hosted-identity-controller.sh"; do
  require_file "$file"
done

KUSTOMIZATION="$MANIFEST_DIR/kustomization.yaml"
CRD="$MANIFEST_DIR/crd.yaml"
ADMISSION="$MANIFEST_DIR/admission.yaml"
RBAC="$MANIFEST_DIR/rbac.yaml"
DEPLOYMENT="$MANIFEST_DIR/deployment.yaml"
NETWORKPOLICY="$MANIFEST_DIR/networkpolicy.yaml"
BOOTSTRAP="$CONTROLLER_DIR/bootstrap-hosted-identity-controller.sh"
SCOPE="$CONTROLLER_DIR/ensure-hosted-identity-scope.sh"
VALIDATE="$CONTROLLER_DIR/validate-hosted-identity-controller.sh"
TRACKER="$ROOT_DIR/design/project-management/implementation-tracking/platform-operations-and-delivery.md"
PROJECTION="$ROOT_DIR/services/hosted-environment-identity-controller/src/main/java/net/firedevops/firemud/hostedidentity/kubernetes/SecretProjectionService.java"
GRPC_GENERATOR="$ROOT_DIR/services/hosted-environment-identity-controller/src/main/java/net/firedevops/firemud/hostedidentity/security/GrpcTransportBundleGenerator.java"

for resource in namespace serviceaccounts crd admission rbac deployment networkpolicy; do
  require_literal "$KUSTOMIZATION" "- $resource.yaml"
done
require_literal "$MANIFEST_DIR/namespace.yaml" "name: firemud-system"
for namespace_label in \
  "pod-security.kubernetes.io/enforce: restricted" \
  "pod-security.kubernetes.io/audit: restricted" \
  "pod-security.kubernetes.io/warn: restricted"; do
  require_literal "$MANIFEST_DIR/namespace.yaml" "$namespace_label"
done
require_literal "$MANIFEST_DIR/serviceaccounts.yaml" "name: firemud-hosted-identity-controller"
require_literal "$MANIFEST_DIR/serviceaccounts.yaml" "name: firemud-hosted-identity-requester"
for postgres_marker in \
  "name: PGDATA" \
  "/var/lib/postgresql/data/pgdata" \
  "runAsUser: 999" \
  "runAsGroup: 999" \
  "fsGroup: 999"; do
  require_literal "$FIREMUD_STATEFUL_CORE" "$postgres_marker"
done
for tracker_marker in \
  "Contract owners are [ADR 0182]" \
  "[Deployment Environments](../../architecture/infrastructure/deployment-environments.md)" \
  "Current implementation/proof status:" \
  "Static/live boundary:"; do
  require_literal "$TRACKER" "$tracker_marker"
done

require_literal "$CRD" "name: hostedenvironmentidentities.platform.firemud.dev"
require_literal "$CRD" "group: platform.firemud.dev"
require_literal "$CRD" "scope: Namespaced"
require_literal "$CRD" "kind: HostedEnvironmentIdentity"
require_literal "$CRD" "name: v1alpha1"
require_literal "$CRD" "served: true"
require_literal "$CRD" "storage: true"
require_literal "$CRD" "status: {}"
require_literal "$CRD" "additionalProperties: false"
require_literal "$CRD" "desiredState"
require_literal "$CRD" "- Active"
require_literal "$CRD" "- Retired"
require_literal "$CRD" "!has(oldSelf.desiredState)"
require_literal "$CRD" "self.metadata.namespace == 'firemud-system'"
require_literal "$CRD" "self.metadata.name.matches('^(dev-demo|pr-[1-9][0-9]*)$')"
for field in observedGeneration phase conditions profile runtimeNamespaceUid deployedHeadSha ingress telnet grpc; do
  require_literal "$CRD" "$field"
done
require_regex "$CRD" "format: date-time"
require_regex "$CRD" 'pattern: "\^\[0-9a-fA-F\]\{40\}\$"'

spec_block="$(sed -n '/^            spec:/,/^            status:/p' "$CRD")"
for forbidden_spec_field in hostname namespace secret issuer port key certificate consumer; do
  if grep -Eq "^[[:space:]]{16}${forbidden_spec_field}" <<<"$spec_block"; then
    fail "sensitive field $forbidden_spec_field leaked into CR spec"
  fi
done
[[ "$(grep -Ec '^                [A-Za-z][A-Za-z0-9]*:' <<<"$spec_block")" == "1" ]] || \
  fail "CR spec is not limited to desiredState"

[[ "$(grep -Fc 'failurePolicy: Fail' "$ADMISSION")" -ge 7 ]] || \
  fail "all admission policies must use failurePolicy Fail"
[[ "$(grep -Fc 'validationActions:' "$ADMISSION")" -ge 7 ]] || \
  fail "all policy bindings must specify validationActions"
for text_value in \
  firemud-hosted-identity-requester \
  firemud-hosted-identity-controller \
  "request.namespace == 'firemud-system'" \
  request.subResource \
  hostedenvironmentidentities/status \
  hostedenvironmentidentities/finalizers \
  oldObject.spec.desiredState \
  "oldObject.status.phase == 'Retired'" \
  ownerReferences \
  "startsWith('firemud.dev/')" \
  secrets \
  cert-manager.io \
  cert-manager:cert-manager \
  "object.spec.secretName == object.metadata.name" \
  "rotationPolicy == 'Always'" \
  firemud-grpc-tls-previous \
  "firemud.dev/role" \
  "firemud.dev/retention" \
  firemud-hosted-identity-scope-roles \
  firemud-hosted-identity-scope-rolebindings \
  'object.rules.size() == 6' \
  'object.rules.all' \
  'object.subjects.size() == 1' \
  "object.roleRef.apiGroup == 'rbac.authorization.k8s.io'" \
  "object.subjects[0].namespace == 'firemud-system'" \
  firemud-hosted-identity-scope \
  firemud-hosted-runtime-scope \
  controller-scope \
  firemud-hosted-system-namespace-guard \
  "request.operation in ['UPDATE', 'PATCH']" \
  "request.operation in ['CREATE', 'DELETE']" \
  "object.metadata.labels == oldObject.metadata.labels" \
  "object.metadata.ownerReferences == oldObject.metadata.ownerReferences" \
  "object.metadata.finalizers == oldObject.metadata.finalizers" \
  "object.spec == oldObject.spec" \
  "system:serviceaccount:kube-system:namespace-controller" \
  validationActions: \
  "- Deny"; do
  require_literal "$ADMISSION" "$text_value"
done
require_regex "$ADMISSION" 'dev-demo'
require_regex "$ADMISSION" 'pr-\[1-9\]\[0-9\]\*'
require_literal "$ADMISSION" "oldObject.metadata.labels['firemud.dev/retention'] == 'retained'"
ADMISSION="$ADMISSION" python3 - <<'PY'
import os
from pathlib import Path

import yaml

policies = {
    document["metadata"]["name"]: document
    for document in yaml.safe_load_all(Path(os.environ["ADMISSION"]).read_text(encoding="utf-8"))
    if isinstance(document, dict) and document.get("kind") == "ValidatingAdmissionPolicy"
}
break_glass = "request.userInfo.groups.exists(group, group == 'system:masters')"
callers = {
    "firemud-hosted-identity-main": "firemud-hosted-identity-requester",
    "firemud-hosted-identity-subresources": "firemud-hosted-identity-controller",
    "firemud-hosted-identity-scope-roles": "firemud-hosted-identity-controller",
    "firemud-hosted-identity-scope-rolebindings": "firemud-hosted-identity-controller",
}
for policy_name, service_account in callers.items():
    expressions = [
        validation["expression"]
        for validation in policies[policy_name]["spec"]["validations"]
    ]
    caller_expression = next(
        expression for expression in expressions if service_account in expression
    )
    assert caller_expression.startswith(f"{break_glass} ||"), policy_name

role_expression = policies["firemud-hosted-identity-scope-roles"]["spec"]["validations"][0]["expression"]
assert "(request.userInfo.username == 'system:serviceaccount:firemud-system:firemud-hosted-identity-controller' &&" in role_expression
assert "object.metadata.labels.size() == 6" in role_expression
assert "object.rules.size() == 6" in role_expression
binding_expression = policies["firemud-hosted-identity-scope-rolebindings"]["spec"]["validations"][0]["expression"]
assert "(request.userInfo.username == 'system:serviceaccount:firemud-system:firemud-hosted-identity-controller' &&" in binding_expression
assert "object.metadata.labels.size() == 5" in binding_expression
assert "object.subjects.size() == 1" in binding_expression
namespace_expression = policies["firemud-hosted-system-namespace-guard"]["spec"]["validations"][0]["expression"]
assert namespace_expression.startswith(f"({break_glass} &&")
assert "(request.operation == 'DELETE' ? request.name : object.metadata.name)" in namespace_expression
assert "'^(firemud-system|dev-identity|pr-[1-9][0-9]*-identity)$'" in namespace_expression
assert "request.userInfo.username == 'system:serviceaccount:firemud-system:firemud-hosted-identity-controller'" in namespace_expression
assert "oldObject.metadata.labels['firemud.dev/retention'] == 'retained'" in namespace_expression
assert "object.metadata.labels['firemud.dev/retention'] == 'retained'" in namespace_expression
secret_expressions = [
    validation["expression"]
    for validation in policies["firemud-hosted-identity-secret-boundary"]["spec"]["validations"]
]
cert_manager_expression = next(
    expression
    for expression in secret_expressions
    if "source-materialized" in expression
)
assert "ownerReferences.size() == 0" in cert_manager_expression
assert "object.type == 'kubernetes.io/tls'" in cert_manager_expression
assert "object.metadata.annotations['cert-manager.io/certificate-name'] == object.metadata.name" in cert_manager_expression
assert "object.metadata.annotations['cert-manager.io/issuer-name'] == 'letsencrypt-prod'" in cert_manager_expression
assert "object.metadata.annotations['cert-manager.io/issuer-kind'] == 'ClusterIssuer'" in cert_manager_expression
assert "object.metadata.annotations['cert-manager.io/issuer-group'] == 'cert-manager.io'" in cert_manager_expression
assert "object.metadata.name == 'dev-tls'" in cert_manager_expression
assert "object.metadata.name == 'dev-telnet-tls'" in cert_manager_expression
assert "request.namespace.substring(0, request.namespace.size() - 9) + '-tls'" in cert_manager_expression
assert "request.namespace.substring(0, request.namespace.size() - 9) + '-telnet-tls'" in cert_manager_expression
assert any("object.metadata.name != 'firemud-grpc-tls'" in expression for expression in secret_expressions)
PY
for phase in Pending Provisioning WaitingForCertificate RuntimeAbsent Syncing Verifying Ready Degraded Blocked Retiring Retired; do
  require_literal "$CRD" "- $phase"
done
for status_field in sourceGeneration sourceObjectGeneration spkiSha256; do
  require_literal "$CRD" "$status_field:"
done

for file in "$MANIFEST_DIR"/*.yaml "$PREVIEW_RBAC"; do
  forbid_literal "$file" 'apiGroups: ["*"]'
  forbid_literal "$file" 'resources: ["*"]'
done
require_literal "$RBAC" "name: firemud-hosted-identity-controller-namespace-lifecycle"
require_literal "$RBAC" "- namespaces"
require_literal "$RBAC" "- create"
require_literal "$RBAC" "- delete"
require_literal "$RBAC" "resourceNames:"
require_literal "$RBAC" "firemud-grpc-ca"
for forbidden_ha_marker in \
  'kind: Lease' \
  firemud-hosted-identity-leader-election \
  FIREMUD_HOSTED_IDENTITY_LEADER_ELECTION_LEASE; do
  forbid_literal "$RBAC" "$forbidden_ha_marker"
done
for consumer in \
  account-service automation-scripting-service entity-management-service \
  game-design-service game-logic-service game-session-service logging-admin-service \
  social-groups-service spring-cloud-gateway tcp-proxy-service world-management-service; do
  require_literal "$SCOPE" "- $consumer"
done
requester_rbac="$(sed -n '1,28p' "$RBAC")"
for forbidden_requester_permission in secrets certificates; do
  if grep -Fqi -- "$forbidden_requester_permission" <<<"$requester_rbac"; then
    fail "requester role has forbidden $forbidden_requester_permission access"
  fi
done
cluster_role_rbac="$(awk '
  /^---$/ { in_document = 0 }
  /^kind: ClusterRole$/ { in_document = 1 }
  in_document { print }
' "$RBAC")"
for forbidden_cluster_permission in secrets certificates hostedenvironmentidentities; do
  if grep -Fqi -- "$forbidden_cluster_permission" <<<"$cluster_role_rbac"; then
    fail "controller ClusterRole has broad $forbidden_cluster_permission access"
  fi
done
scope_writer_rbac="$(awk '
  /^---$/ { in_document = 0; is_scope_writer = 0 }
  /^kind: ClusterRole$/ { in_document = 1 }
  in_document && /^  name: firemud-hosted-identity-scope-writer$/ { is_scope_writer = 1 }
  in_document && is_scope_writer { print }
' "$RBAC")"
for text_value in \
  "- roles" \
  "- rolebindings" \
  "- escalate" \
  "- bind" \
  "- create" \
  "resourceNames:" \
  firemud-hosted-identity-scope \
  firemud-hosted-runtime-scope; do
  grep -Fq -- "$text_value" <<<"$scope_writer_rbac" || \
    fail "scope-writer ClusterRole is missing $text_value"
done
require_literal "$ADMISSION" "object.metadata.name == object.roleRef.name"
for forbidden_scope_permission in 'apiGroups: ["*"]' 'resources: ["*"]' namespaces secrets certificates; do
  if grep -Fqi -- "$forbidden_scope_permission" <<<"$scope_writer_rbac"; then
    fail "scope-writer ClusterRole has forbidden $forbidden_scope_permission access"
  fi
done

for text_value in \
  "serviceAccountName: firemud-hosted-identity-controller" \
  "@sha256:__IMAGE_DIGEST_REQUIRED__" \
  "runAsNonRoot: true" \
  "readOnlyRootFilesystem: true" \
  "allowPrivilegeEscalation: false" \
  "- ALL" \
  "type: RuntimeDefault" \
  "requests:" \
  "limits:" \
  "/actuator/health/liveness" \
  "/actuator/health/readiness" \
  "replicas: 1" \
  "type: Recreate" \
  FIREMUD_HOSTED_IDENTITY_CONTROL_NAMESPACE \
  FIREMUD_HOSTED_IDENTITY_PREVIEW_DOMAIN \
  FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE \
  __ACTIVATION_MODE_REQUIRED__ \
  FIREMUD_HOSTED_IDENTITY_GRPC_TRUST_ANCHOR_SHA256 \
  __GRPC_TRUST_ANCHOR_SHA256_REQUIRED__; do
  require_literal "$DEPLOYMENT" "$text_value"
done
for marker in ':latest' ':stable' ':main' ':develop'; do
  forbid_literal "$DEPLOYMENT" "$marker"
done
forbidden_strategy="type: RollingUpdate"
forbid_literal "$DEPLOYMENT" "$forbidden_strategy"

for text_value in \
  policyTypes: '- Egress' 'k8s-app: kube-dns' 'port: 53' 'port: 443' \
  'endPort: 32016' 'port: 6565' 'firemud.dev/preview: "true"' \
  'firemud.dev/dev-demo: "true"' 'cannot select the apiserver or a public hostname' \
  'except:' '169.254.0.0/16' 'fe80::/10'; do
  require_literal "$NETWORKPOLICY" "$text_value"
done
NETWORKPOLICY="$NETWORKPOLICY" python3 - <<'PY'
import os
from pathlib import Path

import yaml

policy = yaml.safe_load(Path(os.environ["NETWORKPOLICY"]).read_text(encoding="utf-8"))
grpc_targets = [
    target
    for rule in policy["spec"]["egress"]
    if any(port.get("port") == 6565 for port in rule.get("ports", []))
    for target in rule.get("to", [])
]
assert grpc_targets, "controller gRPC egress rule is missing"
for target in grpc_targets:
    assert target.get("namespaceSelector", {}).get("matchLabels", {}).get(
        "firemud.dev/preview"
    ) == "true" or target.get("namespaceSelector", {}).get("matchLabels", {}).get(
        "firemud.dev/dev-demo"
    ) == "true", target
    assert target.get("podSelector", {}).get("matchLabels") == {
        "app": "account-service"
    }, target
PY
for text_value in \
  'name: account-service-controller-ingress' \
  'app: account-service' \
  'kubernetes.io/metadata.name: firemud-system' \
  'port: 6565'; do
  require_literal "$ROOT_DIR/k8s/helm/firemud/templates/network-policies.yaml" "$text_value"
done

for text_value in \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR \
  --server-side \
  --field-manager \
  'kubectl kustomize' \
  'rollout status' \
  'kubectl auth can-i' \
  --identity-name \
  'operator bootstrap/debug' \
  'paused|observe|active' \
  --grpc-trust-anchor-sha256 \
  'GRPC_TRUST_ANCHOR_SHA256" =~ ^[0-9a-f]{64}$' \
  '@sha256:[0-9a-f]{64}'; do
  require_literal "$BOOTSTRAP" "$text_value"
done
for admission_name in \
  firemud-hosted-identity-main \
  firemud-hosted-identity-subresources \
  firemud-hosted-identity-secret-boundary \
  firemud-hosted-identity-certificate-boundary \
  firemud-hosted-identity-scope-roles \
  firemud-hosted-identity-scope-rolebindings \
  firemud-hosted-system-namespace-guard; do
  require_literal "$BOOTSTRAP" "$admission_name"
done
require_literal "$BOOTSTRAP" "validatingadmissionpolicybinding"
require_literal "$BOOTSTRAP" ".spec.failurePolicy"
require_literal "$BOOTSTRAP" ".spec.validationActions[*]"
for forbidden_command in 'kubectl delete' 'kubectl apply --all'; do
  forbid_literal "$BOOTSTRAP" "$forbidden_command"
done
for text_value in FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR --server-side resourceNames: firemud-grpc-tls firemud-grpc-tls-previous '${INGRESS_CERTIFICATE}-previous' '${TELNET_CERTIFICATE}-previous' firemud-grpc-ca 'operator/debug' 'controller during reconcile'; do
  require_literal "$SCOPE" "$text_value"
done
require_literal "$ADMISSION" "'firemud-grpc-tls-previous', 'firemud-grpc-ca'"
require_literal "$ADMISSION" "request.userInfo.username == 'system:serviceaccount:firemud-system:firemud-hosted-identity-controller' ||"
for ca_proof in \
  'get secret firemud-grpc-ca' \
  "ca.crt\\nca.key" \
  "openssl x509 -outform DER" \
  'does not match the configured fingerprint'; do
  require_literal "$BOOTSTRAP" "$ca_proof"
done
require_literal "$PROJECTION" "ACCEPTED_SOURCE_OBJECT_GENERATION_ANNOTATION"
forbid_literal "$GRPC_GENERATOR" 'requiredData(caSource, "tls.crt")'
forbid_literal "$GRPC_GENERATOR" 'requiredData(caSource, "tls.key")'
for forbidden_command in 'kubectl delete' '--all-namespaces' '--all'; do
  forbid_literal "$SCOPE" "$forbidden_command"
done
for forbidden_command in 'kubectl apply' 'kubectl delete' 'kubectl replace'; do
  forbid_literal "$VALIDATE" "$forbidden_command"
done

(
validator_test_dir="$(mktemp -d)"
trap 'rm -rf "$validator_test_dir"' EXIT
validator_output="$validator_test_dir/output"
validator_error="$validator_test_dir/error"
cat >"$validator_test_dir/kubectl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$1" == "get" && "$2" == "namespace" ]]; then
  exit 0
fi
if [[ "$1" == "get" && "$2" == "crd" ]]; then
  exit 0
fi
if [[ "$1" == "get" && "$2" == "validatingadmissionpolicy" ]]; then
  printf 'Fail\n'
  exit 0
fi
if [[ "$1" == "get" && "$2" == "validatingadmissionpolicybinding" ]]; then
  printf 'Deny\n'
  exit 0
fi
if [[ "$1" == "-n" && "$3" == "get" && "$4" == "deployment" ]]; then
  printf 'firemud-hosted-identity-controller\n'
  exit 0
fi
if [[ "$1" == "auth" && "$2" == "can-i" ]]; then
  if [[ " $* " == *" --all-namespaces "* || " $* " == *" --namespace=dev "* ]]; then
    if [[ "${FAKE_CAN_I_ERROR:-0}" == "1" ]]; then
      echo 'simulated authorization API failure' >&2
      exit 2
    fi
    if [[ "${FAKE_CAN_I_ALLOW_DENIED:-0}" == "1" ]]; then
      printf 'yes\n'
      exit 0
    fi
    printf 'no\n'
    exit 1
  fi
  printf 'yes\n'
  exit 0
fi
echo "unexpected fake kubectl invocation: $*" >&2
exit 2
SH
chmod +x "$validator_test_dir/kubectl"
PATH="$validator_test_dir:$PATH" bash "$VALIDATE" >"$validator_output" 2>"$validator_error" || \
  fail "validator rejected expected auth can-i no results: $(cat "$validator_error")"
require_literal "$validator_output" "RBAC and admission discovery checks passed"
if FAKE_CAN_I_ERROR=1 PATH="$validator_test_dir:$PATH" \
  bash "$VALIDATE" >"$validator_output" 2>"$validator_error"; then
  fail "validator accepted an authorization API error"
fi
require_literal "$validator_error" "failed with status 2"
if FAKE_CAN_I_ALLOW_DENIED=1 PATH="$validator_test_dir:$PATH" \
  bash "$VALIDATE" >"$validator_output" 2>"$validator_error"; then
  fail "validator accepted an unexpectedly allowed permission"
fi
require_literal "$validator_error" "returned yes; expected no"
)

(
bootstrap_test_dir="$(mktemp -d)"
trap 'rm -rf "$bootstrap_test_dir"' EXIT
bootstrap_output="$bootstrap_test_dir/output"
bootstrap_error="$bootstrap_test_dir/error"
cat >"$bootstrap_test_dir/kubectl" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${1:-}" == "kustomize" ]]; then
  cat <<'YAML'
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      containers:
        - name: controller
          image: ghcr.io/benhook1013/hosted-environment-identity-controller@sha256:__IMAGE_DIGEST_REQUIRED__
          env:
            - name: FIREMUD_HOSTED_IDENTITY_GRPC_TRUST_ANCHOR_SHA256
              value: __GRPC_TRUST_ANCHOR_SHA256_REQUIRED__
            - name: FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE
              value: __ACTIVATION_MODE_REQUIRED__
YAML
  exit 0
fi
if [[ "${1:-}" == "apply" ]]; then
  exit 0
fi
if [[ "${1:-}" == "-n" && "${2:-}" == "firemud-system" && "${3:-}" == "rollout" ]]; then
  exit 0
fi
if [[ "${1:-}" == "-n" && "${2:-}" == "firemud-system" && "${3:-}" == "get" && "${4:-}" == "deployment" ]]; then
  printf '1/1\n'
  exit 0
fi
if [[ "${1:-}" == "get" && "${2:-}" == "crd" ]]; then
  printf 'True\n'
  exit 0
fi
if [[ "${1:-}" == "get" && "${2:-}" == "validatingadmissionpolicy" ]]; then
  printf 'Fail\n'
  exit 0
fi
if [[ "${1:-}" == "get" && "${2:-}" == "validatingadmissionpolicybinding" ]]; then
  printf 'Deny\n'
  exit 0
fi
if [[ "${1:-}" == "auth" && "${2:-}" == "can-i" ]]; then
  if [[ " $* " == *" --all-namespaces "* || " $* " == *" --namespace=dev "* ]]; then
    if [[ "${FAKE_CAN_I_ERROR:-0}" == "1" ]]; then
      printf 'simulated authorization API failure\n' >&2
      exit 2
    fi
    if [[ "${FAKE_CAN_I_ALLOW_DENIED:-0}" == "1" ]]; then
      printf 'yes\n'
      exit 0
    fi
    printf 'no\n'
    exit 1
  fi
  printf 'yes\n'
  exit 0
fi
printf 'unexpected fake kubectl invocation: %s\n' "$*" >&2
exit 2
SH
chmod +x "$bootstrap_test_dir/kubectl"
bootstrap_image='ghcr.io/benhook1013/hosted-environment-identity-controller@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
bootstrap_fingerprint='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
if ! FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 PATH="$bootstrap_test_dir:$PATH" \
  bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap rejected expected auth can-i no results: $(cat "$bootstrap_error")"
fi
require_literal "$bootstrap_output" "activation=paused"
if FAKE_CAN_I_ERROR=1 FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$bootstrap_test_dir:$PATH" bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted an authorization API error"
fi
require_literal "$bootstrap_error" "failed with status 2"
if FAKE_CAN_I_ALLOW_DENIED=1 FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$bootstrap_test_dir:$PATH" bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted an unexpectedly allowed permission"
fi
require_literal "$bootstrap_error" "returned yes; expected no"
)

forbidden_preview='cert-manager.io'
forbid_literal "$PREVIEW_RBAC" "$forbidden_preview"
forbid_literal "$PREVIEW_RBAC" "acme.cert-manager.io"
require_literal "$PREVIEW_RBAC" "firemud.dev/controller-authority: excluded"

for file in "$MANIFEST_DIR"/*.yaml "$CONTROLLER_DIR"/*.sh; do
  forbid_regex "$file" '(production|staging|hobby-self-hosted)'
done
require_literal "$SCOPE" '^(dev-demo|pr-[1-9][0-9]*)$'
require_literal "$SCOPE" "identity namespace"
require_literal "$SCOPE" "runtime namespace"

rendered="$(mktemp)"
trap 'rm -f "$rendered"' EXIT
kubectl kustomize "$MANIFEST_DIR" >"$rendered"
require_literal "$rendered" "kind: CustomResourceDefinition"
require_literal "$rendered" "kind: ValidatingAdmissionPolicy"
require_literal "$rendered" "kind: Deployment"
forbid_literal "$rendered" 'apiGroups: ["*"]'
forbid_literal "$rendered" 'resources: ["*"]'

echo "hosted identity controller manifest contract passed"
