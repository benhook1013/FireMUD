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
  "-n contract get service account-service --ignore-not-found -o json"|\
  "-n contract get service game-session-service --ignore-not-found -o json"|\
  "-n contract get service automation-scripting-service --ignore-not-found -o json")
    service="${5}"
    if [[ "${QUIESCE_MODE:-}" == bad-account-selector && "$service" == account-service ]] ||
      [[ "${QUIESCE_MODE:-}" == bad-selector && "$service" == game-session-service ]]; then
      printf '{"metadata":{"name":"%s"},"spec":{"selector":{"app":"other-service"}}}\n' "$service"
    else
      selector="$service"
      if [[ -f "$QUIESCE_STATE/$service" ]]; then
        selector="$(<"$QUIESCE_STATE/$service")"
      fi
      printf '{"metadata":{"name":"%s"},"spec":{"selector":{"app":"%s"}}}\n' "$service" "$selector"
    fi
    ;;
  "-n contract patch service account-service --type=merge --patch "*|\
  "-n contract patch service game-session-service --type=merge --patch "*|\
  "-n contract patch service automation-scripting-service --type=merge --patch "*)
    service="${5}"
    if [[ "${QUIESCE_MODE:-}" == restore-failure && "$service" == game-session-service && "$*" == *'"app":"game-session-service"'* ]]; then
      echo 'service patch denied' >&2
      exit 1
    fi
    if [[ "$*" == *'"app":"firemud-v2-migration-quiesced"'* ]]; then
      printf '%s\n' 'firemud-v2-migration-quiesced' >"$QUIESCE_STATE/$service"
    elif [[ "$*" == *"\"app\":\"$service\""* ]]; then
      printf '%s\n' "$service" >"$QUIESCE_STATE/$service"
    else
      echo "unexpected selector patch: $*" >&2
      exit 2
    fi
    ;;
  "-n contract get endpointslices.discovery.k8s.io --selector kubernetes.io/service-name=account-service -o json"|\
  "-n contract get endpointslices.discovery.k8s.io --selector kubernetes.io/service-name=game-session-service -o json"|\
  "-n contract get endpointslices.discovery.k8s.io --selector kubernetes.io/service-name=automation-scripting-service -o json")
    [[ "${QUIESCE_MODE:-}" != endpoint-denied ]] || {
      echo 'forbidden: endpointslices is forbidden' >&2
      exit 1
    }
    service="${6#*=}"
    printf '{"kind":"EndpointSliceList","items":[{"metadata":{"labels":{"kubernetes.io/service-name":"%s"}},"endpoints":[]}]}\n' "$service"
    ;;
  "-n contract get hpa -o json")
    case "${QUIESCE_MODE:-}" in
      account-hpa) printf '%s\n' '{"items":[{"spec":{"scaleTargetRef":{"kind":"Deployment","name":"account-service"}}}]}' ;;
      hpa) printf '%s\n' '{"items":[{"spec":{"scaleTargetRef":{"kind":"Deployment","name":"game-session-service"}}}]}' ;;
      hpa-denied) echo 'forbidden: horizontalpodautoscalers is forbidden' >&2; exit 1 ;;
      *) printf '%s\n' '{"items":[]}' ;;
    esac
    ;;
  "-n contract get deployment account-service --ignore-not-found -o name")
    printf '%s\n' 'deployment/account-service'
    ;;
  "-n contract get deployment game-session-service --ignore-not-found -o name")
    printf '%s\n' 'deployment/game-session-service'
    ;;
  "-n contract get deployment automation-scripting-service --ignore-not-found -o name")
    printf '%s\n' 'deployment/automation-scripting-service'
    ;;
  "-n contract get deployment account-service --ignore-not-found -o json")
    printf '%s\n' '{"spec":{"replicas":0}}'
    ;;
  "-n contract get deployment game-session-service --ignore-not-found -o json"|\
  "-n contract get deployment automation-scripting-service --ignore-not-found -o json")
    printf '%s\n' '{"spec":{"replicas":0}}'
    ;;
  "-n contract scale deployment/account-service --replicas=0")
    [[ "${QUIESCE_MODE:-}" != account-scale-failure ]] || { echo 'scale denied' >&2; exit 1; }
    ;;
  "-n contract scale deployment/game-session-service --replicas=0")
    [[ "${QUIESCE_MODE:-}" != scale-failure ]] || { echo 'scale denied' >&2; exit 1; }
    ;;
  "-n contract scale deployment/automation-scripting-service --replicas=0")
    ;;
  "-n contract get pods -o json")
    if [[ "${QUIESCE_MODE:-}" == pod-api-denied ]]; then
      echo 'forbidden: pods are forbidden' >&2
      exit 1
    fi
    if [[ "${QUIESCE_MODE:-}" == writer-pod-api-denied ]] &&
      grep -q 'firemud-v2-migration-quiesced' "$QUIESCE_CALLS"; then
      echo 'forbidden: pods are forbidden' >&2
      exit 1
    fi
    if [[ "${QUIESCE_MODE:-}" == preexisting-closed-pod ]] &&
      ! grep -q 'firemud-v2-migration-quiesced' "$QUIESCE_CALLS"; then
      printf '%s\n' '{"kind":"PodList","items":[{"metadata":{"labels":{"app":"firemud-v2-migration-quiesced"}}}]}'
    else
      printf '%s\n' '{"kind":"PodList","items":[]}'
    fi
    ;;
  *)
    echo "unexpected kubectl call: $*" >&2
    exit 2
    ;;
esac
EOF
chmod +x "$fake_bin/kubectl"

run_quiesce() {
  mkdir -p "$fixture_dir/state-$1"
  QUIESCE_MODE="$1" QUIESCE_CALLS="$fixture_dir/calls-$1" QUIESCE_STATE="$fixture_dir/state-$1" \
    PATH="$fake_bin:$PATH" bash "$helper" contract
}

run_quiesce success >/dev/null
calls="$fixture_dir/calls-success"
first_endpoint_line="$(grep -nF -- 'get endpointslices.discovery.k8s.io' "$calls" | head -n1 | cut -d: -f1)"
last_close_line="$(grep -nF -- 'firemud-v2-migration-quiesced' "$calls" | grep 'patch service' | tail -n1 | cut -d: -f1)"
last_endpoint_line="$(grep -nF -- 'get endpointslices.discovery.k8s.io' "$calls" | tail -n1 | cut -d: -f1)"
first_scale_line="$(grep -nF -- ' scale deployment/' "$calls" | head -n1 | cut -d: -f1)"
last_scale_line="$(grep -nF -- ' scale deployment/' "$calls" | tail -n1 | cut -d: -f1)"
initial_pod_check_line="$(grep -nFx -- '-n contract get pods -o json' "$calls" | sed -n '1p' | cut -d: -f1)"
first_pod_check_line="$(grep -nFx -- '-n contract get pods -o json' "$calls" | sed -n '2p' | cut -d: -f1)"
last_pod_check_line="$(grep -nFx -- '-n contract get pods -o json' "$calls" | tail -n1 | cut -d: -f1)"
first_restore_line="$(grep -nF -- 'patch service account-service --type=merge --patch {"spec":{"selector":{"app":"account-service"}}}' "$calls" | head -n1 | cut -d: -f1)"
[[ "$initial_pod_check_line" -lt "$last_close_line" && "$last_close_line" -lt "$first_endpoint_line" && "$last_endpoint_line" -lt "$first_scale_line" && "$last_scale_line" -lt "$first_pod_check_line" && "$last_pod_check_line" -lt "$first_restore_line" ]] || {
  echo "Services must close before empty EndpointSlices are proved, all writers scale before pod checks, and selectors restore last" >&2
  exit 1
}
for service in account-service game-session-service automation-scripting-service; do
  close_line="$(grep -nF -- "patch service $service --type=merge --patch {\"spec\":{\"selector\":{\"app\":\"firemud-v2-migration-quiesced\"}}}" "$calls" | head -n1 | cut -d: -f1)"
  closed_readback_line="$(grep -nF -- "get service $service --ignore-not-found -o json" "$calls" | sed -n '2p' | cut -d: -f1)"
  [[ "$close_line" -lt "$closed_readback_line" && "$closed_readback_line" -lt "$first_endpoint_line" ]] || {
    echo "Service/$service must be verified closed before EndpointSlice checks" >&2
    exit 1
  }
  [[ "$(<"$fixture_dir/state-success/$service")" == "$service" ]] || {
    echo "Service/$service must be restored after successful quiescence" >&2
    exit 1
  }
done

for failure_mode in account-hpa bad-account-selector pod-api-denied preexisting-closed-pod hpa hpa-denied bad-selector endpoint-denied account-scale-failure writer-pod-api-denied restore-failure scale-failure; do
  if run_quiesce "$failure_mode" >"$fixture_dir/${failure_mode}.out" 2>&1; then
    echo "quiesce helper unexpectedly succeeded for ${failure_mode}" >&2
    exit 1
  fi
  if [[ "$failure_mode" == account-hpa || "$failure_mode" == bad-account-selector || "$failure_mode" == pod-api-denied || "$failure_mode" == preexisting-closed-pod || "$failure_mode" == hpa || "$failure_mode" == hpa-denied || "$failure_mode" == bad-selector ]]; then
    if grep -q ' scale deployment/' "$fixture_dir/calls-$failure_mode" ||
      grep -q ' patch service ' "$fixture_dir/calls-$failure_mode"; then
      echo "quiesce helper mutated workloads after ${failure_mode} preflight failure" >&2
      exit 1
    fi
  fi
  if [[ "$failure_mode" == endpoint-denied || "$failure_mode" == account-scale-failure || "$failure_mode" == writer-pod-api-denied || "$failure_mode" == restore-failure || "$failure_mode" == scale-failure ]]; then
    for service in account-service game-session-service automation-scripting-service; do
      [[ "$(<"$fixture_dir/state-$failure_mode/$service")" == firemud-v2-migration-quiesced ]] || {
        echo "Service/$service admission must remain closed after ${failure_mode}" >&2
        exit 1
      }
    done
  fi
done
