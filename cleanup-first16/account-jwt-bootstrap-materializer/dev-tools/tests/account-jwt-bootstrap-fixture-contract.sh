#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export ACCOUNT_BOOTSTRAP_REPO_ROOT="$ROOT_DIR"

python3 - <<'PY'
import hashlib
import base64
import json
import os
import re
import shutil
import subprocess
import tempfile
import uuid
from pathlib import Path


root = Path(os.environ["ACCOUNT_BOOTSTRAP_REPO_ROOT"]).resolve()
script = root / "dev-tools/hosted/account-bootstrap/account-jwt-bootstrap-fixture.py"
entrypoint = root / "dev-tools/hosted/account-bootstrap/prove-account-jwt-bootstrap.sh"
redis_helper = root / "dev-tools/hosted/account-bootstrap/account-jwt-bootstrap-redis.py"
account_helper = root / "dev-tools/hosted/account-bootstrap/account-jwt-bootstrap-account.py"
head = "a" * 40

import importlib.util
import sys
account_spec = importlib.util.spec_from_file_location("account_fixture_contract", account_helper)
assert account_spec is not None and account_spec.loader is not None
account_contract = importlib.util.module_from_spec(account_spec)
sys.modules[account_spec.name] = account_contract
account_spec.loader.exec_module(account_contract)
materializer_source = (root / "k8s/account-jwt-materializer/src/main/java/net/firedevops/firemud/accountjwtmaterializer/Materializer.java").read_text()
materializer_main_source = (root / "k8s/account-jwt-materializer/src/main/java/net/firedevops/firemud/accountjwtmaterializer/MaterializerMain.java").read_text()
materializer_test_source = (root / "k8s/account-jwt-materializer/src/test/java/net/firedevops/firemud/accountjwtmaterializer/MaterializerFlowTest.java").read_text()
assert "Outcome observeSecretOnce()" in materializer_source
assert "Outcome.SECRET_OBSERVATION_RECORDED" in materializer_source
assert '"--observe-only".equals(args[0])' in materializer_main_source
assert "observeOnly ? materializer.observeSecretOnce() : materializer.reconcileOnce()" in materializer_main_source
assert "observationOnlyRecordsTheExactEmptySecretAndStopsBeforeGenerationOrPromotion" in materializer_test_source
fixture_spec = importlib.util.spec_from_file_location("account_fixture_public_contract", script)
assert fixture_spec is not None and fixture_spec.loader is not None
fixture_contract = importlib.util.module_from_spec(fixture_spec)
sys.modules[fixture_spec.name] = fixture_contract
fixture_spec.loader.exec_module(fixture_contract)
assert "account-jwt-api/validator-inventory.json" not in account_contract.PROTECTED_INPUTS
assert "account-jwt-api/validator-inventory.json" in account_contract.PROTECTED_MOUNTS
assert "account-jwt-materializer/binding.json" in account_contract.PROTECTED_INPUTS
assert "account-response-envelope/keyring" in account_contract.PROTECTED_INPUTS
assert "account-jwt-api/serving-ca.pem" in account_contract.PROTECTED_INPUTS
assert "account-coordination-redis/acl-password" in account_contract.PROTECTED_MOUNTS
assert "postgres/credentials" not in account_contract.PROTECTED_INPUTS
assert account_contract.PROTECTED_MOUNTS["account-jwt-api/binding.json"] == "/etc/firemud/account-jwt-api/binding.json"
assert account_contract.PROTECTED_MOUNTS["account-response-envelope/keyring"] == "/etc/firemud/account-response-envelope/keyring"
assert account_contract.PROTECTED_MOUNTS["account-login-digest/keyring"] == "/etc/firemud/account-login-digest/keyring"
assert account_contract.PROTECTED_MOUNTS["account-coordination-redis/acl-password"] == "/run/secrets/firemud/account-coordination-redis/acl-password"
account_environment = {
    item["name"]: item.get("value") for item in account_contract._account_environment("account-jwt-contract")
}
assert account_environment["FIREMUD_ACCOUNT_RESPONSE_ENVELOPE_KEYRING_PATH"] == "/etc/firemud/account-response-envelope"
assert account_environment["FIREMUD_ACCOUNT_GAMEPLAY_CANONICAL_LOGIN_DIGEST_KEYRING_PATH"] == "/etc/firemud/account-login-digest"
assert account_contract.PROTECTED_MOUNTS["account-response-envelope/keyring"].startswith(
    account_environment["FIREMUD_ACCOUNT_RESPONSE_ENVELOPE_KEYRING_PATH"] + "/"
)
assert account_contract.PROTECTED_MOUNTS["account-login-digest/keyring"].startswith(
    account_environment["FIREMUD_ACCOUNT_GAMEPLAY_CANONICAL_LOGIN_DIGEST_KEYRING_PATH"] + "/"
)

private_credential_path = "/var/lib/account-jwt-bootstrap-run/account-jwt-materializer-runtime/client.key"
private_credential_digest = "b" * 64
public_projection = fixture_contract.public_fixture(
    {
        "clusterName": "account-jwt-contract",
        "context": "kind-account-jwt-contract",
        "apiAddress": "https://127.0.0.1:6443",
        "kubeSystemNamespaceUid": "00000000-0000-4000-8000-000000000001",
        "kindVersion": "kind v0.33.0",
        "kubectlVersion": "kubectl v1.35.0",
        "kubernetesServerVersion": "v1.35.0",
        "nodeImage": "kindest/node@sha256:" + "c" * 64,
        "nodeContainers": [{"id": "d" * 64}],
        "accountFixture": {
            "state": "account_source_pod_proof_complete",
            "accountMountRoot": "/var/lib/account-jwt-bootstrap-run",
            "accountProtectedMounts": {
                "account-grpc/tls.key": {
                    "hostPath": "/var/lib/account-jwt-bootstrap-run/account-inputs/account-grpc/tls.key",
                    "mountPath": "/etc/firemud/account-grpc/tls.key",
                }
            },
            "accountProtectedFileSha256": {"account-grpc/tls.key": private_credential_digest},
            "accountProtectedMountReadback": [
                {"source": "account-grpc/tls.key", "hostPath": private_credential_path}
            ],
            "ownerMounts": {
                "materializerDirectory": "/var/lib/account-jwt-bootstrap-run/account-jwt-materializer-runtime",
                "files": {"client.key": {"path": private_credential_path, "sha256": private_credential_digest}},
            },
            "accountTlsLeafCertificateSha256": "a" * 64,
        },
    }
)


def public_tree(value):
    if isinstance(value, dict):
        for key, child in value.items():
            yield key
            yield from public_tree(child)
    elif isinstance(value, list):
        for child in value:
            yield from public_tree(child)
    elif isinstance(value, str):
        yield value


public_tree_values = list(public_tree(public_projection))
public_tree_set = set(public_tree_values)
assert not {
    "ownerMounts",
    "accountProtectedFileSha256",
    "accountProtectedMountReadback",
    "hostPath",
    "files",
    "materializerDirectory",
}.intersection(public_tree_set), public_projection
assert private_credential_path not in public_tree_set, public_projection
assert private_credential_digest not in public_tree_set, public_projection
assert "accountMountRoot" not in public_tree_set and "accountProtectedMounts" not in public_tree_set, public_projection
assert "a" * 64 in public_tree_set, public_projection

fake_tool = r'''#!/usr/bin/env python3
import base64
import hashlib
import json
import os
import re
import stat
import sys
import uuid
from pathlib import Path

tool = Path(sys.argv[0]).name
args = sys.argv[1:]
shim_tools = {"--kind-shim": "kind", "--kubectl-shim": "kubectl", "--docker-shim": "docker", "--git-shim": "git", "--openssl-shim": "openssl"}
if args[:1] and args[0] in shim_tools:
    tool = shim_tools[args[0]]
    args = args[1:]
state_path = Path(__file__).resolve().with_name("state.json")
state = json.loads(state_path.read_text())
logged_args = list(args)
if tool == "kubectl" and "exec" in logged_args and "psql" in logged_args and "--command" in logged_args:
    logged_args[logged_args.index("--command") + 1] = "<bounded-owner-state-query>"
state.setdefault("calls", []).append([tool, *logged_args])

def save():
    state_path.write_text(json.dumps(state, separators=(",", ":")))

def emit(value):
    print(json.dumps(value, separators=(",", ":")))

def uid(name):
    return str(uuid.uuid5(uuid.NAMESPACE_DNS, state.get("cluster", "none") + ":" + name))

def read_manifest(path):
    return json.loads(Path(path).read_text())

def object_record(item):
    kind = item["kind"]
    metadata = item["metadata"]
    name = metadata["name"]
    record = {
        "apiVersion": item.get("apiVersion", "v1"),
        "kind": kind,
        "metadata": {
            "name": name,
            "uid": uid(kind + ":" + name),
            "resourceVersion": "1",
            "labels": metadata.get("labels", {}),
            "annotations": metadata.get("annotations", {}),
        },
    }
    if kind == "Namespace":
        record["metadata"]["labels"] = record["metadata"]["labels"] | {"kubernetes.io/metadata.name": name}
    if "namespace" in metadata:
        record["metadata"]["namespace"] = metadata["namespace"]
    for field in ("spec", "rules", "roleRef", "subjects", "data", "type", "immutable", "automountServiceAccountToken"):
        if field in item and (kind != "Secret" or field in {"immutable", "type"}):
            record[field] = item[field]
    if kind == "Secret" and name == "account-coordination-redis-binding":
        record["data"] = item.get("data", {})
        record["type"] = item.get("type")
    if kind == "Service" and name == "account-service":
        record["spec"]["clusterIP"] = "10.0.0.15"
    if kind == "Pod":
        container = item["spec"]["containers"][0]
        image = container["image"]
        record["spec"].setdefault("nodeName", state.get("node_name", "fixture-control-plane"))
        account_pod = name == "account-service"
        account_image_id = state.get("account_image_id", "sha256:" + "c" * 64)
        record["status"] = {
            "phase": "Running",
            "podIP": "10.0.0.13" if account_pod else "10.0.0.12",
            "conditions": (
                [
                    {"type": "ContainersReady", "status": "True"},
                    {"type": "Ready", "status": "False"},
                    {"type": "firemud.org/account-jwt-bootstrap-ready", "status": "False"},
                ]
                if account_pod
                else [{"type": "Ready", "status": "True"}]
            ),
            "containerStatuses": [{
                "name": container["name"],
                "ready": True,
                "image": image,
                "imageID": "containerd://" + account_image_id if account_pod else "docker-pullable://" + image,
                "state": {"running": {"startedAt": "2026-01-01T00:00:00Z"}},
            }],
        }
        if account_pod and state.get("account_pod_image_mismatch"):
            record["status"]["containerStatuses"][0]["imageID"] = "containerd://sha256:" + "d" * 64
        if account_pod and state.get("account_pod_config_mismatch"):
            record["metadata"]["annotations"]["firemud.org/account-jwt-bootstrap-account-config-sha256"] = "0" * 64
        if account_pod and state.get("account_pod_spec_mismatch"):
            record["spec"]["nodeName"] = "unowned-node"
        if account_pod and state.get("account_pod_ready_gate_open"):
            record["status"]["conditions"][-1]["status"] = "True"
        if account_pod and state.get("account_pod_api_projection_mismatch"):
            for volume in record["spec"].get("volumes", []):
                if volume.get("name") == "account-jwt-api-token":
                    volume["projected"]["sources"][0]["serviceAccountToken"]["audience"] = "https://unexpected.example.invalid"
        if account_pod:
            state["account_pod_projection"] = {
                "volumes": item["spec"].get("volumes", []),
                "volumeMounts": container.get("volumeMounts", []),
                "fsGroup": item["spec"].get("securityContext", {}).get("fsGroup"),
            }
    return record

def account_receiver_records(deployment_record):
    deployment_name = deployment_record["metadata"]["name"]
    namespace = deployment_record["metadata"]["namespace"]
    deployment_uid = deployment_record["metadata"]["uid"]
    deployment_spec = deployment_record["spec"]
    template = deployment_spec["template"]
    selector = deployment_spec["selector"]["matchLabels"]
    replica_set_name = deployment_name + "-1234567890"
    replica_set_uid = uid("ReplicaSet:" + replica_set_name)
    pod_name = replica_set_name + "-abcde"
    pod_uid = uid("Pod:" + pod_name)
    template_labels = template["metadata"]["labels"] | {"pod-template-hash": "1234567890"}
    template_annotations = json.loads(json.dumps(template["metadata"]["annotations"]))
    template_spec = json.loads(json.dumps(template["spec"]))
    replica_set = {
        "apiVersion": "apps/v1",
        "kind": "ReplicaSet",
        "metadata": {
            "name": replica_set_name,
            "namespace": namespace,
            "uid": replica_set_uid,
            "labels": template_labels,
            "annotations": template_annotations,
            "ownerReferences": [{"kind": "Deployment", "name": deployment_name, "uid": deployment_uid, "controller": True}],
        },
        "spec": {
            "replicas": 1,
            "selector": {"matchLabels": selector | {"pod-template-hash": "1234567890"}},
            "template": {"metadata": {"labels": template_labels, "annotations": template_annotations}, "spec": template_spec},
        },
    }
    pod = {
        "apiVersion": "v1",
        "kind": "Pod",
        "metadata": {
            "name": pod_name,
            "namespace": namespace,
            "uid": pod_uid,
            "labels": template_labels,
            "annotations": template_annotations,
            "ownerReferences": [{"kind": "ReplicaSet", "name": replica_set_name, "uid": replica_set_uid, "controller": True}],
        },
        "spec": json.loads(json.dumps(template_spec)),
        "status": {
            "phase": "Running",
            "podIP": "10.0.0.13",
            "conditions": [
                {"type": "ContainersReady", "status": "True"},
                {"type": "Ready", "status": "False"},
                {"type": "firemud.org/account-jwt-bootstrap-ready", "status": "False"},
            ],
            "containerStatuses": [{
                "name": "account-service",
                "ready": True,
                "image": template_spec["containers"][0]["image"],
                "imageID": "containerd://" + state.get("account_image_id", "sha256:" + "c" * 64),
                "state": {"running": {"startedAt": "2026-01-01T00:00:00Z"}},
            }],
        },
    }
    if state.get("account_pod_image_mismatch"):
        pod["status"]["containerStatuses"][0]["imageID"] = "containerd://sha256:" + "d" * 64
    if state.get("account_pod_config_mismatch"):
        pod["metadata"]["annotations"]["firemud.org/account-jwt-bootstrap-account-config-sha256"] = "0" * 64
    if state.get("account_pod_spec_mismatch"):
        pod["spec"]["nodeName"] = "unowned-node"
    if state.get("account_pod_ready_gate_open"):
        pod["status"]["conditions"][-1]["status"] = "True"
    if state.get("account_pod_api_projection_mismatch"):
        for volume in pod["spec"].get("volumes", []):
            if volume.get("name") == "account-jwt-api-token":
                volume["projected"]["sources"][0]["serviceAccountToken"]["audience"] = "https://unexpected.example.invalid"
    state["account_pod_name"] = pod_name
    state["account_pod_uid"] = pod_uid
    state["account_replica_set_name"] = replica_set_name
    state["account_replica_set_uid"] = replica_set_uid
    state["account_pod_projection"] = {
        "volumes": template_spec.get("volumes", []),
        "volumeMounts": template_spec["containers"][0].get("volumeMounts", []),
        "fsGroup": template_spec.get("securityContext", {}).get("fsGroup"),
    }
    return replica_set, pod

def option(name):
    return args[args.index(name) + 1]

def fixture_namespace():
    suffix = hashlib.sha256(
        f"{state['repository']}/{state['run_id']}/{state['attempt']}/{state['head']}".encode()
    ).hexdigest()[:16]
    return "account-jwt-" + suffix

def account_input_run_directory():
    run_directory = Path(state["runner_temp"]) / (
        "account-jwt-bootstrap-"
        + state["run_id"]
        + "-"
        + state["attempt"]
        + "-"
        + state["head"][:12]
    )
    return run_directory

def observe_and_tamper_account_inputs():
    run_directory = account_input_run_directory()
    root = run_directory / "account-inputs"
    owner_path = run_directory / "account-inputs-owner.json"
    if not root.is_dir() or state.get("account_inputs_observed"):
        return
    owner = json.loads(owner_path.read_text())
    files = {
        relative: (root / relative).read_bytes()
        for relative in owner["files"]
        if (root / relative).is_file() and not (root / relative).is_symlink()
    }
    api = json.loads(files["account-jwt-api/binding.json"])
    api_content = {key: value for key, value in api.items() if key != "bindingDigest"}
    api_digest_valid = hashlib.sha256(
        json.dumps(api_content, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    ).hexdigest() == api["bindingDigest"]
    ca_bytes = files["account-jwt-api/serving-ca.pem"]
    materializer_fields = dict(
        line.split("=", 1) for line in files["account-jwt-materializer/binding.json"].decode().splitlines()
    )
    materializer_digest_fields = {
        "digestVersion": "account-jwt-signer-materializer-binding/v1",
        "configRevision": materializer_fields["configRevision"],
        "environmentId": materializer_fields["environmentId"],
        "clusterId": materializer_fields["clusterId"],
        "namespace": materializer_fields["namespace"],
        "expectedClusterIncarnationUid": materializer_fields["expectedClusterIncarnationUid"],
        "expectedNamespaceUid": materializer_fields["expectedNamespaceUid"],
        "expectedPeerUri": materializer_fields["expectedPeerUri"],
        "peerSpkiSha256Pins": [materializer_fields["peerSpkiSha256Pins"]],
    }
    materializer_digest_valid = hashlib.sha256(
        json.dumps(materializer_digest_fields, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    ).hexdigest() == materializer_fields["bindingDigest"]
    def keyring_valid(relative, header):
        lines = files[relative].decode("ascii").splitlines()
        fields = lines[1].split(" ") if len(lines) == 2 else []
        if relative.endswith("login-digest/keyring"):
            key_field = fields[3] if len(fields) == 4 else ""
        else:
            key_field = fields[2] if len(fields) == 3 else ""
        try:
            key = base64.urlsafe_b64decode(key_field + "=" * (-len(key_field) % 4))
        except (ValueError, TypeError):
            return False
        return lines[0] == header and len(key) == 32 and base64.urlsafe_b64encode(key).rstrip(b"=").decode() == key_field
    namespace = fixture_namespace()
    state["account_inputs_observed"] = {
        "ownerMatchesRun": owner.get("runId") == state["run_id"]
        and owner.get("runAttempt") == state["attempt"]
        and owner.get("commit") == state["head"]
        and owner.get("namespace") == namespace
        and owner.get("clusterName") == state["cluster"],
        "ownerDigestsMatch": all(
            hashlib.sha256(files[relative]).hexdigest() == digest
            for relative, digest in owner["files"].items()
            if relative in files
        ) and set(files) == set(owner["files"]),
        "privateTreeModes": stat.S_IMODE(root.stat().st_mode) == 0o700
        and all(stat.S_IMODE((root / relative).stat().st_mode) == 0o600 for relative in files),
        "apiBindingDigestValid": api_digest_valid,
        "apiBindingMatchesRun": api["apiServerUrl"] == "https://kubernetes.default.svc.cluster.local:443"
        and api["tlsServerName"] == "kubernetes.default.svc.cluster.local"
        and api["namespace"] == namespace
        and api["expectedApiUsername"] == "system:serviceaccount:" + namespace + ":account-service"
        and api["servingCaSha256"] == hashlib.sha256(ca_bytes).hexdigest(),
        "materializerBindingDigestValid": materializer_digest_valid,
        "materializerBindingMatchesRun": materializer_fields["namespace"] == namespace
        and materializer_fields["expectedPeerUri"] == "spiffe://firemud/ns/" + namespace + "/sa/account-jwt-materializer"
        and len(materializer_fields["peerSpkiSha256Pins"]) == 64,
        "keyringFormatsValid": keyring_valid(
            "account-response-envelope/keyring", "firemud-account-response-envelope-keyring-v1"
        ) and keyring_valid(
            "account-login-digest/keyring", "firemud-account-credential-request-digest-keyring-v1"
        ),
        "materializerClientKeyPresent": bool(files.get("account-jwt-materializer/client.key"))
        and bool(files.get("account-jwt-materializer/client.crt")),
        "accountJwtSigningKeyAbsent": not any("jwt-signing" in name for name in files),
    }
    if state.get("malformed_account_input"):
        (root / "account-jwt-api/binding.json").write_bytes(b"{")
    elif state.get("mismatched_account_input"):
        (root / "account-jwt-api/binding.json").write_bytes(b"changed-after-owner-readback")
    elif state.get("missing_account_input"):
        (root / state["missing_account_input"]).unlink()
    elif state.get("account_input_parent_symlink"):
        actual = root / "account-jwt-api"
        external = run_directory / "external-account-api-inputs"
        actual.rename(external)
        actual.symlink_to(external, target_is_directory=True)
    elif state.get("account_input_leaf_symlink"):
        actual = root / "account-response-envelope/keyring"
        external = run_directory / "external-response-keyring"
        actual.rename(external)
        actual.symlink_to(external)
    state["account_inputs_tampered"] = any(
        state.get(name)
        for name in (
            "malformed_account_input",
            "mismatched_account_input",
            "missing_account_input",
            "account_input_parent_symlink",
            "account_input_leaf_symlink",
        )
    )

if tool == "git":
    observe_and_tamper_account_inputs()
    if args[-2:] == ["rev-parse", "--show-toplevel"]:
        print(args[1])
    elif args[-2:] == ["rev-parse", "HEAD"]:
        print(state["head"])
    elif args[-2:] == ["--porcelain", "--untracked-files=all"]:
        pass
    else:
        save()
        sys.exit(27)
elif tool == "kind":
    if args == ["version"]:
        print("kind v0.33.0 go1.25.0 linux/amd64")
    elif args[:2] == ["config", "get-contexts"]:
        print("old-context" if state.get("existing_context") else "")
    elif args[:2] == ["get", "clusters"]:
        if state.get("created"):
            print(state["cluster"])
    elif args[:2] == ["create", "cluster"]:
        if state.get("create_failure"):
            save()
            sys.exit(17)
        state["cluster"] = option("--name")
        state["image"] = option("--image")
        state["node_name"] = state["cluster"] + "-control-plane"
        state["node_id"] = hashlib.sha256(("node:" + state["cluster"]).encode()).hexdigest()
        state["network_id"] = hashlib.sha256(("network:" + state["cluster"]).encode()).hexdigest()
        state["network_name"] = "kind"
        state["created"] = True
        state["network_exists"] = True
        kubeconfig = Path(os.environ["KUBECONFIG"])
        kubeconfig.write_text("fixture-only kubeconfig\n")
        kubeconfig.chmod(0o600)
    elif args[:2] == ["load", "docker-image"]:
        state["loaded_account_image"] = args[2]
    elif args[:2] == ["get", "nodes"]:
        print(state["node_name"])
    elif args[:2] == ["delete", "cluster"]:
        if state.get("delete_failure"):
            save()
            sys.exit(19)
        if option("--name") != state.get("cluster"):
            save()
            sys.exit(20)
        state["created"] = False
        state["redis_namespace_exists"] = False
        state["node_files"] = {}
        state["node_directories"] = {}
        if state.get("unrelated_network_after_delete"):
            state["additional_network_id"] = state["unrelated_network_after_delete"]
        if state.get("network_id_drift_after_delete"):
            state["network_id"] = "e" * 64
        if state.get("network_name_drift_after_delete"):
            state["network_name"] = "renamed-kind-network"
        if not state.get("network_survives_delete", True):
            state["network_exists"] = False
    else:
        save()
        sys.exit(21)
elif tool == "kubectl":
    if args[:2] == ["version", "--client"]:
        emit({"clientVersion": {"gitVersion": "v1.35.9"}})
    elif "version" in args and "--client" not in args:
        emit({"serverVersion": {"gitVersion": "v1.36.0" if state.get("server_version_mismatch") else "v1.35.8"}})
    elif args[:3] == ["config", "get-contexts", "-o"]:
        if state.get("existing_context"):
            print("old-context")
    elif "current-context" in args:
        state["context_reads"] = state.get("context_reads", 0) + 1
        context = "kind-" + state["cluster"]
        if state.get("context_drift") and state["context_reads"] > 1:
            context = "kind-other"
        print(context)
    elif "view" in args and "--minify" in args:
        state["config_reads"] = state.get("config_reads", 0) + 1
        server = "https://127.0.0.1:9443"
        if state.get("api_drift") and state["config_reads"] > 1:
            server = "https://127.0.0.1:9444"
        output_option = args[args.index("-o") + 1] if "-o" in args else ""
        if output_option == "jsonpath={.clusters[0].cluster.server}":
            print(server)
        elif output_option == "jsonpath={.clusters[0].cluster.certificate-authority-data}":
            ca = b"-----BEGIN CERTIFICATE-----\nYXBpLWNh\n-----END CERTIFICATE-----\n"
            encoded = "not-base64!" if state.get("account_api_server_ca_invalid") else base64.b64encode(ca).decode()
            print(encoded)
        else:
            context = "kind-" + state["cluster"]
            emit({"current-context": context, "contexts": [{"name": context}], "clusters": [{"cluster": {"server": server}}]})
    elif "namespace" in args and "kube-system" in args:
        state["uid_reads"] = state.get("uid_reads", 0) + 1
        uid = "12345678-1234-4234-9234-123456789abc"
        if state.get("uid_drift") and state["uid_reads"] > 1:
            uid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        emit({"kind": "Namespace", "metadata": {"uid": uid}, "status": {"phase": "Active"}})
    elif "create" in args and "-f" in args:
        manifest = read_manifest(option("-f"))
        if manifest.get("kind") == "Namespace":
            state["redis_namespace"] = manifest["metadata"]["name"]
            state["redis_namespace_exists"] = True
            state["redis_namespace_object"] = object_record(manifest)
            state["redis_namespace_object"]["status"] = {"phase": "Active"}
            print("namespace/" + state["redis_namespace"] + " created")
        elif manifest.get("kind") == "List":
            if any(item["kind"] == "Pod" and item["metadata"]["name"] == "redis-primary" for item in manifest["items"]):
                state["redis_objects"] = {}
                for item in manifest["items"]:
                    record = object_record(item)
                    state["redis_objects"][item["kind"].lower() + "/" + item["metadata"]["name"]] = record
                state["redis_image"] = state["redis_objects"]["pod/redis-primary"]["spec"]["containers"][0]["image"]
            elif any(item["kind"] == "Pod" and item["metadata"]["name"] == "account-postgres" for item in manifest["items"]):
                state.setdefault("account_objects", {})
                for item in manifest["items"]:
                    record = object_record(item)
                    state["account_objects"][item["kind"].lower() + "/" + item["metadata"]["name"]] = record
                    if item["kind"] == "Pod":
                        state["postgres_image"] = item["spec"]["containers"][0]["image"]
                print("Account PostgreSQL resources created")
                save()
                sys.exit(0)
            elif any(
                item["kind"] == "ServiceAccount"
                and item["metadata"]["name"] == "account-jwt-materializer"
                for item in manifest["items"]
            ):
                state.setdefault("account_objects", {})
                for item in manifest["items"]:
                    record = object_record(item)
                    state["account_objects"][item["kind"].lower() + "/" + item["metadata"]["name"]] = record
                state["account_owner_access_created"] = True
                print("Account owner identities and exact RBAC created")
                save()
                sys.exit(0)
            elif any(item["kind"] == "Deployment" for item in manifest["items"]):
                state.setdefault("account_objects", {})
                for item in manifest["items"]:
                    record = object_record(item)
                    key = item["kind"].lower() + "/" + item["metadata"]["name"]
                    state["account_objects"][key] = record
                deployment = state["account_objects"]["deployment/account-service"]
                replica_set, pod = account_receiver_records(deployment)
                state["account_objects"]["replicaset/" + replica_set["metadata"]["name"]] = replica_set
                state["account_objects"]["pod/" + pod["metadata"]["name"]] = pod
                account_pod_spec = deployment["spec"]["template"]["spec"]
                account_mounts = account_pod_spec["containers"][0]["volumeMounts"]
                account_volumes = account_pod_spec["volumes"]
                api_token_volume = next(volume for volume in account_volumes if volume["name"] == "account-jwt-api-token")
                service = state["account_objects"]["service/account-service"]
                state["account_mount_contract_probe"] = {
                    "resources": sorted(item["kind"] + "/" + item["metadata"]["name"] for item in manifest["items"])
                    + ["ReplicaSet/" + replica_set["metadata"]["name"], "Pod/" + pod["metadata"]["name"]],
                    "podAutomountServiceAccountToken": account_pod_spec.get("automountServiceAccountToken"),
                    "serviceAccountAutomountServiceAccountToken": next(
                        item for item in manifest["items"] if item["kind"] == "ServiceAccount"
                    ).get("automountServiceAccountToken"),
                    "readinessGates": account_pod_spec.get("readinessGates"),
                    "hostPathFileVolumes": all(
                        set(volume) == {"name", "hostPath"}
                        and volume["hostPath"].get("type") in {"File", "Directory"}
                        for volume in account_volumes if volume["name"].startswith("protected-")
                    ),
                    "apiDirectoryVolume": next(
                        volume for volume in account_volumes
                        if volume["name"] == "protected-account-jwt-api-directory"
                    ),
                    "readOnlyProtectedMounts": all(
                        mount.get("readOnly") is True for mount in account_mounts if mount["name"] != "tmp"
                    ),
                    "onlyApiTokenProjectedVolume": api_token_volume == {
                        "name": "account-jwt-api-token",
                        "projected": {
                            "defaultMode": 0o440,
                            "sources": [{"serviceAccountToken": {
                                "audience": "https://kubernetes.default.svc.cluster.local",
                                "expirationSeconds": 600,
                                "path": "token",
                            }}],
                        },
                    } and all(
                        "projected" not in volume or volume is api_token_volume
                        for volume in account_volumes
                    ),
                    "canonicalSigningSecretProjection": {
                        "volume": next(volume for volume in account_volumes if volume["name"] == "jwt-signing-keys"),
                        "mount": next(mount for mount in account_mounts if mount["name"] == "jwt-signing-keys"),
                    },
                    "canonicalJwksProjection": {
                        "volume": next(volume for volume in account_volumes if volume["name"] == "jwt-jwks"),
                        "mount": next(mount for mount in account_mounts if mount["name"] == "jwt-jwks"),
                    },
                    "roleRules": None,
                    "roleBinding": None,
                    "clusterRoleRules": None,
                    "clusterRoleBinding": None,
                    "inventoryDigestAnnotation": None,
                    "noService": False,
                    "serviceSelector": service["spec"]["selector"],
                    "servicePorts": service["spec"]["ports"],
                    "mountPaths": sorted(mount["mountPath"] for mount in account_mounts if mount["name"] != "tmp"),
                    "nodeMountPaths": sorted(state.get("mount_copy_paths", [])),
                    "containerSecurityContext": account_pod_spec["containers"][0].get("securityContext"),
                    "podSecurityContext": account_pod_spec.get("securityContext"),
                    "environment": account_pod_spec["containers"][0].get("env"),
                    "imagePullPolicy": account_pod_spec["containers"][0].get("imagePullPolicy"),
                    "serverTls": {item["name"]: item.get("value") for item in account_pod_spec["containers"][0]["env"] if item["name"].startswith("SERVER_")},
                }
                print("Account receiver workload created")
                save()
                sys.exit(0)
            elif any(item["kind"] == "Role" for item in manifest["items"]):
                state.setdefault("account_objects", {})
                for item in manifest["items"]:
                    record = object_record(item)
                    state["account_objects"][item["kind"].lower() + "/" + item["metadata"]["name"]] = record
                api_role = next(item for item in manifest["items"] if item["kind"] == "Role")
                api_role_binding = next(item for item in manifest["items"] if item["kind"] == "RoleBinding")
                api_cluster_role = next(item for item in manifest["items"] if item["kind"] == "ClusterRole")
                api_cluster_role_binding = next(item for item in manifest["items"] if item["kind"] == "ClusterRoleBinding")
                probe = state["account_mount_contract_probe"]
                probe["resources"] += sorted(item["kind"] + "/" + item["metadata"]["name"] for item in manifest["items"])
                probe.update({
                    "roleRules": api_role["rules"],
                    "roleBinding": {"roleRef": api_role_binding["roleRef"], "subjects": api_role_binding["subjects"]},
                    "clusterRoleRules": api_cluster_role["rules"],
                    "clusterRoleBinding": {"roleRef": api_cluster_role_binding["roleRef"], "subjects": api_cluster_role_binding["subjects"]},
                    "inventoryDigestAnnotation": api_role["metadata"]["annotations"].get("firemud.org/account-jwt-bootstrap-inventory-sha256"),
                    "nodeMountPaths": sorted(state.get("mount_copy_paths", [])),
                })
                print("Account API source RBAC created after inventory readback")
                save()
                sys.exit(0)
            else:
                save()
                sys.exit(29)
            binding_secret = next(
                item for item in manifest["items"]
                if item["kind"] == "Secret" and item["metadata"]["name"] == "account-coordination-redis-binding"
            )
            binding = json.loads(base64.b64decode(binding_secret["data"]["binding.json"]))
            ca_secret = next(
                item for item in manifest["items"]
                if item["kind"] == "Secret" and item["metadata"]["name"] == "redis-primary-tls"
            )
            auth_secrets = {
                item["metadata"]["name"]: item
                for item in manifest["items"]
                if item["kind"] == "Secret" and item["metadata"]["name"] in {"redis-primary-auth", "redis-replica-auth"}
            }

            def acl_contract(secret):
                lines = base64.b64decode(secret["data"]["users.acl"]).decode().splitlines()
                users = {}
                for line in lines:
                    parts = line.split()
                    if len(parts) < 2 or parts[0] != "user":
                        continue
                    users[parts[1]] = {
                        "enabled": len(parts) > 2 and parts[2] == "on",
                        "keys": sorted(value for value in parts[2:] if value.startswith("~")),
                        "commands": sorted(value for value in parts[2:] if value.startswith("+")),
                    }
                return {"users": users, "dataKeys": sorted(secret["data"])}

            pods = {
                item["metadata"]["name"]: item
                for item in manifest["items"]
                if item["kind"] == "Pod"
            }
            pod_auth_secret = {
                name: next(
                    volume["secret"]["secretName"]
                    for volume in pod["spec"]["volumes"]
                    if volume["name"] == "auth"
                )
                for name, pod in pods.items()
            }
            config = next(
                item for item in manifest["items"]
                if item["kind"] == "ConfigMap" and item["metadata"]["name"] == "redis-config"
            )
            state["redis_acl_contract_probe"] = {
                "primary": acl_contract(auth_secrets["redis-primary-auth"]),
                "replica": acl_contract(auth_secrets["redis-replica-auth"]),
                "podAuthSecret": pod_auth_secret,
                "aclfilePaths": {
                    role: next(line.split(None, 1)[1] for line in config["data"][role + ".conf"].splitlines() if line.startswith("aclfile "))
                    for role in ("primary", "replica")
                },
            }
            binding_digest = binding.pop("bindingDigest")
            state["account_binding_probe"] = {
                "fields": sorted(binding),
                "digestValid": hashlib.sha256(json.dumps(binding, sort_keys=True, separators=(",", ":")).encode()).hexdigest() == binding_digest,
                "caHashValid": hashlib.sha256(base64.b64decode(ca_secret["data"]["ca.crt"])).hexdigest() == binding["servingCaSha256"],
                "passwordMatchesAccountUser": binding_secret["data"]["acl-password"] == next(
                    item for item in manifest["items"]
                    if item["kind"] == "Secret" and item["metadata"]["name"] == "redis-primary-auth"
                )["data"]["account-password"],
                "coordinationHost": binding["coordinationHost"],
                "tlsServerName": binding["tlsServerName"],
                "port": binding["coordinationPort"],
                "aclUsername": binding["aclUsername"],
                "caPath": binding["servingCaPath"],
                "credentialPath": binding["credentialPath"],
            }
            print("fixture resources created")
        elif manifest.get("kind") in {"Secret", "ConfigMap"}:
            record = object_record(manifest)
            state.setdefault("account_objects", {})[
                manifest["kind"].lower() + "/" + manifest["metadata"]["name"]
            ] = record
            if manifest["kind"] == "Secret":
                state["account_owner_signing_secret_created_empty"] = not manifest.get("data")
            else:
                state["account_owner_jwks_configmap_created"] = manifest.get("data") == {"jwks.json": '{"keys":[]}'}
            print(manifest["kind"].lower() + "/" + manifest["metadata"]["name"] + " created")
        elif manifest.get("kind") == "Job":
            metadata = manifest["metadata"]
            name = metadata["name"]
            stage = metadata["labels"]["firemud.org/account-jwt-bootstrap-owner-job"]
            job_record = object_record(manifest)
            job_record["status"] = {
                "succeeded": 1,
                "failed": 0,
                "conditions": [{"type": "Complete", "status": "True"}],
            }
            state.setdefault("account_objects", {})["job/" + name] = job_record
            template = manifest["spec"]["template"]
            pod_name = name + "-abcde"
            pod_labels = dict(template["metadata"]["labels"])
            pod_labels.update({
                "batch.kubernetes.io/controller-uid": job_record["metadata"]["uid"],
                "batch.kubernetes.io/job-name": name,
            })
            pod_spec = json.loads(json.dumps(template["spec"]))
            container = pod_spec["containers"][0]
            pod_uid = uid("Pod:" + pod_name)
            pod = {
                "apiVersion": "v1",
                "kind": "Pod",
                "metadata": {
                    "name": pod_name,
                    "namespace": metadata["namespace"],
                    "uid": pod_uid,
                    "resourceVersion": "1",
                    "labels": pod_labels,
                    "annotations": template["metadata"]["annotations"],
                    "ownerReferences": [{
                        "apiVersion": "batch/v1",
                        "kind": "Job",
                        "name": name,
                        "uid": job_record["metadata"]["uid"],
                        "controller": True,
                        "blockOwnerDeletion": True,
                    }],
                },
                "spec": pod_spec,
                "status": {
                    "phase": "Succeeded",
                    "podIP": "10.0.0.14",
                    "containerStatuses": [{
                        "name": container["name"],
                        "image": container["image"],
                        "imageID": "containerd://" + state["materializer_image_id"],
                        "state": {"terminated": {"exitCode": 0}},
                    }],
                },
            }
            state["account_objects"]["pod/" + pod_name] = pod
            state.setdefault("account_owner_jobs", []).append({"stage": stage, "job": name, "pod": pod_name})
            state.setdefault("account_owner_job_specs", []).append(manifest)
            if stage == "initial-materializer":
                state["account_objects"]["secret/jwt-signing-keys"]["metadata"]["resourceVersion"] = "2"
            elif stage == "followup-materializer":
                state["account_objects"]["secret/jwt-signing-keys"]["metadata"]["resourceVersion"] = "3"
                state["account_objects"]["configmap/jwt-jwks"]["metadata"]["resourceVersion"] = "2"
            state.setdefault("account_owner_outcomes", {})[name] = (
                "readiness_batch_requested"
                if stage == "readiness-harness"
                else json.dumps(
                    {
                        "schemaVersion": 1,
                        "outcome": (
                            "secret_observation_recorded"
                            if stage == "initial-observation"
                            else "generation_recorded"
                            if stage == "initial-materializer"
                            else "private_promotion_receipt_recorded"
                        ),
                    },
                    separators=(",", ":"),
                )
            )
            print("job/" + name + " created")
        else:
            save()
            sys.exit(29)
    elif "wait" in args and (
        any(value.startswith("pod/redis-") or value == "pod/account-postgres" for value in args)
        or "pods" in args
        or any(value.startswith("job/") for value in args)
    ):
        print("pod condition met")
    elif "delete" in args and "namespace" in args:
        name = args[args.index("namespace") + 1]
        if not state.get("redis_namespace_exists") or name != state.get("redis_namespace"):
            save()
            sys.exit(30)
        state["redis_namespace_exists"] = False
        state["account_objects_at_namespace_delete"] = state.get("account_objects", {})
        state["redis_objects"] = {}
        state["account_objects"] = {}
        print("namespace deleted")
    elif "get" in args and "namespace" in args and "kube-system" not in args:
        name = args[args.index("namespace") + 1]
        if not state.get("redis_namespace_exists") or name != state.get("redis_namespace"):
            save()
            sys.exit(31)
        obj = state["redis_namespace_object"]
        if state.get("redis_namespace_annotation_drift"):
            obj["metadata"]["annotations"] = {"different": "owner"}
        if "-o" in args and args[args.index("-o") + 1] == "name":
            print("namespace/" + name)
        else:
            emit(obj)
    elif "exec" in args:
        state.setdefault("exec_calls", []).append(args)
        command = args[-1]
        if "psql" in args:
            assert args[args.index("exec") - 2 : args.index("exec")] == [
                "-n", state["redis_namespace"]
            ], args
            assert args[args.index("exec") + 1 : args.index("exec") + 3] == [
                "pod/account-postgres", "--"
            ], args
            assert args[args.index("--") + 1 : args.index("--") + 8] == [
                "psql", "--no-psqlrc", "--set=ON_ERROR_STOP=1", "--tuples-only",
                "--no-align", "--username=account_app", "--dbname=firemud"
            ], args
            assert "--command" in args and "SELECT jsonb_build_object" in command, args
            assert "account_jwt_signer_secret_observations" in command
            assert "account_jwt_signer_generation_operations" in command
            assert "account_jwt_readiness_pod_receipts" in command
            assert "public_jwk_json" not in command and "canonical_snapshot" in command
            jobs = state.get("account_owner_jobs", [])
            state.setdefault("account_owner_database_reads", []).append({
                "jobCount": len(jobs),
                "singleStatement": command.lstrip().startswith("SELECT jsonb_build_object")
                and command.count(";") == 1,
                "readsSecretContents": "data->" in command or "secret_data" in command,
            })
            api_path = account_input_run_directory() / "account-inputs/account-jwt-api/binding.json"
            api_binding = json.loads(api_path.read_text())
            materializer_path = account_input_run_directory() / "account-inputs/account-jwt-materializer/binding.json"
            materializer_binding = dict(
                line.split("=", 1) for line in materializer_path.read_text().splitlines()
            )
            inventory = state["validator_inventory"]
            signing_secret = state["account_objects"]["secret/jwt-signing-keys"]
            jwks = state["account_objects"]["configmap/jwt-jwks"]
            secret_uid = signing_secret["metadata"]["uid"]
            secret_rv = signing_secret["metadata"]["resourceVersion"]
            jwks_uid = jwks["metadata"]["uid"]
            jwks_rv = jwks["metadata"]["resourceVersion"]
            cluster_uid = "12345678-1234-4234-9234-123456789abc"
            namespace_uid = state["redis_namespace_object"]["metadata"]["uid"]
            materializer_digest = materializer_binding["bindingDigest"]
            materializer_revision = materializer_binding["configRevision"]
            operation_id = "11111111-1111-4111-8111-111111111111"
            operation_digest = "a" * 64
            observation_digest = "b" * 64
            generation_request_digest = "c" * 64
            generation_receipt_digest = "d" * 64
            promotion_operation_id = "22222222-2222-4222-8222-222222222222"
            private_promotion_receipt_digest = "e" * 64
            active_jwks_receipt_digest = "f" * 64
            readiness_evidence_digest = "1" * 64
            readiness_plan_digest = "2" * 64
            inventory_snapshot_digest = "3" * 64
            owner_state = {
                "environmentId": "develop",
                "clusterId": state["cluster"],
                "namespace": state["redis_namespace"],
                "custodyMode": "INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK",
                "privateSecretName": "jwt-signing-keys",
                "publicJwksConfigMapName": "jwt-jwks",
                "recordVersion": 4 if len(jobs) >= 4 else 3 if len(jobs) >= 2 else 2,
                "durableActiveGeneration": 1 if len(jobs) >= 4 else None,
                "durableActiveKid": "bootstrap_1" if len(jobs) >= 4 else None,
                "publishedActiveGeneration": 1 if len(jobs) >= 4 else None,
                "publishedActiveKid": "bootstrap_1" if len(jobs) >= 4 else None,
                "generationOperationId": None if len(jobs) >= 4 else operation_id,
                "preparedOperationId": None,
                "enrollmentClusterUid": cluster_uid,
                "enrollmentNamespaceUid": namespace_uid,
                "enrollmentMaterializerBindingDigest": materializer_digest,
                "enrollmentMaterializerConfigRevision": materializer_revision,
                "enrollmentApiBindingDigest": api_binding["bindingDigest"],
                "enrollmentApiConfigRevision": api_binding["configRevision"],
                "enrollmentPublicConfigMapUid": jwks_uid,
                "enrollmentPublicConfigMapResourceVersion": "1",
                "enrollmentPublicConfigMapSnapshotDigest": "5" * 64,
            }
            if state.get("account_owner_db_active_state") and len(jobs) == 1:
                owner_state["durableActiveGeneration"] = 7
                owner_state["durableActiveKid"] = "existing"
                owner_state["publishedActiveGeneration"] = 7
                owner_state["publishedActiveKid"] = "existing"
            generation_operation = {
                "operationId": operation_id,
                "operationDigest": operation_digest,
                "environmentId": "develop",
                "clusterId": state["cluster"],
                "namespace": state["redis_namespace"],
                "custodyMode": "INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK",
                "expectedRecordVersion": 1,
                "expectedPreviousGeneration": None,
                "expectedPreviousKid": None,
                "expectedPublishedGeneration": None,
                "expectedPublishedKid": None,
                "targetGeneration": 1,
                "targetKid": "bootstrap_1",
                "targetAlgorithm": "RS256",
                "expectedClusterUid": cluster_uid,
                "expectedNamespaceUid": namespace_uid,
                "trustBindingDigest": materializer_digest,
                "trustConfigRevision": materializer_revision,
                "operationAction": "MATERIALIZE_PENDING",
            }
            observation = {
                "operationId": operation_id,
                "operationDigest": operation_digest,
                "environmentId": "develop",
                "clusterId": state["cluster"],
                "namespace": state["redis_namespace"],
                "custodyMode": "INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK",
                "expectedRecordVersion": 1,
                "expectedClusterUid": cluster_uid,
                "expectedNamespaceUid": namespace_uid,
                "trustBindingDigest": materializer_digest,
                "trustConfigRevision": materializer_revision,
                "privateSecretName": "jwt-signing-keys",
                "secretUid": "99999999-9999-4999-8999-999999999999" if state.get("account_owner_db_observation_uid_mismatch") else secret_uid,
                "observedResourceVersion": "1",
                "observationDigest": observation_digest,
                "generationRequestDigest": generation_request_digest,
            }
            generated = len(jobs) >= 2
            committed = len(jobs) >= 4
            result_row = None if not generated else {
                "operationId": operation_id,
                "operationDigest": operation_digest,
                "generationRequestDigest": generation_request_digest,
                "desiredStateVersion": 3,
                "expectedClusterUid": cluster_uid,
                "expectedNamespaceUid": namespace_uid,
                "trustBindingDigest": materializer_digest,
                "trustConfigRevision": materializer_revision,
                "privateSecretName": "jwt-signing-keys",
                "secretUid": secret_uid,
                "expectedPriorResourceVersion": "1",
                "observedResourceVersion": "2",
                "targetGeneration": 1,
                "targetKid": "bootstrap_1",
                "targetAlgorithm": "RS256",
                "publicKeyFingerprint": "6" * 64,
                "receiptDigest": generation_receipt_digest,
            }
            promotion = None if not committed else {
                "operationId": promotion_operation_id,
                "generationOperationId": operation_id,
                "expectedRecordVersion": 2,
                "status": "PREPARED" if state.get("account_owner_db_commit_incomplete") else "COMMITTED",
                "operationAction": "PROMOTE_PENDING",
                "targetGeneration": 1,
                "targetKid": "bootstrap_1",
                "targetPublicKeyFingerprint": "6" * 64,
                "expectedPrivateSecretUid": secret_uid,
                "privatePromotionObservedResourceVersion": secret_rv,
                "privatePromotionReceiptDigest": private_promotion_receipt_digest,
                "expectedPublicConfigMapUid": jwks_uid,
                "activeJwksObservedResourceVersion": jwks_rv,
                "activeJwksReceiptDigest": active_jwks_receipt_digest,
                "readinessPlanDigest": readiness_plan_digest,
                "readinessEvidenceDigest": readiness_evidence_digest,
            }
            readiness_plan = None if not committed else {
                "rotationOperationId": operation_id,
                "planDigest": readiness_plan_digest,
                "planVersion": 2,
                "inventorySnapshotDigest": inventory_snapshot_digest,
                "validatorInventoryComplete": True,
                "applicabilityMatrixDigest": "7" * 64,
            }
            inventory_snapshot = None if not committed else {
                "snapshotDigest": inventory_snapshot_digest,
                "environmentId": "develop",
                "clusterId": state["cluster"],
                "clusterUid": cluster_uid,
                "namespace": state["redis_namespace"],
                "namespaceUid": namespace_uid,
                "apiBindingRevision": api_binding["configRevision"],
                "apiBindingDigest": api_binding["bindingDigest"],
                "inventoryBindingRevision": inventory["configRevision"],
                "inventoryBindingDigest": inventory["bindingDigest"],
                "canonicalSnapshotBytes": 1024,
            }
            snapshot = {
                "schemaVersion": 1,
                "stateCount": 0 if state.get("account_owner_db_state_missing") else 1,
                "state": None if state.get("account_owner_db_state_missing") else owner_state,
                "generationOperationCount": 1,
                "generationOperation": generation_operation,
                "secretObservationCount": 1,
                "secretObservation": observation,
                "generationResultCount": 1 if generated else 0,
                "generationResult": result_row,
                "promotionOperationCount": 1 if committed else 0,
                "promotionOperation": promotion,
                "readinessPlanCount": 1 if committed else 0,
                "readinessPlan": readiness_plan,
                "inventorySnapshotCount": 1 if committed else 0,
                "inventorySnapshot": inventory_snapshot,
                "readinessCounts": {
                    "entryCount": 2 if committed else 0,
                    "verifiedEntryCount": 2 if committed else 0,
                    "badEntryCount": 0,
                    "validatorPodCount": 2 if committed else 0,
                    "podReceiptCount": 2 if committed else 0,
                    "badPodReceiptCount": 0,
                },
            }
            if state.get("account_owner_db_inventory_mismatch") and committed:
                snapshot["inventorySnapshot"]["inventoryBindingDigest"] = "8" * 64
            state.setdefault("account_owner_database_snapshots", []).append(snapshot)
            emit(snapshot)
        elif "projection=versioned-symlink" in command:
            state["api_token_projection_probe"] = {
                "fixedPath": "root=/var/run/secrets/firemud/account-jwt-api-token" in command and 'token="$root/token"' in command,
                "checksVersionedSymlink": "readlink \"$root/..data\"" in command,
                "checksRegularFileMetadata": "0:65532:440|regular file" in command,
                "readsTokenContents": "cat \"$token\"" in command or "sha256sum \"$token\"" in command,
            }
            if state.get("account_api_token_metadata_mismatch"):
                print("projection=versioned-symlink;link=root-owned;target=unexpected")
            else:
                print("projection=versioned-symlink;link=root-owned;target=root-owned-group-65532-mode-0440-regular-file")
        elif "REDISCMDS" in command:
            state["account_probe_without_client_certificate"] = (
                "--tls" in command
                and "--cacert /run/redis-tls/ca.crt" in command
                and "--user account_coord_app" in command
                and 'REDISCLI_AUTH="$(cat /run/redis-primary-auth/account-password)"' in command
                and "--cert" not in command
                and "--key" not in command
            )
            script_sha = hashlib.sha1(b"return redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])").hexdigest()
            eval_line = next(line for line in command.splitlines() if line.startswith("EVALSHA "))
            fixture_value = eval_line.split()[4]
            role = ["master", 17, [["10.0.0.2", 6379, "online", 17]]]
            if state.get("force_wrong_account_role"):
                role[0] = "replica"
            wait_aof = [1, 0] if state.get("force_low_waitaof") else [1, 1]
            aof_status = "err" if state.get("force_aof_unhealthy") else "ok"
            replies = [
                "account_coord_app",
                role,
                "# Server\nredis_mode:standalone\n",
                "# Cluster\ncluster_enabled:0\n",
                "# Persistence\nloading:0\naof_enabled:1\naof_current_size:128\naof_last_write_status:" + aof_status + "\naof_last_bgrewrite_status:ok\n",
                "# Replication\nrole:master\nmaster_failover_state:no-failover\nconnected_slaves:" + ("0" if state.get("force_no_replica") else "1") + "\n",
                script_sha,
                "OK",
                wait_aof,
                fixture_value,
            ]
            for reply in replies:
                print(json.dumps(reply, separators=(",", ":")))
        elif "/run/redis-wrong-ca/ca.crt" in command:
            state["wrong_ca_probe_without_client_certificate"] = (
                "--tls" in command
                and "--user account_coord_app" in command
                and 'REDISCLI_AUTH="$(cat /run/redis-primary-auth/account-password)"' in command
                and "--cert" not in command
                and "--key" not in command
            )
            save()
            sys.exit(0 if state.get("force_wrong_ca_accept") else 1)
        elif "redis-cli" in command and "--tls" not in command:
            save()
            sys.exit(0 if state.get("force_plaintext_accept") else 1)
        elif "CONFIG GET" in command:
            key = re.search(r"CONFIG GET ([^\s;]+)", command).group(1)
            values = {
                "appendonly": "yes",
                "appendfsync": "everysec",
                "save": "",
                "port": "0",
                "tls-port": "6379",
                "tls-auth-clients": "no",
                "tls-replication": "yes",
                "min-replicas-to-write": "1",
                "min-replicas-max-lag": "1",
            }
            if state.get("force_bad_config") and key == "tls-replication":
                values[key] = "no"
            print(json.dumps([key, values[key]]))
        elif "INFO replication" in command and "redis-replica." in command:
            link = "down" if state.get("force_replica_link_down") else "up"
            print(json.dumps("# Replication\nrole:slave\nmaster_link_status:" + link + "\nmaster_sync_in_progress:0\n"))
        elif "INFO replication" in command:
            count = "0" if state.get("force_no_replica") else "1"
            print(json.dumps("# Replication\nrole:master\nmaster_failover_state:no-failover\nconnected_slaves:" + count + "\n"))
        elif "INFO persistence" in command:
            aof_status = "err" if state.get("force_aof_unhealthy") else "ok"
            print(json.dumps("# Persistence\nloading:0\naof_enabled:1\naof_current_size:128\naof_last_write_status:" + aof_status + "\naof_last_bgrewrite_status:ok\n"))
        elif "ROLE" in command and "redis-replica." in command:
            print(json.dumps(["slave", "10.0.0.1", 6379, "connected", 17]))
        elif "ROLE" in command:
            print(json.dumps(["master", 17, [["10.0.0.2", 6379, "online", 17]]]))
        else:
            save()
            sys.exit(32)
    elif "logs" in args:
        pod_name = args[args.index("logs") + 1]
        job = next(
            (item for item in state.get("account_owner_jobs", []) if item["pod"] == pod_name),
            None,
        )
        if job is None:
            save()
            sys.exit(35)
        state.setdefault("account_owner_output_reads", []).append(job["stage"])
        print(state["account_owner_outcomes"][job["job"]])
    elif "get" in args and "-n" in args and args[args.index("-n") + 1] == "kube-system" and args[args.index("get") + 1] == "pod":
        node_name = state.get("node_name", "fixture-control-plane")
        if args[args.index("get") + 2] != "kube-apiserver-" + node_name:
            save()
            sys.exit(34)
        audience = "https://kubernetes.default.svc.cluster.local"
        if state.get("account_api_server_audience_invalid"):
            audience = "http://invalid.example.invalid"
        if state.get("account_api_server_audience_drift") and state.get("account_objects", {}).get("deployment/account-service"):
            audience = "https://unexpected.example.invalid"
        emit({
            "apiVersion": "v1",
            "kind": "Pod",
            "metadata": {
                "name": "kube-apiserver-" + node_name,
                "namespace": "kube-system",
                "uid": str(uuid.uuid5(uuid.NAMESPACE_DNS, state.get("cluster", "none") + ":kube-apiserver")),
            },
            "spec": {
                "nodeName": node_name,
                "containers": [{
                    "name": "kube-apiserver",
                    "image": "registry.k8s.io/kube-apiserver:v1.35.8",
                    "args": [
                        "--service-account-issuer=https://kubernetes.default.svc.cluster.local",
                        *( ["--api-audiences"] if state.get("account_api_server_audience_flag_missing_value") else ["--api-audiences=" + audience] ),
                    ],
                }],
            },
            "status": {
                "phase": "Running",
                "containerStatuses": [{
                    "name": "kube-apiserver",
                    "ready": True,
                    "image": "registry.k8s.io/kube-apiserver:v1.35.8",
                    "imageID": "containerd://sha256:" + "a" * 64,
                }],
            },
        })
    elif "get" in args and "pods" in args and "-l" in args and "-o" in args and args[args.index("-o") + 1] == "json":
        namespace = args[args.index("-n") + 1]
        selector = args[args.index("-l") + 1]
        selector_values = dict(part.split("=", 1) for part in selector.split(","))
        pods = [
            value
            for key, value in state.get("account_objects", {}).items()
            if key.startswith("pod/")
            and value["metadata"].get("namespace") == namespace
            and all(value["metadata"].get("labels", {}).get(key) == item for key, item in selector_values.items())
        ]
        if selector_values.get("firemud.org/account-jwt-bootstrap-validator") == "account-service":
            state["account_pod_lists"] = state.get("account_pod_lists", 0) + 1
            if state.get("account_pod_uid_drift_on_teardown") and state["account_pod_lists"] > 4 and pods:
                pods[0]["metadata"]["uid"] = str(uuid.uuid5(uuid.NAMESPACE_DNS, "changed-account-pod-uid"))
            if state.get("validator_inventory_resource_drift") and state["account_pod_lists"] > 2 and pods:
                pods[0]["status"]["podIP"] = "10.0.0.99"
        emit({"apiVersion": "v1", "kind": "PodList", "items": pods})
    elif "get" in args and "-n" in args and args[args.index("-n") + 1] == state.get("redis_namespace") and args[args.index("get") + 1].lower() in {"deployment", "replicaset"}:
        namespace = args[args.index("-n") + 1]
        kind = args[args.index("get") + 1].lower()
        name = args[args.index("get") + 2]
        record = state.get("account_objects", {}).get(kind + "/" + name)
        if record is None or record["metadata"].get("namespace") != namespace:
            save()
            sys.exit(34)
        if kind == "replicaset":
            state["account_replica_set_reads"] = state.get("account_replica_set_reads", 0) + 1
            if state.get("validator_inventory_resource_drift") and state["account_replica_set_reads"] > 2:
                record["spec"]["selector"]["matchLabels"]["drift"] = "true"
        emit(record)
    elif "get" in args and "pods" in args and "-o" in args and args[args.index("-o") + 1] == "json":
        pods = [
            value
            for objects in (state.get("redis_objects", {}), state.get("account_objects", {}))
            for key, value in objects.items()
            if key.startswith("pod/")
        ]
        emit({"apiVersion": "v1", "kind": "PodList", "items": pods})
    elif "get" in args and "-n" in args and "pods,services,secrets,configmaps" in args:
        namespace = args[args.index("-n") + 1]
        if namespace != state.get("redis_namespace"):
            save()
            sys.exit(33)
        for name in sorted(state.get("redis_objects", {})):
            print(name)
    elif "get" in args and "-n" in args:
        namespace = args[args.index("-n") + 1]
        if namespace != state.get("redis_namespace"):
            save()
            sys.exit(33)
        kind = args[args.index("get") + 1].lower()
        name = args[args.index("get") + 2]
        record = state.get("redis_objects", {}).get(kind + "/" + name)
        if record is None:
            record = state.get("account_objects", {}).get(kind + "/" + name)
        if record is None:
            save()
            sys.exit(34)
        if kind == "pod" and name == "account-service":
            state["account_pod_reads"] = state.get("account_pod_reads", 0) + 1
            if state.get("account_pod_uid_drift_on_teardown") and state["account_pod_reads"] > 1:
                record["metadata"]["uid"] = str(uuid.uuid5(uuid.NAMESPACE_DNS, "changed-account-pod-uid"))
        if state.get("account_api_rbac_spec_mismatch") and kind in {"role", "clusterrole", "rolebinding", "clusterrolebinding"}:
            if kind in {"role", "clusterrole"}:
                record["rules"] = []
            else:
                record["subjects"] = []
        if kind == "secret" and "jsonpath=" in " ".join(args):
            if name == "account-postgres-credentials":
                metadata = record["metadata"]
                if ".metadata.name" in " ".join(args):
                    annotations = metadata["annotations"]
                    labels = metadata["labels"]
                    fields = [
                        metadata["name"],
                        metadata["namespace"],
                        metadata["uid"],
                        labels["firemud.org/account-jwt-bootstrap"],
                        labels["firemud.org/fixture-id"],
                        annotations["firemud.org/account-jwt-bootstrap-repository"],
                        annotations["firemud.org/account-jwt-bootstrap-ref"],
                        annotations["firemud.org/account-jwt-bootstrap-commit"],
                        annotations["firemud.org/account-jwt-bootstrap-run-id"],
                        annotations["firemud.org/account-jwt-bootstrap-run-attempt"],
                        annotations["firemud.org/account-jwt-bootstrap-cluster"],
                        annotations["firemud.org/account-jwt-bootstrap-config-sha256"],
                    ]
                    print("::".join(fields))
                else:
                    print("true::Opaque")
            else:
                query = next(value for value in args if value.startswith("jsonpath="))
                expressions = re.findall(r"\{([^{}]+)\}", query.removeprefix("jsonpath="))
                values = []
                for expression in expressions:
                    if expression == ".type":
                        values.append(record.get("type", ""))
                    elif expression.startswith(".metadata."):
                        remainder = expression.removeprefix(".metadata.")
                        if remainder.startswith("labels."):
                            key = remainder.removeprefix("labels.").replace("\\.", ".")
                            values.append(record["metadata"].get("labels", {}).get(key, ""))
                        elif remainder.startswith("annotations."):
                            key = remainder.removeprefix("annotations.").replace("\\.", ".")
                            values.append(record["metadata"].get("annotations", {}).get(key, ""))
                        else:
                            values.append(record["metadata"].get(remainder, ""))
                    else:
                        values.append("")
                state.setdefault("metadata_only_reads", []).append({"kind": kind, "name": name, "fields": expressions})
                print("::".join(values))
        elif kind == "configmap" and "jsonpath=" in " ".join(args):
            query = next(value for value in args if value.startswith("jsonpath="))
            expressions = re.findall(r"\{([^{}]+)\}", query.removeprefix("jsonpath="))
            values = []
            for expression in expressions:
                if expression.startswith(".metadata."):
                    remainder = expression.removeprefix(".metadata.")
                    if remainder.startswith("labels."):
                        key = remainder.removeprefix("labels.").replace("\\.", ".")
                        values.append(record["metadata"].get("labels", {}).get(key, ""))
                    elif remainder.startswith("annotations."):
                        key = remainder.removeprefix("annotations.").replace("\\.", ".")
                        values.append(record["metadata"].get("annotations", {}).get(key, ""))
                    else:
                        values.append(record["metadata"].get(remainder, ""))
                else:
                    values.append("")
            state.setdefault("metadata_only_reads", []).append({"kind": kind, "name": name, "fields": expressions})
            print("::".join(values))
        else:
            emit(record)
    elif "get" in args and "-n" not in args:
        kind = args[args.index("get") + 1].lower()
        name = args[args.index("get") + 2]
        record = state.get("account_objects", {}).get(kind + "/" + name)
        if record is None:
            save()
            sys.exit(34)
        if state.get("account_api_rbac_spec_mismatch") and kind in {"clusterrole", "clusterrolebinding"}:
            if kind == "clusterrole":
                record["rules"] = []
            else:
                record["subjects"] = []
        emit(record)
    else:
        save()
        sys.exit(22)
elif tool == "docker":
    if args == ["context", "show"]:
        print("default")
    elif args[:2] == ["context", "inspect"]:
        print("unix:///var/run/docker.sock")
    elif args[:2] == ["ps", "--all"]:
        if state.get("created"):
            state["container_reads"] = state.get("container_reads", 0) + 1
            node_id = state["node_id"]
            if state.get("container_drift") and state["container_reads"] > 1:
                node_id = "f" * 64
            print(node_id)
    elif args[:3] == ["network", "ls", "--no-trunc"]:
        filter_value = args[-1] if args[-2:-1] == ["--filter"] else ""
        if filter_value.startswith("id="):
            if state.get("network_exists") and filter_value[3:] == state.get("network_id"):
                print(state["network_id"])
        elif state.get("network_exists") and state.get("network_name") == "kind":
            print(state["network_id"])
        if not filter_value.startswith("id=") and state.get("additional_network_id"):
            print(state["additional_network_id"])
    elif args[:2] == ["network", "inspect"]:
        if not state.get("network_exists") or args[-1] not in ("kind", state.get("network_id")):
            save()
            sys.exit(23)
        containers = {state["node_id"]: {"Name": state["node_name"]}} if state.get("created") else {}
        emit({"Id": state["network_id"], "Name": state.get("network_name", "kind"), "Containers": containers})
    elif args[:2] == ["network", "rm"]:
        if not state.get("network_exists") or args[2] != state.get("network_id"):
            save()
            sys.exit(28)
        state.setdefault("network_rm_ids", []).append(args[2])
        state["network_exists"] = False
    elif args[:2] == ["buildx", "build"]:
        image = args[args.index("--tag") + 1]
        state.setdefault("built_images", {})[image] = (
            "sha256:" + "f" * 64
            if image.startswith("account-jwt-materializer:")
            else "sha256:" + "c" * 64
        )
        if image.startswith("account-jwt-materializer:"):
            state["built_materializer_image"] = image
            state["materializer_image_id"] = state["built_images"][image]
        else:
            state["built_account_image"] = image
            state["account_image_id"] = state["built_images"][image]
    elif args[:3] == ["image", "save", "--output"]:
        archive_path = Path(args[3])
        if args[4] not in state.get("built_images", {}):
            save()
            sys.exit(44)
        archive_path.write_bytes(("mock-only-archive-of-exact-source-image:" + args[4] + "\n").encode())
        archive_path.chmod(0o600)
    elif args[:2] == ["image", "inspect"]:
        if args[-1] not in state.get("built_images", {}):
            save()
            sys.exit(35)
        print(state["built_images"][args[-1]] + "|" + state["head"])
    elif args[:1] == ["cp"]:
        source = Path(args[1])
        node_and_path = args[2]
        node_id, remote_path = node_and_path.split(":", 1)
        if node_id != state.get("node_id"):
            save()
            sys.exit(37)
        state.setdefault("node_files", {})[remote_path] = {
            "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "owner": 1000,
            "group": 1000,
            "mode": "600",
            "kind": "regular file",
            "account_tls_leaf": source.read_bytes().startswith(
                b"-----BEGIN CERTIFICATE-----\nYWNjb3VudC1zZXJ2ZXItbGVhZg==\n"
            ),
        }
        if remote_path.startswith("/var/lib/account-jwt-bootstrap-") and "/validator-inventory.json.next-" not in remote_path:
            state.setdefault("mount_copy_paths", []).append(remote_path)
        if source.name == "validator-inventory.json":
            state["validator_inventory"] = json.loads(source.read_text())
    elif args[:1] == ["exec"] and args[1:3] == ["--user", "0"]:
        node_id = args[3]
        command = args[4:]
        if node_id != state.get("node_id") or not command:
            save()
            sys.exit(38)
        if command[0] == "mkdir" and command[1] == "--mode=0700":
            state.setdefault("node_directories", {})[command[2]] = {
                "owner": 0, "group": 0, "mode": "700", "kind": "directory"
            }
        elif command[0] == "test" and command[1:3] == ["!", "-L"]:
            target = command[3]
            bad = state.get("account_owner_mount_symlink_mismatch") and (
                target in state.get("node_directories", {})
                or target in state.get("node_files", {})
            )
            save()
            sys.exit(1 if bad else 0)
        elif command[0] == "mkdir" and command[1] == "--mode=0750":
            state.setdefault("node_directories", {})[command[2]] = {
                "owner": 0, "group": 0, "mode": "750", "kind": "directory"
            }
        elif command[0] == "ctr" and command[1:4] == ["--namespace", "k8s.io", "images"]:
            if command[4] == "import":
                repository = command[command.index("--base-name") + 1]
                manifest = {
                    "schemaVersion": 2,
                    "mediaType": "application/vnd.oci.image.manifest.v1+json",
                    "config": {
                        "mediaType": "application/vnd.oci.image.config.v1+json",
                        "digest": (
                            "sha256:" + "d" * 64
                            if state.get("account_image_manifest_config_mismatch")
                            else state["materializer_image_id"]
                            if repository == "account-jwt-materializer"
                            else state["account_image_id"]
                        ),
                        "size": 128,
                    },
                    "layers": [{"mediaType": "application/vnd.oci.image.layer.v1.tar", "digest": "sha256:" + "e" * 64, "size": 256}],
                }
                manifest_bytes = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
                manifest_digest = hashlib.sha256(manifest_bytes).hexdigest()
                reference = repository + "@sha256:" + manifest_digest
                state.setdefault("image_manifests", {})[manifest_digest] = base64.b64encode(manifest_bytes).decode()
                state.setdefault("loaded_images", {})[repository] = {
                    "reference": reference,
                    "configDigest": manifest["config"]["digest"],
                    "manifestDigest": manifest_digest,
                }
                if repository == "account-jwt-materializer":
                    state["materializer_image_manifest_digest"] = manifest_digest
                    state["loaded_materializer_image"] = reference
                else:
                    state["account_image_manifest_digest"] = manifest_digest
                    state["account_image_manifest_bytes"] = base64.b64encode(manifest_bytes).decode()
                    state["loaded_account_image"] = reference
            elif command[4:6] == ["list", "--quiet"]:
                for value in state.get("loaded_images", {}).values():
                    print(value["reference"])
            else:
                save()
                sys.exit(45)
        elif command[:3] == ["ctr", "--namespace", "k8s.io"] and command[3:5] == ["content", "get"]:
            digest = command[5].removeprefix("sha256:")
            if digest not in state.get("image_manifests", {}):
                save()
                sys.exit(46)
            sys.stdout.buffer.write(base64.b64decode(state["image_manifests"][digest]))
        elif command[0] == "rm" and command[1] == "--":
            state.get("node_files", {}).pop(command[2], None)
        elif command[0] == "chown":
            record = state.get("node_files", {}).get(command[2]) or state.get("node_directories", {}).get(command[2])
            if record is None or command[1] != "0:65532":
                save()
                sys.exit(39)
            record["owner"] = 0
            record["group"] = 65532
        elif command[0] == "chmod":
            record = state.get("node_files", {}).get(command[2]) or state.get("node_directories", {}).get(command[2])
            if record is None or command[1] not in {"0440", "0550", "0750"}:
                save()
                sys.exit(40)
            record["mode"] = command[1].removeprefix("0")
        elif command[0] == "stat" and command[1:3] == ["-c", "%u:%g:%a|%F"]:
            target = command[3]
            record = state.get("node_files", {}).get(target) or state.get("node_directories", {}).get(target)
            if record is None:
                save()
                sys.exit(41)
            if state.get("account_mount_metadata_mismatch") and target in state.get("mount_copy_paths", []):
                observed = "1000:1000:600|regular file"
            else:
                observed = f"{record['owner']}:{record['group']}:{record['mode']}|{record['kind']}"
            state.setdefault("account_mount_stat_readbacks", []).append({"path": target, "metadata": observed})
            print(observed)
        elif command[:2] == ["sh", "-ec"]:
            script = command[2]
            if script.startswith("test ! -e"):
                # Publication checks are run only for the initial inventory and its unique next file.
                targets = re.findall(r"test ! -[eL] ([^\s&]+)", script)
                present = any(
                    target in state.get("node_files", {})
                    or target in state.get("node_directories", {})
                    for target in targets
                )
                save()
                sys.exit(0 if not present else 1)
            if "stat -c" in script:
                target = (
                    command[-1]
                    if '"$1"' in script and len(command) > 3
                    else re.findall(r"stat -c [^ ]+ ([^\s&]+)", script)[-1]
                )
                record = state.get("node_files", {}).get(target) or state.get("node_directories", {}).get(target)
                if record is None:
                    save()
                    sys.exit(41)
                observed = f"{record['owner']}:{record['group']}:{record['mode']}|{record['kind']}"
                state.setdefault("account_mount_stat_readbacks", []).append({"path": target, "metadata": observed})
                print(observed)
            else:
                save()
                sys.exit(47)
        elif command[0] == "mv" and command[1:2] == ["--"]:
            source_path, destination_path = command[2], command[3]
            record = state.get("node_files", {}).pop(source_path, None)
            if record is None or destination_path in state.get("node_files", {}):
                save()
                sys.exit(48)
            state.setdefault("node_files", {})[destination_path] = record
            if source_path.endswith("/validator-inventory.json.next-" + state.get("account_pod_uid", "")):
                state.setdefault("mount_copy_paths", []).append(destination_path)
        elif command[0] == "sha256sum":
            target = command[1]
            record = state.get("node_files", {}).get(target)
            if record is None:
                save()
                sys.exit(42)
            digest = (
                "0" * 64
                if state.get("account_tls_leaf_digest_mismatch") and record.get("account_tls_leaf")
                else record["sha256"]
            )
            print(digest + "  " + target)
        else:
            save()
            sys.exit(43)
    elif args[:1] == ["exec"] and args[2:4] == ["crictl", "images"]:
        if args[1] != state.get("node_id"):
            save()
            sys.exit(36)
        emit({"images": [
            {"repoTags": [], "repoDigests": [item["reference"]], "id": item["configDigest"]}
            for item in state.get("loaded_images", {}).values()
        ]})
    elif args[:2] == ["inspect", "--format"]:
        if not state.get("created"):
            save()
            sys.exit(24)
        emit({
            "Id": state["node_id"],
            "Name": "/" + state["node_name"],
            "Config": {
                "Image": state["image"],
                "Labels": {
                    "io.x-k8s.kind.cluster": state["cluster"],
                    "io.x-k8s.kind.role": "control-plane",
                },
            },
            "State": {"Running": True},
        })
    else:
        save()
        sys.exit(25)
elif tool == "openssl":
    state.setdefault("openssl_calls", []).append(args)
    if args[:1] == ["req"]:
        private_path = Path(args[args.index("-keyout") + 1])
        private_path.write_bytes(b"mock-private-key-material\n")
        private_path.chmod(0o600)
        output_path = Path(args[args.index("-out") + 1])
        if "-x509" in args:
            certificate = b"-----BEGIN CERTIFICATE-----\nYWNjb3VudC1jYQ==\n-----END CERTIFICATE-----\n"
            output_path.write_bytes(certificate)
        else:
            output_path.write_bytes(b"mock-certificate-signing-request\n")
        output_path.chmod(0o600)
    elif args[:1] == ["x509"] and "-req" in args:
        extension = Path(args[args.index("-extfile") + 1]).read_text()
        label = (
            b"account-server-leaf"
            if "DNS:account-service." in extension
            else b"readiness-harness-client-leaf"
            if "account-jwt-readiness-harness" in extension
            else b"materializer-client-leaf"
        )
        certificate = (
            b"-----BEGIN CERTIFICATE-----\n"
            + base64.b64encode(label)
            + b"\n-----END CERTIFICATE-----\n"
        )
        output_path = Path(args[args.index("-out") + 1])
        output_path.write_bytes(certificate)
        output_path.chmod(0o600)
    elif args[:1] == ["x509"] and "-pubkey" in args:
        path = Path(args[args.index("-out") + 1])
        path.write_bytes(b"-----BEGIN PUBLIC KEY-----\nbW9jay1wdWJsaWM=\n-----END PUBLIC KEY-----\n")
        path.chmod(0o600)
    elif args[:1] == ["x509"] and "-ext" in args:
        namespace = fixture_namespace()
        certificate_path = Path(args[args.index("-in") + 1])
        certificate_bytes = certificate_path.read_bytes()
        label = base64.b64decode(
            re.search(rb"-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=]+)", certificate_bytes).group(1)
        )
        if b"materializer-client-leaf" in label:
            uri = "spiffe://firemud/ns/" + namespace + "/sa/account-jwt-materializer"
            if state.get("account_materializer_tls_identity_mismatch"):
                uri = "spiffe://firemud/ns/" + namespace + "/sa/unexpected"
            print("X509v3 Subject Alternative Name: URI:" + uri)
        elif b"readiness-harness-client-leaf" in label:
            print("X509v3 Subject Alternative Name: URI:spiffe://firemud/ns/" + namespace + "/sa/account-jwt-readiness-harness")
        elif state.get("account_tls_name_binding_mismatch"):
            print("X509v3 Subject Alternative Name: DNS:wrong-service.invalid")
        else:
            print(
                "X509v3 Subject Alternative Name: "
                + "DNS:account-service." + namespace + ".svc.cluster.local, "
                + "URI:spiffe://firemud/ns/" + namespace + "/sa/account-service"
            )
    elif args[:1] == ["pkey"]:
        der = b"mock-only-SPKI-DER"
        if state.get("account_tls_keypair_mismatch") and "-pubin" not in args:
            der = b"mock-only-different-SPKI-DER"
        sys.stdout.buffer.write(der)
    else:
        if args[:1] != ["verify"] and not (args[:1] == ["x509"] and "-noout" in args):
            save()
            sys.exit(38)
    save()
else:
    save()
    sys.exit(26)

save()
'''


def command_shim(tool):
    return f'''#!/usr/bin/env python3
import os
import sys
from pathlib import Path

fake_tool = Path(__file__).resolve().with_name("fake-tool.py")
os.execv(sys.executable, [sys.executable, str(fake_tool), "--{tool}-shim", *sys.argv[1:]])
'''


def run_case(flags=None, run_id="9911", attempt="1", operation="lifecycle", old_context=False, tampered_kind=False):
    temporary = tempfile.TemporaryDirectory(prefix="account-jwt-fixture-contract-")
    base = Path(temporary.name)
    binary_dir = base / "bin"
    home = base / "home"
    runner_temp = base / "runner-temp"
    workspace = base / "workspace"
    binary_dir.mkdir()
    home.mkdir()
    runner_temp.mkdir()
    workspace.mkdir()
    (workspace / "config").mkdir()
    (workspace / "docker").mkdir()
    (workspace / "docker/docker-compose.yml").write_bytes((root / "docker/docker-compose.yml").read_bytes())
    (workspace / "services/account-service").mkdir(parents=True)
    (workspace / "services/account-service/Dockerfile").write_bytes((root / "services/account-service/Dockerfile").read_bytes())
    materializer_module = workspace / "k8s/account-jwt-materializer"
    materializer_module.mkdir(parents=True)
    for filename in ("Dockerfile", ".dockerignore"):
        (materializer_module / filename).write_bytes((root / "k8s/account-jwt-materializer" / filename).read_bytes())
    (workspace / "dev-tools/validation").mkdir(parents=True)
    gradle_runner = workspace / "dev-tools/validation/run-locked-gradle.sh"
    gradle_runner.write_text(
        "#!/usr/bin/env bash\n"
        "set -euo pipefail\n"
        "workspace=\"$(cd \"$(dirname \"$0\")/../..\" && pwd)\"\n"
        "mkdir -p \"$workspace/services/account-service/build/libs\"\n"
        ": > \"$workspace/services/account-service/build/libs/account-service.jar\"\n"
        "if [[ \" $* \" == *\":account-jwt-materializer:installDist\"* ]]; then\n"
        "  install=\"$workspace/k8s/account-jwt-materializer/build/install/account-jwt-materializer/bin\"\n"
        "  mkdir -p \"$install\"\n"
        "  printf '#!/usr/bin/env sh\\nexit 0\\n' > \"$install/account-jwt-materializer\"\n"
        "  printf '#!/usr/bin/env sh\\nexit 0\\n' > \"$install/account-jwt-readiness-harness\"\n"
        "  chmod 755 \"$install/account-jwt-materializer\" \"$install/account-jwt-readiness-harness\"\n"
        "fi\n"
    )
    gradle_runner.chmod(0o755)
    (workspace / "dev-tools/hosted/account-bootstrap").mkdir(parents=True)
    (workspace / "dev-tools/hosted/account-bootstrap/account-jwt-bootstrap-fixture.py").write_bytes(script.read_bytes())
    (workspace / "dev-tools/hosted/account-bootstrap/account-jwt-bootstrap-redis.py").write_bytes(redis_helper.read_bytes())
    (workspace / "dev-tools/hosted/account-bootstrap/account-jwt-bootstrap-account.py").write_bytes(account_helper.read_bytes())
    (workspace / "dev-tools/hosted/account-bootstrap/prove-account-jwt-bootstrap.sh").write_bytes(entrypoint.read_bytes())
    kind_path = binary_dir / "kind"
    for name in ("kind", "kubectl", "docker", "git", "openssl"):
        tool_path = binary_dir / name
        tool_path.write_text(command_shim(name))
        tool_path.chmod(0o755)
    kind_path = binary_dir / "kind"
    kind_digest = hashlib.sha256(kind_path.read_bytes()).hexdigest()
    authority_text = (root / "config/workflow-tool-versions.env").read_text()
    authority_text, replacements = re.subn(
        r"(?m)^KIND_LINUX_AMD64_SHA256=[0-9a-f]{64}$",
        f"KIND_LINUX_AMD64_SHA256={kind_digest}",
        authority_text,
    )
    assert replacements == 1
    (workspace / "config/workflow-tool-versions.env").write_text(authority_text)
    if tampered_kind:
        kind_path.write_text(kind_path.read_text() + "\n# altered bytes; same reported version\n")
    fake_path = binary_dir / "fake-tool.py"
    fake_path.write_text(fake_tool)
    fake_path.chmod(0o755)
    state_path = binary_dir / "state.json"
    state_path.write_text(json.dumps({
        "created": False,
        "head": head,
        "existing_context": old_context,
        "repository": "benhook1013/FireMUD",
        "run_id": run_id,
        "attempt": attempt,
        "runner_temp": str(runner_temp),
        **(flags or {}),
    }))
    if old_context:
        kube_directory = home / ".kube"
        kube_directory.mkdir()
        (kube_directory / "config").write_text("existing shared context\n")
    env = os.environ.copy()
    for name in ("KUBECONFIG", "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH"):
        env.pop(name, None)
    env.update(
        {
            "PATH": f"{binary_dir}:{env.get('PATH', '')}",
            "HOME": str(home),
            "RUNNER_TEMP": str(runner_temp),
            "GITHUB_WORKSPACE": str(workspace),
            "GITHUB_ACTIONS": "true",
            "RUNNER_ENVIRONMENT": "github-hosted",
            "RUNNER_OS": "Linux",
            "RUNNER_ARCH": "X64",
            "GITHUB_REPOSITORY": "benhook1013/FireMUD",
            "GITHUB_REF": "refs/heads/develop",
            "GITHUB_EVENT_NAME": "push",
            "GITHUB_SHA": head,
            "GITHUB_RUN_ID": run_id,
            "GITHUB_RUN_ATTEMPT": attempt,
            "GITHUB_JOB": "account-bootstrap-fixture-contract",
            "GITHUB_HEAD_REF": "",
        }
    )
    for name in ("kind", "kubectl", "docker", "git", "openssl"):
        resolved = shutil.which(name, path=env["PATH"])
        assert resolved is not None and Path(resolved).parent == binary_dir, (name, resolved)
    if operation == "lifecycle":
        argv = ["bash", str(workspace / "dev-tools/hosted/account-bootstrap/prove-account-jwt-bootstrap.sh")]
    else:
        argv = [
            "python3",
            str(workspace / "dev-tools/hosted/account-bootstrap/account-jwt-bootstrap-fixture.py"),
            "--authority",
            str(workspace / "config/workflow-tool-versions.env"),
            "--operation",
            operation,
        ]
    result = subprocess.run(argv, cwd=workspace, env=env, capture_output=True, text=True, check=False, timeout=90)
    try:
        payload = json.loads(result.stdout.strip())
    except json.JSONDecodeError as error:
        temporary.cleanup()
        raise AssertionError(
            "fixture returned non-JSON output: "
            f"exit={result.returncode}, stdout={result.stdout!r}, stderr={result.stderr!r}"
        ) from error
    state = json.loads(state_path.read_text())
    return temporary, runner_temp, env, result, payload, state


def assert_no_delete(state):
    assert not any(call[0] == "kind" and call[1:3] == ["delete", "cluster"] for call in state["calls"]), state["calls"]


temporary, runner_temp, _, result, payload, state = run_case()
owner_snapshots = state.get("account_owner_database_snapshots", [])
owner_snapshot_summary = [
    {
        "state": item.get("state"),
        "operation": item.get("generationOperation"),
        "observation": item.get("secretObservation"),
        "counts": {
            name: item.get(name)
            for name in (
                "stateCount",
                "generationOperationCount",
                "secretObservationCount",
                "generationResultCount",
                "promotionOperationCount",
                "readinessPlanCount",
                "inventorySnapshotCount",
            )
        },
    }
    for item in owner_snapshots[-1:]
]
assert result.returncode == 2, (
    result.returncode,
    payload.get("outcome"),
    payload.get("reason"),
    state.get("calls", [])[-8:],
    state.get("mock_tool_stderr"),
    owner_snapshot_summary,
)
assert payload["outcome"] == "incomplete_proof_flow", payload
assert payload["fixture"]["state"] == "fixture_ready", payload
assert payload["cleanup"] == "removed", payload
assert payload["loginSuccess"] is False and payload["promotionCommitted"] is None, payload
assert payload["readinessGate"] == "closed", payload
assert payload["proof"] == "run_owned_account_deployment_receiver_and_packaged_owner_invocations_observed", payload
assert payload["nextBoundary"] == "durable_owner_operation_and_receiver_receipt_readback_are_not_exported; readiness_and_admission_remain_closed", payload
assert payload["accountPodCreated"] is True, payload
assert payload["accountRuntimeBoundary"] == "canonical_account_login_security_mode; owner_outcome_not_durable_result_proof", payload
account_proof = payload["fixture"]["accountFixture"]
assert account_proof["state"] == "account_source_pod_proof_complete", account_proof
assert account_proof["postgresImage"] == state["postgres_image"], (account_proof, state)
assert account_proof["postgresPodIp"] == "10.0.0.12", account_proof
assert account_proof["postgresPodImageId"].endswith(account_proof["postgresImage"].split("@sha256:", 1)[1]), account_proof
assert account_proof["accountImageSourceCommit"] == head, account_proof
assert account_proof["accountImageId"] == state["account_image_id"], account_proof
assert account_proof["accountImageManifestDigest"] == state["account_image_manifest_digest"], account_proof
assert account_proof["accountImageId"] != "sha256:" + account_proof["accountImageManifestDigest"], account_proof
assert account_proof["accountImage"] == "account-jwt-bootstrap-account@sha256:" + account_proof["accountImageManifestDigest"], account_proof
assert account_proof["accountImageLoadedOnNode"] == [
    {"nodeId": state["node_id"], "runtimeImage": state["loaded_account_image"], "configDigest": state["account_image_id"]}
], account_proof
assert account_proof["ownerMaterializerImage"] == state["loaded_materializer_image"], account_proof
assert account_proof["ownerMaterializerImageId"] == state["materializer_image_id"], account_proof
assert account_proof["ownerMaterializerManifestDigest"] == state["materializer_image_manifest_digest"], account_proof
assert account_proof["ownerBootstrapStages"]["initialObservation"]["outcome"] == "secret_observation_recorded", account_proof
assert account_proof["ownerBootstrapStages"]["initialMaterializer"]["outcome"] == "generation_recorded", account_proof
assert account_proof["ownerBootstrapStages"]["readinessHarness"]["outcome"] == "readiness_batch_requested", account_proof
assert account_proof["ownerBootstrapStages"]["followupMaterializer"]["outcome"] == "private_promotion_receipt_recorded", account_proof
assert account_proof["ownerBootstrapStages"]["proofBoundary"] == "account_owner_database_rows_correlated; source_only_not_runtime_proof", account_proof
assert account_proof["ownerProofBoundary"] == "account_owner_rows_correlated_in_private_receipt; source_only_not_runtime_proof", account_proof
assert "ownerMounts" not in account_proof, account_proof
assert "ownerStateReadbacks" not in account_proof, account_proof
public_owner_stage_fields = {
    "stage",
    "jobName",
    "jobUid",
    "podName",
    "podUid",
    "image",
    "imageId",
    "manifestDigest",
    "serviceAccount",
    "outcome",
    "ownerResourceUids",
    "secretResourceVersion",
    "jwksConfigMapResourceVersion",
}
for stage_name in ("initialObservation", "initialMaterializer", "readinessHarness", "followupMaterializer"):
    assert set(account_proof["ownerBootstrapStages"][stage_name]) == public_owner_stage_fields, account_proof["ownerBootstrapStages"][stage_name]
    assert not any(
        field in account_proof["ownerBootstrapStages"][stage_name]
        for field in ("files", "materializerDirectory", "readinessHarnessDirectory", "mountRoot")
    ), account_proof["ownerBootstrapStages"][stage_name]
assert state["account_inputs_observed"] == {
    "ownerMatchesRun": True,
    "ownerDigestsMatch": True,
    "privateTreeModes": True,
    "apiBindingDigestValid": True,
    "apiBindingMatchesRun": True,
    "materializerBindingDigestValid": True,
    "materializerBindingMatchesRun": True,
    "keyringFormatsValid": True,
    "materializerClientKeyPresent": True,
    "accountJwtSigningKeyAbsent": True,
}, state.get("account_inputs_observed")
assert "materializer-client.key" not in state["account_mount_contract_probe"]["mountPaths"], state["account_mount_contract_probe"]
assert all("account-jwt-materializer/client.key" not in path for path in state.get("node_files", {})), state.get("node_files")
input_run_directory = runner_temp / (
    "account-jwt-bootstrap-" + state["run_id"] + "-" + state["attempt"] + "-" + state["head"][:12]
)
assert not (input_run_directory / "account-inputs").exists(), input_run_directory
assert not (input_run_directory / "account-inputs-owner.json").exists(), input_run_directory
image_imports = [
    call
    for call in state["calls"]
    if call[0] == "docker" and "ctr" in call and "import" in call
]
assert len(image_imports) == 2, image_imports
for image_import in image_imports:
    ctr_index = image_import.index("ctr")
    assert image_import[ctr_index + 1 : ctr_index + 4] == ["--namespace", "k8s.io", "images"], image_import
    assert image_import[ctr_index + 4] == "import", image_import
    assert all(
        flag in image_import[ctr_index + 5 :]
        for flag in ("--local", "--digests", "--base-name", "--platform", "linux/amd64", "--no-unpack")
    ), image_import
assert {image_import[image_import.index("--base-name") + 1] for image_import in image_imports} == {
    "account-jwt-bootstrap-account",
    "account-jwt-materializer",
}, image_imports
manifest_reads = [
    call
    for call in state["calls"]
    if call[0] == "docker" and "ctr" in call and "content" in call and "get" in call
]
assert len(manifest_reads) == 2 and {
    call[-1] for call in manifest_reads
} == {
    "sha256:" + account_proof["accountImageManifestDigest"],
    "sha256:" + account_proof["ownerMaterializerManifestDigest"],
}, manifest_reads
workload_create = next(
    index for index, call in enumerate(state["calls"])
    if call[0] == "kubectl" and "create" in call and call[-1].endswith("/account-workload.json")
)
inventory_copy = next(
    index for index, call in enumerate(state["calls"])
    if call[0] == "docker" and call[1:2] == ["cp"] and "/account-inventory-staging/validator-inventory.json" in call[2]
)
atomic_publish = next(
    index for index, call in enumerate(state["calls"])
    if call[0] == "docker" and "mv" in call and call[-1].endswith("/validator-inventory.json")
)
rbac_create = next(
    index for index, call in enumerate(state["calls"])
    if call[0] == "kubectl" and "create" in call and call[-1].endswith("/account-api-rbac.json")
)
assert workload_create < inventory_copy < atomic_publish < rbac_create, state["calls"]
inventory_copy_path = state["calls"][inventory_copy][-1].split(":", 1)[1]
assert inventory_copy_path.endswith("/validator-inventory.json.next-" + account_proof["accountPodUid"]), inventory_copy_path
assert account_proof["accountPodCreated"] is True and account_proof["accountPodRunning"] is True, account_proof
assert account_proof["accountPodUid"] and account_proof["accountPodIp"] == "10.0.0.13", account_proof
assert account_proof["accountPodNodeName"] == state["node_name"], account_proof
assert account_proof["accountPodImage"] == account_proof["accountImage"], account_proof
assert account_proof["accountPodImageStatusId"] == "containerd://" + account_proof["accountImageId"], account_proof
assert "accountProtectedMountReadback" not in account_proof, account_proof
assert "accountProtectedMounts" not in account_proof and "accountMountRoot" not in account_proof, account_proof
assert account_proof["accountTlsNameBindingEvidence"] == {
    "dnsName": f"account-service.{account_proof['namespace']}.svc.cluster.local",
    "spiffeId": f"spiffe://firemud/ns/{account_proof['namespace']}/sa/account-service",
    "evidenceSha256": account_proof["accountTlsNameBindingEvidence"]["evidenceSha256"],
}, account_proof
assert re.fullmatch(r"[0-9a-f]{64}", account_proof["accountTlsLeafSpkiSha256"]), account_proof
account_inventory = state["validator_inventory"]
assert account_inventory["version"] == "account-jwt-validator-inventory-binding/v2", account_inventory
assert account_inventory["bindingDigest"] == hashlib.sha256(
    json.dumps(
        {key: value for key, value in account_inventory.items() if key != "bindingDigest"},
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode()
).hexdigest(), account_inventory
assert len(account_inventory["validators"]) == 1, account_inventory
account_validator = account_inventory["validators"][0]
assert account_validator["validatorId"] == "account-service", account_validator
assert account_validator["deploymentUid"] == account_proof["accountDeploymentUid"], account_validator
assert account_validator["jwksUri"] == f"https://account-service.{account_proof['namespace']}.svc.cluster.local/.well-known/jwks.json", account_validator
assert account_validator["receiverServiceUri"] == account_proof["accountTlsNameBindingEvidence"]["spiffeId"], account_validator
assert account_validator["profiles"] == [
    {"tokenProfile": "game-session-account-delegation", "audience": "account-service"}
], account_validator
assert account_validator["receiverPods"] == [{
    "podUid": account_proof["accountPodUid"],
    "podIp": account_proof["accountPodIp"],
    "leafSpkiSha256": account_proof["accountTlsLeafSpkiSha256"],
}], account_validator
assert account_proof["accountApiRbacUids"].keys() == {"Role", "RoleBinding", "ClusterRole", "ClusterRoleBinding"}, account_proof
assert account_proof["accountApiTokenProjectionReadback"] == "projection=versioned-symlink;link=root-owned;target=root-owned-group-65532-mode-0440-regular-file", account_proof
assert account_proof["accountApiServerAudienceEvidence"]["apiServerVersion"] == "1.35.8", account_proof
assert account_proof["accountApiServerAudienceEvidence"]["audience"] == "https://kubernetes.default.svc.cluster.local", account_proof
assert account_proof["accountApiTokenProjection"] == {
    "volumeName": "account-jwt-api-token",
    "mountPath": "/var/run/secrets/firemud/account-jwt-api-token",
    "tokenPath": "/var/run/secrets/firemud/account-jwt-api-token/token",
    "audience": "https://kubernetes.default.svc.cluster.local",
    "expirationSeconds": 600,
    "defaultMode": 0o440,
}, account_proof
assert "accountProtectedFileSha256" not in account_proof, account_proof
account_objects = state.get("account_objects_at_namespace_delete", state["account_objects"])
assert account_objects["pod/" + account_proof["accountPodName"]]["metadata"]["annotations"][
    "firemud.org/account-jwt-bootstrap-account-config-sha256"
] == account_proof["accountConfigSha256"], account_objects["pod/" + account_proof["accountPodName"]]
assert account_proof["accountTlsLeafCertificateSha256"] == hashlib.sha256(b"account-server-leaf").hexdigest(), account_proof
assert account_proof["validatorInventoryMounted"] is True and account_proof["validatorInventoryObserved"] is None, account_proof
assert account_proof["jwtSignerActivated"] is None and account_proof["noActiveSignerOperationPerformed"] is None, account_proof
assert account_proof["promotionCommitted"] is None, account_proof
assert account_proof["validatorInventoryInstalled"] is True and account_proof["readinessGate"] == "closed", account_proof
assert account_proof["accountApiRbacSpec"]["inventoryDigest"] == account_inventory["bindingDigest"], account_proof
assert set(account_objects) == {
    "secret/account-postgres-credentials",
    "service/account-postgres",
    "pod/account-postgres",
    "serviceaccount/account-service",
    "service/account-service",
    "deployment/account-service",
    "replicaset/" + account_proof["accountReplicaSetName"],
    "pod/" + account_proof["accountPodName"],
    "role/account-jwt-source-" + account_proof["accountApiRbacSpec"]["inventoryDigest"][:16],
    "rolebinding/account-jwt-source-" + account_proof["accountApiRbacSpec"]["inventoryDigest"][:16],
    "clusterrole/account-jwt-source-" + account_proof["accountApiRbacSpec"]["inventoryDigest"][:16],
    "clusterrolebinding/account-jwt-source-" + account_proof["accountApiRbacSpec"]["inventoryDigest"][:16],
    "serviceaccount/account-jwt-materializer",
    "serviceaccount/account-jwt-readiness-harness",
    "role/account-jwt-materializer",
    "rolebinding/account-jwt-materializer",
    "clusterrole/account-jwt-materializer-incarnation-reader",
    "clusterrolebinding/account-jwt-materializer-incarnation-reader",
    "secret/jwt-signing-keys",
    "configmap/jwt-jwks",
    "job/acct-jwt-" + account_proof["namespace"].rsplit("-", 1)[-1] + "-initial-materializer",
    "job/acct-jwt-" + account_proof["namespace"].rsplit("-", 1)[-1] + "-initial-observation",
    "job/acct-jwt-" + account_proof["namespace"].rsplit("-", 1)[-1] + "-readiness-harness",
    "job/acct-jwt-" + account_proof["namespace"].rsplit("-", 1)[-1] + "-followup-materializer",
    *["pod/" + record["podName"] for record in (
        account_proof["ownerBootstrapStages"]["initialObservation"],
        account_proof["ownerBootstrapStages"]["initialMaterializer"],
        account_proof["ownerBootstrapStages"]["readinessHarness"],
        account_proof["ownerBootstrapStages"]["followupMaterializer"],
    )],
}, account_objects
mount_probe = state["account_mount_contract_probe"]
assert len(mount_probe["resources"]) == 9 and "Pod/" + account_proof["accountPodName"] in mount_probe["resources"], mount_probe
assert "ServiceAccount/account-service" in mount_probe["resources"], mount_probe
assert "Deployment/account-service" in mount_probe["resources"], mount_probe
assert "Service/account-service" in mount_probe["resources"], mount_probe
assert mount_probe["podAutomountServiceAccountToken"] is False, mount_probe
assert mount_probe["serviceAccountAutomountServiceAccountToken"] is False, mount_probe
assert mount_probe["readinessGates"] == [{"conditionType": "firemud.org/account-jwt-bootstrap-ready"}], mount_probe
assert mount_probe["hostPathFileVolumes"] and mount_probe["readOnlyProtectedMounts"], mount_probe
assert mount_probe["onlyApiTokenProjectedVolume"] and not mount_probe["noService"], mount_probe
assert mount_probe["canonicalSigningSecretProjection"] == {
    "volume": {"name": "jwt-signing-keys", "secret": {"secretName": "jwt-signing-keys", "defaultMode": 0o440}},
    "mount": {"name": "jwt-signing-keys", "mountPath": "/var/run/secrets/firemud/jwt", "readOnly": True},
}, mount_probe
assert mount_probe["canonicalJwksProjection"] == {
    "volume": {"name": "jwt-jwks", "configMap": {"name": "jwt-jwks", "defaultMode": 0o440}},
    "mount": {"name": "jwt-jwks", "mountPath": "/var/run/secrets/firemud/jwks", "readOnly": True},
}, mount_probe
assert mount_probe["serviceSelector"] == {"firemud.org/account-jwt-bootstrap-validator": "account-service"}, mount_probe
assert state["account_owner_access_created"] is True, state
assert state["account_owner_signing_secret_created_empty"] is True, state
assert state["account_owner_jwks_configmap_created"] is True, state
assert account_objects["service/account-service"]["spec"].get("publishNotReadyAddresses") is not True, account_objects
assert [record["stage"] for record in state["account_owner_jobs"]] == [
    "initial-observation",
    "initial-materializer",
    "readiness-harness",
    "followup-materializer",
], state["account_owner_jobs"]
owner_job_specs = state["account_owner_job_specs"]
assert len(owner_job_specs) == 4, owner_job_specs
for job in owner_job_specs:
    stage = job["metadata"]["labels"]["firemud.org/account-jwt-bootstrap-owner-job"]
    pod_spec = job["spec"]["template"]["spec"]
    assert pod_spec["hostAliases"] == [{
        "ip": account_proof["accountPodIp"],
        "hostnames": [f"account-service.{account_proof['namespace']}.svc.cluster.local"],
    }], job
    assert pod_spec["serviceAccountName"] == (
        "account-jwt-readiness-harness" if stage == "readiness-harness" else "account-jwt-materializer"
    ), job
    assert pod_spec["automountServiceAccountToken"] is False, job
    if stage == "readiness-harness":
        assert not any("projected" in volume for volume in pod_spec["volumes"]), job
        assert pod_spec["containers"][0]["command"] == [
            "/opt/account-jwt-materializer/app/bin/account-jwt-readiness-harness"
        ], job
    else:
        projected = next(volume for volume in pod_spec["volumes"] if volume["name"] == "materializer-api-token")
        assert projected["projected"] == {
            "defaultMode": 0o440,
            "sources": [{"serviceAccountToken": {
                "audience": account_proof["accountApiServerAudienceEvidence"]["audience"],
                "expirationSeconds": 600,
                "path": "token",
            }}],
        }, job
        token_mount = next(
            mount for mount in pod_spec["containers"][0]["volumeMounts"]
            if mount["name"] == "materializer-api-token"
        )
        assert token_mount == {
            "name": "materializer-api-token",
            "mountPath": "/etc/firemud/account-jwt-materializer/kubernetes-token.jwt",
            "subPath": "token",
            "readOnly": True,
        }, job
        if stage == "initial-observation":
            assert pod_spec["containers"][0]["command"] == [
                "/opt/account-jwt-materializer/app/bin/account-jwt-materializer", "--observe-only"
            ], job
        else:
            assert pod_spec["containers"][0].get("command") is None, job
assert state["account_owner_output_reads"] == [
    "initial-observation",
    "initial-materializer",
    "readiness-harness",
    "followup-materializer",
    "initial-observation",
    "initial-materializer",
    "readiness-harness",
    "followup-materializer",
], state["account_owner_output_reads"]
assert [item["jobCount"] for item in state["account_owner_database_reads"]] == [1, 2, 4, 4], state["account_owner_database_reads"]
assert all(item["singleStatement"] and not item["readsSecretContents"] for item in state["account_owner_database_reads"]), state["account_owner_database_reads"]
assert all(
    all(path.startswith(".") or path.startswith(".metadata") for path in read["fields"])
    and not any(path == ".data" or path.startswith(".data.") for path in read["fields"])
    for read in state["metadata_only_reads"]
    if read["name"] in {"jwt-signing-keys", "jwt-jwks"}
), state["metadata_only_reads"]
assert not (input_run_directory / "account-owner-mount-staging").exists(), input_run_directory
assert mount_probe["servicePorts"] == [
    {"name": "https", "port": 443, "targetPort": 8443, "protocol": "TCP"},
    {"name": "grpc", "port": 6565, "targetPort": 6565, "protocol": "TCP"},
], mount_probe
assert mount_probe["imagePullPolicy"] == "Never", mount_probe
assert mount_probe["serverTls"] == {
    "SERVER_PORT": "8443",
    "SERVER_SSL_ENABLED": "true",
    "SERVER_SSL_BUNDLE": "firemud-grpc",
}, mount_probe
assert mount_probe["podSecurityContext"]["fsGroup"] == 65532, mount_probe
assert mount_probe["inventoryDigestAnnotation"] == account_proof["accountApiRbacSpec"]["inventoryDigest"], mount_probe
assert mount_probe["roleRules"] == [
    {"apiGroups": [""], "resources": ["configmaps"], "resourceNames": ["jwt-jwks"], "verbs": ["get", "patch"]},
    {"apiGroups": ["apps"], "resources": ["deployments"], "resourceNames": ["account-service"], "verbs": ["get"]},
    {"apiGroups": ["apps"], "resources": ["replicasets"], "resourceNames": [account_proof["accountReplicaSetName"]], "verbs": ["get"]},
    {"apiGroups": [""], "resources": ["pods"], "verbs": ["list"]},
], mount_probe
assert mount_probe["clusterRoleRules"] == [
    {"apiGroups": ["authentication.k8s.io"], "resources": ["selfsubjectreviews"], "verbs": ["create"]},
    {"apiGroups": [""], "resources": ["namespaces"], "resourceNames": sorted([account_proof["namespace"], "kube-system"]), "verbs": ["get"]},
], mount_probe
assert mount_probe["roleBinding"]["roleRef"]["name"] == mount_probe["clusterRoleBinding"]["roleRef"]["name"], mount_probe
assert mount_probe["roleBinding"]["subjects"] == mount_probe["clusterRoleBinding"]["subjects"] == [
    {"kind": "ServiceAccount", "name": "account-service", "namespace": account_proof["namespace"]}
], mount_probe
assert "/etc/firemud/account-jwt-api" in mount_probe["mountPaths"], mount_probe
assert mount_probe["apiDirectoryVolume"]["hostPath"]["type"] == "Directory", mount_probe
assert "/etc/firemud/account-response-envelope/keyring" in mount_probe["mountPaths"], mount_probe
assert "/etc/firemud/account-login-digest/keyring" in mount_probe["mountPaths"], mount_probe
assert "/run/secrets/firemud/account-coordination-redis/acl-password" in mount_probe["mountPaths"], mount_probe
assert "/var/run/secrets/firemud/account-jwt-api-token" in mount_probe["mountPaths"], mount_probe
account_environment = {item["name"]: item.get("value") for item in mount_probe["environment"]}
assert account_environment["FIREMUD_ACCOUNT_RESPONSE_ENVELOPE_KEYRING_PATH"] == "/etc/firemud/account-response-envelope", mount_probe
assert account_environment["FIREMUD_ACCOUNT_GAMEPLAY_CANONICAL_LOGIN_DIGEST_KEYRING_PATH"] == "/etc/firemud/account-login-digest", mount_probe
assert state["api_token_projection_probe"] == {
    "fixedPath": True,
    "checksVersionedSymlink": True,
    "checksRegularFileMetadata": True,
    "readsTokenContents": False,
}, state
owner_mount_root = os.path.commonpath(mount_probe["nodeMountPaths"])
owner_mount_paths = {
    f"{owner_mount_root}/account-jwt-materializer-runtime/{name}"
    for name in ("account-ca.pem", "binding.json", "client.crt", "client.key", "kubernetes-api-ca.pem")
} | {
    f"{owner_mount_root}/account-jwt-readiness-harness-runtime/{name}"
    for name in ("account-ca.pem", "binding.json", "client-ca.pem", "client.crt", "client.key")
}
assert owner_mount_paths <= set(mount_probe["nodeMountPaths"]), mount_probe
account_input_mount_paths = set(mount_probe["nodeMountPaths"]) - owner_mount_paths
assert len(account_input_mount_paths) == 13, mount_probe
assert len(mount_probe["nodeMountPaths"]) == len(account_input_mount_paths) + len(owner_mount_paths), mount_probe
assert mount_probe["containerSecurityContext"] == {
    "allowPrivilegeEscalation": False,
    "readOnlyRootFilesystem": True,
    "capabilities": {"drop": ["ALL"]},
}, mount_probe
assert mount_probe["podSecurityContext"] == {
    "runAsNonRoot": True,
    "fsGroup": 65532,
    "supplementalGroups": [65532],
    "seccompProfile": {"type": "RuntimeDefault"},
}, mount_probe
account_env = {item["name"]: item for item in mount_probe["environment"]}
assert account_env["FIREMUD_ACCOUNT_GAMEPLAY_CANONICAL_LOGIN_SECURITY_ENABLED"]["value"] == "true", account_env
assert account_env["FIREMUD_ACCOUNT_GAMEPLAY_CANONICAL_LOGIN_ENABLED"]["value"] == "true", account_env
assert account_env["FIREMUD_ACCOUNT_JWT_JWKS_API_ENABLED"]["value"] == "true", account_env
assert account_env["FIREMUD_ACCOUNT_JWT_SIGNER_MATERIALIZATION_ENABLED"]["value"] == "true", account_env
assert account_env["FIREMUD_ACCOUNT_JWT_SIGNER_BOOTSTRAP_ENABLED"]["value"] == "true", account_env
assert account_env["FIREMUD_ACCOUNT_JWT_READINESS_VALIDATION_ENABLED"]["value"] == "true", account_env
assert account_env["FIREMUD_ACCOUNT_JWT_READINESS_PROBE_OWNER_ENABLED"]["value"] == "false", account_env
assert account_env["FIREMUD_ACCOUNT_JWT_READINESS_POD_RECEIVER_ENABLED"]["value"] == "true", account_env
assert account_env["SPRING_GRPC_SERVER_SSL_CLIENT_AUTH"]["value"] == "REQUIRE", account_env
assert account_env["SERVER_PORT"]["value"] == "8443", account_env
assert account_env["SERVER_SSL_ENABLED"]["value"] == "true", account_env
assert account_env["SERVER_SSL_BUNDLE"]["value"] == "firemud-grpc", account_env
verifier_config = json.loads(account_env["FIREMUD_JWT_VERIFIER_CONFIG"]["value"])
assert verifier_config["jwksUri"] == account_validator["jwksUri"], verifier_config
assert account_env["SPRING_PROFILES_ACTIVE"]["value"] == "prod", account_env
assert account_env["FIREMUD_POSTGRES_HOST"]["value"] == f"account-postgres.{account_proof['namespace']}.svc.cluster.local", account_env
assert account_env["FIREMUD_POSTGRES_DATABASE"]["valueFrom"]["secretKeyRef"] == {
    "name": "account-postgres-credentials", "key": "POSTGRES_DB"
}, account_env
assert account_env["FIREMUD_POSTGRES_USERNAME"]["valueFrom"]["secretKeyRef"] == {
    "name": "account-postgres-credentials", "key": "POSTGRES_USER"
}, account_env
assert account_env["FIREMUD_POSTGRES_PASSWORD"]["valueFrom"]["secretKeyRef"] == {
    "name": "account-postgres-credentials", "key": "POSTGRES_PASSWORD"
}, account_env
assert "FIREMUD_AUTH_JWT_SECRET" not in account_env and "FIREMUD_AUTH_JWT_SECRET_PATH" not in account_env, account_env
mount_file_paths = set(mount_probe["nodeMountPaths"])
mount_metadata_readbacks = [
    item for item in state["account_mount_stat_readbacks"] if item["path"] in mount_file_paths
]
assert len(mount_metadata_readbacks) >= 3 * len(mount_file_paths), state["account_mount_stat_readbacks"]
assert all(item["metadata"] == "0:65532:440|regular file" for item in mount_metadata_readbacks), mount_metadata_readbacks
assert sum(call[0] == "docker" and call[1:2] == ["exec"] and call[-2:-1] == ["sha256sum"] for call in state["calls"]) >= 3 * len(mount_file_paths), state["calls"]
assert sum(
    item["path"] == owner_mount_root and item["metadata"] == "0:0:700|directory"
    for item in state["account_mount_stat_readbacks"]
) >= 3, state["account_mount_stat_readbacks"]
assert state["node_files"] == {} and state["node_directories"] == {}, state
assert payload["loginSuccess"] is False and payload["promotionCommitted"] is None, payload
assert payload["fixture"]["redisFixture"]["state"] == "redis_fixture_proof_complete", payload
assert payload["fixture"]["redisFixture"]["waitAof"]["requiredLocal"] == 1, payload
assert payload["fixture"]["redisFixture"]["waitAof"]["requiredReplica"] == 1, payload
assert payload["fixture"]["redisFixture"]["waitAof"]["timeoutMillis"] == 1000, payload
assert payload["fixture"]["redisFixture"]["accountClientCertificateRequired"] is False, payload
assert payload["fixture"]["redisFixture"]["plaintextDenied"] and payload["fixture"]["redisFixture"]["certificateMismatchDenied"], payload
assert state["account_probe_without_client_certificate"] is True, state
assert state["wrong_ca_probe_without_client_certificate"] is True, state
compose = (root / "docker/docker-compose.yml").read_text()
redis_service = re.search(r"(?ms)^  redis-coord:\s*\n(.*?)(?=^  [A-Za-z0-9_-]+:\s*$|\Z)", compose)
redis_image = re.search(r"(?m)^    image:\s*([^\s#]+)", redis_service.group(1)).group(1)
assert payload["fixture"]["redisFixture"]["image"] == redis_image, payload
binding = state["account_binding_probe"]
assert binding["fields"] == sorted(
    [
        "version",
        "enabled",
        "coordinationHost",
        "coordinationPort",
        "tlsServerName",
        "servingCaPath",
        "servingCaSha256",
        "aclUsername",
        "credentialPath",
    ]
), binding
assert binding["digestValid"] and binding["caHashValid"] and binding["passwordMatchesAccountUser"], binding
acl_contract = state["redis_acl_contract_probe"]
account_acl = acl_contract["primary"]["users"]["account_coord_app"]
assert account_acl["keys"] == sorted(
    [
        "~session:auth:token:*",
        "~session:auth:generation:issuer:*",
        "~session:auth:generation:account:*",
        "~session:auth:generation:tenant:*",
        "~session:auth:generation:membership:*",
    ]
), account_acl
assert account_acl["commands"] == sorted(
    [
        "+acl|whoami",
        "+evalsha",
        "+get",
        "+info",
        "+pexpiretime",
        "+pttl",
        "+role",
        "+script|load",
        "+set",
        "+type",
        "+waitaof",
    ]
), account_acl
assert set(acl_contract["primary"]["users"]) == {
    "default",
    "account_coord_app",
    "account_replication",
    "fixture_inspector",
}, acl_contract
assert set(acl_contract["replica"]["users"]) == {
    "default",
    "account_replication",
    "fixture_inspector",
}, acl_contract
assert acl_contract["primary"]["users"]["default"]["enabled"] is False, acl_contract
assert acl_contract["replica"]["users"]["default"]["enabled"] is False, acl_contract
assert "account-password" in acl_contract["primary"]["dataKeys"], acl_contract
assert "account-password" not in acl_contract["replica"]["dataKeys"], acl_contract
assert acl_contract["primary"]["dataKeys"] == ["account-password", "inspector-password", "users.acl"], acl_contract
assert acl_contract["replica"]["dataKeys"] == ["replication-password", "users.acl"], acl_contract
assert acl_contract["podAuthSecret"] == {
    "redis-primary": "redis-primary-auth",
    "redis-replica": "redis-replica-auth",
}, acl_contract
assert acl_contract["aclfilePaths"] == {
    "primary": "/run/redis-primary-auth/users.acl",
    "replica": "/run/redis-replica-auth/users.acl",
}, acl_contract
assert all(
    role_user in acl_contract["replica"]["users"]
    for role_user in ("account_replication", "fixture_inspector")
), acl_contract
for role in ("primary", "replica"):
    replication_acl = acl_contract[role]["users"]["account_replication"]
    assert replication_acl["keys"] == ["~*"], (role, replication_acl)
    assert replication_acl["commands"] == sorted(["+ping", "+psync", "+replconf"]), (role, replication_acl)
    inspector_acl = acl_contract[role]["users"]["fixture_inspector"]
    assert inspector_acl["keys"] == [], (role, inspector_acl)
    assert inspector_acl["commands"] == sorted(["+acl|whoami", "+config|get", "+info", "+role"]), (role, inspector_acl)
assert binding["coordinationHost"] == binding["tlsServerName"] == payload["fixture"]["redisFixture"]["coordinationHost"], binding
assert binding["port"] == 6379 and binding["aclUsername"] == "account_coord_app", binding
assert binding["caPath"] == "/etc/firemud/account-coordination-redis/coordination-ca.pem", binding
assert binding["credentialPath"] == "/run/secrets/firemud/account-coordination-redis/acl-password", binding
assert any(call[0] == "kind" and call[1:3] == ["delete", "cluster"] for call in state["calls"]), state["calls"]
assert state["network_rm_ids"] == [state["network_id"]], state
assert state["openssl_calls"], state
assert sum(call[:1] == ["req"] and "-newkey" in call and any(arg.startswith("/CN=account-jwt-bootstrap-") for arg in call) for call in state["openssl_calls"]) == 1, state["openssl_calls"]
assert sum(call[:1] == ["req"] and "-newkey" in call and any(arg.startswith("/CN=account-service.") for arg in call) for call in state["openssl_calls"]) == 1, state["openssl_calls"]
assert sum(call[:1] == ["req"] and "-newkey" in call and any(arg.startswith("/CN=account-jwt-materializer.") for arg in call) for call in state["openssl_calls"]) == 1, state["openssl_calls"]
assert sum(call[:1] == ["req"] and "-newkey" in call and any(arg.startswith("/CN=account-jwt-readiness-harness.") for arg in call) for call in state["openssl_calls"]) == 1, state["openssl_calls"]
assert sum(call[:1] == ["verify"] for call in state["openssl_calls"]) == 3, state["openssl_calls"]
openssl_pubkey_count = sum(call[:1] == ["x509"] and "-pubkey" in call for call in state["openssl_calls"])
openssl_san_count = sum(call[:1] == ["x509"] and "subjectAltName" in call for call in state["openssl_calls"])
openssl_der_count = sum(call[:1] == ["pkey"] and "DER" in call for call in state["openssl_calls"])
assert openssl_pubkey_count == 4, ("x509 public-key reads", openssl_pubkey_count)
assert openssl_san_count == 4, ("certificate SAN reads", openssl_san_count)
assert openssl_der_count == 7, ("public-key DER reads", openssl_der_count)
assert state["exec_calls"] and not state.get("runtime_process_started"), state
assert all(call[0] in {"kind", "kubectl", "docker", "git", "openssl"} for call in state["calls"]), state["calls"]
assert "subprocess" not in fake_tool
temporary.cleanup()

for flags, expected_reason, expected_stages in (
    ({"account_owner_db_state_missing": True}, "account_owner_database_binding_mismatch", ["initial-observation"]),
    ({"account_owner_db_active_state": True}, "account_owner_initial_signer_state_not_empty", ["initial-observation"]),
    ({"account_owner_db_observation_uid_mismatch": True}, "account_owner_database_binding_mismatch", ["initial-observation"]),
    ({"account_owner_db_inventory_mismatch": True}, "account_owner_committed_readback_incomplete", ["initial-observation", "initial-materializer", "readiness-harness", "followup-materializer"]),
    ({"account_owner_db_commit_incomplete": True}, "account_owner_committed_readback_incomplete", ["initial-observation", "initial-materializer", "readiness-harness", "followup-materializer"]),
):
    temporary, _, _, result, payload, state = run_case(flags)
    assert result.returncode == 1 and payload["reason"] == expected_reason, (flags, payload, state.get("calls", [])[-12:])
    assert payload["cleanup"] == "resources_retained", (flags, payload)
    assert [item["stage"] for item in state.get("account_owner_jobs", [])] == expected_stages, (flags, state.get("account_owner_jobs"))
    assert state.get("account_owner_database_reads"), (flags, state)
    assert all(item["singleStatement"] and not item["readsSecretContents"] for item in state["account_owner_database_reads"]), state
    assert state.get("redis_namespace_exists") and state.get("created"), (flags, state)
    assert not any(call[0] == "kind" and call[1:3] == ["delete", "cluster"] for call in state["calls"]), state["calls"]
    temporary.cleanup()

for flags, expected_reason in (
    ({"malformed_account_input": True}, "account_protected_input_identity_changed"),
    ({"mismatched_account_input": True}, "account_protected_input_identity_changed"),
    ({"missing_account_input": "account-jwt-api/binding.json"}, "account_protected_input_tree_incomplete"),
    ({"account_input_parent_symlink": True}, "account_protected_input_tree_invalid"),
    ({"account_input_leaf_symlink": True}, "account_protected_input_tree_invalid"),
    ({"account_api_server_ca_invalid": True}, "account_api_server_ca_invalid"),
    ({"account_tls_keypair_mismatch": True}, "account_tls_private_key_certificate_mismatch"),
    ({"account_materializer_tls_identity_mismatch": True}, "account_materializer_tls_identity_mismatch"),
    ({"account_api_server_audience_invalid": True}, "account_api_server_audience_unavailable"),
    ({"account_api_server_audience_flag_missing_value": True}, "account_api_server_audience_unavailable"),
    ({"validator_inventory_resource_drift": True}, "account_validator_inventory_resource_drift"),
    ({"account_mount_metadata_mismatch": True}, "account_fixture_mount_readback_invalid"),
    ({"account_tls_leaf_digest_mismatch": True}, "account_fixture_tls_leaf_identity_changed"),
    ({"account_tls_name_binding_mismatch": True}, "account_fixture_tls_name_binding_invalid"),
    ({"account_image_manifest_config_mismatch": True}, "account_image_manifest_source_mismatch"),
):
    temporary, _, _, result, payload, state = run_case(flags)
    assert result.returncode == 1 and payload["reason"] == expected_reason, (
        flags,
        payload,
        state.get("calls", [])[-8:],
    )
    retain = expected_reason in {
        "account_protected_binding_malformed",
        "account_validator_runtime_configuration_mismatch",
        "account_validator_inventory_resource_drift",
    }
    input_identity_retained = expected_reason in {
        "account_protected_input_identity_changed",
        "account_protected_input_tree_invalid",
    }
    if input_identity_retained:
        run_directory = Path(state["runner_temp"]) / (
            "account-jwt-bootstrap-" + state["run_id"] + "-" + state["attempt"] + "-" + state["head"][:12]
        )
        assert payload["cleanup"] == "resources_retained", (flags, payload)
        assert not state.get("created") and not state.get("redis_namespace_exists"), (flags, state)
        assert state.get("node_files", {}) == {} and state.get("node_directories", {}) == {}, (flags, state)
        assert (run_directory / "account-inputs").exists(), (flags, run_directory)
        assert any(call[0] == "kind" and call[1:3] == ["delete", "cluster"] for call in state["calls"]), state["calls"]
    elif expected_reason == "account_protected_input_tree_incomplete":
        assert payload["cleanup"] == "removed", (flags, payload)
        run_directory = Path(state["runner_temp"]) / (
            "account-jwt-bootstrap-" + state["run_id"] + "-" + state["attempt"] + "-" + state["head"][:12]
        )
        assert not (run_directory / "account-inputs").exists(), (flags, run_directory)
    else:
        assert payload["cleanup"] == ("resources_retained" if retain else "removed"), (flags, payload)
        if retain:
            assert state.get("redis_namespace_exists") and state.get("created"), (flags, payload, state)
            assert state.get("node_files") and state.get("node_directories"), (flags, payload, state)
            assert not any(call[0] == "kind" and call[1:3] == ["delete", "cluster"] for call in state["calls"]), state["calls"]
        else:
            assert not state.get("redis_namespace_exists"), (flags, payload, state.get("redis_namespace_exists"))
            assert state.get("node_files", {}) == {} and state.get("node_directories", {}) == {}, (
                flags,
                state.get("node_files"),
                state.get("node_directories"),
            )
            assert any(call[0] == "kind" and call[1:3] == ["delete", "cluster"] for call in state["calls"]), (
                flags,
                payload,
                state.get("calls", [])[-8:],
            )
    temporary.cleanup()

for flags, expected_reason in (
    ({"account_pod_config_mismatch": True}, "account_fixture_deployment_pod_identity_invalid"),
    ({"account_pod_spec_mismatch": True}, "account_fixture_deployment_pod_identity_invalid"),
    ({"account_pod_image_mismatch": True}, "account_fixture_pod_image_readback_invalid"),
    ({"account_pod_ready_gate_open": True}, "account_fixture_readiness_gate_open"),
    ({"account_api_token_metadata_mismatch": True}, "account_api_token_projection_readback_invalid"),
    ({"account_api_rbac_spec_mismatch": True}, "account_materializer_access_spec_changed"),
    ({"account_pod_api_projection_mismatch": True}, "account_fixture_account_pod_spec_mismatch"),
    ({"account_api_server_audience_drift": True}, "account_owner_job_api_audience_changed"),
):
    temporary, _, _, result, payload, state = run_case(flags)
    assert result.returncode == 1 and payload["reason"] == expected_reason, (flags, payload, state.get("calls", [])[-12:])
    assert payload["cleanup"] == "resources_retained", (flags, payload)
    assert state.get("redis_namespace_exists") and state.get("created"), (flags, state)
    if flags == {"account_api_rbac_spec_mismatch": True}:
        assert "role/account-jwt-materializer" in state.get("account_objects", {}), (flags, state)
        assert not any(
            name.startswith(("deployment/account-service", "pod/account-service"))
            for name in state.get("account_objects", {})
        ), (flags, state.get("account_objects", {}))
    else:
        assert state.get("account_objects", {}).get("pod/" + state.get("account_pod_name", "")), (flags, state)
    assert not any(call[0] == "kind" and call[1:3] == ["delete", "cluster"] for call in state["calls"]), state["calls"]
    temporary.cleanup()

temporary, _, _, result, payload, state = run_case({"account_pod_uid_drift_on_teardown": True})
assert result.returncode == 1 and payload["reason"] == "account_owner_job_receiver_podUid_changed", (
    payload.get("reason"),
    state.get("account_pod_lists"),
    state.get("account_pod_reads"),
)
assert state.get("account_pod_lists") == 5 and state.get("account_pod_reads") is None, state
assert state.get("account_owner_jobs", []) == [], state
assert payload["cleanup"] == "resources_retained", payload
assert state.get("redis_namespace_exists") and state.get("created"), state
assert state.get("node_files") and state.get("node_directories"), state
assert not any(call[0] == "kind" and call[1:3] == ["delete", "cluster"] for call in state["calls"]), state["calls"]
temporary.cleanup()

for flags, expected_reason in (
    ({"force_bad_config": True}, "redis_fixture_redis_config_mismatch"),
    ({"force_aof_unhealthy": True}, "redis_fixture_account_observation_mismatch"),
    ({"force_low_waitaof": True}, "redis_fixture_replica_threshold_unavailable"),
    ({"force_wrong_account_role": True}, "redis_fixture_account_probe_mismatch"),
):
    temporary, _, _, result, payload, state = run_case(flags)
    assert result.returncode == 1 and payload["reason"] == expected_reason, (flags, payload)
    assert payload["cleanup"] == "removed", (flags, payload)
    assert not state.get("redis_namespace_exists"), (flags, state)
    assert any(call[0] == "kind" and call[1:3] == ["delete", "cluster"] for call in state["calls"]), state["calls"]
    temporary.cleanup()

temporary, _, _, result, payload, state = run_case({"force_plaintext_accept": True})
assert result.returncode == 1 and payload["reason"] == "redis_fixture_plaintext_accepted", payload
assert payload["cleanup"] == "removed", payload
assert not state.get("redis_namespace_exists"), state
assert any(call[0] == "kubectl" and "delete" in call and "namespace" in call for call in state["calls"]), state["calls"]
assert any(call[0] == "kind" and call[1:3] == ["delete", "cluster"] for call in state["calls"]), state["calls"]
temporary.cleanup()

temporary, _, _, result, payload, state = run_case({"force_wrong_ca_accept": True})
assert result.returncode == 1 and payload["reason"] == "redis_fixture_certificate_mismatch_accepted", payload
assert payload["cleanup"] == "removed", payload
assert not state.get("redis_namespace_exists"), state
temporary.cleanup()

temporary, _, _, result, payload, state = run_case({"redis_namespace_annotation_drift": True})
assert result.returncode == 1 and payload["cleanup"] == "resources_retained", payload
assert payload["reason"] == "redis_fixture_resource_identity_changed", payload
assert not any(call[0] == "kubectl" and "delete" in call and "namespace" in call for call in state["calls"]), state["calls"]
assert_no_delete(state)
temporary.cleanup()

temporary, _, _, result, payload, state = run_case(tampered_kind=True)
assert result.returncode == 1 and payload["reason"] == "kind_binary_checksum_mismatch", payload
assert not any(call[0] == "kind" for call in state["calls"]), state["calls"]
assert not any(call[0] == "kind" and call[1:3] == ["create", "cluster"] for call in state["calls"]), state["calls"]
temporary.cleanup()

temporary, _, _, result, payload, state = run_case({"server_version_mismatch": True})
assert result.returncode == 1 and payload["reason"] == "kubernetes_api_version_mismatch_resources_retained", payload
assert_no_delete(state)
temporary.cleanup()

for drift in ("context_drift", "api_drift", "uid_drift", "container_drift"):
    temporary, _, _, result, payload, state = run_case({drift: True})
    assert result.returncode == 1, (drift, result.returncode, payload)
    assert payload["outcome"] == "denied" and payload["cleanup"] == "resources_retained", (drift, payload)
    assert_no_delete(state)
    temporary.cleanup()

temporary, _, _, result, payload, state = run_case(old_context=True)
assert result.returncode == 1 and payload["reason"] == "existing_kubeconfig_refused", payload
assert_no_delete(state)
assert not any(call[0] == "kind" and call[1:3] == ["create", "cluster"] for call in state["calls"]), state["calls"]
temporary.cleanup()

temporary, _, _, result, payload, state = run_case({"create_failure": True})
assert result.returncode == 1 and payload["cleanup"] == "resources_retained", payload
assert_no_delete(state)
temporary.cleanup()

temporary, _, _, result, payload, state = run_case({"delete_failure": True})
assert result.returncode == 1 and payload["cleanup"] == "resources_retained", payload
assert any(call[0] == "kind" and call[1:3] == ["delete", "cluster"] for call in state["calls"]), state["calls"]
temporary.cleanup()

temporary, _, _, result, payload, state = run_case({"network_id_drift_after_delete": True})
assert result.returncode == 1 and payload["cleanup"] == "resources_retained", (payload, state)
assert payload["reason"] == "docker_network_cleanup_identity_uncertain_resources_retained", payload
assert not any(call[0] == "docker" and call[1:3] == ["network", "rm"] for call in state["calls"]), state["calls"]
temporary.cleanup()

temporary, _, _, result, payload, state = run_case({"network_name_drift_after_delete": True})
assert result.returncode == 1 and payload["cleanup"] == "resources_retained", payload
assert payload["reason"] == "docker_network_cleanup_identity_uncertain_resources_retained", payload
assert not any(call[0] == "docker" and call[1:3] == ["network", "rm"] for call in state["calls"]), state["calls"]
temporary.cleanup()

temporary, _, _, result, payload, state = run_case({"unrelated_network_after_delete": "f" * 64})
assert result.returncode == 1 and payload["cleanup"] == "resources_retained", payload
assert payload["reason"] == "docker_network_cleanup_identity_uncertain_resources_retained", payload
assert not any(call[0] == "docker" and call[1:3] == ["network", "rm"] for call in state["calls"]), state["calls"]
temporary.cleanup()

prepared, runner_temp, env, result, payload, state = run_case(run_id="77123", attempt="1", operation="prepare")
assert result.returncode == 0 and payload["outcome"] == "fixture_ready", payload
claim = runner_temp / f"account-jwt-bootstrap-77123-1-{head[:12]}" / "fixture.json"
receipt = json.loads(claim.read_text())
receipt["runAttempt"] = "2"
claim.write_text(json.dumps(receipt))
teardown = subprocess.run(
    [
        "python3",
        str(Path(env["GITHUB_WORKSPACE"]) / "dev-tools/hosted/account-bootstrap/account-jwt-bootstrap-fixture.py"),
        "--authority",
        str(Path(env["GITHUB_WORKSPACE"]) / "config/workflow-tool-versions.env"),
        "--operation",
        "teardown",
    ],
    cwd=env["GITHUB_WORKSPACE"],
    env=env,
    capture_output=True,
    text=True,
    check=False,
    timeout=40,
)
teardown_payload = json.loads(teardown.stdout.strip())
state = json.loads((Path(env["PATH"].split(":", 1)[0]) / "state.json").read_text())
assert teardown.returncode == 1 and teardown_payload["cleanup"] == "resources_retained", teardown_payload
assert teardown_payload["reason"] == "fixture_claim_run_identity_mismatch_resources_retained", teardown_payload
assert_no_delete(state)
prepared.cleanup()

first, _, _, first_result, first_payload, _ = run_case(run_id="88331", attempt="1")
second, _, _, second_result, second_payload, _ = run_case(run_id="88331", attempt="2")
assert first_result.returncode == second_result.returncode == 2
assert first_payload["fixture"]["clusterName"] != second_payload["fixture"]["clusterName"]
first.cleanup()
second.cleanup()

print("Account JWT disposable fixture contract: mocked ownership and lifecycle checks passed")
PY
