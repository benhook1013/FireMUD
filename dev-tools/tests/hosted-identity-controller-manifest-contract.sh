#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

ROOT_DIR="$(cd "$(dirname "$(dirname "$(dirname "${BASH_SOURCE[0]}")")")" && pwd)"
MANIFEST_DIR="$ROOT_DIR/k8s/hosted-identity-controller"
CONTROLLER_DIR="$ROOT_DIR/dev-tools/hosted/controller"
ARTIFACT_VALIDATOR="$ROOT_DIR/dev-tools/hosted/preview/validate-preview-artifact.py"
APPLICATION_CONFIG="$ROOT_DIR/services/hosted-environment-identity-controller/src/main/resources/application.yml"

fail() {
  echo "hosted identity controller manifest contract: $*" >&2
  exit 1
}

require_file() {
  [[ -f "$1" ]] || fail "missing file: $1"
}

require_literal() {
  local file="$1"
  local literal="$2"
  grep -Fq -- "$literal" "$file" || fail "missing '$literal' in $file"
}

require_regex() {
  local file="$1"
  local expression="$2"
  grep -Eq -- "$expression" "$file" || fail "missing /$expression/ in $file"
}

forbid_literal() {
  local file="$1"
  local literal="$2"
  if grep -Fq -- "$literal" "$file"; then
    fail "forbidden '$literal' found in $file"
  fi
}

forbid_regex() {
  local file="$1"
  local expression="$2"
  if grep -Eq -- "$expression" "$file"; then
    fail "forbidden /$expression/ found in $file"
  fi
}

select_named_yaml_document() {
  local file="$1"
  local kind="$2"
  local name="$3"
  local selected
  if ! selected="$(python3 - "$file" "$kind" "$name" <<'PY'
import sys
from pathlib import Path

import yaml

path = Path(sys.argv[1])
kind = sys.argv[2]
name = sys.argv[3]
matches = [
    document
    for document in yaml.safe_load_all(path.read_text(encoding="utf-8"))
    if isinstance(document, dict)
    and document.get("kind") == kind
    and isinstance(document.get("metadata"), dict)
    and document["metadata"].get("name") == name
]
if len(matches) != 1:
    raise SystemExit(
        f"expected exactly one {kind}/{name} in {path}, found {len(matches)}"
    )
print(yaml.safe_dump(matches[0], sort_keys=False), end="")
PY
  )"; then
    fail "could not select exactly one $kind/$name from $file"
  fi
  [[ -n "$selected" ]] || fail "selected $kind/$name from $file is empty"
  printf '%s\n' "$selected"
}

for file in \
  "$MANIFEST_DIR/kustomization.yaml" \
  "$MANIFEST_DIR/namespace.yaml" \
  "$MANIFEST_DIR/serviceaccounts.yaml" \
  "$MANIFEST_DIR/crd.yaml" \
  "$MANIFEST_DIR/admission.yaml" \
  "$MANIFEST_DIR/rbac.yaml" \
  "$MANIFEST_DIR/deployment.yaml" \
  "$MANIFEST_DIR/networkpolicy.yaml" \
  "$MANIFEST_DIR/README.md" \
  "$CONTROLLER_DIR/bootstrap-hosted-identity-controller.sh"; do
  require_file "$file"
done
require_file "$ARTIFACT_VALIDATOR"

KUSTOMIZATION="$MANIFEST_DIR/kustomization.yaml"
CRD="$MANIFEST_DIR/crd.yaml"
ADMISSION="$MANIFEST_DIR/admission.yaml"
RBAC="$MANIFEST_DIR/rbac.yaml"
DEPLOYMENT="$MANIFEST_DIR/deployment.yaml"
NETWORKPOLICY="$MANIFEST_DIR/networkpolicy.yaml"
BOOTSTRAP="$CONTROLLER_DIR/bootstrap-hosted-identity-controller.sh"
TRACKER="$ROOT_DIR/design/project-management/implementation-tracking/platform-operations-and-delivery.md"
PROJECTION="$ROOT_DIR/services/hosted-environment-identity-controller/src/main/java/net/firedevops/firemud/hostedidentity/kubernetes/SecretProjectionService.java"
GRPC_GENERATOR="$ROOT_DIR/services/hosted-environment-identity-controller/src/main/java/net/firedevops/firemud/hostedidentity/security/GrpcTransportBundleGenerator.java"

for resource in namespace serviceaccounts crd admission rbac deployment networkpolicy; do
  require_literal "$KUSTOMIZATION" "- $resource.yaml"
done
require_literal "$MANIFEST_DIR/namespace.yaml" "name: firemud-system"
for namespace_label in \
  "pod-security.kubernetes.io/enforce: restricted" \
  "pod-security.kubernetes.io/audit: restricted" \
  "pod-security.kubernetes.io/warn: restricted"; do
  require_literal "$MANIFEST_DIR/namespace.yaml" "$namespace_label"
done
require_literal "$MANIFEST_DIR/serviceaccounts.yaml" "name: firemud-hosted-identity-controller"
require_literal "$MANIFEST_DIR/serviceaccounts.yaml" "name: firemud-hosted-identity-requester"
for tracker_marker in \
  "Contract owners are [ADR 0182]" \
  "[Deployment Environments](../../architecture/infrastructure/deployment-environments.md)" \
  "Current implementation/proof status:" \
  "Static/live boundary:"; do
  require_literal "$TRACKER" "$tracker_marker"
done

require_literal "$CRD" "name: hostedenvironmentidentities.platform.firemud.dev"
require_literal "$CRD" "group: platform.firemud.dev"
require_literal "$CRD" "scope: Namespaced"
require_literal "$CRD" "kind: HostedEnvironmentIdentity"
require_literal "$CRD" "name: v1alpha1"
require_literal "$CRD" "served: true"
require_literal "$CRD" "storage: true"
require_literal "$CRD" "status: {}"
forbid_literal "$CRD" "additionalProperties: false"
require_literal "$CRD" "desiredState"
require_literal "$CRD" "- Active"
require_literal "$CRD" "- Retired"
require_literal "$CRD" "!has(oldSelf.desiredState)"
forbid_literal "$CRD" "self.metadata.namespace == 'firemud-system'"
forbid_literal "$CRD" "x-kubernetes-preserve-unknown-fields"
require_literal "$CRD" "self.metadata.name.matches('^(dev-demo|pr-[1-9][0-9]*)$')"
for field in observedGeneration phase conditions profile runtimeNamespaceUid requestedHeadSha deployedHeadSha ingress telnet gatewayInternalWs tcpProxyBridge grpc; do
  require_literal "$CRD" "$field"
done
require_literal "$APPLICATION_CONFIG" "dev-demo-requested-head-annotation: firemud.dev/requested-dev-demo-head-sha"
require_literal "$APPLICATION_CONFIG" "dev-demo-head-annotation: firemud.dev/last-dev-demo-head-sha"
require_literal "$APPLICATION_CONFIG" "preview-requested-head-annotation: firemud.dev/requested-preview-head-sha"
require_literal "$APPLICATION_CONFIG" "preview-deployed-head-annotation: firemud.dev/last-preview-head-sha"
require_regex "$CRD" "format: date-time"
require_regex "$CRD" 'pattern: "\^\[0-9a-fA-F\]\{40\}\$"'
CRD="$CRD" python3 - <<'PY'
import os
from pathlib import Path

import yaml

crd = yaml.safe_load(Path(os.environ["CRD"]).read_text(encoding="utf-8"))
schema = crd["spec"]["versions"][0]["schema"]["openAPIV3Schema"]
assert crd["spec"].get("preserveUnknownFields", False) is False
assert schema["properties"]["metadata"] == {"type": "object"}
assert set(schema["properties"]["spec"]["properties"]) == {"desiredState"}


def assert_structural(value, path="openAPIV3Schema"):
    if isinstance(value, dict):
        assert value.get("additionalProperties") is not False, path
        for key, child in value.items():
            assert_structural(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            assert_structural(child, f"{path}[{index}]")


assert_structural(schema)
rules = [validation["rule"] for validation in schema["x-kubernetes-validations"]]
assert rules == ["self.metadata.name.matches('^(dev-demo|pr-[1-9][0-9]*)$')"]
PY

spec_block="$(sed -n '/^            spec:/,/^            status:/p' "$CRD")"
for forbidden_spec_field in hostname namespace secret issuer port key certificate consumer; do
  if grep -Eq "^[[:space:]]{16}${forbidden_spec_field}" <<<"$spec_block"; then
    fail "sensitive field $forbidden_spec_field leaked into CR spec"
  fi
done
[[ "$(grep -Ec '^                [A-Za-z][A-Za-z0-9]*:' <<<"$spec_block")" == "1" ]] || \
  fail "CR spec is not limited to desiredState"

for text_value in \
  firemud-hosted-identity-requester \
  firemud-hosted-identity-controller \
  "request.namespace == 'firemud-system'" \
  request.subResource \
  hostedenvironmentidentities/status \
  oldObject.spec.desiredState \
  "oldObject.status.phase == 'Retired'" \
  ownerReferences \
  "startsWith('firemud.dev/')" \
  secrets \
  cert-manager.io \
  cert-manager:cert-manager \
  "object.spec.secretName == object.metadata.name" \
  "rotationPolicy == 'Always'" \
  firemud-grpc-tls-previous \
  "firemud.dev/role" \
  "firemud.dev/retention" \
  firemud-hosted-identity-scope-roles \
  firemud-hosted-identity-scope-rolebindings \
  certificaterequests \
  'object.rules.size() == 7' \
  'object.rules.size() == 6' \
  'object.rules.all' \
  'object.subjects.size() == 1' \
  "object.roleRef.apiGroup == 'rbac.authorization.k8s.io'" \
  "object.subjects[0].namespace == 'firemud-system'" \
  firemud-hosted-identity-scope \
  firemud-hosted-runtime-scope \
  controller-scope \
  firemud-hosted-system-namespace-guard \
  "request.operation == 'UPDATE'" \
  "request.operation in ['CREATE', 'DELETE']" \
  "has(object.metadata.labels) == has(oldObject.metadata.labels)" \
  "has(object.metadata.ownerReferences) == has(oldObject.metadata.ownerReferences)" \
  "object.metadata.finalizers == oldObject.metadata.finalizers" \
  "object.spec == oldObject.spec" \
  "system:serviceaccount:kube-system:namespace-controller" \
  validationActions: \
  "- Deny"; do
  require_literal "$ADMISSION" "$text_value"
done
require_regex "$ADMISSION" 'dev-demo'
require_regex "$ADMISSION" 'pr-\[1-9\]\[0-9\]\*'
require_literal "$ADMISSION" "oldObject.metadata.labels['firemud.dev/retention'] == 'retained'"
ADMISSION="$ADMISSION" python3 - <<'PY'
import os
import re
from pathlib import Path

import yaml

documents = list(
    yaml.safe_load_all(Path(os.environ["ADMISSION"]).read_text(encoding="utf-8"))
)
policy_documents = [
    document
    for document in documents
    if isinstance(document, dict) and document.get("kind") == "ValidatingAdmissionPolicy"
]
binding_documents = [
    document
    for document in documents
    if isinstance(document, dict)
    and document.get("kind") == "ValidatingAdmissionPolicyBinding"
]
assert len(policy_documents) >= 7
assert all(policy.get("spec", {}).get("failurePolicy") == "Fail" for policy in policy_documents)
assert len(binding_documents) >= 7
assert all(
    binding.get("spec", {}).get("validationActions") == ["Deny"]
    for binding in binding_documents
)
valid_admission_operations = {"CREATE", "UPDATE", "DELETE", "CONNECT"}
for policy in policy_documents:
    rules = policy.get("spec", {}).get("matchConstraints", {}).get("resourceRules", [])
    assert rules, policy["metadata"]["name"]
    for rule in rules:
        operations = rule.get("operations", [])
        assert operations, policy["metadata"]["name"]
        assert set(operations) <= valid_admission_operations, (
            policy["metadata"]["name"],
            operations,
        )
        assert len(operations) == len(set(operations)), (
            policy["metadata"]["name"],
            operations,
        )
    for validation in policy.get("spec", {}).get("validations", []):
        assert "PATCH" not in validation.get("expression", ""), (
            policy["metadata"]["name"],
            validation.get("expression", ""),
        )
policies = {document["metadata"]["name"]: document for document in policy_documents}
assert len(policies) == len(policy_documents)
break_glass = "request.userInfo.groups.exists(group, group == 'system:masters')"
callers = {
    "firemud-hosted-identity-main": "firemud-hosted-identity-requester",
    "firemud-hosted-identity-subresources": "firemud-hosted-identity-controller",
    "firemud-hosted-identity-scope-roles": "firemud-hosted-identity-controller",
    "firemud-hosted-identity-scope-rolebindings": "firemud-hosted-identity-controller",
}
for policy_name, service_account in callers.items():
    expressions = [
        validation["expression"]
        for validation in policies[policy_name]["spec"]["validations"]
    ]
    caller_expression = next(
        expression for expression in expressions if service_account in expression
    )
    assert caller_expression.startswith(f"{break_glass} ||"), policy_name

main_expressions = [
    validation["expression"]
    for validation in policies["firemud-hosted-identity-main"]["spec"]["validations"]
]
delete_expression = next(
    expression
    for expression in main_expressions
    if "oldObject.status.phase == 'Retired'" in expression
)
assert delete_expression == (
    "request.operation != 'DELETE' || "
    "(has(oldObject.status) && has(oldObject.status.phase) && "
    "oldObject.spec.desiredState == 'Retired' && "
    "oldObject.status.phase == 'Retired')"
)
main_authorization = next(
    expression
    for expression in main_expressions
    if "firemud-hosted-identity-requester" in expression
)
controller = (
    "system:serviceaccount:firemud-system:firemud-hosted-identity-controller"
)
assert controller in main_authorization
controller_finalizer_expression = next(
    expression
    for expression in main_expressions
    if f"request.userInfo.username != '{controller}'" in expression
    and "object.metadata.finalizers.filter" in expression
)
assert controller_finalizer_expression.startswith(f"{break_glass} ||")
main_rule = policies["firemud-hosted-identity-main"]["spec"]["matchConstraints"][
    "resourceRules"
][0]
assert main_rule["operations"] == ["CREATE", "UPDATE", "DELETE"]
assert main_rule["resources"] == ["hostedenvironmentidentities"]
main_route = next(
    expression for expression in main_expressions if "request.subResource == ''" in expression
)
assert main_route.startswith("request.subResource == '' &&")
subresource_rule = policies["firemud-hosted-identity-subresources"]["spec"][
    "matchConstraints"
]["resourceRules"][0]
assert subresource_rule["operations"] == ["UPDATE"]
assert subresource_rule["resources"] == ["hostedenvironmentidentities/status"]
for required in (
    "request.operation == 'UPDATE'",
    "object.spec == oldObject.spec",
    "has(object.status) == has(oldObject.status)",
    "(!has(object.status) || object.status == oldObject.status)",
    "object.metadata.name == oldObject.metadata.name",
    "object.metadata.namespace == oldObject.metadata.namespace",
    "has(object.metadata.labels) == has(oldObject.metadata.labels)",
    "(!has(object.metadata.labels) || object.metadata.labels == oldObject.metadata.labels)",
    "has(object.metadata.annotations) == has(oldObject.metadata.annotations)",
    "(!has(object.metadata.annotations) || object.metadata.annotations == oldObject.metadata.annotations)",
    "has(object.metadata.ownerReferences) == has(oldObject.metadata.ownerReferences)",
    "(!has(object.metadata.ownerReferences) || object.metadata.ownerReferences == oldObject.metadata.ownerReferences)",
    "oldObject.metadata.finalizers.exists",
    "object.metadata.finalizers.exists",
    "object.metadata.finalizers.filter",
    "oldObject.metadata.finalizers.filter",
):
    assert required in controller_finalizer_expression, required

requester_metadata = next(
    expression
    for expression in main_expressions
    if "has(object.metadata.finalizers) == has(oldObject.metadata.finalizers)"
    in expression
)
assert requester_metadata.startswith(f"{break_glass} ||")
for required in (
    "request.operation == 'UPDATE'",
    "has(object.metadata.finalizers) == has(oldObject.metadata.finalizers)",
    "(!has(object.metadata.finalizers) || object.metadata.finalizers == oldObject.metadata.finalizers)",
    "request.operation == 'CREATE'",
    "(!has(object.metadata.finalizers) || object.metadata.finalizers.size() == 0)",
):
    assert required in requester_metadata, required

controller_finalizer_value = ["platform.firemud.dev/hosted-environment-identity"]
controller_finalizer_name = controller_finalizer_value[0]


def identity_object(finalizers=None):
    metadata = {
        "name": "pr-42",
        "namespace": "firemud-system",
        "labels": {"example.test/label": "preserved"},
        "annotations": {"example.test/annotation": "preserved"},
        "ownerReferences": [{"apiVersion": "v1", "kind": "ConfigMap", "name": "owner"}],
    }
    if finalizers is not None:
        metadata["finalizers"] = finalizers
    return {
        "metadata": metadata,
        "spec": {"desiredState": "Active"},
        "status": {"phase": "Pending"},
    }


def main_policy_controller_update_accepted(
    old_object,
    new_object,
    *,
    operation="UPDATE",
    sub_resource="",
    username=controller,
    groups=(),
):
    if operation not in main_rule["operations"] or sub_resource != "":
        return False
    if new_object["metadata"]["namespace"] != "firemud-system":
        return False
    if re.fullmatch(r"(?:dev-demo|pr-[1-9][0-9]*)", new_object["metadata"]["name"]) is None:
        return False
    system_masters = "system:masters" in groups
    if not system_masters and username not in (
        "system:serviceaccount:firemud-system:firemud-hosted-identity-requester",
        controller,
    ):
        return False

    old_metadata = old_object["metadata"]
    new_metadata = new_object["metadata"]
    old = old_metadata.get("finalizers", [])
    new = new_metadata.get("finalizers", [])
    addition = (
        controller_finalizer_name not in old
        and controller_finalizer_name in new
        and len(new) == len(old) + 1
        and [value for value in new if value != controller_finalizer_name] == old
    )
    removal = (
        controller_finalizer_name in old
        and controller_finalizer_name not in new
        and len(old) == len(new) + 1
        and new == [value for value in old if value != controller_finalizer_name]
    )
    unchanged_fields = all(
        (field in new_metadata) == (field in old_metadata)
        and (field not in new_metadata or new_metadata[field] == old_metadata[field])
        for field in ("labels", "annotations", "ownerReferences")
    )
    controller_shape = (
        operation == "UPDATE"
        and new_object["spec"] == old_object["spec"]
        and ("status" in new_object) == ("status" in old_object)
        and ("status" not in new_object or new_object["status"] == old_object["status"])
        and new_metadata["name"] == old_metadata["name"]
        and new_metadata["namespace"] == old_metadata["namespace"]
        and unchanged_fields
        and (addition or removal)
    )
    if not system_masters and username == controller and not controller_shape:
        return False
    if new_object["spec"]["desiredState"] not in ("Active", "Retired"):
        return False
    if not (
        new_object["spec"]["desiredState"] == old_object["spec"]["desiredState"]
        or (
            old_object["spec"]["desiredState"] == "Active"
            and new_object["spec"]["desiredState"] == "Retired"
        )
    ):
        return False
    return system_masters or username == controller


external = "example.test/external"
replacement = "example.test/replacement"
for old_finalizers, new_finalizers in (
    (None, controller_finalizer_value),
    ([], controller_finalizer_value),
    ([external], [external, controller_finalizer_name]),
    ([external], [controller_finalizer_name, external]),
    ([external, controller_finalizer_name], [external]),
    ([controller_finalizer_name, external], [external]),
):
    assert main_policy_controller_update_accepted(
        identity_object(old_finalizers), identity_object(new_finalizers)
    )

for old_finalizers, new_finalizers in (
    ([external, controller_finalizer_name], controller_finalizer_value),
    ([external], []),
    ([external], [replacement]),
    ([external], [replacement, controller_finalizer_name]),
    ([external, controller_finalizer_name], [replacement]),
    ([external], [external]),
):
    assert not main_policy_controller_update_accepted(
        identity_object(old_finalizers), identity_object(new_finalizers)
    )

reordered_old = ["example.test/first", "example.test/second"]
reordered_new = ["example.test/second", controller_finalizer_name, "example.test/first"]
assert not main_policy_controller_update_accepted(
    identity_object(reordered_old), identity_object(reordered_new)
)
metadata_mutation_old = identity_object([external])
metadata_mutation_new = identity_object([external, controller_finalizer_name])
metadata_mutation_new["metadata"]["labels"] = {"example.test/label": "changed"}
assert not main_policy_controller_update_accepted(
    metadata_mutation_old, metadata_mutation_new
)
assert not main_policy_controller_update_accepted(
    identity_object([external]),
    identity_object([external, controller_finalizer_name]),
    sub_resource="finalizers",
)
assert main_policy_controller_update_accepted(
    identity_object([external, controller_finalizer_name]),
    identity_object([]),
    groups=("system:masters",),
)


def requester_finalizers_accepted(operation, new_metadata, old_metadata=None):
    if operation == "UPDATE":
        return (
            ("finalizers" in new_metadata) == ("finalizers" in old_metadata)
            and (
                "finalizers" not in new_metadata
                or new_metadata["finalizers"] == old_metadata["finalizers"]
            )
        )
    return operation == "CREATE" and (
        "finalizers" not in new_metadata or len(new_metadata["finalizers"]) == 0
    )


absent_finalizers = {}
present_finalizer = {"finalizers": controller_finalizer_value}
assert requester_finalizers_accepted(
    "UPDATE", absent_finalizers, absent_finalizers
)
assert requester_finalizers_accepted(
    "UPDATE", present_finalizer, present_finalizer
)
assert not requester_finalizers_accepted(
    "UPDATE", present_finalizer, absent_finalizers
)
assert not requester_finalizers_accepted(
    "UPDATE", absent_finalizers, present_finalizer
)
assert requester_finalizers_accepted("CREATE", absent_finalizers)
assert requester_finalizers_accepted("CREATE", {"finalizers": []})
assert not requester_finalizers_accepted("CREATE", present_finalizer)

optional_metadata_fields = ("labels", "annotations", "ownerReferences", "finalizers")


def optional_metadata_unchanged(new_metadata, old_metadata):
    return all(
        (field in new_metadata) == (field in old_metadata)
        and (field not in new_metadata or new_metadata[field] == old_metadata[field])
        for field in optional_metadata_fields
    )


absent_optional_metadata = {"name": "pr-42", "namespace": "firemud-system"}
assert optional_metadata_unchanged(
    absent_optional_metadata,
    absent_optional_metadata.copy(),
)
for field in optional_metadata_fields:
    present_value = [] if field in ("ownerReferences", "finalizers") else {}
    with_field = {**absent_optional_metadata, field: present_value}
    assert not optional_metadata_unchanged(with_field, absent_optional_metadata)
    assert not optional_metadata_unchanged(absent_optional_metadata, with_field)

assert not optional_metadata_unchanged(
    {**absent_optional_metadata, "labels": {"example": "changed"}},
    {**absent_optional_metadata, "labels": {"example": "original"}},
)
assert not optional_metadata_unchanged(
    {**absent_optional_metadata, "annotations": {"example": "changed"}},
    {**absent_optional_metadata, "annotations": {"example": "original"}},
)
assert not optional_metadata_unchanged(
    {**absent_optional_metadata, "ownerReferences": [{"name": "changed"}]},
    {**absent_optional_metadata, "ownerReferences": [{"name": "original"}]},
)

role_expression = policies["firemud-hosted-identity-scope-roles"]["spec"]["validations"][0]["expression"]
assert "(request.userInfo.username == 'system:serviceaccount:firemud-system:firemud-hosted-identity-controller' &&" in role_expression
assert "object.metadata.labels.size() == 6" in role_expression
assert "object.rules.size() == 7" in role_expression
assert "object.rules.size() == 6" in role_expression
assert "r.resources == ['certificaterequests']" in role_expression
assert "r.verbs == ['list']" in role_expression
binding_expression = policies["firemud-hosted-identity-scope-rolebindings"]["spec"]["validations"][0]["expression"]
assert "(request.userInfo.username == 'system:serviceaccount:firemud-system:firemud-hosted-identity-controller' &&" in binding_expression
assert "object.metadata.labels.size() == 5" in binding_expression
assert "object.subjects.size() == 1" in binding_expression
namespace_expression = policies["firemud-hosted-system-namespace-guard"]["spec"]["validations"][0]["expression"]
assert namespace_expression.startswith(f"({break_glass} &&")
assert "(request.operation == 'DELETE' ? request.name : object.metadata.name)" in namespace_expression
assert "'^(firemud-system|dev-identity|pr-[1-9][0-9]*-identity)$'" in namespace_expression
assert "request.userInfo.username == 'system:serviceaccount:firemud-system:firemud-hosted-identity-controller'" in namespace_expression
assert "oldObject.metadata.labels['firemud.dev/retention'] == 'retained'" in namespace_expression
assert "object.metadata.labels['firemud.dev/retention'] == 'retained'" in namespace_expression
for field in optional_metadata_fields:
    assert f"has(object.metadata.{field}) == has(oldObject.metadata.{field})" in namespace_expression
    assert (
        f"(!has(object.metadata.{field}) || "
        f"object.metadata.{field} == oldObject.metadata.{field})"
    ) in namespace_expression
secret_policy = policies["firemud-hosted-identity-secret-boundary"]
secret_match = secret_policy["spec"]["matchConditions"][0]["expression"]
normalized_secret_match = " ".join(secret_match.split())
cert_manager_match = (
    "(request.userInfo.username == "
    "'system:serviceaccount:cert-manager:cert-manager' && "
    "request.namespace.matches('^(dev-identity|pr-[1-9][0-9]*-identity)$'))"
)
assert normalized_secret_match.count(cert_manager_match) == 1
assert "system:serviceaccount:firemud-system:firemud-hosted-identity-controller" in secret_match
assert "request.operation == 'DELETE'" in secret_match
assert "request.operation != 'DELETE'" in secret_match
assert "request.name == 'firemud-grpc-tls'" in secret_match
assert "object.metadata.name == 'firemud-grpc-tls'" in secret_match

secret_expressions = [
    validation["expression"]
    for validation in secret_policy["spec"]["validations"]
]
cert_manager_expression = next(
    expression
    for expression in secret_expressions
    if "source-materialized" in expression
)
assert "ownerReferences.size() == 0" in cert_manager_expression
assert "object.type == 'kubernetes.io/tls'" in cert_manager_expression
assert "object.metadata.annotations['cert-manager.io/certificate-name'] == object.metadata.name" in cert_manager_expression
assert "object.metadata.annotations['cert-manager.io/issuer-name'] == 'letsencrypt-prod'" in cert_manager_expression
assert "object.metadata.annotations['cert-manager.io/issuer-kind'] == 'ClusterIssuer'" in cert_manager_expression
assert "object.metadata.annotations['cert-manager.io/issuer-group'] == 'cert-manager.io'" in cert_manager_expression
assert "object.metadata.name == 'dev-tls'" in cert_manager_expression
assert "object.metadata.name == 'dev-telnet-tls'" in cert_manager_expression
assert "object.metadata.name == 'dev-gateway-internal-ws'" in cert_manager_expression
assert "object.metadata.name == 'dev-tcp-proxy-bridge'" in cert_manager_expression
assert "request.namespace.substring(0, request.namespace.size() - 9) + '-tls'" in cert_manager_expression
assert "request.namespace.substring(0, request.namespace.size() - 9) + '-telnet-tls'" in cert_manager_expression
assert "request.namespace.substring(0, request.namespace.size() - 9) + '-gateway-internal-ws'" in cert_manager_expression
assert "request.namespace.substring(0, request.namespace.size() - 9) + '-tcp-proxy-bridge'" in cert_manager_expression
assert any("object.metadata.name != 'firemud-grpc-tls'" in expression for expression in secret_expressions)

certificate_expressions = [
    validation["expression"]
    for validation in policies["firemud-hosted-identity-certificate-boundary"]["spec"]["validations"]
]
profile_expression = next(
    expression for expression in certificate_expressions if "gateway-internal-ws" in expression
)
assert "spring-cloud-gateway-mtls." in profile_expression
assert ".svc.cluster.local" in profile_expression
assert "spiffe://firemud/ns/" in profile_expression
assert "/sa/tcp-proxy-service" in profile_expression
assert "has(object.spec.encodeUsagesInRequest)" in profile_expression
assert "object.spec.encodeUsagesInRequest == true" in profile_expression
assert (
    "object.metadata.labels['firemud.dev/role'] != 'tcp-proxy-bridge' ||\n"
    "    ((!has(object.spec.dnsNames) || object.spec.dnsNames.size() == 0) &&"
) in profile_expression
assert "object.spec.usages == ['digital signature', 'key encipherment', 'server auth']" in profile_expression
assert "object.spec.usages == ['digital signature', 'key encipherment', 'client auth']" in profile_expression
PY
for phase in Pending Provisioning WaitingForCertificate RuntimeAbsent Syncing Verifying Ready Degraded Blocked Retiring Retired; do
  require_literal "$CRD" "- $phase"
done
for status_field in sourceGeneration sourceObjectGeneration spkiSha256; do
  require_literal "$CRD" "$status_field:"
done

for file in "$MANIFEST_DIR"/*.yaml; do
  forbid_literal "$file" 'apiGroups: ["*"]'
  forbid_literal "$file" 'resources: ["*"]'
done
require_literal "$RBAC" "name: firemud-hosted-identity-controller-namespace-lifecycle"
require_literal "$RBAC" "- namespaces"
require_literal "$RBAC" "- create"
require_literal "$RBAC" "- delete"
require_literal "$RBAC" "resourceNames:"
require_literal "$RBAC" "firemud-grpc-ca"
for forbidden_ha_marker in \
  'kind: Lease' \
  firemud-hosted-identity-leader-election \
  FIREMUD_HOSTED_IDENTITY_LEADER_ELECTION_LEASE; do
  forbid_literal "$RBAC" "$forbidden_ha_marker"
done
requester_rbac="$(
  select_named_yaml_document "$RBAC" Role firemud-hosted-identity-requester
)"
[[ -n "$requester_rbac" ]] || fail "requester Role selection is empty"
for forbidden_requester_permission in secrets certificates; do
  if grep -Fqi -- "$forbidden_requester_permission" <<<"$requester_rbac"; then
    fail "requester role has forbidden $forbidden_requester_permission access"
  fi
done
controller_rbac="$(
  select_named_yaml_document "$RBAC" Role firemud-hosted-identity-controller
)"
CONTROLLER_RBAC="$controller_rbac" python3 - <<'PY'
import os

import yaml

role = yaml.safe_load(os.environ["CONTROLLER_RBAC"])
primary = next(
    rule
    for rule in role["rules"]
    if rule.get("resources") == ["hostedenvironmentidentities"]
)
assert primary["verbs"] == ["get", "list", "watch", "patch"]
subresources = next(
    rule
    for rule in role["rules"]
    if rule.get("resources")
    == ["hostedenvironmentidentities/status", "hostedenvironmentidentities/finalizers"]
)
assert subresources["verbs"] == ["get", "update", "patch"]
PY
cluster_role_rbac="$(
  select_named_yaml_document "$RBAC" ClusterRole \
    firemud-hosted-identity-controller-namespace-lifecycle
)"
for forbidden_cluster_permission in secrets certificates hostedenvironmentidentities; do
  if grep -Fqi -- "$forbidden_cluster_permission" <<<"$cluster_role_rbac"; then
    fail "controller ClusterRole has broad $forbidden_cluster_permission access"
  fi
done
scope_writer_rbac="$(
  select_named_yaml_document "$RBAC" ClusterRole firemud-hosted-identity-scope-writer
)"
[[ -n "$scope_writer_rbac" ]] || fail "scope-writer ClusterRole selection is empty"
for text_value in \
  "- roles" \
  "- rolebindings" \
  "- escalate" \
  "- bind" \
  "- create" \
  "resourceNames:" \
  firemud-hosted-identity-scope \
  firemud-hosted-runtime-scope; do
  grep -Fq -- "$text_value" <<<"$scope_writer_rbac" || \
    fail "scope-writer ClusterRole is missing $text_value"
done
require_literal "$ADMISSION" "object.metadata.name == object.roleRef.name"
for forbidden_scope_permission in 'apiGroups: ["*"]' 'resources: ["*"]' namespaces secrets certificates; do
  if grep -Fqi -- "$forbidden_scope_permission" <<<"$scope_writer_rbac"; then
    fail "scope-writer ClusterRole has forbidden $forbidden_scope_permission access"
  fi
done

for text_value in \
  "serviceAccountName: firemud-hosted-identity-controller" \
  "@sha256:__IMAGE_DIGEST_REQUIRED__" \
  "runAsNonRoot: true" \
  "readOnlyRootFilesystem: true" \
  "allowPrivilegeEscalation: false" \
  "- ALL" \
  "type: RuntimeDefault" \
  "requests:" \
  "limits:" \
  "/actuator/health/liveness" \
  "/actuator/health/readiness" \
  "replicas: 1" \
  "progressDeadlineSeconds: 420" \
  "type: Recreate" \
  FIREMUD_HOSTED_IDENTITY_CONTROL_NAMESPACE \
  FIREMUD_HOSTED_IDENTITY_PREVIEW_DOMAIN \
  FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE \
  __ACTIVATION_MODE_REQUIRED__ \
  FIREMUD_HOSTED_IDENTITY_GRPC_TRUST_ANCHOR_SHA256 \
  __GRPC_TRUST_ANCHOR_SHA256_REQUIRED__ \
  "medium: Memory" \
  "sizeLimit: 64Mi"; do
  require_literal "$DEPLOYMENT" "$text_value"
done
DEPLOYMENT="$DEPLOYMENT" python3 - <<'PY'
import os
from pathlib import Path

import yaml

deployment = yaml.safe_load(Path(os.environ["DEPLOYMENT"]).read_text(encoding="utf-8"))
pod_spec = deployment["spec"]["template"]["spec"]
tmp_volumes = [volume for volume in pod_spec["volumes"] if volume.get("name") == "tmp"]
assert tmp_volumes == [
    {"name": "tmp", "emptyDir": {"medium": "Memory", "sizeLimit": "64Mi"}}
]
PY
for marker in ':latest' ':stable' ':main' ':develop'; do
  forbid_literal "$DEPLOYMENT" "$marker"
done
forbidden_strategy="type: RollingUpdate"
forbid_literal "$DEPLOYMENT" "$forbidden_strategy"

for text_value in \
  policyTypes: '- Ingress' '- Egress' 'ingress: []' 'k8s-app: kube-dns' \
  'port: 53' 'port: 443' \
  'endPort: 32016' 'port: 6565' 'firemud.dev/preview: "true"' \
  'firemud.dev/dev-demo: "true"' 'cannot select the apiserver or a public hostname' \
  'except:' '169.254.0.0/16' 'fe80::/10'; do
  require_literal "$NETWORKPOLICY" "$text_value"
done
NETWORKPOLICY="$NETWORKPOLICY" python3 - <<'PY'
import os
from pathlib import Path

import yaml

policy = yaml.safe_load(Path(os.environ["NETWORKPOLICY"]).read_text(encoding="utf-8"))
assert policy["spec"]["policyTypes"] == ["Ingress", "Egress"]
assert policy["spec"]["ingress"] == []
telnet_rules = [
    rule
    for rule in policy["spec"]["egress"]
    if rule.get("ports")
    == [{"protocol": "TCP", "port": 32000, "endPort": 32016}]
]
assert len(telnet_rules) == 1, "controller Telnet NodePort egress rule is missing"
assert telnet_rules[0]["to"] == [
    {"ipBlock": {"cidr": "0.0.0.0/0", "except": ["169.254.0.0/16"]}},
    {"ipBlock": {"cidr": "::/0", "except": ["fe80::/10"]}},
], "controller Telnet NodePort egress must exclude IPv4 and IPv6 link-local ranges"
grpc_targets = [
    target
    for rule in policy["spec"]["egress"]
    if any(port.get("port") == 6565 for port in rule.get("ports", []))
    for target in rule.get("to", [])
]
assert grpc_targets, "controller gRPC egress rule is missing"
for target in grpc_targets:
    assert target.get("namespaceSelector", {}).get("matchLabels", {}).get(
        "firemud.dev/preview"
    ) == "true" or target.get("namespaceSelector", {}).get("matchLabels", {}).get(
        "firemud.dev/dev-demo"
    ) == "true", target
    assert target.get("podSelector", {}).get("matchLabels") == {
        "app": "account-service"
    }, target

gateway_targets = [
    target
    for rule in policy["spec"]["egress"]
    if any(port.get("port") == 443 for port in rule.get("ports", []))
    for target in rule.get("to", [])
    if target.get("podSelector", {}).get("matchLabels") == {
        "app": "spring-cloud-gateway"
    }
]
assert len(gateway_targets) == 2, "controller Gateway-internal TLS egress rule is missing"
assert {
    tuple(sorted(target["namespaceSelector"]["matchLabels"].items()))
    for target in gateway_targets
} == {
    (("firemud.dev/preview", "true"),),
    (("firemud.dev/dev-demo", "true"),),
}
PY
for text_value in \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR \
  --server-side \
  --field-manager \
  'kubectl kustomize' \
  'rollout status' \
  'kubectl auth can-i' \
  'paused|observe|active' \
  --grpc-trust-anchor-sha256 \
  'GRPC_TRUST_ANCHOR_SHA256" =~ ^[0-9a-f]{64}$' \
  '@sha256:[0-9a-f]{64}'; do
  require_literal "$BOOTSTRAP" "$text_value"
done
for admission_name in \
  firemud-hosted-identity-main \
  firemud-hosted-identity-subresources \
  firemud-hosted-identity-secret-boundary \
  firemud-hosted-identity-certificate-boundary \
  firemud-hosted-identity-scope-roles \
  firemud-hosted-identity-scope-rolebindings \
  firemud-hosted-system-namespace-guard; do
  require_literal "$BOOTSTRAP" "$admission_name"
done
require_literal "$BOOTSTRAP" "validatingadmissionpolicybinding"
require_literal "$BOOTSTRAP" ".spec.failurePolicy"
require_literal "$BOOTSTRAP" ".spec.validationActions[*]"
for forbidden_command in 'kubectl delete' 'kubectl apply --all'; do
  forbid_literal "$BOOTSTRAP" "$forbidden_command"
done
require_literal "$ADMISSION" "'firemud-grpc-tls-previous', 'firemud-grpc-ca'"
require_literal "$ADMISSION" "request.userInfo.username == 'system:serviceaccount:firemud-system:firemud-hosted-identity-controller' ||"
for ca_proof in \
  'get secret firemud-grpc-ca' \
  "ca.crt\\nca.key" \
  "openssl x509 -outform DER" \
  "openssl x509 -pubkey -noout" \
  "openssl pkey -pubout -outform DER" \
  'does not match the configured fingerprint' \
  'ca.crt and ca.key do not match'; do
  require_literal "$BOOTSTRAP" "$ca_proof"
done
# shellcheck disable=SC2016 # Match the literal bootstrap variable expression.
require_literal "$BOOTSTRAP" '[[ "$crd_established" == "True" ]]'
require_literal "$BOOTSTRAP" "HostedEnvironmentIdentity CRD is not Established=True"
BOOTSTRAP="$BOOTSTRAP" python3 - <<'PY'
import os
from pathlib import Path

source = Path(os.environ["BOOTSTRAP"]).read_text(encoding="utf-8")
last_authorization_probe = source.index(
    'expect_can_i no --as="$requester_sa" --namespace=dev '
    'get hostedenvironmentidentities.platform.firemud.dev'
)
active_transition = source.index(
    'if [[ "$ACTIVATION_MODE" == "active" ]]; then\n'
    '  verify_grpc_ca_prerequisite'
)
assert last_authorization_probe < active_transition
assert 'rendered_activation_mode="$(sed -n ' in source
assert '[[ "$rendered_activation_mode" == "$initial_activation_mode" ]]' in source
assert '[[ "$rendered_activation_mode" == "$ACTIVATION_MODE" ]]' in source
assert 'fail "activation mode still contains the paused value after replacement"' in source
PY
require_literal "$PROJECTION" "ACCEPTED_SOURCE_OBJECT_GENERATION_ANNOTATION"
forbid_literal "$GRPC_GENERATOR" 'requiredData(caSource, "tls.crt")'
forbid_literal "$GRPC_GENERATOR" 'requiredData(caSource, "tls.key")'

(
bootstrap_test_dir="$(mktemp -d)"
trap 'rm -rf "$bootstrap_test_dir"' EXIT
bootstrap_output="$bootstrap_test_dir/output"
bootstrap_error="$bootstrap_test_dir/error"
cat >"$bootstrap_test_dir/kubectl" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail

record_event() {
  if [[ -n "${FAKE_EVENT_LOG:-}" ]]; then
    printf '%s\n' "$1" >>"$FAKE_EVENT_LOG"
  fi
}

if [[ "${1:-}" == "kustomize" ]]; then
  cat <<'YAML'
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      containers:
        - name: controller
          image: ghcr.io/benhook1013/hosted-environment-identity-controller@sha256:__IMAGE_DIGEST_REQUIRED__
          env:
            - name: FIREMUD_HOSTED_IDENTITY_GRPC_TRUST_ANCHOR_SHA256
              value: __GRPC_TRUST_ANCHOR_SHA256_REQUIRED__
            - name: FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE
              value: __ACTIVATION_MODE_REQUIRED__
YAML
  exit 0
fi
if [[ "${1:-}" == "apply" ]]; then
  manifest=''
  previous=''
  for argument in "$@"; do
    if [[ "$previous" == "-f" ]]; then
      manifest="$argument"
    fi
    previous="$argument"
  done
  [[ -n "$manifest" ]] || exit 2
  activation_mode="$(sed -n '/FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE/{n;s/^[[:space:]]*value: //p;}' "$manifest")"
  record_event "apply:${activation_mode}"
  exit 0
fi
if [[ "${1:-}" == "-n" && "${2:-}" == "firemud-system" && "${3:-}" == "rollout" ]]; then
  record_event rollout
  exit 0
fi
if [[ "${1:-}" == "-n" && "${2:-}" == "firemud-system" && "${3:-}" == "get" && "${4:-}" == "deployment" ]]; then
  printf '1/1\n'
  exit 0
fi
if [[ "${1:-}" == "get" && "${2:-}" == "crd" ]]; then
  printf '%s\n' "${FAKE_CRD_ESTABLISHED:-True}"
  exit 0
fi
if [[ "${1:-}" == "-n" && "${2:-}" == "firemud-system" && "${3:-}" == "get" && "${4:-}" == "secret" && "${5:-}" == "firemud-grpc-ca" ]]; then
  record_event ca-read
  case "$*" in
    *'{.type}'*) printf 'Opaque' ;;
    *'go-template='*) printf 'ca.crt\nca.key\n' ;;
    *'{.data.ca\.crt}'*) base64 --wrap=0 <"$FAKE_CA_CERT" ;;
    *'{.data.ca\.key}'*) base64 --wrap=0 <"$FAKE_CA_KEY" ;;
    *) exit 2 ;;
  esac
  exit 0
fi
if [[ "${1:-}" == "get" && "${2:-}" == "validatingadmissionpolicy" ]]; then
  printf 'Fail\n'
  exit 0
fi
if [[ "${1:-}" == "get" && "${2:-}" == "validatingadmissionpolicybinding" ]]; then
  printf 'Deny\n'
  exit 0
fi
if [[ "${1:-}" == "auth" && "${2:-}" == "can-i" ]]; then
  record_event auth-check
  if [[ " $* " == *" --all-namespaces "* || " $* " == *" --namespace=dev "* ]]; then
    if [[ "${FAKE_CAN_I_ERROR:-0}" == "1" ]]; then
      printf 'simulated authorization API failure\n' >&2
      exit 2
    fi
    if [[ "${FAKE_CAN_I_ALLOW_DENIED:-0}" == "1" ]]; then
      printf 'yes\n'
      exit 0
    fi
    printf 'no\n'
    exit 1
  fi
  printf 'yes\n'
  exit 0
fi
printf 'unexpected fake kubectl invocation: %s\n' "$*" >&2
exit 2
SH
chmod +x "$bootstrap_test_dir/kubectl"
bootstrap_ca_cert="$bootstrap_test_dir/ca.crt"
bootstrap_ca_key="$bootstrap_test_dir/ca.key"
bootstrap_mismatched_ca_key="$bootstrap_test_dir/mismatched-ca.key"
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "$bootstrap_ca_key" \
  -out "$bootstrap_ca_cert" \
  -days 1 \
  -subj '/CN=firemud-grpc-ca' \
  >/dev/null 2>&1
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out "$bootstrap_mismatched_ca_key" >/dev/null 2>&1
bootstrap_image='ghcr.io/benhook1013/hosted-environment-identity-controller@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
bootstrap_fingerprint="$(
  openssl x509 -in "$bootstrap_ca_cert" -outform DER |
    sha256sum |
    awk '{print $1}'
)"
[[ "$bootstrap_fingerprint" =~ ^[0-9a-f]{64}$ ]] || \
  fail "could not compute the fixture gRPC CA fingerprint"
if ! FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 PATH="$bootstrap_test_dir:$PATH" \
  bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap rejected expected auth can-i no results: $(cat "$bootstrap_error")"
fi
require_literal "$bootstrap_output" "activation=paused"
if FAKE_CRD_ESTABLISHED=False FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$bootstrap_test_dir:$PATH" bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted a CRD that was not Established=True"
fi
require_literal "$bootstrap_error" "HostedEnvironmentIdentity CRD is not Established=True"
active_event_log="$bootstrap_test_dir/active-events"
if ! FAKE_EVENT_LOG="$active_event_log" \
  FAKE_CA_CERT="$bootstrap_ca_cert" FAKE_CA_KEY="$bootstrap_ca_key" \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$bootstrap_test_dir:$PATH" bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --activation-mode active --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap rejected the verified pause-first active transition: $(cat "$bootstrap_error")"
fi
require_literal "$bootstrap_output" "activation=active"
mapfile -t active_events <"$active_event_log"
[[ "${active_events[0]:-}" == "apply:paused" ]] || \
  fail "active bootstrap did not apply paused mode first"
[[ "${active_events[-2]:-}" == "apply:active" ]] || \
  fail "active bootstrap did not replace paused mode with active"
[[ "${active_events[-1]:-}" == "rollout" ]] || \
  fail "active bootstrap did not wait for the active rollout"
auth_checks=0
last_auth_index=-1
first_ca_index=-1
active_apply_index=-1
for index in "${!active_events[@]}"; do
  case "${active_events[$index]}" in
    auth-check)
      auth_checks=$((auth_checks + 1))
      last_auth_index="$index"
      ;;
    ca-read)
      if (( first_ca_index < 0 )); then
        first_ca_index="$index"
      fi
      ;;
    apply:active) active_apply_index="$index" ;;
  esac
done
[[ "$auth_checks" -eq 11 ]] || fail "active bootstrap did not run all authorization probes"
(( first_ca_index > last_auth_index )) || \
  fail "active bootstrap read the CA before all authorization probes completed"
(( active_apply_index > first_ca_index )) || \
  fail "active bootstrap applied active mode before verifying the gRPC CA"
mismatch_event_log="$bootstrap_test_dir/mismatch-events"
if FAKE_EVENT_LOG="$mismatch_event_log" \
  FAKE_CA_CERT="$bootstrap_ca_cert" FAKE_CA_KEY="$bootstrap_ca_key" \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$bootstrap_test_dir:$PATH" bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc' \
  --activation-mode active --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted a mismatched gRPC CA fingerprint"
fi
require_literal "$bootstrap_error" "does not match the configured fingerprint"
if grep -Fxq -- "apply:active" "$mismatch_event_log"; then
  fail "bootstrap applied active mode after a mismatched gRPC CA fingerprint"
fi
key_mismatch_event_log="$bootstrap_test_dir/key-mismatch-events"
if FAKE_EVENT_LOG="$key_mismatch_event_log" \
  FAKE_CA_CERT="$bootstrap_ca_cert" FAKE_CA_KEY="$bootstrap_mismatched_ca_key" \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$bootstrap_test_dir:$PATH" bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --activation-mode active --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted a gRPC CA certificate and private-key mismatch"
fi
require_literal "$bootstrap_error" "ca.crt and ca.key do not match"
if grep -Fxq -- "apply:active" "$key_mismatch_event_log"; then
  fail "bootstrap applied active mode after a gRPC CA certificate/key mismatch"
fi
if FAKE_CAN_I_ERROR=1 FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$bootstrap_test_dir:$PATH" bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted an authorization API error"
fi
require_literal "$bootstrap_error" "failed with status 2"
if FAKE_CAN_I_ALLOW_DENIED=1 FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$bootstrap_test_dir:$PATH" bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted an unexpectedly allowed permission"
fi
require_literal "$bootstrap_error" "returned yes; expected no"
)

for file in "$MANIFEST_DIR"/*.yaml "$CONTROLLER_DIR"/*.sh; do
  forbid_regex "$file" '(production|staging|hobby-self-hosted)'
done

# Untrusted Service and Ingress shape errors must remain deliberate validator
# rejections rather than Python attribute/index tracebacks.
python3 - "$ARTIFACT_VALIDATOR" <<'PY'
import copy
import importlib.util
import sys
import tempfile
from pathlib import Path

import yaml

validator_path = Path(sys.argv[1])
module_spec = importlib.util.spec_from_file_location(
    "preview_artifact_validator", validator_path
)
validator = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(validator)


def assert_rejected(call, expected):
    try:
        call()
    except ValueError as error:
        assert str(error) == expected, (str(error), expected)
    else:
        raise AssertionError(f"validator accepted malformed artifact: {expected}")


internal_policies = {
    name: {"spec": copy.deepcopy(spec)}
    for name, spec in validator.EXPECTED_INTERNAL_NETWORK_POLICY_SPECS.items()
}
validator._validate_internal_network_policies(internal_policies)

unsafe_internal_policy_cases = []
widened_selector = copy.deepcopy(internal_policies)
widened_selector["internal-services"]["spec"]["podSelector"]["matchExpressions"][0][
    "values"
].append("spring-cloud-gateway")
unsafe_internal_policy_cases.append(("internal-services", widened_selector))

widened_destination = copy.deepcopy(internal_policies)
widened_destination["internal-services-egress"]["spec"]["egress"][2]["to"].append(
    {"ipBlock": {"cidr": "10.0.0.0/8"}}
)
unsafe_internal_policy_cases.append(
    ("internal-services-egress", widened_destination)
)

widened_port = copy.deepcopy(internal_policies)
widened_port["internal-services"]["spec"]["ingress"][0]["ports"].append(
    {"protocol": "TCP", "port": 8443}
)
unsafe_internal_policy_cases.append(("internal-services", widened_port))

widened_rule = copy.deepcopy(internal_policies)
widened_rule["internal-services-egress"]["spec"]["egress"].append(
    {"to": [{"podSelector": {}}]}
)
unsafe_internal_policy_cases.append(("internal-services-egress", widened_rule))

for policy_name, policies in unsafe_internal_policy_cases:
    assert_rejected(
        lambda policies=policies: validator._validate_internal_network_policies(
            policies
        ),
        f"NetworkPolicy/{policy_name} has an unsafe spec",
    )


with tempfile.TemporaryDirectory() as directory:
    temp_dir = Path(directory)
    for name, expected_spec in validator.EXPECTED_SERVICE_SPECS.items():
        service = {
            "apiVersion": "v1",
            "kind": "Service",
            "metadata": {"name": name, "namespace": "pr-42"},
            "spec": copy.deepcopy(expected_spec),
        }
        validator.validate_services([service])

        service["spec"]["externalIPs"] = ["203.0.113.42"]
        assert_rejected(
            lambda service=service: validator.validate_services([service]),
            f"Service/{name} has an unsafe spec",
        )

    tcp_proxy = {
        "apiVersion": "v1",
        "kind": "Service",
        "metadata": {"name": "tcp-proxy-service", "namespace": "pr-42"},
        "spec": copy.deepcopy(
            validator.EXPECTED_SERVICE_SPECS["tcp-proxy-service"]
        ),
    }
    for field, value in (
        ("allocateLoadBalancerNodePorts", True),
        ("externalTrafficPolicy", "Local"),
        ("healthCheckNodePort", 32001),
        ("internalTrafficPolicy", "Local"),
        ("sessionAffinity", "ClientIP"),
    ):
        unsafe_service = copy.deepcopy(tcp_proxy)
        unsafe_service["spec"][field] = value
        assert_rejected(
            lambda unsafe_service=unsafe_service: validator.validate_services(
                [unsafe_service]
            ),
            "Service/tcp-proxy-service has an unsafe spec",
        )

    tcp_source = temp_dir / "tcp-proxy-service.yaml"
    tcp_output = temp_dir / "tcp-proxy-service-with-port.yaml"
    tcp_source.write_text(yaml.safe_dump(tcp_proxy), encoding="utf-8")
    validator.inject_telnet_port(tcp_source, tcp_output, 32000)
    injected = yaml.safe_load(tcp_output.read_text(encoding="utf-8"))
    assert injected["spec"]["ports"] == [
        {
            "name": "tcp-2323",
            "nodePort": 32000,
            "port": 2323,
            "protocol": "TCP",
            "targetPort": 2323,
        }
    ]

    for index, malformed_spec in enumerate((None, [], "not-a-spec")):
        service = {
            "apiVersion": "v1",
            "kind": "Service",
            "metadata": {"name": "account-service", "namespace": "pr-42"},
            "spec": malformed_spec,
        }
        source = temp_dir / f"malformed-service-{index}.yaml"
        destination = temp_dir / f"malformed-service-{index}-output.yaml"
        source.write_text(yaml.safe_dump(service), encoding="utf-8")
        assert_rejected(
            lambda source=source, destination=destination: validator.sanitize(
                source, destination
            ),
            "Service/account-service.spec is not an object",
        )
        assert not destination.exists()
        assert_rejected(
            lambda service=service: validator.validate_services([service]),
            "Service/account-service.spec is not an object",
        )

    for index, malformed_ports in enumerate((None, {}, [None])):
        service = {
            "apiVersion": "v1",
            "kind": "Service",
            "metadata": {"name": "account-service", "namespace": "pr-42"},
            "spec": {
                "selector": {"app": "account-service"},
                "ports": malformed_ports,
            },
        }
        source = temp_dir / f"malformed-service-ports-{index}.yaml"
        destination = temp_dir / f"malformed-service-ports-{index}-output.yaml"
        source.write_text(yaml.safe_dump(service), encoding="utf-8")
        expected = "Service/account-service.spec.ports is not a list of objects"
        assert_rejected(
            lambda source=source, destination=destination: validator.sanitize(
                source, destination
            ),
            expected,
        )
        assert not destination.exists()
        assert_rejected(
            lambda service=service: validator.validate_services([service]), expected
        )

    valid_tls = [
        {
            "hosts": ["pr-42.preview.example.test"],
            "secretName": "pr-42-tls",
        }
    ]
    valid_path = {
        "path": "/",
        "pathType": "Prefix",
        "backend": {
            "service": {
                "name": "spring-cloud-gateway",
                "port": {"number": 80},
            }
        },
    }
    valid_rules = [
        {
            "host": "pr-42.preview.example.test",
            "http": {"paths": [valid_path]},
        }
    ]
    valid_ingress_spec = {
        "ingressClassName": "traefik",
        "tls": valid_tls,
        "rules": valid_rules,
    }
    valid_ingress = {
        "apiVersion": "networking.k8s.io/v1",
        "kind": "Ingress",
        "metadata": {"name": "firemud-preview", "namespace": "pr-42"},
        "spec": valid_ingress_spec,
    }
    validator.validate_ingress(
        valid_ingress,
        "pr-42",
        "pr-42.preview.example.test",
    )

    for field, value in (
        (
            "defaultBackend",
            {
                "service": {
                    "name": "logging-admin-service",
                    "port": {"number": 8080},
                }
            },
        ),
        ("untrustedExtension", {"enabled": True}),
    ):
        unsafe_ingress = copy.deepcopy(valid_ingress)
        unsafe_ingress["spec"][field] = value
        assert_rejected(
            lambda unsafe_ingress=unsafe_ingress: validator.validate_ingress(
                unsafe_ingress,
                "pr-42",
                "pr-42.preview.example.test",
            ),
            "Ingress/firemud-preview has an unsafe spec",
        )

    wrong_ingress_class = copy.deepcopy(valid_ingress)
    wrong_ingress_class["spec"]["ingressClassName"] = "untrusted"
    assert_rejected(
        lambda: validator.validate_ingress(
            wrong_ingress_class,
            "pr-42",
            "pr-42.preview.example.test",
        ),
        "Ingress/firemud-preview has an unsafe spec",
    )

    shape_cases = [
        (None, "Ingress/firemud-preview.spec is not an object"),
        ([], "Ingress/firemud-preview.spec is not an object"),
    ]
    for malformed in (None, {}, [None]):
        shape_cases.append(
            (
                {"tls": malformed, "rules": valid_rules},
                "Ingress/firemud-preview.spec.tls is not a list of objects",
            )
        )
    for malformed in (None, {}, [None]):
        shape_cases.append(
            (
                {"tls": valid_tls, "rules": malformed},
                "Ingress/firemud-preview.spec.rules is not a list of objects",
            )
        )
    for malformed in (None, {}, [None]):
        shape_cases.append(
            (
                {
                    "tls": valid_tls,
                    "rules": [
                        {
                            "host": "pr-42.preview.example.test",
                            "http": {"paths": malformed},
                        }
                    ],
                },
                (
                    "Ingress/firemud-preview.spec.rules[0].http.paths "
                    "is not a list of objects"
                ),
            )
        )
    for malformed in (None, [], "not-an-object"):
        shape_cases.append(
            (
                {
                    "tls": valid_tls,
                    "rules": [
                        {
                            "host": "pr-42.preview.example.test",
                            "http": malformed,
                        }
                    ],
                },
                "Ingress/firemud-preview.spec.rules[0].http is not an object",
            )
        )
    for malformed in (None, [], "not-an-object"):
        path = {**valid_path, "backend": malformed}
        shape_cases.append(
            (
                {
                    "tls": valid_tls,
                    "rules": [
                        {
                            "host": "pr-42.preview.example.test",
                            "http": {"paths": [path]},
                        }
                    ],
                },
                (
                    "Ingress/firemud-preview.spec.rules[0].http.paths[0].backend "
                    "is not an object"
                ),
            )
        )
    for malformed in (None, [], "not-an-object"):
        path = {**valid_path, "backend": {"service": malformed}}
        shape_cases.append(
            (
                {
                    "tls": valid_tls,
                    "rules": [
                        {
                            "host": "pr-42.preview.example.test",
                            "http": {"paths": [path]},
                        }
                    ],
                },
                (
                    "Ingress/firemud-preview.spec.rules[0].http.paths[0].backend.service "
                    "is not an object"
                ),
            )
        )
    for malformed in (None, [], "not-an-object"):
        path = {
            **valid_path,
            "backend": {
                "service": {
                    "name": "spring-cloud-gateway",
                    "port": malformed,
                }
            },
        }
        shape_cases.append(
            (
                {
                    "tls": valid_tls,
                    "rules": [
                        {
                            "host": "pr-42.preview.example.test",
                            "http": {"paths": [path]},
                        }
                    ],
                },
                (
                    "Ingress/firemud-preview.spec.rules[0].http.paths[0].backend."
                    "service.port is not an object"
                ),
            )
        )

    ingress_path = temp_dir / "malformed-ingress.yaml"
    for malformed_spec, expected in shape_cases:
        ingress = {
            "apiVersion": "networking.k8s.io/v1",
            "kind": "Ingress",
            "metadata": {"name": "firemud-preview", "namespace": "pr-42"},
            "spec": malformed_spec,
        }
        ingress_path.write_text(yaml.safe_dump(ingress), encoding="utf-8")
        assert_rejected(
            lambda: validator.validate_manifest(
                ingress_path,
                "pr-42",
                "pr-42-head-42",
                "pr-42.preview.example.test",
            ),
            expected,
        )
PY

rendered="$(mktemp)"
trap 'rm -f "$rendered"' EXIT
kubectl kustomize "$MANIFEST_DIR" >"$rendered"
require_literal "$rendered" "kind: CustomResourceDefinition"
require_literal "$rendered" "kind: ValidatingAdmissionPolicy"
require_literal "$rendered" "kind: Deployment"
forbid_literal "$rendered" 'apiGroups: ["*"]'
forbid_literal "$rendered" 'resources: ["*"]'

echo "hosted identity controller manifest contract passed"
