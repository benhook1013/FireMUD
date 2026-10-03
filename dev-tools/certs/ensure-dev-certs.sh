#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERT_DIR="${1:-$SCRIPT_DIR}"
GENERATOR="$SCRIPT_DIR/generate-dev-certs.sh"
COMPOSE_MTLS_STAGE_ROOT=""
COMPOSE_MTLS_STAGE_DIR=""
COMPOSE_MTLS_LEAF_STAGE_DIR=""

cleanup_compose_mtls_stage() {
  if [[ -n "$COMPOSE_MTLS_STAGE_DIR" && "$COMPOSE_MTLS_STAGE_DIR" == "$COMPOSE_MTLS_STAGE_ROOT"/.workloads.* && -d "$COMPOSE_MTLS_STAGE_DIR" && ! -L "$COMPOSE_MTLS_STAGE_DIR" ]]; then
    rm -rf -- "$COMPOSE_MTLS_STAGE_DIR"
  fi
  if [[ -n "$COMPOSE_MTLS_LEAF_STAGE_DIR" && "$COMPOSE_MTLS_LEAF_STAGE_DIR" == "$COMPOSE_MTLS_STAGE_ROOT"/.world-management-service-leaf.* && -d "$COMPOSE_MTLS_LEAF_STAGE_DIR" && ! -L "$COMPOSE_MTLS_LEAF_STAGE_DIR" ]]; then
    rm -rf -- "$COMPOSE_MTLS_LEAF_STAGE_DIR"
  fi
}

compose_certificate_matches_private_key() {
  local certificate="$1" private_key="$2" certificate_public_key private_key_public_key
  certificate_public_key="$(openssl x509 -in "$certificate" -pubkey -noout | openssl pkey -pubin -outform DER | sha256sum | awk '{print $1}')"
  private_key_public_key="$(openssl pkey -in "$private_key" -pubout -outform DER | sha256sum | awk '{print $1}')"
  [[ "$certificate_public_key" == "$private_key_public_key" ]]
}

migrate_legacy_world_management_leaf() {
  local requested_root="$1" authority_dir="$2" service_dir="$3"
  local staged_certificate staged_private_key san_output

  # The only upgradeable shape is the original generic authority/client pair,
  # copied byte-for-byte into the WMS fixture and still using the same CA.
  if ! cmp -s "$service_dir/client.crt" "$authority_dir/client.crt" \
    || ! cmp -s "$service_dir/client.key" "$authority_dir/client.key" \
    || ! cmp -s "$service_dir/ca.crt" "$authority_dir/ca.crt" \
    || ! compose_certificate_matches_private_key "$service_dir/client.crt" "$service_dir/client.key"; then
    return 1
  fi

  COMPOSE_MTLS_STAGE_ROOT="$requested_root"
  COMPOSE_MTLS_LEAF_STAGE_DIR="$(mktemp -d "$requested_root/.world-management-service-leaf.XXXXXX")" || return 1
  chmod 700 "$COMPOSE_MTLS_LEAF_STAGE_DIR" || return 1
  staged_certificate="$COMPOSE_MTLS_LEAF_STAGE_DIR/client.crt"
  staged_private_key="$COMPOSE_MTLS_LEAF_STAGE_DIR/client.key"
  "$GENERATOR" --workload \
    "$authority_dir/ca.crt" "$authority_dir/ca.key" \
    "$staged_certificate" "$staged_private_key" dev world-management-service || return 1
  chmod 444 "$staged_certificate" "$staged_private_key" || return 1

  openssl verify -CAfile "$authority_dir/ca.crt" "$staged_certificate" >/dev/null || return 1
  san_output="$(openssl x509 -in "$staged_certificate" -noout -ext subjectAltName)" || return 1
  if [[ "$san_output" != *"URI:spiffe://firemud/ns/dev/sa/world-management-service"* ]] \
    || ! compose_certificate_matches_private_key "$staged_certificate" "$staged_private_key"; then
    echo "Generated Compose mTLS WMS leaf failed identity or key validation." >&2
    return 1
  fi

  # Both validated files are staged on the fixture filesystem; each rename
  # atomically replaces only the corresponding WMS leaf file.
  mv -fT -- "$staged_certificate" "$service_dir/client.crt" || return 1
  mv -fT -- "$staged_private_key" "$service_dir/client.key" || return 1
  openssl verify -CAfile "$service_dir/ca.crt" "$service_dir/client.crt" >/dev/null || return 1
  compose_certificate_matches_private_key "$service_dir/client.crt" "$service_dir/client.key" || return 1
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
      if [[ "$service" == account-service || "$service" == game-session-service || "$service" == entity-management-service || "$service" == world-management-service ]]; then
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
      if ! compose_certificate_matches_private_key "$service_dir/client.crt" "$service_dir/client.key"; then
        echo "Compose mTLS workload certificate and private key do not match: $service" >&2
        return 1
      fi
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
    for service in account-service game-session-service entity-management-service world-management-service; do
      local san_output
      san_output="$(openssl x509 -in "$workloads_dir/$service/client.crt" -noout -ext subjectAltName)"
      if [[ "$san_output" != *"URI:spiffe://firemud/ns/dev/sa/$service"* ]]; then
        if [[ "$service" != world-management-service ]] \
          || ! migrate_legacy_world_management_leaf \
            "$requested_root" "$authority_dir" "$workloads_dir/$service"; then
          echo "Compose mTLS workload has the wrong SPIFFE identity: $service" >&2
          return 1
        fi
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

if [[ "$missing" == "0" ]]; then
  echo "Development certificates already exist in $CERT_DIR"
  exit 0
fi

"$GENERATOR" "$CERT_DIR"
