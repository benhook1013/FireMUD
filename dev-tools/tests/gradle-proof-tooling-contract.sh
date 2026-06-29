#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCKED_RUNNER="$ROOT_DIR/dev-tools/validation/run-locked-gradle.sh"
INSPECTOR="$ROOT_DIR/dev-tools/validation/inspect-test-results.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

single_service="$("$LOCKED_RUNNER" --print-lock-targets :game-session-service:check -PfullCheck)"
[[ "$single_service" == "service:game-session-service" ]]

multi_service="$("$LOCKED_RUNNER" --print-lock-targets :tcp-proxy-service:check :game-session-service:check)"
expected_multi=$'service:game-session-service\nservice:tcp-proxy-service'
[[ "$multi_service" == "$expected_multi" ]]

repo_wide="$("$LOCKED_RUNNER" --print-lock-targets check)"
[[ "$repo_wide" == "repo" ]]

mixed_scope="$("$LOCKED_RUNNER" --print-lock-targets :game-session-service:check check)"
[[ "$mixed_scope" == "repo" ]]

mkdir -p "$TMP_DIR/services/demo-service/build/test-results/test"
mkdir -p "$TMP_DIR/services/demo-service/build/test-results/integrationTest"

cat >"$TMP_DIR/services/demo-service/build/test-results/test/TEST-demo-unit.xml" <<'XML'
<testsuite name="demo-unit" tests="3" failures="0" errors="0" skipped="1"/>
XML

cat >"$TMP_DIR/services/demo-service/build/test-results/integrationTest/TEST-demo-integration.xml" <<'XML'
<testsuite name="demo-integration" tests="2" failures="1" errors="0" skipped="0"/>
XML

inspection_output="$("$INSPECTOR" --root "$TMP_DIR" demo-service)"
grep -q "Service: demo-service" <<<"$inspection_output"
grep -q "test: 1 file(s), 3 test(s), 0 failure(s), 0 error(s), 1 skipped" <<<"$inspection_output"
grep -q "integrationTest: 1 file(s), 2 test(s), 1 failure(s), 0 error(s), 0 skipped" <<<"$inspection_output"
grep -q "Failing XML files:" <<<"$inspection_output"
grep -q "Diagnostic only:" <<<"$inspection_output"

echo "gradle proof tooling contract checks passed"
