#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
FIXTURE_ROOT="$TMP_DIR/checkout"
mkdir -p "$FIXTURE_ROOT/dev-tools/validation" "$FIXTURE_ROOT/dev-tools/smoke" "$FIXTURE_ROOT/docker"
for service_dir in "$ROOT_DIR"/services/*/; do
  mkdir -p "$FIXTURE_ROOT/services/$(basename "$service_dir")"
done
cp "$ROOT_DIR/dev-tools/validation/"{run-locked-gradle.sh,gradle-run-supervisor.py,inspect-test-results.sh} "$FIXTURE_ROOT/dev-tools/validation/"
cp "$ROOT_DIR/dev-tools/"{verify-fresh-bootstrap.sh,ensure-local-compose-env.sh} "$FIXTURE_ROOT/dev-tools/"
cp "$ROOT_DIR/dev-tools/smoke/run-owned-compose.sh" "$FIXTURE_ROOT/dev-tools/smoke/"
cp "$ROOT_DIR/.env.sample" "$FIXTURE_ROOT/.env.sample"
LOCKED_RUNNER="$FIXTURE_ROOT/dev-tools/validation/run-locked-gradle.sh"
INSPECTOR="$FIXTURE_ROOT/dev-tools/validation/inspect-test-results.sh"
BOOTSTRAP_PROOF="$FIXTURE_ROOT/dev-tools/verify-fresh-bootstrap.sh"

# These original checks prove output guards independently of the local resource guard.
export CI=true

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

DRAFT_SUITE="integration.net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFencePostgresIntegrationTest"
DRAFT_CASE="${DRAFT_SUITE}#accountAndGlobalRolePendingScopesVersionOnlyTheirExistingAccountTuple()"
STRICT_ROOT="$TMP_DIR/strict"
STRICT_REPORTS="$STRICT_ROOT/services/account-service/build/test-results/integrationTest"
mkdir -p "$STRICT_REPORTS"

inspect_strict() {
  bash "$INSPECTOR" --root "$STRICT_ROOT" --strict \
    --require-suite "$DRAFT_SUITE" \
    --require-case "$DRAFT_CASE" \
    account-service
}

cat >"$STRICT_REPORTS/TEST-draft.xml" <<XML
<testsuite name="$DRAFT_SUITE" tests="1" failures="0" errors="0" skipped="0">
  <testcase classname="$DRAFT_SUITE" name="accountAndGlobalRolePendingScopesVersionOnlyTheirExistingAccountTuple()"/>
</testsuite>
XML
strict_green_output="$(inspect_strict)"
grep -q '^Strict JUnit proof: passed$' <<<"$strict_green_output"
grep -Fq "Case $DRAFT_CASE: executions=1 outcome=passed" <<<"$strict_green_output"

rm "$STRICT_REPORTS/TEST-draft.xml"
if strict_no_reports_output="$(inspect_strict 2>&1)"; then
  echo "Strict inspection unexpectedly accepted missing JUnit XML reports." >&2
  exit 1
fi
grep -Fq "No JUnit XML reports were found." <<<"$strict_no_reports_output"

cat >"$STRICT_REPORTS/TEST-draft.xml" <<XML
<testsuite name="other-suite" tests="1" failures="0" errors="0" skipped="0">
  <testcase classname="$DRAFT_SUITE" name="accountAndGlobalRolePendingScopesVersionOnlyTheirExistingAccountTuple()"/>
</testsuite>
XML
if strict_missing_suite_output="$(inspect_strict 2>&1)"; then
  echo "Strict inspection unexpectedly accepted a missing required suite." >&2
  exit 1
fi
grep -Fq "Missing required suite: $DRAFT_SUITE" <<<"$strict_missing_suite_output"

cat >"$STRICT_REPORTS/TEST-draft.xml" <<XML
<testsuite name="$DRAFT_SUITE" tests="0" failures="0" errors="0" skipped="0"/>
XML
if strict_zero_tests_output="$(inspect_strict 2>&1)"; then
  echo "Strict inspection unexpectedly accepted an empty required suite." >&2
  exit 1
fi
grep -Fq "Required suite has zero tests: $DRAFT_SUITE" <<<"$strict_zero_tests_output"

cat >"$STRICT_REPORTS/TEST-draft.xml" <<XML
<testsuite name="$DRAFT_SUITE" tests="1" failures="1" errors="0" skipped="0">
  <testcase classname="$DRAFT_SUITE" name="accountAndGlobalRolePendingScopesVersionOnlyTheirExistingAccountTuple()"><failure message="fixture"/></testcase>
</testsuite>
XML
if strict_failure_output="$(inspect_strict 2>&1)"; then
  echo "Strict inspection unexpectedly accepted a failing required case." >&2
  exit 1
fi
grep -Fq "Required suite is failing, errored, or skipped: $DRAFT_SUITE" <<<"$strict_failure_output"

cat >"$STRICT_REPORTS/TEST-draft.xml" <<XML
<testsuite name="$DRAFT_SUITE" tests="1" failures="0" errors="0" skipped="0">
  <testcase classname="$DRAFT_SUITE" name="accountAndGlobalRolePendingScopesVersionOnlyTheirExistingAccountTuple()"/>
</testsuite>
XML
cp "$STRICT_REPORTS/TEST-draft.xml" "$STRICT_REPORTS/TEST-draft-copy.xml"
if strict_duplicate_output="$(inspect_strict 2>&1)"; then
  echo "Strict inspection unexpectedly accepted a duplicate required suite." >&2
  exit 1
fi
grep -Fq "Duplicate required suite: $DRAFT_SUITE" <<<"$strict_duplicate_output"
rm "$STRICT_REPORTS/TEST-draft-copy.xml"

cat >"$STRICT_REPORTS/TEST-draft.xml" <<XML
<testsuite name="$DRAFT_SUITE" tests="2" failures="0" errors="0" skipped="0">
  <testcase classname="$DRAFT_SUITE" name="accountAndGlobalRolePendingScopesVersionOnlyTheirExistingAccountTuple()"/>
  <testcase classname="$DRAFT_SUITE" name="accountAndGlobalRolePendingScopesVersionOnlyTheirExistingAccountTuple()"/>
</testsuite>
XML
if strict_duplicate_case_output="$(inspect_strict 2>&1)"; then
  echo "Strict inspection unexpectedly accepted duplicate execution of a required case." >&2
  exit 1
fi
grep -Fq "Duplicate required case: $DRAFT_CASE" <<<"$strict_duplicate_case_output"

cat >"$STRICT_REPORTS/TEST-draft.xml" <<XML
<testsuite name="$DRAFT_SUITE" tests="1" failures="0" errors="0" skipped="1">
  <testcase classname="$DRAFT_SUITE" name="accountAndGlobalRolePendingScopesVersionOnlyTheirExistingAccountTuple()"><skipped/></testcase>
</testsuite>
XML
if strict_skipped_output="$(inspect_strict 2>&1)"; then
  echo "Strict inspection unexpectedly accepted a skipped required case." >&2
  exit 1
fi
grep -Fq "Required suite is failing, errored, or skipped: $DRAFT_SUITE" <<<"$strict_skipped_output"
grep -Fq "outcome=skipped" <<<"$strict_skipped_output"

printf '<testsuite name="broken"' >"$STRICT_REPORTS/TEST-malformed.xml"
if strict_malformed_output="$(inspect_strict 2>&1)"; then
  echo "Strict inspection unexpectedly accepted malformed XML." >&2
  exit 1
fi
grep -Fq "Malformed report(s): 1" <<<"$strict_malformed_output"

python3 - "$ROOT_DIR" <<'PY'
from __future__ import annotations

import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
workflow = (root / ".github/workflows/ci.yml").read_text()
suite = "integration.net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFencePostgresIntegrationTest"
test_source = (root / "services/account-service/src/test/java/integration/net/firedevops/firemud/accountservice/authordraft/DraftAuthorizationFencePostgresIntegrationTest.java").read_text()
methods = re.findall(r"(?m)^  void ([A-Za-z0-9_]+)\(", test_source)
if len(methods) != 31:
    raise SystemExit(f"Expected 31 Account Draft fence cases, found {len(methods)}")
if methods[:4] != [
    "accountAndGlobalRolePendingScopesVersionOnlyTheirExistingAccountTuple",
    "producerLocksDeduplicatedAccountsInUuidOrderBeforeAnySourceLocks",
    "scopeInsertVersionsAccountBeforeTheExistingParentLockingTrigger",
    "applicableMissingOrMalformedAccountScopesDenyWithoutCreatingAccountOrIntent",
]:
    raise SystemExit("The first four Account Draft fence source lock/version cases changed")
if methods[-1] != "issuerSourceKeysPreserveLegacyAndUnicodeIdentityThroughExactByteBound":
    raise SystemExit("The final Account Draft fence byte-bound case changed")

for method in methods:
    required = f"--require-case '{suite}#{method}()'"
    if workflow.count(required) != 1:
        raise SystemExit(f"CI must require exactly one execution of {suite}#{method}()")

if workflow.count(f"--require-suite {suite}") != 1:
    raise SystemExit("CI must require the Draft Authorization Fence suite exactly once")
if "if: ${{ !cancelled() && matrix.module == 'account-service' }}" not in workflow:
    raise SystemExit("Strict Account Draft fence verification must run unless cancelled")
if "if: ${{ always() && matrix.module == 'account-service' }}" not in workflow:
    raise SystemExit("Account Draft fence raw XML capture must always run")
if "TEST-*DraftAuthorizationFencePostgresIntegrationTest.xml" not in workflow:
    raise SystemExit("CI must capture the Account Draft fence raw XML report")
if "actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9" not in workflow:
    raise SystemExit("Account Draft fence XML capture must use the pinned workflow artifact action")
check_step = workflow.index("- name: 🧪 Run Gradle Checks")
capture_step = workflow.index("- name: 📤 Capture Account Draft authorization fence PostgreSQL XML")
verify_step = workflow.index("- name: 🔎 Verify Account Draft authorization fence PostgreSQL proof")
if not check_step < capture_step < verify_step:
    raise SystemExit("Account Draft fence XML capture and strict verification must follow Gradle checks in order")
if "bash dev-tools/validation/inspect-test-results.sh \\\n            --strict" not in workflow[verify_step:]:
    raise SystemExit("Account Draft fence verification must invoke the strict JUnit inspector")
PY

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

python3 -m unittest discover -s "$ROOT_DIR/dev-tools/tests" -p '*gradle*test*.py'

echo "gradle proof tooling contract checks passed"
