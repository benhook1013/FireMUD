#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
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

check_rbac_wildcards() {
  python3 - "$@" <<'PY'
import sys
from pathlib import Path

import yaml


def reject_rbac_wildcards(documents, source):
    for document in documents:
        if not isinstance(document, dict) or document.get("kind") not in {
            "Role",
            "ClusterRole",
        }:
            continue
        rules = document.get("rules", [])
        if not isinstance(rules, list):
            continue
        identity = f'{document["kind"]}/{document.get("metadata", {}).get("name", "<unnamed>")}'
        for rule_index, rule in enumerate(rules):
            if not isinstance(rule, dict):
                continue
            for field in ("apiGroups", "resources", "verbs"):
                values = rule.get(field, [])
                if not isinstance(values, list):
                    values = [values]
                if any(isinstance(value, str) and value == "*" for value in values):
                    raise AssertionError(
                        f"{source}: {identity} rule {rule_index} {field} contains '*'")


block_style_fixture = yaml.safe_load(
    """
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: block-style-wildcard-fixture
rules:
  - apiGroups:
      - rbac.authorization.k8s.io
    resources:
      - roles
    verbs:
      - get
      - "*"
"""
)
try:
    reject_rbac_wildcards([block_style_fixture], "block-style fixture")
except AssertionError:
    pass
else:
    raise SystemExit("block-style RBAC wildcard fixture was not rejected")

for argument in sys.argv[1:]:
    path = Path(argument)
    reject_rbac_wildcards(
        yaml.safe_load_all(path.read_text(encoding="utf-8")),
        str(path),
    )
PY
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
require_literal "$MANIFEST_DIR/namespace.yaml" "fixed control-plane labels must be restored"
for namespace_label in \
  "pod-security.kubernetes.io/enforce: restricted" \
  "pod-security.kubernetes.io/audit: restricted" \
  "pod-security.kubernetes.io/warn: restricted"; do
  require_literal "$MANIFEST_DIR/namespace.yaml" "$namespace_label"
done
require_literal "$MANIFEST_DIR/serviceaccounts.yaml" "name: firemud-hosted-identity-controller"
require_literal "$MANIFEST_DIR/serviceaccounts.yaml" "name: firemud-hosted-identity-requester"
SERVICE_ACCOUNTS="$MANIFEST_DIR/serviceaccounts.yaml" python3 - <<'PY'
import os
from pathlib import Path

import yaml

service_accounts = {
    document["metadata"]["name"]: document
    for document in yaml.safe_load_all(
        Path(os.environ["SERVICE_ACCOUNTS"]).read_text(encoding="utf-8")
    )
}
assert service_accounts["firemud-hosted-identity-controller"][
    "automountServiceAccountToken"
] is True
assert service_accounts["firemud-hosted-identity-requester"][
    "automountServiceAccountToken"
] is False
PY
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
forbid_literal "$CRD" "!has(oldSelf.desiredState)"
require_literal "$CRD" "self.desiredState == oldSelf.desiredState || (oldSelf.desiredState == 'Active' && self.desiredState == 'Retired')"
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
require_literal "$CRD" "pattern: '^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$'"
CRD="$CRD" python3 - <<'PY'
import os
from pathlib import Path

import yaml

source = Path(os.environ["CRD"]).read_text(encoding="utf-8")
assert source.count("&consumer_status_schema") == 1
assert source.count("*consumer_status_schema") == 4
crd = yaml.safe_load(source)
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
hostname = schema["properties"]["status"]["properties"]["profile"]["properties"]["hostname"]
assert hostname["maxLength"] == 253
assert hostname["pattern"] == (
    r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$"
)
status_properties = schema["properties"]["status"]["properties"]["profile"]["properties"]
assert status_properties["requestedHeadSha"]["maxLength"] == 40
assert status_properties["deployedHeadSha"]["maxLength"] == 40
consumer_properties = schema["properties"]["status"]["properties"]
consumer_schemas = [
    consumer_properties[name]
    for name in ("ingress", "telnet", "gatewayInternalWs", "tcpProxyBridge", "grpc")
]
assert all(value == consumer_schemas[0] for value in consumer_schemas[1:])
PY

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
  namespaces/status \
  namespaces/finalize \
  "request.subResource == ''" \
  "request.subResource in ['status', 'finalize']" \
  system:serviceaccount:kube-system:namespace-controller \
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
policy_names = set(policies)
binding_policy_names = [
    binding.get("spec", {}).get("policyName") for binding in binding_documents
]
assert all(isinstance(name, str) and name for name in binding_policy_names)
assert len(binding_policy_names) == len(set(binding_policy_names))
assert len(binding_policy_names) == len(policy_names)
assert set(binding_policy_names) == policy_names
break_glass = "request.userInfo.groups.exists(group, group == 'system:masters')"
namespace_controller = "system:serviceaccount:kube-system:namespace-controller"
namespace_delete_break_glass = (
    f"(request.userInfo.username == '{namespace_controller}' && "
    "request.operation == 'DELETE')"
)
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
    f"{break_glass} || {namespace_delete_break_glass} || "
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
assert namespace_delete_break_glass in main_authorization
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
subresource_expressions = [
    validation["expression"]
    for validation in policies["firemud-hosted-identity-subresources"]["spec"]["validations"]
]
subresource_preservation = next(
    expression
    for expression in subresource_expressions
    if "object.spec.desiredState == oldObject.spec.desiredState" in expression
)
for required in (
    "request.subResource != 'status'",
    "object.spec.desiredState == oldObject.spec.desiredState",
    "has(object.metadata.labels) == has(oldObject.metadata.labels)",
    "(!has(object.metadata.labels) || object.metadata.labels == oldObject.metadata.labels)",
    "has(object.metadata.annotations) == has(oldObject.metadata.annotations)",
    "(!has(object.metadata.annotations) || object.metadata.annotations == oldObject.metadata.annotations)",
    "has(object.metadata.finalizers) == has(oldObject.metadata.finalizers)",
    "(!has(object.metadata.finalizers) || object.metadata.finalizers == oldObject.metadata.finalizers)",
):
    assert required in subresource_preservation, required


def status_subresource_update_accepted(new_object, old_object):
    if new_object["spec"]["desiredState"] != old_object["spec"]["desiredState"]:
        return False
    return all(
        (field in new_object["metadata"]) == (field in old_object["metadata"])
        and (
            field not in new_object["metadata"]
            or new_object["metadata"][field] == old_object["metadata"][field]
        )
        for field in ("labels", "annotations", "finalizers")
    )


status_old = {
    "metadata": {
        "labels": {"example.test/label": "preserved"},
        "annotations": {"example.test/annotation": "preserved"},
        "finalizers": ["platform.firemud.dev/hosted-environment-identity"],
    },
    "spec": {"desiredState": "Active"},
    "status": {"phase": "Pending"},
}
status_only_update = {
    "metadata": dict(status_old["metadata"]),
    "spec": dict(status_old["spec"]),
    "status": {"phase": "Ready"},
}
assert status_subresource_update_accepted(status_only_update, status_old)
for field, changed in (
    ("labels", {"example.test/label": "changed"}),
    ("annotations", {"example.test/annotation": "changed"}),
    ("finalizers", []),
):
    metadata_mutation = {
        **status_only_update,
        "metadata": {**status_only_update["metadata"], field: changed},
    }
    assert not status_subresource_update_accepted(metadata_mutation, status_old)
    metadata_removal = {
        **status_only_update,
        "metadata": {
            key: value
            for key, value in status_only_update["metadata"].items()
            if key != field
        },
    }
    assert not status_subresource_update_accepted(metadata_removal, status_old)
desired_state_mutation = {
    **status_only_update,
    "spec": {"desiredState": "Retired"},
}
assert not status_subresource_update_accepted(desired_state_mutation, status_old)
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
    "object.metadata.finalizers.size() ==",
    "oldObject.metadata.finalizers.size() ==",
    "f != 'platform.firemud.dev/hosted-environment-identity'",
    "f == 'platform.firemud.dev/hosted-environment-identity'",
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
assert "object.metadata.labels.size() ==" not in role_expression
assert "object.metadata.labels.all(k," in role_expression
assert "!k.startsWith('firemud.dev/')" in role_expression
assert "object.rules.size() == 7" in role_expression
assert "object.rules.size() == 6" in role_expression
assert "'firemud-grpc-ca'" not in role_expression
assert "r.resources == ['certificaterequests']" in role_expression
assert "r.verbs == ['list']" in role_expression
normalized_role_expression = " ".join(role_expression.split())
for guard in (
    "(!has(r.apiGroups) || r.apiGroups.all(group, group != '*'))",
    "(!has(r.resources) || r.resources.all(resource, resource != '*'))",
    "(!has(r.verbs) || r.verbs.all(verb, verb != '*'))",
):
    assert guard in normalized_role_expression
assert "(!has(r.nonResourceURLs) || r.nonResourceURLs.size() == 0)" in (
    normalized_role_expression
)


def policy_rule_has_no_wildcards_or_non_resource_urls(rule):
    return all(
        value != "*"
        for field in ("apiGroups", "resources", "verbs")
        for value in rule.get(field, [])
    ) and not rule.get("nonResourceURLs", [])


assert policy_rule_has_no_wildcards_or_non_resource_urls({})
assert policy_rule_has_no_wildcards_or_non_resource_urls(
    {"apiGroups": [], "resources": [], "verbs": [], "nonResourceURLs": []}
)
for unsafe_rule in (
    {"apiGroups": ["*"]},
    {"resources": ["*"]},
    {"verbs": ["*"]},
    {"nonResourceURLs": ["/healthz"]},
):
    assert not policy_rule_has_no_wildcards_or_non_resource_urls(unsafe_rule)
assert (
    "r.apiGroups == [''] && r.resources == ['services', 'pods'] && "
    "r.verbs == ['get', 'list', 'watch'] && "
    "(!has(r.resourceNames) || r.resourceNames.size() == 0)"
) in normalized_role_expression
binding_expression = policies["firemud-hosted-identity-scope-rolebindings"]["spec"]["validations"][0]["expression"]
assert "(request.userInfo.username == 'system:serviceaccount:firemud-system:firemud-hosted-identity-controller' &&" in binding_expression
assert "object.metadata.labels.size() ==" not in binding_expression
assert "object.metadata.labels.all(k," in binding_expression
assert "!k.startsWith('firemud.dev/')" in binding_expression
assert "object.subjects.size() == 1" in binding_expression


def controller_scope_labels_are_valid(labels, required):
    return all(labels.get(key) == value for key, value in required.items()) and all(
        not key.startswith("firemud.dev/") or key in required for key in labels
    )


role_labels = {
    "app.kubernetes.io/name": "hosted-environment-identity-controller",
    "app.kubernetes.io/component": "controller-scope",
    "app.kubernetes.io/part-of": "firemud",
    "firemud.dev/managed-by": "hosted-identity-controller",
    "firemud.dev/identity-name": "pr-42",
    "firemud.dev/environment-class": "pr-preview",
}
binding_labels = {
    key: role_labels[key]
    for key in (
        "app.kubernetes.io/name",
        "app.kubernetes.io/component",
        "app.kubernetes.io/part-of",
        "firemud.dev/managed-by",
        "firemud.dev/identity-name",
    )
}
assert controller_scope_labels_are_valid(
    {**role_labels, "example.test/owner": "platform"}, role_labels
)
assert not controller_scope_labels_are_valid(
    {**role_labels, "firemud.dev/unowned": "drift"}, role_labels
)
assert controller_scope_labels_are_valid(
    {**binding_labels, "example.test/owner": "platform"}, binding_labels
)
assert not controller_scope_labels_are_valid(
    {**binding_labels, "firemud.dev/unowned": "drift"}, binding_labels
)
namespace_validation = policies["firemud-hosted-system-namespace-guard"]["spec"]["validations"][0]
namespace_expression = namespace_validation["expression"]
namespace_rule = policies["firemud-hosted-system-namespace-guard"]["spec"]["matchConstraints"]["resourceRules"][0]
assert namespace_validation["message"] == (
    "firemud-system and retained identity Namespace CREATE/DELETE operations require "
    "system:masters; only firemud-system permits no-op updates, and ordinary retained "
    "identity lifecycle is controller-managed"
)
assert namespace_rule["operations"] == ["CREATE", "UPDATE", "DELETE"]
assert namespace_rule["resources"] == ["namespaces", "namespaces/status", "namespaces/finalize"]
assert namespace_expression.startswith(f"({break_glass} &&")
assert "(request.operation == 'DELETE' ? request.name : object.metadata.name)" in namespace_expression
assert "'^(firemud-system|dev-identity|pr-[1-9][0-9]*-identity)$'" in namespace_expression
assert "request.subResource == ''" in namespace_expression
assert "request.subResource in ['status', 'finalize']" in namespace_expression
assert "request.operation == 'UPDATE'" in namespace_expression
namespace_noop_update_expression = namespace_expression.split(
    "(request.operation == 'UPDATE' &&", 1
)[1].split(
    "(request.userInfo.username == 'system:serviceaccount:kube-system:namespace-controller'",
    1,
)[0]
assert "request.name == 'firemud-system'" in namespace_noop_update_expression
assert "dev-identity" not in namespace_noop_update_expression
assert "pr-[1-9]" not in namespace_noop_update_expression
assert "system:serviceaccount:kube-system:namespace-controller" in namespace_expression
assert "(request.subResource == 'finalize' || object.spec == oldObject.spec)" in namespace_expression
assert "request.userInfo.username == 'system:serviceaccount:firemud-system:firemud-hosted-identity-controller'" in namespace_expression
assert "oldObject.metadata.labels['firemud.dev/retention'] == 'retained'" in namespace_expression
assert "object.metadata.labels['firemud.dev/retention'] == 'retained'" in namespace_expression
assert "oldObject.metadata.labels.size() ==" not in namespace_expression
assert "object.metadata.labels.size() ==" not in namespace_expression
assert "oldObject.metadata.labels.all(k," in namespace_expression
assert "object.metadata.labels.all(k," in namespace_expression
assert "!k.startsWith('firemud.dev/')" in namespace_expression


def retained_identity_namespace_labels_are_valid(namespace_name, labels):
    identity_name = (
        "dev-demo" if namespace_name == "dev-identity" else namespace_name.removesuffix("-identity")
    )
    environment_class = "dev-demo-cluster" if namespace_name == "dev-identity" else "pr-preview"
    required = {
        "firemud.dev/managed-by": "hosted-identity-controller",
        "firemud.dev/identity-name": identity_name,
        "firemud.dev/environment-class": environment_class,
        "firemud.dev/retention": "retained",
    }
    return all(labels.get(key) == value for key, value in required.items()) and all(
        not key.startswith("firemud.dev/") or key in required for key in labels
    )


retained_namespace_labels = {
    "firemud.dev/managed-by": "hosted-identity-controller",
    "firemud.dev/identity-name": "pr-42",
    "firemud.dev/environment-class": "pr-preview",
    "firemud.dev/retention": "retained",
}
assert retained_identity_namespace_labels_are_valid(
    "pr-42-identity",
    {
        **retained_namespace_labels,
        "kubernetes.io/metadata.name": "pr-42-identity",
        "example.test/owner": "platform",
    },
)
assert not retained_identity_namespace_labels_are_valid(
    "pr-42-identity", {**retained_namespace_labels, "firemud.dev/unowned": "drift"}
)
assert not retained_identity_namespace_labels_are_valid(
    "pr-42-identity", {**retained_namespace_labels, "firemud.dev/retention": "ephemeral"}
)
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
assert "request.userInfo.username == 'system:serviceaccount:firemud-system:firemud-hosted-identity-controller' &&" in secret_match
assert "request.namespace.matches('^(dev|dev-identity|pr-[1-9][0-9]*|pr-[1-9][0-9]*-identity)$')" in normalized_secret_match
assert "request.operation == 'DELETE'" in secret_match
assert "request.operation != 'DELETE'" in secret_match
assert "request.name == 'firemud-grpc-tls'" in secret_match
assert "object.metadata.name == 'firemud-grpc-tls'" in secret_match

secret_expressions = [
    validation["expression"]
    for validation in secret_policy["spec"]["validations"]
]
controller_secret_expression = next(
    expression
    for expression in secret_expressions
    if "system:serviceaccount:firemud-system:firemud-hosted-identity-controller" in expression
)
normalized_controller_secret_expression = " ".join(controller_secret_expression.split())
assert "object.metadata.labels['firemud.dev/role'] in ['ingress', 'telnet', 'gateway-internal-ws', 'tcp-proxy-bridge', 'grpc']" in controller_secret_expression
assert "object.metadata.name == 'firemud-grpc-tls'" in controller_secret_expression
assert normalized_controller_secret_expression.count("request.name.startsWith(") == 3
assert normalized_controller_secret_expression.count("object.metadata.name.startsWith(") == 2
assert "request.namespace.size() - 9" in normalized_controller_secret_expression


def canonical_environment_prefix(namespace):
    if namespace in ("dev", "dev-identity"):
        return "dev-"
    if namespace.endswith("-identity"):
        return f"{namespace[:-9]}-"
    return f"{namespace}-"


def canonical_primary_name(namespace, name):
    prefix = canonical_environment_prefix(namespace)
    return name.startswith(prefix) and name[len(prefix) :] in {
        "tls",
        "telnet-tls",
        "gateway-internal-ws",
        "tcp-proxy-bridge",
    }


assert canonical_primary_name("dev-identity", "dev-tls")
assert canonical_primary_name("pr-42", "pr-42-telnet-tls")
assert canonical_primary_name("pr-42-identity", "pr-42-gateway-internal-ws")
assert not canonical_primary_name("pr-42-identity", "pr-43-gateway-internal-ws")
assert not canonical_primary_name("dev-identity", "pr-42-tls")
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

certificate_policy = policies["firemud-hosted-identity-certificate-boundary"]
certificate_namespace_match = " ".join(
    certificate_policy["spec"]["matchConditions"][0]["expression"].split()
)
assert certificate_namespace_match.startswith(
    "request.userInfo.username == 'system:serviceaccount:firemud-system:firemud-hosted-identity-controller' ||"
)
assert "request.namespace == 'dev-identity'" in certificate_namespace_match
assert "request.namespace.matches('^pr-[1-9][0-9]*-identity$')" in certificate_namespace_match
certificate_match = " ".join(
    certificate_policy["spec"]["validations"][0]["expression"].split()
)
controller_certificate_expression = certificate_match.split(
    "(request.userInfo.username == 'system:serviceaccount:firemud-system:firemud-hosted-identity-controller'",
    1,
)[1].split(
    "(request.userInfo.username == 'system:serviceaccount:cert-manager:cert-manager'",
    1,
)[0]
assert "request.namespace == 'dev-identity'" in controller_certificate_expression
assert "request.namespace.matches('^pr-[1-9][0-9]*-identity$')" in controller_certificate_expression
assert "(request.operation == 'DELETE' &&" in certificate_match
controller_delete_expression = controller_certificate_expression.split(
    "(request.operation == 'DELETE' &&", 1
)[1].split(
    "(request.operation != 'DELETE' &&", 1
)[0]
assert "firemud-grpc-tls" not in controller_delete_expression
assert "request.name.startsWith(" in controller_delete_expression
assert "(request.operation != 'DELETE' && has(object.metadata.labels)" in certificate_match
assert (
    "object.metadata.labels['firemud.dev/role'] in "
    "['ingress', 'telnet', 'gateway-internal-ws', 'tcp-proxy-bridge']"
) in certificate_match
assert "object.metadata.labels['firemud.dev/role'] in ['ingress', 'telnet', 'gateway-internal-ws', 'tcp-proxy-bridge', 'grpc']" not in certificate_match
assert "object.metadata.name == 'firemud-grpc-tls'" not in certificate_match

certificate_expressions = [
    validation["expression"]
    for validation in certificate_policy["spec"]["validations"]
]
controller_non_delete_expression = certificate_expressions[0].split(
    "(request.operation != 'DELETE' &&", 1
)[1].split(
    "(request.userInfo.username == 'system:serviceaccount:cert-manager:cert-manager'",
    1,
)[0]
assert "firemud-grpc-tls" not in controller_non_delete_expression
assert "'grpc'" not in controller_non_delete_expression
assert "has(object.spec.isCA)" in controller_non_delete_expression
assert "object.spec.isCA == false" in controller_non_delete_expression
assert "(!has(object.spec.commonName) || object.spec.commonName == '')" in controller_non_delete_expression
assert "(!has(object.spec.ipAddresses) || object.spec.ipAddresses.size() == 0)" in controller_non_delete_expression
assert "(!has(object.spec.emailAddresses) || object.spec.emailAddresses.size() == 0)" in controller_non_delete_expression
cert_manager_status_expression = certificate_expressions[0].split(
    "(request.userInfo.username == 'system:serviceaccount:cert-manager:cert-manager'",
    1,
)[1]
assert "firemud-grpc-tls" not in cert_manager_status_expression
namespace_controller_expression = certificate_match.split(
    "(request.userInfo.username == 'system:serviceaccount:kube-system:namespace-controller'",
    1,
)[1].split(
    "(request.userInfo.username == 'system:serviceaccount:firemud-system:firemud-hosted-identity-controller'",
    1,
)[0]
assert "firemud-grpc-tls" in namespace_controller_expression
profile_expression = next(
    expression for expression in certificate_expressions if "gateway-internal-ws" in expression
)
normalized_profile_expression = " ".join(profile_expression.split())
assert "spring-cloud-gateway-mtls." in profile_expression
assert ".svc.cluster.local" in profile_expression
assert "spiffe://firemud/ns/" in profile_expression
assert "/sa/tcp-proxy-service" in profile_expression
assert "has(object.spec.encodeUsagesInRequest)" in profile_expression
assert "object.spec.encodeUsagesInRequest == true" in profile_expression
assert "object.spec.dnsNames == [((request.namespace == 'dev-identity') ? 'dev' : request.namespace.substring(0, request.namespace.size() - 9)) + '.preview.firedevops.net']" in profile_expression
assert (
    "object.metadata.labels['firemud.dev/role'] in ['ingress', 'telnet'] && "
    "object.spec.dnsNames == [((request.namespace == 'dev-identity') ? 'dev' : "
    "request.namespace.substring(0, request.namespace.size() - 9)) + "
    "'.preview.firedevops.net'] && "
    "(!has(object.spec.uris) || object.spec.uris.size() == 0) &&"
) in normalized_profile_expression
assert "object.spec.issuerRef.name == 'letsencrypt-prod'" in profile_expression
assert "object.spec.issuerRef.name == 'firemud-ca-issuer'" in profile_expression
assert "object.spec.issuerRef.kind == 'ClusterIssuer'" in profile_expression
assert "object.spec.issuerRef.group == 'cert-manager.io'" in profile_expression
assert (
    "object.metadata.labels['firemud.dev/role'] != 'tcp-proxy-bridge' || "
    "((!has(object.spec.dnsNames) || object.spec.dnsNames.size() == 0) &&"
) in normalized_profile_expression
assert (
    "(object.metadata.labels['firemud.dev/role'] != 'ingress' && "
    "object.metadata.labels['firemud.dev/role'] != 'telnet') || "
    "object.spec.usages == ['digital signature', 'key encipherment', 'server auth']"
) in normalized_profile_expression
assert "object.spec.usages == ['digital signature', 'key encipherment', 'server auth']" in profile_expression
assert "object.spec.usages == ['digital signature', 'key encipherment', 'client auth']" in profile_expression
for private_key_profile in (
    "object.spec.privateKey.algorithm == 'RSA'",
    "object.spec.privateKey.size == 2048",
    "object.spec.privateKey.encoding == 'PKCS8'",
):
    assert private_key_profile in certificate_match
PY
for phase in Pending Provisioning WaitingForCertificate RuntimeAbsent Syncing Verifying Ready Degraded Blocked Retiring Retired; do
  require_literal "$CRD" "- $phase"
done
for status_field in sourceGeneration sourceObjectGeneration spkiSha256; do
  require_literal "$CRD" "$status_field:"
done

check_rbac_wildcards "$MANIFEST_DIR"/*.yaml
require_literal "$RBAC" "name: firemud-hosted-identity-controller-namespace-lifecycle"
require_literal "$RBAC" "RBAC cannot constrain CREATE with resourceNames"
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
REQUESTER_RBAC="$requester_rbac" python3 - <<'PY'
import os

import yaml

role = yaml.safe_load(os.environ["REQUESTER_RBAC"])
assert role["rules"] == [
    {
        "apiGroups": ["platform.firemud.dev"],
        "resources": ["hostedenvironmentidentities"],
        "verbs": ["get", "list", "create", "update", "patch", "delete"],
    }
]
PY
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
    if rule.get("resources") == ["hostedenvironmentidentities/status"]
)
assert subresources["verbs"] == ["get", "update", "patch"]
assert not any(
    "hostedenvironmentidentities/finalizers" in rule.get("resources", [])
    for rule in role["rules"]
)
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
CLUSTER_ROLE_RBAC="$cluster_role_rbac" python3 - <<'PY'
import os

import yaml

cluster_role = yaml.safe_load(os.environ["CLUSTER_ROLE_RBAC"])
namespace_rule = next(
    rule
    for rule in cluster_role["rules"]
    if rule.get("resources") == ["namespaces"]
)
assert namespace_rule["apiGroups"] == [""]
assert namespace_rule["verbs"] == ["get", "create", "delete"]
PY
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
for forbidden_scope_permission in namespaces secrets certificates; do
  if grep -Fqi -- "$forbidden_scope_permission" <<<"$scope_writer_rbac"; then
    fail "scope-writer ClusterRole has forbidden $forbidden_scope_permission access"
  fi
done

for text_value in \
  "serviceAccountName: firemud-hosted-identity-controller" \
  "ghcr.io/benhook1013/firemud-hosted-identity-controller@sha256:__IMAGE_DIGEST_REQUIRED__" \
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
forbidden_image_repository="ghcr.io/benhook1013/hosted-environment-identity-controller"
forbid_literal "$DEPLOYMENT" "$forbidden_image_repository"
forbid_literal "$BOOTSTRAP" "$forbidden_image_repository"
require_literal "$BOOTSTRAP" "ghcr.io/benhook1013/firemud-hosted-identity-controller@sha256:"
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
  'port: 53' 'port: 443' 'port: 6443' \
  'port: 32001' 'port: 32016' 'port: 6565' 'firemud.dev/preview: "true"' \
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
api_rules = [
    rule for rule in policy["spec"]["egress"]
    if any(port.get("port") == 6443 for port in rule.get("ports", []))
]
assert len(api_rules) == 1, "controller Kubernetes API 6443 egress rule is missing"
assert any(port.get("port") == 443 for port in api_rules[0]["ports"]), (
    "controller Kubernetes API egress must retain 443 alongside 6443"
)
telnet_rules = [
    rule
    for rule in policy["spec"]["egress"]
    if rule.get("ports")
    == [{"protocol": "TCP", "port": port} for port in range(32000, 32017)]
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
    for target in rule.get("to", [])
    if target.get("podSelector", {}).get("matchLabels") == {
        "app": "spring-cloud-gateway"
    }
]
assert not gateway_targets, "destination-broad TCP/443 makes a Gateway selector rule inert"
PY
for text_value in \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR \
  'gh attestation verify' \
  '--deny-self-hosted-runners' \
  '--predicate-type' \
  'https://slsa.dev/provenance/v1' \
  '--repo' \
  '--signer-workflow' \
  '--cert-identity' \
  --server-side \
  --field-manager \
  'kubectl kustomize' \
  'rollout status' \
  'kubectl auth can-i' \
  'paused|observe|active' \
  --grpc-trust-anchor-sha256 \
  'GRPC_TRUST_ANCHOR_SHA256" =~ ^[0-9a-f]{64}$' \
  'integer between 1 and 3600' \
  '@sha256:[0-9a-f]{64}'; do
  require_literal "$BOOTSTRAP" "$text_value"
done
BOOTSTRAP="$BOOTSTRAP" python3 - <<'PY'
import os
from pathlib import Path

source = Path(os.environ["BOOTSTRAP"]).read_text(encoding="utf-8")
verify = source.index("gh attestation verify")
assert "--deny-self-hosted-runners" in source[verify:]
assert "--predicate-type" in source[verify:]
assert "--repo" in source[verify:]
assert "--signer-workflow" in source[verify:]
assert "--cert-identity" in source[verify:]
assert "@sha256:" in source[verify:]
policy_apply = source.index('-f "$namespace_guard_policy_manifest"')
binding_apply = source.index('-f "$namespace_guard_binding_manifest"', policy_apply)
policy_read = source.index("namespace_guard_failure_policy=", binding_apply)
full_apply = source.index('-f "$temporary_manifest"', policy_read)
assert policy_apply < binding_apply < policy_read < full_apply
PY
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
require_literal "$BOOTSTRAP" "python3 -c 'import yaml'"
require_literal "$BOOTSTRAP" "yaml.safe_load_all"
require_literal "$BOOTSTRAP" "if len(matches) != 1:"
require_literal "$BOOTSTRAP" "yaml.safe_dump(matches[0], sort_keys=False)"
forbid_literal "$BOOTSTRAP" 'awk -v expected_kind='
# shellcheck disable=SC2016 # Match the literal bootstrap comparison.
require_literal "$BOOTSTRAP" '[[ "$binding_actions" == "Deny" ]]'
for forbidden_command in 'kubectl delete' 'kubectl apply --all' 'sed -i'; do
  forbid_literal "$BOOTSTRAP" "$forbidden_command"
done
# shellcheck disable=SC2016 # Match literal portable-rendering fragments.
for portable_render_fragment in \
  'temporary_rendered_manifest="$(mktemp)"' \
  'sed "$@" "$temporary_manifest" >"$temporary_rendered_manifest"' \
  'mv -f "$temporary_rendered_manifest" "$temporary_manifest"'; do
  require_literal "$BOOTSTRAP" "$portable_render_fragment"
done
require_literal "$ADMISSION" "'firemud-grpc-tls-previous'] &&"
require_literal "$ADMISSION" "request.userInfo.username == 'system:serviceaccount:firemud-system:firemud-hosted-identity-controller' ||"
require_literal "$BOOTSTRAP" "crd_deadline=\$((SECONDS + WAIT_SECONDS))"
# shellcheck disable=SC2016 # Match the literal bootstrap default.
require_literal "$BOOTSTRAP" 'FIREMUD_HOSTED_IDENTITY_BOOTSTRAP_TIMEOUT_SECONDS:-480'
require_literal "$BOOTSTRAP" 'deployment wait timeout (default: 480)'
# shellcheck disable=SC2016 # Match the literal bootstrap expression.
require_literal "$BOOTSTRAP" 'crd_remaining=$((crd_deadline - SECONDS))'
# shellcheck disable=SC2016 # Match the literal bootstrap expression.
require_literal "$BOOTSTRAP" '--request-timeout="${crd_remaining}s"'
require_literal "$BOOTSTRAP" 'while :; do'
# shellcheck disable=SC2016 # Match the literal bootstrap expression.
require_literal "$BOOTSTRAP" '[[ "$crd_established" == "True" ]]'
forbid_literal "$BOOTSTRAP" 'if ((SECONDS >= crd_deadline)); then'
require_literal "$BOOTSTRAP" 'sleep 1'
for ca_proof in \
  'get secret firemud-grpc-ca' \
  "ca.crt\\nca.key" \
  "openssl x509 -outform DER" \
  "openssl x509 -pubkey -noout" \
  "openssl rsa -pubin -noout" \
  "openssl pkcs8 -nocrypt -out /dev/null" \
  "openssl rsa -check -noout" \
  "openssl pkey -pubout -outform DER" \
  'ca.crt public key must be RSA' \
  'ca.key must be an unencrypted PKCS8 private key' \
  'ca.key must be RSA' \
  'does not match the configured fingerprint' \
  'ca.crt and ca.key do not match'; do
  require_literal "$BOOTSTRAP" "$ca_proof"
done
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
assert (
    'expect_can_i yes --as="$controller_sa" --namespace="$CONTROL_NAMESPACE" \\\n'
    '  patch hostedenvironmentidentities.platform.firemud.dev'
) in source
assert (
    'expect_can_i yes --as="$controller_sa" --namespace="$CONTROL_NAMESPACE" \\\n'
    '  get hostedenvironmentidentities.platform.firemud.dev'
) in source
assert (
    'expect_can_i yes --as="$controller_sa" --namespace="$CONTROL_NAMESPACE" \\\n'
    '  update hostedenvironmentidentities.platform.firemud.dev/status'
) in source
for forbidden_controller_root_operation in ("create", "update", "delete"):
    assert (
        f'expect_can_i no --as="$controller_sa" --namespace="$CONTROL_NAMESPACE" \\\n'
        f'  {forbidden_controller_root_operation} hostedenvironmentidentities.platform.firemud.dev'
    ) in source
assert source.count("expect_can_i ") == 14
assert "hostedenvironmentidentities/finalizers.platform.firemud.dev" not in source
assert 'rendered_activation_mode="$(sed -n ' in source
assert '[[ "$rendered_activation_mode" == "$initial_activation_mode" ]]' in source
assert '[[ "$rendered_activation_mode" == "$ACTIVATION_MODE" ]]' in source
assert source.count('[[ "$rendered_activation_mode" == "$initial_activation_mode" ]]') == 1
cleanup_trap = source.index("trap cleanup EXIT")
first_temporary_file = source.index('temporary_manifest="$(mktemp)"')
assert cleanup_trap < first_temporary_file
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
          image: ghcr.io/benhook1013/firemud-hosted-identity-controller@sha256:__IMAGE_DIGEST_REQUIRED__
          env:
            - name: FIREMUD_HOSTED_IDENTITY_GRPC_TRUST_ANCHOR_SHA256
              value: __GRPC_TRUST_ANCHOR_SHA256_REQUIRED__
            - name: FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE
              value: __ACTIVATION_MODE_REQUIRED__
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata:
  name: firemud-hosted-system-namespace-guard
spec:
  failurePolicy: Fail
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicyBinding
metadata:
  name: firemud-hosted-system-namespace-guard
spec:
  policyName: firemud-hosted-system-namespace-guard
  validationActions:
    - Deny
YAML
  if [[ "${FAKE_DUPLICATE_NAMESPACE_GUARD_POLICY:-0}" == 1 ]]; then
    cat <<'YAML'
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata:
  name: firemud-hosted-system-namespace-guard
spec:
  failurePolicy: Fail
YAML
  fi
  exit 0
fi
if [[ "${1:-}" == "auth" && "${2:-}" == "whoami" ]]; then
  record_event operator-whoami
  if [[ "${FAKE_OPERATOR_WHOAMI_ERROR:-0}" == 1 ]]; then
    printf 'simulated operator identity lookup failure\n' >&2
    exit 1
  fi
  printf '%s\n' "${FAKE_OPERATOR_GROUPS:-system:authenticated
system:masters}"
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
  if [[ "$(grep -c '^kind:' "$manifest")" == 1 ]] &&
    grep -q '^kind: ValidatingAdmissionPolicy$' "$manifest"; then
    [[ "${FAKE_EVENT_LOG:-}" == *active-events ]] || record_event guard-policy
    exit 0
  fi
  if [[ "$(grep -c '^kind:' "$manifest")" == 1 ]] &&
    grep -q '^kind: ValidatingAdmissionPolicyBinding$' "$manifest"; then
    [[ "${FAKE_EVENT_LOG:-}" == *active-events ]] || record_event guard-binding
    exit 0
  fi
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
    *'go-template='*)
      if [[ "${FAKE_CA_KEY_LIST_ERROR:-0}" == 1 ]]; then
        printf 'simulated CA data-key listing failure\n' >&2
        exit 1
      fi
      printf 'ca.crt\nca.key\n'
      ;;
    *'{.data.ca\.crt}'*) base64 --wrap=0 <"$FAKE_CA_CERT" ;;
    *'{.data.ca\.key}'*) base64 --wrap=0 <"$FAKE_CA_KEY" ;;
    *) exit 2 ;;
  esac
  exit 0
fi
if [[ "${1:-}" == "get" && "${2:-}" == "validatingadmissionpolicy" ]]; then
  if [[ "${FAKE_MISSING_POLICY:-0}" == 1 ]]; then
    echo "not found" >&2
    exit 1
  fi
  printf 'Fail\n'
  exit 0
fi
if [[ "${1:-}" == "get" && "${2:-}" == "validatingadmissionpolicybinding" ]]; then
  if [[ "${FAKE_MISSING_BINDING:-0}" == 1 ]]; then
    echo "not found" >&2
    exit 1
  fi
  printf '%s\n' "${FAKE_BINDING_ACTIONS:-Deny}"
  exit 0
fi
if [[ "${1:-}" == "auth" && "${2:-}" == "can-i" ]]; then
  record_event auth-check
  if [[ " $* " == *" --as=system:serviceaccount:firemud-system:firemud-hosted-identity-controller "* ]] &&
    [[ "$*" =~ (create|update|delete)[[:space:]]hostedenvironmentidentities\.platform\.firemud\.dev($|[[:space:]]) ]]; then
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
cat >"$bootstrap_test_dir/gh" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "${1:-}" == "attestation" && "${2:-}" == "verify" ]]; then
  printf '%s\n' "$*" >>"${FAKE_ATTESTATION_LOG:?}"
  if [[ "$*" == *"--source-ref refs/heads/develop"* && "${FAKE_ATTESTATION_DEVELOP_FAIL:-0}" == 1 ]]; then
    exit 1
  fi
  [[ "${FAKE_ATTESTATION_FAIL:-0}" != 1 ]] || exit 1
  printf '%s\n' "${FAKE_ATTESTATION_OUTPUT:-verified}"
  exit 0
fi
printf 'unexpected fake gh invocation: %s\n' "$*" >&2
exit 2
SH
chmod +x "$bootstrap_test_dir/gh"
attestation_log="$bootstrap_test_dir/attestation-events"
export PATH="$bootstrap_test_dir:$PATH"
export FAKE_ATTESTATION_LOG="$attestation_log"
bootstrap_ca_cert="$bootstrap_test_dir/ca.crt"
bootstrap_ca_key="$bootstrap_test_dir/ca.key"
bootstrap_mismatched_ca_key="$bootstrap_test_dir/mismatched-ca.key"
bootstrap_ec_ca_cert="$bootstrap_test_dir/ec-ca.crt"
bootstrap_ec_ca_key="$bootstrap_test_dir/ec-ca.key"
bootstrap_pkcs1_ca_key="$bootstrap_test_dir/pkcs1-ca.key"
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "$bootstrap_ca_key" \
  -out "$bootstrap_ca_cert" \
  -days 1 \
  -subj '/CN=firemud-grpc-ca' \
  >/dev/null 2>&1 || fail "could not generate the RSA gRPC CA fixture"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out "$bootstrap_mismatched_ca_key" >/dev/null 2>&1 || \
  fail "could not generate the mismatched RSA CA key fixture"
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
  -keyout "$bootstrap_ec_ca_key" \
  -out "$bootstrap_ec_ca_cert" \
  -days 1 \
  -subj '/CN=firemud-grpc-ca' \
  >/dev/null 2>&1 || fail "could not generate the EC gRPC CA fixture"
openssl rsa -in "$bootstrap_ca_key" -traditional \
  -out "$bootstrap_pkcs1_ca_key" >/dev/null 2>&1 || \
  fail "could not generate the PKCS#1 gRPC CA key fixture"
bootstrap_image='ghcr.io/benhook1013/firemud-hosted-identity-controller@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
bootstrap_fingerprint="$(
  openssl x509 -in "$bootstrap_ca_cert" -outform DER |
    sha256sum |
    awk '{print $1}'
)"
[[ "$bootstrap_fingerprint" =~ ^[0-9a-f]{64}$ ]] || \
  fail "could not compute the fixture gRPC CA fingerprint"
legacy_bootstrap_events="$bootstrap_test_dir/legacy-events"
legacy_bootstrap_image='ghcr.io/benhook1013/hosted-environment-identity-controller@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
if ! FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 PATH="$bootstrap_test_dir:$PATH" \
  bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap rejected expected auth can-i no results: $(cat "$bootstrap_error")"
fi
require_literal "$bootstrap_output" "activation=paused"
legacy_attestation_count="$(wc -l <"$attestation_log")"
if FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 FAKE_EVENT_LOG="$legacy_bootstrap_events" \
  PATH="$bootstrap_test_dir:$PATH" bash "$BOOTSTRAP" --image "$legacy_bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted a legacy controller image repository"
fi
require_literal "$bootstrap_error" "--image must be the approved controller repository"
[[ ! -e "$legacy_bootstrap_events" ]] || fail "legacy controller image reached kubectl"
[[ "$(wc -l <"$attestation_log")" == "$legacy_attestation_count" ]] || \
  fail "legacy controller image reached gh attestation verification"
attestation_event="$(head -n 1 "$attestation_log")"
for attestation_argument in \
  "attestation verify oci://$bootstrap_image" \
  "--repo benhook1013/FireMUD" \
  "--bundle-from-oci" \
  "--signer-workflow github.com/benhook1013/FireMUD/.github/workflows/runtime-images.yml" \
  "--source-ref refs/heads/develop" \
  "--cert-identity https://github.com/benhook1013/FireMUD/.github/workflows/runtime-images.yml@refs/heads/develop" \
  "--predicate-type https://slsa.dev/provenance/v1" \
  "--deny-self-hosted-runners"; do
  [[ "$attestation_event" == *"$attestation_argument"* ]] || \
    fail "bootstrap attestation verification omitted $attestation_argument"
done
develop_failure_events="$bootstrap_test_dir/develop-failure-events"
if ! FAKE_ATTESTATION_DEVELOP_FAIL=1 FAKE_EVENT_LOG="$develop_failure_events" \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 PATH="$bootstrap_test_dir:$PATH" \
  bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap rejected a valid main attestation after develop failure"
fi
grep -Fq -- "--source-ref refs/heads/main" "$attestation_log" || \
  fail "bootstrap did not try the trusted main source ref after develop failure"
both_failure_events="$bootstrap_test_dir/both-failure-events"
if FAKE_ATTESTATION_FAIL=1 FAKE_EVENT_LOG="$both_failure_events" \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 PATH="$bootstrap_test_dir:$PATH" \
  bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted when both trusted attestations failed"
fi
[[ ! -e "$both_failure_events" ]] || fail "attestation failure reached kubectl"
no_gh_dir="$bootstrap_test_dir/no-gh"
mkdir -p "$no_gh_dir"
ln -s "$bootstrap_test_dir/kubectl" "$no_gh_dir/kubectl"
missing_gh_events="$bootstrap_test_dir/missing-gh-events"
if FAKE_EVENT_LOG="$missing_gh_events" FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$no_gh_dir:/usr/bin:/bin" bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted a missing gh attestation verifier"
fi
[[ ! -e "$missing_gh_events" ]] || fail "missing gh reached kubectl"
mktemp_failure_dir="$bootstrap_test_dir/mktemp-failure-bin"
mkdir -p "$mktemp_failure_dir"
ln -s "$bootstrap_test_dir/kubectl" "$mktemp_failure_dir/kubectl"
ln -s "$bootstrap_test_dir/gh" "$mktemp_failure_dir/gh"
cat >"$mktemp_failure_dir/mktemp" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
call_count=0
if [[ -f "${FAKE_MKTEMP_CALLS:?}" ]]; then
  call_count="$(<"$FAKE_MKTEMP_CALLS")"
fi
call_count=$((call_count + 1))
printf '%s' "$call_count" >"$FAKE_MKTEMP_CALLS"
if ((call_count == 2)); then
  exit 1
fi
temporary_path="$(/usr/bin/mktemp)"
printf '%s' "$temporary_path" >"${FAKE_MKTEMP_CREATED:?}"
printf '%s\n' "$temporary_path"
SH
chmod +x "$mktemp_failure_dir/mktemp"
mktemp_calls="$bootstrap_test_dir/mktemp-calls"
mktemp_created="$bootstrap_test_dir/mktemp-created"
if FAKE_MKTEMP_CALLS="$mktemp_calls" FAKE_MKTEMP_CREATED="$mktemp_created" \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$mktemp_failure_dir:/usr/bin:/bin" bash "$BOOTSTRAP" \
  --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted a partial temporary-file initialization"
fi
[[ "$(<"$mktemp_calls")" -eq 2 ]] || \
  fail "bootstrap did not reach the simulated second mktemp failure"
first_partial_temporary_file="$(<"$mktemp_created")"
[[ ! -e "$first_partial_temporary_file" ]] || \
  fail "bootstrap leaked its first temporary file after a later mktemp failed"
non_master_events="$bootstrap_test_dir/non-master-events"
if FAKE_OPERATOR_GROUPS='system:authenticated' FAKE_EVENT_LOG="$non_master_events" \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 PATH="$bootstrap_test_dir:$PATH" \
  bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted an operator outside system:masters"
fi
require_literal "$bootstrap_error" \
  "current Kubernetes operator identity must belong to system:masters before installing the namespace guard"
grep -Fxq operator-whoami "$non_master_events" || \
  fail "bootstrap did not inspect the current operator groups"
if grep -Eq '^(guard-policy|guard-binding|apply:)' "$non_master_events"; then
  fail "bootstrap installed resources for an operator outside system:masters"
fi
operator_lookup_error_events="$bootstrap_test_dir/operator-lookup-error-events"
if FAKE_OPERATOR_WHOAMI_ERROR=1 FAKE_EVENT_LOG="$operator_lookup_error_events" \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 PATH="$bootstrap_test_dir:$PATH" \
  bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted an unavailable operator identity lookup"
fi
require_literal "$bootstrap_error" "unable to verify the current Kubernetes operator identity"
if grep -Eq '^(guard-policy|guard-binding|apply:)' "$operator_lookup_error_events"; then
  fail "bootstrap installed resources after operator identity lookup failed"
fi
duplicate_guard_events="$bootstrap_test_dir/duplicate-guard-events"
if FAKE_DUPLICATE_NAMESPACE_GUARD_POLICY=1 \
  FAKE_EVENT_LOG="$duplicate_guard_events" \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 PATH="$bootstrap_test_dir:$PATH" \
  bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted duplicate rendered namespace guard policies"
fi
require_literal "$bootstrap_error" \
  "expected exactly one ValidatingAdmissionPolicy/firemud-hosted-system-namespace-guard"
if grep -Eq '^(guard-policy|guard-binding|apply:)' "$duplicate_guard_events"; then
  fail "bootstrap installed resources after duplicate rendered namespace guard policies"
fi
if FAKE_MISSING_POLICY=1 FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$bootstrap_test_dir:$PATH" bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted a missing admission policy"
fi
require_literal "$bootstrap_error" "admission policy lookup failed"
if FAKE_MISSING_BINDING=1 FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$bootstrap_test_dir:$PATH" bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted a missing admission policy binding"
fi
require_literal "$bootstrap_error" "admission policy binding lookup failed"
if FAKE_BINDING_ACTIONS='Deny Audit' FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$bootstrap_test_dir:$PATH" bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted admission policy binding actions other than exactly Deny"
fi
require_literal "$bootstrap_error" "must contain exactly validationActions Deny"
for invalid_wait_seconds in 0 3601 invalid 99999999999999999999; do
  if FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 PATH="$bootstrap_test_dir:$PATH" \
    bash "$BOOTSTRAP" --image "$bootstrap_image" \
    --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" \
    --wait-seconds "$invalid_wait_seconds" \
    >"$bootstrap_output" 2>"$bootstrap_error"; then
    fail "bootstrap accepted invalid --wait-seconds ${invalid_wait_seconds}"
  fi
  require_literal "$bootstrap_error" "--wait-seconds must be an integer between 1 and 3600"
done
if ! FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 PATH="$bootstrap_test_dir:$PATH" \
  bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --wait-seconds 3600 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap rejected the maximum valid --wait-seconds boundary: $(cat "$bootstrap_error")"
fi
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
[[ "${active_events[0]:-}" == "operator-whoami" ]] || \
  fail "active bootstrap did not verify the operator identity first"
[[ "${active_events[1]:-}" == "apply:paused" ]] || \
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
[[ "$auth_checks" -eq 14 ]] || fail "active bootstrap did not run all authorization probes"
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
key_list_error_event_log="$bootstrap_test_dir/key-list-error-events"
if FAKE_EVENT_LOG="$key_list_error_event_log" FAKE_CA_KEY_LIST_ERROR=1 \
  FAKE_CA_CERT="$bootstrap_ca_cert" FAKE_CA_KEY="$bootstrap_ca_key" \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$bootstrap_test_dir:$PATH" bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --activation-mode active --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted a failed gRPC CA data-key listing"
fi
require_literal "$bootstrap_error" "firemud-grpc-ca data-key listing failed"
if grep -Fxq -- "apply:active" "$key_list_error_event_log"; then
  fail "bootstrap applied active mode after the gRPC CA data-key listing failed"
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
ec_fingerprint="$(
  openssl x509 -in "$bootstrap_ec_ca_cert" -outform DER |
    sha256sum |
    awk '{print $1}'
)"
ec_event_log="$bootstrap_test_dir/ec-events"
if FAKE_EVENT_LOG="$ec_event_log" \
  FAKE_CA_CERT="$bootstrap_ec_ca_cert" FAKE_CA_KEY="$bootstrap_ec_ca_key" \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$bootstrap_test_dir:$PATH" bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$ec_fingerprint" --activation-mode active --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted an EC gRPC CA"
fi
require_literal "$bootstrap_error" "ca.crt public key must be RSA"
if grep -Fxq -- "apply:active" "$ec_event_log"; then
  fail "bootstrap applied active mode after an EC gRPC CA"
fi
ec_key_event_log="$bootstrap_test_dir/ec-key-events"
if FAKE_EVENT_LOG="$ec_key_event_log" \
  FAKE_CA_CERT="$bootstrap_ca_cert" FAKE_CA_KEY="$bootstrap_ec_ca_key" \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$bootstrap_test_dir:$PATH" bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --activation-mode active --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted an EC gRPC CA private key"
fi
require_literal "$bootstrap_error" "ca.key must be RSA"
if grep -Fxq -- "apply:active" "$ec_key_event_log"; then
  fail "bootstrap applied active mode after an EC gRPC CA private key"
fi
pkcs1_event_log="$bootstrap_test_dir/pkcs1-events"
if FAKE_EVENT_LOG="$pkcs1_event_log" \
  FAKE_CA_CERT="$bootstrap_ca_cert" FAKE_CA_KEY="$bootstrap_pkcs1_ca_key" \
  FIREMUD_HOSTED_IDENTITY_TRUSTED_OPERATOR=1 \
  PATH="$bootstrap_test_dir:$PATH" bash "$BOOTSTRAP" --image "$bootstrap_image" \
  --grpc-trust-anchor-sha256 "$bootstrap_fingerprint" --activation-mode active --wait-seconds 1 \
  >"$bootstrap_output" 2>"$bootstrap_error"; then
  fail "bootstrap accepted a non-PKCS8 gRPC CA private key"
fi
require_literal "$bootstrap_error" "ca.key must be an unencrypted PKCS8 private key"
if grep -Fxq -- "apply:active" "$pkcs1_event_log"; then
  fail "bootstrap applied active mode after a non-PKCS8 gRPC CA private key"
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


image_location = "Deployment/account-service.spec.template.spec.containers[0].image"
assert_rejected(
    lambda: validator._validate_image_reference(
        image_location,
        f"ghcr.io/benhook1013/account-service@sha256:{'a' * 64}",
        "pr-42-test",
    ),
    f"{image_location} uses a digest image reference; tagged images are required",
)


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
        "metadata": {
            "name": "tcp-proxy-service",
            "namespace": "pr-42",
            "labels": {
                **validator._expected_top_level_labels(),
                "app.kubernetes.io/instance": "pr-42",
            },
        },
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
    validator.inject_telnet_port(tcp_source, tcp_output, 32000, "pr-42")
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
check_rbac_wildcards "$rendered"

echo "hosted identity controller manifest contract passed"
