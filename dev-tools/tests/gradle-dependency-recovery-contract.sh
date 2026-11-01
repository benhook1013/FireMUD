#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE_RUNNER="$ROOT_DIR/.github/scripts/run-gradle-module-checks.sh"
RECOVERY_RUNNER="$ROOT_DIR/dev-tools/validation/run-gradle-with-dependency-recovery.sh"
COMPOSE_JARS="$ROOT_DIR/dev-tools/build-compose-service-jars.sh"
COMPILE_FAILURE_COLLECTOR="$ROOT_DIR/dev-tools/validation/collect-gradle-compile-failure.py"
COMPILE_FAILURE_INIT="$ROOT_DIR/dev-tools/validation/compile-failure-facts.init.gradle"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEMP_DIR"' EXIT

fail() {
  echo "Gradle dependency-recovery contract: $*" >&2
  if [[ -f "${CASE_DIR:-}/output" ]]; then
    tail -n 80 "$CASE_DIR/output" >&2
  fi
  exit 1
}

new_case() {
  CASE_DIR="$TEMP_DIR/$1"
  mkdir -p "$CASE_DIR/bin" "$CASE_DIR/.github/scripts" "$CASE_DIR/dev-tools/validation"
  cp "$MODULE_RUNNER" "$CASE_DIR/.github/scripts/run-gradle-module-checks.sh"
  cp "$RECOVERY_RUNNER" "$CASE_DIR/dev-tools/validation/run-gradle-with-dependency-recovery.sh"
  cp "$COMPILE_FAILURE_COLLECTOR" "$CASE_DIR/dev-tools/validation/collect-gradle-compile-failure.py"
  cp "$COMPILE_FAILURE_INIT" "$CASE_DIR/dev-tools/validation/compile-failure-facts.init.gradle"
  cp "$COMPOSE_JARS" "$CASE_DIR/dev-tools/build-compose-service-jars.sh"
  cat > "$CASE_DIR/dev-tools/validation/run-locked-gradle.sh" <<'LOCKED'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${FAKE_LOCK_CALL_LOG:?}"
exec ./gradlew "$@"
LOCKED
  chmod +x "$CASE_DIR/dev-tools/validation/run-locked-gradle.sh"
  cat > "$CASE_DIR/bin/sleep" <<'SLEEP'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${FAKE_SLEEP_LOG:?}"
SLEEP
  chmod +x "$CASE_DIR/bin/sleep"
  : > "$CASE_DIR/calls"
  : > "$CASE_DIR/diagnostic-calls"
  : > "$CASE_DIR/locked-calls"
  : > "$CASE_DIR/sleeps"
}

write_wrapper() {
  local body="$1"
  cat > "$CASE_DIR/gradlew" <<EOF_WRAPPER
#!/usr/bin/env bash
set -euo pipefail
if [[ " \$* " =~ [[:space:]]:[a-z0-9]+(-[a-z0-9]+)*:firemudCompileFailureFacts[[:space:]] ]]; then
  printf '%s\n' "\$*" >> "\$FAKE_DIAGNOSTIC_CALL_LOG"
  echo 'FIREMUD_COMPILE_FACTS={"attribution":"fixture-only"}'
  exit 0
fi
printf '%s\\n' "\$*" >> "\$FAKE_CALL_LOG"
call_count=\$(wc -l < "\$FAKE_CALL_LOG")
$body
EOF_WRAPPER
  chmod +x "$CASE_DIR/gradlew"
}

# shellcheck disable=SC2030,SC2031
run_case() {
  local module="$1"
  local expected_status="$2"
  local actual_status=0
  if (
    cd "$CASE_DIR"
    export CASE_DIR
    export FAKE_CALL_LOG="$CASE_DIR/calls"
    export FAKE_DIAGNOSTIC_CALL_LOG="$CASE_DIR/diagnostic-calls"
    export FAKE_LOCK_CALL_LOG="$CASE_DIR/locked-calls"
    export FAKE_SLEEP_LOG="$CASE_DIR/sleeps"
    export PATH="$CASE_DIR/bin:$PATH"
    bash ./.github/scripts/run-gradle-module-checks.sh "$module" > "$CASE_DIR/output" 2>&1
  ); then
    actual_status=0
  else
    actual_status=$?
  fi
  [[ "$actual_status" -eq "$expected_status" ]] || fail "$CASE_DIR expected exit $expected_status, got $actual_status"
}

# shellcheck disable=SC2030,SC2031
run_compose_case() {
  local expected_status="$1"
  local actual_status=0
  if (
    cd "$CASE_DIR"
    export CASE_DIR
    export FAKE_CALL_LOG="$CASE_DIR/calls"
    export FAKE_DIAGNOSTIC_CALL_LOG="$CASE_DIR/diagnostic-calls"
    export FAKE_LOCK_CALL_LOG="$CASE_DIR/locked-calls"
    export FAKE_SLEEP_LOG="$CASE_DIR/sleeps"
    export PATH="$CASE_DIR/bin:$PATH"
    bash ./dev-tools/build-compose-service-jars.sh > "$CASE_DIR/output" 2>&1
  ); then
    actual_status=0
  else
    actual_status=$?
  fi
  [[ "$actual_status" -eq "$expected_status" ]] || fail "$CASE_DIR expected compose exit $expected_status, got $actual_status"
}

assert_call_count() {
  local expected="$1"
  local actual
  actual="$(wc -l < "$CASE_DIR/calls")"
  [[ "$actual" -eq "$expected" ]] || fail "$CASE_DIR expected $expected Gradle call(s), got $actual"
}

assert_diagnostic_count() {
  local expected="$1"
  local actual
  actual="$(wc -l < "$CASE_DIR/diagnostic-calls")"
  [[ "$actual" -eq "$expected" ]] || fail "$CASE_DIR expected $expected diagnostic facts invocation(s), got $actual"
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
assert_call 1 "--max-workers=2 -Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8 :common-web-support:check -PfullCheck --profile --info --console=plain"
[[ ! -s "$CASE_DIR/sleeps" ]] || fail "normal success slept"

# Match the retained Netty failure shape: cached buildSrc compilation is not a
# substantive module compile/test, while the classpath fetch reset is retryable.
new_case netty-reset
# Generated wrapper code intentionally expands these references at execution.
# shellcheck disable=SC2016
write_wrapper 'if [[ "$call_count" -eq 1 ]]; then
  cat <<"LOG"
> Task :buildSrc:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :buildSrc:compileKotlin FROM-CACHE
> Task :buildSrc:compileJava NO-SOURCE
> Task :buildSrc:compileGroovy NO-SOURCE
> Task :buildSrc:pluginDescriptors
> Task :buildSrc:processResources
> Task :buildSrc:classes
> Task :buildSrc:jar
FAILURE: Build failed with an exception.

* What went wrong:
1 problem was found storing the configuration cache.
- Task `:load-testing:compileJava` of type `org.gradle.api.tasks.compile.JavaCompile`: error writing value of type '\''org.gradle.api.internal.artifacts.configurations.DefaultResolvableConfiguration'\''

See the complete report at file:///tmp/firemud-gradle/build/reports/configuration-cache/55dsxnjhf0f7e758w7006zyz/3tzsi60zc8z4y1sxqe2cppdh9/configuration-cache-report.html

[Incubating] Problems report is available at: file:///tmp/firemud-gradle/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.

You can use '\''--warning-mode all'\'' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/9.8.1/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.
4 actionable tasks: 3 executed, 1 from cache
Configuration cache state could not be cached: field `classpath` of task `:load-testing:compileJava` of type `org.gradle.api.tasks.compile.JavaCompile`: error writing value of type '\''org.gradle.api.internal.artifacts.configurations.DefaultResolvableConfiguration'\''
> Could not resolve all files for configuration '\'':load-testing:compileClasspath'\''.
   > Could not resolve io.netty:netty-codec-socks:4.2.18.Final.
     Required by:
         project '\'':load-testing'\'' > org.springframework.boot:spring-boot-dependencies:4.1.1
      > Could not resolve io.netty:netty-codec-socks:4.2.18.Final.
         > Could not get resource '\''https://repo.maven.apache.org/maven2/io/netty/netty-codec-socks/4.2.18.Final/netty-codec-socks-4.2.18.Final.pom'\''.
            > Could not GET '\''https://repo.maven.apache.org/maven2/io/netty/netty-codec-socks/4.2.18.Final/netty-codec-socks-4.2.18.Final.pom'\''.
               > Got socket exception during request. It might be caused by SSL misconfiguration
                  > Connection reset
> There are 3 more failures with identical causes.

* Try:
> Run with --stacktrace option to get the stack trace.
BUILD FAILED in 48s
LOG
  exit 1
fi
echo "refresh-complete"; exit 0'
run_case load-testing 0
assert_call_count 2
assert_call 1 "--max-workers=2 -Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8 :load-testing:check -PfullCheck --profile --info --console=plain"
assert_call 2 "--refresh-dependencies --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8 :load-testing:check -PfullCheck --profile --info --console=plain"
[[ "$(cat "$CASE_DIR/sleeps")" == 2 ]] || fail "connection reset did not use one short delay"
assert_output_contains "Connection reset"
assert_output_contains "refresh-complete"

# Preserve the c515-style pinned missing-artifact recovery path without
# claiming the miss is stale: a passing refreshed full check is still required.
new_case absent-pinned-artifact
# shellcheck disable=SC2016
write_wrapper 'if [[ "$call_count" -eq 1 ]]; then
  cat <<"LOG"
> Task :buildSrc:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :buildSrc:pluginDescriptors
> Task :buildSrc:processResources
> Task :buildSrc:compileKotlin
> Task :buildSrc:compileJava NO-SOURCE
> Task :buildSrc:compileGroovy NO-SOURCE
> Task :buildSrc:classes
> Task :buildSrc:jar
FAILURE: Build failed with an exception.

* What went wrong:
Configuration cache state could not be cached: field `classpath` of task `:common-data-runtime:compileJava` of type `org.gradle.api.tasks.compile.JavaCompile`: error writing value of type '\''org.gradle.api.internal.artifacts.configurations.DefaultResolvableConfiguration'\''
> Could not resolve all files for configuration '\'':common-data-runtime:compileClasspath'\''.
   > Could not find spring-boot-starter-jdbc-4.1.1.jar (org.springframework.boot:spring-boot-starter-jdbc:4.1.1).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-jdbc/4.1.1/spring-boot-starter-jdbc-4.1.1.jar
   > Could not find spring-boot-starter-data-redis-4.1.1.jar (org.springframework.boot:spring-boot-starter-data-redis:4.1.1).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-data-redis/4.1.1/spring-boot-starter-data-redis-4.1.1.jar
   > Could not find spring-boot-jdbc-4.1.1.jar (org.springframework.boot:spring-boot-jdbc:4.1.1).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-jdbc/4.1.1/spring-boot-jdbc-4.1.1.jar
   > Could not find spring-boot-data-redis-4.1.1.jar (org.springframework.boot:spring-boot-data-redis:4.1.1).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-data-redis/4.1.1/spring-boot-data-redis-4.1.1.jar
   > Could not find spring-boot-sql-4.1.1.jar (org.springframework.boot:spring-boot-sql:4.1.1).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-sql/4.1.1/spring-boot-sql-4.1.1.jar
   > Could not find spring-boot-transaction-4.1.1.jar (org.springframework.boot:spring-boot-transaction:4.1.1).

     Searched in the following locations:
         https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-transaction/4.1.1/spring-boot-transaction-4.1.1.jar
   > Could not find spring-boot-data-commons-4.1.1.jar (org.springframework.boot:spring-boot-data-commons:4.1.1).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-data-commons/4.1.1/spring-boot-data-commons-4.1.1.jar
   > Could not find spring-boot-persistence-4.1.1.jar (org.springframework.boot:spring-boot-persistence:4.1.1).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-persistence/4.1.1/spring-boot-persistence-4.1.1.jar
   > Could not find spring-data-redis-4.1.1.jar (org.springframework.data:spring-data-redis:4.1.1).
1 problem was found storing the configuration cache.
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/org/springframework/data/spring-data-redis/4.1.1/spring-data-redis-4.1.1.jar
   > Could not find spring-data-keyvalue-4.1.1.jar (org.springframework.data:spring-data-keyvalue:4.1.1).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/org/springframework/data/spring-data-keyvalue/4.1.1/spring-data-keyvalue-4.1.1.jar
   > Could not find spring-context-support-7.0.9.jar (org.springframework:spring-context-support:7.0.9).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/org/springframework/spring-context-support/7.0.9/spring-context-support-7.0.9.jar
   > Could not find lettuce-core-7.5.2.RELEASE.jar (io.lettuce:lettuce-core:7.5.2.RELEASE).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/io/lettuce/lettuce-core/7.5.2.RELEASE/lettuce-core-7.5.2.RELEASE.jar
   > Could not find spring-data-commons-4.1.1.jar (org.springframework.data:spring-data-commons:4.1.1).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/org/springframework/data/spring-data-commons/4.1.1/spring-data-commons-4.1.1.jar
   > Could not find spring-messaging-7.0.9.jar (org.springframework:spring-messaging:7.0.9).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/org/springframework/spring-messaging/7.0.9/spring-messaging-7.0.9.jar
   > Could not find spring-jdbc-7.0.9.jar (org.springframework:spring-jdbc:7.0.9).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/org/springframework/spring-jdbc/7.0.9/spring-jdbc-7.0.9.jar
   > Could not find spring-tx-7.0.9.jar (org.springframework:spring-tx:7.0.9).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/org/springframework/spring-tx/7.0.9/spring-tx-7.0.9.jar
- Task `:common-data-runtime:compileJava` of type `org.gradle.api.tasks.compile.JavaCompile`: error writing value of type '\''org.gradle.api.internal.artifacts.configurations.DefaultResolvableConfiguration'\''
   > Could not find spring-oxm-7.0.9.jar (org.springframework:spring-oxm:7.0.9).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/org/springframework/spring-oxm/7.0.9/spring-oxm-7.0.9.jar
   > Could not find HikariCP-7.0.2.jar (com.zaxxer:HikariCP:7.0.2).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/com/zaxxer/HikariCP/7.0.2/HikariCP-7.0.2.jar
   > Could not find redis-authx-core-0.1.1-beta2.jar (redis.clients.authentication:redis-authx-core:0.1.1-beta2).
     Searched in the following locations:
         https://repo.maven.apache.org/maven2/redis/clients/authentication/redis-authx-core/0.1.1-beta2/redis-authx-core-0.1.1-beta2.jar

* Try:
> Run with --stacktrace option to get the stack trace.
BUILD FAILED in 4s
LOG
  exit 1
fi
echo "refreshed-pinned-artifact-resolved"; exit 0'
run_case account-service 0
assert_call_count 2
assert_call 2 "--refresh-dependencies --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8 :account-service:check -PfullCheck --profile --info --console=plain"
[[ "$(cat "$CASE_DIR/sleeps")" == 0 ]] || fail "cached absence added a transient-error delay"
assert_output_contains "Could not find spring-boot-starter-jdbc-4.1.1.jar (org.springframework.boot:spring-boot-starter-jdbc:4.1.1)."
assert_output_contains "refreshed-pinned-artifact-resolved"

# The captured Account failure is a pre-execution JavaExec classpath resolution
# failure wrapped by Gradle's configuration-cache serializer. It may refresh
# only when the wrapper task and jooqCodegen configuration identify the same
# module and the complete fatal cause contains only repository-resolution data.
write_jooq_captured_cause() {
  cat > "$CASE_DIR/jooq-cause.log" <<'LOG'
FAILURE: Build failed with an exception.

* What went wrong:
Configuration cache state could not be cached: field `classpath` of `org.gradle.process.internal.DefaultJavaExecSpec_Decorated` bean found in field `javaExecSpec` of task `:account-service:generateJooq` of type `org.gradle.api.tasks.JavaExec`: error writing value of type 'org.gradle.api.internal.file.collections.DefaultConfigurableFileCollection'
> Could not resolve all files for configuration ':account-service:jooqCodegen'.
> Could not find jooq-codegen-3.21.9.jar (org.jooq:jooq-codegen:3.21.9).
Searched in the following locations:
https://repo.maven.apache.org/maven2/org/jooq/jooq-codegen/3.21.9/jooq-codegen-3.21.9.jar
> Could not find jooq-meta-extensions-3.21.9.jar (org.jooq:jooq-meta-extensions:3.21.9).
Searched in the following locations:
https://repo.maven.apache.org/maven2/org/jooq/jooq-meta-extensions/3.21.9/jooq-meta-extensions-3.21.9.jar
> Could not find jooq-meta-3.21.9.jar (org.jooq:jooq-meta:3.21.9).
Searched in the following locations:
https://repo.maven.apache.org/maven2/org/jooq/jooq-meta/3.21.9/jooq-meta-3.21.9.jar
> Could not find jooq-3.21.9.jar (org.jooq:jooq:3.21.9).
Searched in the following locations:
https://repo.maven.apache.org/maven2/org/jooq/jooq/3.21.9/jooq-3.21.9.jar
> Could not find h2-2.4.240.jar (com.h2database:h2:2.4.240).
Searched in the following locations:
https://repo.maven.apache.org/maven2/com/h2database/h2/2.4.240/h2-2.4.240.jar
> Could not find r2dbc-spi-1.0.0.RELEASE.jar (io.r2dbc:r2dbc-spi:1.0.0.RELEASE).
Searched in the following locations:
https://repo.maven.apache.org/maven2/io/r2dbc/r2dbc-spi/1.0.0.RELEASE/r2dbc-spi-1.0.0.RELEASE.jar
> Could not find jakarta.xml.bind-api-4.0.2.jar (jakarta.xml.bind:jakarta.xml.bind-api:4.0.2).
Searched in the following locations:
https://repo.maven.apache.org/maven2/jakarta/xml/bind/jakarta.xml.bind-api/4.0.2/jakarta.xml.bind-api-4.0.2.jar
> Could not find reactive-streams-1.0.3.jar (org.reactivestreams:reactive-streams:1.0.3).
Searched in the following locations:
https://repo.maven.apache.org/maven2/org/reactivestreams/reactive-streams/1.0.3/reactive-streams-1.0.3.jar
> Could not find jakarta.activation-api-2.1.3.jar (jakarta.activation:jakarta.activation-api:2.1.3).
Searched in the following locations:
https://repo.maven.apache.org/maven2/jakarta/activation/jakarta.activation-api/2.1.3/jakarta.activation-api-2.1.3.jar

* Try:
> Run with --stacktrace option to get the stack trace.
LOG
}

write_jooq_wrapper() {
  local progress_line="$1"
  write_wrapper "if [[ \"\$call_count\" -eq 1 ]]; then
  if [[ -n '$progress_line' ]]; then printf '%s\\n' '$progress_line'; fi
  cat '$CASE_DIR/jooq-cause.log'
  exit 1
fi
echo refreshed-captured-jooq-resolution; exit 0"
}

new_case captured-jooq-codegen-resolution
write_jooq_captured_cause
write_jooq_wrapper ''
run_case account-service 0
assert_call_count 2
assert_call 1 "--max-workers=2 -Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8 :account-service:check -PfullCheck --profile --info --console=plain"
assert_call 2 "--refresh-dependencies --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8 :account-service:check -PfullCheck --profile --info --console=plain"
[[ "$(cat "$CASE_DIR/sleeps")" == 0 ]] || fail "captured jOOQ artifact miss added a transient-error delay"
assert_output_contains "Configuration cache state could not be cached: field \`classpath\` of \`org.gradle.process.internal.DefaultJavaExecSpec_Decorated\`"
assert_output_contains "refreshed-captured-jooq-resolution"

# PR 3252 retained a protoc connect timeout under a disabled MavenRepo and
# the existing pre-execution jOOQ configuration-cache wrapper.
write_jooq_protoc_timeout_cause() {
  cat > "$CASE_DIR/jooq-cause.log" <<'LOG'
8 problems were found storing the configuration cache, 4 of which seem unique.
- Task `:automation-scripting-service:generateIntegrationTestProto` of type `com.google.protobuf.gradle.GenerateProtoTask`: error writing value of type 'org.gradle.api.internal.artifacts.configurations.DefaultLegacyConfiguration'
FAILURE: Build failed with an exception.
* What went wrong:
Configuration cache state could not be cached: field `classpath` of `org.gradle.process.internal.DefaultJavaExecSpec_Decorated` bean found in field `javaExecSpec` of task `:automation-scripting-service:generateJooq` of type `org.gradle.api.tasks.JavaExec`: error writing value of type 'org.gradle.api.internal.file.collections.DefaultConfigurableFileCollection'
> Could not resolve all files for configuration ':automation-scripting-service:jooqCodegen'.
   > Could not resolve org.jooq:jooq-codegen:3.21.9.
     Required by:
         project ':automation-scripting-service'
      > Repository MavenRepo is disabled due to earlier error below:
         > Could not resolve com.google.protobuf:protoc:4.36.2.
            > Could not get resource 'https://repo.maven.apache.org/maven2/com/google/protobuf/protoc/4.36.2/protoc-4.36.2.pom'.
               > Could not GET 'https://repo.maven.apache.org/maven2/com/google/protobuf/protoc/4.36.2/protoc-4.36.2.pom'.
                  > Connect to repo.maven.apache.org:443 [repo.maven.apache.org/104.18.19.12] failed: Connect timed out
* Try:
> Run with --stacktrace option for details.
BUILD FAILED in 2m 2s
LOG
}

new_case captured-jooq-protoc-timeout
write_jooq_protoc_timeout_cause
write_jooq_wrapper ''
run_case automation-scripting-service 0
assert_call_count 2
assert_call 2 "--refresh-dependencies --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8 :automation-scripting-service:check -PfullCheck --profile --info --console=plain"
[[ "$(cat "$CASE_DIR/sleeps")" == 2 ]] || fail "protoc connect timeout did not use one short delay"
assert_output_contains "Connect timed out"
assert_output_contains "refreshed-captured-jooq-resolution"

for mutation in repository configuration pin url host cache-family mixed unknown; do
  new_case "protoc-timeout-invalid-$mutation"
  write_jooq_protoc_timeout_cause
  case "$mutation" in
    repository) sed -i 's/Repository MavenRepo/Repository OtherRepo/' "$CASE_DIR/jooq-cause.log" ;;
    configuration) sed -i 's/:jooqCodegen/:runtimeClasspath/' "$CASE_DIR/jooq-cause.log" ;;
    pin) sed -i 's/:protoc:4.36.2\./:protoc:4.+./' "$CASE_DIR/jooq-cause.log" ;;
    url) sed -i 's@protoc/4.36.2/protoc-4.36.2.pom@protoc/4.36.3/protoc-4.36.3.pom@g' "$CASE_DIR/jooq-cause.log" ;;
    host) sed -i 's@repo.maven.apache.org@other.example.org@g' "$CASE_DIR/jooq-cause.log" ;;
    cache-family) sed -i 's/DefaultJavaExecSpec_Decorated/OtherJavaExecSpec/' "$CASE_DIR/jooq-cause.log" ;;
    mixed) sed -i '/^[*] Try:/i > Received status code 403 from server: Forbidden' "$CASE_DIR/jooq-cause.log" ;;
    unknown) sed -i '/^[*] Try:/i > Unknown repository policy failure' "$CASE_DIR/jooq-cause.log" ;;
  esac
  write_jooq_wrapper ''
  run_case automation-scripting-service 1
  assert_no_retry
done

for status in 401 403 409; do
  new_case "protoc-http-$status"
  write_jooq_protoc_timeout_cause
  sed -i "s/> Connect to .*failed: Connect timed out/> Received status code $status from server: Rejected/" "$CASE_DIR/jooq-cause.log"
  write_jooq_wrapper ''
  run_case automation-scripting-service 1
  assert_no_retry
done

for progress in '> Task :automation-scripting-service:generateJooq' '> Task :automation-scripting-service:compileJava' '> Task :automation-scripting-service:test' 'Compilation failed'; do
  new_case "protoc-timeout-progress-${progress//[^[:alnum:]]/}"
  write_jooq_protoc_timeout_cause
  write_jooq_wrapper "$progress"
  run_case automation-scripting-service 1
  assert_no_retry
done

new_case protoc-timeout-refresh-failure
write_jooq_protoc_timeout_cause
# shellcheck disable=SC2016
write_wrapper 'cat "$CASE_DIR/jooq-cause.log"
if [[ "$call_count" -eq 1 ]]; then exit 1; fi
exit 23'
run_case automation-scripting-service 23
assert_call_count 2
[[ "$(cat "$CASE_DIR/sleeps")" == 2 ]] || fail "protoc refresh failure exceeded retry delay count"

# This retained runtime-image failure is a different narrow boundary: Gradle
# marks generateJooq failed because its jooqCodegen classpath is unresolved,
# before the JavaExec generator process can run.
write_runtime_jooq_captured_cause() {
  cat > "$CASE_DIR/runtime-jooq-cause.log" <<'LOG'
> Task :buildSrc:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :buildSrc:compileKotlin FROM-CACHE
> Task :buildSrc:compileJava NO-SOURCE
> Task :buildSrc:compileGroovy NO-SOURCE
> Task :buildSrc:pluginDescriptors
> Task :buildSrc:processResources
> Task :buildSrc:classes
> Task :buildSrc:jar
> Task :account-service:writeJooqConfig
> Task :account-service:generateJooq FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':account-service:generateJooq' (registered by plugin 'net.firedevops.firemud.jooq-conventions').
> Could not resolve all files for configuration ':account-service:jooqCodegen'.
   > Could not find org.jooq:jooq-meta-extensions:3.21.9.

      Searched in the following locations:
         - https://repo.maven.apache.org/maven2/org/jooq/jooq-meta-extensions/3.21.9/jooq-meta-extensions-3.21.9.pom

      Required by:
          project ':account-service'
   > Could not find org.postgresql:postgresql:42.7.14.
      Searched in the following locations:
         - https://repo.maven.apache.org/maven2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14.pom
      Required by:
          project ':account-service'
   > Could not find org.jooq:jooq-meta:3.21.9.
      Searched in the following locations:
         - https://repo.maven.apache.org/maven2/org/jooq/jooq-meta/3.21.9/jooq-meta-3.21.9.pom
      Required by:
          project ':account-service' > org.jooq:jooq-codegen:3.21.9
> If the artifact you are trying to retrieve can be found in the repository but without metadata in 'Maven POM' format, you need to adjust the 'metadataSources { ... }' of the repository declaration.
* Try:
> Run with --stacktrace option to get the stack trace.
BUILD FAILED in 17s
LOG
}

compose_expected="--no-configuration-cache --max-workers=1 --no-parallel -Dorg.gradle.jvmargs=-Xmx2560m -Dfile.encoding=UTF-8 -PincludeLoadTesting=false :account-service:bootJar :automation-scripting-service:bootJar :entity-management-service:bootJar :game-design-service:bootJar :game-logic-service:bootJar :game-session-service:bootJar :logging-admin-service:bootJar :social-groups-service:bootJar :spring-cloud-gateway:bootJar :tcp-proxy-service:bootJar :world-management-service:bootJar --info --console=plain"
new_case runtime-jooq-classpath-resolution
write_runtime_jooq_captured_cause
# shellcheck disable=SC2016
write_wrapper 'if [[ "$call_count" -eq 1 ]]; then
  cat "$CASE_DIR/runtime-jooq-cause.log"
  exit 1
fi
echo refreshed-runtime-jooq-resolution
exit 0'
run_compose_case 0
assert_call_count 2
assert_call 1 "$compose_expected"
assert_call 2 "--refresh-dependencies $compose_expected"
[[ "$(wc -l < "$CASE_DIR/locked-calls")" -eq 2 ]] || fail "runtime refresh did not retain locked execution"
[[ "$(sed -n '1p' "$CASE_DIR/locked-calls")" == "$compose_expected" ]] || fail "initial runtime task list changed before lock wrapper"
[[ "$(sed -n '2p' "$CASE_DIR/locked-calls")" == "--refresh-dependencies $compose_expected" ]] || fail "refreshed runtime task list changed before lock wrapper"
[[ "$(cat "$CASE_DIR/sleeps")" == 0 ]] || fail "missing runtime artifact added a transient-error delay"
assert_output_contains "Could not resolve all files for configuration ':account-service:jooqCodegen'."
assert_output_contains "refreshed-runtime-jooq-resolution"

# The exact resolution root is not enough when other generator, compile/test,
# or unknown failure work is present in the same complete command output.
new_case runtime-jooq-other-generator-progress
write_runtime_jooq_captured_cause
# shellcheck disable=SC2016
write_wrapper 'if [[ "$call_count" -eq 1 ]]; then
  echo "> Task :automation-scripting-service:generateJooq FROM-CACHE"
  cat "$CASE_DIR/runtime-jooq-cause.log"
  exit 1
fi
echo unexpected-retry
exit 0'
run_compose_case 1
assert_no_retry

new_case runtime-jooq-compile-progress
write_runtime_jooq_captured_cause
# shellcheck disable=SC2016
write_wrapper 'if [[ "$call_count" -eq 1 ]]; then
  echo "> Task :account-service:compileJava"
  cat "$CASE_DIR/runtime-jooq-cause.log"
  exit 1
fi
echo unexpected-retry
exit 0'
run_compose_case 1
assert_no_retry

new_case runtime-jooq-mixed-cause
write_runtime_jooq_captured_cause
awk '/^\* Try:$/ { print "Could not create task :account-service:check because of unsupported plugin configuration." } { print }' \
  "$CASE_DIR/runtime-jooq-cause.log" > "$CASE_DIR/runtime-jooq-mixed-cause.log"
mv "$CASE_DIR/runtime-jooq-mixed-cause.log" "$CASE_DIR/runtime-jooq-cause.log"
# shellcheck disable=SC2016
write_wrapper 'cat "$CASE_DIR/runtime-jooq-cause.log"
exit 1'
run_compose_case 1
assert_no_retry

# A real generator failure is not a dependency failure and must retain its
# original result even though it came from the same JavaExec task.
new_case jooq-codegen-execution-failure
write_wrapper 'cat <<"LOG"
> Task :account-service:generateJooq FAILED
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task '\'':account-service:generateJooq'\''.
org.jooq.exception.DataAccessException: Invalid SQL migration syntax.
* Try:
> Run with --stacktrace option to get the stack trace.
BUILD FAILED in 2s
LOG
exit 1'
run_case account-service 1
assert_no_retry

# An additional cause in the exact captured shape makes it mixed and therefore
# ineligible, even though its repository-resolution portion would otherwise retry.
new_case captured-jooq-mixed-cause
write_jooq_captured_cause
awk '/^\* Try:$/ { print "Could not create task :account-service:check because of unsupported plugin configuration." } { print }' \
  "$CASE_DIR/jooq-cause.log" > "$CASE_DIR/jooq-mixed-cause.log"
mv "$CASE_DIR/jooq-mixed-cause.log" "$CASE_DIR/jooq-cause.log"
write_jooq_wrapper ''
run_case account-service 1
assert_no_retry

# Even the captured resolver/cache wrapper cannot retry after generator work or
# substantive module compilation has started.
new_case jooq-generator-already-started
write_jooq_captured_cause
write_jooq_wrapper '> Task :account-service:generateJooq FROM-CACHE'
run_case account-service 1
assert_no_retry

new_case jooq-compile-already-started
write_jooq_captured_cause
write_jooq_wrapper '> Task :account-service:compileJava'
run_case account-service 1
assert_no_retry

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

# Retain the PR 3220 buildSrc configuration failure, including the plugin
# repository disabling itself and a parent POM failing after a redirect.
write_buildsrc_429_cause() {
  cat > "$CASE_DIR/buildsrc-cause.log" <<'LOG'
FAILURE: Build failed with an exception.
* What went wrong:
A problem occurred configuring project ':buildSrc'.
> Could not resolve all artifacts for configuration 'classpath'.
   > Could not resolve org.jetbrains.kotlin:kotlin-stdlib:2.4.10.
     Required by:
         buildscript of project ':buildSrc'
         buildscript of project ':buildSrc' > org.gradle.kotlin.kotlin-dsl:org.gradle.kotlin.kotlin-dsl.gradle.plugin:6.7.6 > org.gradle.kotlin:gradle-kotlin-dsl-plugins:6.7.6
      > Repository Gradle Central Plugin Repository is disabled due to earlier error below:
         > Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.4.10.
            > Could not get resource 'https://plugins.gradle.org/m2/org/jetbrains/kotlin/kotlin-gradle-plugin-api/2.4.10/kotlin-gradle-plugin-api-2.4.10.pom'.
               > Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin-api/2.4.10/kotlin-gradle-plugin-api-2.4.10.pom'.
                  > Received status code 429 from server: Too Many Requests
   > Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10.
     Required by:
         buildscript of project ':buildSrc' > org.gradle.kotlin.kotlin-dsl:org.gradle.kotlin.kotlin-dsl.gradle.plugin:6.7.6 > org.gradle.kotlin:gradle-kotlin-dsl-plugins:6.7.6
      > Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10.
         > Could not parse POM https://plugins.gradle.org/m2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.4.10/kotlin-gradle-plugin-2.4.10.pom
            > Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.4.10.
               > Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.4.10.
                  > Could not get resource 'https://plugins.gradle.org/m2/org/jetbrains/kotlin/kotlin-gradle-plugins-bom/2.4.10/kotlin-gradle-plugins-bom-2.4.10.pom'.
                     > Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugins-bom/2.4.10/kotlin-gradle-plugins-bom-2.4.10.pom'.
                        > Received status code 429 from server: Too Many Requests
> There are 3 more failures with identical causes.
* Try:
> Run with --stacktrace option for details.
BUILD FAILED in 42s
LOG
}

write_buildsrc_wrapper() {
  # shellcheck disable=SC2016
  write_wrapper 'if [[ "$call_count" -eq 1 ]]; then
  cat "$CASE_DIR/buildsrc-cause.log"
  exit 1
fi
echo buildsrc-refresh-result
exit "${BUILDSRC_REFRESH_EXIT:-0}"'
}

new_case captured-buildsrc-429
write_buildsrc_429_cause
write_buildsrc_wrapper
run_case logging-admin-service 0
assert_call_count 2
assert_call 1 "--max-workers=2 -Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8 :logging-admin-service:check -PfullCheck --profile --info --console=plain"
assert_call 2 "--refresh-dependencies --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8 :logging-admin-service:check -PfullCheck --profile --info --console=plain"
[[ "$(cat "$CASE_DIR/sleeps")" == 2 ]] || fail "buildSrc HTTP 429 did not use one short delay"
assert_output_contains "buildsrc-refresh-result"

# Auth, policy and conflict responses remain final, including mixed failures
# where the other dependency really did encounter a retryable HTTP 429.
for status in 401 403 409; do
  for status_scope in all mixed; do
    new_case "buildsrc-http-$status-$status_scope"
    write_buildsrc_429_cause
    if [[ "$status_scope" == all ]]; then
      sed -i "s/status code 429/status code $status/g" "$CASE_DIR/buildsrc-cause.log"
    else
      sed -i "0,/status code 429/s//status code $status/" "$CASE_DIR/buildsrc-cause.log"
    fi
    write_buildsrc_wrapper
    run_case logging-admin-service 1
    assert_no_retry
  done
done

for cause in 'Compilation failed' '> Access denied by repository policy' '> Unknown configuration failure'; do
  new_case "buildsrc-extra-${cause//[^[:alnum:]]/}"
  write_buildsrc_429_cause
  sed -i "/^[*] Try:/i $cause" "$CASE_DIR/buildsrc-cause.log"
  write_buildsrc_wrapper
  run_case logging-admin-service 1
  assert_no_retry
done

# Wrapper context cannot turn another project, configuration or unpinned
# dependency into an eligible buildSrc retry.
for boundary in project classpath pin; do
  new_case "buildsrc-invalid-$boundary"
  write_buildsrc_429_cause
  case "$boundary" in
    project) sed -i 's/:buildSrc/:otherProject/g' "$CASE_DIR/buildsrc-cause.log" ;;
    classpath) sed -i 's/configuration '\''classpath'\''/configuration '\''runtimeClasspath'\''/' "$CASE_DIR/buildsrc-cause.log" ;;
    pin) sed -i 's/:2.4.10\./:2.+./g' "$CASE_DIR/buildsrc-cause.log" ;;
  esac
  write_buildsrc_wrapper
  run_case logging-admin-service 1
  assert_no_retry
done

new_case buildsrc-refresh-failure
write_buildsrc_429_cause
write_buildsrc_wrapper
export BUILDSRC_REFRESH_EXIT=23
run_case logging-admin-service 23
unset BUILDSRC_REFRESH_EXIT
assert_call_count 2
[[ "$(cat "$CASE_DIR/sleeps")" == 2 ]] || fail "failed buildSrc refresh changed retry limit"
assert_output_contains "Received status code 429"
assert_output_contains "buildsrc-refresh-result"

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
assert_diagnostic_count 1
[[ "$(cat "$CASE_DIR/diagnostic-calls")" == *":common-web-support:firemudCompileFailureFacts" ]] || \
  fail "compiler facts task was not invoked in the failed module project"

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

# Compile test source tasks are compile inputs, not test execution tasks; both
# source sets may retry only when their matching compile classpath is unresolved.
run_compile_input_case() {
  local case_name="$1"
  local task_name="$2"
  local configuration="$3"

  new_case "$case_name"
  # shellcheck disable=SC2016
  write_wrapper "if [[ \"\$call_count\" -eq 1 ]]; then
cat <<'LOG'
> Task :common-web-support:${task_name} FAILED
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':common-web-support:${task_name}'.
> Could not resolve all files for configuration ':common-web-support:${configuration}'.
> Could not find org.example:test-fixture:1.2.3.
  Searched in the following locations:
    https://repo.maven.apache.org/maven2/org/example/test-fixture/1.2.3/test-fixture-1.2.3.pom
* Try:
> Run with --stacktrace option for details.
BUILD FAILED in 1s
LOG
exit 1
fi
echo compile-input-refreshed; exit 0"
  run_case common-web-support 0
  assert_call_count 2
  assert_output_contains "Execution failed for task ':common-web-support:${task_name}'."
}

run_compile_input_case test-source-input-resolution compileTestJava testCompileClasspath
run_compile_input_case integration-test-source-input-resolution compileIntegrationTestJava integrationTestCompileClasspath
run_compile_input_case cross-service-source-input-resolution compileCrossServiceTestJava crossServiceTestCompileClasspath

# A compiler diagnostic from test-source compilation and a completed compile
# task both remain final failures, even with dependency-shaped output present.
new_case test-source-compiler-failure
write_wrapper 'cat <<"LOG"
> Task :common-web-support:compileTestJava FAILED
error: cannot find symbol
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task '\'':common-web-support:compileTestJava'\''.
> Could not resolve all files for configuration '\'':common-web-support:testCompileClasspath'\''.
> Could not find org.example:test-fixture:1.2.3.
  Searched in the following locations:
    https://repo.maven.apache.org/maven2/org/example/test-fixture/1.2.3/test-fixture-1.2.3.pom
* Try:
> Run with --stacktrace option for details.
BUILD FAILED in 1s
LOG
exit 1'
run_case common-web-support 1
assert_no_retry

new_case completed-test-source-compile
write_wrapper 'cat <<"LOG"
> Task :common-web-support:compileTestJava
FAILURE: Build failed with an exception.
* What went wrong:
> Could not resolve all files for configuration '\'':common-web-support:testCompileClasspath'\''.
> Could not find org.example:test-fixture:1.2.3.
  Searched in the following locations:
    https://repo.maven.apache.org/maven2/org/example/test-fixture/1.2.3/test-fixture-1.2.3.pom
* Try:
> Run with --stacktrace option for details.
BUILD FAILED in 1s
LOG
exit 1'
run_case common-web-support 1
assert_no_retry

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
