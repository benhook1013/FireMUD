#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export GAME_SESSION_FACTORY_QUALIFICATION_REPO_ROOT="$ROOT_DIR"

python3 - <<'PY'
import contextlib
import importlib.util
import io
import json
import os
import tempfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

root = Path(os.environ["GAME_SESSION_FACTORY_QUALIFICATION_REPO_ROOT"]).resolve()
script = root / "dev-tools/hosted/account-bootstrap/game-session-factory-qualification.py"
spec = importlib.util.spec_from_file_location("game_session_factory_qualification", script)
assert spec is not None and spec.loader is not None
qualification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qualification)

head = "a" * 40
uid = "12345678-1234-1234-1234-123456789abc"
namespace_uid = "22345678-1234-1234-1234-123456789abc"
node_id = "b" * 64
network_id = "c" * 64
secret_values = ("secret-data-must-not-escape", "token-value-must-not-escape", "password-value-must-not-escape")


class Runner:
    def __init__(self):
        self.resources_may_exist = False


class Identity:
    repository = "benhook1013/FireMUD"
    ref = "refs/heads/develop"
    event = "workflow_dispatch"
    sha = head
    run_id = "9911"
    attempt = "1"
    cluster_name = f"account-jwt-bootstrap-{run_id}-{attempt}-{sha[:8]}"
    run_directory = root / ".qualification-contract-run"


def make_inventory():
    inventory = {resource: [] for resource, _ in qualification.CLUSTER_INVENTORY_RESOURCES}
    inventory["namespaces"] = [
        {
            "apiVersion": "v1",
            "kind": "Namespace",
            "metadata": {"name": "kube-system", "uid": namespace_uid, "resourceVersion": "1"},
        }
    ]
    inventory["nodes"] = [
        {
            "apiVersion": "v1",
            "kind": "Node",
            "metadata": {"name": "kind-control-plane", "uid": uid, "resourceVersion": "2"},
        }
    ]
    inventory["secrets"] = [
        {
            "apiVersion": "v1",
            "kind": "Secret",
            "metadata": {
                "name": "fixture-secret",
                "namespace": "kube-system",
                "uid": "32345678-1234-1234-1234-123456789abc",
                "resourceVersion": "3",
                "annotations": {"token": secret_values[1]},
            },
            "type": "Opaque",
            "data": {"credential": secret_values[0]},
            "stringData": {"password": secret_values[2]},
        }
    ]
    inventory["configmaps"] = [
        {
            "apiVersion": "v1",
            "kind": "ConfigMap",
            "metadata": {
                "name": "fixture-config",
                "namespace": "kube-system",
                "uid": "42345678-1234-1234-1234-123456789abc",
                "resourceVersion": "4",
            },
            "data": {"safe-key": "safe-value"},
        }
    ]
    inventory["pods"] = [
        {
            "apiVersion": "v1",
            "kind": "Pod",
            "metadata": {
                "name": "fixture-pod",
                "namespace": "kube-system",
                "uid": "52345678-1234-1234-1234-123456789abc",
                "resourceVersion": "5",
                "annotations": {"authorization": "Bearer hidden"},
            },
            "spec": {
                "containers": [
                    {
                        "name": "fixture",
                        "image": "example.invalid/fixture:v1",
                        "env": [{"name": "ACCESS_TOKEN", "value": secret_values[1]}],
                        "command": ["fixture", "--password", secret_values[2]],
                    }
                ]
            },
        }
    ]
    return inventory


inventory = make_inventory()
valid_discovery = {
    "core": {"kind": "APIVersions", "apiVersion": "v1", "versions": ["v1"]},
    "groups": {
        "kind": "APIGroupList",
        "apiVersion": "v1",
        "groups": [
            {"name": "apps", "versions": [{"version": "v1", "groupVersion": "apps/v1"}]},
            {
                "name": "review.custom.example",
                "versions": [{"version": "v1", "groupVersion": "review.custom.example/v1"}],
            },
        ],
    },
}


class FakeBackend:
    def __init__(self, _runner, _identity, _kubeconfig, _environment, *, current_inventory=None,
                 discovery=None):
        self.current_inventory = current_inventory if current_inventory is not None else make_inventory()
        self.api_discovery = discovery if discovery is not None else qualification.validate_api_discovery(
            valid_discovery["core"], valid_discovery["groups"]
        )

    def capture_cluster_inventory(self):
        return self.current_inventory


class FakeOwnerAPI:
    FixtureDenied = qualification.FixtureDenied

    def __init__(self, *, identity=None, verify_drift=False, cleanup_uncertain=False):
        self.identity = identity if identity is not None else Identity()
        self.verify_drift = verify_drift
        self.cleanup_uncertain = cleanup_uncertain
        self.verify_calls = 0
        self.create_calls = 0
        self.teardown_calls = 0
        self.evidence = None
        self.evidence_path = None

    def create_fixture(self, runner, _authority):
        self.create_calls += 1
        runner.resources_may_exist = True
        fixture_receipt = {
            "kubeSystemNamespaceUid": namespace_uid,
            "kubernetesServerVersion": "v1.35.8",
            "nodeImage": "kindest/node:v1.35.8@sha256:" + "d" * 64,
            "nodeContainers": [{"id": node_id, "name": "kind-control-plane", "role": "control-plane"}],
            "kindNetwork": {"id": network_id},
        }
        return self.identity, fixture_receipt, {}, root / "fixture.kubeconfig", {}

    def assert_run_directory_owned(self, _identity, _path):
        return None

    def verify_live_ownership(self, _runner, _identity, _receipt, _kubeconfig, _environment):
        self.verify_calls += 1
        if self.verify_drift and self.verify_calls == 2:
            raise self.FixtureDenied("cluster_claim_inventory_mismatch_resources_retained")

    def write_exclusive_json(self, path, value):
        self.evidence_path = path
        self.evidence = value

    def teardown_fixture(self, runner, _identity, _kubeconfig, _environment):
        self.teardown_calls += 1
        if self.cleanup_uncertain:
            raise self.FixtureDenied("kind_teardown_identity_uncertain_resources_retained")
        runner.resources_may_exist = False


def run_qualification(*, owner=None, current_inventory=None, discovery=None):
    owner = owner if owner is not None else FakeOwnerAPI()
    runner = Runner()
    previous_workspace = os.environ.get("GITHUB_WORKSPACE")
    os.environ["GITHUB_WORKSPACE"] = str(root)
    try:
        result = qualification.run_factory_qualification(
            root / "config/workflow-tool-versions.env",
            command_runner=runner,
            fixture_api=owner,
            backend_factory=lambda *args: FakeBackend(
                *args, current_inventory=current_inventory, discovery=discovery
            ),
        )
    finally:
        if previous_workspace is None:
            os.environ.pop("GITHUB_WORKSPACE", None)
        else:
            os.environ["GITHUB_WORKSPACE"] = previous_workspace
    return result, owner, runner


assert len(qualification.CLUSTER_INVENTORY_RESOURCES) == 29
assert len(qualification.KUBERNETES_RESOURCE_KINDS) == 29
assert qualification.validate_factory_inventory(inventory)["namespaces"] == 1
assert qualification.FACTORY_INVENTORY_MAX_ITEMS_PER_RESOURCE == 512
assert qualification.FACTORY_INVENTORY_MAX_TOTAL_ITEMS == 4096
assert qualification.FACTORY_INVENTORY_MAX_SECONDS == 180
assert qualification.FACTORY_INVENTORY_MAX_OUTPUT_BYTES_PER_RESOURCE == 64 * 1024
assert qualification.FACTORY_DISCOVERY_MAX_GROUPS == 128
assert qualification.FACTORY_DISCOVERY_MAX_GROUP_VERSIONS == 256

partial = dict(inventory)
partial.pop("apiservices.apiregistration.k8s.io")
try:
    qualification.validate_factory_inventory(partial)
    raise AssertionError("partial inventory unexpectedly passed")
except qualification.FixtureDenied as error:
    assert error.reason == "game_session_factory_inventory_partial_or_unknown"

unknown = dict(inventory)
unknown["unmapped.example"] = []
try:
    qualification.validate_factory_inventory(unknown)
    raise AssertionError("unknown inventory collection unexpectedly passed")
except qualification.FixtureDenied as error:
    assert error.reason == "game_session_factory_inventory_partial_or_unknown"

duplicate = dict(inventory)
duplicate["namespaces"] = [inventory["namespaces"][0], dict(inventory["namespaces"][0])]
duplicate["namespaces"][1] = json.loads(json.dumps(duplicate["namespaces"][1]))
duplicate["namespaces"][1]["metadata"]["uid"] = "62345678-1234-1234-1234-123456789abc"
try:
    qualification.validate_factory_inventory(duplicate)
    raise AssertionError("duplicate inventory item unexpectedly passed")
except qualification.FixtureDenied as error:
    assert error.reason == "game_session_factory_inventory_duplicate_item"

oversized_resource = dict(inventory)
oversized_resource["namespaces"] = []
for index in range(qualification.FACTORY_INVENTORY_MAX_ITEMS_PER_RESOURCE + 1):
    oversized_resource["namespaces"].append(
        {
            "apiVersion": "v1",
            "kind": "Namespace",
            "metadata": {
                "name": f"ns-{index}",
                "uid": f"{index + 1:08x}-1234-1234-1234-{index + 1:012x}",
                "resourceVersion": str(index + 1),
            },
        }
    )
try:
    qualification.validate_factory_inventory(oversized_resource)
    raise AssertionError("oversized resource inventory unexpectedly passed")
except qualification.FixtureDenied as error:
    assert error.reason == "game_session_factory_inventory_resource_limit_exceeded"

oversized_total = {resource: [] for resource, _ in qualification.CLUSTER_INVENTORY_RESOURCES}
for resource_index, (resource, _namespaced) in enumerate(qualification.CLUSTER_INVENTORY_RESOURCES[:9]):
    kind, api_version, namespaced = qualification.KUBERNETES_RESOURCE_KINDS[resource]
    for item_index in range(457):
        identity_value = resource_index * 1000 + item_index + 1
        metadata = {
            "name": f"{resource_index}-{item_index}",
            "uid": f"{identity_value:08x}-1234-1234-1234-{identity_value:012x}",
            "resourceVersion": str(identity_value),
        }
        if namespaced:
            metadata["namespace"] = "kube-system"
        oversized_total[resource].append(
            {"apiVersion": api_version, "kind": kind, "metadata": metadata}
        )
try:
    qualification.validate_factory_inventory(oversized_total)
    raise AssertionError("oversized total inventory unexpectedly passed")
except qualification.FixtureDenied as error:
    assert error.reason == "game_session_factory_inventory_total_limit_exceeded"

groups_over_limit = {
    "kind": "APIGroupList",
    "apiVersion": "v1",
    "groups": [
        {"name": f"g{index}.example", "versions": [{"version": "v1", "groupVersion": f"g{index}.example/v1"}]}
        for index in range(qualification.FACTORY_DISCOVERY_MAX_GROUPS + 1)
    ],
}
try:
    qualification.validate_api_discovery(valid_discovery["core"], groups_over_limit)
    raise AssertionError("unbounded API discovery unexpectedly passed")
except qualification.FixtureDenied as error:
    assert error.reason == "game_session_api_discovery_invalid_or_unbounded"

versions_over_limit = {
    "kind": "APIGroupList",
    "apiVersion": "v1",
    "groups": [
        {
            "name": f"group{group_index}.example",
            "versions": [
                {
                    "version": f"v{version_index}",
                    "groupVersion": f"group{group_index}.example/v{version_index}",
                }
                for version_index in range(29)
            ],
        }
        for group_index in range(9)
    ],
}
try:
    qualification.validate_api_discovery(valid_discovery["core"], versions_over_limit)
    raise AssertionError("unbounded API group-version discovery unexpectedly passed")
except qualification.FixtureDenied as error:
    assert error.reason == "game_session_api_discovery_invalid_or_unbounded"

result, owner, runner = run_qualification()
assert result["outcome"] == "qualified_for_review_only", result
assert result["qualificationState"] == "review_only_not_an_approved_baseline", result
assert result["approvedFactoryBaseline"] is False, result
assert result["cleanup"] == "removed", result
assert result["totalItems"] == 5, result
assert owner.create_calls == 1 and owner.teardown_calls == 1, (owner.create_calls, owner.teardown_calls)
assert runner.resources_may_exist is False
assert owner.evidence is not None and owner.evidence_path.name == qualification.FACTORY_INVENTORY_ARTIFACT
assert owner.evidence["approvedFactoryBaseline"] is False
assert owner.evidence["inventoryScope"]["observationConsistency"] == "sequential_non_atomic_observation"
assert owner.evidence["inventoryScope"]["customResourceInstances"] == "not_collected"
assert owner.evidence["inventoryScope"]["unlistedApiKinds"] == "unresolved_review_input_only"
serialized_evidence = json.dumps(owner.evidence, sort_keys=True)
assert not any(value in serialized_evidence for value in secret_values), serialized_evidence
assert "inventory" in owner.evidence and len(owner.evidence["inventory"]) == 29
assert not any(key in result for key in ("admission", "migration", "activation", "v31Disposition", "verifiedPublished")), result

unknown_discovery = qualification.validate_api_discovery(
    valid_discovery["core"],
    {
        "kind": "APIGroupList",
        "apiVersion": "v1",
        "groups": valid_discovery["groups"]["groups"]
        + [{"name": "unknown.review.example", "versions": [{"version": "v1", "groupVersion": "unknown.review.example/v1"}]}],
    },
)
unknown_result, unknown_owner, _ = run_qualification(discovery=unknown_discovery)
assert unknown_result["outcome"] == "qualified_for_review_only", unknown_result
assert unknown_result["approvedFactoryBaseline"] is False, unknown_result
assert "unknown.review.example" in {
    group["name"] for group in unknown_owner.evidence["apiDiscovery"]["groups"]
}
assert unknown_owner.evidence["inventoryScope"]["customResourceInstances"] == "not_collected"
assert unknown_owner.evidence["inventoryScope"]["unlistedApiKinds"] == "unresolved_review_input_only"

untrusted_identity = Identity()
untrusted_identity.ref = "refs/pull/17/merge"
untrusted_owner = FakeOwnerAPI(identity=untrusted_identity)
untrusted_result, untrusted_owner, untrusted_runner = run_qualification(owner=untrusted_owner)
assert untrusted_result["outcome"] == "denied" and untrusted_result["reason"] == "trusted_runner_identity_invalid", untrusted_result
assert untrusted_result["cleanup"] == "resources_retained", untrusted_result
assert untrusted_owner.teardown_calls == 0 and untrusted_runner.resources_may_exist is True

partial_result, partial_owner, partial_runner = run_qualification(current_inventory=partial)
assert partial_result["outcome"] == "denied"
assert partial_result["reason"] == "game_session_factory_inventory_partial_or_unknown", partial_result
assert partial_result["cleanup"] == "resources_retained", partial_result
assert partial_owner.teardown_calls == 0 and partial_owner.evidence is None and partial_runner.resources_may_exist

drift_owner = FakeOwnerAPI(verify_drift=True)
drift_result, drift_owner, drift_runner = run_qualification(owner=drift_owner)
assert drift_result["outcome"] == "denied"
assert drift_result["reason"] == "cluster_claim_inventory_mismatch_resources_retained", drift_result
assert drift_result["cleanup"] == "resources_retained", drift_result
assert drift_owner.teardown_calls == 0 and drift_owner.evidence is None and drift_runner.resources_may_exist

cleanup_owner = FakeOwnerAPI(cleanup_uncertain=True)
cleanup_result, cleanup_owner, cleanup_runner = run_qualification(owner=cleanup_owner)
assert cleanup_result["outcome"] == "denied"
assert cleanup_result["reason"] == "kind_teardown_identity_uncertain_resources_retained", cleanup_result
assert cleanup_result["cleanup"] == "resources_retained", cleanup_result
assert cleanup_owner.teardown_calls == 1 and cleanup_runner.resources_may_exist
assert "inventoryArtifact" not in cleanup_result

backend_identity = Identity()
with patch.object(qualification.fixture, "require_tool", return_value="/mock/kubectl"):
    backend = qualification.FactoryInventoryBackend(
        Runner(), backend_identity, Path("/mock/kubeconfig"), {}
    )
with patch.object(qualification.time, "monotonic", side_effect=[0.0, 181.0]):
    try:
        backend.capture_cluster_inventory()
        raise AssertionError("expired capture deadline unexpectedly passed")
    except qualification.FixtureDenied as error:
        assert error.reason == "game_session_factory_inventory_time_limit_exceeded"

class TruncatedRunner:
    def __init__(self):
        self.calls = []

    def run(self, argv, _environment, _reason, timeout_seconds):
        self.calls.append((argv, timeout_seconds))
        return SimpleNamespace(stdout=b"{}", truncated=True)


truncated_runner = TruncatedRunner()
with patch.object(qualification.fixture, "require_tool", return_value="/mock/kubectl"):
    truncated_backend = qualification.FactoryInventoryBackend(
        truncated_runner, backend_identity, Path("/mock/kubeconfig"), {}
    )
try:
    truncated_backend.capture_cluster_inventory()
    raise AssertionError("truncated inventory response unexpectedly passed")
except qualification.FixtureDenied as error:
    assert error.reason == "game_session_kubernetes_inventory_invalid"
assert len(truncated_runner.calls) == 1

class MustNotCreateRunner:
    def __init__(self):
        raise AssertionError("lifecycle rejection created a fixture runner")


original_runner = qualification.fixture.CommandRunner
qualification.fixture.CommandRunner = MustNotCreateRunner
stdout = io.StringIO()
try:
    with contextlib.redirect_stdout(stdout):
        exit_code = qualification.main(
            ["qualification.py", "--authority", str(root / "config/workflow-tool-versions.env"), "--operation", "lifecycle"]
        )
finally:
    qualification.fixture.CommandRunner = original_runner
assert exit_code == 1
lifecycle_result = json.loads(stdout.getvalue())
assert lifecycle_result["reason"] == "usage_invalid" and lifecycle_result["cleanup"] == "not_created_or_not_claimed"

print("Game Session factory qualification contract: mocked review-only inventory and owner-boundary checks passed")
PY
