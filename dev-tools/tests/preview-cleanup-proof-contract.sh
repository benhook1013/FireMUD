#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/preview-cleanup-proof.yml"
ci_workflow="$repo_root/.github/workflows/ci.yml"

python3 - "$repo_root" "$workflow" "$ci_workflow" <<'PY'
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

repo_root, workflow_path, ci_path = map(Path, sys.argv[1:])
workflow = workflow_path.read_text(encoding="utf-8")
ci_workflow = ci_path.read_text(encoding="utf-8")
trigger = workflow.split("permissions:", 1)[0]
assert trigger.count("workflow_dispatch:") == 1
for event in ("schedule:", "repository_dispatch:", "pull_request:", "pull_request_target:", "workflow_run:", "push:"):
    assert event not in trigger
for required in (
    "pr_number:",
    "expected_head_sha:",
    "expected_namespace_uid:",
    "github.ref == 'refs/heads/develop'",
    "environment: trusted-hosted-cluster",
    "self-hosted",
    "group: preview-allocation-lifecycle",
    "pull-requests: read",
    "uses: ./.github/actions/setup-python",
    "secrets.TRUSTED_HOSTED_PREVIEW_NAMESPACE_MANAGER_KUBECONFIG",
    "kubectl auth whoami -o json",
    "kubectl auth can-i get namespaces",
    "kubectl auth can-i delete namespaces",
    'kubectl auth can-i get secrets -n "$proof_namespace"',
    '[[ "$username" == system:serviceaccount:firemud-system:firemud-preview-namespace-manager ]]',
    '[[ "$head_sha" == "$EXPECTED_HEAD_SHA" ]]',
    'annotations["firemud.dev/cleanup-proof"] == "true"',
    '[[ "$observed_uid" == "$EXPECTED_NAMESPACE_UID" ]]',
    '--delete-runtime "$namespace" "$EXPECTED_NAMESPACE_UID"',
):
    assert required in workflow, f"workflow is missing safety requirement: {required}"
assert workflow.count("secrets.") == 1
assert "TRUSTED_HOSTED_IDENTITY_REQUESTER_KUBECONFIG" not in workflow
assert "trusted-preview-ca-recovery" not in workflow
assert ci_workflow.count("bash ./dev-tools/tests/preview-cleanup-proof-contract.sh") == 1


def run_block(step_name):
    marker = f"      - name: {step_name}\n"
    start = workflow.index(marker)
    body_start = workflow.index("        run: |\n", start) + len("        run: |\n")
    lines = []
    for line in workflow[body_start:].splitlines():
        if line.startswith("      - name:"):
            break
        if line:
            assert line.startswith("          "), (step_name, line)
            line = line[10:]
        lines.append(line)
    return "\n".join(lines) + "\n"


runner_check = run_block("Verify runner API access and namespace-delete RBAC")
delete_proof = run_block("Revalidate and delete the exact proof namespace")
kubectl_mock = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
state = Path(os.environ["MOCK_STATE_DIR"])
with (state / "calls").open("a") as stream:
    stream.write(json.dumps(args) + "\n")
env = lambda key, default="": os.environ.get(key, default)
if args[:2] == ["auth", "whoami"]:
    print(json.dumps({"status":{"userInfo":{"username":env("MOCK_USERNAME")}}}))
elif args[:2] == ["auth", "can-i"]:
    p = args[2:]
    if p == ["get","namespaces"]: print(env("MOCK_GET_NAMESPACES","yes"))
    elif p == ["delete","namespaces"]: print(env("MOCK_DELETE_NAMESPACES","yes"))
    elif p[:2] == ["get","secrets"] and "-n" in p: print(env("MOCK_GET_SECRETS","no"))
    else: raise SystemExit("unexpected can-i " + repr(p))
elif args[:2] == ["get","namespace"]:
    name = args[2]
    if name == "kube-system": print("namespace/kube-system")
    elif name == "pr-42":
        if (state / "deleted").exists(): print("")
        else:
            count_path = state / "lookups"
            count = int(count_path.read_text() if count_path.exists() else "0") + 1
            count_path.write_text(str(count))
            if count >= 2 and env("MOCK_HELPER_NAMESPACE_ABSENT") == "yes":
                print("")
                raise SystemExit(0)
            uid = env("MOCK_NAMESPACE_UID","uid-original")
            if count >= 2: uid = env("MOCK_HELPER_NAMESPACE_UID",uid)
            obj = {"metadata":{"name":name,"uid":uid,
                "labels":{"firemud.dev/preview":env("MOCK_PREVIEW_LABEL","true"),
                          "firemud.dev/pr-number":env("MOCK_PR_LABEL","42")},
                "annotations":{"firemud.dev/cleanup-proof":env("MOCK_PROOF_MARKER","true")}}}
            if "-o" in args and args[args.index("-o")+1] == "json": print(json.dumps(obj))
            else: print("namespace/" + name)
    else: raise SystemExit("unexpected namespace " + repr(args))
elif args[:2] == ["version","--output=json"]:
    print(json.dumps({"clientVersion":{"gitVersion":"v1.31.0"},"serverVersion":{"gitVersion":"v1.31.1"}}))
elif args[:2] == ["delete","--raw"]:
    payload = json.load(sys.stdin)
    (state / "delete-options").write_text(json.dumps(payload))
    if env("MOCK_DELETE_FAIL_GONE") == "yes":
        (state / "deleted").write_text("yes")
        raise SystemExit(1)
    if payload.get("preconditions",{}).get("uid") != env("MOCK_NAMESPACE_UID","uid-original"):
        raise SystemExit("unexpected DELETE UID precondition")
    (state / "deleted").write_text("yes")
elif args[:2] == ["wait","--for=delete"]:
    pass
else: raise SystemExit("unexpected kubectl " + repr(args))
'''
gh_mock = r'''#!/usr/bin/env python3
import os, sys
if sys.argv[1:2] != ["api"]: raise SystemExit("unexpected gh call")
print("\t".join([os.environ.get("MOCK_PR_STATE","closed"),
 os.environ.get("MOCK_HEAD_REPOSITORY","benhook1013/FireMUD"),
 os.environ.get("MOCK_BASE_REPOSITORY","benhook1013/FireMUD"),
 os.environ.get("MOCK_HEAD_SHA","a"*40)]))
'''
identity = "system:serviceaccount:firemud-system:firemud-preview-namespace-manager"

def invoke(block, bindir, state, overrides):
    env = os.environ.copy()
    env.update({
        "PATH": f"{bindir}:{env['PATH']}", "MOCK_STATE_DIR":str(state),
        "MOCK_USERNAME":identity, "MOCK_NAMESPACE_UID":"uid-original",
        "MOCK_HELPER_NAMESPACE_UID":"uid-original", "GITHUB_REPOSITORY":"benhook1013/FireMUD",
        "GITHUB_REF":"refs/heads/develop", "GH_TOKEN":"mock",
        "PR_NUMBER":"42", "EXPECTED_HEAD_SHA":"a"*40, "EXPECTED_NAMESPACE_UID":"uid-original",
        "KUBECONFIG":"/tmp/mock",
    })
    env.update(overrides)
    return subprocess.run(["bash","-c",block],cwd=repo_root,env=env,text=True,
                          stdout=subprocess.PIPE,stderr=subprocess.PIPE)

with tempfile.TemporaryDirectory(prefix="preview-cleanup-proof-contract-") as tmp:
    root = Path(tmp)
    bindir = root / "bin"
    bindir.mkdir()
    for name, script in (("kubectl",kubectl_mock),("gh",gh_mock)):
        path=bindir/name
        path.write_text(script)
        path.chmod(0o755)

    def scenario(name, runner=None, delete=None, should_delete=False):
        state=root/name
        state.mkdir()
        if runner is not None:
            result=invoke(runner_check,bindir,state,runner)
            if result.returncode: return result,state
        result=invoke(delete_proof,bindir,state,delete or {})
        assert (state/"delete-options").exists() == should_delete, (name,result.stdout,result.stderr)
        return result,state

    result,_=scenario("wrong-identity",runner={"MOCK_USERNAME":"system:admin"})
    assert result.returncode != 0
    result,_=scenario("secret-read-allowed",runner={"MOCK_GET_SECRETS":"yes"})
    assert result.returncode != 0
    result,_=scenario("open-pr",delete={"MOCK_PR_STATE":"open"})
    assert result.returncode != 0, "an open PR was accepted"
    result,_=scenario("foreign-head-repository",delete={"MOCK_HEAD_REPOSITORY":"attacker/FireMUD"})
    assert result.returncode != 0, "a foreign PR head repository was accepted"
    result,_=scenario("foreign-base-repository",delete={"MOCK_BASE_REPOSITORY":"attacker/FireMUD"})
    assert result.returncode != 0, "a foreign PR base repository was accepted"
    result,_=scenario("wrong-head",delete={"MOCK_HEAD_SHA":"b"*40})
    assert result.returncode != 0
    result,_=scenario("missing-marker",delete={"MOCK_PROOF_MARKER":"false"})
    assert result.returncode != 0
    result,_=scenario("uid-mismatch",delete={"EXPECTED_NAMESPACE_UID":"uid-other"})
    assert result.returncode != 0
    result,_=scenario("replacement-race",delete={"MOCK_HELPER_NAMESPACE_UID":"uid-recreated"})
    assert result.returncode != 0
    result,_=scenario("disappeared-before-helper-get",delete={"MOCK_HELPER_NAMESPACE_ABSENT":"yes"})
    assert result.returncode != 0, "a proof fixture absent before the helper GET was treated as deleted by this proof"
    result,_=scenario("failed-delete-then-absent",delete={"MOCK_DELETE_FAIL_GONE":"yes"},should_delete=True)
    assert result.returncode != 0, "a failed API DELETE followed by NotFound was treated as successful proof"
    result,state=scenario("positive",runner={"MOCK_GET_SECRETS":"no"},should_delete=True)
    assert result.returncode == 0, (result.stdout,result.stderr)
    options=json.loads((state/"delete-options").read_text())
    assert options["preconditions"]["uid"] == "uid-original"
    assert (state/"deleted").read_text() == "yes"

print("Isolated preview cleanup proof workflow contract passed.")
PY
