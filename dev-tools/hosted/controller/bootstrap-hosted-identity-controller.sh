#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

# This command is an operator bootstrap, not a workflow job. The controller
# establishes each named scope during reconciliation. The command intentionally
# exposes no operator-facing --as/impersonation flag and requires an explicit
# trusted-operator acknowledgement before it can apply cluster-scoped resources.
# Internal --as use is confined to read-only kubectl auth can-i verification.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
MANIFEST_DIR="$REPO_ROOT/k8s/hosted-identity-controller"
CONTROL_NAMESPACE="firemud-system"
DEPLOYMENT_NAME="firemud-hosted-identity-controller"
FIELD_MANAGER="firemud-hosted-identity-bootstrap"
ACTIVATION_MODE="paused"
IMAGE_REF="${FIREMUD_HOSTED_IDENTITY_CONTROLLER_IMAGE:-}"
GRPC_TRUST_ANCHOR_SHA256="${FIREMUD_HOSTED_IDENTITY_GRPC_TRUST_ANCHOR_SHA256:-}"
WAIT_SECONDS="${FIREMUD_HOSTED_IDENTITY_BOOTSTRAP_TIMEOUT_SECONDS:-480}"

fail() {
  echo "hosted identity bootstrap: $*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: bootstrap-hosted-identity-controller.sh --image ghcr.io/benhook1013/firemud-hosted-identity-controller@sha256:<64 hex> [options]

Options:
  --activation-mode MODE  paused (default), observe, or active
  --wait-seconds N        deployment wait timeout (default: 480)
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
[[ "$IMAGE_REF" =~ ^ghcr\.io/benhook1013/firemud-hosted-identity-controller@sha256:[0-9a-f]{64}$ ]] || \
  fail "--image must be the approved controller repository pinned by a 64-hex sha256 digest"
[[ "$GRPC_TRUST_ANCHOR_SHA256" =~ ^[0-9a-f]{64}$ ]] || \
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
if ! [[ "$WAIT_SECONDS" =~ ^[1-9][0-9]*$ ]] ||
  ((${#WAIT_SECONDS} > 4)) ||
  ((10#$WAIT_SECONDS > 3600)); then
  fail "--wait-seconds must be an integer between 1 and 3600"
fi
command -v kubectl >/dev/null 2>&1 || fail "kubectl is required"
command -v gh >/dev/null 2>&1 || fail "gh is required to verify controller image provenance"
[[ -d "$MANIFEST_DIR" ]] || fail "missing manifest directory: $MANIFEST_DIR"
umask 077

temporary_manifest="$(mktemp)"
temporary_rendered_manifest=""
namespace_guard_policy_manifest="$(mktemp)"
namespace_guard_binding_manifest="$(mktemp)"
cleanup() {
  rm -f "$temporary_manifest" "$namespace_guard_policy_manifest" "$namespace_guard_binding_manifest"
  if [[ -n "$temporary_rendered_manifest" ]]; then
    rm -f "$temporary_rendered_manifest"
  fi
}
trap cleanup EXIT

# The default verifier predicate is SLSA build provenance. Verify the exact OCI
# digest against this repository, signer workflow, hosted runner, and one of the
# two trusted publication refs before invoking kubectl for any operation.
controller_attestation_verified=false
for trusted_source_ref in refs/heads/develop refs/heads/main; do
  if gh attestation verify "oci://$IMAGE_REF" \
    --repo benhook1013/FireMUD \
    --bundle-from-oci \
    --signer-workflow github.com/benhook1013/FireMUD/.github/workflows/runtime-images.yml \
    --source-ref "$trusted_source_ref" \
    --cert-identity "https://github.com/benhook1013/FireMUD/.github/workflows/runtime-images.yml@$trusted_source_ref" \
    --predicate-type https://slsa.dev/provenance/v1 \
    --deny-self-hosted-runners \
    >/dev/null 2>&1; then
    controller_attestation_verified=true
    break
  fi
done
[[ "$controller_attestation_verified" == true ]] || \
  fail "controller image lacks trusted develop/main runtime-images.yml provenance"

replace_manifest() {
  temporary_rendered_manifest="$(mktemp)"
  sed "$@" "$temporary_manifest" >"$temporary_rendered_manifest"
  mv -f "$temporary_rendered_manifest" "$temporary_manifest"
  temporary_rendered_manifest=""
}

extract_named_yaml_document() {
  local source_path="$1"
  local expected_kind="$2"
  local expected_name="$3"
  local destination_path="$4"
  awk -v expected_kind="$expected_kind" -v expected_name="$expected_name" '
    function reset_document() {
      document = ""
      document_kind = ""
      document_name = ""
      in_metadata = 0
    }
    function emit_matching_document() {
      if (document_kind == expected_kind && document_name == expected_name) {
        matches++
        printf "%s", document
      }
    }
    BEGIN { reset_document() }
    /^---[[:space:]]*$/ {
      emit_matching_document()
      reset_document()
      next
    }
    {
      document = document $0 ORS
      if ($0 ~ /^kind:[[:space:]]*/) {
        document_kind = $0
        sub(/^kind:[[:space:]]*/, "", document_kind)
      }
      if ($0 ~ /^metadata:[[:space:]]*$/) {
        in_metadata = 1
      } else if ($0 ~ /^[^[:space:]]/) {
        in_metadata = 0
      } else if (in_metadata && document_name == "" && $0 ~ /^  name:[[:space:]]*/) {
        document_name = $0
        sub(/^  name:[[:space:]]*/, "", document_name)
      }
    }
    END {
      emit_matching_document()
      if (matches != 1) {
        exit 1
      }
    }
  ' "$source_path" > "$destination_path" || \
    fail "expected exactly one $expected_kind/$expected_name in the rendered manifest"
}

# Render privately so the checked-in base cannot silently acquire a mutable
# image tag or an activation mode.  Server-side apply below remains the only
# cluster write path.
kubectl kustomize "$MANIFEST_DIR" >"$temporary_manifest"
replace_manifest \
  -e "s#ghcr.io/benhook1013/firemud-hosted-identity-controller@sha256:__IMAGE_DIGEST_REQUIRED__#$IMAGE_REF#g" \
  -e "s#value: __GRPC_TRUST_ANCHOR_SHA256_REQUIRED__#value: $GRPC_TRUST_ANCHOR_SHA256#g" \
  -e "s#value: __ACTIVATION_MODE_REQUIRED__#value: $initial_activation_mode#g"
grep -Fq -- "$IMAGE_REF" "$temporary_manifest" || fail "immutable image replacement did not occur"
grep -Fq -- "value: $GRPC_TRUST_ANCHOR_SHA256" "$temporary_manifest" || fail "gRPC trust-anchor replacement did not occur"
rendered_activation_mode="$(sed -n '/FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE/{n;s/^[[:space:]]*value: //p;}' "$temporary_manifest")"
[[ "$rendered_activation_mode" == "$initial_activation_mode" ]] || \
  fail "activation mode replacement did not produce exactly one expected value"
if grep -Fq -- "__IMAGE_DIGEST_REQUIRED__" "$temporary_manifest" || \
   grep -Fq -- "__GRPC_TRUST_ANCHOR_SHA256_REQUIRED__" "$temporary_manifest" || \
   grep -Fq -- "__ACTIVATION_MODE_REQUIRED__" "$temporary_manifest"; then
  fail "rendered manifests still contain a required-input marker"
fi

# Install and observe the exact namespace lifecycle admission boundary before
# the full apply can grant
# ClusterRoleBinding/firemud-hosted-identity-controller-namespace-lifecycle.
extract_named_yaml_document "$temporary_manifest" ValidatingAdmissionPolicy \
  firemud-hosted-system-namespace-guard "$namespace_guard_policy_manifest"
extract_named_yaml_document "$temporary_manifest" ValidatingAdmissionPolicyBinding \
  firemud-hosted-system-namespace-guard "$namespace_guard_binding_manifest"
kubectl apply \
  --server-side \
  --field-manager="$FIELD_MANAGER" \
  -f "$namespace_guard_policy_manifest"
kubectl apply \
  --server-side \
  --field-manager="$FIELD_MANAGER" \
  -f "$namespace_guard_binding_manifest"
namespace_guard_failure_policy="$(kubectl get validatingadmissionpolicy \
  firemud-hosted-system-namespace-guard \
  -o jsonpath='{.spec.failurePolicy}{"\n"}')" || \
  fail "namespace guard admission policy lookup failed before namespace lifecycle grant"
[[ "$namespace_guard_failure_policy" == "Fail" ]] || \
  fail "namespace guard admission policy is missing failurePolicy=Fail before namespace lifecycle grant"
namespace_guard_binding_actions="$(kubectl get validatingadmissionpolicybinding \
  firemud-hosted-system-namespace-guard \
  -o jsonpath='{.spec.validationActions[*]}{"\n"}')" || \
  fail "namespace guard admission policy binding lookup failed before namespace lifecycle grant"
[[ "$namespace_guard_binding_actions" == "Deny" ]] || \
  fail "namespace guard admission policy binding must contain exactly validationActions Deny before namespace lifecycle grant"

kubectl apply \
  --server-side \
  --field-manager="$FIELD_MANAGER" \
  -f "$temporary_manifest"

kubectl -n "$CONTROL_NAMESPACE" rollout status \
  "deployment/$DEPLOYMENT_NAME" \
  --timeout="${WAIT_SECONDS}s"
kubectl -n "$CONTROL_NAMESPACE" get deployment "$DEPLOYMENT_NAME" \
  -o jsonpath='{.status.availableReplicas}/{.spec.replicas}{"\n"}'
crd_deadline=$((SECONDS + WAIT_SECONDS))
while :; do
  crd_remaining=$((crd_deadline - SECONDS))
  if ((crd_remaining <= 0)); then
    fail "HostedEnvironmentIdentity CRD is not Established=True"
  fi
  if crd_established="$(kubectl get crd hostedenvironmentidentities.platform.firemud.dev \
    --request-timeout="${crd_remaining}s" \
    -o jsonpath='{.status.conditions[?(@.type=="Established")].status}')" &&
    [[ "$crd_established" == "True" ]]; then
    break
  fi
  if ((SECONDS >= crd_deadline)); then
    fail "HostedEnvironmentIdentity CRD is not Established=True"
  fi
  sleep 1
done

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
  if ! policy_failure_policy="$(kubectl get validatingadmissionpolicy "$admission_name" \
    -o jsonpath='{.spec.failurePolicy}{"\n"}')"; then
    fail "$admission_name admission policy lookup failed; refusing activation"
  fi
  [[ "$policy_failure_policy" == "Fail" ]] || \
    fail "$admission_name admission policy is missing failurePolicy=Fail"
  if ! binding_actions="$(kubectl get validatingadmissionpolicybinding "$admission_name" \
    -o jsonpath='{.spec.validationActions[*]}{"\n"}')"; then
    fail "$admission_name admission policy binding lookup failed; refusing activation"
  fi
  [[ "$binding_actions" == "Deny" ]] || \
    fail "$admission_name admission policy binding must contain exactly validationActions Deny"
done

verify_grpc_ca_prerequisite() {
  command -v base64 >/dev/null 2>&1 || fail "base64 is required to validate the gRPC CA"
  command -v openssl >/dev/null 2>&1 || fail "openssl is required to validate the gRPC CA"
  command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required to validate the gRPC CA"
  local secret_type ca_keys encoded_certificate encoded_key actual_fingerprint
  local certificate_public_key_sha256 private_key_public_key_sha256
  secret_type="$(kubectl -n "$CONTROL_NAMESPACE" get secret firemud-grpc-ca \
    -o jsonpath='{.type}' 2>/dev/null)" || fail "missing trusted firemud-system/firemud-grpc-ca prerequisite"
  [[ "$secret_type" == "Opaque" ]] || fail "firemud-grpc-ca must be an Opaque Secret"
  # shellcheck disable=SC2016 # The dollar-prefixed names are literal kubectl Go-template variables.
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
  if ! printf '%s' "$encoded_certificate" |
    base64 --decode |
    openssl x509 -pubkey -noout 2>/dev/null |
    openssl rsa -pubin -noout >/dev/null 2>&1; then
    fail "firemud-grpc-ca ca.crt public key must be RSA"
  fi
  if ! printf '%s' "$encoded_key" |
    base64 --decode |
    openssl pkcs8 -nocrypt -out /dev/null >/dev/null 2>&1; then
    fail "firemud-grpc-ca ca.key must be an unencrypted PKCS8 private key"
  fi
  if ! printf '%s' "$encoded_key" |
    base64 --decode |
    openssl rsa -check -noout >/dev/null 2>&1; then
    fail "firemud-grpc-ca ca.key must be RSA"
  fi
  certificate_public_key_sha256="$(
    printf '%s' "$encoded_certificate" |
      base64 --decode |
      openssl x509 -pubkey -noout 2>/dev/null |
      openssl pkey -pubin -outform DER 2>/dev/null |
      sha256sum |
      awk '{print $1}'
  )" || fail "firemud-grpc-ca ca.crt public key could not be parsed"
  private_key_public_key_sha256="$(
    printf '%s' "$encoded_key" |
      base64 --decode |
      openssl pkey -pubout -outform DER 2>/dev/null |
      sha256sum |
      awk '{print $1}'
  )" || fail "firemud-grpc-ca ca.key is not a valid private key"
  [[ "$certificate_public_key_sha256" == "$private_key_public_key_sha256" ]] || \
    fail "firemud-grpc-ca ca.crt and ca.key do not match"
}

controller_sa="system:serviceaccount:$CONTROL_NAMESPACE:firemud-hosted-identity-controller"
requester_sa="system:serviceaccount:$CONTROL_NAMESPACE:firemud-hosted-identity-requester"

expect_can_i() {
  local expected="$1"
  shift
  local result status command_args
  printf -v command_args '%q ' "$@"
  if result="$(kubectl auth can-i "$@" | tr -d '\r')"; then
    status=0
  else
    status=$?
  fi
  if [[ "$status" -ne 0 && ! ( "$status" -eq 1 && "$expected" == "no" ) ]]; then
    fail "auth can-i ${command_args}failed with status $status and output: $result"
  fi
  [[ "$result" == "$expected" ]] || fail "auth can-i ${command_args}returned $result; expected $expected"
}

# Positive checks prove the narrow intended calls; negative checks are part of
# bootstrap because a stale broad ClusterRole must stop installation.
expect_can_i yes --as="$controller_sa" --namespace="$CONTROL_NAMESPACE" \
  get hostedenvironmentidentities.platform.firemud.dev
expect_can_i yes --as="$controller_sa" --namespace="$CONTROL_NAMESPACE" \
  patch hostedenvironmentidentities.platform.firemud.dev
expect_can_i yes --as="$controller_sa" --namespace="$CONTROL_NAMESPACE" \
  update hostedenvironmentidentities.platform.firemud.dev/status
expect_can_i no --as="$controller_sa" --namespace="$CONTROL_NAMESPACE" \
  create hostedenvironmentidentities.platform.firemud.dev
expect_can_i no --as="$controller_sa" --namespace="$CONTROL_NAMESPACE" \
  update hostedenvironmentidentities.platform.firemud.dev
expect_can_i no --as="$controller_sa" --namespace="$CONTROL_NAMESPACE" \
  delete hostedenvironmentidentities.platform.firemud.dev
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

if [[ "$ACTIVATION_MODE" == "active" ]]; then
  verify_grpc_ca_prerequisite
  # Re-rendering is unnecessary: the only changed value is the enum-validated
  # activation field. Re-applying the complete private manifest keeps the
  # transition under the same server-side field manager as bootstrap.
  replace_manifest "/FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE/{n;s/value: $initial_activation_mode/value: $ACTIVATION_MODE/;}"
  rendered_activation_mode="$(sed -n '/FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE/{n;s/^[[:space:]]*value: //p;}' "$temporary_manifest")"
  [[ "$rendered_activation_mode" == "$ACTIVATION_MODE" ]] || \
    fail "active activation replacement did not produce exactly one active value"
  kubectl apply \
    --server-side \
    --field-manager="$FIELD_MANAGER" \
    -f "$temporary_manifest"
  kubectl -n "$CONTROL_NAMESPACE" rollout status \
    "deployment/$DEPLOYMENT_NAME" \
    --timeout="${WAIT_SECONDS}s"
fi

echo "hosted identity controller bootstrap applied in $CONTROL_NAMESPACE (activation=$ACTIVATION_MODE)"
