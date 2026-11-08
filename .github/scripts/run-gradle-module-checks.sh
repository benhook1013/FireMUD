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

# Controlled Account CI reliability experiment: serialize the complete selected check.
# Existing integrationTest logic then uses one fork; preparation and other modules stay at two.
# This does not establish the cause of the observed parallel test-dispatch shutdown wait.
CHECK_WORKERS=2
if [[ "$MODULE" == "account-service" && "$PARTITION" != "full" ]]; then
  CHECK_WORKERS=1
fi
readonly CHECK_WORKERS

# Keep the original recovery policy and complete check; diagnostics never retry it.
CHECK_LOG="$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/firemud-module-check.XXXXXX")" || CHECK_LOG=""
readonly CHECK_LOG
trap 'rm -f -- "$CHECK_LOG"' EXIT
if [[ -z "$CHECK_LOG" ]]; then
  printf 'FIREMUD_STALL_ACTIVATION status=disabled-capture-unavailable\n' >&2
  echo "Compile-failure log unavailable; running the original check without capture" >&2
  exec bash "$ROOT_DIR/dev-tools/validation/run-gradle-with-dependency-recovery.sh" \
    --runner "$ROOT_DIR/gradlew" -- \
    --max-workers="$CHECK_WORKERS" \
    '-Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8' \
    ":${MODULE}:check" "${CHECK_ARGS[@]}" --profile
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
  --max-workers="$CHECK_WORKERS" \
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
exit "$check_status"
