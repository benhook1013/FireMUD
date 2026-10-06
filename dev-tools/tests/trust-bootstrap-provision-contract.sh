#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
helper="$repo_root/dev-tools/hosted/trust-bootstrap/provision-scoped-kubeconfigs.py"

[[ -f "$helper" ]] || {
  echo "scoped kubeconfig provisioning helper is missing" >&2
  exit 1
}

python3 - "$helper" <<'PY'
import importlib.util
import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

helper_path = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("scoped_kubeconfigs", helper_path)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)


def expect_provisioning_error(callback, message):
    try:
        callback()
    except module.ProvisioningError as exc:
        assert message in str(exc)
    else:
        raise AssertionError("expected ProvisioningError")

expected = {
    "firemud-preview-namespace-manager": (
        "trusted-hosted-cluster",
        "TRUSTED_HOSTED_PREVIEW_NAMESPACE_MANAGER_KUBECONFIG",
    ),
    "firemud-preview-runtime": (
        "trusted-hosted-cluster",
        "TRUSTED_HOSTED_PREVIEW_RUNTIME_KUBECONFIG",
    ),
    "firemud-standalone-certificate-writer": (
        "trusted-hosted-cluster",
        "TRUSTED_HOSTED_STANDALONE_CERTIFICATE_KUBECONFIG",
    ),
    "firemud-hosted-identity-requester": (
        "trusted-hosted-cluster",
        "TRUSTED_HOSTED_IDENTITY_REQUESTER_KUBECONFIG",
    ),
    "firemud-preview-ca-recovery": (
        "trusted-preview-ca-recovery",
        "TRUSTED_PREVIEW_CA_RECOVERY_KUBECONFIG",
    ),
}
assert set(module._CREDENTIALS_BY_NAME) == set(expected)
for name, credential in module._CREDENTIALS_BY_NAME.items():
    assert (credential.environment, credential.github_secret) == expected[name]
    assert len(credential.rbac_probes) == 2
    assert {probe[3] for probe in credential.rbac_probes} == {"yes", "no"}

assert module.REPOSITORY == "benhook1013/FireMUD"
assert module.REPOSITORY_LEGACY_SECRET == "PREVIEW_KUBECONFIG"

assert module.select_credentials(None) == module.CREDENTIALS
assert module.select_credentials([
    "firemud-preview-runtime",
    "firemud-preview-runtime",
]) == (module._CREDENTIALS_BY_NAME["firemud-preview-runtime"],)

credential = module._CREDENTIALS_BY_NAME["firemud-preview-runtime"]
secret_name = module.token_secret_name(credential.service_account)
secret = module.service_account_token_secret(secret_name, credential)
assert secret["type"] == "kubernetes.io/service-account-token"
assert secret["metadata"]["namespace"] == "firemud-system"
assert secret["metadata"]["annotations"] == {
    "kubernetes.io/service-account.name": credential.service_account,
    "firemud.dev/provisioned-by": module.PROVISIONER,
}
assert "data" not in secret

token = "eyJhbGciOiJub25lIn0.test-token.signature"
kubeconfig = json.loads(
    module.build_kubeconfig(
        "cluster.example",
        "https://cluster.example:6443",
        "Y2E=",
        credential,
        token,
    )
)
assert kubeconfig["current-context"] == credential.service_account
assert kubeconfig["contexts"][0]["name"] == credential.service_account
assert kubeconfig["clusters"][0]["cluster"] == {
    "server": "https://cluster.example:6443",
    "certificate-authority-data": "Y2E=",
}
assert kubeconfig["users"] == [{
    "name": credential.service_account,
    "user": {"token": token},
}]
for endpoint in (
    "https://api.preview.example:6443",
    "https://192.0.2.20:6443",
    "https://[2001:db8::20]:6443",
    "https://localhost:6443",
    "https://node.localhost:6443",
    "https://127.0.0.1:6443",
    "https://[::1]:6443",
    "https://[::ffff:127.0.0.1]:6443",
):
    assert module.validate_runner_api_server(endpoint) == endpoint
for endpoint in (
    "http://api.preview.example:6443",
    "https://0.0.0.0:6443",
    "https://[::]:6443",
    "https://[::ffff:0.0.0.0]:6443",
    "https://api.preview.example",
    "https://api.preview.example:0",
    "https://api.preview.example:65536",
    "https://user@api.preview.example:6443",
    "https://api.preview.example:6443/path",
    "https://api.preview.example:6443?query=1",
    "https://api.preview.example:6443#fragment",
    "https://bad host:6443",
):
    try:
        module.validate_runner_api_server(endpoint)
    except module.ProvisioningError:
        pass
    else:
        raise AssertionError(f"accepted invalid runner API endpoint: {endpoint}")
recovery = module._CREDENTIALS_BY_NAME["firemud-preview-ca-recovery"]
assert recovery.rbac_probes[0][1] == (
    "validatingadmissionpolicies/firemud-trust-ca-secret-boundary"
)
recovery_kubeconfig = json.loads(
    module.build_kubeconfig(
        "cluster.example",
        "https://cluster.example:6443",
        "Y2E=",
        recovery,
        token,
    )
)
assert recovery_kubeconfig["current-context"] == "firemud-preview-ca-recovery"
assert recovery_kubeconfig["contexts"][0]["name"] == "firemud-preview-ca-recovery"

service_account_uid = "service-account-uid-123"
retained_secret_name = "firemud-firemud-preview-runtime-token-0123456789abcdef"
retained_secret = {
    "apiVersion": "v1",
    "kind": "Secret",
    "metadata": {
        "name": retained_secret_name,
        "namespace": module.CONTROL_NAMESPACE,
        "annotations": {
            "kubernetes.io/service-account.name": credential.service_account,
            "kubernetes.io/service-account.uid": service_account_uid,
            "firemud.dev/provisioned-by": module.PROVISIONER,
        },
        "labels": {
            "app.kubernetes.io/name": "firemud-trust-bootstrap",
            "app.kubernetes.io/component": "scoped-kubeconfig-token",
        },
    },
    "type": module.TOKEN_SECRET_TYPE,
    "data": {
        "token": "ZXhhY3QtdG9rZW4=",
        "ca.crt": "Y2E=",
        "namespace": "ZmlyZW11ZC1zeXN0ZW0=",
    },
}
with patch.object(module, "kubectl_json", return_value=retained_secret):
    assert module.read_token_secret(
        "operator-context", retained_secret_name, credential, service_account_uid
    ) == ("exact-token", "Y2E=")
for changed_field, changed_value in (
    ("kubernetes.io/service-account.uid", "different-uid"),
    ("firemud.dev/provisioned-by", "other-provisioner"),
):
    malformed = json.loads(json.dumps(retained_secret))
    malformed["metadata"]["annotations"][changed_field] = changed_value
    with patch.object(module, "kubectl_json", return_value=malformed):
        expect_provisioning_error(
            lambda: module.read_token_secret(
                "operator-context", retained_secret_name, credential, service_account_uid
            ),
            (
                "UID does not match"
                if changed_field == "kubernetes.io/service-account.uid"
                else "metadata does not prove ownership"
            ),
        )

malformed = json.loads(json.dumps(retained_secret))
malformed["metadata"]["labels"]["app.kubernetes.io/name"] = "unexpected"
with patch.object(module, "kubectl_json", return_value=malformed):
    expect_provisioning_error(
        lambda: module.read_token_secret(
            "operator-context", retained_secret_name, credential, service_account_uid
        ),
        "metadata does not prove ownership",
    )
malformed = json.loads(json.dumps(retained_secret))
malformed["data"]["unexpected"] = "extra-data"
with patch.object(module, "kubectl_json", return_value=malformed):
    expect_provisioning_error(
        lambda: module.read_token_secret(
            "operator-context", retained_secret_name, credential, service_account_uid
        ),
        "unexpected data keys",
    )
module.validate_reuse_token_secret_name(retained_secret_name, credential)
expect_provisioning_error(
    lambda: module.validate_reuse_token_secret_name(
        retained_secret_name, module._CREDENTIALS_BY_NAME["firemud-preview-namespace-manager"]
    ),
    "selected fixed ServiceAccount",
)

# New controller-created token Secrets may be owned but not populated yet.
pending_secret = json.loads(json.dumps(retained_secret))
pending_secret["metadata"]["annotations"].pop("kubernetes.io/service-account.uid")
pending_secret.pop("data")
pending_with_uid = json.loads(json.dumps(pending_secret))
pending_with_uid["metadata"]["annotations"]["kubernetes.io/service-account.uid"] = service_account_uid
pending_with_uid["data"] = {"token": retained_secret["data"]["token"]}
with (
    patch.object(
        module,
        "kubectl_json",
        side_effect=[pending_secret, pending_with_uid, retained_secret],
    ) as secret_reads,
    patch.object(module.time, "monotonic", side_effect=[0.0, 0.1, 0.2]),
    patch.object(module.time, "sleep") as sleep,
):
    assert module.wait_for_token(
        "operator-context", retained_secret_name, credential, service_account_uid, 1
    ) == ("exact-token", "Y2E=")
    assert secret_reads.call_count == 3
    assert sleep.call_count == 2

wrong_uid_secret = json.loads(json.dumps(retained_secret))
wrong_uid_secret["metadata"]["annotations"]["kubernetes.io/service-account.uid"] = "stale-uid"
with (
    patch.object(module, "kubectl_json", return_value=wrong_uid_secret) as secret_reads,
    patch.object(module.time, "monotonic", return_value=0.0),
    patch.object(module.time, "sleep") as sleep,
):
    expect_provisioning_error(
        lambda: module.wait_for_token(
            "operator-context", retained_secret_name, credential, service_account_uid, 1
        ),
        "UID does not match",
    )
    secret_reads.assert_called_once()
    sleep.assert_not_called()

# Extra non-authorizing labels and annotations do not invalidate owned credentials.
annotated_retained_secret = json.loads(json.dumps(retained_secret))
annotated_retained_secret["metadata"]["annotations"]["kubernetes.io/token-used"] = "true"
annotated_retained_secret["metadata"]["labels"]["app.kubernetes.io/managed-by"] = "controller"
with patch.object(module, "kubectl_json", return_value=annotated_retained_secret):
    assert module.read_token_secret(
        "operator-context", retained_secret_name, credential, service_account_uid
    ) == ("exact-token", "Y2E=")
for invalid_live_args in (
    ["--context", "operator-context", "--apply", "--confirm", "--proof-namespace", "pr-42"],
    ["--context", "operator-context", "--apply", "--confirm", "--proof-namespace", "pr-42", "--runner-api-server", "https://api.preview.example:6443", "--reuse-token-secret", retained_secret_name],
):
    with contextlib.redirect_stderr(io.StringIO()):
        try:
            module.parse_args(invalid_live_args)
        except SystemExit as exc:
            assert exc.code == 2
        else:
            raise AssertionError("accepted live provisioning without required endpoint or selected credential")

source = helper_path.read_text(encoding="utf-8")

def run_auth_can_i_with(status, output):
    completed = subprocess.CompletedProcess(
        args=["kubectl", "auth", "can-i"],
        returncode=status,
        stdout=output,
        stderr=b"hidden stderr",
    )
    with patch.object(module.subprocess, "run", return_value=completed) as mocked_run:
        answer = module.run_auth_can_i(["kubectl", "auth", "can-i", "get", "secrets"])
    mocked_run.assert_called_once_with(
        ["kubectl", "auth", "can-i", "get", "secrets"],
        capture_output=True,
        check=False,
    )
    return answer


assert run_auth_can_i_with(0, b"yes\n") == "yes"
assert run_auth_can_i_with(1, b"no\n") == "no"
expect_provisioning_error(
    lambda: run_auth_can_i_with(2, b"unexpected command output"),
    "unexpected status",
)
expect_provisioning_error(
    lambda: run_auth_can_i_with(0, b"yes\nno\n"),
    "malformed output",
)
expect_provisioning_error(
    lambda: run_auth_can_i_with(0, b"\xff"),
    "non-UTF-8",
)
try:
    run_auth_can_i_with(0, b"unexpected command output")
except module.ProvisioningError as exc:
    assert "unexpected command output" not in str(exc)
else:
    raise AssertionError("expected malformed auth can-i output to be rejected")

identity_json = json.dumps({
    "status": {
        "userInfo": {
            "username": f"system:serviceaccount:{module.CONTROL_NAMESPACE}:{credential.service_account}"
        }
    }
}).encode("utf-8")
with (
    patch.object(module, "run_command", return_value=identity_json) as whoami,
    patch.object(module, "run_auth_can_i", side_effect=["yes", "no"]) as probes,
):
    try:
        module.verify_generated_kubeconfig(
            module.build_kubeconfig(
                "cluster",
                "https://api.preview.example:6443",
                "Y2E=",
                credential,
                "probe-token",
            ),
            credential,
            "pr-42",
        )
    except BaseException as exc:
        raise AssertionError(f"generated kubeconfig verification failed: {exc!r}") from exc
    whoami_command = whoami.call_args.args[0]
    assert whoami_command[3:] == ["auth", "whoami", "-o", "json"]
    probe_commands = [entry.args[0] for entry in probes.call_args_list]
    assert len(probe_commands) == len(credential.rbac_probes)
    assert all(command[3] == "auth" and command[4] == "can-i" for command in probe_commands)

write_offset = source.index("file.write(kubeconfig)")
chmod_offset = source.index("Path(file.name).chmod(0o600)")
assert chmod_offset < write_offset

for required in (
    "--apply --confirm",
    "system:masters",
    "kubernetes.io/service-account-token",
    "kubernetes.io/service-account.name",
    "kubernetes.io/service-account.uid",
    "--proof-namespace",
    "--runner-api-server",
    "--reuse-token-secret",
    "--finalize",
    "--confirm-external-deletion",
    "deployment-branch-policies",
    "verify_environment_secrets",
    "rotate_provisioner_tokens",
    "firemud.dev/provisioned-by",
    "verify_legacy_no_privileges",
    "clusterrolebinding",
    "serviceaccount",
    "PREVIEW_KUBECONFIG",
    "auth",
    "can-i",
    "benhook1013/FireMUD",
    "TRUSTED_HOSTED_PREVIEW_NAMESPACE_MANAGER_KUBECONFIG",
    "TRUSTED_HOSTED_PREVIEW_RUNTIME_KUBECONFIG",
    "TRUSTED_HOSTED_STANDALONE_CERTIFICATE_KUBECONFIG",
    "TRUSTED_HOSTED_IDENTITY_REQUESTER_KUBECONFIG",
    "TRUSTED_PREVIEW_CA_RECOVERY_KUBECONFIG",
    '"gh",',
    '"secret",',
    '"set",',
    "input_bytes=kubeconfig",
    "validate_runner_api_server",
):
    assert required in source, required
assert "require_legacy_revoked" not in source
assert '"--all-namespaces"' not in source
assert "print(token" not in source
assert "print(kubeconfig" not in source
assert "--insecure-skip-tls-verify" not in source
assert '"tls-server-name"' not in source

created_secret_name = "firemud-firemud-preview-runtime-token-fedcba9876543210"
accepted_then_failed = {"accepted": False}
stderr = io.StringIO()
with contextlib.redirect_stderr(stderr):
    with (
        patch.object(module.shutil, "which", return_value="/usr/bin/tool"),
        patch.object(module, "require_operator"),
        patch.object(module, "require_proof_namespace"),
        patch.object(module, "verify_environment_policies"),
        patch.object(module, "read_selected_cluster", return_value=("cluster", "Y2E=")),
        patch.object(module, "read_service_account", return_value=service_account_uid),
        patch.object(module, "token_secret_name", return_value=created_secret_name),
        patch.object(module, "ensure_secret_absent"),
        patch.object(module, "create_token_secret"),
        patch.object(module, "wait_for_token", return_value=("sensitive-token", "Y2E=")),
        patch.object(module, "verify_generated_kubeconfig"),
        patch.object(module, "cleanup_created_secrets") as cleanup,
        patch.object(module, "rotate_provisioner_tokens") as rotate,
        patch.object(module, "publish_kubeconfig") as publish,
    ):
        def accept_then_fail(*_args):
            accepted_then_failed["accepted"] = True
            raise module.ProvisioningError("gh failed")

        publish.side_effect = accept_then_fail
        expect_provisioning_error(
            lambda: module.apply(
                (credential,),
                "operator-context",
                "pr-42",
                "https://api.preview.example:6443",
                1,
            ),
            "gh failed",
        )
        assert accepted_then_failed["accepted"] is True
        cleanup.assert_not_called()
        rotate.assert_not_called()
assert "firemud-system/" + created_secret_name in stderr.getvalue()
assert credential.github_secret in stderr.getvalue()
assert "--reuse-token-secret " + created_secret_name in stderr.getvalue()
assert "sensitive-token" not in stderr.getvalue()

# Before any publication call, failure retains the existing cleanup behavior.
with (
    patch.object(module.shutil, "which", return_value="/usr/bin/tool"),
    patch.object(module, "require_operator"),
    patch.object(module, "require_proof_namespace"),
    patch.object(module, "verify_environment_policies"),
    patch.object(module, "read_selected_cluster", return_value=("cluster", "Y2E=")),
    patch.object(module, "read_service_account", return_value=service_account_uid),
    patch.object(module, "token_secret_name", return_value=created_secret_name),
    patch.object(module, "ensure_secret_absent"),
    patch.object(module, "create_token_secret"),
    patch.object(module, "wait_for_token", return_value=("sensitive-token", "Y2E=")),
    patch.object(module, "verify_generated_kubeconfig", side_effect=module.ProvisioningError("TLS probe failed")),
    patch.object(module, "cleanup_created_secrets", return_value=True) as cleanup,
    patch.object(module, "publish_kubeconfig") as publish,
):
    expect_provisioning_error(
        lambda: module.apply(
            (credential,),
            "operator-context",
            "pr-42",
            "https://api.preview.example:6443",
            1,
        ),
        "TLS probe failed",
    )
    cleanup.assert_called_once_with("operator-context", [created_secret_name])
    publish.assert_not_called()

# Explicit reuse refuses incomplete or wrong-UID evidence without creating a replacement.
for retry_secret, expected_failure in (
    (pending_secret, "was not populated"),
    (wrong_uid_secret, "UID does not match"),
):
    with (
        patch.object(module.shutil, "which", return_value="/usr/bin/tool"),
        patch.object(module, "require_operator"),
        patch.object(module, "require_proof_namespace"),
        patch.object(module, "verify_environment_policies"),
        patch.object(module, "read_selected_cluster", return_value=("cluster", "Y2E=")),
        patch.object(module, "read_service_account", return_value=service_account_uid),
        patch.object(module, "kubectl_json", return_value=retry_secret),
        patch.object(module, "token_secret_name", side_effect=AssertionError("retry minted a token")),
        patch.object(module, "ensure_secret_absent", side_effect=AssertionError("retry created a token")),
        patch.object(module, "create_token_secret", side_effect=AssertionError("retry created a token")),
        patch.object(module, "publish_kubeconfig") as publish,
    ):
        expect_provisioning_error(
            lambda: module.apply(
                (credential,),
                "operator-context",
                "pr-42",
                "https://api.preview.example:6443",
                1,
                reuse_token_secret=retained_secret_name,
            ),
            expected_failure,
        )
        publish.assert_not_called()

# Explicit recovery reads and republishes the exact retained token and does not mint a replacement.
verified_retry_configs = []
with (
    patch.object(module.shutil, "which", return_value="/usr/bin/tool"),
    patch.object(module, "require_operator"),
    patch.object(module, "require_proof_namespace"),
    patch.object(module, "verify_environment_policies"),
    patch.object(module, "read_selected_cluster", return_value=("cluster", "Y2E=")),
    patch.object(module, "read_service_account", return_value=service_account_uid),
    patch.object(module, "read_token_secret", return_value=("exact-retained-token", "Y2E=")) as read_retained,
    patch.object(module, "token_secret_name", side_effect=AssertionError("retry minted a token")),
    patch.object(module, "ensure_secret_absent", side_effect=AssertionError("retry created a token")),
    patch.object(module, "create_token_secret", side_effect=AssertionError("retry created a token")),
    patch.object(module, "verify_generated_kubeconfig", side_effect=lambda value, *_args: verified_retry_configs.append(json.loads(value))),
    patch.object(module, "publish_kubeconfig") as publish,
    patch.object(module, "verify_environment_secrets"),
    patch.object(module, "rotate_provisioner_tokens") as rotate,
):
    with contextlib.redirect_stdout(io.StringIO()):
        result = module.apply(
            (credential,),
            "operator-context",
            "pr-42",
            "https://api.preview.example:6443",
            1,
            reuse_token_secret=retained_secret_name,
        )
    assert result == 0
    read_retained.assert_called_once_with(
        "operator-context", retained_secret_name, credential, service_account_uid
    )
    publish.assert_called_once()
    published_retry_config = json.loads(publish.call_args.args[1])
    assert published_retry_config["clusters"][0]["cluster"]["server"] == "https://api.preview.example:6443"
    assert json.loads(publish.call_args.args[1])["users"][0]["user"]["token"] == "exact-retained-token"
    rotate.assert_called_once_with(
        "operator-context", {credential.service_account: retained_secret_name}
    )

with patch.object(module, "run_command", side_effect=module.ProvisioningError("kubectl failed")) as runner_probe:
    expect_provisioning_error(
        lambda: module.verify_generated_kubeconfig(b"{}", credential, "pr-42"),
        "kubectl failed",
    )
    probe_command = runner_probe.call_args.args[0]
    assert probe_command[3:6] == ["auth", "whoami", "-o"]
    assert "--insecure-skip-tls-verify" not in probe_command

wrong_identity = json.dumps({
    "status": {"userInfo": {"username": "system:serviceaccount:other:account"}}
}).encode("utf-8")
with patch.object(module, "run_command", return_value=wrong_identity):
    expect_provisioning_error(
        lambda: module.verify_generated_kubeconfig(
            module.build_kubeconfig(
                "cluster",
                "https://127.0.0.1:6443",
                "Y2E=",
                credential,
                "probe-token",
            ),
            credential,
            "pr-42",
        ),
        "unexpected identity",
    )

# The default mode is a pure plan.  Fake commands would fail the test if the
# helper attempted to touch Kubernetes or GitHub before --apply --confirm.
with tempfile.TemporaryDirectory() as directory:
    fake_bin = Path(directory)
    for command in ("kubectl", "gh"):
        fake = fake_bin / command
        fake.write_text("#!/bin/sh\nexit 99\n", encoding="utf-8")
        fake.chmod(0o700)
    env = dict(os.environ)
    env["PATH"] = f"{fake_bin}:{env['PATH']}"
    result = subprocess.run(
        [
            sys.executable,
            str(helper_path),
            "--context",
            "operator-context",
            "--credential",
            "firemud-preview-runtime",
        ],
        cwd=helper_path.parents[3],
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, result.stderr
    assert "No cluster or GitHub mutation performed" in result.stdout
    assert "firemud-preview-runtime" in result.stdout
    assert result.stderr == ""

print("trust-bootstrap scoped kubeconfig provision contract: PASS")
PY
