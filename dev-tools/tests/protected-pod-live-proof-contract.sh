#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 - "$repo_root" <<'PY'
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

import yaml

root = Path(sys.argv[1])
tool = root / "dev-tools/hosted/trust-bootstrap/prove-protected-pod-boundary.sh"
# This fake API exercises the executable transport and outcomes; it is not a
# CEL evaluator or evidence that a live API server compiles/enforces the policy.
mock = r'''
#!/usr/bin/env python3
import copy
import json
import os
from pathlib import Path
import sys
import yaml

args = sys.argv[1:]
assert args[:4] == ['--context', 'proof-context', '--request-timeout=30s', 'get'] or args[:3] == ['--context', 'proof-context', '--request-timeout=30s']
args = args[3:]
mode = os.environ['PROOF_MODE']
namespace = os.environ.get('PROOF_NAMESPACE', 'dev')
if args[:2] == ['-n', namespace]:
    args = args[2:]
with open(os.environ['PROOF_LOG'], 'a') as stream:
    stream.write(json.dumps(args) + '\n')
boundary = 'firemud-trust-runtime-pod-boundary'
identity = 'firemud-trust-runtime-pod-identity'
if args[0] == 'get':
    resource, name = args[1:3]
    if resource == 'namespace':
        labels = {'firemud.dev/dev-demo': 'true', 'firemud.dev/environment-class': 'dev-demo-cluster'} if namespace == 'dev' else {'firemud.dev/preview': 'true', 'firemud.dev/pr-number': namespace[3:]}
        if mode == 'namespace-labels': labels = {}
        result = {'metadata': {'name': namespace, 'labels': labels}, 'status': {'phase': 'Active'}}
    elif resource == 'pod':
        result = json.loads(Path(os.environ['PROOF_POD']).read_text())
        if mode == 'wrong-pod': result['spec']['containers'][0]['name'] = 'other'
        if mode == 'missing-uid': result['metadata'].pop('uid')
        if mode == 'pod-drift':
            if Path(os.environ['PROOF_POD'] + '.seen').exists(): result['metadata']['resourceVersion'] = '99'
            Path(os.environ['PROOF_POD'] + '.seen').touch()
    else:
        documents = list(yaml.safe_load_all(Path(os.environ['PROOF_POLICY']).read_text()))
        result = next(x for x in documents if x and x['kind'] + '.admissionregistration.k8s.io' == resource and x['metadata']['name'] == name)
        result['metadata'].update(uid='policy-uid-' + name, resourceVersion='10', generation=1)
        if result['kind'] == 'ValidatingAdmissionPolicy':
            result['status'] = {'observedGeneration': 1, 'typeChecking': {'expressionWarnings': []}}
            if mode == 'warning': result['status']['typeChecking']['expressionWarnings'] = [{'warning': 'bad CEL'}]
            if mode == 'missing-typecheck': result['status'].pop('typeChecking')
            if mode == 'stale-generation': result['status']['observedGeneration'] = 0
            if mode == 'fail-open': result['spec']['failurePolicy'] = 'Ignore'
            if mode == 'spec-drift': result['spec']['matchConditions'] = []
        else:
            if mode == 'audit-only': result['spec']['validationActions'] = ['Audit']
        if mode == 'wrong-revision': result['metadata']['annotations']['firemud.dev/admission-revision'] = 'old'
        if mode == 'defaulted':
            match = result['spec'].setdefault('matchConstraints' if result['kind'] == 'ValidatingAdmissionPolicy' else 'matchResources', {})
            match.update(namespaceSelector={}, objectSelector={}, matchPolicy='Equivalent')
    print(json.dumps(result))
    sys.exit(0)

# Fail hard if the executor attempts anything outside dry-run native Pod POST/PUT.
assert args[0] in ('create', 'replace'), args
assert args[1] == '--raw' and args[3:] == ['-f', '-'], args
path = args[2]
assert path.startswith('/api/v1/namespaces/' + namespace + '/pods'), path
assert path.endswith('?dryRun=All') and path.count('?') == 1, path
pod = json.load(sys.stdin)
assert pod['kind'] == 'Pod' and pod['metadata']['namespace'] == namespace
if args[0] == 'replace':
    assert pod['metadata']['uid'] == 'existing-uid' and pod['metadata']['resourceVersion'] == '8'
    assert '/account-service-existing' in path
spec = pod['spec']
container = spec['containers'][0]
security = container['securityContext']
policy = None
message = None
if any(spec.get(k) for k in ('hostNetwork', 'hostPID', 'hostIPC')) or any('hostPath' in v for v in spec['volumes']) or spec['securityContext'].get('sysctls'):
    policy, message = boundary, 'hosted runtime Pods must not use host namespaces, hostPath, or sysctl overrides'
elif (security.get('privileged') or security.get('allowPrivilegeEscalation') or security.get('procMount', 'Default') != 'Default'
      or 'ALL' not in security['capabilities']['drop'] or security['capabilities'].get('add')
      or any(p.get('hostPort', 0) for p in container.get('ports', []))):
    policy, message = boundary, 'every hosted runtime application/init container must drop all capabilities and exclude host ports and privilege escalation'
elif any(c['securityContext'].get('allowPrivilegeEscalation') for c in spec.get('ephemeralContainers', [])):
    policy, message = boundary, 'hosted runtime ephemeral containers must satisfy the same host and capability restrictions'
elif len(spec['containers']) != 1 or spec.get('initContainers') or spec.get('ephemeralContainers') or pod['metadata'].get('annotations'):
    policy, message = identity, 'Account and Game Session require exactly their application container and no init/ephemeral containers or injected annotations'
elif spec['securityContext']['runAsUser'] <= 0 or spec['securityContext']['seccompProfile']['type'] != 'RuntimeDefault':
    policy, message = identity, 'protected Pod identity consumers require nonroot RuntimeDefault confinement without block devices'
elif spec['volumes'][0]['downwardAPI']['items'][0]['fieldRef']['fieldPath'] != 'metadata.uid' or spec['volumes'][0]['downwardAPI']['defaultMode'] != 292:
    policy, message = identity, 'protected Pod UID must come only from the canonical kubelet downwardAPI metadata.uid volume'
else:
    mount = container['volumeMounts'][0]
    if (len(container['volumeMounts']) != 1 or not mount['readOnly'] or mount['mountPath'] != '/var/run/secrets/firemud/pod-identity'
        or 'subPath' in mount or 'subPathExpr' in mount or mount.get('mountPropagation', 'None') != 'None'):
        policy, message = identity, 'protected Pod UID mount must be read-only at the canonical path without subpaths, shadow mounts, or mount propagation'
if policy:
    if mode == 'allow-escape': print(json.dumps(pod)); sys.exit(0)
    if mode == 'auth-error': print('Error from server (Forbidden): user cannot create pods', file=sys.stderr)
    elif mode == 'conflict': print('Error from server (Conflict): resource version stale', file=sys.stderr)
    elif mode == 'wrong-policy': print("ValidatingAdmissionPolicy 'other' with binding 'other' denied request: " + message, file=sys.stderr)
    elif mode == 'wrong-message': print("ValidatingAdmissionPolicy '" + policy + "' with binding '" + policy + "' denied request: different constraint", file=sys.stderr)
    else: print("Error from server (Forbidden): ValidatingAdmissionPolicy '" + policy + "' with binding '" + policy + "' denied request: " + message, file=sys.stderr)
    sys.exit(1)
if mode == 'valid-denied': print('unavailable', file=sys.stderr); sys.exit(1)
if mode == 'malformed-success': print('{}'); sys.exit(0)
print(json.dumps(pod))
'''

with tempfile.TemporaryDirectory(prefix="firemud-pod-proof-contract-") as directory:
    temporary = Path(directory)
    executable = temporary / "kubectl"
    executable.write_text(mock.lstrip(), encoding="utf-8")
    executable.chmod(0o700)
    pod_file = temporary / "pod.json"
    pod = {"apiVersion": "v1", "kind": "Pod", "metadata": {"name": "account-service-existing", "namespace": "dev", "uid": "existing-uid", "resourceVersion": "8"}, "spec": {
        "securityContext": {"runAsNonRoot": True, "runAsUser": 1000, "seccompProfile": {"type": "RuntimeDefault"}},
        "containers": [{"name": "account-service", "image": "registry.k8s.io/pause:3.10", "securityContext": {"allowPrivilegeEscalation": False, "capabilities": {"drop": ["ALL"]}},
                        "volumeMounts": [{"name": "pod-identity", "mountPath": "/var/run/secrets/firemud/pod-identity", "readOnly": True}]}],
        "volumes": [{"name": "pod-identity", "downwardAPI": {"defaultMode": 292, "items": [{"path": "uid", "fieldRef": {"apiVersion": "v1", "fieldPath": "metadata.uid"}}]}}]}}
    pod_file.write_text(json.dumps(pod), encoding="utf-8")
    environment = dict(os.environ, PATH=str(temporary) + os.pathsep + os.environ["PATH"],
                       PROOF_POLICY=str(root / "k8s/trust-bootstrap/deployment-admission.yaml"),
                       PROOF_POD=str(pod_file), PROOF_LOG=str(temporary / "calls"))
    arguments = ["--context", "proof-context", "--namespace", "dev", "--pod", "account-service-existing"]
    cases = ["normal", "defaulted", "namespace-labels", "warning", "missing-typecheck", "stale-generation", "fail-open", "spec-drift", "audit-only", "wrong-revision", "wrong-pod", "missing-uid", "pod-drift", "allow-escape", "auth-error", "conflict", "wrong-policy", "wrong-message", "valid-denied", "malformed-success"]
    for mode in cases:
        result = subprocess.run(["bash", str(tool)] + arguments, env=dict(environment, PROOF_MODE=mode), text=True, capture_output=True)
        assert (result.returncode == 0) == (mode in ("normal", "defaulted")), (mode, result.stdout, result.stderr)
        if mode == "normal":
            assert "UNPROVED: CNI/socket" in result.stdout
            assert "ephemeral privilege escape" in result.stdout
            assert "game-session-service CREATE shadow-" in result.stdout
        print("PASS mock-kubectl " + mode)
    pod["metadata"]["namespace"] = "pr-23"
    pod_file.write_text(json.dumps(pod), encoding="utf-8")
    preview_args = ["--context", "proof-context", "--namespace", "pr-23", "--pod", "account-service-existing"]
    result = subprocess.run(["bash", str(tool)] + preview_args, env=dict(environment, PROOF_MODE="normal", PROOF_NAMESPACE="pr-23"), text=True, capture_output=True)
    assert result.returncode == 0, result.stderr
    print("PASS mock-kubectl canonical pr-N")
    before = (temporary / "calls").read_text()
    for invalid in ([], ["--namespace", "dev", "--pod", "account-service-existing"],
                    ["--context", "", "--namespace", "dev", "--pod", "account-service-existing"],
                    ["--context", "proof-context", "--namespace", "pr-01", "--pod", "account-service-existing"],
                    ["--context", "proof-context", "--namespace", "production", "--pod", "account-service-existing"],
                    ["--context", "proof-context", "--namespace", "dev"],
                    ["--context", "proof-context", "--namespace", "dev", "--pod", "a/b"]):
        result = subprocess.run(["bash", str(tool)] + invalid, env=dict(environment, PROOF_MODE="normal"), text=True, capture_output=True)
        assert result.returncode != 0
    assert (temporary / "calls").read_text() == before, "invalid arguments reached kubectl"
    print("PASS argument rejection before API access")
print("PASS protected Pod live-proof behavioral contract (mock only; no live cluster commands)")
PY
