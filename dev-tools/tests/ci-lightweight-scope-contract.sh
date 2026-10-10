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
  'function isValidationPython(file)' \
  'function isValidationTooling(file)' \
  'function isRuntimeAuthority(file)' \
  '.node-version' \
  '.python-version'; do
  require_contains "$CLASSIFIER" "$expected"
done

python3 - "$CI_WORKFLOW" "$SECURITY_WORKFLOW" "$PREVIEW_WORKFLOW" "$ZAP_WORKFLOW" "$CLASSIFIER" <<'PY'
import copy
import json
from pathlib import Path
import re
import subprocess
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


def require_no_step(workflow, job_id, name_suffix, label):
    steps = value_at(workflow, ("jobs", job_id, "steps"), label)
    matches = [
        step
        for step in steps
        if isinstance(step, dict)
        and isinstance(step.get("name"), str)
        and step["name"].endswith(name_suffix)
    ]
    if matches:
        raise SystemExit(
            f"{label}: unexpected {name_suffix!r} step in jobs/{job_id}"
        )


def load_classifier_module_inventories(path_text):
    node_script = """
const { ALL_MODULES, BOOTABLE_MODULES } = require(process.argv[1]);
process.stdout.write(JSON.stringify({
  allModules: ALL_MODULES,
  bootableModules: [...BOOTABLE_MODULES],
}));
"""
    try:
        result = subprocess.run(
            ["node", "-e", node_script, path_text],
            check=True,
            capture_output=True,
            text=True,
        )
        inventories = json.loads(result.stdout)
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        raise SystemExit(
            f"classifier inventory export could not be loaded from {path_text}: {exc}"
        ) from exc
    if not isinstance(inventories, dict):
        raise SystemExit("classifier inventory export must be an object")
    return inventories


def parse_shared_array(script, name, label):
    matches = list(
        re.finditer(
            rf"(?ms)^[ \t]*const {re.escape(name)} = \[(.*?)\];",
            script,
        )
    )
    if len(matches) != 1:
        raise SystemExit(
            f"{label}: expected one shared {name} array, found {len(matches)}"
        )
    body = matches[0].group(1)
    string_pattern = re.compile(r'"(?:\\.|[^"\\])*"')
    values = []
    cursor = 0
    for match in string_pattern.finditer(body):
        if not re.fullmatch(r"[\s,]*", body[cursor : match.start()]):
            raise SystemExit(f"{label}: shared {name} array contains invalid syntax")
        values.append(json.loads(match.group(0)))
        cursor = match.end()
    if not re.fullmatch(r"[\s,]*", body[cursor:]):
        raise SystemExit(f"{label}: shared {name} array contains invalid syntax")
    return values


CI_GATING_STEPS = (
    (
        "helm-render-validation",
        "Validate hosted Telnet TLS contract",
        "bash ./dev-tools/tests/hosted-telnet-tls-contract.sh",
    ),
)

DEV_TOOL_CONTRACT_COMMANDS = (
    "bash ./dev-tools/tests/hosted-gateway-bridge-contract.sh",
    "bash ./dev-tools/tests/postgres-runtime-upgrade-contract.sh",
    "bash ./dev-tools/tests/minio-pr-smoke-bootstrap-contract.sh",
)
DISCOVERABLE_ARCHITECTURE_UNIT_TEST_MODULES = (
    "dev-tools/validation/test_design_capability_allocation.py",
    "dev-tools/validation/test_adr_review_status.py",
    "dev-tools/validation/test_check_metrics_cardinality.py",
    "dev-tools/validation/test_check_authz_route_matrix.py",
)
DEFERRED_ARCHITECTURE_COMMAND = (
    "bash ./dev-tools/tests/architecture-doc-contracts.sh --defer-unit-tests"
)
VALIDATION_DISCOVERY_COMMAND = (
    "python3 -m unittest discover -s dev-tools/validation "
    "-p 'test_*.py' --durations 0"
)


def require_gating_run_step(workflow, job_id, name_suffix, command):
    label = f"ci {name_suffix} contract"
    step = find_step(workflow, job_id, name_suffix, "ci workflow")
    if "if" in step:
        raise SystemExit(f"{label}: step must run whenever its job runs")
    if str(step.get("continue-on-error", "false")).strip().lower() != "false":
        raise SystemExit(f"{label}: step failure must fail its job")
    run = value_at(step, ("run",), label)
    if not isinstance(run, str) or run.strip() != command:
        raise SystemExit(
            f"{label}: run must be exactly the executable command {command!r}, got {run!r}"
        )


def validate_ci_script_execution(workflow):
    for job_id, name_suffix, command in CI_GATING_STEPS:
        require_gating_run_step(workflow, job_id, name_suffix, command)


def mutate_gating_step(workflow, job_id, name_suffix, changes):
    mutated = copy.deepcopy(workflow)
    step = find_step(mutated, job_id, name_suffix, "mutated ci workflow")
    step.update(changes)
    return mutated


def require_dev_tool_contract_command(workflow, command):
    label = f"ci dev-tool contract command {command!r}"
    step = find_step(
        workflow,
        "dev-tool-contract-checks",
        "Validate dev tool contracts",
        "ci workflow",
    )
    run = value_at(step, ("run",), label)
    if not isinstance(run, str) or not any(
        line.strip() == command for line in run.splitlines()
    ):
        raise SystemExit(
            f"{label}: command must appear as an executable run-block line"
        )


def require_architecture_test_handoff(workflow):
    label = "ci architecture test handoff"
    step = find_step(
        workflow,
        "dev-tool-contract-checks",
        "Validate dev tool contracts",
        "ci workflow",
    )
    if step.get("if") != "${{ needs.changes.outputs.lightweight_only != 'true' }}":
        raise SystemExit(f"{label}: complete-path contract step must run on full validation")
    if str(step.get("continue-on-error", "false")).strip().lower() != "false":
        raise SystemExit(f"{label}: complete-path contract failures must fail the job")
    run = value_at(step, ("run",), label)
    if not isinstance(run, str):
        raise SystemExit(f"{label}: run block must be a string")
    lines = [line.strip() for line in run.splitlines()]
    if lines.count(DEFERRED_ARCHITECTURE_COMMAND) != 1:
        raise SystemExit(
            f"{label}: full contract path must defer discovery-covered architecture "
            "unit tests exactly once"
        )
    if lines.count(VALIDATION_DISCOVERY_COMMAND) != 1:
        raise SystemExit(
            f"{label}: full contract path must run validation discovery with "
            "per-test durations exactly once"
        )
    if lines.index(DEFERRED_ARCHITECTURE_COMMAND) >= lines.index(
        VALIDATION_DISCOVERY_COMMAND
    ):
        raise SystemExit(
            f"{label}: comprehensive validation discovery must follow deferred architecture tests"
        )

    repository_root = Path(sys.argv[1]).resolve().parents[2]
    for module in DISCOVERABLE_ARCHITECTURE_UNIT_TEST_MODULES:
        module_path = repository_root / module
        if not module_path.is_file():
            raise SystemExit(
                f"{label}: discovered architecture test module is missing: {module}"
            )
        if (
            module_path.parent != repository_root / "dev-tools/validation"
            or not module_path.name.startswith("test_")
        ):
            raise SystemExit(
                f"{label}: architecture test module is outside validation discovery: {module}"
            )


def mutate_dev_tool_contract_command(workflow, command):
    mutated = copy.deepcopy(workflow)
    step = find_step(
        mutated,
        "dev-tool-contract-checks",
        "Validate dev tool contracts",
        "mutated ci workflow",
    )
    run = value_at(step, ("run",), "mutated ci workflow")
    step["run"] = "\n".join(
        line for line in run.splitlines() if line.strip() != command
    )
    return mutated


ci = load_workflow(sys.argv[1])
security = load_workflow(sys.argv[2])
preview = load_workflow(sys.argv[3])
zap = load_workflow(sys.argv[4])

module_checks_step = find_step(
    ci, "build-and-test", "Run Gradle Checks", "ci workflow"
)
require_equal(
    module_checks_step,
    ("run",),
    "echo \"::group::Run Module Validation\"\n"
    "./gradlew --refresh-dependencies :${{ matrix.module }}:check -PfullCheck\n"
    "echo \"::endgroup::\"\n",
    "ci workflow",
)

junit_results_step = find_step(
    ci, "build-and-test", "Upload JUnit Test Results", "ci workflow"
)
require_equal(
    junit_results_step,
    ("if",),
    "always()",
    "ci workflow",
)
require_equal(
    junit_results_step,
    ("uses",),
    "actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9",
    "ci workflow",
)
require_equal(
    junit_results_step,
    ("with", "name"),
    "junit-${{ matrix.module }}-results-${{ github.sha }}-attempt-${{ github.run_attempt }}",
    "ci workflow",
)
require_equal(
    junit_results_step,
    ("with", "path"),
    "${{ matrix.module == 'load-testing' && 'dev-tools/load-testing/build/test-results/**/*.xml' || format('services/{0}/build/test-results/**/*.xml', matrix.module) }}",
    "ci workflow",
)
require_equal(
    junit_results_step,
    ("with", "if-no-files-found"),
    "warn",
    "ci workflow",
)
require_equal(
    junit_results_step,
    ("with", "retention-days"),
    "14",
    "ci workflow",
)

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
classifier_inventories = load_classifier_module_inventories(sys.argv[5])
for output_key, constant_name, inventory_key in (
    ("affected_modules", "completeModules", "allModules"),
    ("bootable_modules", "completeBootableModules", "bootableModules"),
):
    expected = classifier_inventories.get(inventory_key)
    if not isinstance(expected, list):
        raise SystemExit(
            f"classifier inventory export {inventory_key!r} must be an array"
        )
    actual = parse_shared_array(
        value_at(ci_compute_step, ("with", "script"), "ci workflow"),
        constant_name,
        "ci workflow",
    )
    if actual != expected:
        raise SystemExit(
            f"ci workflow: shared {constant_name} must exactly match classifier export "
            f"{inventory_key}, got {actual!r}, expected {expected!r}"
        )
    require_contains(
        ci_compute_step,
        ("with", "script"),
        f"{output_key}: {constant_name}",
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
    ("jobs", "changes", "outputs", "postgres_runtime_proof_changed"),
    "${{ steps.compute.outputs.postgres_runtime_proof_changed }}",
    "ci workflow",
)
require_equal(
    ci,
    ("jobs", "postgres-runtime-upgrade-proof", "needs"),
    ["changes"],
    "ci workflow",
)
require_contains(
    ci,
    ("jobs", "postgres-runtime-upgrade-proof", "if"),
    "needs.changes.outputs.postgres_runtime_proof_changed == 'true'",
    "ci workflow",
)
postgres_runtime_proof_step = find_step(
    ci,
    "postgres-runtime-upgrade-proof",
    "Prove PostgreSQL 16 to 18 logical restore on isolated Docker volumes",
    "ci workflow",
)
require_equal(
    postgres_runtime_proof_step,
    ("env", "FIREMUD_POSTGRES_RUNTIME_DOCKER_PROOF"),
    "required",
    "ci workflow",
)
require_equal(
    postgres_runtime_proof_step,
    ("run",),
    "bash ./dev-tools/tests/postgres-runtime-upgrade-contract.sh",
    "ci workflow",
)
validation_gate_needs = value_at(
    ci, ("jobs", "validation-gate", "needs"), "ci workflow"
)
if "postgres-runtime-upgrade-proof" not in validation_gate_needs:
    raise SystemExit(
        "ci workflow: PostgreSQL runtime proof must feed the existing Validation Gate"
    )
validation_gate_steps = value_at(
    ci, ("jobs", "validation-gate", "steps"), "ci workflow"
)
gate_enforcement_steps = [
    step
    for step in validation_gate_steps
    if isinstance(step, dict) and step.get("name") == "Enforce validation success"
]
if len(gate_enforcement_steps) != 1:
    raise SystemExit("ci workflow: expected one Validation Gate enforcement step")
require_equal(
    gate_enforcement_steps[0],
    ("env", "POSTGRES_RUNTIME_PROOF_CHANGED"),
    "${{ needs.changes.outputs.postgres_runtime_proof_changed }}",
    "ci workflow",
)
require_equal(
    gate_enforcement_steps[0],
    ("env", "POSTGRES_RUNTIME_UPGRADE_PROOF"),
    "${{ needs.postgres-runtime-upgrade-proof.result }}",
    "ci workflow",
)
require_contains(
    gate_enforcement_steps[0],
    ("run",),
    'echo "PostgreSQL Runtime Upgrade Proof => $POSTGRES_RUNTIME_UPGRADE_PROOF (changed=$POSTGRES_RUNTIME_PROOF_CHANGED)"',
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
    'ruff check --force-exclude "${python_files[@]}"',
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
require_contains(
    ci,
    ("jobs", "dev-tool-contract-checks", "if"),
    "needs.changes.outputs.lightweight_only != 'true'",
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
require_contains(
    docs_step,
    ("run",),
    "Strict mode remains deferred until the legacy anchor-warning backlog is removed.",
    "ci workflow",
)
docs_node_step = find_step(ci, "docs-check", "Set Up Node", "ci workflow")
require_equal(
    docs_node_step,
    ("with", "node-version-file"),
    ".node-version",
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
docs_python_step = find_step(ci, "docs-check", "🐍 Set Up Python", "ci workflow")
require_equal(docs_python_step, ("with", "requirements"), "docs", "ci workflow")
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
require_list_item(
    ci,
    ("jobs", "validation-gate", "needs"),
    "dev-tools-readme-contract",
    "ci workflow",
)
require_list_item(
    ci,
    ("jobs", "validation-summary", "needs"),
    "dev-tools-readme-contract",
    "ci workflow",
)
readme_checkout_step = find_step(
    ci, "dev-tools-readme-contract", "⬇️ Checkout Code", "ci workflow"
)
require_equal(
    ci,
    ("jobs", "dev-tools-readme-contract", "if"),
    "${{ (github.event_name != 'pull_request' || github.event.action != 'edited' || github.event.changes.base.ref != null) && needs.changes.outputs.lightweight_only == 'true' }}",
    "ci workflow",
)
require_equal(
    readme_checkout_step,
    ("with", "persist-credentials"),
    "false",
    "ci workflow",
)
readme_validation_step = find_step(
    ci,
    "dev-tools-readme-contract",
    "🧭 Validate documented dev-tool paths and links",
    "ci workflow",
)
require_equal(
    readme_validation_step,
    ("run",),
    "bash ./dev-tools/tests/dev-tools-readme-contract.sh",
    "ci workflow",
)
require_equal(
    validation_step,
    ("env", "DEV_TOOLS_README_CONTRACT"),
    "${{ needs.dev-tools-readme-contract.result }}",
    "ci workflow",
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
    'if [ "$LIGHTWEIGHT_ONLY" != "true" ] || [ "$PYTHON_CHANGED" = "true" ]',
    'if [ "$LIGHTWEIGHT_ONLY" != "true" ] || [ "$DESIGN_DOCS_CHANGED" = "true" ] || [ "$VALIDATION_PYTHON_CHANGED" = "true" ]',
    'echo "Dev Tools README Contract => $DEV_TOOLS_README_CONTRACT"',
    'if [ "$LIGHTWEIGHT_ONLY" = "true" ] || [ "$DEV_TOOLS_README_CONTRACT" != "skipped" ]; then',
):
    require_contains(validation_step, ("run",), expected, "ci workflow")

complete_contract_step = find_step(
    ci, "dev-tool-contract-checks", "Validate dev tool contracts", "ci workflow"
)
architecture_document_step = find_step(
    ci,
    "dev-tool-contract-checks",
    "Validate architecture document contracts",
    "ci workflow",
)
contract_python_step = find_step(ci, "dev-tool-contract-checks", "🐍 Set Up Python", "ci workflow")
require_equal(
    contract_python_step,
    ("with", "requirements"),
    "${{ (needs.changes.outputs.lightweight_only == 'true' && needs.changes.outputs.design_docs_changed == 'true' && needs.changes.outputs.validation_python_changed != 'true') && 'yaml' || 'ci' }}",
    "ci workflow",
)
validate_ci_script_execution(ci)
require_no_step(
    ci,
    "helm-render-validation",
    "Validate hosted Gateway bridge contract",
    "ci workflow",
)
for job_id, name_suffix, command in CI_GATING_STEPS:
    for description, changes in (
        ("missing command", {"run": "true"}),
        ("disabled step", {"if": "${{ false }}"}),
        ("ignored failure", {"continue-on-error": "true"}),
        ("heredoc decoy", {"run": f"cat <<'EOF'\n{command}\nEOF"}),
        ("comment decoy", {"run": f"# {command}"}),
    ):
        mutation = mutate_gating_step(ci, job_id, name_suffix, changes)
        try:
            validate_ci_script_execution(mutation)
        except SystemExit:
            continue
        raise SystemExit(
            f"ci script execution contract accepted {name_suffix} {description}"
        )
for command in DEV_TOOL_CONTRACT_COMMANDS:
    require_dev_tool_contract_command(ci, command)
    mutation = mutate_dev_tool_contract_command(ci, command)
    try:
        require_dev_tool_contract_command(mutation, command)
    except SystemExit:
        continue
    raise SystemExit(
        f"ci dev-tool contract execution accepted removal of {command}"
    )
require_architecture_test_handoff(ci)
for description, command in (
    ("missing deferred architecture command", DEFERRED_ARCHITECTURE_COMMAND),
    ("missing validation discovery", VALIDATION_DISCOVERY_COMMAND),
):
    mutation = mutate_dev_tool_contract_command(ci, command)
    try:
        require_architecture_test_handoff(mutation)
    except SystemExit:
        continue
    raise SystemExit(f"ci architecture test handoff accepted {description}")
for description, changes in (
    ("disabled full-path step", {"if": "${{ false }}"}),
    ("ignored full-path failure", {"continue-on-error": "true"}),
):
    mutation = copy.deepcopy(ci)
    step = find_step(
        mutation,
        "dev-tool-contract-checks",
        "Validate dev tool contracts",
        "mutated ci workflow",
    )
    step.update(changes)
    try:
        require_architecture_test_handoff(mutation)
    except SystemExit:
        continue
    raise SystemExit(f"ci architecture test handoff accepted {description}")
require_equal(
    architecture_document_step,
    ("if",),
    "${{ needs.changes.outputs.lightweight_only == 'true' && needs.changes.outputs.design_docs_changed == 'true' }}",
    "ci workflow",
)
require_equal(
    architecture_document_step,
    ("run",),
    "bash ./dev-tools/tests/architecture-doc-contracts.sh",
    "ci workflow",
)
require_contains(
    complete_contract_step,
    ("run",),
    "python3 -m unittest discover -s dev-tools/validation -p 'test_*.py'",
    "ci workflow",
)
require_contains(
    complete_contract_step,
    ("run",),
    "python3 -m unittest discover -s dev-tools/tests -p 'test_*.py'",
    "ci workflow",
)
require_contains(
    complete_contract_step,
    ("run",),
    "bash ./dev-tools/tests/dev-tools-readme-contract.sh",
    "ci workflow",
)
require_contains(
    complete_contract_step,
    ("run",),
    "bash ./dev-tools/tests/gameplay-bridge-network-policy-contract.sh",
    "ci workflow",
)

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

security_scan_steps = value_at(
    security, ("jobs", "trivy-scan", "steps"), "security workflow"
)
for cache_id, lockfile in (
    ("cache-openapi", "config/openapi/package-lock.json"),
    ("cache-web-client", "web-client/package-lock.json"),
):
    cache_steps = [
        step
        for step in security_scan_steps
        if isinstance(step, dict) and step.get("id") == cache_id
    ]
    if len(cache_steps) != 1:
        raise SystemExit(
            f"security workflow: expected one {cache_id} cache step, found {len(cache_steps)}"
        )
    cache_with = cache_steps[0].get("with")
    if not isinstance(cache_with, dict):
        raise SystemExit(f"security workflow: {cache_id} cache step lacks with mapping")
    expected_key = (
        f"{cache_id.removeprefix('cache-')}-node-modules-${{{{ runner.os }}}}-"
        f"${{{{ hashFiles('.node-version') }}}}-"
        f"${{{{ hashFiles('{lockfile}') }}}}"
    )
    expected_restore_key = (
        f"{cache_id.removeprefix('cache-')}-node-modules-${{{{ runner.os }}}}-"
        "${{ hashFiles('.node-version') }}-"
    )
    if cache_with.get("key") != expected_key:
        raise SystemExit(
            f"security workflow: {cache_id} key must include Node and lockfile hashes"
        )
    if cache_with.get("restore-keys") != expected_restore_key:
        raise SystemExit(
            f"security workflow: {cache_id} restore key must be scoped to Node version"
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
        ("on", "pull_request_target", "paths-ignore"),
        path_item,
        "preview workflow",
    )

# ZAP is credential-free PR validation; stacked bases retain the same relevant
# paths and default substantive PR events, while trusted push scope stays fixed.
require_equal(
    zap,
    ("on",),
    {
        "push": {
            "branches": ["main", "develop"],
            "paths": [".github/workflows/zap-baseline.yml", "web-client/**"],
        },
        "pull_request": {"paths": [".github/workflows/zap-baseline.yml", "web-client/**"]},
        "workflow_dispatch": "",
    },
    "ZAP workflow",
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
