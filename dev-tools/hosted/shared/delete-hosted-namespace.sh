#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <namespace> <release_name>" >&2
  exit 1
fi

namespace="$1"
release_name="$2"
wait_seconds="${PREVIEW_NAMESPACE_DELETE_TIMEOUT_SECONDS:-180}"

if [[ "$namespace" == "dev" ]]; then
  if [[ "$release_name" != "dev" ]]; then
    echo "hosted release ${release_name} does not match runtime namespace ${namespace}" >&2
    exit 2
  fi
elif [[ "$namespace" =~ ^pr-[1-9][0-9]*$ ]]; then
  if [[ "$release_name" != "$namespace" ]]; then
    echo "hosted release ${release_name} does not match runtime namespace ${namespace}" >&2
    exit 2
  fi
else
  echo "hosted runtime namespace is not canonical: ${namespace}" >&2
  exit 2
fi

if ! [[ "$wait_seconds" =~ ^[1-9][0-9]*$ ]] ||
  ((${#wait_seconds} > 4)) ||
  ((10#$wait_seconds > 3600)); then
  echo "PREVIEW_NAMESPACE_DELETE_TIMEOUT_SECONDS must be an integer between 1 and 3600" >&2
  exit 2
fi

if ! namespace_lookup="$(
  kubectl get namespace "$namespace" --ignore-not-found -o name
)"; then
  echo "unable to determine whether hosted namespace ${namespace} exists" >&2
  exit 1
fi
if [[ -z "$namespace_lookup" ]]; then
  echo "Hosted namespace ${namespace} is already absent."
  exit 0
fi
if [[ "$namespace_lookup" != "namespace/${namespace}" ]]; then
  echo "hosted namespace ${namespace} lookup returned an unexpected identity" >&2
  exit 1
fi

helm_status=0
helm uninstall "$release_name" \
  --namespace "$namespace" \
  --ignore-not-found || helm_status=$?
if ((helm_status != 0)); then
  echo "Helm uninstall failed for hosted release ${release_name}; continuing namespace deletion." >&2
fi

delete_status=0
kubectl delete namespace "$namespace" --ignore-not-found=true --wait=false >/dev/null || delete_status=$?
if ((delete_status != 0)); then
  echo "unable to request deletion of hosted namespace ${namespace}" >&2
  exit "$delete_status"
fi

wait_status=0
kubectl wait --for=delete "namespace/${namespace}" --timeout="${wait_seconds}s" || wait_status=$?

if ! namespace_lookup="$(
  kubectl get namespace "$namespace" --ignore-not-found -o name
)"; then
  echo "unable to verify deletion of hosted namespace ${namespace}" >&2
  exit 1
fi
if [[ -n "$namespace_lookup" ]]; then
  if [[ "$namespace_lookup" != "namespace/${namespace}" ]]; then
    echo "hosted namespace ${namespace} deletion lookup returned an unexpected identity" >&2
    exit 1
  fi
  echo "hosted namespace ${namespace} still exists after ${wait_seconds}s" >&2
  if ((wait_status != 0)); then
    exit "$wait_status"
  fi
  exit 1
fi

echo "Hosted namespace ${namespace} is absent."
