#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 4 || "$1" != --runner || -z "$2" ]]; then
  echo "usage: run-gradle-with-dependency-recovery.sh --runner <executable> -- <Gradle args...>" >&2
  exit 64
fi
readonly GRADLE_RUNNER="$2"
shift 2
if [[ "$1" != -- ]]; then
  echo "expected -- before Gradle arguments" >&2
  exit 64
fi
shift
if (($# == 0)); then
  echo "at least one Gradle argument is required" >&2
  exit 64
fi
readonly -a GRADLE_ARGS=("$@")

readonly TEMP_ROOT="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
if ! TEMP_DIR="$(mktemp -d "${TEMP_ROOT%/}/firemud-gradle-check.XXXXXX")"; then
  echo "unable to create Gradle recovery log directory" >&2
  exit 125
fi
readonly TEMP_DIR
readonly NORMAL_LOG="$TEMP_DIR/normal.log"
readonly REFRESH_LOG="$TEMP_DIR/refresh.log"

# Invoked indirectly by the EXIT trap below.
# shellcheck disable=SC2329
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
    "$GRADLE_RUNNER" --refresh-dependencies "${GRADLE_ARGS[@]}" 2>&1 | tee "$log_path"
  else
    "$GRADLE_RUNNER" "${GRADLE_ARGS[@]}" 2>&1 | tee "$log_path"
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

      is_compile = name ~ /^compile[A-Z][A-Za-z0-9]*$/
      is_test = !is_compile && name ~ /(^|[A-Za-z0-9])(test|Test)[A-Za-z0-9]*$/
      # buildSrc compiles the build logic during Gradle setup, not the target
      # module sources whose successful execution must remain unretried.
      is_build_logic = task ~ /^:buildSrc(:|$)/
      is_compile = !is_build_logic && is_compile
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
    BEGIN { quote = sprintf("%c", 39) }
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
      if (line ~ /^Configuration cache state could not be cached: field `classpath` of `org\.gradle\.process\.internal\.DefaultJavaExecSpec_Decorated` bean found in field `javaExecSpec` of task `:[^`]+:generateJooq` of type `org\.gradle\.api\.tasks\.JavaExec`: error writing value of type \047org\.gradle\.api\.internal\.file\.collections\.DefaultConfigurableFileCollection\047$/) {
        if (match(line, /task `:[^`]+:generateJooq`/)) {
          task_reference = substr(line, RSTART, RLENGTH)
          sub(/^task `/, "", task_reference)
          sub(/`$/, "", task_reference)
          sub(/:generateJooq$/, "", task_reference)
          if (jooq_module != "" && jooq_module != task_reference) {
            invalid++
          }
          jooq_module = task_reference
        } else {
          invalid++
        }
        jooq_wrapper_causes++
        cache_cause++
        next
      }
      if (line ~ /^Execution failed for task \047:[[:alnum:]-]+:generateJooq\047 \(registered by plugin \047net\.firedevops\.firemud\.jooq-conventions\047\)\.$/) {
        task_reference = line
        sub(/^Execution failed for task \047/, "", task_reference)
        sub(/\047 \(registered by plugin \047net\.firedevops\.firemud\.jooq-conventions\047\)\.$/, "", task_reference)
        sub(/:generateJooq$/, "", task_reference)
        if (jooq_execution_module != "" && jooq_execution_module != task_reference) {
          invalid++
        }
        jooq_execution_module = task_reference
        jooq_execution_causes++
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
      if (line ~ /^\[Incubating\] Problems report is available at: file:.*\/build\/reports\/problems\/problems-report\.html$/ ||
          line == ("> If the artifact you are trying to retrieve can be found in the repository but without metadata in " quote "Maven POM" quote " format, you need to adjust the " quote "metadataSources { ... }" quote " of the repository declaration.") ||
          line == "Deprecated Gradle features were used in this build, making it incompatible with Gradle 10." ||
          line == "You can use \047--warning-mode all\047 to show the individual deprecation warnings and determine if they come from your own scripts or plugins." ||
          line == "For more on this, please refer to https://docs.gradle.org/9.8.1/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation." ||
          line ~ /^[0-9]+ actionable tasks: [0-9]+ executed, [0-9]+ from cache$/) {
        next
      }

      if (line ~ /^> Could not resolve all (artifacts|files) for configuration .+\.$/) {
        resolver_roots++
        if (line ~ /(compileClasspath|testCompileClasspath|integrationTestCompileClasspath|crossServiceTestCompileClasspath)/) {
          compile_classpath_roots++
        }
        configuration_name = line
        sub(/^> Could not resolve all (artifacts|files) for configuration /, "", configuration_name)
        sub(/[.]$/, "", configuration_name)
        quote = sprintf("%c", 39)
        if (substr(configuration_name, 1, 1) == quote) {
          configuration_name = substr(configuration_name, 2, length(configuration_name) - 2)
        }
        if (configuration_name ~ /^:[[:alnum:]-]+:jooqCodegen$/) {
          jooq_codegen_roots++
          root_module = configuration_name
          sub(/:jooqCodegen$/, "", root_module)
          if (jooq_module != "" && jooq_module != root_module) {
            invalid++
          }
          jooq_root_module = root_module
        }
        if ((jooq_wrapper_causes || jooq_execution_causes) && configuration_name !~ /^:[[:alnum:]-]+:jooqCodegen$/) {
          invalid++
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
          line ~ /^(> )?There are [0-9]+ more failures with identical causes\.$/) {
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
          (jooq_wrapper_causes &&
            (jooq_wrapper_causes != 1 || jooq_codegen_roots != 1 || resolver_roots != 1 ||
             cache_cause != 1 || jooq_execution_causes || jooq_module == "" || jooq_root_module != jooq_module)) ||
          (jooq_execution_causes &&
            (jooq_execution_causes != 1 || jooq_codegen_roots != 1 || resolver_roots != 1 ||
             cache_cause != 0 || jooq_execution_module == "" || jooq_root_module != jooq_execution_module)) ||
          (!jooq_wrapper_causes && !jooq_execution_causes && jooq_codegen_roots) ||
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

  jooq_execution_module="$(awk '
    BEGIN { quote = sprintf("%c", 39) }
    /^Execution failed for task / {
      line = $0
      prefix = "Execution failed for task " quote
      suffix = quote " (registered by plugin " quote "net.firedevops.firemud.jooq-conventions" quote ")."
      if (index(line, prefix) != 1 || substr(line, length(line) - length(suffix) + 1) != suffix) {
        invalid++
        next
      }
      line = substr(line, length(prefix) + 1)
      line = substr(line, 1, length(line) - length(suffix))
      if (line !~ /^:[[:alnum:]-]+:generateJooq$/) {
        invalid++
        next
      }
      sub(/:generateJooq$/, "", line)
      module = line
      count++
    }
    END {
      if (count != 1 || invalid) exit 1
      print module
    }
  ' "$body_path")" || jooq_execution_module=""

  if [[ -n "$jooq_execution_module" ]] && ! awk -v expected_module="$jooq_execution_module" '
    /^> Task / {
      task = $3
      outcome = $4
      name = task
      sub(/^.*:/, "", name)
      if (name == "generateJooq") {
        jooq_tasks++
        if (task != expected_module ":generateJooq" || outcome != "FAILED") invalid++
      }
      is_compile = name ~ /^compile[A-Z][A-Za-z0-9]*$/
      is_test = !is_compile && name ~ /(^|[A-Za-z0-9])(test|Test)[A-Za-z0-9]*$/
      if (task ~ /^:buildSrc(:|$)/) is_compile = 0
      cached_or_skipped = outcome == "FROM-CACHE" || outcome == "UP-TO-DATE" || outcome == "NO-SOURCE" || outcome == "SKIPPED"
      if ((is_compile || is_test) && !cached_or_skipped) invalid++
    }
    END {
      if (jooq_tasks != 1 || invalid) exit 1
    }
  ' "$log_path"; then
    rm -f -- "$body_path"
    return 1
  fi

  if grep -Fq 'DefaultJavaExecSpec_Decorated' "$body_path" &&
      grep -Fq ':generateJooq' "$body_path" &&
      grep -Eq '^> Task :[[:alnum:]][[:alnum:]-]*:generateJooq([[:space:]]|$)' "$log_path"; then
    # The configuration-cache wrapper retries only before the generator task starts.
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

echo "Recognized Gradle dependency-resolution failure at the bounded pre-substantive boundary; retrying the complete Gradle command once with dependency metadata refresh after ${RETRY_DELAY}s."
sleep "$RETRY_DELAY"
run_gradle_check true "$REFRESH_LOG"
exit "$GRADLE_EXIT"
