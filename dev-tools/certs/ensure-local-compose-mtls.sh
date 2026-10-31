#!/usr/bin/env bash
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GENERATOR="$SCRIPT_DIR/generate-dev-certs.sh"
CERT_ROOT="${1:-${FIREMUD_LOCAL_COMPOSE_MTLS_CERT_ROOT:-}}"
if [[ $# -gt 1 ]]; then
  echo "usage: $0 [absolute-local-certificate-root]" >&2
  exit 2
fi
if [[ -z "$CERT_ROOT" ]]; then
  DATA_HOME="${XDG_DATA_HOME:-${HOME:?HOME must be set for local Compose mTLS}}"
  if [[ -z "${XDG_DATA_HOME:-}" ]]; then
    DATA_HOME="$DATA_HOME/.local/share"
  fi
  CERT_ROOT="$DATA_HOME/firemud/local-compose-mtls"
fi
if [[ "$CERT_ROOT" != /* ]]; then
  echo "local Compose mTLS certificate root must be an absolute path." >&2
  exit 1
fi
while [[ "$CERT_ROOT" != "/" && "$CERT_ROOT" == */ ]]; do
  CERT_ROOT="${CERT_ROOT%/}"
done
if [[ "$CERT_ROOT" == "/" ]]; then
  echo "local Compose mTLS certificate root must be a dedicated directory." >&2
  exit 1
fi

owner_id="$(id -u)"
authority_dir="$CERT_ROOT/authority"
workloads_dir="$CERT_ROOT/workloads"
authority_stage=""
workloads_stage=""

cleanup_staging() {
  if [[ -n "$authority_stage" && "$authority_stage" == "$CERT_ROOT"/.authority.* \
    && -d "$authority_stage" && ! -L "$authority_stage" ]]; then
    rm -rf -- "$authority_stage"
  fi
  if [[ -n "$workloads_stage" && "$workloads_stage" == "$CERT_ROOT"/.workloads.* \
    && -d "$workloads_stage" && ! -L "$workloads_stage" ]]; then
    rm -rf -- "$workloads_stage"
  fi
}
trap cleanup_staging EXIT

fail() {
  echo "$1" >&2
  exit 1
}

fail_retained_material() {
  echo "$1" >&2
  echo "Keep the retained files; stop the ordinary local Compose stack and retry with a new dedicated FIREMUD_LOCAL_COMPOSE_MTLS_CERT_ROOT. See docker/README.md#local-compose-mtls-certificate-recovery." >&2
  exit 1
}

fail_authority_validation() {
  local directory="$1" message="$2"
  if [[ "$directory" == "$authority_dir" ]]; then
    fail_retained_material "$message"
  fi
  fail "$message"
}

fail_workload_validation() {
  local message="$1"
  if [[ "$workloads_dir" == "$CERT_ROOT/workloads" ]]; then
    fail_retained_material "$message"
  fi
  fail "$message"
}

require_directory() {
  local path="$1" mode="$2" description="$3"
  if [[ -L "$path" || ! -d "$path" ]]; then
    fail "$description must be a real directory: $path"
  fi
  if [[ "$(stat -c '%u %a %F' -- "$path")" != "$owner_id $mode directory" ]]; then
    fail "$description must be owned by this user with mode $mode: $path"
  fi
}

require_file() {
  local path="$1" mode="$2" description="$3"
  if [[ -L "$path" || ! -f "$path" ]]; then
    fail "$description must be a regular file, not a symlink: $path"
  fi
  if [[ "$(stat -c '%u %a %F' -- "$path")" != "$owner_id $mode regular file" ]]; then
    fail "$description must be owned by this user with mode $mode: $path"
  fi
}

certificate_matches_private_key() {
  local certificate="$1" private_key="$2" certificate_public_key private_key_public_key
  certificate_public_key="$(openssl x509 -in "$certificate" -pubkey -noout \
    | openssl pkey -pubin -outform DER | sha256sum | awk '{print $1}')"
  private_key_public_key="$(openssl pkey -in "$private_key" -pubout -outform DER \
    | sha256sum | awk '{print $1}')"
  [[ "$certificate_public_key" == "$private_key_public_key" ]]
}

validate_authority() {
  local directory="${1:-$authority_dir}"
  local -a entries
  entries=("$directory"/*)
  if [[ "${#entries[@]}" -ne 2 || ! -e "$directory/ca.crt" || ! -e "$directory/ca.key" ]]; then
    fail_authority_validation "$directory" "local Compose mTLS authority is incomplete or contains unexpected material; refusing replacement."
  fi
  require_directory "$directory" 700 "local Compose mTLS authority directory"
  require_file "$directory/ca.crt" 644 "local Compose mTLS public CA certificate"
  require_file "$directory/ca.key" 600 "local Compose mTLS CA private key"
  openssl x509 -in "$directory/ca.crt" -noout -checkend 0 >/dev/null \
    || fail_authority_validation "$directory" "local Compose mTLS CA certificate is expired or invalid."
  openssl verify -CAfile "$directory/ca.crt" "$directory/ca.crt" >/dev/null \
    || fail_authority_validation "$directory" "local Compose mTLS CA certificate is not a valid self-signed trust root."
  if ! certificate_matches_private_key "$directory/ca.crt" "$directory/ca.key"; then
    fail_authority_validation "$directory" "local Compose mTLS CA certificate and private key do not match."
  fi
}

services=(
  account-service spring-cloud-gateway automation-scripting-service entity-management-service
  game-design-service game-logic-service game-session-service logging-admin-service
  social-groups-service tcp-proxy-service world-management-service
)

validate_workloads() {
  local service service_dir entry expected_uri san_output
  local -a entries uri_sans
  require_directory "$workloads_dir" 755 "local Compose mTLS workload root"
  entries=("$workloads_dir"/*)
  if [[ "${#entries[@]}" -ne "${#services[@]}" ]]; then
    fail_workload_validation "local Compose mTLS workload set is incomplete or contains unexpected paths."
  fi
  for service in "${services[@]}"; do
    service_dir="$workloads_dir/$service"
    require_directory "$service_dir" 755 "local Compose mTLS workload directory"
    entries=("$service_dir"/*)
    if [[ "${#entries[@]}" -ne 3 \
      || ! -e "$service_dir/client.crt" || ! -e "$service_dir/client.key" || ! -e "$service_dir/ca.crt" ]]; then
      fail_workload_validation "local Compose mTLS workload material is incomplete or contains unexpected files: $service"
    fi
    require_file "$service_dir/client.crt" 444 "local Compose mTLS workload certificate"
    require_file "$service_dir/client.key" 444 "local Compose mTLS workload private key"
    require_file "$service_dir/ca.crt" 444 "local Compose mTLS workload CA certificate"
    if ! cmp -s "$authority_dir/ca.crt" "$service_dir/ca.crt"; then
      fail_workload_validation "local Compose mTLS workload uses a different CA: $service"
    fi
    openssl verify -CAfile "$authority_dir/ca.crt" "$service_dir/client.crt" >/dev/null \
      || fail_workload_validation "local Compose mTLS workload certificate is not trusted: $service"
    openssl x509 -in "$service_dir/client.crt" -noout -checkend 0 >/dev/null \
      || fail_workload_validation "local Compose mTLS workload certificate is expired or invalid: $service"
    if ! certificate_matches_private_key "$service_dir/client.crt" "$service_dir/client.key"; then
      fail_workload_validation "local Compose mTLS workload certificate and private key do not match: $service"
    fi
    san_output="$(openssl x509 -in "$service_dir/client.crt" -noout -ext subjectAltName)" \
      || fail_workload_validation "could not inspect local Compose mTLS workload identity: $service"
    mapfile -t uri_sans < <(printf '%s\n' "$san_output" \
      | tr ',' '\n' | sed -n 's/^[[:space:]]*\(URI:.*\)$/\1/p')
    expected_uri="URI:spiffe://firemud/ns/dev/sa/$service"
    if [[ "${#uri_sans[@]}" -ne 1 || "${uri_sans[0]:-}" != "$expected_uri" ]]; then
      fail_workload_validation "local Compose mTLS workload has the wrong SPIFFE identity: $service"
    fi
  done
}

mkdir -p -- "$CERT_ROOT"
require_directory "$CERT_ROOT" 700 "local Compose mTLS certificate root"
shopt -s dotglob nullglob
for entry in "$CERT_ROOT"/*; do
  case "$(basename "$entry")" in
    authority|workloads) ;;
    *) fail "local Compose mTLS certificate root contains unexpected retained material: $entry" ;;
  esac
done

if [[ -L "$authority_dir" ]]; then
  fail "local Compose mTLS authority path must not be a symlink."
fi
if [[ -e "$authority_dir" ]]; then
  require_directory "$authority_dir" 700 "local Compose mTLS authority directory"
  authority_entries=("$authority_dir"/*)
  if [[ "${#authority_entries[@]}" -eq 0 ]]; then
    if [[ -e "$workloads_dir" || -L "$workloads_dir" ]]; then
      fail_retained_material "local Compose mTLS workloads exist without their retained authority; refusing regeneration."
    fi
    rmdir -- "$authority_dir"
  else
    validate_authority
  fi
elif [[ -e "$workloads_dir" || -L "$workloads_dir" ]]; then
  fail_retained_material "local Compose mTLS workloads exist without their retained authority; refusing regeneration."
fi

if [[ ! -e "$authority_dir" && ! -L "$authority_dir" ]]; then
  authority_stage="$(mktemp -d "$CERT_ROOT/.authority.XXXXXX")"
  chmod 700 "$authority_stage"
  (umask 077; "$GENERATOR" "$authority_stage")
  for generated_file in client.crt client.key dev-ca.pem dev-cert.pem dev-key.pem server.crt server.key; do
    rm -f -- "$authority_stage/$generated_file"
  done
  require_file "$authority_stage/ca.crt" 644 "generated local Compose mTLS public CA certificate"
  require_file "$authority_stage/ca.key" 644 "generated local Compose mTLS CA private key"
  chmod 700 "$authority_stage"
  chmod 600 "$authority_stage/ca.key"
  validate_authority "$authority_stage"
  if ! mv -Tn -- "$authority_stage" "$authority_dir" || [[ -e "$authority_stage" ]]; then
    fail "could not install local Compose mTLS authority without replacing retained material."
  fi
  authority_stage=""
  validate_authority
fi

if [[ -L "$workloads_dir" ]]; then
  fail "local Compose mTLS workload path must not be a symlink."
fi
if [[ -e "$workloads_dir" ]]; then
  validate_workloads
else
  workloads_stage="$(mktemp -d "$CERT_ROOT/.workloads.XXXXXX")"
  chmod 700 "$workloads_stage"
  for service in "${services[@]}"; do
    service_dir="$workloads_stage/$service"
    mkdir -m 755 -- "$service_dir"
    "$GENERATOR" --workload \
      "$authority_dir/ca.crt" "$authority_dir/ca.key" \
      "$service_dir/client.crt" "$service_dir/client.key" dev "$service"
    cp -- "$authority_dir/ca.crt" "$service_dir/ca.crt"
    chmod 444 "$service_dir/client.crt" "$service_dir/client.key" "$service_dir/ca.crt"
  done
  chmod 755 "$workloads_stage"
  # Validate the complete staged set before it becomes the retained local identity set.
  saved_workloads_dir="$workloads_dir"
  workloads_dir="$workloads_stage"
  validate_workloads
  workloads_dir="$saved_workloads_dir"
  if ! mv -Tn -- "$workloads_stage" "$workloads_dir" || [[ -e "$workloads_stage" ]]; then
    fail "could not install local Compose mTLS workloads without replacing retained material."
  fi
  workloads_stage=""
  validate_workloads
fi

printf 'Local Compose mTLS certificates are ready under %s\n' "$CERT_ROOT"
