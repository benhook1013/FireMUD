#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <namespace> <timeout_seconds>" >&2
  exit 2
fi

namespace="$1"
timeout_seconds="$2"
if [[ -z "$namespace" ]]; then
  echo "runtime namespace is required" >&2
  exit 2
fi
if ! [[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "timeout_seconds must be a positive integer" >&2
  exit 2
fi

readonly runtime_deployments=(
  postgres redis-coord redis-cache minio
  account-service automation-scripting-service entity-management-service
  game-design-service game-logic-service game-session-service
  logging-admin-service social-groups-service spring-cloud-gateway
  tcp-proxy-service world-management-service
)

for deployment in "${runtime_deployments[@]}"; do
  kubectl -n "$namespace" rollout status "deployment/${deployment}" \
    --timeout="${timeout_seconds}s"
done
