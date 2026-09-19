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


def require_helm_job_timeout(job_definition):
    timeout_minutes = job_definition.get("timeout-minutes")
    if not isinstance(timeout_minutes, int) or timeout_minutes < 15:
        raise SystemExit(
            "Helm transaction proof job must allow at least 15 minutes for bounded disposable-cluster proof"
        )


require_helm_job_timeout(job)
timeout_fixture = copy.deepcopy(job)
timeout_fixture["timeout-minutes"] = 10
try:
    require_helm_job_timeout(timeout_fixture)
except SystemExit:
    pass
else:
    raise SystemExit("Helm transaction proof contract accepted a 10-minute job timeout")
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


canonical_proof_step_name = "Run Helm lifecycle proof"
canonical_proof_command = "bash ./dev-tools/validation/helm-transaction-proof.sh"


def require_canonical_proof_step(job_definition):
    proof_steps = [
        step
        for step in job_definition.get("steps", [])
        if isinstance(step, dict) and step.get("name") == canonical_proof_step_name
    ]
    if len(proof_steps) != 1:
        raise SystemExit(
            "Helm transaction proof must define exactly one named canonical proof step"
        )
    proof_step = proof_steps[0]
    if "continue-on-error" in proof_step:
        raise SystemExit("Helm transaction proof step must not define continue-on-error")
    if "if" in proof_step:
        raise SystemExit("Helm transaction proof step must not define a step-level condition")
    run = proof_step.get("run")
    if not isinstance(run, str):
        raise SystemExit("Helm transaction proof step must define an executable run")
    executable_lines = [
        line.strip()
        for line in run.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    if executable_lines != [canonical_proof_command]:
        raise SystemExit(
            "Helm transaction proof step must execute the canonical proof command directly"
        )


require_canonical_proof_step(job)
for fixture_name, fixture_run in (
    ("commented-only", f"# {canonical_proof_command}"),
    ("echo-only", f'echo "{canonical_proof_command}"'),
):
    mutated_job = copy.deepcopy(job)
    for step in mutated_job["steps"]:
        if isinstance(step, dict) and step.get("name") == canonical_proof_step_name:
            step["run"] = fixture_run
            break
    else:
        raise SystemExit(f"Helm transaction proof {fixture_name} fixture could not find the proof step")
    try:
        require_canonical_proof_step(mutated_job)
    except SystemExit:
        pass
    else:
        raise SystemExit(
            f"Helm transaction proof accepted a {fixture_name} proof command fixture"
        )

for fixture_name, step_update in (
    ("continue-on-error", {"continue-on-error": True}),
    ("expression continue-on-error", {"continue-on-error": "${{ github.event_name == 'push' }}"}),
    ("skip condition", {"if": "false"}),
):
    mutated_job = copy.deepcopy(job)
    for step in mutated_job["steps"]:
        if isinstance(step, dict) and step.get("name") == canonical_proof_step_name:
            step.update(step_update)
            break
    else:
        raise SystemExit(f"Helm transaction proof {fixture_name} fixture could not find the proof step")
    try:
        require_canonical_proof_step(mutated_job)
    except SystemExit:
        pass
    else:
        raise SystemExit(
            f"Helm transaction proof accepted a {fixture_name} proof step fixture"
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
if "docker logs firemud-helm-proof 2>&1 | grep -F 'k3s is up and running' >/dev/null" not in job_text:
    raise SystemExit("Helm transaction proof must wait on the pinned k3s startup marker before API operations")
if "--disable=metrics-server" not in job_text:
    raise SystemExit("Helm transaction proof must disable the optional metrics server so optional aggregated API discovery cannot affect the proof")
script = (root / "dev-tools/validation/helm-transaction-proof.sh").read_text()
for command in ("helm install", "helm upgrade", "helm history", "helm rollback", "helm status"):
    if command not in script:
        raise SystemExit(f"Helm proof script must exercise {command}")
for required_helm_command in (
    'helm install "$release" "$chart_dir" --namespace "$namespace" --wait --timeout 180s',
    'helm upgrade "$release" "$chart_dir" --namespace "$namespace" --set marker=upgraded --wait --timeout 180s',
    'helm rollback "$release" 1 --namespace "$namespace" --wait --timeout 180s',
):
    if required_helm_command not in script:
        raise SystemExit(
            "Helm proof install, upgrade, and rollback operations must use a bounded 180-second timeout: "
            f"{required_helm_command}"
        )
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
empty_temp_id_guard = '''if [[ -z "$temp_id" ]]; then
  echo "sanitized temp_id derived from work_dir $work_dir is empty" >&2
  rm -rf -- "$work_dir"
  exit 1
fi'''
namespace_assignment = 'namespace="helm-transaction-proof-${temp_id}"'
if empty_temp_id_guard not in script or f"{empty_temp_id_guard}\n{namespace_assignment}" not in script:
    raise SystemExit(
        "Helm proof must reject an empty sanitized temp_id immediately before constructing its namespace "
        "and identify work_dir as the source"
    )
if 'kubectl create namespace "$namespace" >/dev/null 2>&1' not in script:
    raise SystemExit("Helm proof must claim its unique namespace with exclusive kubectl create")
if 'kubectl create namespace "$namespace" --dry-run=client' in script or 'kubectl apply -f -' in script:
    raise SystemExit("Helm proof must not adopt an existing namespace through apply")
print("Helm transaction proof contract passed")
PY
