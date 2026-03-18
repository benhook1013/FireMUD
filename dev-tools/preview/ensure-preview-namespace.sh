#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <namespace> <pr_number>" >&2
  exit 1
fi

namespace="$1"
pr_number="$2"

kubectl get namespace "$namespace" >/dev/null 2>&1 || kubectl create namespace "$namespace"
kubectl label namespace "$namespace" \
  firemud.dev/preview=true \
  "firemud.dev/pr-number=${pr_number}" \
  --overwrite >/dev/null
