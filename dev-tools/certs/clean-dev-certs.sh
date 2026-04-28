#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Remove generated certificates so tooling can start from scratch without
# deleting the tracked helper scripts in dev-tools/certs/.
TARGET="${1:-}"
if [ -n "$TARGET" ]; then
  CERT_DIR="$TARGET"
else
  CERT_DIR="${CERT_DIR:-$SCRIPT_DIR}"
fi

if [ ! -d "$CERT_DIR" ]; then
  echo "No certificate directory at $CERT_DIR"
  exit 0
fi

generated_files=(
  ca.crt
  ca.key
  ca.srl
  client.crt
  client.key
  dev-ca.pem
  dev-cert.pem
  dev-key.pem
  server.crt
  server.key
  server.csr
  dev-cert.cnf
)

removed_any=false
for filename in "${generated_files[@]}"; do
  if [ -e "$CERT_DIR/$filename" ]; then
    rm -f "$CERT_DIR/$filename"
    removed_any=true
  fi
done

if [ "$removed_any" = true ]; then
  echo "Removed generated certificates from $CERT_DIR"
else
  echo "No generated certificates found in $CERT_DIR"
fi
