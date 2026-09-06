#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

# This command is an operator bootstrap, not a workflow job.  The controller
# establishes scopes during reconcile; --identity-name is only an operator
# bootstrap/debug fallback for one named environment.  The command intentionally
# has no --as/impersonation option and requires an explicit trusted-operator
# acknowledgement before it can apply cluster-scoped resources.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
MANIFEST_DIR="$REPO_ROOT/k8s/hosted-identity-controller"
CONTROL_NAMESPACE="firemud-system"
DEPLOYMENT_NAME="firemud-hosted-identity-controller"
FIELD_MANAGER="firemud-hosted-identity-bootstrap"
ACTIVATION_MODE="paused"
IMAGE_REF="${FIREMUD_HOSTED_IDENTITY_CONTROLLER_IMAGE:-}"
GRPC_TRUST_ANCHOR_SHA256="${FIREMUD_HOSTED_IDENTITY_GRPC_TRUST_ANCHOR_SHA256:-}"
IDENTITY_NAME=""
WAIT_SECONDS="${FIREMUD_HOSTED_IDENTITY_BOOTSTRAP_TIMEOUT_SECONDS:-180}"

fail() {
  echo "hosted identity bootstrap: $*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: bootstrap-hosted-identity-controller.sh --image ghcr.io/benhook1013/hosted-environment-identity-controller@sha256:<64 hex> [options]

Options:
  --activation-mode MODE  paused (default), observe, or active
  --identity-name NAME    apply one derived scope as an operator bootstrap/debug fallback
  --wait-seconds N        deployment wait timeout (default: 180)
  --image IMAGE           immutable controller image (also accepted by env)
  --grpc-trust-anchor-sha256 SHA256
                           required gRPC CA SHA-256 fingerprint (also accepted by env)
USAGE
  exit 2
}

while (($# > 0)); do
  case "$1" in
    --activation-mode)
      (($# >= 2)) || usage
      ACTIVATION_MODE="$2"
      shift 2
      ;;
    --identity-name)
      (($# >= 2)) || usage
      IDENTITY_NAME="$2"
      shift 2
      ;;
    --wait-seconds)
      (($# >= 2)) || usage
      WAIT_SECONDS="$2"
      shift 2
      ;;
    --image)
      (($# >= 2)) || usage
      IMAGE_REF="$2"
      shift 2
      ;;
    --grpc-trust-anchor-sha256)
      (($# >= 2)) || usage
      GRPC_TRUST_ANCHOR_SHA256="$2"
      shift 2
      ;;
    --help|-h)
      usage
      ;;
    --*)
      fail "unknown option $1"
      ;;
    *)
      fail "unexpected argument $1"
      ;;
  esac
done

[[ "${FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR:-}" == "1" ]] || \
  fail "set FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 from a trusted operator context"
[[ -n "$IMAGE_REF" ]] || fail "an immutable --image is required"
[[ "$IMAGE_REF" =~ ^ghcr\.io/benhook1013/hosted-environment-identity-controller@sha256:[0-9a-f]{64}$ ]] || \
  fail "--image must be the approved controller repository pinned by a 64-hex sha256 digest"
[[ "$GRPC_TRUST_ANCHOR_SHA256" =~ ^[0-9a-fA-F]{64}$ ]] || \
  fail "--grpc-trust-anchor-sha256 or FIREMUD_HOSTED_IDENTITY_GRPC_TRUST_ANCHOR_SHA256 must be a 64-hex fingerprint"
case "$ACTIVATION_MODE" in
  paused|observe|active) ;;
  *) fail "--activation-mode must be paused, observe, or active" ;;
esac
initial_activation_mode="$ACTIVATION_MODE"
if [[ "$ACTIVATION_MODE" == "active" ]]; then
  # Install into a paused state first.  This prevents a fresh or partially
  # upgraded cluster from reconciling before every admission boundary has been
  # discovered and verified below.
  initial_activation_mode="paused"
fi
[[ "$WAIT_SECONDS" =~ ^[1-9][0-9]*$ ]] || fail "--wait-seconds must be a positive integer"
if [[ -n "$IDENTITY_NAME" && ! "$IDENTITY_NAME" =~ ^(dev-demo|pr-[1-9][0-9]*)$ ]]; then
  fail "--identity-name must be dev-demo or pr-N"
fi
command -v kubectl >/dev/null 2>&1 || fail "kubectl is required"
[[ -d "$MANIFEST_DIR" ]] || fail "missing manifest directory: $MANIFEST_DIR"

temporary_manifest="$(mktemp)"
cleanup() {
  rm -f "$temporary_manifest"
}
trap cleanup EXIT
umask 077

# Render privately so the checked-in base cannot silently acquire a mutable
# image tag or an activation mode.  Server-side apply below remains the only
# cluster write path.
kubectl kustomize "$MANIFEST_DIR" >"$temporary_manifest"
sed -i \
  -e "s#ghcr.io/benhook1013/hosted-environment-identity-controller@sha256:__IMAGE_DIGEST_REQUIRED__#$IMAGE_REF#g" \
  -e "s#value: __GRPC_TRUST_ANCHOR_SHA256_REQUIRED__#value: $GRPC_TRUST_ANCHOR_SHA256#g" \
  -e "s#value: __ACTIVATION_MODE_REQUIRED__#value: $initial_activation_mode#g" \
  "$temporary_manifest"
grep -Fq -- "$IMAGE_REF" "$temporary_manifest" || fail "immutable image replacement did not occur"
grep -Fq -- "value: $GRPC_TRUST_ANCHOR_SHA256" "$temporary_manifest" || fail "gRPC trust-anchor replacement did not occur"
grep -Fq -- "value: $initial_activation_mode" "$temporary_manifest" || fail "activation mode replacement did not occur"
if grep -Fq -- "__IMAGE_DIGEST_REQUIRED__" "$temporary_manifest" || \
   grep -Fq -- "__GRPC_TRUST_ANCHOR_SHA256_REQUIRED__" "$temporary_manifest" || \
   grep -Fq -- "__ACTIVATION_MODE_REQUIRED__" "$temporary_manifest"; then
  fail "rendered manifests still contain a required-input marker"
fi

kubectl apply \
  --server-side \
  --field-manager="$FIELD_MANAGER" \
  -f "$temporary_manifest"

kubectl -n "$CONTROL_NAMESPACE" rollout status \
  "deployment/$DEPLOYMENT_NAME" \
  --timeout="${WAIT_SECONDS}s"
kubectl -n "$CONTROL_NAMESPACE" get deployment "$DEPLOYMENT_NAME" \
  -o jsonpath='{.status.availableReplicas}/{.spec.replicas}{"\n"}'
kubectl get crd hostedenvironmentidentities.platform.firemud.dev \
  -o jsonpath='{.status.conditions[?(@.type=="Established")].status}{"\n"}'

# Active mode is fail-closed until every policy and binding in the install
# boundary exists. Checking one representative policy is insufficient: a
# missing subresource, Secret, Certificate, or scope policy could reopen a
# controller write path.
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
done

verify_grpc_ca_prerequisite() {
  command -v base64 >/dev/null 2>&1 || fail "base64 is required to validate the gRPC CA"
  command -v openssl >/dev/null 2>&1 || fail "openssl is required to validate the gRPC CA"
  command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required to validate the gRPC CA"
  local secret_type ca_keys encoded_certificate encoded_key actual_fingerprint
  secret_type="$(kubectl -n "$CONTROL_NAMESPACE" get secret firemud-grpc-ca \
    -o jsonpath='{.type}' 2>/dev/null)" || fail "missing trusted firemud-system/firemud-grpc-ca prerequisite"
  [[ "$secret_type" == "Opaque" ]] || fail "firemud-grpc-ca must be an Opaque Secret"
  ca_keys="$(kubectl -n "$CONTROL_NAMESPACE" get secret firemud-grpc-ca \
    -o go-template='{{range $key, $value := .data}}{{printf "%s\n" $key}}{{end}}' | LC_ALL=C sort)"
  [[ "$ca_keys" == $'ca.crt\nca.key' ]] || \
    fail "firemud-grpc-ca must contain exactly the ca.crt and ca.key data keys"
  encoded_certificate="$(kubectl -n "$CONTROL_NAMESPACE" get secret firemud-grpc-ca \
    -o jsonpath='{.data.ca\.crt}')"
  [[ -n "$encoded_certificate" ]] || fail "firemud-grpc-ca ca.crt is empty"
  encoded_key="$(kubectl -n "$CONTROL_NAMESPACE" get secret firemud-grpc-ca \
    -o jsonpath='{.data.ca\.key}')"
  [[ -n "$encoded_key" ]] || fail "firemud-grpc-ca ca.key is empty"
  actual_fingerprint="$(
    printf '%s' "$encoded_certificate" |
      base64 --decode |
      openssl x509 -outform DER 2>/dev/null |
      sha256sum |
      awk '{print $1}'
  )" || fail "firemud-grpc-ca ca.crt is not a valid certificate"
  [[ "$actual_fingerprint" == "${GRPC_TRUST_ANCHOR_SHA256,,}" ]] || \
    fail "firemud-grpc-ca ca.crt does not match the configured fingerprint"
}

if [[ "$ACTIVATION_MODE" == "active" ]]; then
  verify_grpc_ca_prerequisite
  # Re-rendering is unnecessary: the only changed value is the enum-validated
  # activation field.  Re-applying the complete private manifest keeps the
  # transition under the same server-side field manager as bootstrap.
  sed -i "/FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE/{n;s/value: $initial_activation_mode/value: $ACTIVATION_MODE/;}" "$temporary_manifest"
  grep -Fq -- "value: $ACTIVATION_MODE" "$temporary_manifest" || fail "active activation replacement did not occur"
  kubectl apply \
    --server-side \
    --field-manager="$FIELD_MANAGER" \
    -f "$temporary_manifest"
  kubectl -n "$CONTROL_NAMESPACE" rollout status \
    "deployment/$DEPLOYMENT_NAME" \
    --timeout="${WAIT_SECONDS}s"
fi

controller_sa="system:serviceaccount:$CONTROL_NAMESPACE:firemud-hosted-identity-controller"
requester_sa="system:serviceaccount:$CONTROL_NAMESPACE:firemud-hosted-identity-requester"

expect_can_i() {
  local expected="$1"
  shift
  local result
  result="$(kubectl auth can-i "$@" | tr -d '\r')"
  [[ "$result" == "$expected" ]] || fail "auth can-i $* returned $result; expected $expected"
}

# Positive checks prove the narrow intended calls; negative checks are part of
# bootstrap because a stale broad ClusterRole must stop installation.
expect_can_i yes --as="$controller_sa" --namespace="$CONTROL_NAMESPACE" \
  get hostedenvironmentidentities.platform.firemud.dev
expect_can_i yes --as="$controller_sa" --namespace="$CONTROL_NAMESPACE" \
  update hostedenvironmentidentities/status.platform.firemud.dev
expect_can_i yes --as="$controller_sa" --namespace="$CONTROL_NAMESPACE" \
  update hostedenvironmentidentities/finalizers.platform.firemud.dev
expect_can_i yes --as="$controller_sa" get namespace
expect_can_i yes --as="$requester_sa" --namespace="$CONTROL_NAMESPACE" \
  create hostedenvironmentidentities.platform.firemud.dev
expect_can_i yes --as="$requester_sa" --namespace="$CONTROL_NAMESPACE" \
  update hostedenvironmentidentities.platform.firemud.dev
expect_can_i no --as="$controller_sa" --all-namespaces list secrets
expect_can_i no --as="$controller_sa" --all-namespaces create certificates.cert-manager.io
expect_can_i yes --as="$controller_sa" create namespaces
expect_can_i no --as="$requester_sa" --all-namespaces list secrets
expect_can_i no --as="$requester_sa" --namespace=dev get hostedenvironmentidentities.platform.firemud.dev

if [[ -n "$IDENTITY_NAME" ]]; then
  "$SCRIPT_DIR/ensure-hosted-identity-scope.sh" --name "$IDENTITY_NAME"
fi

echo "hosted identity controller bootstrap applied in $CONTROL_NAMESPACE (activation=$ACTIVATION_MODE)"
