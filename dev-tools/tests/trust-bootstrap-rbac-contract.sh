#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
deployment_rbac="$repo_root/k8s/trust-bootstrap/deployment-rbac.yaml"
deployment_admission="$repo_root/k8s/trust-bootstrap/deployment-admission.yaml"

python3 - "$repo_root" "$deployment_rbac" "$deployment_admission" <<'PY'
import sys
from pathlib import Path

import yaml

root, deployment_path, admission_path = map(Path, sys.argv[1:])


def documents(path):
    with path.open(encoding="utf-8") as stream:
        return [item for item in yaml.safe_load_all(stream) if item]


def identity(item):
    metadata = item.get("metadata") or {}
    return item.get("kind"), metadata.get("name")


def rules(item):
    return item.get("rules") or []


deployment = documents(deployment_path)
assert not (root / "k8s/preview/preview-deployer-rbac.yaml").exists(), (
    "legacy preview-deployer manifest must not be reapplied"
)
preview_kustomization = documents(root / "k8s/preview/kustomization.yaml")[0]
assert "preview-deployer-rbac.yaml" not in preview_kustomization["resources"]

expected_service_accounts = {
    "firemud-preview-runtime",
    "firemud-standalone-certificate-writer",
    "firemud-preview-namespace-manager",
}
service_accounts = {
    (item.get("metadata") or {}).get("name")
    for item in deployment
    if item.get("kind") == "ServiceAccount"
}
assert expected_service_accounts <= service_accounts
for item in deployment:
    if item.get("kind") == "ServiceAccount":
        assert item.get("automountServiceAccountToken") is False

roles = {
    (item.get("metadata") or {}).get("name"): item
    for item in deployment
    if item.get("kind") == "ClusterRole"
}
runtime_role = roles["firemud-preview-runtime"]
cert_role = roles["firemud-standalone-certificate-writer"]
manager_role = roles["firemud-preview-namespace-manager"]

for role_name, role in roles.items():
    for rule in rules(role):
        api_groups = set(rule.get("apiGroups") or [])
        resources = set(rule.get("resources") or [])
        assert not ("certificaterequests" in resources), f"{role_name} can mutate CertificateRequests"
        assert not ("issuers" in resources or "clusterissuers" in resources), f"{role_name} can mutate issuers"
        if role_name == "firemud-standalone-certificate-writer":
            assert not (api_groups == {""} and "secrets" in resources), (
                f"{role_name} has Secret access"
            )
        elif role_name == "firemud-preview-runtime":
            if "secrets" in resources:
                assert api_groups == {""}
        else:
            assert not (api_groups == {""} and "secrets" in resources), (
                f"{role_name} has unexpected Secret access"
            )

runtime_resources = {
    resource
    for rule in rules(runtime_role)
    for resource in rule.get("resources", [])
}
assert "secrets" in runtime_resources, "runtime deployment cannot create/update namespace-local Secrets"
assert "namespaces" not in runtime_resources, "runtime role must not manage namespaces"

cert_rules = [rule for rule in rules(cert_role) if "cert-manager.io" in (rule.get("apiGroups") or [])]
assert len(cert_rules) == 1
assert cert_rules[0].get("resources") == ["certificates"]
assert set(cert_rules[0].get("verbs") or []) == {
    "get", "watch", "create", "update", "patch", "delete"
}
assert not any("secrets" in (rule.get("resources") or []) for rule in rules(cert_role))

manager_resources = {
    resource
    for rule in rules(manager_role)
    for resource in rule.get("resources", [])
}
assert manager_resources <= {"namespaces", "rolebindings", "clusterroles"}
assert "secrets" not in manager_resources
assert "certificaterequests" not in manager_resources
assert "issuers" not in manager_resources
assert "clusterissuers" not in manager_resources

bindings = [item for item in deployment if item.get("kind") == "ClusterRoleBinding"]
binding_names = {
    (item.get("metadata") or {}).get("name") for item in bindings
}
assert binding_names == {"firemud-preview-namespace-manager"}, (
    "runtime and certificate-writer roles must have no ClusterRoleBinding"
)

manager_binding = bindings[0]
assert manager_binding.get("roleRef", {}).get("name") == "firemud-preview-namespace-manager"
subjects = manager_binding.get("subjects") or []
assert subjects == [{
    "kind": "ServiceAccount",
    "name": "firemud-preview-namespace-manager",
    "namespace": "firemud-system",
}]

admission = documents(admission_path)
policy_names = {
    "firemud-trust-runtime-namespace-boundary",
    "firemud-trust-runtime-binding-boundary",
}
policies = {item["metadata"]["name"]: item for item in admission if item["kind"] == "ValidatingAdmissionPolicy"}
policy_bindings = {item["metadata"]["name"]: item for item in admission if item["kind"] == "ValidatingAdmissionPolicyBinding"}
assert set(policies) == policy_names
assert set(policy_bindings) == policy_names
for name in policy_names:
    assert policies[name]["spec"]["failurePolicy"] == "Fail"
    assert policy_bindings[name]["spec"]["policyName"] == name
    assert policy_bindings[name]["spec"]["validationActions"] == ["Deny"]
namespace_expression = str(policies["firemud-trust-runtime-namespace-boundary"]["spec"])
binding_expression = str(policies["firemud-trust-runtime-binding-boundary"]["spec"])
assert "system:serviceaccount:firemud-system:firemud-preview-namespace-manager" in namespace_expression
assert "^pr-[1-9][0-9]{0,50}$" in namespace_expression
for needle in (
    "firemud-preview-runtime",
    "firemud-standalone-certificate-writer",
    "object.roleRef.name == object.metadata.name",
    "object.subjects.size() == 1",
    "oldObject.roleRef.name",
    "oldObject.subjects.exists",
    "system:serviceaccount:kube-system:namespace-controller",
):
    assert needle in binding_expression, f"missing RoleBinding guard: {needle}"

print("trust-bootstrap RBAC contract: PASS")
PY
