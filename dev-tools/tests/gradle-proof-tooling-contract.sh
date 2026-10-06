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
