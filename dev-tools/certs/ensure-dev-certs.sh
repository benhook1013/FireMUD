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
COMPOSE_MTLS_STAGE_ROOT=""
COMPOSE_MTLS_STAGE_DIR=""

cleanup_compose_mtls_stage() {
  if [[ -n "$COMPOSE_MTLS_STAGE_DIR" && "$COMPOSE_MTLS_STAGE_DIR" == "$COMPOSE_MTLS_STAGE_ROOT"/.workloads.* && -d "$COMPOSE_MTLS_STAGE_DIR" && ! -L "$COMPOSE_MTLS_STAGE_DIR" ]]; then
    rm -rf -- "$COMPOSE_MTLS_STAGE_DIR"
  fi
}

ensure_compose_mtls_certs() {
  local requested_root="$1"
  local ownership_helper="$SCRIPT_DIR/../smoke/run-owned-compose.sh"
  # The smoke wrappers claim/require the project before invoking this mode.
  # Re-derive the run-owned location here so an arbitrary host path cannot be
  # mounted into a Compose application container.
  # shellcheck disable=SC1090,SC1091 # The ownership helper is resolved relative to this script at runtime.
  source "$ownership_helper"
  _firemud_smoke_prepare

  local expected_root="$FIREMUD_SMOKE_OWNERSHIP_DIR_RESOLVED/$FIREMUD_SMOKE_PROJECT_KEY.grpc-mtls"
  if [[ "$requested_root" != "$expected_root" || "$requested_root" != /* ]]; then
    echo "Compose mTLS certificates must use this run-owned project's fixture root." >&2
    return 1
  fi

  local owner_id authority_dir workloads_dir service
  owner_id="$(id -u)"
  if [[ ! -d "$FIREMUD_SMOKE_OWNERSHIP_DIR_RESOLVED" || -L "$FIREMUD_SMOKE_OWNERSHIP_DIR_RESOLVED" ]]; then
    echo "run-owned smoke ownership directory is unavailable." >&2
    return 1
  fi
  if [[ ! -e "$requested_root" ]]; then
    mkdir -m 700 -- "$requested_root"
  fi
  if [[ -L "$requested_root" || ! -d "$requested_root" || "$(stat -Lc '%u %a %F' "$requested_root")" != "$owner_id 700 directory" ]]; then
    echo "Compose mTLS fixture root must be an owner-only (0700) directory owned by this user." >&2
    return 1
  fi

  authority_dir="$requested_root/authority"
  if [[ ! -e "$authority_dir" ]]; then
    mkdir -m 700 -- "$authority_dir"
  fi
  if [[ -L "$authority_dir" || ! -d "$authority_dir" ]]; then
    echo "Compose mTLS authority path must be a real directory." >&2
    return 1
  fi
  chmod 700 "$authority_dir"
  if [[ "$(stat -Lc '%u %a %F' "$authority_dir")" != "$owner_id 700 directory" ]]; then
    echo "Compose mTLS authority fixture must be an owner-only (0700) directory." >&2
    return 1
  fi
  if [[ ! -f "$authority_dir/ca.crt" || ! -f "$authority_dir/ca.key" || ! -f "$authority_dir/client.crt" || ! -f "$authority_dir/client.key" ]]; then
    if [[ -n "$(find "$authority_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
      echo "Compose mTLS authority fixture is incomplete; refusing to replace existing material." >&2
      return 1
    fi
    (umask 077; "$GENERATOR" "$authority_dir")
  fi
  local authority_file
  for authority_file in ca.crt ca.key client.crt client.key; do
    if [[ -L "$authority_dir/$authority_file" || ! -f "$authority_dir/$authority_file" ]]; then
      echo "Compose mTLS authority fixture is missing a regular $authority_file file." >&2
      return 1
    fi
  done
  chmod 600 "$authority_dir/ca.key" "$authority_dir/client.key"
  chmod 644 "$authority_dir/ca.crt" "$authority_dir/client.crt"
  openssl verify -CAfile "$authority_dir/ca.crt" "$authority_dir/client.crt" >/dev/null

  workloads_dir="$requested_root/workloads"
  local -a services=(
    account-service gateway automation-scripting-service entity-management-service
    game-design-service game-logic-service game-session-service logging-admin-service
    social-groups-service tcp-proxy-service world-management-service
  )
  local validate_workloads=0
  if [[ -e "$workloads_dir" || -L "$workloads_dir" ]]; then
    validate_workloads=1
    if [[ -L "$workloads_dir" || ! -d "$workloads_dir" ]]; then
      echo "Compose mTLS workloads path must be a real directory." >&2
      return 1
    fi
  else
    COMPOSE_MTLS_STAGE_ROOT="$requested_root"
    COMPOSE_MTLS_STAGE_DIR="$(mktemp -d "$requested_root/.workloads.XXXXXX")"
    chmod 700 "$COMPOSE_MTLS_STAGE_DIR"
    for service in "${services[@]}"; do
      local service_dir="$COMPOSE_MTLS_STAGE_DIR/$service"
      mkdir -m 755 -- "$service_dir"
      if [[ "$service" == account-service \
        || "$service" == game-session-service \
        || "$service" == entity-management-service \
        || "$service" == social-groups-service ]]; then
        "$GENERATOR" --workload \
          "$authority_dir/ca.crt" "$authority_dir/ca.key" \
          "$service_dir/client.crt" "$service_dir/client.key" dev "$service"
      else
        cp -- "$authority_dir/client.crt" "$service_dir/client.crt"
        cp -- "$authority_dir/client.key" "$service_dir/client.key"
      fi
      cp -- "$authority_dir/ca.crt" "$service_dir/ca.crt"
      chmod 444 "$service_dir/client.crt" "$service_dir/client.key" "$service_dir/ca.crt"
    done
    chmod 755 "$COMPOSE_MTLS_STAGE_DIR"
    mv -- "$COMPOSE_MTLS_STAGE_DIR" "$workloads_dir"
    COMPOSE_MTLS_STAGE_DIR=""
    validate_workloads=1
  fi

  if ((validate_workloads)); then
    if [[ -L "$workloads_dir" ]]; then
      echo "Compose mTLS workloads path must be a real directory." >&2
      return 1
    fi
    if [[ "$(stat -Lc '%u %a %F' "$workloads_dir")" != "$owner_id 755 directory" ]]; then
      echo "Compose mTLS workload fixture must be an owner-owned (0755) directory." >&2
      return 1
    fi
    for service in "${services[@]}"; do
      local service_dir="$workloads_dir/$service"
      if [[ -L "$service_dir" || ! -d "$service_dir" || "$(stat -Lc '%u %a %F' "$service_dir")" != "$owner_id 755 directory" ]]; then
        echo "Compose mTLS workload directory is missing or has unsafe permissions: $service" >&2
        return 1
      fi
      local expected_count=0 file
      for file in "$service_dir"/*; do
        [[ -e "$file" || -L "$file" ]] || continue
        expected_count=$((expected_count + 1))
        if [[ -L "$file" || ! -f "$file" || "$(stat -Lc '%u %a %F' "$file")" != "$owner_id 444 regular file" ]]; then
          echo "Compose mTLS workload files must be regular, read-only, container-readable files: $service" >&2
          return 1
        fi
      done
      if ((expected_count != 3)) || [[ ! -f "$service_dir/client.crt" || ! -f "$service_dir/client.key" || ! -f "$service_dir/ca.crt" ]]; then
        echo "Compose mTLS workload directory must contain only its leaf certificate, private key, and CA certificate: $service" >&2
        return 1
      fi
      openssl verify -CAfile "$service_dir/ca.crt" "$service_dir/client.crt" >/dev/null
    done
    local found_service_count=0 entry
    for entry in "$workloads_dir"/*; do
      [[ -e "$entry" || -L "$entry" ]] || continue
      if [[ -L "$entry" || ! -d "$entry" ]]; then
        echo "Compose mTLS workload fixture contains an unexpected path." >&2
        return 1
      fi
      found_service_count=$((found_service_count + 1))
    done
    if ((found_service_count != ${#services[@]})); then
      echo "Compose mTLS workload fixture contains an unexpected service directory." >&2
      return 1
    fi
    for service in \
      account-service game-session-service entity-management-service social-groups-service; do
      local san_output uri_sans
      san_output="$(openssl x509 -in "$workloads_dir/$service/client.crt" -noout -ext subjectAltName)"
      uri_sans="$(printf '%s\n' "$san_output" | grep -oE 'URI:[^,[:space:]]+' || true)"
      if [[ "$uri_sans" != "URI:spiffe://firemud/ns/dev/sa/$service" \
        || "$san_output" != *"DNS:$service"* ]]; then
        echo "Compose mTLS workload has the wrong SPIFFE identity: $service" >&2
        return 1
      fi
    done
  fi

  echo "Compose mTLS workload certificates are ready in $workloads_dir"
}

if [[ "${1:-}" == "--compose-mtls" ]]; then
  if [[ $# -ne 2 ]]; then
    echo "usage: $0 --compose-mtls <run-owned-fixture-root>" >&2
    exit 2
  fi
  trap cleanup_compose_mtls_stage EXIT
  ensure_compose_mtls_certs "$2"
  exit $?
fi

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
runtime_files=(ca.crt client.crt client.key server.crt server.key dev-ca.pem dev-cert.pem dev-key.pem)
runtime_profiles=(default "${workloads[@]}")
if [[ -e "$RUNTIME_DIR" || -L "$RUNTIME_DIR" ]]; then
  [[ ! -L "$RUNTIME_DIR" && -d "$RUNTIME_DIR" ]] || {
    echo "refusing symlink or non-directory local Compose runtime projection: $RUNTIME_DIR" >&2
    exit 1
  }
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
fi

# Preflight every existing destination across all profiles before any
# projection directory chmod or file copy can mutate an aliased inode.
for profile in "${runtime_profiles[@]}"; do
  profile_dir="$RUNTIME_DIR/$profile"
  [[ ! -L "$profile_dir" ]] || {
    echo "refusing symlink in local Compose runtime projection: $profile_dir" >&2
    exit 1
  }
  if [[ -e "$profile_dir" && ! -d "$profile_dir" ]]; then
    echo "refusing unapproved entry in local Compose runtime projection: $profile_dir" >&2
    exit 1
  fi
  [[ -d "$profile_dir" ]] || continue

  expected_profile_files=(ca.crt client.crt client.key server.crt server.key dev-ca.pem dev-cert.pem dev-key.pem)
  if [[ "$profile" != "default" ]]; then
    if [[ -L "$profile_dir/workloads" ]]; then
      echo "refusing symlink in local Compose runtime projection: $profile_dir/workloads" >&2
      exit 1
    fi
    if [[ -e "$profile_dir/workloads" && ! -d "$profile_dir/workloads" ]]; then
      echo "refusing unapproved file in local Compose runtime projection: $profile/workloads" >&2
      exit 1
    fi
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
    if [[ -f "$existing_path" ]]; then
      link_count="$(stat -c '%h' -- "$existing_path")"
      if ((link_count > 1)); then
        echo "refusing hard-linked file in local Compose runtime projection: $existing_path" >&2
        exit 1
      fi
    fi
  done < <(find "$profile_dir" -mindepth 1 -print0)
done

# The complete existing projection passed preflight, so directory setup and
# copy/chmod operations can now proceed without changing unrelated hard links.
mkdir -p "$RUNTIME_DIR"
chmod 755 "$RUNTIME_DIR"
for workload in "${workloads[@]}"; do
  mkdir -p "$RUNTIME_DIR/$workload/workloads"
done

for profile in "${runtime_profiles[@]}"; do
  profile_dir="$RUNTIME_DIR/$profile"
  mkdir -p "$profile_dir"
  chmod 755 "$profile_dir"
  if [[ "$profile" != "default" ]]; then
    chmod 755 "$profile_dir/workloads"
  fi
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
    chmod 644 "$profile_dir/workloads/$profile.crt" "$profile_dir/workloads/$profile.key"
  fi
  chmod 644 "$profile_dir"/*.crt "$profile_dir"/*.key "$profile_dir"/*.pem
done

echo "Development certificates and local Compose workload identities are ready in $CERT_DIR"
