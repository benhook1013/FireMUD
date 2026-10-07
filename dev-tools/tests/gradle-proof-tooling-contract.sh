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
if grep -q "STRICT_OUTPUT_SENTINEL" <<<"$strict_green_output"; then
  echo "Strict inspection leaked test output." >&2
  exit 1
fi

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
if grep -q "SENSITIVE_" <<<"$failing_output"; then
  echo "Strict inspection leaked failure details." >&2
  exit 1
fi

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

python3 - "$ROOT_DIR" <<'PY'
from __future__ import annotations

import copy
import re
import sys
from pathlib import Path

import yaml

root = Path(sys.argv[1])
workflow = yaml.safe_load((root / ".github/workflows/ci.yml").read_text(encoding="utf-8"))

SOCIAL_SUITE = "net.firedevops.firemud.socialgroups.SocialAccountUuidMigrationIntegrationTest"
SOCIAL_CASES = (
    f"{SOCIAL_SUITE}#freshAndEmptyV9UpgradeConvergeAllAccountColumnsWithoutChangingOtherIdentityTypes()",
    f"{SOCIAL_SUITE}#concurrentWriterCommitsBeforeEmptyCheckAndItsEvidenceIsPreserved()",
)
LOGGING_SUITE = "integration.net.firedevops.firemud.loggingadmin.LoggingAccountUuidMigrationIntegrationTest"
LOGGING_CASES = (
    f"{LOGGING_SUITE}#freshSchemaMigratesWithCanonicalAccountUuidsAndNumericLocalIdentifiers()",
    f"{LOGGING_SUITE}#emptyAffectedTablesUpgradeFromV5AndGenericLogAccountIdRemainsNumeric()",
    f"{LOGGING_SUITE}#retainedModerationActionRefusesWithoutChangingRowsOrMigrationHistory()",
    f"{LOGGING_SUITE}#retainedPlayerReportRefusesWithoutChangingRowsOrMigrationHistory()",
    f"{LOGGING_SUITE}#migrationWaitsForConcurrentWriterAndRefusesAfterItsRowCommits()",
)
SOCIAL_STEP = "🔎 Verify Social Account UUID migration PostgreSQL proof"
LOGGING_STEP = "🔎 Verify Logging Account UUID migration PostgreSQL proof"
ACCOUNT_STEP = "🔎 Verify Account PostgreSQL proof cases"
RECONCILIATION_SUITE = "net.firedevops.firemud.accountservice.AccountJoinReconciliationPostgresIntegrationTest"
RECONCILIATION_CASES = (
    f"{RECONCILIATION_SUITE}#exactPersistedEvidenceRecoversExpiredScopeAndSameRequestRetryWithoutDuplicates()",
    f"{RECONCILIATION_SUITE}#twoWorkersRecoverThresholdPendingEvidenceWithoutDuplicatingMembershipOrAudit()",
    f"{RECONCILIATION_SUITE}#retainedScopeDeletionAndExpiredScopeWithoutMembershipProofRemainPendingWithDiagnostics()",
)
STORAGE_SUITE = "integration.net.firedevops.firemud.accountservice.AccountConnectStorageUuidMigrationIntegrationTest"
STORAGE_CASES = (
    f"{STORAGE_SUITE}#freshMigrationsAndEmptyV39UpgradeUseCanonicalUuidColumns()",
    f"{STORAGE_SUITE}#migrationWaitsForConcurrentWriterThenRejectsItsCommittedEvidence()",
)
CHECK_STEP = "🧪 Run Gradle Checks"
ACCOUNT_PUBLICATION_CAPTURE_STEP = "Capture Account publication participation PostgreSQL raw XML"
ACCOUNT_INTEGRATION_TEST_COMMAND = "./gradlew :account-service:integrationTest"
GAME_DESIGN_OWNER_STEP = "Run complete Game Design owner component PostgreSQL proof"
REALM_POLICY_SOURCE_SUITE = "net.firedevops.firemud.gamedesign.draft.RealmPolicySourcePostgresIntegrationTest"
REALM_POLICY_SOURCE_CASES = (
    f"{REALM_POLICY_SOURCE_SUITE}#freshGenesisCreatesNoFakeCommitAndFirstActualPolicyCommitReplaysExactly()",
    f"{REALM_POLICY_SOURCE_SUITE}#incompleteDraftPoliciesCanSynchronizeButCannotBecomePublicationCapture()",
    f"{REALM_POLICY_SOURCE_SUITE}#retainedVersionUpdateIsNotFreshGenesisAndActualCreationRollbackLeavesNoReceipt()",
    f"{REALM_POLICY_SOURCE_SUITE}#isolatedSiblingSourceComposesOneOwnerResultAndCommandOnlyFenceInheritsPolicy()",
    f"{REALM_POLICY_SOURCE_SUITE}#absentBaselineDeniesAndApplicationReplayAndMismatchBindActualCoordinatorResult()",
    f"{REALM_POLICY_SOURCE_SUITE}#rollbackAndPartialWritesPreserveLastVisibleSourceThenDisjointCommitInheritsExactProvenance()",
    f"{REALM_POLICY_SOURCE_SUITE}#freezeAndSourceWriterUseSameVersionLockAndRollbackDoesNotLeaveCapture()",
)
COMMAND_SOURCE_SUITE = "net.firedevops.firemud.gamedesign.draft.CommandSourcePostgresIntegrationTest"
COMMAND_SOURCE_CASES = (
    f"{COMMAND_SOURCE_SUITE}#freshGenesisMixedUpsertRollbackRetryDisjointInheritanceDeleteAndPendingFreeze()",
    f"{COMMAND_SOURCE_SUITE}#retainedPreSourceVersionIsNotBackfilledAsAnEmptyCommandSet()",
)
GAME_DESIGN_SOURCE_SUITE = "net.firedevops.firemud.gamedesign.draft.GameDesignSourcePostgresIntegrationTest"
GAME_DESIGN_SOURCE_CASES = (
    f"{GAME_DESIGN_SOURCE_SUITE}#versionInsertSharesGenesisAndPolicyCommandMixedCommitsKeepOneExactOutcome()",
    f"{GAME_DESIGN_SOURCE_SUITE}#sourceRollbackIncompleteScopesAndPublicationCaptureFailClosed()",
    f"{GAME_DESIGN_SOURCE_SUITE}#completeSelectedSourcesFreezeTogetherAndExactRetryReadsStoredBytes()",
)
SELECTED_COMMAND_SOURCE_BUNDLE_SUITE = "net.firedevops.firemud.gamedesign.draft.SelectedCommandSourceBundlePostgresIntegrationTest"
SELECTED_COMMAND_SOURCE_BUNDLE_CASES = (
    f"{SELECTED_COMMAND_SOURCE_BUNDLE_SUITE}#bundleUsesCompleteSelectedCaptureAndRetryKeepsItsOriginalBytesAndIdentity()",
    f"{SELECTED_COMMAND_SOURCE_BUNDLE_SUITE}#missingCaptureAndOperationSubstitutionDoNotWriteBundle()",
)
REALM_POLICY_PUBLICATION_SUITE = "integration.net.firedevops.firemud.gamedesign.publication.RealmPolicyPublicationPostgresIntegrationTest"
REALM_POLICY_PUBLICATION_CASES = (
    f"{REALM_POLICY_PUBLICATION_SUITE}#actualSealAssociatesCompleteSetAndReadKeepsOriginalEpochAfterVersionMovement()",
    f"{REALM_POLICY_PUBLICATION_SUITE}#sealRollbackLeavesNoAssociationAndExactPublicationRetryAllocatesOneStableIdentity()",
    f"{REALM_POLICY_PUBLICATION_SUITE}#releaseWithoutTypedSourceRemainsValidButMissingCaptureWithSourceFailsClosed()",
    f"{REALM_POLICY_PUBLICATION_SUITE}#openSetRejectsReplacedSourceAndWrongOrdinalWhileKeepingInheritedProvenance()",
)
GAME_TENANT_CREATION_STEP = "Verify Game Design fresh tenant creation PostgreSQL proof"
GAME_AUTHORED_WORLD_SOURCE_STEP = "Verify Game Design authored-world source PostgreSQL proof"
GAME_DESIGN_CAPTURE_STEP = "Capture Game Design owner component proof before later test selectors"
GAME_DESIGN_LATER_SELECTOR_STEP = "Run Game Design authored-source launch descriptor PostgreSQL and socket mTLS proof"
GAME_TENANT_CREATION_SUITE = "integration.net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepositoryIntegrationTest"
GAME_TENANT_CREATION_CASES = (
    f"{GAME_TENANT_CREATION_SUITE}#createCandidateExactRetryReturnsCommittedReceiptWithoutSecondGameWrite()",
    f"{GAME_TENANT_CREATION_SUITE}#concurrentDuplicateCreationRequestsConvergeOnOneUuidAndReceipt()",
    f"{GAME_TENANT_CREATION_SUITE}#runtimeIdentityLookupReadsOnlyExactNewAndRetainedGameDesignRows()",
)
GAME_AUTHORED_WORLD_SOURCE_SUITE = "integration.net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepositoryIntegrationTest"
GAME_AUTHORED_WORLD_SOURCE_CASES = (
    f"{GAME_AUTHORED_WORLD_SOURCE_SUITE}#registersAndReadsExactFreshAndRetainedV29ToV30Sources()",
    f"{GAME_AUTHORED_WORLD_SOURCE_SUITE}#failedOwnerCommitRollsBackBothTenantSelectorAndWorldSource()",
    f"{GAME_AUTHORED_WORLD_SOURCE_SUITE}#concurrentExactRegistrationRetriesReturnOnePersistedReceipt()",
    f"{GAME_AUTHORED_WORLD_SOURCE_SUITE}#concurrentDifferentTenantsCannotClaimTheSameTenantSlug()",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def find_step(document, name):
    steps = document["jobs"]["build-and-test"]["steps"]
    matches = [step for step in steps if step.get("name") == name]
    require(len(matches) == 1, f"expected exactly one CI step named {name!r}")
    return matches[0]


def validate(document) -> None:
    steps = document["jobs"]["build-and-test"]["steps"]
    check = find_step(document, CHECK_STEP)
    check_run = check.get("run")
    require(isinstance(check_run, str), "Gradle check step has no run script")
    require("continue-on-error" not in check, "Gradle check step must remain fail-closed")
    branch = re.search(r"if \[\[(.*?)\]\]; then\s*(.*?)\s*else", check_run, re.DOTALL)
    require(branch is not None, "Gradle check no-cache branch is missing")
    modules = set(re.findall(r'\$\{\{ matrix\.module \}\}" == "([^"]+)"', branch[1]))
    require(
        {"social-groups-service", "logging-admin-service"} <= modules,
        "Social and Logging must use the fresh Gradle check branch",
    )
    for flag in ("--no-build-cache", "--no-configuration-cache"):
        require(flag in branch[2], f"fresh Gradle check branch omits {flag}")

    check_index = document["jobs"]["build-and-test"]["steps"].index(check)
    account_capture = find_step(document, ACCOUNT_PUBLICATION_CAPTURE_STEP)
    account_capture_index = steps.index(account_capture)
    require(account_capture_index > check_index, "Account publication XML must be captured after the full Account check")
    intervening_steps = steps[check_index + 1 : account_capture_index]
    require(
        all(ACCOUNT_INTEGRATION_TEST_COMMAND not in str(step.get("run", "")) for step in intervening_steps),
        "Account integrationTest must not rerun between the full Account check and raw XML capture",
    )
    for name, service, suite, cases in (
        (SOCIAL_STEP, "social-groups-service", SOCIAL_SUITE, SOCIAL_CASES),
        (LOGGING_STEP, "logging-admin-service", LOGGING_SUITE, LOGGING_CASES),
        (ACCOUNT_STEP, "account-service", RECONCILIATION_SUITE, RECONCILIATION_CASES),
        (ACCOUNT_STEP, "account-service", STORAGE_SUITE, STORAGE_CASES),
    ):
        proof = find_step(document, name)
        require(
            document["jobs"]["build-and-test"]["steps"].index(proof) > check_index,
            f"{name} must follow Gradle checks",
        )
        require(proof.get("if") == f"${{{{ matrix.module == '{service}' }}}}", f"{name} has wrong module condition")
        require("continue-on-error" not in proof, f"{name} must remain fail-closed")
        run = proof.get("run")
        require(isinstance(run, str), f"{name} has no run script")
        require("bash dev-tools/validation/inspect-test-results.sh" in run, f"{name} must use the existing inspector")
        require(re.search(r"(?m)^\s*--strict\s*\\?$", run) is not None, f"{name} must require strict inspection")
        require(run.count(f"--require-suite {suite}") == 1, f"{name} must require exactly one migration suite")
        for case in cases:
            require(run.count(case) == 1, f"{name} must require exactly one execution of {case}")
        require(re.search(rf"(?m)^\s*{re.escape(service)}\s*$", run) is not None, f"{name} targets the wrong service")

    owner = find_step(document, GAME_DESIGN_OWNER_STEP)
    owner_run = owner.get("run", "")
    for suite, cases, description in (
        (COMMAND_SOURCE_SUITE, COMMAND_SOURCE_CASES, "immutable command source"),
        (GAME_DESIGN_SOURCE_SUITE, GAME_DESIGN_SOURCE_CASES, "immutable Game Design source"),
        (SELECTED_COMMAND_SOURCE_BUNDLE_SUITE, SELECTED_COMMAND_SOURCE_BUNDLE_CASES, "selected command source bundle"),
    ):
        require(owner_run.count(f"--tests {suite}") == 1,
                f"Game Design owner proof must execute the {description} suite exactly once")
        require(owner_run.count(f"--require-suite {suite}") == 1,
                f"Game Design owner proof must require the {description} suite")
        for case in cases:
            require(owner_run.count(case) == 1,
                    f"Game Design owner proof must require exactly one execution of {case}")
    require(owner_run.count(f"--tests {REALM_POLICY_SOURCE_SUITE}") == 1,
            "Game Design owner proof must execute the immutable realm-policy source suite exactly once")
    require(owner_run.count(f"--require-suite {REALM_POLICY_SOURCE_SUITE}") == 1,
            "Game Design owner proof must require the immutable realm-policy source suite")
    for case in REALM_POLICY_SOURCE_CASES:
        require(owner_run.count(case) == 1,
                f"Game Design owner proof must require exactly one execution of {case}")
    require(owner_run.count(f"--tests {REALM_POLICY_PUBLICATION_SUITE}") == 1,
            "Game Design owner proof must execute the published-policy association suite exactly once")
    require(owner_run.count(f"--require-suite {REALM_POLICY_PUBLICATION_SUITE}") == 1,
            "Game Design owner proof must require the published-policy association suite")
    for case in REALM_POLICY_PUBLICATION_CASES:
        require(owner_run.count(case) == 1,
                f"Game Design owner proof must require exactly one execution of {case}")
    tenant_creation = find_step(document, GAME_TENANT_CREATION_STEP)
    authored_source = find_step(document, GAME_AUTHORED_WORLD_SOURCE_STEP)
    capture = find_step(document, GAME_DESIGN_CAPTURE_STEP)
    later_selector = find_step(document, GAME_DESIGN_LATER_SELECTOR_STEP)
    owner_index = steps.index(owner)
    tenant_index = steps.index(tenant_creation)
    source_index = steps.index(authored_source)
    capture_index = steps.index(capture)
    later_selector_index = steps.index(later_selector)
    require(
        (tenant_index, source_index, capture_index)
        == (owner_index + 1, owner_index + 2, owner_index + 3),
        "Game Design fresh tenant and authored-source proof checks must immediately follow the owner component proof",
    )
    require(capture_index < later_selector_index, "Game Design owner component reports must be captured before later test selectors")
    for proof, suite, cases in (
        (tenant_creation, GAME_TENANT_CREATION_SUITE, GAME_TENANT_CREATION_CASES),
        (authored_source, GAME_AUTHORED_WORLD_SOURCE_SUITE, GAME_AUTHORED_WORLD_SOURCE_CASES),
    ):
        require(
            proof.get("if") == "${{ !cancelled() && matrix.module == 'game-design-service' }}",
            f"{proof['name']} must run for Game Design unless cancelled",
        )
        require("continue-on-error" not in proof, f"{proof['name']} must remain fail-closed")
        run = proof.get("run")
        require(isinstance(run, str), f"{proof['name']} has no run script")
        require("bash dev-tools/validation/inspect-test-results.sh" in run, f"{proof['name']} must use the existing inspector")
        require(re.search(r"(?m)^\s*--strict\s*\\?$", run) is not None, f"{proof['name']} must require strict inspection")
        require(run.count(f"--require-suite {suite}") == 1, f"{proof['name']} must require exactly one suite")
        for case in cases:
            require(run.count(case) == 1, f"{proof['name']} must require exactly one execution of {case}")
        require(re.search(r"(?m)^\s*game-design-service\s*$", run) is not None, f"{proof['name']} targets the wrong service")
    require(
        capture.get("if") == "${{ always() && matrix.module == 'game-design-service' }}",
        "Game Design owner component reports must still be captured after an earlier proof failure",
    )
    require(capture.get("uses", "").startswith("actions/upload-artifact@"), "Game Design owner component reports must use artifact capture")


validate(workflow)


def mutation_must_fail(label: str, mutate) -> None:
    changed = copy.deepcopy(workflow)
    mutate(changed)
    try:
        validate(changed)
    except ValueError:
        return
    raise SystemExit(f"CI UUID migration proof contract accepted mutation: {label}")


def replace_run(document, step_name: str, old: str, new: str = "") -> None:
    step = find_step(document, step_name)
    run = step["run"]
    require(run.count(old) == 1, f"mutation fixture could not find unique text {old!r}")
    step["run"] = run.replace(old, new, 1)


mutation_must_fail("missing strict mode", lambda doc: replace_run(doc, SOCIAL_STEP, "--strict", "strict"))
mutation_must_fail(
    "missing realm-policy source execution",
    lambda doc: replace_run(doc, GAME_DESIGN_OWNER_STEP, f"--tests {REALM_POLICY_SOURCE_SUITE}", ""),
)
mutation_must_fail(
    "missing realm-policy source suite inspection",
    lambda doc: replace_run(doc, GAME_DESIGN_OWNER_STEP, f"--require-suite {REALM_POLICY_SOURCE_SUITE}", ""),
)
mutation_must_fail(
    "missing realm-policy source freeze-lock proof",
    lambda doc: replace_run(doc, GAME_DESIGN_OWNER_STEP, REALM_POLICY_SOURCE_CASES[-1], ""),
)
for index, case in enumerate(REALM_POLICY_SOURCE_CASES[:4], start=1):
    mutation_must_fail(
        f"missing realm-policy source genesis proof {index}",
        lambda doc, required_case=case: replace_run(doc, GAME_DESIGN_OWNER_STEP, required_case, ""),
    )
for suite, cases, label in (
    (COMMAND_SOURCE_SUITE, COMMAND_SOURCE_CASES, "command source"),
    (GAME_DESIGN_SOURCE_SUITE, GAME_DESIGN_SOURCE_CASES, "Game Design source"),
    (SELECTED_COMMAND_SOURCE_BUNDLE_SUITE, SELECTED_COMMAND_SOURCE_BUNDLE_CASES, "selected command source bundle"),
):
    mutation_must_fail(
        f"missing {label} execution selector",
        lambda doc, selected_suite=suite: replace_run(doc, GAME_DESIGN_OWNER_STEP, f"--tests {selected_suite}", ""),
    )
    mutation_must_fail(
        f"missing {label} strict suite requirement",
        lambda doc, selected_suite=suite: replace_run(doc, GAME_DESIGN_OWNER_STEP, f"--require-suite {selected_suite}", ""),
    )
    for index, case in enumerate(cases, start=1):
        mutation_must_fail(
            f"missing {label} required case {index}",
            lambda doc, required_case=case: replace_run(doc, GAME_DESIGN_OWNER_STEP, required_case, ""),
        )
mutation_must_fail(
    "missing published-policy association execution",
    lambda doc: replace_run(doc, GAME_DESIGN_OWNER_STEP, f"--tests {REALM_POLICY_PUBLICATION_SUITE}", ""),
)
mutation_must_fail(
    "missing published-policy rollback proof",
    lambda doc: replace_run(doc, GAME_DESIGN_OWNER_STEP, REALM_POLICY_PUBLICATION_CASES[1], ""),
)
mutation_must_fail(
    "missing published-policy selected-source row proof",
    lambda doc: replace_run(doc, GAME_DESIGN_OWNER_STEP, REALM_POLICY_PUBLICATION_CASES[-1], ""),
)
mutation_must_fail(
    "missing required suite",
    lambda doc: replace_run(doc, LOGGING_STEP, f"--require-suite {LOGGING_SUITE}", ""),
)
mutation_must_fail(
    "missing Account reconciliation suite",
    lambda doc: replace_run(doc, ACCOUNT_STEP, f"--require-suite {RECONCILIATION_SUITE}", ""),
)
mutation_must_fail("missing required case", lambda doc: replace_run(doc, SOCIAL_STEP, SOCIAL_CASES[0], ""))
mutation_must_fail(
    "wrong module condition",
    lambda doc: find_step(doc, SOCIAL_STEP).__setitem__("if", "${{ matrix.module == 'logging-admin-service' }}"),
)
mutation_must_fail(
    "weakened continue-on-error",
    lambda doc: find_step(doc, LOGGING_STEP).__setitem__("continue-on-error", True),
)
mutation_must_fail(
    "fresh-cache bypass removed",
    lambda doc: replace_run(doc, CHECK_STEP, "--no-build-cache", ""),
)
mutation_must_fail(
    "configuration-cache bypass removed",
    lambda doc: replace_run(doc, CHECK_STEP, "--no-configuration-cache", ""),
)


def insert_account_integration_test_before_capture(document) -> None:
    steps = document["jobs"]["build-and-test"]["steps"]
    capture = find_step(document, ACCOUNT_PUBLICATION_CAPTURE_STEP)
    steps.insert(
        steps.index(capture),
        {
            "name": "Unexpected Account rerun",
            "run": f"{ACCOUNT_INTEGRATION_TEST_COMMAND} --tests ExampleTest",
        },
    )


mutation_must_fail(
    "Account integrationTest rerun before raw XML capture",
    insert_account_integration_test_before_capture,
)


def move_step_after(document, name: str, anchor_name: str) -> None:
    steps = document["jobs"]["build-and-test"]["steps"]
    step = find_step(document, name)
    anchor = find_step(document, anchor_name)
    steps.remove(step)
    steps.insert(steps.index(anchor) + 1, step)


mutation_must_fail(
    "Game Design proof moved after a later Gradle selector",
    lambda doc: move_step_after(doc, GAME_TENANT_CREATION_STEP, GAME_DESIGN_LATER_SELECTOR_STEP),
)

print("CI UUID migration proof workflow contract checks passed")
PY

echo "gradle proof tooling contract checks passed"
