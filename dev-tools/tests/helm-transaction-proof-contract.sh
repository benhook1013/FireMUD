#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 - "$ROOT_DIR" <<'PY'
import copy
import pathlib
import shlex
import yaml

root = pathlib.Path(__import__("sys").argv[1])
workflow = yaml.safe_load((root / ".github/workflows/ci.yml").read_text())
job = workflow.get("jobs", {}).get("helm-transaction-proof")
if not isinstance(job, dict) or job.get("name") != "Helm Transaction Proof":
    raise SystemExit("CI must define the disposable Helm transaction proof job")
pinned_k3s_image = "rancher/k3s:v1.34.5-k3s1@sha256:998f4db28a13143ada759690b554c5d8c1814ac03f77c1bdd78bbd73875a1379"
if not isinstance(job.get("env"), dict) or job["env"].get("K3S_IMAGE") != pinned_k3s_image:
    raise SystemExit("Helm transaction proof must define the exact pinned k3s image once as K3S_IMAGE")
workflow_text = (root / ".github/workflows/ci.yml").read_text()
if workflow_text.count(pinned_k3s_image) != 1:
    raise SystemExit("Helm transaction proof must define the pinned k3s image exactly once")
needs = job.get("needs", [])
if isinstance(needs, str):
    needs = [needs]
if not isinstance(needs, list) or "changes" not in needs:
    raise SystemExit("Helm transaction proof must use CI change detection")
job_text = "\n".join(
    str(step.get("run", "")) for step in job.get("steps", []) if isinstance(step, dict)
)
for required_pull_fragment in (
    "for attempt in 1 2 3;",
    'if timeout 120s docker pull "$K3S_IMAGE"; then',
    'sleep $((attempt * 5))',
    'if [[ "$pull_succeeded" != true ]]; then',
    'echo "Unable to pull $K3S_IMAGE after three attempts" >&2',
):
    if required_pull_fragment not in job_text:
        raise SystemExit(
            "Helm transaction proof must pre-pull its pinned k3s image with bounded retries: "
            f"{required_pull_fragment}"
        )
def require_pinned_k3s_start(job_definition, expected_image):
    start_runs = [
        step.get("run", "")
        for step in job_definition.get("steps", [])
        if isinstance(step, dict) and step.get("name") == "Start pinned disposable k3s server"
    ]
    if len(start_runs) != 1 or not isinstance(start_runs[0], str):
        raise SystemExit("Helm transaction proof must define one pinned k3s start step")
    def shell_commands(script):
        logical_commands = []
        continued_lines = []
        for raw_line in script.splitlines():
            line = raw_line.rstrip()
            if not continued_lines and not line.strip():
                continue
            if line.endswith("\\"):
                continued_lines.append(line[:-1])
                continue
            continued_lines.append(line)
            logical_commands.append("\n".join(continued_lines))
            continued_lines = []
        if continued_lines:
            logical_commands.append("\n".join(continued_lines))

        commands = []
        for logical_command in logical_commands:
            lexer = shlex.shlex(logical_command, posix=True, punctuation_chars=";&|")
            lexer.whitespace_split = True
            lexer.commenters = "#"
            command = []
            for token in lexer:
                if token in {";", "&&", "||", "&"}:
                    if command:
                        commands.append(command)
                        command = []
                else:
                    command.append(token)
            if command:
                commands.append(command)
        return commands

    k3s_runs = [
        command
        for command in shell_commands(start_runs[0])
        if command[:2] == ["docker", "run"]
    ]
    if len(k3s_runs) != 1:
        raise SystemExit("Helm transaction proof must define exactly one executable k3s docker run")
    run_tokens = k3s_runs[0]
    if run_tokens.count("--pull=never") != 1:
        raise SystemExit(
            "Helm transaction proof must run the k3s image with exactly one --pull=never"
        )
    try:
        image_index = run_tokens.index("$K3S_IMAGE")
        server_indices = [index for index, token in enumerate(run_tokens) if token == "server"]
    except ValueError:
        image_index = -1
        server_indices = []
    if (
        run_tokens.count("$K3S_IMAGE") != 1
        or image_index < 0
        or len(server_indices) != 1
        or run_tokens.index("--pull=never") > image_index
        or server_indices[0] != image_index + 1
    ):
        raise SystemExit(
            "Helm transaction proof must run the exact K3S_IMAGE token immediately before server"
        )
    if job_definition.get("env", {}).get("K3S_IMAGE") != expected_image:
        raise SystemExit("Helm transaction proof must bind the executable image to pinned K3S_IMAGE")


require_pinned_k3s_start(job, pinned_k3s_image)
mutated_job = copy.deepcopy(job)
for step in mutated_job["steps"]:
    if isinstance(step, dict) and step.get("name") == "Start pinned disposable k3s server":
        step["run"] = (
            step["run"].replace('"$K3S_IMAGE"', "rancher/k3s:v1.34.5-k3s1")
            + f"\n# pinned image: {pinned_k3s_image}\necho \"$K3S_IMAGE\" server\n"
        )
        break
else:
    raise SystemExit("Helm transaction proof mutation fixture could not find the start step")
try:
    require_pinned_k3s_start(mutated_job, pinned_k3s_image)
except SystemExit:
    pass
else:
    raise SystemExit(
        "Helm transaction proof accepted a mutable executable k3s tag with the digest only in a comment"
    )
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
