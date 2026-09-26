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
elif [[ $# -eq 2 && "$1" == --migrator ]]; then
  transport_operation='write-migrator'
  namespace="$2"
elif [[ $# -eq 2 && "$1" == --verify-migrator ]]; then
  transport_operation='verify-migrator'
  namespace="$2"
else
  echo "usage: $0 [--wait|--migrator|--verify-migrator] <runtime-namespace>" >&2
  exit 2
fi

if [[ "$transport_operation" == write || "$transport_operation" == wait ]] &&
  [[ ! "$namespace" =~ ^pr-[1-9][0-9]{0,50}$ ]]; then
  echo "runtime namespace must be canonical pr-N: ${namespace}" >&2
  exit 2
fi

if [[ ! "$namespace" =~ ^(dev|pr-[1-9][0-9]{0,50})$ ]]; then
  echo "runtime namespace must be dev or canonical pr-N: ${namespace}" >&2
  exit 2
fi

telnet_certificate="${namespace}-telnet-tls"
gateway_certificate="${namespace}-gateway-internal-ws"
bridge_certificate="${namespace}-tcp-proxy-bridge"
public_hostname="${namespace}.${PREVIEW_DOMAIN}"
gateway_dns_name="spring-cloud-gateway-mtls.${namespace}.svc.cluster.local"
bridge_uri_san="spiffe://firemud/ns/${namespace}/sa/tcp-proxy-service"
migrator_certificate="${namespace}-grpc-game-design-baseline-migrator"
migrator_secret='firemud-grpc-game-design-baseline-migrator'
migrator_uri_san="spiffe://firemud/ns/${namespace}/sa/game-design-baseline-migrator"
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

write_migrator_certificate() {
  cat <<EOF | kubectl --namespace "$namespace" apply -f -
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: ${migrator_certificate}
  namespace: ${namespace}
spec:
  secretName: ${migrator_secret}
  secretTemplate:
    metadata:
      labels:
        firemud.dev/managed-by: entity-baseline-migration
        firemud.dev/role: grpc-game-design-baseline-migrator
        firemud.dev/retention: ephemeral
      annotations:
        firemud.dev/provenance: cert-manager
  privateKey:
    algorithm: RSA
    size: 2048
    encoding: PKCS8
    rotationPolicy: Always
  isCA: false
  revisionHistoryLimit: 1
  uris:
    - ${migrator_uri_san}
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
  if ! kubectl --namespace "$namespace" wait \
    --for=condition=Ready "certificate/${migrator_certificate}" \
    --timeout="${certificate_wait_timeout_seconds}s"; then
    echo "Certificate/${migrator_certificate} did not become Ready; refusing migrator credential handoff." >&2
    return 1
  fi
  printf 'namespace=%s\ncertificate=%s\nready=true\n' "$namespace" "$migrator_certificate"
}

verify_migrator_secret() {
  local secret_json trusted_ca_data temp_dir san_output eku_output
  if ! kubectl --namespace "$namespace" wait \
    --for=condition=Ready "certificate/${migrator_certificate}" \
    --timeout="${certificate_wait_timeout_seconds}s"; then
    echo "Certificate/${migrator_certificate} is not Ready; refusing migrator identity verification." >&2
    return 1
  fi
  if ! secret_json="$(kubectl --namespace "$namespace" get secret "$migrator_secret" -o json)"; then
    echo "Unable to read Secret/${migrator_secret}; refusing migrator identity verification." >&2
    return 1
  fi
  if ! trusted_ca_data="$(kubectl --namespace "$namespace" get secret firemud-grpc-tls \
    -o 'jsonpath={.data.ca\.crt}')" || [[ -z "$trusted_ca_data" ]]; then
    echo "Unable to read the canonical firemud-grpc-tls CA projection; refusing migrator identity verification." >&2
    return 1
  fi
  if ! jq -e --arg name "$migrator_secret" '
    .metadata.name == $name and
    .type == "kubernetes.io/tls" and
    .data["tls.crt"] != null and .data["tls.crt"] != "" and
    .data["tls.key"] != null and .data["tls.key"] != "" and
    .data["ca.crt"] != null and .data["ca.crt"] != ""
  ' <<<"$secret_json" >/dev/null; then
    echo "Secret/${migrator_secret} is not a complete dedicated TLS projection." >&2
    return 1
  fi

  temp_dir="$(mktemp -d)"
  chmod 700 "$temp_dir"
  trap 'rm -rf -- "$temp_dir"' RETURN
  jq -r '.data["tls.crt"]' <<<"$secret_json" | base64 --decode >"$temp_dir/tls.crt"
  jq -r '.data["ca.crt"]' <<<"$secret_json" | base64 --decode >"$temp_dir/ca.crt"
  base64 --decode <<<"$trusted_ca_data" >"$temp_dir/trusted-ca.crt"
  if ! openssl verify -CAfile "$temp_dir/ca.crt" "$temp_dir/tls.crt" >/dev/null; then
    echo "Secret/${migrator_secret} leaf does not verify against its projected CA." >&2
    return 1
  fi
  if ! openssl verify -CAfile "$temp_dir/trusted-ca.crt" -partial_chain \
    "$temp_dir/ca.crt" >/dev/null ||
    ! openssl verify -CAfile "$temp_dir/trusted-ca.crt" -untrusted "$temp_dir/ca.crt" \
      "$temp_dir/tls.crt" >/dev/null; then
    echo "Secret/${migrator_secret} CA chain is not trusted by the namespace firemud-grpc-tls bundle." >&2
    return 1
  fi
  san_output="$(openssl x509 -in "$temp_dir/tls.crt" -noout -ext subjectAltName 2>/dev/null | tail -n +2 | xargs)"
  if [[ "$san_output" != "URI:${migrator_uri_san}" ]]; then
    echo "Secret/${migrator_secret} certificate does not contain only the exact migrator SPIFFE URI SAN." >&2
    return 1
  fi
  eku_output="$(openssl x509 -in "$temp_dir/tls.crt" -noout -ext extendedKeyUsage 2>/dev/null | tail -n +2 | xargs)"
  if [[ "$eku_output" != 'TLS Web Client Authentication' ]]; then
    echo "Secret/${migrator_secret} certificate is not clientAuth-only." >&2
    return 1
  fi
  printf 'namespace=%s\nsecret=%s\nleaf-ca-match=true\nnamespace-trust-match=true\nuri-san=%s\nclient-auth-only=true\n' \
    "$namespace" "$migrator_secret" "$migrator_uri_san"
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

if [[ "$transport_operation" == write-migrator ]]; then
  write_migrator_certificate
elif [[ "$transport_operation" == verify-migrator ]]; then
  verify_migrator_secret
elif [[ "$transport_operation" == write ]]; then
  write_certificates
  wait_for_certificates
  printf 'namespace=%s\ncertificates=ready\n' "$namespace"
else
  wait_for_secret_projection "$telnet_certificate" 'tls.crt,tls.key'
  wait_for_secret_projection "$gateway_certificate" 'tls.crt,tls.key,ca.crt'
  wait_for_secret_projection "$bridge_certificate" 'tls.crt,tls.key,ca.crt'
  printf 'namespace=%s\nsecrets=key-complete\n' "$namespace"
fi
