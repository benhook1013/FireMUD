#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
POLICY="$ROOT_DIR/k8s/base/gameplay-bridge-network-policy.yaml"

python3 - "$POLICY" <<'PY'
import pathlib
import sys

import yaml

documents = [
    document
    for document in yaml.safe_load_all(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
gateway = next(
    document
    for document in documents
    if document.get("kind") == "NetworkPolicy"
    and document.get("metadata", {}).get("name") == "spring-cloud-gateway-ingress"
)
if gateway["spec"].get("podSelector") != {"matchLabels": {"app": "spring-cloud-gateway"}}:
    raise SystemExit(
        "Gateway NetworkPolicy must select the spring-cloud-gateway app pods: "
        f"{gateway['spec'].get('podSelector')}"
    )
ingress = gateway["spec"]["ingress"]
tcp_proxy_rules = [
    rule
    for rule in ingress
    if rule.get("from") == [{"podSelector": {"matchLabels": {"app": "tcp-proxy-service"}}}]
]
if tcp_proxy_rules != [
    {
        "from": [{"podSelector": {"matchLabels": {"app": "tcp-proxy-service"}}}],
        "ports": [{"protocol": "TCP", "port": 8443}],
    }
]:
    raise SystemExit(f"expected exactly one TCP Proxy TCP/8443 ingress rule: {tcp_proxy_rules}")
traefik_rules = [
    rule
    for rule in ingress
    if rule.get("from") == [
        {
            "namespaceSelector": {
                "matchLabels": {"kubernetes.io/metadata.name": "kube-system"}
            },
            "podSelector": {"matchLabels": {"app.kubernetes.io/name": "traefik"}},
        }
    ]
]
if traefik_rules != [
    {
        "from": [
            {
                "namespaceSelector": {
                    "matchLabels": {"kubernetes.io/metadata.name": "kube-system"}
                },
                "podSelector": {"matchLabels": {"app.kubernetes.io/name": "traefik"}},
            }
        ],
        "ports": [{"protocol": "TCP", "port": 8080}],
    }
]:
    raise SystemExit(f"expected exactly one kube-system Traefik TCP/8080 ingress rule: {traefik_rules}")
public_rules = [
    rule
    for rule in ingress
    if rule.get("from") == [
        {"ipBlock": {"cidr": "0.0.0.0/0"}},
        {"ipBlock": {"cidr": "::/0"}},
    ]
]
if public_rules != [
    {
        "from": [
            {"ipBlock": {"cidr": "0.0.0.0/0"}},
            {"ipBlock": {"cidr": "::/0"}},
        ],
        "ports": [{"protocol": "TCP", "port": 8080}],
    }
]:
    raise SystemExit(f"expected exactly one dual-stack public TCP/8080 ingress rule: {public_rules}")
if len(ingress) != 3:
    raise SystemExit(f"base Gateway policy must contain exactly the three ingress allowlist rules: {ingress}")
if any(
    peer == {"podSelector": {}}
    for rule in ingress
    for peer in rule.get("from", [])
):
    raise SystemExit("base Gateway policy must not allow TCP ingress from every same-namespace pod")
proxy_egress = next(
    document
    for document in documents
    if document.get("kind") == "NetworkPolicy"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service-egress"
)
if proxy_egress["spec"].get("podSelector") != {"matchLabels": {"app": "tcp-proxy-service"}}:
    raise SystemExit(
        "TCP Proxy NetworkPolicy must select the tcp-proxy-service app pods: "
        f"{proxy_egress['spec'].get('podSelector')}"
    )
elasticsearch_rules = [
    rule
    for rule in proxy_egress["spec"]["egress"]
    if rule.get("to") == [{"podSelector": {"matchLabels": {"app": "elasticsearch"}}}]
]
expected_elasticsearch_rule = {
    "to": [{"podSelector": {"matchLabels": {"app": "elasticsearch"}}}],
    "ports": [{"protocol": "TCP", "port": 9200}],
}
if elasticsearch_rules != [expected_elasticsearch_rule]:
    raise SystemExit(
        "base TCP Proxy policy must contain exactly one narrow Elasticsearch TCP/9200 rule: "
        f"{elasticsearch_rules}"
    )
ip_block_rules = [
    rule
    for rule in ingress
    if any("ipBlock" in peer for peer in rule.get("from", []))
]
if any(
    rule.get("ports") != [{"protocol": "TCP", "port": 8080}]
    for rule in ip_block_rules
):
    raise SystemExit(
        "base Gateway public ipBlock peers must be restricted to TCP/8080: "
        f"{ip_block_rules}"
    )
if any(
    port.get("protocol") == "TCP" and port.get("port") == 6565
    for rule in ingress
    for port in rule.get("ports", [])
):
    raise SystemExit("base Gateway policy must not allow same-namespace TCP/6565 ingress")
PY

echo "gameplay bridge base NetworkPolicy contract passed"
