#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 - "$ROOT_DIR" <<'PY'
import json, os, pathlib, subprocess, sys, tempfile
import yaml
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
wait = proof[proof.index('policy_deadline='):proof.index('elapsed fixture_rollout')]
wait = wait[:wait.index('phase_started=')]
# Execute the real wait body against bounded fake API/time, never a cluster.
with tempfile.TemporaryDirectory(prefix="firemud-policy-wait-") as directory:
    tmp = pathlib.Path(directory)
    def command(name, source):
        file = tmp / name
        file.write_text("#!/usr/bin/env python3\n" + source)
        file.chmod(0o700)
    command("date", 'import os,pathlib\np=pathlib.Path(os.environ["CLOCK"]);n=int(p.read_text()) if p.exists() else 0;p.write_text(str(n+30));print(n)\n')
    command("sleep", 'pass\n')
    command("kubectl", '''import os,pathlib,json,sys
p=pathlib.Path(os.environ["CALLS"]);n=int(p.read_text()) if p.exists() else 0;p.write_text(str(n+1))
if os.environ["CASE"]=="api-error":sys.exit(1)
ready=os.environ["CASE"]=="ready" or (os.environ["CASE"]=="late" and n>0)
print(json.dumps({"metadata":{"generation":2},"spec":{"failurePolicy":"Fail"},"status":{"observedGeneration":2 if ready else 1,"typeChecking":{"expressionWarnings":["bad"] if os.environ["CASE"]=="warnings" else []}}}))
''')
    for case, success in [("ready", True), ("late", True), ("stale", False), ("warnings", False), ("api-error", False)]:
        for name in ("clock", "calls"):
            (tmp/name).unlink(missing_ok=True)
        env = dict(os.environ, PATH=str(tmp)+":"+os.environ["PATH"], CASE=case,
                   CLOCK=str(tmp/"clock"), CALLS=str(tmp/"calls"),
                   KUBECONFIG="/not-a-live-config", expected_context="fixture")
        result = subprocess.run(["bash", "-c", "set -euo pipefail\nrun_wait() {\n"+wait+"\n}\nrun_wait"], env=env, capture_output=True, text=True, timeout=10)
        assert (result.returncode == 0) == success, (case, result.stderr)
        if success:
            assert int((tmp/"calls").read_text()) >= 4
        else:
            assert "policy_readiness=timeout" in result.stderr
print("Protected Pod workflow bounded cleanup/readiness contract passed")
PY
