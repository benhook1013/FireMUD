#!/usr/bin/env bash
set -euo pipefail

# Generate self-signed CA and a shared mTLS certificate for local dev and preview.
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

# Shared mTLS certificate. It is used by both gRPC servers and gRPC clients in
# preview/local environments, so it must be valid for localhost and the in-cluster
# service DNS names clients dial.
cat >"$CERT_DIR/dev-cert.cnf" <<'EOF'
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no

[req_distinguished_name]
CN = firemud-grpc

[v3_req]
subjectAltName = @alt_names
extendedKeyUsage = serverAuth, clientAuth
keyUsage = digitalSignature, keyEncipherment

[alt_names]
DNS.1 = localhost
DNS.2 = account-service
DNS.3 = automation-scripting-service
DNS.4 = entity-management-service
DNS.5 = game-design-service
DNS.6 = game-logic-service
DNS.7 = game-session-service
DNS.8 = logging-admin-service
DNS.9 = social-groups-service
DNS.10 = spring-cloud-gateway
DNS.11 = tcp-proxy-service
DNS.12 = world-management-service
IP.1 = 127.0.0.1
EOF

openssl genrsa -out "$CERT_DIR/server.key" 2048
openssl req -new -key "$CERT_DIR/server.key" -config "$CERT_DIR/dev-cert.cnf" \
  -out "$CERT_DIR/server.csr"
openssl x509 -req -in "$CERT_DIR/server.csr" -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" \
  -CAcreateserial -out "$CERT_DIR/server.crt" -days 365 -sha256 \
  -extensions v3_req -extfile "$CERT_DIR/dev-cert.cnf"

# Keep the historical file names that local scripts and preview secret generation
# already consume. The shared certificate is valid for both client and server use.
cp "$CERT_DIR/server.crt" "$CERT_DIR/client.crt"
cp "$CERT_DIR/server.key" "$CERT_DIR/client.key"

cp "$CERT_DIR/ca.crt" "$CERT_DIR/dev-ca.pem"
cp "$CERT_DIR/client.crt" "$CERT_DIR/dev-cert.pem"
cp "$CERT_DIR/client.key" "$CERT_DIR/dev-key.pem"

rm -f "$CERT_DIR"/*.csr "$CERT_DIR"/*.srl
rm -f "$CERT_DIR/dev-cert.cnf"

# Containers run as a non-root application user in CI and local Docker.
# Make the generated development certificates world-readable so bind mounts
# remain readable inside the container regardless of host UID/GID.
chmod 755 "$CERT_DIR"
chmod 644 "$CERT_DIR"/*.crt "$CERT_DIR"/*.key "$CERT_DIR"/*.pem

echo "Certificates generated in $(cd "$CERT_DIR" && pwd)"
