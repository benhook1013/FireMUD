#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

# Read-only post-bootstrap validation.  It deliberately performs no apply,
# replace, rollout restart, issue, or deletion operation.
CONTROL_NAMESPACE="firemud-system"
DEPLOYMENT_NAME="firemud-hosted-identity-controller"
CONTROLLER_SA="system:serviceaccount:$CONTROL_NAMESPACE:firemud-hosted-identity-controller"
REQUESTER_SA="system:serviceaccount:$CONTROL_NAMESPACE:firemud-hosted-identity-requester"

fail() {
  echo "hosted identity validation: $*" >&2
  exit 1
}

command -v kubectl >/dev/null 2>&1 || fail "kubectl is required"

kubectl get namespace "$CONTROL_NAMESPACE" >/dev/null || fail "missing $CONTROL_NAMESPACE"
kubectl get crd hostedenvironmentidentities.platform.firemud.dev >/dev/null || \
  fail "HostedEnvironmentIdentity CRD is not installed"
required_admission_policies=(
  firemud-hosted-identity-main
  firemud-hosted-identity-subresources
  firemud-hosted-identity-secret-boundary
  firemud-hosted-identity-certificate-boundary
  firemud-hosted-identity-scope-roles
  firemud-hosted-identity-scope-rolebindings
  firemud-hosted-system-namespace-guard
)
for admission_name in "${required_admission_policies[@]}"; do
  policy_failure_policy="$(kubectl get validatingadmissionpolicy "$admission_name" \
    -o jsonpath='{.spec.failurePolicy}{"\n"}')"
  [[ "$policy_failure_policy" == "Fail" ]] || \
    fail "$admission_name admission policy is missing failurePolicy=Fail"
  binding_actions="$(kubectl get validatingadmissionpolicybinding "$admission_name" \
    -o jsonpath='{.spec.validationActions[*]}{"\n"}')"
  [[ " $binding_actions " == *" Deny "* ]] || \
    fail "$admission_name admission policy binding is missing validationActions Deny"
  kubectl get validatingadmissionpolicy "$admission_name" >/dev/null || \
    fail "$admission_name admission policy is not installed"
done
kubectl -n "$CONTROL_NAMESPACE" get deployment "$DEPLOYMENT_NAME" \
  -o jsonpath='{.spec.template.spec.serviceAccountName}{"\n"}' | \
  grep -Fxq firemud-hosted-identity-controller || fail "deployment uses the wrong service account"

expect_can_i() {
  local expected="$1"
  shift
  local result
  result="$(kubectl auth can-i "$@" | tr -d '\r')"
  [[ "$result" == "$expected" ]] || fail "auth can-i $* returned $result; expected $expected"
}

expect_can_i yes --as="$CONTROLLER_SA" --namespace="$CONTROL_NAMESPACE" \
  get hostedenvironmentidentities.platform.firemud.dev
expect_can_i yes --as="$CONTROLLER_SA" --namespace="$CONTROL_NAMESPACE" \
  update hostedenvironmentidentities/status.platform.firemud.dev
expect_can_i yes --as="$CONTROLLER_SA" get namespace
expect_can_i yes --as="$CONTROLLER_SA" create namespaces
expect_can_i no --as="$CONTROLLER_SA" --all-namespaces list secrets
expect_can_i no --as="$CONTROLLER_SA" --all-namespaces create certificates.cert-manager.io
expect_can_i no --as="$REQUESTER_SA" --all-namespaces list secrets
expect_can_i no --as="$REQUESTER_SA" --namespace=dev \
  get hostedenvironmentidentities.platform.firemud.dev

echo "hosted identity controller RBAC and admission discovery checks passed"
