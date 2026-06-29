#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCK_DIR="$ROOT_DIR/.gradle/firemud-validation-locks"
WAIT_FOR_LOCK="${FIREMUD_LOCK_GRADLE_WAIT:-0}"
PRINT_LOCK_TARGETS=0

usage() {
  cat <<'EOF' >&2
Usage: run-locked-gradle.sh [--print-lock-targets] <gradle args...>

Wraps ./gradlew with repo-owned verification locks so overlapping local runs do
not write the same service test-result trees at once.

Options:
  --print-lock-targets   Print the derived lock targets and exit.

Environment:
  FIREMUD_LOCK_GRADLE_WAIT=1   Wait for locks instead of failing fast.
EOF
  exit 1
}

gradle_args=()
while (($# > 0)); do
  case "$1" in
    --print-lock-targets)
      PRINT_LOCK_TARGETS=1
      shift
      ;;
    --help|-h)
      usage
      ;;
    *)
      gradle_args+=("$1")
      shift
      ;;
  esac
done

if ((${#gradle_args[@]} == 0)); then
  usage
fi

mkdir -p "$LOCK_DIR"

declare -A KNOWN_SERVICES=()
while IFS= read -r service; do
  KNOWN_SERVICES["$service"]=1
done < <(find "$ROOT_DIR/services" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort)

declare -A LOCK_TARGET_SET=()
repo_lock_required=0

for arg in "${gradle_args[@]}"; do
  if [[ "$arg" == -* ]]; then
    continue
  fi

  if [[ "$arg" =~ ^:([^:]+):.*$ ]]; then
    project_name="${BASH_REMATCH[1]}"
    if [[ -n "${KNOWN_SERVICES[$project_name]:-}" ]]; then
      LOCK_TARGET_SET["service:$project_name"]=1
    else
      repo_lock_required=1
    fi
    continue
  fi

  if [[ "$arg" =~ ^([^:]+):.*$ ]]; then
    project_name="${BASH_REMATCH[1]}"
    if [[ -n "${KNOWN_SERVICES[$project_name]:-}" ]]; then
      LOCK_TARGET_SET["service:$project_name"]=1
    else
      repo_lock_required=1
    fi
    continue
  fi

  repo_lock_required=1
done

lock_targets=()
if ((repo_lock_required)); then
  lock_targets=("repo")
elif ((${#LOCK_TARGET_SET[@]} > 0)); then
  while IFS= read -r target; do
    lock_targets+=("$target")
  done < <(printf '%s\n' "${!LOCK_TARGET_SET[@]}" | sort)
fi

if ((PRINT_LOCK_TARGETS)); then
  if ((${#lock_targets[@]} == 0)); then
    echo "none"
  else
    printf '%s\n' "${lock_targets[@]}"
  fi
  exit 0
fi

if ((${#lock_targets[@]} == 0)); then
  echo "No verification lock targets detected; running ./gradlew without a repo lock."
  exec "$ROOT_DIR/gradlew" "${gradle_args[@]}"
fi

cleanup() {
  for lock_target in "${lock_targets[@]}"; do
    meta_path="$LOCK_DIR/${lock_target//:/__}.meta"
    if [[ -f "$meta_path" ]]; then
      owner_pid="$(sed -n '1p' "$meta_path" 2>/dev/null || true)"
      if [[ "$owner_pid" == "$$" ]]; then
        rm -f "$meta_path"
      fi
    fi
  done
}
trap cleanup EXIT

describe_lock_owner() {
  local meta_path="$1"
  if [[ ! -f "$meta_path" ]]; then
    echo "  Lock owner metadata is unavailable." >&2
    return
  fi

  mapfile -t meta_lines <"$meta_path"
  local owner_pid="${meta_lines[0]:-unknown}"
  local started_at="${meta_lines[1]:-unknown}"
  local cwd="${meta_lines[2]:-unknown}"
  local command_line="${meta_lines[3]:-unknown}"

  echo "  Active lock owner PID: $owner_pid" >&2
  echo "  Started at: $started_at" >&2
  echo "  Working directory: $cwd" >&2
  echo "  Command: $command_line" >&2
}

lock_fds=()
for lock_target in "${lock_targets[@]}"; do
  lock_file="$LOCK_DIR/${lock_target//:/__}.lock"
  meta_path="$LOCK_DIR/${lock_target//:/__}.meta"

  exec {lock_fd}>"$lock_file"
  lock_fds+=("$lock_fd")

  if [[ "$WAIT_FOR_LOCK" == "1" ]]; then
    flock "$lock_fd"
  elif ! flock -n "$lock_fd"; then
    echo "Verification lock unavailable for $lock_target." >&2
    describe_lock_owner "$meta_path"
    echo "Re-run with FIREMUD_LOCK_GRADLE_WAIT=1 to wait for the active run." >&2
    exit 1
  fi

  {
    echo "$$"
    date -u +"%Y-%m-%dT%H:%M:%SZ"
    pwd
    printf '%q ' "$ROOT_DIR/gradlew" "${gradle_args[@]}"
    echo
  } >"$meta_path"
done

echo "Acquired verification lock(s): ${lock_targets[*]}"
"$ROOT_DIR/gradlew" "${gradle_args[@]}"
