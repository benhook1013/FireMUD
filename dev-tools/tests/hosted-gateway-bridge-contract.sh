#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHART_DIR="$ROOT_DIR/k8s/helm/firemud"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if ! command -v helm >/dev/null 2>&1; then
  echo "helm is required to render the hosted Gateway bridge contract" >&2
  exit 1
fi

python3 "$ROOT_DIR/dev-tools/hosted/preview/render-preview-values.py" \
  "$CHART_DIR/values-hosted-shared.example.yaml" \
  "$TMP_DIR/values.yaml" \
  42 pr-42 pr-42 pr-42.preview.example.test test 32042
python3 "$ROOT_DIR/dev-tools/hosted/dev-demo/render-dev-demo-values.py" \
  "$CHART_DIR/values-hosted-shared.example.yaml" \
  "$TMP_DIR/rendered-dev-demo-values.yaml" \
  dev dev-demo dev.preview.firedevops.net test 32016
if ! grep -q '^    trustEnvironment: pr-preview$' "$TMP_DIR/values.yaml"; then
  echo "preview renderer did not select pr-preview trust environment" >&2
  exit 1
fi
if ! grep -q '^    trustEnvironment: dev-demo-cluster$' "$TMP_DIR/rendered-dev-demo-values.yaml"; then
  echo "dev-demo renderer did not select dev-demo-cluster trust environment" >&2
  exit 1
fi
python3 - "$TMP_DIR/values.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

path = Path(sys.argv[1])
values = yaml.safe_load(path.read_text(encoding="utf-8"))
values["previewStack"]["telnetTls"]["secretName"] = "pr-42-telnet-tls"
path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY

if ! service_indexes="$(python3 - "$TMP_DIR/values.yaml" <<'PY'
import pathlib
import sys

import yaml

values = yaml.safe_load(pathlib.Path(sys.argv[1]).read_text())
services = (values or {}).get("previewStack", {}).get("services")
if not isinstance(services, list):
    raise SystemExit("previewStack.services must be a list")

indexes = {}
for index, service in enumerate(services):
    if isinstance(service, dict) and service.get("name") in {
        "spring-cloud-gateway",
        "tcp-proxy-service",
    }:
        indexes.setdefault(service["name"], []).append(index)

for service_name in ("spring-cloud-gateway", "tcp-proxy-service"):
    matches = indexes.get(service_name, [])
    if len(matches) != 1:
        raise SystemExit(
            f"expected exactly one previewStack.services entry named "
            f"{service_name}, found {len(matches)}"
        )

print(indexes["spring-cloud-gateway"][0], indexes["tcp-proxy-service"][0])
PY
)"; then
  echo "could not derive unique Gateway and TCP Proxy service indexes" >&2
  exit 1
fi
read -r gateway_index proxy_index <<<"$service_indexes"
if [[ -z "$gateway_index" || -z "$proxy_index" ]]; then
  echo "derived Gateway and TCP Proxy service indexes were empty" >&2
  exit 1
fi

python3 - "$TMP_DIR/values.yaml" "$TMP_DIR/private-values.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

source_path, output_path = map(Path, sys.argv[1:])
values = yaml.safe_load(source_path.read_text(encoding="utf-8"))
values["previewStack"]["certificateIdentity"]["mode"] = "hosted-controller"
proxy = next(
    service
    for service in values["previewStack"]["services"]
    if service["name"] == "tcp-proxy-service"
)
proxy["serviceType"] = "ClusterIP"
for port in proxy.get("ports", []):
    port.pop("nodePort", None)
output_path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY

helm template pr-42 "$CHART_DIR" \
  --namespace pr-42 \
  -f "$TMP_DIR/private-values.yaml" \
  >"$TMP_DIR/rendered.yaml"

python3 - "$TMP_DIR/rendered.yaml" \
  "$ROOT_DIR/dev-tools/hosted/preview/validate-preview-artifact.py" <<'PY'
import importlib.util
import pathlib
import subprocess
import sys
import tempfile

import yaml

validator_path = pathlib.Path(sys.argv[2])
validator_spec = importlib.util.spec_from_file_location(
    "preview_artifact_validator_contract", validator_path
)
if validator_spec is None or validator_spec.loader is None:
    raise SystemExit(f"could not load preview artifact validator: {validator_path}")
validator = importlib.util.module_from_spec(validator_spec)
validator_spec.loader.exec_module(validator)

documents = [
    document
    for document in yaml.safe_load_all(pathlib.Path(sys.argv[1]).read_text())
    if isinstance(document, dict)
]

def named(kind, name):
    matches = [
        document for document in documents
        if document.get("kind") == kind
        and document.get("metadata", {}).get("name") == name
    ]
    if len(matches) != 1:
        raise SystemExit(f"expected exactly one {kind}/{name}, found {len(matches)}")
    return matches[0]

def env_map(deployment):
    containers = deployment["spec"]["template"]["spec"]["containers"]
    container = next(item for item in containers if item["name"] == deployment["metadata"]["name"])
    return container, {item["name"]: item.get("value") for item in container.get("env", [])}

def assert_restricted_workload(document, expected_uid):
    workload_name = document["metadata"]["name"]
    pod_spec = document["spec"]["template"]["spec"]
    pod_security = pod_spec.get("securityContext") or {}
    expected_pod_security = {
        "runAsNonRoot": True,
        "runAsUser": expected_uid,
        "runAsGroup": expected_uid,
        "fsGroup": expected_uid,
        "seccompProfile": {"type": "RuntimeDefault"},
    }
    for field, expected in expected_pod_security.items():
        if pod_security.get(field) != expected:
            raise SystemExit(
                f"{workload_name} pod securityContext {field} did not render as {expected!r}: "
                f"{pod_security.get(field)!r}"
            )
    containers = pod_spec.get("containers") or []
    if not containers:
        raise SystemExit(f"{workload_name} rendered without containers")
    for container in containers:
        container_name = container.get("name", "<unnamed>")
        security = container.get("securityContext") or {}
        expected_container_security = {
            "allowPrivilegeEscalation": False,
            "runAsUser": expected_uid,
            "runAsGroup": expected_uid,
            "capabilities": {"drop": ["ALL"]},
        }
        for field, expected in expected_container_security.items():
            if security.get(field) != expected:
                raise SystemExit(
                    f"{workload_name}/{container_name} container securityContext {field} "
                    f"did not render as {expected!r}: {security.get(field)!r}"
                )

infrastructure_uids = {
    "postgres": 999,
    "redis-coord": 999,
    "redis-cache": 999,
    "minio": 1000,
}
deployments = [document for document in documents if document.get("kind") == "Deployment"]
if not deployments:
    raise SystemExit("expected rendered application and infrastructure Deployments")
for deployment in deployments:
    deployment_name = deployment["metadata"]["name"]
    assert_restricted_workload(
        deployment, infrastructure_uids.get(deployment_name, 1000)
    )
seed_job = named("Job", "firemud-seed")
assert_restricted_workload(seed_job, 1000)

gateway_deployment = named("Deployment", "spring-cloud-gateway")
proxy_deployment = named("Deployment", "tcp-proxy-service")
gateway, gateway_env = env_map(gateway_deployment)
proxy, proxy_env = env_map(proxy_deployment)
if proxy_deployment["spec"].get("strategy") != {"type": "Recreate"}:
    raise SystemExit("preview TCP Proxy Deployment must use the Recreate strategy")
postgres_deployment = named("Deployment", "postgres")
postgres, postgres_env = env_map(postgres_deployment)
if postgres_env.get("PGDATA") != "/var/lib/postgresql/data/pgdata":
    raise SystemExit(
        "PostgreSQL PGDATA did not render as /var/lib/postgresql/data/pgdata"
    )
postgres_pod_spec = postgres_deployment["spec"]["template"]["spec"]
postgres_init_containers = postgres_pod_spec.get("initContainers") or []
if len(postgres_init_containers) != 1:
    raise SystemExit(
        "PostgreSQL must render exactly one data-layout guard init container"
    )
postgres_layout_guard = postgres_init_containers[0]
if postgres_layout_guard != validator.POSTGRES_DATA_LAYOUT_CHECK_INIT_CONTAINER:
    raise SystemExit(
        "PostgreSQL data-layout guard drifted from "
        "POSTGRES_DATA_LAYOUT_CHECK_INIT_CONTAINER in validate-preview-artifact.py"
    )
if postgres_layout_guard.get("name") != "postgres-data-layout-check":
    raise SystemExit("PostgreSQL data-layout guard has an unexpected name")
if postgres_layout_guard.get("image") != postgres["image"]:
    raise SystemExit("PostgreSQL data-layout guard must use the PostgreSQL image")
if postgres_layout_guard.get("command") != ["sh", "-ec"]:
    raise SystemExit("PostgreSQL data-layout guard must execute a shell check")
if postgres_layout_guard.get("volumeMounts") != [{
    "name": "postgres-data",
    "mountPath": "/var/lib/postgresql/data",
    "readOnly": True,
}]:
    raise SystemExit(
        "PostgreSQL data-layout guard must read the PostgreSQL PVC read-only"
    )
guard_script = (postgres_layout_guard.get("args") or [None])[0]
if (
    not isinstance(guard_script, str)
    or 'data_root="/var/lib/postgresql/data"' not in guard_script
    or '"${data_root}/PG_VERSION"' not in guard_script
):
    raise SystemExit("PostgreSQL data-layout guard did not inspect the legacy PG_VERSION marker")
for layout, expected_returncode in (("legacy", 1), ("fresh", 0), ("nested", 0)):
    with tempfile.TemporaryDirectory() as data_root:
        data_root_path = pathlib.Path(data_root)
        if layout == "legacy":
            (data_root_path / "PG_VERSION").write_text("16\n")
        elif layout == "nested":
            nested_path = data_root_path / "pgdata"
            nested_path.mkdir()
            (nested_path / "PG_VERSION").write_text("16\n")
        rendered_guard_script = guard_script.replace(
            "/var/lib/postgresql/data", data_root
        )
        guard_result = subprocess.run(
            ["sh", "-ec", rendered_guard_script],
            capture_output=True,
            text=True,
            check=False,
        )
        if guard_result.returncode != expected_returncode:
            raise SystemExit(
                f"PostgreSQL data-layout guard returned {guard_result.returncode} for {layout} layout"
            )
        if layout == "legacy" and "legacy root PG_VERSION" not in guard_result.stderr:
            raise SystemExit(
                "PostgreSQL data-layout guard did not clearly reject the legacy root marker"
            )
expected_gateway = {
    "FIREMUD_GATEWAY_TCP_PROXY_TLS_ENABLED": "true",
    "FIREMUD_GATEWAY_TCP_PROXY_TLS_BIND_ADDRESS": "0.0.0.0",
    "FIREMUD_GATEWAY_TCP_PROXY_TLS_PORT": "8443",
    "FIREMUD_GATEWAY_TCP_PROXY_TLS_CERT_CHAIN_PATH": "/gateway-ws-server-tls/tls.crt",
    "FIREMUD_GATEWAY_TCP_PROXY_TLS_PRIVATE_KEY_PATH": "/gateway-ws-server-tls/tls.key",
    "FIREMUD_GATEWAY_TCP_PROXY_TLS_CLIENT_CA_PATH": "/gateway-ws-server-tls/ca.crt",
    "FIREMUD_GATEWAY_TCP_PROXY_TRUST_ENVIRONMENT": "pr-preview",
    "FIREMUD_GATEWAY_TCP_PROXY_TRUST_PROFILE": "production_uri",
    "FIREMUD_GATEWAY_TCP_PROXY_TRUST_URI_SAN": "spiffe://firemud/ns/pr-42/sa/tcp-proxy-service",
}
for key, value in expected_gateway.items():
    if gateway_env.get(key) != value:
        raise SystemExit(f"Gateway env {key} did not render as {value!r}: {gateway_env.get(key)!r}")
expected_proxy = {
    "GATEWAY_WS_URL": "wss://spring-cloud-gateway-mtls.pr-42.svc.cluster.local:443/ws/game",
    "FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH": "/gateway-ws-client-tls/tls.crt",
    "FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH": "/gateway-ws-client-tls/tls.key",
    "FIREMUD_GATEWAY_WS_CA_CERT_PATH": "/gateway-ws-client-tls/ca.crt",
}
for key, value in expected_proxy.items():
    if proxy_env.get(key) != value:
        raise SystemExit(f"Proxy env {key} did not render as {value!r}: {proxy_env.get(key)!r}")
for env in (gateway_env, proxy_env):
    if any("INSECURE" in key for key in env):
        raise SystemExit("insecure Gateway header trust env remained in the hosted render")

def secret_volume(pod_spec, volume_name, secret_name):
    volume = next(item for item in pod_spec["volumes"] if item["name"] == volume_name)
    secret = volume.get("secret") or {}
    if secret.get("secretName") != secret_name:
        raise SystemExit(f"{volume_name} did not use Secret {secret_name}")
    expected_items = [
        {"key": "tls.crt", "path": "tls.crt"},
        {"key": "tls.key", "path": "tls.key"},
        {"key": "ca.crt", "path": "ca.crt"},
    ]
    if secret.get("items") != expected_items:
        raise SystemExit(f"{volume_name} did not select exactly tls.crt/tls.key/ca.crt")

for deployment, pod_spec, volume_name, mount_name, mount_path, secret_name in (
    (gateway_deployment, gateway_deployment["spec"]["template"]["spec"], "gateway-ws-server-tls", "gateway-ws-server-tls", "/gateway-ws-server-tls", "pr-42-gateway-internal-ws"),
    (proxy_deployment, proxy_deployment["spec"]["template"]["spec"], "gateway-ws-client-tls", "gateway-ws-client-tls", "/gateway-ws-client-tls", "pr-42-tcp-proxy-bridge"),
):
    pod_container = next(item for item in pod_spec["containers"] if item["name"] == deployment["metadata"]["name"])
    if {item["name"]: item for item in pod_container["volumeMounts"]}[mount_name] != {
        "name": mount_name, "mountPath": mount_path, "readOnly": True
    }:
        raise SystemExit(f"{deployment['metadata']['name']} bridge mount is not distinct/read-only")
    secret_volume(pod_spec, volume_name, secret_name)

gateway_service = named("Service", "spring-cloud-gateway-mtls")
if gateway_service["spec"].get("type") != "ClusterIP":
    raise SystemExit("Gateway mTLS Service must remain internal ClusterIP")
if gateway_service["spec"].get("ports") != [{"name": "wss-mtls", "port": 443, "targetPort": 8443, "protocol": "TCP"}]:
    raise SystemExit("Gateway mTLS Service must expose only 443 -> 8443")

proxy_service = named("Service", "tcp-proxy-service")
if proxy_service["spec"].get("type") != "ClusterIP":
    raise SystemExit("Hosted TCP Proxy Service must remain private ClusterIP")
proxy_ports = proxy_service["spec"].get("ports") or []
if len(proxy_ports) != 1 or proxy_ports[0].get("port") != 2323 or "nodePort" in proxy_ports[0]:
    raise SystemExit("Hosted TCP Proxy Service must expose only private port 2323 without nodePort")

identity_mode_label = "firemud.dev/certificate-identity-mode"
for document in (proxy_deployment, proxy_service):
    labels = document.get("metadata", {}).get("labels", {})
    if labels.get(identity_mode_label) != "hosted-controller":
        raise SystemExit(
            f"hosted TCP Proxy {document['kind']} did not render the hosted-controller identity mode label"
        )
labelled_objects = {
    (document.get("kind"), document.get("metadata", {}).get("name"))
    for document in documents
    if identity_mode_label in document.get("metadata", {}).get("labels", {})
}
if labelled_objects != {("Deployment", "tcp-proxy-service"), ("Service", "tcp-proxy-service")}:
    raise SystemExit(
        "certificate identity mode label rendered on an unexpected hosted object"
    )

account_policy = named("NetworkPolicy", "account-service-controller-ingress")["spec"]
if account_policy != {
    "podSelector": {"matchLabels": {"app": "account-service"}},
    "policyTypes": ["Ingress"],
    "ingress": [{
        "from": [{
            "namespaceSelector": {
                "matchLabels": {"kubernetes.io/metadata.name": "firemud-system"}
            },
            "podSelector": {
                "matchLabels": {
                    "app.kubernetes.io/name": "hosted-environment-identity-controller",
                    "app.kubernetes.io/component": "controller",
                }
            },
        }],
        "ports": [{"protocol": "TCP", "port": 6565}],
    }],
}:
    raise SystemExit(
        "Hosted Account ingress policy must allow only the identity controller to TCP 6565"
    )

gateway_policy = named("NetworkPolicy", "spring-cloud-gateway-ingress")["spec"]
if gateway_policy["podSelector"] != {"matchLabels": {"app": "spring-cloud-gateway"}}:
    raise SystemExit("Gateway ingress policy selected an unexpected workload")
gateway_proxy_rule = {
    "from": [{"podSelector": {"matchLabels": {"app": "tcp-proxy-service"}}}],
    "ports": [{"protocol": "TCP", "port": 8443}],
}
gateway_controller_rule = {
    "from": [{
        "namespaceSelector": {
            "matchLabels": {"kubernetes.io/metadata.name": "firemud-system"}
        },
        "podSelector": {
            "matchLabels": {
                "app.kubernetes.io/name": "hosted-environment-identity-controller",
                "app.kubernetes.io/component": "controller",
            }
        },
    }],
    "ports": [{"protocol": "TCP", "port": 8443}],
}
gateway_8443_rules = [
    rule for rule in gateway_policy["ingress"]
    if rule.get("ports") == [{"protocol": "TCP", "port": 8443}]
]
if gateway_8443_rules != [gateway_proxy_rule, gateway_controller_rule]:
    raise SystemExit(
        "Gateway ingress policy did not restrict exactly the proxy and identity controller to 8443"
    )
if any(
    rule.get("from") == [{"podSelector": {}}]
    and rule.get("ports") == [{"protocol": "TCP", "port": 8080}]
    for rule in gateway_policy["ingress"]
):
    raise SystemExit("Gateway ingress policy broadly exposed port 8080 to every pod")
if any(rule.get("ports") == [{"protocol": "TCP", "port": 6565}] for rule in gateway_policy["ingress"]):
    raise SystemExit("Gateway ingress policy unexpectedly exposed the gRPC port")
gateway_egress_policy = named("NetworkPolicy", "spring-cloud-gateway-egress")["spec"]
if gateway_egress_policy != {
    "podSelector": {"matchLabels": {"app": "spring-cloud-gateway"}},
    "policyTypes": ["Egress"],
    "egress": [
        {
            "to": [{
                "namespaceSelector": {
                    "matchLabels": {"kubernetes.io/metadata.name": "kube-system"}
                },
                "podSelector": {"matchLabels": {"k8s-app": "kube-dns"}},
            }],
            "ports": [
                {"protocol": "UDP", "port": 53},
                {"protocol": "TCP", "port": 53},
            ],
        },
        {
            "to": [{
                "podSelector": {
                    "matchExpressions": [{
                        "key": "app",
                        "operator": "In",
                        "values": [
                            "game-session-service",
                            "logging-admin-service",
                            "game-design-service",
                            "account-service",
                            "social-groups-service",
                        ],
                    }],
                }
            }],
            "ports": [{"protocol": "TCP", "port": 8080}],
        },
        {
            "to": [{"podSelector": {"matchLabels": {"app": "redis-cache"}}}],
            "ports": [{"protocol": "TCP", "port": 6379}],
        },
        {
            "to": [{"podSelector": {"matchLabels": {"app": "otel-collector"}}}],
            "ports": [{"protocol": "TCP", "port": 4317}],
        },
    ],
}:
    raise SystemExit(
        "Gateway egress policy must allow only DNS, canonical HTTP routes, Redis cache, and OTEL"
    )
proxy_policy = named("NetworkPolicy", "tcp-proxy-service-egress")["spec"]
proxy_gateway_rules = [
    rule for rule in proxy_policy["egress"]
    if rule.get("ports") == [{"protocol": "TCP", "port": 8443}]
]
if proxy_gateway_rules != [{
    "to": [{"podSelector": {"matchLabels": {"app": "spring-cloud-gateway"}}}],
    "ports": [{"protocol": "TCP", "port": 8443}],
}]:
    raise SystemExit("Proxy egress policy did not restrict Gateway access exactly to 8443")
required_proxy_egress = {
    (rule["to"][0].get("podSelector", {}).get("matchLabels", {}).get("app"), rule["ports"][0]["port"])
    for rule in proxy_policy["egress"]
    if rule.get("to") and rule.get("ports")
}
for dependency in {
    ("game-session-service", 6565),
    ("otel-collector", 4317),
    ("elasticsearch", 9200),
}:
    if dependency not in required_proxy_egress:
        raise SystemExit(f"Proxy egress policy omitted required dependency {dependency}")
PY

helm template standalone-pr-42 "$CHART_DIR" \
  --namespace pr-42 \
  -f "$TMP_DIR/values.yaml" \
  --set previewStack.certificateIdentity.mode=standalone \
  >"$TMP_DIR/standalone-rendered.yaml"
python3 - <<'PY' "$TMP_DIR/standalone-rendered.yaml"
import pathlib
import sys

import yaml

documents = [
    document
    for document in yaml.safe_load_all(pathlib.Path(sys.argv[1]).read_text())
    if isinstance(document, dict)
]
if any(
    document.get("kind") == "NetworkPolicy"
    and document.get("metadata", {}).get("name") == "account-service-controller-ingress"
    for document in documents
):
    raise SystemExit(
        "standalone certificate identity mode unexpectedly rendered the hosted controller Account ingress policy"
    )
for kind in ("Deployment", "Service"):
    matches = [
        document
        for document in documents
        if document.get("kind") == kind
        and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    ]
    if len(matches) != 1:
        raise SystemExit(
            f"expected exactly one standalone tcp-proxy-service {kind}, found {len(matches)}"
        )
    labels = matches[0].get("metadata", {}).get("labels", {})
    if labels.get("firemud.dev/certificate-identity-mode") != "standalone":
        raise SystemExit(
            f"standalone TCP Proxy {kind} did not render the standalone identity mode label"
        )
labelled_objects = {
    (document.get("kind"), document.get("metadata", {}).get("name"))
    for document in documents
    if "firemud.dev/certificate-identity-mode"
    in document.get("metadata", {}).get("labels", {})
}
if labelled_objects != {("Deployment", "tcp-proxy-service"), ("Service", "tcp-proxy-service")}:
    raise SystemExit(
        "certificate identity mode label rendered on an unexpected standalone object"
    )
gateway_policies = [
    document
    for document in documents
    if document.get("kind") == "NetworkPolicy"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway-ingress"
]
if len(gateway_policies) != 1:
    raise SystemExit(
        f"expected exactly one standalone spring-cloud-gateway-ingress policy, found {len(gateway_policies)}"
    )
controller_rules = [
    rule
    for rule in gateway_policies[0]["spec"].get("ingress", [])
    if rule.get("from") == [{
        "namespaceSelector": {
            "matchLabels": {"kubernetes.io/metadata.name": "firemud-system"}
        },
        "podSelector": {
            "matchLabels": {
                "app.kubernetes.io/name": "hosted-environment-identity-controller",
                "app.kubernetes.io/component": "controller",
            }
        },
    }]
]
if controller_rules:
    raise SystemExit(
        "standalone certificate identity mode unexpectedly admitted the hosted controller to Gateway ingress"
    )
PY

helm template dev-demo "$CHART_DIR" \
  --namespace dev \
  -f "$TMP_DIR/rendered-dev-demo-values.yaml" \
  >"$TMP_DIR/dev-demo-rendered.yaml"
if ! grep -A1 'name: FIREMUD_GATEWAY_TCP_PROXY_TRUST_ENVIRONMENT' "$TMP_DIR/dev-demo-rendered.yaml" \
    | grep -Eq 'value: "?dev-demo-cluster"?'; then
  echo "dev-demo Helm render did not retain dev-demo-cluster trust environment" >&2
  exit 1
fi
python3 - <<'PY' "$TMP_DIR/dev-demo-rendered.yaml"
import pathlib
import sys

import yaml

documents = [
    document
    for document in yaml.safe_load_all(pathlib.Path(sys.argv[1]).read_text())
    if isinstance(document, dict)
]
proxy_deployments = [
    document
    for document in documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
]
if len(proxy_deployments) != 1:
    raise SystemExit(
        f"expected exactly one dev-demo tcp-proxy-service Deployment, found {len(proxy_deployments)}"
    )
if proxy_deployments[0]["spec"].get("strategy") != {"type": "Recreate"}:
    raise SystemExit("dev-demo TCP Proxy Deployment must use the Recreate strategy")
PY

for unsafe_case in \
  "previewStack.services[${proxy_index}].ports[0].nodePort=32042|tcp-proxy-service nodePort requires serviceType NodePort or LoadBalancer" \
  "previewStack.telnetTls.enabled=false previewStack.services[${proxy_index}].serviceType=NodePort|tcp-proxy-service must remain ClusterIP while Telnet TLS is disabled"; do
  unsafe_override=${unsafe_case%%|*}
  expected_unsafe_error=${unsafe_case#*|}
  read -r -a unsafe_args <<<"$unsafe_override"
  set_args=()
  for arg in "${unsafe_args[@]}"; do
    set_args+=(--set "$arg")
  done
  if helm template unsafe-pr-42 "$CHART_DIR" \
    --namespace pr-42 \
    -f "$TMP_DIR/private-values.yaml" \
    "${set_args[@]}" \
    >"$TMP_DIR/unsafe-rendered.yaml" 2>"$TMP_DIR/unsafe-error"; then
    echo "unsafe hosted TCP Proxy override unexpectedly rendered: $unsafe_override" >&2
    exit 1
  fi
  if ! grep -Fq "$expected_unsafe_error" "$TMP_DIR/unsafe-error"; then
    echo "unsafe hosted TCP Proxy override failed for an unexpected reason: $unsafe_override" >&2
    cat "$TMP_DIR/unsafe-error" >&2
    exit 1
  fi
done

for invalid_trust_environment in '' unsupported-environment; do
  if helm template invalid-trust-environment-pr-42 "$CHART_DIR" \
    --namespace pr-42 \
    -f "$TMP_DIR/values.yaml" \
    --set "previewStack.gatewayWsTls.trustEnvironment=${invalid_trust_environment}" \
    >"$TMP_DIR/invalid-trust-environment.yaml" 2>"$TMP_DIR/invalid-trust-environment-error"; then
    echo "invalid Gateway trust environment unexpectedly rendered: ${invalid_trust_environment:-empty}" >&2
    exit 1
  fi
  if ! grep -q 'previewStack.gatewayWsTls.trustEnvironment must be' "$TMP_DIR/invalid-trust-environment-error"; then
    echo "invalid Gateway trust environment failed for an unexpected reason: ${invalid_trust_environment:-empty}" >&2
    cat "$TMP_DIR/invalid-trust-environment-error" >&2
    exit 1
  fi
done

if helm template invalid-certificate-identity-mode-pr-42 "$CHART_DIR" \
  --namespace pr-42 \
  -f "$TMP_DIR/values.yaml" \
  --set previewStack.certificateIdentity.mode=unsupported-mode \
  >"$TMP_DIR/invalid-certificate-identity-mode.yaml" \
  2>"$TMP_DIR/invalid-certificate-identity-mode-error"; then
  echo "unsupported certificate identity mode unexpectedly rendered" >&2
  exit 1
fi
if ! grep -q 'previewStack.certificateIdentity.mode must be standalone or hosted-controller' \
    "$TMP_DIR/invalid-certificate-identity-mode-error"; then
  echo "unsupported certificate identity mode failed for an unexpected reason" >&2
  cat "$TMP_DIR/invalid-certificate-identity-mode-error" >&2
  exit 1
fi

for mismatch in \
  'preview.prNumber=0 previewStack.gatewayWsTls.trustEnvironment=pr-preview' \
  'preview.prNumber=42 previewStack.gatewayWsTls.trustEnvironment=dev-demo-cluster' \
  'preview.prNumber='; do
  read -r -a mismatch_args <<<"$mismatch"
  set_args=()
  for arg in "${mismatch_args[@]}"; do
    set_args+=(--set "$arg")
  done
  if helm template mismatched-trust-environment-pr-42 "$CHART_DIR" \
    --namespace pr-42 \
    -f "$TMP_DIR/values.yaml" \
    "${set_args[@]}" \
    >"$TMP_DIR/mismatched-trust-environment.yaml" 2>"$TMP_DIR/mismatched-trust-environment-error"; then
    echo "mismatched Gateway trust environment unexpectedly rendered: $mismatch" >&2
    exit 1
  fi
  if ! grep -Eq 'preview\.prNumber|previewStack\.gatewayWsTls\.trustEnvironment' \
      "$TMP_DIR/mismatched-trust-environment-error"; then
    echo "mismatched Gateway trust environment failed for an unexpected reason: $mismatch" >&2
    cat "$TMP_DIR/mismatched-trust-environment-error" >&2
    exit 1
  fi
done

helm template disabled-pr-42 "$CHART_DIR" \
  --namespace pr-42 \
  -f "$TMP_DIR/values.yaml" \
  --set previewStack.enabled=true \
  --set previewStack.gatewayWsTls.enabled=false \
  --set "previewStack.services[${gateway_index}].mountGatewayWsServerTls=false" \
  --set "previewStack.services[${proxy_index}].mountGatewayWsClientTls=false" \
  >"$TMP_DIR/disabled-bridge.yaml"
python3 - <<'PY' "$TMP_DIR/disabled-bridge.yaml"
import pathlib
import sys

import yaml

documents = [
    document
    for document in yaml.safe_load_all(pathlib.Path(sys.argv[1]).read_text())
    if isinstance(document, dict)
]

for deployment_name in ("spring-cloud-gateway", "tcp-proxy-service"):
    matches = [
        document
        for document in documents
        if document.get("kind") == "Deployment"
        and document.get("metadata", {}).get("name") == deployment_name
    ]
    if len(matches) != 1:
        raise SystemExit(
            f"disabled hosted test values rendered {len(matches)} {deployment_name} Deployments"
        )
    containers = matches[0]["spec"]["template"]["spec"]["containers"]
    container = next(item for item in containers if item["name"] == deployment_name)
    env_names = {item["name"] for item in container.get("env", [])}
    bridge_env_names = {
        name
        for name in env_names
        if name.startswith("FIREMUD_GATEWAY_TCP_PROXY_TLS_")
        or name.startswith("FIREMUD_GATEWAY_WS_")
        or name == "GATEWAY_WS_URL"
    }
    if bridge_env_names:
        raise SystemExit(
            f"disabled hosted {deployment_name} workload rendered Gateway bridge env: "
            f"{sorted(bridge_env_names)}"
        )

if any(
    document.get("kind") == "Service"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway-mtls"
    for document in documents
):
    raise SystemExit("disabled hosted test values unexpectedly rendered the Gateway mTLS Service")
if any(
    document.get("kind") == "NetworkPolicy"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway-egress"
    for document in documents
):
    raise SystemExit(
        "disabled hosted test values unexpectedly rendered the Gateway egress policy"
    )
PY

for collision in \
  "tcp-proxy-service|TCP_PROXY_TLS_ENABLED|Telnet TLS" \
  "spring-cloud-gateway|FIREMUD_GATEWAY_TCP_PROXY_TLS_PORT|Gateway WebSocket server TLS" \
  "tcp-proxy-service|GATEWAY_WS_URL|Gateway WebSocket client TLS"; do
  IFS='|' read -r collision_service collision_key collision_surface <<<"$collision"
  COLLISION_VALUES="$TMP_DIR/extra-env-${collision_key}.yaml"
  python3 - "$TMP_DIR/values.yaml" "$COLLISION_VALUES" "$collision_service" "$collision_key" <<'PY'
import sys
from pathlib import Path

import yaml

source_path, output_path = map(Path, sys.argv[1:3])
service_name = sys.argv[3]
key = sys.argv[4]
values = yaml.safe_load(source_path.read_text(encoding="utf-8"))
service = next(
    service for service in values["previewStack"]["services"] if service["name"] == service_name
)
service.setdefault("extraEnv", {})[key] = "collision"
output_path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
  if helm template pr-42 "$CHART_DIR" \
    -f "$COLLISION_VALUES" \
    --show-only templates/apps.yaml \
    --namespace pr-42 >/dev/null 2>"$TMP_DIR/extra-env-${collision_key}.err"; then
    echo "apps template rendered with a managed $collision_key extraEnv collision" >&2
    exit 1
  fi
  if ! grep -Fq "extraEnv key $collision_key collides with the managed $collision_surface environment" \
    "$TMP_DIR/extra-env-${collision_key}.err"; then
    echo "apps template did not diagnose the managed $collision_key extraEnv collision" >&2
    sed -n '1,20p' "$TMP_DIR/extra-env-${collision_key}.err" >&2
    exit 1
  fi
done

FOREIGN_TLS_MOUNTS_VALUES="$TMP_DIR/foreign-tls-mounts-values.yaml"
FOREIGN_TLS_MOUNTS_RENDERED="$TMP_DIR/foreign-tls-mounts-rendered.yaml"
python3 - "$TMP_DIR/values.yaml" "$FOREIGN_TLS_MOUNTS_VALUES" <<'PY'
import sys
from pathlib import Path

import yaml

source_path, output_path = map(Path, sys.argv[1:])
values = yaml.safe_load(source_path.read_text(encoding="utf-8"))
for service in values["previewStack"]["services"]:
    service["mountTelnetTls"] = True
    service["mountGatewayWsServerTls"] = True
    service["mountGatewayWsClientTls"] = True
output_path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
helm template pr-42 "$CHART_DIR" \
  -f "$FOREIGN_TLS_MOUNTS_VALUES" \
  --namespace pr-42 \
  --show-only templates/apps.yaml \
  >"$FOREIGN_TLS_MOUNTS_RENDERED"
python3 - "$FOREIGN_TLS_MOUNTS_RENDERED" <<'PY'
import sys
from pathlib import Path

import yaml

expected_mounts = {
    "tcp-proxy-service": {"telnet-tls", "gateway-ws-client-tls"},
    "spring-cloud-gateway": {"gateway-ws-server-tls"},
}
documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
for deployment in (document for document in documents if document.get("kind") == "Deployment"):
    service = deployment["metadata"]["name"]
    actual = {
        mount["name"]
        for mount in deployment["spec"]["template"]["spec"]["containers"][0].get("volumeMounts", [])
        if mount["name"] in {"telnet-tls", "gateway-ws-server-tls", "gateway-ws-client-tls"}
    }
    expected = expected_mounts.get(service, set())
    if actual != expected:
        raise SystemExit(
            f"{service} rendered role-specific TLS mounts {actual!r}; expected {expected!r}"
        )
PY

for gateway_listener_collision in health http grpc; do
  COLLISION_VALUES="$TMP_DIR/gateway-ws-tls-$gateway_listener_collision-collision-values.yaml"
  python3 - "$TMP_DIR/values.yaml" "$COLLISION_VALUES" "$gateway_listener_collision" <<'PY'
import sys
from pathlib import Path

import yaml

source_path, output_path = map(Path, sys.argv[1:3])
collision = sys.argv[3]
values = yaml.safe_load(source_path.read_text(encoding="utf-8"))
gateway = next(
    service
    for service in values["previewStack"]["services"]
    if service["name"] == "spring-cloud-gateway"
)
if collision == "health":
    gateway["healthPort"] = 8443
elif collision == "http":
    next(port for port in gateway["ports"] if str(port["port"]) == "80")[
        "targetPort"
    ] = 8443
else:
    next(port for port in gateway["ports"] if str(port["port"]) == "6565")[
        "targetPort"
    ] = 8443
output_path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
  if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
    -f "$COLLISION_VALUES" \
    --show-only templates/apps.yaml \
    --namespace pr-123 >/dev/null 2>"$TMP_DIR/gateway-ws-tls-$gateway_listener_collision-collision.err"; then
    echo "Gateway WebSocket TLS rendered with a managed $gateway_listener_collision listener collision" >&2
    exit 1
  fi
  if [[ "$gateway_listener_collision" == "grpc" ]]; then
    EXPECTED_COLLISION_FRAGMENT="collides with the Gateway managed gRPC listener service port 6565 targetPort 8443"
  else
    EXPECTED_COLLISION_FRAGMENT="collides with the Gateway managed health/HTTP listener"
  fi
  if ! grep -Fq "$EXPECTED_COLLISION_FRAGMENT" \
    "$TMP_DIR/gateway-ws-tls-$gateway_listener_collision-collision.err"; then
    echo "chart did not diagnose the Gateway WebSocket TLS $gateway_listener_collision listener collision" >&2
    sed -n '1,20p' "$TMP_DIR/gateway-ws-tls-$gateway_listener_collision-collision.err" >&2
    exit 1
  fi
done

for gateway_ws_tls_enabled in false true; do
  for invalid_gateway_ports in missing duplicate; do
  INVALID_GATEWAY_PORTS_VALUES="$TMP_DIR/plaintext-gateway-$invalid_gateway_ports-values.yaml"
  python3 - "$TMP_DIR/values.yaml" "$INVALID_GATEWAY_PORTS_VALUES" "$invalid_gateway_ports" <<'PY'
import sys
from pathlib import Path

import yaml

source_path, output_path = map(Path, sys.argv[1:3])
mutation = sys.argv[3]
values = yaml.safe_load(source_path.read_text(encoding="utf-8"))
gateway = next(
    service
    for service in values["previewStack"]["services"]
    if service["name"] == "spring-cloud-gateway"
)
if mutation == "missing":
    gateway["ports"] = [
        port for port in gateway["ports"] if str(port.get("port")) != "80"
    ]
else:
    gateway["ports"].append({"port": 80, "targetPort": 8282})
output_path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
  if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
    -f "$INVALID_GATEWAY_PORTS_VALUES" \
    --set previewStack.gatewayWsTls.enabled="$gateway_ws_tls_enabled" \
    --show-only templates/network-policies.yaml \
    --namespace pr-123 >/dev/null 2>"$TMP_DIR/gateway-$gateway_ws_tls_enabled-$invalid_gateway_ports.err"; then
    echo "Gateway WebSocket TLS=$gateway_ws_tls_enabled rendered with $invalid_gateway_ports port-80 Gateway service configuration" >&2
    exit 1
  fi
  if ! grep -Fq \
    "previewStack.services.spring-cloud-gateway must declare exactly one port: 80 for Gateway HTTP ingress" \
    "$TMP_DIR/gateway-$gateway_ws_tls_enabled-$invalid_gateway_ports.err"; then
    echo "chart did not reject $invalid_gateway_ports port-80 Gateway service configuration" >&2
    sed -n '1,20p' "$TMP_DIR/gateway-$gateway_ws_tls_enabled-$invalid_gateway_ports.err" >&2
    exit 1
    fi
  done
done

MISSING_GATEWAY_TARGET_PORT_VALUES="$TMP_DIR/plaintext-gateway-missing-target-port-values.yaml"
python3 - "$TMP_DIR/values.yaml" "$MISSING_GATEWAY_TARGET_PORT_VALUES" <<'PY'
import sys
from pathlib import Path

import yaml

source_path, output_path = map(Path, sys.argv[1:])
values = yaml.safe_load(source_path.read_text(encoding="utf-8"))
gateway = next(
    service
    for service in values["previewStack"]["services"]
    if service["name"] == "spring-cloud-gateway"
)
gateway_port = next(port for port in gateway["ports"] if str(port["port"]) == "80")
gateway_port.pop("targetPort", None)
output_path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$MISSING_GATEWAY_TARGET_PORT_VALUES" \
  --set previewStack.gatewayWsTls.enabled=false \
  --show-only templates/network-policies.yaml \
  --namespace pr-123 >/dev/null 2>"$TMP_DIR/plaintext-gateway-missing-target-port.err"; then
  echo "disabled Gateway TLS rendered without the Gateway service targetPort" >&2
  exit 1
fi
if ! grep -Fq \
  "previewStack.services.spring-cloud-gateway port: 80 must declare a targetPort for Gateway HTTP ingress" \
  "$TMP_DIR/plaintext-gateway-missing-target-port.err"; then
  echo "chart did not reject a missing Gateway service targetPort" >&2
  sed -n '1,20p' "$TMP_DIR/plaintext-gateway-missing-target-port.err" >&2
  exit 1
fi
if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$MISSING_GATEWAY_TARGET_PORT_VALUES" \
  --set previewStack.gatewayWsTls.enabled=true \
  --show-only templates/network-policies.yaml \
  --namespace pr-123 >/dev/null 2>"$TMP_DIR/tls-gateway-missing-target-port.err"; then
  echo "enabled Gateway TLS rendered without the Gateway service targetPort" >&2
  exit 1
fi
if ! grep -Fq \
  "previewStack.services.spring-cloud-gateway port: 80 must declare a targetPort for Gateway HTTP ingress" \
  "$TMP_DIR/tls-gateway-missing-target-port.err"; then
  echo "chart did not reject a missing Gateway service targetPort with TLS enabled" >&2
  sed -n '1,20p' "$TMP_DIR/tls-gateway-missing-target-port.err" >&2
  exit 1
fi

for gateway_ws_mount_service in spring-cloud-gateway tcp-proxy-service; do
  for gateway_ws_mount_state in missing false; do
    GATEWAY_WS_MOUNT_VALUES="$TMP_DIR/gateway-ws-$gateway_ws_mount_service-$gateway_ws_mount_state-values.yaml"
    python3 - "$TMP_DIR/values.yaml" "$GATEWAY_WS_MOUNT_VALUES" \
      "$gateway_ws_mount_service" "$gateway_ws_mount_state" <<'PY'
import sys
from pathlib import Path

import yaml

source_path, output_path, service_name, mount_state = sys.argv[1:]
values = yaml.safe_load(Path(source_path).read_text(encoding="utf-8"))
service = next(
    service
    for service in values["previewStack"]["services"]
    if service["name"] == service_name
)
mount_name = (
    "mountGatewayWsServerTls"
    if service_name == "spring-cloud-gateway"
    else "mountGatewayWsClientTls"
)
if mount_state == "missing":
    service.pop(mount_name, None)
else:
    service[mount_name] = False
Path(output_path).write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY

    if [[ "$gateway_ws_mount_service" == "spring-cloud-gateway" ]]; then
      GATEWAY_WS_MOUNT_ERROR="previewStack.services.spring-cloud-gateway.mountGatewayWsServerTls must be true when previewStack.gatewayWsTls.enabled is true"
    else
      GATEWAY_WS_MOUNT_ERROR="previewStack.services.tcp-proxy-service.mountGatewayWsClientTls must be true when previewStack.gatewayWsTls.enabled is true"
    fi
    GATEWAY_WS_MOUNT_ERROR_FILE="$TMP_DIR/gateway-ws-$gateway_ws_mount_service-$gateway_ws_mount_state.err"
    if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
      -f "$GATEWAY_WS_MOUNT_VALUES" \
      --show-only templates/apps.yaml \
      --namespace pr-123 >/dev/null 2>"$GATEWAY_WS_MOUNT_ERROR_FILE"; then
      echo "Gateway WebSocket TLS rendered with $gateway_ws_mount_service $gateway_ws_mount_state mount" >&2
      exit 1
    fi
    if ! grep -Fq "$GATEWAY_WS_MOUNT_ERROR" "$GATEWAY_WS_MOUNT_ERROR_FILE"; then
      echo "chart did not report the expected $gateway_ws_mount_service $gateway_ws_mount_state mount diagnostic" >&2
      sed -n '1,20p' "$GATEWAY_WS_MOUNT_ERROR_FILE" >&2
      exit 1
    fi
  done
done

ENABLED_POLICIES="$TMP_DIR/enabled-network-policies.yaml"
DISABLED_POLICIES="$TMP_DIR/disabled-network-policies.yaml"
helm template pr-42 "$CHART_DIR" \
  --namespace pr-42 \
  -f "$TMP_DIR/values.yaml" \
  --show-only templates/network-policies.yaml \
  >"$ENABLED_POLICIES"
helm template pr-42 "$CHART_DIR" \
  --namespace pr-42 \
  -f "$TMP_DIR/values.yaml" \
  --set previewStack.gatewayWsTls.enabled=false \
  --show-only templates/network-policies.yaml \
  >"$DISABLED_POLICIES"
python3 - "$ENABLED_POLICIES" "$DISABLED_POLICIES" <<'PY'
import sys
from pathlib import Path

import yaml


def policies(path):
    return {
        document["metadata"]["name"]: document
        for document in yaml.safe_load_all(Path(path).read_text(encoding="utf-8"))
        if isinstance(document, dict) and document.get("kind") == "NetworkPolicy"
    }


enabled = policies(sys.argv[1])
disabled = policies(sys.argv[2])
gateway_egress = enabled.get("spring-cloud-gateway-egress")
if gateway_egress is None:
    raise SystemExit("enabled Gateway render omitted the default-deny egress policy")
if gateway_egress["spec"].get("policyTypes") != ["Egress"]:
    raise SystemExit("enabled Gateway egress policy changed policyTypes")
if not gateway_egress["spec"].get("egress"):
    raise SystemExit("enabled Gateway egress policy lost canonical service routes")
if "spring-cloud-gateway-egress" in disabled:
    raise SystemExit("disabled Gateway render retained the TLS-only egress policy")

enabled_proxy = [
    rule for rule in enabled["tcp-proxy-service-egress"]["spec"].get("egress", [])
    if rule.get("to") == [{"podSelector": {"matchLabels": {"app": "spring-cloud-gateway"}}}]
]
if len(enabled_proxy) != 1 or enabled_proxy[0].get("ports") != [{"protocol": "TCP", "port": 8443}]:
    raise SystemExit(f"enabled Gateway TLS changed exact TCP Proxy egress: {enabled_proxy}")
gateway_ingress = enabled.get("spring-cloud-gateway-ingress")
if gateway_ingress is None:
    raise SystemExit("enabled Gateway render omitted the Gateway listener ingress policy")
listener_rules = [
    rule for rule in gateway_ingress["spec"].get("ingress", [])
    if rule.get("from") == [{"podSelector": {"matchLabels": {"app": "tcp-proxy-service"}}}]
]
if len(listener_rules) != 1 or listener_rules[0].get("ports") != [{"protocol": "TCP", "port": 8443}]:
    raise SystemExit(f"enabled Gateway listener ingress changed target port: {listener_rules}")
if "spring-cloud-gateway-ingress" in disabled:
    raise SystemExit("disabled Gateway render retained the TLS-only listener policy")
PY

HOSTED_CONTROLLER_PUBLIC_VALUES="$TMP_DIR/hosted-controller-public-values.yaml"
cp "$TMP_DIR/values.yaml" "$HOSTED_CONTROLLER_PUBLIC_VALUES"
python3 - "$HOSTED_CONTROLLER_PUBLIC_VALUES" <<'PY'
import sys
from pathlib import Path

import yaml

path = Path(sys.argv[1])
values = yaml.safe_load(path.read_text(encoding="utf-8"))
proxy = next(
    service
    for service in values["previewStack"]["services"]
    if service["name"] == "tcp-proxy-service"
)
proxy["serviceType"] = "NodePort"
# The chart overrides this fixture value with preview.telnetPort before rendering.
proxy["ports"][0]["nodePort"] = 30001
path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
helm template hosted-controller-public-pr-42 "$CHART_DIR" \
  --namespace pr-42 \
  -f "$HOSTED_CONTROLLER_PUBLIC_VALUES" \
  --set-string previewStack.telnetTls.secretName=pr-42-telnet-tls \
  >"$TMP_DIR/hosted-controller-public-rendered.yaml"
python3 - "$TMP_DIR/hosted-controller-public-rendered.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
proxy_service = next(
    document
    for document in documents
    if document.get("kind") == "Service"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
if proxy_service["spec"].get("type") != "NodePort":
    raise SystemExit("public hosted-controller TCP Proxy Service must use NodePort")
telnet_ports = [port for port in proxy_service["spec"].get("ports", []) if port.get("port") == 2323]
if len(telnet_ports) != 1 or telnet_ports[0].get("nodePort") != 32042:
    raise SystemExit("public hosted-controller Telnet NodePort must use the allocated preview port")
if proxy_service.get("metadata", {}).get("annotations", {}).get(
    "firemud.dev/allocated-telnet-port"
) != "32042":
    raise SystemExit("public hosted-controller Service lost its allocated-port annotation")
if proxy_service.get("metadata", {}).get("labels", {}).get(
    "firemud.dev/certificate-identity-mode"
) != "hosted-controller":
    raise SystemExit("public hosted-controller Service lost its identity-mode label")
PY

PUBLIC_VALUES="$TMP_DIR/public-values.yaml"
cp "$TMP_DIR/values.yaml" "$PUBLIC_VALUES"
python3 - "$PUBLIC_VALUES" <<'PY'
import sys
from pathlib import Path

import yaml

path = Path(sys.argv[1])
values = yaml.safe_load(path.read_text(encoding="utf-8"))
values["previewStack"]["certificateIdentity"]["mode"] = "standalone"
proxy = next(
    service
    for service in values["previewStack"]["services"]
    if service["name"] == "tcp-proxy-service"
)
proxy["serviceType"] = "NodePort"
proxy["ports"][0]["nodePort"] = 30001
path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
helm template public-pr-42 "$CHART_DIR" \
  --namespace pr-42 \
  -f "$PUBLIC_VALUES" \
  --set-string previewStack.telnetTls.secretName=pr-42-telnet-tls \
  >"$TMP_DIR/public-rendered.yaml"
python3 - "$TMP_DIR/public-rendered.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
proxy_services = [
    document
    for document in documents
    if document.get("kind") == "Service"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
]
if len(proxy_services) != 1:
    raise SystemExit(f"expected exactly one public TCP Proxy Service, found {len(proxy_services)}")
proxy_service = proxy_services[0]
if proxy_service["spec"].get("type") != "NodePort":
    raise SystemExit("standalone public TCP Proxy Service must use NodePort")
telnet_ports = [port for port in proxy_service["spec"].get("ports", []) if port.get("port") == 2323]
if len(telnet_ports) != 1 or telnet_ports[0].get("nodePort") != 30001:
    raise SystemExit("standalone public Telnet NodePort must preserve the configured service port")
labels = proxy_service.get("metadata", {}).get("labels", {})
if labels.get("firemud.dev/certificate-identity-mode") != "standalone":
    raise SystemExit("public TCP Proxy Service lost its standalone identity-mode label")
PY

helm template public-pr-42 "$CHART_DIR" \
  --namespace pr-42 \
  -f "$PUBLIC_VALUES" \
  --set-string previewStack.telnetTls.secretName=pr-42-telnet-tls \
  --show-only templates/gateway-ws-certificates.yaml \
  >"$TMP_DIR/public-gateway-ws-certificates.yaml"
python3 - "$TMP_DIR/public-gateway-ws-certificates.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
certificates = {
    document["metadata"]["name"]: document
    for document in documents
    if document.get("apiVersion") == "cert-manager.io/v1"
    and document.get("kind") == "Certificate"
}
expected_names = {"public-pr-42-gateway-internal-ws", "public-pr-42-tcp-proxy-bridge"}
if set(certificates) != expected_names:
    raise SystemExit(
        "standalone public mode must render exactly the Gateway bridge Certificates: "
        f"{set(certificates)!r}"
    )
server = certificates["public-pr-42-gateway-internal-ws"]["spec"]
if server.get("dnsNames") != ["spring-cloud-gateway-mtls.pr-42.svc.cluster.local"]:
    raise SystemExit("public Gateway server Certificate must use the namespace DNS SAN")
if server.get("usages") != ["digital signature", "key encipherment", "server auth"]:
    raise SystemExit("public Gateway server Certificate lost server-auth usage")
client = certificates["public-pr-42-tcp-proxy-bridge"]["spec"]
if client.get("uris") != ["spiffe://firemud/ns/pr-42/sa/tcp-proxy-service"]:
    raise SystemExit("public bridge client Certificate must use the TCP Proxy SPIFFE URI")
if client.get("usages") != ["digital signature", "key encipherment", "client auth"]:
    raise SystemExit("public bridge client Certificate lost client-auth usage")
PY

PUBLIC_GATEWAY_WS_OVERRIDE="$TMP_DIR/public-gateway-ws-certificates-override.yaml"
helm template public-pr-42 "$CHART_DIR" \
  --namespace pr-42 \
  -f "$PUBLIC_VALUES" \
  --set-string previewStack.telnetTls.secretName=pr-42-telnet-tls \
  --set-string previewStack.gatewayWsTls.clusterIssuer=custom-bridge-ca-issuer \
  --show-only templates/gateway-ws-certificates.yaml \
  >"$PUBLIC_GATEWAY_WS_OVERRIDE"
python3 - "$TMP_DIR/public-gateway-ws-certificates.yaml" "$PUBLIC_GATEWAY_WS_OVERRIDE" <<'PY'
import copy
import sys
from pathlib import Path

import yaml


def certificates(path):
    return {
        document["metadata"]["name"]: document
        for document in yaml.safe_load_all(Path(path).read_text(encoding="utf-8"))
        if isinstance(document, dict)
        and document.get("apiVersion") == "cert-manager.io/v1"
        and document.get("kind") == "Certificate"
    }


default = certificates(sys.argv[1])
override = certificates(sys.argv[2])
if set(default) != set(override):
    raise SystemExit("Gateway bridge issuer override changed the Certificate set")
for name, certificate in default.items():
    expected = copy.deepcopy(certificate)
    expected["spec"]["issuerRef"]["name"] = "custom-bridge-ca-issuer"
    if override[name] != expected:
        raise SystemExit(
            f"Gateway bridge issuer override changed Certificate {name} beyond issuerRef.name"
        )
PY

python3 - "$TMP_DIR/hosted-controller-public-rendered.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
if any(document.get("kind") == "Certificate" for document in documents):
    raise SystemExit(
        "public hosted-controller mode must not render a chart-owned Telnet Certificate"
    )
PY

python3 - "$TMP_DIR/public-rendered.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
certificates = [
    document
    for document in documents
    if document.get("apiVersion") == "cert-manager.io/v1"
    and document.get("kind") == "Certificate"
]
telnet_certificates = [
    certificate
    for certificate in certificates
    if certificate.get("metadata", {}).get("name") == "pr-42-telnet-tls"
]
if len(telnet_certificates) != 1:
    raise SystemExit("public Telnet TLS must render exactly one named Certificate")
if telnet_certificates[0]["spec"].get("privateKey") != {
    "algorithm": "RSA",
    "size": 2048,
    "encoding": "PKCS8",
    "rotationPolicy": "Always",
}:
    raise SystemExit("public Telnet TLS Certificate lost its private-key contract")
PY

echo "hosted Gateway WebSocket mTLS Helm contract passed"
