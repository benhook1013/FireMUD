#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export ACCOUNT_BOOTSTRAP_REPO_ROOT="$ROOT_DIR"

python3 - <<'PY'
import hashlib
import importlib.util
import json
import os
import re
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path


root = Path(os.environ["ACCOUNT_BOOTSTRAP_REPO_ROOT"]).resolve()
script = root / "dev-tools/hosted/account-bootstrap/account-jwt-bootstrap-fixture.py"
head = "a" * 40
fixture_spec = importlib.util.spec_from_file_location("fixture_command_runner_contract", script)
assert fixture_spec is not None and fixture_spec.loader is not None
fixture_command_runner = importlib.util.module_from_spec(fixture_spec)
sys.modules[fixture_spec.name] = fixture_command_runner
fixture_spec.loader.exec_module(fixture_command_runner)

fake_tool = r'''#!/usr/bin/env python3
import hashlib
import json
import os
import sys
from pathlib import Path

tool = Path(sys.argv[0]).name
args = sys.argv[1:]
shim_tools = {"--kind-shim": "kind", "--kubectl-shim": "kubectl", "--docker-shim": "docker", "--git-shim": "git"}
if args[:1] and args[0] in shim_tools:
    tool = shim_tools[args[0]]
    args = args[1:]
state_path = Path(__file__).resolve().with_name("state.json")
state = json.loads(state_path.read_text())
state.setdefault("calls", []).append([tool, *args])

def save():
    state_path.write_text(json.dumps(state))

def emit(value):
    print(json.dumps(value, separators=(",", ":")))

def option(name):
    return args[args.index(name) + 1]

if tool == "git":
    if args[-2:] == ["rev-parse", "--show-toplevel"]:
        print(args[1])
    elif args[-2:] == ["rev-parse", "HEAD"]:
        print(state["head"])
    else:
        save()
        sys.exit(27)
elif tool == "kind":
    if args == ["version"]:
        print(f"kind v{state['kind_version']} go1.25.0 linux/amd64")
    elif args[:2] == ["config", "get-contexts"]:
        print("old-context" if state.get("existing_context") else "")
    elif args[:2] == ["get", "clusters"]:
        if state.get("created"):
            print(state["cluster"])
    elif args[:2] == ["create", "cluster"]:
        if state.get("create_failure"):
            save()
            sys.exit(17)
        state["cluster"] = option("--name")
        state["image"] = option("--image")
        state["node_name"] = state["cluster"] + "-control-plane"
        state["node_id"] = hashlib.sha256(("node:" + state["cluster"]).encode()).hexdigest()
        state["network_id"] = hashlib.sha256(("network:" + state["cluster"]).encode()).hexdigest()
        state["network_name"] = "kind"
        state["created"] = True
        state["network_exists"] = True
        kubeconfig = Path(os.environ["KUBECONFIG"])
        kubeconfig.write_text("fixture-only kubeconfig\n")
        kubeconfig.chmod(0o600)
    elif args[:2] == ["get", "nodes"]:
        print(state["node_name"])
    elif args[:2] == ["delete", "cluster"]:
        if state.get("delete_failure"):
            save()
            sys.exit(19)
        if option("--name") != state.get("cluster"):
            save()
            sys.exit(20)
        state["created"] = False
        if state.get("unrelated_network_after_delete"):
            state["additional_network_id"] = state["unrelated_network_after_delete"]
        if state.get("network_id_drift_after_delete"):
            state["network_id"] = "e" * 64
        if state.get("network_name_drift_after_delete"):
            state["network_name"] = "renamed-kind-network"
        if not state.get("network_survives_delete", True):
            state["network_exists"] = False
    else:
        save()
        sys.exit(21)
elif tool == "kubectl":
    if args[:2] == ["version", "--client"]:
        emit({"clientVersion": {"gitVersion": f"v{state['kubectl_version']}"}})
    elif "version" in args and "--client" not in args:
        server_version = state["wrong_server_version"] if state.get("server_version_mismatch") else state["kind_node_image_version"]
        emit({"serverVersion": {"gitVersion": server_version}})
    elif args[:3] == ["config", "get-contexts", "-o"]:
        if state.get("existing_context"):
            print("old-context")
    elif "current-context" in args:
        state["context_reads"] = state.get("context_reads", 0) + 1
        context = "kind-" + state["cluster"]
        if state.get("context_drift") and state["context_reads"] > 1:
            context = "kind-other"
        print(context)
    elif "view" in args and "--minify" in args:
        state["config_reads"] = state.get("config_reads", 0) + 1
        server = "https://127.0.0.1:9443"
        if state.get("api_drift") and state["config_reads"] > 1:
            server = "https://127.0.0.1:9444"
        context = "kind-" + state["cluster"]
        emit({"current-context": context, "contexts": [{"name": context}], "clusters": [{"cluster": {"server": server}}]})
    elif "namespace" in args and "kube-system" in args:
        state["uid_reads"] = state.get("uid_reads", 0) + 1
        uid = "12345678-1234-1234-1234-123456789abc"
        if state.get("uid_drift") and state["uid_reads"] > 1:
            uid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        emit({"kind": "Namespace", "metadata": {"uid": uid}, "status": {"phase": "Active"}})
    else:
        save()
        sys.exit(22)
elif tool == "docker":
    if args == ["context", "show"]:
        print("default")
    elif args[:2] == ["context", "inspect"]:
        print("unix:///var/run/docker.sock")
    elif args[:2] == ["ps", "--all"]:
        if state.get("created"):
            state["container_reads"] = state.get("container_reads", 0) + 1
            node_id = state["node_id"]
            if state.get("container_drift") and state["container_reads"] > 1:
                node_id = "f" * 64
            print(node_id)
    elif args[:3] == ["network", "ls", "--no-trunc"]:
        filter_value = args[-1] if args[-2:-1] == ["--filter"] else ""
        if filter_value.startswith("id="):
            if state.get("network_exists") and filter_value[3:] == state.get("network_id"):
                print(state["network_id"])
        elif state.get("network_exists") and state.get("network_name") == "kind":
            print(state["network_id"])
        if not filter_value.startswith("id=") and state.get("additional_network_id"):
            print(state["additional_network_id"])
    elif args[:2] == ["network", "inspect"]:
        if not state.get("network_exists") or args[-1] not in ("kind", state.get("network_id")):
            save()
            sys.exit(23)
        containers = {state["node_id"]: {"Name": state["node_name"]}} if state.get("created") else {}
        emit({"Id": state["network_id"], "Name": state.get("network_name", "kind"), "Containers": containers})
    elif args[:2] == ["network", "rm"]:
        if not state.get("network_exists") or args[2] != state.get("network_id"):
            save()
            sys.exit(28)
        state.setdefault("network_rm_ids", []).append(args[2])
        state["network_exists"] = False
    elif args[:2] == ["inspect", "--format"]:
        if not state.get("created"):
            save()
            sys.exit(24)
        emit({
            "Id": state["node_id"],
            "Name": "/" + state["node_name"],
            "Config": {
                "Image": state["image"],
                "Labels": {
                    "io.x-k8s.kind.cluster": state["cluster"],
                    "io.x-k8s.kind.role": "control-plane",
                },
            },
            "State": {"Running": True},
        })
    else:
        save()
        sys.exit(25)
else:
    save()
    sys.exit(26)

save()
'''


def command_shim(tool):
    return f'''#!/usr/bin/env python3
import os
import sys
from pathlib import Path

fake_tool = Path(__file__).resolve().with_name("fake-tool.py")
os.execv(sys.executable, [sys.executable, str(fake_tool), "--{tool}-shim", *sys.argv[1:]])
'''


def invoke_fixture(workspace, env, state_path, operation):
    result = subprocess.run(
        [
            "python3",
            str(workspace / "dev-tools/hosted/account-bootstrap/account-jwt-bootstrap-fixture.py"),
            "--authority",
            str(workspace / "config/workflow-tool-versions.env"),
            "--operation",
            operation,
        ],
        cwd=workspace,
        env=env,
        capture_output=True,
        text=True,
        check=False,
        timeout=40,
    )
    try:
        payload = json.loads(result.stdout.strip())
    except json.JSONDecodeError as error:
        raise AssertionError(f"fixture returned non-JSON output: {result.stdout!r}") from error
    return result, payload, json.loads(state_path.read_text())


def run_case(
    flags=None,
    run_id="9911",
    attempt="1",
    operation="prepare",
    old_context=False,
    tampered_kind=False,
    authority_overrides=None,
):
    temporary = tempfile.TemporaryDirectory(prefix="account-jwt-fixture-contract-")
    base = Path(temporary.name)
    binary_dir = base / "bin"
    home = base / "home"
    runner_temp = base / "runner-temp"
    workspace = base / "workspace"
    binary_dir.mkdir()
    home.mkdir()
    runner_temp.mkdir()
    workspace.mkdir()
    (workspace / "config").mkdir()
    (workspace / "dev-tools/hosted/account-bootstrap").mkdir(parents=True)
    (workspace / "dev-tools/hosted/account-bootstrap/account-jwt-bootstrap-fixture.py").write_bytes(script.read_bytes())
    kind_path = binary_dir / "kind"
    for name in ("kind", "kubectl", "docker", "git"):
        tool_path = binary_dir / name
        tool_path.write_text(command_shim(name))
        tool_path.chmod(0o755)
    kind_path = binary_dir / "kind"
    kind_digest = hashlib.sha256(kind_path.read_bytes()).hexdigest()
    authority_text = (root / "config/workflow-tool-versions.env").read_text()
    authority_text, replacements = re.subn(
        r"(?m)^KIND_LINUX_AMD64_SHA256=[0-9a-f]{64}$",
        f"KIND_LINUX_AMD64_SHA256={kind_digest}",
        authority_text,
    )
    assert replacements == 1
    for key, value in (authority_overrides or {}).items():
        authority_text, replacements = re.subn(
            rf"(?m)^{re.escape(key)}=[^\n]+$",
            f"{key}={value}",
            authority_text,
        )
        assert replacements == 1, key
    (workspace / "config/workflow-tool-versions.env").write_text(authority_text)
    if tampered_kind:
        kind_path.write_text(kind_path.read_text() + "\n# altered bytes; same reported version\n")
    fake_path = binary_dir / "fake-tool.py"
    fake_path.write_text(fake_tool)
    fake_path.chmod(0o755)
    state_path = binary_dir / "state.json"
    authority = dict(
        line.split("=", 1)
        for line in authority_text.splitlines()
        if line and not line.startswith("#")
    )
    node_major, node_minor, _ = authority["KIND_NODE_IMAGE_VERSION"][1:].split(".")
    state = {
        "created": False,
        "head": head,
        "existing_context": old_context,
        "kind_version": authority["KIND_VERSION"],
        "kubectl_version": authority["KUBECTL_VERSION"],
        "kind_node_image_version": authority["KIND_NODE_IMAGE_VERSION"],
        "wrong_server_version": f"v{node_major}.{int(node_minor) + 1}.0",
        **(flags or {}),
    }
    state_path.write_text(json.dumps(state))
    if old_context:
        kube_directory = home / ".kube"
        kube_directory.mkdir()
        (kube_directory / "config").write_text("existing shared context\n")
    env = os.environ.copy()
    for name in ("KUBECONFIG", "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH"):
        env.pop(name, None)
    env.update(
        {
            "PATH": f"{binary_dir}:{env.get('PATH', '')}",
            "HOME": str(home),
            "RUNNER_TEMP": str(runner_temp),
            "GITHUB_WORKSPACE": str(workspace),
            "GITHUB_ACTIONS": "true",
            "RUNNER_ENVIRONMENT": "github-hosted",
            "RUNNER_OS": "Linux",
            "RUNNER_ARCH": "X64",
            "GITHUB_REPOSITORY": "benhook1013/FireMUD",
            "GITHUB_REF": "refs/heads/develop",
            "GITHUB_EVENT_NAME": "push",
            "GITHUB_SHA": head,
            "GITHUB_RUN_ID": run_id,
            "GITHUB_RUN_ATTEMPT": attempt,
            "GITHUB_JOB": "account-bootstrap-fixture-contract",
            "GITHUB_HEAD_REF": "",
        }
    )
    result, payload, state = invoke_fixture(workspace, env, state_path, operation)
    return temporary, runner_temp, env, result, payload, state


def assert_no_delete(state):
    assert not any(call[0] == "kind" and call[1:3] == ["delete", "cluster"] for call in state["calls"]), state["calls"]


temporary, runner_temp, env, result, payload, state = run_case()
assert result.returncode == 0, (result.returncode, payload)
assert payload["outcome"] == "fixture_ready", payload
assert payload["fixture"]["state"] == "fixture_ready", payload
assert_no_delete(state)
teardown, teardown_payload, state = invoke_fixture(
    Path(env["GITHUB_WORKSPACE"]), env, Path(env["PATH"].split(":", 1)[0]) / "state.json", "teardown"
)
assert teardown.returncode == 0 and teardown_payload["outcome"] == "fixture_torn_down", teardown_payload
assert any(call[0] == "kind" and call[1:3] == ["delete", "cluster"] for call in state["calls"]), state["calls"]
assert state["network_rm_ids"] == [state["network_id"]], state
temporary.cleanup()

updated_versions = {
    "KIND_VERSION": "0.34.1",
    "KIND_LINUX_AMD64_CHECKSUM_VERSION": "0.34.1",
    "KUBECTL_VERSION": "1.35.10",
    "KIND_NODE_IMAGE_VERSION": "v1.35.9",
    "KIND_NODE_IMAGE_DIGEST": "sha256:" + "c" * 64,
}
temporary, runner_temp, env, result, payload, state = run_case(authority_overrides=updated_versions)
assert result.returncode == 0 and payload["outcome"] == "fixture_ready", payload
assert payload["fixture"]["kindVersion"] == updated_versions["KIND_VERSION"], payload
assert payload["fixture"]["kubectlVersion"] == updated_versions["KUBECTL_VERSION"], payload
assert payload["fixture"]["kubernetesServerVersion"] == updated_versions["KIND_NODE_IMAGE_VERSION"][1:], payload
assert payload["fixture"]["nodeImage"].endswith(
    f"{updated_versions['KIND_NODE_IMAGE_VERSION']}@{updated_versions['KIND_NODE_IMAGE_DIGEST']}"
), payload
teardown, teardown_payload, state = invoke_fixture(
    Path(env["GITHUB_WORKSPACE"]), env, Path(env["PATH"].split(":", 1)[0]) / "state.json", "teardown"
)
assert teardown.returncode == 0 and teardown_payload["outcome"] == "fixture_torn_down", teardown_payload
temporary.cleanup()

temporary, _, _, result, payload, state = run_case(tampered_kind=True)
assert result.returncode == 1 and payload["reason"] == "kind_binary_checksum_mismatch", payload
assert not any(call[0] == "kind" for call in state["calls"]), state["calls"]
assert not any(call[0] == "kind" and call[1:3] == ["create", "cluster"] for call in state["calls"]), state["calls"]
temporary.cleanup()

temporary, _, _, result, payload, state = run_case({"server_version_mismatch": True})
assert result.returncode == 1 and payload["reason"] == "kubernetes_api_version_mismatch_resources_retained", payload
assert_no_delete(state)
temporary.cleanup()

for drift in ("context_drift", "api_drift", "uid_drift", "container_drift"):
    temporary, _, env, result, payload, state = run_case({drift: True})
    assert result.returncode == 0 and payload["outcome"] == "fixture_ready", (drift, result.returncode, payload)
    teardown, payload, state = invoke_fixture(
        Path(env["GITHUB_WORKSPACE"]), env, Path(env["PATH"].split(":", 1)[0]) / "state.json", "teardown"
    )
    assert teardown.returncode == 1, (drift, teardown.returncode, payload)
    assert payload["outcome"] == "denied" and payload["cleanup"] == "resources_retained", (drift, payload)
    assert_no_delete(state)
    temporary.cleanup()

temporary, _, _, result, payload, state = run_case(old_context=True)
assert result.returncode == 1 and payload["reason"] == "existing_kubeconfig_refused", payload
assert_no_delete(state)
assert not any(call[0] == "kind" and call[1:3] == ["create", "cluster"] for call in state["calls"]), state["calls"]
temporary.cleanup()

temporary, _, _, result, payload, state = run_case({"create_failure": True})
assert result.returncode == 1 and payload["cleanup"] == "resources_retained", payload
assert_no_delete(state)
temporary.cleanup()

temporary, _, env, result, payload, state = run_case({"delete_failure": True})
assert result.returncode == 0 and payload["outcome"] == "fixture_ready", payload
teardown, payload, state = invoke_fixture(
    Path(env["GITHUB_WORKSPACE"]), env, Path(env["PATH"].split(":", 1)[0]) / "state.json", "teardown"
)
assert teardown.returncode == 1 and payload["cleanup"] == "resources_retained", payload
assert any(call[0] == "kind" and call[1:3] == ["delete", "cluster"] for call in state["calls"]), state["calls"]
temporary.cleanup()

temporary, _, env, result, payload, state = run_case({"network_id_drift_after_delete": True})
assert result.returncode == 0 and payload["outcome"] == "fixture_ready", payload
teardown, payload, state = invoke_fixture(
    Path(env["GITHUB_WORKSPACE"]), env, Path(env["PATH"].split(":", 1)[0]) / "state.json", "teardown"
)
assert teardown.returncode == 1 and payload["cleanup"] == "resources_retained", (payload, state)
assert payload["reason"] == "docker_network_cleanup_identity_uncertain_resources_retained", payload
assert not any(call[0] == "docker" and call[1:3] == ["network", "rm"] for call in state["calls"]), state["calls"]
temporary.cleanup()

temporary, _, env, result, payload, state = run_case({"network_name_drift_after_delete": True})
assert result.returncode == 0 and payload["outcome"] == "fixture_ready", payload
teardown, payload, state = invoke_fixture(
    Path(env["GITHUB_WORKSPACE"]), env, Path(env["PATH"].split(":", 1)[0]) / "state.json", "teardown"
)
assert teardown.returncode == 1 and payload["cleanup"] == "resources_retained", payload
assert payload["reason"] == "docker_network_cleanup_identity_uncertain_resources_retained", payload
assert not any(call[0] == "docker" and call[1:3] == ["network", "rm"] for call in state["calls"]), state["calls"]
temporary.cleanup()

temporary, _, env, result, payload, state = run_case({"unrelated_network_after_delete": "f" * 64})
assert result.returncode == 0 and payload["outcome"] == "fixture_ready", payload
teardown, payload, state = invoke_fixture(
    Path(env["GITHUB_WORKSPACE"]), env, Path(env["PATH"].split(":", 1)[0]) / "state.json", "teardown"
)
assert teardown.returncode == 1 and payload["cleanup"] == "resources_retained", payload
assert payload["reason"] == "docker_network_cleanup_identity_uncertain_resources_retained", payload
assert not any(call[0] == "docker" and call[1:3] == ["network", "rm"] for call in state["calls"]), state["calls"]
temporary.cleanup()

prepared, runner_temp, env, result, payload, state = run_case(run_id="77123", attempt="1", operation="prepare")
assert result.returncode == 0 and payload["outcome"] == "fixture_ready", payload
claim = runner_temp / f"account-jwt-bootstrap-77123-1-{head[:12]}" / "fixture.json"
receipt = json.loads(claim.read_text())
receipt["runAttempt"] = "2"
claim.write_text(json.dumps(receipt))
teardown, teardown_payload, state = invoke_fixture(
    Path(env["GITHUB_WORKSPACE"]), env, Path(env["PATH"].split(":", 1)[0]) / "state.json", "teardown"
)
assert teardown.returncode == 1 and teardown_payload["cleanup"] == "resources_retained", teardown_payload
assert teardown_payload["reason"] == "fixture_claim_run_identity_mismatch_resources_retained", teardown_payload
assert_no_delete(state)
prepared.cleanup()

first, first_runner_temp, first_env, first_result, first_payload, _ = run_case(run_id="88331", attempt="1")
second, second_runner_temp, second_env, second_result, second_payload, _ = run_case(run_id="88331", attempt="2")
assert first_result.returncode == second_result.returncode == 0
assert first_payload["fixture"]["clusterName"] != second_payload["fixture"]["clusterName"]
for runner_temp, env in ((first_runner_temp, first_env), (second_runner_temp, second_env)):
    teardown, teardown_payload, state = invoke_fixture(
        Path(env["GITHUB_WORKSPACE"]), env, Path(env["PATH"].split(":", 1)[0]) / "state.json", "teardown"
    )
    assert teardown.returncode == 0 and teardown_payload["outcome"] == "fixture_torn_down", teardown_payload
    assert any(call[0] == "kind" and call[1:3] == ["delete", "cluster"] for call in state["calls"]), state["calls"]
first.cleanup()
second.cleanup()

with tempfile.TemporaryDirectory(prefix="fixture-command-runner-contract-") as temporary_directory:
    command_directory = Path(temporary_directory)
    output_command = command_directory / "output-command.py"
    output_command.write_text(
        "import sys\n"
        "size = int(sys.argv[1])\n"
        "sys.stdout.buffer.write(b'o' * size)\n"
        "sys.stdout.flush()\n"
        "sys.stderr.buffer.write(b'e' * size)\n"
        "sys.stderr.flush()\n"
    )
    result = fixture_command_runner.CommandRunner().run(
        [sys.executable, str(output_command), str(70 * 1024)],
        os.environ.copy(),
        "command_output_contract_failed",
        timeout_seconds=10,
    )
    assert len(result.stdout) == fixture_command_runner.MAX_STREAM_BYTES, len(result.stdout)
    assert len(result.stderr) == fixture_command_runner.MAX_STREAM_BYTES, len(result.stderr)
    assert result.truncated

    result = fixture_command_runner.CommandRunner().run(
        [
            sys.executable,
            str(output_command),
            str(fixture_command_runner.MAX_COMMAND_STDOUT_BYTES + 1),
        ],
        os.environ.copy(),
        "command_output_contract_failed",
        timeout_seconds=10,
        stdout_limit=fixture_command_runner.MAX_COMMAND_STDOUT_BYTES,
    )
    assert len(result.stdout) == fixture_command_runner.MAX_COMMAND_STDOUT_BYTES, len(result.stdout)
    assert len(result.stderr) == fixture_command_runner.MAX_STREAM_BYTES, len(result.stderr)
    assert result.truncated

    for invalid_limit in (0, -1, fixture_command_runner.MAX_COMMAND_STDOUT_BYTES + 1, True, 1.5, "65536"):
        try:
            fixture_command_runner.CommandRunner().run(
                ["/command-must-not-start"],
                os.environ.copy(),
                "command_output_contract_failed",
                stdout_limit=invalid_limit,
            )
            raise AssertionError(f"invalid stdout cap unexpectedly passed: {invalid_limit!r}")
        except fixture_command_runner.FixtureDenied as error:
            assert error.reason == "command_stdout_limit_invalid", (invalid_limit, error.reason)

    process_tree_command = command_directory / "process-tree-command.py"
    process_tree_command.write_text(
        "import os, signal, subprocess, sys, time\n"
        "from pathlib import Path\n"
        "pid_file = Path(sys.argv[2])\n"
        "if sys.argv[1] == 'descendant':\n"
        "    signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
        "    pid_file.write_text(f'{os.getpid()} {os.getpgrp()}')\n"
        "    while True: time.sleep(1)\n"
        "signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))\n"
        "subprocess.Popen([sys.executable, __file__, 'descendant', str(pid_file)])\n"
        "while not pid_file.exists(): time.sleep(0.01)\n"
        "while True: time.sleep(1)\n"
    )
    process_tree_pids = command_directory / "process-tree-pids.txt"
    descendant_pid = None
    process_group = None

    def process_is_running(pid):
        try:
            process_stat = Path(f"/proc/{pid}/stat").read_text()
        except FileNotFoundError:
            return False
        process_state = process_stat.rsplit(")", 1)[1].strip().split()[0]
        return process_state not in ("Z", "X")

    try:
        try:
            fixture_command_runner.CommandRunner().run(
                [sys.executable, str(process_tree_command), "parent", str(process_tree_pids)],
                os.environ.copy(),
                "command_timeout_contract_failed",
                timeout_seconds=2,
            )
            raise AssertionError("command timeout unexpectedly passed")
        except fixture_command_runner.FixtureDenied as error:
            assert error.reason == "command_deadline_exceeded", error.reason
        descendant_pid_text, process_group_text = process_tree_pids.read_text().split()
        descendant_pid = int(descendant_pid_text)
        process_group = int(process_group_text)
        wait_deadline = time.monotonic() + 3
        while process_is_running(descendant_pid) and time.monotonic() < wait_deadline:
            time.sleep(0.02)
        assert not process_is_running(descendant_pid), "TERM-ignoring descendant survived command timeout"
    finally:
        if process_group is not None and descendant_pid is not None and process_is_running(descendant_pid):
            try:
                os.killpg(process_group, signal.SIGKILL)
            except ProcessLookupError:
                pass

print("Account JWT fixture owner API contract: mocked create, ownership, and teardown checks passed")
PY
