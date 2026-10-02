#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

refuse_symlink_path() {
  local path="$1"
  local description="$2"
  local current_path="$path"

  while [[ "$current_path" == */ && "$current_path" != "/" ]]; do
    current_path="${current_path%/}"
  done

  while [[ "$current_path" != "." && "$current_path" != "/" ]]; do
    [[ ! -L "$current_path" ]] || {
      echo "refusing symlinked $description: $path" >&2
      exit 1
    }
    current_path="$(dirname -- "$current_path")"
  done
}

preflight_managed_file() {
  local path="$1"
  local description="$2"
  local link_count

  refuse_symlink_path "$path" "$description"
  [[ -e "$path" ]] || return 0
  [[ -f "$path" ]] || {
    echo "refusing non-regular $description: $path" >&2
    exit 1
  }
  link_count="$(stat -c '%h' -- "$path")"
  if ((link_count > 1)); then
    echo "refusing hard-linked $description: $path" >&2
    exit 1
  fi
}

preflight_managed_directory() {
  local path="$1"
  local description="$2"

  refuse_symlink_path "$path" "$description"
  if [[ -e "$path" && ! -d "$path" ]]; then
    echo "refusing non-directory $description: $path" >&2
    exit 1
  fi
}

paths_are_same_file() {
  [[ "$1" == "$2" ]] || { [[ -e "$1" && -e "$2" ]] && [[ "$1" -ef "$2" ]]; }
}

if [[ "${1:-}" == "--workload" ]]; then
  umask 077
  if [[ $# -ne 7 ]]; then
    echo "usage: $0 --workload <ca.crt> <ca.key> <output.crt> <output.key> <runtime-namespace> <workload>" >&2
    exit 1
  fi

  ca_cert="$2"
  ca_key="$3"
  output_cert="$4"
  output_key="$5"
  runtime_namespace="$6"
  workload="$7"
  preflight_managed_file "$ca_cert" "certificate authority source"
  preflight_managed_file "$ca_key" "certificate authority source"
  preflight_managed_file "$output_cert" "workload certificate output"
  preflight_managed_file "$output_key" "workload private-key output"
  if paths_are_same_file "$ca_cert" "$ca_key" \
    || paths_are_same_file "$output_cert" "$output_key" \
    || paths_are_same_file "$output_cert" "$ca_cert" \
    || paths_are_same_file "$output_cert" "$ca_key" \
    || paths_are_same_file "$output_key" "$ca_cert" \
    || paths_are_same_file "$output_key" "$ca_key"; then
    echo "certificate authority sources and workload outputs must be distinct paths." >&2
    exit 1
  fi
  for required_file in "$ca_cert" "$ca_key"; do
    [[ -f "$required_file" ]] || {
      echo "missing certificate authority file: $required_file" >&2
      exit 1
    }
  done
  [[ "$runtime_namespace" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || {
    echo "invalid runtime namespace for workload certificate: $runtime_namespace" >&2
    exit 1
  }
  case "$workload" in
    account-service|game-session-service|social-groups-service|game-design-service|world-management-service|entity-management-service|game-logic-service|automation-scripting-service)
      ;;
    *)
      echo "unsupported gRPC workload identity: $workload" >&2
      exit 1
      ;;
  esac

  mkdir -p "$(dirname "$output_cert")" "$(dirname "$output_key")"
  workload_config="$(mktemp)"
  workload_request="$(mktemp)"
  workload_serial="$(mktemp)"
  trap 'rm -f "$workload_config" "$workload_request" "$workload_serial"' EXIT
  # OpenSSL versions differ on whether an empty -CAserial file can seed a new
  # certificate. Initialize a unique serial explicitly before signing.
  openssl rand -hex 16 >"$workload_serial"
  cat >"$workload_config" <<EOF
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no

[req_distinguished_name]
CN = firemud-grpc-${workload}

[v3_req]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth,clientAuth
subjectAltName = @alt_names

[alt_names]
URI.1 = spiffe://firemud/ns/${runtime_namespace}/sa/${workload}
DNS.1 = ${workload}
DNS.2 = ${workload}.${runtime_namespace}
DNS.3 = ${workload}.${runtime_namespace}.svc
DNS.4 = ${workload}.${runtime_namespace}.svc.cluster.local
EOF
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$output_key" \
    >/dev/null 2>&1
  openssl req -new -key "$output_key" -config "$workload_config" -out "$workload_request"
  openssl x509 -req -in "$workload_request" -CA "$ca_cert" -CAkey "$ca_key" \
    -CAserial "$workload_serial" -out "$output_cert" -days 365 -sha256 \
    -extensions v3_req -extfile "$workload_config" >/dev/null || {
    echo "failed to sign gRPC workload identity certificate: $workload" >&2
    exit 1
  }
  chmod 644 "$output_cert"
  chmod 600 "$output_key"
  exit 0
fi

# Generate self-signed CA and a shared mTLS certificate for local dev and preview.
TARGET="${1:-}"
if [ -n "$TARGET" ]; then
  CERT_DIR="$TARGET"
else
  CERT_DIR="${CERT_DIR:-$SCRIPT_DIR}"
fi

generated_files=(
  ca.crt ca.key ca.srl client.crt client.key dev-ca.pem dev-cert.pem dev-key.pem
  server.crt server.key server.csr dev-cert.cnf
)
preflight_managed_directory "$CERT_DIR" "certificate directory"
for filename in "${generated_files[@]}"; do
  preflight_managed_file "$CERT_DIR/$filename" "certificate material"
done
if [[ -d "$CERT_DIR" ]]; then
  while IFS= read -r -d '' symlink_path; do
    echo "refusing symlinked certificate material: $symlink_path" >&2
    exit 1
  done < <(find -P "$CERT_DIR" -mindepth 1 -maxdepth 1 -type l \( \
    -name '*.crt' -o -name '*.key' -o -name '*.pem' -o -name '*.srl' -o -name '*.csr' \
    -o -name 'dev-cert.cnf' \
  \) -print0)
fi

generic_bundle_files=(
  ca.crt ca.key client.crt client.key dev-ca.pem dev-cert.pem dev-key.pem server.crt server.key
)
local_service_dns_names=(
  localhost account-service automation-scripting-service entity-management-service
  game-design-service game-logic-service game-session-service logging-admin-service
  social-groups-service spring-cloud-gateway tcp-proxy-service world-management-service
)

certificate_matches_private_key() {
  local certificate="$1"
  local private_key="$2"
  local certificate_public_key private_key_public_key

  certificate_public_key="$(openssl x509 -in "$certificate" -pubkey -noout 2>/dev/null \
    | openssl pkey -pubin -outform DER 2>/dev/null | openssl dgst -sha256 2>/dev/null)" || return 1
  private_key_public_key="$(openssl pkey -in "$private_key" -pubout -outform DER 2>/dev/null \
    | openssl dgst -sha256 2>/dev/null)" || return 1
  [[ -n "$certificate_public_key" && "$certificate_public_key" == "$private_key_public_key" ]]
}

validate_existing_leaf() {
  local certificate="$1"
  local private_key="$2"
  local description="$3"
  local purpose subject_alt_names dns_name san_pattern

  if ! openssl x509 -in "$certificate" -noout >/dev/null 2>&1; then
    echo "existing local $description certificate is malformed: $certificate" >&2
    return 1
  fi
  if ! openssl x509 -in "$certificate" -checkend 0 -noout >/dev/null 2>&1; then
    echo "existing local $description certificate is expired or expires now: $certificate" >&2
    return 1
  fi
  for purpose in sslclient sslserver; do
    if ! openssl verify -purpose "$purpose" -CAfile "$CERT_DIR/ca.crt" "$certificate" >/dev/null 2>&1; then
      echo "existing local $description certificate does not verify for $purpose under ca.crt: $certificate" >&2
      return 1
    fi
  done
  if ! certificate_matches_private_key "$certificate" "$private_key"; then
    echo "existing local $description certificate and private key do not match: $certificate / $private_key" >&2
    return 1
  fi
  if ! subject_alt_names="$(openssl x509 -in "$certificate" -noout -ext subjectAltName 2>/dev/null)"; then
    echo "existing local $description certificate has no readable subject alternative names: $certificate" >&2
    return 1
  fi
  for dns_name in "${local_service_dns_names[@]}"; do
    san_pattern="DNS:${dns_name}([,[:space:]]|$)"
    if [[ ! "$subject_alt_names" =~ $san_pattern ]]; then
      echo "existing local $description certificate is missing the expected DNS SAN $dns_name: $certificate" >&2
      return 1
    fi
  done
  if [[ "$subject_alt_names" != *"IP Address:127.0.0.1"* ]]; then
    echo "existing local $description certificate is missing the expected loopback IP SAN: $certificate" >&2
    return 1
  fi
}

validate_existing_generic_bundle() {
  local ca_cert="$CERT_DIR/ca.crt"
  local ca_key="$CERT_DIR/ca.key"

  if ! openssl x509 -in "$ca_cert" -noout >/dev/null 2>&1; then
    echo "existing local certificate authority certificate is malformed: $ca_cert" >&2
    return 1
  fi
  if ! openssl x509 -in "$ca_cert" -checkend 0 -noout >/dev/null 2>&1; then
    echo "existing local certificate authority certificate is expired or expires now: $ca_cert" >&2
    return 1
  fi
  if ! openssl verify -check_ss_sig -CAfile "$ca_cert" "$ca_cert" >/dev/null 2>&1; then
    echo "existing local certificate authority is not a valid trusted self-signed certificate: $ca_cert" >&2
    return 1
  fi
  if ! certificate_matches_private_key "$ca_cert" "$ca_key"; then
    echo "existing local certificate authority certificate and private key do not match: $ca_cert / $ca_key" >&2
    return 1
  fi

  if ! validate_existing_leaf "$CERT_DIR/server.crt" "$CERT_DIR/server.key" "server"; then
    return 1
  fi
  if ! validate_existing_leaf "$CERT_DIR/client.crt" "$CERT_DIR/client.key" "client"; then
    return 1
  fi

  if ! cmp -s "$CERT_DIR/ca.crt" "$CERT_DIR/dev-ca.pem"; then
    echo "existing local legacy CA alias does not match ca.crt: $CERT_DIR/dev-ca.pem" >&2
    return 1
  fi
  if ! cmp -s "$CERT_DIR/client.crt" "$CERT_DIR/dev-cert.pem"; then
    echo "existing local legacy certificate alias does not match client.crt: $CERT_DIR/dev-cert.pem" >&2
    return 1
  fi
  if ! cmp -s "$CERT_DIR/client.key" "$CERT_DIR/dev-key.pem"; then
    echo "existing local legacy private-key alias does not match client.key: $CERT_DIR/dev-key.pem" >&2
    return 1
  fi
}

bundle_present=true
for filename in "${generic_bundle_files[@]}"; do
  if [[ ! -f "$CERT_DIR/$filename" ]]; then
    bundle_present=false
    break
  fi
done

if [[ "$bundle_present" == "true" ]]; then
  if ! validate_existing_generic_bundle; then
    echo "Existing local certificate bundle is invalid in $CERT_DIR; no files were changed and the CA or issued leaves were not rotated." >&2
    echo "Back up this local certificate set, then deliberately reissue it with:" >&2
    printf '  %q %q\n' "$SCRIPT_DIR/clean-dev-certs.sh" "$CERT_DIR" >&2
    printf '  %q %q\n' "$SCRIPT_DIR/generate-dev-certs.sh" "$CERT_DIR" >&2
    exit 1
  fi
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
# Keep the CA private key host-only. Runtime containers use the CA certificate
# and leaf keys from the local-runtime projection prepared by ensure-dev-certs.
# The shared client/server keys remain readable for non-root local containers.
chmod 755 "$CERT_DIR"
chmod 644 "$CERT_DIR"/*.crt "$CERT_DIR"/client.key "$CERT_DIR"/server.key "$CERT_DIR"/*.pem
chmod 600 "$CERT_DIR/ca.key"

echo "Certificates generated in $(cd "$CERT_DIR" && pwd)"
