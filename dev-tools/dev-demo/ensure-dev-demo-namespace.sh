#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <namespace>" >&2
  exit 1
fi

namespace="$1"

kubectl get namespace "$namespace" >/dev/null 2>&1 || kubectl create namespace "$namespace"
kubectl label namespace "$namespace" \
  firemud.dev/dev-demo=true \
  firemud.dev/environment-class=dev-demo-cluster \
  --overwrite >/dev/null
