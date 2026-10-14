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

  grep -Eq '(^|[[:space:]])(Compilation failed|Compilation error|error:|e: file:|There were failing tests)' "$log_path" && return 0

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
  return $?
}

failure_body() {
  local log_path="$1"

  awk '
    /^FAILURE: Build failed with an exception\.$/ {
      failures++
      in_failure = 1
      next
    }
    in_failure && /^\* What went wrong:$/ {
      sections++
      in_body = 1
      next
    }
    in_body && /^\* Try:$/ {
      in_body = 0
      ended = 1
      next
    }
    in_body {
      print
    }
    END {
      if (failures != 1 || sections != 1 || !ended || invalid) {
        exit 1
      }
    }
  ' "$log_path"
}

classify_failure_body() {
  local log_path="$1"

  awk '
    function trim(s) {
      sub(/^[[:space:]]+/, "", s)
      sub(/[[:space:]]+$/, "", s)
      return s
    }
    {
      line = trim($0)
      if (line == "" || line == "* What went wrong:") {
        next
      }

      if (line == "1 problem was found storing the configuration cache.") {
        cache_summary++
        next
      }
      if (line ~ /^Configuration cache state could not be cached: field `classpath` of task `:[^`]+:compile[A-Za-z0-9]+` of type .*JavaCompile.*: error writing value of type .*DefaultResolvableConfiguration/ ||
          line ~ /^- Task `:[^`]+:compile[A-Za-z0-9]+` of type .*JavaCompile.*: error writing value of type .*DefaultResolvableConfiguration/) {
        if (match(line, /`:[^`]+:compile[A-Za-z0-9]+`/)) {
          task_reference = substr(line, RSTART, RLENGTH)
          if (cache_task != "" && cache_task != task_reference) {
            invalid++
          }
          cache_task = task_reference
        } else {
          invalid++
        }
        cache_cause++
        next
      }
      if (line ~ /^See the complete report at file:.*configuration-cache\//) {
        next
      }

      if (line ~ /^> Could not resolve all (artifacts|files) for configuration .+\.$/) {
        resolver_roots++
        if (line ~ /compileClasspath/) {
          compile_classpath_roots++
        }
        next
      }
      if (line ~ /^> Could not (find|resolve) [[:alnum:]_.-]+:[[:alnum:]_.-]+:[0-9][[:alnum:]_.-]*\.$/ ||
          line ~ /^> Could not find [^[:space:]]+ \([[:alnum:]_.-]+:[[:alnum:]_.-]+:[0-9][[:alnum:]_.-]*\)\.$/) {
        coordinates++
        next
      }
      if (line ~ /^> Could not (get resource|GET|HEAD) [\047\042]?https?:\/\// ||
          line ~ /^> Could not GET https?:\/\//) {
        repository_requests++
        next
      }
      if (line ~ /^Execution failed for task / && line ~ /:compile[A-Za-z0-9]+[\047"][.]$/) {
        compile_task_failure++
        next
      }
      if (line == "Searched in the following locations:" || line ~ /^https?:\/\// || line ~ /^- https?:\/\// ||
          line == "Required by:" || line ~ /^project / ||
          line ~ /^> Got socket exception during request\./ ||
          line ~ /^> Connection (reset|timed out)$/ ||
          line ~ /^> SocketTimeoutException/ ||
          line ~ /^Received status code (429|5[0-9][0-9]) from server/ ||
          line ~ /^Retry-After: .+$/ ||
          line ~ /^There are [0-9]+ more failures with identical causes\.$/) {
        if (line ~ /^Received status code /) {
          transient++
        }
        if (line ~ /^> Connection / || line ~ /^> SocketTimeoutException/ || line ~ /^> Got socket exception/) {
          transient++
        }
        if (line ~ /^Searched in the following locations:/) {
          searched++
        }
        if (line ~ /^https?:\/\// || line ~ /^- https?:\/\//) {
          repository_urls++
        }
        next
      }

      # Every other line inside the fatal cause body is an additional
      # or unknown cause. Do not let matching dependency text elsewhere mask it.
      invalid++
    }
    END {
      if (invalid || resolver_roots == 0 || coordinates == 0 ||
          (cache_summary && (cache_cause == 0 || cache_cause > 2)) ||
          (compile_task_failure && compile_classpath_roots == 0) ||
          !((transient && repository_requests) || (searched && repository_urls))) {
        exit 1
      }
      exit 0
    }
  ' "$log_path"
}

is_dependency_resolution_failure() {
  local log_path="$1"
  local body_path
  local substantive_status=0

  body_path="$(mktemp "${TEMP_DIR%/}/failure-body.XXXXXX")" || return 1
  if ! failure_body "$log_path" > "$body_path"; then
    rm -f -- "$body_path"
    return 1
  fi
  if ! classify_failure_body "$body_path"; then
    rm -f -- "$body_path"
    return 1
  fi

  has_substantive_failure "$log_path" || substantive_status=$?
  rm -f -- "$body_path"
  case "$substantive_status" in
    0) return 1 ;;
    1|2) return 0 ;;
    *) return 1 ;;
  esac
}

retry_delay_seconds() {
  local log_path="$1"
  local retry_after
  local delay

  retry_after="$(failure_body "$log_path" | sed -nE 's/^.*[Rr]etry-[Aa]fter:[[:space:]]*(.*)$/\1/p')" || return 1
  if [[ -z "$retry_after" ]]; then
    if failure_body "$log_path" | grep -Eq 'Connection reset|Connection timed out|SocketTimeoutException|Received status code 429 from server|Received status code 5[0-9][0-9] from server'; then
      printf '2\n'
    else
      printf '0\n'
    fi
    return 0
  fi

  # Reject date and malformed values; cap raw decimal length before arithmetic,
  # strip leading zeroes, and force base ten so values such as 08 stay safe.
  [[ "$retry_after" =~ ^[0-9]+$ ]] || return 1
  (( ${#retry_after} <= 16 )) || return 1
  local canonical="${retry_after#"${retry_after%%[!0]*}"}"
  [[ -n "$canonical" ]] || canonical=0
  (( ${#canonical} <= 2 )) || return 1
  delay=$((10#$canonical))

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
