#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly ROOT_DIR
readonly MODULE="${1:-}"
readonly PARTITION="${2:-full}"
if [[ $# -lt 1 || $# -gt 2 || ! "$MODULE" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ || \
  ( "$MODULE" == "account-service" && "$PARTITION" != "full" && ! "$PARTITION" =~ ^[1-4]$ ) || \
  ( "$MODULE" == "game-session-service" && "$PARTITION" != "full" && ! "$PARTITION" =~ ^[1-4]$ ) || \
  ( "$MODULE" == "world-management-service" && "$PARTITION" != "full" && ! "$PARTITION" =~ ^[1-2]$ ) || \
  ( "$MODULE" != "account-service" && "$MODULE" != "game-session-service" && "$MODULE" != "world-management-service" && $# -eq 2 && "$PARTITION" != "full" ) ]]; then
  echo "usage: run-gradle-module-checks.sh <module-name> [full|partition] (Account: 1-4; Game Session: 1-4; World: 1-2)" >&2
  exit 64
fi

declare -a CHECK_ARGS=(-PfullCheck)
require_physical_proof=false
# These required physical suites must execute, not restore cached JUnit results.
if [[ "$MODULE" == "game-design-service" || "$MODULE" == "logging-admin-service" || "$MODULE" == "social-groups-service" ]]; then
  require_physical_proof=true
  CHECK_ARGS+=(--no-build-cache --no-configuration-cache ":${MODULE}:integrationTest" --rerun)
  # A skipped/no-source task must not satisfy this invocation from old XML.
  # Invalidate only generated JUnit reports for this module before Gradle runs.
  test_results="$ROOT_DIR/services/$MODULE/build/test-results"
  for proof_dir in "$ROOT_DIR/services" "$ROOT_DIR/services/$MODULE" \
    "$ROOT_DIR/services/$MODULE/build" "$test_results"; do
    if [[ -L "$proof_dir" ]]; then
      echo "Required database proof refuses a symlinked result path" >&2
      exit 1
    fi
  done
  if [[ -d "$test_results" ]]; then
    if [[ -n "$(find "$test_results" -type l -print -quit)" ]]; then
      echo "Required database proof refuses symlinked generated results" >&2
      exit 1
    fi
    find "$test_results" -type f -name 'TEST-*.xml' -delete
  fi
fi

verify_required_physical_proof() {
  case "$MODULE" in
    game-design-service)
      local suite="net.firedevops.firemud.gamedesign.service.VersionAssetPublicationMinioPostgresIntegrationTest"
      bash "$ROOT_DIR/dev-tools/validation/inspect-test-results.sh" --strict \
        --require-suite "$suite" \
        --require-case "$suite#composesPostgresSnapshotAndCandidateWithMinioBeforeWriteAndRetriesAfterLostAcknowledgement()" \
        "$MODULE"
      ;;
    logging-admin-service)
      local moderation="net.firedevops.firemud.loggingadmin.ModerationAccountUuidMigrationPostgresIntegrationTest"
      local report="net.firedevops.firemud.loggingadmin.ReportAccountUuidMigrationPostgresIntegrationTest"
      bash "$ROOT_DIR/dev-tools/validation/inspect-test-results.sh" --strict \
        --require-suite "$moderation" \
        --require-suite "$report" \
        --require-case "$moderation#emptySchemaMigratesAndKeepsExactUuidWithoutChangingPolicyFields()" \
        --require-case "$moderation#populatedLegacyRowsBlockMigrationWithoutChangingRowsOrMigrationHistory()" \
        --require-case "$moderation#concurrentModerationWriterBlocksMigrationThenItsCommittedRowForcesRefusal()" \
        --require-case "$report#freshSchemaMigratesWithCanonicalAccountUuidsAndNumericLocalIdentifiers()" \
        --require-case "$report#emptyAffectedTablesUpgradeFromV1007AndGenericLogAccountIdRemainsNumeric()" \
        --require-case "$report#existingCanonicalModerationRowsAreUntouchedByReportOnlyMigration()" \
        --require-case "$report#retainedPlayerReportRefusesWithoutChangingRowsOrMigrationHistory()" \
        --require-case "$report#migrationWaitsForConcurrentWriterAndRefusesAfterItsRowCommits()" \
        "$MODULE"
      ;;
    social-groups-service)
      local suite="integration.net.firedevops.firemud.socialgroups.repository.SocialAccountUuidMigrationPostgresIntegrationTest"
      bash "$ROOT_DIR/dev-tools/validation/inspect-test-results.sh" --strict \
        --require-suite "$suite" \
        --require-case "$suite#emptyLegacyFrontierConvergesAndTypedRepositoriesRoundTripCanonicalAccountUuids()" \
        --require-case "$suite#[1] table = \"guilds\"" \
        --require-case "$suite#[2] table = \"guild_members\"" \
        --require-case "$suite#[3] table = \"chat_messages\"" \
        --require-case "$suite#[4] table = \"friend_links\"" \
        --require-case "$suite#[5] table = \"mail_messages\"" \
        --require-case "$suite#[6] table = \"account_friend_links\"" \
        --require-case "$suite#concurrentWriterCommitsBeforeEmptyCheckAndItsEvidenceIsPreserved()" \
        "$MODULE"
      ;;
  esac
}
if [[ ( "$MODULE" == "account-service" || "$MODULE" == "game-session-service" || "$MODULE" == "world-management-service" ) && "$PARTITION" != "full" ]]; then
  SERVICE_DIR="$ROOT_DIR/services/$MODULE"
  PARTITION_DIR="$SERVICE_DIR/build/slow-service-ci-partition"
  declare -a PREPARE_TASKS=(":${MODULE}:testClasses")
  declare -a CLASS_DIR_ARGS=(
    --classes-dir "test=$SERVICE_DIR/build/classes/java/test"
  )
  for task_name in integrationTest crossServiceTest; do
    source_set="${task_name/integrationTest/integration}"
    if [[ "$task_name" == "crossServiceTest" ]]; then
      source_set="crossservice"
    fi
    if [[ -d "$SERVICE_DIR/src/test/java/$source_set" ]]; then
      PREPARE_TASKS+=(":${MODULE}:${task_name}Classes")
      CLASS_DIR_ARGS+=(
        --classes-dir "$task_name=$SERVICE_DIR/build/classes/java/$task_name"
      )
    fi
  done

  declare -a PREPARE_LOG_ARGS=()
  if [[ "$MODULE" == "world-management-service" ]]; then
    PREPARE_LOG_ARGS+=(--info)
  fi

  # Build every current service test class first, then freeze one source-derived selector.
  bash "$ROOT_DIR/dev-tools/validation/run-gradle-with-dependency-recovery.sh" \
    --runner "$ROOT_DIR/gradlew" -- \
    --max-workers=2 \
    '-Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8' \
    "${PREPARE_TASKS[@]}" -PfullCheck --profile "${PREPARE_LOG_ARGS[@]}"
  python3 "$ROOT_DIR/dev-tools/validation/slow_service_ci_test_partition.py" plan \
    --module "$MODULE" \
    --partition "$PARTITION" \
    --source-revision "${GITHUB_SHA:-local}" \
    --run-id "${GITHUB_RUN_ID:-0}" \
    --run-attempt "${GITHUB_RUN_ATTEMPT:-0}" \
    --output-dir "$PARTITION_DIR" \
    "${CLASS_DIR_ARGS[@]}"
  CHECK_ARGS+=("-PslowServiceCiPartitionModule=$MODULE" "-PslowServiceCiPartition=$PARTITION")
fi

# Keep the original recovery policy and complete check; diagnostics never retry it.
CHECK_LOG="$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/firemud-module-check.XXXXXX")" || CHECK_LOG=""
readonly CHECK_LOG
trap 'rm -f -- "$CHECK_LOG"' EXIT
if [[ -z "$CHECK_LOG" ]]; then
  printf 'FIREMUD_STALL_ACTIVATION status=disabled-capture-unavailable\n' >&2
  echo "Compile-failure log unavailable; running the original check without capture" >&2
  if [[ "$require_physical_proof" == false ]]; then
    # Preserve the existing exec/process boundary for every unaffected module.
    exec bash "$ROOT_DIR/dev-tools/validation/run-gradle-with-dependency-recovery.sh" \
      --runner "$ROOT_DIR/gradlew" -- \
      --max-workers=2 \
      '-Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8' \
      ":${MODULE}:check" "${CHECK_ARGS[@]}" --profile
  fi
  if bash "$ROOT_DIR/dev-tools/validation/run-gradle-with-dependency-recovery.sh" \
    --runner "$ROOT_DIR/gradlew" -- \
    --max-workers=2 \
    '-Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8' \
    ":${MODULE}:check" "${CHECK_ARGS[@]}" --profile; then
    verify_required_physical_proof
    exit 0
  else
    exit "$?"
  fi
fi
declare -a CAPTURE_ARGS=()
stall_activation=disabled-scope
# Observe Account's stalled forks only on an isolated hosted Linux runner.
if [[ "$MODULE" == "account-service" && "$PARTITION" != "full" &&
  "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted &&
  "${RUNNER_OS:-}" == Linux ]]; then
  CAPTURE_ARGS+=(--root "$ROOT_DIR" --stall-output
    "$ROOT_DIR/build/reports/gradle-stall/$MODULE/partition-$PARTITION")
  stall_activation=enabled
elif [[ "$MODULE" == "account-service" && "$PARTITION" != "full" ]]; then
  stall_activation=disabled-runner-guard
fi
# Fixed reason only: do not print environment values or invocation arguments.
printf 'FIREMUD_STALL_ACTIVATION status=%s\n' "$stall_activation" >&2
set +e
bash "$ROOT_DIR/dev-tools/validation/run-gradle-with-dependency-recovery.sh" \
  --runner "$ROOT_DIR/gradlew" -- \
  --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8' \
  ":${MODULE}:check" \
  "${CHECK_ARGS[@]}" \
  --profile --info --console=plain 2>&1 | \
  (
    python3 "$ROOT_DIR/dev-tools/validation/collect-gradle-compile-failure.py" --capture-log "$CHECK_LOG" "${CAPTURE_ARGS[@]}"
    capture_status=$?
    if (( capture_status != 0 )); then
      cat >/dev/null
    fi
    exit "$capture_status"
  )
check_status=${PIPESTATUS[0]}
set -e
if (( check_status > 0 && check_status < 128 )); then
  # Diagnostic errors are visible but cannot replace the original check result.
  python3 "$ROOT_DIR/dev-tools/validation/collect-gradle-compile-failure.py" \
    --root "$ROOT_DIR" --log "$CHECK_LOG" --check-exit "$check_status" \
    --output "$ROOT_DIR/build/reports/compile-failure/$MODULE" || \
    echo "Compile-failure evidence collection unavailable; preserving check exit $check_status" >&2
fi
if (( check_status == 0 )); then
  verify_required_physical_proof
fi
exit "$check_status"
