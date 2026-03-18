#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <namespace>" >&2
  exit 1
fi

namespace="$1"
secret_name="${PREVIEW_GRPC_TLS_SECRET_NAME:-firemud-grpc-tls}"
cert_dir="${PREVIEW_GRPC_TLS_CERT_DIR:-dev-tools/certs}"

if [[ ! -f "${cert_dir}/ca.crt" || ! -f "${cert_dir}/client.crt" || ! -f "${cert_dir}/client.key" ]]; then
  if [[ -x "dev-tools/generate-dev-certs.sh" ]]; then
    mkdir -p "$cert_dir"
    dev-tools/generate-dev-certs.sh "$cert_dir"
  fi
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
