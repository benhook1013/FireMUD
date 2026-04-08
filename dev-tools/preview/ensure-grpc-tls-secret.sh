#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <namespace>" >&2
  exit 1
fi

namespace="$1"
secret_name="${PREVIEW_GRPC_TLS_SECRET_NAME:-firemud-grpc-tls}"
cert_dir="${PREVIEW_GRPC_TLS_CERT_DIR:-}"

if [[ -z "$cert_dir" ]]; then
  cert_dir="$(mktemp -d)"
  trap 'rm -rf "$cert_dir"' EXIT
fi

if [[ -x "dev-tools/generate-dev-certs.sh" ]]; then
  mkdir -p "$cert_dir"
  rm -f "${cert_dir}/ca.crt" "${cert_dir}/client.crt" "${cert_dir}/client.key" \
    "${cert_dir}/server.crt" "${cert_dir}/server.key" "${cert_dir}/dev-ca.pem" \
    "${cert_dir}/dev-cert.pem" "${cert_dir}/dev-key.pem"
  dev-tools/generate-dev-certs.sh "$cert_dir"
fi

for required_file in ca.crt client.crt client.key; do
  if [[ ! -f "${cert_dir}/${required_file}" ]]; then
    echo "missing required TLS file: ${cert_dir}/${required_file}" >&2
    exit 1
  fi
done

kubectl -n "$namespace" create secret generic "$secret_name" \
  --from-file=ca.crt="${cert_dir}/ca.crt" \
  --from-file=client.crt="${cert_dir}/client.crt" \
  --from-file=client.key="${cert_dir}/client.key" \
  --dry-run=client \
  -o yaml | kubectl apply -f -
