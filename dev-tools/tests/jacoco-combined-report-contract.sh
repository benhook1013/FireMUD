#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCKED_GRADLE="$ROOT_DIR/dev-tools/validation/run-locked-gradle.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

unit_graph="$TMP_DIR/unit-graph"
check_graph="$TMP_DIR/check-graph"
report_graph="$TMP_DIR/report-graph"

bash "$LOCKED_GRADLE" :common-saga:test -PfullCheck --dry-run --no-configuration-cache >"$unit_graph"
bash "$LOCKED_GRADLE" :common-saga:check -PfullCheck --dry-run --no-configuration-cache >"$check_graph"
bash "$LOCKED_GRADLE" :common-saga:jacocoTestReport -PfullCheck --dry-run --no-configuration-cache >"$report_graph"

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

echo "JaCoCo combined report task graph contract checks passed"
