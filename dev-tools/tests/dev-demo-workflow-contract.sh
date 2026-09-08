#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$ROOT_DIR/.github/workflows/dev-demo.yml"

check_port_forward_guard() {
  local workflow="$1"
  python3 - "$workflow" <<'PY'
import sys
from pathlib import Path

workflow_path = Path(sys.argv[1])
source = workflow_path.read_text(encoding="utf-8")

function_start = source.find("          wait_for_bootstrap_port_forward() {\n")
function_end = source.find("          cleanup_bootstrap_account_id_file() {\n", function_start)
if function_start < 0 or function_end < 0:
    raise SystemExit("dev-demo workflow must define the port-forward readiness guard")

guard = source[function_start:function_end]
required_guard_fragments = (
    "BOOTSTRAP_PORT_FORWARD_READY_ATTEMPTS",
    'kill -0 "${BOOTSTRAP_PORT_FORWARD_PID}"',
    "while IFS= read -r forwarding_line; do",
    'if [[ "${forwarding_line}" =~ ^Forwarding\\ from\\ (127\\.0\\.0\\.1|localhost):([0-9]+)\\ -\\>\\ 8080$ ]]; then',
    'BOOTSTRAP_GATEWAY_PORT="${BASH_REMATCH[2]}"',
    '[[ -z "${BOOTSTRAP_GATEWAY_PORT}" || ! "${BOOTSTRAP_GATEWAY_PORT}" =~ ^[0-9]+$ ]]',
    '"${BOOTSTRAP_PORT_FORWARD_LOG}"',
    "sleep 1",
)
for fragment in required_guard_fragments:
    if fragment not in guard:
        raise SystemExit(f"dev-demo port-forward readiness guard must contain: {fragment}")

if guard.count('kill -0 "${BOOTSTRAP_PORT_FORWARD_PID}"') < 2:
    raise SystemExit("dev-demo port-forward readiness guard must recheck its spawned PID after log confirmation")
if "$((" in guard or "$(" in guard:
    raise SystemExit("dev-demo port-forward readiness guard must parse its listener without executable substitutions")

port_assignment = source.find("          BOOTSTRAP_GATEWAY_PORT=\n", function_end)
dynamic_port_forward = source.find('            ":80"', port_assignment)
fixed_port_forward = source.find("            \"${BOOTSTRAP_GATEWAY_PORT}:80\"\n", port_assignment)
spawn_pid = source.find("          BOOTSTRAP_PORT_FORWARD_PID=$!\n", port_assignment)
guard_call = source.find("          if ! wait_for_bootstrap_port_forward; then\n", spawn_pid)
credential_bootstrap = source.find("          if ! BOOTSTRAP_MODE=account \\\n", spawn_pid)
base_url = source.find('BOOTSTRAP_GATEWAY_BASE_URL="http://127.0.0.1:${BOOTSTRAP_GATEWAY_PORT}"', credential_bootstrap)
parsed_port = guard.find('BOOTSTRAP_GATEWAY_PORT="${BASH_REMATCH[2]}"')
if min(port_assignment, dynamic_port_forward, spawn_pid, guard_call, credential_bootstrap, base_url) < 0:
    raise SystemExit("dev-demo workflow must retain dynamic port allocation, spawned PID, readiness call, and account bootstrap")
if fixed_port_forward >= 0:
    raise SystemExit("dev-demo workflow must not bind a fixed local port before readiness parsing")
if not port_assignment < dynamic_port_forward < spawn_pid < guard_call < credential_bootstrap:
    raise SystemExit("dev-demo workflow must prove its spawned port-forward before account credentials can be sent")
if parsed_port < 0:
    raise SystemExit("dev-demo workflow must source the bootstrap port from the confirmed forwarding line")
parsed_port_absolute = source.find('BOOTSTRAP_GATEWAY_PORT="${BASH_REMATCH[2]}"', function_start)
if not function_start < parsed_port_absolute < function_end:
    raise SystemExit("dev-demo workflow must assign the selected port inside the readiness guard")
if 'cat "${BOOTSTRAP_PORT_FORWARD_LOG}"' in source:
    raise SystemExit("dev-demo workflow must not print the raw port-forward log on credential-bootstrap failure")
PY
}

exercise_port_forward_guard() {
  local workflow="$1"
  local forwarding_line="$2"
  local expected_port="$3"
  local extracted_guard="$FIXTURE_DIR/extracted-port-forward-guard.sh"
  local port_forward_log="$FIXTURE_DIR/port-forward.log"

  python3 - "$workflow" "$extracted_guard" <<'PY'
import sys
from pathlib import Path

source = Path(sys.argv[1]).read_text(encoding="utf-8")
function_start = source.find("          wait_for_bootstrap_port_forward() {\n")
function_end = source.find("          cleanup_bootstrap_account_id_file() {\n", function_start)
if function_start < 0 or function_end < 0:
    raise SystemExit("dev-demo workflow must define the port-forward readiness guard")

indent = "          "
lines = source[function_start:function_end].splitlines(keepends=True)
if any(line.strip() and not line.startswith(indent) for line in lines):
    raise SystemExit("dev-demo port-forward readiness guard has unexpected indentation")
Path(sys.argv[2]).write_text(
    "".join(line[len(indent):] if line.strip() else line for line in lines),
    encoding="utf-8",
)
PY

  printf '%s\n' "$forwarding_line" > "$port_forward_log"
  (
    # shellcheck disable=SC1090 # Generated from the checked workflow function above.
    source "$extracted_guard"
    export BOOTSTRAP_PORT_FORWARD_READY_ATTEMPTS=1
    export BOOTSTRAP_PORT_FORWARD_LOG="$port_forward_log"
    export BOOTSTRAP_PORT_FORWARD_PID="$BASHPID"
    BOOTSTRAP_GATEWAY_PORT=
    if ! wait_for_bootstrap_port_forward; then
      echo "dev-demo workflow guard rejected a representative kubectl forwarding line" >&2
      return 1
    fi
    if [[ "$BOOTSTRAP_GATEWAY_PORT" != "$expected_port" ]]; then
      echo "dev-demo workflow guard parsed the wrong dynamic listener port" >&2
      return 1
    fi
  )
}

check_port_forward_guard "$WORKFLOW"

# Keep the workflow source's bootstrap path binding stable; this is the shell
# declaration used by the following heredoc and subsequent bootstrap commands.
if ! grep -Fq '          readonly BOOTSTRAP_SCRIPT=/tmp/dev-demo-bootstrap.py' "$WORKFLOW"; then
  echo "dev-demo workflow must declare its readonly bootstrap script path" >&2
  exit 1
fi

FIXTURE_DIR="$(mktemp -d)"
trap 'rm -rf "${FIXTURE_DIR}"' EXIT

exercise_port_forward_guard \
  "$WORKFLOW" \
  "Forwarding from 127.0.0.1:54321 -> 8080" \
  "54321"

MISSING_GUARD_FIXTURE="$FIXTURE_DIR/missing-port-forward-guard.yml"
sed '/^          if ! wait_for_bootstrap_port_forward; then$/,/^          fi$/d' \
  "$WORKFLOW" > "$MISSING_GUARD_FIXTURE"
if check_port_forward_guard "$MISSING_GUARD_FIXTURE" >/dev/null 2>&1; then
  echo "dev-demo workflow contract accepted credential bootstrap without the port-forward guard" >&2
  exit 1
fi

WRONG_TARGET_FIXTURE="$FIXTURE_DIR/wrong-port-forward-target.yml"
python3 - "$WORKFLOW" "$WRONG_TARGET_FIXTURE" <<'PY'
import sys
from pathlib import Path

source = Path(sys.argv[1]).read_text(encoding="utf-8")
old = r"\ -\>\ 8080$"
new = r"\ -\>\ 8081$"
if source.count(old) != 1:
    raise SystemExit("expected exactly one bootstrap forwarding target assertion")
Path(sys.argv[2]).write_text(source.replace(old, new), encoding="utf-8")
PY
if check_port_forward_guard "$WRONG_TARGET_FIXTURE" >/dev/null 2>&1; then
  echo "dev-demo workflow contract accepted confirmation for an unrelated pod target" >&2
  exit 1
fi

FIXED_PORT_FIXTURE="$FIXTURE_DIR/fixed-port-forward.yml"
python3 - "$WORKFLOW" "$FIXED_PORT_FIXTURE" <<'PY'
import sys
from pathlib import Path

source = Path(sys.argv[1]).read_text(encoding="utf-8")
old = '            ":80" \\\n'
new = '            "18080:80" \\\n'
if source.count(old) != 1:
    raise SystemExit("expected exactly one dynamic bootstrap port-forward spec")
Path(sys.argv[2]).write_text(source.replace(old, new), encoding="utf-8")
PY
if check_port_forward_guard "$FIXED_PORT_FIXTURE" >/dev/null 2>&1; then
  echo "dev-demo workflow contract accepted a fixed local port-forward binding" >&2
  exit 1
fi

FIXED_BASE_URL_FIXTURE="$FIXTURE_DIR/fixed-base-url-port.yml"
# shellcheck disable=SC2016 # The fixture mutation must match the literal workflow placeholder.
sed 's/127\.0\.0\.1:${BOOTSTRAP_GATEWAY_PORT}/127.0.0.1:18080/' \
  "$WORKFLOW" > "$FIXED_BASE_URL_FIXTURE"
if check_port_forward_guard "$FIXED_BASE_URL_FIXTURE" >/dev/null 2>&1; then
  echo "dev-demo workflow contract accepted a bootstrap URL detached from the selected port" >&2
  exit 1
fi

LATE_GUARD_FIXTURE="$FIXTURE_DIR/late-port-forward-guard.yml"
python3 - "$WORKFLOW" "$LATE_GUARD_FIXTURE" <<'PY'
import sys
from pathlib import Path

source = Path(sys.argv[1]).read_text(encoding="utf-8")
guard_start = source.index("          if ! wait_for_bootstrap_port_forward; then\n")
account_bootstrap = source.index("          if ! BOOTSTRAP_MODE=account \\\n", guard_start)
guard = source[guard_start:account_bootstrap]
source = source[:guard_start] + source[account_bootstrap:]
account_bootstrap = source.index("          if ! BOOTSTRAP_MODE=account \\\n", guard_start)
cleanup = source.index("          cleanup_bootstrap_port_forward\n", account_bootstrap)
Path(sys.argv[2]).write_text(source[:cleanup] + guard + source[cleanup:], encoding="utf-8")
PY
if check_port_forward_guard "$LATE_GUARD_FIXTURE" >/dev/null 2>&1; then
  echo "dev-demo workflow contract accepted account bootstrap before the port-forward guard" >&2
  exit 1
fi

PRINTED_LOG_FIXTURE="$FIXTURE_DIR/printed-port-forward-log.yml"
# shellcheck disable=SC2016 # Insert the literal workflow variable reference.
sed '/^          BOOTSTRAP_PORT_FORWARD_PID=\$!$/a\          cat "${BOOTSTRAP_PORT_FORWARD_LOG}"' \
  "$WORKFLOW" > "$PRINTED_LOG_FIXTURE"
if check_port_forward_guard "$PRINTED_LOG_FIXTURE" >/dev/null 2>&1; then
  echo "dev-demo workflow contract accepted printing the raw port-forward log after PID assignment" >&2
  exit 1
fi

python3 - "$WORKFLOW" <<'PY'
import copy
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

import yaml


def load_workflow(path):
    workflow = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    if not isinstance(workflow, dict):
        raise ValueError("dev-demo workflow must be a mapping")
    return workflow


def named_step(steps, name):
    matches = [step for step in steps if step.get("name") == name]
    if len(matches) != 1:
        raise ValueError(f"dev-demo workflow must contain exactly one {name!r} step")
    return matches[0]


def workflow_steps(workflow, job_name):
    jobs = workflow.get("jobs")
    if not isinstance(jobs, dict):
        raise ValueError("dev-demo workflow must define jobs")
    job = jobs.get(job_name)
    if not isinstance(job, dict):
        raise ValueError(f"dev-demo workflow must define {job_name}")
    steps = job.get("steps")
    if not isinstance(steps, list):
        raise ValueError(f"{job_name} must define steps")
    return steps


def validate_exact_head_guard(workflow):
    derive = named_step(workflow_steps(workflow, "dev-demo-plan"), "Derive dev-demo target")
    run = derive.get("run")
    if not isinstance(run, str):
        raise ValueError("dev-demo target derivation must be a shell script")
    default_image = 'if [ -z "$IMAGE_TAG" ]; then\n  IMAGE_TAG="${HEAD_SHA}"\nfi\n'
    exact_head_guard = (
        'if [ "$ACTION" = "deploy" ] && [ "$IMAGE_TAG" != "$HEAD_SHA" ]; then\n'
        '  echo "::error::Dev-demo deploy image tag must match its reconciled head SHA."\n'
        "  exit 1\n"
        "fi\n"
    )
    output_start = "{\n  echo \"action=${ACTION}\""
    if run.count(exact_head_guard) != 1:
        raise ValueError("dev-demo deploy derivation must reject a non-exact image tag")
    if run.find(default_image) < 0:
        raise ValueError("dev-demo derivation must resolve an omitted image tag to its head SHA")
    if not run.index(default_image) < run.index(exact_head_guard) < run.index(output_start):
        raise ValueError("dev-demo must reject a non-exact image tag before publishing plan outputs")


def exercise_target_derivation(workflow, *, event_name, action, head_sha, image_tag, github_sha):
    derive = named_step(workflow_steps(workflow, "dev-demo-plan"), "Derive dev-demo target")
    script = derive["run"]
    replacements = {
        "${{ github.event_name }}": "${TEST_EVENT_NAME}",
        "${{ github.sha }}": "${TEST_PUSH_SHA}",
        "${{ inputs.action }}": "${TEST_ACTION}",
        "${{ inputs.head_sha }}": "${TEST_HEAD_SHA}",
        "${{ inputs.hostname }}": "${TEST_HOSTNAME}",
        "${{ inputs.telnet_port }}": "${TEST_TELNET_PORT}",
        "${{ inputs.image_tag }}": "${TEST_IMAGE_TAG}",
    }
    for expression, shell_value in replacements.items():
        if script.count(expression) != 1:
            raise ValueError(f"target derivation must contain exactly one {expression}")
        script = script.replace(expression, shell_value)

    with tempfile.TemporaryDirectory() as directory:
        output_path = Path(directory) / "github-output"
        environment = os.environ.copy()
        environment.update(
            {
                "GITHUB_OUTPUT": str(output_path),
                "GITHUB_SHA": github_sha,
                "TEST_ACTION": action,
                "TEST_EVENT_NAME": event_name,
                "TEST_HEAD_SHA": head_sha,
                "TEST_HOSTNAME": "dev.preview.firedevops.net",
                "TEST_IMAGE_TAG": image_tag,
                "TEST_PUSH_SHA": github_sha,
                "TEST_TELNET_PORT": "32016",
            }
        )
        result = subprocess.run(
            ["bash", "-eu", "-o", "pipefail", "-c", script],
            check=False,
            capture_output=True,
            env=environment,
            text=True,
        )
        output = output_path.read_text(encoding="utf-8") if output_path.exists() else ""
        return result, output


def validate_mismatched_deploy_rejection(workflow):
    result, output = exercise_target_derivation(
        workflow,
        event_name="workflow_dispatch",
        action="deploy",
        head_sha="a" * 40,
        image_tag="b" * 40,
        github_sha="a" * 40,
    )
    if result.returncode == 0 or output:
        raise ValueError(
            "dev-demo target derivation must reject a non-exact deploy target before outputs"
        )


def validate_exact_head_contract(workflow):
    validate_mismatched_deploy_rejection(workflow)
    validate_exact_head_guard(workflow)


def validate_reconciled_publication(workflow):
    steps = workflow_steps(workflow, "dev-demo-deploy")

    smoke = named_step(steps, "Smoke dev-demo over TCP")
    publication = named_step(steps, "Record reconciled dev-demo target")
    annotation_helper = "bash ./dev-tools/hosted/dev-demo/annotate-dev-demo-namespace.sh"
    annotation_steps = [
        step
        for step in steps
        if annotation_helper in str(step.get("run", ""))
    ]
    if len(annotation_steps) != 1 or annotation_steps[0] is not publication:
        raise ValueError(
            "dev-demo target annotation must occur only in the reconciled publication step"
        )
    if smoke.get("id") != "gameplay-smoke":
        raise ValueError("dev-demo gameplay smoke must retain the gameplay-smoke result id")
    if steps.index(publication) <= steps.index(smoke):
        raise ValueError("dev-demo target must not be marked reconciled before gameplay smoke")

    expected_condition = (
        "${{ steps.cluster-access.outputs.available == 'true' && "
        "steps.deploy-release.outcome == 'success' && "
        "steps.gameplay-smoke.outcome == 'success' }}"
    )
    if publication.get("if") != expected_condition:
        raise ValueError(
            "reconciled publication must require successful cluster access, deploy, and gameplay smoke"
        )
    run = publication.get("run")
    if not isinstance(run, str) or run.count(annotation_helper) != 1:
        raise ValueError(
            "reconciled publication must invoke the namespace annotation helper exactly once"
        )


def validate_dev_demo_preflight(workflow):
    steps = workflow_steps(workflow, "dev-demo-deploy")
    validation = named_step(steps, "Validate dev-demo chart render")
    run = validation.get("run")
    if not isinstance(run, str):
        raise ValueError("dev-demo render validation must be a shell script")
    if run.count("FIREMUD_PREFLIGHT_CONTEXT=operator") != 1:
        raise ValueError("dev-demo render validation must use operator context exactly once")
    if "--expected-hosted-telnet-node-port" not in run:
        raise ValueError("dev-demo preflight must bind its fixed Telnet NodePort")
    if "${{ needs.dev-demo-plan.outputs.telnet_port }}" not in run:
        raise ValueError("dev-demo preflight must use the derived Telnet NodePort")
    preflight_index = run.index("preflight.py hosted-bridge")
    server_dry_run_index = run.index("kubectl apply --dry-run=server")
    if preflight_index >= server_dry_run_index:
        raise ValueError("dev-demo preflight must precede server-side dry-run")


def validate_hosted_identity_lifecycle(workflow):
    steps = workflow_steps(workflow, "dev-demo-deploy")
    candidate = named_step(steps, "Record dev-demo identity candidate")
    requester = named_step(steps, "Write hosted identity requester kubeconfig")
    active_request = named_step(steps, "Apply canonical dev-demo Active request")
    restore = named_step(steps, "Restore dev-demo runtime kubeconfig")
    preflight = named_step(steps, "Validate dev-demo chart render")
    deploy = named_step(steps, "Deploy dev-demo release")
    identity_wait = named_step(steps, "Wait for exact dev-demo controller identity")
    smoke = named_step(steps, "Smoke dev-demo over TCP")
    publication = named_step(steps, "Record reconciled dev-demo target")
    cleanup = named_step(steps, "Clear unproven dev-demo identity candidate")

    ordered = (
        candidate,
        requester,
        active_request,
        restore,
        preflight,
        deploy,
        identity_wait,
        smoke,
        publication,
        cleanup,
    )
    if [steps.index(step) for step in ordered] != sorted(steps.index(step) for step in ordered):
        raise ValueError(
            "dev-demo identity request, projection preflight, readiness, smoke, and publication are out of order"
        )

    candidate_run = candidate.get("run", "")
    for annotation in (
        "firemud.dev/last-dev-demo-head-sha=${{ needs.dev-demo-plan.outputs.head_sha }}",
        "firemud.dev/last-dev-demo-telnet-port=${{ needs.dev-demo-plan.outputs.telnet_port }}",
    ):
        if annotation not in candidate_run:
            raise ValueError(f"dev-demo identity candidate must set {annotation}")

    if requester.get("uses") != "./.github/actions/write-kubeconfig":
        raise ValueError("dev-demo Active request must use the private kubeconfig action")
    if requester.get("with", {}).get("content") != "${{ secrets.HOSTED_IDENTITY_REQUESTER_KUBECONFIG }}":
        raise ValueError("dev-demo Active request must use the requester-only kubeconfig")
    active_run = active_request.get("run", "")
    for fragment in (
        "apiVersion: platform.firemud.dev/v1alpha1",
        "kind: HostedEnvironmentIdentity",
        "name: dev-demo",
        "namespace: firemud-system",
        "desiredState: Active",
    ):
        if active_run.count(fragment) != 1:
            raise ValueError(f"dev-demo Active request must contain exactly one {fragment!r}")

    if restore.get("uses") != "./.github/actions/write-kubeconfig":
        raise ValueError("dev-demo workflow must restore its runtime kubeconfig with the private action")
    if restore.get("with", {}).get("content") != "${{ secrets.PREVIEW_RUNTIME_KUBECONFIG }}":
        raise ValueError("dev-demo workflow must restore the runtime-only kubeconfig")
    if restore.get("if") != "${{ always() && steps.cluster-access.outputs.available == 'true' }}":
        raise ValueError("dev-demo runtime kubeconfig restoration must run after a failed request")

    workflow_source = Path(sys.argv[1]).read_text(encoding="utf-8")
    if "ensure-grpc-tls-secret.sh" in workflow_source:
        raise ValueError("dev-demo workflow must not generate controller-owned gRPC identity material")

    wait_run = identity_wait.get("run", "")
    for fragment in (
        "./dev-tools/hosted/preview/wait-for-hosted-identity.sh",
        "dev-demo",
        "${{ needs.dev-demo-plan.outputs.head_sha }}",
        "${{ needs.dev-demo-plan.outputs.namespace }}",
        "900",
    ):
        if fragment not in wait_run:
            raise ValueError(f"dev-demo controller readiness wait must contain {fragment!r}")
    expected_wait_condition = (
        "${{ steps.cluster-access.outputs.available == 'true' && "
        "steps.deploy-release.outcome == 'success' }}"
    )
    if identity_wait.get("if") != expected_wait_condition:
        raise ValueError("dev-demo controller readiness must require a successful application deploy")

    expected_cleanup_condition = (
        "${{ always() && steps.cluster-access.outputs.available == 'true' && "
        "steps.gameplay-smoke.outcome != 'success' }}"
    )
    if cleanup.get("if") != expected_cleanup_condition:
        raise ValueError("unproven dev-demo candidate cleanup must run after every unsuccessful smoke")
    cleanup_run = cleanup.get("run", "")
    for annotation in (
        "firemud.dev/last-dev-demo-head-sha-",
        "firemud.dev/last-dev-demo-image-tag-",
        "firemud.dev/last-dev-demo-telnet-port-",
        "firemud.dev/last-dev-demo-sync-at-",
    ):
        if cleanup_run.count(annotation) != 1:
            raise ValueError(f"unproven dev-demo cleanup must remove exactly one {annotation}")


def validate_bootstrap_readiness_contract(workflow):
    steps = workflow_steps(workflow, "dev-demo-deploy")
    bootstrap_run = named_step(steps, "Create dev-demo smoke account").get("run")
    if not isinstance(bootstrap_run, str):
        raise ValueError("dev-demo bootstrap must be a shell script")
    heredoc_start = bootstrap_run.index("cat >\"${BOOTSTRAP_SCRIPT}\" <<'PY'\n")
    heredoc_end = bootstrap_run.index("\nPY\n", heredoc_start)
    bootstrap_python = bootstrap_run[
        heredoc_start + len("cat >\"${BOOTSTRAP_SCRIPT}\" <<'PY'\n") : heredoc_end
    ]
    readiness_start = bootstrap_python.index("def readiness_ok(url, request_timeout):\n")
    readiness_end = bootstrap_python.index("def post_json(url, payload):\n", readiness_start)
    readiness_source = bootstrap_python[readiness_start:readiness_end]
    namespace = {"json": json, "time": time, "urllib": __import__("urllib")}
    exec(readiness_source, namespace)
    readiness_ok = namespace["readiness_ok"]
    wait_for_ready = namespace["wait_for_ready"]

    class Response:
        def __init__(self, payload, status=200):
            self.payload = json.dumps(payload).encode("utf-8")
            self.status = status

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def read(self):
            return self.payload

    original_urlopen = urllib.request.urlopen
    original_monotonic = time.monotonic
    original_sleep = time.sleep
    try:
        urllib.request.urlopen = lambda _url, timeout: Response(
            {"components": {"database": {"status": "UP"}}}
        )
        if readiness_ok("http://readiness", 1):
            raise ValueError("dev-demo readiness must reject a nested-only UP status")
        urllib.request.urlopen = lambda _url, timeout: Response({"status": "DOWN"})
        if readiness_ok("http://readiness", 1):
            raise ValueError("dev-demo readiness must reject a non-UP root status")
        urllib.request.urlopen = lambda _url, timeout: Response({"status": "UP"})
        if not readiness_ok("http://readiness", 1):
            raise ValueError("dev-demo readiness must accept root status UP")

        clock = {"now": 100.0}
        request_timeouts = []
        sleeps = []

        def bounded_urlopen(_url, timeout):
            request_timeouts.append(timeout)
            return Response({"status": "DOWN"})

        def fake_sleep(seconds):
            sleeps.append(seconds)
            clock["now"] += seconds

        urllib.request.urlopen = bounded_urlopen
        time.monotonic = lambda: clock["now"]
        time.sleep = fake_sleep
        try:
            wait_for_ready("service", "http://readiness", timeout_seconds=0.5)
        except SystemExit:
            pass
        else:
            raise ValueError("dev-demo readiness wait must fail when its deadline expires")
        if not request_timeouts or any(timeout <= 0 or timeout > 0.5 for timeout in request_timeouts):
            raise ValueError("dev-demo readiness requests must be bounded by the enclosing deadline")
        if any(seconds <= 0 or seconds > 0.5 for seconds in sleeps):
            raise ValueError("dev-demo readiness retry sleeps must be bounded by the enclosing deadline")
    finally:
        urllib.request.urlopen = original_urlopen
        time.monotonic = original_monotonic
        time.sleep = original_sleep


workflow = load_workflow(sys.argv[1])
validate_exact_head_contract(workflow)
validate_reconciled_publication(workflow)
validate_dev_demo_preflight(workflow)
validate_hosted_identity_lifecycle(workflow)
validate_bootstrap_readiness_contract(workflow)

matched, matched_output = exercise_target_derivation(
    workflow,
    event_name="workflow_dispatch",
    action="deploy",
    head_sha="a" * 40,
    image_tag="a" * 40,
    github_sha="c" * 40,
)
if matched.returncode != 0:
    raise SystemExit(f"dev-demo target derivation rejected an exact deploy target: {matched.stderr}")
if "head_sha=" + "a" * 40 not in matched_output or "image_tag=" + "a" * 40 not in matched_output:
    raise SystemExit("dev-demo target derivation did not publish the exact manual deploy target")

destroy, destroy_output = exercise_target_derivation(
    workflow,
    event_name="workflow_dispatch",
    action="destroy",
    head_sha="a" * 40,
    image_tag="b" * 40,
    github_sha="c" * 40,
)
if destroy.returncode != 0 or "action=destroy" not in destroy_output:
    raise SystemExit("dev-demo target derivation rejected a non-deploy workflow dispatch")

push, push_output = exercise_target_derivation(
    workflow,
    event_name="push",
    action="destroy",
    head_sha="a" * 40,
    image_tag="b" * 40,
    github_sha="c" * 40,
)
if push.returncode != 0:
    raise SystemExit(f"dev-demo target derivation rejected a develop push: {push.stderr}")
if "action=deploy" not in push_output:
    raise SystemExit("dev-demo push target derivation did not retain deploy action")
if "head_sha=" + "c" * 40 not in push_output or "image_tag=" + "c" * 40 not in push_output:
    raise SystemExit("dev-demo push target derivation did not bind its image tag to the pushed SHA")

exact_head_mutations = []
publication_mutations = []
preflight_mutations = []

unguarded_target = copy.deepcopy(workflow)
unguarded_derive = named_step(
    workflow_steps(unguarded_target, "dev-demo-plan"),
    "Derive dev-demo target",
)
unguarded_derive["run"] = unguarded_derive["run"].replace(
    'if [ "$ACTION" = "deploy" ] && [ "$IMAGE_TAG" != "$HEAD_SHA" ]; then\n'
    '  echo "::error::Dev-demo deploy image tag must match its reconciled head SHA."\n'
    "  exit 1\n"
    "fi\n",
    "",
)
exact_head_mutations.append(("target derivation without exact-head guard", unguarded_target))

early_exit_target = copy.deepcopy(workflow)
early_exit_derive = named_step(
    workflow_steps(early_exit_target, "dev-demo-plan"),
    "Derive dev-demo target",
)
early_exit_derive["run"] = early_exit_derive["run"].replace(
    'if [ "$ACTION" = "deploy" ] && [ "$IMAGE_TAG" != "$HEAD_SHA" ]; then\n',
    'exit 0\nif [ "$ACTION" = "deploy" ] && [ "$IMAGE_TAG" != "$HEAD_SHA" ]; then\n',
)
exact_head_mutations.append(("target derivation bypassing its exact-head guard", early_exit_target))

for description, mutation in exact_head_mutations:
    try:
        validate_exact_head_contract(mutation)
    except ValueError:
        continue
    raise SystemExit(f"dev-demo workflow contract accepted {description}")

missing_preflight_port = copy.deepcopy(workflow)
missing_preflight_port_step = named_step(
    workflow_steps(missing_preflight_port, "dev-demo-deploy"),
    "Validate dev-demo chart render",
)
missing_preflight_port_step["run"] = missing_preflight_port_step["run"].replace(
    "--expected-hosted-telnet-node-port",
    "# expected port removed",
)
preflight_mutations.append(("dev-demo preflight without the fixed NodePort", missing_preflight_port))

static_dev_demo_preflight = copy.deepcopy(workflow)
static_dev_demo_preflight_step = named_step(
    workflow_steps(static_dev_demo_preflight, "dev-demo-deploy"),
    "Validate dev-demo chart render",
)
static_dev_demo_preflight_step["run"] = static_dev_demo_preflight_step["run"].replace(
    "FIREMUD_PREFLIGHT_CONTEXT=operator", "FIREMUD_PREFLIGHT_CONTEXT=ci-static"
)
preflight_mutations.append(("dev-demo preflight weakened to ci-static", static_dev_demo_preflight))

for description, mutation in preflight_mutations:
    try:
        validate_dev_demo_preflight(mutation)
    except ValueError:
        continue
    raise SystemExit(f"dev-demo workflow contract accepted {description}")

pre_smoke = copy.deepcopy(workflow)
pre_smoke_steps = pre_smoke["jobs"]["dev-demo-deploy"]["steps"]
pre_smoke_publication = named_step(pre_smoke_steps, "Record reconciled dev-demo target")
pre_smoke_steps.remove(pre_smoke_publication)
pre_smoke_index = pre_smoke_steps.index(named_step(pre_smoke_steps, "Smoke dev-demo over TCP"))
pre_smoke_steps.insert(pre_smoke_index, pre_smoke_publication)
publication_mutations.append(("pre-smoke success publication", pre_smoke))

duplicate_pre_smoke = copy.deepcopy(workflow)
duplicate_pre_smoke_steps = duplicate_pre_smoke["jobs"]["dev-demo-deploy"]["steps"]
duplicate_publication = copy.deepcopy(
    named_step(duplicate_pre_smoke_steps, "Record reconciled dev-demo target")
)
duplicate_publication["name"] = "Premature reconciled dev-demo target"
duplicate_publication["if"] = "${{ steps.cluster-access.outputs.available == 'true' }}"
duplicate_pre_smoke_index = duplicate_pre_smoke_steps.index(
    named_step(duplicate_pre_smoke_steps, "Smoke dev-demo over TCP")
)
duplicate_pre_smoke_steps.insert(duplicate_pre_smoke_index, duplicate_publication)
publication_mutations.append(
    ("duplicate pre-smoke reconciled publication", duplicate_pre_smoke)
)

duplicate_in_step = copy.deepcopy(workflow)
duplicate_in_step_publication = named_step(
    duplicate_in_step["jobs"]["dev-demo-deploy"]["steps"],
    "Record reconciled dev-demo target",
)
duplicate_in_step_publication["run"] += "\n" + duplicate_in_step_publication["run"]
publication_mutations.append(
    ("duplicate annotation helper invocation in the publication step", duplicate_in_step)
)

unbound_smoke = copy.deepcopy(workflow)
unbound_publication = named_step(
    unbound_smoke["jobs"]["dev-demo-deploy"]["steps"],
    "Record reconciled dev-demo target",
)
unbound_publication["if"] = (
    "${{ steps.cluster-access.outputs.available == 'true' && "
    "steps.deploy-release.outcome == 'success' }}"
)
publication_mutations.append(("publication not bound to gameplay-smoke success", unbound_smoke))

failed_smoke_alias = copy.deepcopy(workflow)
named_step(
    failed_smoke_alias["jobs"]["dev-demo-deploy"]["steps"],
    "Smoke dev-demo over TCP",
)["id"] = "untracked-smoke"
publication_mutations.append(("publication detached from the smoke result id", failed_smoke_alias))

for description, mutation in publication_mutations:
    try:
        validate_reconciled_publication(mutation)
    except ValueError:
        continue
    raise SystemExit(f"dev-demo workflow contract accepted {description}")
PY

python3 "$ROOT_DIR/dev-tools/validation/check_dev_demo_summary.py" "$ROOT_DIR"
python3 "$ROOT_DIR/dev-tools/validation/test_check_dev_demo_summary.py"
