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
  require_run_owned_compose_project || return 1
  docker compose "$@" down -v --remove-orphans || return 1
  release_run_owned_compose_project
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  require_run_owned_compose_project
fi
