#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == --discover-api ]]; then
  if [[ $# -ne 1 ]]; then
    echo "usage: $0 --discover-api" >&2
    exit 2
  fi

  expected_resource='hostedenvironmentidentities.platform.firemud.dev'
  discovery_output="$(kubectl api-resources \
    --api-group=platform.firemud.dev \
    --namespaced=true \
    --no-headers \
    --cached=false \
    -o name)"
  expected_resource_count=0
  while IFS= read -r resource; do
    [[ -z "$resource" ]] && continue
    [[ "$resource" =~ ^[a-z0-9][a-z0-9-]*\.[a-z0-9][a-z0-9.-]*$ ]] || {
      echo "Hosted identity API discovery returned malformed resource output: ${resource@Q}." >&2
      exit 1
    }
    if [[ "$resource" == "$expected_resource" ]]; then
      ((expected_resource_count += 1))
    fi
  done <<< "$discovery_output"
  (( expected_resource_count <= 1 )) || {
    echo "Hosted identity API discovery returned duplicate ${expected_resource} resources." >&2
    exit 1
  }
  if (( expected_resource_count == 0 )); then
    printf 'served=false\n'
  else
    printf 'served=true\n'
  fi
  exit 0
fi

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <identity_name> <Active|Retired>" >&2
  exit 2
fi

identity_name="$1"
desired_state="$2"
if [[ ! "$identity_name" =~ ^(dev-demo|pr-[1-9][0-9]{0,50})$ ]]; then
  echo "identity name is not canonical: ${identity_name}" >&2
  exit 2
fi
if [[ "$desired_state" != Active && "$desired_state" != Retired ]]; then
  echo "desired state must be Active or Retired: ${desired_state}" >&2
  exit 2
fi

cat <<EOF | kubectl -n firemud-system apply -f -
apiVersion: platform.firemud.dev/v1alpha1
kind: HostedEnvironmentIdentity
metadata:
  name: ${identity_name}
  namespace: firemud-system
spec:
  desiredState: ${desired_state}
EOF
