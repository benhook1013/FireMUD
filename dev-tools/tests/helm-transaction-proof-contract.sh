#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 - "$ROOT_DIR" <<'PY'
import pathlib
import yaml

root = pathlib.Path(__import__("sys").argv[1])
workflow = yaml.safe_load((root / ".github/workflows/ci.yml").read_text())
job = workflow.get("jobs", {}).get("helm-transaction-proof")
if not isinstance(job, dict) or job.get("name") != "Helm Transaction Proof":
    raise SystemExit("CI must define the disposable Helm transaction proof job")
if "changes" not in job.get("needs", []):
    raise SystemExit("Helm transaction proof must use CI change detection")
job_text = "\n".join(
    str(step.get("run", "")) for step in job.get("steps", []) if isinstance(step, dict)
)
if "rancher/k3s:v1.34.5-k3s1@sha256:998f4db28a13143ada759690b554c5d8c1814ac03f77c1bdd78bbd73875a1379" not in job_text:
    raise SystemExit("Helm transaction proof must use the pinned k3s v1.34.5 server image")
if "setup-kubectl" not in "\n".join(str(step) for step in job.get("steps", [])):
    raise SystemExit("Helm transaction proof must use canonical kubectl setup")
if "setup-helm" not in "\n".join(str(step) for step in job.get("steps", [])):
    raise SystemExit("Helm transaction proof must use canonical Helm setup")
if "helm-transaction-proof.sh" not in job_text:
    raise SystemExit("Helm transaction proof job must invoke the canonical proof script")
if "kubectl get nodes --no-headers" not in job_text or 'awk \'$2 ~ /^Ready/ { found=1 } END { exit found ? 0 : 1 }\'' not in job_text:
    raise SystemExit("Helm transaction proof must wait on the disposable server Ready condition")
script = (root / "dev-tools/validation/helm-transaction-proof.sh").read_text()
for command in ("helm install", "helm upgrade", "helm history", "helm rollback", "helm status"):
    if command not in script:
        raise SystemExit(f"Helm proof script must exercise {command}")
print("Helm transaction proof contract passed")
PY
