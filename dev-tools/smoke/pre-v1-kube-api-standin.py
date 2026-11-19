#!/usr/bin/env python3
"""Pre-v1 stand-in for the Kubernetes API that Account's JWT signer lifecycle reads.

Compose smoke only, under the pre-v1 gate waiver (ADR 0185). It serves the fixed endpoints in
AccountJwtJwksApiBinding.ApiCall over TLS with one bearer token: SelfSubjectReview, the
kube-system and target namespaces, and the jwt-jwks ConfigMap with resourceVersion CAS. A real
cluster API and its RBAC are owed before v1.
"""

import argparse
import hmac
import json
import os
import ssl
import tempfile
import threading
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONFIG_MAP_NAME = "jwt-jwks"
POD_TEMPLATE_HASH = "standin1"
RUNTIME_CONFIG_ENV = "FIREMUD_JWT_VERIFIER_CONFIG"


def runtime_config(validator):
    profiles = sorted(
        ({"audience": p["audience"], "tokenProfile": p["tokenProfile"]} for p in validator["profiles"]),
        key=lambda p: (p["tokenProfile"], p["audience"]),
    )
    return json.dumps(
        {
            "algorithm": "RS256",
            "jwksUri": validator["jwksUri"],
            "maxCacheAgeSeconds": validator["maxCacheAgeSeconds"],
            "profiles": profiles,
        },
        sort_keys=True,
        separators=(",", ":"),
    )


def pod_template(validator, extra_labels):
    labels = dict(validator["selector"])
    labels.update(extra_labels)
    return {
        "metadata": {"labels": labels},
        "spec": {
            "containers": [
                {
                    "name": validator["containerName"],
                    "image": validator["image"],
                    "env": [{"name": RUNTIME_CONFIG_ENV, "value": runtime_config(validator)}],
                }
            ]
        },
    }


class Validators:
    """Deployment, ReplicaSet and Pods for each validator in the protected inventory binding."""

    def __init__(self, inventory_file, namespace):
        self.namespace = namespace
        self.by_deployment = {}
        self.by_replica_set = {}
        self.by_selector = {}
        if not inventory_file:
            return
        with open(inventory_file, encoding="utf-8") as handle:
            inventory = json.load(handle)
        for validator in inventory["validators"]:
            replica_set = validator["deploymentName"] + "-" + POD_TEMPLATE_HASH
            replica_set_uid = str(uuid.uuid5(uuid.NAMESPACE_URL, "rs/" + validator["deploymentUid"]))
            self.by_deployment[validator["deploymentName"]] = validator
            self.by_replica_set[replica_set] = (validator, replica_set_uid)
            selector = ",".join(f"{k}={v}" for k, v in sorted(validator["selector"].items()))
            self.by_selector[selector] = (validator, replica_set, replica_set_uid)

    def deployment(self, name):
        validator = self.by_deployment.get(name)
        if validator is None:
            return None
        replicas = validator["replicas"]
        return {
            "apiVersion": "apps/v1",
            "kind": "Deployment",
            "metadata": {
                "name": name,
                "namespace": self.namespace,
                "uid": validator["deploymentUid"],
                "resourceVersion": "1",
                "generation": 1,
            },
            "spec": {
                "replicas": replicas,
                "selector": {"matchLabels": dict(validator["selector"])},
                "template": pod_template(validator, {}),
            },
            "status": {
                "observedGeneration": 1,
                "replicas": replicas,
                "updatedReplicas": replicas,
                "readyReplicas": replicas,
                "availableReplicas": replicas,
            },
        }

    def replica_set(self, name):
        entry = self.by_replica_set.get(name)
        if entry is None:
            return None
        validator, uid = entry
        labels = {"pod-template-hash": POD_TEMPLATE_HASH}
        selector = dict(validator["selector"])
        selector.update(labels)
        return {
            "apiVersion": "apps/v1",
            "kind": "ReplicaSet",
            "metadata": {
                "name": name,
                "namespace": self.namespace,
                "uid": uid,
                "resourceVersion": "1",
                "generation": 1,
                "ownerReferences": [
                    {
                        "apiVersion": "apps/v1",
                        "kind": "Deployment",
                        "name": validator["deploymentName"],
                        "uid": validator["deploymentUid"],
                        "controller": True,
                    }
                ],
            },
            "spec": {
                "replicas": validator["replicas"],
                "selector": {"matchLabels": selector},
                "template": pod_template(validator, labels),
            },
            "status": {"observedGeneration": 1, "replicas": validator["replicas"],
                       "readyReplicas": validator["replicas"]},
        }

    def pods(self, selector):
        entry = self.by_selector.get(selector)
        items = []
        if entry is not None:
            validator, replica_set, replica_set_uid = entry
            digest = validator["image"].rsplit("@", 1)[1]
            template = pod_template(validator, {"pod-template-hash": POD_TEMPLATE_HASH})
            for index, pin in enumerate(validator["receiverPods"]):
                items.append(
                    {
                        "apiVersion": "v1",
                        "kind": "Pod",
                        "metadata": {
                            "name": f"{replica_set}-{index}",
                            "namespace": self.namespace,
                            "uid": pin["podUid"],
                            "resourceVersion": "1",
                            "labels": template["metadata"]["labels"],
                            "ownerReferences": [
                                {
                                    "apiVersion": "apps/v1",
                                    "kind": "ReplicaSet",
                                    "name": replica_set,
                                    "uid": replica_set_uid,
                                    "controller": True,
                                }
                            ],
                        },
                        "spec": template["spec"],
                        "status": {
                            "phase": "Running",
                            "podIP": pin["podIp"],
                            "conditions": [{"type": "Ready", "status": "True"}],
                            "containerStatuses": [
                                {
                                    "name": validator["containerName"],
                                    "ready": True,
                                    "imageID": "docker-pullable://standin@" + digest,
                                }
                            ],
                        },
                    }
                )
        return {"apiVersion": "v1", "kind": "PodList", "metadata": {"resourceVersion": "1"},
                "items": items}


class State:
    def __init__(self, args):
        self.lock = threading.Lock()
        self.username = args.username
        self.namespace = args.namespace
        self.namespace_uid = args.namespace_uid
        self.cluster_uid = args.cluster_uid
        self.token = open(args.token_file, encoding="ascii").read().strip()
        self.config_map_uid = args.config_map_uid
        self.projection_dir = args.projection_dir
        self.validators = Validators(args.inventory, args.namespace)
        self.resource_version = 1
        self.data = {}

    def project(self):
        """Mirror ConfigMap data into a mounted directory, as kubelet does for a volume."""
        if not self.projection_dir:
            return
        for key, value in self.data.items():
            fd, tmp = tempfile.mkstemp(dir=self.projection_dir)
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(value)
            os.chmod(tmp, 0o444)
            os.replace(tmp, os.path.join(self.projection_dir, key))

    def config_map(self):
        return {
            "apiVersion": "v1",
            "kind": "ConfigMap",
            "metadata": {
                "name": CONFIG_MAP_NAME,
                "namespace": self.namespace,
                "uid": self.config_map_uid,
                "resourceVersion": str(self.resource_version),
            },
            "data": dict(self.data),
        }


def namespace_body(name, uid):
    return {
        "apiVersion": "v1",
        "kind": "Namespace",
        "metadata": {"name": name, "uid": uid},
        "status": {"phase": "Active"},
    }


def make_handler(state):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            return

        def reply(self, status, body):
            raw = json.dumps(body).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def authorized(self):
            supplied = self.headers.get("Authorization", "")
            return hmac.compare_digest(supplied, "Bearer " + state.token)

        def body(self):
            length = int(self.headers.get("Content-Length", "0") or "0")
            return self.rfile.read(length) if length else b""

        def do_GET(self):
            if not self.authorized():
                return self.reply(401, {"kind": "Status", "code": 401})
            if self.path == "/api/v1/namespaces/kube-system":
                return self.reply(200, namespace_body("kube-system", state.cluster_uid))
            if self.path == "/api/v1/namespaces/" + state.namespace:
                return self.reply(200, namespace_body(state.namespace, state.namespace_uid))
            if self.path == f"/api/v1/namespaces/{state.namespace}/configmaps/{CONFIG_MAP_NAME}":
                with state.lock:
                    return self.reply(200, state.config_map())
            apps = f"/apis/apps/v1/namespaces/{state.namespace}/"
            found = None
            if self.path.startswith(apps + "deployments/"):
                found = state.validators.deployment(self.path[len(apps + "deployments/"):])
            elif self.path.startswith(apps + "replicasets/"):
                found = state.validators.replica_set(self.path[len(apps + "replicasets/"):])
            elif self.path.startswith(f"/api/v1/namespaces/{state.namespace}/pods?"):
                query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
                found = state.validators.pods(query.get("labelSelector", [""])[0])
            if found is not None:
                return self.reply(200, found)
            return self.reply(404, {"kind": "Status", "code": 404})

        def do_POST(self):
            if not self.authorized():
                return self.reply(401, {"kind": "Status", "code": 401})
            self.body()
            if self.path == "/apis/authentication.k8s.io/v1/selfsubjectreviews":
                return self.reply(
                    201,
                    {
                        "apiVersion": "authentication.k8s.io/v1",
                        "kind": "SelfSubjectReview",
                        "status": {"userInfo": {"username": state.username}},
                    },
                )
            return self.reply(404, {"kind": "Status", "code": 404})

        def do_PATCH(self):
            if not self.authorized():
                return self.reply(401, {"kind": "Status", "code": 401})
            if self.path != f"/api/v1/namespaces/{state.namespace}/configmaps/{CONFIG_MAP_NAME}":
                return self.reply(404, {"kind": "Status", "code": 404})
            if self.headers.get("Content-Type") != "application/merge-patch+json":
                return self.reply(415, {"kind": "Status", "code": 415})
            try:
                patch = json.loads(self.body())
            except ValueError:
                return self.reply(400, {"kind": "Status", "code": 400})
            metadata = patch.get("metadata") or {}
            with state.lock:
                if metadata.get("uid") not in (None, state.config_map_uid):
                    return self.reply(409, {"kind": "Status", "code": 409})
                expected = metadata.get("resourceVersion")
                if expected is not None and expected != str(state.resource_version):
                    return self.reply(409, {"kind": "Status", "code": 409})
                for key, value in (patch.get("data") or {}).items():
                    if value is None:
                        state.data.pop(key, None)
                    else:
                        state.data[key] = value
                state.resource_version += 1
                state.project()
                return self.reply(200, state.config_map())

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=6443)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("--namespace", required=True)
    parser.add_argument("--namespace-uid", required=True)
    parser.add_argument("--cluster-uid", required=True)
    parser.add_argument("--config-map-uid", required=True)
    parser.add_argument("--projection-dir", help="directory mirroring jwt-jwks ConfigMap data")
    parser.add_argument("--inventory", help="protected validator inventory binding to serve")
    args = parser.parse_args()
    state = State(args)
    server = ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(state))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.cert, args.key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
