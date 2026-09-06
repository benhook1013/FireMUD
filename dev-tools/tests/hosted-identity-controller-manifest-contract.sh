#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

ROOT_DIR="$(cd "$(dirname "$(dirname "$(dirname "$BASH_SOURCE")")")" && pwd)"
MANIFEST_DIR="$ROOT_DIR/k8s/hosted-identity-controller"
CONTROLLER_DIR="$ROOT_DIR/dev-tools/hosted/controller"
PREVIEW_RBAC="$ROOT_DIR/k8s/preview/preview-deployer-rbac.yaml"

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
PROJECTION="$ROOT_DIR/services/hosted-environment-identity-controller/src/main/java/net/firedevops/firemud/hostedidentity/kubernetes/SecretProjectionService.java"
GRPC_GENERATOR="$ROOT_DIR/services/hosted-environment-identity-controller/src/main/java/net/firedevops/firemud/hostedidentity/security/GrpcTransportBundleGenerator.java"

for resource in namespace serviceaccounts crd admission rbac deployment networkpolicy; do
  require_literal "$KUSTOMIZATION" "- $resource.yaml"
done
require_literal "$MANIFEST_DIR/namespace.yaml" "name: firemud-system"
require_literal "$MANIFEST_DIR/serviceaccounts.yaml" "name: firemud-hosted-identity-controller"
require_literal "$MANIFEST_DIR/serviceaccounts.yaml" "name: firemud-hosted-identity-requester"

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
  validationActions: \
  "- Deny"; do
  require_literal "$ADMISSION" "$text_value"
done
require_regex "$ADMISSION" 'dev-demo'
require_regex "$ADMISSION" 'pr-\[1-9\]\[0-9\]\*'
require_literal "$ADMISSION" "oldObject.metadata.labels['firemud.dev/retention'] == 'retained'"
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
cluster_role_rbac="$(sed -n '/^kind: ClusterRole$/,/^kind: ClusterRoleBinding$/p' "$RBAC")"
for forbidden_cluster_permission in secrets certificates hostedenvironmentidentities; do
  if grep -Fqi -- "$forbidden_cluster_permission" <<<"$cluster_role_rbac"; then
    fail "controller ClusterRole has broad $forbidden_cluster_permission access"
  fi
done
scope_writer_rbac="$(sed -n '/^  name: firemud-hosted-identity-scope-writer$/,/^kind: ClusterRoleBinding$/p' "$RBAC")"
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

for text_value in \
  policyTypes: '- Egress' 'k8s-app: kube-dns' 'port: 53' 'port: 443' \
  'endPort: 32016' 'cannot select the apiserver or a public hostname'; do
  require_literal "$NETWORKPOLICY" "$text_value"
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
