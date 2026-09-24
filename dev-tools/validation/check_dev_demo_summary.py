#!/usr/bin/env python3
"""Validate the dev-demo workflow and its summary-producing shell paths."""

# Contract violations intentionally retain the prior assertion-based failure API.
# ruff: noqa: TRY004

from __future__ import annotations

import ast
import re
import shlex
import sys
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

import yaml

WORKFLOW_RELATIVE_PATH = Path(".github/workflows/dev-demo.yml")
SUMMARY_HELPER_PATH = "./dev-tools/hosted/dev-demo/write-dev-demo-summary.sh"
SUMMARY_HELPER_NAME = "write-dev-demo-summary.sh"
ALLOWED_WORKSPACE_ROOT_VARIABLES = frozenset(
    {"FIREMUD_REPO_ROOT", "GITHUB_WORKSPACE", "ROOT_DIR"}
)

# The workflow deliberately has one supported summary language. Keep this
# parser narrower than shell itself: a direct, line-continued invocation of
# the canonical helper, followed by the exact plan outputs, is the only
# supported helper form. Unknown shell syntax is rejected rather than being
# silently omitted from the sensitive-output scan.
SUMMARY_HELPER_COMMAND_PATTERN = re.compile(
    r"^(?:"
    r"(?:target|success|destroyed) "
    r'"\$\{\{ needs[.]dev-demo-plan[.]outputs[.]head_sha \}\}" '
    r'"\$\{\{ needs[.]dev-demo-plan[.]outputs[.]image_tag \}\}" '
    r'"\$\{\{ needs[.]dev-demo-plan[.]outputs[.]hostname \}\}" '
    r'"\$\{\{ needs[.]dev-demo-plan[.]outputs[.]telnet_port \}\}"'
    r"|"
    r"unavailable "
    r'"\$\{\{ needs[.]dev-demo-plan[.]outputs[.]head_sha \}\}" '
    r'"\$\{\{ needs[.]dev-demo-plan[.]outputs[.]image_tag \}\}" '
    r'"\$\{\{ needs[.]dev-demo-plan[.]outputs[.]hostname \}\}" '
    r'"\$\{\{ needs[.]dev-demo-plan[.]outputs[.]telnet_port \}\}" '
    r'"cluster-access"'
    r"|"
    r"failure "
    r'"\$\{\{ needs[.]dev-demo-plan[.]outputs[.]head_sha \}\}" '
    r'"\$\{\{ needs[.]dev-demo-plan[.]outputs[.]image_tag \}\}" '
    r'"\$\{\{ needs[.]dev-demo-plan[.]outputs[.]hostname \}\}" '
    r'"\$\{\{ needs[.]dev-demo-plan[.]outputs[.]telnet_port \}\}" '
    r'"\$\{DEV_DEMO_STAGE:-unknown\}"'
    r') >> "\$GITHUB_STEP_SUMMARY"$'
)

SUMMARY_METADATA_LINES = (
    "{",
    'echo ""',
    'echo "- Demo login username: ${DEMO_SMOKE_USERNAME}"',
    'echo "- Demo login email: ${DEMO_SMOKE_EMAIL}"',
    'echo "- Demo login password: repository smoke default (not printed)"',
    '} >> "$GITHUB_STEP_SUMMARY"',
)

REPO_SHELL_HELPER_REFERENCE = re.compile(
    r"(?<![A-Za-z0-9_./$-])"
    r"(?:(?:bash|source|\.)[ \t]+)?"
    r"(?P<path>(?:(?:\./)?|\$(?:\{[A-Za-z_][A-Za-z0-9_]*\}|[A-Za-z_][A-Za-z0-9_]*)/|/[^\s\"']+/)"
    r"dev-tools/[A-Za-z0-9_./-]+[.]sh)"
    r"(?![A-Za-z0-9_./-])"
)

FORBIDDEN_SUMMARY_REFERENCE = re.compile(
    r"DEMO_SMOKE_PASSWORD|"
    r"\$\{?BOOTSTRAP_SECRET_DIR\}?/password|"
    r"\$\{\{\s*secrets[.]|"
    r"steps[.][A-Za-z0-9_-]+[.]outputs[.]password|"
    r"(?<![;&|\n])[^;&|\n]*(?<![A-Za-z])"
    r"(?:secrets?|secs?|credentials?|creds?)(?![A-Za-z])"
    r"[^;&|\n]*(?:\|[^;&|\n]*)*\|\s*base64\s+"
    r"(?:-[A-Za-z]*d[A-Za-z]*|--decode)\b",
    re.IGNORECASE,
)

SHELL_IF_START = re.compile(r"^if\b.*;[ \t]*then$")
BOOTSTRAP_PYTHON_HEREDOC_OPENER = 'cat >"${BOOTSTRAP_SCRIPT}" <<\'PY\''
BOOTSTRAP_SECRET_COMMAND_PREFIX = (
    "kubectl",
    "-n",
    "${PREVIEW_NAMESPACE}",
    "create",
    "secret",
    "generic",
    "dev-demo-bootstrap-env",
)
BOOTSTRAP_SECRET_FILE_MAPPINGS = {
    "DEMO_SMOKE_EMAIL": "${BOOTSTRAP_SECRET_DIR}/email",
    "DEMO_SMOKE_PASSWORD": "${BOOTSTRAP_SECRET_DIR}/password",
    "DEMO_SMOKE_USERNAME": "${BOOTSTRAP_SECRET_DIR}/username",
}
BOOTSTRAP_MANIFEST_REQUIRED_MARKERS = (
    "cleanup_bootstrap_temp_dir() {",
    'if rm -rf "${BOOTSTRAP_SECRET_DIR}"; then',
    'echo "::error::Failed to remove dev-demo bootstrap credential files"',
    "if ! cleanup_bootstrap_temp_dir; then",
    "cleanup_bootstrap_resources() {",
    "trap cleanup_bootstrap_resources EXIT",
    "trap 'exit 130' INT",
    "trap 'exit 143' TERM",
)
BOOTSTRAP_ACCOUNT_TRANSPORT_REQUIRED_MARKERS = (
    "cleanup_bootstrap_port_forward() {",
    "BOOTSTRAP_PORT_FORWARD_PID=$!",
    "kubectl -n \"${PREVIEW_NAMESPACE}\" port-forward",
    "--address 127.0.0.1",
    "service/spring-cloud-gateway",
    ":80",
    "BOOTSTRAP_MODE=account",
    'BOOTSTRAP_GATEWAY_BASE_URL="http://127.0.0.1:${BOOTSTRAP_GATEWAY_PORT}"',
    'gateway_base_url = os.environ["BOOTSTRAP_GATEWAY_BASE_URL"]',
    'return f"{gateway_base_url}/api/account{path}"',
    'cleanup_bootstrap_port_forward\n          if [[ ! -s "${BOOTSTRAP_ACCOUNT_ID_FILE}" ]]; then',
    '--from-file=account-id="${BOOTSTRAP_ACCOUNT_ID_FILE}"',
    'value: session',
)
BOOTSTRAP_PORT_FORWARD_READINESS_REQUIRED_MARKERS = (
    'BOOTSTRAP_PORT_FORWARD_LOG=/tmp/dev-demo-gateway-port-forward.log',
    'if ! kill -0 "${BOOTSTRAP_PORT_FORWARD_PID}" >/dev/null 2>&1; then',
    "Forwarding from 127[.]0[.]0[.]1:([0-9]+) -> 80",
    'BOOTSTRAP_GATEWAY_PORT="$({ sed -nE',
    '[[ "${BOOTSTRAP_GATEWAY_PORT}" =~ ^[0-9]+$ ]]',
    "BOOTSTRAP_GATEWAY_PORT <= 65535",
    'cat "${BOOTSTRAP_PORT_FORWARD_LOG}" || true',
)
BOOTSTRAP_DYNAMIC_PORT_FORWARD_PATTERN = re.compile(
    r"service/spring-cloud-gateway(?:\s+\\)?\s+:80"
)
BOOTSTRAP_PORT_FORWARD_LOOP_PATTERN = re.compile(
    r"for attempt in \{1\.\.(?P<attempts>[0-9]+)\}; do"
)
BOOTSTRAP_ACCOUNT_ID_REQUIRED_MARKER = "account_file.write(str(account_id))"
BOOTSTRAP_CREDENTIAL_VALIDATION = """for credential in DEMO_SMOKE_EMAIL DEMO_SMOKE_PASSWORD DEMO_SMOKE_USERNAME; do
  if [[ -z "${!credential:-}" ]]; then
    echo "::error::${credential} is empty; refusing to create dev-demo bootstrap credentials" >&2
    exit 1
  fi
done
BOOTSTRAP_SECRET_DIR="$(mktemp -d)"""
BOOTSTRAP_SECRET_CLEANUP_AND_CREATE = """if ! cleanup_bootstrap_secret; then
  exit 1
fi
kubectl -n "${PREVIEW_NAMESPACE}" create secret generic dev-demo-bootstrap-env"""
BOOTSTRAP_TEMP_DIRECTORY_CLEANUP_SUCCESS = """if rm -rf "${BOOTSTRAP_SECRET_DIR}"; then
    BOOTSTRAP_SECRET_DIR=
    return 0"""
BOOTSTRAP_TEMP_DIRECTORY_CLEANUP_FAILURE = """echo "::error::Failed to remove dev-demo bootstrap credential files" >&2
  return 1"""
BOOTSTRAP_POST_LOG_CLEANUP = """kubectl -n "${PREVIEW_NAMESPACE}" logs dev-demo-bootstrap | tee "${BOOTSTRAP_POD_LOG}"
  kubectl -n "${PREVIEW_NAMESPACE}" delete pod dev-demo-bootstrap --ignore-not-found >/dev/null 2>&1 || true
  kubectl -n "${PREVIEW_NAMESPACE}" delete configmap dev-demo-bootstrap-script --ignore-not-found >/dev/null 2>&1 || true
  cleanup_bootstrap_secret"""
BOOTSTRAP_MANIFEST_HEREDOC_OPENER = (
    "cat <<'EOF' | kubectl -n \"${PREVIEW_NAMESPACE}\" apply -f -\n"
)


@dataclass(frozen=True)
class WorkflowRunSource:
    job_name: str
    step_name: str
    source: str


def normalize_script(script: str) -> str:
    return " ".join(script.split())


def normalize_nonempty_lines(script: str) -> str:
    return "\n".join(
        " ".join(line.split()) for line in script.splitlines() if line.strip()
    )


def _assert_supported_shell_if(line: str) -> None:
    if re.match(r"^if(?:\s|$)", line) and not SHELL_IF_START.fullmatch(line):
        raise AssertionError(
            "unsupported shell if form; expected a single-line 'if ...; then' opener: "
            f"{line}"
        )


def closing_fi_index(lines: list[str], if_index: int) -> int | None:
    _assert_supported_shell_if(lines[if_index])
    nested_if_depth = 0
    for index in range(if_index + 1, len(lines)):
        line = lines[index]
        if re.match(r"^if(?:\s|$)", line):
            _assert_supported_shell_if(line)
            nested_if_depth += 1
        elif line == "fi":
            if nested_if_depth == 0:
                return index
            nested_if_depth -= 1
    return None


def _summary_write_line_ranges(source: str) -> list[tuple[int, int]]:
    """Find only the canonical direct helper and metadata summary regions."""

    lines = source.splitlines()
    ranges: list[tuple[int, int]] = []
    index = 0
    while index < len(lines):
        if lines[index].strip() == f"bash {SUMMARY_HELPER_PATH} \\":
            end = index
            command_lines = [lines[index].strip()]
            while command_lines[-1].endswith("\\"):
                end += 1
                if end >= len(lines):
                    break
                command_lines.append(lines[end].strip())
            if end < len(lines):
                command = " ".join(
                    line.removesuffix("\\").rstrip() for line in command_lines
                )
                if SUMMARY_HELPER_COMMAND_PATTERN.fullmatch(
                    command.removeprefix(f"bash {SUMMARY_HELPER_PATH} ")
                ):
                    ranges.append((index, end))
                index = end
        if index + len(SUMMARY_METADATA_LINES) <= len(lines):
            candidate = tuple(
                line.strip()
                for line in lines[index : index + len(SUMMARY_METADATA_LINES)]
            )
            if candidate == SUMMARY_METADATA_LINES:
                ranges.append((index, index + len(SUMMARY_METADATA_LINES) - 1))
                index += len(SUMMARY_METADATA_LINES) - 1
        index += 1
    return ranges


def summary_write_regions(source: str) -> list[str]:
    """Return supported regions that write to GITHUB_STEP_SUMMARY."""

    lines = source.splitlines()
    return [
        "\n".join(lines[start : end + 1])
        for start, end in _summary_write_line_ranges(source)
    ]


def _repo_shell_helper_references(source: str) -> list[tuple[int, str]]:
    """Find explicit repository shell-helper paths and their source lines."""

    return [
        (source.count("\n", 0, match.start()), match.group("path"))
        for match in REPO_SHELL_HELPER_REFERENCE.finditer(source)
    ]


def _repo_shell_helper_path(reference: str, root_dir: Path) -> Path:
    if reference.startswith("$"):
        variable, separator, suffix = reference.partition("/dev-tools/")
        variable = variable[2:-1] if variable.startswith("${") else variable[1:]
        if not separator or variable not in ALLOWED_WORKSPACE_ROOT_VARIABLES:
            raise AssertionError(f"unsupported repository shell helper root: {reference}")
        return (root_dir / "dev-tools" / suffix).resolve()
    if reference.startswith("/"):
        return Path(reference).resolve()
    return (root_dir / reference.removeprefix("./")).resolve()


def has_forbidden_summary_reference(text: str) -> bool:
    normalized_text = re.sub(r"[ \t]+", " ", text)
    return FORBIDDEN_SUMMARY_REFERENCE.search(normalized_text) is not None


def collect_workflow_run_sources(workflow: dict) -> list[WorkflowRunSource]:
    jobs = workflow.get("jobs") if isinstance(workflow, dict) else None
    if not isinstance(jobs, dict):
        raise AssertionError("dev-demo workflow must define jobs as a mapping")
    sources: list[WorkflowRunSource] = []
    for job_name, job in jobs.items():
        if not isinstance(job, dict):
            raise AssertionError(
                f"dev-demo workflow job {job_name!r} must be a mapping"
            )
        steps = job.get("steps")
        if steps is None:
            continue
        if not isinstance(steps, list):
            raise AssertionError(
                f"dev-demo workflow job {job_name!r} steps must be a list"
            )
        for step_index, step in enumerate(steps):
            if not isinstance(step, dict):
                raise AssertionError(
                    f"dev-demo workflow job {job_name!r} step {step_index} must be a mapping"
                )
            run = step.get("run")
            if isinstance(run, str):
                sources.append(
                    WorkflowRunSource(job_name, str(step.get("name", step_index)), run)
                )
    return sources


def discover_summary_writers(
    workflow_run_sources: Iterable[WorkflowRunSource], root_dir: Path
) -> list[WorkflowRunSource]:
    """Find summary writers and reject unsupported shell/source forms."""

    root_dir = root_dir.resolve()
    summary_writers: list[WorkflowRunSource] = []
    pending_helpers: list[Path] = []
    seen_helpers: set[Path] = set()
    for current in workflow_run_sources:
        direct_ranges = _summary_write_line_ranges(current.source)
        if any(
            marker in current.source
            for marker in ("GITHUB_STEP_SUMMARY", SUMMARY_HELPER_NAME)
        ):
            summary_lines = {
                line_index
                for line_index, line in enumerate(current.source.splitlines())
                if "GITHUB_STEP_SUMMARY" in line
            }
            expected_summary_lines = {end for _, end in direct_ranges}
            if summary_lines != expected_summary_lines:
                raise AssertionError(
                    "dev-demo summary must use only the canonical direct helper or "
                    "the exact safe metadata block; unsupported or indirect summary "
                    f"syntax found in {current.job_name}/{current.step_name}"
                )
            helper_lines = {
                line_index
                for line_index, line in enumerate(current.source.splitlines())
                if SUMMARY_HELPER_NAME in line
            }
            expected_helper_lines = {
                start
                for start, _ in direct_ranges
                if current.source.splitlines()[start].strip().startswith("bash ")
            }
            if helper_lines != expected_helper_lines:
                raise AssertionError(
                    "dev-demo summary helper must use the canonical direct invocation; "
                    f"unsupported or indirect helper syntax found in {current.job_name}/{current.step_name}"
                )
            if direct_ranges:
                summary_writers.append(current)

        direct_helper_starts = {
            start
            for start, _ in direct_ranges
            if current.source.splitlines()[start].strip().startswith("bash ")
        }
        for line_index, helper_reference in _repo_shell_helper_references(
            current.source
        ):
            helper_path = _repo_shell_helper_path(helper_reference, root_dir)
            canonical_path = (root_dir / SUMMARY_HELPER_PATH.removeprefix("./")).resolve()
            if helper_path == canonical_path:
                if line_index not in direct_helper_starts:
                    raise AssertionError(
                        "dev-demo summary helper must use the canonical direct invocation; "
                        f"unsupported or indirect helper syntax found in {current.job_name}/{current.step_name}"
                    )
                continue
            pending_helpers.append(helper_path)

    while pending_helpers:
        helper_path = pending_helpers.pop()
        if helper_path in seen_helpers:
            continue
        seen_helpers.add(helper_path)
        try:
            helper_path.relative_to(root_dir)
        except ValueError as exc:
            raise AssertionError(
                "repository shell helper path escapes the repository: "
                f"{helper_path}"
            ) from exc
        if not helper_path.is_file():
            raise AssertionError(f"repository shell helper is missing: {helper_path}")
        try:
            helper_source = helper_path.read_text(encoding="utf-8")
        except OSError as exc:
            raise AssertionError(
                f"repository shell helper cannot be read: {helper_path}"
            ) from exc
        if "GITHUB_STEP_SUMMARY" in helper_source:
            raise AssertionError(
                "repository shell helper must not write to GITHUB_STEP_SUMMARY: "
                f"{helper_path}"
            )
        for _, helper_reference in _repo_shell_helper_references(helper_source):
            nested_path = _repo_shell_helper_path(helper_reference, root_dir)
            if nested_path == (
                root_dir / SUMMARY_HELPER_PATH.removeprefix("./")
            ).resolve():
                raise AssertionError(
                    "repository shell helper must not invoke the canonical dev-demo "
                    f"summary helper: {helper_path}"
                )
            pending_helpers.append(nested_path)

    if any(
        _summary_write_line_ranges(source.source)
        for source in summary_writers
    ):
        helper_path = root_dir / SUMMARY_HELPER_PATH.removeprefix("./")
        if not helper_path.is_file():
            raise AssertionError(
                "canonical dev-demo summary helper is missing: " f"{helper_path}"
            )
        try:
            helper_source = helper_path.read_text(encoding="utf-8")
        except OSError as exc:
            raise AssertionError(
                "canonical dev-demo summary helper cannot be read: " f"{helper_path}"
            ) from exc
        if has_forbidden_summary_reference(helper_source):
            raise AssertionError(
                "canonical dev-demo summary helper must not reference "
                "bootstrap credential material"
            )
    return summary_writers


def _load_workflow(root: Path) -> dict:
    workflow_path = root / WORKFLOW_RELATIVE_PATH
    if not workflow_path.is_file():
        raise AssertionError(f"dev-demo workflow is missing: expected {workflow_path}")
    try:
        workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        raise AssertionError("dev-demo workflow is not valid YAML") from exc
    if not isinstance(workflow, dict):
        raise AssertionError("dev-demo workflow must be a mapping")
    return workflow


def _find_step(deploy_job: dict, name: str) -> dict:
    step = next(
        (
            candidate
            for candidate in deploy_job["steps"]
            if isinstance(candidate, dict) and candidate.get("name") == name
        ),
        None,
    )
    if step is None:
        raise AssertionError(f"dev-demo-deploy job missing required step {name!r}")
    return step


def _cleanup_function_end_index(lines: list[str], function_start: int) -> int | None:
    brace_depth = 0
    for index in range(function_start, len(lines)):
        line = lines[index]
        if line.endswith("() {") or line == "{":
            brace_depth += 1
        elif line == "}":
            brace_depth -= 1
            if brace_depth == 0:
                return index + 1
    return None


def _extract_bootstrap_pod(bootstrap_manifest: str) -> dict:
    if bootstrap_manifest.count(BOOTSTRAP_MANIFEST_HEREDOC_OPENER) != 1:
        raise AssertionError(
            "dev-demo bootstrap step must contain exactly one expected pod manifest heredoc opener"
        )
    try:
        manifest_start = bootstrap_manifest.index(BOOTSTRAP_MANIFEST_HEREDOC_OPENER)
        manifest_start = bootstrap_manifest.index("\n", manifest_start) + 1
        try:
            manifest_end = bootstrap_manifest.index("\nEOF\n", manifest_start)
        except ValueError:
            if not bootstrap_manifest.endswith("\nEOF"):
                raise
            manifest_end = len(bootstrap_manifest) - len("\nEOF")
    except ValueError as exc:
        raise AssertionError(
            "dev-demo bootstrap step must contain the expected pod manifest heredoc"
        ) from exc
    try:
        pod = yaml.safe_load(bootstrap_manifest[manifest_start:manifest_end])
    except yaml.YAMLError as exc:
        raise AssertionError(
            "dev-demo bootstrap pod manifest heredoc is not valid YAML"
        ) from exc
    if not isinstance(pod, dict):
        raise AssertionError("dev-demo bootstrap pod manifest must be a mapping")
    return pod


def _validate_bootstrap_secret_command(command_lines: list[str]) -> None:
    command = " ".join(
        line.removesuffix("\\").rstrip() for line in command_lines
    )
    try:
        tokens = shlex.split(command)
    except ValueError as exc:
        raise AssertionError(
            "dev-demo bootstrap credential secret must use direct create without "
            "apply annotations; command has invalid shell quoting"
        ) from exc

    prefix_length = len(BOOTSTRAP_SECRET_COMMAND_PREFIX)
    if tokens[:prefix_length] != list(BOOTSTRAP_SECRET_COMMAND_PREFIX):
        raise AssertionError(
            "dev-demo bootstrap credential secret must use direct create without "
            "apply annotations; expected kubectl create for "
            'dev-demo-bootstrap-env in "${PREVIEW_NAMESPACE}"'
        )

    file_mappings: dict[str, str] = {}
    duplicate_keys: list[str] = []
    malformed_arguments = False
    for argument in tokens[prefix_length:]:
        if not argument.startswith("--from-file="):
            malformed_arguments = True
            continue
        key, separator, path = argument.removeprefix(
            "--from-file="
        ).partition("=")
        if not separator or not key or not path:
            malformed_arguments = True
            continue
        if key in file_mappings:
            duplicate_keys.append(key)
        file_mappings[key] = path

    issues: list[str] = []
    if malformed_arguments:
        issues.append("only --from-file arguments are allowed after the secret name")
    if duplicate_keys:
        issues.append(
            f"duplicate file keys: {', '.join(sorted(set(duplicate_keys)))}"
        )
    missing_keys = sorted(set(BOOTSTRAP_SECRET_FILE_MAPPINGS) - file_mappings.keys())
    if missing_keys:
        issues.append(f"missing file keys: {', '.join(missing_keys)}")
    unexpected_keys = sorted(
        file_mappings.keys() - set(BOOTSTRAP_SECRET_FILE_MAPPINGS)
    )
    if unexpected_keys:
        issues.append(f"unexpected file keys: {', '.join(unexpected_keys)}")
    mismatched_paths = sorted(
        key
        for key, expected_path in BOOTSTRAP_SECRET_FILE_MAPPINGS.items()
        if key in file_mappings and file_mappings[key] != expected_path
    )
    if mismatched_paths:
        issues.append(
            "file keys have unexpected source paths: "
            f"{', '.join(mismatched_paths)}"
        )
    if issues:
        raise AssertionError(
            "dev-demo bootstrap credential secret must use direct create without "
            "apply annotations and only the expected file-backed credentials; "
            + "; ".join(issues)
        )


def _validate_bootstrap_temp_directory_cleanup(bootstrap_lines: list[str]) -> None:
    cleanup_starts = [
        index
        for index, line in enumerate(bootstrap_lines)
        if line == "cleanup_bootstrap_temp_dir() {"
    ]
    if len(cleanup_starts) != 1:
        raise AssertionError(
            "dev-demo bootstrap must contain exactly one cleanup_bootstrap_temp_dir function"
        )
    cleanup_end = _cleanup_function_end_index(bootstrap_lines, cleanup_starts[0])
    if cleanup_end is None:
        raise AssertionError(
            "dev-demo bootstrap cleanup function has no same-nesting closing brace"
        )
    cleanup_lines = bootstrap_lines[cleanup_starts[0] : cleanup_end]
    success_start = next(
        (
            index
            for index, line in enumerate(cleanup_lines)
            if 'if rm -rf "${BOOTSTRAP_SECRET_DIR}"; then' in line
        ),
        None,
    )
    if success_start is None:
        raise AssertionError(
            "dev-demo bootstrap temp directory cleanup success branch is missing"
        )
    success_end = closing_fi_index(cleanup_lines, success_start)
    if success_end is None:
        raise AssertionError(
            "dev-demo bootstrap temp directory cleanup success branch has no closing fi"
        )
    success_return = next(
        (
            index
            for index in range(success_start + 1, success_end)
            if "return 0" in cleanup_lines[index]
        ),
        None,
    )
    if success_return is None:
        raise AssertionError(
            "dev-demo bootstrap temp directory cleanup success branch must return 0"
        )
    clear_directory_lines = [
        index
        for index, line in enumerate(cleanup_lines)
        if line == "BOOTSTRAP_SECRET_DIR="
    ]
    if (
        len(clear_directory_lines) != 1
        or not success_start < clear_directory_lines[0] < success_return < success_end
    ):
        raise AssertionError(
            "dev-demo bootstrap temp directory must clear its variable only in the "
            "successful rm branch before return 0"
        )


def _validate_bootstrap_pod_spec(bootstrap_manifest: str) -> None:
    bootstrap_pod = _extract_bootstrap_pod(bootstrap_manifest)
    pod_spec = bootstrap_pod.get("spec")
    if not isinstance(pod_spec, dict):
        raise AssertionError("dev-demo bootstrap pod must define spec as a mapping")
    containers = pod_spec.get("containers")
    if not isinstance(containers, list) or not containers:
        raise AssertionError(
            "dev-demo bootstrap pod spec.containers must be a non-empty list"
        )
    if not isinstance(containers[0], dict):
        raise AssertionError(
            "dev-demo bootstrap pod spec.containers[0] must be a mapping"
        )
    if containers[0].get("envFrom", []) != [
        {"secretRef": {"name": "dev-demo-bootstrap-env"}}
    ]:
        raise AssertionError(
            "dev-demo bootstrap pod must import dev-demo-bootstrap-env"
        )


def _bootstrap_python_source(bootstrap_manifest: str) -> str:
    """Extract the Python heredoc so shell comments and strings cannot be calls."""

    lines = bootstrap_manifest.splitlines()
    opener_indices = [
        index
        for index, line in enumerate(lines)
        if line.strip() == BOOTSTRAP_PYTHON_HEREDOC_OPENER
    ]
    if len(opener_indices) != 1:
        raise AssertionError(
            "dev-demo bootstrap must contain exactly one Python script heredoc"
        )
    start = opener_indices[0] + 1
    try:
        end = next(index for index in range(start, len(lines)) if lines[index].strip() == "PY")
    except StopIteration as exc:
        raise AssertionError(
            "dev-demo bootstrap Python script heredoc is unterminated"
        ) from exc
    return "\n".join(lines[start:end])


def _player_bootstrap_requests(bootstrap_manifest: str) -> list[ast.Call]:
    """Return actual Python calls, excluding comments and string literals."""

    try:
        python_tree = ast.parse(_bootstrap_python_source(bootstrap_manifest))
    except SyntaxError as exc:
        raise AssertionError(
            "dev-demo bootstrap Python script must be valid Python"
        ) from exc
    return [
        node
        for node in ast.walk(python_tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Name)
        and node.func.id == "post_json"
        and len(node.args) >= 2
        and isinstance(node.args[0], ast.Call)
        and isinstance(node.args[0].func, ast.Name)
        and node.args[0].func.id == "public_account_url"
        and node.args[0].args
        and isinstance(node.args[0].args[0], ast.Constant)
        and node.args[0].args[0].value == "/auth/player-bootstrap"
    ]


def _validate_player_bootstrap_payload(request: ast.Call) -> bool:
    if len(request.args) < 2 or not isinstance(request.args[1], ast.Dict):
        return False
    payload = request.args[1]
    if payload is None or len(payload.keys) != 2:
        return False
    fields = {
        key.value: value.id
        for key, value in zip(payload.keys, payload.values, strict=True)
        if isinstance(key, ast.Constant)
        and isinstance(key.value, str)
        and isinstance(value, ast.Name)
    }
    return fields == {"accountIdentifier": "email", "secret": "password"}


def _validate_bootstrap_manifest(bootstrap_manifest: str) -> None:
    normalized = normalize_script(bootstrap_manifest)
    for expected in BOOTSTRAP_MANIFEST_REQUIRED_MARKERS:
        if normalize_script(expected) not in normalized:
            raise AssertionError(
                f"dev-demo bootstrap step contract missing: {expected}"
            )
    for expected in BOOTSTRAP_ACCOUNT_TRANSPORT_REQUIRED_MARKERS:
        if normalize_script(expected) not in normalized:
            raise AssertionError(
                "dev-demo player bootstrap must use the authenticated Kubernetes "
                f"port-forward transport; missing: {expected}"
            )
    if re.search(r"\bBOOTSTRAP_GATEWAY_PORT\s*=\s*[0-9]+\b", normalized):
        raise AssertionError(
            "dev-demo player bootstrap must use kubectl's dynamically selected local port"
        )
    if BOOTSTRAP_DYNAMIC_PORT_FORWARD_PATTERN.search(normalized) is None:
        raise AssertionError(
            "dev-demo player bootstrap must use kubectl's dynamic :80 local-port syntax"
        )
    for expected in BOOTSTRAP_PORT_FORWARD_READINESS_REQUIRED_MARKERS:
        if normalize_script(expected) not in normalized:
            raise AssertionError(
                "dev-demo player bootstrap must prove the dynamic port-forward binding; "
                f"missing: {expected}"
            )
    readiness_loop_match = BOOTSTRAP_PORT_FORWARD_LOOP_PATTERN.search(normalized)
    if readiness_loop_match is None or not 1 <= int(
        readiness_loop_match["attempts"]
    ) <= 60:
        raise AssertionError(
            "dev-demo player bootstrap must use a short bounded port-forward readiness loop"
        )
    readiness_loop = readiness_loop_match.group(0)
    python_invocation = normalize_script('python3 "${BOOTSTRAP_SCRIPT}"')
    liveness_check = normalize_script(
        'if ! kill -0 "${BOOTSTRAP_PORT_FORWARD_PID}" >/dev/null 2>&1; then'
    )
    python_index = normalized.find(python_invocation)
    if python_index == -1:
        raise AssertionError(
            'dev-demo player bootstrap must invoke Python as python3 "${BOOTSTRAP_SCRIPT}"'
        )
    if normalized.find(readiness_loop) > python_index:
        raise AssertionError(
            "dev-demo player bootstrap must prove the port-forward before invoking Python"
        )
    if (
        normalized.count(liveness_check) < 2
        or normalized.rfind(liveness_check) > python_index
    ):
        raise AssertionError(
            "dev-demo player bootstrap must recheck port-forward liveness before invoking Python"
        )
    if normalize_script(BOOTSTRAP_ACCOUNT_ID_REQUIRED_MARKER) not in normalized:
        raise AssertionError(
            "dev-demo bootstrap must write the account id as text to preserve the "
            "account-id file flow"
        )

    normalized_lines = normalize_nonempty_lines(bootstrap_manifest)
    credential_validation = normalize_nonempty_lines(BOOTSTRAP_CREDENTIAL_VALIDATION)
    if credential_validation not in normalized_lines:
        raise AssertionError(
            "dev-demo bootstrap must reject empty credentials before creating temporary files"
        )
    if 'chmod 700 "${BOOTSTRAP_SECRET_DIR}"' in bootstrap_manifest:
        raise AssertionError(
            "dev-demo bootstrap must rely on mktemp directory permissions"
        )
    secret_cleanup_and_create = normalize_nonempty_lines(
        BOOTSTRAP_SECRET_CLEANUP_AND_CREATE
    )
    if secret_cleanup_and_create not in normalized_lines:
        raise AssertionError(
            "dev-demo bootstrap must delete stale credentials before direct secret creation"
        )

    source_lines = bootstrap_manifest.splitlines()
    try:
        secret_start = next(
            index
            for index, line in enumerate(source_lines)
            if "create secret generic dev-demo-bootstrap-env" in line
        )
    except StopIteration as exc:
        raise AssertionError(
            "dev-demo bootstrap must create its credential secret directly"
        ) from exc
    secret_command_lines: list[str] = []
    secret_index = secret_start
    while True:
        line = source_lines[secret_index].strip()
        secret_command_lines.append(line)
        if not line.endswith("\\"):
            break
        secret_index += 1
        if secret_index >= len(source_lines):
            raise AssertionError("dev-demo bootstrap secret command is unterminated")
    _validate_bootstrap_secret_command(secret_command_lines)
    cleanup_success = normalize_nonempty_lines(BOOTSTRAP_TEMP_DIRECTORY_CLEANUP_SUCCESS)
    if cleanup_success not in normalized_lines:
        raise AssertionError(
            "dev-demo bootstrap temp directory must clear its variable only after rm succeeds"
        )
    cleanup_failure = normalize_nonempty_lines(BOOTSTRAP_TEMP_DIRECTORY_CLEANUP_FAILURE)
    if cleanup_failure not in normalized_lines:
        raise AssertionError(
            "dev-demo bootstrap temp directory removal failure must return failure"
        )
    post_log_cleanup = normalize_nonempty_lines(BOOTSTRAP_POST_LOG_CLEANUP)
    if post_log_cleanup not in normalized_lines:
        raise AssertionError(
            "dev-demo bootstrap must remove its credential secret after successful pod logging"
        )

    player_bootstrap_requests = _player_bootstrap_requests(bootstrap_manifest)
    player_bootstrap_request_count = len(player_bootstrap_requests)
    if player_bootstrap_request_count != 1:
        raise AssertionError(
            "dev-demo bootstrap must contain exactly one /auth/player-bootstrap request "
            f"(found {player_bootstrap_request_count})"
        )
    if not _validate_player_bootstrap_payload(player_bootstrap_requests[0]):
        raise AssertionError(
            "dev-demo bootstrap must send exactly accountIdentifier and secret "
            "to /auth/player-bootstrap"
        )

    bootstrap_lines = [line.strip() for line in bootstrap_manifest.splitlines()]
    _validate_bootstrap_temp_directory_cleanup(bootstrap_lines)
    _validate_bootstrap_pod_spec(bootstrap_manifest)


def _validate_smoke_account_contract(root: Path) -> None:
    smoke_script_paths = (
        root / "services/game-session-service/websocket-login-look-smoke.sh",
        root / "services/tcp-proxy-service/telnet-login-look-smoke.sh",
    )
    required_markers = (
        'login_email = os.environ.get("SMOKE_LOGIN_EMAIL", os.environ["DEMO_SMOKE_EMAIL"])',
        "verify_smoke_account(account_api_base, login_email, password, timeout_seconds)",
    )
    for smoke_script in smoke_script_paths:
        if not smoke_script.is_file():
            raise AssertionError(
                f"Smoke account contract script is missing: {smoke_script}"
            )
        source = smoke_script.read_text(encoding="utf-8")
        for required_marker in required_markers:
            if required_marker not in source:
                raise AssertionError(
                    "Smoke contract missing required account verification marker "
                    f"{required_marker!r}: {smoke_script}"
                )


def _validate_smoke_condition(condition: object) -> None:
    if not isinstance(condition, str):
        raise AssertionError(
            "dev-demo TCP smoke must use a success() leading && guard"
        )

    expression = condition.strip()
    if not (expression.startswith("${{") and expression.endswith("}}")):
        raise AssertionError(
            "dev-demo TCP smoke must use a success() leading && guard"
        )

    body = expression[3:-2].strip()
    if not body.startswith("success()"):
        raise AssertionError(
            "dev-demo TCP smoke must use a success() leading && guard"
        )

    remainder = body[len("success()") :].strip()
    if not remainder:
        return
    if not remainder.startswith("&&"):
        raise AssertionError(
            "dev-demo TCP smoke must use success() as a mandatory leading && guard"
        )
    continuation = remainder[2:].strip()
    if not continuation or "||" in continuation or re.search(r"!(?!=)", body):
        raise AssertionError(
            "dev-demo TCP smoke must use success() as a mandatory leading && guard"
        )


def validate_workflow(root: Path) -> None:
    workflow = _load_workflow(root)
    jobs = workflow.get("jobs")
    if not isinstance(jobs, dict) or "dev-demo-deploy" not in jobs:
        raise AssertionError("dev-demo workflow missing required 'dev-demo-deploy' job")
    deploy_job = jobs["dev-demo-deploy"]
    if not isinstance(deploy_job, dict) or not isinstance(
        deploy_job.get("steps"), list
    ):
        raise AssertionError("dev-demo-deploy job missing its required steps list")
    bootstrap_step = _find_step(deploy_job, "Create dev-demo smoke account")
    smoke_step = _find_step(deploy_job, "Smoke dev-demo over TCP")
    _validate_smoke_condition(smoke_step.get("if"))
    bootstrap_manifest = bootstrap_step.get("run")
    if not isinstance(bootstrap_manifest, str):
        raise AssertionError("dev-demo bootstrap step run must be a string")
    _validate_bootstrap_manifest(bootstrap_manifest)

    run_sources = collect_workflow_run_sources(workflow)
    summary_writers = discover_summary_writers(run_sources, root)
    if not summary_writers:
        raise AssertionError("dev-demo workflow must define summary-writing steps")
    offending = [
        (source.job_name, source.step_name)
        for source in summary_writers
        if any(
            has_forbidden_summary_reference(region)
            for region in summary_write_regions(source.source)
        )
    ]
    if offending:
        writers = ", ".join(f"{job}/{step}" for job, step in offending)
        raise AssertionError(
            "dev-demo summaries must not reference bootstrap credential material; "
            f"offending summary writers: {writers}"
        )


def main(argv: list[str] | None = None) -> int:
    args = argv if argv is not None else sys.argv[1:]
    if len(args) != 1:
        raise SystemExit(f"usage: {Path(sys.argv[0]).name} ROOT_DIR")
    root = Path(args[0]).resolve()
    validate_workflow(root)
    _validate_smoke_account_contract(root)
    print("dev-demo workflow and summary contract checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
