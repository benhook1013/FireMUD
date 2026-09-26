#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: quiesce-v2-schema-migrations.sh <namespace>

Close service admission for Account, Game Session, and Automation, prove their
EndpointSlices are empty, scale their schema writers to zero, and prove their
Deployment and Pod state is quiescent. On failure after closure begins, the
helper attempts to keep all canonical Services closed. If closure cannot be
verified or reapplied, an operator must verify and repair admission before
retrying a migration. On success, canonical selectors are restored while all
writer Deployments stay at zero for the caller to apply the reviewed release.
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

services=(account-service game-session-service automation-scripting-service)
closed_selector="firemud-v2-migration-quiesced"
admission_mutation_started=false
quiesce_complete=false

leave_admission_closed_on_failure() {
  local exit_status=$?
  trap - EXIT
  if [[ "$admission_mutation_started" == true && "$quiesce_complete" != true ]]; then
    for service in "${services[@]}"; do
      kubectl -n "$namespace" patch service "$service" --type=merge \
        --patch "{\"spec\":{\"selector\":{\"app\":\"$closed_selector\"}}}" >/dev/null ||
        echo "V2 schema migration preflight failed: could not leave Service/$service closed after failure" >&2
    done
  fi
  exit "$exit_status"
}
trap leave_admission_closed_on_failure EXIT

service_json() {
  local service="$1"
  local result
  if ! result="$(kubectl -n "$namespace" get service "$service" --ignore-not-found -o json)"; then
    fail "cannot inspect Service/$service in namespace $namespace"
  fi
  [[ -n "$result" ]] || fail "Service/$service is absent in namespace $namespace"
  printf '%s\n' "$result"
}

patch_service_selector() {
  local service="$1"
  local selector="$2"
  kubectl -n "$namespace" patch service "$service" --type=merge \
    --patch "{\"spec\":{\"selector\":{\"app\":\"$selector\"}}}"
}

read_endpoint_slices() {
  local service="$1"
  local result
  if ! result="$(kubectl -n "$namespace" get endpointslices.discovery.k8s.io \
    --selector "kubernetes.io/service-name=$service" -o json)"; then
    fail "cannot inspect EndpointSlices for Service/$service"
  fi
  printf '%s\n' "$result"
}

# The in-scope Services must exist and use the exact canonical selector. If
# selectors have drifted, the helper cannot prove which traffic they admit.
for service in "${services[@]}"; do
  json="$(service_json "$service")"
  jq -e --arg service "$service" \
    '.metadata.name == $service and .spec.selector == {app: $service}' \
    <<<"$json" >/dev/null ||
    fail "Service/$service does not have the canonical app=$service selector"
done

# An HPA can undo the scale-to-zero maintenance boundary. Do not proceed if
# another controller can re-admit a writer while the migration is applying.
if ! hpa_json="$(kubectl -n "$namespace" get hpa -o json)"; then
  fail "cannot inspect namespace HPAs"
fi
if ! matching_hpas="$(jq -er '[.items[]? | select(.spec.scaleTargetRef.kind == "Deployment" and (.spec.scaleTargetRef.name == "account-service" or .spec.scaleTargetRef.name == "game-session-service" or .spec.scaleTargetRef.name == "automation-scripting-service"))] | length' <<<"$hpa_json")"; then
  fail "cannot parse namespace HPAs"
fi
[[ "$matching_hpas" == 0 ]] ||
  fail "an HPA targets a migration writer; remove or suspend that controller before migration"

if ! pods_json="$(kubectl -n "$namespace" get pods -o json)"; then
  fail "cannot inspect namespace Pods before closing Service selectors"
fi
if ! sentinel_pod_count="$(jq -er --arg selector "$closed_selector" '
  def valid_pod_list:
    (.kind == "PodList" or .kind == "List")
    and ((.items | type) == "array")
    and (.kind != "List" or all(.items[]?; type == "object" and .kind == "Pod"));
  if valid_pod_list | not then
    error("invalid PodList")
  else
    [.items[]? | select(.metadata.labels.app == $selector)] | length
  end
' <<<"$pods_json")"; then
  fail "cannot determine whether the closed Service selector matches an existing Pod"
fi
[[ "$sentinel_pod_count" == 0 ]] ||
  fail "closed selector app=$closed_selector already matches a Pod; choose a nonmatching selector before migration"

# Close every canonical Service before observing endpoint removal. Set the
# failure trap first so partial patch/readback/API failures cannot reopen traffic.
admission_mutation_started=true
for service in "${services[@]}"; do
  patch_service_selector "$service" "$closed_selector" ||
    fail "could not close Service/$service admission"
done

for service in "${services[@]}"; do
  json="$(service_json "$service")"
  jq -e --arg selector "$closed_selector" \
    '.spec.selector == {app: $selector}' <<<"$json" >/dev/null ||
    fail "Service/$service selector did not remain closed"
done

deadline=$((SECONDS + 300))
for service in "${services[@]}"; do
  while :; do
    endpoint_json="$(read_endpoint_slices "$service")"
    if ! endpoint_count="$(jq -er --arg service "$service" '
      def valid_endpoint_slice_list:
        (.kind == "EndpointSliceList" or .kind == "List")
        and ((.items | type) == "array")
        and (.kind != "List" or all(.items[]?; type == "object" and .kind == "EndpointSlice"));
      if valid_endpoint_slice_list | not then
        error("invalid EndpointSliceList")
      elif any(.items[]?; (.metadata.labels["kubernetes.io/service-name"] // "") != $service) then
        error("EndpointSlice service label mismatch")
      else
        [.items[]? | (.endpoints // [])[]] | length
      end
    ' <<<"$endpoint_json")"; then
      fail "cannot validate EndpointSlices for Service/$service"
    fi
    [[ "$endpoint_count" == 0 ]] && break
    (( SECONDS < deadline )) ||
      fail "EndpointSlices for Service/$service still contain endpoints after 300 seconds ($endpoint_count remain)"
    sleep 2
  done
done

# An HPA or a concurrent controller can race the first observation. Scale all
# owners before checking any Deployment or Pod so no writer can overlap apply.
deadline=$((SECONDS + 300))
for service in "${services[@]}"; do
  if ! deployment_name="$(kubectl -n "$namespace" get deployment "$service" --ignore-not-found -o name)"; then
    fail "cannot inspect Deployment/$service"
  fi
  if [[ -n "$deployment_name" ]]; then
    kubectl -n "$namespace" scale "deployment/$service" --replicas=0 ||
      fail "could not stop Deployment/$service"
  fi
done

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
      def valid_pod_list:
        (.kind == "PodList" or .kind == "List")
        and ((.items | type) == "array")
        and (.kind != "List" or all(.items[]?; type == "object" and .kind == "Pod"));
      if valid_pod_list | not then
        error("invalid PodList")
      else
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
      end
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

# Restore only after every writer Deployment and Pod has been proven quiescent.
# If any write/readback fails, the EXIT trap closes all three selectors again.
for service in "${services[@]}"; do
  patch_service_selector "$service" "$service" ||
    fail "could not restore canonical Service/$service selector"
done
for service in "${services[@]}"; do
  json="$(service_json "$service")"
  jq -e --arg service "$service" \
    '.metadata.name == $service and .spec.selector == {app: $service}' \
    <<<"$json" >/dev/null ||
    fail "Service/$service canonical selector could not be verified"
done

quiesce_complete=true
trap - EXIT
echo "Account, Game Session, and Automation schema writers are quiesced in namespace $namespace; run the reviewed migration release now."
