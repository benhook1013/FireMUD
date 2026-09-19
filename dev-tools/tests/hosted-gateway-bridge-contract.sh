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
  dev-identity dev-demo dev.preview.firedevops.net test 32016
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
import sys

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

gateway_deployment = named("Deployment", "spring-cloud-gateway")
proxy_deployment = named("Deployment", "tcp-proxy-service")
gateway, gateway_env = env_map(gateway_deployment)
proxy, proxy_env = env_map(proxy_deployment)
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

def secret_volume(container, volume_name, secret_name):
    volume = next(item for item in container["volumes"] if item["name"] == volume_name)
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

for deployment, container, volume_name, mount_name, mount_path, secret_name in (
    (gateway_deployment, gateway_deployment["spec"]["template"]["spec"], "gateway-ws-server-tls", "gateway-ws-server-tls", "/gateway-ws-server-tls", "pr-42-gateway-internal-ws"),
    (proxy_deployment, proxy_deployment["spec"]["template"]["spec"], "gateway-ws-client-tls", "gateway-ws-client-tls", "/gateway-ws-client-tls", "pr-42-tcp-proxy-bridge"),
):
    pod_container = next(item for item in container["containers"] if item["name"] == deployment["metadata"]["name"])
    if {item["name"]: item for item in pod_container["volumeMounts"]}[mount_name] != {
        "name": mount_name, "mountPath": mount_path, "readOnly": True
    }:
        raise SystemExit(f"{deployment['metadata']['name']} bridge mount is not distinct/read-only")
    secret_volume(container, volume_name, secret_name)

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

gateway_policy = named("NetworkPolicy", "spring-cloud-gateway-ingress")["spec"]
if gateway_policy["podSelector"] != {"matchLabels": {"app": "spring-cloud-gateway"}}:
    raise SystemExit("Gateway ingress policy selected an unexpected workload")
gateway_proxy_rules = [
    rule for rule in gateway_policy["ingress"]
    if rule.get("ports") == [{"protocol": "TCP", "port": 8443}]
]
if gateway_proxy_rules != [{
    "from": [{"podSelector": {"matchLabels": {"app": "tcp-proxy-service"}}}],
    "ports": [{"protocol": "TCP", "port": 8443}],
}]:
    raise SystemExit("Gateway ingress policy did not restrict exactly the proxy to 8443")
if any(
    rule.get("from") == [{"podSelector": {}}]
    and rule.get("ports") == [{"protocol": "TCP", "port": 8080}]
    for rule in gateway_policy["ingress"]
):
    raise SystemExit("Gateway ingress policy broadly exposed port 8080 to every pod")
if any(rule.get("ports") == [{"protocol": "TCP", "port": 6565}] for rule in gateway_policy["ingress"]):
    raise SystemExit("Gateway ingress policy unexpectedly exposed the gRPC port")
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

helm template dev-demo "$CHART_DIR" \
  --namespace dev-identity \
  -f "$TMP_DIR/rendered-dev-demo-values.yaml" \
  >"$TMP_DIR/dev-demo-rendered.yaml"
if ! grep -A1 'name: FIREMUD_GATEWAY_TCP_PROXY_TRUST_ENVIRONMENT' "$TMP_DIR/dev-demo-rendered.yaml" \
    | grep -Eq 'value: "?dev-demo-cluster"?'; then
  echo "dev-demo Helm render did not retain dev-demo-cluster trust environment" >&2
  exit 1
fi

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
PY

echo "hosted Gateway WebSocket mTLS Helm contract passed"
