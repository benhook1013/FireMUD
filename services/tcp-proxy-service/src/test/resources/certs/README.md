# Rotated Gateway Client Test Certificates

> [!WARNING]
> Every key and certificate in this directory is a test-only fixture. Never mount or use them as deployed credentials; production requires separately issued credentials.

These fixtures exercise Gateway WebSocket client-certificate rotation independently of the ordinary development certificates:

- `rotated-gateway-client-ca.crt` is the fixture CA certificate.
- `rotated-gateway-client.crt` is the client leaf signed by that CA.
- `rotated-gateway-client.key` is the matching leaf private key.

The checked-in CA and leaf expire at `2036-09-12 15:22:19 UTC`. Regenerate the complete set together before the more-than-30-day validity test begins failing at `2036-08-13 15:22:19 UTC`. The generic `dev-tools/certs/generate-dev-certs.sh` workflow does not own these rotated fixture filenames.

From the repository root, use OpenSSL to create a new ten-year fixture set:

Run all shell code blocks in this regeneration procedure in the same interactive shell so that `work_dir` and the `EXIT` trap remain available across blocks.

```bash
fixture_dir=services/tcp-proxy-service/src/test/resources/certs
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

openssl genrsa -out "$work_dir/ca.key" 2048
openssl genrsa -out "$work_dir/rotated-gateway-client.key" 2048
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
CN = firemud-gateway-client

[v3_req]
subjectAltName = @alt_names
extendedKeyUsage = clientAuth
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
DNS.9 = spring-cloud-gateway
DNS.10 = tcp-proxy-service
DNS.11 = world-management-service
IP.1 = 127.0.0.1
```

Generate the CA, request, and signed leaf:

```bash
openssl req -x509 -new -nodes -key "$work_dir/ca.key" -sha256 -days 3650 \
  -config "$work_dir/ca.cnf" \
  -out "$work_dir/rotated-gateway-client-ca.crt"
openssl req -new -key "$work_dir/rotated-gateway-client.key" \
  -config "$work_dir/client.cnf" \
  -out "$work_dir/rotated-gateway-client.csr"
openssl x509 -req -in "$work_dir/rotated-gateway-client.csr" \
  -CA "$work_dir/rotated-gateway-client-ca.crt" \
  -CAkey "$work_dir/ca.key" \
  -CAserial "$work_dir/rotated-gateway-client-ca.srl" -CAcreateserial \
  -out "$work_dir/rotated-gateway-client.crt" -days 3650 -sha256 \
  -extensions v3_req -extfile "$work_dir/client.cnf"
```

Verify the issuer relationship, key match, validity window, SANs, and EKUs before installing the regenerated fixtures:

```bash
openssl verify \
  -CAfile "$work_dir/rotated-gateway-client-ca.crt" \
  "$work_dir/rotated-gateway-client.crt"
openssl x509 -in "$work_dir/rotated-gateway-client.crt" -pubkey -noout \
  | openssl pkey -pubin -outform der | openssl sha256
openssl pkey -in "$work_dir/rotated-gateway-client.key" -pubout -outform der \
  | openssl sha256
openssl x509 -in "$work_dir/rotated-gateway-client-ca.crt" \
  -noout -subject -issuer -dates
openssl x509 -in "$work_dir/rotated-gateway-client.crt" \
  -noout -subject -issuer -dates -ext subjectAltName
openssl x509 -in "$work_dir/rotated-gateway-client.crt" \
  -noout -ext extendedKeyUsage
```

The two public-key SHA-256 values must match, `openssl verify` must report `OK`, the CA must be self-issued as `CN = FireMUD-CA`, and the leaf issuer must be that same CA. Update both the recorded expiry date and the recorded 30-day validity threshold date above whenever the fixtures are regenerated.

Only after every verification passes, install the staged fixtures with their final filenames and modes:

```bash
install -m 644 "$work_dir/rotated-gateway-client-ca.crt" \
  "$fixture_dir/rotated-gateway-client-ca.crt"
install -m 644 "$work_dir/rotated-gateway-client.crt" \
  "$fixture_dir/rotated-gateway-client.crt"
install -m 600 "$work_dir/rotated-gateway-client.key" \
  "$fixture_dir/rotated-gateway-client.key"
```
