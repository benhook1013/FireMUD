#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERT_DIR="${1:-$SCRIPT_DIR}"
GENERATOR="$SCRIPT_DIR/generate-dev-certs.sh"
WORKLOAD_NAMESPACE="local"
WORKLOAD_DIR="$CERT_DIR/workloads"
RUNTIME_DIR="$CERT_DIR/local-runtime"

required_files=(
  "$CERT_DIR/ca.crt"
  "$CERT_DIR/ca.key"
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

if [[ "$missing" != "0" ]]; then
  "$GENERATOR" "$CERT_DIR"
fi
chmod 600 "$CERT_DIR/ca.key"

workloads=(account-service game-session-service social-groups-service)
mkdir -p "$WORKLOAD_DIR"
chmod 700 "$WORKLOAD_DIR"

workload_certificate_is_valid() {
  local workload="$1"
  local certificate="$WORKLOAD_DIR/$workload.crt"
  local private_key="$WORKLOAD_DIR/$workload.key"
  local expected_uri="spiffe://firemud/ns/$WORKLOAD_NAMESPACE/sa/$workload"
  local subject_alt_names certificate_public_key private_key_public_key

  [[ -f "$certificate" && -f "$private_key" ]] || return 1
  openssl verify -purpose sslclient -CAfile "$CERT_DIR/ca.crt" "$certificate" >/dev/null 2>&1 || return 1
  openssl verify -purpose sslserver -CAfile "$CERT_DIR/ca.crt" "$certificate" >/dev/null 2>&1 || return 1
  subject_alt_names="$(openssl x509 -in "$certificate" -noout -ext subjectAltName 2>/dev/null)" || return 1
  [[ "$subject_alt_names" == *"URI:$expected_uri"* && "$subject_alt_names" == *"DNS:$workload"* ]] || return 1
  certificate_public_key="$(openssl x509 -in "$certificate" -pubkey -noout \
    | openssl pkey -pubin -outform DER 2>/dev/null | openssl dgst -sha256 2>/dev/null)" || return 1
  private_key_public_key="$(openssl pkey -in "$private_key" -pubout -outform DER 2>/dev/null \
    | openssl dgst -sha256 2>/dev/null)" || return 1
  [[ -n "$certificate_public_key" && "$certificate_public_key" == "$private_key_public_key" ]]
}

for workload in "${workloads[@]}"; do
  certificate="$WORKLOAD_DIR/$workload.crt"
  private_key="$WORKLOAD_DIR/$workload.key"
  if ! workload_certificate_is_valid "$workload"; then
    "$GENERATOR" --workload "$CERT_DIR/ca.crt" "$CERT_DIR/ca.key" \
      "$certificate" "$private_key" "$WORKLOAD_NAMESPACE" "$workload"
  fi
  workload_certificate_is_valid "$workload" || {
    echo "local workload certificate failed identity, key-match, or CA validation: $workload" >&2
    exit 1
  }
done

# Keep host-side workload keys private. Readability is granted only to the
# per-service runtime projection copied below; the generic generator output
# remains mode 0600.
chmod 700 "$WORKLOAD_DIR"
chmod 644 "$WORKLOAD_DIR"/*.crt
chmod 600 "$WORKLOAD_DIR"/*.key

# Mount only runtime material. Protected callers receive their own leaf, not
# the other protected services' private keys. No projection includes ca.key.
[[ ! -L "$RUNTIME_DIR" ]] || {
  echo "refusing symlink as local Compose runtime projection: $RUNTIME_DIR" >&2
  exit 1
}
mkdir -p "$RUNTIME_DIR"
chmod 755 "$RUNTIME_DIR"
runtime_files=(ca.crt client.crt client.key server.crt server.key dev-ca.pem dev-cert.pem dev-key.pem)
runtime_profiles=(default "${workloads[@]}")
while IFS= read -r -d '' existing_path; do
  [[ ! -L "$existing_path" ]] || {
    echo "refusing symlink in local Compose runtime projection: $existing_path" >&2
    exit 1
  }
  profile_name="${existing_path##*/}"
  approved=false
  for profile in "${runtime_profiles[@]}"; do
    if [[ "$profile_name" == "$profile" && -d "$existing_path" ]]; then
      approved=true
      break
    fi
  done
  [[ "$approved" == "true" ]] || {
    echo "refusing unapproved entry in local Compose runtime projection: $profile_name" >&2
    exit 1
  }
done < <(find "$RUNTIME_DIR" -mindepth 1 -maxdepth 1 -print0)

for workload in "${workloads[@]}"; do
  profile_dir="$RUNTIME_DIR/$workload"
  [[ ! -L "$profile_dir" ]] || {
    echo "refusing symlink in local Compose runtime projection: $profile_dir" >&2
    exit 1
  }
  mkdir -p "$profile_dir/workloads"
done

for profile in "${runtime_profiles[@]}"; do
  profile_dir="$RUNTIME_DIR/$profile"
  [[ ! -L "$profile_dir" && ! -L "$profile_dir/workloads" ]] || {
    echo "refusing symlink in local Compose runtime projection: $profile_dir" >&2
    exit 1
  }
  mkdir -p "$profile_dir"
  chmod 755 "$profile_dir"
  expected_profile_files=(ca.crt client.crt client.key server.crt server.key dev-ca.pem dev-cert.pem dev-key.pem)
  if [[ "$profile" != "default" ]]; then
    mkdir -p "$profile_dir/workloads"
    chmod 755 "$profile_dir/workloads"
    expected_profile_files+=(workloads "workloads/$profile.crt" "workloads/$profile.key")
  fi

  while IFS= read -r -d '' existing_path; do
    [[ ! -L "$existing_path" ]] || {
      echo "refusing symlink in local Compose runtime projection: $existing_path" >&2
      exit 1
    }
    relative_path="${existing_path#"$profile_dir"/}"
    approved=false
    for expected_path in "${expected_profile_files[@]}"; do
      if [[ "$relative_path" == "$expected_path" ]]; then
        if [[ "$expected_path" == "workloads" ]]; then
          [[ -d "$existing_path" ]] && approved=true
        else
          [[ -f "$existing_path" ]] && approved=true
        fi
        break
      fi
    done
    [[ "$approved" == "true" ]] || {
      echo "refusing unapproved file in local Compose runtime projection: $profile/$relative_path" >&2
      exit 1
    }
  done < <(find "$profile_dir" -mindepth 1 -print0)

  for file in "${runtime_files[@]}"; do
    [[ -f "$CERT_DIR/$file" ]] || {
      echo "missing runtime certificate material: $CERT_DIR/$file" >&2
      exit 1
    }
    cp "$CERT_DIR/$file" "$profile_dir/$file"
  done
  if [[ "$profile" != "default" ]]; then
    cp "$WORKLOAD_DIR/$profile.crt" "$profile_dir/workloads/$profile.crt"
    cp "$WORKLOAD_DIR/$profile.key" "$profile_dir/workloads/$profile.key"
    chmod 755 "$profile_dir/workloads"
    chmod 644 "$profile_dir/workloads/$profile.crt" "$profile_dir/workloads/$profile.key"
  fi
  chmod 644 "$profile_dir"/*.crt "$profile_dir"/*.key "$profile_dir"/*.pem
done

echo "Development certificates and local Compose workload identities are ready in $CERT_DIR"
