#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly REPO_ROOT
readonly MANIFEST="$REPO_ROOT/k8s/trust-bootstrap/issuance-admission.yaml"

if [[ ! -f "$MANIFEST" ]]; then
  echo "missing trust-bootstrap issuance admission manifest: $MANIFEST" >&2
  exit 1
fi

python3 - "$MANIFEST" <<'PY'
from __future__ import annotations

import copy
import sys
from pathlib import Path

import yaml


manifest_path = Path(sys.argv[1])
documents = [
    document
    for document in yaml.safe_load_all(manifest_path.read_text(encoding="utf-8"))
    if document is not None
]


def fail(message: str) -> None:
    raise AssertionError(message)


def policies(items: list[dict]) -> dict[str, dict]:
    return {
        item["metadata"]["name"]: item
        for item in items
        if item.get("kind") == "ValidatingAdmissionPolicy"
    }


def bindings(items: list[dict]) -> dict[str, dict]:
    return {
        item["metadata"]["name"]: item
        for item in items
        if item.get("kind") == "ValidatingAdmissionPolicyBinding"
    }


def expressions(policy: dict) -> str:
    spec = policy["spec"]
    parts: list[str] = []
    parts.extend(condition["expression"] for condition in spec.get("matchConditions", []))
    parts.extend(validation["expression"] for validation in spec["validations"])
    return "\n".join(parts)


def require(text: str, needle: str, context: str) -> None:
    if needle not in text:
        fail(f"{context} is missing required contract: {needle}")


def check_contract(items: list[dict]) -> None:
    expected_policy_names = {
        "firemud-trust-bootstrap-certificaterequest",
        "firemud-trust-bootstrap-certificaterequest-subresources",
        "firemud-trust-bootstrap-certificate",
        "firemud-trust-bootstrap-certificate-status",
        "firemud-trust-bootstrap-ca-issuers",
    }
    expected_binding_names = set(expected_policy_names)
    actual_policies = policies(items)
    actual_bindings = bindings(items)
    if set(actual_policies) != expected_policy_names:
        fail(f"unexpected trust-bootstrap policies: {sorted(actual_policies)}")
    if set(actual_bindings) != expected_binding_names:
        fail(f"unexpected trust-bootstrap bindings: {sorted(actual_bindings)}")
    if len(items) != len(expected_policy_names) * 2:
        fail("manifest must contain exactly one binding for every trust-bootstrap policy")

    for name, policy in actual_policies.items():
        if policy["apiVersion"] != "admissionregistration.k8s.io/v1":
            fail(f"{name} is not an admissionregistration.k8s.io/v1 policy")
        if policy["spec"].get("failurePolicy") != "Fail":
            fail(f"{name} is not fail-closed")
        if not policy["spec"].get("matchConditions"):
            fail(f"{name} has no scoped match condition")
        if not policy["spec"].get("validations"):
            fail(f"{name} has no validation")

    for name, binding in actual_bindings.items():
        if binding["spec"].get("policyName") != name:
            fail(f"{name} does not bind its same-named policy")
        if binding["spec"].get("validationActions") != ["Deny"]:
            fail(f"{name} is not Deny-only")

    request = expressions(actual_policies["firemud-trust-bootstrap-certificaterequest"])
    request_match = actual_policies["firemud-trust-bootstrap-certificaterequest"]["spec"][
        "matchConditions"
    ][0]["expression"]
    for needle in (
        "request.operation == 'CREATE' &&",
        "request.operation == 'UPDATE' &&",
        "oldObject.spec.issuerRef.name == 'firemud-ca-issuer'",
    ):
        require(request_match, needle, "CertificateRequest match condition")
    for needle in (
        "firemud-ca-issuer",
        "system:serviceaccount:cert-manager:cert-manager",
        "system:masters",
        "object.spec.issuerRef.kind == 'ClusterIssuer'",
        "object.spec.issuerRef.group == 'cert-manager.io'",
        "owner.kind == 'Certificate'",
        "owner.controller == true",
        "owner.blockOwnerDeletion == true",
        "object.spec == oldObject.spec",
    ):
        require(request, needle, "CertificateRequest boundary")
    owner_validation = actual_policies["firemud-trust-bootstrap-certificaterequest"]["spec"][
        "validations"
    ][1]["expression"].rstrip()
    if not owner_validation.endswith(")))"):
        fail("CertificateRequest owner validation is missing its enclosing CEL parenthesis")

    request_subresources = expressions(
        actual_policies["firemud-trust-bootstrap-certificaterequest-subresources"]
    )
    for needle in (
        "system:serviceaccount:cert-manager:cert-manager",
        "object.spec == oldObject.spec",
        "owner.kind == 'Certificate'",
    ):
        require(request_subresources, needle, "CertificateRequest subresource boundary")
    subresource_rules = actual_policies["firemud-trust-bootstrap-certificaterequest-subresources"][
        "spec"
    ]["matchConstraints"]["resourceRules"]
    if set(subresource_rules[0]["resources"]) != {
        "certificaterequests/status",
        "certificaterequests/approval",
    }:
        fail("CertificateRequest approval/status boundary must cover both subresources")

    certificate = expressions(actual_policies["firemud-trust-bootstrap-certificate"])
    certificate_validation = actual_policies["firemud-trust-bootstrap-certificate"]["spec"][
        "validations"
    ][0]["expression"]
    certificate_match = actual_policies["firemud-trust-bootstrap-certificate"]["spec"][
        "matchConditions"
    ][0]["expression"]
    for needle in (
        "system:serviceaccount:firemud-system:firemud-standalone-certificate-writer",
        "request.operation == 'CREATE' &&",
        "request.operation == 'UPDATE' &&",
        "oldObject.spec.issuerRef.name == 'firemud-ca-issuer'",
        "pr-[1-9][0-9]{0,50}-(telnet-tls|gateway-internal-ws|tcp-proxy-bridge)",
    ):
        require(certificate_match, needle, "Certificate match condition")
    for needle in (
        "system:serviceaccount:firemud-system:firemud-standalone-certificate-writer",
        "^pr-[1-9][0-9]{0,50}$",
        "spring-cloud-gateway-mtls.",
        "spiffe://firemud/ns/",
        "object.spec.issuerRef.kind == 'ClusterIssuer'",
        "object.spec.issuerRef.group == 'cert-manager.io'",
        "letsencrypt-prod",
        "request.namespace + '.preview.firedevops.net'",
        "object.spec.privateKey.algorithm == 'RSA'",
        "object.spec.privateKey.size == 2048",
        "object.spec.privateKey.encoding == 'PKCS8'",
        "object.spec.privateKey.rotationPolicy == 'Always'",
        "object.spec.usages == ['digital signature', 'key encipherment', 'server auth']",
        "object.spec.usages == ['digital signature', 'key encipherment', 'client auth']",
        "firemud-hosted-identity-controller",
        "system:serviceaccount:kube-system:namespace-controller",
    ):
        require(certificate, needle, "Certificate boundary")
    require(
        certificate_validation,
        "^(dev|pr-[1-9][0-9]{0,50})-(telnet-tls|gateway-internal-ws|tcp-proxy-bridge|grpc-(game-design-service|world-management-service|entity-management-service|game-logic-service|automation-scripting-service))$",
        "standalone Certificate validation",
    )
    require(
        certificate_validation,
        "system:serviceaccount:firemud-system:firemud-standalone-certificate-writer",
        "standalone Certificate validation",
    )

    issuer = expressions(actual_policies["firemud-trust-bootstrap-ca-issuers"])
    issuer_match = actual_policies["firemud-trust-bootstrap-ca-issuers"]["spec"][
        "matchConditions"
    ][0]["expression"]
    for needle in (
        "request.operation == 'CREATE' &&",
        "request.operation == 'UPDATE' &&",
        "oldObject.spec.ca",
    ):
        require(issuer_match, needle, "CA issuer match condition")
    for needle in (
        "firemud-ca-issuer",
        "has(object.spec.ca)",
        "has(oldObject.spec.ca)",
        "system:masters",
    ):
        require(issuer, needle, "CA issuer boundary")
    issuer_rules = actual_policies["firemud-trust-bootstrap-ca-issuers"]["spec"][
        "matchConstraints"
    ]["resourceRules"]
    issuer_resources = set(issuer_rules[0]["resources"])
    if issuer_resources != {"issuers", "clusterissuers"}:
        fail(f"CA issuer boundary has unexpected resources: {sorted(issuer_resources)}")
    if issuer_rules[0].get("scope") != "*":
        fail("CA issuer boundary must cover both namespaced and cluster issuers")

check_contract(documents)

# These mutations model the dangerous regressions this contract is meant to
# catch. Every mutation goes through the same production checker as the
# original manifest, so the negative fixtures cannot drift into a weaker copy
# of the contract.
mutation = copy.deepcopy(documents)
mutation_policy = next(
    item
    for item in mutation
    if item.get("metadata", {}).get("name") == "firemud-trust-bootstrap-certificaterequest"
)
mutation_policy["spec"]["validations"][1]["expression"] = mutation_policy["spec"][
    "validations"
][1]["expression"].replace("object.spec.issuerRef.kind == 'ClusterIssuer' &&", "")
try:
    check_contract(mutation)
except AssertionError:
    pass
else:
    fail("negative mutation removing the exact ClusterIssuer check was accepted")

mutation = copy.deepcopy(documents)
mutation_policy = next(
    item
    for item in mutation
    if item.get("metadata", {}).get("name") == "firemud-trust-bootstrap-certificate"
)
mutation_policy["spec"]["validations"][0]["expression"] = mutation_policy["spec"][
    "validations"
][0]["expression"].replace(
    "system:serviceaccount:firemud-system:firemud-standalone-certificate-writer",
    "system:serviceaccount:untrusted",
)
try:
    check_contract(mutation)
except AssertionError:
    pass
else:
    fail("negative mutation removing the standalone writer identity was accepted")

mutation = copy.deepcopy(documents)
mutation_binding = next(
    item
    for item in mutation
    if item.get("kind") == "ValidatingAdmissionPolicyBinding"
    and item.get("metadata", {}).get("name") == "firemud-trust-bootstrap-ca-issuers"
)
mutation_binding["spec"]["validationActions"] = ["Warn"]
try:
    check_contract(mutation)
except AssertionError:
    pass
else:
    fail("negative mutation changing the CA issuer binding from Deny was accepted")

print(f"trust-bootstrap issuance contract: {len(documents)} documents, fail-closed bindings verified")
PY
