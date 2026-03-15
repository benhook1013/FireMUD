#!/usr/bin/env bash
set -euo pipefail

# Generate self-signed CA and client/server certificates for local dev
TARGET="${1:-}"
if [ -n "$TARGET" ]; then
  CERT_DIR="$TARGET"
else
  CERT_DIR="${CERT_DIR:-certs}"
fi

if [ -f "$CERT_DIR/dev-cert.pem" ] && [ -f "$CERT_DIR/dev-key.pem" ] && [ -f "$CERT_DIR/dev-ca.pem" ]; then
  echo "Dev certificates already exist in $CERT_DIR"
  exit 0
fi

mkdir -p "$CERT_DIR"

# CA
openssl genrsa -out "$CERT_DIR/ca.key" 2048
openssl req -x509 -new -nodes -key "$CERT_DIR/ca.key" -sha256 -days 365 \
  -subj "/CN=FireMUD-CA" -out "$CERT_DIR/ca.crt"

# Server cert
openssl genrsa -out "$CERT_DIR/server.key" 2048
openssl req -new -key "$CERT_DIR/server.key" -subj "/CN=localhost" \
  -out "$CERT_DIR/server.csr"
openssl x509 -req -in "$CERT_DIR/server.csr" -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" \
  -CAcreateserial -out "$CERT_DIR/server.crt" -days 365 -sha256

# Client cert
openssl genrsa -out "$CERT_DIR/client.key" 2048
openssl req -new -key "$CERT_DIR/client.key" -subj "/CN=firemud-client" \
  -out "$CERT_DIR/client.csr"
openssl x509 -req -in "$CERT_DIR/client.csr" -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" \
  -CAcreateserial -out "$CERT_DIR/client.crt" -days 365 -sha256

cp "$CERT_DIR/ca.crt" "$CERT_DIR/dev-ca.pem"
cp "$CERT_DIR/client.crt" "$CERT_DIR/dev-cert.pem"
cp "$CERT_DIR/client.key" "$CERT_DIR/dev-key.pem"

rm -f "$CERT_DIR"/*.csr "$CERT_DIR"/*.srl

# Containers run as a non-root application user in CI and local Docker.
# Make the generated development certificates world-readable so bind mounts
# remain readable inside the container regardless of host UID/GID.
chmod 755 "$CERT_DIR"
chmod 644 "$CERT_DIR"/*.crt "$CERT_DIR"/*.key "$CERT_DIR"/*.pem

echo "Certificates generated in $(cd "$CERT_DIR" && pwd)"
