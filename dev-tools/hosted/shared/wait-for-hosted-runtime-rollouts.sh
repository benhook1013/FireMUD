#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <namespace> <per_deployment_timeout_seconds>" >&2
  exit 2
fi

namespace="$1"
per_deployment_timeout_seconds="$2"
if [[ -z "$namespace" ]]; then
  echo "runtime namespace is required" >&2
  exit 2
fi
if ! [[ "$per_deployment_timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "per_deployment_timeout_seconds must be a positive integer" >&2
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
  # This bound applies independently to each deployment rollout.
  kubectl -n "$namespace" rollout status "deployment/${deployment}" \
    --timeout="${per_deployment_timeout_seconds}s"
done
