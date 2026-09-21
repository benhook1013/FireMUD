#!/usr/bin/env bash
set -euo pipefail

# Operator-only, pre-CA proof. Every Certificate, CertificateRequest, issuer,
# and Secret probe is server-side dry-run; this helper never creates a CA,
# issuer, certificate, or Secret.
usage() {
  cat >&2 <<'EOF'
usage: prove-issuance-boundary.sh --context CONTEXT --namespace pr-N
The context is explicit and the caller must be in system:masters. The namespace
must already exist as a canonical pr-N runtime namespace.
EOF
}

context=''
namespace=''
while (($#)); do
  case "$1" in
    --context)
      (($# >= 2)) || { usage; exit 2; }
      context=$2
      shift 2
      ;;
    --namespace)
      (($# >= 2)) || { usage; exit 2; }
      namespace=$2
      shift 2
      ;;
    --help|-h)
      usage >&1
      exit 0
      ;;
    *)
      usage
      exit 2
      ;;
  esac
done
[[ -n "$context" && -n "$namespace" ]] || { usage; exit 2; }
[[ "$context" != -* && "$namespace" != -* ]] || { usage; exit 2; }
[[ "$namespace" =~ ^pr-[1-9][0-9]{0,50}$ ]] || {
  echo 'refusing: --namespace must be an existing canonical pr-N namespace' >&2
  exit 2
}

readonly context namespace
readonly legacy_identity='system:serviceaccount:kube-system:preview-deployer'
readonly cert_manager_identity='system:serviceaccount:cert-manager:cert-manager'
readonly standalone_writer_identity='system:serviceaccount:firemud-system:firemud-standalone-certificate-writer'
probe_run_id="$(date -u +%s%N | tail -c 11)"
readonly probe_run_id
readonly probe_name="firemud-trust-proof-${probe_run_id}"
readonly probe_clusterrole="$probe_name"
readonly probe_binding="$probe_name"
readonly probe_serviceaccount="$probe_name"
readonly KUBECTL=(kubectl --context "$context")
temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/firemud-trust-proof.XXXXXX")"
readonly temporary_directory
resources_created=0

cleanup() {
  local status=$?
  local cleanup_failed=0
  trap - EXIT
  if ((resources_created)); then
    # Names were checked absent before creation. Cleanup is exact-name only.
    "${KUBECTL[@]}" delete clusterrolebinding "$probe_binding" --ignore-not-found --wait=false >/dev/null 2>&1 || cleanup_failed=1
    "${KUBECTL[@]}" delete clusterrole "$probe_clusterrole" --ignore-not-found --wait=false >/dev/null 2>&1 || cleanup_failed=1
    "${KUBECTL[@]}" -n "$namespace" delete serviceaccount "$probe_serviceaccount" --ignore-not-found --wait=false >/dev/null 2>&1 || cleanup_failed=1
  fi
  rm -rf -- "$temporary_directory"
  if ((cleanup_failed)); then
    echo 'issuance-boundary proof failed: exact temporary RBAC cleanup was incomplete' >&2
    ((status == 0)) && status=1
  fi
  exit "$status"
}
trap cleanup EXIT

fail() {
  echo "issuance-boundary proof failed: $1" >&2
  exit 1
}

run_quiet() {
  "$@" >/dev/null 2>"$temporary_directory/command.stderr"
}

auth_can() {
  local verb=$1
  local resource=$2
  local identity=$3
  local target_namespace=$4
  local result
  if [[ -n "$target_namespace" ]]; then
    result=$("${KUBECTL[@]}" auth can-i "$verb" "$resource" -n "$target_namespace" --as="$identity" 2>/dev/null || true)
  else
    result=$("${KUBECTL[@]}" auth can-i "$verb" "$resource" --as="$identity" 2>/dev/null || true)
  fi
  [[ "$result" == 'yes' ]]
}

expect_auth_denied() {
  local description=$1 verb=$2 resource=$3 identity=$4 target_namespace=$5
  if auth_can "$verb" "$resource" "$identity" "$target_namespace"; then
    fail "$description: legacy identity still has $verb $resource permission"
  fi
  printf 'PASS pre-CA RBAC denial: %s caller=%s response=denied\n' "$description" "$identity"
}

expect_dry_run_denied() {
  local description=$1 identity=$2 manifest=$3 target_namespace=$4 expected_message=$5
  local -a command=("${KUBECTL[@]}" create --as="$identity" --dry-run=server -o name -f -)
  [[ -n "$target_namespace" ]] && command+=(-n "$target_namespace")
  if printf '%s\n' "$manifest" | "${command[@]}" >/dev/null 2>"$temporary_directory/probe-error"; then
    fail "$description: admission unexpectedly allowed the probe"
  fi
  if ! grep -Fq -- "$expected_message" "$temporary_directory/probe-error"; then
    fail "$description: request failed without the expected fail-closed policy response"
  fi
  printf 'PASS pre-CA admission denial: %s caller=%s response=denied\n' "$description" "$identity"
}

expect_dry_run_allowed() {
  local description=$1 identity=$2 manifest=$3 target_namespace=$4
  local -a command=("${KUBECTL[@]}" create --as="$identity" --dry-run=server -o name -f -)
  [[ -n "$target_namespace" ]] && command+=(-n "$target_namespace")
  if ! printf '%s\n' "$manifest" | "${command[@]}" >/dev/null 2>"$temporary_directory/probe-error"; then
    fail "$description: canonical probe was not admitted"
  fi
  printf 'PASS pre-CA admission allow: %s caller=%s response=admitted\n' "$description" "$identity"
}

echo "issuance-boundary proof context=$context namespace=$namespace"
run_quiet "${KUBECTL[@]}" get namespace "$namespace" || fail "namespace $namespace is not present"
whoami_json=$("${KUBECTL[@]}" auth whoami -o json 2>/dev/null) || fail 'could not read selected context identity'
if ! python3 -c '
import json, sys
identity = json.load(sys.stdin).get("status", {}).get("userInfo", {})
if "system:masters" not in identity.get("groups", []):
    raise SystemExit(1)
' <<<"$whoami_json"; then
  fail 'selected context caller is not a system:masters operator'
fi
echo 'PASS selected context identity: caller is system:masters (identity details withheld)'

# These checks are run only after the legacy binding and credential are revoked.
expect_auth_denied 'legacy preview deployer Secret read' get secrets "$legacy_identity" "$namespace"
expect_auth_denied 'legacy preview deployer firemud-system Secret read' get secrets "$legacy_identity" firemud-system
expect_auth_denied 'legacy preview deployer cert-manager Secret read' get secrets "$legacy_identity" cert-manager
expect_auth_denied 'legacy preview deployer CertificateRequest creation' create certificaterequests.cert-manager.io "$legacy_identity" "$namespace"
expect_auth_denied 'legacy preview deployer issuer mutation' update issuers.cert-manager.io "$legacy_identity" "$namespace"
expect_auth_denied 'legacy preview deployer ClusterIssuer mutation' create clusterissuers.cert-manager.io "$legacy_identity" ''

# This intentionally overprivileged principal is disposable. It proves that
# VAP, rather than RBAC, supplies the deny boundary. No token is minted.
if "${KUBECTL[@]}" -n "$namespace" get serviceaccount "$probe_serviceaccount" --ignore-not-found -o name 2>/dev/null | grep -q .; then
  fail "temporary proof name already exists: $probe_serviceaccount"
fi
if "${KUBECTL[@]}" get clusterrole "$probe_clusterrole" --ignore-not-found -o name 2>/dev/null | grep -q . ||
   "${KUBECTL[@]}" get clusterrolebinding "$probe_binding" --ignore-not-found -o name 2>/dev/null | grep -q .; then
  fail "temporary proof name already exists: $probe_name"
fi

resources_created=1
cat <<EOF | "${KUBECTL[@]}" apply --server-side --field-manager=firemud-trust-boundary-proof -f - >/dev/null 2>"$temporary_directory/apply-error" || fail 'could not create disposable proof RBAC'
apiVersion: v1
kind: ServiceAccount
metadata:
  name: $probe_serviceaccount
  namespace: $namespace
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: $probe_clusterrole
rules:
  - apiGroups: ["cert-manager.io"]
    resources: ["certificates", "certificaterequests", "issuers"]
    verbs: ["create", "update", "patch", "delete"]
  - apiGroups: ["cert-manager.io"]
    resources: ["clusterissuers"]
    verbs: ["create", "update", "patch", "delete"]
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["create", "update", "patch", "delete"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: $probe_binding
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: $probe_clusterrole
subjects:
  - kind: ServiceAccount
    name: $probe_serviceaccount
    namespace: $namespace
EOF
probe_identity="system:serviceaccount:$namespace:$probe_serviceaccount"
for permission in \
  'create certificaterequests.cert-manager.io' \
  'create certificates.cert-manager.io' \
  'create issuers.cert-manager.io' \
  'create clusterissuers.cert-manager.io' \
  'create secrets'; do
  read -r verb resource <<<"$permission"
  auth_can "$verb" "$resource" "$probe_identity" "$namespace" ||
    fail "disposable probe lacks expected permission: $permission"
done
echo "PASS disposable overprivileged probe RBAC: caller=$probe_identity response=permissions-present"

openssl req -new -newkey rsa:2048 -nodes \
  -subj "/CN=firemud-trust-boundary-proof" \
  -keyout "$temporary_directory/probe.key" \
  -out "$temporary_directory/probe.csr" >/dev/null 2>&1 ||
  fail 'could not create disposable non-CA CSR'
csr_b64=$(base64 -w0 "$temporary_directory/probe.csr")

request_manifest() {
  local name=$1 issuer_name=$2 issuer_kind=$3 issuer_group=$4
  local issuer_kind_line issuer_group_line
  issuer_kind_line="    kind: $issuer_kind"
  issuer_group_line="    group: $issuer_group"
  [[ "$issuer_kind" == OMIT ]] && issuer_kind_line=''
  [[ "$issuer_group" == OMIT ]] && issuer_group_line=''
  cat <<EOF
apiVersion: cert-manager.io/v1
kind: CertificateRequest
metadata:
  name: $name
  namespace: $namespace
spec:
  request: $csr_b64
  issuerRef:
    name: $issuer_name
$issuer_kind_line
$issuer_group_line
  usages:
    - digital signature
    - server auth
EOF
}

expect_dry_run_denied 'direct CertificateRequest with canonical issuer but no Certificate owner' \
  "$probe_identity" "$(request_manifest firemud-proof-request firemud-ca-issuer ClusterIssuer cert-manager.io)" "$namespace" \
  'only cert-manager or a trusted system:masters bootstrap may handle internal CertificateRequests'
expect_dry_run_denied 'direct CertificateRequest with issuerRef group omitted' \
  "$probe_identity" "$(request_manifest firemud-proof-request-no-group firemud-ca-issuer ClusterIssuer OMIT)" "$namespace" \
  'only cert-manager or a trusted system:masters bootstrap may handle internal CertificateRequests'
expect_dry_run_denied 'direct CertificateRequest with issuerRef kind omitted' \
  "$probe_identity" "$(request_manifest firemud-proof-request-no-kind firemud-ca-issuer OMIT cert-manager.io)" "$namespace" \
  'only cert-manager or a trusted system:masters bootstrap may handle internal CertificateRequests'

expect_dry_run_denied 'CA-backed Issuer alias' "$probe_identity" "$(cat <<EOF
apiVersion: cert-manager.io/v1
kind: Issuer
metadata:
  name: firemud-proof-alias
  namespace: $namespace
spec:
  ca:
    secretName: firemud-grpc-ca
EOF
)" "$namespace" 'only trusted system:masters may create, mutate, or delete the internal CA issuer or a CA-backed alias'
expect_dry_run_denied 'CA-backed ClusterIssuer alias' "$probe_identity" "$(cat <<'EOF'
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: firemud-proof-cluster-alias
spec:
  ca:
    secretName: firemud-grpc-ca
EOF
)" '' 'only trusted system:masters may create, mutate, or delete the internal CA issuer or a CA-backed alias'
expect_dry_run_denied 'reserved firemud-system CA Secret mutation' "$probe_identity" "$(cat <<'EOF'
apiVersion: v1
kind: Secret
metadata:
  name: firemud-grpc-ca
  namespace: firemud-system
type: Opaque
data:
  ca.crt: cHJvb2Y=
  ca.key: cHJvb2Y=
EOF
)" firemud-system 'only the protected recovery identity or system:masters may write the fixed CA Secret'

certificate_manifest() {
  local name=$1 dns=$2 issuer_name=$3 issuer_kind=$4 issuer_group=$5 mode=$6
  if [[ "$mode" == server ]]; then
    cat <<EOF
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: $name
  namespace: $namespace
spec:
  secretName: $name
  isCA: false
  revisionHistoryLimit: 1
  privateKey:
    algorithm: RSA
    size: 2048
    encoding: PKCS8
    rotationPolicy: Always
  encodeUsagesInRequest: true
  dnsNames:
    - $dns
  issuerRef:
    name: $issuer_name
    kind: $issuer_kind
    group: $issuer_group
  usages:
    - digital signature
    - key encipherment
    - server auth
EOF
  else
    cat <<EOF
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: $name
  namespace: $namespace
spec:
  secretName: $name
  isCA: false
  revisionHistoryLimit: 1
  privateKey:
    algorithm: RSA
    size: 2048
    encoding: PKCS8
    rotationPolicy: Always
  encodeUsagesInRequest: true
  uris:
    - spiffe://firemud/ns/$namespace/sa/tcp-proxy-service
  issuerRef:
    name: $issuer_name
    kind: $issuer_kind
    group: $issuer_group
  usages:
    - digital signature
    - key encipherment
    - client auth
EOF
  fi
}

gateway_name="$namespace-gateway-internal-ws"
proxy_name="$namespace-tcp-proxy-bridge"
telnet_name="$namespace-telnet-tls"
gateway_dns="spring-cloud-gateway-mtls.$namespace.svc.cluster.local"
telnet_dns="$namespace.preview.firedevops.net"
canonical_telnet="$(certificate_manifest "$telnet_name" "$telnet_dns" letsencrypt-prod ClusterIssuer cert-manager.io server)"
canonical_gateway="$(certificate_manifest "$gateway_name" "$gateway_dns" firemud-ca-issuer ClusterIssuer cert-manager.io server)"
canonical_proxy="$(certificate_manifest "$proxy_name" '' firemud-ca-issuer ClusterIssuer cert-manager.io client)"
expect_dry_run_allowed 'canonical standalone public Telnet Certificate' "$standalone_writer_identity" "$canonical_telnet" "$namespace"
expect_dry_run_allowed 'canonical standalone Gateway Certificate' "$standalone_writer_identity" "$canonical_gateway" "$namespace"
expect_dry_run_allowed 'canonical standalone TCP Proxy Certificate' "$standalone_writer_identity" "$canonical_proxy" "$namespace"
expect_dry_run_denied 'standalone writer arbitrary public Certificate' "$standalone_writer_identity" \
  "$(certificate_manifest "$namespace-arbitrary" "$telnet_dns" letsencrypt-prod ClusterIssuer cert-manager.io server)" "$namespace" \
  'FireMUD Certificates are limited to the exact standalone transport specs or canonical retained identity controller'
expect_dry_run_denied 'standalone Certificate with wrong Gateway SAN' "$standalone_writer_identity" \
  "$(certificate_manifest "$gateway_name" "wrong.$namespace.svc.cluster.local" firemud-ca-issuer ClusterIssuer cert-manager.io server)" "$namespace" \
  'FireMUD Certificates are limited to the exact standalone transport specs or canonical retained identity controller'
expect_dry_run_denied 'standalone Certificate with wrong canonical name' "$standalone_writer_identity" \
  "$(certificate_manifest "$namespace-not-canonical" "$gateway_dns" firemud-ca-issuer ClusterIssuer cert-manager.io server)" "$namespace" \
  'FireMUD Certificates are limited to the exact standalone transport specs or canonical retained identity controller'
expect_dry_run_denied 'standalone Certificate with wrong issuer kind' "$standalone_writer_identity" \
  "$(certificate_manifest "$gateway_name" "$gateway_dns" firemud-ca-issuer Issuer cert-manager.io server)" "$namespace" \
  'FireMUD Certificates are limited to the exact standalone transport specs or canonical retained identity controller'

run_quiet "${KUBECTL[@]}" -n cert-manager get serviceaccount cert-manager ||
  fail 'cert-manager ServiceAccount is missing'
deployment_identity=$("${KUBECTL[@]}" -n cert-manager get deployment cert-manager -o jsonpath='{.spec.template.spec.serviceAccountName}' 2>/dev/null) ||
  fail 'cert-manager Deployment identity could not be read'
[[ "$deployment_identity" == cert-manager ]] ||
  fail 'cert-manager Deployment uses an unexpected ServiceAccount'
auth_can get certificaterequests.cert-manager.io "$cert_manager_identity" "$namespace" ||
  fail 'cert-manager identity cannot read CertificateRequests in the proof namespace'
echo 'PASS cert-manager identity/config readback: serviceAccount=cert-manager config=matched'

echo 'PASS pre-CA issuance boundary: unauthorized direct requests, aliases, and CA Secret mutation denied'
echo 'PASS pre-CA standalone path: canonical Gateway/TCP Proxy Certificate shapes admitted'
echo 'DEFERRED later Ready/signing proof: no CA, issuer, controller, or Certificate was created by this helper'
