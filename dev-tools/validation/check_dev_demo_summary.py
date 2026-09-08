#!/usr/bin/env python3
"""Validate the dev-demo workflow and its summary-producing shell paths."""

# Contract violations intentionally retain the prior assertion-based failure API.
# ruff: noqa: TRY004

from __future__ import annotations

import ast
import re
import shlex
import sys
import textwrap
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
    r"<<(?P<strip_tabs>-)?[ \t]*(?P<delimiter>"
    r"'[^'\r\n]*'|"
    r'\"(?:\\.|[^\"\\\r\n])*\"|'
    r"(?:\\[^\r\n]|[^\s;&|<>'\"])+)"
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
BOOTSTRAP_PORT_FORWARD_AUTHORIZATION_CHECK = (
    'kubectl auth can-i create pods --subresource=portforward '
    '-n "${PREVIEW_NAMESPACE}" >/dev/null'
)
BOOTSTRAP_PORT_FORWARD_READINESS_GATE = """if ! wait_for_bootstrap_port_forward; then
  exit 1
fi"""
BOOTSTRAP_ACCOUNT_COMMAND_TOKENS = (
    "if",
    "!",
    "BOOTSTRAP_MODE=account",
    "BOOTSTRAP_GATEWAY_BASE_URL=http://127.0.0.1:${BOOTSTRAP_GATEWAY_PORT}",
    "BOOTSTRAP_ACCOUNT_ID_FILE=${BOOTSTRAP_ACCOUNT_ID_FILE}",
    "python3",
    "${BOOTSTRAP_SCRIPT}",
    ";",
    "then",
)
BOOTSTRAP_SCRIPT_PATH = "/tmp/dev-demo-bootstrap.py"
BOOTSTRAP_SCRIPT_ASSIGNMENT_TOKENS = (f"BOOTSTRAP_SCRIPT={BOOTSTRAP_SCRIPT_PATH}",)
BOOTSTRAP_SCRIPT_DEFINITION_TOKENS = (
    "cat",
    ">",
    "${BOOTSTRAP_SCRIPT}",
    "<<",
    "PY",
)
BOOTSTRAP_SCRIPT_CONFIGMAP_REFERENCE = (
    "--from-file=bootstrap.py=${BOOTSTRAP_SCRIPT}"
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
KUBERNETES_SECRET_KIND = re.compile(
    r"^kind[ \t]*:[ \t]*['\"]?Secret['\"]?[ \t]*(?:#.*)?$",
    re.IGNORECASE | re.MULTILINE,
)
SHELL_CONTROL_OPERATORS = frozenset({";", ";;", ";&", ";;&", "&", "&&", "||"})
SHELL_COMMAND_PREFIXES = frozenset(
    {
        "!",
        "if",
        "then",
        "elif",
        "else",
        "fi",
        "for",
        "while",
        "until",
        "do",
        "done",
        "{",
        "(",
    }
)
SUDO_FLAG_OPTIONS = frozenset(
    {
        "-A",
        "--askpass",
        "-b",
        "--background",
        "-B",
        "--bell",
        "-E",
        "--preserve-env",
        "-e",
        "--edit",
        "-H",
        "--set-home",
        "-i",
        "--login",
        "-K",
        "--remove-timestamp",
        "-k",
        "--reset-timestamp",
        "-l",
        "--list",
        "-n",
        "--non-interactive",
        "-P",
        "--preserve-groups",
        "-S",
        "--stdin",
        "-s",
        "--shell",
        "-V",
        "--version",
        "-v",
        "--validate",
    }
)
SUDO_VALUE_OPTIONS = frozenset(
    {
        "-C",
        "--close-from",
        "-D",
        "--chdir",
        "-g",
        "--group",
        "-h",
        "--host",
        "-p",
        "--prompt",
        "-R",
        "--chroot",
        "-r",
        "--role",
        "-t",
        "--type",
        "-T",
        "--command-timeout",
        "-U",
        "--other-user",
        "-u",
        "--user",
    }
)
TIMEOUT_FLAG_OPTIONS = frozenset(
    {"--foreground", "--preserve-status", "-v", "--verbose"}
)
TIMEOUT_VALUE_OPTIONS = frozenset({"-k", "--kill-after", "-s", "--signal"})
ENV_FLAG_OPTIONS = frozenset(
    {
        "-i",
        "--ignore-environment",
        "-0",
        "--null",
        "--list-signal-handling",
        "-v",
        "--debug",
        "--help",
        "--version",
    }
)
ENV_VALUE_OPTIONS = frozenset({"-u", "--unset", "-C", "--chdir"})
ENV_SPLIT_VALUE_OPTIONS = frozenset({"-S", "--split-string"})
ENV_OPTIONAL_VALUE_OPTIONS = frozenset(
    {"--block-signal", "--default-signal", "--ignore-signal"}
)
# This bootstrap boundary intentionally does not interpret general command
# launchers. The canonical workflow needs none of these, so reject them instead
# of trying to prove what command their options or evaluated input will execute.
UNSAFE_BOOTSTRAP_EXECUTABLES = frozenset(
    {
        "bash",
        "sh",
        "xargs",
        "exec",
        "eval",
        "builtin",
        "nohup",
        "nice",
        "setsid",
        "chroot",
        "stdbuf",
        "ionice",
    }
)
KUBECTL_VALUE_FLAGS = frozenset(
    {
        "-n",
        "--namespace",
        "--context",
        "--cluster",
        "--user",
        "--kubeconfig",
        "--request-timeout",
        "--server",
        "--as",
        "--as-group",
        "--token",
        "--certificate-authority",
        "--client-certificate",
        "--client-key",
        "--tls-server-name",
        "--cache-dir",
        "--dry-run",
        "-f",
        "--filename",
        "-o",
        "--output",
    }
)
KUBECTL_MANIFEST_STDIN_FILENAMES = frozenset(
    {"-", "/dev/stdin", "/dev/fd/0", "/proc/self/fd/0"}
)
YAML_DOCUMENT_SEPARATOR = re.compile(r"^---[ \t]*(?:#.*)?$", re.MULTILINE)


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


def _heredoc_delimiter(opener: re.Match[str]) -> str:
    token = opener["delimiter"]
    if token.startswith("'") and token.endswith("'"):
        return token[1:-1]
    if token.startswith('"') and token.endswith('"'):
        return re.sub(r'\\([$`"\\])', r"\1", token[1:-1])
    return re.sub(r"\\(.)", r"\1", token)


def _shell_tokens(source: str, source_label: str = "shell source") -> list[str]:
    lexer = shlex.shlex(source, posix=True, punctuation_chars=";&|<>")
    lexer.commenters = "#"
    lexer.whitespace_split = True
    try:
        return [token for token in lexer if token not in {"\n", "\r\n"}]
    except ValueError as error:
        raise AssertionError(f"{source_label} contains invalid shell syntax") from error


def _shell_line_state(
    line: str, initial_quote: str | None = None, source_label: str = "shell source"
) -> tuple[bool, str | None]:
    quote = initial_quote
    escaped = False
    word_started = False
    for character in line:
        if escaped:
            escaped = False
            word_started = True
            continue
        if character == "\\" and quote != "'":
            escaped = True
            continue
        if quote is not None:
            if character == quote:
                quote = None
            continue
        if character in "'\"":
            quote = character
            word_started = True
            continue
        if character == "#" and not word_started:
            break
        word_started = not (character.isspace() or character in ";|&<>()")
    if quote is not None or escaped:
        return True, quote
    token_source = line if initial_quote is None else initial_quote + line
    tokens = _shell_tokens(token_source, source_label)
    return bool(tokens) and tokens[-1] in {"|", "||", "&&"}, quote


def _shell_line_continues(line: str, source_label: str = "shell source") -> bool:
    return _shell_line_state(line, source_label=source_label)[0]


def _heredoc_specs(
    command: str, source_label: str = "shell source"
) -> list[tuple[str, bool, bool]]:
    tokens = _shell_tokens(command, source_label)
    feeds_manifest_stdin: dict[int, bool] = {}
    group_start = 0
    group_ranges: list[tuple[int, int]] = []
    for index, token in enumerate(tokens):
        if token in SHELL_CONTROL_OPERATORS:
            group_ranges.append((group_start, index))
            group_start = index + 1
    group_ranges.append((group_start, len(tokens)))
    for start, end in group_ranges:
        pipeline_start = start
        pipeline_ranges: list[tuple[int, int]] = []
        for index in range(start, end):
            if tokens[index] == "|":
                pipeline_ranges.append((pipeline_start, index))
                pipeline_start = index + 1
        pipeline_ranges.append((pipeline_start, end))
        for pipeline_index, (command_start, command_end) in enumerate(pipeline_ranges):
            openers = [
                index
                for index in range(command_start, command_end)
                if tokens[index] == "<<" and index + 1 < command_end
            ]
            if not openers:
                continue
            downstream = pipeline_ranges[pipeline_index:]
            feeds_manifest_stdin[openers[-1]] = any(
                arguments is not None and _kubectl_reads_manifest_stdin(arguments)
                for downstream_start, downstream_end in downstream
                for arguments in (
                    _kubectl_arguments(tokens[downstream_start:downstream_end]),
                )
            )
            for opener in openers[:-1]:
                feeds_manifest_stdin[opener] = False

    result: list[tuple[str, bool, bool]] = []
    for index, token in enumerate(tokens):
        if token != "<<" or index + 1 >= len(tokens):
            continue
        delimiter = tokens[index + 1]
        strip_tabs = delimiter.startswith("-")
        result.append(
            (
                delimiter[1:] if strip_tabs else delimiter,
                strip_tabs,
                feeds_manifest_stdin.get(index, False),
            )
        )
    return result


def _shell_statements(
    source: str, source_label: str = "shell source"
) -> Iterable[tuple[str, list[tuple[str, bool]]]]:
    """Yield shell statements separately from any attached heredoc bodies."""
    lines = source.splitlines()
    index = 0

    def statement_starts() -> Iterable[int]:
        while index < len(lines):
            yield index

    for command_start, command_end in _shell_command_line_ranges(
        source, source_label, starts=statement_starts()
    ):
        if command_start != index:
            raise AssertionError(f"{source_label} shell statement boundary drifted")
        command = "\n".join(lines[index : command_end + 1])
        heredocs = _heredoc_specs(command, source_label)
        if not heredocs:
            index = command_end + 1
            yield command, []
            continue
        index = command_end + 1
        bodies: list[tuple[str, bool]] = []
        for delimiter, strip_tabs, feeds_manifest_stdin in heredocs:
            body_start = index
            while index < len(lines):
                candidate = lines[index].lstrip("\t") if strip_tabs else lines[index]
                if candidate == delimiter:
                    body_lines = lines[body_start:index]
                    if strip_tabs:
                        body_lines = [line.lstrip("\t") for line in body_lines]
                    bodies.append(("\n".join(body_lines), feeds_manifest_stdin))
                    break
                index += 1
            else:
                raise AssertionError(
                    f"{source_label} contains unterminated heredoc {delimiter!r}"
                )
            index += 1
        yield command, bodies


def _heredoc_inputs(
    source: str, source_label: str = "shell source"
) -> Iterable[tuple[str, str]]:
    """Yield each command and the heredoc body that supplies its effective stdin."""

    for command, bodies in _shell_statements(source, source_label):
        for body, feeds_manifest_stdin in bodies:
            if feeds_manifest_stdin:
                yield command, body


def _assert_parsable_shell(source: str, source_label: str = "shell source") -> None:
    """Raise with source context when shell statement parsing fails."""

    for _statement in _shell_statements(source, source_label):
        pass


def _shell_command_groups(tokens: list[str]) -> Iterable[list[str]]:
    start = 0
    for index, token in enumerate(tokens):
        if token in SHELL_CONTROL_OPERATORS:
            if start < index:
                yield tokens[start:index]
            start = index + 1
    if start < len(tokens):
        yield tokens[start:]


def _case_arm_pattern_end(tokens: list[str], start: int = 0) -> int | None:
    """Return a bounded case-pattern terminator, including spaced `)`."""

    separate_close = next(
        (index for index in range(start, len(tokens)) if tokens[index] == ")"),
        None,
    )
    scan_end = separate_close if separate_close is not None else len(tokens)
    expect_pattern = True
    saw_pattern = False
    for index in range(start, scan_end):
        token = tokens[index]
        attached_close = separate_close is None and token.endswith(")")
        value = token[:-1] if attached_close else token
        if expect_pattern:
            if not value or value == "|":
                return None
            saw_pattern = True
            expect_pattern = False
        elif value == "|":
            expect_pattern = True
        else:
            return None
        if attached_close:
            return index if not expect_pattern else None
    if separate_close is not None and saw_pattern and not expect_pattern:
        return separate_close
    return None


def _shell_syntax_command_index(group: list[str]) -> int | None:
    index = 0
    while index < len(group) and (
        group[index] in SHELL_COMMAND_PREFIXES
        or re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*", group[index])
    ):
        index += 1
    return index if index < len(group) else None


def _case_aware_command_groups(
    tokens: list[str], case_depth: int
) -> tuple[list[list[str]], int]:
    """Strip bounded case syntax while retaining each arm's executable body."""

    commands: list[list[str]] = []

    def append_arm_body(body: list[str]) -> None:
        syntax_index = _shell_syntax_command_index(body)
        if syntax_index is not None and body[syntax_index] in {"case", "esac"}:
            raise AssertionError("unsupported nested shell case syntax")
        commands.append(body)

    for group in _shell_command_groups(tokens):
        if not group:
            continue
        syntax_index = _shell_syntax_command_index(group)
        syntax_token = group[syntax_index] if syntax_index is not None else None
        if syntax_token == "case":
            if syntax_index != 0 or case_depth != 0:
                raise AssertionError("unsupported nested shell case syntax")
            if len(group) < 3 or group[2] != "in":
                raise AssertionError("unsupported shell case syntax")
            case_depth += 1
            if len(group) == 3:
                continue
            pattern_end = _case_arm_pattern_end(group, 3)
            if pattern_end is None:
                raise AssertionError("unsupported shell case arm syntax")
            if pattern_end + 1 < len(group):
                append_arm_body(group[pattern_end + 1 :])
            continue
        if syntax_token == "esac":
            if syntax_index != 0 or case_depth == 0 or len(group) != 1:
                raise AssertionError("unsupported shell case terminator syntax")
            case_depth -= 1
            continue
        if case_depth > 0:
            pattern_end = _case_arm_pattern_end(group)
            if pattern_end is not None:
                if pattern_end + 1 < len(group):
                    append_arm_body(group[pattern_end + 1 :])
                continue
        commands.append(group)
    return commands, case_depth


def _pipeline_commands(tokens: list[str]) -> Iterable[list[str]]:
    start = 0
    for index, token in enumerate(tokens):
        if token == "|":
            if start < index:
                yield tokens[start:index]
            start = index + 1
    if start < len(tokens):
        yield tokens[start:]


def _wrapped_executable_index(
    command: list[str],
    start: int,
    wrapper: str,
    flag_options: frozenset[str],
    value_options: frozenset[str],
) -> int:
    index = start
    while index < len(command):
        token = command[index]
        if token == "--":
            return index + 1
        if token == "-" or not token.startswith("-"):
            return index

        option = token.split("=", 1)[0]
        if option in value_options:
            if "=" in token:
                index += 1
                continue
            if len(token) > 2 and not token.startswith("--"):
                index += 1
                continue
            if index + 1 >= len(command):
                raise AssertionError(f"{wrapper} option {token!r} requires a value")
            index += 2
            continue
        if option in flag_options:
            index += 1
            continue
        if (
            not token.startswith("--")
            and len(token) > 2
            and all(f"-{flag}" in flag_options for flag in token[1:])
        ):
            index += 1
            continue
        raise AssertionError(f"unsupported {wrapper} option syntax: {token!r}")
    return index


def _attached_env_value_option(argument: str) -> str | None:
    return next(
        (
            option
            for option in ("-u", "-C", "-S")
            if argument.startswith(option) and len(argument) > len(option)
        ),
        None,
    )


def _env_executable_index(command: list[str], index: int) -> int:
    options_ended = False
    while index < len(command):
        argument = command[index]
        if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*", argument):
            index += 1
            continue
        if options_ended:
            return index
        if argument == "--":
            options_ended = True
            index += 1
            continue
        if argument == "-":
            index += 1
            continue
        if not argument.startswith("-"):
            return index

        option = argument.split("=", 1)[0]
        attached_value_option = _attached_env_value_option(argument)
        if (
            option in ENV_VALUE_OPTIONS
            or option in ENV_SPLIT_VALUE_OPTIONS
            or attached_value_option is not None
        ):
            if "=" in argument:
                value = argument.split("=", 1)[1]
            elif attached_value_option is not None:
                value = argument[len(attached_value_option) :]
            else:
                if index + 1 >= len(command):
                    raise AssertionError(
                        f"env option {argument!r} requires a value"
                    )
                value = command[index + 1]
                index += 1
            if not value:
                raise AssertionError(f"env option {option!r} requires a value")
            if option in ENV_SPLIT_VALUE_OPTIONS or (
                attached_value_option in ENV_SPLIT_VALUE_OPTIONS
            ):
                raise AssertionError(
                    "env split-string command expansion is unsupported"
                )
            index += 1
            continue
        if option in ENV_OPTIONAL_VALUE_OPTIONS:
            index += 1
            continue
        if argument in ENV_FLAG_OPTIONS:
            index += 1
            continue
        if (
            not argument.startswith("--")
            and len(argument) > 2
            and all(f"-{flag}" in ENV_FLAG_OPTIONS for flag in argument[1:])
        ):
            index += 1
            continue
        raise AssertionError(f"unsupported env option syntax: {argument!r}")
    return index


def _effective_executable_index(command: list[str]) -> int | None:
    index = 0
    while index < len(command):
        token = command[index]
        if token in SHELL_COMMAND_PREFIXES or re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*", token):
            index += 1
            continue
        executable = token.rsplit("/", 1)[-1]
        if executable == "env":
            index = _env_executable_index(command, index + 1)
            continue
        if executable == "command":
            index += 1
            while index < len(command) and command[index].startswith("-"):
                index += 1
            continue
        if executable == "sudo":
            index = _wrapped_executable_index(
                command,
                index + 1,
                "sudo",
                SUDO_FLAG_OPTIONS,
                SUDO_VALUE_OPTIONS,
            )
            continue
        if executable == "timeout":
            duration_index = _wrapped_executable_index(
                command,
                index + 1,
                "timeout",
                TIMEOUT_FLAG_OPTIONS,
                TIMEOUT_VALUE_OPTIONS,
            )
            index = duration_index + 1
            continue
        return index
    return None


def _kubectl_arguments(command: list[str]) -> list[str] | None:
    index = _effective_executable_index(command)
    if index is None or command[index].rsplit("/", 1)[-1] != "kubectl":
        return None
    return command[index + 1 :]


def _next_kubectl_positional(
    arguments: list[str],
    start: int = 0,
    *,
    reject_unknown_options: bool = False,
) -> tuple[str, int] | None:
    index = start
    while index < len(arguments):
        token = arguments[index]
        if token == "--":
            index += 1
            return (arguments[index], index) if index < len(arguments) else None
        if token.startswith("-"):
            option = token.split("=", 1)[0]
            attached_short_value = next(
                (
                    flag
                    for flag in KUBECTL_VALUE_FLAGS
                    if flag.startswith("-")
                    and not flag.startswith("--")
                    and token.startswith(flag)
                    and len(token) > len(flag)
                ),
                None,
            )
            if option in KUBECTL_VALUE_FLAGS and "=" in token:
                if not token.split("=", 1)[1]:
                    raise AssertionError(f"kubectl option {option!r} requires a value")
                index += 1
            elif attached_short_value is not None:
                index += 1
            elif token in KUBECTL_VALUE_FLAGS:
                if index + 1 >= len(arguments):
                    raise AssertionError(f"kubectl option {token!r} requires a value")
                index += 2
            else:
                if reject_unknown_options:
                    raise AssertionError(f"unsupported kubectl option syntax: {token!r}")
                index += 1
            continue
        return token, index
    return None


def _kubectl_creates_secret(arguments: list[str]) -> bool:
    verb = _next_kubectl_positional(arguments, reject_unknown_options=True)
    if verb is None or verb[0] != "create":
        return False
    resource = _next_kubectl_positional(
        arguments, verb[1] + 1, reject_unknown_options=True
    )
    return resource is not None and resource[0] == "secret"


def _kubectl_reads_manifest_stdin(arguments: list[str]) -> bool:
    verb = _next_kubectl_positional(arguments, reject_unknown_options=True)
    if verb is None or verb[0] not in {"apply", "create", "replace"}:
        return False
    values = arguments[verb[1] + 1 :]
    for index, token in enumerate(values):
        if token == "--":
            break
        if token in {"-f", "--filename"}:
            if (
                index + 1 < len(values)
                and values[index + 1] in KUBECTL_MANIFEST_STDIN_FILENAMES
            ):
                return True
            continue
        for prefix in ("-f=", "-f", "--filename="):
            if (
                token.startswith(prefix)
                and token[len(prefix) :] in KUBECTL_MANIFEST_STDIN_FILENAMES
            ):
                return True
    return False


def _contains_secret_manifest(value: object) -> bool:
    if not isinstance(value, dict):
        return False
    kind = value.get("kind")
    if kind == "Secret":
        return True
    if not isinstance(kind, str) or not kind.endswith("List"):
        return False
    items = value.get("items")
    return isinstance(items, list) and any(
        _contains_secret_manifest(item) for item in items
    )


def _heredoc_contains_secret_manifest(body: str) -> bool:
    """Recognize Secret documents, including multi-document and templated YAML."""

    try:
        documents = list(yaml.safe_load_all(body))
    except yaml.YAMLError:
        # A shell-expanded value may not be valid YAML until execution. Keep the
        # resource-kind check fail closed without treating ordinary text as a Secret.
        # Dedenting each document lets the root-anchored kind check recognize common
        # heredoc indentation without matching indented examples inside another kind.
        return any(
            KUBERNETES_SECRET_KIND.search(textwrap.dedent(document)) is not None
            for document in YAML_DOCUMENT_SEPARATOR.split(body)
        )
    return any(_contains_secret_manifest(document) for document in documents)


def _bootstrap_creates_secret(bootstrap_manifest: str) -> bool:
    source_label = (
        "workflow job 'dev-demo-deploy' step 'Create dev-demo smoke account'"
    )
    case_depth = 0
    for statement, _ in _shell_statements(bootstrap_manifest, source_label):
        groups, case_depth = _case_aware_command_groups(
            _shell_tokens(statement, source_label), case_depth
        )
        for group in groups:
            for command in _pipeline_commands(group):
                executable_index = _effective_executable_index(command)
                if executable_index is not None and command[
                    executable_index
                ].rsplit("/", 1)[-1] in UNSAFE_BOOTSTRAP_EXECUTABLES:
                    return True
                arguments = _kubectl_arguments(command)
                if arguments is not None and _kubectl_creates_secret(arguments):
                    return True
    if case_depth != 0:
        raise AssertionError("unterminated shell case syntax")
    return any(
        _heredoc_contains_secret_manifest(body)
        for command, body in _heredoc_inputs(bootstrap_manifest, source_label)
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


def _shell_command_line_ranges(
    source: str,
    source_label: str = "shell source",
    *,
    start: int = 0,
    starts: Iterable[int] | None = None,
) -> Iterable[tuple[int, int]]:
    """Yield physical line ranges for quote-aware shell statements."""

    lines = source.splitlines()

    def command_end_for(index: int) -> int:
        command_end = index
        quote: str | None = None
        while command_end + 1 < len(lines):
            continues, quote = _shell_line_state(
                lines[command_end], quote, source_label
            )
            if not continues:
                break
            command_end += 1
        return command_end

    if starts is not None:
        for index in starts:
            yield index, command_end_for(index)
        return

    index = start
    while index < len(lines):
        command_end = command_end_for(index)
        yield index, command_end
        index = command_end + 1


def _summary_write_line_ranges(
    source: str, source_label: str = "shell source"
) -> list[tuple[int, int]]:
    lines = source.splitlines()
    ranges: list[tuple[int, int]] = []
    statement_ranges = list(_shell_command_line_ranges(source, source_label))
    for index, line in enumerate(lines):
        target_match = SUMMARY_TARGET.search(line)
        if target_match is None:
            continue
        statement_start = index
        for candidate_start, candidate_end in statement_ranges:
            if candidate_start <= index <= candidate_end:
                statement_start = candidate_start
                break
        start = _grouped_command_start(lines, index, target_match)
        if start is None:
            start = statement_start
        else:
            start = min(start, statement_start)
        end = index
        heredoc_match = _summary_heredoc(lines, start, index)
        if heredoc_match is not None:
            delimiter = _heredoc_delimiter(heredoc_match)
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


def summary_write_regions(
    source: str, source_label: str = "shell source"
) -> list[str]:
    """Return shell regions that write to GITHUB_STEP_SUMMARY."""

    lines = source.splitlines()
    return [
        "\n".join(lines[start : end + 1])
        for start, end in _summary_write_line_ranges(source, source_label)
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


def _workflow_run_source_label(source: WorkflowRunSource) -> str:
    if source.resolved_helper_path is not None:
        return f"summary helper {source.resolved_helper_path}"
    return f"workflow job {source.job_name!r} step {source.step_name!r}"


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
        source_label = _workflow_run_source_label(current)
        _assert_parsable_shell(current.source, source_label)
        direct_ranges = _summary_write_line_ranges(current.source, source_label)
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


def _shell_compound_nesting_delta(statement: str, tokens: list[str]) -> int:
    """Track bounded compound-shell nesting without interpreting command data."""

    grouping = shell_group_tokens(statement)
    delta = grouping.count("{") - grouping.count("}")
    normalized = normalize_script(statement)
    if normalized == "(":
        delta += 1
    elif normalized == ")":
        delta -= 1
    for group in _shell_command_groups(tokens):
        if group[0] in {"if", "for", "while", "until", "case"}:
            delta += 1
        elif group[0] in {"fi", "done", "esac"}:
            delta -= 1
    return delta


def _validate_bootstrap_readiness_gate(bootstrap_manifest: str) -> None:
    """Require the fail-closed readiness gate in executable top-level flow."""

    source_label = (
        "workflow job 'dev-demo-deploy' step 'Create dev-demo smoke account'"
    )
    records: list[tuple[list[str], int, list[tuple[str, bool]]]] = []
    nesting = 0
    for statement, bodies in _shell_statements(bootstrap_manifest, source_label):
        tokens = _shell_tokens(statement, source_label)
        if not tokens:
            continue
        records.append((tokens, nesting, bodies))
        nesting += _shell_compound_nesting_delta(statement, tokens)
        if nesting < 0:
            raise AssertionError("unsupported shell compound nesting")

    assignment_indexes = [
        index
        for index, (tokens, _, _) in enumerate(records)
        if any(token.startswith("BOOTSTRAP_SCRIPT=") for token in tokens)
    ]
    if len(assignment_indexes) != 1 or records[assignment_indexes[0]][0] != list(
        BOOTSTRAP_SCRIPT_ASSIGNMENT_TOKENS
    ):
        raise AssertionError(
            "dev-demo player bootstrap must assign the canonical bootstrap script "
            "path exactly once"
        )
    assignment_index = assignment_indexes[0]

    definition_indexes = [
        index
        for index, (tokens, _, bodies) in enumerate(records)
        if tokens == list(BOOTSTRAP_SCRIPT_DEFINITION_TOKENS)
        and len(bodies) == 1
        and bool(bodies[0][0].strip())
    ]
    if len(definition_indexes) != 1:
        raise AssertionError(
            "dev-demo player bootstrap must define the bootstrap script exactly once"
        )
    definition_index = definition_indexes[0]
    configmap_reference_indexes = [
        index
        for index, (tokens, _, _) in enumerate(records)
        if BOOTSTRAP_SCRIPT_CONFIGMAP_REFERENCE in tokens
    ]
    if len(configmap_reference_indexes) != 1:
        raise AssertionError(
            "dev-demo player bootstrap must reference the canonical bootstrap "
            "script ConfigMap source exactly once"
        )
    configmap_reference_index = configmap_reference_indexes[0]

    def references_account_bootstrap(
        record_index: int, tokens: list[str]
    ) -> bool:
        if "BOOTSTRAP_MODE=account" in tokens:
            return True
        for index, token in enumerate(tokens):
            if token == BOOTSTRAP_SCRIPT_PATH and record_index != assignment_index:
                return True
            if not re.search(
                r"\$(?:BOOTSTRAP_SCRIPT\b|\{BOOTSTRAP_SCRIPT(?:[^}]*)\})", token
            ):
                continue
            if record_index == definition_index:
                continue
            if (
                record_index == configmap_reference_index
                and token == BOOTSTRAP_SCRIPT_CONFIGMAP_REFERENCE
            ):
                continue
            return True
        return False

    account_candidates = [
        tokens
        for record_index, (tokens, _, _) in enumerate(records)
        if references_account_bootstrap(record_index, tokens)
    ]
    if account_candidates != [list(BOOTSTRAP_ACCOUNT_COMMAND_TOKENS)]:
        raise AssertionError(
            "dev-demo player bootstrap must execute exactly one canonical account "
            "bootstrap command"
        )

    expected_sequence = (
        (["BOOTSTRAP_PORT_FORWARD_PID=$!"], 0),
        (["if", "!", "wait_for_bootstrap_port_forward", ";", "then"], 0),
        (["exit", "1"], 1),
        (["fi"], 1),
        (list(BOOTSTRAP_ACCOUNT_COMMAND_TOKENS), 0),
    )
    for start in range(len(records) - len(expected_sequence) + 1):
        window = records[start : start + len(expected_sequence)]
        if all(
            tokens == expected and depth == expected_depth
            for (tokens, depth, _), (expected, expected_depth) in zip(
                window, expected_sequence, strict=True
            )
        ) and (
            assignment_index + 1 == definition_index
            and definition_index < start
            and start + len(expected_sequence) <= configmap_reference_index
        ):
            return
    raise AssertionError(
        "dev-demo player bootstrap must execute the exact fail-closed "
        "port-forward readiness gate immediately before account bootstrap"
    )


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
    _validate_bootstrap_readiness_gate(bootstrap_manifest)
    authorization_lines = [
        line.strip()
        for line in bootstrap_manifest.splitlines()
        if re.search(r"\bkubectl\s+auth\s+can-i\b", line)
    ]
    expected_authorization_line = (
        f"if ! {BOOTSTRAP_PORT_FORWARD_AUTHORIZATION_CHECK}; then"
    )
    if authorization_lines != [expected_authorization_line]:
        raise AssertionError(
            "dev-demo port-forward authorization must use exactly: "
            f"{BOOTSTRAP_PORT_FORWARD_AUTHORIZATION_CHECK}"
        )
    normalized_lines = normalize_nonempty_lines(bootstrap_manifest)
    credential_validation = normalize_nonempty_lines(BOOTSTRAP_CREDENTIAL_VALIDATION)
    if credential_validation not in normalized_lines:
        raise AssertionError(
            "dev-demo account bootstrap must reject empty credentials"
        )
    if "BOOTSTRAP_SECRET_DIR" in bootstrap_manifest or _bootstrap_creates_secret(
        bootstrap_manifest
    ):
        raise AssertionError(
            "dev-demo session pod must not create or mount credential Secret material "
            "or use disallowed command-indirection launchers"
        )
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
                summary_write_regions(
                    source.source, _workflow_run_source_label(source)
                )
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
