#!/usr/bin/env bash
set -euo pipefail

readonly DEFAULT_CERTIFICATE_WAIT_TIMEOUT_SECONDS=900
readonly PREVIEW_DOMAIN='preview.firedevops.net'
readonly PUBLIC_ISSUER='letsencrypt-prod'
readonly INTERNAL_ISSUER='firemud-ca-issuer'

# The default operation is restricted to Certificate writes and readiness.
# --wait is the separate runtime-credential operation for Secret readback.
certificate_wait_timeout_seconds="${CERTIFICATE_WAIT_TIMEOUT_SECONDS:-$DEFAULT_CERTIFICATE_WAIT_TIMEOUT_SECONDS}"
if [[ ! "$certificate_wait_timeout_seconds" =~ ^[1-9][0-9]{0,3}$ ]] ||
  ((10#$certificate_wait_timeout_seconds > 3600)); then
  echo "CERTIFICATE_WAIT_TIMEOUT_SECONDS must be an integer between 1 and 3600" >&2
  exit 2
fi

transport_operation='write'
if [[ $# -eq 1 && "$1" != --wait ]]; then
  namespace="$1"
elif [[ $# -eq 2 && "$1" == --wait ]]; then
  transport_operation='wait'
  namespace="$2"
else
  echo "usage: $0 [--wait] <pr-N-namespace>" >&2
  exit 2
fi

if [[ ! "$namespace" =~ ^pr-[1-9][0-9]{0,50}$ ]]; then
  echo "runtime namespace must be canonical pr-N: ${namespace}" >&2
  exit 2
fi

telnet_certificate="${namespace}-telnet-tls"
gateway_certificate="${namespace}-gateway-internal-ws"
bridge_certificate="${namespace}-tcp-proxy-bridge"
public_hostname="${namespace}.${PREVIEW_DOMAIN}"
gateway_dns_name="spring-cloud-gateway-mtls.${namespace}.svc.cluster.local"
bridge_uri_san="spiffe://firemud/ns/${namespace}/sa/tcp-proxy-service"
deadline=$((SECONDS + certificate_wait_timeout_seconds))

write_certificates() {
  cat <<EOF | kubectl --namespace "$namespace" apply -f -
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: ${telnet_certificate}
  namespace: ${namespace}
spec:
  secretName: ${telnet_certificate}
  privateKey:
    algorithm: RSA
    size: 2048
    encoding: PKCS8
    rotationPolicy: Always
  isCA: false
  revisionHistoryLimit: 1
  dnsNames:
    - ${public_hostname}
  usages:
    - digital signature
    - key encipherment
    - server auth
  encodeUsagesInRequest: true
  issuerRef:
    name: ${PUBLIC_ISSUER}
    kind: ClusterIssuer
    group: cert-manager.io
---
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: ${gateway_certificate}
  namespace: ${namespace}
spec:
  secretName: ${gateway_certificate}
  privateKey:
    algorithm: RSA
    size: 2048
    encoding: PKCS8
    rotationPolicy: Always
  isCA: false
  revisionHistoryLimit: 1
  dnsNames:
    - ${gateway_dns_name}
  usages:
    - digital signature
    - key encipherment
    - server auth
  encodeUsagesInRequest: true
  issuerRef:
    name: ${INTERNAL_ISSUER}
    kind: ClusterIssuer
    group: cert-manager.io
---
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: ${bridge_certificate}
  namespace: ${namespace}
spec:
  secretName: ${bridge_certificate}
  privateKey:
    algorithm: RSA
    size: 2048
    encoding: PKCS8
    rotationPolicy: Always
  isCA: false
  revisionHistoryLimit: 1
  uris:
    - ${bridge_uri_san}
  usages:
    - digital signature
    - key encipherment
    - client auth
  encodeUsagesInRequest: true
  issuerRef:
    name: ${INTERNAL_ISSUER}
    kind: ClusterIssuer
    group: cert-manager.io
EOF
}

wait_for_certificates() {
  local certificate remaining
  for certificate in "$telnet_certificate" "$gateway_certificate" "$bridge_certificate"; do
    remaining=$((deadline - SECONDS))
    if ((remaining <= 0)); then
      echo "Certificate/${certificate} did not become Ready within ${certificate_wait_timeout_seconds}s; refusing standalone transport activation." >&2
      return 1
    fi
    if ! kubectl --namespace "$namespace" wait \
      --for=condition=Ready "certificate/${certificate}" \
      --timeout="${remaining}s"; then
      echo "Certificate/${certificate} did not become Ready; refusing standalone transport activation." >&2
      return 1
    fi
  done
}

secret_has_required_keys() {
  local secret_json="$1"
  local secret_name="$2"
  local required_keys="$3"

  jq -e \
    --arg secret_name "$secret_name" \
    --arg required_keys "$required_keys" \
    '
      (.metadata | type == "object") and
      .metadata.name == $secret_name and
      (.data | type == "object") and
      (. as $secret |
        ($required_keys | split(",")) as $keys |
        all($keys[]; $secret.data[.] | type == "string" and length > 0))
    ' <<<"$secret_json" >/dev/null
}

wait_for_secret_projection() {
  local secret_name="$1"
  local required_keys="$2"
  local secret_json

  while ((SECONDS < deadline)); do
    if ! secret_json="$(kubectl --namespace "$namespace" get secret "$secret_name" \
      --ignore-not-found -o json)"; then
      echo "Unable to read Secret/${secret_name}; refusing standalone transport activation." >&2
      return 1
    fi
    if [[ -n "$secret_json" ]] && secret_has_required_keys "$secret_json" "$secret_name" "$required_keys"; then
      return 0
    fi
    sleep 5
  done

  echo "Secret/${secret_name} did not become key-complete within ${certificate_wait_timeout_seconds}s; refusing standalone transport activation." >&2
  return 1
}

if [[ "$transport_operation" == write ]]; then
  write_certificates
  wait_for_certificates
  printf 'namespace=%s\ncertificates=ready\n' "$namespace"
else
  wait_for_secret_projection "$telnet_certificate" 'tls.crt,tls.key'
  wait_for_secret_projection "$gateway_certificate" 'tls.crt,tls.key,ca.crt'
  wait_for_secret_projection "$bridge_certificate" 'tls.crt,tls.key,ca.crt'
  printf 'namespace=%s\nsecrets=key-complete\n' "$namespace"
fi
