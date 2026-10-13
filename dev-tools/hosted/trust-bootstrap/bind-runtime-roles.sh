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
  echo "KUBECONFIG must name a readable kubeconfig: the first proof namespace may use an explicit system:masters kubeconfig; later runs use the trusted namespace-manager credential" >&2
  exit 2
fi

# The trusted checkout is the contract authority. Read every complete policy
# and binding before granting either runtime role; names or Fail/Deny alone
# cannot establish that the protected Pod boundary is installed.
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
python3 - "$repo_root/k8s/trust-bootstrap/deployment-admission.yaml" <<'PY'
import json
import copy
import subprocess
import sys

import yaml

with open(sys.argv[1], encoding="utf-8") as stream:
    required = [item for item in yaml.safe_load_all(stream) if item]

revision_key = "firemud.dev/admission-revision"
expected_names = {
    "firemud-trust-runtime-namespace-boundary",
    "firemud-trust-runtime-binding-boundary",
    "firemud-trust-runtime-pod-boundary",
    "firemud-trust-runtime-pod-identity",
}
expected_resources = {
    (kind, name)
    for kind in ("ValidatingAdmissionPolicy", "ValidatingAdmissionPolicyBinding")
    for name in expected_names
}
if {(item["kind"], item["metadata"]["name"]) for item in required} != expected_resources or len(required) != 8:
    raise SystemExit("trusted deployment admission manifest does not contain the exact required policy/binding set")


def canonical_spec(kind, spec):
    if not isinstance(spec, dict):
        return spec
    result = copy.deepcopy(spec)
    # MatchResources defaults empty selectors to all objects/namespaces and
    # matchPolicy to Equivalent. An omitted binding matchResources matches all.
    # Normalize only those API defaults; all other fields remain exact.
    match_key = "matchConstraints" if kind == "ValidatingAdmissionPolicy" else "matchResources"
    match = result.setdefault(match_key, {})
    if isinstance(match, dict):
        match.setdefault("namespaceSelector", {})
        match.setdefault("objectSelector", {})
        match.setdefault("matchPolicy", "Equivalent")
    return result


for expected in required:
    kind, name = expected["kind"], expected["metadata"]["name"]
    actual = json.loads(subprocess.check_output(
        ["kubectl", "get", f"{kind}.admissionregistration.k8s.io", name, "-o", "json"],
        text=True,
    ))
    if (
        actual.get("apiVersion") != expected["apiVersion"]
        or actual.get("kind") != kind
        or actual.get("metadata", {}).get("name") != name
        or actual.get("metadata", {}).get("deletionTimestamp") is not None
        or expected["metadata"].get("annotations", {}).get(revision_key) != "hosted-pod-identity-v1"
        or actual.get("metadata", {}).get("annotations", {}).get(revision_key) != "hosted-pod-identity-v1"
        or canonical_spec(kind, actual.get("spec")) != canonical_spec(kind, expected["spec"])
    ):
        raise SystemExit(f"required admission {kind}/{name} differs from the trusted content/revision; refusing runtime role grants")
    if kind == "ValidatingAdmissionPolicy" and actual.get("status", {}).get("typeChecking", {}).get("expressionWarnings"):
        raise SystemExit(f"required admission {name} has CEL type-check warnings; refusing runtime role grants")
PY

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
