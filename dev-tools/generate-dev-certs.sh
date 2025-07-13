#!/usr/bin/env bash
set -euo pipefail

# Generate self-signed CA and client/server certificates for local dev
CERT_DIR="certs"
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

rm "$CERT_DIR"/*.csr "$CERT_DIR"/*.srl

echo "Certificates generated in $CERT_DIR" 
