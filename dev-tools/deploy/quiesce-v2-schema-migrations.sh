#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: quiesce-v2-schema-migrations.sh <namespace>

Scale the two V2 schema writers to zero and prove that their Deployment and
ReplicaSet pods have drained. The command deliberately
leaves both Deployments at zero so the caller can apply the reviewed V2 release.
EOF
}

fail() {
  echo "V2 schema migration preflight failed: $*" >&2
  exit 1
}

if [[ $# -ne 1 ]]; then
  usage >&2
  exit 2
fi

namespace="$1"
[[ "$namespace" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] ||
  fail "namespace must be one Kubernetes DNS label"

for command_name in kubectl jq; do
  command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required"
done

services=(game-session-service automation-scripting-service)

# The in-scope Services must route only to the canonical app label checked
# below. If selectors drift, the helper cannot prove admission is closed.
for service in "${services[@]}"; do
  if ! service_json="$(kubectl -n "$namespace" get service "$service" --ignore-not-found -o json)"; then
    fail "cannot inspect Service/$service in namespace $namespace"
  fi
  if [[ -n "$service_json" ]] && ! jq -e --arg service "$service" \
    '.spec.selector.app == $service' <<<"$service_json" >/dev/null; then
    fail "Service/$service does not select the canonical app=$service writer label"
  fi
done

# An HPA can undo the scale-to-zero maintenance boundary. Do not proceed if
# another controller can re-admit a writer while the V2 release is applying.
if ! hpa_json="$(kubectl -n "$namespace" get hpa -o json)"; then
  fail "cannot inspect namespace HPAs"
fi
if ! matching_hpas="$(jq -er '[.items[]? | select(.spec.scaleTargetRef.kind == "Deployment" and (.spec.scaleTargetRef.name == "game-session-service" or .spec.scaleTargetRef.name == "automation-scripting-service"))] | length' <<<"$hpa_json")"; then
  fail "cannot parse namespace HPAs"
fi
[[ "$matching_hpas" == 0 ]] ||
  fail "an HPA targets a V2 writer; remove or suspend that controller before migration"

# Scale both owners before waiting for either to drain. If any mutation or
# observation fails, leave the environment closed rather than restoring old pods.
for service in "${services[@]}"; do
  if ! deployment_name="$(kubectl -n "$namespace" get deployment "$service" --ignore-not-found -o name)"; then
    fail "cannot inspect Deployment/$service in namespace $namespace"
  fi
  if [[ -n "$deployment_name" ]]; then
    kubectl -n "$namespace" scale "deployment/$service" --replicas=0 ||
      fail "could not stop Deployment/$service"
  fi
done

deadline=$((SECONDS + 300))
for service in "${services[@]}"; do
  while :; do
    if ! deployment_json="$(kubectl -n "$namespace" get deployment "$service" --ignore-not-found -o json)"; then
      fail "cannot verify Deployment/$service after quiesce"
    fi
    if [[ -n "$deployment_json" ]]; then
      replicas="$(jq -er '.spec.replicas // 0' <<<"$deployment_json")" ||
        fail "cannot read desired replicas for Deployment/$service"
      [[ "$replicas" == 0 ]] ||
        fail "Deployment/$service did not remain at zero replicas"
    fi

    if ! pods_json="$(kubectl -n "$namespace" get pods -o json)"; then
      fail "cannot verify pods for Deployment/$service"
    fi
    if ! pod_count="$(jq -er --arg service "$service" '
      [
        .items[]?
        | select(
            (.metadata.labels.app == $service)
            or any(.spec.containers[]?;
              .name == $service
              or ((.image // "") | split("@")[0] | split(":")[0] | split("/")[-1]) == $service
            )
          )
      ] | length
    ' <<<"$pods_json")"; then
      fail "cannot parse pods for Deployment/$service"
    fi
    if [[ "$pod_count" == 0 ]]; then
      break
    fi
    (( SECONDS < deadline )) ||
      fail "old writer pods for $service did not terminate within 300 seconds ($pod_count remain)"
    sleep 2
  done

done

echo "V2 schema writers are quiesced in namespace $namespace; run the reviewed release now."
