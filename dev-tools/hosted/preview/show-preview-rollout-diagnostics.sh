#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: $0 <namespace> [stage]" >&2
  exit 1
fi

namespace="$1"
stage="${2:-unknown}"

if ! command -v kubectl >/dev/null 2>&1; then
  echo "kubectl is required" >&2
  exit 1
fi

section() {
  local title="$1"
  shift
  echo "=== ${title} ==="
  "$@" || true
  echo
}

print_pod_logs() {
  local pod="$1"
  mapfile -t containers < <(
    kubectl -n "$namespace" get pod "$pod" -o jsonpath='{range .spec.containers[*]}{.name}{"\n"}{end}' 2>/dev/null \
      | sed '/^$/d'
  )
  if [[ "${#containers[@]}" -eq 0 ]]; then
    echo "No containers found for ${pod}."
    return
  fi
  for container in "${containers[@]}"; do
    echo "--- ${pod}/${container} current logs ---"
    kubectl -n "$namespace" logs "$pod" -c "$container" --tail=120 || true
    echo
    echo "--- ${pod}/${container} previous logs ---"
    kubectl -n "$namespace" logs "$pod" -c "$container" --previous --tail=120 || true
    echo
  done
}

echo "Preview diagnostics for namespace ${namespace} (stage: ${stage})"
echo

section "namespace" kubectl get namespace "$namespace" -o yaml
section "workload summary" kubectl -n "$namespace" get deployment,statefulset,pods -o wide
section "service and ingress summary" kubectl -n "$namespace" get svc,ingress -o wide
section "configmap summary" kubectl -n "$namespace" get configmap -o go-template='{{range .items}}{{.metadata.name}}{{"\t"}}{{len .data}}{{"\n"}}{{end}}'
section "recent events" bash -lc "kubectl -n '$namespace' get events --sort-by=.lastTimestamp | tail -n 160"

mapfile -t problematic_workloads < <(
  kubectl -n "$namespace" get deployment,statefulset --no-headers 2>/dev/null \
    | awk '
        {
          split($2, ready, "/");
          if (ready[1] != ready[2]) {
            print $1;
          }
        }'
)

if [[ "${#problematic_workloads[@]}" -gt 0 ]]; then
  echo "=== describe unavailable workloads ==="
  for workload in "${problematic_workloads[@]}"; do
    echo "--- ${workload} ---"
    kubectl -n "$namespace" describe "$workload" || true
    echo
  done
  echo
fi

mapfile -t problematic_pods < <(
  kubectl -n "$namespace" get pods --no-headers 2>/dev/null \
    | awk '
        {
          split($2, ready, "/");
          if (ready[1] != ready[2] || ($3 != "Running" && $3 != "Completed")) {
            print $1;
          }
        }'
)

if [[ "${#problematic_pods[@]}" -gt 0 ]]; then
  echo "=== describe problematic pods ==="
  for pod in "${problematic_pods[@]}"; do
    echo "--- ${pod} ---"
    kubectl -n "$namespace" describe pod "$pod" || true
    echo
  done
  echo

  echo "=== problematic pod logs ==="
  for pod in "${problematic_pods[@]}"; do
    print_pod_logs "$pod"
  done
fi
