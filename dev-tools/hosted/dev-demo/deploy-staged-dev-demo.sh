#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <namespace> <release> <values-file>" >&2
  exit 2
fi
runtime_namespace="$1"
release_name="$2"
values_file="$3"
echo "DEV_DEMO_STAGE=deploy" >> "${GITHUB_ENV:?}"
# Keep the publication coordinator unavailable until every digest
# participant has rolled to this image. A single chart apply can
# otherwise make Game Design ready while participants are mixed.
game_design_service_index="$(python3 - "$values_file" <<'PY'
import sys

import yaml

with open(sys.argv[1], encoding="utf-8") as values_file:
    values = yaml.safe_load(values_file)
services = values.get("previewStack", {}).get("services") if isinstance(values, dict) else None
if not isinstance(services, list):
    raise SystemExit("rendered dev-demo values must contain previewStack.services")
matches = [
    index
    for index, service in enumerate(services)
    if isinstance(service, dict) and service.get("name") == "game-design-service"
]
if len(matches) != 1:
    raise SystemExit(
        "rendered dev-demo values must contain exactly one game-design-service "
        f"(found {len(matches)})"
    )
print(matches[0])
PY
)"
# Namespace and release are supplied by the workflow arguments.
game_design_service="game-design-service"
closed_selector="firemud-game-design-migration-quiesced"
admission_mutation_started=false
deploy_complete=false

assert_no_reserved_selector_pods() {
  local pods_json="$1"
  local error_message="$2"
  if ! jq -e --arg selector "$closed_selector" '
    if .kind != "PodList" or ((.items | type) != "array") then
      error("invalid PodList")
    else
      ([.items[]? | select((.metadata.labels.app // "") == $selector)] | length) == 0
    end
  ' <<<"$pods_json" >/dev/null; then
    echo "$error_message" >&2
    return 1
  fi
}

count_old_game_design_pods() {
  local pods_json="$1"
  jq -er --arg service "$game_design_service" '
    if .kind != "PodList" or ((.items | type) != "array") then
      error("invalid PodList")
    else
      [
        .items[]?
        | select(
            ((.metadata.labels.app == $service)
              or any(.spec.containers[]?;
                .name == $service
                or ((.image // "") | split("@")[0] | split(":")[0] | split("/")[-1]) == $service
              ))
            and ((.status.phase // "Unknown") != "Succeeded")
            and ((.status.phase // "Unknown") != "Failed")
        )
      ] | length
    end
  ' <<<"$pods_json"
}

# Return codes distinguish patch, readback, and selector verification failures.
# The refreshed Service JSON is assigned to the named caller variable.
close_game_design_admission() {
  local current_json="$1"
  local target_variable="$2"
  local force_patch="$3"
  local quiet_patch="$4"
  local readback
  local patch_output=/dev/stdout
  [[ "$quiet_patch" == true ]] && patch_output=/dev/null
  if [[ "$force_patch" == true ]] ||
    ! jq -e --arg selector "$closed_selector" '.spec.selector == {app: $selector}' <<<"$current_json" >/dev/null; then
    kubectl -n "$runtime_namespace" patch service "$game_design_service" \
      --type=merge \
      --patch "{\"spec\":{\"selector\":{\"app\":\"$closed_selector\"}}}" >"$patch_output" || return 1
  fi
  readback="$(kubectl -n "$runtime_namespace" get service "$game_design_service" -o json)" || return 2
  jq -e --arg selector "$closed_selector" '.spec.selector == {app: $selector}' <<<"$readback" >/dev/null || return 3
  printf -v "$target_variable" '%s' "$readback"
}

leave_game_design_closed_on_failure() {
  local exit_status=$?
  local failure_service_json
  trap - EXIT
  if [[ "$admission_mutation_started" == true && "$deploy_complete" != true ]]; then
    if ! failure_service_json="$(kubectl -n "$runtime_namespace" \
      get service "$game_design_service" --ignore-not-found -o json)"; then
      echo "::error title=Game Design admission closure failed::Could not read the Service after staged deployment failure." >&2
    elif [[ -n "$failure_service_json" ]]; then
      if ! jq -e --arg service "$game_design_service" --arg selector "$closed_selector" '
        .kind == "Service" and .metadata.name == $service
      ' <<<"$failure_service_json" >/dev/null; then
        echo "::error title=Game Design admission closure failed::Service readback is ambiguous after staged deployment failure." >&2
      else
        local failure_pods_json
        failure_pods_json="$(kubectl -n "$runtime_namespace" get pods -o json)" || {
          echo "::error title=Game Design admission closure failed::Could not verify the reserved selector after staged deployment failure." >&2
          exit "$exit_status"
        }
        if ! assert_no_reserved_selector_pods "$failure_pods_json" \
          "::error title=Game Design admission closure failed::Reserved selector is ambiguous after staged deployment failure."; then
          exit "$exit_status"
        fi
        local close_status=0
        close_game_design_admission "$failure_service_json" failure_service_json false true || close_status=$?
        case "$close_status" in
          1) echo "::error title=Game Design admission closure failed::Could not reapply the closed Service selector after staged deployment failure." >&2 ;;
          2) echo "::error title=Game Design admission closure failed::Could not read back the Service after staged deployment failure." >&2 ;;
          3) echo "::error title=Game Design admission closure failed::Could not verify the closed Service selector after staged deployment failure." >&2 ;;
        esac
        [[ "$close_status" == 0 ]] || exit "$exit_status"
      fi
    fi
  fi
  exit "$exit_status"
}
trap leave_game_design_closed_on_failure EXIT

service_json="$(kubectl -n "$runtime_namespace" get service "$game_design_service" \
  --ignore-not-found -o json)"
pods_json="$(kubectl -n "$runtime_namespace" get pods -o json)"
assert_no_reserved_selector_pods "$pods_json" \
  "The reserved Game Design closed selector already matches a Pod." || exit 1
if [[ -z "$service_json" ]]; then
  deployment_json="$(kubectl -n "$runtime_namespace" get deployment "$game_design_service" \
    --ignore-not-found -o json)"
  endpoint_slices_json="$(kubectl -n "$runtime_namespace" \
    get endpointslices.discovery.k8s.io \
    --selector kubernetes.io/service-name=game-design-service -o json)"
  jq -e --arg service "$game_design_service" '
    if .kind != "EndpointSliceList" or ((.items | type) != "array") then
      error("invalid Game Design EndpointSliceList")
    elif any(.items[]?; (.metadata.labels["kubernetes.io/service-name"] // "") != $service) then
      error("Game Design EndpointSlice service label mismatch")
    else
      (.items | length) == 0
    end
  ' <<<"$endpoint_slices_json" >/dev/null || {
    echo "Game Design Service is absent but EndpointSlices remain; refusing first-install admission." >&2
    exit 1
  }
  if [[ -n "$deployment_json" ]]; then
    jq -e --arg service "$game_design_service" \
      '.kind == "Deployment" and .metadata.name == $service' \
      <<<"$deployment_json" >/dev/null || {
      echo "Game Design Service is absent but its Deployment readback is ambiguous." >&2
      exit 1
    }
    echo "Game Design Service is absent but a Deployment remains; refusing first-install admission." >&2
    exit 1
  fi
  old_pod_count="$(count_old_game_design_pods "$pods_json")"
  [[ "$old_pod_count" == 0 ]] || {
    echo "Game Design Service is absent but old Pods remain; refusing first-install admission." >&2
    exit 1
  }
else
  jq -e --arg service "$game_design_service" --arg selector "$closed_selector" '
    .metadata.name == $service and
    (.spec.selector == {app: $service} or .spec.selector == {app: $selector})
  ' <<<"$service_json" >/dev/null || {
    admission_mutation_started=true
    echo "Game Design Service has an unknown selector; refusing staged deployment." >&2
    exit 1
  }
fi
hpa_json="$(kubectl -n "$runtime_namespace" get hpa -o json)"
jq -e --arg service "$game_design_service" '
  ([.items[]? | select(
    .spec.scaleTargetRef.kind == "Deployment" and
    .spec.scaleTargetRef.name == $service
  )] | length) == 0
' <<<"$hpa_json" >/dev/null || {
  echo "An HPA targets the Game Design Deployment; remove or suspend it before migration." >&2
  exit 1
}

if [[ -n "$service_json" ]]; then
  admission_mutation_started=true
  close_status=0
  close_game_design_admission "$service_json" service_json false false || close_status=$?
  if [[ "$close_status" != 0 ]]; then
    echo "Game Design Service selector did not remain closed." >&2
    exit 1
  fi
  deadline=$((SECONDS + 300))
  while :; do
    endpoint_slices_json="$(kubectl -n "$runtime_namespace" \
      get endpointslices.discovery.k8s.io \
      --selector kubernetes.io/service-name=game-design-service -o json)"
    endpoint_count="$(jq -er --arg service "$game_design_service" '
      if .kind != "EndpointSliceList" or ((.items | type) != "array") then
        error("invalid Game Design EndpointSliceList")
      elif any(.items[]?; (.metadata.labels["kubernetes.io/service-name"] // "") != $service) then
        error("Game Design EndpointSlice service label mismatch")
      else
        [.items[]? | (.endpoints // [])[]?] | length
      end
    ' <<<"$endpoint_slices_json")"
    if [[ "$endpoint_count" == 0 ]]; then
      break
    fi
    (( SECONDS < deadline )) || {
      echo "Game Design publication endpoints remain after 300 seconds." >&2
      exit 1
    }
    sleep 2
  done
fi
deadline=$((SECONDS + 300))
deployment_scale_requested=false
while :; do
  deployment_json="$(kubectl -n "$runtime_namespace" get deployment "$game_design_service" \
    --ignore-not-found -o json)"
  if [[ -z "$deployment_json" ]]; then
    deployment_replicas=0
  else
    deployment_replicas="$(jq -er '
      if .kind != "Deployment" or .metadata.name != "game-design-service" or
        (.spec.replicas | type) != "number" or .spec.replicas < 0 then
        error("invalid Game Design Deployment readback")
      else
        .spec.replicas
      end
    ' <<<"$deployment_json")"
  fi
  pods_json="$(kubectl -n "$runtime_namespace" get pods -o json)"
  old_pod_count="$(count_old_game_design_pods "$pods_json")"
  if [[ "$deployment_replicas" == 0 && "$old_pod_count" == 0 ]]; then
    break
  fi
  if [[ -n "$deployment_json" && "$deployment_scale_requested" != true ]]; then
    kubectl -n "$runtime_namespace" scale "deployment/$game_design_service" --replicas=0
    deployment_scale_requested=true
  fi
  (( SECONDS < deadline )) || {
    echo "Old Game Design Deployment or Pods remain after 300 seconds." >&2
    exit 1
  }
  sleep 2
done

# Helm --wait proves that every participant generation is ready while
# Game Design remains at zero and its Service admission stays closed.
admission_mutation_started=true
helm upgrade --install "$release_name" k8s/helm/firemud \
  -f "$values_file" \
  --set "previewStack.services[${game_design_service_index}].replicaCount=0" \
  --namespace "$runtime_namespace" \
  --wait \
  --timeout 15m
# The participant chart reapplies the canonical Service selector;
# close Game Design again before the publication-restore upgrade.
close_status=0
close_game_design_admission "$service_json" service_json true false || close_status=$?
if [[ "$close_status" != 0 ]]; then
  echo "Game Design Service selector could not remain closed before publication restore." >&2
  exit 1
fi
# Restore the canonical Service selector only after the participant
# generation has passed Helm's readiness gate.
helm upgrade --install "$release_name" k8s/helm/firemud \
  -f "$values_file" \
  --namespace "$runtime_namespace" \
  --wait \
  --timeout 15m
restored_service_json="$(kubectl -n "$runtime_namespace" get service "$game_design_service" -o json)" || {
  echo "Game Design Service could not be read after publication restore." >&2
  exit 1
}
jq -e --arg service "$game_design_service" '
  .kind == "Service" and
  .metadata.name == $service and
  .spec.selector == {app: $service}
' <<<"$restored_service_json" >/dev/null || {
  echo "Game Design Service selector did not match the canonical chart selector after publication restore." >&2
  exit 1
}
deploy_complete=true
trap - EXIT
