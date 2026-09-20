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
janitor_path = workflow_dir / "preview-janitor.yml"
dev_demo_path = workflow_dir / "dev-demo.yml"
dev_demo_reconciler_path = workflow_dir / "dev-demo-reconciler.yml"


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


preview = load(preview_path)
trusted = load(trusted_path)
preview_triggers = triggers(preview)
trusted_triggers = triggers(trusted)
preview_jobs = preview.get("jobs")
trusted_jobs = trusted.get("jobs")
if not isinstance(preview_jobs, dict) or not preview_jobs:
    raise AssertionError("preview.yml must define source jobs")
if not isinstance(trusted_jobs, dict) or not trusted_jobs:
    raise AssertionError("hosted-identity-request.yml must define trusted jobs")
if set(preview_triggers) != {"pull_request_target", "repository_dispatch"}:
    raise AssertionError(
        "preview.yml must use only default-branch-owned pull_request_target and repository_dispatch triggers"
    )
if preview_triggers["repository_dispatch"] != {"types": ["preview-deploy", "preview-destroy"]}:
    raise AssertionError("preview.yml must accept only typed preview deploy/destroy dispatches")
if "workflow_run" not in trusted_triggers or "pull_request_target" not in trusted_triggers:
    raise AssertionError(
        "trusted hosted lifecycle must retain workflow_run deploy and pull_request_target cleanup triggers"
    )

# Cluster-facing maintenance workflows are trusted consumers of repository
# dispatches. They must not expose their privileged jobs through a manually
# dispatched workflow, and every privileged job must execute the checked-in
# default-branch implementation.
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
        if checkout.get("with", {}).get("ref") != "${{ github.event.repository.default_branch }}":
            raise AssertionError(
                f"{path.name}:{job_name} must checkout the repository default branch"
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

# The source workflow definition must not be selectable from a PR branch.
# Typed repository dispatch and pull_request_target execute the default-branch
# workflow; the source job may still check out an exact PR merge only on an
# unprivileged GitHub-hosted runner.
preview_source = preview_path.read_text(encoding="utf-8")
if "workflow_dispatch" in preview_source or "on:\n  pull_request:" in preview_source:
    raise AssertionError("preview.yml retains a branch-selectable source trigger")
if '"$EVENT_NAME" == repository_dispatch && "$GITHUB_REF" != "refs/heads/$DEFAULT_BRANCH"' not in preview_source:
    raise AssertionError("preview.yml dispatch lacks a default-branch ref guard")
reconciler_source = reconciler_path.read_text(encoding="utf-8")
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
source_permissions = permissions(preview.get("permissions"))
if any(permission.endswith(": write") for permission in source_permissions):
    raise AssertionError(
        f"preview source workflow grants write permissions: {source_permissions}"
    )
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
    job_permissions = permissions(job.get("permissions"))
    if any(permission.endswith(": write") for permission in job_permissions):
        raise AssertionError(
            f"preview source job {job_name} grants write permissions: {job_permissions}"
        )
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

# The plan's local actions and scripts must come from the default branch; the
# render's PR checkout is exact, unprivileged, and bound to the planned merge.
plan_checkouts = checkouts(preview_jobs["preview-plan"])
if len(plan_checkouts) != 1 or plan_checkouts[0].get("with", {}).get("ref") != "${{ github.event.repository.default_branch }}":
    raise AssertionError("preview plan must check out the trusted default branch")
render_checkouts = checkouts(preview_jobs["preview-render"])
if len(render_checkouts) != 1 or render_checkouts[0].get("with", {}).get("ref") != "refs/pull/${{ needs.preview-plan.outputs.pr_number }}/merge":
    raise AssertionError("preview render must check out the exact PR merge ref")
for job_name, job in preview_jobs.items():
    for checkout in checkouts(job):
        if checkout.get("with", {}).get("persist-credentials") is not False:
            raise AssertionError(f"preview checkout in {job_name} persists credentials")
trusted_target = next(
    step for step in trusted_jobs["validate-target"]["steps"]
    if isinstance(step, dict) and step.get("id") == "target"
)["run"]
for required in (
    "[[ \"$SOURCE_EVENT\" == pull_request_target || \"$SOURCE_EVENT\" == repository_dispatch ]]",
    "require_source_field path '.github/workflows/preview.yml'",
    'require_source_field head-branch "$DEFAULT_BRANCH"',
    "Ignoring source run without exactly one canonical preview artifact.",
    '"$SOURCE_EVENT" == pull_request_target && "$ARTIFACT_KIND" != render',
    '"$WORKFLOW_RUN_HEAD_SHA")" == "$EXPECTED_HEAD_SHA"',
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
reconciler_text = reconciler_path.read_text(encoding="utf-8")
allocator_text = (root / "dev-tools/hosted/preview/allocate-preview-capacity.sh").read_text(
    encoding="utf-8"
)
if "preview:priority" not in reconciler_text or "preview:priority" not in allocator_text:
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
