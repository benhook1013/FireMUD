#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
deployment_rbac="$repo_root/k8s/trust-bootstrap/deployment-rbac.yaml"
deployment_admission="$repo_root/k8s/trust-bootstrap/deployment-admission.yaml"

python3 - "$repo_root" "$deployment_rbac" "$deployment_admission" <<'PY'
import sys
import copy
import re
import json
import subprocess
from unittest.mock import patch
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
scale_rules = [
    rule
    for rule in rules(runtime_role)
    if "deployments/scale" in (rule.get("resources") or [])
]
assert len(scale_rules) == 1, "runtime role must have one scoped Deployment scale rule"
assert scale_rules[0].get("apiGroups") == ["apps"]
assert scale_rules[0].get("resources") == ["deployments/scale"]
assert set(scale_rules[0].get("resourceNames") or []) == {
    "account-service",
    "game-session-service",
    "automation-scripting-service",
    "game-design-service",
}
assert set(scale_rules[0].get("verbs") or []) == {"get", "update", "patch"}
endpoint_slice_rules = [
    rule
    for rule in rules(runtime_role)
    if "endpointslices" in (rule.get("resources") or [])
]
assert len(endpoint_slice_rules) == 1, "runtime role must have one EndpointSlice observation rule"
assert endpoint_slice_rules[0].get("apiGroups") == ["discovery.k8s.io"]
assert endpoint_slice_rules[0].get("resources") == ["endpointslices"]
assert set(endpoint_slice_rules[0].get("verbs") or []) == {"list"}
assert "resourceNames" not in endpoint_slice_rules[0], (
    "EndpointSlice list access must remain read-only"
)

cert_rules = [rule for rule in rules(cert_role) if "cert-manager.io" in (rule.get("apiGroups") or [])]
assert len(cert_rules) == 1
assert cert_rules[0].get("resources") == ["certificates"]
assert set(cert_rules[0].get("verbs") or []) == {
    "get", "watch", "create", "update", "patch"
}
assert not any("secrets" in (rule.get("resources") or []) for rule in rules(cert_role))

manager_resources = {
    resource
    for rule in rules(manager_role)
    for resource in rule.get("resources", [])
}
assert manager_resources <= {
    "namespaces", "rolebindings", "clusterroles",
    "validatingadmissionpolicies", "validatingadmissionpolicybindings",
}
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
    "firemud-trust-runtime-pod-boundary",
    "firemud-trust-runtime-pod-identity",
}
policies = {item["metadata"]["name"]: item for item in admission if item["kind"] == "ValidatingAdmissionPolicy"}
policy_bindings = {item["metadata"]["name"]: item for item in admission if item["kind"] == "ValidatingAdmissionPolicyBinding"}
assert set(policies) == policy_names
assert set(policy_bindings) == policy_names
for name in policy_names:
    assert policies[name]["spec"]["failurePolicy"] == "Fail"
    assert policy_bindings[name]["spec"]["policyName"] == name
    assert policy_bindings[name]["spec"]["validationActions"] == ["Deny"]
    for resource in (policies[name], policy_bindings[name]):
        assert resource["metadata"]["annotations"]["firemud.dev/admission-revision"] == "hosted-pod-identity-v1"

admission_reads = [
    rule for rule in rules(manager_role)
    if "admissionregistration.k8s.io" in (rule.get("apiGroups") or [])
]
assert admission_reads == [{
    "apiGroups": ["admissionregistration.k8s.io"],
    "resources": ["validatingadmissionpolicies", "validatingadmissionpolicybindings"],
    "resourceNames": [
        "firemud-trust-runtime-namespace-boundary",
        "firemud-trust-runtime-binding-boundary",
        "firemud-trust-runtime-pod-boundary",
        "firemud-trust-runtime-pod-identity",
    ],
    "verbs": ["get"],
}]
for role in (runtime_role, cert_role):
    assert not any("admissionregistration.k8s.io" in rule.get("apiGroups", []) for rule in rules(role))

# These assertions cover routing and security-critical syntax, not execution
# of native CEL. Server-side allow/deny and post-webhook proof remain required.
for name in ("firemud-trust-runtime-pod-boundary", "firemud-trust-runtime-pod-identity"):
    spec = policies[name]["spec"]
    assert spec["matchConstraints"] == {
        "matchPolicy": "Equivalent",
        "resourceRules": [{
            "apiGroups": [""], "apiVersions": ["v1"],
            "operations": ["CREATE", "UPDATE"],
            "resources": ["pods", "pods/ephemeralcontainers"],
            "scope": "Namespaced",
        }],
    }
    assert spec["matchConditions"] == [{
        "name": "hosted-runtime-namespace",
        "expression": "request.namespace == 'dev' || request.namespace.matches('^pr-[1-9][0-9]{0,50}$')",
    }]
    assert "objectSelector" not in spec["matchConstraints"]
    assert "namespaceSelector" not in spec["matchConstraints"]

escape_spec = str(policies["firemud-trust-runtime-pod-boundary"]["spec"])
for needle in (
    "hostNetwork", "hostPID", "hostIPC", "hostPath", "sysctls",
    "hostPort", "allowPrivilegeEscalation", "privileged", "procMount",
    "hostProcess", "capabilities.drop", "capabilities.add", "ephemeralContainers",
):
    assert needle in escape_spec, f"missing all-Pod escape restriction: {needle}"
identity_spec = str(policies["firemud-trust-runtime-pod-identity"]["spec"])
for needle in (
    "[oldObject]", "p.metadata.labels", "p.metadata.name", "p.metadata.generateName",
    "p.spec.serviceAccountName", "p.spec.containers.exists", "account-service", "game-session-service",
    "containers.size() == 1", "initContainers.size() == 0", "ephemeralContainers.size() == 0",
    "annotations.size() == 0", "runAsNonRoot", "runAsUser > 0", "RuntimeDefault",
    "/var/run/secrets/firemud/pod-identity", "v.name == 'pod-identity'", "v.downwardAPI",
    "defaultMode == 292", "items.size() == 1", "path == 'uid'", "fieldPath == 'metadata.uid'",
    "m.readOnly", "!has(m.subPath)", "!has(m.subPathExpr)", "mountPropagation == 'None'",
    "!m.mountPath.endsWith('/')", "segment in ['.', '..']",
    "!variables.identityPath.startsWith(m.mountPath + '/')",
    "!m.mountPath.startsWith(variables.identityPath + '/')",
):
    assert needle in identity_spec, f"missing protected UID restriction: {needle}"

# Exercise the actual embedded readback gate against canonical and tampered
# API responses. No Kubernetes process or cluster mutation is involved.
bind_script = (root / "dev-tools/hosted/trust-bootstrap/bind-runtime-roles.sh").read_text(encoding="utf-8")
readback_gate = bind_script.split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
assert bind_script.index("<<'PY'") < bind_script.index('namespace_json="$(kubectl')
assert bind_script.index("\nPY\n") < bind_script.index("binding_document()")
canonical = {(item["kind"], item["metadata"]["name"]): item for item in admission}


def readback(responses):
    calls = []

    def fake_get(args, **kwargs):
        assert args[:2] == ["kubectl", "get"]
        assert args[4:] == ["-o", "json"]
        key = (args[2].split(".", 1)[0], args[3])
        calls.append(key)
        if key not in responses:
            raise subprocess.CalledProcessError(1, args)
        return json.dumps(responses[key])

    with patch.object(sys, "argv", ["readback", str(admission_path)]), patch.object(subprocess, "check_output", fake_get):
        exec(compile(readback_gate, "bind-runtime-roles:readback", "exec"), {})
    assert set(calls) == set(canonical)


readback(canonical)
defaulted = copy.deepcopy(canonical)
for (kind, name), item in defaulted.items():
    match_key = "matchConstraints" if kind == "ValidatingAdmissionPolicy" else "matchResources"
    match = item["spec"].setdefault(match_key, {})
    match.setdefault("matchPolicy", "Equivalent")
    match.setdefault("namespaceSelector", {})
    match.setdefault("objectSelector", {})
readback(defaulted)
for key in canonical:
    for mutation in ("missing", "revision", "spec", "terminating", "type-warning", "unknown-field"):
        if mutation == "type-warning" and key[0] != "ValidatingAdmissionPolicy":
            continue
        changed = copy.deepcopy(canonical)
        if mutation == "missing":
            del changed[key]
        elif mutation == "revision":
            changed[key]["metadata"]["annotations"]["firemud.dev/admission-revision"] = "stale"
        elif mutation == "spec":
            # Keep Fail/Deny and the same name while dropping actual enforcement.
            if key[0] == "ValidatingAdmissionPolicy":
                changed[key]["spec"]["validations"][0]["expression"] = "true"
            else:
                changed[key]["spec"]["matchResources"] = {"namespaceSelector": {"matchLabels": {"bypass": "true"}}}
        elif mutation == "terminating":
            changed[key]["metadata"]["deletionTimestamp"] = "2026-10-09T00:00:00Z"
        elif mutation == "unknown-field":
            changed[key]["spec"]["unexpected"] = True
        else:
            changed[key]["status"] = {"typeChecking": {"expressionWarnings": [{"warning": "invalid CEL"}]}}
        try:
            readback(changed)
        except (SystemExit, subprocess.CalledProcessError):
            pass
        else:
            raise AssertionError(f"readback accepted {mutation}: {key}")
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

binding_policy = policies["firemud-trust-runtime-binding-boundary"]
gc_delete_clause = " ".join("""
    (request.userInfo.username == 'system:serviceaccount:kube-system:namespace-controller' &&
     request.operation == 'DELETE' &&
     (request.namespace == 'dev' || request.namespace.matches('^pr-[1-9][0-9]{0,50}$')) &&
     oldObject.metadata.name in ['firemud-preview-runtime', 'firemud-standalone-certificate-writer'])
""".split())


def check_binding_delete_contract(policy):
    match = " ".join(policy["spec"]["matchConditions"][0]["expression"].split())
    authorization = " ".join(policy["spec"]["validations"][0]["expression"].split())
    # Name independence is structural proof of named/unnamed request parity;
    # these fixtures do not execute Kubernetes CEL or establish live GC proof.
    assert not re.search(r"\brequest\.name\b", match + authorization)
    assert "(request.operation == 'DELETE' ? oldObject.metadata.name in " in match
    assert gc_delete_clause in authorization
    assert "(request.operation == 'DELETE' ? oldObject.metadata.name in " in authorization


check_binding_delete_contract(binding_policy)
assert "request.operation == 'DELETE' ? oldObject.metadata.name : object.metadata.name" in namespace_expression
for old, new in (
    ("oldObject.metadata.name", "request.name"),
    ("system:serviceaccount:kube-system:namespace-controller", "system:serviceaccount:untrusted:controller"),
    ("request.namespace == 'dev'", "request.namespace == 'kube-system'"),
    ("'firemud-preview-runtime'", "'unexpected-binding'"),
):
    mutation = copy.deepcopy(binding_policy)
    mutation["spec"]["validations"][0]["expression"] = mutation["spec"]["validations"][0]["expression"].replace(old, new)
    try:
        check_binding_delete_contract(mutation)
    except AssertionError:
        pass
    else:
        raise AssertionError(f"RoleBinding DELETE contract accepted mutation: {old}")

print("trust-bootstrap RBAC contract: PASS")
PY
