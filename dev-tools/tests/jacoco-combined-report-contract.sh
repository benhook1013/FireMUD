#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCKED_GRADLE="$ROOT_DIR/dev-tools/validation/run-locked-gradle.sh"
RECOVERY_GRADLE="$ROOT_DIR/dev-tools/validation/run-gradle-with-dependency-recovery.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

unit_graph="$TMP_DIR/unit-graph"
check_graph="$TMP_DIR/check-graph"
report_graph="$TMP_DIR/report-graph"
account_aggregate_graph="$TMP_DIR/account-aggregate-graph"
game_session_aggregate_graph="$TMP_DIR/game-session-aggregate-graph"
world_aggregate_graph="$TMP_DIR/world-aggregate-graph"

run_task_graph() {
  local graph="$1"
  local status
  shift
  if bash "$RECOVERY_GRADLE" --runner "$LOCKED_GRADLE" -- "$@" >"$graph"; then
    return 0
  else
    status=$?
    cat "$graph" >&2
    return "$status"
  fi
}

run_task_graph "$unit_graph" :common-saga:test -PfullCheck --dry-run --no-configuration-cache
run_task_graph "$check_graph" :common-saga:check -PfullCheck --dry-run --no-configuration-cache
run_task_graph "$report_graph" :common-saga:jacocoTestReport -PfullCheck --dry-run --no-configuration-cache
run_task_graph "$account_aggregate_graph" :account-service:jacocoTestReport -PfullCheck -PslowServiceCiCoverageAggregate=account-service -PslowServiceCiCoverageClassesDirectory=build/slow-service-ci-verified-classes --dry-run --configuration-cache --configuration-cache-problems=fail
run_task_graph "$game_session_aggregate_graph" :game-session-service:jacocoTestReport -PfullCheck -PslowServiceCiCoverageAggregate=game-session-service -PslowServiceCiCoverageClassesDirectory=build/slow-service-ci-verified-classes --dry-run --configuration-cache --configuration-cache-problems=fail
run_task_graph "$world_aggregate_graph" :world-management-service:jacocoTestReport -PfullCheck -PslowServiceCiCoverageAggregate=world-management-service -PslowServiceCiCoverageClassesDirectory=build/slow-service-ci-verified-classes --dry-run --configuration-cache --configuration-cache-problems=fail

if grep -Eq '^:common-saga:(integrationTest|crossServiceTest|jacocoTestReport)([[:space:]]|$)' "$unit_graph"; then
  echo "Focused :common-saga:test unexpectedly scheduled categorized tests or the combined report." >&2
  exit 1
fi

grep -Eq '^:common-saga:integrationTest([[:space:]]|$)' "$check_graph" || {
  echo "Full :common-saga:check did not schedule integrationTest." >&2
  exit 1
}
grep -Eq '^:common-saga:jacocoTestReport([[:space:]]|$)' "$check_graph" || {
  echo "Full :common-saga:check did not schedule jacocoTestReport." >&2
  exit 1
}
grep -Eq '^:common-saga:integrationTest([[:space:]]|$)' "$report_graph" || {
  echo "Explicit jacocoTestReport did not schedule integrationTest." >&2
  exit 1
}
grep -Eq '^:common-saga:jacocoTestReport([[:space:]]|$)' "$report_graph" || {
  echo "Explicit jacocoTestReport did not schedule the combined report task." >&2
  exit 1
}

if grep -Eq '^:account-service:(test|integrationTest|crossServiceTest)([[:space:]]|$)' "$account_aggregate_graph"; then
  echo "Account coverage aggregation unexpectedly reran a partitioned test task." >&2
  exit 1
fi
if grep -Eq '^:account-service:classes([[:space:]]|$)' "$account_aggregate_graph"; then
  echo "Account coverage aggregation recompiled classes instead of using measured partition classes." >&2
  exit 1
fi
grep -Eq '^:account-service:jacocoTestReport([[:space:]]|$)' "$account_aggregate_graph" || {
  echo "Account coverage aggregation did not schedule jacocoTestReport." >&2
  exit 1
}

if grep -Eq '^:game-session-service:(test|integrationTest|crossServiceTest)([[:space:]]|$)' "$game_session_aggregate_graph"; then
  echo "Game Session coverage aggregation unexpectedly reran a partitioned test task." >&2
  exit 1
fi
if grep -Eq '^:game-session-service:classes([[:space:]]|$)' "$game_session_aggregate_graph"; then
  echo "Game Session coverage aggregation recompiled classes instead of using measured partition classes." >&2
  exit 1
fi
grep -Eq '^:game-session-service:jacocoTestReport([[:space:]]|$)' "$game_session_aggregate_graph" || {
  echo "Game Session coverage aggregation did not schedule jacocoTestReport." >&2
  exit 1
}

if grep -Eq '^:world-management-service:(test|integrationTest|crossServiceTest)([[:space:]]|$)' "$world_aggregate_graph"; then
  echo "World coverage aggregation unexpectedly reran a partitioned test task." >&2
  exit 1
fi
if grep -Eq '^:[^ ]*:(classes|compile[^ ]*|test|integrationTest|crossServiceTest)([[:space:]]|$)' "$world_aggregate_graph"; then
  echo "World coverage aggregation recompiled classes instead of using measured partition classes." >&2
  exit 1
fi
grep -Eq '^:world-management-service:jacocoTestReport([[:space:]]|$)' "$world_aggregate_graph" || {
  echo "World coverage aggregation did not schedule jacocoTestReport." >&2
  exit 1
}

echo "JaCoCo combined and slow-service partition report task graph contracts passed"
