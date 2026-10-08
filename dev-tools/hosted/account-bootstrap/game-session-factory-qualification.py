#!/usr/bin/env python3
"""Qualify a fresh disposable kind factory inventory for review only."""

from __future__ import annotations

import importlib.util
import json
import os
import re
import sys
import time
from pathlib import Path
from typing import Any

FIXTURE_PATH = Path(__file__).with_name("account-jwt-bootstrap-fixture.py")
FIXTURE_SPEC = importlib.util.spec_from_file_location("account_jwt_bootstrap_fixture", FIXTURE_PATH)
if FIXTURE_SPEC is None or FIXTURE_SPEC.loader is None:
    raise RuntimeError("trusted Account bootstrap fixture is unavailable")
fixture = importlib.util.module_from_spec(FIXTURE_SPEC)
sys.modules[FIXTURE_SPEC.name] = fixture
FIXTURE_SPEC.loader.exec_module(fixture)
FixtureDenied = fixture.FixtureDenied

UID_PATTERN = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")

FACTORY_INVENTORY_MAX_ITEMS_PER_RESOURCE = 512

FACTORY_INVENTORY_MAX_TOTAL_ITEMS = 4096

FACTORY_INVENTORY_MAX_SECONDS = 180

FACTORY_INVENTORY_MAX_OUTPUT_BYTES_PER_RESOURCE = fixture.MAX_COMMAND_STDOUT_BYTES

FACTORY_DISCOVERY_MAX_GROUPS = 128

FACTORY_DISCOVERY_MAX_GROUP_VERSIONS = 256

FACTORY_INVENTORY_ARTIFACT = "game-session-factory-inventory-qualification.json"

KUBERNETES_RESOURCE_KINDS = {
    "namespaces": ("Namespace", "v1", False),
    "nodes": ("Node", "v1", False),
    "pods": ("Pod", "v1", True),
    "deployments.apps": ("Deployment", "apps/v1", True),
    "statefulsets.apps": ("StatefulSet", "apps/v1", True),
    "daemonsets.apps": ("DaemonSet", "apps/v1", True),
    "replicasets.apps": ("ReplicaSet", "apps/v1", True),
    "replicationcontrollers": ("ReplicationController", "v1", True),
    "jobs.batch": ("Job", "batch/v1", True),
    "cronjobs.batch": ("CronJob", "batch/v1", True),
    "services": ("Service", "v1", True),
    "endpoints": ("Endpoints", "v1", True),
    "endpointslices.discovery.k8s.io": ("EndpointSlice", "discovery.k8s.io/v1", True),
    "configmaps": ("ConfigMap", "v1", True),
    "serviceaccounts": ("ServiceAccount", "v1", True),
    "secrets": ("Secret", "v1", True),
    "roles.rbac.authorization.k8s.io": ("Role", "rbac.authorization.k8s.io/v1", True),
    "rolebindings.rbac.authorization.k8s.io": (
        "RoleBinding", "rbac.authorization.k8s.io/v1", True
    ),
    "clusterroles.rbac.authorization.k8s.io": (
        "ClusterRole", "rbac.authorization.k8s.io/v1", False
    ),
    "clusterrolebindings.rbac.authorization.k8s.io": (
        "ClusterRoleBinding", "rbac.authorization.k8s.io/v1", False
    ),
    "persistentvolumes": ("PersistentVolume", "v1", False),
    "persistentvolumeclaims": ("PersistentVolumeClaim", "v1", True),
    "networkpolicies.networking.k8s.io": ("NetworkPolicy", "networking.k8s.io/v1", True),
    "mutatingwebhookconfigurations.admissionregistration.k8s.io": (
        "MutatingWebhookConfiguration", "admissionregistration.k8s.io/v1", False
    ),
    "validatingwebhookconfigurations.admissionregistration.k8s.io": (
        "ValidatingWebhookConfiguration", "admissionregistration.k8s.io/v1", False
    ),
    "validatingadmissionpolicies.admissionregistration.k8s.io": (
        "ValidatingAdmissionPolicy", "admissionregistration.k8s.io/v1", False
    ),
    "validatingadmissionpolicybindings.admissionregistration.k8s.io": (
        "ValidatingAdmissionPolicyBinding", "admissionregistration.k8s.io/v1", False
    ),
    "customresourcedefinitions.apiextensions.k8s.io": (
        "CustomResourceDefinition", "apiextensions.k8s.io/v1", False
    ),
    "apiservices.apiregistration.k8s.io": ("APIService", "apiregistration.k8s.io/v1", False),
}

CLUSTER_INVENTORY_RESOURCES = (
    ("namespaces", False),
    ("nodes", False),
    ("pods", True),
    ("deployments.apps", True),
    ("statefulsets.apps", True),
    ("daemonsets.apps", True),
    ("replicasets.apps", True),
    ("replicationcontrollers", True),
    ("jobs.batch", True),
    ("cronjobs.batch", True),
    ("services", True),
    ("endpoints", True),
    ("endpointslices.discovery.k8s.io", True),
    ("configmaps", True),
    ("serviceaccounts", True),
    ("secrets", True),
    ("roles.rbac.authorization.k8s.io", True),
    ("rolebindings.rbac.authorization.k8s.io", True),
    ("clusterroles.rbac.authorization.k8s.io", False),
    ("clusterrolebindings.rbac.authorization.k8s.io", False),
    ("persistentvolumes", False),
    ("persistentvolumeclaims", True),
    ("networkpolicies.networking.k8s.io", True),
    ("mutatingwebhookconfigurations.admissionregistration.k8s.io", False),
    ("validatingwebhookconfigurations.admissionregistration.k8s.io", False),
    ("validatingadmissionpolicies.admissionregistration.k8s.io", False),
    ("validatingadmissionpolicybindings.admissionregistration.k8s.io", False),
    ("customresourcedefinitions.apiextensions.k8s.io", False),
    ("apiservices.apiregistration.k8s.io", False),
)

def _deny(reason: str) -> None:
    raise FixtureDenied(reason)

def validate_runner_identity(identity: Any) -> None:
    """Recheck the trusted fixture's run identity before deriving any storage names."""
    if (
        getattr(identity, "repository", None) != fixture.EXPECTED_REPOSITORY
        or getattr(identity, "ref", None) != fixture.EXPECTED_REF
        or getattr(identity, "event", None) is not None
        and identity.event not in fixture.ALLOWED_EVENTS
        or not SHA_PATTERN.fullmatch(str(getattr(identity, "sha", "")))
        or not str(getattr(identity, "run_id", "")).isascii()
        or not str(getattr(identity, "run_id", "")).isdigit()
        or int(getattr(identity, "run_id", "0")) < 1
        or not str(getattr(identity, "attempt", "")).isascii()
        or not str(getattr(identity, "attempt", "")).isdigit()
        or int(getattr(identity, "attempt", "0")) < 1
    ):
        _deny("trusted_runner_identity_invalid")
    if getattr(identity, "cluster_name", "") != (
        f"account-jwt-bootstrap-{identity.run_id}-{identity.attempt}-{identity.sha[:8]}"
    ):
        _deny("trusted_runner_cluster_identity_mismatch")

def _decode_json_result(result: Any, reason: str) -> dict[str, Any]:
    if (
        result.truncated
        or not isinstance(result.stdout, bytes)
        or len(result.stdout) > FACTORY_INVENTORY_MAX_OUTPUT_BYTES_PER_RESOURCE
    ):
        _deny(reason)
    try:
        value = json.loads(result.stdout.decode("utf-8", errors="strict"))
    except (UnicodeError, json.JSONDecodeError, AttributeError):
        _deny(reason)
    if not isinstance(value, dict):
        _deny(reason)
    return value

def _require_resource_list(value: dict[str, Any], resource: str, reason: str,
                           expected_namespace: str | None = None,
                           all_namespaces: bool = False) -> list[dict[str, Any]]:
    kind_spec = KUBERNETES_RESOURCE_KINDS.get(resource)
    if kind_spec is None:
        _deny(reason)
    expected_kind, expected_api_version, namespaced = kind_spec
    list_kind = value.get("kind")
    if (
        list_kind not in ("List", f"{expected_kind}List")
        or not isinstance(value.get("items"), list)
        or (list_kind == "List" and value.get("apiVersion") != "v1")
        or (list_kind == f"{expected_kind}List" and value.get("apiVersion") != expected_api_version)
        or (expected_namespace is not None and not namespaced)
        or (all_namespaces and not namespaced)
        or (namespaced and expected_namespace is None and not all_namespaces)
    ):
        _deny(reason)
    items: list[dict[str, Any]] = []
    for item in value["items"]:
        metadata = item.get("metadata") if isinstance(item, dict) else None
        if (
            not isinstance(item, dict)
            or item.get("kind") != expected_kind
            or item.get("apiVersion") != expected_api_version
            or not isinstance(metadata, dict)
            or not isinstance(metadata.get("name"), str)
            or not isinstance(metadata.get("uid"), str)
            or not UID_PATTERN.fullmatch(metadata["uid"])
            or not isinstance(metadata.get("resourceVersion"), str)
            or not metadata["resourceVersion"]
            or (namespaced and not isinstance(metadata.get("namespace"), str))
            or (not namespaced and "namespace" in metadata)
            or (expected_namespace is not None and metadata.get("namespace") != expected_namespace)
        ):
            _deny(reason)
        items.append(item)
    return items

def validate_factory_inventory(inventory: dict[str, list[dict[str, Any]]]) -> dict[str, int]:
    """Require a complete, bounded, typed set of factory API inventories."""
    if set(inventory) != {resource for resource, _ in CLUSTER_INVENTORY_RESOURCES}:
        _deny("game_session_factory_inventory_partial_or_unknown")
    counts: dict[str, int] = {}
    total = 0
    for resource, _ in CLUSTER_INVENTORY_RESOURCES:
        kind, api_version, namespaced = KUBERNETES_RESOURCE_KINDS[resource]
        items = _items(inventory, resource)
        if len(items) > FACTORY_INVENTORY_MAX_ITEMS_PER_RESOURCE:
            _deny("game_session_factory_inventory_resource_limit_exceeded")
        total += len(items)
        if total > FACTORY_INVENTORY_MAX_TOTAL_ITEMS:
            _deny("game_session_factory_inventory_total_limit_exceeded")
        observed_names: set[tuple[str | None, str]] = set()
        for item in items:
            metadata = item.get("metadata")
            if (
                item.get("kind") != kind
                or item.get("apiVersion") != api_version
                or not isinstance(metadata, dict)
                or not isinstance(metadata.get("name"), str)
                or not isinstance(metadata.get("uid"), str)
                or not UID_PATTERN.fullmatch(metadata["uid"])
                or not isinstance(metadata.get("resourceVersion"), str)
                or not metadata["resourceVersion"]
                or (namespaced and not isinstance(metadata.get("namespace"), str))
                or (not namespaced and "namespace" in metadata)
            ):
                _deny("game_session_factory_inventory_item_invalid")
            object_name = (metadata.get("namespace"), metadata["name"])
            if object_name in observed_names:
                _deny("game_session_factory_inventory_duplicate_item")
            observed_names.add(object_name)
        counts[resource] = len(items)
    return counts

def validate_api_discovery(core: dict[str, Any], groups: dict[str, Any]) -> dict[str, Any]:
    """Retain bounded API group/version structure without querying arbitrary custom resources."""
    core_versions = core.get("versions") if isinstance(core, dict) else None
    api_groups = groups.get("groups") if isinstance(groups, dict) else None
    if (
        not isinstance(core, dict)
        or core.get("kind") != "APIVersions"
        or ("apiVersion" in core and core.get("apiVersion") != "v1")
        or not isinstance(core_versions, list)
        or not core_versions
        or len(core_versions) > 32
        or any(not isinstance(version, str) or not re.fullmatch(r"v[0-9]+", version)
               for version in core_versions)
        or len(set(core_versions)) != len(core_versions)
        or not isinstance(groups, dict)
        or groups.get("kind") != "APIGroupList"
        or groups.get("apiVersion") != "v1"
        or not isinstance(api_groups, list)
        or not api_groups
        or len(api_groups) > FACTORY_DISCOVERY_MAX_GROUPS
    ):
        _deny("game_session_api_discovery_invalid_or_unbounded")
    normalized_groups: list[dict[str, Any]] = []
    group_names: set[str] = set()
    group_version_count = 0
    for group in api_groups:
        name = group.get("name") if isinstance(group, dict) else None
        versions = group.get("versions") if isinstance(group, dict) else None
        if (
            not isinstance(name, str)
            or len(name) > 253
            or not re.fullmatch(r"[a-z0-9](?:[-a-z0-9.]*[a-z0-9])?", name)
            or name in group_names
            or not isinstance(versions, list)
            or not versions
            or len(versions) > 32
        ):
            _deny("game_session_api_discovery_invalid_or_unbounded")
        group_names.add(name)
        version_names: set[str] = set()
        for version in versions:
            version_name = version.get("version") if isinstance(version, dict) else None
            group_version = version.get("groupVersion") if isinstance(version, dict) else None
            if (
                not isinstance(version_name, str)
                or not re.fullmatch(r"v[0-9]+(?:(?:alpha|beta)[0-9]+)?", version_name)
                or group_version != f"{name}/{version_name}"
                or version_name in version_names
            ):
                _deny("game_session_api_discovery_invalid_or_unbounded")
            version_names.add(version_name)
        group_version_count += len(version_names)
        if group_version_count > FACTORY_DISCOVERY_MAX_GROUP_VERSIONS:
            _deny("game_session_api_discovery_invalid_or_unbounded")
        normalized_groups.append({"name": name, "versions": sorted(version_names)})
    return {
        "coreVersions": sorted(core_versions),
        "groups": sorted(normalized_groups, key=lambda item: item["name"]),
        "groupCount": len(normalized_groups),
        "groupVersionCount": group_version_count,
    }

SENSITIVE_INVENTORY_KEY_PARTS = (
    "password", "token", "credential", "authorization", "privatekey", "clientsecret",
    "accesskey", "sshkey", "tlskey", "secretvalue",
)

SAFE_SECRET_REFERENCE_KEYS = {"secretname", "secretkeyref", "secretref", "imagepullsecrets"}

SENSITIVE_INVENTORY_VALUE_PATTERN = re.compile(
    r"-----BEGIN [^-]*PRIVATE KEY-----|\bBearer\s+\S+|"
    r"\b(?:password|token|client_secret|private_key|authorization)\s*[:=]\s*\S+|"
    r"\b[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{8,}\b",
    re.IGNORECASE,
)

def _normalized_inventory_key(value: Any) -> str:
    return re.sub(r"[^a-z0-9]", "", str(value).lower())

def _sanitize_inventory_string(value: str) -> str:
    if SENSITIVE_INVENTORY_VALUE_PATTERN.search(value):
        return "[redacted]"
    return re.sub(r"(?<=://)[^/@]+@", "[redacted]@", value)

def _sanitize_inventory_value(value: Any, key: str | None = None) -> Any:
    normalized = _normalized_inventory_key(key) if key is not None else ""
    if normalized in SAFE_SECRET_REFERENCE_KEYS:
        if isinstance(value, dict):
            return {str(child_key): _sanitize_inventory_value(child_value, str(child_key))
                    for child_key, child_value in value.items()}
        if isinstance(value, list):
            return [_sanitize_inventory_value(item) for item in value]
        return value
    if any(part in normalized for part in SENSITIVE_INVENTORY_KEY_PARTS):
        return "[redacted]"
    if normalized in {"message", "log", "logs", "stdout", "stderr"}:
        return "[omitted]"
    if normalized in {"data", "stringdata", "binarydata"} and isinstance(value, dict):
        return {"fieldNamesOnly": sorted(str(child_key) for child_key in value)}
    if normalized == "annotations" and isinstance(value, dict):
        return {"keysOnly": sorted(str(child_key) for child_key in value)}
    if normalized == "env" and isinstance(value, list):
        sanitized_env = []
        for item in value:
            if not isinstance(item, dict):
                sanitized_env.append("[invalid-env-item]")
                continue
            entry: dict[str, Any] = {}
            if isinstance(item.get("name"), str):
                entry["name"] = item["name"]
            if "value" in item:
                entry["literalValuePresent"] = True
            if "valueFrom" in item:
                entry["valueFrom"] = _sanitize_inventory_value(item["valueFrom"], "valueFrom")
            sanitized_env.append(entry)
        return sanitized_env
    if normalized in {"command", "args"} and isinstance(value, list):
        # Command arguments can carry credentials without a credential-like field name (for
        # example, argv after --password). Keep only the shape, never the literal arguments.
        return {"itemCount": len(value), "literalValues": "omitted"}
    if isinstance(value, dict):
        return {
            str(child_key): _sanitize_inventory_value(child_value, str(child_key))
            for child_key, child_value in value.items()
            if _normalized_inventory_key(child_key) not in {"managedfields"}
        }
    if isinstance(value, list):
        return [_sanitize_inventory_value(item) for item in value]
    if isinstance(value, str):
        return _sanitize_inventory_string(value)
    return value

def sanitize_factory_object(item: dict[str, Any]) -> dict[str, Any]:
    """Keep structural proof while excluding secret values and secret-derived digests."""
    metadata = item.get("metadata", {})
    metadata_value: dict[str, Any] = {}
    for field in ("name", "namespace", "uid", "resourceVersion", "generation", "creationTimestamp"):
        if field in metadata:
            metadata_value[field] = metadata[field]
    if isinstance(metadata.get("labels"), dict):
        metadata_value["labels"] = _sanitize_inventory_value(metadata["labels"], "labels")
    if isinstance(metadata.get("annotations"), dict):
        metadata_value["annotationKeys"] = sorted(str(key) for key in metadata["annotations"])
    if isinstance(metadata.get("ownerReferences"), list):
        metadata_value["ownerReferences"] = _sanitize_inventory_value(metadata["ownerReferences"])
    if isinstance(metadata.get("finalizers"), list):
        metadata_value["finalizers"] = _sanitize_inventory_value(metadata["finalizers"])

    value: dict[str, Any] = {
        "apiVersion": item.get("apiVersion"),
        "kind": item.get("kind"),
        "metadata": metadata_value,
    }
    if item.get("kind") == "Secret":
        value["type"] = item.get("type")
        value["immutable"] = item.get("immutable")
        data = item.get("data")
        string_data = item.get("stringData")
        value["dataKeysOnly"] = sorted(str(key) for key in data) if isinstance(data, dict) else []
        value["stringDataKeysOnly"] = sorted(str(key) for key in string_data) if isinstance(string_data, dict) else []
        return value
    for key, item_value in item.items():
        if key in {"apiVersion", "kind", "metadata", "data", "binaryData", "stringData"}:
            continue
        value[key] = _sanitize_inventory_value(item_value, key)
    if item.get("kind") in {"ConfigMap"}:
        data = item.get("data")
        binary_data = item.get("binaryData")
        value["dataKeysOnly"] = sorted(str(key) for key in data) if isinstance(data, dict) else []
        value["binaryDataKeysOnly"] = sorted(str(key) for key in binary_data) if isinstance(binary_data, dict) else []
    return value

def _items(inventory: dict[str, list[dict[str, Any]]], resource: str) -> list[dict[str, Any]]:
    value = inventory.get(resource)
    if not isinstance(value, list) or any(not isinstance(item, dict) for item in value):
        _deny("game_session_kubernetes_inventory_partial_or_unknown")
    return value


def _factory_inventory_evidence(identity: Any, fixture_receipt: dict[str, Any],
                                inventory: dict[str, list[dict[str, Any]]],
                                counts: dict[str, int],
                                api_discovery: dict[str, Any]) -> dict[str, Any]:
    resources: dict[str, list[dict[str, Any]]] = {}
    for resource, _ in CLUSTER_INVENTORY_RESOURCES:
        ordered = sorted(
            _items(inventory, resource),
            key=lambda item: (
                str(item.get("metadata", {}).get("namespace", "")),
                str(item.get("metadata", {}).get("name", "")),
                str(item.get("metadata", {}).get("uid", "")),
            ),
        )
        resources[resource] = [sanitize_factory_object(item) for item in ordered]
    nodes = fixture_receipt.get("nodeContainers")
    network = fixture_receipt.get("kindNetwork")
    if (
        not isinstance(nodes, list)
        or not nodes
        or any(
            not isinstance(node, dict)
            or not isinstance(node.get("id"), str)
            or not re.fullmatch(r"[0-9a-f]{64}", node["id"])
            or not isinstance(node.get("name"), str)
            or not node["name"]
            or not isinstance(node.get("role"), str)
            or not node["role"]
            for node in nodes
        )
        or len({node["id"] for node in nodes if isinstance(node, dict)}) != len(nodes)
        or not isinstance(network, dict)
        or not re.fullmatch(r"[0-9a-f]{64}", str(network.get("id", "")))
        or not isinstance(fixture_receipt.get("kubernetesServerVersion"), str)
        or not fixture_receipt["kubernetesServerVersion"]
        or not isinstance(fixture_receipt.get("nodeImage"), str)
        or not re.fullmatch(r".+@sha256:[0-9a-f]{64}", fixture_receipt["nodeImage"])
    ):
        _deny("trusted_fixture_immutable_identity_missing_resources_retained")
    return {
        "schemaVersion": 1,
        "qualificationState": "review_only_not_an_approved_baseline",
        "approvedFactoryBaseline": False,
        "repository": identity.repository,
        "ref": identity.ref,
        "commit": identity.sha,
        "runId": identity.run_id,
        "runAttempt": identity.attempt,
        "clusterName": identity.cluster_name,
        "kubeSystemNamespaceUid": fixture_receipt["kubeSystemNamespaceUid"],
        "kubernetesServerVersion": fixture_receipt["kubernetesServerVersion"],
        "nodeImage": fixture_receipt["nodeImage"],
        "nodeContainers": [
            {key: node[key] for key in ("id", "name", "role")}
            for node in nodes
        ],
        "kindNetworkId": network["id"],
        "inventoryBounds": {
            "maxItemsPerResource": FACTORY_INVENTORY_MAX_ITEMS_PER_RESOURCE,
            "maxTotalItems": FACTORY_INVENTORY_MAX_TOTAL_ITEMS,
            "maxCaptureSeconds": FACTORY_INVENTORY_MAX_SECONDS,
            "maxOutputBytesPerResponse": FACTORY_INVENTORY_MAX_OUTPUT_BYTES_PER_RESOURCE,
            "maxApiGroups": FACTORY_DISCOVERY_MAX_GROUPS,
            "maxApiGroupVersions": FACTORY_DISCOVERY_MAX_GROUP_VERSIONS,
        },
        "resourceCounts": counts,
        "totalItems": sum(counts.values()),
        "apiDiscovery": api_discovery,
        "inventoryScope": {
            "namedBuiltInCollections": sorted(resource for resource, _ in CLUSTER_INVENTORY_RESOURCES),
            "customResourceDefinitionsIncluded": True,
            "apiServicesIncluded": True,
            "customResourceInstances": "not_collected",
            "unlistedApiKinds": "unresolved_review_input_only",
            "observationConsistency": "sequential_non_atomic_observation",
            "approvedFactoryBaseline": False,
        },
        "inventory": resources,
    }


class FactoryInventoryBackend:
    """Bounded read-only observation of the fixture's Kubernetes factory inventory."""

    def __init__(self, command_runner: Any, identity: Any, kubeconfig: Path,
                 environment: dict[str, str]):
        self.command_runner = command_runner
        self.identity = identity
        self.kubeconfig = kubeconfig
        self.environment = environment
        self.context = f"kind-{identity.cluster_name}"
        self.kubectl = fixture.require_tool("kubectl")

    def _run(self, args: list[str], reason: str, timeout: int = 45) -> Any:
        return self.command_runner.run(
            [self.kubectl, "--kubeconfig", str(self.kubeconfig), "--context", self.context, *args],
            self.environment,
            reason,
            timeout_seconds=timeout,
            stdout_limit=FACTORY_INVENTORY_MAX_OUTPUT_BYTES_PER_RESOURCE,
        )

    def _get_list(self, resource: str, *, all_namespaces: bool = False,
                  timeout_seconds: int = 30) -> list[dict[str, Any]]:
        args = ["get", resource]
        if all_namespaces:
            args.append("--all-namespaces")
        args.extend(["--request-timeout=15s", "-o", "json"])
        value = _decode_json_result(
            self._run(args, "game_session_kubernetes_inventory_unavailable", timeout=timeout_seconds),
            "game_session_kubernetes_inventory_invalid",
        )
        return _require_resource_list(
            value,
            resource,
            "game_session_kubernetes_inventory_invalid",
            all_namespaces=all_namespaces,
        )

    def capture_cluster_inventory(self) -> dict[str, list[dict[str, Any]]]:
        deadline = time.monotonic() + FACTORY_INVENTORY_MAX_SECONDS
        result: dict[str, list[dict[str, Any]]] = {}
        for resource, namespaced in CLUSTER_INVENTORY_RESOURCES:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                _deny("game_session_factory_inventory_time_limit_exceeded")
            result[resource] = self._get_list(
                resource,
                all_namespaces=namespaced,
                timeout_seconds=min(30, max(1, int(remaining))),
            )
        validate_factory_inventory(result)
        self.api_discovery = self._capture_api_discovery(deadline)
        if time.monotonic() > deadline:
            _deny("game_session_factory_inventory_time_limit_exceeded")
        return result

    def _capture_api_discovery(self, deadline: float) -> dict[str, Any]:
        responses: list[dict[str, Any]] = []
        for path in ("/api", "/apis"):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                _deny("game_session_factory_inventory_time_limit_exceeded")
            result = self._run(
                ["get", "--raw", path, "--request-timeout=15s"],
                "game_session_api_discovery_unavailable",
                timeout=min(30, max(1, int(remaining))),
            )
            responses.append(_decode_json_result(result, "game_session_api_discovery_invalid"))
        return validate_api_discovery(responses[0], responses[1])


def run_factory_qualification(authority_path: Path, *, command_runner: Any = None,
                              fixture_api: Any = None, backend_factory: Any = None) -> dict[str, Any]:
    """Capture review-only inventory and delegate exact fixture cleanup to its owner."""
    api = fixture if fixture_api is None else fixture_api
    runner = api.CommandRunner() if command_runner is None else command_runner
    run_identity = None
    try:
        workspace_value = os.environ.get("GITHUB_WORKSPACE")
        if not workspace_value:
            _deny("trusted_workspace_unavailable")
        workspace = Path(workspace_value).resolve(strict=True)
        expected_authority = (workspace / "config" / "workflow-tool-versions.env").resolve(strict=True)
        if authority_path.resolve(strict=True) != expected_authority:
            _deny("workflow_tool_authority_path_mismatch")
        run_identity, fixture_receipt, _pins, kubeconfig, environment = api.create_fixture(
            runner, expected_authority
        )
        validate_runner_identity(run_identity)
        if (
            not isinstance(fixture_receipt, dict)
            or not isinstance(fixture_receipt.get("kubeSystemNamespaceUid"), str)
            or not UID_PATTERN.fullmatch(fixture_receipt["kubeSystemNamespaceUid"])
        ):
            _deny("trusted_fixture_kubernetes_identity_invalid_resources_retained")
        run_directory = run_identity.run_directory
        api.assert_run_directory_owned(run_identity, run_directory)
        api.verify_live_ownership(runner, run_identity, fixture_receipt, kubeconfig, environment)
        backend_type = FactoryInventoryBackend if backend_factory is None else backend_factory
        backend = backend_type(runner, run_identity, kubeconfig, environment)
        inventory = backend.capture_cluster_inventory()
        counts = validate_factory_inventory(inventory)
        api.verify_live_ownership(runner, run_identity, fixture_receipt, kubeconfig, environment)
        api_discovery = getattr(backend, "api_discovery", None)
        if not isinstance(api_discovery, dict):
            _deny("game_session_api_discovery_unavailable_resources_retained")
        evidence = _factory_inventory_evidence(
            run_identity, fixture_receipt, inventory, counts, api_discovery
        )
        evidence_path = run_directory / FACTORY_INVENTORY_ARTIFACT
        try:
            api.write_exclusive_json(evidence_path, evidence)
        except Exception:  # noqa: BLE001 -- fail closed and retain resources after an uncertain evidence write
            _deny("game_session_factory_inventory_evidence_write_failed_resources_retained")
        api.teardown_fixture(runner, run_identity, kubeconfig, environment)
        return {
            "outcome": "qualified_for_review_only",
            "qualificationState": "review_only_not_an_approved_baseline",
            "approvedFactoryBaseline": False,
            "cleanup": "removed",
            "resourceCounts": counts,
            "totalItems": sum(counts.values()),
            "apiGroupCount": api_discovery["groupCount"],
            "apiGroupVersionCount": api_discovery["groupVersionCount"],
            "inventoryArtifact": FACTORY_INVENTORY_ARTIFACT,
        }
    except FixtureDenied as error:
        return {
            "outcome": "denied",
            "reason": error.reason,
            "cleanup": "resources_retained" if getattr(runner, "resources_may_exist", False) else "not_created_or_not_claimed",
            "approvedFactoryBaseline": False,
        }
    except Exception:  # noqa: BLE001 -- return a sanitized denial and preserve uncertain cleanup state
        return {
            "outcome": "denied",
            "reason": "unexpected_factory_qualification_error",
            "cleanup": "resources_retained" if getattr(runner, "resources_may_exist", False) else "not_created_or_not_claimed",
            "approvedFactoryBaseline": False,
        }


def emit(value: dict[str, Any]) -> None:
    print(json.dumps(value, sort_keys=True, separators=(",", ":")))


def main(argv: list[str]) -> int:
    if len(argv) != 5 or argv[1] != "--authority" or argv[3] != "--operation":
        emit({"outcome": "denied", "reason": "usage_invalid", "cleanup": "not_created_or_not_claimed"})
        return 1
    if argv[4] != "qualify-factory":
        emit({"outcome": "denied", "reason": "usage_invalid", "cleanup": "not_created_or_not_claimed"})
        return 1
    try:
        result = run_factory_qualification(Path(argv[2]))
    except FixtureDenied as error:
        result = {
            "outcome": "denied",
            "reason": error.reason,
            "cleanup": "not_created_or_not_claimed",
            "approvedFactoryBaseline": False,
        }
    except Exception:  # noqa: BLE001 -- expose only the bounded public denial contract
        result = {
            "outcome": "denied",
            "reason": "unexpected_factory_qualification_error",
            "cleanup": "not_created_or_not_claimed",
            "approvedFactoryBaseline": False,
        }
    emit(result)
    return 0 if result.get("outcome") == "qualified_for_review_only" else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
