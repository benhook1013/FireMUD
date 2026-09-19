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

sed \
  -e 's/__PR_NUMBER__/42/g' \
  -e 's/__NAMESPACE__/pr-42/g' \
  -e 's/__RELEASE_NAME__/pr-42/g' \
  -e 's/__HOSTNAME__/pr-42.preview.example.test/g' \
  -e 's/__TELNET_PORT__/32042/g' \
  -e 's/__IMAGE_TAG__/test/g' \
  -e 's/__TLS_SECRET_NAME__/pr-42-tls/g' \
  -e 's/__JWT_SIGNING_KEY__/test-key/g' \
  -e 's/__TCP_PROXY_GATEWAY_BASE_URL_LINE__//g' \
  -e 's/__TCP_PROXY_ADDITIONAL_SERVICE_PORTS__//g' \
  "$CHART_DIR/values-hosted-shared.example.yaml" >"$TMP_DIR/values.yaml"

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

for unsafe_override in \
  'previewStack.services[9].serviceType=NodePort' \
  'previewStack.services[9].ports[0].nodePort=32042'; do
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

helm template standalone "$CHART_DIR" -f "$CHART_DIR/values.yaml" >"$TMP_DIR/standalone.yaml"
if grep -q 'spring-cloud-gateway-mtls\|FIREMUD_GATEWAY_TCP_PROXY_TLS_ENABLED' "$TMP_DIR/standalone.yaml"; then
  echo "standalone defaults unexpectedly rendered the hosted Gateway bridge" >&2
  exit 1
fi

echo "hosted Gateway WebSocket mTLS Helm contract passed"
