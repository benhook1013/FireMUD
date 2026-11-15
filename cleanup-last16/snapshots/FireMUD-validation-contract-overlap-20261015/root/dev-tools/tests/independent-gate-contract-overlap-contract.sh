#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HELPER="$ROOT_DIR/.github/scripts/run-independent-gate-contracts.sh"
FIXTURE_ROOT="$(mktemp -d)"
UNRELATED_PID=""
INTERRUPTED_HELPER_PID=""
trap 'if [[ -n "$INTERRUPTED_HELPER_PID" ]]; then kill -KILL "$INTERRUPTED_HELPER_PID" 2>/dev/null || true; wait "$INTERRUPTED_HELPER_PID" 2>/dev/null || true; fi; if [[ -n "$UNRELATED_PID" ]]; then kill "$UNRELATED_PID" 2>/dev/null || true; wait "$UNRELATED_PID" 2>/dev/null || true; fi; rm -rf "$FIXTURE_ROOT"' EXIT

make_fixture() {
  local fixture="$1"
  mkdir -p "$fixture/.github/scripts" "$fixture/dev-tools/tests"
  cp "$HELPER" "$fixture/.github/scripts/run-independent-gate-contracts.sh"
  chmod +x "$fixture/.github/scripts/run-independent-gate-contracts.sh"
}

write_contract() {
  local path="$1"
  local body="$2"
  cat >"$path" <<EOF
#!/usr/bin/env bash
set -euo pipefail
$body
EOF
  chmod +x "$path"
}

overlap_fixture="$FIXTURE_ROOT/overlap"
make_fixture "$overlap_fixture"
for name in jacoco-combined-report pr-required-gate-context; do
  # shellcheck disable=SC2016 # The body is written as literal fixture code.
  write_contract "$overlap_fixture/dev-tools/tests/$name-contract.sh" '
fixture="${INDEPENDENT_GATE_FIXTURE:?}"
name="${BASH_SOURCE[0]##*/}"
name="${name%-contract.sh}"
touch "$fixture/$name.started"
for attempt in {1..100}; do
  [[ -f "$fixture/jacoco-combined-report.started" && -f "$fixture/pr-required-gate-context.started" ]] && break
  sleep 0.02
done
[[ -f "$fixture/jacoco-combined-report.started" && -f "$fixture/pr-required-gate-context.started" ]] || {
  echo "overlap barrier was not reached by both contracts" >&2
  exit 21
}
echo "$name reached overlap barrier"
'
done
INDEPENDENT_GATE_FIXTURE="$overlap_fixture" bash "$overlap_fixture/.github/scripts/run-independent-gate-contracts.sh" >"$FIXTURE_ROOT/overlap.out" 2>&1 || {
  cat "$FIXTURE_ROOT/overlap.out" >&2
  exit 1
}
grep -Fq 'jacoco-combined-report reached overlap barrier' "$FIXTURE_ROOT/overlap.out"
grep -Fq 'pr-required-gate-context reached overlap barrier' "$FIXTURE_ROOT/overlap.out"

launch_fixture="$FIXTURE_ROOT/launch-boundary"
make_fixture "$launch_fixture"
for name in jacoco-combined-report pr-required-gate-context; do
  # shellcheck disable=SC2016 # This literal is written as fixture code.
  write_contract "$launch_fixture/dev-tools/tests/$name-contract.sh" \
    'touch "${INDEPENDENT_GATE_FIXTURE:?}/contract-started"'
done
# Deliver TERM at the precise gap after Bash starts the asynchronous worker but
# before the helper records $! in its worker array.
awk '
  /^  WORKER_PIDS\[\$index\]="\$!"$/ { print "  kill -TERM \"$BASHPID\"" }
  { print }
' "$launch_fixture/.github/scripts/run-independent-gate-contracts.sh" \
  >"$launch_fixture/.github/scripts/launch-boundary.sh"
mv "$launch_fixture/.github/scripts/launch-boundary.sh" \
  "$launch_fixture/.github/scripts/run-independent-gate-contracts.sh"
launch_status=0
INDEPENDENT_GATE_FIXTURE="$launch_fixture" \
  bash "$launch_fixture/.github/scripts/run-independent-gate-contracts.sh" \
  >"$FIXTURE_ROOT/launch-boundary.out" 2>&1 || launch_status="$?"
[[ "$launch_status" -eq 143 ]]
[[ ! -e "$launch_fixture/contract-started" ]]

failure_fixture="$FIXTURE_ROOT/failure"
make_fixture "$failure_fixture"
write_contract "$failure_fixture/dev-tools/tests/jacoco-combined-report-contract.sh" 'echo "jacoco failure marker"; exit 17'
write_contract "$failure_fixture/dev-tools/tests/pr-required-gate-context-contract.sh" 'echo "required-gate success marker"; exit 0'
failure_status=0
bash "$failure_fixture/.github/scripts/run-independent-gate-contracts.sh" >"$FIXTURE_ROOT/failure.out" 2>&1 || failure_status="$?"
[[ "$failure_status" -ne 0 ]]
grep -Fq 'jacoco failure marker' "$FIXTURE_ROOT/failure.out"
grep -Fq 'required-gate success marker' "$FIXTURE_ROOT/failure.out"
grep -Fq 'jacoco-combined-report contract failed with status 17' "$FIXTURE_ROOT/failure.out"

second_failure_fixture="$FIXTURE_ROOT/second-failure"
make_fixture "$second_failure_fixture"
write_contract "$second_failure_fixture/dev-tools/tests/jacoco-combined-report-contract.sh" \
  'echo "jacoco success marker"; exit 0'
write_contract "$second_failure_fixture/dev-tools/tests/pr-required-gate-context-contract.sh" \
  'echo "required-gate failure marker"; exit 23'
second_failure_status=0
bash "$second_failure_fixture/.github/scripts/run-independent-gate-contracts.sh" \
  >"$FIXTURE_ROOT/second-failure.out" 2>&1 || second_failure_status="$?"
[[ "$second_failure_status" -ne 0 ]]
grep -Fq 'jacoco success marker' "$FIXTURE_ROOT/second-failure.out"
grep -Fq 'required-gate failure marker' "$FIXTURE_ROOT/second-failure.out"
grep -Fq 'pr-required-gate-context contract failed with status 23' "$FIXTURE_ROOT/second-failure.out"

completed_fixture="$FIXTURE_ROOT/completed-worker"
make_fixture "$completed_fixture"
awk '
  /^  if wait "\$pid"; then$/ { after_worker_wait = 1 }
  after_worker_wait && /^  WORKER_PIDS\[\$index\]=""$/ && !inserted {
    print "  if [[ \"$index\" == \"0\" ]]; then kill -TERM \"$BASHPID\"; fi"
    inserted = 1
  }
  { print }
' "$completed_fixture/.github/scripts/run-independent-gate-contracts.sh" \
  >"$completed_fixture/.github/scripts/completed-worker.sh"
mv "$completed_fixture/.github/scripts/completed-worker.sh" \
  "$completed_fixture/.github/scripts/run-independent-gate-contracts.sh"
# shellcheck disable=SC2016 # The body is written as literal fixture code.
write_contract "$completed_fixture/dev-tools/tests/jacoco-combined-report-contract.sh" '
fixture="${INDEPENDENT_GATE_FIXTURE:?}"
process_stat="$(<"/proc/$$/stat")"
process_fields="${process_stat##*) }"
read -r -a fields <<<"$process_fields"
echo "${fields[3]}" >"$fixture/completed.sid"
echo "jacoco completed"
'
# shellcheck disable=SC2016 # The body is written as literal fixture code.
write_contract "$completed_fixture/dev-tools/tests/pr-required-gate-context-contract.sh" '
fixture="${INDEPENDENT_GATE_FIXTURE:?}"
touch "$fixture/second.started"
sleep 30 &
echo "$!" >"$fixture/second.pid"
wait
'
KILL_TRACE="$FIXTURE_ROOT/kill.trace"
export KILL_TRACE
kill() {
  printf '%s\n' "$*" >>"$KILL_TRACE"
  builtin kill "$@"
}
export -f kill
INDEPENDENT_GATE_FIXTURE="$completed_fixture" \
  bash "$completed_fixture/.github/scripts/run-independent-gate-contracts.sh" \
  >"$FIXTURE_ROOT/completed-worker.out" 2>&1 || completed_status="$?"
completed_status="${completed_status:-0}"
[[ "$completed_status" -eq 143 ]]
[[ -s "$completed_fixture/completed.sid" ]]
completed_sid="$(<"$completed_fixture/completed.sid")"
if awk -v target="-$completed_sid" '$1 == "-TERM" && $3 == target { found = 1 } END { exit !found }' \
  "$KILL_TRACE"; then
  echo "helper signaled the completed worker's process group $completed_sid" >&2
  exit 1
fi
unset -f kill

interrupt_fixture="$FIXTURE_ROOT/interrupt"
make_fixture "$interrupt_fixture"
for name in jacoco-combined-report pr-required-gate-context; do
  # shellcheck disable=SC2016 # The body is written as literal fixture code.
  write_contract "$interrupt_fixture/dev-tools/tests/$name-contract.sh" '
fixture="${INDEPENDENT_GATE_FIXTURE:?}"
touch "$fixture/worker.started"
trap "" TERM
sleep 30 &
echo "$!" >> "$fixture/worker.pids"
wait
'
done
UNRELATED_PID=""
sleep 30 &
UNRELATED_PID="$!"
INDEPENDENT_GATE_FIXTURE="$interrupt_fixture" bash "$interrupt_fixture/.github/scripts/run-independent-gate-contracts.sh" >"$FIXTURE_ROOT/interrupt.out" 2>&1 &
INTERRUPTED_HELPER_PID="$!"
attempt=0
while [[ "$attempt" -lt 100 ]]; do
  [[ -f "$interrupt_fixture/worker.pids" ]] && break
  sleep 0.02
  attempt=$((attempt + 1))
done
[[ -f "$interrupt_fixture/worker.pids" ]]
kill -TERM "$INTERRUPTED_HELPER_PID"
interrupt_status=0
wait "$INTERRUPTED_HELPER_PID" || interrupt_status="$?"
INTERRUPTED_HELPER_PID=""
[[ "$interrupt_status" -eq 143 ]]
while IFS= read -r worker_pid; do
  [[ -n "$worker_pid" ]]
  if kill -0 "$worker_pid" 2>/dev/null; then
    echo "owned contract worker survived helper interruption: $worker_pid" >&2
    exit 1
  fi
done <"$interrupt_fixture/worker.pids"
kill -0 "$UNRELATED_PID"

printf 'Independent CI gate contract overlap checks passed\n'
