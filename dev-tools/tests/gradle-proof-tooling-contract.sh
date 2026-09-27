#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCKED_RUNNER="$ROOT_DIR/dev-tools/validation/run-locked-gradle.sh"
INSPECTOR="$ROOT_DIR/dev-tools/validation/inspect-test-results.sh"
BOOTSTRAP_PROOF="$ROOT_DIR/dev-tools/verify-fresh-bootstrap.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

cat >"$TMP_DIR/fake-gradlew.sh" <<'EOF'
#!/usr/bin/env bash
sleep "${FIREMUD_FAKE_GRADLE_SLEEP:-2}"
EOF
chmod +x "$TMP_DIR/fake-gradlew.sh"

single_service="$(bash "$LOCKED_RUNNER" --print-lock-targets :game-session-service:check -PfullCheck)"
[[ "$single_service" == "service:game-session-service" ]]

single_service_with_tests="$(bash "$LOCKED_RUNNER" --print-lock-targets :game-session-service:test --tests net.firedevops.ExampleTest)"
[[ "$single_service_with_tests" == "service:game-session-service" ]]

single_service_with_exclusion="$(bash "$LOCKED_RUNNER" --print-lock-targets :game-session-service:check -x test)"
[[ "$single_service_with_exclusion" == "service:game-session-service" ]]

multi_service="$(bash "$LOCKED_RUNNER" --print-lock-targets :tcp-proxy-service:check :game-session-service:check)"
expected_multi=$'service:game-session-service\nservice:tcp-proxy-service'
[[ "$multi_service" == "$expected_multi" ]]

repo_wide="$(bash "$LOCKED_RUNNER" --print-lock-targets check)"
[[ "$repo_wide" == "repo" ]]

mixed_scope="$(bash "$LOCKED_RUNNER" --print-lock-targets :game-session-service:check check)"
[[ "$mixed_scope" == "repo" ]]

env FIREMUD_LOCK_GRADLE_EXEC="$TMP_DIR/fake-gradlew.sh" FIREMUD_FAKE_GRADLE_SLEEP=3 \
  bash "$LOCKED_RUNNER" :game-session-service:check >/dev/null 2>"$TMP_DIR/service-lock.err" &
service_holder_pid=$!
sleep 0.3
set +e
service_conflict_output="$(env FIREMUD_LOCK_GRADLE_EXEC="$TMP_DIR/fake-gradlew.sh" bash "$LOCKED_RUNNER" check 2>&1)"
service_conflict_status=$?
set -e
[[ $service_conflict_status -ne 0 ]]
grep -q "Verification lock unavailable for repo." <<<"$service_conflict_output"
wait "$service_holder_pid"

env FIREMUD_LOCK_GRADLE_EXEC="$TMP_DIR/fake-gradlew.sh" FIREMUD_FAKE_GRADLE_SLEEP=3 \
  bash "$LOCKED_RUNNER" check >/dev/null 2>"$TMP_DIR/repo-lock.err" &
repo_holder_pid=$!
sleep 0.3
set +e
repo_conflict_output="$(env FIREMUD_LOCK_GRADLE_EXEC="$TMP_DIR/fake-gradlew.sh" bash "$LOCKED_RUNNER" :game-session-service:check 2>&1)"
repo_conflict_status=$?
set -e
[[ $repo_conflict_status -ne 0 ]]
grep -q "Verification lock unavailable for repo." <<<"$repo_conflict_output"
wait "$repo_holder_pid"

mkdir -p "$TMP_DIR/services/demo-service/build/test-results/test"
mkdir -p "$TMP_DIR/services/demo-service/build/test-results/integrationTest"

cat >"$TMP_DIR/services/demo-service/build/test-results/test/TEST-demo-unit.xml" <<'XML'
<testsuite name="demo-unit" tests="3" failures="0" errors="0" skipped="1"/>
XML

cat >"$TMP_DIR/services/demo-service/build/test-results/integrationTest/TEST-demo-integration.xml" <<'XML'
<testsuite name="demo-integration" tests="2" failures="1" errors="0" skipped="0"/>
XML

inspection_output="$(bash "$INSPECTOR" --root "$TMP_DIR" demo-service)"
grep -q "Service: demo-service" <<<"$inspection_output"
grep -q "Per-suite summary from XML currently on disk:" <<<"$inspection_output"
grep -q "test: 1 file(s), 3 test(s), 0 failure(s), 0 error(s), 1 skipped" <<<"$inspection_output"
grep -q "integrationTest: 1 file(s), 2 test(s), 1 failure(s), 0 error(s), 0 skipped" <<<"$inspection_output"
grep -q "Most recent XML on disk:" <<<"$inspection_output"
grep -q "Failing XML files:" <<<"$inspection_output"
grep -q "Diagnostic only:" <<<"$inspection_output"

JOIN_SUITE="net.firedevops.firemud.accountservice.AccountJoinPostgresIntegrationTest"
EXCHANGE_SUITE="integration.net.firedevops.firemud.accountservice.AccountBareLoginExchangeRepositoryIntegrationTest"
JOIN_CASE="$JOIN_SUITE#positiveMembershipSnapshotRejectsScalarAndWrongTenantVersionMaps()"
EXCHANGE_CASE="$EXCHANGE_SUITE#claimOnlyTransactionRollsBackPendingExchange()"

inspect_strict() {
  bash "$INSPECTOR" --root "$1" --strict \
    --require-suite "$JOIN_SUITE" \
    --require-suite "$EXCHANGE_SUITE" \
    --require-case "$JOIN_CASE" \
    --require-case "$EXCHANGE_CASE" \
    account-service
}

new_strict_fixture() {
  mkdir -p "$1/services/account-service/build/test-results/integrationTest"
}

STRICT_GREEN_ROOT="$TMP_DIR/strict-green"
new_strict_fixture "$STRICT_GREEN_ROOT"
cat >"$STRICT_GREEN_ROOT/services/account-service/build/test-results/integrationTest/TEST-join.xml" <<XML
<testsuite name="$JOIN_SUITE" tests="2" failures="0" errors="0" skipped="0">
  <testcase classname="$JOIN_SUITE" name="positiveMembershipSnapshotRejectsScalarAndWrongTenantVersionMaps()"/>
  <testcase classname="$JOIN_SUITE" name="anotherMembershipCase()"/>
  <system-out>STRICT_OUTPUT_SENTINEL</system-out>
</testsuite>
XML
cat >"$STRICT_GREEN_ROOT/services/account-service/build/test-results/integrationTest/TEST-exchange.xml" <<XML
<testsuite name="$EXCHANGE_SUITE" tests="1" failures="0" errors="0" skipped="0">
  <testcase classname="$EXCHANGE_SUITE" name="claimOnlyTransactionRollsBackPendingExchange()"/>
</testsuite>
XML
strict_green_output="$(inspect_strict "$STRICT_GREEN_ROOT")"
grep -q '^Strict JUnit proof: passed$' <<<"$strict_green_output"
grep -Fq "Suite $JOIN_SUITE: tests=2 skipped=0 failures=0 errors=0 outcome=passed" <<<"$strict_green_output"
grep -Fq "Case $JOIN_CASE: executions=1 outcome=passed" <<<"$strict_green_output"
grep -Fq "Case $EXCHANGE_CASE: executions=1 outcome=passed" <<<"$strict_green_output"
! grep -q "STRICT_OUTPUT_SENTINEL" <<<"$strict_green_output"

STRICT_MISSING_SUITE_ROOT="$TMP_DIR/strict-missing-suite"
new_strict_fixture "$STRICT_MISSING_SUITE_ROOT"
cp "$STRICT_GREEN_ROOT/services/account-service/build/test-results/integrationTest/TEST-join.xml" \
  "$STRICT_MISSING_SUITE_ROOT/services/account-service/build/test-results/integrationTest/TEST-join.xml"
if missing_suite_output="$(inspect_strict "$STRICT_MISSING_SUITE_ROOT" 2>&1)"; then
  echo "Strict inspection unexpectedly accepted a missing suite." >&2
  exit 1
fi
grep -Fq "Missing required suite: $EXCHANGE_SUITE" <<<"$missing_suite_output"

STRICT_NO_REPORTS_ROOT="$TMP_DIR/strict-no-reports"
new_strict_fixture "$STRICT_NO_REPORTS_ROOT"
if no_reports_output="$(inspect_strict "$STRICT_NO_REPORTS_ROOT" 2>&1)"; then
  echo "Strict inspection unexpectedly accepted an empty report directory." >&2
  exit 1
fi
grep -Fq "No JUnit XML reports were found." <<<"$no_reports_output"

STRICT_MISSING_CASE_ROOT="$TMP_DIR/strict-missing-case"
new_strict_fixture "$STRICT_MISSING_CASE_ROOT"
cat >"$STRICT_MISSING_CASE_ROOT/services/account-service/build/test-results/integrationTest/TEST-join.xml" <<XML
<testsuite name="$JOIN_SUITE" tests="1" failures="0" errors="0" skipped="0">
  <testcase classname="$JOIN_SUITE" name="differentMembershipCase()"/>
</testsuite>
XML
cp "$STRICT_GREEN_ROOT/services/account-service/build/test-results/integrationTest/TEST-exchange.xml" \
  "$STRICT_MISSING_CASE_ROOT/services/account-service/build/test-results/integrationTest/TEST-exchange.xml"
if missing_case_output="$(inspect_strict "$STRICT_MISSING_CASE_ROOT" 2>&1)"; then
  echo "Strict inspection unexpectedly accepted a missing case." >&2
  exit 1
fi
grep -Fq "Missing required case: $JOIN_CASE" <<<"$missing_case_output"

STRICT_ZERO_TESTS_ROOT="$TMP_DIR/strict-zero-tests"
new_strict_fixture "$STRICT_ZERO_TESTS_ROOT"
cat >"$STRICT_ZERO_TESTS_ROOT/services/account-service/build/test-results/integrationTest/TEST-join.xml" <<XML
<testsuite name="$JOIN_SUITE" tests="0" failures="0" errors="0" skipped="0"/>
XML
cp "$STRICT_GREEN_ROOT/services/account-service/build/test-results/integrationTest/TEST-exchange.xml" \
  "$STRICT_ZERO_TESTS_ROOT/services/account-service/build/test-results/integrationTest/TEST-exchange.xml"
if zero_tests_output="$(inspect_strict "$STRICT_ZERO_TESTS_ROOT" 2>&1)"; then
  echo "Strict inspection unexpectedly accepted an empty required suite." >&2
  exit 1
fi
grep -Fq "Required suite has zero tests: $JOIN_SUITE" <<<"$zero_tests_output"

STRICT_SKIPPED_ROOT="$TMP_DIR/strict-skipped"
new_strict_fixture "$STRICT_SKIPPED_ROOT"
cat >"$STRICT_SKIPPED_ROOT/services/account-service/build/test-results/integrationTest/TEST-join.xml" <<XML
<testsuite name="$JOIN_SUITE" tests="1" failures="0" errors="0" skipped="1">
  <testcase classname="$JOIN_SUITE" name="positiveMembershipSnapshotRejectsScalarAndWrongTenantVersionMaps()"><skipped/></testcase>
</testsuite>
XML
cp "$STRICT_GREEN_ROOT/services/account-service/build/test-results/integrationTest/TEST-exchange.xml" \
  "$STRICT_SKIPPED_ROOT/services/account-service/build/test-results/integrationTest/TEST-exchange.xml"
if skipped_output="$(inspect_strict "$STRICT_SKIPPED_ROOT" 2>&1)"; then
  echo "Strict inspection unexpectedly accepted a skipped suite." >&2
  exit 1
fi
grep -Fq "Required suite is failing, errored, or skipped: $JOIN_SUITE" <<<"$skipped_output"
grep -Fq "Case $JOIN_CASE: executions=1 outcome=skipped" <<<"$skipped_output"

STRICT_FAILING_ROOT="$TMP_DIR/strict-failing"
new_strict_fixture "$STRICT_FAILING_ROOT"
cat >"$STRICT_FAILING_ROOT/services/account-service/build/test-results/integrationTest/TEST-join.xml" <<XML
<testsuite name="$JOIN_SUITE" tests="1" failures="1" errors="0" skipped="0">
  <testcase classname="$JOIN_SUITE" name="positiveMembershipSnapshotRejectsScalarAndWrongTenantVersionMaps()">
    <failure message="SENSITIVE_FAILURE_SENTINEL">SENSITIVE_FAILURE_SENTINEL</failure>
  </testcase>
  <system-out>SENSITIVE_STDOUT_SENTINEL</system-out>
</testsuite>
XML
cp "$STRICT_GREEN_ROOT/services/account-service/build/test-results/integrationTest/TEST-exchange.xml" \
  "$STRICT_FAILING_ROOT/services/account-service/build/test-results/integrationTest/TEST-exchange.xml"
if failing_output="$(inspect_strict "$STRICT_FAILING_ROOT" 2>&1)"; then
  echo "Strict inspection unexpectedly accepted a failing suite." >&2
  exit 1
fi
grep -Fq "Required suite is failing, errored, or skipped: $JOIN_SUITE" <<<"$failing_output"
grep -Fq "Case $JOIN_CASE: executions=1 outcome=failure" <<<"$failing_output"
! grep -q "SENSITIVE_" <<<"$failing_output"

STRICT_DUPLICATE_SUITE_ROOT="$TMP_DIR/strict-duplicate-suite"
new_strict_fixture "$STRICT_DUPLICATE_SUITE_ROOT"
cp "$STRICT_GREEN_ROOT/services/account-service/build/test-results/integrationTest/TEST-join.xml" \
  "$STRICT_DUPLICATE_SUITE_ROOT/services/account-service/build/test-results/integrationTest/TEST-join.xml"
cp "$STRICT_GREEN_ROOT/services/account-service/build/test-results/integrationTest/TEST-join.xml" \
  "$STRICT_DUPLICATE_SUITE_ROOT/services/account-service/build/test-results/integrationTest/TEST-join-copy.xml"
cp "$STRICT_GREEN_ROOT/services/account-service/build/test-results/integrationTest/TEST-exchange.xml" \
  "$STRICT_DUPLICATE_SUITE_ROOT/services/account-service/build/test-results/integrationTest/TEST-exchange.xml"
if duplicate_suite_output="$(inspect_strict "$STRICT_DUPLICATE_SUITE_ROOT" 2>&1)"; then
  echo "Strict inspection unexpectedly accepted a duplicate suite report." >&2
  exit 1
fi
grep -Fq "Duplicate required suite: $JOIN_SUITE (2 report(s))" <<<"$duplicate_suite_output"

STRICT_DUPLICATE_CASE_ROOT="$TMP_DIR/strict-duplicate-case"
new_strict_fixture "$STRICT_DUPLICATE_CASE_ROOT"
cat >"$STRICT_DUPLICATE_CASE_ROOT/services/account-service/build/test-results/integrationTest/TEST-join.xml" <<XML
<testsuite name="$JOIN_SUITE" tests="2" failures="0" errors="0" skipped="0">
  <testcase classname="$JOIN_SUITE" name="positiveMembershipSnapshotRejectsScalarAndWrongTenantVersionMaps()"/>
  <testcase classname="$JOIN_SUITE" name="positiveMembershipSnapshotRejectsScalarAndWrongTenantVersionMaps()"/>
</testsuite>
XML
cp "$STRICT_GREEN_ROOT/services/account-service/build/test-results/integrationTest/TEST-exchange.xml" \
  "$STRICT_DUPLICATE_CASE_ROOT/services/account-service/build/test-results/integrationTest/TEST-exchange.xml"
if duplicate_case_output="$(inspect_strict "$STRICT_DUPLICATE_CASE_ROOT" 2>&1)"; then
  echo "Strict inspection unexpectedly accepted duplicate requested case execution." >&2
  exit 1
fi
grep -Fq "Duplicate required case: $JOIN_CASE (2 execution(s))" <<<"$duplicate_case_output"

STRICT_MALFORMED_ROOT="$TMP_DIR/strict-malformed"
new_strict_fixture "$STRICT_MALFORMED_ROOT"
cp "$STRICT_GREEN_ROOT/services/account-service/build/test-results/integrationTest/TEST-join.xml" \
  "$STRICT_MALFORMED_ROOT/services/account-service/build/test-results/integrationTest/TEST-join.xml"
cp "$STRICT_GREEN_ROOT/services/account-service/build/test-results/integrationTest/TEST-exchange.xml" \
  "$STRICT_MALFORMED_ROOT/services/account-service/build/test-results/integrationTest/TEST-exchange.xml"
printf '<testsuite name="malformed"' >"$STRICT_MALFORMED_ROOT/services/account-service/build/test-results/integrationTest/TEST-malformed.xml"
if malformed_output="$(inspect_strict "$STRICT_MALFORMED_ROOT" 2>&1)"; then
  echo "Strict inspection unexpectedly accepted malformed JUnit XML." >&2
  exit 1
fi
grep -Fq "malformed reports: 1" <<<"$malformed_output"

bootstrap_validation_output="$(
  FIREMUD_SMOKE_COMPOSE_SERVICES=$'gateway\ngame-session-service\n' \
  FIREMUD_SMOKE_NO_CACHE_SERVICES='gateway game-session-service' \
  FIREMUD_SMOKE_VALIDATE_ONLY=1 \
  bash "$BOOTSTRAP_PROOF"
)"
grep -q "Validation-only mode: compose service selector parsing succeeded." <<<"$bootstrap_validation_output"

set +e
bootstrap_invalid_output="$(
  FIREMUD_SMOKE_COMPOSE_SERVICES=$'gateway\ngame-session-service' \
  FIREMUD_SMOKE_NO_CACHE_SERVICES='spring-cloud-gateway' \
  FIREMUD_SMOKE_VALIDATE_ONLY=1 \
  bash "$BOOTSTRAP_PROOF" 2>&1
)"
bootstrap_invalid_status=$?
set -e
[[ $bootstrap_invalid_status -ne 0 ]]
grep -q "Use Docker Compose service ids here" <<<"$bootstrap_invalid_output"

echo "gradle proof tooling contract checks passed"
