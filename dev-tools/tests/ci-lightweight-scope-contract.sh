#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CI_WORKFLOW="$ROOT_DIR/.github/workflows/ci.yml"
SECURITY_WORKFLOW="$ROOT_DIR/.github/workflows/security.yml"
PREVIEW_WORKFLOW="$ROOT_DIR/.github/workflows/preview.yml"
ZAP_WORKFLOW="$ROOT_DIR/.github/workflows/zap-baseline.yml"
CLASSIFIER="$ROOT_DIR/.github/scripts/classify-change-scope.cjs"

node --test "$ROOT_DIR/.github/scripts/classify-change-scope.test.cjs"

require_contains() {
  local path="$1"
  local expected="$2"
  if ! grep -Fq -- "$expected" "$path"; then
    echo "$path: missing lightweight-scope contract: $expected" >&2
    exit 1
  fi
}

for expected in \
  'function isDocumentation(file)' \
  'function isValidationPython(file)'; do
  require_contains "$CLASSIFIER" "$expected"
done

python3 - "$CI_WORKFLOW" "$SECURITY_WORKFLOW" "$PREVIEW_WORKFLOW" "$ZAP_WORKFLOW" <<'PY'
from pathlib import Path
import sys

import yaml


def load_workflow(path_text):
    path = Path(path_text)
    try:
        workflow = yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    except yaml.YAMLError as exc:
        raise SystemExit(f"{path}: invalid YAML: {exc}") from exc
    if not isinstance(workflow, dict):
        raise SystemExit(f"{path}: workflow root must be a mapping")
    return workflow


def value_at(mapping, path, label):
    value = mapping
    for key in path:
        if not isinstance(value, dict) or key not in value:
            raise SystemExit(f"{label}: missing YAML field {'/'.join(path)}")
        value = value[key]
    return value


def require_equal(mapping, path, expected, label):
    actual = value_at(mapping, path, label)
    if actual != expected:
        raise SystemExit(
            f"{label}: {'/'.join(path)} must be {expected!r}, got {actual!r}"
        )


def require_contains(mapping, path, expected, label):
    actual = value_at(mapping, path, label)
    if not isinstance(actual, str) or expected not in actual:
        raise SystemExit(
            f"{label}: {'/'.join(path)} must contain {expected!r}, got {actual!r}"
        )


def require_list_item(mapping, path, expected, label):
    actual = value_at(mapping, path, label)
    if not isinstance(actual, list) or expected not in actual:
        raise SystemExit(
            f"{label}: {'/'.join(path)} must contain {expected!r}, got {actual!r}"
        )


def find_step(workflow, job_id, name_suffix, label):
    steps = value_at(workflow, ("jobs", job_id, "steps"), label)
    matches = [
        step
        for step in steps
        if isinstance(step, dict)
        and isinstance(step.get("name"), str)
        and step["name"].endswith(name_suffix)
    ]
    if len(matches) != 1:
        raise SystemExit(
            f"{label}: expected one {name_suffix!r} step in jobs/{job_id}, found {len(matches)}"
        )
    return matches[0]


ci = load_workflow(sys.argv[1])
security = load_workflow(sys.argv[2])
preview = load_workflow(sys.argv[3])
zap = load_workflow(sys.argv[4])

require_equal(
    ci,
    ("jobs", "changes", "permissions", "pull-requests"),
    "read",
    "ci workflow",
)
ci_classifier_checkout = find_step(
    ci, "changes", "Check out change classifier", "ci workflow"
)
require_equal(
    ci_classifier_checkout,
    ("with", "ref"),
    "${{ github.event_name == 'pull_request' && github.event.pull_request.base.sha || github.sha }}",
    "ci workflow",
)
ci_compute_step = find_step(ci, "changes", "Compute affected jobs", "ci workflow")
require_contains(
    ci_compute_step,
    ("with", "script"),
    "const { classifyGithubChangeScope } = require(classifierPath)",
    "ci workflow",
)
require_contains(
    ci_compute_step,
    ("with", "script"),
    "await classifyGithubChangeScope(github, context)",
    "ci workflow",
)
require_contains(
    ci_compute_step,
    ("with", "script"),
    "Base revision predates the change classifier; using complete validation scope.",
    "ci workflow",
)

require_equal(
    ci,
    ("jobs", "changes", "outputs", "lightweight_only"),
    "${{ steps.compute.outputs.lightweight_only }}",
    "ci workflow",
)
require_equal(
    ci,
    ("jobs", "python-script-validation", "needs"),
    ["changes"],
    "ci workflow",
)
require_contains(
    ci,
    ("jobs", "python-script-validation", "if"),
    "needs.changes.outputs.python_changed == 'true'",
    "ci workflow",
)
python_step = find_step(
    ci, "python-script-validation", "Validate tracked Python scripts", "ci workflow"
)
require_contains(
    python_step,
    ("run",),
    'ruff check "${python_files[@]}"',
    "ci workflow",
)
require_equal(
    ci,
    ("jobs", "dev-tool-contract-checks", "needs"),
    ["changes"],
    "ci workflow",
)
require_contains(
    ci,
    ("jobs", "dev-tool-contract-checks", "if"),
    "needs.changes.outputs.design_docs_changed == 'true'",
    "ci workflow",
)
require_contains(
    ci,
    ("jobs", "dev-tool-contract-checks", "if"),
    "needs.changes.outputs.validation_python_changed == 'true'",
    "ci workflow",
)
require_equal(
    ci,
    ("jobs", "docs-check", "needs"),
    ["changes"],
    "ci workflow",
)
docs_step = find_step(ci, "docs-check", "Build documentation site", "ci workflow")
require_contains(
    docs_step,
    ("run",),
    "python3 -m mkdocs build --clean",
    "ci workflow",
)
docs_node_step = find_step(ci, "docs-check", "Set Up Node", "ci workflow")
require_equal(
    docs_node_step,
    ("with", "node-version"),
    "24.19.0",
    "ci workflow",
)
require_equal(
    docs_node_step,
    ("with", "cache-dependency-path"),
    "config/openapi/package-lock.json",
    "ci workflow",
)
docs_dependencies_step = find_step(
    ci, "docs-check", "Install documentation dependencies", "ci workflow"
)
require_contains(
    docs_dependencies_step,
    ("run",),
    "npm ci --prefix config/openapi",
    "ci workflow",
)
require_contains(
    docs_dependencies_step,
    ("run",),
    "python3 -m pip install --disable-pip-version-check mkdocs==1.6.1 mkdocs-material==9.6.5",
    "ci workflow",
)
docs_links_step = find_step(ci, "docs-check", "Lint Markdown and links", "ci workflow")
require_contains(
    docs_links_step,
    ("run",),
    "config/openapi/node_modules/.bin/markdownlint-cli2",
    "ci workflow",
)
require_contains(
    docs_links_step,
    ("run",),
    "CHECK_EXTERNAL_LINKS=${{ github.event_name == 'pull_request' && '0' || '1' }} bash ./dev-tools/docs/link-check.sh",
    "ci workflow",
)
validation_step = find_step(
    ci, "validation-gate", "Enforce validation success", "ci workflow"
)
require_contains(
    validation_step,
    ("run",),
    'echo "Validate Documentation => $DOCS_CHECK"',
    "ci workflow",
)
for expected in (
    'if [ "$LIGHTWEIGHT_ONLY" = "true" ]',
    'is_acceptable_optional_result "$result"',
    'if [ "$PYTHON_CHANGED" = "true" ]',
    'if [ "$DESIGN_DOCS_CHANGED" = "true" ] || [ "$VALIDATION_PYTHON_CHANGED" = "true" ]',
):
    require_contains(validation_step, ("run",), expected, "ci workflow")

require_equal(
    security,
    ("jobs", "changes", "permissions", "pull-requests"),
    "read",
    "security workflow",
)
security_classifier_checkout = find_step(
    security, "changes", "Check out change classifier", "security workflow"
)
require_equal(
    security_classifier_checkout,
    ("with", "ref"),
    "${{ github.event_name == 'pull_request' && github.event.pull_request.base.sha || github.sha }}",
    "security workflow",
)
security_compute_step = find_step(
    security, "changes", "Compute security scope", "security workflow"
)
require_contains(
    security_compute_step,
    ("with", "script"),
    "const { classifyGithubChangeScope } = require(classifierPath)",
    "security workflow",
)
require_contains(
    security_compute_step,
    ("with", "script"),
    "await classifyGithubChangeScope(github, context)",
    "security workflow",
)
require_contains(
    security_compute_step,
    ("with", "script"),
    "Base revision predates the change classifier; running the complete security scope.",
    "security workflow",
)
require_equal(
    security,
    ("jobs", "trivy-scan", "if"),
    "${{ (github.event_name != 'pull_request' || github.event.action != 'edited' || github.event.changes.base.ref != null) && needs.changes.outputs.lightweight_only != 'true' }}",
    "security workflow",
)
require_equal(
    security,
    ("jobs", "secret-compliance", "if"),
    "${{ github.event_name != 'pull_request' || github.event.action != 'edited' || github.event.changes.base.ref != null }}",
    "security workflow",
)
require_equal(
    security,
    ("jobs", "changes", "name"),
    "Detect Security-Relevant Changes",
    "security workflow",
)
require_equal(
    security,
    ("jobs", "security-gate", "needs"),
    ["changes", "trivy-scan", "secret-compliance"],
    "security workflow",
)
security_step = find_step(
    security, "security-gate", "Enforce security success", "security workflow"
)
require_equal(
    security_step,
    ("env", "LIGHTWEIGHT_ONLY"),
    "${{ needs.changes.outputs.lightweight_only }}",
    "security workflow",
)

for path_item in (
    "**/*.md",
    "mkdocs.yml",
    "design/**",
    "dev-tools/docs/**",
    "dev-tools/validation/**/*.py",
):
    require_list_item(
        preview,
        ("on", "pull_request", "paths-ignore"),
        path_item,
        "preview workflow",
    )

for event in ("push", "pull_request"):
    for path_item in (".github/workflows/zap-baseline.yml", "web-client/**"):
        require_list_item(
            zap,
            ("on", event, "paths"),
            path_item,
            "ZAP workflow",
        )
PY

echo "CI lightweight scope contract passed"
