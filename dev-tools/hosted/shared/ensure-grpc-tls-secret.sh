#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <namespace>" >&2
  exit 1
fi

namespace="$1"
umask 077
[[ "$namespace" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || {
  echo "invalid runtime namespace: $namespace" >&2
  exit 1
}

for required_command in kubectl openssl base64; do
  command -v "$required_command" >/dev/null 2>&1 || {
    echo "missing required command: $required_command" >&2
    exit 1
  }
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
legacy_generator="$script_dir/../../certs/generate-dev-certs.sh"
[[ -x "$legacy_generator" ]] || {
  echo "missing development certificate generator: $legacy_generator" >&2
  exit 1
}

shared_secret="${PREVIEW_GRPC_TLS_SECRET_NAME:-firemud-grpc-tls}"
ca_secret="firemud-grpc-ca"
cert_dir="${PREVIEW_GRPC_TLS_CERT_DIR:-}"
temporary_cert_dir=false
if [[ -z "$cert_dir" ]]; then
  cert_dir="$(mktemp -d)"
  temporary_cert_dir=true
fi
mkdir -p "$cert_dir"
if [[ "$temporary_cert_dir" == true ]]; then
  trap 'rm -rf "$cert_dir"' EXIT
fi

workloads=(
  game-design-service
  world-management-service
  entity-management-service
  game-logic-service
  automation-scripting-service
)

secret_exists() {
  kubectl -n "$namespace" get secret "$1" >/dev/null 2>&1
}

read_secret_file() {
  local secret_name="$1"
  local key="$2"
  local output="$3"
  local encoded

  encoded="$(kubectl -n "$namespace" get secret "$secret_name" -o "jsonpath={.data['${key}']}")" || return 1
  [[ -n "$encoded" ]] || return 1
  printf '%s' "$encoded" | base64 --decode >"$output" || return 1
  [[ -s "$output" ]]
}

apply_secret() {
  local secret_name="$1"
  shift
  kubectl -n "$namespace" create secret generic "$secret_name" "$@" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
}

assert_key_matches_certificate() {
  local certificate="$1"
  local key="$2"
  local certificate_public_key
  local key_public_key

  certificate_public_key="$(openssl x509 -in "$certificate" -pubkey -noout | openssl sha256)"
  key_public_key="$(openssl pkey -in "$key" -pubout | openssl sha256)"
  [[ "$certificate_public_key" == "$key_public_key" ]] || {
    echo "certificate and private key do not match: $certificate" >&2
    return 1
  }
}

ca_bundle_contains() {
  local bundle="$1"
  local certificate="$2"
  openssl verify -CAfile "$bundle" "$certificate" >/dev/null 2>&1
}

validate_workload_certificate() {
  local certificate="$1"
  local key="$2"
  local workload="$3"
  local expected_uri="spiffe://firemud/ns/${namespace}/sa/${workload}"
  local san_values
  local expected_dns

  openssl x509 -in "$certificate" -noout >/dev/null
  assert_key_matches_certificate "$certificate" "$key"
  san_values="$(openssl x509 -in "$certificate" -noout -ext subjectAltName | awk 'NR > 1 { print }' | tr ',' '\n' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
  [[ "$(printf '%s\n' "$san_values" | sed '/^$/d' | wc -l)" -eq 5 ]] || {
    echo "workload certificate must contain exactly one URI SAN and four DNS SANs: $certificate" >&2
    return 1
  }
  printf '%s\n' "$san_values" | grep -Fx "URI:${expected_uri}" >/dev/null || {
    echo "workload certificate has the wrong URI SAN: $certificate" >&2
    return 1
  }
  for expected_dns in \
    "$workload" \
    "${workload}.${namespace}" \
    "${workload}.${namespace}.svc" \
    "${workload}.${namespace}.svc.cluster.local"; do
    printf '%s\n' "$san_values" | grep -Fx "DNS:${expected_dns}" >/dev/null || {
      echo "workload certificate is missing DNS SAN ${expected_dns}: $certificate" >&2
      return 1
    }
  done
}

shared_ca="$cert_dir/shared-ca.crt"
shared_cert="$cert_dir/shared-client.crt"
shared_key="$cert_dir/shared-client.key"
source_ca="$cert_dir/source-ca.crt"
source_key="$cert_dir/source-ca.key"

if ! secret_exists "$shared_secret"; then
  if secret_exists "$ca_secret"; then
    echo "shared gRPC TLS Secret is missing while standalone source material exists; refusing fresh replacement" >&2
    exit 1
  fi
  for workload in "${workloads[@]}"; do
    if secret_exists "firemud-grpc-${workload}" || secret_exists "${namespace}-grpc-${workload}"; then
      echo "shared gRPC TLS Secret is missing while a publication Secret exists; refusing fresh replacement" >&2
      exit 1
    fi
  done
fi

if secret_exists "$shared_secret"; then
  read_secret_file "$shared_secret" ca.crt "$shared_ca" || {
    echo "existing gRPC TLS Secret lacks a usable ca.crt: $shared_secret" >&2
    exit 1
  }
  read_secret_file "$shared_secret" client.crt "$shared_cert" || {
    echo "existing gRPC TLS Secret lacks a usable client.crt: $shared_secret" >&2
    exit 1
  }
  read_secret_file "$shared_secret" client.key "$shared_key" || {
    echo "existing gRPC TLS Secret lacks a usable client.key: $shared_secret" >&2
    exit 1
  }
  openssl x509 -in "$shared_cert" -noout >/dev/null
  assert_key_matches_certificate "$shared_cert" "$shared_key"
else
  # The legacy shared bundle is still required by the six non-publication
  # workloads. Generate it once, then preserve its leaf on every later run.
  "$legacy_generator" "$cert_dir"
  for required_file in ca.crt client.crt client.key ca.key; do
    [[ -s "$cert_dir/$required_file" ]] || {
      echo "shared certificate bootstrap did not produce $required_file" >&2
      exit 1
    }
  done
  cp "$cert_dir/ca.crt" "$shared_ca"
  cp "$cert_dir/client.crt" "$shared_cert"
  cp "$cert_dir/client.key" "$shared_key"
  apply_secret "$shared_secret" \
    --from-file=ca.crt="$shared_ca" \
    --from-file=client.crt="$shared_cert" \
    --from-file=client.key="$shared_key"
fi

existing_publication_secret=false
for workload in "${workloads[@]}"; do
  if secret_exists "firemud-grpc-${workload}" || secret_exists "${namespace}-grpc-${workload}"; then
    existing_publication_secret=true
  fi
done

if secret_exists "$ca_secret"; then
  read_secret_file "$ca_secret" ca.crt "$source_ca" || {
    echo "existing standalone gRPC CA Secret lacks a usable ca.crt" >&2
    exit 1
  }
  read_secret_file "$ca_secret" ca.key "$source_key" || {
    echo "existing standalone gRPC CA Secret lacks its private key; refusing to mint replacement material" >&2
    exit 1
  }
else
  if [[ "$existing_publication_secret" == true ]]; then
    echo "standalone gRPC CA Secret is missing while a publication Secret exists; refusing certificate replacement" >&2
    exit 1
  fi

  # Keep the legacy shared leaf and CA intact. A separate, stable CA signs the
  # five distinct publication leaves; the CA key is retained only in this
  # unmounted source Secret so it can be reused for future missing projections.
  openssl genrsa -out "$source_key" 2048 >/dev/null 2>&1
  openssl req -x509 -new -nodes -key "$source_key" -sha256 -days 365 \
    -subj "/CN=FireMUD-Standalone-gRPC-CA" \
    -addext "basicConstraints=critical,CA:true,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -out "$source_ca"
  chmod 644 "$source_ca"
  chmod 600 "$source_key"
  apply_secret "$ca_secret" \
    --from-file=ca.crt="$source_ca" \
    --from-file=ca.key="$source_key"
fi

openssl x509 -in "$source_ca" -noout >/dev/null
openssl pkey -in "$source_key" -noout >/dev/null
assert_key_matches_certificate "$source_ca" "$source_key" 2>/dev/null || {
  echo "standalone gRPC CA certificate and private key do not match" >&2
  exit 1
}

# Add the standalone CA to the existing shared trust bundle without replacing
# the shared six-workload leaf. This is a one-time migration and is a no-op on
# every subsequent deployment.
if ! ca_bundle_contains "$shared_ca" "$source_ca"; then
  cat "$shared_ca" "$source_ca" >"$cert_dir/shared-ca-updated.crt"
  mv "$cert_dir/shared-ca-updated.crt" "$shared_ca"
  apply_secret "$shared_secret" \
    --from-file=ca.crt="$shared_ca" \
    --from-file=client.crt="$shared_cert" \
    --from-file=client.key="$shared_key"
fi

declare -A fingerprints=()
shared_fingerprint="$(openssl x509 -in "$shared_cert" -outform der | openssl sha256)"

for workload in "${workloads[@]}"; do
  secret_name="firemud-grpc-${workload}"
  source_name="${namespace}-grpc-${workload}"
  workload_cert="$cert_dir/${workload}.crt"
  workload_key="$cert_dir/${workload}.key"

  if secret_exists "$source_name"; then
    read_secret_file "$source_name" tls.crt "$workload_cert" || {
      echo "existing publication source Secret lacks a usable tls.crt: $source_name" >&2
      exit 1
    }
    read_secret_file "$source_name" tls.key "$workload_key" || {
      echo "existing publication source Secret lacks a usable tls.key: $source_name" >&2
      exit 1
    }
  elif secret_exists "$secret_name"; then
    # Recover the stable source name from an older standalone run without
    # minting a new leaf. The runtime projection remains disposable.
    read_secret_file "$secret_name" tls.crt "$workload_cert" || {
      echo "existing publication Secret lacks a usable tls.crt: $secret_name" >&2
      exit 1
    }
    read_secret_file "$secret_name" tls.key "$workload_key" || {
      echo "existing publication Secret lacks a usable tls.key: $secret_name" >&2
      exit 1
    }
  else
    "$legacy_generator" --workload "$source_ca" "$source_key" "$workload_cert" "$workload_key" "$namespace" "$workload"
  fi

  validate_workload_certificate "$workload_cert" "$workload_key" "$workload"
  openssl verify -CAfile "$source_ca" "$workload_cert" >/dev/null
  fingerprint="$(openssl x509 -in "$workload_cert" -outform der | openssl sha256)"
  [[ "$fingerprint" != "$shared_fingerprint" ]] || {
    echo "publication workload certificate must not reuse the shared gRPC leaf: $secret_name" >&2
    exit 1
  }
  [[ -z "${fingerprints[$fingerprint]+present}" ]] || {
    echo "publication workload certificates must have distinct leaf identities: $secret_name" >&2
    exit 1
  }
  fingerprints["$fingerprint"]="$workload"

  if ! secret_exists "$source_name"; then
    apply_secret "$source_name" \
      --from-file=ca.crt="$shared_ca" \
      --from-file=tls.crt="$workload_cert" \
      --from-file=tls.key="$workload_key"
  fi

  apply_secret "$secret_name" \
    --from-file=ca.crt="$shared_ca" \
    --from-file=tls.crt="$workload_cert" \
    --from-file=tls.key="$workload_key"
done

echo "Stable standalone gRPC TLS material is ready in namespace ${namespace}: shared leaf plus ${#workloads[@]} distinct publication leaves"
