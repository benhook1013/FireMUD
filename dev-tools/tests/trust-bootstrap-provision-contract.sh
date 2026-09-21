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
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

helper_path = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("scoped_kubeconfigs", helper_path)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)

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
recovery = module._CREDENTIALS_BY_NAME["firemud-preview-ca-recovery"]
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

source = helper_path.read_text(encoding="utf-8")
for required in (
    "--apply --confirm",
    "system:masters",
    "kubernetes.io/service-account-token",
    "kubernetes.io/service-account.name",
    "kubernetes.io/service-account.uid",
    "--proof-namespace",
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
):
    assert required in source, required
assert "require_legacy_revoked" not in source
assert '"--all-namespaces"' not in source
assert "print(token" not in source
assert "print(kubeconfig" not in source

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
