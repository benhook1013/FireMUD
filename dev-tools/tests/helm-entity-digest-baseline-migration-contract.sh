#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHART_DIR="$ROOT_DIR/k8s/helm/firemud"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if ! command -v helm >/dev/null 2>&1; then
  echo "helm is required to render the Entity baseline migration contract" >&2
  exit 1
fi

VALUES="$TMP_DIR/preview-values.yaml"
python3 "$ROOT_DIR/dev-tools/hosted/preview/render-preview-values.py" \
  "$CHART_DIR/values-hosted-shared.example.yaml" \
  "$VALUES" \
  123 \
  pr-123 \
  pr-123 \
  pr-123.preview.firedevops.net \
  pr-123-deadbeef \
  32000

python3 - "$ROOT_DIR" "$CHART_DIR" "$VALUES" "$TMP_DIR" <<'PY'
import copy
import pathlib
import subprocess
import sys

import yaml

root = pathlib.Path(sys.argv[1])
chart = pathlib.Path(sys.argv[2])
values = pathlib.Path(sys.argv[3])
tmp = pathlib.Path(sys.argv[4])
base = yaml.safe_load(values.read_text(encoding="utf-8"))
image = "ghcr.io/benhook1013/game-design-service@sha256:" + "a" * 64


def render(name, override=None):
    namespace = base.get("preview", {}).get("namespace", "pr-123")
    args = [
        "helm",
        "template",
        name,
        str(chart),
        "--namespace",
        namespace,
        "-f",
        str(values),
    ]
    if override is not None:
        override_path = tmp / f"{name}-override.yaml"
        override_path.write_text(yaml.safe_dump(override), encoding="utf-8")
        args.extend(["-f", str(override_path)])
    return subprocess.run(args, text=True, capture_output=True, check=False)


def documents(output):
    return [
        item
        for item in yaml.safe_load_all(output)
        if isinstance(item, dict)
    ]


def require_failure(case, override, expected_fragment):
    result = render(f"invalid-{case}", override)
    if result.returncode == 0:
        raise SystemExit(f"Helm accepted invalid migration configuration: {case}")
    if expected_fragment not in result.stderr:
        raise SystemExit(
            f"Helm failure for {case} did not explain the invalid value: {result.stderr}"
        )


def assert_database_identity(job, expected_secret_name, mode):
    container = job.get("spec", {}).get("template", {}).get("spec", {}).get("containers", [])[0]
    if container.get("envFrom"):
        raise SystemExit(f"{mode} Job must not import a broad service environment")
    env = {entry["name"]: entry for entry in container.get("env", [])}
    for name in ("FIREMUD_POSTGRES_USER", "FIREMUD_POSTGRES_PASSWORD"):
        ref = env.get(name, {}).get("valueFrom", {}).get("secretKeyRef", {})
        if ref.get("name") != expected_secret_name:
            raise SystemExit(f"{mode} Job must use {expected_secret_name} for {name}")
        if ref.get("key") != name:
            raise SystemExit(f"{mode} Job must use the exact {name} Secret key")
        if ref.get("optional") is not False:
            raise SystemExit(f"{mode} Job must require its {name} Secret key")
    if env.get("SPRING_FLYWAY_ENABLED", {}).get("value") != "false":
        raise SystemExit(f"{mode} Job must disable Flyway")

    def contains_shared_secret(value):
        if isinstance(value, dict):
            if value.get("name") == "firemud-secret" or value.get("secretName") == "firemud-secret":
                return True
            return any(contains_shared_secret(child) for child in value.values())
        if isinstance(value, list):
            return any(contains_shared_secret(child) for child in value)
        return False

    if contains_shared_secret(job):
        raise SystemExit(f"{mode} Job must not reference shared firemud-secret")


EXPECTED_MIGRATOR_POLICY_SPEC = {
    "podSelector": {
        "matchLabels": {
            "app": "game-design-baseline-migrator",
            "firemud.dev/workload": "game-design-baseline-migrator",
        }
    },
    "policyTypes": ["Ingress", "Egress"],
    "ingress": [],
    "egress": [
        {
            "to": [
                {
                    "namespaceSelector": {
                        "matchLabels": {"kubernetes.io/metadata.name": "kube-system"}
                    },
                    "podSelector": {"matchLabels": {"k8s-app": "kube-dns"}},
                }
            ],
            "ports": [
                {"protocol": "UDP", "port": 53},
                {"protocol": "TCP", "port": 53},
            ],
        },
        {
            "to": [{"podSelector": {"matchLabels": {"app": "postgres"}}}],
            "ports": [{"protocol": "TCP", "port": 5432}],
        },
        {
            "to": [
                {"podSelector": {"matchLabels": {"app": "entity-management-service"}}}
            ],
            "ports": [{"protocol": "TCP", "port": 6565}],
        },
    ],
}


def validate_migrator_network_policy(policy):
    if policy.get("spec") != EXPECTED_MIGRATOR_POLICY_SPEC:
        raise ValueError(
            "migration NetworkPolicy must exactly select the dedicated pods and allow "
            "only kube-dns UDP/TCP 53, same-namespace postgres TCP 5432, and "
            "same-namespace Entity Management TCP 6565"
        )


def assert_migrator_policy_rejected(case, mutate):
    fixture = copy.deepcopy(policy)
    mutate(fixture["spec"])
    try:
        validate_migrator_network_policy(fixture)
    except ValueError:
        return
    raise SystemExit(f"migration NetworkPolicy validator accepted invalid fixture: {case}")


disabled = render("migration-disabled")
if disabled.returncode != 0:
    raise SystemExit(f"disabled chart render failed: {disabled.stderr}")
disabled_docs = documents(disabled.stdout)
if any(
    item.get("metadata", {}).get("name", "").startswith("game-design-baseline-migrator")
    for item in disabled_docs
):
    raise SystemExit("disabled configuration rendered migration identity or Job resources")
if any(
    item.get("metadata", {}).get("name") == "game-design-baseline-migrator"
    for item in disabled_docs
    if item.get("kind") == "NetworkPolicy"
):
    raise SystemExit("disabled configuration rendered the migration NetworkPolicy")

migration = {
    "enabled": True,
    "mode": "migrate",
    "targetNamespace": "pr-123",
    "operationId": "entity-v1-to-v2-pr123-v42",
    "tenantId": "tenant-alpha",
    "versionId": "42",
    "actorIdentity": "operator:baseline-migration",
    "image": image,
    "expectedSource": {
        "kind": "ConfigMap",
        "name": "entity-v1-baseline-source-2872",
        "key": "expected-source.json",
    },
}
enabled = render(
    "migration-enabled",
    {"previewStack": {"entityDigestBaselineMigration": migration}},
)
if enabled.returncode != 0:
    raise SystemExit(f"enabled chart render failed: {enabled.stderr}")
enabled_docs = documents(enabled.stdout)
by_kind_name = {
    (item.get("kind"), item.get("metadata", {}).get("name")): item
    for item in enabled_docs
}
job_name = "game-design-baseline-migrator-entity-v1-to-v2-pr123-v42"
job = by_kind_name.get(("Job", job_name))
if job is None:
    raise SystemExit("enabled configuration did not render the operation-scoped Job")
assert_database_identity(job, "firemud-game-design-baseline-writer-db", "migrate")
if by_kind_name.get(("ServiceAccount", "game-design-baseline-migrator"), {}).get(
    "automountServiceAccountToken"
) is not False:
    raise SystemExit("migrator service account must not mount a Kubernetes API token")
role = by_kind_name.get(("Role", "game-design-baseline-migrator"))
if role is None or role.get("rules") != []:
    raise SystemExit("migrator Role must contain no Kubernetes API permissions")
role_binding = by_kind_name.get(("RoleBinding", "game-design-baseline-migrator"))
if role_binding is None:
    raise SystemExit("enabled configuration did not render the dedicated RoleBinding")

job_template = job.get("spec", {}).get("template", {})
pod_spec = job_template.get("spec", {})
if pod_spec.get("serviceAccountName") != "game-design-baseline-migrator":
    raise SystemExit("Job does not use the dedicated migrator service account")
if pod_spec.get("automountServiceAccountToken") is not False:
    raise SystemExit("Job pod must not mount a Kubernetes API token")
container = pod_spec.get("containers", [])[0]
if container.get("image") != image:
    raise SystemExit("Job image does not match the required immutable image digest")
if container.get("imagePullPolicy") != "IfNotPresent":
    raise SystemExit("digest-pinned Job image must use IfNotPresent")
if "helm.sh/hook" in job.get("metadata", {}).get("annotations", {}):
    raise SystemExit("migration Job must not be a Helm hook")
if job.get("spec", {}).get("backoffLimit") != 0:
    raise SystemExit("migration Job must stop after its first failed attempt")
labels = job_template.get("metadata", {}).get("labels", {})
if labels.get("app") != "game-design-baseline-migrator":
    raise SystemExit("migration Job must use a Service-distinct pod label")
game_design_service = next(
    (
        item
        for item in enabled_docs
        if item.get("kind") == "Service"
        and item.get("metadata", {}).get("name") == "game-design-service"
    ),
    None,
)
if game_design_service is None:
    raise SystemExit("expected game-design-service ClusterIP Service was not rendered")
if game_design_service.get("spec", {}).get("selector", {}).get("app") == labels["app"]:
    raise SystemExit("game-design-service Service would select the migration Job pod")

env = {entry["name"]: entry for entry in container.get("env", [])}
if any(
    name in env
    for name in (
        "FIREMUD_AUTH_JWT_SECRET",
        "FIREMUD_AUTH_JWT_SECRET_PATH",
    )
):
    raise SystemExit("migration Job must not receive the ordinary shared JWT signing secret")
if container.get("envFrom"):
    raise SystemExit("migration Job must not import a broad ordinary service environment")
for name, expected in (
    ("FIREMUD_ENTITY_BASELINE_MIGRATION_ENABLED", "true"),
    ("FIREMUD_ENTITY_BASELINE_MIGRATION_MODE", "migrate"),
    ("FIREMUD_ENTITY_BASELINE_MIGRATION_OPERATION_ID", migration["operationId"]),
    ("FIREMUD_ENTITY_BASELINE_MIGRATION_TENANT_ID", migration["tenantId"]),
    ("FIREMUD_ENTITY_BASELINE_MIGRATION_VERSION_ID", migration["versionId"]),
    ("FIREMUD_ENTITY_BASELINE_MIGRATION_ACTOR_IDENTITY", migration["actorIdentity"]),
    (
        "FIREMUD_ENTITY_BASELINE_MIGRATION_EXPECTED_SOURCE_PATH",
        "/migration/expected-source.json",
    ),
    ("FIREMUD_ENTITY_BASELINE_MIGRATION_TARGET_NAMESPACE", "pr-123"),
):
    if env.get(name, {}).get("value") != expected:
        raise SystemExit(f"Job did not bind {name} to its exact operator input")
for name in (
    "FIREMUD_ENTITY_BASELINE_MIGRATION_POD_NAMESPACE",
    "FIREMUD_ENTITY_BASELINE_MIGRATION_POD_SERVICE_ACCOUNT",
):
    if "fieldRef" not in env.get(name, {}).get("valueFrom", {}):
        raise SystemExit(f"Job must derive {name} from downward API identity")
for name in (
    "FIREMUD_POSTGRES_HOST",
    "FIREMUD_POSTGRES_PORT",
    "FIREMUD_POSTGRES_DB",
):
    config_ref = env.get(name, {}).get("valueFrom", {}).get("configMapKeyRef", {})
    if config_ref.get("name") != "firemud-config":
        raise SystemExit(f"Job database setting {name} is not config-projected")

volumes = {volume["name"]: volume for volume in pod_spec.get("volumes", [])}
if any(
    volume.get("secret", {}).get("secretName") == "firemud-secret"
    and volume_name not in {"grpc-identity", "grpc-trust"}
    for volume_name, volume in volumes.items()
):
    raise SystemExit("migration Job must not project the ordinary application Secret as a volume")
if volumes.get("grpc-identity", {}).get("secret", {}).get("secretName") != (
    "firemud-grpc-game-design-baseline-migrator"
):
    raise SystemExit("Job must mount its dedicated, separately provisioned gRPC identity")
if volumes.get("grpc-trust", {}).get("secret", {}).get("secretName") != "firemud-grpc-tls":
    raise SystemExit("Job must mount the fixed gRPC trust bundle")
source = volumes.get("expected-source", {}).get("configMap", {})
if source.get("name") != migration["expectedSource"]["name"]:
    raise SystemExit("Job does not mount the operator-supplied expected-source snapshot")
if source.get("items", [{}])[0].get("path") != "expected-source.json":
    raise SystemExit("expected-source JSON is not projected to the runner's fixed path")

policy = by_kind_name.get(("NetworkPolicy", "game-design-baseline-migrator"))
if policy is None:
    raise SystemExit("enabled configuration did not render the isolated migration policy")
try:
    validate_migrator_network_policy(policy)
except ValueError as error:
    raise SystemExit(str(error))

assert_migrator_policy_rejected(
    "extra-second-peer",
    lambda spec: spec["egress"][1]["to"].append(
        {"podSelector": {"matchLabels": {"app": "unrelated"}}}
    ),
)
assert_migrator_policy_rejected(
    "extra-second-port",
    lambda spec: spec["egress"][1]["ports"].append(
        {"protocol": "TCP", "port": 5433}
    ),
)
assert_migrator_policy_rejected(
    "rule-without-peer",
    lambda spec: spec["egress"].append({"ports": [{"protocol": "TCP", "port": 1}]}),
)
assert_migrator_policy_rejected(
    "empty-policy-selector", lambda spec: spec.update(podSelector={})
)
assert_migrator_policy_rejected(
    "missing-migrator-workload-label",
    lambda spec: spec["podSelector"]["matchLabels"].pop("firemud.dev/workload"),
)
assert_migrator_policy_rejected(
    "empty-peer-selector",
    lambda spec: spec["egress"].append(
        {"to": [{"podSelector": {}}], "ports": [{"protocol": "TCP", "port": 1}]}
    ),
)
assert_migrator_policy_rejected(
    "wildcard-namespace-selector",
    lambda spec: spec["egress"][0]["to"][0].update(namespaceSelector={}),
)
assert_migrator_policy_rejected(
    "wrong-dns-namespace",
    lambda spec: spec["egress"][0]["to"][0]["namespaceSelector"]["matchLabels"].update(
        {"kubernetes.io/metadata.name": "default"}
    ),
)
assert_migrator_policy_rejected(
    "wrong-dns-pod-selector",
    lambda spec: spec["egress"][0]["to"][0]["podSelector"]["matchLabels"].update(
        {"k8s-app": "not-dns"}
    ),
)
assert_migrator_policy_rejected(
    "wrong-protocol",
    lambda spec: spec["egress"][0]["ports"][0].update(protocol="TCP"),
)
assert_migrator_policy_rejected(
    "extra-end-port",
    lambda spec: spec["egress"][1]["ports"][0].update(endPort=5434),
)
assert_migrator_policy_rejected(
    "ip-block-peer",
    lambda spec: spec["egress"][1]["to"].append(
        {"ipBlock": {"cidr": "0.0.0.0/0"}}
    ),
)
assert_migrator_policy_rejected(
    "missing-egress-isolation",
    lambda spec: spec.update(policyTypes=["Ingress"]),
)

for case, mutation, fragment in (
    (
        "missing-image",
        lambda item: item.update(image=""),
        "image must be an immutable image reference",
    ),
    (
        "tagged-image",
        lambda item: item.update(image="ghcr.io/benhook1013/game-design-service:latest"),
        "must end in @sha256:",
    ),
    (
        "missing-source",
        lambda item: item.update(expectedSource={"kind": "", "name": "", "key": ""}),
        "expectedSource.kind to be ConfigMap or Secret",
    ),
    (
        "invalid-operation-id",
        lambda item: item.update(operationId="not a dns label"),
        "requires a DNS-label operationId",
    ),
    (
        "wrong-target-namespace",
        lambda item: item.update(targetNamespace="other-namespace"),
        "must exactly match the Helm release namespace",
    ),
):
    bad = copy.deepcopy(migration)
    mutation(bad)
    require_failure(
        case,
        {"previewStack": {"entityDigestBaselineMigration": bad}},
        fragment,
    )

preflight = {
    **{key: value for key, value in migration.items() if key != "expectedSource"},
    "mode": "preflight",
    "operationId": "",
}
preflight_result = render(
    "migration-preflight",
    {"previewStack": {"entityDigestBaselineMigration": preflight}},
)
if preflight_result.returncode != 0:
    raise SystemExit(f"scoped preflight render failed: {preflight_result.stderr}")
preflight_docs = documents(preflight_result.stdout)
preflight_job = next(
    item for item in preflight_docs if item.get("kind") == "Job" and item.get("metadata", {}).get("name", "").startswith("game-design-baseline-migrator-preflight-")
)
assert_database_identity(preflight_job, "firemud-game-design-baseline-reader-db", "preflight")
if any(volume.get("name") == "expected-source" for volume in preflight_job["spec"]["template"]["spec"].get("volumes", [])):
    raise SystemExit("read-only preflight unexpectedly requires or mounts an expected-source tuple")

enumerate_values = {
    **{key: value for key, value in migration.items() if key not in {"tenantId", "versionId", "expectedSource"}},
    "mode": "enumerate",
    "operationId": "",
    "afterBaselineId": "0",
    "limit": 500,
}
enumerate_result = render(
    "migration-enumerate",
    {"previewStack": {"entityDigestBaselineMigration": enumerate_values}},
)
if enumerate_result.returncode != 0:
    raise SystemExit(f"bounded enumerate render failed: {enumerate_result.stderr}")
enumerate_docs = documents(enumerate_result.stdout)
enumerate_job = next(
    item for item in enumerate_docs if item.get("kind") == "Job" and "-enumerate-" in item.get("metadata", {}).get("name", "")
)
assert_database_identity(enumerate_job, "firemud-game-design-baseline-reader-db", "enumerate")
enumerate_env = {
    entry["name"]: entry
    for entry in enumerate_job["spec"]["template"]["spec"]["containers"][0].get("env", [])
}
if enumerate_env.get("FIREMUD_ENTITY_BASELINE_MIGRATION_LIMIT", {}).get("value") != "500":
    raise SystemExit("enumeration Job did not carry the bounded page limit")
if enumerate_env.get("FIREMUD_ENTITY_BASELINE_MIGRATION_AFTER_BASELINE_ID", {}).get("value") != "0":
    raise SystemExit("enumeration Job did not start from the canonical baseline ID cursor")

require_failure(
    "enumerate-scope",
    {
        "previewStack": {
            "entityDigestBaselineMigration": {
                **enumerate_values,
                "tenantId": "tenant-alpha",
            }
        }
    },
    "enumerate mode must not set tenantId or versionId",
)
require_failure(
    "excessive-limit",
    {
        "previewStack": {
            "entityDigestBaselineMigration": {
                **enumerate_values,
                "limit": 501,
            }
        }
    },
    "limit must be between 1 and 500",
)
unguarded_values = copy.deepcopy(migration)
unguarded_result = render(
    "migration-network-policy-required",
    {
        "previewStack": {
            "networkPolicies": {"enabled": False},
            "entityDigestBaselineMigration": unguarded_values,
        }
    },
)
if unguarded_result.returncode == 0 or "networkPolicies.enabled=true" not in unguarded_result.stderr:
    raise SystemExit("migration Job rendered without its egress NetworkPolicy")
print("Entity digest baseline migration Helm contract passed")
PY
