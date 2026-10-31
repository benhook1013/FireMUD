#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CI_WORKFLOW="$ROOT_DIR/.github/workflows/ci.yml"
SECURITY_WORKFLOW="$ROOT_DIR/.github/workflows/security.yml"
PREVIEW_WORKFLOW="$ROOT_DIR/.github/workflows/preview.yml"
ZAP_WORKFLOW="$ROOT_DIR/.github/workflows/zap-baseline.yml"
CLASSIFIER="$ROOT_DIR/.github/scripts/classify-change-scope.cjs"
MODULE_CHECK_WRAPPER="$ROOT_DIR/.github/scripts/run-gradle-module-checks.sh"

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

# shellcheck disable=SC2016 # These expected strings intentionally match literal shell source.
for expected in \
  'declare -a PREPARE_TASKS=(":${MODULE}:testClasses")' \
  'PREPARE_TASKS+=(":${MODULE}:${task_name}Classes")' \
  'bash "$ROOT_DIR/dev-tools/validation/run-gradle-with-dependency-recovery.sh"'; do
  require_contains "$MODULE_CHECK_WRAPPER" "$expected"
done

python3 - "$CI_WORKFLOW" "$SECURITY_WORKFLOW" "$PREVIEW_WORKFLOW" "$ZAP_WORKFLOW" "$CLASSIFIER" "$ROOT_DIR/build.gradle.kts" <<'PY'
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


def evaluate_module_check_matrix(step, modules):
    script = value_at(step, ("with", "script"), "ci workflow")
    match = re.search(
        r"(?ms)^[ \t]*outputs\.module_check_matrix = .*?^[ \t]*\);",
        script,
    )
    if match is None:
        raise SystemExit("ci workflow: module check matrix builder is missing")
    partition_set = re.search(
        r'(?m)^[ \t]*const partitionedSlowServices = new Set\([^\n]+\);',
        script[:match.start()],
    )
    if partition_set is None:
        raise SystemExit("ci workflow: slow-service partition allowlist is missing")
    node_script = """
const fs = require("node:fs");
const input = JSON.parse(fs.readFileSync(0, "utf8"));
const outputs = { affected_modules: input.modules };
const rows = new Function("outputs", `${input.partition_set}\n${input.assignment}\nreturn outputs.module_check_matrix;`)(outputs);
process.stdout.write(JSON.stringify(rows));
"""
    try:
        result = subprocess.run(
            ["node", "-e", node_script],
            check=True,
            capture_output=True,
            text=True,
            input=json.dumps({
                "modules": modules,
                "assignment": match.group(0).strip(),
                "partition_set": partition_set.group(0).strip(),
            }),
        )
        rows = json.loads(result.stdout)
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        raise SystemExit(f"ci workflow: module check matrix fixture failed: {exc}") from exc
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise SystemExit("ci workflow: module check matrix builder must return job records")
    return rows


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
DEV_TOOLS_TEST_DISCOVERY_COMMAND = "python3 -m unittest discover -s dev-tools/tests -p 'test_*.py'"


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


def executable_command_line(line):
    # Bash's reserved-word time preserves the command's exit status and errexit.
    # Strip only that exact prefix; comments, echo/quoted decoys, and failure
    # suppression must still fail the exact executable-command comparisons.
    return line.strip().removeprefix("time ")


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
        executable_command_line(line) == command for line in run.splitlines()
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
    lines = [executable_command_line(line) for line in run.splitlines()]
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
        line for line in run.splitlines() if executable_command_line(line) != command
    )
    return mutated


ci = load_workflow(sys.argv[1])
security = load_workflow(sys.argv[2])
preview = load_workflow(sys.argv[3])
zap = load_workflow(sys.argv[4])
ci_compute_step = find_step(ci, "changes", "Compute affected jobs", "ci workflow")

module_checks_step = find_step(
    ci, "build-and-test", "Run Gradle Checks", "ci workflow"
)
require_equal(
    module_checks_step,
    ("run",),
    "echo \"::group::Run Module Validation\"\n"
    "bash ./.github/scripts/run-gradle-module-checks.sh '${{ matrix.module }}' '${{ matrix.partition }}'\n"
    "echo \"::endgroup::\"\n",
    "ci workflow",
)
require_equal(
    ci,
    ("jobs", "changes", "outputs", "module_check_matrix"),
    "${{ steps.compute.outputs.module_check_matrix }}",
    "ci workflow",
)
require_equal(
    ci,
    ("jobs", "build-and-test", "strategy", "matrix", "include"),
    "${{ fromJSON(needs.changes.outputs.module_check_matrix) }}",
    "ci workflow",
)
matrix_config = value_at(ci, ("jobs", "build-and-test", "strategy", "matrix"), "ci workflow")
if set(matrix_config) != {"include"}:
    raise SystemExit("ci workflow: module check matrix must use independent include-only records")
matrix_rows = evaluate_module_check_matrix(
    ci_compute_step, ["account-service", "game-session-service", "common-security"]
)
expected_matrix_rows = [
    {"module": "account-service", "partition": "1", "java-version": 21},
    {"module": "account-service", "partition": "2", "java-version": 21},
    {"module": "game-session-service", "partition": "1", "java-version": 21},
    {"module": "game-session-service", "partition": "2", "java-version": 21},
    {"module": "common-security", "partition": "full", "java-version": 21},
]
expanded_jobs = [dict(row) for row in matrix_rows]
if expanded_jobs != expected_matrix_rows or len({
    (row["module"], row["partition"]) for row in expanded_jobs
}) != 5:
    raise SystemExit(f"ci workflow: matrix expansion lost or duplicated a job: {expanded_jobs!r}")
require_contains(
    ci_compute_step,
    ("with", "script"),
    'outputs.module_check_matrix = outputs.affected_modules.flatMap((module) =>',
    "ci workflow",
)
require_contains(
    ci_compute_step,
    ("with", "script"),
    'const partitionedSlowServices = new Set(["account-service", "game-session-service"]);',
    "ci workflow",
)
for expected in (
    '{ module, partition: "1", "java-version": 21 },',
    '{ module, partition: "2", "java-version": 21 },',
    'partition: "full"',
    '"java-version": 21',
):
    require_contains(ci_compute_step, ("with", "script"), expected, "ci workflow")
java_setup_step = find_step(ci, "build-and-test", "Set Up Java", "ci workflow")
require_equal(
    java_setup_step,
    ("with", "java-version"),
    "${{ matrix.java-version }}",
    "ci workflow",
)

runtime_jar_step = find_step(
    ci, "build-and-test", "Build Runtime JAR", "ci workflow"
)
require_equal(
    runtime_jar_step,
    ("if",),
    "${{ contains(fromJSON(needs.changes.outputs.bootable_modules), matrix.module) && (matrix.module != 'account-service' && matrix.module != 'game-session-service' || matrix.partition == '1') }}",
    "ci workflow",
)
require_equal(
    ci,
    ("jobs", "validation-gate", "env", "ACCOUNT_MODULE_AFFECTED"),
    "${{ contains(fromJSON(needs.changes.outputs.affected_modules || '[]'), 'account-service') }}",
    "ci workflow",
)
require_equal(
    runtime_jar_step,
    ("run",),
    "./gradlew --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8' :${{ matrix.module }}:bootJar -PfullCheck",
    "ci workflow",
)

test_logs_step = find_step(
    ci, "build-and-test", "Upload Test Logs if Failed", "ci workflow"
)
require_equal(
    test_logs_step,
    ("if",),
    "${{ failure() || cancelled() }}",
    "ci workflow",
)

junit_results_step = find_step(
    ci, "build-and-test", "Upload JUnit Test Results", "ci workflow"
)
require_equal(
    junit_results_step,
    ("if",),
    "${{ always() && matrix.module != 'account-service' && matrix.module != 'game-session-service' }}",
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
    "junit-${{ matrix.module }}-${{ matrix.partition }}-results-${{ github.sha }}-attempt-${{ github.run_attempt }}",
    "ci workflow",
)
require_equal(
    junit_results_step,
    ("with", "path"),
    "${{ matrix.module == 'load-testing' && 'dev-tools/load-testing/build/test-results/**/*.xml' || format('services/{0}/build/test-results/**/*.xml', matrix.module) }}",
    "ci workflow",
)

slow_partition_artifact_step = find_step(
    ci, "build-and-test",
    "Upload Slow-Service Partition Reports and Execution Data",
    "ci workflow",
)
require_equal(
    slow_partition_artifact_step, ("if",),
    "${{ always() && (matrix.module == 'account-service' || matrix.module == 'game-session-service') }}",
    "ci workflow",
)
require_equal(
    slow_partition_artifact_step, ("with", "name"),
    "slow-service-ci-partition-${{ github.run_id }}-${{ github.sha }}-attempt-${{ github.run_attempt }}-${{ matrix.module }}-partition-${{ matrix.partition }}",
    "ci workflow",
)
for artifact_path in (
    "services/${{ matrix.module }}/build/slow-service-ci-partition/${{ matrix.module }}/partition-${{ matrix.partition }}/",
    "services/${{ matrix.module }}/build/jacoco/*.exec",
    "services/${{ matrix.module }}/build/reports/jacoco/test/",
    "services/${{ matrix.module }}/build/test-results/**/*.xml",
):
    require_contains(slow_partition_artifact_step, ("with", "path"), artifact_path, "ci workflow")

for module, job_id, display, prefix in (
    ("account-service", "account-coverage-aggregate", "Account", "ACCOUNT"),
    ("game-session-service", "game-session-coverage-aggregate", "Game Session", "GAME_SESSION"),
):
    aggregate_job = value_at(ci, ("jobs", job_id), "ci workflow")
    require_equal(aggregate_job, ("needs",), ["changes", "build-and-test"], "ci workflow")
    require_contains(aggregate_job, ("if",), "needs.build-and-test.result == 'success'", "ci workflow")
    require_contains(
        aggregate_job, ("if",),
        f"contains(fromJSON(needs.changes.outputs.affected_modules || '[]'), '{module}')",
        "ci workflow",
    )
    require_equal(aggregate_job, ("permissions",), {"actions": "read", "contents": "read"}, "ci workflow")
    capture = find_step(ci, job_id, f"Capture {display} partition job and artifact provenance", "ci workflow")
    require_equal(capture, ("env", "CI_MODULE"), module, "ci workflow")
    require_equal(
        capture,
        ("env", "CI_PROVENANCE_PATH"),
        f"services/{module}/build/slow-service-ci-partition-provenance.json",
        "ci workflow",
    )
    for env_name, expected in (
        ("CI_EVENT_NAME", "${{ github.event_name }}"),
        ("CI_REF_NAME", "${{ github.ref_name }}"),
        ("CI_SOURCE_SHA", "${{ github.sha }}"),
        ("CI_NATIVE_HEAD_SHA", "${{ github.event_name == 'pull_request' && github.event.pull_request.head.sha || github.sha }}"),
    ):
        require_equal(capture, ("env", env_name), expected, "ci workflow")
    require_contains(
        capture,
        ("with", "script"),
        ".github/scripts/capture-slow-service-provenance.cjs",
        "ci workflow",
    )
    require_contains(
        capture,
        ("with", "script"),
        "module: process.env.CI_MODULE",
        "ci workflow",
    )
    select = find_step(ci, job_id, f"Select {display} artifacts from latest effective job outcomes", "ci workflow")
    require_contains(select, ("run",), "python3 dev-tools/validation/slow_service_ci_test_partition.py select-artifacts", "ci workflow")
    require_contains(select, ("run",), f"--module {module}", "ci workflow")
    download = find_step(ci, job_id, f"Download both effective {display} partition artifacts", "ci workflow")
    require_equal(download, ("continue-on-error",), "true", "ci workflow")
    require_equal(download, ("with", "artifact-ids"), "${{ steps.selection.outputs.artifact_ids }}", "ci workflow")
    verify = find_step(ci, job_id, f"Verify complete {display} partition and report union", "ci workflow")
    require_contains(verify, ("run",), "python3 dev-tools/validation/slow_service_ci_test_partition.py verify-aggregate", "ci workflow")
    merge = find_step(ci, job_id, f"Merge complete {display} JaCoCo coverage", "ci workflow")
    require_contains(
        merge, ("run",),
        f":{module}:jacocoTestReport -PfullCheck -PslowServiceCiCoverageAggregate={module}",
        "ci workflow",
    )
    require_list_item(ci, ("jobs", "validation-gate", "needs"), job_id, "ci workflow")
    result_expression = "${{ needs." + job_id + ".result }}"
    require_equal(
        ci, ("jobs", "validation-gate", "env", prefix + "_COVERAGE_AGGREGATE"),
        result_expression, "ci workflow",
    )
    gate = find_step(ci, "validation-gate", "Enforce validation success", "ci workflow")
    require_equal(gate, ("env", prefix + "_COVERAGE_AGGREGATE"), result_expression, "ci workflow")
    require_contains(
        gate, ("run",),
        'if [ "$' + prefix + '_COVERAGE_AGGREGATE" != "success" ]; then',
        "ci workflow",
    )
    require_contains(
        gate, ("run",),
        'if [ "$' + prefix + '_MODULE_AFFECTED" = "true" ]; then',
        "ci workflow",
    )

# Execute the shared provenance helper with strict, offline GitHub API mocks.
# This covers both service callers, identity aliases, pagination, and refusal paths.
helper_path = Path(sys.argv[1]).resolve().parents[2] / ".github/scripts/capture-slow-service-provenance.cjs"
if not helper_path.is_file():
    raise SystemExit(f"ci workflow: missing shared provenance helper: {helper_path}")
capture_scripts = []
for module, job_id, display in (
    ("account-service", "account-coverage-aggregate", "Account"),
    ("game-session-service", "game-session-coverage-aggregate", "Game Session"),
):
    capture = find_step(
        ci, job_id, f"Capture {display} partition job and artifact provenance", "ci workflow"
    )
    capture_scripts.append({"module": module, "script": capture["with"]["script"]})
capture_node = r"""
const assert = require("node:assert/strict");
const fs = require("node:fs");
const input = JSON.parse(fs.readFileSync(0, "utf8"));
const captureSlowServiceProvenance = require(input.helper);
const owner = "example", repository = "firemud", runId = 70;
const testedMerge = "a".repeat(40), nativeHead = "b".repeat(40), baseSha = "c".repeat(40);
const wrongSha = "d".repeat(40);
const repositoryFullName = `${owner}/${repository}`;
const coreJobRows = ["account-service", "game-session-service"].flatMap((module, index) =>
  [1, 2].map(partition => ({
    id: 100 + index * 10 + partition, run_id: runId, head_sha: nativeHead,
    name: `Build and Test (${module} / ${partition})`, status: "completed",
    conclusion: "success", started_at: "2026-10-30T01:00:00Z",
    completed_at: "2026-10-30T02:00:00Z",
  }))
);
const jobRows = coreJobRows.concat(Array.from({ length: 97 }, (_, index) => ({
  id: 1000 + index, run_id: runId, head_sha: nativeHead,
  name: `Unrelated completed job ${index}`, status: "completed", conclusion: "success",
  started_at: "2026-10-30T01:00:00Z", completed_at: "2026-10-30T02:00:00Z",
})));
const coreArtifactRows = ["account-service", "game-session-service"].flatMap((module, index) =>
  [1, 2].map(partition => ({
    id: 700 + index * 10 + partition,
    name: `slow-service-ci-partition-${runId}-${testedMerge}-attempt-1-${module}-partition-${partition}`,
    expired: false, size_in_bytes: 1024, created_at: "2026-10-30T02:00:00Z",
    workflow_run: { id: runId, head_sha: nativeHead },
  }))
);
const artifactRows = coreArtifactRows.concat(Array.from({ length: 97 }, (_, index) => ({
  id: 2000 + index, name: `unrelated-artifact-${index}`, expired: false,
  size_in_bytes: 512, created_at: "2026-10-30T02:00:00Z",
  workflow_run: { id: runId, head_sha: nativeHead },
})));

function rowsForPage(rows, request, incompletePage) {
  const start = (request.page - 1) * request.per_page;
  let pageRows = rows.slice(start, start + request.per_page);
  if (request.page === incompletePage && pageRows.length > 0) pageRows = pageRows.slice(0, -1);
  return pageRows;
}

async function runFor(embedded, options = {}) {
  const eventName = options.eventName || "pull_request";
  const runAttempt = options.runAttempt ?? 1;
  const sourceSha = options.sourceSha || (eventName === "pull_request" ? testedMerge : nativeHead);
  const refName = options.refName || "develop";
  const eventHead = options.eventHead || nativeHead;
  const eventBase = options.eventBase || baseSha;
  const expectedRunTitle = eventName === "pull_request"
    ? `CI — Validation pr-42 base-${eventBase} head-${eventHead}`
    : `CI — Validation ${refName}`;
  const eventPullRequest = {
    number: 42,
    base: { sha: eventBase, ref: "develop" },
    head: { sha: eventHead, repo: { full_name: "contributor/firemud" } },
  };
  const context = {
    repo: { owner, repo: repository },
    eventName,
    sha: options.contextSha || sourceSha,
    payload: eventName === "pull_request" ? { pull_request: eventPullRequest } : {},
  };
  const apiAssociations = options.apiPullRequests === undefined ? [] : options.apiPullRequests;
  const run = {
    id: runId,
    head_sha: options.runHead || (eventName === "pull_request" ? nativeHead : sourceSha),
    run_attempt: options.apiRunAttempt ?? runAttempt,
    path: options.runPath || ".github/workflows/ci.yml",
    name: options.runName ?? expectedRunTitle,
    event: options.runEvent || eventName,
    repository: { full_name: options.runRepository || repositoryFullName },
    display_title: options.displayTitle ?? expectedRunTitle,
    pull_requests: apiAssociations,
  };
  const calls = [];
  const assertRepo = request => {
    assert.equal(request.owner, owner, "every Actions request must bind the owner");
    assert.equal(request.repo, repository, "every Actions request must bind the repository");
  };
  const actions = {
    getWorkflowRun: async request => {
      assertRepo(request);
      assert.deepEqual(Object.keys(request).sort(), ["owner", "repo", "run_id"]);
      assert.equal(request.run_id, runId);
      calls.push(["run", request]);
      return { data: run };
    },
    listJobsForWorkflowRunAttempt: async request => {
      assertRepo(request);
      assert.deepEqual(Object.keys(request).sort(), ["attempt_number", "owner", "page", "per_page", "repo", "run_id"]);
      assert.equal(request.run_id, runId);
      assert.equal(request.per_page, 100);
      assert.ok(request.attempt_number >= 1 && request.attempt_number <= runAttempt);
      calls.push(["jobs", request]);
      return {
        data: {
          total_count: jobRows.length,
          jobs: rowsForPage(jobRows, request, options.incompleteJobsPage).map(job => ({
            ...job,
            // GitHub clones a carried job with a new ID and the retry's raw
            // run_attempt while retaining its original execution window.
            id: job.id + (request.attempt_number - 1) * 10000,
            run_attempt: request.attempt_number,
          })),
        },
      };
    },
    listWorkflowRunArtifacts: async request => {
      assertRepo(request);
      assert.deepEqual(Object.keys(request).sort(), ["owner", "page", "per_page", "repo", "run_id"]);
      assert.equal(request.run_id, runId);
      assert.equal(request.per_page, 100);
      calls.push(["artifacts", request]);
      return {
        data: {
          total_count: artifactRows.length,
          artifacts: rowsForPage(artifactRows, request, options.incompleteArtifactsPage),
        },
      };
    },
  };
  const writes = [];
  const env = {
    CI_RUN_ID: String(runId),
    CI_RUN_ATTEMPT: String(runAttempt),
    CI_EVENT_NAME: eventName,
    CI_REF_NAME: refName,
    CI_SOURCE_SHA: sourceSha,
    CI_NATIVE_HEAD_SHA: options.nativeHead || (eventName === "pull_request" ? nativeHead : sourceSha),
    CI_PROVENANCE_PATH: `services/${embedded.module}/build/slow-service-ci-partition-provenance.json`,
  };
  const fileSystem = {
    mkdirSync(directory, options) { assert.ok(directory.startsWith("services/")); assert.equal(options.recursive, true); },
    writeFileSync(path, contents) { writes.push([path, String(contents)]); },
  };
  const github = { rest: { actions } };
  try {
    await captureSlowServiceProvenance({
      github,
      context,
      env,
      module: options.module || embedded.module,
      fileSystem,
    });
    assert.equal(writes.length, 1);
    assert.equal(writes[0][0], env.CI_PROVENANCE_PATH);
    return { ok: true, calls, provenance: JSON.parse(writes[0][1]) };
  } catch (error) {
    return { ok: false, calls, error: error.message };
  }
}

(async () => {
  assert.equal(jobRows.length, 101);
  assert.equal(artifactRows.length, 101);
  for (const embedded of input.scripts) {
    assert.ok(embedded.script.includes("capture-slow-service-provenance.cjs"));
    assert.ok(embedded.script.includes("module: process.env.CI_MODULE"));
    const validCases = [
      { eventName: "pull_request" },
      { eventName: "pull_request", runName: "CI — Validation",
        apiPullRequests: [{ number: 42, base: { sha: baseSha }, head: { sha: nativeHead } }] },
      { eventName: "push", refName: "develop" },
      { eventName: "workflow_dispatch", refName: "release/2026", runName: "CI — Validation" },
      { eventName: "pull_request", runAttempt: 2 },
    ];
    for (const options of validCases) {
      const result = await runFor(embedded, options);
      assert.equal(result.ok, true, `${embedded.module}/${options.eventName}: ${result.error}`);
      const attempts = options.runAttempt || 1;
      const expectedKinds = ["run", ...Array(attempts * 2).fill("jobs"), "artifacts", "artifacts"];
      assert.deepEqual(result.calls.map(([kind]) => kind), expectedKinds);
      assert.deepEqual(
        result.calls.filter(([kind]) => kind === "jobs").map(([, request]) => request.page),
        Array.from({ length: attempts }, () => [1, 2]).flat(),
      );
      assert.deepEqual(
        result.calls.filter(([kind]) => kind === "jobs").map(([, request]) => request.attempt_number),
        Array.from({ length: attempts }, (_, index) => [index + 1, index + 1]).flat(),
      );
      assert.deepEqual(
        result.calls.filter(([kind]) => kind === "artifacts").map(([, request]) => request.page),
        [1, 2],
      );
      assert.equal(result.provenance.run.head_sha, nativeHead);
      assert.equal(result.provenance.run.tested_source_sha,
        options.eventName === "pull_request" ? testedMerge : nativeHead);
      const expectedTitle = options.eventName === "pull_request"
        ? `CI — Validation pr-42 base-${baseSha} head-${nativeHead}`
        : `CI — Validation ${options.refName || "develop"}`;
      assert.equal(result.provenance.run.api_name, options.runName || expectedTitle);
      assert.equal(result.provenance.run.name, "CI — Validation");
      assert.equal(result.provenance.run.display_title, expectedTitle);
      assert.equal(result.provenance.run.run_attempt, attempts);
      assert.equal(result.provenance.attempts.length, attempts);
      for (const attempt of result.provenance.attempts) {
        assert.equal(attempt.jobs.length, 101);
        for (const job of attempt.jobs) {
          assert.equal(job.head_sha, result.provenance.run.head_sha);
          assert.equal(job.run_attempt, attempt.attempt, "raw API attempt is preserved per snapshot");
        }
        assert.equal(
          attempt.jobs[0].id,
          coreJobRows[0].id + (attempt.attempt - 1) * 10000,
          "attempt snapshots retain cloned job IDs",
        );
      }
      assert.equal(result.provenance.artifacts.items.length, 101);
      for (const artifact of result.provenance.artifacts.items) {
        assert.equal(artifact.workflow_run.head_sha, result.provenance.run.head_sha);
      }
      if (options.eventName === "pull_request") {
        assert.equal(result.provenance.run.pull_request.head_sha, nativeHead);
        assert.equal(result.provenance.run.tested_source_sha, testedMerge);
      } else {
        assert.equal(result.provenance.run.head_sha, result.provenance.run.tested_source_sha);
      }
    }

    const wrongIdentityCases = [
      { label: "wrong run head", options: { eventName: "pull_request", runHead: wrongSha } },
      { label: "wrong PR number", options: { eventName: "pull_request",
        apiPullRequests: [{ number: 43, base: { sha: baseSha }, head: { sha: nativeHead } }] } },
      { label: "wrong PR base", options: { eventName: "pull_request",
        apiPullRequests: [{ number: 42, base: { sha: wrongSha }, head: { sha: nativeHead } }] } },
      { label: "wrong PR head", options: { eventName: "pull_request",
        apiPullRequests: [{ number: 42, base: { sha: baseSha }, head: { sha: wrongSha } }] } },
      { label: "ambiguous PR association", options: { eventName: "pull_request",
        apiPullRequests: [
          { number: 42, base: { sha: baseSha }, head: { sha: nativeHead } },
          { number: 42, base: { sha: baseSha }, head: { sha: nativeHead } },
        ] } },
      { label: "wrong display title", options: { eventName: "pull_request", displayTitle: "unbound PR title" } },
      { label: "unrelated API name", options: { eventName: "pull_request", runName: "CI — Security" } },
      { label: "wrong workflow path", options: { eventName: "pull_request", runPath: ".github/workflows/other.yml" } },
      { label: "wrong event", options: { eventName: "pull_request", runEvent: "push" } },
      { label: "wrong tested source", options: { eventName: "pull_request", sourceSha: wrongSha, contextSha: testedMerge } },
      { label: "wrong context source", options: { eventName: "pull_request", contextSha: wrongSha } },
      { label: "wrong native head", options: { eventName: "pull_request", nativeHead: wrongSha } },
      { label: "wrong attempt", options: { eventName: "pull_request", runAttempt: 1, apiRunAttempt: 2 } },
      { label: "wrong repository", options: { eventName: "pull_request", runRepository: "other/repository" } },
      { label: "wrong dispatch title", options: { eventName: "workflow_dispatch", refName: "release/2026", runName: "CI — Validation develop" } },
      { label: "unsupported module", options: { eventName: "pull_request", module: "world-management-service" } },
    ];
    for (const { label, options } of wrongIdentityCases) {
      const result = await runFor(embedded, options);
      assert.equal(result.ok, false, `${embedded.module} accepted ${label}`);
      assert.ok(result.calls.length <= 1, `reject ${label} before job/artifact lookup`);
    }

    const incompleteJobs = await runFor(embedded, {
      eventName: "pull_request", incompleteJobsPage: 2,
    });
    assert.equal(incompleteJobs.ok, false, `${embedded.module} accepted incomplete jobs pagination`);
    assert.match(incompleteJobs.error, /Incomplete attempt 1 job page 2/);
    assert.deepEqual(incompleteJobs.calls.map(([kind]) => kind), ["run", "jobs", "jobs"]);

    const incompleteArtifacts = await runFor(embedded, {
      eventName: "pull_request", incompleteArtifactsPage: 2,
    });
    assert.equal(incompleteArtifacts.ok, false, `${embedded.module} accepted incomplete artifact pagination`);
    assert.match(incompleteArtifacts.error, /Incomplete workflow artifact page 2/);
    assert.deepEqual(
      incompleteArtifacts.calls.map(([kind]) => kind),
      ["run", "jobs", "jobs", "artifacts", "artifacts"],
    );
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
"""
try:
    fixture_result = subprocess.run(
        ["node", "-e", capture_node],
        input=json.dumps({"helper": str(helper_path), "scripts": capture_scripts}),
        check=True,
        capture_output=True,
        text=True,
    )
except (OSError, subprocess.CalledProcessError) as exc:
    raise SystemExit(f"ci workflow: shared provenance capture fixture failed: {exc}") from exc

coverage_report_step = find_step(ci, "build-and-test", "Upload Coverage Report", "ci workflow")
require_equal(
    coverage_report_step, ("with", "name"),
    "jacoco-${{ matrix.module }}-${{ matrix.partition }}-report-${{ github.sha }}-attempt-${{ github.run_attempt }}",
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

gradle_build = Path(sys.argv[6]).read_text(encoding="utf-8")
test_logging_match = re.search(
    (
        r"tasks\.withType<Test>\(\)\.configureEach\s*\{\s*"
        r"testLogging\s*\{(?P<settings>.*?)\n\s*\}\s*\}"
    ),
    gradle_build,
    re.DOTALL,
)
if test_logging_match is None:
    raise SystemExit(
        "build.gradle.kts: missing canonical Test task logging configuration"
    )
test_logging_settings = test_logging_match.group("settings")
for expected in (
    "exceptionFormat = TestExceptionFormat.FULL",
    "showExceptions = true",
    "showCauses = true",
    "showStackTraces = true",
):
    if expected not in test_logging_settings:
        raise SystemExit(
            f"build.gradle.kts: Test task failure logging must include {expected!r}"
        )
if re.search(r"\bevents\s*(?:=|\()", test_logging_settings):
    raise SystemExit(
        "build.gradle.kts: Test task failure logging must preserve inherited events"
    )
if "showStandardStreams = true" in test_logging_settings:
    raise SystemExit(
        "build.gradle.kts: Test task failure logging must not expose standard streams"
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
if "validation-summary" in ci["jobs"]:
    raise SystemExit("Optional Validation Summary must not delay the original workflow completion")
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
    for replacement in ("", f"# time {command}", f'echo "time {command}"',
                        f'"time {command}"', f"time {command} || true", f"time {command} &"):
        mutation = mutate_dev_tool_contract_command(ci, command)
        step = find_step(mutation, "dev-tool-contract-checks", "Validate dev tool contracts", "mutated ci workflow")
        step["run"] += "\n" + replacement
        try:
            require_dev_tool_contract_command(mutation, command)
        except SystemExit:
            continue
        raise SystemExit(
            f"ci dev-tool contract execution accepted replacement of {command} with {replacement!r}"
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
architecture_run = value_at(architecture_document_step, ("run",), "ci architecture document contract")
architecture_command = "bash ./dev-tools/tests/architecture-doc-contracts.sh"
if architecture_run not in (
    architecture_command,
    f"TIMEFORMAT='{architecture_command}: real=%3R user=%3U sys=%3S'\ntime {architecture_command}\n",
):
    raise SystemExit("ci architecture document contract must retain its exact executable command with optional timing")

require_contains(
    complete_contract_step,
    ("run",),
    "python3 -m unittest discover -s dev-tools/validation -p 'test_*.py'",
    "ci workflow",
)
require_contains(
    complete_contract_step,
    ("run",),
    DEV_TOOLS_TEST_DISCOVERY_COMMAND,
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
