#!/usr/bin/env python3
"""Offline candidate-production proof; no installation or authority is exercised."""

import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import yaml

MODULE_PATH = Path(__file__).with_name("runtime-installation-inventory.py")
SPEC = importlib.util.spec_from_file_location("runtime_installation_inventory", MODULE_PATH)
inventory = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(inventory)
DIGEST = "sha256:" + "a" * 64
IMAGE = "registry.example/independent/sidecar@" + DIGEST


def workload(kind="Deployment", name="world"):
    spec = {
        "containers": [{"name": "application", "image": IMAGE}, {"name": "foreign-sidecar", "image": IMAGE}],
        "initContainers": [{"name": "initializer", "image": IMAGE}],
    }
    api = "v1" if kind == "Pod" else "batch/v1" if kind in {"Job", "CronJob"} else "apps/v1"
    document = {"apiVersion": api, "kind": kind, "metadata": {"name": name, "namespace": "firemud"}}
    body = spec
    for field in reversed(inventory.WORKLOAD_PATHS[(api, kind)]):
        body = {field: body}
    document.update(body)
    return document


def member(document, name, container_kind="APPLICATION"):
    return {
        "workloadApiVersion": document["apiVersion"], "workloadKind": document["kind"],
        "workloadNamespace": "firemud", "workloadName": document["metadata"]["name"],
        "containerKind": container_kind, "containerName": name,
        "ownerId": "", "ownership": "NO_INSTANCE_STATE", "catalogSources": [], "families": [],
    }


def fixture(kinds=("Deployment",)):
    documents = [workload(kind, "workload-" + str(index)) for index, kind in enumerate(kinds)]
    declarations = {
        "schema": inventory.DECLARATIONS_SCHEMA,
        "artifactId": "e8975b6d-88ca-4b50-8307-e0f8c8cccd53",
        "installationId": "56b80b22-977d-47bb-89db-6705d84294b1",
        "environment": "staging", "generation": "captured-installation-generation",
        "clusterUid": "captured-cluster-uid", "namespaceUid": "captured-namespace-uid",
        "namespace": "firemud", "sourceCommit": "b" * 40,
        "executor": {"identity": "protected/executor/v1", "versionDigest": DIGEST},
        "inputBindings": {name: [] for name in inventory.BINDING_FIELDS},
        "nonWorkloadKinds": [],
        "members": [
            member(document, name, kind)
            for document in documents
            for name, kind in (("application", "APPLICATION"), ("foreign-sidecar", "APPLICATION"), ("initializer", "INIT"))
        ],
    }
    return documents, declarations


def encode(documents, declarations):
    return yaml.safe_dump_all(documents).encode(), json.dumps(declarations).encode()


def runtime_owner(declarations, replacement="S1"):
    owner = declarations["members"][0]
    owner.update({
        "ownerId": "world", "ownership": "RUNTIME_OWNER",
        "catalogSources": [{"sourceId": "world-sql", "contract": "world/catalog/v1", "epoch": "1", "digest": DIGEST}],
        "families": [{
            "familyId": "world-state", "catalogSourceId": "world-sql", "replacementClass": replacement,
            "cleanupContract": {
                "operation": "world/cleanup/v1", "requestIdentity": "world/request/v1",
                "acknowledgement": "world/ack/v1", "retention": "world/retention/v1",
            },
        }],
    })
    return owner


class InventoryTest(unittest.TestCase):
    def refuse(self, documents, declarations):
        with self.assertRaises(inventory.InventoryError):
            inventory.produce(*encode(documents, declarations))

    def test_checked_in_interoperability_vector_matches_exact_producer_bytes(self):
        directory = MODULE_PATH.parent / "fixtures" / "runtime-installation"
        self.assertEqual(
            inventory.produce((directory / "rendered.yaml").read_bytes(), (directory / "declarations.json").read_bytes()),
            (directory / "candidate.json").read_bytes(),
        )

    def test_complete_every_supported_workload_and_container(self):
        documents, declarations = fixture(("Pod", "Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job", "CronJob"))
        documents[0]["spec"]["initContainers"][0]["restartPolicy"] = "Always"
        raw_manifest, raw_declarations = encode(documents, declarations)
        artifact = json.loads(inventory.produce(raw_manifest, raw_declarations))
        self.assertEqual(len(artifact["members"]), 21)
        self.assertEqual({entry["image"] for entry in artifact["members"]}, {IMAGE})
        self.assertEqual(sum(entry["containerKind"] == "INIT" for entry in artifact["members"]), 7)
        self.assertEqual(sum(entry["containerName"] == "foreign-sidecar" for entry in artifact["members"]), 7)
        self.assertEqual(set(artifact), inventory.ROOT_FIELDS | {"renderedManifestDigest", "declarationsDigest"})

    def test_exact_bytes_deterministic_compact_sorted_output(self):
        documents, declarations = fixture(("CronJob", "Pod"))
        runtime_owner(declarations)
        raw_manifest, raw_declarations = encode(documents, declarations)
        candidate = inventory.produce(raw_manifest, raw_declarations)
        self.assertEqual(candidate, inventory.produce(raw_manifest, raw_declarations))
        artifact = json.loads(candidate)
        self.assertEqual(candidate, (json.dumps(artifact, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n").encode())
        self.assertEqual(artifact["renderedManifestDigest"], inventory.digest(raw_manifest))
        self.assertEqual(artifact["declarationsDigest"], inventory.digest(raw_declarations))
        self.assertEqual([inventory.identity(entry) for entry in artifact["members"]], sorted(inventory.identity(entry) for entry in artifact["members"]))
        changed_manifest = json.loads(inventory.produce(raw_manifest + b"\n", raw_declarations))
        changed_declarations = json.loads(inventory.produce(raw_manifest, raw_declarations + b"\n"))
        self.assertNotEqual(artifact["renderedManifestDigest"], changed_manifest["renderedManifestDigest"])
        self.assertNotEqual(artifact["declarationsDigest"], changed_declarations["declarationsDigest"])
        self.assertEqual(artifact["members"], changed_manifest["members"])
        self.assertEqual(artifact["members"], changed_declarations["members"])

    def test_explicit_captured_binding_digest_retained_without_secret_inference(self):
        documents, declarations = fixture()
        declarations["inputBindings"]["secretContracts"] = [{"name": "workload-identity", "version": "captured-v3", "digest": DIGEST}]
        artifact = json.loads(inventory.produce(*encode(documents, declarations)))
        self.assertEqual(artifact["inputBindings"], declarations["inputBindings"])
        self.assertNotIn("qualified", artifact)

    def test_unresolved_retained_without_claiming_qualified_launch(self):
        documents, declarations = fixture()
        runtime_owner(declarations, "UNRESOLVED")
        artifact = json.loads(inventory.produce(*encode(documents, declarations)))
        owner = next(entry for entry in artifact["members"] if entry["ownerId"] == "world")
        self.assertEqual(owner["families"][0]["replacementClass"], "UNRESOLVED")
        self.assertNotIn("qualified", artifact)
        self.assertNotIn("admission", artifact)

    def test_missing_extra_duplicate_container_declarations(self):
        for change in ("missing", "extra", "duplicate"):
            with self.subTest(change=change):
                documents, declarations = fixture()
                if change == "missing":
                    declarations["members"].pop()
                elif change == "extra":
                    declarations["members"].append(member(documents[0], "absent"))
                else:
                    declarations["members"].append(copy.deepcopy(declarations["members"][0]))
                self.refuse(documents, declarations)

    def test_non_workload_classification_exact_and_explicit(self):
        documents, declarations = fixture()
        documents.append({"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "config"}, "data": {"mode": "exact"}})
        self.refuse(documents, declarations)
        declarations["nonWorkloadKinds"] = [{"apiVersion": "v1", "kind": "ConfigMap"}]
        inventory.produce(*encode(documents, declarations))
        for change in ("extra", "duplicate", "workload", "unknown-field"):
            candidate = copy.deepcopy(declarations)
            with self.subTest(change=change):
                if change == "extra":
                    candidate["nonWorkloadKinds"].append({"apiVersion": "v1", "kind": "Secret"})
                elif change == "duplicate":
                    candidate["nonWorkloadKinds"] *= 2
                elif change == "workload":
                    candidate["nonWorkloadKinds"] = [{"apiVersion": "unsupported/v1", "kind": "Deployment"}]
                else:
                    candidate["nonWorkloadKinds"][0]["trusted"] = True
                self.refuse(documents, candidate)

    def test_hidden_podspec_and_unsupported_workloads_refused(self):
        for kind in ("CustomWorker", "ReplicationController", "List"):
            documents, declarations = fixture()
            documents.append({"apiVersion": "custom/v1", "kind": kind, "spec": {"template": {"spec": {"containers": [{"name": "hidden", "image": IMAGE}]}}}})
            declarations["nonWorkloadKinds"] = [{"apiVersion": "custom/v1", "kind": kind}]
            with self.subTest(kind=kind):
                self.refuse(documents, declarations)
        documents, declarations = fixture()
        documents[0]["otherTemplate"] = {"spec": {"containers": [{"name": "hidden", "image": IMAGE}]}}
        self.refuse(documents, declarations)

    def test_duplicate_yaml_and_json_keys_and_yaml_aliases(self):
        documents, declarations = fixture()
        raw_manifest, raw_declarations = encode(documents, declarations)
        for manifest in (
            raw_manifest + b"\n---\napiVersion: v1\nkind: ConfigMap\nkind: Pod\n",
            raw_manifest.replace(b"name: application", b"name: application\n      name: repeated"),
            b"apiVersion: v1\nkind: Pod\nmetadata: &metadata {name: a}\nspec: *metadata\n",
        ):
            with self.subTest(manifest=manifest[-80:]):
                with self.assertRaises(inventory.InventoryError):
                    inventory.produce(manifest, raw_declarations)
        for raw in (
            raw_declarations[:-1] + b',"schema":"firemud-runtime-installation-declarations/v1"}',
            raw_declarations.replace(b'"ownerId": ""', b'"ownerId": "", "ownerId": "world"', 1),
        ):
            with self.assertRaises(inventory.InventoryError):
                inventory.produce(raw_manifest, raw)

    def test_namespace_image_mutation_duplicate_workload_and_ephemeral_refused(self):
        for change in ("missing-namespace", "wrong-namespace", "mutable-image", "uppercase-digest", "duplicate-container", "duplicate-workload", "ephemeral"):
            documents, declarations = fixture(("Pod",))
            with self.subTest(change=change):
                if change == "missing-namespace":
                    del documents[0]["metadata"]["namespace"]
                elif change == "wrong-namespace":
                    documents[0]["metadata"]["namespace"] = "elsewhere"
                elif change == "mutable-image":
                    documents[0]["spec"]["containers"][0]["image"] = "example/world:latest"
                elif change == "uppercase-digest":
                    documents[0]["spec"]["containers"][0]["image"] = IMAGE.upper()
                elif change == "duplicate-container":
                    documents[0]["spec"]["initContainers"][0]["name"] = "application"
                elif change == "duplicate-workload":
                    documents.append(copy.deepcopy(documents[0]))
                else:
                    documents[0]["spec"]["ephemeralContainers"] = [{"name": "debug", "image": IMAGE}]
                self.refuse(documents, declarations)

    def test_closed_shapes_types_bounds_and_catalog_cross_references(self):
        changes = [
            lambda d: d.update(extra="field"),
            lambda d: d.update(artifactId="00000000-0000-0000-0000-000000000000"),
            lambda d: d.update(installationId="not-a-uuid"),
            lambda d: d.update(environment="unknown"),
            lambda d: d.update(generation=1),
            lambda d: d.update(clusterUid=""),
            lambda d: d.update(namespaceUid="x" * 257),
            lambda d: d.update(sourceCommit="main"),
            lambda d: d["executor"].update(versionDigest="sha256:no"),
            lambda d: d["inputBindings"].pop("migrations"),
            lambda d: d["inputBindings"].update(migrations=[{"name": "a", "version": "1", "digest": DIGEST}] * 2),
            lambda d: d["members"][0].update(image=IMAGE),
            lambda d: d["members"][0].update(containerKind="SIDECAR"),
            lambda d: d["members"][0].update(ownership="UNKNOWN"),
            lambda d: d["members"][0].update(ownerId=""),
            lambda d: d["members"][0].update(catalogSources=[]),
            lambda d: d["members"][0]["catalogSources"][0].update(epoch="01"),
            lambda d: d["members"][0]["catalogSources"][0].update(epoch="0"),
            lambda d: d["members"][0]["catalogSources"][0].update(epoch=1),
            lambda d: d["members"][0]["catalogSources"][0].update(digest="sha256:" + "A" * 64),
            lambda d: d["members"][0]["families"][0].update(catalogSourceId="absent"),
            lambda d: d["members"][0]["families"][0].update(replacementClass="OTHER"),
            lambda d: d["members"][0]["families"][0]["cleanupContract"].pop("retention"),
            lambda d: d["members"][0]["families"][0]["cleanupContract"].update(operation=""),
            lambda d: d["members"][0].update(ownership="NO_INSTANCE_STATE"),
            lambda d: d["members"][0]["families"].append(copy.deepcopy(d["members"][0]["families"][0])),
            lambda d: d.update(members=d["members"] * (inventory.MAX_MEMBERS + 1)),
        ]
        for index, change in enumerate(changes):
            documents, declarations = fixture()
            runtime_owner(declarations)
            change(declarations)
            with self.subTest(index=index):
                self.refuse(documents, declarations)

    def test_owner_family_unique_across_different_containers(self):
        documents, declarations = fixture()
        owner = runtime_owner(declarations)
        declarations["members"][1].update({field: copy.deepcopy(owner[field]) for field in ("ownerId", "ownership", "catalogSources", "families")})
        self.refuse(documents, declarations)

    def test_size_and_structure_bounds(self):
        documents, declarations = fixture()
        raw_manifest, raw_declarations = encode(documents, declarations)
        for manifest, raw in (
            (b" " * (inventory.MAX_MANIFEST_BYTES + 1), raw_declarations),
            (raw_manifest, b" " * (inventory.MAX_BYTES + 1)),
            (raw_manifest, b"[" * 100 + b"0" + b"]" * 100),
            (b"[" * 100 + b"0" + b"]" * 100, raw_declarations),
        ):
            with self.assertRaises(inventory.InventoryError):
                inventory.produce(manifest, raw)

    def test_cli_exclusive_output_creation_and_retained_inputs(self):
        documents, declarations = fixture()
        raw_manifest, raw_declarations = encode(documents, declarations)
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            manifest = directory / "manifest.yaml"
            declaration = directory / "declarations.json"
            output = directory / "candidate.json"
            manifest.write_bytes(raw_manifest)
            declaration.write_bytes(raw_declarations)
            command = [sys.executable, str(MODULE_PATH), "--rendered-manifest", str(manifest), "--declarations", str(declaration), "--output", str(output)]
            first = subprocess.run(command, capture_output=True, check=False)
            self.assertEqual(first.returncode, 0, first.stderr)
            self.assertEqual(output.read_bytes(), inventory.produce(raw_manifest, raw_declarations))
            output.write_bytes(b"retained-existing-evidence\n")
            second = subprocess.run(command, capture_output=True, check=False)
            self.assertNotEqual(second.returncode, 0)
            self.assertEqual(output.read_bytes(), b"retained-existing-evidence\n")
            self.assertEqual(manifest.read_bytes(), raw_manifest)
            self.assertEqual(declaration.read_bytes(), raw_declarations)


if __name__ == "__main__":
    unittest.main()
