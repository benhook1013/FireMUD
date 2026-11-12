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

      # buildSrc plugin resolution may wrap a pinned dependency HTTP 429 in
      # configuration, repository-disablement and parent-POM context. These
      # lines are admitted only together with that narrow boundary below.
      if (line == "A problem occurred configuring project \047:buildSrc\047.") {
        buildsrc_configuration++
        next
      }
      if (line ~ /^buildscript of project \047:buildSrc\047( > [[:alnum:]_.-]+:[[:alnum:]_.-]+:[0-9][[:alnum:]_.-]*)*$/) {
        buildsrc_context++
        next
      }
      if (line == "> Repository Gradle Central Plugin Repository is disabled due to earlier error below:") {
        buildsrc_repository++
        next
      }
      if (line ~ /^> Could not parse POM https:\/\/plugins\.gradle\.org\/m2\/org\/jetbrains\/kotlin\/kotlin-gradle-plugin\/[0-9][[:alnum:]_.-]*\/kotlin-gradle-plugin-[0-9][[:alnum:]_.-]*\.pom$/) {
        buildsrc_pom++
        next
      }
      if (line == "> Received status code 429 from server: Too Many Requests") {
        buildsrc_http_429++
        transient++
        next
      }
      if (line == "> Repository MavenRepo is disabled due to earlier error below:") {
        mavenrepo_disabled++
        next
      }
      if (line ~ /^> Connect to repo\.maven\.apache\.org:443 \[repo\.maven\.apache\.org\/[0-9a-fA-F:.]+\] failed: Connect timed out$/) {
        protoc_connect_timeout++
        transient++
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
      # Gradle 9.9 can resolve annotation processors while serializing the
      # compiler-fork predicate, before it starts the Java compile task.
      if (line ~ /^Configuration cache state could not be cached: field `annotationProcessorPath` of `org\.gradle\.api\.tasks\.compile\.CompileOptions_Decorated` bean found in captured state from method `org\.gradle\.api\.internal\.tasks\.compile\.CompilerForkUtils\.doNotCacheIfForkingViaExecutable\$[[:xdigit:]]+` of lambda of type `org\.gradle\.api\.internal\.lambdas\.SerializableLambdas\$SerializableSpec` returning `boolean` found in field `spec` of `org\.gradle\.api\.internal\.tasks\.execution\.SelfDescribingSpec` bean found in doNotCacheIf specs of task `:[[:alnum:]-]+:compileJava` of type `org\.gradle\.api\.tasks\.compile\.JavaCompile`: error writing value of type \047org\.gradle\.api\.internal\.artifacts\.configurations\.DefaultLegacyConfiguration\047$/) {
        module = line
        sub(/^.* specs of task `/, "", module)
        sub(/:compileJava`.*$/, "", module)
        expected_wrapped_configuration = module ":annotationProcessor"
        wrapped_resolution_causes++
        wrapped_cache_cause = 1
        cache_cause++
        next
      }
      # Protobuf extracts dependency protos before generation or compilation.
      if (line ~ /^Execution failed for task \047:[[:alnum:]-]+:extractIncludeProto\047 \(registered by plugin \047com\.google\.protobuf\047\)\.$/) {
        module = line
        sub(/^Execution failed for task \047/, "", module)
        sub(/:extractIncludeProto\047.*$/, "", module)
        expected_wrapped_configuration = module ":compileProtoPath"
        wrapped_resolution_causes++
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
          line ~ /^For more on this, please refer to https:\/\/docs\.gradle\.org\/[0-9]+\.[0-9]+\.[0-9]+\/userguide\/command_line_interface\.html#sec:command_line_warnings in the Gradle documentation\.$/ ||
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
        resolved_configuration = configuration_name
        if (configuration_name == "classpath") buildsrc_classpath++
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
        if (line ~ /^> Could not resolve com\.google\.protobuf:protoc:[0-9][[:alnum:]_.-]*\.$/) {
          protoc_coordinates++
          protoc_version = line
          sub(/^> Could not resolve com\.google\.protobuf:protoc:/, "", protoc_version)
          sub(/\.$/, "", protoc_version)
          protoc_pom_url = "https://repo.maven.apache.org/maven2/com/google/protobuf/protoc/" protoc_version "/protoc-" protoc_version ".pom"
        }
        coordinates++
        next
      }
      if (line ~ /^> Could not (get resource|GET|HEAD) [\047\042]?https?:\/\// ||
          line ~ /^> Could not GET https?:\/\//) {
        if (protoc_pom_url != "") {
          if (line == "> Could not get resource " quote protoc_pom_url quote ".") protoc_pom_resource++
          if (line == "> Could not GET " quote protoc_pom_url quote ".") protoc_pom_get++
        }
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
          ((mavenrepo_disabled || protoc_connect_timeout) &&
            (mavenrepo_disabled != 1 || protoc_connect_timeout != 1 ||
             protoc_coordinates != 1 || protoc_pom_resource != 1 || protoc_pom_get != 1 ||
             coordinates != 2 || repository_requests != 2 || searched ||
             jooq_wrapper_causes != 1)) ||
          ((buildsrc_configuration || buildsrc_context || buildsrc_repository || buildsrc_pom || buildsrc_http_429) &&
            (buildsrc_configuration != 1 || buildsrc_context == 0 ||
             resolver_roots != 1 || buildsrc_classpath != 1 || buildsrc_http_429 == 0 ||
             cache_cause || compile_task_failure || searched)) ||
          (cache_summary && (cache_cause == 0 || cache_cause > 2)) ||
          (compile_task_failure && compile_classpath_roots == 0) ||
          (wrapped_resolution_causes &&
            (wrapped_resolution_causes != 1 || resolver_roots != 1 ||
             expected_wrapped_configuration != resolved_configuration ||
             cache_cause != wrapped_cache_cause || compile_task_failure ||
             jooq_wrapper_causes || jooq_execution_causes)) ||
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
      if (wrapped_resolution_causes) print "before-compile"
      exit 0
    }
  ' "$log_path"
}

is_dependency_resolution_failure() {
  local log_path="$1"
  local body_path
  local resolution_boundary
  local substantive_status=0

  body_path="$(mktemp "${TEMP_DIR%/}/failure-body.XXXXXX")" || return 1
  if ! failure_body "$log_path" > "$body_path"; then
    rm -f -- "$body_path"
    return 1
  fi
  if ! resolution_boundary="$(classify_failure_body "$body_path")"; then
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
    1) return 0 ;;
    2) [[ "$resolution_boundary" != before-compile ]] ;;
    *) return 1 ;;
  esac
}

retry_delay_seconds() {
  local log_path="$1"
  local retry_after
  local delay

  retry_after="$(failure_body "$log_path" | sed -nE 's/^.*[Rr]etry-[Aa]fter:[[:space:]]*(.*)$/\1/p')" || return 1
  if [[ -z "$retry_after" ]]; then
    if failure_body "$log_path" | grep -Eq 'Connection reset|Connection timed out|Connect timed out|SocketTimeoutException|Received status code 429 from server|Received status code 5[0-9][0-9] from server'; then
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

# Recover only a wrapper download failure before any Gradle task starts. The
# official API is another transport for the same pinned release bytes, never a
# version fallback or permission to retry compilation/tests.
recover_wrapper_distribution() {
  local root_dir
  root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  grep -Eq '^Downloading https://services[.]gradle[.]org/distributions/gradle-[0-9]+[.][0-9]+[.][0-9]+-bin[.]zip$' "$NORMAL_LOG" || return 1
  python3 - "$root_dir" "$NORMAL_LOG" "${GRADLE_ARGS[@]}" <<'PY_DISTRIBUTION'
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

root, log = Path(sys.argv[1]), Path(sys.argv[2])

def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()

def recover():
    # A different CLI cache selection or custom layout is not this recovery path.
    if any(arg == "-g" or arg.startswith("-g") or arg.startswith("--gradle-user-home") for arg in sys.argv[3:]):
        return False
    if log.stat().st_size > 1048576:
        return False
    text = log.read_text(errors="replace")
    text = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", text)
    if re.search(r"^> Task |BUILD FAILED|Compilation failed|There were failing tests|\berror:", text, re.MULTILINE):
        return False
    props = {}
    properties = root / "gradle/wrapper/gradle-wrapper.properties"
    if properties.stat().st_size > 65536:
        return False
    for line in properties.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, value = line.split("=", 1)
        if key in props:
            return False
        props[key] = value.replace("\\:", ":")
    url = props.get("distributionUrl", "")
    match = re.fullmatch(r"https://services\.gradle\.org/distributions/gradle-([0-9]+\.[0-9]+\.[0-9]+)-bin\.zip", url)
    checksum = props.get("distributionSha256Sum", "")
    if not match or not re.fullmatch(r"[0-9a-f]{64}", checksum):
        return False
    if any(props.get(key) != value for key, value in {
        "distributionBase": "GRADLE_USER_HOME", "distributionPath": "wrapper/dists",
        "zipStoreBase": "GRADLE_USER_HOME", "zipStorePath": "wrapper/dists",
    }.items()):
        return False
    failure = re.compile(r'(?:Exception in thread "main" )?java\.io\.IOException: Server returned HTTP response code: (?:429|500|502|503|504) for URL: ' + re.escape(url))
    if not any(failure.fullmatch(line) for line in text.splitlines()):
        return False
    # Refuse mixed top-level failures and downloads for a different distribution.
    for line in text.splitlines():
        if line.startswith("Downloading ") and line != f"Downloading {url}":
            return False
        if re.match(r'(?:Exception in thread "[^"]+" )?(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:Exception|Error):', line) and not failure.fullmatch(line):
            return False
    version = match[1]
    name = f"gradle-{version}-bin.zip"
    cache_root = Path(os.environ.get("GRADLE_USER_HOME") or Path.home() / ".gradle")
    # Match Gradle PathAssembler's URL-derived cache key; SHA-256 verifies bytes.
    number = int.from_bytes(hashlib.md5(url.encode()).digest(), "big")
    cache_key = ""
    while number:
        number, remainder = divmod(number, 36)
        cache_key = "0123456789abcdefghijklmnopqrstuvwxyz"[remainder] + cache_key
    target = cache_root / "wrapper/dists" / name.removesuffix(".zip") / cache_key / name
    if target.is_symlink():
        return False
    if target.exists():
        return digest(target) == checksum
    with tempfile.TemporaryDirectory(prefix="distribution-", dir=log.parent) as temporary:
        metadata, archive = Path(temporary) / "release.json", Path(temporary) / name
        common = ["curl", "-fsSL", "--retry", "2", "--retry-delay", "2", "--connect-timeout", "10"]
        subprocess.run(common + ["--max-time", "20", "--retry-max-time", "25", "--max-filesize", "2097152",
            "-H", "Accept: application/vnd.github+json", "-o", str(metadata),
            f"https://api.github.com/repos/gradle/gradle-distributions/releases/tags/v{version}"],
            check=True, timeout=30)
        release = json.loads(metadata.read_text())
        if not isinstance(release, dict) or not isinstance(release.get("assets"), list):
            return False
        if any(not isinstance(asset, dict) for asset in release["assets"]):
            return False
        assets = [asset for asset in release["assets"] if asset.get("name") == name]
        if release.get("tag_name") != f"v{version}" or release.get("draft") or release.get("prerelease") or len(assets) != 1:
            return False
        asset = assets[0]
        asset_id, size = asset.get("id"), asset.get("size")
        if type(asset_id) is not int or asset_id <= 0 or type(size) is not int or not 0 < size <= 536870912:
            return False
        if asset.get("digest") != f"sha256:{checksum}":
            return False
        subprocess.run(common + ["--max-time", "60", "--retry-max-time", "70", "--max-filesize", str(size),
            "-H", "Accept: application/octet-stream", "-o", str(archive),
            f"https://api.github.com/repos/gradle/gradle-distributions/releases/assets/{asset_id}"],
            check=True, timeout=80)
        if archive.stat().st_size != size or digest(archive) != checksum:
            return False
        target.parent.mkdir(parents=True, exist_ok=True)
        # Publish a complete verified archive atomically without replacing an
        # existing archive. The ordinary wrapper still verifies and extracts it.
        with tempfile.NamedTemporaryFile(prefix="verified-", dir=target.parent, delete=False) as output:
            candidate = Path(output.name)
            try:
                with archive.open("rb") as source:
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
                try:
                    os.link(candidate, target)
                except FileExistsError:
                    if target.is_symlink() or digest(target) != checksum:
                        return False
            finally:
                candidate.unlink(missing_ok=True)
        print(f"Verified pinned Gradle {version} distribution via official release asset API; SHA256={checksum}.")
        return True

try:
    recovered = recover()
except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as failure:
    print(f"Pinned Gradle distribution transport recovery unavailable ({type(failure).__name__}); preserving original build failure.", file=sys.stderr)
    recovered = False
sys.exit(0 if recovered else 1)
PY_DISTRIBUTION
}

run_gradle_check false "$NORMAL_LOG"
if [[ "$GRADLE_EXIT" -eq 0 ]]; then
  exit 0
fi

if [[ "$GRADLE_EXIT" -ne 1 ]]; then
  exit "$GRADLE_EXIT"
fi

if recover_wrapper_distribution; then
  echo "Recovered the pinned wrapper distribution before task execution; retrying the original Gradle command once."
  run_gradle_check false "$REFRESH_LOG"
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
