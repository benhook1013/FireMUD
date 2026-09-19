#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

python3 - "$ROOT_DIR" <<'PY'
import re
import sys
from pathlib import Path

import yaml

root = Path(sys.argv[1])
docs = list(
    yaml.safe_load_all(
        (root / "k8s/velero/verify-backups-cronjob.yaml").read_text(encoding="utf-8")
    )
)
if len(docs) != 4:
    raise SystemExit("backup verifier projection must contain ServiceAccount, Role, RoleBinding, and CronJob")
service_account, role, binding, cronjob = docs
if service_account["kind"] != "ServiceAccount" or service_account["metadata"] != {
    "name": "verify-velero-backups",
    "namespace": "firemud",
}:
    raise SystemExit("backup verifier ServiceAccount must be owned by firemud")
if (
    role["kind"] != "Role"
    or role["metadata"].get("name") != "verify-velero-backups-reader"
    or role["metadata"].get("namespace") != "velero"
):
    raise SystemExit("backup verifier Role must be named verify-velero-backups-reader and scoped to velero")
if role["rules"] != [
    {"apiGroups": ["velero.io"], "resources": ["backups"], "verbs": ["get", "list"]}
]:
    raise SystemExit("backup verifier Role must only get/list Velero Backups")
if binding["kind"] != "RoleBinding" or binding["metadata"]["namespace"] != "velero":
    raise SystemExit("backup verifier RoleBinding must be scoped to velero")
if binding["subjects"] != [
    {"kind": "ServiceAccount", "name": "verify-velero-backups", "namespace": "firemud"}
]:
    raise SystemExit("backup verifier RoleBinding must target the firemud ServiceAccount")
if binding["roleRef"] != {
    "apiGroup": "rbac.authorization.k8s.io",
    "kind": "Role",
    "name": "verify-velero-backups-reader",
}:
    raise SystemExit("backup verifier RoleBinding must target the read-only Role")
if (
    cronjob.get("kind") != "CronJob"
    or cronjob.get("metadata", {}).get("name") != "verify-velero-backups"
    or cronjob.get("metadata", {}).get("namespace") != "firemud"
):
    raise SystemExit("backup verifier CronJob must be named verify-velero-backups in firemud")
pod = cronjob["spec"]["jobTemplate"]["spec"]["template"]["spec"]
container = pod["containers"][0]
expected_image = (
    "ghcr.io/benhook1013/backup-verifier:"
    "9c41b19b3417a004d5a70de70468de94b97805f2@"
    "sha256:f92597ca04dbd8a1821813965a95cf237c85de78db40fe91b6b539513605c60f"
)
if pod["serviceAccountName"] != "verify-velero-backups" or container["image"] != expected_image:
    raise SystemExit("backup verifier CronJob identity or digest is stale")
if container.get("securityContext", {}).get("seccompProfile") != {"type": "RuntimeDefault"}:
    raise SystemExit("backup verifier CronJob must use the RuntimeDefault seccomp profile")
if container.get("securityContext", {}).get("capabilities") != {"drop": ["ALL"]}:
    raise SystemExit("backup verifier CronJob must drop all Linux capabilities")
if "command" in container or "volumeMounts" in container or "volumes" in pod:
    raise SystemExit("backup verifier CronJob must use the image entrypoint without embedded script drift")
if container["env"] != [{"name": "VELERO_NAMESPACE", "value": "velero"}]:
    raise SystemExit("backup verifier CronJob must explicitly select the Velero namespace")

forbidden_env_names = {
    "PG_DUMP_BUCKET",
    "PG_DUMP_ENDPOINT",
    "AWS_ACCESS_KEY_ID",
    "AWS_SECRET_ACCESS_KEY",
    "AWS_SESSION_TOKEN",
}
if any(entry.get("name") in forbidden_env_names for entry in container.get("env", [])):
    raise SystemExit("backup verifier CronJob must not bundle optional object-store or credential environment")

def contains_key(value, key):
    if isinstance(value, dict):
        return key in value or any(contains_key(child, key) for child in value.values())
    if isinstance(value, list):
        return any(contains_key(child, key) for child in value)
    return False

for forbidden_key in ("secretName", "secretKeyRef", "secretRef", "envFrom"):
    if contains_key(cronjob, forbidden_key):
        raise SystemExit(
            "backup verifier CronJob must not bundle optional credential or secret configuration: "
            f"{forbidden_key}"
        )
dockerfile = (root / "docker/backup-verifier.Dockerfile").read_text(encoding="utf-8")
authority = {}
for line in (root / "config/workflow-tool-versions.env").read_text(encoding="utf-8").splitlines():
    if "=" in line and not line.startswith("#"):
        key, value = line.split("=", 1)
        authority[key] = value
expected_stage = (
    f"FROM velero/velero:v{authority['VELERO_VERSION']}@{authority['VELERO_IMAGE_DIGEST']} AS velero-cli"
)
if dockerfile.count(expected_stage) != 1:
    raise SystemExit("backup verifier Dockerfile Velero stage drifted from authority")
script_text = (root / "dev-tools/backups/verify-backups.sh").read_text(encoding="utf-8")
for required in (
    "TARGET_NAMESPACE=${FIREMUD_K8S_NAMESPACE:-firemud}",
    "VELERO_NAMESPACE=${VELERO_NAMESPACE:-velero}",
    'velero backup get -n "$VELERO_NAMESPACE"',
):
    if required not in script_text:
        raise SystemExit(f"backup verifier script is missing namespace contract: {required}")
if re.search(r'velero backup get -n "\$TARGET_NAMESPACE"', script_text):
    raise SystemExit("backup verifier script must not use the target namespace for Velero Backups")
PY

echo 'verify-backups CronJob and RBAC contract passed'
