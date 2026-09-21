#!/usr/bin/env bash
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

python3 - <<'PY'
import base64
import importlib.util
import json
from pathlib import Path

import yaml

source = Path("dev-tools/hosted/trust-bootstrap/recover-firemud-ca.py")
source_text = source.read_text()
assert "if sys.stdout.isatty():" in source_text, "pack must refuse terminal disclosure"
assert "Signature Algorithm: sha256WithRSAEncryption" in source_text
assert "730 * 24 * 60 * 60" in source_text
spec = importlib.util.spec_from_file_location("firemud_ca_recovery", source)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

assert module.BOUNDARY_POLICIES == (
    "firemud-trust-bootstrap-certificaterequest",
    "firemud-trust-bootstrap-certificaterequest-subresources",
    "firemud-trust-bootstrap-certificate",
    "firemud-trust-bootstrap-certificate-status",
    "firemud-trust-bootstrap-ca-issuers",
    "firemud-trust-ca-secret-boundary",
    "firemud-trust-runtime-namespace-boundary",
    "firemud-trust-runtime-binding-boundary",
)

certificate = b"public-test-certificate"
private_key = b"synthetic-test-key-not-a-real-ca"
fingerprint = "a" * 64
bundle = json.dumps({
    "version": 1,
    "certificate": base64.b64encode(certificate).decode(),
    "privateKey": base64.b64encode(private_key).decode(),
    "fingerprintSha256": fingerprint,
})
assert module.decode_bundle(bundle) == (certificate, private_key, fingerprint)
for bad in ("", "{}", bundle.replace('"version": 1', '"version": 2'), bundle + "garbage"):
    try:
        module.decode_bundle(bad)
    except module.RecoveryError:
        pass
    else:
        raise AssertionError("malformed recovery bundle was accepted")

operator = module.desired_secret("firemud-system", certificate, private_key)
issuer = module.desired_secret("cert-manager", certificate, private_key)
assert operator["type"] == "Opaque"
assert issuer["type"] == "kubernetes.io/tls"
assert set(operator["data"]) == {"ca.crt", "ca.key"}
assert set(issuer["data"]) == {"tls.crt", "tls.key"}
assert operator["data"]["ca.crt"] == issuer["data"]["tls.crt"]
assert operator["data"]["ca.key"] == issuer["data"]["tls.key"]

applied = []
stored = {}
def fake_kubectl(*args, input_bytes=None):
    assert args[0] == "kubectl"
    if args[1:3] == ("config", "current-context"):
        return b"firemud-preview-ca-recovery\n"
    if args[1:3] == ("auth", "whoami"):
        return json.dumps({"status": {"userInfo": {"username": module.RECOVERY_USER}}}).encode()
    if args[1:3] == ("get", "validatingadmissionpolicy"):
        return json.dumps({"spec": {"failurePolicy": "Fail"}}).encode()
    if args[1:3] == ("get", "validatingadmissionpolicybinding"):
        return json.dumps({"spec": {"validationActions": ["Deny"], "policyName": args[3]}}).encode()
    if args[1:3] == ("-n", "kube-system"):
        return b""
    if args[1:3] == ("get", "clusterrolebinding"):
        return b""
    if args[1] == "apply":
        secret = json.loads(input_bytes)
        applied.append(secret["metadata"]["namespace"])
        stored[secret["metadata"]["namespace"]] = secret
        return b""
    if args[1] == "-n" and args[3:5] == ("get", "secret"):
        return json.dumps(stored[args[2]]).encode()
    raise AssertionError(f"unexpected kubectl call: {args}")

module.run = fake_kubectl
module.restore(certificate, private_key, "firemud-preview-ca-recovery", "cert-manager")
assert applied == ["firemud-system", "cert-manager"]


def assert_boundary_refusal(kind, response, expected):
    before = list(applied)

    def mutated_boundary(*args, input_bytes=None):
        if args[1:3] == ("get", kind) and args[3] == "firemud-trust-ca-secret-boundary":
            return response
        return fake_kubectl(*args, input_bytes=input_bytes)

    module.run = mutated_boundary
    try:
        module.restore(certificate, private_key, "firemud-preview-ca-recovery", "cert-manager")
    except module.RecoveryError as error:
        assert expected in str(error), str(error)
    else:
        raise AssertionError(f"recovery accepted mutated {kind} boundary")
    assert applied == before, f"mutated {kind} boundary triggered Secret apply"


assert_boundary_refusal(
    "validatingadmissionpolicy",
    b"{}",
    "admission policy firemud-trust-ca-secret-boundary is not fail-closed",
)
assert_boundary_refusal(
    "validatingadmissionpolicybinding",
    b"{}",
    "admission binding firemud-trust-ca-secret-boundary is not denying",
)
assert_boundary_refusal(
    "validatingadmissionpolicy",
    json.dumps({"spec": {"failurePolicy": "Ignore"}}).encode(),
    "admission policy firemud-trust-ca-secret-boundary is not fail-closed",
)
assert_boundary_refusal(
    "validatingadmissionpolicybinding",
    json.dumps({"spec": {"validationActions": ["Warn"]}}).encode(),
    "admission binding firemud-trust-ca-secret-boundary is not denying",
)

def surviving_legacy(*args, input_bytes=None):
    if args[1:3] == ("-n", "kube-system"):
        return b"serviceaccount/preview-deployer\n"
    return fake_kubectl(*args, input_bytes=input_bytes)
module.run = surviving_legacy
try:
    module.restore(certificate, private_key, "firemud-preview-ca-recovery", "cert-manager")
except module.RecoveryError as error:
    assert "legacy preview-deployer" in str(error)
else:
    raise AssertionError("recovery accepted a surviving legacy credential")
assert applied == ["firemud-system", "cert-manager"]

documents = list(yaml.safe_load_all(Path("k8s/trust-bootstrap/recovery-rbac.yaml").read_text()))
assert len(documents) == 9
for document in documents:
    if document["kind"] == "Role":
        for rule in document["rules"]:
            assert "delete" not in rule["verbs"]
            if "secrets" in rule["resources"] and "create" not in rule["verbs"]:
                assert rule["resourceNames"] == ["firemud-grpc-ca"]
    if document["kind"] == "ClusterRole":
        assert all("secrets" not in rule["resources"] for rule in document["rules"])

admission = list(yaml.safe_load_all(Path("k8s/trust-bootstrap/recovery-admission.yaml").read_text()))
assert len(admission) == 2
policy, binding = admission
assert policy["metadata"]["name"] == "firemud-trust-ca-secret-boundary"
assert policy["spec"]["failurePolicy"] == "Fail"
policy_text = str(policy["spec"])
assert "reserved-ca-secret-or-recovery-caller" in policy_text
assert "system:serviceaccount:firemud-system:firemud-preview-ca-recovery" in policy_text
assert policy_text.count("firemud-grpc-ca") >= 2
assert binding["spec"]["validationActions"] == ["Deny"]
assert binding["spec"]["policyName"] == policy["metadata"]["name"]

workflow = yaml.safe_load(Path(".github/workflows/preview-ca-recovery.yml").read_text())
job = workflow["jobs"]["recover-preview-ca"]
assert job["environment"] == "trusted-preview-ca-recovery"
assert "refs/heads/develop" in job["if"]
events = workflow.get("on", workflow.get(True, {}))
assert set(events) == {"workflow_dispatch"}
assert "FIREMUD_PREVIEW_CA_RECOVERY_BUNDLE" in str(job)
assert "TRUSTED_PREVIEW_CA_RECOVERY_KUBECONFIG" in str(job)
PY

printf 'preview CA recovery contract passed\n'
