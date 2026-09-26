#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 - "$ROOT_DIR" <<'PY'
import copy
import pathlib
import subprocess
import sys
import tempfile

import yaml

root = pathlib.Path(sys.argv[1])
chart = root / "k8s/helm/firemud"
sha = "a" * 64


def documents(output):
    return [document for document in yaml.safe_load_all(output) if isinstance(document, dict)]


with tempfile.TemporaryDirectory() as directory:
    temporary = pathlib.Path(directory)
    values = temporary / "preview-values.yaml"
    subprocess.run(
        [
            "python3",
            str(root / "dev-tools/hosted/preview/render-preview-values.py"),
            str(chart / "values-hosted-shared.example.yaml"),
            str(values),
            "123",
            "pr-123",
            "pr-123",
            "pr-123.preview.firedevops.net",
            "pr-123-deadbeef",
            "32000",
        ],
        check=True,
    )

    def render(name, migration=None):
        args = ["helm", "template", name, str(chart), "--namespace", "pr-123", "-f", str(values)]
        if migration is not None:
            override = temporary / f"{name}.yaml"
            override.write_text(yaml.safe_dump(migration), encoding="utf-8")
            args += ["-f", str(override)]
        return subprocess.run(args, capture_output=True, text=True, check=False)

    def require_failure(name, migration, fragment):
        result = render(name, migration)
        if result.returncode == 0 or fragment not in result.stderr:
            raise SystemExit(f"{name} did not fail closed with {fragment}: {result.stderr}")

    disabled = render("tenant-migration-disabled")
    if disabled.returncode:
        raise SystemExit(disabled.stderr)
    forbidden_names = {"game-design-tenant-migrator", "account-tenant-migrator"}
    if any(
        item.get("metadata", {}).get("name") in forbidden_names
        or item.get("metadata", {}).get("name", "").startswith(("game-design-tenant-", "account-tenant-"))
        for item in documents(disabled.stdout)
    ):
        raise SystemExit("ordinary release rendered tenant-migration resources")

    migration = {
        "previewStack": {
            "tenantAssociationMigration": {
                "gameDesign": {
                    "enabled": True,
                    "mode": "apply",
                    "targetNamespace": "pr-123",
                    "runId": "approved-42",
                    "image": f"ghcr.io/benhook1013/game-design-service@sha256:{sha}",
                    "signedManifestSecretName": "tenant-manifest-approved-42",
                    "trustedKeysSecretName": "tenant-owner-public-keys",
                },
                "account": {
                    "enabled": True,
                    "mode": "import",
                    "targetNamespace": "pr-123",
                    "runId": "approved-42",
                    "image": f"ghcr.io/benhook1013/account-service@sha256:{sha}",
                    "legacyTenantId": "41",
                },
            }
        }
    }
    enabled = render("tenant-migration-enabled", migration)
    if enabled.returncode:
        raise SystemExit(enabled.stderr)
    by_kind_name = {
        (item.get("kind"), item.get("metadata", {}).get("name")): item
        for item in documents(enabled.stdout)
    }
    for service, mode in (("game-design", "apply"), ("account", "import")):
        workload = f"{service}-tenant-migrator"
        job = by_kind_name[("Job", f"{service}-tenant-{mode}-approved-42")]
        pod = job["spec"]["template"]["spec"]
        if pod["serviceAccountName"] != workload or pod["automountServiceAccountToken"] is not False:
            raise SystemExit(f"{workload} Job did not bind the dedicated tokenless service account")
        if by_kind_name[("ServiceAccount", workload)]["automountServiceAccountToken"] is not False:
            raise SystemExit(f"{workload} service account mounts an API token")
        secret = next(volume["secret"]["secretName"] for volume in pod["volumes"] if volume["name"] == "grpc-identity")
        if secret != f"firemud-grpc-{workload}":
            raise SystemExit(f"{workload} reused an ordinary service certificate")
        policy = by_kind_name[("NetworkPolicy", workload)]["spec"]
        if policy["ingress"] != [] or set(policy["policyTypes"]) != {"Ingress", "Egress"}:
            raise SystemExit(f"{workload} has an unexpected ingress allowance")
        destination_apps = {
            selector["podSelector"]["matchLabels"]["app"]
            for rule in policy["egress"]
            for selector in rule["to"]
            if "podSelector" in selector and "app" in selector["podSelector"].get("matchLabels", {})
        }
        expected = {"postgres"} | ({"game-design-service"} if service == "account" else set())
        if destination_apps != expected:
            raise SystemExit(f"{workload} egress targets differ: {destination_apps}")

    wrong_namespace = copy.deepcopy(migration)
    wrong_namespace["previewStack"]["tenantAssociationMigration"]["account"]["targetNamespace"] = "pr-999"
    require_failure("wrong-namespace", wrong_namespace, "targetNamespace must match")
    mutable_image = copy.deepcopy(migration)
    mutable_image["previewStack"]["tenantAssociationMigration"]["gameDesign"]["image"] = "game-design:latest"
    require_failure("mutable-image", mutable_image, "image must end in @sha256")
    no_policy = copy.deepcopy(migration)
    no_policy["previewStack"]["networkPolicies"] = {"enabled": False}
    require_failure("missing-policy", no_policy, "networkPolicies.enabled=true")
    print("tenant association Helm Job render contract passed")
PY
