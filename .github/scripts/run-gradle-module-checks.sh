#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly ROOT_DIR
readonly MODULE="${1:-}"
readonly PARTITION="${2:-full}"
if [[ $# -lt 1 || $# -gt 2 || ! "$MODULE" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ || \
  ( ( "$MODULE" == "account-service" || "$MODULE" == "game-session-service" ) && $# -eq 2 && "$PARTITION" != "1" && "$PARTITION" != "2" ) || \
  ( "$MODULE" != "account-service" && "$MODULE" != "game-session-service" && $# -eq 2 && "$PARTITION" != "full" ) ]]; then
  echo "usage: run-gradle-module-checks.sh <module-name> [full|1|2] (Account and Game Session partitions only)" >&2
  exit 64
fi

declare -a CHECK_ARGS=(-PfullCheck)
if [[ ( "$MODULE" == "account-service" || "$MODULE" == "game-session-service" ) && "$PARTITION" != "full" ]]; then
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

  # Build every current service test class first, then freeze one source-derived selector.
  bash "$ROOT_DIR/dev-tools/validation/run-gradle-with-dependency-recovery.sh" \
    --runner "$ROOT_DIR/gradlew" -- \
    --max-workers=2 \
    '-Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8' \
    "${PREPARE_TASKS[@]}" -PfullCheck
  python3 "$ROOT_DIR/dev-tools/validation/slow_service_ci_test_partition.py" plan \
    --module "$MODULE" \
    --partition "$PARTITION" \
    --source-revision "${GITHUB_SHA:-local}" \
    --run-id "${GITHUB_RUN_ID:-0}" \
    --run-attempt "${GITHUB_RUN_ATTEMPT:-0}" \
    --output-dir "$PARTITION_DIR" \
    "${CLASS_DIR_ARGS[@]}"
  CHECK_ARGS+=("-PslowServiceCiPartition=$PARTITION")
fi

# Keep the original recovery policy and complete check; diagnostics never retry it.
CHECK_LOG="$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/firemud-module-check.XXXXXX")" || CHECK_LOG=""
readonly CHECK_LOG
trap 'rm -f -- "$CHECK_LOG"' EXIT
if [[ -z "$CHECK_LOG" ]]; then
  echo "Compile-failure log unavailable; running the original check without capture" >&2
  exec bash "$ROOT_DIR/dev-tools/validation/run-gradle-with-dependency-recovery.sh" \
    --runner "$ROOT_DIR/gradlew" -- \
    --max-workers=2 \
    '-Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8' \
    ":${MODULE}:check" "${CHECK_ARGS[@]}" --profile
fi
set +e
bash "$ROOT_DIR/dev-tools/validation/run-gradle-with-dependency-recovery.sh" \
  --runner "$ROOT_DIR/gradlew" -- \
  --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8' \
  ":${MODULE}:check" \
  "${CHECK_ARGS[@]}" \
  --profile --info --console=plain 2>&1 | \
  (
    python3 "$ROOT_DIR/dev-tools/validation/collect-gradle-compile-failure.py" --capture-log "$CHECK_LOG"
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
