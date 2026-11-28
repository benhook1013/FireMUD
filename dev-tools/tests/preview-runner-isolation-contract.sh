#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

command -v python3 >/dev/null 2>&1 || {
  echo "python3 is required for the preview runner isolation contract." >&2
  exit 1
}
python3 -c 'import yaml' >/dev/null 2>&1 || {
  echo "python3 with PyYAML is required for the preview runner isolation contract." >&2
  exit 1
}
python3 -c 'import sys; sys.exit(0 if __debug__ else "Preview runner isolation contract requires Python assertions; unset PYTHONOPTIMIZE.")'

python3 - "$ROOT_DIR" <<'PY'
from __future__ import annotations

import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

import yaml


root = Path(sys.argv[1])
workflow_dir = root / ".github" / "workflows"
preview_path = workflow_dir / "preview.yml"
trusted_path = workflow_dir / "hosted-identity-request.yml"
reconciler_path = workflow_dir / "preview-reconciler.yml"
reconciler_script_path = root / "dev-tools/hosted/preview/reconcile-preview-deploys.sh"
janitor_path = workflow_dir / "preview-janitor.yml"
dev_demo_path = workflow_dir / "dev-demo.yml"
dev_demo_reconciler_path = workflow_dir / "dev-demo-reconciler.yml"
recovery_path = workflow_dir / "preview-ca-recovery.yml"


def load(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise AssertionError(f"missing workflow: {path}")
    value = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise AssertionError(f"{path.name} is not a YAML mapping")
    return value


def triggers(workflow: dict[str, Any]) -> dict[str, Any]:
    # PyYAML 1.1 parses the YAML 1.2 `on` key as boolean True.
    value = workflow.get("on", workflow.get(True, {}))
    return value if isinstance(value, dict) else {}


def text(value: Any) -> str:
    return yaml.safe_dump(value, sort_keys=False, width=10**6)


def labels(runs_on: Any) -> set[str]:
    if isinstance(runs_on, dict):
        runs_on = runs_on.get("labels", [])
    if isinstance(runs_on, str):
        return {runs_on.lower()}
    if isinstance(runs_on, list):
        return {item.lower() for item in runs_on if isinstance(item, str)}
    return set()


def checkouts(job: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        step
        for step in job.get("steps", [])
        if isinstance(step, dict)
        and isinstance(step.get("uses"), str)
        and step["uses"].startswith("actions/checkout@")
    ]


def permissions(value: Any) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, dict):
        raise AssertionError("workflow permissions must be an explicit mapping")
    return [
        f"{key}: {permission}"
        for key, permission in value.items()
        if isinstance(permission, str)
    ]


try:
    permissions("write-all")
except AssertionError:
    pass
else:
    raise AssertionError("scalar write-all permissions escaped source checking")



# Exercise the reconciler's actual active-run filter, not a duplicate predicate.
reconciler_source = reconciler_script_path.read_text(encoding="utf-8")
active_filter_match = re.search(
    r'--arg retry_run_suffix [^\n]+\n\s*"\$preview_native_pr_binding_jq"\'\n(.*?)\' <<<"\$\{active_preview_runs_json\}"',
    reconciler_source, re.S,
)
if active_filter_match is None:
    raise AssertionError("missing active preview filter in checked-in reconciler")
active_filter = active_filter_match.group(1)
native_pr_binding = re.search(r"readonly preview_native_pr_binding_jq='(.*?)'", reconciler_source, re.S)
if native_pr_binding is None:
    raise AssertionError("missing native PR binding in checked-in reconciler")


def active_ids(row: dict[str, Any]) -> str:
    return subprocess.check_output([
        "jq", "-r", "--arg", "workflow_name", "PR Preview Environment",
        "--argjson", "workflow_id", "173511323", "--argjson", "consumer_workflow_id", "357129350",
        "--arg", "repository", "benhook1013/FireMUD",
        "--arg", "default_branch", "develop", "--arg", "pr_title", "PR preview 7",
        "--argjson", "pr_number", "7", "--arg", "base_sha", "b" * 40,
        "--arg", "head_sha", "a" * 40, "--arg", "merge_sha", "c" * 40,
        "--arg", "run_name", "Preview dispatch pr-7-base-b-head-a-merge-c",
        "--arg", "retry_run_suffix", "pr-7-base-b-head-a-merge-c",
        native_pr_binding.group(1) + "(" + active_filter + ") | [.id, .exact] | @tsv",
    ], input=json.dumps([row]), text=True).strip()
expected_association = {"number": 7, "head": {"sha": "a" * 40}}
active_run = {
    "id": 1, "name": "PR preview 7", "path": ".github/workflows/preview.yml",
    "workflow_id": 173511323, "repository": {"full_name": "benhook1013/FireMUD"},
    "head_repository": {"full_name": "benhook1013/FireMUD"},
    "head_branch": "feature", "head_sha": "a" * 40, "pull_requests": [expected_association],
    "display_title": "PR preview 7", "status": "in_progress", "run_attempt": 1, "event": "pull_request",
    "firemud_reconciler_branch_filtered": False,
}
assert active_ids(active_run) == "1\tfalse"
assert active_ids({key: value for key, value in active_run.items() if key != "run_attempt"}) == ""
assert active_ids({**active_run, "pull_requests": [{"number": 8}, expected_association]}) == "1\tfalse"
assert active_ids({**active_run, "pull_requests": [expected_association, expected_association]}) == ""
qualified_pr = {**active_run, "path": ".github/workflows/preview.yml@refs/pull/7/merge",
                "pull_requests": [], "head_sha": "c" * 40}
assert active_ids(qualified_pr) == "1\tfalse"
assert active_ids({**qualified_pr, "head_sha": "a" * 40}) == ""
assert active_ids({**qualified_pr, "path": ".github/workflows/preview.yml@refs/pull/8/merge"}) == ""
for fields in (
    {"path": ".github/workflows/unrelated.yml"},
    {"head_sha": "b" * 40},
    {"workflow_id": 170000000},
    {"head_repository": {"full_name": "fork/FireMUD"}},
    {"repository": {"full_name": "fork/FireMUD"}},
    {"pull_requests": [{"number": 8}]},
):
    assert active_ids({**active_run, **fields}) == ""
assert active_ids({**active_run, "event": "pull_request_target"}) == "1\tfalse"
assert active_ids({**active_run, "event": "pull_request_target", "path": ".github/workflows/unrelated.yml"}) == ""
dispatch_title = "Preview dispatch pr-7-base-b-head-a-merge-c"
dispatch = {**active_run, "name": dispatch_title, "display_title": dispatch_title,
            "pull_requests": [], "head_branch": "develop"}
assert active_ids(dispatch) == ""
assert active_ids({**dispatch, "pull_requests": [expected_association]}) == "1\tfalse"
assert active_ids({**dispatch, "event": "repository_dispatch"}) == "1\ttrue"
assert active_ids({**dispatch, "event": "repository_dispatch", "head_branch": "feature"}) == ""
assert active_ids({**dispatch, "event": "repository_dispatch", "workflow_id": 170000000}) == ""
assert active_ids({**dispatch, "event": "repository_dispatch", "path": ".github/workflows/other.yml"}) == ""
assert active_ids({**dispatch, "event": "pull_request_target", "pull_requests": [{"number": 7}]}) == ""
assert active_ids({**dispatch, "event": "workflow_run", "name": "Trusted Hosted Identity Request",
                   "path": ".github/workflows/hosted-identity-request.yml"}) == ""

preview = load(preview_path)
trusted = load(trusted_path)
recovery = load(recovery_path)
preview_triggers = triggers(preview)
trusted_triggers = triggers(trusted)
recovery_triggers = triggers(recovery)
preview_jobs = preview.get("jobs")
trusted_jobs = trusted.get("jobs")
if not isinstance(preview_jobs, dict) or not preview_jobs:
    raise AssertionError("preview.yml must define source jobs")
if not isinstance(trusted_jobs, dict) or not trusted_jobs:
    raise AssertionError("hosted-identity-request.yml must define trusted jobs")
if set(preview_triggers) != {"pull_request", "repository_dispatch"}:
    raise AssertionError(
        "preview.yml must use only credential-free pull_request and default-branch repository_dispatch triggers"
    )
if preview_triggers["repository_dispatch"] != {"types": ["preview-deploy", "preview-destroy"]}:
    raise AssertionError("preview.yml must accept only typed preview deploy/destroy dispatches")
if "workflow_run" not in trusted_triggers or "pull_request_target" not in trusted_triggers:
    raise AssertionError(
        "trusted hosted lifecycle must retain workflow_run deploy and pull_request_target cleanup triggers"
    )

# CA recovery is intentionally a separate, recovery-only trust boundary. It
# must not be folded into the ordinary trusted-hosted-cluster jobs: its
# environment and credential are independently protected and its operation is
# limited to an explicit default-branch operator dispatch.
if set(recovery_triggers) != {"workflow_dispatch"}:
    raise AssertionError("preview CA recovery must use only workflow_dispatch")
recovery_input = recovery_triggers["workflow_dispatch"].get("inputs", {}).get("operation", {})
if recovery_input.get("options") != ["verify", "restore"]:
    raise AssertionError("preview CA recovery must expose only verify and restore operations")
recovery_job = recovery.get("jobs", {}).get("recover-preview-ca")
if not isinstance(recovery_job, dict):
    raise AssertionError("preview CA recovery lost its recovery job")
if recovery_job.get("environment") != "trusted-preview-ca-recovery":
    raise AssertionError("preview CA recovery must use its separate protected environment")
if "github.ref == 'refs/heads/develop'" not in str(recovery_job.get("if", "")):
    raise AssertionError("preview CA recovery must be restricted to develop")
recovery_checkouts = checkouts(recovery_job)
if len(recovery_checkouts) != 1:
    raise AssertionError("preview CA recovery must have exactly one checkout")
if recovery_checkouts[0].get("with", {}).get("ref") != "develop":
    raise AssertionError("preview CA recovery must check out develop")
if recovery_checkouts[0].get("with", {}).get("persist-credentials") is not False:
    raise AssertionError("preview CA recovery persists checkout credentials")
recovery_text = text(recovery_job)
if "trusted-hosted-cluster" in recovery_text:
    raise AssertionError("preview CA recovery must not reuse the deploy environment")
if "TRUSTED_PREVIEW_CA_RECOVERY_KUBECONFIG" not in recovery_text:
    raise AssertionError("preview CA recovery lost its recovery-only kubeconfig")

# Cluster-facing maintenance workflows are trusted consumers of repository
# dispatches. They must not expose their privileged jobs through a manually
# dispatched workflow. Their cleanup and maintenance jobs execute the checked-in
# default-branch implementation; the dev-demo deploy job is the one deliberate
# exception because its plan proves and binds the exact event head.
expected_dispatch_types = {
    janitor_path: ["preview-janitor"],
    reconciler_path: ["preview-reconcile"],
    dev_demo_path: ["dev-demo"],
    dev_demo_reconciler_path: ["dev-demo-reconcile"],
}

# Every job that can reach a cluster or consume deployment credentials must be
# gated by the separately protected GitHub Environment.  This is deliberately
# distinct from the Kubernetes target classes (pr-preview and
# dev-demo-cluster): those names describe where workloads run, while
# trusted-hosted-cluster controls which reviewed default-branch job may receive
# credentials.  Keep the list explicit so a newly added privileged job cannot
# silently inherit an unrestricted environment.
protected_jobs = {
    trusted_path: (
        "prepare-runtime",
        "deploy-runtime",
        "verify-runtime",
        "destroy-runtime",
        "retire-identity",
    ),
    reconciler_path: ("reconcile-previews",),
    janitor_path: ("prune-stale-preview-namespaces",),
    dev_demo_path: ("dev-demo-deploy", "dev-demo-destroy"),
    dev_demo_reconciler_path: ("reconcile-dev-demo",),
}
for path in (trusted_path, janitor_path, reconciler_path, dev_demo_path, dev_demo_reconciler_path):
    workflow = load(path)
    workflow_triggers = triggers(workflow)
    if "workflow_dispatch" in workflow_triggers:
        raise AssertionError(
            f"{path.name} exposes privileged jobs through workflow_dispatch"
        )
    if path == trusted_path:
        continue
    if path not in expected_dispatch_types:
        continue
    repository_dispatch = workflow_triggers.get("repository_dispatch")
    if not isinstance(repository_dispatch, dict) or repository_dispatch.get("types") != expected_dispatch_types[path]:
        raise AssertionError(
            f"{path.name} must use its exact typed repository_dispatch handoff"
        )
    privileged_jobs = []
    for job_name, job in workflow.get("jobs", {}).items():
        if not isinstance(job, dict):
            continue
        job_labels = labels(job.get("runs-on"))
        if (
            "self-hosted" in job_labels
            or job.get("environment") in {"pr-preview", "dev-demo-cluster"}
            or "secrets." in text(job)
        ):
            privileged_jobs.append((job_name, job))
    if not privileged_jobs:
        raise AssertionError(f"{path.name} has no identifiable privileged consumer job")
    for job_name, job in privileged_jobs:
        job_checkouts = checkouts(job)
        if len(job_checkouts) != 1:
            raise AssertionError(
                f"{path.name}:{job_name} must have exactly one trusted checkout"
            )
        checkout = job_checkouts[0]
        expected_ref = "${{ github.event.repository.default_branch }}"
        if path == dev_demo_path and job_name == "dev-demo-deploy":
            expected_ref = "${{ needs.dev-demo-plan.outputs.head_sha }}"
        if checkout.get("with", {}).get("ref") != expected_ref:
            raise AssertionError(
                f"{path.name}:{job_name} must checkout {expected_ref}"
            )
        if checkout.get("with", {}).get("persist-credentials") is not False:
            raise AssertionError(f"{path.name}:{job_name} persists checkout credentials")

for path, job_names in protected_jobs.items():
    workflow = load(path)
    jobs = workflow.get("jobs", {})
    for job_name in job_names:
        job = jobs.get(job_name)
        if not isinstance(job, dict):
            raise AssertionError(f"{path.name} lost privileged job {job_name}")
        if job.get("environment") != "trusted-hosted-cluster":
            raise AssertionError(
                f"{path.name}:{job_name} must select the protected trusted-hosted-cluster environment"
            )
        # A protected environment does not help if GitHub resolves an old
        # repository-level secret with the same workflow reference.  The
        # privileged jobs must use the deliberately distinct environment
        # secret names; migration is therefore fail-closed at the workflow
        # contract even before live secret rotation is performed.
        old_secret = re.search(
            r"secrets\.(?:PREVIEW_(?:KUBECONFIG|RUNTIME_KUBECONFIG|GHCR_USERNAME|GHCR_TOKEN)|HOSTED_IDENTITY_REQUESTER_KUBECONFIG)",
            text(job),
        )
        if old_secret:
            raise AssertionError(
                f"{path.name}:{job_name} still consumes repository-scoped {old_secret.group(0)}"
            )
        if "secrets.TRUSTED_HOSTED_PREVIEW_KUBECONFIG" in text(job):
            raise AssertionError(
                f"{path.name}:{job_name} still consumes the legacy shared preview kubeconfig secret"
            )

# Automatic pull_request source is untrusted; only typed repository dispatch
# uses the default-branch workflow. Both render with credential-free tools.
preview_source = preview_path.read_text(encoding="utf-8")
if "workflow_dispatch" in preview_source or "pull_request_target:" in preview_source:
    raise AssertionError("preview.yml retains a branch-selectable source trigger")
if '"$EVENT_NAME" == repository_dispatch && "$GITHUB_REF" != "refs/heads/$DEFAULT_BRANCH"' not in preview_source:
    raise AssertionError("preview.yml dispatch lacks a default-branch ref guard")
if (
    '"repos/${GITHUB_REPOSITORY}/dispatches"' not in reconciler_source
    or "event_type=preview-deploy" not in reconciler_source
):
    raise AssertionError("preview reconciler lost its typed default-branch source handoff")
reconciler_permissions = load(reconciler_path).get("permissions", {})
if reconciler_permissions.get("contents") != "write" or reconciler_permissions.get("actions") != "read":
    raise AssertionError(
        "preview reconciler needs contents:write to post repository_dispatch and only actions:read to inspect runs"
    )

# Every job in preview.yml is source-side orchestration. It may inspect PR
# metadata and render an artifact, but it must never be a privileged consumer.
# The one deliberate exception is the default-branch trusted dispatcher that
# asks the credential-free runtime-image workflow to refresh a stale base.
# Keep this exception exact: it must not turn the PR render into a writable
# job, or grow unrelated repository permissions.
source_permissions = permissions(preview.get("permissions"))
if any(permission.endswith(": write") for permission in source_permissions):
    raise AssertionError(
        f"preview source workflow grants write permissions: {source_permissions}"
    )


def check_preview_source_permissions(job_name: str, job: dict[str, Any]) -> None:
    job_permissions = job.get("permissions")
    if job_name == "preview-runtime-base-refresh":
        expected = {"contents": "write", "pull-requests": "read"}
        if job_permissions != expected:
            raise AssertionError(
                "preview-runtime-base-refresh must have exactly contents:write and pull-requests:read"
            )
        condition = str(job.get("if", "")).strip()
        # Accept only the current conjunction grammar; OR is not a mandatory gate.
        if not condition.startswith("$"+"{{ github.event_name == 'repository_dispatch' && ") or "||" in condition:
            raise AssertionError("preview runtime refresh write permissions require dispatch-only gating")
        return
    job_permissions_list = permissions(job_permissions)
    if any(permission.endswith(": write") for permission in job_permissions_list):
        raise AssertionError(
            f"preview source job {job_name} grants write permissions: {job_permissions_list}"
        )


# Preserve per-PR supersession for ordinary renders and give each reserved
# retry a separate run-scoped group so an ordinary run cannot replace it.
job_concurrency = {
    "preview-plan": (
        "github.event_name == 'repository_dispatch' && github.event.client_payload.proof_retry_nonce != ''",
        "github.event_name == 'pull_request' && github.event.pull_request.number || "
        "github.event.client_payload.pr_number || github.ref",
    ),
    "preview-render": (
        "needs.preview-plan.outputs.proof_retry_nonce != ''",
        "needs.preview-plan.outputs.pr_number",
    ),
}
for job_name, (retry, ordinary_key) in job_concurrency.items():
    concurrency = preview_jobs[job_name]["concurrency"]
    prefix = job_name.removeprefix("preview-")
    assert concurrency["group"] == (
        f"preview-readonly-{prefix}-" + "${{ "
        + f"({retry}) && format('retry-{{0}}', github.run_id) || {ordinary_key}" + " }}"
    )
    assert concurrency["cancel-in-progress"] == "${{ !(" + retry + ") }}"

for job_name, job in preview_jobs.items():
    if not isinstance(job, dict):
        raise AssertionError(f"preview job {job_name} is not a mapping")
    runner_labels = labels(job.get("runs-on"))
    if "self-hosted" in runner_labels or "preview" in runner_labels:
        raise AssertionError(f"preview source job {job_name} runs on a privileged preview runner")
    if job.get("environment") is not None:
        raise AssertionError(
            f"preview source job {job_name} selects a deployment environment"
        )
    check_preview_source_permissions(job_name, job)
    job_text = text(job)
    forbidden = (
        r"secrets\.",
        r"PREVIEW_KUBECONFIG",
        r"PREVIEW_GHCR_",
        r"HOSTED_IDENTITY_REQUESTER",
        r"docker/login-action@",
        r"docker\s+push\b",
        r"\bkubectl\b",
        r"helm\s+(?:upgrade|install)\b",
        r"request-hosted-identity\.sh",
        r"delete-hosted-namespace\.sh",
    )
    for pattern in forbidden:
        if re.search(pattern, job_text, re.IGNORECASE):
            raise AssertionError(
                f"preview source job {job_name} contains credential-bearing operation /{pattern}/"
            )

refresh_job = preview_jobs.get("preview-runtime-base-refresh")
if not isinstance(refresh_job, dict):
    raise AssertionError("preview workflow lost its trusted runtime base refresh dispatcher")
refresh_needs = refresh_job.get("needs")
if refresh_needs != "preview-plan" and refresh_needs != ["preview-plan"]:
    raise AssertionError("preview runtime base refresh must consume the trusted preview plan")
if checkouts(refresh_job):
    raise AssertionError("preview runtime base refresh must not check out PR-controlled source")
refresh_if = str(refresh_job.get("if", ""))
for required in (
    "eligible == 'true'",
    "base_refresh_required == 'true'",
    "action == 'deploy'",
    "image_tag != needs.preview-plan.outputs.base_sha",
):
    if required not in refresh_if:
        raise AssertionError(f"preview runtime base refresh lost its {required} gate")
refresh_scripts = [
    step.get("with", {}).get("script", "")
    for step in refresh_job.get("steps", [])
    if isinstance(step, dict)
    and isinstance(step.get("uses"), str)
    and re.fullmatch(r"actions/github-script@[0-9a-f]{40}", step["uses"])
]
if len(refresh_scripts) != 1:
    raise AssertionError("preview runtime base refresh must use exactly one pinned GitHub Script step")
refresh_script = refresh_scripts[0]
if re.search(r"['\"]pr-runtime-base-refresh['\"]", refresh_script):
    raise AssertionError("preview must not start the runtime producer directly")
request = re.search(
    r'event_type: "pr-runtime-base-refresh-request",\s*client_payload: \{([^}]+)\}',
    refresh_script,
)
if request is None or [line.strip() for line in request[1].strip().splitlines()] != [
    "pr_number: process.env.PR_NUMBER,",
    "base_ref: baseRef,",
    "base_sha: process.env.BASE_SHA,",
    "head_sha: process.env.HEAD_SHA,",
    "merge_sha: process.env.MERGE_SHA,",
    "preview_only: true,",
]:
    raise AssertionError("preview must request durable admission for the exact preview-only runtime tuple")

# Keep the permission boundary regression-tested independently of the live
# workflow. A render write grant is always forbidden; the trusted dispatcher
# accepts only its two explicitly required permissions.
render_with_write = dict(preview_jobs["preview-render"])
render_with_write["permissions"] = {"contents": "write"}
try:
    check_preview_source_permissions("preview-render", render_with_write)
except AssertionError:
    pass
else:
    raise AssertionError("preview render write permission mutation escaped source checking")

refresh_with_extra_permission = dict(refresh_job)
refresh_with_extra_permission["permissions"] = {
    "contents": "write",
    "pull-requests": "read",
    "actions": "read",
}
try:
    check_preview_source_permissions(
        "preview-runtime-base-refresh", refresh_with_extra_permission
    )
except AssertionError:
    pass
else:
    raise AssertionError("preview runtime refresh extra permission mutation escaped source checking")

refresh_with_pr_gate = dict(refresh_job)
refresh_with_pr_gate["if"] = "${{ github.event_name == 'pull_request' }}"
try:
    check_preview_source_permissions("preview-runtime-base-refresh", refresh_with_pr_gate)
except AssertionError:
    pass
else:
    raise AssertionError("preview runtime refresh PR write mutation escaped source checking")

refresh_with_or_gate = dict(refresh_job)
refresh_with_or_gate["if"] = "$"+"{{ github.event_name == 'repository_dispatch' || github.event_name == 'pull_request' }}"
try:
    check_preview_source_permissions("preview-runtime-base-refresh", refresh_with_or_gate)
except AssertionError:
    pass
else:
    raise AssertionError("OR dispatch gate escaped source checking")

# The plan's local actions and scripts must come from the default branch; the
# render's PR checkout is exact, unprivileged, and bound to the planned merge.
plan_checkouts = checkouts(preview_jobs["preview-plan"])
if len(plan_checkouts) != 1:
    raise AssertionError("preview plan must have one trusted tools checkout")
render_checkouts = checkouts(preview_jobs["preview-render"])
if len(render_checkouts) != 2:
    raise AssertionError("preview render must isolate trusted tools and candidate chart checkouts")

def check_trusted_tools_checkout(checkout: dict[str, Any]) -> None:
    options = checkout.get("with", {})
    if options.get("ref") != "${{ github.event.repository.default_branch }}" or options.get("path") is not None:
        raise AssertionError("preview tools must remain rooted at the trusted default branch")
    if "repository" in options and options["repository"] != "${{ github.repository }}":
        raise AssertionError("preview tools must check out the current repository")

for tools_checkout in (plan_checkouts[0], render_checkouts[0]):
    check_trusted_tools_checkout(tools_checkout)
    for override, reason in (
        ({"repository": "other-owner/other-repo"}, "preview tools must check out the current repository"),
        ({"path": "candidate"}, "preview tools must remain rooted at the trusted default branch"),
    ):
        try:
            check_trusted_tools_checkout({**tools_checkout, "with": {**tools_checkout["with"], **override}})
        except AssertionError as error:
            if str(error) != reason:
                raise
        else:
            raise AssertionError(f"untrusted tools checkout escaped checking: {override}")

def check_candidate_checkout(step: dict[str, Any]) -> None:
    options = step.get("with", {})
    if options.get("repository", "$"+"{{ github.repository }}") != "$"+"{{ github.repository }}":
        raise AssertionError("preview candidate must check out the current repository")
    if options.get("ref") != "refs/pull/${{ needs.preview-plan.outputs.pr_number }}/merge" or options.get("path") != "candidate":
        raise AssertionError("preview render must isolate the exact PR merge ref under candidate/")
    depth = options.get("fetch-depth")
    if not re.fullmatch(r"0|[1-9][0-9]*", str(depth)) or int(depth) == 1:
        raise AssertionError("preview candidate checkout must fetch depth 0 or an explicit integer at least 2 to verify ordered merge parents")

check_candidate_checkout(render_checkouts[1])
for depth in (0, 2, 3, "2"):
    check_candidate_checkout({
        **render_checkouts[1],
        "with": {**render_checkouts[1]["with"], "fetch-depth": depth},
    })
for options in (
    {key: value for key, value in render_checkouts[1]["with"].items() if key != "fetch-depth"},
    {**render_checkouts[1]["with"], "fetch-depth": 1},
):
    try:
        check_candidate_checkout({**render_checkouts[1], "with": options})
    except AssertionError as error:
        if str(error) != "preview candidate checkout must fetch depth 0 or an explicit integer at least 2 to verify ordered merge parents":
            raise
    else:
        raise AssertionError(f"shallow candidate checkout escaped ordered-parent checking: {options}")
try:
    check_candidate_checkout({
        **render_checkouts[1],
        "with": {**render_checkouts[1].get("with", {}), "repository": "other-owner/other-repo"},
    })
except AssertionError as error:
    if str(error) != "preview candidate must check out the current repository":
        raise
else:
    raise AssertionError("foreign candidate checkout escaped repository checking")

def check_trusted_working_directories(workflow: dict[str, Any], job: dict[str, Any]) -> None:
    inherited = workflow.get("defaults", {}).get("run", {}).get("working-directory", ".")
    inherited = job.get("defaults", {}).get("run", {}).get("working-directory", inherited)
    for step in job.get("steps", []):
        if "run" not in step:
            continue
        effective = step.get("working-directory", inherited)
        if effective not in (".", "./", "$"+"{{ github.workspace }}"):
            raise AssertionError("preview executable step must remain rooted in trusted tools")

for name in ("preview-plan", "preview-render"):
    check_trusted_working_directories(preview, preview_jobs[name])
for mutation in (
    {"defaults": {"run": {"working-directory": "candidate"}}, "steps": [{"run": "true"}]},
    {"steps": [{"run": "true", "working-directory": "candidate"}]},
):
    try:
        check_trusted_working_directories(preview, mutation)
    except AssertionError:
        pass
    else:
        raise AssertionError("candidate working-directory mutation escaped source checking")
try:
    check_trusted_working_directories(
        {"defaults": {"run": {"working-directory": "candidate"}}}, {"steps": [{"run": "true"}]}
    )
except AssertionError:
    pass
else:
    raise AssertionError("workflow default working-directory escaped source checking")
render_text = text(preview_jobs["preview-render"])
for required in ("git -C candidate", "candidate/k8s/helm/firemud", "python3 ./dev-tools/hosted/preview/"):
    if required not in render_text:
        raise AssertionError(f"preview render lost isolated trusted-tools/candidate-data boundary: {required}")
render_step = next(
    step for step in preview_jobs["preview-render"]["steps"]
    if step.get("name") == "Render and sanitize preview without credentials"
)
assert render_step["env"]["PLANNED_MERGE_SHA"] == "${{ needs.preview-plan.outputs.merge_sha }}"
assert render_step["env"]["BASE_SHA"] == "${{ needs.preview-plan.outputs.base_sha }}"
assert render_step["env"]["HEAD_SHA"] == "${{ needs.preview-plan.outputs.head_sha }}"
render_guard, marker, _ = render_step["run"].partition('values="$RUNNER_TEMP/preview-values.yaml"')
assert marker, "preview merge comparison must run before reading candidate values"
merge_sha, base_sha, head_sha, moved_sha = (digit * 40 for digit in "abcf")
fixture_git = r'''
git() {
    case "$*" in
        "-C candidate rev-parse --verify HEAD^{commit}") printf '%s\n' "$CANDIDATE" ;;
        "-C candidate rev-list --parents -n 1 $CANDIDATE") printf '%s %s\n' "$CANDIDATE" "$PARENTS" ;;
        *) return 2 ;;
    esac
}
'''
for candidate, parents, expected in (
    (merge_sha, f"{base_sha} {head_sha}", 0),
    (moved_sha, f"{base_sha} {head_sha}", 1),
    (merge_sha, f"{head_sha} {base_sha}", 1),
):
    result = subprocess.run(
        ["bash", "-c", fixture_git + render_guard], capture_output=True, text=True,
        env={"CANDIDATE": candidate, "PARENTS": parents, "PLANNED_MERGE_SHA": merge_sha,
             "BASE_SHA": base_sha, "HEAD_SHA": head_sha}, timeout=5,
    )
    assert result.returncode == expected, (candidate, parents, result.stderr)
    if expected:
        assert "Stale preview merge ref" in result.stderr
def check_candidate_execution(job):
    for step in job.get("steps", []):
        if re.match(r"(?:\./)?candidate/", step.get("uses", "")):
            raise AssertionError("preview render uses a candidate local action")
        script = step.get("run", "").replace("\\\n", "")
        if re.search(r"(?:bash|python3)\s+(?:\./)?candidate/", script) or re.search(
            r"""(?:^|[;&|()]|(?:then|do|else)\s)\s*['"]?(?:\./)?candidate/""",
            script,
            flags=re.MULTILINE,
        ):
            raise AssertionError("preview render executes a candidate tool")

check_candidate_execution(preview_jobs["preview-render"])
check_candidate_execution({"steps": [{"run": "helm template example ./candidate/k8s/helm/firemud"}]})
try:
    check_candidate_execution({"steps": [{"run": "./candidate/inert-fixture.sh"}]})
except AssertionError:
    pass
else:
    raise AssertionError("direct candidate execution escaped isolation checking")
for job_name, job in preview_jobs.items():
    for checkout in checkouts(job):
        if checkout.get("with", {}).get("persist-credentials") is not False:
            raise AssertionError(f"preview checkout in {job_name} persists credentials")
trusted_target = next(
    step for step in trusted_jobs["validate-target"]["steps"]
    if isinstance(step, dict) and step.get("id") == "target"
)["run"]
for required in (
    "[[ \"$SOURCE_EVENT\" == pull_request || \"$SOURCE_EVENT\" == repository_dispatch ]]",
    "require_source_field path '.github/workflows/preview.yml'",
    'require_source_field head "$WORKFLOW_RUN_HEAD_SHA"',
    'require_source_field head-branch "$DEFAULT_BRANCH"',
    "Ignoring source run without exactly one canonical preview artifact.",
    '"$SOURCE_EVENT" == pull_request && "$ARTIFACT_KIND" != render',
    '[[ "$current_head_sha" == "$EXPECTED_HEAD_SHA" ]]',
):
    if required not in trusted_target:
        raise AssertionError(f"trusted source provenance lost {required!r}")

# The trusted consumer must execute reviewed default-branch workflow code and
# validate the exact source artifact before preparation/deployment. A consumer
# can share one implementation across modes, or split the two mode jobs, but
# both mode decisions must remain explicit and covered by the artifact gate.
deploy_jobs = {
    name: job
    for name, job in trusted_jobs.items()
    if isinstance(job, dict)
    and "outputs.action == 'deploy'" in str(job.get("if", ""))
    and any(token in name for token in ("prepare", "deploy"))
}
if not deploy_jobs:
    raise AssertionError("trusted hosted lifecycle has no deploy consumer jobs")
for job_name, job in deploy_jobs.items():
    trusted_checkouts = checkouts(job)
    if len(trusted_checkouts) != 1:
        raise AssertionError(
            f"trusted deploy consumer {job_name} must have exactly one checkout"
        )
    checkout = trusted_checkouts[0]
    if checkout.get("with", {}).get("ref") != "${{ github.event.repository.default_branch }}":
        raise AssertionError(
            f"trusted deploy consumer {job_name} must checkout the default branch"
        )
    if checkout.get("with", {}).get("persist-credentials") is not False:
        raise AssertionError(f"trusted deploy consumer {job_name} persists checkout credentials")
    action_steps = [
        step
        for step in job.get("steps", [])
        if isinstance(step, dict)
        and step.get("uses") == "./.github/actions/download-validated-preview-artifact"
    ]
    if not action_steps:
        raise AssertionError(
            f"trusted deploy consumer {job_name} lacks exact preview artifact validation"
        )

trusted_text = text(trusted)
for mode in ("standalone", "hosted-controller"):
    if f"certificate_identity_mode == '{mode}'" not in trusted_text:
        raise AssertionError(
            f"trusted hosted lifecycle has no explicit {mode} deploy path"
        )
if "validate-preview-artifact.py" not in trusted_text:
    raise AssertionError("trusted lifecycle does not retain preview artifact validation")

# Credential files must stay in runner.temp and be removed. The old helper
# writes ${HOME}/.kube/config; no workflow may call it or recreate that path.
def reject_runner_home_kubeconfig(directory: Path) -> None:
    workflow_paths = sorted(
        path
        for path in directory.iterdir()
        if path.is_file() and path.suffix in {".yml", ".yaml"}
    )
    for path in workflow_paths:
        source = path.read_text(encoding="utf-8")
        if re.search(r"persist-runner-kubeconfig|HOME[^\n]*\.kube|\.kube/config", source):
            raise AssertionError(f"{path.name} persists a runner-home kubeconfig")


reject_runner_home_kubeconfig(workflow_dir)
with tempfile.TemporaryDirectory() as temporary:
    forbidden_yaml = Path(temporary) / "hostile.yaml"
    forbidden_yaml.write_text("echo $HOME/.kube/config\n", encoding="utf-8")
    try:
        reject_runner_home_kubeconfig(Path(temporary))
    except AssertionError:
        pass
    else:
        raise AssertionError(".yaml runner-home kubeconfig persistence escaped checking")

required_cleanup_jobs = ("destroy-runtime", "retire-identity")
for job_name in required_cleanup_jobs:
    job = trusted_jobs.get(job_name)
    if not isinstance(job, dict):
        raise AssertionError(f"trusted lifecycle lost {job_name} cleanup job")
    if "outputs.action == 'destroy'" not in str(job.get("if", "")):
        raise AssertionError(f"{job_name} is not gated on a destroy action")
    if not any(
        isinstance(step, dict)
        and isinstance(step.get("run"), str)
        and "revalidate-preview-deploy.sh" in step["run"]
        and "--cleanup" in step["run"]
        for step in job.get("steps", [])
    ):
        raise AssertionError(f"{job_name} lost stale-head cleanup revalidation")
retire_if = str(trusted_jobs["retire-identity"].get("if", ""))
if "needs.validate-target.outputs.retire_identity == 'true'" not in retire_if:
    raise AssertionError("open-PR manual destroy can reach identity retirement")
if "CLEANUP_STATE=open" not in trusted_text or "RETIRE_IDENTITY=true" not in trusted_text:
    raise AssertionError("trusted cleanup does not distinguish open and closed targets")

if "preview-allocation-lifecycle" not in trusted_text:
    raise AssertionError("trusted deploy/cleanup lifecycle lost its allocation lock")
allocator_text = (root / "dev-tools/hosted/preview/allocate-preview-capacity.sh").read_text(
    encoding="utf-8"
)
if "preview:priority" not in reconciler_source or "preview:priority" not in allocator_text:
    raise AssertionError("preview priority allocation gates are missing")
if "preview-allocation-lifecycle" not in janitor_path.read_text(encoding="utf-8"):
    raise AssertionError("preview janitor lost the shared allocation lifecycle lock")

# Destroy handoffs are source artifacts, not free-form dispatch inputs. Exercise
# the validator's accepted record and its repository/run/action/head/schema
# rejection paths so a forged intent cannot reach the trusted cleanup branch.
intent_validator = root / "dev-tools/hosted/preview/validate-preview-intent.py"
valid_intent = {
    "schemaVersion": 1,
    "event": "repository_dispatch",
    "action": "destroy",
    "repository": "example/FireMUD",
    "sourceWorkflow": ".github/workflows/preview.yml",
    "sourceRunId": 42,
    "prNumber": 900,
    "headSha": "a" * 40,
}
with tempfile.TemporaryDirectory() as temporary:
    intent_path = Path(temporary) / "preview-intent.json"

    def run_intent(document: dict[str, Any], repository: str = "example/FireMUD", run_id: str = "42") -> subprocess.CompletedProcess[str]:
        intent_path.write_text(json.dumps(document), encoding="utf-8")
        return subprocess.run(
            [sys.executable, str(intent_validator), str(intent_path), repository, run_id],
            check=False,
            text=True,
            capture_output=True,
        )

    accepted = run_intent(valid_intent)
    if accepted.returncode != 0 or "pr_number=900" not in accepted.stdout:
        raise AssertionError(f"valid preview destroy intent was rejected: {accepted.stderr}")
    for field, value in (
        ("event", "workflow_dispatch"),
        ("repository", "fork/FireMUD"),
        ("sourceRunId", 43),
        ("action", "deploy"),
        ("headSha", "A" * 40),
    ):
        forged = dict(valid_intent)
        forged[field] = value
        rejected = run_intent(forged)
        if rejected.returncode == 0:
            raise AssertionError(f"preview destroy intent accepted forged {field}")
    forged = dict(valid_intent)
    forged["unexpected"] = True
    if run_intent(forged).returncode == 0:
        raise AssertionError("preview destroy intent accepted an unexpected field")

print("preview runner isolation contract checks passed")
PY

# Exercise the metadata-only observer through this canonical isolation gate.
node --test "$ROOT_DIR/dev-tools/tests/preview-runtime-refresh.test.cjs"
