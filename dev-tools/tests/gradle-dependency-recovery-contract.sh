#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNNER="$ROOT_DIR/.github/scripts/run-gradle-module-checks.sh"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEMP_DIR"' EXIT

fail() {
  echo "Gradle dependency-recovery contract: $*" >&2
  exit 1
}

new_case() {
  CASE_DIR="$TEMP_DIR/$1"
  mkdir -p "$CASE_DIR/bin"
  cp "$RUNNER" "$CASE_DIR/run-gradle-module-checks.sh"
  cat > "$CASE_DIR/bin/sleep" <<'SLEEP'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${FAKE_SLEEP_LOG:?}"
SLEEP
  chmod +x "$CASE_DIR/bin/sleep"
  : > "$CASE_DIR/calls"
  : > "$CASE_DIR/sleeps"
}

write_wrapper() {
  local body="$1"
  cat > "$CASE_DIR/gradlew" <<EOF_WRAPPER
#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "\$*" >> "\$FAKE_CALL_LOG"
call_count=\$(wc -l < "\$FAKE_CALL_LOG")
$body
EOF_WRAPPER
  chmod +x "$CASE_DIR/gradlew"
}

run_case() {
  local module="$1"
  local expected_status="$2"
  local actual_status=0
  if (
    cd "$CASE_DIR"
    export FAKE_CALL_LOG="$CASE_DIR/calls"
    export FAKE_SLEEP_LOG="$CASE_DIR/sleeps"
    export PATH="$CASE_DIR/bin:$PATH"
    bash ./run-gradle-module-checks.sh "$module" > "$CASE_DIR/output" 2>&1
  ); then
    actual_status=0
  else
    actual_status=$?
  fi
  [[ "$actual_status" -eq "$expected_status" ]] || fail "$CASE_DIR expected exit $expected_status, got $actual_status"
}

assert_call_count() {
  local expected="$1"
  local actual
  actual="$(wc -l < "$CASE_DIR/calls")"
  [[ "$actual" -eq "$expected" ]] || fail "$CASE_DIR expected $expected Gradle call(s), got $actual"
}

assert_call() {
  local line="$1"
  local expected="$2"
  local actual
  actual="$(sed -n "${line}p" "$CASE_DIR/calls")"
  [[ "$actual" == "$expected" ]] || fail "$CASE_DIR Gradle call $line differed: $actual"
}

assert_output_contains() {
  local expected="$1"
  grep -Fq -- "$expected" "$CASE_DIR/output" || fail "$CASE_DIR output omitted: $expected"
}

assert_no_retry() {
  assert_call_count 1
  [[ ! -s "$CASE_DIR/sleeps" ]] || fail "$CASE_DIR unexpectedly delayed before retry"
}

# The successful cache path keeps the full-check invocation unchanged and does
# not ask repositories to revalidate every dependency.
new_case normal-success
write_wrapper 'echo "> Task :buildSrc:compileKotlin FROM-CACHE"; echo "normal-complete"; exit 0'
run_case common-web-support 0
assert_call_count 1
assert_call 1 "--max-workers=2 -Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8 :common-web-support:check -PfullCheck"
[[ ! -s "$CASE_DIR/sleeps" ]] || fail "normal success slept"

# Match the retained Netty failure shape: cached buildSrc compilation is not a
# substantive module compile/test, while the classpath fetch reset is retryable.
new_case netty-reset
# Generated wrapper code intentionally expands these references at execution.
# shellcheck disable=SC2016
write_wrapper 'if [[ "$call_count" -eq 1 ]]; then
  cat <<"LOG"
> Task :buildSrc:compileKotlin FROM-CACHE
> Task :buildSrc:compileJava NO-SOURCE
FAILURE: Build failed with an exception.
* What went wrong:
1 problem was found storing the configuration cache.
- Task `:load-testing:compileJava` of type `org.gradle.api.tasks.compile.JavaCompile`: error writing value of type '\''org.gradle.api.internal.artifacts.configurations.DefaultResolvableConfiguration'\''
See the complete report at file:///tmp/configuration-cache/report
Configuration cache state could not be cached: field `classpath` of task `:load-testing:compileJava` of type `org.gradle.api.tasks.compile.JavaCompile`: error writing value of type '\''org.gradle.api.internal.artifacts.configurations.DefaultResolvableConfiguration'\''
> Could not resolve all files for configuration ':load-testing:compileClasspath'.
> Could not resolve io.netty:netty-codec-socks:4.2.18.Final.
> Could not GET '\''https://repo.maven.apache.org/maven2/io/netty/netty-codec-socks/4.2.18.Final/netty-codec-socks-4.2.18.Final.pom'\''.
> Connection reset
* Try:
> Run with --stacktrace option for details.
BUILD FAILED in 48s
LOG
  exit 1
fi
echo "refresh-complete"; exit 0'
run_case load-testing 0
assert_call_count 2
assert_call 1 "--max-workers=2 -Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8 :load-testing:check -PfullCheck"
assert_call 2 "--refresh-dependencies --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8 :load-testing:check -PfullCheck"
[[ "$(cat "$CASE_DIR/sleeps")" == 2 ]] || fail "connection reset did not use one short delay"
assert_output_contains "Connection reset"
assert_output_contains "refresh-complete"

# Preserve the c515-style pinned missing-artifact recovery path without
# claiming the miss is stale: a passing refreshed full check is still required.
new_case absent-pinned-artifact
# shellcheck disable=SC2016
write_wrapper 'if [[ "$call_count" -eq 1 ]]; then
  cat <<"LOG"
> Task :buildSrc:compileKotlin
> Task :buildSrc:compileJava NO-SOURCE
FAILURE: Build failed with an exception.
* What went wrong:
1 problem was found storing the configuration cache.
Configuration cache state could not be cached: field `classpath` of task `:common-data-runtime:compileJava` of type `org.gradle.api.tasks.compile.JavaCompile`: error writing value of type '\''org.gradle.api.internal.artifacts.configurations.DefaultResolvableConfiguration'\''
> Could not resolve all files for configuration :common-data-runtime:compileClasspath.
> Could not find spring-boot-starter-jdbc-4.1.1.jar (org.springframework.boot:spring-boot-starter-jdbc:4.1.1).
  Searched in the following locations:
    - https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-jdbc/4.1.1/spring-boot-starter-jdbc-4.1.1.jar
* Try:
> Run with --stacktrace option for details.
BUILD FAILED in 4s
LOG
  exit 1
fi
echo "refreshed-pinned-artifact-resolved"; exit 0'
run_case account-service 0
assert_call_count 2
assert_call 2 "--refresh-dependencies --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8 :account-service:check -PfullCheck"
[[ "$(cat "$CASE_DIR/sleeps")" == 0 ]] || fail "cached absence added a transient-error delay"
assert_output_contains "Could not find spring-boot-starter-jdbc-4.1.1.jar (org.springframework.boot:spring-boot-starter-jdbc:4.1.1)."
assert_output_contains "refreshed-pinned-artifact-resolved"

# The resolver retry is bounded and observes a logged numeric Retry-After.
new_case http-429
# shellcheck disable=SC2016
write_wrapper 'if [[ "$call_count" -eq 1 ]]; then
  cat <<"LOG"
FAILURE: Build failed with an exception.
* What went wrong:
> Could not resolve all artifacts for configuration '\''classpath'\''.
> Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.4.10.
> Could not GET '\''https://plugins.gradle.org/m2/org/jetbrains/kotlin/kotlin-gradle-plugin-api/2.4.10/kotlin-gradle-plugin-api-2.4.10.pom'\''.
Received status code 429 from server: Too Many Requests
Retry-After: 5
* Try:
> Run with --stacktrace option for details.
BUILD FAILED in 1s
LOG
  exit 1
fi
echo "refreshed-http-429-resolved"; exit 0'
run_case common-web-support 0
assert_call_count 2
[[ "$(cat "$CASE_DIR/sleeps")" == 5 ]] || fail "HTTP 429 did not observe Retry-After"

# A Retry-After beyond the short retry budget fails closed without waiting.
new_case long-retry-after
write_wrapper 'cat <<"LOG"
FAILURE: Build failed with an exception.
* What went wrong:
> Could not resolve all artifacts for configuration '\''classpath'\''.
> Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.4.10.
> Could not GET '\''https://plugins.gradle.org/m2/org/jetbrains/kotlin/kotlin-gradle-plugin-api/2.4.10/kotlin-gradle-plugin-api-2.4.10.pom'\''.
Received status code 429 from server: Too Many Requests
Retry-After: 60
* Try:
> Run with --stacktrace option for details.
BUILD FAILED in 1s
LOG
exit 1'
run_case common-web-support 1
assert_no_retry

# Bounded numeric Retry-After parsing canonicalizes base-ten input before any
# arithmetic, accepts 08, and refuses values outside the configured window.
run_retry_after_case() {
  local case_name="$1"
  local retry_after="$2"
  local expected_status="$3"
  local expected_delay="$4"

  new_case "$case_name"
  write_wrapper "if [[ \"\$call_count\" -eq 1 ]]; then
cat <<'LOG'
FAILURE: Build failed with an exception.
* What went wrong:
> Could not resolve all artifacts for configuration 'classpath'.
> Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.4.10.
> Could not GET 'https://plugins.gradle.org/m2/org/jetbrains/kotlin/kotlin-gradle-plugin-api/2.4.10/kotlin-gradle-plugin-api-2.4.10.pom'.
Received status code 429 from server: Too Many Requests
Retry-After: $retry_after
* Try:
> Run with --stacktrace option for details.
BUILD FAILED in 1s
LOG
exit 1
fi
echo retried-after-retry-after; exit 0"
  run_case common-web-support "$expected_status"
  if [[ "$expected_status" -eq 0 ]]; then
    assert_call_count 2
    [[ "$(cat "$CASE_DIR/sleeps")" == "$expected_delay" ]] || fail "$case_name used the wrong bounded delay"
  else
    assert_no_retry
  fi
}

run_retry_after_case leading-zero-08 08 0 8
run_retry_after_case retry-after-30 30 0 30
run_retry_after_case retry-after-31 31 1 ''
run_retry_after_case retry-after-huge 18446744073709551616 1 ''
run_retry_after_case retry-after-unbounded 00000000000000000000000000000000000000000000000000000000 1 ''
run_retry_after_case retry-after-malformed 1e3 1 ''
run_retry_after_case retry-after-date 'Wed, 21 Oct 2015 07:28:00 GMT' 1 ''

# Real test execution and compiler diagnostics must retain the first failure.
new_case executed-test
write_wrapper 'cat <<"LOG"
> Task :common-web-support:test
FAILURE: Build failed with an exception.
* What went wrong:
> Could not resolve all files for configuration '\''runtimeClasspath'\''.
> Could not find org.example:missing-lib:1.2.3.
  Searched in the following locations:
    - https://repo.maven.apache.org/maven2/org/example/missing-lib/1.2.3/missing-lib-1.2.3.pom
* Try:
> Run with --stacktrace option for details.
BUILD FAILED in 1s
LOG
exit 1'
run_case common-web-support 1
assert_no_retry

new_case compiler-failure
write_wrapper 'cat <<"LOG"
> Task :common-web-support:compileJava FAILED
error: cannot find symbol
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task '\'':common-web-support:compileJava'\''.
Could not resolve all files for configuration '\''compileClasspath'\''.
Could not find org.example:missing-lib:1.2.3.
Searched in the following locations:
  - https://repo.maven.apache.org/maven2/org/example/missing-lib/1.2.3/missing-lib-1.2.3.pom
* Try:
> Run with --stacktrace option for details.
BUILD FAILED in 1s
LOG
exit 1'
run_case common-web-support 1
assert_no_retry

# A genuine test failure remains a failure even when a dependency-shaped
# message appears later in the retained output.
new_case test-failure
write_wrapper 'cat <<"LOG"
> Task :common-web-support:test FAILED
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task '\'':common-web-support:test'\''.
There were failing tests.
> Could not resolve all files for configuration '\''runtimeClasspath'\''.
> Could not find org.example:missing-lib:1.2.3.
Searched in the following locations:
  - https://repo.maven.apache.org/maven2/org/example/missing-lib/1.2.3/missing-lib-1.2.3.pom
* Try:
> Run with --stacktrace option for details.
BUILD FAILED in 1s
LOG
exit 1'
run_case common-web-support 1
assert_no_retry

# Unrelated configuration errors and interrupted Gradle processes do not retry.
new_case unrelated-configuration
write_wrapper 'printf "%s\\n" "FAILURE: Build failed with an exception." "* What went wrong:" "Could not create task :common-web-support:check." "* Try:" "BUILD FAILED"; exit 1'
run_case common-web-support 1
assert_no_retry

# A dependency-shaped diagnostic outside the final Gradle cause cannot trigger
# refresh when the actual fatal body is an unrelated configuration failure.
new_case dependency-text-outside-failure
# shellcheck disable=SC2016
write_wrapper 'cat <<"LOG"
> Could not resolve all artifacts for configuration '\''classpath'\''.
> Could not find org.example:old-missing:1.2.3.
Searched in the following locations:
https://repo.maven.apache.org/maven2/org/example/old-missing/1.2.3/old-missing-1.2.3.pom
FAILURE: Build failed with an exception.
* What went wrong:
Could not create task :common-web-support:check because of an unsupported plugin configuration.
* Try:
> Run with --stacktrace option for details.
BUILD FAILED in 1s
LOG
exit 1'
run_case common-web-support 1
assert_no_retry

# A second unrelated cache/configuration cause invalidates an otherwise
# dependency-shaped body.
new_case mixed-resolution-and-cache-failure
# shellcheck disable=SC2016
write_wrapper 'cat <<"LOG"
FAILURE: Build failed with an exception.
* What went wrong:
1 problem was found storing the configuration cache.
Configuration cache state could not be cached: field `classpath` of task `:common-data-runtime:compileJava` of type `org.gradle.api.tasks.compile.JavaCompile`: error writing value of type '\''org.gradle.api.internal.artifacts.configurations.DefaultResolvableConfiguration'\''
> Could not resolve all files for configuration :common-data-runtime:compileClasspath.
> Could not find spring-boot-starter-jdbc-4.1.1.jar (org.springframework.boot:spring-boot-starter-jdbc:4.1.1).
  Searched in the following locations:
    - https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-jdbc/4.1.1/spring-boot-starter-jdbc-4.1.1.jar
Could not cache task `:other:processResources` due unsupported configuration value.
* Try:
> Run with --stacktrace option for details.
BUILD FAILED in 1s
LOG
exit 1'
run_case common-web-support 1
assert_no_retry

# A canceled process never enters the dependency classifier or retries.
new_case interrupted
write_wrapper 'cat <<"LOG"
FAILURE: Build failed with an exception.
* What went wrong:
> Build was cancelled by user.
> Could not resolve all artifacts for configuration '\''classpath'\''.
> Could not find org.jetbrains.kotlin:kotlin-stdlib:2.4.10.
Searched in the following locations:
  - https://plugins.gradle.org/m2/org/jetbrains/kotlin/kotlin-stdlib/2.4.10/kotlin-stdlib-2.4.10.pom
* Try:
> Run with --stacktrace option for details.
BUILD FAILED
LOG
exit 130'
run_case common-web-support 130
assert_no_retry

# A compile task that failed while resolving its input classpath may retry;
# compiler diagnostics with dependency-shaped text remain a final failure.
new_case compile-input-resolution
# shellcheck disable=SC2016
write_wrapper 'if [[ "$call_count" -eq 1 ]]; then
cat <<"LOG"
> Task :common-web-support:compileJava FAILED
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task '\'':common-web-support:compileJava'\''.
> Could not resolve all files for configuration '\'':common-web-support:compileClasspath'\''.
> Could not find org.example:missing-lib:1.2.3.
  Searched in the following locations:
    - https://repo.maven.apache.org/maven2/org/example/missing-lib/1.2.3/missing-lib-1.2.3.pom
* Try:
> Run with --stacktrace option for details.
BUILD FAILED in 1s
LOG
exit 1
fi
echo refreshed-compile-classpath; exit 0'
run_case common-web-support 0
assert_call_count 2
assert_output_contains "Execution failed for task ':common-web-support:compileJava'."

# Retry failure is the final check result; the first attempt remains in output.
new_case second-attempt-failure
# shellcheck disable=SC2016
write_wrapper 'if [[ "$call_count" -eq 1 ]]; then
  cat <<"LOG"
FAILURE: Build failed with an exception.
* What went wrong:
> Could not resolve all artifacts for configuration '\''classpath'\''.
> Could not find org.jetbrains.kotlin:kotlin-stdlib:2.4.10.
Searched in the following locations:
  - https://plugins.gradle.org/m2/org/jetbrains/kotlin/kotlin-stdlib/2.4.10/kotlin-stdlib-2.4.10.pom
* Try:
> Run with --stacktrace option for details.
BUILD FAILED
LOG
  exit 1
fi
echo "refresh-attempt-still-failed"; exit 23'
run_case common-web-support 23
assert_call_count 2
assert_output_contains "Could not find org.jetbrains.kotlin:kotlin-stdlib:2.4.10."
assert_output_contains "refresh-attempt-still-failed"

# Invalid module text is rejected before invoking the Gradle wrapper.
new_case invalid-module
write_wrapper 'exit 0'
run_case '../common-web-support' 64
assert_call_count 0

# A failed output tee invalidates classification; do not attempt a refresh.
new_case log-capture-failure
write_wrapper 'printf "%s\\n" "FAILURE: Build failed with an exception." "* What went wrong:" "Could not resolve all artifacts for configuration classpath." "Could not find org.jetbrains.kotlin:kotlin-stdlib:2.4.10." "Searched in the following locations:" "https://plugins.gradle.org/m2/org/jetbrains/kotlin/kotlin-stdlib/2.4.10/kotlin-stdlib-2.4.10.pom" "BUILD FAILED"; exit 1'
cat > "$CASE_DIR/bin/tee" <<'TEE'
#!/usr/bin/env bash
cat
exit 1
TEE
chmod +x "$CASE_DIR/bin/tee"
run_case common-web-support 125
assert_no_retry

echo "Gradle dependency-recovery contract passed."
