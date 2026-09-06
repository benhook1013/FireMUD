#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 4 ]]; then
  echo "usage: $0 <identity_name> <expected_head_sha> [runtime_namespace] [timeout_seconds]" >&2
  exit 2
fi

identity_name="$1"
expected_head_sha="$2"
runtime_namespace="${3:-$identity_name}"
timeout_seconds="${4:-900}"

if [[ ! "$identity_name" =~ ^(dev-demo|pr-[1-9][0-9]*)$ ]]; then
  echo "identity name is not canonical: ${identity_name}" >&2
  exit 2
fi
if [[ -z "$expected_head_sha" ]]; then
  echo "expected head SHA is required" >&2
  exit 2
fi
if [[ ! "$runtime_namespace" =~ ^(dev|pr-[1-9][0-9]*)$ ]]; then
  echo "runtime namespace is not canonical: ${runtime_namespace}" >&2
  exit 2
fi
if [[ ! "$timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "timeout must be a positive integer" >&2
  exit 2
fi

deadline=$((SECONDS + timeout_seconds))
while (( SECONDS < deadline )); do
  if ! namespace_json="$(kubectl get namespace "$runtime_namespace" -o json 2>/dev/null)"; then
    echo "Waiting for runtime namespace ${runtime_namespace}..."
    sleep 5
    continue
  fi

  namespace_uid="$(jq -r '.metadata.uid // empty' <<<"$namespace_json")"
  namespace_head="$(jq -r '(.metadata.annotations["firemud.dev/last-preview-head-sha"] // .metadata.annotations["firemud.dev/last-dev-demo-head-sha"] // empty)' <<<"$namespace_json")"
  if [[ -z "$namespace_uid" ]]; then
    echo "Runtime namespace ${runtime_namespace} has no UID yet; retrying."
    sleep 5
    continue
  fi
  if [[ "$namespace_head" != "$expected_head_sha" ]]; then
    echo "Runtime namespace ${runtime_namespace} is not aligned to requested head ${expected_head_sha}; observed ${namespace_head:-missing}."
    sleep 5
    continue
  fi

  if ! identity_json="$(kubectl -n firemud-system get hostedenvironmentidentity "$identity_name" -o json 2>/dev/null)"; then
    echo "Waiting for HostedEnvironmentIdentity/${identity_name}..."
    sleep 5
    continue
  fi

  generation="$(jq -r '.metadata.generation // empty' <<<"$identity_json")"
  observed_generation="$(jq -r '.status.observedGeneration // empty' <<<"$identity_json")"
  phase="$(jq -r '.status.phase // empty' <<<"$identity_json")"
  ready_status="$(jq -r 'first(.status.conditions[]? | select(.type == "Ready") | .status) // empty' <<<"$identity_json")"
  ready_reason="$(jq -r 'first(.status.conditions[]? | select(.type == "Ready") | .reason) // empty' <<<"$identity_json")"
  ready_message="$(jq -r 'first(.status.conditions[]? | select(.type == "Ready") | .message) // empty' <<<"$identity_json")"
  profile_uid="$(jq -r '.status.profile.runtimeNamespaceUid // empty' <<<"$identity_json")"
  profile_head="$(jq -r '.status.profile.deployedHeadSha // empty' <<<"$identity_json")"
  ingress_revision="$(jq -r '.status.ingress.revision // empty' <<<"$identity_json")"
  telnet_revision="$(jq -r '.status.telnet.revision // empty' <<<"$identity_json")"
  grpc_revision="$(jq -r '.status.grpc.revision // empty' <<<"$identity_json")"

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
  if [[ "$profile_uid" != "$namespace_uid" || "$profile_head" != "$expected_head_sha" ]]; then
    echo "Ready identity profile is stale (namespace UID ${profile_uid:-missing}, head ${profile_head:-missing}); retrying."
    sleep 5
    continue
  fi
  if [[ -z "$ingress_revision" || -z "$telnet_revision" || -z "$grpc_revision" ]]; then
    echo "Ready identity has incomplete projected revisions; retrying."
    sleep 5
    continue
  fi

  printf 'identity=%s\nphase=%s\nobservedGeneration=%s\ningressRevision=%s\ntelnetRevision=%s\ngrpcRevision=%s\n' \
    "$identity_name" "$phase" "$observed_generation" "$ingress_revision" "$telnet_revision" "$grpc_revision"
  exit 0
done

echo "Timed out waiting for HostedEnvironmentIdentity/${identity_name} to serve head ${expected_head_sha}." >&2
exit 1
