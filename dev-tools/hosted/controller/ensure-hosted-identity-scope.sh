#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

# Apply one environment's narrow access slice as an operator/debug fallback.
# The controller during reconcile is the canonical owner of this establishment.
# This is intentionally a single-name operation: it never enumerates,
# bulk-creates, bulk-rotates, or removes environments. The controller owns the
# exact retained identity Namespace; runtime Namespace creation/deletion remains
# an operator or lifecycle-deployer responsibility.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTROL_NAMESPACE="firemud-system"
NAME=""

fail() {
  echo "hosted identity scope: $*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: ensure-hosted-identity-scope.sh --name dev-demo|pr-N
USAGE
  exit 2
}

while (($# > 0)); do
  case "$1" in
    --name)
      (($# >= 2)) || usage
      NAME="$2"
      shift 2
      ;;
    --help|-h)
      usage
      ;;
    *)
      fail "unknown or incomplete option: $1"
      ;;
  esac
done

[[ "${FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR:-}" == "1" ]] || \
  fail "set FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 from a trusted operator context"
[[ "$NAME" =~ ^(dev-demo|pr-[1-9][0-9]*)$ ]] || \
  fail "--name must be dev-demo or pr-N"
command -v kubectl >/dev/null 2>&1 || fail "kubectl is required"

if [[ "$NAME" == "dev-demo" ]]; then
  IDENTITY_NAMESPACE="dev-identity"
  RUNTIME_NAMESPACE="dev"
  ENVIRONMENT_CLASS="dev-demo-cluster"
  INGRESS_CERTIFICATE="dev-tls"
  TELNET_CERTIFICATE="dev-telnet-tls"
  EXPECTED_LABEL_KEY="firemud.dev/dev-demo"
  EXPECTED_LABEL_VALUE="true"
else
  IDENTITY_NAMESPACE="${NAME}-identity"
  RUNTIME_NAMESPACE="$NAME"
  ENVIRONMENT_CLASS="pr-preview"
  INGRESS_CERTIFICATE="${NAME}-tls"
  TELNET_CERTIFICATE="${NAME}-telnet-tls"
  EXPECTED_LABEL_KEY="firemud.dev/preview"
  EXPECTED_LABEL_VALUE="true"
fi

kubectl get namespace "$RUNTIME_NAMESPACE" >/dev/null || \
  fail "runtime namespace $RUNTIME_NAMESPACE must already exist"
kubectl get namespace "$IDENTITY_NAMESPACE" >/dev/null || \
  fail "identity namespace $IDENTITY_NAMESPACE must already exist"

runtime_label="$(kubectl get namespace "$RUNTIME_NAMESPACE" -o "jsonpath={.metadata.labels['$EXPECTED_LABEL_KEY']}")"
[[ "$runtime_label" == "$EXPECTED_LABEL_VALUE" ]] || \
  fail "runtime namespace $RUNTIME_NAMESPACE is not marked for $ENVIRONMENT_CLASS"
if [[ "$NAME" != "dev-demo" ]]; then
  pr_number="${NAME#pr-}"
  pr_label="$(kubectl get namespace "$RUNTIME_NAMESPACE" -o 'jsonpath={.metadata.labels.firemud\.dev/pr-number}')"
  [[ "$pr_label" == "$pr_number" ]] || \
    fail "runtime namespace $RUNTIME_NAMESPACE has no matching firemud.dev/pr-number"
else
  class_label="$(kubectl get namespace "$RUNTIME_NAMESPACE" -o 'jsonpath={.metadata.labels.firemud\.dev/environment-class}')"
  [[ "$class_label" == "$ENVIRONMENT_CLASS" ]] || \
    fail "runtime namespace $RUNTIME_NAMESPACE is not the dev-demo environment"
fi

controller_sa="system:serviceaccount:$CONTROL_NAMESPACE:firemud-hosted-identity-controller"
requester_sa="system:serviceaccount:$CONTROL_NAMESPACE:firemud-hosted-identity-requester"

# Only the fixed controller identity receives these Roles.  Secret CREATE is
# deliberately separate from named reads/updates because Kubernetes RBAC does
# not apply resourceNames to create; the fail-closed admission policy enforces
# the exact derived names on create.
kubectl apply \
  --server-side \
  --field-manager=firemud-hosted-identity-scope \
  -f - <<YAML
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: firemud-hosted-identity-scope
  namespace: ${IDENTITY_NAMESPACE}
  labels:
    app.kubernetes.io/name: hosted-environment-identity-controller
    app.kubernetes.io/component: controller-scope
    app.kubernetes.io/part-of: firemud
    firemud.dev/managed-by: hosted-identity-controller
    firemud.dev/identity-name: ${NAME}
    firemud.dev/environment-class: ${ENVIRONMENT_CLASS}
rules:
  - apiGroups:
      - cert-manager.io
    resources:
      - certificates
    verbs:
      - list
      - watch
  - apiGroups:
      - cert-manager.io
    resources:
      - certificates
    resourceNames:
      - ${INGRESS_CERTIFICATE}
      - ${TELNET_CERTIFICATE}
      - firemud-grpc-tls
    verbs:
      - get
      - update
      - patch
      - delete
  - apiGroups:
      - cert-manager.io
    resources:
      - certificates
    verbs:
      - create
  - apiGroups:
      - ""
    resources:
      - secrets
    resourceNames:
      - ${INGRESS_CERTIFICATE}
      - ${TELNET_CERTIFICATE}
      - firemud-grpc-tls
      - ${INGRESS_CERTIFICATE}-previous
      - ${TELNET_CERTIFICATE}-previous
      - firemud-grpc-tls-previous
      - firemud-grpc-ca
    verbs:
      - get
  - apiGroups:
      - ""
    resources:
      - secrets
    resourceNames:
      - ${INGRESS_CERTIFICATE}
      - ${TELNET_CERTIFICATE}
      - firemud-grpc-tls
      - ${INGRESS_CERTIFICATE}-previous
      - ${TELNET_CERTIFICATE}-previous
      - firemud-grpc-tls-previous
    verbs:
      - update
      - patch
      - delete
  - apiGroups:
      - ""
    resources:
      - secrets
    verbs:
      - create
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: firemud-hosted-runtime-scope
  namespace: ${RUNTIME_NAMESPACE}
  labels:
    app.kubernetes.io/name: hosted-environment-identity-controller
    app.kubernetes.io/component: controller-scope
    app.kubernetes.io/part-of: firemud
    firemud.dev/managed-by: hosted-identity-controller
    firemud.dev/identity-name: ${NAME}
    firemud.dev/environment-class: ${ENVIRONMENT_CLASS}
rules:
  - apiGroups:
      - ""
    resources:
      - secrets
    resourceNames:
      - ${INGRESS_CERTIFICATE}
      - ${TELNET_CERTIFICATE}
      - firemud-grpc-tls
    verbs:
      - get
      - update
      - patch
      - delete
  - apiGroups:
      - ""
    resources:
      - secrets
    verbs:
      - create
  - apiGroups:
      - apps
    resources:
      - deployments
    verbs:
      - list
      - watch
  - apiGroups:
      - apps
    resources:
      - deployments
    resourceNames:
      - account-service
      - automation-scripting-service
      - entity-management-service
      - game-design-service
      - game-logic-service
      - game-session-service
      - logging-admin-service
      - social-groups-service
      - spring-cloud-gateway
      - tcp-proxy-service
      - world-management-service
    verbs:
      - get
      - update
      - patch
  - apiGroups:
      - ""
    resources:
      - services
      - pods
    verbs:
      - get
      - list
      - watch
  - apiGroups:
      - networking.k8s.io
    resources:
      - ingresses
    verbs:
      - get
      - list
      - watch
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: firemud-hosted-identity-scope
  namespace: ${IDENTITY_NAMESPACE}
  labels:
    app.kubernetes.io/name: hosted-environment-identity-controller
    app.kubernetes.io/component: controller-scope
    app.kubernetes.io/part-of: firemud
    firemud.dev/managed-by: hosted-identity-controller
    firemud.dev/identity-name: ${NAME}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: firemud-hosted-identity-scope
subjects:
  - kind: ServiceAccount
    name: firemud-hosted-identity-controller
    namespace: ${CONTROL_NAMESPACE}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: firemud-hosted-runtime-scope
  namespace: ${RUNTIME_NAMESPACE}
  labels:
    app.kubernetes.io/name: hosted-environment-identity-controller
    app.kubernetes.io/component: controller-scope
    app.kubernetes.io/part-of: firemud
    firemud.dev/managed-by: hosted-identity-controller
    firemud.dev/identity-name: ${NAME}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: firemud-hosted-runtime-scope
subjects:
  - kind: ServiceAccount
    name: firemud-hosted-identity-controller
    namespace: ${CONTROL_NAMESPACE}
YAML

expect_can_i() {
  local expected="$1"
  shift
  local result
  result="$(kubectl auth can-i "$@" | tr -d '\r')"
  [[ "$result" == "$expected" ]] || fail "auth can-i $* returned $result; expected $expected"
}

expect_can_i yes --as="$controller_sa" --namespace="$IDENTITY_NAMESPACE" \
  get secret "$INGRESS_CERTIFICATE"
expect_can_i yes --as="$controller_sa" --namespace="$RUNTIME_NAMESPACE" \
  patch deployment account-service
expect_can_i no --as="$requester_sa" --namespace="$IDENTITY_NAMESPACE" \
  get secret "$INGRESS_CERTIFICATE"
expect_can_i no --as="$requester_sa" --namespace="$RUNTIME_NAMESPACE" \
  patch deployment account-service

echo "hosted identity scope applied for $NAME (identity=$IDENTITY_NAMESPACE runtime=$RUNTIME_NAMESPACE)"
