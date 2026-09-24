#!/usr/bin/env bash
set -euo pipefail

readonly DEFAULT_CERTIFICATE_WAIT_TIMEOUT_SECONDS=900
readonly INTERNAL_ISSUER='firemud-ca-issuer'

certificate_wait_timeout_seconds="${CERTIFICATE_WAIT_TIMEOUT_SECONDS:-$DEFAULT_CERTIFICATE_WAIT_TIMEOUT_SECONDS}"
if [[ ! "$certificate_wait_timeout_seconds" =~ ^[1-9][0-9]{0,3}$ ]] ||
  ((10#$certificate_wait_timeout_seconds > 3600)); then
  echo "CERTIFICATE_WAIT_TIMEOUT_SECONDS must be an integer between 1 and 3600" >&2
  exit 2
fi

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <dev|pr-N-namespace>" >&2
  exit 2
fi

namespace="$1"
if [[ "$namespace" != dev && ! "$namespace" =~ ^pr-[1-9][0-9]{0,50}$ ]]; then
  echo "runtime namespace must be dev or canonical pr-N: $namespace" >&2
  exit 2
fi

workloads=(
  game-design-service
  world-management-service
  entity-management-service
  game-logic-service
  automation-scripting-service
)

write_certificates() {
  local workload certificate dns_name
  for workload in "${workloads[@]}"; do
    certificate="${namespace}-grpc-${workload}"
    dns_name="$workload"
    cat <<EOF
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: ${certificate}
  namespace: ${namespace}
spec:
  secretName: firemud-grpc-${workload}
  privateKey:
    algorithm: RSA
    size: 2048
    encoding: PKCS8
    rotationPolicy: Always
  isCA: false
  revisionHistoryLimit: 1
  dnsNames:
    - ${dns_name}
    - ${dns_name}.${namespace}
    - ${dns_name}.${namespace}.svc
    - ${dns_name}.${namespace}.svc.cluster.local
  uris:
    - spiffe://firemud/ns/${namespace}/sa/${workload}
  usages:
    - digital signature
    - key encipherment
    - server auth
    - client auth
  encodeUsagesInRequest: true
  issuerRef:
    name: ${INTERNAL_ISSUER}
    kind: ClusterIssuer
    group: cert-manager.io
---
EOF
  done
}

write_certificates | kubectl --namespace "$namespace" apply -f - >/dev/null

deadline=$((SECONDS + certificate_wait_timeout_seconds))
for workload in "${workloads[@]}"; do
  certificate="${namespace}-grpc-${workload}"
  remaining=$((deadline - SECONDS))
  if ((remaining <= 0)); then
    echo "Certificate/${certificate} did not become Ready within ${certificate_wait_timeout_seconds}s; refusing standalone gRPC activation." >&2
    exit 1
  fi
  if ! kubectl --namespace "$namespace" wait \
    --for=condition=Ready "certificate/${certificate}" \
    --timeout="${remaining}s"; then
    echo "Certificate/${certificate} did not become Ready; refusing standalone gRPC activation." >&2
    exit 1
  fi
done

printf 'namespace=%s\ncertificates=ready\n' "$namespace"
