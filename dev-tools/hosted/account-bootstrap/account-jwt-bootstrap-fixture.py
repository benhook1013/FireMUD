#!/usr/bin/env python3
"""Create and safely dispose a trusted-runner-only Account JWT fixture cluster."""

from __future__ import annotations

import hashlib
import json
import os
import re
import selectors
import shutil
import signal
import stat
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

EXPECTED_REPOSITORY = "benhook1013/FireMUD"
EXPECTED_REF = "refs/heads/develop"
ALLOWED_EVENTS = {"push", "workflow_dispatch"}
NODE_IMAGE_REPOSITORY = "kindest/node"
MAX_STREAM_BYTES = 64 * 1024
MAX_COMMAND_STDOUT_BYTES = 1024 * 1024
PROCESS_TERMINATION_GRACE_SECONDS = 2
MAX_KIND_BINARY_BYTES = 128 * 1024 * 1024
DEFAULT_COMMAND_TIMEOUT_SECONDS = 45
CREATE_TIMEOUT_SECONDS = 300
TOTAL_TIMEOUT_SECONDS = 900
KIND_WAIT_ARGUMENT = "180s"
UID_PATTERN = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
HEX_DIGEST_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")


class FixtureDenied(Exception):
    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


@dataclass
class CommandResult:
    returncode: int
    stdout: bytes
    stderr: bytes
    truncated: bool


@dataclass(frozen=True)
class BinaryIdentity:
    path: Path
    device: int
    inode: int
    size: int
    mtime_ns: int
    ctime_ns: int


@dataclass
class RunnerIdentity:
    repository: str
    ref: str
    sha: str
    run_id: str
    attempt: str
    workspace: Path
    runner_temp: Path
    home: Path

    @property
    def cluster_name(self) -> str:
        value = f"account-jwt-bootstrap-{self.run_id}-{self.attempt}-{self.sha[:8]}"
        if len(value) > 63 or not re.fullmatch(r"[a-z0-9]([-a-z0-9]*[a-z0-9])?", value):
            raise FixtureDenied("cluster_claim_invalid")
        return value

    @property
    def run_directory(self) -> Path:
        return self.runner_temp / f"account-jwt-bootstrap-{self.run_id}-{self.attempt}-{self.sha[:12]}"


class CommandRunner:
    def __init__(self) -> None:
        self.deadline = time.monotonic() + TOTAL_TIMEOUT_SECONDS
        self.resources_may_exist = False
        self.verified_binaries: dict[str, BinaryIdentity] = {}

    @staticmethod
    def file_identity_tuple(value: os.stat_result) -> tuple[int, int, int, int, int]:
        return value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns

    @staticmethod
    def identity_tuple(value: BinaryIdentity) -> tuple[int, int, int, int, int]:
        return value.device, value.inode, value.size, value.mtime_ns, value.ctime_ns

    @staticmethod
    def binary_identity(path: Path) -> BinaryIdentity:
        try:
            resolved = path.resolve(strict=True)
            value = resolved.stat()
        except OSError:
            raise FixtureDenied("kind_binary_identity_unavailable") from None
        if not stat.S_ISREG(value.st_mode) or not value.st_mode & 0o111:
            raise FixtureDenied("kind_binary_identity_invalid")
        return BinaryIdentity(resolved, value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns)

    def verify_kind_binary(self, path: str, expected_sha256: str) -> str:
        try:
            resolved = Path(path).resolve(strict=True)
            flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
            descriptor = os.open(resolved, flags)
        except OSError:
            raise FixtureDenied("kind_binary_identity_unavailable") from None
        try:
            before = os.fstat(descriptor)
            if not stat.S_ISREG(before.st_mode) or not before.st_mode & 0o111:
                raise FixtureDenied("kind_binary_identity_invalid")
            if before.st_size <= 0 or before.st_size > MAX_KIND_BINARY_BYTES:
                raise FixtureDenied("kind_binary_size_invalid")
            digest = hashlib.sha256()
            total = 0
            while True:
                chunk = os.read(descriptor, min(1024 * 1024, MAX_KIND_BINARY_BYTES + 1 - total))
                if not chunk:
                    break
                total += len(chunk)
                if total > MAX_KIND_BINARY_BYTES:
                    raise FixtureDenied("kind_binary_size_invalid")
                digest.update(chunk)
            after = os.fstat(descriptor)
            if self.file_identity_tuple(before) != self.file_identity_tuple(after) or total != before.st_size:
                raise FixtureDenied("kind_binary_identity_changed")
            if digest.hexdigest() != expected_sha256:
                raise FixtureDenied("kind_binary_checksum_mismatch")
            identity = self.binary_identity(resolved)
            if self.file_identity_tuple(before) != self.identity_tuple(identity):
                raise FixtureDenied("kind_binary_identity_changed")
            self.verified_binaries[str(resolved)] = identity
            return str(resolved)
        finally:
            os.close(descriptor)

    def verify_executable_unchanged(self, path: str) -> None:
        expected = self.verified_binaries.get(path)
        if expected is None:
            return
        actual = self.binary_identity(Path(path))
        if actual != expected:
            raise FixtureDenied("kind_binary_identity_changed")

    @staticmethod
    def terminate_process_group(process: subprocess.Popen[bytes]) -> None:
        process_group = process.pid
        try:
            os.killpg(process_group, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=PROCESS_TERMINATION_GRACE_SECONDS)
        except subprocess.TimeoutExpired:
            pass
        try:
            os.killpg(process_group, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=PROCESS_TERMINATION_GRACE_SECONDS)
        except subprocess.TimeoutExpired:
            pass

    def run(
        self,
        argv: list[str],
        env: dict[str, str],
        failure: str,
        timeout_seconds: int = DEFAULT_COMMAND_TIMEOUT_SECONDS,
        stdout_limit: int = MAX_STREAM_BYTES,
    ) -> CommandResult:
        if (
            not isinstance(stdout_limit, int)
            or isinstance(stdout_limit, bool)
            or stdout_limit < 1
            or stdout_limit > MAX_COMMAND_STDOUT_BYTES
        ):
            raise FixtureDenied("command_stdout_limit_invalid")
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            reason = (
                "lifecycle_deadline_exceeded_resources_retained"
                if self.resources_may_exist
                else "lifecycle_deadline_exceeded"
            )
            raise FixtureDenied(reason)
        timeout_seconds = min(timeout_seconds, max(1, int(remaining)))
        self.verify_executable_unchanged(argv[0])
        try:
            process = subprocess.Popen(
                argv,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=env,
                start_new_session=True,
            )
        except (OSError, ValueError):
            raise FixtureDenied(failure) from None

        assert process.stdout is not None
        assert process.stderr is not None
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ, "stdout")
        selector.register(process.stderr, selectors.EVENT_READ, "stderr")
        buffers = {"stdout": bytearray(), "stderr": bytearray()}
        truncated = False
        termination_attempted = False
        deadline = time.monotonic() + timeout_seconds
        try:
            while selector.get_map():
                left = deadline - time.monotonic()
                if left <= 0:
                    raise TimeoutError
                events = selector.select(min(left, 0.25))
                for key, _ in events:
                    chunk = os.read(key.fileobj.fileno(), 8192)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        key.fileobj.close()
                        continue
                    target = buffers[key.data]
                    stream_limit = stdout_limit if key.data == "stdout" else MAX_STREAM_BYTES
                    capacity = stream_limit - len(target)
                    if capacity > 0:
                        target.extend(chunk[:capacity])
                    if len(chunk) > capacity:
                        truncated = True
            left = deadline - time.monotonic()
            if left <= 0:
                raise TimeoutError
            returncode = process.wait(timeout=left)
        except (TimeoutError, subprocess.TimeoutExpired):
            termination_attempted = True
            self.terminate_process_group(process)
            raise FixtureDenied(failure if self.resources_may_exist else "command_deadline_exceeded") from None
        finally:
            selector.close()
            process.stdout.close()
            process.stderr.close()
            if not termination_attempted and process.poll() is None:
                self.terminate_process_group(process)
        if returncode != 0:
            raise FixtureDenied(failure)
        return CommandResult(returncode, bytes(buffers["stdout"]), bytes(buffers["stderr"]), truncated)


def parse_authority(path: Path) -> dict[str, str]:
    try:
        raw_lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        raise FixtureDenied("workflow_tool_authority_unavailable") from None
    values: dict[str, str] = {}
    for line in raw_lines:
        if not line or line.startswith("#"):
            continue
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*=[A-Za-z0-9._:-]+", line):
            raise FixtureDenied("workflow_tool_authority_invalid")
        key, value = line.split("=", 1)
        if key in values:
            raise FixtureDenied("workflow_tool_authority_invalid")
        values[key] = value
    required = {
        "KUBECTL_VERSION",
        "KIND_VERSION",
        "KIND_LINUX_AMD64_CHECKSUM_VERSION",
        "KIND_LINUX_AMD64_SHA256",
        "KIND_NODE_IMAGE_VERSION",
        "KIND_NODE_IMAGE_DIGEST",
    }
    if not required.issubset(values):
        raise FixtureDenied("workflow_tool_authority_incomplete")
    if not re.fullmatch(r"\d+\.\d+\.\d+", values["KUBECTL_VERSION"]):
        raise FixtureDenied("workflow_tool_authority_invalid")
    if not re.fullmatch(r"\d+\.\d+\.\d+", values["KIND_VERSION"]):
        raise FixtureDenied("workflow_tool_authority_invalid")
    if values["KIND_LINUX_AMD64_CHECKSUM_VERSION"] != values["KIND_VERSION"]:
        raise FixtureDenied("workflow_tool_authority_invalid")
    if not re.fullmatch(r"[0-9a-f]{64}", values["KIND_LINUX_AMD64_SHA256"]):
        raise FixtureDenied("workflow_tool_authority_invalid")
    node_version = values["KIND_NODE_IMAGE_VERSION"]
    node_digest = values["KIND_NODE_IMAGE_DIGEST"]
    if not re.fullmatch(r"v\d+\.\d+\.\d+", node_version) or not HEX_DIGEST_PATTERN.fullmatch(node_digest):
        raise FixtureDenied("workflow_tool_authority_invalid")
    if ".".join(values["KUBECTL_VERSION"].split(".")[:2]) != ".".join(node_version[1:].split(".")[:2]):
        raise FixtureDenied("workflow_tool_kubernetes_minor_mismatch")
    values["KIND_NODE_IMAGE"] = f"{NODE_IMAGE_REPOSITORY}:{node_version}@{node_digest}"
    return values


def required_env(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise FixtureDenied("runner_identity_incomplete")
    return value


def runner_identity(command_runner: CommandRunner) -> RunnerIdentity:
    if os.environ.get("GITHUB_ACTIONS", "").lower() != "true":
        raise FixtureDenied("trusted_github_runner_required")
    if os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted":
        raise FixtureDenied("trusted_github_runner_required")
    if os.environ.get("RUNNER_OS") != "Linux" or os.environ.get("RUNNER_ARCH") != "X64":
        raise FixtureDenied("trusted_linux_amd64_runner_required")
    if os.environ.get("GITHUB_REPOSITORY") != EXPECTED_REPOSITORY:
        raise FixtureDenied("repository_identity_mismatch")
    if os.environ.get("GITHUB_REF") != EXPECTED_REF or os.environ.get("GITHUB_EVENT_NAME") not in ALLOWED_EVENTS:
        raise FixtureDenied("trusted_ref_required")
    if os.environ.get("GITHUB_HEAD_REF"):
        raise FixtureDenied("pull_request_run_refused")
    run_id = required_env("GITHUB_RUN_ID")
    attempt = required_env("GITHUB_RUN_ATTEMPT")
    if not run_id.isascii() or not run_id.isdigit() or int(run_id) < 1:
        raise FixtureDenied("runner_identity_invalid")
    if not attempt.isascii() or not attempt.isdigit() or int(attempt) < 1:
        raise FixtureDenied("runner_identity_invalid")
    sha = required_env("GITHUB_SHA")
    if not SHA_PATTERN.fullmatch(sha):
        raise FixtureDenied("runner_identity_invalid")
    if not required_env("GITHUB_JOB").strip():
        raise FixtureDenied("runner_identity_incomplete")

    workspace = Path(required_env("GITHUB_WORKSPACE"))
    runner_temp = Path(required_env("RUNNER_TEMP"))
    home = Path(required_env("HOME"))
    try:
        workspace = workspace.resolve(strict=True)
        runner_temp = runner_temp.resolve(strict=True)
        home = home.resolve(strict=True)
    except OSError:
        raise FixtureDenied("runner_paths_unavailable") from None
    if not workspace.is_dir() or not runner_temp.is_dir() or not home.is_dir():
        raise FixtureDenied("runner_paths_unavailable")

    env = minimal_env(home)
    top = command_runner.run(
        [require_tool("git"), "-C", str(workspace), "rev-parse", "--show-toplevel"], env, "source_checkout_mismatch"
    )
    head = command_runner.run(
        [require_tool("git"), "-C", str(workspace), "rev-parse", "HEAD"], env, "source_checkout_mismatch"
    )
    if top.truncated or head.truncated:
        raise FixtureDenied("source_checkout_mismatch")
    if decode_line(top.stdout) != str(workspace) or decode_line(head.stdout) != sha:
        raise FixtureDenied("source_checkout_mismatch")
    return RunnerIdentity(
        repository=EXPECTED_REPOSITORY,
        ref=EXPECTED_REF,
        sha=sha,
        run_id=run_id,
        attempt=attempt,
        workspace=workspace,
        runner_temp=runner_temp,
        home=home,
    )


def minimal_env(home: Path, kubeconfig: Path | None = None, docker_config: Path | None = None) -> dict[str, str]:
    path = os.environ.get("PATH", "")
    if not path:
        raise FixtureDenied("runner_path_unavailable")
    env = {"PATH": path, "HOME": str(home), "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8"}
    if kubeconfig is not None:
        env["KUBECONFIG"] = str(kubeconfig)
    if docker_config is not None:
        env["DOCKER_CONFIG"] = str(docker_config)
    return env


def require_tool(name: str) -> str:
    result = shutil.which(name, path=os.environ.get("PATH", ""))
    if result is None:
        raise FixtureDenied("required_runner_tool_unavailable")
    return result


def kind_tool(command_runner: CommandRunner) -> str:
    registered = list(command_runner.verified_binaries)
    if len(registered) != 1:
        raise FixtureDenied("kind_binary_not_verified")
    path = require_tool("kind")
    try:
        resolved = str(Path(path).resolve(strict=True))
    except OSError:
        raise FixtureDenied("kind_binary_identity_unavailable") from None
    if resolved != registered[0]:
        raise FixtureDenied("kind_binary_identity_changed")
    command_runner.verify_executable_unchanged(registered[0])
    return registered[0]


def decode_line(value: bytes) -> str:
    try:
        return value.decode("utf-8").strip()
    except UnicodeDecodeError:
        raise FixtureDenied("tool_output_invalid") from None


def output_text(result: CommandResult) -> str:
    if result.truncated:
        raise FixtureDenied("tool_output_exceeded_limit")
    try:
        return result.stdout.decode("utf-8").strip()
    except UnicodeDecodeError:
        raise FixtureDenied("tool_output_invalid") from None


def output_json(result: CommandResult) -> Any:
    try:
        return json.loads(output_text(result))
    except (json.JSONDecodeError, FixtureDenied):
        raise FixtureDenied("tool_output_invalid") from None


def lines(result: CommandResult) -> list[str]:
    text = output_text(result)
    return [line.strip() for line in text.splitlines() if line.strip()]


def current_tool_versions(command_runner: CommandRunner, env: dict[str, str], pins: dict[str, str]) -> None:
    kind = command_runner.verify_kind_binary(require_tool("kind"), pins["KIND_LINUX_AMD64_SHA256"])
    kubectl = require_tool("kubectl")
    kind_result = command_runner.run([kind, "version"], env, "kind_version_unverified")
    kind_output = output_text(kind_result)
    match = re.search(r"\bkind v(\d+\.\d+\.\d+)\b", kind_output)
    if match is None or match.group(1) != pins["KIND_VERSION"]:
        raise FixtureDenied("kind_version_mismatch")
    kubectl_result = command_runner.run(
        [kubectl, "version", "--client", "-o", "json"], env, "kubectl_version_unverified"
    )
    kubectl_json = output_json(kubectl_result)
    client_version = kubectl_json.get("clientVersion", {}).get("gitVersion") if isinstance(kubectl_json, dict) else None
    if client_version != f"v{pins['KUBECTL_VERSION']}":
        raise FixtureDenied("kubectl_version_mismatch")


def server_kubernetes_version(
    command_runner: CommandRunner, env: dict[str, str], kubeconfig: Path, context: str, pins: dict[str, str]
) -> str:
    result = command_runner.run(
        [
            require_tool("kubectl"),
            "--kubeconfig",
            str(kubeconfig),
            "--context",
            context,
            "version",
            "-o",
            "json",
        ],
        env,
        "kubernetes_api_version_unavailable_resources_retained",
        timeout_seconds=30,
    )
    value = checked_json(result, "kubernetes_api_version_invalid_resources_retained")
    server = value.get("serverVersion")
    git_version = server.get("gitVersion") if isinstance(server, dict) else None
    expected = pins["KIND_NODE_IMAGE_VERSION"]
    if git_version != expected:
        raise FixtureDenied("kubernetes_api_version_mismatch_resources_retained")
    return git_version[1:]


def checked_json(result: CommandResult, key: str) -> dict[str, Any]:
    value = output_json(result)
    if not isinstance(value, dict):
        raise FixtureDenied(key)
    return value


def check_docker_target(command_runner: CommandRunner, env: dict[str, str]) -> None:
    context = command_runner.run([require_tool("docker"), "context", "show"], env, "docker_target_unverified")
    if output_text(context) != "default":
        raise FixtureDenied("docker_target_unverified")
    endpoint = command_runner.run(
        [require_tool("docker"), "context", "inspect", "default", "--format", "{{.Endpoints.docker.Host}}"],
        env,
        "docker_target_unverified",
    )
    if output_text(endpoint) != "unix:///var/run/docker.sock":
        raise FixtureDenied("docker_target_unverified")


def clusters(command_runner: CommandRunner, env: dict[str, str]) -> list[str]:
    result = command_runner.run(
        [kind_tool(command_runner), "get", "clusters"], env, "kind_cluster_inventory_unavailable"
    )
    return lines(result)


def kind_container_ids(
    command_runner: CommandRunner, env: dict[str, str], cluster_name: str | None = None
) -> list[str]:
    args = [require_tool("docker"), "ps", "--all", "--no-trunc", "--quiet", "--filter", "label=io.x-k8s.kind.cluster"]
    if cluster_name:
        args[-1] = f"label=io.x-k8s.kind.cluster={cluster_name}"
    values = lines(command_runner.run(args, env, "docker_container_inventory_unavailable"))
    if any(not re.fullmatch(r"[0-9a-f]{64}", item) for item in values):
        raise FixtureDenied("docker_container_inventory_invalid")
    return sorted(set(values))


def kind_network_names(command_runner: CommandRunner, env: dict[str, str]) -> list[str]:
    result = command_runner.run(
        [require_tool("docker"), "network", "ls", "--no-trunc", "--quiet", "--filter", "name=kind"],
        env,
        "docker_network_inventory_unavailable",
    )
    return lines(result)


def network_ids(command_runner: CommandRunner, env: dict[str, str], network_id: str) -> list[str]:
    result = command_runner.run(
        [
            require_tool("docker"),
            "network",
            "ls",
            "--no-trunc",
            "--quiet",
            "--filter",
            f"id={network_id}",
        ],
        env,
        "docker_network_inventory_unavailable_resources_retained",
    )
    values = lines(result)
    if any(not re.fullmatch(r"[0-9a-f]{64}", value) for value in values):
        raise FixtureDenied("docker_network_inventory_invalid_resources_retained")
    return values


def preflight(command_runner: CommandRunner, identity: RunnerIdentity, run_env: dict[str, str]) -> None:
    for name in ("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "KUBECONFIG"):
        if os.environ.get(name):
            raise FixtureDenied("ambient_cluster_or_docker_override_refused")
    default_kubeconfig = identity.home / ".kube" / "config"
    if default_kubeconfig.exists() or default_kubeconfig.is_symlink():
        raise FixtureDenied("existing_kubeconfig_refused")

    contexts = command_runner.run(
        [require_tool("kubectl"), "config", "get-contexts", "-o", "name"],
        minimal_env(identity.home),
        "kube_context_inventory_unavailable",
    )
    if lines(contexts):
        raise FixtureDenied("existing_kube_context_refused")
    if clusters(command_runner, run_env):
        raise FixtureDenied("existing_kind_cluster_refused")
    check_docker_target(command_runner, run_env)
    if kind_container_ids(command_runner, run_env):
        raise FixtureDenied("existing_kind_container_refused")
    if kind_network_names(command_runner, run_env):
        raise FixtureDenied("existing_kind_network_refused")


def private_run_directory(identity: RunnerIdentity) -> tuple[Path, Path, Path, Path]:
    path = identity.run_directory
    try:
        path.mkdir(mode=0o700)
        os.chmod(path, 0o700)
        home = path / "home"
        docker_config = path / "docker-config"
        home.mkdir(mode=0o700)
        docker_config.mkdir(mode=0o700)
    except FileExistsError:
        raise FixtureDenied("run_attempt_claim_already_exists") from None
    except OSError:
        raise FixtureDenied("private_run_directory_unavailable") from None
    kubeconfig = path / "kubeconfig"
    return path, home, docker_config, kubeconfig


def api_address_from_config(result: CommandResult, expected_context: str) -> str:
    config = checked_json(result, "kubeconfig_identity_invalid")
    if config.get("current-context") != expected_context:
        raise FixtureDenied("kube_context_mismatch")
    contexts = config.get("contexts")
    clusters_config = config.get("clusters")
    if (
        not isinstance(contexts, list)
        or len(contexts) != 1
        or not isinstance(clusters_config, list)
        or len(clusters_config) != 1
    ):
        raise FixtureDenied("kubeconfig_identity_invalid")
    context = contexts[0].get("name") if isinstance(contexts[0], dict) else None
    cluster = clusters_config[0].get("cluster") if isinstance(clusters_config[0], dict) else None
    server = cluster.get("server") if isinstance(cluster, dict) else None
    if context != expected_context or not isinstance(server, str):
        raise FixtureDenied("kubeconfig_identity_invalid")
    parsed = urlsplit(server)
    if (
        parsed.scheme != "https"
        or parsed.username
        or parsed.password
        or parsed.path not in ("", "/")
        or parsed.query
        or parsed.fragment
    ):
        raise FixtureDenied("kube_api_address_invalid")
    try:
        port = parsed.port
    except ValueError:
        raise FixtureDenied("kube_api_address_invalid") from None
    if parsed.hostname not in ("127.0.0.1", "::1", "localhost") or port is None or not 1 <= port <= 65535:
        raise FixtureDenied("kube_api_address_not_loopback")
    return server


def docker_inspect_node(
    command_runner: CommandRunner, env: dict[str, str], node: str, cluster_name: str, image: str
) -> dict[str, str]:
    result = command_runner.run(
        [require_tool("docker"), "inspect", "--format", "{{json .}}", node],
        env,
        "docker_node_identity_unavailable",
    )
    value = checked_json(result, "docker_node_identity_invalid")
    identifier = value.get("Id")
    config = value.get("Config")
    labels = config.get("Labels") if isinstance(config, dict) else None
    if not isinstance(identifier, str) or not re.fullmatch(r"[0-9a-f]{64}", identifier):
        raise FixtureDenied("docker_node_identity_invalid")
    if not isinstance(labels, dict) or labels.get("io.x-k8s.kind.cluster") != cluster_name:
        raise FixtureDenied("docker_node_identity_mismatch")
    if labels.get("io.x-k8s.kind.role") not in ("control-plane", "worker"):
        raise FixtureDenied("docker_node_identity_mismatch")
    if config.get("Image") != image or not value.get("State", {}).get("Running", False):
        raise FixtureDenied("docker_node_identity_mismatch")
    return {"id": identifier, "name": str(value.get("Name", "")).lstrip("/"), "role": labels["io.x-k8s.kind.role"]}


def network_identity(
    command_runner: CommandRunner,
    env: dict[str, str],
    node_ids: set[str],
    network_reference: str = "kind",
) -> dict[str, str]:
    result = command_runner.run(
        [require_tool("docker"), "network", "inspect", "--format", "{{json .}}", network_reference],
        env,
        "docker_network_identity_unavailable",
    )
    network = checked_json(result, "docker_network_identity_invalid")
    identifier = network.get("Id")
    endpoints = network.get("Containers")
    if not isinstance(identifier, str) or not re.fullmatch(r"[0-9a-f]{64}", identifier):
        raise FixtureDenied("docker_network_identity_invalid")
    endpoint_ids = (
        set(endpoints) if isinstance(endpoints, dict) else set() if endpoints is None and not node_ids else None
    )
    if network.get("Name") != "kind" or endpoint_ids != node_ids:
        raise FixtureDenied("docker_network_identity_mismatch")
    return {"id": identifier, "name": "kind"}


def namespace_uid(command_runner: CommandRunner, env: dict[str, str], kubeconfig: Path, context: str) -> str:
    result = command_runner.run(
        [
            require_tool("kubectl"),
            "--kubeconfig",
            str(kubeconfig),
            "--context",
            context,
            "get",
            "namespace",
            "kube-system",
            "--request-timeout=15s",
            "-o",
            "json",
        ],
        env,
        "kube_system_namespace_unavailable",
        timeout_seconds=30,
    )
    value = checked_json(result, "kube_system_namespace_invalid")
    metadata = value.get("metadata")
    uid = metadata.get("uid") if isinstance(metadata, dict) else None
    if value.get("kind") != "Namespace" or not isinstance(uid, str) or not UID_PATTERN.fullmatch(uid):
        raise FixtureDenied("kube_system_namespace_identity_invalid")
    if value.get("status", {}).get("phase") != "Active":
        raise FixtureDenied("kube_system_namespace_not_active")
    return uid


def create_fixture(
    command_runner: CommandRunner,
    authority_path: Path,
) -> tuple[RunnerIdentity, dict[str, Any], dict[str, str], Path, dict[str, str]]:
    identity = runner_identity(command_runner)
    pins = parse_authority(authority_path)
    run_directory, private_home, docker_config, kubeconfig = private_run_directory(identity)
    run_env = minimal_env(private_home, kubeconfig, docker_config)
    current_tool_versions(command_runner, run_env, pins)
    preflight(command_runner, identity, run_env)

    kind = kind_tool(command_runner)
    command_runner.resources_may_exist = True
    command_runner.run(
        [
            kind,
            "create",
            "cluster",
            "--name",
            identity.cluster_name,
            "--image",
            pins["KIND_NODE_IMAGE"],
            "--kubeconfig",
            str(kubeconfig),
            "--wait",
            KIND_WAIT_ARGUMENT,
            "--retain",
        ],
        run_env,
        "kind_cluster_create_failed_resources_may_be_retained",
        timeout_seconds=CREATE_TIMEOUT_SECONDS,
    )
    observed_clusters = clusters(command_runner, run_env)
    if observed_clusters != [identity.cluster_name]:
        raise FixtureDenied("cluster_claim_inventory_mismatch_resources_retained")

    expected_context = f"kind-{identity.cluster_name}"
    current_context = output_text(
        command_runner.run(
            [require_tool("kubectl"), "--kubeconfig", str(kubeconfig), "config", "current-context"],
            run_env,
            "kube_context_unavailable_resources_retained",
        )
    )
    if current_context != expected_context:
        raise FixtureDenied("kube_context_mismatch_resources_retained")
    secure_kubeconfig(kubeconfig)
    api_address = api_address_from_config(
        command_runner.run(
            [require_tool("kubectl"), "--kubeconfig", str(kubeconfig), "config", "view", "--minify", "-o", "json"],
            run_env,
            "kubeconfig_identity_unavailable_resources_retained",
        ),
        expected_context,
    )
    server_version = server_kubernetes_version(command_runner, run_env, kubeconfig, expected_context, pins)
    live_uid = namespace_uid(command_runner, run_env, kubeconfig, expected_context)

    node_names = lines(
        command_runner.run(
            [kind, "get", "nodes", "--name", identity.cluster_name],
            run_env,
            "kind_node_inventory_unavailable_resources_retained",
        )
    )
    if not node_names or len(node_names) != len(set(node_names)):
        raise FixtureDenied("kind_node_inventory_invalid_resources_retained")
    nodes = [
        docker_inspect_node(command_runner, run_env, name, identity.cluster_name, pins["KIND_NODE_IMAGE"])
        for name in node_names
    ]
    node_ids = {node["id"] for node in nodes}
    if (
        len(node_ids) != len(nodes)
        or set(kind_container_ids(command_runner, run_env, identity.cluster_name)) != node_ids
    ):
        raise FixtureDenied("kind_node_container_inventory_mismatch_resources_retained")
    network = network_identity(command_runner, run_env, node_ids)
    fixture = {
        "schemaVersion": 1,
        "state": "fixture_ready",
        "repository": identity.repository,
        "ref": identity.ref,
        "commit": identity.sha,
        "runId": identity.run_id,
        "runAttempt": identity.attempt,
        "clusterName": identity.cluster_name,
        "context": expected_context,
        "apiAddress": api_address,
        "kubeSystemNamespaceUid": live_uid,
        "kindVersion": pins["KIND_VERSION"],
        "kubectlVersion": pins["KUBECTL_VERSION"],
        "kubernetesServerVersion": server_version,
        "nodeImage": pins["KIND_NODE_IMAGE"],
        "nodeContainers": sorted(nodes, key=lambda value: value["id"]),
        "kindNetwork": network,
    }
    receipt_path = run_directory / "fixture.json"
    write_exclusive_json(receipt_path, fixture)
    return identity, fixture, pins, kubeconfig, run_env


def write_exclusive_json(path: Path, value: dict[str, Any]) -> None:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8") + b"\n"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(path, flags, 0o600)
        with os.fdopen(descriptor, "wb") as output:
            output.write(encoded)
            output.flush()
            os.fsync(output.fileno())
    except OSError:
        raise FixtureDenied("fixture_claim_write_failed_resources_retained") from None


def secure_kubeconfig(path: Path) -> None:
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_uid != os.getuid():
            raise FixtureDenied("kubeconfig_identity_invalid_resources_retained")
        os.chmod(path, 0o600)
    except FixtureDenied:
        raise
    except OSError:
        raise FixtureDenied("kubeconfig_permissions_invalid_resources_retained") from None


def read_fixture_receipt(path: Path) -> dict[str, Any]:
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_uid != os.getuid() or path.stat().st_mode & 0o077:
            raise FixtureDenied("fixture_claim_identity_invalid_resources_retained")
        value = json.loads(path.read_text(encoding="utf-8"))
    except FixtureDenied:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise FixtureDenied("fixture_claim_identity_invalid_resources_retained") from None
    if not isinstance(value, dict) or value.get("schemaVersion") != 1 or value.get("state") != "fixture_ready":
        raise FixtureDenied("fixture_claim_identity_invalid_resources_retained")
    return value


def assert_run_directory_owned(identity: RunnerIdentity, path: Path) -> None:
    try:
        if path.is_symlink() or not path.is_dir() or path.stat().st_uid != os.getuid() or path.stat().st_mode & 0o077:
            raise FixtureDenied("fixture_claim_identity_invalid_resources_retained")
    except OSError:
        raise FixtureDenied("fixture_claim_identity_invalid_resources_retained") from None
    if path != identity.run_directory:
        raise FixtureDenied("fixture_claim_identity_invalid_resources_retained")


def verify_fixture_identity(identity: RunnerIdentity, fixture: dict[str, Any]) -> tuple[str, str, set[str]]:
    if (
        fixture.get("repository") != identity.repository
        or fixture.get("ref") != identity.ref
        or fixture.get("commit") != identity.sha
        or fixture.get("runId") != identity.run_id
        or fixture.get("runAttempt") != identity.attempt
        or fixture.get("clusterName") != identity.cluster_name
    ):
        raise FixtureDenied("fixture_claim_run_identity_mismatch_resources_retained")
    context = f"kind-{identity.cluster_name}"
    if fixture.get("context") != context:
        raise FixtureDenied("fixture_claim_context_mismatch_resources_retained")
    api_address = fixture.get("apiAddress")
    if not isinstance(api_address, str):
        raise FixtureDenied("fixture_claim_api_mismatch_resources_retained")
    node_records = fixture.get("nodeContainers")
    if not isinstance(node_records, list) or not node_records:
        raise FixtureDenied("fixture_claim_node_identity_invalid_resources_retained")
    node_ids: set[str] = set()
    for node in node_records:
        if (
            not isinstance(node, dict)
            or not isinstance(node.get("id"), str)
            or not re.fullmatch(r"[0-9a-f]{64}", node["id"])
        ):
            raise FixtureDenied("fixture_claim_node_identity_invalid_resources_retained")
        node_ids.add(node["id"])
    if len(node_ids) != len(node_records):
        raise FixtureDenied("fixture_claim_node_identity_invalid_resources_retained")
    if not isinstance(fixture.get("kindNetwork"), dict) or not re.fullmatch(
        r"[0-9a-f]{64}", str(fixture["kindNetwork"].get("id", ""))
    ):
        raise FixtureDenied("fixture_claim_network_identity_invalid_resources_retained")
    if (
        fixture.get("nodeImage")
        != parse_authority(identity.workspace / "config" / "workflow-tool-versions.env")["KIND_NODE_IMAGE"]
    ):
        raise FixtureDenied("fixture_claim_image_mismatch_resources_retained")
    return context, api_address, node_ids


def verify_live_ownership(
    command_runner: CommandRunner,
    identity: RunnerIdentity,
    fixture: dict[str, Any],
    kubeconfig: Path,
    run_env: dict[str, str],
) -> None:
    context, expected_api, expected_node_ids = verify_fixture_identity(identity, fixture)
    if clusters(command_runner, run_env) != [identity.cluster_name]:
        raise FixtureDenied("cluster_claim_inventory_mismatch_resources_retained")
    current_context = output_text(
        command_runner.run(
            [require_tool("kubectl"), "--kubeconfig", str(kubeconfig), "config", "current-context"],
            run_env,
            "kube_context_unavailable_resources_retained",
        )
    )
    if current_context != context:
        raise FixtureDenied("kube_context_mismatch_resources_retained")
    actual_api = api_address_from_config(
        command_runner.run(
            [require_tool("kubectl"), "--kubeconfig", str(kubeconfig), "config", "view", "--minify", "-o", "json"],
            run_env,
            "kubeconfig_identity_unavailable_resources_retained",
        ),
        context,
    )
    if actual_api != expected_api:
        raise FixtureDenied("kube_api_address_mismatch_resources_retained")
    if namespace_uid(command_runner, run_env, kubeconfig, context) != fixture.get("kubeSystemNamespaceUid"):
        raise FixtureDenied("kube_system_namespace_uid_mismatch_resources_retained")
    pins = parse_authority(identity.workspace / "config" / "workflow-tool-versions.env")
    if server_kubernetes_version(command_runner, run_env, kubeconfig, context, pins) != fixture.get(
        "kubernetesServerVersion"
    ):
        raise FixtureDenied("kubernetes_api_version_mismatch_resources_retained")

    actual_node_ids = set(kind_container_ids(command_runner, run_env, identity.cluster_name))
    if actual_node_ids != expected_node_ids:
        raise FixtureDenied("kind_node_container_identity_mismatch_resources_retained")
    recorded_nodes = fixture["nodeContainers"]
    by_id = {item["id"]: item for item in recorded_nodes}
    names = lines(
        command_runner.run(
            [kind_tool(command_runner), "get", "nodes", "--name", identity.cluster_name],
            run_env,
            "kind_node_inventory_unavailable_resources_retained",
        )
    )
    inspected = [
        docker_inspect_node(command_runner, run_env, name, identity.cluster_name, fixture["nodeImage"])
        for name in names
    ]
    if {item["id"] for item in inspected} != expected_node_ids or any(
        item["name"] != by_id[item["id"]]["name"] or item["role"] != by_id[item["id"]]["role"] for item in inspected
    ):
        raise FixtureDenied("kind_node_container_identity_mismatch_resources_retained")
    expected_network = fixture["kindNetwork"].get("id")
    actual_network = network_identity(command_runner, run_env, expected_node_ids)
    if actual_network["id"] != expected_network:
        raise FixtureDenied("docker_network_identity_mismatch_resources_retained")


def teardown_fixture(
    command_runner: CommandRunner,
    identity: RunnerIdentity,
    kubeconfig: Path,
    run_env: dict[str, str],
) -> None:
    run_directory = identity.run_directory
    assert_run_directory_owned(identity, run_directory)
    fixture = read_fixture_receipt(run_directory / "fixture.json")
    verify_fixture_identity(identity, fixture)
    if kubeconfig != run_directory / "kubeconfig" or kubeconfig.is_symlink() or not kubeconfig.is_file():
        raise FixtureDenied("kubeconfig_identity_invalid_resources_retained")
    if kubeconfig.stat().st_uid != os.getuid() or kubeconfig.stat().st_mode & 0o077:
        raise FixtureDenied("kubeconfig_permissions_invalid_resources_retained")
    verify_live_ownership(command_runner, identity, fixture, kubeconfig, run_env)

    command_runner.run(
        [
            kind_tool(command_runner),
            "delete",
            "cluster",
            "--name",
            identity.cluster_name,
            "--kubeconfig",
            str(kubeconfig),
        ],
        run_env,
        "kind_teardown_failed_resources_retained",
        timeout_seconds=180,
    )
    if identity.cluster_name in clusters(command_runner, run_env):
        raise FixtureDenied("kind_teardown_identity_uncertain_resources_retained")
    if kind_container_ids(command_runner, run_env):
        raise FixtureDenied("kind_teardown_container_inventory_uncertain_resources_retained")
    recorded_network_id = fixture["kindNetwork"]["id"]
    owned_networks = network_ids(command_runner, run_env, recorded_network_id)
    networks = kind_network_names(command_runner, run_env)
    if owned_networks:
        if owned_networks != [recorded_network_id] or networks != [recorded_network_id]:
            raise FixtureDenied("docker_network_cleanup_identity_uncertain_resources_retained")
        network = network_identity(command_runner, run_env, set(), recorded_network_id)
        if network["id"] != recorded_network_id:
            raise FixtureDenied("docker_network_cleanup_identity_uncertain_resources_retained")
        command_runner.run(
            [require_tool("docker"), "network", "rm", recorded_network_id],
            run_env,
            "docker_network_cleanup_failed_resources_retained",
        )
        if network_ids(command_runner, run_env, recorded_network_id) or kind_network_names(command_runner, run_env):
            raise FixtureDenied("docker_network_cleanup_identity_uncertain_resources_retained")
    elif networks:
        raise FixtureDenied("docker_network_cleanup_identity_uncertain_resources_retained")
    try:
        kubeconfig.unlink()
    except OSError:
        raise FixtureDenied("kubeconfig_cleanup_failed_resources_retained") from None
    command_runner.resources_may_exist = False


def public_fixture(fixture: dict[str, Any]) -> dict[str, Any]:
    return {
        "state": "fixture_ready",
        "clusterName": fixture["clusterName"],
        "context": fixture["context"],
        "apiAddress": fixture["apiAddress"],
        "kubeSystemNamespaceUid": fixture["kubeSystemNamespaceUid"],
        "kindVersion": fixture["kindVersion"],
        "kubectlVersion": fixture["kubectlVersion"],
        "kubernetesServerVersion": fixture["kubernetesServerVersion"],
        "nodeImage": fixture["nodeImage"],
        "nodeContainerIds": [item["id"] for item in fixture["nodeContainers"]],
    }


def emit(value: dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def lifecycle(authority_path: Path) -> int:
    command_runner = CommandRunner()
    identity: RunnerIdentity | None = None
    fixture: dict[str, Any] | None = None
    kubeconfig: Path | None = None
    run_env: dict[str, str] | None = None
    try:
        identity, fixture, _, kubeconfig, run_env = create_fixture(command_runner, authority_path)
    except FixtureDenied as error:
        emit(
            {
                "outcome": "denied",
                "reason": error.reason,
                "fixture": public_fixture(fixture) if fixture is not None else None,
                "cleanup": "resources_retained" if command_runner.resources_may_exist else "not_created_or_not_claimed",
                "loginSuccess": False,
                "promotionCommitted": False,
                "readinessGate": "closed",
            }
        )
        return 1
    except Exception:  # noqa: BLE001 -- sanitize the lifecycle boundary; never print tool output
        emit(
            {
                "outcome": "denied",
                "reason": "unexpected_lifecycle_error",
                "fixture": public_fixture(fixture) if fixture is not None else None,
                "cleanup": "resources_retained" if command_runner.resources_may_exist else "not_created_or_not_claimed",
                "loginSuccess": False,
                "promotionCommitted": False,
                "readinessGate": "closed",
            }
        )
        return 1

    try:
        assert identity is not None and fixture is not None and kubeconfig is not None and run_env is not None
        # This lifecycle intentionally has no Account producer, receiver, promotion, or login proof steps yet.
        teardown_fixture(command_runner, identity, kubeconfig, run_env)
    except FixtureDenied as error:
        emit(
            {
                "outcome": "denied",
                "reason": error.reason,
                "fixture": public_fixture(fixture),
                "cleanup": "resources_retained",
                "loginSuccess": False,
                "promotionCommitted": False,
                "readinessGate": "closed",
            }
        )
        return 1
    except Exception:  # noqa: BLE001 -- sanitize the teardown boundary; retain uncertain resources
        emit(
            {
                "outcome": "denied",
                "reason": "unexpected_teardown_error",
                "fixture": public_fixture(fixture),
                "cleanup": "resources_retained",
                "loginSuccess": False,
                "promotionCommitted": False,
                "readinessGate": "closed",
            }
        )
        return 1

    emit(
        {
            "outcome": "incomplete_proof_flow",
            "fixture": public_fixture(fixture),
            "proof": "not_composed",
            "cleanup": "removed",
            "loginSuccess": False,
            "promotionCommitted": False,
            "readinessGate": "closed",
        }
    )
    return 2


def prepare_only(authority_path: Path) -> int:
    command_runner = CommandRunner()
    try:
        _, fixture, _, _, _ = create_fixture(command_runner, authority_path)
    except FixtureDenied as error:
        emit(
            {
                "outcome": "denied",
                "reason": error.reason,
                "cleanup": "resources_retained" if command_runner.resources_may_exist else "not_created_or_not_claimed",
                "loginSuccess": False,
                "promotionCommitted": False,
                "readinessGate": "closed",
            }
        )
        return 1
    except Exception:  # noqa: BLE001 -- sanitize the preparation boundary; never print tool output
        emit(
            {
                "outcome": "denied",
                "reason": "unexpected_lifecycle_error",
                "cleanup": "resources_retained" if command_runner.resources_may_exist else "not_created_or_not_claimed",
                "loginSuccess": False,
                "promotionCommitted": False,
                "readinessGate": "closed",
            }
        )
        return 1
    emit(
        {
            "outcome": "fixture_ready",
            "fixture": public_fixture(fixture),
            "proof": "not_run",
            "loginSuccess": False,
            "promotionCommitted": False,
            "readinessGate": "closed",
        }
    )
    return 0


def teardown_only(authority_path: Path) -> int:
    command_runner = CommandRunner()
    command_runner.resources_may_exist = True
    try:
        identity = runner_identity(command_runner)
        pins = parse_authority(authority_path)
        run_directory = identity.run_directory
        assert_run_directory_owned(identity, run_directory)
        for child in (run_directory / "home", run_directory / "docker-config"):
            if (
                child.is_symlink()
                or not child.is_dir()
                or child.stat().st_uid != os.getuid()
                or child.stat().st_mode & 0o077
            ):
                raise FixtureDenied("fixture_private_directory_invalid_resources_retained")
        fixture = read_fixture_receipt(run_directory / "fixture.json")
        verify_fixture_identity(identity, fixture)
        kubeconfig = run_directory / "kubeconfig"
        run_env = minimal_env(run_directory / "home", kubeconfig, run_directory / "docker-config")
        current_tool_versions(command_runner, run_env, pins)
        teardown_fixture(command_runner, identity, kubeconfig, run_env)
    except FixtureDenied as error:
        emit(
            {
                "outcome": "denied",
                "reason": error.reason,
                "cleanup": "resources_retained" if command_runner.resources_may_exist else "removed",
                "loginSuccess": False,
                "promotionCommitted": False,
                "readinessGate": "closed",
            }
        )
        return 1
    except Exception:  # noqa: BLE001 -- sanitize the teardown boundary; retain uncertain resources
        emit(
            {
                "outcome": "denied",
                "reason": "unexpected_teardown_error",
                "cleanup": "resources_retained" if command_runner.resources_may_exist else "removed",
                "loginSuccess": False,
                "promotionCommitted": False,
                "readinessGate": "closed",
            }
        )
        return 1
    emit(
        {
            "outcome": "fixture_torn_down",
            "fixture": public_fixture(fixture),
            "loginSuccess": False,
            "promotionCommitted": False,
            "readinessGate": "closed",
        }
    )
    return 0


def main(argv: list[str]) -> int:
    if len(argv) != 5 or argv[1] != "--authority" or argv[3] != "--operation":
        emit({"outcome": "denied", "reason": "usage_invalid", "cleanup": "not_started"})
        return 1
    authority_path = Path(argv[2])
    operation = argv[4]
    try:
        workspace = Path(required_env("GITHUB_WORKSPACE")).resolve(strict=True)
        expected_authority = (workspace / "config" / "workflow-tool-versions.env").resolve(strict=True)
        if authority_path.resolve(strict=True) != expected_authority:
            raise FixtureDenied("workflow_tool_authority_path_mismatch")
        if operation == "lifecycle":
            return lifecycle(expected_authority)
        if operation == "prepare":
            return prepare_only(expected_authority)
        if operation == "teardown":
            return teardown_only(expected_authority)
        raise FixtureDenied("usage_invalid")
    except FixtureDenied as error:
        emit(
            {
                "outcome": "denied",
                "reason": error.reason,
                "cleanup": "not_created_or_not_claimed",
                "loginSuccess": False,
                "promotionCommitted": False,
                "readinessGate": "closed",
            }
        )
        return 1
    except Exception:  # noqa: BLE001 -- sanitize the command boundary; never print private context
        emit(
            {
                "outcome": "denied",
                "reason": "unexpected_lifecycle_error",
                "cleanup": "not_created_or_not_claimed",
                "loginSuccess": False,
                "promotionCommitted": False,
                "readinessGate": "closed",
            }
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
