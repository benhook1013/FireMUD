#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Required gates on metadata-only PR edits: a title/body edit (edited with no
# base change) skips each required gate under a non-required name, so the
# earlier substantive result stands and an edit can never turn a failing PR
# green. Base retargets keep the required name and rerun the full gate.
python3 - "$ROOT_DIR" <<'PY'
import ast
import os
from pathlib import Path
import re
import subprocess
import sys

import yaml

root = Path(sys.argv[1])
META = "github.event_name == 'pull_request' && github.event.action == 'edited' && github.event.changes.base.ref == null"
SUBSTANTIVE = "(github.event_name != 'pull_request' || github.event.action != 'edited' || github.event.changes.base.ref != null)"
PR_SUBSTANTIVE = "(github.event.action != 'edited' || github.event.changes.base.ref != null)"
gates = {
    # workflow: (workflow name, gate job, gate name, change job name, result step, PR-only, run-scoped metadata group)
    "ci.yml": ("CI — Validation", "validation-gate", "Validation Gate", "Detect CI-Relevant Changes", "Enforce validation success", False, True),
    "security.yml": ("Security Gate", "security-gate", "Security Gate", "Detect Security-Relevant Changes", "Enforce security success", False, True),
    "license-scan.yml": ("License Gate", "license-gate", "License Gate", "Detect License-Relevant Changes", "Verify ORT results", False, False),
    "smoke.yml": ("PR Smoke Gate", "smoke-gate", "Smoke Gate", "Detect Smoke-Relevant Changes", "Classify smoke gate execution", True, True),
    "codeql.yml": ("CodeQL Analysis", "codeql-gate", "CodeQL Gate", "Detect CodeQL-Relevant Changes", "Enforce successful CodeQL analysis", True, False),
}
retired = (
    "preserve-required-gate",
    "publish-required-gate-status",
    "resolve-required-gates",
    "statuses: write",
)


def evaluate(expression, action, base_ref, event="pull_request"):
    """Evaluate the narrow boolean gate expressions used here."""
    body = expression.strip()
    if body.startswith("${{") and body.endswith("}}"):
        body = body[3:-2].strip()
    values = {
        "github.event.changes.base.ref": repr(base_ref),
        "github.event.action": repr(action),
        "github.event_name": repr(event),
        "always()": "True",
    }
    for key, value in values.items():
        body = body.replace(key, value)
    body = body.replace("&&", " and ").replace("||", " or ").replace("!=", " != ").replace("null", "None")
    return eval(body, {"__builtins__": {}})



def literal_format(pattern, *args):
    if type(pattern) is not str:
        raise ValueError("Concurrency format requires a literal string")
    parts = []
    cursor = 0
    for match in re.finditer(r"\{([0-9]+)\}", pattern):
        literal = pattern[cursor:match.start()]
        if "{" in literal or "}" in literal:
            raise ValueError("Unsupported concurrency format placeholder")
        index = int(match[1])
        if index >= len(args):
            raise ValueError("Concurrency format placeholder out of range")
        parts.extend((literal, str(args[index])))
        cursor = match.end()
    tail = pattern[cursor:]
    if "{" in tail or "}" in tail:
        raise ValueError("Unsupported concurrency format placeholder")
    return "".join((*parts, tail))


def interpret_selector(node):
    if isinstance(node, ast.Constant) and type(node.value) in (str, int, bool, type(None)):
        return node.value
    if isinstance(node, ast.BoolOp) and isinstance(node.op, (ast.And, ast.Or)):
        # GitHub's boolean operators return operands and short-circuit.
        value = interpret_selector(node.values[0])
        for operand in node.values[1:]:
            if bool(value) == isinstance(node.op, ast.Or):
                return value
            value = interpret_selector(operand)
        return value
    if (isinstance(node, ast.Compare) and len(node.ops) == 1
            and isinstance(node.ops[0], (ast.Eq, ast.NotEq))):
        values = interpret_selector(node.left), interpret_selector(node.comparators[0])
        # Actions compares strings without regard to case.
        if all(type(value) is str for value in values):
            values = tuple(value.casefold() for value in values)
        return (values[0] == values[1]) == isinstance(node.ops[0], ast.Eq)
    if (isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
            and node.func.id == "format" and not node.keywords and node.args
            and isinstance(node.args[0], ast.Constant)):
        return literal_format(*(interpret_selector(arg) for arg in node.args))
    raise ValueError("Unsupported concurrency selector")


for expression, expected in (("'Develop' == 'develop'", True), ("'Develop' != 'develop'", False)):
    assert interpret_selector(ast.parse(expression, mode="eval").body) is expected


assert literal_format("metadata-{0}", 123) == "metadata-123"
for forbidden in ("{name}", "{0.attr}", "{0[0]}", "{0:04}", "{0!r}", "{{0}}", "{1}"):
    try:
        literal_format(forbidden, 123)
    except ValueError:
        pass
    else:
        raise AssertionError(f"Accepted unsupported concurrency format: {forbidden}")


def effective_group(group, action, base_ref, workflow_name="Required Gate", *, push_branch=None):
    values = {
        "github.event.changes.base.ref": repr(base_ref),
        "github.event.pull_request.number": "42",
        "github.event.action": repr(action),
        "github.event_name": repr("push" if push_branch else "pull_request"),
        "github.workflow": repr(workflow_name),
        "github.run_id": "123",
        "github.ref": repr(f"refs/heads/{push_branch}" if push_branch else "refs/pull/42/merge"),
    }

    def render(match):
        body = match[1].strip()
        for key, value in values.items():
            body = body.replace(key, value)
        body = body.replace("&&", " and ").replace("||", " or ")
        body = re.sub(r"\b(null|true|false)\b", lambda token: {
            "null": "None", "true": "True", "false": "False",
        }[token[0]], body)
        tree = ast.parse(body, mode="eval")
        return str(interpret_selector(tree.body))
    return re.sub(r"\$\{\{(.*?)\}\}", render, group)


def cross_workflow_group_collisions(rendered_groups):
    owners = {}
    collisions = []
    for workflow, action, group in rendered_groups:
        key = group.casefold()
        prior = owners.get(key)
        if prior is not None and prior[0] != workflow:
            collisions.append((prior, (workflow, action, group)))
        else:
            owners[key] = (workflow, action, group)
    return collisions


def can_reuse_required_name(name, required_names):
    if not name:
        return False
    # These current matrix templates have fixed delimiters that can never
    # render any required context. Do not assume arbitrary dynamics are safe.
    matrix_prefixes = (
        "Build and Test (${{ matrix.module }} / ${{ matrix.partition }})",
        "Build and Test (account-service / ${{ matrix.partition }})",
        "Build and Test (game-session-service / ${{ matrix.partition }})",
        "Build and Test (world-management-service / ${{ matrix.partition }})",
        "CodeQL Analysis (${{ matrix.language }})",
    )
    if name in matrix_prefixes and all(
        not gate.startswith(("Build and Test (", "CodeQL Analysis (")) for gate in required_names
    ):
        return False
    dynamic = re.fullmatch(r"\$\{\{(.*?)\}\}", name)
    if not dynamic:
        return "${{" in name or name in required_names
    body = dynamic[1].strip()
    # Limit dynamic names to the existing exact metadata ternary shape or a
    # constant literal; other shapes need an explicitly reviewed model.
    if re.fullmatch(r"'[^']*'", body):
        return body[1:-1] in required_names
    summary = re.fullmatch(
        re.escape("github.event.action == 'edited' && github.event.changes.base.ref == null")
        + r" && '([^']*)' \|\| '([^']*)'",
        body,
    )
    return not summary or any(option in required_names for option in summary.groups())


assert can_reuse_required_name("${{ 'CodeQL Gate' }}", {"CodeQL Gate"})
assert not can_reuse_required_name("${{ 'Auxiliary Report' }}", {"CodeQL Gate"})
assert can_reuse_required_name("${{ matrix.language }}", {"CodeQL Gate"})
assert not can_reuse_required_name("CodeQL Analysis (${{ matrix.language }})", {"CodeQL Gate"})


def isolates_metadata_group(group, run_scoped):
    metadata = effective_group(group, "edited", None)
    expected_suffix = "-metadata-123" if run_scoped else "-metadata"
    substantive = [effective_group(group, action, base_ref) for action, base_ref in (
        ("edited", "develop"), ("opened", None), ("synchronize", None), ("reopened", None),
    )]
    return metadata.endswith(expected_suffix) and all(
        name != metadata and name.endswith("-required") for name in substantive
    )


def excludes_metadata(expression):
    body = expression.strip()
    if body.startswith("${{") and body.endswith("}}"):
        body = body[3:-2].strip()
    if body == SUBSTANTIVE[1:-1] or body == PR_SUBSTANTIVE[1:-1]:
        return True
    # Only a required top-level conjunction can enforce the exclusion.
    # Parenthesized additional conditions may contain OR without bypassing it.
    depth = 0
    quoted = False
    start = 0
    terms = []
    index = 0
    while index < len(body):
        char = body[index]
        if char == "'":
            if quoted and index + 1 < len(body) and body[index + 1] == "'":
                index += 2
                continue
            quoted = not quoted
        elif not quoted:
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth < 0:
                    return False
            elif depth == 0 and body[index:index + 2] == "||":
                return False
            elif depth == 0 and body[index:index + 2] == "&&":
                terms.append(body[start:index].strip())
                start = index + 2
                index += 1
        index += 1
    terms.append(body[start:].strip())
    return not quoted and depth == 0 and any(term in (SUBSTANTIVE, PR_SUBSTANTIVE) for term in terms)


assert excludes_metadata("${{ " + SUBSTANTIVE + " && needs.changes.result == 'success' }}")
assert excludes_metadata("${{ always() && " + PR_SUBSTANTIVE + " && (true || false) }}")
assert not excludes_metadata("${{ github.event_name == 'pull_request' || github.event.action != 'edited' || github.event.changes.base.ref != null }}")
assert not excludes_metadata("${{ " + SUBSTANTIVE + " || always() }}")
assert not excludes_metadata("${{ (" + SUBSTANTIVE + " || always()) }}")

for directory in (".github/actions/preserve-required-gate", ".github/actions/publish-required-gate-status"):
    if (root / directory).exists():
        raise SystemExit(f"retired metadata preservation machinery must stay deleted: {directory}")
if (root / ".github/workflows/resolve-required-gates.yml").exists():
    raise SystemExit("retired required-gate resolver workflow must stay deleted")

for workflow, (workflow_name, gate_job, gate_name, change_job, result_step, pr_only, run_scoped) in gates.items():
    path = root / ".github/workflows" / workflow
    text = path.read_text(encoding="utf-8")
    for token in retired:
        if token in text:
            raise SystemExit(f"{workflow} still references retired metadata preservation: {token}")
    data = yaml.load(text, Loader=yaml.BaseLoader)
    if data.get("name") != workflow_name:
        raise SystemExit(f"{workflow} must keep workflow name {workflow_name!r}")
    if set(data["on"]["pull_request"]["types"]) != {"opened", "synchronize", "reopened", "edited"}:
        raise SystemExit(f"{workflow} must still trigger on edited so base retargets rerun the gate")
    expected_title = (
        f"{workflow_name} ${{{{ github.event_name == 'pull_request' && format('pr-{{0}} base-{{1}} head-{{2}}', "
        "github.event.pull_request.number, github.event.pull_request.base.sha, github.event.pull_request.head.sha) || github.ref_name }}"
    )
    if workflow in {"codeql.yml", "license-scan.yml"}:
        expected_title += f"${{{{ {META} && ' mode-metadata' || '' }}}}"
    if data.get("run-name") != expected_title:
        raise SystemExit(f"{workflow} must publish its canonical pull-request run title")
    if data["jobs"]["changes"].get("name") != change_job:
        raise SystemExit(f"{workflow} change detection job must stay {change_job!r}")

    gate = data["jobs"][gate_job]
    if gate.get("name") != f"${{{{ {META} && 'PR Metadata Edit ({gate_name})' || '{gate_name}' }}}}":
        raise SystemExit(f"{workflow} {gate_name} must take a non-required name on metadata-only edits")
    expected_if = (
        f"${{{{ always() && github.event_name == 'pull_request' && {PR_SUBSTANTIVE} }}}}"
        if pr_only else f"${{{{ always() && {SUBSTANTIVE} }}}}"
    )
    if gate.get("if") != expected_if:
        raise SystemExit(f"{workflow} {gate_name} must skip only metadata-only edits: {expected_if}")
    # Metadata edits skip; opens, pushes, reopens and base retargets run.
    if evaluate(gate["if"], "edited", None):
        raise SystemExit(f"{workflow} {gate_name} must skip a title/body edit")
    for action, base_ref in (("edited", "develop"), ("opened", None), ("synchronize", None), ("reopened", None)):
        if not evaluate(gate["if"], action, base_ref):
            raise SystemExit(f"{workflow} {gate_name} must run for {action} (base change={base_ref})")
    if not pr_only and not evaluate(gate["if"], None, None, event="push"):
        raise SystemExit(f"{workflow} {gate_name} must still run on push")
    needs = gate.get("needs")
    if "changes" not in (needs if isinstance(needs, list) else [needs]):
        raise SystemExit(f"{workflow} {gate_name} must depend on change detection")
    compatibility = [step for step in gate.get("steps", []) if step.get("name") == "Preserve successful required gate on metadata-only edit"]
    expected_compatibility = [{"name": "Preserve successful required gate on metadata-only edit", "if": "${{ !always() }}", "run": ":"}] if workflow == "ci.yml" else []
    if compatibility != expected_compatibility:
        raise SystemExit(f"{workflow} must retain only the inert CI callback compatibility marker")
    result_steps = [step for step in gate.get("steps", []) if step.get("name") == result_step]
    if len(result_steps) != 1 or result_steps[0].get("env", {}).get("CHANGES_RESULT") != "${{ needs.changes.result }}":
        raise SystemExit(f"{workflow} {gate_name} must enforce through one {result_step!r} step reading CHANGES_RESULT")
    if result_steps[0].get("continue-on-error", "false") != "false":
        raise SystemExit(f"{workflow} {gate_name} result step cannot ignore failures")
    if "if" in result_steps[0]:
        raise SystemExit(f"{workflow} {gate_name} result step must run unconditionally")
    if workflow == "codeql.yml":
        step = result_steps[0]
        if step["env"].get("SHOULD_RUN") != "${{ needs.changes.outputs.should_run }}" or \
           step["env"].get("ANALYZE_RESULT") != "${{ needs.analyze.result }}":
            raise SystemExit("CodeQL enforcement must use actual detector and analyzer results")
        for changes, should_run, analyze, success in (
            ("success", "true", "failure", False),
            ("success", "true", "success", True),
            ("success", "false", "skipped", True),
            ("failure", "false", "skipped", False),
            ("success", "", "skipped", False),
        ):
            outcome = subprocess.run(["bash", "-e", "-c", step["run"]], check=False, capture_output=True,
                                     env={**os.environ, "CHANGES_RESULT": changes,
                                          "SHOULD_RUN": should_run, "ANALYZE_RESULT": analyze})
            if (outcome.returncode == 0) != success:
                raise SystemExit(f"CodeQL enforcement returned {outcome.returncode} for {changes}/{should_run}/{analyze}")
    if any("if" in step and "edited" in step["if"] for step in gate.get("steps", [])):
        raise SystemExit(f"{workflow} {gate_name} steps must not branch on edits; the job condition owns that")

    # Every other job also stays idle for metadata-only edits, and none of
    # them may hold a required gate name.
    for job_id, job in data["jobs"].items():
        if job_id == gate_job:
            continue
        condition = job.get("if", "")
        if not excludes_metadata(condition):
            raise SystemExit(f"{workflow} job {job_id} can run for a metadata-only edit")
        if can_reuse_required_name(job.get("name", job_id), {name for _, _, name, *_ in gates.values()}):
            raise SystemExit(f"{workflow} job {job_id} has a possible required gate name collision")

    # Metadata runs never cancel or replace the substantive run.
    concurrency = data["concurrency"]
    group = concurrency["group"]
    suffix = "format('metadata-{0}', github.run_id) || 'required'" if run_scoped else "&& 'metadata' || 'required'"
    if "github.event.pull_request.number" not in group or META.split(" && ", 1)[1] not in group or suffix not in group:
        raise SystemExit(f"{workflow} concurrency group must keep metadata runs out of the PR's required namespace")
    if not isolates_metadata_group(group, run_scoped):
        raise SystemExit(f"{workflow} effective metadata and substantive groups must differ")
    # The old substring guard would accept this expression despite its bypass.
    bypassed = group.replace(META, "true && 'required' || " + META, 1)
    assert bypassed != group and META in bypassed and suffix in bypassed
    assert effective_group(bypassed, "edited", None) == effective_group(bypassed, "edited", "develop"), workflow
    assert effective_group(bypassed, "edited", None).endswith("-required"), workflow
    assert not isolates_metadata_group(bypassed, run_scoped), workflow
    collided = group.replace(META, f"github.event.action == 'synchronize' || {META}", 1)
    assert collided != group and not isolates_metadata_group(collided, run_scoped), workflow
    if concurrency.get("cancel-in-progress") != "true":
        raise SystemExit(f"{workflow} must cancel obsolete runs within each namespace")

rendered_substantive_groups = []
for workflow, (workflow_name, *_rest) in gates.items():
    data = yaml.load((root / ".github/workflows" / workflow).read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    group = data["concurrency"]["group"]
    if data.get("name") != workflow_name:
        raise SystemExit(f"{workflow} actual name must match its required-gate contract")
    for action, base_ref in (("opened", None), ("synchronize", None), ("reopened", None), ("edited", "develop")):
        rendered_substantive_groups.append((
            workflow,
            action,
            effective_group(group, action, base_ref, workflow_name),
        ))

collisions = cross_workflow_group_collisions(rendered_substantive_groups)
if collisions:
    first, second = collisions[0]
    raise SystemExit(
        f"required workflows {first[0]} and {second[0]} share a substantive concurrency group "
        f"for the same PR (case-insensitive): {first[2]!r} / {second[2]!r}"
    )
for branch in ("main", "develop"):
    push_groups = []
    for workflow in ("ci.yml", "security.yml"):
        data = yaml.load((root / ".github/workflows" / workflow).read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
        push_groups.append((
            workflow,
            "push",
            effective_group(data["concurrency"]["group"], None, None, data["name"], push_branch=branch),
        ))
    if cross_workflow_group_collisions(push_groups):
        raise SystemExit(f"Security and Validation share a push concurrency group on {branch}")
assert cross_workflow_group_collisions([
    ("validation.yml", "opened", "required-pr-42"),
    ("smoke.yml", "edited", "REQUIRED-PR-42"),
]), "the collision detector must model GitHub's case-insensitive group comparison"
PY

# shellcheck disable=SC2016 # Match the checked-in arithmetic assignment literally.
CODEQL_WORKFLOW="$ROOT_DIR/.github/workflows/codeql.yml"
OVERLAY_WORKFLOW="$ROOT_DIR/.github/workflows/validate-kustomize-overlays.yml"

grep -Fq 'needs: [changes, analyze]' "$CODEQL_WORKFLOW" || {
  echo "CodeQL gate must depend directly on change detection" >&2
  exit 1
}
# The structured caller check above requires CodeQL Gate for every PR base.
grep -Fq 'types: [opened, synchronize, reopened, edited]' "$OVERLAY_WORKFLOW" || {
  echo "Overlay validation must rerun when a pull request base is edited" >&2
  exit 1
}
grep -Fq "github.actor != 'dependabot[bot]' && (github.event_name != 'pull_request' || github.event.action != 'edited' || github.event.changes.base.ref != null)" "$OVERLAY_WORKFLOW" || {
  echo "Overlay validation must skip metadata-only edits without replacing the required context" >&2
  exit 1
}
grep -Fq "name: \${{ github.event_name == 'pull_request' && github.event.action == 'edited' && github.event.changes.base.ref == null && 'PR Metadata Edit (validate-overlays)' || 'validate-overlays' }}" "$OVERLAY_WORKFLOW" || {
  echo "Overlay validation must isolate metadata-only edits from its optional context" >&2
  exit 1
}

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

# The Smoke consumer must outlive its producer and still terminate fail closed.
smoke_clock_fixture="$tmp_dir/smoke-clock.js"
python3 - "$ROOT_DIR" "$smoke_clock_fixture" <<'PY'
from pathlib import Path
import base64
import hashlib
import json
import re
import sys
import yaml

root = Path(sys.argv[1])
smoke = yaml.load((root / ".github/workflows/smoke.yml").read_text(), Loader=yaml.BaseLoader)
producer = yaml.load((root / ".github/workflows/runtime-images.yml").read_text(), Loader=yaml.BaseLoader)
job = smoke["jobs"]["smoke-gate"]
script = next(step["with"]["script"] for step in job["steps"] if step.get("name") == "Track full-stack smoke result from Build Runtime Images")
producer_minutes = int(re.search(r"const producerTimeoutMinutes = ([0-9]+);", script)[1])
overhead_minutes = int(re.search(r"const prerequisiteAndQueueAllowanceMinutes = ([0-9]+);", script)[1])
if producer_minutes != int(producer["jobs"]["pr-local-smoke"]["timeout-minutes"]):
    raise SystemExit("Smoke polling producer budget must match the actual full-stack producer timeout")
if overhead_minutes != 45 or int(job["timeout-minutes"]) < producer_minutes + overhead_minutes + 5:
    raise SystemExit("Smoke polling must retain bounded scheduling overhead and five-minute job margin")
if "const timeoutMs = (producerTimeoutMinutes + prerequisiteAndQueueAllowanceMinutes) * 60 * 1000;" not in script:
    raise SystemExit("Smoke polling deadline must use the checked producer and overhead authorities")
fingerprint = re.search(r'const canonicalRuntimeWorkflowSha256 = "([0-9a-f]{64})";', script)
expected = hashlib.sha256((root / ".github/workflows/runtime-images.yml").read_bytes()).hexdigest()
if fingerprint is None or fingerprint[1] != expected:
    raise SystemExit(
        "Smoke Gate producer fingerprint is stale: after reviewing runtime-images.yml, update "
        f"canonicalRuntimeWorkflowSha256 in smoke.yml to {expected} and rerun this contract."
    )
classifier_paths = (
    "dev-tools/verify-smoke-images.sh",
    "dev-tools/smoke/smoke_common.py",
    "services/game-session-service/websocket-login-look-smoke.sh",
    "dev-tools/smoke/demo-smoke-defaults.sh",
    "dev-tools/smoke/held-admission-qualification.sh",
    "dev-tools/smoke/held_admission_qualification.py",
    "dev-tools/smoke/run-owned-compose.sh",
    "dev-tools/certs/ensure-dev-certs.sh",
    ".env.sample",
    "docker/docker-compose.yml",
    "docker/docker-compose.override.yml",
    "docker/docker-compose.smoke-images.override.yml",
    "docker/docker-compose.pr-local-minio.override.yml",
    "docker/docker-compose.grpc-mtls.override.yml",
)
classifier_fingerprints = re.search(r"const canonicalPlayClassifierSha256 = (\{[\s\S]*?\});", script)
if classifier_fingerprints is None:
    raise SystemExit("Smoke Gate must fingerprint the canonical PLAY classifier files")
classifiers = json.loads(classifier_fingerprints[1])
if set(classifiers) != set(classifier_paths):
    raise SystemExit("Smoke Gate PLAY classifier fingerprint paths must match the canonical classifier, qualification, cleanup, and configuration inputs")
classifier_definitions = {}
for path in classifier_paths:
    classifier_bytes = (root / path).read_bytes()
    expected = hashlib.sha256(classifier_bytes).hexdigest()
    if classifiers[path] != expected:
        raise SystemExit(
            f"Smoke Gate PLAY classifier fingerprint is stale: after reviewing {path}, update "
            f"canonicalPlayClassifierSha256 in smoke.yml to {expected} for that path and rerun this contract."
        )
    classifier_definitions[path] = {
        "type": "file", "path": path, "encoding": "base64",
        "content": base64.b64encode(classifier_bytes).decode("ascii"), "size": len(classifier_bytes),
    }
Path(sys.argv[2] + ".classifiers.json").write_text(json.dumps(classifier_definitions))
producer_job = producer["jobs"]["pr-local-smoke"]
if producer["jobs"]["image-meta"]["outputs"].get("canonical_play_transition") != "${{ steps.meta.outputs.canonical_play_transition }}":
    raise SystemExit("Typed-refresh PLAY marker authority must come from verified image metadata")
cancelled_inventory = re.search(
    r"const preExecutionCancelledJobNames = new Set\(\[([\s\S]*?)\]\);", script
)
if cancelled_inventory is None:
    raise SystemExit("Smoke Gate must declare its bounded pre-execution cancellation inventory")
producer_job_names = [job["name"] for job in producer["jobs"].values()]
Path(sys.argv[2] + ".cancelled-job-names.json").write_text(json.dumps(producer_job_names))
if producer_job["name"] != "PR Full-Stack Smoke (runtime-required=${{ needs.image-meta.outputs.runtime_smoke_required }})":
    raise SystemExit("Native producer name must affirm runtime smoke scope")
steps = producer_job["steps"]
smoke_step = next(step for step in steps if step.get("id") == "runtime-smoke")
cleanup_step = next(step for step in steps if step.get("id") == "runtime-cleanup")
guard = steps[-1]
if smoke_step.get("continue-on-error") != "true":
    raise SystemExit("Probe failure must reach the exact transition terminal guard")
if any(step.get("continue-on-error") not in (None, "false") for step in (cleanup_step, guard)):
    raise SystemExit("Cleanup and terminal enforcement cannot ignore failures")
for name in ("Export fixed-tag preview image artifact", "Upload preview image artifact", "Summarize secure PR runtime artifact"):
    artifact = next(step for step in steps if step.get("name") == name)
    if artifact.get("if") != "${{ success() && (needs.image-meta.outputs.runtime_smoke_required == 'false' || steps.runtime-smoke.outcome == 'success') }}":
        raise SystemExit("Failed probe must never export, upload or summarize publishable images")
if smoke_step["run"] != "bash ./dev-tools/verify-smoke-images.sh --keep-running":
    raise SystemExit("Native smoke outcome must belong to the canonical smoke invocation")
if guard["if"] != "${{ always() }}" or guard["name"] != "Require successful runtime smoke and authenticated cleanup":
    raise SystemExit("Producer must finish with its mandatory always terminal guard")
if guard["env"] != {
    "RUNTIME_SMOKE_REQUIRED": "${{ needs.image-meta.outputs.runtime_smoke_required }}",
    "RUNTIME_SMOKE_OUTCOME": "${{ steps.runtime-smoke.outcome }}",
    "RUNTIME_CLEANUP_OUTCOME": "${{ steps.runtime-cleanup.outcome }}",
    "KNOWN_PLAY_EXCEPTION_OUTCOME": "${{ steps.canonical-play-transition.outcome }}",
}:
    raise SystemExit("Terminal guard must consume native Actions outcomes, never command-written outputs")
Path(sys.argv[2] + ".guard").write_text(guard["with"]["script"])
marker = next(step for step in steps if step.get("name") == "Known canonical transition PLAY failure with successful authenticated cleanup")
for clause in (
    "always()", "github.event_name == 'pull_request'", "github.repository == 'benhook1013/FireMUD'",
    "contains(github.event.pull_request.labels.*.name, 'canonical-play-transition')", "needs.image-meta.outputs.runtime_smoke_required == 'true'",
    "github.event_name == 'repository_dispatch'", "github.event.action == 'pr-runtime-base-refresh'",
    "needs.image-meta.outputs.canonical_play_transition == 'true'",
    "steps.runtime-smoke.outcome == 'failure'", "steps.runtime-cleanup.outcome == 'success'",
    "steps.runtime-smoke.outputs.known_play_failure == 'direct-backend-play-entitlement-unavailable-after-login'",
):
    if clause not in marker["if"]:
        raise SystemExit(f"Temporary PLAY marker lacks fail-closed clause: {clause}")
Path(sys.argv[2] + ".marker").write_text(marker["if"])
Path(sys.argv[2] + ".producer").write_bytes((root / ".github/workflows/runtime-images.yml").read_bytes())
Path(sys.argv[2]).write_text(script)
PY

node - "$smoke_clock_fixture" <<'NODE'
const assert = require("node:assert/strict");
const script = require("node:fs").readFileSync(process.argv[2], "utf8");
const head = "a".repeat(40), base = "b".repeat(40), merge = "c".repeat(40);
const title = `Build Runtime Images secure-pr-artifact pr-42 base-${base} head-${head} merge-${merge} mode-required`;
const cancellationInventoryBody = script.match(/const preExecutionCancelledJobNames = new Set\(\[([\s\S]*?)\]\);/)[1];
const cancellationInventory = new Function(`return [${cancellationInventoryBody}];`)();
const canonicalCancellationInventory = JSON.parse(require("node:fs").readFileSync(process.argv[2] + ".cancelled-job-names.json", "utf8"));
assert.equal(cancellationInventory.length, 13);
assert.deepEqual([...cancellationInventory].sort(), canonicalCancellationInventory.sort(),
  "bounded cancellation inventory must match all canonical runtime workflow job names");
const context = { repo: { owner: "example", repo: "firemud" }, sha: merge,
  payload: { pull_request: { number: 42, head: { sha: head, repo: {full_name: "example/firemud"}},
    base: { ref: "develop", sha: base }, created_at: "2026-09-01T00:00:00Z" } } };
const execute = new Function("github", "context", "core", "Date", "setTimeout", `return (async () => {\n${script}\n})()`);
async function scenario(outcome, completedAtMinutes, selectionCase = "single") {
  let now = 1000;
  const start = now;
  const failures = [];
  const cancelledReads = new Set();
  const listRuns = async () => {}, listJobs = async () => {};
  const cancelledSource = runId => ({id: runId, workflow_id: 7, run_attempt: 1, event: "pull_request",
    status: "completed", conclusion: "cancelled", path: ".github/workflows/runtime-images.yml",
    repository: {full_name: "example/firemud"}, head_repository: {full_name: "example/firemud"},
    head_sha: head, display_title: title});
  const cancellationJobs = runId => {
    if (selectionCase === "cancelled-empty-inventory") return [];
    const names = selectionCase === "cancelled-partial-inventory" ? cancellationInventory.slice(1) : cancellationInventory;
    return names.map((name, index) => ({id: runId * 100 + index + 1, run_id: runId,
      name,
      status: "completed", conclusion: selectionCase === "cancelled-unknown-outcome" && index === 0
        ? "failure" : index % 2 === 0 ? "cancelled" : "skipped",
      steps: selectionCase === "cancelled-executed" && index === 0 ? [{name: "executed"}] : []}));
  };
  const github = { rest: {
    pulls: { get: async () => ({ data: { state: "open", head: { sha: head }, base: { ref: "develop", sha: base }, merge_commit_sha: merge } }) },
    repos: { getCommit: async () => ({ data: { sha: merge, parents: [{ sha: base }, { sha: head }] } }) },
    actions: { listWorkflowRuns: listRuns, listJobsForWorkflowRun: listJobs,
      getWorkflow: async () => ({data: {id: 7, path: ".github/workflows/runtime-images.yml", name: "Build Runtime Images"}}),
      getWorkflowRun: async ({run_id}) => ({data: {id: run_id,
        run_attempt: selectionCase === "cancelled-changed-attempt" ? 2 : 1,
        status: "completed", conclusion: cancelledReads.has(run_id) ? "cancelled" : "success"}}) },
  }, request: async (route, input) => {
    assert.equal(route, "GET /repos/{owner}/{repo}/actions/runs/{run_id}/attempts/{attempt_number}");
    assert.equal(input.attempt_number, 1);
    cancelledReads.add(input.run_id);
    return {data: cancelledSource(input.run_id)};
  }, paginate: async (method, input) => {
    assert.equal(input.attempt_number, 1);
    if (!cancelledReads.has(input.run_id)) {
      return [{ id: input.run_id + 100, run_id: input.run_id, name: "PR Full-Stack Smoke", status: "completed", conclusion: "success",
        steps: [{ name: "Run credential-free full-stack smoke", status: "completed", conclusion: "success" }] }];
    }
    assert.equal(method, "GET /repos/{owner}/{repo}/actions/runs/{run_id}/attempts/{attempt_number}/jobs");
    return cancellationJobs(input.run_id);
  } };
  github.paginate.iterator = async function* (method, input) {
    assert.equal(method, listRuns);
    assert.equal(input.workflow_id, "runtime-images.yml");
    if (input.event === "repository_dispatch") { yield { data: [] }; return; }
    assert.equal(input.head_sha, head);
    const complete = now - start >= completedAtMinutes * 60 * 1000;
    const makeRun = (id, createdAt, status, conclusion) => ({id, workflow_id: 7, run_attempt: 1,
      event: "pull_request", head_sha: head, display_title: title, created_at: createdAt,
      pull_requests: [{number: 42, head: {sha: head}}], status, conclusion});
    const active = makeRun(42, "2026-09-01T00:00:00Z", complete ? "completed" : "in_progress", complete ? outcome : null);
    const cancelled = makeRun(43, "2026-09-01T00:00:00Z", "completed", "cancelled");
    if (selectionCase === "duplicate" || selectionCase === "cancelled-higher-id") {
      yield { data: selectionCase === "duplicate" ? [
        {...active, status: "completed", conclusion: "cancelled"}, {...active, id: 43},
      ] : [cancelled, active] };
      return;
    }
    if (selectionCase === "newer-failure-over-success") {
      yield {data: [
        makeRun(42, "2026-09-01T00:00:00Z", "completed", "success"),
        makeRun(43, "2026-09-01T00:00:01Z", "completed", "failure"),
      ]};
      return;
    }
    if (selectionCase === "newer-pending-over-success") {
      yield {data: [
        makeRun(42, "2026-09-01T00:00:00Z", "completed", "success"),
        makeRun(43, "2026-09-01T00:00:01Z", complete ? "completed" : "in_progress", complete ? outcome : null),
      ]};
      return;
    }
    if (["cancelled-executed", "cancelled-partial-inventory", "cancelled-empty-inventory",
      "cancelled-unknown-outcome", "cancelled-changed-attempt", "cancelled-older-success"].includes(selectionCase)) {
      yield {data: [
        makeRun(42, "2026-09-01T00:00:00Z", "completed", "success"),
        makeRun(43, "2026-09-01T00:00:01Z", "completed", "cancelled"),
      ]};
      return;
    }
    if (selectionCase === "wrong-tuple-event") {
      yield { data: [
        {...active, id: 99, event: "push", status: "completed", conclusion: "failure"},
        {...active, id: 98, head_sha: "f".repeat(40), status: "completed", conclusion: "failure"},
        active,
      ] };
      return;
    }
    yield { data: [makeRun(42, "2026-09-01T00:00:00Z",
      complete ? "completed" : "in_progress", complete ? outcome : null)] };
  };
  const core = { info: () => {}, warning: () => {}, setFailed: message => failures.push(message) };
  const clock = { now: () => now, parse: Date.parse };
  const sleep = (callback, duration) => { now += duration; queueMicrotask(callback); };
  await execute(github, context, core, clock, sleep);
  return { failures, minutes: (now - start) / 60000 };
}
(async () => {
  const success = await scenario("success", 30);
  assert.deepEqual(success.failures, [], "exact producer success after 25 minutes must be admitted");
  assert.equal(success.minutes, 30);
  const failed = await scenario("failure", 30);
  assert.equal(failed.failures.length, 1);
  assert.match(failed.failures[0], /did not complete successfully/);
  assert.equal(failed.minutes, 30, "terminal failure must stop polling promptly");
  const replacement = await scenario("success", 1, "duplicate");
  assert.deepEqual(replacement.failures, [], "newer same-time exact run must replace older cancelled run despite API order");
  assert.equal(replacement.minutes, 1, "newer in-progress run must be tracked until it succeeds");
  const oppositeIdOrdering = await scenario("success", 0, "cancelled-higher-id");
  assert.deepEqual(oppositeIdOrdering.failures, [], "pre-execution cancellation must not hide a same-time lower-ID active run");
  const newerPending = await scenario("success", 1, "newer-pending-over-success");
  assert.deepEqual(newerPending.failures, [], "newer pending run must remain authoritative over older success until it completes");
  assert.equal(newerPending.minutes, 1);
  const newerFailure = await scenario("failure", 0, "newer-failure-over-success");
  assert.equal(newerFailure.failures.length, 1);
  assert.match(newerFailure.failures[0], /did not complete successfully/,
    "newer exact failure must remain authoritative over older success");
  for (const kind of ["cancelled-executed", "cancelled-partial-inventory", "cancelled-empty-inventory",
    "cancelled-unknown-outcome", "cancelled-changed-attempt"]) {
    const unsafeCancellation = await scenario("success", 0, kind);
    assert.equal(unsafeCancellation.failures.length, 1, `${kind} cannot be excluded to reveal older success`);
    assert.ok(unsafeCancellation.minutes <= 5.25, `${kind} remains bounded while replacement is absent`);
  }
  const olderSuccess = await scenario("success", 0, "cancelled-older-success");
  assert.deepEqual(olderSuccess.failures, [], "only a complete no-step cancellation may be excluded from authority");
  const ignoredDistractors = await scenario("success", 0, "wrong-tuple-event");
  assert.deepEqual(ignoredDistractors.failures, [], "wrong-event and wrong-head runs must not displace the exact run");
  const cancelled = await scenario("cancelled", 0);
  assert.equal(cancelled.failures.length, 1);
  assert.match(cancelled.failures[0], /cancelled/i);
  assert.ok(cancelled.minutes <= 5.25, "cancelled source without an exact replacement remains bounded");
  const deadline = await scenario("success", Infinity);
  assert.equal(deadline.failures.length, 1);
  assert.match(deadline.failures[0], /Timed out waiting for the exact/);
  assert.equal(deadline.minutes, 90, "active producers cannot escape the total deadline");
  console.log("Smoke clock contract passed: late success, prompt failure, bounded cancellation and deadline");
})().catch(error => { console.error(error); process.exitCode = 1; });
NODE

node - "$smoke_clock_fixture" "$ROOT_DIR/.github/workflows/runtime-images.yml" <<'NODE'
const assert = require("node:assert/strict");
const fs = require("node:fs");
const script = fs.readFileSync(process.argv[2], "utf8");
const definition = fs.readFileSync(process.argv[3]);
const guard = new Function("process", "core", fs.readFileSync(process.argv[2] + ".guard", "utf8"));
for (const smoke of ["success", "failure", "skipped", "cancelled", "unknown", ""]) {
  for (const cleanup of ["success", "failure", "skipped", "cancelled", "unknown", ""]) {
    const failures = [];
    guard({env: {RUNTIME_SMOKE_REQUIRED: "true", RUNTIME_SMOKE_OUTCOME: smoke, RUNTIME_CLEANUP_OUTCOME: cleanup}},
      {setFailed: message => failures.push(message)});
    assert.equal(failures.length === 0, smoke === "success" && cleanup === "success");
  }
}
for (const required of ["false", "", "unknown"]) {
  const failures = [];
  guard({env: {RUNTIME_SMOKE_REQUIRED: required}}, {setFailed: message => failures.push(message)});
  assert.equal(failures.length === 0, required === "false");
}
for (const smoke of ["success", "failure", "skipped", "cancelled", ""]) {
  for (const cleanup of ["success", "failure", "skipped", "cancelled", ""]) {
    for (const marker of ["success", "failure", "skipped", ""]) {
      const failures = [], warnings = [];
      guard({env: {RUNTIME_SMOKE_REQUIRED: "true", RUNTIME_SMOKE_OUTCOME: smoke,
        RUNTIME_CLEANUP_OUTCOME: cleanup, KNOWN_PLAY_EXCEPTION_OUTCOME: marker}},
        {setFailed: message => failures.push(message), warning: message => warnings.push(message)});
      const exception = smoke === "failure" && marker === "success" && cleanup === "success";
      assert.equal(failures.length === 0, cleanup === "success" && (smoke === "success" || exception));
      assert.equal(warnings.length, Number(exception));
    }
  }
}
const execute = new Function("github", "context", "core", "Date", "setTimeout", "require", `return (async () => {\n${script}\n})()`);
const head = "a".repeat(40), base = "b".repeat(40), merge = "c".repeat(40), defaultSha = "d".repeat(40);
const repository = "example/firemud";
const title = `Build Runtime Images secure-pr-artifact pr-42 base-${base} head-${head} merge-${merge} mode-required`;
async function scenario(kind, event = "pull_request", captureLookupFailure = false) {
  let now = 1000, snapshots = 0, reads = 0, currentRunReads = 0, refreshInventoryReads = 0;
  const liveFallback = kind.startsWith("trusted-current-base") || kind === "optional-final-acceptance-transient";
  const h0 = kind.startsWith("optional-final-read-");
  const cancelledPrimary = kind === "optional-final-read-cancelled-primary";
  const cancelledWithReplacement = kind === "optional-final-read-cancelled-with-replacement";
  const finalAttemptError = kind.startsWith("optional-final-read-attempt-");
  const duplicatePrimary = kind === "optional-final-read-duplicate";
  const disappearingSnapshot = kind === "optional-final-read-incomplete-snapshot";
  const badStatus = kind.endsWith("-forbidden") ? 403 : kind.endsWith("-missing") ? 404 : 503;
  const primaryCanRecover = h0 && !cancelledPrimary && !cancelledWithReplacement && badStatus === 503;
  const primaryReady = () => primaryCanRecover && primaryInventoryReads > 1;
  let alternativeJobsReads = 0, primaryInventoryReads = 0, lookupFailure;
  let pullRequestLookups = 0, acceptanceLookupArmed = false, acceptanceLookupAttempts = 0;
  const nextBase = "e".repeat(40), nextMerge = "f".repeat(40);
  const tupleAdvanced = () => kind === "trusted-current-base-advances" && currentRunReads >= 2;
  let rerunStartedDuringLatestIdentityRead = false;
  const failures = [], refs = [], info = [];
  const context = {repo: {owner: "example", repo: "firemud"}, sha: liveFallback ? null : merge, payload: {
    repository: {default_branch: "develop"},
    pull_request: {number: 42, head: {sha: head, repo: {full_name: repository}},
      base: {ref: "develop", sha: base}, created_at: "2026-09-01T00:00:00Z"},
  }};
  const source = {id: 42, workflow_id: 7, run_attempt: 1, repository: {full_name: repository},
    created_at: "2026-09-01T00:00:00Z",
    head_repository: {full_name: repository}, path: ".github/workflows/runtime-images.yml",
    event, head_sha: event === "pull_request" ? head : defaultSha, head_branch: "develop", display_title: title,
    status: "completed", conclusion: "success", pull_requests: []};
  const exactPrimary = {...source, id: 43, event: "pull_request", head_sha: head,
    created_at: "2026-09-01T00:00:01Z"};
  const dispatchPrimary = {...source, id: 44, created_at: "2026-09-01T00:00:02Z"};
  const incompleteSnapshot = kind.startsWith("incomplete-snapshot-");
  let snapshotRerunVisible = false;
  const job = {id: 100, run_id: 42, run_attempt: 1, head_sha: source.head_sha,
    check_run_url: `https://api.github.com/repos/${repository}/check-runs/100`,
    name: "PR Full-Stack Smoke (runtime-required=true)", status: "completed", conclusion: "success",
    steps: [{name: "Known canonical transition PLAY failure with successful authenticated cleanup", status: "completed", conclusion: "skipped"}]};
  if (kind === "marker-missing") job.steps = [];
  if (kind === "marker-success") job.steps[0].conclusion = "success";
  if (kind.startsWith("legacy")) job.name = "PR Full-Stack Smoke";
  if (kind === "not-required") job.name = "PR Full-Stack Smoke (runtime-required=false)";
  if (kind === "duplicate-nonterminal-step") job.steps.push({name: "Run credential-free full-stack smoke", status: "in_progress", conclusion: null});
  if (["duplicate-terminal-skipped", "duplicate-terminal-failure"].includes(kind)) job.steps.push({name: "Run credential-free full-stack smoke", status: "completed", conclusion: kind.endsWith("failure") ? "failure" : "skipped"});
  if (kind === "pending-native-refresh-failed") source.conclusion = "failure";
  if (kind === "pending-native-refresh-skipped") job.steps.push({name: "Run credential-free full-stack smoke", status: "completed", conclusion: "skipped"});
  if (kind === "step-skipped") job.steps = [{name: "Run credential-free full-stack smoke", status: "completed", conclusion: "skipped"}];
  if (kind === "legacy-positive") job.steps = [{name: "Run credential-free full-stack smoke", status: "completed", conclusion: "success"}];
  const sourceMutations = {
    "wrong-repository": {repository: {full_name: "other/repo"}},
    "wrong-workflow": {workflow_id: 8}, "wrong-path": {path: ".github/workflows/fake.yml"},
    "wrong-event": {event: "push"}, "wrong-head": {head_sha: "e".repeat(40)},
    "wrong-head-repository": {head_repository: {full_name: "other/repo"}},
    "wrong-title": {display_title: title + " unrelated"}, "wrong-source-attempt": {run_attempt: 2},
    "wrong-dispatch-branch": {head_branch: "feature"},
  };
  const listRuns = async () => {}, listJobs = async () => {};
  const github = {rest: {
    pulls: {get: async () => {
      reads += 1;
      pullRequestLookups += 1;
      if (kind === "optional-final-acceptance-transient" && pullRequestLookups === 4) {
        acceptanceLookupArmed = true;
      }
      if (kind === "trusted-current-base-attempt-advances" && currentRunReads >= 2) {
        rerunStartedDuringLatestIdentityRead = true;
      }
      return {data: {state: "open", head: {sha: head}, base: {ref: kind === "stale-final" && reads > 3 ? "other" : "develop", sha: tupleAdvanced() ? nextBase : base}, merge_commit_sha: tupleAdvanced() ? nextMerge : merge}};
    }},
    git: {getRef: async () => ({data: {object: {sha: tupleAdvanced() ? nextBase : base}}})},
    repos: {
      getCommit: async ({ref}) => ({data: {sha: ref, parents: [{sha: kind === "bad-parents" ? defaultSha : ref === nextMerge ? nextBase : base}, {sha: head}]}}),
      getContent: async ({ref, path}) => {
        refs.push(ref);
        assert.equal(path, source.path);
        if (kind === "unavailable-definition") throw Object.assign(new Error("definition unavailable"), {status: 404});
        const bytes = kind === "changed-definition" ? Buffer.concat([definition, Buffer.from("\n# altered")]) : definition;
        return {data: {type: "file", path, encoding: "base64", content: bytes.toString("base64"), size: bytes.length}};
      },
    },
    actions: {
      listWorkflowRuns: listRuns, listJobsForWorkflowRun: listJobs,
      getWorkflow: async () => ({data: {id: 7, path: source.path, name: "Build Runtime Images"}}),
      getWorkflowRun: async ({run_id: runId}) => {
        if (runId === 43 && primaryReady()) return {data: exactPrimary};
        if (runId === 44 && cancelledWithReplacement) return {data: dispatchPrimary};
        if (kind === "optional-final-acceptance-transient" && runId === 44) {
          return {data: {...source, id: 44, event: "repository_dispatch", head_sha: defaultSha,
            head_branch: "develop", status: "completed", conclusion: "success"}};
        }
        if (kind === "optional-final-acceptance-transient" && acceptanceLookupArmed) {
          acceptanceLookupAttempts += 1;
          if (acceptanceLookupAttempts === 4) acceptanceLookupArmed = false;
          throw Object.assign(new Error("final current-base workflow-run lookup unavailable"), {status: 503});
        }
        currentRunReads += 1;
        if (finalAttemptError && currentRunReads >= 2) {
          throw Object.assign(new Error("alternative final workflow-run unavailable"),
            kind.endsWith("-network") ? {cause: {code: "ECONNRESET"}} : {status: badStatus});
        }
        if (incompleteSnapshot) {
          snapshotRerunVisible = kind === "incomplete-snapshot-superseded";
          return {data: {...source, run_attempt: snapshotRerunVisible ? 2 : 1}};
        }
        const superseded = kind === "newer-attempt" || rerunStartedDuringLatestIdentityRead || (kind === "rerun-after-proof" && currentRunReads > 1);
        return {data: {...source, run_attempt: superseded ? 2 : 1, status: superseded ? "in_progress" : "completed"}};
      },
    },
  }, request: async (route, input) => {
    assert.equal(route, "GET /repos/{owner}/{repo}/actions/runs/{run_id}/attempts/{attempt_number}");
    if (input.run_id === 43 && primaryReady()) {
      assert.equal(input.attempt_number, exactPrimary.run_attempt);
      return {data: exactPrimary};
    }
    if (kind === "optional-final-acceptance-transient" && input.run_id === 44) {
      return {data: {...source, id: 44, event: "repository_dispatch", head_sha: defaultSha,
        head_branch: "develop", status: "completed", conclusion: "success"}};
    }
    if (cancelledWithReplacement && input.run_id === 44) {
      assert.equal(input.attempt_number, dispatchPrimary.run_attempt);
      return {data: dispatchPrimary};
    }
    if ((cancelledPrimary || cancelledWithReplacement) && input.run_id === 43) {
      assert.equal(input.attempt_number, exactPrimary.run_attempt);
      return {data: {...source, id: 43, event: "pull_request", status: "completed", conclusion: "cancelled"}};
    }
    assert.equal(input.run_id, 42); assert.equal(input.attempt_number, 1);
    return {data: {...source, ...(sourceMutations[kind] || {})}};
  }, paginate: async (method, input) => {
    if (method === listJobs) { snapshots += 1; return kind === "duplicate-jobs" ? [job, {...job, id: 101}] : [job]; }
    assert.equal(method, "GET /repos/{owner}/{repo}/actions/runs/{run_id}/attempts/{attempt_number}/jobs");
    if (input.run_id === 43 && primaryReady()) {
      assert.equal(input.attempt_number, exactPrimary.run_attempt);
      return [{...job, run_id: 43, head_sha: head, steps: [...job.steps,
        {name: "Run credential-free full-stack smoke", status: "completed", conclusion: "success"}]}];
    }
    if (kind === "optional-final-acceptance-transient" && input.run_id === 44) {
      return [{...job, run_id: 44, head_sha: defaultSha, steps: [...job.steps,
        {name: "Run credential-free full-stack smoke", status: "completed", conclusion: "success"}]}];
    }
    if (cancelledWithReplacement && input.run_id === 44) {
      assert.equal(input.attempt_number, dispatchPrimary.run_attempt);
      return [{...job, run_id: 44, steps: [...job.steps,
        {name: "Run credential-free full-stack smoke", status: "completed", conclusion: "success"}]}];
    }
    assert.equal(input.run_id, 42);
    if (h0 && ++alternativeJobsReads > (cancelledPrimary ? 0 : 2) && !finalAttemptError) {
      assert.equal(input.attempt_number, source.run_attempt);
      if (kind.endsWith("-malformed")) return null;
      if (disappearingSnapshot) return [];
      throw Object.assign(new Error("alternative final attempt jobs unavailable"),
        kind.endsWith("-network") ? {code: "ECONNRESET"} : {status: badStatus});
    }
    if (finalAttemptError && alternativeJobsReads > 2) {
      return [{...job, steps: [...job.steps,
        {name: "Run credential-free full-stack smoke", status: "completed", conclusion: "success"}]}];
    }
    if (incompleteSnapshot) {
      snapshots += 1;
      if (input.attempt_number === 1) return [];
      assert.equal(input.attempt_number, 2);
      return [{...job, run_attempt: 2, steps: [
        ...job.steps, {name: "Run credential-free full-stack smoke", status: "completed", conclusion: "success"},
      ]}];
    }
    assert.equal(input.attempt_number, 1);
    const mutations = {
      "wrong-job": {id: 101}, "wrong-job-run": {run_id: 43}, "wrong-job-attempt": {run_attempt: 2},
      "wrong-job-head": {head_sha: defaultSha}, "wrong-check-url": {check_run_url: "https://example.com/check"},
      "failed-job": {conclusion: "failure"}, "pending-job": {status: "in_progress", conclusion: null},
    };
    snapshots += 1;
    return ["duplicate-jobs", "duplicate-attempt-jobs"].includes(kind) ? [job, {...job, id: 101}] : [{...job, ...(mutations[kind] || {})}];
  }};
  github.paginate.iterator = async function* (method, input) {
    assert.equal(method, listRuns);
    if (kind === "optional-final-acceptance-transient" && input.event === "repository_dispatch") {
      primaryInventoryReads += 1;
      const pending = {...source, id: 44, event: "repository_dispatch", head_sha: defaultSha,
        head_branch: "develop", created_at: "2026-09-01T00:00:01Z",
        status: primaryInventoryReads === 1 ? "in_progress" : "completed",
        conclusion: primaryInventoryReads === 1 ? null : "success"};
      const completed = {...source, id: 42, event: "repository_dispatch", head_sha: defaultSha,
        head_branch: "develop", created_at: "2026-09-01T00:00:00Z",
        status: "completed", conclusion: "success"};
      yield {data: primaryInventoryReads === 1 ? [pending, completed] : [pending]};
      return;
    }
    if (h0 && input.event === "pull_request") {
      primaryInventoryReads += 1;
      const primary = {...exactPrimary, status: cancelledPrimary || cancelledWithReplacement || primaryReady() ? "completed" : "queued",
        conclusion: cancelledPrimary || cancelledWithReplacement ? "cancelled" : primaryReady() ? "success" : null};
      yield {data: duplicatePrimary ? [primary, source] : [primary]};
      return;
    }
    if (cancelledWithReplacement && input.event === "repository_dispatch") {
      refreshInventoryReads += 1;
      const next = refreshInventoryReads > 1 ? dispatchPrimary : {...dispatchPrimary, status: "queued", conclusion: null};
      yield {data: [next, source]}; return;
    }
    if (event === "repository_dispatch" && input.event === "pull_request") {
      yield {data: [{...source, id: kind.startsWith("pending-native") ? 43 : source.id, event: "pull_request", head_sha: head,
        status: kind.startsWith("pending-native") ? "queued" : "completed",
        conclusion: kind.startsWith("pending-native") ? null : "cancelled"}]}; return;
    }
    if (kind.startsWith("pending-native-inventory-") && input.event === "repository_dispatch") {
      refreshInventoryReads += 1;
      const forbidden = kind.endsWith("-forbidden");
      throw Object.assign(new Error(forbidden ? "optional inventory forbidden" : "optional inventory unavailable"),
        {status: forbidden ? 403 : 503});
    }
    if (incompleteSnapshot) {
      yield {data: [{...source, run_attempt: snapshotRerunVisible ? 2 : 1}]}; return;
    }
    yield {data: kind.startsWith("duplicate-") && !["duplicate-jobs", "duplicate-attempt-jobs"].includes(kind)
      ? [{...source, id: 43, created_at: "2026-09-01T00:00:01Z", status: "in_progress", conclusion: null}, source] : [source]};
  };
  const clock = {now: () => now, parse: Date.parse};
  try {
    await execute(github, context, {info: message => info.push(message), warning: () => {}, setFailed: message => failures.push(message)},
      clock, (callback, duration) => {now += duration; queueMicrotask(callback);}, require);
  } catch (error) {
    if (!captureLookupFailure) throw error;
    lookupFailure = error;
  }
  return {failures, refs, snapshots, info, refreshInventoryReads, alternativeJobsReads, primaryInventoryReads,
    acceptanceLookupAttempts, lookupFailure};
}
(async () => {
  for (const event of ["pull_request", "repository_dispatch"]) {
    const success = await scenario("success", event);
    assert.deepEqual(success.failures, []);
    assert.deepEqual(success.refs, [event === "pull_request" ? merge : defaultSha], "immutable definition must come from actual source event revision");
  }
  assert.deepEqual((await scenario("legacy-positive")).failures, []);
  assert.deepEqual((await scenario("pending-native-refresh", "repository_dispatch")).failures, [], "trusted successful refresh releases queued native wait");
  for (const kind of ["pending-native-refresh-skipped", "pending-native-refresh-failed"]) {
    const result = await scenario(kind, "repository_dispatch");
    assert.equal(result.failures.length, 1, `${kind} cannot release queued native wait`);
    assert(result.info.some(line => line.startsWith("Found runtime-images run 43 with status=queued")),
      `${kind} must leave the native run selected`);
    assert(!result.info.some(line => line.startsWith("Found runtime-images run 42 with status=")),
      `${kind} must not select the refresh`);
  }
  for (const [kind, event, selectionMessage] of [
    ["optional-final-read-refresh", "repository_dispatch", "supersedes the pending native wait"],
    ["optional-final-read-jobs-network", "repository_dispatch", "supersedes the pending native wait"],
    ["optional-final-read-duplicate", "pull_request", "precedes the pending duplicate"],
  ]) {
    const result = await scenario(kind, event);
    assert(result.info.some(line => line.includes(selectionMessage)), kind);
    assert.equal(result.alternativeJobsReads, 6, kind);
    assert.equal(result.primaryInventoryReads, 2, kind);
    assert(result.info.some(line => line.includes("Found runtime-images run 43 with status=completed")), kind);
    assert.deepEqual(result.failures, [], kind);
  }
  for (const [kind, expectedReads, status] of [
    ["optional-final-read-cancelled-primary", 4, 503],
    ["optional-final-read-forbidden", 3, 403],
    ["optional-final-read-missing", 3, 404],
    ["optional-final-read-malformed", 3, undefined],
  ]) {
    const result = await scenario(kind, "repository_dispatch", true);
    assert.equal(result.alternativeJobsReads, expectedReads, kind);
    assert.equal(result.primaryInventoryReads, 1, kind);
    assert.equal(result.lookupFailure?.status ?? result.lookupFailure?.cause?.status, status, kind);
    if (status === undefined) assert(result.lookupFailure instanceof TypeError, kind);
  }
  const cancelledReplacement = await scenario("optional-final-read-cancelled-with-replacement", "repository_dispatch");
  assert.equal(cancelledReplacement.alternativeJobsReads, 6, "old dispatch proof exhausts final jobs retries");
  assert(cancelledReplacement.info.some(line => line.includes("Found runtime-images run 44 with status=completed")));
  assert.deepEqual(cancelledReplacement.failures, []);
  for (const kind of ["optional-final-read-attempt-transient", "optional-final-read-attempt-network",
    "optional-final-read-incomplete-snapshot"]) {
    const result = await scenario(kind, "repository_dispatch");
    assert.equal(result.primaryInventoryReads, 2, `${kind} resumes the native producer`);
    assert(result.info.some(line => line.includes("Found runtime-images run 43 with status=completed")), kind);
    assert.deepEqual(result.failures, [], kind);
    if (kind.endsWith("-snapshot")) assert.equal(result.alternativeJobsReads, 11, kind);
  }
  const finalCurrentBaseRetry = await scenario("optional-final-acceptance-transient", "repository_dispatch");
  assert.equal(finalCurrentBaseRetry.acceptanceLookupAttempts, 4,
    "the final optional current-base attempt read must exhaust only its bounded transient retries");
  assert.equal(finalCurrentBaseRetry.primaryInventoryReads, 2,
    "a transient final current-base attempt read must restore and reselect the pending exact producer");
  assert(finalCurrentBaseRetry.info.some(line => line.includes("Found runtime-images run 44 with status=completed")));
  assert.deepEqual(finalCurrentBaseRetry.failures, [],
    "the pending exact producer may pass after the final transient read recovers");
  for (const [kind, code] of [["optional-final-read-attempt-forbidden", 403], ["optional-final-read-attempt-missing", 404]]) {
    const result = await scenario(kind, "repository_dispatch", true);
    assert.equal(result.lookupFailure?.status ?? result.lookupFailure?.cause?.status, code, kind);
    assert.equal(result.primaryInventoryReads, 1, kind);
  }
  const unavailable = await scenario("pending-native-inventory-unavailable", "repository_dispatch");
  assert(unavailable.refreshInventoryReads >= 4, "temporary inventory errors exhaust the normal retries");
  assert(unavailable.info.some(line => line.startsWith("Found runtime-images run 43 with status=queued")),
    "temporary inventory outage must leave the native run selected");
  assert.equal(unavailable.failures.length, 1, "a native run that never finishes still fails the gate");
  await assert.rejects(() => scenario("pending-native-inventory-forbidden", "repository_dispatch"), /inventory forbidden/);
  for (const kind of ["duplicate-missing-step", "duplicate-nonterminal-step"]) {
    assert.deepEqual((await scenario(kind)).failures, [], `${kind} preserves canonical fallback`);
  }
  for (const kind of ["duplicate-terminal-skipped", "duplicate-terminal-failure", "rerun-after-proof"]) {
    assert.equal((await scenario(kind)).failures.length, 1, `${kind} cannot supply successful proof`);
  }
  for (const kind of ["marker-missing", "marker-success", "legacy-missing", "not-required", "step-skipped", "wrong-repository", "wrong-workflow",
    "wrong-path", "wrong-event", "wrong-head", "wrong-head-repository", "wrong-title", "wrong-source-attempt",
    "changed-definition", "unavailable-definition", "wrong-job", "wrong-job-run", "wrong-job-attempt", "wrong-job-head",
    "wrong-check-url", "failed-job", "pending-job", "duplicate-jobs", "duplicate-attempt-jobs", "newer-attempt", "bad-parents"]) {
    assert.equal((await scenario(kind)).failures.length, 1, `${kind} must not supply successful proof`);
  }
  assert.equal((await scenario("wrong-dispatch-branch", "repository_dispatch")).failures.length, 1);
  const supersededSnapshot = await scenario("incomplete-snapshot-superseded");
  assert(supersededSnapshot.info.some(line => line.includes("superseded the incomplete smoke snapshot")));
  assert.deepEqual(supersededSnapshot.failures, [], "reselection must qualify the newer attempt's own exact smoke evidence");
  const unchangedSnapshot = await scenario("incomplete-snapshot-unchanged");
  assert.match(unchangedSnapshot.failures[0], /did not expose a terminal PR Full-Stack Smoke job/);
  const stableFallback = await scenario("trusted-current-base-stable", "repository_dispatch");
  assert.deepEqual(stableFallback.failures, [], "unchanged current-base tuple still accepts exact refresh proof");
  const rerunningFallback = await scenario("trusted-current-base-attempt-advances", "repository_dispatch");
  assert(rerunningFallback.info.some(line => line.includes("attempt changed after current-base identity validation")),
    "rerunning the producer during the live tuple lookup cannot accept the old attempt");
  assert.equal(rerunningFallback.failures.length, 1, "no exact proof for the new attempt must not pass");
  const advancedFallback = await scenario("trusted-current-base-advances", "repository_dispatch");
  assert(advancedFallback.info.some(line => line.includes("identity changed after the final runtime attempt lookup")),
    "late base movement cannot accept the old trusted refresh");
  assert.match(advancedFallback.failures[0], /Timed out waiting for the exact runtime-images/);
  const stale = await scenario("stale-final");
  assert.deepEqual(stale.failures, [], "obsolete gate exits without publishing proof for changed base ref");
  console.log("Smoke producer contract passed: native outcomes, exact immutable definition/attempt, legacy proof and fail-closed cases");
})().catch(error => {console.error(error); process.exitCode = 1;});
NODE

# Exercise the temporary failed-producer verifier separately: passing producer
# proof above is unchanged and remains the only runtime qualification path.
node - "$smoke_clock_fixture" "$ROOT_DIR/.github/workflows/smoke.yml" <<'NODE'
const assert = require("node:assert/strict");
const fs = require("node:fs");
const script = fs.readFileSync(process.argv[2], "utf8");
const icons = [...fs.readFileSync(process.argv[3], "utf8").matchAll(/const statusIcon = \(value\) => \{[\s\S]*?\n\s*\};/g)];
assert.equal(icons.length, 2);
for (const [declaration] of icons) {
  const statusIcon = new Function(`${declaration}; return statusIcon;`)();
  assert.equal(statusIcon("failure (known temporary PLAY exception)"), "❌");
  assert.equal(statusIcon("failure"), "❌");
  assert.equal(statusIcon("success"), "✅");
  assert.equal(statusIcon("cancelled"), "⚪");
  assert.equal(statusIcon("skipped"), "⏭️");
}
const bytes = fs.readFileSync(process.argv[2] + ".producer");
const start = script.indexOf("function isSuccessfulRuntimeWorkflowDefinition(");
const end = script.indexOf("// Commit objects", start);
const fn = script.slice(start, end);
const workflowPath = ".github/workflows/runtime-images.yml";
const repositoryName = "benhook1013/FireMUD";
const canonicalSmokeJobName = "PR Full-Stack Smoke (runtime-required=true)";
const canonicalRuntimeWorkflowSha256 = require("node:crypto").createHash("sha256").update(bytes).digest("hex");
const previousRefreshBytes = Buffer.concat([bytes, Buffer.from("\n# Previous trusted refresh definition\n")]);
const canonicalPreviousTrustedRefreshRuntimeWorkflowSha256 = require("node:crypto").createHash("sha256")
  .update(previousRefreshBytes).digest("hex");
// Separate test authority lets the same verifier exercise both immutable modes.
const canonicalLegacyRuntimeWorkflowSha256 = "0".repeat(64);
const outgoingTrustedRefreshRuntimeWorkflowSha256 = new Set(JSON.parse(
  script.match(/const outgoingTrustedRefreshRuntimeWorkflowSha256 = new Set\((\[[\s\S]*?\])\);/)[1]
    .replace(/,\s*\]/, "]")));

for (const fingerprint of [
  "1993dbcd405b657d171339ca7aaf21ff8757098289a8f9121ddc433a00df803e",
  "877a1a1656f6b625c9c2cbece0cf55d8657de320ceae45f3e6c50301a850b30b",
]) assert.ok(outgoingTrustedRefreshRuntimeWorkflowSha256.has(fingerprint),
  `outgoing trusted refresh fingerprint missing: ${fingerprint}`);
const outgoingRefreshBytes = Buffer.concat([bytes, Buffer.from("\n# Outgoing trusted refresh fixture\n")]);
const outgoingRefreshFixtureSha256 = require("node:crypto").createHash("sha256")
  .update(outgoingRefreshBytes).digest("hex");
assert.ok(![canonicalRuntimeWorkflowSha256, canonicalPreviousTrustedRefreshRuntimeWorkflowSha256,
  canonicalLegacyRuntimeWorkflowSha256].includes(outgoingRefreshFixtureSha256));
assert.ok(!outgoingTrustedRefreshRuntimeWorkflowSha256.has(outgoingRefreshFixtureSha256));

const canonicalPlayClassifierSha256 = JSON.parse(script.match(/const canonicalPlayClassifierSha256 = (\{[\s\S]*?\});/)[1]);
const classifierDefinitions = JSON.parse(fs.readFileSync(process.argv[2] + ".classifiers.json", "utf8"));
const headSha = "a".repeat(40), baseSha = "b".repeat(40), mergeSha = "c".repeat(40);
const workflowSha = "d".repeat(40);
const identity = {headSha, baseSha, mergeSha, baseRef: "develop"};
const run = {id: 99, run_attempt: 2, event: "pull_request"};
const failures = ["Run credential-free full-stack smoke", "Require successful runtime smoke and authenticated cleanup"];
const step = (name, conclusion) => ({name, conclusion, status: "completed"});
const source = {...run, status: "completed", conclusion: "success", path: workflowPath,
  repository: {full_name: repositoryName}, workflow_id: 7, head_repository: {full_name: repositoryName}, head_sha: headSha,
  display_title: `Build Runtime Images secure-pr-artifact pr-3390 base-${baseSha} head-${headSha} merge-${mergeSha} mode-required`};
const candidate = {id: 123, run_id: 99, run_attempt: 2, head_sha: headSha, name: canonicalSmokeJobName,
  status: "completed", conclusion: "success", check_run_url: `https://api.github.com/repos/${repositoryName}/check-runs/123`,
  steps: [...failures.map(name => step(name, "success")), step("Stop smoke stack", "success"),
    step("Known canonical transition PLAY failure with successful authenticated cleanup", "success"),
    step("Export fixed-tag preview image artifact", "skipped"), step("Upload preview image artifact", "skipped")]};
async function verify(mutate = () => {}, event = "pull_request", knownPlay = true) {
  const data = structuredClone({pr: "3390", consumerRepository: repositoryName, run, identity, source, candidate, jobs: [candidate], latest: {...source},
    eventHeadRepository: repositoryName,
    currentPullRequest: {state: "open", head: {sha: headSha, repo: {full_name: repositoryName}},
      base: {ref: "develop", repo: {full_name: repositoryName}}, merge_commit_sha: mergeSha,
      labels: [{name: "canonical-play-transition"}]},
    currentBase: {ref: "refs/heads/develop", object: {sha: baseSha}},
    definitions: {...classifierDefinitions,
      [workflowPath]: {type: "file", path: workflowPath, encoding: "base64", content: bytes.toString("base64"), size: bytes.length}}});
  if (event === "repository_dispatch") {
    data.run.event = data.source.event = data.latest.event = event;
    data.source.head_branch = data.latest.head_branch = "develop";
    data.source.head_sha = data.latest.head_sha = workflowSha;
    data.candidate.head_sha = data.jobs[0].head_sha = workflowSha;
  }
  if (!knownPlay) {
    data.candidate.steps.find(step => step.name ===
      "Known canonical transition PLAY failure with successful authenticated cleanup").conclusion = "skipped";
  }
  mutate(data);
  const contentRequests = [];
  const github = {request: async () => ({data: data.source}), paginate: async () => data.jobs,
    rest: {actions: {getWorkflow: async () => ({data: {id: 7, path: workflowPath, name: "Build Runtime Images"}}),
      getWorkflowRun: async () => ({data: data.latest})}, pulls: {get: async () => ({data: data.currentPullRequest})},
      git: {getRef: async ({ref}) => {assert.equal(ref, `heads/${data.identity.baseRef}`); return {data: data.currentBase};}},
      repos: {getContent: async ({owner, repo, path, ref}) => {
        assert.equal(`${owner}/${repo}`, repositoryName);
        assert.equal(ref, path === workflowPath && event === "repository_dispatch" ? workflowSha : mergeSha,
          "workflow attestation must use its executed revision; classifier attestation must use the verified merge");
        contentRequests.push(path);
        if (!data.definitions[path]) throw Object.assign(new Error("definition unavailable"), {status: 404});
        return {data: data.definitions[path]};
      }}}};
  const verifier = knownPlay ? "verifyKnownPlayFailure" : "verifyCanonicalSmokeProducer";
  const accepted = await new Function("github", "prNumber", "repositoryName", "canonicalSmokeJobName", "workflowPath",
    "canonicalRuntimeWorkflowSha256", "canonicalPreviousTrustedRefreshRuntimeWorkflowSha256", "canonicalLegacyRuntimeWorkflowSha256", "outgoingTrustedRefreshRuntimeWorkflowSha256", "canonicalPlayClassifierSha256", "positiveInteger", "withTransientGitHubRetry", "context", "owner", "repo", "require", "data",
    `${fn}; return ${verifier}(data.run, data.candidate, data.identity);`)(
      github, data.pr, data.consumerRepository, canonicalSmokeJobName, workflowPath, canonicalRuntimeWorkflowSha256,
      canonicalPreviousTrustedRefreshRuntimeWorkflowSha256,
      data.legacyDefinition ? canonicalRuntimeWorkflowSha256 : canonicalLegacyRuntimeWorkflowSha256,
      new Set([...outgoingTrustedRefreshRuntimeWorkflowSha256, outgoingRefreshFixtureSha256]), canonicalPlayClassifierSha256,
      value => Number.isSafeInteger(value) && value > 0, (_label, call) => call(),
      {payload: {repository: {default_branch: "develop"}, pull_request: {head: {repo: {full_name: data.eventHeadRepository}}}}},
      "benhook1013", "FireMUD", require, data);
  if (accepted) assert.deepEqual(contentRequests, knownPlay ? [workflowPath, ...Object.keys(classifierDefinitions)] : [workflowPath]);
  return accepted;
}
// Proof errors in a successful exception attempt must fail closed.
const failedStart = script.indexOf('// A continued probe');
const failedEnd = script.indexOf('if (fullSmokeJob?.conclusion !== "success")', failedStart);
const failedBranch = script.slice(failedStart, failedEnd);
class TerminalGitHubRequestError extends Error {}
async function failedProof(error, event = "pull_request") {
  const warnings = [], failures = [], outputs = [];
  const core = {warning: value => warnings.push(value), setFailed: value => failures.push(value),
    setOutput: (...value) => outputs.push(value)};
  const execute = new Function("fullSmokeJob", "matching", "canonicalSmokeJobName", "currentIdentity",
    "verifyKnownPlayFailure", "TerminalGitHubRequestError", "core",
    `return (async () => { smokeGatePolling: while (true) { ${failedBranch} break; } })();`);
  await execute(candidate, {...source, event}, canonicalSmokeJobName, identity,
    async () => {throw error;}, TerminalGitHubRequestError, core);
  assert.deepEqual(failures, ["Canonical PLAY transition exception could not be verified; runtime qualification remains blocked."]);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /^Canonical PLAY transition exception proof is unavailable:/);
  assert.deepEqual(outputs, []);
}
// Execute the failed-run branch so proof errors retain the deterministic failure diagnostic.
const legacyFailedStart = script.indexOf('if (matching.conclusion !== "success")');
const legacyFailedEnd = script.indexOf("const latestIdentity", legacyFailedStart);
const legacyFailedBranch = script.slice(legacyFailedStart, legacyFailedEnd);
async function failedLegacyProof(stage, error, event = "pull_request") {
  const warnings = [], failures = [], outputs = [];
  const github = {paginate: async () => {
    if (stage === "jobs") throw error;
    return stage === "malformed-jobs" ? {} : [candidate];
  }};
  const core = {warning: value => warnings.push(value), setFailed: value => failures.push(value),
    setOutput: (...value) => outputs.push(value)};
  const execute = new Function("matching", "repositoryName", "github", "owner", "repo",
    "canonicalSmokeJobName", "currentIdentity", "withTransientGitHubRetry", "verifyKnownPlayFailure",
    "isCurrentPullRequestObsolete", "TerminalGitHubRequestError", "core",
    `return (async () => { smokeGatePolling: while (true) { ${legacyFailedBranch} break; } })();`);
  await execute({...source, conclusion: "failure", event}, repositoryName, github, "benhook1013", "FireMUD", canonicalSmokeJobName,
    identity, (_label, call) => call(), async () => {throw error;}, async () => false,
    TerminalGitHubRequestError, core);
  assert.deepEqual(failures, ["Runtime images run 99 did not complete successfully: run=failure"]);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /^Canonical PLAY transition exception proof is unavailable:/);
  assert.deepEqual(outputs, []);
}
const markerExpression = fs.readFileSync(process.argv[2] + ".marker", "utf8").trim().slice(3, -2);
const markerValues = {
  "github.event_name": "pull_request", "github.repository": repositoryName,
  "github.event.action": "pr-runtime-base-refresh",
  "github.event.pull_request.labels.*.name": ["canonical-play-transition"],
  "needs.image-meta.outputs.canonical_play_transition": "false",
  "needs.image-meta.outputs.runtime_smoke_required": "true",
  "steps.runtime-smoke.outcome": "failure", "steps.runtime-smoke.outputs.known_play_failure": "direct-backend-play-entitlement-unavailable-after-login",
  "steps.runtime-cleanup.outcome": "success",
};
function markerAccepted(overrides = {}) {
  let expression = markerExpression.replace("always()", "true")
    .replace("contains(", "includes(");
  for (const [key, value] of Object.entries({...markerValues, ...overrides})) {
    expression = expression.replaceAll(key, JSON.stringify(value));
  }
  return new Function("includes", `return (${expression});`)((values, value) => values.includes(value));
}
(async () => {
  for (const event of ["pull_request", "repository_dispatch"]) {
    for (const stage of ["jobs", "proof", "malformed-jobs"]) {
      await failedLegacyProof(stage, Object.assign(new Error("unavailable"), {status: 404}), event);
    }
  }
  await failedLegacyProof("proof", null);
  for (const event of ["pull_request", "repository_dispatch"]) {
    await failedProof(Object.assign(new Error("unavailable"), {status: 404}), event);
    await failedProof(null, event);
  }
  for (const stage of ["jobs", "proof"]) {
    const terminal = new TerminalGitHubRequestError("permission denied");
    await assert.rejects(failedLegacyProof(stage, terminal), error => error === terminal);
    await assert.rejects(failedProof(terminal), error => error === terminal);
  }
  assert.equal(markerAccepted(), true);
  const refreshMarker = {"github.event_name": "repository_dispatch",
    "github.event.pull_request.labels.*.name": [], "needs.image-meta.outputs.canonical_play_transition": "true"};
  assert.equal(markerAccepted(refreshMarker), true);
  for (const overrides of [
    {"github.event.action": "other"}, {"needs.image-meta.outputs.canonical_play_transition": "false"},
    {"needs.image-meta.outputs.canonical_play_transition": ""}, {"steps.runtime-cleanup.outcome": "failure"},
    {"steps.runtime-smoke.outputs.known_play_failure": ""}, {"steps.runtime-smoke.outcome": "success"},
    {"needs.image-meta.outputs.runtime_smoke_required": "false"}, {"github.repository": "attacker/FireMUD"},
  ]) assert.equal(markerAccepted({...refreshMarker, ...overrides}), false);
  for (const overrides of [
    {"steps.runtime-smoke.outputs.known_play_failure": ""},
    {"steps.runtime-smoke.outputs.known_play_failure": "malformed"},
    {"steps.runtime-smoke.outcome": "success"},
    {"steps.runtime-cleanup.outcome": "failure"},
    {"steps.runtime-cleanup.outcome": "skipped"},
    {"needs.image-meta.outputs.runtime_smoke_required": "false"},
    {"github.event.pull_request.labels.*.name": []},
    {"github.repository": "attacker/FireMUD"},
    {"github.event_name": "repository_dispatch"},
    {"github.event_name": "workflow_run"},
  ]) assert.equal(markerAccepted(overrides), false);
  assert.equal(await verify(), true);
  for (const event of ["pull_request", "repository_dispatch"]) {
    assert.equal(await verify(d => {
      d.legacyDefinition = true;
      d.source.conclusion = d.latest.conclusion = d.candidate.conclusion = "failure";
      for (const step of d.candidate.steps) if (failures.includes(step.name)) step.conclusion = "failure";
    }, event), true, "exact old fail-red default-branch producer remains a diagnostic exception only");
  }
  assert.equal(await verify(() => {}, "repository_dispatch"), true);
  const usePreviousRefresh = data => Object.assign(data.definitions[workflowPath], {
    content: previousRefreshBytes.toString("base64"), size: previousRefreshBytes.length,
  });
  for (const knownPlay of [true, false]) {
    assert.equal(await verify(usePreviousRefresh, "repository_dispatch", knownPlay), true,
      "trusted refresh may retain the previous successful producer proof");
    assert.equal(await verify(usePreviousRefresh, "pull_request", knownPlay), false,
      "the previous successful definition cannot attest a PR producer");
  }
  const useOutgoingRefresh = data => Object.assign(data.definitions[workflowPath], {
    content: outgoingRefreshBytes.toString("base64"), size: outgoingRefreshBytes.length,
  });
  for (const knownPlay of [true, false]) {
    assert.equal(await verify(useOutgoingRefresh, "repository_dispatch", knownPlay), true,
      "trusted refresh may retain an outgoing producer proof");
    assert.equal(await verify(useOutgoingRefresh, "pull_request", knownPlay), false,
      "an outgoing definition cannot attest a PR producer");
  }
  for (const path of Object.keys(classifierDefinitions)) {
    assert.equal(await verify(d => {
      const altered = Buffer.concat([Buffer.from(d.definitions[path].content, "base64"), Buffer.from("\n# altered classifier\n")]);
      d.definitions[path].content = altered.toString("base64");
      d.definitions[path].size = altered.length;
    }), false, `altered classifier bytes must fail closed: ${path}`);
    for (const change of [
      {type: "dir"}, {path: "other-classifier"}, {encoding: "none"}, {content: null}, {size: 0},
    ]) assert.equal(await verify(d => {Object.assign(d.definitions[path], change);}), false, `${path}: ${JSON.stringify(change)}`);
    await assert.rejects(verify(d => {delete d.definitions[path];}), /definition unavailable/);
  }
  for (const stackPr of [3405, 3409, 3407, 3408, 3410]) {
    assert.equal(await verify(d => {d.pr = String(stackPr); d.source.display_title = d.source.display_title.replace("pr-3390", `pr-${stackPr}`);}), true);
  }
  for (const mutate of [
    d => {d.pr = "3391";},
    d => {d.consumerRepository = "attacker/FireMUD";},
    d => {d.currentPullRequest.labels = [];},
    d => {d.currentPullRequest.labels = [{name: "other"}];},
    d => {d.currentPullRequest.state = "closed";},
    d => {d.currentPullRequest.head.sha = "e".repeat(40);},
    d => {d.currentPullRequest.head.repo.full_name = "attacker/FireMUD";},
    d => {d.currentPullRequest.base.repo.full_name = "attacker/FireMUD";},
    d => {d.eventHeadRepository = "fork-owner/FireMUD"; d.source.head_repository.full_name = d.eventHeadRepository;},
    d => {d.currentPullRequest.base.ref = "main";},
    d => {d.identity.baseSha = "d".repeat(40);},
    d => {d.identity.headSha = "d".repeat(40);},
    d => {d.identity.mergeSha = "d".repeat(40);},
    d => {d.run.event = "workflow_dispatch";},
    d => {d.source.event = "workflow_run";},
    d => {d.source.run_attempt = 1;},
    d => {d.latest.run_attempt = 3; assert.equal(d.source.run_attempt, 2);},
    d => {d.latest.conclusion = "failure"; assert.equal(d.source.conclusion, "success");},
    d => {d.source.path = ".github/workflows/spoof.yml";},
    d => {d.source.repository.full_name = "attacker/FireMUD";},
    d => {d.definitions[workflowPath].content = Buffer.from("spoof").toString("base64"); d.definitions[workflowPath].size = 5;},
    d => {d.jobs.push({...d.candidate, id: 124});},
    d => {d.jobs.push({id: 2, status: "completed", conclusion: "failure"});},
    d => {d.jobs[0].steps.push(step("Unrelated failure", "failure"));},
    d => {d.jobs[0].steps.find(s => s.name === "Stop smoke stack").conclusion = "failure";},
    d => {d.jobs[0].steps.find(s => s.name.startsWith("Known canonical transition")).conclusion = "skipped";},
    d => {d.jobs[0].steps = d.jobs[0].steps.filter(s => !s.name.startsWith("Known canonical transition"));},
    d => {d.jobs[0].steps.find(s => s.name.startsWith("Export fixed")).conclusion = "success";},
    d => {d.jobs[0].steps = d.jobs[0].steps.filter(s => !s.name.startsWith("Export fixed"));},
    d => {d.source.conclusion = "failure";},
    d => {d.jobs[0].steps.find(s => s.name === "Run credential-free full-stack smoke").conclusion = "failure";},
    d => {d.jobs[0].steps.find(s => s.name === "Require successful runtime smoke and authenticated cleanup").conclusion = "failure";},
    d => {d.jobs = [];},
    d => {d.jobs[0].steps = [];},
    d => {delete d.jobs[0].steps;},
  ]) {
    for (const event of ["pull_request", "repository_dispatch"]) {
      assert.equal(await verify(mutate, event), false, `${event}: ${mutate.toString()}`);
    }
  }
  for (const mutate of [
    d => {d.source.head_branch = "feature/untrusted";},
    d => {d.source.head_sha = "invalid";},
    d => {d.source.event = "pull_request";},
    d => {d.currentPullRequest.merge_commit_sha = "e".repeat(40);},
    d => {d.currentBase.object.sha = "e".repeat(40);},
    d => {d.currentBase.ref = "refs/heads/main";},
    d => {delete d.currentBase.object;},
  ]) assert.equal(await verify(mutate, "repository_dispatch"), false, mutate.toString());
  console.log("Canonical PLAY transition exception positive and negative contracts passed.");
})().catch(error => {console.error(error); process.exitCode = 1;});
NODE
