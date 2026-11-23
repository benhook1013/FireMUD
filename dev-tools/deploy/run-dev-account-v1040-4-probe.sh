#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "usage: $0 <namespace> <namespace-uid> <deployed-sha> <target-sha> <applied-origin-migrations:0..3>" >&2
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

verify_held_source
image="$(kubectl --request-timeout=10s -n "$namespace" get deployment postgres -o json |
  jq -er --arg namespace "$namespace" '
    select(.kind == "Deployment" and .metadata.name == "postgres" and .metadata.namespace == $namespace)
    | .spec.template.spec.containers | map(select(.name == "postgres"))
    | select(length == 1) | .[0].image | select(type == "string")')" ||
  fail "the retained PostgreSQL image is unavailable"
[[ "$image" =~ ^postgres:[0-9][a-zA-Z0-9._-]*@sha256:[0-9a-f]{64}$ ]] ||
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
  local uid="$job_uid"
  job_uid=""
  jq -n --arg uid "$uid" \
    '{apiVersion:"v1",kind:"DeleteOptions",preconditions:{uid:$uid},propagationPolicy:"Foreground"}' |
    kubectl --request-timeout=10s delete --raw "/apis/batch/v1/namespaces/$namespace/jobs/$job_name" -f - >/dev/null &&
    kubectl -n "$namespace" wait --for=delete --timeout=30s "job/$job_name" >/dev/null
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

if ! created_job="$(jq -n \
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
  ' | kubectl --request-timeout=10s -n "$namespace" create -f - -o json)"; then
  # An ambiguous create is never sufficient for Helm. Clean up only if a
  # fresh read identifies the owned Job; its deadline and TTL are the fallback.
  if verify_namespace; then
    job_uid="$(kubectl --request-timeout=10s -n "$namespace" get job "$job_name" --ignore-not-found -o json |
      read_owned_job_uid)" || job_uid=""
  fi
  fail "the protected probe Job could not be confirmed"
fi
job_uid="$(read_owned_job_uid <<<"$created_job")" || fail "Kubernetes did not return the owned probe Job UID"

if ! kubectl -n "$namespace" wait --for=condition=complete --timeout=75s "job/$job_name" >/dev/null; then
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
