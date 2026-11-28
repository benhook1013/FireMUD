#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 - "$ROOT_DIR" <<'PY'
import json, os, pathlib, subprocess, sys, tempfile
import yaml
if not __debug__:
    raise SystemExit("Protected Pod admission proof requires Python assertions")
root = pathlib.Path(sys.argv[1])
workflow = yaml.safe_load((root / ".github/workflows/protected-pod-admission-proof.yml").read_text())
steps = workflow["jobs"]["admission-only"]["steps"]
runs = [step.get("run", "") for step in steps]
lookups = [line for script in runs for line in script.splitlines() if "node_names=" in line]
assert len(lookups) == 2
assert all("timeout --signal=TERM --kill-after=5s 15s docker ps -a" in line for line in lookups)
inspects = [line for script in runs for line in script.splitlines() if 'owner_label="$(timeout' in line]
assert len(inspects) == 2
assert all('15s docker inspect' in line and '|| owner_label=""' in line for line in inspects)
proof = next(script for script in runs if "policy_deadline=" in script)
version_guard = proof[proof.index('expected_server_version='):proof.index("printf 'kubernetes_context=")]
for actual, expected in [("v1.34.1", 0), ("v1.34.10", 1), ("v1.34.1-unexpected", 1)]:
    result = subprocess.run(["bash", "-c", version_guard], capture_output=True, env={
        **os.environ, "KIND_NODE_IMAGE": "kindest/node:v1.34.1@sha256:pin", "server_version": actual,
    })
    assert result.returncode == expected, (actual, result.returncode, result.stderr)
wait = proof[proof.index('policy_deadline='):proof.index('elapsed fixture_rollout')]
wait = wait[:wait.index('phase_started=')]
# Execute the real wait body against bounded fake API/time, never a cluster.
with tempfile.TemporaryDirectory(prefix="firemud-policy-wait-") as directory:
    tmp = pathlib.Path(directory)
    def command(name, source):
        file = tmp / name
        file.write_text("#!/usr/bin/env python3\n" + source)
        file.chmod(0o700)
    command("date", 'import os,pathlib\np=pathlib.Path(os.environ["CLOCK"]);print(int(p.read_text()) if p.exists() else 0)\n')
    command("sleep", 'pass\n')
    command("kubectl", '''import os,pathlib,json,sys
assert "--request-timeout=5s" in sys.argv
policy = sys.argv[sys.argv.index("validatingadmissionpolicy") + 1]
p=pathlib.Path(os.environ["CALLS"])
calls=p.read_text().splitlines() if p.exists() else []
n=len(calls)
p.write_text("\\n".join([*calls, policy]) + "\\n")
if os.environ["CASE"]=="api-error":sys.exit(1)
case=os.environ["CASE"]
if case == "transient-error" and n == 0:sys.exit(1)
if case == "timely-ready":pathlib.Path(os.environ["CLOCK"]).write_text("89")
if case == "late-ready":pathlib.Path(os.environ["CLOCK"]).write_text("90")
if case == "shared-deadline" and n == 0 and policy == "firemud-trust-runtime-namespace-boundary":
    pathlib.Path(os.environ["CLOCK"]).write_text("89")
ready=(case in ("ready", "timely-ready", "transient-error", "late-ready", "warnings", "ignore")
       or (case=="shared-deadline" and n==0 and policy=="firemud-trust-runtime-namespace-boundary"))
print(json.dumps({"metadata":{"generation":2},"spec":{"failurePolicy":"Ignore" if os.environ["CASE"]=="ignore" else "Fail"},"status":{"observedGeneration":2 if ready else 1,"typeChecking":{"expressionWarnings":["bad"] if os.environ["CASE"]=="warnings" else []}}}))
''')
    for case, success in [("ready", True), ("timely-ready", True), ("transient-error", True),
                          ("late-ready", False),
                          ("expired-before-request", False), ("stale", False),
                          ("warnings", False), ("ignore", False), ("api-error", False),
                          ("shared-deadline", False)]:
        for name in ("clock", "calls", "date-calls"):
            (tmp/name).unlink(missing_ok=True)
        env = dict(os.environ, PATH=str(tmp)+":"+os.environ["PATH"], CASE=case,
                   CLOCK=str(tmp/"clock"), CALLS=str(tmp/"calls"),
                   DATE_CALLS=str(tmp/"date-calls"),
                   KUBECONFIG="/not-a-live-config", expected_context="fixture")
        (tmp/"calls").write_text("")
        date_command = tmp / "date"
        date_command.write_text('''#!/usr/bin/env python3
import os,pathlib
case=os.environ["CASE"]
p=pathlib.Path(os.environ["CLOCK"])
calls=pathlib.Path(os.environ["DATE_CALLS"])
if case in ("stale", "warnings", "ignore", "api-error"):
    n=int(calls.read_text()) if calls.exists() else 0
    calls.write_text(str(n+1))
    print("0" if n < 2 else "90")
else:
    print(p.read_text() if p.exists() else "0")
''')
        date_command.chmod(0o700)
        if case == "expired-before-request":
            date_command.write_text('#!/usr/bin/env python3\nimport os,pathlib\np=pathlib.Path(os.environ["CLOCK"]);n=int(p.read_text()) if p.exists() else 0\nif n == 0:p.write_text("90")\nprint(n)\n')
            date_command.chmod(0o700)
        elif case == "shared-deadline":
            date_command.write_text('''#!/usr/bin/env python3
import os,pathlib
clock=pathlib.Path(os.environ["CLOCK"])
calls=pathlib.Path(os.environ["DATE_CALLS"])
n=int(calls.read_text()) if calls.exists() else 0
calls.write_text(str(n+1))
now=int(clock.read_text()) if clock.exists() else 0
if n == 2:
    now=89
    clock.write_text("90")
print(now)
''')
            date_command.chmod(0o700)
        result = subprocess.run(["bash", "-c", "set -euo pipefail\nrun_wait() {\n"+wait+"\n}\nrun_wait"], env=env, capture_output=True, text=True, timeout=10)
        assert (result.returncode == 0) == success, (case, result.stderr)
        if success:
            calls = (tmp/"calls").read_text().splitlines()
            assert set(calls) == {
                "firemud-trust-runtime-namespace-boundary", "firemud-trust-runtime-binding-boundary",
                "firemud-trust-runtime-pod-boundary", "firemud-trust-runtime-pod-identity",
            }
            if case == "transient-error":
                assert calls[:2] == ["firemud-trust-runtime-namespace-boundary"] * 2
                assert len(calls) == 5
            else:
                assert len(calls) == 4
        else:
            assert "policy_readiness=timeout" in result.stderr
            if case in ("late-ready", "expired-before-request"):
                calls = (tmp/"calls").read_text().splitlines() if (tmp/"calls").exists() else []
                assert len(calls) == (1 if case == "late-ready" else 0)
                assert ("boundary=ready_response" in result.stderr) == (case == "late-ready")
            if case in ("stale", "warnings", "ignore", "api-error"):
                calls = (tmp/"calls").read_text().splitlines()
                assert len(calls) == 1, (case, calls)
            if case == "shared-deadline":
                first = "firemud-trust-runtime-namespace-boundary"
                second = "firemud-trust-runtime-binding-boundary"
                assert (tmp/"calls").read_text().splitlines() == [first]
                assert f"policy_readiness=timeout policy={second} boundary=before_request" in result.stderr

qualifier_start = proof.index('python3 - "$pinned_probe" "$qualified_probe"')
qualifier_end = proof.index("\nPY\n", qualifier_start) + len("\nPY\n")
with tempfile.TemporaryDirectory(prefix="firemud-policy-receipt-") as directory:
    pinned = pathlib.Path(directory) / "pinned.py"
    qualified = pathlib.Path(directory) / "qualified.py"
    pinned.write_text(
        'import re\n'
        'def accepted(result, policy, message, boundary, identity, rejection):\n'
        '    if policy:\n'
        '        if result.returncode == 0:\n'
        '            return False\n'
        '        elif re.search(rejection, result.stderr):\n'
        '            return True\n'
        '    return False\n'
    )
    subprocess.run(
        ["bash", "-c", proof[qualifier_start:qualifier_end]],
        env={**os.environ, "pinned_probe": str(pinned), "qualified_probe": str(qualified)},
        check=True,
    )
    namespace = {}
    exec(compile(qualified.read_text(), str(qualified), "exec"), namespace)
    from contextlib import redirect_stdout
    from io import StringIO
    from types import SimpleNamespace
    boundary = "firemud-trust-runtime-pod-boundary"
    identity = "firemud-trust-runtime-pod-identity"
    overlap = "hosted runtime ephemeral containers must satisfy the same host and capability restrictions"
    denial = (
        f"ValidatingAdmissionPolicy '{identity}' with binding '{identity}' denied request: "
        "Account and Game Session require exactly their application container and no init/ephemeral containers or injected annotations"
    )
    accepted = namespace["accepted"]
    overlap_result = SimpleNamespace(returncode=1, stderr=denial)
    overlap_output = StringIO()
    with redirect_stdout(overlap_output):
        assert accepted(overlap_result, boundary, overlap, boundary, identity, "primary")
    assert "UNPROVED: common Pod boundary independent enforcement" in overlap_output.getvalue()
    primary_output = StringIO()
    with redirect_stdout(primary_output):
        assert accepted(SimpleNamespace(returncode=1, stderr="primary"), boundary, overlap, boundary, identity, "primary")
    assert "UNPROVED:" not in primary_output.getvalue()
    assert not accepted(SimpleNamespace(returncode=0, stderr=denial), boundary, overlap, boundary, identity, "primary")
    assert not accepted(SimpleNamespace(returncode=1, stderr=denial), boundary, "other", boundary, identity, "primary")
    assert not accepted(SimpleNamespace(returncode=1, stderr=denial.replace("with binding '" + identity, "with binding 'unknown")), boundary, overlap, boundary, identity, "primary")
print("Protected Pod workflow bounded cleanup/readiness contract passed")
PY
