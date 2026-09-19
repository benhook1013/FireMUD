#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
cat > "$tmp/bin/kubectl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" != version || "${2:-}" != --output=json ]]; then
  echo "unexpected kubectl invocation" >&2
  exit 1
fi
printf '%s\n' "${KUBECTL_VERSION_JSON:?}"
EOF
chmod 0755 "$tmp/bin/kubectl"

run_case() {
  local name="$1"
  local expected="$2"
  local json="$3"
  local diagnostic="${4:-}"
  local output
  if output="$(PATH="$tmp/bin:$PATH" KUBECTL_VERSION_JSON="$json" bash "$ROOT_DIR/dev-tools/hosted/shared/check-kubectl-version-skew.sh" 2>&1)"; then
    if [[ "$expected" != pass ]]; then
      echo "$name unexpectedly passed: $output" >&2
      exit 1
    fi
  elif [[ "$expected" = pass ]]; then
    echo "$name unexpectedly failed: $output" >&2
    exit 1
  elif [[ -n "$diagnostic" && "$output" != *"$diagnostic"* ]]; then
    echo "$name returned the wrong diagnostic: $output" >&2
    exit 1
  fi
}

run_case same pass '{"clientVersion":{"gitVersion":"v1.34.5"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}'
run_case client-newer pass '{"clientVersion":{"gitVersion":"v1.35.8"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}'
run_case client-older pass '{"clientVersion":{"gitVersion":"v1.33.9"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}'
run_case too-new fail '{"clientVersion":{"gitVersion":"v1.36.0"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}' 'minor skew is unsupported'
run_case too-old fail '{"clientVersion":{"gitVersion":"v1.32.0"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}' 'minor skew is unsupported'
run_case major-mismatch fail '{"clientVersion":{"gitVersion":"v2.34.0"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}' 'major version skew is unsupported'
run_case missing-server fail '{"clientVersion":{"gitVersion":"v1.34.5"}}' 'kubectl version output lacks parseable client and server gitVersion values'

python3 - "$ROOT_DIR" <<'PY'
import copy
import pathlib
import re
import sys

import yaml

root = pathlib.Path(sys.argv[1])
skew_script = "dev-tools/hosted/shared/check-kubectl-version-skew.sh"
github_env_kubeconfig_pattern = re.compile(
    r"(?:^|[;&|])[ \t]*(?:echo|printf)[ \t]+[^;&|]*\bKUBECONFIG=[^;&|]*>>[ \t]*(?:[\"']?\$\{?GITHUB_ENV\}?\"?|'?\$\{?GITHUB_ENV\}?'?)"
)


def has_kubeconfig(environment):
    return isinstance(environment, dict) and bool(str(environment.get("KUBECONFIG", "")).strip())


def persists_kubeconfig(step):
    uses = str(step.get("uses", ""))
    if uses.endswith("/write-kubeconfig"):
        options = step.get("with", {})
        return isinstance(options, dict) and str(options.get("export-to-github-env", "true")).lower() != "false"
    run = str(step.get("run", ""))
    for raw_line in run.splitlines():
        line = raw_line.lstrip()
        if not line or line.startswith("#"):
            continue
        line = line.split("#", 1)[0]
        if github_env_kubeconfig_pattern.search(line):
            return True
    return False


def executable_invocation_count(run):
    command_pattern = re.compile(
        rf"(?:^|[;&|])[ \t]*(?:(?:bash|sh)[ \t]+)?\./{re.escape(skew_script)}(?=$|[ \t])"
    )
    count = 0
    for raw_line in run.splitlines():
        line = raw_line.lstrip()
        if not line or line.startswith("#"):
            continue
        line = line.split("#", 1)[0]
        count += len(command_pattern.findall(line))
    return count


def validate(workflow, fixture_name, required_jobs=()):
    jobs = workflow.get("jobs", {})
    if not isinstance(jobs, dict):
        raise SystemExit(f"{fixture_name}: workflow must define jobs")
    required_jobs = tuple(required_jobs)
    missing_jobs = [job_name for job_name in required_jobs if job_name not in jobs]
    if missing_jobs:
        raise SystemExit(f"{fixture_name}: required skew jobs are missing: {', '.join(missing_jobs)}")
    skew_invocations = {}
    for job_name, job in jobs.items():
        if not isinstance(job, dict) or "steps" not in job:
            continue
        steps = job["steps"]
        if not isinstance(steps, list):
            raise SystemExit(f"{fixture_name}: {job_name} must define steps as a list")
        established = has_kubeconfig(workflow.get("env", {})) or has_kubeconfig(job.get("env", {}))
        for index, step in enumerate(steps):
            if not isinstance(step, dict):
                continue
            run = str(step.get("run", ""))
            local = has_kubeconfig(step.get("env", {}))
            persistent = persists_kubeconfig(step)
            invocation_count = executable_invocation_count(run)
            if invocation_count:
                skew_invocations[job_name] = skew_invocations.get(job_name, 0) + invocation_count
                if not (established or local):
                    raise SystemExit(
                        f"{fixture_name}: {job_name} invokes skew check before kubeconfig at step {index + 1}"
                    )
            established = established or persistent
    for job_name in required_jobs:
        invocation_count = skew_invocations.get(job_name, 0)
        if invocation_count != 1:
            raise SystemExit(
                f"{fixture_name}: {job_name} must invoke the shared kubectl version skew preflight exactly once; "
                f"found {invocation_count}"
            )


manual_backup_path = root / ".github/workflows/manual-backup-restore.yml"
manual_backup = yaml.safe_load(manual_backup_path.read_text(encoding="utf-8"))
validate(manual_backup, "manual-backup-restore.yml")

hosted_workflow_names = (
    "preview.yml",
    "dev-demo.yml",
    "preview-reconciler.yml",
    "dev-demo-reconciler.yml",
    "preview-janitor.yml",
    "hosted-identity-request.yml",
)
hosted_workflows = {}
required_hosted_skew_jobs = {
    "preview.yml": ("preview-deploy", "preview-destroy"),
    "dev-demo.yml": ("dev-demo-deploy", "dev-demo-destroy"),
    "preview-reconciler.yml": ("reconcile-previews",),
    "dev-demo-reconciler.yml": ("reconcile-dev-demo",),
    "preview-janitor.yml": ("prune-stale-preview-namespaces",),
    "hosted-identity-request.yml": ("deploy-runtime", "destroy-runtime", "retire-identity"),
}
for workflow_name in hosted_workflow_names:
    workflow_path = root / ".github/workflows" / workflow_name
    workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
    hosted_workflows[workflow_name] = workflow
    validate(workflow, workflow_name, required_jobs=required_hosted_skew_jobs[workflow_name])


def expect_rejected(workflow, fixture_name, message_fragment="before kubeconfig", required_jobs=()):
    try:
        validate(workflow, fixture_name, required_jobs=required_jobs)
    except SystemExit as error:
        if message_fragment not in str(error):
            raise
    else:
        raise SystemExit(f"{fixture_name} unexpectedly passed")

# A renamed step must not bypass the command-level guard.
renamed_step_fixture = copy.deepcopy(manual_backup)
renamed_step_fixture["jobs"]["verify"]["steps"].insert(0, {"name": "Check cluster compatibility", "run": f"bash ./{skew_script}"})
expect_rejected(renamed_step_fixture, "renamed-step fixture")

# A later invocation is valid once the workflow exports its kubeconfig.
after_kubeconfig_fixture = copy.deepcopy(manual_backup)
after_kubeconfig_fixture["jobs"]["verify"]["steps"].extend([
    {"name": "Establish restore kubeconfig", "run": 'echo "KUBECONFIG=$RUNNER_TEMP/restore.kubeconfig" >> "$GITHUB_ENV"'},
    {"name": "Validate restored cluster skew", "run": f"bash ./{skew_script}"},
])
validate(after_kubeconfig_fixture, "after-kubeconfig fixture")

# A comment-only export must not authorize a later invocation.
comment_only_export_fixture = copy.deepcopy(manual_backup)
comment_only_export_fixture["jobs"]["verify"]["steps"].extend([
    {
        "name": "Comment-only fake kubeconfig export",
        "run": '# echo "KUBECONFIG=/tmp/comment-only.kubeconfig" >> "$GITHUB_ENV"',
    },
    {"name": "Reject skew after comment-only export", "run": f"bash ./{skew_script}"},
])
expect_rejected(comment_only_export_fixture, "comment-only export fixture")

# An export later in the same step must not authorize an earlier invocation.
before_export_fixture = copy.deepcopy(manual_backup)
before_export_fixture["jobs"]["verify"]["steps"].append({
    "name": "Reject skew before kubeconfig export",
    "run": f'bash ./{skew_script}\necho "KUBECONFIG=$RUNNER_TEMP/restore.kubeconfig" >> "$GITHUB_ENV"',
})
expect_rejected(before_export_fixture, "before-export fixture")

# A step-local KUBECONFIG must not leak into a later step.
step_env_fixture = copy.deepcopy(manual_backup)
step_env_fixture["jobs"]["verify"]["steps"].extend([
    {"name": "Use step-local kubeconfig", "env": {"KUBECONFIG": "/tmp/restore.kubeconfig"}, "run": "true"},
    {"name": "Reject leaked step-local kubeconfig", "run": f"bash ./{skew_script}"},
])
expect_rejected(step_env_fixture, "step-local env fixture")

# A commented-out invocation must not satisfy the exactly-once command contract.
commented_invocation_fixture = copy.deepcopy(manual_backup)
for step in commented_invocation_fixture["jobs"]["verify"]["steps"]:
    if isinstance(step, dict) and isinstance(step.get("run"), str):
        step["run"] = "\n".join(line for line in step["run"].splitlines() if skew_script not in line)
commented_invocation_fixture["jobs"]["verify"]["steps"].append(
    {"name": "Commented skew invocation", "run": f"# bash ./{skew_script}"}
)
expect_rejected(
    commented_invocation_fixture,
    "commented invocation fixture",
    "must invoke the shared kubectl version skew preflight exactly once",
    required_jobs=("verify",),
)

# A hosted workflow must also reject a skew invocation that precedes its kubeconfig setup.
hosted_wrong_order_fixture = copy.deepcopy(hosted_workflows["preview-janitor.yml"])
hosted_wrong_order_fixture["jobs"]["prune-stale-preview-namespaces"]["steps"] = [
    step
    for step in hosted_wrong_order_fixture["jobs"]["prune-stale-preview-namespaces"]["steps"]
    if skew_script not in str(step.get("run", ""))
]
hosted_wrong_order_fixture["jobs"]["prune-stale-preview-namespaces"]["steps"].insert(
    0, {"name": "Reject skew before kubeconfig", "run": f"bash ./{skew_script}"}
)
expect_rejected(hosted_wrong_order_fixture, "hosted wrong-order fixture")

# A workflow-level invocation must not satisfy a different cluster-using job.
multi_job_removal_fixture = copy.deepcopy(hosted_workflows["preview.yml"])
multi_job_removal_fixture["jobs"]["preview-destroy"]["steps"] = [
    step
    for step in multi_job_removal_fixture["jobs"]["preview-destroy"]["steps"]
    if skew_script not in str(step.get("run", ""))
]
expect_rejected(
    multi_job_removal_fixture,
    "multi-job removal fixture",
    "must invoke the shared kubectl version skew preflight exactly once",
    required_jobs=required_hosted_skew_jobs["preview.yml"],
)
PY

echo "kubectl version skew contract passed"
