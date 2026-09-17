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
  local output
  if output="$(PATH="$tmp/bin:$PATH" KUBECTL_VERSION_JSON="$json" bash "$ROOT_DIR/dev-tools/hosted/shared/check-kubectl-version-skew.sh" 2>&1)"; then
    if [[ "$expected" != pass ]]; then
      echo "$name unexpectedly passed: $output" >&2
      exit 1
    fi
  elif [[ "$expected" = pass ]]; then
    echo "$name unexpectedly failed: $output" >&2
    exit 1
  fi
}

run_case same pass '{"clientVersion":{"gitVersion":"v1.34.5"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}'
run_case client-newer pass '{"clientVersion":{"gitVersion":"v1.35.8"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}'
run_case client-older pass '{"clientVersion":{"gitVersion":"v1.33.9"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}'
run_case too-new fail '{"clientVersion":{"gitVersion":"v1.36.0"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}'
run_case too-old fail '{"clientVersion":{"gitVersion":"v1.32.0"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}'
run_case major-mismatch fail '{"clientVersion":{"gitVersion":"v2.34.0"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}'
run_case missing-server fail '{"clientVersion":{"gitVersion":"v1.34.5"}}'

python3 - "$ROOT_DIR" <<'PY'
import copy
import pathlib
import sys

import yaml

root = pathlib.Path(sys.argv[1])
skew_script = "dev-tools/hosted/shared/check-kubectl-version-skew.sh"


def has_kubeconfig(environment):
    return isinstance(environment, dict) and bool(str(environment.get("KUBECONFIG", "")).strip())


def persists_kubeconfig(step):
    uses = str(step.get("uses", ""))
    if uses.endswith("/write-kubeconfig"):
        options = step.get("with", {})
        return isinstance(options, dict) and str(options.get("export-to-github-env", "true")).lower() != "false"
    run = str(step.get("run", ""))
    return "GITHUB_ENV" in run and "KUBECONFIG=" in run


def validate(workflow, fixture_name):
    jobs = workflow.get("jobs", {})
    if not isinstance(jobs, dict):
        raise SystemExit(f"{fixture_name}: workflow must define jobs")
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
            if skew_script in run and not (established or local):
                raise SystemExit(f"{fixture_name}: {job_name} invokes skew check before kubeconfig at step {index + 1}")
            established = established or persistent


manual_backup_path = root / ".github/workflows/manual-backup-restore.yml"
manual_backup = yaml.safe_load(manual_backup_path.read_text(encoding="utf-8"))
validate(manual_backup, "manual-backup-restore.yml")


def expect_rejected(workflow, fixture_name):
    try:
        validate(workflow, fixture_name)
    except SystemExit as error:
        if "before kubeconfig" not in str(error):
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
PY

for workflow in preview.yml dev-demo.yml preview-reconciler.yml dev-demo-reconciler.yml preview-janitor.yml hosted-identity-request.yml; do
  if ! grep -Fq 'dev-tools/hosted/shared/check-kubectl-version-skew.sh' "$ROOT_DIR/.github/workflows/$workflow"; then
    echo "$workflow must invoke the shared kubectl version skew preflight" >&2
    exit 1
  fi
done

echo "kubectl version skew contract passed"
