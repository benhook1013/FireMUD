#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <namespace> <release_name>" >&2
  exit 1
fi

namespace="$1"
release_name="$2"
wait_seconds="${PREVIEW_NAMESPACE_DELETE_TIMEOUT_SECONDS:-180}"

helm uninstall "$release_name" \
  --namespace "$namespace" \
  --ignore-not-found || true

kubectl delete namespace "$namespace" --ignore-not-found=true >/dev/null || true

if kubectl get namespace "$namespace" >/dev/null 2>&1; then
  kubectl wait --for=delete "namespace/${namespace}" --timeout="${wait_seconds}s" || {
    echo "hosted namespace ${namespace} still exists after ${wait_seconds}s" >&2
    exit 1
  }
fi
