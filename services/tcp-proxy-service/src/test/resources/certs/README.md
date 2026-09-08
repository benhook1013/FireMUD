# Rotated Gateway Client Test Certificates

These fixtures exercise Gateway WebSocket client-certificate rotation independently of the ordinary development certificates:

- `rotated-gateway-client-ca.crt` is the fixture CA certificate.
- `rotated-gateway-client.crt` is the client leaf signed by that CA.
- `rotated-gateway-client.key` is the matching leaf private key.

The checked-in CA and leaf expire at `2027-09-06 13:32:58 UTC`. Regenerate the complete set together before that date. The generic `dev-tools/certs/generate-dev-certs.sh` workflow does not own these rotated fixture filenames.

From the repository root, use OpenSSL to create a new one-year fixture set:

Run all shell code blocks in this regeneration procedure in the same interactive shell so that `work_dir` and the `EXIT` trap remain available across blocks.

```bash
fixture_dir=services/tcp-proxy-service/src/test/resources/certs
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

openssl genrsa -out "$work_dir/ca.key" 2048
openssl genrsa -out "$fixture_dir/rotated-gateway-client.key" 2048
```

Create `$work_dir/ca.cnf` with this exact content:

```ini
[req]
distinguished_name = distinguished_name
x509_extensions = v3_ca
prompt = no

[distinguished_name]
CN = FireMUD-CA

[v3_ca]
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always,issuer
basicConstraints = critical,CA:true
```

Create `$work_dir/client.cnf` with this exact content:

```ini
[req]
distinguished_name = distinguished_name
req_extensions = v3_req
prompt = no

[distinguished_name]
CN = firemud-gateway-websocket-client

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
```

Generate the CA, request, and signed leaf:

```bash
openssl req -x509 -new -nodes -key "$work_dir/ca.key" -sha256 -days 365 \
  -config "$work_dir/ca.cnf" \
  -out "$fixture_dir/rotated-gateway-client-ca.crt"
openssl req -new -key "$fixture_dir/rotated-gateway-client.key" \
  -config "$work_dir/client.cnf" \
  -out "$work_dir/rotated-gateway-client.csr"
openssl x509 -req -in "$work_dir/rotated-gateway-client.csr" \
  -CA "$fixture_dir/rotated-gateway-client-ca.crt" \
  -CAkey "$work_dir/ca.key" -CAcreateserial \
  -out "$fixture_dir/rotated-gateway-client.crt" -days 365 -sha256 \
  -extensions v3_req -extfile "$work_dir/client.cnf"
rm -f "$fixture_dir/rotated-gateway-client-ca.srl"
chmod 644 \
  "$fixture_dir/rotated-gateway-client-ca.crt" \
  "$fixture_dir/rotated-gateway-client.crt" \
  "$fixture_dir/rotated-gateway-client.key"
```

Verify the issuer relationship, key match, validity window, SANs, and EKUs before committing the regenerated fixtures:

```bash
openssl verify \
  -CAfile "$fixture_dir/rotated-gateway-client-ca.crt" \
  "$fixture_dir/rotated-gateway-client.crt"
openssl x509 -in "$fixture_dir/rotated-gateway-client.crt" -pubkey -noout \
  | openssl pkey -pubin -outform der | openssl sha256
openssl pkey -in "$fixture_dir/rotated-gateway-client.key" -pubout -outform der \
  | openssl sha256
openssl x509 -in "$fixture_dir/rotated-gateway-client-ca.crt" \
  -noout -subject -issuer -dates
openssl x509 -in "$fixture_dir/rotated-gateway-client.crt" \
  -noout -subject -issuer -dates -ext subjectAltName
openssl x509 -in "$fixture_dir/rotated-gateway-client.crt" \
  -noout -ext extendedKeyUsage
```

The two public-key SHA-256 values must match, `openssl verify` must report `OK`, the CA must be self-issued as `CN = FireMUD-CA`, and the leaf issuer must be that same CA. Update the expiry date recorded above whenever the fixtures are regenerated.
