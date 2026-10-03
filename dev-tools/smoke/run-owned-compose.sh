#!/usr/bin/env bash

# Validate and retain the explicit per-run binding before smoke mutation or
# destructive Compose access. This file is sourceable by smoke entrypoints.

_firemud_smoke_fail() {
  echo "Refusing run-owned smoke access: $*" >&2
  return 1
}

_firemud_smoke_require_platform() {
  local platform
  platform="$(uname -s 2>/dev/null || true)"
  if [[ "$platform" != Linux ]]; then
    _firemud_smoke_fail "run-owned smoke ownership requires Linux/WSL or GitHub-hosted Ubuntu (Linux kernel)."
    return 1
  fi
  if [[ ! -d /proc/self/fd ]]; then
    _firemud_smoke_fail "run-owned smoke ownership requires procfs at /proc/self/fd."
    return 1
  fi
  if ! command -v flock >/dev/null 2>&1; then
    _firemud_smoke_fail "run-owned smoke ownership requires the flock dependency."
    return 1
  fi
  if ! command -v stat >/dev/null 2>&1 || ! stat -Lc '%F' /proc >/dev/null 2>&1; then
    _firemud_smoke_fail "run-owned smoke ownership requires GNU stat with -L and -c support."
    return 1
  fi
  local dependency
  for dependency in readlink id mktemp awk; do
    if ! command -v "$dependency" >/dev/null 2>&1; then
      _firemud_smoke_fail "run-owned smoke ownership requires the $dependency dependency."
      return 1
    fi
  done
  if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
    _firemud_smoke_fail "run-owned smoke ownership requires sha256sum or shasum."
    return 1
  fi
}

_firemud_smoke_sha256() {
  local digest_output
  if command -v sha256sum >/dev/null 2>&1; then
    if ! digest_output="$(sha256sum)"; then
      _firemud_smoke_fail "sha256sum command failed."
      return 1
    fi
    printf '%s\n' "$digest_output" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    if ! digest_output="$(shasum -a 256)"; then
      _firemud_smoke_fail "shasum command failed."
      return 1
    fi
    printf '%s\n' "$digest_output" | awk '{print $1}'
  else
    _firemud_smoke_fail "sha256sum or shasum is required for ownership markers."
  fi
}

_firemud_smoke_context() {
  local run_id="${GITHUB_RUN_ID:-}"
  local run_attempt="${GITHUB_RUN_ATTEMPT:-}"
  local job="${GITHUB_JOB:-}"

  if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
    if [[ -z "$run_id" || -z "$run_attempt" || -z "$job" ]]; then
      _firemud_smoke_fail "GitHub Actions mode requires nonempty GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT, and GITHUB_JOB."
      return 1
    fi
    if [[ ! "$run_id" =~ ^[A-Za-z0-9._-]+$ || ! "$run_attempt" =~ ^[A-Za-z0-9._-]+$ || ! "$job" =~ ^[A-Za-z0-9._-]+$ ]]; then
      _firemud_smoke_fail "GitHub Actions identity contains unsafe characters."
      return 1
    fi
    FIREMUD_SMOKE_EXPECTED_MODE=github
    FIREMUD_SMOKE_EXPECTED_PROJECT="smoke-full-$run_id-$run_attempt"
    FIREMUD_SMOKE_CAPABILITY_INPUT="github:$run_id:$run_attempt:$job"
    return 0
  fi

  run_id="${FIREMUD_SMOKE_RUN_ID:-}"
  if [[ ! "$run_id" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
    _firemud_smoke_fail "local FIREMUD_SMOKE_RUN_ID must match ^[a-z0-9][a-z0-9-]*$ and be bound to COMPOSE_PROJECT_NAME."
    return 1
  fi
  FIREMUD_SMOKE_EXPECTED_MODE=local
  FIREMUD_SMOKE_EXPECTED_PROJECT="firemud-smoke-$run_id"
  FIREMUD_SMOKE_CAPABILITY_INPUT=
}

_firemud_smoke_prepare() {
  _firemud_smoke_require_platform || return 1
  _firemud_smoke_context || return 1
  if [[ "${COMPOSE_PROJECT_NAME:-}" != "$FIREMUD_SMOKE_EXPECTED_PROJECT" ]]; then
    _firemud_smoke_fail "COMPOSE_PROJECT_NAME must exactly match $FIREMUD_SMOKE_EXPECTED_PROJECT."
    return 1
  fi

  local dir="${FIREMUD_SMOKE_OWNERSHIP_DIR:-}"
  if [[ -z "$dir" ]]; then
    local state_home="${XDG_STATE_HOME:-}"
    if [[ -z "$state_home" ]]; then
      if [[ -n "${HOME:-}" ]]; then
        state_home="$HOME/.local/state"
      else
        state_home="/tmp/firemud-smoke-state-$(id -u)"
      fi
    fi
    dir="$state_home/firemud-smoke/ownership"
  elif [[ "${FIREMUD_SMOKE_TEST_MODE:-}" != "1" ]]; then
    _firemud_smoke_fail "FIREMUD_SMOKE_OWNERSHIP_DIR is available only with FIREMUD_SMOKE_TEST_MODE=1."
    return 1
  fi
  if [[ "$dir" != /* || -L "$dir" || "$dir" == *$'\n'* || "$dir" == *$'\r'* ]]; then
    _firemud_smoke_fail "ownership directory must be an absolute, non-symlink path."
    return 1
  fi
  if [[ ! -d "$dir" ]]; then
    local previous_umask mkdir_status
    previous_umask="$(umask)"
    umask 077
    if mkdir -p "$dir"; then
      mkdir_status=0
    else
      mkdir_status=$?
    fi
    umask "$previous_umask"
    if ((mkdir_status != 0)); then
      _firemud_smoke_fail "could not create ownership directory."
      return 1
    fi
  fi
  if [[ "$(stat -Lc '%u %a %F' "$dir" 2>/dev/null || true)" != "$(id -u) 700 directory" ]]; then
    _firemud_smoke_fail "ownership directory must be an owner-only directory (mode 700)."
    return 1
  fi

  FIREMUD_SMOKE_OWNERSHIP_DIR_RESOLVED="$dir"
  FIREMUD_SMOKE_PROJECT_KEY="$(printf '%s' "$FIREMUD_SMOKE_EXPECTED_PROJECT" | _firemud_smoke_sha256)" || return 1
  FIREMUD_SMOKE_MARKER_PATH="$dir/$FIREMUD_SMOKE_PROJECT_KEY.marker"
  FIREMUD_SMOKE_LOCK_PATH="$dir/$FIREMUD_SMOKE_PROJECT_KEY.lock"
}

_firemud_smoke_capability() {
  local token="${FIREMUD_SMOKE_OWNERSHIP_TOKEN:-}"
  if [[ "$FIREMUD_SMOKE_EXPECTED_MODE" == local ]]; then
    if [[ ! "$token" =~ ^[a-f0-9]{64}$ ]]; then
      _firemud_smoke_fail "local FIREMUD_SMOKE_OWNERSHIP_TOKEN must be an opaque 64-character lowercase hexadecimal token."
      return 1
    fi
    printf '%s' "$token" | _firemud_smoke_sha256
  else
    printf '%s' "$FIREMUD_SMOKE_CAPABILITY_INPUT" | _firemud_smoke_sha256
  fi
}

_firemud_smoke_unlock() {
  local fd="${FIREMUD_SMOKE_LOCK_FD:-}"
  if [[ "$fd" =~ ^[0-9]+$ ]]; then
    flock -u "$fd" 2>/dev/null || true
    eval "exec ${fd}>&-"
  fi
  unset FIREMUD_SMOKE_LOCK_FD FIREMUD_SMOKE_LOCK_PROJECT_KEY
}

_firemud_smoke_lock() {
  local fd current_fd="${FIREMUD_SMOKE_LOCK_FD:-}"
  if [[ "$current_fd" =~ ^[0-9]+$ && -e "/proc/self/fd/$current_fd" ]]; then
    if [[ "${FIREMUD_SMOKE_LOCK_PROJECT_KEY:-}" == "$FIREMUD_SMOKE_PROJECT_KEY" && "$(readlink "/proc/self/fd/$current_fd" 2>/dev/null || true)" == "$FIREMUD_SMOKE_LOCK_PATH" ]]; then
      return 0
    fi
    _firemud_smoke_fail "the calling shell already holds a different smoke project lock."
    return 1
  fi
  if [[ -L "$FIREMUD_SMOKE_LOCK_PATH" ]]; then
    _firemud_smoke_fail "ownership lock must not be a symlink."
    return 1
  fi

  local previous_umask lock_status
  previous_umask="$(umask)"
  umask 077
  if exec {fd}>"$FIREMUD_SMOKE_LOCK_PATH"; then
    lock_status=0
  else
    lock_status=$?
  fi
  umask "$previous_umask"
  if ((lock_status != 0)); then
    _firemud_smoke_fail "could not open ownership lock."
    return 1
  fi
  if ! chmod 600 "$FIREMUD_SMOKE_LOCK_PATH"; then
    eval "exec ${fd}>&-"
    _firemud_smoke_fail "could not restrict ownership lock permissions."
    return 1
  fi
  if ! flock -n "$fd"; then
    eval "exec ${fd}>&-"
    _firemud_smoke_fail "another smoke invocation holds the project lock."
    return 1
  fi
  FIREMUD_SMOKE_LOCK_FD="$fd"
  FIREMUD_SMOKE_LOCK_PROJECT_KEY="$FIREMUD_SMOKE_PROJECT_KEY"
}

_firemud_smoke_marker_read() {
  local marker="$1"
  if [[ -L "$marker" ]]; then
    _firemud_smoke_fail "ownership marker must not be a symlink."
    return 1
  fi
  if [[ ! -e "$marker" || "$(stat -Lc '%u %a %F' "$marker" 2>/dev/null || true)" != "$(id -u) 600 regular file" ]]; then
    _firemud_smoke_fail "ownership marker must be an owner-only regular file (mode 600)."
    return 1
  fi

  local -a lines=()
  mapfile -t lines <"$marker" || {
    _firemud_smoke_fail "could not read ownership marker."
    return 1
  }
  if ((${#lines[@]} != 1)) || [[ ! "${lines[0]:-}" =~ ^[a-f0-9]{64}$ ]]; then
    _firemud_smoke_fail "ownership marker must contain exactly one capability digest."
    return 1
  fi
  FIREMUD_SMOKE_MARKER_DIGEST="${lines[0]}"
}

_firemud_smoke_marker_matches() {
  local expected_digest="$1"
  [[ "$FIREMUD_SMOKE_MARKER_DIGEST" == "$expected_digest" ]]
}

_firemud_smoke_resources() {
  local project="$1"
  local output

  output="$(docker ps -a --filter "label=com.docker.compose.project=$project" --format '{{.ID}}' 2>/dev/null)" || return 1
  [[ -z "$output" ]] || printf '%s\n' "$output"
  output="$(docker network ls --filter "label=com.docker.compose.project=$project" --format '{{.ID}}' 2>/dev/null)" || return 1
  [[ -z "$output" ]] || printf '%s\n' "$output"
  output="$(docker volume ls --filter "label=com.docker.compose.project=$project" --format '{{.Name}}' 2>/dev/null)" || return 1
  [[ -z "$output" ]] || printf '%s\n' "$output"
}

_firemud_smoke_resource_status() {
  local resources
  resources="$(_firemud_smoke_resources "$1")" || return 2
  [[ -n "$resources" ]] && return 0
  return 1
}

_firemud_smoke_verify_marker() {
  local digest
  digest="$(_firemud_smoke_capability)" || return 1
  if [[ ! -e "$FIREMUD_SMOKE_MARKER_PATH" && ! -L "$FIREMUD_SMOKE_MARKER_PATH" ]]; then
    _firemud_smoke_fail "no ownership marker exists for this project."
    return 1
  fi
  _firemud_smoke_marker_read "$FIREMUD_SMOKE_MARKER_PATH" || return 1
  if ! _firemud_smoke_marker_matches "$digest"; then
    _firemud_smoke_fail "ownership marker does not match this project and invocation capability."
    return 1
  fi
}

_firemud_smoke_write_marker() {
  local digest="$1"
  local temporary
  temporary="$(mktemp "$FIREMUD_SMOKE_OWNERSHIP_DIR_RESOLVED/.marker.$FIREMUD_SMOKE_PROJECT_KEY.XXXXXX")" || {
    _firemud_smoke_fail "could not create temporary ownership marker."
    return 1
  }
  if ! chmod 600 "$temporary" || ! printf '%s\n' "$digest" >"$temporary"; then
    rm -f "$temporary"
    _firemud_smoke_fail "could not write temporary ownership marker."
    return 1
  fi
  if ! ln "$temporary" "$FIREMUD_SMOKE_MARKER_PATH" 2>/dev/null; then
    rm -f "$temporary"
    _firemud_smoke_fail "ownership marker creation raced with another invocation."
    return 1
  fi
  rm -f "$temporary"
}

_firemud_smoke_initial_admission_context() {
  local digest project_name="$FIREMUD_SMOKE_EXPECTED_PROJECT"
  digest="$(_firemud_smoke_capability)" || return 1
  if [[ "$FIREMUD_SMOKE_EXPECTED_MODE" == github ]]; then
    FIREMUD_SMOKE_RUN_ID="gha-$(printf '%s' "$FIREMUD_SMOKE_CAPABILITY_INPUT" | _firemud_smoke_sha256)" || return 1
  fi
  if [[ ! "$FIREMUD_SMOKE_RUN_ID" =~ ^[a-z0-9][a-z0-9-]{0,127}$ || ! "$project_name" =~ ^[a-z0-9][a-z0-9-]{0,127}$ ]]; then
    _firemud_smoke_fail "initial-admission run and project identifiers must match the Game Session capability identifier format."
    return 1
  fi

  FIREMUD_SMOKE_COMPOSE_PROJECT_NAME="$project_name"
  FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_PATH=/app/run-owned-initial-admission-capability.json
  FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_HOST_PATH="$FIREMUD_SMOKE_OWNERSHIP_DIR_RESOLVED/$FIREMUD_SMOKE_PROJECT_KEY.initial-admission-capability.json"
  FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED=false
  FIREMUD_SMOKE_INITIAL_ADMISSION_CLAIM_DIGEST="$digest"
  export FIREMUD_SMOKE_RUN_ID FIREMUD_SMOKE_COMPOSE_PROJECT_NAME
  export FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_PATH
  export FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_HOST_PATH
  export FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED
}

_firemud_smoke_initial_admission_operation_id() {
  python3 - "$FIREMUD_SMOKE_INITIAL_ADMISSION_CLAIM_DIGEST" \
    "$FIREMUD_SMOKE_RUN_ID" "$FIREMUD_SMOKE_COMPOSE_PROJECT_NAME" "$1" "$2" <<'PY'
import sys
import uuid

claim_digest, run_id, project_name, leaf_sha256, ca_sha256 = sys.argv[1:]
namespace = uuid.UUID("2b7ca687-3637-4dc4-9b12-08ec4c6a9f76")
identity = "\0".join(
    (
        "firemud.run-owned-initial-admission-fixture.v1",
        claim_digest,
        run_id,
        project_name,
        leaf_sha256,
        ca_sha256,
    )
)
print(uuid.uuid5(namespace, identity))
PY
}

_firemud_smoke_validate_initial_admission_capability() {
  local path="$1" operation_id="$2" leaf_sha256="$3" ca_sha256="$4"
  local file_metadata
  if [[ -L "$path" || ! -f "$path" ]]; then
    _firemud_smoke_fail "initial-admission capability must be an existing regular non-symlink file."
    return 1
  fi
  file_metadata="$(stat -c '%u %a %F %s' "$path" 2>/dev/null || true)"
  if [[ ! "$file_metadata" =~ ^$(id -u)[[:space:]]444[[:space:]]regular\ file[[:space:]][1-9][0-9]{0,4}$ ]]; then
    _firemud_smoke_fail "initial-admission capability must be owner-owned, read-only, and bounded."
    return 1
  fi

  if ! python3 - "$path" "$FIREMUD_SMOKE_RUN_ID" \
    "$FIREMUD_SMOKE_COMPOSE_PROJECT_NAME" "$operation_id" "$leaf_sha256" "$ca_sha256" <<'PY'
import json
import os
import re
import stat
import sys
import uuid

path, run_id, project_name, operation_id, leaf_sha256, ca_sha256 = sys.argv[1:]
allowed = {
    "schema", "runId", "composeProjectName", "operationId", "tenantId",
    "gameTemplateId", "ownerAccountId", "worldSlug", "worldDisplayName",
    "realmSlug", "realmDisplayName", "visible", "publicProductionRealm",
    "requiresCharacterSelection", "stateScope", "characterCreationPolicy",
    "gameSessionLeafSha256", "gameSessionUriSan", "caCertificateSha256",
}

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate key")
        result[key] = value
    return result

try:
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        before = os.fstat(descriptor)
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_uid != os.getuid()
            or stat.S_IMODE(before.st_mode) != 0o444
            or before.st_size <= 0
            or before.st_size > 16 * 1024
        ):
            raise ValueError("bad capability file")
        chunks = bytearray()
        while len(chunks) <= 16 * 1024:
            chunk = os.read(descriptor, min(1024, 16 * 1024 + 1 - len(chunks)))
            if not chunk:
                break
            chunks.extend(chunk)
        after = os.fstat(descriptor)
        path_after = os.stat(path, follow_symlinks=False)
        if (
            len(chunks) > 16 * 1024
            or not stat.S_ISREG(path_after.st_mode)
            or (before.st_dev, before.st_ino) != (after.st_dev, after.st_ino)
            or (before.st_dev, before.st_ino) != (path_after.st_dev, path_after.st_ino)
            or (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns)
            or (before.st_size, before.st_mtime_ns) != (path_after.st_size, path_after.st_mtime_ns)
            or before.st_uid != after.st_uid
            or before.st_uid != path_after.st_uid
            or stat.S_IMODE(after.st_mode) != 0o444
            or stat.S_IMODE(path_after.st_mode) != 0o444
        ):
            raise ValueError("changed capability file")
        payload = bytes(chunks)
    finally:
        os.close(descriptor)
    value = json.loads(payload.decode("utf-8"), object_pairs_hook=unique_object)
    uuid.UUID(operation_id)
except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError):
    raise SystemExit(1)

expected = {
    "schema": "firemud.run-owned-initial-admission-fixture.v1",
    "runId": run_id,
    "composeProjectName": project_name,
    "operationId": operation_id,
    "tenantId": 1,
    "gameTemplateId": 1,
    "ownerAccountId": 1,
    "worldSlug": "demo",
    "worldDisplayName": "Demo World",
    "realmSlug": "production",
    "realmDisplayName": "Live Realm",
    "visible": True,
    "publicProductionRealm": True,
    "requiresCharacterSelection": False,
    "stateScope": "SHARED",
    "characterCreationPolicy": "ALLOW_NEW",
    "gameSessionLeafSha256": leaf_sha256,
    "gameSessionUriSan": "spiffe://firemud/ns/dev/sa/game-session-service",
    "caCertificateSha256": ca_sha256,
}
safe_id = re.compile(r"[a-z0-9][a-z0-9-]{0,127}\Z")
if not safe_id.fullmatch(run_id) or not safe_id.fullmatch(project_name):
    raise SystemExit(1)
if (
    not isinstance(value, dict)
    or set(value) != allowed
    or value != expected
    or any(type(value[key]) is not type(expected[key]) for key in allowed)
):
    raise SystemExit(1)
PY
  then
    _firemud_smoke_fail "initial-admission capability does not match the exact run claim, project, fixture, and TLS identity."
    return 1
  fi
}

ensure_run_owned_initial_admission_capability() {
  local certificate_root="$1" mode="$2"
  local expected_certificate_root leaf_certificate ca_certificate leaf_sha256 ca_sha256
  local operation_id capability_path temporary
  local -a uri_sans=()

  case "$mode" in
    create-or-verify|reuse-only) ;;
    *)
      _firemud_smoke_fail "initial-admission capability mode must be create-or-verify or reuse-only."
      return 1
      ;;
  esac
  _firemud_smoke_prepare || return 1
  _firemud_smoke_lock || return 1
  _firemud_smoke_verify_marker || return 1
  _firemud_smoke_initial_admission_context || return 1

  expected_certificate_root="$FIREMUD_SMOKE_OWNERSHIP_DIR_RESOLVED/$FIREMUD_SMOKE_PROJECT_KEY.grpc-mtls"
  if [[ "$certificate_root" != "$expected_certificate_root" || -L "$certificate_root" || ! -d "$certificate_root" ]]; then
    _firemud_smoke_fail "initial-admission TLS material must be under this claimed run's canonical certificate root."
    return 1
  fi
  leaf_certificate="$certificate_root/workloads/game-session-service/client.crt"
  ca_certificate="$certificate_root/workloads/game-session-service/ca.crt"
  if [[ -L "$certificate_root/workloads" || -L "$certificate_root/workloads/game-session-service" \
    || ! -d "$certificate_root/workloads/game-session-service" \
    || -L "$leaf_certificate" || ! -f "$leaf_certificate" \
    || -L "$ca_certificate" || ! -f "$ca_certificate" ]]; then
    _firemud_smoke_fail "initial-admission TLS leaf and CA must be regular files in the claimed Game Session workload directory."
    return 1
  fi
  if [[ "$(grep -c -- '-----BEGIN CERTIFICATE-----' "$leaf_certificate" 2>/dev/null || true)" != 1 \
    || "$(grep -c -- '-----BEGIN CERTIFICATE-----' "$ca_certificate" 2>/dev/null || true)" != 1 ]]; then
    _firemud_smoke_fail "initial-admission TLS leaf and CA must each contain exactly one certificate."
    return 1
  fi
  if ! openssl x509 -in "$leaf_certificate" -noout -checkend 0 >/dev/null 2>&1 \
    || ! openssl x509 -in "$ca_certificate" -noout -checkend 0 >/dev/null 2>&1 \
    || ! openssl verify -CAfile "$ca_certificate" "$leaf_certificate" >/dev/null 2>&1; then
    _firemud_smoke_fail "initial-admission Game Session TLS leaf is invalid for the run-owned CA."
    return 1
  fi
  mapfile -t uri_sans < <(openssl x509 -in "$leaf_certificate" -noout -ext subjectAltName 2>/dev/null \
    | grep -oE 'URI:[^,[:space:]]+')
  if ((${#uri_sans[@]} != 1)) \
    || [[ "${uri_sans[0]:-}" != URI:spiffe://firemud/ns/dev/sa/game-session-service ]]; then
    _firemud_smoke_fail "initial-admission Game Session TLS leaf must contain one exact dev workload URI SAN."
    return 1
  fi
  leaf_sha256="$(openssl x509 -in "$leaf_certificate" -outform DER 2>/dev/null \
    | openssl dgst -sha256 2>/dev/null | awk '{print $NF}')"
  ca_sha256="$(openssl x509 -in "$ca_certificate" -outform DER 2>/dev/null \
    | openssl dgst -sha256 2>/dev/null | awk '{print $NF}')"
  if [[ ! "$leaf_sha256" =~ ^[a-f0-9]{64}$ || ! "$ca_sha256" =~ ^[a-f0-9]{64}$ ]]; then
    _firemud_smoke_fail "could not fingerprint the run-owned Game Session TLS leaf and CA."
    return 1
  fi

  capability_path="$FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_HOST_PATH"
  operation_id="$(_firemud_smoke_initial_admission_operation_id "$leaf_sha256" "$ca_sha256")" || {
    _firemud_smoke_fail "could not derive the stable initial-admission operation identity."
    return 1
  }
  if [[ -e "$capability_path" || -L "$capability_path" ]]; then
    _firemud_smoke_validate_initial_admission_capability \
      "$capability_path" "$operation_id" "$leaf_sha256" "$ca_sha256"
    local validation_status=$?
    if ((validation_status == 0)); then
      FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED=true
      export FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED
    fi
    return "$validation_status"
  fi
  if [[ "$mode" == reuse-only ]]; then
    _firemud_smoke_fail "restart requires the existing matching initial-admission capability; it will not create one."
    return 1
  fi

  temporary="$(mktemp "$FIREMUD_SMOKE_OWNERSHIP_DIR_RESOLVED/.initial-admission-capability.$FIREMUD_SMOKE_PROJECT_KEY.XXXXXX")" || {
    _firemud_smoke_fail "could not create a temporary initial-admission capability."
    return 1
  }
  if ! python3 - "$temporary" "$FIREMUD_SMOKE_RUN_ID" \
    "$FIREMUD_SMOKE_COMPOSE_PROJECT_NAME" "$operation_id" "$leaf_sha256" "$ca_sha256" <<'PY'
import json
import pathlib
import sys

path, run_id, project_name, operation_id, leaf_sha256, ca_sha256 = sys.argv[1:]
value = {
    "schema": "firemud.run-owned-initial-admission-fixture.v1",
    "runId": run_id,
    "composeProjectName": project_name,
    "operationId": operation_id,
    "tenantId": 1,
    "gameTemplateId": 1,
    "ownerAccountId": 1,
    "worldSlug": "demo",
    "worldDisplayName": "Demo World",
    "realmSlug": "production",
    "realmDisplayName": "Live Realm",
    "visible": True,
    "publicProductionRealm": True,
    "requiresCharacterSelection": False,
    "stateScope": "SHARED",
    "characterCreationPolicy": "ALLOW_NEW",
    "gameSessionLeafSha256": leaf_sha256,
    "gameSessionUriSan": "spiffe://firemud/ns/dev/sa/game-session-service",
    "caCertificateSha256": ca_sha256,
}
with pathlib.Path(path).open("w", encoding="utf-8") as target:
    json.dump(value, target, indent=2)
    target.write("\n")
PY
  then
    rm -f -- "$temporary"
    _firemud_smoke_fail "could not write the initial-admission capability."
    return 1
  fi
  if ! chmod 444 "$temporary" || ! ln "$temporary" "$capability_path"; then
    rm -f -- "$temporary"
    _firemud_smoke_fail "initial-admission capability creation raced or could not be made read-only."
    return 1
  fi
  rm -f -- "$temporary"
  _firemud_smoke_validate_initial_admission_capability \
    "$capability_path" "$operation_id" "$leaf_sha256" "$ca_sha256" || return 1
  FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED=true
  export FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED
}

require_run_owned_compose_project() {
  _firemud_smoke_prepare || return 1
  _firemud_smoke_lock || return 1
  _firemud_smoke_verify_marker || return 1

  local status=0
  _firemud_smoke_resource_status "$FIREMUD_SMOKE_EXPECTED_PROJECT" || status=$?
  case "$status" in
    0) return 0 ;;
    1) _firemud_smoke_fail "ownership marker exists but no standard Compose-labelled project resource is present." ;;
    *) _firemud_smoke_fail "could not inspect standard Compose-labelled resources." ;;
  esac
}

claim_run_owned_compose_project() {
  _firemud_smoke_prepare || return 1
  _firemud_smoke_lock || return 1

  local digest status=0
  digest="$(_firemud_smoke_capability)" || return 1
  if [[ -e "$FIREMUD_SMOKE_MARKER_PATH" || -L "$FIREMUD_SMOKE_MARKER_PATH" ]]; then
    _firemud_smoke_verify_marker
    return $?
  fi

  _firemud_smoke_resource_status "$FIREMUD_SMOKE_EXPECTED_PROJECT" || status=$?
  case "$status" in
    0) _firemud_smoke_fail "standard Compose-labelled resources already exist without an ownership marker." ;;
    1) _firemud_smoke_write_marker "$digest" ;;
    *) _firemud_smoke_fail "could not establish that the Compose project is empty." ;
  esac
}

require_run_owned_compose_service() {
  local service="$1"
  local container_port="$2"
  local host_port="$3"
  local ids id service_id="" count=0 binding bindings

  require_run_owned_compose_project || return 1
  if [[ ! "$container_port" =~ ^[0-9]+$ || ! "$host_port" =~ ^[0-9]+$ ]]; then
    _firemud_smoke_fail "Compose service port expectations must be numeric."
    return 1
  fi
  ids="$(docker ps --filter "label=com.docker.compose.project=$FIREMUD_SMOKE_EXPECTED_PROJECT" --filter "label=com.docker.compose.service=$service" --filter status=running --format '{{.ID}}' 2>/dev/null)" || {
    _firemud_smoke_fail "could not inspect running Compose service $service."
    return 1
  }
  while IFS= read -r id; do
    [[ -n "$id" ]] || continue
    count=$((count + 1))
    service_id="$id"
    if ((count > 1)); then
      _firemud_smoke_fail "expected exactly one running Compose service $service."
      return 1
    fi
  done <<<"$ids"
  if ((count != 1)); then
    _firemud_smoke_fail "expected exactly one running Compose service $service."
    return 1
  fi

  bindings="$(docker port "$service_id" "${container_port}/tcp" 2>/dev/null)" || {
    _firemud_smoke_fail "could not inspect published port for Compose service $service."
    return 1
  }
  while IFS= read -r binding; do
    [[ "$binding" == *":$host_port" ]] && return 0
  done <<<"$bindings"
  _firemud_smoke_fail "Compose service $service does not publish ${container_port}/tcp on host port $host_port."
}

require_smoke_mutation_boundary() {
  SMOKE_MUTATION_EXTENSION=${SMOKE_MUTATION_EXTENSION:-false}
  SMOKE_MUTATION_BOUNDARY=${SMOKE_MUTATION_BOUNDARY:-}
  case "$SMOKE_MUTATION_EXTENSION" in
    false|0)
      SMOKE_MUTATION_EXTENSION=false
      ;;
    true|1)
      SMOKE_MUTATION_EXTENSION=true
      case "$SMOKE_MUTATION_BOUNDARY" in
        run-owned-compose)
          require_run_owned_compose_project || return 1
          ;;
        restricted-synthetic|synthetic-identity)
          echo "SMOKE_MUTATION_BOUNDARY=$SMOKE_MUTATION_BOUNDARY is unavailable: no authoritative synthetic identity/isolation verifier exists." >&2
          return 1
          ;;
        *)
          echo "Mutation extension requires SMOKE_MUTATION_BOUNDARY=run-owned-compose; refusing unverified state." >&2
          return 1
          ;;
      esac
      ;;
    *)
      echo "SMOKE_MUTATION_EXTENSION must be boolean true/false (or 1/0); refusing to run." >&2
      return 1
      ;;
  esac
  export SMOKE_MUTATION_EXTENSION
  export SMOKE_MUTATION_BOUNDARY
}

release_run_owned_compose_project() {
  _firemud_smoke_prepare || return 1
  _firemud_smoke_lock || return 1
  if ! _firemud_smoke_verify_marker; then
    _firemud_smoke_unlock
    return 1
  fi

  local status=0
  _firemud_smoke_resource_status "$FIREMUD_SMOKE_EXPECTED_PROJECT" || status=$?
  case "$status" in
    0)
      _firemud_smoke_fail "cannot release ownership while standard Compose-labelled project resources remain."
      return 1
      ;;
    1)
      if ! rm -f "$FIREMUD_SMOKE_MARKER_PATH"; then
        _firemud_smoke_fail "could not remove ownership marker."
        return 1
      fi
      _firemud_smoke_unlock
      return 0
      ;;
    *)
      _firemud_smoke_fail "could not establish that the Compose project is empty."
      return 1
      ;;
  esac
}

stop_run_owned_compose_project() {
  _firemud_smoke_prepare || return 1
  _firemud_smoke_lock || return 1
  if ! _firemud_smoke_verify_marker; then
    _firemud_smoke_unlock
    return 1
  fi

  local status=0
  _firemud_smoke_resource_status "$FIREMUD_SMOKE_EXPECTED_PROJECT" || status=$?
  case "$status" in
    0)
      docker compose "$@" down -v --remove-orphans || {
        _firemud_smoke_unlock
        return 1
      }
      ;;
    1)
      # Compose can fail before creating any labelled resource (for example,
      # while pulling an image). With the matching run capability and a
      # complete empty-resource check under the project lock, release its
      # marker without asking Compose to tear down a project it never created.
      ;;
    *)
      _firemud_smoke_fail "could not establish that the Compose project is empty."
      _firemud_smoke_unlock
      return 1
      ;;
  esac

  if ! release_run_owned_compose_project; then
    _firemud_smoke_unlock
    return 1
  fi
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  require_run_owned_compose_project
fi
