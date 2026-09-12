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

if ! namespace_json="$(
  kubectl get namespace "$namespace" --ignore-not-found -o json
)"; then
  echo "unable to determine whether hosted namespace ${namespace} exists" >&2
  exit 1
fi
if [[ -z "$namespace_json" ]]; then
  echo "Hosted namespace ${namespace} is already absent."
  exit 0
fi

expected_pr_number=""
if [[ "$namespace" =~ ^pr-([1-9][0-9]*)$ ]]; then
  expected_pr_number="${BASH_REMATCH[1]}"
fi
if ! namespace_uid="$(
  jq -e -r \
    --arg namespace "$namespace" \
    --arg expected_pr_number "$expected_pr_number" '
      (.metadata.labels // {}) as $labels
      | select(.metadata.name == $namespace)
      | select(.metadata.uid | type == "string" and length > 0)
      | select(
          if $namespace == "dev" then
            $labels["firemud.dev/dev-demo"] == "true"
              and $labels["firemud.dev/environment-class"] == "dev-demo-cluster"
          else
            $labels["firemud.dev/preview"] == "true"
              and $labels["firemud.dev/pr-number"] == $expected_pr_number
          end
        )
      | .metadata.uid
    ' <<<"$namespace_json" 2>/dev/null
)"; then
  echo "hosted namespace ${namespace} identity or ownership metadata is invalid" >&2
  exit 1
fi

delete_options="$(
  jq -cn \
    --arg uid "$namespace_uid" \
    '{apiVersion:"v1",kind:"DeleteOptions",preconditions:{uid:$uid}}'
)"
delete_status=0
printf '%s\n' "$delete_options" |
  kubectl delete --raw "/api/v1/namespaces/${namespace}" -f - >/dev/null || delete_status=$?
if ((delete_status != 0)); then
  if ! namespace_lookup="$(
    kubectl get namespace "$namespace" --ignore-not-found -o name
  )"; then
    echo "unable to verify failed deletion of hosted namespace ${namespace}" >&2
    exit 1
  fi
  if [[ -z "$namespace_lookup" ]]; then
    echo "Hosted namespace ${namespace} is absent."
    exit 0
  fi
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
