#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

RENDERED="$TMP_DIR/rendered.yaml"
render_test_values() {
  local output_path="$1"
  local pr_number="$2"
  local namespace="$3"
  local release_name="$4"
  local hostname="$5"
  local image_tag="$6"
  local telnet_port="$7"

  python3 - \
    "$ROOT_DIR/k8s/helm/firemud/values-hosted-shared.example.yaml" \
    "$output_path" \
    "$pr_number" \
    "$namespace" \
    "$release_name" \
    "$hostname" \
    "$image_tag" \
    "$telnet_port" <<'PY'
import json
import sys
from pathlib import Path

template_path = Path(sys.argv[1])
output_path = Path(sys.argv[2])
pr_number, namespace, release_name, hostname, image_tag, telnet_port = sys.argv[3:]
text = template_path.read_text(encoding="utf-8")
replacements = {
    "__PR_NUMBER__": pr_number,
    "__NAMESPACE__": namespace,
    "__RELEASE_NAME__": release_name,
    "__HOSTNAME__": hostname,
    "__TELNET_PORT__": telnet_port,
    "__IMAGE_TAG__": image_tag,
    "__TLS_SECRET_NAME__": f"{release_name}-tls",
    "__TELNET_TLS_SECRET_NAME__": f"{release_name}-telnet-tls",
    "__JWT_SIGNING_KEY__": "a" * 64,
    "__JWKS_JSON__": json.dumps({"keys": []}, separators=(",", ":")),
    "__SEED_GAME_NAME__": "Bridge Contract Game",
    "__SEED_GAME_DESCRIPTION__": "Bridge contract fixture game.",
    "__SEED_VERSION_NOTES__": "Bridge contract fixture version",
    "__SEED_TEMPLATE_NAME__": "Bridge Contract Template",
    "__SEED_TEMPLATE_DESCRIPTION__": "Bridge contract fixture template.",
    "__SEED_WORKFLOW_ID__": "bridge-contract-seed",
    "__SEED_MANIFEST_HASH__": "bridge-contract-manifest",
    "__SEED_GENERATION_CONFIG_REVISION__": "genrev:bridge-contract",
}
for target, replacement in replacements.items():
    if target not in text:
        raise SystemExit(f"bridge contract fixture token is missing: {target}")

required_markers = (
    "# __TCP_PROXY_GATEWAY_BASE_URL_LINE__",
    "# __TCP_PROXY_ADDITIONAL_SERVICE_PORTS__",
)
missing_markers = [marker for marker in required_markers if marker not in text]
if missing_markers:
    raise SystemExit(
        "bridge contract fixture replacement markers are missing before any replacement: "
        + ", ".join(missing_markers)
    )

for target, replacement in replacements.items():
    text = text.replace(target, replacement)
for marker in required_markers:
    text = text.replace("        " + marker, "")
output_path.write_text(text, encoding="utf-8")
PY
}

render_test_values \
  "$TMP_DIR/preview-values.yaml" \
  123 pr-123 pr-123 preview-123.example.test image-tag 32123
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/preview-values.yaml" \
  --namespace pr-123 \
  >"$RENDERED"

python3 - "$RENDERED" <<'PY'
import sys
from pathlib import Path

import yaml

documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
gateway = next(
    document
    for document in documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway"
)
container = gateway["spec"]["template"]["spec"]["containers"][0]
expected_env = [
    {"name": "SPRING_PROFILES_ACTIVE", "value": "prod"},
    {"name": "SPRING_FLYWAY_TABLE", "value": "flyway_schema_history_gateway"},
    {"name": "SERVICE_SCHEMA", "value": "gateway"},
    {"name": "FIREMUD_GRPC_CERT_CHAIN_PATH", "value": "/tls/client.crt"},
    {"name": "FIREMUD_GRPC_PRIVATE_KEY_PATH", "value": "/tls/client.key"},
    {"name": "FIREMUD_GRPC_CA_CERT_PATH", "value": "/tls/ca.crt"},
    {"name": "FIREMUD_GATEWAY_TCP_PROXY_TLS_ENABLED", "value": "true"},
    {"name": "FIREMUD_GATEWAY_TCP_PROXY_TLS_BIND_ADDRESS", "value": "0.0.0.0"},
    {"name": "FIREMUD_GATEWAY_TCP_PROXY_TLS_PORT", "value": "8443"},
    {
        "name": "FIREMUD_GATEWAY_TCP_PROXY_TLS_CERT_CHAIN_PATH",
        "value": "/gateway-ws-server-tls/tls.crt",
    },
    {
        "name": "FIREMUD_GATEWAY_TCP_PROXY_TLS_PRIVATE_KEY_PATH",
        "value": "/gateway-ws-server-tls/tls.key",
    },
    {
        "name": "FIREMUD_GATEWAY_TCP_PROXY_TLS_CLIENT_CA_PATH",
        "value": "/gateway-ws-server-tls/ca.crt",
    },
    {"name": "FIREMUD_GATEWAY_TCP_PROXY_TRUST_ENVIRONMENT", "value": "pr-preview"},
    {"name": "FIREMUD_GATEWAY_TCP_PROXY_TRUST_PROFILE", "value": "production_uri"},
    {
        "name": "FIREMUD_GATEWAY_TCP_PROXY_TRUST_URI_SAN",
        "value": "spiffe://firemud/ns/pr-123/sa/tcp-proxy-service",
    },
]
if container.get("env") != expected_env:
    raise SystemExit(
        f"Gateway rendered an unexpected exact env contract: {container.get('env')}"
    )
if container.get("envFrom") != [
    {"configMapRef": {"name": "firemud-config"}},
    {"secretRef": {"name": "firemud-secret"}},
]:
    raise SystemExit(
        f"Gateway rendered an unexpected exact envFrom contract: {container.get('envFrom')}"
    )
PY

for collision in \
  "tcp-proxy-service|TCP_PROXY_TLS_ENABLED|Telnet TLS" \
  "spring-cloud-gateway|FIREMUD_GATEWAY_TCP_PROXY_TLS_PORT|Gateway WebSocket server TLS" \
  "tcp-proxy-service|GATEWAY_WS_URL|Gateway WebSocket client TLS"; do
  IFS='|' read -r collision_service collision_key collision_surface <<<"$collision"
  COLLISION_VALUES="$TMP_DIR/extra-env-${collision_key}.yaml"
  python3 - "$TMP_DIR/preview-values.yaml" "$COLLISION_VALUES" "$collision_service" "$collision_key" <<'PY'
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
  if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
    -f "$COLLISION_VALUES" \
    --show-only templates/apps.yaml \
    --namespace pr-123 >/dev/null 2>"$TMP_DIR/extra-env-${collision_key}.err"; then
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
python3 - "$TMP_DIR/preview-values.yaml" "$FOREIGN_TLS_MOUNTS_VALUES" <<'PY'
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
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$FOREIGN_TLS_MOUNTS_VALUES" \
  --namespace pr-123 \
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
for deployment in (
    document for document in documents if document.get("kind") == "Deployment"
):
    service = deployment["metadata"]["name"]
    actual = {
        mount["name"]
        for mount in deployment["spec"]["template"]["spec"]["containers"][0].get(
            "volumeMounts", []
        )
        if mount["name"] in {
            "telnet-tls",
            "gateway-ws-server-tls",
            "gateway-ws-client-tls",
        }
    }
    expected = expected_mounts.get(service, set())
    if actual != expected:
        raise SystemExit(
            f"{service} rendered role-specific TLS mounts {actual!r}; expected {expected!r}"
        )
PY

python3 - "$ROOT_DIR/k8s/base/gameplay-bridge-network-policy.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
policy = next(
    document
    for document in documents
    if document.get("kind") == "NetworkPolicy"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service-egress"
)
elasticsearch_rules = [
    rule
    for rule in policy["spec"]["egress"]
    if rule.get("to")
    == [{"podSelector": {"matchLabels": {"app": "elasticsearch"}}}]
]
expected_rule = {
    "to": [{"podSelector": {"matchLabels": {"app": "elasticsearch"}}}],
    "ports": [{"protocol": "TCP", "port": 9200}],
}
if elasticsearch_rules != [expected_rule]:
    raise SystemExit(
        "Kustomize TCP Proxy policy must contain exactly one narrow Elasticsearch TCP/9200 rule: "
        f"{elasticsearch_rules}"
    )

gateway_policy = next(
    document
    for document in documents
    if document.get("kind") == "NetworkPolicy"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway-ingress"
)
public_gateway_rules = [
    rule
    for rule in gateway_policy["spec"]["ingress"]
    if rule.get("from")
    == [
        {"ipBlock": {"cidr": "0.0.0.0/0"}},
        {"ipBlock": {"cidr": "::/0"}},
    ]
]
expected_public_gateway_rule = {
    "from": [
        {"ipBlock": {"cidr": "0.0.0.0/0"}},
        {"ipBlock": {"cidr": "::/0"}},
    ],
    "ports": [{"protocol": "TCP", "port": 8080}],
}
if public_gateway_rules != [expected_public_gateway_rule]:
    raise SystemExit(
        "Kustomize Gateway policy must contain exactly one dual-stack public TCP/8080 rule: "
        f"{public_gateway_rules}"
    )
ip_block_rules = [
    rule
    for rule in gateway_policy["spec"]["ingress"]
    if any("ipBlock" in peer for peer in rule.get("from", []))
]
if any(
    rule.get("ports") != [{"protocol": "TCP", "port": 8080}]
    for rule in ip_block_rules
):
    raise SystemExit(
        "Kustomize Gateway public ipBlock peers must be restricted to TCP/8080: "
        f"{ip_block_rules}"
    )
PY

FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  python3 "$ROOT_DIR/dev-tools/deploy/preflight.py" hosted-bridge \
    "$RENDERED" pr-123 pr-123 >"$TMP_DIR/no-flag-preflight.json"
FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  python3 "$ROOT_DIR/dev-tools/deploy/preflight.py" hosted-bridge \
    "$RENDERED" pr-123 pr-123 \
    --expected-hosted-telnet-node-port 32123 >"$TMP_DIR/preflight.json"
if FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  python3 "$ROOT_DIR/dev-tools/deploy/preflight.py" hosted-bridge \
    "$RENDERED" pr-123 pr-123 \
    --expected-hosted-telnet-node-port 32124 >"$TMP_DIR/mismatched-preflight.json"; then
  echo "hosted bridge preflight accepted a mismatched expected Telnet NodePort" >&2
  exit 1
fi
if ! grep -Fq 'Telnet nodePort must equal 32124' "$TMP_DIR/mismatched-preflight.json"; then
  echo "hosted bridge preflight did not diagnose the mismatched expected Telnet NodePort" >&2
  exit 1
fi

EXTRA_NODEPORT_RENDERED="$TMP_DIR/extra-nodeport-rendered.yaml"
python3 - "$RENDERED" "$EXTRA_NODEPORT_RENDERED" <<'PY'
import sys
from pathlib import Path

import yaml

source, destination = map(Path, sys.argv[1:])
documents = list(yaml.safe_load_all(source.read_text(encoding="utf-8")))
service = next(
    document
    for document in documents
    if isinstance(document, dict)
    and document.get("kind") == "Service"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
service["spec"]["ports"].append(
    {
        "name": "unexpected",
        "port": 9999,
        "targetPort": 9999,
        "protocol": "TCP",
        "nodePort": 32124,
    }
)
destination.write_text(yaml.safe_dump_all(documents, sort_keys=False), encoding="utf-8")
PY
if FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  python3 "$ROOT_DIR/dev-tools/deploy/preflight.py" hosted-bridge \
    "$EXTRA_NODEPORT_RENDERED" pr-123 pr-123 \
    --expected-hosted-telnet-node-port 32123 >"$TMP_DIR/extra-nodeport-preflight.json"; then
  echo "hosted bridge preflight accepted an extra explicit NodePort" >&2
  exit 1
fi
if ! grep -Fq 'must not declare any other explicit nodePorts' "$TMP_DIR/extra-nodeport-preflight.json"; then
  echo "hosted bridge preflight did not diagnose an extra explicit NodePort" >&2
  exit 1
fi

DISABLED_GATEWAY_WS_TLS_RENDERED="$TMP_DIR/disabled-gateway-ws-tls.yaml"
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/preview-values.yaml" \
  --set previewStack.gatewayWsTls.enabled=false \
  --show-only templates/network-policies.yaml \
  --namespace pr-123 >"$DISABLED_GATEWAY_WS_TLS_RENDERED"
python3 - "$RENDERED" "$DISABLED_GATEWAY_WS_TLS_RENDERED" <<'PY'
import sys
from pathlib import Path

import yaml


def policies(path):
    return {
        document["metadata"]["name"]: document
        for document in yaml.safe_load_all(Path(path).read_text(encoding="utf-8"))
        if isinstance(document, dict) and document.get("kind") == "NetworkPolicy"
    }


def proxy_egress_destinations(policy):
    destinations = set()
    for rule in policy["spec"]["egress"]:
        peer = rule["to"][0]
        pod_labels = peer.get("podSelector", {}).get("matchLabels", {})
        if pod_labels.get("k8s-app") == "kube-dns":
            destinations.add(("kube-dns", tuple(port["port"] for port in rule["ports"])))
        elif "app" in pod_labels:
            destinations.add(
                (pod_labels["app"], tuple(port["port"] for port in rule["ports"]))
            )
    return destinations


enabled = policies(sys.argv[1])
disabled = policies(sys.argv[2])
controller_ingress = enabled.get("account-service-controller-ingress")
expected_controller_ingress = {
    "from": [
        {
            "namespaceSelector": {
                "matchLabels": {"kubernetes.io/metadata.name": "firemud-system"}
            },
            "podSelector": {
                "matchLabels": {
                    "app.kubernetes.io/name": "hosted-environment-identity-controller",
                    "app.kubernetes.io/component": "controller",
                }
            },
        }
    ],
    "ports": [{"protocol": "TCP", "port": 6565}],
}
if controller_ingress is None:
    raise SystemExit(
        "hosted Helm omitted the Account ingress policy for the identity controller"
    )
if controller_ingress.get("spec", {}).get("podSelector") != {
    "matchLabels": {"app": "account-service"}
}:
    raise SystemExit(
        "identity controller ingress policy must select only the Account workload"
    )
if controller_ingress.get("spec", {}).get("policyTypes") != ["Ingress"]:
    raise SystemExit(
        "identity controller ingress policy must not change Account egress policy"
    )
if controller_ingress["spec"].get("ingress") != [expected_controller_ingress]:
    raise SystemExit(
        "identity controller ingress policy must allow only firemud-system/controller TCP/6565"
    )
expected_gateway_egress = {
    "podSelector": {"matchLabels": {"app": "spring-cloud-gateway"}},
    "policyTypes": ["Egress"],
    "egress": [
        {
            "to": [
                {
                    "namespaceSelector": {
                        "matchLabels": {
                            "kubernetes.io/metadata.name": "kube-system"
                        }
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
            "to": [
                {
                    "podSelector": {
                        "matchExpressions": [
                            {
                                "key": "app",
                                "operator": "In",
                                "values": [
                                    "account-service",
                                    "game-design-service",
                                    "game-session-service",
                                    "logging-admin-service",
                                    "social-groups-service",
                                ],
                            }
                        ]
                    }
                }
            ],
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
}
for mode, rendered in (("enabled", enabled), ("disabled", disabled)):
    gateway_egress = rendered.get("spring-cloud-gateway-egress")
    if gateway_egress is None:
        raise SystemExit(f"{mode} render omitted the Gateway default-deny egress policy")
    if gateway_egress.get("spec") != expected_gateway_egress:
        raise SystemExit(
            f"{mode} render widened or changed the Gateway egress contract: "
         f"{gateway_egress.get('spec')}"
        )
required_base_egress = {
    ("kube-dns", (53, 53)),
    ("game-session-service", (6565,)),
    ("otel-collector", (4317,)),
}
enabled_destinations = proxy_egress_destinations(
    enabled["tcp-proxy-service-egress"]
)
if not required_base_egress.issubset(enabled_destinations) or (
    "spring-cloud-gateway",
    (8443,),
) not in enabled_destinations:
    raise SystemExit(
        f"enabled Gateway TLS rendered incomplete TCP Proxy egress: {enabled_destinations}"
    )
if "spring-cloud-gateway-ingress" not in enabled:
    raise SystemExit("enabled Gateway TLS omitted the Gateway listener ingress policy")
enabled_gateway_listener_rules = [
    rule
    for rule in enabled["spring-cloud-gateway-ingress"]["spec"]["ingress"]
    if rule.get("from")
    == [{"podSelector": {"matchLabels": {"app": "tcp-proxy-service"}}}]
]
if len(enabled_gateway_listener_rules) != 1 or enabled_gateway_listener_rules[0].get(
    "ports"
) != [{"protocol": "TCP", "port": 8443}]:
    raise SystemExit(
        "enabled Gateway TLS did not use the configured Gateway TLS targetPort for ingress: "
        f"{enabled_gateway_listener_rules}"
    )
enabled_gateway_ingress = enabled["spring-cloud-gateway-ingress"]["spec"]["ingress"]
enabled_traefik_rule = next(
    rule
    for rule in enabled_gateway_ingress
    if rule.get("from")
    == [
        {
            "namespaceSelector": {
                "matchLabels": {"kubernetes.io/metadata.name": "kube-system"}
            },
            "podSelector": {"matchLabels": {"app.kubernetes.io/name": "traefik"}},
        }
    ]
)
if enabled_traefik_rule.get("ports") != [{"protocol": "TCP", "port": 8080}]:
    raise SystemExit(
        "enabled Gateway TLS did not use the configured Gateway HTTP targetPort for ingress: "
        f"{enabled_traefik_rule}"
    )
if any(
    rule.get("from") == [{"podSelector": {}}]
    or any(port.get("port") == 6565 for port in rule.get("ports", []))
    for rule in enabled_gateway_ingress
):
    raise SystemExit("enabled Gateway ingress has a namespace-wide or 6565 allowance")

disabled_destinations = proxy_egress_destinations(
    disabled["tcp-proxy-service-egress"]
)
if not required_base_egress.issubset(disabled_destinations):
    raise SystemExit(
        "disabled Gateway TLS omitted required TCP Proxy base egress: "
        f"{disabled_destinations}"
    )
if ("spring-cloud-gateway", (8080,)) not in disabled_destinations:
    raise SystemExit(
        "disabled Gateway TLS omitted plaintext TCP Proxy Gateway egress: "
        f"{disabled_destinations}"
    )
if any(
    destination == "spring-cloud-gateway" and ports != (8080,)
    for destination, ports in disabled_destinations
):
    raise SystemExit(
        "disabled Gateway TLS rendered a non-exact plaintext TCP Proxy Gateway egress rule: "
        f"{disabled_destinations}"
    )
if "spring-cloud-gateway-ingress" not in disabled:
    raise SystemExit("disabled Gateway TLS omitted the Gateway listener ingress policy")
disabled_gateway_listener_rules = [
    rule
    for rule in disabled["spring-cloud-gateway-ingress"]["spec"]["ingress"]
    if rule.get("from")
    == [{"podSelector": {"matchLabels": {"app": "tcp-proxy-service"}}}]
]
if len(disabled_gateway_listener_rules) != 1 or disabled_gateway_listener_rules[0].get(
    "ports"
) != [{"protocol": "TCP", "port": 8080}]:
    raise SystemExit(
        "disabled Gateway TLS did not use the validated plaintext Gateway targetPort for ingress: "
        f"{disabled_gateway_listener_rules}"
    )
PY

PLAINTEXT_GATEWAY_TARGET_PORT_RENDERED="$TMP_DIR/plaintext-gateway-target-port.yaml"
python3 - "$TMP_DIR/preview-values.yaml" "$TMP_DIR/plaintext-gateway-target-port-values.yaml" <<'PY'
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
gateway["ports"][0]["targetPort"] = 8181
gateway["ports"] = [
    gateway["ports"][1],
    gateway["ports"][0],
    {"port": 8081, "targetPort": 8281},
]
output_path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/plaintext-gateway-target-port-values.yaml" \
  --set previewStack.gatewayWsTls.enabled=false \
  --show-only templates/network-policies.yaml \
  --namespace pr-123 >"$PLAINTEXT_GATEWAY_TARGET_PORT_RENDERED"
python3 - "$PLAINTEXT_GATEWAY_TARGET_PORT_RENDERED" <<'PY'
import sys
from pathlib import Path

import yaml

documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
policy = next(
    document
    for document in documents
    if document.get("kind") == "NetworkPolicy"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service-egress"
)
gateway_rules = [
    rule
    for rule in policy["spec"]["egress"]
    if rule.get("to")
    == [{"podSelector": {"matchLabels": {"app": "spring-cloud-gateway"}}}]
]
if len(gateway_rules) != 1 or gateway_rules[0].get("ports") != [
    {"protocol": "TCP", "port": 8181}
]:
    raise SystemExit(
        "disabled Gateway TLS did not use the configured Gateway service targetPort "
        "for its exact plaintext egress rule: "
        f"{gateway_rules}"
    )
PY

TLS_GATEWAY_TARGET_PORT_RENDERED="$TMP_DIR/tls-gateway-target-port.yaml"
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/plaintext-gateway-target-port-values.yaml" \
  --set previewStack.gatewayWsTls.enabled=true \
  --show-only templates/network-policies.yaml \
  --namespace pr-123 >"$TLS_GATEWAY_TARGET_PORT_RENDERED"
python3 - "$TLS_GATEWAY_TARGET_PORT_RENDERED" <<'PY'
import sys
from pathlib import Path

import yaml

documents = {
    document["metadata"]["name"]: document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict) and document.get("kind") == "NetworkPolicy"
}
gateway_policy = documents["spring-cloud-gateway-ingress"]
listener_rule = next(
    rule
    for rule in gateway_policy["spec"]["ingress"]
    if rule.get("from")
    == [{"podSelector": {"matchLabels": {"app": "tcp-proxy-service"}}}]
)
if listener_rule.get("ports") != [{"protocol": "TCP", "port": 8443}]:
    raise SystemExit(f"enabled Gateway TLS changed the TCP Proxy WSS target port: {listener_rule}")
http_rules = [rule for rule in gateway_policy["spec"]["ingress"] if rule.get("from") in [
    [{"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "kube-system"}},
      "podSelector": {"matchLabels": {"app.kubernetes.io/name": "traefik"}}}],
]]
if len(http_rules) != 1 or any(
    rule.get("ports") != expected_ports
    for rule, expected_ports in zip(
        http_rules,
        [
            [{"protocol": "TCP", "port": 8181}],
        ],
    )
):
    raise SystemExit(f"enabled Gateway TLS did not use the configured HTTP target port: {http_rules}")
if any(
    rule.get("from") == [{"podSelector": {}}]
    or any(port.get("port") == 6565 for port in rule.get("ports", []))
    for rule in gateway_policy["spec"]["ingress"]
):
    raise SystemExit("enabled Gateway ingress has a namespace-wide or 6565 allowance")
proxy_policy = documents["tcp-proxy-service-egress"]
proxy_rule = next(
    rule
    for rule in proxy_policy["spec"]["egress"]
    if rule.get("to")
    == [{"podSelector": {"matchLabels": {"app": "spring-cloud-gateway"}}}]
)
if proxy_rule.get("ports") != [{"protocol": "TCP", "port": 8443}]:
    raise SystemExit(f"enabled Gateway TLS changed the TCP Proxy WSS egress target port: {proxy_rule}")
PY

for gateway_listener_collision in health http grpc; do
  COLLISION_VALUES="$TMP_DIR/gateway-ws-tls-$gateway_listener_collision-collision-values.yaml"
  python3 - "$TMP_DIR/preview-values.yaml" "$COLLISION_VALUES" "$gateway_listener_collision" <<'PY'
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
  python3 - "$TMP_DIR/preview-values.yaml" "$INVALID_GATEWAY_PORTS_VALUES" "$invalid_gateway_ports" <<'PY'
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
python3 - "$TMP_DIR/preview-values.yaml" "$MISSING_GATEWAY_TARGET_PORT_VALUES" <<'PY'
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
    python3 - "$TMP_DIR/preview-values.yaml" "$GATEWAY_WS_MOUNT_VALUES" \
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

LEGACY_NODEPORT_RENDERED="$TMP_DIR/legacy-nodeport.yaml"
python3 - "$TMP_DIR/preview-values.yaml" "$TMP_DIR/legacy-nodeport-values.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

source_path, output_path = map(Path, sys.argv[1:])
values = yaml.safe_load(source_path.read_text(encoding="utf-8"))
tcp_proxy = next(
    service
    for service in values["previewStack"]["services"]
    if service["name"] == "tcp-proxy-service"
)
tcp_proxy["ports"][0]["nodePort"] = 30001
tcp_proxy["ports"].append({"port": 8080, "targetPort": 8080})
output_path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/legacy-nodeport-values.yaml" \
  --namespace pr-123 \
  >"$LEGACY_NODEPORT_RENDERED"
python3 - "$LEGACY_NODEPORT_RENDERED" <<'PY'
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
telnet_port = next(
    port for port in proxy_service["spec"]["ports"] if port.get("port") == 2323
)
if telnet_port.get("nodePort") != 32123:
    raise SystemExit(
        "hosted-controller Telnet NodePort did not use preview.telnetPort: "
        f"{telnet_port}"
    )
extra_port = next(
    port for port in proxy_service["spec"]["ports"] if port.get("port") == 8080
)
if "nodePort" in extra_port:
    raise SystemExit(
        "hosted-controller extra TCP Proxy service port unexpectedly gained a NodePort"
    )
PY

STANDALONE_NODEPORT_RENDERED="$TMP_DIR/standalone-nodeport.yaml"
python3 - "$TMP_DIR/preview-values.yaml" "$TMP_DIR/standalone-nodeport-values.yaml" <<'PY'
import sys
from pathlib import Path

import yaml

source_path, output_path = map(Path, sys.argv[1:])
values = yaml.safe_load(source_path.read_text(encoding="utf-8"))
values["previewStack"]["certificateIdentity"]["mode"] = "standalone"
tcp_proxy = next(
    service
    for service in values["previewStack"]["services"]
    if service["name"] == "tcp-proxy-service"
)
tcp_proxy["ports"][0]["nodePort"] = 30001
output_path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/standalone-nodeport-values.yaml" \
  --namespace pr-123 \
  >"$STANDALONE_NODEPORT_RENDERED"
python3 - "$STANDALONE_NODEPORT_RENDERED" <<'PY'
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
telnet_port = next(
    port for port in proxy_service["spec"]["ports"] if port.get("port") == 2323
)
if telnet_port.get("nodePort") != 30001:
    raise SystemExit(
        "standalone TCP Proxy Telnet NodePort no longer uses the service port value: "
        f"{telnet_port}"
    )
PY

STANDALONE_GATEWAY_WS_CERT_RENDERED="$TMP_DIR/standalone-gateway-ws-certificates.yaml"
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/standalone-nodeport-values.yaml" \
  --show-only templates/gateway-ws-certificates.yaml \
  --namespace pr-123 >"$STANDALONE_GATEWAY_WS_CERT_RENDERED"
python3 - "$STANDALONE_GATEWAY_WS_CERT_RENDERED" <<'PY'
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
expected_names = {"pr-123-gateway-internal-ws", "pr-123-tcp-proxy-bridge"}
if set(certificates) != expected_names:
    raise SystemExit(
        "standalone mode must render exactly the two chart-owned Gateway bridge Certificates: "
        f"{set(certificates)!r}"
    )

common_private_key = {
    "algorithm": "RSA",
    "size": 2048,
    "encoding": "PKCS8",
    "rotationPolicy": "Always",
}
common_issuer_ref = {
    "name": "firemud-ca-issuer",
    "kind": "ClusterIssuer",
    "group": "cert-manager.io",
}
for name, certificate in certificates.items():
    spec = certificate["spec"]
    if spec.get("secretName") != name:
        raise SystemExit(f"standalone Certificate {name} must write its exact named Secret")
    if spec.get("privateKey") != common_private_key:
        raise SystemExit(f"standalone Certificate {name} has the wrong private-key contract")
    if spec.get("isCA") is not False or spec.get("revisionHistoryLimit") != 1:
        raise SystemExit(f"standalone Certificate {name} has the wrong CA/history contract")
    if spec.get("encodeUsagesInRequest") is not True:
        raise SystemExit(f"standalone Certificate {name} must encode usages in its request")
    if spec.get("issuerRef") != common_issuer_ref:
        raise SystemExit(f"standalone Certificate {name} has the wrong internal ClusterIssuer")

server = certificates["pr-123-gateway-internal-ws"]["spec"]
if server.get("dnsNames") != ["spring-cloud-gateway-mtls.pr-123.svc.cluster.local"]:
    raise SystemExit("standalone Gateway server Certificate must use the exact namespace DNS SAN")
if "uris" in server or server.get("usages") != [
    "digital signature",
    "key encipherment",
    "server auth",
]:
    raise SystemExit("standalone Gateway server Certificate has the wrong identity usages")

client = certificates["pr-123-tcp-proxy-bridge"]["spec"]
if client.get("uris") != ["spiffe://firemud/ns/pr-123/sa/tcp-proxy-service"]:
    raise SystemExit("standalone bridge client Certificate must use the exact SPIFFE URI SAN")
if "dnsNames" in client or client.get("usages") != [
    "digital signature",
    "key encipherment",
    "client auth",
]:
    raise SystemExit("standalone bridge client Certificate has the wrong identity usages")
PY

OVERRIDE_GATEWAY_WS_CERT_RENDERED="$TMP_DIR/override-gateway-ws-certificates.yaml"
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/standalone-nodeport-values.yaml" \
  --set-string previewStack.gatewayWsTls.clusterIssuer=custom-bridge-ca-issuer \
  --show-only templates/gateway-ws-certificates.yaml \
  --namespace pr-123 >"$OVERRIDE_GATEWAY_WS_CERT_RENDERED"
python3 - "$STANDALONE_GATEWAY_WS_CERT_RENDERED" "$OVERRIDE_GATEWAY_WS_CERT_RENDERED" <<'PY'
import copy
import sys
from pathlib import Path

import yaml


def certificates(path):
    documents = [
        document
        for document in yaml.safe_load_all(Path(path).read_text(encoding="utf-8"))
        if isinstance(document, dict)
    ]
    return {
        document["metadata"]["name"]: document
        for document in documents
        if document.get("apiVersion") == "cert-manager.io/v1"
        and document.get("kind") == "Certificate"
    }


default_certificates = certificates(sys.argv[1])
override_certificates = certificates(sys.argv[2])
if set(override_certificates) != set(default_certificates):
    raise SystemExit("ClusterIssuer override changed the standalone Certificate set")
for name, default_certificate in default_certificates.items():
    expected = copy.deepcopy(default_certificate)
    expected["spec"]["issuerRef"]["name"] = "custom-bridge-ca-issuer"
    if override_certificates[name] != expected:
        raise SystemExit(
            f"ClusterIssuer override changed standalone Certificate {name} beyond issuerRef.name"
        )
    if override_certificates[name]["spec"]["issuerRef"] != {
        "name": "custom-bridge-ca-issuer",
        "kind": "ClusterIssuer",
        "group": "cert-manager.io",
    }:
        raise SystemExit(f"standalone Certificate {name} lost its ClusterIssuer contract")
PY

DEV_RENDERED="$TMP_DIR/dev-rendered.yaml"
render_test_values \
  "$TMP_DIR/dev-values.yaml" \
  0 dev dev dev.example.test image-tag 32023
helm template dev "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/dev-values.yaml" \
  --namespace dev \
  >"$DEV_RENDERED"
FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  python3 "$ROOT_DIR/dev-tools/deploy/preflight.py" hosted-bridge \
    "$DEV_RENDERED" dev dev >"$TMP_DIR/dev-preflight.json"

DISABLED_TELNET_CERT_RENDERED="$TMP_DIR/disabled-telnet-certificate.yaml"
set +e
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/preview-values.yaml" \
  --set previewStack.enabled=false \
  --set previewStack.telnetTls.enabled=true \
  --set-string 'previewStack.telnetTls.secretName=' \
  --show-only templates/tcp-proxy-certificate.yaml \
  --namespace pr-123 >"$DISABLED_TELNET_CERT_RENDERED" 2>"$TMP_DIR/disabled-telnet-certificate.err"
disabled_certificate_status=$?
set -e
if [[ "$disabled_certificate_status" -ne 0 && "$disabled_certificate_status" -ne 1 ]] ||
  [[ "$disabled_certificate_status" -eq 1 &&
    "$(
      grep -F "could not find template templates/tcp-proxy-certificate.yaml" \
        "$TMP_DIR/disabled-telnet-certificate.err" || true
    )" == "" ]]; then
  echo "disabled previewStack certificate render failed unexpectedly" >&2
  sed -n '1,20p' "$TMP_DIR/disabled-telnet-certificate.err" >&2
  exit 1
fi
python3 - "$DISABLED_TELNET_CERT_RENDERED" <<'PY'
import sys
from pathlib import Path

import yaml

documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
if documents:
    raise SystemExit(
        "disabled previewStack unexpectedly rendered the standalone Telnet TLS template"
    )
PY

STANDALONE_TELNET_CERT_RENDERED="$TMP_DIR/standalone-telnet-certificate.yaml"
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/standalone-nodeport-values.yaml" \
  --show-only templates/tcp-proxy-certificate.yaml \
  --namespace pr-123 >"$STANDALONE_TELNET_CERT_RENDERED"
python3 - "$STANDALONE_TELNET_CERT_RENDERED" <<'PY'
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
if len(certificates) != 1:
    raise SystemExit("standalone Telnet TLS render must contain exactly one Certificate")
if certificates[0]["spec"].get("privateKey") != {
    "algorithm": "RSA",
    "size": 2048,
    "encoding": "PKCS8",
    "rotationPolicy": "Always",
}:
    raise SystemExit("standalone Telnet TLS Certificate has the wrong private-key contract")
PY

HOSTED_CONTROLLER_TELNET_CERT_RENDERED="$TMP_DIR/hosted-controller-telnet-certificate.yaml"
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/preview-values.yaml" \
  --set previewStack.enabled=true \
  --set previewStack.certificateIdentity.mode=hosted-controller \
  --namespace pr-123 >"$HOSTED_CONTROLLER_TELNET_CERT_RENDERED"
python3 - "$HOSTED_CONTROLLER_TELNET_CERT_RENDERED" <<'PY'
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
        "enabled hosted-controller mode rendered a chart-owned Telnet TLS Certificate"
    )
PY

HOSTED_CONTROLLER_GATEWAY_WS_CERT_RENDERED="$TMP_DIR/hosted-controller-gateway-ws-certificates.yaml"
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/preview-values.yaml" \
  --set previewStack.certificateIdentity.mode=hosted-controller \
  --namespace pr-123 >"$HOSTED_CONTROLLER_GATEWAY_WS_CERT_RENDERED"
python3 - "$HOSTED_CONTROLLER_GATEWAY_WS_CERT_RENDERED" <<'PY'
import sys
from pathlib import Path

import yaml

documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
if any(
    document.get("apiVersion") == "cert-manager.io/v1"
    and document.get("kind") == "Certificate"
    and document.get("metadata", {}).get("name")
    in {"pr-123-gateway-internal-ws", "pr-123-tcp-proxy-bridge"}
    for document in documents
):
    raise SystemExit(
        "enabled hosted-controller mode rendered chart-owned Gateway bridge Certificates"
    )
PY

for gateway_ws_certificate_disabled_case in preview bridge; do
  DISABLED_GATEWAY_WS_CERT_RENDERED="$TMP_DIR/disabled-$gateway_ws_certificate_disabled_case-gateway-ws-certificates.yaml"
  if [[ "$gateway_ws_certificate_disabled_case" == "preview" ]]; then
    DISABLED_GATEWAY_WS_CERT_ARGS=(--set previewStack.enabled=false)
  else
    DISABLED_GATEWAY_WS_CERT_ARGS=(--set previewStack.gatewayWsTls.enabled=false)
  fi
  helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
    -f "$TMP_DIR/standalone-nodeport-values.yaml" \
    "${DISABLED_GATEWAY_WS_CERT_ARGS[@]}" \
    --namespace pr-123 >"$DISABLED_GATEWAY_WS_CERT_RENDERED"
  python3 - "$DISABLED_GATEWAY_WS_CERT_RENDERED" "$gateway_ws_certificate_disabled_case" <<'PY'
import sys
from pathlib import Path

import yaml

documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
if any(
    document.get("apiVersion") == "cert-manager.io/v1"
    and document.get("kind") == "Certificate"
    and document.get("metadata", {}).get("name")
    in {"pr-123-gateway-internal-ws", "pr-123-tcp-proxy-bridge"}
    for document in documents
):
    raise SystemExit(
        f"disabled {sys.argv[2]} mode rendered chart-owned Gateway bridge Certificates"
    )
PY
done

OVERRIDE_RENDERED="$TMP_DIR/trust-environment-override.yaml"
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/preview-values.yaml" \
  --set-string previewStack.gatewayWsTls.trustEnvironment=staging \
  --show-only templates/apps.yaml \
  --namespace pr-123 >"$OVERRIDE_RENDERED"

EXPLICIT_WITHOUT_PR_NUMBER_VALUES="$TMP_DIR/explicit-trust-without-pr-number-values.yaml"
cp "$TMP_DIR/preview-values.yaml" "$EXPLICIT_WITHOUT_PR_NUMBER_VALUES"
python3 - "$EXPLICIT_WITHOUT_PR_NUMBER_VALUES" <<'PY'
import sys
from pathlib import Path

import yaml

path = Path(sys.argv[1])
values = yaml.safe_load(path.read_text(encoding="utf-8"))
values["preview"].pop("prNumber")
values["previewStack"]["gatewayWsTls"]["trustEnvironment"] = "staging"
path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
EXPLICIT_WITHOUT_PR_NUMBER_RENDERED="$TMP_DIR/explicit-trust-without-pr-number.yaml"
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$EXPLICIT_WITHOUT_PR_NUMBER_VALUES" \
  --show-only templates/apps.yaml \
  --namespace pr-123 >"$EXPLICIT_WITHOUT_PR_NUMBER_RENDERED"
python3 - "$RENDERED" "$DEV_RENDERED" "$OVERRIDE_RENDERED" <<'PY'
import sys
from pathlib import Path

import yaml


def trust_environment(path):
    documents = [
        document
        for document in yaml.safe_load_all(Path(path).read_text(encoding="utf-8"))
        if isinstance(document, dict)
    ]
    gateway = next(
        document
        for document in documents
        if document.get("kind") == "Deployment"
        and document.get("metadata", {}).get("name") == "spring-cloud-gateway"
    )
    environment = next(
        entry
        for entry in gateway["spec"]["template"]["spec"]["containers"][0]["env"]
        if entry.get("name") == "FIREMUD_GATEWAY_TCP_PROXY_TRUST_ENVIRONMENT"
    )
    return environment.get("value")


assert trust_environment(sys.argv[1]) == "pr-preview"
assert trust_environment(sys.argv[2]) == "dev-demo-cluster"
assert trust_environment(sys.argv[3]) == "staging"
PY
python3 - "$EXPLICIT_WITHOUT_PR_NUMBER_RENDERED" <<'PY'
import sys
from pathlib import Path

import yaml

documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
gateway = next(
    document
    for document in documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway"
)
trust_environment = next(
    entry["value"]
    for entry in gateway["spec"]["template"]["spec"]["containers"][0]["env"]
    if entry.get("name") == "FIREMUD_GATEWAY_TCP_PROXY_TRUST_ENVIRONMENT"
)
if trust_environment != "staging":
    raise SystemExit(
        "explicit trustEnvironment without preview.prNumber did not render staging: "
        f"{trust_environment!r}"
    )
PY

STRING_ZERO_RENDERED="$TMP_DIR/string-zero-preview-pr-number.yaml"
helm template dev "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/dev-values.yaml" \
  --set-string preview.prNumber=0 \
  --show-only templates/apps.yaml \
  --namespace dev >"$STRING_ZERO_RENDERED"
python3 - "$STRING_ZERO_RENDERED" <<'PY'
import sys
from pathlib import Path

import yaml

documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
gateway = next(
    document
    for document in documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway"
)
trust_environment = next(
    entry["value"]
    for entry in gateway["spec"]["template"]["spec"]["containers"][0]["env"]
    if entry.get("name") == "FIREMUD_GATEWAY_TCP_PROXY_TRUST_ENVIRONMENT"
)
if trust_environment != "dev-demo-cluster":
    raise SystemExit(
        "string zero preview.prNumber did not infer dev-demo-cluster: "
        f"{trust_environment!r}"
    )
PY

UNRESOLVED_PR_NUMBER_VALUES="$TMP_DIR/unresolved-pr-number-values.yaml"
python3 - "$TMP_DIR/preview-values.yaml" "$UNRESOLVED_PR_NUMBER_VALUES" <<'PY'
import sys
from pathlib import Path

import yaml

source_path, output_path = map(Path, sys.argv[1:])
values = yaml.safe_load(source_path.read_text(encoding="utf-8"))
values["preview"]["prNumber"] = "__PR_NUMBER__"
output_path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
UNRESOLVED_PR_NUMBER_ERROR="preview.prNumber must be resolved before Gateway WebSocket TLS trust-environment inference"
if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$UNRESOLVED_PR_NUMBER_VALUES" \
  --show-only templates/apps.yaml \
  --namespace pr-123 >/dev/null 2>"$TMP_DIR/unresolved-pr-number.err"; then
  echo "Gateway WebSocket TLS inferred pr-preview from unresolved preview.prNumber" >&2
  exit 1
fi
if ! grep -Fq "$UNRESOLVED_PR_NUMBER_ERROR" "$TMP_DIR/unresolved-pr-number.err"; then
  echo "chart did not reject unresolved preview.prNumber before trust-environment inference" >&2
  sed -n '1,20p' "$TMP_DIR/unresolved-pr-number.err" >&2
  exit 1
fi

TRUST_ENVIRONMENT_ERROR="previewStack.gatewayWsTls.trustEnvironment must be one of the canonical environments"
if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/preview-values.yaml" \
  --set-string previewStack.gatewayWsTls.trustEnvironment=not-a-canonical-environment \
  --show-only templates/apps.yaml \
  --namespace pr-123 >/dev/null 2>"$TMP_DIR/invalid-trust-environment.err"; then
  echo "apps template rendered with an invalid explicit Gateway trust environment" >&2
  exit 1
fi
if ! grep -Fq "$TRUST_ENVIRONMENT_ERROR" "$TMP_DIR/invalid-trust-environment.err"; then
  echo "apps template did not report the expected invalid Gateway trust environment diagnostic" >&2
  sed -n '1,20p' "$TMP_DIR/invalid-trust-environment.err" >&2
  exit 1
fi

if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/preview-values.yaml" \
  --set-string previewStack.gatewayWsTls.trustEnvironment=isolated-test \
  --show-only templates/apps.yaml \
  --namespace pr-123 >/dev/null 2>"$TMP_DIR/isolated-test-trust-environment.err"; then
  echo "apps template rendered with the runtime-test-only Gateway trust environment" >&2
  exit 1
fi
if ! grep -Fq "$TRUST_ENVIRONMENT_ERROR" "$TMP_DIR/isolated-test-trust-environment.err"; then
  echo "apps template did not report the expected runtime-test-only Gateway trust environment diagnostic" >&2
  sed -n '1,20p' "$TMP_DIR/isolated-test-trust-environment.err" >&2
  exit 1
fi

for preview_shape in absent null; do
  INVALID_PREVIEW_VALUES="$TMP_DIR/preview-values-$preview_shape.yaml"
  cp "$TMP_DIR/preview-values.yaml" "$INVALID_PREVIEW_VALUES"
  python3 - "$INVALID_PREVIEW_VALUES" "$preview_shape" <<'PY'
import sys
from pathlib import Path

import yaml

path = Path(sys.argv[1])
values = yaml.safe_load(path.read_text(encoding="utf-8"))
if sys.argv[2] == "absent":
    values.pop("preview")
else:
    values["preview"] = None
path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY

  APPS_PREVIEW_ERROR="preview.telnetPort is required for hosted-controller TCP Proxy"
  if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
    -f "$INVALID_PREVIEW_VALUES" \
    --set previewStack.ingress.enabled=false \
    --set previewStack.gatewayWsTls.enabled=false \
    --show-only templates/apps.yaml \
    --namespace pr-123 >/dev/null 2>"$TMP_DIR/invalid-preview-$preview_shape-apps.err"; then
    echo "apps template rendered hosted-controller TCP Proxy with $preview_shape preview" >&2
    exit 1
  fi
  if ! grep -Fq "$APPS_PREVIEW_ERROR" "$TMP_DIR/invalid-preview-$preview_shape-apps.err"; then
    echo "apps template did not report the expected $preview_shape preview.telnetPort diagnostic" >&2
    sed -n '1,20p' "$TMP_DIR/invalid-preview-$preview_shape-apps.err" >&2
    exit 1
  fi

  CERT_PREVIEW_ERROR="preview.hostname is required when rendering the standalone Telnet TLS Certificate"
  if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
    -f "$INVALID_PREVIEW_VALUES" \
    --set previewStack.ingress.enabled=false \
    --set previewStack.gatewayWsTls.enabled=false \
    --set previewStack.certificateIdentity.mode=standalone \
    --show-only templates/tcp-proxy-certificate.yaml \
    --namespace pr-123 >/dev/null 2>"$TMP_DIR/invalid-preview-$preview_shape-certificate.err"; then
    echo "standalone Telnet TLS Certificate rendered with $preview_shape preview" >&2
    exit 1
  fi
  if ! grep -Fq "$CERT_PREVIEW_ERROR" "$TMP_DIR/invalid-preview-$preview_shape-certificate.err"; then
    echo "certificate template did not report the expected $preview_shape preview.hostname diagnostic" >&2
    sed -n '1,20p' "$TMP_DIR/invalid-preview-$preview_shape-certificate.err" >&2
    exit 1
  fi
done

PREVIEW_PR_NUMBER_ERROR="preview.prNumber is required when Gateway WebSocket TLS is enabled"
for invalid_pr_number in missing empty; do
  INVALID_PR_VALUES="$TMP_DIR/preview-values-pr-number-$invalid_pr_number.yaml"
  cp "$TMP_DIR/preview-values.yaml" "$INVALID_PR_VALUES"
  python3 - "$INVALID_PR_VALUES" "$invalid_pr_number" <<'PY'
import sys
from pathlib import Path

import yaml

path = Path(sys.argv[1])
values = yaml.safe_load(path.read_text(encoding="utf-8"))
if sys.argv[2] == "missing":
    values["preview"].pop("prNumber")
else:
    values["preview"]["prNumber"] = ""
path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
  if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
    -f "$INVALID_PR_VALUES" \
    --namespace pr-123 >/dev/null 2>"$TMP_DIR/invalid-pr-number-$invalid_pr_number.err"; then
    echo "Gateway WebSocket TLS rendered with $invalid_pr_number preview.prNumber" >&2
    exit 1
  fi
  if ! grep -Fq "$PREVIEW_PR_NUMBER_ERROR" "$TMP_DIR/invalid-pr-number-$invalid_pr_number.err"; then
    echo "chart did not report the expected $invalid_pr_number preview.prNumber diagnostic" >&2
    sed -n '1,20p' "$TMP_DIR/invalid-pr-number-$invalid_pr_number.err" >&2
    exit 1
  fi
done

GATEWAY_WS_TARGET_PORT_ERROR="previewStack.gatewayWsTls.targetPort is required when Gateway WebSocket TLS is enabled"
for template in templates/apps.yaml templates/network-policies.yaml; do
  error_file="$TMP_DIR/missing-gateway-target-port-$(basename "$template").err"
  if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
    -f "$TMP_DIR/preview-values.yaml" \
    --set-string 'previewStack.gatewayWsTls.targetPort=' \
    --show-only "$template" \
    --namespace pr-123 >/dev/null 2>"$error_file"; then
    echo "$template rendered Gateway WebSocket TLS with no targetPort" >&2
    exit 1
  fi
  if ! grep -Fq "$GATEWAY_WS_TARGET_PORT_ERROR" "$error_file"; then
    echo "$template did not report the expected missing targetPort diagnostic" >&2
    sed -n '1,20p' "$error_file" >&2
    exit 1
  fi
done

GATEWAY_WS_SERVICE_PORT_ERROR="previewStack.gatewayWsTls.servicePort is required when Gateway WebSocket TLS is enabled"
if helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/preview-values.yaml" \
  --set-string 'previewStack.gatewayWsTls.servicePort=' \
  --show-only templates/apps.yaml \
  --namespace pr-123 >/dev/null 2>"$TMP_DIR/missing-gateway-service-port.err"; then
  echo "apps template rendered Gateway WebSocket TLS with no servicePort" >&2
  exit 1
fi
if ! grep -Fq "$GATEWAY_WS_SERVICE_PORT_ERROR" "$TMP_DIR/missing-gateway-service-port.err"; then
  echo "apps template did not report the expected missing servicePort diagnostic" >&2
  sed -n '1,20p' "$TMP_DIR/missing-gateway-service-port.err" >&2
  exit 1
fi

EXISTING_GATEWAY_TARGET_PORT_RENDERED="$TMP_DIR/existing-gateway-target-port.yaml"
python3 - "$TMP_DIR/preview-values.yaml" "$TMP_DIR/existing-gateway-target-port-values.yaml" <<'PY'
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
gateway["ports"].append({"port": 8443, "targetPort": 8443})
output_path.write_text(yaml.safe_dump(values, sort_keys=False), encoding="utf-8")
PY
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/existing-gateway-target-port-values.yaml" \
  --show-only templates/apps.yaml \
  --namespace pr-123 >"$EXISTING_GATEWAY_TARGET_PORT_RENDERED"
python3 - "$RENDERED" "$EXISTING_GATEWAY_TARGET_PORT_RENDERED" <<'PY'
import sys
from pathlib import Path

import yaml


def gateway_container(path):
    documents = [
        document
        for document in yaml.safe_load_all(Path(path).read_text(encoding="utf-8"))
        if isinstance(document, dict)
    ]
    deployment = next(
        document
        for document in documents
        if document.get("kind") == "Deployment"
        and document.get("metadata", {}).get("name") == "spring-cloud-gateway"
    )
    return deployment["spec"]["template"]["spec"]["containers"][0]


default_ports = gateway_container(sys.argv[1])["ports"]
default_target_ports = [port for port in default_ports if port["containerPort"] == 8443]
if default_target_ports != [{"containerPort": 8443, "name": "gateway-ws-mtls"}]:
    raise SystemExit(
        "chart did not append the named Gateway TLS port when the generic ports omitted it: "
        f"{default_target_ports}"
    )

existing_ports = gateway_container(sys.argv[2])["ports"]
existing_target_ports = [port for port in existing_ports if port["containerPort"] == 8443]
if existing_target_ports != [{"containerPort": 8443}]:
    raise SystemExit(
        "chart duplicated the Gateway TLS target port already emitted by the generic loop: "
        f"{existing_target_ports}"
    )
PY

NON_DEFAULT_SERVICE_PORT_RENDERED="$TMP_DIR/non-default-service-port.yaml"
helm template pr-123 "$ROOT_DIR/k8s/helm/firemud" \
  -f "$TMP_DIR/preview-values.yaml" \
  --set previewStack.gatewayWsTls.servicePort=444 \
  --show-only templates/apps.yaml \
  --namespace pr-123 >"$NON_DEFAULT_SERVICE_PORT_RENDERED"
python3 - "$NON_DEFAULT_SERVICE_PORT_RENDERED" <<'PY'
import sys
from pathlib import Path

import yaml

documents = [
    document
    for document in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
proxy = next(
    document
    for document in documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
proxy_env = {
    entry["name"]: entry.get("value")
    for entry in proxy["spec"]["template"]["spec"]["containers"][0]["env"]
}
assert proxy_env["GATEWAY_WS_URL"] == (
    "wss://spring-cloud-gateway-mtls.pr-123.svc.cluster.local:444/ws/game"
)
gateway_service = next(
    document
    for document in documents
    if document.get("kind") == "Service"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway-mtls"
)
assert gateway_service["spec"]["ports"] == [
    {"name": "wss-mtls", "port": 444, "targetPort": 8443, "protocol": "TCP"}
]
PY

for context in ci-static operator; do
  set +e
  invalid_output="$(
    FIREMUD_PREFLIGHT_CONTEXT="$context" \
      python3 "$ROOT_DIR/dev-tools/deploy/preflight.py" hosted-bridge \
        "$RENDERED" production prod 2>&1
  )"
  invalid_status=$?
  set -e
  if [[ "$invalid_status" -ne 1 ]] ||
    [[ "$invalid_output" != *"hosted bridge identity must be exactly dev/dev"* ]]; then
    echo "$context CLI preflight did not reject a non-hosted identity before validation" >&2
    exit 1
  fi
done

python3 - "$ROOT_DIR" "$RENDERED" "$DEV_RENDERED" <<'PY'
import copy
import importlib.util
import io
import json
import pathlib
import subprocess
import sys
from unittest import mock

import yaml

root = pathlib.Path(sys.argv[1])
rendered_path = pathlib.Path(sys.argv[2])
dev_rendered_path = pathlib.Path(sys.argv[3])
spec = importlib.util.spec_from_file_location(
    "hosted_bridge_preflight", root / "dev-tools/deploy/preflight.py"
)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)

documents = [
    document
    for document in yaml.safe_load_all(rendered_path.read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
for document in documents:
    document.setdefault("metadata", {}).setdefault("namespace", "pr-123")

dev_documents = [
    document
    for document in yaml.safe_load_all(dev_rendered_path.read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
for environment, rendered_documents in (
    ("preview", documents),
    ("dev-demo", dev_documents),
):
    tcp_proxy_resources = [
        document
        for document in rendered_documents
        if document.get("kind") in {"Deployment", "Service"}
        and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    ]
    if len(tcp_proxy_resources) != 2 or any(
        document.get("metadata", {}).get("labels", {}).get(
            "firemud.dev/certificate-identity-mode"
        )
        != "hosted-controller"
        for document in tcp_proxy_resources
    ):
        raise SystemExit(
            f"{environment} workflow-rendered artifact did not retain hosted-controller ownership"
        )

expected = module.hosted_bridge_expected_bindings("pr-123", "pr-123")
values, issues = module.validate_gateway_ws_values(documents, expected)
if issues or values != [
    "wss://spring-cloud-gateway-mtls.pr-123.svc.cluster.local:443/ws/game"
]:
    raise SystemExit(f"canonical hosted bridge render failed validation: {issues}")

if module.hosted_bridge_expected_bindings("dev", "dev")["environment"] != "dev-demo-cluster":
    raise SystemExit("canonical dev hosted bridge identity was rejected")

invalid_identities = (
    ("production", "prod"),
    ("other", "other"),
    ("pr-0", "pr-0"),
    ("pr-01", "pr-01"),
    ("pr-1", "pr-2"),
    ("pr-1-preview", "pr-1-preview"),
    ("pr-1foo", "pr-1foo"),
    ("preview-pr-1", "preview-pr-1"),
    ("pr-" + "1" * 61, "pr-" + "1" * 61),
    ("dev", "dev-demo"),
)
for namespace, release_name in invalid_identities:
    try:
        module.hosted_bridge_expected_bindings(namespace, release_name)
    except ValueError:
        pass
    else:
        raise SystemExit(
            f"invalid hosted bridge identity was classified: {namespace}/{release_name}"
        )

for context in ("ci-static", "operator"):
    for namespace, release_name in invalid_identities:
        with mock.patch.object(
            module,
            "secret_keys_lookup_failure",
            side_effect=AssertionError("invalid identity reached Secret lookup"),
        ), mock.patch.object(module.sys, "stderr", io.StringIO()):
            try:
                module.hosted_bridge_preflight(
                    rendered_path, namespace, release_name, context
                )
            except SystemExit as exc:
                if exc.code != 1:
                    raise SystemExit(
                        f"invalid hosted bridge identity exited unexpectedly: {exc.code}"
                    ) from exc
            else:
                raise SystemExit(
                    f"{context} preflight accepted invalid identity: "
                    f"{namespace}/{release_name}"
                )

with mock.patch.object(
    module.subprocess,
    "run",
    return_value=subprocess.CompletedProcess(
        args=[],
        returncode=0,
        stdout='{"data":{"tls.crt":"redacted","tls.key":"redacted"}}',
        stderr="",
    ),
):
    missing_key_issue, missing_key_retryable, missing_key_timed_out = (
        module.secret_keys_lookup_failure(
            "pr-123-tcp-proxy-bridge",
            "pr-123",
            {"tls.crt", "tls.key", "ca.crt"},
        )
    )
if (
    missing_key_issue is None
    or "missing keys: ca.crt" not in missing_key_issue
    or missing_key_retryable is not True
    or missing_key_timed_out is not False
):
    raise SystemExit("operator preflight accepted an incomplete controller-projected Secret")

operator_secret_lookups = []


def record_operator_secret_lookup(
    secret_name, namespace, required_keys, timeout_seconds
):
    if timeout_seconds != module.SECRET_LOOKUP_TIMEOUT_SECONDS:
        raise SystemExit("operator bridge Secret lookup did not retain its bounded timeout")
    operator_secret_lookups.append((secret_name, namespace, required_keys))
    return None, False, False


with mock.patch.object(
    module,
    "secret_keys_lookup_failure",
    side_effect=record_operator_secret_lookup,
), mock.patch.object(module.sys, "stdout", io.StringIO()):
    operator_result = module.hosted_bridge_preflight(
        rendered_path,
        "pr-123",
        "pr-123",
        "operator",
        expected_hosted_telnet_node_port=32123,
    )
if operator_result != 0:
    raise SystemExit("operator preflight rejected ready controller-projected Secrets")
if operator_secret_lookups != [
    (
        "pr-123-gateway-internal-ws",
        "pr-123",
        {"tls.crt", "tls.key", "ca.crt"},
    ),
    (
        "pr-123-tcp-proxy-bridge",
        "pr-123",
        {"tls.crt", "tls.key", "ca.crt"},
    ),
    (
        "pr-123-telnet-tls",
        "pr-123",
        {"tls.crt", "tls.key"},
    ),
]:
    raise SystemExit(
        f"operator preflight did not verify every controller projection: "
        f"{operator_secret_lookups}"
    )

forged_standalone = copy.deepcopy(documents)
for document in forged_standalone:
    if (
        document.get("kind") in {"Deployment", "Service"}
        and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    ):
        document["metadata"]["labels"][
            "firemud.dev/certificate-identity-mode"
        ] = "standalone"
forged_standalone.append(
    {
        "apiVersion": "cert-manager.io/v1",
        "kind": "Certificate",
        "metadata": {
            "name": "pr-123-telnet-tls",
            "namespace": "pr-123",
            "labels": {
                "app.kubernetes.io/name": "firemud",
                "app.kubernetes.io/instance": "pr-123",
                "app.kubernetes.io/managed-by": "Helm",
            },
        },
        "spec": {
            "secretName": "pr-123-telnet-tls",
            "privateKey": {"algorithm": "RSA", "encoding": "PKCS8"},
            "dnsNames": ["preview-123.example.test"],
            "issuerRef": {"name": "letsencrypt-prod", "kind": "ClusterIssuer"},
        },
    }
)
forged_path = rendered_path.parent / "forged-standalone.yaml"
forged_path.write_text(
    yaml.safe_dump_all(forged_standalone, sort_keys=False), encoding="utf-8"
)
forged_static_output = io.StringIO()
with mock.patch.object(module.sys, "stdout", forged_static_output):
    forged_static_result = module.hosted_bridge_preflight(
        forged_path, "pr-123", "pr-123", "ci-static"
    )
forged_static_policy = json.loads(forged_static_output.getvalue())
if (
    forged_static_result != 1
    or forged_static_policy.get("policyId") != "PREFLIGHT-BRIDGE-001"
    or forged_static_policy.get("status") != "fail"
    or "certificate identity mode must be hosted-controller"
    not in forged_static_policy.get("message", "")
):
    raise SystemExit(
        "hosted preflight accepted artifact-controlled standalone certificate ownership"
    )

with mock.patch.object(
    module,
    "secret_keys_lookup_failure",
    side_effect=AssertionError("invalid render reached operator Secret lookup"),
), mock.patch.object(module.sys, "stdout", io.StringIO()):
    forged_operator_result = module.hosted_bridge_preflight(
        forged_path, "pr-123", "pr-123", "operator"
    )
if forged_operator_result != 1:
    raise SystemExit(
        "operator preflight accepted artifact-controlled standalone certificate ownership"
    )

resource_kinds = {
    (document.get("kind"), document.get("metadata", {}).get("name"))
    for document in documents
}
for forbidden in (
    ("Certificate", "pr-123-gateway-internal-ws"),
    ("Certificate", "pr-123-tcp-proxy-bridge"),
    ("Certificate", "pr-123-telnet-tls"),
    ("Issuer", "pr-123-gateway-internal-ws"),
    ("Secret", "pr-123-gateway-internal-ws"),
    ("Secret", "pr-123-tcp-proxy-bridge"),
):
    if forbidden in resource_kinds:
        raise SystemExit(f"chart took ownership of controller-projected identity: {forbidden}")

gateway = next(
    document
    for document in documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway"
)
proxy = next(
    document
    for document in documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
gateway_strategy = gateway.get("spec", {}).get("strategy")
if gateway_strategy is not None and (
    not isinstance(gateway_strategy, dict)
    or gateway_strategy.get("type") != "RollingUpdate"
):
    raise SystemExit("Gateway Deployment must retain Kubernetes' RollingUpdate default")
if proxy.get("spec", {}).get("strategy") != {"type": "Recreate"}:
    raise SystemExit("TCP Proxy identity withdrawal can retain a stale rolling-update pod")

gateway_omitted_documents = copy.deepcopy(documents)
gateway_omitted = next(
    document
    for document in gateway_omitted_documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway"
)
gateway_omitted["spec"].pop("strategy", None)
_, omitted_strategy_issues = module.validate_gateway_ws_values(
    gateway_omitted_documents, expected
)
if any(
    "Gateway bridge Deployment strategy must be RollingUpdate or omitted" in issue
    for issue in omitted_strategy_issues
):
    raise SystemExit(
        f"Omitted Gateway Deployment strategy was incorrectly rejected: {omitted_strategy_issues}"
    )

gateway_strategy_documents = copy.deepcopy(documents)
gateway_copy = next(
    document
    for document in gateway_strategy_documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway"
)
gateway_copy["spec"]["strategy"] = {"type": "Recreate"}
_, gateway_strategy_issues = module.validate_gateway_ws_values(
    gateway_strategy_documents, expected
)
if not any(
    "Gateway bridge Deployment strategy must be RollingUpdate or omitted"
    in issue
    for issue in gateway_strategy_issues
):
    raise SystemExit(
        "Gateway Recreate strategy was accepted for the bridge listener: "
        f"{gateway_strategy_issues}"
    )

gateway_tuned_documents = copy.deepcopy(documents)
gateway_tuned = next(
    document
    for document in gateway_tuned_documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway"
)
gateway_tuned["spec"]["strategy"] = {
    "type": "RollingUpdate",
    "rollingUpdate": {"maxUnavailable": 0, "maxSurge": 1},
}
_, tuned_strategy_issues = module.validate_gateway_ws_values(gateway_tuned_documents, expected)
if any(
    "Gateway bridge Deployment strategy must be RollingUpdate or omitted" in issue
    for issue in tuned_strategy_issues
):
    raise SystemExit(
        f"Gateway RollingUpdate tuning fields were incorrectly rejected: {tuned_strategy_issues}"
    )

for invalid_strategy in ("RollingUpdate", {"type": "Recreate"}, {"rollingUpdate": {}}):
    invalid_strategy_documents = copy.deepcopy(documents)
    invalid_strategy_gateway = next(
        document
        for document in invalid_strategy_documents
        if document.get("kind") == "Deployment"
        and document.get("metadata", {}).get("name") == "spring-cloud-gateway"
    )
    invalid_strategy_gateway["spec"]["strategy"] = invalid_strategy
    _, invalid_strategy_issues = module.validate_gateway_ws_values(
        invalid_strategy_documents, expected
    )
    if not any(
        "Gateway bridge Deployment strategy must be RollingUpdate or omitted" in issue
        for issue in invalid_strategy_issues
    ):
        raise SystemExit(
            f"Invalid Gateway Deployment strategy was accepted: {invalid_strategy!r}"
        )

strategy_issue = (
    "TCP Proxy bridge Deployment strategy must be Recreate for planned identity replacement"
)
multi_container_proxy = copy.deepcopy(documents)
proxy_copy = next(
    document
    for document in multi_container_proxy
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
second_proxy_container = copy.deepcopy(
    proxy_copy["spec"]["template"]["spec"]["containers"][0]
)
second_proxy_container["name"] = "tcp-proxy-shadow-service"
proxy_copy["spec"]["template"]["spec"]["containers"].append(second_proxy_container)
_, recreate_issues = module.validate_gateway_ws_values(multi_container_proxy, expected)
if strategy_issue in recreate_issues:
    raise SystemExit(
        f"multi-container Recreate bridge reported a strategy issue: {recreate_issues}"
    )
proxy_copy["spec"]["strategy"] = {"type": "RollingUpdate"}
_, rolling_update_issues = module.validate_gateway_ws_values(
    multi_container_proxy, expected
)
if rolling_update_issues.count(strategy_issue) != 1:
    raise SystemExit(
        "multi-container non-Recreate bridge did not report exactly one strategy issue: "
        f"{rolling_update_issues}"
    )


def mutate_env(documents_to_mutate, workload, name, value=None, remove=False):
    deployment = next(
        document
        for document in documents_to_mutate
        if document.get("kind") == "Deployment"
        and document.get("metadata", {}).get("name") == workload
    )
    container = deployment["spec"]["template"]["spec"]["containers"][0]
    if remove:
        container["env"] = [
            entry for entry in container.get("env", []) if entry.get("name") != name
        ]
        return
    next(entry for entry in container["env"] if entry.get("name") == name)["value"] = value


def append_env(documents_to_mutate, workload, name, value):
    deployment = next(
        document
        for document in documents_to_mutate
        if document.get("kind") == "Deployment"
        and document.get("metadata", {}).get("name") == workload
    )
    deployment["spec"]["template"]["spec"]["containers"][0]["env"].append(
        {"name": name, "value": value}
    )


for label, mutation, expected_fragment in (
    (
        "plaintext",
        lambda docs: mutate_env(
            docs,
            "tcp-proxy-service",
            "GATEWAY_WS_URL",
            "ws://spring-cloud-gateway/ws/game",
        ),
        "does not match canonical",
    ),
    (
        "missing-listener",
        lambda docs: mutate_env(
            docs,
            "spring-cloud-gateway",
            "FIREMUD_GATEWAY_TCP_PROXY_TLS_ENABLED",
            remove=True,
        ),
        "TLS_ENABLED must be exactly",
    ),
    (
        "development-profile",
        lambda docs: mutate_env(
            docs,
            "spring-cloud-gateway",
            "FIREMUD_GATEWAY_TCP_PROXY_TRUST_PROFILE",
            "development_cidr",
        ),
        "development_cidr is forbidden",
    ),
    (
        "legacy-cidr-fallback",
        lambda docs: append_env(
            docs,
            "spring-cloud-gateway",
            "FIREMUD_GATEWAY_HEADER_TRUST_TCP_PROXY_ALLOW_INSECURE_HEADERS_FROM_TRUSTED_CIDRS",
            "true",
        ),
        "must not configure legacy TCP Proxy header trust",
    ),
    (
        "foreign-trust-environment",
        lambda docs: mutate_env(
            docs,
            "spring-cloud-gateway",
            "FIREMUD_GATEWAY_TCP_PROXY_TRUST_ENVIRONMENT",
            "dev-demo-cluster",
        ),
        "FIREMUD_GATEWAY_TCP_PROXY_TRUST_ENVIRONMENT must be exactly 'pr-preview'",
    ),
):
    mutated = copy.deepcopy(documents)
    mutation(mutated)
    _, mutation_issues = module.validate_gateway_ws_values(mutated, expected)
    if not any(expected_fragment in issue for issue in mutation_issues):
        raise SystemExit(f"{label} mutation was accepted: {mutation_issues}")

missing_credentials = copy.deepcopy(documents)
proxy_copy = next(
    document
    for document in missing_credentials
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
proxy_container = proxy_copy["spec"]["template"]["spec"]["containers"][0]
proxy_container["volumeMounts"] = [
    mount
    for mount in proxy_container["volumeMounts"]
    if mount.get("name") != "gateway-ws-client-tls"
]
_, credential_issues = module.validate_gateway_ws_values(missing_credentials, expected)
if not any("requires exactly one /gateway-ws-client-tls mount" in issue for issue in credential_issues):
    raise SystemExit(f"missing bridge credentials were accepted: {credential_issues}")

shared_telnet_identity = copy.deepcopy(documents)
proxy_copy = next(
    document
    for document in shared_telnet_identity
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
next(
    volume
    for volume in proxy_copy["spec"]["template"]["spec"]["volumes"]
    if volume.get("name") == "telnet-tls"
)["secret"]["secretName"] = "pr-123-tcp-proxy-bridge"
_, shared_identity_issues = module.validate_gateway_ws_values(
    shared_telnet_identity, expected
)
if not any("distinct from the Telnet TLS Secret" in issue for issue in shared_identity_issues):
    raise SystemExit(
        f"bridge client reused the Telnet identity without failing: {shared_identity_issues}"
    )

proxy_policy = next(
    document
    for document in documents
    if document.get("kind") == "NetworkPolicy"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service-egress"
)
collector_rules = [
    rule
    for rule in proxy_policy["spec"]["egress"]
    if rule.get("to")
    == [{"podSelector": {"matchLabels": {"app": "otel-collector"}}}]
]
if len(collector_rules) != 1 or collector_rules[0].get("ports") != [
    {"protocol": "TCP", "port": 4317}
]:
    raise SystemExit(
        "TCP Proxy egress does not narrowly allow same-namespace OTLP to app=otel-collector"
    )
if any(
    rule.get("to") == [{"podSelector": {"matchLabels": {"app": "postgres"}}}]
    for rule in proxy_policy["spec"]["egress"]
):
    raise SystemExit("TCP Proxy egress unexpectedly allows direct Postgres access")

widened_policy = copy.deepcopy(documents)
gateway_policy = next(
    document
    for document in widened_policy
    if document.get("kind") == "NetworkPolicy"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway-ingress"
)
gateway_policy["spec"]["ingress"][0]["from"] = [{"podSelector": {}}]
_, policy_issues = module.validate_gateway_ws_values(widened_policy, expected)
if not any("exactly one app=tcp-proxy-service peer rule" in issue for issue in policy_issues):
    raise SystemExit(f"widened Gateway listener policy was accepted: {policy_issues}")

widened_port_range = copy.deepcopy(documents)
gateway_policy = next(
    document
    for document in widened_port_range
    if document.get("kind") == "NetworkPolicy"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway-ingress"
)
gateway_policy["spec"]["ingress"][0]["ports"] = [
    {"protocol": "TCP", "port": 8000, "endPort": 9000}
]
_, range_issues = module.validate_gateway_ws_values(widened_port_range, expected)
if not any("listener rule must be exactly TCP 8443" in issue for issue in range_issues):
    raise SystemExit(f"widened Gateway listener port range was accepted: {range_issues}")

for label, policy_name, direction, peer in (
    (
        "Gateway ingress",
        "spring-cloud-gateway-ingress",
        "ingress",
        "tcp-proxy-service",
    ),
    (
        "TCP Proxy egress",
        "tcp-proxy-service-egress",
        "egress",
        "spring-cloud-gateway",
    ),
):
    extra_port_policy = copy.deepcopy(documents)
    policy = next(
        document
        for document in extra_port_policy
        if document.get("kind") == "NetworkPolicy"
        and document.get("metadata", {}).get("name") == policy_name
    )
    canonical_peer_rule = next(
        rule
        for rule in policy["spec"][direction]
        if module.canonical_bridge_peer(rule, peer)
    )
    canonical_peer_rule["ports"] = [
        {"protocol": "TCP", "port": 8443},
        {"protocol": "TCP", "port": 8080},
    ]
    _, extra_port_issues = module.validate_gateway_ws_values(
        extra_port_policy, expected
    )
    if not any(
        f"{label} listener rule must be exactly TCP 8443" in issue
        for issue in extra_port_issues
    ):
        raise SystemExit(
            f"{label} canonical peer rule with an extra port was accepted: "
            f"{extra_port_issues}"
        )

for label, mutation in (
    (
        "omitted-ports",
        lambda rule: rule.pop("ports"),
    ),
    (
        "empty-ports",
        lambda rule: rule.__setitem__("ports", []),
    ),
    (
        "missing-port-value",
        lambda rule: rule.__setitem__("ports", [{"protocol": "TCP"}]),
    ),
):
    all_port_policy = copy.deepcopy(documents)
    gateway_policy = next(
        document
        for document in all_port_policy
        if document.get("kind") == "NetworkPolicy"
        and document.get("metadata", {}).get("name") == "spring-cloud-gateway-ingress"
    )
    mutation(gateway_policy["spec"]["ingress"][0])
    _, all_port_issues = module.validate_gateway_ws_values(
        all_port_policy, expected
    )
    if not any("must not contain an all-port rule" in issue for issue in all_port_issues):
        raise SystemExit(f"{label} Gateway policy was accepted: {all_port_issues}")

for label, ports, expected_fragment in (
    (
        "repeated-listener-qualifiers",
        [
            {"protocol": "TCP", "port": 8443},
            {"protocol": "TCP", "port": 8000, "endPort": 9000},
        ],
        "listener rule must be exactly TCP 8443",
    ),
    (
        "repeated-malformed-qualifiers",
        [{"protocol": "TCP"}, {"protocol": "TCP"}],
        "must not contain an all-port rule",
    ),
):
    repeated_qualifier_policy = copy.deepcopy(documents)
    gateway_policy = next(
        document
        for document in repeated_qualifier_policy
        if document.get("kind") == "NetworkPolicy"
        and document.get("metadata", {}).get("name")
        == "spring-cloud-gateway-ingress"
    )
    gateway_policy["spec"]["ingress"][0]["ports"] = ports
    _, repeated_qualifier_issues = module.validate_gateway_ws_values(
        repeated_qualifier_policy, expected
    )
    if not any(
        expected_fragment in issue for issue in repeated_qualifier_issues
    ) or any(
        "exactly one app=tcp-proxy-service peer rule" in issue
        for issue in repeated_qualifier_issues
    ):
        raise SystemExit(
            f"{label} counted one Gateway policy rule more than once: "
            f"{repeated_qualifier_issues}"
        )

for label, policy_name, policy_types, expected_fragment in (
    (
        "gateway-direction-disabled",
        "spring-cloud-gateway-ingress",
        ["Egress"],
        "Gateway ingress must allow TCP 8443",
    ),
    (
        "proxy-direction-disabled",
        "tcp-proxy-service-egress",
        ["Ingress"],
        "TCP Proxy egress must allow TCP 8443",
    ),
):
    wrong_direction_policy = copy.deepcopy(documents)
    policy = next(
        document
        for document in wrong_direction_policy
        if document.get("kind") == "NetworkPolicy"
        and document.get("metadata", {}).get("name") == policy_name
    )
    policy["spec"]["policyTypes"] = policy_types
    _, direction_issues = module.validate_gateway_ws_values(
        wrong_direction_policy, expected
    )
    if not any(expected_fragment in issue for issue in direction_issues):
        raise SystemExit(f"{label} policy was accepted: {direction_issues}")

PY

echo "Hosted Gateway bridge Helm, NetworkPolicy, and preflight contracts passed"
