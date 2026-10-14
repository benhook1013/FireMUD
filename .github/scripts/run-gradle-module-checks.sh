#!/usr/bin/env bash
set -euo pipefail

readonly MODULE="${1:-}"
if [[ $# -ne 1 || ! "$MODULE" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]]; then
  echo "usage: run-gradle-module-checks.sh <module-name>" >&2
  exit 64
fi

readonly -a CHECK_ARGS=(
  --max-workers=2
  '-Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8'
  ":${MODULE}:check"
  -PfullCheck
)

readonly TEMP_ROOT="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
if ! TEMP_DIR="$(mktemp -d "${TEMP_ROOT%/}/firemud-gradle-check.XXXXXX")"; then
  echo "unable to create Gradle check log directory" >&2
  exit 125
fi
readonly TEMP_DIR
readonly NORMAL_LOG="$TEMP_DIR/normal.log"
readonly REFRESH_LOG="$TEMP_DIR/refresh.log"

cleanup() {
  rm -f -- "$NORMAL_LOG" "$REFRESH_LOG"
  rmdir -- "$TEMP_DIR"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

GRADLE_EXIT=0
run_gradle_check() {
  local refresh="$1"
  local log_path="$2"
  local -a pipeline_status=()

  set +e
  if [[ "$refresh" == true ]]; then
    ./gradlew --refresh-dependencies "${CHECK_ARGS[@]}" 2>&1 | tee "$log_path"
  else
    ./gradlew "${CHECK_ARGS[@]}" 2>&1 | tee "$log_path"
  fi
  pipeline_status=("${PIPESTATUS[@]}")
  set -e

  GRADLE_EXIT="${pipeline_status[0]:-125}"
  if [[ "${pipeline_status[1]:-125}" -ne 0 ]]; then
    echo "failed to capture complete Gradle output; refusing dependency retry" >&2
    exit 125
  fi
}

has_substantive_failure() {
  local log_path="$1"

  grep -Eq '(^|[[:space:]])(Compilation failed|error:|There were failing tests|Execution failed for task .*(compile|test))' "$log_path" && return 0

  awk '
    /^> Task / {
      task = $3
      outcome = $4
      name = task
      sub(/^.*:/, "", name)

      is_test = name ~ /(^|[A-Za-z0-9])(test|Test)[A-Za-z0-9]*$/
      # buildSrc compiles the build logic during Gradle setup, not the target
      # module sources whose successful execution must remain unretried.
      is_build_logic = task ~ /^:buildSrc(:|$)/
      is_compile = !is_build_logic && name ~ /^compile[A-Z][A-Za-z0-9]*$/
      if (!is_test && !is_compile) {
        next
      }

      cached_or_skipped = outcome == "FROM-CACHE" || outcome == "UP-TO-DATE" || outcome == "NO-SOURCE" || outcome == "SKIPPED"
      if (is_test && !cached_or_skipped) {
        found = 1
      } else if (is_compile && outcome != "FAILED" && !cached_or_skipped) {
        found = 1
      }
      if (is_compile && outcome == "FAILED" && !cached_or_skipped) {
        failed_compile = 1
      }
    }
    END {
      if (found) {
        exit 0
      }
      if (failed_compile) {
        exit 2
      }
      exit 1
    }
  ' "$log_path"
  case "$?" in
    0) return 0 ;;
    2)
      # A failed compile task may be retryable only when the root cause is the
      # recognized dependency-resolution failure checked below.
      return 1
      ;;
  esac
  return 1
}

is_dependency_resolution_failure() {
  local log_path="$1"
  local resolution_context=false
  local fixed_coordinate=false
  local known_remote_cause=false

  grep -Fq 'FAILURE: Build failed with an exception.' "$log_path" || return 1
  grep -Eq 'Could not resolve all (artifacts|files) for configuration' "$log_path" || return 1

  if grep -Eq '> Could not (find|resolve) [[:alnum:]_.-]+:[[:alnum:]_.-]+:[0-9][[:alnum:]_.-]*\.' "$log_path" \
    || grep -Eq '> Could not find [^[:space:]]+ \([[:alnum:]_.-]+:[[:alnum:]_.-]+:[0-9][[:alnum:]_.-]*\)\.' "$log_path"; then
    fixed_coordinate=true
  fi
  [[ "$fixed_coordinate" == true ]] || return 1

  if grep -Eq "Could not (GET|HEAD) ['\"]?https?://|Could not get resource ['\"]https?://|Searched in the following locations:" "$log_path"; then
    resolution_context=true
  fi
  [[ "$resolution_context" == true ]] || return 1

  if grep -Eq 'Connection reset|Connection timed out|SocketTimeoutException|Received status code 429 from server|Received status code 5[0-9][0-9] from server' "$log_path"; then
    known_remote_cause=true
  elif grep -Fq 'Searched in the following locations:' "$log_path"; then
    # A pinned coordinate can be temporarily absent from restored Gradle
    # metadata; the single refreshed attempt remains required proof.
    known_remote_cause=true
  fi
  [[ "$known_remote_cause" == true ]] || return 1

  ! has_substantive_failure "$log_path"
}

retry_delay_seconds() {
  local log_path="$1"
  local retry_after
  local target_epoch
  local current_epoch
  local delay

  retry_after="$(sed -nE 's/^.*[Rr]etry-[Aa]fter:[[:space:]]*(.*)$/\1/p' "$log_path" | tail -n 1)"
  if [[ -z "$retry_after" ]]; then
    if grep -Eq 'Connection reset|Connection timed out|SocketTimeoutException|Received status code 429 from server|Received status code 5[0-9][0-9] from server' "$log_path"; then
      printf '2\n'
    else
      printf '0\n'
    fi
    return 0
  fi

  if [[ "$retry_after" =~ ^[0-9]+$ ]]; then
    delay="$retry_after"
  else
    target_epoch="$(date -d "$retry_after" +%s 2>/dev/null)" || return 1
    current_epoch="$(date +%s)"
    delay=$((target_epoch - current_epoch))
    (( delay < 0 )) && delay=0
  fi

  if (( delay > 30 )); then
    echo "Retry-After exceeds the bounded 30-second retry window; preserving original failure" >&2
    return 1
  fi
  printf '%s\n' "$delay"
}

run_gradle_check false "$NORMAL_LOG"
if [[ "$GRADLE_EXIT" -eq 0 ]]; then
  exit 0
fi

if [[ "$GRADLE_EXIT" -ne 1 ]]; then
  exit "$GRADLE_EXIT"
fi

if ! is_dependency_resolution_failure "$NORMAL_LOG"; then
  exit "$GRADLE_EXIT"
fi

if ! RETRY_DELAY="$(retry_delay_seconds "$NORMAL_LOG")"; then
  exit "$GRADLE_EXIT"
fi

echo "Recognized Gradle dependency-resolution failure before compile/test execution; retrying the complete module check once with dependency metadata refresh after ${RETRY_DELAY}s."
sleep "$RETRY_DELAY"
run_gradle_check true "$REFRESH_LOG"
exit "$GRADLE_EXIT"
