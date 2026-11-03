#!/usr/bin/env python3
"""Produce candidate bytes only; this is not a protected installation authority."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import uuid
from pathlib import Path
from typing import Any

import yaml
from yaml.events import AliasEvent

MAX_BYTES = 4 * 1024 * 1024
MAX_MANIFEST_BYTES = 16 * 1024 * 1024
MAX_MEMBERS = 1024
MAX_BINDINGS = 256
MAX_FAMILIES = 256
MAX_SOURCES = 64
MAX_NODES = 200000
MAX_DEPTH = 64
SCHEMA = "firemud-runtime-installation-inventory/v1"
DECLARATIONS_SCHEMA = "firemud-runtime-installation-declarations/v1"
ENVIRONMENTS = {
    "local-dev", "pr-preview", "dev-demo-cluster", "hobby-self-hosted",
    "staging", "production",
}
BINDING_FIELDS = {
    "migrations", "configurations", "mountedResources", "secretContracts", "externalBindings",
}
ROOT_FIELDS = {
    "schema", "artifactId", "installationId", "environment", "generation", "clusterUid",
    "namespaceUid", "namespace", "sourceCommit", "executor", "inputBindings", "members",
}
MEMBER_FIELDS = {
    "workloadApiVersion", "workloadKind", "workloadNamespace", "workloadName",
    "containerKind", "containerName", "ownerId", "ownership", "catalogSources", "families",
}
IDENTITY_FIELDS = (
    "workloadApiVersion", "workloadKind", "workloadNamespace", "workloadName",
    "containerKind", "containerName",
)
WORKLOAD_PATHS = {
    ("v1", "Pod"): ("spec",),
    ("apps/v1", "Deployment"): ("spec", "template", "spec"),
    ("apps/v1", "StatefulSet"): ("spec", "template", "spec"),
    ("apps/v1", "DaemonSet"): ("spec", "template", "spec"),
    ("apps/v1", "ReplicaSet"): ("spec", "template", "spec"),
    ("batch/v1", "Job"): ("spec", "template", "spec"),
    ("batch/v1", "CronJob"): ("spec", "jobTemplate", "spec", "template", "spec"),
}
KNOWN_WORKLOAD_KINDS = {kind for _, kind in WORKLOAD_PATHS} | {
    "ReplicationController", "PodTemplate", "List", "DeploymentList", "PodList",
    "StatefulSetList", "DaemonSetList", "ReplicaSetList", "JobList", "CronJobList",
}
CONTAINER_KEYS = {"containers", "initContainers", "ephemeralContainers"}
DIGEST = r"sha256:[0-9a-f]{64}"
IMAGE = r"[a-z0-9][a-z0-9./:_-]*@sha256:[0-9a-f]{64}"
DNS_LABEL = r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"
DNS_NAME = r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?"


class InventoryError(ValueError):
    """The candidate input is incomplete, ambiguous, or outside the closed contract."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise InventoryError(message)


def shape(value: Any, fields: set[str], label: str) -> dict:
    require(type(value) is dict and set(value) == fields, f"{label}: expected exact fields {sorted(fields)}")
    return value


def text(value: Any, label: str, maximum: int = 256, empty: bool = False) -> str:
    require(type(value) is str, f"{label}: expected string")
    require((empty or bool(value)) and len(value.encode("utf-8")) <= maximum, f"{label}: invalid length")
    require(value == value.strip() and not any(ord(c) < 32 or ord(c) == 127 for c in value), f"{label}: invalid whitespace/control character")
    return value


def matched(value: Any, pattern: str, label: str, maximum: int = 256) -> str:
    value = text(value, label, maximum)
    require(re.fullmatch(pattern, value) is not None, f"{label}: invalid format")
    return value


def array(value: Any, label: str, maximum: int) -> list:
    require(type(value) is list and len(value) <= maximum, f"{label}: expected array with at most {maximum} entries")
    return value


def unique(items: list, label: str) -> None:
    require(len(items) == len(set(items)), f"{label}: duplicate identity")


def digest(raw: bytes) -> str:
    return "sha256:" + hashlib.sha256(raw).hexdigest()


def bounded_tree(value: Any, depth: int = 0, budget: list[int] | None = None) -> None:
    if budget is None:
        budget = [MAX_NODES]
    budget[0] -= 1
    require(depth <= MAX_DEPTH and budget[0] >= 0, "document exceeds structural bounds")
    if type(value) is dict:
        require(all(type(key) is str for key in value), "mapping keys must be strings")
        for item in value.values():
            bounded_tree(item, depth + 1, budget)
    elif type(value) is list:
        for item in value:
            bounded_tree(item, depth + 1, budget)


def json_pairs(pairs: list) -> dict:
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


class ClosedLoader(yaml.SafeLoader):
    """Reject aliases, duplicate mappings and expansion/depth abuse before constructing."""

    def __init__(self, stream: str):
        super().__init__(stream)
        self.node_count = 0
        self.node_depth = 0

    def compose_node(self, parent: Any, index: Any) -> Any:
        require(not self.check_event(AliasEvent), "YAML aliases are not supported")
        self.node_count += 1
        self.node_depth += 1
        require(self.node_count <= MAX_NODES and self.node_depth <= MAX_DEPTH, "manifest exceeds structural bounds")
        try:
            return super().compose_node(parent, index)
        finally:
            self.node_depth -= 1

    def construct_mapping(self, node: Any, deep: bool = False) -> dict:
        require(isinstance(node, yaml.MappingNode), "expected YAML mapping")
        result = {}
        for key_node, value_node in node.value:
            key = self.construct_object(key_node, deep=deep)
            require(type(key) is str, "YAML mapping keys must be strings")
            require(key not in result, f"duplicate YAML mapping key: {key}")
            result[key] = self.construct_object(value_node, deep=deep)
        return result


def identity(member: dict) -> tuple:
    return tuple(member[field] for field in IDENTITY_FIELDS)


def validate_declarations(declarations: Any) -> dict:
    shape(declarations, ROOT_FIELDS | {"nonWorkloadKinds"}, "declarations")
    require(declarations["schema"] == DECLARATIONS_SCHEMA, "unsupported declarations schema")
    for field in ("artifactId", "installationId"):
        value = text(declarations[field], field, 36)
        try:
            parsed = uuid.UUID(value)
        except ValueError as exc:
            raise InventoryError(f"{field}: invalid UUID") from exc
        require(parsed.int != 0 and str(parsed) == value, f"{field}: expected canonical non-nil UUID")
    require(declarations["environment"] in ENVIRONMENTS, "invalid environment")
    for field in ("generation", "clusterUid", "namespaceUid"):
        text(declarations[field], field)
    matched(declarations["namespace"], DNS_LABEL, "namespace", 63)
    matched(declarations["sourceCommit"], r"[0-9a-f]{40}", "sourceCommit", 40)
    executor = shape(declarations["executor"], {"identity", "versionDigest"}, "executor")
    text(executor["identity"], "executor.identity", 512)
    matched(executor["versionDigest"], DIGEST, "executor.versionDigest")
    bindings = shape(declarations["inputBindings"], BINDING_FIELDS, "inputBindings")
    for category, entries in bindings.items():
        for entry in array(entries, category, MAX_BINDINGS):
            shape(entry, {"name", "version", "digest"}, category)
            text(entry["name"], f"{category}.name")
            text(entry["version"], f"{category}.version", 512)
            matched(entry["digest"], DIGEST, f"{category}.digest")
        unique([entry["name"] for entry in entries], category)
    classes = []
    for entry in array(declarations["nonWorkloadKinds"], "nonWorkloadKinds", 256):
        shape(entry, {"apiVersion", "kind"}, "nonWorkloadKinds")
        text(entry["apiVersion"], "apiVersion", 128)
        text(entry["kind"], "kind", 128)
        require(entry["kind"] not in KNOWN_WORKLOAD_KINDS, "known workload kind cannot be classified as non-workload")
        classes.append((entry["apiVersion"], entry["kind"]))
    unique(classes, "nonWorkloadKinds")
    require(classes == sorted(classes), "nonWorkloadKinds must be sorted")
    owner_families = []
    for member in array(declarations["members"], "members", MAX_MEMBERS):
        shape(member, MEMBER_FIELDS, "member")
        require((member["workloadApiVersion"], member["workloadKind"]) in WORKLOAD_PATHS, "member: unsupported workload")
        require(member["workloadNamespace"] == declarations["namespace"], "member namespace mismatch")
        matched(member["workloadName"], DNS_NAME, "workloadName", 253)
        matched(member["containerName"], DNS_LABEL, "containerName", 63)
        require(member["containerKind"] in {"APPLICATION", "INIT"}, "invalid containerKind")
        require(member["ownership"] in {"RUNTIME_OWNER", "NO_INSTANCE_STATE"}, "invalid ownership")
        text(member["ownerId"], "ownerId", empty=member["ownership"] == "NO_INSTANCE_STATE")
        sources = array(member["catalogSources"], "catalogSources", MAX_SOURCES)
        families = array(member["families"], "families", MAX_FAMILIES)
        if member["ownership"] == "NO_INSTANCE_STATE":
            require(not sources and not families, "NO_INSTANCE_STATE requires empty catalogSources and families")
        else:
            require(bool(sources), "RUNTIME_OWNER requires catalogSources")
        for source in sources:
            shape(source, {"sourceId", "contract", "epoch", "digest"}, "catalog source")
            text(source["sourceId"], "sourceId")
            text(source["contract"], "catalog contract", 512)
            matched(source["epoch"], r"[1-9][0-9]{0,18}", "catalog epoch", 19)
            matched(source["digest"], DIGEST, "catalog digest")
        source_ids = [source["sourceId"] for source in sources]
        unique(source_ids, "catalogSources")
        for family in families:
            shape(family, {"familyId", "catalogSourceId", "replacementClass", "cleanupContract"}, "family")
            text(family["familyId"], "familyId")
            text(family["catalogSourceId"], "catalogSourceId")
            require(family["catalogSourceId"] in source_ids, "family catalogSourceId does not resolve in member")
            require(family["replacementClass"] in {"S1", "S2", "S3", "UNRESOLVED"}, "invalid replacementClass")
            cleanup = shape(family["cleanupContract"], {"operation", "requestIdentity", "acknowledgement", "retention"}, "cleanupContract")
            for field, value in cleanup.items():
                text(value, f"cleanupContract.{field}", 512)
            owner_families.append((member["ownerId"], family["familyId"]))
    unique(owner_families, "owner/family")
    unique([identity(member) for member in declarations["members"]], "members")
    return declarations


def contains_containers(value: Any, excluded: Any = None) -> bool:
    if value is excluded:
        return False
    if type(value) is dict:
        return bool(CONTAINER_KEYS & value.keys()) or any(contains_containers(child, excluded) for child in value.values())
    if type(value) is list:
        return any(contains_containers(child, excluded) for child in value)
    return False


def rendered_containers(documents: list, declarations: dict) -> dict:
    classes = {(entry["apiVersion"], entry["kind"]) for entry in declarations["nonWorkloadKinds"]}
    observed_classes = set()
    workloads = set()
    containers = {}
    for document in documents:
        require(type(document) is dict, "rendered resource must be a mapping")
        api = text(document.get("apiVersion"), "resource apiVersion", 128)
        kind = text(document.get("kind"), "resource kind", 128)
        resource_type = (api, kind)
        if resource_type not in WORKLOAD_PATHS:
            require(resource_type in classes, f"unclassified rendered kind: {api}/{kind}")
            require(not contains_containers(document), "non-workload classification hides a PodSpec/container field")
            observed_classes.add(resource_type)
            continue
        metadata = document.get("metadata")
        require(type(metadata) is dict, "workload metadata must be a mapping")
        namespace = metadata.get("namespace")
        require(namespace == declarations["namespace"], "rendered workload namespace mismatch or missing")
        name = matched(metadata.get("name"), DNS_NAME, "rendered workload name", 253)
        workload_id = (api, kind, namespace, name)
        require(workload_id not in workloads, "duplicate rendered workload")
        workloads.add(workload_id)
        spec = document
        for field in WORKLOAD_PATHS[resource_type]:
            require(type(spec) is dict and type(spec.get(field)) is dict, "missing rendered PodSpec")
            spec = spec[field]
        require(not contains_containers(document, spec), "unsupported additional PodSpec/container field")
        require(not spec.get("ephemeralContainers"), "ephemeral containers have no supported inventory classification")
        applications = array(spec.get("containers"), "containers", MAX_MEMBERS)
        require(bool(applications), "workload requires an application container")
        init = array(spec.get("initContainers", []), "initContainers", MAX_MEMBERS)
        names = []
        for container_kind, entries in (("APPLICATION", applications), ("INIT", init)):
            for container in entries:
                require(type(container) is dict, "container must be a mapping")
                container_name = matched(container.get("name"), DNS_LABEL, "rendered container name", 63)
                names.append(container_name)
                image = matched(container.get("image"), IMAGE, "immutable image", 1024)
                container_id = workload_id + (container_kind, container_name)
                require(container_id not in containers, "duplicate rendered container")
                containers[container_id] = image
                require(len(containers) <= MAX_MEMBERS, "too many rendered containers")
        unique(names, "rendered application/init container names")
    require(observed_classes == classes, "extra non-workload classification has no rendered resource")
    return containers


def produce(rendered_bytes: bytes, declarations_bytes: bytes) -> bytes:
    """Bind exact supplied bytes; external input facts and installation remain unverified."""
    require(len(rendered_bytes) <= MAX_MANIFEST_BYTES, "rendered manifest too large")
    require(len(declarations_bytes) <= MAX_BYTES, "declarations too large")
    try:
        declarations = json.loads(
            declarations_bytes.decode("utf-8"), object_pairs_hook=json_pairs,
            parse_constant=lambda value: (_ for _ in ()).throw(InventoryError(f"invalid JSON constant: {value}")),
        )
        bounded_tree(declarations)
        validate_declarations(declarations)
        documents = [doc for doc in yaml.load_all(rendered_bytes.decode("utf-8"), Loader=ClosedLoader) if doc is not None]
        require(bool(documents), "empty rendered manifest")
        for document in documents:
            bounded_tree(document)
        containers = rendered_containers(documents, declarations)
        require(set(containers) == {identity(member) for member in declarations["members"]}, "rendered containers and ownership declarations differ")
        artifact = {key: value for key, value in declarations.items() if key != "nonWorkloadKinds"}
        artifact["schema"] = SCHEMA
        artifact["renderedManifestDigest"] = digest(rendered_bytes)
        artifact["declarationsDigest"] = digest(declarations_bytes)
        artifact["members"] = [dict(member, image=containers[identity(member)]) for member in sorted(declarations["members"], key=identity)]
        result = (json.dumps(artifact, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")
        require(len(result) <= MAX_BYTES, "candidate artifact too large")
        return result
    except (UnicodeError, json.JSONDecodeError, yaml.YAMLError, RecursionError, TypeError) as exc:
        raise InventoryError(f"invalid input: {exc}") from exc


def read_bounded(path: Path, limit: int) -> bytes:
    with path.open("rb") as source:
        raw = source.read(limit + 1)
    require(len(raw) <= limit, f"input exceeds {limit} bytes: {path}")
    return raw


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rendered-manifest", required=True, type=Path)
    parser.add_argument("--declarations", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        candidate = produce(read_bounded(args.rendered_manifest, MAX_MANIFEST_BYTES), read_bounded(args.declarations, MAX_BYTES))
        with args.output.open("xb") as output:
            output.write(candidate)
        return 0
    except (InventoryError, OSError) as exc:
        print(f"runtime-installation inventory refused: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
