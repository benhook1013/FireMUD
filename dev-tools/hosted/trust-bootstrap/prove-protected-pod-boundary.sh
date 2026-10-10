#!/usr/bin/env bash
set -euo pipefail

# Operator-invoked live admission proof; every POST/PUT is server dry-run.
# No credentials, temporary RBAC, real Pods, or cluster cleanup are created.
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
python3 - "$repo_root/k8s/trust-bootstrap/deployment-admission.yaml" "$@" <<'PY'
import argparse
import copy
import json
import re
import subprocess
import sys
import uuid

import yaml


def fail(message):
    raise SystemExit("protected-Pod admission proof failed: " + message)


parser = argparse.ArgumentParser(description="Live native Pod admission proof using dryRun=All only")
parser.add_argument("--context", required=True)
parser.add_argument("--namespace", required=True)
parser.add_argument("--pod", required=True, help="Exact existing Account or Game Session Pod name")
args = parser.parse_args(sys.argv[2:])
if not args.context.strip() or args.context.startswith("-"):
    parser.error("an explicit nonempty context is required")
if not re.fullmatch(r"dev|pr-[1-9][0-9]{0,50}", args.namespace):
    parser.error("namespace must be exactly dev or canonical pr-N")
if not re.fullmatch(r"[a-z0-9](?:[-a-z0-9.]{0,251}[a-z0-9])?", args.pod):
    parser.error("--pod must be an exact Pod name")
kubectl = ["kubectl", "--context", args.context, "--request-timeout=30s"]


def call(command, body=None):
    try:
        return subprocess.run(kubectl + command, input=None if body is None else json.dumps(body),
                              text=True, capture_output=True, check=False, timeout=45)
    except (OSError, subprocess.TimeoutExpired):
        fail("kubectl unavailable or timed out; no admission conclusion")


def get(resource, name, namespaced=False):
    command = (["-n", args.namespace] if namespaced else []) + ["get", resource, name, "-o", "json"]
    result = call(command)
    if result.returncode:
        fail("cannot read " + resource + "/" + name)
    try:
        return json.loads(result.stdout)
    except ValueError:
        fail("malformed API readback for " + resource)


boundary = "firemud-trust-runtime-pod-boundary"
identity = "firemud-trust-runtime-pod-identity"
revision_key = "firemud.dev/admission-revision"
with open(sys.argv[1], encoding="utf-8") as stream:
    expected = [item for item in yaml.safe_load_all(stream)
                if item and item.get("metadata", {}).get("name") in (boundary, identity)]
if len(expected) != 4 or {(item["kind"], item["metadata"]["name"]) for item in expected} != {
    (kind, name) for kind in ("ValidatingAdmissionPolicy", "ValidatingAdmissionPolicyBinding")
    for name in (boundary, identity)
}:
    fail("trusted manifest lacks the exact protected Pod policy/binding set")


def canonical_spec(kind, spec):
    result = copy.deepcopy(spec)
    if not isinstance(result, dict):
        return result
    match = result.setdefault("matchConstraints" if kind == "ValidatingAdmissionPolicy" else "matchResources", {})
    if isinstance(match, dict):
        match.setdefault("namespaceSelector", {})
        match.setdefault("objectSelector", {})
        match.setdefault("matchPolicy", "Equivalent")
    return result


def verify_policies():
    versions = {}
    for wanted in expected:
        kind, name = wanted["kind"], wanted["metadata"]["name"]
        actual = get(kind + ".admissionregistration.k8s.io", name)
        metadata = actual.get("metadata", {})
        if (actual.get("apiVersion") != wanted["apiVersion"] or actual.get("kind") != kind
                or metadata.get("name") != name or metadata.get("deletionTimestamp") is not None
                or wanted["metadata"].get("annotations", {}).get(revision_key) != "hosted-pod-identity-v1"
                or metadata.get("annotations", {}).get(revision_key) != "hosted-pod-identity-v1"
                or canonical_spec(kind, actual.get("spec")) != canonical_spec(kind, wanted["spec"])
                or not metadata.get("uid") or not metadata.get("resourceVersion")):
            fail("installed policy/binding differs from exact trusted revision: " + name)
        if kind == "ValidatingAdmissionPolicy":
            status = actual.get("status", {})
            if (actual["spec"].get("failurePolicy") != "Fail"
                    or not isinstance(metadata.get("generation"), int)
                    or status.get("observedGeneration") != metadata["generation"]
                    or not isinstance(status.get("typeChecking"), dict)
                    or status["typeChecking"].get("expressionWarnings", []) != []):
                fail("policy typechecking/current generation/Fail not proved: " + name)
        elif actual["spec"].get("validationActions") != ["Deny"]:
            fail("binding is not exact Deny: " + name)
        versions[(kind, name)] = (metadata["uid"], metadata["resourceVersion"])
    return versions


namespace = get("namespace", args.namespace)
labels = namespace.get("metadata", {}).get("labels", {})
if (namespace.get("metadata", {}).get("name") != args.namespace
        or namespace.get("metadata", {}).get("deletionTimestamp") is not None
        or namespace.get("status", {}).get("phase") != "Active"):
    fail("namespace is absent, terminating, or not Active")
if args.namespace == "dev":
    valid_namespace = labels.get("firemud.dev/dev-demo") == "true" and labels.get("firemud.dev/environment-class") == "dev-demo-cluster"
else:
    valid_namespace = labels.get("firemud.dev/preview") == "true" and labels.get("firemud.dev/pr-number") == args.namespace[3:]
if not valid_namespace:
    fail("namespace does not carry canonical hosted ownership labels")
policy_versions = verify_policies()
base_path = "/api/v1/namespaces/" + args.namespace + "/pods"


def probe(description, body, policy=None, message=None, path=None):
    command = ["replace" if path else "create", "--raw", (path or base_path) + "?dryRun=All", "-f", "-"]
    result = call(command, body)
    if policy is None:
        if result.returncode:
            fail(description + ": valid control was not admitted")
        try:
            returned = json.loads(result.stdout)
        except ValueError:
            fail(description + ": no valid Pod response")
        if returned.get("kind") != "Pod" or returned.get("metadata", {}).get("name") != body["metadata"]["name"]:
            fail(description + ": unexpected successful response")
    else:
        # Authentication, schema, immutability, conflict and arbitrary API errors
        # are never accepted as admission proof. Require native named rejection.
        rejection = (r"ValidatingAdmissionPolicy [\"']" + re.escape(policy)
                     + r"[\"'] with binding [\"']" + re.escape(policy)
                     + r"[\"'] denied request: " + re.escape(message))
        if result.returncode == 0:
            reason = "unexpected success; request was accepted instead of denied"
        elif re.search(rejection, result.stderr):
            reason = None
        elif re.search(r"ValidatingAdmissionPolicy [\"'].* denied request:", result.stderr):
            reason = "named-policy/message mismatch; denial did not match the exact expected policy, binding, and message"
        else:
            reason = "API/schema rejection; this is not evidence of the expected admission-policy denial"
        if reason:
            def bounded(value, limit=512):
                value = value.replace("\r", "\\r").replace("\n", "\\n")
                if len(value) > limit:
                    return value[:limit] + "...[truncated " + str(len(value) - limit) + " chars]"
                return value

            fail(description + ": " + reason + "; kubectl exit status=" + str(result.returncode)
                 + "; stdout=" + bounded(result.stdout) + "; stderr=" + bounded(result.stderr))
    print("PASS " + description, flush=True)


security = {"allowPrivilegeEscalation": False, "capabilities": {"drop": ["ALL"]}}
mount_path = "/var/run/secrets/firemud/pod-identity"
run_id = uuid.uuid4().hex[:12]
for app in ("account-service", "game-session-service"):
    pod = {"apiVersion": "v1", "kind": "Pod", "metadata": {
        "name": app + "-admission-proof-" + run_id, "namespace": args.namespace}, "spec": {
        "automountServiceAccountToken": False, "restartPolicy": "Never",
        "securityContext": {"runAsNonRoot": True, "runAsUser": 1000, "seccompProfile": {"type": "RuntimeDefault"}},
        "containers": [{"name": app, "image": "registry.k8s.io/pause:3.10", "securityContext": copy.deepcopy(security),
                        "volumeMounts": [{"name": "pod-identity", "mountPath": mount_path, "readOnly": True}]}],
        "volumes": [{"name": "pod-identity", "downwardAPI": {"defaultMode": 292, "items": [
            {"path": "uid", "fieldRef": {"apiVersion": "v1", "fieldPath": "metadata.uid"}}]}}]}}
    probe(app + " valid CREATE", pod)
    host_message = "hosted runtime Pods must not use host namespaces, hostPath, or sysctl overrides"
    container_message = "every hosted runtime application/init container must drop all capabilities and exclude host ports and privilege escalation"
    injection_message = "Account and Game Session require exactly their application container and no init/ephemeral containers or injected annotations"
    confinement_message = "protected Pod identity consumers require nonroot RuntimeDefault confinement without block devices"
    volume_message = "protected Pod UID must come only from the canonical kubelet downwardAPI metadata.uid volume"
    mount_message = "protected Pod UID mount must be read-only at the canonical path without subpaths, shadow mounts, or mount propagation"

    def deny(label, mutate, policy, message):
        candidate = copy.deepcopy(pod)
        mutate(candidate)
        probe(app + " CREATE " + label, candidate, policy, message)

    for field in ("hostNetwork", "hostPID", "hostIPC"):
        deny(field, lambda p, field=field: p["spec"].update({field: True}), boundary, host_message)
    deny("hostPath", lambda p: p["spec"]["volumes"].append({"name": "host", "hostPath": {"path": "/tmp"}}), boundary, host_message)
    deny("sysctls", lambda p: p["spec"]["securityContext"].update({"sysctls": [{"name": "net.ipv4.ip_local_port_range", "value": "1024 65535"}]}), boundary, host_message)
    for label, change in (("privileged", {"privileged": True, "allowPrivilegeEscalation": True}),
                          ("escalation", {"allowPrivilegeEscalation": True}),
                          ("NET_ADMIN", {"capabilities": {"drop": ["ALL"], "add": ["NET_ADMIN"]}}),
                          ("missing-drop", {"capabilities": {"drop": []}})):
        deny(label, lambda p, change=change: p["spec"]["containers"][0]["securityContext"].update(change), boundary, container_message)
    def unmasked_proc(p):
        # Kubernetes v1.35 requires Unmasked procMount to use a Pod user namespace.
        p["spec"]["hostUsers"] = False
        p["spec"]["containers"][0]["securityContext"]["procMount"] = "Unmasked"
    deny("procMount", unmasked_proc, boundary, container_message)
    deny("hostPort", lambda p: p["spec"]["containers"][0].update({"ports": [{"containerPort": 8080, "hostPort": 8080}]}), boundary, container_message)
    deny("sidecar", lambda p: p["spec"]["containers"].append({"name": "proxy", "image": "registry.k8s.io/pause:3.10", "securityContext": security}), identity, injection_message)
    deny("init", lambda p: p["spec"].update({"initContainers": [{"name": "proxy", "image": "registry.k8s.io/pause:3.10", "securityContext": security}]}), identity, injection_message)
    deny("annotations", lambda p: p["metadata"].update({"annotations": {"proof.firemud.dev/inject": "true"}}), identity, injection_message)
    deny("root", lambda p: p["spec"]["securityContext"].update({"runAsUser": 0}), identity, confinement_message)
    deny("seccomp", lambda p: p["spec"]["securityContext"].update({"seccompProfile": {"type": "Unconfined"}}), identity, confinement_message)
    deny("UID-source", lambda p: p["spec"]["volumes"][0]["downwardAPI"]["items"][0]["fieldRef"].update({"fieldPath": "metadata.name"}), identity, volume_message)
    deny("UID-mode", lambda p: p["spec"]["volumes"][0]["downwardAPI"].update({"defaultMode": 420}), identity, volume_message)
    for label, change in (("writable", {"readOnly": False}), ("subPath", {"subPath": "uid"}),
                          ("subPathExpr", {"subPathExpr": "uid"}), ("propagation", {"mountPropagation": "HostToContainer"}),
                          ("noncanonical-path", {"mountPath": mount_path + "-alternate"})):
        deny(label, lambda p, change=change: p["spec"]["containers"][0]["volumeMounts"][0].update(change), identity, mount_message)
    for shadow_path in ("/var/run/secrets/firemud", mount_path + "/uid"):
        def shadow(p, shadow_path=shadow_path):
            p["spec"]["volumes"].append({"name": "shadow", "emptyDir": {}})
            p["spec"]["containers"][0]["volumeMounts"].append({"name": "shadow", "mountPath": shadow_path})
        deny("shadow-" + shadow_path, shadow, identity, mount_message)

existing = get("pod", args.pod, True)
metadata = existing.get("metadata", {})
containers = existing.get("spec", {}).get("containers", [])
if (existing.get("apiVersion") != "v1" or existing.get("kind") != "Pod"
        or metadata.get("name") != args.pod or metadata.get("namespace") != args.namespace
        or not metadata.get("uid") or not metadata.get("resourceVersion")
        or metadata.get("deletionTimestamp") is not None or len(containers) != 1
        or containers[0].get("name") not in ("account-service", "game-session-service")):
    fail("--pod does not identify one current protected Pod with exact UID/resourceVersion")
existing.pop("status", None)
metadata.pop("managedFields", None)
pod_path = base_path + "/" + args.pod
probe("existing Pod valid UPDATE", existing, path=pod_path)
changed = copy.deepcopy(existing)
changed["metadata"].setdefault("annotations", {})["proof.firemud.dev/inject"] = "true"
probe("existing Pod annotation UPDATE", changed, identity, injection_message, pod_path)
probe("existing Pod valid ephemeralcontainers control", existing, path=pod_path + "/ephemeralcontainers")
changed = copy.deepcopy(existing)
changed["spec"].setdefault("ephemeralContainers", []).append({
    "name": "proof-" + run_id, "image": "registry.k8s.io/pause:3.10", "securityContext": security})
probe("existing Pod ephemeral injection", changed, identity, injection_message, pod_path + "/ephemeralcontainers")
changed["spec"]["ephemeralContainers"][-1]["securityContext"] = {
    "allowPrivilegeEscalation": True, "capabilities": {"drop": ["ALL"], "add": ["NET_ADMIN"]}}
probe("existing Pod ephemeral privilege escape", changed, boundary,
      "hosted runtime ephemeral containers must satisfy the same host and capability restrictions", pod_path + "/ephemeralcontainers")
current = get("pod", args.pod, True).get("metadata", {})
if any(current.get(key) != metadata[key] for key in ("uid", "resourceVersion")) or current.get("deletionTimestamp") is not None:
    fail("existing Pod changed during proof; rerun against a stable explicit target")
if verify_policies() != policy_versions:
    fail("installed policy/binding changed during proof")
print("PASS native admission only; UPDATE/ephemeral evidence covers the explicitly selected Pod.")
print("UNPROVED: CNI/socket routing, kubelet UID-to-process correspondence, replacement races, shared-key closure, and promotion readiness.")
PY
