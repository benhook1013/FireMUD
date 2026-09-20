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

helm template pr-42 "$CHART_DIR" \
  --namespace pr-42 \
  -f "$TMP_DIR/values.yaml" \
  >"$TMP_DIR/rendered.yaml"

python3 - <<'PY' "$TMP_DIR/rendered.yaml"
import pathlib
import subprocess
import sys
import tempfile

import yaml

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

for unsafe_override in \
  "previewStack.services[${proxy_index}].serviceType=NodePort" \
  "previewStack.services[${proxy_index}].ports[0].nodePort=32042"; do
  if helm template unsafe-pr-42 "$CHART_DIR" \
    --namespace pr-42 \
    -f "$TMP_DIR/values.yaml" \
    --set "$unsafe_override" \
    >"$TMP_DIR/unsafe-rendered.yaml" 2>"$TMP_DIR/unsafe-error"; then
    echo "unsafe hosted TCP Proxy override unexpectedly rendered: $unsafe_override" >&2
    exit 1
  fi
  if ! grep -q 'tcp-proxy-service must' "$TMP_DIR/unsafe-error"; then
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

echo "hosted Gateway WebSocket mTLS Helm contract passed"
