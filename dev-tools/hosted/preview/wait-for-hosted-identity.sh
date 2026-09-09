#!/usr/bin/env bash
set -euo pipefail

validate_identity_runtime_pairing() {
  local identity_name="$1"
  local runtime_namespace="$2"

  if [[ "$identity_name" == dev-demo && "$runtime_namespace" != dev ]]; then
    echo "dev-demo identity requires the dev runtime namespace" >&2
    exit 2
  fi
  if [[ "$identity_name" != dev-demo && "$runtime_namespace" != "$identity_name" ]]; then
    echo "PR identity ${identity_name} requires matching runtime namespace ${identity_name}" >&2
    exit 2
  fi
}

validate_timeout_seconds() {
  local timeout_seconds="$1"
  if [[ ! "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] ||
    ((${#timeout_seconds} > 4)) ||
    ((10#$timeout_seconds > 3600)); then
    echo "timeout must be a positive integer" >&2
    exit 2
  fi
}

if [[ "${1:-}" == "--projections" ]]; then
  if [[ $# -lt 2 || $# -gt 4 ]]; then
    echo "usage: $0 --projections <identity_name> [runtime_namespace] [timeout_seconds]" >&2
    exit 2
  fi
  identity_name="$2"
  if [[ $# -ge 3 ]]; then
    runtime_namespace="$3"
  elif [[ "$identity_name" == dev-demo ]]; then
    runtime_namespace=dev
  else
    runtime_namespace="$identity_name"
  fi
  timeout_seconds="${4:-900}"
  if [[ ! "$identity_name" =~ ^(dev-demo|pr-[1-9][0-9]*)$ ]]; then
    echo "identity name is not canonical: ${identity_name}" >&2
    exit 2
  fi
  if [[ ! "$runtime_namespace" =~ ^(dev|pr-[1-9][0-9]*)$ ]]; then
    echo "runtime namespace is not canonical: ${runtime_namespace}" >&2
    exit 2
  fi
  validate_timeout_seconds "$timeout_seconds"
  validate_identity_runtime_pairing "$identity_name" "$runtime_namespace"

  if [[ "$identity_name" == dev-demo ]]; then
    projection_prefix=dev
  else
    projection_prefix="$identity_name"
  fi
  projections=(
    "${projection_prefix}-tls|ingress|tls.crt,tls.key"
    "${projection_prefix}-telnet-tls|telnet|tls.crt,tls.key"
    "${projection_prefix}-gateway-internal-ws|gateway-internal-ws|tls.crt,tls.key,ca.crt"
    "${projection_prefix}-tcp-proxy-bridge|tcp-proxy-bridge|tls.crt,tls.key,ca.crt"
    "firemud-grpc-tls|grpc|tls.crt,tls.key,ca.crt,client.crt,client.key"
  )
  deadline=$((SECONDS + timeout_seconds))
  all_projections_ready=true
  for projection in "${projections[@]}"; do
    IFS='|' read -r secret_name role required_keys <<<"$projection"
    projection_ready=false
    projection_attempted=false
    while (( SECONDS < deadline )) || [[ "$projection_attempted" != true ]]; do
      projection_attempted=true
      if secret_json="$(kubectl -n "$runtime_namespace" get secret "$secret_name" --ignore-not-found -o json)"; then
        if [[ -z "$secret_json" ]]; then
          echo "Waiting for controller projection ${runtime_namespace}/${secret_name} to appear."
        elif jq -e \
            --arg name "$secret_name" \
            --arg identity "$identity_name" \
            --arg role "$role" \
            --arg keys "$required_keys" '
              .metadata.name == $name and
              .metadata.labels["firemud.dev/managed-by"] == "hosted-identity-controller" and
              .metadata.labels["firemud.dev/identity-name"] == $identity and
              .metadata.labels["firemud.dev/role"] == $role and
              .metadata.labels["firemud.dev/retention"] == "retained" and
              (. as $secret | ($keys | split(",")) as $required |
                all($required[]; . as $key | $secret.data[$key] | type == "string" and length > 0))
            ' <<<"$secret_json" >/dev/null; then
          projection_ready=true
          break
        else
          echo "Controller projection ${runtime_namespace}/${secret_name} is incomplete; retrying."
        fi
      else
        kubectl_status=$?
        echo "Unable to determine controller projection ${runtime_namespace}/${secret_name}; kubectl get failed (exit ${kubectl_status})." >&2
        exit "$kubectl_status"
      fi
      sleep 5
    done
    if [[ "$projection_ready" != true ]]; then
      echo "Timed out waiting for complete controller projection ${runtime_namespace}/${secret_name}." >&2
      all_projections_ready=false
    fi
  done
  if [[ "$all_projections_ready" != true ]]; then
    exit 1
  fi
  printf 'identity=%s\nruntimeNamespace=%s\nprojections=ready\n' \
    "$identity_name" "$runtime_namespace"
  exit 0
fi

if [[ "${1:-}" == "--retired" ]]; then
  if [[ $# -lt 2 || $# -gt 3 ]]; then
    echo "usage: $0 --retired <identity_name> [timeout_seconds]" >&2
    exit 2
  fi
  identity_name="$2"
  timeout_seconds="${3:-600}"
  if [[ ! "$identity_name" =~ ^(dev-demo|pr-[1-9][0-9]*)$ ]]; then
    echo "identity name is not canonical: ${identity_name}" >&2
    exit 2
  fi
  validate_timeout_seconds "$timeout_seconds"

  deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < deadline )); do
    if identity_json="$(kubectl -n firemud-system get hostedenvironmentidentity "$identity_name" --ignore-not-found -o json)"; then
      if [[ -z "$identity_json" ]]; then
        printf 'identity=%s\nphase=Retired\n' "$identity_name"
        exit 0
      else
        generation="$(jq -r '.metadata.generation // empty' <<<"$identity_json")"
        observed_generation="$(jq -r '.status.observedGeneration // empty' <<<"$identity_json")"
        phase="$(jq -r '.status.phase // empty' <<<"$identity_json")"
        ready_status="$(jq -r 'first(.status.conditions[]? | select(.type == "Ready") | .status) // empty' <<<"$identity_json")"
        ready_generation="$(jq -r 'first(.status.conditions[]? | select(.type == "Ready") | .observedGeneration) // empty' <<<"$identity_json")"
        if [[ "$phase" == "Retired" && "$observed_generation" == "$generation" && "$ready_status" == "False" && "$ready_generation" == "$generation" ]]; then
          printf 'identity=%s\nphase=%s\nobservedGeneration=%s\n' \
            "$identity_name" "$phase" "$observed_generation"
          exit 0
        fi
        echo "Waiting for HostedEnvironmentIdentity/${identity_name} generation ${generation:-missing} Retired/Ready=False (phase=${phase:-missing}, observed=${observed_generation:-missing})."
      fi
    else
      kubectl_status=$?
      echo "Unable to determine retirement state for HostedEnvironmentIdentity/${identity_name}; kubectl get failed (exit ${kubectl_status})." >&2
      exit "$kubectl_status"
    fi
    sleep 5
  done
  echo "Timed out waiting for HostedEnvironmentIdentity/${identity_name} to retire." >&2
  exit 1
fi

if [[ $# -lt 2 || $# -gt 4 ]]; then
  echo "usage: $0 <identity_name> <expected_head_sha> [runtime_namespace] [timeout_seconds]" >&2
  exit 2
fi

identity_name="$1"
expected_head_sha="$2"
if [[ $# -ge 3 ]]; then
  runtime_namespace="$3"
elif [[ "$identity_name" == dev-demo ]]; then
  runtime_namespace=dev
else
  runtime_namespace="$identity_name"
fi
timeout_seconds="${4:-900}"

if [[ ! "$identity_name" =~ ^(dev-demo|pr-[1-9][0-9]*)$ ]]; then
  echo "identity name is not canonical: ${identity_name}" >&2
  exit 2
fi
normalize_head_sha() {
  local head_sha="$1"
  [[ "$head_sha" =~ ^[0-9a-fA-F]{40}$ ]] || return 1
  printf '%s' "${head_sha,,}"
}

if ! expected_head_sha="$(normalize_head_sha "$expected_head_sha")"; then
  echo "expected head SHA must be exactly 40 hexadecimal characters" >&2
  exit 2
fi
if [[ ! "$runtime_namespace" =~ ^(dev|pr-[1-9][0-9]*)$ ]]; then
  echo "runtime namespace is not canonical: ${runtime_namespace}" >&2
  exit 2
fi
validate_timeout_seconds "$timeout_seconds"
validate_identity_runtime_pairing "$identity_name" "$runtime_namespace"

deadline=$((SECONDS + timeout_seconds))
while (( SECONDS < deadline )); do
  if namespace_json="$(kubectl get namespace "$runtime_namespace" --ignore-not-found -o json)"; then
    if [[ -z "$namespace_json" ]]; then
      echo "Waiting for runtime namespace ${runtime_namespace} to appear..."
      sleep 5
      continue
    fi
  else
    kubectl_status=$?
    echo "Unable to determine runtime namespace ${runtime_namespace}; kubectl get failed (exit ${kubectl_status})." >&2
    exit "$kubectl_status"
  fi

  namespace_uid="$(jq -r '.metadata.uid // empty' <<<"$namespace_json")"
  if [[ "$identity_name" == dev-demo ]]; then
    namespace_requested_head="$(jq -r '.metadata.annotations["firemud.dev/requested-dev-demo-head-sha"] // empty' <<<"$namespace_json")"
    namespace_deployed_head="$(jq -r '.metadata.annotations["firemud.dev/last-dev-demo-head-sha"] // empty' <<<"$namespace_json")"
  else
    namespace_requested_head="$(jq -r '.metadata.annotations["firemud.dev/requested-preview-head-sha"] // empty' <<<"$namespace_json")"
    namespace_deployed_head="$(jq -r '.metadata.annotations["firemud.dev/last-preview-head-sha"] // empty' <<<"$namespace_json")"
  fi
  if [[ -z "$namespace_uid" ]]; then
    echo "Runtime namespace ${runtime_namespace} has no UID yet; retrying."
    sleep 5
    continue
  fi
  if ! normalized_namespace_requested_head="$(normalize_head_sha "$namespace_requested_head")"; then
    echo "Runtime namespace ${runtime_namespace} has no canonical requested head; observed ${namespace_requested_head:-missing}."
    sleep 5
    continue
  fi
  if [[ "$normalized_namespace_requested_head" != "$expected_head_sha" ]]; then
    echo "Runtime namespace ${runtime_namespace} requested head is stale; expected ${expected_head_sha}, observed ${namespace_requested_head}."
    sleep 5
    continue
  fi
  if ! normalized_namespace_deployed_head="$(normalize_head_sha "$namespace_deployed_head")"; then
    echo "Runtime namespace ${runtime_namespace} has no canonical deployed head; observed ${namespace_deployed_head:-missing}."
    sleep 5
    continue
  fi
  if [[ "$normalized_namespace_deployed_head" != "$expected_head_sha" ]]; then
    echo "Runtime namespace ${runtime_namespace} deployed head is stale; expected ${expected_head_sha}, observed ${namespace_deployed_head}."
    sleep 5
    continue
  fi

  if identity_json="$(kubectl -n firemud-system get hostedenvironmentidentity "$identity_name" --ignore-not-found -o json)"; then
    if [[ -z "$identity_json" ]]; then
      echo "Waiting for HostedEnvironmentIdentity/${identity_name} to appear..."
      sleep 5
      continue
    fi
  else
    kubectl_status=$?
    echo "Unable to determine HostedEnvironmentIdentity/${identity_name}; kubectl get failed (exit ${kubectl_status})." >&2
    exit "$kubectl_status"
  fi

  generation="$(jq -r '.metadata.generation // empty' <<<"$identity_json")"
  observed_generation="$(jq -r '.status.observedGeneration // empty' <<<"$identity_json")"
  phase="$(jq -r '.status.phase // empty' <<<"$identity_json")"
  ready_status="$(jq -r 'first(.status.conditions[]? | select(.type == "Ready") | .status) // empty' <<<"$identity_json")"
  ready_reason="$(jq -r 'first(.status.conditions[]? | select(.type == "Ready") | .reason) // empty' <<<"$identity_json")"
  ready_message="$(jq -r 'first(.status.conditions[]? | select(.type == "Ready") | .message) // empty' <<<"$identity_json")"
  profile_uid="$(jq -r '.status.profile.runtimeNamespaceUid // empty' <<<"$identity_json")"
  profile_requested_head="$(jq -r '.status.profile.requestedHeadSha // empty' <<<"$identity_json")"
  profile_deployed_head="$(jq -r '.status.profile.deployedHeadSha // empty' <<<"$identity_json")"
  ingress_revision="$(jq -r '.status.ingress.revision // empty' <<<"$identity_json")"
  telnet_revision="$(jq -r '.status.telnet.revision // empty' <<<"$identity_json")"
  grpc_revision="$(jq -r '.status.grpc.revision // empty' <<<"$identity_json")"
  gateway_internal_ws_revision="$(jq -r '.status.gatewayInternalWs.revision // empty' <<<"$identity_json")"
  tcp_proxy_bridge_revision="$(jq -r '.status.tcpProxyBridge.revision // empty' <<<"$identity_json")"

  case "$phase" in
    Pending|Provisioning|WaitingForCertificate|RuntimeAbsent|Syncing|Verifying|Degraded|Blocked|Retiring|Retired)
      echo "HostedEnvironmentIdentity/${identity_name} is not ready (phase=${phase}, reason=${ready_reason:-unknown}, message=${ready_message:-unknown})."
      sleep 5
      continue
      ;;
    Ready)
      ;;
    *)
      echo "HostedEnvironmentIdentity/${identity_name} has unknown phase ${phase:-missing}; retrying."
      sleep 5
      continue
      ;;
  esac
  if [[ "$observed_generation" != "$generation" || "$ready_status" != "True" ]]; then
    echo "Waiting for HostedEnvironmentIdentity/${identity_name} generation ${generation} Ready=True (observed=${observed_generation:-missing}, reason=${ready_reason:-unknown})."
    sleep 5
    continue
  fi
  if [[ "$profile_uid" != "$namespace_uid" ]]; then
    echo "Ready identity profile is stale (namespace UID ${profile_uid:-missing}, requested head ${profile_requested_head:-missing}, deployed head ${profile_deployed_head:-missing}); retrying."
    sleep 5
    continue
  fi
  if ! normalized_profile_requested_head="$(normalize_head_sha "$profile_requested_head")" ||
    ! normalized_profile_deployed_head="$(normalize_head_sha "$profile_deployed_head")" ||
    [[ "$normalized_profile_requested_head" != "$expected_head_sha" ]] ||
    [[ "$normalized_profile_deployed_head" != "$expected_head_sha" ]]; then
    echo "Ready identity profile is stale (namespace UID ${profile_uid:-missing}, requested head ${profile_requested_head:-missing}, deployed head ${profile_deployed_head:-missing}); retrying."
    sleep 5
    continue
  fi
  if [[ -z "$ingress_revision" || -z "$telnet_revision" || -z "$gateway_internal_ws_revision" || -z "$tcp_proxy_bridge_revision" || -z "$grpc_revision" ]]; then
    echo "Ready identity has incomplete projected revisions; retrying."
    sleep 5
    continue
  fi

  printf 'identity=%s\nphase=%s\nobservedGeneration=%s\ningressRevision=%s\ntelnetRevision=%s\ngatewayInternalWsRevision=%s\ntcpProxyBridgeRevision=%s\ngrpcRevision=%s\n' \
    "$identity_name" "$phase" "$observed_generation" "$ingress_revision" "$telnet_revision" "$gateway_internal_ws_revision" "$tcp_proxy_bridge_revision" "$grpc_revision"
  exit 0
done

echo "Timed out waiting for HostedEnvironmentIdentity/${identity_name} to serve head ${expected_head_sha}." >&2
exit 1
