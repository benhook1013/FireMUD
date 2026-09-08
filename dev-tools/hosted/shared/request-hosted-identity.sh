#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <identity_name> <Active|Retired>" >&2
  exit 2
fi

identity_name="$1"
desired_state="$2"
if [[ ! "$identity_name" =~ ^(dev-demo|pr-[1-9][0-9]*)$ ]]; then
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
