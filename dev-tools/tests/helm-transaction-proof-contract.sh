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
needs = job.get("needs", [])
if isinstance(needs, str):
    needs = [needs]
if not isinstance(needs, list) or "changes" not in needs:
    raise SystemExit("Helm transaction proof must use CI change detection")
job_text = "\n".join(
    str(step.get("run", "")) for step in job.get("steps", []) if isinstance(step, dict)
)
if "rancher/k3s:v1.34.5-k3s1@sha256:998f4db28a13143ada759690b554c5d8c1814ac03f77c1bdd78bbd73875a1379" not in job_text:
    raise SystemExit("Helm transaction proof must use the pinned k3s v1.34.5 server image")
step_uses = {
    step.get("uses")
    for step in job.get("steps", [])
    if isinstance(step, dict) and isinstance(step.get("uses"), str)
}
if "./.github/actions/setup-kubectl" not in step_uses:
    raise SystemExit("Helm transaction proof must use canonical kubectl setup")
if "./.github/actions/setup-helm" not in step_uses:
    raise SystemExit("Helm transaction proof must use canonical Helm setup")
if "helm-transaction-proof.sh" not in job_text:
    raise SystemExit("Helm transaction proof job must invoke the canonical proof script")
if "docker logs firemud-helm-proof 2>&1 | grep -F 'k3s is up and running' >/dev/null" not in job_text:
    raise SystemExit("Helm transaction proof must wait on the pinned k3s startup marker before API operations")
if "--disable=metrics-server" not in job_text:
    raise SystemExit("Helm transaction proof must disable the optional metrics server so optional aggregated API discovery cannot affect the proof")
script = (root / "dev-tools/validation/helm-transaction-proof.sh").read_text()
for command in ("helm install", "helm upgrade", "helm history", "helm rollback", "helm status"):
    if command not in script:
        raise SystemExit(f"Helm proof script must exercise {command}")
if "assert " in script:
    raise SystemExit("Helm proof history validation must not use Python assert")
for required_history_check_fragment in (
    "history = json.load(sys.stdin)",
    "if len(history) < 2:",
    'print(\"Helm history must contain at least two revisions\", file=sys.stderr)',
    "raise SystemExit(1)",
):
    if required_history_check_fragment not in script:
        raise SystemExit(
            f"Helm proof history validation must fail clearly without assertions: {required_history_check_fragment}"
        )
for required_cleanup_fragment in (
    "cleanup() {",
    'namespace_owned=false',
    'if [[ "$namespace_owned" == true ]]; then',
    'helm uninstall "$release" --namespace "$namespace" --wait --timeout 180s >/dev/null 2>&1 || true',
    'kubectl delete namespace "$namespace" --wait --request-timeout=180s --timeout=180s >/dev/null 2>&1 || true',
    "trap cleanup EXIT",
):
    if required_cleanup_fragment not in script:
        raise SystemExit(f"Helm proof script must define failure cleanup: {required_cleanup_fragment}")
strict_cleanup_sequence = (
    'helm uninstall "$release" --namespace "$namespace" --wait --timeout 180s\n'
    'kubectl delete namespace "$namespace" --wait --request-timeout=180s --timeout=180s\n'
    'rm -rf -- "$work_dir"\n'
    "trap - EXIT"
)
if strict_cleanup_sequence not in script:
    raise SystemExit("Helm proof script must keep normal cleanup strict before disarming its EXIT trap")
if 'temp_id="$(basename "$work_dir"' not in script or 'namespace="helm-transaction-proof-${temp_id}"' not in script:
    raise SystemExit("Helm proof namespace must be unique per temporary work directory")
if 'kubectl create namespace "$namespace" >/dev/null 2>&1' not in script:
    raise SystemExit("Helm proof must claim its unique namespace with exclusive kubectl create")
if 'kubectl create namespace "$namespace" --dry-run=client' in script or 'kubectl apply -f -' in script:
    raise SystemExit("Helm proof must not adopt an existing namespace through apply")
print("Helm transaction proof contract passed")
PY
