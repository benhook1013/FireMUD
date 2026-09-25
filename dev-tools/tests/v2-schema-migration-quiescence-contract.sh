#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
helper="$ROOT_DIR/dev-tools/deploy/quiesce-v2-schema-migrations.sh"
command -v jq >/dev/null 2>&1 || {
  echo "jq is required for the V2 schema quiescence contract." >&2
  exit 1
}
bash -n "$helper"

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
fake_bin="$fixture_dir/bin"
mkdir -p "$fake_bin"

cat >"$fake_bin/kubectl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$QUIESCE_CALLS"
case "$*" in
  "-n contract get service game-session-service --ignore-not-found -o json")
    if [[ "${QUIESCE_MODE:-}" == bad-selector ]]; then
      printf '%s\n' '{"spec":{"selector":{"app":"other-service"}}}'
    else
      printf '%s\n' '{"spec":{"selector":{"app":"game-session-service"}}}'
    fi
    ;;
  "-n contract get service automation-scripting-service --ignore-not-found -o json")
    printf '%s\n' '{"spec":{"selector":{"app":"automation-scripting-service"}}}'
    ;;
  "-n contract get hpa -o json")
    case "${QUIESCE_MODE:-}" in
      hpa) printf '%s\n' '{"items":[{"spec":{"scaleTargetRef":{"kind":"Deployment","name":"game-session-service"}}}]}' ;;
      hpa-denied) echo 'forbidden: horizontalpodautoscalers is forbidden' >&2; exit 1 ;;
      *) printf '%s\n' '{"items":[]}' ;;
    esac
    ;;
  "-n contract get deployment game-session-service --ignore-not-found -o name")
    printf '%s\n' 'deployment/game-session-service'
    ;;
  "-n contract get deployment automation-scripting-service --ignore-not-found -o name")
    printf '%s\n' 'deployment/automation-scripting-service'
    ;;
  "-n contract scale deployment/game-session-service --replicas=0")
    [[ "${QUIESCE_MODE:-}" != scale-failure ]] || { echo 'scale denied' >&2; exit 1; }
    ;;
  "-n contract scale deployment/automation-scripting-service --replicas=0")
    ;;
  "-n contract get deployment game-session-service --ignore-not-found -o json"|\
  "-n contract get deployment automation-scripting-service --ignore-not-found -o json")
    printf '%s\n' '{"spec":{"replicas":0}}'
    ;;
  "-n contract get pods -o json")
    printf '%s\n' '{"items":[]}'
    ;;
  *)
    echo "unexpected kubectl call: $*" >&2
    exit 2
    ;;
esac
EOF
chmod +x "$fake_bin/kubectl"

run_quiesce() {
  QUIESCE_MODE="$1" QUIESCE_CALLS="$fixture_dir/calls" \
    PATH="$fake_bin:$PATH" bash "$helper" contract
}

: >"$fixture_dir/calls"
run_quiesce success >/dev/null
game_scale_line="$(grep -nFx -- '-n contract scale deployment/game-session-service --replicas=0' "$fixture_dir/calls" | cut -d: -f1)"
automation_scale_line="$(grep -nFx -- '-n contract scale deployment/automation-scripting-service --replicas=0' "$fixture_dir/calls" | cut -d: -f1)"
first_pod_check_line="$(grep -nFx -- '-n contract get pods -o json' "$fixture_dir/calls" | head -n1 | cut -d: -f1)"
[[ "$game_scale_line" -lt "$first_pod_check_line" && "$automation_scale_line" -lt "$first_pod_check_line" ]] || {
  echo "both V2 writers must be scaled down before the helper verifies Pods" >&2
  exit 1
}

for failure_mode in hpa hpa-denied bad-selector scale-failure; do
  : >"$fixture_dir/calls"
  if run_quiesce "$failure_mode" >"$fixture_dir/${failure_mode}.out" 2>&1; then
    echo "quiesce helper unexpectedly succeeded for ${failure_mode}" >&2
    exit 1
  fi
  if [[ "$failure_mode" == hpa || "$failure_mode" == hpa-denied || "$failure_mode" == bad-selector ]]; then
    if grep -q 'scale deployment/' "$fixture_dir/calls"; then
      echo "quiesce helper mutated Deployments after ${failure_mode} preflight failure" >&2
      exit 1
    fi
  fi
done
