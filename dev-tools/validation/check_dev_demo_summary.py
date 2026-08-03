#!/usr/bin/env python3
"""Validate the dev-demo workflow and its summary-producing shell paths."""

# Contract violations intentionally retain the prior assertion-based failure API.
# ruff: noqa: TRY004

from __future__ import annotations

import re
import shlex
import sys
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

import yaml

WORKFLOW_RELATIVE_PATH = Path(".github/workflows/dev-demo.yml")
ALLOWED_WORKSPACE_ROOT_VARIABLES = frozenset(
    {"FIREMUD_REPO_ROOT", "GITHUB_WORKSPACE", "ROOT_DIR"}
)

SUMMARY_HELPER_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_./$-])(?:bash[ \t]+)?"
    r"(?P<invocation>(?:"
    r"dev-tools/[A-Za-z0-9_./-]+[.]sh|"
    r"[.]/dev-tools/[A-Za-z0-9_./-]+[.]sh|"
    r"/[^\s;&|\"']+/dev-tools/[A-Za-z0-9_./-]+[.]sh|"
    r"(?P<variable>\$(?:\{[A-Za-z_][A-Za-z0-9_]*\}|[A-Za-z_][A-Za-z0-9_]*))"
    r"/dev-tools/[A-Za-z0-9_./-]+[.]sh"
    r"))(?![A-Za-z0-9_./-])"
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

SUMMARY_TARGET = re.compile(
    r"(?P<operator>>{1,2}|tee(?:[ \t]+(?:-a|--append))?)[ \t]*"
    r"['\"]?\$\{?GITHUB_STEP_SUMMARY\}?['\"]?"
)
HEREDOC_OPEN = re.compile(
    r"<<(?P<strip_tabs>-)?[ \t]*(?P<quote>['\"]?)"
    r"(?P<delimiter>[A-Za-z_][A-Za-z0-9_]*)"
    r"(?P=quote)"
)
SHELL_IF_START = re.compile(r"^if\b.*;[ \t]*then$")

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
    summary_reachable: bool = False
    resolved_helper_path: Path | None = None


def normalize_script(script: str) -> str:
    return " ".join(script.split())


def normalize_nonempty_lines(script: str) -> str:
    return "\n".join(
        " ".join(line.split()) for line in script.splitlines() if line.strip()
    )


def shell_group_tokens(line: str) -> list[str]:
    """Return shell grouping tokens while ignoring quoted/comment text."""

    tokens: list[str] = []
    quote: str | None = None
    escaped = False
    word_started = False
    index = 0
    while index < len(line):
        character = line[index]
        if escaped:
            escaped = False
            word_started = True
            index += 1
            continue
        if character == "\\" and quote != "'":
            escaped = True
            word_started = True
            index += 1
            continue
        if quote is not None:
            if character == quote:
                quote = None
            index += 1
            continue
        if character in "'\"":
            quote = character
            word_started = True
            index += 1
            continue
        if character == "$" and index + 1 < len(line) and line[index + 1] in "{(":
            opener = line[index + 1]
            closer = "}" if opener == "{" else ")"
            depth = 1
            word_started = True
            index += 2
            while index < len(line) and depth:
                if line[index] == opener:
                    depth += 1
                elif line[index] == closer:
                    depth -= 1
                index += 1
            continue
        if character == "#" and not word_started:
            break
        if character in "{}()":
            tokens.append(character)
        if character.isspace() or character in ";|&<>()":
            word_started = False
        else:
            word_started = True
        index += 1
    return tokens


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


def _grouped_command_start(
    lines: list[str], index: int, target_match: re.Match[str]
) -> int | None:
    attached_tokens = shell_group_tokens(lines[index][: target_match.start()])
    if not attached_tokens or attached_tokens[-1] not in "})":
        return None
    depth = 0
    saw_closing_group = False
    for candidate in range(index, -1, -1):
        candidate_text = (
            lines[candidate][: target_match.start()]
            if candidate == index
            else lines[candidate]
        )
        for token in reversed(shell_group_tokens(candidate_text)):
            if token in "})":
                depth += 1
                saw_closing_group = True
            elif saw_closing_group and token in "{(":
                depth -= 1
                if depth == 0:
                    return candidate
    return None


def _summary_heredoc(
    lines: list[str], start: int, index: int
) -> re.Match[str] | None:
    for opener_index in range(start, index + 1):
        if opener_index < index and any(
            not lines[candidate].rstrip().endswith("\\")
            for candidate in range(opener_index, index)
        ):
            continue
        opener_line = lines[opener_index]
        match = HEREDOC_OPEN.search(opener_line)
        if match is None:
            continue
        suffix = opener_line[match.end() :]
        if re.search(r"[;&|]", suffix) and not re.fullmatch(
            r"\s*\|\s*tee(?:\s+(?:-a|--append))?\s+"
            r"['\"]?\$\{?GITHUB_STEP_SUMMARY\}?['\"]?\s*",
            suffix,
        ):
            continue
        return match
    return None


def _summary_write_line_ranges(source: str) -> list[tuple[int, int]]:
    lines = source.splitlines()
    ranges: list[tuple[int, int]] = []
    for index, line in enumerate(lines):
        target_match = SUMMARY_TARGET.search(line)
        if target_match is None:
            continue
        start = _grouped_command_start(lines, index, target_match)
        if start is None:
            start = index
            while start > 0 and lines[start - 1].rstrip().endswith("\\"):
                start -= 1
        end = index
        heredoc_match = _summary_heredoc(lines, start, index)
        if heredoc_match is not None:
            delimiter = heredoc_match.group("delimiter")
            strip_tabs = heredoc_match.group("strip_tabs") is not None
            for candidate in range(index + 1, len(lines)):
                candidate_line = lines[candidate]
                if strip_tabs:
                    candidate_line = candidate_line.lstrip("\t")
                if candidate_line == delimiter:
                    end = candidate
                    break
            else:
                end = len(lines) - 1
        ranges.append((start, end))
    return ranges


def summary_write_regions(source: str) -> list[str]:
    """Return shell regions that write to GITHUB_STEP_SUMMARY."""

    lines = source.splitlines()
    return [
        "\n".join(lines[start : end + 1])
        for start, end in _summary_write_line_ranges(source)
    ]


def has_forbidden_summary_reference(text: str) -> bool:
    normalized_text = re.sub(r"[ \t]+", " ", text)
    return FORBIDDEN_SUMMARY_REFERENCE.search(normalized_text) is not None


def _variable_name(variable: str) -> str:
    return variable[2:-1] if variable.startswith("${") else variable[1:]


def normalize_summary_helper_path(invocation: str, root_dir: Path) -> Path:
    """Resolve a helper path, rejecting unknown variable-rooted forms."""

    root_dir = root_dir.resolve()
    if invocation.startswith("$"):
        variable, separator, suffix = invocation.partition("/dev-tools/")
        if (
            not separator
            or _variable_name(variable) not in ALLOWED_WORKSPACE_ROOT_VARIABLES
        ):
            allowed = ", ".join(sorted(ALLOWED_WORKSPACE_ROOT_VARIABLES))
            raise ValueError(
                f"unsupported variable-prefixed summary helper path: {invocation}; "
                f"allowed workspace variables: {allowed}"
            )
        return (root_dir / "dev-tools" / suffix).resolve()
    if invocation.startswith("/"):
        return Path(invocation).resolve()
    return (root_dir / invocation.removeprefix("./")).resolve()


def _helper_matches(source: str) -> Iterable[re.Match[str]]:
    return SUMMARY_HELPER_PATTERN.finditer(source)


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
    """Find direct and transitively redirected summary-producing sources."""

    root_dir = root_dir.resolve()
    summary_writers: list[WorkflowRunSource] = []
    pending = list(workflow_run_sources)
    seen_sources: dict[tuple[str, str, str], bool] = {}
    seen_helpers: dict[tuple[str, Path], bool] = {}
    summary_writer_indexes: dict[tuple[str, str, str] | tuple[str, Path], int] = {}
    while pending:
        current = pending.pop()
        if current.resolved_helper_path is None:
            traversal_key: tuple[str, str, str] | tuple[str, Path] = (
                current.job_name,
                current.step_name,
                current.source,
            )
            previous_reachability = seen_sources.get(traversal_key)
        else:
            traversal_key = (current.job_name, current.resolved_helper_path)
            previous_reachability = seen_helpers.get(traversal_key)
        if previous_reachability is True or (
            previous_reachability is False and not current.summary_reachable
        ):
            continue
        if current.resolved_helper_path is None:
            seen_sources[traversal_key] = current.summary_reachable
        else:
            seen_helpers[traversal_key] = current.summary_reachable
        direct_ranges = _summary_write_line_ranges(current.source)
        if direct_ranges or current.summary_reachable:
            existing_index = summary_writer_indexes.get(traversal_key)
            if existing_index is None:
                summary_writer_indexes[traversal_key] = len(summary_writers)
                summary_writers.append(current)
            else:
                summary_writers[existing_index] = current

        for match in _helper_matches(current.source):
            helper_path = normalize_summary_helper_path(
                match.group("invocation"), root_dir
            )
            try:
                relative_helper = helper_path.relative_to(root_dir)
            except ValueError as exc:
                raise ValueError(
                    f"summary helper path escapes repository root: {match.group('invocation')}"
                ) from exc
            if not helper_path.is_file():
                raise AssertionError(
                    "summary helper file is missing: "
                    f"{helper_path} (referenced as {match.group('invocation')})"
                )
            line_index = current.source.count("\n", 0, match.start())
            redirected = current.summary_reachable or any(
                start <= line_index <= end for start, end in direct_ranges
            )
            helper_source = helper_path.read_text(encoding="utf-8")
            pending.append(
                WorkflowRunSource(
                    current.job_name,
                    f"{current.step_name}:{relative_helper.as_posix()}",
                    helper_source,
                    summary_reachable=redirected,
                    resolved_helper_path=helper_path,
                )
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


def _validate_bootstrap_manifest(bootstrap_manifest: str) -> None:
    normalized = normalize_script(bootstrap_manifest)
    for expected in BOOTSTRAP_MANIFEST_REQUIRED_MARKERS:
        if normalize_script(expected) not in normalized:
            raise AssertionError(
                f"dev-demo bootstrap step contract missing: {expected}"
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
    smoke_condition = smoke_step.get("if")
    if not isinstance(smoke_condition, str) or "!cancelled()" not in smoke_condition:
        raise AssertionError(
            "dev-demo TCP smoke must still run after a non-cancellation bootstrap failure"
        )
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
            for region in (
                summary_write_regions(source.source)
                + ([source.source] if source.summary_reachable else [])
            )
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
