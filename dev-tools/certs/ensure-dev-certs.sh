#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERT_DIR="${1:-$SCRIPT_DIR}"
GENERATOR="$SCRIPT_DIR/generate-dev-certs.sh"
WORKLOAD_NAMESPACE="local"
WORKLOAD_DIR="$CERT_DIR/workloads"
RUNTIME_DIR="$CERT_DIR/local-runtime"

refuse_symlink_path() {
  local path="$1"
  local description="$2"
  local current_path="$path"

  while [[ "$current_path" != "." && "$current_path" != "/" ]]; do
    [[ ! -L "$current_path" ]] || {
      echo "refusing symlinked $description: $path" >&2
      exit 1
    }
    current_path="$(dirname -- "$current_path")"
  done
}

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

refuse_symlink_path "$CERT_DIR" "certificate directory"
for file in "${required_files[@]}"; do
  refuse_symlink_path "$file" "certificate material"
done

refuse_symlink_path "$WORKLOAD_DIR" "workload certificate directory"
for workload in account-service game-session-service social-groups-service; do
  refuse_symlink_path "$WORKLOAD_DIR/$workload.crt" "workload certificate output"
  refuse_symlink_path "$WORKLOAD_DIR/$workload.key" "workload private-key output"
done

refuse_symlink_path "$RUNTIME_DIR" "local Compose runtime projection"
if [[ -d "$WORKLOAD_DIR" ]]; then
  while IFS= read -r -d '' symlink_path; do
    echo "refusing symlinked workload certificate path: $symlink_path" >&2
    exit 1
  done < <(find -P "$WORKLOAD_DIR" -mindepth 1 -maxdepth 1 -type l -print0)
fi
if [[ -d "$RUNTIME_DIR" ]]; then
  while IFS= read -r -d '' symlink_path; do
    echo "refusing symlink in local Compose runtime projection: $symlink_path" >&2
    exit 1
  done < <(find -P "$RUNTIME_DIR" -mindepth 1 -type l -print0)
fi

# Route complete bundles through the generator's read-only validation path as
# well as routing missing bundles through its existing generation path.
"$GENERATOR" "$CERT_DIR"
chmod 600 "$CERT_DIR/ca.key"

workloads=(account-service game-session-service social-groups-service)
mkdir -p "$WORKLOAD_DIR"
chmod 700 "$WORKLOAD_DIR"

workload_certificate_is_valid() {
  local workload="$1"
  local certificate="$WORKLOAD_DIR/$workload.crt"
  local private_key="$WORKLOAD_DIR/$workload.key"
  local expected_uri="spiffe://firemud/ns/$WORKLOAD_NAMESPACE/sa/$workload"
  local subject_alt_names uri_sans certificate_public_key private_key_public_key

  [[ -f "$certificate" && -f "$private_key" ]] || return 1
  openssl verify -purpose sslclient -CAfile "$CERT_DIR/ca.crt" "$certificate" >/dev/null 2>&1 || return 1
  openssl verify -purpose sslserver -CAfile "$CERT_DIR/ca.crt" "$certificate" >/dev/null 2>&1 || return 1
  subject_alt_names="$(openssl x509 -in "$certificate" -noout -ext subjectAltName 2>/dev/null)" || return 1
  uri_sans="$(printf '%s\n' "$subject_alt_names" | grep -oE 'URI:[^,[:space:]]+' || true)"
  [[ "$uri_sans" == "URI:$expected_uri" && "$subject_alt_names" == *"DNS:$workload"* ]] || return 1
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
for workload in "${workloads[@]}"; do
  chmod 644 "$WORKLOAD_DIR/$workload.crt"
  chmod 600 "$WORKLOAD_DIR/$workload.key"
done

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
