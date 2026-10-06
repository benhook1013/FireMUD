#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCK_DIR="$ROOT_DIR/.gradle/firemud-validation-locks"
GRADLE_EXEC="${FIREMUD_LOCK_GRADLE_EXEC:-$ROOT_DIR/gradlew}"
PRINT_LOCK_TARGETS=0

usage() {
  cat <<'EOF' >&2
Usage: run-locked-gradle.sh [--print-lock-targets] <gradle args...>

Wraps ./gradlew with repo-owned verification locks so overlapping local runs do
not write the same service test-result trees at once.

Options:
  --print-lock-targets   Print the derived lock targets and exit.

Environment:
  FIREMUD_LOCK_GRADLE_WAIT=1   Wait up to 300 seconds instead of failing fast.
  FIREMUD_LOCK_GRADLE_WAIT_SECONDS    Override the total lock wait budget.
  FIREMUD_LOCK_GRADLE_RUN_SECONDS     Override the 7200-second run budget.
  FIREMUD_LOCK_GRADLE_CANCEL_SECONDS  Override the 30-second cancellation grace.
  FIREMUD_LOCK_GRADLE_RESOURCE_DIR    Override the local user-wide lock directory.

Local Linux/WSL runs use single-use daemons for owned cancellation. CI retains
its daemon policy and bypasses only the local resource guard. See the validation
and runtime proof guide for cancellation limits and direct Gradle bypasses.
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

option_consumes_next_arg() {
  case "$1" in
    -x|--exclude-task|--tests|--test-dry-run|--include-build|-p|--project-dir|-g|--gradle-user-home|-c|--settings-file|-b|--build-file|-I|--init-script|-D|--system-prop|-P|--project-prop)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

for ((i = 0; i < ${#gradle_args[@]}; i++)); do
  arg="${gradle_args[i]}"

  if [[ "$arg" == "--" ]]; then
    continue
  fi

  if [[ "$arg" == -* ]]; then
    if [[ "$arg" != *=* ]] && option_consumes_next_arg "$arg"; then
      ((i += 1))
    fi
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

lock_modes=()
actual_lock_targets=()
if ((repo_lock_required)); then
  lock_modes=("exclusive")
  actual_lock_targets=("repo")
elif ((${#LOCK_TARGET_SET[@]} > 0)); then
  lock_modes=("shared")
  actual_lock_targets=("repo")
  while IFS= read -r target; do
    lock_modes+=("exclusive")
    actual_lock_targets+=("$target")
  done < <(printf '%s\n' "${!LOCK_TARGET_SET[@]}" | sort)
fi

# Bind graceful cancellation to one private channel, never a recyclable PID.
# Unlink the FIFO after opening it so only this run's inherited FD can address it.
cancel_directory="$(mktemp -d "${TMPDIR:-/tmp}/firemud-gradle-cancel.XXXXXXXX")"
mkfifo "$cancel_directory/request"
exec {cancel_fd}<>"$cancel_directory/request"
rm -- "$cancel_directory/request"
rmdir -- "$cancel_directory"

# Supply this Bash process's original start identity before Python can launch.
wrapper_stat="$(<"/proc/$$/stat")"
wrapper_fields="${wrapper_stat##*) }"
read -r -a wrapper_identity_fields <<<"$wrapper_fields"
wrapper_started="${wrapper_identity_fields[19]}"
[[ "$wrapper_started" =~ ^[0-9]+$ ]] || exit 1
supervisor_args=(--root "$ROOT_DIR" --wrapper-pid "$$" --wrapper-start "$wrapper_started" --cancel-fd "$cancel_fd")
for idx in "${!actual_lock_targets[@]}"; do
  supervisor_args+=(--lock "${lock_modes[idx]}=${actual_lock_targets[idx]}")
done

# Keep the owner separate: it retains locks if this wrapper dies, then cancels
# only this invocation's processes. Explicit input redirection preserves stdin.
python3 "$ROOT_DIR/dev-tools/validation/gradle-run-supervisor.py" \
  "${supervisor_args[@]}" -- "$GRADLE_EXEC" "${gradle_args[@]}" <&0 &
supervisor_pid=$!
cancel_run() {
  local status="$1"
  trap '' INT TERM HUP
  printf '%s\n' "$status" >&"$cancel_fd"
  wait "$supervisor_pid" || true
  exit "$status"
}
trap 'cancel_run 130' INT
trap 'cancel_run 143' TERM
trap 'cancel_run 129' HUP
wait "$supervisor_pid"
