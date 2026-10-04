#!/usr/bin/env bash
# shellcheck disable=SC2016 # This contract intentionally asserts literal shell source.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
proof="$repo_root/dev-tools/hosted/trust-bootstrap/prove-issuance-boundary.sh"
verifier="$repo_root/dev-tools/hosted/trust-bootstrap/verify-live-admission-boundary.py"
readme="$repo_root/k8s/trust-bootstrap/README.md"

fail() {
  echo "trust-bootstrap live-proof contract: $1" >&2
  exit 1
}

[[ -f "$proof" ]] || fail 'proof helper is missing'
[[ -f "$verifier" ]] || fail 'live admission verifier is missing'
[[ -f "$readme" ]] || fail 'trust-bootstrap README is missing'
bash -n "$proof" || fail 'proof helper has invalid shell syntax'

python3 - "$readme" <<'PY'
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


readme = Path(sys.argv[1]).read_text(encoding="utf-8")
marker = "## Pre-CA handoff evidence (non-secret)"
if marker not in readme:
    raise SystemExit("README is missing the canonical pre-CA handoff evidence section")
handoff = readme.split(marker, 1)[1].split("## Recovery copy and rotation", 1)[0]
ordered_markers = [
    "reviewed repository root",
    "Python 3 and PyYAML",
    "trusted_context=",
    'kubectl config current-context',
    'readonly KUBECTL=(kubectl --context "$trusted_context")',
    'auth whoami -o jsonpath=',
    'grep -Fx system:masters',
    'verify-live-admission-boundary.py',
    '  --context "$trusted_context"',
    'admission_policies=(',
    'firemud-trust-bootstrap-certificaterequest',
    'firemud-trust-ca-secret-boundary',
    'firemud-trust-runtime-binding-boundary',
    'failure_policy="',
    'validation_actions="',
    'controller_resource="',
    'controller_mode="',
    'FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE',
    'rollout status deployment/firemud-hosted-identity-controller --timeout=480s',
    "'{.metadata.generation}'",
    "'{.status.observedGeneration}'",
    'controller_mode_after_rollout=',
    'controller_pods=',
    'app.kubernetes.io/name=hosted-environment-identity-controller,app.kubernetes.io/component=controller',
    'controllerActivation=',
    'clusterissuer firemud-ca-issuer',
    'secret firemud-grpc-ca',
    'serviceaccount preview-deployer',
    'clusterrolebinding preview-deployer',
    'pre-ca-handoff=pass',
]
positions = [handoff.find(marker) for marker in ordered_markers]
if any(position < 0 for position in positions) or positions != sorted(positions):
    raise SystemExit(
        "pre-CA handoff evidence is missing its context, identity, Fail/Deny, "
        "paused-controller, absence, or pass ordering"
    )

for forbidden in (
    "kubectl apply",
    "kubectl create",
    "kubectl delete",
    "kubectl patch",
    "gh secret set",
    "base64",
    ".data",
    "--from-file",
):
    if forbidden in handoff:
        raise SystemExit(f"pre-CA evidence must not contain mutating or secret-bearing operation: {forbidden}")

if re.search(r"-o json(?:\s|['\"]|$)", handoff):
    raise SystemExit("pre-CA evidence must not request Secret-bearing JSON output")

if '--ignore-not-found -o name' not in handoff:
    raise SystemExit("pre-CA evidence must use name-only absence readbacks")
if 'if ! resource_names=' not in handoff:
    raise SystemExit("pre-CA evidence must fail closed when an absence lookup errors")
if 'pre-CA handoff could not verify resource absence:' not in handoff:
    raise SystemExit("pre-CA evidence must report lookup failure separately from resource presence")
if 'if [[ -n "$resource_names" ]]; then' not in handoff:
    raise SystemExit("pre-CA evidence must check captured names only after a successful lookup")
if 'if kubectl get $resource --ignore-not-found -o name | grep -q .' in handoff:
    raise SystemExit("pre-CA evidence must not treat a failed lookup pipeline as absence")
if 'if "${KUBECTL[@]}" get $resource --ignore-not-found -o name | grep -q .' in handoff:
    raise SystemExit("pre-CA evidence must not treat a failed lookup pipeline as absence")
if "all eight" not in readme.split("## Required order", 1)[1].split(
    "## Pre-CA handoff evidence", 1
)[0]:
    raise SystemExit("required order must retain the all-eight admission-boundary obligation")

block_match = re.search(r"```bash\n(.*?)\n```", handoff, re.DOTALL)
if not block_match:
    raise SystemExit("pre-CA handoff evidence is missing its executable Bash block")
handoff_script = block_match.group(1)
mock_kubectl = r'''#!/usr/bin/env python3
import copy
import json
import os
import sys
from pathlib import Path

import yaml

raw_args = sys.argv[1:]
case = os.environ["MOCK_CASE"]
admission_case = os.environ["MOCK_ADMISSION_CASE"]
state = Path(os.environ["MOCK_STATE"])
joined = " ".join(raw_args)

def output(value=""):
    sys.stdout.write(value)

def canonical_objects():
    cache = Path(os.environ["MOCK_SPEC_CACHE"])
    if cache.exists():
        return json.loads(cache.read_text())
    root = Path(os.environ["MOCK_REPO_ROOT"])
    objects = {}
    for manifest in (
        "k8s/trust-bootstrap/issuance-admission.yaml",
        "k8s/trust-bootstrap/recovery-admission.yaml",
        "k8s/trust-bootstrap/deployment-admission.yaml",
    ):
        for document in yaml.safe_load_all((root / manifest).read_text()):
            key = document["kind"] + "/" + document["metadata"]["name"]
            objects[key] = document
    cache.write_text(json.dumps(objects))
    return objects

if raw_args[:2] == ["config", "current-context"]:
    output("other-context" if case == "wrong-context" else "trusted-context")
    raise SystemExit(0)
if raw_args[:2] != ["--context", "trusted-context"]:
    raise SystemExit("kubectl invocation was not pinned to the approved context")
args = raw_args[2:]
joined = " ".join(args)

if args[:2] == ["auth", "whoami"]:
    output("operator" if ".username}" in joined else
           ("developer" if case == "non-admin" else "system:masters") + chr(10))
elif "rollout" in args and "status" in args:
    if case == "rollout-failure":
        raise SystemExit(1)
elif args[:1] == ["get"] and len(args) > 2 and args[1] in (
    "validatingadmissionpolicy",
    "validatingadmissionpolicybinding",
):
    resource, name = args[1], args[2]
    kind = (
        "ValidatingAdmissionPolicy"
        if resource == "validatingadmissionpolicy"
        else "ValidatingAdmissionPolicyBinding"
    )
    output_index = args.index("-o") + 1 if "-o" in args else -1
    output_format = args[output_index] if output_index >= 0 else ""
    if output_format == "json":
        log = Path(os.environ["MOCK_JSON_READS"])
        log.write_text(log.read_text() + kind + "/" + name + chr(10)
                       if log.exists() else kind + "/" + name + chr(10))
        if admission_case == "api-error" and name == "firemud-trust-bootstrap-certificate":
            raise SystemExit(1)
        if admission_case == "missing-object" and name == "firemud-trust-bootstrap-certificate":
            raise SystemExit(1)
        if admission_case == "malformed-json" and name == "firemud-trust-bootstrap-certificate":
            output("{not-json")
            raise SystemExit(0)
        document = copy.deepcopy(canonical_objects()[kind + "/" + name])
        spec = document["spec"]
        if name == "firemud-trust-bootstrap-certificate":
            if admission_case == "missing-validation":
                spec["validations"].pop()
            elif admission_case == "true-validation":
                spec["validations"][0]["expression"] = "true"
            elif admission_case == "altered-condition":
                spec["matchConditions"][0]["expression"] = "true"
            elif admission_case == "unknown-field":
                spec["unreviewedField"] = True
        if name == "firemud-trust-runtime-namespace-boundary":
            if admission_case == "altered-scope":
                spec["matchConstraints"]["resourceRules"][0]["scope"] = "Namespaced"
            elif admission_case == "widened-policy-scope":
                spec["matchConstraints"]["resourceRules"][0]["resources"] = ["*"]
        if kind == "ValidatingAdmissionPolicyBinding":
            if admission_case == "empty-selectors":
                spec["matchResources"] = {
                    "namespaceSelector": {
                        "matchLabels": {},
                        "matchExpressions": [],
                    },
                    "objectSelector": {"matchExpressions": []},
                }
            elif admission_case == "narrowed-binding-selector" and name == "firemud-trust-bootstrap-certificate":
                spec["matchResources"] = {
                    "namespaceSelector": {"matchLabels": {"proof": "selected"}},
                }
            elif admission_case == "binding-object-selector" and name == "firemud-trust-bootstrap-certificate":
                spec["matchResources"] = {
                    "objectSelector": {"matchExpressions": [
                        {"key": "proof", "operator": "In", "values": ["selected"]}
                    ]},
                }
            elif admission_case == "widened-binding-selector" and name == "firemud-trust-bootstrap-certificate":
                spec["matchResources"] = {
                    "namespaceSelector": {"matchExpressions": [
                        {"key": "proof", "operator": "NotIn", "values": ["selected"]}
                    ]},
                }
            elif admission_case == "binding-paramref" and name == "firemud-trust-bootstrap-certificate":
                spec["paramRef"] = {
                    "name": "unapproved-admission-parameters",
                    "namespace": "firemud-system",
                }
            elif admission_case == "unknown-selector-field" and name == "firemud-trust-bootstrap-certificate":
                spec["matchResources"] = {
                    "namespaceSelector": {"matchLabels": {}, "futureField": "ignored"},
                }
        output(json.dumps(document))
    elif ".spec.failurePolicy}" in joined:
        output("Fail")
    elif ".spec.policyName}" in joined:
        output(name)
    elif ".spec.validationActions[*]}" in joined:
        output("Deny")
elif "get" in args and "deployment" in args:
    if "--ignore-not-found" in args:
        output(
            ""
            if case in ("absent", "absent-active-orphan", "absent-paused-orphan")
            else "deployment.apps/firemud-hosted-identity-controller"
        )
    elif "ACTIVATION_MODE" in joined:
        count = int(state.read_text()) if state.exists() else 0
        state.write_text(str(count + 1))
        output("active" if case == "marker-change" and count > 0 else "paused")
    elif ".metadata.generation}" in joined:
        output("5")
    elif ".status.observedGeneration}" in joined:
        output("" if case == "missing-observed" else "4" if case == "stale-observed" else "5")
    elif ".spec.replicas}" in joined:
        output("0" if case == "zero-desired-replicas" else "1")
    elif ".status.replicas}" in joined:
        output("0" if case == "zero-status-replicas" else "0" if case == "zero-desired-replicas" else "1")
    elif ".status.updatedReplicas}" in joined:
        output("0" if case in ("unconverged-replicas", "zero-status-replicas", "zero-desired-replicas") else "1")
    elif ".status.readyReplicas}" in joined:
        output("0" if case in ("zero-status-replicas", "zero-desired-replicas") else "1")
    elif ".status.availableReplicas}" in joined:
        output("0" if case in ("zero-status-replicas", "zero-desired-replicas") else "1")
elif "get" in args and "pods" in args:
    if case in ("paused-stale-active", "old-active-pods"):
        output("controller-old" + chr(9) + "active" + chr(10))
    elif case == "absent-active-orphan":
        output("controller-orphan" + chr(9) + "active" + chr(10))
    elif case == "absent-paused-orphan":
        output("controller-orphan" + chr(9) + "paused" + chr(10))
    elif case == "missing-pod-mode":
        output("controller-missing-mode" + chr(9) + chr(10))
    elif case in ("absent-matching-pods", "absent"):
        output("")
    else:
        output("controller-current" + chr(9) + "paused" + chr(10))
elif "get" in args:
    # The remaining named resource checks are absence-only readbacks.
    output("")
else:
    raise SystemExit("unexpected mocked kubectl invocation: " + joined)
'''

controller_cases = {
    "wrong-context": False,
    "non-admin": False,
    "paused-stale-active": False,
    "rollout-failure": False,
    "stale-observed": False,
    "missing-observed": False,
    "unconverged-replicas": False,
    "zero-desired-replicas": False,
    "zero-status-replicas": False,
    "old-active-pods": False,
    "missing-pod-mode": False,
    "absent-matching-pods": False,
    "absent": True,
    "absent-active-orphan": False,
    "absent-paused-orphan": False,
    "converged": True,
    "marker-change": False,
}
admission_cases = {
    "admission-missing-validation": False,
    "admission-true-validation": False,
    "admission-altered-scope": False,
    "admission-altered-condition": False,
    "admission-widened-policy-scope": False,
    "admission-narrowed-binding-selector": False,
    "admission-binding-object-selector": False,
    "admission-widened-binding-selector": False,
    "admission-binding-paramref": False,
    "admission-unknown-field": False,
    "admission-unknown-selector-field": False,
    "admission-missing-object": False,
    "admission-api-error": False,
    "admission-malformed-json": False,
    "admission-empty-selectors": True,
}
cases = {**controller_cases, **admission_cases}
with tempfile.TemporaryDirectory(prefix="trust-bootstrap-pause-proof-") as temporary:
    mock_path = Path(temporary) / "kubectl"
    mock_path.write_text(mock_kubectl, encoding="utf-8")
    mock_path.chmod(0o755)
    for case, expected_success in cases.items():
        environment = os.environ.copy()
        environment.update(
            PATH=f"{temporary}:{environment['PATH']}",
            MOCK_CASE=case,
            MOCK_ADMISSION_CASE=case.removeprefix("admission-")
            if case.startswith("admission-")
            else "canonical",
            MOCK_STATE=str(Path(temporary) / f"{case}.state"),
            MOCK_JSON_READS=str(Path(temporary) / f"{case}.json-reads"),
            MOCK_SPEC_CACHE=str(Path(temporary) / "canonical-admission-specs.json"),
            MOCK_REPO_ROOT=str(Path(sys.argv[1]).resolve().parents[2]),
            FIREMUD_HOSTED_IDENTITY_TRUSTED_CONTEXT="trusted-context",
        )
        result = subprocess.run(
            ["bash", "-euo", "pipefail", "-c", handoff_script],
            env=environment,
            cwd=Path(sys.argv[1]).resolve().parents[2],
            text=True,
            capture_output=True,
            check=False,
        )
        if (result.returncode == 0) != expected_success:
            raise SystemExit(
                f"mocked pre-CA controller case {case!r} returned "
                f"{result.returncode}, expected success={expected_success}: "
                f"{result.stderr}{result.stdout}"
            )
        if expected_success and "pre-ca-handoff=pass" not in result.stdout:
            raise SystemExit(f"mocked pre-CA controller case {case!r} did not pass the gate")
        if expected_success:
            if "admission-specs=pass objects=16 sha256=" not in result.stdout:
                raise SystemExit(f"mocked pre-CA case {case!r} did not prove all admission specs")
            if "request.userInfo" in result.stdout or "expression" in result.stdout:
                raise SystemExit(f"mocked pre-CA case {case!r} printed a raw admission spec")
            reads = Path(environment["MOCK_JSON_READS"]).read_text().splitlines()
            if len(reads) != 16 or len(set(reads)) != 16:
                raise SystemExit(f"mocked pre-CA case {case!r} did not read exactly 16 fixed admission objects")
        elif case.startswith("admission-") and case.endswith("api-error"):
            if "result=api-error" not in result.stderr:
                raise SystemExit("admission API failure was not reported fail-closed")
        elif case.startswith("admission-") and case.endswith("missing-object"):
            if "result=api-error" not in result.stderr:
                raise SystemExit("missing admission object was not reported fail-closed")
        elif case.startswith("admission-") and case.endswith("malformed-json"):
            if "result=invalid-json" not in result.stderr:
                raise SystemExit("malformed admission JSON was not reported fail-closed")

repo_root = Path(sys.argv[1]).resolve().parents[2]
canonical_manifests = (
    "k8s/trust-bootstrap/issuance-admission.yaml",
    "k8s/trust-bootstrap/recovery-admission.yaml",
    "k8s/trust-bootstrap/deployment-admission.yaml",
)
for source_error in ("malformed-source", "missing-source"):
    with tempfile.TemporaryDirectory(prefix="trust-bootstrap-source-proof-") as temporary:
        temporary_path = Path(temporary)
        isolated_root = temporary_path / "repo"
        for manifest in canonical_manifests:
            destination = isolated_root / manifest
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(repo_root / manifest, destination)
        isolated_helper = (
            isolated_root
            / "dev-tools/hosted/trust-bootstrap/verify-live-admission-boundary.py"
        )
        isolated_helper.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(
            repo_root
            / "dev-tools/hosted/trust-bootstrap/verify-live-admission-boundary.py",
            isolated_helper,
        )
        source_file = isolated_root / canonical_manifests[0]
        if source_error == "malformed-source":
            source_file.write_text("apiVersion: [malformed\n", encoding="utf-8")
        else:
            (isolated_root / canonical_manifests[1]).unlink()
        kubectl_stub = temporary_path / "kubectl"
        kubectl_stub.write_text("#!/bin/sh\nexit 99\n", encoding="utf-8")
        kubectl_stub.chmod(0o755)
        environment = os.environ.copy()
        environment["PATH"] = str(temporary_path)
        result = subprocess.run(
            [
                sys.executable,
                str(isolated_helper),
                "--context",
                "trusted-context",
            ],
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode == 0 or "result=source-error" not in result.stderr:
            raise SystemExit(
                f"mocked {source_error} case did not fail closed: "
                f"{result.stderr}{result.stdout}"
            )
PY

require() {
  local fragment=$1
  grep -Fq -- "$fragment" "$proof" || fail "proof helper is missing: $fragment"
}

require 'usage: prove-issuance-boundary.sh --context CONTEXT --namespace pr-N'
require 'readonly KUBECTL=(kubectl --context "$context")'
require 'system:masters'
require "legacy_identity='system:serviceaccount:kube-system:preview-deployer'"
require "readonly probe_namespace='firemud-system'"
require 'auth can-i'
require '--dry-run=server'
require 'local description=$1 identity=$2 manifest=$3 target_namespace=$4 expected_message=$5'
require 'grep -Fq -- "$expected_message" "$temporary_directory/probe-error"'
require 'firemud-proof-request-no-group'
require 'firemud-proof-request-no-kind'
require 'CA-backed Issuer alias'
require 'CA-backed ClusterIssuer alias'
require 'reserved firemud-system CA Secret mutation'
require 'canonical standalone Gateway Certificate'
require 'canonical standalone TCP Proxy Certificate'
require 'canonical standalone public Telnet Certificate'
require 'standalone writer arbitrary public Certificate'
require 'wrong Gateway SAN'
require 'wrong canonical name'
require 'wrong issuer kind'
require 'cert-manager ServiceAccount is missing'
require 'deployment cert-manager'
require 'DEFERRED later Ready/signing proof'
require 'trap cleanup EXIT'
require 'delete clusterrolebinding "$probe_binding"'
require 'delete clusterrole "$probe_clusterrole"'
require 'delete serviceaccount "$probe_serviceaccount"'
require 'get namespace "$probe_namespace"'
require 'protected namespace $probe_namespace is not present'
require 'namespace: $probe_namespace'
require 'system:serviceaccount:$probe_namespace:$probe_serviceaccount'
require 'resources_created=1'
require 'firemud-grpc-ca'
require 'standalone certificate writer cannot create Certificates'
require 'bind-runtime-roles.sh'

python3 - "$proof" <<'PY'
from __future__ import annotations

import re
import sys


source = open(sys.argv[1], encoding="utf-8").read()
runner = r'(?:\bkubectl\b|"?\$\{KUBECTL\[@\]\}"?)'
secret_create = re.compile(
    runner + r'[^\n]*(?:^|\s)create\s+[^\n]*\bsecrets?\b', re.MULTILINE
)
secret_get = re.compile(
    runner + r'[^\n]*(?:^|\s)get\s+[^\n]*\bsecrets?\b', re.MULTILINE
)
apply = re.compile(runner + r'[^\n]*\bapply\b[^\n]*', re.MULTILINE)
heredoc = re.compile(r'<<-?\s*[\'\"]?([A-Za-z_][A-Za-z0-9_]*)[\'\"]?')


def command_lines(text: str) -> list[tuple[int, str]]:
    lines = text.splitlines()
    commands = []
    index = 0
    while index < len(lines):
        end = index
        parts = [lines[index].rstrip()]
        while parts[-1].endswith(("\\", "|")) and end + 1 < len(lines):
            end += 1
            parts.append(lines[end].rstrip())
        commands.append((end, " ".join(parts)))
        index = end + 1
    return commands


def apply_contains_ca(text: str) -> bool:
    lines = text.splitlines()
    for index, line in command_lines(text):
        if line.lstrip().startswith("#"):
            continue
        if not apply.search(line):
            continue
        if "firemud-grpc-ca" in line:
            return True
        marker = heredoc.search(line)
        if marker:
            for payload in lines[index + 1 :]:
                if payload.strip() == marker.group(1):
                    break
                if "firemud-grpc-ca" in payload:
                    return True
    return False


def assert_forbidden_fixture(fixture: str, *, operation: str) -> None:
    if operation == "create":
        assert secret_create.search(fixture), fixture
    elif operation == "get":
        assert secret_get.search(fixture), fixture
    else:
        assert apply_contains_ca(fixture), fixture


assert 'readonly probe_namespace=\'firemud-system\'' in source
assert 'get namespace "$probe_namespace"' in source
assert 'delete serviceaccount "$probe_serviceaccount"' in source
assert 'namespace: $probe_namespace' in source
assert 'probe_identity="system:serviceaccount:$probe_namespace:$probe_serviceaccount"' in source
assert 'probe_identity="system:serviceaccount:$namespace:$probe_serviceaccount"' not in source


# Deterministic fixtures ensure the guard continues to cover direct kubectl,
# array-based kubectl, optional context/namespace flags, and CA material in an
# apply heredoc even when the production helper itself has no forbidden call.
assert_forbidden_fixture(
    "kubectl --context ctx --namespace firemud-system create secret generic firemud-grpc-ca",
    operation="create",
)
assert_forbidden_fixture(
    '"${KUBECTL[@]}" -n firemud-system get secrets firemud-grpc-ca',
    operation="get",
)
assert_forbidden_fixture(
    "kubectl --context ctx -n firemud-system apply -f - <<EOF\n"
    "metadata:\n  name: firemud-grpc-ca\nEOF\n",
    operation="apply",
)
assert_forbidden_fixture(
    'cat <<EOF | "${KUBECTL[@]}" --namespace firemud-system apply -f -\n'
    "metadata:\n  name: firemud-grpc-ca\nEOF\n",
    operation="apply",
)
assert_forbidden_fixture(
    'cat <<EOF | \\\n'
    '"${KUBECTL[@]}" --context ctx apply -f -\n'
    "metadata:\n  name: firemud-grpc-ca\nEOF\n",
    operation="apply",
)

if any(secret_create.search(line) for _, line in command_lines(source)):
    print('proof helper must not create a Secret', file=sys.stderr)
    raise SystemExit(1)
if any(secret_get.search(line) for _, line in command_lines(source)):
    print('proof helper must not read Secret data', file=sys.stderr)
    raise SystemExit(1)
if apply_contains_ca(source):
    print('proof helper must not apply CA material', file=sys.stderr)
    raise SystemExit(1)
PY

echo 'trust-bootstrap live-proof contract: PASS'
