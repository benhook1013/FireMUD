#!/usr/bin/env bash
set -euo pipefail

check_permissions=false
if [[ "${1:-}" == --check-permissions ]]; then
  check_permissions=true
  shift
fi

if [[ $# -ne 5 ]]; then
  echo "usage: $0 [--check-permissions] <namespace> <namespace-uid> <deployed-sha> <target-sha> <applied-origin-migrations:0..3>" >&2
  exit 2
fi

namespace="$1"
namespace_uid="$2"
deployed_sha="$3"
target_sha="$4"
origin_prefix="$5"
helper_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

fail() {
  echo "Account V1040.4 preflight failed: $*" >&2
  exit 1
}

if [[ "$namespace" != dev || ! "$namespace_uid" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$ ||
  ! "$deployed_sha" =~ ^[a-f0-9]{40}$ || ! "$target_sha" =~ ^[a-f0-9]{40}$ ||
  "$deployed_sha" == "$target_sha" || ! "$origin_prefix" =~ ^[0-3]$ ||
  -z "${DEV_NAMESPACE_MANAGER_KUBECONFIG:-}" || -z "${KUBECONFIG:-}" ]]; then
  fail "the protected caller must provide the exact dev deployment comparison and its scoped kubeconfigs"
fi
[[ "$(git -C "$helper_dir" rev-parse HEAD)" == "$target_sha" ]] ||
  fail "the checked-out probe source does not match the target"

cluster_route() {
  kubectl --kubeconfig "$1" config view --raw --flatten --minify -o json |
    jq -ce '
      .clusters | select(length == 1) | .[0].cluster
      | select((.server | type == "string" and startswith("https://"))
          and (."certificate-authority-data" | type == "string" and length > 0)
          and ((."insecure-skip-tls-verify" // false) == false))
      | {server, ca: ."certificate-authority-data",
          tlsName: (."tls-server-name" // ""), proxy: (."proxy-url" // "")}
    '
}

manager_route="$(cluster_route "$DEV_NAMESPACE_MANAGER_KUBECONFIG")" ||
  fail "the namespace manager credential must identify a TLS-verified cluster"
runtime_route="$(cluster_route "$KUBECONFIG")" ||
  fail "the runtime credential must identify a TLS-verified cluster"
[[ "$manager_route" == "$runtime_route" ]] ||
  fail "the namespace manager and runtime credentials must address the same cluster"

verify_namespace() {
  kubectl --kubeconfig "$DEV_NAMESPACE_MANAGER_KUBECONFIG" --request-timeout=10s get namespace "$namespace" -o json |
    jq -e --arg namespace "$namespace" --arg uid "$namespace_uid" --arg deployed "$deployed_sha" '
      .metadata.name == $namespace and .metadata.uid == $uid and
      .metadata.labels["firemud.dev/dev-demo"] == "true" and
      .metadata.labels["firemud.dev/environment-class"] == "dev-demo-cluster" and
      .metadata.annotations["firemud.dev/last-dev-demo-head-sha"] == $deployed
    ' >/dev/null
}

verify_held_source() {
  verify_namespace || fail "the namespace identity or deployed head changed"
  bash "$helper_dir/quiesce-v2-schema-migrations.sh" --verify-only "$namespace"
}

if [[ "$check_permissions" == true ]]; then
  verify_namespace || fail "the namespace identity or deployed head changed"
else
  verify_held_source
fi
image="$(kubectl --request-timeout=10s -n "$namespace" get deployment postgres -o json |
  jq -er --arg namespace "$namespace" '
    select(.kind == "Deployment" and .metadata.name == "postgres" and .metadata.namespace == $namespace)
    | .spec.template.spec.containers | map(select(.name == "postgres"))
    | select(length == 1) | .[0].image | select(type == "string")')" ||
  fail "the retained PostgreSQL image is unavailable"
image_ref="${image%@sha256:*}"
image_basename="${image_ref##*/}"
image_tag="${image_basename##*:}"
[[ "$image" =~ @sha256:[0-9a-f]{64}$ &&
  "$image_basename" == postgres:* && "$image_tag" =~ ^[0-9][a-zA-Z0-9._-]*$ ]] ||
  fail "the retained PostgreSQL image is not an approved digest-pinned client"

probe_source="$(<"$helper_dir/probe-dev-account-v1040-4.sh")"
probe_id="$(openssl rand -hex 16)"
[[ "$probe_id" =~ ^[0-9a-f]{32}$ ]] || fail "the probe job identity could not be generated"
job_name="firemud-account-v1040-4-$probe_id"
job_uid=""

read_owned_job_uid() {
  jq -er --arg name "$job_name" --arg namespace "$namespace" --arg uid "$namespace_uid" --arg target "$target_sha" '
    select(.kind == "Job" and .metadata.name == $name and .metadata.namespace == $namespace
      and .metadata.annotations["firemud.dev/probe-namespace-uid"] == $uid
      and .metadata.annotations["firemud.dev/probe-target"] == $target)
    | .metadata.uid | select(type == "string" and test("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"))
  '
}

remove_probe_job() {
  local delete_accepted=false observed observed_uid observed_deleting deadline
  if jq -n --arg uid "$job_uid" \
    '{apiVersion:"v1",kind:"DeleteOptions",preconditions:{uid:$uid},propagationPolicy:"Foreground"}' |
    kubectl --request-timeout=10s delete --raw "/apis/batch/v1/namespaces/$namespace/jobs/$job_name" -f - >/dev/null; then
    delete_accepted=true
  fi

  deadline=$((SECONDS + 30))
  while :; do
    # A lost DELETE response may still have removed the Job. A different UID
    # proves only that the original is gone and must never be given to DELETE.
    if observed="$(kubectl --request-timeout=10s -n "$namespace" get job "$job_name" --ignore-not-found -o json)"; then
      if [[ -z "$observed" ]]; then
        job_uid=""
        return 0
      fi
      if observed_uid="$(jq -er --arg name "$job_name" --arg namespace "$namespace" '
        select(.kind == "Job" and .metadata.name == $name and .metadata.namespace == $namespace)
        | .metadata.uid | select(type == "string" and test("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"))
      ' <<<"$observed")"; then
        if [[ "$observed_uid" != "$job_uid" ]]; then
          job_uid=""
          return 0
        fi
        if observed_deleting="$(jq -er '.metadata.deletionTimestamp | select(type == "string" and length > 0)' <<<"$observed")"; then
          delete_accepted=true
        fi
      fi
    fi
    [[ "$delete_accepted" == true ]] && (( SECONDS < deadline )) || return 1
    sleep 2
  done
}

cleanup() {
  local status=$?
  trap - EXIT
  if [[ -n "$job_uid" ]] && ! remove_probe_job; then
    echo "Account V1040.4 preflight could not confirm probe Job cleanup" >&2
    status=1
  fi
  exit "$status"
}
trap cleanup EXIT

if ! job_manifest="$(jq -n \
  --arg name "$job_name" \
  --arg namespace "$namespace" --arg uid "$namespace_uid" --arg deployed "$deployed_sha" \
  --arg target "$target_sha" --arg prefix "$origin_prefix" --arg image "$image" --arg probe "$probe_source" '
    {
      apiVersion: "batch/v1", kind: "Job",
      metadata: {namespace: $namespace, name: $name, annotations: {
        "firemud.dev/probe-namespace-uid": $uid, "firemud.dev/probe-target": $target
      }},
      spec: {
        backoffLimit: 0, activeDeadlineSeconds: 70, ttlSecondsAfterFinished: 300,
        template: {spec: {
          restartPolicy: "Never", automountServiceAccountToken: false,
          securityContext: {runAsNonRoot: true, runAsUser: 999, runAsGroup: 999, seccompProfile: {type: "RuntimeDefault"}},
          containers: [{
            name: "probe", image: $image, imagePullPolicy: "IfNotPresent",
            securityContext: {allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: ["ALL"]}},
            command: ["bash", "-c"],
            args: [$probe, "probe-dev-account-v1040-4", $namespace, $uid, $deployed, $target, $prefix],
            env: [
              {name: "FIREMUD_POSTGRES_USER", valueFrom: {secretKeyRef: {name: "firemud-secret", key: "FIREMUD_POSTGRES_USER"}}},
              {name: "PGPASSWORD", valueFrom: {secretKeyRef: {name: "firemud-secret", key: "FIREMUD_POSTGRES_PASSWORD"}}}
            ]
          }]
        }}
      }
    }
  ')"; then
  fail "the protected probe Job manifest could not be prepared"
fi

if [[ "$check_permissions" == true ]]; then
  for required in 'get jobs.batch' 'list jobs.batch' 'watch jobs.batch' 'delete jobs.batch' \
    'list pods' 'get pods --subresource=log'; do
    read -ra permission <<<"$required"
    if ! kubectl --request-timeout=10s -n "$namespace" auth can-i "${permission[@]}" >/dev/null; then
      fail "the runtime credential cannot $required before writer quiescence"
    fi
  done
  kubectl --request-timeout=10s -n "$namespace" create --dry-run=server -f - -o name \
    <<<"$job_manifest" >/dev/null || fail "the probe Job failed server dry-run before writer quiescence"
  exit 0
fi

if ! created_job="$(kubectl --request-timeout=10s -n "$namespace" create -f - -o json <<<"$job_manifest")"; then
  # An ambiguous create is never sufficient for Helm. Clean up only if a
  # fresh read identifies the owned Job; its deadline and TTL are the fallback.
  if verify_namespace; then
    job_uid="$(kubectl --request-timeout=10s -n "$namespace" get job "$job_name" --ignore-not-found -o json |
      read_owned_job_uid)" || job_uid=""
  fi
  fail "the protected probe Job could not be confirmed"
fi
job_uid="$(read_owned_job_uid <<<"$created_job")" || fail "Kubernetes did not return the owned probe Job UID"

if ! kubectl --request-timeout=10s -n "$namespace" wait --for=condition=complete --timeout=75s "job/$job_name" >/dev/null; then
  probe_error="$(kubectl --request-timeout=10s -n "$namespace" logs "job/$job_name" --tail=1 2>/dev/null || true)"
  case "$probe_error" in
    'Account V1040.4 probe found duplicate retained admission attempts. Account-owned disposition is required.'|\
    'Account V1040.4 probe refused an unverified database state (unverified-account-history).'|\
    'Account V1040.4 probe refused an unverified database state (unverified-account-schema).'|\
    'Account V1040.4 probe refused an unverified database state (unverified-origin-relation).')
      fail "$probe_error"
      ;;
    *)
      fail "the read-only PostgreSQL probe did not complete"
      ;;
  esac
fi
observed_job="$(kubectl --request-timeout=10s -n "$namespace" get job "$job_name" -o json)" ||
  fail "the completed probe Job is unavailable"
[[ "$(read_owned_job_uid <<<"$observed_job")" == "$job_uid" ]] || fail "the probe Job was replaced"
receipt="$(kubectl --request-timeout=10s -n "$namespace" logs "job/$job_name")" ||
  fail "the probe receipt is unavailable"
outcome=retained-no-duplicates
if [[ "$origin_prefix" == 0 ]]; then
  outcome=first-origin-migration
fi
expected="account-v1040-4-probe=$outcome namespace=$namespace namespace_uid=$namespace_uid deployed_sha=$deployed_sha target_sha=$target_sha schema=account_service applied_origin_migrations=$origin_prefix checksum_comparison=verified-v1040-prefix"
[[ "$receipt" == "$expected" ]] || fail "the probe receipt does not match the held deployment comparison"
remove_probe_job || fail "the probe Job cleanup could not be confirmed"
verify_held_source
printf '%s\n' "$receipt"
