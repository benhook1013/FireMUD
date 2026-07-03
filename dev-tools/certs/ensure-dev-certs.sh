#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERT_DIR="${1:-$SCRIPT_DIR}"
GENERATOR="$SCRIPT_DIR/generate-dev-certs.sh"

required_files=(
  "$CERT_DIR/ca.crt"
  "$CERT_DIR/client.crt"
  "$CERT_DIR/client.key"
  "$CERT_DIR/dev-ca.pem"
  "$CERT_DIR/dev-cert.pem"
  "$CERT_DIR/dev-key.pem"
  "$CERT_DIR/server.crt"
  "$CERT_DIR/server.key"
)

missing=0
for file in "${required_files[@]}"; do
  if [[ ! -f "$file" ]]; then
    missing=1
    break
  fi
done

if [[ "$missing" == "0" ]]; then
  echo "Development certificates already exist in $CERT_DIR"
  exit 0
fi

"$GENERATOR" "$CERT_DIR"
