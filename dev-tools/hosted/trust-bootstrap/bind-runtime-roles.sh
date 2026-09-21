#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <dev|pr-N-namespace>" >&2
  exit 2
fi

namespace="$1"
if [[ "$namespace" != dev && ! "$namespace" =~ ^pr-[1-9][0-9]{0,50}$ ]]; then
  echo "runtime namespace must be dev or pr-[1-9][0-9]{0,50}: ${namespace}" >&2
  exit 2
fi
if [[ -z "${KUBECONFIG:-}" || ! -r "$KUBECONFIG" ]]; then
  echo "KUBECONFIG must name the trusted namespace-manager kubeconfig" >&2
  exit 2
fi

namespace_json="$(kubectl get namespace "$namespace" -o json)"
if [[ "$namespace" == dev ]]; then
  jq -e '
    .metadata.name == "dev" and
    (.metadata.labels // {})["firemud.dev/dev-demo"] == "true" and
    (.metadata.labels // {})["firemud.dev/environment-class"] == "dev-demo-cluster"
  ' <<<"$namespace_json" >/dev/null || {
    echo "runtime namespace ${namespace} is not the canonical dev-demo target" >&2
    exit 1
  }
else
  expected_pr_number="${namespace#pr-}"
  jq -e --arg expected_pr_number "$expected_pr_number" '
    .metadata.name == ($expected_pr_number | "pr-" + .) and
    (.metadata.labels // {})["firemud.dev/preview"] == "true" and
    (.metadata.labels // {})["firemud.dev/pr-number"] == $expected_pr_number
  ' <<<"$namespace_json" >/dev/null || {
    echo "runtime namespace ${namespace} is not the canonical preview target" >&2
    exit 1
  }
fi

binding_document() {
  local role_name="$1"
  jq -n \
    --arg namespace "$namespace" \
    --arg role_name "$role_name" \
    '{
      apiVersion: "rbac.authorization.k8s.io/v1",
      kind: "RoleBinding",
      metadata: {
        name: $role_name,
        namespace: $namespace,
        labels: {
          "app.kubernetes.io/name": "firemud-trust-bootstrap",
          "app.kubernetes.io/managed-by": "trusted-namespace-manager"
        }
      },
      roleRef: {
        apiGroup: "rbac.authorization.k8s.io",
        kind: "ClusterRole",
        name: $role_name
      },
      subjects: [{
        kind: "ServiceAccount",
        name: $role_name,
        namespace: "firemud-system"
      }]
    }'
}

validate_existing_binding() {
  local role_name="$1"
  local binding_json="$2"
  jq -e \
    --arg namespace "$namespace" \
    --arg role_name "$role_name" \
    '
      .apiVersion == "rbac.authorization.k8s.io/v1" and
      .kind == "RoleBinding" and
      .metadata.name == $role_name and
      .metadata.namespace == $namespace and
      .roleRef == {
        apiGroup: "rbac.authorization.k8s.io",
        kind: "ClusterRole",
        name: $role_name
      } and
      .subjects == [{
        kind: "ServiceAccount",
        name: $role_name,
        namespace: "firemud-system"
      }]
    ' <<<"$binding_json" >/dev/null || {
      echo "RoleBinding ${namespace}/${role_name} exists with an unexpected role or subject; refusing to overwrite it" >&2
      exit 1
    }
}

for role_name in firemud-preview-runtime firemud-standalone-certificate-writer; do
  binding_json="$(kubectl -n "$namespace" get rolebinding "$role_name" --ignore-not-found -o json)"
  if [[ -n "$binding_json" ]]; then
    validate_existing_binding "$role_name" "$binding_json"
    continue
  fi
  binding_document "$role_name" |
    kubectl -n "$namespace" apply --server-side \
      --field-manager=firemud-trusted-namespace-manager -f - >/dev/null
  binding_json="$(kubectl -n "$namespace" get rolebinding "$role_name" -o json)"
  validate_existing_binding "$role_name" "$binding_json"
done

printf 'namespace=%s\nruntimeRoleBinding=ready\ncertificateRoleBinding=ready\n' "$namespace"
