#!/usr/bin/env python3
"""Validate the dev-demo workflow and its summary-producing shell paths."""

# Contract violations intentionally retain the prior assertion-based failure API.
# ruff: noqa: TRY004

from __future__ import annotations

import ast
import re
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
PLAYER_BOOTSTRAP_REQUEST_CALL = re.compile(
    r"public_account_url\s*\(\s*(?P<quote>['\"])/auth/player-bootstrap"
    r"(?P=quote)\s*\)",
    re.DOTALL,
)
BOOTSTRAP_MANIFEST_REQUIRED_MARKERS = (
    "cleanup_bootstrap_resources() {",
    "trap cleanup_bootstrap_resources EXIT",
    "trap 'exit 130' INT",
    "trap 'exit 143' TERM",
)
BOOTSTRAP_ACCOUNT_TRANSPORT_REQUIRED_MARKERS = (
    "cleanup_bootstrap_port_forward() {",
    "BOOTSTRAP_PORT_FORWARD_PID=$!",
    'kubectl -n "${PREVIEW_NAMESPACE}" port-forward',
    "--address 127.0.0.1",
    "service/spring-cloud-gateway",
    '"${BOOTSTRAP_GATEWAY_PORT}:80"',
    "BOOTSTRAP_MODE=account",
    'BOOTSTRAP_GATEWAY_BASE_URL="http://127.0.0.1:${BOOTSTRAP_GATEWAY_PORT}"',
    'gateway_base_url = os.environ["BOOTSTRAP_GATEWAY_BASE_URL"]',
    'return f"{gateway_base_url}/api/account{path}"',
    'cleanup_bootstrap_port_forward\n          if [[ ! -s "${BOOTSTRAP_ACCOUNT_ID_FILE}" ]]; then',
    '--from-file=account-id="${BOOTSTRAP_ACCOUNT_ID_FILE}"',
    'value: session',
    'kubectl auth can-i create pods/portforward -n "${PREVIEW_NAMESPACE}" >/dev/null',
    'if bootstrap_mode == "account":',
    'email = os.environ["DEMO_SMOKE_EMAIL"]',
    'password = os.environ["DEMO_SMOKE_PASSWORD"]',
    'username = os.environ["DEMO_SMOKE_USERNAME"]',
    "account_file.write(str(account_id))",
)
BOOTSTRAP_CREDENTIAL_VALIDATION = """for credential in DEMO_SMOKE_EMAIL DEMO_SMOKE_PASSWORD DEMO_SMOKE_USERNAME; do
  if [[ -z "${!credential:-}" ]]; then
    echo "::error::${credential} is empty; refusing account bootstrap" >&2
    exit 1
  fi
done"""
BOOTSTRAP_POST_LOG_CLEANUP = """kubectl -n "${PREVIEW_NAMESPACE}" logs dev-demo-bootstrap | tee "${BOOTSTRAP_POD_LOG}"
  kubectl -n "${PREVIEW_NAMESPACE}" delete pod dev-demo-bootstrap --ignore-not-found >/dev/null 2>&1 || true
  kubectl -n "${PREVIEW_NAMESPACE}" delete configmap dev-demo-bootstrap-script --ignore-not-found >/dev/null 2>&1 || true"""
BOOTSTRAP_MANIFEST_HEREDOC_OPENER = (
    "cat <<'EOF' | kubectl -n \"${PREVIEW_NAMESPACE}\" apply -f -\n"
)
NON_CREDENTIAL_SECRET_KEYS = frozenset({"imagepullsecrets"})
BOOTSTRAP_SECRET_CREATE_COMMAND = re.compile(
    r"\bkubectl\b[^;&|]*\bcreate\s+secret(?:\s|$)",
    re.IGNORECASE,
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



def _validate_bootstrap_pod_spec(bootstrap_manifest: str) -> None:
    bootstrap_pod = _extract_bootstrap_pod(bootstrap_manifest)
    pod_spec = bootstrap_pod.get("spec")
    if not isinstance(pod_spec, dict):
        raise AssertionError("dev-demo bootstrap pod must define spec as a mapping")
    if pod_spec.get("automountServiceAccountToken") is not False:
        raise AssertionError(
            "dev-demo bootstrap pod must set automountServiceAccountToken: false"
        )
    containers = pod_spec.get("containers")
    if not isinstance(containers, list) or not containers:
        raise AssertionError(
            "dev-demo bootstrap pod spec.containers must be a non-empty list"
        )
    if not isinstance(containers[0], dict):
        raise AssertionError(
            "dev-demo bootstrap pod spec.containers[0] must be a mapping"
        )
    container = containers[0]
    if container.get("command") != ["python", "/tmp/bootstrap.py"]:
        raise AssertionError(
            "dev-demo bootstrap pod must execute the single in-cluster bootstrap script"
        )
    container_env = container.get("env", [])
    if not isinstance(container_env, list):
        raise AssertionError(
            "dev-demo bootstrap pod spec.containers[0].env must be a list"
        )
    environment = {
        item.get("name"): item.get("value")
        for item in container_env
        if isinstance(item, dict)
    }
    if environment.get("BOOTSTRAP_MODE") != "session":
        raise AssertionError(
            "dev-demo bootstrap pod must run the noncredential session bootstrap"
        )

    for container_group in ("containers", "initContainers", "ephemeralContainers"):
        candidates = pod_spec.get(container_group, [])
        if candidates is None:
            continue
        if not isinstance(candidates, list):
            raise AssertionError(
                f"dev-demo bootstrap pod spec.{container_group} must be a list"
            )
        for index, candidate in enumerate(candidates):
            if not isinstance(candidate, dict):
                raise AssertionError(
                    "dev-demo bootstrap pod spec."
                    f"{container_group}[{index}] must be a mapping"
                )
            if _contains_mapping_key(candidate.get("envFrom"), "secretRef"):
                raise AssertionError(
                    "dev-demo bootstrap pod must not import credential Secret env"
                )
            if _contains_mapping_key(candidate.get("env"), "secretKeyRef"):
                raise AssertionError(
                    "dev-demo bootstrap pod must not import credential Secret env"
                )

    volumes = pod_spec.get("volumes", [])
    if volumes is None:
        volumes = []
    elif not isinstance(volumes, list):
        raise AssertionError("dev-demo bootstrap pod spec.volumes must be a list")
    if any(isinstance(volume, dict) and "secret" in volume for volume in volumes):
        raise AssertionError(
            "dev-demo session pod must not create or mount credential Secret material"
        )

    secret_key = _find_secret_mapping_key(pod_spec)
    if secret_key is not None:
        raise AssertionError(
            "dev-demo bootstrap pod must not contain Secret-bearing pod spec key: "
            f"{secret_key}"
        )


def _contains_mapping_key(value: object, key: str) -> bool:
    if isinstance(value, dict):
        return key in value or any(
            _contains_mapping_key(nested, key) for nested in value.values()
        )
    if isinstance(value, list):
        return any(_contains_mapping_key(nested, key) for nested in value)
    return False


def _find_secret_mapping_key(value: object) -> str | None:
    """Find a credential-bearing Secret key while allowing image pull references."""
    if isinstance(value, dict):
        for key, nested in value.items():
            if (
                isinstance(key, str)
                and "secret" in key.casefold()
                and key.casefold() not in NON_CREDENTIAL_SECRET_KEYS
            ):
                return key
            found = _find_secret_mapping_key(nested)
            if found is not None:
                return found
    elif isinstance(value, list):
        for nested in value:
            found = _find_secret_mapping_key(nested)
            if found is not None:
                return found
    return None


def _player_bootstrap_payload_end(source: str, start: int) -> int | None:
    """Return the end of the first balanced mapping after a request call."""

    if start >= len(source) or source[start] != "{":
        return None

    depth = 0
    quote: str | None = None
    triple_quoted = False
    escaped = False
    index = start
    while index < len(source):
        character = source[index]
        if quote is not None:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif triple_quoted:
                if source.startswith(quote * 3, index):
                    quote = None
                    triple_quoted = False
                    index += 2
            elif character == quote:
                quote = None
            index += 1
            continue
        if character in "'\"":
            if source.startswith(character * 3, index):
                quote = character
                triple_quoted = True
                index += 3
            else:
                quote = character
                index += 1
            continue
        if character == "#":
            newline = source.find("\n", index)
            index = len(source) if newline == -1 else newline + 1
            continue
        if character == "{":
            depth += 1
        elif character == "}":
            depth -= 1
            if depth == 0:
                return index + 1
        index += 1
    return None


def _player_bootstrap_payload(source: str, request: re.Match[str]) -> ast.Dict | None:
    """Parse the mapping passed to one /auth/player-bootstrap request."""

    payload_start = request.end()
    while payload_start < len(source) and source[payload_start].isspace():
        payload_start += 1
    if payload_start >= len(source) or source[payload_start] != ",":
        return None
    payload_start += 1
    while payload_start < len(source) and source[payload_start].isspace():
        payload_start += 1
    payload_end = _player_bootstrap_payload_end(source, payload_start)
    if payload_end is None:
        return None
    try:
        expression = ast.parse(source[payload_start:payload_end], mode="eval")
    except SyntaxError:
        return None
    return expression.body if isinstance(expression.body, ast.Dict) else None


def _validate_player_bootstrap_payload(source: str, request: re.Match[str]) -> bool:
    payload = _player_bootstrap_payload(source, request)
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
    normalized_lines = normalize_nonempty_lines(bootstrap_manifest)
    credential_validation = normalize_nonempty_lines(BOOTSTRAP_CREDENTIAL_VALIDATION)
    if credential_validation not in normalized_lines:
        raise AssertionError(
            "dev-demo account bootstrap must reject empty credentials"
        )
    if "BOOTSTRAP_SECRET_DIR" in bootstrap_manifest or BOOTSTRAP_SECRET_CREATE_COMMAND.search(normalized):
        raise AssertionError("dev-demo session pod must not create or mount credential Secret material")
    post_log_cleanup = normalize_nonempty_lines(BOOTSTRAP_POST_LOG_CLEANUP)
    if post_log_cleanup not in normalized_lines:
        raise AssertionError(
            "dev-demo bootstrap must remove its temporary resources after successful pod logging"
        )

    player_bootstrap_requests = list(
        PLAYER_BOOTSTRAP_REQUEST_CALL.finditer(bootstrap_manifest)
    )
    player_bootstrap_request_count = len(player_bootstrap_requests)
    if player_bootstrap_request_count != 1:
        raise AssertionError(
            "dev-demo bootstrap must contain exactly one /auth/player-bootstrap request "
            f"(found {player_bootstrap_request_count})"
        )
    if not _validate_player_bootstrap_payload(
        bootstrap_manifest, player_bootstrap_requests[0]
    ):
        raise AssertionError(
            "dev-demo bootstrap must send exactly accountIdentifier and secret "
            "to /auth/player-bootstrap"
        )

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
